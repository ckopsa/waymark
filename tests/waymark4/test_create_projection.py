"""The create surface joins projection (design §10): create-guard
``accepts=`` sets fold into the advertised create schema as enums,
warning-severity create guards render on the create entry the way action
warnings do (design E1), approval-mode creates carry acknowledgments like
ordinary writes, and a member invite refuses a role the registry does not
know — at create, where the reviewer is still looking at it.
"""
from __future__ import annotations

import os
import uuid
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark4
from waymark4 import Ctx, Resource, Safety, action
from waymark4.core.guards import Guard
from waymark4.core.types import Acknowledged, Allow, Deny
from waymark4.server.bus import InProcessBus
from waymark4.server.engine import header_principal
from waymark4.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

LIMIT = 1000
DESK_FUNDS = ("alpha", "beta")
FROZEN = {"on": False}  # the intake freeze the ops team flips


class ReconState(StrEnum):
    OPEN = "open"
    CLOSED = "closed"


class ReconData(BaseModel):
    fund: str = Field(min_length=1, max_length=40)
    amount: float = Field(gt=0)


# refuse severity with accepts=: THE set — advertised on the create form
# (design §10) and enforced at create (design E9), one declaration
desk_covers_fund = Guard(
    name="desk_covers_fund",
    judges=("fund",),
    accepts=lambda r: list(DESK_FUNDS),  # r is None at create
    explain="Fund {fund} is not reconciled by this desk.",
)


async def _check_amount(r, inp, ctx: Ctx):
    if inp.amount > LIMIT:
        return Deny(vars={"amount": inp.amount, "limit": LIMIT})
    return Allow()

# input-grading warning: cannot be judged before the form is filled, so it
# surfaces at dry-run/invoke — and must not crash the create-entry probe
over_threshold = Guard(
    name="over_threshold", severity="warning",
    explain="{amount} is over the {limit} intake threshold — proceed only "
            "if this amount is expected.",
    vars=("amount", "limit"),
    check=_check_amount,
)


async def _check_frozen(r, inp, ctx: Ctx):
    return Deny() if FROZEN["on"] else Allow()

# input-independent warning (needs_input=False, declared): the projector
# probes it, so the create entry warns up front — the E1 face on create
intake_frozen = Guard(
    name="intake_frozen", severity="warning", needs_input=False,
    explain="Intake is frozen this week — proceed only if this event "
            "cannot wait.",
    check=_check_frozen,
)


class ReconEvent(Resource):
    kind = "recon_event"
    State = ReconState
    Data = ReconData
    initial = ReconState.OPEN
    terminal = {ReconState.CLOSED}
    summary = "Recon of {data.amount} for {data.fund} · {state.label}"

    create_guards = (desk_covers_fund, over_threshold, intake_frozen)

    @action(from_=ReconState.OPEN, to=ReconState.CLOSED,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Closing a recon event is the point of one.")),
            display=dict(label="Close"))
    async def close(self, inp: None, ctx: Ctx) -> None:
        pass


