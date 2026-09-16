# Spec — the seat: requirements

**Purpose.** This document gives the requirements for the seat, the
model, and the sitting in waymark10. A seat is the unit of the bill:
it groups work that needs one level of judgment, it names the
cheapest model that can hold that work, and it records what the
work cost. Waymark is the machine that lowers the bill: it speaks
the law at the door, so the model does not have to know it ahead of
time. The seat measures. Waymark lowers. This document gives the
rules that tie the two together.

This document is written in ASD-STE100 Simplified Technical English.
Technical names from the codebase keep their spelling: grant, scope,
leash, ask, anchor, door, kind, sitter, sitting. "Must" gives a
requirement. "Can" gives a permission. "Is" gives a fact.

Source: Yegge, "Seats and Sunsets", 2026-09-15. The owner's
statement of the goal, 2026-09-16: "Waymark is really about just in
time context for AI agents. The agent doesn't need to know the law
ahead of time, because waymark will tell it the law when it attempts
to do something against the law." Bead: waymark-fp62.1, leg 1 of
the epic waymark-fp62.

## 1. The bill

A model turn pays for three things.

| item | what the model must hold | who can hold it instead |
|---|---|---|
| the rules | what is allowed, what a field means, which door comes next, what the house forbids | the engine, at the door |
| the situation | what is in the queue, what this row says, what happened last time | the row, the filter, the envelope |
| the judgment | is this an action item, is this sentence in the owner's voice, is this change safe | nobody. This is the model's work. |

The first two items are the bulk of most sessions, and they are
where a frontier model is wasted. A frontier model that reads a
rules file spends frontier tokens on a lookup. Waymark's end state
is to take all of the first item and most of the second off the
model. The envelope shows only the doors that are open now. The 409
speaks the rule at the moment it matters. The filter hides what is
not relevant. The row holds the state, so no turn needs the last
turn's context. When that is done, a turn's cost is judgment alone.

That is why the seat is the right unit. A seat groups work that
needs one kind of judgment at one level, and its model is set by
that judgment alone. The question "what must be true for a cheaper
model to do this work as well" has a mechanical answer: each fact
the model now derives from context must move into law the engine
speaks at the door. Each fence the engine enforces is a piece of
intelligence the house no longer rents.

The loop between the two halves is the point. The sitting records
where a seat's tokens went. When a seat spends tokens on a refused
door, on a rule it did not know, or on a queue it could have
filtered, that is not a model problem. It is a waymark defect: a
place where the law was not spoken at the door. So the sitting's
refusal count is waymark's backlog. Success has one visible shape:
seats move down the tier list over time, and their outcomes hold.

## 2. Definitions

| term | meaning |
|---|---|
| seat | an office: a name, a charter, an authority, a budget, a model floor, and a lifecycle. A row of kind `seat`. |
| sitter | the member that holds an accepted grant that cites a seat |
| substitute | a sitter whose grant has `substitute` set. It reads the seat's memory and does not write it. |
| model | a row of kind `model`: one model identifier, its tier, and its prices |
| sitting | one wake of a seat: a row of kind `sitting` with its token counts, its cost, and its counts of transitions and refusals |
| judgment | the part of a seat's work that the engine cannot say at a door |
| the residual | the charter: the judgment, written in the person's words, and nothing else |
| the floor | the cheapest model that holds a seat with its outcomes intact |
| the ladder | the path of a seat from a frontier model down to its floor, one `restate` of `held_for` per step |
| refusal | a 409 served to a sitter. Fuel spent on law the model did not know. |
| correction | a person's transition on a row that the seat's sitter moved last |
| memory | the sitter's own-surface kinds: `self`, `journal`, `letter` |
| scope | the list of kinds, actions, rows, fields, and filters a grant admits. The scope schema in `grants.clj`. |
| the four scope guards | `scope-names-real-kinds`, `scope-names-real-actions`, `scope-filters-are-filterable`, `scope-omits-private-kinds` |
| the harness | the owner's driver script, which starts the model and speaks to the engine for it |

## 3. The problem on record

**Fuel, first.** The owner's account, 2026-09-16: with the usage
habits of the summer, a week's allowance is gone by midweek on the
newest frontier model. The house has no ledger that says which work
took it. A seat that costs more than it returns cannot be found,
because nothing records what a seat costs or what it returned.
Every lever the essay names, a cheaper model, a smaller task, an
earlier hand-off, a longer cadence, is pulled blind.

**Drift, second.** A grant's scope is a copy of an ask. Each
extend-ask copies it forward again. The copy drifted three times:

- waymark-ycp: the extend fold appended, and one grant reached 74
  entries for 20 kinds. The ask door refused the next ask. The leash
  died twice with nobody asking.
- waymark-enx: a push retired `value.restate`. The stored scope still
  named it. Every extend-ask got a 409, and the leash would have died
  in silence.
- waymark-h6y: an ask with no expiry got a 30-minute default, and the
  mint copied it onto the grant.

The essay adds two facts. A seat is fluid: a person opens one when
fuel is plentiful, and parks or merges seats when fuel runs short.
And a seat has a fixed cost, because a wake costs money with no
work.

## 4. Requirements: the seat kind

**R-4.1** The engine must serve a framework kind `seat`, in
`waymark10/src/waymark10/server/seats.clj`, with `:nav :system`.

**R-4.2** A seat must have these fields.

| field | type | meaning |
|---|---|---|
| `name` | string, 1 to 40 | the token a grant and an ask spell. One spelling per seat. |
| `charter` | string, 1 to 1200 | the residual: the seat's judgment, in the person's words. R-4.10. |
| `scope` | scope schema | the seat's authority |
| `substitute_drop` | scope schema | the entries a substitute does not get |
| `held_for` | list of model refs | the models that can sit as the full sitter. Empty means any. The seat's place on the ladder. |
| `substitute_for` | list of model refs | the models that can sit as a substitute. Empty means any. |
| `standing_ttl_seconds` | int | the longest leash a grant in this seat can request |
| `cadence_seconds` | int | how often the schedule fires the seat. The fixed wake cost. |
| `budget_usd_per_week` | decimal | the seat's fuel for seven days |
| `sitting_budget_tokens` | int, 20000 or more | one sitting's ceiling, passed to the harness |
| `walk` | kind name, optional | the queue this seat walks, one row at a time, in the order of its default sort. R-12.7. |
| `rows_per_firing` | int, default 20 | the most rows one firing moves to a leaf. The walk's cap. |
| `stale` | list of scope entries | written by the sweep. A person never writes it. |
| `merged_into` | seat ref | the seat this one merged into |

There is no `must` list and no `never` list. The first draft had
both. Each sentence the model must pre-load is fuel, and each rule
in prose is a fence the engine is not yet speaking. One field holds
the residual, and its cap is the priming budget: 1200 characters is
near 300 tokens, and a cache read of it costs a fraction of a cent
per turn.

