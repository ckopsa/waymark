"""Core value types shared across the engine: principals, guard verdicts, safety."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any, Literal

ActorType = Literal["human", "agent", "system"]


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
    invocation; guards that need input short-circuit to
    ``Allow(pending_input=True)`` during probe.
    """

    principal: Principal
    now: datetime
    services: Any = None
    session: Any = None  # ambient AsyncSession during invoke; None in pure render tests
    locale: str = "en"
    correlation_id: str | None = None
    # "dry_run" runs guards with real input but signals side-effectful guards
    # (e.g. rate limits) not to consume budget
    mode: Literal["probe", "invoke", "dry_run"] = "invoke"
    _invoker: Any = None  # set by the engine; enables ctx.invoke for workflows
    _reader: Any = None   # set by the engine; enables ctx.read for guards

    async def invoke(self, resource: type | str, id: str, action: str,
                     body: dict[str, Any] | None = None) -> Any:
        """Invoke a transition on another resource through the engine (§14).

        Shares this invocation's transaction and correlation_id.
        """
        if self._invoker is None:
            raise RuntimeError("ctx.invoke is only available inside an engine-managed invocation")
        return await self._invoker(resource, id, action, body, ctx=self)

    async def read(self, resource: type | str, id: str) -> Any:
        """Load another resource's current instance in this transaction —
        the read half of cross-resource guards (§14)."""
        if self._reader is None:
            raise RuntimeError("ctx.read is only available inside an engine-managed invocation")
        return await self._reader(resource, id, ctx=self)


@dataclass(frozen=True)
class Allow:
    pending_input: bool = False


@dataclass(frozen=True)
class Deny:
    vars: dict[str, Any] | None = None
    errors: dict[str, list[str]] | None = None
    retry_at: datetime | None = None


@dataclass(frozen=True)
class Safety:
    idempotent: bool
    reversible: bool
    confirm: bool
    requires_if_match: bool = False

    def to_wire(self) -> dict[str, Any]:
        out: dict[str, Any] = {
            "idempotent": self.idempotent,
            "reversible": self.reversible,
            "confirm": self.confirm,
        }
        if self.requires_if_match:
            out["requires_if_match"] = True
        return out


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
