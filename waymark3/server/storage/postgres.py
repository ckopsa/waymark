"""PostgresStorage: engine-owned tables, JSONB data, generated columns for
query affordances, and the single append-only transition log that is the
audit trail, the outbox, the activity feed, and the idempotency anchor.

2.0 changes (design §4, §8):

- Engine tables are ``waymark3_*`` (a v1 and a v2 app can share a database).
- Drafts are keyed ``(kind, resource_id, action, part_key, audience)`` and
  carry per-field ``revs`` and ``authors`` — the storage half of the draft
  sub-resource and waymark-relay/2.
- Date-time fields promote to real ``timestamptz`` generated columns via a
  shipped IMMUTABLE conversion function, so ``*_after`` filters use their
  indexes (v1 knew the fix and deferred it).
- :meth:`schema_snapshot` serializes the declared schema — the input to
  ``waymark3 migrate``'s diff (design §8).
"""
from __future__ import annotations

from datetime import datetime
from typing import Any, AsyncContextManager

from sqlalchemy import (
    BigInteger,
    Column,
    Computed,
    DateTime,
    Identity,
    Index,
    Integer,
    LargeBinary,
    MetaData,
    Numeric,
    String,
    Table,
    Text,
    and_,
    func,
    select,
    text,
    update,
)
from sqlalchemy.dialects.postgresql import JSONB, array
from sqlalchemy.ext.asyncio import AsyncConnection, AsyncEngine, create_async_engine

from ...core.registry import Registry, ResourceDef
from ...core.resource import FilterOp, Resource
from ...core.schemas import _field_types
from ...core.types import Principal
from .protocol import TransitionRecord

NOTIFY_CHANNEL = "waymark3_transitions"

# ISO 8601 text → timestamptz. Declared IMMUTABLE: the cast is only STABLE
# in general (session TimeZone affects zoneless input), but every timestamp
# the engine writes carries an explicit offset, for which the conversion is
# genuinely immutable. This is what lets date-time fields be real indexed
# timestamptz generated columns instead of v1's TEXT-cast-at-query-time.
TS_FUNCTION = "waymark3_ts"
TS_FUNCTION_DDL = f"""
CREATE OR REPLACE FUNCTION {TS_FUNCTION}(value text)
RETURNS timestamptz
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
RETURN value::timestamptz
"""


def _promoted_fields(rdef: ResourceDef) -> dict[str, str]:
    """filterable ∪ sortable fields (minus ``state``) → promoted type name
    (``integer`` / ``number`` / ``date-time`` / ``string``).

    A declared ``Vocab`` field promotes to ``array`` *by declaration*
    (design §6) — the schema-shape inference below remains only for plain
    list-typed filterables."""
    from ...core.vocab import model_vocabs

    cls = rdef.cls
    names: set[str] = set()
    if cls.filterable is not None:
        names |= set(cls.filterable.fields)
    if cls.sortable is not None:
        names |= set(cls.sortable.fields)
    names.discard("state")
    vocabs = model_vocabs(cls.Data)
    types = _field_types(cls)
    out: dict[str, str] = {}
    for n in sorted(names):
        if n in vocabs:
            out[n] = "array"
            continue
        t = types.get(n, {})
        if t.get("format") == "date-time":
            out[n] = "date-time"
        else:
            out[n] = t.get("type", "string")
    return out


def _generated_column(field: str, promoted_type: str) -> Column:
    """Promote a JSONB field to an indexed generated column."""
    if promoted_type == "integer":
        return Column(field, BigInteger,
                      Computed(f"((data->>'{field}')::bigint)", persisted=True))
    if promoted_type == "number":
        return Column(field, Numeric,
                      Computed(f"((data->>'{field}')::numeric)", persisted=True))
    if promoted_type == "date-time":
        return Column(field, DateTime(timezone=True),
                      Computed(f"{TS_FUNCTION}(data->>'{field}')", persisted=True))
    if promoted_type == "array":
        # stays JSONB (no ->> text cast): filtering is containment, and the
        # GIN index below serves both @> (Eq) and ?| (In)
        return Column(field, JSONB,
                      Computed(f"(data->'{field}')", persisted=True))
    return Column(field, Text, Computed(f"(data->>'{field}')", persisted=True))


