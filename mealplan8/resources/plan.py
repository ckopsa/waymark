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
    Member,
    Surface,
    Allow,
    Count,
    Ctx,
    Deny,
    Derived,
    E,
    Guard,
    On,
    OneOf,
    Owns,
    PartScope,
    Predecessor,
    Query,
    Ref,
    Related,
    Rollup,
    VocabField,
    RefField,
    Relation,
    Resource,
    Safety,
    action,
    filterable,
    guard,
    link,
    require,
    rollup_is,
    sortable,
)

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
    # "not yet chosen" is a declared placeholder (design §6), not a bare
    # sentinel: the wire says so (x-vocab.placeholder), and the branches
    # below reference the one declaration in themes.py
    theme: str = VocabField(min_length=1, max_length=50, open=True,
                            placeholder=ROTATING)
    meal_id: Ref["meal"] | None = RefField(default=None, label="meal_name")
    meal_name: str | None = Field(default=None, max_length=200)
    # capped at 2 named slots rather than an open list (design tradeoff):
    # scalar fields directly on DayPlan sit at the one level of nesting the
    # engine's label maintenance already reaches, so meal_name-for-a-side is
    # genuinely engine-maintained here, the same as the main meal — no
    # framework change, no synthetic list-item identity needed
    side_dish_id: Ref["meal"] | None = RefField(
        default=None, label="side_dish_name")
    side_dish_name: str | None = Field(default=None, max_length=200)
    second_side_dish_id: Ref["meal"] | None = RefField(
        default=None, label="second_side_dish_name")
    second_side_dish_name: str | None = Field(default=None, max_length=200)
    eating_out: bool = False
    eating_out_where: str | None = Field(
        default=None, max_length=120,
        json_schema_extra={"x-display": {"label": "Where"}})

    # a day is covered by a meal OR by eating out, never both (design §5):
    # setting one arm clears the other in the engine — the hand-written
    # clearing that v2 repeated in three handlers is gone, and "is this day
    # covered?" is coverage.filled(day), derived, not re-derived. The side
    # slots ride the "meal" arm (not the primary — fill detection still
    # keys off meal_id) purely so eating-out clears them for free, same as
    # meal_name.
    coverage: ClassVar[OneOf] = OneOf(
        arms={"meal": ("meal_id", "meal_name",
                       "side_dish_id", "side_dish_name",
                       "second_side_dish_id", "second_side_dish_name"),
              "eating_out": ("eating_out", "eating_out_where")},
        clears=True)


# The relation the plan decision actually consults (design 6.0 §1): the
# family calendar is nobody's child — a recital doesn't belong to a meal
# plan, it overlaps one. The predicate is stored boundaries on our side
# against the event's date on theirs; both directions are indexed, which
# is what lets §2's maintainer keep the facts below honest in the same
# commit as any event write.
_calendar = Related("event", on=(
    On(ours="start_date", op="<=", theirs="date"),
    On(ours="end_date",   op=">=", theirs="date"),
))


