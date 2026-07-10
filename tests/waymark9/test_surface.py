"""The decision surface is a resource (design 6.0 §4).

A ``Surface`` is a declared composition — anchor, members (declared link
rels of the anchor, hand-templated or edge-cited), showcased actions,
attention filter. Three things make it a resource: it is fingerprinted
(a definition row per revision, ``target_kind="surface:{name}"``, revised
across boots with a diff exactly like a kind), it is grantable (a grant
naming ``surface:{name}`` gates the route; anchor ownership opens it;
member visibility still applies per-kind underneath), and it is
discoverable (the well-known document lists surfaces; anchor envelopes
link to the surfaces anchored on them).
"""
from __future__ import annotations

import os
import uuid
from datetime import date
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark9
from waymark9 import (
    Ctx,
    DefinitionError,
    Member,
    On,
    Related,
    Resource,
    Safety,
    Surface,
    action,
    filterable,
    link,
)
from waymark9.server.bus import InProcessBus
from waymark9.server.engine import header_principal
from waymark9.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

DANA = {"X-Principal-Id": "dana", "X-Principal-Display": "Dana K."}


# ── the fixture app: a close-review-shaped anchor with two members ───────
class OneState(StrEnum):
    OPEN = "open"


class SEventData(BaseModel):
    name: str = Field(default="event", max_length=80)
    date: date


class SEvent(Resource):
    kind = "sevent"
    State = OneState
    Data = SEventData
    initial = OneState.OPEN
    summary = "{data.name} · {state.label}"
    filterable = filterable(state=filterable.Eq, date=filterable.Range)


class SNoteData(BaseModel):
    work_id: str = Field(max_length=64)
    text: str = Field(default="", max_length=200)


class SNote(Resource):
    kind = "snote"
    State = OneState
    Data = SNoteData
    initial = OneState.OPEN
    summary = "note: {data.text} · {state.label}"
    filterable = filterable(state=filterable.Eq, work_id=filterable.Eq)


class WorkState(StrEnum):
    DRAFT = "draft"
    PREPARED = "prepared"
    REVIEWED = "reviewed"


_period = Related("sevent", on=(
    On(ours="start_date", op="<=", theirs="date"),
    On(ours="end_date", op=">=", theirs="date"),
))


class SWorkData(BaseModel):
    fund: str = Field(max_length=40)
    period: str = Field(max_length=20)
    start_date: date
    end_date: date


