"""Import-time checks: fail fast, before the app serves a request.

2.0 (design §1, §3, §10): most of v1's usability *warnings* are now either
unrepresentable (their invariants moved into ``Safety``/``Edit``/``Guard``
constructors) or **errors** (the closure rule). What remains as warnings are
genuine heuristics — the waiver list is down to two tokens.

Every violation raises :class:`DefinitionError` naming the resource, the
action, and the rule. These run from ``Resource.__init_subclass__``, so a
broken definition module cannot even be imported.
"""
from __future__ import annotations

import inspect
import re
import string
import typing
import warnings
from typing import Any

from pydantic import BaseModel

from .actions import ActionDef
from .machine import StateMachine
from .refs import model_refs, ref_display
from .types import DefinitionError

_SNAKE = re.compile(r"^[a-z][a-z0-9_]*$")

WAIVABLE = frozenset({"altitude", "large_effort"})

LONG_TEXT_BUDGET = 280  # two summary budgets: beyond this, it's prose


class UsabilityWarning(UserWarning):
    """A definition is enforceable but likely to render a poor human surface."""


def run_all(cls: type, machine: StateMachine, *, allow_dead: frozenset[str],
            summary_template: str) -> None:
    check_tokens(cls, machine)
    check_reachability(cls, machine, allow_dead)
    check_terminal_no_exit(cls, machine)
    check_reversible(cls, machine)
    check_one_way(cls, machine)
    check_guard_declarations(cls, machine)
    check_guard_templates(cls, machine)
    check_closure(cls, machine)
    check_handler_signatures(cls, machine)
    check_summary_template(cls, summary_template)
    check_waive_tokens(cls, machine)
    check_place(cls, machine)
    check_edit(cls, machine)
    check_altitude(cls, machine)
    check_long_text(cls, machine)
    check_faceted(cls)
    check_oneof(cls)


def check_oneof(cls: type) -> None:
    """Every OneOf group (on the data root or a list-item model) must name
    real fields — a typo'd arm would silently enforce nothing."""
    from .groups import _item_models, groups_of

    data_cls = getattr(cls, "Data", None)
    if data_cls is None:
        return
    for group in groups_of(data_cls).values():
        group.validate_against(data_cls)
    for item_cls in _item_models(data_cls).values():
        for group in groups_of(item_cls).values():
            group.validate_against(item_cls)


def check_faceted(cls: type) -> None:
    """``faceted`` fields must be Eq/In-filterable: a facet is a filter
    value with a count, so a field the query cannot filter on has nothing
    to facet."""
    from .resource import FilterOp

    faceted = tuple(getattr(cls, "faceted", ()) or ())
    if not faceted:
        return
    fspec = getattr(cls, "filterable", None)
    fields = fspec.fields if fspec is not None else {}
    for f in faceted:
        if f == "state":
            continue  # state facets are automatic; the declaration is moot
        ops = fields.get(f)
        if ops is None or not ops & (FilterOp.EQ | FilterOp.IN):
            raise _err(cls, f"faceted field {f!r} is not Eq/In-filterable; "
                            "declare it in filterable(...) first")


def _err(cls: type, msg: str) -> DefinitionError:
    return DefinitionError(f"{cls.__module__}.{cls.__qualname__}: {msg}")


def check_tokens(cls: type, machine: StateMachine) -> None:
    kind = getattr(cls, "kind", None)
    if not kind or not _SNAKE.match(kind):
        raise _err(cls, f"kind must be a snake_case token, got {kind!r}")
    for s in machine.states:
        if not _SNAKE.match(s):
            raise _err(cls, f"state token {s!r} is not snake_case")
    if machine.initial not in machine.states:
        raise _err(cls, f"initial state {machine.initial!r} is not a declared state")
    unknown = machine.terminal - set(machine.states)
    if unknown:
        raise _err(cls, f"terminal states {sorted(unknown)} are not declared states")
    for name, defn in machine.actions.items():
        bad = (defn.from_ | {defn.to}) - set(machine.states)
        if bad:
            raise _err(cls, f"action {name!r} references undeclared states {sorted(bad)}")


def check_reachability(cls: type, machine: StateMachine,
                       allow_dead: frozenset[str]) -> None:
    reachable = machine.reachable_states()
    for s in machine.states:
        if s == machine.initial or s in allow_dead:
            continue
        if s not in reachable:
            raise _err(cls, f"state {s!r} is unreachable from {machine.initial!r} "
                            "(annotate allow_dead if intentional)")
        if s not in machine.terminal and not machine.transitions_from(s):
            raise _err(cls, f"state {s!r} is a dead end: non-terminal but has no "
                            "outgoing transitions (annotate allow_dead if intentional)")