**R-4.3** A seat must have the states `active`, `parked`, `merged`,
and `retired`. `merged` and `retired` are terminal.

**R-4.4** A seat must have these actions.

| action | from | to | actor | effect |
|---|---|---|---|---|
| `create` | — | active | a person, not a sitter | opens the seat |
| `restate` | active | active | a person, not a sitter | changes charter, scope, drop-list, held-for lists, ttl, cadence, budgets, walk |
| `park` | active | parked | a person | the seat serves nothing. Grants stay. |
| `unpark` | parked | active | a person | the seat serves again |
| `merge` | active, parked | merged | a person, not a sitter | folds this scope into `into`. This seat closes. |
| `retire` | active, parked | retired | a person | the seat closes for good |

**R-4.5** `park` must have `:confirm false` and `:reversible true`.
It is the cheap lever, and it must cost nothing to pull.

**R-4.6** `merge` must have `:confirm true` with this consequence
sentence: "This seat closes. Its scope folds into {into}. Each
sitter of this seat loses its grant and must ask to sit in {into}."

**R-4.7** These guards must judge the seat's doors.

| guard | doors | rule |
|---|---|---|
| `one-spelling` | create | no active seat has this name. From `roles.clj`. |
| `a-person` | create, restate, park, unpark, merge, retire | the principal's type is human. The precedent is the `actor_type` check on the member row in `members.clj`. |
| `not-a-sitter` | create, restate, merge | the actor holds no live grant that cites this seat, or the `into` seat |
| the four scope guards | create, restate | the scope names only kinds, actions, filter fields, and non-private kinds the registry declares |
| `drop-inside-scope` | create, restate | each `substitute_drop` entry is inside `scope` |
| `ttl-within-standing` | create, restate | `standing_ttl_seconds` is not more than `reentry-standing-ttl-seconds` |
| `held-for-active-models` | create, restate | each model in the two lists is active |
| `walk-names-a-kind-in-scope` | create, restate | `walk` names a kind the scope admits, and the kind declares `:default-filters` over state |
| `step-carries-a-note` | restate | a restate that changes `held_for` or `substitute_for` carries `note`, 1 to 240 characters. `note` is a transition input; the log's `inputs` column holds it, and no column is added. |
| `merge-target-is-active` | merge | `into` is active, and is not this seat |

**R-4.8** `not-a-sitter` is the human verdict the grant law requires.
A sitter must not widen its own seat.

**R-4.9** The seat row must be own-surface for its sitters, read-only:
a grant's audience can read the seat the grant cites, with no scope
entry. The precedent is the grant, which its audience reads.

**R-4.10** The charter is the residual. It must hold the judgment
the engine cannot say at a door, and nothing else. A sentence that
names a door the scope does not open is redundant: the door is
absent from the envelope, and absence is the rule. A sentence that
tells the model which door comes next is redundant: the envelope
offers only the open doors. A sentence that repeats a correction
the person has made more than once is a fence not yet written: the
fix is a guard, a filter, a door, or a reason string, and then the
sentence leaves the charter. The engine does not judge the charter's
words. The person does, with the sitting's counts in hand (section
11).

## 5. Requirements: the grant and the ask

**R-5.1** The `grant` kind must gain two optional fields: `seat`, a
seat ref, and `substitute`, a boolean. A grant with `seat` set must
hold no `scope`. A grant with `scope` must hold no `seat`.

**R-5.2** The router must resolve a seat grant's visibility from the
seat row at each request, in this order.

1. Load the seat. If the seat is not `active`, the grant scopes to
   nothing.
2. Read the session's model. If the grant is a full grant and
   `held_for` is not empty, the model must be in `held_for`. If the
   grant is a substitute grant and `substitute_for` is not empty, the
   model must be in `substitute_for`. Otherwise the grant scopes to
   nothing.
3. Sum `cost_usd` over the seat's closed sittings of the last seven
   days. If the sum is at or over `budget_usd_per_week`, the grant
   scopes to nothing.
4. Take the seat's `scope`.
5. If `substitute` is set, remove the `substitute_drop` entries.
6. Remove the `stale` entries.
7. Resolve as a scope grant resolves today.

**R-5.3** The `extend` transition on a seat grant must change only
`expires_at`. There is no scope to merge.

**R-5.4** The `approval_request` kind must gain two optional fields:
`seat` and `substitute`. The three shapes of an ask:

| shape | fields | approval effect |
|---|---|---|
| bootstrap, scope | task, scope, expires_at | mints a scope grant, as today |
| bootstrap, seat | task, seat, substitute?, expires_at | mints a seat grant with `audience` = requester. The ask spells the seat's `name`; the mint resolves it to the seat ref and writes the ref on the grant. |
| extend | grant_id, task, expires_at | slides `expires_at` on the named grant |

**R-5.5** The ask door must refuse: an ask with both `seat` and
`scope`; an extend ask with a `scope` on a seat grant; an ask that
names a seat that is not active.

**R-5.6** `asks-are-short` must read the seat's
`standing_ttl_seconds` for a seat ask, and the 24-hour ceiling for a
scope ask.

**R-5.7** `model-may-sit` on `approval_request/create` must refuse a
seat ask whose requester's session model is not in the seat's list
for the ask's kind, full or substitute. The refusal names the list.

**R-5.8** `seat-has-one-sitter` on `approve` must refuse a second
accepted full grant that cites a seat while the first is live.
Substitutes are not limited.

**R-5.9** The approver's screen must show the seat's charter and
scope beside a seat ask, through the seat link. The approver
approves an office.

## 6. Requirements: merge

**R-6.1** `merge` must take `into`, a seat ref. The handler must:

1. fold `into`'s scope with this seat's scope through `merge-scope`,
   judge the fold with the four scope guards, and write it onto
   `into`;
2. fold the two `substitute_drop` lists the same way;
3. keep the larger `standing_ttl_seconds` and the larger
   `budget_usd_per_week`;
4. write `merged_into` on this seat and move it to `merged`.

**R-6.2** `merge` must not revoke grants and must not mint grants. A
grant that cites a merged seat scopes to nothing by R-5.2 and
expires on its own clock. Each moved sitter files a bootstrap ask for
`into`.

## 7. Requirements: the sweep

**R-7.1** The registry changes only at boot. `boot-revise!` must
judge each active or parked seat's `scope` with the four scope
guards after the kind fingerprints.

**R-7.2** For each seat that fails, the engine must write the failing
entries into `stale` through a concealed transition `mark_stale`,
system actor, logged, with the guard's own sentence as the note.

**R-7.3** A stale seat must still serve the entries that are not
stale. The leash must not go dark.

**R-7.4** A stale seat must not be quiet. `waymark_discover` must
carry `doors.ask.seat` with `name`, `state`, `standing_ttl_seconds`,
`stale`, and `budget` (spent, limit, resumes_at). The seat's envelope
must carry a warning with the stale entries. A firing must say the
stale entries and the budget line first, before any other output.

