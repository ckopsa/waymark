"""The mealplan9 pantry-prices story: an ingredient is a resource, a
product is how a store sells it, and a price is a sighting.

The AI is a client here, exactly as it is for meals: parsing a receipt or
scraping a shelf means creating suggested rows and recording sightings
through the same action surface a human would use. The error-prone step —
this receipt line ↔ that ingredient — is what the product lifecycle
guards; the rollups the family reads (latest price, cents per 100 g,
staleness) are derived facts nobody recomputes by hand; and the per-store
trip question is a collection query, not code.
"""
from __future__ import annotations

import os
import uuid
from datetime import datetime, timezone

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

import waymark9
from waymark9.server.bus import InProcessBus
from waymark9.server.engine import header_principal
from waymark9.testing import per_worker_dsn

from mealplan9.event_source import FakeEvents
from mealplan9.resources.event import Event
from mealplan9.resources.grocery_list import GroceryList
from mealplan9.resources.ingredient import Ingredient
from mealplan9.resources.meal import Meal
from mealplan9.resources.meal_line import MealLine
from mealplan9.resources.plan import MealPlan
from mealplan9.resources.prep_task import PrepTask
from mealplan9.resources.product import Product
from mealplan9.resources.rotation import SundayRotation
from mealplan9.resources.substitution import Substitution
from mealplan9.services import Services

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

PRIYA = {"X-Principal-Id": "priya", "X-Principal-Display": "Priya"}