def check_terminal_no_exit(cls: type, machine: StateMachine) -> None:
    for name, defn in machine.actions.items():
        dead = defn.from_ & machine.terminal
        if dead:
            raise _err(cls, f"action {name!r} exits terminal state(s) {sorted(dead)}")


def check_reversible(cls: type, machine: StateMachine) -> None:
    """``reversible=True`` is verified against the graph, not trusted.

    A reverse transition ``to → src`` must exist for *every* source state,
    and its guards must be unconditional-or-time-based — approximated as:
    not hidden and not permission-token-gated.
    """
    for name, defn in machine.actions.items():
        if not defn.safety.reversible:
            continue
        for src, reverses in machine.reverse_edges(defn).items():
            usable = [
                r for r in reverses
                if not any(g.hide or (g.requires_token or "").startswith("role:")
                           for g in r.guards)
            ]
            if not usable:
                raise _err(cls, f"action {name!r} declares reversible=True but no "
                                f"unconditional transition {defn.to!r} → {src!r} exists")


def check_one_way(cls: type, machine: StateMachine) -> None:
    """A button that cannot be undone and doesn't pause is the #1 trust
    destroyer (v1's planned ``one_way_door``). An irreversible, unconfirmed,
    state-leaving action must carry ``Safety(one_way=Acknowledged('…'))``.
    Self-loops are exempt: re-doing is its own undo."""
    for name, defn in machine.actions.items():
        s = defn.safety
        if s.reversible or s.confirm or s.one_way is not None:
            continue
        leaves = any(src != defn.to for src in defn.from_)
        if leaves:
            raise _err(cls, f"action {name!r} is irreversible, unconfirmed, and "
                            f"leaves the current state — a silent one-way door. "
                            "Declare confirm (with its consequence), make it "
                            "honestly reversible, or acknowledge with "
                            "Safety(one_way=Acknowledged('why this is low-stakes'))")


def check_guard_declarations(cls: type, machine: StateMachine) -> None:
    """Judged fields must exist on the action's input model — a typo'd
    ``judges`` entry would silently advertise and enforce nothing."""
    for name, defn in machine.actions.items():
        for g in _leaf_guards(defn):
            if not g.judges:
                continue
            if defn.input is None:
                raise _err(cls, f"guard {g.name!r} on action {name!r} judges "
                                f"{sorted(g.judges)} but the action takes no input")
            missing = [f for f in g.judges if f not in defn.input.model_fields]
            if missing:
                raise _err(cls, f"guard {g.name!r} on action {name!r} judges "
                                f"{missing}, not field(s) of {defn.input.__name__}")


def check_guard_templates(cls: type, machine: StateMachine) -> None:
    """Static template check, now total: a ``vars_fn`` must declare its
    names via ``vars=`` (design §8), so there is no empirical escape."""
    for name, defn in machine.actions.items():
        for g in _leaf_guards(defn):
            placeholders = {
                field.partition(".")[0].partition("[")[0]
                for _, field, _, _ in string.Formatter().parse(g.explain)
                if field
            }
            known = set(g.declared_vars) | set(g.judges)
            unknown = placeholders - known
            if unknown:
                raise _err(cls, f"guard {g.name!r} on action {name!r}: explain "
                                f"template references {sorted(unknown)}, which "
                                "neither vars=… nor its judged fields supply")


def check_closure(cls: type, machine: StateMachine) -> None:
    """The closure rule (design §1) — v1's schema-guard gap, made a
    definition-time error.

    A guard whose ``judges`` names an input field must give clients
    something to go on for that field: an ``accepts`` set, a declared
    ``relates`` relation, a schema constraint (enum / const / boolean /
    format / bounds / widget), or an explicit ``open=Acknowledged('…')``.
    A blank the server will grade post-hoc is unrepresentable.
    """
    from . import schemas as schemagen

    for name, defn in machine.actions.items():
        for g in _leaf_guards(defn):
            if not g.judges or g.open is not None:
                continue
            if defn.input is None:
                continue  # check_guard_declarations already raised
            schema = schemagen.input_schema(defn.input, dict(defn.field_display))[0]
            covered = set()
            if g.accepts is not None:
                # a Relation's acceptance set closes every field it judges
                covered.update(g.judges if g.is_relation else (g.judges[0],))
            if g.is_relation and g.op is not None:
                # a comparison closes both fields it relates
                covered.update(g.judges)
            for f in g.judges:
                if f in covered:
                    continue
                prop = _resolve_property(schema, f)
                if prop is not None and _is_constrained(prop):
                    continue
                raise _err(
                    cls,
                    f"guard {g.name!r} on action {name!r} judges {f!r}, but "
                    f"nothing tells the client what {f!r} wants: no accepts=, "
                    "no relates=, and no schema constraint. Declare the "
                    "acceptance set, constrain the schema, or acknowledge "
                    "the open judgment with open=Acknowledged('…')")


