"""End-to-end HTTP tests over the generated routes, using the example app."""
import os
import uuid

import pytest
from httpx import ASGITransport, AsyncClient

import waymark
from waymark.server.engine import header_principal

from app.resources.order import Order
from app.services import VALID_METHOD, Services

TEST_DSN = os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test")

ORDER = {
    "items": [{"sku": "A-100", "qty": 2, "price": 12.10}],
    "total": 84.20,
    "currency": "USD",
}

OWNER = {"X-Principal-Id": "dana", "X-Principal-Display": "Dana K."}
MANAGER = {"X-Principal-Id": "boss", "X-Principal-Roles": "manager"}


def key() -> dict:
    return {"Idempotency-Key": uuid.uuid4().hex}


@pytest.fixture
async def client():
    from fastapi import FastAPI

    engine = waymark.Engine(resources=[Order], storage=TEST_DSN,
                            principal=header_principal, services=Services())
    app = FastAPI(lifespan=engine.lifespan)
    app.include_router(engine.router, prefix="/api")
    await engine.storage.drop_all()
    async with ASGITransport(app=app) as transport:
        await engine.startup()
        try:
            yield AsyncClient(transport=transport, base_url="http://test")
        finally:
            await engine.shutdown()


async def create_order(client, headers=OWNER) -> dict:
    res = await client.post("/api/orders", json=ORDER, headers={**headers, **key()})
    assert res.status_code == 201, res.text
    return res.json()


async def test_wellknown_index(client):
    res = await client.get("/api/.well-known/waymark")
    assert res.status_code == 200
    doc = res.json()
    assert doc["waymark"] == "1"
    assert doc["collections"]["order"] == "/api/orders"


async def test_schemas_endpoint_serves_canonical_bytes(client):
    res = await client.get("/api/schemas/SubmitPayment")
    assert res.status_code == 200
    assert res.headers["content-type"].startswith("application/schema+json")
    assert res.json()["required"] == ["payment_method_id"]
    missing = await client.get("/api/schemas/Nope")
    assert missing.status_code == 404
    assert missing.headers["content-type"].startswith("application/problem+json")


async def test_create_and_get_resource(client):
    doc = await create_order(client)
    assert doc["state"] == "draft"
    res = await client.get(doc["self"], headers=OWNER)
    assert res.status_code == 200
    assert res.headers["content-type"].startswith("application/waymark+json")
    assert res.headers["etag"] == doc["meta"]["etag"]
    fetched = res.json()
    assert fetched["summary"].startswith("Order #")
    assert "place" in fetched["actions"]
    assert "cancel" in fetched["actions"]
    assert fetched["actions"]["cancel"]["safety"]["confirm"] is True


async def test_create_without_idempotency_key_428(client):
    res = await client.post("/api/orders", json=ORDER, headers=OWNER)
    assert res.status_code == 428


async def test_unknown_collection_404_problem(client):
    res = await client.get("/api/frobs")
    assert res.status_code == 404
    assert res.json()["type"] == "https://waymark.dev/problems/not-found"


async def test_full_order_walk(client):
    doc = await create_order(client)
    oid = doc["self"]

    placed = await client.post(oid + "/-/place", headers=OWNER)
    assert placed.status_code == 200
    placed = placed.json()
    assert placed["state"] == "awaiting_payment"
    assert "submit_payment" in placed["actions"]
    assert "refund" in placed["unavailable"]

    pay = await client.post(
        oid + "/-/submit_payment",
        json={"payment_method_id": str(VALID_METHOD)},
        headers={**OWNER, "If-Match": placed["meta"]["etag"]})
    assert pay.status_code == 200, pay.text
    paid = pay.json()
    assert paid["state"] == "paid"
    assert paid["data"]["paid_at"] is not None

    # refund is confirm+manager gated; owner sees it unavailable with a reason
    assert paid["unavailable"]["refund"]["reason"] == "Requires manager approval."

    refund = await client.post(oid + "/-/refund", json={},
                               headers={**MANAGER, **key()})
    assert refund.status_code == 200
    assert refund.json()["state"] == "paid"

    done = await client.post(oid + "/-/fulfil", headers=OWNER)
    assert done.status_code == 200
    assert done.json()["state"] == "fulfilled"
    assert done.json()["actions"] == {}


