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

from pydantic import BaseModel, Field, model_validator

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
DEPLOY = Principal(id="waymark8-deploy", type="system", display="Deploy")

# the registry-level target: one row records the whole deploy — the
# sorted map of kind → fingerprint hash — even when many kinds changed
REGISTRY_KIND = "__registry__"

# rollback detection scans this many most-recent revisions per kind; a
# reversion past that horizon still revises, just without the name
REVISION_SCAN = 1000

log = logging.getLogger("waymark8.definitions")


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
    ``withdrawn`` from proposed and piloted, plus ``grandfathered``
    between current and superseded — the state of a law whose successor
    is current while rows still live under it (design §3: laws die when
    they are empty, so a revision with survivors is not history yet; the
    design implied a lingering ``current``, but two current rows per kind
    would be a lie — a distinct state is more honest, recorded
    deviation). ``draft`` (API-authored data revisions) is declared now
    and remains unwired (``allow_dead``); ``proposed`` is entered at
    creation by a propose-mode boot (``created_in``)."""

    DRAFT = "draft"
    PROPOSED = "proposed"
    PILOTED = "piloted"
    CURRENT = "current"
    GRANDFATHERED = "grandfathered"
    SUPERSEDED = "superseded"
    WITHDRAWN = "withdrawn"


class WithdrawInput(BaseModel):
    reason: str = Field(
        min_length=1, max_length=500,
        description="Why this proposal is withdrawn — recorded on the "
                    "transition (the honest exit, in the log)",
        json_schema_extra={"x-display": {"widget": "prose"}})


class Population(BaseModel):
    """The pilot's declared population of rows (design 7.0 §3): either a
    bounded predicate over the target kind's query grammar (``where``) or
    grandfathering-forward (``after=True`` — rows created from now on;
    existing rows keep their law). Exactly one; a pilot that names no
    rows and a pilot that names them two ways are both refused."""

    where: dict[str, Any] | None = Field(
        default=None,
        description="A bounded population: rows the target kind's query "
                    "grammar admits (promoted stored fields; Eq/In and "
                    "the _gte/_lte range suffixes)",
        json_schema_extra={"x-display": {"raw": True}})
    after: bool = Field(
        default=False,
        description="Grandfathering: rows created from now on live under "
                    "the piloted revision; existing rows keep their law")

    @model_validator(mode="after")
    def _exactly_one(self) -> "Population":
        if bool(self.where) == bool(self.after):
            raise ValueError(
                "a Population is where={...} or after=true — exactly one")
        return self


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
    # in an engine bookkeeping row (the ``waymark8_cursors`` precedent),
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
    # the pilot's population (design 7.0 §3), as validated and recorded on
    # the pilot transition: {"where": {...}} or {"after": true}. None
    # outside a pilot. The create path routes new rows by it and the boot
    # re-installs it on re-detection.
    population: dict[str, Any] | None = Field(
        default=None,
        description="The declared population of rows living under this "
                    "piloted revision (design §3)",
        json_schema_extra={"x-display": {"raw": True}})
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

    @action(from_={DefinitionState.CURRENT, DefinitionState.GRANDFATHERED},
            to=DefinitionState.SUPERSEDED,
            guards=(_deploy_only(),),
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Superseding is the boot's bookkeeping: the "
                              "revision row is immutable history either "
                              "way, and a rollback is a new revision, "
                              "never an un-supersede.")),
            display=dict(label="Supersede", order=9,
                         description="A newer revision of this law has "
                                     "taken effect and no row lives under "
                                     "this one"))
    async def supersede(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=DefinitionState.CURRENT, to=DefinitionState.GRANDFATHERED,
            guards=(_deploy_only(),),
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Grandfathering is the engine's bookkeeping "
                              "at a promote or revise whose kind declares "
                              "adoption=Never: rows still live under this "
                              "revision, so it is law, not history — it "
                              "supersedes the day its last row adopts or "
                              "closes.")),
            display=dict(label="Grandfather", order=9,
                         description="A newer revision took effect but "
                                     "rows still live under this one "
                                     "(design §3: laws die when they are "
                                     "empty)"))
    async def grandfather(self, inp: None, ctx: Ctx) -> None:
        """The row half of supersede-when-empty (design 7.0 §1/§3): a
        revision with survivors cannot honestly supersede, and two
        ``current`` rows per kind would be a lie — this state names the
        in-between. The lazy check on every adopt/terminal transition of
        the target kind supersedes it the moment its last stamped
        non-terminal row is gone (the February story: the old revision
        supersedes the day its last workbook closes)."""

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

    @action(from_={DefinitionState.PROPOSED, DefinitionState.PILOTED},
            to=DefinitionState.CURRENT,
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

    @action(from_=DefinitionState.PROPOSED, to=DefinitionState.PILOTED,
            input=Population, record=Inputs(),
            guards=(_resident_only(), four_eyes(of="propose")),
            safety=Safety(idempotent=False, reversible=False, confirm=True,
                          consequence="The declared population's rows "
                                      "begin living under this revision — "
                                      "existing matches restamp and "
                                      "recompute; the current law keeps "
                                      "governing everything else. The way "
                                      "back is withdraw, which returns "
                                      "the population to the current "
                                      "law."),
            display=dict(label="Pilot", order=2,
                         description="Try the proposal on a declared "
                                     "population of rows (design §3)"))
    async def pilot(self, inp: Population, ctx: Ctx) -> None:
        """The pilot is a population (design 7.0 §3): a set of rows, never
        a set of readers — Marcus and Elena both see fund-alpha under the
        new law, so the controls stay coherent. Four-eyes with propose
        (the law's E3 covers the trial, not only the adoption); the input
        is recorded (§5) — the population declaration IS the pilot's
        meaning. The gate (design §4): only a data-law diff may pilot —
        code does not interpret per-row — and only one live pilot or
        grandfathered predecessor at a time (a stage, never a lattice).
        The restamp of existing matches runs post-commit through the
        lifecycle seam, paged like the §4 backfill."""
        from .problems import GuardRefused, SchemaInvalid

        if self.data.diff_class != "data_law":
            raise GuardRefused(
                "Only a data-law diff can pilot per-population — code does "
                "not interpret per-row (design §4); preview and promote "
                "totally instead.",
                action_attempted="pilot")
        engine = ctx._lifecycle.engine
        rdef = engine.registry.get(self.data.target_kind)
        if rdef is None:
            raise GuardRefused(
                f"{self.data.target_kind!r} has no rows; there is nothing "
                "a population could claim.",
                action_attempted="pilot")
        lingering = await ctx.find(
            "definition", limit=2, target_kind=self.data.target_kind,
            state=str(DefinitionState.GRANDFATHERED))
        if lingering:
            raise GuardRefused(
                "A grandfathered revision of this kind still has rows "
                "living under it; two live revisions is the maximum "
                "(a stage, not a lattice) — the pilot must wait until "
                "that law is empty.",
                action_attempted="pilot")
        if inp.where:
            errors = check_population(rdef, inp.where)
            if errors:
                raise SchemaInvalid("Input failed validation.",
                                    action_attempted="pilot", errors=errors)
        self.data.population = inp.model_dump(mode="json",
                                              exclude_defaults=True)

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
    overrides for it. Phase 2 rides the row halves alongside (design §3):
    the live pilot (if any) with its population, the revision → row-id
    map every stamp resolves through, and the per-revision parameter
    overlays for laws that are not the resident code's."""

    law_id: str
    law_revision: int
    pending: tuple[str, ...] = ()
    proposed_id: str | None = None
    proposed_revision: int | None = None
    # (kind, fact) → LawOverride: the CURRENT law's stored parameters
    overrides: dict[tuple[str, str], Any] = dc_field(default_factory=dict)
    # the live pilot (design §3), re-detected across boots
    piloted_id: str | None = None
    piloted_revision: int | None = None
    population: dict[str, Any] | None = None
    # revision NUMBER → definition row id (rdef.law_ids)
    law_ids: dict[int, str] = dc_field(default_factory=dict)
    # revision NUMBER → {fact → LawOverride}: grandfathered revisions (and
    # a parameter-served pilot) — the maintainer's law_overlay entries
    law_overlays: dict[int, dict[str, Any]] = dc_field(default_factory=dict)


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
            tolerance=entry.get("tolerance"), expr=entry.get("expr"),
            where=where)
    return out


