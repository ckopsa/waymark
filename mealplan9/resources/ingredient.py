"""The Ingredient resource: the canonical pantry concept behind recipes,
grocery items, and store products.

An ingredient is store-agnostic ("chicken thighs"); what a store actually
sells is a Product (product.py) referencing it. The AI proposes
ingredients while parsing receipts and recipes — matching is fallible, so
the lifecycle mirrors Meal's: ``suggested`` until a human verdict, which
is what keeps the canonical list canonical instead of accreting "chicken
thigh" / "chicken thighs" / "boneless chicken thighs" as three rows.

``aliases`` is the load-bearing field: every name this ingredient goes by
on receipts and in recipes. Confirmed fuzzy matches fold their spelling
back in so the next match is exact. ``absorb`` is the dedupe verdict —
the survivor takes the duplicate's names, repoints its products, and
retires it; the cross-writes are declared touches, never quiet.

``preferred_stores`` carries the family's buying preference (best first);
the ``preferred_stores=costco`` membership filter is the "what would the
whole trip at Costco cover" entry point.
"""
from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import BaseModel, Field

from waymark9 import (
    Acknowledged,
    Advances,
    Allow,
    Bulk,
    Count,
    Ctx,
    Deny,
    E,
    Edit,
    Observed,
    Owns,
    Query,
    Ref,
    RefField,
    Resource,
    Safety,
    Vocab,
    VocabField,
    action,
    filterable,
    guard,
    link,
    sortable,
)


class IngredientState(StrEnum):
    SUGGESTED = "suggested"   # proposed by the AI, awaiting a human verdict
    ACTIVE = "active"         # canonical; products may reference it
    RETIRED = "retired"       # declined, merged away, or no longer used


Alias = Annotated[str, Field(min_length=1, max_length=200)]
Store = Annotated[str, Field(min_length=1, max_length=50)]


# one ownership edge, one consumer here (design §10): the tracked-product
# count is a library rollup — it badges the row and garnishes retire's
# warning below; nobody re-counts products in a handler or a client
_products = Owns("product", via="ingredient_id")


