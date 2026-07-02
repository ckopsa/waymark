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
    profiles: ClassVar[dict[str, Profile]] = {}
    links: ClassVar[tuple[LinkDef, ...]] = ()
    display: ClassVar[dict[str, Any]] = {}
    allow_dead: ClassVar[set[Any]] = set()
    row_affordances: ClassVar[bool] = True
    spans: ClassVar[tuple[type, ...]] = ()   # workflow resources (§14)
    renames: ClassVar[dict[str, str]] = {}   # state-migration map (§16)

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
                 created_at: datetime | None = None, updated_at: datetime | None = None):
        self.id = id
        self.state = state
        self.data = data
        self.version = version
        self.created_at = created_at
        self.updated_at = updated_at

    def __repr__(self) -> str:
        return (f"<{type(self).__name__} id={self.id} state={self.state} "
                f"v{self.version}>")
