"""The Substitution resource: a directed, family-approved stand-in claim.

"B can stand in for A" is a judgment about the pantry, not about any one
recipe — so it lives once, with the app's standard shape: the AI proposes
substitutions while writing recipes (it knows buttermilk ≈ milk + lemon);
a human verdict accepts the ones the family actually tolerates. It is the
complement of the ingredient's ``absorb``: absorb says *same concept,
different spelling*; substitution says *different concept, acceptable
stand-in*.

Directed on purpose (applesauce → oil in muffins is not oil → applesauce),
with a grams-per-gram ``ratio`` (the family measures in grams) and a prose
``context`` scope note. The pricing fallback consumes ACCEPTED rows only:
an unpriceable meal line may price via a substitute's products — always
marked ``priced_via``, never silently.
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
    require,
)


class SubstitutionState(StrEnum):
    SUGGESTED = "suggested"   # proposed by the AI, awaiting a human verdict
    ACCEPTED = "accepted"     # family-approved; pricing and shopping may use it
    RETIRED = "retired"       # declined or withdrawn


class SubstitutionData(BaseModel):
    from_ingredient_id: Ref["ingredient"] = RefField(
        min_length=1, label="from_ingredient_name",
        pick=Query(state="active"),
        description="The ingredient a recipe asks for")
    from_ingredient_name: str | None = Field(default=None, max_length=200)
    to_ingredient_id: Ref["ingredient"] = RefField(
        min_length=1, label="to_ingredient_name",
        pick=Query(state="active"),
        description="The stand-in")
    to_ingredient_name: str | None = Field(default=None, max_length=200)
    ratio: float = Field(
        default=1.0, gt=0, le=100,
        description="Grams of substitute per gram asked for — 100 g butter "
                    "≈ 80 g oil is 0.8")
    context: str | None = Field(
        default=None, max_length=200,
        description="Where this substitution holds — 'baking only', 'fine "
                    "in chili, not on the Traeger', …")
    # a claim that substitutes an ingredient for itself claims nothing —
    # the fact is declared once, and the create gate judges it
    distinct: bool = Derived(
        over=("from_ingredient_id", "to_ingredient_id"),
        expr=~E.f("from_ingredient_id").eq(E.f("to_ingredient_id")),
        explain="An ingredient cannot substitute for itself.")


class DetailsInput(BaseModel):
    ratio: float = Field(gt=0, le=100)
    context: str | None = Field(default=None, max_length=200)


class Substitution(Resource):
    kind = "substitution"
    State = SubstitutionState
    Data = SubstitutionData

    initial = SubstitutionState.SUGGESTED
    terminal = {SubstitutionState.RETIRED}

    summary = ("{data.from_ingredient_name} → {data.to_ingredient_name} · "
               "×{data.ratio} · {state.label}")

    create_guards = (require("distinct"),)

    filterable = filterable(
        state=filterable.Eq | filterable.In,
        from_ingredient_id=filterable.Eq,
        to_ingredient_id=filterable.Eq,
    )

    display = {"title": "{data.from_ingredient_name} → "
                        "{data.to_ingredient_name}"}

    links = (
        link("from_ingredient", kind="ingredient",
             href="/ingredients/{data.from_ingredient_id}",
             summary="The ingredient a recipe asks for"),
        link("to_ingredient", kind="ingredient",
             href="/ingredients/{data.to_ingredient_id}",
             summary="The stand-in"),
    )

    @action(from_=SubstitutionState.SUGGESTED, to=SubstitutionState.ACCEPTED,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Accepting is low-stakes; Retire withdraws "
                              "the substitution from pricing and shopping "
                              "any time.")),
            display=dict(label="Accept", style="primary", order=1))
    async def accept(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=SubstitutionState.SUGGESTED, to=SubstitutionState.RETIRED,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Declining is cheap — the AI can propose it "
                              "again if the family's taste changes.")),
            display=dict(label="No thanks", order=2))
    async def decline(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=SubstitutionState.ACCEPTED, to=SubstitutionState.ACCEPTED,
            input=DetailsInput,
            edit=Edit(prefill=("ratio", "context")),
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Update details", order=2))
    async def update_details(self, inp: DetailsInput, ctx: Ctx) -> None:
        self.data.ratio = inp.ratio
        self.data.context = inp.context

    @action(from_=SubstitutionState.ACCEPTED, to=SubstitutionState.RETIRED,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Retiring only stops pricing and shopping "
                              "from using the substitution; estimates "
                              "already stamped through it keep their "
                              "priced_via mark.")),
            display=dict(label="Retire", style="danger", order=9))
    async def retire(self, inp: None, ctx: Ctx) -> None:
        pass