**R-7.5** `restate` must clear `stale` when the new scope passes the
four guards. A `restate` whose scope still names a stale entry is
refused by the guards, with the entry named.

**R-7.6** The boot sweep must move a sitting left `open` for more
than two cadences to `abandoned`, with no tokens.

## 8. Requirements: the substitute

**R-8.1** A substitute must not write the seat's memory. `self`,
`journal`, and `letter` are private own-surface kinds that no scope
can name, so the bar is a guard, not a scope entry.

The reason is continuity, not capability. The memory is the seat's
voice across sessions. A substitute that writes it writes in another
voice, and the next full sitter inherits it. The bar applies to every
substitute, whatever its tier. The audit is different: the transition
log records every act a substitute takes, with the model in the
actor, and no sitter can opt out of it.

**R-8.2** The visibility map must carry a `substitute` flag read
from the grant. The guard `not-a-substitute` must judge
`self/update`, `journal/create`, and `letter/create`. Its sentence:
"A substitute reads the seat's memory and does not write it. The
seat's own sitter writes here."

**R-8.3** A substitute can read all three kinds.

## 9. Requirements: the model kind

**R-9.1** The engine must serve a framework kind `model` in
`seats.clj`, `:nav :system`, with states `active` and `retired`.

**R-9.2** A model must have these fields.

| field | type | meaning |
|---|---|---|
| `name` | string, 1 to 64 | the API identifier, for example `claude-fable-5-1`, `claude-opus-5`, `claude-sonnet-5`, `claude-haiku-4-5`. One spelling. |
| `display` | string | the name a person reads |
| `vendor` | string | who serves it |
| `tier` | enum frontier, strong, economy | the rung on the ladder. The ordering is frontier, then strong, then economy. |
| `price_input_per_mtok` | decimal | dollars per million input tokens |
| `price_output_per_mtok` | decimal | dollars per million output tokens |
| `price_cache_read_per_mtok` | decimal | dollars per million cache-read tokens |
| `price_cache_write_per_mtok` | decimal | dollars per million cache-write tokens |
| `notes` | string | free prose |

**R-9.3** A model must have the actions `retire`, `reactivate`, and
`reprice`. Each reprice is a transition, so the history of prices is
on record.

**R-9.4** The harness must declare the session's model. A session
is a signed token, not a row (`oidc.clj`, HS256), so the model is a
claim in the token, beside `actor_type`. `POST /auth/agent` and
`POST /auth/agent/renew` must accept `model` and mint it into the
token. A token with no claim has model null. The MCP `initialize`
cannot rewrite a cookie, so it is not a declaration door; the
harness declares at bind and at renew only (R-12.6).

**R-9.5** The principal must gain `model`, read from the session. The
actor on each transition then carries it, in the `actor` column that
exists. No migration is needed for the log.

**R-9.6** The model is a claim the harness makes. The engine cannot
verify it. The document says so, and the check is against the
harness, which is the failure the essay describes.

## 10. Requirements: the sitting kind

**R-10.1** The engine must serve a framework kind `sitting` in
`seats.clj`, with states `open`, `closed`, and `abandoned`. `closed`
and `abandoned` are terminal.

**R-10.2** A sitting must have these fields.

| field | type | meaning |
|---|---|---|
| `seat` | seat ref | the seat woken |
| `member` | member ref | stamped from the principal |
| `model` | model ref | stamped from the session |
| `grant` | grant ref | the grant worn |
| `started_at` | instant | when the model started |
| `ended_at` | instant | when the model stopped |
| `input_tokens`, `output_tokens`, `cache_read_tokens`, `cache_write_tokens` | int | the harness's exact counts, summed over the sitting |
| `turns` | int | the number of model turns |
| `transitions` | int | committed transitions under the grant while the sitting was open. The engine counts. |
| `refusals` | int | 409s served under the grant while the sitting was open. The engine counts. |
| `cost_usd` | decimal | written at close |
| `prices` | map | the four prices used at close |
| `note` | string | one sentence on what the sitting did |

**R-10.3** A sitting must be own-surface for its member, with the
actions `create`, `close`, and `abandon`. The session opens it
before it reads the queue, and the harness's hook closes it when the
session ends (R-12.3).

**R-10.4** `close` must take the four token counts and the turn count.
The handler must read the model's prices at that moment, compute
`cost_usd`, and write the prices used beside it. A reprice later
must not change a closed sitting.

**R-10.5** The engine must not estimate tokens. It records the
harness's report.

**R-10.6** The engine must count transitions and refusals. The
router must find the open sitting for the request's grant, one
lookup by `grant` and state `open` under an index on `grant`, and it
must add one to `transitions` on each committed transition and one
to `refusals` on each 409 it serves. A request with no open sitting
counts nothing. The harness does not report these. No refusal log
exists today; this counter is the first record of a refusal as
fuel.

**R-10.7** The sitting collection must be filterable by `seat`,
`model`, and `started_at` after, so these are each one query: fuel
per seat per week against its budget; fuel per model; refusals per
seat per week; cost per transition; the fixed cost of a seat, as the
sittings that wrote nothing.

## 11. Requirements: the ladder

**R-11.1** A seat opens on the model the person names. The advice
of this document: a new seat, whose judgment is not yet known,
opens on a frontier model. The first weeks find out what the
judgment is.

**R-11.2** A step is a `restate` that changes `held_for` or
`substitute_for`. `step-carries-a-note` (R-4.7) makes each step a
record: the model before, the model after, and the reason, in the
transition log.

**R-11.3** The house must answer these questions about a seat over
a window, each as one query over rows that exist.

| question | query |
|---|---|
| what did it cost | sum of `cost_usd` over closed sittings |
| what did it do | sum of `transitions` |
| where did it hit the law | sum of `refusals` |
| what did it get wrong | corrections: transitions by a person on rows whose previous transition's actor is the seat's sitter |
| what did each thing cost | cost divided by transitions |
| which model did it | group by `model` |

**R-11.3a** The six answers must be one call. `GET
/api/seats/{id}/ledger?since=` must return them for the window, and
`waymark_discover` must name the route under `doors.ask.seat`. The
corrections answer is a window over the transition log (each row's
previous transition, by `resource_id` and `id`), which no query
serves today; it is new, and it is the one query the ladder cannot
do without.

**R-11.4** A step down holds when corrections per transition do not
rise across the sittings that follow it. The engine gives the
numbers. The person judges the count of sittings; the advice of this
document is five. A step down that does not hold is reversed by one
`restate`, with a note.

**R-11.5** A refusal is a waymark defect. A refused door is a rule
the model paid to learn. The count per seat per week is the backlog
for the fence census (leg 2, waymark-fp62.2) and for priming on
demand (leg 3, waymark-fp62.3). A refusal that repeats has one of
three fixes: the reason string says what to do instead, a filter
hides the row the door does not apply to, or the door is absent
from the envelope in that state. A line in the charter is not a
fix.

