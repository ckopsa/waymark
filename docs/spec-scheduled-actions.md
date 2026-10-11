# Spec — scheduled actions: a call stored for a time, judged again before it runs

**Thesis.** The engine can already store a call and run it later
(`held_call`), rehearse a call without writing (`dry_run`), fence a call on a
version (`if_version`), land a call once (the idempotency key) and wake on its
own clock (`wakes/tick!`, the `sweep!`s). It cannot yet say *"do this at 08:30
tomorrow"*. A scheduled action is those five pieces in one row, with one new
idea: **the call is judged twice**, at scheduling and again at its time, and
the scheduler chooses how much the world may move in between.

Design pass for epic 1cc8f166 (ticket c7559f86). No code lands with this
document.

## Epistemic status

Every part exists somewhere in the engine; none of them was built for this.
The risk is not the clock. The risk is authority: a call that runs when nobody
is looking is the easiest place in the engine to run something under a grant
that is gone, as a principal who has left, past a wall that held at the wire.
So this spec is mostly about what is read again at `run_at`, and it answers
*everything*: the row, the grant, the guards, the consequence sentence.
Nothing but the person's tap is carried forward from scheduling.

The owner asked on 2026-10-01 for all three validity rules. They are one
mechanism with three strengths, not three features, and the first child
builds two of them.

What was read for this pass, and what was not, is in *To confirm* at the end.

## What exists

- `server/held_calls.clj` — a call stored on a row and run later. A power
  hold forwards *"EXACTLY what would have gone out at call time, and no grant
  is re-read at a moment the call was not judged in"*. A door hold
  (`hold-door!`) keeps the body in `forward` and the target in `door`
  (`kind`, `action`, `id`, `author`, `if_match`, `prefill_digests`), and
  `forward-door!` replays it once, as the author, under `:within` naming the
  held row, keyed `held_call:<id>`. Its rule is **NOTHING RUNS LATE** (R-7):
  `expires_at` at birth, `sweep-expired!` on a five-minute loop.
- `server/invoke.clj` — the fifteen steps. The ones this spec leans on:
  step 2 (idempotency key, stored replay), step 3 (one row lock per
  invocation), step 5 (wrong state), step 6 (the fence: If-Match against the
  etag), step 9 (the guard loop, with acknowledged warnings), step 10 (the
  dry-run exit), and `create-dry-run!` for a create's rehearsal.
- `server/mcp.clj` — the confirm gate. `consequence-of` reads the sentence
  off the row's own rendered entry, and *"two readings of one sentence is a
  gate that can be walked around"*.
- `server/wakes.clj` — `tick!` every thirty seconds (`default-tick-ms`):
  `sweep-cadence!` then `sweep-pending!`. `start-tick!` says *"ONE process
  per database should run it, and that is not decided here: the module's
  hook carries `:elected`"*. `definitions/sweep-clock!` is the seat module's
  other clock (sittings, transcripts, `wakes/sweep-missed!`).
- `server/jobs.clj` — the `job` kind: a bulk call past its threshold, run as
  soon as a worker claims it. Its orphan sweeper is elected the same way
  (`:elected :jobs-orphan-sweeper`). Its recorded deviation is the warning
  this spec heeds: items run under a principal *reconstructed* from
  `requested_by`, *"held roles are not carried"*.
- `factory10` ticket `defer` — `defer-the-ticket` writes `defer_until` on the
  row and `resume-the-ticket` clears it. `ticket.clj` holds no sweep that
  reads the date: a deferred ticket comes back when somebody resumes it.
- `server/collections.clj` — the filter grammar (`grammar`, `cond-of`): per
  kind, only the fields it declares `:filterable`, each with the operators it
  declares.
- `server/held_calls.clj`, lower half — `notifier` and `notice_rule`: a
  transition matched on `action`, `from_state`, `to_state` reaches a person,
  with quiet hours and a digest.
