"""Multi-theme meals: a meal is tagged with every theme night it can serve.

Covers the whole path the feature depends on: array fields promoted to
JSONB generated columns (Eq = @> membership, In = ?| any-of), the retagging
action, legacy single-``theme`` payloads and rows, and the plan guard
accepting a multi-tagged meal on any of its nights.
"""
from __future__ import annotations

import os
import uuid
from datetime import date

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

import waymark2
from waymark2.server.bus import InProcessBus
from waymark2.server.engine import header_principal
from waymark2.testing import per_worker_dsn

from mealplan.resources.meal import Meal, MealData
from mealplan.resources.plan import MealPlan
from mealplan.resources.rotation import SundayRotation

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

TUESDAY = date(2026, 7, 7)  # weekday themes: mexican; Wed: american; Fri: pizza


@pytest.fixture
async def env():
    engine = waymark2.Engine(
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


async def _create_meal(client: AsyncClient, name: str,
                       themes: list[str]) -> dict:
    resp = await client.post(
        "/api/meals", json={"name": name, "themes": themes},
        headers={"Idempotency-Key": uuid.uuid4().hex})
    assert resp.status_code == 201, resp.text
    return resp.json()


async def _accept(client: AsyncClient, doc: dict) -> dict:
    resp = await client.post(f"{doc['self']}/-/accept", json={},
                             headers={"Idempotency-Key": uuid.uuid4().hex})
    assert resp.status_code == 200, resp.text
    return resp.json()


async def test_legacy_single_theme_rows_still_hydrate():
    # storage hydrates rows through Data.model_validate — a single-theme-era
    # row (not yet backfilled by 0002) folds into a one-tag list
    assert MealData.model_validate(
        {"name": "Brisket", "theme": "bbq"}).themes == ["bbq"]
    # an old payload carrying both keeps the richer list
    assert MealData.model_validate(
        {"name": "Fajitas", "theme": "mexican",
         "themes": ["mexican", "american"]}).themes == ["mexican", "american"]


async def test_theme_filter_is_membership(env):
    await _create_meal(env, "Fajitas", ["mexican", "american"])
    await _create_meal(env, "Brisket", ["bbq"])

    async def names(query: str) -> set[str]:
        resp = await env.get(f"/api/meals?{query}")
        assert resp.status_code == 200, resp.text
        return {item["data"]["name"]
                for item in resp.json()["data"]["items"]}

    assert await names("themes=mexican") == {"Fajitas"}
    assert await names("themes=american") == {"Fajitas"}
    assert await names("themes=bbq") == {"Brisket"}
    assert await names("themes=pizza") == set()
    # comma list is the In op: tagged with any of them
    assert await names("themes=bbq,mexican") == {"Fajitas", "Brisket"}


async def test_update_themes_retags(env):
    doc = await _accept(env, await _create_meal(env, "Fried rice", ["asian"]))

    advert = doc["actions"]["update_themes"]
    assert advert["input"]["properties"]["themes"]["default"] == ["asian"]

    resp = await env.post(
        f"{doc['self']}/-/update_themes",
        json={"themes": ["asian", "american", "asian"]},
        headers={"Idempotency-Key": uuid.uuid4().hex,
                 "If-Match": doc["meta"]["etag"]})
    assert resp.status_code == 200, resp.text
    updated = resp.json()
    assert updated["data"]["themes"] == ["asian", "american"]  # deduped
    assert "asian, american" in updated["summary"]

    # the edit is fenced: without If-Match the write is refused
    resp = await env.post(
        f"{doc['self']}/-/update_themes", json={"themes": ["pizza"]},
        headers={"Idempotency-Key": uuid.uuid4().hex})
    assert resp.status_code in (409, 412, 428), resp.text


async def test_multi_tagged_meal_assigns_on_any_of_its_nights(env):
    meal = await _accept(env, await _create_meal(
        env, "Fajitas", ["mexican", "american"]))
    meal_id = meal["self"].rsplit("/", 1)[-1]

    resp = await env.post(
        "/api/plans", json={"start_date": TUESDAY.isoformat()},
        headers={"Idempotency-Key": uuid.uuid4().hex})
    assert resp.status_code == 201, resp.text
    plan = resp.json()

    async def assign(day: date):
        return await env.post(
            f"{plan['self']}/-/assign_meal",
            json={"date": day.isoformat(), "meal_id": meal_id},
            headers={"Idempotency-Key": uuid.uuid4().hex})

    ok = await assign(TUESDAY)  # mexican night
    assert ok.status_code == 200, ok.text
    ok = await assign(date(2026, 7, 8))  # american night
    assert ok.status_code == 200, ok.text

    denied = await assign(date(2026, 7, 10))  # pizza night
    assert denied.status_code == 409, denied.text
    assert "pizza night" in denied.text
