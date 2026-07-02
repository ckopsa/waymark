"""The agent client's Part IV contract, against the real example app."""
import uuid

import pytest
from httpx import ASGITransport, AsyncClient

import waymark
from waymark.client import (
    AffordanceError,
    AgentClient,
    Divergence,
    PendingConfirmation,
    mcp_tools,
)
from waymark.server.engine import header_principal

from app.resources.order import Order
from app.services import VALID_METHOD, Services

from ..server.test_router import ORDER, TEST_DSN

OWNER = {"X-Principal-Id": "dana", "X-Principal-Display": "Dana K."}
MANAGER = {"X-Principal-Id": "boss", "X-Principal-Roles": "manager"}


@pytest.fixture
async def env():
    from fastapi import FastAPI

    engine = waymark.Engine(resources=[Order], storage=TEST_DSN,
                            principal=header_principal, services=Services())
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    await engine.storage.drop_all()
    await engine.startup()
    transport = ASGITransport(app=app)

    def agent_for(headers) -> AgentClient:
        return AgentClient(http=AsyncClient(transport=transport,
                                            base_url="http://test"),
                           headers=headers)

    try:
        yield engine, agent_for
    finally:
        await transport.aclose()
        await engine.shutdown()


async def make_order_doc(engine) -> str:
    created = await engine.invoker.create(
        "order", ORDER, principal=waymark.Principal(id="dana"),
        idempotency_key=uuid.uuid4().hex)
    return created.doc["self"]


async def test_agent_never_constructs_urls(env):
    engine, agent_for = env
    agent = agent_for(OWNER)
    doc = await agent.fetch(await make_order_doc(engine))
    with pytest.raises(AffordanceError, match="does not afford 'refund'"):
        await agent.act(doc, "refund", {})


async def test_confirm_is_a_hard_stop_until_confirmed(env):
    engine, agent_for = env
    agent = agent_for(OWNER)
    doc = await agent.fetch(await make_order_doc(engine))

    pending = await agent.act(doc, "cancel", {"reason": "changed mind"})
    assert isinstance(pending, PendingConfirmation)
    assert doc.state == "draft"  # nothing happened
    assert "cancel → cancelled (terminal)" in pending.summary

    after = await pending.confirm()
    assert after.state == "cancelled"


async def test_auto_idempotency_key_and_stable_retry(env):
    engine, agent_for = env
    agent = agent_for(MANAGER)
    href = await make_order_doc(engine)
    doc = await agent.fetch(href)
    doc = await agent.act(doc, "place")
    pay = await agent.act(doc, "submit_payment",
                          {"payment_method_id": str(VALID_METHOD)})
    assert pay.state == "paid"

    pending = await agent.act(pay, "refund", {"reason": "test"})
    first = await pending.confirm()
    assert first.state == "paid"
    # the key was persisted; a second confirm replays instead of double-refunding
    again = await agent.act(pay, "refund", {"reason": "test"}, confirmed=True)
    assert engine.services.payments.refunds.count(
        href.rsplit("/", 1)[-1]) == 1
    assert len(agent.key_store) == 1


async def test_if_match_supplied_automatically(env):
    engine, agent_for = env
    agent = agent_for(OWNER)
    doc = await agent.fetch(await make_order_doc(engine))
    placed = await agent.act(doc, "place")
    paid = await agent.act(placed, "submit_payment",
                           {"payment_method_id": str(VALID_METHOD)})
    assert paid.state == "paid"


async def test_dry_run_prevalidates(env):
    engine, agent_for = env
    agent = agent_for(OWNER)
    doc = await agent.fetch(await make_order_doc(engine))
    placed = await agent.act(doc, "place")
    ok, _ = await agent.dry_run(placed, "submit_payment",
                                {"payment_method_id": str(VALID_METHOD)})
    assert ok is True
    ok, problem = await agent.dry_run(placed, "submit_payment",
                                      {"payment_method_id": "garbage"})
    assert ok is False and problem.status == 422
    assert placed.state == "awaiting_payment"  # side-effect free


async def test_plan_over_learned_effect_graph(env):
    engine, agent_for = env
    agent = agent_for(OWNER)
    doc = await agent.fetch(await make_order_doc(engine))
    # from the draft doc alone the agent knows draft →place→ awaiting_payment
    assert agent.plan(doc, "awaiting_payment") == ["place"]
    placed = await agent.act(doc, "place")
    # having seen both states, it can route draft → paid
    assert agent.plan(doc, "paid") == ["place", "submit_payment"]
    assert agent.plan(placed, "draft") == ["edit"]


async def test_divergence_is_surfaced_not_improvised(env, monkeypatch):
    engine, agent_for = env
    agent = agent_for(OWNER)
    doc = await agent.fetch(await make_order_doc(engine))
    entry = doc.actions["place"]
    entry["effect"]["to"] = "paid"  # a lying (or stale) advertisement
    with pytest.raises(Divergence, match="declared effect.to='paid'"):
        await agent.act(doc, "place")


async def test_mcp_tool_projection(env):
    engine, agent_for = env
    agent = agent_for(OWNER)
    doc = await agent.fetch(await make_order_doc(engine))
    tools = {t["name"]: t for t in mcp_tools(doc)}
    assert set(tools) == {"order.place", "order.cancel"}
    assert tools["order.place"]["description"] == "Place order"
    assert "requires human confirmation" in tools["order.cancel"]["description"]
    reason_schema = tools["order.cancel"]["input_schema"]["properties"]["reason"]
    assert {"maxLength": 500, "type": "string"} in reason_schema["anyOf"]
    # tools are the current affordances, not the full machine
    cancelled = await (await agent.act(doc, "cancel", {})).confirm()
    assert mcp_tools(cancelled) == []
