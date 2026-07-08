"""PostgresStorage: engine-owned tables, JSONB data, generated columns for
query affordances, and the single append-only transition log that is the
audit trail, the outbox, the activity feed, and the idempotency anchor.

2.0 changes (design §4, §8):

- Engine tables are ``waymark7_*`` (a v1 and a v2 app can share a database).
- Drafts are keyed ``(kind, resource_id, action, part_key, audience)`` and
  carry per-field ``revs`` and ``authors`` — the storage half of the draft
  sub-resource and waymark-relay/2.
- Date-time fields promote to real ``timestamptz`` generated columns via a
  shipped IMMUTABLE conversion function, so ``*_after`` filters use their
  indexes (v1 knew the fix and deferred it).
- :meth:`schema_snapshot` serializes the declared schema — the input to
  ``waymark7 migrate``'s diff (design §8).
"""
from __future__ import annotations

from datetime import datetime
from typing import Any, AsyncContextManager

from sqlalchemy import (
    BigInteger,
    Boolean,
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
    UniqueConstraint,
    and_,
    func,
    select,
    text,
    update,
)
from sqlalchemy.dialects.postgresql import JSONB, array
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncConnection, AsyncEngine, create_async_engine

from ...core.registry import Registry, ResourceDef
from ...core.resource import FilterOp, Resource
from ...core.schemas import _field_types
from ...core.types import Principal
from .protocol import TransitionRecord

NOTIFY_CHANNEL = "waymark7_transitions"

# action names the engine's own write tails append outside any declared
# machine: creation, and the authority's same-state sync bookkeeping
# (design §8). Part of every kind's action vocabulary for the continuity
# check (design §5) and the replay conformance.
ENGINE_ACTIONS = frozenset({
    "create", "observe_authored",
    "mark_stale", "mark_conflicted", "mark_unreachable",
})


def resolve_renamed(name: str, renames: dict[str, str],
                    known: set[str] | frozenset[str]) -> str | None:
    """Follow a declared rename chain (design §5's continuity map) from a
    historical name forward until it lands in ``known``; None when the
    chain never reaches it (or cycles)."""
    seen: set[str] = set()
    while name not in known:
        if name in seen or name not in renames:
            return None
        seen.add(name)
        name = renames[name]
    return name

# ISO 8601 text → timestamptz. Declared IMMUTABLE: the cast is only STABLE
# in general (session TimeZone affects zoneless input), but every timestamp
# the engine writes carries an explicit offset, for which the conversion is
# genuinely immutable. This is what lets date-time fields be real indexed
# timestamptz generated columns instead of v1's TEXT-cast-at-query-time.
TS_FUNCTION = "waymark7_ts"
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
    if promoted_type == "boolean":
        # a boolean fact (usually a §2 derivation) filters as a real
        # boolean column, not text — ?overdue=true compiles to = true
        return Column(field, Boolean,
                      Computed(f"((data->>'{field}')::boolean)", persisted=True))
    if promoted_type == "array":
        # stays JSONB (no ->> text cast): filtering is containment, and the
        # GIN index below serves both @> (Eq) and ?| (In)
        return Column(field, JSONB,
                      Computed(f"(data->'{field}')", persisted=True))
    return Column(field, Text, Computed(f"(data->>'{field}')", persisted=True))


def table_snapshot(table: Table, dialect: Any) -> dict[str, Any]:
    """One table's declared shape as data — the per-table half of
    :meth:`PostgresStorage.schema_snapshot`, shared with the definition
    fingerprint's storage facet (design §1). Deterministic: columns in
    declaration order, indexes and uniques sorted by name."""
    cols: dict[str, Any] = {}
    for col in table.columns:
        entry: dict[str, Any] = {
            "type": col.type.compile(dialect=dialect),
            "nullable": bool(col.nullable),
        }
        if col.primary_key:
            entry["primary_key"] = True
        if col.computed is not None:
            entry["generated"] = str(col.computed.sqltext)
        cols[col.name] = entry
    out: dict[str, Any] = {
        "columns": cols,
        # JSON-stable shape: a dict round-trips identically, so emit()
        # can compare snapshots by equality
        "indexes": {ix.name: [c.name for c in ix.columns]
                    for ix in sorted(table.indexes, key=lambda i: i.name)},
    }
    uniques = {c.name: [col.name for col in c.columns]
               for c in table.constraints
               if isinstance(c, UniqueConstraint)}
    if uniques:
        # declared uniqueness (design E2) must round-trip through
        # migrate, or the constraint silently exists only in dev
        out["unique"] = dict(sorted(uniques.items()))
    return out


