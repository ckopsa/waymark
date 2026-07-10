"""The derivation maintainer (design §2, §3): one definition, one writer.

Every derived field is materialized into the row's ``data`` at every
write that can change it — create, every transition on the resource, and
(for ``Owns``/``Related``-based inputs) writes on the other side of the
edge — riding the same commit as the causing transition, exactly as the
transition row does. That is the materialization law: because ``over=``
is declared, the maintainer knows the complete invalidation map, and a
derived fact recomputed per-request across a collection (``apply_scope``
reborn) is unrepresentable. For ``Owns`` inputs the reverse map is a
scalar FK dereference; for ``Related`` inputs (design 6.0 §2) it is the
INVERTED predicate — an indexed query over the anchors' promoted join
columns, run for both the old and the new target values, dirtying the
set. Invertibility is checked at import (``check_related``), because it
is the guarantee, not an optimization. Filters, sorts, and facets hit the promoted columns the
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
monotonic cursor (the ``waymark8_cursors`` row named ``clock``) refuses
a backwards tick, so a restarted or skewed worker cannot re-announce
crossings it already published.
"""
from __future__ import annotations

from dataclasses import dataclass, field, replace as dc_replace
from datetime import UTC, date, datetime, time, timedelta
from decimal import Decimal
from typing import Any, Mapping

from ..core.derived import (
    ChildField,
    Clock,
    DerivedSpec,
    Tolerance,
    derived_specs,
    has_clock_derived,
    ordered_specs,
)
from ..core.related import On, RelatedField, forward_filters, inverted_filters
from ..core.types import DefinitionError

CLOCK_CURSOR = "clock"


@dataclass
class LawOverride:
    """One derived fact's CURRENT-law parameters (design 7.0 §1): the
    stored values the §1 overlay serves while a data-law proposal is held
    at ``proposed``. Built from the current revision's stored fingerprint
    — the definition store holds every revision's serialized form, and a
    ``Tolerance`` literal / ``where=`` filter is recoverable from it
    exactly because it is data, which is the §4 data/code boundary made
    operational: a ``fn`` is only a hash there, so code cannot overlay."""

    tolerance: str | None = None
    # over-index → the where= filters the current law declares there
    where: Mapping[int, Mapping[str, Any]] = field(default_factory=dict)


def _overlaid(spec: DerivedSpec, ov: LawOverride) -> DerivedSpec:
    """The resident spec with the current law's stored parameters in
    place of the resident declaration's — the one substitution the §1
    overlay performs."""
    over = list(spec.over)
    for i, w in ov.where.items():
        inp = over[i]
        if isinstance(inp, (ChildField, RelatedField)):
            over[i] = dc_replace(inp, where=dict(w))
    tolerance = spec.tolerance
    if ov.tolerance is not None:
        tolerance = Tolerance(ov.tolerance)
    return dc_replace(spec, over=tuple(over), tolerance=tolerance)


def _jsonify(value: Any) -> Any:
    if isinstance(value, datetime | date):
        return value.isoformat()
    if isinstance(value, Decimal):
        return float(value)
    return value


