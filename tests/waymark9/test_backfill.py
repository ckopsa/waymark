"""Stale-by-definition and the backfill invariant (design §4).

A deploy whose diff touches a derivation's semantic surface — the fn
source, ``over=``, the ``Tolerance``, ``flips_at`` — marks the fact
stale by definition, and the boot settles it before the kind serves:
immediately (recompute every row inside startup — no derivation events,
no version bumps, clock index refreshed) or under a declared
``Deferred`` (serve at once with the fact marked ``meta.recomputing``
and honestly un-advertised from the query surface until a background
task catches it up). An advertisement-classified change (a Derived's
``explain=`` text) triggers no backfill at all. E4's seed-template
policy rides the same section: ``Seed(retro=Never)`` is fingerprinted,
so the policy is a declaration with a diff class instead of prose.
"""
from __future__ import annotations

import asyncio
import os
import uuid
from datetime import UTC, datetime, timedelta
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import AwareDatetime, BaseModel, Field

import waymark9
from waymark9 import (
    Clock,
    Ctx,
    Deferred,
    DefinitionError,
    Derived,
    Never,
    Owns,
    Resource,
    Safety,
    Seed,
    action,
    filterable,
)
from waymark9.core.fingerprint import (
    classify_path,
    diff_fingerprints,
    fingerprint_of,
)
from waymark9.core.registry import Registry
from waymark9.server.bus import InProcessBus
from waymark9.server.engine import header_principal
from waymark9.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

OWNER = {"X-Principal-Id": "elena", "X-Principal-Display": "Elena V."}

NOW = datetime(2026, 7, 1, 12, 0, tzinfo=UTC)

# two laws for the same fact: textually distinct lambdas, exactly the
# redefinition the fingerprint's source-text semantics detect
BIG_V1 = lambda hours: hours > 8.0    # noqa: E731
BIG_V2 = lambda hours: hours > 6.0    # noqa: E731

EXPLAIN_V1 = "Big tasks take more than a day."
EXPLAIN_V2 = "A big task exceeds one working day."

# the clocked shape: same fn, but the declared flip time moves — a
# semantic-surface change the backfill must re-index
STALE_FN = lambda checked, now: (now - checked) > timedelta(hours=3)  # noqa: E731
FLIPS_V1 = lambda r: r.data.checked_at + timedelta(hours=1)           # noqa: E731
FLIPS_V2 = lambda r: r.data.checked_at + timedelta(hours=2)           # noqa: E731


class TaskState(StrEnum):
    OPEN = "open"
    DONE = "done"


def make_task(big_fn, *, explain: str = EXPLAIN_V1, deferred=None):
    """A fresh ``btask`` kind — two calls with the same arguments yield
    byte-identical declarations, which is what lets one test stage two
    deploys against one database (the test_definition pattern)."""

    class TaskData(BaseModel):
        title: str = Field(min_length=1, max_length=80)
        hours: float = Field(default=1.0, ge=0)
        big: bool = Derived(over=("hours",), fn=big_fn, explain=explain)

    class Task(Resource):
        kind = "btask"
        State = TaskState
        Data = TaskData
        initial = TaskState.OPEN
        terminal = {TaskState.DONE}
        summary = "{data.title} · {state.label}"
        filterable = filterable(state=filterable.Eq, big=filterable.Eq,
                                hours=filterable.Eq)
        backfill = deferred

        @action(from_=TaskState.OPEN, to=TaskState.DONE,
                safety=Safety(idempotent=True, reversible=False,
                              confirm=True,
                              consequence="Marks the task complete."))
        async def finish(self, inp: None, ctx: Ctx) -> None:
            pass

    return Task


