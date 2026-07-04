"""Import-time checks (§10.1): fail fast, before the app serves a request.

Every violation raises DefinitionError naming the resource, the action, and
the rule. These run from ``Resource.__init_subclass__``, so a broken
definition module cannot even be imported.

Usability checks emit :class:`UsabilityWarning` instead of raising: they are
heuristics about *human* experience (a form offering choices its own guards
will refuse, an action asking the user to re-specify context the screen
already shows). A finding is either fixed (``admits=…``, a tighter schema, a
scoped placement) or explicitly acknowledged with ``@action(waives=(…,))`` —
never silently ignored.
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

_SNAKE = re.compile(r"^[a-z][a-z0-9_]*$")

WAIVABLE = frozenset({"open_input", "altitude", "blank_edit", "unfenced_edit",
                      "large_effort"})


class DefinitionError(TypeError):
    pass


class UsabilityWarning(UserWarning):
    """A definition is enforceable but likely to render a poor human surface."""


def run_all(cls: type, machine: StateMachine, *, allow_dead: frozenset[str],
            summary_template: str) -> None:
    check_tokens(cls, machine)
    check_reachability(cls, machine, allow_dead)
    check_terminal_no_exit(cls, machine)
    check_reversible(cls, machine)
    check_guard_templates(cls, machine)
    check_handler_signatures(cls, machine)
    check_summary_template(cls, summary_template)
    check_waive_tokens(cls, machine)
    check_admits_fields(cls, machine)
    check_scope(cls, machine)
    check_prefill_and_draft(cls, machine)
    check_open_input(cls, machine)
    check_altitude(cls, machine)
    check_edit_shape(cls, machine)
    check_long_text(cls, machine)


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
    """``reversible=True`` is verified against the graph, not trusted (§10.1).

    A reverse transition ``to → src`` must exist for *every* source state, and
    its guards must be unconditional-or-time-based — approximated in v0.1 as:
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


def check_guard_templates(cls: type, machine: StateMachine) -> None:
    for name, defn in machine.actions.items():
        for g in defn.guards:
            placeholders = {
                field.partition(".")[0].partition("[")[0]
                for _, field, _, _ in string.Formatter().parse(g.else_)
                if field
            }
            unknown = placeholders - set(g.declared_vars)
            if unknown:
                raise _err(cls, f"guard {g.name!r} on action {name!r}: else_ template "
                                f"references {sorted(unknown)} which its Deny(vars=…) "
                                "cannot supply (pass vars=[…] to @guard to declare them)")


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


def check_admits_fields(cls: type, machine: StateMachine) -> None:
    """``admits=(field, fn)`` must name a field of every input model the
    guard is attached to — a typo here would silently tighten nothing."""
    for name, defn in machine.actions.items():
        for g in _leaf_guards(defn):
            if g.admits is None:
                continue
            field, _ = g.admits
            if defn.input is None:
                raise _err(cls, f"guard {g.name!r} on action {name!r} declares "
                                f"admits={field!r} but the action takes no input")
            if field not in defn.input.model_fields:
                raise _err(cls, f"guard {g.name!r} on action {name!r} declares "
                                f"admits={field!r}, not a field of "
                                f"{defn.input.__name__}")


def check_scope(cls: type, machine: StateMachine) -> None:
    """``scope=(array, key)`` must name a list-of-model Data field whose item
    model and the action's input model both carry the key field."""
    for name, defn in machine.actions.items():
        if defn.scope is None:
            continue
        array, key = defn.scope
        if defn.input is None:
            raise _err(cls, f"action {name!r} declares scope={defn.scope} but "
                            "takes no input to bind the key into")
        dfield = cls.Data.model_fields.get(array)
        item = None
        if dfield is not None and typing.get_origin(dfield.annotation) is list:
            args = typing.get_args(dfield.annotation)
            if args and isinstance(args[0], type) and issubclass(args[0], BaseModel):
                item = args[0]
        if item is None:
            raise _err(cls, f"action {name!r} scope names data.{array}, which "
                            "is not a list-of-model Data field")
        if key not in item.model_fields:
            raise _err(cls, f"action {name!r} scope key {key!r} is not a field "
                            f"of data.{array} items ({item.__name__})")
        if key not in defn.input.model_fields:
            raise _err(cls, f"action {name!r} scope key {key!r} is not a field "
                            f"of its input model {defn.input.__name__}")


