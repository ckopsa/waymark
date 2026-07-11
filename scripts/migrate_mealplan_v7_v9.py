"""One-time data migration: mealplan7 (waymark7) → mealplan9 (waymark9).

The v2→v7 script's pattern, one fork later (current-state-only, by the
same decision): each live resource row is re-created in the v9 schema with
its id, state, and data preserved; the v9 engine recomputes the derived
facts — including the pantry-prices additions this deploy ships
(estimated_total_cents, priced_items, est_grocery_cost_cents, …) — via
``materialize`` and stamps every row with the current law revision. One
synthetic ``create`` transition per row anchors it to the v9 law; v7
history is archival and is not carried.

The v7 and v9 *resource* tables share names (``meals``, ``plans``, …) —
only the engine tables are version-namespaced — so v9 must live in a
SEPARATE database. Source is read-only; nothing in the v7 database is
touched. Rollback is repointing the app at the old database + image.

Events are not migrated: the v9 Event kind is a read-only Mirror of the
family Google Calendar and re-mints from the feed. Ingredient and Product
are new in v9 — they start empty and fill from receipts.

    SOURCE_DSN=postgresql+asyncpg://mealplan@host/mealplan7 \
    TARGET_DSN=postgresql+asyncpg://mealplan@host/mealplan9 \
    uv run python scripts/migrate_mealplan_v7_v9.py

Refuses to run if the target already has rows (idempotency guard); pass
``--force`` to override (drops nothing — it only skips the guard).
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
import uuid
from datetime import UTC, datetime

import waymark9 as waymark
from waymark9.core.summary import render_summary
from waymark9.core.types import Principal
from waymark9.server.engine import header_principal

from mealplan9.resources.event import Event
from mealplan9.resources.grocery_list import GroceryList
from mealplan9.resources.ingredient import Ingredient
from mealplan9.resources.meal import Meal
from mealplan9.resources.plan import MealPlan, WeekBoard
from mealplan9.resources.prep_task import PrepTask
from mealplan9.resources.product import Product
from mealplan9.resources.rotation import SundayRotation
from mealplan9.services import Services

# the migration actor: a system principal, like the deploy tail
MIGRATOR = Principal(id="mealplan-v7-migrate", type="system",
                     display="mealplan v7→v9 migration")

# (kind, v7 table) in dependency order — refs point at rows already in
RESOURCES = [
    ("rotation", "rotations"),
    ("meal", "meals"),
    ("plan", "plans"),
    ("grocery_list", "grocery_lists"),
    ("prep_task", "prep_tasks"),
]


def _asyncpg_dsn(sa_dsn: str) -> str:
    """A SQLAlchemy asyncpg DSN → the plain libpq form asyncpg.connect wants."""
    return sa_dsn.replace("postgresql+asyncpg://", "postgresql://")


async def _load_source_rows(dsn: str, table: str) -> list[dict]:
    import asyncpg

    conn = await asyncpg.connect(_asyncpg_dsn(dsn))
    try:
        rows = await conn.fetch(
            f"SELECT id, state, data, created_at, updated_at "
            f"FROM {table} ORDER BY created_at")
        out = []
        for r in rows:
            data = r["data"]
            out.append({
                "id": r["id"], "state": r["state"],
                "data": json.loads(data) if isinstance(data, str) else data,
                "created_at": r["created_at"], "updated_at": r["updated_at"],
            })
        return out
    finally:
        await conn.close()


async def migrate(source_dsn: str, target_dsn: str, *, force: bool) -> dict:
    engine = waymark.Engine(
        resources=[Meal, SundayRotation, MealPlan, GroceryList, PrepTask,
                   Ingredient, Product, Event],
        surfaces=[WeekBoard],
        storage=target_dsn,
        principal=header_principal,
        services=Services(),
    )
    await engine.startup()  # creates the v9 schema + definition revision 1
    storage = engine.storage
    maintainer = engine.invoker.derived
    counts: dict[str, int] = {}
    try:
        for kind, table in RESOURCES:
            rdef = engine.registry[kind]
            cls = rdef.cls
            # idempotency guard: never double-write a kind
            async with storage.session() as s:
                _, existing = await storage.query(
                    s, kind, filters={}, sort=None, page_size=1,
                    page_number=1, include_rows=False)
            if existing and not force:
                raise SystemExit(
                    f"target already has {existing} {kind} row(s); refusing "
                    "to migrate onto a non-empty target (pass --force to "
                    "override)")

            rows = await _load_source_rows(source_dsn, table)
            for row in rows:
                now = datetime.now(UTC)
                data = cls.Data.model_validate(row["data"])
                inst = cls(
                    id=row["id"], state=row["state"], data=data, version=1,
                    created_at=row["created_at"] or now,
                    updated_at=row["updated_at"] or now, owner=None)
                # the row lives under the current law (the 7.0 §3 stamp) —
                # set before materialize so facts compute under it
                inst.law_revision = rdef.current_law_revision
                async with engine.invoker._flip_session() as s:
                    # recompute the v9-new derived facts + generated columns;
                    # initial materialization is not a flip
                    await maintainer.materialize(s, inst, rdef, now=now)
                    await storage.insert(s, kind, inst)
                    # one synthetic create, anchored to the v9 law (defined_by
                    # resolves through the row's law_revision) — the audit
                    # trail's starting point; v7 history is not carried
                    await engine.invoker._append(
                        s, kind=kind, instance=inst, action="create",
                        from_state="", principal=MIGRATOR, input_digest="",
                        summary=render_summary(rdef.summary_template, inst),
                        at=inst.created_at, correlation_id=uuid.uuid4().hex)
            counts[kind] = len(rows)
            print(f"  {kind:14s} migrated {len(rows)}")
        # second pass: facts that read ACROSS kinds (the plan's grocery
        # Sums) were computed before their source rows existed — the
        # maintainer's backfill recomputes every row now that the whole
        # population is present, the same path a deploy's boot rides
        for kind, _ in RESOURCES:
            n = await maintainer.backfill(kind)
            print(f"  {kind:14s} re-derived {n}")
    finally:
        await engine.shutdown()
    return counts


def main() -> None:
    source = os.environ.get("SOURCE_DSN")
    target = os.environ.get("TARGET_DSN")
    force = "--force" in sys.argv
    if not source or not target:
        raise SystemExit("set SOURCE_DSN and TARGET_DSN")
    if source == target:
        raise SystemExit("SOURCE_DSN and TARGET_DSN must differ (v7 and v9 "
                         "resource tables collide — use a separate database)")
    print(f"migrating {_asyncpg_dsn(source).rsplit('/',1)[-1]} → "
          f"{_asyncpg_dsn(target).rsplit('/',1)[-1]}")
    counts = asyncio.run(migrate(source, target, force=force))
    print(f"done: {counts} (total {sum(counts.values())} rows)")


if __name__ == "__main__":
    main()
