"""Create-time guards (design E9): ``create_guards`` judge the validated
create input with r=None — the guard surface create never had. Refuse
severity denies the create outright; warning severity joins the E1
acknowledge protocol, and the override lands on the create's own
transition row. The rule that used to require hand-raising GuardRefused
from ``on_create`` (Grant's role check) is now a declaration.
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
from waymark4.core.types import Acknowledged, Allow, DefinitionError, Deny
from waymark4.server.bus import InProcessBus
from waymark4.server.engine import header_principal
from waymark4.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

LIMIT = 1000

DESK_FUNDS = ("alpha", "beta")


class ReconState(StrEnum):
    OPEN = "open"
    CLOSED = "closed"


class ReconData(BaseModel):
    fund: str = Field(min_length=1, max_length=40)
    amount: float = Field(gt=0)


# refuse severity: judges a create-model field, accepts= is THE set —
# advertised and enforced from one declaration, as on any guard
desk_covers_fund = Guard(
    name="desk_covers_fund",
    judges=("fund",),
    accepts=lambda r: list(DESK_FUNDS),  # r is None at create (design E9)
    explain="Fund {fund} is not reconciled by this desk.",
)


async def _check_amount(r, inp, ctx: Ctx):
    # r is None (design E9): a create guard's check grades the input
    if inp.amount > LIMIT:
        return Deny(vars={"amount": inp.amount, "limit": LIMIT})
    return Allow()

over_threshold = Guard(
    name="over_threshold", severity="warning",
    explain="{amount} is over the {limit} intake threshold — proceed only "
            "if this amount is expected.",
    vars=("amount", "limit"),
    check=_check_amount,
)


class ReconEvent(Resource):
    kind = "recon_event"
    State = ReconState
    Data = ReconData
    initial = ReconState.OPEN
    terminal = {ReconState.CLOSED}
    summary = "Recon of {data.amount} for {data.fund} · {state.label}"

    create_guards = (desk_covers_fund, over_threshold)

    @action(from_=ReconState.OPEN, to=ReconState.CLOSED,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Closing a recon event is the point of one.")),
            display=dict(label="Close"))
    async def close(self, inp: None, ctx: Ctx) -> None:
        pass


@pytest.fixture
async def env():
    engine = waymark4.Engine(resources=[ReconEvent], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    transport = ASGITransport(app=app)
    client = AsyncClient(transport=transport, base_url="http://t",
                         headers={"X-Principal-Id": "sofia",
                                  "X-Principal-Display": "Sofia"})
    try:
        yield engine, client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _count(engine, kind: str) -> int:
    async with engine.storage.session() as s:
        _, total = await engine.storage.query(
            s, kind, filters={}, sort=None, page_size=1, page_number=1)
    return total


async def test_refuse_create_guard_denies_and_creates_nothing(env):
    """A refuse-severity create guard 409s with its own sentence — and no
    row, no transition, no on_create side effect precedes the verdict."""
    engine, client = env
    res = await client.post("/api/recon_events",
                            json={"fund": "gamma", "amount": 5},
                            headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 409, res.text
    problem = res.json()
    assert problem["type"].endswith("guard-failed")
    assert problem["detail"] == "Fund gamma is not reconciled by this desk."
    assert problem["action_attempted"] == "create"
    assert await _count(engine, "recon_event") == 0


async def test_warning_create_guard_requires_acknowledgment(env):
    """An unacknowledged warning 409s ``warning-required`` carrying the
    override affordance; the acknowledged retry creates, and the create's
    transition row records exactly what was overridden (design E1 audit)."""
    engine, client = env
    res = await client.post("/api/recon_events",
                            json={"fund": "alpha", "amount": 5000},
                            headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 409, res.text
    problem = res.json()
    assert problem["type"].endswith("warning-required")
    assert problem["severity"] == "warning"
    assert problem["warnings"] == [
        {"name": "over_threshold",
         "reason": "5000.0 is over the 1000 intake threshold — proceed only "
                   "if this amount is expected."}]
    assert problem["acknowledge"] == {"header": "Waymark-Acknowledge",
                                      "names": ["over_threshold"]}
    assert await _count(engine, "recon_event") == 0

    res = await client.post("/api/recon_events",
                            json={"fund": "alpha", "amount": 5000},
                            headers={"Idempotency-Key": uuid.uuid4().hex,
                                     "Waymark-Acknowledge": "over_threshold"})
    assert res.status_code == 201, res.text
    doc = res.json()
    event_id = doc["self"].rsplit("/", 1)[-1]
    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(s, "recon_event", event_id)
    assert last.action == "create"
    assert last.acknowledged == ["over_threshold"], \
        "the override is in the audit log"


async def test_unwarned_create_records_no_acknowledgment(env):
    """A create no warning denied leaves the acknowledged column null."""
    engine, client = env
    res = await client.post("/api/recon_events",
                            json={"fund": "alpha", "amount": 5},
                            headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    event_id = res.json()["self"].rsplit("/", 1)[-1]
    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(s, "recon_event", event_id)
    assert last.acknowledged is None


async def test_dry_run_create_surfaces_warnings_and_creates_nothing(env):
    """Dry-run create judges declared create guards the way the action
    path does: warnings ride the response body, refuse raises, and no
    row exists either way."""
    engine, client = env
    res = await client.post("/api/recon_events?dry_run=1",
                            json={"fund": "alpha", "amount": 5000})
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["valid"] is True
    assert body["warnings"] == [
        {"name": "over_threshold",
         "reason": "5000.0 is over the 1000 intake threshold — proceed only "
                   "if this amount is expected."}]

    res = await client.post("/api/recon_events?dry_run=1",
                            json={"fund": "gamma", "amount": 5})
    assert res.status_code == 409, res.text
    assert res.json()["detail"] == \
        "Fund gamma is not reconciled by this desk."
    assert await _count(engine, "recon_event") == 0


async def test_grant_to_unregistered_role_still_refused_at_create(env):
    """Regression for the migrated hack: Grant's role check is now a
    create guard (role_registered_create), and the refusal keeps its
    exact sentence and remedy (design §9's silent-grant gap)."""
    engine, client = env
    res = await client.post("/api/grants",
                            json={"holder_name": "Readers",
                                  "holder_kind": "role",
                                  "holder_id": "reader"},
                            headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 409, res.text
    problem = res.json()
    assert "No active role named reader" in problem["detail"]
    assert "create the role first" in problem["detail"]
    assert "role.create" in (problem.get("remedies") or [])
    assert await _count(engine, "grant") == 0


async def test_create_guard_judging_unknown_field_is_a_definition_error():
    """check_create_guards: a create guard's judged fields must exist on
    the create model — a typo would silently judge nothing."""
    class OnlyOpen(StrEnum):
        OPEN = "open"

    with pytest.raises(DefinitionError, match="judges"):
        class Broken(Resource):
            kind = "broken_recon"
            State = OnlyOpen
            Data = ReconData
            initial = OnlyOpen.OPEN
            summary = "Broken · {state.label}"

            create_guards = (Guard(
                name="typo", judges=("fudn",),
                accepts=lambda r: ["alpha"],
                explain="Fund {fudn} is not reconciled by this desk."),)
