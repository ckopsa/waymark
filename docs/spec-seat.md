# Spec — the seat: an office as a resource

**Thesis.** A seat is an office. A person creates it, edits its
authority, parks it when fuel is short, merges it with another seat,
and retires it. None of these needs a deploy. A grant that sits in a
seat holds no scope of its own. It points at the seat, and the engine
reads the seat's scope at each request. The scope has one source, so
the copy that drifted is gone.

This document is written in ASD-STE100 Simplified Technical English.
Technical names from the codebase (grant, scope, leash, ask, anchor,
door, kind, sitter) keep their spelling.

## Epistemic status

The failure is on record three times. Each time, a stored copy of a
scope was the cause.

- `spec-standing-agent.md` (waymark-ycp): the extend-ask copied the
  grant's scope, and the approval appended it. One grant reached 74
  entries for 20 kinds. The ask door refused the next ask. The leash
  died twice with nobody asking.
- waymark-enx: slice 2 of the hypotheses work removed the action
  `value.restate`. The stored scope still named it. The driver copied
  the scope forward, and every extend-ask got a 409. The leash would
  have died silently at 2026-08-31T22:49Z.
- waymark-h6y: an ask filed with no `expires_at` gets a 30-minute
  default. The mint copies that value onto the grant. The person
  approves, and the access dies minutes later.

The essay this work answers (Yegge, "Seats and Sunsets", 2026-09-15)
adds two facts. A seat is fluid: when fuel is plentiful, a person
opens a new seat, and when fuel runs short, the person parks seats
and merges roles. And each seat has a fixed cost, because a wake
costs money even when there is no work.

**The first draft of this spec chose a declaration in code.** The
owner ruled on 2026-09-16 that a seat must change with no deploy,
because the essay's seats are fluid. This draft is the resource
design. The first draft's reason was the declaration gate: a
declaration fails `make check-queue` on the push that retires an
action, and a row does not. That gap is real. Section 6 closes it
with a sweep at boot.

## What exists

- `waymark10/src/waymark10/server/roles.clj`: the `:role` kind. A
  role is a name and a description. Its meaning lives in guards and
  in grants. A role carries no scope.
- `waymark10/src/waymark10/server/grants.clj`: the `:grant` kind
  `{audience, scope, expires_at}` and the `:approval_request` kind
  `{grant_id?, task, scope, expires_at}`. The scope schema is shared.
  Four create guards check a scope against the registry:
  `scope-names-real-kinds`, `scope-names-real-actions`,
  `scope-filters-are-filterable`, `scope-omits-private-kinds`.
  `merge-scope` folds two scopes to one entry per kind.
- The router resolves a grant's visibility once per request and
  carries it as a closure map. A dead grant scopes to nothing.
- `approval-effects!` runs after the approve commits. An anchorless
  ask mints a grant. An anchored ask extends the named grant.
- `asks-are-short` caps an ask at `grant-max-ttl-seconds`, 24 hours.
  `reentry-standing-ttl-seconds` in `members.clj` is a second,
  longer ceiling of seven days.
- `definitions.clj`: `boot-revise!` fingerprints each kind at boot
  and compares it to the stored law. The registry changes only at
  boot. This is the one moment a stored scope can go stale.
- `scripts/standing-agent-tick.sh`, step 4: the driver copies the
  grant's scope into the extend-ask.
- `waymark_discover` carries `doors.ask.anchor`, the grant the
  caller wears.

## The design

### 1. The seat kind

`:seat` is a framework kind in `waymark10/src/waymark10/server/
seats.clj`, `:nav :system`, beside `:grant` and `:role`.

