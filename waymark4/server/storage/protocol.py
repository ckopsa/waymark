"""The Storage protocol (§11.1): ~6 methods; implement it and keep everything else."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any, AsyncContextManager, Protocol, runtime_checkable

from ...core.resource import Resource
from ...core.types import Principal


@dataclass(frozen=True)
class TransitionRecord:
    id: int
    kind: str
    resource_id: str
    action: str
    from_state: str
    to_state: str
    version: int
    actor_type: str
    actor_id: str
    actor_display: str
    input_digest: str
    correlation_id: str | None
    summary: str
    at: datetime
    # names of warning guards the actor acknowledged past (design E1);
    # None = nothing was overridden
    acknowledged: list[str] | None = None


@runtime_checkable
class Storage(Protocol):
    def session(self) -> AsyncContextManager[Any]:
        """One transaction; every invocation runs entirely inside it."""
        ...

    async def load(self, s: Any, kind: str, id: str, *,
                   for_update: bool = False) -> Resource | None: ...

    async def insert(self, s: Any, kind: str, instance: Resource) -> None: ...

    async def save(self, s: Any, kind: str, instance: Resource, *,
                   expected_version: int) -> None: ...

    async def query(self, s: Any, kind: str, *, filters: dict[str, Any],
                    sort: str | None, page_size: int,
                    page_number: int) -> tuple[list[Resource], int]: ...

    async def append_transition(
        self, s: Any, *, kind: str, instance: Resource, action: str,
        from_state: str, principal: Principal, input_digest: str,
        summary: str, at: datetime, correlation_id: str | None = None,
    ) -> TransitionRecord: ...
