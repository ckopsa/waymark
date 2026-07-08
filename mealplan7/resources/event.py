"""The family calendar Event: the kind that is nobody's child (design 6.0
§1–§2, the driving story).

Every earlier mealplan kept the calendar out of the system because the
plan couldn't own it — a recital doesn't belong to a meal plan, it merely
*overlaps* one. 6.0 makes the overlap a declared relation: the plan cites
``event`` through a date-containment predicate, so an event needs nothing
but its own honest fields. ``kind`` says whether the evening is spoken
for (``blocking`` — a recital, a ward activity) or merely noted
(``note`` — a birthday to bake for); only blocking events count as
conflicts on the plans whose weeks contain them.

The lifecycle is deliberately tiny: an event is ``scheduled`` until it's
``cancelled``; a moved date is ``reschedule`` (an Edit — the form opens
on the stored date, fenced against concurrent edits). Both writes run
the inverted predicate, so the plans on either side of the move learn
the truth in the same commit.
"""
from __future__ import annotations

from datetime import date as date_t
from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, Field

from waymark7 import (
    Ctx,
    Edit,
    Resource,
    Safety,
    action,
    filterable,
    sortable,
)


class EventState(StrEnum):
    SCHEDULED = "scheduled"
    CANCELLED = "cancelled"


class EventData(BaseModel):
    title: str = Field(min_length=1, max_length=120,
                       description="What the family has on — a recital, "
                                   "a birthday, the ward picnic…")
    # promoted Eq|Range (design §3): the honest date grammar serves the
    # family's "what's on this week?" filter AND the plan's join predicate
    date: date_t = Field(description="The day it happens")
    kind: Literal["blocking", "note"] = Field(
        default="blocking",
        description="blocking: the evening is spoken for (counts as a "
                    "conflict); note: worth seeing, doesn't block dinner")


class RescheduleInput(BaseModel):
    date: date_t = Field(description="The new day")


class Event(Resource):
    kind = "event"
    State = EventState
    Data = EventData

    initial = EventState.SCHEDULED
    terminal = {EventState.CANCELLED}

    summary = "{data.title} · {data.date} · {state.label}"

    filterable = filterable(
        state=filterable.Eq | filterable.In,
        date=filterable.Eq | filterable.Range,
        kind=filterable.Eq | filterable.In,
    )
    sortable = sortable("date", default="date")

    display = {"title": "{data.title} — {data.date}"}

    @action(from_=EventState.SCHEDULED, to=EventState.SCHEDULED,
            input=RescheduleInput,
            # the form opens on the stored date, fenced: a mis-click must
            # not silently move someone else's concurrent reschedule
            edit=Edit(prefill=("date",)),
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Reschedule", order=2))
    async def reschedule(self, inp: RescheduleInput, ctx: Ctx) -> None:
        self.data.date = inp.date

    @action(from_=EventState.SCHEDULED, to=EventState.CANCELLED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The event comes off the family "
                                      "calendar; plans it conflicted with "
                                      "clear in the same breath."),
            display=dict(label="Cancel", style="danger", order=9))
    async def cancel(self, inp: None, ctx: Ctx) -> None:
        pass
