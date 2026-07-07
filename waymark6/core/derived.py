"""Derived fields (design §1, §2): truth declares its source.

The 3.0 mappings' residue was one problem in six coats — the computed
fact with no declared home, recomputed at every point of use until the
definitions drifted (four spellings of ``recon_difference``, three
tolerances; ``overdue`` in twelve parallel queries). 4.0 gives the fact
a field: a derivation is a named, typed Data field whose value is a pure
function of declared inputs, computed by the engine's maintainer and
stored in the row like any other field — so render, filter, sort,
guards, and the bus all read the one definition::

    ifts = Owns("ift", via="wire_id")

    class WireData(BaseModel):
        amount: float
        open_ifts: int = Count(ifts, where={"state": ("open",)})
        all_ifts_terminal: bool = Derived(
            over=(ifts.field("state"),),
            fn=lambda states: all(s in TERMINAL for s in states),
            explain="{open} transfer(s) are still open.",
            vars=lambda states: {"open": sum(s not in TERMINAL for s in states)})
        difference: float = Derived(over=("amount", "total_settled"),
                                    fn=lambda a, t: a - t)
        reconciled: bool = Derived(over=("difference",),
                                   within=Tolerance("0.00001"))

    class StepData(BaseModel):
        due_at: AwareDatetime
        overdue: bool = Derived(over=("due_at", Clock),
                                fn=lambda due, now: now > due)

``over=`` names the declared inputs, positionally matching ``fn``'s
arguments: an own Data field by name, an owned-children field through an
:class:`~.owns.Owns` edge (``edge.field("state")`` — the values arrive
as a list), a related-rows field through a :class:`~.related.Related`
edge (design 6.0 §2 — same spelling, same list), or :class:`Clock` for
``now``. ``within=Tolerance(...)`` is
sugar for ``abs(x) <= tol`` over a single numeric input — THE tolerance,
stated once. ``explain=``/``vars=`` are carried on the declaration for
the guard/refusal surface (design §5) to consume.

Field origins are exclusive and checked at import (``checks.py``): an
action input model naming a derived field is a :class:`DefinitionError`;
a handler that assigns one is refused by the engine at write time; a
create body that supplies one is refused at validation. On the wire the
value renders inside ``data`` like any field, and the published schema
marks it ``readOnly: true`` with ``x-source: "derived"`` — there is no
second place to write the fact, so there is no second definition to
disagree with the first.

Clock inputs (design §3): the engine must be able to compute, per row,
the next moment the fact can change, so the flip is *extractable* — the
honest subset is a comparison of ``now`` (or ``now.date()``) against a
stored date/datetime input, whose flip candidates the engine derives
itself. Where the shape is genuinely richer, ``flips_at=lambda r: ...``
declares the next flip time explicitly — prefer extraction for the
simple ``now > field`` shape; ``flips_at`` is the acknowledged escape
for everything else, and it is still a declaration the maintainer
consumes, never a consumer someone remembers to write.

E4's rollups are re-founded here (design §10): :func:`Count` and
:func:`Sum` are library derivations over ``Owns`` — the documented
spelling. The legacy ``Owns(rollups=...)`` declaration keeps working
(same wire, the envelope's ``rollups`` key) for apps that want the
aggregate outside ``data``; new code declares the fact as a field.
"""
from __future__ import annotations

import typing
from dataclasses import dataclass, field as dc_field
from decimal import Decimal, InvalidOperation
from typing import Any, Callable, Iterable, Mapping

from pydantic import BaseModel, Field
from pydantic.fields import FieldInfo

from .related import RelatedField, forward_filters
from .types import DefinitionError


class Clock:
    """Sentinel input: the engine's ``now``. A derivation over Clock is a
    fact the clock can flip without a write, which is why declaring it
    demands an extractable flip time (or ``flips_at=``) — the §3 clock
    consumer sweeps one index, never polls rows."""

    def __init__(self) -> None:  # pragma: no cover - the class IS the token
        raise TypeError("Clock is a sentinel; use the class itself in over=")


