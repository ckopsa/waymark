"""The MealPlan resource: one Tuesday-to-Tuesday week (or two, to save
grocery trips), each day pre-themed from the weekly calendar.

The lifecycle mirrors how the family actually plans:

- ``draft``: assign on-list meals to days (the guard holds each day to its
  theme; Sunday first needs a theme picked from the rotation), mark
  eating-out days, or override off-theme with an explicit confirm.
- ``planned``: every day is covered; the grocery list and prep tasks hang
  off this state.
- ``active`` → ``done``: the week runs its course.
"""
from __future__ import annotations

from datetime import date as date_t
from datetime import datetime, time, timedelta, timezone
from enum import StrEnum

from pydantic import BaseModel, Field, model_validator
from pydantic.json_schema import SkipJsonSchema

from waymark import Allow, Ctx, Deny, Resource, action, filterable, guard, sortable

from ..themes import ROTATING, WEEKDAY_THEMES
from .meal import Meal, MealState


class PlanState(StrEnum):
    DRAFT = "draft"
    PLANNED = "planned"
    ACTIVE = "active"
    DONE = "done"
    ABANDONED = "abandoned"


class DayPlan(BaseModel):
    date: date_t
    theme: str = Field(min_length=1, max_length=50)
    meal_id: str | None = Field(default=None, json_schema_extra={"x-display": {
        "label": "Meal", "widget": "resource", "kind": "meal",
        "label_field": "meal_name"}})
    meal_name: str | None = Field(default=None, max_length=200)
    eating_out: bool = False


class PlanData(BaseModel):
    start_date: date_t = Field(description="Our week runs Tuesday to Tuesday")
    weeks: int = Field(default=1, ge=1, le=2,
                       description="Plan 2 weeks to save on grocery trips")
    rotation_id: str | None = Field(
        default=None, description="The Sunday-theme rotation to draw from",
        json_schema_extra={"x-display": {
            "label": "Sunday rotation",
            "widget": "resource", "kind": "rotation"}})
    days: list[DayPlan] = Field(default_factory=list)
    notes: str | None = Field(default=None, max_length=2000,
                              json_schema_extra={"x-display": {
                                  "widget": "prose"}})

    @model_validator(mode="after")
    def _build_days(self) -> "PlanData":
        if not self.days:
            for i in range(7 * self.weeks):
                d = self.start_date + timedelta(days=i)
                self.days.append(DayPlan(date=d, theme=WEEKDAY_THEMES[d.weekday()]))
        return self


def _next_tuesday() -> date_t:
    today = date_t.today()
    return today + timedelta(days=(1 - today.weekday()) % 7)


class PlanCreate(PlanData):
    """The create form: only the decisions a human actually makes. ``days``
    is derived from the theme calendar, so it never appears; everything else
    defaults sensibly (blank start date = the coming Tuesday)."""

    start_date: date_t = Field(
        default_factory=_next_tuesday,
        description="Our week runs Tuesday to Tuesday. "
                    "Leave blank for the coming Tuesday.")
    rotation_id: str | None = Field(
        default=None, description="The Sunday-theme rotation to draw from. "
                                  "Leave blank for the active rotation.",
        json_schema_extra={"x-display": {
            "label": "Sunday rotation",
            "widget": "resource", "kind": "rotation"}})
    days: SkipJsonSchema[list[DayPlan]] = Field(default_factory=list)


class DayInput(BaseModel):
    date: date_t


class AssignInput(BaseModel):
    date: date_t
    meal_id: str = Field(min_length=1, json_schema_extra={"x-display": {
        "label": "Meal", "widget": "resource", "kind": "meal",
        "params": {"state": "on_list"}}})


class SundayThemeInput(BaseModel):
    date: date_t
    theme: str = Field(min_length=1, max_length=50)


def _day(r, d: date_t) -> DayPlan | None:
    return next((day for day in r.data.days if day.date == d), None)


# ── Guards ──────────────────────────────────────────────────────────────
@guard(else_="{date} is not a day in this plan.", vars=["date"],
       admits=("date", lambda r: [d.date.isoformat() for d in r.data.days]))
async def date_in_plan(r, inp: DayInput, ctx: Ctx) -> Allow | Deny:
    if _day(r, inp.date) is not None:
        return Allow()
    return Deny(vars={"date": inp.date.isoformat()},
                errors={"date": ["not a day in this plan"]})


@guard(else_="Meal {meal_id} is not on the meal list. Accept a suggestion "
             "(or ask the AI for one) first.",
       vars=["meal_id"], remedies=["meal.accept"])
