"""Proposals show their blast radius (design 7.0 §2).

``measure`` on a proposed definition defers an E6 job: for each derived
fact the diff marks redefined, a FULL scan of the target kind's rows
(the maintainer's paging discipline — no silent sampling) comparing the
stored current-law value against a fresh computation under the resident
proposed parameters. The per-fact flip report lands as the job
artifact's ``detail`` — ``{"fact": ..., "flips": n, "of": total,
"sample": [...], "scan": "full"}`` — and the job id lands on the
proposal (``data.measure_job``), linkable from the review.
"""
from __future__ import annotations

import asyncio
import os
import uuid

import pytest
from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient

import waymark8
from waymark8.server.bus import InProcessBus
from waymark8.server.engine import header_principal
from waymark8.testing import per_worker_dsn

from .test_definition_lifecycle import TOL_V1, TOL_V2, make_wb

pytestmark = pytest.mark.asyncio

TEST_DSN = per_worker_dsn(os.environ.get(
    "WAYMARK_TEST_DSN", "postgresql+asyncpg://localhost/waymark_test"))

ELENA = {"X-Principal-Id": "elena", "X-Principal-Display": "Elena V."}


async def _boot(cls, *, deploy: str = "auto", drop: bool = False):
    engine = waymark8.Engine(resources=[cls], storage=TEST_DSN,
                             principal=header_principal, services=None,
                             bus=InProcessBus(), deploy=deploy)
    if drop:
        await engine.storage.drop_all()
    await engine.startup()
    return engine


def _client(engine) -> AsyncClient:
    app = FastAPI()
    app.include_router(engine.router, prefix="/api")
    return AsyncClient(transport=ASGITransport(app=app),
                       base_url="http://t", headers=ELENA)


async def _post(client, href, json=None):
    return await client.post(href, json=json,
                             headers={"Idempotency-Key": uuid.uuid4().hex})


async def _wait_done(engine, job_id, timeout=10.0):
    """Poll the job resource until the deferred measure finishes."""
    deadline = asyncio.get_event_loop().time() + timeout
    while True:
        async with engine.storage.session() as s:
            job = await engine.storage.load(s, "job", job_id)
        if job is not None and job.state == "done":
            return job
        assert asyncio.get_event_loop().time() < deadline, \
            f"measure job {job_id} did not finish (state: " \
            f"{job.state if job else 'missing'})"
        await asyncio.sleep(0.05)


async def test_measure_reports_honest_flip_count_and_sample():
    # law A (0.05): near/mid/edge reconcile, far does not
    e1 = await _boot(make_wb(TOL_V1), drop=True)
    client = _client(e1)
    flip_ids = []
    try:
        for title, amount in (("near", 0.003), ("mid", 0.02),
                              ("edge", 0.04), ("far", 0.2)):
            res = await _post(client, "/api/lwbs",
                              {"title": title, "amount": amount})
            assert res.status_code == 201, res.text
            doc = res.json()
            expected = abs(amount) <= 0.05
            assert doc["data"]["reconciled"] is expected
            if expected != (abs(amount) <= 0.01):
                # the rows whose fact flips under the proposed 0.01
                flip_ids.append(doc["self"].rsplit("/", 1)[-1])
    finally:
        await client.aclose()
        await e1.shutdown()
    assert len(flip_ids) == 2  # mid and edge

    # the proposal arrives; the current law keeps serving
    e2 = await _boot(make_wb(TOL_V2), deploy="propose")
    client = _client(e2)
    try:
        async with e2.storage.session() as s:
            revs, _ = await e2.storage.query(
                s, "definition", filters={"target_kind": "lwb",
                                          "state": "proposed"},
                sort=None, page_size=10, page_number=1)
        assert len(revs) == 1
        proposal = revs[0]

        res = await _post(client, f"/api/definitions/{proposal.id}/-/measure")
        assert res.status_code == 200, res.text
        doc = res.json()
        assert doc["state"] == "proposed", "measure holds the state"
        job_id = doc["data"]["measure_job"]
        assert job_id, "the report is linkable from the review"

        job = await _wait_done(e2, job_id)
        assert job.data.action == "measure"
        assert job.data.succeeded == 1 and job.data.failed == 0
        (artifact,) = job.data.artifacts
        assert artifact.name == "lwb.reconciled"
        assert artifact.status == "succeeded"
        report = artifact.detail
        assert report is not None, "the flip report is the job artifact"
        assert report["fact"] == "lwb.reconciled"
        assert report["flips"] == 2, \
            "0.02 and 0.04 flip between 0.05 and 0.01; 0.003 and 0.2 hold"
        assert report["of"] == 4
        assert sorted(report["sample"]) == sorted(flip_ids), \
            "the sample names the flipped rows"
        assert report["scan"] == "full", \
            "no silent sampling — the artifact says what was scanned"

        # measuring changed nothing: stored truth is still the current law
        async with e2.storage.session() as s:
            rows, _ = await e2.storage.query(
                s, "lwb", filters={}, sort=None,
                page_size=10, page_number=1)
        assert {r.data.title: r.data.reconciled for r in rows} == \
            {"near": True, "mid": True, "edge": True, "far": False}
    finally:
        await client.aclose()
        await e2.shutdown()
