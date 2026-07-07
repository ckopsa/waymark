"""The compound act (design §6): a declared multi-resource transition.
E8 declared the touches; the Compound makes the same declaration the
whole contract — every child write through the single invoker in one
transaction under one correlation id, external effects as declared
Service ops with mandatory compensators run in reverse on abort (each
attempt audited on a job resource), and the blast radius rendered on the
action entry's effect before anyone confirms it.
"""
from __future__ import annotations

import asyncio
import os
import uuid
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark4
from waymark4 import (
    Advance, Allow, Compound, Create, Ctx, DefinitionError, Deny, Each,
    Guard, Op, Owns, Ref, RefField, Registry, Resource, Safety,
    ServiceEffect, action, filterable,
)
from waymark4.core import checks
from waymark4.server.bus import InProcessBus
from waymark4.server.engine import header_principal
from waymark4.server.external import Service
from waymark4.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


# ── resources ────────────────────────────────────────────────────────────
class CounterState(StrEnum):
    OPEN = "open"


class CounterData(BaseModel):
    n: int = 0


class CCounter(Resource):
    kind = "ccounter"
    State = CounterState
    Data = CounterData
    initial = CounterState.OPEN
    terminal: set = set()
    summary = "Counter at {data.n} · {state.label}"

    @action(from_=CounterState.OPEN, to=CounterState.OPEN,
            safety=Safety(idempotent=False, reversible=True, confirm=False),
            display=dict(label="Decrement"))
    async def decrement(self, inp: None, ctx: Ctx) -> None:
        self.data.n -= 1


class EventState(StrEnum):
    OPEN = "open"


class CEventData(BaseModel):
    label: str = Field(default="", max_length=80)


class CEvent(Resource):
    kind = "cevent"
    State = EventState
    Data = CEventData
    initial = EventState.OPEN
    terminal: set = set()
    summary = "Event '{data.label}' · {state.label}"


class RowState(StrEnum):
    OPEN = "open"


class CRowData(BaseModel):
    wire_id: Ref["cwire"] = RefField()
    event_id: str | None = Field(
        default=None, max_length=64,
        json_schema_extra={"x-display": {"raw": True}})
    flag: str = Field(default="keep", max_length=16)
    locked: bool = False


class ReassignInput(BaseModel):
    event_id: str = Field(min_length=1, max_length=64,
                          json_schema_extra={"x-display": {"raw": True}})


async def _unlocked(r, inp, ctx) -> Allow | Deny:
    return Deny() if r.data.locked else Allow()


not_locked = Guard(explain="Locked rows cannot be reassigned.",
                   check=_unlocked, name="not_locked")


