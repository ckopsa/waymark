"""The derivation maintainer (design §2, §3): one definition, one writer.

Every derived field is materialized into the row's ``data`` at every
write that can change it — create, every transition on the resource, and
(for ``Owns``-based inputs) transitions on the owned side — riding the
same commit as the causing transition, exactly as the transition row
does. That is the materialization law: because ``over=`` is declared,
the maintainer knows the complete invalidation map, and a derived fact
recomputed per-request across a collection (``apply_scope`` reborn) is
unrepresentable. Filters, sorts, and facets hit the promoted columns the
values already live under; render reads the same bytes.

Flips are announced as ``derivation``-class events (design §3): the
payload carries the fact, ``from``/``to``, ``at``, and a ``cause`` — the
causing transition's id, or ``"clock"``. Transition-caused flips are
collected while the causing transaction is open and published on the bus
only after it commits (the outbox discipline: a rolled-back write
announces nothing). Nothing new is stored — the transition log plus the
clock is a complete basis, and a consumer that missed a flip re-derives
it from the envelope.

The backfill invariant (design §4): current truth follows the current
law — provably. When a boot's revise (``definitions.py``) marks a fact
**stale by definition** — its ``fn`` source, ``over=`` inputs,
``Tolerance``, or ``flips_at`` changed since the values were
materialized — :meth:`DerivedMaintainer.backfill` recomputes the kind's
rows in batches before the engine serves, or (under a declared
``Deferred``) a background task drains them while the fact is honestly
un-advertised from the query surface and every envelope of the kind says
``meta.recomputing``. What is unrepresentable either way: **serving a
materialized value as current truth when the current law disagrees with
it** — without ``Deferred`` boot does not complete until truth is
current; with it, the stale fact is never served *as filterable truth*
and the envelope says it is recomputing. The backfill emits **no
derivation events** and bumps no versions: per-row flips during a
redefinition are noise — the ``revise`` transition is the one loud
event, exactly as loud as the change deserves — and recomputation is
maintenance, not a transition. Clocked facts refresh their
``next_flip_at`` index in the same pass, so the sweep's schedule follows
the new law too.

The clock consumer: for derivations over :class:`~...core.derived.Clock`
the engine maintains ``next_flip_at`` per row — extracted from the
declared comparison's stored date/datetime (candidate boundaries: the
timestamp itself; UTC midnights around a date), or supplied by the
declaration's ``flips_at=``. ``tick()`` sweeps the one index
(``next_flip_at <= now``), recomputes, stores, and emits with
``cause: "clock"``; a spurious candidate simply advances the index. A
monotonic cursor (the ``waymark5_cursors`` row named ``clock``) refuses
a backwards tick, so a restarted or skewed worker cannot re-announce
crossings it already published.
"""
from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta
from decimal import Decimal
from typing import Any

from ..core.derived import (
    ChildField,
    Clock,
    DerivedSpec,
    derived_specs,
    has_clock_derived,
    ordered_specs,
)
from ..core.types import DefinitionError

CLOCK_CURSOR = "clock"


def _jsonify(value: Any) -> Any:
    if isinstance(value, datetime | date):
        return value.isoformat()
    if isinstance(value, Decimal):
        return float(value)
    return value


def _flip_candidates(value: Any) -> list[datetime]:
    """The moments a ``now``-vs-``value`` comparison can change truth: the
    timestamp itself, or the UTC midnights bounding a date (covering both
    ``now.date() > d`` and ``now.date() >= d``; a candidate where nothing
    flips is swept once, silently, and advances the index)."""
    if isinstance(value, datetime):
        return [value]
    if isinstance(value, date):
        midnight = datetime.combine(value, time.min, tzinfo=UTC)
        return [midnight, midnight + timedelta(days=1)]
    return []


