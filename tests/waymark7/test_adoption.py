"""Adoption is a transition (design 7.0 §3): Never grandfathers, adopt
restamps, Immediate is today's behavior spelled out.

An ``adoption=Never`` kind's rows finish under their birth law when a
newer revision becomes current: the boot revise grandfathers the old
revision instead of superseding it, the rows keep their stamps and their
values, and each carries the engine-injected ``adopt`` affordance —
explicit, recorded, computed under the new law the moment it lands. An
``adoption=Immediate`` kind (the default) bulk-adopts at the revise: the
backfill restamps everything, which is exactly what every pre-7.0
deploy always did.
"""
from __future__ import annotations

import os
import uuid
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark7
from waymark7 import (
    Ctx, Derived, Never, Resource, Safety, Tolerance, action, filterable,
)
from waymark7.server.bus import InProcessBus
from waymark7.server.engine import header_principal
from waymark7.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

ELENA = {"X-Principal-Id": "elena", "X-Principal-Display": "Elena V."}

TOL_V1 = "0.05"
TOL_V2 = "0.01"


class WbState(StrEnum):
    OPEN = "open"
    CLOSED = "closed"


def make_wb(tolerance: str, *, never: bool = True):
    class WbData(BaseModel):
        title: str = Field(min_length=1, max_length=80)
        amount: float = 0.0
        reconciled: bool = Derived(over=("amount",),
                                   within=Tolerance(tolerance))

    class Workbook(Resource):
        kind = "awb"
        State = WbState
        Data = WbData
        initial = WbState.OPEN
        terminal = {WbState.CLOSED}
        summary = "{data.title} · {state.label}"
        filterable = filterable(state=filterable.Eq,
                                reconciled=filterable.Eq)
        if never:
            adoption = Never

        @action(from_=WbState.OPEN, to=WbState.CLOSED,
                safety=Safety(idempotent=True, reversible=False,
                              confirm=True,
                              consequence="Closes the workbook."))
        async def close(self, inp: None, ctx: Ctx) -> None:
            pass

    return Workbook


