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
| rows_per_firing | 1 | one row is a whole week of days, a rotation read and one finalize. One week is one sitting's decision; a second draft waits one wake |
| cadence_seconds | 86400 | one day. The week is decided in the family chat, and the conversation moves at the pace of replies. The daily cadence is the floor that reads them. The wake below fires the seat sooner when a week begins |
| fire_interval_seconds | 300 | the damper. A count wake is a level and not an edge, so each transition of a `plan` is evaluated again. Five minutes holds a burst of them to one firing, and a second mention of the bot inside that gap waits at most five minutes |
| wake_on | the two entries under "The wake" | the seat wakes when no planned week is waiting (waymark-fp62.13), and when the family speaks to the house in the chat (waymark-fp62.18.2) |
| held_for | Opus 5 for the discovery run, then the model the rules fit | the seat's place on the ladder. See "The discovery run" below |
| standing_ttl_seconds | 604800 | the ceiling the engine enforces, and one cadence of this seat |
| sitting_idle_seconds | 3600 | a sitting that says nothing for an hour is abandoned by the sweep |
| substitute_for | `[]` | this seat stands in for no other |
| substitute_drop | `[]` | a substitute of this seat drops nothing from its scope |
| sitting_budget_tokens | 200000 | two weeks of days, with the rotation and the meals beside them |
| budget_usd_per_week | 30 for the discovery run, 10 after | the fuel. A daily sitting reads a chat and a plan, a mention adds one, and Opus reads at five times Sonnet's price |
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

The two `telegram_bot` entries are powers, not kinds
(spec-mcp-servers). `telegram_bot.read` lets the seat read the family
chat as the house's own bot. `telegram_bot.send` lets it write to that
chat as the bot. A power takes no action name. The gate lists no
filter for either, so the entries carry none.

The seat speaks as the BOT and not as a person. The house has a voice
of its own in the chat, the family can name it, and a mention of it is
the wake below. The powers of the person's own Telegram account,
`telegram.read` and `telegram.send`, are not in this scope: two voices
in one chat make one conversation that answers itself.

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
  {"kind": "telegram_bot.read", "actions": []},
  {"kind": "telegram_bot.send", "actions": []}
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

The count wake makes the week. It does not hear the family. Add this
second entry to wake the seat when the family speaks to the house:

```json
{"kind": "thread", "actions": ["observe_mention"],
 "filter": {"external_id": "tgram:-1004383242252"}}
```

The house mirrors each Telegram chat as a `thread` row. Each message
in the chat moves that row, and `observe_external` is the door for
that. A message that names the house's bot, replies to it, or gives it
a command moves the row's `last_mention_at`, and `observe_mention` is
the door for THAT. The entry above names the second door only. The
family talks in the chat all day. The seat wakes when the family talks
to the house.

The entry does not settle. A mention of the house wants an answer at
once, so the first mention wakes the seat and the seat reads the chat
as it stands. A second mention while the seat sits is remembered and
released when the sitting closes. To wait for a conversation to end
instead, add `settle_seconds` to the entry (waymark-fp62.17): each
mention then moves the wake forward by that many seconds, and the
seat wakes when the chat has been quiet for that long.

The door opens when the house looks at the chat. The house looks every
twenty seconds. The `thread` kind declares `:advance-every 20`, and the
engine asks the bot rig on that beat which chats the house was named
in (waymark-fp62.18.3). A mention moves the row within that beat, and
the wake fires the seat at once. A mention wakes the seat within about
twenty seconds, plus the run's own start.

Do not add the wake without the filter. Every Telegram chat the bot
hears would wake the seat.

The id is the chat's id as Telegram gives it today. When the bot was
made an admin on 2026-09-21, Telegram turned the group `-5091757250`
into the supergroup `-1004383242252`, and both rigs name the new id.
The old thread row stays as the record of the messages before that
moment. When Telegram migrates the chat again, restate this filter.

The wake needs the bot rig. The `tgrambot` server answers
`last_mention_at`; the person's own account rig does not. Until that
rig is live, the entry is lawful and it never fires.

## The charter

At most 1200 characters. The charter is judgment, not procedure.

```
Decide the coming week's meals with the family, in the family chat on
Telegram, the way a person running the kitchen would. Keep a draft
plan for the coming week, and get it to planned once the family has
agreed to it.

Use the doors you hold as you see fit: assign meals, mark nights out,
change a day when somebody asks, pick meals that fit what they said
they want, and write a recipe onto a meal that has none. A dish to
bring somewhere is that day's meal.

Answer every message that names you, even when nothing changed: say
where the week stands and what you are waiting on. Name the week by
its first day in every message, and read your own earlier messages
before you write, so you do not repeat yourself.

Finalize only when everyone in the chat has said yes to the week as
it stands. Silence is not a yes. Never acknowledge a warning; a person
does that. When finalize is refused, leave the plan in draft and say
why in the close.

When the sit hands you no plan, create one for the coming Tuesday.

This seat is learning how the family wants to use it. In the close of
each sitting, say what you wanted to do and could not, and which rule
you bent. Those notes become the charter.
```

The charter names the chat, the walls and the one rule: silence is
not a yes. It does not name the message shapes or the stages. The
seat decides those, and its close notes say what it decided.

The walls are the sentences that do not move whatever the seat
learns: finalize needs everyone's yes, silence is not a yes, a
warning is a person's to acknowledge, every message names the week.

This is the loose charter of the discovery run, stated on
2026-09-21. The tight one it replaced is under "The discovery run"
below, with why it went.

## Step 3: the instructions