@dataclass(frozen=True)
class Tolerance:
    """THE tolerance, stated once (design §2): ``within=Tolerance("1e-5")``
    makes the derived field ``abs(input) <= tolerance`` — the predicate the
    cash-recon mapping found defined four times at three widths."""

    value: str

    def __post_init__(self) -> None:
        try:
            Decimal(self.value)
        except InvalidOperation:
            raise DefinitionError(
                f"Tolerance({self.value!r}) is not a decimal literal") from None

    @property
    def decimal(self) -> Decimal:
        return Decimal(self.value)


@dataclass(frozen=True)
class ChildField:
    """An owned-children input: one field of every child on an ``Owns``
    edge (optionally filtered), delivered to ``fn`` as a list. Spelled
    ``edge.field("state")`` / ``edge.field("amount", where=...)`` —
    validated at engine assembly (``checks.check_derived_edges``)."""

    kind: str
    via: str
    field: str
    where: Mapping[str, Any] = dc_field(default_factory=dict)


@dataclass(frozen=True)
class DerivedSpec:
    """The declaration behind a derived field, carried on the pydantic
    field and consumed by the schema generator, the import checks, and
    the engine maintainer alike."""

    over: tuple[Any, ...]
    fn: Callable[..., Any] | None
    tolerance: Tolerance | None
    explain: str | None
    vars: Callable[..., Mapping[str, Any]] | None
    flips_at: Callable[[Any], Any] | None

    def apply(self, args: list[Any]) -> Any:
        if self.tolerance is not None:
            x = args[0]
            if x is None:
                return False
            return abs(Decimal(str(x))) <= self.tolerance.decimal
        assert self.fn is not None
        return self.fn(*args)

    @property
    def has_clock(self) -> bool:
        return any(inp is Clock for inp in self.over)

    @property
    def clock_comparand(self) -> str | None:
        """The stored field ``now`` is compared against — the extractable
        shape (design §3). None when ``flips_at`` carries the flip."""
        if not self.has_clock or self.flips_at is not None:
            return None
        others = [i for i in self.over if isinstance(i, str)]
        return others[0] if others else None

    @property
    def child_inputs(self) -> tuple[ChildField, ...]:
        return tuple(i for i in self.over if isinstance(i, ChildField))

    @property
    def related_inputs(self) -> tuple[RelatedField, ...]:
        """Related-rows inputs (design 6.0 §2): ``edge.field(...)`` over a
        declared :class:`~.related.Related` predicate."""
        return tuple(i for i in self.over if isinstance(i, RelatedField))

    @property
    def own_inputs(self) -> tuple[str, ...]:
        return tuple(i for i in self.over if isinstance(i, str))


@dataclass(frozen=True)
class Deferred:
    """Declared deferral of the §4 backfill invariant (design §4): a
    resource whose table is too large to recompute before serving says so
    — ``backfill = Deferred(batch=500, pause=0.0)`` on the class — and
    the boot marks the redefined facts as recomputing instead of holding
    the door. While a fact is catching up it is honestly un-advertised
    from the query surface (dropped from the collection query schema,
    refused by the router) and every envelope of the kind carries
    ``meta.recomputing``; the engine's background task drains the batches
    (``batch`` rows per transaction, ``pause`` seconds between batches).
    Without this declaration the backfill runs inside startup and boot
    does not complete until truth is current."""

    batch: int = 500
    pause: float = 0.0

    def __post_init__(self) -> None:
        if self.batch < 1:
            raise DefinitionError("Deferred(batch=...) must be ≥ 1")
        if self.pause < 0:
            raise DefinitionError("Deferred(pause=...) must be ≥ 0")


