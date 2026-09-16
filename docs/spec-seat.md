# Spec — the seat: requirements

**Purpose.** This document gives the requirements for the seat, the
model, and the sitting in waymark10. A seat is an office that an
agent occupies. A person opens it, changes it, parks it, merges it,
and retires it with no deploy. The engine reads the seat at each
request, enforces its budget, and records who sat in it, as which
model, and at what cost.

This document is written in ASD-STE100 Simplified Technical English.
Technical names from the codebase keep their spelling: grant, scope,
leash, ask, anchor, door, kind, sitter, sitting. "Must" gives a
requirement. "Can" gives a permission. "Is" gives a fact.

Source: Yegge, "Seats and Sunsets", 2026-09-15. Bead: waymark-fp62.1,
leg 1 of the epic waymark-fp62.

## 1. Definitions

| term | meaning |
|---|---|
| seat | an office: a name, a charter, an authority, a budget, and a lifecycle. A row of kind `seat`. |
| sitter | the member that holds an accepted grant that cites a seat |
| substitute | a sitter whose grant has `substitute` set. It reads the seat's memory and does not write it. |
| model | a row of kind `model`: one model identifier, its tier, and its prices |
| sitting | one wake of a seat: a row of kind `sitting` with its token counts and its cost |
| memory | the sitter's own-surface kinds: `self`, `journal`, `letter` |
| scope | the list of kinds, actions, rows, fields, and filters a grant admits. The scope schema in `grants.clj`. |
| the four scope guards | `scope-names-real-kinds`, `scope-names-real-actions`, `scope-filters-are-filterable`, `scope-omits-private-kinds` |
| the harness | the owner's driver script, which starts the model and speaks to the engine for it |

## 2. The problem on record

A grant's scope is a copy of an ask. Each extend-ask copies it
forward again. The copy drifted three times:

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

## 3. Requirements: the seat kind

**R-3.1** The engine must serve a framework kind `seat`, in
`waymark10/src/waymark10/server/seats.clj`, with `:nav :system`.

**R-3.2** A seat must have these fields.

| field | type | meaning |
|---|---|---|
| `name` | string, 1 to 40 | the token a grant and an ask spell. One spelling per seat. |
| `charter` | string, to 480 | what the seat is for, in the person's words |
| `must` | list of strings | the seat's standing duties, one sentence each |
| `never` | list of strings | advice to the sitter. Prose. Not enforced. |
| `scope` | scope schema | the seat's authority |
| `substitute_drop` | scope schema | the entries a substitute does not get |
| `held_for` | list of model refs | the models that can sit as the full sitter. Empty means any. |
| `substitute_for` | list of model refs | the models that can sit as a substitute. Empty means any. |
| `standing_ttl_seconds` | int | the longest leash a grant in this seat can request |
| `cadence_seconds` | int | how often the driver wakes the seat |
| `budget_usd_per_week` | decimal | the seat's fuel for seven days |
| `sitting_budget_tokens` | int, 20000 or more | one sitting's ceiling, passed to the harness |
| `stale` | list of scope entries | written by the sweep. A person never writes it. |
| `merged_into` | seat ref | the seat this one merged into |

**R-3.3** A seat must have the states `active`, `parked`, `merged`,
and `retired`. `merged` and `retired` are terminal.

**R-3.4** A seat must have these actions.

| action | from | to | actor | effect |
|---|---|---|---|---|
| `create` | — | active | a person, not a sitter | opens the seat |
| `restate` | active | active | a person, not a sitter | changes charter, must, never, scope, drop-list, held-for lists, ttl, cadence, budgets |
| `park` | active | parked | a person | the seat serves nothing. Grants stay. |
| `unpark` | parked | active | a person | the seat serves again |
| `merge` | active, parked | merged | a person, not a sitter | folds this scope into `into`. This seat closes. |
| `retire` | active, parked | retired | a person | the seat closes for good |

**R-3.5** `park` must have `:confirm false` and `:reversible true`.
It is the cheap lever, and it must cost nothing to pull.

**R-3.6** `merge` must have `:confirm true` with this consequence
sentence: "This seat closes. Its scope folds into {into}. Each
sitter of this seat loses its grant and must ask to sit in {into}."

