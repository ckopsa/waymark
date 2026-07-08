"""Root conftest: what an application supplies to `pytest --waymark` (Part III)
— the engine fixture, one state factory per resource, and example inputs where
schema generation can't satisfy semantic guards.
"""
from __future__ import annotations

import asyncio
import os
import uuid
from dataclasses import dataclass, field
from datetime import date, timedelta
from typing import Any

import pytest

import waymark
import waymark2
import waymark3
from waymark import Principal
from waymark.testing import example_input, per_worker_dsn, state_factory
from waymark2.testing import (
    conformance_resource as w2_conformance_resource,
    example_input as w2_example_input,
    state_factory as w2_state_factory,
)
from waymark3.testing import (
    conformance_resource as w3_conformance_resource,
    example_input as w3_example_input,
    state_factory as w3_state_factory,
)

from app.resources.order import Order, OrderState
from app.resources.return_workflow import Return, ReturnState
from app.resources.shipment import Shipment, ShipmentState
from app.services import Services, mint_method
from mealplan.resources.grocery_list import GroceryList, GroceryState
from mealplan.resources.meal import Meal
from mealplan.resources.plan import MealPlan, PlanState
from mealplan.resources.prep_task import PrepTask
from mealplan.resources.rotation import SundayRotation
from mealplan3.resources.grocery_list import (
    GroceryList as GroceryList3, GroceryState as GroceryState3)
from mealplan3.resources.meal import Meal as Meal3
from mealplan3.resources.plan import MealPlan as MealPlan3, PlanState as PlanState3
from mealplan3.resources.prep_task import PrepTask as PrepTask3
from mealplan3.resources.rotation import SundayRotation as SundayRotation3

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
    """The v1 suite covers the v1 example shop; the meal-plan app moved to
    waymark2 (see waymark2_engine below)."""
    engine = waymark.Engine(resources=[Order, Shipment, Return],
                            storage=TEST_DSN, services=SuiteServices())
    await engine.storage.drop_all()
    await engine.startup()
    try:
        yield engine
    finally:
        await engine.shutdown()


@pytest.fixture
async def waymark2_engine():
    from waymark2.server.bus import InProcessBus

    engine = waymark2.Engine(
        resources=[Meal, SundayRotation, MealPlan, GroceryList, PrepTask],
        storage=TEST_DSN, services=SuiteServices(), bus=InProcessBus())
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


# ── Meal-plan app (waymark2, design §9: derive more, register less) ─────
# Meal, rotation, and prep task need no factories at all — the derived
# walker reaches every state from a create example. Plan and grocery list
# keep factories because their states need semantic setup (a linked
# rotation, a covered week, a fixed always-past Tuesday so `plan_started`
# allows `begin`). Example inputs remain only where a check-based guard
# wants a real id the schema cannot invent.
PLAN_START = date(2026, 6, 30)
PLAN_SUNDAY = date(2026, 7, 5)

w2_conformance_resource(Meal)
w2_conformance_resource(SundayRotation)
w2_conformance_resource(PrepTask)


@w2_example_input(Meal, "create")
def meal_create_example(services) -> dict:
    return {"name": "Carnitas tacos", "themes": ["mexican"],
            "recipe": "# Carnitas tacos\n\nSlow-cook the pork…",
            "prep_minutes": 45, "thaw_hours": 12}


@w2_example_input(PrepTask, "create")
def prep_task_create_example(services) -> dict:
    return {"plan_id": services.seeded.get("plan_id", "unlinked"),
            "date": PLAN_SUNDAY.isoformat(), "meal_name": "Pulled pork",
            "task_type": "thaw", "due_at": "2026-07-04T18:00:00+00:00",
            "duration_minutes": 720}


async def _mk(engine, kind: str, body: dict) -> str:
    created = await engine.invoker.create(kind, body,
                                          principal=FACTORY_PRINCIPAL,
                                          idempotency_key=uuid.uuid4().hex)
    return created.doc["self"].rsplit("/", 1)[-1]


async def _step(engine, kind: str, id: str, action: str, body=None):
    return await engine.invoker.invoke(kind, id, action, body,
                                       principal=FACTORY_PRINCIPAL,
                                       idempotency_key=uuid.uuid4().hex)


async def _listed_meal(engine, services) -> str:
    """An on-list Taco-Tuesday meal; its id is stashed for example inputs.

    ``themes`` (plural): the wire's additionalProperties check rejects the
    single-theme-era key before the model's fold can run — the factory must
    speak the current shape."""
    mid = await _mk(engine, "meal", {"name": "Tacos al pastor",
                                     "themes": ["mexican"]})
    await _step(engine, "meal", mid, "accept")
    services.seeded["meal_id"] = mid
    return mid


@w2_state_factory(MealPlan)
async def make_plan(state: str, engine, services) -> MealPlan:
    rid = await _mk(engine, "rotation", {})
    await _listed_meal(engine, services)
    pid = await _mk(engine, "plan", {"start_date": PLAN_START.isoformat(),
                                     "weeks": 1, "rotation_id": rid})
    services.seeded["plan_id"] = pid
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


# the one genuinely semantic input left: a real on-list meal id (the guard
# is check-based, reads another resource, and the schema cannot invent it)
@w2_example_input(MealPlan, "assign_meal")
def assign_meal_example(services) -> dict:
    return {"date": PLAN_START.isoformat(), "meal_id": services.seeded["meal_id"]}


@w2_example_input(MealPlan, "assign_off_theme")
def assign_off_theme_example(services) -> dict:
    return {"date": PLAN_START.isoformat(), "meal_id": services.seeded["meal_id"]}


GROCERY_ITEMS = [
    {"name": "chicken thighs", "quantity": "2 lbs", "category": "meat"},
    {"name": "paper towels", "category": "household"},
]


@w2_state_factory(GroceryList)
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


# remove_item's synthesized input comes from item_on_list's acceptance set,
# whose first entry is "chicken thighs" — but the walker may remove then
# check, and only "paper towels" is safe to lose.
@w2_example_input(GroceryList, "remove_item")
def remove_item_example(services) -> dict:
    return {"name": "paper towels"}


# ── Meal-plan app on waymark3 (the 3.0 dogfood: same app, v3 declarations) ──