class PostgresStorage:
    def __init__(self, engine: AsyncEngine | str, registry: Registry):
        self.engine = (create_async_engine(engine) if isinstance(engine, str)
                       else engine)
        self.registry = registry
        self.metadata = MetaData()
        self.tables: dict[str, Table] = {}
        self._promoted: dict[str, dict[str, str]] = {}
        self._unique: dict[str, tuple[tuple[str, ...], ...]] = {}
        self._clocked: set[str] = set()  # kinds with Clock-input derivations
        for rdef in registry.defs():
            self._build_table(rdef)

        self.transitions = Table(
            "waymark7_transitions", self.metadata,
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
            # warning-guard names the actor acknowledged past (design E1);
            # NULL = nothing overridden — the common row costs nothing
            Column("acknowledged", JSONB, nullable=True),
            # the anchor (design §3): the definition revision id under which
            # this write was validated, guarded, and rendered. NULL = the
            # row predates the first revise ("pre-law", migration sketch) —
            # honesty about the horizon beats a fabricated anchor.
            Column("defined_by", String(64), nullable=True),
            # declared retention (design 7.0 §5): the validated input
            # payload, stored only where ``record=Inputs()`` (or the
            # kind's unconditional recording) said so. NULL = digest-only,
            # the default — the common row costs nothing. Rolled out by
            # the waymark7_schema_migrations mechanism: the column is
            # nullable, so the emitted ALTER is a plain ADD COLUMN.
            Column("inputs", JSONB, nullable=True),
            Index("ix_waymark7_transitions_resource", "kind", "resource_id", "id"),
        )
        self.idempotency = Table(
            "waymark7_idempotency", self.metadata,
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
            "waymark7_webhook_cursors", self.metadata,
            Column("subscription_id", String(64), primary_key=True),
            Column("last_id", BigInteger, nullable=False),
            Column("updated_at", DateTime(timezone=True), nullable=False),
        )
        # engine-internal consumer cursors (design E4): the cascade runner
        # (and future declared consumers) resume from where they left off —
        # an outage drains instead of dropping
        self.cursors = Table(
            "waymark7_cursors", self.metadata,
            Column("consumer", String(64), primary_key=True),
            Column("last_id", BigInteger, nullable=False),
            Column("updated_at", DateTime(timezone=True), nullable=False),
        )
        # job leases (design E6): a queued/running job belongs to exactly
        # one live worker — claim-or-steal keyed on expiry, so a booting
        # neighbor's orphan sweep cannot cancel a live job and a dead
        # worker's job frees itself by clock
        self.job_leases = Table(
            "waymark7_job_leases", self.metadata,
            Column("job_id", String(64), primary_key=True),
            Column("worker", String(64), nullable=False),
            Column("expires_at", DateTime(timezone=True), nullable=False),
        )
        # the draft sub-resource's row (design §4): keyed per part and per
        # declared audience ("*" = shared, else a principal id); per-field
        # revs/authors are what make relay/2's base_rev/reject enforceable
        self.drafts = Table(
            "waymark7_drafts", self.metadata,
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
        from ...core.derived import has_clock_derived
        from ...core.resource import unique_groups

        promoted = _promoted_fields(rdef)
        self._promoted[rdef.kind] = promoted
        uniques = unique_groups(rdef.cls)
        self._unique[rdef.kind] = uniques
        clocked = has_clock_derived(rdef.cls)
        if clocked:
            self._clocked.add(rdef.kind)
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
        if clocked:
            # the clock consumer's one index (design §3): the next moment
            # any Clock-input derivation on this row can flip — maintained
            # by the engine at every write, swept by engine.tick()
            columns.append(Column("next_flip_at", DateTime(timezone=True),
                                  nullable=True))
            indexes.append(Index(f"ix_{rdef.plural}_next_flip_at",
                                 "next_flip_at"))
        for field, promoted_type in promoted.items():
            columns.append(_generated_column(field, promoted_type))
            if promoted_type == "array":
                indexes.append(Index(f"ix_{rdef.plural}_{field}", field,
                                     postgresql_using="gin"))
            else:
                indexes.append(Index(f"ix_{rdef.plural}_{field}", field))
        # declared uniqueness (design E2): the constraint lives on the
        # promoted columns; its name is how a violation maps back to fields
        constraints = [UniqueConstraint(
            *fields, name=f"uq_{rdef.plural}_{'_'.join(fields)}")
            for fields in uniques]
        table = Table(rdef.plural, self.metadata, *columns, *indexes,
                      *constraints)
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
        instance = cls(
            id=row["id"], state=row["state"], version=row["version"],
            data=cls.Data.model_validate(data),
            created_at=row["created_at"], updated_at=row["updated_at"],
            owner=row["owner"],
        )
        if kind in self._clocked:
            instance.next_flip_at = row["next_flip_at"]
        return instance

    def _clock_values(self, kind: str, instance: Resource) -> dict[str, Any]:
        if kind not in self._clocked:
            return {}
        return {"next_flip_at": getattr(instance, "next_flip_at", None)}

    async def load_many(self, s: AsyncConnection, kind: str,
                        ids: list[str]) -> list[Resource]:
        """One query per relation across a document — the N+1 killer."""
        table = self.tables[kind]
        rows = (await s.execute(
            select(table).where(table.c.id.in_(ids)))).mappings().all()
        return [self._hydrate(kind, r) for r in rows]

    async def insert(self, s: AsyncConnection, kind: str, instance: Resource) -> None:
        table = self.tables[kind]
        try:
            await s.execute(table.insert().values(
                id=instance.id, state=instance.state, version=instance.version,
                data=instance.data.model_dump(mode="json"),
                shape=type(instance).shape, owner=instance.owner,
                created_at=instance.created_at, updated_at=instance.updated_at,
                **self._clock_values(kind, instance),
            ))
        except IntegrityError as exc:
            self._raise_unique(kind, instance, exc)
            raise

    async def save(self, s: AsyncConnection, kind: str, instance: Resource, *,
                   expected_version: int) -> None:
        table = self.tables[kind]
        try:
            result = await s.execute(
                update(table)
                .where(and_(table.c.id == instance.id,
                            table.c.version == expected_version))
                .values(state=instance.state, version=instance.version,
                        data=instance.data.model_dump(mode="json"),
                        shape=type(instance).shape,
                        updated_at=instance.updated_at,
                        **self._clock_values(kind, instance))
            )
        except IntegrityError as exc:
            self._raise_unique(kind, instance, exc)
            raise
        if result.rowcount != 1:
            raise StaleWriteError(
                f"{kind}/{instance.id}: expected version {expected_version} "
                "was gone at write time")

    async def update_data(self, s: AsyncConnection, kind: str,
                          instance: Resource) -> None:
        """Engine maintenance of derived values (design §2): rewrite the
        row's data (and its clock index) without touching state, version,
        or updated_at — those narrate *transitions*, and a derivation flip
        is not one. Callers hold the row (FOR UPDATE) in the same commit
        as the causing write, so this never races a transition's save."""
        table = self.tables[kind]
        await s.execute(
            update(table).where(table.c.id == instance.id)
            .values(data=instance.data.model_dump(mode="json"),
                    **self._clock_values(kind, instance)))

    async def id_page(self, s: AsyncConnection, kind: str, *,
                      after: str | None = None, limit: int = 500) -> list[str]:
        """One keyset page of row ids, in id order (design §4): the
        backfill's walk over a whole table — each page's rows are then
        taken FOR UPDATE and recomputed, so the walk never holds more
        than one batch of locks."""
        table = self.tables[kind]
        stmt = select(table.c.id).order_by(table.c.id).limit(limit)
        if after is not None:
            stmt = stmt.where(table.c.id > after)
        return [row[0] for row in (await s.execute(stmt)).all()]

    async def due_flips(self, s: AsyncConnection, kind: str, now: datetime,
                        limit: int = 200) -> list[Resource]:
        """Rows whose declared clock crossing has come (design §3): one
        indexed sweep of ``next_flip_at <= now``, locked for the flip."""
        table = self.tables[kind]
        stmt = (select(table)
                .where(and_(table.c.next_flip_at.is_not(None),
                            table.c.next_flip_at <= now))
                .order_by(table.c.next_flip_at)
                .limit(limit).with_for_update())
        rows = (await s.execute(stmt)).mappings().all()
        return [self._hydrate(kind, r) for r in rows]

    def _raise_unique(self, kind: str, instance: Resource,
                      exc: IntegrityError) -> None:
        """Map a constraint violation back to its declared field group (by
        the constraint's name); anything else re-raises unmapped."""
        rdef = self.registry.get(kind)
        plural = rdef.plural if rdef else f"{kind}s"
        detail = str(exc.orig or exc)
        dump = instance.data.model_dump(mode="json")
        for fields in self._unique.get(kind, ()):
            name = f"uq_{plural}_{'_'.join(fields)}"
            if name in detail:
                raise UniqueViolation(
                    kind=kind, fields=fields,
                    values={f: dump.get(f) for f in fields}) from exc

    def _rollup_map(self, kind: str) -> dict[str, tuple[Any, Any]]:
        from ...core.owns import owns_of

        rdef = self.registry.get(kind)
        if rdef is None:
            return {}
        return {name: (edge, rollup)
                for edge in owns_of(rdef.cls)
                for name, rollup in edge.rollups.items()}

    def _rollup_expr(self, parent_table: Table, edge: Any, rollup: Any) -> Any:
        """A declared rollup as a correlated scalar subquery — the same
        aggregate the envelope renders, usable in WHERE and ORDER BY
        (design E4: the dashboard's status filter compiles, never
        post-filters)."""
        child = self.tables[edge.kind]
        conds = self._conditions(edge.kind, child, dict(rollup.filters))
        conds.append(child.c[edge.via] == parent_table.c.id)
        measure = (func.count() if rollup.agg == "count"
                   else func.coalesce(func.sum(child.c[rollup.of]), 0))
        return (select(measure).where(and_(*conds))
                .correlate(parent_table).scalar_subquery())

    async def query(self, s: AsyncConnection, kind: str, *, filters: dict[str, Any],
                    sort: str | None, page_size: int,
                    page_number: int,
                    restrict: tuple[str, set[str] | frozenset[str]] | None = None,
                    include_rows: bool = True,
                    ) -> tuple[list[Resource], int]:
        from decimal import Decimal

        table = self.tables[kind]
        rollups = self._rollup_map(kind)
        column_filters, rollup_conds = dict(filters), []
        for name, (edge, rollup) in rollups.items():
            for param, op in ((name, "=="), (f"{name}_gte", ">="),
                              (f"{name}_lte", "<=")):
                value = column_filters.pop(param, None)
                if value is None:
                    continue
                if isinstance(value, float):
                    value = Decimal(str(value))
                expr = self._rollup_expr(table, edge, rollup)
                rollup_conds.append(expr == value if op == "==" else
                                    expr >= value if op == ">=" else
                                    expr <= value)
        conds = self._conditions(kind, table, column_filters)
        conds.extend(rollup_conds)
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
        if not include_rows:
            # ?rows=none (design §9): the count is the same conds the rows
            # would run under — one WHERE, one definition, no drift
            return [], total

        stmt = select(table)
        if conds:
            stmt = stmt.where(and_(*conds))
        if sort:
            descending = sort.startswith("-")
            field = sort.lstrip("-")
            if field in rollups:
                col: Any = self._rollup_expr(table, *rollups[field])
            else:
                col = table.c[field]
            stmt = stmt.order_by(col.desc() if descending else col.asc())
        stmt = stmt.order_by(table.c.id)  # stable tiebreak: pagination walks exactly once
        stmt = stmt.limit(page_size).offset((page_number - 1) * page_size)
        rows = (await s.execute(stmt)).mappings().all()
        return [self._hydrate(kind, r) for r in rows], total

    # the range/join comparison suffixes: _gte/_lte are the public §3
    # grammar; _gt/_lt are internal — minted only by the §2 relation
    # maintainer's inverted/forward predicates (strict On ops), never
    # advertised by the query schema, so the router cannot receive them
    _RANGE_SUFFIXES = (("_gte", ">="), ("_lte", "<="), ("_gt", ">"),
                       ("_lt", "<"))

    def _filter_value(self, kind: str, field: str, value: Any) -> Any:
        """Normalize one comparison value to its promoted column's type
        (design 6.0 §3): ISO strings become datetimes for timestamptz
        promotions (the router and the §2 inverted queries may pass
        either), and date/datetime objects become ISO text for date
        fields promoted as text — the lexicographic order of ISO dates
        IS date order, so the btree serves the comparison."""
        from datetime import date as _date

        ptype = self._promoted.get(kind, {}).get(field)
        if ptype == "date-time" and isinstance(value, str):
            return datetime.fromisoformat(value.replace("Z", "+00:00"))
        if ptype != "date-time" and isinstance(value, (datetime, _date)):
            return value.isoformat()
        return value

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
            suffix = next((s for s, _ in self._RANGE_SUFFIXES
                           if name.endswith(s) and name not in promoted),
                          None)
            if promoted.get(name) == "array":
                # membership, not equality: Eq = tagged with the value (@>),
                # In = tagged with any of them (?|) — both GIN-indexed
                col = table.c[name]
                if isinstance(value, (list, tuple)):
                    conds.append(col.has_any(array([str(v) for v in value])))
                else:
                    conds.append(col.contains([value]))
            elif suffix is not None:
                field = name.removesuffix(suffix)
                op = dict(self._RANGE_SUFFIXES)[suffix]
                col = table.c[field]
                value = self._filter_value(kind, field, value)
                conds.append(col >= value if op == ">=" else
                             col <= value if op == "<=" else
                             col > value if op == ">" else
                             col < value)
            elif name.endswith("_after"):
                field = self._after_field(kind, name)
                if isinstance(value, str):
                    value = datetime.fromisoformat(value.replace("Z", "+00:00"))
                # date-time promotions are real timestamptz columns now —
                # the comparison is indexed, no cast required
                conds.append(table.c[field] > value)
            elif isinstance(value, (list, tuple)):
                conds.append(table.c[name].in_(
                    [self._filter_value(kind, name, v) for v in value]))
            else:
                conds.append(table.c[name] == self._filter_value(kind, name,
                                                                 value))
        return conds

    def _after_field(self, kind: str, param: str) -> str:
        stem = param.removesuffix("_after")
        promoted = self._promoted.get(kind, {})
        for candidate in (f"{stem}_at", stem):
            if candidate in promoted:
                return candidate
        raise KeyError(param)

    async def facets(self, s: AsyncConnection, kind: str, field: str,
                     restrict: tuple[str, set[str] | frozenset[str]] | None = None,
                     ) -> dict[str, int]:
        table = self.tables[kind]
        # the same pushdown as query() (design §9): a restricted principal's
        # facet counts cover exactly the rows their listing covers
        cond = None
        if restrict is not None:
            owner_id, granted_ids = restrict
            cond = table.c.owner == owner_id
            if granted_ids:
                cond = cond | table.c.id.in_(sorted(granted_ids))
        if self._promoted.get(kind, {}).get(field) == "array":
            # per-element counts: FROM <table>, jsonb_array_elements_text(col)
            # (an implicit lateral) — a row tagged twice counts once per tag
            fn = func.jsonb_array_elements_text(table.c[field]).table_valued(
                "value", joins_implicitly=True).render_derived()
            stmt = (select(fn.c.value, func.count())
                    .select_from(table, fn).group_by(fn.c.value))
        else:
            stmt = select(table.c[field], func.count()).group_by(table.c[field])
        if cond is not None:
            stmt = stmt.where(cond)
        rows = await s.execute(stmt)
        return {str(k): v for k, v in rows.all() if k is not None}

    async def append_transition(
        self, s: AsyncConnection, *, kind: str, instance: Resource, action: str,
        from_state: str, principal: Principal, input_digest: str,
        summary: str, at: datetime, correlation_id: str | None = None,
        acknowledged: list[str] | None = None,
        defined_by: str | None = None,
        inputs: dict[str, Any] | None = None,
    ) -> TransitionRecord:
        result = await s.execute(self.transitions.insert().returning(
            self.transitions.c.id).values(
            kind=kind, resource_id=instance.id, action=action,
            from_state=from_state, to_state=instance.state,
            version=instance.version,
            actor_type=principal.type, actor_id=principal.id,
            actor_display=principal.display,
            input_digest=input_digest, correlation_id=correlation_id,
            summary=summary, at=at, acknowledged=acknowledged,
            defined_by=defined_by, inputs=inputs,
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
            summary=summary, at=at, acknowledged=acknowledged,
            defined_by=defined_by, inputs=inputs,
        )

    async def transition_actor(self, s: AsyncConnection, kind: str,
                               resource_id: str, action: str) -> str | None:
        """The actor of the latest ``action`` transition on a resource —
        what a four-eyes guard consults (design E3). Served by the
        (kind, resource_id, id) index."""
        stmt = (select(self.transitions.c.actor_id)
                .where(and_(self.transitions.c.kind == kind,
                            self.transitions.c.resource_id == resource_id,
                            self.transitions.c.action == action))
                .order_by(self.transitions.c.id.desc())
                .limit(1))
        return (await s.execute(stmt)).scalar_one_or_none()

    async def transitions_by_correlation(
            self, s: AsyncConnection,
            correlation_id: str) -> list[TransitionRecord]:
        """Every row of one correlated story (design E8's conformance
        read). Test-surface query; deliberately unindexed."""
        stmt = (select(self.transitions)
                .where(self.transitions.c.correlation_id == correlation_id)
                .order_by(self.transitions.c.id))
        rows = (await s.execute(stmt)).mappings().all()
        return [TransitionRecord(**dict(r)) for r in rows]

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

    async def rollup_counts(self, s: AsyncConnection, kind: str, via: str,
                            parent_ids: list[str],
                            filters: dict[str, Any], *,
                            agg: str = "count",
                            of: str | None = None) -> dict[str, Any]:
        """A declared rollup's aggregate per parent (design E4): one
        GROUP BY per rollup per page, on promoted indexed columns."""
        table = self.tables[kind]
        conds = self._conditions(kind, table, filters)
        conds.append(table.c[via].in_(parent_ids))
        measure = (func.count() if agg == "count"
                   else func.coalesce(func.sum(table.c[of]), 0))
        stmt = (select(table.c[via], measure)
                .where(and_(*conds)).group_by(table.c[via]))
        out: dict[str, Any] = {}
        for k, v in (await s.execute(stmt)).all():
            # Numeric sums arrive as Decimal; the envelope speaks JSON
            out[str(k)] = float(v) if agg == "sum" else v
        return out

    # ── consumer cursors (design E4; CascadeRunner is a writer) ─────────
    async def cursor(self, s: AsyncConnection, consumer: str) -> int | None:
        row = (await s.execute(
            select(self.cursors.c.last_id).where(
                self.cursors.c.consumer == consumer))).scalar_one_or_none()
        return int(row) if row is not None else None

    async def set_cursor(self, s: AsyncConnection, consumer: str,
                         last_id: int, at: datetime) -> None:
        from sqlalchemy.dialects.postgresql import insert as pg_insert

        stmt = pg_insert(self.cursors).values(
            consumer=consumer, last_id=last_id, updated_at=at)
        await s.execute(stmt.on_conflict_do_update(
            index_elements=["consumer"],
            set_={"last_id": last_id, "updated_at": at}))

    # ── job leases (design E6; the job runners are the writers) ─────────
    async def claim_job_lease(self, s: AsyncConnection, job_id: str,
                              worker: str, expires_at: datetime,
                              now: datetime) -> bool:
        """Claim-or-steal: an absent or expired lease moves to ``worker``;
        a live lease held elsewhere stays put. True iff this worker holds
        the lease after the attempt."""
        from sqlalchemy.dialects.postgresql import insert as pg_insert

        stmt = pg_insert(self.job_leases).values(
            job_id=job_id, worker=worker, expires_at=expires_at)
        await s.execute(stmt.on_conflict_do_update(
            index_elements=["job_id"],
            set_={"worker": worker, "expires_at": expires_at},
            where=(self.job_leases.c.expires_at < now)))
        held = (await s.execute(
            select(self.job_leases.c.worker).where(
                self.job_leases.c.job_id == job_id))).scalar_one_or_none()
        return held == worker

    async def renew_job_lease(self, s: AsyncConnection, job_id: str,
                              worker: str, expires_at: datetime) -> None:
        await s.execute(update(self.job_leases).where(
            and_(self.job_leases.c.job_id == job_id,
                 self.job_leases.c.worker == worker)
        ).values(expires_at=expires_at))

    async def release_job_lease(self, s: AsyncConnection, job_id: str,
                                worker: str) -> None:
        await s.execute(self.job_leases.delete().where(
            and_(self.job_leases.c.job_id == job_id,
                 self.job_leases.c.worker == worker)))

    async def job_lease(self, s: AsyncConnection,
                        job_id: str) -> tuple[str, datetime] | None:
        row = (await s.execute(
            select(self.job_leases.c.worker, self.job_leases.c.expires_at)
            .where(self.job_leases.c.job_id == job_id))).one_or_none()
        return (row[0], row[1]) if row is not None else None

    async def max_transition_id(self, s: AsyncConnection) -> int:
        return (await s.execute(
            select(func.coalesce(func.max(self.transitions.c.id), 0)))
        ).scalar_one()

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
        """Boot refusal: every row's state must map onto the machine, and
        (design §5, the continuity map) every action name the transition
        log records must be either in the current machine or reachable
        through the declared ``renamed_actions`` chain — a follower
        scrolling two years of log never hits a name that means nothing
        under any law it can reach. One ``SELECT DISTINCT kind, action``
        over the log; the comparison is Python's."""
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
            history: dict[str, set[str]] = {}
            rows = await conn.execute(
                select(self.transitions.c.kind,
                       self.transitions.c.action).distinct())
            for kind, action in rows:
                history.setdefault(kind, set()).add(action)
            for rdef in self.registry.defs():
                # a kind's declared create spellings (Resource.created_as,
                # design §2 — the definition kind's `revise`) are part of
                # its action vocabulary like the engine's own tails
                known = (set(rdef.machine.actions) | ENGINE_ACTIONS
                         | set(rdef.cls.create_action_names))
                renames = dict(rdef.cls.renamed_actions)
                orphaned = sorted(
                    a for a in history.get(rdef.kind, ())
                    if resolve_renamed(a, renames, known) is None)
                if orphaned:
                    hints = ", ".join(f'"{a}": "<current action>"'
                                      for a in orphaned)
                    raise RuntimeError(
                        f"resource {rdef.kind!r}: the transition log records "
                        f"action(s) {orphaned} that neither the current "
                        "machine nor a declared rename chain reaches; "
                        f"declare renamed_actions={{{hints}}} on "
                        f"{rdef.cls.__name__} (or migrate the log) "
                        "before boot")

    # ── migrations (design §8) ──────────────────────────────────────────
    def schema_snapshot(self) -> dict[str, Any]:
        """The declared schema as data: the input to ``waymark7 migrate``'s
        diff. Deterministic (sorted) so snapshots are diffable in review.
        The per-table serialization is :func:`table_snapshot`, which the
        definition fingerprint (design §1) reuses as its storage facet —
        one serializer, so the migration diff and the law's diff cannot
        disagree about what the schema is."""
        tables = {table.name: table_snapshot(table, self.engine.dialect)
                  for table in self.metadata.sorted_tables}
        return {"function": TS_FUNCTION, "tables": tables}


class StaleWriteError(RuntimeError):
    pass


class UniqueViolation(Exception):
    """A declared uniqueness group was violated (design E2). The invoker
    turns this into the ``already-exists`` Problem carrying a link to the
    conflicting resource."""

    def __init__(self, *, kind: str, fields: tuple[str, ...],
                 values: dict[str, Any]):
        super().__init__(f"{kind}: {dict(values)!r} already exists")
        self.kind = kind
        self.fields = fields
        self.values = values
