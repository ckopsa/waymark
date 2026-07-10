"""Declared touches (design E8): a transition says what it spans — the
declaration renders on the effect, the ctx enforces it, and the log
carries one correlated story. The carve-out saga, the transfer twin,
and the bulk-UPDATE misattribution, resolved by two verbs.
"""
from __future__ import annotations

import os
import uuid
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark9
from waymark9 import (
    Acknowledged, Advances, Creates, Ctx, DefinitionError, Delegated,
    Ref, RefField, Registry, Resource, Safety, action, filterable,
)
from waymark9.core import checks
from waymark9.server.bus import InProcessBus
from waymark9.server.engine import header_principal
from waymark9.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


class RowState(StrEnum):
    OPEN = "open"
    DONE = "done"


class RowData(BaseModel):
    event_id: Ref["xevent"] = RefField()
    amount: float = Field(default=0, ge=0)


class ReassignInput(BaseModel):
    event_id: Ref["xevent"] = RefField(min_length=1)


class XRow(Resource):
    kind = "xrow"
    State = RowState
    Data = RowData
    initial = RowState.OPEN
    terminal = {RowState.DONE}
    summary = "Row of {data.amount} · {state.label}"
    filterable = filterable(event_id=filterable.Eq,
                            state=filterable.Eq | filterable.In)

    @action(from_=RowState.OPEN, to=RowState.OPEN,
            input=ReassignInput,
            edit=waymark9.Edit(
                prefill=("event_id",), fence=False,
                unfenced_reason="Reassignment is driven by the parent's "
                                "carve under its own transaction; there is "
                                "no human form to clobber."),
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Reassign"))
    async def reassign(self, inp: ReassignInput, ctx: Ctx) -> None:
        self.data.event_id = inp.event_id

    @action(from_=RowState.OPEN, to=RowState.DONE,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Completion is the record; nothing is lost.")),
            display=dict(label="Done"))
    async def complete(self, inp: None, ctx: Ctx) -> None:
        pass


class EventState(StrEnum):
    OPEN = "open"


class XEventData(BaseModel):
    total: float = Field(default=0, ge=0)
    twin: Ref["xevent"] | None = RefField(default=None, raw=True)


class CarveInput(BaseModel):
    wire_ids: list[str] = Field(min_length=1, max_length=50)


class XEvent(Resource):
    kind = "xevent"
    State = EventState
    Data = XEventData
    initial = EventState.OPEN
    terminal: set = set()
    summary = "Event of {data.total} · {state.label}"

    @action(from_=EventState.OPEN, to=EventState.OPEN,
            input=CarveInput,
            touches=(Creates("xevent"), Advances("xrow", "reassign")),
            safety=Safety(idempotent=False, reversible=False, confirm=True,
                          consequence="The selected rows move to a new "
                                      "event; this one's total shrinks."),
            display=dict(label="Carve out"))
    async def carve_out(self, inp: CarveInput, ctx: Ctx) -> None:
        moved = 0.0
        for row_id in inp.wire_ids:  # re-read under our own transaction
            row = await ctx.read("xrow", row_id)
            moved += row.data.amount
        child = await ctx.create("xevent", {"total": moved})
        child_id = child["self"].rsplit("/", 1)[-1]
        for row_id in inp.wire_ids:  # each move is the row's own transition
            await ctx.invoke("xrow", row_id, "reassign",
                             {"event_id": child_id})
        self.data.total -= moved

    @action(from_=EventState.OPEN, to=EventState.OPEN,
            touches=(Advances("xrow", "complete", may=True),),
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Completes only rows still open; done rows "
                              "are untouched.")),
            display=dict(label="Complete all"))
    async def complete_all(self, inp: None, ctx: Ctx) -> None:
        rows = await ctx.find("xrow", event_id=self.id, state="open",
                              limit=500)
        for row in rows:
            await ctx.invoke("xrow", row.id, "complete")

    @action(from_=EventState.OPEN, to=EventState.OPEN,
            touches=(Creates("xevent"),),
            safety=Safety(idempotent=False, reversible=False, confirm=True,
                          consequence="A linked twin event is created."),
            display=dict(label="Book pair"))
    async def book_pair(self, inp: None, ctx: Ctx) -> None:
        twin = await ctx.create("xevent", {"total": self.data.total,
                                           "twin": self.id})
        self.data.twin = twin["self"].rsplit("/", 1)[-1]

    @action(from_=EventState.OPEN, to=EventState.OPEN,
            safety=Safety(idempotent=False, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "A deliberately undeclared write, for the "
                              "enforcement test.")),
            display=dict(label="Sneak"))
    async def sneak(self, inp: None, ctx: Ctx) -> None:
        await ctx.create("xevent", {"total": 1})  # undeclared: must refuse


