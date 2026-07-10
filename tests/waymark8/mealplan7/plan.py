"""The MealPlan resource: one Tuesday-to-Tuesday week (or two, to save
grocery trips), each day pre-themed from the weekly calendar.

The lifecycle mirrors how the family actually plans:

- ``draft``: assign on-list meals to days (the guard holds each day to its
  theme; Sunday first needs a theme picked from the rotation), mark
  eating-out days, or override off-theme with an explicit confirm.
- ``planned``: every day is covered; the grocery list and prep tasks hang
  off this state.
- ``active`` → ``done``: the week runs its course.

2.0: the day-shaped actions share one ``PartScope`` declared once;
``date_in_plan`` / ``sunday_only`` / ``theme_in_rotation`` are pure
acceptance-set declarations (the enum and the enforcement are the same
set); references are ``Ref`` fields.
"""
from __future__ import annotations

from datetime import date as date_t
from datetime import datetime, time, timedelta, timezone
from enum import StrEnum
from typing import ClassVar

from pydantic import BaseModel, Field, model_validator
from pydantic.json_schema import SkipJsonSchema

from waymark8 import (
    Acknowledged,
    Allow,
    Ctx,
    Deny,
    Derived,
    Guard,
    OneOf,
    Owns,
    PartScope,
    Predecessor,
    Query,
    Ref,
    Rollup,
    VocabField,
    RefField,
    Relation,
    Resource,
    Safety,
    action,
    filterable,
    guard,
    require,
    rollup_is,
    sortable,
)

from .themes import ROTATING, WEEKDAY_THEMES
from .meal import Meal, MealState


class PlanState(StrEnum):
    DRAFT = "draft"
    PLANNED = "planned"
    ACTIVE = "active"
    DONE = "done"
    ABANDONED = "abandoned"


class DayPlan(BaseModel):
    date: date_t
    # "not yet chosen" is a declared placeholder (design §6), not a bare
    # sentinel: the wire says so (x-vocab.placeholder), and the branches
    # below reference the one declaration in themes.py
    theme: str = VocabField(min_length=1, max_length=50, open=True,
                            placeholder=ROTATING)
    meal_id: Ref["meal"] | None = RefField(default=None, label="meal_name")
    meal_name: str | None = Field(default=None, max_length=200)
    eating_out: bool = False
    eating_out_where: str | None = Field(
        default=None, max_length=120,
        json_schema_extra={"x-display": {"label": "Where"}})

    # a day is covered by a meal OR by eating out, never both (design §5):
    # setting one arm clears the other in the engine — the hand-written
    # clearing that v2 repeated in three handlers is gone, and "is this day
    # covered?" is coverage.filled(day), derived, not re-derived
    coverage: ClassVar[OneOf] = OneOf(
        arms={"meal": ("meal_id", "meal_name"),
              "eating_out": ("eating_out", "eating_out_where")},
        clears=True)


class PlanData(BaseModel):
    start_date: date_t = Field(description="Our week runs Tuesday to Tuesday")
    weeks: int = Field(default=1, ge=1, le=2,
                       description="Plan 2 weeks to save on grocery trips")
    rotation_id: Ref["rotation"] | None = RefField(
        default=None, description="The Sunday-theme rotation to draw from")
    # period chaining (design E7): the engine resolves the latest earlier
    # plan at create — last week is data, not date arithmetic
    previous_plan: Ref["plan"] | None = RefField(
        default=None, predecessor=Predecessor(order="start_date"),
        description="The plan this one follows (engine-resolved)")
    days: list[DayPlan] = Field(default_factory=list)
    # the coverage rollup as a declared fact (design §2): one definition —
    # the finalize gate judges it, the refusal reason is generated from it,
    # and data.all_days_covered renders it; the hand-written guard that
    # recomputed "missing days" at every probe is gone
    all_days_covered: bool = Derived(
        over=("days",),
        fn=lambda days: all(DayPlan.coverage.filled(d) for d in days),
        explain="Every day needs a meal or an eating-out mark before "
                "finalizing; missing: {missing}.",
        vars=lambda days: {"missing": ", ".join(
            d.date.isoformat() for d in days
            if not DayPlan.coverage.filled(d))})
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
    rotation_id: Ref["rotation"] | None = RefField(
        default=None, description="The Sunday-theme rotation to draw from. "
                                  "Leave blank for the active rotation.")
    previous_plan: SkipJsonSchema[str | None] = None
    days: SkipJsonSchema[list[DayPlan]] = Field(default_factory=list)


class DayInput(BaseModel):
    date: date_t


