"""Ownership (design E4): one declared edge, two consumers — the cascade
runner (parent transitions fan out to owned children as system-actor
transitions sharing the parent's correlation id) and rollups (child
counts on the envelope, gating parent transitions through ``rollup_is``).
Dogfood: the meal plan owns its prep tasks.
"""
from __future__ import annotations

import asyncio
import os
import uuid
from datetime import date, timedelta

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

import waymark9
from waymark9 import DefinitionError, Registry
from waymark9.core import checks
from waymark9.server.bus import InProcessBus
from waymark9.server.engine import header_principal
from waymark9.testing import per_worker_dsn

from tests.waymark9.mealplan7.grocery_list import GroceryList
from tests.waymark9.mealplan7.meal import Meal
from tests.waymark9.mealplan7.plan import MealPlan
from tests.waymark9.mealplan7.prep_task import PrepTask
from tests.waymark9.mealplan7.rotation import SundayRotation

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

START = date(2026, 6, 1)  # safely in the past: `begin` is admissible


@pytest.fixture
async def env():
    engine = waymark9.Engine(
        resources=[Meal, SundayRotation, MealPlan, GroceryList, PrepTask],
        storage=TEST_DSN, principal=header_principal, services=None,
        bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t",
                         headers={"X-Principal-Id": "colton",
                                  "X-Principal-Display": "Colton"})
    try:
        yield engine, client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _post(client, href, json=None):
    return await client.post(href, json=json,
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def _plan(client, start=START) -> dict:
    res = await _post(client, "/api/plans",
                      {"start_date": start.isoformat(), "weeks": 1})
    assert res.status_code == 201, res.text
    return res.json()


async def _task(client, plan_id, *, days_out=3) -> dict:
    res = await _post(client, "/api/prep_tasks", {
        "plan_id": plan_id, "date": (START + timedelta(days=1)).isoformat(),
        "meal_name": "Pulled pork", "task_type": "thaw",
        "due_at": f"{(START + timedelta(days=days_out)).isoformat()}T18:00:00+00:00",
        "duration_minutes": 720})
    assert res.status_code == 201, res.text
    return res.json()


async def _eventually(check, *, timeout=5.0):
    deadline = asyncio.get_event_loop().time() + timeout
    while True:
        result = await check()
        if result:
            return result
        if asyncio.get_event_loop().time() > deadline:
            raise AssertionError("condition not reached in time")
        await asyncio.sleep(0.05)


async def _abandoned_with_tasks(engine, client):
    plan = await _plan(client)
    plan_id = plan["self"].rsplit("/", 1)[-1]
    open_task = await _task(client, plan_id)
    done_task = await _task(client, plan_id)
    res = await _post(client, f"{done_task['self']}/-/complete")
    assert res.status_code == 200, res.text
    res = await _post(client, f"{plan['self']}/-/abandon")
    assert res.status_code == 200, res.text
    return plan, plan_id, open_task, done_task


async def test_abandon_cascades_cancel_to_open_prep_tasks(env):
    """The cascade is a system actor telling one story: the child cancel
    carries the parent abandon's correlation id."""
    engine, client = env
    plan, plan_id, open_task, done_task = \
        await _abandoned_with_tasks(engine, client)

    async def cancelled():
        doc = (await client.get(open_task["self"])).json()
        return doc if doc["state"] == "cancelled" else None
    await _eventually(cancelled)

    task_id = open_task["self"].rsplit("/", 1)[-1]
    async with engine.storage.session() as s:
        cancel = await engine.storage.last_transition(s, "prep_task", task_id)
        abandon = await engine.storage.last_transition(s, "plan", plan_id)
    assert cancel.action == "cancel"
    assert cancel.actor_type == "system"
    assert cancel.actor_id == "waymark-cascade"
    assert cancel.correlation_id == abandon.correlation_id, \
        "one narrative: the cascade rides the parent's correlation id"


async def test_cascade_skips_terminal_children_and_other_plans(env):
    engine, client = env
    other = await _plan(client, start=START + timedelta(days=14))
    other_id = other["self"].rsplit("/", 1)[-1]
    other_task = await _task(client, other_id)

    plan, plan_id, open_task, done_task = \
        await _abandoned_with_tasks(engine, client)

    await _eventually(lambda: _state_is(client, open_task["self"], "cancelled"))
    assert (await client.get(done_task["self"])).json()["state"] == "done", \
        "a completed task is not the cascade's to touch"
    assert (await client.get(other_task["self"])).json()["state"] == "pending", \
        "the via filter keeps other parents' children out of reach"

    done_id = done_task["self"].rsplit("/", 1)[-1]
    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(s, "prep_task", done_id)
    assert last.action == "complete", "no transition row was added to it"


def _state_is(client, href, state):
    async def check():
        doc = (await client.get(href)).json()
        return doc if doc["state"] == state else None
    return check()


async def test_drain_is_idempotent(env):
    """Redelivery is a natural no-op: cascaded children leave the state
    filter, so a second drain adds nothing."""
    engine, client = env
    plan, plan_id, open_task, _ = await _abandoned_with_tasks(engine, client)
    await _eventually(lambda: _state_is(client, open_task["self"], "cancelled"))

    task_id = open_task["self"].rsplit("/", 1)[-1]

    async def count_cancels():
        async with engine.storage.session() as s:
            rows = await engine.storage.transitions_since(s, 0, limit=500)
        return len([t for t in rows
                    if t.kind == "prep_task" and t.resource_id == task_id
                    and t.action == "cancel"])

    before = await count_cancels()
    assert before == 1
    await engine.cascades.drain()
    await engine.cascades.drain()
    assert await count_cancels() == 1


async def test_rollups_ride_resource_collection_and_invoke_envelopes(env):
    engine, client = env
    plan = await _plan(client)
    plan_id = plan["self"].rsplit("/", 1)[-1]
    assert plan["rollups"] == {"open_tasks": 0}, \
        "the create response already carries the rollup"

    await _task(client, plan_id)
    t2 = await _task(client, plan_id)
    doc = (await client.get(plan["self"])).json()
    assert doc["rollups"] == {"open_tasks": 2}

    listing = (await client.get("/api/plans")).json()
    row = next(i for i in listing["data"]["items"]
               if i["self"] == plan["self"])
    assert row["rollups"] == {"open_tasks": 2}, "collection rows carry it"

    res = await _post(client, f"{t2['self']}/-/complete")
    assert res.status_code == 200
    assert (await client.get(plan["self"])).json()["rollups"] \
        == {"open_tasks": 1}


async def test_complete_is_gated_on_the_rollup(env):
    """rollup_is (design E4): the count folds into `unavailable` with the
    same number enforcement will re-derive — the holdings-recon rule
    ('every break explained') as one declaration."""
    engine, client = env
    plan = await _plan(client)
    href = plan["self"]
    plan_id = href.rsplit("/", 1)[-1]
    for i in range(7):
        await _post(client, f"{href}/-/mark_eating_out",
                    {"date": (START + timedelta(days=i)).isoformat()})
    assert (await _post(client, f"{href}/-/finalize")).status_code == 200
    assert (await _post(client, f"{href}/-/begin")).status_code == 200

    task = await _task(client, plan_id)
    doc = (await client.get(href)).json()
    assert "complete" not in doc["actions"]
    entry = doc["unavailable"]["complete"]
    assert "1 prep task(s) are still open" in entry["reason"]
    assert "prep_task.cancel" in entry["remedies"]

    refused = await _post(client, f"{href}/-/complete")
    assert refused.status_code == 409
    assert refused.json()["detail"] == entry["reason"]

    await _post(client, f"{task['self']}/-/cancel")
    res = await _post(client, f"{href}/-/complete")
    assert res.status_code == 200, res.text
    assert res.json()["state"] == "done"


async def test_check_owns_rejects_bad_declarations(env):
    """Assembly-time honesty: every way an Owns edge can lie is an import
    error naming the fix."""
    engine, _ = env

    def registry_with(plan_owns):
        reg = Registry()

        class Plan2(MealPlan):
            kind = "plan"
            owns = plan_owns
        for cls in (Meal, SundayRotation, Plan2, GroceryList, PrepTask):
            reg.register(cls)
        return reg

    from waymark9 import Owns, Rollup

    for bad, why in [
        ((Owns("nope", via="plan_id"),), "unregistered child"),
        ((Owns("prep_task", via="missing"),), "via not a field"),
        ((Owns("prep_task", via="meal_name"),), "via not a Ref"),
        ((Owns("prep_task", via="plan_id", on={"nope": "cancel"}),),
         "unknown parent action"),
        ((Owns("prep_task", via="plan_id", on={"abandon": "nope"}),),
         "unknown child action"),
        ((Owns("prep_task", via="plan_id", on={"abandon": "schedule"}),),
         "cascade target takes input"),
        ((Owns("prep_task", via="plan_id",
               rollups={"x": Rollup(filters={"nope": 1})}),),
         "rollup filter not filterable"),
    ]:
        with pytest.raises(DefinitionError):
            checks.check_owns(registry_with(bad))
