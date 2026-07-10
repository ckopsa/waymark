"""The definition resource and boot-time revise (design §1–§2, §6).

The law is a resource: the first boot mints revision 1 for every
registered kind (plus the ``__registry__`` row), a restart writes
nothing, a changed declaration is a `revise` — new revision created
``current``, old one superseded, one correlation — and a rollback is a
revise that names the revision it reverts to. The fingerprint hashes the
declaration's *text* (§6: the constants are the law), which the
determinism tests hold across processes and the ``OVERDUE_V2`` lambda
demonstrates from the honest side: a textual edit revises the law even
when behavior is identical.
"""
from __future__ import annotations

import os
import subprocess
import sys
import uuid
from enum import StrEnum
from pathlib import Path

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field
from sqlalchemy import func, select

import waymark8
from waymark8 import Allow, Ctx, Deny, Derived, Guard, Resource, Safety, action
from waymark8.core.fingerprint import fingerprint_hash, fingerprint_of
from waymark8.server.bus import InProcessBus
from waymark8.server.definitions import REGISTRY_KIND
from waymark8.server.engine import header_principal
from waymark8.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

OWNER = {"X-Principal-Id": "dana", "X-Principal-Display": "Dana K."}


class DocState(StrEnum):
    DRAFT = "draft"
    APPROVED = "approved"


class NoteState(StrEnum):
    OPEN = "open"
    FILED = "filed"


def make_note(prominence: str = "primary"):
    """A second app kind whose ``nav=`` is parametrized — byte-identical
    for a given argument (a distinct class each call, same declaration), so
    two boots stage a nav-prominence revision. The RHS names the enclosing
    parameter, not the class-local ``nav``, so validation runs on it."""

    class NoteData(BaseModel):
        body: str = Field(min_length=1, max_length=200)

    class Note(Resource):
        kind = "note"
        State = NoteState
        Data = NoteData
        initial = NoteState.OPEN
        terminal = {NoteState.FILED}
        summary = "{data.body}"
        nav = prominence

        @action(from_=NoteState.OPEN, to=NoteState.FILED,
                safety=Safety(idempotent=True, reversible=False, confirm=True,
                              consequence="The note is filed."),
                display=dict(label="File"))
        async def file(self, inp: None, ctx: Ctx) -> None:
            pass

    return Note


# two spellings of the same predicate: textually different, behaviorally
# identical — exactly the edit the fingerprint's source-text semantics
# (core/fingerprint.py docstring) promise to treat as a new law
OVERDUE_V1 = lambda due: due > 1.0     # noqa: E731
OVERDUE_V2 = lambda due: due > 1.00    # noqa: E731

EXPLAIN_V1 = "Only a manager may approve this."
EXPLAIN_V2 = "Approval is reserved for managers."


def make_doc(explain: str, overdue_fn):
    """A fresh ``doc`` kind. Called twice with the same arguments it
    yields byte-identical declarations (distinct class objects), which is
    what lets the tests stage deploys: one engine per version of the
    module, same database."""

    async def _managers_only(r, inp, ctx: Ctx) -> Allow | Deny:
        return Allow() if "manager" in ctx.principal.roles else Deny()

    gate = Guard(name="managers_only", explain=explain,
                 check=_managers_only, reads=("principal",))

    class DocData(BaseModel):
        title: str = Field(min_length=1, max_length=80)
        due: float = 0.0
        overdue: bool = Derived(over=("due",), fn=overdue_fn)

    class Doc(Resource):
        kind = "doc"
        State = DocState
        Data = DocData

        initial = DocState.DRAFT
        terminal = {DocState.APPROVED}

        summary = "{data.title} · {state.label}"

        @action(from_=DocState.DRAFT, to=DocState.APPROVED,
                guards=(gate,),
                safety=Safety(idempotent=True, reversible=False,
                              confirm=True,
                              consequence="The document is locked."),
                display=dict(label="Approve"))
        async def approve(self, inp: None, ctx: Ctx) -> None:
            pass

    return Doc


async def _boot(doc_cls, *, drop: bool = False):
    engine = waymark8.Engine(resources=[doc_cls], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus())
    if drop:
        await engine.storage.drop_all()
    await engine.startup()
    return engine


async def _definitions(engine, target_kind: str | None = None):
    filters = {"target_kind": target_kind} if target_kind else {}
    async with engine.storage.session() as s:
        rows, _ = await engine.storage.query(
            s, "definition", filters=filters, sort="revision",
            page_size=500, page_number=1)
    return rows


async def _transition_count(engine) -> int:
    async with engine.storage.session() as s:
        return (await s.execute(select(func.count()).select_from(
            engine.storage.transitions))).scalar_one()