async def test_stale_if_match_412_with_fresh_resource(client):
    doc = await create_order(client)
    oid = doc["self"]
    placed = (await client.post(oid + "/-/place", headers=OWNER)).json()
    res = await client.post(
        oid + "/-/submit_payment",
        json={"payment_method_id": str(VALID_METHOD)},
        headers={**OWNER, "If-Match": doc["meta"]["etag"]})  # stale (v1)
    assert res.status_code == 412
    problem = res.json()
    assert problem["resource"]["meta"]["etag"] == placed["meta"]["etag"]


async def test_guard_refusal_is_hypermedia(client):
    from app.services import EXPIRED_METHOD

    doc = await create_order(client)
    oid = doc["self"]
    placed = (await client.post(oid + "/-/place", headers=OWNER)).json()
    res = await client.post(
        oid + "/-/submit_payment",
        json={"payment_method_id": str(EXPIRED_METHOD)},
        headers={**OWNER, "If-Match": placed["meta"]["etag"]})
    assert res.status_code == 409
    problem = res.json()
    assert problem["type"] == "https://waymark.dev/problems/guard-failed"
    assert problem["detail"] == \
        "Payment method is invalid or expired. Update it, then retry."
    assert problem["errors"]["payment_method_id"] == ["Card expired 05/2026"]
    assert problem["remedies"] == ["customer.update_payment_method"]
    assert problem["resource"]["state"] == "awaiting_payment"


async def test_dry_run_side_effect_free(client):
    doc = await create_order(client)
    oid = doc["self"]
    placed = (await client.post(oid + "/-/place", headers=OWNER)).json()
    res = await client.post(
        oid + "/-/submit_payment?dry_run=1",
        json={"payment_method_id": str(VALID_METHOD)},
        headers={**OWNER, "If-Match": placed["meta"]["etag"]})
    assert res.status_code == 200
    assert res.json() == {"valid": True}
    after = (await client.get(oid, headers=OWNER)).json()
    assert after["state"] == "awaiting_payment"
    assert after["meta"]["version"] == placed["meta"]["version"]


async def test_collection_query_filter_sort_paginate(client):
    ids = []
    for i in range(3):
        doc = await create_order(client)
        ids.append(doc["self"])
    await client.post(ids[0] + "/-/place", headers=OWNER)

    res = await client.get("/api/orders?state=awaiting_payment", headers=OWNER)
    assert res.status_code == 200
    doc = res.json()
    assert doc["kind"] == "order_collection"
    assert doc["data"]["total"] == 1
    assert doc["data"]["items"][0]["state"] == "awaiting_payment"
    # per-row affordances are honest
    assert "submit_payment" in doc["data"]["items"][0]["actions"]
    # facets are live counts
    facets = doc["actions"]["query"]["input"]["properties"]["state"]["x-facets"]
    assert facets == {"draft": 2, "awaiting_payment": 1}

    res = await client.get("/api/orders?page[size]=2", headers=OWNER)
    body = res.json()
    assert len(body["data"]["items"]) == 2
    assert body["links"]["next"] is not None

    bad = await client.get("/api/orders?state=nonsense", headers=OWNER)
    assert bad.status_code == 422
    assert "state" in bad.json()["errors"]


async def test_generic_ui_served(client):
    res = await client.get("/api/-/ui")
    assert res.status_code == 200
    assert res.headers["content-type"].startswith("text/html")
    assert "Waymark" in res.text
    index = (await client.get("/api/.well-known/waymark")).json()
    assert index["ui"] == "/api/-/ui"


async def test_depth_degrades_with_header(client):
    doc = await create_order(client)
    res = await client.get(doc["self"] + "?depth=expanded:nope", headers=OWNER)
    assert res.status_code == 200
    assert res.headers["waymark-depth"] == "full"
