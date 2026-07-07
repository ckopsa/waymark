"""The definition resource (design §1–§2): the law is inside the envelope.

Everything inside the boundary has been a resource since §9 made identity
one — members, grants, jobs, webhooks, attachments — everything except
the definition module that produces them all. It lived in git, outside
the envelope, and a deploy changed the law silently mid-history. This
kind closes that: one row per **revision** per target kind, whose
``data`` is the canonical fingerprint (``core/fingerprint.py``) — the
record and the anchor, never the source. Git still holds the text; this
holds "which text was live, when, and what it meant" — the same division
of labor the transition log already has with the database.

- The machine is small and honest: ``current → superseded`` by one
  transition, plus one self-transition (``settle``) that clears the
  revision's catch-up marker when its backfill commits. A boot whose
  registry hash differs creates revision N+1 as ``current`` and
  supersedes revision N **in the same transaction, under one
  correlation id** — the deploy reads as one story in the log, rides
  the outbox, and is joinable by anything that can read a transition.
- Read-only on the wire: creation and supersession are guarded to the
  system deploy actor, so the only writer is the boot
  (:func:`revise_definitions`) — the write path for code is git and
  review (design §7's refusal), and the wire gets safe reads: the
  collection IS the deploy history, rendered by the generic UI like any
  other kind.
- A rollback is just another revise: when the new fingerprint matches a
  prior revision's hash, the diff carries ``reverts_to`` and the
  transition summary names the reversion — the undeclared mass
  transition of meaning becomes a declared one (design §2).
- One row for the whole deploy: the ``__registry__`` target's
  fingerprint is the sorted map of kind → hash, so "the deploy" is one
  revise even when many kinds changed.

Boot ordering: :func:`revise_definitions` runs in ``engine.startup()``
right after the state-token check and before any other startup consumer
writes (orphan-job sweeps, cascades) — definition rows must exist before
anything anchors to them (``engine.current_law``, the §3 seam). The
transitions land before the dispatcher starts, which is deliberate: a
server not yet serving has no live subscribers to push to; the log has
them (``Last-Event-ID`` replays them) and webhook cursors drain them
like any backlog. The revise also returns §4's work order — the derived
facts whose semantic surface the diff touched, **stale by definition** —
which ``startup()`` backfills (or marks recomputing under a declared
``Deferred``) before the dispatcher and clock start: nothing downstream
can observe a materialized value the current law disagrees with.
"""
from __future__ import annotations

import logging
import uuid
from collections import Counter
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, Field

from ..core.actions import action
from ..core.fingerprint import (
    diff_fingerprints,
    fingerprint_hash,
    fingerprint_of,
    stale_facts,
    surface_fingerprint,
)
from ..core.guards import Guard
from ..core.resource import Resource, filterable, sortable
from ..core.types import Acknowledged, Allow, Ctx, Deny, Principal, Safety
from .idempotency import body_digest

# the deploy actor (design §2): the process identity that revises the law
# at boot — a system principal like the engine's other write tails
DEPLOY = Principal(id="waymark6-deploy", type="system", display="Deploy")

# the registry-level target: one row records the whole deploy — the
# sorted map of kind → fingerprint hash — even when many kinds changed
REGISTRY_KIND = "__registry__"

# rollback detection scans this many most-recent revisions per kind; a
# reversion past that horizon still revises, just without the name
REVISION_SCAN = 1000

log = logging.getLogger("waymark6.definitions")