# ── §2: first boot / restart / revise / rollback ─────────────────────────
async def test_first_boot_mints_revision_one_per_kind():
    """Migration path: an empty database gets revision 1 for every
    registered kind — engine kinds included; the law covers the whole
    registry — plus the ``__registry__`` row tying the deploy together."""
    engine = await _boot(make_doc(EXPLAIN_V1, OVERDUE_V1), drop=True)
    try:
        rows = await _definitions(engine)
        by_kind = {r.data.target_kind: r for r in rows}
        assert set(by_kind) == set(engine.registry.kinds()) | {REGISTRY_KIND}
        assert "definition" in by_kind, "the law covers itself"
        for row in rows:
            assert row.state == "current"
            assert row.data.revision == 1
            assert row.data.diff is None, "revision 1 has no prior law to differ from"
            assert len(row.data.fingerprint_hash) == 64
        # the registry row IS the sorted kind → hash map
        reg = by_kind[REGISTRY_KIND]
        assert reg.data.fingerprint["kinds"]["doc"] == \
            by_kind["doc"].data.fingerprint_hash
        assert sorted(reg.data.fingerprint["kinds"]) == \
            sorted(engine.registry.kinds())
        # the fingerprint is the record: machine + storage facets present
        fp = by_kind["doc"].data.fingerprint
        assert fp["machine"]["states"] == ["draft", "approved"]
        assert "approve" in fp["machine"]["actions"]
        assert "docs" in str(fp["storage"]["indexes"]) or fp["storage"]["columns"]
        # the §3 seam: current_law is cached after boot
        assert engine.current_law("doc") == by_kind["doc"].id
        assert engine.current_law(REGISTRY_KIND) == reg.id
        assert engine.current_law("nonesuch") is None
    finally:
        await engine.shutdown()


async def test_identical_second_boot_writes_nothing():
    """A restart costs nothing: the second boot — a *recreated* but
    textually identical module — matches every stored hash and appends no
    transition."""
    e1 = await _boot(make_doc(EXPLAIN_V1, OVERDUE_V1), drop=True)
    count = await _transition_count(e1)
    law = dict(e1._law)
    await e1.shutdown()

    e2 = await _boot(make_doc(EXPLAIN_V1, OVERDUE_V1))
    try:
        assert await _transition_count(e2) == count, \
            "an unchanged boot must write nothing"
        assert e2._law == law, "the cached law is the same rows"
        assert [r.data.revision for r in await _definitions(e2, "doc")] == [1]
    finally:
        await e2.shutdown()


async def test_changed_guard_explain_is_a_judgment_revision():
    """Changing a guard's ``explain=`` — the sentence every refusal and
    every unavailable entry carries — is a law change: revision 2 minted
    ``current``, revision 1 superseded, in one correlation, with the diff
    classified ``judgment`` (§4's tag; the policies are elsewhere)."""
    e1 = await _boot(make_doc(EXPLAIN_V1, OVERDUE_V1), drop=True)
    await e1.shutdown()

    e2 = await _boot(make_doc(EXPLAIN_V2, OVERDUE_V1))
    try:
        rev1, rev2 = await _definitions(e2, "doc")
        assert (rev1.data.revision, rev1.state) == (1, "superseded")
        assert (rev2.data.revision, rev2.state) == (2, "current")
        assert e2.current_law("doc") == rev2.id

        diff = rev2.data.diff
        assert diff["added"] == [] and diff["removed"] == []
        changed = {e["path"]: e["class"] for e in diff["changed"]}
        assert changed, "the explain edit must surface as changed paths"
        assert any(".guards." in p and p.endswith(".explain")
                   for p in changed), changed
        assert set(changed.values()) == {"judgment"}, changed
        assert "reverts_to" not in diff

        # one correlation: the new revision's revise and the old one's
        # supersede are one deploy in the log, by the deploy actor. The
        # deploy is nameable on the wire (design §2): the create of a
        # non-first revision is logged as `revise` — the definition kind's
        # declared create spelling — while revision 1's row (nothing was
        # revised) stays `create`
        async with e2.storage.session() as s:
            created = await e2.storage.last_transition(s, "definition", rev2.id)
            superseded = await e2.storage.last_transition(s, "definition",
                                                          rev1.id)
            first = await e2.storage.transitions_since(
                s, 0, kinds=["definition"], limit=500)
        assert created.action == "revise"
        assert superseded.action == "supersede"
        rev1_create = next(t for t in first
                           if t.resource_id == rev1.id and t.from_state == "")
        assert rev1_create.action == "create", \
            "revision 1 recorded the law; nothing was revised"
        assert created.correlation_id == superseded.correlation_id
        assert superseded.actor_type == "system"
        assert superseded.actor_id == "waymark8-deploy"

        # the whole deploy is one row too: the registry target revised in
        # the same correlation, its diff naming the moved kind hash
        reg_rows = await _definitions(e2, REGISTRY_KIND)
        assert [r.data.revision for r in reg_rows] == [1, 2]
        reg_changed = [e["path"] for e in reg_rows[1].data.diff["changed"]]
        assert reg_changed == ["kinds.doc"]
        async with e2.storage.session() as s:
            reg_created = await e2.storage.last_transition(
                s, "definition", reg_rows[1].id)
        assert reg_created.correlation_id == created.correlation_id
    finally:
        await e2.shutdown()