def _is_constrained(prop: dict[str, Any]) -> bool:
    if any(k in prop for k in ("enum", "const", "format", "minimum", "maximum",
                               "exclusiveMinimum", "exclusiveMaximum",
                               "minLength", "maxLength", "pattern")):
        return True
    if prop.get("type") == "boolean":
        return True
    if (prop.get("x-display") or {}).get("widget"):
        return True
    for branch in prop.get("anyOf", ()) or prop.get("oneOf", ()):
        if isinstance(branch, dict) and branch.get("type") != "null" \
                and _is_constrained(branch):
            return True
    return False


def check_handler_signatures(cls: type, machine: StateMachine) -> None:
    for name, defn in machine.actions.items():
        fn = defn.handler
        if defn.bulk is False and not inspect.iscoroutinefunction(fn):
            raise _err(cls, f"action handler {name!r} must be `async def`")
        params = list(inspect.signature(fn).parameters.values())
        if len(params) != 3:
            raise _err(cls, f"action handler {name!r} must have signature "
                            "(self, inp, ctx)")
        try:
            hints = typing.get_type_hints(fn, vars(inspect.getmodule(fn)) if
                                          inspect.getmodule(fn) else None)
        except NameError as e:
            raise _err(cls, f"action handler {name!r}: unresolvable annotation "
                            f"({e}) — define input models before the resource class")
        inp_ann: Any = hints.get(params[1].name, None)
        if defn.input is None:
            if inp_ann not in (None, type(None), Any):
                raise _err(cls, f"action {name!r} declares no input but handler "
                                f"annotates inp as {inp_ann!r}")
        elif inp_ann is not None and inp_ann is not defn.input:
            raise _err(cls, f"action {name!r}: handler annotates inp as {inp_ann!r} "
                            f"but the action declares input={defn.input.__name__}")


def check_summary_template(cls: type, template: str) -> None:
    from .summary import template_fields

    if not template:
        raise _err(cls, "summary template is required")
    allowed = {"id", "state", "data", "kind", "version"}
    unknown = set(template_fields(template)) - allowed
    if unknown:
        raise _err(cls, f"summary template references unknown roots {sorted(unknown)}; "
                        f"allowed: {sorted(allowed)}")


def check_waive_tokens(cls: type, machine: StateMachine) -> None:
    for name, defn in machine.actions.items():
        unknown = defn.waives - WAIVABLE
        if unknown:
            raise _err(cls, f"action {name!r} waives unknown usability checks "
                            f"{sorted(unknown)}; waivable: {sorted(WAIVABLE)}")


def check_place(cls: type, machine: StateMachine) -> None:
    """``place=PartScope(array, key)`` must name a list-of-model Data field
    whose item model and the action's input model both carry the key."""
    for name, defn in machine.actions.items():
        if defn.place is None:
            continue
        array, key = defn.place.array, defn.place.key
        if defn.input is None:
            raise _err(cls, f"action {name!r} declares place={defn.place!r} but "
                            "takes no input to bind the key into")
        item = _item_model(cls, array)
        if item is None:
            raise _err(cls, f"action {name!r} place names data.{array}, which "
                            "is not a list-of-model Data field")
        if key not in item.model_fields:
            raise _err(cls, f"action {name!r} place key {key!r} is not a field "
                            f"of data.{array} items ({item.__name__})")
        if key not in defn.input.model_fields:
            raise _err(cls, f"action {name!r} place key {key!r} is not a field "
                            f"of its input model {defn.input.__name__}")


