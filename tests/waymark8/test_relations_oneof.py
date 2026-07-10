"""Relation and OneOf (design §5): the declarations that catch what fell
off v2's cliff.

- ``meal_fits_day`` is one tuple set with two consumers: each bound day's
  ``meal_id`` enum offers exactly the meals serving its night (a rotating
  Sunday binds ``assign_meal`` not at all), and the invoke enforces
  membership in the same set.
- ``DayPlan.coverage`` is declared exclusivity: assigning a meal clears the
  eating-out arm and vice versa, in the engine, not in handlers.
"""
from __future__ import annotations

import os
import uuid
from datetime import date, timedelta

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

import waymark8
from waymark8.server.bus import InProcessBus
from waymark8.server.engine import header_principal
from waymark8.testing import per_worker_dsn

from tests.waymark8.mealplan7.meal import Meal
from tests.waymark8.mealplan7.plan import MealPlan
from tests.waymark8.mealplan7.prep_task import PrepTask
from tests.waymark8.mealplan7.rotation import SundayRotation

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

TUESDAY = date(2026, 7, 7)      # theme: mexican
WEDNESDAY = date(2026, 7, 8)    # theme: american
SUNDAY = date(2026, 7, 12)      # theme: rotating (until picked)


@pytest.fixture
async def env():
    engine = waymark8.Engine(
        resources=[Meal, SundayRotation, MealPlan, PrepTask],
        storage=TEST_DSN,
        principal=header_principal, services=None, bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(
        transport=ASGITransport(app=app), base_url="http://t",
        headers={"X-Principal-Id": "dana", "X-Principal-Type": "human",
                 "X-Principal-Display": "Dana"})
    try:
        yield client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _post(client, href, json=None):
    return await client.post(href, json=json or {},
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def _meal(client, name, themes) -> str:
    resp = await _post(client, "/api/meals", {"name": name, "themes": themes})
    assert resp.status_code == 201, resp.text
    doc = resp.json()
    accepted = await _post(client, f"{doc['self']}/-/accept")
    assert accepted.status_code == 200
    return doc["self"].rsplit("/", 1)[-1]


async def _plan(client) -> dict:
    resp = await _post(client, "/api/plans",
                       {"start_date": TUESDAY.isoformat(), "weeks": 1})
    assert resp.status_code == 201, resp.text
    return resp.json()


def _part(doc: dict, day: date) -> dict | None:
    items = doc.get("parts", {}).get("days", {}).get("items", [])
    return next((it for it in items if it["key"] == day.isoformat()), None)


async def test_relation_folds_per_day_enums(env):
    tacos = await _meal(env, "Tacos", ["mexican"])
    burgers = await _meal(env, "Burgers", ["american"])
    doc = (await env.get("/api/plans/" +
                         (await _plan(env))["self"].rsplit("/", 1)[-1])).json()

    tue = _part(doc, TUESDAY)
    assert tue is not None
    enum = tue["actions"]["assign_meal"]["input"]["properties"]["meal_id"]["enum"]
    assert enum == [tacos]  # only the meal serving mexican night

    wed = _part(doc, WEDNESDAY)
    assert wed["actions"]["assign_meal"]["input"]["properties"]["meal_id"]["enum"] \
        == [burgers]

    # a rotating Sunday admits no (meal, date) tuple → assign_meal not bound
    sun = _part(doc, SUNDAY)
    assert sun is None or "assign_meal" not in sun["actions"]
    # ...but the off-theme override (no relation) still binds Sunday
    if sun is not None:
        assert "assign_off_theme" in sun["actions"]


async def test_relation_enforces_the_same_set(env):
    tacos = await _meal(env, "Tacos", ["mexican"])
    plan = await _plan(env)
    # wrong pairing: a mexican meal on american night → guard-failed problem
    resp = await _post(env, f"{plan['self']}/-/assign_meal",
                       {"date": WEDNESDAY.isoformat(), "meal_id": tacos})
    assert resp.status_code == 409, resp.text
    problem = resp.json()
    assert "theme night" in problem["detail"]
    assert "plan.assign_off_theme" in problem["remedies"]
    # right pairing sails through
    resp = await _post(env, f"{plan['self']}/-/assign_meal",
                       {"date": TUESDAY.isoformat(), "meal_id": tacos})
    assert resp.status_code == 200, resp.text


async def test_oneof_clears_the_other_arm(env):
    tacos = await _meal(env, "Tacos", ["mexican"])
    plan = await _plan(env)
    href = plan["self"]

    resp = await _post(env, f"{href}/-/assign_meal",
                       {"date": TUESDAY.isoformat(), "meal_id": tacos})
    day = next(d for d in resp.json()["data"]["days"]
               if d["date"] == TUESDAY.isoformat())
    assert day["meal_id"] == tacos and day["meal_name"] == "Tacos"

    # eating out clears the meal arm — no handler wrote that clearing
    resp = await _post(env, f"{href}/-/mark_eating_out",
                       {"date": TUESDAY.isoformat(), "where": "Grandma's"})
    day = next(d for d in resp.json()["data"]["days"]
               if d["date"] == TUESDAY.isoformat())
    assert day["eating_out"] is True and day["eating_out_where"] == "Grandma's"
    assert day["meal_id"] is None and day["meal_name"] is None

    # and assigning again clears the eating-out arm
    resp = await _post(env, f"{href}/-/assign_meal",
                       {"date": TUESDAY.isoformat(), "meal_id": tacos})
    day = next(d for d in resp.json()["data"]["days"]
               if d["date"] == TUESDAY.isoformat())
    assert day["meal_id"] == tacos
    assert day["eating_out"] is False and day["eating_out_where"] is None


async def test_oneof_renders_on_the_parts_group(env):
    await _meal(env, "Tacos", ["mexican"])
    plan = await _plan(env)
    doc = (await env.get(plan["self"])).json()
    one_of = doc["parts"]["days"]["one_of"]["coverage"]
    assert one_of["clears"] is True
    assert set(one_of["arms"]) == {"meal", "eating_out"}


async def test_covered_predicate_still_finalizes(env):
    plan = await _plan(env)
    href = plan["self"]
    for i in range(7):
        d = TUESDAY + timedelta(days=i)
        resp = await _post(env, f"{href}/-/mark_eating_out",
                           {"date": d.isoformat()})
        assert resp.status_code == 200, resp.text
    resp = await _post(env, f"{href}/-/finalize")
    assert resp.status_code == 200, resp.text
    assert resp.json()["state"] == "planned"


# ── comparison relations: module-level so handler annotations resolve ───
from enum import StrEnum  # noqa: E402

from pydantic import BaseModel  # noqa: E402

from waymark8 import Ctx as WCtx  # noqa: E402
from waymark8 import DefinitionError, Guard, Registry, Relation, Resource  # noqa: E402
from waymark8 import Safety as WSafety  # noqa: E402
from waymark8 import action as waction  # noqa: E402

span_ok = Relation(judges=("start", "end"), op="<=", name="span_ok",
                   explain="{start} must not be after {end}.")


class BState(StrEnum):
    OPEN = "open"


class BData(BaseModel):
    pass


class SpanInput(BaseModel):
    start: date
    end: date


class Booking(Resource):
    kind = "booking"
    State = BState
    Data = BData
    initial = BState.OPEN
    terminal: set = set()
    summary = "booking · {state.label}"

    @waction(from_=BState.OPEN, to=BState.OPEN, input=SpanInput,
             guards=[span_ok],
             safety=WSafety(idempotent=True, reversible=True, confirm=False))
    async def book(self, inp: SpanInput, ctx: WCtx) -> None:
        pass


async def test_comparison_is_a_relation():
    """v2's relates= folded into Relation (one spelling per concept): a
    comparison advertises x-display.relation on both fields and enforces
    the same declaration on invoke."""
    from waymark8.core.types import Allow, Deny

    rdef = Registry().register(Booking)
    props = rdef.action_schemas["book"][0]["properties"]
    assert props["start"]["x-display"]["relation"] == \
        {"op": "<=", "side": "left", "with": "end"}
    assert props["end"]["x-display"]["relation"] == \
        {"op": "<=", "side": "right", "with": "start"}

    inst = Booking(id="1", state="open", data=BData())
    ctx = WCtx(principal=None, now=None)
    bad = SpanInput(start=date(2026, 7, 9), end=date(2026, 7, 7))
    good = SpanInput(start=date(2026, 7, 7), end=date(2026, 7, 9))
    assert isinstance((await span_ok.evaluate(inst, bad, ctx))[0], Deny)
    assert isinstance((await span_ok.evaluate(inst, good, ctx))[0], Allow)

    # the retired spelling teaches its replacement
    with pytest.raises(DefinitionError, match="Relation"):
        Guard(explain="x", relates=("start", "<=", "end"))
