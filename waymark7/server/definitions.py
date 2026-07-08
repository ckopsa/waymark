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
from dataclasses import dataclass, field as dc_field
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, Field

from ..core.actions import Inputs, action
from ..core.fingerprint import (
    classify_diff,
    diff_fingerprints,
    fingerprint_hash,
    fingerprint_of,
    stale_facts,
    surface_fingerprint,
)
from ..core.guards import Guard, four_eyes
from ..core.resource import Resource, filterable, sortable
from ..core.types import Acknowledged, Allow, Ctx, Deny, Principal, Safety
from .idempotency import body_digest

# the deploy actor (design §2): the process identity that revises the law
# at boot — a system principal like the engine's other write tails
DEPLOY = Principal(id="waymark7-deploy", type="system", display="Deploy")

# the registry-level target: one row records the whole deploy — the
# sorted map of kind → fingerprint hash — even when many kinds changed
REGISTRY_KIND = "__registry__"

# rollback detection scans this many most-recent revisions per kind; a
# reversion past that horizon still revises, just without the name
REVISION_SCAN = 1000

log = logging.getLogger("waymark7.definitions")


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


async def _resident_check(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
    lc = getattr(ctx, "_lifecycle", None)
    if lc is not None and lc.is_pending(r):
        return Allow()
    return Deny()


def _resident_only() -> Guard:
    """A proposal is actionable only while its code is resident (design
    §1/§4): promote would install a law the process cannot interpret, and
    measure needs the resident parameters as the second set. The engine's
    lifecycle seam knows which proposal (at most one per kind) the boot
    registered and held."""
    return Guard(
        name="proposal_is_resident",
        explain="Only a proposal whose code is resident in the running "
                "process can be promoted or measured.",
        check=_resident_check, reads=("principal",),
    )


class DefinitionState(StrEnum):
    """The definition's machine (design 7.0 §1):
    ``draft → proposed → piloted → current → superseded``, plus
    ``withdrawn`` from proposed and piloted. ``draft`` (API-authored data
    revisions) and ``piloted`` (row populations) are declared now and
    arrive with Phase 2 — both annotated ``allow_dead``; ``proposed`` is
    entered at creation by a propose-mode boot (``created_in``)."""

    DRAFT = "draft"
    PROPOSED = "proposed"
    PILOTED = "piloted"
    CURRENT = "current"
    SUPERSEDED = "superseded"
    WITHDRAWN = "withdrawn"


class WithdrawInput(BaseModel):
    reason: str = Field(
        min_length=1, max_length=500,
        description="Why this proposal is withdrawn — recorded on the "
                    "transition (the honest exit, in the log)",
        json_schema_extra={"x-display": {"widget": "prose"}})


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
    # in an engine bookkeeping row (the ``waymark7_cursors`` precedent),
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
    # the deploy-mode gate's verdict (design 7.0 §1): whether this
    # revision's diff was pure data law (Tolerance / where= on Derived
    # inputs — overlayable) or exceeded it. None on revision 1.
    diff_class: str | None = Field(
        default=None,
        description="data_law: the diff is overlayable stored parameters; "
                    "code_or_shape: it is not, and propose mode promotes "
                    "without holding",
        json_schema_extra={"x-display": {"raw": True}})
    # True when a propose-mode boot registered this revision and HELD it:
    # created in `proposed` (created_in), logged as `propose` (created_as),
    # served-law overlay installed until promote/withdraw
    held: bool = Field(
        default=False,
        description="This revision arrived as a held proposal: the boot "
                    "kept serving the current law while its code sat "
                    "resident (design §1's propose mode)")
    # the recorded marker for a propose-mode deploy the gate refused to
    # hold — rides the revise transition's recorded inputs (§5) too
    deploy_note: str | None = Field(
        default=None, max_length=120,
        description="Deploy-mode marker, e.g. 'promoted without hold: "
                    "diff exceeds data-law'")
    # §2's report, linkable from the review: the most recent blast-radius
    # job this proposal deferred (measure writes it)
    measure_job: str | None = Field(
        default=None,
        description="The job carrying this proposal's blast-radius "
                    "report (design §2)",
        json_schema_extra={"x-display": {"raw": True}})


class Definition(Resource):
    kind = "definition"
    State = DefinitionState
    Data = DefinitionData

    # auto-mode deploys are born current (the v6 single-breath revise,
    # kept exactly); a propose-mode boot's held revision is born proposed
    # via the declared create landing below (design 7.0 §1)
    initial = DefinitionState.CURRENT
    terminal = {DefinitionState.SUPERSEDED, DefinitionState.WITHDRAWN}
    # draft (API-authored revisions) and piloted (row populations) are
    # Phase 2's; declared now so the machine — and every envelope's state
    # vocabulary — already names the becoming. proposed is entered at
    # creation (a declared create landing the reachability walk cannot
    # see), and withdrawn is reachable only through it.
    allow_dead = {DefinitionState.DRAFT, DefinitionState.PROPOSED,
                  DefinitionState.PILOTED, DefinitionState.WITHDRAWN}

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
    create_action_names = frozenset({"create", "revise", "propose"})
    # a held proposal is born `proposed` (design 7.0 §1) — the declared
    # create landing the invoker and the replay conformance both read
    create_state_names = frozenset({str(DefinitionState.PROPOSED)})
    # the law does not get privacy from its subjects (design 7.0 §5):
    # every definition transition stores its validated input payload
    record_inputs = True

    def created_as(self) -> str:
        if self.data.revision <= 1:
            return "create"
        return "propose" if self.data.held else "revise"

    def created_in(self) -> str:
        return (str(DefinitionState.PROPOSED) if self.data.held
                else str(DefinitionState.CURRENT))

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

    @action(from_=DefinitionState.PROPOSED, to=DefinitionState.CURRENT,
            guards=(_resident_only(), four_eyes(of="propose")),
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="This proposal becomes the served law "
                                      "for every row of its kind; the "
                                      "standard recompute of redefined "
                                      "facts runs."),
            display=dict(label="Promote", order=1,
                         description="Adopt the proposal as the current "
                                     "law (design §1)"))
    async def promote(self, inp: None, ctx: Ctx) -> None:
        """The law's own E3, passed (design §1): four-eyes on ``of=
        "propose"`` bars whoever proposed — the boot's deploy actor, so
        any human passes; the deploy actor itself cannot promote what it
        proposed. In the same transaction the previous current revision
        supersedes (below, as the deploy actor, one correlation — the
        boot-revise discipline); after commit the engine's lifecycle seam
        flips the served law, drops the §1 overlay, and runs the
        stale-by-definition backfill (the existing §4 machinery — promote
        triggers what a boot revise triggers)."""
        await ctx._lifecycle.supersede_prior(self, ctx)

    @action(from_={DefinitionState.PROPOSED, DefinitionState.PILOTED},
            to=DefinitionState.WITHDRAWN,
            input=WithdrawInput, record=Inputs(),
            waives=("large_effort",),  # one sentence, not a composition
            safety=Safety(idempotent=False, reversible=False, confirm=True,
                          consequence="The proposal closes with its reason "
                                      "in the log; the current law "
                                      "continues to govern."),
            display=dict(label="Withdraw", order=3,
                         description="The honest exit: a transition with "
                                     "a reason, in the log (design §1)"))
    async def withdraw(self, inp: WithdrawInput, ctx: Ctx) -> None:
        """Flag systems bury their dead; this one records them — the
        reason is the transition's recorded input (design §5). The §1
        overlay STAYS: the process keeps serving the current law from
        stored parameters while the withdrawn proposal's code sits
        resident — indefinitely, and recorded here as acceptable;
        redeploying the current law's code clears it naturally (the boot
        finds matching fingerprints and installs no overlay)."""

    @action(from_=DefinitionState.PROPOSED, to=DefinitionState.PROPOSED,
            guards=(_resident_only(),),
            safety=Safety(idempotent=False, reversible=True, confirm=False),
            display=dict(label="Measure blast radius", order=2,
                         description="Recompute every redefined fact over "
                                     "the live rows and report the flips "
                                     "(design §2)"))
    async def measure(self, inp: None, ctx: Ctx) -> None:
        """Proposals show their blast radius (design §2): for each derived
        fact the diff marks redefined, a deferred job (the E6 machinery)
        recomputes the target kind's rows under the resident proposed
        parameters and compares against the stored current-law values —
        both parameter sets are available exactly because the store holds
        the current law and the process holds the proposal. Per-fact flip
        counts land as job artifacts; the job id lands here, linkable
        from the review. Full scan with the maintainer's paging
        discipline — no silent sampling; the artifact says ``scan:
        full``."""
        facts = [f for f in stale_facts(self.data.diff or {})
                 if f in (self.data.fingerprint.get("derived") or {})]
        if not facts:
            from .problems import GuardRefused

            raise GuardRefused(
                "This proposal redefines no derived fact; there is no "
                "blast radius to measure.",
                action_attempted="measure")
        artifacts = [(f"{self.data.target_kind}.{fact}",
                      (self.data.target_kind, fact)) for fact in facts]
        self.data.measure_job = await ctx.defer(
            ctx._lifecycle.meter, artifacts, action="measure")


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


