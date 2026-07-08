"""The Resource base class and declaration helpers (§10.1, §10.6).

A resource definition is the spec, the docs, the contract, and the test
oracle. ``__init_subclass__`` assembles the state machine from the declared
``@action``s and runs every import-time check before the module finishes
importing.
"""
from __future__ import annotations

import enum
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, ClassVar

from pydantic import BaseModel

from . import checks
from .actions import ActionDef
from .guards import Guard
from .machine import StateMachine
from .owns import Immediate, Never


def unique_groups(cls: type["Resource"]) -> tuple[tuple[str, ...], ...]:
    """The declared uniqueness entries, normalized to field tuples."""
    return tuple((entry,) if isinstance(entry, str) else tuple(entry)
                 for entry in cls.unique)


class FilterOp(enum.Flag):
    EQ = enum.auto()
    IN = enum.auto()
    RANGE = enum.auto()   # → {field}_gte / {field}_lte
    AFTER = enum.auto()   # → {field minus _at}_after (RFC 3339)


class filterable:
    """``filterable(state=filterable.Eq | filterable.In, total=filterable.Range)``"""

    Eq = FilterOp.EQ
    In = FilterOp.IN
    Range = FilterOp.RANGE
    After = FilterOp.AFTER

    def __init__(self, **fields: FilterOp):
        self.fields: dict[str, FilterOp] = fields


@dataclass(frozen=True)
class SortableSpec:
    fields: tuple[str, ...]
    default: str


def sortable(*fields: str, default: str) -> SortableSpec:
    if default.lstrip("-") not in fields:
        raise checks.DefinitionError(
            f"sortable default {default!r} is not one of the sortable fields {fields}")
    return SortableSpec(fields=fields, default=default)


@dataclass(frozen=True)
class Profile:
    """A named embedding profile (§4.1): link relation → depth."""

    embed: dict[str, str] = field(default_factory=dict)


def profile(*, embed: dict[str, str]) -> Profile:
    for depth in embed.values():
        if depth not in ("summary", "full"):
            raise checks.DefinitionError(
                f"profile embed depth must be 'summary' or 'full', got {depth!r}")
    return Profile(embed=dict(embed))


@dataclass(frozen=True)
class LinkDef:
    """A declared link relation (§4): rel → target kind + href derivation.

    ``href`` is a template over the instance (e.g. "/customers/{data.customer_id}",
    relative to the API base); ``kind`` names the target resource kind.
    ``embed=True`` invites the client to co-present the target inline with
    this document; ``badge`` names a Data field whose current value rides
    the rendered link as scent (a count the client can show before the
    traversal, not after).

    ``edge`` cites a declared ``Related`` edge instead (design 6.0 §1):
    the href is *compiled* from the predicate at engine assembly — the
    target collection filtered by the §3 grammar — so the target kind,
    the params, and their types are checked where everything else is.
    Mutually exclusive with a templated ``href``; ``kind`` defaults to
    ``{target}_collection``. (The design doc spells the citation
    ``rel=``, which collides with this first positional parameter — the
    shipped spelling is ``edge=``; see waymark7-notes.)
    """

    rel: str
    kind: str
    href: str | None = None
    summary: str | None = None
    embed: bool = False
    badge: str | None = None
    edge: Any = None


def link(rel: str, *, kind: str | None = None, href: str | None = None,
         edge: Any = None, summary: str | None = None,
         embed: bool = False, badge: str | None = None) -> LinkDef:
    if edge is not None and href is not None:
        raise checks.DefinitionError(
            f"link({rel!r}): href= and edge= are mutually exclusive — an "
            "edge-cited link's href is compiled from the declared "
            "predicate (design 6.0 §1)")
    if edge is None and href is None:
        raise checks.DefinitionError(
            f"link({rel!r}) needs either href= (a template over the "
            "instance) or edge= (a Related declaration)")
    if edge is not None:
        target = getattr(edge, "kind", None)
        if not target:
            raise checks.DefinitionError(
                f"link({rel!r}): edge= takes a Related declaration")
        kind = kind or f"{target}_collection"
    elif kind is None:
        raise checks.DefinitionError(
            f"link({rel!r}) with href= requires kind= naming the target")
    return LinkDef(rel=rel, kind=kind, href=href, summary=summary,
                   embed=embed, badge=badge, edge=edge)