async def test_changed_derived_fn_source_is_a_truth_revision():
    """A ``Derived.fn`` whose *text* changed is a truth change even when
    the behavior didn't (``> 1.0`` vs ``> 1.00``): the text is the law."""
    e1 = await _boot(make_doc(EXPLAIN_V1, OVERDUE_V1), drop=True)
    await e1.shutdown()

    e2 = await _boot(make_doc(EXPLAIN_V1, OVERDUE_V2))
    try:
        rev1, rev2 = await _definitions(e2, "doc")
        assert rev2.state == "current" and rev1.state == "superseded"
        changed = {e["path"]: e["class"] for e in rev2.data.diff["changed"]}
        assert changed.get("derived.overdue.fn") == "truth", changed
    finally:
        await e2.shutdown()


async def test_rollback_is_a_named_reversion():
    """Rolling back to the original module is revision 3 — a forward
    revise whose diff names ``reverts_to: 1`` and whose summary says so:
    the undeclared mass transition of meaning, declared."""
    e1 = await _boot(make_doc(EXPLAIN_V1, OVERDUE_V1), drop=True)
    await e1.shutdown()
    e2 = await _boot(make_doc(EXPLAIN_V2, OVERDUE_V1))
    await e2.shutdown()

    e3 = await _boot(make_doc(EXPLAIN_V1, OVERDUE_V1))
    try:
        rev1, rev2, rev3 = await _definitions(e3, "doc")
        assert [r.data.revision for r in (rev1, rev2, rev3)] == [1, 2, 3]
        assert rev3.state == "current"
        assert rev2.state == rev1.state == "superseded"
        assert rev3.data.fingerprint_hash == rev1.data.fingerprint_hash
        assert rev3.data.diff["reverts_to"] == 1
        assert rev3.data.change_summary == "reverts to revision 1"
        async with e3.storage.session() as s:
            t = await e3.storage.last_transition(s, "definition", rev3.id)
        assert "reverts to revision 1" in t.summary
    finally:
        await e3.shutdown()


# ── §1: fingerprint determinism (load-bearing for §2's comparison) ───────
def _doc_hash() -> str:
    """The doc kind's fingerprint hash, computed from a fresh class in a
    fresh registry with an assembled (never connected) storage — what a
    booting process computes before comparing against the stored law."""
    from waymark8.core.registry import Registry
    from waymark8.server.storage.postgres import PostgresStorage

    registry = Registry()
    rdef = registry.register(make_doc(EXPLAIN_V1, OVERDUE_V1))
    PostgresStorage("postgresql+asyncpg://nobody@localhost:5433/never",
                    registry)  # builds tables; no connection is made
    return fingerprint_hash(fingerprint_of(rdef))


async def test_fingerprint_determinism_across_processes():
    """Same module → same hash: twice in this process (fresh classes each
    time) and once in a subprocess with its own hash seed. A spurious
    mismatch here would make every restart mint a lying revision."""
    first, second = _doc_hash(), _doc_hash()
    assert first == second

    repo = Path(__file__).resolve().parents[2]
    out = subprocess.run(
        [sys.executable, "-c",
         "from tests.waymark8.test_definition import _doc_hash; "
         "print(_doc_hash())"],
        capture_output=True, text=True, cwd=repo, check=True)
    assert out.stdout.strip() == first, \
        "the fingerprint hash must be identical across processes"


