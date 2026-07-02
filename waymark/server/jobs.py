"""The engine-provided job resource (§7.4, §14): deferred bulk executions are
ordinary Waymark resources — progress in ``data``, a ``cancel`` action, and
transitions in the same audit log as everything else.
"""
from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, Field

from ..core.resource import Resource
from ..core.actions import action
from ..core.types import Ctx, Principal

SYSTEM = Principal(id="waymark-engine", type="system", display="Waymark engine")


class JobState(StrEnum):
    QUEUED = "queued"
    RUNNING = "running"
    DONE = "done"
    CANCELLED = "cancelled"


class Refusal(BaseModel):
    self: str
    reason: str


class JobData(BaseModel):
    action: str
    target_kind: str
    total: int = Field(ge=0)
    processed: int = 0
    succeeded: int = 0
    refused: int = 0
    failed: int = 0
    refusals: list[Refusal] = []


class Job(Resource):
    kind = "job"
    State = JobState
    Data = JobData

    initial = JobState.QUEUED
    terminal = {JobState.DONE, JobState.CANCELLED}

    summary = ("Job {id} · {data.action} · {data.processed}/{data.total} "
               "processed · {state.label}")

    @action(from_=JobState.QUEUED, to=JobState.RUNNING,
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Start"))
    async def start(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=JobState.RUNNING, to=JobState.DONE,
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Finish"))
    async def finish(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_={JobState.QUEUED, JobState.RUNNING}, to=JobState.CANCELLED,
            idempotent=True, reversible=False, confirm=True,
            display=dict(label="Cancel job", style="danger"))
    async def cancel(self, inp: None, ctx: Ctx) -> None:
        pass
