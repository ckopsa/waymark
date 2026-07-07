"""The engine-provided job resource (§7.4, §14): deferred bulk executions are
ordinary Waymark resources — progress in ``data``, a ``cancel`` action, and
transitions in the same audit log as everything else.
"""
from __future__ import annotations

from enum import StrEnum
from typing import Any

from pydantic import BaseModel, Field

from ..core.resource import Resource
from ..core.actions import action
from ..core.types import Acknowledged, Ctx, Principal, Safety

SYSTEM = Principal(id="waymark-engine", type="system", display="Waymark engine")


class JobState(StrEnum):
    QUEUED = "queued"
    RUNNING = "running"
    DONE = "done"
    CANCELLED = "cancelled"


class Refusal(BaseModel):
    # the report table must show these whatever their length (§7.4)
    self: str = Field(json_schema_extra={"x-display": {"raw": True}})
    reason: str = Field(json_schema_extra={"x-display": {"raw": True}})


class JobArtifact(BaseModel):
    """One named piece of a deferred service invocation (design E6) —
    the per-dataset sub-status every hand-built import queue grew."""

    name: str = Field(max_length=200)
    status: str = Field(default="pending", max_length=16,
                        json_schema_extra={"x-display": {"raw": True}})
    message: str | None = Field(default=None, max_length=240)


class JobData(BaseModel):
    action: str = Field(max_length=200)
    target_kind: str = Field(max_length=200)
    total: int = Field(ge=0)
    processed: int = 0
    succeeded: int = 0
    refused: int = 0
    failed: int = 0
    refusals: list[Refusal] = []
    # service jobs (design E6) carry one entry per artifact; bulk jobs none
    artifacts: list[JobArtifact] = []


class Job(Resource):
    kind = "job"
    State = JobState
    Data = JobData

    initial = JobState.QUEUED
    terminal = {JobState.DONE, JobState.CANCELLED}

    # the summary orients, never identifies: no {id} (the href carries it)
    summary = ("Job: {data.action} on {data.target_kind} · "
               "{data.processed}/{data.total} processed · {state.label}")

    @action(from_=JobState.QUEUED, to=JobState.RUNNING,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "System-driven lifecycle step; starting a job "
                              "loses nothing.")),
            display=dict(label="Start"))
    async def start(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=JobState.RUNNING, to=JobState.DONE,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "System-driven completion; the work is already "
                              "done when this fires.")),
            display=dict(label="Finish"))
    async def finish(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_={JobState.QUEUED, JobState.RUNNING}, to=JobState.CANCELLED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="Remaining items will not be processed; "
                                      "items already processed stay done."),
            display=dict(label="Cancel job", style="danger"))
    async def cancel(self, inp: None, ctx: Ctx) -> None:
        pass


async def sweep_orphan_jobs(engine: Any) -> int:
    """Startup honesty (design E6): a queued/running job whose lease is
    absent or expired has no live worker — cancel it as the system, with
    the orphan reason on its unfinished artifacts, rather than letting a
    dead job render as running forever. Multi-worker safe: a lease held
    with a future expiry is another live worker's job and is skipped; a
    died worker's job is cancelled once its lease expires. Resuming such
    a job instead of cancelling it is a recorded punt."""
    cancelled = 0
    async with engine.storage.session() as s:
        orphans, _ = await engine.storage.query(
            s, "job", filters={"state": ["queued", "running"]},
            sort=None, page_size=500, page_number=1)
    for job in orphans:
        now = engine.invoker.clock()
        async with engine.storage.session() as s:
            lease = await engine.storage.job_lease(s, job.id)
        if lease is not None and lease[1] > now:
            continue  # a live worker owns it (design E6 leases)
        async with engine.storage.session() as s:
            fresh = await engine.storage.load(s, "job", job.id,
                                              for_update=True)
            if fresh is None or fresh.state not in ("queued", "running"):
                continue
            for artifact in fresh.data.artifacts:
                if artifact.status in ("pending", "running"):
                    artifact.status = "failed"
                    artifact.message = "orphaned by a worker restart"
            fresh.version += 1
            fresh.updated_at = engine.invoker.clock()
            await engine.storage.save(s, "job", fresh,
                                      expected_version=fresh.version - 1)
        await engine.invoker.invoke("job", job.id, "cancel", None,
                                    principal=SYSTEM)
        if lease is not None:
            # the dead worker's stale lease row goes with its job
            async with engine.storage.session() as s:
                await engine.storage.release_job_lease(s, job.id, lease[0])
        cancelled += 1
    return cancelled
