"""The February close closes under February's law (design 7.0 §3,
appendix): supersede-when-empty, end to end.

The March rule change deploys mid-February. February's workbook lives
and dies under revision 1 — no adoption, by domain choice
(``adoption=Never``) — while March's closes under revision 2. Revision
1 is superseded on the day its last workbook closes: laws die when they
are empty, and the log knows the day. The closed row's envelope still
names the law it lived under (history keeps its own law, §5), and the
replay conformance walks the mixed-law history."""
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
from waymark7.testing.conformance import replay_history

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

ELENA = {"X-Principal-Id": "elena", "X-Principal-Display": "Elena V."}

TOL_V1 = "0.05"
TOL_V2 = "0.01"


class WbState(StrEnum):
    OPEN = "open"
    CLOSED = "closed"


def make_wb(tolerance: str):
    class WbData(BaseModel):
        title: str = Field(min_length=1, max_length=80)
        period: str = Field(default="2026-02", max_length=7)
        amount: float = 0.0
        reconciled: bool = Derived(over=("amount",),
                                   within=Tolerance(tolerance))

    class Workbook(Resource):
        kind = "swb"
        State = WbState
        Data = WbData
        initial = WbState.OPEN
        terminal = {WbState.CLOSED}
        summary = "{data.title} · {state.label}"
        filterable = filterable(state=filterable.Eq, period=filterable.Eq)
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


async def _revisions(engine, target_kind="swb"):
    async with engine.storage.session() as s:
        rows, _ = await engine.storage.query(
            s, "definition", filters={"target_kind": target_kind},
            sort="revision", page_size=50, page_number=1)
    return rows


async def test_the_february_close_closes_under_februarys_law():
    # February: two workbooks under revision 1 (the 0.05 law)
    e1 = await _boot(make_wb(TOL_V1), drop=True)
    client = _client(e1)
    try:
        feb_a = (await _post(client, "/api/swbs",
                             {"title": "feb-a", "period": "2026-02",
                              "amount": 0.03})).json()
        feb_b = (await _post(client, "/api/swbs",
                             {"title": "feb-b", "period": "2026-02",
                              "amount": 0.03})).json()
        assert feb_a["data"]["reconciled"] is True
    finally:
        await client.aclose()
        await e1.shutdown()

    # mid-February the March rule deploys; February grandfathers
    e2 = await _boot(make_wb(TOL_V2))
    client = _client(e2)
    try:
        rev1, rev2 = await _revisions(e2)
        assert (rev1.state, rev2.state) == ("grandfathered", "current")

        march = (await _post(client, "/api/swbs",
                             {"title": "march", "period": "2026-03",
                              "amount": 0.03})).json()
        assert march["meta"]["law_revision"] == 2
        assert march["data"]["reconciled"] is False, \
            "March lives under March's law"

        # the February close is judged and computed under February's law
        feb_a_id = feb_a["self"].rsplit("/", 1)[-1]
        feb_b_id = feb_b["self"].rsplit("/", 1)[-1]
        closed_a = (await _post(client,
                                f"/api/swbs/{feb_a_id}/-/close")).json()
        assert closed_a["data"]["reconciled"] is True
        assert closed_a["meta"]["law_revision"] == 1

        # one February workbook still open: revision 1 is law, not history
        rev1, rev2 = await _revisions(e2)
        assert rev1.state == "grandfathered", \
            "a revision with a living row cannot supersede"

        # the last February workbook closes — the law dies that day
        closed_b = (await _post(client,
                                f"/api/swbs/{feb_b_id}/-/close")).json()
        assert closed_b["meta"]["law_revision"] == 1
        rev1, rev2 = await _revisions(e2)
        assert rev1.state == "superseded", \
            "revision 1 superseded the day its last workbook closed"
        async with e2.storage.session() as s:
            t = await e2.storage.last_transition(s, "definition", rev1.id)
        assert t.action == "supersede"
        assert t.actor_type == "system"

        # history keeps its own law (§5): the closed rows' envelopes still
        # name the revision they lived and died under
        doc = (await client.get(f"/api/swbs/{feb_a_id}")).json()
        assert doc["meta"]["law_revision"] == 1
        assert doc["meta"]["law"] == rev1.id
        assert doc["data"]["reconciled"] is True

        # and the replay conformance walks the mixed-law history: every
        # transition legal under the machine of the revision it anchors to
        checked = await replay_history(e2.storage, e2.registry, "swb")
        assert checked >= 5
    finally:
        await client.aclose()
        await e2.shutdown()


async def test_grandfathered_law_survives_a_reboot():
    """The overlay for a grandfathered revision re-installs at boot: its
    rows keep computing from its stored parameters after a restart, and
    the lazy supersede check still fires from the fresh process."""
    e1 = await _boot(make_wb(TOL_V1), drop=True)
    client = _client(e1)
    try:
        feb = (await _post(client, "/api/swbs",
                           {"title": "feb", "period": "2026-02",
                            "amount": 0.03})).json()
        feb_id = feb["self"].rsplit("/", 1)[-1]
    finally:
        await client.aclose()
        await e1.shutdown()

    e2 = await _boot(make_wb(TOL_V2))
    await e2.shutdown()

    # a second boot of the March code: the grandfathered law re-detected
    e3 = await _boot(make_wb(TOL_V2))
    client = _client(e3)
    try:
        rev1, rev2 = await _revisions(e3)
        assert rev1.state == "grandfathered"
        assert ("swb", 1) in e3.invoker.derived.law_overlay, \
            "the row-law overlay re-installs from the stored fingerprint"

        closed = (await _post(client, f"/api/swbs/{feb_id}/-/close")).json()
        assert closed["data"]["reconciled"] is True, \
            "computed under revision 1's stored 0.05 across the reboot"
        rev1, rev2 = await _revisions(e3)
        assert rev1.state == "superseded"
    finally:
        await client.aclose()
        await e3.shutdown()
