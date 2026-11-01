# Spec: promises — what must hold, by when, and who owes it

Piece 1 of epic `2993ea03` (ticket `0b6026fe`). This is a spec only, so
no engine code lands with it. It ends with the build plan the mayor
splits into children, in the epic's Order, as
`docs/spec-measures.md` and `docs/spec-change.md` end. It composes with
`docs/spec-measures.md`: a goal may measure a promise's kept rate (§9).

> A fix merges at 13:52. The house holds the promise "merged content is
> live within 2 h". At 15:52 the promise breaks. The engine moves it to
> `broken`, reads who holds the door that fixes it, and wakes that seat
> with the change, the cause and the door in the fire text. The seat
> deploys, the next check finds the content live, and the promise moves
> back to `kept`. The owner sees one held call, the apply, and taps it.
> The mayor fired nothing.

The engine already says what MAY happen (guards, grants, scope), who
hears a move (subscriptions) and who holds the next move (`waiting_on`).
It has no row that says what MUST happen, by when, and who owes it. That
intent lives in charters, fire text and done-when, and the mayor carries
it by hand: about fifteen hand fires a day on Oct 30-31. This spec makes
the intent a row, a `promise`, and makes each break a transition that
wakes the debtor.

---

## 0 · Today

Read at main `c82bc7e3`. Paths are under `waymark10/src/waymark10/`.

- **Who holds the next move is already computed.**
  `server/waiting_on.clj:265` `block` builds a row's `waiting_on`: the
  doors out of the state (`cracks/row-doors`, :280), and the holder
  `holder-of` picks (:199). `choose` (:164) takes a model seat first (a
  woken one before one on a cadence), then a source, then a person, then
  nobody. A seat counts only when its walk admits the row
  (`live-cracks/walk-admits?`, :306-310). With no model seat the verdict
  reads "a person: no seat a model sits in holds a door out of …"
  (:235).
- **How late it is is already computed.** `block` reads
  `live-cracks/expected-age` (:286) or a dated `deadline` (:287-289) and
  answers `since.expected_seconds` or `since.due` (:317-318). The age is
  declared per kind and state under `:expected-age`, or falls back to
  `default-ages` by state name (`server/live_cracks.clj:119`: open,
  submitted and in_review a day, blocked a week) and then the kind's
  `:default-expected-age` (:67-73). A map age (`{:business-days n}`,
  `{:until …}`) reads the calendar (:75-82).
- **Nothing acts on lateness except the cracks pass.**
  `live_cracks.clj:1263` `pass!` runs every 15 minutes
  (`start-sweeper!`, :1290-1295). It finds aged rows, routes each crack
  (`route`, :761: `:wait`, `:wake` or `:file`), wakes the holding seat by
  its `fire` door with a row-naming text (`wake!` :963, `wake-text`
  :745), and files a draft ticket otherwise. It is capped by `file-cap`
  and keyed by the row's last write, so a row is woken on once per time
  it ages (:965-966). It knows one rule only: "sat past its expected
  age".
- **The walk is hand-written.** A seat's queue is its scope entry's
  filter (`server/seats.clj:632` `walk-filter`) or the kind's default
  filter. When the default hides a state a held door leaves from, the
  seat never meets the row: the default-filter trap, ticket `086dcbfd`,
  caught only as an advisory (`doors-the-default-hides`,
  `seats.clj:857`; `walk-not-hidden-by-its-default`, :884).
  `live_cracks.clj:266` `in-walk?` re-reads that filter to decide whether
  `waiting_on` may name the seat.
- **The declaration audit is static.** `server/cracks.clj:988` `audit`
  lists states no active seat can leave, remedies no seat holds, and
  waits nobody is woken to (:1005-1012). It judges the law, not the rows.
- **Seat health is a second, separate rule set.**
  `seats.clj:2249` `sitting-health` stamps each closed sitting's outcome,
  `stamp-health` (:2380) writes it, and `health-breach` (:7687) judges a
  window of sittings against `health-alert-defaults` (:264). A breach
  names a rule. Nothing owes a remedy for it.