@dataclass
class KindRevise:
    """One target's boot-revise outcome (design §1/§2): which revision is
    the served law, the facts awaiting recompute under it, and — in
    propose mode — the held proposal plus the §1 overlay's parameter
    overrides for it."""

    law_id: str
    law_revision: int
    pending: tuple[str, ...] = ()
    proposed_id: str | None = None
    proposed_revision: int | None = None
    # (kind, fact) → LawOverride: the CURRENT law's stored parameters
    overrides: dict[tuple[str, str], Any] = dc_field(default_factory=dict)


async def _withdraw(invoker: Any, correlation: str, row_id: str,
                    reason: str) -> None:
    """Boot-side withdraw of a lingering proposal (design §1): an
    ordinary ``withdraw`` transition by the deploy actor, its reason
    recorded as the transition's input (§5)."""
    async with invoker._flip_session() as s:
        await invoker._invoke_in_session(
            s, "definition", row_id, "withdraw", {"reason": reason},
            principal=DEPLOY, if_match=None, idempotency_key=None,
            dry_run=False, locale="en", correlation_id=correlation,
            require_key=False)


def _overrides_for(target_kind: str, current_fp: dict[str, Any],
                   facts: tuple[str, ...]) -> dict[tuple[str, str], Any]:
    """The §1 overlay's parameter overrides: for each redefined fact, the
    CURRENT revision's stored ``Tolerance`` literal and per-input
    ``where=`` filters, read from the fingerprint the definition store
    already holds (design §4: the declared law is data, and data
    interprets from declarations)."""
    from .derived import LawOverride

    out: dict[tuple[str, str], Any] = {}
    derived = current_fp.get("derived") or {}
    for fact in facts:
        entry = derived.get(fact)
        if entry is None:
            continue
        where: dict[int, dict[str, Any]] = {}
        for i, item in enumerate(entry.get("over") or ()):
            if isinstance(item, dict):
                inner = item.get("child") or item.get("related") or {}
                w = inner.get("where")
                if w is not None:
                    where[i] = dict(w)
        out[(target_kind, fact)] = LawOverride(
            tolerance=entry.get("tolerance"), where=where)
    return out