class CRow(Resource):
    kind = "crow"
    State = RowState
    Data = CRowData
    initial = RowState.OPEN
    terminal: set = set()
    summary = "Row ({data.flag}) · {state.label}"
    filterable = filterable(wire_id=filterable.Eq, flag=filterable.Eq,
                            state=filterable.Eq | filterable.In)

    @action(from_=RowState.OPEN, to=RowState.OPEN,
            input=ReassignInput, guards=[not_locked],
            edit=waymark4.Edit(
                prefill=("event_id",), fence=False,
                unfenced_reason="Reassignment is driven by the parent's "
                                "compound under its own transaction; there "
                                "is no human form to clobber."),
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Reassign"))
    async def reassign(self, inp: ReassignInput, ctx: Ctx) -> None:
        self.data.event_id = inp.event_id


class WireState(StrEnum):
    ACTIVE = "active"


class CWireData(BaseModel):
    total: float = 0
    counter_id: Ref["ccounter"] | None = RefField(default=None, raw=True)


class CarveInput(BaseModel):
    new_label: str = Field(min_length=1, max_length=80)


class CWire(Resource):
    kind = "cwire"
    State = WireState
    Data = CWireData
    initial = WireState.ACTIVE
    terminal: set = set()
    summary = "Wire of {data.total} · {state.label}"
    owns = (Owns("crow", via="wire_id"),)

    carve_out = Compound(
        from_=WireState.ACTIVE, to=WireState.ACTIVE,
        input=CarveInput,
        safety=Safety(idempotent=False, reversible=False, confirm=True,
                      consequence="Flagged rows move to a new event, the "
                                  "counter decrements, and the documents "
                                  "are copied."),
        creates=(Create("cevent", seed={"label": "{input.new_label}"}),),
        advances=(Each("crow", action="reassign", where={"flag": "move"},
                       input={"event_id": "{created.cevent.id}"}),
                  Advance("counter_id", action="decrement")),
        effects=(
            ServiceEffect("blob", "copy",
                          args=("docs/{id}/", "docs/{created.cevent.id}/"),
                          compensate=Op("delete",
                                        args=("docs/{created.cevent.id}/",))),
            ServiceEffect("blob", "copy",
                          args=("audit/{id}/", "audit/{created.cevent.id}/"),
                          compensate=Op("delete",
                                        args=("audit/{created.cevent.id}/",))),
            ServiceEffect("notify", "send",
                          args=("carved {input.new_label}",),
                          compensate=Op("recall",
                                        args=("carved {input.new_label}",))),
        ),
        display=dict(label="Carve out"),
    )

    snapshot = Compound(
        from_=WireState.ACTIVE, to=WireState.ACTIVE,
        safety=Safety(idempotent=False, reversible=False, confirm=True,
                      consequence="A cold copy of the documents is made "
                                  "and the hub is notified."),
        effects=(
            ServiceEffect("blob", "copy", args=("docs/{id}/", "cold/{id}/"),
                          compensate=Op("delete", args=("cold/{id}/",))),
            ServiceEffect("notify", "send", args=("snapshot {id}",),
                          compensate=Op("recall", args=("snapshot {id}",))),
        ),
        defer=True,
        display=dict(label="Snapshot"),
    )


# ── fake adapters: scriptable, order-recording ──────────────────────────
class FakeBlob:
    def __init__(self, log: list):
        self.log = log
        self.fail: set[str] = set()

    async def copy(self, src: str, dst: str) -> None:
        if "copy" in self.fail:
            raise RuntimeError("copy blew up")
        self.log.append(("blob.copy", src, dst))

    async def delete(self, path: str) -> None:
        if "delete" in self.fail:
            raise RuntimeError("delete blew up")
        self.log.append(("blob.delete", path))


class FakeNotify:
    def __init__(self, log: list):
        self.log = log
        self.fail: set[str] = set()

    async def send(self, msg: str) -> None:
        if "send" in self.fail:
            raise RuntimeError("hub rejected the message")
        self.log.append(("notify.send", msg))

    async def recall(self, msg: str) -> None:
        self.log.append(("notify.recall", msg))


class Services:
    def __init__(self):
        self.calls: list = []
        self.blob_backend = FakeBlob(self.calls)
        self.notify_backend = FakeNotify(self.calls)
        self.blob = Service("blob", handler=self.blob_backend)
        # a rejected message is not a hub outage; keep the service up so
        # the abort path exercises compensation, not backoff
        self.notify = Service("notify", handler=self.notify_backend,
                              down_on_error=False)


@pytest.fixture
async def env():
    services = Services()
    engine = waymark4.Engine(
        resources=[CWire, CRow, CEvent, CCounter], storage=TEST_DSN,
        principal=header_principal, services=services, bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t",
                         headers={"X-Principal-Id": "fern",
                                  "X-Principal-Display": "Fern"})
    try:
        yield engine, client, services
    finally:
        await client.aclose()
        await engine.shutdown()


async def _post(client, href, json=None):
    return await client.post(href, json=json,
                             headers={"Idempotency-Key": uuid.uuid4().hex})


def _id(doc) -> str:
    return doc["self"].rsplit("/", 1)[-1]


async def _setup_wire(client, *, total=100.0):
    counter = (await _post(client, "/api/ccounters", {"n": 5})).json()
    wire = (await _post(client, "/api/cwires",
                        {"total": total, "counter_id": _id(counter)})).json()
    return wire, counter


async def _row(client, wire_id, flag="move", locked=False) -> dict:
    res = await _post(client, "/api/crows",
                      {"wire_id": wire_id, "flag": flag, "locked": locked})
    assert res.status_code == 201, res.text
    return res.json()


async def _effect_jobs(client) -> list[dict]:
    jobs = (await client.get("/api/jobs")).json()["data"]["items"]
    out = []
    for j in jobs:
        full = (await client.get(j["self"])).json()
        if full["data"]["target_kind"] == "compound_effects":
            out.append(full)
    return out


# ── §6: the atomic cascade ───────────────────────────────────────────────
async def test_compound_is_one_correlated_story(env):
    """One POST: peer created from the declared seed, each flagged row
    moved through its OWN action, the Ref'd counter advanced, effects run
    — one transaction, one correlation id, and the effect audit rides the
    same correlation as a job resource."""
    engine, client, services = env
    wire, counter = await _setup_wire(client)
    r1 = await _row(client, _id(wire), flag="move")
    r2 = await _row(client, _id(wire), flag="move")
    keep = await _row(client, _id(wire), flag="keep")

    res = await _post(client, f"{wire['self']}/-/carve_out",
                      {"new_label": "Carved"})
    assert res.status_code == 200, res.text

    events = (await client.get("/api/cevents")).json()["data"]["items"]
    assert len(events) == 1
    event = (await client.get(events[0]["self"])).json()
    assert event["data"]["label"] == "Carved", \
        "the created child carries the input-templated seed"

    for r in (r1, r2):
        doc = (await client.get(r["self"])).json()
        assert doc["data"]["event_id"] == _id(event), "the flagged row moved"
    assert (await client.get(keep["self"])).json()["data"]["event_id"] \
        is None, "the unflagged row did not"
    assert (await client.get(counter["self"])).json()["data"]["n"] == 4

    assert services.calls == [
        ("blob.copy", f"docs/{_id(wire)}/", f"docs/{_id(event)}/"),
        ("blob.copy", f"audit/{_id(wire)}/", f"audit/{_id(event)}/"),
        ("notify.send", "carved Carved"),
    ], "effects executed in declaration order"

    async with engine.storage.session() as s:
        root = await engine.storage.last_transition(s, "cwire", _id(wire))
        story = await engine.storage.transitions_by_correlation(
            s, root.correlation_id)
    steps = {(t.kind, t.action) for t in story}
    assert {("cwire", "carve_out"), ("cevent", "create"),
            ("crow", "reassign"), ("ccounter", "decrement")} <= steps, \
        "the cascade is one correlated story"
    assert {("job", "create"), ("job", "start"), ("job", "finish")} <= steps, \
        "the effect audit rides the same correlation"
    resource_steps = [t for t in story if t.kind != "job"]
    assert all(t.actor_id == "fern" for t in resource_steps), \
        "every touched resource is the invoking actor's own doing"

    audits = await _effect_jobs(client)
    assert len(audits) == 1
    audit = audits[0]
    assert audit["state"] == "done"
    assert [a["status"] for a in audit["data"]["artifacts"]] \
        == ["succeeded"] * 3


async def test_child_guard_refusal_aborts_the_whole_act(env):
    """A locked row refuses reassign: the 409 names the refusing child
    action and its reason, and NOTHING landed — no event, no counter
    decrement, and no external effect even attempted (effects run after
    the resource writes)."""
    engine, client, services = env
    wire, counter = await _setup_wire(client)
    await _row(client, _id(wire), flag="move", locked=True)

    res = await _post(client, f"{wire['self']}/-/carve_out",
                      {"new_label": "Carved"})
    assert res.status_code == 409, res.text
    problem = res.json()
    assert problem["action_attempted"] == "reassign", \
        "the refusal says WHICH child refused"
    assert problem["detail"] == "Locked rows cannot be reassigned."

    assert (await client.get("/api/cevents")).json()["data"]["total"] == 0
    assert (await client.get(counter["self"])).json()["data"]["n"] == 5
    assert services.calls == [], "no effect ran before the abort"


async def test_compensators_run_in_reverse_order_and_are_audited(env):
    """The third effect fails: the two executed blob copies are
    compensated newest-first, the resource writes roll back, and every
    attempt — effects and compensations — lands on a job-resource audit."""
    engine, client, services = env
    services.notify_backend.fail.add("send")
    wire, counter = await _setup_wire(client)
    await _row(client, _id(wire), flag="move")

    res = await _post(client, f"{wire['self']}/-/carve_out",
                      {"new_label": "Doomed"})
    assert res.status_code == 502, res.text
    assert res.json()["effect"] == "notify.send"

    ops = [c[0] for c in services.calls]
    assert ops == ["blob.copy", "blob.copy", "blob.delete", "blob.delete"], \
        "compensators ran, in reverse, only for executed effects"
    assert services.calls[2][1].startswith("audit/"), \
        "the LAST executed effect compensates FIRST"
    assert services.calls[3][1].startswith("docs/")

    # the act itself left nothing behind
    assert (await client.get("/api/cevents")).json()["data"]["total"] == 0
    assert (await client.get(counter["self"])).json()["data"]["n"] == 5
    row_docs = (await client.get("/api/crows")).json()["data"]["items"]
    full = (await client.get(row_docs[0]["self"])).json()
    assert full["data"]["event_id"] is None, "the reassignment rolled back"

    audits = await _effect_jobs(client)
    assert len(audits) == 1
    artifacts = audits[0]["data"]["artifacts"]
    assert [(a["name"], a["status"]) for a in artifacts] == [
        ("blob.copy", "succeeded"),
        ("blob.copy", "succeeded"),
        ("notify.send", "failed"),
        ("compensate:blob.delete", "succeeded"),
        ("compensate:blob.delete", "succeeded"),
    ]
    assert "hub rejected" in artifacts[2]["message"]


async def test_blast_radius_renders_on_the_effect(env):
    """What E8 enforced, §6 advertises: the action entry's effect carries
    creates/advances/effects summaries AND the compiled touches — one
    declaration, both faces."""
    engine, client, services = env
    wire, _ = await _setup_wire(client)
    doc = (await client.get(wire["self"])).json()
    effect = doc["actions"]["carve_out"]["effect"]
    assert effect["creates"] == ["cevent"]
    assert effect["advances"] == ["crow.reassign", "ccounter.decrement"]
    assert effect["effects"] == ["blob.copy", "blob.copy", "notify.send"]
    assert effect["touches"] == [
        {"creates": "cevent"}, {"advances": "crow.reassign"},
        {"advances": "ccounter.decrement"}]
    snap = doc["actions"]["snapshot"]["effect"]
    assert snap["effects"] == ["blob.copy", "notify.send"]
    assert snap["deferred"] is True


async def _job_done(client, job_self, timeout=5.0) -> dict:
    deadline = asyncio.get_event_loop().time() + timeout
    while True:
        doc = (await client.get(job_self)).json()
        if doc["state"] == "done":
            return doc
        if asyncio.get_event_loop().time() > deadline:
            raise AssertionError(f"job never finished: {doc}")
        await asyncio.sleep(0.05)


async def test_deferred_compound_runs_effects_on_the_job_executor(env):
    """defer=True: the resource write commits immediately; effects run
    post-commit on the E6 job kind, per-effect artifacts and all."""
    engine, client, services = env
    wire, _ = await _setup_wire(client)

    res = await _post(client, f"{wire['self']}/-/snapshot")
    assert res.status_code == 200, res.text

    audits = await _effect_jobs(client)
    assert len(audits) == 1
    job = await _job_done(client, audits[0]["self"])
    assert job["data"]["action"] == "cwire.snapshot"
    assert [a["status"] for a in job["data"]["artifacts"]] \
        == ["succeeded"] * 2
    assert ("blob.copy", f"docs/{_id(wire)}/", f"cold/{_id(wire)}/") \
        in services.calls
    assert ("notify.send", f"snapshot {_id(wire)}") in services.calls


async def test_deferred_failure_compensates_executed_effects(env):
    """A deferred effect fails after an earlier one executed: the
    committed resource writes stand (that is what defer means), and the
    executed effects are compensated on the job, audited as artifacts."""
    engine, client, services = env
    services.notify_backend.fail.add("send")
    wire, _ = await _setup_wire(client)
    version_before = (await client.get(wire["self"])).json()["meta"]["version"]

    res = await _post(client, f"{wire['self']}/-/snapshot")
    assert res.status_code == 200, res.text
    assert (await client.get(wire["self"])).json()["meta"]["version"] \
        == version_before + 1, "the resource write stands"

    audits = await _effect_jobs(client)
    job = await _job_done(client, audits[0]["self"])
    assert [(a["name"], a["status"]) for a in job["data"]["artifacts"]] == [
        ("blob.copy", "succeeded"),
        ("notify.send", "failed"),
        ("compensate:blob.delete", "succeeded"),
    ]
    assert ("blob.delete", f"cold/{_id(wire)}/") in services.calls


# ── import-time / assembly checks ────────────────────────────────────────
async def test_advance_must_name_a_ref_field(env):
    with pytest.raises(DefinitionError, match="Advance"):
        class BadWire(Resource):
            kind = "badwire"
            State = WireState
            Data = CWireData
            initial = WireState.ACTIVE
            terminal: set = set()
            summary = "bad"

            bad = Compound(
                from_=WireState.ACTIVE, to=WireState.ACTIVE,
                safety=Safety(idempotent=True, reversible=True,
                              confirm=False),
                advances=(Advance("total", action="decrement"),))


async def test_each_requires_an_owns_edge(env):
    with pytest.raises(DefinitionError, match="Owns"):
        class EdgelessWire(Resource):
            kind = "edgeless"
            State = WireState
            Data = CWireData
            initial = WireState.ACTIVE
            terminal: set = set()
            summary = "bad"

            bad = Compound(
                from_=WireState.ACTIVE, to=WireState.ACTIVE,
                safety=Safety(idempotent=True, reversible=True,
                              confirm=False),
                advances=(Each("crow", action="reassign"),))


async def test_unknown_child_action_fails_the_touch_check(env):
    class NopeWire(Resource):
        kind = "nopewire"
        State = WireState
        Data = CWireData
        initial = WireState.ACTIVE
        terminal: set = set()
        summary = "Wire of {data.total}"
        owns = (Owns("crow", via="wire_id"),)

        bad = Compound(
            from_=WireState.ACTIVE, to=WireState.ACTIVE,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            advances=(Each("crow", action="nope"),))

    reg = Registry()
    reg.register(NopeWire)
    reg.register(CRow)
    reg.register(CEvent)
    reg.register(CCounter)
    with pytest.raises(DefinitionError, match="nope"):
        checks.check_touches(reg)


def _registry() -> Registry:
    reg = Registry()
    for cls in (CWire, CRow, CEvent, CCounter):
        reg.register(cls)
    return reg


async def test_effect_must_name_a_declared_service_and_op(env):
    reg = _registry()
    with pytest.raises(DefinitionError, match="not a declared Service"):
        checks.check_compounds(reg, None)

    class HalfServices:
        def __init__(self):
            self.blob = Service("blob", handler=FakeBlob([]))

    with pytest.raises(DefinitionError, match="notify"):
        checks.check_compounds(reg, HalfServices())

    class OplessBlob:
        async def copy(self, src, dst): ...
        # no delete — the compensator has nowhere to land

    class OplessServices(Services):
        def __init__(self):
            super().__init__()
            self.blob = Service("blob", handler=OplessBlob())

    with pytest.raises(DefinitionError, match="delete"):
        checks.check_compounds(reg, OplessServices())

    checks.check_compounds(reg, Services())  # the honest set passes


async def test_each_where_must_be_filterable(env):
    class WhereWire(Resource):
        kind = "wherewire"
        State = WireState
        Data = CWireData
        initial = WireState.ACTIVE
        terminal: set = set()
        summary = "Wire of {data.total}"
        owns = (Owns("crow", via="wire_id"),)

        bad = Compound(
            from_=WireState.ACTIVE, to=WireState.ACTIVE,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            advances=(Each("crow", action="reassign",
                           where={"locked": False},
                           input={"event_id": "x"}),))

    reg = Registry()
    for cls in (WhereWire, CRow, CEvent, CCounter):
        reg.register(cls)
    with pytest.raises(DefinitionError, match="filterable"):
        checks.check_compounds(reg, Services())


async def test_compensator_is_mandatory(env):
    with pytest.raises(DefinitionError, match="compensate"):
        ServiceEffect("blob", "copy", args=("a", "b"))


async def test_create_seed_fields_are_checked(env):
    class SeedWire(Resource):
        kind = "seedwire"
        State = WireState
        Data = CWireData
        initial = WireState.ACTIVE
        terminal: set = set()
        summary = "Wire of {data.total}"

        bad = Compound(
            from_=WireState.ACTIVE, to=WireState.ACTIVE,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            creates=(Create("cevent", seed={"nonesuch": 1}),))

    reg = Registry()
    for cls in (SeedWire, CRow, CEvent, CCounter):
        reg.register(cls)
    with pytest.raises(DefinitionError, match="nonesuch"):
        checks.check_compounds(reg, Services())


async def test_compound_touches_are_exactly_the_declaration(env):
    """E8's enforcement holds for compounds by construction: the compiled
    touch set IS the declaration, so the handler cannot out-run it."""
    from waymark4 import Advances, Creates

    defn = CWire.__waymark_machine__.actions["carve_out"]
    assert defn.touches == (Creates("cevent"),
                            Advances("crow", "reassign"),
                            Advances("ccounter", "decrement"))
    assert defn.compound is CWire.carve_out
