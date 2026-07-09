"""The event boundary, declared as a Mirror's adapter (see event.py).

Home Assistant's REST calendar API (``/api/calendars/<entity_id>``) turned
out to be blocked by whatever sits in front of the house's public hostname
(``/api/states`` and ``/api/config`` work with the same token; every
``/api/calendars*`` path 404s) — so this pulls straight from the private
Google Calendar iCal feed instead (Settings → Integrate calendar → "Secret
address in iCal format"). That URL is a bearer secret: anyone holding it
can read the calendar, so it lives only in ``MEALPLAN_GCAL_ICS_URL``, never
in source.

A feed has no natural resource ids, so an occurrence's ``external_id`` is
``{uid}@{start date}`` — the pairing that makes one recurring VEVENT's many
occurrences distinct mirrored resources (each with its own date, exactly
what the plan's overlap predicate needs). ``recurring_ical_events`` expands
``RRULE``s into concrete occurrences within a window; a plain feed fetch
would hand back one VEVENT per series, undated for any but its first
occurrence.
"""
from __future__ import annotations

import hashlib
from datetime import date as date_t, datetime, time, timedelta, timezone
from typing import Any

import httpx
import recurring_ical_events
from icalendar import Calendar

# the default rolling window when no plan says otherwise: enough to
# discover anything a mealplan week board could still care about (a plan
# a week old, or one being built a couple months out). Widened per-fetch
# (see _needed_window) to cover every existing plan's own date range, so
# a plan further out than this default is never left unsynced.
WINDOW_PAST = timedelta(days=7)
WINDOW_FUTURE = timedelta(days=90)


def _to_date(value: Any) -> date_t:
    if isinstance(value, datetime):
        return value.date()
    return value


def _external_id(uid: str, start: date_t) -> str:
    return f"{uid}@{start.isoformat()}"


def _content_etag(doc: dict[str, Any]) -> str:
    return hashlib.sha256(
        f"{doc['title']}|{doc['date']}|{doc.get('kind', 'note')}"
        .encode()).hexdigest()


# a sane bound on a VEVENT's DTSTART year: real calendars only need this
# century either side. Some Google Calendar entries carry a corrupted
# DTSTART (observed: year 1) that blows up recurring_ical_events'
# timezone-normalization pass (an OverflowError) for the WHOLE feed, not
# just that one event — so bad entries are dropped before that pass runs,
# rather than letting one corrupt event take down every occurrence.
_MIN_SANE_YEAR = 1900
_MAX_SANE_YEAR = 2200


def _sane_calendar(cal: Calendar) -> Calendar:
    clean = Calendar()
    for key, value in cal.items():
        clean.add(key, value)
    for component in cal.subcomponents:
        if component.name != "VEVENT":
            clean.add_component(component)
            continue
        dtstart = component.get("DTSTART")
        year = getattr(dtstart.dt, "year", None) if dtstart else None
        if year is not None and (year < _MIN_SANE_YEAR or year > _MAX_SANE_YEAR):
            continue
        clean.add_component(component)
    return clean