@pytest.fixture
async def waymark3_engine():
    from waymark3.server.bus import InProcessBus

    engine = waymark3.Engine(
        resources=[Meal3, SundayRotation3, MealPlan3, GroceryList3, PrepTask3],
        storage=TEST_DSN, services=SuiteServices(), bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    try:
        yield engine
    finally:
        await engine.shutdown()


w3_conformance_resource(Meal3)
w3_conformance_resource(SundayRotation3)
w3_conformance_resource(PrepTask3)

# 3.0 engine kinds: ordinary resources, ordinary conformance
from waymark3.server.attachments import (  # noqa: E402
    Attachment as Attachment3W, BYTES_ACTOR as BYTES_ACTOR3W,
)
from waymark3.server.members import Member as Member3W  # noqa: E402
from waymark3.server.roles import Role as Role3W  # noqa: E402
from waymark3.server.subscriptions import (  # noqa: E402
    WebhookSubscription as Subscription3W,
)

w3_conformance_resource(Member3W)
w3_conformance_resource(Role3W)
w3_conformance_resource(Subscription3W)


# an attachment's create must name a live target, so its states need a
# factory rather than a schema-synthesized create (design E5)
@w3_state_factory(Attachment3W)
async def w3_make_attachment(state: str, engine, services) -> Attachment3W:
    mid = await _mk(engine, "meal", {"name": "Attachment target",
                                     "themes": ["mexican"]})
    services.seeded["attachment_target"] = mid
    aid = await _mk(engine, "attachment", {
        "resource_kind": "meal", "resource_id": mid,
        "name": "recipe.pdf", "mime": "application/pdf"})
    if state in ("uploaded", "removed"):
        await engine.invoker.invoke(
            "attachment", aid, "mark_uploaded",
            {"size": 3, "sha256": "a" * 64}, principal=BYTES_ACTOR3W)
    if state == "removed":
        await _step(engine, "attachment", aid, "remove")
    return await _load(engine, "attachment", aid)


@w3_example_input(Attachment3W, "duplicate")
def w3_attachment_duplicate_example(services) -> dict:
    # a synthesized target would dangle; duplicate onto the factory's meal
    return {"resource_kind": "meal",
            "resource_id": services.seeded["attachment_target"]}


@w3_example_input(Role3W, "create")
def w3_role_create_example(services) -> dict:
    # role names are declared unique (design E2); the walker may create
    # several per test, so the example must mint fresh spellings
    return {"name": f"reader-{uuid.uuid4().hex[:8]}",
            "description": "May read shared note titles"}


@w3_example_input(Member3W, "create")
def w3_member_create_example(services) -> dict:
    return {"email": "mom@example.com", "display_name": "Grandma",
            "roles": ["reader"]}


@w3_example_input(Subscription3W, "create")
def w3_subscription_create_example(services) -> dict:
    return {"url": "https://budget.example/hooks", "kinds": ["plan"]}


@w3_example_input(Meal3, "create")
def w3_meal_create_example(services) -> dict:
    # 3.0 speaks only the current shape on the wire; the single-theme era
    # is a declared upcast (Meal3.shape/upcasts), not a payload dialect
    return {"name": "Carnitas tacos", "themes": ["mexican"],
            "recipe": "# Carnitas tacos\n\nSlow-cook the pork…",
            "prep_minutes": 45, "thaw_hours": 12}


@w3_example_input(PrepTask3, "create")
def w3_prep_task_create_example(services) -> dict:
    return prep_task_create_example(services)


async def _listed_meal3(engine, services) -> str:
    mid = await _mk(engine, "meal", {"name": "Tacos al pastor",
                                     "themes": ["mexican"]})
    await _step(engine, "meal", mid, "accept")
    services.seeded["meal_id"] = mid
    return mid


@w3_state_factory(MealPlan3)
async def w3_make_plan(state: str, engine, services) -> MealPlan3:
    rid = await _mk(engine, "rotation", {})
    await _listed_meal3(engine, services)
    pid = await _mk(engine, "plan", {"start_date": PLAN_START.isoformat(),
                                     "weeks": 1, "rotation_id": rid})
    services.seeded["plan_id"] = pid
    target = PlanState3(state)
    if target == PlanState3.ABANDONED:
        await _step(engine, "plan", pid, "abandon")
    elif target != PlanState3.DRAFT:
        for i in range(7):
            await _step(engine, "plan", pid, "mark_eating_out",
                        {"date": (PLAN_START + timedelta(days=i)).isoformat()})
        await _step(engine, "plan", pid, "finalize")
        if target in (PlanState3.ACTIVE, PlanState3.DONE):
            await _step(engine, "plan", pid, "begin")
        if target == PlanState3.DONE:
            await _step(engine, "plan", pid, "complete")
    return await _load(engine, "plan", pid)


# assign_meal needs no example: its Relation's tuple set feeds the
# synthesizer (waymark3.testing.factories.synthesize_input)
@w3_example_input(MealPlan3, "assign_off_theme")
def w3_assign_off_theme_example(services) -> dict:
    return {"date": PLAN_START.isoformat(), "meal_id": services.seeded["meal_id"]}


@w3_state_factory(GroceryList3)
async def w3_make_grocery_list(state: str, engine, services) -> GroceryList3:
    plan = await w3_make_plan(PlanState3.PLANNED, engine, services)
    gid = await _mk(engine, "grocery_list",
                    {"plan_id": plan.id, "items": GROCERY_ITEMS})
    target = GroceryState3(state)
    if target != GroceryState3.DRAFT:
        await _step(engine, "grocery_list", gid, "finalize")
    if target == GroceryState3.DONE:
        for item in GROCERY_ITEMS:
            await _step(engine, "grocery_list", gid, "check_item",
                        {"name": item["name"]})
        await _step(engine, "grocery_list", gid, "complete")
    return await _load(engine, "grocery_list", gid)


@w3_example_input(GroceryList3, "remove_item")
def w3_remove_item_example(services) -> dict:
    return {"name": "paper towels"}


# ── Meal-plan app on waymark4 (the 4.0 dogfood: same app, v4 declarations) ──

import waymark4  # noqa: E402
from waymark4.testing import (  # noqa: E402
    conformance_resource as w4_conformance_resource,
    example_input as w4_example_input,
    state_factory as w4_state_factory,
)
from mealplan4.resources.grocery_list import (  # noqa: E402
    GroceryList as GroceryList4, GroceryState as GroceryState4)
from mealplan4.resources.meal import Meal as Meal4  # noqa: E402
from mealplan4.resources.plan import (  # noqa: E402
    MealPlan as MealPlan4, PlanState as PlanState4)
from mealplan4.resources.prep_task import PrepTask as PrepTask4  # noqa: E402
from mealplan4.resources.rotation import (  # noqa: E402
    SundayRotation as SundayRotation4)


@pytest.fixture
async def waymark4_engine():
    from waymark4.server.bus import InProcessBus

    engine = waymark4.Engine(
        resources=[Meal4, SundayRotation4, MealPlan4, GroceryList4, PrepTask4],
        storage=TEST_DSN, services=SuiteServices(), bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    try:
        yield engine
    finally:
        await engine.shutdown()


w4_conformance_resource(Meal4)
w4_conformance_resource(SundayRotation4)
w4_conformance_resource(PrepTask4)

# 4.0 engine kinds: ordinary resources, ordinary conformance
from waymark4.server.attachments import (  # noqa: E402
    Attachment as Attachment4W, BYTES_ACTOR as BYTES_ACTOR4W,
)
from waymark4.server.members import Member as Member4W  # noqa: E402
from waymark4.server.roles import Role as Role4W  # noqa: E402
from waymark4.server.subscriptions import (  # noqa: E402
    WebhookSubscription as Subscription4W,
)

w4_conformance_resource(Member4W)
w4_conformance_resource(Role4W)
w4_conformance_resource(Subscription4W)


# an attachment's create must name a live target, so its states need a
# factory rather than a schema-synthesized create (design E5)
@w4_state_factory(Attachment4W)
async def w4_make_attachment(state: str, engine, services) -> Attachment4W:
    mid = await _mk(engine, "meal", {"name": "Attachment target",
                                     "themes": ["mexican"]})
    services.seeded["attachment_target"] = mid
    aid = await _mk(engine, "attachment", {
        "resource_kind": "meal", "resource_id": mid,
        "name": "recipe.pdf", "mime": "application/pdf"})
    if state in ("uploaded", "removed"):
        await engine.invoker.invoke(
            "attachment", aid, "mark_uploaded",
            {"size": 3, "sha256": "a" * 64}, principal=BYTES_ACTOR4W)
    if state == "removed":
        await _step(engine, "attachment", aid, "remove")
    return await _load(engine, "attachment", aid)


@w4_example_input(Attachment4W, "duplicate")
def w4_attachment_duplicate_example(services) -> dict:
    # a synthesized target would dangle; duplicate onto the factory's meal
    return {"resource_kind": "meal",
            "resource_id": services.seeded["attachment_target"]}


@w4_example_input(Role4W, "create")
def w4_role_create_example(services) -> dict:
    # role names are declared unique (design E2); the walker may create
    # several per test, so the example must mint fresh spellings
    return {"name": f"reader-{uuid.uuid4().hex[:8]}",
            "description": "May read shared note titles"}


@w4_example_input(Member4W, "create")
def w4_member_create_example(services) -> dict:
    return {"email": "mom@example.com", "display_name": "Grandma",
            "roles": ["reader"]}


@w4_example_input(Subscription4W, "create")
def w4_subscription_create_example(services) -> dict:
    return {"url": "https://budget.example/hooks", "kinds": ["plan"]}


@w4_example_input(Meal4, "create")
def w4_meal_create_example(services) -> dict:
    # 4.0 speaks only the current shape on the wire; the single-theme era
    # is a declared upcast (Meal4.shape/upcasts), not a payload dialect
    return {"name": "Carnitas tacos", "themes": ["mexican"],
            "recipe": "# Carnitas tacos\n\nSlow-cook the pork…",
            "prep_minutes": 45, "thaw_hours": 12}


@w4_example_input(PrepTask4, "create")
def w4_prep_task_create_example(services) -> dict:
    return prep_task_create_example(services)


async def _listed_meal3(engine, services) -> str:
    mid = await _mk(engine, "meal", {"name": "Tacos al pastor",
                                     "themes": ["mexican"]})
    await _step(engine, "meal", mid, "accept")
    services.seeded["meal_id"] = mid
    return mid


@w4_state_factory(MealPlan4)
async def w4_make_plan(state: str, engine, services) -> MealPlan4:
    rid = await _mk(engine, "rotation", {})
    await _listed_meal3(engine, services)
    pid = await _mk(engine, "plan", {"start_date": PLAN_START.isoformat(),
                                     "weeks": 1, "rotation_id": rid})
    services.seeded["plan_id"] = pid
    target = PlanState4(state)
    if target == PlanState4.ABANDONED:
        await _step(engine, "plan", pid, "abandon")
    elif target != PlanState4.DRAFT:
        for i in range(7):
            await _step(engine, "plan", pid, "mark_eating_out",
                        {"date": (PLAN_START + timedelta(days=i)).isoformat()})
        await _step(engine, "plan", pid, "finalize")
        if target in (PlanState4.ACTIVE, PlanState4.DONE):
            await _step(engine, "plan", pid, "begin")
        if target == PlanState4.DONE:
            await _step(engine, "plan", pid, "complete")
    return await _load(engine, "plan", pid)


# assign_meal needs no example: its Relation's tuple set feeds the
# synthesizer (waymark4.testing.factories.synthesize_input)
@w4_example_input(MealPlan4, "assign_off_theme")
def w4_assign_off_theme_example(services) -> dict:
    return {"date": PLAN_START.isoformat(), "meal_id": services.seeded["meal_id"]}


@w4_state_factory(GroceryList4)
async def w4_make_grocery_list(state: str, engine, services) -> GroceryList4:
    plan = await w4_make_plan(PlanState4.PLANNED, engine, services)
    gid = await _mk(engine, "grocery_list",
                    {"plan_id": plan.id, "items": GROCERY_ITEMS})
    target = GroceryState4(state)
    if target != GroceryState4.DRAFT:
        await _step(engine, "grocery_list", gid, "finalize")
    if target == GroceryState4.DONE:
        for item in GROCERY_ITEMS:
            await _step(engine, "grocery_list", gid, "check_item",
                        {"name": item["name"]})
        await _step(engine, "grocery_list", gid, "complete")
    return await _load(engine, "grocery_list", gid)


@w4_example_input(GroceryList4, "remove_item")
def w4_remove_item_example(services) -> dict:
    return {"name": "paper towels"}


# ── Meal-plan app on waymark5 (the 5.0 dogfood: same app, v5 declarations) ──

import waymark5  # noqa: E402
from waymark5.testing import (  # noqa: E402
    conformance_resource as w5_conformance_resource,
    example_input as w5_example_input,
    state_factory as w5_state_factory,
)
from mealplan5.resources.grocery_list import (  # noqa: E402
    GroceryList as GroceryList5, GroceryState as GroceryState5)
from mealplan5.resources.meal import Meal as Meal5  # noqa: E402
from mealplan5.resources.plan import (  # noqa: E402
    MealPlan as MealPlan5, PlanState as PlanState5)
from mealplan5.resources.prep_task import PrepTask as PrepTask5  # noqa: E402
from mealplan5.resources.rotation import (  # noqa: E402
    SundayRotation as SundayRotation5)

# ── Cash reconciliation on waymark5 (the 5.0 dogfood: ledger5) ──────────
from ledger5.resources.account import Account as CRAccount  # noqa: E402
from ledger5.resources.account import AccountState as CRAccountState  # noqa: E402
from ledger5.resources.account_template import (  # noqa: E402
    AccountTemplate as CRAccountTemplate)
from ledger5.resources.break_ import BreakState as CRBreakState  # noqa: E402
from ledger5.resources.break_ import ReconBreak as CRReconBreak  # noqa: E402
from ledger5.resources.transaction import Transaction as CRTransaction  # noqa: E402
from ledger5.resources.transaction import (  # noqa: E402
    TransactionState as CRTransactionState)
from ledger5.resources.workbook import Workbook as CRWorkbook  # noqa: E402
from ledger5.resources.workbook import WorkbookState as CRWorkbookState  # noqa: E402
from ledger5.services import FakeBeacon  # noqa: E402

CR_PREPARER = Principal(id="marcus", type="human", display="Marcus")
CR_REVIEWER = Principal(id="elena", type="human", display="Elena")


@dataclass
class SuiteServices5(SuiteServices):
    """mealplan5's services plus the Beacon boundary ledger5 declares —
    one shared engine/services pair covers both v5 dogfood apps, since
    `pytest --waymark5` walks a single ``waymark5_engine`` fixture."""

    beacon_backend: FakeBeacon = field(default_factory=FakeBeacon)
    beacon: Any = None

    def __post_init__(self) -> None:
        if self.beacon is None:
            from waymark5.server.external import Service

            self.beacon = Service("beacon", handler=self.beacon_backend.pull,
                                 timeout=30.0, backoff_seconds=60.0,
                                 down_on_error=True)


@pytest.fixture
async def waymark5_engine():
    from waymark5.server.bus import InProcessBus

    services = SuiteServices5()
    engine = waymark5.Engine(
        resources=[Meal5, SundayRotation5, MealPlan5, GroceryList5, PrepTask5,
                   CRAccountTemplate, CRWorkbook, CRAccount, CRReconBreak,
                   CRTransaction],
        storage=TEST_DSN, services=services, bus=InProcessBus())
    services.beacon_backend.engine = engine
    await engine.storage.drop_all()
    await engine.startup()
    try:
        yield engine
    finally:
        await engine.shutdown()


w5_conformance_resource(Meal5)
w5_conformance_resource(SundayRotation5)
w5_conformance_resource(PrepTask5)

# 5.0 engine kinds: ordinary resources, ordinary conformance
from waymark5.server.attachments import (  # noqa: E402
    Attachment as Attachment5W, BYTES_ACTOR as BYTES_ACTOR5W,
)
from waymark5.server.members import Member as Member5W  # noqa: E402
from waymark5.server.roles import Role as Role5W  # noqa: E402
from waymark5.server.subscriptions import (  # noqa: E402
    WebhookSubscription as Subscription5W,
)

w5_conformance_resource(Member5W)
w5_conformance_resource(Role5W)
w5_conformance_resource(Subscription5W)


# an attachment's create must name a live target, so its states need a
# factory rather than a schema-synthesized create (design E5)
@w5_state_factory(Attachment5W)
async def w5_make_attachment(state: str, engine, services) -> Attachment5W:
    mid = await _mk(engine, "meal", {"name": "Attachment target",
                                     "themes": ["mexican"]})
    services.seeded["attachment_target"] = mid
    aid = await _mk(engine, "attachment", {
        "resource_kind": "meal", "resource_id": mid,
        "name": "recipe.pdf", "mime": "application/pdf"})
    if state in ("uploaded", "removed"):
        await engine.invoker.invoke(
            "attachment", aid, "mark_uploaded",
            {"size": 3, "sha256": "a" * 64}, principal=BYTES_ACTOR5W)
    if state == "removed":
        await _step(engine, "attachment", aid, "remove")
    return await _load(engine, "attachment", aid)


@w5_example_input(Attachment5W, "duplicate")
def w5_attachment_duplicate_example(services) -> dict:
    # a synthesized target would dangle; duplicate onto the factory's meal
    return {"resource_kind": "meal",
            "resource_id": services.seeded["attachment_target"]}


@w5_example_input(Role5W, "create")
def w5_role_create_example(services) -> dict:
    # role names are declared unique (design E2); the walker may create
    # several per test, so the example must mint fresh spellings
    return {"name": f"reader-{uuid.uuid4().hex[:8]}",
            "description": "May read shared note titles"}


@w5_example_input(Member5W, "create")
def w5_member_create_example(services) -> dict:
    return {"email": "mom@example.com", "display_name": "Grandma",
            "roles": ["reader"]}


@w5_example_input(Subscription5W, "create")
def w5_subscription_create_example(services) -> dict:
    return {"url": "https://budget.example/hooks", "kinds": ["plan"]}


@w5_example_input(Meal5, "create")
def w5_meal_create_example(services) -> dict:
    # 5.0 speaks only the current shape on the wire; the single-theme era
    # is a declared upcast (Meal5.shape/upcasts), not a payload dialect
    return {"name": "Carnitas tacos", "themes": ["mexican"],
            "recipe": "# Carnitas tacos\n\nSlow-cook the pork…",
            "prep_minutes": 45, "thaw_hours": 12}


@w5_example_input(PrepTask5, "create")
def w5_prep_task_create_example(services) -> dict:
    return prep_task_create_example(services)


async def _listed_meal3(engine, services) -> str:
    mid = await _mk(engine, "meal", {"name": "Tacos al pastor",
                                     "themes": ["mexican"]})
    await _step(engine, "meal", mid, "accept")
    services.seeded["meal_id"] = mid
    return mid


@w5_state_factory(MealPlan5)
async def w5_make_plan(state: str, engine, services) -> MealPlan5:
    rid = await _mk(engine, "rotation", {})
    await _listed_meal3(engine, services)
    pid = await _mk(engine, "plan", {"start_date": PLAN_START.isoformat(),
                                     "weeks": 1, "rotation_id": rid})
    services.seeded["plan_id"] = pid
    target = PlanState5(state)
    if target == PlanState5.ABANDONED:
        await _step(engine, "plan", pid, "abandon")
    elif target != PlanState5.DRAFT:
        for i in range(7):
            await _step(engine, "plan", pid, "mark_eating_out",
                        {"date": (PLAN_START + timedelta(days=i)).isoformat()})
        await _step(engine, "plan", pid, "finalize")
        if target in (PlanState5.ACTIVE, PlanState5.DONE):
            await _step(engine, "plan", pid, "begin")
        if target == PlanState5.DONE:
            await _step(engine, "plan", pid, "complete")
    return await _load(engine, "plan", pid)


# assign_meal needs no example: its Relation's tuple set feeds the
# synthesizer (waymark5.testing.factories.synthesize_input)
@w5_example_input(MealPlan5, "assign_off_theme")
def w5_assign_off_theme_example(services) -> dict:
    return {"date": PLAN_START.isoformat(), "meal_id": services.seeded["meal_id"]}


@w5_state_factory(GroceryList5)
async def w5_make_grocery_list(state: str, engine, services) -> GroceryList5:
    plan = await w5_make_plan(PlanState5.PLANNED, engine, services)
    gid = await _mk(engine, "grocery_list",
                    {"plan_id": plan.id, "items": GROCERY_ITEMS})
    target = GroceryState5(state)
    if target != GroceryState5.DRAFT:
        await _step(engine, "grocery_list", gid, "finalize")
    if target == GroceryState5.DONE:
        for item in GROCERY_ITEMS:
            await _step(engine, "grocery_list", gid, "check_item",
                        {"name": item["name"]})
        await _step(engine, "grocery_list", gid, "complete")
    return await _load(engine, "grocery_list", gid)


@w5_example_input(GroceryList5, "remove_item")
def w5_remove_item_example(services) -> dict:
    return {"name": "paper towels"}


# no cross-resource refs and every field is schema-synthesizable — the
# derived walker needs no factory (mirrors Meal5/PrepTask5)
w5_conformance_resource(CRAccountTemplate)


async def _step_as(engine, kind: str, id: str, action: str, principal,
                   body=None):
    return await engine.invoker.invoke(kind, id, action, body,
                                       principal=principal,
                                       idempotency_key=uuid.uuid4().hex)


async def _cr_accounts_of(engine, workbook_id: str) -> list[Any]:
    async with engine.storage.session() as s:
        rows, _ = await engine.storage.query(
            s, "account", filters={"workbook_id": workbook_id},
            sort=None, page_size=50, page_number=1)
    return rows


async def _cr_job_done(engine, job_id: str, *, timeout: float = 5.0) -> None:
    """Wait for a deferred refresh job to finish before the factory
    returns. Without this, ``principals_with`` (which calls a state
    factory once per candidate principal) leaves several jobs' async
    start/finish transitions racing in the background — one can land
    inside a *later*, unrelated conformance case's before/after window
    and look like that case's action wrote an extra transition."""
    deadline = asyncio.get_event_loop().time() + timeout
    while True:
        async with engine.storage.session() as s:
            job = await engine.storage.load(s, "job", job_id)
        if job is not None and job.state in ("done", "cancelled"):
            return
        if asyncio.get_event_loop().time() > deadline:
            raise AssertionError(f"job {job_id} never finished")
        await asyncio.sleep(0.02)


async def _make_cr_workbook_id(engine) -> str:
    """A fresh fund + workbook every call — never cached in
    ``services.seeded``, since a state factory may run several times in
    one test (once per principal, per conformance case) and a cached id
    already at the target state can't be re-driven through the same
    from-open recipe (design E2's unique=(("fund","period")) would also
    409 on a repeat fund otherwise)."""
    fund = f"fund-cr5-{uuid.uuid4().hex[:8]}"
    await _mk(engine, "account_template",
              {"fund": fund, "name": "Ops checking",
               "bank_name": "First Bank", "last4": "4321",
               "beacon_coa_id": "COA-1"})
    return await _mk(engine, "workbook", {"fund": fund, "period": "2026-06"})


@w5_state_factory(CRWorkbook)
async def make_cr_workbook(state: str, engine, services) -> CRWorkbook:
    wid = await _make_cr_workbook_id(engine)
    target = CRWorkbookState(state)
    if target == CRWorkbookState.ABANDONED:
        await _step(engine, "workbook", wid, "abandon")
        return await _load(engine, "workbook", wid)
    if target in (CRWorkbookState.PREPARED, CRWorkbookState.REVIEWED):
        # freshen the sync first: beacon_fresh is a warning guard on
        # prepare, and refresh stamps last_synced_at synchronously.
        # Wait for the deferred job to finish before moving on — a
        # conformance case calls this factory once per candidate
        # principal, and a job still running in the background would
        # otherwise race a *later* case's before/after transition count.
        refreshed = await _step(engine, "workbook", wid, "refresh")
        await _cr_job_done(engine, refreshed.doc["data"]["sync_job_id"])
        for acc in await _cr_accounts_of(engine, wid):
            if acc.state == "open":
                await _step(engine, "account", acc.id, "reconcile")
        await _step_as(engine, "workbook", wid, "prepare", CR_PREPARER)
        if target == CRWorkbookState.REVIEWED:
            await _step_as(engine, "workbook", wid, "review", CR_REVIEWER)
    return await _load(engine, "workbook", wid)


async def _make_cr_account_id(engine) -> str:
    wid = await _make_cr_workbook_id(engine)
    accounts = await _cr_accounts_of(engine, wid)
    return accounts[0].id


@w5_state_factory(CRAccount)
async def make_cr_account(state: str, engine, services) -> CRAccount:
    aid = await _make_cr_account_id(engine)
    target = CRAccountState(state)
    if target == CRAccountState.REMOVED:
        await _step(engine, "account", aid, "remove")
    elif target == CRAccountState.BALANCED:
        # a freshly seeded account is already reconciled (0 - 0 + 0 == 0)
        await _step(engine, "account", aid, "reconcile")
    return await _load(engine, "account", aid)


@w5_state_factory(CRReconBreak)
async def make_cr_break(state: str, engine, services) -> CRReconBreak:
    aid = await _make_cr_account_id(engine)
    bid = await _mk(engine, "break",
                    {"account_id": aid, "amount": 12.34,
                     "note": "conformance"})
    if CRBreakState(state) == CRBreakState.REMOVED:
        await _step(engine, "break", bid, "remove")
    return await _load(engine, "break", bid)


@w5_state_factory(CRTransaction)
async def make_cr_transaction(state: str, engine, services) -> CRTransaction:
    aid = await _make_cr_account_id(engine)
    tid = await _mk(engine, "transaction",
                    {"account_id": aid, "transaction_date": "2026-06-15",
                     "amount": -42.0, "memo": "conformance"})
    if CRTransactionState(state) == CRTransactionState.REMOVED:
        await _step(engine, "transaction", tid, "remove")
    return await _load(engine, "transaction", tid)


# ── Meal-plan app on waymark6 (the 6.0 dogfood: same app + the calendar) ──

import waymark6  # noqa: E402
from waymark6.testing import (  # noqa: E402
    conformance_resource as w6_conformance_resource,
    example_input as w6_example_input,
    state_factory as w6_state_factory,
)
from mealplan6.resources.event import Event as Event6  # noqa: E402
from mealplan6.resources.grocery_list import (  # noqa: E402
    GroceryList as GroceryList6, GroceryState as GroceryState6)
from mealplan6.resources.meal import Meal as Meal6  # noqa: E402
from mealplan6.resources.plan import (  # noqa: E402
    MealPlan as MealPlan6, PlanState as PlanState6)
from mealplan6.resources.prep_task import PrepTask as PrepTask6  # noqa: E402
from mealplan6.resources.rotation import (  # noqa: E402
    SundayRotation as SundayRotation6)

# ── Cash reconciliation on waymark6 (the 6.0 dogfood: ledger6) ──────────
from ledger6.resources.account import Account as CR6Account  # noqa: E402
from ledger6.resources.account import (  # noqa: E402
    AccountState as CR6AccountState)
from ledger6.resources.account_template import (  # noqa: E402
    AccountTemplate as CR6AccountTemplate)
from ledger6.resources.break_ import BreakState as CR6BreakState  # noqa: E402
from ledger6.resources.break_ import ReconBreak as CR6ReconBreak  # noqa: E402
from ledger6.resources.transaction import (  # noqa: E402
    Transaction as CR6Transaction)
from ledger6.resources.transaction import (  # noqa: E402
    TransactionState as CR6TransactionState)
from ledger6.resources.workbook import Workbook as CR6Workbook  # noqa: E402
from ledger6.resources.workbook import (  # noqa: E402
    WorkbookState as CR6WorkbookState)
from ledger6.services import FakeBeacon as FakeBeacon6  # noqa: E402
from ledger6.surfaces import (  # noqa: E402
    CloseReview as CloseReview6, ReconcileAccount as ReconcileAccount6)


@dataclass
class SuiteServices6(SuiteServices):
    """mealplan6's services plus the Beacon boundary ledger6 declares —
    one shared engine/services pair covers both v6 dogfood apps, since
    `pytest --waymark6` walks a single ``waymark6_engine`` fixture."""

    beacon_backend: FakeBeacon6 = field(default_factory=FakeBeacon6)
    beacon: Any = None

    def __post_init__(self) -> None:
        if self.beacon is None:
            from waymark6.server.external import Service

            self.beacon = Service("beacon", handler=self.beacon_backend.pull,
                                 timeout=30.0, backoff_seconds=60.0,
                                 down_on_error=True)


@pytest.fixture
async def waymark6_engine():
    from waymark6.server.bus import InProcessBus

    services = SuiteServices6()
    engine = waymark6.Engine(
        resources=[Meal6, SundayRotation6, MealPlan6, GroceryList6,
                   PrepTask6, Event6,
                   CR6AccountTemplate, CR6Workbook, CR6Account,
                   CR6ReconBreak, CR6Transaction],
        surfaces=[CloseReview6, ReconcileAccount6],
        storage=TEST_DSN, services=services, bus=InProcessBus())
    services.beacon_backend.engine = engine
    await engine.storage.drop_all()
    await engine.startup()
    try:
        yield engine
    finally:
        await engine.shutdown()


w6_conformance_resource(Meal6)
w6_conformance_resource(SundayRotation6)
w6_conformance_resource(PrepTask6)
# the 6.0 calendar kind: every field is schema-synthesizable (the closed
# ``kind`` vocab is a Literal with a default) — the derived walker suffices
w6_conformance_resource(Event6)

# 6.0 engine kinds: ordinary resources, ordinary conformance
from waymark6.server.attachments import (  # noqa: E402
    Attachment as Attachment6W, BYTES_ACTOR as BYTES_ACTOR6W,
)
from waymark6.server.members import Member as Member6W  # noqa: E402
from waymark6.server.roles import Role as Role6W  # noqa: E402
from waymark6.server.subscriptions import (  # noqa: E402
    WebhookSubscription as Subscription6W,
)

w6_conformance_resource(Member6W)
w6_conformance_resource(Role6W)
w6_conformance_resource(Subscription6W)


# an attachment's create must name a live target, so its states need a
# factory rather than a schema-synthesized create (design E5)
@w6_state_factory(Attachment6W)
async def w6_make_attachment(state: str, engine, services) -> Attachment6W:
    mid = await _mk(engine, "meal", {"name": "Attachment target",
                                     "themes": ["mexican"]})
    services.seeded["attachment_target"] = mid
    aid = await _mk(engine, "attachment", {
        "resource_kind": "meal", "resource_id": mid,
        "name": "recipe.pdf", "mime": "application/pdf"})
    if state in ("uploaded", "removed"):
        await engine.invoker.invoke(
            "attachment", aid, "mark_uploaded",
            {"size": 3, "sha256": "a" * 64}, principal=BYTES_ACTOR6W)
    if state == "removed":
        await _step(engine, "attachment", aid, "remove")
    return await _load(engine, "attachment", aid)


@w6_example_input(Attachment6W, "duplicate")
def w6_attachment_duplicate_example(services) -> dict:
    # a synthesized target would dangle; duplicate onto the factory's meal
    return {"resource_kind": "meal",
            "resource_id": services.seeded["attachment_target"]}


@w6_example_input(Role6W, "create")
def w6_role_create_example(services) -> dict:
    # role names are declared unique (design E2); the walker may create
    # several per test, so the example must mint fresh spellings
    return {"name": f"reader-{uuid.uuid4().hex[:8]}",
            "description": "May read shared note titles"}


@w6_example_input(Member6W, "create")
def w6_member_create_example(services) -> dict:
    return {"email": "mom@example.com", "display_name": "Grandma",
            "roles": ["reader"]}


@w6_example_input(Subscription6W, "create")
def w6_subscription_create_example(services) -> dict:
    return {"url": "https://budget.example/hooks", "kinds": ["plan"]}


@w6_example_input(Meal6, "create")
def w6_meal_create_example(services) -> dict:
    # 6.0 speaks only the current shape on the wire; the single-theme era
    # is a declared upcast (Meal6.shape/upcasts), not a payload dialect
    return {"name": "Carnitas tacos", "themes": ["mexican"],
            "recipe": "# Carnitas tacos\n\nSlow-cook the pork…",
            "prep_minutes": 45, "thaw_hours": 12}


@w6_example_input(PrepTask6, "create")
def w6_prep_task_create_example(services) -> dict:
    return prep_task_create_example(services)


async def _listed_meal6(engine, services) -> str:
    mid = await _mk(engine, "meal", {"name": "Tacos al pastor",
                                     "themes": ["mexican"]})
    await _step(engine, "meal", mid, "accept")
    services.seeded["meal_id"] = mid
    return mid


@w6_state_factory(MealPlan6)
async def w6_make_plan(state: str, engine, services) -> MealPlan6:
    rid = await _mk(engine, "rotation", {})
    await _listed_meal6(engine, services)
    pid = await _mk(engine, "plan", {"start_date": PLAN_START.isoformat(),
                                     "weeks": 1, "rotation_id": rid})
    services.seeded["plan_id"] = pid
    target = PlanState6(state)
    if target == PlanState6.ABANDONED:
        await _step(engine, "plan", pid, "abandon")
    elif target != PlanState6.DRAFT:
        for i in range(7):
            await _step(engine, "plan", pid, "mark_eating_out",
                        {"date": (PLAN_START + timedelta(days=i)).isoformat()})
        await _step(engine, "plan", pid, "finalize")
        if target in (PlanState6.ACTIVE, PlanState6.DONE):
            await _step(engine, "plan", pid, "begin")
        if target == PlanState6.DONE:
            await _step(engine, "plan", pid, "complete")
    return await _load(engine, "plan", pid)


# assign_meal needs no example: its Relation's tuple set feeds the
# synthesizer (waymark6.testing.factories.synthesize_input)
@w6_example_input(MealPlan6, "assign_off_theme")
def w6_assign_off_theme_example(services) -> dict:
    return {"date": PLAN_START.isoformat(), "meal_id": services.seeded["meal_id"]}


@w6_state_factory(GroceryList6)
async def w6_make_grocery_list(state: str, engine, services) -> GroceryList6:
    plan = await w6_make_plan(PlanState6.PLANNED, engine, services)
    gid = await _mk(engine, "grocery_list",
                    {"plan_id": plan.id, "items": GROCERY_ITEMS})
    target = GroceryState6(state)
    if target != GroceryState6.DRAFT:
        await _step(engine, "grocery_list", gid, "finalize")
    if target == GroceryState6.DONE:
        for item in GROCERY_ITEMS:
            await _step(engine, "grocery_list", gid, "check_item",
                        {"name": item["name"]})
        await _step(engine, "grocery_list", gid, "complete")
    return await _load(engine, "grocery_list", gid)


@w6_example_input(GroceryList6, "remove_item")
def w6_remove_item_example(services) -> dict:
    return {"name": "paper towels"}


# no cross-resource refs and every field is schema-synthesizable — the
# derived walker needs no factory (mirrors Meal6/PrepTask6)
w6_conformance_resource(CR6AccountTemplate)

# The ledger helpers below (_step_as, _cr_accounts_of, _cr_job_done,
# _make_cr_workbook_id, _make_cr_account_id) and the CR_PREPARER /
# CR_REVIEWER principals are version-agnostic — they only touch the
# engine passed in — so the w6 factories reuse them directly.


@w6_state_factory(CR6Workbook)
async def w6_make_cr_workbook(state: str, engine, services) -> CR6Workbook:
    # fresh fund + fresh workbook every call, never cached: a factory runs
    # once per candidate principal per case, and a cached id already at
    # the target state can't be re-driven (unique=(fund, period) would
    # also 409) — see _make_cr_workbook_id's docstring
    wid = await _make_cr_workbook_id(engine)
    target = CR6WorkbookState(state)
    if target == CR6WorkbookState.ABANDONED:
        await _step(engine, "workbook", wid, "abandon")
        return await _load(engine, "workbook", wid)
    if target in (CR6WorkbookState.PREPARED, CR6WorkbookState.REVIEWED):
        # freshen the sync first: beacon_fresh is a warning guard on
        # prepare, and refresh stamps last_synced_at synchronously.
        # Wait for the deferred job to finish before moving on — a
        # conformance case calls this factory once per candidate
        # principal, and a job still running in the background would
        # otherwise race a *later* case's before/after transition count.
        refreshed = await _step(engine, "workbook", wid, "refresh")
        await _cr_job_done(engine, refreshed.doc["data"]["sync_job_id"])
        for acc in await _cr_accounts_of(engine, wid):
            if acc.state == "open":
                await _step(engine, "account", acc.id, "reconcile")
        await _step_as(engine, "workbook", wid, "prepare", CR_PREPARER)
        if target == CR6WorkbookState.REVIEWED:
            await _step_as(engine, "workbook", wid, "review", CR_REVIEWER)
    return await _load(engine, "workbook", wid)


@w6_state_factory(CR6Account)
async def w6_make_cr_account(state: str, engine, services) -> CR6Account:
    aid = await _make_cr_account_id(engine)
    target = CR6AccountState(state)
    if target == CR6AccountState.REMOVED:
        await _step(engine, "account", aid, "remove")
    elif target == CR6AccountState.BALANCED:
        # a freshly seeded account is already reconciled (0 - 0 + 0 == 0)
        await _step(engine, "account", aid, "reconcile")
    return await _load(engine, "account", aid)


@w6_state_factory(CR6ReconBreak)
async def w6_make_cr_break(state: str, engine, services) -> CR6ReconBreak:
    aid = await _make_cr_account_id(engine)
    bid = await _mk(engine, "break",
                    {"account_id": aid, "amount": 12.34,
                     "note": "conformance"})
    if CR6BreakState(state) == CR6BreakState.REMOVED:
        await _step(engine, "break", bid, "remove")
    return await _load(engine, "break", bid)


@w6_state_factory(CR6Transaction)
async def w6_make_cr_transaction(state: str, engine, services) -> CR6Transaction:
    aid = await _make_cr_account_id(engine)
    tid = await _mk(engine, "transaction",
                    {"account_id": aid, "transaction_date": "2026-06-15",
                     "amount": -42.0, "memo": "conformance"})
    if CR6TransactionState(state) == CR6TransactionState.REMOVED:
        await _step(engine, "transaction", tid, "remove")
    return await _load(engine, "transaction", tid)


# ── Meal-plan app on waymark7 (the 7.0 dogfood: same app + the calendar) ──

import waymark7  # noqa: E402
from waymark7.testing import (  # noqa: E402
    conformance_resource as w7_conformance_resource,
    example_input as w7_example_input,
    state_factory as w7_state_factory,
)
from waymark7.testing.factories import make_state as w7_make_state  # noqa: E402
from mealplan7.resources.event import Event as Event7  # noqa: E402
from mealplan7.resources.grocery_list import (  # noqa: E402
    GroceryList as GroceryList7, GroceryState as GroceryState7)
from mealplan7.resources.meal import Meal as Meal7  # noqa: E402
from mealplan7.resources.plan import (  # noqa: E402
    MealPlan as MealPlan7, PlanState as PlanState7)
from mealplan7.resources.prep_task import PrepTask as PrepTask7  # noqa: E402
from mealplan7.resources.rotation import (  # noqa: E402
    SundayRotation as SundayRotation7)

# ── Cash reconciliation on waymark7 (the 7.0 dogfood: ledger7) ──────────
# Account and Workbook are factory-built (`build_account()` /
# `build_workbook()` at module level) so the deploy stories can revise
# their laws; the module-level names ARE the shipped defaults.
from ledger7.resources.account import Account as CR7Account  # noqa: E402
from ledger7.resources.account import (  # noqa: E402
    AccountState as CR7AccountState)
from ledger7.resources.account_template import (  # noqa: E402
    AccountTemplate as CR7AccountTemplate)
from ledger7.resources.break_ import BreakState as CR7BreakState  # noqa: E402
from ledger7.resources.break_ import ReconBreak as CR7ReconBreak  # noqa: E402
from ledger7.resources.transaction import (  # noqa: E402
    Transaction as CR7Transaction)
from ledger7.resources.transaction import (  # noqa: E402
    TransactionState as CR7TransactionState)
from ledger7.resources.fund import Fund as CR7Fund  # noqa: E402
from ledger7.resources.beacon_coa import BeaconCoa as CR7BeaconCoa  # noqa: E402
from ledger7.resources.workbook import Workbook as CR7Workbook  # noqa: E402
from ledger7.resources.workbook import (  # noqa: E402
    WorkbookState as CR7WorkbookState)
from ledger7.fund_source import FUNDS as FUNDS7  # noqa: E402
from ledger7.beacon_coa_source import BEACON_COAS as BEACON_COAS7  # noqa: E402
from ledger7.services import FakeBeacon as FakeBeacon7  # noqa: E402
from ledger7.surfaces import (  # noqa: E402
    CloseReview as CloseReview7, ReconcileAccount as ReconcileAccount7)


@dataclass
class SuiteServices7(SuiteServices):
    """mealplan7's services plus the Beacon boundary ledger7 declares —
    one shared engine/services pair covers both v7 dogfood apps, since
    `pytest --waymark7` walks a single ``waymark7_engine`` fixture."""

    beacon_backend: FakeBeacon7 = field(default_factory=FakeBeacon7)
    beacon: Any = None
    beacon_coas: Any = None

    def __post_init__(self) -> None:
        if self.beacon is None:
            from waymark7.server.external import Service

            self.beacon = Service("beacon", handler=self.beacon_backend.pull,
                                 timeout=30.0, backoff_seconds=60.0,
                                 down_on_error=True)
        if self.beacon_coas is None:
            from waymark7.server.external import Service

            # dispatches to whatever CR7BeaconCoa.adapter currently is at
            # call time (ledger7.services.Services._sync_coas's same
            # lazy-seam), since this fixture swaps the module singleton
            # per test rather than constructing a fresh adapter per case
            async def _sync_coas(fund_beacon_id: int) -> dict[str, Any]:
                return await CR7BeaconCoa.adapter.sync_fund(fund_beacon_id)

            self.beacon_coas = Service(
                "beacon_coas", handler=_sync_coas, timeout=30.0,
                backoff_seconds=60.0, down_on_error=True)


@pytest.fixture
async def waymark7_engine():
    from waymark7.server.bus import InProcessBus

    # the fund Mirror's adapter is class-level (the framework's seam);
    # reset the module-singleton fake per fixture, the framework's own
    # mirror-test discipline
    FUNDS7.docs.clear()
    FUNDS7.discoverable.clear()
    FUNDS7.down = False
    FUNDS7.pulls = 0
    CR7Fund.adapter = FUNDS7
    BEACON_COAS7.docs.clear()
    BEACON_COAS7.by_fund.clear()
    BEACON_COAS7.down = False
    BEACON_COAS7.pulls = 0
    CR7BeaconCoa.adapter = BEACON_COAS7

    services = SuiteServices7()
    engine = waymark7.Engine(
        resources=[Meal7, SundayRotation7, MealPlan7, GroceryList7,
                   PrepTask7, Event7,
                   CR7AccountTemplate, CR7Workbook, CR7Account,
                   CR7ReconBreak, CR7Transaction, CR7Fund, CR7BeaconCoa],
        surfaces=[CloseReview7, ReconcileAccount7],
        storage=TEST_DSN, services=services, bus=InProcessBus())
    services.beacon_backend.engine = engine
    CR7BeaconCoa.adapter.engine = engine
    await engine.storage.drop_all()
    await engine.startup()
    try:
        yield engine
    finally:
        await engine.shutdown()


w7_conformance_resource(Meal7)
w7_conformance_resource(SundayRotation7)
w7_conformance_resource(PrepTask7)
# the calendar kind: every field is schema-synthesizable (the closed
# ``kind`` vocab is a Literal with a default) — the derived walker suffices
w7_conformance_resource(Event7)

# 7.0 engine kinds: ordinary resources, ordinary conformance
from waymark7.server.attachments import (  # noqa: E402
    Attachment as Attachment7W, BYTES_ACTOR as BYTES_ACTOR7W,
)
from waymark7.server.members import Member as Member7W  # noqa: E402
from waymark7.server.roles import Role as Role7W  # noqa: E402
from waymark7.server.subscriptions import (  # noqa: E402
    WebhookSubscription as Subscription7W,
)

w7_conformance_resource(Member7W)
w7_conformance_resource(Role7W)
w7_conformance_resource(Subscription7W)


# an attachment's create must name a live target, so its states need a
# factory rather than a schema-synthesized create (design E5)
@w7_state_factory(Attachment7W)
async def w7_make_attachment(state: str, engine, services) -> Attachment7W:
    mid = await _mk(engine, "meal", {"name": "Attachment target",
                                     "themes": ["mexican"]})
    services.seeded["attachment_target"] = mid
    aid = await _mk(engine, "attachment", {
        "resource_kind": "meal", "resource_id": mid,
        "name": "recipe.pdf", "mime": "application/pdf"})
    if state in ("uploaded", "removed"):
        await engine.invoker.invoke(
            "attachment", aid, "mark_uploaded",
            {"size": 3, "sha256": "a" * 64}, principal=BYTES_ACTOR7W)
    if state == "removed":
        await _step(engine, "attachment", aid, "remove")
    return await _load(engine, "attachment", aid)


@w7_example_input(Attachment7W, "duplicate")
def w7_attachment_duplicate_example(services) -> dict:
    # a synthesized target would dangle; duplicate onto the factory's meal
    return {"resource_kind": "meal",
            "resource_id": services.seeded["attachment_target"]}


@w7_example_input(Role7W, "create")
def w7_role_create_example(services) -> dict:
    # role names are declared unique (design E2); the walker may create
    # several per test, so the example must mint fresh spellings
    return {"name": f"reader-{uuid.uuid4().hex[:8]}",
            "description": "May read shared note titles"}


@w7_example_input(Member7W, "create")
def w7_member_create_example(services) -> dict:
    return {"email": "mom@example.com", "display_name": "Grandma",
            "roles": ["reader"]}


@w7_example_input(Subscription7W, "create")
def w7_subscription_create_example(services) -> dict:
    return {"url": "https://budget.example/hooks", "kinds": ["plan"]}


@w7_example_input(Meal7, "create")
def w7_meal_create_example(services) -> dict:
    # 7.0 speaks only the current shape on the wire; the single-theme era
    # is a declared upcast (Meal7.shape/upcasts), not a payload dialect
    return {"name": "Carnitas tacos", "themes": ["mexican"],
            "recipe": "# Carnitas tacos\n\nSlow-cook the pork…",
            "prep_minutes": 45, "thaw_hours": 12}


@w7_example_input(PrepTask7, "create")
def w7_prep_task_create_example(services) -> dict:
    return prep_task_create_example(services)


async def _listed_meal7(engine, services) -> str:
    mid = await _mk(engine, "meal", {"name": "Tacos al pastor",
                                     "themes": ["mexican"]})
    await _step(engine, "meal", mid, "accept")
    services.seeded["meal_id"] = mid
    return mid


@w7_state_factory(MealPlan7)
async def w7_make_plan(state: str, engine, services) -> MealPlan7:
    rid = await _mk(engine, "rotation", {})
    await _listed_meal7(engine, services)
    pid = await _mk(engine, "plan", {"start_date": PLAN_START.isoformat(),
                                     "weeks": 1, "rotation_id": rid})
    services.seeded["plan_id"] = pid
    target = PlanState7(state)
    if target == PlanState7.ABANDONED:
        await _step(engine, "plan", pid, "abandon")
    elif target != PlanState7.DRAFT:
        for i in range(7):
            await _step(engine, "plan", pid, "mark_eating_out",
                        {"date": (PLAN_START + timedelta(days=i)).isoformat()})
        await _step(engine, "plan", pid, "finalize")
        if target in (PlanState7.ACTIVE, PlanState7.DONE):
            await _step(engine, "plan", pid, "begin")
        if target == PlanState7.DONE:
            await _step(engine, "plan", pid, "complete")
    return await _load(engine, "plan", pid)


# assign_meal needs no example: its Relation's tuple set feeds the
# synthesizer (waymark7.testing.factories.synthesize_input)
@w7_example_input(MealPlan7, "assign_off_theme")
def w7_assign_off_theme_example(services) -> dict:
    return {"date": PLAN_START.isoformat(), "meal_id": services.seeded["meal_id"]}


@w7_state_factory(GroceryList7)
async def w7_make_grocery_list(state: str, engine, services) -> GroceryList7:
    plan = await w7_make_plan(PlanState7.PLANNED, engine, services)
    gid = await _mk(engine, "grocery_list",
                    {"plan_id": plan.id, "items": GROCERY_ITEMS})
    target = GroceryState7(state)
    if target != GroceryState7.DRAFT:
        await _step(engine, "grocery_list", gid, "finalize")
    if target == GroceryState7.DONE:
        for item in GROCERY_ITEMS:
            await _step(engine, "grocery_list", gid, "check_item",
                        {"name": item["name"]})
        await _step(engine, "grocery_list", gid, "complete")
    return await _load(engine, "grocery_list", gid)


@w7_example_input(GroceryList7, "remove_item")
def w7_remove_item_example(services) -> dict:
    return {"name": "paper towels"}


# The fund is a read-only Mirror (ledger3 friction #3 closed). Since the
# 7.x Mirror-aware conformance extension (docs/waymark7-notes.md), a Mirror
# walks its sync states through the harness's own system-actor factory: the
# suite reads a staged state with pull-through suppressed (default-off seam),
# and the factory stages `fresh/stale/unreachable` via SYSTEM_OBSERVER. So
# the fund enrolls with the derived (Mirror-aware) walker — no hand-written
# factory. `conflicted` is skipped by the factory for this read-only mirror
# (push_on_write=False never diverges under our write). The full-walk proof
# lives in tests/waymark7/test_mirror_conformance.py; the fund's app
# behavior in tests/ledger7/test_story8_funds.py.
w7_conformance_resource(CR7Fund)

# beacon_coa is the same shape of read-only Mirror as fund (see beacon_coa.py)
# and enrolls the same way — the derived Mirror-aware walker, no
# hand-written factory.
w7_conformance_resource(CR7BeaconCoa)


async def _make_cr7_fund(engine) -> str:
    """A fresh, pulled-through fund per call (its name filled), for the
    workbook/account/template factories that denormalize the fund's name at
    create. Delegates to the Mirror-aware state factory, which pulls the
    adapter directly — so the name fills even under the suite's refresh
    suppression (a GET-path pull would be suppressed; this is not)."""
    fund = await w7_make_state("fund", "fresh", engine)
    return fund.id


async def _make_cr7_beacon_coa(engine) -> str:
    """A fresh, pulled-through beacon_coa per call — the same Mirror-aware
    delegation as ``_make_cr7_fund``, for the account_template/account
    factories that denormalize the CoA's name at create."""
    coa = await w7_make_state("beacon_coa", "fresh", engine)
    return coa.id


@w7_state_factory(CR7AccountTemplate)
async def w7_make_cr_template(state, engine, services) -> CR7AccountTemplate:
    fund_id = await _make_cr7_fund(engine)
    beacon_coa_id = await _make_cr7_beacon_coa(engine)
    tid = await _mk(engine, "account_template",
                    {"fund_id": fund_id, "name": "Ops checking",
                     "bank_name": "First Bank", "last4": "4321",
                     "beacon_coa_id": beacon_coa_id})
    if state == "retired":
        await _step(engine, "account_template", tid, "retire")
    return await _load(engine, "account_template", tid)


# The ledger helpers (_step_as, _cr_accounts_of, _cr_job_done,
# CR_PREPARER / CR_REVIEWER) are version-agnostic — they only touch the
# engine passed in. The workbook/account id helpers, though, must mint a
# fund first on v7 (fund_id is a real Ref now), so v7 gets its own.
async def _make_cr7_workbook_id(engine) -> str:
    """A fresh fund + registry template + workbook every call (never cached
    — a factory runs once per candidate principal per case, and unique
    (fund_id, period) would 409 on a repeat)."""
    fund_id = await _make_cr7_fund(engine)
    beacon_coa_id = await _make_cr7_beacon_coa(engine)
    await _mk(engine, "account_template",
              {"fund_id": fund_id, "name": "Ops checking",
               "bank_name": "First Bank", "last4": "4321",
               "beacon_coa_id": beacon_coa_id})
    return await _mk(engine, "workbook",
                     {"fund_id": fund_id, "period": "2026-06"})


async def _make_cr7_account_id(engine) -> str:
    wid = await _make_cr7_workbook_id(engine)
    accounts = await _cr_accounts_of(engine, wid)
    return accounts[0].id


@w7_state_factory(CR7Workbook)
async def w7_make_cr_workbook(state: str, engine, services) -> CR7Workbook:
    # fresh fund + fresh workbook every call, never cached: a factory runs
    # once per candidate principal per case, and a cached id already at
    # the target state can't be re-driven (unique=(fund_id, period) would
    # also 409) — see _make_cr7_workbook_id's docstring
    wid = await _make_cr7_workbook_id(engine)
    target = CR7WorkbookState(state)
    if target == CR7WorkbookState.ABANDONED:
        await _step(engine, "workbook", wid, "abandon")
        return await _load(engine, "workbook", wid)
    if target in (CR7WorkbookState.PREPARED, CR7WorkbookState.REVIEWED):
        # freshen the sync first: beacon_fresh is a warning guard on
        # prepare, and refresh stamps last_synced_at synchronously.
        # Wait for the deferred job to finish before moving on — a
        # conformance case calls this factory once per candidate
        # principal, and a job still running in the background would
        # otherwise race a *later* case's before/after transition count.
        refreshed = await _step(engine, "workbook", wid, "refresh")
        await _cr_job_done(engine, refreshed.doc["data"]["sync_job_id"])
        for acc in await _cr_accounts_of(engine, wid):
            if acc.state == "open":
                await _step(engine, "account", acc.id, "reconcile")
        await _step_as(engine, "workbook", wid, "prepare", CR_PREPARER)
        if target == CR7WorkbookState.REVIEWED:
            await _step_as(engine, "workbook", wid, "review", CR_REVIEWER)
    return await _load(engine, "workbook", wid)


@w7_state_factory(CR7Account)
async def w7_make_cr_account(state: str, engine, services) -> CR7Account:
    aid = await _make_cr7_account_id(engine)
    target = CR7AccountState(state)
    if target == CR7AccountState.REMOVED:
        await _step(engine, "account", aid, "remove")
    elif target == CR7AccountState.BALANCED:
        # a freshly seeded account is already reconciled (0 - 0 + 0 == 0)
        await _step(engine, "account", aid, "reconcile")
    return await _load(engine, "account", aid)


@w7_state_factory(CR7ReconBreak)
async def w7_make_cr_break(state: str, engine, services) -> CR7ReconBreak:
    aid = await _make_cr7_account_id(engine)
    bid = await _mk(engine, "break",
                    {"account_id": aid, "amount": 12.34,
                     "note": "conformance"})
    if CR7BreakState(state) == CR7BreakState.REMOVED:
        await _step(engine, "break", bid, "remove")
    return await _load(engine, "break", bid)


@w7_state_factory(CR7Transaction)
async def w7_make_cr_transaction(state: str, engine, services) -> CR7Transaction:
    aid = await _make_cr7_account_id(engine)
    tid = await _mk(engine, "transaction",
                    {"account_id": aid, "transaction_date": "2026-06-15",
                     "amount": -42.0, "memo": "conformance"})
    if CR7TransactionState(state) == CR7TransactionState.REMOVED:
        await _step(engine, "transaction", tid, "remove")
    return await _load(engine, "transaction", tid)
