"""Member visibility (design §9): humans under
``Engine(member_visibility="granted")`` see what they own plus what their
member- and role-held grants say — advertised, enforced, and pushed into
collection SQL from one object. Delegation attenuates: a token's view
never exceeds its approver's current effective view.
"""
from __future__ import annotations

import os
import uuid
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark3
from waymark3 import Ctx, Resource, Safety, action
from waymark3.server.bus import InProcessBus
from waymark3.server.engine import header_principal
from waymark3.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


class NoteState(StrEnum):
    OPEN = "open"


class NoteData(BaseModel):
    title: str = Field(min_length=1, max_length=120)
    body: str | None = Field(default=None, max_length=200)


class Note(Resource):
    kind = "note"
    State = NoteState
    Data = NoteData
    initial = NoteState.OPEN
    terminal: set = set()
    summary = "{data.title} · {state.label}"

    @action(from_=NoteState.OPEN, to=NoteState.OPEN,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Touch"))
    async def touch(self, inp: None, ctx: Ctx) -> None:
        pass


def H(pid: str, roles: str = "") -> dict[str, str]:
    h = {"X-Principal-Id": pid, "X-Principal-Display": pid.title()}
    if roles:
        h["X-Principal-Roles"] = roles
    return h


@pytest.fixture
async def env():
    engine = waymark3.Engine(resources=[Note], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus(),
                             member_visibility="granted")
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    transport = ASGITransport(app=app)

    def client(pid: str, roles: str = "") -> AsyncClient:
        return AsyncClient(transport=transport, base_url="http://t",
                           headers=H(pid, roles))

    clients: list[AsyncClient] = []

    def tracked(pid: str, roles: str = "") -> AsyncClient:
        c = client(pid, roles)
        clients.append(c)
        return c

    try:
        yield engine, transport, tracked
    finally:
        for c in clients:
            await c.aclose()
        await engine.shutdown()


async def _post(client, href, json=None):
    return await client.post(href, json=json or {},
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def _act(client, doc, action, body=None):
    headers = {"Idempotency-Key": uuid.uuid4().hex}
    entry = (doc.get("actions") or {}).get(action)
    if entry and entry.get("safety", {}).get("fence"):
        headers["If-Match"] = doc["meta"]["etag"]
    return await client.post(f"{doc['self']}/-/{action}", json=body,
                             headers=headers)


async def _note(client, title) -> str:
    res = await _post(client, "/api/notes", {"title": title, "body": "…"})
    assert res.status_code == 201, res.text
    return res.json()["self"]


async def _share(dana, bob, over_ids, fields=None, actions=None,
                 holder="bob") -> str:
    """bob asks; dana decides — the negotiation loop with humans as
    holders."""
    res = await _post(bob, "/api/grants",
                      {"holder_name": holder.title(), "holder_kind": "member",
                       "holder_id": holder})
    assert res.status_code == 201, res.text
    href = res.json()["self"]
    res = await _act(bob, (await bob.get(href)).json(), "request_access", {
        "task": "See the shared notes.",
        "requested_fields": {"note": fields or {"title": "clear",
                                                "summary": "clear"}},
        "requested_actions": {"note": actions or {"touch": "open"}},
        "requested_over": {"note": over_ids} if over_ids else {},
        "requested_hours": 2,
    })
    assert res.status_code == 200, res.text
    res = await _act(dana, (await dana.get(href)).json(), "approve")
    assert res.status_code == 200, res.text
    return href


async def test_ownership_is_the_baseline(env):
    engine, transport, client = env
    dana, bob = client("dana"), client("bob")
    mine = await _note(dana, "Dana's plan")

    # the owner has the full envelope
    doc = (await dana.get(mine)).json()
    assert doc["data"]["title"] == "Dana's plan"
    assert "touch" in doc["actions"]
    assert doc["meta"]["owner"] == "dana"

    # a member nobody granted sees a shell — and cannot act
    shell = (await bob.get(mine)).json()
    assert shell["data"] == {}
    assert shell["summary"] == "note (scoped view)"
    assert "touch" not in shell["actions"]
    assert (await _act(bob, {"self": mine, "meta": {}, "actions": {}},
                       "touch")).status_code == 403

    # the pushdown: bob's listing is honestly empty, count included
    assert (await bob.get("/api/notes")).json()["data"]["total"] == 0
    assert (await dana.get("/api/notes")).json()["data"]["total"] == 1


async def test_member_grant_shares_one_note(env):
    engine, transport, client = env
    dana, bob = client("dana"), client("bob")
    shared = await _note(dana, "Shared with Bob")
    private = await _note(dana, "Private planning")
    await _share(dana, bob, [shared.rsplit("/", 1)[-1]])

    doc = (await bob.get(shared)).json()
    assert doc["data"]["title"] == "Shared with Bob"
    assert "body" not in doc["data"], "ungranted field stays nonexistent"
    assert "touch" in doc["actions"]
    assert (await _act(bob, doc, "touch")).status_code == 200

    assert (await bob.get(private)).json()["data"] == {}

    # pushdown includes exactly the granted id (plus bob's own — none)
    listing = (await bob.get("/api/notes")).json()
    assert listing["data"]["total"] == 1
    assert listing["data"]["items"][0]["self"] == shared


async def test_holder_cannot_approve_their_own_grant(env):
    engine, transport, client = env
    bob = client("bob")
    res = await _post(bob, "/api/grants",
                      {"holder_name": "Bob", "holder_kind": "member",
                       "holder_id": "bob"})
    href = res.json()["self"]
    await _act(bob, (await bob.get(href)).json(), "request_access", {
        "task": "Everything, please.",
        "requested_fields": {"note": {"title": "clear"}},
        "requested_hours": 2})
    denied = await _act(bob, (await bob.get(href)).json(), "approve")
    assert denied.status_code == 409
    assert "someone else approves" in denied.json()["detail"]


async def test_role_grants_reach_every_holder(env):
    engine, transport, client = env
    dana, carol = client("dana"), client("carol", roles="reader")
    await _note(dana, "Family recipes")

    admin = client("admin")
    res = await _post(admin, "/api/grants",
                      {"holder_name": "Readers", "holder_kind": "role",
                       "holder_id": "reader"})
    href = res.json()["self"]
    await _act(admin, (await admin.get(href)).json(), "request_access", {
        "task": "Readers may read note titles.",
        "requested_fields": {"note": {"title": "clear", "summary": "clear"}},
        "requested_hours": 2})
    await _act(dana, (await dana.get(href)).json(), "approve")

    # carol holds the role: kind-level grant lifts the listing restriction
    listing = (await carol.get("/api/notes")).json()
    assert listing["data"]["total"] == 1
    item = listing["data"]["items"][0]
    assert item["data"] == {"title": "Family recipes"}

    # a member without the role stays restricted
    assert (await client("eve").get("/api/notes")).json()["data"]["total"] == 0


async def test_delegation_attenuates_live(env):
    engine, transport, client = env
    dana, bob = client("dana"), client("bob")
    shared = await _note(dana, "Shared with Bob")
    note_id = shared.rsplit("/", 1)[-1]
    bob_grant = await _share(dana, bob, [note_id])

    # bob delegates to an agent: a token grant over the whole note KIND,
    # approved by bob — whose own view covers only the one note
    res = await _post(bob, "/api/grants", {"holder_name": "Robo"})
    agent_href = res.json()["self"]
    token = res.json()["data"]["token"]
    agent = AsyncClient(transport=transport, base_url="http://t",
                        headers={"Authorization": f"Bearer {token}"})
    own = (await agent.get(agent_href)).json()
    await _act(agent, own, "request_access", {
        "task": "Tend the notes.",
        "requested_fields": {"note": {"title": "clear", "summary": "clear"}},
        "requested_hours": 2})
    res = await _act(bob, (await bob.get(agent_href)).json(), "approve")
    assert res.status_code == 200, res.text

    # the ceiling: kind-level ask ∩ bob's one-note view = the one note
    doc = (await agent.get(shared)).json()
    assert doc["data"]["title"] == "Shared with Bob"
    other = await _note(dana, "Not Bob's to delegate")
    assert (await agent.get(other)).json()["data"] == {}

    # revoke bob's own grant: the delegate's view collapses in the same
    # render — no cleanup job ran
    res = await _act(dana, (await dana.get(bob_grant)).json(), "revoke")
    assert res.status_code == 200, res.text
    assert (await agent.get(shared)).json()["data"] == {}
    await agent.aclose()