def make_probe(flips_at):
    """A clocked kind whose declared flip time is the redefined surface."""

    class ProbeData(BaseModel):
        checked_at: AwareDatetime
        stale: bool = Derived(over=("checked_at", Clock), fn=STALE_FN,
                              flips_at=flips_at)

    class Probe(Resource):
        kind = "bprobe"
        State = TaskState
        Data = ProbeData
        initial = TaskState.OPEN
        terminal = {TaskState.DONE}
        summary = "probe · {state.label}"
        filterable = filterable(state=filterable.Eq, stale=filterable.Eq)

        @action(from_=TaskState.OPEN, to=TaskState.DONE,
                safety=Safety(idempotent=True, reversible=False,
                              confirm=True,
                              consequence="Retires the probe."))
        async def finish(self, inp: None, ctx: Ctx) -> None:
            pass

    return Probe


async def _boot(resources, *, drop: bool = False, events: list | None = None):
    engine = waymark9.Engine(resources=resources, storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus(), clock=lambda: NOW)
    if drop:
        await engine.storage.drop_all()
    if events is not None:
        # every derivation announcement funnels through publish — an
        # empty capture across startup IS "the backfill emitted nothing"
        real = engine.invoker.derived.publish

        async def spying_publish(payloads):
            events.extend(payloads)
            await real(payloads)

        engine.invoker.derived.publish = spying_publish
    await engine.startup()
    return engine


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


def _id(doc) -> str:
    return doc["self"].rsplit("/", 1)[-1]


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


# ── immediate mode: boot does not complete until truth is current ────────
async def test_immediate_backfill_recomputes_before_serve():
    """Rows materialized under law A are recomputed under law B inside
    boot 2 — values current before any request, no derivation events, no
    version bumps, and the clocked fact's ``next_flip_at`` re-indexed to
    the new law's flip time."""
    e1 = await _boot([make_task(BIG_V1), make_probe(FLIPS_V1)], drop=True)
    client = _client(e1)
    checked = NOW - timedelta(minutes=30)
    try:
        for title, hours in (("audit", 7.0), ("rebuild", 9.0), ("email", 2.0)):
            await _post(client, "/api/btasks", {"title": title, "hours": hours})
        probe = await _post(client, "/api/bprobes",
                            {"checked_at": checked.isoformat()})
        before = await _rows(e1, "btask")
        # law A: only the 9-hour task is big; the probe flips at +1h
        assert {r.data.title: r.data.big for r in before.values()} == \
            {"audit": False, "rebuild": True, "email": False}
        probe_row = (await _rows(e1, "bprobe"))[_id(probe)]
        assert probe_row.next_flip_at == checked + timedelta(hours=1)
    finally:
        await client.aclose()
        await e1.shutdown()

    events: list = []
    e2 = await _boot([make_task(BIG_V2), make_probe(FLIPS_V2)],
                     events=events)
    client = _client(e2)
    try:
        # the values are already current — asserted at the storage layer,
        # before the first request touches the kind
        after = await _rows(e2, "btask")
        assert {r.data.title: r.data.big for r in after.values()} == \
            {"audit": True, "rebuild": True, "email": False}, \
            "law B (> 6.0) must govern every stored row after boot"
        for row_id, row in after.items():
            assert row.version == before[row_id].version, \
                "backfill is maintenance: no transition, no version bump"
        assert [p for p in events if p.get("class") == "derivation"] == [], \
            "the revise is the one loud event; per-row flips are noise"
        # the clocked fact's index follows the new law
        probe_row = (await _rows(e2, "bprobe"))[_id(probe)]
        assert probe_row.next_flip_at == checked + timedelta(hours=2)

        # the revise diff is what marked the facts: semantic paths, truth
        task_revs = await _revisions(e2, "btask")
        assert len(task_revs) == 2
        changed = {en["path"]: en["class"]
                   for en in task_revs[1].data.diff["changed"]}
        assert changed.get("derived.big.fn") == "truth", changed
        probe_revs = await _revisions(e2, "bprobe")
        changed = {en["path"]: en["class"]
                   for en in probe_revs[1].data.diff["changed"]}
        assert changed.get("derived.stale.flips_at") == "truth", changed

        # and the wire serves current truth, unmarked: no catching up
        col = (await client.get("/api/btasks?big=true")).json()
        assert {i["data"]["title"] for i in col["data"]["items"]} == \
            {"audit", "rebuild"}
        assert "recomputing" not in col["meta"]
    finally:
        await client.aclose()
        await e2.shutdown()


