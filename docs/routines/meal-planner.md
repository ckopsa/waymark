# Routine: meal-planner

A house must have a meal plan for the coming week. The `plan` kind of
mealplan10 runs draft, planned, active, done. A week is ready when its
plan reaches `planned` before the week starts. Every day must be
covered, the calendar must be clear, and the recipes must be attached.
Those three are the gates on the `finalize` door. This seat is what
makes that happen.

This seat is a DOING seat. It walks draft plans, it covers the days,
and it finalizes the week. It says no verdict. A judge over draft
plans is a different seat and a later bead.

This file holds what is different from the CI classifier. The chair
key, the fire link, the seat's place, the environment and the Stop
hook are the same. docs/routines/ci-classifier.md says them one time.

Written in ASD-STE100 Simplified Technical English.

## Step 1: the chair

Do the four steps of "One Routine for each model" in
docs/routines/ci-classifier.md. Do them one time for a model. Do not
repeat them for this seat.

The four steps are the chair key, `offer_key` on the MODEL row, the
one Routine with its prompt, and `link` with the fire URL and the
token. The chair of this seat is the first model in its `held_for`.
A seat with no schedule link of its own fires through the chair's
link (R-12.36).

## Step 2: the seat

Create the seat with these values. A restate changes the next firing.
It does not change the Routine.

| field | value | why |
|---|---|---|
| name | `meal-planner` | one spelling |
| mode | `fired` | the Routine and the wake open the sittings. No person sits here |
| walk | `plan` | the queue is the draft plans. The `plan` kind declares no default filter, so the scope entry's own filter is what makes the queue (waymark-fp62.12) |
| rows_per_firing | 2 | one row is a whole week of days, a rotation read and one finalize. Two weeks is a full sitting, and a third draft waits one wake |
| cadence_seconds | 604800 | the floor. The wake is what fires this seat in a live house. The cadence is the backstop for a house where nobody walks the door that empties the queue |
| fire_interval_seconds | 3600 | the damper. A count wake is a level and not an edge, so each transition of a `plan` is evaluated again. One hour holds a burst of them to one firing |
| wake_on | one count entry, under "The wake" | the seat wakes when no planned week is waiting (waymark-fp62.13) |
| held_for | the model the Routine runs | the seat's place on the ladder. The sit frames the week, and the doors of one day are the whole answer |
| standing_ttl_seconds | 604800 | the ceiling the engine enforces, and one cadence of this seat |
| sitting_budget_tokens | 200000 | two weeks of days, with the rotation and the meals beside them |
| budget_usd_per_week | 5 | the fuel |
| charter | the text under "The charter" | the residual |
| scope | the entries under "The scope" | the authority |

The seat holds no judgment. The queue is the scope entry's filter and
nothing else.

## The scope

The `plan` entry is the queue. Its filter is `state=draft`, so the
seat sees a draft week and no other week. Its two actions are
`create` and `finalize`. `finalize` is the door out of `draft`, which
is what the guard `walk-leaves-its-filter` asks for
(waymark-fp62.12). `create` is how the seat makes the next week when
the queue is empty.

The `plan_day` entry carries the four doors that cover a day. The
`rotation` entry and the `meal` entry are read-only. They let the
seat read the Sunday themes and the meals on the list.

```json
[
  {"kind": "plan",
   "actions": ["create", "finalize"],
   "filter": {"state": "draft"}},
  {"kind": "plan_day",
   "actions": ["assign_meal", "assign_off_theme",
               "set_sunday_theme", "mark_eating_out"]},
  {"kind": "rotation", "actions": []},
  {"kind": "meal", "actions": []}
]
```

The scope names `plan_day` directly. A day is an owned child of the
plan, but an entry for the owner does not open its children. One
entry opens one kind.

Each other door of a `plan` is a person's, and the seat does not get
it. `reopen` moves a planned week back to draft. `begin` starts the
week. `complete` and `resume` end it and open it again. `abandon`
discards it. `clear_day` on a `plan_day` is also a person's. The seat
covers a day and it does not uncover one.

## The wake

```json
[{"kind": "plan", "actions": [], "filter": {"state": "planned"},
  "at_most": 0}]
```

This is a count wake, and it counts on absence (waymark-fp62.13). The
engine counts the `plan` rows in `planned` when an action of a `plan`
commits. It fires the seat when that count is at or below zero.

The wake is what it is for three reasons.

The seat's own work makes a plan leave draft. A wake on `create` of a
`plan`, which is the default for a walk seat, would fire the seat on
the plan the seat itself just made. The count wake does not.

When the planned week begins, the count falls to zero. The engine
fires the seat, and the seat makes the next week's plan. That is the
one moment the house needs a new week.

