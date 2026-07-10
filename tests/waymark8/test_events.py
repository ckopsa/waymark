"""waymark8 SSE endpoints end-to-end over a real uvicorn server (httpx's
ASGITransport buffers responses, so infinite streams need real sockets —
the conformance suite subscribes to the dispatcher directly and can never
see a broken HTTP streaming shim; this test exists for exactly that gap).

Uses the engine's default bus (Postgres LISTEN/NOTIFY against the test
database), so the wake path is the production one, not the in-process stub.
"""
from __future__ import annotations

import asyncio
import json
import os
import socket
import uuid
from enum import StrEnum

import pytest
import uvicorn
from httpx import AsyncClient
from pydantic import BaseModel, Field

import waymark8
from waymark8 import Ctx, Resource, Safety, action
from waymark8.server.engine import header_principal
from waymark8.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

OWNER = {"X-Principal-Id": "dana", "X-Principal-Display": "Dana K."}


class TicketState(StrEnum):
    OPEN = "open"
    DONE = "done"


class TicketData(BaseModel):
    title: str = Field(min_length=1, max_length=120)


class Ticket(Resource):
    kind = "ticket"
    State = TicketState
    Data = TicketData

    initial = TicketState.OPEN
    terminal: set = set()

    summary = "{data.title} · {state.label}"

    @action(from_=TicketState.OPEN, to=TicketState.DONE,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Finish"))
    async def finish(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=TicketState.DONE, to=TicketState.OPEN,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Reopen"))
    async def reopen(self, inp: None, ctx: Ctx) -> None:
        pass


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture
async def env():
    from fastapi import FastAPI

    engine = waymark8.Engine(resources=[Ticket], storage=TEST_DSN,
                             principal=header_principal, services=None)
    await engine.storage.drop_all()

    app = FastAPI(lifespan=engine.lifespan)
    app.include_router(engine.router, prefix="/api")

    port = free_port()
    config = uvicorn.Config(app, host="127.0.0.1", port=port, log_level="error")
    server = uvicorn.Server(config)
    task = asyncio.create_task(server.serve())
    while not server.started:
        await asyncio.sleep(0.05)
    engine.dispatcher.poll_interval = 0.2

    client = AsyncClient(base_url=f"http://127.0.0.1:{port}")
    try:
        yield engine, client
    finally:
        await client.aclose()
        server.should_exit = True
        await task


async def sse_events(response):
    current = {}
    async for line in response.aiter_lines():
        line = line.strip()
        if not line:
            if "data" in current:
                yield current
            current = {}
        elif ":" in line:
            key, _, value = line.partition(":")
            current[key.strip()] = value.strip()


async def read_sse_events(response, n: int) -> list[dict]:
    events = []
    async for event in sse_events(response):
        events.append(event)
        if len(events) == n:
            break
    return events


async def _create(client) -> str:
    res = await client.post(
        "/api/tickets", json={"title": "Fix the fence"},
        headers={**OWNER, "Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    return res.json()["self"]


async def test_per_resource_stream_delivers_transition(env):
    engine, client = env
    self_href = await _create(client)

    async def act():
        await asyncio.sleep(0.2)
        res = await client.post(self_href + "/-/finish", headers=OWNER)
        assert res.status_code == 200

    async with client.stream("GET", self_href + "/-/events",
                             headers=OWNER, timeout=15) as response:
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/event-stream")
        task = asyncio.create_task(act())

        async def until_finish():
            # a just-subscribed client may still see the earlier `create` —
            # at-least-once delivery; clients dedupe on (self, version)
            async for candidate in sse_events(response):
                if json.loads(candidate["data"])["action"] == "finish":
                    return candidate

        event = await asyncio.wait_for(until_finish(), timeout=10)
        await task

    assert event["event"] == "transition"
    payload = json.loads(event["data"])
    # one taxonomy, on the wire (design 4.0 §3): every event names its
    # class — transitions included, not just observation/derivation
    assert payload["class"] == "transition"
    assert payload["self"] == self_href
    assert payload["from"] == "open" and payload["to"] == "done"
    assert payload["actor"] == {"type": "human", "id": "dana",
                                "display": "Dana K."}
    assert payload["version"] == 2
    assert payload["summary"].startswith("Fix the fence")


async def test_firehose_filters_by_kind_and_resumes(env):
    engine, client = env
    self_href = await _create(client)
    await client.post(self_href + "/-/finish", headers=OWNER)

    # Last-Event-ID: 0 → replay from the beginning of the log (the kinds
    # filter hides the boot's own definition revisions, design §2 — the
    # log ids are global, so the ticket's are merely ordered, not 1 and 2)
    async with client.stream(
            "GET", "/api/-/events?kinds=ticket",
            headers={**OWNER, "Last-Event-ID": "0"}, timeout=15) as response:
        events = await asyncio.wait_for(read_sse_events(response, 2), timeout=10)
    actions = [json.loads(e["data"])["action"] for e in events]
    assert actions == ["create", "finish"]
    ids = [int(e["id"]) for e in events]
    assert ids[0] < ids[1], "replayed events must keep log order"

    # a kinds filter that matches nothing stays silent
    async with client.stream(
            "GET", "/api/-/events?kinds=job",
            headers={**OWNER, "Last-Event-ID": "0"}, timeout=15) as response:
        with pytest.raises(asyncio.TimeoutError):
            await asyncio.wait_for(read_sse_events(response, 1), timeout=1.0)


async def test_presence_streams_navigation(env):
    """The presence stream is ephemeral liveness (design: supervision, not
    surveillance records): a principal's GETs appear as `viewed` events for
    followers, filtered by actor; nothing is stored or replayable."""
    engine, client = env
    self_href = await _create(client)

    async def navigate():
        await asyncio.sleep(0.2)
        # dana views; the anonymous rambler must be filtered out
        await client.get(self_href, headers={"X-Principal-Id": "rambler"})
        await client.get(self_href, headers=OWNER)

    async with client.stream("GET", "/api/-/presence?actor=dana",
                             headers=OWNER, timeout=15) as response:
        assert response.status_code == 200
        task = asyncio.create_task(navigate())
        events = await asyncio.wait_for(read_sse_events(response, 1),
                                        timeout=10)
        await task

    assert events[0]["event"] == "viewed"
    payload = json.loads(events[0]["data"])
    assert payload["self"] == self_href
    assert payload["actor"]["id"] == "dana"  # rambler's view was filtered
    assert "id" not in events[0], "presence events carry no resumable id"


async def test_presence_ignores_lookups(env):
    """Plumbing is a route class, not a query flag (design §3): ref-label
    and picker fetches ride ``/-/lookup/…``, which never emits
    observations — a follower's screen is not yanked toward every
    reference the page resolves, and there is no flag a client could
    forget to send."""
    engine, client = env
    self_href = await _create(client)
    tid = self_href.rsplit("/", 1)[-1]

    async def navigate():
        await asyncio.sleep(0.2)
        # lookups first — the label and the picker — then a real view
        await client.get(f"/api/-/lookup/tickets/{tid}?depth=summary",
                         headers=OWNER)
        await client.get("/api/-/lookup/tickets", headers=OWNER)
        await client.get(self_href, headers=OWNER)

    async with client.stream("GET", "/api/-/presence?actor=dana",
                             headers=OWNER, timeout=15) as response:
        assert response.status_code == 200
        task = asyncio.create_task(navigate())
        events = await asyncio.wait_for(read_sse_events(response, 1),
                                        timeout=10)
        await task

    # the first (and only) event is the real view; the lookups never streamed
    payload = json.loads(events[0]["data"])
    assert payload["self"] == self_href

    # v2's peek flag is retired, not silently ignored (design §7)
    res = await client.get("/api/tickets?peek=1", headers=OWNER)
    assert res.status_code == 422


async def test_presence_streams_form_engagement(env):
    """Form engagement is derived from wire facts the server already sees —
    a dry-run is 'someone is filling this form' — never from
    client-reported gestures. It arrives as an `engaged` event naming the
    action."""
    engine, client = env
    self_href = await _create(client)

    async def engage():
        await asyncio.sleep(0.2)
        res = await client.post(self_href + "/-/finish?dry_run=1",
                                headers=OWNER)
        assert res.status_code == 200

    async with client.stream("GET", "/api/-/presence?actor=dana",
                             headers=OWNER, timeout=15) as response:
        task = asyncio.create_task(engage())
        events = await asyncio.wait_for(read_sse_events(response, 1),
                                        timeout=10)
        await task

    assert events[0]["event"] == "engaged"
    payload = json.loads(events[0]["data"])
    assert payload["action"] == "finish" and payload["via"] == "dry_run"
    assert payload["self"] == self_href


async def test_rate_windows_ride_the_bus():
    """Two workers (two coordinators, one bus) enforce ONE limit (design
    §8): a hit on either folds into both windows — v2's per-process
    counter silently doubled every limit at two workers."""
    from datetime import UTC, datetime, timedelta

    from waymark8.server.bus import InProcessBus, RateCoordinator

    bus = InProcessBus()
    worker_a, worker_b = RateCoordinator(bus), RateCoordinator(bus)
    now = datetime.now(UTC)
    await worker_a.hit("rate:5/60s:dana", now)
    await worker_b.hit("rate:5/60s:dana", now)

    cutoff = now - timedelta(seconds=60)
    assert len(worker_a.window("rate:5/60s:dana", cutoff)) == 2
    assert len(worker_b.window("rate:5/60s:dana", cutoff)) == 2
    # other principals' budgets are untouched
    assert worker_a.window("rate:5/60s:colton", cutoff) == []
