"""The mealplan6 dogfood's driving story (design 6.0 §1–§2 appendix):
Priya plans the week, and the calendar the plan decision consults is
nobody's child.

Before v6 the recital was invisible to the plan — the relation is a
date-overlap predicate, not an ownership edge, so the conflict was
nobody's fact. Here the plan declares ``_calendar = Related("event", …)``
over its stored week boundaries and carries ``calendar_conflicts`` /
``has_conflicts`` as maintained fields: Sam's event write flips the
plan's facts in the same request, finalize *warns* (a recital on taco
night is worth an acknowledgment, not a wall), and cancelling the event
clears the fact through the same inverted predicate. The calendar link
is compiled from the edge — §3's honest date-range params, badged with
the conflict count.
"""
from __future__ import annotations

import os
import uuid

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

import waymark6
from waymark6.server.bus import InProcessBus
from waymark6.server.engine import header_principal
from waymark6.testing import per_worker_dsn

from mealplan6.resources.event import Event
from mealplan6.resources.grocery_list import GroceryList
from mealplan6.resources.meal import Meal
from mealplan6.resources.plan import MealPlan
from mealplan6.resources.prep_task import PrepTask
from mealplan6.resources.rotation import SundayRotation
from mealplan6.services import Services

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

PRIYA = {"X-Principal-Id": "priya", "X-Principal-Display": "Priya"}

# a Tuesday; the one-week plan covers 2026-06-30 … 2026-07-06 inclusive
WEEK_START = "2026-06-30"
WEEK_END = "2026-07-06"


@pytest.fixture
async def env():
    engine = waymark6.Engine(
        resources=[Meal, SundayRotation, MealPlan, GroceryList, PrepTask,
                   Event],
        storage=TEST_DSN, principal=header_principal, services=Services(),
        bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t", headers=PRIYA)
    try:
        yield engine, client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _post(client, href, body=None, **headers):
    return await client.post(
        href, json=body,
        headers={"Idempotency-Key": uuid.uuid4().hex, **headers})


async def _plan(client, start: str = WEEK_START, weeks: int = 1) -> dict:
    res = await _post(client, "/api/plans",
                      {"start_date": start, "weeks": weeks})
    assert res.status_code == 201, res.text
    return res.json()


async def _event(client, when: str, kind: str = "blocking",
                 title: str = "School recital") -> dict:
    res = await _post(client, "/api/events",
                      {"title": title, "date": when, "kind": kind})
    assert res.status_code == 201, res.text
    return res.json()


async def _fresh(client, doc) -> dict:
    res = await client.get(doc["self"])
    assert res.status_code == 200, res.text
    return res.json()


async def _cover_week(client, plan: dict) -> None:
    """A week of eating out is a covered week — opens the finalize gate."""
    for day in plan["data"]["days"]:
        await _post(client, f"{plan['self']}/-/mark_eating_out",
                    {"date": day["date"]})


async def test_priya_plans_the_week(env):
    engine, client = env

    # ── the plan is born telling the truth: no conflicts yet ────────────
    plan = await _plan(client)
    assert plan["data"]["end_date"] == WEEK_END, \
        "the derived far boundary is a stored fact"
    assert plan["data"]["has_conflicts"] is False
    assert plan["data"]["calendar_conflicts"] == 0

    # ── Sam adds the recital; the plan's fact flips in the SAME request ─
    recital = await _event(client, "2026-07-02")
    data = (await _fresh(client, plan))["data"]
    assert data["has_conflicts"] is True
    assert data["calendar_conflicts"] == 1

    # a note-kind event is worth seeing, not a conflict — the where=
    # narrows the fact to blocking evenings
    await _event(client, "2026-07-03", kind="note", title="Bake for Ada")
    data = (await _fresh(client, plan))["data"]
    assert data["calendar_conflicts"] == 1

    # ── finalize WARNS with the conflict sentence and honest remedies ───
    await _cover_week(client, plan)
    res = await _post(client, f"{plan['self']}/-/finalize")
    assert res.status_code == 409, res.text
    problem = res.json()
    assert problem["type"].endswith("warning-required")
    assert problem["severity"] == "warning"
    (warning,) = problem["warnings"]
    assert warning["name"] == "calendar_clear"
    assert "1 calendar conflict(s) overlap this week" in warning["reason"]
    assert "event.cancel" in warning["remedies"]
    assert problem["acknowledge"] == {"header": "Waymark-Acknowledge",
                                      "names": ["calendar_clear"]}

    # the recital is on the calendar and dinner is eating out anyway —
    # Priya acknowledges, and the override lands in the audit log
    res = await _post(client, f"{plan['self']}/-/finalize",
                      **{"Waymark-Acknowledge": "calendar_clear"})
    assert res.status_code == 200, res.text
    assert res.json()["state"] == "planned"
    plan_id = plan["self"].rsplit("/", 1)[-1]
    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(s, "plan", plan_id)
    assert last.action == "finalize"
    assert last.acknowledged == ["calendar_clear"]

    # ── the recital is cancelled; the fact flips back, same commit ──────
    res = await _post(client, f"{recital['self']}/-/cancel")
    assert res.status_code == 200, res.text
    data = (await _fresh(client, plan))["data"]
    assert data["has_conflicts"] is False
    assert data["calendar_conflicts"] == 0


async def test_reschedule_moves_the_conflict_between_weeks(env):
    """The two-set union: moving the event dirties BOTH windows."""
    _, client = env
    this_week = await _plan(client, WEEK_START)
    next_week = await _plan(client, "2026-07-07")
    ev = await _event(client, "2026-07-02")
    assert (await _fresh(client, this_week))["data"]["calendar_conflicts"] == 1
    assert (await _fresh(client, next_week))["data"]["calendar_conflicts"] == 0

    # reschedule is an Edit: prefilled AND fenced — the form carries the
    # stored date as its default and the write demands the etag it was
    # rendered against
    res = await _post(client, f"{ev['self']}/-/reschedule",
                      {"date": "2026-07-09"},
                      **{"If-Match": ev["meta"]["etag"]})
    assert res.status_code == 200, res.text
    assert (await _fresh(client, this_week))["data"]["calendar_conflicts"] == 0
    assert (await _fresh(client, next_week))["data"]["calendar_conflicts"] == 1


async def test_calendar_link_is_compiled_from_the_edge(env):
    """The edge-cited link (design §1): href compiled onto §3's honest
    date-range grammar, conflict count riding as badge scent."""
    _, client = env
    await _event(client, "2026-07-01")
    await _event(client, "2026-07-02")
    plan = await _plan(client)

    entry = plan["links"]["calendar"]
    assert entry["embed"] is True
    assert entry["kind"] == "event_collection"
    assert entry["badge"] == 2, "the count rides the link, before traversal"
    assert entry["summary"] == "What the family already has planned"
    # the compiled predicate: start_date <= date <= end_date, spelled in
    # the public range grammar the events collection actually serves
    assert entry["href"].endswith(
        f"/events?date_gte={WEEK_START}&date_lte={WEEK_END}")

    # the href is live: following it returns exactly the week's events
    res = await client.get("/api" + entry["href"].split("/api")[-1]
                           if "/api" in entry["href"]
                           else "/api" + entry["href"])
    assert res.status_code == 200, res.text
    items = res.json()["data"]["items"]
    assert len(items) == 2
