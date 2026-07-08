"""Predecessor refs (design E7): the latest earlier sibling — optionally
within a partition — becomes data at create, resolved by the engine. The
holdings/price-validation "previous workbook by date arithmetic" pattern,
declared.
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
    Ctx, DefinitionError, Predecessor, Ref, RefField, Resource, Safety,
    action, filterable,
)
from waymark7.server.bus import InProcessBus
from waymark7.server.engine import header_principal
from waymark7.testing import per_worker_dsn

from tests.waymark7.mealplan7.grocery_list import GroceryList
from tests.waymark7.mealplan7.meal import Meal
from tests.waymark7.mealplan7.plan import MealPlan
from tests.waymark7.mealplan7.prep_task import PrepTask
from tests.waymark7.mealplan7.rotation import SundayRotation

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


class LedgerState(StrEnum):
    OPEN = "open"


class LedgerData(BaseModel):
    fund_id: str = Field(min_length=1, max_length=64)
    as_of: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}$")
    previous: Ref["ledger"] | None = RefField(
        default=None, predecessor=Predecessor(order="as_of",
                                              partition="fund_id"))
    config: str | None = Field(default=None, max_length=120)


class Ledger(Resource):
    kind = "ledger"
    State = LedgerState
    Data = LedgerData
    initial = LedgerState.OPEN
    terminal: set = set()
    summary = "{data.fund_id} @ {data.as_of} · {state.label}"

    filterable = filterable(fund_id=filterable.Eq, as_of=filterable.Eq)

    async def on_create(self, ctx: Ctx) -> None:
        # carry-forward is app logic reading the resolved ref (design E7)
        if self.data.previous is not None and self.data.config is None:
            prior = await ctx.read("ledger", self.data.previous)
            if prior is not None:
                self.data.config = prior.data.config

    @action(from_=LedgerState.OPEN, to=LedgerState.OPEN,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Touch"))
    async def touch(self, inp: None, ctx: Ctx) -> None:
        pass


@pytest.fixture
async def env():
    engine = waymark7.Engine(
        resources=[Ledger, Meal, SundayRotation, MealPlan, GroceryList,
                   PrepTask],
        storage=TEST_DSN, principal=header_principal, services=None,
        bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t",
                         headers={"X-Principal-Id": "maya"})
    try:
        yield client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _post(client, href, json=None):
    return await client.post(href, json=json,
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def _ledger(client, fund, as_of, config=None) -> dict:
    body = {"fund_id": fund, "as_of": as_of}
    if config:
        body["config"] = config
    res = await _post(client, "/api/ledgers", body)
    assert res.status_code == 201, res.text
    return res.json()


async def test_the_latest_earlier_sibling_resolves_within_the_partition(env):
    """Partitioned chaining with carry-forward: the July ledger follows
    June's — for its own fund — and inherits its config."""
    client = env
    june = await _ledger(client, "fund-88", "2026-06-30",
                         config="hierarchy: a,b")
    other = await _ledger(client, "fund-99", "2026-06-30")

    july = await _ledger(client, "fund-88", "2026-07-31")
    assert july["data"]["previous"] == june["self"].rsplit("/", 1)[-1], \
        "the predecessor is data, not date arithmetic"
    assert july["data"]["config"] == "hierarchy: a,b", \
        "on_create read the resolved ref and carried the config forward"
    assert other["data"]["previous"] is None, \
        "the first of a partition follows nothing"

    aug = await _ledger(client, "fund-88", "2026-08-31")
    assert aug["data"]["previous"] == july["self"].rsplit("/", 1)[-1], \
        "the chain follows the latest, not the first"


async def test_backdated_creates_slot_into_order(env):
    client = env
    june = await _ledger(client, "fund-88", "2026-06-30")
    aug = await _ledger(client, "fund-88", "2026-08-31")
    may = await _ledger(client, "fund-88", "2026-05-31")
    assert may["data"]["previous"] is None, \
        "an earlier period has no earlier sibling"
    july = await _ledger(client, "fund-88", "2026-07-31")
    assert july["data"]["previous"] == june["self"].rsplit("/", 1)[-1], \
        "order means the declared field, not insertion time"


async def test_the_plan_dogfood_chains_weeks(env):
    client = env
    start = date(2026, 6, 2)
    first = await _post(client, "/api/plans",
                        {"start_date": start.isoformat(), "weeks": 1})
    second = await _post(
        client, "/api/plans",
        {"start_date": (start + timedelta(days=7)).isoformat(), "weeks": 1})
    assert second.json()["data"]["previous_plan"] \
        == first.json()["self"].rsplit("/", 1)[-1]


async def test_predecessor_order_must_be_promoted(env):
    with pytest.raises(DefinitionError):
        class BadLedger(Resource):
            kind = "bad_ledger"
            State = LedgerState
            Data = LedgerData
            initial = LedgerState.OPEN
            summary = "x"
            filterable = None  # order/partition unpromoted

        reg = waymark7.Registry()
        reg.register(BadLedger)
        from waymark7.core import checks
        checks.check_refs(reg)
