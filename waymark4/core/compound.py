"""The compound act (design §6): a declared multi-resource transition.

The fact outgrew the field; the act outgrew the transition. E8 declared
the *touches* (``Advances``/``Creates``, with undeclared ctx writes
aborting); the ``Compound`` makes the same declaration the whole
contract — declaration, enforcement, and advertisement are one object:

    class Wire(Resource):
        owns = (Owns("row", via="wire_id"),)

        carve_out = Compound(
            from_=W.ACTIVE, to=W.ACTIVE, input=CarveInput,
            safety=Safety(...),
            creates=(Create("event", seed={"label": "{input.new_label}"}),),
            advances=(Each("row", action="reassign", where={"flag": "move"},
                           input={"event_id": "{created.event.id}"}),
                      Advance("counter_id", action="decrement")),
            effects=(ServiceEffect("blob", "copy",
                                   args=("docs/{id}/",
                                         "docs/{created.event.id}/"),
                                   compensate=Op("delete",
                                                 args=("docs/{created.event.id}/",))),),
        )

- It IS an action on the wire: the declaration compiles to an ordinary
  ``ActionDef`` (same ``/-/{name}`` route, same guards/safety/input, the
  single invoker), whose generated handler drives every child write
  through ``ctx.create``/``ctx.invoke`` — one transaction, one
  correlation id, E8's touch enforcement compiled from the declaration
  itself.
- External effects are honest: each names a declared ``Service``
  operation and a compensator; the engine runs compensators in reverse
  order on abort and audits every attempt (server/invoke.py settles the
  ledger; deferred compounds ride the E6 job kind).
- The blast radius renders: ``blast_radius()`` folds
  ``creates``/``advances``/``effects`` summaries into the action entry's
  ``effect`` — what E8 enforced, the envelope now advertises.

Template values resolve over a declared scope: ``{id}`` and ``{data.*}``
(the parent), ``{input.*}`` (the validated input), and
``{created.<kind>.id}`` / ``{created.<kind>.data.*}`` (resources this act
created, by kind — creates run first, in declaration order; a second
create of the same kind shadows the first). A value that is exactly one
placeholder resolves to the raw value, type preserved.
"""
from __future__ import annotations

import re
import string
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any

from .touches import Advances, Creates
from .types import Ctx, DefinitionError

_ONE_FIELD = re.compile(r"^\{([^{}]+)\}$")


def _kind_token(kind: Any) -> str:
    return kind if isinstance(kind, str) else getattr(kind, "kind", "") or ""


@dataclass(frozen=True)
class Create:
    """This act creates one resource of ``kind``, seeded from the declared
    ``seed`` body (values may template over the scope)."""

    kind: str
    seed: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        object.__setattr__(self, "kind", _kind_token(self.kind))
        object.__setattr__(self, "seed", dict(self.seed))
        if not self.kind:
            raise DefinitionError("Create requires kind=")
        if not self.seed:
            raise DefinitionError(
                "Create requires seed= — a child of nothing but a kind "
                "says nothing; declare what it carries")


@dataclass(frozen=True)
class Advance:
    """Advance the peer a Ref field of Data names: ``ref`` is the field,
    ``action`` the peer's own transition, ``input`` its (templatable)
    body. An unset optional ref advances nothing."""

    ref: str
    action: str
    input: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        object.__setattr__(self, "input", dict(self.input))
        if not self.ref or not self.action:
            raise DefinitionError("Advance requires ref= and action=")


@dataclass(frozen=True)
class Each:
    """Advance every owned child of ``kind`` matching ``where`` (promoted
    child columns; values may template). The fan-out rides the parent's
    declared Owns edge and is bounded by ``limit``."""

    kind: str
    action: str
    where: Mapping[str, Any] = field(default_factory=dict)
    input: Mapping[str, Any] = field(default_factory=dict)
    limit: int = 500

    def __post_init__(self) -> None:
        object.__setattr__(self, "kind", _kind_token(self.kind))
        object.__setattr__(self, "where", dict(self.where))
        object.__setattr__(self, "input", dict(self.input))
        if not self.kind or not self.action:
            raise DefinitionError("Each requires kind= and action=")
        if self.limit < 1:
            raise DefinitionError("Each(limit=…) must be ≥ 1")


@dataclass(frozen=True)
class Op:
    """A named adapter operation with its (templatable) args — the
    compensator half of a declared effect."""

    op: str
    args: tuple[Any, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "args", tuple(self.args))
        if not self.op:
            raise DefinitionError("Op requires the operation name")


@dataclass(frozen=True)
class ServiceEffect:
    """One declared external write of the act: a named operation on a
    declared Service, with a mandatory compensator — an external write
    without one is the uncompensated S3 copy the design exists to kill
    (design §6)."""

    service: str
    op: str
    args: tuple[Any, ...] = ()
    compensate: Op | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "args", tuple(self.args))
        if not self.service or not self.op:
            raise DefinitionError("ServiceEffect requires service= and op=")
        if self.compensate is None:
            raise DefinitionError(
                f"ServiceEffect({self.service!r}, {self.op!r}) declares no "
                "compensate=Op(…) — an external write without a declared "
                "compensator strands whatever cancellation leaves behind "
                "(design §6)")

    @property
    def name(self) -> str:
        return f"{self.service}.{self.op}"


