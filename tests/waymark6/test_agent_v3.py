"""First-class v2 agent client: draft awareness and effort-annotated tools.

The scenario the draft sub-resource exists for (design §4): a human leaves
half-written effort in a shared draft; an agent reads it from the envelope,
continues it through the same write path, and either party commits — one
accountable transition, no effort stranded anywhere.
"""
from __future__ import annotations

import os
import uuid
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark6
from waymark6 import Ctx, DraftPolicy, Edit, Resource, Safety, action
from waymark6.client import AgentClient, mcp_tools
from waymark6.server.bus import InProcessBus
from waymark6.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


class NoteState(StrEnum):
    OPEN = "open"


class NoteData(BaseModel):
    title: str = Field(min_length=1, max_length=120)
    body: str | None = Field(default=None, json_schema_extra={
        "x-display": {"widget": "prose"}})


class BodyInput(BaseModel):
    body: str = Field(min_length=1, json_schema_extra={
        "x-display": {"widget": "prose"}})


class Note(Resource):
    kind = "note"
    State = NoteState
    Data = NoteData

    initial = NoteState.OPEN
    terminal: set = set()

    summary = "{data.title} · {state.label}"

    @action(from_=NoteState.OPEN, to=NoteState.OPEN, input=BodyInput,
            edit=Edit(prefill=("body",), draft=DraftPolicy(shared=True)),
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Edit body"))
    async def edit(self, inp: BodyInput, ctx: Ctx) -> None:
        self.data.body = inp.body

    @action(from_=NoteState.OPEN, to=NoteState.OPEN,
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Touch"))
    async def touch(self, inp: None, ctx: Ctx) -> None:
        pass


def _headers(pid: str, ptype: str) -> dict[str, str]:
    return {"X-Principal-Id": pid, "X-Principal-Type": ptype,
            "X-Principal-Display": pid.title()}


@pytest.fixture
async def env():
    from waymark6.server.engine import header_principal

    engine = waymark6.Engine(resources=[Note], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    transport = ASGITransport(app=app)
    human = AsyncClient(transport=transport, base_url="http://t",
                        headers=_headers("dana", "human"))
    agent = AgentClient(http=AsyncClient(transport=transport, base_url="http://t"),
                        headers=_headers("agent-1", "agent"))
    try:
        yield agent, human
    finally:
        await human.aclose()
        await agent.aclose()
        await engine.shutdown()


async def test_agent_continues_a_human_draft(env):
    agent, human = env

    created = await human.post(
        "/api/notes", json={"title": "Packing list"},
        headers={"Idempotency-Key": uuid.uuid4().hex})
    assert created.status_code == 201, created.text
    self_href = created.json()["self"]

    # the human leaves half-written effort in the shared draft
    saved = await human.put(f"{self_href}/-/edit/draft",
                            json={"body": "- tent\n- sleeping bags\n"})
    assert saved.status_code == 200, saved.text

    # the agent discovers it from the envelope: advert first, then the truth
    doc = await agent.fetch(self_href)
    advert = doc.actions["edit"]["draft"]
    assert advert["shared"] is True and advert["exists"] is True

    draft = await agent.draft(doc, "edit")
    assert draft.kind == "draft" and draft.state == "open"
    human_text = draft.data["values"]["body"]
    assert "tent" in human_text
    rev_before = draft.data["revs"]["body"]

    # …continues it through the same write path (the drain rule both ways)
    draft = await agent.save_draft(doc, "edit",
                                   {"body": human_text + "- headlamps\n"})
    assert draft.data["revs"]["body"] == rev_before + 1
    assert draft.data["authors"]["body"]["id"] == "agent-1"

    # the human sees the agent's help in the shared draft
    fresh = await human.get(f"{self_href}/-/edit/draft")
    assert "headlamps" in fresh.json()["data"]["values"]["body"]

    # either party commits: one accountable transition consumes the draft
    doc = await agent.fetch(self_href)
    final = draft.data["values"]["body"]
    after = await agent.act(doc, "edit", {"body": final})
    assert after.data["body"] == final
    emptied = await agent.draft(after, "edit")
    assert emptied.data["values"] == {}


async def test_agent_discards_via_the_draft_envelope(env):
    agent, human = env
    created = await human.post(
        "/api/notes", json={"title": "Scratch"},
        headers={"Idempotency-Key": uuid.uuid4().hex})
    self_href = created.json()["self"]

    doc = await agent.fetch(self_href)
    await agent.save_draft(doc, "edit", {"body": "false start"})
    await agent.discard_draft(doc, "edit")
    emptied = await agent.draft(doc, "edit")
    assert emptied.data["values"] == {}


async def test_mcp_tools_carry_effort_and_draftability(env):
    agent, human = env
    created = await human.post(
        "/api/notes", json={"title": "Tooling"},
        headers={"Idempotency-Key": uuid.uuid4().hex})
    doc = await agent.fetch(created.json()["self"])

    tools = {t["name"]: t for t in mcp_tools(doc)}
    edit = tools["note.edit"]
    assert "[composition:" in edit["description"]
    assert "[draftable:" in edit["description"]
    touch = tools["note.touch"]
    assert "[assent:" in touch["description"]
