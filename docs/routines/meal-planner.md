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

Do the two steps of "One Routine for each model" in
docs/routines/ci-classifier.md. Do them one time for a model. Do not
repeat them for this seat.

The two steps are the one Routine with its prompt, and `link` with the
fire URL and the token. The prompt holds no key: the engine mints a
key for each firing, and the fire text carries it (R-12.37). The chair
of this seat is the first model in its `held_for`. A seat with no
schedule link of its own fires through the chair's link (R-12.36).

## Step 2: the seat

Create the seat with these values. A restate changes the next firing.
It does not change the Routine.

| field | value | why |
|---|---|---|
| name | `meal-planner` | one spelling |
| mode | `fired` | the Routine and the wake open the sittings. No person sits here |
| walk | `plan` | the queue is the draft plans. The `plan` kind declares no default filter, so the scope entry's own filter is what makes the queue (waymark-fp62.12) |
| rows_per_firing | 2 | one row is a whole week of days, a rotation read and one finalize. Two weeks is a full sitting, and a third draft waits one wake |
| cadence_seconds | 86400 | one day. The week is decided in the family chat, and the conversation moves at the pace of replies. The daily cadence is the floor that reads them. The wake below fires the seat sooner when a week begins |
| fire_interval_seconds | 3600 | the damper. A count wake is a level and not an edge, so each transition of a `plan` is evaluated again. One hour holds a burst of them to one firing |
| wake_on | one count entry, under "The wake" | the seat wakes when no planned week is waiting (waymark-fp62.13) |
| held_for | the model the Routine runs | the seat's place on the ladder. The sit frames the week, and the doors of one day are the whole answer |
| standing_ttl_seconds | 604800 | the ceiling the engine enforces, and one cadence of this seat |
| sitting_idle_seconds | 3600 | a sitting that says nothing for an hour is abandoned by the sweep |
| substitute_for | `[]` | this seat stands in for no other |
| substitute_drop | `[]` | a substitute of this seat drops nothing from its scope |
| sitting_budget_tokens | 200000 | two weeks of days, with the rotation and the meals beside them |
| budget_usd_per_week | 10 | the fuel. A daily sitting reads a chat and a plan, and a week has up to seven of them |
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

The two `telegram` entries are powers, not kinds (spec-mcp-servers).
`telegram.read` lets the seat read the family chat. `telegram.send`
lets it write to that chat. A power takes no action name. The gate
lists no filter for either, so the entries carry none.

```json
[
  {"kind": "plan",
   "actions": ["create", "finalize"],
   "filter": {"state": "draft"}},
  {"kind": "plan_day",
   "actions": ["assign_meal", "assign_off_theme",
               "set_sunday_theme", "mark_eating_out"]},
  {"kind": "rotation", "actions": []},
  {"kind": "meal", "actions": []},
  {"kind": "telegram.read", "actions": []},
  {"kind": "telegram.send", "actions": []}
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

A reply in the family chat does not wake the seat today. The house
mirrors each Telegram chat as a `thread` row, and each reply moves
that row. Add this second entry to wake the seat on the chat:

```json
{"kind": "thread", "actions": ["observe_external"],
 "filter": {"external_id": "tgram:-5091757250"},
 "settle_seconds": 900}
```

The entry settles, and 900 seconds is a quarter of an hour. The seat
must read a conversation and not its first word. A wake on the first
reply gives the seat a chat that is half answered, and the family is
still deciding. Each reply moves the wake forward, and the seat wakes
when the chat has been quiet for fifteen minutes.

Do not add the wake without the filter. Every Telegram chat in the
house would wake the seat, one sitting an hour.

## The charter

At most 1200 characters. The charter is judgment, not procedure.

```
Make sure the coming week has a plan in planned, and that the family
agreed to it first.

The family chat on Telegram is where a week is decided. When a draft
plan has no request from you in the chat yet, ask once: name the week,
ask for meal requests and nights out, and stop.

When everyone in the chat has answered, or a day has passed since you
asked, cover each undecided day: a requested meal on its day, a night
out where they said so, and for the rest a meal on the day's theme
from the rotation. Then send the week to the chat as one message, one
line per day, and ask for a yes. Stop.

Finalize only when everyone in the chat has said yes to that exact
week. When somebody asks for a change, change those days, send the
week again, and stop. Silence is not a yes.

When the sit hands you no plan, create one for the coming Tuesday and
stop. The next firing walks it.

