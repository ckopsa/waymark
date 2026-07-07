"""Engine-internal log consumers (design E4/E5): the dispatcher is only
a wake signal; the feed is ``transitions_since`` behind a durable cursor
row, so an outage drains instead of dropping. First boot seeds at the
log's head — pre-feature history never replays. Per-event failures log
and the cursor advances: liveness over completeness, the webhook
deliverer's documented trade.
"""
from __future__ import annotations

import asyncio
import contextlib
import logging
from typing import Any

log = logging.getLogger("waymark5.consumers")


class LogConsumer:
    """Subclasses declare ``consumer`` (the cursor name) and ``kinds``
    (the wake filter) and implement ``handle(t)`` per transition."""

    consumer: str
    kinds: frozenset[str] | None = None

    def __init__(self, engine: Any):
        self.engine = engine
        self._sub: Any = None
        self._task: asyncio.Task | None = None

    async def handle(self, t: Any) -> None:  # pragma: no cover - abstract
        raise NotImplementedError

    def start(self, dispatcher: Any) -> None:
        self._sub = dispatcher.subscribe(
            kinds=self.kinds, classes=frozenset({"transition"}))
        self._task = asyncio.create_task(
            self._run(), name=f"waymark5-{self.consumer}")

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
            self._task = None

    async def _run(self) -> None:
        await self._ensure_cursor()
        await self.drain()  # an outage's backlog drains before new wakes
        while True:
            await self._sub.queue.get()
            while not self._sub.queue.empty():  # coalesce the burst
                self._sub.queue.get_nowait()
            try:
                await self.drain()
            except Exception:  # noqa: BLE001 — the loop must survive
                log.exception("%s drain failed; will retry on next wake",
                              self.consumer)

    async def _ensure_cursor(self) -> None:
        storage = self.engine.storage
        async with storage.session() as s:
            if await storage.cursor(s, self.consumer) is None:
                await storage.set_cursor(s, self.consumer,
                                         await storage.max_transition_id(s),
                                         self.engine.invoker.clock())

    async def drain(self) -> None:
        storage = self.engine.storage
        while True:
            async with storage.session() as s:
                last = await storage.cursor(s, self.consumer) or 0
                batch = await storage.transitions_since(s, last, limit=200)
            if not batch:
                return
            for t in batch:
                try:
                    await self.handle(t)
                except Exception:  # noqa: BLE001 — liveness over completeness
                    log.exception("%s: handling %s.%s for %s failed; "
                                  "cursor advances", self.consumer,
                                  t.kind, t.action, t.resource_id)
                async with storage.session() as s:
                    await storage.set_cursor(s, self.consumer, t.id,
                                             self.engine.invoker.clock())