**R-11.6** A correction that repeats is a fence not yet written. The
fix is law: a guard, a filter, a door, or a source that drops the
row before the model sees it. After the fix, the charter loses the
sentence that covered it (R-4.10). The frontier model's work in the
house is this: it turns a repeated correction into a rule once, and
the economy model obeys the rule at the price of a door.

**R-11.7** The floor is reached when the next step down does not
hold. The seat stays one rung above it. A seat whose floor is the
frontier is not a failure; it is a seat whose judgment is real.

## 12. Requirements: the driver

**R-12.0** The driver is the harness's own scheduler. The owner's
ruling, 2026-09-16: use the native things. In Claude, that is a
Routine: a named schedule that starts a fresh session on each
firing, with a prompt and a model. In Jules, that is a scheduled
session. The house does not write a loop script. It defines what the
schedule needs, and the seat row holds it.

| the schedule needs | the seat row holds it as |
|---|---|
| a name | `name` |
| a cadence | `cadence_seconds`, spelled as the scheduler's cron |
| a model | `held_for`, one model, set on the schedule |
| a prompt | a pointer to the seat, not the charter. Section R-12.2. |

`scripts/standing-agent-tick.sh` stays as the leash keeper: it
renews the session, files the extend-ask, and writes the cookie the
scheduled session uses to reach the engine. It starts no model, and
it does not need to.

**R-12.1** Each firing is one sitting. A firing must read
`doors.ask.seat` first. If the seat is parked, over budget, or held
for another model, the session must say the reason and stop before
it reads anything else. The reason is the sitting's `note`.

**R-12.2** The schedule's prompt must be a pointer and a walk rule,
not a copy of the charter. The prompt for a seat is this, with the
seat's name in it:

> You sit in the seat `{name}`. Read the seat row with `waymark_get`
> and do what its charter says. Take only the doors the envelope
> offers. When the seat says parked, over budget, or held for another
> model, say why and stop.

The charter stays on the row, so a `restate` changes what the next
firing does with no change to the schedule. A copy is the failure on
record (section 3). The name, the cadence, and the model are the
three things the schedule copies, and a `restate` of `cadence_seconds`
or `held_for` is also an update of the schedule. The seat's
`restate` handler must say so in its consequence: "The schedule
named {name} must be updated to match." An engine effect that
updates the schedule through the scheduler's API is the follow-up
the owner named (section 17).