async def _revise_kind(invoker: Any, correlation: str, target_kind: str,
                       fp: dict[str, Any], fp_hash: str, *,
                       mode: str = "auto") -> KindRevise:
    """Compare one target's stored law to the fresh fingerprint; write
    nothing, or deploy per the declared mode (design 7.0 §1).

    ``mode="auto"`` keeps the v6 single-breath revise exactly: write
    revision N+1 as ``current`` and supersede N in one transaction.
    ``mode="propose"``: an unknown fingerprint whose diff is **data-law
    only** (``classify_diff``) registers as a ``proposed`` revision —
    logged ``propose``, born in the declared ``proposed`` landing — and
    the boot KEEPS SERVING the current law: the returned overrides are
    the §1 overlay's, built from the current revision's stored
    parameters. A diff that exceeds data-law auto-promotes with the
    recorded marker (``deploy_note``) — the pilot-gate philosophy
    applied at propose time; the resident objects ARE that law, so
    holding would be pretense.

    ``pending`` is the derived facts awaiting recompute under the SERVED
    law: the facts a revise-to-current marked **stale by definition**
    (design §4) unioned with any unsettled ``backfill_pending`` marker a
    crashed prior boot left. A held proposal contributes nothing to it —
    the stored values already agree with the law still being served; its
    own row carries the debt promote will owe."""
    async with invoker.storage.session() as s:
        rows, _ = await invoker.storage.query(
            s, "definition", filters={"target_kind": target_kind},
            sort="-revision", page_size=REVISION_SCAN, page_number=1)
    current = next((r for r in rows if r.state == "current"), None)
    proposed_rows = [r for r in rows if r.state == "proposed"]
    declared = fp.get("derived") or {}
    # the durable marker survives a crash between revise and backfill:
    # matching hashes on the re-boot write nothing, but the debt reports
    carried = _still_declared(
        target_kind,
        current.data.backfill_pending if current is not None else (),
        declared)
    if current is not None and current.data.fingerprint_hash == fp_hash:
        # unchanged: a restart costs nothing — but a lingering proposal
        # whose code is no longer resident cannot honestly be promoted;
        # it exits the honest way, reason recorded (design §1)
        for p in proposed_rows:
            await _withdraw(invoker, correlation, p.id,
                            "the resident code matches the current law; "
                            "this proposal's code is no longer deployed")
        return KindRevise(current.id, current.data.revision, carried)

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
    diff_class = classify_diff(diff) if diff is not None else None

    if mode == "propose" and current is not None \
            and diff_class == "data_law":
        # §1's hold: register the proposal, keep serving the current law
        match = next((p for p in proposed_rows
                      if p.data.fingerprint_hash == fp_hash), None)
        for p in proposed_rows:
            if match is None or p.id != match.id:
                await _withdraw(invoker, correlation, p.id,
                                "a newer deploy replaced this proposal")
        if match is None:
            body = {
                "target_kind": target_kind,
                "revision": revision,
                "fingerprint_hash": fp_hash,
                "fingerprint": fp,
                "diff": diff,
                "change_summary": change_summary,
                "diff_class": diff_class,
                "held": True,
                # the debt promote will owe, written where promote reads it
                "backfill_pending": list(pending) or None,
            }
            async with invoker._flip_session() as s:
                ctx = invoker._ctx(DEPLOY, s, correlation_id=correlation)
                doc = await invoker._create_core(
                    s, ctx, invoker._rdef("definition"), body,
                    body_digest(body))
            proposed_id = doc["self"].rsplit("/", 1)[-1]
            proposed_revision = revision
        else:
            # the same proposal, re-booted: nothing new to write; the
            # overlay re-installs from the same stored parameters
            proposed_id = match.id
            proposed_revision = match.data.revision
        overrides = _overrides_for(target_kind, current.data.fingerprint,
                                   stale)
        return KindRevise(current.id, current.data.revision, carried,
                          proposed_id=proposed_id,
                          proposed_revision=proposed_revision,
                          overrides=overrides)

    # auto mode — or a propose-mode diff the gate refused to hold, or the
    # first law: revise to current, exactly as v6. Lingering proposals
    # are replaced, recorded.
    for p in proposed_rows:
        await _withdraw(invoker, correlation, p.id,
                        "a newer deploy replaced this proposal")
    deploy_note = None
    if mode == "propose" and current is not None:
        deploy_note = "promoted without hold: diff exceeds data-law"
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
        **({"diff_class": diff_class} if diff_class else {}),
        **({"deploy_note": deploy_note} if deploy_note else {}),
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
    return KindRevise(new_id, revision, pending)


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
    mode = getattr(engine, "deploy", "auto")
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
        outcome = await _revise_kind(
            engine.invoker, correlation, rdef.kind, fp, fp_hash, mode=mode)
        law[rdef.kind] = outcome.law_id
        if outcome.pending:
            stale[rdef.kind] = outcome.pending
        # the §3 anchor seam: the invoker's _law() and render's meta.law
        # read this attribute on every write and every envelope — and the
        # revision NUMBER rides beside the id (meta.law_revision), so a
        # human client renders "rev N" without resolving the row
        rdef.current_law = outcome.law_id
        rdef.current_law_revision = outcome.law_revision
        # the §1 hold: the pending proposal (if any) and the served-law
        # overlay — the maintainer's specs_for consults both
        rdef.proposed_law = outcome.proposed_id
        rdef.proposed_law_revision = outcome.proposed_revision
        if outcome.overrides:
            engine.invoker.derived.overlay.update(outcome.overrides)
            log.info(
                "propose mode holds %s at proposed revision %s; the "
                "current law (revision %s) keeps serving via the overlay "
                "for facts %s", rdef.kind, outcome.proposed_revision,
                outcome.law_revision,
                sorted(f for _, f in outcome.overrides))
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
        # surfaces revise in auto semantics whatever the engine's deploy
        # mode: a surface has no rows and no derived facts, so there is
        # nothing the §1 overlay could hold — its fingerprint is pure
        # composition, and the resident declaration is what serves
        outcome = await _revise_kind(engine.invoker, correlation,
                                     sdef.target_kind, fp, fp_hash,
                                     mode="auto")
        law[sdef.target_kind] = outcome.law_id
        sdef.current_law_revision = outcome.law_revision
        # the surface envelope's meta.law seam — the SurfaceDef plays the
        # rdef's role for a definition target that is not a kind
        sdef.current_law = outcome.law_id
    registry_fp = {"kinds": {k: hashes[k] for k in sorted(hashes)},
                   # emitted only when surfaces are declared: adding the
                   # mechanism must not re-hash every registry that never
                   # touched it
                   **({"surfaces": surface_hashes} if surface_hashes
                      else {})}
    # the __registry__ row records THE DEPLOY — the resident code, one row
    # per boot whose hashes moved — always in auto semantics: in propose
    # mode the deploy has genuinely happened (the code is resident) even
    # while a per-kind row holds its LAW at proposed; the per-kind rows
    # carry the lifecycle, the registry row carries the arrival
    outcome = await _revise_kind(
        engine.invoker, correlation, REGISTRY_KIND, registry_fp,
        fingerprint_hash(registry_fp), mode="auto")
    law[REGISTRY_KIND] = outcome.law_id
    return law, stale


