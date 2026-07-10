"""Async egress is a job resource (design E6): ``ctx.defer`` runs named
artifacts through a declared Service on a background runner — progress
as data, outcomes per artifact, ServiceDown honoring the declared
backoff, and orphaned jobs cancelled honestly at boot. The pattern five
hand-built implementations were reaching for.
"""
from __future__ import annotations

import asyncio
import os
import uuid
from datetime import timedelta
from enum import StrEnum

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

import waymark9
from waymark9 import Ctx, Resource, Safety, action
from waymark9.server.bus import InProcessBus
from waymark9.server.engine import header_principal
from waymark9.server.external import Service
from waymark9.testing import per_worker_dsn

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))


class Ledger:
    """A fake accounting system: scriptable per-report outcomes."""

    def __init__(self) -> None:
        self.pulled: list[str] = []
        self.fail: set[str] = set()
        self.hang = asyncio.Event()
        self.hang.set()

    async def pull(self, report: str) -> str:
        await self.hang.wait()
        if report in self.fail:
            raise RuntimeError(f"{report} export is malformed")
        self.pulled.append(report)
        return report


class BookState(StrEnum):
    OPEN = "open"


class BookData(BaseModel):
    period: str = Field(min_length=1, max_length=32)
    pull_job_id: str | None = Field(
        default=None, json_schema_extra={"x-display": {"raw": True}})


class Book(Resource):
    kind = "book"
    State = BookState
    Data = BookData
    initial = BookState.OPEN
    terminal: set = set()
    summary = "{data.period} · {state.label}"

    @action(from_=BookState.OPEN, to=BookState.OPEN,
            safety=Safety(idempotent=False, reversible=True, confirm=False),
            display=dict(label="Pull reports"))
    async def pull_reports(self, inp: None, ctx: Ctx) -> None:
        self.data.pull_job_id = await ctx.defer(
            ctx.services.ledger,
            [("balance_sheet", ("balance_sheet",)),
             ("general_ledger", ("general_ledger",)),
             ("positions", ("positions",))],
            action="pull_reports")

    @action(from_=BookState.OPEN, to=BookState.OPEN,
            safety=Safety(idempotent=False, reversible=True, confirm=False),
            display=dict(label="Import intake"))
    async def import_icat(self, inp: None, ctx: Ctx) -> None:
        # the intake shape (design E6): five independent sub-imports on a
        # down_on_error=False service
        self.data.pull_job_id = await ctx.defer(
            ctx.services.beacon,
            [(name, (name,)) for name in
             ("entities", "contacts", "documents", "holdings",
              "agreements")],
            action="import_icat")


class Services:
    def __init__(self, ledger: Ledger):
        self.ledger_backend = ledger
        self.ledger = Service("ledger", handler=ledger.pull,
                              backoff_seconds=60.0)
        # independent artifact failures (design E6): one malformed export
        # is not an outage of the whole external system
        self.beacon = Service("beacon", handler=ledger.pull,
                             backoff_seconds=60.0, down_on_error=False)


@pytest.fixture
async def env():
    ledger = Ledger()
    engine = waymark9.Engine(resources=[Book], storage=TEST_DSN,
                             principal=header_principal,
                             services=Services(ledger),
                             bus=InProcessBus())
    await engine.storage.drop_all()
    await engine.startup()
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    client = AsyncClient(transport=ASGITransport(app=app),
                         base_url="http://t",
                         headers={"X-Principal-Id": "ana"})
    try:
        yield engine, client, ledger
    finally:
        await client.aclose()
        await engine.shutdown()


