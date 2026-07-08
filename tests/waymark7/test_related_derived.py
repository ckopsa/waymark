"""Facts over relations (design 6.0 §2): the materialization law,
extended in mechanism, unchanged in statement.

A meal plan carries ``calendar_conflicts`` over the events whose date
falls inside its stored week boundaries — a date-overlap predicate, not
an ownership edge. For ``Owns`` inputs the maintainer dereferences one
scalar FK; for ``Related`` inputs it runs the INVERTED predicate — an
indexed query over the anchors' promoted join columns — over the old
AND the new target values, and dirties the set. The recompute rides the
causing commit, rows locked, flips published through the outbox after:
the same sentence that governs children, verbatim. Not punted, on
principle — there is no eventually-consistent tier.

Deletion needs no special case: waymark has no hard delete, so "the row
left the relation" is always a write whose before and after both
evaluate — a terminal transition keeps its join values, the same
anchors recompute, and a ``where=`` that excludes the new state settles
the fact through the forward read.
"""
from __future__ import annotations

import os
import uuid
from datetime import date, timedelta
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark7
from waymark7 import (
    Count,
    Ctx,
    Derived,
    On,
    Related,
    Resource,
    Safety,
    action,
    filterable,
    require,
)
from waymark7.server.bus import InProcessBus
from waymark7.server.engine import header_principal
from waymark7.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

OWNER = {"X-Principal-Id": "priya", "X-Principal-Display": "Priya"}

_calendar = Related("cevent", on=(
    On(ours="start_date", op="<=", theirs="date"),
    On(ours="end_date",   op=">=", theirs="date"),
))


class CEventState(StrEnum):
    SCHEDULED = "scheduled"
    CANCELLED = "cancelled"


class CEventData(BaseModel):
    date: date
    kind: str = Field(default="fyi", max_length=40)


class MoveInput(BaseModel):
    to_date: date