@dataclass(frozen=True)
class ResolvedEffect:
    """A ServiceEffect with its args resolved against the act's scope —
    what the handler hands the engine's effect executor."""

    name: str
    service: str
    op: str
    args: tuple[Any, ...]
    comp_op: str
    comp_args: tuple[Any, ...]


@dataclass(frozen=True)
class _Step:
    """One resolved advance: a Ref-named peer (``ref``) or an owned
    fan-out (``via`` from the Owns edge)."""

    kind: str
    action: str
    ref: str | None = None
    via: str | None = None
    where: Mapping[str, Any] = field(default_factory=dict)
    input: Mapping[str, Any] = field(default_factory=dict)
    limit: int = 500


# ── template resolution ─────────────────────────────────────────────────
class _Scope:
    def __init__(self, instance: Any, inp: Any):
        self.instance = instance
        self.inp = inp
        self.created: dict[str, dict[str, Any]] = {}

    def lookup(self, path: str) -> Any:
        head, _, rest = path.partition(".")
        if head == "id" and not rest:
            return self.instance.id
        if head == "data" and rest:
            return _dig(self.instance.data, rest)
        if head == "input" and rest:
            if self.inp is None:
                raise KeyError(f"{path}: the compound declares no input")
            return _dig(self.inp, rest)
        if head == "created" and rest:
            kind, _, tail = rest.partition(".")
            doc = self.created.get(kind)
            if doc is None:
                raise KeyError(
                    f"{path}: no {kind!r} was created by this act — creates "
                    "run first, in declaration order")
            if not tail or tail == "id":
                return doc["id"]
            if tail.startswith("data."):
                return _dig(doc["data"], tail[5:])
            raise KeyError(path)
        raise KeyError(
            f"{path}: compound templates resolve id, data.*, input.*, and "
            "created.<kind>…")


def _dig(obj: Any, dotted: str) -> Any:
    out = obj
    for part in dotted.split("."):
        out = out[part] if isinstance(out, Mapping) else getattr(out, part)
    return out


class _ScopeFormatter(string.Formatter):
    def __init__(self, scope: _Scope):
        self.scope = scope

    def get_field(self, field_name: str, args: Any, kwargs: Any) -> Any:
        return self.scope.lookup(field_name), field_name


def resolve(value: Any, scope: _Scope) -> Any:
    if isinstance(value, str) and "{" in value:
        exact = _ONE_FIELD.match(value)
        if exact:  # a lone placeholder keeps the raw value's type
            return scope.lookup(exact.group(1))
        return _ScopeFormatter(scope).vformat(value, (), {})
    return value


def resolve_map(mapping: Mapping[str, Any], scope: _Scope) -> dict[str, Any]:
    return {k: resolve(v, scope) for k, v in mapping.items()}


