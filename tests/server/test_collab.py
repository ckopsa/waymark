"""The collab seam (§2.2): the channel is live, and everything drains.

These tests use starlette's sync TestClient because httpx's ASGITransport
cannot speak WebSockets; everything else in tests/server stays async.
"""
import asyncio
import os
import uuid
from enum import StrEnum

import pytest
from fastapi import FastAPI
from pydantic import BaseModel, Field
from starlette.testclient import TestClient

import waymark
from waymark import Ctx, Resource, action
from waymark.server.engine import header_principal
from waymark.testing import per_worker_dsn

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

DANA = {"X-Principal-Id": "dana", "X-Principal-Display": "Dana K."}
COLTON = {"X-Principal-Id": "colton", "X-Principal-Display": "Colton"}


def key() -> dict:
    return {"Idempotency-Key": uuid.uuid4().hex}


class NoteState(StrEnum):
    OPEN = "open"
    PUBLISHED = "published"


class NoteData(BaseModel):
    title: str = Field(max_length=80, default="untitled")
    body: str = Field(max_length=200, default="")


class ComposeInput(BaseModel):
    body: str = Field(max_length=200)
    title: str | None = Field(default=None, max_length=80)


class Note(Resource):
    kind = "note"
    State = NoteState
    Data = NoteData
    initial = NoteState.OPEN
    terminal = {NoteState.PUBLISHED}
    summary = "Note · {data.title} · {state.label}"

    @action(from_=NoteState.OPEN, to=NoteState.PUBLISHED, input=ComposeInput,
            draft=True, collab=True, prefill=("body", "title"),
            requires_if_match=True,
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Publish", style="primary",
                         description="Publishes the note as it stands."))
    async def publish(self, inp: ComposeInput, ctx: Ctx) -> None:
        self.data.body = inp.body
        if inp.title is not None:
            self.data.title = inp.title


@pytest.fixture
def client():
    # reset tables in a throwaway loop; TestClient runs its own via portal
    async def _reset():
        eng = waymark.Engine(resources=[Note], storage=TEST_DSN)
        await eng.storage.drop_all()
        await eng.storage.engine.dispose()

    asyncio.run(_reset())
    engine = waymark.Engine(resources=[Note], storage=TEST_DSN,
                            principal=header_principal)
    app = FastAPI(lifespan=engine.lifespan)
    app.include_router(engine.router, prefix="/api")
    with TestClient(app) as c:
        yield c


def make_note(client) -> dict:
    res = client.post("/api/notes", json={"title": "Trip plan"},
                      headers={**DANA, **key()})
    assert res.status_code == 201, res.text
    return res.json()


def ws_url(href: str, pid: str, display: str) -> str:
    return f"{href}?principal-id={pid}&principal-display={display}"


def test_collab_advertised_on_the_draft_entry(client):
    doc = make_note(client)
    draft = doc["actions"]["publish"]["draft"]
    assert draft["href"] == f"{doc['self']}/-/publish/draft"
    assert draft["collab"] == {"href": f"{doc['self']}/-/publish/draft/collab",
                               "protocol": "waymark-relay/1"}


def test_drain_rule_join_write_drop(client):
    """Effort spent through the channel lands in the draft — a dropped
    connection strands nothing, and any collaborator sees it (shared)."""
    doc = make_note(client)
    draft = doc["actions"]["publish"]["draft"]
    with client.websocket_connect(
            ws_url(draft["collab"]["href"], "dana", "Dana")) as ws:
        state = ws.receive_json()
        assert state["type"] == "state"
        assert state["values"] == {} and state["saved_at"] is None
        assert [p["id"] for p in state["participants"]] == ["dana"]
        ws.send_json({"type": "update", "values": {"body": "Half-written…"}})
        saved = ws.receive_json()
        assert saved["type"] == "saved" and saved["saved_at"]
    # connection gone; the effort is server-state, visible to another
    # principal through the plain draft GET
    res = client.get(draft["href"], headers=COLTON)
    assert res.status_code == 200
    body = res.json()
    assert body["values"] == {"body": "Half-written…"}
    assert body["stale"] is False
    # and in the envelope, for everyone (collab drafts are shared)
    doc2 = client.get(doc["self"], headers=COLTON).json()
    assert doc2["actions"]["publish"]["draft"]["values"] == {
        "body": "Half-written…"}


def test_update_merges_and_null_clears(client):
    doc = make_note(client)
    draft = doc["actions"]["publish"]["draft"]
    with client.websocket_connect(
            ws_url(draft["collab"]["href"], "dana", "Dana")) as ws:
        ws.receive_json()  # state
        ws.send_json({"type": "update", "values": {"body": "v1"}})
        ws.receive_json()  # saved
        ws.send_json({"type": "update", "values": {"title": "Tuesday"}})
        ws.receive_json()  # saved — merged, not replaced
        ws.send_json({"type": "update", "values": {"body": None}})
        ws.receive_json()  # saved — null clears the field
    res = client.get(draft["href"], headers=DANA)
    assert res.json()["values"] == {"title": "Tuesday"}