# ── §1–§2: the lifecycle seam ────────────────────────────────────────────
SAMPLE_CAP = 20  # ids per blast-radius artifact sample (design §2)


class BlastRadiusMeter:
    """§2's measurer, run per-fact on the E6 job executor via ``ctx.defer``.

    For one redefined fact: a full scan of the target kind's rows with
    the maintainer's paging discipline, comparing the STORED value (the
    current law's materialized truth — the definition store's half of
    "both parameter sets") against a fresh computation under the RESIDENT
    declaration (the proposed law's half). Returns the report the job
    stores as the artifact's ``detail``:
    ``{"fact": "...", "flips": n, "of": total, "sample": [...],
    "scan": "full"}`` — no silent sampling; the sample is capped, the
    scan is not, and the artifact says so."""

    name = "blast_radius"

    def __init__(self, engine: Any):
        self.engine = engine

    async def call(self, target_kind: str, fact: str, *,
                   now: Any) -> dict[str, Any]:
        from ..core.derived import ordered_specs

        engine = self.engine
        rdef = engine.registry.get(target_kind)
        if rdef is None:
            raise RuntimeError(f"unknown kind {target_kind!r}")
        maintainer = engine.invoker.derived
        # the resident declaration IS the proposed law (design §4): pass
        # it explicitly so the §1 overlay does not fold it back to current
        proposed = ordered_specs(rdef.cls.Data)
        flips = 0
        total = 0
        sample: list[str] = []
        after: str | None = None
        while True:
            async with engine.storage.session() as s:
                ids = await engine.storage.id_page(s, target_kind,
                                                   after=after, limit=200)
                for row_id in ids:
                    instance = await engine.storage.load(s, target_kind,
                                                         row_id)
                    if instance is None:
                        continue
                    total += 1
                    stored = getattr(instance.data, fact, None)
                    fresh = await maintainer.compute(s, instance, rdef,
                                                     now=now, specs=proposed)
                    if fresh.get(fact) != stored:
                        flips += 1
                        if len(sample) < SAMPLE_CAP:
                            sample.append(row_id)
            if len(ids) < 200:
                break
            after = ids[-1]
        return {"fact": f"{target_kind}.{fact}", "flips": flips,
                "of": total, "sample": sample, "scan": "full"}