def Derived(
    *,
    over: Iterable[Any],
    fn: Callable[..., Any] | None = None,
    within: Tolerance | None = None,
    explain: str | None = None,
    vars: Callable[..., Mapping[str, Any]] | None = None,
    flips_at: Callable[[Any], Any] | None = None,
    default: Any = None,
    **kwargs: Any,
) -> Any:
    """Declare a derived Data field. ``over=`` names the inputs (own
    fields, ``Owns`` child fields, ``Clock``), ``fn=`` is a pure function
    of their resolved values in ``over=`` order; ``within=`` is the
    single-input tolerance shorthand. The default is the engine's to
    overwrite — a derived field is never authored by a caller."""
    inputs = tuple(over)
    if not inputs:
        raise DefinitionError("Derived requires over=(...) naming at least "
                              "one input — a derivation of nothing derives "
                              "nothing")
    if (fn is None) == (within is None):
        raise DefinitionError(
            "Derived: declare exactly one of fn= (a pure function of the "
            "inputs) or within= (a Tolerance over one numeric input)")
    for inp in inputs:
        if inp is Clock or isinstance(inp, (str, ChildField, RelatedField)):
            continue
        raise DefinitionError(
            f"Derived input {inp!r} is not an own field name, an "
            "Owns.field(...), a Related.field(...), or Clock")
    clocks = sum(1 for i in inputs if i is Clock)
    if clocks > 1:
        raise DefinitionError("Derived: Clock may appear at most once in "
                              "over=")
    if within is not None:
        if not isinstance(within, Tolerance):
            raise DefinitionError("within= takes a Tolerance('...')")
        if len(inputs) != 1 or not isinstance(inputs[0], str):
            raise DefinitionError(
                "within=Tolerance(...) derives a bool from exactly one own "
                "numeric input — anything richer is a fn=")
    if clocks:
        others = [i for i in inputs if i is not Clock]
        extractable = (len(others) == 1 and isinstance(others[0], str))
        if flips_at is None and not extractable:
            raise DefinitionError(
                "Derived over Clock must be extractable — a comparison of "
                "now against one stored date/datetime field — or declare "
                "flips_at=lambda r: ... naming the next flip time (design "
                "§3; prefer extraction for the simple `now > field` shape)")
    elif flips_at is not None:
        raise DefinitionError("flips_at= only applies to derivations over "
                              "Clock")
    spec = DerivedSpec(over=inputs, fn=fn, tolerance=within, explain=explain,
                       vars=vars, flips_at=flips_at)
    user_extra = dict(kwargs.pop("json_schema_extra", None) or {})

    # json_schema_extra as a callable: the spec (it holds callables) never
    # tries to reach the wire, and every schema emission — Data, a Create
    # model that inherits the field, anywhere — carries the origin marks
    # (design §1: readOnly + x-source) without a second site to forget
    def mark(schema: dict[str, Any]) -> None:
        schema.update(user_extra)
        schema.pop("default", None)  # an engine-owned value offers none
        schema["readOnly"] = True
        schema["x-source"] = "derived"

    mark.__waymark_derived__ = spec  # type: ignore[attr-defined]
    return Field(default, json_schema_extra=mark, **kwargs)


def Count(edge: Any, *, where: Mapping[str, Any] | None = None,
          **kwargs: Any) -> Any:
    """E4's count rollup as a library derivation (design §10): the number
    of rows on the edge (matching ``where``), maintained as a field. The
    edge is a relation — ``Owns`` or ``Related`` (design 6.0 §2) — and
    the derivation rides whichever ``edge.field()`` produces."""
    return Derived(over=(edge.field("id", where=where),),
                   fn=lambda children: len(children), **kwargs)


def Sum(edge: Any, of: str, *, where: Mapping[str, Any] | None = None,
        **kwargs: Any) -> Any:
    """E4's sum rollup as a library derivation (design §10): Σ of a field
    across the rows on the edge (matching ``where``) — ``Owns`` or
    ``Related`` (design 6.0 §2), like :func:`Count`."""
    return Derived(over=(edge.field(of, where=where),),
                   fn=lambda values: sum(v or 0 for v in values), **kwargs)