async def _post(client, href, json=None):
    return await client.post(href, json=json,
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def _pulled_job(client) -> str:
    book = (await _post(client, "/api/books", {"period": "2026-07"})).json()
    doc = (await _post(client, f"{book['self']}/-/pull_reports")).json()
    return doc["data"]["pull_job_id"]


async def _job_done(client, job_id, *, state="done", timeout=5.0):
    deadline = asyncio.get_event_loop().time() + timeout
    while True:
        res = await client.get(f"/api/jobs/{job_id}")
        if res.status_code == 200 and res.json()["state"] == state:
            return res.json()
        if asyncio.get_event_loop().time() > deadline:
            raise AssertionError(
                f"job never reached {state}: {res.text}")
        await asyncio.sleep(0.05)


async def test_defer_runs_artifacts_to_a_finished_job(env):
    """The handler stores the job id; the job carries per-artifact truth
    and finishes as a system-driven lifecycle."""
    engine, client, ledger = env
    job_id = await _pulled_job(client)

    job = await _job_done(client, job_id)
    assert job["data"]["action"] == "pull_reports"
    assert job["data"]["target_kind"] == "service:ledger"
    assert job["data"]["succeeded"] == 3
    assert [a["status"] for a in job["data"]["artifacts"]] \
        == ["succeeded"] * 3
    assert ledger.pulled == ["balance_sheet", "general_ledger", "positions"]

    async with engine.storage.session() as s:
        last = await engine.storage.last_transition(s, "job", job_id)
    assert last.action == "finish"
    assert last.actor_type == "system"


async def test_a_failing_artifact_keeps_its_cause_and_downs_the_service(env):
    """A service failure IS an outage (§10's declaration): the triggering
    artifact records the adapter's own words — the intake error-file need —
    and the rest fail fast with the backoff instead of hammering."""
    engine, client, ledger = env
    ledger.fail.add("general_ledger")
    job_id = await _pulled_job(client)

    job = await _job_done(client, job_id)
    by_name = {a["name"]: a for a in job["data"]["artifacts"]}
    assert by_name["balance_sheet"]["status"] == "succeeded"
    assert by_name["general_ledger"]["status"] == "failed"
    assert "malformed" in by_name["general_ledger"]["message"]
    assert by_name["positions"]["status"] == "failed"
    assert "retry at" in by_name["positions"]["message"]
    assert job["data"]["failed"] == 2 and job["data"]["succeeded"] == 1


async def test_service_down_fails_remaining_artifacts_with_retry_at(env):
    """ServiceDown honors the declared backoff: the job stops hammering
    and says when to come back — the un-advertised button, job-shaped."""
    engine, client, ledger = env

    async def die(report: str) -> str:
        raise ConnectionError("connection refused")
    ledger.pull = die
    engine.services.ledger.handler = die

    job_id = await _pulled_job(client)
    job = await _job_done(client, job_id)
    statuses = [a["status"] for a in job["data"]["artifacts"]]
    assert statuses == ["failed"] * 3
    assert "retry at" in job["data"]["artifacts"][1]["message"], \
        "artifacts after the outage carry the backoff, not fresh failures"
    assert engine.services.ledger.up(engine.invoker.clock()) is False


async def test_down_on_error_false_fails_one_artifact_alone(env):
    """Independent artifact failures (design E6): the intake shape — five
    sub-imports against one Beacon; a malformed contacts export fails with
    the adapter's own words while the other four succeed, and the
    service stays up, because one bad file is not a Beacon outage."""
    engine, client, ledger = env
    ledger.fail.add("contacts")
    book = (await _post(client, "/api/books", {"period": "2026-07"})).json()
    doc = (await _post(client, f"{book['self']}/-/import_icat")).json()
    job_id = doc["data"]["pull_job_id"]

    job = await _job_done(client, job_id)
    by_name = {a["name"]: a for a in job["data"]["artifacts"]}
    assert by_name["contacts"]["status"] == "failed"
    assert "malformed" in by_name["contacts"]["message"]
    for name in ("entities", "documents", "holdings", "agreements"):
        assert by_name[name]["status"] == "succeeded", by_name[name]
    assert job["data"]["succeeded"] == 4 and job["data"]["failed"] == 1
    assert engine.services.beacon.up(engine.invoker.clock()) is True


async def test_cancel_mid_run_stops_remaining_artifacts(env):
    engine, client, ledger = env
    ledger.hang.clear()  # the first artifact blocks until released
    job_id = await _pulled_job(client)

    res = await client.get(f"/api/jobs/{job_id}")
    doc = res.json()
    cancel = await client.post(
        f"/api/jobs/{job_id}/-/cancel",
        headers={"Idempotency-Key": uuid.uuid4().hex,
                 "If-Match": doc["meta"]["etag"]})
    assert cancel.status_code == 200, cancel.text
    ledger.hang.set()
    job = await _job_done(client, job_id, state="cancelled")
    assert job["data"]["succeeded"] == 0
    assert len(ledger.pulled) <= 1


async def test_orphaned_jobs_are_cancelled_at_boot(env):
    """A queued/running job whose lease has lapsed has no live worker:
    the sweep cancels it with the orphan reason on its unfinished
    artifacts. (This process's runner is alive and holds the lease, so
    playing dead means expiring it by hand — design E6 leases.)"""
    import waymark9.server.jobs as jobs_mod

    engine, client, ledger = env
    ledger.hang.clear()
    job_id = await _pulled_job(client)
    await asyncio.sleep(0.2)  # the runner is now mid-artifact

    past = engine.invoker.clock() - timedelta(seconds=1)
    async with engine.storage.session() as s:
        await engine.storage.renew_job_lease(
            s, job_id, engine.invoker.worker_id, past)

    swept = await jobs_mod.sweep_orphan_jobs(engine)
    assert swept == 1
    ledger.hang.set()
    job = await _job_done(client, job_id, state="cancelled")
    assert any(a["message"] == "orphaned by a worker restart"
               for a in job["data"]["artifacts"])
