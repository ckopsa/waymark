"""Events: one taxonomy, one dispatcher (design §3).

Every event is one of two declared classes:

- ``transition`` — durable, log-ordered, at-least-once, replayable via
  ``Last-Event-ID``. The transition log is the outbox is the activity feed.
- ``observation`` — ephemeral, at-most-once, drop-on-pressure, never
  stored. ``viewed``/``engaged`` liveness: who is looking at what, right
  now. Reads never enter the audit trail.

Same bus, same ``actor``/``kind``/``at`` shape; the delivery discipline is
a property of the declared class, not of which hub you subscribed to —
v2's parallel ``PresenceHub`` (its own queues, its own channel, its event
*type* inferred from payload shape) folds into the one dispatcher, and the
event name is declared at the emit site (:func:`publish_observation`).
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

log = logging.getLogger("waymark3.events")

OBSERVATION_CHANNEL = "waymark3_observations"


async def publish_observation(bus: Any, *, event: str, actor: dict[str, Any],
                              self_href: str, kind: str, at: str,
                              action: str | None = None,
                              via: str | None = None) -> None:
    """Emit an observation-class event. The *name* (``viewed``,
    ``engaged``) is declared here by the emitting stage — never inferred
    downstream from what the payload happens to contain."""
    message: dict[str, Any] = {"event": event, "class": "observation",
                               "actor": actor, "self": self_href,
                               "kind": kind, "at": at}
    if action:
        message["action"] = action
    if via:
        message["via"] = via
    await bus.publish(OBSERVATION_CHANNEL, message)


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
    actor: str | None = None                 # follow one principal's actions
    # which event classes this subscriber receives (design §3)
    classes: frozenset[str] = frozenset({"transition"})
    queue: asyncio.Queue = field(default_factory=lambda: asyncio.Queue(maxsize=1000))
    # Last-Event-ID replay: live events buffer in `pending` until the replayed
    # backlog is enqueued, so the stream stays ordered without losing events
    paused: bool = False
    pending: list = field(default_factory=list)

    def wants(self, t: TransitionRecord) -> bool:
        if "transition" not in self.classes:
            return False
        if self.actor is not None and t.actor_id != self.actor:
            return False
        if self.resource is not None:
            return (t.kind, t.resource_id) == self.resource
        return self.kinds is None or t.kind in self.kinds

    def wants_observation(self, m: dict[str, Any]) -> bool:
        if "observation" not in self.classes:
            return False
        if self.actor is not None \
                and (m.get("actor") or {}).get("id") != self.actor:
            return False
        return self.kinds is None or m.get("kind") in self.kinds

    def deliver(self, t: TransitionRecord) -> None:
        if self.paused:
            self.pending.append(t)
        else:
            self.queue.put_nowait(t)

    def deliver_observation(self, m: dict[str, Any]) -> None:
        """At-most-once by declaration: no pause buffering, no replay, and
        pressure drops the event — liveness may be lost; truth may not."""
        with contextlib.suppress(asyncio.QueueFull):
            self.queue.put_nowait(m)


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
            # register before bus.start() so PostgresBus LISTENs the channels
            bus.listen(NOTIFY_CHANNEL, self._on_bus)
            bus.listen(OBSERVATION_CHANNEL, self._on_observation)

    def _on_bus(self, _message: dict[str, Any]) -> None:
        self._wake.set()

    def _on_observation(self, message: dict[str, Any]) -> None:
        payload = {k: v for k, v in message.items() if k != "_origin"}
        for sub in list(self._subs):
            if sub.wants_observation(payload):
                sub.deliver_observation(payload)

    async def start(self) -> None:
        async with self.storage.session() as s:
            from sqlalchemy import func, select

            self._last_seen = (await s.execute(
                select(func.coalesce(func.max(self.storage.transitions.c.id), 0))
            )).scalar_one()
        self._task = asyncio.create_task(self._run(), name="waymark3-dispatcher")

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
            self._task = None

    def subscribe(self, *, kinds: frozenset[str] | None = None,
                  resource: tuple[str, str] | None = None,
                  actor: str | None = None,
                  classes: frozenset[str] = frozenset({"transition"}),
                  paused: bool = False) -> Subscription:
        sub = Subscription(kinds=kinds, resource=resource, actor=actor,
                           classes=classes, paused=paused)
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
    """Yields SSE-framed events per the subscription's declared classes.

    Transition-class events carry ``id`` (the global transition id) so
    ``Last-Event-ID`` resumes the stream; observation-class events carry
    their declared name and no id — there is nothing to resume, by design.
    Client disconnect cancels the generator (Starlette), which unsubscribes
    via ``finally``."""
    try:
        if last_event_id and last_event_id.isdigit():
            await dispatcher.replay_since(sub, int(last_event_id))
        else:
            for t in sub.pending:
                sub.queue.put_nowait(t)
            sub.pending.clear()
            sub.paused = False
        while True:
            item = await sub.queue.get()
            if isinstance(item, TransitionRecord):
                data = json.dumps(event_payload(item, registry, base))
                yield f"id: {item.id}\nevent: transition\ndata: {data}\n\n"
            else:  # observation: declared name, no id, no resume
                yield (f"event: {item['event']}\n"
                       f"data: {json.dumps(item)}\n\n")
    finally:
        dispatcher.unsubscribe(sub)


def sse_response(generator: AsyncIterator[str]) -> Any:
    from starlette.responses import StreamingResponse

    return StreamingResponse(
        generator, media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
