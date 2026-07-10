"""The 8.0 admission test (design 8.0, "Why 8.0"): a semantic change
that yesterday was an fn edit — refused at the pilot gate, promoted
totally, grandfathered approximately — spelled as an expression, gets
the full 7.0 lifecycle with zero Python deployed.

The story is the design appendix's: ``reconciled`` moves from an
absolute tolerance (``abs(amount) <= 0.05``) to a relative one
(``abs(amount) <= max(0.01, 0.001 * base)``) — a *structural* change no
parameter overlay could fake, which is exactly what makes it the proof:

- boot 2 (propose) HOLDS it at ``proposed`` and keeps serving the
  current tree (v7: ``code_or_shape``, promoted totally with a note);
- Elena pilots it for fund-alpha: one collection, two expression laws,
  each row honest (v7: unrepresentable);
- an ``adoption=Never`` kind's rows keep evaluating their birth
  revision's OWN tree after the new law promotes — v7 deviation #6
  (resident code with stored parameters) is dead for expr facts;
- the same change spelled as ``fn=`` keeps its 7.0 fate, which is the
  boundary moving for a reason rather than dissolving.

Boots are simulated the test_populations way: factory-made classes, one
engine per version, same database.
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
from waymark9 import Ctx, Derived, E, Edit, Never, Resource, Safety, \
    action, filterable
from waymark9.server.bus import InProcessBus
from waymark9.server.engine import header_principal
from waymark9.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

ELENA = {"X-Principal-Id": "elena", "X-Principal-Display": "Elena V."}

ABSOLUTE = E.f("amount").abs() <= E.num("0.05")
RELATIVE = E.f("amount").abs() <= E.max(E.num("0.01"),
                                        E.num("0.001") * E.f("base"))


class WbState(StrEnum):
    OPEN = "open"
    CLOSED = "closed"


class Restate(BaseModel):
    amount: float


def make_wb(expr, *, adoption=None, kind_token="xwb"):
    """A workbook whose reconciliation law is an expression. ``base``
    rides ``over=`` in both revisions — only the tree changes, which is
    what keeps the diff pure data-law (an over= reshape changes the
    invalidation map and honestly stays code_or_shape)."""

    class WbData(BaseModel):
        title: str = Field(min_length=1, max_length=80)
        fund: str = Field(default="fund-beta", max_length=40)
        amount: float = 0.0
        base: float = 1.0
        reconciled: bool = Derived(over=("amount", "base"), expr=expr)

    class Workbook(Resource):
        kind = kind_token
        State = WbState
        Data = WbData
        initial = WbState.OPEN
        terminal = {WbState.CLOSED}
        summary = "{data.title} · {state.label}"
        filterable = filterable(state=filterable.Eq, fund=filterable.Eq,
                                reconciled=filterable.Eq)

        @action(from_=WbState.OPEN, to=WbState.OPEN, input=Restate,
                edit=Edit(prefill=("amount",)),
                safety=Safety(idempotent=True, reversible=True,
                              confirm=False))
        async def restate(self, inp: Restate, ctx: Ctx) -> None:
            self.data.amount = inp.amount

        @action(from_=WbState.OPEN, to=WbState.CLOSED,
                safety=Safety(idempotent=True, reversible=False,
                              confirm=True,
                              consequence="Closes the workbook."))
        async def close(self, inp: None, ctx: Ctx) -> None:
            pass

    if adoption is not None:
        Workbook.adoption = adoption
    return Workbook


def make_wb_fn(fn, *, kind_token="xwbf"):
    """The same law spelled as code — the contrast half."""

    class WbData(BaseModel):
        title: str = Field(min_length=1, max_length=80)
        amount: float = 0.0
        base: float = 1.0
        reconciled: bool = Derived(over=("amount", "base"), fn=fn)

    class Workbook(Resource):
        kind = kind_token
        State = WbState
        Data = WbData
        initial = WbState.OPEN
        terminal = {WbState.CLOSED}
        summary = "{data.title} · {state.label}"

        @action(from_=WbState.OPEN, to=WbState.CLOSED,
                safety=Safety(idempotent=True, reversible=False,
                              confirm=True,
                              consequence="Closes the workbook."))
        async def close(self, inp: None, ctx: Ctx) -> None:
            pass

    return Workbook


async def _boot(cls, *, deploy: str = "auto", drop: bool = False):
    engine = waymark9.Engine(resources=[cls], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus(), deploy=deploy)
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


async def _restate(client, plural, row_id, amount):
    """restate declares edit=Edit(prefill), so it is fenced: the invoke
    carries the document's etag, as any honest editor would."""
    doc = (await client.get(f"/api/{plural}/{row_id}")).json()
    return await client.post(
        f"/api/{plural}/{row_id}/-/restate", json={"amount": amount},
        headers={"Idempotency-Key": uuid.uuid4().hex,
                 "If-Match": doc["meta"]["etag"]})


