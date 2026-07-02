"""The @action decorator (§10.1): transitions with explicit safety metadata.

``idempotent``, ``reversible`` and ``confirm`` have **no implicit values** —
omitting any of them is a TypeError at decoration time, before the class even
assembles.
"""
from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field, replace
from typing import Any

from pydantic import BaseModel

from .guards import Guard
from .types import Effect, Safety

_REQUIRED: Any = object()


@dataclass(frozen=True)
class ActionDef:
    name: str
    from_: frozenset[str]
    to: str
    input: type[BaseModel] | None
    guards: tuple[Guard, ...]
    safety: Safety
    emits: tuple[str, ...]
    display: Mapping[str, Any]
    handler: Callable  # unbound async def (self, inp, ctx) -> None
    bulk: bool = False
    atomic: bool = False
    max_items: int = 500
    defer_over: int | None = None  # ids beyond this → 202 + job resource
    terminal: bool = False  # filled in by the machine build
    field_display: Mapping[str, Any] = field(default_factory=dict)

    @property
    def effect(self) -> Effect:
        return Effect(to=self.to, terminal=self.terminal, emits=self.emits, bulk=self.bulk)

    def with_terminal(self, terminal: bool) -> "ActionDef":
        return replace(self, terminal=terminal)


def emits(*events: str) -> tuple[str, ...]:
    return events


def action(
    *,
    from_: Any,
    to: Any,
    input: type[BaseModel] | None = None,
    guards: Sequence[Guard] = (),
    idempotent: bool = _REQUIRED,
    reversible: bool = _REQUIRED,
    confirm: bool = _REQUIRED,
    requires_if_match: bool = False,
    side_effects: tuple[str, ...] = (),
    display: Mapping[str, Any] | None = None,
    field_display: Mapping[str, Any] | None = None,
    bulk: bool = False,
    atomic: bool = False,
    max_items: int = 500,
    defer_over: int | None = None,
) -> Callable:
    missing = [n for n, v in (("idempotent", idempotent), ("reversible", reversible),
                              ("confirm", confirm)) if v is _REQUIRED]
    if missing:
        raise TypeError(
            f"@action requires explicit safety fields; missing: {', '.join(missing)} "
            "(safety is declared, not inferred — spec §0.4)"
        )
    froms = from_ if isinstance(from_, (set, frozenset, list, tuple)) else {from_}
    from_tokens = frozenset(str(s) for s in froms)

    def decorate(fn: Callable) -> Callable:
        fn.__waymark_action__ = ActionDef(  # type: ignore[attr-defined]
            name=fn.__name__,
            from_=from_tokens,
            to=str(to),
            input=input,
            guards=tuple(guards),
            safety=Safety(
                idempotent=idempotent, reversible=reversible,
                confirm=confirm, requires_if_match=requires_if_match,
            ),
            emits=tuple(side_effects),
            display=dict(display or {}),
            handler=fn,
            bulk=bulk,
            atomic=atomic,
            max_items=max_items,
            defer_over=defer_over,
            field_display=dict(field_display or {}),
        )
        return fn

    return decorate
