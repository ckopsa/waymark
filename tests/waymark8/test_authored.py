"""Authority is per-field (design §8): ``Authored(by=...)`` fields are
read-only inside the boundary and written only by the authority's sync
path (the Mirror adapter protocol, scoped to the authored subset);
external changes arrive as system-actor transitions; ``follows=`` maps an
incoming value to a declared transition through the single invoker; a
conflict over authored fields never blocks writes to written fields; and
a declared ``Discover`` sweep mints new resources on the clock tick.
"""
from __future__ import annotations

import os
import uuid
from datetime import UTC, datetime, timedelta
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark8
from waymark8 import Authored, Ctx, Resource, Safety, action
from waymark8.core.guards import Guard
from waymark8.core.types import Acknowledged, Allow, DefinitionError, Deny
from waymark8.server.bus import InProcessBus
from waymark8.server.engine import header_principal
from waymark8.server.external import AuthoredMeta, Discover
from waymark8.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

OWNER = {"X-Principal-Id": "dana", "X-Principal-Display": "Dana"}


# ── a fake CRM (the external authority) ─────────────────────────────────
class FakeCRM:
    def __init__(self):
        self.docs: dict[str, dict] = {}
        self.etags: dict[str, str] = {}
        self.down = False
        self.pulls = 0
        self.discoverable: list[str] = []

    def seed(self, id: str, doc: dict) -> None:
        self.docs[id] = doc
        self.etags[id] = uuid.uuid4().hex

    async def pull(self, external_id: str):
        self.pulls += 1
        if self.down:
            raise ConnectionError("crm unreachable")
        return dict(self.docs[external_id]), self.etags[external_id]

    async def discover(self):
        if self.down:
            raise ConnectionError("crm unreachable")
        return list(self.discoverable)


CRM = FakeCRM()


class EntityState(StrEnum):
    ONBOARDING = "onboarding"
    ACTIVE = "active"
    SUSPENDED = "suspended"


class EntityData(AuthoredMeta):
    # HubSpot's fields: externally owned, read-only inside the boundary
    name: str | None = Authored(by="hubspot", default=None, max_length=80)
    stage: str | None = Authored(by="hubspot", default=None, max_length=40,
                                 follows={"closedwon": "activate",
                                          "closedlost": "suspend"})
    # local, writable — one entity to the user (design §8)
    crosswalk_id: str | None = Field(default=None, max_length=40)


class CrosswalkInput(BaseModel):
    crosswalk_id: str = Field(min_length=1, max_length=40)


async def _has_crosswalk(r, inp, ctx: Ctx) -> Allow | Deny:
    return Allow() if r.data.crosswalk_id else Deny()


crosswalk_set = Guard(
    name="crosswalk_set",
    explain="Link a crosswalk id before activating.",
    check=_has_crosswalk,
)


class Entity(Resource):
    kind = "entity"
    State = EntityState
    Data = EntityData
    initial = EntityState.ONBOARDING
    terminal = {EntityState.SUSPENDED}
    summary = "{data.name} · {state.label}"

    adapter = CRM
    ttl_seconds = 0  # tests drive the authority's changes read-by-read
    discover = Discover(every=600)

    filterable = waymark8.filterable(
        state=waymark8.filterable.Eq | waymark8.filterable.In,
        external_id=waymark8.filterable.Eq,
    )

    @action(from_=EntityState.ONBOARDING, to=EntityState.ONBOARDING,
            input=CrosswalkInput,
            edit=waymark8.Edit(prefill=("crosswalk_id",), fence=False,
                               unfenced_reason="Test fixture: the sync "
                                               "path bumps versions under "
                                               "the form."),
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Set crosswalk"))
    async def set_crosswalk(self, inp: CrosswalkInput, ctx: Ctx) -> None:
        self.data.crosswalk_id = inp.crosswalk_id

    @action(from_=EntityState.ONBOARDING, to=EntityState.ACTIVE,
            guards=[crosswalk_set],
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Activation mirrors the closed deal; the log "
                              "records who and when.")),
            display=dict(label="Activate"))
    async def activate(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_={EntityState.ONBOARDING, EntityState.ACTIVE},
            to=EntityState.SUSPENDED,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Suspension mirrors the lost deal; the log "
                              "records who and when.")),
            display=dict(label="Suspend"))
    async def suspend(self, inp: None, ctx: Ctx) -> None:
        pass