def check_edit(cls: type, machine: StateMachine) -> None:
    """Edit declarations validated hard; edit-*shaped* actions that never
    declared Edit get the heuristic warning (the shape is detectable, the
    intent is not — that's the one honest warning left in this family)."""
    for name, defn in machine.actions.items():
        if defn.edit is not None:
            if defn.draft and defn.input is None:
                raise _err(cls, f"action {name!r} declares a draft but takes "
                                "no input — there is nothing to draft")
            for f in defn.edit.prefill:
                if defn.input is None or f not in defn.input.model_fields:
                    raise _err(cls, f"action {name!r} prefills {f!r}, not a "
                                    "field of its input model")
                if f not in cls.Data.model_fields:
                    raise _err(cls, f"action {name!r} prefills {f!r}, but Data "
                                    "has no such field to prefill from")
        if defn.input is None or defn.bulk:
            continue
        if defn.edit is None:
            mirrored = [
                f for f, ff in defn.input.model_fields.items()
                if (df := cls.Data.model_fields.get(f)) is not None
                and _sans_none(df.annotation) == _sans_none(ff.annotation)]
            if mirrored:
                warnings.warn(
                    f"{cls.__module__}.{cls.__qualname__}: action {name!r} is "
                    f"edit-shaped — input field(s) {mirrored} mirror Data "
                    f"fields the document already holds, but no edit=Edit(…) "
                    f"is declared. The form renders blank and unfenced. "
                    f"Declare edit=Edit(prefill=({', '.join(repr(f) for f in mirrored)},)).",
                    UsabilityWarning, stacklevel=3)
        # the knowledge floor (design §10): composition demands a draft
        required = set(defn.input.model_json_schema().get("required", []))
        prose_required = [
            f for f, ff in defn.input.model_fields.items()
            if f in required
            and isinstance(ff.json_schema_extra, dict)
            and (ff.json_schema_extra.get("x-display") or {}).get("widget")
            == "prose"]
        if prose_required and not defn.draft \
                and "large_effort" not in defn.waives:
            warnings.warn(
                f"{cls.__module__}.{cls.__qualname__}: action {name!r} "
                f"demands composition ({prose_required}) with no draft — a "
                f"mis-click discards everything the user typed. Declare "
                f"edit=Edit(draft=DraftPolicy()) or acknowledge with "
                f"waives=('large_effort',).",
                UsabilityWarning, stacklevel=3)


def check_altitude(cls: type, machine: StateMachine) -> None:
    """Wrong-altitude heuristic: an input field that keys the items of a
    ``data`` array re-asks the user to identify the item they are already
    looking at. The fix is ``place=``; the signature of identification is a
    document-derivable guard judging the field."""
    arrays = [(dname, item) for dname in cls.Data.model_fields
              if (item := _item_model(cls, dname)) is not None]
    if not arrays:
        return
    for name, defn in machine.actions.items():
        if defn.input is None or defn.bulk or "altitude" in defn.waives:
            continue
        judged = {f for g in _leaf_guards(defn)
                  if not g.reads_ctx
                  for f in g.judges}
        for dname, item in arrays:
            if defn.place is not None and defn.place.array == dname:
                continue  # already placed on this array: the fix is applied
            matched = [fname for fname, ffield in defn.input.model_fields.items()
                       if fname in judged
                       and (target := item.model_fields.get(fname)) is not None
                       and target.annotation == ffield.annotation]
            if matched:
                warnings.warn(
                    f"{cls.__module__}.{cls.__qualname__}: action {name!r} "
                    f"input field(s) {matched} mirror the items of "
                    f"data.{dname} — the form re-asks the user to identify "
                    f"an item they are already looking at. Declare a "
                    f"PartScope({dname!r}, key=…) on the resource and place "
                    f"the action on it, or acknowledge with "
                    f"waives=('altitude',).",
                    UsabilityWarning, stacklevel=3)


def check_long_text(cls: type, machine: StateMachine) -> None:
    """Long-form text is a document, not a datum. A string field with no
    length budget (or ≥ 280 chars) must say what it is: ``x-display
    {widget: 'prose'}``, a real ``max_length``, or ``hidden``/``raw``.
    Length budgets are a wire contract, not a style preference."""
    import types as _types

    models: list[tuple[str, type[BaseModel]]] = [("data", cls.Data)]
    for dname in cls.Data.model_fields:
        item = _item_model(cls, dname)
        if item is not None:
            models.append((f"data.{dname}[]", item))
    seen_inputs: set[type] = set()
    for name, defn in machine.actions.items():
        if defn.input is not None and defn.input not in seen_inputs:
            seen_inputs.add(defn.input)
            models.append((f"input {defn.input.__name__}", defn.input))

    for where, model in models:
        for fname, f in model.model_fields.items():
            ann = f.annotation
            is_text = ann is str or (
                typing.get_origin(ann) in (typing.Union, _types.UnionType)
                and str in typing.get_args(ann))
            if not is_text:
                continue
            if ref_display(f) is not None:
                continue  # Ref fields render as references, never as prose
            max_len = next((m.max_length for m in f.metadata
                            if getattr(m, "max_length", None) is not None),
                           None)
            if max_len is not None and max_len < LONG_TEXT_BUDGET:
                continue
            extra = f.json_schema_extra \
                if isinstance(f.json_schema_extra, dict) else {}
            xd = extra.get("x-display") or {}
            if xd.get("widget") or xd.get("hidden") or xd.get("raw"):
                continue
            budget = f"max_length={max_len}" if max_len is not None \
                else "no max_length"
            warnings.warn(
                f"{cls.__module__}.{cls.__qualname__}: {where}.{fname} is a "
                f"text field with {budget} — long-form text breaks any "
                f"table a generic client derives. Declare x-display "
                f"{{'widget': 'prose'}} for long-form content, set "
                f"max_length under {LONG_TEXT_BUDGET} if it's genuinely "
                f"short, or mark {{'hidden': True}} / {{'raw': True}}.",
                UsabilityWarning, stacklevel=3)


