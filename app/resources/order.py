"""The Order resource — spec §10.1, with its deliberate gaps fixed:

- missing imports (UUID, AwareDatetime) added and RefundInput defined;
- ``place`` gets explicit ``confirm=False`` (the spec sample omits it, which
  the engine rejects by design) and an ``edit`` action back to draft so its
  ``reversible=True`` claim survives the graph check;
- ``fulfil`` (paid → fulfilled) added so FULFILLED is reachable.
"""
from __future__ import annotations

from datetime import timedelta
from enum import StrEnum
from uuid import UUID

from pydantic import AwareDatetime, BaseModel, Field

from waymark import Allow, Ctx, Deny, Resource, action, emits, filterable, guard, sortable


class OrderState(StrEnum):
    DRAFT = "draft"
    AWAITING_PAYMENT = "awaiting_payment"
    PAID = "paid"
    FULFILLED = "fulfilled"
    CANCELLED = "cancelled"


class LineItem(BaseModel):
    sku: str = Field(max_length=64)
    qty: int = Field(ge=1)
    price: float = Field(ge=0)


class OrderData(BaseModel):
    items: list[LineItem]
    total: float
    currency: str = Field(max_length=3, pattern="^[A-Z]{3}$")
    placed_at: AwareDatetime | None = None
    paid_at: AwareDatetime | None = None


class SubmitPayment(BaseModel):
    payment_method_id: UUID
    tip: float = Field(default=0, ge=0)


class CancelInput(BaseModel):
    reason: str | None = Field(default=None, max_length=500,
                               json_schema_extra={"x-display": {
                                   "widget": "prose"}})


class RefundInput(BaseModel):
    amount: float | None = Field(default=None, ge=0)
    reason: str | None = Field(default=None, max_length=500,
                               json_schema_extra={"x-display": {
                                   "widget": "prose"}})


# ── Guards ──────────────────────────────────────────────────────────────
@guard(else_="Payment method is invalid or expired. Update it, then retry.",
       remedies=["customer.update_payment_method"])
async def payment_method_valid(r: "Order", inp: SubmitPayment, ctx: Ctx) -> Allow | Deny:
    pm = await ctx.services.payments.get_method(inp.payment_method_id)
    return Allow() if pm and not pm.expired else Deny(
        errors={"payment_method_id": [f"Card expired {pm.expiry:%m/%Y}"]} if pm else None
    )


@guard(else_="Refund window closed on {deadline:%Y-%m-%d}.",
       becomes_available_at=lambda r: r.data.paid_at + timedelta(days=30))
async def within_refund_window(r, inp, ctx: Ctx) -> Allow | Deny:
    deadline = r.data.paid_at + timedelta(days=30)
    return Allow() if ctx.now < deadline else Deny(vars={"deadline": deadline})


manager_only = guard.role("manager", else_="Requires manager approval.",
                          requires_token="role:manager")


# ── Resource ────────────────────────────────────────────────────────────
class Order(Resource):
    kind = "order"
    State = OrderState
    Data = OrderData

    initial = OrderState.DRAFT
    terminal = {OrderState.FULFILLED, OrderState.CANCELLED}

    summary = ("Order #{id} · {data.items|len} items · "
               "{data.total:.2f} {data.currency} · {state.label}")

    filterable = filterable(
        state=filterable.Eq | filterable.In,
        total=filterable.Range,          # → total_gte / total_lte
        placed_at=filterable.After,      # → placed_after
    )
    sortable = sortable("placed_at", "total", default="-placed_at")

    display = {"title": "Order #{id}"}

    # ── Transitions ─────────────────────────────────────────────────────
    @action(from_=OrderState.DRAFT, to=OrderState.AWAITING_PAYMENT,
            idempotent=True, reversible=True, confirm=False,
            display=dict(label="Place order", style="primary", order=1))
    async def place(self, inp: None, ctx: Ctx) -> None:
        self.data.placed_at = ctx.now

    @action(from_=OrderState.AWAITING_PAYMENT, to=OrderState.DRAFT,
            idempotent=True, reversible=True, confirm=False,
            display=dict(label="Edit order", order=2))
    async def edit(self, inp: None, ctx: Ctx) -> None:
        self.data.placed_at = None

    @action(from_=OrderState.AWAITING_PAYMENT, to=OrderState.PAID,
            input=SubmitPayment, guards=[payment_method_valid],
            idempotent=True, reversible=False, confirm=False,
            requires_if_match=True,
            # payment methods live in an external vault: no enumerable set to
            # advertise, the client brings the token id
            waives=("open_input",),
            side_effects=emits("email:receipt", "webhook:order.paid"),
            display=dict(label="Pay now", style="primary", order=1))
    async def submit_payment(self, inp: SubmitPayment, ctx: Ctx) -> None:
        await ctx.services.payments.charge(self, inp)
        self.data.paid_at = ctx.now

    @action(from_=OrderState.PAID, to=OrderState.FULFILLED,
            idempotent=True, reversible=False, confirm=False,
            side_effects=emits("email:shipped"),
            display=dict(label="Fulfil", order=3))
    async def fulfil(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_={OrderState.DRAFT, OrderState.AWAITING_PAYMENT},
            to=OrderState.CANCELLED,
            input=CancelInput,
            idempotent=True, reversible=False, confirm=True,
            display=dict(label="Cancel order", style="danger", order=9))
    async def cancel(self, inp: CancelInput, ctx: Ctx) -> None:
        ...

    @action(from_={OrderState.DRAFT, OrderState.AWAITING_PAYMENT},
            to=OrderState.CANCELLED,
            input=CancelInput,
            idempotent=True, reversible=False, confirm=True,
            bulk=True, max_items=500, defer_over=50,
            display=dict(label="Cancel selected", style="danger"))
    async def cancel_many(self, inp: CancelInput, ctx: Ctx) -> None:
        ...

    @action(from_=OrderState.PAID, to=OrderState.PAID,   # self-transition
            input=RefundInput,
            guards=[within_refund_window, manager_only],
            idempotent=False, reversible=False, confirm=True,
            side_effects=emits("payment:refund"),
            display=dict(label="Refund", style="danger", order=9))
    async def refund(self, inp: RefundInput, ctx: Ctx) -> None:
        await ctx.services.payments.refund(self, inp)