class CEvent(Resource):
    kind = "cevent"
    State = CEventState
    Data = CEventData
    initial = CEventState.SCHEDULED
    terminal = {CEventState.CANCELLED}
    summary = "event {data.date} · {state.label}"
    filterable = filterable(state=filterable.Eq | filterable.In,
                            date=filterable.Range,
                            kind=filterable.Eq | filterable.In)

    @action(from_=CEventState.SCHEDULED, to=CEventState.SCHEDULED,
            input=MoveInput,
            safety=Safety(idempotent=True, reversible=False, confirm=False))
    async def move(self, inp: MoveInput, ctx: Ctx) -> None:
        self.data.date = inp.to_date

    @action(from_=CEventState.SCHEDULED, to=CEventState.CANCELLED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Removes the event from the calendar."))
    async def cancel(self, inp: None, ctx: Ctx) -> None:
        pass


class CPlanState(StrEnum):
    DRAFT = "draft"
    FINAL = "final"


class CPlanData(BaseModel):
    start_date: date
    end_date: date
    # the design's spelling, verbatim: Count over a Related edge is the
    # same library derivation Count over Owns is
    calendar_conflicts: int = Count(
        _calendar, where={"kind": ("blocking",), "state": ("scheduled",)})
    conflict_free: bool = Derived(
        over=(_calendar.field("kind", where={"state": ("scheduled",)}),),
        fn=lambda kinds: not any(k == "blocking" for k in kinds),
        explain="{n} calendar conflict(s) overlap this week.",
        vars=lambda kinds: {"n": sum(k == "blocking" for k in kinds)})


class CPlan(Resource):
    kind = "cplan"
    State = CPlanState
    Data = CPlanData
    initial = CPlanState.DRAFT
    terminal = {CPlanState.FINAL}
    summary = "plan {data.start_date} · {state.label}"
    filterable = filterable(state=filterable.Eq,
                            start_date=filterable.Range,
                            end_date=filterable.Range,
                            conflict_free=filterable.Eq,
                            calendar_conflicts=filterable.Eq
                            | filterable.Range)

    @action(from_=CPlanState.DRAFT, to=CPlanState.FINAL,
            guards=[require("conflict_free")],
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Locks the week's plan."))
    async def finalize(self, inp: None, ctx: Ctx) -> None:
        pass


@pytest.fixture
async def env():
    engine = waymark7.Engine(resources=[CPlan, CEvent], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t", headers=OWNER)
    try:
        yield engine, client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _post(client, href, body=None):
    return await client.post(href, json=body,
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def _plan(client, start: str, end: str) -> dict:
    res = await _post(client, "/api/cplans",
                      {"start_date": start, "end_date": end})
    assert res.status_code == 201, res.text
    return res.json()


async def _event(client, when: str, kind: str = "blocking") -> dict:
    res = await _post(client, "/api/cevents", {"date": when, "kind": kind})
    assert res.status_code == 201, res.text
    return res.json()


async def _fresh(client, doc) -> dict:
    return (await client.get(doc["self"])).json()["data"]


async def assert_conform(engine, kind: str, id: str) -> None:
    """The §2 conformance proof: recompute-from-inputs == stored value."""
    rdef = engine.registry[kind]
    async with engine.storage.session() as s:
        instance = await engine.storage.load(s, kind, id)
        fresh = await engine.invoker.derived.compute(
            s, instance, rdef, now=engine.invoker.clock())
    stored = instance.data.model_dump()
    for field, value in fresh.items():
        assert stored[field] == value, \
            f"{kind}.{field}: stored {stored[field]!r} != derived {value!r}"


def _id(doc) -> str:
    return doc["self"].rsplit("/", 1)[-1]


# ── (a) materialization at anchor create: the forward read ──────────────

async def test_related_facts_materialize_at_plan_create(env):
    engine, client = env
    await _event(client, "2026-06-03", "blocking")
    await _event(client, "2026-06-04", "fyi")
    await _event(client, "2026-07-20", "blocking")  # outside the window

    plan = await _plan(client, "2026-06-01", "2026-06-07")
    assert plan["data"]["calendar_conflicts"] == 1
    assert plan["data"]["conflict_free"] is False

    empty = await _plan(client, "2026-08-01", "2026-08-07")
    assert empty["data"]["calendar_conflicts"] == 0
    assert empty["data"]["conflict_free"] is True  # vacuously
    await assert_conform(engine, "cplan", _id(plan))
    await assert_conform(engine, "cplan", _id(empty))


# ── (b) target create inside the window flips in the same request ───────

async def test_event_create_flips_the_plan_in_the_same_commit(env):
    engine, client = env
    plan = await _plan(client, "2026-06-01", "2026-06-07")
    assert plan["data"]["conflict_free"] is True

    await _event(client, "2026-06-05", "blocking")
    # read-after-write: no sleep, no tick — the inverted predicate ran
    # inside the event create's own transaction
    data = await _fresh(client, plan)
    assert data["calendar_conflicts"] == 1
    assert data["conflict_free"] is False
    await assert_conform(engine, "cplan", _id(plan))


# ── (c) a write outside the window flips nothing ─────────────────────────

async def test_event_outside_the_window_flips_nothing(env):
    engine, client = env
    plan = await _plan(client, "2026-06-01", "2026-06-07")
    await _event(client, "2026-06-20", "blocking")
    data = await _fresh(client, plan)
    assert data["calendar_conflicts"] == 0
    assert data["conflict_free"] is True
    await assert_conform(engine, "cplan", _id(plan))


# ── (d) a moved date dirties BOTH windows: the two-set union ─────────────

async def test_moving_an_event_recomputes_both_plans(env):
    engine, client = env
    plan_a = await _plan(client, "2026-06-01", "2026-06-07")
    plan_b = await _plan(client, "2026-07-01", "2026-07-07")
    ev = await _event(client, "2026-06-03", "blocking")
    assert (await _fresh(client, plan_a))["calendar_conflicts"] == 1

    res = await _post(client, f"{ev['self']}/-/move",
                      {"to_date": "2026-07-03"})
    assert res.status_code == 200, res.text
    # the OLD values selected plan A, the NEW select plan B — the union
    # recomputed, so neither week lies (recompute_owners' before-value
    # discipline, generalized from two ids to two sets)
    assert (await _fresh(client, plan_a))["calendar_conflicts"] == 0
    assert (await _fresh(client, plan_a))["conflict_free"] is True
    assert (await _fresh(client, plan_b))["calendar_conflicts"] == 1
    assert (await _fresh(client, plan_b))["conflict_free"] is False
    await assert_conform(engine, "cplan", _id(plan_a))
    await assert_conform(engine, "cplan", _id(plan_b))


# ── (e) leaving the relation is a state write, not a delete ──────────────

async def test_terminal_transition_excluded_by_where_recomputes(env):
    engine, client = env
    plan = await _plan(client, "2026-06-01", "2026-06-07")
    ev = await _event(client, "2026-06-03", "blocking")
    assert (await _fresh(client, plan))["conflict_free"] is False

    res = await _post(client, f"{ev['self']}/-/cancel")
    assert res.status_code == 200, res.text
    # the join values still stand, so the inverted predicate still finds
    # the plan; the where= on the input excludes the cancelled row on the
    # forward read — "the row left the relation" is just a write
    data = await _fresh(client, plan)
    assert data["calendar_conflicts"] == 0
    assert data["conflict_free"] is True
    await assert_conform(engine, "cplan", _id(plan))


# ── (f) set-valued fan-out: one write, every containing window ──────────

async def test_overlapping_plans_both_recompute_from_one_event(env):
    engine, client = env
    plan_a = await _plan(client, "2026-06-01", "2026-06-10")
    plan_b = await _plan(client, "2026-06-05", "2026-06-15")
    await _event(client, "2026-06-07", "blocking")
    for plan in (plan_a, plan_b):
        data = await _fresh(client, plan)
        assert data["calendar_conflicts"] == 1, plan["self"]
        assert data["conflict_free"] is False
        await assert_conform(engine, "cplan", _id(plan))


# ── (g) require() speaks the derivation's generated sentence ────────────

async def test_require_over_the_related_fact_refuses_with_the_explain(env):
    _, client = env
    plan = await _plan(client, "2026-06-01", "2026-06-07")
    await _event(client, "2026-06-03", "blocking")
    await _event(client, "2026-06-04", "blocking")

    res = await _post(client, f"{plan['self']}/-/finalize")
    assert res.status_code == 409, res.text  # guard-failed Problem
    body = res.json()
    assert body["detail"] == "2 calendar conflict(s) overlap this week."
    # the same sentence rides the envelope's unavailable entry — one
    # declaration serving refusal and advertisement alike
    assert body["resource"]["unavailable"]["finalize"]["reason"] == \
        "2 calendar conflict(s) overlap this week."

    # resolving the conflicts opens the gate — same fact, same maintainer
    listing = (await client.get("/api/cevents?state=scheduled")).json()
    for item in listing["data"]["items"]:
        await _post(client, f"{item['self']}/-/cancel")
    res = await _post(client, f"{plan['self']}/-/finalize")
    assert res.status_code == 200, res.text


# ── (h) the related fact is a promoted column like any derived field ─────

async def test_related_facts_filter_as_promoted_columns(env):
    _, client = env
    clear = await _plan(client, "2026-08-01", "2026-08-07")
    busy = await _plan(client, "2026-06-01", "2026-06-07")
    await _event(client, "2026-06-03", "blocking")

    listing = (await client.get("/api/cplans?conflict_free=false")).json()
    assert [i["self"] for i in listing["data"]["items"]] == [busy["self"]]
    listing = (await client.get(
        "/api/cplans?calendar_conflicts_gte=1")).json()
    assert [i["self"] for i in listing["data"]["items"]] == [busy["self"]]
    listing = (await client.get("/api/cplans?conflict_free=true")).json()
    assert [i["self"] for i in listing["data"]["items"]] == [clear["self"]]


# ── (i) backfill: a Related fact defined after rows exist ────────────────

class BEventData(BaseModel):
    date: date
    kind: str = Field(default="fyi", max_length=40)


class BEvent(Resource):
    kind = "bevent6"
    State = CEventState
    Data = BEventData
    initial = CEventState.SCHEDULED
    terminal = {CEventState.CANCELLED}
    summary = "event {data.date} · {state.label}"
    filterable = filterable(state=filterable.Eq,
                            date=filterable.Range,
                            kind=filterable.Eq)

    @action(from_=CEventState.SCHEDULED, to=CEventState.CANCELLED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Removes the event."))
    async def cancel(self, inp: None, ctx: Ctx) -> None:
        pass


def make_bplan(with_fact: bool):
    """Two laws for one kind (the test_backfill pattern): the second adds
    a Related-derived fact to an app whose rows already exist. The fact
    is deliberately unpromoted — a new generated column is a migration,
    a new JSONB fact is a backfill, and this test is about the latter."""
    edge = Related("bevent6", on=(
        On(ours="start_date", op="<=", theirs="date"),
        On(ours="end_date",   op=">=", theirs="date"),
    ))

    if with_fact:
        class PlanData(BaseModel):
            start_date: date
            end_date: date
            conflicts: int = Count(
                edge, where={"kind": ("blocking",),
                             "state": ("scheduled",)})
    else:
        class PlanData(BaseModel):
            start_date: date
            end_date: date

    class Plan(Resource):
        kind = "bplan6"
        State = CPlanState
        Data = PlanData
        initial = CPlanState.DRAFT
        terminal = {CPlanState.FINAL}
        summary = "plan {data.start_date} · {state.label}"
        filterable = filterable(state=filterable.Eq,
                                start_date=filterable.Range,
                                end_date=filterable.Range)

        @action(from_=CPlanState.DRAFT, to=CPlanState.FINAL,
                safety=Safety(idempotent=True, reversible=False,
                              confirm=True,
                              consequence="Locks the plan."))
        async def finalize(self, inp: None, ctx: Ctx) -> None:
            pass

    return Plan


async def _boot(resources, *, drop=False):
    engine = waymark7.Engine(resources=resources, storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    if drop:
        await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t", headers=OWNER)
    return engine, client


async def test_backfill_materializes_a_new_related_fact_on_old_rows():
    """Stale-by-definition over a relation (design §2 + the §4 backfill
    invariant, unchanged): a deploy that ADDS a Related-derived fact
    recomputes every existing anchor row through the ordinary forward
    read inside boot — no special-casing, no events, no version bumps —
    so the first request already reads the fact the new law declares."""
    e1, c1 = await _boot([make_bplan(with_fact=False), BEvent], drop=True)
    try:
        res = await _post(c1, "/api/bplan6s",
                          {"start_date": "2026-06-01",
                           "end_date": "2026-06-07"})
        assert res.status_code == 201, res.text
        plan_id = _id(res.json())
        version_before = res.json()["meta"]["etag"]
        res = await _post(c1, "/api/bevent6s",
                          {"date": "2026-06-03", "kind": "blocking"})
        assert res.status_code == 201, res.text
        res = await _post(c1, "/api/bevent6s",
                          {"date": "2026-06-04", "kind": "blocking"})
        assert res.status_code == 201, res.text
    finally:
        await c1.aclose()
        await e1.shutdown()

    e2, c2 = await _boot([make_bplan(with_fact=True), BEvent])
    try:
        # asserted at the storage layer, before any request: the boot's
        # revise marked `conflicts` stale by definition and the backfill
        # recomputed it per-anchor via the forward read
        async with e2.storage.session() as s:
            row = await e2.storage.load(s, "bplan6", plan_id)
        assert row.data.conflicts == 2
        assert row.version == 1, \
            "backfill is maintenance: no transition, no version bump"
        doc = (await c2.get(f"/api/bplan6s/{plan_id}")).json()
        assert doc["data"]["conflicts"] == 2
        assert doc["meta"]["etag"] == version_before
        # and the maintainer owns the fact from here: a new event flips it
        res = await _post(c2, "/api/bevent6s",
                          {"date": "2026-06-05", "kind": "blocking"})
        assert res.status_code == 201, res.text
        doc = (await c2.get(f"/api/bplan6s/{plan_id}")).json()
        assert doc["data"]["conflicts"] == 3
    finally:
        await c2.aclose()
        await e2.shutdown()


# ── (j) a derived join key mutated by an action: same-pass freshness ─────

_wcalendar = Related("wevent6", on=(
    On(ours="start_date", op="<=", theirs="date"),
    On(ours="end_date",   op=">=", theirs="date"),
))


class WEventData(BaseModel):
    date: date
    kind: str = Field(default="fyi", max_length=40)


class WEvent(Resource):
    kind = "wevent6"
    State = CEventState
    Data = WEventData
    initial = CEventState.SCHEDULED
    terminal = {CEventState.CANCELLED}
    summary = "event {data.date} · {state.label}"
    filterable = filterable(state=filterable.Eq | filterable.In,
                            date=filterable.Range,
                            kind=filterable.Eq | filterable.In)

    @action(from_=CEventState.SCHEDULED, to=CEventState.CANCELLED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Removes the event."))
    async def cancel(self, inp: None, ctx: Ctx) -> None:
        pass


class ShiftInput(BaseModel):
    start_date: date


class WPlanData(BaseModel):
    # deliberately adversarial declaration order: the fact over the
    # relation is declared BEFORE the derived boundary it joins on —
    # ordered_specs must topo-sort the join key first (a Related input's
    # `ours` fields are dependencies, design §2)
    week_conflicts: int = Count(
        _wcalendar, where={"kind": ("blocking",), "state": ("scheduled",)})
    start_date: date
    end_date: date = Derived(
        over=("start_date",),
        fn=lambda start: start + timedelta(days=6))


class WPlan(Resource):
    kind = "wplan6"
    State = CPlanState
    Data = WPlanData
    initial = CPlanState.DRAFT
    terminal = {CPlanState.FINAL}
    summary = "week of {data.start_date} · {state.label}"
    filterable = filterable(state=filterable.Eq,
                            start_date=filterable.Range,
                            end_date=filterable.Range,
                            week_conflicts=filterable.Eq | filterable.Range)

    @action(from_=CPlanState.DRAFT, to=CPlanState.DRAFT, input=ShiftInput,
            safety=Safety(idempotent=True, reversible=False, confirm=False))
    async def shift(self, inp: ShiftInput, ctx: Ctx) -> None:
        self.data.start_date = inp.start_date

    @action(from_=CPlanState.DRAFT, to=CPlanState.FINAL,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Locks the week's plan."))
    async def finalize(self, inp: None, ctx: Ctx) -> None:
        pass


async def test_a_shifted_window_relates_against_its_new_boundaries():
    """The mutable-derived-join-key case (design §2): an action moves
    `start_date`, the derived `end_date` moves with it IN THE SAME PASS,
    and the related fact is computed against the NEW window — not the
    previous materialization's. Before the ordered-specs join-key
    dependency and the compute pass's fresh view, this lagged one write:
    the exact disagreement §2 declares unrepresentable."""
    engine, client = await _boot([WPlan, WEvent], drop=True)
    try:
        res = await _post(client, "/api/wevent6s",
                          {"date": "2026-06-10", "kind": "blocking"})
        assert res.status_code == 201, res.text

        plan = (await _post(client, "/api/wplan6s",
                            {"start_date": "2026-06-01"})).json()
        assert plan["data"]["end_date"] == "2026-06-07"
        assert plan["data"]["week_conflicts"] == 0, \
            "the event sits outside the declared week"

        # shift the window over the event: the SAME response must already
        # relate against the new boundaries
        moved = (await _post(client, f"{plan['self']}/-/shift",
                             {"start_date": "2026-06-08"})).json()
        assert moved["data"]["end_date"] == "2026-06-14"
        assert moved["data"]["week_conflicts"] == 1, \
            "the fact must see THIS pass's boundary, not last write's"
        await assert_conform(engine, "wplan6", _id(plan))

        # and back out again — no residue from the old window
        back = (await _post(client, f"{plan['self']}/-/shift",
                            {"start_date": "2026-06-01"})).json()
        assert back["data"]["week_conflicts"] == 0
        await assert_conform(engine, "wplan6", _id(plan))
    finally:
        await client.aclose()
        await engine.shutdown()
