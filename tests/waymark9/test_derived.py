"""Derived fields (design §1, §2): one definition, every consumer.

A derivation is a named, typed Data field — a pure function of declared
inputs (own fields, owned children via the ``Owns`` edge, a tolerance) —
materialized by the engine at every write that can change it and read by
render, filter, sort, the schema, and the bus from the one name. The
scenario is the payouts mapping's: a wire whose facts
(``open_ifts``, ``all_ifts_terminal``, ``reconciled``) were recomputed
per screen in v3 and are fields here.
"""
from __future__ import annotations

import asyncio
import json
import os
import socket
import uuid
from enum import StrEnum

import pytest
import uvicorn
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark9
from waymark9 import (
    Count,
    Ctx,
    DefinitionError,
    Derived,
    Owns,
    Ref,
    RefField,
    Registry,
    Resource,
    Safety,
    Sum,
    Tolerance,
    action,
    filterable,
    sortable,
)
from waymark9.core import checks
from waymark9.server.bus import InProcessBus
from waymark9.server.engine import header_principal
from waymark9.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

OWNER = {"X-Principal-Id": "sam", "X-Principal-Display": "Sam"}

TERMINAL = ("settled", "returned")

ifts = Owns("ift", via="wire_id")


class IftState(StrEnum):
    OPEN = "open"
    SETTLED = "settled"
    RETURNED = "returned"


class IftData(BaseModel):
    wire_id: Ref["wire"] = RefField(min_length=1)
    amount: float = Field(default=0.0, ge=0)


class ReassignInput(BaseModel):
    # named for what it is (the destination), so the action is honestly
    # not an edit of the stored ref
    to_wire_id: Ref["wire"] = RefField(min_length=1)


