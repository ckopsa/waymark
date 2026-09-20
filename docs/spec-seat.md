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
| `mode` | enum `fired`, `interactive`, default `fired` | who opens a sitting here. A schedule, a person or a wake fires a fired seat. A person sits in an interactive seat, and nothing fires it. R-10.8. |
| `budget_usd_per_week` | decimal | the seat's fuel for seven days |
| `sitting_budget_tokens` | int, 20000 or more | one sitting's ceiling, passed to the harness |
| `sitting_idle_seconds` | int, 60 to 86400, default 3600 | how long an open interactive sitting can wait with no new tally before the sweep ends it. R-7.6. |
| `walk` | kind name, optional | the queue this seat walks, one row at a time, in the order of its default sort. R-12.9. |
| `rows_per_firing` | int, default 20 | the most rows one firing moves to a leaf. The walk's cap. |
| `stale` | list of scope entries | written by the sweep. A person never writes it. |
| `halt` | map, optional | `{reason, since, detail}`, written by the router at a wall and cleared when it lifts. R-7.7. |
| `schedule` | schedule ref | the means by which a sitting is created for this seat. Engine-written. R-12.0. |
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
| `a-person` | create, restate, park, unpark, merge, retire | the principal's type is human, or the principal is a delegate: an agent the identity gate marked `acts-for`, which is a person signed in through a tool (`spec-connector-door.md` § 3). A bare agent is refused. The precedent is the `actor_type` check on the member row in `members.clj`. |
| `not-a-sitter` | create, restate, merge | the actor holds no live grant that cites this seat, or the `into` seat |
| the four scope guards | create, restate | the scope names only kinds, actions, filter fields, and non-private kinds the registry declares |
| `drop-inside-scope` | create, restate | each `substitute_drop` entry is inside `scope` |
| `ttl-within-standing` | create, restate | `standing_ttl_seconds` is not more than `reentry-standing-ttl-seconds` |
| `held-for-active-models` | create, restate | each model in the two lists is active |
| `walk-names-a-kind-in-scope` | create, restate | `walk` names a kind the scope admits, and the kind declares a `:default-filters` over one of its own fields |
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

Steps 1 to 3 are walls. A wall is hard: the grant scopes to nothing,
and the seat writes `halt` and raises the alert of R-7.7.

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

An interactive sitting has its own clock. The sweep must close an
open interactive sitting that has a tally and whose `tallied_at` is
older than the seat's `sitting_idle_seconds`, with the counts of the
last tally and the note `Closed by the sweep after {n} seconds
idle.` That sitting moves to `closed`, not to `abandoned`, because
the counts are real (R-12.25).

An open interactive sitting that has no tally, and that has waited
longer than `sitting_idle_seconds` after `started_at`, is
`abandoned`, as the rule above abandons a stale fired sitting. The
sweep records the absence of a bill, and not a bill of zero.

**R-7.7** A hard stop must raise an alert. The owner's ruling,
2026-09-17: the three walls of R-5.2 (the seat not active, the model
outside its list, the budget reached) stay hard, and each must reach
a person. The seat must carry a field `halt`, `{reason, since,
detail}`, that the router writes through a concealed transition
`mark_halted` the first time a request under the seat's grant meets
a wall, and clears through `clear_halt` the first time a request
passes again. Both are logged with the system actor. The feed must
carry a seat's `mark_halted` to the seat's approver as an item that
wants a tap, the way it carries an ask. `doors.ask.seat` must carry
`halt`. A firing must say the halt first, before any other output.
A halt is not a state: `park` and `unpark` are the person's, and a
halted seat is still `active`, so the wall lifts on its own when
the condition clears, and the alert says so when it does.

The line is a record of a wall. It is not a lock. A fired seat makes
no request of its own, so a line that only a request could lift stops
the seat until a person starts the Routine by hand. Three doors lift
the line, and each lift is logged:

- A `restate` that changes the input of a wall lifts the line of that
  wall, in the same transaction. A new `budget_usd_per_week` lifts
  `budget_reached`. A new `held_for` or `substitute_for` lifts
  `model_not_held`. The handler compares the input with the row; it
  does not sum the week. The next request judges the wall again, and
  writes the line again if the wall holds.
- `unpark` lifts `seat_not_active`. The state of the seat is the input
  of that wall, and `unpark` is the hand that moves it.
- The `fire` door judges the week's fuel again, with the sum of R-5.2
  step 3. The window rolls with no hand, and the door must see it. If
  the sum is less than the budget, the fire goes out, and the engine
  lifts the line through `clear_halt` after the commit.

The `fire` door does not judge `model_not_held` or `seat_not_active`.
The first needs the model that the session declares, and the second is
the choice of a person. The door refuses, and the sentence says which
door lifts the line.

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
cannot rewrite a cookie, so it is not a declaration door for a
session that binds through the agent door; that harness declares
at bind and at renew only (R-12.7). A keyed session declares
nothing itself: its claim is the schedule's `model` (R-12.15).

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
| `harness_session` | string | the harness's id for the session that sat. Written at `create` from `waymark_sit`, or at `close` from the hook's report (R-12.17). |
| `transitions` | int | committed transitions under the grant while the sitting was open. The engine counts. |
| `refusals` | int | 409s served under the grant while the sitting was open. The engine counts. |
| `mode` | enum `fired`, `interactive` | the seat's mode, copied at birth. Engine-written. R-10.8. |
| `person` | string, optional, up to 200 | the member the delegate acts for, when the sitter is a delegate. Engine-written. |
| `tallied_at` | instant, optional | when the harness last tallied this open sitting. Engine-written. R-12.25. |
| `cost_usd` | decimal | written at close, and again at each tally of an open sitting (R-12.27) |
| `prices` | map | the four prices used at close, or at the last tally |
| `note` | string | one sentence on what the sitting did |

**R-10.3** A sitting must be own-surface for its member, with the
actions `create`, `close`, and `abandon`. The session opens it
before it reads the queue, and the harness's hook closes it when the
session ends (R-12.5). An interactive sitting has a fourth action,
`tally`, which the harness's hook opens on each turn (R-12.25).

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
to `refusals` on each 409 it serves. The rule holds at every door
the engine serves, the HTTP door and the MCP door alike: one 409 at
either door counts one refusal. A request with no open sitting
counts nothing. The harness does not report these. No refusal log
exists today; this counter is the first record of a refusal as
fuel.

**R-10.6a** The engine must count the bytes the MCP door serves. The
sitting carries `served`: a map from the tool name to `calls` and
`bytes`. `calls` is how many times the door answered that tool.
`bytes` is the UTF-8 length of the text of those answers. The map
starts empty.

The door adds one call and its bytes on each `tools/call` it answers.
It counts an allowance and a refusal in the same way, because the
model reads both. It finds the sitting as R-10.6 does: one lookup by
`grant` and state `open`, under the index on `grant`. A call from a
session with no open sitting counts nothing. The count is a
maintenance write, as the two counters above are: the document only,
no transition, one write for each call. The `close` freezes `served`.

The bytes of `waymark_power` count as the bytes of any other tool.
Gate's answer goes through the door word for word, so the door sees
its size.

The engine counts bytes and not tokens. It does not run the model, so
it cannot count tokens truthfully. A reader divides by four. No price
is attached to `served`: the bytes are the engine's own record, and
the cost is the harness's.

**R-10.7** The sitting collection must be filterable by `seat`,
`model`, and `started_at` after, so these are each one query: fuel
per seat per week against its budget; fuel per model; refusals per
seat per week; cost per transition; the fixed cost of a seat, as the
sittings that wrote nothing.

**R-10.8** The mode is the seat's, not the principal's. The owner's
reading of 2026-09-17: the seat says who sits in it. A seat's `mode`
is `fired` or `interactive`, and the default is `fired` (R-4.2). A
fired seat keeps the behavior of section 12: a cadence, a person's
fire, and a wake each start a sitting. An interactive seat has no
cadence, and nothing fires it. The engine mints no schedule row for
it, the wake consumer passes it by, and its `fire` door is refused
(R-12.20).

A delegate opens an interactive seat's sitting: an agent the identity
gate marked `acts-for`, which is a person signed in through a tool.
A Routine's run is a bare agent, and `waymark_sit` refuses it at an
interactive seat: ``The seat `{name}` is an interactive seat. A
person sits here.`` A fired seat admits both, as it does today.

The sitting takes its `mode` from the seat at birth, and the engine
writes it. The sitting also holds `person`, the member id the
delegate acts for. `waymark_sit` answers the mode, so the harness's
hook learns it from the sit's answer (R-12.26).

The sitter is the seat's member in both modes, so the seat's grant,
the walk, the budget and the ceiling apply the same way. The ladder's
audit chair is therefore a second seat, with the same charter and the
same scope, in the interactive mode, and the ledger compares a seat
with a seat. The ledger reports the two modes in two columns, and a
step-down judgment (R-11.4) compares a fired sitting with a fired
sitting.

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
| which tool served the bytes | sum of `served` by tool over closed sittings |