class _FreshView:
    """The compute pass's view of the anchor's own fields: values already
    computed THIS pass shadow the stored ones (design §2 — a derived join
    key must be read at this pass's value; see ``_related_values``)."""

    def __init__(self, fresh: dict[str, Any], data: Any):
        self._fresh = fresh
        self._data = data

    def __getattr__(self, name: str) -> Any:
        if name in self._fresh:
            return self._fresh[name]
        return getattr(self._data, name, None)


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
        # target kind → ((anchor kind, predicate), ...): the reverse-query
        # descriptors for Related inputs (design 6.0 §2) — a write on the
        # target runs the INVERTED predicate over its old and new field
        # values and dirties the resulting anchor SET, not a FK's worth
        # of parents. Deduplicated (many fields over one edge is one
        # descriptor) and sorted, so recompute order is deterministic.
        reverse_related: dict[str, set[tuple[str, tuple[On, ...]]]] = {}
        self.clocked_kinds: tuple[str, ...] = ()
        clocked: list[str] = []
        for rdef in registry.defs():
            for spec in derived_specs(rdef.cls.Data).values():
                for cf in spec.child_inputs:
                    self._reverse.setdefault(cf.kind, set()).add(
                        (rdef.kind, cf.via))
                for rf in spec.related_inputs:
                    reverse_related.setdefault(rf.kind, set()).add(
                        (rdef.kind, rf.on))
            if has_clock_derived(rdef.cls):
                clocked.append(rdef.kind)
        self._reverse_related: dict[str, tuple[tuple[str, tuple[On, ...]],
                                               ...]] = {
            kind: tuple(sorted(entries, key=lambda e: (
                e[0], tuple((c.ours, c.op, c.theirs) for c in e[1]))))
            for kind, entries in reverse_related.items()}
        self.clocked_kinds = tuple(clocked)
        # the law overlay (design 7.0 §1): (kind, fact) → the CURRENT
        # revision's stored parameters, installed by a propose-mode boot
        # that held a data-law diff at ``proposed``, dropped at promote.
        # This is the SERVED law's overlay — what a row under the current
        # revision computes through while the resident code is a held
        # proposal or a live pilot.
        self.overlay: dict[tuple[str, str], LawOverride] = {}
        # the row-law overlay (design 7.0 §3): (kind, revision NUMBER) →
        # {fact → LawOverride}, one entry per NON-current revision that
        # still has rows living under it (a grandfathered law). Built from
        # that revision's stored fingerprint — recoverable exactly because
        # a grandfathered/piloted law's diff is data (design §4); a code
        # diff's rows compute under the resident fn with their revision's
        # stored parameters, which is as far as the overlay can honestly
        # reach (recorded deviation). Installed by the boot revise and the
        # promote/grandfather flow; dropped when the revision supersedes.
        self.law_overlay: dict[tuple[str, int], dict[str, LawOverride]] = {}

    # ── declaration views ────────────────────────────────────────────────
    def specs(self, cls: type) -> tuple[tuple[str, DerivedSpec], ...]:
        """The resident declaration's specs — input SHAPE only (which
        edges/kinds feed which facts). Value computation goes through
        :meth:`specs_for`, which resolves the law."""
        return ordered_specs(cls.Data)

    def specs_for(self, rdef: Any, *, revision: str | int | None = None
                  ) -> tuple[tuple[str, DerivedSpec], ...]:
        """Spec resolution under a law (design 7.0 §1/§3/§4 — THE per-row
        seam). ``revision`` is the ROW's ``law_revision`` stamp (an int),
        or None for the law the engine serves. Three cases, kept honest:

        - the row is under the CURRENT revision (or None): the resident
          declaration, parameter-overlaid with the current revision's
          stored ``Tolerance``/``where=`` values while a data-law proposal
          is held or a pilot is live;
        - the row is under the PILOTED revision: the resident declaration
          verbatim — the resident code IS the piloted/proposed law;
        - the row is under any OTHER revision (grandfathered): the
          resident declaration with THAT revision's stored parameters
          (``law_overlay``, built from its fingerprint).

        The held proposal's revision row *id* (a str) is also accepted and
        returns the resident declaration verbatim — the Phase 1 spelling,
        kept for the blast-radius seam."""
        base = ordered_specs(rdef.cls.Data)
        if revision is not None \
                and revision == getattr(rdef, "proposed_law", None):
            return base
        if isinstance(revision, int):
            per = self.law_overlay.get((rdef.kind, revision))
            if per:
                # a non-resident revision with rows living under it — a
                # grandfathered law, or a pilot riding its stored
                # parameters while the current law's code is resident
                return tuple(
                    (name, _overlaid(spec, ov)
                     if (ov := per.get(name)) is not None else spec)
                    for name, spec in base)
            if revision == getattr(rdef, "piloted_law_revision", None):
                return base
            # any other non-current revision with no installed overlay
            # (nothing about its data-law parameters differs, or it
            # superseded while this row was in flight): the served law is
            # the honest fallback
        if not self.overlay:
            return base
        return tuple(
            (name, _overlaid(spec, ov)
             if (ov := self.overlay.get((rdef.kind, name))) is not None
             else spec)
            for name, spec in base)

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

    def related_dirties(self, kind: str) -> bool:
        """Does any registered kind derive over ``kind`` through a Related
        edge (design 6.0 §2)? When true, every write on ``kind`` needs its
        ``before`` values — the inverted predicate runs over old AND new,
        so a moved row dirties both anchor sets."""
        return bool(self._reverse_related.get(kind))

    # ── computation ──────────────────────────────────────────────────────
    async def compute(self, s: Any, instance: Any, rdef: Any, *,
                      now: datetime, revision: str | int | None = None,
                      specs: tuple[tuple[str, DerivedSpec], ...] | None = None
                      ) -> dict[str, Any]:
        """Every derived value, fresh from its declared inputs — the pure
        function the conformance walk replays against stored rows.
        ``revision`` resolves which law's parameters compute (design 7.0
        §1/§3; None defaults to the ROW's ``law_revision`` stamp — every
        fact computes under the law of its row, everywhere); ``specs``
        bypasses resolution with an explicit set — the §2 blast-radius
        meter's "compute this row under the proposed parameters" read."""
        if revision is None:
            revision = getattr(instance, "law_revision", None)
        values: dict[str, Any] = {}
        for name, spec in (specs if specs is not None
                           else self.specs_for(rdef, revision=revision)):
            args: list[Any] = []
            for inp in spec.over:
                if inp is Clock:
                    args.append(now)
                elif isinstance(inp, ChildField):
                    args.append(await self._child_values(s, instance, inp))
                elif isinstance(inp, RelatedField):
                    args.append(await self._related_values(
                        s, instance, inp, fresh=values))
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

    async def _related_values(self, s: Any, instance: Any,
                              rf: RelatedField, *,
                              fresh: dict[str, Any] | None = None) -> list[Any]:
        """The forward read over a Related edge (design 6.0 §2): the
        anchor's own stored values, through the predicate, select the
        target rows on their promoted columns — one indexed query, the
        exact mirror of the inverted one. A null join value relates to
        nothing.

        ``fresh`` is the compute pass's accumulating values: a join key
        that is itself derived (a plan's ``end_date``) must be read at
        THIS pass's value, not the previous materialization's —
        ``ordered_specs`` guarantees the boundary computed first; this
        parameter makes the forward read see it. Without it, an action
        that moved the window would relate against the old one, one
        write late — the exact disagreement §2 declares unrepresentable."""
        source = instance.data if not fresh else _FreshView(fresh,
                                                            instance.data)
        filters = forward_filters(rf.on, source)
        if filters is None:
            return []
        filters = {**filters, **dict(rf.where)}
        values: list[Any] = []
        page = 1
        while True:
            rows, _ = await self.storage.query(
                s, rf.kind, filters=filters, sort=None,
                page_size=200, page_number=page)
            for row in rows:
                if rf.field == "state":
                    values.append(row.state)
                elif rf.field == "id":
                    values.append(row.id)
                else:
                    values.append(getattr(row.data, rf.field))
            if len(rows) < 200:
                return values
            page += 1

    async def materialize(self, s: Any, instance: Any, rdef: Any, *,
                          now: datetime, revision: str | int | None = None
                          ) -> list[tuple[str, Any, Any]]:
        """Recompute every derived field onto the instance and refresh its
        clock index. Returns the flips ``(field, from, to)``; the caller
        persists (the write always rides the causing commit).
        ``revision`` is the law seam, threaded to :meth:`compute` — None
        defaults to the ROW's ``law_revision`` stamp (design 7.0 §3: one
        row, one law, on every write path), which for a current-stamped
        row is the served law, overlay included."""
        if revision is None:
            revision = getattr(instance, "law_revision", None)
        specs = self.specs_for(rdef, revision=revision)
        if not specs:
            return []
        fresh = await self.compute(s, instance, rdef, now=now,
                                   revision=revision)
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
        the number of rows recomputed.

        The law's row half (design 7.0 §3): for an ``adoption=Immediate``
        kind the backfill IS the bulk adopt — each row restamps to the
        current revision before recomputing, which is today's behavior
        spelled out (per-row adopt transitions would be noise; the revise
        or promote is the one loud event). An ``adoption=Never`` kind's
        rows keep their stamp and their values: only rows already under
        the current revision recompute — the grandfathered live under
        their birth law until an explicit ``adopt``."""
        import asyncio

        from ..core.owns import Never

        rdef = self.registry[kind]
        grandfathers = rdef.cls.adoption is Never
        current = getattr(rdef, "current_law_revision", None)
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
                    stamp = getattr(instance, "law_revision", None)
                    if stamp is not None and stamp == getattr(
                            rdef, "piloted_law_revision", None):
                        # a live pilot's rows are under the resident law
                        # already — a backfill of the kind must neither
                        # restamp nor recompute them out of the pilot
                        continue
                    if grandfathers and stamp is not None \
                            and stamp != current:
                        continue  # the row finishes under its birth law
                    if not grandfathers and current is not None:
                        instance.law_revision = current  # the bulk adopt
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
        """A child (or related target) wrote; the other side's facts may
        have moved. For ``Owns`` inputs both the old and the new parent
        recompute when the ``via`` ref itself was reassigned — E8's
        carve-out move dirties two wires, and both must tell the truth.
        For ``Related`` inputs the same discipline generalizes from two
        ids to two sets (design 6.0 §2). Rides the same commit; every
        dirtied row is taken FOR UPDATE so concurrent writes serialize.

        Chaining (the inputs-and-identities wave): a dirtied row whose
        facts FLIPPED is itself a write someone may derive over —
        break → account.unexplained → workbook.all_accounts_reconciled,
        or, in the new child→parent direction, a workbook transition
        flipping an account's fact flipping a break's gate. Each flip
        propagates through this same method, same commit, same cause (the
        causing transition), so every fact downstream of one act tells
        the truth in that act's own response. Termination is the
        assembly-checked acyclicity of the cross-kind fact graph
        (``checks.check_derived_cycles``): a row with no flips propagates
        nothing, and each hop settles a strictly deeper fact."""
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
                parent_before = parent.data.model_dump(mode="json")
                flips = await self.materialize(s, parent, prdef, now=now)
                if flips:
                    await self.storage.update_data(s, parent_kind, parent)
                    self.record(s, prdef, parent.id, flips, cause=cause,
                                at=now)
                    await self.recompute_owners(s, parent, prdef,
                                                parent_before,
                                                now=now, cause=cause)
        await self._recompute_related(s, instance, rdef, before,
                                      now=now, cause=cause)

    async def _recompute_related(self, s: Any, instance: Any, rdef: Any,
                                 before: dict[str, Any] | None, *,
                                 now: datetime, cause: Any) -> None:
        """The inverted predicate (design 6.0 §2): a write on a related
        target dirties the SET of anchors its old and new join values
        select — the ``before`` discipline above, generalized from two
        ids to two sets, unioned. There is no delete case to special-case:
        waymark has no hard delete, so "the row left the relation" is
        always a write whose before and after both evaluate — a terminal
        transition keeps its join values, the same anchors recompute, and
        a ``where=`` that excludes the new state settles the fact through
        the forward read. The recompute rides the causing commit, anchors
        locked FOR UPDATE, flips published through the outbox after — the
        materialization law, verbatim."""
        descriptors = self._reverse_related.get(rdef.kind, ())
        if not descriptors:
            return
        # identity is not in the data dumps: model_dump never carries the
        # primary key, but an identity join's inverted filter reads
        # values["id"] — ride the row's id alongside both value sets (it
        # never changes between old and new, so the two identity filters
        # dedupe below exactly as any unmoved join value does)
        current = instance.data.model_dump(mode="json")
        current["id"] = instance.id
        for anchor_kind, on in descriptors:
            queries = []
            filters_new = inverted_filters(on, current)
            if filters_new is not None:
                queries.append(filters_new)
            if before is not None:
                filters_old = inverted_filters(on, {**before,
                                                    "id": instance.id})
                if filters_old is not None and filters_old not in queries:
                    queries.append(filters_old)
            ids: set[str] = set()
            for filters in queries:
                page = 1
                while True:
                    rows, _ = await self.storage.query(
                        s, anchor_kind, filters=filters, sort=None,
                        page_size=200, page_number=page)
                    ids.update(r.id for r in rows)
                    if len(rows) < 200:
                        break
                    page += 1
            ardef = self.registry[anchor_kind]
            for aid in sorted(ids):
                if anchor_kind == rdef.kind and aid == instance.id:
                    continue  # a self-related row is its own write's job
                anchor = await self.storage.load(s, anchor_kind, aid,
                                                 for_update=True)
                if anchor is None:
                    continue
                anchor_before = anchor.data.model_dump(mode="json")
                flips = await self.materialize(s, anchor, ardef, now=now)
                if flips:
                    await self.storage.update_data(s, anchor_kind, anchor)
                    self.record(s, ardef, anchor.id, flips, cause=cause,
                                at=now)
                    # chaining, same as the Owns direction: a flipped
                    # anchor is a write someone may derive over
                    await self.recompute_owners(s, anchor, ardef,
                                                anchor_before,
                                                now=now, cause=cause)

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
