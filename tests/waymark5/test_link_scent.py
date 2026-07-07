"""Link scent and warning remedies on the wire (§4, design E1).

A declared link may invite co-presentation (``embed=True``) and wear the
current value of a Data field as its ``badge`` — the count rides the link,
so a client can render "Breaks · 3" before the traversal, not after. A
badge naming a field Data does not have is a DefinitionError at import.
And a warning-severity guard's declared ``remedies`` ride every place the
warning is rendered: the action entry, the 409 warning-required problem,
and the dry-run body — the same pointer refusals already carry.
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
from waymark5 import Ctx, Resource, Safety, action, link
from waymark5.core.guards import Guard
from waymark5.core.types import Allow, Deny, DefinitionError
from waymark5.server.bus import InProcessBus
from waymark5.server.engine import header_principal
from waymark5.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


class NoteState(StrEnum):
    OPEN = "open"


class NoteData(BaseModel):
    title: str = Field(min_length=1, max_length=120)
    open_items: int | None = Field(
        default=None, description="How many items are still open")


async def _check_tidy(r, inp, ctx: Ctx):
    if r.data.open_items:
        return Deny(vars={"count": r.data.open_items})
    return Allow()

untidy = Guard(
    name="untidy", severity="warning",
    explain="{count} item(s) still open — archiving now buries them.",
    vars=("count",),
    remedies=("note.touch",),
    check=_check_tidy,
)


async def _check_titled(r, inp, ctx: Ctx):
    if r.data.title.strip():
        return Allow()
    return Deny()

blank_title = Guard(
    name="blank_title", severity="warning",
    explain="The title is blank — the archive lists it as nothing.",
    check=_check_titled,
)


class Note(Resource):
    kind = "note"
    State = NoteState
    Data = NoteData
    initial = NoteState.OPEN
    terminal: set = set()
    summary = "{data.title} · {state.label}"

    links = (
        link("items", kind="note_collection",
             href="/notes?ref={id}",
             summary="This note's follow-ups",
             embed=True, badge="open_items"),
        link("plain", kind="note_collection", href="/notes"),
    )

    @action(from_=NoteState.OPEN, to=NoteState.OPEN,
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Touch"))
    async def touch(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=NoteState.OPEN, to=NoteState.OPEN,
            guards=[untidy, blank_title],
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Archive"))
    async def archive(self, inp: None, ctx: Ctx) -> None:
        pass


@pytest.fixture
async def env():
    engine = waymark5.Engine(resources=[Note], storage=TEST_DSN,
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


async def _note(client, title="Groceries", open_items=None) -> dict:
    res = await client.post("/api/notes",
                            json={"title": title, "open_items": open_items},
                            headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    return res.json()


async def test_badge_and_embed_ride_the_link(env):
    engine, client = env
    doc = await _note(client, open_items=3)
    entry = doc["links"]["items"]
    assert entry["embed"] is True
    assert entry["badge"] == 3
    assert entry["kind"] == "note_collection"
    assert entry["href"].endswith(f"/notes?ref={doc['self'].rsplit('/', 1)[-1]}")


async def test_badge_omitted_when_value_is_none(env):
    """None is absence, not a zero to render — and an undeclared link
    carries neither key."""
    engine, client = env
    doc = await _note(client, open_items=None)
    items = doc["links"]["items"]
    assert "badge" not in items
    assert items["embed"] is True, "embed is declaration, not data"
    plain = doc["links"]["plain"]
    assert "embed" not in plain and "badge" not in plain


async def test_badge_naming_no_data_field_is_a_definition_error():
    with pytest.raises(DefinitionError, match=r"badge='open_itmes'"):
        class Typo(Resource):
            kind = "typo"
            State = NoteState
            Data = NoteData
            initial = NoteState.OPEN
            terminal: set = set()
            summary = "{data.title} · {state.label}"

            links = (
                link("items", kind="typo_collection",
                     href="/typos?ref={id}", badge="open_itmes"),
            )

            @action(from_=NoteState.OPEN, to=NoteState.OPEN,
                    safety=Safety(idempotent=True, reversible=False,
                                  confirm=False),
                    display=dict(label="Touch"))
            async def touch(self, inp: None, ctx: Ctx) -> None:
                pass


async def test_warning_entries_carry_declared_remedies(env):
    """The remedy rides everywhere the warning does: the advertised entry,
    the 409 problem, the dry-run body — and a guard that declared none
    adds no empty key."""
    engine, client = env
    doc = await _note(client, title="  ", open_items=2)
    warnings = doc["actions"]["archive"]["warnings"]
    by_name = {w["name"]: w for w in warnings}
    assert by_name["untidy"]["remedies"] == ["note.touch"]
    assert "remedies" not in by_name["blank_title"]

    res = await client.post(f"{doc['self']}/-/archive",
                            headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 409, res.text
    problem = res.json()
    assert problem["type"].endswith("warning-required")
    by_name = {w["name"]: w for w in problem["warnings"]}
    assert by_name["untidy"]["remedies"] == ["note.touch"]
    assert "remedies" not in by_name["blank_title"]

    res = await client.post(f"{doc['self']}/-/archive?dry_run=1")
    assert res.status_code == 200, res.text
    by_name = {w["name"]: w for w in res.json()["warnings"]}
    assert by_name["untidy"]["remedies"] == ["note.touch"]
    assert "remedies" not in by_name["blank_title"]
