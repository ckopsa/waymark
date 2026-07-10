"""The registry: one ResourceDef per resource, schemas pre-generated and cached."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from . import schemas as schemagen
from .actions import ActionDef
from .machine import StateMachine
from .resource import Resource


def _relations(defn: ActionDef) -> list[tuple[str, str, str]]:
    """Comparison relations (design §5) → the ``x-display.relation`` hints
    the input schema carries on both related fields."""
    out = []
    for guard in defn.guards:
        for g in guard.iter_leaves():
            if g.is_relation and g.op is not None:
                out.append((g.judges[0], g.op, g.judges[1]))
    return out


def _recomputing_blocks(machine: StateMachine) -> dict[str, tuple[str, ...]]:
    """Static fact → action-names index (design §4 follow-up): a
    ``require(fact)`` guard (``core/guards.py``'s ``FactRequired``) is the
    only guard shape that names a gating fact directly (``.fact``), and
    which actions gate on which fact never changes after registration — so
    this is computed once here, not per-request. ``rdef.recomputing`` (the
    dynamic, currently-stale subset) is what render.py filters this
    against; a generic "may predate the current law" told a reader
    nothing about what stopped working — this is what did."""
    out: dict[str, list[str]] = {}
    for name, defn in machine.actions.items():
        facts = {fact for guard in defn.guards for leaf in guard.iter_leaves()
                 if (fact := getattr(leaf, "fact", None))}
        for fact in facts:
            out.setdefault(fact, []).append(name)
    return {fact: tuple(sorted(names)) for fact, names in out.items()}


@dataclass
class ResourceDef:
    cls: type[Resource]
    kind: str
    plural: str
    machine: StateMachine
    data_schema: dict[str, Any]
    data_schema_bytes: bytes
    action_schemas: dict[str, tuple[dict[str, Any], bytes]]
    query_schema: dict[str, Any]
    query_schema_bytes: bytes
    row_model: Any = None  # set by storage
    # the definition revision id currently governing this kind (design §3):
    # stamped by the boot revise (§2), read by every write path as the
    # ``defined_by`` anchor and by render as ``meta.law``. None before the
    # first revise — the pre-law horizon the migration sketch names.
    current_law: str | None = None
    # the same law's revision NUMBER (design §3): stamped beside the row
    # id by the boot revise, rendered as ``meta.law_revision`` so a human
    # client shows "rev N" without resolving the deploy history first
    current_law_revision: int | None = None
    # the held proposal (design 7.0 §1): the ``proposed`` definition
    # revision a propose-mode boot registered for this kind while the §1
    # overlay keeps serving the current law. None outside a hold; the
    # maintainer's ``specs_for`` and the promote/measure residency guard
    # read it. At most one per kind — a stage, not a lattice.
    proposed_law: str | None = None
    proposed_law_revision: int | None = None
    # the piloted revision (design 7.0 §3): the ``piloted`` definition row
    # whose declared population's rows live under it while the current law
    # governs the rest. The resident code IS the piloted law (the pilot
    # gate admits data-law diffs only, and the propose-mode hold keeps the
    # current law served from stored parameters). At most one per kind —
    # the same stage-not-lattice rule as the hold.
    piloted_law: str | None = None
    piloted_law_revision: int | None = None
    # the pilot's Population declaration, as recorded on the pilot
    # transition: {"where": {...}} or {"after": True}. The create path
    # routes new rows by it (design 7.0 §3: creates are judged by the
    # revision whose population claims the input).
    piloted_population: dict[str, Any] | None = None
    # revision NUMBER → definition revision row id, every stored revision
    # of this kind (design 7.0 §3): how a row's integer stamp resolves to
    # the law id its envelope (meta.law) and its writes (defined_by) name.
    # Filled by the boot revise; promote/pilot append to it.
    law_ids: dict[int, str] = field(default_factory=dict)
    # whether the engine itself contributed this kind (definition, grant,
    # member, job, …) as opposed to the app's ``resources=[...]`` — set at
    # registration, where the distinction is a fact rather than a list to
    # maintain; discovery advertises it so a client can fold engine
    # plumbing behind the domain kinds
    engine_owned: bool = False
    # the derived facts still catching up with the current law (design §4):
    # stamped by the boot when a Deferred backfill is declared, cleared as
    # the background task drains each kind. While non-empty, envelopes of
    # the kind carry ``meta.recomputing`` and the named facts are dropped
    # from the collection query schema and refused by the router — the
    # value renders as data, but is never served as *filterable truth*.
    recomputing: tuple[str, ...] = ()
    # static fact → action-names this fact's require() gates (design §4
    # follow-up): computed once at register() from the machine's guards,
    # independent of whether the fact is currently recomputing — render.py
    # filters this against ``recomputing`` per request to say what a
    # stale fact costs, not just that it's stale
    recomputing_blocks: dict[str, tuple[str, ...]] = field(default_factory=dict)
    extra: dict[str, Any] = field(default_factory=dict)

    @property
    def summary_template(self) -> str:
        return self.cls.summary

    def action(self, name: str) -> ActionDef | None:
        return self.machine.actions.get(name)


class Registry:
    def __init__(self) -> None:
        self._by_kind: dict[str, ResourceDef] = {}
        self._by_plural: dict[str, ResourceDef] = {}
        self._schemas: dict[str, tuple[dict[str, Any], bytes]] = {}

    def register(self, cls: type[Resource], *,
                 engine_owned: bool = False) -> ResourceDef:
        if cls.kind in self._by_kind:
            existing = self._by_kind[cls.kind]
            if existing.cls is cls:
                return existing
            raise ValueError(f"kind {cls.kind!r} registered twice "
                             f"({existing.cls.__qualname__} and {cls.__qualname__})")
        data_dict, data_bytes = schemagen.data_schema(cls.Data)
        action_schemas = {
            name: schemagen.input_schema(defn.input, dict(defn.field_display),
                                         relations=_relations(defn))
            for name, defn in cls.__waymark_machine__.actions.items()
            if defn.input is not None
        }
        query_dict, query_bytes = schemagen.query_schema(cls)
        create_model = getattr(cls, "Create", None) or cls.Data
        create_schema, _ = schemagen.input_schema(create_model)
        rdef = ResourceDef(
            cls=cls, kind=cls.kind, plural=cls.plural,
            machine=cls.__waymark_machine__,
            data_schema=data_dict, data_schema_bytes=data_bytes,
            action_schemas=action_schemas,
            query_schema=query_dict, query_schema_bytes=query_bytes,
            engine_owned=engine_owned,
            recomputing_blocks=_recomputing_blocks(cls.__waymark_machine__),
        )
        rdef.extra["create_model"] = create_model
        rdef.extra["create_schema"] = create_schema
        self._by_kind[cls.kind] = rdef
        self._by_plural[cls.plural] = rdef
        # published schema names: the kind for Data, model names for inputs
        self._schemas[cls.kind] = (data_dict, data_bytes)
        for name, defn in cls.__waymark_machine__.actions.items():
            if defn.input is not None:
                self._schemas[defn.input.__name__] = action_schemas[name]
        return rdef

    def __getitem__(self, kind: str) -> ResourceDef:
        return self._by_kind[kind]

    def __contains__(self, kind: str) -> bool:
        return kind in self._by_kind

    def get(self, kind: str) -> ResourceDef | None:
        return self._by_kind.get(kind)

    def by_plural(self, plural: str) -> ResourceDef | None:
        return self._by_plural.get(plural)

    def kinds(self) -> list[str]:
        return list(self._by_kind)

    def defs(self) -> list[ResourceDef]:
        return list(self._by_kind.values())

    def engine_kinds(self) -> list[str]:
        """Kinds the engine itself contributed (vs. app-supplied
        ``resources=[...]``) — the registry knows because registration
        said so; nothing re-derives the list from a hardcoded set."""
        return [k for k, rdef in self._by_kind.items() if rdef.engine_owned]

    def secondary_kinds(self) -> list[str]:
        """Kinds the law marks ``nav="secondary"`` — real domain resources
        that aren't lead entry points, folded behind the nav overflow. The
        sibling of ``engine_kinds``: the machinery hides by ownership, the
        secondary domain by the declared judgment of prominence. Discovery
        advertises the set so the client renders tiers it is told about
        rather than guessing which kinds are relevant."""
        return [k for k, rdef in self._by_kind.items()
                if rdef.cls.nav == "secondary"]

    def schema(self, name: str) -> tuple[dict[str, Any], bytes] | None:
        return self._schemas.get(name)