def test_unknown_field_refused_like_a_draft_put(client):
    doc = make_note(client)
    draft = doc["actions"]["publish"]["draft"]
    with client.websocket_connect(
            ws_url(draft["collab"]["href"], "dana", "Dana")) as ws:
        ws.receive_json()  # state
        ws.send_json({"type": "update", "values": {"nope": 1}})
        err = ws.receive_json()
        assert err["type"] == "error"
        assert err["errors"] == {"nope": ["unknown field"]}
    res = client.get(draft["href"], headers=DANA)
    assert res.status_code == 204  # nothing drained


def test_presence_and_broadcast_between_participants(client):
    doc = make_note(client)
    href = doc["actions"]["publish"]["draft"]["collab"]["href"]
    with client.websocket_connect(ws_url(href, "dana", "Dana")) as a:
        assert a.receive_json()["type"] == "state"
        with client.websocket_connect(ws_url(href, "colton", "Colton")) as b:
            st = b.receive_json()
            assert st["type"] == "state"
            assert {p["id"] for p in st["participants"]} == {"dana", "colton"}
            joined = a.receive_json()
            assert joined["type"] == "presence" and joined["event"] == "joined"
            assert joined["actor"]["id"] == "colton"
            b.send_json({"type": "update", "values": {"title": "Tue → Tue"}})
            assert b.receive_json()["type"] == "saved"
            upd = a.receive_json()
            assert upd["type"] == "update"
            assert upd["values"] == {"title": "Tue → Tue"}
            assert upd["actor"] == {"id": "colton", "type": "human",
                                    "display": "Colton"}
        left = a.receive_json()
        assert left["type"] == "presence" and left["event"] == "left"
        assert [p["id"] for p in left["participants"]] == ["dana"]


def test_plain_put_fallback_broadcasts_into_the_room(client):
    """A client that can't speak the channel still lands in the shared
    draft — and the room sees the update."""
    doc = make_note(client)
    draft = doc["actions"]["publish"]["draft"]
    with client.websocket_connect(
            ws_url(draft["collab"]["href"], "dana", "Dana")) as ws:
        ws.receive_json()  # state
        res = client.put(draft["href"], json={"body": "from a plain client"},
                         headers=COLTON)
        assert res.status_code == 200
        upd = ws.receive_json()
        assert upd["type"] == "update"
        assert upd["values"] == {"body": "from a plain client"}
        assert upd["actor"]["id"] == "colton"


def test_invoke_consumes_draft_and_closes_the_room(client):
    doc = make_note(client)
    entry = doc["actions"]["publish"]
    with client.websocket_connect(
            ws_url(entry["draft"]["collab"]["href"], "dana", "Dana")) as ws:
        ws.receive_json()  # state
        ws.send_json({"type": "update", "values": {"body": "Final text."}})
        ws.receive_json()  # saved
        res = client.post(entry["href"], json={"body": "Final text."},
                          headers={**DANA, "If-Match": doc["meta"]["etag"]})
        assert res.status_code == 200, res.text
        closed = ws.receive_json()
        assert closed == {"type": "closed", "reason": "consumed"}
    res = client.get(entry["draft"]["href"], headers=DANA)
    assert res.status_code == 204  # the effort landed; the draft is gone


def test_discard_closes_the_room(client):
    doc = make_note(client)
    draft = doc["actions"]["publish"]["draft"]
    with client.websocket_connect(
            ws_url(draft["collab"]["href"], "dana", "Dana")) as ws:
        ws.receive_json()  # state
        ws.send_json({"type": "update", "values": {"body": "meh"}})
        ws.receive_json()  # saved
        res = client.delete(draft["href"], headers=COLTON)
        assert res.status_code == 204
        assert ws.receive_json() == {"type": "closed", "reason": "discarded"}


def test_late_joiner_gets_current_truth(client):
    doc = make_note(client)
    href = doc["actions"]["publish"]["draft"]["collab"]["href"]
    with client.websocket_connect(ws_url(href, "dana", "Dana")) as a:
        a.receive_json()
        a.send_json({"type": "update", "values": {"body": "already here"}})
        a.receive_json()
        with client.websocket_connect(ws_url(href, "colton", "Colton")) as b:
            st = b.receive_json()
            assert st["type"] == "state"
            assert st["values"] == {"body": "already here"}
            a.receive_json()  # colton's join presence


def test_non_collab_action_has_no_channel(client):
    doc = make_note(client)
    with pytest.raises(Exception):  # closed 4404 before accept
        with client.websocket_connect(
                f"{doc['self']}/-/nonexistent/draft/collab?principal-id=dana"):
            pass