```clojure
{:kind :seat
 :states [:active :parked :merged :retired]
 :initial :active
 :terminal #{:merged :retired}
 :schema [:map
          [:name        [:string {:min 1 :max 40}]]      ; one spelling
          [:charter     [:string {:max 480}]]            ; what the seat is for
          [:must        {:optional true} [:vector [:string {:max 240}]]] ; standing duties
          [:never       {:optional true} [:vector [:string {:max 240}]]] ; advice, not law
          [:scope       scope-schema]                    ; the authority
          [:substitute_drop {:optional true} scope-schema] ; removed for a substitute
          [:held_for   {:optional true} [:vector :waymark/ref]] ; models that may sit (section 10)
          [:substitute_for {:optional true} [:vector :waymark/ref]] ; models that may substitute
          [:standing_ttl_seconds {:optional true} [:int {:min 60}]]
          [:cadence_seconds {:optional true} [:int {:min 60}]] ; how often the driver wakes it
          [:budget_usd_per_week {:optional true} [:decimal {:gt 0}]] ; the seat's fuel (section 11)
          [:sitting_budget_tokens {:optional true} [:int {:min 20000}]] ; one sitting's ceiling
          [:stale       {:optional true} [:vector scope-entry]] ; written by the sweep
          [:merged_into {:optional true :kind :seat} :waymark/ref]]}
```

The fields, one sentence each:

- `name` is the token a grant and an ask spell. One spelling per
  seat, with the `one-spelling` guard from `roles.clj`.
- `charter` is the sentence an agent reads at boot: who this seat
  is, and what it is for.
- `must` lists the seat's standing duties, one sentence each. The
  sitter reads them at boot. Example: "A sitting never ends with no
  work done."
- `never` lists the advice a person gives the sitter, one sentence
  each. It is prose, and the engine does not enforce it. The enforced
  never-list is the scope: a door the scope does not name is absent.
- `scope` is the seat's authority, in the scope schema grants use.
- `substitute_drop` lists the entries a substitute sitter does not
  get. Each entry must be inside `scope`.
- `held_for` lists the models that can sit as the full sitter. An
  empty list means any model. Section 10 has the model kind.
- `substitute_for` lists the models that can sit as a substitute. An
  empty list means any model.
- `standing_ttl_seconds` is the longest leash a grant in this seat
  can request. The cap is `reentry-standing-ttl-seconds`, seven
  days. An empty field means the 24-hour default.
- `cadence_seconds` is how often the driver wakes the seat. A parked
  seat has no wakes. The person changes the cadence with `restate`.
- `budget_usd_per_week` is the seat's fuel for seven days. When the
  closed sittings of the last seven days reach it, the seat serves
  nothing until the window moves. Section 11.
- `sitting_budget_tokens` is one sitting's ceiling. The driver passes
  it to the harness as the task budget, so the model paces itself.
- `stale` is written by the sweep in section 6. A person never
  writes it.
- `merged_into` names the seat this one merged into.

### 2. The actions

| action | from | to | who | what it does |
|---|---|---|---|---|
| `create` | — | active | a person, not a sitter | opens a seat |
| `restate` | active | active | a person, not a sitter | edits charter, scope, ttl, drop-list |
| `park` | active | parked | a person | sitters keep their grants; the seat serves nothing |
| `unpark` | parked | active | a person | the seat serves again, with no new tap |
| `merge` | active, parked | merged | a person, not a sitter | folds this scope into `into`; this seat closes |
| `retire` | active, parked | retired | a person | the seat closes for good |

The guards:

- **`not-a-sitter`** on `create`, `restate`, and `merge`. The actor
  holds no live grant that cites this seat. A sitter cannot widen
  its own seat. This is the human verdict the grant law requires.
  The precedent is `no-self-dealing` on `:grant`.
- **The four scope guards** on `create` and `restate`. A scope that
  names a kind, an action, a filter field, or a private kind the
  registry does not declare is refused at the door.
- **`drop-inside-scope`** on `create` and `restate`. Each entry in
  `substitute_drop` must be inside `scope`.
- **`ttl-within-standing`** on `create` and `restate`.
  `standing_ttl_seconds` must not exceed
  `reentry-standing-ttl-seconds`.
- **`merge-target-is-active`** on `merge`. The `into` seat must be
  active, and not this seat.

`merge` has `:confirm true`. Its consequence sentence: "This seat
closes. Its scope folds into {into}. Each sitter of this seat loses
its grant and must ask to sit in {into}."

`park` has `:confirm false` and `:reversible true`. It is the cheap
lever, and it must cost nothing to pull.

### 3. The grant points at the seat

`:grant` gains two optional fields, `seat` and `substitute`. A grant
with `seat` set holds no `scope`. The router resolves its
visibility from the seat row at each request:

1. Load the seat. If the seat is not `active`, the grant scopes to
   nothing. A parked seat serves nothing, and a merged or retired
   seat serves nothing.
2. Read the session's model (section 10). If the seat has a
   `held_for` list and the grant is a full grant, the model must be
   in it. If the seat has a `substitute_for` list and the grant is a
   substitute grant, the model must be in it. A model not in the
   list scopes the grant to nothing, and `doors.ask.seat` says why.
3. Take the seat's `scope`.
4. If the grant has `substitute` set, remove the `substitute_drop`
   entries.
5. Remove the `stale` entries.
6. Resolve as a scope grant resolves today.

The seat row is own-surface for its sitters, read-only: a grant's
audience can GET the seat the grant cites, with no scope entry. The
sitter reads its charter, its must list, and its never list there.
The precedent is the grant, which its audience reads without a
grant.

The grant load already happens once per request. The seat load is
one more row in the same read. A grant with `seat` set refuses a
`scope` in its body, and the reverse. A grant is one or the other.

The `:extend` transition on a seat grant changes only `expires_at`.
There is no scope to merge. `merge-scope` stays for scope grants.

### 4. The ask names the seat

`:approval_request` gains `seat` and `substitute`, both optional.
The three shapes of an ask:

| shape | fields | approval does |
|---|---|---|
| bootstrap, scope | task, scope, expires_at | mints a scope grant, as today |
| bootstrap, seat | task, seat, substitute?, expires_at | mints a seat grant, `audience` = requester |
| extend | grant_id, task, expires_at | slides `expires_at` on the named grant |

An ask with both `seat` and `scope` is refused. An extend ask with a
`scope` on a seat grant is refused, and the refusal says the seat
holds the scope. An ask that names a parked, merged, or retired seat
is refused. `asks-are-short` reads the seat's
`standing_ttl_seconds` for a seat ask, and the 24-hour ceiling for a
scope ask.

The approver's screen renders the seat's charter and scope beside
the ask, through the `seat` link. The approver approves a seat, not a
list. If a person changes the seat later, the sitter's authority
changes with it. That is the design, and section 9 records it as a
fork.

One full sitter per seat: the `seat-has-one-sitter` guard on
`approve` refuses a second accepted, non-substitute grant that cites
the same seat while the first is live. Substitutes are not limited.

### 5. Merge, exactly

`merge` takes `into`, a seat ref. The handler:

1. Folds `into`'s scope with this seat's scope through
   `merge-scope`, and writes the fold onto `into`. The four scope
   guards judge the fold before the write.
2. Folds the two `substitute_drop` lists the same way.
3. Takes the larger `standing_ttl_seconds`.
4. Writes `merged_into` on this seat and moves it to `merged`.

Each grant that cites the merged seat scopes to nothing from step 4,
by the rule in section 3. The grants are not revoked. They expire on
their own clocks. Each sitter of the merged seat files a bootstrap
ask for `into`, and the person who merged approves it in the same
sitting. One tap per moved sitter. The recorded punt is that `merge`
does not mint an offered grant for each sitter.

The `into` seat widens by the fold. The person who invokes `merge`
is not a sitter of either seat, by `not-a-sitter` on `merge` (judged
against both). That person is the human verdict.

### 6. The sweep: a seat cannot go stale in silence

The registry changes only at boot. `boot-revise!` gains one step
after the kind fingerprints: judge each active or parked seat's
`scope` with the four scope guards. For each seat that fails, the
engine writes the failing entries into `stale` through the concealed
transition `:mark_stale` (active → active, system actor, logged),
with the guard's own sentence as the transition's note.

A stale seat still serves. Section 3 removes the `stale` entries at
each request, so the sitter keeps the doors that still exist. The
leash does not go dark. This is the opposite of waymark-enx, where
one dead entry refused the whole ask.

A stale seat cannot stay quiet:

- `waymark_discover` carries `doors.ask.seat` with `name`, `state`,
  `standing_ttl_seconds`, and `stale`. The MCP instructions gain one
  sentence: "If doors.ask.seat.stale is not empty, tell your person
  the seat needs a restate, and name the entries."