- `docs/spec-time-travel.md` — *"the log records what happened, not what the
  row looked like."* A snapshot that is needed later must be stored when it
  is taken.

## R-1. The row is a new kind, `scheduled_action`

Not `run_at` on `held_call`. Three reasons, in order of weight:

1. **The contracts are opposite.** A held call forwards exactly what was
   written and re-reads no grant; a scheduled action re-reads everything.
   One kind cannot promise both, and a flag that switches the promise is a
   wall with a door in it.
2. **`held_call` says nothing runs late.** Its `expires_at` means *"after
   this, never"*. `run_at` means *"not before this"*. Two instants with
   opposite senses on one row is how a call runs at its expiry.
3. **The states answer different questions.** `held → allowed → done` is a
   person's decision. `scheduled → running → done` is a clock's. A call that
   needs both (R-4) uses both rows: the scheduled action *has* a held call,
   it is not one.

Nor a `job`: a job has no time, no second judgment and a reconstructed
principal. It lends its shape (an ordinary engine-served resource, a cancel
door, an elected sweeper) and nothing else.

### Fields

| field | who writes it | what it is |
|---|---|---|
| `target` | scheduler | `{kind, action, id}` for a row's door; `{kind, action}` with no `id` for a create; `{tool}` for a power (R-4.4) |
| `input` | scheduler | the action's input, or the tool's arguments, wire-shaped, stored once and capped as `held_calls/capped` caps |
| `run_at` | scheduler | an instant, stored in UTC, whole minutes |
| `zone` | scheduler, or the engine from the member | the IANA zone `run_at` was entered in and is shown in |
| `scheduler` | engine | the principal that scheduled it; never an input |
| `acts_as` | engine | who the run runs as (R-4); never an input |
| `grant` | engine | the id of the grant the scheduler wore, when it wore one. An id, not a copy |
| `validity` | scheduler | `strict`, `state` (default) or `conditions` |
| `conditions` | scheduler | R-2.3; only with `validity: conditions` |
| `snapshot` | engine | `{version, etag, state, law_revision}` of the target at scheduling |
| `expect_state` | scheduler | R-3.2; the state the row is expected to be in at `run_at`, when it is not in it now |
| `expect_refusals` | scheduler | R-3.2; guard names the scheduling check may meet and pass over |
| `acknowledge` | scheduler | the consequence sentence of a confirm door, as the row stated it at scheduling |
| `acknowledge_warnings` | scheduler | guard names accepted in advance, as on an invoke |
| `grace_seconds` | scheduler | how late the run may be (R-5.3); default 3600, at most 86400 |
| `tell` | scheduler | `all` (default), `problems` or `none` (R-6) |
| `held_call` | engine | the held call that carries the person's yes, when one was needed (R-4.3) |
| `ran_at` | engine | when the run began |
| `outcome` | engine | on `done`: `{kind, action, id, state}` of the written row, the shape `forward-door!` lands |
| `outcome_why` | engine | one sentence, written at the run and kept (R-6.2) |

**The idempotency key is derived, not stored:** `scheduled_action:<id>`, the
way a held call's replay is keyed `held_call:<id>`. One row is one run.
`reschedule` moves a row that has not run, so the key is still unspent.

### States and doors

```
proposed ──arm──▶ scheduled ──start──▶ running ──land──▶ done
    │                 │  ▲                 ├────skip──▶ skipped
    │                 │  └─reschedule      └────fail──▶ failed
    └──cancel──┬──────┘
               ▼            scheduled ──skip──▶ skipped   (past its grace)
           cancelled
```

- A row is born `scheduled`, or `proposed` when it waits on a person (R-4.3).
- `arm`, `start`, `land`, `skip`, `fail` are the engine's own hand, walled
  the way `the-engine-finishes-it` walls a held call's endings.
