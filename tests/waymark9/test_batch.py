"""Batch is the collection form of the act (design §7): the same declared
action accepts N inputs at .../-/{name}/batch — every item validated and
guarded exactly as a single invocation, verdicts in the bulk-report shape,
atomic batches one transaction under one correlation id, idempotency for
the whole batch, drafts staging items, and a dry-run verdict report
(validation always completes even when commitment doesn't).
"""
from __future__ import annotations

import os
import uuid
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark9
from waymark9 import (
    Allow, Batch, Bulk, Ctx, DefinitionError, Deny, DraftPolicy, Edit,
    Guard, PartScope, Resource, Safety, action,
)
from waymark9.server.bus import InProcessBus
from waymark9.server.engine import header_principal
from waymark9.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


class SheetState(StrEnum):
    OPEN = "open"


class SheetData(BaseModel):
    total: float = 0
    posted: list[str] = []


class RowInput(BaseModel):
    sku: str = Field(min_length=1, max_length=40)
    qty: float = Field(ge=0, le=1000)


async def _cap(r, inp, ctx) -> Allow | Deny:
    if inp is None:
        return Allow(pending_input=True)
    return Deny() if inp.qty > 100 else Allow()


def qty_cap() -> Guard:
    return Guard(explain="Rows over 100 units need supervisor review.",
                 judges=("qty",), check=_cap, name="qty_cap")


class BSheet(Resource):
    kind = "bsheet"
    State = SheetState
    Data = SheetData
    initial = SheetState.OPEN
    terminal: set = set()
    summary = "Sheet of {data.total} · {state.label}"

    @action(from_=SheetState.OPEN, to=SheetState.OPEN,
            input=RowInput, guards=[qty_cap()],
            batch=Batch(atomic=True, max_items=5),
            edit=Edit(draft=DraftPolicy(), fence=False,
                      unfenced_reason="A batch carries one body and no "
                                      "per-item etag; rows are keyed by "
                                      "sku, not by document version."),
            safety=Safety(idempotent=False, reversible=True, confirm=False),
            display=dict(label="Post row"))
    async def post_row(self, inp: RowInput, ctx: Ctx) -> None:
        self.data.total += inp.qty
        self.data.posted.append(inp.sku)

    @action(from_=SheetState.OPEN, to=SheetState.OPEN,
            input=RowInput, guards=[qty_cap()],
            batch=Batch(atomic=False, max_items=100),
            safety=Safety(idempotent=False, reversible=True, confirm=False),
            display=dict(label="Post loose"))
    async def post_loose(self, inp: RowInput, ctx: Ctx) -> None:
        self.data.total += inp.qty
        self.data.posted.append(inp.sku)


@pytest.fixture
async def env():
    engine = waymark9.Engine(resources=[BSheet], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t",
                         headers={"X-Principal-Id": "ana",
                                  "X-Principal-Display": "Ana"})
    try:
        yield engine, client
    finally:
        await client.aclose()
        await engine.shutdown()


async def _post(client, href, json=None, **headers):
    return await client.post(href, json=json,
                             headers={"Idempotency-Key": uuid.uuid4().hex,
                                      **headers})


async def _sheet(client) -> dict:
    res = await _post(client, "/api/bsheets", {"total": 0})
    assert res.status_code == 201, res.text
    return res.json()


OK1 = {"sku": "A-1", "qty": 10}
OK2 = {"sku": "A-2", "qty": 20}
BAD = {"sku": "A-3", "qty": 500}  # schema-valid; guard refuses > 100


async def test_batch_form_is_advertised_on_the_action_entry(env):
    engine, client = env
    sheet = await _sheet(client)
    doc = (await client.get(sheet["self"])).json()
    assert doc["actions"]["post_row"]["batch"] == {
        "href": f"{sheet['self']}/-/post_row/batch",
        "atomic": True, "max_items": 5}
    assert doc["actions"]["post_loose"]["batch"]["atomic"] is False


