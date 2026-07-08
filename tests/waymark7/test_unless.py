"""Per-action relative visibility (design §4): ``unless=Unless(actor_of(…))``
is ONE declaration with two consumers — the projector sorts the action into
``unavailable`` for the principal the log fact names (never out of the
envelope: the conformance invariant holds), and the invoker refuses the
client that ignores advertisement with the SAME sentence, in the
guard-refused Problem shape. E3's ``four_eyes`` survives as sugar for
exactly this mechanism.
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
from waymark7 import (
    Ctx,
    DefinitionError,
    Resource,
    Safety,
    Unless,
    action,
    actor_of,
    four_eyes,
)
from waymark7.server.bus import InProcessBus
from waymark7.server.engine import header_principal
from waymark7.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

OVERRIDE = "Submitted by you; a second reviewer must approve."


class WireState(StrEnum):
    DRAFT = "draft"
    SUBMITTED = "submitted"
    APPROVED = "approved"


class WireData(BaseModel):
    memo: str = Field(min_length=1, max_length=120)


class Wire(Resource):
    kind = "wire"
    State = WireState
    Data = WireData
    initial = WireState.DRAFT
    terminal = {WireState.APPROVED}
    summary = "{data.memo} · {state.label}"

    @action(from_=WireState.DRAFT, to=WireState.SUBMITTED,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Submit"))
    async def submit(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=WireState.SUBMITTED, to=WireState.DRAFT,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Send back"))
    async def send_back(self, inp: None, ctx: Ctx) -> None:
        pass

    # the general mechanism, with the generated reason
    @action(from_=WireState.SUBMITTED, to=WireState.APPROVED,
            unless=Unless(actor_of("submit")),
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Releases the wire for payment."),
            display=dict(label="Approve"))
    async def approve(self, inp: None, ctx: Ctx) -> None:
        pass

    # the same mechanism, with the declaration's own sentence
    @action(from_=WireState.SUBMITTED, to=WireState.APPROVED,
            unless=Unless(actor_of("submit"), explain=OVERRIDE),
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Releases the wire for payment."),
            display=dict(label="Fast approve"))
    async def fast_approve(self, inp: None, ctx: Ctx) -> None:
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

    clients: list[AsyncClient] = []

    def client(pid: str) -> AsyncClient:
        c = AsyncClient(transport=transport, base_url="http://t",
                        headers={"X-Principal-Id": pid,
                                 "X-Principal-Display": pid.title()})
        clients.append(c)
        return c

    try:
        yield client
    finally:
        for c in clients:
            await c.aclose()
        await engine.shutdown()


async def _post(client, href, json=None):
    return await client.post(href, json=json,
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def _submitted(client) -> dict:
    doc = (await _post(client, "/api/wires", {"memo": "vendor invoice"})).json()
    return (await _post(client, f"{doc['self']}/-/submit")).json()


async def test_projector_and_invoker_share_the_sentence(env):
    """Sam sees approve honestly unavailable — with the generated reason —
    and the invoke that ignores the advertisement is refused with the
    identical string (409, guard-refused shape). Priya sees the button and
    her invoke lands."""
    sam, priya = env("sam"), env("priya")
    doc = await _submitted(sam)

    assert "approve" not in doc["actions"]
    entry = doc["unavailable"]["approve"]
    assert entry["reason"] == ("You performed submit on this resource; "
                               "a different principal must do this.")

    refused = await _post(sam, f"{doc['self']}/-/approve")
    assert refused.status_code == 409, refused.text
    body = refused.json()
    assert body["detail"] == entry["reason"]
    assert body["type"].endswith("guard-failed")

    priya_doc = (await priya.get(doc["self"])).json()
    assert "approve" in priya_doc["actions"]
    res = await _post(priya, f"{doc['self']}/-/approve")
    assert res.status_code == 200, res.text
    assert res.json()["state"] == "approved"


async def test_unless_lands_in_unavailable_never_vanishes(env):
    """The conformance invariant: actions ∪ unavailable ∪ hidden ==
    transitions_from(state). An unless-refused action lands in
    ``unavailable`` — the honest no — not out of the envelope."""
    sam = env("sam")
    doc = await _submitted(sam)

    everywhere = set(doc["actions"]) | set(doc["unavailable"])
    assert everywhere == {"submit", "send_back", "approve", "fast_approve"}
    assert {"approve", "fast_approve"} <= set(doc["unavailable"])
    assert {"send_back"} <= set(doc["actions"])


async def test_explain_override_rides_both_surfaces(env):
    """``Unless(..., explain=…)`` replaces the generated sentence — on the
    envelope and in the refusal alike, because there is only one sentence."""
    sam = env("sam")
    doc = await _submitted(sam)

    assert doc["unavailable"]["fast_approve"]["reason"] == OVERRIDE
    refused = await _post(sam, f"{doc['self']}/-/fast_approve")
    assert refused.status_code == 409
    assert refused.json()["detail"] == OVERRIDE


async def test_the_bar_follows_the_latest_actor(env):
    """The fact is the log's latest word: after priya re-submits, the bar
    moves to her and sam may approve."""
    sam, priya = env("sam"), env("priya")
    doc = await _submitted(sam)
    href = doc["self"]
    await _post(priya, f"{href}/-/send_back")
    await _post(priya, f"{href}/-/submit")

    refused = await _post(priya, f"{href}/-/approve")
    assert refused.status_code == 409

    res = await _post(sam, f"{href}/-/approve")
    assert res.status_code == 200, res.text
    assert res.json()["state"] == "approved"


async def test_four_eyes_is_sugar_for_the_same_mechanism():
    """E3's fate (design §4): kept in behavior, generalized in mechanism —
    ``four_eyes(of=…)`` produces the Unless guard, reading the same log
    fact both surfaces consume."""
    g = four_eyes(of="prepare")
    assert g.name == "four_eyes:prepare"
    assert set(g.reads) == {"transitions", "principal"}
    assert "someone else" in g.explain


async def test_unknown_transition_is_a_definition_error():
    """unless= referencing a transition the machine never logs could bar
    nobody — refused at import, not discovered in production."""
    class TwoStep(StrEnum):
        DRAFT = "draft"
        APPROVED = "approved"

    with pytest.raises(DefinitionError, match="names no transition"):
        class Broken(Resource):
            kind = "broken_wire"
            State = TwoStep
            Data = WireData
            initial = TwoStep.DRAFT
            terminal = {TwoStep.APPROVED}
            summary = "{data.memo}"

            @action(from_=TwoStep.DRAFT, to=TwoStep.APPROVED,
                    unless=Unless(actor_of("no_such_transition")),
                    safety=Safety(idempotent=True, reversible=False,
                                  confirm=True, consequence="Approves."))
            async def approve(self, inp: None, ctx: Ctx) -> None:
                pass


async def test_unless_takes_the_declaration_not_a_string():
    with pytest.raises(DefinitionError, match="unless= takes an Unless"):
        @action(from_=WireState.DRAFT, to=WireState.APPROVED,
                unless="submit",  # type: ignore[arg-type]
                safety=Safety(idempotent=True, reversible=False, confirm=True,
                              consequence="Approves."))
        async def approve(self, inp: None, ctx: Ctx) -> None:
            pass

    with pytest.raises(DefinitionError, match="log fact"):
        Unless("submit")  # type: ignore[arg-type]
