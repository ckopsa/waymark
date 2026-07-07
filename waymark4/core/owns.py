"""Ownership between kinds (design E4): one declared edge, many consumers.

The mapping series' strongest finding: six apps hand-built some face of
*a kind that owns a kind* — cascade lifecycle, child rollups gating the
parent, template instantiation, parent-scoped bulk. The edge is declared
once, on the parent:

    class MealPlan(Resource):
        owns = (Owns("prep_task", via="plan_id",
                     on={"abandon": "cancel"},
                     rollups={"open_tasks": Rollup(
                         filters={"state": ("pending", "scheduled")})}),)

Consumers in this wave: the cascade runner (``server/owns.py`` — parent
transitions named in ``on`` fan out to owned children as system-actor
transitions sharing the parent's correlation id) and rollups (computed
counts on the envelope + the ``rollup_is`` guard). Template
instantiation, visibility inheritance, and owned-create atomicity are
deliberate punts recorded in the design section.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping

from .guards import RELATION_OPS, Guard
from .types import Allow, Ctx, DefinitionError, Deny


@dataclass(frozen=True)
class Rollup:
    """An aggregated subset of an ``Owns`` edge's children. ``filters``
    take the child collection's promoted fields (``state`` included);
    ``agg="count"`` needs nothing more, ``agg="sum"`` names the child
    field it totals via ``of`` (cash recon's Σbreaks, declared)."""

    filters: Mapping[str, Any] = field(default_factory=dict)
    agg: str = "count"
    of: str | None = None

    def __post_init__(self) -> None:
        if self.agg not in ("count", "sum"):
            raise DefinitionError(
                f"Rollup(agg={self.agg!r}) — count and sum ship; other "
                "aggregates are a recorded punt (design E4)")
        if (self.agg == "sum") != (self.of is not None):
            raise DefinitionError(
                "Rollup: agg='sum' requires of='<child field>' and "
                "agg='count' takes none")


@dataclass(frozen=True)
class Seed:
    """Template instantiation (design E4): creating the parent creates
    one child per matching row of a source kind — the onboarding
    `create_event` proc, intake's checklist copy, and cash recon's account
    registry, as one declaration.

    ``where`` filters the source collection; string values may template
    over the parent (``"{data.fund_type}"``). ``copy`` maps child fields
    from source data fields; ``defaults`` are literal child fields. The
    engine adds the edge's ``via`` itself."""

    kind: str
    where: Mapping[str, Any] = field(default_factory=dict)
    copy: Mapping[str, str] = field(default_factory=dict)
    defaults: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.kind:
            raise DefinitionError("Seed requires kind=")
        if not self.copy and not self.defaults:
            raise DefinitionError(
                "Seed with neither copy= nor defaults= would create "
                "children of nothing but a parent id — say what they carry")


@dataclass(frozen=True)
class Owns:
    """One ownership edge: the child kind, the child's Ref field naming
    the parent (``via``), the cascade map (parent action → child action),
    the edge's rollups, and an optional create-time ``seed``."""

    kind: str
    via: str
    on: Mapping[str, str] = field(default_factory=dict)
    rollups: Mapping[str, "Rollup"] = field(default_factory=dict)
    seed: "Seed | None" = None

    def __post_init__(self) -> None:
        if not self.kind or not self.via:
            raise DefinitionError("Owns requires kind= and via=")

    def field(self, name: str, *, where: Mapping[str, Any] | None = None):
        """A derivation input over this edge (design §2): ``name`` on every
        owned child (``"state"`` and ``"id"`` included), optionally filtered
        by ``where`` — the values reach the derivation's ``fn`` as a list.
        This is how E4's rollups generalize: ``Count``/``Sum`` are library
        derivations over exactly this input."""
        from .derived import ChildField

        return ChildField(kind=self.kind, via=self.via, field=name,
                          where=dict(where or {}))


def owns_of(cls: type) -> tuple[Owns, ...]:
    return tuple(getattr(cls, "owns", ()) or ())


def find_rollup(cls: type, name: str) -> tuple[Owns, Rollup] | None:
    for edge in owns_of(cls):
        rollup = edge.rollups.get(name)
        if rollup is not None:
            return edge, rollup
    return None


def rollup_is(name: str, op: str, value: int, *, explain: str,
              remedies: tuple[str, ...] = ()) -> Guard:
    """A guard over a declared rollup (design E4): the acceptance the
    holdings-recon app hand-built — a child-set predicate gating a parent
    transition — as one declaration the projector also renders.

    The count is re-queried authoritatively at evaluate time (render-time
    rollup values may be stale) and saturates at ``value + 1``: rollup
    gates are threshold gates, not analytics."""
    if op not in RELATION_OPS:
        raise DefinitionError(f"rollup_is op {op!r} not one of "
                              f"{sorted(RELATION_OPS)}")

    async def check(r: Any, inp: Any, ctx: Ctx) -> Allow | Deny:
        found = find_rollup(type(r), name)
        if found is None:
            raise DefinitionError(
                f"{type(r).__name__}: no rollup {name!r} declared on any "
                "Owns edge — rollup_is guards a declaration, not a guess")
        edge, rollup = found
        # counts saturate at value+1; sums read up to the fetch bound —
        # rollup gates are threshold gates, not analytics
        limit = value + 1 if rollup.agg == "count" else 500
        children = await ctx.find(edge.kind, limit=limit,
                                  **{edge.via: r.id}, **dict(rollup.filters))
        if rollup.agg == "count":
            current: Any = len(children)
        else:
            current = sum(getattr(c.data, rollup.of) or 0 for c in children)
        if RELATION_OPS[op](current, value):
            return Allow()
        return Deny(vars={name: current})

    return Guard(
        explain=explain, vars=(name,),
        check=check, reads=(f"rollup:{name}",),
        remedies=remedies, name=f"rollup:{name}",
    )
