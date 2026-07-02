"""Root conftest: what an application supplies to `pytest --waymark` (Part III)
— the engine fixture, one state factory per resource, and example inputs where
schema generation can't satisfy semantic guards.
"""
from __future__ import annotations

import os
import uuid

import pytest

import waymark
from waymark import Principal
from waymark.testing import example_input, state_factory

from app.resources.order import Order, OrderState
from app.resources.return_workflow import Return, ReturnState
from app.resources.shipment import Shipment, ShipmentState
from app.services import Services, mint_method

TEST_DSN = os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test")

ORDER_BODY = {
    "items": [{"sku": "A-100", "qty": 2, "price": 12.10}],
    "total": 84.20,
    "currency": "USD",
}

FACTORY_PRINCIPAL = Principal(id="owner", type="human", display="Owner")


@pytest.fixture
async def waymark_engine():
    engine = waymark.Engine(resources=[Order, Shipment, Return],
                            storage=TEST_DSN, services=Services())
    await engine.storage.drop_all()
    await engine.startup()
    try:
        yield engine
    finally:
        await engine.shutdown()


async def _load(engine, kind: str, id: str):
    async with engine.storage.session() as s:
        return await engine.storage.load(s, kind, id)


@state_factory(Order)
async def make_order(state: str, engine, services) -> Order:
    """Walk real transitions to the requested state (the honest route)."""
    inv = engine.invoker
    created = await inv.create("order", ORDER_BODY,
                               principal=FACTORY_PRINCIPAL,
                               idempotency_key=uuid.uuid4().hex)
    oid = created.doc["self"].rsplit("/", 1)[-1]

    async def step(action: str, body=None, *, principal=FACTORY_PRINCIPAL,
                   etag: str | None = None):
        return await inv.invoke("order", oid, action, body,
                                principal=principal, if_match=etag,
                                idempotency_key=uuid.uuid4().hex)

    if state == OrderState.DRAFT:
        return await _load(engine, "order", oid)
    if state == OrderState.CANCELLED:
        await step("cancel", {"reason": "conformance"})
        return await _load(engine, "order", oid)

    placed = await step("place")
    if state == OrderState.AWAITING_PAYMENT:
        return await _load(engine, "order", oid)

    await step("submit_payment",
               {"payment_method_id": str(mint_method(services))},
               etag=placed.doc["meta"]["etag"])
    if state == OrderState.PAID:
        return await _load(engine, "order", oid)

    await step("fulfil")
    if state == OrderState.FULFILLED:
        return await _load(engine, "order", oid)

    raise waymark.testing.SkipState(state)  # pragma: no cover


@example_input(Order, "submit_payment")
def submit_payment_example(services) -> dict:
    # a schema-generated UUID can never name a real payment method
    return {"payment_method_id": str(mint_method(services))}


MANAGER = Principal(id="manager", type="human", roles=frozenset(["manager"]),
                    display="Manager")


@state_factory(Shipment)
async def make_shipment(state: str, engine, services) -> Shipment:
    order = await make_order(OrderState.PAID, engine, services)
    inv = engine.invoker
    created = await inv.create("shipment", {"order_id": order.id},
                               principal=FACTORY_PRINCIPAL,
                               idempotency_key=uuid.uuid4().hex)
    sid = created.doc["self"].rsplit("/", 1)[-1]
    steps = {ShipmentState.PENDING: [], ShipmentState.SHIPPED: ["ship"],
             ShipmentState.DELIVERED: ["ship", "deliver"]}[ShipmentState(state)]
    for action in steps:
        await inv.invoke("shipment", sid, action, None,
                         principal=FACTORY_PRINCIPAL)
    return await _load(engine, "shipment", sid)


@state_factory(Return)
async def make_return(state: str, engine, services) -> Return:
    order = await make_order(OrderState.PAID, engine, services)
    inv = engine.invoker
    created = await inv.create("return", {"order_id": order.id,
                                          "reason": "changed my mind"},
                               principal=FACTORY_PRINCIPAL,
                               idempotency_key=uuid.uuid4().hex)
    rid = created.doc["self"].rsplit("/", 1)[-1]

    async def step(action, body=None, principal=FACTORY_PRINCIPAL):
        await inv.invoke("return", rid, action, body, principal=principal,
                         idempotency_key=uuid.uuid4().hex)

    target = ReturnState(state)
    if target != ReturnState.AWAITING_ITEM:
        await step("receive_item")
    if target == ReturnState.REJECTED:
        await step("reject", {"condition_ok": False, "notes": "damaged"})
    if target in (ReturnState.REFUNDING, ReturnState.DONE):
        await step("approve", {"condition_ok": True, "notes": "like new"},
                   principal=MANAGER)
    if target == ReturnState.DONE:
        await step("complete")
    return await _load(engine, "return", rid)


@example_input(Return, "approve")
def approve_example(services) -> dict:
    return {"condition_ok": True, "notes": "like new"}


@example_input(Return, "reject")
def reject_example(services) -> dict:
    return {"condition_ok": False, "notes": "damaged"}