**R-11.3a** The seven answers must be one call. `GET
/api/seats/{id}/ledger?since=` must return them for the window, and
`waymark_discover` must name the route under `doors.ask.seat`. The
corrections answer is a window over the transition log (each row's
previous transition, by `resource_id` and `id`), which no query
serves today; it is new, and it is the one query the ladder cannot
do without.

**R-11.4** The audit is the truth. The transition log holds every
act a sitter took, with the model in the actor, and the person reads
it. The counters of R-10.6 and the answers of R-11.3 are data beside
the audit, not verdicts: they point the person at the sittings to
read, and they prove nothing on their own. A step down holds when
the person, reading the audit of the sittings that followed it,
says it holds. The advice of this document is to read five. A step
down that does not hold is reversed by one `restate`, with a note.

The owner's ruling, 2026-09-17: so long as what was done is audited,
the refusal and correction counts need not mean anything by
themselves. They are kept as data, used where they show a signal,
and dropped when they do not. Nothing in this document may make a
decision from a count alone.

**R-11.5** A refusal the audit confirms is a waymark defect. A
refused door is a rule the model paid to learn, and some refusals
are the lazy-loading contract working as designed: the model hit
the law and then followed its reason. The count per seat per week
says where to read; the audit says which refusals repeated on one
door, or were not followed. Those are the backlog for the fence
census (leg 2, waymark-fp62.2) and for priming on demand (leg 3,
waymark-fp62.3). A refusal that repeats has one of three fixes: the reason string says what to do instead, a filter
hides the row the door does not apply to, or the door is absent
from the envelope in that state. A line in the charter is not a
fix.

**R-11.6** A correction that repeats, and that the audit shows was
a reversal of the sitter's verdict rather than a person's change of
mind, is a fence not yet written. The fix is law: a guard, a filter, a door, or a source that drops the
row before the model sees it. After the fix, the charter loses the
sentence that covered it (R-4.10). The frontier model's work in the
house is this: it turns a repeated correction into a rule once, and
the economy model obeys the rule at the price of a door.

**R-11.7** The floor is reached when the next step down does not
hold. The seat stays one rung above it. A seat whose floor is the
frontier is not a failure; it is a seat whose judgment is real.

## 12. Requirements: the schedule

**R-12.0** The seat is the only thing a person manages. The owner's
ruling, 2026-09-17: remove the Routine as a thing a person edits,
and give the seat a link to a resource that represents the means by
which a sitting is created. That resource is a framework kind
`schedule`, one row per seat, owned by the engine and mirrored out
to the harness's own scheduler: a Claude Routine, a scheduled Jules
session, or a cron. The precedent is the calendar: the event kind is
written to Google through a mirror adapter, and read back to catch
drift. Nobody edits the Routine by hand. The house does not write a
loop script.

**R-12.1** A `schedule` must have these fields.

| field | type | meaning |
|---|---|---|
| `seat` | seat ref | the seat this schedule fires |
| `provider` | enum claude_routine, jules, cron | which scheduler holds the external copy |
| `model` | model ref | the model the firing starts. Defaults to the first of the seat's `held_for`; a person can restate it, for a substitute day. |
| `external_id` | string | the provider's id for the copy. Engine-written. |
| `pushed_at` | instant | when the adapter last wrote the copy |
| `seen_at` | instant | when the adapter last read the copy back |
| `drift` | string, optional | what the read-back found that the row does not say. Engine-written. |
| `fire_url` | string, optional | the Routine's fire endpoint. A person writes it (R-12.18). |
| `fire_token` | string, secret, optional | the token a fire carries. A person writes it. The engine never answers it. |
| `last_fired_at` | instant, optional | when the engine last fired the Routine. Engine-written. |
| `last_run_url` | string, optional | the provider's page for the last run. Engine-written. |
| `wake_pending` | boolean, optional | a match waits for the damper to lift (R-12.22). Engine-written. |
| `wake_fired_at` | instant, optional | when the engine last fired the seat for a matching transition (R-12.22). Engine-written. |

States: `pending` (no copy yet), `live`, `paused`, `broken` (the
adapter could not reach the provider; the note says why). The seat
row must carry `schedule`, a schedule ref, engine-written.

**R-12.2** The engine must create the schedule row when a seat is
created, and the adapter must then create the provider's copy from
the seat and the schedule: the seat's `name` as the copy's name, the
seat's `cadence_seconds` as its cron, the schedule's `model` as its
model, and the fixed prompt of R-12.3. A `restate` of the seat's
`name` or `cadence_seconds`, or of the schedule's `model`, must push
the copy again. `park` must pause the copy; `unpark` must resume it;
`retire` and `merge` must delete it. The push is a post-commit
effect at the wire boundary, the same seam as `approval-effects!`,
and the same open question (waymark-442.14) applies to it.

**R-12.3** The copy's prompt must be a pointer and a walk rule, not
a copy of the charter. The prompt for a seat is this, with the
seat's name in it, and it never changes after the copy is made:

> You sit in the seat `{name}`. Read the seat row with `waymark_get`
> and do what its charter says. Take only the doors the envelope
> offers. When the seat says halted or parked, say why and stop.

The charter stays on the row, so a `restate` changes what the next
firing does with no push at all. The three things the copy holds
beside the prompt (name, cadence, model) are the mirror's, and the
mirror is what keeps a copy honest: the adapter reads the copy back
on a cadence and writes any difference into `drift`, which the boot
sweep and `doors.ask.seat` report.

The Claude Routine provider is the exception, and R-12.18 is its
rule. Its API fires a Routine and does nothing else. There is no
copy for the adapter to make, and none to read back, so a linked row
gets no `drift`. The link is the by-hand path: a person makes the
Routine, writes this prompt into it, and gives the row the fire URL
and the token.

**R-12.4** Each firing is one sitting. A firing must read
`doors.ask.seat` first. If the seat carries `halt`, or is parked,
the session must say the reason and stop before it reads anything
else. The reason is the sitting's `note`.

**R-12.5** The session must open a sitting before it reads the
queue, and the harness must close it with the exact token counts
when the session ends. The session cannot count its own tokens, so
the close is a hook of the harness, not an act of the model. The
hook is Claude Code's Stop hook. It reads the transcript's usage and
posts it to the close door. R-12.17 gives the rule.

**R-12.6** `sitting_budget_tokens` is the copy's ceiling, through
whatever knob the provider has. If it has none, the walk rule caps
the rows per firing (`rows_per_firing`, R-12.9), and the sitting's
`note` says when the cap was hit.

**R-12.7** The leash keeper (`scripts/standing-agent-tick.sh`) stays
as it is: it renews the session, files the extend-ask as
`{grant_id, task, expires_at}` with no scope when the grant cites a
seat, and writes the cookie the firing's session uses. It must
declare the schedule's `model` at bind and at renew (R-9.4). It
starts no model, and it does not need to.

**R-12.8** The schedule's `model` is the declaration. A firing whose
session declares a model outside the seat's list meets the wall of
R-5.2 and the alert of R-7.7. That is the harness bug the essay
describes, caught at the door, and the mirror's read-back is what
catches the copy's model drifting before a firing does. A linked
schedule (R-12.18) is not pushed, so its copy keeps the model of the
link and drifts when the seat steps down. For a linked schedule the
seat's first `held_for` is the declaration, and the person keeps the
Routine's model equal to it by hand; the engine cannot read it back.

