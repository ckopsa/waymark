"""One-time data migration: mealplan (waymark2) → mealplan7 (waymark7).

Current-state-only (by decision): each live resource row is re-created in
the v7 schema with its id, state, and input data preserved; the v7 engine
recomputes the new derived facts (end_date, all_days_covered, overdue,
calendar_conflicts, …) via ``materialize`` and stamps every row with the
current definition revision (``law_revision``). One synthetic ``create``
transition per row anchors it to the v7 law so the audit trail has a
starting point — the 534 v2 transitions themselves are NOT carried (the
meal planner uses no four-eyes / as-of replay, so history is archival).

The v2 and v7 *resource* tables share names (``meals``, ``plans``, …) —
only the engine tables are version-namespaced — so v7 must live in a
SEPARATE database. Source is read-only; nothing in the v2 database is
touched. Rollback is repointing the app at the old database + image.

    SOURCE_DSN=postgresql+asyncpg://mealplan@host/mealplan \
    TARGET_DSN=postgresql+asyncpg://mealplan@host/mealplan7 \
    uv run python scripts/migrate_mealplan_v2_v7.py

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

import waymark7 as waymark
from waymark7.core.summary import render_summary
from waymark7.core.types import Principal
from waymark7.server.engine import header_principal

from mealplan7.resources.event import Event
from mealplan7.resources.grocery_list import GroceryList
from mealplan7.resources.meal import Meal
from mealplan7.resources.plan import MealPlan, WeekBoard
from mealplan7.resources.prep_task import PrepTask
from mealplan7.resources.rotation import SundayRotation
from mealplan7.services import Services

# the migration actor: a system principal, like the deploy tail
MIGRATOR = Principal(id="mealplan-v2-migrate", type="system",
                     display="mealplan v2→v7 migration")

# (kind, v2 table) in dependency order — refs point at rows already in
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
        resources=[Meal, SundayRotation, MealPlan, GroceryList, PrepTask, Event],
        surfaces=[WeekBoard],
        storage=target_dsn,
        principal=header_principal,
        services=Services(),
    )
    await engine.startup()  # creates the v7 schema + definition revision 1
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
                # the row lives under the current law (design §3 upgrade
                # stamp) — set before materialize so facts compute under it
                inst.law_revision = rdef.current_law_revision
                async with engine.invoker._flip_session() as s:
                    # recompute the v7-new derived facts + generated columns;
                    # initial materialization is not a flip
                    await maintainer.materialize(s, inst, rdef, now=now)
                    await storage.insert(s, kind, inst)
                    # one synthetic create, anchored to the v7 law (defined_by
                    # resolves through the row's law_revision) — the audit
                    # trail's starting point; v2 history is not carried
                    await engine.invoker._append(
                        s, kind=kind, instance=inst, action="create",
                        from_state="", principal=MIGRATOR, input_digest="",
                        summary=render_summary(rdef.summary_template, inst),
                        at=inst.created_at, correlation_id=uuid.uuid4().hex)
            counts[kind] = len(rows)
            print(f"  {kind:14s} migrated {len(rows)}")
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
        raise SystemExit("SOURCE_DSN and TARGET_DSN must differ (v2 and v7 "
                         "resource tables collide — use a separate database)")
    print(f"migrating {_asyncpg_dsn(source).rsplit('/',1)[-1]} → "
          f"{_asyncpg_dsn(target).rsplit('/',1)[-1]}")
    counts = asyncio.run(migrate(source, target, force=force))
    print(f"done: {counts} (total {sum(counts.values())} rows)")


if __name__ == "__main__":
    main()