- `cancel` and `reschedule` are the scheduler's, and the person's the
  scheduler acts for. The kind declares `:own-surface {:by :scheduler
  :actions #{:cancel :reschedule}}`: whoever scheduled it reads it and stops
  it with no further grant.
- `reschedule {run_at, zone}` keeps the snapshot. *Strict* means "as I saw it
  when I decided", and moving the time is not deciding again. It runs the
  scheduling check again (R-3.1). Whoever wants a fresh snapshot cancels and
  schedules anew.
- `done`, `skipped`, `failed`, `cancelled` are terminal. **`skipped` is not
  a failure**: it is the validity rule doing its work. `failed` is a call
  that was attempted and did not land.

### Limits

`run_at` is at least one minute and at most 366 days ahead. One scheduler
holds at most 100 rows in `proposed` or `scheduled`. Both are refusals at
scheduling with a sentence, and both are numbers the first child may tune.

## R-2. Validity: three rules, chosen per row

All three read the target **at `run_at`, under the runner's grant as it is
then**. A row the runner can no longer see is skipped with the same sentence
as a row that is gone, because the engine does not tell those two apart for
anybody.

### R-2.1 `strict`

The run sends the snapshot's etag as If-Match (step 6). Any write to the row
since scheduling moves the version, the fence answers 412, and the row is
`skipped`: *"The ticket changed since this was scheduled (version 7, now
9)."*

Strict does **not** take the relaxation `forward-door!` gives a held edit
(the `prefill_digests` re-read that lets an unrelated transition through).
That relaxation is a judgment about which changes matter, and a scheduler
who wants one says `state` or `conditions`.

### R-2.2 `state` (the default)

Three checks, in this order, and no If-Match:

1. the row is in `snapshot.state` (or `expect_state`);
2. the row's envelope, read as the runner, still advertises the action;
3. a full dry run of the call passes: schema, guards, and no warning that
   was not accepted in `acknowledge_warnings`.

Then the invoke. An edit that did not move the state does not matter. A row
that left the state and came back to it passes: the rule is about where the
row *is*, and the row's history is in the log for whoever asks.

### R-2.3 `conditions`

`state`, plus the scheduler's own predicates over the row's fields, read
between checks 1 and 2.

**The grammar is the collection filter's, exactly, and nothing more.**
`conditions` is a map of filter parameter to string value, the same map
`waymark_query` takes as `filter`. For a kind, the legal names are the ones
`collections/grammar` answers for it:

| parameter | meaning | present when the field declares |
|---|---|---|
| `field` | equals; a comma-separated value is *any of* | `:eq`, or `:in` for the comma form |
| `field_ne` | not equal; comma is *none of* | `:ne` |
| `field_gte`, `field_lte` | inclusive bounds | `:range` |
| `field_after`, `field_before` | exclusive bounds on a date or instant | `:after`, `:before` |
| `field_set` | `true` or `false`: the field is present | `:set` |
| `field_contains` | substring | `:contains` |
| `state` | any of the machine's states | always |

Entries are ANDed. There is no OR, no negation beyond `_ne`, no comparison
between two fields, no other row, and no `now`: a condition compares a field
with a constant written at scheduling. **No code in a condition**, and no
second grammar to keep in step with the first.

Three rules follow from reuse:

- A field the kind does not declare `:filterable` with that operator cannot
  be a condition. The refusal is the collection's own, with its vocabulary.
- A field the scheduler's grant does not admit is refused the same way an
  unknown one is, at scheduling. At `run_at` the conditions are read under
  the runner's grant *then*; a condition the grant no longer admits skips
  the row.
- The evaluator is the collection's. The run asks the store for *this row,
  where these conds hold* (`parse-query`'s conds plus the row's id) and reads
  one row or none. The casts and the comparisons are therefore the ones a
  person sees when they filter the list, by construction.

The skip names the first condition that failed and the value it met:
*"Not reopened: priority is 1, and the condition was priority=2."* The value
is shown only when the runner may read the field.