class EatingOutInput(BaseModel):
    date: date_t
    where: str | None = Field(
        default=None, max_length=120,
        description="Where you're eating — a restaurant, grandma's, the "
                    "ward picnic… (optional)")


class AssignInput(BaseModel):
    date: date_t
    # the picker is declared once, here (design §4): {item.theme} resolves
    # against the binding at render — there is no field_display duplicate
    # on the action to silently disagree with
    meal_id: Ref["meal"] = RefField(
        min_length=1, pick=Query(state="on_list", themes="{item.theme}"))


class SundayThemeInput(BaseModel):
    date: date_t
    theme: str = Field(min_length=1, max_length=50)


def _day(r, d: date_t) -> DayPlan | None:
    return next((day for day in r.data.days if day.date == d), None)


# ── Guards ──────────────────────────────────────────────────────────────
# One acceptance set: the rendered enum, the per-part availability, and the
# enforcement. There is no separate body to drift out of sync.
date_in_plan = Guard(
    name="date_in_plan",
    judges=("date",),
    accepts=lambda r: [d.date.isoformat() for d in r.data.days],
    explain="{date} is not a day of this plan.",
)

sunday_only = Guard(
    name="sunday_only",
    judges=("date",),
    accepts=lambda r: [d.date.isoformat() for d in r.data.days
                       if d.date.weekday() == 6],
    explain="Only Sunday rotates; {date} has a fixed weeknight theme.",
)


async def _rotation_themes(r, ctx: Ctx) -> list[str] | None:
    """The linked rotation's themes — resolved server-side at render time so
    the theme field offers real choices. None (no constraint) when no
    rotation is linked: any theme goes then."""
    if r.data.rotation_id is None:
        return None
    rotation = await ctx.read("rotation", r.data.rotation_id)
    return list(rotation.data.themes) if rotation is not None else None


theme_in_rotation = Guard(
    name="theme_in_rotation",
    judges=("theme",),
    reads=("rotation",),
    accepts=_rotation_themes,
    explain="'{theme}' is not in the Sunday rotation. Add it there first.",
    remedies=("rotation.add_theme",),
)


@guard("That meal is not on the family meal list yet. Accept a suggestion "
       "(or ask the AI for one) first.",
       judges=("meal_id",), reads=("meal",), remedies=("meal.accept",))
async def meal_is_listed(r, inp: AssignInput, ctx: Ctx) -> Allow | Deny:
    meal = await ctx.read(Meal, inp.meal_id)
    if meal is not None and meal.state == str(MealState.ON_LIST):
        return Allow()
    return Deny(errors={"meal_id": ["not an on-list meal"]})


async def _admissible_assignments(r, ctx: Ctx) -> set[tuple[str, str]]:
    """Every (meal, date) pair this plan will accept: an on-list meal, on a
    day whose theme it serves (rotating Sundays admit nothing until their
    theme is picked)."""
    meals = await ctx.find(Meal, state=str(MealState.ON_LIST), limit=500)
    return {(m.id, d.date.isoformat())
            for d in r.data.days if d.theme != ROTATING
            for m in meals if d.theme in m.data.themes}


# One relation, two consumers (design §5): each bound day's picker offers
# exactly the meals that serve its night (a rotating Sunday binds nothing),
# and the invoke enforces membership in the same tuple set. This replaces
# v2's meal_matches_theme — a check= with four hand-written prose reasons.
meal_fits_day = Relation(
    name="meal_fits_day",
    judges=("meal_id", "date"),
    reads=("meal",),
    accepts=_admissible_assignments,
    explain="That meal doesn't serve {date}'s theme night. Pick the Sunday "
            "theme first if the day still rotates, or assign off-theme with "
            "confirmation.",
    remedies=("plan.set_sunday_theme", "plan.assign_off_theme"),
)


def _start_of(r) -> datetime:
    return datetime.combine(r.data.start_date, time.min, tzinfo=timezone.utc)