def check_open_input(cls: type, machine: StateMachine) -> None:
    """The schema-guard gap, statically (usability: error prevention).

    A guard judging exactly one input field means that field has a semantic
    acceptance set the server can evaluate at render time — from the document
    alone (ctx-free guard) or by reading other resources/services (the render
    ctx carries a reader). If neither the guard (``admits=…``) nor the schema
    (enum/const) nor a picker widget tells the client what the field wants,
    the rendered form is a blank the guard will grade post-hoc.
    """
    from . import schemas as schemagen

    for name, defn in machine.actions.items():
        if defn.input is None or "open_input" in defn.waives:
            continue
        # the registry-grade schema: field_display merged, labels ensured —
        # a widget granted via field_display counts as guidance
        schema = schemagen.input_schema(defn.input, dict(defn.field_display))[0]
        for g in _leaf_guards(defn):
            if g.admits is not None or not g.input_fields \
                    or len(g.input_fields) != 1:
                continue
            (field,) = g.input_fields
            prop = _resolve_property(schema, field)
            if prop is None or "enum" in prop or "const" in prop \
                    or (prop.get("x-display") or {}).get("widget"):
                continue
            fix = (f"Declare admits=({field!r}, fn(r)) on the guard"
                   if not g.reads_ctx else
                   f"Declare admits=({field!r}, fn(r, ctx)) on the guard "
                   "(async is fine; render evaluates it server-side)")
            warnings.warn(
                f"{cls.__module__}.{cls.__qualname__}: guard {g.name!r} on "
                f"action {name!r} judges {field!r}, but the input schema "
                f"leaves {field!r} an unbounded blank — nothing tells the "
                f"client what the field wants before the guard grades it. "
                f"{fix}, give the field an enum or picker widget, or "
                f"acknowledge with waives=('open_input',).",
                UsabilityWarning, stacklevel=3)


def check_altitude(cls: type, machine: StateMachine) -> None:
    """Wrong-altitude heuristic (usability: don't ask what the screen shows).

    An input field that keys the items of a ``data`` array means the user
    picks "which item" in a form after already looking at that item on
    screen. The affordance likely belongs on the item, with the key
    pre-bound, not on the resource with the key re-asked.

    The signature of identification (vs. creation — ``add_item`` also mirrors
    item fields) is a document-derivable guard judging the field: a pure
    lookup against the document is exactly what "which one?" compiles to.
    """
    arrays: list[tuple[str, type[BaseModel]]] = []
    for dname, dfield in cls.Data.model_fields.items():
        ann = dfield.annotation
        if typing.get_origin(ann) is list:
            (item,) = typing.get_args(ann) or (None,)
            if isinstance(item, type) and issubclass(item, BaseModel):
                arrays.append((dname, item))
    if not arrays:
        return
    for name, defn in machine.actions.items():
        if defn.input is None or defn.bulk or "altitude" in defn.waives:
            continue
        judged = {f for g in _leaf_guards(defn)
                  if not g.reads_ctx and g.input_fields
                  for f in g.input_fields}
        for dname, item in arrays:
            if defn.scope is not None and defn.scope[0] == dname:
                continue  # already scoped to this array: the fix is applied
            matched = [fname for fname, ffield in defn.input.model_fields.items()
                       if fname in judged
                       and (target := item.model_fields.get(fname)) is not None
                       and target.annotation == ffield.annotation]
            if matched:
                warnings.warn(
                    f"{cls.__module__}.{cls.__qualname__}: action {name!r} "
                    f"input field(s) {matched} mirror the items of "
                    f"data.{dname} — the form re-asks the user to identify "
                    f"an item they are already looking at on screen. "
                    f"Consider scoping the action to the item (pre-bind the "
                    f"key), or acknowledge with waives=('altitude',).",
                    UsabilityWarning, stacklevel=3)


def check_prefill_and_draft(cls: type, machine: StateMachine) -> None:
    """``prefill`` and ``draft`` declarations are validated hard: a typo'd
    prefill field or a draft on an input-less action would silently do
    nothing."""
    for name, defn in machine.actions.items():
        if defn.draft and defn.input is None:
            raise _err(cls, f"action {name!r} declares draft=True but takes "
                            "no input — there is nothing to draft")
        if defn.draft and defn.bulk:
            raise _err(cls, f"action {name!r} declares draft=True on a bulk "
                            "action; drafts are per-resource")
        for f in defn.prefill:
            if defn.input is None or f not in defn.input.model_fields:
                raise _err(cls, f"action {name!r} prefills {f!r}, not a "
                                "field of its input model")
            if f not in cls.Data.model_fields:
                raise _err(cls, f"action {name!r} prefills {f!r}, but Data "
                                "has no such field to prefill from")


def _sans_none(ann: Any) -> Any:
    """``str | None`` → ``str`` so edit-shape comparison ignores optionality."""
    import types as _types

    if typing.get_origin(ann) in (typing.Union, _types.UnionType):
        rest = [a for a in typing.get_args(ann) if a is not type(None)]
        if len(rest) == 1:
            return rest[0]
    return ann