class Ift(Resource):
    kind = "ift"
    State = IftState
    Data = IftData
    initial = IftState.OPEN
    terminal = {IftState.SETTLED, IftState.RETURNED}
    summary = "transfer {data.amount} · {state.label}"
    filterable = filterable(state=filterable.Eq | filterable.In,
                            wire_id=filterable.Eq,
                            amount=filterable.Range)

    @action(from_=IftState.OPEN, to=IftState.SETTLED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Marks the transfer settled."))
    async def settle(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=IftState.OPEN, to=IftState.RETURNED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Returns the transfer to sender."))
    async def send_back(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=IftState.OPEN, to=IftState.OPEN, input=ReassignInput,
            safety=Safety(idempotent=True, reversible=False, confirm=False))
    async def reassign(self, inp: ReassignInput, ctx: Ctx) -> None:
        self.data.wire_id = inp.to_wire_id


class WireState(StrEnum):
    ACTIVE = "active"
    DONE = "done"


class WireData(BaseModel):
    amount: float = Field(default=0.0, ge=0)
    # E4's rollups as library derivations over the edge (design §10)
    open_ifts: int = Count(ifts, where={"state": ("open",)})
    total_settled: float = Sum(ifts, "amount", where={"state": ("settled",)})
    # the general thing the rollup pair only sighted: any pure fn over
    # a declared child field
    all_ifts_terminal: bool = Derived(
        over=(ifts.field("state"),),
        fn=lambda states: all(s in TERMINAL for s in states),
        explain="{open} transfer(s) are still open.",
        vars=lambda states: {"open": sum(s not in TERMINAL for s in states)})
    # a derivation over a derivation, and THE tolerance, stated once
    difference: float = Derived(over=("amount", "total_settled"),
                                fn=lambda a, t: a - t)
    reconciled: bool = Derived(over=("difference",),
                               within=Tolerance("0.00001"))


class AdjustInput(BaseModel):
    new_amount: float = Field(ge=0)


class Wire(Resource):
    kind = "wire"
    State = WireState
    Data = WireData
    initial = WireState.ACTIVE
    terminal = {WireState.DONE}
    summary = "wire {data.amount} · {state.label}"
    owns = (ifts,)
    filterable = filterable(state=filterable.Eq,
                            reconciled=filterable.Eq,
                            open_ifts=filterable.Eq | filterable.Range)
    sortable = sortable("open_ifts", default="-open_ifts")

    @action(from_=WireState.ACTIVE, to=WireState.ACTIVE, input=AdjustInput,
            safety=Safety(idempotent=True, reversible=False, confirm=False))
    async def adjust(self, inp: AdjustInput, ctx: Ctx) -> None:
        self.data.amount = inp.new_amount

    @action(from_=WireState.ACTIVE, to=WireState.ACTIVE,
            safety=Safety(idempotent=True, reversible=False, confirm=False))
    async def tamper(self, inp: None, ctx: Ctx) -> None:
        self.data.reconciled = True  # a handler may not author a derivation

    @action(from_=WireState.ACTIVE, to=WireState.DONE,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Closes the wire."))
    async def complete(self, inp: None, ctx: Ctx) -> None:
        pass


@pytest.fixture
async def env():
    engine = waymark9.Engine(resources=[Wire, Ift], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t", headers=OWNER)
    try:
        yield engine, client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _post(client, href, json=None):
    return await client.post(href, json=json,
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def _wire(client, amount=40.0) -> dict:
    res = await _post(client, "/api/wires", {"amount": amount})
    assert res.status_code == 201, res.text
    return res.json()


async def _ift(client, wire_id, amount) -> dict:
    res = await _post(client, "/api/ifts",
                      {"wire_id": wire_id, "amount": amount})
    assert res.status_code == 201, res.text
    return res.json()


def _id(doc) -> str:
    return doc["self"].rsplit("/", 1)[-1]


async def assert_conform(engine, kind: str, id: str) -> None:
    """The §2 conformance proof: recompute-from-inputs == stored value."""
    rdef = engine.registry[kind]
    async with engine.storage.session() as s:
        instance = await engine.storage.load(s, kind, id)
        fresh = await engine.invoker.derived.compute(
            s, instance, rdef, now=engine.invoker.clock())
    stored = instance.data.model_dump()
    for field, value in fresh.items():
        assert stored[field] == value, \
            f"{kind}.{field}: stored {stored[field]!r} != derived {value!r}"


# ── materialization: every write that can change an input ───────────────

async def test_derived_values_materialize_at_create(env):
    engine, client = env
    wire = await _wire(client, amount=40.0)
    assert wire["data"]["open_ifts"] == 0
    assert wire["data"]["total_settled"] == 0
    assert wire["data"]["all_ifts_terminal"] is True  # vacuously
    assert wire["data"]["difference"] == 40.0
    assert wire["data"]["reconciled"] is False
    await assert_conform(engine, "wire", _id(wire))


async def test_child_writes_recompute_the_parents_facts(env):
    """The invalidation map is declared (design §2): a child create and
    every child transition recompute the owner in the same commit."""
    engine, client = env
    wire = await _wire(client, amount=40.0)
    a = await _ift(client, _id(wire), 25.0)
    b = await _ift(client, _id(wire), 15.0)

    doc = (await client.get(wire["self"])).json()
    assert doc["data"]["open_ifts"] == 2
    assert doc["data"]["all_ifts_terminal"] is False

    await _post(client, f"{a['self']}/-/settle")
    doc = (await client.get(wire["self"])).json()
    assert doc["data"]["open_ifts"] == 1
    assert doc["data"]["total_settled"] == 25.0
    assert doc["data"]["difference"] == 15.0
    assert doc["data"]["reconciled"] is False

    await _post(client, f"{b['self']}/-/settle")
    doc = (await client.get(wire["self"])).json()
    assert doc["data"]["open_ifts"] == 0
    assert doc["data"]["difference"] == 0.0
    assert doc["data"]["reconciled"] is True, \
        "one Tolerance, stated once, judged where the data lives"
    assert doc["data"]["all_ifts_terminal"] is True
    await assert_conform(engine, "wire", _id(wire))


async def test_own_field_writes_recompute_in_the_same_transition(env):
    engine, client = env
    wire = await _wire(client, amount=40.0)
    a = await _ift(client, _id(wire), 25.0)
    await _post(client, f"{a['self']}/-/settle")

    res = await _post(client, f"{wire['self']}/-/adjust", {"new_amount": 25.0})
    assert res.status_code == 200, res.text
    doc = res.json()  # the POST response already carries the fresh facts
    assert doc["data"]["difference"] == 0.0
    assert doc["data"]["reconciled"] is True
    await assert_conform(engine, "wire", _id(wire))


async def test_reassigning_the_via_ref_recomputes_both_parents(env):
    engine, client = env
    wire_a = await _wire(client, amount=10.0)
    wire_b = await _wire(client, amount=10.0)
    ift = await _ift(client, _id(wire_a), 10.0)

    res = await _post(client, f"{ift['self']}/-/reassign",
                      {"to_wire_id": _id(wire_b)})
    assert res.status_code == 200, res.text
    assert (await client.get(wire_a["self"])).json()["data"]["open_ifts"] == 0
    assert (await client.get(wire_b["self"])).json()["data"]["open_ifts"] == 1
    await assert_conform(engine, "wire", _id(wire_a))
    await assert_conform(engine, "wire", _id(wire_b))


async def test_conformance_walk_recompute_equals_stored(env):
    """Walk transitions that dirty every input shape (own field, child
    create, child transition, ref move) and hold the stored value to the
    recomputed one at each step — the 2.0 closure rule, applied to facts."""
    engine, client = env
    wire = await _wire(client, amount=40.0)
    other = await _wire(client, amount=5.0)
    wid = _id(wire)

    steps = []
    a = await _ift(client, wid, 25.0)
    steps.append(None)
    b = await _ift(client, wid, 20.0)
    steps.append(None)
    await assert_conform(engine, "wire", wid)

    for href, action_name, body in [
        (a["self"], "settle", None),
        (b["self"], "reassign", {"to_wire_id": _id(other)}),
        (b["self"], "send_back", None),
        (wire["self"], "adjust", {"new_amount": 25.0}),
    ]:
        res = await _post(client, f"{href}/-/{action_name}", body)
        assert res.status_code == 200, res.text
        await assert_conform(engine, "wire", wid)
        await assert_conform(engine, "wire", _id(other))


# ── filter / sort / schema: the other consumers of the one name ─────────

async def test_derived_fields_filter_and_sort_as_promoted_columns(env):
    engine, client = env
    reconciled = await _wire(client, amount=0.0)
    open_one = await _wire(client, amount=30.0)
    await _ift(client, _id(open_one), 30.0)

    listing = (await client.get("/api/wires?reconciled=true")).json()
    assert [i["self"] for i in listing["data"]["items"]] \
        == [reconciled["self"]]

    listing = (await client.get("/api/wires?open_ifts_gte=1")).json()
    assert [i["self"] for i in listing["data"]["items"]] == [open_one["self"]]

    listing = (await client.get("/api/wires?sort=-open_ifts")).json()
    assert [i["self"] for i in listing["data"]["items"]] \
        == [open_one["self"], reconciled["self"]]


async def test_schema_marks_derived_fields_readonly(env):
    engine, client = env
    res = await client.get("/api/schemas/wire")
    props = res.json()["properties"]
    for field in ("open_ifts", "total_settled", "all_ifts_terminal",
                  "difference", "reconciled"):
        assert props[field]["readOnly"] is True, field
        assert props[field]["x-source"] == "derived", field
    assert "readOnly" not in props["amount"], "written fields stay writable"
    # the create surface carries the same origin marks
    create_props = engine.registry["wire"].extra["create_schema"]["properties"]
    assert create_props["reconciled"]["readOnly"] is True


# ── one origin per field: every other writer is refused ─────────────────

async def test_create_body_supplying_a_derived_field_is_refused(env):
    engine, client = env
    res = await _post(client, "/api/wires",
                      {"amount": 4.0, "reconciled": True})
    assert res.status_code == 422
    assert "derived" in json.dumps(res.json()["errors"]["reconciled"])


async def test_handler_assigning_a_derived_field_is_refused(env):
    from waymark9.core.types import Principal

    engine, client = env
    wire = await _wire(client)
    with pytest.raises(DefinitionError, match="derived field 'reconciled'"):
        await engine.invoker.invoke(
            "wire", _id(wire), "tamper", None,
            principal=Principal(id="sam", display="Sam"))
    # the refused write rolled back whole
    assert (await client.get(wire["self"])).json()["data"]["reconciled"] \
        is False


# ── derivation events (design §3): flips announced with their cause ─────

async def test_transition_caused_flips_emit_derivation_events(env):
    engine, client = env
    wire = await _wire(client, amount=25.0)
    ift = await _ift(client, _id(wire), 25.0)

    sub = engine.dispatcher.subscribe(kinds=frozenset({"wire"}),
                                      classes=frozenset({"derivation"}))
    res = await _post(client, f"{ift['self']}/-/settle")
    assert res.status_code == 200

    events = []
    while not sub.queue.empty():
        events.append(sub.queue.get_nowait())
    facts = {e["fact"]: e for e in events}
    assert set(facts) == {"open_ifts", "total_settled", "difference",
                          "reconciled", "all_ifts_terminal"}

    async with engine.storage.session() as s:
        settle = await engine.storage.last_transition(s, "ift", _id(ift))
    flip = facts["reconciled"]
    assert flip["class"] == "derivation"
    assert flip["kind"] == "wire"
    assert flip["self"] == wire["self"]
    assert flip["from"] is False and flip["to"] is True
    assert flip["at"]
    assert flip["cause"] == settle.id, \
        "the flip is anchored to the transition that dirtied its inputs"
    assert facts["open_ifts"]["from"] == 1 and facts["open_ifts"]["to"] == 0


async def test_unchanged_recomputes_emit_nothing(env):
    engine, client = env
    wire = await _wire(client, amount=40.0)
    sub = engine.dispatcher.subscribe(kinds=frozenset({"wire"}),
                                      classes=frozenset({"derivation"}))
    # adjust to the same value: inputs rewritten, no fact changed
    await _post(client, f"{wire['self']}/-/adjust", {"new_amount": 40.0})
    assert sub.queue.empty(), "an event announces a CHANGE, not a write"


async def test_aborted_writes_announce_nothing(env):
    from waymark9.core.types import Principal

    engine, client = env
    wire = await _wire(client, amount=25.0)  # unreconciled: tamper changes it

    sub = engine.dispatcher.subscribe(kinds=frozenset({"wire"}),
                                      classes=frozenset({"derivation"}))
    with pytest.raises(DefinitionError):
        await engine.invoker.invoke(
            "wire", _id(wire), "tamper", None,
            principal=Principal(id="sam", display="Sam"))
    assert sub.queue.empty(), "a rolled-back write announces nothing"


async def test_engine_without_clock_derivations_starts_no_sweep(env):
    engine, _ = env
    assert engine._tick_task is None


# ── declaration honesty: import-time and assembly-time refusals ──────────

def test_action_input_naming_a_derived_field_is_a_definition_error():
    class BadInput(BaseModel):
        reconciled: bool

    with pytest.raises(DefinitionError, match="derived"):
        class BadWire(Wire):
            kind = "wire"

            @action(from_=WireState.ACTIVE, to=WireState.ACTIVE,
                    input=BadInput,
                    safety=Safety(idempotent=True, reversible=False,
                                  confirm=False))
            async def poke(self, inp: BadInput, ctx: Ctx) -> None:
                pass


def test_derived_declaration_shapes_are_checked():
    with pytest.raises(DefinitionError):
        Derived(over=("x",))  # neither fn nor within
    with pytest.raises(DefinitionError):
        Derived(over=("x",), fn=lambda x: x, within=Tolerance("0.1"))
    with pytest.raises(DefinitionError):
        Derived(over=("x", "y"), within=Tolerance("0.1"))  # within is unary
    with pytest.raises(DefinitionError):
        Derived(over=(), fn=lambda: 1)
    with pytest.raises(DefinitionError):
        Tolerance("not-a-decimal")


def test_unknown_inputs_and_cycles_are_definition_errors():
    with pytest.raises(DefinitionError, match="not a Data field"):
        class D1(BaseModel):
            x: int = Derived(over=("nope",), fn=lambda v: v)

        class R1(Wire):
            kind = "wire"
            Data = D1

    with pytest.raises(DefinitionError, match="cycle"):
        class D2(BaseModel):
            a: int = Derived(over=("b",), fn=lambda b: b)
            b: int = Derived(over=("a",), fn=lambda a: a)

        class R2(Wire):
            kind = "wire"
            Data = D2


def test_check_derived_edges_rejects_bad_child_inputs():
    """Assembly-time honesty (the check_owns discipline): every way an
    owned-children input can lie is a DefinitionError naming the fix."""
    def registry_with(data_cls, owns=()):
        class W(Wire):
            kind = "wire"
            Data = data_cls
            filterable = filterable(state=filterable.Eq)
            sortable = None
        W.owns = tuple(owns)
        reg = Registry()
        reg.register(W)
        reg.register(Ift)
        return reg

    class NoEdge(BaseModel):
        n: int = Count(Owns("ift", via="wire_id"))

    with pytest.raises(DefinitionError, match="no matching Owns edge"):
        checks.check_derived_edges(registry_with(NoEdge))

    class BadField(BaseModel):
        n: float = Sum(ifts, "nope")

    with pytest.raises(DefinitionError, match="not a data field"):
        checks.check_derived_edges(registry_with(BadField, owns=(ifts,)))

    class BadWhere(BaseModel):
        n: int = Count(ifts, where={"nope": 1})

    with pytest.raises(DefinitionError, match="not filterable"):
        checks.check_derived_edges(registry_with(BadWhere, owns=(ifts,)))


# ── the wire surface, end to end over a real socket ──────────────────────

def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


async def sse_events(response):
    current = {}
    async for line in response.aiter_lines():
        line = line.strip()
        if not line:
            if "data" in current:
                yield current
            current = {}
        elif ":" in line:
            key, _, value = line.partition(":")
            current[key.strip()] = value.strip()


async def test_derivation_events_reach_sse_subscribers():
    """The dispatcher path is the production one (Postgres bus): flips
    reach the resource stream framed ``event: derivation`` with no id —
    replay is recomputation, not a stream cursor — and ``?classes=``
    filters by class."""
    engine = waymark9.Engine(resources=[Wire, Ift], storage=TEST_DSN,
                             principal=header_principal, services=None)
    await engine.storage.drop_all()
    app = FastAPI(lifespan=engine.lifespan)
    app.include_router(engine.router, prefix="/api")
    port = free_port()
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port,
                                           log_level="error"))
    task = asyncio.create_task(server.serve())
    while not server.started:
        await asyncio.sleep(0.05)
    client = AsyncClient(base_url=f"http://127.0.0.1:{port}", headers=OWNER)
    try:
        wire = await _wire(client, amount=25.0)
        ift = await _ift(client, _id(wire), 25.0)

        async def act():
            await asyncio.sleep(0.2)
            res = await _post(client, f"{ift['self']}/-/settle")
            assert res.status_code == 200

        # ?classes=derivation: the settle transition itself is filtered out
        async with client.stream(
                "GET", wire["self"] + "/-/events?classes=derivation",
                timeout=15) as response:
            assert response.status_code == 200
            worker = asyncio.create_task(act())

            async def first_event():
                async for candidate in sse_events(response):
                    return candidate

            event = await asyncio.wait_for(first_event(), timeout=10)
            await worker

        assert event["event"] == "derivation"
        assert "id" not in event, "nothing to resume: replay is recomputation"
        payload = json.loads(event["data"])
        assert payload["class"] == "derivation"
        assert payload["kind"] == "wire"
        assert payload["self"] == wire["self"]
        assert payload["fact"] in {"open_ifts", "total_settled", "difference",
                                   "reconciled", "all_ifts_terminal"}
        assert {"from", "to", "at", "cause"} <= set(payload)
    finally:
        await client.aclose()
        server.should_exit = True
        await task