class DefinitionLifecycle:
    """The engine-held seam the definition kind's own handlers and guards
    consult (design 7.0 §1–§2), and the entry-point after-commit hook a
    human ``promote`` needs. Constructed by the engine at startup and
    stamped on the invoker; it rides every Ctx as ``_lifecycle``. It is
    deliberately definition-only plumbing — app handlers never see it."""

    def __init__(self, engine: Any):
        self.engine = engine
        self.meter = BlastRadiusMeter(engine)

    def is_pending(self, row: Any) -> bool:
        """Is this definition row THE proposal whose code is resident —
        the one the boot registered and held? (At most one per kind: a
        stage, not a lattice.)"""
        rdef = self.engine.registry.get(row.data.target_kind)
        return rdef is not None and rdef.proposed_law == row.id

    async def supersede_prior(self, row: Any, ctx: Ctx) -> None:
        """The in-transaction half of promote (design §1): the previous
        current revision supersedes as the deploy actor, in the promote's
        own transaction and correlation — the boot-revise discipline
        (one deploy, one story in the log) applied to a human promote."""
        priors = await ctx.find("definition", limit=10,
                                target_kind=row.data.target_kind,
                                state="current")
        for prior in priors:
            if prior.id == row.id:
                continue
            await self.engine.invoker._invoke_in_session(
                ctx.session, "definition", prior.id, "supersede", None,
                principal=DEPLOY, if_match=None, idempotency_key=None,
                dry_run=False, locale="en",
                correlation_id=ctx.correlation_id, require_key=False)

    async def after_commit(self, kind: str, id: str, action: str,
                           result: Any) -> None:
        """The post-commit half of promote (design §1): flip the served
        law, drop the §1 overlay, and run the standard stale-by-definition
        backfill + settle — the existing §4 machinery; the promote
        transition triggers what a boot revise triggers. Idempotent by
        construction (a replayed promote finds the law already flipped
        and no unsettled marker), so natural replays cost a row load.
        Runs only on the entry-point invoke path — promote is a wire act,
        never a nested ``ctx.invoke``. A crash inside this window is the
        boot-revise crash window, already closed: the next boot finds the
        promoted row current with its durable ``backfill_pending`` marker
        and resumes the debt."""
        if kind != "definition" or action != "promote" \
                or getattr(result, "status", None) != 200:
            return
        engine = self.engine
        async with engine.storage.session() as s:
            row = await engine.storage.load(s, "definition", id)
        if row is None or row.state != str(DefinitionState.CURRENT):
            return
        target = row.data.target_kind
        rdef = engine.registry.get(target)
        if rdef is None:
            return
        # the law flip: meta.law, defined_by anchoring, and current_law()
        # all read these attributes
        rdef.current_law = row.id
        rdef.current_law_revision = row.data.revision
        engine._law[target] = row.id
        if rdef.proposed_law == row.id:
            rdef.proposed_law = None
            rdef.proposed_law_revision = None
        # the overlay drops: the resident law becomes the served law
        maintainer = engine.invoker.derived
        for key in [k for k in maintainer.overlay if k[0] == target]:
            del maintainer.overlay[key]
        # the standard stale-by-definition backfill (design §4), then the
        # settle that clears the durable marker. Runs inside the promote
        # request (a declared Deferred is honored at boot only — recorded
        # deviation: a promote holds its caller, not the door).
        pending = _still_declared(
            target, row.data.backfill_pending,
            row.data.fingerprint.get("derived") or {})
        if pending:
            await maintainer.backfill(target)
            await settle_backfill(engine.invoker, row.id)