async def resolve_inputs(spec: DerivedSpec, r: Any, ctx: Any) -> list[Any]:
    """The spec's declared inputs, resolved for one row through the ctx —
    what the refusal surface needs to garnish ``explain=`` with ``vars=``
    (design §5). Own fields (including already-materialized derivations)
    come off the row; ``Clock`` is the ctx's ``now``; owned-children
    fields come through ``ctx.find`` — the same sanctioned cross-resource
    read guards make. This resolves *garnish inputs*, never the fact: the
    fact itself is the maintained column, judged as stored."""
    args: list[Any] = []
    for inp in spec.over:
        if inp is Clock:
            args.append(ctx.now)
        elif isinstance(inp, ChildField):
            rows = await ctx.find(inp.kind, limit=1000,
                                  **{inp.via: r.id}, **dict(inp.where))
            args.append([_row_field(child, inp.field) for child in rows])
        elif isinstance(inp, RelatedField):
            # the forward read over a declared relation (design 6.0 §2):
            # the anchor's own values, through the predicate, select the
            # target rows — the same sanctioned cross-resource read
            filters = forward_filters(inp.on, r.data)
            rows = [] if filters is None else await ctx.find(
                inp.kind, limit=1000, **filters, **dict(inp.where))
            args.append([_row_field(row, inp.field) for row in rows])
        else:
            args.append(getattr(r.data, inp))
    return args


class _InputView:
    """The create-input view of the anchor's own fields: values already
    computed THIS pass shadow the input's (a derived join key must be
    read at this pass's value — the maintainer's ``_FreshView``
    discipline, applied to inputs)."""

    def __init__(self, fresh: Mapping[str, Any], inp: Any):
        self._fresh = fresh
        self._inp = inp

    def __getattr__(self, name: str) -> Any:
        if name in self._fresh:
            return self._fresh[name]
        return getattr(self._inp, name, None)


async def compute_from_input(model: type[BaseModel], inp: Any,
                             ctx: Any) -> tuple[dict[str, Any],
                                                dict[str, list[Any]]]:
    """Every derived value of ``model``, computed from a validated CREATE
    input (the inputs-and-identities wave, the E9 precedent extended):
    own fields read off the input (a field the create model does not
    carry resolves None, exactly as the row would hold); ``ChildField``
    → an empty list — a new row owns nothing yet, which is true, not a
    fallback; ``RelatedField`` → the forward read with the INPUT's join
    values (identity joins included: a break's create input carries
    ``account_id``, so the parent load works); ``Clock`` → the ctx's
    ``now``. The same ordered specs and the same pure ``apply`` that
    materialization and the conformance replay use — one truth, third
    calling context — which is what makes the value a create guard
    judges equal the value the same create materializes into the row.

    Returns ``(values, args)``: every derived value by field name, and
    the resolved input args per field (the ``vars=`` garnish consumes
    the latter without a second read)."""
    values: dict[str, Any] = {}
    resolved: dict[str, list[Any]] = {}
    for name, spec in ordered_specs(model):
        args: list[Any] = []
        for i in spec.over:
            if i is Clock:
                args.append(ctx.now)
            elif isinstance(i, ChildField):
                args.append([])
            elif isinstance(i, RelatedField):
                filters = forward_filters(i.on, _InputView(values, inp))
                rows: list[Any] = []
                page = 1
                while filters is not None:
                    batch = await ctx.find(i.kind, limit=200, page=page,
                                           **filters, **dict(i.where))
                    rows.extend(batch)
                    if len(batch) < 200:
                        break
                    page += 1
                args.append([_row_field(row, i.field) for row in rows])
            elif i in values:  # a derivation over a derivation
                args.append(values[i])
            else:
                args.append(getattr(inp, i, None))
        resolved[name] = args
        values[name] = spec.apply(args)
    return values, resolved


def _row_field(row: Any, name: str) -> Any:
    """One edge-input value off one related/owned row — ``state`` and
    ``id`` live on the instance, everything else on its Data."""
    if name == "state":
        return row.state
    if name == "id":
        return row.id
    return getattr(row.data, name)


def derived_spec(field_info: FieldInfo) -> DerivedSpec | None:
    spec = getattr(field_info.json_schema_extra, "__waymark_derived__", None)
    return spec if isinstance(spec, DerivedSpec) else None