class PostgresStorage:
    def __init__(self, engine: AsyncEngine | str, registry: Registry):
        self.engine = (create_async_engine(engine) if isinstance(engine, str)
                       else engine)
        self.registry = registry
        self.metadata = MetaData()
        self.tables: dict[str, Table] = {}
        self._promoted: dict[str, dict[str, str]] = {}
        for rdef in registry.defs():
            self._build_table(rdef)

        self.transitions = Table(
            "waymark3_transitions", self.metadata,
            Column("id", BigInteger, Identity(), primary_key=True),  # global ordering
            Column("kind", String(64), nullable=False),
            Column("resource_id", String(64), nullable=False),
            Column("action", String(128), nullable=False),
            Column("from_state", String(64), nullable=False),
            Column("to_state", String(64), nullable=False),
            Column("version", Integer, nullable=False),
            Column("actor_type", String(16), nullable=False),
            Column("actor_id", String(128), nullable=False),
            Column("actor_display", String(256), nullable=False, default=""),
            Column("input_digest", String(64), nullable=False),
            Column("correlation_id", String(64), nullable=True),
            Column("summary", Text, nullable=False),
            Column("at", DateTime(timezone=True), nullable=False),
            Index("ix_waymark3_transitions_resource", "kind", "resource_id", "id"),
        )
        self.idempotency = Table(
            "waymark3_idempotency", self.metadata,
            Column("key", String(256), primary_key=True),
            Column("kind", String(64), nullable=False),
            Column("action", String(128), nullable=False),
            Column("body_digest", String(64), nullable=False),
            Column("status", Integer, nullable=False),
            Column("response_body", LargeBinary, nullable=False),
            Column("media_type", String(128), nullable=False),
            Column("created_at", DateTime(timezone=True), nullable=False),
        )
        # webhook delivery cursors (design §10): the outbox is the log,
        # deliveries resume from where each subscription left off — the
        # DraftStore pattern (engine-owned row, single writer, not a
        # transition) applied to delivery bookkeeping
        self.webhook_cursors = Table(
            "waymark3_webhook_cursors", self.metadata,
            Column("subscription_id", String(64), primary_key=True),
            Column("last_id", BigInteger, nullable=False),
            Column("updated_at", DateTime(timezone=True), nullable=False),
        )
        # the draft sub-resource's row (design §4): keyed per part and per
        # declared audience ("*" = shared, else a principal id); per-field
        # revs/authors are what make relay/2's base_rev/reject enforceable
        self.drafts = Table(
            "waymark3_drafts", self.metadata,
            Column("kind", String(64), primary_key=True),
            Column("resource_id", String(64), primary_key=True),
            Column("action", String(128), primary_key=True),
            Column("part_key", String(128), primary_key=True, default=""),
            Column("audience", String(128), primary_key=True),
            Column("values", JSONB, nullable=False),
            Column("revs", JSONB, nullable=False),
            Column("authors", JSONB, nullable=False),
            Column("base_version", Integer, nullable=False),
            Column("saved_at", DateTime(timezone=True), nullable=False),
        )

    def _build_table(self, rdef: ResourceDef) -> None:
        promoted = _promoted_fields(rdef)
        self._promoted[rdef.kind] = promoted
        columns = [
            Column("id", String(64), primary_key=True),
            Column("state", String(64), nullable=False, index=True),
            Column("version", Integer, nullable=False, default=1),
            Column("data", JSONB, nullable=False),
            # declared data-shape version (design §8): the write path stamps
            # the class's current shape; _hydrate upcasts older rows lazily
            Column("shape", Integer, nullable=False, default=1,
                   server_default="1"),
            # ownership (design §9): the creating actor, stamped at insert —
            # the log already knew; this denormalizes it so visibility rules
            # ("full over what you created") evaluate at projection and push
            # into collection SQL. Indexed for exactly that pushdown.
            Column("owner", String(128), nullable=True, index=True),
            Column("created_at", DateTime(timezone=True), nullable=False),
            Column("updated_at", DateTime(timezone=True), nullable=False),
        ]
        indexes = []
        for field, promoted_type in promoted.items():
            columns.append(_generated_column(field, promoted_type))
            if promoted_type == "array":
                indexes.append(Index(f"ix_{rdef.plural}_{field}", field,
                                     postgresql_using="gin"))
            else:
                indexes.append(Index(f"ix_{rdef.plural}_{field}", field))
        table = Table(rdef.plural, self.metadata, *columns, *indexes)
        self.tables[rdef.kind] = table
        rdef.row_model = table

    # ── protocol ────────────────────────────────────────────────────────
    def session(self) -> AsyncContextManager[AsyncConnection]:
        return self.engine.begin()

    async def load(self, s: AsyncConnection, kind: str, id: str, *,
                   for_update: bool = False) -> Resource | None:
        table = self.tables[kind]
        stmt = select(table).where(table.c.id == id)
        if for_update:
            stmt = stmt.with_for_update()
        row = (await s.execute(stmt)).mappings().first()
        return self._hydrate(kind, row) if row else None

    def _hydrate(self, kind: str, row: Any) -> Resource:
        cls = self.registry[kind].cls
        data = row["data"]
        stored_shape = row.get("shape", 1) if hasattr(row, "get") else row["shape"]
        if stored_shape > cls.shape:
            raise RuntimeError(
                f"{kind}/{row['id']}: stored shape {stored_shape} is newer "
                f"than the declared shape {cls.shape} — refusing to guess")
        if stored_shape < cls.shape:
            # lazy read-time migration through the declared chain (design §8)
            data = dict(data)
            for n in range(stored_shape, cls.shape):
                data = cls.upcasts[n](data)
        return cls(
            id=row["id"], state=row["state"], version=row["version"],
            data=cls.Data.model_validate(data),
            created_at=row["created_at"], updated_at=row["updated_at"],
            owner=row["owner"],
        )

    async def load_many(self, s: AsyncConnection, kind: str,
                        ids: list[str]) -> list[Resource]:
        """One query per relation across a document — the N+1 killer."""
        table = self.tables[kind]
        rows = (await s.execute(
            select(table).where(table.c.id.in_(ids)))).mappings().all()
        return [self._hydrate(kind, r) for r in rows]

    async def insert(self, s: AsyncConnection, kind: str, instance: Resource) -> None:
        table = self.tables[kind]
        await s.execute(table.insert().values(
            id=instance.id, state=instance.state, version=instance.version,
            data=instance.data.model_dump(mode="json"),
            shape=type(instance).shape, owner=instance.owner,
            created_at=instance.created_at, updated_at=instance.updated_at,
        ))

    async def save(self, s: AsyncConnection, kind: str, instance: Resource, *,
                   expected_version: int) -> None:
        table = self.tables[kind]
        result = await s.execute(
            update(table)
            .where(and_(table.c.id == instance.id,
                        table.c.version == expected_version))
            .values(state=instance.state, version=instance.version,
                    data=instance.data.model_dump(mode="json"),
                    shape=type(instance).shape,
                    updated_at=instance.updated_at)
        )
        if result.rowcount != 1:
            raise StaleWriteError(
                f"{kind}/{instance.id}: expected version {expected_version} "
                "was gone at write time")

    async def query(self, s: AsyncConnection, kind: str, *, filters: dict[str, Any],
                    sort: str | None, page_size: int,
                    page_number: int,
                    restrict: tuple[str, set[str] | frozenset[str]] | None = None,
                    ) -> tuple[list[Resource], int]:
        table = self.tables[kind]
        conds = self._conditions(kind, table, filters)
        if restrict is not None:
            # visibility pushdown (design §9): "what you own, plus what you
            # were granted" is WHERE, never post-filtering rendered envelopes
            owner_id, granted_ids = restrict
            cond = table.c.owner == owner_id
            if granted_ids:
                cond = cond | table.c.id.in_(sorted(granted_ids))
            conds.append(cond)
        count_stmt = select(func.count()).select_from(table)
        if conds:
            count_stmt = count_stmt.where(and_(*conds))
        total = (await s.execute(count_stmt)).scalar_one()

        stmt = select(table)
        if conds:
            stmt = stmt.where(and_(*conds))
        if sort:
            descending = sort.startswith("-")
            field = sort.lstrip("-")
            col = table.c[field]
            stmt = stmt.order_by(col.desc() if descending else col.asc())
        stmt = stmt.order_by(table.c.id)  # stable tiebreak: pagination walks exactly once
        stmt = stmt.limit(page_size).offset((page_number - 1) * page_size)
        rows = (await s.execute(stmt)).mappings().all()
        return [self._hydrate(kind, r) for r in rows], total

    def _conditions(self, kind: str, table: Table, filters: dict[str, Any]) -> list[Any]:
        from decimal import Decimal

        promoted = self._promoted.get(kind, {})
        conds: list[Any] = []
        for name, value in filters.items():
            if value is None:
                continue
            if isinstance(value, float):
                # bind by decimal string: a float's binary expansion
                # (84.2 → 84.2000…0028) must not defeat NUMERIC comparison
                value = Decimal(str(value))
            if promoted.get(name) == "array":
                # membership, not equality: Eq = tagged with the value (@>),
                # In = tagged with any of them (?|) — both GIN-indexed
                col = table.c[name]
                if isinstance(value, (list, tuple)):
                    conds.append(col.has_any(array([str(v) for v in value])))
                else:
                    conds.append(col.contains([value]))
            elif name.endswith("_gte"):
                conds.append(table.c[name.removesuffix("_gte")] >= value)
            elif name.endswith("_lte"):
                conds.append(table.c[name.removesuffix("_lte")] <= value)
            elif name.endswith("_after"):
                field = self._after_field(kind, name)
                if isinstance(value, str):
                    value = datetime.fromisoformat(value.replace("Z", "+00:00"))
                # date-time promotions are real timestamptz columns now —
                # the comparison is indexed, no cast required
                conds.append(table.c[field] > value)
            elif isinstance(value, (list, tuple)):
                conds.append(table.c[name].in_(list(value)))
            else:
                conds.append(table.c[name] == value)
        return conds

    def _after_field(self, kind: str, param: str) -> str:
        stem = param.removesuffix("_after")
        promoted = self._promoted.get(kind, {})
        for candidate in (f"{stem}_at", stem):
            if candidate in promoted:
                return candidate
        raise KeyError(param)

    async def facets(self, s: AsyncConnection, kind: str,
                     field: str) -> dict[str, int]:
        table = self.tables[kind]
        if self._promoted.get(kind, {}).get(field) == "array":
            # per-element counts: FROM <table>, jsonb_array_elements_text(col)
            # (an implicit lateral) — a row tagged twice counts once per tag
            fn = func.jsonb_array_elements_text(table.c[field]).table_valued(
                "value", joins_implicitly=True).render_derived()
            rows = await s.execute(
                select(fn.c.value, func.count())
                .select_from(table, fn).group_by(fn.c.value))
        else:
            rows = await s.execute(
                select(table.c[field], func.count()).group_by(table.c[field]))
        return {str(k): v for k, v in rows.all() if k is not None}

    async def append_transition(
        self, s: AsyncConnection, *, kind: str, instance: Resource, action: str,
        from_state: str, principal: Principal, input_digest: str,
        summary: str, at: datetime, correlation_id: str | None = None,
    ) -> TransitionRecord:
        result = await s.execute(self.transitions.insert().returning(
            self.transitions.c.id).values(
            kind=kind, resource_id=instance.id, action=action,
            from_state=from_state, to_state=instance.state,
            version=instance.version,
            actor_type=principal.type, actor_id=principal.id,
            actor_display=principal.display,
            input_digest=input_digest, correlation_id=correlation_id,
            summary=summary, at=at,
        ))
        tid = result.scalar_one()
        # transactional outbox: NOTIFY is delivered iff this txn commits
        await s.execute(
            text(f"SELECT pg_notify('{NOTIFY_CHANNEL}', :payload)"),
            {"payload": str(tid)},
        )
        return TransitionRecord(
            id=tid, kind=kind, resource_id=instance.id, action=action,
            from_state=from_state, to_state=instance.state,
            version=instance.version, actor_type=principal.type,
            actor_id=principal.id, actor_display=principal.display,
            input_digest=input_digest, correlation_id=correlation_id,
            summary=summary, at=at,
        )

    async def last_transition(self, s: AsyncConnection, kind: str,
                              resource_id: str) -> TransitionRecord | None:
        stmt = (select(self.transitions)
                .where(and_(self.transitions.c.kind == kind,
                            self.transitions.c.resource_id == resource_id))
                .order_by(self.transitions.c.id.desc())
                .limit(1))
        row = (await s.execute(stmt)).mappings().first()
        return TransitionRecord(**dict(row)) if row else None

    async def transitions_since(self, s: AsyncConnection, after_id: int,
                                kinds: list[str] | None = None,
                                limit: int = 500) -> list[TransitionRecord]:
        stmt = (select(self.transitions)
                .where(self.transitions.c.id > after_id)
                .order_by(self.transitions.c.id)
                .limit(limit))
        if kinds:
            stmt = stmt.where(self.transitions.c.kind.in_(kinds))
        rows = (await s.execute(stmt)).mappings().all()
        return [TransitionRecord(**dict(r)) for r in rows]

    # ── webhook cursors (design §10; WebhookDeliverer is the writer) ────
    async def webhook_cursor(self, s: AsyncConnection,
                             subscription_id: str) -> int | None:
        row = (await s.execute(
            select(self.webhook_cursors.c.last_id).where(
                self.webhook_cursors.c.subscription_id == subscription_id)
        )).scalar_one_or_none()
        return int(row) if row is not None else None

    async def set_webhook_cursor(self, s: AsyncConnection,
                                 subscription_id: str, last_id: int,
                                 at: datetime) -> None:
        from sqlalchemy.dialects.postgresql import insert as pg_insert

        stmt = pg_insert(self.webhook_cursors).values(
            subscription_id=subscription_id, last_id=last_id, updated_at=at)
        await s.execute(stmt.on_conflict_do_update(
            index_elements=["subscription_id"],
            set_={"last_id": last_id, "updated_at": at}))

    # ── drafts (the sub-resource's row; DraftStore is the write path) ───
    async def save_draft(self, s: AsyncConnection, *, kind: str,
                         resource_id: str, action: str, part_key: str,
                         audience: str, values: dict, revs: dict,
                         authors: dict, base_version: int, at: Any) -> None:
        from sqlalchemy.dialects.postgresql import insert as pg_insert

        stmt = pg_insert(self.drafts).values(
            kind=kind, resource_id=resource_id, action=action,
            part_key=part_key, audience=audience, values=values, revs=revs,
            authors=authors, base_version=base_version, saved_at=at)
        await s.execute(stmt.on_conflict_do_update(
            index_elements=["kind", "resource_id", "action", "part_key",
                            "audience"],
            set_={"values": values, "revs": revs, "authors": authors,
                  "base_version": base_version, "saved_at": at}))

    async def load_drafts(self, s: AsyncConnection, kind: str,
                          resource_id: str) -> dict[tuple[str, str, str], dict]:
        """All draft rows for a resource, keyed (action, part_key, audience).
        One query; audience filtering is the caller's declared policy."""
        stmt = select(self.drafts).where(and_(
            self.drafts.c.kind == kind,
            self.drafts.c.resource_id == resource_id))
        rows = (await s.execute(stmt)).mappings().all()
        return {(r["action"], r["part_key"], r["audience"]): dict(r)
                for r in rows}

    async def delete_draft(self, s: AsyncConnection, kind: str,
                           resource_id: str, action: str, part_key: str,
                           audience: str) -> None:
        await s.execute(self.drafts.delete().where(and_(
            self.drafts.c.kind == kind,
            self.drafts.c.resource_id == resource_id,
            self.drafts.c.action == action,
            self.drafts.c.part_key == part_key,
            self.drafts.c.audience == audience)))

    # ── lifecycle ───────────────────────────────────────────────────────
    async def create_all(self) -> None:
        async with self.engine.begin() as conn:
            # the conversion function must exist before generated columns use it
            await conn.execute(text(TS_FUNCTION_DDL))
            await conn.run_sync(self.metadata.create_all)

    async def drop_all(self) -> None:
        async with self.engine.begin() as conn:
            await conn.run_sync(self.metadata.drop_all)

    async def check_state_tokens(self) -> None:
        """Boot refusal: every row's state must map onto the machine."""
        async with self.engine.connect() as conn:
            for rdef in self.registry.defs():
                table = self.tables[rdef.kind]
                known = set(rdef.machine.states) | set(rdef.cls.renames)
                rows = await conn.execute(select(table.c.state).distinct())
                unmapped = {r[0] for r in rows} - known
                if unmapped:
                    raise RuntimeError(
                        f"resource {rdef.kind!r}: rows occupy unmapped states "
                        f"{sorted(unmapped)}; declare renames or migrate before boot")

    # ── migrations (design §8) ──────────────────────────────────────────
    def schema_snapshot(self) -> dict[str, Any]:
        """The declared schema as data: the input to ``waymark3 migrate``'s
        diff. Deterministic (sorted) so snapshots are diffable in review."""
        tables: dict[str, Any] = {}
        for table in self.metadata.sorted_tables:
            cols: dict[str, Any] = {}
            for col in table.columns:
                entry: dict[str, Any] = {
                    "type": col.type.compile(dialect=self.engine.dialect),
                    "nullable": bool(col.nullable),
                }
                if col.primary_key:
                    entry["primary_key"] = True
                if col.computed is not None:
                    entry["generated"] = str(col.computed.sqltext)
                cols[col.name] = entry
            tables[table.name] = {
                "columns": cols,
                # JSON-stable shape: a dict round-trips identically, so
                # emit() can compare snapshots by equality
                "indexes": {ix.name: [c.name for c in ix.columns]
                            for ix in sorted(table.indexes,
                                             key=lambda i: i.name)},
            }
        return {"function": TS_FUNCTION, "tables": tables}


class StaleWriteError(RuntimeError):
    pass
