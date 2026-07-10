"""The pilot is a population (design 7.0 §3): the law binds rows.

The §3 tolerance proof: one collection, fund-alpha's rows reconciled
under the piloted 0.01, fund-beta's under the current 0.05, each
envelope's ``meta.law_revision`` honest — for *everyone* (row-scoped law
is what keeps four-eyes coherent; there is nothing reader-shaped to
test, which is the point). Creates route by the population's claim on
the validated input; ``after=True`` grandfathers existing rows; the
population declaration is the pilot transition's recorded input (§5);
piloting is four-eyes guarded with propose; withdraw returns the
population to the current law.

Boots are simulated the test_definition_lifecycle way: factory-made
resource classes, one engine per version, same database.
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
from waymark8 import Ctx, Derived, Resource, Safety, Tolerance, action, filterable
from waymark8.server.bus import InProcessBus
from waymark8.server.engine import header_principal
from waymark8.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

ELENA = {"X-Principal-Id": "elena", "X-Principal-Display": "Elena V."}
MARCUS = {"X-Principal-Id": "marcus", "X-Principal-Display": "Marcus T."}
DEPLOY_H = {"X-Principal-Id": "waymark8-deploy", "X-Principal-Type": "system",
            "X-Principal-Display": "Deploy"}

TOL_V1 = "0.05"
TOL_V2 = "0.01"


class WbState(StrEnum):
    OPEN = "open"
    CLOSED = "closed"


def make_wb(tolerance: str):
    """A workbook kind with a promoted ``fund`` field — the §3 story's
    population axis — and a Tolerance-derived fact, THE canonical
    data-law diff."""

    class WbData(BaseModel):
        title: str = Field(min_length=1, max_length=80)
        fund: str = Field(default="fund-beta", max_length=40)
        amount: float = 0.0
        reconciled: bool = Derived(over=("amount",),
                                   within=Tolerance(tolerance))

    class Workbook(Resource):
        kind = "pwb"
        State = WbState
        Data = WbData
        initial = WbState.OPEN
        terminal = {WbState.CLOSED}
        summary = "{data.title} · {state.label}"
        filterable = filterable(state=filterable.Eq, fund=filterable.Eq,
                                reconciled=filterable.Eq)

        @action(from_=WbState.OPEN, to=WbState.CLOSED,
                safety=Safety(idempotent=True, reversible=False,
                              confirm=True,
                              consequence="Closes the workbook."))
        async def close(self, inp: None, ctx: Ctx) -> None:
            pass

    return Workbook


async def _boot(cls, *, deploy: str = "auto", drop: bool = False):
    engine = waymark8.Engine(resources=[cls], storage=TEST_DSN,
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


async def _create(client, title, fund, amount):
    res = await _post(client, "/api/pwbs",
                      {"title": title, "fund": fund, "amount": amount})
    assert res.status_code == 201, res.text
    return res.json()


async def _revisions(engine, target_kind="pwb"):
    async with engine.storage.session() as s:
        rows, _ = await engine.storage.query(
            s, "definition", filters={"target_kind": target_kind},
            sort="revision", page_size=50, page_number=1)
    return rows


async def _rows(engine, kind="pwb"):
    async with engine.storage.session() as s:
        rows, _ = await engine.storage.query(
            s, kind, filters={}, sort=None, page_size=100, page_number=1)
    return {r.data.title: r for r in rows}


async def _seed_v1():
    """Boot 1 (auto, fresh db): one fund-alpha and one fund-beta workbook
    under the 0.05 law — both amounts inside 0.05, outside 0.01."""
    e1 = await _boot(make_wb(TOL_V1), drop=True)
    client = _client(e1)
    try:
        alpha = await _create(client, "alpha-1", "fund-alpha", 0.03)
        beta = await _create(client, "beta-1", "fund-beta", 0.03)
        assert alpha["data"]["reconciled"] is True
        assert beta["data"]["reconciled"] is True
        assert alpha["meta"]["law_revision"] == 1
    finally:
        await client.aclose()
        await e1.shutdown()


async def _piloted_engine():
    """Boot 2 (propose) + Elena pilots fund-alpha: the §3 stage set."""
    e2 = await _boot(make_wb(TOL_V2), deploy="propose")
    elena = _client(e2)
    rev1, rev2 = await _revisions(e2)
    res = await _post(elena, f"/api/definitions/{rev2.id}/-/pilot",
                      {"where": {"fund": "fund-alpha"}})
    assert res.status_code == 200, res.text
    assert res.json()["state"] == "piloted"
    return e2, elena, rev1, rev2


# ── the §3 tolerance proof ───────────────────────────────────────────────
async def test_pilot_population_two_laws_row_honest_for_everyone():
    """One collection, two laws: fund-alpha's rows live under the piloted
    0.01 for Marcus AND Elena; fund-beta's under the current 0.05 —
    every envelope names its row's revision, and the two readers see the
    same truth (the controls stay coherent because the law is the
    row's, design §3)."""
    await _seed_v1()
    e2, elena, rev1, rev2 = await _piloted_engine()
    marcus = _client(e2, headers=MARCUS)
    try:
        rows = await _rows(e2)
        # the pilot restamped and recomputed its existing matches
        assert rows["alpha-1"].law_revision == 2
        assert rows["alpha-1"].data.reconciled is False, \
            "0.03 does not reconcile under the piloted 0.01"
        assert rows["beta-1"].law_revision == 1
        assert rows["beta-1"].data.reconciled is True, \
            "fund-beta stays under the current 0.05"

        for client in (elena, marcus):
            alpha = (await client.get(
                f"/api/pwbs/{rows['alpha-1'].id}")).json()
            beta = (await client.get(
                f"/api/pwbs/{rows['beta-1'].id}")).json()
            assert alpha["meta"]["law_revision"] == 2
            assert alpha["meta"]["law"] == rev2.id
            assert alpha["data"]["reconciled"] is False
            assert beta["meta"]["law_revision"] == 1
            assert beta["meta"]["law"] == rev1.id
            assert beta["data"]["reconciled"] is True

        # a write on a beta row recomputes under ITS law (the current),
        # anchored to it — writes are judged by the row's law
        res = await _post(elena, f"/api/pwbs/{rows['beta-1'].id}/-/close")
        assert res.status_code == 200, res.text
        assert res.json()["data"]["reconciled"] is True
        async with e2.storage.session() as s:
            t = await e2.storage.last_transition(s, "pwb",
                                                 rows["beta-1"].id)
        assert t.defined_by == rev1.id, \
            "the write anchors to the row's law, not the pilot's"
    finally:
        await elena.aclose()
        await marcus.aclose()
        await e2.shutdown()


async def test_creates_route_by_population_claim():
    """Creates are judged by the revision whose population claims the
    validated input (design §3): a fund-alpha create is born under the
    piloted law, a fund-beta create under the current one."""
    await _seed_v1()
    e2, elena, rev1, rev2 = await _piloted_engine()
    try:
        alpha2 = await _create(elena, "alpha-2", "fund-alpha", 0.03)
        assert alpha2["meta"]["law_revision"] == 2
        assert alpha2["meta"]["law"] == rev2.id
        assert alpha2["data"]["reconciled"] is False, \
            "born into the pilot: 0.03 fails the piloted 0.01"
        beta2 = await _create(elena, "beta-2", "fund-beta", 0.03)
        assert beta2["meta"]["law_revision"] == 1
        assert beta2["data"]["reconciled"] is True
    finally:
        await elena.aclose()
        await e2.shutdown()


async def test_pilot_input_recorded_and_four_eyes_bars_proposer():
    """The population declaration IS the pilot transition's recorded
    input (§5), and the proposer (the deploy actor) cannot pilot what it
    proposed — four-eyes covers the trial, not only the adoption."""
    await _seed_v1()
    e2 = await _boot(make_wb(TOL_V2), deploy="propose")
    elena = _client(e2)
    deploy = _client(e2, headers=DEPLOY_H)
    try:
        rev1, rev2 = await _revisions(e2)
        refused = await _post(deploy, f"/api/definitions/{rev2.id}/-/pilot",
                              {"where": {"fund": "fund-alpha"}})
        assert refused.status_code == 409, refused.text
        assert "someone else" in refused.json()["detail"]

        res = await _post(elena, f"/api/definitions/{rev2.id}/-/pilot",
                          {"where": {"fund": "fund-alpha"}})
        assert res.status_code == 200, res.text
        async with e2.storage.session() as s:
            t = await e2.storage.last_transition(s, "definition", rev2.id)
        assert t.action == "pilot"
        assert t.inputs == {"where": {"fund": "fund-alpha"},
                            "after": False}, \
            "the population rides the transition (design §5)"
    finally:
        await elena.aclose()
        await deploy.aclose()
        await e2.shutdown()


async def test_population_after_grandfathers_existing_rows():
    """``Population(after=True)`` (design §3): no existing row moves —
    they finish under the current law — while creates from now on are
    born under the piloted revision."""
    await _seed_v1()
    e2 = await _boot(make_wb(TOL_V2), deploy="propose")
    elena = _client(e2)
    try:
        rev1, rev2 = await _revisions(e2)
        res = await _post(elena, f"/api/definitions/{rev2.id}/-/pilot",
                          {"after": True})
        assert res.status_code == 200, res.text

        rows = await _rows(e2)
        assert rows["alpha-1"].law_revision == 1
        assert rows["alpha-1"].data.reconciled is True, \
            "existing rows keep their law and their values"

        fresh = await _create(elena, "fresh", "fund-beta", 0.03)
        assert fresh["meta"]["law_revision"] == 2
        assert fresh["data"]["reconciled"] is False, \
            "rows created from now on live under the piloted law"
    finally:
        await elena.aclose()
        await e2.shutdown()


async def test_population_validates_against_the_query_grammar():
    """A population names what the collection route could list (design
    §3): an unknown param, a derived fact, a state, and a malformed
    declaration are each refused with field-keyed errors."""
    await _seed_v1()
    e2 = await _boot(make_wb(TOL_V2), deploy="propose")
    elena = _client(e2)
    try:
        rev1, rev2 = await _revisions(e2)
        href = f"/api/definitions/{rev2.id}/-/pilot"

        res = await _post(elena, href, {"where": {"bogus": "x"}})
        assert res.status_code == 422, res.text
        assert "bogus" in res.json()["errors"]

        res = await _post(elena, href, {"where": {"reconciled": True}})
        assert res.status_code == 422, res.text
        assert "derived" in str(res.json()["errors"]["reconciled"])

        res = await _post(elena, href, {"where": {"state": "open"}})
        assert res.status_code == 422, res.text

        # exactly one of where/after
        res = await _post(elena, href,
                          {"where": {"fund": "fund-alpha"}, "after": True})
        assert res.status_code == 422, res.text
        res = await _post(elena, href, {})
        assert res.status_code == 422, res.text
    finally:
        await elena.aclose()
        await e2.shutdown()


async def test_withdrawing_a_pilot_returns_its_rows_to_the_current_law():
    """The honest exit, row half (design §1/§3): withdrawing a piloted
    revision restamps its population back to the current revision and
    recomputes — the served law, which the §1 overlay keeps honest while
    the withdrawn code sits resident."""
    await _seed_v1()
    e2, elena, rev1, rev2 = await _piloted_engine()
    try:
        rows = await _rows(e2)
        assert rows["alpha-1"].law_revision == 2

        res = await _post(elena, f"/api/definitions/{rev2.id}/-/withdraw",
                          {"reason": "the pilot showed too many flips"})
        assert res.status_code == 200, res.text

        rows = await _rows(e2)
        assert rows["alpha-1"].law_revision == 1
        assert rows["alpha-1"].data.reconciled is True, \
            "back under the current 0.05, recomputed"
        # and creates stop routing to the pilot
        fresh = await _create(elena, "alpha-3", "fund-alpha", 0.03)
        assert fresh["meta"]["law_revision"] == 1
        assert fresh["data"]["reconciled"] is True
    finally:
        await elena.aclose()
        await e2.shutdown()


async def test_promoting_a_pilot_adopts_everyone():
    """proposed → piloted → current (design §1): promoting the piloted
    revision makes it the law of every row — the standard backfill IS
    the bulk adopt for an Immediate kind — and the pilot seams clear."""
    await _seed_v1()
    e2, elena, rev1, rev2 = await _piloted_engine()
    try:
        res = await _post(elena, f"/api/definitions/{rev2.id}/-/promote")
        assert res.status_code == 200, res.text
        assert res.json()["state"] == "current"

        rev1, rev2 = await _revisions(e2)
        assert (rev1.state, rev2.state) == ("superseded", "current")
        rows = await _rows(e2)
        assert rows["alpha-1"].law_revision == 2
        assert rows["beta-1"].law_revision == 2, \
            "Immediate adoption: the promote backfill bulk-adopts"
        assert rows["beta-1"].data.reconciled is False
        assert e2.registry["pwb"].piloted_law is None
    finally:
        await elena.aclose()
        await e2.shutdown()


async def test_pilot_survives_a_reboot():
    """The pilot continues across a re-boot of the same code (design §3):
    the boot re-detects the piloted revision by fingerprint, re-installs
    the population and the served-law overlay, and mints nothing."""
    await _seed_v1()
    e2, elena, rev1, rev2 = await _piloted_engine()
    await elena.aclose()
    await e2.shutdown()

    e3 = await _boot(make_wb(TOL_V2), deploy="propose")
    client = _client(e3)
    try:
        revs = await _revisions(e3)
        assert [r.data.revision for r in revs] == [1, 2]
        assert [r.state for r in revs] == ["current", "piloted"]
        rdef = e3.registry["pwb"]
        assert rdef.piloted_law_revision == 2
        assert rdef.piloted_population == {"where": {"fund": "fund-alpha"}}

        # rows keep their pilot stamps and their values
        rows = await _rows(e3)
        assert rows["alpha-1"].law_revision == 2
        assert rows["alpha-1"].data.reconciled is False
        assert rows["beta-1"].law_revision == 1
        assert rows["beta-1"].data.reconciled is True

        # creates still route by the population; the current law still
        # serves fund-beta from stored parameters (the overlay)
        alpha = await _create(client, "alpha-post-boot", "fund-alpha", 0.03)
        assert alpha["meta"]["law_revision"] == 2
        assert alpha["data"]["reconciled"] is False
        beta = await _create(client, "beta-post-boot", "fund-beta", 0.03)
        assert beta["meta"]["law_revision"] == 1
        assert beta["data"]["reconciled"] is True
    finally:
        await client.aclose()
        await e3.shutdown()