@pytest.fixture
async def env():
    engine = waymark9.Engine(resources=[XEvent, XRow], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t",
                         headers={"X-Principal-Id": "fern",
                                  "X-Principal-Display": "Fern"})
    try:
        yield engine, client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _post(client, href, json=None):
    return await client.post(href, json=json,
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def _event(client, total=0.0) -> dict:
    res = await _post(client, "/api/xevents", {"total": total})
    assert res.status_code == 201, res.text
    return res.json()


async def _row(client, event_id, amount) -> dict:
    res = await _post(client, "/api/xrows",
                      {"event_id": event_id, "amount": amount})
    assert res.status_code == 201, res.text
    return res.json()


def _id(doc) -> str:
    return doc["self"].rsplit("/", 1)[-1]


async def test_carve_out_is_one_correlated_story(env):
    """The 190-line saga as a declared transition: peer created, each row
    moved through its OWN action, parent decremented — one transaction,
    one correlation, every row's log entry by the invoking actor."""
    engine, client = env
    parent = await _event(client, 100.0)
    r1 = await _row(client, _id(parent), 30.0)
    r2 = await _row(client, _id(parent), 20.0)

    res = await _post(client, f"{parent['self']}/-/carve_out",
                      {"wire_ids": [_id(r1), _id(r2)]})
    assert res.status_code == 200, res.text
    assert res.json()["data"]["total"] == 50.0

    async with engine.storage.session() as s:
        root = await engine.storage.last_transition(s, "xevent", _id(parent))
        story = await engine.storage.transitions_by_correlation(
            s, root.correlation_id)
    assert {(t.kind, t.action) for t in story} \
        == {("xevent", "carve_out"), ("xevent", "create"),
            ("xrow", "reassign")}
    assert all(t.actor_id == "fern" for t in story), \
        "every touched row is the invoker's own doing"

    moved = (await client.get(f"/api/xrows?event_id={_id(r1)}")).json()
    r1_doc = (await client.get(r1["self"])).json()
    child_id = r1_doc["data"]["event_id"]
    assert child_id != _id(parent), "the row moved"
    child = (await client.get(f"/api/xevents/{child_id}")).json()
    assert child["data"]["total"] == 50.0


async def test_undeclared_writes_refuse_naming_the_declaration(env):
    """Composition's check= is closed for the write path: a handler whose
    writes out-run its advertisement is a definition error, and the
    transaction leaves nothing behind."""
    engine, client = env
    parent = await _event(client, 10.0)

    with pytest.raises(DefinitionError) as err:
        await engine.invoker.invoke(
            "xevent", _id(parent), "sneak", None,
            principal=waymark9.Principal(id="fern", type="human"),
            idempotency_key=uuid.uuid4().hex)
    assert "Creates('xevent')" in str(err.value)

    listing = (await client.get("/api/xevents")).json()
    assert listing["data"]["total"] == 1, "the aborted txn created nothing"


async def test_child_refusal_aborts_the_whole_act(env):
    """A mid-carve child refusal (a done row cannot reassign) rolls back
    the parent decrement and the peer create alike."""
    engine, client = env
    parent = await _event(client, 100.0)
    done = await _row(client, _id(parent), 30.0)
    await _post(client, f"{done['self']}/-/complete")

    res = await _post(client, f"{parent['self']}/-/carve_out",
                      {"wire_ids": [_id(done)]})
    assert res.status_code == 409, res.text
    assert (await client.get(parent["self"])).json()["data"]["total"] \
        == 100.0
    assert (await client.get("/api/xevents")).json()["data"]["total"] == 1


async def test_transfer_pair_born_linked_or_not_at_all(env):
    engine, client = env
    leg = await _event(client, 500.0)
    res = await _post(client, f"{leg['self']}/-/book_pair")
    assert res.status_code == 200, res.text
    twin_id = res.json()["data"]["twin"]
    twin = (await client.get(f"/api/xevents/{twin_id}")).json()
    assert twin["data"]["twin"] == _id(leg), \
        "the intake twin can no longer be born alone"


async def test_complete_all_attributes_children_to_the_invoker(env):
    """Where E4's async cascade says waymark-cascade, these rows say
    Fern — bulk-completed by the person, which is what the raw UPDATE
    destroyed. Retry is a natural no-op."""
    engine, client = env
    parent = await _event(client)
    r1 = await _row(client, _id(parent), 1)
    r2 = await _row(client, _id(parent), 2)
    already = await _row(client, _id(parent), 3)
    await _post(client, f"{already['self']}/-/complete")

    res = await _post(client, f"{parent['self']}/-/complete_all")
    assert res.status_code == 200, res.text
    for r in (r1, r2):
        doc = (await client.get(r["self"])).json()
        assert doc["state"] == "done"
        async with engine.storage.session() as s:
            last = await engine.storage.last_transition(s, "xrow", _id(r))
        assert last.actor_id == "fern" and last.actor_type == "human"

    versions = [(await client.get(r["self"])).json()["meta"]["version"]
                for r in (r1, r2, already)]
    res = await _post(client, f"{parent['self']}/-/complete_all")
    assert res.status_code == 200
    assert [(await client.get(r["self"])).json()["meta"]["version"]
            for r in (r1, r2, already)] == versions, \
        "the second sweep selected nothing"


async def test_touches_ride_the_effect_on_the_wire(env):
    engine, client = env
    parent = await _event(client)
    doc = (await client.get(parent["self"])).json()
    assert doc["actions"]["carve_out"]["effect"]["touches"] == [
        {"creates": "xevent"}, {"advances": "xrow.reassign"}]
    assert doc["actions"]["complete_all"]["effect"]["touches"] == [
        {"advances": "xrow.complete", "may": True}]


async def test_delegated_requires_a_sentence(env):
    with pytest.raises(DefinitionError):
        Delegated("")


async def test_touch_declarations_are_checked_at_assembly(env):
    def registry_with(touches):
        reg = Registry()

        class XEvent2(XEvent):
            kind = "xevent"

            @action(from_=EventState.OPEN, to=EventState.OPEN,
                    touches=touches,
                    safety=Safety(idempotent=True, reversible=True,
                                  confirm=False),
                    display=dict(label="T"))
            async def touchy(self, inp: None, ctx: Ctx) -> None:
                pass
        reg.register(XEvent2)
        reg.register(XRow)
        return reg

    for bad in [(Creates("nope"),),
                (Advances("nope", "complete"),),
                (Advances("xrow", "nope"),)]:
        with pytest.raises(DefinitionError):
            checks.check_touches(registry_with(bad))


async def test_defer_and_seeds_stay_lawful(env):
    """Engine surfaces are exempt by construction: an approval-run's
    Delegated touch set and ctx.defer's job row need no declarations
    from the app (regression guard for the exemptions)."""
    from waymark9.server.grants import ApprovalRequest

    run = ApprovalRequest.__waymark_machine__.actions["run"]
    assert any(isinstance(t, Delegated) for t in run.touches)