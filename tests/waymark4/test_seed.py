"""Template instantiation and sum rollups (design E4, second pass): the
seed declaration replaces onboarding's ``create_event`` proc, intake's
checklist copy, and cash recon's account-registry clone; ``agg="sum"``
declares cash recon's Σbreaks.
"""
from __future__ import annotations

import os
import uuid
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark4
from waymark4 import (
    Ctx, DefinitionError, Owns, Ref, RefField, Registry, Resource, Rollup,
    Safety, Seed, action, filterable, rollup_is,
)
from waymark4.core import checks
from waymark4.server.bus import InProcessBus
from waymark4.server.engine import header_principal
from waymark4.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


class TemplateState(StrEnum):
    ACTIVE = "active"
    RETIRED = "retired"


class TemplateData(BaseModel):
    fund_type: str = Field(min_length=1, max_length=16)
    name: str = Field(min_length=1, max_length=120)
    amount: float = Field(default=0, ge=0)


class ItemTemplate(Resource):
    kind = "item_template"
    State = TemplateState
    Data = TemplateData
    initial = TemplateState.ACTIVE
    terminal: set = set()
    summary = "{data.name} · {state.label}"
    filterable = filterable(fund_type=filterable.Eq,
                            state=filterable.Eq | filterable.In)

    @action(from_=TemplateState.ACTIVE, to=TemplateState.RETIRED,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Retire"))
    async def retire(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=TemplateState.RETIRED, to=TemplateState.ACTIVE,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Reactivate"))
    async def reactivate(self, inp: None, ctx: Ctx) -> None:
        pass


class ItemState(StrEnum):
    PENDING = "pending"
    DONE = "done"


class ItemData(BaseModel):
    event_id: Ref["event"] = RefField()
    name: str = Field(min_length=1, max_length=120)
    amount: float = Field(default=0, ge=0)
    source: str | None = Field(default=None, max_length=32)


class Item(Resource):
    kind = "item"
    State = ItemState
    Data = ItemData
    initial = ItemState.PENDING
    terminal = {ItemState.DONE}
    summary = "{data.name} · {state.label}"
    filterable = filterable(event_id=filterable.Eq,
                            state=filterable.Eq | filterable.In,
                            amount=filterable.Eq)

    @action(from_=ItemState.PENDING, to=ItemState.DONE,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=waymark4.Acknowledged(
                              "Completion is the record; nothing is lost.")),
            display=dict(label="Done"))
    async def complete(self, inp: None, ctx: Ctx) -> None:
        pass


class EventState(StrEnum):
    OPEN = "open"
    CLOSED = "closed"


class EventData(BaseModel):
    fund_type: str = Field(min_length=1, max_length=16)


class Event(Resource):
    kind = "event"
    State = EventState
    Data = EventData
    initial = EventState.OPEN
    terminal = {EventState.CLOSED}
    summary = "{data.fund_type} onboarding · {state.label}"

    owns = (Owns("item", via="event_id",
                 seed=Seed(kind="item_template",
                           where={"fund_type": "{data.fund_type}",
                                  "state": "active"},
                           copy={"name": "name", "amount": "amount"},
                           defaults={"source": "template"}),
                 rollups={"open_items": Rollup(
                              filters={"state": ("pending",)}),
                          "open_amount": Rollup(
                              filters={"state": ("pending",)},
                              agg="sum", of="amount")}),)

    @action(from_=EventState.OPEN, to=EventState.CLOSED,
            guards=[rollup_is(
                "open_amount", "==", 0,
                explain="{open_amount} is still unexplained — finish the "
                        "items first.")],
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=waymark4.Acknowledged(
                              "Closing records a finished onboarding.")),
            display=dict(label="Close"))
    async def close(self, inp: None, ctx: Ctx) -> None:
        pass


