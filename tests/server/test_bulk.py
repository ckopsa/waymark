"""Bulk actions (§5, §7.4): partial-success reports, atomicity, deferred jobs."""
import asyncio
import uuid

import pytest
from httpx import ASGITransport, AsyncClient

import waymark
from waymark.server.engine import header_principal

from app.resources.order import Order
from app.services import Services

from .test_router import ORDER, OWNER, TEST_DSN


@pytest.fixture
async def client_env():
    from fastapi import FastAPI

    engine = waymark.Engine(resources=[Order], storage=TEST_DSN,
                            principal=header_principal, services=Services())
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    await engine.storage.drop_all()
    await engine.startup()
    transport = ASGITransport(app=app)
    client = AsyncClient(transport=transport, base_url="http://test")
    try:
        yield engine, client
    finally:
        await client.aclose()
        await transport.aclose()
        await engine.shutdown()


async def make_orders(client, n: int, *, place: set[int] = frozenset(),
                      pay: set[int] = frozenset()) -> list[str]:
    from app.services import VALID_METHOD

    ids = []
    for i in range(n):
        doc = (await client.post(
            "/api/orders", json=ORDER,
            headers={**OWNER, "Idempotency-Key": uuid.uuid4().hex})).json()
        oid = doc["self"].rsplit("/", 1)[-1]
        ids.append(oid)
        if i in place or i in pay:
            placed = (await client.post(doc["self"] + "/-/place",
                                        headers=OWNER)).json()
            if i in pay:
                res = await client.post(
                    doc["self"] + "/-/submit_payment",
                    json={"payment_method_id": str(VALID_METHOD)},
                    headers={**OWNER, "If-Match": placed["meta"]["etag"]})
                assert res.status_code == 200
    return ids


async def test_bulk_partial_success_report(client_env):
    engine, client = client_env
    ids = await make_orders(client, 4, pay={3})  # 3 cancellable, 1 paid

    res = await client.post("/api/orders/-/cancel_many",
                            json={"ids": ids, "reason": "cleanup"},
                            headers=OWNER)
    assert res.status_code == 200
    report = res.json()
    assert report["kind"] == "bulk_report"
    assert report["action"] == "cancel_many"
    assert report["data"]["succeeded"] == 3
    assert report["data"]["refused"] == 1
    assert report["data"]["failed"] == 0
    refusal = report["data"]["refusals"][0]
    assert refusal["self"].endswith(ids[3])
    assert "paid" in refusal["reason"]  # the guard's honest reason
    assert report["links"]["job"] is None

    # per-item transitions really happened, under one correlation id
    from sqlalchemy import select

    async with engine.storage.session() as s:
        rows = (await s.execute(
            select(engine.storage.transitions.c.correlation_id,
                   engine.storage.transitions.c.action)
            .where(engine.storage.transitions.c.action == "cancel_many"))).all()
    assert len(rows) == 3
    assert len({r[0] for r in rows}) == 1


async def test_bulk_advertised_on_collection_not_resource(client_env):
    engine, client = client_env
    ids = await make_orders(client, 1)
    collection = (await client.get("/api/orders", headers=OWNER)).json()
    entry = collection["actions"]["cancel_many"]
    assert entry["href"] == "/api/orders/-/cancel_many"
    assert entry["effect"]["bulk"] is True
    assert entry["input"]["properties"]["ids"]["maxItems"] == 500
    resource = (await client.get(f"/api/orders/{ids[0]}", headers=OWNER)).json()
    assert "cancel_many" not in resource["actions"]
    assert "cancel_many" not in resource["unavailable"]
    # direct per-resource invocation of a bulk action is not a thing
    res = await client.post(f"/api/orders/{ids[0]}/-/cancel_many", json={},
                            headers=OWNER)
    assert res.status_code == 404


async def test_bulk_requires_ids(client_env):
    engine, client = client_env
    res = await client.post("/api/orders/-/cancel_many", json={},
                            headers=OWNER)
    assert res.status_code == 422
    assert "ids" in res.json()["errors"]


async def test_bulk_unknown_action_404(client_env):
    engine, client = client_env
    res = await client.post("/api/orders/-/nope", json={"ids": ["x"]},
                            headers=OWNER)
    assert res.status_code == 404


async def test_deferred_bulk_returns_job_and_completes(client_env):
    engine, client = client_env
    ids = await make_orders(client, 3)
    # push past defer_over by repeating ids (idempotent cancels replay cleanly)
    many = (ids * 20)[:60]

    res = await client.post("/api/orders/-/cancel_many",
                            json={"ids": many}, headers=OWNER)
    assert res.status_code == 202
    job = res.json()
    assert job["kind"] == "job"
    assert job["data"]["total"] == 60
    assert job["state"] in ("queued", "running")
    assert "cancel" in {**job["actions"], **job["unavailable"]}

    for _ in range(100):
        current = (await client.get(job["self"], headers=OWNER)).json()
        if current["state"] == "done":
            break
        await asyncio.sleep(0.1)
    assert current["state"] == "done"
    assert current["data"]["processed"] == 60
    assert current["data"]["succeeded"] == 60  # replays count as success
    assert current["actions"] == {}


async def test_job_cancellation_stops_processing(client_env):
    engine, client = client_env
    ids = await make_orders(client, 2)
    many = (ids * 40)[:80]
    res = await client.post("/api/orders/-/cancel_many",
                            json={"ids": many}, headers=OWNER)
    assert res.status_code == 202
    job_href = res.json()["self"]

    cancel = await client.post(job_href + "/-/cancel", headers=OWNER)
    assert cancel.status_code == 200
    assert cancel.json()["state"] == "cancelled"
    await asyncio.sleep(0.3)
    final = (await client.get(job_href, headers=OWNER)).json()
    assert final["state"] == "cancelled"
    assert final["data"]["processed"] < 80
