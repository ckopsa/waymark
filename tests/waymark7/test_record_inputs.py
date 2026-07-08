"""record=Inputs() — declared retention for the transition log (design
7.0 §5).

Transition rows have stored ``input_digest`` since 5.0 (v3 friction #8);
an action that declares ``record=Inputs()`` stores the validated payload
itself. The default stays digest-only; the definition kind's own
transitions record unconditionally (the law does not get privacy from
its subjects); an input-less action refuses the declaration at import.
Exposure rides the transition surface (SSE frames / event_payload) for
full-visibility principals only — the stream is not projected per row,
which is the recorded deviation.
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
from waymark7 import Ctx, DefinitionError, Inputs, Resource, Safety, action
from waymark7.server.bus import InProcessBus
from waymark7.server.engine import header_principal
from waymark7.server.events import event_payload
from waymark7.testing import per_worker_dsn

from .test_definition_lifecycle import TOL_V1, TOL_V2, make_wb

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

ELENA = {"X-Principal-Id": "elena", "X-Principal-Display": "Elena V."}


class NoteState(StrEnum):
    OPEN = "open"
    APPROVED = "approved"
    REJECTED = "rejected"


class ReasonInput(BaseModel):
    reason: str = Field(min_length=1, max_length=200)


class NoteData(BaseModel):
    title: str = Field(min_length=1, max_length=80)


class Note(Resource):
    kind = "rinote"
    State = NoteState
    Data = NoteData
    initial = NoteState.OPEN
    terminal = {NoteState.APPROVED, NoteState.REJECTED}
    summary = "{data.title} · {state.label}"

    # compliance spelling (design §5): the reviewer's words must be
    # readable a year later — the transition row keeps the payload
    @action(from_=NoteState.OPEN, to=NoteState.REJECTED,
            input=ReasonInput, record=Inputs(),
            safety=Safety(idempotent=False, reversible=False, confirm=True,
                          consequence="The note closes as rejected."))
    async def reject(self, inp: ReasonInput, ctx: Ctx) -> None:
        pass

    # same input shape, no declaration: the digest-only default
    @action(from_=NoteState.OPEN, to=NoteState.APPROVED,
            input=ReasonInput,
            safety=Safety(idempotent=False, reversible=False, confirm=True,
                          consequence="The note closes as approved."))
    async def approve(self, inp: ReasonInput, ctx: Ctx) -> None:
        pass


async def _boot(resources, *, drop: bool = False):
    engine = waymark7.Engine(resources=resources, storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    if drop:
        await engine.storage.drop_all()
    await engine.startup()
    return engine


def _client(engine) -> AsyncClient:
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    return AsyncClient(transport=ASGITransport(app=app),
                       base_url="http://t", headers=ELENA)


async def _post(client, href, json=None):
    return await client.post(href, json=json,
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def test_declared_action_records_and_default_keeps_digest():
    engine = await _boot([Note], drop=True)
    client = _client(engine)
    try:
        a = (await _post(client, "/api/rinotes", {"title": "a"})).json()
        b = (await _post(client, "/api/rinotes", {"title": "b"})).json()

        res = await _post(client, f"{a['self']}/-/reject",
                          {"reason": "the numbers do not tie"})
        assert res.status_code == 200, res.text
        res = await _post(client, f"{b['self']}/-/approve",
                          {"reason": "ties to the penny"})
        assert res.status_code == 200, res.text

        async with engine.storage.session() as s:
            rejected = await engine.storage.last_transition(
                s, "rinote", a["self"].rsplit("/", 1)[-1])
            approved = await engine.storage.last_transition(
                s, "rinote", b["self"].rsplit("/", 1)[-1])

        # the declared action's payload is on its transition row,
        # alongside the digest every row always carried
        assert rejected.action == "reject"
        assert rejected.inputs == {"reason": "the numbers do not tie"}
        assert rejected.input_digest, "the digest stays — inputs is additive"

        # the digest-only default is unchanged: same shape, no declaration
        assert approved.action == "approve"
        assert approved.inputs is None
        assert approved.input_digest

        # exposure (design §5): the transition surface carries a recorded
        # input for full-visibility readers; the digest-era shape without
        payload = event_payload(rejected, engine.registry, "/api",
                                include_inputs=True)
        assert payload["inputs"] == {"reason": "the numbers do not tie"}
        bare = event_payload(rejected, engine.registry, "/api")
        assert "inputs" not in bare, \
            "a reader without full visibility sees the digest-era frame"
        assert "inputs" not in event_payload(approved, engine.registry,
                                             "/api", include_inputs=True), \
            "nothing recorded, nothing exposed"
    finally:
        await client.aclose()
        await engine.shutdown()


async def test_definition_transitions_record_unconditionally():
    """The law does not get privacy from its subjects (design §5): every
    definition transition stores its validated payload — the revise that
    wrote revision 2 carries the law it wrote."""
    e1 = await _boot([make_wb(TOL_V1)], drop=True)
    await e1.shutdown()
    e2 = await _boot([make_wb(TOL_V2)])
    try:
        async with e2.storage.session() as s:
            revs, _ = await e2.storage.query(
                s, "definition", filters={"target_kind": "lwb"},
                sort="revision", page_size=10, page_number=1)
            transitions = await e2.storage.transitions_since(
                s, 0, kinds=["definition"], limit=500)
        rev2 = revs[-1]
        assert rev2.data.revision == 2
        revise = next(t for t in transitions
                      if t.resource_id == rev2.id and t.from_state == "")
        assert revise.action == "revise"
        assert revise.inputs is not None, \
            "definition transitions record unconditionally"
        assert revise.inputs["fingerprint_hash"] == \
            rev2.data.fingerprint_hash
        assert revise.inputs["target_kind"] == "lwb"
    finally:
        await e2.shutdown()


def test_record_without_input_model_is_a_definition_error():
    with pytest.raises(DefinitionError, match="record=Inputs"):
        @action(from_=NoteState.OPEN, to=NoteState.APPROVED,
                record=Inputs(),
                safety=Safety(idempotent=True, reversible=True,
                              confirm=False))
        async def bare(self, inp: None, ctx: Ctx) -> None:
            pass


def test_record_takes_an_inputs_declaration():
    with pytest.raises(DefinitionError, match="Inputs"):
        @action(from_=NoteState.OPEN, to=NoteState.APPROVED,
                input=ReasonInput, record=True,  # type: ignore[arg-type]
                safety=Safety(idempotent=True, reversible=True,
                              confirm=False))
        async def wrong(self, inp: ReasonInput, ctx: Ctx) -> None:
            pass