async def meal_is_listed(r, inp: AssignInput, ctx: Ctx) -> Allow | Deny:
    meal = await ctx.read(Meal, inp.meal_id)
    if meal is not None and meal.state == str(MealState.ON_LIST):
        return Allow()
    return Deny(vars={"meal_id": inp.meal_id},
                errors={"meal_id": ["not an on-list meal"]})


@guard(else_="{reason}", vars=["reason"],
       remedies=["plan.set_sunday_theme", "plan.assign_off_theme"])
async def meal_matches_theme(r, inp: AssignInput, ctx: Ctx) -> Allow | Deny:
    day = _day(r, inp.date)
    if day is None:
        return Deny(vars={"reason": f"{inp.date} is not a day in this plan."})
    if day.theme == ROTATING:
        return Deny(vars={"reason": f"Pick {inp.date}'s Sunday theme from the "
                                    "rotation first, then assign — or assign "
                                    "off-theme with confirmation."})
    meal = await ctx.read(Meal, inp.meal_id)
    if meal is None:
        return Deny(vars={"reason": f"Meal {inp.meal_id} does not exist."})
    if meal.data.theme != day.theme:
        return Deny(
            vars={"reason": f"'{meal.data.name}' is {meal.data.theme}; "
                            f"{inp.date} is {day.theme} night. Use "
                            "assign_off_theme to override."},
            errors={"meal_id": [f"theme {meal.data.theme!r} does not match "
                                f"{day.theme!r}"]})
    return Allow()


@guard(else_="Only Sunday rotates; {date} is {theme} night.", vars=["date", "theme"],
       admits=("date", lambda r: [d.date.isoformat() for d in r.data.days
                                  if d.date.weekday() == 6]))
async def sunday_only(r, inp: SundayThemeInput, ctx: Ctx) -> Allow | Deny:
    if inp.date.weekday() == 6:
        return Allow()
    day = _day(r, inp.date)
    return Deny(vars={"date": inp.date.isoformat(),
                      "theme": day.theme if day else "not planned"},
                errors={"date": ["not a Sunday"]})


async def _rotation_themes(r, ctx: Ctx) -> list[str] | None:
    """The linked rotation's themes — evaluated server-side at render time so
    the theme field offers real choices. None (no constraint) when no
    rotation is linked: the guard allows any theme then."""
    if r.data.rotation_id is None:
        return None
    rotation = await ctx.read("rotation", r.data.rotation_id)
    return list(rotation.data.themes) if rotation is not None else None


@guard(else_="'{theme}' is not in the Sunday rotation. Add it there first.",
       vars=["theme"], remedies=["rotation.add_theme"],
       admits=("theme", _rotation_themes))
async def theme_in_rotation(r, inp: SundayThemeInput, ctx: Ctx) -> Allow | Deny:
    if r.data.rotation_id is None:
        return Allow()  # no rotation linked — any theme goes
    rotation = await ctx.read("rotation", r.data.rotation_id)
    if rotation is None:
        return Deny(vars={"theme": inp.theme},
                    errors={"rotation_id": ["rotation not found"]})
    if inp.theme in rotation.data.themes:
        return Allow()
    return Deny(vars={"theme": inp.theme},
                errors={"theme": ["not in the Sunday rotation"]})


@guard(else_="Every day needs a meal or an eating-out mark before finalizing; "
             "missing: {missing}.", vars=["missing"])
async def all_days_covered(r, inp, ctx: Ctx) -> Allow | Deny:
    missing = [d.date.isoformat() for d in r.data.days
               if not d.eating_out and d.meal_id is None]
    if not missing:
        return Allow()
    return Deny(vars={"missing": ", ".join(missing)})


def _start_of(r) -> datetime:
    return datetime.combine(r.data.start_date, time.min, tzinfo=timezone.utc)


@guard(else_="The plan starts {start}.", vars=["start"],
       becomes_available_at=_start_of)
async def plan_started(r, inp, ctx: Ctx) -> Allow | Deny:
    if ctx.now.astimezone(timezone.utc).date() >= r.data.start_date:
        return Allow()
    return Deny(vars={"start": r.data.start_date.isoformat()})