- The seat's envelope carries a warning with the same entries.
- `restate` clears `stale` when the new scope passes the four guards.
  A `restate` whose scope still names a stale entry is refused by
  the guards, with the entry named.
- The driver prints the stale entries as its first line, above the
  title. The rule is the one `grant_watch` already follows for NO
  ASK STANDS.

The sweep runs also when a seat is created or restated, as the four
guards at the door. The boot sweep is the only path a seat can go
stale without a write, so it is the only sweep the engine needs.

### 7. The substitute cannot write the seat's memory

The essay's secondment bars a substitute from the memories and the
ledgers. In waymark the memories are `self`, `journal`, and `letter`.
These are private own-surface kinds. No scope can name them, so the
`substitute_drop` list cannot bar them. The bar must be a guard.

The router's visibility map gains one flag, `substitute`, read from
the grant. Three guards read the flag:

- `not-a-substitute` on `self/update` and `journal/create`: a
  substitute cannot write the sitter's self or journal.
- `not-a-substitute` on `letter/create`: a substitute cannot leave a
  letter for the next sitter.

A substitute can read all three. The refusal sentence: "A substitute
reads the seat's memory and does not write it. The seat's own sitter
writes here." The `substitute_drop` list keeps its job for the
granted kinds, such as `insight`.

### 8. The driver, and fuel

Step 4 of `standing-agent-tick.sh` reads `doors.ask.seat`. When the
grant has a seat, the driver files `{grant_id, task, expires_at}` and
no scope. The copy is gone.

When the seat is `parked`, the driver still renews the session and
the leash. Those are two HTTP calls, and no model wakes. The driver
exits before any sitting, and prints "seat parked". When the seat is
`unparked`, the next tick sits again, with no tap. This is the fixed
cost of a seat reduced to two calls.

The driver reads `cadence_seconds` and wakes the seat at that rate.
It opens a sitting before the model starts and closes it with the
token counts when the model stops. Section 11 has the sitting.

### 9. The forks, decided

**The grant is a pointer, not a snapshot.** A snapshot is a copy,
and the copy is the failure on record. With a pointer, a person's
edit to the seat changes the sitter's authority at the next request.
The grant law says scope widens only through a human verdict. The
verdict is the person's `restate` or `merge`, judged by
`not-a-sitter`. The approver of a seat ask approves an office and
trusts the office's editors. The record shows every edit as a
transition on the seat row.

**A stale seat degrades, it does not die.** A dead leash was the
disaster twice. A seat that serves the surviving entries and says so
in three places keeps the agent working and gets the person's
attention.

**Park does not revoke.** Revoke is one-way, and an unpark would
then cost a tap. The essay's park is a fuel lever a person pulls
often, so it must be free in both directions.

**Merge does not mint.** The person who merges is present, and each
moved sitter costs one tap in the same sitting. A mint from the
merge handler would be a second post-commit effect at the wire
boundary, and waymark-442.14 is still open on the first.

**One full sitter per seat.** "Who sits in this seat" must be a
one-row answer, and the essay's seat does not want two occupants.
Substitutes are the exception, by name.

**The `:role` kind stays.** A role is a name a member holds, checked
at assignment. A seat is an office with authority. The two do not
merge in this leg.

### 10. The model kind, and the seat held for a model

The essay says any model can sit, and also that a seat can be tied
to one model. Both are true here. A model is a row, so a new model
arrives with no deploy, and a lean week moves a seat from one model
to another with one `restate`.

`:model` is a framework kind in `seats.clj`, `:nav :system`.

```clojure
{:kind :model
 :states [:active :retired]
 :initial :active
 :terminal #{}
 :schema [:map
          [:name    [:string {:min 1 :max 64}]]   ; the API model id, one spelling
          [:display [:string {:min 1 :max 80}]]
          [:vendor  [:string {:min 1 :max 40}]]
          [:tier    [:enum "frontier" "strong" "economy"]]
          [:price_input_per_mtok  {:optional true} [:decimal {:min 0}]]
          [:price_output_per_mtok {:optional true} [:decimal {:min 0}]]
          [:price_cache_read_per_mtok  {:optional true} [:decimal {:min 0}]]
          [:price_cache_write_per_mtok {:optional true} [:decimal {:min 0}]]
          [:notes   {:optional true} [:maybe [:string {:max 480}]]]]}
```

