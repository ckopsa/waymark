"""The GroceryList resource: compiled by the AI from a finalized plan.

The agent reads the plan's assigned meals and their recipes, then creates
one list per plan (or per two-week stretch) in ``draft`` and fills it with
``add_item``. ``finalize`` is guarded on the plan actually being finalized —
a list can't get ahead of the plan it shops for. In ``ready`` the humans
shop, checking items off; ``complete`` refuses while anything is unchecked.
"""
from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, Field
from pydantic.json_schema import SkipJsonSchema

from waymark import Allow, Ctx, Deny, Resource, action, filterable, guard, link, profile


class GroceryState(StrEnum):
    DRAFT = "draft"    # the AI is compiling it
    READY = "ready"    # shopping from it
    DONE = "done"


class GroceryItem(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    quantity: str | None = Field(default=None, max_length=50,
                                 description='e.g. "2 lbs", "3 cans"')
    category: str | None = Field(default=None, max_length=50,
                                 description="produce, meat, pantry, …")
    have: bool = False


class GroceryData(BaseModel):
    plan_id: str = Field(min_length=1,
                         description="The meal plan this list shops for",
                         json_schema_extra={"x-display": {
                             "label": "Plan",
                             "widget": "resource", "kind": "plan"}})
    items: list[GroceryItem] = Field(default_factory=list)
    notes: str | None = Field(default=None, max_length=2000,
                              json_schema_extra={"x-display": {
                                  "widget": "prose"}})


class GroceryCreate(GroceryData):
    """The create form: pick the plan; items arrive via ``add_item``."""

    items: SkipJsonSchema[list[GroceryItem]] = Field(default_factory=list)


class ItemInput(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    quantity: str | None = Field(default=None, max_length=50)
    category: str | None = Field(default=None, max_length=50)


class NameInput(BaseModel):
    name: str = Field(min_length=1, max_length=200)


# ── Guards ──────────────────────────────────────────────────────────────
@guard(else_="Finalize the meal plan first — the grocery list follows from it.",
       remedies=["plan.finalize"])
async def plan_is_planned(r, inp, ctx: Ctx) -> Allow | Deny:
    plan = await ctx.read("plan", r.data.plan_id)
    if plan is None:
        return Deny(errors={"plan_id": ["plan not found"]})
    return Allow() if plan.state in ("planned", "active") else Deny()


@guard(else_="No item named '{name}' on this list.", vars=["name"],
       admits=("name", lambda r: [i.name for i in r.data.items]))
async def item_on_list(r, inp: NameInput, ctx: Ctx) -> Allow | Deny:
    if any(i.name == inp.name for i in r.data.items):
        return Allow()
    return Deny(vars={"name": inp.name},
                errors={"name": ["not on this list"]})


@guard(else_="Still unchecked: {unchecked}.", vars=["unchecked"])
async def all_items_checked(r, inp, ctx: Ctx) -> Allow | Deny:
    unchecked = [i.name for i in r.data.items if not i.have]
    if not unchecked:
        return Allow()
    return Deny(vars={"unchecked": ", ".join(unchecked)})


# ── Resource ────────────────────────────────────────────────────────────
class GroceryList(Resource):
    kind = "grocery_list"
    State = GroceryState
    Data = GroceryData
    Create = GroceryCreate

    initial = GroceryState.DRAFT
    terminal = {GroceryState.DONE}

    summary = "Groceries · {data.items|len} items · {state.label}"

    filterable = filterable(
        state=filterable.Eq | filterable.In,
        plan_id=filterable.Eq,
    )

    display = {"title": "Grocery list"}

    links = (
        link("plan", kind="plan", href="/plans/{data.plan_id}",
             summary="The meal plan this list shops for"),
    )

    profiles = {
        "with_plan": profile(embed={"plan": "summary"}),
    }

    @action(from_=GroceryState.DRAFT, to=GroceryState.DRAFT,
            input=ItemInput,
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Add item", style="primary", order=1))
    async def add_item(self, inp: ItemInput, ctx: Ctx) -> None:
        existing = next((i for i in self.data.items if i.name == inp.name), None)
        if existing is not None:
            existing.quantity = inp.quantity or existing.quantity
            existing.category = inp.category or existing.category
        else:
            self.data.items.append(GroceryItem(
                name=inp.name, quantity=inp.quantity, category=inp.category))

    @action(from_=GroceryState.DRAFT, to=GroceryState.DRAFT,
            input=NameInput,
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Remove item", order=2))
    async def remove_item(self, inp: NameInput, ctx: Ctx) -> None:
        # removing an absent item is a no-op, so retries stay replay-safe
        self.data.items = [i for i in self.data.items if i.name != inp.name]

    @action(from_=GroceryState.DRAFT, to=GroceryState.READY,
            guards=[plan_is_planned],
            idempotent=True, reversible=True, confirm=False,
            display=dict(label="Ready to shop", style="primary", order=1))
    async def finalize(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=GroceryState.READY, to=GroceryState.DRAFT,
            idempotent=True, reversible=True, confirm=False,
            display=dict(label="Back to editing", order=3))
    async def reopen(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=GroceryState.READY, to=GroceryState.READY,
            input=NameInput, scope=("items", "name"), guards=[item_on_list],
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Check off", style="primary", order=1))
    async def check_item(self, inp: NameInput, ctx: Ctx) -> None:
        for item in self.data.items:
            if item.name == inp.name:
                item.have = True

    @action(from_=GroceryState.READY, to=GroceryState.DONE,
            guards=[all_items_checked],
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Shopping done", order=2))
    async def complete(self, inp: None, ctx: Ctx) -> None:
        pass
