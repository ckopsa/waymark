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
from .machine import StateMachine


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
    """

    rel: str
    kind: str
    href: str
    summary: str | None = None


def link(rel: str, *, kind: str, href: str, summary: str | None = None) -> LinkDef:
    return LinkDef(rel=rel, kind=kind, href=href, summary=summary)


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
    profiles: ClassVar[dict[str, Profile]] = {}
    links: ClassVar[tuple[LinkDef, ...]] = ()
    display: ClassVar[dict[str, Any]] = {}
    allow_dead: ClassVar[set[Any]] = set()
    row_affordances: ClassVar[bool] = True
    spans: ClassVar[tuple[type, ...]] = ()   # workflow resources (§14)
    renames: ClassVar[dict[str, str]] = {}   # state-migration map (§16)
    # Declared data-shape versioning (design §8): rows are stamped with the
    # shape they were written at; a row older than the declared shape is
    # upcast at read through the chain — upcasts[n] takes a shape-n data
    # dict to shape n+1. The ad-hoc before-validator doing silent shape
    # folding (v2's _legacy_theme) is what this replaces.
    shape: ClassVar[int] = 1
    upcasts: ClassVar[dict[int, Any]] = {}
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

    async def on_create(self, ctx: Any) -> None:
        """Hook for initial data that depends on other resources (§14).

        The engine calls this once, after validation and before the first
        insert, with a full ``Ctx`` (``read``/``find``/``invoke``). Mutate
        ``self.data`` only — the initial state is the machine's to declare.
        """

    def __repr__(self) -> str:
        return (f"<{type(self).__name__} id={self.id} state={self.state} "
                f"v{self.version}>")
