"""The world outside the envelope (design §10): declared services that
un-advertise instead of 500ing, mirrors with honest sync states, and the
outbox exposed as signed webhooks.
"""
from __future__ import annotations

import asyncio
import json
import os
import uuid
from datetime import datetime

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient, MockTransport, Response
from pydantic import BaseModel, Field

import waymark3
from waymark3 import Ctx, Safety, action
from waymark3.server.bus import InProcessBus
from waymark3.server.engine import header_principal
from waymark3.server.external import (
    ExternalConflict,
    Mirror,
    MirrorMeta,
    Service,
    SyncState,
    service_up,
)
from waymark3.server.subscriptions import WebhookDeliverer, sign
from waymark3.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

OWNER = {"X-Principal-Id": "dana", "X-Principal-Display": "Dana"}


# ── a fake household todo list (the external truth) ─────────────────────
class FakeTodoList:
    def __init__(self):
        self.docs: dict[str, dict] = {}
        self.etags: dict[str, str] = {}
        self.down = False
        self.pushes = 0

    def seed(self, id: str, doc: dict) -> None:
        self.docs[id] = doc
        self.etags[id] = uuid.uuid4().hex

    async def pull(self, external_id: str):
        if self.down:
            raise ConnectionError("todo list unreachable")
        return dict(self.docs[external_id]), self.etags[external_id]

    async def push(self, external_id: str, document: dict, *, etag):
        if self.down:
            raise ConnectionError("todo list unreachable")
        if etag != self.etags[external_id]:
            raise ExternalConflict(dict(self.docs[external_id]),
                                   self.etags[external_id])
        self.docs[external_id] = dict(document)
        self.etags[external_id] = uuid.uuid4().hex
        self.pushes += 1
        return self.etags[external_id]


TODO = FakeTodoList()


class GroceryMirrorData(MirrorMeta):
    items: list[str] = Field(default_factory=list)


class ItemInput(BaseModel):
    name: str = Field(min_length=1, max_length=80)