**R-3.7** These guards must judge the seat's doors.

| guard | doors | rule |
|---|---|---|
| `one-spelling` | create | no active seat has this name. From `roles.clj`. |
| `not-a-sitter` | create, restate, merge | the actor holds no live grant that cites this seat, or the `into` seat |
| the four scope guards | create, restate | the scope names only kinds, actions, filter fields, and non-private kinds the registry declares |
| `drop-inside-scope` | create, restate | each `substitute_drop` entry is inside `scope` |
| `ttl-within-standing` | create, restate | `standing_ttl_seconds` is not more than `reentry-standing-ttl-seconds` |
| `held-for-active-models` | create, restate | each model in the two lists is active |
| `merge-target-is-active` | merge | `into` is active, and is not this seat |

**R-3.8** `not-a-sitter` is the human verdict the grant law requires.
A sitter must not widen its own seat.

**R-3.9** The seat row must be own-surface for its sitters, read-only:
a grant's audience can read the seat the grant cites, with no scope
entry. The precedent is the grant, which its audience reads.

## 4. Requirements: the grant and the ask

**R-4.1** The `grant` kind must gain two optional fields: `seat`, a
seat ref, and `substitute`, a boolean. A grant with `seat` set must
hold no `scope`. A grant with `scope` must hold no `seat`.

**R-4.2** The router must resolve a seat grant's visibility from the
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

**R-4.3** The `extend` transition on a seat grant must change only
`expires_at`. There is no scope to merge.

**R-4.4** The `approval_request` kind must gain two optional fields:
`seat` and `substitute`. The three shapes of an ask:

| shape | fields | approval effect |
|---|---|---|
| bootstrap, scope | task, scope, expires_at | mints a scope grant, as today |
| bootstrap, seat | task, seat, substitute?, expires_at | mints a seat grant with `audience` = requester |
| extend | grant_id, task, expires_at | slides `expires_at` on the named grant |

**R-4.5** The ask door must refuse: an ask with both `seat` and
`scope`; an extend ask with a `scope` on a seat grant; an ask that
names a seat that is not active.

**R-4.6** `asks-are-short` must read the seat's
`standing_ttl_seconds` for a seat ask, and the 24-hour ceiling for a
scope ask.

**R-4.7** `model-may-sit` on `approval_request/create` must refuse a
seat ask whose requester's session model is not in the seat's list
for the ask's kind, full or substitute. The refusal names the list.

**R-4.8** `seat-has-one-sitter` on `approve` must refuse a second
accepted full grant that cites a seat while the first is live.
Substitutes are not limited.

**R-4.9** The approver's screen must show the seat's charter and
scope beside a seat ask, through the seat link. The approver
approves an office.

## 5. Requirements: merge

**R-5.1** `merge` must take `into`, a seat ref. The handler must:

1. fold `into`'s scope with this seat's scope through `merge-scope`,
   judge the fold with the four scope guards, and write it onto
   `into`;
2. fold the two `substitute_drop` lists the same way;
3. keep the larger `standing_ttl_seconds` and the larger
   `budget_usd_per_week`;
4. write `merged_into` on this seat and move it to `merged`.

**R-5.2** `merge` must not revoke grants and must not mint grants. A
grant that cites a merged seat scopes to nothing by R-4.2 and
expires on its own clock. Each moved sitter files a bootstrap ask for
`into`.

## 6. Requirements: the sweep

**R-6.1** The registry changes only at boot. `boot-revise!` must
judge each active or parked seat's `scope` with the four scope
guards after the kind fingerprints.

**R-6.2** For each seat that fails, the engine must write the failing
entries into `stale` through a concealed transition `mark_stale`,
system actor, logged, with the guard's own sentence as the note.

**R-6.3** A stale seat must still serve the entries that are not
stale. The leash must not go dark.

**R-6.4** A stale seat must not be quiet. `waymark_discover` must
carry `doors.ask.seat` with `name`, `state`, `standing_ttl_seconds`,
`stale`, and `budget` (spent, limit, resumes_at). The seat's envelope
must carry a warning with the stale entries. The driver must print
the stale entries and the budget line first, above the title.