- **In flight is a read, not a duty.** `server/in_flight.clj:1-46`
  serves every open row with an expected age, grouped by holder, most
  overdue first. It shows the debt and wakes nobody.
- **The wake machinery exists.** A door subscription that fires a seat
  marks it pending (`server/door_receivers.clj:1-9`, after
  docs/spec-events.md §4.2). `wakes/wake-text` (`server/wakes.clj:341`)
  carries kind, id, action, from and to. `wakes/wake-held` (:1413) says
  what holds a wake: `:damped`, `:throttled`, `:fuel_wall`,
  `:spoken_for`, `:held`.
- **A person's choice exists.** `held_call` has a `propose` create
  (`server/held_calls.clj:1204-1208`): an agent's multi-choice hold that
  the person answers with `allow {choice}` (:1210-1219).
- **Verdicts exist.** `verdict.clj:707` is a row about a row
  (`subject_kind`, `subject_id`, `judgment`), states `said` and
  `overruled` (:718-720).
- **Powers are reached through one door.** `server/gate_proxy.clj:1-22`
  judges a power token against the grant and forwards to the
  `mcp_server` row's client, answering the payload verbatim.
- **Measures and goals have landed.** `server/goals.clj:1-18` (one
  active goal per measure, owner-held doors) and
  `server/readings.clj:1-11` (one `measure_reading` per window, written
  only by `maintainer/sweep-measures!`, `server/maintainer.clj:967`).

So every piece of a promise exists except the promise: a row that says
what must hold, checks it, and treats a break as a transition with a
debtor.

## 1 · The decision in one paragraph

**A promise is a row; its standing is its state; a break is a
transition; the debtor is whoever holds the remedy door.** A `promise`
names what it is about (one row, or a kind and a filter), what must hold
(a query, a probe or a judgment), by when (a deadline counted from a
transition, or a standing window), and the remedy door. The engine
checks it on the transitions it hears and on a slow sweep. A failed
check moves it `kept → at_risk → broken`. A recovered one moves it back
to `kept`. Each move is a logged transition, so subscriptions, history
and the ledger already see it. On `broken` the engine computes the
debtor exactly as `waiting_on` computes a holder, and wakes that seat
with the row, the cause and the door. With no model seat it proposes
one held call to the person. A seat's walk becomes the rows that break
the promises it keeps.

## 2 · The promise kind

### Fields

**R-P.2.1** A `promise` row carries:

| field | what it says |
|---|---|
| `name` | a short sentence a person reads: "merged content is live within 2 h" |
| `about` | `{kind, id}` for one row, or `{kind, filter}` for every row the filter admits (waymark_query's `filter` grammar) |
| `holds` | the check: `{query …}`, `{probe …}` or `{judgment …}` (§3) |
| `by` | `{after <action>, within <age>}`: a deadline counted from the row's last `<action>` transition, or `{window <age>}`: the check must pass at every sweep |
| `remedy` | `{kind, door}`: the door that fixes a break, on the `about` row or a row it links to (`{via <link rel>}`) |
| `debtor` | optional seat name; when named, it must accept (R-P.2.6). Unnamed, the debtor is computed per break (§5) |
| `mode` | `watch` or `enforced` |
| `owner` | the member the promise is for: the person who hears an escalation |

**R-P.2.2** `within` and `window` take the same age values as
`:expected-age` (`live_cracks.clj:67-82`): seconds, `{:business-days
n}`, or `{:until <form>}`. A promise reuses the deadline code; it does
not grow a second clock.

**R-P.2.3** `mode watch` checks and moves standing but wakes nobody and
proposes nothing. A watched promise shows its breaks in history and in
flight, so the owner sees what it would have done before enforcing it
(story 4). A new promise is born `watch`.

### States and doors

**R-P.2.4** States: `proposed`, `kept`, `at_risk`, `broken`, `retired`.
`retired` is terminal. `proposed` is the state a promise waits in until
its named debtor accepts it.

| door | from → to | who |
|---|---|---|
| `create` | → `proposed` (debtor named) or `kept` | the owner, or a seat whose grant names `promise.create` |
| `accept` | `proposed` → `kept` | the named debtor seat |
| `decline` | `proposed` → `retired` | the named debtor seat, with a sentence |
| `restate` | any but `retired` → same | the owner, or a seat acting for them. An edit of `holds`, `by`, `remedy` or `debtor`. A changed `debtor` goes back to `proposed` |
| `enforce` / `watch` | any but `retired` → same | the owner: flips `mode` |
| `risk` | `kept` → `at_risk` | the engine only |
| `break` | `kept`, `at_risk` → `broken` | the engine only |
| `keep` | `at_risk`, `broken` → `kept` | the engine only |
| `retire` | any → `retired` | the owner |

**R-P.2.5** `risk`, `break` and `keep` are walled to the engine's own
hand, the way `readings.clj:26-33` walls a reading's create. A seat
cannot mark its own promise kept; it can only fix the row.

**R-P.2.6** A promise that names a seat as debtor needs that seat's
`accept` (story 7). The seat accepts with its own grant under the
person's approval: a model seat's `accept` is a `held_call` for the
seat's person. A seat cannot be made to owe what it never took.

**R-P.2.7** The doors that write a promise are held as `goal`'s are
(`goals.clj:10-15`): the owner of the house, and any seat whose grant
names `promise`. A delegate is judged as its person.

## 3 · Three ways to check

**R-P.3.1** An evaluation answers one of `pass`, `fail` or `error`. **An
error is not a breach.** An error leaves standing where it is and
stamps `last_error` on the promise. Three errors in a row move nothing
either. They file one draft ticket against the promise, keyed by the
promise and the error's first line, so a broken probe is reported once.

### Query

**R-P.3.2** `{query {kind, filter, expect}}`: a collection read under
the engine's grant. `expect` is `none` (no row may match), `some` (a
row must match), or `{count_lte n}`. With `about {kind, filter}`, the
query runs per `about` row with `{id}` bound, so "every submitted
change" is one promise with many breaches (R-P.4.4).

- **When:** on every transition of the `about` kind (the same hearing
  `notice-rules-transition!`, `held_calls.clj:2729`, does), and on the
  sweep for promises whose `by` is time-bound. The sweep reuses the
  live cracks clock (`live_cracks.clj:1290`) at 15 minutes.
- **Cost:** one indexed collection read. The same 10 000-row cap as
  `server/measure.clj` applies. Over it the evaluation is an `error`.
- **Fail means** the expectation is false past `by`.

### Probe

**R-P.3.3** `{probe {power, arguments, expect, every}}`: one call
through the power door (`gate_proxy.clj:18-22`) under the promise
owner's grant, with `why` set to the promise's `name`. `expect` reads
the answer: a JSON path and a comparison, for example `{path
"$.newest_at", age_lte 86400}`.

- **When:** on its own schedule, `every` (at least 15 minutes), as a
  `scheduled_action` (`server/scheduled.clj:1908`). Never on a
  transition, because a probe touches a wire.
- **Cost:** one power call per `every`. The call's spend is the
  promise's (§10).
- **Fail means** the answer was read and `expect` is false. A refused
  grant, a server down or an unreadable answer is an `error`.

### Judgment

**R-P.3.4** `{judgment {id, expect}}`: the newest `said` verdict
(`verdict.clj:707`) of that judgment about the `about` row. `expect`
names the verdicts that pass.

- **When:** when a verdict is said or overruled about the row.
- **Cost:** none beyond the judgment's own run. The promise reads the
  verdict and never runs the judge.
- **Fail means** the standing verdict is not in `expect`. No verdict yet
  is a pass until `by` passes, and after that a fail.

## 4 · Standing as transitions

