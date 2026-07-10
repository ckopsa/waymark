"""Guards judge facts; demand is conditional (design §5).

``require("fact")`` gates a transition on a declared bool derivation: the
renderer folds the refusal — reason generated from the Derived spec's
``explain=``/``vars=``, never hand-written — and the invoker judges the
same stored fact; a clock-extractable gate carries ``becomes_available.at``
from the maintained flip time. ``When(...)`` declares conditional
requiredness on an input model: the wire schema carries ``if``/``then``
(/``else``) with the resource comparand resolved per document, the invoker
enforces the same predicate as field-keyed 422s, and ``effort`` reflects
the base branch. The scenario is the deliverable tracker's: completing
late demands ``client_caused`` and a reason; on time, they are forbidden.
"""
from __future__ import annotations

import os
import uuid
from datetime import UTC, date, datetime, timedelta
from enum import StrEnum
from typing import ClassVar

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import AwareDatetime, BaseModel
from pydantic import Field as PField

import waymark9
from waymark9 import (
    Clock,
    Ctx,
    DefinitionError,
    Derived,
    Field,
    Owns,
    Ref,
    RefField,
    Resource,
    Safety,
    When,
    action,
    filterable,
    require,
)
from waymark9.server.bus import InProcessBus
from waymark9.server.engine import header_principal
from waymark9.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

NOW = datetime(2026, 7, 1, 12, 0, tzinfo=UTC)
DUE = date(2026, 7, 10)
TERMINAL = ("settled",)


# ── When: conditional demand on the deliverable ──────────────────────────

class StepState(StrEnum):
    ACTIVE = "active"
    COMPLETED = "completed"


class StepData(BaseModel):
    title: str = PField(min_length=1, max_length=120)
    due_date: date
    completed_on: date | None = None
    delay_client_caused: bool | None = None
    delay_reason: str | None = PField(None, max_length=240)


class CompleteInput(BaseModel):
    date_completed: date
    client_caused: bool | None = None
    reason: str | None = PField(
        None, json_schema_extra={"x-display": {"widget": "prose"}})

    late: ClassVar[When] = When(
        ("date_completed", ">", Field.of("due_date")),
        requires=("client_caused", "reason"),
        forbids_otherwise=True)