class DerivedMaintainer:
    def __init__(self, registry: Any, storage: Any, *, base: str = "/api",
                 bus: Any = None, clock: Any = None):
        self.registry = registry
        self.storage = storage
        self.base = base
        self.bus = bus
        self.clock = clock or (lambda: datetime.now(UTC))
        # flip sinks per open transaction: the invoker registers one per
        # entry-point session; nested ctx.invoke shares it, so every flip
        # of one act publishes after the one commit
        self._sinks: dict[int, list[dict[str, Any]]] = {}
        # child kind → {(parent kind, via)}: the invalidation map the
        # declarations imply — which transitions dirty whose facts
        self._reverse: dict[str, set[tuple[str, str]]] = {}
        self.clocked_kinds: tuple[str, ...] = ()
        clocked: list[str] = []
        for rdef in registry.defs():
            for spec in derived_specs(rdef.cls.Data).values():
                for cf in spec.child_inputs:
                    self._reverse.setdefault(cf.kind, set()).add(
                        (rdef.kind, cf.via))
            if has_clock_derived(rdef.cls):
                clocked.append(rdef.kind)
        self.clocked_kinds = tuple(clocked)

    # ── declaration views ────────────────────────────────────────────────
    def specs(self, cls: type) -> tuple[tuple[str, DerivedSpec], ...]:
        return ordered_specs(cls.Data)

    def snapshot(self, instance: Any) -> dict[str, Any]:
        """The derived values before a handler runs — the tamper witness."""
        return {name: getattr(instance.data, name, None)
                for name in derived_specs(type(instance).Data)}

    def refuse_tampering(self, rdef: Any, instance: Any, action: str,
                         before: dict[str, Any]) -> None:
        """Derived values are engine-computed only (design §1): a handler
        that assigned one is refused — always, not silently recomputed
        over — the E8 discipline applied to the field surface."""
        for name, old in before.items():
            if getattr(instance.data, name, None) != old:
                raise DefinitionError(
                    f"{rdef.kind}.{action} assigned derived field {name!r} "
                    "— derived values are the maintainer's to write "
                    "(design §1); declare the fact's inputs, not its value")

    def owner_vias(self, kind: str) -> tuple[str, ...]:
        """The Ref fields on ``kind`` whose targets derive over it — a
        write here must recompute there (both old and new parent when the
        ref itself moves)."""
        return tuple(sorted({via for _, via in self._reverse.get(kind, ())}))

    # ── computation ──────────────────────────────────────────────────────
    async def compute(self, s: Any, instance: Any, rdef: Any, *,
                      now: datetime) -> dict[str, Any]:
        """Every derived value, fresh from its declared inputs — the pure
        function the conformance walk replays against stored rows."""
        values: dict[str, Any] = {}
        for name, spec in self.specs(rdef.cls):
            args: list[Any] = []
            for inp in spec.over:
                if inp is Clock:
                    args.append(now)
                elif isinstance(inp, ChildField):
                    args.append(await self._child_values(s, instance, inp))
                elif inp in values:  # a derivation over a derivation
                    args.append(values[inp])
                else:
                    args.append(getattr(instance.data, inp))
            values[name] = spec.apply(args)
        return values

    async def _child_values(self, s: Any, instance: Any,
                            cf: ChildField) -> list[Any]:
        values: list[Any] = []
        filters = {cf.via: instance.id, **dict(cf.where)}
        page = 1
        while True:
            rows, _ = await self.storage.query(
                s, cf.kind, filters=filters, sort=None,
                page_size=200, page_number=page)
            for child in rows:
                if cf.field == "state":
                    values.append(child.state)
                elif cf.field == "id":
                    values.append(child.id)
                else:
                    values.append(getattr(child.data, cf.field))
            if len(rows) < 200:
                return values
            page += 1

    async def materialize(self, s: Any, instance: Any, rdef: Any, *,
                          now: datetime) -> list[tuple[str, Any, Any]]:
        """Recompute every derived field onto the instance and refresh its
        clock index. Returns the flips ``(field, from, to)``; the caller
        persists (the write always rides the causing commit)."""
        specs = self.specs(rdef.cls)
        if not specs:
            return []
        fresh = await self.compute(s, instance, rdef, now=now)
        flips: list[tuple[str, Any, Any]] = []
        for name, _spec in specs:
            old = getattr(instance.data, name, None)
            new = fresh[name]
            if new != old:
                flips.append((name, old, new))
            setattr(instance.data, name, new)
        if rdef.kind in set(self.clocked_kinds):
            instance.next_flip_at = self._next_flip(instance, rdef.cls, now)
        return flips

    def _next_flip(self, instance: Any, cls: type,
                   now: datetime) -> datetime | None:
        """The earliest future moment any Clock derivation on this row can
        change — the maintained value the §3 sweep indexes."""
        moments: list[datetime] = []
        for _name, spec in ordered_specs(cls.Data):
            if not spec.has_clock:
                continue
            if spec.flips_at is not None:
                t = spec.flips_at(instance)
                if t is not None and t > now:
                    moments.append(t)
                continue
            comparand = spec.clock_comparand
            value = getattr(instance.data, comparand, None)
            moments.extend(c for c in _flip_candidates(value) if c > now)
        return min(moments) if moments else None

    # ── the backfill invariant (design §4) ───────────────────────────────
    async def backfill(self, kind: str, *, batch: int = 500,
                       pause: float = 0.0) -> int:
        """Recompute every row of ``kind`` under the current law: pages of
        ids (keyset, one batch of ``FOR UPDATE`` locks at a time), then
        the ordinary materialize + ``update_data`` per row — the same
        maintainer path every write rides, minus the announcement. No
        sink is open and nothing records, so the backfill emits no
        derivation events and bumps no versions (see the module
        docstring); ``next_flip_at`` refreshes with the values. Returns
        the number of rows recomputed."""
        import asyncio

        rdef = self.registry[kind]
        after: str | None = None
        total = 0
        while True:
            now = self.clock()
            async with self.storage.session() as s:
                ids = await self.storage.id_page(s, kind, after=after,
                                                 limit=batch)
                for row_id in ids:
                    instance = await self.storage.load(s, kind, row_id,
                                                       for_update=True)
                    if instance is None:
                        continue
                    await self.materialize(s, instance, rdef, now=now)
                    await self.storage.update_data(s, kind, instance)
                    total += 1
            if len(ids) < batch:
                return total
            after = ids[-1]
            if pause:
                await asyncio.sleep(pause)

    # ── the invalidation map's other direction ───────────────────────────
    async def recompute_owners(self, s: Any, instance: Any, rdef: Any,
                               before: dict[str, Any] | None, *,
                               now: datetime, cause: Any) -> None:
        """A child wrote; the parents' facts may have moved. Both the old
        and the new parent recompute when the ``via`` ref itself was
        reassigned — E8's carve-out move dirties two wires, and both must
        tell the truth. Rides the same commit; the parent row is taken FOR
        UPDATE so concurrent children serialize."""
        for parent_kind, via in sorted(self._reverse.get(rdef.kind, ())):
            ids: set[str] = set()
            current = getattr(instance.data, via, None)
            if current:
                ids.add(str(current))
            previous = (before or {}).get(via)
            if previous:
                ids.add(str(previous))
            for pid in sorted(ids):
                parent = await self.storage.load(s, parent_kind, pid,
                                                 for_update=True)
                if parent is None:
                    continue
                prdef = self.registry[parent_kind]
                flips = await self.materialize(s, parent, prdef, now=now)
                if flips:
                    await self.storage.update_data(s, parent_kind, parent)
                    self.record(s, prdef, parent.id, flips, cause=cause,
                                at=now)

    # ── flips: collected in the commit, published after it ───────────────
    def open_sink(self, s: Any) -> list[dict[str, Any]]:
        sink: list[dict[str, Any]] = []
        self._sinks[id(s)] = sink
        return sink

    def close_sink(self, s: Any) -> None:
        self._sinks.pop(id(s), None)

    def record(self, s: Any, rdef: Any, resource_id: str,
               flips: list[tuple[str, Any, Any]], *, cause: Any,
               at: datetime) -> None:
        sink = self._sinks.get(id(s))
        if sink is None:
            # values are already stored (the truth); the event is only an
            # index into it — a path without a sink loses liveness, never
            # truth, and consumers re-derive on fetch (design §3)
            return
        for fact, old, new in flips:
            sink.append({
                "event": "derivation", "class": "derivation",
                "kind": rdef.kind,
                "self": f"{self.base}/{rdef.plural}/{resource_id}",
                "fact": fact, "from": _jsonify(old), "to": _jsonify(new),
                "at": at.isoformat(), "cause": cause,
                # the anchor (design §3): the law under which the flip was
                # computed — the current law at emit, so a third party
                # that stored the fact can later ask whether its
                # definition changed since
                "defined_by": rdef.current_law,
            })

    async def publish(self, payloads: list[dict[str, Any]]) -> None:
        if self.bus is None:
            return
        from .events import DERIVATION_CHANNEL

        for payload in payloads:
            await self.bus.publish(DERIVATION_CHANNEL, payload)

    # ── the clock consumer (design §3) ───────────────────────────────────
    async def tick(self, now: datetime | None = None) -> int:
        """Flip the facts whose time has come: sweep ``next_flip_at <=
        now`` per clocked kind, recompute, store, announce with
        ``cause: "clock"``. Returns the number of flips. Idempotent by
        construction — a re-sweep recomputes to the same values and the
        index only ever advances — and monotonic by cursor: a tick behind
        the last one is refused, so restarts neither skip nor repeat."""
        now = now or self.clock()
        if not self.clocked_kinds:
            return 0
        now_ms = int(now.timestamp() * 1000)
        async with self.storage.session() as s:
            last = await self.storage.cursor(s, CLOCK_CURSOR)
        if last is not None and now_ms < last:
            return 0
        total = 0
        for kind in self.clocked_kinds:
            rdef = self.registry[kind]
            while True:
                payloads: list[dict[str, Any]] = []
                async with self.storage.session() as s:
                    rows = await self.storage.due_flips(s, kind, now,
                                                        limit=200)
                    for instance in rows:
                        flips = await self.materialize(s, instance, rdef,
                                                       now=now)
                        await self.storage.update_data(s, kind, instance)
                        for fact, old, new in flips:
                            payloads.append({
                                "event": "derivation", "class": "derivation",
                                "kind": kind,
                                "self": f"{self.base}/{rdef.plural}/"
                                        f"{instance.id}",
                                "fact": fact, "from": _jsonify(old),
                                "to": _jsonify(new), "at": now.isoformat(),
                                "cause": "clock",
                                # the law the flip was computed under (§3)
                                "defined_by": rdef.current_law,
                            })
                await self.publish(payloads)  # after the sweep's commit
                total += len(payloads)
                if len(rows) < 200:
                    break
        async with self.storage.session() as s:
            await self.storage.set_cursor(s, CLOCK_CURSOR, now_ms, now)
        return total