**R-P.4.1** Standing is the promise's state, so each change is a logged
transition with an actor (the engine), a reason (the evaluation) and a
basis. Subscriptions, `waymark_history` and the ledger see it with no new
plumbing.

**R-P.4.2** `risk` fires when the check fails before `by` has passed
and at least half the span has been used. `break` fires when the check
fails at or after `by`. `keep` fires when the check passes.

**R-P.4.3** **Damping.** A promise moves out of `broken` only after its
check passes twice in a row at least one sweep apart. It moves back into
`broken` from `kept` within one `by` span of its last `keep` only on a
fail that a second evaluation confirms. A promise that moves `broken →
kept → broken` three times in a day stays `broken`, stamps `flapping`,
and escalates to the owner (§5) instead of waking again.

**R-P.4.4** **One promise, many rows.** With `about {kind, filter}` the
promise keeps a `breaches` list of `{id, since, cause}`, one per `about`
row whose check fails. The promise is `broken` while the list is not
empty. Each row's entry and exit is written by the same transition, with
the row in its input, so history says which row broke and which
recovered.

## 5 · The remedy and the wake

**R-P.5.1** **The debtor.** For each breach the engine resolves
`remedy` to a row (the `about` row, or the row its `via` link names) and
reads that row's `waiting_on`. The debtor is the holder `choose`
(`waiting_on.clj:164`) picks among the seats that hold `remedy.door`
only. Without a promise-named debtor, nothing new is computed.

**R-P.5.2** **The wake.** In `enforced` mode, when the debtor is a
model seat, `break` fires it by its `fire` door as the engine, as
`live_cracks.clj:963-978` does. The text is `wakes/wake-text`'s shape
with three more keys:

```json
{"kind": "change", "id": "…", "action": "break", "from": "merged", "to": "merged",
 "promise": "…", "cause": "not live 2 h after merge", "door": "deploy"}
```

The seat's session walks that one row, as any row-naming fire does. The
idempotency key is `promise:<id>:<row>:<break transition id>`, so one
break wakes once. `wakes/wake-held` (`wakes.clj:1413`) is asked first.
A held wake is retried on the next sweep and is not a new break.

**R-P.5.3** **The escalation.** When the debtor is a person, or nobody,
or the woken seat leaves the breach standing one `by` span later, the
engine proposes one `held_call` (`held_calls.clj:1204`) to the
promise's `owner`. Its choices are the remedy door on the row (when the
person holds it), "retire this promise", and "switch to watch". One
proposal per breach. The owner hears only promises only they can fix
(story 2).

**R-P.5.4** A promise in `watch` mode resolves the debtor and writes it
on the transition, so history shows who would have been woken, and
wakes nobody.

## 6 · Queues from promises

**R-P.6.1** A seat may declare `walk {promises true}` in place of a
filter. Its walk is then the union of the open `breaches` rows of every
enforced promise whose debtor it is, or whose remedy door it holds. The
sit hands those rows, newest break first, each with its cause.

**R-P.6.2** That walk cannot hide a door, because it is derived from the
doors the seat holds. That retires the default-filter trap
(`086dcbfd`, `seats.clj:857-891`) for every seat that moves to it, and
`in-walk?` (`live_cracks.clj:266`) answers a promise walk by membership
in `breaches`.

**R-P.6.3** A hand-written filter stays legal. A seat on a cadence with
a filter keeps its sweep. The promise walk is the default for new seats
only once build step 4 has landed.

## 7 · done-when as promises

**R-P.7.1** A ticket may carry `outcomes`: a list of promise drafts in
§12's syntax, with `about` naming the ticket's change.

**R-P.7.2** On the change's `merge` the engine creates each draft as a
promise in `kept`, about the change, with `by {after merge, within …}`
(the draft states the span its deploy takes), `outcome_of` naming the
ticket, and remedy `{kind ticket, door reopen, via born_from}`. The
remedy is the ticket's EXISTING `reopen` (`factorykit/ask.clj`,
`done | dropped → draft`), which the person and the engine hold; no
door is added (decision, mayor, Oct 31).

