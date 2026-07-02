"""Workflows (§14): engine-mediated cascades, one correlation_id, one txn."""
import uuid

import pytest
from sqlalchemy import select

import waymark
from waymark.server.problems import GuardRefused

from app.resources.order import Order, OrderState
from app.resources.return_workflow import Return
from app.resources.shipment import Shipment
from app.services import Services, mint_method

from .test_router import TEST_DSN

OWNER = waymark.Principal(id="owner", display="Owner")
MANAGER = waymark.Principal(id="boss", roles=frozenset(["manager"]),
                            display="Boss")

ORDER_BODY = {"items": [{"sku": "A", "qty": 1, "price": 10.0}],
              "total": 10.0, "currency": "USD"}


@pytest.fixture
async def engine():
    eng = waymark.Engine(resources=[Order, Shipment, Return],
                         storage=TEST_DSN, services=Services())
    await eng.storage.drop_all()
    await eng.startup()
    try:
        yield eng
    finally:
        await eng.shutdown()


async def make_paid_order(engine) -> str:
    inv = engine.invoker
    created = await inv.create("order", ORDER_BODY, principal=OWNER,
                               idempotency_key=uuid.uuid4().hex)
    oid = created.doc["self"].rsplit("/", 1)[-1]
    placed = await inv.invoke("order", oid, "place", None, principal=OWNER)
    await inv.invoke("order", oid, "submit_payment",
                     {"payment_method_id": str(mint_method(engine.services))},
                     principal=OWNER, if_match=placed.doc["meta"]["etag"])
    return oid


async def load(engine, kind, id):
    async with engine.storage.session() as s:
        return await engine.storage.load(s, kind, id)


async def test_approve_cascades_refund_under_one_correlation(engine):
    inv = engine.invoker
    oid = await make_paid_order(engine)
    created = await inv.create("return", {"order_id": oid}, principal=OWNER,
                               idempotency_key=uuid.uuid4().hex)
    rid = created.doc["self"].rsplit("/", 1)[-1]
    await inv.invoke("return", rid, "receive_item", None, principal=OWNER)

    res = await inv.invoke("return", rid, "approve",
                           {"condition_ok": True, "notes": "mint"},
                           principal=MANAGER,
                           idempotency_key=uuid.uuid4().hex)
    assert res.doc["state"] == "refunding"
    # the child refund really ran
    assert engine.services.payments.refunds == [oid]

    async with engine.storage.session() as s:
        t = engine.storage.transitions
        rows = (await s.execute(
            select(t.c.kind, t.c.action, t.c.correlation_id)
            .where(t.c.action.in_(["approve", "refund"]))
            .order_by(t.c.id))).all()
    assert [(r[0], r[1]) for r in rows] == [("order", "refund"),
                                            ("return", "approve")]
    assert len({r[2] for r in rows}) == 1, \
        "workflow cascade must share one correlation_id"


async def test_workflow_cascade_is_atomic(engine):
    """If the child transition refuses, the workflow transition rolls back."""
    inv = engine.invoker
    oid = await make_paid_order(engine)
    # push the order out of refundable state behind the workflow's back
    await inv.invoke("order", oid, "fulfil", None, principal=OWNER)

    created = await inv.create("return", {"order_id": oid}, principal=OWNER,
                               idempotency_key=uuid.uuid4().hex)
    rid = created.doc["self"].rsplit("/", 1)[-1]
    await inv.invoke("return", rid, "receive_item", None, principal=OWNER)

    with pytest.raises(GuardRefused) as exc:
        await inv.invoke("return", rid, "approve",
                         {"condition_ok": True}, principal=MANAGER,
                         idempotency_key=uuid.uuid4().hex)
    # the cross-resource guard names the actual order state
    assert exc.value.detail == \
        "Order must be in state 'paid'. It is in state 'fulfilled'."

    ret = await load(engine, "return", rid)
    assert ret.state == "inspecting"  # nothing moved
    assert engine.services.payments.refunds == []


async def test_shipment_deliver_fulfils_order(engine):
    inv = engine.invoker
    oid = await make_paid_order(engine)
    created = await inv.create("shipment", {"order_id": oid}, principal=OWNER,
                               idempotency_key=uuid.uuid4().hex)
    sid = created.doc["self"].rsplit("/", 1)[-1]
    await inv.invoke("shipment", sid, "ship", None, principal=OWNER)
    res = await inv.invoke("shipment", sid, "deliver", None, principal=OWNER)
    assert res.doc["state"] == "delivered"

    order = await load(engine, "order", oid)
    assert order.state == "fulfilled"

    async with engine.storage.session() as s:
        t = engine.storage.transitions
        rows = (await s.execute(
            select(t.c.correlation_id)
            .where(t.c.action.in_(["deliver", "fulfil"])))).scalars().all()
    assert len(rows) == 2 and len(set(rows)) == 1


async def test_cross_resource_link_rendered(engine):
    oid = await make_paid_order(engine)
    inv = engine.invoker
    created = await inv.create("shipment", {"order_id": oid}, principal=OWNER,
                               idempotency_key=uuid.uuid4().hex)
    doc = created.doc
    assert doc["links"]["order"]["href"] == f"/api/orders/{oid}"
    assert doc["links"]["order"]["kind"] == "order"