**R-12.3** The session must open a sitting before it reads the
queue, and the harness must close it with the exact token counts
when the session ends. The session cannot count its own tokens, so
the close is a hook of the harness (Claude Code's session-end hook
reads the transcript's usage), not an act of the model. Until that
hook exists (waymark-fp62.6.1), the harness's own session record is
the ledger, and the sitting row is closed by hand from it.

**R-12.4** `sitting_budget_tokens` is the schedule's ceiling, through
whatever knob the scheduler has. If it has none, the prompt's walk
rule caps the rows per firing (`rows_per_firing`, section R-12.7),
and the sitting's `note` says when the cap was hit.

**R-12.5** The leash keeper must file an extend-ask as `{grant_id,
task, expires_at}` with no scope when the grant cites a seat.

**R-12.6** The schedule's model is the declaration. The leash keeper
must declare the same model at bind and at renew (R-9.4), read from
the seat's `held_for`. A schedule whose model differs from the seat's
`held_for` makes every firing stop at R-12.1, which is the harness
bug the essay describes, caught at the door.

**R-12.7** When the seat has `walk`, the firing walks. The session
reads the queue (the kind's collection under its default filter),
and for each row takes the one door the envelope offers, then the
next door, until the row is at a leaf. Then the next row. It stops
when the queue is empty or when it has moved `rows_per_firing` rows.
`rows_per_firing` is a field on the seat, default 20. One sitting
covers the firing.

A native schedule gives one session per firing, not one turn per
row, so the rows share one context. The essay's "smaller tasks" is
kept by the cap and by the envelope, which holds one row at a time.
The one-row-per-session form comes later through the scheduler's
API: the source fires the schedule with the row's id as its text,
and the firing walks that row alone. That is the "trigger via API"
the owner named, and it is a change to the source, not to the seat.

**R-12.8** The prompt must give the model nothing beyond the pointer,
the walk rule, and the engine's own answers: the seat row, the
envelope, the discover document, the schema, the refusal. No rules
file, no document from `docs/`, no law ahead of time. When a firing
fails for want of a rule, the fix is in the engine (R-11.5), never a
line in the prompt. This is the lazy-loading contract. Without it
the ladder does not descend, because the prompt grows to cover what
the engine should say.

## 13. The email clerk: the descent

The seat surfaces action items from the owner's inbox. Its name is
`inbox-clerk`. This section follows it down the ladder over five
weeks, from a frontier model with a prose charter to an economy
model walking a tree. Each week is one section.

### 13.1 Week one: the person opens the seat

One POST to `/api/seats`, by a person who will not sit in it.

```json
{
  "name": "inbox-clerk",
  "charter": "You read Colton's inbox and turn each request in it into a task in the queue. Make one task for each request that names Colton, with the due date the sender named. Do not make a task for a newsletter or a receipt. You do not answer mail. Write one journal entry at the end of each sitting.",
  "scope": [
    {"kind": "email.read", "actions": []},
    {"kind": "task", "actions": ["create", "prioritize"], "filter": {"source": "todo"}},
    {"kind": "insight", "actions": ["create"]}
  ],
  "substitute_drop": [
    {"kind": "insight", "actions": ["create"]}
  ],
  "held_for": ["claude-opus-5"],
  "substitute_for": ["claude-sonnet-5"],
  "standing_ttl_seconds": 604800,
  "cadence_seconds": 3600,
  "budget_usd_per_week": 12.00,
  "sitting_budget_tokens": 40000
}
```

The four scope guards judge it at the door. `email.read` is a Gate
power, and a scope entry names a power in its `kind` field.
`task.create` and `task.prioritize` exist. The filter `source =
todo` is legal because `source` is declared filterable with eq, so
the seat sees the todo tasks and not the chores or the meals.
`insight.create` exists. The drop entry is inside the scope. Both
model names are active model rows. Seven days is at the cap.

The first draft of this charter had a sixth sentence: "Do not move
or send mail. The scope does not open those doors." It is cut. The
doors are absent from `waymark_powers`, and absence is the rule
(R-4.10). The charter starts on the strong tier, not the frontier,
because the person already knows the judgment is small.

### 13.2 The models exist as rows

Four rows of kind `model`, created once by the person and repriced
when the vendor moves. The prices are the first-party rates on the
day of writing.

| name | tier | input $/M | output $/M |
|---|---|---|---|
| `claude-fable-5-1` | frontier | 10.00 | 50.00 |
| `claude-opus-5` | strong | 5.00 | 25.00 |
| `claude-sonnet-5` | economy | 2.00 | 10.00 |
| `claude-haiku-4-5` | economy | 1.00 | 5.00 |

### 13.3 The sitter is a member

This exists today. The person mints an invite link. The agent opens
the welcome document and binds. The result is a member row with
`actor_type` agent and a re-entry credential. Its `self`, `journal`,
and letters are its memory.

### 13.4 The agent asks to sit

The leash keeper binds and declares `claude-opus-5`, the model the
Routine will start. The agent files one ask, with no scope.

```json
{
  "seat": "inbox-clerk",
  "task": "Sit in the inbox clerk seat for one week.",
  "expires_at": "2026-09-23T12:00:00Z"
}
```

`model-may-sit` passes, because the session's model is in
`held_for`. The approver sees the ask, and beside it the seat's
charter and scope. The approver taps approve. The effect mints:

```json
{
  "audience": "<member id>",
  "seat": "inbox-clerk",
  "substitute": false,
  "expires_at": "2026-09-23T12:00:00Z"
}
```

There is no scope on this grant.

### 13.5 One sitting

The leash keeper has renewed the session on its own cron and
declared `claude-opus-5`. At the cadence, the Routine named
`inbox-clerk` fires a fresh session on `claude-opus-5`. Its prompt
is the pointer of R-12.2. Nothing else (R-12.8).

1. The session calls `waymark_discover`. `doors.ask.seat` says:
   active, stale empty, budget spent 3.10 of 12.00, resumes null.
2. It opens a sitting: seat, grant, started_at.
3. It reads the seat row with `waymark_get`, for the charter.
4. `waymark_powers` lists the emila read tools. Move and send are
   absent.
5. The model reads the inbox through `waymark_power`.
6. For each request it finds, it invokes `task.create` with the
   title, the due date, and source todo. Each create is a transition
   with the member as actor and `claude-opus-5` as model. The router
   adds one to the sitting's `transitions`.
7. It tries `task.update` on a task it made, to add a note. The
   door is absent. It tries `task.create` with a due date in the
   past. The guard refuses, 409. The router adds one to
   `refusals`.
8. It invokes `insight.create` for a finding that cites a task it
    made, and writes one journal entry.
9. The session ends. The harness's session-end hook closes the
    sitting with the usage from the transcript: input 31200, output
    2900, cache read 18000, cache write 0, turns 6. The handler
    writes `cost_usd` 0.23 and the prices used. The row already
    holds transitions 4 and refusals 1.

### 13.6 What the record holds

| row | points at | holds |
|---|---|---|
| seat `inbox-clerk` | two models | charter, scope, budgets, stale |
| grant | seat, member | expires_at, substitute |
| member | — | display, roles, the re-entry credential |
| self, journal, letter | member | the sitter's memory |
| sitting | seat, member, model, grant | tokens, cost, prices used, transitions, refusals |
| transitions on task and insight | member as actor, with model | the history |

"Who sits in the inbox clerk seat" is one query: accepted grants that
cite the seat. "What did the seat cost this week" is one query:
closed sittings that cite the seat, started in the last seven days.

### 13.7 Week one's ledger

At the end of the week the person reads the six questions of R-11.3
for the seat.

| question | week one, Opus, prose charter |
|---|---|
| cost | 9.80 |
| transitions | 61 tasks, 12 insights, 7 journal entries |
| refusals | 23 |
| corrections | 9: the person deleted 9 tasks |
| cost per transition | 0.12 |

The refusals are of two shapes. Fourteen are a due date in the past,
from mail that named a date already gone. Nine are a second task for
a message the seat had already handled in an earlier sitting. The
corrections are of one shape: receipts and newsletters that named
Colton in the body.

None of these is a judgment problem. Each is the law spoken late.
The past-date refusal is fuel spent on a rule the guard could have
put in its reason string, with the fix named. The duplicate is a
queue the seat rebuilt from the inbox on every wake, because nothing
held which messages it had seen. The receipts are a filter, not a
sentence in the charter.

### 13.8 Week two: the frontier model writes the law

The person spends frontier fuel once. A session on
`claude-fable-5-1`, in code, turns the three repeated failures into
law.

**The decision tree is a kind.** The owner's design, 2026-09-16: an
economy model comes online, its first prompt is the charter, and
then it walks a decision tree until it reaches a leaf. For email: a
queue of messages, and for each one three moves. Research opens the
message and enables the other two. Yes states the action item. No
dismisses it.

A tree with branches is a state machine with two doors from one
state. That is an ordinary `defresource`, not a `:process` (which
has no branches by design). The tree lives in code, because it is
law. The seat lives in a row, because it is fluid.

```clojure
(defresource inbox_item
  {:kind :inbox_item
   :states [:queued :researched :action_item :dismissed]
   :initial :queued
   :terminal #{:action_item :dismissed}
   :default-filters {:state "queued"}          ; the queue is the collection
   :sortable {:fields [:received_at] :default "received_at"}
   :schema [:map
            [:message_id  [:string]]           ; the address in the inbox
            [:subject     [:string]]
            [:sender      [:string]]
            [:received_at :waymark/instant]
            [:summary     {:optional true} [:maybe [:string {:max 480}]]] ; written by research
            [:task        {:optional true :kind :task} [:maybe :waymark/ref]] ; stamped by yes
            [:reason      {:optional true} [:maybe [:string {:max 240}]]]]  ; written by no
   :actions
   {:research {:from #{:queued} :to :researched
               :input [:map [:summary [:string {:min 1 :max 480}]]]
               :display {:label "Research" :order 1}}
    :yes      {:from #{:researched} :to :action_item
               :input [:map [:action_item [:string {:min 1 :max 200}]]
                             [:due_at {:optional true} [:maybe :waymark/instant]]]
               :touches [{:kind :task :action :create}]   ; the task is born here
               :handler yes->task                         ; ctx :create, outer principal
               :display {:label "Yes, action item" :order 2}}
    :no       {:from #{:researched} :to :dismissed
               :input [:map [:reason {:optional true} [:maybe [:string {:max 240}]]]]
               :display {:label "No" :order 3}}
    :reopen   {:from #{:dismissed} :to :researched         ; the person's correction
               :display {:label "Reopen" :order 4}}}})
```

The tree is enforced by the machine, not by the prompt. At `queued`,
the envelope offers one door: research. At `researched`, it offers
two: yes and no. At a leaf, it offers none to the sitter. The model
cannot skip research, because the yes door is absent until it is
done. It cannot make a task except through yes, which demands the
action item in one sentence. `:touches` advertises the task birth,
and the conformance library checks that it fired. `reopen` is the
person's door: a reopen after a `no` is a correction, and the query
of R-11.3 finds it.

**Three repeated failures, three pieces of law.**

| week one failure | the law |
|---|---|
| a due date in the past, 14 refusals | the `yes` door accepts `due_at` in the past and the task guard's reason string says: "The due date has passed. Omit it, or set today." One refusal becomes zero. |
| a second task for a handled message, 9 refusals | the queue. `inbox_item` holds `message_id`, and a leaf is never offered again. Zero refusals. |
| a receipt or a newsletter, 9 corrections | the source. A message with a list-unsubscribe header is not minted. The sentence leaves the charter. |

**The queue fills with no tokens.** A source in
`workqueue10/sources/`, on the pattern of `gtasks.clj`, lists the
inbox headers through the `email.read` power at the cadence and
mints one `inbox_item` per new message id, and none for a message
with a list-unsubscribe header. Headers only. The body is never
stored; the model reads it through `waymark_power` at research
time.

This is one deploy, and it is the last deploy in this section.

### 13.9 Week three: the seat steps down to the walk

The person restates the seat. No deploy.

```json
{
  "charter": "You triage Colton's inbox. For each message the queue offers, take the one door the envelope shows. Research first. Then say yes with the action item in one sentence, or no. A request that names Colton and asks for something is a yes.",
  "scope": [
    {"kind": "email.read", "actions": []},
    {"kind": "inbox_item", "actions": ["research", "yes", "no"]}
  ],
  "held_for": ["claude-sonnet-5"],
  "walk": "inbox_item",
  "cadence_seconds": 3600,
  "budget_usd_per_week": 4.00,
  "sitting_budget_tokens": 20000,
  "rows_per_firing": 20,
  "note": "Week one's refusals and corrections are law now. The walk needs no judgment the envelope does not frame. Step from opus to sonnet."
}
```

The scope no longer names `task.create`. The task is born inside
the yes handler through the cross-write door, under the outer
principal, and `:touches` says so. The charter lost the receipts
sentence and the journal sentence. It is 234 characters. The seat is
held for an economy model as its full sitter, and
`step-carries-a-note` records why.

**The schedule.** The person updates the Routine to match the seat.
Three fields change on it, and the prompt does not.

| Routine field | value | from the seat |
|---|---|---|
| name | `inbox-clerk` | `name` |
| schedule | `0 * * * *`, hourly | `cadence_seconds` 3600 |
| model | `claude-sonnet-5` | `held_for` |
| prompt | the pointer of R-12.2 | unchanged |

**One firing, row by row.** The session opens one sitting, reads the
seat row, then reads the queue:

1. The envelope of the first row offers one door, research. The
   model reads the message through `waymark_power`, then invokes
   research with a summary.
2. The envelope now carries the summary and offers yes and no. The
   model takes one.
3. The row is at a leaf. The model moves to the next row.
4. After twenty rows, or an empty queue, the session says so and
   ends. The hook closes the sitting.

The envelope holds one row at a time, never the queue. The rows
share one session's context, capped at twenty; the one-row-per-
session form comes when the source fires the Routine with a row id
(R-12.7). Each transition carries the member and `claude-sonnet-5`
in the actor.

**Week three's ledger, beside week one's.**

| question | week one, Opus, prose | week three, Sonnet, walk |
|---|---|---|
| cost | 9.80 | 1.90 |
| transitions | 80 | 148: 74 research, 58 yes, 16 no |
| refusals | 23 | 0 |
| corrections | 9 | 2 reopens |
| cost per transition | 0.12 | 0.013 |

The step holds: corrections per transition fell. The seat has not
lost an outcome. It gained the count of what it declined, which the
prose seat never recorded.

### 13.10 Week five: the floor

The person tries one more rung. `restate` with `held_for`
`["claude-haiku-4-5"]` and the note "Try the last rung." Two weeks
later:

| question | week three, Sonnet | week five, Haiku |
|---|---|---|
| cost | 1.90 | 0.95 |
| transitions | 148 | 151 |
| refusals | 0 | 0 |
| corrections | 2 | 11: 8 reopens, 3 tasks deleted |
| cost per transition | 0.013 | 0.006 |

Corrections per transition rose five times over. The step does not
hold (R-11.4). The person restates `held_for` back to
`["claude-sonnet-5"]` with the note "Haiku says yes to requests that
are not for Colton. The judgment is real at this rung." The floor is
Sonnet. Both steps are in the transition log with their reasons.

The dollar saved on Haiku was not the point. The point is that the
house can now say, with numbers, which rung this seat's judgment
needs. The next question is not "which model" but "is there one more
rule that would make Haiku hold", and that is a frontier session's
question for another week.

### 13.11 A lean week: the levers

The person has four levers on the seat row, and none needs a deploy.

- `park`. The grant stays. The next firing reads parked, says so,
  and stops before it reads anything else. The cost is one short
  turn, until the person pauses the Routine too.
- `restate` cadence from one hour to six hours. The fixed wake cost
  falls six times.
- `restate` `held_for` down one rung, with a note, and the same
  model on the Routine. The next firing starts the cheaper model.
- Lower `budget_usd_per_week`. At the limit, the engine parks the
  seat on its own, and discover says when it resumes.

### 13.12 A substitute

Sonnet is unavailable for a day. The person sets the Routine's
model to `claude-haiku-4-5`, and the agent asks with `substitute:
true`, because the person set
`substitute_for` to `["claude-haiku-4-5"]` in week five.
`model-may-sit` passes on `substitute_for`. The substitute walks the
same tree. It reads the journal and cannot write it, by
`not-a-substitute`. Every transition it makes carries
`claude-haiku-4-5` in the actor, so the corrections of that day are
on record against the substitute, not the seat's floor.

### 13.13 A leaner month

The person merges `inbox-clerk` into `composer`. The composer's
scope becomes the fold of both. The inbox clerk seat closes. The
clerk's sitter leaves a letter for the composer's sitter, with the
sender rules it learned, and asks to sit in `composer`. One tap.

### 13.14 The law moves

A push retires `inbox_item.no` in favor of a `dismiss` with a
reason. At the next boot, the sweep writes
`[{"kind": "inbox_item", "actions": ["no"]}]` into `stale`. The seat
still serves research and yes. Discover says stale. The firing
says it first. The person restates the scope with `dismiss`, and
the stale list clears.

**What the person tunes, and where.** The tree is a deploy: a new
branch is a new door, and it is law. The seat is a row: which model
walks, how often, with what budget, and the charter's words, all
with no deploy. That is the right split: law in code, fluid things
in rows.

## 14. The composer: a frontier seat

The clerk walks a tree. The composer holds an objective. That is the
difference between an economy seat and a frontier seat, and the
house already has the composer's history to draw on: the standing
agent (waymark-53u), the loop redesign of 2026-08-27 (bd memory
`no-floor-advance-one-arrival`), the iterate loop (waymark-9j2), and
the 74-entry grant that drifted (waymark-ycp). Its runs were retired
in 2026-09. This seat is how they come back.

**Why its floor is the frontier.** Four reasons, each one a thing a
tree cannot hold.

1. The objective is a specification with a bright line, not a set of
   doors: "advance a specific arrival as far as the evidence honestly
   supports, no further". Enrich, then link, then compose. Compose an
   outcome only when the goal is larger than any single evidence row.
   Where an arrival stops on that spectrum is a judgment.
2. A quiet sitting is lawful. The floor was retracted because a
   vague floor was gamed. Knowing when not to act is the caution the
   essay calls wisdom, and it is the one thing an economy model in a
   walk never has to decide.
3. It writes the house's knowledge and its own memory: insights,
   hypotheses, its journal, and letters. This is the seat where the
   voice matters, and where the substitute bar earns its keep.
4. It converses. The iterate loop is a thread with a person: the
   person says "the plan is wrong, the outcome is right", and the
   composer reworks the pieces in place over turns.

**The seat.**

```json
{
  "name": "composer",
  "charter": "You read what arrived in the house since your last sitting and advance each arrival as far as the evidence honestly supports. Enrich a bare task with an insight that cites its source and names the concrete next step; do not change the task. Link it to what it belongs with. Compose an outcome only when the goal is larger than any single row, and never one that wraps a single task. When a person iterates an outcome, rework its pieces in place; do not stage a twin. Only people mark things done. A quiet sitting is lawful; do not pad. Write one journal entry per sitting that did work, and leave a letter when you learn a rule the next sitter needs.",
  "scope": [
    {"kind": "feed", "actions": [], "filter": {"preview_as": "composer"}},
    {"kind": "task", "actions": []},
    {"kind": "remark", "actions": []},
    {"kind": "person", "actions": ["restate", "still_with_us"]},
    {"kind": "thread", "actions": []},
    {"kind": "outcome", "actions": ["create", "rework"]},
    {"kind": "insight", "actions": ["create"]},
    {"kind": "hypothesis", "actions": ["create", "restate", "still_stands", "dismiss"]},
    {"kind": "email.read", "actions": []},
    {"kind": "telegram.read", "actions": []},
    {"kind": "messages.read", "actions": []}
  ],
  "substitute_drop": [
    {"kind": "outcome", "actions": ["create", "rework"]},
    {"kind": "hypothesis", "actions": ["create", "restate", "still_stands", "dismiss"]}
  ],
  "held_for": ["claude-fable-5-1"],
  "substitute_for": ["claude-opus-5"],
  "standing_ttl_seconds": 604800,
  "cadence_seconds": 3600,
  "budget_usd_per_week": 40.00,
  "sitting_budget_tokens": 200000
}
```

The charter is 650 characters. Two sentences from the first draft
are gone, by the residual test: "only people decide" on tasks is the
scope, which gives `task` no actions; "do not offer a piece that
marks something done" is a guard on the outcome kind that already
refuses it, and its reason string says so. The rest is judgment.

Eleven scope entries, one per kind, where the drifted grant had 74.
The scope is the ruling of 2026-08-27 written as law: reads on the
evidence kinds, writes only on the knowledge kinds and the outcome.
The substitute, on Opus, keeps enrichment and loses composition and
the belief doors: it can annotate, and it cannot judge in the seat's
name.

**The fuel story is already on record.** At a cadence of one wake
every fifteen minutes with a floor of one outcome per sitting, the
fleet padded: wrapper outcomes, twins, the banned piece. That was a
damper problem. On this seat the cadence is a field, the floor is
gone by charter, the budget is a wall, and the sitting ceiling is
passed to the harness. All four are on the row, and none is a
deploy.

**The ladder for a frontier seat.** The composer's first step down
is the substitute: Opus on enrichment only, when Fable's fuel is
short. Its second is a walk that does not exist yet: if the ledger
shows that most of the composer's transitions are enrichments of the
same shape, the enrichment is a tree, and a second seat on Sonnet
can walk it. The composer keeps composition. The ledger tells the
person whether that split is worth a deploy.

**What to measure in a trial week.**

| number | what it says |
|---|---|
| outcomes accepted, declined, iterated, expired | is the composition worth its cost |
| enrichments written, and whether the task was actionable after | is the floor of the spectrum working |
| refusals per sitting | is the law spoken late anywhere on this seat |
| quiet sittings as a share of all sittings | is the seat padding, or is it waiting well |
| dollars per accepted outcome | the price of one thing you wanted |
| letters left, and whether the next sitter read them | is the memory a memory |

**Economy and frontier, side by side.**

| | the clerk | the composer |
|---|---|---|
| what holds the decision | the kind's machine | the charter and the model |
| a turn | one row, one door | the arrivals since the last snapshot |
| memory writes | none | insight, hypothesis, journal, letter |
| substitute | Haiku, same walk | Opus, enrichment only |
| a quiet sitting | impossible; the queue is empty or it is not | lawful, and the point |
| budget | four dollars | forty dollars |
| the floor | Sonnet, found in week five | the frontier, by the four reasons |
| what a person tunes | which model, how often | the charter's words, and the outcomes it iterates |

## 15. Acceptance

A test namespace `waymark10.seat-test` must prove each requirement
above. The cases:

1. A seat whose scope names a missing action is refused at `create`
   and at `restate`, with the entry named. (R-4.7)
2. A sitter's `restate` on its own seat is refused. (R-4.8)
3. A seat ask mints a grant with `seat` set and no scope. An ask with
   both `seat` and `scope` is refused. (R-5.1, R-5.5)
4. A request under a seat grant sees exactly the seat's scope. After
   a `restate`, the next request sees the new scope with no new
   grant. (R-5.2)
5. A substitute grant does not see the drop entries. A substitute's
   write to `self`, `journal`, or `letter` is refused; its read is
   served. (R-5.2, R-8)
6. `park` makes the sitter's request see nothing. `unpark` restores
   it. No grant moved. (R-4.4)
7. `merge` writes the fold onto `into`, closes the source, and the
   source's sitter sees nothing. The fold has one entry per kind.
   (R-6)
8. A boot with a retired action marks the seat stale with the entry
   named. The sitter sees the surviving entries. Discover carries
   `stale`. A `restate` that drops the entry clears it. (R-7)
9. A second full sitter on one seat is refused at `approve`. A
   substitute is not. (R-5.8)
10. A seat ask can request up to the seat's ceiling. A scope ask is
    capped at 24 hours. (R-5.6)
11. A seat held for one model refuses a seat ask from a session that
    declares another, and names the list. A renew that changes the
    model to one not in the list makes the next request see
    nothing. (R-5.7, R-5.2)
12. A transition written under a seat grant carries the session's
    model in its actor. (R-9.5)
13. A `restate` whose `held_for` names a retired model is refused.
    (R-4.7)
14. A `close` computes `cost_usd` from the model's prices and writes
    the prices used. A `reprice` afterwards does not change it.
    (R-10.4)
15. A seat whose closed sittings of the last seven days reach its
    budget serves nothing. Discover carries spent, limit, and
    resumes_at. A sitting closed eight days ago does not count.
    (R-5.2, R-7.4)
16. A sitting left open past two cadences is marked abandoned by the
    boot sweep. (R-7.6)
17. On a walk seat, a `queued` row's envelope offers only research,
    a `researched` row's offers only yes and no, and a leaf offers
    none to the sitter. A `yes` births exactly one task and stamps
    it. (R-12.7, section 13.8)
18. While a sitting is open, a committed transition under its grant
    adds one to `transitions`, and a 409 under its grant adds one to
    `refusals`. A transition under another grant adds nothing. The
    counts are frozen at `close`. (R-10.6)
19. A `restate` that changes `held_for` with no `note` is refused. One
    with a note is served, and the transition log holds the note.
    (R-4.7, R-11.2)
20. A `create` or `restate` whose charter is longer than 1200
    characters is refused. (R-4.2)
21. The corrections query returns a person's `reopen` on a row whose
    last transition was the seat's sitter's `no`, and does not return
    a person's transition on a row the sitter never moved. (R-11.3)
22. A sitter's `create`, `park`, or `retire` on a seat is refused by
    `a-person`. A person's is served. (R-4.7)
23. The ledger route returns the six answers for a window, and
    discover names the route. (R-11.3a)

The conformance suite must invoke every new door. `make check-queue`
must pass. The `approval_request` and `grant` fingerprints move,
because both schemas gain fields; the pinned hash in
`waymark10.decision-sugar-test` must be updated with the change.

## 16. Decisions on record

Each decision, its alternative, and the reason. The reversed drafts
stay here, because a record that is rewritten is a record nobody
trusts.

- **The seat is the bill, and waymark lowers it.** The first three
  drafts (2026-09-16) were written from the essay's seat downward:
  an office, then its fields. The owner's statement the same evening
  put cost first: seats exist because fuel is finite, and waymark
  exists to make a cheaper model adequate by speaking the law at the
  door. Section 1 is the result, and each field in section 4 was
  kept only if it lowers the bill or measures it.
- **The charter is the residual.** The first drafts had `must` and
  `never` lists beside the charter. Each sentence a model pre-loads
  is fuel, and a rule in prose is a fence not yet written. One field,
  capped, holds the judgment and nothing else.
- **Refusals are waymark's backlog.** A 409 served to a sitter is
  fuel spent on law the model did not know. The engine counts them
  on the sitting, because the harness cannot see them and the person
  cannot fix what nobody counts.
- **A seat is a resource, not a declaration in code.** The first
  draft (2026-09-16, morning) chose a declaration, because the
  declaration gate fails on the push that retires an action. The
  owner ruled the same day that a seat must change with no deploy,
  because the essay's seats are fluid. The gate's job moved to the
  boot sweep (section 7).
- **Fuel is a ledger in the house.** The second draft punted tokens
  as "outside the house". The owner ruled the same day that the cost
  of models goes up and fuel is a high priority. The sitting kind,
  the prices on the model, and the enforced budget are the result.
- **The grant is a pointer, not a snapshot.** A snapshot is a copy,
  and the copy is the failure on record. The human verdict for a
  widening is the person's `restate` or `merge`, judged by
  `not-a-sitter`. The approver of a seat ask approves an office.
- **A stale seat degrades. It does not die.** A dead leash was the
  disaster twice.
- **Park does not revoke.** Revoke is one-way, and an unpark would
  cost a tap.
- **Merge does not mint.** The person who merges is present, and each
  moved sitter costs one tap in the same sitting. A mint from the
  merge handler would be a second post-commit effect at the wire
  boundary while waymark-442.14 is open on the first.
- **One full sitter per seat.** "Who sits here" must be a one-row
  answer. Substitutes are the named exception.
- **The `role` kind stays.** A role is a name a member holds. A seat
  is an office with authority.
- **The model is a claim.** The engine cannot see the model. The
  harness declares it, and the check catches the harness bug the
  essay describes.
- **The budget window is seven days from now,** not a calendar week.
  A declared window is a follow-up if the fixed one is wrong.
- **The driver is the harness's scheduler, not a script of ours.**
  The fourth draft had a shell loop that started one model turn per
  row. The owner ruled (2026-09-16, late) to use the native things: a
  Claude Routine, a scheduled Jules session. The seat row holds what
  a schedule needs, the prompt is a pointer to the seat so the
  charter is never copied, and the one-row-per-session form comes
  through the scheduler's API later. The cost is a shared context
  across the rows of one firing, capped by `rows_per_firing`.
- **A step down is judged by corrections, not by cost.** Cost always
  falls on a step down. The only question is whether the outcomes
  held, and a correction is the one record of an outcome that did
  not.

## 17. Recorded punts

- A cross-check of the model claim against the MCP client name. It
  verifies the client, not the model.
- A composed seat page that answers the six questions of R-11.3 on
  one screen, with the ladder's steps beside them. The queries exist;
  the page is a surface declaration away, as the member page was.
- Trust that accrues by rule, such as a longer leash after N clean
  sittings. The person sets the ceiling by hand.
- A step down the engine proposes on its own, when refusals are zero
  and corrections are flat for N sittings. The numbers are there.
  The person pulls the lever, because the essay's caution is that a
  fence nobody signed is a fence nobody trusts.
- A seat that a person creates from a declared template. The
  substitute drop-list is already a narrowing of a scope, so the path
  is open.
- The 30-minute default on an anchorless scope ask (waymark-h6y) is
  unchanged. A seat ask defaults its leash to the seat's ceiling.
- Research as an engine step: the research handler could fetch the
  message through the Gate proxy under the sitter's grant, so the
  model never holds the power. `invoke-for` exists in
  `gate_proxy.clj`; a handler that reaches it is a new seam, and a
  follow-up.
- An engine effect that updates the schedule when a seat's `name`,
  `cadence_seconds`, or `held_for` changes, through the scheduler's
  API. Until then the `restate` consequence tells the person to do
  it, and a firing on the wrong model stops at R-12.1.
- The source fires the schedule with one row id, so one session
  walks one row. The scheduler's API accepts a text with the firing.
  This is the "trigger via API" path, and it changes the source, not
  the seat.
- A refusal log with the door, the guard, and the sentence, beyond
  the count. The count is enough to find the seat. The log is what
  the fence census (leg 2) reads to find the guard.

## 18. Effort

**Medium.** One new file, `seats.clj`, with three kinds: the seat
(six actions, eight guards), the model (three actions, one guard),
and the sitting (three actions, one handler, two counters). One
guard on three own-surface doors. Two optional fields on `grant` and
two on `approval_request`. One field on the session and one on the
principal, accepted at two auth doors and the MCP initialize. The
router's seat resolve gains one row load and one sum, and the
router's commit and refusal paths each gain one counter update on
the open sitting. `boot-revise!` gains two steps. The migration adds
three tables and four nullable columns. The scope schema, the four
scope guards, `merge-scope`, `no-self-dealing`, and `one-spelling`
are reused as they are. The delta from the third draft is two
counters at close, one guard on one door, and the cut of two fields.
Two things the earlier drafts did not count: the ledger route with
its corrections window is a new query; and the sitting's close is a
harness hook, not engine code. There is no driver script: the
scheduler is the harness's, and the tick script stays as it is.