# ── Deferred: marked, un-advertised, drained ─────────────────────────────
async def test_deferred_backfill_marks_and_unadvertises_until_drained():
    deferral = Deferred(batch=2, pause=0.0)
    e1 = await _boot([make_task(BIG_V1, deferred=deferral)], drop=True)
    client = _client(e1)
    try:
        for title, hours in (("audit", 7.0), ("rebuild", 9.0),
                             ("email", 2.0), ("deploy", 7.5),
                             ("standup", 0.5)):
            await _post(client, "/api/btasks", {"title": title, "hours": hours})
        before = await _rows(e1, "btask")
    finally:
        await client.aclose()
        await e1.shutdown()

    e2 = waymark9.Engine(resources=[make_task(BIG_V2, deferred=deferral)],
                         storage=TEST_DSN, principal=header_principal,
                         services=None, bus=InProcessBus(),
                         clock=lambda: NOW)
    # hold the drain open so the catching-up window is observable
    gate = asyncio.Event()
    real_backfill = e2.invoker.derived.backfill

    async def gated_backfill(kind, **kwargs):
        await gate.wait()
        return await real_backfill(kind, **kwargs)

    e2.invoker.derived.backfill = gated_backfill
    events: list = []
    real_publish = e2.invoker.derived.publish

    async def spying_publish(payloads):
        events.extend(payloads)
        await real_publish(payloads)

    e2.invoker.derived.publish = spying_publish
    await e2.startup()
    client = _client(e2)
    try:
        assert e2.registry["btask"].recomputing == ("big",)

        # the envelope says what is catching up, on the collection and on
        # every member — and the query action honestly un-advertises the
        # stale fact while every other filter stays offered
        col = (await client.get("/api/btasks")).json()
        assert col["meta"]["recomputing"] == ["big"]
        props = col["actions"]["query"]["input"]["properties"]
        assert "big" not in props, "a recomputing fact is un-advertised"
        assert "hours" in props and "state" in props
        item = col["data"]["items"][0]
        assert "big" in item["data"], \
            "the value still renders as data — marked, never hidden"
        one = (await client.get(item["self"])).json()
        assert one["meta"]["recomputing"] == ["big"]

        # filtering on the stale fact is a Problem naming why — the
        # Service-down honesty, applied to truth
        refused = await client.get("/api/btasks?big=true")
        assert refused.status_code == 503, refused.text
        problem = refused.json()
        assert problem["type"].endswith("/fact-recomputing")
        assert problem["recomputing"] == ["big"]
        assert "'big'" in problem["detail"]

        # filters on non-stale facts work throughout
        by_hours = (await client.get("/api/btasks?hours=9")).json()
        assert [i["data"]["title"] for i in by_hours["data"]["items"]] == \
            ["rebuild"]
        by_state = (await client.get("/api/btasks?state=open")).json()
        assert by_state["data"]["total"] == 5

        # drain: the background task catches the rows up in its declared
        # batches (2 per page over 5 rows), silently
        gate.set()
        await e2._backfill_task
        assert e2.registry["btask"].recomputing == ()
        assert [p for p in events if p.get("class") == "derivation"] == [], \
            "the deferred drain announces nothing either"

        col = (await client.get("/api/btasks")).json()
        assert "recomputing" not in col["meta"]
        assert "big" in col["actions"]["query"]["input"]["properties"]
        served = (await client.get("/api/btasks?big=true")).json()
        assert {i["data"]["title"] for i in served["data"]["items"]} == \
            {"audit", "rebuild", "deploy"}, "law B truth, filterable again"
        after = await _rows(e2, "btask")
        for row_id, row in after.items():
            assert row.version == before[row_id].version
    finally:
        await client.aclose()
        await e2.shutdown()