class GroceryMirror(Mirror):
    kind = "grocery_mirror"
    Data = GroceryMirrorData
    adapter = TODO
    ttl_seconds = 300

    summary = "Groceries · {state.label}"

    @action(from_=SyncState.FRESH, to=SyncState.FRESH, input=ItemInput,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Add item"))
    async def add_item(self, inp: ItemInput, ctx: Ctx) -> None:
        if inp.name not in self.data.items:
            self.data.items.append(inp.name)


@pytest.fixture
async def env():
    TODO.docs.clear()
    TODO.etags.clear()
    TODO.down = False
    TODO.pushes = 0
    engine = waymark3.Engine(resources=[GroceryMirror], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t", headers=OWNER)
    try:
        yield engine, client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _mirror(client) -> str:
    TODO.seed("list-1", {"items": ["milk"]})
    res = await client.post(
        "/api/grocery_mirrors",
        json={"external_id": "list-1", "items": ["milk"]},
        headers={**OWNER, "Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    return res.json()["self"]


async def test_external_change_arrives_as_system_transition(env):
    engine, client = env
    href = await _mirror(client)

    # someone checks an item off at the store (out of band)
    TODO.docs["list-1"]["items"] = ["milk", "tortillas"]
    TODO.etags["list-1"] = uuid.uuid4().hex

    doc = (await client.get(href)).json()  # TTL: synced_at is unset → pull
    assert doc["data"]["items"] == ["milk", "tortillas"]
    assert doc["state"] == "fresh"
    assert doc["data"]["synced_at"] is not None

    # the observed change is an audited transition by a system actor
    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(
            s, "grocery_mirror", href.rsplit("/", 1)[-1])
    assert last.action == "observe_external"
    assert last.actor_type == "system" and last.actor_id == "mirror-sync"


async def test_unreachable_is_a_state_not_an_error(env):
    engine, client = env
    href = await _mirror(client)
    TODO.down = True

    res = await client.get(href)
    assert res.status_code == 200, "stored truth keeps serving"
    doc = res.json()
    assert doc["state"] == "unreachable"
    assert doc["data"]["items"] == ["milk"]

    # recovery: next read observes and returns to fresh
    TODO.down = False
    doc = (await client.get(href)).json()
    assert doc["state"] == "fresh"


async def test_local_write_pushes_and_conflict_is_a_state(env):
    engine, client = env
    href = await _mirror(client)
    (await client.get(href)).json()  # sync up

    res = await client.post(f"{href}/-/add_item", json={"name": "salsa"},
                            headers={**OWNER,
                                     "Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 200, res.text
    assert TODO.pushes == 1
    assert "salsa" in TODO.docs["list-1"]["items"]

    # now the outside world changes between our read and our write
    doc = (await client.get(href)).json()
    TODO.docs["list-1"]["items"] = ["milk", "salsa", "eggs"]
    TODO.etags["list-1"] = uuid.uuid4().hex
    res = await client.post(f"{href}/-/add_item", json={"name": "queso"},
                            headers={**OWNER,
                                     "Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 200
    doc = (await client.get(href)).json()
    assert doc["state"] == "conflicted"
    assert doc["data"]["theirs"]["items"] == ["milk", "salsa", "eggs"]
    assert "reconcile" in doc["actions"], "a person picks the winner"

    # reconcile keeping theirs adopts the external truth
    res = await client.post(f"{href}/-/reconcile", json={"keep": "theirs"},
                            headers={**OWNER,
                                     "Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 200, res.text
    doc = res.json()
    assert doc["state"] == "fresh"
    assert doc["data"]["items"] == ["milk", "salsa", "eggs"]


async def test_service_down_unadvertises_with_retry_at():
    from waymark3.core.types import Allow

    calls = {"n": 0}

    async def flaky():
        calls["n"] += 1
        raise ConnectionError("nope")

    svc = Service("calendar", flaky, backoff_seconds=60)
    guard = service_up(svc)
    now = datetime.now(__import__("datetime").UTC)

    ctx = waymark3.Ctx(principal=waymark3.Principal(id="dana"), now=now)
    verdict, _ = await guard.evaluate(None, None, ctx)
    assert isinstance(verdict, Allow), "up until proven down"

    with pytest.raises(Exception):
        await svc.call(now=now)
    verdict, _ = await guard.evaluate(None, None, ctx)
    assert verdict.retry_at == svc.down_until, \
        "down renders retry_at, not a 500"


async def test_webhooks_deliver_signed_transitions(env):
    engine, client = env
    received: list[tuple[bytes, str]] = []

    def handler(request):
        received.append((request.content,
                         request.headers["X-Waymark-Signature"]))
        return Response(200)

    # swap the deliverer's http client for a capture transport
    engine.webhooks.http = AsyncClient(transport=MockTransport(handler))

    res = await client.post(
        "/api/subscriptions",
        json={"url": "https://budget.app/hooks", "kinds": ["grocery_mirror"]},
        headers={**OWNER, "Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    secret = res.json()["data"]["secret"]

    href = await _mirror(client)
    for _ in range(20):
        if received:
            break
        await asyncio.sleep(0.1)
    assert received, "the create transition was delivered"
    body, signature = received[0]
    assert signature == sign(secret, body)
    event = json.loads(body)
    assert event["kind"] == "grocery_mirror"
    assert event["action"] == "create"
    assert event["class"] == "transition"


async def test_webhook_replay_across_restart(env):
    """At-least-once off the log (design §10): a subscription's cursor
    resumes across a deliverer outage — events during the gap replay
    instead of dropping, and a drained cursor doesn't re-deliver."""
    from waymark3.server.subscriptions import WebhookDeliverer

    engine, client = env
    await engine.webhooks.stop()  # the outage

    res = await client.post(
        "/api/subscriptions",
        json={"url": "https://budget.app/hooks", "kinds": ["grocery_mirror"]},
        headers={**OWNER, "Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201
    href = await _mirror(client)  # a create transition nobody delivered

    received: list[dict] = []

    def handler(request):
        received.append(json.loads(request.content))
        return Response(200)

    # the restart: a fresh deliverer picks up from the stored cursor
    restarted = WebhookDeliverer(
        engine, http=AsyncClient(transport=MockTransport(handler)),
        backoff_seconds=0)
    await restarted.drain()
    assert [e["action"] for e in received] == ["create"]
    assert received[0]["kind"] == "grocery_mirror"
    assert received[0]["self"] == href

    # cursor advanced: a second drain has nothing to say
    await restarted.drain()
    assert len(received) == 1
    await restarted.stop()
    engine.webhooks = None  # already stopped; engine.shutdown must not re-stop
