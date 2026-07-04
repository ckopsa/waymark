"""Root conftest: what an application supplies to `pytest --waymark` (Part III)
— the engine fixture, one state factory per resource, and example inputs where
schema generation can't satisfy semantic guards.
"""
from __future__ import annotations

import os
import uuid
from dataclasses import dataclass, field
from datetime import date, timedelta

import pytest

import waymark
from waymark import Principal
from waymark.testing import example_input, per_worker_dsn, state_factory

from app.resources.order import Order, OrderState
from app.resources.return_workflow import Return, ReturnState
from app.resources.shipment import Shipment, ShipmentState
from app.services import Services, mint_method
from mealplan.resources.grocery_list import GroceryList, GroceryState
from mealplan.resources.meal import Meal, MealState
from mealplan.resources.plan import MealPlan, PlanState
from mealplan.resources.prep_task import PrepState, PrepTask
from mealplan.resources.rotation import RotationState, SundayRotation

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

ORDER_BODY = {
    "items": [{"sku": "A-100", "qty": 2, "price": 12.10}],
    "total": 84.20,
    "currency": "USD",
}

FACTORY_PRINCIPAL = Principal(id="owner", type="human", display="Owner")


@dataclass
class SuiteServices(Services):
    """The example-shop services plus a stash the meal-plan factories use to
    hand real ids to ``@example_input`` functions (which only see services)."""

    seeded: dict[str, str] = field(default_factory=dict)


@pytest.fixture
async def waymark_engine():
    engine = waymark.Engine(resources=[Order, Shipment, Return, Meal,
                                       SundayRotation, MealPlan, GroceryList,
                                       PrepTask],
                            storage=TEST_DSN, services=SuiteServices())
    await engine.storage.drop_all()
    await engine.startup()
    try:
        yield engine
    finally:
        await engine.shutdown()


async def _load(engine, kind: str, id: str):
    async with engine.storage.session() as s:
        return await engine.storage.load(s, kind, id)


@state_factory(Order)
async def make_order(state: str, engine, services) -> Order:
    """Walk real transitions to the requested state (the honest route)."""
    inv = engine.invoker
    created = await inv.create("order", ORDER_BODY,
                               principal=FACTORY_PRINCIPAL,
                               idempotency_key=uuid.uuid4().hex)
    oid = created.doc["self"].rsplit("/", 1)[-1]

    async def step(action: str, body=None, *, principal=FACTORY_PRINCIPAL,
                   etag: str | None = None):
        return await inv.invoke("order", oid, action, body,
                                principal=principal, if_match=etag,
                                idempotency_key=uuid.uuid4().hex)

    if state == OrderState.DRAFT:
        return await _load(engine, "order", oid)
    if state == OrderState.CANCELLED:
        await step("cancel", {"reason": "conformance"})
        return await _load(engine, "order", oid)

    placed = await step("place")
    if state == OrderState.AWAITING_PAYMENT:
        return await _load(engine, "order", oid)

    await step("submit_payment",
               {"payment_method_id": str(mint_method(services))},
               etag=placed.doc["meta"]["etag"])
    if state == OrderState.PAID:
        return await _load(engine, "order", oid)

    await step("fulfil")
    if state == OrderState.FULFILLED:
        return await _load(engine, "order", oid)

    raise waymark.testing.SkipState(state)  # pragma: no cover


@example_input(Order, "submit_payment")
def submit_payment_example(services) -> dict:
    # a schema-generated UUID can never name a real payment method
    return {"payment_method_id": str(mint_method(services))}


MANAGER = Principal(id="manager", type="human", roles=frozenset(["manager"]),
                    display="Manager")


