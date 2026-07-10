"""Declared touches (design E8): a transition says what it spans.

The engine always had lawful multi-resource *execution* — ``ctx.create``
and ``ctx.invoke`` run in the enclosing invocation's transaction and
correlation id, each touched resource transitioning through its own
machine. What a handler that quietly creates a peer lacked was the same
thing composition's ``check=`` lacked: a declaration. ``touches=`` is
that declaration — rendered on the action's ``effect``, enforced by the
ctx the handler receives, verified by conformance against the log.

The quantum-breaking shape is refused, not blessed: there is no
``Moves`` — re-parenting a child is a declared child action advanced via
``Advances``, so the move IS a child transition with its own log entry.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .types import DefinitionError


@dataclass(frozen=True)
class Creates:
    """This action may ``ctx.create`` resources of ``kind``."""

    kind: str
    may: bool = False

    def __post_init__(self) -> None:
        if not self.kind:
            raise DefinitionError("Creates requires kind=")

    def to_wire(self) -> dict[str, Any]:
        out: dict[str, Any] = {"creates": self.kind}
        if self.may:
            out["may"] = True
        return out


@dataclass(frozen=True)
class Advances:
    """This action may ``ctx.invoke`` ``action`` on resources of ``kind``."""

    kind: str
    action: str
    may: bool = False

    def __post_init__(self) -> None:
        if not self.kind or not self.action:
            raise DefinitionError("Advances requires kind= and action=")

    def to_wire(self) -> dict[str, Any]:
        out: dict[str, Any] = {"advances": f"{self.kind}.{self.action}"}
        if self.may:
            out["may"] = True
        return out


@dataclass(frozen=True)
class Delegated:
    """The acknowledged escape hatch (the ``Acknowledged`` discipline):
    the touch set is data of the resource itself — an approval's run
    executes what a human just approved. The hatch is a sentence."""

    reason: str

    def __post_init__(self) -> None:
        if not self.reason or not self.reason.strip():
            raise DefinitionError(
                "Delegated() requires a non-empty reason — say why this "
                "action's touches are the resource's data to declare")

    def to_wire(self) -> dict[str, Any]:
        return {"delegated": True}


def allows_create(touches: tuple[Any, ...], kind: str) -> bool:
    return any(isinstance(t, Delegated)
               or (isinstance(t, Creates) and t.kind == kind)
               for t in touches)


def allows_advance(touches: tuple[Any, ...], kind: str, action: str) -> bool:
    return any(isinstance(t, Delegated)
               or (isinstance(t, Advances) and t.kind == kind
                   and t.action == action)
               for t in touches)