@pytest.fixture
async def env():
    FROZEN["on"] = False
    engine = waymark4.Engine(resources=[ReconEvent], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    transport = ASGITransport(app=app)
    human = AsyncClient(transport=transport, base_url="http://t",
                        headers={"X-Principal-Id": "sofia",
                                 "X-Principal-Display": "Sofia"})
    try:
        yield engine, transport, human
    finally:
        await human.aclose()
        await engine.shutdown()


# ── the create form folds its guards (design §10) ───────────────────────
async def test_create_schema_carries_accepts_enums(env):
    engine, transport, human = env
    doc = (await human.get("/api/recon_events")).json()
    entry = doc["actions"]["create"]
    assert entry["input"]["properties"]["fund"]["enum"] == ["alpha", "beta"], \
        "the form never offers a fund the desk will refuse"
    assert "warnings" not in entry, "no warning noise while intake is open"


async def test_probeable_create_warning_rides_the_entry(env):
    engine, transport, human = env
    FROZEN["on"] = True
    doc = (await human.get("/api/recon_events")).json()
    entry = doc["actions"]["create"]
    assert entry["warnings"] == [
        {"name": "intake_frozen",
         "reason": "Intake is frozen this week — proceed only if this "
                   "event cannot wait."}], \
        "advertised up front, the same sentence enforcement will give"


async def test_create_warning_acknowledge_flow(env):
    """The E1 protocol on create: 409 warning-required with the override
    affordance, then the acknowledged retry creates with the override in
    the audit log."""
    engine, transport, human = env
    FROZEN["on"] = True
    res = await human.post("/api/recon_events",
                           json={"fund": "alpha", "amount": 5},
                           headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 409, res.text
    problem = res.json()
    assert problem["type"].endswith("warning-required")
    assert problem["acknowledge"]["names"] == ["intake_frozen"]

    res = await human.post("/api/recon_events",
                           json={"fund": "alpha", "amount": 5},
                           headers={"Idempotency-Key": uuid.uuid4().hex,
                                    "Waymark-Acknowledge": "intake_frozen"})
    assert res.status_code == 201, res.text
    event_id = res.json()["self"].rsplit("/", 1)[-1]
    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(s, "recon_event",
                                                    event_id)
    assert last.acknowledged == ["intake_frozen"]


# ── approval-mode creates carry acknowledgments (design §10) ────────────
def _agent(transport, token) -> AsyncClient:
    return AsyncClient(transport=transport, base_url="http://t",
                       headers={"Authorization": f"Bearer {token}"})


async def _act(client, doc, action_name, body=None, extra=None):
    headers = {"Idempotency-Key": uuid.uuid4().hex, **(extra or {})}
    entry = doc["actions"].get(action_name)
    if entry and entry.get("safety", {}).get("fence"):
        headers["If-Match"] = doc["meta"]["etag"]
    return await client.post(f"{doc['self']}/-/{action_name}", json=body,
                             headers=headers)


async def _approval_grant(transport, human) -> AsyncClient:
    res = await human.post("/api/grants", json={"holder_name": "Robo"},
                           headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    grant = res.json()
    agent = _agent(transport, grant["data"]["token"])
    own = (await agent.get(grant["self"])).json()
    res = await _act(agent, own, "request_access", {
        "task": "Propose recon events for review.",
        "requested_fields": {"recon_event": {"fund": "clear",
                                             "amount": "clear",
                                             "summary": "clear"}},
        "requested_actions": {"recon_event": {"create": "approval"}},
        "requested_hours": 2,
    })
    assert res.status_code == 200, res.text
    res = await _act(human, (await human.get(grant["self"])).json(),
                     "approve")
    assert res.status_code == 200, res.text
    return agent


async def test_approval_mode_create_carries_acknowledgments(env):
    engine, transport, human = env
    agent = await _approval_grant(transport, human)

    # the agent proposes an over-threshold create WITH its acknowledgment;
    # the capture records it for the approver to see (design §10)
    res = await agent.post(
        "/api/recon_events", json={"fund": "alpha", "amount": 5000},
        headers={"Idempotency-Key": uuid.uuid4().hex,
                 "Waymark-Acknowledge": "over_threshold"})
    assert res.status_code == 202, res.text
    approval = res.json()
    h_appr = (await human.get(approval["self"])).json()
    assert h_appr["data"]["acknowledged"] == ["over_threshold"], \
        "the reviewer sees what the agent accepted responsibility for"

    res = await human.post(f"{approval['self']}/-/approve",
                           json={"overrides": {}},
                           headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 200, res.text
    a_appr = (await agent.get(approval["self"])).json()
    res = await agent.post(f"{a_appr['self']}/-/run",
                           headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 200, res.text
    closed = (await human.get(approval["self"])).json()
    assert closed["data"]["outcome"] == "Ran successfully.", closed["data"]

    listing = (await human.get("/api/recon_events")).json()
    assert listing["data"]["total"] == 1
    event_id = listing["data"]["items"][0]["self"].rsplit("/", 1)[-1]
    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(s, "recon_event",
                                                    event_id)
    assert last.action == "create"
    assert last.acknowledged == ["over_threshold"], \
        "the override rode the approval into the audit log"


async def test_unacknowledged_approval_create_still_fails_the_run(env):
    """Severity stays the declaration's: an approval does not launder an
    unacknowledged warning — the run refuses with the E1 sentence."""
    engine, transport, human = env
    agent = await _approval_grant(transport, human)

    res = await agent.post(
        "/api/recon_events", json={"fund": "alpha", "amount": 5000},
        headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 202, res.text
    approval = res.json()
    await human.post(f"{approval['self']}/-/approve", json={"overrides": {}},
                     headers={"Idempotency-Key": uuid.uuid4().hex})
    a_appr = (await agent.get(approval["self"])).json()
    res = await agent.post(f"{a_appr['self']}/-/run",
                           headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 200, res.text
    closed = (await human.get(approval["self"])).json()
    assert closed["data"]["outcome"].startswith("Failed:")
    assert "acknowledgment" in closed["data"]["outcome"]
    listing = (await human.get("/api/recon_events")).json()
    assert listing["data"]["total"] == 0


# ── member roles validate at invite (design §10) ────────────────────────
async def test_member_invite_refuses_unknown_role(env):
    engine, transport, human = env
    res = await human.post(
        "/api/members",
        json={"email": "sam@example.com", "display_name": "Sam",
              "roles": ["ghost"]},
        headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 409, res.text
    problem = res.json()
    assert "No active role named ghost" in problem["detail"]
    assert "role.create" in (problem.get("remedies") or [])

    # register the role; the same invite lands
    res = await human.post("/api/roles", json={"name": "ghost"},
                           headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    res = await human.post(
        "/api/members",
        json={"email": "sam@example.com", "display_name": "Sam",
              "roles": ["ghost"]},
        headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text


async def test_retired_role_refuses_at_invite_too(env):
    engine, transport, human = env
    res = await human.post("/api/roles", json={"name": "reader"},
                           headers={"Idempotency-Key": uuid.uuid4().hex})
    role = res.json()
    await human.post(f"{role['self']}/-/retire",
                     headers={"Idempotency-Key": uuid.uuid4().hex})
    res = await human.post(
        "/api/members",
        json={"email": "amy@example.com", "display_name": "Amy",
              "roles": ["reader"]},
        headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 409, res.text
    assert "No active role named reader" in res.json()["detail"]