### R-2.4 A create has no row

- `state` — the collection still advertises the create to the runner, and
  `create-dry-run!` passes in full (not `:partial`).
- `strict` — the same, **and** the kind's law revision equals
  `snapshot.law_revision`. A create has no version to pin; what a scheduler
  who says *strict* can pin is the law the create will be judged and stamped
  by. A promoted definition skips it.
- `conditions` — refused at scheduling, 422. There is no row to read, and a
  predicate over the collection ("only if no open ticket has this title") is
  a query, which this grammar does not have.

## R-3. The checks

### R-3.1 At scheduling

The call is rehearsed as the scheduler, under the grant the scheduler wears:
the same dry run the invoke door runs, with the same `acknowledge_warnings`.
**What would be refused now is refused now**, with the door's own refusal
and remedies, and no row is written. A confirm door demands its consequence
sentence here, as it does on an invoke.

### R-3.2 The two things a scheduler may name as expected

Scheduling exists partly for calls that cannot run yet. Two refusals may be
passed over, each only when the scheduler names it:

- **State.** `expect_state: "deferred"` on a `resume` of a ticket that is
  still open. The scheduling check then validates the input's schema and
  that the action is declared from that state, and judges no guard: a guard
  judged against a row in the wrong state is a plausible wrong answer.
  `snapshot.state` is the expected state. `strict` is refused with
  `expect_state`: there is no version to pin on a row that has not got
  there.
- **Guards.** `expect_refusals: ["not-before-monday"]`, guard names as a
  refusal spells them. The engine does not know which guards are "about
  time", and this spec does not teach it; the scheduler says which refusal
  it expects to lift, and the names are on the row for the reader.

Neither loosens the run. At `run_at` every guard is judged for real.

### R-3.3 At `run_at`

`start` (the claim, R-5.2), then the validity rule, then a dry run, then the
invoke with the derived key. Dry run and invoke are two transactions and the
row may move between them; the invoke's own steps 5, 6 and 9 are the
judgment that counts, and a refusal there is a **skip**, not a failure,
whenever it is a refusal the dry run could have given (wrong state, fence,
guard, confirm). `failed` is kept for what a rehearsal cannot see: a handler
that throws, a wire that drops.

**The confirm gate is read again.** The stored `acknowledge` is compared
with the sentence the row states at `run_at`, through the one accessor
`mcp/consequence-of` already is. A sentence that changed skips the row:
*"The consequence changed since this was acknowledged."* The scheduler
agreed to a sentence, not to a verb.

**The law is the row's law at `run_at`** (step 4). A definition promoted
between scheduling and the run judges the run. That is the meaning of
*checked again*.

## R-4. Who it runs as, and approval

### R-4.1 A person

Runs as that person. The principal is built at `run_at` from the member row
as it stands then, **roles read then, not carried** (the jobs deviation,
closed rather than inherited). A member who is gone, or who lost the role
the door wants, meets the door's ordinary refusal and the row is skipped.

### R-4.2 An agent or a seat

`acts_as` is the scheduler's own principal: the seat's sitter (its member
id, type agent, `:acts-for` its owner, as `door-principal` builds it) or the
connector's delegate. `grant` is the **id** of the grant it wore. At
`run_at` the grant is read by that id:

- revoked, expired or narrowed past this call: skipped, *"The grant this was
  scheduled under no longer admits it."*
- widened: the run wears the wider grant. That is what *as it is at
  `run_at`* means in both directions.
- the seat is parked or retired: skipped. A parked seat does nothing, and
  that includes what it arranged before it was parked.

`acts_as` and `scheduler` are stamped by the engine from the scheduling
principal. No input names them, so nobody schedules as somebody else.

