"""The GroceryList resource: compiled by the AI from a finalized plan.

The agent reads the plan's assigned meals and their recipes, then creates
one list per plan (or per two-week stretch) in ``draft`` and fills it with
``add_item``. ``finalize`` is guarded on the plan actually being finalized —
a list can't get ahead of the plan it shops for. In ``ready`` the humans
shop, checking items off; ``complete`` refuses while anything is unchecked.

2.0: the item-shaped actions share one ``PartScope``; ``item_on_list`` is a
pure acceptance-set declaration; ``plan_id`` is a ``Ref``.
"""
from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, Field
from pydantic.json_schema import SkipJsonSchema

from waymark8 import (
    Acknowledged,
    Allow,
    Ctx,
    Deny,
    Derived,
    E,
    Guard,
    PartScope,
    Ref,
    RefField,
    Resource,
    Safety,
    action,
    filterable,
    guard,
    link,
    profile,
    require,
)


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
    meals: list[str] = Field(default_factory=list,
                             description="Meals this item shops for")
    have: bool = False


class GroceryData(BaseModel):
    plan_id: Ref["plan"] = RefField(
        min_length=1, description="The meal plan this list shops for")
    items: list[GroceryItem] = Field(default_factory=list)
    # the shopping rollup as a declared fact (design §2): complete's gate,
    # its rendered reason, and data.all_items_checked are one definition —
    # the hand-written guard that re-listed "unchecked" per probe is gone
    # 8.0: the shopping rollup's body is an expression — the vars=
    # garnish stays a lambda (prose is advertisement, design §5)
    all_items_checked: bool = Derived(
        over=("items",),
        expr=E.all(E.f("items"), E.it.have),
        explain="Still unchecked: {unchecked}.",
        vars=lambda items: {"unchecked": ", ".join(
            i.name for i in items if not i.have)})
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
    meals: list[str] = Field(default_factory=list,
                             description="Meals this item shops for")


class NameInput(BaseModel):
    name: str = Field(min_length=1, max_length=200)


# ── Guards ──────────────────────────────────────────────────────────────
# the recorded 8.0 §5 residue: a verdict that READS another kind's state
# is not pure over (row, input, clock), so it stays code — reads=("plan",)
# names the dependency honestly
@guard("Finalize the meal plan first — the grocery list follows from it.",
       reads=("plan",), remedies=("plan.finalize",))
async def plan_is_planned(r, inp, ctx: Ctx) -> Allow | Deny:
    plan = await ctx.read("plan", r.data.plan_id)
    if plan is None:
        return Deny(errors={"plan_id": ["plan not found"]})
    return Allow() if plan.state in ("planned", "active") else Deny()


# what's on the list: the rendered enum, the per-part availability, and the
# enforcement, from one set
item_on_list = Guard(
    name="item_on_list",
    judges=("name",),
    accepts=lambda r: [i.name for i in r.data.items],
    explain="No item named '{name}' on this list.",
)

# a checked item drops out of check_item's admitted set — so the button
# disappears from that row instead of staying clickable for a no-op
item_not_checked = Guard(
    name="item_not_checked",
    judges=("name",),
    accepts=lambda r: [i.name for i in r.data.items if not i.have],
    explain="'{name}' is already checked off.",
)

# the mirror of item_not_checked: uncheck_item only admits rows that are
# actually checked, so an accidental tap has a one-tap way back
item_checked = Guard(
    name="item_checked",
    judges=("name",),
    accepts=lambda r: [i.name for i in r.data.items if i.have],
    explain="'{name}' isn't checked off yet.",
)


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

    items = PartScope("items", key="name")

    @action(from_=GroceryState.DRAFT, to=GroceryState.DRAFT,
            input=ItemInput,
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Add item", style="primary", order=1))
    async def add_item(self, inp: ItemInput, ctx: Ctx) -> None:
        existing = next((i for i in self.data.items if i.name == inp.name), None)
        if existing is not None:
            existing.quantity = inp.quantity or existing.quantity
            existing.category = inp.category or existing.category
            existing.meals += [m for m in inp.meals if m not in existing.meals]
        else:
            self.data.items.append(GroceryItem(
                name=inp.name, quantity=inp.quantity, category=inp.category,
                meals=list(inp.meals)))

    @action(from_=GroceryState.DRAFT, to=GroceryState.DRAFT,
            input=NameInput, place=items, guards=[item_on_list],
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Remove item", order=2))
    async def remove_item(self, inp: NameInput, ctx: Ctx) -> None:
        # removing an absent item is a no-op, so retries stay replay-safe
        self.data.items = [i for i in self.data.items if i.name != inp.name]

    @action(from_=GroceryState.DRAFT, to=GroceryState.READY,
            guards=[plan_is_planned],
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Ready to shop", style="primary", order=1))
    async def finalize(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=GroceryState.READY, to=GroceryState.DRAFT,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Back to editing", order=3))
    async def reopen(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=GroceryState.READY, to=GroceryState.READY,
            input=NameInput, place=items,
            guards=[item_on_list, item_not_checked],
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Check off", style="primary", order=1))
    async def check_item(self, inp: NameInput, ctx: Ctx) -> None:
        for item in self.data.items:
            if item.name == inp.name:
                item.have = True

    @action(from_=GroceryState.READY, to=GroceryState.READY,
            input=NameInput, place=items,
            guards=[item_on_list, item_checked],
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Uncheck", order=2))
    async def uncheck_item(self, inp: NameInput, ctx: Ctx) -> None:
        for item in self.data.items:
            if item.name == inp.name:
                item.have = False

    @action(from_=GroceryState.READY, to=GroceryState.DONE,
            # the gate judges the stored fact; its reason is generated from
            # the derivation's explain=/vars= (design §5), never written here
            guards=[require("all_items_checked")],
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Completing records a finished shop; the list "
                              "stays readable as history.")),
            display=dict(label="Shopping done", order=2))
    async def complete(self, inp: None, ctx: Ctx) -> None:
        pass