async def _deploy_check(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
    return Allow() if ctx.principal.type == "system" else Deny()


def _deploy_only() -> Guard:
    """The law is boot-written (design §7: the write path for code is git
    and review). One guard, two surfaces: as a create guard it refuses a
    wire client minting fake law; on ``supersede`` it renders the action
    honestly unavailable to humans and agents, with the same sentence
    the 409 carries."""
    return Guard(
        name="deploy_writes_the_law",
        explain="The law is revised by the deploy at boot, never over "
                "the wire.",
        check=_deploy_check, reads=("principal",),
    )


class DefinitionState(StrEnum):
    CURRENT = "current"
    SUPERSEDED = "superseded"


class DefinitionData(BaseModel):
    target_kind: str = Field(
        min_length=1, max_length=64,
        description="The resource kind this revision governs "
                    "(the registry row uses a reserved token)",
        json_schema_extra={"x-display": {"raw": True}})
    revision: int = Field(
        ge=1, description="1-based per target kind; the deploy history")
    fingerprint_hash: str = Field(
        min_length=64, max_length=64,
        description="sha256 of the canonical fingerprint JSON",
        json_schema_extra={"x-display": {"raw": True}})
    # the fingerprint IS the record (design §1): everything §5's replay
    # check and §7's as-of reads would need, stored where history can
    # join it
    fingerprint: dict[str, Any] = Field(
        description="The canonical description of the declaration",
        json_schema_extra={"x-display": {"raw": True}})
    # vs the previous revision: added/removed/changed paths, each tagged
    # advertisement | judgment | truth | shape (§4 owns the class
    # policies; a revise only needs the tags), plus reverts_to on a
    # rollback. None on revision 1 — there was no prior law to differ from
    diff: dict[str, Any] | None = Field(
        default=None,
        description="What changed since the previous revision",
        json_schema_extra={"x-display": {"raw": True}})
    change_summary: str = Field(
        default="the law as first recorded", max_length=80,
        description="One line naming what this revision did")
    # the durable catch-up marker (design §4). It lives HERE — on the
    # revision row, written in the revise's own transaction — rather than
    # in an engine bookkeeping row (the ``waymark6_cursors`` precedent),
    # for two reasons. Atomicity is structural, not protocol: the marker
    # and the law change are one row, so "the law moved but the debt was
    # lost" is unrepresentable — no second table to fall out of sync with
    # the revise, no cross-table transaction to get right. And the
    # catch-up lifecycle is auditable where the deploy already is: the
    # marker appears in the revision's data, and the ``settle``
    # transition that clears it lands in the same log the ``revise``
    # did — the deploy history reads "law changed, truth caught up" as
    # two joined transitions instead of a row in a table nothing renders.
    # The machine cost is one self-transition (``settle``), which stays
    # small. A boot that crashes between its revise and its backfill
    # re-boots into matching hashes, but this field survives on the
    # current row and re-detection resumes the debt.
    backfill_pending: list[str] | None = Field(
        default=None,
        description="Derived facts this revision marked stale by "
                    "definition (or inherited unpaid from the revision "
                    "it superseded) whose rows have not yet been "
                    "recomputed; cleared by `settle` when the backfill "
                    "commits",
        json_schema_extra={"x-display": {"raw": True}})


class Definition(Resource):
    kind = "definition"
    State = DefinitionState
    Data = DefinitionData

    initial = DefinitionState.CURRENT
    terminal = {DefinitionState.SUPERSEDED}

    # orients across the deploy history: which law, which revision, what
    # it did — never the row id (the href carries that)
    summary = ("Law of {data.target_kind} · revision {data.revision} · "
               "{data.change_summary} · {state.label}")

    filterable = filterable(
        state=filterable.Eq | filterable.In,
        target_kind=filterable.Eq | filterable.In,
        revision=filterable.Range,
    )
    # revision order IS the deploy history; the collection defaults to it
    sortable = sortable("revision", default="revision")
    faceted = ("target_kind",)
    # two racing boots cannot double-mint a revision: the constraint is
    # the database's, and the loser's UniqueViolation aborts its boot
    unique = (("target_kind", "revision"),)

    display = {"title": "Definition — {data.target_kind}"}

    create_guards = (_deploy_only(),)

    # deploys are `revise` transitions (design §2): the create of revision
    # N+1 IS the deploy, and the log names it honestly. Revision 1 stays
    # `create` — nothing was revised; the law was first recorded. The
    # declared vocabulary covers both spellings, so history from boots
    # that logged every revision as `create` stays reachable (`create`
    # is an engine action for every kind) while new deploys read as what
    # they are. One invoker path either way — see Resource.created_as.
    create_action_names = frozenset({"create", "revise"})

    def created_as(self) -> str:
        return "revise" if self.data.revision > 1 else "create"

    @action(from_=DefinitionState.CURRENT, to=DefinitionState.SUPERSEDED,
            guards=(_deploy_only(),),
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Superseding is the boot's bookkeeping: the "
                              "revision row is immutable history either "
                              "way, and a rollback is a new revision, "
                              "never an un-supersede.")),
            display=dict(label="Supersede", order=9,
                         description="A newer revision of this law has "
                                     "taken effect"))
    async def supersede(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=DefinitionState.CURRENT, to=DefinitionState.CURRENT,
            guards=(_deploy_only(),),
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Settle", order=8,
                         description="The backfill this revision demanded "
                                     "has committed; truth is current "
                                     "again"))
    async def settle(self, inp: None, ctx: Ctx) -> None:
        """Clear the catch-up marker (design §4): invoked by the boot's
        immediate backfill or the Deferred drain — the same system deploy
        actor that wrote the revise — only after the recompute of every
        row has committed. Until this transition lands, every boot
        re-detects the debt from ``backfill_pending`` and recomputes
        again (idempotent by construction: recompute writes the same
        values). A self-transition, not a state: the row stays
        ``current`` — the law did not move, the truth caught up."""
        self.data.backfill_pending = None