@guard("The plan starts {start}.", vars=("start",), reads=("now",),
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

    # one ownership edge, two consumers (design E4): abandoning the plan
    # cancels its open prep tasks (cascade), and the open-task count rides
    # every envelope and gates `complete` below (rollup)
    owns = (Owns("prep_task", via="plan_id",
                 on={"abandon": "cancel"},
                 rollups={"open_tasks": Rollup(
                     filters={"state": ("pending", "scheduled")})}),)

    # the one place per-day placement is declared (design §3); every
    # day-shaped action places itself on it and the key is pre-bound per part
    days = PartScope("days", key="date")

    async def on_create(self, ctx: Ctx) -> None:
        """A blank rotation_id means the most recently activated active
        rotation; each rotating Sunday is pre-themed from it, walking the
        list from ``position``. With no rotation at all, Sundays stay
        ``rotating`` and the old flow (``set_sunday_theme`` first) still
        applies."""
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
            input=AssignInput, place=days,
            guards=[date_in_plan, meal_fits_day],
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Assign meal", style="primary", order=1))
    async def assign_meal(self, inp: AssignInput, ctx: Ctx) -> None:
        await self._assign(inp, ctx)

    @action(from_=PlanState.DRAFT, to=PlanState.DRAFT,
            input=AssignInput, place=days,
            guards=[date_in_plan, meal_is_listed],
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The day gets a meal that does not "
                                      "match its theme night."),
            # deliberately looser picker than the Ref's default: off-theme
            # assignment exists to escape the theme narrowing
            field_display={"meal_id": {"params": {"state": "on_list"}}},
            display=dict(label="Assign off-theme", order=5))
    async def assign_off_theme(self, inp: AssignInput, ctx: Ctx) -> None:
        await self._assign(inp, ctx)

    async def _assign(self, inp: AssignInput, ctx: Ctx) -> None:
        day = _day(self, inp.date)
        assert day is not None  # date_in_plan ensured it
        day.meal_id = inp.meal_id
        # meal_name is the engine's to maintain (Ref label, design §4);
        # coverage (OneOf) clears the eating-out arm — neither is handler work

    @action(from_=PlanState.DRAFT, to=PlanState.DRAFT,
            input=SundayThemeInput, place=days,
            guards=[date_in_plan, sunday_only, theme_in_rotation],
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Pick Sunday theme", order=2))
    async def set_sunday_theme(self, inp: SundayThemeInput, ctx: Ctx) -> None:
        day = _day(self, inp.date)
        assert day is not None
        day.theme = inp.theme

    @action(from_=PlanState.DRAFT, to=PlanState.DRAFT,
            input=EatingOutInput, place=days, guards=[date_in_plan],
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Eating out", order=3))
    async def mark_eating_out(self, inp: EatingOutInput, ctx: Ctx) -> None:
        day = _day(self, inp.date)
        assert day is not None
        day.eating_out = True
        day.eating_out_where = inp.where
        # coverage (OneOf) clears the meal arm — not this handler

    @action(from_=PlanState.DRAFT, to=PlanState.DRAFT,
            input=DayInput, place=days, guards=[date_in_plan],
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Clear day", order=4))
    async def clear_day(self, inp: DayInput, ctx: Ctx) -> None:
        day = _day(self, inp.date)
        assert day is not None
        day.meal_id = None
        day.meal_name = None
        day.eating_out = False
        day.eating_out_where = None

    @action(from_=PlanState.DRAFT, to=PlanState.PLANNED,
            # the gate judges the stored fact; its reason is generated from
            # the derivation's explain=/vars= (design §5), never written here
            guards=[require("all_days_covered")],
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Finalize plan", style="primary", order=1))
    async def finalize(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=PlanState.PLANNED, to=PlanState.DRAFT,
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Reopen", order=2))
    async def reopen(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=PlanState.PLANNED, to=PlanState.ACTIVE,
            guards=[plan_started],
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Starting the week only reflects the calendar; "
                              "nothing is lost and the plan stays editable "
                              "through its days.")),
            display=dict(label="Start the week", style="primary", order=1))
    async def begin(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_=PlanState.ACTIVE, to=PlanState.DONE,
            guards=[rollup_is(
                "open_tasks", "==", 0,
                explain="{open_tasks} prep task(s) are still open — finish "
                        "or cancel them before closing the week.",
                remedies=("prep_task.complete", "prep_task.cancel"))],
            safety=Safety(idempotent=True, reversible=False, confirm=False,
                          one_way=Acknowledged(
                              "Completing records a finished week; the plan "
                              "remains readable as history.")),
            display=dict(label="Week done", style="primary", order=1))
    async def complete(self, inp: None, ctx: Ctx) -> None:
        pass

    @action(from_={PlanState.DRAFT, PlanState.PLANNED, PlanState.ACTIVE},
            to=PlanState.ABANDONED,
            safety=Safety(idempotent=True, reversible=False, confirm=True,
                          consequence="The plan is discarded for good; its "
                                      "open prep tasks are cancelled; its "
                                      "days and any grocery list stay "
                                      "readable as records."),
            display=dict(label="Abandon plan", style="danger", order=9))
    async def abandon(self, inp: None, ctx: Ctx) -> None:
        pass
