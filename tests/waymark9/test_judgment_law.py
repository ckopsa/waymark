"""The 9.0 admission test (design 9.0): the law binds the row's judgment.

The story: a workbook's ``close`` gate tightens from ``abs(amount) ≤
0.05`` to ``≤ 0.01``. Under v8 that is a judgment diff — ``code_or_
shape``, promoted totally, every open workbook struck at once. Here the
gate is a tree, so the deploy holds (rows still advertised AND enforced
under the current gate), pilots per population (one collection, two
gates, each row's envelope and its 409s agreeing under the row's own
revision, for every reader), and grandfathers (an ``adoption=Never``
row closes under the gate it was born under — and its law supersedes
the day it does). The same change spelled as ``check=`` keeps its v8
fate, which is the boundary moving for a reason.
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
    Allow, Ctx, Deny, E, Edit, Never, Resource, Safety, action, filterable,
    guard,
)
from waymark9.core.fingerprint import (
    _guard_fp, classify_diff, diff_fingerprints,
)
from waymark9.server.bus import InProcessBus
from waymark9.server.engine import header_principal
from waymark9.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

ELENA = {"X-Principal-Id": "elena", "X-Principal-Display": "Elena V."}
MARCUS = {"X-Principal-Id": "marcus", "X-Principal-Display": "Marcus T."}

GATE_V1 = E.data("amount").abs() <= E.num("0.05")
GATE_V2 = E.data("amount").abs() <= E.num("0.01")


class WbState(StrEnum):
    OPEN = "open"
    CLOSED = "closed"


class Restate(BaseModel):
    amount: float


def make_wb(gate, *, adoption=None, kind_token="jwb"):
    """A workbook whose close GATE is the law under trial — no derived
    facts at all, so the v1→v2 diff is pure judgment: one expression
    leaf under machine.actions.close.guards."""

    class WbData(BaseModel):
        title: str = Field(min_length=1, max_length=80)
        fund: str = Field(default="fund-beta", max_length=40)
        amount: float = 0.0

    class Workbook(Resource):
        kind = kind_token
        State = WbState
        Data = WbData
        initial = WbState.OPEN
        terminal = {WbState.CLOSED}
        summary = "{data.title} · {state.label}"
        filterable = filterable(state=filterable.Eq, fund=filterable.Eq)

        @action(from_=WbState.OPEN, to=WbState.OPEN, input=Restate,
                edit=Edit(prefill=("amount",)),
                safety=Safety(idempotent=True, reversible=True,
                              confirm=False))
        async def restate(self, inp: Restate, ctx: Ctx) -> None:
            self.data.amount = inp.amount

        @action(from_=WbState.OPEN, to=WbState.CLOSED,
                guards=[guard.expr(
                    name="balanced", when=gate,
                    explain="Out of balance by {amount}.",
                    vars={"amount": E.data("amount")})],
                safety=Safety(idempotent=True, reversible=False,
                              confirm=True,
                              consequence="Closes the workbook."))
        async def close(self, inp: None, ctx: Ctx) -> None:
            pass

    if adoption is not None:
        Workbook.adoption = adoption
    return Workbook


def make_wb_check(balanced_check, *, kind_token="jwbc"):
    """The same gate spelled as code — the contrast half. The check body
    is written at each call site: callables hash by SOURCE TEXT, so a
    closed-over threshold would make two laws fingerprint as one."""
    from waymark9 import Guard

    class WbData(BaseModel):
        title: str = Field(min_length=1, max_length=80)
        amount: float = 0.0

    class Workbook(Resource):
        kind = kind_token
        State = WbState
        Data = WbData
        initial = WbState.OPEN
        terminal = {WbState.CLOSED}
        summary = "{data.title} · {state.label}"

        @action(from_=WbState.OPEN, to=WbState.CLOSED,
                guards=[Guard(name="balanced", check=balanced_check,
                              explain="Out of balance by {amount}.",
                              vars=("amount",))],
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


async def _create(client, plural, title, fund, amount):
    res = await _post(client, f"/api/{plural}",
                      {"title": title, "fund": fund, "amount": amount})
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


# ── the gate widens by exactly one class (design §2) ─────────────────────
def _machine_fp(g):
    return {"machine": {"actions": {"close": {"guards": [_guard_fp(g)]}}}}


def test_judgment_leaf_diffs_are_data_law_structure_is_not():
    v1 = guard.expr(name="balanced", when=GATE_V1, explain="Off by {a}.",
                    vars={"a": E.data("amount")})
    v2 = guard.expr(name="balanced", when=GATE_V2, explain="Off by {a}.",
                    vars={"a": E.data("amount")})
    diff = diff_fingerprints(_machine_fp(v1), _machine_fp(v2))
    assert classify_diff(diff) == "data_law", \
        "a changed verdict tree is a recoverable judgment — holdable"

    # prose and severity are recoverable leaves too: the overlay serves
    # the row's revision's reason, so render stays honest under the hold
    softer = guard.expr(name="balanced", when=GATE_V1,
                        explain="Slightly off by {a}.", severity="warning",
                        vars={"a": E.data("amount")})
    diff = diff_fingerprints(_machine_fp(v1), _machine_fp(softer))
    assert classify_diff(diff) == "data_law"

    # adding a guard shifts positional structure — machine shape, refused
    two = {"machine": {"actions": {"close": {
        "guards": [_guard_fp(v1), _guard_fp(v2)]}}}}
    diff = diff_fingerprints(_machine_fp(v1), two)
    assert classify_diff(diff) == "code_or_shape"

    # converting check= to expr= necessarily changes the check leaf:
    # one total promote, pilotable forever after
    async def check(r, inp, ctx):  # pragma: no cover - hashed, never run
        return Allow()

    from waymark9 import Guard

    coded = Guard(name="balanced", check=check, explain="Off by {a}.",
                  vars=("a",))
    diff = diff_fingerprints(_machine_fp(coded), _machine_fp(v1))
    assert classify_diff(diff) == "code_or_shape"


# ── the hold: rows advertised AND enforced under the current gate ────────
async def test_judgment_diff_holds_and_the_current_gate_serves():
    e1 = await _boot(make_wb(GATE_V1), drop=True)
    c1 = _client(e1)
    try:
        doc = await _create(c1, "jwbs", "seed", "fund-beta", 0.03)
        assert "close" in doc["actions"]  # 0.03 ≤ 0.05
    finally:
        await c1.aclose()
        await e1.shutdown()

    e2 = await _boot(make_wb(GATE_V2), deploy="propose")
    c2 = _client(e2)
    try:
        rev1, rev2 = await _revisions(e2, "jwb")
        assert rev2.state == "proposed", \
            "a judgment tree diff holds (v8 promoted it totally)"
        assert rev2.data.diff_class == "data_law"
        assert rev2.data.deploy_note is None

        rows = await _rows(e2, "jwb")
        doc = (await c2.get(f"/api/jwbs/{rows['seed'].id}")).json()
        assert "close" in doc["actions"], \
            "the current gate serves from its stored tree; the resident " \
            "(proposed) gate would refuse 0.03"
        res = await _post(c2, doc["self"] + "/-/close")
        assert res.status_code == 200, res.text
    finally:
        await c2.aclose()
        await e2.shutdown()


# ── the pilot: one collection, two gates, every row honest ───────────────
async def test_judgment_change_pilots_per_population():
    e1 = await _boot(make_wb(GATE_V1), drop=True)
    c1 = _client(e1)
    try:
        await _create(c1, "jwbs", "alpha-1", "fund-alpha", 0.03)
        await _create(c1, "jwbs", "beta-1", "fund-beta", 0.03)
    finally:
        await c1.aclose()
        await e1.shutdown()

    e2 = await _boot(make_wb(GATE_V2), deploy="propose")
    elena = _client(e2)
    marcus = _client(e2, headers=MARCUS)
    try:
        rev1, rev2 = await _revisions(e2, "jwb")
        res = await _post(elena, f"/api/definitions/{rev2.id}/-/pilot",
                          {"where": {"fund": "fund-alpha"}})
        assert res.status_code == 200, res.text

        rows = await _rows(e2, "jwb")
        assert rows["alpha-1"].law_revision == 2
        assert rows["beta-1"].law_revision == 1

        for client in (elena, marcus):
            alpha = (await client.get(
                f"/api/jwbs/{rows['alpha-1'].id}")).json()
            beta = (await client.get(
                f"/api/jwbs/{rows['beta-1'].id}")).json()
            # the piloted gate refuses alpha, with the piloted reason
            assert "close" not in alpha["actions"]
            assert alpha["unavailable"]["close"]["reason"] == \
                "Out of balance by 0.03."
            assert alpha["meta"]["law_revision"] == 2
            # the current gate allows beta — same collection, same reader
            assert "close" in beta["actions"]
            assert beta["meta"]["law_revision"] == 1

        # enforcement agrees with the advertisement, per row: the same
        # POST is a 409 under the pilot and a 200 under the current law
        res = await _post(elena, f"/api/jwbs/{rows['alpha-1'].id}/-/close")
        assert res.status_code == 409
        assert res.json()["detail"] == "Out of balance by 0.03."
        res = await _post(elena, f"/api/jwbs/{rows['beta-1'].id}/-/close")
        assert res.status_code == 200, res.text

        # and a piloted row can comply with ITS gate: restate under 0.01
        # and the affordance appears
        doc = (await elena.get(f"/api/jwbs/{rows['alpha-1'].id}")).json()
        res = await elena.post(
            doc["self"] + "/-/restate", json={"amount": 0.005},
            headers={"Idempotency-Key": uuid.uuid4().hex,
                     "If-Match": doc["meta"]["etag"]})
        assert res.status_code == 200, res.text
        assert "close" in res.json()["actions"]
    finally:
        await elena.aclose()
        await marcus.aclose()
        await e2.shutdown()


# ── the grandfather: the workflow outlives the judgment ──────────────────
async def test_grandfathered_row_closes_under_its_birth_gate():
    e1 = await _boot(make_wb(GATE_V1, adoption=Never, kind_token="jnwb"),
                     drop=True)
    c1 = _client(e1)
    try:
        doc = await _create(c1, "jnwbs", "feb", "fund-beta", 0.03)
        assert "close" in doc["actions"]
    finally:
        await c1.aclose()
        await e1.shutdown()

    e2 = await _boot(make_wb(GATE_V2, adoption=Never, kind_token="jnwb"))
    c2 = _client(e2)
    try:
        rev1, rev2 = await _revisions(e2, "jnwb")
        assert rev1.state == "grandfathered"
        assert rev2.state == "current"

        # a fresh row is judged by the current (tight) gate…
        doc = await _create(c2, "jnwbs", "mar", "fund-beta", 0.03)
        assert "close" not in doc["actions"]
        assert doc["unavailable"]["close"]["reason"] == \
            "Out of balance by 0.03."
        res = await _post(c2, doc["self"] + "/-/close")
        assert res.status_code == 409

        # …while the February workbook keeps the gate it was born under,
        # in the envelope and at the wire alike
        rows = await _rows(e2, "jnwb")
        feb = (await c2.get(f"/api/jnwbs/{rows['feb'].id}")).json()
        assert feb["meta"]["law_revision"] == 1
        assert "close" in feb["actions"], \
            "v8 would have judged this row by the resident (tight) gate"
        res = await _post(c2, feb["self"] + "/-/close")
        assert res.status_code == 200, res.text

        # the February story's last line, for judgment: its law dies the
        # day its last workbook closes
        rev1_after = next(r for r in await _revisions(e2, "jnwb")
                          if r.data.revision == 1)
        assert rev1_after.state == "superseded"
    finally:
        await c2.aclose()
        await e2.shutdown()


# ── the contrast: code keeps its fate ────────────────────────────────────
async def test_the_same_gate_spelled_as_check_promotes_totally():
    async def loose(r, inp, ctx):
        if abs(r.data.amount) <= 0.05:
            return Allow()
        return Deny(vars={"amount": r.data.amount})

    async def tight(r, inp, ctx):
        if abs(r.data.amount) <= 0.01:
            return Allow()
        return Deny(vars={"amount": r.data.amount})

    e1 = await _boot(make_wb_check(loose), drop=True)
    await e1.shutdown()
    e2 = await _boot(make_wb_check(tight), deploy="propose")
    try:
        rev1, rev2 = await _revisions(e2, "jwbc")
        assert rev2.state == "current"
        assert rev2.data.diff_class == "code_or_shape"
        assert rev2.data.deploy_note == \
            "promoted without hold: diff exceeds data-law"
    finally:
        await e2.shutdown()