@pytest.fixture
async def env():
    engine = waymark4.Engine(resources=[ItemTemplate, Item, Event],
                             storage=TEST_DSN, principal=header_principal,
                             services=None, bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t",
                         headers={"X-Principal-Id": "priya",
                                  "X-Principal-Display": "Priya"})
    try:
        yield engine, client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _post(client, href, json=None):
    return await client.post(href, json=json,
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def _template(client, fund_type, name, amount=0.0) -> dict:
    res = await _post(client, "/api/item_templates",
                      {"fund_type": fund_type, "name": name,
                       "amount": amount})
    assert res.status_code == 201, res.text
    return res.json()


async def test_create_seeds_children_from_matching_templates(env):
    """One declaration replaces the stored proc: matching active templates
    become owned children, atomically with the parent, in the creator's
    own audit entries, sharing the create's correlation id."""
    engine, client = env
    await _template(client, "pe", "KYC review", 100.0)
    await _template(client, "pe", "Bank setup", 50.0)
    await _template(client, "hf", "Prime broker", 75.0)
    retired = await _template(client, "pe", "Old step")
    await _post(client, f"{retired['self']}/-/retire")

    event = (await _post(client, "/api/events",
                         {"fund_type": "pe"})).json()
    assert event["rollups"] == {"open_items": 2, "open_amount": 150.0}, \
        "the create response already counts and sums the seeded children"

    event_id = event["self"].rsplit("/", 1)[-1]
    listing = (await client.get(f"/api/items?event_id={event_id}")).json()
    assert listing["data"]["total"] == 2
    names = {i["data"]["name"] for i in listing["data"]["items"]}
    assert names == {"KYC review", "Bank setup"}, \
        "the where filter templated over the parent and honored state"
    assert all(i["data"]["source"] == "template"
               for i in listing["data"]["items"])

    async with engine.storage.session() as s:
        create = await engine.storage.last_transition(s, "event", event_id)
        item_id = listing["data"]["items"][0]["self"].rsplit("/", 1)[-1]
        child = await engine.storage.last_transition(s, "item", item_id)
    assert child.action == "create"
    assert child.actor_id == "priya", "seeding is the creator's own doing"
    assert child.correlation_id == create.correlation_id, "one story"


async def test_template_changes_do_not_retro_propagate(env):
    """The recorded punt, asserted: retiring a template later leaves open
    events untouched (onboarding story 4's propagation policy is a
    domain decision this declaration deliberately does not make)."""
    engine, client = env
    tmpl = await _template(client, "pe", "KYC review", 10.0)
    event = (await _post(client, "/api/events",
                         {"fund_type": "pe"})).json()
    await _post(client, f"{tmpl['self']}/-/retire")
    assert (await client.get(event["self"])).json()["rollups"][
        "open_items"] == 1


async def test_sum_rollup_gates_the_close(env):
    """cash recon's Σbreaks, declared: close refuses with the unexplained
    amount in the reason, and passes at zero."""
    engine, client = env
    await _template(client, "pe", "KYC review", 100.0)
    event = (await _post(client, "/api/events",
                         {"fund_type": "pe"})).json()

    doc = (await client.get(event["self"])).json()
    assert "close" not in doc["actions"]
    assert "100.0 is still unexplained" in doc["unavailable"]["close"]["reason"]

    event_id = event["self"].rsplit("/", 1)[-1]
    items = (await client.get(f"/api/items?event_id={event_id}")).json()
    await _post(client, f"{items['data']['items'][0]['self']}/-/complete")

    res = await _post(client, f"{event['self']}/-/close")
    assert res.status_code == 200, res.text
    assert res.json()["state"] == "closed"


async def test_rollups_filter_and_sort_the_collection(env):
    """The onboarding dashboard's status filter, compiled (design E4):
    rollup params land in the collection WHERE/ORDER BY as correlated
    subqueries — the count stays honest, nothing is post-filtered."""
    engine, client = env
    await _template(client, "pe", "KYC review", 100.0)
    busy = (await _post(client, "/api/events", {"fund_type": "pe"})).json()
    idle = (await _post(client, "/api/events", {"fund_type": "hf"})).json()

    in_progress = (await client.get("/api/events?open_items_gte=1")).json()
    assert in_progress["data"]["total"] == 1
    assert in_progress["data"]["items"][0]["self"] == busy["self"]

    done = (await client.get("/api/events?open_items=0")).json()
    assert done["data"]["total"] == 1
    assert done["data"]["items"][0]["self"] == idle["self"], \
        "zero children counts as zero, not as missing"

    heavy = (await client.get("/api/events?open_amount_gte=50")).json()
    assert heavy["data"]["total"] == 1

    listing = (await client.get("/api/events?sort=-open_items")).json()
    assert [i["self"] for i in listing["data"]["items"]] \
        == [busy["self"], idle["self"]]

    # the params are advertised, so a typo stays a loud Problem
    schema = (await client.get("/api/events")).json()
    props = schema["actions"]["query"]["input"]["properties"]
    assert props["open_items"]["x-rollup"] is True
    assert (await client.get("/api/events?open_itmes=1")).status_code == 422


async def test_seed_declarations_are_checked_at_assembly(env):
    def registry_with(seed):
        reg = Registry()

        class Event2(Event):
            kind = "event"
            owns = (Owns("item", via="event_id", seed=seed),)
        for cls in (ItemTemplate, Item, Event2):
            reg.register(cls)
        return reg

    for bad in [
        Seed(kind="nope", copy={"name": "name"}),
        Seed(kind="item_template", where={"nope": 1}, copy={"name": "name"}),
        Seed(kind="item_template", copy={"nope": "name"}),
        Seed(kind="item_template", copy={"name": "nope"}),
        Seed(kind="item_template", copy={"name": "name"},
             defaults={"nope": 1}),
    ]:
        with pytest.raises(DefinitionError):
            checks.check_owns(registry_with(bad))
