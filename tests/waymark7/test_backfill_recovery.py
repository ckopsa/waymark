"""The crash window, closed (design §4): the catch-up debt is durable.

A boot that commits its revise and crashes before the backfill completes
used to re-boot into matching hashes — no revise, no stale set, stale
values served as current truth. Now the revise writes the pending facts
onto the revision row itself (``backfill_pending``, one insert with the
law change — atomic by construction), and every boot unions its freshly
detected stale facts with any unsettled marker: immediate kinds backfill
before serving, Deferred kinds restore ``recomputing`` and resume the
drain. The marker is cleared by an ordinary ``settle`` transition only
after the recompute commits, so the lifecycle is auditable in the deploy
history. Sorting on a recomputing fact is blocked with its filters and
facets: both spellings leave the advertised enum and ``?sort=`` naming
it is the same 503 ``FactRecomputing`` Problem.
"""
from __future__ import annotations

import asyncio
import logging
import os
import uuid
from datetime import UTC, datetime
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark7
from waymark7 import (
    Ctx,
    Deferred,
    Derived,
    Resource,
    Safety,
    action,
    filterable,
    sortable,
)
from waymark7.server.bus import InProcessBus
from waymark7.server.engine import header_principal
from waymark7.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

OWNER = {"X-Principal-Id": "elena", "X-Principal-Display": "Elena V."}

NOW = datetime(2026, 7, 1, 12, 0, tzinfo=UTC)

# two laws for the same fact (the test_backfill pattern): textually
# distinct lambdas, exactly the redefinition the fingerprint detects
BIG_V1 = lambda hours: hours > 8.0    # noqa: E731
BIG_V2 = lambda hours: hours > 6.0    # noqa: E731

EXPLAIN_V1 = "Big tasks take more than a day."
EXPLAIN_V2 = "A big task exceeds one working day."


class TaskState(StrEnum):
    OPEN = "open"
    DONE = "done"


def make_task(big_fn, *, explain: str = EXPLAIN_V1, deferred=None):
    """A fresh ``rtask`` kind — two calls with the same arguments yield
    byte-identical declarations, which is what lets one test stage
    several deploys against one database. Sortable and faceted on the
    derived fact, so the blocking of the whole query surface is
    observable."""

    class TaskData(BaseModel):
        title: str = Field(min_length=1, max_length=80)
        hours: float = Field(default=1.0, ge=0)
        big: bool = Derived(over=("hours",), fn=big_fn, explain=explain)

    class Task(Resource):
        kind = "rtask"
        State = TaskState
        Data = TaskData
        initial = TaskState.OPEN
        terminal = {TaskState.DONE}
        summary = "{data.title} · {state.label}"
        filterable = filterable(state=filterable.Eq, big=filterable.Eq,
                                hours=filterable.Eq)
        sortable = sortable("big", "hours", default="hours")
        faceted = ("big",)
        backfill = deferred

        @action(from_=TaskState.OPEN, to=TaskState.DONE,
                safety=Safety(idempotent=True, reversible=False,
                              confirm=True,
                              consequence="Marks the task complete."))
        async def finish(self, inp: None, ctx: Ctx) -> None:
            pass

    return Task


def make_task_without_big():
    """The same kind under a law that no longer declares the fact — the
    since-removed-fact case a stale marker must survive."""

    class TaskData(BaseModel):
        title: str = Field(min_length=1, max_length=80)
        hours: float = Field(default=1.0, ge=0)

    class Task(Resource):
        kind = "rtask"
        State = TaskState
        Data = TaskData
        initial = TaskState.OPEN
        terminal = {TaskState.DONE}
        summary = "{data.title} · {state.label}"
        filterable = filterable(state=filterable.Eq, hours=filterable.Eq)
        sortable = sortable("hours", default="hours")

        @action(from_=TaskState.OPEN, to=TaskState.DONE,
                safety=Safety(idempotent=True, reversible=False,
                              confirm=True,
                              consequence="Marks the task complete."))
        async def finish(self, inp: None, ctx: Ctx) -> None:
            pass

    return Task


def _engine(resources):
    return waymark7.Engine(resources=resources, storage=TEST_DSN,
                           principal=header_principal, services=None,
                           bus=InProcessBus(), clock=lambda: NOW)


