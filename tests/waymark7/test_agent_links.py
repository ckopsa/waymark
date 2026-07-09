"""Agent links, end to end: mint → catalog-only → request → approve →
scoped work → approval-gated work → human-supplied arguments → revoke.

The design under test: by default a token grants nothing; the agent
requests its minimum (per-field clear/hashed/hidden, per-action
open/approval/none, per-argument edit/approval/none, plus duration); the
human approves; enforcement is *rendering* — an ungrated field does not
exist, an ungrated action is honestly unavailable with the remedy naming
the fix.
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
from waymark7 import Ctx, Edit, Resource, Safety, action
from waymark7.server.bus import InProcessBus
from waymark7.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


class MemoState(StrEnum):
    OPEN = "open"


class MemoStep(BaseModel):
    label: str = Field(max_length=120)
    done: bool = False


class MemoData(BaseModel):
    title: str = Field(min_length=1, max_length=120)
    secret: str | None = Field(default=None, max_length=120)
    body: str | None = Field(default=None, json_schema_extra={
        "x-display": {"widget": "prose"}})
    steps: list[MemoStep] = Field(default_factory=list)


class BodyInput(BaseModel):
    body: str = Field(min_length=1, json_schema_extra={
        "x-display": {"widget": "prose"}})


class Memo(Resource):
    kind = "memo"
    State = MemoState
    Data = MemoData

    initial = MemoState.OPEN
    terminal: set = set()

    summary = "{data.title} · {state.label}"

    @action(from_=MemoState.OPEN, to=MemoState.OPEN,
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Touch"))
    async def touch(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=MemoState.OPEN, to=MemoState.OPEN, input=BodyInput,
            edit=Edit(prefill=("body",)),
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Edit body"))
    async def edit(self, inp: BodyInput, ctx: Ctx) -> None:
        self.data.body = inp.body


def H(pid: str, ptype: str = "human") -> dict[str, str]:
    return {"X-Principal-Id": pid, "X-Principal-Type": ptype,
            "X-Principal-Display": pid.title()}


@pytest.fixture
async def env():
    from waymark7.server.engine import header_principal

    engine = waymark7.Engine(resources=[Memo], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    transport = ASGITransport(app=app)
    human = AsyncClient(transport=transport, base_url="http://t",
                        headers=H("dana"))
    try:
        yield engine, transport, human
    finally:
        await human.aclose()
        await engine.shutdown()


async def _mint(human) -> tuple[str, str]:
    """Human mints the link; returns (grant_href, token)."""
    res = await human.post("/api/grants",
                           json={"holder_name": "Robo"},
                           headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    doc = res.json()
    return doc["self"], doc["data"]["token"]


def _agent(transport, token) -> AsyncClient:
    return AsyncClient(transport=transport, base_url="http://t",
                       headers={"Authorization": f"Bearer {token}"})


async def _memo(human, title="Groceries note", secret="pin 4321") -> str:
    res = await human.post("/api/memos",
                           json={"title": title, "secret": secret,
                                 "body": "original"},
                           headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    return res.json()["self"]


async def _act(client, doc, action, body=None, extra=None):
    headers = {"Idempotency-Key": uuid.uuid4().hex, **(extra or {})}
    entry = doc["actions"].get(action)
    if entry and entry.get("safety", {}).get("fence"):
        headers["If-Match"] = doc["meta"]["etag"]
    return await client.post(f"{doc['self']}/-/{action}", json=body,
                             headers=headers)


async def test_default_deny_then_negotiate_then_work(env):
    engine, transport, human = env
    grant_href, token = await _mint(human)
    memo_href = await _memo(human)
    agent = _agent(transport, token)

    # ── default deny: the token sees shapes, not truth ──────────────────
    doc = (await agent.get(memo_href)).json()
    assert doc["data"] == {}, "ungranted fields must not exist"
    assert "scoped view" in doc["summary"]
    assert doc["actions"] == {}
    assert all("granted scope" in e["reason"]
               for e in doc["unavailable"].values())
    # the catalog stays open: discovery and schemas carry no data
    assert (await agent.get("/api/.well-known/waymark")).status_code == 200
    assert (await agent.get("/api/schemas/memo")).status_code == 200
    # workspace streams are not for scoped agents
    assert (await agent.get("/api/-/presence")).status_code == 403

    # ── the agent's own grant: request, never decide ────────────────────
    own = (await agent.get(grant_href)).json()
    assert set(own["actions"]) == {"request_access"}
    forbidden = await _act(agent, own, "approve")
    assert forbidden.status_code == 403

    ask = {
        "task": "Keep the groceries memo tidy for the family.",
        "requested_fields": {"memo": {"title": "clear", "secret": "hashed",
                                      "summary": "clear"}},
        "requested_actions": {"memo": {"touch": "open", "edit": "approval"}},
        "requested_args": {"memo": {"edit": {"body": "edit"}}},
        "requested_hours": 2,
    }
    res = await _act(agent, own, "request_access", ask)
    assert res.status_code == 200, res.text
    assert res.json()["state"] == "requested"

    # nothing changes until a person approves
    assert (await agent.get(memo_href)).json()["data"] == {}
    approved = await _act(human, (await human.get(grant_href)).json(),
                          "approve")
    assert approved.status_code == 200 and approved.json()["state"] == "granted"

    # ── the granted view: exactly the ask, nothing more ─────────────────
    doc = (await agent.get(memo_href)).json()
    assert doc["data"]["title"] == "Groceries note"
    assert doc["data"]["secret"].startswith("sha256:")
    assert "body" not in doc["data"], "unrequested field stays nonexistent"
    assert doc["summary"] == "Groceries note · Open"
    assert "touch" in doc["actions"]
    assert doc["actions"]["edit"].get("access") == "approval"

    # open action: straight through, audited as the agent
    res = await _act(agent, doc, "touch")
    assert res.status_code == 200
    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(
            s, "memo", memo_href.rsplit("/", 1)[-1])
    assert last.actor_type == "agent" and last.actor_display == "Robo"

    # approval action: becomes a pending approval, not an execution
    doc = (await agent.get(memo_href)).json()
    res = await _act(agent, doc, "edit", {"body": "tidied list"})
    assert res.status_code == 202, res.text
    approval = res.json()
    assert approval["kind"] == "approval_request"
    assert approval["state"] == "pending"
    assert (await human.get(memo_href)).json()["data"]["body"] == "original"

    # human approves; the agent runs what was approved — one audit entry
    h_appr = (await human.get(approval["self"])).json()
    res = await _act(human, h_appr, "approve", {"overrides": {}})
    assert res.status_code == 200 and res.json()["state"] == "approved"
    a_appr = (await agent.get(approval["self"])).json()
    assert set(a_appr["actions"]) == {"run"}
    res = await _act(agent, a_appr, "run")
    assert res.status_code == 200
    closed = (await human.get(approval["self"])).json()
    assert closed["state"] == "closed"
    assert closed["data"]["outcome"] == "Ran successfully."
    assert (await human.get(memo_href)).json()["data"]["body"] == "tidied list"

    # ── revoke: the link goes dead, immediately ─────────────────────────
    await _act(human, (await human.get(grant_href)).json(), "revoke")
    doc = (await agent.get(memo_href)).json()
    assert doc["data"] == {} and doc["actions"] == {}
    assert (await _act(agent, {"self": memo_href, "actions": {}, "meta": {}},
                       "touch")).status_code == 403


async def test_required_argument_the_agent_may_not_supply(env):
    """arg mode 'none' on a required argument: the invocation still works —
    through a human, who fills the argument at approval time."""
    engine, transport, human = env
    grant_href, token = await _mint(human)
    memo_href = await _memo(human, title="Family motto")
    agent = _agent(transport, token)

    own = (await agent.get(grant_href)).json()
    await _act(agent, own, "request_access", {
        "task": "Trigger the body rewrite; a person supplies the words.",
        "requested_fields": {"memo": {"title": "clear", "summary": "clear"}},
        "requested_actions": {"memo": {"edit": "open"}},
        "requested_args": {"memo": {"edit": {"body": "none"}}},
        "requested_hours": 1,
    })
    await _act(human, (await human.get(grant_href)).json(), "approve")

    doc = (await agent.get(memo_href)).json()
    # supplying the forbidden argument is refused outright
    res = await _act(agent, doc, "edit", {"body": "sneaky"})
    assert res.status_code == 422
    # omitting it routes to a human, with the gap named
    res = await _act(agent, doc, "edit", {})
    assert res.status_code == 202
    approval = res.json()
    assert approval["data"]["missing"] == ["body"]

    h_appr = (await human.get(approval["self"])).json()
    await _act(human, h_appr, "approve",
               {"overrides": {"body": "Chosen by a person"}})
    a_appr = (await agent.get(approval["self"])).json()
    res = await _act(agent, a_appr, "run")
    assert res.status_code == 200
    assert (await human.get(memo_href)).json()["data"]["body"] == \
        "Chosen by a person"


async def test_approval_mode_create_composes(env):
    """Design §2: approval capture is a stage on the write pipeline, and
    create is a write — so it composes. v2 answered this path with prose
    ("Approval-mode create is not supported yet")."""
    engine, transport, human = env
    grant_href, token = await _mint(human)
    agent = _agent(transport, token)

    own = (await agent.get(grant_href)).json()
    await _act(agent, own, "request_access", {
        "task": "Propose memos for the family to approve.",
        "requested_fields": {"memo": {"title": "clear", "summary": "clear"}},
        "requested_actions": {"memo": {"create": "approval"}},
        "requested_hours": 2,
    })
    await _act(human, (await human.get(grant_href)).json(), "approve")

    # the agent's create becomes a pending approval, not a resource
    res = await agent.post("/api/memos",
                           json={"title": "Taco night shopping"},
                           headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 202, res.text
    approval = res.json()
    assert approval["kind"] == "approval_request"
    assert approval["state"] == "pending"
    listing = (await human.get("/api/memos")).json()
    assert listing["data"]["total"] == 0, "nothing created before approval"

    # human approves; the agent runs it; the memo exists, audited
    h_appr = (await human.get(approval["self"])).json()
    assert h_appr["data"]["target_action"] == "create"
    res = await _act(human, h_appr, "approve", {"overrides": {}})
    assert res.status_code == 200, res.text
    a_appr = (await agent.get(approval["self"])).json()
    res = await _act(agent, a_appr, "run")
    assert res.status_code == 200, res.text
    closed = (await human.get(approval["self"])).json()
    assert closed["data"]["outcome"] == "Ran successfully.", closed["data"]
    listing = (await human.get("/api/memos")).json()
    assert listing["data"]["total"] == 1
    assert listing["data"]["items"][0]["data"]["title"] == "Taco night shopping"


async def test_clear_reveals_nested_structure(env):
    """``clear`` grants the field's whole value, nested structure included.
    Sub-keys of a list-of-objects field are not kind-level fields — no grant
    could ever name them, so projecting them through the kind field map
    rendered every element as ``{}`` (a plan's days, a memo's steps)."""
    engine, transport, human = env
    grant_href, token = await _mint(human)
    res = await human.post("/api/memos", json={
        "title": "Taco prep", "secret": "pin 4321",
        "steps": [{"label": "thaw chicken"}, {"label": "make salsa",
                                              "done": True}],
    }, headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    memo_href = res.json()["self"]
    agent = _agent(transport, token)

    own = (await agent.get(grant_href)).json()
    await _act(agent, own, "request_access", {
        "task": "Track the prep steps.",
        "requested_fields": {"memo": {"title": "clear", "steps": "clear",
                                      "summary": "clear"}},
        "requested_hours": 1,
    })
    await _act(human, (await human.get(grant_href)).json(), "approve")

    doc = (await agent.get(memo_href)).json()
    assert doc["data"]["steps"] == [
        {"label": "thaw chicken", "done": False},
        {"label": "make salsa", "done": True},
    ]
    assert "secret" not in doc["data"], "ungranted siblings stay hidden"


async def test_grants_narrow_to_specific_resources(env):
    """Design §9: granularity is a selector. A grant over ONE memo grants
    nothing about its siblings — same kind, different id, default deny."""
    engine, transport, human = env
    grant_href, token = await _mint(human)
    ours = await _memo(human, title="Shared with Robo")
    theirs = await _memo(human, title="Private planning")
    agent = _agent(transport, token)

    own = (await agent.get(grant_href)).json()
    await _act(agent, own, "request_access", {
        "task": "Tend one memo only.",
        "requested_fields": {"memo": {"title": "clear", "summary": "clear"}},
        "requested_actions": {"memo": {"touch": "open"}},
        "requested_over": {"memo": [ours.rsplit("/", 1)[-1]]},
        "requested_hours": 2,
    })
    await _act(human, (await human.get(grant_href)).json(), "approve")

    # the selected memo: granted view and granted action
    doc = (await agent.get(ours)).json()
    assert doc["data"]["title"] == "Shared with Robo"
    assert "touch" in doc["actions"]
    assert (await _act(agent, doc, "touch")).status_code == 200

    # the sibling: same kind, not selected — default deny end to end
    sibling = (await agent.get(theirs)).json()
    assert sibling["data"] == {}
    assert sibling["summary"] == "memo (scoped view)"
    assert "touch" not in sibling["actions"]
    denied = await _act(agent, sibling, "touch")
    assert denied.status_code == 403

    # and the collection view narrows item by item, not kind by kind
    listing = (await agent.get("/api/memos")).json()
    by_self = {i["self"]: i for i in listing["data"]["items"]}
    assert by_self[ours]["data"].get("title") == "Shared with Robo"
    assert by_self[theirs]["data"] == {}


async def test_agent_reads_its_own_grant_and_the_send_back_note(env):
    """The agent negotiates with sight: it reads its OWN grant in the clear
    — task, request_summary, and a reviewer's send-back note — so a decline
    is a legible instruction, not a silent bounce."""
    engine, transport, human = env
    grant_href, token = await _mint(human)
    agent = _agent(transport, token)

    own = (await agent.get(grant_href)).json()
    # sight of one's own grant (the agent's negotiation surface)
    assert own["data"]["holder_name"] == "Robo"
    assert own["data"]["request_summary"] == "Nothing requested yet."

    await _act(agent, own, "request_access", {
        "task": "Tidy the family memo.",
        "requested_fields": {"memo": {"title": "clear", "secret": "hashed"}},
        "requested_actions": {"memo": {"touch": "open", "edit": "approval"}},
        "requested_hours": 6,
    })

    # the ask reads back as one legible line for the approver and the agent
    own = (await agent.get(grant_href)).json()
    assert own["data"]["task"] == "Tidy the family memo."
    assert own["data"]["request_summary"] == (
        "Read memo: secret (hashed), title · Do memo: edit (approval), touch "
        "· for 6h")

    # a human sends it back WITH a reason — the note lands on the grant
    await _act(human, (await human.get(grant_href)).json(), "deny",
               {"note": "Drop the edit action; touch is enough."})
    own = (await agent.get(grant_href)).json()
    assert own["state"] == "draft"
    assert own["data"]["review_note"] == "Drop the edit action; touch is enough."

    # a fresh ask supersedes the last verdict — the note clears
    await _act(agent, own, "request_access", {
        "task": "Tidy the family memo.",
        "requested_fields": {"memo": {"title": "clear"}},
        "requested_actions": {"memo": {"touch": "open"}},
        "requested_hours": 6,
    })
    own = (await agent.get(grant_href)).json()
    assert own["data"].get("review_note") is None


async def test_partial_approval_grants_less_than_asked(env):
    """Design D: the approver trims the ask instead of sending it back. An
    explicit map on approve overrides the request; the record of what was
    asked stays on requested_*, what's enforced is the trimmed grant."""
    engine, transport, human = env
    grant_href, token = await _mint(human)
    memo_href = await _memo(human)
    agent = _agent(transport, token)

    own = (await agent.get(grant_href)).json()
    await _act(agent, own, "request_access", {
        "task": "Tidy and rename the memo.",
        "requested_fields": {"memo": {"title": "clear", "summary": "clear"}},
        "requested_actions": {"memo": {"touch": "open", "edit": "open"}},
        "requested_hours": 24,
    })

    # the approve form opens prefilled with the ask (design D): the server
    # stamps the request onto the action input's defaults, so the approver
    # edits down instead of retyping. The default rides the OUTER property
    # even though each map field is optional (anyOf) — the UI reads it there.
    grant_doc = (await human.get(grant_href)).json()
    approve_in = grant_doc["actions"]["approve"]["input"]["properties"]
    assert approve_in["requested_fields"].get("default") == {
        "memo": {"title": "clear", "summary": "clear"}}
    assert approve_in["requested_actions"].get("default") == {
        "memo": {"touch": "open", "edit": "open"}}
    assert approve_in["requested_hours"].get("default") == 24

    # the human grants touch but NOT edit, and shortens the window
    approved = await _act(human, grant_doc, "approve", {
        "requested_actions": {"memo": {"touch": "open"}},
        "requested_hours": 2,
    })
    assert approved.status_code == 200 and approved.json()["state"] == "granted"

    # the record of the ask is intact; enforcement is the trimmed grant
    grant_doc = (await human.get(grant_href)).json()
    assert grant_doc["data"]["requested_actions"] == {
        "memo": {"touch": "open", "edit": "open"}}
    assert grant_doc["data"]["granted_actions"] == {"memo": {"touch": "open"}}

    # touch was granted; edit was trimmed away — default-deny end to end
    doc = (await agent.get(memo_href)).json()
    assert "touch" in doc["actions"] and "edit" not in doc["actions"]
    assert (await _act(agent, doc, "touch")).status_code == 200
    assert (await _act(agent, doc, "edit", {"body": "x"})).status_code == 403


