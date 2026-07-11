"""The MealLine resource: one recipe line, promoted from embedded data.

Lines were parts on the meal until the cost question outgrew them: a
line's estimate depends on another kind's rows (the ingredient's tracked
products), and an embedded ref is invisible to the join grammar — no
promoted column, no edge, no maintainer index, so the meal needed a
manual Reprice. As a resource the line's refs are real columns: the
meal's cost facts become engine-maintained rollups over an ``Owns`` edge
(a line write flips the meal's totals in the same commit), and the
line's own estimate is one grammar extension away from live derivation
(a ``Related`` edge to products — deferred until the tuple-input
question is settled; until then the estimate prices at write time and
``reprice`` refreshes it).

Removing a line is a transition, not a delete — the row stays readable
as history, and the meal's rollups count only ``on_recipe`` lines.
"""
from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, Field

from waymark9 import (
    Acknowledged,
    Ctx,
    Derived,
    E,
    Edit,
    Query,
    Ref,
    RefField,
    Resource,
    Safety,
    action,
    filterable,
    link,
    profile,
)


class MealLineState(StrEnum):
    ON_RECIPE = "on_recipe"
    REMOVED = "removed"


async def _best_unit_price(ingredient_id: str, ctx: Ctx) -> int | None:
    """The ingredient's best tracked cents_per_100g: preferred store first
    (the ingredient's own ordering), else cheapest per gram — the same
    rule the trip math uses. None when nothing is unit-priceable."""
    products = await ctx.find("product", state="tracked",
                              ingredient_id=ingredient_id, limit=50)
    offers = [p for p in products if p.data.cents_per_100g]
    if not offers:
        return None
    ing = await ctx.read("ingredient", ingredient_id)
    prefs = [s.lower() for s in (ing.data.preferred_stores if ing else [])]
    offers.sort(key=lambda p: (prefs.index(p.data.store)
                               if p.data.store in prefs else 99,
                               p.data.cents_per_100g))
    return offers[0].data.cents_per_100g


async def price_line(data, ctx: Ctx, *, refresh: bool = False) -> None:
    """Price the line from tracked products; when the ingredient itself is
    unpriceable, walk its ACCEPTED substitutions and price grams × ratio
    against the cheapest stand-in — always marked ``priced_via``, never
    silently. By default only a blank estimate fills (an explicit stamp
    wins); ``refresh`` recomputes when a lookup can reach a price. Nothing
    reachable → the line keeps what it has, blank included, honestly."""
    if data.est_cost_cents is not None and not refresh:
        return
    direct = await _best_unit_price(data.ingredient_id, ctx)
    if direct is not None:
        data.est_cost_cents = round(data.grams * direct / 100)
        data.priced_via = None  # priced as itself
        return
    subs = await ctx.find("substitution", state="accepted",
                          from_ingredient_id=data.ingredient_id, limit=50)
    candidates = []
    for sub in subs:
        unit = await _best_unit_price(sub.data.to_ingredient_id, ctx)
        if unit is None:
            continue
        cost = round(data.grams * sub.data.ratio * unit / 100)
        label = sub.data.to_ingredient_name or sub.data.to_ingredient_id
        if sub.data.ratio != 1.0:
            label = f"{label} ×{sub.data.ratio:g}"
        candidates.append((cost, label))
    if candidates:
        cost, label = min(candidates, key=lambda c: c[0])
        data.est_cost_cents = cost
        data.priced_via = label


class MealLineData(BaseModel):
    meal_id: Ref["meal"] = RefField(
        min_length=1, label="meal_name", pick=Query(state="on_list"),
        description="The meal this line belongs to")
    meal_name: str | None = Field(default=None, max_length=200)
    ingredient_id: Ref["ingredient"] = RefField(
        min_length=1, label="ingredient_name", pick=Query(state="active"),
        description="The pantry ingredient this line uses")
    ingredient_name: str | None = Field(default=None, max_length=200)
    grams: int = Field(ge=1,
                       description="Recipe quantity in grams — the family "
                                   "convention, never cups")
    est_cost_cents: int | None = Field(
        default=None, ge=0,
        description="Estimated cost of this line. Leave blank: it's priced "
                    "from tracked products at write time; stays blank only "
                    "when no priced product exists yet. An explicit value "
                    "wins.",
        json_schema_extra={"x-display": {"widget": "money",
                                         "label": "Est. cost"}})
    priced_via: str | None = Field(
        default=None, max_length=200,
        description="The accepted substitution the estimate priced "
                    "through — blank means priced as itself; an estimate "
                    "via a stand-in never masquerades as the real thing",
        json_schema_extra={"x-display": {"label": "Priced via"}})
    # the meal's priced-of-total rollup counts this promoted fact
    priced: bool = Derived(
        over=("est_cost_cents",),
        expr=E.f("est_cost_cents").is_set())


class GramsInput(BaseModel):
    grams: int = Field(ge=1)


class MealLine(Resource):
    kind = "meal_line"
    State = MealLineState
    Data = MealLineData

    initial = MealLineState.ON_RECIPE
    terminal = {MealLineState.REMOVED}

    summary = "{data.grams} g {data.ingredient_name} · {data.meal_name}"

    filterable = filterable(
        state=filterable.Eq | filterable.In,
        meal_id=filterable.Eq,
        ingredient_id=filterable.Eq,
        priced=filterable.Eq,
    )

    display = {"title": "{data.ingredient_name} — {data.meal_name}"}

    links = (
        link("meal", kind="meal", href="/meals/{data.meal_id}",
             summary="The meal this line belongs to"),
        link("ingredient", kind="ingredient",
             href="/ingredients/{data.ingredient_id}",
             summary="The pantry ingredient this line uses"),
    )

    profiles = {
        "with_ingredient": profile(embed={"ingredient": "summary"}),
    }

    async def on_create(self, ctx: Ctx) -> None:
        """(ingredient, grams) is enough — a blank estimate is priced from
        tracked products the moment the line exists."""
        await price_line(self.data, ctx)

    @action(from_=MealLineState.ON_RECIPE, to=MealLineState.ON_RECIPE,
            input=GramsInput,
            edit=Edit(prefill=("grams",)),
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Set grams", style="primary", order=1))
    async def set_grams(self, inp: GramsInput, ctx: Ctx) -> None:
        self.data.grams = inp.grams
        self.data.est_cost_cents = None  # re-quantity → re-price
        self.data.priced_via = None
        await price_line(self.data, ctx)

    # NOT idempotent, honestly: the outcome depends on the price world
    # outside this row, so reprice-after-a-receipt is a different act than
    # the reprice before it — declaring it idempotent would let the
    # engine's natural replay skip the second one
    @action(from_=MealLineState.ON_RECIPE, to=MealLineState.ON_RECIPE,
            safety=Safety(idempotent=False, reversible=False, confirm=False),
            display=dict(label="Reprice", order=2,
                         description="Refresh the estimate from current "
                                     "tracked product prices"))
    async def reprice(self, inp: None, ctx: Ctx) -> None:
        await price_line(self.data, ctx, refresh=True)

    @action(from_=MealLineState.ON_RECIPE, to=MealLineState.REMOVED,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Removing a line is cheap — add it again any "
                              "time; the row stays readable as history.")),
            display=dict(label="Remove", style="danger", order=9))
    async def remove(self, inp: None, ctx: Ctx) -> None:
        pass
