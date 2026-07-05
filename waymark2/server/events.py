"""Events: the transition log is the outbox is the activity feed.

A single dispatcher task tails ``waymark2_transitions`` — woken by the bus
(design §7: the same LISTEN/NOTIFY seam collab rides) with a polling
fallback — and fans out to SSE subscribers. Delivery is at-least-once;
clients dedupe on ``(self, version)``.
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from dataclasses import dataclass, field
from typing import Any, AsyncIterator

from ..core.registry import Registry
from .storage.postgres import NOTIFY_CHANNEL, PostgresStorage
from .storage.protocol import TransitionRecord

log = logging.getLogger("waymark2.events")


def event_payload(t: TransitionRecord, registry: Registry, base: str) -> dict[str, Any]:
    rdef = registry[t.kind]
    return {
        "kind": t.kind,
        "self": f"{base}/{rdef.plural}/{t.resource_id}",
        "action": t.action,
        "from": t.from_state,
        "to": t.to_state,
        "actor": {"type": t.actor_type, "id": t.actor_id,
                  "display": t.actor_display},
        "at": t.at.isoformat(),
        "version": t.version,
        "summary": t.summary,
    }


@dataclass(eq=False)  # identity semantics: each subscriber is its own queue
class Subscription:
    kinds: frozenset[str] | None = None      # None = all kinds
    resource: tuple[str, str] | None = None  # (kind, id)
    queue: asyncio.Queue = field(default_factory=lambda: asyncio.Queue(maxsize=1000))
    # Last-Event-ID replay: live events buffer in `pending` until the replayed
    # backlog is enqueued, so the stream stays ordered without losing events
    paused: bool = False
    pending: list = field(default_factory=list)

    def wants(self, t: TransitionRecord) -> bool:
        if self.resource is not None:
            return (t.kind, t.resource_id) == self.resource
        return self.kinds is None or t.kind in self.kinds

    def deliver(self, t: TransitionRecord) -> None:
        if self.paused:
            self.pending.append(t)
        else:
            self.queue.put_nowait(t)


class Dispatcher:
    def __init__(self, storage: PostgresStorage, registry: Registry, *,
                 base: str = "/api", poll_interval: float = 2.0,
                 bus: Any = None):
        self.storage = storage
        self.registry = registry
        self.base = base
        self.poll_interval = poll_interval
        self._subs: set[Subscription] = set()
        self._wake = asyncio.Event()
        self._task: asyncio.Task | None = None
        self._last_seen = 0
        if bus is not None:
            # register before bus.start() so PostgresBus LISTENs the channel
            bus.listen(NOTIFY_CHANNEL, self._on_bus)

    def _on_bus(self, _message: dict[str, Any]) -> None:
        self._wake.set()

    async def start(self) -> None:
        async with self.storage.session() as s:
            from sqlalchemy import func, select

            self._last_seen = (await s.execute(
                select(func.coalesce(func.max(self.storage.transitions.c.id), 0))
            )).scalar_one()
        self._task = asyncio.create_task(self._run(), name="waymark2-dispatcher")

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
            self._task = None

    def subscribe(self, *, kinds: frozenset[str] | None = None,
                  resource: tuple[str, str] | None = None,
                  paused: bool = False) -> Subscription:
        sub = Subscription(kinds=kinds, resource=resource, paused=paused)
        self._subs.add(sub)
        return sub

    def unsubscribe(self, sub: Subscription) -> None:
        self._subs.discard(sub)

    async def replay_since(self, sub: Subscription, after_id: int) -> None:
        """Enqueue the backlog, then flush buffered live events in order."""
        async with self.storage.session() as s:
            rows = await self.storage.transitions_since(s, after_id)
        delivered = after_id
        for t in rows:
            if sub.wants(t):
                sub.queue.put_nowait(t)
            delivered = max(delivered, t.id)
        for t in sub.pending:
            if t.id > delivered:
                sub.queue.put_nowait(t)
        sub.pending.clear()
        sub.paused = False

    async def _run(self) -> None:
        while True:
            with contextlib.suppress(asyncio.TimeoutError, TimeoutError):
                await asyncio.wait_for(self._wake.wait(), timeout=self.poll_interval)
            self._wake.clear()
            try:
                async with self.storage.session() as s:
                    rows = await self.storage.transitions_since(s, self._last_seen)
            except Exception as exc:  # keep the loop alive across db hiccups
                log.warning("dispatcher poll failed: %s", exc)
                continue
            for t in rows:
                self._last_seen = max(self._last_seen, t.id)
                for sub in list(self._subs):
                    if sub.wants(t):
                        try:
                            sub.deliver(t)
                        except asyncio.QueueFull:  # slow consumer: drop + log
                            log.warning("subscriber queue full; dropping event %s", t.id)


async def sse_stream(dispatcher: Dispatcher, sub: Subscription,
                     registry: Registry, base: str,
                     last_event_id: str | None = None) -> AsyncIterator[str]:
    """Yields SSE-framed ``transition`` events; ``id`` is the global
    transition id so ``Last-Event-ID`` resumes the stream without a second
    event system. Client disconnect cancels the generator (Starlette), which
    unsubscribes via ``finally``."""
    try:
        if last_event_id and last_event_id.isdigit():
            await dispatcher.replay_since(sub, int(last_event_id))
        else:
            for t in sub.pending:
                sub.queue.put_nowait(t)
            sub.pending.clear()
            sub.paused = False
        while True:
            t: TransitionRecord = await sub.queue.get()
            data = json.dumps(event_payload(t, registry, base))
            yield f"id: {t.id}\nevent: transition\ndata: {data}\n\n"
    finally:
        dispatcher.unsubscribe(sub)


def sse_response(generator: AsyncIterator[str]) -> Any:
    from starlette.responses import StreamingResponse

    return StreamingResponse(
        generator, media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