# ── Resource ────────────────────────────────────────────────────────────
class MealPlan(Resource):
    kind = "plan"
    State = PlanState
    Data = PlanData
    Create = PlanCreate

    initial = PlanState.DRAFT
    terminal = {PlanState.DONE, PlanState.ABANDONED}

    summary = ("Week of {data.start_date} · {data.weeks} wk · "
               "{data.days|len} days · {state.label}")

    filterable = filterable(state=filterable.Eq | filterable.In)
    sortable = sortable("start_date", default="-start_date")

    display = {"title": "Meal plan — week of {data.start_date}"}

    spans = (Meal,)

    async def on_create(self, ctx: Ctx) -> None:
        """A blank rotation_id means the most recently activated active
        rotation; each rotating
        Sunday is pre-themed from it, walking the list from ``position``.
        With no rotation at all, Sundays stay ``rotating`` and the old flow
        (``set_sunday_theme`` first) still applies."""
        if self.data.rotation_id is None:
            active = await ctx.find("rotation", state="active", limit=100)
            if active:
                epoch = datetime.min.replace(tzinfo=timezone.utc)
                freshest = max(active,
                               key=lambda r: r.data.activated_at or epoch)
                self.data.rotation_id = freshest.id
        if self.data.rotation_id is None:
            return
        rotation = await ctx.read("rotation", self.data.rotation_id)
        if rotation is None:
            return
        themes, pos = rotation.data.themes, rotation.data.position
        sundays = 0
        for day in self.data.days:
            if day.theme == ROTATING:
                day.theme = themes[(pos + sundays) % len(themes)]
                sundays += 1

    @action(from_=PlanState.DRAFT, to=PlanState.DRAFT,
            input=AssignInput, scope=("days", "date"),
            guards=[date_in_plan, meal_is_listed, meal_matches_theme],
            idempotent=True, reversible=False, confirm=False,
            # scoped rendering resolves {item.theme} to the day's theme, so
            # the picker only offers meals that match the night
            field_display={"meal_id": {"params": {
                "state": "on_list", "theme": "{item.theme}"}}},
            display=dict(label="Assign meal", style="primary", order=1))
    async def assign_meal(self, inp: AssignInput, ctx: Ctx) -> None:
        await self._assign(inp, ctx)

    @action(from_=PlanState.DRAFT, to=PlanState.DRAFT,
            input=AssignInput, scope=("days", "date"),
            guards=[date_in_plan, meal_is_listed],
            idempotent=True, reversible=False, confirm=True,
            display=dict(label="Assign off-theme", order=5))
    async def assign_off_theme(self, inp: AssignInput, ctx: Ctx) -> None:
        await self._assign(inp, ctx)

    async def _assign(self, inp: AssignInput, ctx: Ctx) -> None:
        meal = await ctx.read(Meal, inp.meal_id)
        day = _day(self, inp.date)
        assert day is not None and meal is not None  # guards ensured both
        day.meal_id = inp.meal_id
        day.meal_name = meal.data.name
        day.eating_out = False

    @action(from_=PlanState.DRAFT, to=PlanState.DRAFT,
            input=SundayThemeInput, scope=("days", "date"),
            guards=[date_in_plan, sunday_only, theme_in_rotation],
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Pick Sunday theme", order=2))
    async def set_sunday_theme(self, inp: SundayThemeInput, ctx: Ctx) -> None:
        day = _day(self, inp.date)
        assert day is not None
        day.theme = inp.theme

    @action(from_=PlanState.DRAFT, to=PlanState.DRAFT,
            input=DayInput, scope=("days", "date"), guards=[date_in_plan],
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Eating out", order=3))
    async def mark_eating_out(self, inp: DayInput, ctx: Ctx) -> None:
        day = _day(self, inp.date)
        assert day is not None
        day.eating_out = True
        day.meal_id = None
        day.meal_name = None

    @action(from_=PlanState.DRAFT, to=PlanState.DRAFT,
            input=DayInput, scope=("days", "date"), guards=[date_in_plan],
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Clear day", order=4))
    async def clear_day(self, inp: DayInput, ctx: Ctx) -> None:
        day = _day(self, inp.date)
        assert day is not None
        day.meal_id = None
        day.meal_name = None
        day.eating_out = False

    @action(from_=PlanState.DRAFT, to=PlanState.PLANNED,
            guards=[all_days_covered],
            idempotent=True, reversible=True, confirm=False,
            display=dict(label="Finalize plan", style="primary", order=1))
    async def finalize(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=PlanState.PLANNED, to=PlanState.DRAFT,
            idempotent=True, reversible=True, confirm=False,
            display=dict(label="Reopen", order=2))
    async def reopen(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=PlanState.PLANNED, to=PlanState.ACTIVE,
            guards=[plan_started],
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Start the week", style="primary", order=1))
    async def begin(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=PlanState.ACTIVE, to=PlanState.DONE,
            idempotent=True, reversible=False, confirm=False,
            display=dict(label="Week done", style="primary", order=1))
    async def complete(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_={PlanState.DRAFT, PlanState.PLANNED, PlanState.ACTIVE},
            to=PlanState.ABANDONED,
            idempotent=True, reversible=False, confirm=True,
            display=dict(label="Abandon plan", style="danger", order=9))
    async def abandon(self, inp: None, ctx: Ctx) -> None:
        pass
