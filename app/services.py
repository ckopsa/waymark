"""Service container for the example app: a fake payment provider."""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from uuid import UUID, uuid4

VALID_METHOD = UUID("00000000-0000-4000-8000-000000000001")
EXPIRED_METHOD = UUID("00000000-0000-4000-8000-000000000002")


@dataclass
class PaymentMethod:
    id: UUID
    expiry: date
    @property
    def expired(self) -> bool:
        return self.expiry < date(2026, 7, 1)


@dataclass
class FakePayments:
    methods: dict[UUID, PaymentMethod] = field(default_factory=lambda: {
        VALID_METHOD: PaymentMethod(VALID_METHOD, date(2030, 1, 1)),
        EXPIRED_METHOD: PaymentMethod(EXPIRED_METHOD, date(2026, 5, 1)),
    })
    charges: list[tuple[str, float]] = field(default_factory=list)
    refunds: list[str] = field(default_factory=list)

    async def get_method(self, method_id: UUID) -> PaymentMethod | None:
        return self.methods.get(method_id)

    async def charge(self, order, inp) -> None:
        # §11.3: handlers of idempotent actions must be replay-safe; the
        # engine's natural-replay dedupe keeps this from double-charging on
        # same-key/same-input retries, and the provider dedupes on order id.
        if any(oid == order.id for oid, _ in self.charges):
            return
        self.charges.append((order.id, order.data.total + (inp.tip or 0)))

    async def refund(self, order, inp) -> None:
        self.refunds.append(order.id)


@dataclass
class Services:
    payments: FakePayments = field(default_factory=FakePayments)
    features: set[str] = field(default_factory=set)


def mint_method(services: Services, *, expired: bool = False) -> UUID:
    mid = uuid4()
    services.payments.methods[mid] = PaymentMethod(
        mid, date(2026, 5, 1) if expired else date(2031, 1, 1))
    return mid