def check_population(rdef: Any, where: dict[str, Any]) -> dict[str, list[str]]:
    """Validate a pilot population's ``where`` against the target kind's
    query grammar (design 7.0 §3) — the surface ``attention=`` checker's
    discipline, applied at pilot time instead of import time because the
    population is input, not declaration. Returns field-keyed errors
    (empty = valid). Beyond grammar membership, populations claim rows by
    **promoted stored input fields** only: ``state`` moves and derived
    facts are computed under the very law being piloted — a population
    over either would be circular — so both are refused."""
    from ..core.derived import derived_specs

    props = rdef.query_schema.get("properties", {})
    derived = derived_specs(rdef.cls.Data)
    errors: dict[str, list[str]] = {}
    for param, value in where.items():
        schema = props.get(param)
        if schema is None:
            errors.setdefault(param, []).append(
                f"not in the query grammar of {rdef.kind!r} — a population "
                "claims rows the collection route could list")
            continue
        stem = param[:-4] if param.endswith(("_gte", "_lte")) else param
        if stem == "state":
            errors.setdefault(param, []).append(
                "populations claim rows by stored input fields; a state "
                "moves under the very machine being piloted")
            continue
        if stem in derived:
            errors.setdefault(param, []).append(
                "populations claim rows by stored input fields; a derived "
                "fact is computed under the very law being piloted")
            continue
        if "enum" in schema and isinstance(value, str):
            parts = value.split(",") if schema.get("x-in") else [value]
            bad = [v for v in parts if v not in schema["enum"]]
            if bad:
                errors.setdefault(param, []).append(
                    f"admits {schema['enum']}; got {bad}")
    return errors


