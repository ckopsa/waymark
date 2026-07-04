"""A minimal second resource: exercises links between kinds."""
from __future__ import annotations

from enum import StrEnum

from pydantic import AwareDatetime, BaseModel, Field

from waymark import Ctx, Resource, action, filterable, link, profile, sortable


class ShipmentState(StrEnum):
    PENDING = "pending"
    SHIPPED = "shipped"
    DELIVERED = "delivered"


class ShipmentData(BaseModel):
    order_id: str = Field(json_schema_extra={"x-display": {
        "label": "Order", "widget": "resource", "kind": "order"}})
    carrier: str = Field(default="UPS", max_length=100)
    shipped_at: AwareDatetime | None = None


class Shipment(Resource):
    kind = "shipment"
    State = ShipmentState
    Data = ShipmentData

    initial = ShipmentState.PENDING
    terminal = {ShipmentState.DELIVERED}

    summary = "Shipment {id} · via {data.carrier} · {state.label}"

    filterable = filterable(state=filterable.Eq | filterable.In)
    sortable = sortable("shipped_at", default="-shipped_at")

    links = (
        link("order", kind="order", href="/orders/{data.order_id}",
             summary="The order this shipment fulfils"),
    )

    profiles = {
        "with_order": profile(embed={"order": "summary"}),
    }

    @action(from_=ShipmentState.PENDING, to=ShipmentState.SHIPPED,
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Mark shipped", style="primary"))
    async def ship(self, inp: None, ctx: Ctx) -> None:
        self.data.shipped_at = ctx.now

    @action(from_=ShipmentState.SHIPPED, to=ShipmentState.DELIVERED,
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Mark delivered"))
    async def deliver(self, inp: None, ctx: Ctx) -> None:
        # deliver the goods → fulfil the order, one audited cascade (§14)
        order = await ctx.read("order", self.data.order_id)
        if order is not None and order.state == "paid":
            await ctx.invoke("order", self.data.order_id, "fulfil", None)
