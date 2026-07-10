"""The family calendar Event: a read-only Mirror of the Google Calendar
feed (design 6.0 §1-§2's driving story, revised).

Every earlier mealplan kept the calendar out of the system because the
plan couldn't own it — a recital doesn't belong to a meal plan, it merely
*overlaps* one. The plan cites ``event`` through a date-containment
predicate, so an event needs nothing but its own honest fields. Those
fields are no longer authored here: the calendar is the family's, kept in
Google Calendar (Home Assistant's REST calendar API turned out to be
blocked in front of the house's public hostname — see event_source.py),
so ``event`` is a pure whole-resource Mirror (``MirrorMeta``, not
per-field ``Authored``) — pulled, never written, ``push_on_write=False``.
A declared ``Discover`` sweep mints a resource per feed occurrence on the
clock tick; fields arrive on the resource's first pull-through read.

``kind`` still exists so the plan's overlap predicate has something to
filter on, but every pulled event is minted ``note`` (non-blocking) for
now — the feed carries no signal for "this evening is spoken for" (design
note: revisit if that distinction turns out to matter in practice).
"""
from __future__ import annotations

from datetime import date as date_t
from typing import Any, Literal

from pydantic import Field
from pydantic.json_schema import SkipJsonSchema

from waymark9 import filterable, sortable
from waymark9.server.external import Discover, Mirror, MirrorMeta

from ..event_source import EVENTS

# a personal calendar changes rarely enough that an hour between
# pull-throughs is plenty
TTL_SECONDS = 3_600
# the discovery sweep cadence: once an hour picks up newly-added events
DISCOVER_EVERY = 3_600.0


class EventData(MirrorMeta):
    # all authority-written (the Mirror sync path sets these; no handler
    # or create body may)
    title: str | None = Field(
        default=None, max_length=120,
        description="What the family has on, per the calendar")
    date: date_t | None = Field(default=None, description="The day it happens")
    kind: Literal["blocking", "note"] = Field(
        default="note",
        description="blocking: the evening is spoken for (counts as a "
                    "conflict); note: worth seeing, doesn't block dinner — "
                    "every calendar-sourced event is minted note for now")


class EventCreate(MirrorMeta):
    """An event enters by its feed identity alone: the display fields are
    the calendar's, filled on the first pull-through read (a mint carries
    only ``external_id``)."""

    title: SkipJsonSchema[str | None] = None
    date: SkipJsonSchema[date_t | None] = None
    kind: SkipJsonSchema[Literal["blocking", "note"]] = "note"


class Event(Mirror):
    kind = "event"
    Data = EventData
    Create = EventCreate

    adapter = EVENTS
    ttl_seconds = TTL_SECONDS
    push_on_write = False  # read-only: an event is the calendar's, not ours
    discover = Discover(every=DISCOVER_EVERY)

    summary = "{data.title} · {data.date}"
    display = {"title": "{data.title} — {data.date}"}

    filterable = filterable(
        external_id=filterable.Eq,
        date=filterable.Eq | filterable.Range,
        kind=filterable.Eq | filterable.In,
        state=filterable.Eq | filterable.In,
    )
    sortable = sortable("date", default="date")

    def apply_external(self, document: dict[str, Any]) -> None:
        # the wire/storage document keeps date as an ISO string (JSON-safe
        # for the transition record); the default Mirror.apply_external
        # does a plain setattr with no parsing, so a real ``date`` needs
        # this override
        document = dict(document)
        if isinstance(document.get("date"), str):
            document["date"] = date_t.fromisoformat(document["date"])
        super().apply_external(document)