class Resource:
    # ── declaration surface (class attributes) ─────────────────────────
    kind: ClassVar[str]
    State: ClassVar[type[enum.StrEnum]]
    Data: ClassVar[type[BaseModel]]
    initial: ClassVar[Any]
    terminal: ClassVar[set[Any]] = set()
    summary: ClassVar[str]
    plural: ClassVar[str]
    filterable: ClassVar[filterable | None] = None
    sortable: ClassVar[SortableSpec | None] = None
    # filter fields whose observed values render as the param's enum (with
    # counts) on every collection — for dynamic vocabularies a static enum
    # can't know. "state" is always faceted; declaring it here is redundant.
    faceted: ClassVar[tuple[str, ...]] = ()
    # declared uniqueness (design E2): each entry is a field or a tuple of
    # fields whose combination must be unique across the kind. Enforced as
    # a database constraint on the promoted columns; the refusal is a
    # Problem carrying a link to the conflicting resource.
    unique: ClassVar[tuple[str | tuple[str, ...], ...]] = ()
    # create-time guards (design E9): each guard judges the VALIDATED
    # CREATE INPUT — its check is called check(None, data, ctx) with
    # r=None, because no instance exists yet; ``judges=`` names fields of
    # the create model (Create, else Data); ``accepts=`` constrains the
    # input as on any guard. Severity splits as on actions (design E1):
    # refuse denies the create outright; a warning demands acknowledgment,
    # and the override lands on the create's transition row.
    create_guards: ClassVar[tuple[Guard, ...]] = ()
    # declared ownership edges (design E4): Owns(child_kind, via=...) —
    # cascade transitions and rollups hang off the one edge. Validated at
    # engine assembly (checks.check_owns), where every kind is known.
    owns: ClassVar[tuple[Any, ...]] = ()
    profiles: ClassVar[dict[str, Profile]] = {}
    links: ClassVar[tuple[LinkDef, ...]] = ()
    display: ClassVar[dict[str, Any]] = {}
    # Where this kind sits in the workspace navigation. "primary" — a lead
    # entry point, inline in the nav bar; "secondary" — a real domain kind
    # that isn't a first stop, folded behind the nav's overflow menu. The
    # sibling of the ``engine_kinds`` fold: the machinery hides by
    # ownership, the secondary domain hides by the law's own judgment of
    # prominence — so a helpful domain can grow past a handful of kinds
    # without the nav becoming a wall. Discovery advertises the secondary
    # set; the client renders the tiers it is told about (it never guesses
    # relevance). Fingerprinted as advertisement — revising which kinds
    # lead is a law change, but a cosmetic one that recomputes nothing.
    nav: ClassVar[str] = "primary"
    allow_dead: ClassVar[set[Any]] = set()
    row_affordances: ClassVar[bool] = True
    spans: ClassVar[tuple[type, ...]] = ()   # workflow resources (§14)
    renames: ClassVar[dict[str, str]] = {}   # state-migration map (§16)
    # The continuity map, generalized (design §5): v1's ``renames`` covered
    # states; these cover the rest of the vocabulary. Each maps an old
    # name to its current spelling (chains allowed: old → older → current).
    # A revision that renames or removes an action must declare it here —
    # boot refuses a transition log whose action names are unreachable
    # through the chain, and the replay conformance reads history across
    # the rename. ``renamed_fields`` records fact/field renames for the
    # same audit continuity (it rides the fingerprint, so the rename is
    # in the revise's diff).
    renamed_actions: ClassVar[dict[str, str]] = {}
    renamed_fields: ClassVar[dict[str, str]] = {}
    # The create spelling (design §2): the action name the engine's create
    # path records when it writes an instance of this kind. One code path,
    # one declared label — a kind whose creation MEANS something more
    # specific than "create" (the definition kind's non-first revisions
    # are the design's `revise` deploy transition) declares the honest
    # name here instead of forking the invoker. ``created_as()`` picks the
    # spelling per instance; ``create_action_names`` declares every name
    # it may return, and is what the continuity check and the replay
    # conformance read (the ``renamed_actions`` precedent: the vocabulary
    # lives on the current class, covering history written before it).
    create_action_names: ClassVar[frozenset[str]] = frozenset({"create"})

    def created_as(self) -> str:
        """The action name this instance's creation is logged under. Must
        return a member of ``create_action_names`` — the invoker refuses
        an undeclared spelling at write time."""
        return "create"
    # The create landing (design 7.0 §1): the states a creation of this
    # kind may land in *beyond* the machine's initial — the
    # ``create_action_names`` pattern applied to the state half of the
    # vocabulary. The definition kind's propose-mode deploys are born
    # ``proposed``; everything else keeps the initial. ``created_in()``
    # picks per instance; the invoker refuses an undeclared landing, and
    # the replay conformance reads history against the declared set.
    create_state_names: ClassVar[frozenset[str]] = frozenset()

    def created_in(self) -> str:
        """The state this instance's creation lands in. Must be the
        machine's initial state or a member of ``create_state_names`` —
        where a creation lands is declared, never improvised."""
        return type(self).__waymark_machine__.initial
    # Declared adoption policy (design 7.0 §3): what a newer current
    # revision of this kind's law means for rows already living. Immediate
    # (the default) is today's behavior spelled out — the promote/revise
    # backfill restamps and recomputes every row at once. Never
    # grandfathers: rows finish under their birth law, the old revision is
    # `grandfathered` until its last stamped non-terminal row adopts or
    # closes, and the engine-injected `adopt` action is each row's
    # explicit, recorded way forward. Fingerprinted — changing the policy
    # is a law change.
    adoption: ClassVar[type] = Immediate
    # Unconditional input retention (design 7.0 §5): every transition of
    # this kind stores its validated input payload, not only the digest.
    # The definition kind declares it ("the law does not get privacy from
    # its subjects"); apps opt in per action with ``record=Inputs()``.
    record_inputs: ClassVar[bool] = False
    # Declared data-shape versioning (design §8): rows are stamped with the
    # shape they were written at; a row older than the declared shape is
    # upcast at read through the chain — upcasts[n] takes a shape-n data
    # dict to shape n+1. The ad-hoc before-validator doing silent shape
    # folding (v2's _legacy_theme) is what this replaces.
    shape: ClassVar[int] = 1
    upcasts: ClassVar[dict[int, Any]] = {}
    # Declared deferral of the stale-by-definition backfill (design §4):
    # None means a deploy that changed a derivation's semantics recomputes
    # every row inside startup, before the kind serves; Deferred(batch=,
    # pause=) says the table is too large to hold the door for, and the
    # redefined facts serve marked ``meta.recomputing`` and un-advertised
    # from the query surface until a background task catches them up.
    backfill: ClassVar[Any] = None
    # What a Ref pointing here denormalizes into its label field (design
    # §4). Defaults to "{data.name}" when the Data model has a name field —
    # the engine maintains referring labels on every write, so the
    # hand-copied `day.meal_name = meal.data.name` is not the app's job.
    label_template: ClassVar[str | None] = None

    __waymark_machine__: ClassVar[StateMachine]

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        if cls.__dict__.get("__waymark_abstract__", False):
            return
        for required in ("kind", "State", "Data", "initial", "summary"):
            if not hasattr(cls, required):
                raise checks.DefinitionError(
                    f"{cls.__qualname__}: missing required declaration {required!r}")
        if "plural" not in cls.__dict__ and not hasattr(cls, "plural"):
            cls.plural = f"{cls.kind}s"
        elif not getattr(cls, "plural", None):
            cls.plural = f"{cls.kind}s"

        actions: dict[str, ActionDef] = {}
        for klass in reversed(cls.__mro__):
            for name, member in vars(klass).items():
                defn = getattr(member, "__waymark_action__", None)
                if defn is not None:
                    actions[name] = defn

        # Vocab fields (design §6) merge themselves into the filter/facet
        # surface — the app declares the field once; the dual
        # filterable-plus-faceted declaration is the framework's to derive.
        from .vocab import model_vocabs

        vocabs = model_vocabs(cls.Data) if hasattr(cls, "Data") else {}
        if vocabs:
            merged = dict(cls.filterable.fields) if cls.filterable else {}
            for fname in vocabs:
                merged.setdefault(fname, FilterOp.EQ | FilterOp.IN)
            spec = filterable()
            spec.fields = merged
            cls.filterable = spec
            observed = tuple(f for f, s in vocabs.items() if s.get("facet"))
            cls.faceted = tuple(dict.fromkeys((*cls.faceted, *observed)))

        if cls.nav not in ("primary", "secondary"):
            raise checks.DefinitionError(
                f"{cls.__qualname__}: nav={cls.nav!r} — must be "
                '"primary" (a lead nav entry) or "secondary" (folded behind '
                "the nav overflow)")

        if cls.adoption not in (Immediate, Never):
            raise checks.DefinitionError(
                f"{cls.__qualname__}: adoption= takes the Immediate or "
                "Never policy token (design 7.0 §3)")

        if cls.shape < 1:
            raise checks.DefinitionError(
                f"{cls.__qualname__}: shape={cls.shape} — shapes start at 1")
        expected = set(range(1, cls.shape))
        declared = set(cls.upcasts)
        if declared != expected:
            raise checks.DefinitionError(
                f"{cls.__qualname__}: shape={cls.shape} requires upcasts for "
                f"exactly {sorted(expected)} (each n → n+1); got "
                f"{sorted(declared)} — a gap in the chain would strand "
                "stored rows")

        states = tuple(str(s) for s in cls.State)
        machine = StateMachine.build(
            states=states,
            initial=str(cls.initial),
            terminal=frozenset(str(s) for s in cls.terminal),
            actions=actions,
        )
        unknown_landings = set(cls.create_state_names) - set(states)
        if unknown_landings:
            raise checks.DefinitionError(
                f"{cls.__qualname__}: create_state_names names undeclared "
                f"states {sorted(unknown_landings)}")
        cls.__waymark_machine__ = machine
        checks.run_all(
            cls, machine,
            allow_dead=frozenset(str(s) for s in cls.allow_dead),
            summary_template=cls.summary,
        )

    # ── instance surface (a hydrated row) ──────────────────────────────
    def __init__(self, *, id: str, state: str, data: BaseModel, version: int = 1,
                 created_at: datetime | None = None, updated_at: datetime | None = None,
                 owner: str | None = None):
        self.id = id
        self.state = state
        self.data = data
        self.version = version
        self.created_at = created_at
        self.updated_at = updated_at
        # the creating actor (design §9), stamped by the engine at create;
        # never the handler's to set
        self.owner = owner
        # the row's law (design 7.0 §3): the definition revision NUMBER of
        # this kind's law that governs this row — stamped by the engine at
        # create (to the revision whose population claims it), changed only
        # by an explicit adopt/pilot restamp. None = pre-stamp (an upgraded
        # row before its boot stamping, or a bare instance outside storage).
        self.law_revision: int | None = None

    async def on_create(self, ctx: Any) -> None:
        """Hook for initial data that depends on other resources (§14).

        The engine calls this once, after validation and before the first
        insert, with a full ``Ctx`` (``read``/``find``/``invoke``). Mutate
        ``self.data`` only — the initial state is the machine's to declare.
        """

    def __repr__(self) -> str:
        return (f"<{type(self).__name__} id={self.id} state={self.state} "
                f"v{self.version}>")
