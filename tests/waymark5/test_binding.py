"""First-class binding (design §4): the picker is declared once on the
Ref (``{item.*}`` params resolve against the binding), the engine
maintains denormalized Ref labels in nested models, and part-bound draft
hrefs are emitted, never string-patched.
"""
from __future__ import annotations

import os
import uuid
from datetime import date

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

import waymark5
from waymark5.server.bus import InProcessBus
from waymark5.server.engine import header_principal
from waymark5.testing import per_worker_dsn

from mealplan5.resources.meal import Meal
from mealplan5.resources.plan import MealPlan
from mealplan5.resources.prep_task import PrepTask
from mealplan5.resources.rotation import SundayRotation

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

TUESDAY = date(2026, 7, 7)  # mexican night


@pytest.fixture
async def env():
    engine = waymark5.Engine(
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


async def _setup(client) -> tuple[str, dict]:
    resp = await _post(client, "/api/meals",
                       {"name": "Tacos", "themes": ["mexican"]})
    meal = resp.json()
    await _post(client, f"{meal['self']}/-/accept")
    resp = await _post(client, "/api/plans",
                       {"start_date": TUESDAY.isoformat(), "weeks": 1})
    return meal["self"].rsplit("/", 1)[-1], resp.json()


def _day_entry(doc, day, action):
    items = doc["parts"]["days"]["items"]
    part = next(it for it in items if it["key"] == day.isoformat())
    return part["actions"][action]


async def test_picker_params_come_from_the_ref_alone(env):
    _, plan = await _setup(env)
    doc = (await env.get(plan["self"])).json()

    # bound: {item.theme} resolved to this day's theme — declared on the
    # Ref, no field_display duplicate anywhere in the definition
    bound = _day_entry(doc, TUESDAY, "assign_meal")
    params = bound["input"]["properties"]["meal_id"]["x-display"]["params"]
    assert params == {"state": "on_list", "themes": "mexican"}

    # unbound (top-level): the templated param is dropped as unresolvable
    top = doc["actions"]["assign_meal"]
    top_params = top["input"]["properties"]["meal_id"]["x-display"]["params"]
    assert top_params == {"state": "on_list"}

    # off-theme override: deliberately looser (field_display replaces the
    # Ref's params — a declared difference, not a duplicate)
    off = _day_entry(doc, TUESDAY, "assign_off_theme")
    off_params = off["input"]["properties"]["meal_id"]["x-display"]["params"]
    assert off_params == {"state": "on_list"}


async def test_engine_maintains_nested_ref_labels(env):
    meal_id, plan = await _setup(env)
    resp = await _post(env, f"{plan['self']}/-/assign_meal",
                       {"date": TUESDAY.isoformat(), "meal_id": meal_id})
    assert resp.status_code == 200, resp.text
    day = next(d for d in resp.json()["data"]["days"]
               if d["date"] == TUESDAY.isoformat())
    # the handler wrote only meal_id; the label is generated (design §4)
    assert day["meal_name"] == "Tacos"

    # clearing the ref clears the label the same way
    resp = await _post(env, f"{plan['self']}/-/clear_day",
                       {"date": TUESDAY.isoformat()})
    day = next(d for d in resp.json()["data"]["days"]
               if d["date"] == TUESDAY.isoformat())
    assert day["meal_id"] is None and day["meal_name"] is None
