"""SSE endpoints end-to-end over a real uvicorn server (httpx's ASGITransport
buffers responses, so infinite streams need real sockets)."""
import asyncio
import json
import socket
import uuid

import pytest
import uvicorn
from httpx import AsyncClient

import waymark
from waymark.server.engine import header_principal

from app.resources.order import Order
from app.services import Services

from ..server.test_router import ORDER, OWNER, TEST_DSN


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture
async def env():
    from fastapi import FastAPI

    engine = waymark.Engine(resources=[Order], storage=TEST_DSN,
                            principal=header_principal, services=Services())
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


async def test_per_resource_stream_delivers_transition(env):
    engine, client = env
    created = (await client.post(
        "/api/orders", json=ORDER,
        headers={**OWNER, "Idempotency-Key": uuid.uuid4().hex})).json()
    self_href = created["self"]

    async def act():
        await asyncio.sleep(0.2)
        res = await client.post(self_href + "/-/place", headers=OWNER)
        assert res.status_code == 200

    async with client.stream("GET", self_href + "/-/events",
                             headers=OWNER, timeout=15) as response:
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/event-stream")
        task = asyncio.create_task(act())

        async def until_place():
            # a just-subscribed client may still see the earlier `create` —
            # at-least-once delivery; clients dedupe on (self, version)
            async for candidate in sse_events(response):
                if json.loads(candidate["data"])["action"] == "place":
                    return candidate

        event = await asyncio.wait_for(until_place(), timeout=10)
        await task

    assert event["event"] == "transition"
    payload = json.loads(event["data"])
    assert payload["self"] == self_href
    assert payload["from"] == "draft" and payload["to"] == "awaiting_payment"
    assert payload["actor"] == {"type": "human", "id": "dana",
                                "display": "Dana K."}
    assert payload["version"] == 2
    assert payload["summary"].startswith("Order #")


async def test_firehose_filters_by_kind_and_resumes(env):
    engine, client = env
    created = (await client.post(
        "/api/orders", json=ORDER,
        headers={**OWNER, "Idempotency-Key": uuid.uuid4().hex})).json()
    self_href = created["self"]
    await client.post(self_href + "/-/place", headers=OWNER)

    # Last-Event-ID: 0 → replay from the beginning of the log
    async with client.stream(
            "GET", "/api/-/events?kinds=order",
            headers={**OWNER, "Last-Event-ID": "0"}, timeout=15) as response:
        events = await asyncio.wait_for(read_sse_events(response, 2), timeout=10)
    actions = [json.loads(e["data"])["action"] for e in events]
    assert actions == ["create", "place"]
    assert [e["id"] for e in events] == ["1", "2"]

    # a kinds filter that matches nothing stays silent
    async with client.stream(
            "GET", "/api/-/events?kinds=shipment",
            headers={**OWNER, "Last-Event-ID": "0"}, timeout=15) as response:
        with pytest.raises(asyncio.TimeoutError):
            await asyncio.wait_for(read_sse_events(response, 1), timeout=1.0)