async def _boot(resources, *, drop: bool = False):
    engine = _engine(resources)
    if drop:
        await engine.storage.drop_all()
    await engine.startup()
    return engine


def _crash_backfill(engine) -> None:
    """The simulated crash: the revise commits, the backfill never runs —
    the process dies between them."""

    async def crash(kind, **kwargs):
        raise RuntimeError("simulated crash before backfill")

    engine.invoker.derived.backfill = crash


async def _abort(engine) -> None:
    """Release a crashed boot's pool; its startup never reached the bus
    or the dispatcher."""
    await engine.storage.engine.dispose()


def _client(engine) -> AsyncClient:
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    return AsyncClient(transport=ASGITransport(app=app),
                       base_url="http://t", headers=OWNER)


async def _post(client, href, json):
    res = await client.post(href, json=json,
                            headers={"Idempotency-Key": uuid.uuid4().hex})
    assert res.status_code == 201, res.text
    return res.json()


async def _rows(engine, kind):
    async with engine.storage.session() as s:
        rows, _ = await engine.storage.query(
            s, kind, filters={}, sort=None, page_size=100, page_number=1)
    return {r.id: r for r in rows}


async def _revisions(engine, target_kind):
    async with engine.storage.session() as s:
        rows, _ = await engine.storage.query(
            s, "definition", filters={"target_kind": target_kind},
            sort="revision", page_size=50, page_number=1)
    return rows


async def _last(engine, row_id):
    async with engine.storage.session() as s:
        return await engine.storage.last_transition(s, "definition", row_id)


async def _seed(resources, rows):
    """Boot 1: law A, with rows materialized under it."""
    e1 = await _boot(resources, drop=True)
    client = _client(e1)
    try:
        for title, hours in rows:
            await _post(client, "/api/rtasks",
                        {"title": title, "hours": hours})
    finally:
        await client.aclose()
        await e1.shutdown()


ROWS = (("audit", 7.0), ("rebuild", 9.0), ("email", 2.0))


# ── the crash window: immediate mode ─────────────────────────────────────
async def test_crashed_immediate_backfill_recovers_on_next_boot():
    """Boot 2 commits the revise and dies before the backfill; boot 3
    (matching hashes — no fresh revise) detects the durable marker,
    recomputes before serving, settles it, and serves law-B truth."""
    await _seed([make_task(BIG_V1)], ROWS)

    e2 = _engine([make_task(BIG_V2)])
    _crash_backfill(e2)
    with pytest.raises(RuntimeError, match="simulated crash"):
        await e2.startup()
    stored = await _rows(e2, "rtask")
    assert {r.data.title: r.data.big for r in stored.values()} == \
        {"audit": False, "rebuild": True, "email": False}, \
        "the crash left law-A values under a committed law-B revise"
    await _abort(e2)

    e3 = await _boot([make_task(BIG_V2)])
    client = _client(e3)
    try:
        revs = await _revisions(e3, "rtask")
        assert [r.data.revision for r in revs] == [1, 2], \
            "boot 3's hashes match — recovery must not need a fresh revise"
        after = await _rows(e3, "rtask")
        assert {r.data.title: r.data.big for r in after.values()} == \
            {"audit": True, "rebuild": True, "email": False}, \
            "law B (> 6.0) must govern every stored row after recovery"
        # the debt is settled: marker cleared by an ordinary transition,
        # auditable in the deploy history next to the revise it pays
        assert revs[1].data.backfill_pending is None
        settled = await _last(e3, revs[1].id)
        assert settled.action == "settle"
        assert settled.actor_id == "waymark7-deploy"
        col = (await client.get("/api/rtasks?big=true")).json()
        assert {i["data"]["title"] for i in col["data"]["items"]} == \
            {"audit", "rebuild"}
        assert "recomputing" not in col["meta"]
    finally:
        await client.aclose()
        await e3.shutdown()


async def test_marker_write_is_atomic_with_the_revise():
    """The marker and the law change are one row, one insert: after a
    faked mid-boot failure both are present — the revise cannot commit
    without its debt, and a rolled-back revise takes the marker with
    it."""
    await _seed([make_task(BIG_V1)], (("audit", 7.0),))

    e2 = _engine([make_task(BIG_V2)])
    _crash_backfill(e2)
    with pytest.raises(RuntimeError):
        await e2.startup()
    try:
        rev1, rev2 = await _revisions(e2, "rtask")
        assert rev2.state == "current" and rev1.state == "superseded"
        assert rev2.data.fingerprint_hash != rev1.data.fingerprint_hash, \
            "the law moved"
        assert rev2.data.backfill_pending == ["big"], \
            "…and its unpaid recompute debt landed with it, same row"
        assert rev1.data.backfill_pending is None
    finally:
        await _abort(e2)