**The sitting.** The scheduling is one transition (the `scheduled_action`
create) and counts on the sitting that made it. **The run counts on no
sitting.** The seat's sitting may be closed at `run_at`, and reopening one,
or minting a system sitting, would put a transition on a tally no sitter
reported. The run's transition is in the target's history under the seat's
member, and its actor carries `scheduled: <scheduled_action id>` beside
`allowed_by` (`invoke/actor-map`), so the correction count and the audit
both read where it came from. The 100-row limit (R-1) is what stops a seat
from spending tomorrow's budget today.

### R-4.3 A call that would be held for a person

**The person approves at scheduling, and the run needs no second tap.** A
tap at 07:00 for a message meant to go out at 07:00 while the person sleeps
is the feature not working.

- The scheduling check meets a hold (a `:hold true` guard, or a power whose
  `approval` is `person`). The engine writes the row `proposed` and mints one
  `held_call` whose door is `scheduled_action.arm` on that row. `shown` says
  the call **and the time**: *"groom ticket 4f2a · tomorrow 08:30"*. A
  person who approves a scheduled call must see when.
- Allow replays `arm`: `proposed → scheduled`, and `held_call` is written on
  the row. Refuse cancels the row with the decider's reason. Expiry (the held
  call's 24 hours) or `run_at` passing first skips it: *"Nobody approved
  this before its time."*
- At `run_at` the run invokes the door under `:within` naming the
  `scheduled_action`. The hold guards learn one more shape of the person's
  yes: a `scheduled_action` that is `running`, names this door, this caller
  and this input, and whose `held_call` is `done` by `allow`. A hand at the
  wire cannot set `:within`, as today.

**Every wall still holds.** `the-caller-does-not-decide` is unchanged: the
held call's `caller` is the scheduler, so no scheduler approves its own
schedule. The approver role is unchanged. The yes covers **this call at this
time**: `reschedule` on a row that carries a `held_call` sends it back to
`proposed` with a new held call, and no door edits the input of a scheduled
row at all.

What the yes does **not** carry forward is the grant. An approved call whose
grant was revoked on the day between is skipped (R-4.2). The person said the
agent may do this; the grant says whether the agent may still do anything.

### R-4.4 A power tool

`target: {tool}`, `input` the arguments with their `why`. There is no row
and no rehearsal: an external server has no dry run, and this spec does not
invent one.

- `state` — at `run_at` the tool is still among the runner's powers and its
  `mcp_server` row is still serving.
- `strict` — the same, and the `mcp_server` row's version equals the
  snapshot's (its `powers` entry and filter did not move).
- `conditions` — refused at scheduling.

The run goes through the power door's own judgment (`gate-proxy/invoke-for`)
at `run_at`, so the filter's `allow` globs and the `why` are prepared from
the server row as it is then. This is the deliberate difference from a held
call, which forwards what was prepared at call time, and it is reason 1 of
R-1 in practice.

**An external call does not land once by key.** The engine's key stops a
second *forward*; it cannot stop a message a server sent before the wire
dropped. So a power run that ends unknown is `failed` with *"It may or may
not have been sent"*, and is never retried (R-5.4).

## R-5. The clock

### R-5.1 The loop

A new sweep, `scheduled/sweep-due!`, on its own loop of `start-tick!`'s
shape (a daemon thread, a latch, a warned and survived failure), **not a
line in `wakes/tick!`**. The tick is the seat module's and runs where seats
are served; scheduled actions are the core's, and a household engine with no
seat must still groom a ticket at 08:30. The body is one public function a
test calls instead of waiting, as `tick!` and `sweep-pending!` are.