@pytest.fixture
async def env():
    CRM.docs.clear()
    CRM.etags.clear()
    CRM.down = False
    CRM.pulls = 0
    CRM.discoverable = []
    engine = waymark8.Engine(resources=[Entity], storage=TEST_DSN,
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


async def _entity(client, external_id="crm-1", crosswalk=None) -> dict:
    CRM.seed(external_id, {"name": "Acme", "stage": "qualified"})
    body = {"external_id": external_id}
    if crosswalk:
        body["crosswalk_id"] = crosswalk
    res = await client.post("/api/entitys", json=body,
                            headers={**OWNER,
                                     "Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    return res.json()


# ── the origin is exclusive: refused at every write surface ────────────
async def test_action_input_naming_authored_field_is_definition_error():
    class OneState(StrEnum):
        OPEN = "open"

    class SoloData(AuthoredMeta):
        name: str | None = Authored(by="hubspot", default=None, max_length=80)

    class BadInput(BaseModel):
        name: str = Field(max_length=80)

    with pytest.raises(DefinitionError,
                       match="input model names authored"):
        class Broken(Resource):
            kind = "broken_entity"
            State = OneState
            Data = SoloData
            initial = OneState.OPEN
            summary = "Broken · {state.label}"

            @action(from_=OneState.OPEN, to=OneState.OPEN, input=BadInput,
                    safety=Safety(idempotent=True, reversible=True,
                                  confirm=False))
            async def rename(self, inp, ctx) -> None:
                pass


async def test_follows_naming_unknown_transition_is_definition_error():
    class OneState(StrEnum):
        OPEN = "open"

    with pytest.raises(DefinitionError, match="follows"):
        class BrokenFollows(Resource):
            kind = "broken_follows"
            State = OneState

            class Data(AuthoredMeta):
                stage: str | None = Authored(by="hubspot", default=None,
                                             max_length=40,
                                             follows={"closedwon": "nope"})

            initial = OneState.OPEN
            summary = "Broken · {state.label}"


async def test_create_body_supplying_authored_field_is_refused(env):
    engine, client = env
    res = await client.post(
        "/api/entitys", json={"external_id": "crm-9", "name": "Sneaky"},
        headers={**OWNER, "Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 422, res.text
    problem = res.json()
    assert problem["errors"]["name"] == [
        "read-only: authored — the hubspot authority writes this value"]


async def test_schema_marks_authored_fields(env):
    engine, client = env
    schema = (await client.get("/api/schemas/entity")).json()
    prop = schema["properties"]["name"]
    assert prop["readOnly"] is True
    assert prop["x-source"] == "authored"
    # the authority is named on the wire (design §8): a client shows WHO
    # owns the value, not merely that somebody external does
    assert prop["x-authority"] == "hubspot"
    assert "x-source" not in schema["properties"]["crosswalk_id"]
    assert "x-authority" not in schema["properties"]["crosswalk_id"]


async def test_handler_assigning_authored_field_is_refused(env):
    engine, client = env
    doc = await _entity(client)
    id = doc["self"].rsplit("/", 1)[-1]

    # swap in a tampering handler on the declared action: the engine
    # refuses the write regardless of what the code does (design §8)
    defn = Entity.__waymark_machine__.actions["set_crosswalk"]
    real = defn.handler

    async def tamper(self, inp, ctx):
        self.data.name = "Tampered"

    object.__setattr__(defn, "handler", tamper)
    try:
        with pytest.raises(DefinitionError, match="authored"):
            await engine.invoker.invoke(
                "entity", id, "set_crosswalk", {"crosswalk_id": "X-1"},
                principal=waymark8.Principal(id="dana"),
                idempotency_key=uuid.uuid4().hex)
    finally:
        object.__setattr__(defn, "handler", real)


# ── the sync path: authored subset only, system-actor transitions ───────
async def test_sync_updates_authored_fields_only(env):
    engine, client = env
    doc = await _entity(client, crosswalk="XW-7")
    id = doc["self"].rsplit("/", 1)[-1]
    assert doc["data"]["name"] is None, "authored values await the authority"

    got = (await client.get(doc["self"])).json()  # TTL: synced_at unset → pull
    assert got["data"]["name"] == "Acme"
    assert got["data"]["stage"] == "qualified"
    assert got["data"]["crosswalk_id"] == "XW-7", "written field untouched"
    assert got["data"]["sync_state"] == "fresh"
    assert got["data"]["synced_at"] is not None
    assert got["state"] == "onboarding", "the domain machine did not move"

    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(s, "entity", id)
    assert last.action == "observe_authored"
    assert last.actor_type == "system" and last.actor_id == "mirror-sync"
    assert last.from_state == last.to_state == "onboarding"


async def test_pull_changing_non_authored_field_is_adapter_error(env):
    engine, client = env
    doc = await _entity(client, external_id="crm-2", crosswalk="XW-1")
    id = doc["self"].rsplit("/", 1)[-1]
    document = {"name": "Acme", "crosswalk_id": "EVIL"}
    with pytest.raises(DefinitionError, match="non-authored"):
        await engine.invoker.sync_authored("entity", id, document,
                                           uuid.uuid4().hex)
    got = (await client.get("/api/-/lookup/entitys/" + id)).json()
    assert got["data"]["crosswalk_id"] == "XW-1", "nothing was written"


async def test_unreachable_is_sync_state_not_error(env):
    engine, client = env
    doc = await _entity(client, external_id="crm-3")
    CRM.down = True
    res = await client.get(doc["self"])
    assert res.status_code == 200, "stored truth keeps serving"
    got = res.json()
    assert got["data"]["sync_state"] == "unreachable"
    CRM.down = False
    got = (await client.get(doc["self"])).json()
    assert got["data"]["sync_state"] == "fresh"
    assert got["data"]["name"] == "Acme"


async def test_conflict_on_authored_fields_does_not_block_written_writes(env):
    engine, client = env
    doc = await _entity(client, external_id="crm-4")
    (await client.get(doc["self"])).json()  # sync up
    id = doc["self"].rsplit("/", 1)[-1]

    await engine.invoker.mark_authored(
        "entity", id, "conflicted", theirs={"name": "Acme Holdings"})
    got = (await client.get(doc["self"])).json()
    assert got["data"]["sync_state"] == "conflicted"
    assert got["data"]["theirs"] == {"name": "Acme Holdings"}

    pulls = CRM.pulls
    res = await client.post(
        f"{doc['self']}/-/set_crosswalk", json={"crosswalk_id": "XW-9"},
        headers={**OWNER, "Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 200, res.text
    assert res.json()["data"]["crosswalk_id"] == "XW-9", \
        "a conflict over hubspot's fields never blocks the written field"
    got = (await client.get(doc["self"])).json()
    assert got["data"]["sync_state"] == "conflicted", \
        "the conflict stands — a person's move, not the clock's"
    assert CRM.pulls == pulls, "no pull while conflicted"


# ── follows=: the authority's value change moves the declared machine ───
async def test_follows_fires_through_the_single_invoker(env):
    engine, client = env
    doc = await _entity(client, external_id="crm-5", crosswalk="XW-2")
    (await client.get(doc["self"])).json()  # sync up
    id = doc["self"].rsplit("/", 1)[-1]

    CRM.docs["crm-5"]["stage"] = "closedwon"
    CRM.etags["crm-5"] = uuid.uuid4().hex
    got = (await client.get(doc["self"])).json()
    assert got["data"]["stage"] == "closedwon"
    assert got["state"] == "active", "follows moved the declared machine"

    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(s, "entity", id)
    assert last.action == "activate"
    assert last.actor_type == "system", "guards ran; the actor is honest"


async def test_follows_refusal_records_value_but_not_transition(env):
    engine, client = env
    doc = await _entity(client, external_id="crm-6")  # no crosswalk
    (await client.get(doc["self"])).json()  # sync up

    CRM.docs["crm-6"]["stage"] = "closedwon"
    CRM.etags["crm-6"] = uuid.uuid4().hex
    got = (await client.get(doc["self"])).json()
    assert got["data"]["stage"] == "closedwon", \
        "the value change is recorded even though the transition refused"
    assert got["state"] == "onboarding", \
        "activate's guard (no crosswalk) refused the follows transition"


# ── discovery: the clock sweep mints via the single invoker ─────────────
async def test_discovery_mints_via_invoker_on_tick(env):
    engine, client = env
    CRM.seed("crm-a", {"name": "Alpha", "stage": "new"})
    CRM.seed("crm-b", {"name": "Beta", "stage": "new"})
    CRM.discoverable = ["crm-a", "crm-b"]

    now = datetime.now(UTC)
    await engine.tick(now)
    listing = (await client.get("/api/entitys")).json()
    assert listing["data"]["total"] == 2
    by_ext = {i["data"]["external_id"]: i for i in listing["data"]["items"]}
    assert set(by_ext) == {"crm-a", "crm-b"}

    minted_id = by_ext["crm-a"]["self"].rsplit("/", 1)[-1]
    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(s, "entity", minted_id)
    assert last.action == "create" and last.actor_type == "system", \
        "minted through the single invoker as the system actor"

    # inside the declared interval: no re-sweep, no duplicates
    await engine.tick(now + timedelta(seconds=1))
    assert (await client.get("/api/entitys")).json()["data"]["total"] == 2

    # past the interval: known ids are skipped, new ones mint
    CRM.seed("crm-c", {"name": "Gamma", "stage": "new"})
    CRM.discoverable = ["crm-a", "crm-b", "crm-c"]
    await engine.tick(now + timedelta(seconds=601))
    assert (await client.get("/api/entitys")).json()["data"]["total"] == 3


async def test_discovered_entity_fills_on_first_read(env):
    engine, client = env
    CRM.seed("crm-z", {"name": "Zeta", "stage": "qualified"})
    CRM.discoverable = ["crm-z"]
    await engine.tick(datetime.now(UTC))
    listing = (await client.get("/api/entitys")).json()
    href = listing["data"]["items"][0]["self"]
    got = (await client.get(href)).json()
    assert got["data"]["name"] == "Zeta", \
        "the mint carries the id; values arrive on the first pull-through"