async def test_atomic_batch_is_one_correlated_transaction(env):
    engine, client = env
    sheet = await _sheet(client)
    res = await _post(client, f"{sheet['self']}/-/post_row/batch",
                      {"items": [OK1, OK2, {"sku": "A-9", "qty": 5}]})
    assert res.status_code == 200, res.text
    doc = res.json()
    assert doc["kind"] == "bulk_report" and doc["action"] == "post_row"
    assert doc["data"]["succeeded"] == 3
    assert doc["data"]["refused"] == 0 and doc["data"]["failed"] == 0
    assert [v["verdict"] for v in doc["data"]["verdicts"]] == ["ok"] * 3

    after = (await client.get(sheet["self"])).json()
    assert after["data"]["total"] == 35.0
    assert after["data"]["posted"] == ["A-1", "A-2", "A-9"]

    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(s, "bsheet", _id(sheet))
        story = await engine.storage.transitions_by_correlation(
            s, last.correlation_id)
    assert [(t.kind, t.action) for t in story] \
        == [("bsheet", "post_row")] * 3, \
        "one correlation id: the batch is one transition-log unit"


def _id(doc) -> str:
    return doc["self"].rsplit("/", 1)[-1]


async def test_atomic_refusal_carries_every_verdict_and_commits_nothing(env):
    """Validation of ALL items completes even though nothing commits: the
    grid can show every problem at once (design §7)."""
    engine, client = env
    sheet = await _sheet(client)
    res = await _post(client, f"{sheet['self']}/-/post_row/batch",
                      {"items": [OK1, BAD, OK2]})
    assert res.status_code == 409, res.text
    problem = res.json()
    assert "nothing committed" in problem["detail"]
    assert problem["verdicts"] == [
        {"index": 0, "verdict": "ok"},
        {"index": 1, "verdict": "refused",
         "reason": "Rows over 100 units need supervisor review."},
        {"index": 2, "verdict": "ok"},
    ]
    assert problem["report"] == {"succeeded": 0, "refused": 1, "failed": 0}

    after = (await client.get(sheet["self"])).json()
    assert after["data"]["total"] == 0 and after["data"]["posted"] == []
    assert after["meta"]["version"] == 1, "all or none: none"


async def test_partial_batch_commits_what_passes(env):
    engine, client = env
    sheet = await _sheet(client)
    res = await _post(client, f"{sheet['self']}/-/post_loose/batch",
                      {"items": [OK1, BAD, OK2]})
    assert res.status_code == 200, res.text
    report = res.json()["data"]
    assert report["succeeded"] == 2 and report["refused"] == 1
    assert report["refusals"] == [
        {"index": 1, "self": sheet["self"],
         "reason": "Rows over 100 units need supervisor review."}]
    after = (await client.get(sheet["self"])).json()
    assert after["data"]["total"] == 30.0
    assert after["data"]["posted"] == ["A-1", "A-2"]


async def test_schema_invalid_items_are_per_item_verdicts(env):
    """Every item is validated exactly as a single invocation would be —
    schema errors are that item's refusal, not the batch's 422."""
    engine, client = env
    sheet = await _sheet(client)
    res = await _post(client, f"{sheet['self']}/-/post_loose/batch",
                      {"items": [OK1, {"sku": "A-4", "qty": "not-a-number"},
                                 {"sku": "A-5", "qty": 3,
                                  "extra": "nope"}]})
    assert res.status_code == 200, res.text
    report = res.json()["data"]
    assert report["succeeded"] == 1 and report["refused"] == 2
    assert [v["verdict"] for v in report["verdicts"]] \
        == ["ok", "refused", "refused"]


async def test_batch_idempotency_replays_the_stored_report(env):
    engine, client = env
    sheet = await _sheet(client)
    key = uuid.uuid4().hex
    body = {"items": [OK1, OK2]}
    first = await client.post(f"{sheet['self']}/-/post_row/batch", json=body,
                              headers={"Idempotency-Key": key})
    assert first.status_code == 200, first.text
    second = await client.post(f"{sheet['self']}/-/post_row/batch", json=body,
                               headers={"Idempotency-Key": key})
    assert second.status_code == 200
    assert second.content == first.content, "byte-for-byte replay"
    after = (await client.get(sheet["self"])).json()
    assert after["data"]["total"] == 30.0, "the retry posted nothing"
    assert after["meta"]["version"] == 3  # 2 items, once


async def test_batch_requires_an_idempotency_key(env):
    engine, client = env
    sheet = await _sheet(client)
    res = await client.post(f"{sheet['self']}/-/post_row/batch",
                            json={"items": [OK1]})
    assert res.status_code == 428, res.text