class PlanData(BaseModel):
    start_date: date_t = Field(description="Our week runs Tuesday to Tuesday")
    weeks: int = Field(default=1, ge=1, le=2,
                       description="Plan 2 weeks to save on grocery trips")
    # the week's far boundary as a stored fact: derived from our own
    # fields, materialized into a promoted column — which is exactly what
    # lets it serve as _calendar's join key (Phase 1 accepts a derived
    # join key; the create path materializes twice so the related facts
    # below never read a boundary that isn't stored yet)
    # 8.0: the boundary arithmetic is an expression — the week's far edge
    # is law a reviewer can diff leaf-by-leaf and a revision can hold,
    # not a lambda hashed by its source text
    end_date: date_t | None = Derived(
        over=("start_date", "weeks"),
        expr=E.f("start_date") + E.days(7 * E.f("weeks") - 1))
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
    # 8.0: covered = the OneOf's arms, stated as law (an expression has
    # no calls, so coverage.filled cannot be referenced — each arm's
    # primary field is named instead: meal_id for the meal arm,
    # eating_out for the other; _filled = "not None and not False").
    # The vars= garnish stays a lambda: prose is advertisement (§5).
    all_days_covered: bool = Derived(
        over=("days",),
        expr=E.all(E.f("days"),
                   E.it.meal_id.is_set() | E.it.eating_out.eq(True)),
        explain="Every day needs a meal or an eating-out mark before "
                "finalizing; missing: {missing}.",
        vars=lambda days: {"missing": ", ".join(
            d.date.isoformat() for d in days
            if not DayPlan.coverage.filled(d))})
    # facts over the relation (design 6.0 §2): the same library Count and
    # the same Derived that serve Owns — the conflict count badges the
    # calendar link, filters the plan list, and feeds the finalize warning;
    # nobody re-joins the calendar in a handler or a client
    calendar_conflicts: int = Count(
        _calendar, where={"kind": ("blocking",), "state": ("fresh", "stale")})
    has_conflicts: bool = Derived(
        over=(_calendar.field("kind", where={"state": ("fresh", "stale")}),),
        expr=E.any(E.f("event.kind"), E.it.eq("blocking")),
        explain="{n} calendar conflict(s) overlap this week.",
        vars=lambda kinds: {"n": sum(k == "blocking" for k in kinds)})
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


class SideDishInput(BaseModel):
    date: date_t
    # same picker as the main meal (design consistency): a side must also be
    # an on-list meal that serves the night's theme
    meal_id: Ref["meal"] = RefField(
        min_length=1, pick=Query(state="on_list", themes="{item.theme}"))


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

day_has_meal = Guard(
    name="day_has_meal",
    judges=("date",),
    accepts=lambda r: [d.date.isoformat() for d in r.data.days
                       if d.meal_id is not None],
    explain="Assign the main meal for {date} before adding a side dish.",
    remedies=("plan.assign_meal",),
)