async def _create(client, plural, title, fund, amount, base=1.0):
    res = await _post(client, f"/api/{plural}",
                      {"title": title, "fund": fund, "amount": amount,
                       "base": base})
    assert res.status_code == 201, res.text
    return res.json()


async def _revisions(engine, target_kind):
    async with engine.storage.session() as s:
        rows, _ = await engine.storage.query(
            s, "definition", filters={"target_kind": target_kind},
            sort="revision", page_size=50, page_number=1)
    return rows


async def _rows(engine, kind):
    async with engine.storage.session() as s:
        rows, _ = await engine.storage.query(
            s, kind, filters={}, sort=None, page_size=100, page_number=1)
    return {r.data.title: r for r in rows}


# ── the hold: v7 refused this diff; v8 serves the current tree ───────────
async def test_expr_diff_holds_at_proposed_and_serves_the_current_tree():
    e1 = await _boot(make_wb(ABSOLUTE), drop=True)
    c1 = _client(e1)
    try:
        doc = await _create(c1, "xwbs", "seed", "fund-beta", 0.03)
        assert doc["data"]["reconciled"] is True  # 0.03 ≤ 0.05
    finally:
        await c1.aclose()
        await e1.shutdown()

    e2 = await _boot(make_wb(RELATIVE), deploy="propose")
    c2 = _client(e2)
    try:
        rev1, rev2 = await _revisions(e2, "xwb")
        assert rev2.state == "proposed", \
            "an expression diff is data-law: held, not promoted (v7 " \
            "would have promoted totally with a deploy note)"
        assert rev2.data.diff_class == "data_law"
        assert rev2.data.deploy_note is None

        # a create during the hold computes under the CURRENT tree,
        # reconstructed from the stored fingerprint — not the resident
        # proposal (0.03 under the relative law would be False)
        doc = await _create(c2, "xwbs", "held", "fund-beta", 0.03)
        assert doc["data"]["reconciled"] is True
        assert doc["meta"]["law_revision"] == 1
    finally:
        await c2.aclose()
        await e2.shutdown()


