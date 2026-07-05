"""The PrepTask resource: when to thaw the meat and when to start cooking.

After a plan is finalized, the AI derives one task per meal step from the
recipe's ``thaw_hours`` / ``prep_minutes`` and creates them here. Putting a
task on the family calendar is outward-facing, so ``schedule`` is
confirm-gated with its consequence spelled out: the agent client hard-stops
until a human approves, then creates the calendar event and records its id
back on the task.

2.0: ``plan_id`` is a ``Ref``; the schedule input names what it actually is
(the *new* event's id, not an edit of the stored one), which is why it
declares no Edit.
"""
from __future__ import annotations

from datetime import date as date_t
from enum import StrEnum
from typing import Literal

from pydantic import AwareDatetime, BaseModel, Field

from waymark2 import (
    Acknowledged,
    Ctx,
    Ref,
    RefField,
    Resource,
    Safety,
    action,
    filterable,
    link,
    profile,
    sortable,
)


class PrepState(StrEnum):
    PENDING = "pending"      # derived from the plan, not yet on the calendar
    SCHEDULED = "scheduled"  # a calendar event exists for it
    DONE = "done"
    CANCELLED = "cancelled"


class PrepData(BaseModel):
    plan_id: Ref["plan"] = RefField(
        min_length=1, description="The meal plan this task serves")
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
    # named for what it is: the id of the NEW event the agent just created —
    # not an edit of the stored one, so no prefill and no Edit declaration
    event_id: str = Field(min_length=1, max_length=200,
                          description="The calendar event that was created "
                                      "for this task")


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
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="An event goes on the family calendar "
                                      "for this prep step."),
            display=dict(label="Put on calendar", style="primary", order=1))
    async def schedule(self, inp: ScheduleInput, ctx: Ctx) -> None:
        self.data.calendar_event_id = inp.event_id

    @action(from_={PrepState.PENDING, PrepState.SCHEDULED}, to=PrepState.DONE,
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Marking a prep step done records kitchen "
                              "reality; nothing external changes.")),
            display=dict(label="Done", order=2))
    async def complete(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_={PrepState.PENDING, PrepState.SCHEDULED},
            to=PrepState.CANCELLED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The task is dropped; any calendar "
                                      "event for it should be removed by "
                                      "hand."),
            display=dict(label="Cancel", style="danger", order=9))
    async def cancel(self, inp: None, ctx: Ctx) -> None:
        pass