**R-P.7.3** When such a promise breaks, the engine walks the ticket's
`reopen` itself, the failed check as the move's why, and retires the
promise: the ticket lands in **draft**, and its groomers restate it
with what reality showed. Its debtor is who holds `reopen`, the
ticket's groomer. When it has been kept for its `by` span plus one
sweep, it retires itself, and the ticket's outcome is proved.

## 8 · Defaults from expected ages

**R-P.8.1** Every kind and non-terminal state that has an expected age
(`live-cracks/expected-age`, `live_cracks.clj:202`) gets one default
promise, written by the maintainer at boot as `default-goals!` writes
goals (`maintainer.clj:587`): about `{kind, filter {state}}`, holds
`{query expect none}` over rows past the age, by `{after <entering
action>, within <age>}`, remedy every door out of the state.

**R-P.8.2** Default promises are born `watch`. The owner enforces them
one kind at a time. While a kind's default is `watch`, the live cracks
pass still handles its aged rows. Once it is `enforced`, the pass skips
that kind and state, so a row is never both a crack and a breach.

**R-P.8.3** `waiting_on.since.expected_seconds` keeps its meaning. It
now reads from the default promise's `within` when one stands.

## 9 · Promises and measures

**R-P.9.1** A promise says one thing must hold, now, for each row. A
measure says how often things held, over a window, across rows. They
meet in the transition log: `promise` declares `breaks`, the `break`
transitions in a window; `keeps`, the `keep` transitions; and
`breach_seconds`, the sum of the `breach_seconds` each `keep` writes —
the seconds since the breaks it cleared — placed at the keep's time.
There is no `kept_rate`: an evaluation that moves nothing is not
logged, by design, so a share of evaluations has nothing to read. The
measures are house-wide over `promise` rows. A goal (`goals.clj`) on
`promise.breaks`, as `<= 3` a week, is judged by the measure sweep;
one goal per promise waits on goals that take a filter.

**R-P.9.2** What belongs to each: the promise owns the debtor, the
wake and the remedy. The goal owns the target and the miss notice. A
goal never wakes a debtor, and a promise never tells "91% this month".

## 10 · Spend

**R-P.10.1** Every fire a promise sends carries the promise id. The
sitting it opens stamps `promise` beside its seat. A probe's power call
stamps it too. Spend per promise is then a sum over sittings and power
calls by `promise`, and spend per kept promise divides it by kept
evaluations (story 3). The stamp is the piece. The rollup is a
dashboard slot over existing fields.

## 11 · What it retires

**R-P.11.1** **Aged-row cracks retire.** Once default promises are
enforced (R-P.8.2), `find-cracks`' aged rows, `route` and `wake!`
(`live_cracks.clj:761-978`) are the promise sweep. `orphans`,
`hiring-gaps` and `balance-drift!` stay: they are not about a row
sitting late.

**R-P.11.2** **The static audit stays.** `cracks/audit`
(`cracks.clj:988`) judges the law, not rows. It gains one finding: a
promise whose remedy door no active seat holds.

**R-P.11.3** **Health breaches become promises about seats.** Each
rule in `health-alert-defaults` (`seats.clj:264`) becomes a default
promise about the `seat` kind, with `holds {query …}` over its recent
sittings and remedy the seat's own `restate`. `health-breach`
(`seats.clj:7687`) stays as the pure function the query calls.
`sitting-health` and `stamp-health` stay: they write the facts the
promise reads.

**R-P.11.4** **Duplicate sitting-judge findings retire.** A judgment
that finds the same seat failing the same way is a promise with
`holds {judgment …}`. The breach list, keyed by row, replaces the
finding filed again each run.

## 12 · The three proving-ground promises

**R-P.12.1** Written as a create input. Ages are in seconds.

