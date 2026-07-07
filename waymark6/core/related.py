"""Relationships are declarations (design 6.0 §1).

A :class:`Related` edge names a target kind and a conjunction of field
comparisons between *our* stored fields and *theirs*::

    _calendar = Related("event", on=(
        On(ours="start_date", op="<=", theirs="date"),
        On(ours="end_date",   op=">=", theirs="date"),
    ))

The predicate is data about the definition: it rides the fingerprint,
it is validated at assembly (``checks.check_related``, in the
``check_derived_edges`` tradition), and every consumer reads the one
declaration — ``Related.field()`` is a Derived input exactly as
``Owns.field()`` is (§2), and a ``link(edge=...)`` compiles its href
from the predicate instead of hand-templating a query string the
checks cannot see.

``Owns`` is conceptually a subtype — an ownership edge *is* a relation
whose predicate is ``On(ours="id", op="==", theirs=via)`` plus the
obligations only ownership can claim (cascade, seed, lifetime). The
subtyping is semantic, not structural: consumers that accept a relation
accept either declaration; consumers that demand ownership still demand
``Owns``. Nothing about ``Owns`` changes here.

What is deliberately unrepresentable: a predicate over :class:`Clock`.
A relation whose membership drifts as time passes, with no write on
either side, would make §2's materialization guarantee a lie on a
timer — ``On`` takes stored field names only, and the stored-boundaries
discipline (a plan's week is ``start_date``/``end_date``, not "the
current week") covers every case the dogfoods produced. Recorded punt.
"""
from __future__ import annotations

from dataclasses import dataclass, field as dc_field
from typing import Any, Mapping

from .types import DefinitionError

RELATED_OPS = ("==", "<=", ">=", "<", ">")

# anchor.ours <op> target.theirs, read from each side. Inverted: given
# one target row's value, the filter suffix selecting matching ANCHORS
# (anchor.ours <op> value). Forward: given one anchor's value, the
# suffix selecting matching TARGETS (value <op> target.theirs).
_INVERTED_SUFFIX = {"==": "", "<=": "_lte", ">=": "_gte",
                    "<": "_lt", ">": "_gt"}
_FORWARD_SUFFIX = {"==": "", "<=": "_gte", ">=": "_lte",
                   "<": "_gt", ">": "_lt"}


@dataclass(frozen=True)
class On:
    """One comparison of the conjunction: ``anchor.ours op target.theirs``.
    Both sides are stored Data field names; the checks demand they be
    promoted (filterable or sortable) columns, because both directions of
    the predicate must be indexable — invertibility is §2's guarantee,
    not an optimization."""

    ours: str
    op: str
    theirs: str

    def __post_init__(self) -> None:
        for side, value in (("ours", self.ours), ("theirs", self.theirs)):
            if not isinstance(value, str) or not value:
                raise DefinitionError(
                    f"On({side}={value!r}) — a Related predicate compares "
                    "stored Data fields by name; anything else (Clock "
                    "included) is unrepresentable by design (design §1)")
        if self.op not in RELATED_OPS:
            raise DefinitionError(
                f"On(op={self.op!r}) is not one of {list(RELATED_OPS)}")


@dataclass(frozen=True)
class Related:
    """One declared relationship: the target kind and the conjunction of
    ``On`` comparisons that select the related rows. Validated at engine
    assembly, where every kind is known."""

    kind: str
    on: tuple["On", ...] = ()

    def __post_init__(self) -> None:
        if not self.kind:
            raise DefinitionError("Related requires kind=")
        object.__setattr__(self, "on", tuple(self.on))
        if not self.on:
            raise DefinitionError(
                "Related requires on=(On(...), ...) — an edge with no "
                "predicate relates everything to everything")
        for cond in self.on:
            if not isinstance(cond, On):
                raise DefinitionError(
                    f"Related on= entries are On(...) declarations, got "
                    f"{cond!r}")

    def field(self, name: str, *, where: Mapping[str, Any] | None = None):
        """A derivation input over this edge (design §2): ``name`` on
        every related row (``"state"`` and ``"id"`` included), optionally
        filtered by ``where`` — the values reach the derivation's ``fn``
        as a list, exactly as ``Owns.field()`` delivers children."""
        return RelatedField(kind=self.kind, on=self.on, field=name,
                            where=dict(where or {}))


@dataclass(frozen=True)
class RelatedField:
    """A related-rows input to a Derived field: one field of every target
    row the edge's predicate selects for the anchor (optionally filtered
    by ``where``, which — like the predicate — must run on promoted
    target columns). Spelled ``edge.field("kind")`` / ``edge.field("id",
    where=...)``; ``Count``/``Sum`` over a Related edge produce exactly
    this input."""

    kind: str
    on: tuple[On, ...]
    field: str
    where: Mapping[str, Any] = dc_field(default_factory=dict)


def forward_filters(on: tuple[On, ...], data: Any) -> dict[str, Any] | None:
    """The forward read: filters selecting the TARGET rows related to one
    anchor, from the anchor's own stored values. ``None`` when a join
    value is null — a row with no boundary relates to nothing."""
    filters: dict[str, Any] = {}
    for cond in on:
        value = getattr(data, cond.ours, None)
        if value is None:
            return None
        filters[cond.theirs + _FORWARD_SUFFIX[cond.op]] = value
    return filters


def inverted_filters(on: tuple[On, ...],
                     values: Mapping[str, Any]) -> dict[str, Any] | None:
    """The inverted predicate (design §2): filters selecting the ANCHOR
    rows related to one target row, from that row's field values —
    ``values`` may be the live data or a ``before`` dump, which is how a
    single helper serves both halves of the old/new-set union. ``None``
    when a join value is null."""
    filters: dict[str, Any] = {}
    for cond in on:
        value = values.get(cond.theirs)
        if value is None:
            return None
        filters[cond.ours + _INVERTED_SUFFIX[cond.op]] = value
    return filters


def on_wire(on: tuple[On, ...]) -> list[dict[str, str]]:
    """The predicate as deterministic data — the fingerprint facet."""
    return [{"ours": c.ours, "op": c.op, "theirs": c.theirs} for c in on]


def compile_edge_links(registry: Any) -> None:
    """Compile every edge-cited link's href from its predicate (design
    §1): the target collection filtered by the §3 grammar, with ``ours``
    values interpolated at render — equality → ``?field={data.ours}``,
    range pairs → ``?field_gte={data.start}&field_lte={data.end}``. Runs
    at engine assembly, after ``check_related`` proved the params exist
    and type-check; hand-templated hrefs are untouched — the escape
    hatch stays honest."""
    for rdef in registry.defs():
        for ld in getattr(rdef.cls, "links", ()) or ():
            edge = getattr(ld, "edge", None)
            if edge is None:
                continue
            target = registry.get(edge.kind)
            if target is None:  # check_related already raised
                continue
            params = "&".join(
                f"{c.theirs}{_FORWARD_SUFFIX[c.op]}={{data.{c.ours}}}"
                for c in edge.on)
            object.__setattr__(ld, "href", f"/{target.plural}?{params}")