# ── §2: revise at boot ───────────────────────────────────────────────────
def _describe(diff: dict[str, Any]) -> str:
    counts = Counter(entry["class"]
                     for key in ("added", "removed", "changed")
                     for entry in diff.get(key, ()))
    if not counts:
        return "revised with no path-level change"
    parts = ", ".join(f"{n} {cls}" for cls, n in sorted(counts.items()))
    return f"changes: {parts}"[:80]


def _still_declared(target_kind: str, marked: Any,
                    declared: dict[str, Any]) -> tuple[str, ...]:
    """Filter a persisted catch-up marker to the facts the current law
    still declares. A marker naming a since-removed fact is dropped with
    a log line — the fact is gone; there is nothing to recompute."""
    kept: list[str] = []
    for fact in tuple(marked or ()):
        if fact in declared:
            kept.append(fact)
        else:
            log.info(
                "dropping backfill marker %s.%s: the current law no "
                "longer declares the fact", target_kind, fact)
    return tuple(kept)


async def _revise_kind(invoker: Any, correlation: str, target_kind: str,
                       fp: dict[str, Any], fp_hash: str
                       ) -> tuple[str, int, tuple[str, ...]]:
    """Compare one target's stored law to the fresh fingerprint; write
    nothing, or write revision N+1 and supersede N in one transaction.
    Returns the id of the target's current definition row and its
    revision NUMBER (the human spelling ``meta.law_revision`` renders
    beside the id, design §3), plus the
    derived facts awaiting recompute under it: the facts this revise
    marked **stale by definition** (design §4: the diff touched their
    semantic surface — fn source, ``over=``, ``Tolerance``,
    ``flips_at``), unioned with any unsettled ``backfill_pending``
    marker persisted by a prior boot that revised and then crashed
    before its backfill (or Deferred drain) committed. Empty on a plain
    restart with no debt, and on revision 1, whose rows haven't existed
    under any other law. A new revision carries the union forward as its
    own ``backfill_pending`` — written in the same transaction as the
    revise, so the law and its unpaid recompute debt commit or vanish
    together."""
    async with invoker.storage.session() as s:
        rows, _ = await invoker.storage.query(
            s, "definition", filters={"target_kind": target_kind},
            sort="-revision", page_size=REVISION_SCAN, page_number=1)
    current = next((r for r in rows if r.state == "current"), None)
    declared = fp.get("derived") or {}
    # the durable marker survives a crash between revise and backfill:
    # matching hashes on the re-boot write nothing, but the debt reports
    carried = _still_declared(
        target_kind,
        current.data.backfill_pending if current is not None else (),
        declared)
    if current is not None and current.data.fingerprint_hash == fp_hash:
        # unchanged: a restart costs nothing
        return current.id, current.data.revision, carried

    revision = rows[0].data.revision + 1 if rows else 1
    diff = None
    stale: tuple[str, ...] = ()
    change_summary = "the law as first recorded"
    previous = current or (rows[0] if rows else None)
    if previous is not None:
        diff = diff_fingerprints(previous.data.fingerprint, fp)
        # a rollback marks facts stale like any revise: the rows were
        # materialized under the law being left, whichever way time runs.
        # Facts the new law no longer declares are filtered out — nothing
        # is left to recompute for them.
        stale = tuple(f for f in stale_facts(diff) if f in declared)
        reverted = next((r.data.revision for r in rows
                         if r.data.fingerprint_hash == fp_hash), None)
        if reverted is not None:
            # a rollback is just another revise (design §2), named as such
            diff["reverts_to"] = reverted
            change_summary = f"reverts to revision {reverted}"
        else:
            change_summary = _describe(diff)

    pending = tuple(sorted(set(stale) | set(carried)))
    body = {
        "target_kind": target_kind,
        "revision": revision,
        "fingerprint_hash": fp_hash,
        "fingerprint": fp,
        "diff": diff,
        "change_summary": change_summary,
        # atomic with the revise by construction: same row, same insert —
        # the marker cannot be lost between the law changing and the
        # truth catching up (design §4's crash window, closed)
        "backfill_pending": list(pending) or None,
    }
    # one transaction, one correlation: the new revision's create and the
    # old one's supersede commit together and read as one deploy in the
    # log — the _record_effect_job pattern, applied to the law
    async with invoker._flip_session() as s:
        ctx = invoker._ctx(DEPLOY, s, correlation_id=correlation)
        doc = await invoker._create_core(
            s, ctx, invoker._rdef("definition"), body, body_digest(body))
        new_id = doc["self"].rsplit("/", 1)[-1]
        if current is not None:
            await invoker._invoke_in_session(
                s, "definition", current.id, "supersede", None,
                principal=DEPLOY, if_match=None, idempotency_key=None,
                dry_run=False, locale="en", correlation_id=correlation,
                require_key=False)
    return new_id, revision, pending