Invoke `restate` on the seat `meal-planner` with the field
`instructions`. The field holds at most 2000 characters. A restate
carries the whole row: every field of Step 2 is sent again, and
`instructions` is added to them. Write this text in it:

```
You sit in the seat `meal-planner`. The sit answers the charter and
your rows, each with its doors and the input each door takes. Each
row is a draft plan.

The family chat is the Telegram chat titled `Meal plans`. You speak in
it as the house's own bot. Find it with tgrambot__list_chats, read it
with tgrambot__get_messages (power telegram_bot.read), and send with
tgrambot__send_message (power telegram_bot.send). Rows with from_bot
true are your own earlier messages; read them before you write. When
no row is yours, the rig does not record sends yet, and the plan's
days are your record of what you did.

For each row, read the plan with waymark_get; its days come with it.
Read the chat. Decide what the week needs now and do it through the
doors on plan_day (assign_meal, mark_eating_out, set_sunday_theme,
assign_off_theme) and meal (create, accept, update_recipe,
update_details, update_themes). A meal the family named that is not
on the list: create it and accept it. Then send one message: what
changed, the week one line per day when it changed, and the question
you are waiting on. Reply to the message that named you when there is
one.

Finalize with the plan's finalize door only when everyone in the chat
has said yes to the week as it stands. Leave the plan in draft when
finalize is refused, and say why in the close.

When the walk carries no row, invoke create on the kind plan with no
start date. Then stop.

Do not call discover or schema; a refusal names its own remedy. When
the seat says halted or parked, say why and stop. If a
routine-fire-payload block names a row id, walk that row and stop.
When the Stop hook asks you to close the sitting, make that one call
with the numbers it gives. In the note, say the stage of the week,
what you wanted to do and could not, and which rule you bent.
```

The chat is named by its title, `Meal plans`. To point the seat at
another chat, restate the instructions with the other title. Nothing
else changes.

Leave the seat's schedule with no link. A schedule with no link of
its own fires through the chair's link (R-12.36). Nothing else goes
in the instructions (R-12.10).

## How a week is decided

A sitting cannot wait for a reply, so the week is decided across
firings, and the chat is the seat's memory. Each firing reads the
plan, reads the chat, its own earlier messages among them (`from_bot`
true, waymark-fp62.18.4), and decides what the week needs now. The
shape it usually takes:

1. The Routine's session reads the `Key:` line of the fire text and
   sits with that key and the seat the `Seat:` line names. The sit
   answers the charter and one `plan` row: the oldest draft, under the
   scope entry's filter.
2. No request in the chat yet: the session names the week and asks
   for requests and nights out.
3. Requests in, or a day passed: the session covers the days through
   the doors on plan_day and meal, sends the week one line per day,
   and asks for a yes.
4. Everyone said yes: the session invokes `finalize`. A change asked
   instead changes those days and sends the week again.
5. Any message that names the bot gets an answer, even when nothing
   changed: where the week stands and what the seat waits on.
6. The session's close note says the stage, what it wanted to do and
   could not, and which rule it bent. The Stop hook closes the
   sitting.

Silence is not a yes. A week nobody answered stays in draft, and the
cadence asks again the next day by reading the same chat.

## The discovery run

On 2026-09-21 the seat moved from Sonnet 5 under a tight charter to
Opus 5 under the loose one above, after nine sittings were read. The
tight charter fixed three stages and told the seat to stop after
each. What went wrong was not the stages:

- The seat could not see its own messages, because the bot rig kept
  only what it received. Every mention read as stage 1 again, and
  the week went to the chat three times in one afternoon. Fixed on
  the rig by waymark-fp62.18.4.
- The family's requests did not fit the charter's words. A dish to
  bring to a potluck is neither a meal nor a night out; a creamy
  soup is a kind of meal, not one on the list. The seat judged "no
  change" and said nothing.
- The family expected recipes on the days. The seat held
  `update_recipe` and the charter never told it to write one.
- The charter ended every stage with "stop", so a mention that
  changed nothing got no reply.

The loose charter says the intent and the walls and leaves the
procedure to the seat. Each close note says what the seat wanted to
do and could not, and which rule it bent. Those notes are read after
a week of sittings, and the rules they name go into the charter as
sentences, or into a door, a filter or a reason string where one
fits (spec-seat.md R-12.10). Then `held_for` steps back down to
Sonnet 5 with the note the restate demands, the budget goes back to
10, and `rows_per_firing` to 2.

The tight charter it replaced, for the record:

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

A request changes a covered day too. When somebody asks for a meal, a
night out, or something easy on a day, change that day, then send the
week again.

Finalize only when everyone in the chat has said yes to that exact
week. When somebody asks for a change, change those days, send the
week again, and stop. Silence is not a yes.

When the sit hands you no plan, create one for the coming Tuesday and
stop. The next firing walks it.

When a gate refuses finalize, leave the plan in draft. Say why in the
close of the sitting. Never acknowledge a warning. A person does that.
```

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
3. The seat exists and is active, with the scope above, the two wake
   entries above, and `held_for` naming the model the Routine runs.
   A Telegram chat titled `Meal plans` exists, with everyone who
   decides the week in it.
   The house's bot is a member of that chat, and it can read it: a
   bot with privacy mode on hears only the messages that name it, so
   make the bot an admin of the group or turn privacy mode off.
   An `mcp_server` row named `tgrambot` points at the bot rig, and its
   powers name `telegram_bot.read` and `telegram_bot.send`. The
   powers of a server row are the vocabulary a scope entry may name,
   so the scope above is refused until that row exists.
   A restate of the seat carries the new scope, the new wake and the
   new instructions. Do it by hand, after the bot rig is live.
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