# ── the crash window: Deferred mode ──────────────────────────────────────
async def test_crashed_deferred_drain_resumes_from_durable_marker():
    """A Deferred drain that dies leaves ``backfill_pending`` unsettled;
    the next boot restores ``meta.recomputing`` from the marker, resumes
    the drain, settles, and the full query surface returns."""
    deferral = Deferred(batch=2, pause=0.0)
    await _seed([make_task(BIG_V1, deferred=deferral)],
                (*ROWS, ("deploy", 7.5), ("standup", 0.5)))

    # boot 2: the revise commits, the drain fails — the crashed-drain
    # shape (marks used to be in-process only; the marker is not)
    e2 = _engine([make_task(BIG_V2, deferred=deferral)])
    _crash_backfill(e2)
    await e2.startup()
    await e2._backfill_task
    assert e2.registry["rtask"].recomputing == ("big",), \
        "a failed drain leaves the kind marked"
    revs = await _revisions(e2, "rtask")
    assert revs[1].data.backfill_pending == ["big"], \
        "…and the durable marker unsettled"
    await e2.shutdown()

    # boot 3: matching hashes, no fresh revise — the marker alone must
    # restore the mark; hold the drain open to observe the window
    e3 = _engine([make_task(BIG_V2, deferred=deferral)])
    gate = asyncio.Event()
    real_backfill = e3.invoker.derived.backfill

    async def gated_backfill(kind, **kwargs):
        await gate.wait()
        return await real_backfill(kind, **kwargs)

    e3.invoker.derived.backfill = gated_backfill
    await e3.startup()
    client = _client(e3)
    try:
        assert e3.registry["rtask"].recomputing == ("big",), \
            "recomputing restored from the durable marker, not a revise"
        assert [r.data.revision
                for r in await _revisions(e3, "rtask")] == [1, 2]

        col = (await client.get("/api/rtasks")).json()
        assert col["meta"]["recomputing"] == ["big"]
        props = col["actions"]["query"]["input"]["properties"]
        assert "big" not in props, "filter and facet stay un-advertised"
        refused = await client.get("/api/rtasks?big=true")
        assert refused.status_code == 503
        assert refused.json()["type"].endswith("/fact-recomputing")

        gate.set()
        await e3._backfill_task
        assert e3.registry["rtask"].recomputing == ()
        revs = await _revisions(e3, "rtask")
        assert revs[1].data.backfill_pending is None, "the drain settled"
        assert (await _last(e3, revs[1].id)).action == "settle"

        # filters, facets, and sort restored, serving law-B truth
        col = (await client.get("/api/rtasks")).json()
        assert "recomputing" not in col["meta"]
        props = col["actions"]["query"]["input"]["properties"]
        assert "big" in props and "x-facets" in props["big"]
        assert "big" in props["sort"]["enum"]
        served = (await client.get("/api/rtasks?big=true")).json()
        assert {i["data"]["title"] for i in served["data"]["items"]} == \
            {"audit", "rebuild", "deploy"}
    finally:
        await client.aclose()
        await e3.shutdown()


