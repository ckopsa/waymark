"""Every write is anchored (design §3).

The transition log gains ``defined_by`` — the definition revision under
which the write was validated, guarded, and rendered — threaded through
the invoker's one append choke point so no write path can forget it:
create, actions, batch items, bulk fan-out, compound children through
``ctx.invoke``, and the authority's authored-sync commits. Envelopes and
collections carry ``meta.law``; transition events (SSE and webhook
payloads share one serializer) and derivation events carry the anchor
too. A NULL anchor is the pre-law horizon (migration sketch): read
honestly, skipped by the replay check, breaking nothing.
"""
from __future__ import annotations

import asyncio
import os
import uuid
from datetime import UTC, datetime
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark7
from waymark7 import (
    Advances, Authored, Batch, Bulk, Ctx, Derived, Principal, Resource,
    Safety, action,
)
from waymark7.server.bus import InProcessBus
from waymark7.server.engine import header_principal
from waymark7.server.events import event_payload
from waymark7.server.external import AuthoredMeta
from waymark7.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

DANA = Principal(id="dana", type="human", display="Dana")
OWNER = {"X-Principal-Id": "dana", "X-Principal-Display": "Dana"}

OVERDUE = lambda due: due > 1.0  # noqa: E731


class NoteState(StrEnum):
    OPEN = "open"
    DONE = "done"


class ChildState(StrEnum):
    OPEN = "open"


class RowInput(BaseModel):
    sku: str = Field(min_length=1, max_length=40)


class DueInput(BaseModel):
    new_due: float = Field(ge=0)


class NoteData(BaseModel):
    title: str = Field(min_length=1, max_length=80)
    due: float = 0.0
    posted: list[str] = []
    child_id: str | None = Field(default=None, max_length=64)
    overdue: bool = Derived(over=("due",), fn=OVERDUE)


class ChildData(BaseModel):
    bumps: int = 0


class AChild(Resource):
    kind = "achild"
    State = ChildState
    Data = ChildData
    initial = ChildState.OPEN
    terminal: set = set()
    summary = "Child bumped {data.bumps} times · {state.label}"

    @action(from_=ChildState.OPEN, to=ChildState.OPEN,
            safety=Safety(idempotent=False, reversible=True, confirm=False),
            display=dict(label="Bump"))
    async def bump(self, inp: None, ctx: Ctx) -> None:
        self.data.bumps += 1