def check_refs(registry: Any) -> None:
    """Assembly-time reference checks (design §2).

    Errors: a ``Ref[kind]`` naming an unregistered kind is a broken
    declaration. Warnings: a ``{kind}_id``-named field for a registered kind
    without a ``Ref`` type is the v1 heuristic demoted to a lint — it tells
    you to use the declaration that retires it.
    """
    kinds = set(registry.kinds())
    for rdef in registry.defs():
        models: list[tuple[str, type[BaseModel]]] = [("data", rdef.cls.Data)]
        for dname in rdef.cls.Data.model_fields:
            item = _item_model(rdef.cls, dname)
            if item is not None:
                models.append((f"data.{dname}[]", item))
        for name, defn in rdef.machine.actions.items():
            if defn.input is not None:
                models.append((f"input {defn.input.__name__}", defn.input))
        for where, model in models:
            refs = model_refs(model)
            for fname, meta in refs.items():
                if meta.kind not in kinds:
                    raise DefinitionError(
                        f"{rdef.cls.__module__}.{rdef.cls.__qualname__}: "
                        f"{where}.{fname} is Ref[{meta.kind!r}], but no such "
                        "kind is registered on this engine")
            for fname, f in model.model_fields.items():
                if fname in refs:
                    continue
                target = fname.removesuffix("_id")
                if target == fname or target not in kinds:
                    continue
                extra = f.json_schema_extra \
                    if isinstance(f.json_schema_extra, dict) else {}
                xd = extra.get("x-display") or {}
                if xd.get("widget") or xd.get("hidden") or xd.get("raw"):
                    continue
                warnings.warn(
                    f"{rdef.cls.__module__}.{rdef.cls.__qualname__}: "
                    f"{where}.{fname} references a {target!r} by naming "
                    f"convention but is not Ref[{target!r}] — type it as a "
                    f"Ref (with RefField(label=…, pick=…, raw=…) options) so "
                    "the picker, the navigable reference, and the "
                    "dangling-ref check all come from one declaration.",
                    UsabilityWarning, stacklevel=2)


def _item_model(cls: type, array: str) -> type[BaseModel] | None:
    dfield = cls.Data.model_fields.get(array)
    if dfield is None or typing.get_origin(dfield.annotation) is not list:
        return None
    args = typing.get_args(dfield.annotation)
    if args and isinstance(args[0], type) and issubclass(args[0], BaseModel):
        return args[0]
    return None


def _sans_none(ann: Any) -> Any:
    """``str | None`` → ``str`` so edit-shape comparison ignores optionality."""
    import types as _types

    if typing.get_origin(ann) in (typing.Union, _types.UnionType):
        rest = [a for a in typing.get_args(ann) if a is not type(None)]
        if len(rest) == 1:
            return rest[0]
    return ann


def _leaf_guards(defn: ActionDef):
    for g in defn.guards:
        yield from g.iter_leaves()


def _resolve_property(schema: dict[str, Any], field: str) -> dict[str, Any] | None:
    """Follow one level of local $ref / single-element allOf to the field's
    effective schema (pydantic wraps enums this way)."""
    prop = (schema.get("properties") or {}).get(field)
    if prop is None:
        return None
    if len(prop.get("allOf", ())) == 1:
        prop = {**prop, **prop["allOf"][0]}
    ref = prop.get("$ref", "")
    if ref.startswith("#/$defs/"):
        target = (schema.get("$defs") or {}).get(ref.rsplit("/", 1)[-1], {})
        prop = {**target, **{k: v for k, v in prop.items() if k != "$ref"}}
    return prop
