"""Job leases (design E6 completion): a queued/running job belongs to
exactly one live worker. Claims are atomic claim-or-steal keyed on
expiry, the startup sweep skips a neighbor's live job and reclaims a
dead one, and a finished job leaves no lease behind — the multi-worker
deployment E6 recorded as a punt.
"""
from __future__ import annotations

import asyncio
from datetime import timedelta

import pytest

import waymark8.server.jobs as jobs_mod

from .test_service_jobs import (  # noqa: F401 — env is the fixture
    env,
    _job_done,
    _pulled_job,
)

pytestmark = pytest.mark.asyncio


async def test_claim_is_atomic_claim_or_steal(env):
    """Worker A's claim holds against worker B until it expires; an
    expired lease is stolen, not queued behind."""
    engine, _client, _ledger = env
    now = engine.invoker.clock()
    ttl = timedelta(seconds=30)

    async with engine.storage.session() as s:
        assert await engine.storage.claim_job_lease(
            s, "job-1", "worker-a", now + ttl, now) is True
    async with engine.storage.session() as s:
        assert await engine.storage.claim_job_lease(
            s, "job-1", "worker-b", now + ttl, now) is False
    async with engine.storage.session() as s:
        held = await engine.storage.job_lease(s, "job-1")
    assert held is not None and held[0] == "worker-a"

    later = now + ttl + timedelta(seconds=1)
    async with engine.storage.session() as s:
        assert await engine.storage.claim_job_lease(
            s, "job-1", "worker-b", later + ttl, later) is True
    async with engine.storage.session() as s:
        held = await engine.storage.job_lease(s, "job-1")
    assert held is not None and held[0] == "worker-b"


async def test_sweep_skips_a_live_neighbors_job(env):
    """The multi-worker boot story: worker A's sweep must not cancel
    worker B's live (leased, unexpired) job; once B's lease lapses the
    job is B's corpse and the sweep cancels it honestly."""
    engine, client, ledger = env
    ledger.hang.clear()
    job_id = await _pulled_job(client)
    await asyncio.sleep(0.2)  # the runner is now mid-artifact

    # a neighbor worker takes the lease with a future expiry (the steal
    # is forced by a future `now`; atomicity has its own test above)
    ahead = engine.invoker.clock() + timedelta(seconds=120)
    async with engine.storage.session() as s:
        assert await engine.storage.claim_job_lease(
            s, job_id, "worker-elsewhere", ahead + timedelta(seconds=30),
            ahead) is True

    assert await jobs_mod.sweep_orphan_jobs(engine) == 0
    res = await client.get(f"/api/jobs/{job_id}")
    assert res.json()["state"] == "running", \
        "a live neighbor's job survived its own worker; not the sweep's"

    # the neighbor dies: its lease expires and the sweep reclaims
    past = engine.invoker.clock() - timedelta(seconds=1)
    async with engine.storage.session() as s:
        await engine.storage.renew_job_lease(
            s, job_id, "worker-elsewhere", past)
    assert await jobs_mod.sweep_orphan_jobs(engine) == 1

    ledger.hang.set()
    job = await _job_done(client, job_id, state="cancelled")
    assert any(a["message"] == "orphaned by a worker restart"
               for a in job["data"]["artifacts"])
    async with engine.storage.session() as s:
        assert await engine.storage.job_lease(s, job_id) is None, \
            "the dead worker's stale lease row goes with its job"


async def test_a_finished_job_leaves_no_lease(env):
    """Runner lifecycle: the lease is claimed before start, renewed per
    artifact, and released after finish — a done job holds nothing."""
    engine, client, _ledger = env
    job_id = await _pulled_job(client)
    await _job_done(client, job_id)

    deadline = asyncio.get_event_loop().time() + 2.0
    while True:
        async with engine.storage.session() as s:
            lease = await engine.storage.job_lease(s, job_id)
        if lease is None:
            break
        assert asyncio.get_event_loop().time() < deadline, \
            "lease row survived a normal finish"
        await asyncio.sleep(0.05)