**R-12.9** When the seat has `walk`, the firing walks. The session
reads the queue (the kind's collection under its default filter),
and for each row takes the one door the envelope offers, then the
next door, until the row is at a leaf. Then the next row. It stops
when the queue is empty or when it has moved `rows_per_firing` rows.
`rows_per_firing` is a field on the seat, default 20. One sitting
covers the firing.

A provider's firing is one session, not one turn per row, so the
rows of one firing share one context. Each turn reads the whole
context again; the twentieth row costs more than the first, and its
decision is made with nineteen other rows in view. The cap bounds
both. The one-row-per-session form comes through the fire door
(R-12.21). A fire carries the row's id as its text, and that firing
walks the one row. The engine fires the seat, so no source needs
fire code. The trial week measures cost and corrections by row
position, and that decides whether the cap is enough.

**R-12.10** The prompt must give the model nothing beyond the
pointer, the walk rule, and the engine's own answers: the seat row,
the envelope, the discover document, the schema, the refusal. No
rules file, no document from `docs/`, no law ahead of time. When a
firing fails for want of a rule, the fix is in the engine (R-11.5),
never a line in the prompt. This is the lazy-loading contract.
Without it the ladder does not descend, because the prompt grows to
cover what the engine should say.

**R-12.11** The adapter's credential is a power. A provider's token
is held the way a Gate power's reach is held: by the engine, named
in the capability registry as `schedule.write` for that provider,
never on a grant a sitter can wear. A deployment with no token for a
provider serves the schedule kind with that provider `broken` and
its note saying so, which is a boot that says so rather than one
that fails.

The fire token of R-12.18 is not this credential. A person writes it
on one schedule row, and it opens one Routine. The engine holds it
as it holds `sitter_key`: written by one door, never answered, never
filtered on, and never in a transition's recorded inputs.

### 12.1 The keyed session

The owner's ruling of 2026-09-17: the Routine that drives a seat is
a Claude Routine with the engine's own connector attached, and the
person gives it a key in its instructions. The key, with the
connector's own credential, makes that session the seat's sitter.

The problem it solves. A person signed in through the connector is
one delegate per tool and person (`spec-connector-door.md` § 3).
Every session that person's tool opens, a Routine's or a chat's,
arrives with the same bearer and resolves to the same delegate. A
credential cannot tell the clerk's firing from the person's chat.
A key the person pastes into one Routine can.

**R-12.12** A seat must have a field `sitter_key`, secret, written
only by two doors: `offer_key`, which takes the key from the person
and stores it, and `revoke_key`, which clears it. Both doors are the
person's, guarded by `a-person`. A create or restate that carries
the field is refused. The person mints the key by machine, 128 bits,
and pastes it into the Routine's instructions. The engine never
answers a key. The precedent is the member's re-entry credential.

**R-12.13** The MCP door must keep a session. `initialize` answers
an `Mcp-Session-Id`, and the client sends it on every later call. A
call that names a session the engine does not know is answered 404,
which is the transport's own word for start again. A client that
sends no session id is served as before, stateless.

**R-12.14** The MCP surface must serve a fixed tool `waymark_sit`
that takes the key. It binds the calling session to the seat when
three things hold: the call carries a known session id, the caller
is a delegate, and an active seat holds this key. The call can also
carry `session`, the harness's own id for this session. The engine
then makes the sitting with `harness_session` set to that id, and
the report of R-12.17 pairs to this sitting by it. A failure of any
one is a refusal in a sentence, and the key's refusal is uniform:
no seat answers this key. A leaked key without a person's bearer
opens nothing.

**R-12.15** A bound session is the seat's sitter, not the person's
delegate. The sitter is a member row of its own, id `seat:{seat
id}`, actor type agent, acting for the person, provisioned at the
first sit. It wears a seat grant the engine mints at the first sit,
because the person handing over the key is the approval, with the
seat's `standing_ttl_seconds` as its leash. Its model claim is the
schedule's `model` (R-12.8). The bind opens the seat's sitting, or
reuses the one already open under the grant, because no leash keeper
stands behind a Routine's firing to open one. From the bind until
the session ends, every call resolves to the sitter: the router
counts transitions and refusals against that sitting, and R-5.2's
walls apply. The first firing on production (2026-09-17) showed the
gap: the sitter moved rows as itself, on the schedule's model, and
no sitting counted them. The person's other sessions carry no key and
stay the person's delegate, with the seat's levers.

**R-12.16** The bind is held in the engine's memory for the life of
the session, at most eight hours, and dies with a restart. A session
that loses its bind is told 404 and starts again: it initializes,
sits with the key once more, and continues. Nothing about the seat,
the sitter, or the grant is lost, because those are rows.

**R-12.17** The harness must close the sitting. The repository that
the Routine clones carries a Stop hook in its `.claude/settings.json`.
The Routine attaches the seat's place and not the code: a repository
that holds that hook, its settings and a short note, mirrored from
the branch `seat` of this repository. A Routine's firing is one
prompt, so the session raises one Stop event, at its end. The hook
reads the session's transcript. It sums the usage of each API
response, in the session and in each subagent beside it. It then
posts the four token counts and the turn count to
`POST /api/-/sittings/close`. It sends the seat's key in the header
`Waymark-Seat-Key`. The engine finds the seat by that key. The
engine then closes that seat's open sitting through the sitting's
own `close` door (R-10.4), so the handler reads the model's prices
and writes `cost_usd` at that moment.

The body carries `harness_session`, the harness's id for the
session, and the sitting keeps it as a field (R-10.2). The engine
pairs the report to the sitting by `harness_session` first. When no
open sitting of the seat carries that id, the engine closes the
seat's newest open sitting that carries no id. A sitting that
carries an id has a report of its own coming, so a report that
names a different run does not close it. When every open sitting
carries an id, the engine closes the newest.

The door gives four answers. It answers 200 with the closed sitting
and its `cost_usd`. It answers 404 with `No seat answers this key.`
when the key is wrong or absent; the refusal is uniform, as R-12.14
makes it. It answers 409 with ``The seat `name` has no open
sitting.`` when a second report comes in, or when nothing sat. It
answers 422 when the body is malformed.

The key is a header, and not a bearer. The identity layer reads a
bearer as an OIDC token, so a key in that place is refused before
the door sees it. A header is also what an environment's stored
credential can add: the proxy writes it after the request leaves the
container, so the key never enters the session.

The post is the first path. Some environments carry no variable and
no credential, and take the repository only. There the hook has no
door to post to, and it uses a second path: it holds the stop one
time. It gives the session the sitting's id, the four token counts
and the turn count. The session then closes its own sitting through
the connector, with `close` on the sitting, because the sitting is
own-surface for its member (R-10.3). The counts stay the hook's: the
session copies the numbers as they are written, and it does not
count again. The tokens of the closing call are not on the bill,
because the hook sums the transcript before the call (section 18).

The hook holds the stop one time only. The harness tells the hook
when it continues a session because a hook held its stop
(`stop_hook_active`), and the hook is then silent. The hook is also
silent when the transcript already shows a `close` on a sitting, and
when the transcript shows no sit. This path needs no domain, no
variable and no credential, because the traffic is the connector's,
which the session already holds.

The harness raises a second event. `SessionEnd` comes one time, when
the session ends. The repository's `.claude/settings.json` carries an
entry for it, which runs the same hook script with the argument
`end`. The hook posts the close there for an interactive sitting
(R-12.25). A fired sitting keeps the Stop close of this rule, because
its run ends with its one Stop event.

A sitting that gets no report is still the sweep's. The sweep
abandons it after two cadences, with no tokens (R-7.6). The counts
of transitions and refusals stand.

**R-12.28** The sit must answer the walk. When the seat names a
`walk`, the answer to `waymark_sit` carries `walk`: the seat's
charter, and the rows. The rows are the rows of that kind, under the
kind's own default filter and its own default sort, and there are not
more of them than `rows_per_firing`. The engine reads them as the
sitter, under the seat's grant, through the same route
`waymark_query` uses. A row that the grant does not admit is absent;
the sit does not refuse it (R-10.6). Each row carries its summary
projection and the doors its envelope offers. Each door carries the
name of its action and the input the sitter must give. A door with
`safety.confirm` also carries the sentence to echo back. The sitter
therefore does not call `waymark_discover`, `waymark_schema`,
`waymark_query` or `waymark_get` before its first `waymark_invoke`,
and the note in the answer tells it so. A seat that walks nothing
answers no `walk`, and its note points at the seat row. A seat at a
wall scopes to nothing, so its queue is concealed and the answer
carries no `walk`. The bytes of this answer count on the sitting,
under `waymark_sit`, as the bytes of every other tool count
(R-10.6a). `rows_per_firing` and the size of one summary bound the
answer; a seat whose answer is too large is a seat the person
restates.

**R-12.29** The sit must answer the bench for a code seat. When the
seat's walk is `change` or `ci_run`, and the first row of the walk
names a change, the answer to `waymark_sit` carries three more
things. `bench` is the worktree the engine made before it answered:
the repository, the branch, the base branch, the head commit, and how
many paths are different from the head because an earlier sitting left
them. The engine makes it with one call to the bench rig's `prepare`,
with its own hand and not with the seat's — `prepare` is on no
capability token, so no scope can name it and `waymark_power` refuses
it.

`bench` must also carry `tools`: a map from each bench power the seat's
scope names to the tool name the power door resolves it to, such as
`bench.read` to `bench__read`. A power the scope does not name is
absent. A power the server maps to more than one tool is absent,
because that power names no single tool. The seat must read from this
map the name it calls the bench with, and the seat's instructions must
name no spelling.

The branch is the change's head branch, or the repository policy's
branch pattern with the change's own id in place of the `*`; there is
one branch for each change, so a second sitting finds the work the
first one left. `orientation` is the path of the document the seat
reads first, from the same policy, and `docs/orientation.md` when the
policy names no other. The engine answers that path only when the file
is in the worktree. It reads the path once with the rig's `read`, with
its own hand. A refusal, a dark rig and a worktree the rig did not
make each mean the file is not there, and the answer is then one
sentence in place of the path: "This repository has no orientation
file. Submit means: " and the `submit_means` sentence. A path to a
document that is not there costs the seat one call and a refusal to
reason about; the sentence costs it nothing.
`submit_means` is one sentence made from the
policy: whether a push opens a pull request, who merges it, how many
lines one change may have, and how many rounds this change gets. The
model chooses none of these. If the rig does not answer, the sit
answers no `bench` and a `bench_note` sentence, and the walk still
rides: a sitting that cannot reach the bench can read its rows and say
so.

A refusal is an answer. The rig refuses a `prepare` with the refuser's
name, the command it would not run, and the reason. The sit must carry
that reason in `bench_note`: "The bench refused to open the worktree:
<command> — <reason>." The seat then stalls the change with the
reason, and a person reads the cause on the row. The sentence for a
bench that said nothing is for a rig that threw and for a rig that
answered nothing at all.

**R-12.30** A bench grant must be able to name less than the whole
rig. The bench has five powers: `bench.find`, `bench.read`,
`bench.edit`, `bench.pull` and `bench.feedback`. A seat's scope entry
for a bench power may carry a `filter`. The filter names `repo`, or
`path`, or both. `bench.pull` and `bench.feedback` name `repo` alone.
A path cannot narrow a whole checkout, and it cannot narrow a whole
branch. A comma in a `repo` value means "any of these repositories".
A `path` value is a comma list of
globs in the rig's deny grammar, where `*` matches any characters,
slashes included, `?` matches one character, and a glob also matches
the last part of the path alone. The engine judges the filter
when a person writes the scope, and refuses a field the server's
`powers` entry does not list in `constraints`. The power door judges
the filter again on every call. A call outside every entry refuses
with a 403 and reaches no rig. A call that names no path reaches the
rig with the filter's globs as `allow`. One power may carry more than
one filtered entry, and the door admits a call that any entry admits.
A seat's scope entry is a grant entry, so a seat narrows a bench power
with no rule of its own.

The engine must also name the office on each bench call. A call
through `waymark_power` from a bound session carries `seat` and
`sitting`, which are the ids the sit bound. A session with no bound
sitting carries neither. The rig holds no seat between calls, so the
engine names the office on every call. See
`docs/spec-mcp-servers.md` § 4 for the `constraints` a row must list
before a filter is legal at all.

**R-12.31** The sit must answer what the submit caused. This rule is
for a code seat, as R-12.29 is. A seat submits a change. The checks
then go red, or a reviewer asks for a change. The next sitting must
read both. The rig gathers both with its `feedback` tool.

The engine reads the change of the walk. A change has a submit behind
it when it names a `head_branch` and a `number`, or when its `rounds`
is one or more. A change the engine minted for a walk row (R-12.32)
names a head branch from its birth and has no pull request, so a head
branch alone is not a submit. The engine must then call the rig's `feedback` one time. The
engine calls with its own hand, and not with the seat's. The call
gives `repo`, `branch` and `log_bytes` 2048. The repository and the
branch are the worktree's, from the `prepare` of R-12.29. The engine
must not call `feedback` when the rig made no worktree. The engine
must not call `feedback` for a change with no head branch and no
round. A branch nobody pushed has no pull request and no pipeline.

The answer to `waymark_sit` then carries `feedback`. `feedback` has
three parts. `pull_request` is the pull request of the branch: its
number, its state and its url. `pull_request` is empty when the forge
has no pull request for the branch. `findings` is the rig's list. Each
finding carries `source`, `severity` and `message`. A finding also
carries `locations` when the rig named locations. `findings` holds not
more than 40 findings. The engine must keep the rig's own order. The
engine must add no finding of its own. `unavailable` is the rig's list
of the parts of the forge it could not read. A forge that is half dark
is a sentence the seat reads, and not a refusal.

A refusal, a dark rig and a rig that faults each mean no `feedback`
key at all. The sit still answers, as R-12.29 says for the bench.

The rig opens the pull request only when its entry for the repository
carries a land block. The engine sends that block at the enrolment,
from the repository policy row. The block names the policy's base as
the target. The block asks for a pull request when the policy's
`opens_pr` is true. The block asks for no rebase, because a seat may
work on a person's own pull request branch. The pull request block
carries `auto_merge` from the policy. The rig must turn auto-merge on
for the pull request when `auto_merge` is true. The forge then merges
the change when the checks are green. No person taps merge. A forge
that refuses auto-merge gives a finding in `feedback`. The pull
request stands.

**R-12.32** The sit must give a bench to a seat that walks a queue of
asks. This rule is for a code seat, as R-12.29 and R-12.31 are. A
person says what the house must build in a row of a queue: a `task` in
a task list. That row is the ask. An ask names no repository, and it
has no change. `submit` is a door on `change`. So the engine does
three things before it answers, and the source does one thing after.

A seat must be able to walk a queue whose lifecycle is data. The guard
`walk-names-a-kind-in-scope` asks that the walked kind declares a
default filter. It asked for a default filter over `state` before this
rule. A kind that mirrors an outside authority keeps its machine for
freshness, and it keeps its lifecycle in a field of its own. The guard
therefore accepts a default filter over any field. The `task` kind
declares a default filter `status=open`. Its collection opens on the
tasks that are open, and its default sort is the priority, which puts
the highest rank first.

First, the engine reads the repository from the seat. A seat's scope
carries one entry for each bench power, and each entry carries a
filter with a `repo` (R-12.30). When every bench entry names the same
one repository, that repository is the seat's. The seat has no
repository when an entry carries no filter, when two entries name two
repositories, or when one value names more than one repository. The
sit then gives no `bench`. It gives a `bench_note` that tells the
person to name one repository in the scope, and the rows still ride.
A seat whose scope names no bench power at all is not a code seat.
Its sit answers no `change`, no `bench` and no `bench_note`.

Second, the engine finds or mints one change row for the FIRST row of
the walk. The `change_id` is the walk kind, a colon, and the walk
row's own id. The engine writes the row with its own hand, because the
birth door of `change` is the mirror's. The row carries the seat's
repository, the walk row's title, the policy's branch pattern with the
walk row's id in place of the `*`, the policy's base, and the seat's
name as the author. The title is the walk row's own title, or its
summary line when that row carries no title. The row carries no number, because no pull request
is open yet. `change_id` is unique, so a second sitting on the same
ask finds the first sitting's row and mints no other. The bench of
R-12.29 then opens on that row, and the branch is that row's head
branch.

Third, the sit answers `change` beside `walk`. `change` is that change
row, read as the sitter through the route `waymark_get` takes. The
doors on it are therefore the doors the seat's scope opens, which are
`submit`, `stall` and `discard`. The walk rows stay the asks. A seat
that walks `change` or `ci_run` answers no `change` key, because its
walk rows are already the changes. The seat's scope must name `change`
with those three doors, and the walk kind with the doors its charter
uses.

A change the engine minted has a head branch from its birth, and it
has no pull request. The engine therefore asks the rig for no feedback
until that change has a round or a number (R-12.31).

A seat-born change writes its branch at birth, so a person who
restates the branch pattern does not reach it. The sit must mint that
branch again. It does this for a change whose `change_id` is not the
forge's, which has no number, which has spent no round, and which
stands at `open` or at `stuck`. The sit makes the branch from the
policy's pattern with the walk row's own id, as the mint does. When
that branch is not the branch on the row, the sit writes it through
the door `rebranch`, which is the mirror's and is hidden, and the
change stands at `open`. A change that has spent a round keeps its
branch, because the forge holds it. The sit does this before it opens
the bench, so the worktree of R-12.29 is made on the new branch.

The branch pattern must not shadow a branch. Git holds
`refs/heads/seat` and `refs/heads/seat/<id>` never at the same time.
So the text before the `*` must not be the name of a branch the
repository already has, or every `prepare` refuses. The help of the
`branch_pattern` field says this, because the enrolment cannot read
the branches.

Last, the source must adopt that row, and it must mint no second one.
The source reads a pull request. It asks for a change row by the pull
request's own id. When no row answers, the source looks for a row of
the same repository, on the same head branch, whose `change_id` is not
the forge's. A `change_id` the forge owns starts with `github:`. When
such a row is there, the source writes the pull request's identity
onto it: the `change_id`, the number and the url. The write goes
through the door `adopt`, which is the mirror's and is hidden. A row
at `submitted` is written by `adopt_submitted`, because one action
declares one `to` state. A row at `stuck`, at `merged` or at `closed`
is not adopted. `change_id` is unique, so that write lands whole or it
does not land at all. The facts and the state then follow through
`observe` and the state doors, as they do for every other row.

The change must keep the walk row it was born from. The mint writes
the field `born_from`. `born_from` has the same value as `change_id`
at the mint: the walk kind, a colon and the walk row's own id. The
adoption writes GitHub's identity over `change_id`, and it does not
touch `born_from`. So the row says which walk row it was built for
after the pull request is open.

The task is done when its pull request merges. The engine must
complete the task when a seat-born change moves to `merged`. The
engine reads `born_from`. The engine does this only when `born_from`
names a `task`. The engine invokes the `complete` door on that task,
with its own hand. The engine does this in the merge of the change.

The completion is best-effort. A task that is already done is not an
error. A task that is gone is not an error. A door that refuses is not
an error. The engine writes the refusal in its log, and the change
moves to `merged` all the same. GitHub merged the pull request, and
the row follows GitHub.

The seat must complete the task too. The instructions of the code seat
say: after the submit, invoke `complete` on the task row, then stop.
The two are one answer: the seat closes the task on the day, and the
merge closes a task the seat left open.

### 12.2 The fire door

The owner's ruling of 2026-09-17: a seat must be fired on demand and
on events, and not on a cadence alone. The Routines API is fire-only.
It has one endpoint, which starts a run. It has no endpoint that
makes a Routine, changes one, lists them, or reads one back. The
engine therefore cannot hold the copy of R-12.2 for this provider.
The person makes the Routine by hand, one time, and links it to the
schedule row. The engine fires it from then on. The fire URL holds
the Routine's id, which is not a secret. The token is a secret.

**R-12.18** The schedule row must hold the link. R-12.1 gives the
fields: `fire_url` and `fire_token`, which a person writes, and
`last_fired_at` and `last_run_url`, which the engine writes. The
engine shows `fire_url`. The engine never shows `fire_token`. It
holds that token as it holds the seat's `sitter_key`.

The schedule must have a door `link`, with the input `{fire_url,
token}`. Only a person or a delegate opens it. A `link` moves the row
to `live` and clears the note. A second `link` replaces the first.
The schedule must have a door `unlink`. It clears both fields, moves
the row to `broken`, and writes the note `No link: the Routine's
fire URL and token are not on this schedule.` The engine does not
make, change or read a Routine.

A linked row is a row a person manages. The adapter of R-12.2 must
therefore leave it alone. A push, a pause and a resume of a linked
row call no adapter and change no field. A delete still ends the row.

**R-12.19** The seat must have a door `fire`. The input has one
optional field, `text`, of at most 2000 characters. A person, a
delegate or the engine opens the door. The engine records the text
with the transition.

The fire goes out after the commit. A consumer of the transition log
hears the fire, reads the seat's schedule, and sends a POST to
`fire_url` with the token and the beta header. The push of R-12.2 is
the same seam. The consumer then writes `last_fired_at` and
`last_run_url` on the schedule row, through a door of the engine's
own hand. A fire is a real transition from active to active, and the
ledger counts it.

**R-12.20** The engine must refuse a fire with one sentence in these
cases. The seat is parked: `The seat is parked. Unpark it first.`
The seat is halted: the halt's own sentence. The schedule has no
link: `Link the Routine's fire URL and token to the schedule
first.` The seat is an interactive seat: `The seat is an interactive
seat. A person sits here; nothing fires it.` The caller is a bare agent, with no person behind it: the
refusal of R-4.7.

The provider's own answer comes after the commit, so it is a note on
the schedule row and not a refusal at the door. When the provider
answers 429, the engine moves the row to `broken`, with the note
`The Routine has no free run. Try again after {retry_after}.` The
next fire that goes out clears it. When the provider answers 400
paused, the engine moves the row to `paused`. When the provider
answers 401, the engine moves the row to `broken`, with the note
`The Routine refused the token.` When the provider answers 404, the
engine moves the row to `broken`, with the note `No Routine answers
the fire URL.`

A fire is fuel. The engine never fires a seat behind a wall. A halt
line whose wall no longer holds is not a wall: the door sums the
week's fuel again before it refuses, and lifts the line when the fire
goes out (R-7.7).

**R-12.21** When the fire's text names a row id of a kind in the
seat's scope, the session must walk that one row. The provider puts
the text into the session in a `routine-fire-payload` block. A
session reads that block only when its instructions tell it to, so
the Routine's instructions must get this line: `If a
routine-fire-payload block names a row id, walk that row and stop.`
A fire with no text walks the queue, as a cadence firing does.

**R-12.22** The seat must have a field `wake_on`. It is a list of
entries `{kind, actions}`, in the shape of a scope entry. A walk seat
with no `wake_on` behaves as one entry: the walk's kind, with the
action `create`. The engine computes that default when it reads the
seat, and it writes nothing.

Each entry is a subscription over the transition log. The engine
already has this: the subscription kind, one cursor for each
subscription, at-least-once delivery, and a fail or skip policy. The
receiver of a `wake_on` entry is not a URL. The receiver is the
seat's `fire` door. When a committed transition matches an entry, the
engine fires the seat, with the transition as the text: the kind, the
row id, the action, the from state and the to state. Example: the
entry `{task, ["complete"]}` fires the seat when a task moves from
open to complete. An entry's `filter` (R-12.24) applies to the row
that moved on a transition wake, and to the counted rows on a count
wake.

The damper has three parts. The engine does not fire while the seat
has an open sitting. The engine fires at most once in
`fire_interval_seconds`, a seat field with the default 300. A match
the damper stops sets `wake_pending` on the schedule row. The next
fire after the damper lifts names no row, so the session walks the
queue. A replay after a restart is harmless, because the
open-sitting check stops the second fire.

The inbox source has no fire code. The cadence stays for a seat with
no `wake_on`. Three things begin a sitting: the cadence, a person's
fire, and a transition the seat asked to be woken by.

**R-12.23** The provider's answer to a fire names a session id and a
run URL. That id is the run page's id. It is not the sitting's
`harness_session`, which the session reads in its own container
(R-12.14). The engine writes the run URL on the schedule row, so a
person can open the run. The exact pairing of a fire to its sitting
is a punt (section 18).

**R-12.24** A `wake_on` entry must be able to count. The entry gains
two optional fields, and the shape becomes `{kind, actions, filter,
at_least}`. `filter` is a map in the shape of a query's where clause
for that kind, which is the shape a scope entry's filter already has.
`at_least` is a whole number, 1 or more. An entry with no `at_least`
is a transition wake (R-12.22). An entry with `at_least` is a count
wake.

A count wake does not poll. The engine counts only when a committed
transition of that kind matches the entry's actions, and an entry
with no actions matches every action of the kind. The count is one
query over the kind's collection under the entry's `filter`. When the
entry carries no `filter`, the kind's default filter applies, which
is the walk's queue. When the count is at or above `at_least`, the
engine fires the seat.

A count fire's text names the kind and the count, and it names no
row: `{"kind": "inbox_item", "count": 23, "at_least": 20}`. The
session therefore walks the queue, as a cadence firing does.

The damper of R-12.22 applies with no change. The engine does not
fire while the seat has an open sitting, and it fires at most once in
`fire_interval_seconds`. A match the damper stops sets
`wake_pending`, and one fire goes out when the damper lifts.

The cadence stays. A seat with a count wake and a cadence fires when
the queue reaches the size, or when the interval passes, whichever
comes first. Example: the entry `{inbox_item, at_least: 20}` on a
seat with `rows_per_firing` 20 fires one full batch. Example: the
entry `{approval_request, filter: {state: "pending", kind_of:
"extend"}, at_least: 5}`.

The engine must refuse an `at_least` below 1, at `create` and at
`restate`: `at_least must be 1 or more.` A `filter` that names a
field the kind does not have is refused with the query's own
sentence.

### 12.3 The interactive sitting

The owner's reading of 2026-09-17: the sitting does not change.
Instead of a close at the end of the run, the session waits for the
next instruction, and the person says when to close.

An interactive sitting is a person's own session in the seat. The
person's connector sits with `waymark_sit`, the model walks the
queue, the person corrects it, and the session waits between the
turns. The sitting runs across many turns and many hours. It is the
audit chair of the ladder (section 11): a correction the person makes
in the chair is the record a step-down judgment reads. A fired
sitting is the seat's work day. An interactive sitting is the seat's
training day.

**R-12.25** The harness must tally an interactive sitting on each
turn. A Routine's run raises one Stop event, at the end of its one
prompt. An interactive session raises one Stop event for each turn.
So the hook must not close the sitting on Stop, and it must not hold
the stop: a hold on each turn tells the model to close after the
person's first message.

The hook posts a tally instead. It sums the transcript's usage as
R-12.17 says, and it posts the four token counts and the turn count
to `POST /api/-/sittings/tally`, with the seat's key in the header
`Waymark-Seat-Key`. The body, the pairing rule and the four answers
are the close door's (R-12.17). The engine writes the counts on the
open sitting through the sitting's `tally` door, from `open` to
`open`, and it writes `tallied_at` beside them.

The counts are cumulative. Each tally carries the sum over the
sitting to that moment, so a second tally replaces the first, and a
replay of one tally changes nothing.

The close comes from the harness's `SessionEnd` hook, which posts the
final counts to `POST /api/-/sittings/close`. When no `SessionEnd`
event comes, because the person closed the machine, the sweep ends
the sitting after the seat's `sitting_idle_seconds`: it closes a
sitting that has a tally, with that tally as its counts, and it
abandons one that never tallied (R-7.6).

**R-12.26** A person's own machine carries the environment. The shell
holds `WAYMARK_SEAT_URL` and `WAYMARK_SEAT_KEY`, so the direct post
of R-12.17, which is the first path, is the interactive path. One
variable covers the two doors: the hook makes the tally URL from
`WAYMARK_SEAT_URL` and puts `/tally` in the place of `/close`. The
second path, which holds the stop and hands the counts to the
session, is not used in an interactive sitting: a hold on every turn
stops the person's work.

The hook reads the mode from the sit's own answer. `waymark_sit`
answers `mode` beside the sitting (R-10.8), so the hook finds it in
the transcript's tool result. The hook then takes one of three ways.
A fired sitting with no URL: hold the stop one time, as today. An
interactive sitting with the URL: tally on Stop, and close on
`SessionEnd`. An interactive sitting with no URL: write one line on
the error stream, one time, and let the sweep close the sitting. That
line says that an interactive sitting tallies through
`WAYMARK_SEAT_URL`, that none is set, and that the sweep closes the
sitting.

**R-12.27** The engine must price each tally. The router judges the
week's wall on every request, and the sum it reads counts closed
sittings only. A fired sitting is bounded by its run, so the sum is
near the truth. An interactive sitting is not bounded, so it can
spend for hours against a wall that cannot see it.

The tally is the fix. The engine writes a running `cost_usd` on the
open sitting at the prices of that moment, with the pricing the close
door uses (R-10.4). The week's sum is then the closed sittings of the
window plus the running cost of the open ones. The wall therefore
drops inside a sitting, and the next request meets `budget_reached`.
The wall lags by one turn at most. An open sitting with no tally yet
has no running cost, and it adds nothing.

The same tally lets the router judge `sitting_budget_tokens` on the
open sitting. When the sitting's tallied counts add up to the seat's
ceiling, the router walls the request with a reason of its own,
`sitting_budget_reached`: `This sitting's fuel is spent: {n} of
{ceiling} tokens. Close the sitting; a new one opens fresh.` The sum
is the four counts together: input, output, cache read and cache
write.

The honest limit: the wall stops the seat's work, and it does not
stop the provider's meter. A session with every door refused has
nothing to do, and the refusal says so.

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

The leash keeper binds and declares `claude-opus-5`, the schedule's
model. The agent files one ask, with no scope.

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
declared `claude-opus-5`. At the cadence, the schedule's copy, a
Routine named `inbox-clerk` that the adapter made, fires a fresh
session on `claude-opus-5`. Its prompt is the pointer of R-12.3.
Nothing else (R-12.10).

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
9. The session ends. The harness's Stop hook closes the
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
   :terminal #{:action_item}                   ; dismissed keeps the person's door open
   :over {:accomplished #{:action_item} :let-go #{:dismissed}}
   :default-filters {:state "queued"}          ; the queue is the collection
   :sortable {:fields [:received_at] :default "received_at"}
   :schema [:map
            [:message_id  [:string]]           ; the address in the inbox
            [:subject     [:string]]
            [:sender      [:string]]
            [:received_at :waymark/instant]
            [:summary     {:optional true} [:maybe [:string {:max 480}]]] ; written by research
            [:body_excerpt {:optional true} [:maybe [:string {:max 4000}]]] ; read by research, for the model
            [:body_cut    {:optional true} [:maybe [:int {:min 0}]]]        ; what the cap removed
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

As built (commit 4d9b05f): the framework refuses a door out of a
terminal state, and its one waiver is for an undo within minutes,
not a person's correction days later. So `dismissed` is not
terminal; `:over` carries the ending, and every reader that asks
whether the work is over gets the same answer. The deviation is
recorded on the kind in `:deviations`.

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
with a list-unsubscribe header. Headers only.

The WHOLE body is still never stored. The research door reads the
message itself, through the sitter's own `email.read` power, and
keeps the first 4,000 characters of the plain text in `body_excerpt`
with `body_cut` beside it (section 17, "Research is an engine
step"). The model therefore decides from the row it already has, and
`waymark_power` is for the rare message the excerpt cannot answer.

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

**The schedule.** The person touched only the seat. The engine
restated the schedule row's `model` to the new first entry of
`held_for`, and the adapter pushed the Routine. The prompt did not
change.

| the copy holds | value | from |
|---|---|---|
| name | `inbox-clerk` | the seat's `name` |
| cron | `0 * * * *`, hourly | the seat's `cadence_seconds` 3600 |
| model | `claude-sonnet-5` | the schedule's `model`, defaulted from `held_for` |
| prompt | the pointer of R-12.3 | fixed at creation |

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
session form comes when the source fires the schedule with a row id
(R-12.9). Each transition carries the member and `claude-sonnet-5`
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

- `park`. The grant stays. The adapter pauses the copy, so no
  firing starts. `unpark` resumes it.
- `restate` cadence from one hour to six hours. The fixed wake cost
  falls six times.
- `restate` `held_for` down one rung, with a note. The schedule's
  `model` follows, the adapter pushes, and the next firing starts the
  cheaper model.
- Lower `budget_usd_per_week`. At the limit, the wall closes, the
  seat writes `halt`, the approver's feed says so, and discover says
  when it resumes.

### 13.12 A substitute

Sonnet is unavailable for a day. The person restates the schedule
row's `model` to `claude-haiku-4-5`, the adapter pushes it, and the
agent asks with `substitute: true`, because the person set
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

## 15. The case against data, and its answers

The owner's ruling, 2026-09-17: the seat, the model, the sitting,
and the schedule are all data in waymark. This section records the
reasons not to, so that each is answered rather than forgotten.

| the case against | what answers it |
|---|---|
| The engine cannot verify any of it. A model row is a claim, and its prices are the vendor's; a stored price drifts the day the vendor moves, and a copy that drifts is the failure this document was written to end. | A source, not a form. A price source that restates the model row on a cadence, on the pattern of the stale-price scraper. Until it exists a reprice is a person's tap, and a wrong price is a wrong cost on every sitting after it. |
| Rows are not law. The registry is fingerprinted at boot and gated by a declaration check; a seat row is not. A seat's scope can name a door that no longer exists, and nothing refuses the push that retired it. | The sweep (section 7). It is a runtime check, not a gate, and the document says so. |
| The request path gets heavier. The router loads the seat row, sums a week of sittings, and writes a counter on each request under a seat grant. | One indexed lookup, one cached sum refreshed at each sitting close, and one counter write. Bounded, and measured in the trial week. |
| Every deployment carries it. A framework kind lands in every house that runs waymark, whether or not it seats agents: a table, a migration, a surface. | The kinds are `:nav :system` and empty in a house with no seats. The cost is a table nobody fills. |
| Secrets move into the house. A schedule adapter needs a provider's token, and the engine holds it. | The Gate power pattern (R-12.11): named in the registry, held by the engine, never on a grant. |
| Knobs invite tuning. Each seat field is a lever a person can pull with no deploy, and the essay's caution is that fences arrive before crashes. | The residual rule (R-4.10) and the ruling that counts are data, not verdicts (R-11.4). A field that no ledger ever justified is cut, as `must` and `never` were. |

## 16. Acceptance

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
    it. (R-12.9, section 13.8)
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
    `a-person`. A person's is served, and so is a delegate's: an
    agent principal that carries `acts-for`. A bare agent's is
    refused. (R-4.7)
23. The ledger route returns the six answers for a window, and
    discover names the route. (R-11.3a)
24. The first request that meets a wall writes `halt` with the
    reason and raises one feed item to the approver; a second request
    at the same wall writes nothing more; the first request that
    passes clears `halt`. (R-7.7)
25. Creating a seat creates its schedule row and, through a fake
    adapter, one provider copy with the seat's name, its cadence as
    cron, `held_for`'s first model, and the fixed prompt. A `restate`
    of cadence pushes again; `park` pauses; `retire` deletes. The
    prompt never changes. (R-12.2, R-12.3)
26. A read-back that differs from the row writes `drift`, and
    discover carries it. A provider with no token serves the
    schedule `broken` with a note. (R-12.3, R-12.11)
27. `offer_key` by a person stores the key, and no projection of the
    row shows it. A bare agent's `offer_key` is refused. A create or
    restate that carries `sitter_key` is refused. `revoke_key` clears
    it. (R-12.12)
28. `initialize` answers an `Mcp-Session-Id`. A call naming an
    unknown session is answered 404. A call with no session id is
    served as before. (R-12.13)
29. `waymark_sit` with the seat's key on a known session binds it: a
    following discover on that session names the sitter `seat:{id}`
    and carries `doors.ask.seat` for the seat. The same discover
    without the session id names the delegate. A wrong key, a caller
    that is not a delegate, and a client that keeps no session are
    each refused in a sentence. (R-12.14)
30. A transition made through a bound session carries the sitter as
    its actor, and the seat's open sitting counts it. (R-12.15)
31. A Stop hook report that carries the seat's key closes the seat's
    open sitting. The sitting holds the four token counts, the turn
    count, and a `cost_usd` from the model's prices. A report with a
    wrong key is answered 404. A second report is answered 409.
    A seat session whose environment carries no URL is held at its
    stop one time and closes its own sitting through the connector.
    (R-12.17, R-10.4)
32. After the close, the next `waymark_sit` opens a fresh sitting.
    Two sessions that sit with different session ids hold two open
    sittings, and a report closes the one its `harness_session`
    names. (R-12.14, R-12.17)
33. `link` by a person moves the schedule row from `broken` to
    `live`, and no projection of the row shows `fire_token`. `unlink`
    moves the row back to `broken`, with the note that says no link.
    (R-12.18)
34. `fire` on a linked seat that is active sends the POST through a
    fake provider, writes `last_fired_at` and `last_run_url` on the
    schedule row, and counts one transition. A 429 from the provider
    leaves the row `broken` with the retry sentence, and the next
    fire that goes out clears it. A 400 paused answer moves the row
    to `paused`. A 401 and a 404 each leave the row `broken` with
    their own sentence. (R-12.19, R-12.20)
35. `fire` on a parked seat, on a halted seat, and on a seat whose
    schedule has no link is refused with the sentence, and the fake
    provider gets nothing. A bare agent's `fire` is refused the same
    way. (R-12.20)
36. A committed transition that matches a `wake_on` entry fires the
    seat one time, and the text names the row. A second match inside
    `fire_interval_seconds` does not fire, and it sets
    `wake_pending`. (R-12.22)
37. A match while the seat has an open sitting does not fire.
    (R-12.22)
38. A queue of 19 rows does not fire a seat whose entry asks for 20.
    The twentieth `create` fires the seat one time, and the text
    names the kind, the count and `at_least`, and names no row.
    (R-12.24)
39. A damped count wake sets `wake_pending` and releases one fire
    when the sitting closes. An entry's `filter` narrows the count,
    so rows outside the filter do not reach the size. An `at_least`
    of 0 is refused at `create` with the sentence. (R-12.24)
40. A seat created with no `mode` is `fired`. An interactive seat
    refuses a bare agent's `waymark_sit` with the sentence, and
    admits a delegate's. The sitting carries the seat's `mode` and
    the delegate's `person`. `fire` on an interactive seat is
    refused with the sentence. The engine mints no schedule row for
    it, and the wake consumer passes it by. (R-10.8, R-12.20)
41. A `tally` writes the four counts, `tallied_at` and a running
    `cost_usd` on the open sitting, and the sitting stays `open`. A
    second tally replaces the counts of the first. (R-12.25)
42. The week's sum counts an open sitting's running cost, so the
    budget wall drops in the middle of a sitting. A sitting whose
    tallied counts reach `sitting_budget_tokens` meets
    `sitting_budget_reached` with its own sentence. (R-12.27)
43. `POST /api/-/sittings/tally` answers 200 with the open sitting
    and its running cost, 404 with `No seat answers this key.`, 409
    when the seat has no open sitting, and 422 on a malformed body.
    (R-12.25, R-12.17)
44. The sweep closes an open interactive sitting whose `tallied_at`
    is older than `sitting_idle_seconds`, with the last tally's
    counts and the note that names the seconds. It abandons one that
    waited as long with no tally at all, with no tokens. It leaves a
    fresh sitting open. The hook tallies on Stop and closes on
    `SessionEnd`, and it holds no stop in an interactive sitting.
    (R-7.6, R-12.25, R-12.26)
45. A tool answer of N bytes under a bound session adds one call and
    N bytes to `served`, under that tool's name. A second answer adds
    to both counts. A 404 or a 409 answer counts its bytes too. A
    call from a session with no open sitting counts nothing, and a
    call under the same grant after the `close` does not move the
    closed row. The ledger answers `served` by tool over the window,
    and `bytes_per_transition`. (R-10.6a, R-11.3)
46. A `waymark_sit` on a seat that walks a queue answers `walk` with
    the charter and the rows, oldest first, not more than
    `rows_per_firing` of them, each with its summary projection and
    its doors, and each door with its input. A row outside the
    sitter's grant is absent. A seat that walks nothing answers no
    `walk`. A door with `safety.confirm` carries the sentence to echo
    back. The sit's own answer counts under `waymark_sit` on the
    sitting it opened. (R-12.28, R-10.6a)
47. A seat scope entry that filters a bench power by `repo` or by
    `path` narrows what the sitter may touch. A call inside the filter
    forwards. A call outside every entry refuses and reaches no rig. A
    call that names no path reaches the rig with `allow`. A bench call
    from a bound sitting carries `seat` and `sitting`. (R-12.30)
48. A sit on a change that names a head branch carries `feedback`: the
    pull request the rig answered, the rig's findings in the rig's own
    order, and the rig's `unavailable` list. A sit on a change with no
    head branch and no round makes no `feedback` call. A refusal and a
    dark rig each answer no `feedback` key, and the sit answers all the
    same. (R-12.31)
49. A seat that walks a queue of asks is created with no refusal. Its
    sit answers the ask rows, one change row with the `submit` door, a
    bench on the branch the policy's pattern made from the ask's own
    id, and the orientation. A second sitting on the same ask finds
    the first sitting's change and mints no other. A scope that does
    not name one repository answers no bench and a `bench_note`. The
    next source pass adopts the row the seat built, and mints no
    second one. (R-12.32)
50. The change the sit mints carries `born_from`: the walk kind, a
    colon and the walk row's own id. The adoption writes GitHub's id
    over `change_id` and leaves `born_from` as it is. A merged pull
    request on such a row completes the task it was born from, with
    the engine's own hand. A task that is already done, a task that is
    gone, and a door that refuses each leave the change at `merged`
    and raise no error. A change GitHub gave us carries no
    `born_from`, and its merge completes nothing. (R-12.32)
51. A `prepare` the rig refuses answers a `bench_note` that names the
    command and the reason, and not the sentence for a bench that said
    nothing. A seat-born change with no number and no round, at `open`
    or at `stuck`, whose branch is not the policy's pattern with its
    walk row's id, gets the new branch through `rebranch` at the next
    sit and stands at `open`. A change that has spent a round keeps
    its branch. (R-12.29, R-12.32)

The conformance suite must invoke every new door. `make check-queue`
must pass. The `approval_request` and `grant` fingerprints move,
because both schemas gain fields; the pinned hash in
`waymark10.decision-sugar-test` must be updated with the change.

## 17. Decisions on record

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
- **A key in the instructions makes the Routine the sitter.** The
  first design of the driver had the sitter bind as its own member
  through the agent door, with a leash keeper renewing a cookie in
  the Routine's MCP config. The owner ruled on 2026-09-17 that the
  driver is a Claude Routine with the engine's connector attached,
  and that a key in its instructions, with the connector's own
  credential, must be enough. The connector's credential is one per
  person and tool, so the key is what tells one session from the
  rest, and the MCP door keeps a session so the key is shown once.
  The alternatives were a second connector per seat, which the owner
  declined, and a key on every call, which a model forgets. Section
  12.1 is the result. The recorded punt that the MCP handshake is
  not a declaration door is retired for a keyed session: its
  declaration is the schedule's model, as R-12.8 always said.
- **A person's delegate opens a seat.** The first build of
  `a-person` admitted the human type only, and refused the owner's
  own connector, because the connector resolves to an agent
  (`spec-connector-door.md` § 3). The owner ruled on 2026-09-17 that
  the law changes, not the grant: a delegate is a person signed in
  through a tool, the identity gate marks it `acts-for` from a
  verified token and nothing else can, and the members gate admits
  it only while that person is an active member. The delegate still
  wears a grant, so the seat's doors open only when the person
  approved a scope that names them, and `not-a-sitter` still refuses
  a delegate whose grant cites the seat. The rule the wall protects
  is unchanged: an agent does not widen its own authority. A bare
  agent, with no person behind it, is refused as before.
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
- **Counts are data, not verdicts.** The fourth draft let refusals
  per week and corrections per transition decide whether a step
  down held. The owner ruled (2026-09-17) that so long as what was
  done is audited, the counts need not mean anything by themselves:
  keep them as data, use them where they show a signal, drop them
  when they do not. The audit is the truth.
- **A wall is hard, and it alerts.** The three walls of R-5.2 stay
  hard. The owner ruled (2026-09-17) that each raises an alert a
  person sees, so action can follow. `halt` and its feed item are
  the result.
- **The seat is the only thing a person manages.** The fifth draft
  left the Routine's name, cadence, and model as copies a person
  kept in step by hand. The owner ruled (2026-09-17) to remove the
  Routine as a thing a person edits and give the seat a link to the
  means by which a sitting is created. The `schedule` kind, mirrored
  out through an adapter on the calendar's pattern, is the result.
- **All of it is data in waymark.** The owner ruled (2026-09-17) that
  the seat, the model, the sitting, and the schedule are rows. The
  case against, and what answers each part of it, is section 15.
- **One full sitter per seat stays.** Confirmed 2026-09-17. If a
  queue ever outruns one firing, this is the first rule to bend.
- **The numbers stand until measured.** The charter cap, the rows
  per firing, the seven-day window, the two-cadence abandon rule,
  and the five-sitting read are guesses with reasons. The owner
  ruled (2026-09-17) they are fine for now and change when a ledger
  says so.
- **The driver is the harness's scheduler, not a script of ours.**
  The fourth draft had a shell loop that started one model turn per
  row. The owner ruled (2026-09-16, late) to use the native things: a
  Claude Routine, a scheduled Jules session. The seat row holds what
  a schedule needs, the prompt is a pointer to the seat so the
  charter is never copied, and the one-row-per-session form comes
  through the scheduler's API later. The cost is a shared context
  across the rows of one firing, capped by `rows_per_firing`.
- **The Stop hook is the session's end.** A Routine run is one
  prompt, so its one Stop event is where the bill is known. The
  engine does not poll the harness: the run's fire token cannot read
  anything, and no session API exists. The transcript on the
  container's disk is the exact record of every API response, and
  the hook is the one path that exists today. The session copies its
  own id into the sit, but the bill is the hook's, so a wrong id
  costs a pairing and never the truth. The hook can also hold the
  stop one time, and give the counts to the session, which closes its
  own sitting through the connector. That second path is for an
  environment that carries no variable and no credential, because the
  connector's traffic needs no domain, no variable and no key.
- **The Routine is made by hand and linked; the engine fires it.**
  Section 12 mirrored the Routine out through the adapter of R-12.2.
  The API answers one endpoint, which fires a run, so there is
  nothing to push and nothing to read back. The alternative was to
  wait for a create endpoint, which leaves the seat on its cadence
  alone. The owner ruled (2026-09-17) that a seat must be fired on
  demand and on events. The person makes the Routine one time, copies
  the fire URL and the token, and links them to the schedule row.
  One row, not two kinds: the cadence and the fire link are fields of
  the schedule row, and the events are rows of the transition log.
  The fire itself is a consumer's act, because the log is the record:
  the transition is committed before anything leaves the house, the
  ledger counts it, and a refusal from the provider is a note on the
  row. A door that waited for the provider's answer would make the
  provider's health the seat's health, and a person's fire would fail
  for a reason the person did not cause. Section 12.2 is the result.
- **A step down is judged by corrections, not by cost.** Cost always
  falls on a step down. The only question is whether the outcomes
  held, and a correction is the one record of an outcome that did
  not.
- **The mode is the seat's.** An earlier draft read the mode from the
  principal: a delegate's sit was interactive, and a Routine's run
  was fired. The owner ruled (2026-09-17) that the seat defines the
  mode. A seat is a charter and a bill, and the two modes bill in
  different ways, so one seat in two modes hides which sittings the
  ledger is reading. The audit chair is therefore a second seat, with
  the same charter and the same scope, and the ledger compares a seat
  with a seat (R-10.8).
- **Research is an engine step, because the mail was the bill.** The
  seventh counter measured the clerk's first sitting on the cheaper
  model (2026-09-18): one `waymark_power` call answered 179,483 bytes
  for three messages, which was 80 percent of the 223 KB the model
  read; the query page was 4 percent. Each turn after that read the
  same bytes again — 1.79M cache-read tokens over 25 turns, 62
  percent of the 0.58 USD. The bill is the sum, over the turns, of
  everything read before, so the largest early answer is the lever.
  The research handler therefore reads the message itself, through
  the Gate proxy under the sitter's own `email.read` grant (the ctx
  `:power` hook), and writes `body_excerpt` — 4,000 characters of
  plain text — with `body_cut` beside it. One fetch for each row
  replaces a re-read on each turn, and the model never holds the
  power to do it. The engine's own reach never refuses the door: no
  hook, a dark Gate or a rig that says no writes no excerpt, and the
  transition commits. `waymark_power` stays for the rare full read,
  and it now takes `text_only` and `max_chars` so a caller can ask
  for the words instead of the markup; what the shape removed is
  recorded on the sitting's `served` line as `dropped`.
- **The tally is the safety net under the wait.** The owner's reading
  (2026-09-17) is that the sitting does not change: the session waits
  for the next instruction, and the person says when to close. The
  wait is then the whole difference between the two modes, and an
  interactive sitting nobody closes costs a day of usage that nothing
  records. The alternative was to close on each Stop event and to
  open a fresh sitting on the next turn, which makes one afternoon
  into forty sittings and loses the chair as one record. The tally
  writes the counts on the open sitting on each turn, so the ledger,
  the week's wall and the sitting's own ceiling all read a sitting
  that is still open (R-12.25, R-12.27).

## 18. Recorded punts

- A cross-check of the model claim against the MCP client name. It
  verifies the client, not the model.
- A hook that runs when the container is reclaimed in the middle of
  a turn. No Stop event comes, so that sitting stays the sweep's.
- The hook posts one time for each Stop. A person who continues a
  run's session by hand makes turns that no sitting counts.
- The closing call's own tokens. The hook sums before the close, so
  the last call is not on the bill.
- A composed seat page that answers the six questions of R-11.3 on
  one screen, with the ladder's steps and the audit beside them. The
  queries exist; the page is a surface declaration away, as the
  member page was.
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
- A summary of a message instead of a cap on it. It would spend
  tokens to save tokens, and the first 4,000 characters are what the
  verdict needs.
- Gate answering plain text by policy. That is the right place for
  it, and it is outside this repository; the cap here is what this
  engine can enforce on its own.
- A cap for each seat. 4,000 characters is the default the engine
  holds; a seat may carry its own later.
- The exact pairing of a fire to its sitting. The provider's answer
  names the run page's session id, and the sitting carries the
  harness's own id. The run URL on the schedule row is enough to find
  the run by hand (R-12.23).
- A queue of the fires the provider's run cap refused. A 429 breaks
  the schedule row, and the next fire that goes out clears it. The
  fires inside the cap are lost, and a person fires again.
- The fire on a GitHub event. It belongs to the factory, which hears
  those events. This engine hears its own transition log.
- A rename of `schedule` to `routine`. The row now holds a link to a
  Routine, and no copy of one. The name can follow later, with a
  migration.
- The read-back of R-12.3 stays a punt for the Claude Routine
  provider. Its API has no read endpoint, so a linked row has no
  `drift` to write.
- A price source that restates model rows on a cadence (section 15).
- Adapters for providers beyond the Claude Routine. Jules and cron
  are named in the enum; the first adapter built is the Routine's,
  because the trial runs on it.
- A refusal log with the door, the guard, and the sentence, beyond
  the count. The count is enough to find the seat. The log is what
  the fence census (leg 2) reads to find the guard.
- Turn-level cost inside an interactive sitting. The tally is one sum
  over the sitting, so the record does not say which correction cost
  what. Keeping the turns is a later leg.
- A count wake over a kind the seat cannot see. The count runs under
  the seat's own grant, so an absent kind counts zero, and the seat
  says nothing. A sentence in `doors.ask.seat` that says the count
  sees nothing is the follow-up.

## 19. Effort

**Medium, leaning large.** One new file, `seats.clj`, with four
kinds: the seat (six actions, eight guards, two concealed
transitions for `halt`), the model (three actions, one guard), the
sitting (three actions, one handler, two counters), and the schedule
(engine-owned, one adapter protocol on the calendar mirror's
pattern, with the Claude Routine adapter first). One
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
scheduler is the harness's, the schedule kind mirrors to it, and the
tick script stays as it is. The adapter and its credential are the
part of this leg that reaches outside the house. The fire door of
section 12.2 adds two doors to the schedule, one door and two fields
to the seat, and one more adapter with its fake. The wake consumer
is one namespace over the transition log, with a cache of the active
seats and a tick for the damper. The count wake of R-12.24 adds two
fields to the `wake_on` entry schema and one count query to that
consumer, which reuses the collection count the list page already
runs. The interactive sitting of section 12.3 adds two fields to the
seat and three to the sitting, one door with a handler that prices an
open sitting, one route beside the close route, one wall reason, one
sum in the week's total, one branch in the sweep, and two events in
the harness's hook.
