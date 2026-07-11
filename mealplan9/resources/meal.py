"""The Meal resource: the meal library, fed entirely by AI suggestions.

The AI (via the agent client / MCP tool surface) creates meals in
``suggested`` with a full recipe attached — recipes are never written by
hand. Humans review suggestions in the generic UI and ``accept`` the keepers
onto the meal list, which is what a plan day can be assigned from.

2.0: ``update_recipe`` is one ``Edit`` declaration — prefill, the If-Match
fence, and the shared live draft are a single concept instead of four flags.

A meal is tagged with every theme night it can serve (``themes``, a list —
fajitas are mexican *and* american); ``update_themes`` retags, and the
``themes`` filter is membership, not equality. Rows from the single-theme
era are a declared shape: ``shape=2`` with an upcast folding ``theme`` into
a one-tag ``themes`` — 3.0's answer to the before-validator that did this
silently and versionlessly in the v2 app (design §8).
"""
from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Any

from pydantic import BaseModel, Field

from waymark9 import (
    Acknowledged,
    Ctx,
    Derived,
    DraftPolicy,
    E,
    Edit,
    Guard,
    Observed,
    PartScope,
    Query,
    Ref,
    RefField,
    Resource,
    Safety,
    Vocab,
    VocabField,
    action,
    Bulk,
    filterable,
    sortable,
)


class MealState(StrEnum):
    SUGGESTED = "suggested"   # proposed by the AI, awaiting a human verdict
    ON_LIST = "on_list"       # on the family meal list; assignable to plan days
    RETIRED = "retired"       # declined or rotated out


Theme = Annotated[str, Field(min_length=1, max_length=50)]


class MealIngredient(BaseModel):
    """One recipe line, tied to the pantry. Writing (ingredient, grams) is
    enough: a blank est_cost_cents is priced by the engine at write time —
    grams × the best tracked product's unit price (preferred store first,
    else cheapest) — a deterministic lookup, not a judgment, so it doesn't
    wait on an agent. An explicit stamp wins (a client may know better,
    e.g. whole-package math)."""

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
                    "from tracked products at write time (preferred store "
                    "first, else cheapest per gram); stays blank only when "
                    "no priced product exists yet. An explicit value wins.",
        json_schema_extra={"x-display": {"widget": "money",
                                         "label": "Est. cost"}})