The interval is thirty seconds and the granularity is a minute: `run_at` is
whole minutes, and a row runs in the pass after its minute begins. A pass
takes rows in `scheduled` with `run_at <= now`, oldest first, up to a cap
(`sweep-cap`'s idea), and the rest wait one pass.

### R-5.2 One runner

Two things, and the second is the one that is true under every failure:

1. The loop's lifecycle hook carries `:elected :scheduled-actions`, the role
   the forge pass, the wake tick and the jobs orphan sweeper use, so one
   process per database runs it.
2. **`start` is the claim.** It is an ordinary invoke (`scheduled →
   running`), which takes the row lock at step 3. Two allocations during a
   roll that both believe they are elected both call `start`; one moves the
   row, the other meets wrong-state and goes on to the next row. Only the
   hand that moved the row runs the call, and the call's own key
   (`scheduled_action:<id>`) makes a second attempt a replay.

### R-5.3 Rows due while the engine was down

A row whose `run_at` has passed runs **late, within its grace**:
`now <= run_at + grace_seconds` (default one hour). Past it, the sweep walks
`skip`: *"Not run: the engine was down at 08:30 and came back at 11:02,
past this action's one-hour grace."* The grace is the scheduler's because
only the scheduler knows whether a 07:00 message is still wanted at 09:00.

### R-5.4 Rows a stopped engine left `running`

A row `running` for more than five minutes has no live runner.

- An engine door is run again with the same key. If the first attempt
  committed, step 2 or step 8 answers its result and the row lands `done`;
  if not, this is the first run. Either way it lands once.
- A power tool is failed, never retried (R-4.4).

## R-6. Telling the person

### R-6.1 What reaches them

`done`, `skipped` and `failed` are ordinary transitions of an ordinary kind,
so the delivery that exists carries them: the transition log, the feed, the
SSE stream, and `notifier` with its quiet hours and digest. What this kind
adds is the address: **the person is `scheduler`, or the person `acts_as`
acts for** when the scheduler is an agent or a seat. One engine-declared
notice rule per ending names that field; nothing new is delivered by a new
road.

`tell` narrows it: `all` tells the three endings, `problems` tells `skipped`
and `failed`, `none` tells nothing. `cancelled` is never told, since the
person did it or refused it. A row's envelope shows its pending scheduled
actions (R-7.3), so the *before* is visible too.

### R-6.2 The outcome line

`outcome_why` is one sentence, at most 240 characters, written at the run
and **kept, not rendered later**: the as-of spec's lesson, that the words
the household read that day are the record. It says what was to happen,
when, and what did, in the scheduler's zone:

- done — *"Groomed ticket 4f2a at 08:30, as scheduled."*
- done, late — *"Groomed ticket 4f2a at 08:47; it was scheduled for 08:30."*
- skipped — *"Not reopened: priority is 1, and the condition was
  priority=2."*
- skipped — *"Not edited: the ticket changed since this was scheduled
  (version 7, now 9)."*
- failed — *"Telegram did not answer at 07:00. The message may or may not
  have been sent."*

The kind's `:summary` is `{target} · {run_at} · {state}`, and after an
ending the line is `outcome_why`.

## R-7. Surfaces

### R-7.1 The kind itself (first)

`scheduled_action` is a kind like any other: `create` on its collection
schedules, `cancel` and `reschedule` are doors on the row, and
`waymark_query` lists them. An agent or a script can schedule from day one
with nothing but this, and every later surface is a shorter way to write
the same create.

### R-7.2 `at` on `waymark_invoke`, then on `waymark_power` (second)

`waymark_invoke` gains optional `at`, `validity`, `conditions`, and the rest
of R-1's scheduler fields (`expect_state`, `expect_refusals`,
`grace_seconds`, `tell`). With `at`, the call is not made. The answer is
`{scheduled: true, scheduled_action: <id>, run_at, shown}`: **an answer, not
a refusal**, counted as one served answer, the way `held-answer` is. So an
agent can schedule any call it could make, by adding one argument to the
call it already knows how to write.

- `at` is RFC 3339 with an offset, or a local date-time with `zone`. A local
  time with no zone uses the scheduler's member zone; where there is none,
  the call is refused with a sentence. **The engine never guesses UTC.**
- A local time that does not exist (the spring gap) is refused. One that
  occurs twice (the autumn hour) takes the earlier, and `shown` says which.
