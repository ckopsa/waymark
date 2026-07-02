"""The Return workflow (§14): a resource whose handlers invoke transitions on
other resources *through the engine*, sharing the transaction and
correlation_id. Cross-resource invariants live here as ordinary guards.
"""
from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, Field

from waymark import Allow, Ctx, Deny, Guard, Resource, action, emits, guard

from .order import Order, OrderState, manager_only


class ReturnState(StrEnum):
    AWAITING_ITEM = "awaiting_item"
    INSPECTING = "inspecting"
    REFUNDING = "refunding"
    DONE = "done"
    REJECTED = "rejected"


class ReturnData(BaseModel):
    order_id: str
    reason: str | None = Field(default=None, max_length=500)
    condition_notes: str | None = None


class InspectionInput(BaseModel):
    condition_ok: bool
    notes: str | None = Field(default=None, max_length=500)


def order_is(state: OrderState) -> Guard:
    """Cross-resource guard: reads the order inside the same transaction."""

    @guard(else_=f"Order must be in state '{state}'. It is in state {{actual}}.",
           vars=["actual"])
    async def check(r, inp, ctx: Ctx) -> Allow | Deny:
        order = await ctx.read(Order, r.data.order_id)
        if order is None:
            return Deny(vars={"actual": "missing"})
        return (Allow() if order.state == str(state)
                else Deny(vars={"actual": f"'{order.state}'"}))

    check.name = f"order_is:{state}"
    return check


@guard(else_="Item failed inspection: {notes}.")
async def item_condition_ok(r, inp: InspectionInput, ctx) -> Allow | Deny:
    return Allow() if inp.condition_ok else Deny(
        vars={"notes": inp.notes or "no notes"},
        errors={"condition_ok": ["item not in returnable condition"]})


class Return(Resource):
    kind = "return"
    State = ReturnState
    Data = ReturnData

    initial = ReturnState.AWAITING_ITEM
    terminal = {ReturnState.DONE, ReturnState.REJECTED}

    summary = "Return {id} · order {data.order_id} · {state.label}"

    spans = (Order,)

    @action(from_=ReturnState.AWAITING_ITEM, to=ReturnState.INSPECTING,
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Item received", style="primary"))
    async def receive_item(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=ReturnState.INSPECTING, to=ReturnState.REFUNDING,
            input=InspectionInput,
            guards=[item_condition_ok, order_is(OrderState.PAID), manager_only],
            idempotent=False, reversible=False, confirm=True,
            side_effects=emits("payment:refund"),
            display=dict(label="Approve refund", style="primary"))
    async def approve(self, inp: InspectionInput, ctx: Ctx) -> None:
        self.data.condition_notes = inp.notes
        # the child transition shares this transaction and correlation_id
        await ctx.invoke(Order, self.data.order_id, "refund",
                         {"reason": f"return {self.id}"})

    @action(from_=ReturnState.INSPECTING, to=ReturnState.REJECTED,
            input=InspectionInput,
            idempotent=True, reversible=False, confirm=True,
            display=dict(label="Reject return", style="danger"))
    async def reject(self, inp: InspectionInput, ctx: Ctx) -> None:
        self.data.condition_notes = inp.notes

    @action(from_=ReturnState.REFUNDING, to=ReturnState.DONE,
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Complete"))
    async def complete(self, inp: None, ctx: Ctx) -> None:
        pass
