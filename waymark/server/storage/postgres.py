"""PostgresStorage (§11.1–11.2): engine-owned tables, JSONB data, generated
columns for query affordances, and the single append-only transition log that
is the audit trail, the outbox, the activity feed, and the idempotency anchor.
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
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.asyncio import AsyncConnection, AsyncEngine, create_async_engine

from ...core.registry import Registry, ResourceDef
from ...core.resource import FilterOp, Resource
from ...core.schemas import _field_types
from ...core.types import Principal
from .protocol import TransitionRecord

NOTIFY_CHANNEL = "waymark_transitions"


def _promoted_fields(rdef: ResourceDef) -> dict[str, str]:
    """filterable ∪ sortable fields (minus ``state``) → JSON type name."""
    cls = rdef.cls
    names: set[str] = set()
    if cls.filterable is not None:
        names |= set(cls.filterable.fields)
    if cls.sortable is not None:
        names |= set(cls.sortable.fields)
    names.discard("state")
    types = _field_types(cls)
    return {n: types.get(n, {}).get("type", "string") for n in sorted(names)}


def _generated_column(field: str, json_type: str) -> Column:
    """Promote a JSONB field to an indexed generated column (§11.1).

    Numeric casts are immutable and safe in generated columns; date-time
    fields are promoted as TEXT (ISO 8601) and cast at query time — the
    ``::timestamptz`` cast is only STABLE, which Postgres rejects in
    generated-column expressions.
    """
    if json_type == "integer":
        return Column(field, BigInteger,
                      Computed(f"((data->>'{field}')::bigint)", persisted=True))
    if json_type == "number":
        return Column(field, Numeric,
                      Computed(f"((data->>'{field}')::numeric)", persisted=True))
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
            "waymark_transitions", self.metadata,
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
            Index("ix_waymark_transitions_resource", "kind", "resource_id", "id"),
        )
        self.idempotency = Table(
            "waymark_idempotency", self.metadata,
            Column("key", String(256), primary_key=True),
            Column("kind", String(64), nullable=False),
            Column("action", String(128), nullable=False),
            Column("body_digest", String(64), nullable=False),
            Column("status", Integer, nullable=False),
            Column("response_body", LargeBinary, nullable=False),
            Column("media_type", String(128), nullable=False),
            Column("created_at", DateTime(timezone=True), nullable=False),
        )

    def _build_table(self, rdef: ResourceDef) -> None:
        promoted = _promoted_fields(rdef)
        self._promoted[rdef.kind] = promoted
        columns = [
            Column("id", String(64), primary_key=True),
            Column("state", String(64), nullable=False, index=True),
            Column("version", Integer, nullable=False, default=1),
            Column("data", JSONB, nullable=False),
            Column("created_at", DateTime(timezone=True), nullable=False),
            Column("updated_at", DateTime(timezone=True), nullable=False),
        ]
        indexes = []
        for field, json_type in promoted.items():
            columns.append(_generated_column(field, json_type))
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
        return cls(
            id=row["id"], state=row["state"], version=row["version"],
            data=cls.Data.model_validate(row["data"]),
            created_at=row["created_at"], updated_at=row["updated_at"],
        )

    async def load_many(self, s: AsyncConnection, kind: str,
                        ids: list[str]) -> list[Resource]:
        """One query per relation across a document (§12) — the N+1 killer."""
        table = self.tables[kind]
        rows = (await s.execute(
            select(table).where(table.c.id.in_(ids)))).mappings().all()
        return [self._hydrate(kind, r) for r in rows]

    async def insert(self, s: AsyncConnection, kind: str, instance: Resource) -> None:
        table = self.tables[kind]
        await s.execute(table.insert().values(
            id=instance.id, state=instance.state, version=instance.version,
            data=instance.data.model_dump(mode="json"),
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
                    updated_at=instance.updated_at)
        )
        if result.rowcount != 1:
            raise StaleWriteError(
                f"{kind}/{instance.id}: expected version {expected_version} "
                "was gone at write time")

    async def query(self, s: AsyncConnection, kind: str, *, filters: dict[str, Any],
                    sort: str | None, page_size: int,
                    page_number: int) -> tuple[list[Resource], int]:
        table = self.tables[kind]
        conds = self._conditions(kind, table, filters)
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
            col = self._sort_expr(kind, table, field)
            stmt = stmt.order_by(col.desc() if descending else col.asc())
        stmt = stmt.order_by(table.c.id)  # stable tiebreak: pagination walks exactly once
        stmt = stmt.limit(page_size).offset((page_number - 1) * page_size)
        rows = (await s.execute(stmt)).mappings().all()
        return [self._hydrate(kind, r) for r in rows], total

    def _sort_expr(self, kind: str, table: Table, field: str) -> Any:
        col = table.c[field]
        if self._promoted.get(kind, {}).get(field) == "string" and \
                self._is_datetime(kind, field):
            return col.cast(DateTime(timezone=True))
        return col

    def _is_datetime(self, kind: str, field: str) -> bool:
        types = _field_types(self.registry[kind].cls)
        return types.get(field, {}).get("format") == "date-time"

    def _conditions(self, kind: str, table: Table, filters: dict[str, Any]) -> list[Any]:
        from decimal import Decimal

        conds: list[Any] = []
        for name, value in filters.items():
            if value is None:
                continue
            if isinstance(value, float):
                # bind by decimal string: a float's binary expansion
                # (84.2 → 84.2000…0028) must not defeat NUMERIC comparison
                value = Decimal(str(value))
            if name.endswith("_gte"):
                conds.append(table.c[name.removesuffix("_gte")] >= value)
            elif name.endswith("_lte"):
                conds.append(table.c[name.removesuffix("_lte")] <= value)
            elif name.endswith("_after"):
                field = self._after_field(kind, name)
                if isinstance(value, str):
                    value = datetime.fromisoformat(value.replace("Z", "+00:00"))
                conds.append(table.c[field].cast(DateTime(timezone=True)) > value)
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
        rows = await s.execute(
            select(table.c[field], func.count()).group_by(table.c[field]))
        return {str(k): v for k, v in rows.all()}

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

    # ── lifecycle ───────────────────────────────────────────────────────
    async def create_all(self) -> None:
        async with self.engine.begin() as conn:
            await conn.run_sync(self.metadata.create_all)

    async def drop_all(self) -> None:
        async with self.engine.begin() as conn:
            await conn.run_sync(self.metadata.drop_all)

    async def check_state_tokens(self) -> None:
        """Boot refusal (§16): every row's state must map onto the machine."""
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


class StaleWriteError(RuntimeError):
    pass