**R-6.5** `restate` must clear `stale` when the new scope passes the
four guards. A `restate` whose scope still names a stale entry is
refused by the guards, with the entry named.

**R-6.6** The boot sweep must move a sitting left `open` for more
than two cadences to `abandoned`, with no tokens.

## 7. Requirements: the substitute

**R-7.1** A substitute must not write the seat's memory. `self`,
`journal`, and `letter` are private own-surface kinds that no scope
can name, so the bar is a guard, not a scope entry.

**R-7.2** The visibility map must carry a `substitute` flag read
from the grant. The guard `not-a-substitute` must judge
`self/update`, `journal/create`, and `letter/create`. Its sentence:
"A substitute reads the seat's memory and does not write it. The
seat's own sitter writes here."

**R-7.3** A substitute can read all three kinds.

## 8. Requirements: the model kind

**R-8.1** The engine must serve a framework kind `model` in
`seats.clj`, `:nav :system`, with states `active` and `retired`.

**R-8.2** A model must have these fields.

| field | type | meaning |
|---|---|---|
| `name` | string, 1 to 64 | the API identifier, for example `claude-fable-5-1`, `claude-opus-5`, `claude-sonnet-5`, `claude-haiku-4-5`. One spelling. |
| `display` | string | the name a person reads |
| `vendor` | string | who serves it |
| `tier` | enum frontier, strong, economy | the person's grouping for fuel decisions |
| `price_input_per_mtok` | decimal | dollars per million input tokens |
| `price_output_per_mtok` | decimal | dollars per million output tokens |
| `price_cache_read_per_mtok` | decimal | dollars per million cache-read tokens |
| `price_cache_write_per_mtok` | decimal | dollars per million cache-write tokens |
| `notes` | string | free prose |

**R-8.3** A model must have the actions `retire`, `reactivate`, and
`reprice`. Each reprice is a transition, so the history of prices is
on record.

