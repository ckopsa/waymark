"""The decision surface is a resource (design 6.0 §4).

A :class:`Surface` is a declared composition: an anchor kind, members
naming **declared link rels of the anchor class** (hand-templated or
edge-cited — either way the surface composes what the law relates; it
smuggles no new joins), the affordances it showcases, and optionally the
filter (``attention=``) nominating anchor rows for the client's
dashboard. It is served at ``/surfaces/{name}/{anchor_id}`` as an
ordinary envelope whose data is the anchor's and whose members arrive
embedded.

Three things make it a *resource* rather than a template:

- **Fingerprinted** (``fingerprint.surface_fingerprint``): a surface has
  no rows — the ``__registry__`` precedent for a definition target that
  is not an ordinary kind — and its definition revises across boots with
  a diff, exactly like a kind's. Reordering the columns Elena reviews
  the close by is a ``revise`` transition in the same log as a guard
  change.
- **Grantable**: a grant may name ``surface:{name}`` (the ``view``
  action) and the surface route checks it before rendering; member
  visibility still applies per-kind underneath.
- **Discoverable**: the well-known document lists surfaces, and an
  anchor's envelope links to the surfaces anchored on it.

What a surface deliberately is not: a layout language. Members declare
*what* is co-present and *which* columns matter (``Member.table`` is an
``x-display`` hint on the embedded entry — visibility still governs
actual field presence); arrangement remains the renderer's.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, ClassVar

from .registry import Registry, ResourceDef
from .resource import LinkDef
from .summary import template_fields
from .types import DefinitionError

# kebab-case, and short enough that "surface:{name}" fits the definition
# row's target_kind column (max_length=64)
_NAME_RE = re.compile(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
_NAME_BUDGET = 64 - len("surface:")


@dataclass(frozen=True)
class Member:
    """One member of a surface: a declared link rel of the anchor class,
    optionally with the columns its embedded table should show, in
    order (target-kind Data fields — validated at assembly)."""

    rel: str
    table: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if not isinstance(self.rel, str) or not self.rel:
            raise DefinitionError(
                f"Member(rel={self.rel!r}) — a member names a declared "
                "link rel of the anchor class")
        object.__setattr__(self, "table", tuple(self.table))
        for col in self.table:
            if not isinstance(col, str) or not col:
                raise DefinitionError(
                    f"Member({self.rel!r}): table= entries are field "
                    f"names, got {col!r}")


class Surface:
    """The declaration surface. Subclass, declare, and register with
    ``Engine(surfaces=[...])`` — surfaces are NOT kinds: no storage, no
    machine, no rows."""

    name: ClassVar[str]
    anchor: ClassVar[str]
    title: ClassVar[str | None] = None
    members: ClassVar[tuple[Member, ...]] = ()
    showcase: ClassVar[tuple[str, ...]] = ()
    # a filter over the anchor kind (validated against its query grammar)
    # nominating rows for the client's dashboard strip
    attention: ClassVar[dict[str, Any] | None] = None

    def __init_subclass__(cls, **kwargs: Any) -> None:
        super().__init_subclass__(**kwargs)
        for required in ("name", "anchor"):
            if not isinstance(getattr(cls, required, None), str) \
                    or not getattr(cls, required):
                raise DefinitionError(
                    f"{cls.__qualname__}: missing required surface "
                    f"declaration {required!r}")
        if not _NAME_RE.fullmatch(cls.name):
            raise DefinitionError(
                f"{cls.__qualname__}: surface name {cls.name!r} is not "
                "kebab-case")
        if len(cls.name) > _NAME_BUDGET:
            raise DefinitionError(
                f"{cls.__qualname__}: surface name {cls.name!r} exceeds "
                f"{_NAME_BUDGET} characters — 'surface:{{name}}' must fit "
                "the definition row's target_kind")
        cls.members = tuple(cls.members)
        for m in cls.members:
            if not isinstance(m, Member):
                raise DefinitionError(
                    f"{cls.__qualname__}: members are Member(...) "
                    f"declarations, got {m!r}")
        cls.showcase = tuple(cls.showcase)
        for a in cls.showcase:
            if not isinstance(a, str) or not a:
                raise DefinitionError(
                    f"{cls.__qualname__}: showcase entries are action "
                    f"names, got {a!r}")
        if cls.attention is not None and not isinstance(cls.attention, dict):
            raise DefinitionError(
                f"{cls.__qualname__}: attention= is a dict of filter "
                "params over the anchor kind")
        if cls.title is not None:
            roots = set(template_fields(cls.title))
            if roots - {"anchor"}:
                raise DefinitionError(
                    f"{cls.__qualname__}: title template may only "
                    "reference {anchor.*} paths; got roots "
                    f"{sorted(roots - {'anchor'})}")


@dataclass(frozen=True)
class MemberDef:
    """One member, resolved at assembly: the cited link and its target."""

    rel: str
    table: tuple[str, ...]
    link: LinkDef
    target: ResourceDef


@dataclass
class SurfaceDef:
    """One surface, assembled per engine (the ResourceDef discipline):
    the declaration plus the boot-stamped law anchor. Never registered
    as a kind — every per-kind iteration that assumes storage excludes
    surfaces by construction."""

    cls: type[Surface]
    name: str
    anchor: ResourceDef
    members: tuple[MemberDef, ...]
    showcase: tuple[str, ...]
    title: str | None
    attention: dict[str, Any] | None
    # the definition revision currently governing this surface (design
    # §4 via the 5.0 anchor): stamped by the boot revise, rendered as
    # the surface envelope's meta.law / meta.law_revision
    current_law: str | None = None
    current_law_revision: int | None = None

    @property
    def target_kind(self) -> str:
        return f"surface:{self.name}"


def _err(surface: type[Surface], msg: str) -> DefinitionError:
    return DefinitionError(f"surface {surface.name!r}: {msg}")


def _resolve_target(registry: Registry, surface: type[Surface],
                    ld: LinkDef) -> ResourceDef:
    """The member link's target kind: an edge-cited link names it on the
    edge; a templated link names it as ``kind=`` (``{kind}_collection``
    for collection links, the bare kind for to-one links)."""
    edge = getattr(ld, "edge", None)
    if edge is not None:
        target = registry.get(edge.kind)
        if target is not None:
            return target
    if ld.kind.endswith("_collection"):
        target = registry.get(ld.kind[: -len("_collection")])
        if target is not None:
            return target
    target = registry.get(ld.kind)
    if target is None:
        raise _err(surface,
                   f"member {ld.rel!r} cites link kind {ld.kind!r}, "
                   "which resolves to no registered kind")
    return target


def _check_attention(surface: type[Surface], anchor: ResourceDef) -> None:
    props = anchor.query_schema.get("properties", {})
    for param, value in (surface.attention or {}).items():
        schema = props.get(param)
        if schema is None:
            raise _err(surface,
                       f"attention param {param!r} is not in the query "
                       f"grammar of {anchor.kind!r} — the dashboard "
                       "queries what the collection route can answer")
        if "enum" in schema and isinstance(value, str):
            parts = value.split(",") if schema.get("x-in") else [value]
            bad = [v for v in parts if v not in schema["enum"]]
            if bad:
                raise _err(surface,
                           f"attention param {param!r} admits "
                           f"{schema['enum']}; got {bad}")


def assemble_surfaces(registry: Registry,
                      surfaces: Any) -> dict[str, SurfaceDef]:
    """Validate every declared surface against the assembled registry
    (the ``check_related`` tradition: refusals happen where every kind
    is known, never at first request) and return name → SurfaceDef."""
    out: dict[str, SurfaceDef] = {}
    for surface in surfaces or ():
        if not (isinstance(surface, type) and issubclass(surface, Surface)):
            raise DefinitionError(
                f"Engine(surfaces=[...]) takes Surface subclasses, got "
                f"{surface!r}")
        if surface.name in out:
            raise DefinitionError(
                f"duplicate surface name {surface.name!r} "
                f"({out[surface.name].cls.__qualname__} and "
                f"{surface.__qualname__})")
        anchor = registry.get(surface.anchor)
        if anchor is None:
            raise _err(surface,
                       f"anchor kind {surface.anchor!r} is not registered "
                       "on this engine")
        declared_links = {ld.rel: ld for ld in anchor.cls.links}
        members: list[MemberDef] = []
        seen_rels: set[str] = set()
        for m in surface.members:
            if m.rel in seen_rels:
                raise _err(surface, f"member {m.rel!r} is declared twice")
            seen_rels.add(m.rel)
            ld = declared_links.get(m.rel)
            if ld is None:
                raise _err(surface,
                           f"member {m.rel!r} names no declared link rel "
                           f"of {surface.anchor!r} — the surface composes "
                           "what the law relates")
            target = _resolve_target(registry, surface, ld)
            for col in m.table:
                if col not in target.cls.Data.model_fields:
                    raise _err(surface,
                               f"member {m.rel!r}: table column {col!r} "
                               f"is not a data field of {target.kind!r}")
            members.append(MemberDef(rel=m.rel, table=m.table, link=ld,
                                     target=target))
        for a in surface.showcase:
            if a not in anchor.machine.actions:
                raise _err(surface,
                           f"showcase names unknown action {a!r} of "
                           f"{surface.anchor!r}")
        _check_attention(surface, anchor)
        out[surface.name] = SurfaceDef(
            cls=surface, name=surface.name, anchor=anchor,
            members=tuple(members), showcase=surface.showcase,
            title=surface.title,
            attention=dict(surface.attention) if surface.attention else None)
    return out