# ── the pilot: one collection, two expression laws, every row honest ─────
async def test_expr_change_pilots_per_population():
    e1 = await _boot(make_wb(ABSOLUTE), drop=True)
    c1 = _client(e1)
    try:
        await _create(c1, "xwbs", "alpha-1", "fund-alpha", 0.03)
        await _create(c1, "xwbs", "beta-1", "fund-beta", 0.03)
    finally:
        await c1.aclose()
        await e1.shutdown()

    e2 = await _boot(make_wb(RELATIVE), deploy="propose")
    elena = _client(e2)
    try:
        rev1, rev2 = await _revisions(e2, "xwb")
        res = await _post(elena, f"/api/definitions/{rev2.id}/-/pilot",
                          {"where": {"fund": "fund-alpha"}})
        assert res.status_code == 200, res.text
        assert res.json()["state"] == "piloted"

        rows = await _rows(e2, "xwb")
        # fund-alpha restamped and recomputed under the piloted TREE
        assert rows["alpha-1"].law_revision == 2
        assert rows["alpha-1"].data.reconciled is False, \
            "0.03 fails max(0.01, 0.001·1) — the piloted relative law"
        # fund-beta keeps the current tree, served from the stored law
        assert rows["beta-1"].law_revision == 1
        assert rows["beta-1"].data.reconciled is True

        for title, revision in (("alpha-1", 2), ("beta-1", 1)):
            doc = (await elena.get(f"/api/xwbs/{rows[title].id}")).json()
            assert doc["meta"]["law_revision"] == revision

        # writes are judged and recomputed by the row's law: restating a
        # beta row to 0.04 reconciles under ITS 0.05, not the pilot's
        res = await _restate(elena, "xwbs", rows["beta-1"].id, 0.04)
        assert res.status_code == 200, res.text
        assert res.json()["data"]["reconciled"] is True
        # and the same write on the piloted row settles under the pilot
        res = await _restate(elena, "xwbs", rows["alpha-1"].id, 0.005)
        assert res.status_code == 200, res.text
        assert res.json()["data"]["reconciled"] is True, \
            "0.005 ≤ max(0.01, 0.001·1) under the piloted tree"
    finally:
        await elena.aclose()
        await e2.shutdown()


# ── the grandfather: the birth revision's OWN tree, not resident code ────
async def test_grandfathered_expr_law_evaluates_its_own_tree():
    e1 = await _boot(make_wb(ABSOLUTE, adoption=Never, kind_token="xnwb"),
                     drop=True)
    c1 = _client(e1)
    try:
        doc = await _create(c1, "xnwbs", "feb", "fund-beta", 0.04)
        assert doc["data"]["reconciled"] is True  # 0.04 ≤ 0.05
    finally:
        await c1.aclose()
        await e1.shutdown()

    # boot 2 promotes the relative law (auto); Never grandfathers
    e2 = await _boot(make_wb(RELATIVE, adoption=Never, kind_token="xnwb"))
    c2 = _client(e2)
    try:
        rev1, rev2 = await _revisions(e2, "xnwb")
        assert rev1.state == "grandfathered"
        assert rev2.state == "current"

        rows = await _rows(e2, "xnwb")
        assert rows["feb"].law_revision == 1
        assert rows["feb"].data.reconciled is True, \
            "Never: the living row was neither restamped nor recomputed"

        # THE deviation-#6 kill: a write recomputes under revision 1's
        # stored TREE (0.03 ≤ 0.05 → True). Under v7 semantics the
        # resident code (the relative law) would have judged it False.
        res = await _restate(c2, "xnwbs", rows["feb"].id, 0.03)
        assert res.status_code == 200, res.text
        doc = res.json()
        assert doc["meta"]["law_revision"] == 1
        assert doc["data"]["reconciled"] is True, \
            "the grandfathered row evaluates ITS revision's expression " \
            "exactly — not resident code with stored parameters"

        # while a fresh create is born under the current relative law and
        # the same amount honestly fails it
        doc = await _create(c2, "xnwbs", "mar", "fund-beta", 0.03)
        assert doc["meta"]["law_revision"] == 2
        assert doc["data"]["reconciled"] is False
    finally:
        await c2.aclose()
        await e2.shutdown()


# ── the contrast: code keeps its 7.0 fate ────────────────────────────────
async def test_the_same_change_spelled_as_fn_promotes_totally():
    e1 = await _boot(make_wb_fn(lambda a, b: abs(a) <= 0.05), drop=True)
    await e1.shutdown()

    e2 = await _boot(make_wb_fn(lambda a, b: abs(a) <= max(0.01, 0.001 * b)),
                     deploy="propose")
    try:
        rev1, rev2 = await _revisions(e2, "xwbf")
        assert rev2.state == "current", \
            "an fn edit still cannot hold — the capability line moved " \
            "to the expression boundary, it did not dissolve"
        assert rev2.data.diff_class == "code_or_shape"
        assert rev2.data.deploy_note == \
            "promoted without hold: diff exceeds data-law"
    finally:
        await e2.shutdown()
