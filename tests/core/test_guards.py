from datetime import UTC, datetime, timedelta

import pytest
from pydantic import BaseModel

from waymark import Allow, Ctx, Deny, Principal, guard


class PayInput(BaseModel):
    method_id: str


def ctx(mode="invoke", roles=(), now=None) -> Ctx:
    return Ctx(
        principal=Principal(id="u1", roles=frozenset(roles)),
        now=now or datetime.now(UTC),
        mode=mode,
    )


@guard(else_="Payment method {method_id} is expired.", vars=["method_id"])
async def method_valid(r, inp: PayInput, c):
    return Allow() if inp.method_id == "good" else Deny(vars={"method_id": inp.method_id})


@guard(else_="Too early.")
async def after_noon(r, inp, c):
    return Allow() if c.now.hour >= 12 else Deny()


async def test_input_typed_guard_probe_short_circuits():
    verdict = await method_valid(None, None, ctx(mode="probe"))
    assert verdict == Allow(pending_input=True)


async def test_input_typed_guard_enforces_at_invoke():
    verdict = await method_valid(None, PayInput(method_id="bad"), ctx())
    assert isinstance(verdict, Deny)
    assert method_valid.render_reason(verdict) == "Payment method bad is expired."


async def test_input_independent_guard_runs_in_probe():
    early = ctx(mode="probe", now=datetime(2026, 7, 1, 9, tzinfo=UTC))
    assert isinstance(await after_noon(None, None, early), Deny)
    late = ctx(mode="probe", now=datetime(2026, 7, 1, 14, tzinfo=UTC))
    assert isinstance(await after_noon(None, None, late), Allow)


async def test_deny_vars_scanned_from_source():
    assert "method_id" in method_valid.declared_vars


async def test_and_composition_first_deny_wins_and_supplies_reason():
    role = guard.role("manager")
    combined = after_noon & role
    early = ctx(now=datetime(2026, 7, 1, 9, tzinfo=UTC))
    verdict, denier = await combined.evaluate(None, None, early)
    assert isinstance(verdict, Deny)
    assert denier.render_reason(verdict) == "Too early."

    late_no_role = ctx(now=datetime(2026, 7, 1, 14, tzinfo=UTC))
    verdict, denier = await combined.evaluate(None, None, late_no_role)
    assert denier.render_reason(verdict) == "Requires role 'manager'."
    assert denier.requires_token == "role:manager"

    late_manager = ctx(roles=["manager"], now=datetime(2026, 7, 1, 14, tzinfo=UTC))
    verdict, _ = await combined.evaluate(None, None, late_manager)
    assert isinstance(verdict, Allow)


async def test_or_composition_any_allow_wins():
    combined = guard.role("manager") | guard.role("admin")
    verdict, _ = await combined.evaluate(None, None, ctx(roles=["admin"]))
    assert isinstance(verdict, Allow)
    verdict, denier = await combined.evaluate(None, None, ctx())
    assert isinstance(verdict, Deny)
    assert "manager" in denier.render_reason(verdict)


async def test_role_guard_hide_flag():
    hidden = guard.role("admin", hide=True)
    assert hidden.hide is True


async def test_becomes_available_from_retry_at():
    when = datetime(2026, 7, 2, tzinfo=UTC)

    @guard(else_="Rate limited.")
    async def limited(r, inp, c):
        return Deny(retry_at=when)

    verdict = await limited(None, None, ctx())
    assert limited.becomes_available(verdict) == {"at": when.isoformat()}


async def test_becomes_available_from_requires_token():
    g = guard.role("manager")
    verdict = await g(None, None, ctx())
    assert g.becomes_available(verdict) == {"requires": "role:manager"}


async def test_rate_limit_denies_with_retry_at_and_probe_is_free():
    g = guard.rate_limit(2, 60)
    now = datetime(2026, 7, 1, 12, 0, tzinfo=UTC)
    for _ in range(5):  # probing never consumes budget
        assert isinstance(await g(None, None, ctx(mode="probe", now=now)), Allow)
    assert isinstance(await g(None, None, ctx(now=now)), Allow)
    assert isinstance(await g(None, None, ctx(now=now + timedelta(seconds=1))), Allow)
    verdict = await g(None, None, ctx(now=now + timedelta(seconds=2)))
    assert isinstance(verdict, Deny)
    assert verdict.retry_at == now + timedelta(seconds=60)


async def test_reason_never_crashes_on_missing_vars():
    @guard(else_="Deadline was {deadline}.", vars=["deadline"])
    async def g(r, inp, c):
        return Deny()  # forgot vars at runtime

    verdict = await g(None, None, ctx())
    assert g.render_reason(verdict) == "Deadline was {deadline}."