**R-8.4** The harness must declare the session's model. `POST
/auth/agent`, `POST /auth/agent/renew`, and the MCP `initialize`
must accept `model`, and the session must record it. A session with
no declaration has model null.

**R-8.5** The principal must gain `model`, read from the session. The
actor on each transition then carries it, in the `actor` column that
exists. No migration is needed for the log.

**R-8.6** The model is a claim the harness makes. The engine cannot
verify it. The document says so, and the check is against the
harness, which is the failure the essay describes.

## 9. Requirements: the sitting kind

**R-9.1** The engine must serve a framework kind `sitting` in
`seats.clj`, with states `open`, `closed`, and `abandoned`. `closed`
and `abandoned` are terminal.

**R-9.2** A sitting must have these fields.

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
| `cost_usd` | decimal | written at close |
| `prices` | map | the four prices used at close |
| `note` | string | one sentence on what the sitting did |

**R-9.3** A sitting must be own-surface for its member, with the
actions `create`, `close`, and `abandon`. The driver opens it before
the model starts and closes it when the model stops.

**R-9.4** `close` must take the four token counts and the turn count.
The handler must read the model's prices at that moment, compute
`cost_usd`, and write the prices used beside it. A reprice later
must not change a closed sitting.

**R-9.5** The engine must not estimate tokens. It records the
harness's report.

**R-9.6** The sitting collection must be filterable by `seat`,
`model`, and `started_at` after, so these are each one query: fuel
per seat per week against its budget; fuel per model; cost per
outcome, by the grant and the window; the fixed cost of a seat, as
the sittings that wrote nothing.

## 10. Requirements: the driver

**R-10.1** The driver must read `doors.ask.seat` before any sitting.
If the seat is parked, over budget, or held for another model, the
driver must print the reason first and exit before the model starts.
It must still renew the session and the leash.

**R-10.2** The driver must wake the seat at `cadence_seconds`.

**R-10.3** The driver must open a sitting before the model starts
and close it with the exact token counts when the model stops.

**R-10.4** The driver must pass `sitting_budget_tokens` to the
harness as the task budget.

**R-10.5** The driver must file an extend-ask as `{grant_id, task,
expires_at}` with no scope when the grant cites a seat.

**R-10.6** The driver must declare the model it starts, at bind and
at renew.

## 11. The email clerk: a worked example

The seat surfaces action items from the owner's inbox. Its name is
`inbox-clerk`.

### 11.1 The person opens the seat

One POST to `/api/seats`, by a person who will not sit in it.

```json
{
  "name": "inbox-clerk",
  "charter": "You read Colton's inbox and turn each request in it into a task in the queue. You do not answer mail.",
  "must": [
    "Read every unread message in each sitting.",
    "Make one task for each request that names Colton, with the due date the sender named.",
    "Write one journal entry at the end of each sitting."
  ],
  "never": [
    "Do not make a task for a newsletter or a receipt.",
    "Do not move or send mail. The scope does not open those doors."
  ],
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

### 11.2 The models exist as rows

Two rows of kind `model`, created once by the person and repriced
when the vendor moves. The prices are the first-party rates on the
day of writing.

| name | tier | input $/M | output $/M |
|---|---|---|---|
| `claude-opus-5` | strong | 5.00 | 25.00 |
| `claude-sonnet-5` | economy | 2.00 | 10.00 |

### 11.3 The sitter is a member

This exists today. The person mints an invite link. The agent opens
the welcome document and binds. The result is a member row with
`actor_type` agent and a re-entry credential. Its `self`, `journal`,
and letters are its memory.

### 11.4 The agent asks to sit

The driver starts `claude-opus-5` and declares it at bind. The agent
files one ask, with no scope.

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

### 11.5 One sitting

At the cadence, the driver ticks.

1. It renews the session and declares `claude-opus-5`.
2. It calls `waymark_discover`. `doors.ask.seat` says: active, stale
   empty, budget spent 3.10 of 12.00, resumes null.
3. It opens a sitting: seat, grant, started_at.
4. It starts the model with a task budget of 40000 tokens.
5. The model reads the seat row: the charter, the must list, the
   never list.
6. `waymark_powers` lists the emila read tools. Move and send are
   absent.
7. The model reads the inbox through `waymark_power`.
8. For each request it finds, it invokes `task.create` with the
   title, the due date, and source todo. Each create is a transition
   with the member as actor and `claude-opus-5` as model.
9. It invokes `insight.create` for a finding that cites a task it
   made.
10. It writes one journal entry.
11. The driver closes the sitting with the usage the API returned:
    input 31200, output 2900, cache read 18000, cache write 0, turns
    6. The handler writes `cost_usd` 0.23 and the prices used.

### 11.6 What the record holds

| row | points at | holds |
|---|---|---|
| seat `inbox-clerk` | two models | charter, must, never, scope, budgets, stale |
| grant | seat, member | expires_at, substitute |
| member | — | display, roles, the re-entry credential |
| self, journal, letter | member | the sitter's memory |
| sitting | seat, member, model, grant | tokens, cost, prices used |
| transitions on task and insight | member as actor, with model | the history |

"Who sits in the inbox clerk seat" is one query: accepted grants that
cite the seat. "What did the seat cost this week" is one query:
closed sittings that cite the seat, started in the last seven days.

### 11.7 A lean week

The person has four levers on the seat row, and none needs a deploy.

- `park`. The grant stays. The driver renews and exits. No model
  wakes.
- `restate` cadence from one hour to six hours.
- `restate` `held_for` to `claude-sonnet-5`. The driver's next tick
  starts the cheaper model and declares it.
- Lower `budget_usd_per_week`. At the limit, the engine parks the
  seat on its own, and discover says when it resumes.

### 11.8 A substitute

Opus is out of fuel. The driver starts `claude-sonnet-5` and the
agent asks with `substitute: true`. `model-may-sit` passes on
`substitute_for`. The substitute reads mail and makes tasks. It does
not get `insight.create`, by the drop-list. It reads the journal and
cannot write it, by `not-a-substitute`. Every task it makes carries
`claude-sonnet-5` in the actor.

### 11.9 A leaner month

The person merges `inbox-clerk` into `composer`. The composer's
scope becomes the fold of both. The inbox clerk seat closes. The
clerk's sitter leaves a letter for the composer's sitter, with the
sender rules it learned, and asks to sit in `composer`. One tap.

### 11.10 The law moves

A push retires `task.prioritize`. At the next boot, the sweep writes
`[{"kind": "task", "actions": ["prioritize"]}]` into `stale`. The
seat still serves the other three entries. Discover says stale. The
driver prints it first. The person restates the scope without
prioritize, and the stale list clears.

## 12. Acceptance

A test namespace `waymark10.seat-test` must prove each requirement
above. The cases:

1. A seat whose scope names a missing action is refused at `create`
   and at `restate`, with the entry named. (R-3.7)
2. A sitter's `restate` on its own seat is refused. (R-3.8)
3. A seat ask mints a grant with `seat` set and no scope. An ask with
   both `seat` and `scope` is refused. (R-4.1, R-4.5)
4. A request under a seat grant sees exactly the seat's scope. After
   a `restate`, the next request sees the new scope with no new
   grant. (R-4.2)
5. A substitute grant does not see the drop entries. A substitute's
   write to `self`, `journal`, or `letter` is refused; its read is
   served. (R-4.2, R-7)
6. `park` makes the sitter's request see nothing. `unpark` restores
   it. No grant moved. (R-3.4)
7. `merge` writes the fold onto `into`, closes the source, and the
   source's sitter sees nothing. The fold has one entry per kind.
   (R-5)
8. A boot with a retired action marks the seat stale with the entry
   named. The sitter sees the surviving entries. Discover carries
   `stale`. A `restate` that drops the entry clears it. (R-6)
9. A second full sitter on one seat is refused at `approve`. A
   substitute is not. (R-4.8)
10. A seat ask can request up to the seat's ceiling. A scope ask is
    capped at 24 hours. (R-4.6)
11. A seat held for one model refuses a seat ask from a session that
    declares another, and names the list. A renew that changes the
    model to one not in the list makes the next request see
    nothing. (R-4.7, R-4.2)
12. A transition written under a seat grant carries the session's
    model in its actor. (R-8.5)
13. A `restate` whose `held_for` names a retired model is refused.
    (R-3.7)
14. A `close` computes `cost_usd` from the model's prices and writes
    the prices used. A `reprice` afterwards does not change it.
    (R-9.4)
15. A seat whose closed sittings of the last seven days reach its
    budget serves nothing. Discover carries spent, limit, and
    resumes_at. A sitting closed eight days ago does not count.
    (R-4.2, R-6.4)
16. A sitting left open past two cadences is marked abandoned by the
    boot sweep. (R-6.6)

The conformance suite must invoke every new door. `make check-queue`
must pass. The `approval_request` and `grant` fingerprints move,
because both schemas gain fields; the pinned hash in
`waymark10.decision-sugar-test` must be updated with the change.

## 13. Decisions on record

Each decision, its alternative, and the reason. The reversed drafts
stay here, because a record that is rewritten is a record nobody
trusts.

- **A seat is a resource, not a declaration in code.** The first
  draft (2026-09-16, morning) chose a declaration, because the
  declaration gate fails on the push that retires an action. The
  owner ruled the same day that a seat must change with no deploy,
  because the essay's seats are fluid. The gate's job moved to the
  boot sweep (section 6).
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

## 14. Recorded punts

- A cross-check of the model claim against the MCP client name. It
  verifies the client, not the model.
- A composed seat page that sums laurels, failures, and fuel. The
  queries exist (R-9.6); the page is a surface declaration away, as
  the member page was.
- Trust that accrues by rule, such as a longer leash after N clean
  sittings. The person sets the ceiling by hand.
- A seat that a person creates from a declared template. The
  substitute drop-list is already a narrowing of a scope, so the path
  is open.
- The 30-minute default on an anchorless scope ask (waymark-h6y) is
  unchanged. A seat ask defaults its leash to the seat's ceiling.

## 15. Effort

**Medium.** One new file, `seats.clj`, with three kinds: the seat
(six actions, seven guards), the model (three actions, one guard),
and the sitting (three actions, one handler). One guard on three
own-surface doors. Two optional fields on `grant` and two on
`approval_request`. One field on the session and one on the
principal, accepted at two auth doors and the MCP initialize. The
router's seat resolve gains one row load and one sum. `boot-revise!`
gains two steps. The migration adds three tables and four nullable
columns. The scope schema, the four scope guards, `merge-scope`,
`no-self-dealing`, and `one-spelling` are reused as they are.
