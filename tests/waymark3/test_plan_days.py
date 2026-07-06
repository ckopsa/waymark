"""Day-shaped plan actions: eating out can say where.

``mark_eating_out`` takes an optional ``where`` note that lives on the day;
anything that turns the day back into a cooking (or blank) day clears it.
"""
from __future__ import annotations

import os
import uuid
from datetime import date

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

import waymark3
from waymark3.server.bus import InProcessBus
from waymark3.server.engine import header_principal
from waymark3.testing import per_worker_dsn

from mealplan3.resources.meal import Meal
from mealplan3.resources.plan import MealPlan
from mealplan3.resources.rotation import SundayRotation

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

TUESDAY = date(2026, 7, 7)


@pytest.fixture
async def env():
    engine = waymark3.Engine(
        resources=[Meal, SundayRotation, MealPlan], storage=TEST_DSN,
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


async def _act(client, href: str, action: str, body: dict) -> dict:
    resp = await client.post(f"{href}/-/{action}", json=body,
                             headers={"Idempotency-Key": uuid.uuid4().hex})
    assert resp.status_code == 200, resp.text
    return resp.json()


def _day(doc: dict, day: date) -> dict:
    return next(d for d in doc["data"]["days"] if d["date"] == day.isoformat())


async def test_eating_out_notes_where_until_the_day_changes(env):
    resp = await env.post("/api/plans",
                          json={"start_date": TUESDAY.isoformat()},
                          headers={"Idempotency-Key": uuid.uuid4().hex})
    assert resp.status_code == 201, resp.text
    plan = resp.json()

    doc = await _act(env, plan["self"], "mark_eating_out",
                     {"date": TUESDAY.isoformat(), "where": "Casa Amigos"})
    day = _day(doc, TUESDAY)
    assert day["eating_out"] is True
    assert day["eating_out_where"] == "Casa Amigos"

    # the note is optional — re-marking without one replaces it
    doc = await _act(env, plan["self"], "mark_eating_out",
                     {"date": TUESDAY.isoformat()})
    assert _day(doc, TUESDAY)["eating_out_where"] is None

    doc = await _act(env, plan["self"], "mark_eating_out",
                     {"date": TUESDAY.isoformat(), "where": "grandma's"})
    assert _day(doc, TUESDAY)["eating_out_where"] == "grandma's"

    # assigning a meal turns the day back into a cooking day: note gone
    created = await env.post(
        "/api/meals", json={"name": "Tacos", "themes": ["mexican"]},
        headers={"Idempotency-Key": uuid.uuid4().hex})
    assert created.status_code == 201, created.text
    meal = await _act(env, created.json()["self"], "accept", {})
    doc = await _act(env, plan["self"], "assign_meal",
                     {"date": TUESDAY.isoformat(),
                      "meal_id": meal["self"].rsplit("/", 1)[-1]})
    day = _day(doc, TUESDAY)
    assert day["eating_out"] is False
    assert day["eating_out_where"] is None
    assert day["meal_name"] == "Tacos"

    # and clear_day scrubs everything
    doc = await _act(env, plan["self"], "mark_eating_out",
                     {"date": TUESDAY.isoformat(), "where": "ward picnic"})
    doc = await _act(env, plan["self"], "clear_day",
                     {"date": TUESDAY.isoformat()})
    day = _day(doc, TUESDAY)
    assert day["eating_out"] is False
    assert day["eating_out_where"] is None
