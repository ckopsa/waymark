"""The Product resource: one store's representation of an ingredient.

"Kirkland Organic Chicken Thighs, 2.72 kg, Costco" is not an ingredient —
it's how Costco sells one. A product carries the two machine keys the
ingestion paths converge on (``upc`` for receipt lines, ``url`` for the
scraper) and accumulates price sightings; the rollups the family actually
reads (``latest_price_cents``, ``cents_per_100g``, ``price_is_stale``)
are derived facts over them, stated once.

The error-prone step in both ingestion paths is the *match* (this receipt
line ↔ that ingredient), not the price — so the lifecycle guards exactly
that: a product born from an unknown receipt line or scrape waits in
``suggested`` with the AI's best-guess ``ingredient_id`` until a human
confirms or rematches. A sighting has no lifecycle of its own, which is
why it is an embedded part (one per day; a same-day re-record replaces,
so retries and receipt-then-scrape days stay replay-safe).

``price_is_stale`` is the scraper's whole work queue:
``?state=tracked&price_is_stale=true`` — the resource says what needs
refreshing; no agent keeps a private list. Per-store trip questions read
the same surface: ``?store=costco&sort=cents_per_100g``.
"""
from __future__ import annotations

from datetime import date as date_t
from datetime import datetime, time, timedelta, timezone
from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, Field
from pydantic.json_schema import SkipJsonSchema

from waymark9 import (
    Acknowledged,
    Clock,
    Ctx,
    Derived,
    Edit,
    Guard,
    PartScope,
    Query,
    Ref,
    RefField,
    Resource,
    Safety,
    action,
    filterable,
    link,
    profile,
    sortable,
)

# a weekly-shopping family re-prices everything inside a month; older than
# that and the trip math is guessing
STALE_AFTER_DAYS = 30


class ProductState(StrEnum):
    SUGGESTED = "suggested"       # AI-matched to an ingredient, awaiting a verdict
    TRACKED = "tracked"           # match confirmed; sightings accumulate
    DISCONTINUED = "discontinued"


class PriceSighting(BaseModel):
    seen_on: date_t
    price_cents: int = Field(gt=0,
                             description="What ONE package cost, in cents "
                                         "— the per-item price, not the "
                                         "line's extended total",
                             json_schema_extra={"x-display": {
                                 "widget": "money", "label": "Price"}})
    source: Literal["receipt", "scrape"]
    ref: str | None = Field(default=None, max_length=280,
                            description="Receipt id or the scraped URL — "
                                        "where this price was seen",
                            json_schema_extra={"x-display": {"raw": True}})
    quantity: int = Field(default=1, ge=1,
                          description="Packages bought (receipts) — actual "
                                      "spend is price_cents × quantity; 1 "
                                      "for a scraped shelf price")
    on_sale: bool = False


class ProductData(BaseModel):
    ingredient_id: Ref["ingredient"] = RefField(
        min_length=1, label="ingredient_name",
        pick=Query(state="active"),
        description="The pantry ingredient this product represents")
    ingredient_name: str | None = Field(default=None, max_length=200)
    store: str = Field(min_length=1, max_length=50,
                       description="costco, winco, …")
    name: str = Field(min_length=1, max_length=200,
                      description="As printed on the shelf or receipt")
    package_grams: int | None = Field(
        default=None, ge=1,
        description="Net contents of one package — the unit-price "
                    "denominator; blank for counted goods")
    package_count: int | None = Field(
        default=None, ge=1,
        description="Items per package for counted goods (a dozen eggs)")
    upc: str | None = Field(default=None, max_length=20,
                            description="The receipt-matching key")
    url: str | None = Field(default=None, max_length=280,
                            description="The product page a scraper "
                                        "refreshes prices from",
                            json_schema_extra={"x-display": {"raw": True}})
    sightings: list[PriceSighting] = Field(default_factory=list)
    # the price rollups as declared facts (design §2): the trip math, the
    # cheapest-store sort, and the scraper's queue all read the one
    # definition — argmax over parts is beyond the expression grammar, so
    # the bodies stay fn= (pure over the declared inputs)
    last_seen_on: date_t | None = Derived(
        over=("sightings",),
        fn=lambda sightings: max((s.seen_on for s in sightings),
                                 default=None))
    latest_price_cents: int | None = Derived(
        over=("sightings",),
        fn=lambda sightings: (sorted(sightings,
                                     key=lambda s: s.seen_on)[-1].price_cents
                              if sightings else None),
        json_schema_extra={"x-display": {"widget": "money",
                                         "label": "Latest price"}})
    cents_per_100g: int | None = Derived(
        over=("latest_price_cents", "package_grams"),
        fn=lambda price, grams: (round(price * 100 / grams)
                                 if price and grams else None))
    # the clock can flip this without a write; the flip time is last seen
    # + the staleness window, which is richer than `now > field`, so the
    # declared flips_at= carries it (design §3)
    price_is_stale: bool = Derived(
        over=(Clock, "last_seen_on"),
        fn=lambda now, last: (last is None
                              or now.date() - last
                              >= timedelta(days=STALE_AFTER_DAYS)),
        flips_at=lambda r: (
            datetime.combine(
                r.data.last_seen_on + timedelta(days=STALE_AFTER_DAYS),
                time.min, tzinfo=timezone.utc)
            if r.data.last_seen_on is not None else None),
        explain="Last priced {last} — a receipt line or a scrape "
                "refreshes it.",
        vars=lambda now, last: {"last": last.isoformat() if last else "never"})
    notes: str | None = Field(default=None, max_length=2000,
                              json_schema_extra={"x-display": {
                                  "widget": "prose"}})


