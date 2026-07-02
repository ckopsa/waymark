"""Depth negotiation (§4.1): summary/full/expanded:profile."""
import uuid

import pytest
from httpx import ASGITransport, AsyncClient

import waymark
from waymark.server.engine import header_principal

from app.resources.order import Order
from app.resources.shipment import Shipment
from app.services import Services, mint_method

from .test_router import ORDER, OWNER, TEST_DSN


@pytest.fixture
async def client_env():
    from fastapi import FastAPI

    engine = waymark.Engine(resources=[Order, Shipment], storage=TEST_DSN,
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


async def make_shipment(engine) -> tuple[str, str]:
    inv = engine.invoker
    owner = waymark.Principal(id="dana", display="Dana K.")
    created = await inv.create("order", ORDER, principal=owner,
                               idempotency_key=uuid.uuid4().hex)
    oid = created.doc["self"].rsplit("/", 1)[-1]
    placed = await inv.invoke("order", oid, "place", None, principal=owner)
    await inv.invoke("order", oid, "submit_payment",
                     {"payment_method_id": str(mint_method(engine.services))},
                     principal=owner, if_match=placed.doc["meta"]["etag"])
    ship = await inv.create("shipment", {"order_id": oid}, principal=owner,
                            idempotency_key=uuid.uuid4().hex)
    return ship.doc["self"], oid


async def test_expanded_profile_embeds_target(client_env):
    engine, client = client_env
    self_href, oid = await make_shipment(engine)

    plain = (await client.get(self_href, headers=OWNER)).json()
    assert "embedded" not in plain["links"]["order"]

    res = await client.get(self_href + "?depth=expanded:with_order",
                           headers=OWNER)
    assert res.status_code == 200
    assert "waymark-depth" not in res.headers  # honored, not degraded
    doc = res.json()
    embedded = doc["links"]["order"]["embedded"]
    # a full envelope at the negotiated depth, affordances computed
    assert embedded["waymark"] == "1"
    assert embedded["kind"] == "order"
    assert embedded["self"] == f"/api/orders/{oid}"
    assert embedded["state"] == "paid"
    assert isinstance(embedded["actions"], dict)
    assert "display" not in embedded  # summary depth: presentation stripped
    # the link summary is upgraded to the live document's summary
    assert doc["links"]["order"]["summary"] == embedded["summary"]
    # embedding never replaces the href (§4)
    assert doc["links"]["order"]["href"] == f"/api/orders/{oid}"


async def test_summary_depth_strips_display(client_env):
    engine, client = client_env
    self_href, _ = await make_shipment(engine)
    full = (await client.get(self_href, headers=OWNER)).json()
    assert "display" in full
    summary = (await client.get(self_href + "?depth=summary",
                                headers=OWNER)).json()
    assert "display" not in summary
    assert summary["data"] == full["data"]
    assert summary["actions"].keys() == full["actions"].keys()


async def test_wellknown_lists_profiles(client_env):
    engine, client = client_env
    doc = (await client.get("/api/.well-known/waymark")).json()
    assert doc["profiles"]["shipment"] == ["with_order"]