# ── the wire: the collection is the deploy history ───────────────────────
async def test_definition_collection_is_the_deploy_history():
    """The generic router renders definitions like any kind: revision
    order by default, read-only for wire principals (create and supersede
    are the deploy's alone)."""
    e1 = await _boot(make_doc(EXPLAIN_V1, OVERDUE_V1), drop=True)
    await e1.shutdown()
    engine = await _boot(make_doc(EXPLAIN_V2, OVERDUE_V1))

    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    transport = ASGITransport(app=app)
    client = AsyncClient(transport=transport, base_url="http://t",
                         headers=OWNER)
    try:
        res = await client.get("/api/definitions?target_kind=doc")
        assert res.status_code == 200, res.text
        doc = res.json()
        items = doc["data"]["items"]
        assert [i["data"]["revision"] for i in items] == [1, 2], \
            "the collection defaults to revision order — the deploy history"
        assert [i["state"] for i in items] == ["superseded", "current"]
        assert items[0]["kind"] == "definition"
        assert "revision 2" in items[1]["summary"]

        # read-only on the wire: a human's supersede renders unavailable
        # with the guard's sentence, and both writes refuse with it
        current = (await client.get(items[1]["self"])).json()
        assert "supersede" not in current["actions"]
        reason = current["unavailable"]["supersede"]["reason"]
        assert reason == ("The law is revised by the deploy at boot, "
                          "never over the wire.")
        refused = await client.post(f"{current['self']}/-/supersede",
                                    headers={"Idempotency-Key":
                                             uuid.uuid4().hex})
        assert refused.status_code == 409
        assert refused.json()["detail"] == reason
        minted = await client.post(
            "/api/definitions",
            json={"target_kind": "doc", "revision": 99,
                  "fingerprint_hash": "0" * 64, "fingerprint": {}},
            headers={"Idempotency-Key": uuid.uuid4().hex})
        assert minted.status_code == 409, \
            "a wire client must not mint law"
        assert minted.json()["detail"] == reason
    finally:
        await client.aclose()
        await engine.shutdown()


async def test_discovery_marks_engine_kinds():
    """Discovery distinguishes the machinery from the domain: kinds the
    engine itself contributed (definition, job, grant, member, …) are
    advertised as ``engine_kinds`` — marked where they were registered,
    not re-derived from a hardcoded list — so a client can fold the
    plumbing behind the app's own kinds."""
    engine = await _boot(make_doc(EXPLAIN_V1, OVERDUE_V1), drop=True)
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t", headers=OWNER)
    try:
        doc = (await client.get("/api/.well-known/waymark")).json()
        engine_kinds = doc["engine_kinds"]
        assert "doc" not in engine_kinds, \
            "an app-supplied kind is the domain, never the machinery"
        assert "doc" in doc["kinds"]
        assert {"definition", "job"} <= set(engine_kinds)
        assert set(engine_kinds) <= set(doc["kinds"]), \
            "every engine kind is still a kind — the flag folds, " \
            "it does not hide"
        assert set(engine_kinds) == {
            k for k in doc["kinds"]
            if engine.registry[k].engine_owned}
    finally:
        await client.aclose()
        await engine.shutdown()


async def test_discovery_marks_secondary_kinds():
    """Discovery advertises the law's judgment of prominence: a domain kind
    declared ``nav="secondary"`` is listed in ``secondary_kinds`` so the
    client folds it behind the nav overflow — the sibling of engine_kinds
    (the machinery folds by ownership; the secondary domain by declared
    prominence). A primary kind is simply absent; the fold never hides —
    the kind and its collection stay in discovery."""
    engine = waymark8.Engine(
        resources=[make_doc(EXPLAIN_V1, OVERDUE_V1), make_note("secondary")],
        storage=TEST_DSN, principal=header_principal, services=None,
        bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t", headers=OWNER)
    try:
        doc = (await client.get("/api/.well-known/waymark")).json()
        secondary = doc["secondary_kinds"]
        assert secondary == ["note"], secondary
        assert "doc" not in secondary, "a primary domain kind never lists here"
        assert "note" in doc["kinds"], "secondary folds, it does not hide"
        assert "note" in doc["collections"], "the collection stays reachable"
        # prominence and ownership are disjoint declarations: an engine
        # kind is folded by ownership, never appearing among the secondary
        assert set(secondary).isdisjoint(doc["engine_kinds"])
    finally:
        await client.aclose()
        await engine.shutdown()


async def test_nav_secondary_is_an_advertisement_revision():
    """Marking a kind ``nav="secondary"`` is a law change — the law judges
    what leads — but a cosmetic one: revision 2 is minted, its one diff
    path (``nav``, added, since primary omits the key) classified
    ``advertisement``, so no derived fact is marked stale."""
    e1 = await _boot(make_note("primary"), drop=True)
    await e1.shutdown()

    e2 = await _boot(make_note("secondary"))
    try:
        rev1, rev2 = await _definitions(e2, "note")
        assert (rev1.data.revision, rev1.state) == (1, "superseded")
        assert (rev2.data.revision, rev2.state) == (2, "current")
        diff = rev2.data.diff
        assert diff["added"] == [{"path": "nav", "class": "advertisement"}], diff
        assert diff["removed"] == [] and diff["changed"] == []
    finally:
        await e2.shutdown()
