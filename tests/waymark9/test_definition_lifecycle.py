"""The definition lifecycle (design 7.0 §1): the deploy is a workflow.

Auto mode keeps the v6 boot revise byte-for-byte (single ``revise``
transition — the design's "propose+promote in one recorded breath" ideal
is deliberately NOT taken, so the forked baseline's transition-count and
last-transition assertions stay true; recorded deviation). Propose mode
holds a data-law diff at ``proposed`` while the §1 overlay keeps serving
the current law from stored parameters — proved here by computing a fact
under the OLD Tolerance while the NEW code is resident. Promote is
four-eyes guarded against the proposer (the deploy actor), flips the
served law, and runs the standard stale-by-definition backfill; withdraw
records its reason (§5) and leaves the overlay standing; a diff that
exceeds data-law auto-promotes with the recorded marker.

Boots are simulated the test_definition/test_backfill way: factory-made
resource classes whose two calls with the same arguments yield
byte-identical declarations, one engine per version, same database.
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
from waymark9 import Ctx, Derived, Resource, Safety, Tolerance, action, filterable
from waymark9.server.bus import InProcessBus
from waymark9.server.engine import header_principal
from waymark9.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

ELENA = {"X-Principal-Id": "elena", "X-Principal-Display": "Elena V."}
# the boot's proposer: the system deploy actor (definitions.DEPLOY)
DEPLOY_H = {"X-Principal-Id": "waymark9-deploy", "X-Principal-Type": "system",
            "X-Principal-Display": "Deploy"}

TOL_V1 = "0.05"
TOL_V2 = "0.01"

# textually distinct fns for the diff-exceeds-data-law case: an fn source
# change is code, never overlayable data
RECON_FN_V1 = lambda amount: abs(amount) <= 0.05   # noqa: E731
RECON_FN_V2 = lambda amount: abs(amount) <= 0.01   # noqa: E731


class WbState(StrEnum):
    OPEN = "open"
    CLOSED = "closed"


def make_wb(tolerance: str):
    """A fresh ``lwb`` kind whose reconciliation Tolerance is THE law
    under test — a Tolerance literal change is the canonical data-law
    diff (design §4: all data, all holdable)."""

    class WbData(BaseModel):
        title: str = Field(min_length=1, max_length=80)
        amount: float = 0.0
        reconciled: bool = Derived(over=("amount",),
                                   within=Tolerance(tolerance))

    class Workbook(Resource):
        kind = "lwb"
        State = WbState
        Data = WbData
        initial = WbState.OPEN
        terminal = {WbState.CLOSED}
        summary = "{data.title} · {state.label}"
        filterable = filterable(state=filterable.Eq,
                                reconciled=filterable.Eq)

        @action(from_=WbState.OPEN, to=WbState.CLOSED,
                safety=Safety(idempotent=True, reversible=False,
                              confirm=True,
                              consequence="Closes the workbook."))
        async def close(self, inp: None, ctx: Ctx) -> None:
            pass

    return Workbook


def make_wb_fn(fn):
    """The same kind with the tolerance as an ``fn=`` body — a source
    change the data-law gate must refuse to hold."""

    class WbData(BaseModel):
        title: str = Field(min_length=1, max_length=80)
        amount: float = 0.0
        reconciled: bool = Derived(over=("amount",), fn=fn)

    class Workbook(Resource):
        kind = "lwb"
        State = WbState
        Data = WbData
        initial = WbState.OPEN
        terminal = {WbState.CLOSED}
        summary = "{data.title} · {state.label}"
        filterable = filterable(state=filterable.Eq,
                                reconciled=filterable.Eq)

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


async def _create(client, title, amount):
    res = await _post(client, "/api/lwbs", {"title": title, "amount": amount})
    assert res.status_code == 201, res.text
    return res.json()


async def _revisions(engine, target_kind="lwb"):
    async with engine.storage.session() as s:
        rows, _ = await engine.storage.query(
            s, "definition", filters={"target_kind": target_kind},
            sort="revision", page_size=50, page_number=1)
    return rows


async def _create_transition(engine, row_id):
    """The creating transition of one definition row (from_state == "") —
    the test_definition pattern; the LAST transition may be the boot's
    own `settle` after a backfill."""
    async with engine.storage.session() as s:
        rows = await engine.storage.transitions_since(
            s, 0, kinds=["definition"], limit=500)
    return next(t for t in rows
                if t.resource_id == row_id and t.from_state == "")


async def _rows(engine, kind="lwb"):
    async with engine.storage.session() as s:
        rows, _ = await engine.storage.query(
            s, kind, filters={}, sort=None, page_size=100, page_number=1)
    return {r.data.title: r for r in rows}


async def _seed_v1(wb_factory=make_wb, tol=TOL_V1):
    """Boot 1 (auto, fresh db): two workbooks under the 0.05 law."""
    e1 = await _boot(wb_factory(tol), drop=True)
    client = _client(e1)
    try:
        near = await _create(client, "near", 0.03)   # inside 0.05
        far = await _create(client, "far", 0.2)      # outside both laws
        assert near["data"]["reconciled"] is True
        assert far["data"]["reconciled"] is False
    finally:
        await client.aclose()
        await e1.shutdown()
    return near, far


# ── auto mode: v6 behavior, unchanged ────────────────────────────────────
async def test_auto_mode_revise_is_the_v6_single_breath():
    """Auto keeps the forked behavior exactly: one `revise` create +
    one `supersede`, new law current, rows backfilled — the design's
    two-transition ideal is deliberately not taken (recorded deviation,
    design §1's own escape hatch for exactly this)."""
    await _seed_v1()
    e2 = await _boot(make_wb(TOL_V2))
    try:
        rev1, rev2 = await _revisions(e2)
        assert (rev1.state, rev2.state) == ("superseded", "current")
        assert e2.current_law("lwb") == rev2.id
        assert rev2.data.diff_class == "data_law", \
            "the classifier's verdict is data on the revision either way"
        assert rev2.data.deploy_note is None, \
            "auto mode needs no marker; nothing was ever held"
        created = await _create_transition(e2, rev2.id)
        assert created.action == "revise", \
            "auto mode stays the single recorded revise (v6 exactly)"
        # law B (0.01) governs every stored row after boot (§4 backfill)
        rows = await _rows(e2)
        assert rows["near"].data.reconciled is False
        assert rows["far"].data.reconciled is False
    finally:
        await e2.shutdown()


# ── propose mode: the hold and THE overlay proof ─────────────────────────
async def test_propose_mode_holds_tolerance_diff_and_serves_old_law():
    """The load-bearing piece (design §1): the resident Python objects
    are the NEW law (Tolerance 0.01), but the engine serves the CURRENT
    one (0.05) — a row created under the new code computes its fact under
    the OLD tolerance, from the current revision's stored parameters."""
    await _seed_v1()
    e2 = await _boot(make_wb(TOL_V2), deploy="propose")
    client = _client(e2)
    try:
        rev1, rev2 = await _revisions(e2)
        assert (rev1.state, rev2.state) == ("current", "proposed")
        assert rev2.data.held is True
        assert rev2.data.diff_class == "data_law"
        assert e2.current_law("lwb") == rev1.id, \
            "the served law is still revision 1"
        assert e2.registry["lwb"].proposed_law == rev2.id
        async with e2.storage.session() as s:
            created = await e2.storage.last_transition(s, "definition",
                                                       rev2.id)
        assert created.action == "propose", \
            "a held revision's creation is logged as the propose it is"

        # stored rows untouched: no backfill ran, old law's values stand
        rows = await _rows(e2)
        assert rows["near"].data.reconciled is True

        # THE overlay proof: new code resident (0.01), fact computed
        # under the old tolerance (0.05)
        doc = await _create(client, "fresh", 0.03)
        assert doc["data"]["reconciled"] is True, \
            "0.03 reconciles only under the CURRENT tolerance (0.05) — " \
            "the resident declaration says 0.01; the overlay served the law"
        assert doc["meta"]["law"] == rev1.id
        assert doc["meta"]["law_revision"] == 1

        # a write on an existing row recomputes under the old law too
        res = await _post(client, f"/api/lwbs/{rows['near'].id}/-/close")
        assert res.status_code == 200, res.text
        assert res.json()["data"]["reconciled"] is True

        # the proposal's envelope advertises the lifecycle: a human sees
        # promote/measure/withdraw offered; the proposer (deploy actor)
        # sees promote honestly unavailable with the four-eyes sentence
        env = (await client.get(f"/api/definitions/{rev2.id}")).json()
        assert {"promote", "measure", "withdraw"} <= set(env["actions"])
        deploy = _client(e2, headers=DEPLOY_H)
        try:
            deploy_env = (await deploy.get(
                f"/api/definitions/{rev2.id}")).json()
            assert "promote" not in deploy_env["actions"]
            assert "someone else" in \
                deploy_env["unavailable"]["promote"]["reason"]
        finally:
            await deploy.aclose()
    finally:
        await client.aclose()
        await e2.shutdown()


async def test_promote_flips_law_backfills_and_four_eyes_bars_proposer():
    """Promote (design §1): four-eyes on ``of="propose"`` refuses the
    proposer (the deploy actor); a human promotes via the ordinary invoke
    path. On promote the overlay drops, the resident law becomes served
    law, and the standard stale-by-definition backfill runs and settles."""
    await _seed_v1()
    e2 = await _boot(make_wb(TOL_V2), deploy="propose")
    elena = _client(e2)
    deploy = _client(e2, headers=DEPLOY_H)
    try:
        rev1, rev2 = await _revisions(e2)

        # the proposer cannot promote what it proposed (the law's own E3)
        refused = await _post(deploy, f"/api/definitions/{rev2.id}/-/promote")
        assert refused.status_code == 409, refused.text
        assert "someone else" in refused.json()["detail"]

        # any human passes
        res = await _post(elena, f"/api/definitions/{rev2.id}/-/promote")
        assert res.status_code == 200, res.text
        assert res.json()["state"] == "current"

        rev1, rev2 = await _revisions(e2)
        assert (rev1.state, rev2.state) == ("superseded", "current")
        assert e2.current_law("lwb") == rev2.id, "the law flipped"
        assert e2.registry["lwb"].proposed_law is None
        async with e2.storage.session() as s:
            superseded = await e2.storage.last_transition(s, "definition",
                                                          rev1.id)
            promoted_row = await e2.storage.load(s, "definition", rev2.id)
        assert superseded.action == "supersede"
        assert promoted_row.data.backfill_pending is None, \
            "the backfill settled — the §4 marker cleared by `settle`"

        # the backfill recomputed stored rows under the promoted law
        rows = await _rows(e2)
        assert rows["near"].data.reconciled is False, \
            "0.03 no longer reconciles under the promoted 0.01"

        # and new writes serve the new law, anchored to it
        doc = await _create(elena, "post", 0.03)
        assert doc["data"]["reconciled"] is False
        assert doc["meta"]["law"] == rev2.id
        assert doc["meta"]["law_revision"] == 2
    finally:
        await elena.aclose()
        await deploy.aclose()
        await e2.shutdown()


async def test_withdraw_records_reason_and_overlay_stays():
    """Withdraw (design §1/§5): the honest exit — a transition whose
    reason is the recorded input. The overlay STAYS: the process keeps
    serving the current law while the withdrawn code sits resident;
    redeploying the current law's code clears it naturally."""
    await _seed_v1()
    e2 = await _boot(make_wb(TOL_V2), deploy="propose")
    client = _client(e2)
    try:
        rev1, rev2 = await _revisions(e2)
        res = await _post(client, f"/api/definitions/{rev2.id}/-/withdraw",
                          {"reason": "not this close period"})
        assert res.status_code == 200, res.text
        assert res.json()["state"] == "withdrawn"
        async with e2.storage.session() as s:
            t = await e2.storage.last_transition(s, "definition", rev2.id)
        assert t.action == "withdraw"
        assert t.inputs == {"reason": "not this close period"}, \
            "the reason is the transition's recorded input (design §5)"

        # the overlay stays: current law continues to govern, recorded as
        # acceptable — the resident (withdrawn) code never serves
        assert e2.current_law("lwb") == rev1.id
        doc = await _create(client, "after-withdraw", 0.03)
        assert doc["data"]["reconciled"] is True, \
            "still the 0.05 law: withdrawing does not adopt the proposal"
    finally:
        await client.aclose()
        await e2.shutdown()

    # redeploying old code clears the hold naturally: fingerprint matches
    # current, no revision minted, the withdrawn row stays history
    e3 = await _boot(make_wb(TOL_V1))
    try:
        revs = await _revisions(e3)
        assert [r.data.revision for r in revs] == [1, 2]
        assert [r.state for r in revs] == ["current", "withdrawn"]
        assert e3.current_law("lwb") == revs[0].id
        assert e3.registry["lwb"].proposed_law is None
        assert not e3.invoker.derived.overlay, \
            "matching fingerprints install no overlay"
    finally:
        await e3.shutdown()


async def test_diff_exceeding_data_law_auto_promotes_with_marker():
    """Propose mode's pilot-gate philosophy applied at propose time
    (design §1/§4): a changed fn *source* is code — one Python function
    resident in one process — so the boot promotes totally, with the
    marker recorded on the revise."""
    await _seed_v1(wb_factory=make_wb_fn, tol=RECON_FN_V1)
    e2 = await _boot(make_wb_fn(RECON_FN_V2), deploy="propose")
    try:
        rev1, rev2 = await _revisions(e2)
        assert (rev1.state, rev2.state) == ("superseded", "current"), \
            "no hold: the diff exceeds data-law"
        assert rev2.data.diff_class == "code_or_shape"
        assert rev2.data.deploy_note == \
            "promoted without hold: diff exceeds data-law"
        assert rev2.data.held is False
        assert e2.current_law("lwb") == rev2.id
        assert e2.registry["lwb"].proposed_law is None
        assert not e2.invoker.derived.overlay
        created = await _create_transition(e2, rev2.id)
        assert created.action == "revise"
        assert created.inputs is not None and \
            created.inputs.get("deploy_note") == rev2.data.deploy_note, \
            "the marker rides the revise transition's recorded inputs (§5)"
        # promoted totally means backfilled totally
        rows = await _rows(e2)
        assert rows["near"].data.reconciled is False
    finally:
        await e2.shutdown()