# ── advertisement changes leave truth alone ──────────────────────────────
async def test_advertisement_change_triggers_no_backfill():
    """A Derived's ``explain=`` is refusal-surface garnish: the revise
    happens (the text is the law), classified ``advertisement`` — and no
    fact is marked stale, no row is recomputed."""
    e1 = await _boot([make_task(BIG_V1, explain=EXPLAIN_V1)], drop=True)
    client = _client(e1)
    try:
        await _post(client, "/api/btasks", {"title": "audit", "hours": 7.0})
    finally:
        await client.aclose()
        await e1.shutdown()

    e2 = waymark9.Engine(resources=[make_task(BIG_V1, explain=EXPLAIN_V2)],
                         storage=TEST_DSN, principal=header_principal,
                         services=None, bus=InProcessBus(),
                         clock=lambda: NOW)
    calls: list[str] = []
    real_backfill = e2.invoker.derived.backfill

    async def spying_backfill(kind, **kwargs):
        calls.append(kind)
        return await real_backfill(kind, **kwargs)

    e2.invoker.derived.backfill = spying_backfill
    await e2.startup()
    client = _client(e2)
    try:
        assert calls == [], "an advertisement change recomputes nothing"
        assert e2.registry["btask"].recomputing == ()

        revs = await _revisions(e2, "btask")
        assert len(revs) == 2, "the explain text is still a law change"
        changed = {en["path"]: en["class"]
                   for en in revs[1].data.diff["changed"]}
        assert changed == {"derived.big.explain": "advertisement"}, changed

        col = (await client.get("/api/btasks")).json()
        assert "recomputing" not in col["meta"]
        assert "big" in col["actions"]["query"]["input"]["properties"]
    finally:
        await client.aclose()
        await e2.shutdown()


# ── declarations validate at the door ────────────────────────────────────
def test_deferred_validates_its_batching():
    with pytest.raises(DefinitionError):
        Deferred(batch=0)
    with pytest.raises(DefinitionError):
        Deferred(pause=-1.0)


def test_backfill_declaration_must_be_deferred():
    cls = make_task(BIG_V1)
    cls.backfill = "run it overnight"
    with pytest.raises(DefinitionError, match="Deferred"):
        waymark9.Engine(resources=[cls], storage=TEST_DSN,
                        principal=header_principal, services=None,
                        bus=InProcessBus())


# ── Seed retro=Never: the policy is a declaration with a diff class ──────
def _seed_parent(defaults):
    class BfEventData(BaseModel):
        fund_type: str = Field(min_length=1, max_length=16)

    class BfEvent(Resource):
        kind = "bf_event"
        State = TaskState
        Data = BfEventData
        initial = TaskState.OPEN
        terminal = {TaskState.DONE}
        summary = "{data.fund_type} · {state.label}"
        owns = (Owns("bf_item", via="event_id",
                     seed=Seed(kind="bf_template",
                               where={"fund_type": "{data.fund_type}"},
                               copy={"name": "name"},
                               defaults=defaults,
                               retro=Never)),)

        @action(from_=TaskState.OPEN, to=TaskState.DONE,
                safety=Safety(idempotent=True, reversible=False,
                              confirm=True, consequence="Closes the event."))
        async def close(self, inp: None, ctx: Ctx) -> None:
            pass

    return BfEvent


def test_seed_retro_never_is_fingerprinted_and_diffs_classify():
    fp_a = fingerprint_of(Registry().register(_seed_parent({"source": "seed"})))
    assert fp_a["owns"][0]["seed"]["retro"] == "Never", \
        "the policy is in the fingerprint — a declaration, not prose"

    # a template-surface edit is a diff whose paths carry §4's class; the
    # retro entry sits on the same surface
    fp_b = fingerprint_of(
        Registry().register(_seed_parent({"source": "template"})))
    diff = diff_fingerprints(fp_a, fp_b)
    changed = {en["path"]: en["class"] for en in diff["changed"]}
    assert changed.get("owns.0.seed.defaults.source") == "truth", changed
    assert classify_path("owns.0.seed.retro") == "truth"


def test_seed_retro_rejects_unshipped_policies():
    class Sometimes:
        pass

    with pytest.raises(DefinitionError, match="retro"):
        Seed(kind="bf_template", copy={"name": "name"}, retro=Sometimes)
