"""Core value types: principals, guard verdicts, safety.

2.0 change (design §3): ``Safety`` is a declaration object whose invariants
live in its constructor — a confirm without a stated consequence, or an
unacknowledged one-way door, cannot be expressed at all.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any, Literal

ActorType = Literal["human", "agent", "system"]


class DefinitionError(TypeError):
    """A resource definition violates a declared rule. Raised at import."""


@dataclass(frozen=True)
class Principal:
    id: str
    type: ActorType = "human"
    roles: frozenset[str] = frozenset()
    display: str = ""
    locale: str = "en"

    @classmethod
    def anonymous(cls) -> "Principal":
        return cls(id="anonymous", type="human", display="Anonymous")


@dataclass
class Ctx:
    """Per-invocation context passed to guards and handlers.

    ``mode`` distinguishes render-time probing (``inp is None``) from real
    invocation; guards whose ``check`` needs input short-circuit to
    ``Allow(pending_input=True)`` during probe.
    """

    principal: Principal
    now: datetime
    services: Any = None
    session: Any = None  # ambient AsyncSession during invoke; None in pure render tests
    locale: str = "en"
    correlation_id: str | None = None
    mode: Literal["probe", "invoke", "dry_run"] = "invoke"
    _invoker: Any = None  # set by the engine; enables ctx.invoke for workflows
    _reader: Any = None   # set by the engine; enables ctx.read for guards
    _finder: Any = None   # set by the engine; enables ctx.find for guards/hooks

    async def invoke(self, resource: type | str, id: str, action: str,
                     body: dict[str, Any] | None = None) -> Any:
        """Invoke a transition on another resource through the engine.

        Shares this invocation's transaction and correlation_id.
        """
        if self._invoker is None:
            raise RuntimeError("ctx.invoke is only available inside an engine-managed invocation")
        return await self._invoker(resource, id, action, body, ctx=self)

    async def read(self, resource: type | str, id: str) -> Any:
        """Load another resource's current instance in this transaction —
        the read half of cross-resource guards."""
        if self._reader is None:
            raise RuntimeError("ctx.read is only available inside an engine-managed invocation")
        return await self._reader(resource, id, ctx=self)

    async def find(self, resource: type | str, *, sort: str | None = None,
                   limit: int = 25, **filters: Any) -> list[Any]:
        """Query another kind's instances in this transaction — the list half
        of cross-resource reads. Filters take the collection query's field
        names (e.g. ``state="active"``)."""
        if self._finder is None:
            raise RuntimeError("ctx.find is only available inside an engine-managed invocation")
        return await self._finder(resource, filters, sort=sort, limit=limit,
                                  ctx=self)


@dataclass(frozen=True)
class Allow:
    pending_input: bool = False


@dataclass(frozen=True)
class Deny:
    vars: dict[str, Any] | None = None
    errors: dict[str, list[str]] | None = None
    retry_at: datetime | None = None


@dataclass(frozen=True)
class Acknowledged:
    """A written acknowledgment for an escape hatch (design §1, §3).

    Wherever 2.0 turns a v1 warning into a structural rule, the way out is
    not a bare waiver token but a sentence: you must say *why* this
    declaration is safe as written.
    """

    reason: str

    def __post_init__(self) -> None:
        if not self.reason or not self.reason.strip():
            raise DefinitionError("Acknowledged() requires a non-empty reason "
                                  "— the acknowledgment is the sentence")


@dataclass(frozen=True)
class Safety:
    """Declared safety semantics; the invariants are the constructor's.

    - ``confirm=True`` requires ``consequence`` — a confirm dialog that
      cannot say what happens is unrepresentable (v1's planned
      ``blind_confirm``).
    - ``fence=True`` renders as wire ``requires_if_match`` (the DSL name
      changed in 2.0; the wire did not).
    - ``one_way`` acknowledges an irreversible, unconfirmed, state-leaving
      transition (v1's planned ``one_way_door``); whether it is *required*
      is the machine checker's call — the graph knows about self-loops,
      this object does not.
    """

    idempotent: bool
    reversible: bool
    confirm: bool
    fence: bool = False
    consequence: str | None = None
    one_way: Acknowledged | None = None

    def __post_init__(self) -> None:
        if self.confirm and not (self.consequence and self.consequence.strip()):
            raise DefinitionError(
                "Safety(confirm=True) requires consequence='…' stating what "
                "will happen — a confirm that only asks 'are you sure?' is a "
                "blind confirm")
        if self.one_way is not None and (self.reversible or self.confirm):
            raise DefinitionError(
                "Safety(one_way=…) acknowledges an irreversible, unconfirmed "
                "transition; this one is "
                + ("reversible" if self.reversible else "confirmed"))

    def to_wire(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "idempotent": self.idempotent,
            "reversible": self.reversible,
            "confirm": self.confirm,
        }
        if self.fence:
            out["requires_if_match"] = True
        return out

    # The server fork and the wire keep v1's spelling; the DSL says `fence`.
    @property
    def requires_if_match(self) -> bool:
        return self.fence


@dataclass(frozen=True)
class Effect:
    to: str
    terminal: bool = False
    emits: tuple[str, ...] = ()
    bulk: bool = False

    def to_wire(self) -> dict[str, Any]:
        out: dict[str, Any] = {"to": self.to}
        if self.terminal:
            out["terminal"] = True
        if self.emits:
            out["emits"] = list(self.emits)
        if self.bulk:
            out["bulk"] = True
        return out