async def test_bare_approve_still_grants_the_whole_ask(env):
    """The null-body approve (a programmatic approve, or the UI's unedited
    form falling through) grants exactly the request — the prefill default
    is 'grant what was asked'."""
    engine, transport, human = env
    grant_href, token = await _mint(human)
    memo_href = await _memo(human)
    agent = _agent(transport, token)

    own = (await agent.get(grant_href)).json()
    await _act(agent, own, "request_access", {
        "task": "Tidy the memo.",
        "requested_fields": {"memo": {"title": "clear"}},
        "requested_actions": {"memo": {"touch": "open"}},
        "requested_hours": 5,
    })
    await _act(human, (await human.get(grant_href)).json(), "approve")

    grant_doc = (await human.get(grant_href)).json()
    assert grant_doc["data"]["granted_actions"] == {"memo": {"touch": "open"}}
    doc = (await agent.get(memo_href)).json()
    assert doc["data"]["title"] == "Groceries note"
    assert (await _act(agent, doc, "touch")).status_code == 200


async def test_grantable_catalog_is_the_ask_menu(env):
    """Design C: a bare token reads /-/grantable — the public menu of what
    it CAN request. Domain kinds carry their fields (with clear|hashed) and
    actions (with args); the negotiation kinds themselves are omitted."""
    engine, transport, human = env
    _, token = await _mint(human)
    agent = _agent(transport, token)

    # discovery advertises the menu, and a bare token may read it
    index = (await agent.get("/api/.well-known/waymark")).json()
    assert index["grantable"] == "/api/-/grantable"
    cat = (await agent.get("/api/-/grantable")).json()

    memo = cat["kinds"]["memo"]
    assert memo["collection"] == "/api/memos"
    fields = {f["name"]: f for f in memo["fields"]}
    assert "title" in fields and fields["title"]["modes"] == ["clear", "hashed"]
    actions = {a["name"]: a for a in memo["actions"]}
    assert "touch" in actions and actions["touch"]["modes"] == ["open", "approval"]
    # an action's arguments ride the entry (so requested_args is a menu too)
    edit = actions["edit"]
    assert any(arg["name"] == "body" for arg in edit.get("args", []))

    # the negotiation surface itself is not grantable
    assert "grant" not in cat["kinds"]
    assert "approval_request" not in cat["kinds"]


async def test_scoped_token_streams_only_its_own_grant(env):
    """Design E: a token may await its grant's decision by push — it streams
    its OWN grant's events — but the workspace pulse and other resources
    stay refused (the refusal returns immediately; the 200 stream itself is
    covered over a real socket)."""
    engine, transport, human = env
    grant_href, token = await _mint(human)
    memo_href = await _memo(human)
    agent = _agent(transport, token)

    # someone else's resource: refused (not the agent's negotiation surface)
    assert (await agent.get(memo_href + "/-/events")).status_code == 403
    # the workspace firehose: still refused for a scoped token
    assert (await agent.get("/api/-/events")).status_code == 403
    # a foreign grant id: refused (owns() is an exact match)
    other = grant_href.rsplit("/", 1)[0] + "/" + uuid.uuid4().hex
    assert (await agent.get(other + "/-/events")).status_code == 403