class MealData(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    # one declaration (design §6): membership filtering over a GIN-indexed
    # column, comma-list wire form, and observed-value facets all derive
    # from the Vocab — no filterable/faceted entries below
    themes: Vocab[Theme] = VocabField(
        min_length=1, max_length=10,
        open=True, facet=Observed(counts=True),
        description="Every theme night this meal can serve: weekday themes "
                    "(italian, mexican, american, asian, pizza, bbq) and/or "
                    "themes from the Sunday rotation")
    recipe: str | None = Field(
        default=None,
        description="Markdown recipe — written by the AI, never by hand",
        json_schema_extra={"x-display": {"label": "Recipe",
                                         "widget": "prose"}})
    ingredients: list[MealIngredient] = Field(
        default_factory=list,
        description="The recipe's ingredient lines, tied to the pantry — "
                    "written by the AI alongside the recipe")
    # what the meal potentially costs (pantry-prices, one level down from
    # the grocery list): the arithmetic over the stamped lines is the
    # meal's own law — one definition serves the envelope, the collection,
    # and "what's the cheapest bbq night"
    est_cost_cents: int = Derived(
        over=("ingredients",),
        expr=E.sum(E.f("ingredients"), of=E.it.est_cost_cents,
                   where=E.it.est_cost_cents.is_set()),
        json_schema_extra={"x-display": {"widget": "money",
                                         "label": "Est. cost"}})
    priced_ingredients: int = Derived(
        over=("ingredients",),
        expr=E.count(E.f("ingredients"), E.it.est_cost_cents.is_set()))
    total_ingredients: int = Derived(
        over=("ingredients",),
        expr=E.count(E.f("ingredients")))
    prep_minutes: int = Field(default=30, ge=0,
                              description="Active time from start to plated")
    thaw_hours: int = Field(default=0, ge=0,
                            description="Hours of thawing needed; 0 = none")
    servings: int | None = Field(default=None, ge=1)
    notes: str | None = Field(default=None, max_length=2000,
                              json_schema_extra={"x-display": {
                                  "widget": "prose"}})


class RecipeInput(BaseModel):
    recipe: str = Field(min_length=1, json_schema_extra={"x-display": {
        "label": "Recipe", "widget": "prose"}})
    prep_minutes: int | None = Field(default=None, ge=0)
    thaw_hours: int | None = Field(default=None, ge=0)


class ThemesInput(BaseModel):
    themes: list[Theme] = Field(
        min_length=1, max_length=10,
        description="The full set of theme nights this meal can serve — "
                    "replaces the current tags")


class IngredientsInput(BaseModel):
    ingredients: list[MealIngredient] = Field(
        max_length=50,
        description="The recipe's full ingredient lines — replaces the "
                    "current set")


class IngredientLineInput(BaseModel):
    """(ingredient, grams) is the whole form — the estimate prices itself
    from tracked products; explicit stamps ride update_ingredients."""

    ingredient_id: Ref["ingredient"] = RefField(
        min_length=1, pick=Query(state="active"),
        description="The pantry ingredient this line uses")
    grams: int = Field(ge=1,
                       description="Recipe quantity in grams — the family "
                                   "convention, never cups")


class IngredientRefInput(BaseModel):
    ingredient_id: Ref["ingredient"] = RefField(min_length=1)


# what's on the recipe: the rendered enum, the per-part availability, and
# the enforcement, from one set (the grocery list's item_on_list, one
# level down)
line_on_recipe = Guard(
    name="line_on_recipe",
    judges=("ingredient_id",),
    accepts=lambda r: [l.ingredient_id for l in r.data.ingredients],
    explain="No line for that ingredient on this recipe.",
)


async def _price_lines(lines: list[MealIngredient], ctx: Ctx,
                       *, refresh: bool = False) -> None:
    """Price lines from tracked products: grams × the best offer's
    cents_per_100g. Preferred store first (the ingredient's own ordering),
    else cheapest per gram — the same rule the trip math uses. By default
    only blank lines fill (an explicit stamp wins); ``refresh`` recomputes
    every line the lookup can reach — what reprice means. A line with no
    unit-priceable product keeps what it has, blank included, honestly."""
    for line in lines:
        if line.est_cost_cents is not None and not refresh:
            continue
        products = await ctx.find("product", state="tracked",
                                  ingredient_id=line.ingredient_id, limit=50)
        offers = [p for p in products if p.data.cents_per_100g]
        if not offers:
            continue
        ing = await ctx.read("ingredient", line.ingredient_id)
        prefs = [s.lower() for s in
                 (ing.data.preferred_stores if ing else [])]
        offers.sort(key=lambda p: (prefs.index(p.data.store)
                                   if p.data.store in prefs else 99,
                                   p.data.cents_per_100g))
        line.est_cost_cents = round(
            line.grams * offers[0].data.cents_per_100g / 100)


def _fold_theme(data: dict[str, Any]) -> dict[str, Any]:
    """shape 1 → 2: the single-theme era's ``theme`` becomes a one-tag
    ``themes`` list."""
    theme = data.pop("theme", None)
    if theme is not None and not data.get("themes"):
        data["themes"] = [theme]
    return data


class Meal(Resource):
    kind = "meal"
    State = MealState
    Data = MealData

    initial = MealState.SUGGESTED
    terminal = {MealState.RETIRED}

    shape = 2
    upcasts = {1: _fold_theme}

    # the one place per-line placement is declared (design §3); the
    # remove button renders once per ingredient row, key pre-bound
    ingredients = PartScope("ingredients", key="ingredient_id")

    async def on_create(self, ctx: Ctx) -> None:
        """A meal born with ingredient lines gets them priced immediately —
        (ingredient, grams) in the create body is enough."""
        await _price_lines(self.data.ingredients, ctx)

    summary = "{data.name} · {data.themes|join} · {state.label}"

    # themes is absent here on purpose: the Vocab declaration on MealData
    # carries its own filter (membership) and facet (observed) semantics
    filterable = filterable(state=filterable.Eq | filterable.In)
    # "what's the cheapest bbq night" = ?themes=bbq&sort=est_cost_cents
    sortable = sortable("name", "est_cost_cents", default="name")

    display = {"title": "{data.name}"}

    @action(from_=MealState.SUGGESTED, to=MealState.ON_LIST,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Joining the meal list is low-stakes; Retire "
                              "takes a meal off it again.")),
            display=dict(label="Add to meal list", style="primary", order=1))
    async def accept(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=MealState.SUGGESTED, to=MealState.ON_LIST,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Every selected suggestion joins the "
                                      "family meal list."),
            bulk=Bulk(max_items=200, defer_over=50),
            display=dict(label="Add selected to meal list", style="primary"))
    async def accept_many(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=MealState.SUGGESTED, to=MealState.RETIRED,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Declining a suggestion is cheap — the AI can "
                              "suggest it again any time.")),
            display=dict(label="No thanks", order=2))
    async def decline(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=MealState.ON_LIST, to=MealState.ON_LIST,
            input=RecipeInput,
            # one edit concept: prefilled (editing is not re-authoring),
            # fenced (a prefilled form is a snapshot), drafted shared + live
            # (the whole family can polish a recipe together)
            edit=Edit(prefill=("recipe", "prep_minutes", "thaw_hours"),
                      draft=DraftPolicy(shared=True, live=True)),
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Update recipe", order=2))
    async def update_recipe(self, inp: RecipeInput, ctx: Ctx) -> None:
        self.data.recipe = inp.recipe
        if inp.prep_minutes is not None:
            self.data.prep_minutes = inp.prep_minutes
        if inp.thaw_hours is not None:
            self.data.thaw_hours = inp.thaw_hours

    @action(from_=MealState.ON_LIST, to=MealState.ON_LIST,
            input=ThemesInput,
            edit=Edit(prefill=("themes",)),
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Update themes", order=3,
                         description="Retag the meal with every theme night "
                                     "it can serve"))
    async def update_themes(self, inp: ThemesInput, ctx: Ctx) -> None:
        self.data.themes = list(dict.fromkeys(inp.themes))

    @action(from_=MealState.ON_LIST, to=MealState.ON_LIST,
            input=IngredientsInput,
            edit=Edit(prefill=("ingredients",)),
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Update ingredients", order=6,
                         description="Rewrite the recipe's ingredient lines "
                                     "wholesale — Add/Remove ingredient "
                                     "handle single lines"))
    async def update_ingredients(self, inp: IngredientsInput,
                                 ctx: Ctx) -> None:
        self.data.ingredients = list(inp.ingredients)
        await _price_lines(self.data.ingredients, ctx)

    @action(from_=MealState.ON_LIST, to=MealState.ON_LIST,
            input=IngredientLineInput,
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Add ingredient", order=4,
                         description="Add one line (or re-quantity an "
                                     "existing one); a blank estimate "
                                     "prices itself from tracked products"))
    async def add_ingredient(self, inp: IngredientLineInput,
                             ctx: Ctx) -> None:
        existing = next((l for l in self.data.ingredients
                         if l.ingredient_id == inp.ingredient_id), None)
        if existing is not None:
            existing.grams = inp.grams
            existing.est_cost_cents = None  # re-quantity → re-price
        else:
            self.data.ingredients.append(MealIngredient(
                ingredient_id=inp.ingredient_id, grams=inp.grams))
        await _price_lines(self.data.ingredients, ctx)

    @action(from_=MealState.ON_LIST, to=MealState.ON_LIST,
            input=IngredientRefInput, place=ingredients,
            guards=[line_on_recipe],
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Remove", order=5))
    async def remove_ingredient(self, inp: IngredientRefInput,
                                ctx: Ctx) -> None:
        # removing an absent line is a no-op, so retries stay replay-safe
        self.data.ingredients = [l for l in self.data.ingredients
                                 if l.ingredient_id != inp.ingredient_id]

    # write-time pricing goes stale the moment a receipt teaches a better
    # price (or the first price) — reprice refreshes every line the lookup
    # can reach from CURRENT tracked products, no input to fill
    @action(from_=MealState.ON_LIST, to=MealState.ON_LIST,
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Reprice", order=5,
                         description="Refresh every ingredient line's "
                                     "estimate from current tracked "
                                     "product prices"))
    async def reprice(self, inp: None, ctx: Ctx) -> None:
        await _price_lines(self.data.ingredients, ctx, refresh=True)

    @action(from_=MealState.ON_LIST, to=MealState.RETIRED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The meal leaves the family list and "
                                      "can no longer be assigned to plan "
                                      "days."),
            display=dict(label="Retire", style="danger", order=9))
    async def retire(self, inp: None, ctx: Ctx) -> None:
        pass