_SPECS_CACHE: dict[type, dict[str, DerivedSpec]] = {}
_ORDER_CACHE: dict[type, tuple[tuple[str, DerivedSpec], ...]] = {}


def derived_specs(model: type[BaseModel]) -> dict[str, DerivedSpec]:
    """All derived fields of a model, by field name."""
    cached = _SPECS_CACHE.get(model)
    if cached is None:
        cached = {name: spec for name, f in model.model_fields.items()
                  if (spec := derived_spec(f)) is not None}
        _SPECS_CACHE[model] = cached
    return cached


def ordered_specs(model: type[BaseModel]) -> tuple[tuple[str, DerivedSpec], ...]:
    """Derived fields in dependency order (a derivation may read another —
    ``reconciled`` over ``difference``). A cycle is a DefinitionError at
    import: two facts each defined in terms of the other define nothing."""
    cached = _ORDER_CACHE.get(model)
    if cached is not None:
        return cached
    specs = derived_specs(model)
    ordered: list[tuple[str, DerivedSpec]] = []
    done: set[str] = set()
    walking: set[str] = set()

    def visit(name: str) -> None:
        if name in done:
            return
        if name in walking:
            raise DefinitionError(
                f"{model.__name__}: derived fields form a cycle through "
                f"{name!r} — a fact defined in terms of itself is a design "
                "error")
        walking.add(name)
        for dep in specs[name].own_inputs:
            if dep in specs:
                visit(dep)
        # a Related input's join keys (the `ours` side of its predicate)
        # are dependencies too: a fact over the relation must compute
        # after the derived boundary that defines the relation's window
        # (design §2 — the forward read must see this pass's values)
        for inp in specs[name].over:
            for cond in getattr(inp, "on", ()) or ():
                if cond.ours in specs:
                    visit(cond.ours)
        walking.discard(name)
        done.add(name)
        ordered.append((name, specs[name]))

    for name in specs:
        visit(name)
    result = tuple(ordered)
    _ORDER_CACHE[model] = result
    return result


def has_clock_derived(cls: type) -> bool:
    """Does this resource declare any Clock-input derivation? Consumed by
    storage (the ``next_flip_at`` maintained column rides only the kinds
    that need it) and by the engine (the sweep task starts only then)."""
    data = getattr(cls, "Data", None)
    if data is None:
        return False
    return any(spec.has_clock for spec in derived_specs(data).values())


def _bare_type(ann: Any) -> Any:
    """Unwrap ``X | None`` and ``Annotated[X, ...]`` (pydantic's
    ``AwareDatetime`` is the latter) down to the bare type."""
    import types as _types

    while True:
        origin = typing.get_origin(ann)
        if origin in (typing.Union, _types.UnionType):
            rest = [a for a in typing.get_args(ann) if a is not type(None)]
            if len(rest) != 1:
                return ann
            ann = rest[0]
        elif origin is typing.Annotated:
            ann = typing.get_args(ann)[0]
        else:
            return ann


def comparand_is_temporal(model: type[BaseModel], name: str) -> bool:
    """Is the clock comparand a stored date/datetime? The extractable shape
    (design §3) — checked at import so a fact the sweep cannot schedule is
    unrepresentable rather than silently stale. Judged by the field's wire
    format (``date``/``date-time``), which covers plain ``datetime``/
    ``date`` annotations and pydantic's marker types (``AwareDatetime``)
    alike — the wire never lies about what is stored."""
    import datetime as _dt

    f = model.model_fields.get(name)
    if f is None:
        return False
    ann = _bare_type(f.annotation)
    if isinstance(ann, type) and issubclass(ann, (_dt.datetime, _dt.date)):
        return True
    prop = (model.model_json_schema(mode="serialization")
            .get("properties") or {}).get(name) or {}
    formats = {prop.get("format")} | {
        (branch or {}).get("format") for branch in prop.get("anyOf", ())}
    return bool(formats & {"date", "date-time"})