class ProductCreate(ProductData):
    """The create form: the match guess plus the observation that birthed
    the product (a receipt line or a scrape). Later prices arrive via
    ``record_sighting`` once the match is confirmed; the label field is
    the engine's."""

    sightings: list[PriceSighting] = Field(
        default_factory=list, max_length=1,
        description="The price observation this product was born from")
    ingredient_name: SkipJsonSchema[str | None] = None


class RematchInput(BaseModel):
    ingredient_id: Ref["ingredient"] = RefField(
        min_length=1, pick=Query(state="active"),
        description="The ingredient this product actually represents")


class DetailsInput(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    package_grams: int | None = Field(default=None, ge=1)
    package_count: int | None = Field(default=None, ge=1)
    upc: str | None = Field(default=None, max_length=20)
    url: str | None = Field(default=None, max_length=280,
                            json_schema_extra={"x-display": {"raw": True}})


class SeenOnInput(BaseModel):
    seen_on: date_t


# ── Guards ──────────────────────────────────────────────────────────────
# what's on record: the rendered enum, the per-part availability, and the
# enforcement, from one set
sighting_on_record = Guard(
    name="sighting_on_record",
    judges=("seen_on",),
    accepts=lambda r: [s.seen_on.isoformat() for s in r.data.sightings],
    explain="No sighting on {seen_on} for this product.",
)


# ── Resource ────────────────────────────────────────────────────────────
class Product(Resource):
    kind = "product"
    State = ProductState
    Data = ProductData
    Create = ProductCreate
    nav = "secondary"

    initial = ProductState.SUGGESTED
    terminal = {ProductState.DISCONTINUED}

    summary = "{data.name} · {data.store} · {state.label}"

    filterable = filterable(
        state=filterable.Eq | filterable.In,
        store=filterable.Eq | filterable.In,
        ingredient_id=filterable.Eq,
        upc=filterable.Eq,
        price_is_stale=filterable.Eq,
        cents_per_100g=filterable.Eq | filterable.Range,
        last_seen_on=filterable.Eq | filterable.Range,
    )
    sortable = sortable("name", "cents_per_100g", "last_seen_on",
                        default="name")

    display = {"title": "{data.name}"}

    links = (
        link("ingredient", kind="ingredient",
             href="/ingredients/{data.ingredient_id}",
             summary="The pantry ingredient this product represents"),
    )

    profiles = {
        "with_ingredient": profile(embed={"ingredient": "summary"}),
    }

    sightings = PartScope("sightings", key="seen_on")

    @action(from_=ProductState.SUGGESTED, to=ProductState.TRACKED,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Confirming a match is low-stakes; Rematch "
                              "repoints the product any time.")),
            display=dict(label="Confirm match", style="primary", order=1))
    async def confirm_match(self, inp: None, ctx: Ctx) -> None:
        pass

    # correcting the match IS confirming it — from suggested this lands
    # tracked with the corrected ingredient in one move; from tracked it
    # repoints (which is also how an absorb cascade re-parents)
    @action(from_={ProductState.SUGGESTED, ProductState.TRACKED},
            to=ProductState.TRACKED,
            input=RematchInput,
            edit=Edit(prefill=("ingredient_id",), fence=False,
                      unfenced_reason="A rematch is a one-field verdict, "
                                      "not a composition — the last verdict "
                                      "wins by design, and the absorb "
                                      "cascade repoints products it never "
                                      "rendered a form for."),
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Repointing is as cheap as it is to repoint "
                              "again; the sightings ride along either way.")),
            display=dict(label="Rematch ingredient", order=2))
    async def rematch(self, inp: RematchInput, ctx: Ctx) -> None:
        self.data.ingredient_id = inp.ingredient_id
        # ingredient_name is the engine's to maintain (Ref label, design §4)

    @action(from_=ProductState.SUGGESTED, to=ProductState.DISCONTINUED,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Dismissing a bad suggestion is cheap — the "
                              "next receipt or scrape can propose it "
                              "again.")),
            display=dict(label="Not a real product", order=3))
    async def dismiss(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=ProductState.TRACKED, to=ProductState.TRACKED,
            input=PriceSighting,
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Record price", style="primary", order=1))
    async def record_sighting(self, inp: PriceSighting, ctx: Ctx) -> None:
        # one sighting per day (the part key): a same-day re-record
        # replaces, so retries and receipt-then-scrape days stay replay-safe
        self.data.sightings = [s for s in self.data.sightings
                               if s.seen_on != inp.seen_on]
        self.data.sightings.append(inp)
        self.data.sightings.sort(key=lambda s: s.seen_on)

    @action(from_=ProductState.TRACKED, to=ProductState.TRACKED,
            input=SeenOnInput, place=sightings,
            guards=[sighting_on_record],
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Remove sighting", order=4))
    async def remove_sighting(self, inp: SeenOnInput, ctx: Ctx) -> None:
        # removing an absent sighting is a no-op, so retries stay replay-safe
        self.data.sightings = [s for s in self.data.sightings
                               if s.seen_on != inp.seen_on]

    @action(from_=ProductState.TRACKED, to=ProductState.TRACKED,
            input=DetailsInput,
            edit=Edit(prefill=("name", "package_grams", "package_count",
                               "upc", "url")),
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Update details", order=3))
    async def update_details(self, inp: DetailsInput, ctx: Ctx) -> None:
        self.data.name = inp.name
        self.data.package_grams = inp.package_grams
        self.data.package_count = inp.package_count
        self.data.upc = inp.upc
        self.data.url = inp.url

    @action(from_=ProductState.TRACKED, to=ProductState.DISCONTINUED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The product stops being tracked and "
                                      "leaves the store-trip math; its "
                                      "price history stays readable."),
            display=dict(label="Discontinue", style="danger", order=9))
    async def discontinue(self, inp: None, ctx: Ctx) -> None:
        pass
