"""The clock is a publisher (design §3): a Clock-input derivation flips
when its declared moment arrives — no write, no poll. The engine keeps
``next_flip_at`` per row (extracted from the ``now``-vs-stored-field
comparison, or declared via ``flips_at=``) and ``engine.tick(now=...)``
sweeps the one index: recompute, store, announce with ``cause: "clock"``.
The scenario is the deliverable tracker's ``overdue`` — twelve parallel
queries in v3; one maintained column and one event here.
"""
from __future__ import annotations

import os
import uuid
from datetime import UTC, date, datetime, timedelta
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import AwareDatetime, BaseModel, Field

import waymark7
from waymark7 import (
    Clock,
    Ctx,
    DefinitionError,
    Derived,
    Resource,
    Safety,
    action,
    filterable,
)
from waymark7.server.bus import InProcessBus
from waymark7.server.engine import header_principal
from waymark7.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

OWNER = {"X-Principal-Id": "marcus", "X-Principal-Display": "Marcus"}

NOW = datetime(2026, 7, 1, 12, 0, tzinfo=UTC)


class StepState(StrEnum):
    PENDING = "pending"
    DONE = "done"


class StepData(BaseModel):
    title: str = Field(min_length=1, max_length=120)
    due_at: AwareDatetime
    # the extractable shape: now against a stored timestamp
    overdue: bool = Derived(over=("due_at", Clock),
                            fn=lambda due, now: now > due)