def check_edit_shape(cls: type, machine: StateMachine) -> None:
    """Editing is not re-authoring (usability: recognition over recall).

    An action whose input fields mirror top-level Data fields is edit-shaped:
    the server knows the current values, and a blank form both forces recall
    and invites destructive blank-overwrites. Declare ``prefill=(…)`` so the
    rendered schema carries the current values as ``default``s.

    Corollary (``unfenced_edit``): a prefilled form is a snapshot of version
    N; without ``requires_if_match=True`` two editors silently clobber each
    other — §7.2's 412-with-fresh-document exists exactly for this.
    """
    for name, defn in machine.actions.items():
        if defn.input is None or defn.bulk:
            continue
        mirrored = [
            f for f, ff in defn.input.model_fields.items()
            if f not in defn.prefill
            and (df := cls.Data.model_fields.get(f)) is not None
            and _sans_none(df.annotation) == _sans_none(ff.annotation)]
        if mirrored and "blank_edit" not in defn.waives:
            warnings.warn(
                f"{cls.__module__}.{cls.__qualname__}: action {name!r} is "
                f"edit-shaped — input field(s) {mirrored} mirror Data fields "
                f"the document already holds, but the form renders blank. "
                f"The user must re-enter (or lose) current values. Declare "
                f"prefill=({', '.join(repr(f) for f in mirrored)},) or "
                f"acknowledge with waives=('blank_edit',).",
                UsabilityWarning, stacklevel=3)
        if defn.prefill and not defn.safety.requires_if_match \
                and "unfenced_edit" not in defn.waives:
            warnings.warn(
                f"{cls.__module__}.{cls.__qualname__}: action {name!r} "
                f"prefills from the document but does not declare "
                f"requires_if_match=True — a prefilled form is a snapshot, "
                f"and without the etag fence two editors silently clobber "
                f"each other. Declare requires_if_match=True or acknowledge "
                f"with waives=('unfenced_edit',).",
                UsabilityWarning, stacklevel=3)
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
                f"requires long-form input ({prose_required}) but declares "
                f"no draft — a mis-click discards everything the user typed. "
                f"Declare draft=True (the server persists partial input at "
                f"{{self}}/-/{name}/draft) or acknowledge with "
                f"waives=('large_effort',).",
                UsabilityWarning, stacklevel=3)


LONG_TEXT_BUDGET = 280  # two summary budgets: beyond this, it's prose


def check_long_text(cls: type, machine: StateMachine) -> None:
    """Long-form text is a document, not a datum (usability: layout honesty).

    A Data string field with no declared length budget, or one that admits
    more than ~two summaries' worth (≥ 280 chars), will wreck any tabular
    rendering a generic client mechanically derives. Say what it is: give it
    ``x-display {widget: "prose"}`` (clients render a text block and keep it
    out of tables), a real ``max_length`` budget if it's actually short, or
    ``hidden``/``raw`` as elsewhere.
    """
    models: list[tuple[str, type[BaseModel]]] = [("data", cls.Data)]
    for dname, dfield in cls.Data.model_fields.items():
        if typing.get_origin(dfield.annotation) is list:
            args = typing.get_args(dfield.annotation)
            if args and isinstance(args[0], type) \
                    and issubclass(args[0], BaseModel):
                models.append((f"data.{dname}[]", args[0]))
    seen_inputs: set[type] = set()
    for name, defn in machine.actions.items():
        if defn.input is not None and defn.input not in seen_inputs:
            seen_inputs.add(defn.input)
            models.append((f"input {defn.input.__name__}", defn.input))
    import types as _types

    for where, model in models:
        for fname, f in model.model_fields.items():
            ann = f.annotation
            is_text = ann is str or (
                typing.get_origin(ann) in (typing.Union, _types.UnionType)
                and str in typing.get_args(ann))
            if not is_text:
                continue
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


def check_opaque_refs(registry: Any) -> None:
    """Raw identity tokens are machine plumbing, not human information.

    Runs at engine assembly (it needs every kind registered): a Data field
    named ``{kind}_id`` for a registered kind is a cross-resource reference,
    and rendering it as a bare token forces humans to read UUIDs. Declare on
    the field's ``x-display``: ``widget: "resource"`` (clients render a
    navigable reference, optionally labeled by a sibling ``label_field``),
    ``hidden: true`` (machine-only), or ``raw: true`` (acknowledged raw
    display). Fields that don't match a registered kind (external ids like
    ``calendar_event_id``) are out of scope.
    """
    kinds = set(registry.kinds())
    for rdef in registry.defs():
        models: list[tuple[str, type[BaseModel]]] = [("data", rdef.cls.Data)]
        for dname, dfield in rdef.cls.Data.model_fields.items():
            if typing.get_origin(dfield.annotation) is list:
                args = typing.get_args(dfield.annotation)
                if args and isinstance(args[0], type) \
                        and issubclass(args[0], BaseModel):
                    models.append((f"data.{dname}[]", args[0]))
        for where, model in models:
            for fname, f in model.model_fields.items():
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
                    f"{where}.{fname} references a {target!r} but renders as "
                    f"a raw id token to humans. Declare x-display "
                    f"{{'widget': 'resource', 'kind': {target!r}}} for a "
                    f"navigable reference (add 'label_field' to name a "
                    f"sibling holding the human label), {{'hidden': True}} "
                    f"for machine-only plumbing, or {{'raw': True}} to "
                    f"acknowledge the raw display.",
                    UsabilityWarning, stacklevel=2)


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
        # outer keys (x-display, defaults) survive the ref hop
        prop = {**target, **{k: v for k, v in prop.items() if k != "$ref"}}
    return prop