# ── the declaration ─────────────────────────────────────────────────────
class Compound:
    """A declared multi-resource act, compiled to an ordinary ActionDef
    (design §6). Declared as a class attribute of the resource it acts
    on — the declaration site is the ``on=``."""

    def __init__(
        self,
        *,
        from_: Any,
        to: Any,
        safety: Any,
        input: Any = None,
        guards: Sequence[Any] = (),
        creates: Sequence[Create] = (),
        advances: Sequence[Any] = (),
        effects: Sequence[ServiceEffect] = (),
        defer: bool = False,
        display: Mapping[str, Any] | None = None,
        field_display: Mapping[str, Any] | None = None,
        unless: Any = None,
        waives: Sequence[str] = (),
    ):
        if not (creates or advances or effects):
            raise DefinitionError(
                "Compound declares no creates=, advances=, or effects= — "
                "an act that spans nothing is an @action")
        for c in creates:
            if not isinstance(c, Create):
                raise DefinitionError(
                    f"Compound creates= entries are Create declarations, "
                    f"got {c!r}")
        for a in advances:
            if not isinstance(a, (Advance, Each)):
                raise DefinitionError(
                    f"Compound advances= entries are Advance or Each "
                    f"declarations, got {a!r}")
        for e in effects:
            if not isinstance(e, ServiceEffect):
                raise DefinitionError(
                    f"Compound effects= entries are ServiceEffect "
                    f"declarations, got {e!r}")
        if defer and not effects:
            raise DefinitionError(
                "Compound(defer=True) defers external effects; this act "
                "declares none")
        self.from_ = from_
        self.to = to
        self.safety = safety
        self.input = input
        self.guards = tuple(guards)
        self.creates = tuple(creates)
        self.advances = tuple(advances)
        self.effects = tuple(effects)
        self.defer = bool(defer)
        self.display = dict(display or {})
        self.field_display = dict(field_display or {})
        self.unless = unless
        self.waives = tuple(waives)
        self.name: str | None = None
        self._steps: tuple[_Step, ...] = ()

    # descriptor hook: runs at class creation, before __init_subclass__
    # assembles the machine — the compiled ActionDef is picked up by the
    # ordinary "__waymark_action__" scan, so a Compound IS an action.
    def __set_name__(self, owner: type, name: str) -> None:
        from .actions import action as action_decorator
        from .owns import owns_of
        from .refs import ref_meta

        self.name = name
        where = f"{owner.__module__}.{owner.__qualname__}.{name}"
        steps: list[_Step] = []
        for a in self.advances:
            if isinstance(a, Each):
                edge = next((e for e in owns_of(owner) if e.kind == a.kind),
                            None)
                if edge is None:
                    raise DefinitionError(
                        f"{where}: Each({a.kind!r}) has no matching Owns "
                        "edge on this resource — the fan-out rides a "
                        "declared edge (design §6)")
                steps.append(_Step(kind=a.kind, action=a.action,
                                   via=edge.via, where=a.where,
                                   input=a.input, limit=a.limit))
            else:
                f = getattr(owner, "Data").model_fields.get(a.ref)
                meta = ref_meta(f) if f is not None else None
                if meta is None:
                    raise DefinitionError(
                        f"{where}: Advance({a.ref!r}) names no Ref field of "
                        "Data — the peer to advance must be a declared "
                        "reference (design §6)")
                steps.append(_Step(kind=meta.kind, action=a.action,
                                   ref=a.ref, input=a.input))
        self._steps = tuple(steps)

        # the declaration IS the touch set (design §6): E8's enforcement
        # is compiled from the same object the envelope advertises
        touches: list[Any] = []
        seen: set[tuple] = set()
        for c in self.creates:
            key = ("creates", c.kind)
            if key not in seen:
                seen.add(key)
                touches.append(Creates(c.kind))
        for st in steps:
            key = ("advances", st.kind, st.action)
            if key not in seen:
                seen.add(key)
                touches.append(Advances(st.kind, st.action))

        handler = _make_handler(self)
        handler.__name__ = name
        handler.__qualname__ = f"{owner.__qualname__}.{name}"
        action_decorator(
            from_=self.from_, to=self.to, input=self.input,
            guards=self.guards, safety=self.safety, display=self.display,
            field_display=self.field_display, touches=tuple(touches),
            unless=self.unless, waives=self.waives,
        )(handler)
        from dataclasses import replace

        self.__waymark_action__ = replace(  # type: ignore[attr-defined]
            handler.__waymark_action__, compound=self)

    def blast_radius(self) -> dict[str, Any]:
        """What the act spans, for the action entry's ``effect`` — kind
        and action names and service ops, so a client sees the whole act
        before confirming it (design §6)."""
        out: dict[str, Any] = {}
        if self.creates:
            out["creates"] = [c.kind for c in self.creates]
        if self._steps:
            out["advances"] = [f"{s.kind}.{s.action}" for s in self._steps]
        if self.effects:
            out["effects"] = [e.name for e in self.effects]
            if self.defer:
                out["deferred"] = True
        return out

    def __repr__(self) -> str:  # pragma: no cover — debugging nicety
        return f"Compound({self.name or '?'}: {self.blast_radius()!r})"


def _make_handler(compound: Compound) -> Any:
    async def handler(self, inp, ctx: Ctx) -> None:
        scope = _Scope(self, inp)
        # creates first: later steps and effects may reference the
        # created resources by kind
        for c in compound.creates:
            doc = await ctx.create(c.kind, resolve_map(c.seed, scope))
            scope.created[c.kind] = {
                "id": doc["self"].rsplit("/", 1)[-1],
                "data": dict(doc.get("data") or {}),
            }
        for step in compound._steps:
            body = resolve_map(step.input, scope) or None
            if step.ref is not None:
                target = getattr(self.data, step.ref, None)
                if target is None:
                    continue  # an unset optional ref advances nothing
                await ctx.invoke(step.kind, str(target), step.action, body)
            else:
                filters = {step.via: self.id,
                           **resolve_map(step.where, scope)}
                rows = await ctx.find(step.kind, limit=step.limit, **filters)
                for row in rows:
                    await ctx.invoke(step.kind, row.id, step.action, body)
        if compound.effects:
            resolved = [
                ResolvedEffect(
                    name=e.name, service=e.service, op=e.op,
                    args=tuple(resolve(a, scope) for a in e.args),
                    comp_op=e.compensate.op,
                    comp_args=tuple(resolve(a, scope)
                                    for a in e.compensate.args),
                )
                for e in compound.effects
            ]
            await ctx.run_effects(f"{type(self).kind}.{compound.name}",
                                  resolved, defer=compound.defer)

    # the signature check reads the handler's inp annotation; the compiled
    # handler's is whatever the declaration says
    if compound.input is not None:
        handler.__annotations__["inp"] = compound.input
    return handler