class Step(Resource):
    kind = "step"
    State = StepState
    Data = StepData
    initial = StepState.PENDING
    terminal = {StepState.DONE}
    summary = "{data.title} · {state.label}"
    filterable = filterable(state=filterable.Eq, overdue=filterable.Eq)

    @action(from_=StepState.PENDING, to=StepState.PENDING,
            safety=Safety(idempotent=True, reversible=False, confirm=False))
    async def touch(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=StepState.PENDING, to=StepState.DONE,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Marks the step complete."))
    async def complete(self, inp: None, ctx: Ctx) -> None:
        pass


class MilestoneData(BaseModel):
    title: str = Field(min_length=1, max_length=120)
    due_date: date
    # the date shape: flips at a UTC midnight, which the engine derives
    overdue: bool = Derived(over=("due_date", Clock),
                            fn=lambda due, now: now.date() > due)


class Milestone(Resource):
    kind = "milestone"
    State = StepState
    Data = MilestoneData
    initial = StepState.PENDING
    terminal = {StepState.DONE}
    summary = "{data.title} · {state.label}"
    filterable = filterable(state=filterable.Eq, overdue=filterable.Eq)

    @action(from_=StepState.PENDING, to=StepState.DONE,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Marks the milestone reached."))
    async def complete(self, inp: None, ctx: Ctx) -> None:
        pass


class ProbeData(BaseModel):
    checked_at: AwareDatetime
    # not a bare comparison — the declared alternative: flips_at= names
    # the next flip time explicitly (design §3's acknowledged escape)
    stale: bool = Derived(
        over=("checked_at", Clock),
        fn=lambda checked, now: (now - checked) > timedelta(hours=1),
        flips_at=lambda r: r.data.checked_at + timedelta(hours=1))


class Probe(Resource):
    kind = "probe"
    State = StepState
    Data = ProbeData
    initial = StepState.PENDING
    terminal = {StepState.DONE}
    summary = "probe · {state.label}"
    filterable = filterable(state=filterable.Eq, stale=filterable.Eq)

    @action(from_=StepState.PENDING, to=StepState.DONE,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Retires the probe."))
    async def complete(self, inp: None, ctx: Ctx) -> None:
        pass


RESOURCES = [Step, Milestone, Probe]


@pytest.fixture
async def env():
    clock = {"now": NOW}
    engine = waymark7.Engine(resources=RESOURCES, storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus(), clock=lambda: clock["now"])
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t", headers=OWNER)
    try:
        yield engine, client, clock
    finally:
        await client.aclose()
        await engine.shutdown()


async def _post(client, href, json=None):
    return await client.post(href, json=json,
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def _step(client, *, due=NOW + timedelta(hours=1)) -> dict:
    res = await _post(client, "/api/steps",
                      {"title": "Ship the report", "due_at": due.isoformat()})
    assert res.status_code == 201, res.text
    return res.json()


def _id(doc) -> str:
    return doc["self"].rsplit("/", 1)[-1]


async def _next_flip(engine, kind, id):
    async with engine.storage.session() as s:
        instance = await engine.storage.load(s, kind, id)
    return instance.next_flip_at


# ── extraction: the flip time is a maintained column ─────────────────────

async def test_create_extracts_the_next_flip_time(env):
    engine, client, _ = env
    due = NOW + timedelta(hours=1)
    step = await _step(client, due=due)
    assert step["data"]["overdue"] is False
    assert await _next_flip(engine, "step", _id(step)) == due, \
        "the against-now operand IS the next flip"


async def test_tick_flips_facts_whose_time_has_come(env):
    engine, client, clock = env
    due = NOW + timedelta(hours=1)
    step = await _step(client, due=due)
    sub = engine.dispatcher.subscribe(kinds=frozenset({"step"}),
                                      classes=frozenset({"derivation"}))

    assert await engine.tick(now=NOW + timedelta(minutes=30)) == 0, \
        "nothing is due; the sweep touches nothing"
    assert sub.queue.empty()

    clock["now"] = NOW + timedelta(hours=2)
    assert await engine.tick(now=clock["now"]) == 1

    doc = (await client.get(step["self"])).json()
    assert doc["data"]["overdue"] is True
    assert await _next_flip(engine, "step", _id(step)) is None, \
        "already flipped: the clock alone cannot change it again"

    event = sub.queue.get_nowait()
    assert event["class"] == "derivation"
    assert event["kind"] == "step"
    assert event["self"] == step["self"]
    assert event["fact"] == "overdue"
    assert event["from"] is False and event["to"] is True
    assert event["cause"] == "clock"

    listing = (await client.get("/api/steps?overdue=true")).json()
    assert [i["self"] for i in listing["data"]["items"]] == [step["self"]]


async def test_reticking_the_same_moment_is_idempotent(env):
    engine, client, clock = env
    await _step(client, due=NOW + timedelta(hours=1))
    later = NOW + timedelta(hours=2)
    assert await engine.tick(now=later) == 1
    assert await engine.tick(now=later) == 0, \
        "the index advanced with the flip; nothing re-announces"


async def test_the_cursor_refuses_a_backwards_clock(env):
    engine, client, clock = env
    await _step(client, due=NOW + timedelta(hours=1))
    assert await engine.tick(now=NOW + timedelta(hours=3)) == 1
    # a step already due at the earlier moment would re-sweep — refused
    assert await engine.tick(now=NOW + timedelta(hours=2)) == 0, \
        "a tick behind the cursor is a lie about time; skipped whole"


async def test_a_restart_neither_skips_nor_repeats_a_crossing(env):
    """All sweep state is rows and a cursor: a second engine (a restarted
    worker) flips the crossing the first one never saw, and the first
    engine's next sweep re-announces nothing."""
    engine, client, clock = env
    step = await _step(client, due=NOW + timedelta(hours=1))
    later = NOW + timedelta(hours=2)

    worker2 = waymark7.Engine(resources=RESOURCES, storage=TEST_DSN,
                              principal=header_principal, services=None,
                              bus=InProcessBus(), clock=lambda: later)
    try:
        assert await worker2.tick(now=later) == 1
    finally:
        await worker2.storage.engine.dispose()

    assert await engine.tick(now=later) == 0
    doc = (await client.get(step["self"])).json()
    assert doc["data"]["overdue"] is True


# ── the date shape: midnight is derived, not hand-written ────────────────

async def test_date_comparisons_flip_at_the_midnight_after(env):
    engine, client, clock = env
    due = (NOW + timedelta(days=1)).date()
    res = await _post(client, "/api/milestones",
                      {"title": "Filing deadline", "due_date": due.isoformat()})
    assert res.status_code == 201, res.text
    doc = res.json()
    assert doc["data"]["overdue"] is False

    midnight_of_due = datetime(due.year, due.month, due.day, tzinfo=UTC)
    # a candidate where nothing flips is swept silently and advances
    assert await engine.tick(now=midnight_of_due + timedelta(minutes=1)) == 0
    assert await _next_flip(engine, "milestone", _id(doc)) \
        == midnight_of_due + timedelta(days=1)

    assert await engine.tick(
        now=midnight_of_due + timedelta(days=1, minutes=1)) == 1
    assert (await client.get(doc["self"])).json()["data"]["overdue"] is True


# ── flips_at: the declared alternative to extraction ─────────────────────

async def test_flips_at_declares_the_next_flip(env):
    engine, client, clock = env
    res = await _post(client, "/api/probes",
                      {"checked_at": NOW.isoformat()})
    assert res.status_code == 201, res.text
    doc = res.json()
    assert doc["data"]["stale"] is False
    assert await _next_flip(engine, "probe", _id(doc)) \
        == NOW + timedelta(hours=1)

    assert await engine.tick(now=NOW + timedelta(minutes=61)) == 1
    assert (await client.get(doc["self"])).json()["data"]["stale"] is True


# ── transitions recompute clock facts too, with a transition cause ───────

async def test_transitions_recompute_clock_facts_with_their_own_cause(env):
    engine, client, clock = env
    step = await _step(client, due=NOW + timedelta(hours=1))
    clock["now"] = NOW + timedelta(hours=2)  # time passed; nobody ticked

    sub = engine.dispatcher.subscribe(kinds=frozenset({"step"}),
                                      classes=frozenset({"derivation"}))
    res = await _post(client, f"{step['self']}/-/touch")
    assert res.status_code == 200, res.text
    assert res.json()["data"]["overdue"] is True, \
        "any write recomputes; the sweep is for rows nobody writes"

    event = sub.queue.get_nowait()
    assert event["fact"] == "overdue"
    async with engine.storage.session() as s:
        touch = await engine.storage.last_transition(s, "step", _id(step))
    assert event["cause"] == touch.id, "a write's flip is the write's, " \
        "not the clock's"


async def test_clocked_engine_starts_the_background_sweep(env):
    engine, _, _ = env
    assert engine._tick_task is not None
    assert not engine._tick_task.done()


# ── declaration honesty ───────────────────────────────────────────────────

def test_unextractable_clock_shapes_are_definition_errors():
    with pytest.raises(DefinitionError, match="extractable"):
        Derived(over=(Clock,), fn=lambda now: True)

    with pytest.raises(DefinitionError, match="extractable"):
        class BadData(BaseModel):
            title: str = "x"
            stale: bool = Derived(over=("title", Clock),
                                  fn=lambda t, now: False)

        class BadStep(Step):
            kind = "step"
            Data = BadData

    with pytest.raises(DefinitionError, match="flips_at"):
        Derived(over=("a",), fn=lambda a: a,
                flips_at=lambda r: None)  # flips_at without Clock
