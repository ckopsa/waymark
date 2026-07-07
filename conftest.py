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

