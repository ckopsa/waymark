"""The Meal resource: the meal library, fed entirely by AI suggestions.

The AI (via the agent client / MCP tool surface) creates meals in
``suggested`` with a full recipe attached — recipes are never written by
hand. Humans review suggestions in the generic UI and ``accept`` the keepers
onto the meal list, which is what a plan day can be assigned from.

2.0: ``update_recipe`` is one ``Edit`` declaration — prefill, the If-Match
fence, and the shared live draft are a single concept instead of four flags.
"""
from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, Field

from waymark2 import (
    Acknowledged,
    Ctx,
    DraftPolicy,
    Edit,
    Resource,
    Safety,
    action,
    Bulk,
    filterable,
    sortable,
)


class MealState(StrEnum):
    SUGGESTED = "suggested"   # proposed by the AI, awaiting a human verdict
    ON_LIST = "on_list"       # on the family meal list; assignable to plan days
    RETIRED = "retired"       # declined or rotated out


class MealData(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    theme: str = Field(
        min_length=1, max_length=50,
        description="A weekday theme (italian, mexican, american, asian, "
                    "pizza, bbq) or any theme from the Sunday rotation")
    recipe: str | None = Field(
        default=None,
        description="Markdown recipe — written by the AI, never by hand",
        json_schema_extra={"x-display": {"label": "Recipe",
                                         "widget": "prose"}})
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


class Meal(Resource):
    kind = "meal"
    State = MealState
    Data = MealData

    initial = MealState.SUGGESTED
    terminal = {MealState.RETIRED}

    summary = "{data.name} · {data.theme} · {state.label}"

    filterable = filterable(
        state=filterable.Eq | filterable.In,
        theme=filterable.Eq | filterable.In,
    )
    sortable = sortable("name", "theme", default="name")

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

    @action(from_=MealState.ON_LIST, to=MealState.RETIRED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The meal leaves the family list and "
                                      "can no longer be assigned to plan "
                                      "days."),
            display=dict(label="Retire", style="danger", order=9))
    async def retire(self, inp: None, ctx: Ctx) -> None:
        pass