async def settle_backfill(invoker: Any, row_id: str) -> None:
    """The other half of the durable marker (design §4): the backfill of
    every fact a revision marked pending has committed, so the debt is
    cleared — by an ordinary ``settle`` transition on the current
    definition row, by the same system deploy actor that revised. In the
    log the catch-up lifecycle reads as two joined transitions: the
    ``revise`` that created the debt and the ``settle`` that paid it.
    Called only after the recompute committed for all rows; a crash
    before this lands leaves the marker in place, and the next boot
    re-runs the whole backfill — idempotent by construction, since
    recompute writes the same values."""
    async with invoker._flip_session() as s:
        await invoker._invoke_in_session(
            s, "definition", row_id, "settle", None,
            principal=DEPLOY, if_match=None, idempotency_key=None,
            dry_run=False, locale="en", correlation_id=uuid.uuid4().hex,
            require_key=False)


async def revise_definitions(engine: Any
                             ) -> tuple[dict[str, str],
                                        dict[str, tuple[str, ...]]]:
    """The boot IS the revise (design §2): fingerprint every registered
    kind — application and engine kinds alike; the law covers the whole
    registry, this kind included — compare to the stored current rows,
    and write revisions only where the hash moved. One correlation id
    spans the whole deploy, and the ``__registry__`` row records it as
    one revise even when many kinds changed. Returns two maps: kind →
    current revision row id (``engine.current_law``'s cache, the §3
    anchor seam), and kind → derived facts awaiting recompute — the
    freshly stale-by-definition set unioned with any durable
    ``backfill_pending`` marker a crashed prior boot left unsettled —
    the §4 backfill's work order, which ``engine.startup()`` settles
    before the kind serves (or defers under a declared ``Deferred``)."""
    correlation = uuid.uuid4().hex
    law: dict[str, str] = {}
    stale: dict[str, tuple[str, ...]] = {}
    hashes: dict[str, str] = {}
    # §3 seed: the revise's own transitions (rows of the definition kind)
    # are anchored like any write — to the definition law in force at
    # write time. Load the stored current row before writing anything; a
    # first boot has none, and its revise transitions are honestly
    # pre-law (defined_by NULL).
    ddef = engine.registry.get("definition")
    if ddef is not None and ddef.current_law is None:
        async with engine.invoker.storage.session() as s:
            rows, _ = await engine.invoker.storage.query(
                s, "definition",
                filters={"target_kind": "definition", "state": "current"},
                sort=None, page_size=1, page_number=1)
        if rows:
            ddef.current_law = rows[0].id
            ddef.current_law_revision = rows[0].data.revision
    # the definition kind revises first, so every later kind's revision
    # rows anchor to the fresh law of the law
    for rdef in sorted(engine.registry.defs(),
                       key=lambda r: (r.kind != "definition", r.kind)):
        fp = fingerprint_of(rdef)
        fp_hash = fingerprint_hash(fp)
        hashes[rdef.kind] = fp_hash
        law[rdef.kind], law_revision, kind_stale = await _revise_kind(
            engine.invoker, correlation, rdef.kind, fp, fp_hash)
        if kind_stale:
            stale[rdef.kind] = kind_stale
        # the §3 anchor seam: the invoker's _law() and render's meta.law
        # read this attribute on every write and every envelope — and the
        # revision NUMBER rides beside the id (meta.law_revision), so a
        # human client renders "rev N" without resolving the row
        rdef.current_law = law[rdef.kind]
        rdef.current_law_revision = law_revision
    # decision surfaces revise like kinds (design 6.0 §4): a surface has
    # no rows — the ``__registry__`` precedent for a storage-less
    # definition target — but its fingerprint (anchor, members with each
    # cited edge's predicate, showcase, title, attention) moves the same
    # way, in the same correlation, and its target never appears in the
    # stale map (no ``derived`` facet, nothing to backfill). Surfaces
    # revise after their anchors, so their rows anchor to fresh law.
    surface_hashes: dict[str, str] = {}
    for name in sorted(getattr(engine, "surfaces", None) or {}):
        sdef = engine.surfaces[name]
        fp = surface_fingerprint(sdef)
        fp_hash = fingerprint_hash(fp)
        surface_hashes[name] = fp_hash
        law[sdef.target_kind], sdef.current_law_revision, _ = \
            await _revise_kind(engine.invoker, correlation,
                               sdef.target_kind, fp, fp_hash)
        # the surface envelope's meta.law seam — the SurfaceDef plays the
        # rdef's role for a definition target that is not a kind
        sdef.current_law = law[sdef.target_kind]
    registry_fp = {"kinds": {k: hashes[k] for k in sorted(hashes)},
                   # emitted only when surfaces are declared: adding the
                   # mechanism must not re-hash every registry that never
                   # touched it
                   **({"surfaces": surface_hashes} if surface_hashes
                      else {})}
    law[REGISTRY_KIND], _, _ = await _revise_kind(
        engine.invoker, correlation, REGISTRY_KIND, registry_fp,
        fingerprint_hash(registry_fp))
    return law, stale