When a person reopens a planned week, the count falls to zero again.
The engine fires the seat, and the seat finishes the week the person
opened.

The count is the engine's own. It is not read under the seat's leash.
The scope filter hides the planned plans from the seat. It does not
hide them from the count.

The entry names no action, so every action of a `plan` is counted on.
`fire_interval_seconds` is the damper on that.

## The charter

At most 1200 characters. The charter is judgment, not procedure.

```
Make sure the coming week has a plan in planned.

When the sit hands you a draft plan, cover each undecided day. Give
the day a meal on that day's theme, from the rotation. Mark the day
eating out when the calendar says the family is out that night.

Finalize when every day is covered.

When the sit hands you no plan, create one for the coming Tuesday and
stop. The next firing walks it.

When a gate refuses finalize, leave the plan in draft. Say why in the
close of the sitting.

Never acknowledge a warning. A person does that.
```

## Step 3: the instructions

Invoke `restate` on the seat `meal-planner` with the field
`instructions`. The field holds at most 2000 characters. Write this
text in it:

```
You sit in the seat `meal-planner`. The sit answers the charter and
your rows, each with its doors and the input each door takes. Each
row is a draft plan.

For each row, read the plan with waymark_get. Its days come with it.
For each day that is undecided, invoke one door on the kind plan_day:
assign_meal with a meal that fits the day's theme, or mark_eating_out
when the family is out that night. Invoke set_sunday_theme first when
a Sunday carries no theme yet. Invoke assign_off_theme only when no
listed meal fits the theme, and echo its sentence back.

Then invoke finalize on the plan. Leave the plan in draft when
finalize is refused, and say why in the close.

When the walk carries no row, invoke create on the kind plan with no
start date. Then stop.

Do not call discover, schema or powers; a refusal names its own
remedy. When the seat says halted or parked, say why and stop.

If a routine-fire-payload block names a row id, walk that row and
stop.

When the Stop hook asks you to close the sitting, make that one call
with the numbers it gives, then stop.
```

Leave the seat's schedule with no link. A schedule with no link of
its own fires through the chair's link (R-12.36). Nothing else goes
in the instructions (R-12.10).

## What one firing does

1. The Routine's session sits with the chair key. The engine binds
   the session to this seat.
2. The sit answers the charter and at most two `plan` rows
   (R-12.28). The rows are the draft plans, under the scope entry's
   filter. A plan in another state is absent, and it is not refused.
3. The session reads one plan with `waymark_get`. The plan's days
   come with it, under the `days` link.
4. For each undecided day, the session invokes one door on the day.
   A meal that does not fit the theme is refused, and the refusal
   names the theme.
5. The session invokes `finalize` on the plan. The three gates judge
   the stored facts. A refused finalize leaves the plan in draft.
6. A finalized plan moves to `planned`. It leaves the scope entry's
   filter in the same commit, so it is not in the next walk.
7. The session says in one line how many weeks it finalized and how
   many it left in draft. The Stop hook closes the sitting.

## When the queue is empty

An empty walk is the seat's other job. The house has no draft week,
so the seat creates one. The create takes no start date, and the
engine gives the plan the coming Tuesday. The birth door makes the
seven days with it, each pre-themed from the active rotation.

The seat then stops. It does not cover the days in the same firing.
The new plan is a draft, so the next firing walks it.

## The warnings are a person's

Two of the three gates on `finalize` are warnings. `calendar-clear`
warns when an event overlaps the week. `recipes-attached` warns when
a planned day carries a meal with no recipe. A warning finalizes with
an acknowledgment.

The seat never acknowledges. An acknowledgment is a person saying
that a recital on taco night is acceptable, and the seat cannot know
that. The seat leaves the plan in draft and says the count in the
close.

`all-days-covered` is the third gate and it is a refusal. It is the
one the seat's own work clears.

## Before the first firing

1. A rotation stands in `active`, and it carries themes. Without one,
   each Sunday stays `rotating`, and the seat must pick a theme by
   hand through `set_sunday_theme`.
2. Meals stand in `on_list`, with themes on them. A week cannot be
   covered from an empty list.
3. The seat exists and is active, with the scope above, the wake
   above, and `held_for` naming the model the Routine runs.
4. `offer_key` and `link` have been invoked on the model row
   (ci-classifier.md, "One Routine for each model"), and the seat
   carries its `instructions`.
5. The environment carries the URL and the key, or it carries nothing
   and the hook holds the stop (inbox-clerk.md, "The environment").
6. A person watches the first firing. The person reads the sitting
   row, the week it finalized, and the days it covered.

## To pause

Park the seat. The next firing meets the wall, says so, and stops.
The week then waits for a person. To stop the seat for good, retire
it. The plans it made stay on the record.