class ANote(Resource):
    kind = "anote"
    State = NoteState
    Data = NoteData
    initial = NoteState.OPEN
    terminal = {NoteState.DONE}
    summary = "{data.title} · {state.label}"

    @action(from_=NoteState.OPEN, to=NoteState.OPEN, input=DueInput,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Set due"))
    async def set_due(self, inp: DueInput, ctx: Ctx) -> None:
        self.data.due = inp.new_due

    @action(from_=NoteState.OPEN, to=NoteState.OPEN, input=RowInput,
            batch=Batch(atomic=True, max_items=5),
            safety=Safety(idempotent=False, reversible=True, confirm=False),
            display=dict(label="Post row"))
    async def post_row(self, inp: RowInput, ctx: Ctx) -> None:
        self.data.posted.append(inp.sku)

    @action(from_=NoteState.OPEN, to=NoteState.OPEN,
            touches=(Advances("achild", "bump"),),
            safety=Safety(idempotent=False, reversible=True, confirm=False),
            display=dict(label="Cascade"))
    async def cascade(self, inp: None, ctx: Ctx) -> None:
        await ctx.invoke("achild", self.data.child_id, "bump", None)

    @action(from_=NoteState.OPEN, to=NoteState.OPEN,
            bulk=Bulk(max_items=10),
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Sweep"))
    async def sweep(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=NoteState.OPEN, to=NoteState.DONE,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The note is closed for good."),
            display=dict(label="Finish"))
    async def finish(self, inp: None, ctx: Ctx) -> None:
        pass


class EntityData(AuthoredMeta):
    name: str | None = Authored(by="crm", default=None, max_length=80)


class AEntity(Resource):
    kind = "aentity"
    State = ChildState
    Data = EntityData
    initial = ChildState.OPEN
    terminal: set = set()
    summary = "Entity · {state.label}"


async def _boot(*, drop: bool = False):
    engine = waymark7.Engine(resources=[ANote, AChild, AEntity],
                             storage=TEST_DSN, principal=header_principal,
                             services=None, bus=InProcessBus())
    if drop:
        await engine.storage.drop_all()
    await engine.startup()
    return engine


async def _last(engine, kind: str, id: str):
    async with engine.storage.session() as s:
        return await engine.storage.last_transition(s, kind, id)


async def _all(engine, kind: str):
    async with engine.storage.session() as s:
        return await engine.storage.transitions_since(s, 0, kinds=[kind],
                                                      limit=500)


async def _mk_note(engine, **data) -> str:
    body = {"title": "Anchored", **data}
    res = await engine.invoker.create("anote", body, principal=DANA,
                                      idempotency_key=uuid.uuid4().hex)
    return res.doc["self"].rsplit("/", 1)[-1]


# ── §3: defined_by on every write path ───────────────────────────────────
async def test_create_and_action_are_anchored():
    engine = await _boot(drop=True)
    try:
        law = engine.current_law("anote")
        assert law is not None, "the boot revise must stamp the law"
        nid = await _mk_note(engine)
        created = await _last(engine, "anote", nid)
        assert created.action == "create"
        assert created.defined_by == law

        await engine.invoker.invoke("anote", nid, "finish", None,
                                    principal=DANA)
        finished = await _last(engine, "anote", nid)
        assert finished.action == "finish"
        assert finished.defined_by == law
    finally:
        await engine.shutdown()


async def test_batch_items_and_bulk_are_anchored():
    engine = await _boot(drop=True)
    try:
        law = engine.current_law("anote")
        nid = await _mk_note(engine)
        res = await engine.invoker.batch(
            "anote", nid, "post_row",
            {"items": [{"sku": "a"}, {"sku": "b"}]}, principal=DANA,
            idempotency_key=uuid.uuid4().hex)
        assert res.status == 200
        posted = [t for t in await _all(engine, "anote")
                  if t.action == "post_row"]
        assert len(posted) == 2
        assert all(t.defined_by == law for t in posted), \
            "every batch item rides the anchor"

        res = await engine.invoker.bulk("anote", "sweep", {"ids": [nid]},
                                        principal=DANA)
        assert res.status == 200
        swept = [t for t in await _all(engine, "anote")
                 if t.action == "sweep"]
        assert swept and all(t.defined_by == law for t in swept)
    finally:
        await engine.shutdown()


async def test_compound_child_is_anchored_to_its_own_kinds_law():
    engine = await _boot(drop=True)
    try:
        child = await engine.invoker.create(
            "achild", {}, principal=DANA, idempotency_key=uuid.uuid4().hex)
        cid = child.doc["self"].rsplit("/", 1)[-1]
        nid = await _mk_note(engine, child_id=cid)
        await engine.invoker.invoke("anote", nid, "cascade", None,
                                    principal=DANA,
                                    idempotency_key=uuid.uuid4().hex)
        parent = await _last(engine, "anote", nid)
        bumped = await _last(engine, "achild", cid)
        assert parent.action == "cascade"
        assert parent.defined_by == engine.current_law("anote")
        assert bumped.action == "bump"
        assert bumped.defined_by == engine.current_law("achild"), \
            "a ctx.invoke child anchors to ITS kind's law, not the parent's"
        # one correlation, two kinds, each row under its own law
        assert bumped.correlation_id == parent.correlation_id
    finally:
        await engine.shutdown()


async def test_authored_sync_commits_are_anchored():
    engine = await _boot(drop=True)
    try:
        law = engine.current_law("aentity")
        res = await engine.invoker.create(
            "aentity", {"external_id": "crm-1"}, principal=DANA,
            idempotency_key=uuid.uuid4().hex)
        eid = res.doc["self"].rsplit("/", 1)[-1]
        changed = await engine.invoker.sync_authored(
            "aentity", eid, {"name": "Zed"}, "etag-1")
        assert changed == ["name"]
        observed = await _last(engine, "aentity", eid)
        assert observed.action == "observe_authored"
        assert observed.defined_by == law

        await engine.invoker.mark_authored("aentity", eid, "stale")
        marked = await _last(engine, "aentity", eid)
        assert marked.action == "mark_stale"
        assert marked.defined_by == law
    finally:
        await engine.shutdown()


async def test_boot_revise_transitions_are_anchored_to_the_law_of_the_law():
    """The deploy's own writes are writes: the revise's transition rows
    (rows of the definition kind) carry ``defined_by`` naming the
    definition kind's law in force at write time. The definition kind
    revises first, so on the very first boot only its own revision-1
    create is honestly pre-law (NULL — no law existed yet); every later
    kind's revision row anchors to the fresh law of the law."""
    engine = await _boot(drop=True)
    try:
        definition_law = engine.current_law("definition")
        async with engine.storage.session() as s:
            rows, _ = await engine.storage.query(
                s, "definition", filters={}, sort=None,
                page_size=500, page_number=1)
        by_target = {r.data.target_kind: r for r in rows}
        own = await _last(engine, "definition", by_target["definition"].id)
        assert own.action == "create" and own.defined_by is None, \
            "the law of the law is born pre-law on the first boot"
        note_row = await _last(engine, "definition", by_target["anote"].id)
        assert note_row.defined_by == definition_law, \
            "every later revision row anchors to the law of the law"
    finally:
        await engine.shutdown()


# ── §3: meta.law on envelopes and collections ────────────────────────────
async def test_meta_law_on_envelopes_and_collections():
    engine = await _boot(drop=True)
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t", headers=OWNER)
    try:
        nid = await _mk_note(engine)
        law = engine.current_law("anote")

        # the revision NUMBER rides beside the id (design §3): a human
        # client stamps "⚖ rev N" straight off the envelope, no deploy-
        # history resolution fetch — a fresh boot is revision 1
        doc = (await client.get(f"/api/anotes/{nid}")).json()
        assert doc["meta"]["law"] == law
        assert doc["meta"]["law_revision"] == 1

        collection = (await client.get("/api/anotes")).json()
        assert collection["meta"]["law"] == law
        assert collection["meta"]["law_revision"] == 1

        # the invoke response is the post-transition document — same law
        invoked = await engine.invoker.invoke("anote", nid, "set_due",
                                              {"new_due": 0.5}, principal=DANA)
        assert invoked.doc["meta"]["law"] == law
        assert invoked.doc["meta"]["law_revision"] == 1

        # the definition kind's own envelopes carry the definition kind's
        # current revision id (and number) — the law of the law
        deflaw = engine.current_law("definition")
        defs = (await client.get(
            "/api/definitions?target_kind=anote")).json()
        assert defs["meta"]["law"] == deflaw
        assert defs["meta"]["law_revision"] == 1
        item = (await client.get(
            defs["data"]["items"][0]["self"])).json()
        assert item["meta"]["law"] == deflaw
        assert item["meta"]["law_revision"] == 1
    finally:
        await client.aclose()
        await engine.shutdown()


# ── §3: events and webhook payloads carry the anchor ─────────────────────
async def test_transition_and_derivation_events_carry_defined_by():
    engine = await _boot(drop=True)
    try:
        law = engine.current_law("anote")
        nid = await _mk_note(engine)
        sub = engine.dispatcher.subscribe(resource=("anote", nid))
        dsub = engine.dispatcher.subscribe(
            kinds=frozenset({"anote"}),
            classes=frozenset({"derivation"}))
        try:
            # due 0 → 5 flips the derived `overdue` fact
            await engine.invoker.invoke("anote", nid, "set_due",
                                        {"new_due": 5.0}, principal=DANA)

            async def next_set_due():
                while True:
                    record = await sub.queue.get()
                    if record.action == "set_due":
                        return record

            record = await asyncio.wait_for(next_set_due(), timeout=10)
            assert record.defined_by == law
            # SSE frames and webhook deliveries share this one serializer
            payload = event_payload(record, engine.registry, "/api")
            assert payload["defined_by"] == law
            assert payload["summary"] == record.summary

            flip = await asyncio.wait_for(dsub.queue.get(), timeout=10)
            assert flip["class"] == "derivation"
            assert flip["fact"] == "overdue"
            assert flip["defined_by"] == law, \
                "a derivation event carries the law the flip was computed under"
        finally:
            engine.dispatcher.unsubscribe(sub)
            engine.dispatcher.unsubscribe(dsub)
    finally:
        await engine.shutdown()


# ── the NULL horizon: pre-law rows are read, skipped, never fatal ────────
async def test_pre_law_row_is_harmless():
    from waymark7.testing.conformance import replay_history

    engine = await _boot(drop=True)
    try:
        nid = await _mk_note(engine)
        checked_before = await replay_history(engine.storage,
                                              engine.registry, "anote")
        # a hand-inserted row from before the law (the v4 migration horizon)
        async with engine.storage.session() as s:
            await s.execute(engine.storage.transitions.insert().values(
                kind="anote", resource_id=nid, action="finish",
                from_state="open", to_state="done", version=99,
                actor_type="human", actor_id="dana", actor_display="Dana",
                input_digest="", correlation_id=None,
                summary="a pre-law write", at=datetime.now(UTC),
                defined_by=None))
        # the event surface reads it honestly
        rows = await _all(engine, "anote")
        horizon_row = next(t for t in rows if t.version == 99)
        assert horizon_row.defined_by is None
        payload = event_payload(horizon_row, engine.registry, "/api")
        assert payload["defined_by"] is None
        # the replay check skips it — no anchor is no lie
        checked_after = await replay_history(engine.storage,
                                             engine.registry, "anote")
        assert checked_after == checked_before
        # the envelope still renders and a restart still boots
        doc = await engine.invoker.invoke("anote", nid, "set_due",
                                          {"new_due": 0.2}, principal=DANA)
        assert doc.status == 200
    finally:
        await engine.shutdown()

    reboot = await _boot()
    try:
        assert reboot.current_law("anote") is not None
    finally:
        await reboot.shutdown()
