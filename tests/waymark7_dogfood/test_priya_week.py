"""The mealplan7 dogfood's driving story (design 6.0 §1–§2 appendix):
Priya plans the week, and the calendar the plan decision consults is
nobody's child.

Before v6 the recital was invisible to the plan — the relation is a
date-overlap predicate, not an ownership edge, so the conflict was
nobody's fact. Here the plan declares ``_calendar = Related("event", …)``
over its stored week boundaries and carries ``calendar_conflicts`` /
``has_conflicts`` as maintained fields: the calendar feed's own pull-through
read flips the plan's facts in the same request. Event is a read-only
Mirror of the family's Google Calendar (event_source.py) — nobody inside
this boundary authors title/date/kind, so the test mints through the
fake source's ``seed``/``external_id`` mechanism, exactly like the
ledger7 fund Mirror tests.
"""
from __future__ import annotations

import os
import uuid

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

import waymark7
from waymark7.server.bus import InProcessBus
from waymark7.server.engine import header_principal
from waymark7.testing import per_worker_dsn

from mealplan7.event_source import FakeEvents
from mealplan7.resources.event import Event
from mealplan7.resources.grocery_list import GroceryList
from mealplan7.resources.meal import Meal
from mealplan7.resources.plan import MealPlan
from mealplan7.resources.prep_task import PrepTask
from mealplan7.resources.rotation import SundayRotation
from mealplan7.services import Services

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

PRIYA = {"X-Principal-Id": "priya", "X-Principal-Display": "Priya"}

# a Tuesday; the one-week plan covers 2026-06-30 … 2026-07-06 inclusive
WEEK_START = "2026-06-30"
WEEK_END = "2026-07-06"


@pytest.fixture
async def env():
    events = FakeEvents()
    Event.adapter = events
    engine = waymark7.Engine(
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
    client.events = events
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
    external_id = f"{title}-{when}-{uuid.uuid4().hex[:8]}"
    client.events.seed(external_id, title=title, date=when, kind=kind)
    res = await _post(client, "/api/events", {"external_id": external_id})
    assert res.status_code == 201, res.text
    minted = res.json()
    # the pull-through read fills the calendar's fields — Event's data
    # (and so the plan's overlap predicate) is unset until this happens
    got = await client.get(minted["self"])
    assert got.status_code == 200, got.text
    return got.json()


async def _fresh(client, doc) -> dict:
    res = await client.get(doc["self"])
    assert res.status_code == 200, res.text
    return res.json()


async def _cover_week(client, plan: dict) -> None:
    """A week of eating out is a covered week — opens the finalize gate."""
    for day in plan["data"]["days"]:
        await _post(client, f"{plan['self']}/-/mark_eating_out",
                    {"date": day["date"]})


async def test_priya_plans_the_week(env, monkeypatch):
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

    # ── the recital is deleted off the calendar; the fact flips back ────
    # (a read-only Mirror learns this on its own next pull-through, not
    # through a local cancel action — force one by dropping the TTL and
    # removing the fake source's doc, exactly as a real feed would after
    # the family deletes the event)
    monkeypatch.setattr(Event, "ttl_seconds", 0)
    client.events.remove(recital["data"]["external_id"])
    res = await client.get(recital["self"])
    assert res.status_code == 200, res.text
    assert res.json()["state"] == "unreachable"
    data = (await _fresh(client, plan))["data"]
    assert data["has_conflicts"] is False
    assert data["calendar_conflicts"] == 0


async def test_moving_the_event_on_the_calendar_moves_the_conflict(
        env, monkeypatch):
    """The two-set union: moving the event dirties BOTH windows — a
    read-only Mirror learns the new date on its own next pull-through, not
    through a local reschedule action."""
    _, client = env
    monkeypatch.setattr(Event, "ttl_seconds", 0)
    this_week = await _plan(client, WEEK_START)
    next_week = await _plan(client, "2026-07-07")
    ev = await _event(client, "2026-07-02")
    assert (await _fresh(client, this_week))["data"]["calendar_conflicts"] == 1
    assert (await _fresh(client, next_week))["data"]["calendar_conflicts"] == 0

    # the family moves the recital in the calendar itself
    client.events.seed(ev["data"]["external_id"], title="School recital",
                       date="2026-07-09", kind="blocking")
    res = await client.get(ev["self"])
    assert res.status_code == 200, res.text
    assert res.json()["data"]["date"] == "2026-07-09"
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