# ── sort blocked during the window ───────────────────────────────────────
async def test_sort_on_recomputing_fact_is_blocked_during_window():
    """An order over a half-recomputed column ranks rows by two laws at
    once: both spellings leave the advertised sort enum and ``?sort=``
    naming the fact is the same 503 the filter gets; other sorts work
    throughout, and the surface returns when the drain settles."""
    deferral = Deferred(batch=2, pause=0.0)
    await _seed([make_task(BIG_V1, deferred=deferral)], ROWS)

    e2 = _engine([make_task(BIG_V2, deferred=deferral)])
    gate = asyncio.Event()
    real_backfill = e2.invoker.derived.backfill

    async def gated_backfill(kind, **kwargs):
        await gate.wait()
        return await real_backfill(kind, **kwargs)

    e2.invoker.derived.backfill = gated_backfill
    await e2.startup()
    client = _client(e2)
    try:
        col = (await client.get("/api/rtasks")).json()
        enum = col["actions"]["query"]["input"]["properties"]["sort"]["enum"]
        assert "big" not in enum and "-big" not in enum, \
            "both spellings leave the advertised enum"
        assert "hours" in enum and "-hours" in enum

        for spelling in ("big", "-big"):
            refused = await client.get(f"/api/rtasks?sort={spelling}")
            assert refused.status_code == 503, refused.text
            problem = refused.json()
            assert problem["type"].endswith("/fact-recomputing")
            assert problem["recomputing"] == ["big"]
            assert "'big'" in problem["detail"]
            assert "sorting" in problem["detail"]

        ordered = (await client.get("/api/rtasks?sort=-hours")).json()
        assert [i["data"]["title"] for i in ordered["data"]["items"]] == \
            ["rebuild", "audit", "email"], "other sorts work throughout"

        gate.set()
        await e2._backfill_task
        col = (await client.get("/api/rtasks")).json()
        enum = col["actions"]["query"]["input"]["properties"]["sort"]["enum"]
        assert "big" in enum and "-big" in enum
        restored = await client.get("/api/rtasks?sort=-big")
        assert restored.status_code == 200
        assert [i["data"]["title"]
                for i in restored.json()["data"]["items"]][0] in \
            {"audit", "rebuild"}, "law-B truth, sortable again"
    finally:
        await client.aclose()
        await e2.shutdown()


# ── a marker for a fact the law no longer declares ───────────────────────
async def test_marker_for_removed_fact_is_dropped_with_a_log_line(caplog):
    """The crashed deploy marked ``big`` pending; the next law removes
    the fact entirely. There is nothing to recompute: the marker is
    dropped with a log line and the boot completes clean."""
    await _seed([make_task(BIG_V1)], (("audit", 7.0),))

    e2 = _engine([make_task(BIG_V2)])
    _crash_backfill(e2)
    with pytest.raises(RuntimeError):
        await e2.startup()
    await _abort(e2)

    caplog.set_level(logging.INFO, logger="waymark7.definitions")
    e3 = await _boot([make_task_without_big()])
    client = _client(e3)
    try:
        assert e3.registry["rtask"].recomputing == ()
        revs = await _revisions(e3, "rtask")
        assert [r.data.revision for r in revs] == [1, 2, 3]
        assert revs[2].data.backfill_pending is None, \
            "the dropped marker is not carried forward"
        dropped = [r for r in caplog.records
                   if "dropping backfill marker" in r.getMessage()]
        assert dropped and "rtask.big" in dropped[0].getMessage()
        col = (await client.get("/api/rtasks")).json()
        assert col["data"]["total"] == 1
        assert "recomputing" not in col["meta"]
    finally:
        await client.aclose()
        await e3.shutdown()


# ── the debt survives a further revise ───────────────────────────────────
async def test_unsettled_marker_propagates_across_a_further_revise():
    """Crash under law B, then deploy an advertisement-only change (no
    fresh stale facts): the new revision inherits the unpaid debt as its
    own ``backfill_pending``, the boot recomputes and settles it."""
    await _seed([make_task(BIG_V1)], ROWS)

    e2 = _engine([make_task(BIG_V2)])
    _crash_backfill(e2)
    with pytest.raises(RuntimeError):
        await e2.startup()
    await _abort(e2)

    # law B's fn, new explain text: the hash moves, stale_facts is empty —
    # only the inherited marker knows the rows still hold law-A values
    e3 = await _boot([make_task(BIG_V2, explain=EXPLAIN_V2)])
    client = _client(e3)
    try:
        revs = await _revisions(e3, "rtask")
        assert [r.data.revision for r in revs] == [1, 2, 3]
        assert revs[1].data.backfill_pending == ["big"], \
            "the crashed revision keeps its record"
        assert revs[2].data.backfill_pending is None, \
            "the inherited debt was settled on the new revision"
        assert (await _last(e3, revs[2].id)).action == "settle"
        after = await _rows(e3, "rtask")
        assert {r.data.title: r.data.big for r in after.values()} == \
            {"audit": True, "rebuild": True, "email": False}, \
            "law B governs — the inherited marker drove the backfill"
    finally:
        await client.aclose()
        await e3.shutdown()