When a gate refuses finalize, leave the plan in draft. Say why in the
close of the sitting. Never acknowledge a warning. A person does that.
```

The charter names the chat, the stages and the one rule: silence is
not a yes. It does not name the message shapes. The instructions do.

## Step 3: the instructions

Invoke `restate` on the seat `meal-planner` with the field
`instructions`. The field holds at most 2000 characters. A restate
carries the whole row: every field of Step 2 is sent again, and
`instructions` is added to them. Write this text in it:

```
You sit in the seat `meal-planner`. The sit answers the charter and
your rows, each with its doors and the input each door takes. Each
row is a draft plan.

The family chat is the Telegram chat titled `Meal plans`. Read it
with the power telegram.read; waymark_powers lists the tool. Send to
it with the power telegram.send. Every message you send names the
week by its first day, so a later firing can find it.

For each row, read the plan with waymark_get. Its days come with it.
Then read the chat, and find which stage the week is at.

Stage 1, no request from you for this week in the chat: send one
message. Name the week. Ask for meal requests and nights out. Stop.

Stage 2, a request sent and either everyone has answered or a day has
passed: for each undecided day invoke one door on the kind plan_day.
Use assign_meal with the requested meal, or with a meal that fits the
day's theme. Use mark_eating_out for a night out. Invoke
set_sunday_theme first when a Sunday carries no theme. Use
assign_off_theme only when no listed meal fits, and echo its sentence
back. Then send the week as one message, one line per day, and ask for
a yes from everyone. Stop.

Stage 3, the week sent and everyone said yes to it: invoke finalize on
the plan. When somebody asked for a change instead, change those days
and send the week again. Stop.

Leave the plan in draft when finalize is refused, and say why in the
close.

When the walk carries no row, invoke create on the kind plan with no
start date. Then stop.

Do not call discover or schema; a refusal names its own remedy. When
the seat says halted or parked, say why and stop. If a
routine-fire-payload block names a row id, walk that row and stop.
When the Stop hook asks you to close the sitting, make that one call
with the numbers it gives, then stop.
```

The chat is named by its title, `Meal plans`. To point the seat at
another chat, restate the instructions with the other title. Nothing
else changes.

Leave the seat's schedule with no link. A schedule with no link of
its own fires through the chair's link (R-12.36). Nothing else goes
in the instructions (R-12.10).

## The three stages

A sitting cannot wait for a reply, so the week is decided across
firings. The chat is the seat's memory. Each firing reads the plan,
reads the chat, and finds the stage from the messages the seat sent
before. Each message names the week by its first day.

1. The Routine's session reads the `Key:` line of the fire text and
   sits with that key and the seat the `Seat:` line names. The engine
   binds the session to this seat. The sit answers the charter and at most
   two `plan` rows (R-12.28): the draft plans, under the scope
   entry's filter.
2. Stage 1. No request for this week is in the chat. The session
   sends one message: the week, and a question about meal requests
   and nights out. It stops.
3. Stage 2. The request is in the chat, and everyone answered or a
   day has passed. The session covers each undecided day through the
   doors on the day: a requested meal on its day, a night out where
   they said so, a meal on the day's theme for the rest. It sends the
   week as one message, one line for each day, and asks for a yes.
   It stops.
4. Stage 3. The week is in the chat and everyone said yes to it. The
   session invokes `finalize`. The three gates judge the stored facts.
   A finalized plan moves to `planned` and leaves the walk in the
   same commit. When somebody asked for a change instead, the session
   changes those days, sends the week again, and stops.
5. The session says in one line which stage each week is at. The
   Stop hook closes the sitting.

Silence is not a yes. A week nobody answered stays in draft, and the
cadence asks again the next day by reading the same chat.

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
   A Telegram chat titled `Meal plans` exists, with everyone who
   decides the week in it, and the house's Telegram connection can
   read it and write to it.
4. `link` has been invoked on the model row (ci-classifier.md, "One
   Routine for each model"), and the seat carries its `instructions`.
   The Routine's prompt holds no key.
5. The environment carries the URL and the key, or it carries nothing
   and the hook holds the stop (inbox-clerk.md, "The environment").
6. A person watches the first firing. The person reads the sitting
   row, the week it finalized, and the days it covered.

## To pause

Park the seat. The next firing meets the wall, says so, and stops.
The week then waits for a person. To stop the seat for good, retire
it. The plans it made stay on the record.