class GoogleCalendarEvents:
    """The real event boundary: one HTTP fetch of the private ``.ics`` feed
    per call, expanded to concrete occurrences over the window above. The
    feed is small (a personal calendar), so fetching whole and filtering in
    memory is simpler than any partial-fetch scheme — no ``push`` (a
    mealplan event is the calendar's, never ours to write back)."""

    def __init__(self, ics_url: str) -> None:
        self.ics_url = ics_url
        # set post-construction by main.py (the same late-bound seam
        # ledger7's adapters use for their own engine handle) — lets
        # discovery see every currently-existing plan's date range, not
        # just a fixed window around "now"
        self.engine: Any = None

    async def _needed_window(self, now: datetime) -> tuple[datetime, datetime]:
        """The default rolling window, widened to cover every existing
        plan's own start/end — a plan built further out than
        WINDOW_FUTURE (or looking back further than WINDOW_PAST) still
        gets its whole week's calendar synced, not just whatever falls in
        the default range."""
        start, end = now - WINDOW_PAST, now + WINDOW_FUTURE
        if self.engine is None:
            return start, end
        async with self.engine.storage.session() as s:
            plans, _ = await self.engine.storage.query(
                s, "plan", filters={}, sort=None, page_size=500,
                page_number=1)
        for plan in plans:
            plan_start = datetime.combine(
                plan.data.start_date, time.min, tzinfo=timezone.utc)
            plan_end = datetime.combine(
                plan.data.end_date, time.min, tzinfo=timezone.utc) + \
                timedelta(days=1)
            start, end = min(start, plan_start), max(end, plan_end)
        return start, end

    async def _occurrences(self) -> list[dict[str, Any]]:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(self.ics_url)
            resp.raise_for_status()
        cal = _sane_calendar(Calendar.from_ical(resp.content))
        start, end = await self._needed_window(datetime.utcnow())
        events = recurring_ical_events.of(cal).between(start, end)
        occurrences = []
        for vevent in events:
            uid = str(vevent.get("UID"))
            start = _to_date(vevent.get("DTSTART").dt)
            occurrences.append({
                "external_id": _external_id(uid, start),
                "title": str(vevent.get("SUMMARY", "Untitled")),
                "date": start,
            })
        return occurrences

    async def discover(self) -> list[str]:
        return [o["external_id"] for o in await self._occurrences()]

    async def pull(self, external_id: str) -> tuple[dict[str, Any], str]:
        for o in await self._occurrences():
            if o["external_id"] == external_id:
                # the feed has no signal for "this evening is spoken for" —
                # every pulled occurrence is minted note (see event.py)
                doc = {"title": o["title"], "date": o["date"].isoformat(),
                       "kind": "note"}
                return doc, _content_etag(doc)
        raise LookupError(f"no calendar occurrence for {external_id!r} "
                          f"in the current window")

    async def push(self, external_id: str, document: dict[str, Any],
                   *, etag: str | None) -> str:  # pragma: no cover
        raise NotImplementedError(
            "events are read-only mirrors (push_on_write=False)")


class FakeEvents:
    """Scriptable in-memory event source: the dev/test twin of
    ``GoogleCalendarEvents`` (as ``FakeFunds`` is of ``WarehouseFunds``)."""

    def __init__(self) -> None:
        self.docs: dict[str, dict[str, Any]] = {}
        self.discoverable: list[str] = []
        self.removed: set[str] = set()
        self.down = False
        self.pulls = 0

    def remove(self, external_id: str) -> None:
        """Simulate the family deleting the occurrence off the calendar:
        the next pull fails, exactly like a real feed no longer carrying
        it — unlike an unseeded id, which auto-vivifies (see ``_doc``)."""
        self.docs.pop(external_id, None)
        self.removed.add(external_id)

    def seed(self, external_id: str, *, title: str,
             date: date_t | str, kind: str = "note") -> None:
        if isinstance(date, date_t):
            date = date.isoformat()
        self.docs[external_id] = {"title": title, "date": date, "kind": kind}

    def _doc(self, external_id: str) -> dict[str, Any]:
        doc = self.docs.get(external_id)
        if doc is not None:
            return dict(doc)
        return {"title": external_id, "date": date_t.today().isoformat(),
                "kind": "note"}

    async def pull(self, external_id: str) -> tuple[dict[str, Any], str]:
        self.pulls += 1
        if self.down:
            raise ConnectionError("calendar feed unreachable")
        if external_id in self.removed:
            raise LookupError(f"{external_id!r} is no longer on the calendar")
        doc = self._doc(external_id)
        return doc, _content_etag(doc)

    async def discover(self) -> list[str]:
        if self.down:
            raise ConnectionError("calendar feed unreachable")
        return list(self.discoverable)

    async def push(self, external_id: str, document: dict[str, Any],
                   *, etag: str | None) -> str:  # pragma: no cover
        raise NotImplementedError(
            "events are read-only mirrors (push_on_write=False)")


# The module singleton the Event kind mirrors through (the framework's
# class-level adapter seam — see waymark7.server.external.Mirror). Tests
# clear it per-fixture; main.py swaps in GoogleCalendarEvents for the real
# server when MEALPLAN_GCAL_ICS_URL is set.
EVENTS = FakeEvents()