The four prices are dollars per million tokens, as the vendor
publishes them. The person writes them, and `reprice` is the action
that changes them. Each reprice is a transition, so the ledger keeps
the history of prices. The essay's complaint is that prices move
often; this is where the move is recorded.

`name` is the identifier the harness spells, for example
`claude-fable-5-1`, `claude-opus-5`, `claude-sonnet-5`, or
`claude-haiku-4-5`. The `one-spelling` guard applies. `tier` is the
person's own grouping for fuel decisions, not a fact the vendor
publishes. `retire`, `reactivate`, and `reprice` are the actions. The first two
have the `role` kind's shape.

**Where the model is declared.** The engine cannot see the model on
the other end of a request. The harness can. The driver starts the
model, so the driver declares it. `POST /auth/agent` and
`POST /auth/agent/renew` accept `model` in the body, and the session
records it. The MCP `initialize` accepts the same field in
`clientInfo`. A session with no model declared has model null.

This is a claim, and the spec says so. The claim comes from the
owner's own harness, not from the model. The failure it catches is
the harness bug in the essay: a seat that stayed on an economy model
into its next turn. With the check, that session sees nothing, and
discover says "this seat is held for claude-fable-5-1; you are
claude-haiku-4-5". The sitting stops with a sentence, not with a
coma.

**Where it is checked.** Three places, one rule:

- At the ask. `model-may-sit` on `approval_request/create` refuses a
  seat ask whose requester's session model is not in the seat's
  list for the ask's kind, full or substitute. The refusal names the
  list.
- At each request. Step 2 of section 3. The session's model can
  change at a renew, so the check runs each time.
- At the seat. `held-for-active-models` on `create` and `restate`
  refuses a list that names a retired model.

**What the record holds.** The principal gains `model`. The actor
on each transition carries it, in the `actor` column that already
exists, so no migration is needed for the log. Each journal entry,
each task, and each insight then says which member wrote it and as
which model. That is the essay's "writing memories as Astra", made
visible.

**The lean week, with models.** The person restates the composer
seat: `held_for` from `claude-fable-5-1` to `claude-opus-5`. The
driver's next tick starts the new model and declares it. No deploy,
no new grant, no tap. When fuel returns, one more restate.

### 11. The sitting: fuel as a ledger in the house

The first draft of this section was a punt: "tokens are outside the
house". The owner ruled on 2026-09-16 that fuel is a high priority,
because the cost of models goes up. The punt is reversed. The house
keeps the ledger, and the seat has a budget the engine enforces.

**The sitting kind.** One row per wake of a seat. The driver opens
it before the model starts and closes it when the model stops.

```clojure
{:kind :sitting
 :states [:open :closed :abandoned]
 :initial :open
 :terminal #{:closed :abandoned}
 :own-surface {:by :member :actions #{"create" "close" "abandon"}}
 :schema [:map
          [:seat    {:kind :seat}   :waymark/ref]
          [:member  {:kind :member} :waymark/ref]   ; stamped from the principal
          [:model   {:kind :model}  :waymark/ref]   ; stamped from the session
          [:grant   {:kind :grant}  :waymark/ref]
          [:started_at :waymark/instant]
          [:ended_at   {:optional true} [:maybe :waymark/instant]]
          [:input_tokens       {:optional true} [:int {:min 0}]]
          [:output_tokens      {:optional true} [:int {:min 0}]]
          [:cache_read_tokens  {:optional true} [:int {:min 0}]]
          [:cache_write_tokens {:optional true} [:int {:min 0}]]
          [:turns  {:optional true} [:int {:min 0}]]
          [:cost_usd {:optional true} [:decimal {:min 0}]]  ; written at close
          [:prices {:optional true} :any]                    ; the four prices used at close
          [:note   {:optional true} [:maybe [:string {:max 480}]]]]}
```

`close` takes the four token counts and the turn count. The handler
reads the model's four prices at that moment, computes `cost_usd`,
and writes the prices used beside it. A reprice later does not
change a closed sitting. The record is the cost that was paid.