- `run_at` is an instant from then on. A person who later changes zone does
  not move what they scheduled.
- `dry_run` with `at` rehearses the scheduling: R-3.1's verdict, no row.
- `at` with `ids` or `items` is refused in this pass. A scheduled bulk call
  is a scheduled job, and that is its own design.
- `waymark_pursue` does not take `at`.

`waymark_power` gains the same `at` with R-4.4's meaning, after R-4.3 lands,
because most scheduled power calls are held ones.

### R-7.3 The UI: "Do this later" (third)

An action dialog gains a second button beside its submit. It opens a time
picker in the person's zone, with the zone named, and the validity choice in
plain words:

- *Only if nothing about it changes* (`strict`)
- *As long as it is still \<state\>* (`state`, chosen)
- *Only if…* (`conditions`: rows of field, operator and value, drawn from
  the kind's query input schema, the same control the collection's filter
  bar is)

A row's page lists its pending scheduled actions with cancel and
reschedule. A confirm door shows its consequence sentence in the same
dialog; the tap that schedules is the acknowledgment.

### R-7.4 The MCP Apps page (later)

A scheduled action is shown read-only with its cancel door, once the dialog
has shown which fields people read. Not in this epic's first pass.

## Walkthroughs

**A person grooms a ticket at 08:30 tomorrow (`state`).** Colton opens the
draft, presses *Do this later* on `groom`, picks tomorrow 08:30. The check
rehearses `groom` as that person: it passes. The row is `scheduled`,
snapshot `{state: draft, version: 4}`. Overnight an agent edits the ticket's
detail (version 5). At 08:30 the sweep claims the row; the ticket is still
`draft`, its envelope still offers `groom` to the runner, the dry run
passes, the invoke lands. *"Groomed ticket 4f2a at 08:30, as scheduled."*
Had somebody dropped the ticket at midnight: *"Not groomed: the ticket is
dropped, and it was draft when this was scheduled."*

**An agent sends a Telegram message at 07:00 through `waymark_power` (held
at scheduling).** The inbox seat calls `waymark_power` with `at`. The power's
approval is `person`, so the row is born `proposed` and a held call goes to
the owner: *"send_message · tomorrow 07:00"*, with the text. The owner
allows it that evening; `arm` moves the row to `scheduled`. At 07:00 the
seat's sitting is long closed. The run reads the seat's grant by id (still
there, still admitting `messages.send`), goes through the power door under
`:within`, and the message goes out with no tap. It counts on no sitting.
Had the owner parked the seat at 23:00: skipped, and the owner is told why.

**Reopen a ticket Monday only if it is still dropped and still P2
(`conditions`).** `reopen` on a dropped ticket, `at` Monday 09:00,
`validity: conditions`, `conditions: {priority: "2"}`. `state` already
covers *still dropped*. Scheduling checks that `ticket` declares `priority`
filterable by equality and that the scheduler may read it. On Friday
somebody restates the ticket to P1. Monday 09:00: the state holds, the
condition does not. *"Not reopened: priority is 1, and the condition was
priority=2."*

**A strict edit that a later comment skips.** An agent schedules a `restate`
of a ticket's detail for 18:00 with `validity: strict`, snapshot version 7.
At 15:00 a person comments; a comment is a transition and the version is 8.
At 18:00 the If-Match fence answers 412 and the row is skipped: *"Not
edited: the ticket changed since this was scheduled (version 7, now 8)."*
That is the point of strict: the agent wrote its edit against a ticket
nobody had answered, and somebody answered.

## R-8. The children

Each is one pull request with its tests named. **1 and 2 are first and are
the feature**; a create on the kind is a surface (R-7.1), so nothing after
them is needed to use it.