async def _boot(cls, *, drop: bool = False):
    engine = waymark7.Engine(resources=[cls], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    if drop:
        await engine.storage.drop_all()
    await engine.startup()
    return engine


def _client(engine, headers=ELENA) -> AsyncClient:
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    return AsyncClient(transport=ASGITransport(app=app),
                       base_url="http://t", headers=headers)


async def _post(client, href, json=None):
    return await client.post(href, json=json,
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def _revisions(engine, target_kind="awb"):
    async with engine.storage.session() as s:
        rows, _ = await engine.storage.query(
            s, "definition", filters={"target_kind": target_kind},
            sort="revision", page_size=50, page_number=1)
    return rows


async def _rows(engine, kind="awb"):
    async with engine.storage.session() as s:
        rows, _ = await engine.storage.query(
            s, kind, filters={}, sort=None, page_size=100, page_number=1)
    return {r.data.title: r for r in rows}


async def _seed_v1(**kw):
    e1 = await _boot(make_wb(TOL_V1, **kw), drop=True)
    client = _client(e1)
    try:
        res = await _post(client, "/api/awbs",
                          {"title": "wb-1", "amount": 0.03})
        assert res.status_code == 201, res.text
        assert res.json()["data"]["reconciled"] is True
    finally:
        await client.aclose()
        await e1.shutdown()


async def test_never_kind_grandfathers_at_the_revise():
    """adoption=Never (design §3): the new law governs new rows only —
    the old revision grandfathers (not superseded: rows live under it),
    existing rows keep their stamp and their values, and the fingerprint
    records the policy."""
    await _seed_v1()
    e2 = await _boot(make_wb(TOL_V2))
    client = _client(e2)
    try:
        rev1, rev2 = await _revisions(e2)
        assert (rev1.state, rev2.state) == ("grandfathered", "current"), \
            "a revision with survivors is law, not history (design §1)"
        assert rev2.data.fingerprint.get("adoption") == "Never"

        rows = await _rows(e2)
        assert rows["wb-1"].law_revision == 1
        assert rows["wb-1"].data.reconciled is True, \
            "the backfill must not strike a grandfathered row"

        # the row's envelope names its law and affords the way forward
        doc = (await client.get(f"/api/awbs/{rows['wb-1'].id}")).json()
        assert doc["meta"]["law_revision"] == 1
        assert doc["meta"]["law"] == rev1.id
        assert "adopt" in doc["actions"]

        # a new row is born under the current law, and does NOT afford it
        res = await _post(client, "/api/awbs",
                          {"title": "wb-2", "amount": 0.03})
        assert res.status_code == 201, res.text
        fresh = res.json()
        assert fresh["meta"]["law_revision"] == 2
        assert fresh["data"]["reconciled"] is False
        assert "adopt" not in fresh["actions"]

        # a write on the grandfathered row recomputes under ITS law, from
        # the old revision's stored parameters, and anchors to it
        res = await _post(client, f"/api/awbs/{rows['wb-1'].id}/-/close")
        assert res.status_code == 200, res.text
        assert res.json()["data"]["reconciled"] is True, \
            "0.03 still reconciles under the row's 0.05 law"
        async with e2.storage.session() as s:
            t = await e2.storage.last_transition(s, "awb", rows["wb-1"].id)
        assert t.defined_by == rev1.id
    finally:
        await client.aclose()
        await e2.shutdown()


async def test_adopt_restamps_recomputes_and_records():
    """The adopt transition (design §3): explicit, recorded, and the
    row's recompute under its new law rides it — 5.0's
    stale-by-definition machinery, per-row."""
    await _seed_v1()
    e2 = await _boot(make_wb(TOL_V2))
    client = _client(e2)
    try:
        rev1, rev2 = await _revisions(e2)
        rows = await _rows(e2)
        wb = rows["wb-1"]

        res = await _post(client, f"/api/awbs/{wb.id}/-/adopt")
        assert res.status_code == 200, res.text
        doc = res.json()
        assert doc["state"] == "open", "adopt is a same-state transition"
        assert doc["meta"]["law_revision"] == 2
        assert doc["meta"]["law"] == rev2.id
        assert doc["data"]["reconciled"] is False, \
            "adoption triggers the recompute under the new law"
        assert "adopt" not in doc["actions"], \
            "an adopted row no longer affords adoption"

        async with e2.storage.session() as s:
            t = await e2.storage.last_transition(s, "awb", wb.id)
        assert t.action == "adopt"
        assert t.from_state == t.to_state == "open"
        assert t.actor_id == "elena"
        assert t.defined_by == rev2.id, \
            "the adopt anchors to the law the row moved TO"

        # idempotent: a second adopt returns the document, no transition
        res = await _post(client, f"/api/awbs/{wb.id}/-/adopt")
        assert res.status_code == 200, res.text
        async with e2.storage.session() as s:
            again = await e2.storage.last_transition(s, "awb", wb.id)
        assert again.id == t.id
    finally:
        await client.aclose()
        await e2.shutdown()


async def test_adopting_the_last_survivor_supersedes_the_old_law():
    """Supersede-when-empty via adoption (design §1/§3): the moment the
    last row stamped to a grandfathered revision adopts, the revision
    supersedes — lazily, on that very transition, by the system actor."""
    await _seed_v1()
    e2 = await _boot(make_wb(TOL_V2))
    client = _client(e2)
    try:
        rows = await _rows(e2)
        res = await _post(client, f"/api/awbs/{rows['wb-1'].id}/-/adopt")
        assert res.status_code == 200, res.text

        rev1, rev2 = await _revisions(e2)
        assert rev1.state == "superseded", \
            "the law died the moment it was empty"
        async with e2.storage.session() as s:
            t = await e2.storage.last_transition(s, "definition", rev1.id)
        assert t.action == "supersede"
        assert t.actor_type == "system"
    finally:
        await client.aclose()
        await e2.shutdown()


async def test_immediate_kind_bulk_adopts_at_the_revise():
    """adoption=Immediate (the default): the revise backfill IS the bulk
    adopt — every row restamps and recomputes, the old revision
    supersedes, and nothing affords adopt. Today's behavior, now spelled
    out in the stamps."""
    await _seed_v1(never=False)
    e2 = await _boot(make_wb(TOL_V2, never=False))
    client = _client(e2)
    try:
        rev1, rev2 = await _revisions(e2)
        assert (rev1.state, rev2.state) == ("superseded", "current")
        rows = await _rows(e2)
        assert rows["wb-1"].law_revision == 2
        assert rows["wb-1"].data.reconciled is False
        doc = (await client.get(f"/api/awbs/{rows['wb-1'].id}")).json()
        assert "adopt" not in doc["actions"]
        assert doc["meta"]["law_revision"] == 2
    finally:
        await client.aclose()
        await e2.shutdown()