async def test_dry_run_returns_the_full_verdict_report_without_committing(env):
    """The load-bearing §7 piece: validation always completes even when
    commitment doesn't — ?dry_run=1 returns every verdict, commits
    nothing, and needs no idempotency key."""
    engine, client = env
    sheet = await _sheet(client)
    res = await client.post(f"{sheet['self']}/-/post_row/batch?dry_run=1",
                            json={"items": [OK1, BAD, OK2]})
    assert res.status_code == 200, res.text
    doc = res.json()
    assert doc["valid"] is False
    assert [v["verdict"] for v in doc["verdicts"]] == ["ok", "refused", "ok"]
    assert doc["verdicts"][1]["reason"] \
        == "Rows over 100 units need supervisor review."

    all_good = await client.post(
        f"{sheet['self']}/-/post_row/batch?dry_run=1",
        json={"items": [OK1, OK2]})
    assert all_good.json()["valid"] is True

    after = (await client.get(sheet["self"])).json()
    assert after["meta"]["version"] == 1 and after["data"]["total"] == 0, \
        "dry-run committed nothing either way"


async def test_max_items_and_items_shape_are_enforced(env):
    engine, client = env
    sheet = await _sheet(client)
    res = await _post(client, f"{sheet['self']}/-/post_row/batch",
                      {"items": [OK1] * 6})
    assert res.status_code == 422
    assert "maxItems is 5" in res.text

    res = await _post(client, f"{sheet['self']}/-/post_row/batch",
                      {"items": []})
    assert res.status_code == 422
    res = await _post(client, f"{sheet['self']}/-/post_row/batch",
                      {"rows": [OK1]})
    assert res.status_code == 422

    res = await _post(client, f"{sheet['self']}/-/nonesuch/batch",
                      {"items": [OK1]})
    assert res.status_code == 404


async def test_single_invocation_of_a_batch_action_still_works(env):
    """batch= is the action's collection FORM, not a replacement — the
    single route stays."""
    engine, client = env
    sheet = await _sheet(client)
    res = await _post(client, f"{sheet['self']}/-/post_row", OK1)
    assert res.status_code == 200, res.text
    assert res.json()["data"]["total"] == 10.0


async def test_batch_draft_stages_items_and_is_consumed_by_the_batch(env):
    """A batch draft stages {"items": [...]}; the staged rows dry-run to
    per-item verdicts at the batch route; a successful batch consumes the
    draft (design §7)."""
    engine, client = env
    sheet = await _sheet(client)
    draft_href = f"{sheet['self']}/-/post_row/draft"

    save = await client.put(draft_href, json={"items": [OK1, BAD]})
    assert save.status_code == 200, save.text
    stored = (await client.get(draft_href)).json()
    assert stored["data"]["values"]["items"] == [OK1, BAD]

    # the staged batch's verdicts, via the existing dry-run machinery
    staged = stored["data"]["values"]["items"]
    verdicts = (await client.post(
        f"{sheet['self']}/-/post_row/batch?dry_run=1",
        json={"items": staged})).json()
    assert [v["verdict"] for v in verdicts["verdicts"]] == ["ok", "refused"]

    # fields the action doesn't take are still refused
    bad = await client.put(draft_href, json={"nonesuch": 1})
    assert bad.status_code == 422

    # drain the (fixed) batch as one act; the draft is consumed
    res = await _post(client, f"{sheet['self']}/-/post_row/batch",
                      {"items": [OK1, OK2]})
    assert res.status_code == 200, res.text
    fresh = (await client.get(draft_href)).json()
    assert fresh["data"]["values"] == {}, "the batch consumed the draft"


async def test_batch_declaration_checks(env):
    with pytest.raises(DefinitionError, match="input"):
        action(from_=SheetState.OPEN, to=SheetState.OPEN,
               batch=Batch(atomic=True),
               safety=Safety(idempotent=True, reversible=True,
                             confirm=False))

    with pytest.raises(DefinitionError, match="placed"):
        action(from_=SheetState.OPEN, to=SheetState.OPEN, input=RowInput,
               batch=Batch(atomic=True),
               place=PartScope("rows", key="sku"),
               safety=Safety(idempotent=True, reversible=True,
                             confirm=False))

    with pytest.raises(DefinitionError, match="fenced"):
        action(from_=SheetState.OPEN, to=SheetState.OPEN, input=RowInput,
               batch=Batch(atomic=True),
               safety=Safety(idempotent=True, reversible=True, confirm=False,
                             fence=True))

    with pytest.raises(DefinitionError, match="not both"):
        action(from_=SheetState.OPEN, to=SheetState.OPEN, input=RowInput,
               batch=Batch(atomic=True), bulk=Bulk(),
               safety=Safety(idempotent=True, reversible=True,
                             confirm=False))

    with pytest.raises(DefinitionError, match="max_items"):
        Batch(max_items=0)
