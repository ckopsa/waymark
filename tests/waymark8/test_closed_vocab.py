"""Closed vocabularies (design §6): ``VocabField(open=False, values=…)``
is an enum that also facets — the declared member set rides the wire as
``items.enum`` and the invoker refuses members outside it, on create and
on action inputs alike. One declaration, two consumers.
"""
from __future__ import annotations

import os
import uuid
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark8
from waymark8 import Ctx, Resource, Safety, action
from waymark8.core.vocab import Observed, Vocab, VocabField
from waymark8.server.bus import InProcessBus
from waymark8.server.engine import header_principal
from waymark8.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

SIZES = ("small", "regular", "large")


class DrinkState(StrEnum):
    LISTED = "listed"


class DrinkData(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    sizes: Vocab[str] = VocabField(
        default_factory=list, open=False, values=SIZES,
        facet=Observed(counts=True),
        description="The cup sizes this drink is served in")


class SizesInput(BaseModel):
    sizes: Vocab[str] = VocabField(
        default_factory=list, open=False, values=SIZES)


class Drink(Resource):
    kind = "drink"
    State = DrinkState
    Data = DrinkData
    initial = DrinkState.LISTED
    terminal: set = set()
    summary = "{data.name} · {state.label}"

    @action(from_=DrinkState.LISTED, to=DrinkState.LISTED,
            input=SizesInput,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Set sizes"))
    async def set_sizes(self, inp: SizesInput, ctx: Ctx) -> None:
        self.data.sizes = inp.sizes


@pytest.fixture
async def env():
    engine = waymark8.Engine(resources=[Drink], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    transport = ASGITransport(app=app)
    client = AsyncClient(transport=transport, base_url="http://t",
                         headers={"X-Principal-Id": "barista"})
    try:
        yield client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _post(client, href, json=None):
    return await client.post(href, json=json or {},
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def test_declared_members_pass_and_unknown_ones_422(env):
    client = env
    res = await _post(client, "/api/drinks",
                      {"name": "Flat white", "sizes": ["small", "regular"]})
    assert res.status_code == 201, res.text
    doc = res.json()
    assert doc["data"]["sizes"] == ["small", "regular"]

    res = await _post(client, "/api/drinks",
                      {"name": "Affogato", "sizes": ["venti"]})
    assert res.status_code == 422, res.text
    errors = res.json()["errors"]
    assert "venti" in errors["sizes"][0]
    assert "small" in errors["sizes"][0], "the refusal names the declared set"

    # the same declaration judges action inputs
    res = await _post(client, f"{doc['self']}/-/set_sizes",
                      {"sizes": ["large", "grande"]})
    assert res.status_code == 422, res.text
    assert "grande" in res.json()["errors"]["sizes"][0]

    res = await _post(client, f"{doc['self']}/-/set_sizes",
                      {"sizes": ["large"]})
    assert res.status_code == 200, res.text
    assert res.json()["data"]["sizes"] == ["large"]


async def test_the_schema_advertises_the_enum(env):
    client = env
    schema = (await client.get("/api/schemas/drink")).json()
    assert schema["properties"]["sizes"]["items"]["enum"] == list(SIZES)
    input_schema = (await client.get("/api/schemas/SizesInput")).json()
    assert input_schema["properties"]["sizes"]["items"]["enum"] == list(SIZES)