@pytest.fixture
async def env():
    Event.adapter = FakeEvents()
    engine = waymark9.Engine(
        resources=[Meal, MealLine, SundayRotation, MealPlan, GroceryList,
                   PrepTask, Ingredient, Product, Substitution, Event],
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


def _id(doc) -> str:
    return doc["self"].rsplit("/", 1)[-1]


async def _fresh(client, doc) -> dict:
    res = await client.get(doc["self"])
    assert res.status_code == 200, res.text
    return res.json()


async def _ingredient(client, name: str, **data) -> dict:
    res = await _post(client, "/api/ingredients", {"name": name, **data})
    assert res.status_code == 201, res.text
    return res.json()


async def test_the_family_learns_what_dinner_costs(env):
    engine, client = env

    # ── the AI, parsing a receipt, proposes the pantry concept ──────────
    thighs = await _ingredient(
        client, "Chicken thighs",
        aliases=["boneless skinless chicken thighs"],
        category="meat", preferred_stores=["costco", "winco"])
    assert thighs["state"] == "suggested"
    accepted = await _post(client, f"{thighs['self']}/-/accept")
    assert accepted.status_code == 200, accepted.text

    # ── an unknown receipt line becomes a suggested product, born with
    #    the observation that minted it ─────────────────────────────────
    res = await _post(client, "/api/products", {
        "ingredient_id": _id(thighs), "store": "costco",
        "name": "Kirkland chicken thighs 2.72 kg", "package_grams": 2720,
        "upc": "096619123456",
        "sightings": [{"seen_on": "2026-07-01", "price_cents": 1899,
                       "source": "receipt", "ref": "costco-2026-07-01",
                       "quantity": 2}]})
    assert res.status_code == 201, res.text
    kirkland = res.json()
    assert kirkland["state"] == "suggested"
    # the rollups are already facts on the row — nobody recomputed them.
    # price_cents is per package ($18.99 each, 2 bought); quantity is
    # spend context, never a divisor
    assert kirkland["data"]["latest_price_cents"] == 1899
    assert kirkland["data"]["cents_per_100g"] == 70  # 1899 / 2720 g · 100
    assert kirkland["data"]["last_seen_on"] == "2026-07-01"
    assert kirkland["data"]["price_is_stale"] is False
    # the Ref label is the engine's to maintain
    assert kirkland["data"]["ingredient_name"] == "Chicken thighs"

    # ── the human verdict on the match ──────────────────────────────────
    confirmed = await _post(client, f"{kirkland['self']}/-/confirm_match")
    assert confirmed.status_code == 200, confirmed.text

    # ── a scrape a week later refreshes the price; a same-day re-record
    #    replaces instead of duplicating ──────────────────────────────────
    await _post(client, f"{kirkland['self']}/-/record_sighting",
                {"seen_on": "2026-07-08", "price_cents": 2099,
                 "source": "scrape", "ref": "https://costco.example/thighs"})
    await _post(client, f"{kirkland['self']}/-/record_sighting",
                {"seen_on": "2026-07-08", "price_cents": 1999,
                 "source": "scrape", "ref": "https://costco.example/thighs"})
    data = (await _fresh(client, kirkland))["data"]
    assert len(data["sightings"]) == 2, "one sighting per day — replaced"
    assert data["latest_price_cents"] == 1999
    assert data["cents_per_100g"] == 73

    # ── the same ingredient at another store: unit prices compare ───────
    res = await _post(client, "/api/products", {
        "ingredient_id": _id(thighs), "store": "winco",
        "name": "WinCo chicken thighs 1 kg", "package_grams": 1000,
        "sightings": [{"seen_on": "2026-07-08", "price_cents": 899,
                       "source": "receipt", "ref": "winco-2026-07-08"}]})
    winco = res.json()
    await _post(client, f"{winco['self']}/-/confirm_match")

    # the per-store trip view and the cheapest-first comparison are both
    # collection queries — no client-side math
    at_costco = (await client.get(
        "/api/products", params={"store": "costco"})).json()["data"]["items"]
    assert [p["data"]["name"] for p in at_costco] == [
        "Kirkland chicken thighs 2.72 kg"]
    by_unit_price = (await client.get(
        "/api/products", params={"ingredient_id": _id(thighs),
                                 "sort": "cents_per_100g"})).json()["data"]["items"]
    assert [p["data"]["store"] for p in by_unit_price] == ["costco", "winco"]

    # the ingredient side: preferred stores filter (the trip planner's
    # entry point) and the tracked-product rollup
    prefers_costco = (await client.get(
        "/api/ingredients",
        params={"preferred_stores": "costco"})).json()["data"]["items"]
    assert _id(thighs) in [_id(i) for i in prefers_costco]
    fresh_thighs = await _fresh(client, thighs)
    assert fresh_thighs["data"]["products_tracked"] == 2
    # the ingredient page shows how stores sell it: the products link is
    # the filtered child collection, badged with the tracked count
    products_link = fresh_thighs["links"]["products"]
    assert products_link["badge"] == 2
    assert f"ingredient_id={_id(thighs)}" in products_link["href"]
    linked = (await client.get(products_link["href"])).json()["data"]["items"]
    assert {p["data"]["store"] for p in linked} == {"costco", "winco"}

    # ── dedupe: the AI later minted "Chicken thigh" and matched a product
    #    to it; the survivor absorbs — names fold in, products repoint,
    #    the duplicate retires ────────────────────────────────────────────
    dup = await _ingredient(client, "Chicken thigh", category="meat")
    await _post(client, f"{dup['self']}/-/accept")
    res = await _post(client, "/api/products", {
        "ingredient_id": _id(dup), "store": "winco",
        "name": "WinCo thigh family pack", "package_grams": 2000,
        "sightings": [{"seen_on": "2026-07-08", "price_cents": 1699,
                       "source": "receipt", "ref": "winco-2026-07-08"}]})
    stray = res.json()
    absorbed = await _post(client, f"{thighs['self']}/-/absorb",
                           {"duplicate_id": _id(dup)})
    assert absorbed.status_code == 200, absorbed.text
    merged = await _fresh(client, thighs)
    assert "Chicken thigh" in merged["data"]["aliases"]
    assert merged["data"]["products_tracked"] == 3
    stray_now = await _fresh(client, stray)
    assert stray_now["data"]["ingredient_id"] == _id(thighs)
    assert stray_now["state"] == "tracked", \
        "absorbing repoints AND confirms — the human directed the match"
    assert (await _fresh(client, dup))["state"] == "retired"

    # a survivor with tracked products warns before retiring
    refused = await _post(client, f"{thighs['self']}/-/retire")
    assert refused.status_code == 409, refused.text

    # ── a month of silence and the clock flips the scraper's queue on —
    #    no write, just the sweep over the declared flip times ───────────
    assert (await client.get(
        "/api/products", params={"state": "tracked",
                                 "price_is_stale": "true"}
    )).json()["data"]["items"] == []
    await engine.tick(now=datetime(2026, 9, 1, tzinfo=timezone.utc))
    stale = (await client.get(
        "/api/products", params={"state": "tracked",
                                 "price_is_stale": "true"})).json()["data"]["items"]
    assert len(stale) == 3, "everything is a month unpriced — all queued"


async def test_the_grocery_list_knows_what_it_costs(env):
    """Pantry-prices phase 2: the AI stamps per-item estimates when it
    compiles the list; the list's own derived law does the arithmetic."""
    engine, client = env

    thighs = await _ingredient(client, "Chicken thighs", category="meat")
    await _post(client, f"{thighs['self']}/-/accept")

    # a covered, finalized week for the list to shop for
    res = await _post(client, "/api/plans",
                      {"start_date": "2026-07-14", "weeks": 1})
    plan = res.json()
    for day in plan["data"]["days"]:
        await _post(client, f"{plan['self']}/-/mark_eating_out",
                    {"date": day["date"]})
    await _post(client, f"{plan['self']}/-/finalize")

    res = await _post(client, "/api/grocery_lists",
                      {"plan_id": _id(plan)})
    glist = res.json()
    # a fresh list totals zero — the facts exist before any item does
    assert glist["data"]["estimated_total_cents"] == 0
    assert glist["data"]["priced_items"] == 0

    await _post(client, f"{glist['self']}/-/add_item",
                {"name": "Chicken thighs", "quantity": "3000 g",
                 "category": "meat", "ingredient_id": _id(thighs),
                 "est_cost_cents": 4298})
    await _post(client, f"{glist['self']}/-/add_item",
                {"name": "Cilantro", "quantity": "30 g",
                 "category": "produce"})  # no priced product yet
    data = (await _fresh(client, glist))["data"]
    assert data["estimated_total_cents"] == 4298
    assert data["priced_items"] == 1

    # re-adding the same item re-prices it — add_item is the upsert
    await _post(client, f"{glist['self']}/-/add_item",
                {"name": "Chicken thighs", "est_cost_cents": 2149})
    data = (await _fresh(client, glist))["data"]
    assert data["estimated_total_cents"] == 2149
    assert len(data["items"]) == 2

    # the plan carries the same facts, rolled up from its lists' totals —
    # the list write flipped the parent's Sums in the same commit
    plan_data = (await _fresh(client, plan))["data"]
    assert plan_data["est_grocery_cost_cents"] == 2149
    assert plan_data["priced_grocery_items"] == 1
    assert plan_data["total_grocery_items"] == 2


async def test_a_meal_knows_what_it_potentially_costs(env):
    """Recipe lines are meal_line ROWS: (meal, ingredient, grams) is a
    create — the estimate prices itself from tracked products, and the
    meal's cost facts are engine-maintained rollups over the edge."""
    engine, client = env

    thighs = await _ingredient(client, "Chicken thighs", category="meat")
    await _post(client, f"{thighs['self']}/-/accept")
    sauce = await _ingredient(client, "BBQ sauce", category="pantry")
    await _post(client, f"{sauce['self']}/-/accept")

    # a tracked, priced product is what makes the lookup possible:
    # $18.99 / 2720 g → 70¢ per 100 g
    res = await _post(client, "/api/products", {
        "ingredient_id": _id(thighs), "store": "costco",
        "name": "Kirkland chicken thighs 2.72 kg", "package_grams": 2720,
        "sightings": [{"seen_on": "2026-07-01", "price_cents": 1899,
                       "source": "receipt", "ref": "costco-2026-07-01"}]})
    await _post(client, f"{res.json()['self']}/-/confirm_match")

    res = await _post(client, "/api/meals", {
        "name": "Traeger BBQ chicken thighs", "themes": ["bbq"],
        "recipe": "# Traeger BBQ chicken thighs\n\nTraeger at 275°F…"})
    meal = res.json()
    await _post(client, f"{meal['self']}/-/accept")

    res = await _post(client, "/api/meal_lines", {
        "meal_id": _id(meal), "ingredient_id": _id(thighs), "grams": 1400})
    assert res.status_code == 201, res.text
    line = res.json()
    # 1400 g × 70¢/100g — priced at create, no stamp supplied; labels are
    # the engine's to maintain
    assert line["data"]["est_cost_cents"] == 980
    assert line["data"]["priced"] is True
    assert line["data"]["ingredient_name"] == "Chicken thighs"
    assert line["data"]["meal_name"] == "Traeger BBQ chicken thighs"
    await _post(client, "/api/meal_lines", {
        "meal_id": _id(meal), "ingredient_id": _id(sauce),
        "grams": 250})  # no priced product yet — stays blank

    # the meal's facts are rollups over the edge — the line writes flipped
    # them in the same commits, and the lines link badges the count
    data = (await _fresh(client, meal))["data"]
    assert data["est_cost_cents"] == 980
    assert data["priced_ingredients"] == 1
    assert data["total_ingredients"] == 2
    fresh_meal = await _fresh(client, meal)
    assert fresh_meal["links"]["ingredients"]["badge"] == 2

    # prices move: a cheaper product appears AFTER the line was written —
    # the meal's reprice fans out to every on_recipe line
    res = await _post(client, "/api/products", {
        "ingredient_id": _id(thighs), "store": "winco",
        "name": "WinCo chicken thighs 1 kg", "package_grams": 1000,
        "sightings": [{"seen_on": "2026-07-09", "price_cents": 500,
                       "source": "receipt", "ref": "winco-2026-07-09"}]})
    await _post(client, f"{res.json()['self']}/-/confirm_match")
    await _post(client, f"{meal['self']}/-/reprice")
    assert (await _fresh(client, line))["data"]["est_cost_cents"] == 700
    assert (await _fresh(client, meal))["data"]["est_cost_cents"] == 700

    # re-quantity re-prices (set_grams is an Edit — fenced); removing a
    # line is a transition, and the rollups follow
    fresh_line = await _fresh(client, line)
    res = await _post(client, f"{line['self']}/-/set_grams", {"grams": 700},
                      **{"If-Match": fresh_line["meta"]["etag"]})
    assert res.status_code == 200, res.text
    assert (await _fresh(client, line))["data"]["est_cost_cents"] == 350
    lines = (await client.get(
        "/api/meal_lines", params={"meal_id": _id(meal),
                                   "state": "on_recipe"})).json()["data"]["items"]
    sauce_line = next(l for l in lines
                      if l["data"]["ingredient_id"] == _id(sauce))
    await _post(client, f"{sauce_line['self']}/-/remove")
    data = (await _fresh(client, meal))["data"]
    assert data["total_ingredients"] == 1
    assert data["est_cost_cents"] == 350


async def test_a_substitute_prices_the_unpriceable(env):
    """Substitution: the AI proposes, the family accepts, and an
    unpriceable line prices through the stand-in — marked, never silent."""
    engine, client = env

    butter = await _ingredient(client, "Butter", category="dairy")
    await _post(client, f"{butter['self']}/-/accept")
    margarine = await _ingredient(client, "Margarine", category="dairy")
    await _post(client, f"{margarine['self']}/-/accept")

    # margarine is priced ($3.49 / 454 g → 77¢/100g); butter is not
    res = await _post(client, "/api/products", {
        "ingredient_id": _id(margarine), "store": "winco",
        "name": "WinCo margarine 454 g", "package_grams": 454,
        "sightings": [{"seen_on": "2026-07-10", "price_cents": 349,
                       "source": "receipt", "ref": "winco-2026-07-10"}]})
    await _post(client, f"{res.json()['self']}/-/confirm_match")

    # self-substitution is unrepresentable — the create gate judges the
    # declared fact
    res = await _post(client, "/api/substitutions", {
        "from_ingredient_id": _id(butter),
        "to_ingredient_id": _id(butter)})
    assert res.status_code == 409, res.text

    res = await _post(client, "/api/substitutions", {
        "from_ingredient_id": _id(butter),
        "to_ingredient_id": _id(margarine),
        "ratio": 1.0, "context": "baking and sautéing"})
    assert res.status_code == 201, res.text
    sub = res.json()
    assert sub["state"] == "suggested"

    # a butter line while the substitution awaits its verdict: unpriced —
    # suggested substitutions price nothing
    meal = (await _post(client, "/api/meals", {
        "name": "Butter pancakes", "themes": ["american"]})).json()
    await _post(client, f"{meal['self']}/-/accept")
    line = (await _post(client, "/api/meal_lines", {
        "meal_id": _id(meal), "ingredient_id": _id(butter),
        "grams": 200})).json()
    assert line["data"]["est_cost_cents"] is None

    # the family verdict, then a reprice: 200 g × 77¢/100g = 154¢, and the
    # estimate says what it priced through
    await _post(client, f"{sub['self']}/-/accept")
    await _post(client, f"{line['self']}/-/reprice")
    data = (await _fresh(client, line))["data"]
    assert data["est_cost_cents"] == 154
    assert data["priced_via"] == "Margarine"
    # the meal rollup includes the substituted estimate
    assert (await _fresh(client, meal))["data"]["est_cost_cents"] == 154

    # a direct price arriving later wins back the line: priced as itself
    res = await _post(client, "/api/products", {
        "ingredient_id": _id(butter), "store": "costco",
        "name": "Kirkland butter 1.81 kg", "package_grams": 1810,
        "sightings": [{"seen_on": "2026-07-11", "price_cents": 1099,
                       "source": "receipt", "ref": "costco-2026-07-11"}]})
    assert res.status_code == 201, res.text
    butter_product = res.json()
    r = await _post(client, f"{butter_product['self']}/-/confirm_match")
    assert r.status_code == 200, r.text
    fresh_bp = (await _fresh(client, butter_product))["data"]
    assert fresh_bp["cents_per_100g"] == 61, fresh_bp
    r = await _post(client, f"{line['self']}/-/reprice")
    assert r.status_code == 200, r.text
    data = (await _fresh(client, line))["data"]
    assert data["est_cost_cents"] == 122  # 200 g × 61¢/100g, as butter
    assert data["priced_via"] is None