class Step(Resource):
    kind = "step"
    State = StepState
    Data = StepData
    initial = StepState.ACTIVE
    terminal = {StepState.COMPLETED}
    summary = "{data.title} · {state.label}"

    @action(from_=StepState.ACTIVE, to=StepState.COMPLETED,
            input=CompleteInput,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Marks the deliverable complete."))
    async def complete(self, inp: CompleteInput, ctx: Ctx) -> None:
        self.data.completed_on = inp.date_completed
        self.data.delay_client_caused = inp.client_caused
        self.data.delay_reason = inp.reason


# ── require: the rollup gate (parent-gated-on-children) ──────────────────

transfers = Owns("transfer", via="payout_id")


class TransferState(StrEnum):
    OPEN = "open"
    SETTLED = "settled"


class TransferData(BaseModel):
    payout_id: Ref["payout"] = RefField(min_length=1)


class Transfer(Resource):
    kind = "transfer"
    State = TransferState
    Data = TransferData
    initial = TransferState.OPEN
    terminal = {TransferState.SETTLED}
    summary = "transfer · {state.label}"
    filterable = filterable(state=filterable.Eq | filterable.In,
                            payout_id=filterable.Eq)

    @action(from_=TransferState.OPEN, to=TransferState.SETTLED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Marks the transfer settled."))
    async def settle(self, inp: None, ctx: Ctx) -> None:
        pass


class PayoutState(StrEnum):
    ACTIVE = "active"
    COMPLETED = "completed"


class PayoutData(BaseModel):
    memo: str = PField(min_length=1, max_length=120)
    all_transfers_terminal: bool = Derived(
        over=(transfers.field("state"),),
        fn=lambda states: all(s in TERMINAL for s in states),
        explain="{open} transfer(s) are still open.",
        vars=lambda states: {"open": sum(s not in TERMINAL for s in states)})


class Payout(Resource):
    kind = "payout"
    State = PayoutState
    Data = PayoutData
    initial = PayoutState.ACTIVE
    terminal = {PayoutState.COMPLETED}
    summary = "{data.memo} · {state.label}"
    owns = (transfers,)

    @action(from_=PayoutState.ACTIVE, to=PayoutState.COMPLETED,
            guards=[require("all_transfers_terminal")],
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Closes the payout."))
    async def complete(self, inp: None, ctx: Ctx) -> None:
        pass


# ── require: the clocked gate (becomes_available.at is maintained) ───────

class OfferState(StrEnum):
    ACTIVE = "active"
    ACCEPTED = "accepted"


class OfferData(BaseModel):
    available_at: AwareDatetime
    matured: bool = Derived(
        over=("available_at", Clock),
        fn=lambda at, now: now >= at,
        explain="Not open for acceptance until {when}.",
        vars=lambda at, now: {"when": at.isoformat()})


class Offer(Resource):
    kind = "offer"
    State = OfferState
    Data = OfferData
    initial = OfferState.ACTIVE
    terminal = {OfferState.ACCEPTED}
    summary = "offer · {state.label}"

    @action(from_=OfferState.ACTIVE, to=OfferState.ACCEPTED,
            guards=[require("matured")],
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Accepts the offer."))
    async def accept(self, inp: None, ctx: Ctx) -> None:
        pass


RESOURCES = [Step, Payout, Transfer, Offer]
OWNER = {"X-Principal-Id": "marcus", "X-Principal-Display": "Marcus"}


@pytest.fixture
async def env():
    clock = {"now": NOW}
    engine = waymark9.Engine(resources=RESOURCES, storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus(), clock=lambda: clock["now"])
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t", headers=OWNER)
    try:
        yield engine, client, clock
    finally:
        await client.aclose()
        await engine.shutdown()


async def _post(client, href, json=None):
    return await client.post(href, json=json,
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def _step(client) -> dict:
    res = await _post(client, "/api/steps",
                      {"title": "Ship the report",
                       "due_date": DUE.isoformat()})
    assert res.status_code == 201, res.text
    return res.json()


# ── the wire advertisement ────────────────────────────────────────────────

async def test_if_then_schema_rides_the_wire(env):
    """One declaration's first consumer: the input schema carries the
    predicate as if/then/else, with the resource's own due_date resolved
    into the comparison — the generic client reveals the late arm from
    the schema alone."""
    _, client, _ = env
    step = await _step(client)
    doc = (await client.get(step["self"])).json()
    schema = doc["actions"]["complete"]["input"]

    assert schema["if"] == {
        "required": ["date_completed"],
        "properties": {"date_completed": {"exclusiveMinimum": "2026-07-10"}},
    }
    assert schema["then"] == {"required": ["client_caused", "reason"]}
    assert schema["else"] == {"not": {"anyOf": [{"required": ["client_caused"]},
                                                {"required": ["reason"]}]}}


async def test_effort_reflects_the_base_branch(env):
    """The third consumer: conditionally-required fields do not count
    toward the entry's effort — on time, completing is a date (recall),
    not a composition; the if/then block advertises what lateness adds."""
    _, client, _ = env
    step = await _step(client)
    doc = (await client.get(step["self"])).json()
    assert doc["actions"]["complete"]["effort"] == "recall", \
        "the prose `reason` belongs to the conditional arm, not the base"


# ── the enforcement, both directions ──────────────────────────────────────

async def test_late_without_the_arm_is_a_field_keyed_422(env):
    _, client, _ = env
    step = await _step(client)
    res = await _post(client, f"{step['self']}/-/complete",
                      {"date_completed": "2026-07-12"})
    assert res.status_code == 422, res.text
    errors = res.json()["errors"]
    assert set(errors) == {"client_caused", "reason"}
    assert "required when" in errors["client_caused"][0]
    assert "due date" in errors["client_caused"][0]


async def test_on_time_with_the_arm_is_a_field_keyed_422(env):
    _, client, _ = env
    step = await _step(client)
    res = await _post(client, f"{step['self']}/-/complete",
                      {"date_completed": "2026-07-08",
                       "client_caused": True})
    assert res.status_code == 422, res.text
    errors = res.json()["errors"]
    assert set(errors) == {"client_caused"}
    assert "must be omitted unless" in errors["client_caused"][0]


async def test_both_branches_complete(env):
    _, client, _ = env

    on_time = await _step(client)
    res = await _post(client, f"{on_time['self']}/-/complete",
                      {"date_completed": "2026-07-10"})
    assert res.status_code == 200, res.text
    assert res.json()["state"] == "completed"

    late = await _step(client)
    res = await _post(client, f"{late['self']}/-/complete",
                      {"date_completed": "2026-07-12",
                       "client_caused": True,
                       "reason": "Client shifted the deadline twice."})
    assert res.status_code == 200, res.text
    assert res.json()["data"]["delay_reason"].startswith("Client shifted")


# ── require: projector and invoker read the one stored fact ──────────────

async def test_require_folds_the_derivations_own_explanation(env):
    """The gate's refusal is generated from the Derived spec's explain=
    and vars= — the carried metadata's consumer — identically on the
    envelope and in the 409."""
    _, client, _ = env
    payout = (await _post(client, "/api/payouts", {"memo": "July run"})).json()
    for _ in range(2):
        await _post(client, "/api/transfers",
                    {"payout_id": payout["self"].rsplit("/", 1)[-1]})

    doc = (await client.get(payout["self"])).json()
    assert "complete" not in doc["actions"]
    entry = doc["unavailable"]["complete"]
    assert entry["reason"] == "2 transfer(s) are still open."

    refused = await _post(client, f"{payout['self']}/-/complete")
    assert refused.status_code == 409, refused.text
    assert refused.json()["detail"] == entry["reason"]


async def test_require_opens_when_the_fact_flips(env):
    _, client, _ = env
    payout = (await _post(client, "/api/payouts", {"memo": "August run"})).json()
    t = (await _post(client, "/api/transfers",
                     {"payout_id": payout["self"].rsplit("/", 1)[-1]})).json()

    doc = (await client.get(payout["self"])).json()
    assert doc["unavailable"]["complete"]["reason"] == \
        "1 transfer(s) are still open."

    await _post(client, f"{t['self']}/-/settle")
    doc = (await client.get(payout["self"])).json()
    assert "complete" in doc["actions"], \
        "the child's settle recomputed the parent's fact in the same commit"
    res = await _post(client, f"{payout['self']}/-/complete")
    assert res.status_code == 200, res.text


async def test_clocked_require_carries_becomes_available_at(env):
    """Where the gating fact is clock-extractable, the refusal says when it
    flips — read from the maintained flip time, on the envelope and in the
    409 alike — and the tick opens the gate."""
    engine, client, clock = env
    opens = NOW + timedelta(hours=3)
    offer = (await _post(client, "/api/offers",
                         {"available_at": opens.isoformat()})).json()
    assert offer["data"]["matured"] is False

    doc = (await client.get(offer["self"])).json()
    entry = doc["unavailable"]["accept"]
    assert entry["reason"] == f"Not open for acceptance until {opens.isoformat()}."
    assert entry["becomes_available"] == {"at": opens.isoformat()}

    refused = await _post(client, f"{offer['self']}/-/accept")
    assert refused.status_code == 409, refused.text
    body = refused.json()
    assert body["detail"] == entry["reason"]
    assert body["becomes_available"] == {"at": opens.isoformat()}

    clock["now"] = opens + timedelta(minutes=1)
    await engine.tick(now=clock["now"])
    doc = (await client.get(offer["self"])).json()
    assert "accept" in doc["actions"]
    res = await _post(client, f"{offer['self']}/-/accept")
    assert res.status_code == 200, res.text
    assert res.json()["state"] == "accepted"


# ── import-time checks ────────────────────────────────────────────────────

async def test_require_on_a_non_derived_field_is_refused():
    with pytest.raises(DefinitionError, match="names no derived field"):
        class Bad(Resource):
            kind = "bad_gate"
            State = PayoutState
            Data = PayoutData
            initial = PayoutState.ACTIVE
            terminal = {PayoutState.COMPLETED}
            summary = "{data.memo}"

            @action(from_=PayoutState.ACTIVE, to=PayoutState.COMPLETED,
                    guards=[require("memo")],
                    safety=Safety(idempotent=True, reversible=False,
                                  confirm=True, consequence="Closes."))
            async def complete(self, inp: None, ctx: Ctx) -> None:
                pass


async def test_require_on_a_non_bool_derivation_is_refused():
    class SumData(BaseModel):
        amount: float = 0.0
        doubled: float = Derived(over=("amount",), fn=lambda a: a * 2)

    with pytest.raises(DefinitionError, match="non-bool"):
        class Bad(Resource):
            kind = "bad_bool_gate"
            State = PayoutState
            Data = SumData
            initial = PayoutState.ACTIVE
            terminal = {PayoutState.COMPLETED}
            summary = "sum"

            @action(from_=PayoutState.ACTIVE, to=PayoutState.COMPLETED,
                    guards=[require("doubled")],
                    safety=Safety(idempotent=True, reversible=False,
                                  confirm=True, consequence="Closes."))
            async def complete(self, inp: None, ctx: Ctx) -> None:
                pass


async def test_when_naming_unknown_fields_is_refused():
    class BadInput(BaseModel):
        date_completed: date
        client_caused: bool | None = None

        late: ClassVar[When] = When(
            ("date_completed", ">", Field.of("no_such_field")),
            requires=("client_caused",))

    with pytest.raises(DefinitionError, match="not a data field"):
        class Bad(Resource):
            kind = "bad_when"
            State = StepState
            Data = StepData
            initial = StepState.ACTIVE
            terminal = {StepState.COMPLETED}
            summary = "{data.title}"

            @action(from_=StepState.ACTIVE, to=StepState.COMPLETED,
                    input=BadInput,
                    safety=Safety(idempotent=True, reversible=False,
                                  confirm=True, consequence="Completes."))
            async def complete(self, inp, ctx: Ctx) -> None:
                pass


async def test_when_requires_must_be_optional():
    """A conditionally-required field that the model already always
    requires makes the condition a lie — refused at import."""
    class BadInput(BaseModel):
        date_completed: date
        client_caused: bool  # not Optional: always demanded

        late: ClassVar[When] = When(
            ("date_completed", ">", Field.of("due_date")),
            requires=("client_caused",))

    with pytest.raises(DefinitionError, match="non-optional"):
        class Bad(Resource):
            kind = "bad_when_required"
            State = StepState
            Data = StepData
            initial = StepState.ACTIVE
            terminal = {StepState.COMPLETED}
            summary = "{data.title}"

            @action(from_=StepState.ACTIVE, to=StepState.COMPLETED,
                    input=BadInput,
                    safety=Safety(idempotent=True, reversible=False,
                                  confirm=True, consequence="Completes."))
            async def complete(self, inp, ctx: Ctx) -> None:
                pass


async def test_when_declaration_invariants():
    with pytest.raises(DefinitionError, match="not one of"):
        When(("a", "!!", "b"), requires=("c",))
    with pytest.raises(DefinitionError, match="names no fields"):
        When(("a", ">", "b"), requires=())
    with pytest.raises(DefinitionError, match="judge its own consequence"):
        When(("a", ">", "b"), requires=("a",))