```edn
;; vanilla-plus: merged content is live within 2 h
{:name   "merged content is live within 2 h"
 :about  {:kind "change" :filter {:repository "ckopsa/vanilla-plus" :state "merged"}}
 :holds  {:probe {:power "release.status"
                  :arguments {:commit "{data.merge_sha}"}
                  :expect {:path "$.live" :eq true}
                  :every 900}}
 :by     {:after "merge" :within 7200}
 :remedy {:kind "release" :door "apply" :via "release"}
 :mode   "enforced"}

;; Household: the newest messa text is under 24 h old
{:name   "the newest messa text is under 24 h old"
 :about  {:kind "source" :filter {:name "messa"}}
 :holds  {:query {:kind "message" :filter {:source "messa" :created_at_after "{now-86400}"}
                  :expect "some"}}
 :by     {:window 86400}
 :remedy {:kind "source" :door "refresh"}
 :mode   "enforced"}

;; Factory: a submitted change merges or reaches its author seat within 6 h
{:name   "a submitted change merges or reaches its author seat within 6 h"
 :about  {:kind "change" :filter {:state "submitted"}}
 :holds  {:query {:kind "change" :filter {:id "{id}" :state "merged,open,stuck"}
                  :expect "some"}}
 :by     {:after "submit" :within 21600}
 :remedy {:kind "change" :door "merge"}
 :mode   "enforced"}
```

The power `release.status`, the `release` kind with its `apply` door,
and the `message` and `source` kinds are those proving grounds' own.
Each build piece that runs one of these confirms the names on the live
house before it enforces it. The third promise's remedy holder is the
mayor today (`merge`, person-held): it escalates by R-P.5.3 until a seat
holds `merge` or the change's `stall` returns it to its author.

## 13 · Not in this spec

- Promises across houses. `about` names a kind this engine serves.
- Composite checks ("a query AND a probe"). Two promises do it.
- Paying a seat per kept promise. §10 counts. It does not price.
- A promise editor in the client beyond the generated forms.

## 14 · Build plan

Each step is one ticket and one pull request under the 800-line
ceiling, in the epic's Order. Step 1 is this spec.

2. **The smallest end-to-end slice (stories 10, 13, 5).** The `promise`
   kind with R-P.2.1's fields, its states and doors (R-P.2.4, R-P.2.5),
   the `query` check only (R-P.3.2), `risk`, `break` and `keep` as engine
   transitions with R-P.4.3's damping, breaches (R-P.4.4), the debtor
   (R-P.5.1) and the wake (R-P.5.2). No `proposed` state yet. Tests: a
   query promise breaks past `by` and wakes the remedy holder once; a
   pass moves it back after two passes; an error moves nothing.
3. **Debtor acceptance and the person escalation (stories 7, 2).**
   `proposed`, `accept` and `decline` (R-P.2.6), and the held-call
   proposal (R-P.5.3). Tests: a named debtor owes nothing until it
   accepts; a person-held remedy proposes one held call per breach.
4. **Default promises from expected ages (story 14).** R-P.8.1-8.3,
   born `watch`, and the cracks pass skipping enforced kinds. Then the
   promise walk (R-P.6.1-6.3). Tests: one default per aged state; an
   enforced kind is never a crack.
5. **Probes and done-when (stories 11, 9).** R-P.3.3 through the power
   door on a scheduled action, and R-P.7.1-7.3 on merge. Tests: a
   refused power is an error, not a breach; a broken outcome reopens
   its ticket.
6. **Judgment checks, spend and watch mode (stories 12, 3, 4).**
   R-P.3.4, the `promise` stamp on sittings and power calls (R-P.10.1),
   `watch` and `enforce` (R-P.2.3, R-P.5.4), and the `kept_rate` measure
   (R-P.9.1). Tests: a watched break writes its debtor and wakes nobody.
7. **Retire cracks, health breaches and duplicate findings (story
   15).** R-P.11.1-11.4, the audit finding of R-P.11.2, and the three
   proving-ground promises of §12 enforced on the live house.
