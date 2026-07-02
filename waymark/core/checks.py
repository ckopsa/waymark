"""Import-time checks (§10.1): fail fast, before the app serves a request.

Every violation raises DefinitionError naming the resource, the action, and
the rule. These run from ``Resource.__init_subclass__``, so a broken
definition module cannot even be imported.
"""
from __future__ import annotations

import inspect
import re
import string
import typing
from typing import Any

from .actions import ActionDef
from .machine import StateMachine

_SNAKE = re.compile(r"^[a-z][a-z0-9_]*$")


class DefinitionError(TypeError):
    pass


def run_all(cls: type, machine: StateMachine, *, allow_dead: frozenset[str],
            summary_template: str) -> None:
    check_tokens(cls, machine)
    check_reachability(cls, machine, allow_dead)
    check_terminal_no_exit(cls, machine)
    check_reversible(cls, machine)
    check_guard_templates(cls, machine)
    check_handler_signatures(cls, machine)
    check_summary_template(cls, summary_template)


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