@state_factory(Shipment)
async def make_shipment(state: str, engine, services) -> Shipment:
    order = await make_order(OrderState.PAID, engine, services)
    inv = engine.invoker
    created = await inv.create("shipment", {"order_id": order.id},
                               principal=FACTORY_PRINCIPAL,
                               idempotency_key=uuid.uuid4().hex)
    sid = created.doc["self"].rsplit("/", 1)[-1]
    steps = {ShipmentState.PENDING: [], ShipmentState.SHIPPED: ["ship"],
             ShipmentState.DELIVERED: ["ship", "deliver"]}[ShipmentState(state)]
    for action in steps:
        await inv.invoke("shipment", sid, action, None,
                         principal=FACTORY_PRINCIPAL)
    return await _load(engine, "shipment", sid)


@state_factory(Return)
async def make_return(state: str, engine, services) -> Return:
    order = await make_order(OrderState.PAID, engine, services)
    inv = engine.invoker
    created = await inv.create("return", {"order_id": order.id,
                                          "reason": "changed my mind"},
                               principal=FACTORY_PRINCIPAL,
                               idempotency_key=uuid.uuid4().hex)
    rid = created.doc["self"].rsplit("/", 1)[-1]

    async def step(action, body=None, principal=FACTORY_PRINCIPAL):
        await inv.invoke("return", rid, action, body, principal=principal,
                         idempotency_key=uuid.uuid4().hex)

    target = ReturnState(state)
    if target != ReturnState.AWAITING_ITEM:
        await step("receive_item")
    if target == ReturnState.REJECTED:
        await step("reject", {"condition_ok": False, "notes": "damaged"})
    if target in (ReturnState.REFUNDING, ReturnState.DONE):
        await step("approve", {"condition_ok": True, "notes": "like new"},
                   principal=MANAGER)
    if target == ReturnState.DONE:
        await step("complete")
    return await _load(engine, "return", rid)


@example_input(Return, "approve")
def approve_example(services) -> dict:
    return {"condition_ok": True, "notes": "like new"}


@example_input(Return, "reject")
def reject_example(services) -> dict:
    return {"condition_ok": False, "notes": "damaged"}


# ── Meal-plan app ────────────────────────────────────────────────────────
# A fixed, always-past Tuesday so `plan_started` allows `begin` and the
# example inputs can name dates that are honestly in every factory plan.
PLAN_START = date(2026, 6, 30)
PLAN_SUNDAY = date(2026, 7, 5)


async def _mk(engine, kind: str, body: dict) -> str:
    created = await engine.invoker.create(kind, body,
                                          principal=FACTORY_PRINCIPAL,
                                          idempotency_key=uuid.uuid4().hex)
    return created.doc["self"].rsplit("/", 1)[-1]


async def _step(engine, kind: str, id: str, action: str, body=None):
    return await engine.invoker.invoke(kind, id, action, body,
                                       principal=FACTORY_PRINCIPAL,
                                       idempotency_key=uuid.uuid4().hex)


@state_factory(Meal)
async def make_meal(state: str, engine, services) -> Meal:
    mid = await _mk(engine, "meal", {
        "name": "Carnitas tacos", "theme": "mexican",
        "recipe": "# Carnitas tacos\n\nSlow-cook the pork…",
        "prep_minutes": 45, "thaw_hours": 12})
    if state == MealState.ON_LIST:
        await _step(engine, "meal", mid, "accept")
    elif state == MealState.RETIRED:
        await _step(engine, "meal", mid, "decline")
    return await _load(engine, "meal", mid)


@state_factory(SundayRotation)
async def make_rotation(state: str, engine, services) -> SundayRotation:
    rid = await _mk(engine, "rotation", {})
    if state == RotationState.ACTIVE:
        await _step(engine, "rotation", rid, "activate")
    return await _load(engine, "rotation", rid)


async def _listed_meal(engine, services) -> str:
    """An on-list Taco-Tuesday meal; its id is stashed for example inputs."""
    mid = await _mk(engine, "meal", {"name": "Tacos al pastor",
                                     "theme": "mexican"})
    await _step(engine, "meal", mid, "accept")
    services.seeded["meal_id"] = mid
    return mid