1. **The kind and its doors, `strict` and `state`.** `server/scheduled.clj`:
   the resource, its states and walls, the own surface, the scheduling check
   (R-3.1, R-3.2), the zone rules, the limits, engine-door targets and
   creates. No clock: `start` and the run are public functions.
   Tests, `waymark10.scheduled-actions-test`: the scheduling check refuses
   what the door refuses; `expect_state` and `expect_refusals`; the snapshot;
   nobody names `acts_as`; cancel and reschedule by the scheduler only;
   strict skips on any version move; state runs past an edit and skips on a
   state move; a create under both rules; the confirm sentence re-read; the
   run lands once under a repeated call.
2. **The clock and the run.** `sweep-due!`, its loop and its elected hook,
   the grace, the `running` recovery, the grant read by id at `run_at`, the
   member read at `run_at`. Tests, `waymark10.scheduled-clock-test`: a due
   row runs and an undue one does not; late within grace; past grace skips
   with the sentence; two sweeps over one row write one transition; a
   revoked grant skips; a parked seat skips; a row left `running` is
   recovered once; the run counts on no sitting.
3. **`at` on `waymark_invoke`.** The arguments, the scheduled answer,
   `dry_run` with `at`, the refusals for bulk. Tests,
   `waymark10.mcp-schedule-test`.
4. **Approval at scheduling, and power targets.** `proposed`, `arm`, the
   held call that names the time, the hold guards' new `:within` shape,
   `{tool}` targets, `at` on `waymark_power`. Tests,
   `waymark10.scheduled-approval-test`: the caller does not approve its own
   schedule; allow arms; refuse cancels; no second tap at `run_at`;
   reschedule asks again; a revoked grant still skips an approved row; a
   power run that ends unknown fails and is not retried.
5. **Conditions.** The filter grammar over one row, the refusals at
   scheduling, the skip sentence. Tests,
   `waymark10.scheduled-conditions-test`: one case per operator in R-2.3's
   table; a field outside the grant refused as unknown; conditions on a
   create refused.
6. **Notifications.** The engine-declared notice rules, `tell`, the outcome
   sentences. Tests, `waymark10.scheduled-notice-test`.
7. **The UI dialog.** *Do this later*, the picker, the validity choice, the
   pending list on a row's page. Proved by a `ui-drive.mjs` scenario.

4 may land before 3; 5, 6 and 7 are independent of each other.

## What this is not

- **Not recurrence.** One row is one run. "Every Monday" is a seat's
  cadence or a rule that schedules, and neither is designed here.
- **Not a replacement for `defer`.** `defer_until` is a fact about a ticket
  that a person reads on the ticket. A scheduled `resume` at that date is
  how a deferred ticket could come back by itself, and wiring `defer` to
  schedule one is a decision for the ticket kind.
- **Not a queue.** A scheduled action runs at a time, not after another one.

## To confirm in the first child

This pass read `held_calls.clj`, `invoke.clj`, `wakes.clj`, `jobs.clj`,
`collections.clj`, `delegation.clj`, the confirm gate in `mcp.clj` and
`ticket.clj`'s defer handlers. It did not read these, and the first child
must before it relies on them:

- **Where a member's zone lives.** `notifier` keeps a zone inside
  `notify.quiet`; whether the member row carries one of its own was not
  read. If it does not, child 1 adds it.
- **How a grant is read by id for a principal that is not at the wire**
  (`grants.clj`). R-4.2 depends on it.
- **Whether the store can add an id cond to a parsed query** without a new
  storage operator. R-2.3's evaluator depends on it; the fallback is the
  same conds judged in memory over the decoded row, with the same casts.
- **That a ticket comment moves the row's version.** The strict walkthrough
  assumes it, from step 13 (every transition is version + 1).
- **Where the `:elected` hook is declared** for a core loop. `wakes.clj` and
  `jobs.clj` both say the module's lifecycle hook carries it; the
  declaration itself was not read.
- **The ticket kind's own door names.** The walkthroughs say `reopen` and
  `restate`; only `groom`, `defer`, `resume` and `unblock` were read.
