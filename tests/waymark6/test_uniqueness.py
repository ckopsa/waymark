"""Declared uniqueness (design E2): the constraint lives on the promoted
columns, and the refusal is hypermedia — an ``already-exists`` Problem
carrying a link to the resource holding the value. Four apps hand-built
this recovery from error payloads; one shipped it broken.
"""
from __future__ import annotations

import os
import uuid
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark6
from waymark6 import Ctx, Resource, Safety, action
from waymark6.core.resource import filterable
from waymark6.core.types import DefinitionError
from waymark6.server.bus import InProcessBus
from waymark6.server.engine import header_principal
from waymark6.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


class BookState(StrEnum):
    OPEN = "open"


class WorkbookData(BaseModel):
    fund_id: str = Field(min_length=1, max_length=64)
    as_of: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}$")
    notes: str | None = Field(default=None, max_length=200)


class RenameInput(BaseModel):
    as_of: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}$")


class Workbook(Resource):
    kind = "workbook"
    State = BookState
    Data = WorkbookData
    initial = BookState.OPEN
    terminal: set = set()
    summary = "{data.fund_id} @ {data.as_of} · {state.label}"

    filterable = filterable(fund_id=filterable.Eq, as_of=filterable.Eq)
    # the composite form: one workbook per fund × period (design E2)
    unique = (("fund_id", "as_of"),)

    @action(from_=BookState.OPEN, to=BookState.OPEN,
            input=RenameInput,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Move period"))
    async def move_period(self, inp: RenameInput, ctx: Ctx) -> None:
        self.data.as_of = inp.as_of


@pytest.fixture
async def env():
    engine = waymark6.Engine(resources=[Workbook], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t",
                         headers={"X-Principal-Id": "maya"})
    try:
        yield client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _post(client, href, json=None, **headers):
    return await client.post(href, json=json or {},
                             headers={"Idempotency-Key": uuid.uuid4().hex,
                                      **headers})


async def test_duplicate_create_409s_with_the_existing_href(env):
    """The refusal carries the pointer, not just the no."""
    client = env
    first = await _post(client, "/api/workbooks",
                        {"fund_id": "fund-88", "as_of": "2026-07-01"})
    assert first.status_code == 201, first.text

    dup = await _post(client, "/api/workbooks",
                      {"fund_id": "fund-88", "as_of": "2026-07-01"})
    assert dup.status_code == 409, dup.text
    problem = dup.json()
    assert problem["type"].endswith("already-exists")
    assert "fund_id + as_of" in problem["detail"]
    assert problem["existing"]["href"] == first.json()["self"]

    # a different period is a different fact
    ok = await _post(client, "/api/workbooks",
                     {"fund_id": "fund-88", "as_of": "2026-08-01"})
    assert ok.status_code == 201, ok.text


async def test_action_writes_hit_the_same_constraint(env):
    """Uniqueness judges updates too: moving a workbook onto an occupied
    period refuses with the occupant linked."""
    client = env
    await _post(client, "/api/workbooks",
                {"fund_id": "fund-88", "as_of": "2026-07-01"})
    aug = (await _post(client, "/api/workbooks",
                       {"fund_id": "fund-88", "as_of": "2026-08-01"})).json()

    res = await _post(client, f"{aug['self']}/-/move_period",
                      {"as_of": "2026-07-01"})
    assert res.status_code == 409, res.text
    assert res.json()["type"].endswith("already-exists")
    assert res.json()["existing"]["href"] != aug["self"]

    # the refused write left nothing behind
    assert (await client.get(aug["self"])).json()["data"]["as_of"] \
        == "2026-08-01"


async def test_role_names_are_unique(env):
    """The dogfood: the role registry's names carry the declaration —
    'unique-ish' became unique (design E2)."""
    client = env  # roles ride every engine with members=True
    first = await _post(client, "/api/roles", {"name": "reader"})
    assert first.status_code == 201, first.text
    dup = await _post(client, "/api/roles", {"name": "reader"})
    assert dup.status_code == 409, dup.text
    assert dup.json()["existing"]["href"] == first.json()["self"]


async def test_unique_fields_must_be_promoted():
    """Definition-time: uniqueness is enforced on promoted columns, so an
    unfilterable/unsortable field cannot declare it."""
    with pytest.raises(DefinitionError):
        class Bad(Resource):
            kind = "bad_unique"
            State = BookState
            Data = WorkbookData
            initial = BookState.OPEN
            summary = "x"
            unique = ("notes",)  # not filterable/sortable