@state_factory(MealPlan)
async def make_plan(state: str, engine, services) -> MealPlan:
    rid = await _mk(engine, "rotation", {})
    await _listed_meal(engine, services)
    pid = await _mk(engine, "plan", {"start_date": PLAN_START.isoformat(),
                                     "weeks": 1, "rotation_id": rid})
    target = PlanState(state)
    if target == PlanState.ABANDONED:
        await _step(engine, "plan", pid, "abandon")
    elif target != PlanState.DRAFT:
        for i in range(7):  # a week of eating out is a covered week
            await _step(engine, "plan", pid, "mark_eating_out",
                        {"date": (PLAN_START + timedelta(days=i)).isoformat()})
        await _step(engine, "plan", pid, "finalize")
        if target in (PlanState.ACTIVE, PlanState.DONE):
            await _step(engine, "plan", pid, "begin")
        if target == PlanState.DONE:
            await _step(engine, "plan", pid, "complete")
    return await _load(engine, "plan", pid)


@example_input(MealPlan, "assign_meal")
def assign_meal_example(services) -> dict:
    return {"date": PLAN_START.isoformat(), "meal_id": services.seeded["meal_id"]}


@example_input(MealPlan, "assign_off_theme")
def assign_off_theme_example(services) -> dict:
    return {"date": PLAN_START.isoformat(), "meal_id": services.seeded["meal_id"]}


@example_input(MealPlan, "set_sunday_theme")
def set_sunday_theme_example(services) -> dict:
    return {"date": PLAN_SUNDAY.isoformat(), "theme": "indian"}


@example_input(MealPlan, "mark_eating_out")
def mark_eating_out_example(services) -> dict:
    return {"date": PLAN_START.isoformat()}


@example_input(MealPlan, "clear_day")
def clear_day_example(services) -> dict:
    return {"date": PLAN_START.isoformat()}


GROCERY_ITEMS = [
    {"name": "chicken thighs", "quantity": "2 lbs", "category": "meat"},
    {"name": "paper towels", "category": "household"},
]


@state_factory(GroceryList)
async def make_grocery_list(state: str, engine, services) -> GroceryList:
    plan = await make_plan(PlanState.PLANNED, engine, services)
    gid = await _mk(engine, "grocery_list",
                    {"plan_id": plan.id, "items": GROCERY_ITEMS})
    target = GroceryState(state)
    if target != GroceryState.DRAFT:
        await _step(engine, "grocery_list", gid, "finalize")
    if target == GroceryState.DONE:
        for item in GROCERY_ITEMS:
            await _step(engine, "grocery_list", gid, "check_item",
                        {"name": item["name"]})
        await _step(engine, "grocery_list", gid, "complete")
    return await _load(engine, "grocery_list", gid)


@example_input(GroceryList, "check_item")
def check_item_example(services) -> dict:
    return {"name": "chicken thighs"}


# remove_item's example must differ from check_item's: the walker may remove
# then check, and only "paper towels" is safe to lose.
@example_input(GroceryList, "remove_item")
def remove_item_example(services) -> dict:
    return {"name": "paper towels"}


@state_factory(PrepTask)
async def make_prep_task(state: str, engine, services) -> PrepTask:
    plan = await make_plan(PlanState.PLANNED, engine, services)
    tid = await _mk(engine, "prep_task", {
        "plan_id": plan.id, "date": PLAN_SUNDAY.isoformat(),
        "meal_name": "Pulled pork", "task_type": "thaw",
        "due_at": "2026-07-04T18:00:00+00:00", "duration_minutes": 720})
    target = PrepState(state)
    if target == PrepState.CANCELLED:
        await _step(engine, "prep_task", tid, "cancel")
    elif target in (PrepState.SCHEDULED, PrepState.DONE):
        await _step(engine, "prep_task", tid, "schedule",
                    {"calendar_event_id": "gcal-demo-1"})
        if target == PrepState.DONE:
            await _step(engine, "prep_task", tid, "complete")
    return await _load(engine, "prep_task", tid)
