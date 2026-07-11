"""Root conftest for the mealplan8 branch.

The waymark7/waymark8 framework suites (``tests/waymark7/``,
``tests/waymark8/``) are self-contained. This module supplies the
``--waymark7`` conformance walker with the mealplan7 engine and
enrollments, and the ``--waymark8`` walker with mealplan8's (the v8
fork whose law is expressions — see ``docs/waymark8-design.md``); each
app also exercises its framework's built-in member/role/subscription/
attachment resources (a meal is the attachment target).
"""
from __future__ import annotations

import os
import uuid
from dataclasses import dataclass, field
from datetime import date, timedelta

import pytest

import waymark7
from waymark7.core.types import Principal
from waymark7.server.attachments import (
    Attachment as Attachment7W, BYTES_ACTOR as BYTES_ACTOR7W)
from waymark7.server.bus import InProcessBus
from waymark7.server.members import Member as Member7W
from waymark7.server.roles import Role as Role7W
from waymark7.server.subscriptions import (
    WebhookSubscription as Subscription7W)
from waymark7.testing import (  # noqa: E402
    conformance_resource as w7_conformance_resource,
    example_input as w7_example_input,
    per_worker_dsn,
    state_factory as w7_state_factory,
)

from mealplan7.event_source import EVENTS as EVENTS7
from mealplan7.resources.event import Event as Event7
from mealplan7.resources.grocery_list import (
    GroceryList as GroceryList7, GroceryState as GroceryState7)
from mealplan7.resources.meal import Meal as Meal7
from mealplan7.resources.plan import MealPlan as MealPlan7, PlanState as PlanState7
from mealplan7.resources.prep_task import PrepTask as PrepTask7
from mealplan7.resources.rotation import SundayRotation as SundayRotation7
from mealplan7.services import Services as MealplanServices

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

FACTORY_PRINCIPAL = Principal(id="owner", type="human", display="Owner")

PLAN_START = date(2026, 6, 30)
PLAN_SUNDAY = date(2026, 7, 5)

GROCERY_ITEMS = [
    {"name": "chicken thighs", "quantity": "2 lbs", "category": "meat"},
    {"name": "paper towels", "category": "household"},
]


def prep_task_create_example(services) -> dict:
    return {"plan_id": services.seeded.get("plan_id", "unlinked"),
            "date": PLAN_SUNDAY.isoformat(), "meal_name": "Pulled pork",
            "task_type": "thaw", "due_at": "2026-07-04T18:00:00+00:00",
            "duration_minutes": 720}


@dataclass
class ConformanceServices(MealplanServices):
    """mealplan7's services plus a stash the state factories use to hand real
    ids to ``@example_input`` functions (which only see services)."""

    seeded: dict = field(default_factory=dict)


# ── generic engine helpers ──────────────────────────────────────────────────
async def _load(engine, kind: str, id: str):
    async with engine.storage.session() as s:
        return await engine.storage.load(s, kind, id)


async def _mk(engine, kind: str, body: dict) -> str:
    created = await engine.invoker.create(kind, body,
                                          principal=FACTORY_PRINCIPAL,
                                          idempotency_key=uuid.uuid4().hex)
    return created.doc["self"].rsplit("/", 1)[-1]


async def _step(engine, kind: str, id: str, action: str, body=None):
    return await engine.invoker.invoke(kind, id, action, body,
                                       principal=FACTORY_PRINCIPAL,
                                       idempotency_key=uuid.uuid4().hex)


