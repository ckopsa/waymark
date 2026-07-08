"""One bus (design §7): the single cross-process fan-out seam.

v1 built process fan-out three times — SSE got LISTEN/NOTIFY, collab rooms
got an in-process dict with a "pin to one worker" caveat, the rate limiter
got a per-process counter. 2.0 has one seam with two implementations:

- :class:`InProcessBus` — dev/test/single-worker; publish delivers to local
  listeners directly.
- :class:`PostgresBus` — LISTEN/NOTIFY; every worker's bus hears every
  publish, so collab rooms and SSE wake-ups work across uvicorn workers.

Consumers: the SSE dispatcher (transition wake-ups), collab rooms (frame
relay), deferred-job progress (rides transitions). Payloads must stay under
Postgres's ~8 KB NOTIFY budget — publishers that might exceed it send a
sync marker and let receivers re-read the database (the truth is always in
the tables; the bus only carries liveness).
"""
from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import uuid
from collections import defaultdict
from typing import Any, Awaitable, Callable, Protocol

log = logging.getLogger("waymark7.bus")

Listener = Callable[[dict[str, Any]], Awaitable[None] | None]

NOTIFY_BUDGET = 7500  # bytes; stay under Postgres's ~8 KB NOTIFY limit


class Bus(Protocol):
    origin: str  # unique per bus instance; lets a worker skip its own echo

    async def start(self) -> None: ...
    async def stop(self) -> None: ...
    async def publish(self, channel: str, message: dict[str, Any]) -> None: ...
    def listen(self, channel: str, listener: Listener) -> None: ...


class _BaseBus:
    def __init__(self) -> None:
        self.origin = uuid.uuid4().hex
        self._listeners: dict[str, list[Listener]] = defaultdict(list)

    def listen(self, channel: str, listener: Listener) -> None:
        self._listeners[channel].append(listener)

    async def _deliver(self, channel: str, message: dict[str, Any]) -> None:
        for listener in self._listeners.get(channel, []):
            try:
                out = listener(message)
                if asyncio.iscoroutine(out):
                    await out
            except Exception:  # a broken listener must not stall the bus
                log.exception("bus listener failed on channel %r", channel)


class InProcessBus(_BaseBus):
    """Single-process delivery; the degenerate (and test) case."""

    async def start(self) -> None:  # pragma: no cover - nothing to do
        pass

    async def stop(self) -> None:  # pragma: no cover - nothing to do
        pass

    async def publish(self, channel: str, message: dict[str, Any]) -> None:
        await self._deliver(channel, {**message, "_origin": self.origin})


class PostgresBus(_BaseBus):
    """LISTEN/NOTIFY delivery: one listener connection per process, every
    worker hears every publish (its own included — consumers use
    ``message["_origin"]`` to skip echoes when they already delivered
    locally)."""

    def __init__(self, dsn: str):
        super().__init__()
        # asyncpg wants a plain postgresql:// DSN
        self.dsn = dsn.replace("postgresql+asyncpg://", "postgresql://")
        self._conn: Any = None
        self._pub: Any = None
        self._pub_lock = asyncio.Lock()
        self._channels: set[str] = set()

    def listen(self, channel: str, listener: Listener) -> None:
        super().listen(channel, listener)
        self._channels.add(channel)

    async def start(self) -> None:
        import asyncpg

        self._conn = await asyncpg.connect(self.dsn)
        for channel in self._channels:
            await self._conn.add_listener(channel, self._on_notify)

    def _on_notify(self, _conn: Any, _pid: Any, channel: str, payload: str) -> None:
        try:
            message = json.loads(payload)
        except json.JSONDecodeError:
            message = {"raw": payload}
        # asyncpg callbacks are sync; hop back into the loop
        asyncio.get_running_loop().create_task(self._deliver(channel, message))

    async def stop(self) -> None:
        for conn in (self._conn, self._pub):
            if conn is not None:
                with contextlib.suppress(Exception):
                    await conn.close()
        self._conn = self._pub = None

    async def publish(self, channel: str, message: dict[str, Any]) -> None:
        payload = json.dumps({**message, "_origin": self.origin}, default=str)
        if len(payload.encode()) > NOTIFY_BUDGET:
            # the truth is in the tables; the bus only carries liveness
            payload = json.dumps({"type": "sync", "_origin": self.origin})
        import asyncpg

        async with self._pub_lock:
            if self._pub is None or self._pub.is_closed():
                self._pub = await asyncpg.connect(self.dsn)
            await self._pub.execute("SELECT pg_notify($1, $2)", channel, payload)


def make_bus(dsn: str | None, *, in_process: bool = False) -> Bus:
    if in_process or not dsn or not dsn.startswith("postgresql"):
        return InProcessBus()
    return PostgresBus(dsn)


RATE_CHANNEL = "waymark7_ratelimit"


class RateCoordinator:
    """The ``guard.rate_limit`` window, coordinated over the bus (design
    §8): every consumed hit publishes, every worker folds remote hits into
    its window, so N workers enforce one limit instead of N. v2's deviation
    note ("the bus exists but the limiter doesn't ride it yet") was the
    last per-process counter — the §7 argument (no private plumbing to
    outgrow) applies to it verbatim.

    Windows are keyed ``(scope, principal)``; hits are datetimes, pruned on
    read. Best-effort by declaration: a lost NOTIFY slightly over-admits,
    which is the honest trade for a limiter that is not a database queue.
    """

    def __init__(self, bus: Any = None):
        self.bus = bus
        self.windows: dict[str, list[Any]] = {}
        if bus is not None:
            bus.listen(RATE_CHANNEL, self._on_bus)

    def _on_bus(self, message: dict[str, Any]) -> None:
        # no origin skip: every worker hears every hit exactly once, its
        # own included — the echo IS the count
        key, at = message.get("key"), message.get("at")
        if not key or not at:
            return
        from datetime import datetime

        with contextlib.suppress(ValueError):
            self.windows.setdefault(key, []).append(datetime.fromisoformat(at))

    async def hit(self, key: str, at: Any) -> None:
        if self.bus is None:  # guard-local fallback outside an engine
            self.windows.setdefault(key, []).append(at)
            return
        await self.bus.publish(RATE_CHANNEL, {"key": key, "at": at.isoformat()})

    def window(self, key: str, cutoff: Any) -> list[Any]:
        hits = sorted(t for t in self.windows.get(key, []) if t > cutoff)
        self.windows[key] = hits
        return hits