class IngredientData(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    aliases: Vocab[Alias] = VocabField(
        default=[], max_length=30,
        open=True,
        description="Every name this ingredient goes by on receipts and in "
                    "recipes — confirmed matches fold their spelling in so "
                    "the next match is exact")
    category: str | None = Field(
        default=None, max_length=50,
        description="produce, meat, pantry, … — the grocery list's "
                    "category vocabulary")
    unit: Literal["g", "ml", "each"] = Field(
        default="g",
        description="Recipes measure in grams; 'each' for counted things "
                    "like eggs")
    preferred_stores: Vocab[Store] = VocabField(
        default=[], max_length=10,
        open=True, facet=Observed(counts=True),
        description="Where the family prefers to buy this, best first")
    products_tracked: int = Count(
        _products, where={"state": ("tracked",)},
        description="Tracked store products representing this ingredient")
    notes: str | None = Field(default=None, max_length=2000,
                              json_schema_extra={"x-display": {
                                  "widget": "prose"}})


class DetailsInput(BaseModel):
    category: str | None = Field(default=None, max_length=50)
    unit: Literal["g", "ml", "each"] = "g"
    preferred_stores: list[Store] = Field(
        default_factory=list, max_length=10,
        description="Where the family prefers to buy this, best first — "
                    "replaces the current list")


class AliasesInput(BaseModel):
    aliases: list[Alias] = Field(
        max_length=30,
        description="Every name this ingredient goes by — replaces the "
                    "current set")


class AbsorbInput(BaseModel):
    duplicate_id: Ref["ingredient"] = RefField(
        min_length=1, pick=Query(state="active"),
        description="The duplicate this ingredient absorbs: its names fold "
                    "into the aliases, its products repoint here, and it "
                    "retires")


# ── Guards ──────────────────────────────────────────────────────────────
# the recorded 8.0 §5 residue: a verdict that READS another row's state is
# not pure over (row, input, clock), so it stays code — reads=("ingredient",)
# names the dependency honestly
@guard("Pick an active ingredient other than this one to absorb.",
       judges=("duplicate_id",), reads=("ingredient",))
async def duplicate_is_absorbable(r, inp: AbsorbInput, ctx: Ctx) -> Allow | Deny:
    if inp.duplicate_id == r.id:
        return Deny(errors={"duplicate_id": [
            "an ingredient cannot absorb itself"]})
    dup = await ctx.read("ingredient", inp.duplicate_id)
    if dup is None or dup.state != str(IngredientState.ACTIVE):
        return Deny(errors={"duplicate_id": ["not an active ingredient"]})
    return Allow()


# retiring with products still tracked is worth a warning, not a wall — the
# verdict is a tree over the stored rollup, like plan.calendar_clear
no_tracked_products = guard.expr(
    name="no_tracked_products", severity="warning",
    when=E.data("products_tracked").eq(0),
    explain="{n} tracked product(s) still point at this ingredient — absorb "
            "it into another ingredient to keep their history together, or "
            "acknowledge to retire anyway.",
    vars={"n": E.data("products_tracked")},
)


# ── Resource ────────────────────────────────────────────────────────────
class Ingredient(Resource):
    kind = "ingredient"
    State = IngredientState
    Data = IngredientData
    nav = "secondary"

    initial = IngredientState.SUGGESTED
    terminal = {IngredientState.RETIRED}

    summary = "{data.name} · {state.label}"

    # aliases and preferred_stores carry their own membership filters (and
    # the stores facet) via Vocab — only the plain fields are declared here
    filterable = filterable(
        state=filterable.Eq | filterable.In,
        category=filterable.Eq | filterable.In,
    )
    sortable = sortable("name", default="name")

    display = {"title": "{data.name}"}

    # the ingredient page shows how stores sell it: the link is the child
    # collection filtered to this row, the tracked count rides as badge
    # scent, and embed invites the rows onto the page itself
    links = (
        link("products", kind="product_collection",
             href="/products?ingredient_id={id}",
             embed=True, badge="products_tracked",
             summary="How stores sell this ingredient"),
        link("substitutions", kind="substitution_collection",
             href="/substitutions?from_ingredient_id={id}&state=accepted",
             embed=True,
             summary="What the family accepts in its place"),
    )

    owns = (_products,)

    @action(from_=IngredientState.SUGGESTED, to=IngredientState.ACTIVE,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Joining the pantry list is low-stakes; Retire "
                              "or Absorb takes an ingredient off it again.")),
            display=dict(label="Accept", style="primary", order=1))
    async def accept(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=IngredientState.SUGGESTED, to=IngredientState.ACTIVE,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Every selected suggestion joins the "
                                      "pantry list."),
            bulk=Bulk(max_items=200, defer_over=50),
            display=dict(label="Accept selected", style="primary"))
    async def accept_many(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=IngredientState.SUGGESTED, to=IngredientState.RETIRED,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Declining a suggestion is cheap — the next "
                              "receipt or recipe can suggest it again.")),
            display=dict(label="No thanks", order=2))
    async def decline(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=IngredientState.ACTIVE, to=IngredientState.ACTIVE,
            input=AliasesInput,
            edit=Edit(prefill=("aliases",)),
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Update aliases", order=2,
                         description="Retag every name this ingredient goes "
                                     "by on receipts and in recipes"))
    async def update_aliases(self, inp: AliasesInput, ctx: Ctx) -> None:
        self.data.aliases = list(dict.fromkeys(inp.aliases))

    @action(from_=IngredientState.ACTIVE, to=IngredientState.ACTIVE,
            input=DetailsInput,
            edit=Edit(prefill=("category", "unit", "preferred_stores")),
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Update details", order=3))
    async def update_details(self, inp: DetailsInput, ctx: Ctx) -> None:
        self.data.category = inp.category
        self.data.unit = inp.unit
        self.data.preferred_stores = list(dict.fromkeys(inp.preferred_stores))

    @action(from_=IngredientState.ACTIVE, to=IngredientState.ACTIVE,
            input=AbsorbInput, guards=[duplicate_is_absorbable],
            # rematch is may=: a duplicate with no live products absorbs
            # without touching any; retire always happens
            touches=(Advances("product", "rematch", may=True),
                     Advances("ingredient", "retire")),
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The duplicate's names fold into this "
                                      "ingredient's aliases, its products "
                                      "repoint here, and it retires."),
            display=dict(label="Absorb duplicate", order=4))
    async def absorb(self, inp: AbsorbInput, ctx: Ctx) -> None:
        dup = await ctx.read(Ingredient, inp.duplicate_id)
        assert dup is not None  # duplicate_is_absorbable ensured it
        known = {self.data.name, *self.data.aliases}
        self.data.aliases += [a for a in (dup.data.name, *dup.data.aliases)
                              if a not in known]
        page = 1
        while True:
            products = await ctx.find("product", limit=200, page=page,
                                      ingredient_id=inp.duplicate_id)
            for p in products:
                # a discontinued product keeps its historical match; only
                # live rows repoint
                if p.state in ("suggested", "tracked"):
                    await ctx.invoke("product", p.id, "rematch",
                                     {"ingredient_id": self.id})
            if len(products) < 200:
                break
            page += 1
        await ctx.invoke(Ingredient, inp.duplicate_id, "retire", None)

    @action(from_=IngredientState.ACTIVE, to=IngredientState.RETIRED,
            guards=[no_tracked_products],
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Retiring only stops new products and grocery "
                              "items from matching; the row and its "
                              "products' history stay readable.")),
            display=dict(label="Retire", style="danger", order=9))
    async def retire(self, inp: None, ctx: Ctx) -> None:
        pass