has_free_side_slot = Guard(
    name="has_free_side_slot",
    judges=("date",),
    accepts=lambda r: [d.date.isoformat() for d in r.data.days
                       if d.side_dish_id is None
                       or d.second_side_dish_id is None],
    explain="{date} already has 2 side dishes — remove one first.",
    remedies=("plan.remove_side_dish",),
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


# the recorded 8.0 §5 residue: a verdict that READS (the input's ref,
# another kind's state) is not pure over (row, input, clock), so it
# stays code — reads=("meal",) names the dependency honestly
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


# The design story's judgment call (design 6.0 appendix §1–§2): a recital
# on taco night is worth a warning, not a wall — the family may well plan
# around it. 8.0: the verdict is a tree over the stored §2 facts — the
# fingerprint reads the judgment instead of hashing a check body, and
# the count garnish is an expression too (no vars_fn lambda).
calendar_clear = guard.expr(
    name="calendar_clear", severity="warning",
    when=~E.data("has_conflicts"),
    explain="{n} calendar conflict(s) overlap this week — move or cancel "
            "them on the calendar itself, or acknowledge to finalize "
            "anyway.",
    vars={"n": E.data("calendar_conflicts")},
)


def _start_of(r) -> datetime:
    return datetime.combine(r.data.start_date, time.min, tzinfo=timezone.utc)


# 8.0: the clock gate as data — now, as a UTC date, against the stored
# start. becomes_available_at stays a callable: structured hope is
# scheduling garnish, not the verdict (design 8.0 §5).
plan_started = guard.expr(
    name="plan_started",
    when=E.date(E.now()) >= E.data("start_date"),
    explain="The plan starts {start}.",
    vars={"start": E.data("start_date")},
    becomes_available_at=_start_of,
)


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

    # start/end promoted as honest date ranges (design 6.0 §3) — the same
    # grammar serves "?start_date_gte=…" and _calendar's join; the related
    # facts filter as promoted columns like any derived field
    filterable = filterable(
        state=filterable.Eq | filterable.In,
        start_date=filterable.Eq | filterable.Range,
        end_date=filterable.Eq | filterable.Range,
        has_conflicts=filterable.Eq,
        calendar_conflicts=filterable.Eq | filterable.Range,
    )
    sortable = sortable("start_date", default="-start_date")

    display = {"title": "Meal plan — week of {data.start_date}"}

    spans = (Meal,)

    # the edge-cited link (design 6.0 §1): the href is COMPILED from
    # _calendar's predicate — target kind, params, and types checked at
    # assembly, not typo'd into a template — and the conflict count rides
    # it as scent, so "Calendar · 2" renders before the traversal
    links = (
        link("calendar", edge=_calendar, embed=True,
             badge="calendar_conflicts",
             summary="What the family already has planned"),
    )

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
        # coverage (OneOf) clears the meal arm, side slots included — not
        # this handler

    @action(from_=PlanState.DRAFT, to=PlanState.DRAFT,
            input=DayInput, place=days, guards=[date_in_plan],
            safety=Safety(idempotent=True, reversible=False, confirm=False),
            display=dict(label="Clear day", order=4))
    async def clear_day(self, inp: DayInput, ctx: Ctx) -> None:
        day = _day(self, inp.date)
        assert day is not None
        day.meal_id = None
        day.meal_name = None
        day.side_dish_id = None
        day.side_dish_name = None
        day.second_side_dish_id = None
        day.second_side_dish_name = None
        day.eating_out = False
        day.eating_out_where = None

    @action(from_=PlanState.DRAFT, to=PlanState.DRAFT,
            input=SideDishInput, place=days,
            guards=[date_in_plan, day_has_meal, has_free_side_slot,
                    meal_fits_day, meal_is_listed],
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Add side dish", order=6))
    async def add_side_dish(self, inp: SideDishInput, ctx: Ctx) -> None:
        day = _day(self, inp.date)
        assert day is not None  # date_in_plan ensured it
        if inp.meal_id in (day.side_dish_id, day.second_side_dish_id):
            return  # already a side of this day — idempotent no-op
        if day.side_dish_id is None:
            day.side_dish_id = inp.meal_id
        else:
            day.second_side_dish_id = inp.meal_id
        # side_dish_name/second_side_dish_name are the engine's to maintain
        # (labeled Refs, design §4) — same machinery as meal_name

    @action(from_=PlanState.DRAFT, to=PlanState.DRAFT,
            input=SideDishInput, place=days, guards=[date_in_plan],
            safety=Safety(idempotent=True, reversible=True, confirm=False),
            display=dict(label="Remove side dish", order=7))
    async def remove_side_dish(self, inp: SideDishInput, ctx: Ctx) -> None:
        day = _day(self, inp.date)
        assert day is not None
        if day.side_dish_id == inp.meal_id:
            day.side_dish_id = None
            day.side_dish_name = None
        elif day.second_side_dish_id == inp.meal_id:
            day.second_side_dish_id = None
            day.second_side_dish_name = None

    @action(from_=PlanState.DRAFT, to=PlanState.PLANNED,
            # the gate judges the stored fact; its reason is generated from
            # the derivation's explain=/vars= (design §5), never written
            # here. calendar_clear WARNS over the §2 related fact: a week
            # with a recital in it finalizes with acknowledgment, not never
            guards=[require("all_days_covered"), calendar_clear],
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


class WeekBoard(Surface):
    """The week's decision surface (design 6.0 §4): the plan anchor with
    the family calendar co-present — the exact composition the finalize
    decision consults, declared once and served as a resource."""

    name = "week-board"
    anchor = "plan"
    title = "Week board — {anchor.data.start_date}"
    members = (Member("calendar", table=("date", "title", "kind")),)
    showcase = ("finalize",)
    attention = {"has_conflicts": True}