class SWork(Resource):
    kind = "swork"
    State = WorkState
    Data = SWorkData
    initial = WorkState.DRAFT
    terminal = {WorkState.REVIEWED}
    summary = "{data.fund} {data.period} · {state.label}"
    filterable = filterable(state=filterable.Eq | filterable.In,
                            fund=filterable.Eq,
                            start_date=filterable.Range,
                            end_date=filterable.Range)
    links = (
        link("events", edge=_period, embed=True,
             summary="Events in the period"),
        link("notes", kind="snote_collection", href="/snotes?work_id={id}"),
    )

    @action(from_=WorkState.DRAFT, to=WorkState.PREPARED,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Prepare"))
    async def prepare(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=WorkState.PREPARED, to=WorkState.REVIEWED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The close is signed off."),
            display=dict(label="Review"))
    async def review(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=WorkState.PREPARED, to=WorkState.DRAFT,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Reject"))
    async def reject(self, inp: None, ctx: Ctx) -> None:
        pass


def make_close_review(table=("name", "date")):
    """A fresh surface class per call — the test_definition factory
    discipline: identical arguments yield identical fingerprints, so the
    tests can stage deploys."""

    class CloseReview(Surface):
        name = "close-review"
        anchor = "swork"
        title = "Close review — {anchor.data.fund} {anchor.data.period}"
        members = (Member("events", table=table), Member("notes"))
        showcase = ("prepare", "review", "reject")
        attention = {"state": "prepared"}

    return CloseReview


def _engine(surfaces, **kw):
    return waymark9.Engine(resources=[SWork, SEvent, SNote],
                           surfaces=surfaces, storage=TEST_DSN,
                           principal=header_principal, services=None,
                           bus=InProcessBus(), **kw)


async def _boot(*, drop=False, surface=None, **kw):
    engine = _engine([surface or make_close_review()], **kw)
    if drop:
        await engine.storage.drop_all()
    await engine.startup()
    return engine


def _client(engine, headers=DANA):
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    return AsyncClient(transport=ASGITransport(app=app),
                       base_url="http://t", headers=headers)


async def _post(client, href, body):
    res = await client.post(href, json=body,
                            headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    return res.json()


async def _act(client, self_href, act, body=None):
    """Invoke with the current etag riding If-Match — fenced actions
    (request_access) demand it; the others ignore it."""
    doc = (await client.get(self_href)).json()
    res = await client.post(f"{self_href}/-/{act}", json=body,
                            headers={"Idempotency-Key": uuid.uuid4().hex,
                                     "If-Match": doc["meta"]["etag"]})
    assert res.status_code == 200, res.text
    return res.json()


async def _definitions(engine, target_kind):
    async with engine.storage.session() as s:
        rows, _ = await engine.storage.query(
            s, "definition", filters={"target_kind": target_kind},
            sort="revision", page_size=100, page_number=1)
    return rows


async def _seed(client):
    """One workbook, one in-period event, one out, one note each way."""
    work = await _post(client, "/api/sworks",
                       {"fund": "growth", "period": "2026-01",
                        "start_date": "2026-01-01",
                        "end_date": "2026-01-31"})
    await _post(client, "/api/sevents",
                {"name": "wire in", "date": "2026-01-15"})
    await _post(client, "/api/sevents",
                {"name": "far away", "date": "2026-03-02"})
    wid = work["self"].rsplit("/", 1)[-1]
    await _post(client, "/api/snotes", {"work_id": wid, "text": "check"})
    await _post(client, "/api/snotes", {"work_id": "someone-else",
                                        "text": "other"})
    return work


# ── (a) assembly refusals: the composition is checked where every kind
# is known, never at first request ───────────────────────────────────────
def test_unknown_anchor_kind_is_refused():
    class Bad(Surface):
        name = "bad-anchor"
        anchor = "nonesuch"

    with pytest.raises(DefinitionError, match="not registered"):
        _engine([Bad])


def test_member_must_name_a_declared_link_rel():
    class Bad(Surface):
        name = "bad-member"
        anchor = "swork"
        members = (Member("ghost"),)

    with pytest.raises(DefinitionError,
                       match="composes what the law relates"):
        _engine([Bad])


def test_member_table_columns_are_target_data_fields():
    class Bad(Surface):
        name = "bad-table"
        anchor = "swork"
        members = (Member("events", table=("name", "nope")),)

    with pytest.raises(DefinitionError, match="not a data field"):
        _engine([Bad])


def test_showcase_must_name_anchor_actions():
    class Bad(Surface):
        name = "bad-showcase"
        anchor = "swork"
        showcase = ("fly",)

    with pytest.raises(DefinitionError, match="showcase names unknown"):
        _engine([Bad])


def test_attention_params_validate_against_the_query_grammar():
    class BadParam(Surface):
        name = "bad-attention"
        anchor = "swork"
        attention = {"bogus": "1"}

    with pytest.raises(DefinitionError, match="not in the query grammar"):
        _engine([BadParam])

    class BadValue(Surface):
        name = "bad-attention-value"
        anchor = "swork"
        attention = {"state": "nonesuch"}

    with pytest.raises(DefinitionError, match="admits"):
        _engine([BadValue])


def test_duplicate_surface_names_are_refused():
    with pytest.raises(DefinitionError, match="duplicate surface name"):
        _engine([make_close_review(), make_close_review(table=("date",))])


def test_surface_names_are_kebab_case():
    with pytest.raises(DefinitionError, match="kebab-case"):
        class Bad(Surface):
            name = "Not Kebab"
            anchor = "swork"


def test_title_templates_speak_only_anchor_paths():
    with pytest.raises(DefinitionError, match="title template"):
        class Bad(Surface):
            name = "bad-title"
            anchor = "swork"
            title = "Close — {data.fund}"


# ── (b) the surface envelope ─────────────────────────────────────────────
async def test_surface_envelope_composes_the_decision():
    engine = await _boot(drop=True)
    client = _client(engine)
    try:
        work = await _seed(client)
        wid = work["self"].rsplit("/", 1)[-1]
        res = await client.get(f"/api/surfaces/close-review/{wid}")
        assert res.status_code == 200, res.text
        doc = res.json()

        # an ordinary envelope: the anchor's data/state under the
        # surface's own kind and self
        assert doc["kind"] == "surface:close-review"
        assert doc["self"] == f"/api/surfaces/close-review/{wid}"
        assert doc["state"] == "draft"
        assert doc["data"]["fund"] == "growth"
        assert doc["summary"] == "growth 2026-01 · Draft"
        assert doc["display"]["title"] == "Close review — growth 2026-01"

        # members arrive embedded: the link object plus items and total,
        # fetched through the same grammar the real collection serves
        events = doc["embedded"]["events"]
        assert events["href"] == \
            "/api/sevents?date_gte=2026-01-01&date_lte=2026-01-31"
        assert events["total"] == 1
        assert [i["data"]["name"] for i in events["items"]] == ["wire in"]
        # the declared columns ride as an x-display hint, never a filter:
        # the items keep their full (visibility-governed) data
        assert events["x-display"]["columns"] == ["name", "date"]
        notes = doc["embedded"]["notes"]
        assert notes["total"] == 1
        assert notes["items"][0]["data"]["text"] == "check"
        assert "x-display" not in notes, "no table= declared, no hint"

        # showcased actions render first, flagged; out-of-state showcase
        # entries stay honestly unavailable
        assert list(doc["actions"])[0] == "prepare"
        assert doc["actions"]["prepare"]["showcased"] is True
        assert doc["actions"]["prepare"]["href"] == \
            f"/api/sworks/{wid}/-/prepare", \
            "the affordances are the anchor's — invocation lands there"
        assert {"review", "reject"} <= set(doc["unavailable"])

        # meta: the anchor's etag, the SURFACE's own law
        anchor = (await client.get(work["self"])).json()
        assert doc["meta"]["etag"] == anchor["meta"]["etag"]
        assert res.headers["ETag"] == anchor["meta"]["etag"]
        assert doc["meta"]["law"] == \
            engine.current_law("surface:close-review")
        assert doc["meta"]["law"] != engine.current_law("swork")
        assert doc["meta"]["law_revision"] == 1
        assert doc["links"]["anchor"]["href"] == work["self"]
    finally:
        await client.aclose()
        await engine.shutdown()


async def test_showcased_ordering_follows_the_declaration():
    engine = await _boot(drop=True)
    client = _client(engine)
    try:
        work = await _seed(client)
        wid = work["self"].rsplit("/", 1)[-1]
        await _act(client, work["self"], "prepare")
        doc = (await client.get(f"/api/surfaces/close-review/{wid}")).json()
        # showcase order (prepare, review, reject) filtered to what the
        # state affords: review then reject, both flagged
        assert list(doc["actions"]) == ["review", "reject"]
        assert all(doc["actions"][a]["showcased"] is True
                   for a in ("review", "reject"))
        assert "prepare" in doc["unavailable"]
    finally:
        await client.aclose()
        await engine.shutdown()


# ── (c) member visibility: absent, not leaked ────────────────────────────
async def test_member_visibility_governs_the_embedded_members():
    engine = await _boot(drop=True, member_visibility="granted")
    dana = _client(engine)
    sam = _client(engine, {"X-Principal-Id": "sam",
                           "X-Principal-Display": "Sam"})
    try:
        work = await _seed(dana)
        wid = work["self"].rsplit("/", 1)[-1]
        href = f"/api/surfaces/close-review/{wid}"

        # ungranted: the composition is the sensitive thing — 403 with
        # the negotiation remedy, exactly like an ungranted action
        refused = await sam.get(href)
        assert refused.status_code == 403
        assert "grant.request_access" in refused.json()["remedies"]

        # a grant names the surface (the composed view IS the grant
        # target) plus one anchor field; the member kinds stay ungranted
        minted = await _post(sam, "/api/grants",
                             {"holder_name": "Sam", "holder_kind": "member",
                              "holder_id": "sam"})
        await _act(sam, minted["self"], "request_access", {
            "task": "Review the close.",
            "requested_fields": {"swork": {"fund": "clear",
                                           "summary": "clear"}},
            "requested_actions": {"surface:close-review": {"view": "open"}},
            "requested_hours": 2,
        })
        await _act(dana, minted["self"], "approve")

        doc = (await sam.get(href)).json()
        # anchor fields project per-kind: granted fund renders, period
        # never existed for sam
        assert doc["data"] == {"fund": "growth"}
        # ungranted member kinds are EMPTY under the same pushdown the
        # real collection route runs — count included, nothing leaked
        assert doc["embedded"]["events"]["items"] == []
        assert doc["embedded"]["events"]["total"] == 0
        assert doc["embedded"]["notes"]["items"] == []
        assert doc["embedded"]["notes"]["total"] == 0

        # the anchor's owner needs no surface grant: ownership opens the
        # view, and their members carry their own rows
        own = (await dana.get(href)).json()
        assert own["embedded"]["events"]["total"] == 1
        assert own["data"]["period"] == "2026-01"
    finally:
        await sam.aclose()
        await dana.aclose()
        await engine.shutdown()


# ── (d) the fingerprint: changing a surface across boots is a revise ─────
async def test_surface_change_is_a_revise_with_a_diff():
    e1 = await _boot(drop=True)
    first = await _definitions(e1, "surface:close-review")
    assert [r.data.revision for r in first] == [1]
    assert first[0].data.diff is None
    await e1.shutdown()

    # an identical reboot writes nothing (fresh class, same declaration)
    e2 = await _boot(surface=make_close_review())
    assert [r.data.revision
            for r in await _definitions(e2, "surface:close-review")] == [1]
    await e2.shutdown()

    # reordering/removing member columns IS a change of what the
    # decision-maker is shown: revision 2, current, with a diff — and
    # the anchor kind's own definition does NOT revise
    e3 = await _boot(surface=make_close_review(table=("date",)))
    try:
        rows = await _definitions(e3, "surface:close-review")
        assert [(r.data.revision, r.state) for r in rows] == \
            [(1, "superseded"), (2, "current")]
        diff = rows[1].data.diff
        paths = [e["path"] for k in ("added", "removed", "changed")
                 for e in diff[k]]
        assert any(p.startswith("members.") for p in paths), paths
        assert e3.current_law("surface:close-review") == rows[1].id
        assert [r.data.revision
                for r in await _definitions(e3, "swork")] == [1], \
            "the anchor's law did not move; only the surface's did"

        # the deploy row records the moved surface hash
        reg = await _definitions(e3, "__registry__")
        reg_paths = [e["path"] for e in reg[-1].data.diff["changed"]]
        assert "surfaces.close-review" in reg_paths

        # the revise is a transition in the same log as everything else
        async with e3.storage.session() as s:
            t = await e3.storage.last_transition(s, "definition",
                                                 rows[1].id)
        assert t.action == "revise"
        assert t.actor_id == "waymark9-deploy"
    finally:
        await e3.shutdown()


# ── (e) discovery: the well-known document and the anchor's link ─────────
async def test_surfaces_are_discoverable():
    engine = await _boot(drop=True)
    client = _client(engine)
    try:
        wk = (await client.get("/api/.well-known/waymark")).json()
        entry = wk["surfaces"]["close-review"]
        assert entry["anchor"] == "swork"
        assert entry["collection"] == "/api/sworks"
        assert entry["href"] == "/api/surfaces/close-review/{anchor_id}"
        assert entry["attention"] == {"state": "prepared"}
        assert "surface:close-review" not in wk["kinds"], \
            "a surface is not a kind — no storage, no machine, no rows"

        work = await _seed(client)
        wid = work["self"].rsplit("/", 1)[-1]
        anchor = (await client.get(work["self"])).json()
        sl = anchor["links"]["surface:close-review"]
        assert sl["href"] == f"/api/surfaces/close-review/{wid}"
        assert sl["kind"] == "surface:close-review"
    finally:
        await client.aclose()
        await engine.shutdown()


# ── (f) 404s ─────────────────────────────────────────────────────────────
async def test_unknown_surface_and_anchor_404():
    engine = await _boot(drop=True)
    client = _client(engine)
    try:
        assert (await client.get(
            "/api/surfaces/nonesuch/abc")).status_code == 404
        assert (await client.get(
            "/api/surfaces/close-review/nonesuch")).status_code == 404
    finally:
        await client.aclose()
        await engine.shutdown()


# ── (g) the grant gates the route for token principals too ───────────────
async def test_agent_grant_gates_the_surface_route():
    engine = await _boot(drop=True)
    dana = _client(engine)
    try:
        work = await _seed(dana)
        wid = work["self"].rsplit("/", 1)[-1]
        href = f"/api/surfaces/close-review/{wid}"

        minted = await _post(dana, "/api/grants", {"holder_name": "Robo"})
        token = minted["data"]["token"]
        agent = _client(engine, {"Authorization": f"Bearer {token}"})

        refused = await agent.get(href)
        assert refused.status_code == 403, refused.text

        await _act(agent, minted["self"], "request_access", {
            "task": "Watch the close.",
            "requested_actions": {"surface:close-review": {"view": "open"}},
            "requested_hours": 2,
        })
        await _act(dana, minted["self"], "approve")

        doc = (await agent.get(href)).json()
        assert doc["kind"] == "surface:close-review"
        # the grant opened the composition; per-kind visibility still
        # governs every field — nothing was granted clear, so the data
        # namespace is honestly empty
        assert doc["data"] == {}
        assert all(i["data"] == {}
                   for i in doc["embedded"]["events"]["items"])
        await agent.aclose()
    finally:
        await dana.aclose()
        await engine.shutdown()
