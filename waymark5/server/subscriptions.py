"""The outbox is a product (design §10): webhook subscriptions.

The transition log is already an outbox — this exposes it. A
``subscription`` is an engine-owned resource (url, kind filter, secret);
delivery is at-least-once off the log with backoff, each event signed
(``X-Waymark-Signature: sha256=<hmac(secret, body)>``). Third parties
integrate by consuming plain signed transition JSON without ever learning
the envelope format.

Delivery discipline: **at-least-once off the log.** Each subscription
keeps a cursor (an engine-owned row, the DraftStore pattern); the
deliverer drains ``transitions_since(cursor)`` — woken by the dispatcher,
resumed across restarts, so an outage replays instead of dropping. A
subscription's cursor starts at its own creation transition: it hears the
world from the moment it exists, never before. Per-event failures retry
with backoff and are then *skipped with the cursor advanced* and a log
line — a broken endpoint must not dam the log; at-least-once yields to
liveness only after the declared attempts, and says so.
"""
from __future__ import annotations

import asyncio
import contextlib
import hashlib
import hmac
import json
import logging
import uuid
from enum import StrEnum
from typing import Any

import httpx
from pydantic import BaseModel, Field
from pydantic.json_schema import SkipJsonSchema

from ..core.actions import action
from ..core.resource import Resource, filterable
from ..core.types import Ctx, Safety

log = logging.getLogger("waymark5.subscriptions")


class SubscriptionState(StrEnum):
    ACTIVE = "active"
    PAUSED = "paused"
    REVOKED = "revoked"


def _mint_secret() -> str:
    return "whsec_" + uuid.uuid4().hex


class SubscriptionData(BaseModel):
    url: str = Field(max_length=250, pattern=r"^https?://",
                     json_schema_extra={"x-display": {"raw": True}},
                     description="Where transition events POST to")
    kinds: list[str] = Field(
        default_factory=list, max_length=32,
        description="Resource kinds to deliver; empty = every kind")
    description: str | None = Field(default=None, max_length=200)
    secret: str = Field(default_factory=_mint_secret,
                        description="HMAC key for X-Waymark-Signature",
                        json_schema_extra={"x-display": {"raw": True}})


class SubscriptionCreate(SubscriptionData):
    secret: SkipJsonSchema[str] = Field(default_factory=_mint_secret)


class WebhookSubscription(Resource):
    kind = "subscription"
    State = SubscriptionState
    Data = SubscriptionData
    Create = SubscriptionCreate

    initial = SubscriptionState.ACTIVE
    terminal = {SubscriptionState.REVOKED}

    summary = "{data.url} · {state.label}"

    filterable = filterable(state=filterable.Eq | filterable.In)

    display = {"title": "Webhook — {data.url}"}

    @action(from_=SubscriptionState.ACTIVE, to=SubscriptionState.PAUSED,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Pause", order=1))
    async def pause(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=SubscriptionState.PAUSED, to=SubscriptionState.ACTIVE,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Resume", order=1))
    async def resume(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_={SubscriptionState.ACTIVE, SubscriptionState.PAUSED},
            to=SubscriptionState.REVOKED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Deliveries stop for good; the "
                                      "endpoint keeps nothing."),
            display=dict(label="Revoke", style="danger", order=9))
    async def revoke(self, inp: None, ctx: Ctx) -> None:
        pass


def sign(secret: str, body: bytes) -> str:
    return "sha256=" + hmac.new(secret.encode(), body,
                                hashlib.sha256).hexdigest()


class WebhookDeliverer:
    """Drains the transition log per subscription cursor; the dispatcher
    is only the wake signal. The subscription cache invalidates itself on
    the subscription kind's own transitions — dogfood all the way down."""

    def __init__(self, engine: Any, *, http: httpx.AsyncClient | None = None,
                 attempts: int = 3, backoff_seconds: float = 2.0):
        self.engine = engine
        self.http = http or httpx.AsyncClient(timeout=10.0)
        self.attempts = attempts
        self.backoff_seconds = backoff_seconds
        self._subs_cache: list[Any] | None = None
        self._task: asyncio.Task | None = None
        self._sub: Any = None

    def start(self, dispatcher: Any) -> None:
        self._sub = dispatcher.subscribe(classes=frozenset({"transition"}))
        self._task = asyncio.create_task(self._run(),
                                         name="waymark5-webhooks")

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
            self._task = None
        await self.http.aclose()

    async def _active(self) -> list[Any]:
        if self._subs_cache is None:
            async with self.engine.storage.session() as s:
                items, _ = await self.engine.storage.query(
                    s, "subscription", filters={"state": "active"},
                    sort=None, page_size=500, page_number=1)
            self._subs_cache = items
        return self._subs_cache

    async def _run(self) -> None:
        # drain once at startup: an outage replays, it doesn't drop
        with contextlib.suppress(Exception):
            await self.drain()
        while True:
            t = await self._sub.queue.get()
            # coalesce the burst: the queue is a wake signal, not the feed
            while True:
                try:
                    more = self._sub.queue.get_nowait()
                except asyncio.QueueEmpty:
                    break
                if more.kind == "subscription":
                    self._subs_cache = None
            if t.kind == "subscription":
                self._subs_cache = None  # the truth changed; re-read it
            try:
                await self.drain()
            except Exception:
                log.exception("webhook drain failed; will retry on next wake")

    async def drain(self) -> None:
        """Deliver everything past each active subscription's cursor."""
        from .events import event_payload

        subs = await self._active()
        for sub in subs:
            storage = self.engine.storage
            async with storage.session() as s:
                cursor = await storage.webhook_cursor(s, sub.id)
                if cursor is None:
                    # the world starts at the subscription's own creation
                    last = await storage.last_transition(
                        s, "subscription", sub.id)
                    cursor = last.id if last is not None else 0
                    await storage.set_webhook_cursor(
                        s, sub.id, cursor, self.engine.invoker.clock())
                rows = await storage.transitions_since(s, cursor, limit=500)
            for t in rows:
                if t.kind != "subscription" \
                        and (not sub.data.kinds or t.kind in sub.data.kinds):
                    payload = event_payload(t, self.engine.registry,
                                            self.engine.base_path)
                    body = json.dumps({"id": t.id, "class": "transition",
                                       **payload}).encode()
                    delivered = await self._deliver(sub, t.id, body)
                    if not delivered:
                        log.warning(
                            "webhook delivery to %s failed after %d attempts;"
                            " skipping event %s (cursor advances — a broken "
                            "endpoint must not dam the log)",
                            sub.data.url, self.attempts, t.id)
                async with storage.session() as s:
                    await storage.set_webhook_cursor(
                        s, sub.id, t.id, self.engine.invoker.clock())

    async def _deliver(self, sub: Any, event_id: int, body: bytes) -> bool:
        headers = {"Content-Type": "application/json",
                   "X-Waymark-Event-Id": str(event_id),
                   "X-Waymark-Signature": sign(sub.data.secret, body)}
        for attempt in range(self.attempts):
            try:
                res = await self.http.post(sub.data.url, content=body,
                                           headers=headers)
                if res.status_code < 400:
                    return True
            except Exception:
                pass
            await asyncio.sleep(self.backoff_seconds * (2 ** attempt))
        return False