def population_claims(where: dict[str, Any],
                      dump: dict[str, Any]) -> bool:
    """Does this population's ``where`` claim a row with these (validated
    create) values? The Python half of the one grammar the SQL restamp
    query runs — Eq, In (a list), the ``_gte``/``_lte`` range suffixes,
    and membership for a list-valued (Vocab) field. The require-at-create
    precedent: creates are judged by the revision whose population claims
    the input (design §3), so the same predicate must be answerable from
    the input alone."""
    for param, expected in where.items():
        if param.endswith(("_gte", "_lte")):
            value = dump.get(param[:-4])
            if value is None:
                return False
            try:
                if param.endswith("_gte"):
                    if not value >= expected:
                        return False
                elif not value <= expected:
                    return False
            except TypeError:
                return False
            continue
        value = dump.get(param)
        if isinstance(expected, (list, tuple)):
            hits = (set(value) & set(expected) if isinstance(value, list)
                    else value in expected)
            if not hits:
                return False
        elif isinstance(value, list):
            if expected not in value:
                return False
        elif value != expected:
            return False
    return True


def _overrides_all(target_kind: str,
                   fp: dict[str, Any]) -> dict[tuple[str, str], Any]:
    """Every derived fact's stored parameters from one revision's
    fingerprint — the row-law overlay's source (design 7.0 §3): a
    grandfathered or parameter-served piloted revision computes its rows'
    facts from these, whatever subset actually differs from the resident
    declaration (overlaying an identical parameter is harmless)."""
    derived = fp.get("derived") or {}
    return _overrides_for(target_kind, fp, tuple(sorted(derived)))


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
    from ..core.owns import Never

    async with invoker.storage.session() as s:
        rows, _ = await invoker.storage.query(
            s, "definition", filters={"target_kind": target_kind},
            sort="-revision", page_size=REVISION_SCAN, page_number=1)
    current = next((r for r in rows if r.state == "current"), None)
    proposed_rows = [r for r in rows if r.state == "proposed"]
    piloted_rows = [r for r in rows if r.state == "piloted"]
    declared = fp.get("derived") or {}
    has_table = target_kind in getattr(invoker.storage, "tables", {})
    # the upgrade stamping (design §3, migration sketch): rows written
    # before the stamp existed were living under the stored current law —
    # say so once, before anything decides by the stamps
    if has_table and current is not None:
        async with invoker.storage.session() as s:
            stamped = await invoker.storage.stamp_null_law(
                s, target_kind, current.data.revision)
        if stamped:
            log.info("stamped %d unstamped %s row(s) to revision %d (the "
                     "law they were living under)", stamped, target_kind,
                     current.data.revision)
    # the stamp-resolution map (design §3): every stored revision, so a
    # grandfathered row's envelope and writes can name their law
    law_ids = {r.data.revision: r.id for r in rows}
    # grandfathered laws serve their rows from stored parameters (§3/§4)
    law_overlays = {
        r.data.revision: {f: ov for (_, f), ov in
                          _overrides_all(target_kind,
                                         r.data.fingerprint).items()}
        for r in rows if r.state == "grandfathered"}
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
        outcome = KindRevise(current.id, current.data.revision, carried,
                             law_ids=law_ids, law_overlays=law_overlays)
        if piloted_rows:
            # a live pilot whose code is no longer resident (the current
            # law's code was redeployed): the pilot is a data-law diff by
            # the pilot gate, so its rows keep computing from its STORED
            # parameters — the row-law overlay — while promote honestly
            # requires its code back (the residency guard). Withdraw
            # remains available either way.
            pilot = piloted_rows[0]
            law_overlays[pilot.data.revision] = {
                f: ov for (_, f), ov in
                _overrides_all(target_kind,
                               pilot.data.fingerprint).items()}
            outcome.piloted_id = pilot.id
            outcome.piloted_revision = pilot.data.revision
            outcome.population = pilot.data.population
        return outcome

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

    pilot_match = next((p for p in piloted_rows
                        if p.data.fingerprint_hash == fp_hash), None)
    if pilot_match is not None and current is not None:
        # the pilot continues across a re-boot (design §3): the resident
        # code IS the piloted law — the propose-mode hold's shape, with a
        # population living under the resident revision. The current law
        # keeps serving everything else from stored parameters (the §1
        # overlay); no revision is minted, whatever the deploy mode — the
        # deploy already happened and this boot is its restart.
        for p in proposed_rows:
            await _withdraw(invoker, correlation, p.id,
                            "a piloted revision holds this kind's stage; "
                            "this proposal was replaced")
        return KindRevise(
            current.id, current.data.revision, carried,
            proposed_id=pilot_match.id,
            proposed_revision=pilot_match.data.revision,
            # `stale` was diffed against `previous`, which IS the current
            # row whenever one exists — the §1 overlay's fact set
            overrides=_overrides_for(target_kind, current.data.fingerprint,
                                     stale),
            piloted_id=pilot_match.id,
            piloted_revision=pilot_match.data.revision,
            population=pilot_match.data.population,
            law_ids=law_ids, law_overlays=law_overlays)
    if piloted_rows:
        # a genuinely new deploy while a pilot is live would strand the
        # pilot's rows under a law the process can neither run nor
        # parameter-serve against the new resident code — refused, loudly:
        # the stage is occupied (design §1: a stage, never a lattice)
        raise RuntimeError(
            f"{target_kind}: revision {piloted_rows[0].data.revision} is "
            "piloted with rows living under it, but the resident code "
            "matches neither it nor the current law — promote or withdraw "
            "the pilot before deploying different code")

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
        law_ids[proposed_revision] = proposed_id
        return KindRevise(current.id, current.data.revision, carried,
                          proposed_id=proposed_id,
                          proposed_revision=proposed_revision,
                          overrides=overrides,
                          law_ids=law_ids, law_overlays=law_overlays)

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
    # old one's retirement commit together and read as one deploy in the
    # log — the _record_effect_job pattern, applied to the law
    async with invoker._flip_session() as s:
        ctx = invoker._ctx(DEPLOY, s, correlation_id=correlation)
        doc = await invoker._create_core(
            s, ctx, invoker._rdef("definition"), body, body_digest(body))
        new_id = doc["self"].rsplit("/", 1)[-1]
        if current is not None:
            # supersede-when-empty (design §1/§3): an adoption=Never kind
            # whose rows still live under the prior revision grandfathers
            # it instead — the rows finish under their birth law, and the
            # lazy check retires the revision when the last one closes.
            # Immediate kinds supersede as always: the backfill about to
            # run IS the bulk adopt, so no row survives the prior law.
            retire = "supersede"
            trdef = invoker.registry.get(target_kind)
            if has_table and trdef is not None \
                    and trdef.cls.adoption is Never:
                survivors = await invoker.storage.law_survivors(
                    s, target_kind, current.data.revision,
                    trdef.machine.terminal)
                if survivors:
                    retire = "grandfather"
            await invoker._invoke_in_session(
                s, "definition", current.id, retire, None,
                principal=DEPLOY, if_match=None, idempotency_key=None,
                dry_run=False, locale="en", correlation_id=correlation,
                require_key=False)
            if retire == "grandfather":
                law_overlays[current.data.revision] = {
                    f: ov for (_, f), ov in
                    _overrides_all(target_kind,
                                   current.data.fingerprint).items()}
    law_ids[revision] = new_id
    if has_table and not rows:
        # the first law of a kind whose table already has rows (an
        # upgraded store): they were living under what just became
        # revision 1 — the migration sketch's stamping
        async with invoker.storage.session() as s:
            await invoker.storage.stamp_null_law(s, target_kind, revision)
    return KindRevise(new_id, revision, pending,
                      law_ids=law_ids, law_overlays=law_overlays)


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
        # the row halves (design §3): the stamp-resolution map, the live
        # pilot with its population, and the per-revision parameter
        # overlays for grandfathered (or parameter-served piloted) laws
        rdef.law_ids = dict(outcome.law_ids)
        rdef.piloted_law = outcome.piloted_id
        rdef.piloted_law_revision = outcome.piloted_revision
        rdef.piloted_population = outcome.population
        for rev, per_fact in outcome.law_overlays.items():
            engine.invoker.derived.law_overlay[(rdef.kind, rev)] = per_fact
            engine._grandfathered_kinds.add(rdef.kind)
        if outcome.piloted_id is not None:
            log.info(
                "revision %s of %s is piloted (population %s); the current "
                "law (revision %s) governs the rest", outcome.piloted_revision,
                rdef.kind, outcome.population, outcome.law_revision)
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
        current revision retires as the deploy actor, in the promote's
        own transaction and correlation — the boot-revise discipline
        (one deploy, one story in the log) applied to a human promote.
        Supersede-when-empty (§3): an ``adoption=Never`` kind whose rows
        still live under the prior revision grandfathers it instead —
        rows finish under their birth law; the lazy check retires the
        revision when the last one adopts or closes. Immediate kinds
        supersede: the post-commit backfill IS the bulk adopt."""
        from ..core.owns import Never

        target = row.data.target_kind
        rdef = self.engine.registry.get(target)
        priors = await ctx.find("definition", limit=10,
                                target_kind=target, state="current")
        for prior in priors:
            if prior.id == row.id:
                continue
            retire = "supersede"
            if rdef is not None and rdef.cls.adoption is Never \
                    and target in getattr(self.engine.storage, "tables", {}):
                survivors = await self.engine.storage.law_survivors(
                    ctx.session, target, prior.data.revision,
                    rdef.machine.terminal)
                if survivors:
                    retire = "grandfather"
            await self.engine.invoker._invoke_in_session(
                ctx.session, "definition", prior.id, retire, None,
                principal=DEPLOY, if_match=None, idempotency_key=None,
                dry_run=False, locale="en",
                correlation_id=ctx.correlation_id, require_key=False)

    async def after_commit(self, kind: str, id: str, action: str,
                           result: Any) -> None:
        """The post-commit half of the lifecycle (design §1/§3), on the
        entry-point invoke path only. For the definition kind: a committed
        ``promote`` flips the served law; a ``pilot`` installs the
        population and restamps its existing matches; a ``withdraw`` of a
        piloted revision returns its rows to the current law. For every
        other kind: the lazy supersede-when-empty check — an ``adopt`` or
        a terminal landing may have emptied a grandfathered revision
        (laws die when they are empty, and the log knows the day)."""
        if getattr(result, "status", None) != 200:
            return
        if kind == "definition":
            if action == "promote":
                await self._after_promote(id)
            elif action == "pilot":
                await self._after_pilot(id)
            elif action == "withdraw":
                await self._after_withdraw(id)
            return
        if kind not in self.engine._grandfathered_kinds:
            return
        doc = getattr(result, "doc", None) or {}
        rdef = self.engine.registry.get(kind)
        if rdef is None:
            return
        if action == "adopt" or doc.get("state") in rdef.machine.terminal:
            await self._sweep_empty_laws(kind, rdef)

    async def _after_promote(self, id: str) -> None:
        """Flip the served law, drop the §1 overlay, and run the standard
        stale-by-definition backfill + settle — the existing §4 machinery;
        the promote transition triggers what a boot revise triggers (and
        for an Immediate kind that backfill IS the bulk adopt, design §3).
        Idempotent by construction (a replayed promote finds the law
        already flipped and no unsettled marker), so natural replays cost
        a row load. A crash inside this window is the boot-revise crash
        window, already closed: the next boot finds the promoted row
        current with its durable ``backfill_pending`` marker and resumes
        the debt."""
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
        rdef.law_ids[row.data.revision] = row.id
        engine._law[target] = row.id
        if rdef.proposed_law == row.id:
            rdef.proposed_law = None
            rdef.proposed_law_revision = None
        maintainer = engine.invoker.derived
        if rdef.piloted_law == row.id:
            # the piloted population's trial ended in adoption: the pilot
            # seams clear — its rows are simply under the current law now
            rdef.piloted_law = None
            rdef.piloted_law_revision = None
            rdef.piloted_population = None
            maintainer.law_overlay.pop((target, row.data.revision), None)
        # the overlay drops: the resident law becomes the served law
        for key in [k for k in maintainer.overlay if k[0] == target]:
            del maintainer.overlay[key]
        # the prior revision may have grandfathered in-transaction
        # (supersede_prior, adoption=Never with survivors): its rows keep
        # computing under it, from its stored parameters
        async with engine.storage.session() as s:
            lingering, _ = await engine.storage.query(
                s, "definition",
                filters={"target_kind": target,
                         "state": str(DefinitionState.GRANDFATHERED)},
                sort=None, page_size=50, page_number=1)
        for old in lingering:
            maintainer.law_overlay[(target, old.data.revision)] = {
                f: ov for (_, f), ov in
                _overrides_all(target, old.data.fingerprint).items()}
            engine._grandfathered_kinds.add(target)
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

    async def _after_pilot(self, id: str) -> None:
        """Install the pilot (design §3): the population routes creates
        from this commit on, and existing rows the ``where`` claims
        restamp to the piloted revision and recompute under it — paged
        like the §4 backfill, run inline in the pilot request (acceptable
        for 7.0; a deferred variant is future work, recorded).
        ``after=True`` moves no existing row — grandfathering forward."""
        engine = self.engine
        async with engine.storage.session() as s:
            row = await engine.storage.load(s, "definition", id)
        if row is None or row.state != str(DefinitionState.PILOTED):
            return
        target = row.data.target_kind
        rdef = engine.registry.get(target)
        if rdef is None:
            return
        rdef.piloted_law = row.id
        rdef.piloted_law_revision = row.data.revision
        rdef.piloted_population = row.data.population
        rdef.law_ids[row.data.revision] = row.id
        where = (row.data.population or {}).get("where")
        if where:
            await self._restamp(target, rdef, dict(where),
                                row.data.revision)

    async def _after_withdraw(self, id: str) -> None:
        """A withdrawn PILOT returns its rows to the current law (design
        §1's honest exit, applied to §3's populations): every row stamped
        to the withdrawn revision restamps to current and recomputes
        under it — the served law, which the §1 overlay keeps honest
        while the withdrawn code sits resident. A withdrawn PROPOSAL
        changes nothing (no row ever lived under it); the overlay stays,
        exactly as Phase 1 recorded."""
        engine = self.engine
        async with engine.storage.session() as s:
            row = await engine.storage.load(s, "definition", id)
        if row is None or row.state != str(DefinitionState.WITHDRAWN):
            return
        target = row.data.target_kind
        rdef = engine.registry.get(target)
        if rdef is None or rdef.piloted_law != row.id:
            return
        rdef.piloted_law = None
        rdef.piloted_law_revision = None
        rdef.piloted_population = None
        engine.invoker.derived.law_overlay.pop(
            (target, row.data.revision), None)
        if rdef.current_law_revision is not None:
            await self._restamp(target, rdef,
                                {"law_revision": row.data.revision},
                                rdef.current_law_revision)

    async def _restamp(self, kind: str, rdef: Any, filters: dict[str, Any],
                       revision: int, *, batch: int = 200) -> int:
        """Move a filtered population to ``revision`` and recompute each
        row under it — the §4 backfill's discipline (pages, FOR UPDATE,
        no events, no version bumps: recomputation is maintenance) with a
        stamp move riding it. Returns the number of rows moved."""
        storage = self.engine.storage
        maintainer = self.engine.invoker.derived
        clock = self.engine.invoker.clock
        # ids first, then batched writes: a restamp can move a row out of
        # (or keep it inside) its own filter set, and paging a result set
        # the loop is mutating would skip or repeat rows
        ids: list[str] = []
        page = 1
        while True:
            async with storage.session() as s:
                rows, _ = await storage.query(
                    s, kind, filters=filters, sort=None,
                    page_size=batch, page_number=page)
            ids.extend(r.id for r in rows)
            if len(rows) < batch:
                break
            page += 1
        total = 0
        for start in range(0, len(ids), batch):
            now = clock()
            async with storage.session() as s:
                for row_id in ids[start:start + batch]:
                    instance = await storage.load(s, kind, row_id,
                                                  for_update=True)
                    if instance is None or instance.law_revision == revision:
                        continue
                    instance.law_revision = revision
                    await maintainer.materialize(s, instance, rdef, now=now)
                    await storage.update_data(s, kind, instance)
                    total += 1
        return total

    async def _sweep_empty_laws(self, kind: str, rdef: Any) -> None:
        """Supersede-when-empty, checked lazily (design §1/§3): on every
        adopt or terminal transition of a kind with grandfathered
        revisions, count each one's survivors — stamped, non-terminal
        rows — and supersede the empty, as the system deploy actor. The
        February story's last line: revision 41 is superseded months
        later, on the day its last workbook closes, and the log knows
        the day. (Nested cascade terminals miss this check until the
        next entry-point trigger or boot — lazy is the declaration.)"""
        engine = self.engine
        async with engine.storage.session() as s:
            rows, _ = await engine.storage.query(
                s, "definition",
                filters={"target_kind": kind,
                         "state": str(DefinitionState.GRANDFATHERED)},
                sort=None, page_size=50, page_number=1)
        remaining = False
        for row in rows:
            async with engine.storage.session() as s:
                survivors = await engine.storage.law_survivors(
                    s, kind, row.data.revision, rdef.machine.terminal)
            if survivors:
                remaining = True
                continue
            async with engine.invoker._flip_session() as s:
                await engine.invoker._invoke_in_session(
                    s, "definition", row.id, "supersede", None,
                    principal=DEPLOY, if_match=None, idempotency_key=None,
                    dry_run=False, locale="en",
                    correlation_id=uuid.uuid4().hex, require_key=False)
            engine.invoker.derived.law_overlay.pop(
                (kind, row.data.revision), None)
            log.info("revision %d of %s superseded: its last stamped row "
                     "adopted or closed (laws die when they are empty)",
                     row.data.revision, kind)
        if not remaining:
            engine._grandfathered_kinds.discard(kind)