The token counts are the harness's report. The API returns exact
usage with each response, and the harness sums it over the sitting.
The engine does not estimate. A sitting with no close after
`cadence_seconds` times two is moved to `abandoned` by the boot
sweep, with no tokens, so a crashed sitting is visible and never
counted as free.

**The seat's budget, enforced.** Step 2 of section 3 gains a
clause. The router sums `cost_usd` over the seat's closed sittings
of the last seven days. If the sum reaches `budget_usd_per_week`,
the grant scopes to nothing, and `doors.ask.seat` carries
`budget: {spent, limit, resumes_at}`. The driver prints that line
first and exits before the model starts. This is the automatic
park. The essay's dampers were a person standing at the engine; this
one is a number on the seat.

A budget applies to full sitters and substitutes together. A seat
with no budget has no cap.

**One sitting's ceiling.** The driver reads
`sitting_budget_tokens` and passes it to the harness as the task
budget, so the model paces itself inside one wake instead of being
cut off. The engine does not enforce it; the close records what was
spent, and a sitting over its ceiling is visible in the ledger.

**What the ledger answers.** Each of these is one query on
`sitting`, filterable by seat, model, and `started_at`:

- fuel per seat per week, and against its budget;
- fuel per model, so a reprice can be judged before it is paid;
- cost per outcome, by joining sittings to the transitions written
  under the same grant in the same window;
- the fixed cost of a seat, as the sittings that wrote nothing.

The seat page sums the first. The gate's census line (leg 5) prints
the total for the week.

**The lean week, with fuel.** The person has three levers on the
seat row, and none needs a deploy: `restate` the cadence from ten
minutes to an hour; `restate` `held_for` to a cheaper model; lower
`budget_usd_per_week`. The engine enforces the third on its own.

## The essay's requirements, mapped

The essay defines a seat as an office with expectations, context,
history, memories, scope, authority, must-do lists, never-do lists,
laurels, failures, and accomplishments. A new occupant inherits all
of it. Any model can sit. A substitute cannot write the memories.
The table maps each requirement to what exists, what this spec adds,
and what stays missing.

| requirement | exists today | this spec adds | still missing |
|---|---|---|---|
| an office, not an occupant | the `member` row: durable id, re-entry credential, bind; the session is the occupant | the `seat` kind; the member is the sitter, through the grant | nothing records which model occupies the session |
| expectations | `self.about`, in the agent's own words; `role.description` | `charter`, in the person's words | — |
| context at boot | `waymark_discover`, the MCP instructions, `self.working_notes`, the welcome `:home` with letters | `doors.ask.seat` | — |
| history | the transition log with actor and law revision; `journal`; the member page and follow | — | a view keyed by seat across occupants |
| memories | `self` (about, boundaries, lessons, working_notes), `journal`, both own-surface and private by construction | — | the repository's `bd` memories are outside the house |
| scope | the grant's scope, as a copy | `scope` on the seat, one source | — |
| authority | the grant, the guards, the 24-hour leash | `standing_ttl_seconds` per seat | trust that accrues by rule |
| must-do list | one `bd` memory ("a sitting never has no work") and the retired driver | `must` | — |
| never-do list | the scope (absent means cannot), the guards, `self.boundaries` | `never` as advice | — |
| laurels, failures, accomplishments | the feed's outcomes and the owner's verdict words; the decision record (why it was allowed) | — | a page that sums them by seat |
| inheritance | `self` and `journal` persist on the member; `letter` is the addressed handoff to the next sitter | park and merge keep the member and its memory | — |
| any model can sit | nothing binds a model; the principal has id, type, roles, display, locale | `substitute` on the grant | the model tier on the session and the actor (the next leg) |
| a seat tied to a model | — | — | `preferred_model` as advice, with the next leg |
| secondment | — | `substitute_drop` for granted kinds; `not-a-substitute` on self, journal, letter | — |
| the fixed cost of a seat | the tick: renew, come home, ask | park: two HTTP calls and no wake; the sitting ledger; a weekly budget the engine enforces; a cadence per seat | — |

Two findings from the map:

1. **The member row is already half a seat.** It is durable, it
   holds a re-entry credential, and its `self`, `journal`, and
   letters are the memory that outlives a session. The `seat` kind
   does not replace it. The member sits in the seat. On merge, the
   source seat's sitter leaves a letter for the target's sitter. The
   letter kind exists for that trip.
2. **A drop-list cannot bar the memory.** `self`, `journal`, and
   `letter` can never be granted, so the substitute bar is a guard,
   not a scope entry. Section 7 is that correction.

## Recorded punts

- `merge` does not mint an offered grant for each moved sitter.
- A seat has no `holder` field. The sitter is the accepted grant
  that cites the seat, one query away, through the `grants` link.
- The seat's own ledger from the essay (laurels, failures,
  memories) is not a field. The transition log on the seat row and
  the grants that cite it are the record. A composed seat page is a
  surface declaration away, as the member page was.
- The 30-minute default on an anchorless scope ask (waymark-h6y) is
  unchanged. A seat ask defaults its leash to the seat's ceiling.
- The `approval_request` and `grant` fingerprints move, because both
  schemas gain fields. The pinned hash in
  `waymark10.decision-sugar-test` must be updated with the change.
- The model is a claim the harness makes. The engine cannot verify
  it. A cross-check against the MCP client name is a follow-up, and
  it verifies the client, not the model.
- Token counts are the harness's report, as the model is. The engine
  records what it is told and computes cost from the prices on the
  model row.
- Trust that accrues by rule (a longer leash after N clean sittings)
  is not designed. The person sets `standing_ttl_seconds` by hand.
- The budget window is a fixed seven days, not a calendar week. A
  declared window is a follow-up if the fixed one is wrong.

## What proves it

A test namespace `waymark10.seat-test` with these cases:

1. A seat whose scope names a missing action is refused at `create`
   and at `restate`, and the refusal names the entry.
2. A sitter's `restate` on its own seat is refused by
   `not-a-sitter`.
3. A seat ask mints a grant with `seat` set and no scope. An ask
   with both `seat` and `scope` is refused.
4. A request under a seat grant sees exactly the seat's scope. After
   a `restate`, the next request sees the new scope, with no new
   grant.
5. A request under a substitute grant does not see the
   `substitute_drop` entries. A substitute's write to `self`,
   `journal`, or `letter` is refused; its read is served.
6. `park` makes the sitter's request see nothing. `unpark` restores
   it. No grant moved.
7. `merge` writes the fold onto `into`, closes the source, and the
   source's sitter sees nothing. The fold has one entry per kind.
8. A boot with a retired action marks the seat stale, with the entry
   named. The sitter's request sees the surviving entries. Discover
   carries `stale`. A `restate` that drops the entry clears it.
9. A second full sitter on one seat is refused at `approve`. A
   substitute is not.
10. A seat ask can request up to the seat's ceiling. A scope ask is
    still capped at 24 hours.
11. A seat held for one model refuses a seat ask from a session that
    declares another model, and names the list. A session that
    declares the held-for model sees the scope. A renew that changes
    the model to one not in the list makes the next request see
    nothing.
12. A transition written under a seat grant carries the session's
    model in its actor.
13. A `restate` whose `held_for` names a retired model is refused.
14. A `close` on a sitting computes `cost_usd` from the model's
    prices and writes the prices used. A `reprice` afterwards does
    not change it.
15. A seat whose closed sittings of the last seven days reach its
    budget serves nothing, and discover carries the spent amount,
    the limit, and the resume instant. A sitting closed eight days
    ago does not count.
16. A sitting left open past two cadences is marked abandoned by the
    boot sweep.

The conformance suite must invoke every new door. `make check-queue`
must pass.

## Effort

**Medium.** The seat kind is one file with six actions and five
guards, plus one guard on three own-surface doors. The model kind is
three actions and one guard in the same file. The sitting kind is
three actions and one handler. The session gains one field, the
principal gains one field, and two auth doors and the MCP initialize
accept it. The router's seat resolve gains one sum over sittings. The scope schema, the four scope guards, `merge-scope`,
`no-self-dealing`, and `one-spelling` all exist and are reused. The
router gains one row load in the visibility resolve. `boot-revise!`
gains one step. The migration adds one table and four nullable
columns.
