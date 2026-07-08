"""Two-tier guards (design E1): a warning is a guard whose Deny a
principal may acknowledge past — advertised up front on the action entry,
refused with the override affordance in the problem, and recorded in the
transition log when overridden. One protocol, replacing the org's three
incompatible ``is_warning`` dialects.
"""
from __future__ import annotations

import os
import uuid
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark7
from waymark7 import Ctx, Resource, Safety, action
from waymark7.core.guards import Guard
from waymark7.core.types import Acknowledged, Allow, Deny
from waymark7.server.bus import InProcessBus
from waymark7.server.engine import header_principal
from waymark7.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

LIMIT = 1000


class WireState(StrEnum):
    DRAFT = "draft"
    SENT = "sent"


class WireData(BaseModel):
    amount: float = Field(gt=0)
    memo: str | None = Field(default=None, max_length=120)


async def _check_amount(r, inp, ctx: Ctx):
    if r.data.amount > LIMIT:
        return Deny(vars={"amount": r.data.amount})
    return Allow()

unusually_large = Guard(
    name="unusually_large", severity="warning",
    explain="{amount} is over the {limit} review threshold — proceed only "
            "if this amount is expected.",
    vars=("amount", "limit"), vars_fn=lambda r: {"limit": LIMIT},
    check=_check_amount,
)


async def _check_memo(r, inp, ctx: Ctx):
    if r.data.memo:
        return Allow()
    return Deny()

memo_required = Guard(
    name="memo_required",
    explain="A wire needs a memo before it can be sent.",
    check=_check_memo,
)


class Wire(Resource):
    kind = "wire"
    State = WireState
    Data = WireData
    initial = WireState.DRAFT
    terminal = {WireState.SENT}
    summary = "Wire of {data.amount} · {state.label}"

    @action(from_=WireState.DRAFT, to=WireState.SENT,
            guards=[memo_required, unusually_large],
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Sending is the point; the log records it.")),
            display=dict(label="Send"))
    async def send(self, inp: None, ctx: Ctx) -> None:
        pass


@pytest.fixture
async def env():
    engine = waymark7.Engine(resources=[Wire], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    transport = ASGITransport(app=app)
    client = AsyncClient(transport=transport, base_url="http://t",
                         headers={"X-Principal-Id": "priya",
                                  "X-Principal-Display": "Priya"})
    try:
        yield engine, client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _wire(client, amount, memo="rent") -> dict:
    res = await client.post("/api/wires",
                            json={"amount": amount, "memo": memo},
                            headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    return res.json()


async def test_warning_advertises_refuses_and_overrides(env):
    """The three faces of one declaration: the entry warns up front, the
    bare invoke 409s with the override affordance, and the acknowledged
    invoke proceeds with the override recorded in the log."""
    engine, client = env
    doc = await _wire(client, 5000)
    entry = doc["actions"]["send"]
    assert entry["warnings"] == [
        {"name": "unusually_large",
         "reason": "5000.0 is over the 1000 review threshold — proceed only "
                   "if this amount is expected."}]

    res = await client.post(f"{doc['self']}/-/send",
                            headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 409, res.text
    problem = res.json()
    assert problem["type"].endswith("warning-required")
    assert problem["severity"] == "warning"
    assert problem["warnings"][0]["name"] == "unusually_large"
    assert problem["acknowledge"] == {"header": "Waymark-Acknowledge",
                                      "names": ["unusually_large"]}

    res = await client.post(f"{doc['self']}/-/send",
                            headers={"Idempotency-Key": uuid.uuid4().hex,
                                     "Waymark-Acknowledge": "unusually_large"})
    assert res.status_code == 200, res.text
    assert res.json()["state"] == "sent"

    wire_id = doc["self"].rsplit("/", 1)[-1]
    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(s, "wire", wire_id)
    assert last.action == "send"
    assert last.acknowledged == ["unusually_large"], \
        "the override is in the audit log"


async def test_unwarned_actions_carry_no_warning_noise(env):
    engine, client = env
    doc = await _wire(client, 50)
    assert "warnings" not in doc["actions"]["send"]

    res = await client.post(f"{doc['self']}/-/send",
                            headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 200, res.text
    wire_id = doc["self"].rsplit("/", 1)[-1]
    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(s, "wire", wire_id)
    assert last.acknowledged is None


async def test_refuse_guards_cannot_be_acknowledged_past(env):
    """Severity is the declaration's: a refuse guard ignores the header."""
    engine, client = env
    doc = await _wire(client, 50, memo=None)
    assert "send" in doc["unavailable"]
    res = await client.post(f"{doc['self']}/-/send",
                            headers={"Idempotency-Key": uuid.uuid4().hex,
                                     "Waymark-Acknowledge": "memo_required"})
    assert res.status_code == 409, res.text
    assert res.json()["detail"] == "A wire needs a memo before it can be sent."


async def test_dry_run_surfaces_warnings(env):
    engine, client = env
    doc = await _wire(client, 5000)
    res = await client.post(f"{doc['self']}/-/send?dry_run=1")
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["valid"] is True
    assert body["warnings"][0]["name"] == "unusually_large"
    assert (await client.get(doc["self"])).json()["state"] == "draft", \
        "dry run moved nothing"