# ── the conformance engine (mealplan7 resources + its own services) ─────────
@pytest.fixture
async def waymark7_engine():
    # the calendar's Event Mirror adapter is class-level (the framework's
    # seam); reset the module-singleton fake per fixture
    EVENTS7.docs.clear()
    EVENTS7.discoverable.clear()
    EVENTS7.down = False
    EVENTS7.pulls = 0
    Event7.adapter = EVENTS7

    services = ConformanceServices()
    engine = waymark7.Engine(
        resources=[Meal7, SundayRotation7, MealPlan7, GroceryList7,
                   PrepTask7, Event7],
        storage=TEST_DSN, services=services, bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    try:
        yield engine
    finally:
        await engine.shutdown()


# ── mealplan7 enrollments (verbatim from the shared v7 conformance harness) ──
w7_conformance_resource(Meal7)
w7_conformance_resource(SundayRotation7)
w7_conformance_resource(PrepTask7)
# the calendar kind: every field is schema-synthesizable (the closed ``kind``
# vocab is a Literal with a default) — the derived walker suffices
w7_conformance_resource(Event7)

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
    # 7.0 speaks only the current shape on the wire; the single-theme era is a
    # declared upcast (Meal7.shape/upcasts), not a payload dialect
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


# assign_meal needs no example: its Relation's tuple set feeds the synthesizer
@w7_example_input(MealPlan7, "assign_off_theme")
def w7_assign_off_theme_example(services) -> dict:
    return {"date": PLAN_START.isoformat(),
            "meal_id": services.seeded["meal_id"]}


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


# ═══ the waymark8 half: mealplan8 (the expression-law fork) ═════════════════
import waymark8  # noqa: E402
from waymark8.server.attachments import (  # noqa: E402
    Attachment as Attachment8W, BYTES_ACTOR as BYTES_ACTOR8W)
from waymark8.server.bus import InProcessBus as InProcessBus8  # noqa: E402
from waymark8.server.members import Member as Member8W  # noqa: E402
from waymark8.server.roles import Role as Role8W  # noqa: E402
from waymark8.server.subscriptions import (  # noqa: E402
    WebhookSubscription as Subscription8W)
from waymark8.testing import (  # noqa: E402
    conformance_resource as w8_conformance_resource,
    example_input as w8_example_input,
    state_factory as w8_state_factory,
)

from mealplan8.event_source import EVENTS as EVENTS8  # noqa: E402
from mealplan8.resources.event import Event as Event8  # noqa: E402
from mealplan8.resources.grocery_list import (  # noqa: E402
    GroceryList as GroceryList8, GroceryState as GroceryState8)
from mealplan8.resources.meal import Meal as Meal8  # noqa: E402
from mealplan8.resources.plan import (  # noqa: E402
    MealPlan as MealPlan8, PlanState as PlanState8)
from mealplan8.resources.prep_task import PrepTask as PrepTask8  # noqa: E402
from mealplan8.resources.rotation import (  # noqa: E402
    SundayRotation as SundayRotation8)
from mealplan8.services import Services as MealplanServices8  # noqa: E402


@dataclass
class ConformanceServices8(MealplanServices8):
    """mealplan8's services plus the factory-to-example stash (see the
    waymark7 twin above)."""

    seeded: dict = field(default_factory=dict)


@pytest.fixture
async def waymark8_engine():
    EVENTS8.docs.clear()
    EVENTS8.discoverable.clear()
    EVENTS8.down = False
    EVENTS8.pulls = 0
    Event8.adapter = EVENTS8

    services = ConformanceServices8()
    engine = waymark8.Engine(
        resources=[Meal8, SundayRotation8, MealPlan8, GroceryList8,
                   PrepTask8, Event8],
        storage=TEST_DSN, services=services, bus=InProcessBus8())
    await engine.storage.drop_all()
    await engine.startup()
    try:
        yield engine
    finally:
        await engine.shutdown()


w8_conformance_resource(Meal8)
w8_conformance_resource(SundayRotation8)
w8_conformance_resource(PrepTask8)
w8_conformance_resource(Event8)

w8_conformance_resource(Member8W)
w8_conformance_resource(Role8W)
w8_conformance_resource(Subscription8W)


@w8_state_factory(Attachment8W)
async def w8_make_attachment(state: str, engine, services) -> Attachment8W:
    mid = await _mk(engine, "meal", {"name": "Attachment target",
                                     "themes": ["mexican"]})
    services.seeded["attachment_target"] = mid
    aid = await _mk(engine, "attachment", {
        "resource_kind": "meal", "resource_id": mid,
        "name": "recipe.pdf", "mime": "application/pdf"})
    if state in ("uploaded", "removed"):
        await engine.invoker.invoke(
            "attachment", aid, "mark_uploaded",
            {"size": 3, "sha256": "a" * 64}, principal=BYTES_ACTOR8W)
    if state == "removed":
        await _step(engine, "attachment", aid, "remove")
    return await _load(engine, "attachment", aid)


@w8_example_input(Attachment8W, "duplicate")
def w8_attachment_duplicate_example(services) -> dict:
    return {"resource_kind": "meal",
            "resource_id": services.seeded["attachment_target"]}


@w8_example_input(Role8W, "create")
def w8_role_create_example(services) -> dict:
    return {"name": f"reader-{uuid.uuid4().hex[:8]}",
            "description": "May read shared note titles"}


@w8_example_input(Member8W, "create")
def w8_member_create_example(services) -> dict:
    return {"email": "mom@example.com", "display_name": "Grandma",
            "roles": ["reader"]}


@w8_example_input(Subscription8W, "create")
def w8_subscription_create_example(services) -> dict:
    return {"url": "https://budget.example/hooks", "kinds": ["plan"]}


@w8_example_input(Meal8, "create")
def w8_meal_create_example(services) -> dict:
    return {"name": "Carnitas tacos", "themes": ["mexican"],
            "recipe": "# Carnitas tacos\n\nSlow-cook the pork…",
            "prep_minutes": 45, "thaw_hours": 12}


@w8_example_input(PrepTask8, "create")
def w8_prep_task_create_example(services) -> dict:
    return prep_task_create_example(services)


async def _listed_meal8(engine, services) -> str:
    mid = await _mk(engine, "meal", {"name": "Tacos al pastor",
                                     "themes": ["mexican"]})
    await _step(engine, "meal", mid, "accept")
    services.seeded["meal_id"] = mid
    return mid


@w8_state_factory(MealPlan8)
async def w8_make_plan(state: str, engine, services) -> MealPlan8:
    rid = await _mk(engine, "rotation", {})
    await _listed_meal8(engine, services)
    pid = await _mk(engine, "plan", {"start_date": PLAN_START.isoformat(),
                                     "weeks": 1, "rotation_id": rid})
    services.seeded["plan_id"] = pid
    target = PlanState8(state)
    if target == PlanState8.ABANDONED:
        await _step(engine, "plan", pid, "abandon")
    elif target != PlanState8.DRAFT:
        for i in range(7):
            await _step(engine, "plan", pid, "mark_eating_out",
                        {"date": (PLAN_START + timedelta(days=i)).isoformat()})
        await _step(engine, "plan", pid, "finalize")
        if target in (PlanState8.ACTIVE, PlanState8.DONE):
            await _step(engine, "plan", pid, "begin")
        if target == PlanState8.DONE:
            await _step(engine, "plan", pid, "complete")
    return await _load(engine, "plan", pid)


@w8_example_input(MealPlan8, "assign_off_theme")
def w8_assign_off_theme_example(services) -> dict:
    return {"date": PLAN_START.isoformat(),
            "meal_id": services.seeded["meal_id"]}


@w8_state_factory(GroceryList8)
async def w8_make_grocery_list(state: str, engine, services) -> GroceryList8:
    plan = await w8_make_plan(PlanState8.PLANNED, engine, services)
    gid = await _mk(engine, "grocery_list",
                    {"plan_id": plan.id, "items": GROCERY_ITEMS})
    target = GroceryState8(state)
    if target != GroceryState8.DRAFT:
        await _step(engine, "grocery_list", gid, "finalize")
    if target == GroceryState8.DONE:
        for item in GROCERY_ITEMS:
            await _step(engine, "grocery_list", gid, "check_item",
                        {"name": item["name"]})
        await _step(engine, "grocery_list", gid, "complete")
    return await _load(engine, "grocery_list", gid)


@w8_example_input(GroceryList8, "remove_item")
def w8_remove_item_example(services) -> dict:
    return {"name": "paper towels"}


# ═══ the waymark9 half: mealplan9 (the expression-law fork) ═════════════════
import waymark9  # noqa: E402
from waymark9.server.attachments import (  # noqa: E402
    Attachment as Attachment9W, BYTES_ACTOR as BYTES_ACTOR9W)
from waymark9.server.bus import InProcessBus as InProcessBus9  # noqa: E402
from waymark9.server.members import Member as Member9W  # noqa: E402
from waymark9.server.roles import Role as Role9W  # noqa: E402
from waymark9.server.subscriptions import (  # noqa: E402
    WebhookSubscription as Subscription9W)
from waymark9.testing import (  # noqa: E402
    conformance_resource as w9_conformance_resource,
    example_input as w9_example_input,
    state_factory as w9_state_factory,
)

from mealplan9.event_source import EVENTS as EVENTS9  # noqa: E402
from mealplan9.resources.event import Event as Event9  # noqa: E402
from mealplan9.resources.grocery_list import (  # noqa: E402
    GroceryList as GroceryList9, GroceryState as GroceryState9)
from mealplan9.resources.ingredient import (  # noqa: E402
    Ingredient as Ingredient9, IngredientState as IngredientState9)
from mealplan9.resources.meal import Meal as Meal9  # noqa: E402
from mealplan9.resources.meal_line import (  # noqa: E402
    MealLine as MealLine9, MealLineState as MealLineState9)
from mealplan9.resources.plan import (  # noqa: E402
    MealPlan as MealPlan9, PlanState as PlanState9)
from mealplan9.resources.prep_task import PrepTask as PrepTask9  # noqa: E402
from mealplan9.resources.product import (  # noqa: E402
    Product as Product9, ProductState as ProductState9)
from mealplan9.resources.rotation import (  # noqa: E402
    SundayRotation as SundayRotation9)
from mealplan9.services import Services as MealplanServices9  # noqa: E402


@dataclass
class ConformanceServices9(MealplanServices9):
    """mealplan9's services plus the factory-to-example stash (see the
    waymark7 twin above)."""

    seeded: dict = field(default_factory=dict)


@pytest.fixture
async def waymark9_engine():
    EVENTS9.docs.clear()
    EVENTS9.discoverable.clear()
    EVENTS9.down = False
    EVENTS9.pulls = 0
    Event9.adapter = EVENTS9

    services = ConformanceServices9()
    engine = waymark9.Engine(
        resources=[Meal9, MealLine9, SundayRotation9, MealPlan9,
                   GroceryList9, PrepTask9, Ingredient9, Product9, Event9],
        storage=TEST_DSN, services=services, bus=InProcessBus9())
    # async example inputs (ingredient.absorb) mint rows through this handle
    services.engine = engine
    await engine.storage.drop_all()
    await engine.startup()
    try:
        yield engine
    finally:
        await engine.shutdown()


w9_conformance_resource(Meal9)
w9_conformance_resource(SundayRotation9)
w9_conformance_resource(PrepTask9)
w9_conformance_resource(Event9)

w9_conformance_resource(Member9W)
w9_conformance_resource(Role9W)
w9_conformance_resource(Subscription9W)


@w9_state_factory(Attachment9W)
async def w9_make_attachment(state: str, engine, services) -> Attachment9W:
    mid = await _mk(engine, "meal", {"name": "Attachment target",
                                     "themes": ["mexican"]})
    services.seeded["attachment_target"] = mid
    aid = await _mk(engine, "attachment", {
        "resource_kind": "meal", "resource_id": mid,
        "name": "recipe.pdf", "mime": "application/pdf"})
    if state in ("uploaded", "removed"):
        await engine.invoker.invoke(
            "attachment", aid, "mark_uploaded",
            {"size": 3, "sha256": "a" * 64}, principal=BYTES_ACTOR9W)
    if state == "removed":
        await _step(engine, "attachment", aid, "remove")
    return await _load(engine, "attachment", aid)


@w9_example_input(Attachment9W, "duplicate")
def w9_attachment_duplicate_example(services) -> dict:
    return {"resource_kind": "meal",
            "resource_id": services.seeded["attachment_target"]}


@w9_example_input(Role9W, "create")
def w9_role_create_example(services) -> dict:
    return {"name": f"reader-{uuid.uuid4().hex[:8]}",
            "description": "May read shared note titles"}


@w9_example_input(Member9W, "create")
def w9_member_create_example(services) -> dict:
    return {"email": "mom@example.com", "display_name": "Grandma",
            "roles": ["reader"]}


@w9_example_input(Subscription9W, "create")
def w9_subscription_create_example(services) -> dict:
    return {"url": "https://budget.example/hooks", "kinds": ["plan"]}


@w9_example_input(Meal9, "create")
def w9_meal_create_example(services) -> dict:
    return {"name": "Carnitas tacos", "themes": ["mexican"],
            "recipe": "# Carnitas tacos\n\nSlow-cook the pork…",
            "prep_minutes": 45, "thaw_hours": 12}


@w9_example_input(PrepTask9, "create")
def w9_prep_task_create_example(services) -> dict:
    return prep_task_create_example(services)


async def _listed_meal9(engine, services) -> str:
    mid = await _mk(engine, "meal", {"name": "Tacos al pastor",
                                     "themes": ["mexican"]})
    await _step(engine, "meal", mid, "accept")
    services.seeded["meal_id"] = mid
    return mid


@w9_state_factory(MealPlan9)
async def w9_make_plan(state: str, engine, services) -> MealPlan9:
    rid = await _mk(engine, "rotation", {})
    await _listed_meal9(engine, services)
    pid = await _mk(engine, "plan", {"start_date": PLAN_START.isoformat(),
                                     "weeks": 1, "rotation_id": rid})
    services.seeded["plan_id"] = pid
    target = PlanState9(state)
    if target == PlanState9.ABANDONED:
        await _step(engine, "plan", pid, "abandon")
    elif target != PlanState9.DRAFT:
        for i in range(7):
            await _step(engine, "plan", pid, "mark_eating_out",
                        {"date": (PLAN_START + timedelta(days=i)).isoformat()})
        await _step(engine, "plan", pid, "finalize")
        if target in (PlanState9.ACTIVE, PlanState9.DONE):
            await _step(engine, "plan", pid, "begin")
        if target == PlanState9.DONE:
            await _step(engine, "plan", pid, "complete")
    return await _load(engine, "plan", pid)


@w9_example_input(MealPlan9, "assign_off_theme")
def w9_assign_off_theme_example(services) -> dict:
    return {"date": PLAN_START.isoformat(),
            "meal_id": services.seeded["meal_id"]}


@w9_state_factory(GroceryList9)
async def w9_make_grocery_list(state: str, engine, services) -> GroceryList9:
    plan = await w9_make_plan(PlanState9.PLANNED, engine, services)
    gid = await _mk(engine, "grocery_list",
                    {"plan_id": plan.id, "items": GROCERY_ITEMS})
    target = GroceryState9(state)
    if target != GroceryState9.DRAFT:
        await _step(engine, "grocery_list", gid, "finalize")
    if target == GroceryState9.DONE:
        for item in GROCERY_ITEMS:
            await _step(engine, "grocery_list", gid, "check_item",
                        {"name": item["name"]})
        await _step(engine, "grocery_list", gid, "complete")
    return await _load(engine, "grocery_list", gid)


@w9_example_input(GroceryList9, "remove_item")
def w9_remove_item_example(services) -> dict:
    return {"name": "paper towels"}


@w9_state_factory(Ingredient9)
async def w9_make_ingredient(state: str, engine, services) -> Ingredient9:
    # exactly ONE ingredient row: the collection contract counts the rows a
    # factory mints against the states it returns — absorb's duplicate is
    # created lazily by the async example input below, never here
    iid = await _mk(engine, "ingredient", {
        "name": "Chicken thighs",
        "aliases": ["boneless skinless chicken thighs"],
        "category": "meat", "preferred_stores": ["costco", "winco"]})
    services.seeded["ingredient_id"] = iid
    target = IngredientState9(state)
    if target != IngredientState9.SUGGESTED:
        await _step(engine, "ingredient", iid, "accept")
    if target == IngredientState9.RETIRED:
        await _step(engine, "ingredient", iid, "retire")
    return await _load(engine, "ingredient", iid)


@w9_example_input(Ingredient9, "create")
def w9_ingredient_create_example(services) -> dict:
    return {"name": "Crushed tomatoes", "aliases": ["tomatoes, crushed"],
            "category": "pantry", "preferred_stores": ["costco"]}


@w9_example_input(Ingredient9, "absorb")
async def w9_ingredient_absorb_example(services) -> dict:
    # example inputs may be async: mint a fresh active duplicate on demand
    # through the engine handle the fixture stashes on services
    engine = services.engine
    dup_id = await _mk(engine, "ingredient",
                       {"name": "Chicken thigh fillets", "category": "meat"})
    await _step(engine, "ingredient", dup_id, "accept")
    return {"duplicate_id": dup_id}


PRODUCT_SIGHTING = {"seen_on": "2026-07-01", "price_cents": 1899,
                    "source": "receipt", "ref": "costco-2026-07-01",
                    "quantity": 1}


@w9_state_factory(Product9)
async def w9_make_product(state: str, engine, services) -> Product9:
    iid = await _mk(engine, "ingredient",
                    {"name": "Chicken thighs", "category": "meat"})
    await _step(engine, "ingredient", iid, "accept")
    services.seeded["ingredient_id"] = iid
    pid = await _mk(engine, "product", {
        "ingredient_id": iid, "store": "costco",
        "name": "Kirkland chicken thighs 2.72 kg", "package_grams": 2720,
        "upc": "096619123456", "sightings": [PRODUCT_SIGHTING]})
    services.seeded["product_id"] = pid
    target = ProductState9(state)
    if target != ProductState9.SUGGESTED:
        await _step(engine, "product", pid, "confirm_match")
    if target == ProductState9.DISCONTINUED:
        await _step(engine, "product", pid, "discontinue")
    return await _load(engine, "product", pid)


@w9_example_input(Product9, "create")
def w9_product_create_example(services) -> dict:
    return {"ingredient_id": services.seeded["ingredient_id"],
            "store": "winco", "name": "WinCo chicken thighs 1 kg",
            "package_grams": 1000, "sightings": [PRODUCT_SIGHTING]}


@w9_example_input(Product9, "rematch")
def w9_product_rematch_example(services) -> dict:
    return {"ingredient_id": services.seeded["ingredient_id"]}


@w9_example_input(Product9, "record_sighting")
def w9_product_record_sighting_example(services) -> dict:
    return {"seen_on": "2026-07-08", "price_cents": 1799,
            "source": "scrape", "ref": "https://costco.example/thighs"}


@w9_example_input(Product9, "remove_sighting")
def w9_product_remove_sighting_example(services) -> dict:
    return {"seen_on": PRODUCT_SIGHTING["seen_on"]}


@w9_state_factory(MealLine9)
async def w9_make_meal_line(state: str, engine, services) -> MealLine9:
    iid = await _mk(engine, "ingredient",
                    {"name": "Chicken thighs", "category": "meat"})
    await _step(engine, "ingredient", iid, "accept")
    services.seeded["ingredient_id"] = iid
    mid = await _mk(engine, "meal", {"name": "Tacos al pastor",
                                     "themes": ["mexican"]})
    await _step(engine, "meal", mid, "accept")
    services.seeded["meal_id"] = mid
    lid = await _mk(engine, "meal_line", {"meal_id": mid,
                                          "ingredient_id": iid,
                                          "grams": 500})
    services.seeded["meal_line_id"] = lid
    if MealLineState9(state) == MealLineState9.REMOVED:
        await _step(engine, "meal_line", lid, "remove")
    return await _load(engine, "meal_line", lid)


@w9_example_input(MealLine9, "create")
def w9_meal_line_create_example(services) -> dict:
    return {"meal_id": services.seeded["meal_id"],
            "ingredient_id": services.seeded["ingredient_id"],
            "grams": 250}
