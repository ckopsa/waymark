"""The PrepTask resource: when to thaw the meat and when to start cooking.

After a plan is finalized, the AI derives one task per meal step from the
recipe's ``thaw_hours`` / ``prep_minutes`` and creates them here. Putting a
task on the family calendar is outward-facing, so ``schedule`` is
``confirm=true``: the agent client hard-stops until a human approves, then
creates the calendar event and records its id back on the task.
"""
from __future__ import annotations

from datetime import date as date_t
from enum import StrEnum
from typing import Literal

from pydantic import AwareDatetime, BaseModel, Field

from waymark import Ctx, Resource, action, filterable, link, profile, sortable


class PrepState(StrEnum):
    PENDING = "pending"      # derived from the plan, not yet on the calendar
    SCHEDULED = "scheduled"  # a calendar event exists for it
    DONE = "done"
    CANCELLED = "cancelled"


class PrepData(BaseModel):
    plan_id: str = Field(min_length=1,
                         description="The meal plan this task serves",
                         json_schema_extra={"x-display": {
                             "label": "Plan",
                             "widget": "resource", "kind": "plan"}})
    date: date_t = Field(description="The dinner this task serves")
    meal_name: str = Field(min_length=1, max_length=200)
    task_type: Literal["thaw", "prep", "cook"] = "prep"
    due_at: AwareDatetime = Field(description="When to start this step")
    duration_minutes: int | None = Field(default=None, ge=0)
    calendar_event_id: str | None = Field(
        default=None, description="Set by `schedule` once the event exists",
        json_schema_extra={"x-display": {"hidden": True}})
    notes: str | None = Field(default=None, max_length=1000,
                              json_schema_extra={"x-display": {
                                  "widget": "prose"}})


class ScheduleInput(BaseModel):
    calendar_event_id: str = Field(min_length=1, max_length=200)


class PrepTask(Resource):
    kind = "prep_task"
    State = PrepState
    Data = PrepData

    initial = PrepState.PENDING
    terminal = {PrepState.DONE, PrepState.CANCELLED}

    summary = "{data.task_type} · {data.meal_name} ({data.date}) · {state.label}"

    filterable = filterable(
        state=filterable.Eq | filterable.In,
        plan_id=filterable.Eq,
        task_type=filterable.Eq | filterable.In,
        due_at=filterable.After,
    )
    sortable = sortable("due_at", default="due_at")

    display = {"title": "{data.task_type}: {data.meal_name}"}

    links = (
        link("plan", kind="plan", href="/plans/{data.plan_id}",
             summary="The meal plan this task serves"),
    )

    profiles = {
        "with_plan": profile(embed={"plan": "summary"}),
    }

    @action(from_=PrepState.PENDING, to=PrepState.SCHEDULED,
            input=ScheduleInput,
            # not an edit: the input records a NEW external event id the
            # agent just created — prefilling the old one would be wrong
            waives=("blank_edit",),
            idempotent=True, reversible=False, confirm=True,
            display=dict(label="Put on calendar", style="primary", order=1))
    async def schedule(self, inp: ScheduleInput, ctx: Ctx) -> None:
        self.data.calendar_event_id = inp.calendar_event_id

    @action(from_={PrepState.PENDING, PrepState.SCHEDULED}, to=PrepState.DONE,
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Done", order=2))
    async def complete(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_={PrepState.PENDING, PrepState.SCHEDULED},
            to=PrepState.CANCELLED,
            idempotent=True, reversible=False, confirm=True,
            display=dict(label="Cancel", style="danger", order=9))
    async def cancel(self, inp: None, ctx: Ctx) -> None:
        pass
