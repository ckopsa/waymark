"""Four eyes (design E3): authority conditioned on history — whoever
performed the ``of`` transition cannot perform the guarded one. The guard
reads the transition log (``ctx.actor_of``), so enforcement and the
rendered refusal come from the same fact the log already keeps.
"""
from __future__ import annotations

import os
import uuid
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark5
from waymark5 import Ctx, Resource, Safety, action
from waymark5.core.guards import four_eyes
from waymark5.server.bus import InProcessBus
from waymark5.server.engine import header_principal
from waymark5.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


class RecState(StrEnum):
    OPEN = "open"
    PREPARED = "prepared"
    REVIEWED = "reviewed"


class RecData(BaseModel):
    period: str = Field(min_length=1, max_length=32)


class Recon(Resource):
    kind = "recon"
    State = RecState
    Data = RecData
    initial = RecState.OPEN
    terminal = {RecState.REVIEWED}
    summary = "{data.period} · {state.label}"

    @action(from_=RecState.OPEN, to=RecState.PREPARED,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Prepare"))
    async def prepare(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=RecState.PREPARED, to=RecState.REVIEWED,
            guards=[four_eyes(of="prepare")],
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The reconciliation closes for good."),
            display=dict(label="Review"))
    async def review(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=RecState.PREPARED, to=RecState.OPEN,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Reopen"))
    async def reopen(self, inp: None, ctx: Ctx) -> None:
        pass


@pytest.fixture
async def env():
    engine = waymark5.Engine(resources=[Recon], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    transport = ASGITransport(app=app)

    def client(pid: str) -> AsyncClient:
        return AsyncClient(transport=transport, base_url="http://t",
                           headers={"X-Principal-Id": pid,
                                    "X-Principal-Display": pid.title()})

    clients: list[AsyncClient] = []

    def tracked(pid: str) -> AsyncClient:
        c = client(pid)
        clients.append(c)
        return c

    try:
        yield tracked
    finally:
        for c in clients:
            await c.aclose()
        await engine.shutdown()


async def _post(client, href, json=None):
    return await client.post(href, json=json,
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def test_the_preparer_cannot_review(env):
    """Projection and enforcement read the same log fact: marcus's own
    review renders unavailable with the reason, and the invoke refuses
    with the identical sentence."""
    marcus, elena = env("marcus"), env("elena")
    doc = (await _post(marcus, "/api/recons", {"period": "2026-07"})).json()
    doc = (await _post(marcus, f"{doc['self']}/-/prepare")).json()

    assert "review" not in doc["actions"]
    entry = doc["unavailable"]["review"]
    assert "someone else" in entry["reason"]

    refused = await _post(marcus, f"{doc['self']}/-/review")
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] == entry["reason"]

    # elena's envelope offers it, and her invoke lands
    elena_doc = (await elena.get(doc["self"])).json()
    assert "review" in elena_doc["actions"]
    res = await _post(elena, f"{doc['self']}/-/review")
    assert res.status_code == 200, res.text
    assert res.json()["state"] == "reviewed"


async def test_the_latest_performer_is_the_one_barred(env):
    """History means the log's latest word: after elena re-prepares, the
    bar moves to her and marcus may review."""
    marcus, elena = env("marcus"), env("elena")
    doc = (await _post(marcus, "/api/recons", {"period": "2026-08"})).json()
    href = doc["self"]
    await _post(marcus, f"{href}/-/prepare")
    await _post(elena, f"{href}/-/reopen")
    await _post(elena, f"{href}/-/prepare")

    refused = await _post(elena, f"{href}/-/review")
    assert refused.status_code == 409

    res = await _post(marcus, f"{href}/-/review")
    assert res.status_code == 200, res.text
    assert res.json()["state"] == "reviewed"
