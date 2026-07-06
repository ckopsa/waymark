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

import waymark3
from waymark3.server.bus import InProcessBus
from waymark3.server.engine import header_principal
from waymark3.testing import per_worker_dsn

from mealplan3.resources.meal import Meal, MealData
from mealplan3.resources.plan import MealPlan
from mealplan3.resources.prep_task import PrepTask
from mealplan3.resources.rotation import SundayRotation

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

TUESDAY = date(2026, 7, 7)  # weekday themes: mexican; Wed: american; Fri: pizza


@pytest.fixture
async def env():
    engine = waymark3.Engine(
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
    client._engine = engine  # for tests that reach into storage
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


async def test_shape1_rows_upcast_at_read(env):
    """A single-theme-era row is a *declared* shape now (design §8): stored
    at shape 1, upcast through Meal.upcasts on hydrate — no silent
    before-validator, and the row itself is untouched (lazy migration)."""
    from sqlalchemy import text as sql

    engine = env._engine
    async with engine.storage.session() as s:
        await s.execute(sql(
            "INSERT INTO meals (id, state, version, data, shape, created_at, "
            "updated_at) VALUES ('legacy1', 'on_list', 1, "
            '\'{"name": "Brisket", "theme": "bbq"}\'::jsonb, 1, now(), now())'))
    async with engine.storage.session() as s:
        instance = await engine.storage.load(s, "meal", "legacy1")
    assert instance.data.themes == ["bbq"]
    assert not hasattr(instance.data, "theme")
    # the stored row still says shape 1 until its next write
    async with engine.storage.session() as s:
        row = (await s.execute(sql(
            "SELECT shape FROM meals WHERE id = 'legacy1'"))).scalar_one()
    assert row == 1


async def test_vocab_is_one_declaration():
    """Design §6: the Vocab field carries filter + facet + wire hint; the
    resource declares neither filterable nor faceted for it."""
    from waymark3.core.resource import FilterOp

    # merged into the filter/facet surface at import — not declared twice
    assert Meal.filterable.fields["themes"] == FilterOp.EQ | FilterOp.IN
    assert "themes" in Meal.faceted

    # and the declaration reaches the wire
    schema = MealData.model_json_schema()
    spec = schema["properties"]["themes"]["x-vocab"]
    assert spec["open"] is True
    assert spec["facet"] == {"observed": True, "counts": True}


async def test_placeholder_is_declared_on_the_wire():
    from mealplan3.resources.plan import DayPlan

    spec = DayPlan.model_json_schema()["properties"]["theme"]["x-vocab"]
    assert spec["placeholder"] == "rotating"


async def test_upcast_chain_must_be_contiguous():
    from waymark3 import DefinitionError, Resource

    with pytest.raises(DefinitionError, match="upcasts"):
        class Gapped(Meal):
            kind = "gapped_meal"
            plural = "gapped_meals"
            shape = 3
            upcasts = {2: lambda d: d}  # missing 1 → 2


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


async def test_theme_facets_render_as_the_filter_enum(env):
    await _create_meal(env, "Fajitas", ["mexican", "american"])
    await _create_meal(env, "Brisket", ["bbq"])
    await _create_meal(env, "Tacos", ["mexican"])

    resp = await env.get("/api/meals")
    assert resp.status_code == 200, resp.text
    themes = resp.json()["actions"]["query"]["input"]["properties"]["themes"]

    # per-element counts: a twice-tagged meal counts once per tag
    assert themes["x-facets"] == {"mexican": 2, "american": 1, "bbq": 1}
    # the observed vocabulary is the dropdown; comma lists are advertised
    assert themes["enum"] == ["american", "bbq", "mexican"]
    assert themes["x-in"] is True

    # state facets keep working alongside
    state = resp.json()["actions"]["query"]["input"]["properties"]["state"]
    assert state["x-facets"] == {"suggested": 3}


async def test_faceted_must_be_filterable():
    from enum import StrEnum

    from pydantic import BaseModel

    from waymark3.core.checks import DefinitionError

    with pytest.raises(DefinitionError, match="faceted"):
        class BadState(StrEnum):
            OPEN = "open"

        class BadData(BaseModel):
            tags: list[str] = []

        class Bad(waymark3.Resource):
            kind = "bad_faceted"
            State = BadState
            Data = BadData
            initial = BadState.OPEN
            summary = "{state.label}"
            faceted = ("tags",)  # not declared filterable


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
    # one Relation, one explain template (design §5) — the bespoke per-case
    # prose of v2's check= guard is gone by design
    assert "doesn't serve 2026-07-10's theme night" in denied.text
