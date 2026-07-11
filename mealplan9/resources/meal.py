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
    Advances,
    Count,
    Ctx,
    DraftPolicy,
    Edit,
    Observed,
    Owns,
    Resource,
    Safety,
    Sum,
    Vocab,
    VocabField,
    action,
    Bulk,
    filterable,
    link,
    sortable,
)


class MealState(StrEnum):
    SUGGESTED = "suggested"   # proposed by the AI, awaiting a human verdict
    ON_LIST = "on_list"       # on the family meal list; assignable to plan days
    RETIRED = "retired"       # declined or rotated out


Theme = Annotated[str, Field(min_length=1, max_length=50)]


# the recipe's lines live as meal_line ROWS (their refs are promoted
# columns, so the engine maintains the rollups below in the same commit
# as any line write — the reason lines stopped being embedded data)
_lines = Owns("meal_line", via="meal_id")


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
    # what the meal potentially costs: engine-maintained rollups over the
    # meal_line edge — a line write (or its price) flips these in the same
    # commit; only on_recipe lines count
    est_cost_cents: int = Sum(
        _lines, "est_cost_cents", where={"state": ("on_recipe",)},
        json_schema_extra={"x-display": {"widget": "money",
                                         "label": "Est. cost"}})
    priced_ingredients: int = Count(
        _lines, where={"state": ("on_recipe",), "priced": (True,)})
    total_ingredients: int = Count(
        _lines, where={"state": ("on_recipe",)})
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

    summary = "{data.name} · {data.themes|join} · {state.label}"

    # themes is absent here on purpose: the Vocab declaration on MealData
    # carries its own filter (membership) and facet (observed) semantics
    filterable = filterable(state=filterable.Eq | filterable.In)
    # "what's the cheapest bbq night" = ?themes=bbq&sort=est_cost_cents
    sortable = sortable("name", "est_cost_cents", default="name")

    display = {"title": "{data.name}"}

    owns = (_lines,)

    # the meal page shows its recipe lines: the child collection filtered
    # to this row, the line count riding as badge scent, embed inviting
    # the rows onto the page
    links = (
        link("ingredients", kind="meal_line_collection",
             href="/meal_lines?meal_id={id}&state=on_recipe",
             embed=True, badge="total_ingredients",
             summary="The recipe's ingredient lines"),
    )

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

    # write-time pricing goes stale the moment a receipt teaches a better
    # price — reprice fans out to every on_recipe line (declared touches)
    @action(from_=MealState.ON_LIST, to=MealState.ON_LIST,
            touches=(Advances("meal_line", "reprice", may=True),),
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Reprice", order=4,
                         description="Refresh every ingredient line's "
                                     "estimate from current tracked "
                                     "product prices"))
    async def reprice(self, inp: None, ctx: Ctx) -> None:
        page = 1
        while True:
            lines = await ctx.find("meal_line", limit=200, page=page,
                                   meal_id=self.id, state="on_recipe")
            for line in lines:
                await ctx.invoke("meal_line", line.id, "reprice", None)
            if len(lines) < 200:
                break
            page += 1

    @action(from_=MealState.ON_LIST, to=MealState.RETIRED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The meal leaves the family list and "
                                      "can no longer be assigned to plan "
                                      "days."),
            display=dict(label="Retire", style="danger", order=9))
    async def retire(self, inp: None, ctx: Ctx) -> None:
        pass
