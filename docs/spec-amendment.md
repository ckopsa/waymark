# Spec: amendments — how the house changes its own law

Piece 1 of epic `5bcafc95` (ticket `be78b4ee`). This is a spec only, so
no engine code lands with it. It ends with the build plan the mayor
splits into children, as `docs/spec-promise.md` ends. It composes with
`docs/spec-promise.md`: an amendment answers a promise's breaches (§8),
and its verdict reads the promise measures (§5).

> meal-planner rereads the week of Oct 1 every night and plans nothing.
> The promise "a plan exists for the coming week" breaks a third time,
> and the engine opens a draft amendment against meal-planner with the
> three breaches attached. The mayor writes the new walk and charter on
> it. The replay reads the last 14 days: under the new walk the seat
> would have met each week's plan row and no stale draft; it says the
> charter's effect on the model is a blind spot. The owner taps adopt
> once. A week later the trial reads two breaks before and none after,
> and the verdict `better` is logged. Nothing reverted, and nobody
> checked back by hand.

Today a rule changes by a raw `restate`. The person writes it, hopes,
and checks back. Nothing says what the change would have done, nothing
judges what it did, and nothing undoes it when it hurts. On Oct 30 the
mayor's own restate of world-keeper dropped its walk filter, and no door
said so. This spec makes a rule change a row, an `amendment`, that is
replayed against history before it lands, tried after it lands, and
reverted as a transition when the trial says it hurt.

---

## 0 · Today

Read at main `4763c91c`. Paths are under `waymark10/src/waymark10/`
unless they say otherwise.

- **The seat restate door.** `server/seats.clj:4777` declares
  `restate` on `active → active`. It states the office again, whole:
  `charter` is required and every field of `restatable`
  (`seats.clj:1617-1627`) is written from the input by `restate-seat`
  (`seats.clj:1649-1678`), which also moves the managed subscriptions
  (:1675-1677). Its guards (`seats.clj:4960-4985`) include
  `walk-not-hidden-by-its-default`, `walk-matches-the-judgment`,
  `opens-no-crack` and `a-crack-waits-on-the-person`. The door carries
  no `:record`, so its input is not retained on the log.
- **A seat never restates itself.** `server/delegation.clj:376`
  `authors-within-the-ceiling` holds a seat's restate of a seat whose
  grant it holds as a held call for the person (:403-406), under
  `inv-self`, "Invariant 1: a seat never restates itself or a seat whose
  grant it holds" (:316-317). A person and the engine pass (:384-386).
  `approved-hold?` (:138) lets the replay of an allowed held call
  through.
- **A restate already replays one thing.** `seats.clj:3977`
  `cracks-opened` reads the active seats and the door subscriptions,
  builds the seats as the write would leave them
  (`as-it-would-stand`, :3990), and answers the cracks added. A person's
  write gets it as the warning `opens-no-crack` (:4001-4006), acknowledged
  by name; an author's is held by `a-crack-waits-on-the-person` (:4019).
  It judges the law, not the rows: it does not say which rows a walk
  would lose.
- **A walk is a filter read off the row.** `seats.clj:632` `walk-filter`
  answers the scope entry's filter the walk names, or nil for the kind's
  default; `walk-filters` (:643) answers a union. `live_cracks.clj:383`
  `walk-admits?` decides whether a seat's walk hands it one row, by that
  filter, a judgment's `queue`, or the promise breaches it keeps
  (:405-407).
- **Who holds a row is computed.** `server/waiting_on.clj:164` `choose`
  picks a woken model seat, then one on a cadence, then a source, then a
  person; a seat counts only when its walk admits the row (:170-174).
  `debtor` (:187-200) is the same choice over a promise's remedy door.
- **The promise restate door.** `server/promises.clj:346-353` declares
  `restate` on every open state, `:record true`, input `name`, `holds`,
  `by`, `remedy`, `debtor`; `restate-promise` (:202) merges them and
  sends a changed debtor back to `proposed`. Standing moves only by the
  engine: `risk`, `break` and `keep` (:364-380) carry
  `the-engine-judges-it` (:70-75), which admits a `:system` principal
  only.
- **What the log keeps.** `server/store.clj:166-179`
  `append-transition!` writes each move with `:judgment` only for a kind
  declaring `:retain {:judgment true}` and `:after`, the document as the
  write left it, only for `:retain {:data true}`
  (`server/decision.clj:158-165`). `:record true` retains the validated
  input (`resource.clj:114-115`). Neither `seat` nor `promise` declares
  `:retain`; most kinds keep no after-image (ticket `2c1d0918`).
  `server/history.clj:29-41` names the three answers a read of the past
  gives: `recorded`, `before_the_record`, `not_retained`.
- **The log reads.** `store.clj:180` `transitions` (by kind, row and
  cursor), `store.clj:204` `transition-times` (the `at` of each
  `<action>` on a kind in a window) and `store.clj:212`
  `transition-marks` (the same with each row's id, and `:after` where it
  is retained).
- **Promises wake their debtor.** `promises.clj:741` `debtor-of` reads
  the remedy row's `waiting_on`; `wake!` (:775) fires a model seat as the
  engine; `escalate!` (:817) proposes one held call to the owner. In
  `watch` mode a break writes its debtor and wakes nobody (R-P.2.3,
  `docs/spec-promise.md:138`; R-P.5.4, :282).
- **A person's choice exists.** `server/held_calls.clj:1242-1246`
  declares the `propose` create; the person answers with `allow
  {choice}` (:1248).
- **Measures and their honesty.** `promises.clj:333-342` declares
  `breaks` and `keeps` (a count of `transition:break` and
  `transition:keep` over a week) and `breach_seconds` (a sum read at
  `keep`). `server/maintainer.clj:974` `sweep-measures!` writes the
  readings; `measures.clj:315-324` `verdict` answers `unjudged` past the
  read cap or below `:at-least`. `server/measure.clj:42-45` `read-cap`
  is 10 000 rows: past it a number is refused, never truncated.
- **A law sweep exists for measures.** `maintainer.clj:1001`
  `reread-sealed` reads a proposed measure over the last sealed window
  beside the old reading (`docs/spec-measures.md` §7). It is the one
  place today that shows a law change's effect on history before it
  ships.

So every piece of an amendment exists except the amendment: a row that
holds the change, what it would have done, what it did, and the way
back.

## 1 · The decision in one paragraph

**An amendment is a row; it wraps one restate of one row; replay comes
before adoption, a trial after, and a revert is a transition.** An
`amendment` names the promise breaches it answers and one change: a
patch to one `seat` or `promise` row through that row's own `restate`
door. On create or edit the engine replays the change over the last 14
days of the log and writes what it would have changed, and every blind
spot by name. The owner adopts it with one tap; adoption runs the
target's restate under the owner's own principal, so every guard on that
door judges it as it judges the owner today, and the amendment stores the
exact prior values. The engine then compares the answered promises'
breaks and breach seconds over a matched window before and after, and
writes a verdict. An amendment is born `watch`: a `worse` verdict is
logged and nothing reverts. Only where the owner armed revert for that
kind of amendment does the engine restore the prior rule, once, as a
transition on both rows.

## 2 · The amendment kind

### Fields

**R-A.2.1** An `amendment` row carries:

| field | written by | what it says |
|---|---|---|
| `title` | filer | a sentence a person reads: "meal-planner walks next week's plan" |
| `answers` | filer, engine | `[{promise, breach}]`: each promise id and the id of its `break` transition. A seat's amendment names at least one; a person's may name none |
| `changes` | filer | `{kind, id, door, patch}`: one target row, its `restate` door, and the fields it changes, by name |
| `before` | engine | the target's values of every patched field, read in the adopt's transaction |
| `after` | engine | the values the adopt wrote, so revert can tell a later hand from its own |
| `replay` | engine | the replay report (§3) |
| `trial` | engine | `{adopted_at, before_window, after_window, readings}` (§5) |
| `verdict` | engine | `better`, `same`, `worse`, `quiet`, `confounded` or `unjudged` (R-A.5.4) |
| `mode` | engine | `watch` or `revert`, copied from the policy at adopt (§7) |
| `owner` | filer | the member who adopts: the house owner unless the filer names another member who owns the target's domain |
| `filed_by` | engine | the principal of the create; `engine` for a self-opened draft (§9) |
| `key` | engine | the self-open key (R-A.9.2), nil for a filed one |

**R-A.2.2** One amendment changes one row. Two rows are two amendments,
so each has its own replay, trial and revert, and a revert never has to
unpick half a change.

**R-A.2.3** The kind declares `:retain {:data true}`, so its own history
keeps every replay and verdict as it stood. It is small and rare; the
cost is a few rows a week.

### States and doors

**R-A.2.4** States: `draft`, `replayed`, `trial`, `settled`,
`reverted`, `withdrawn`. `reverted` and `withdrawn` are terminal.
`settled` is terminal except for the owner's `revert`.

| door | from → to | who holds it |
|---|---|---|
| `create` | → `draft` | the owner; a seat whose grant names `amendment.create`; the engine (§9) |
| `restate` | `draft`, `replayed` → `draft` | the filer or the owner: an edit of `title`, `answers` or `changes`. It clears `replay` |
| `replay` | `draft` → `replayed` | **engine only** |
| `adopt` | `replayed` → `trial` | the owner, a person's tap |
| `settle` | `trial` → `settled` | **engine only** |
| `revert` | `trial`, `settled` → `reverted` | the engine (R-A.6.1), or the owner at any time |
| `withdraw` | `draft`, `replayed` → `withdrawn` | the filer or the owner, with a sentence |

**R-A.2.5** `replay` and `settle`, and the engine's `revert`, carry a
guard of the shape of `the-engine-judges-it` (`promises.clj:70-75`). No
seat replays its own change into `replayed` or judges its own trial.

**R-A.2.6** `adopt` is the owner's even when the filer is a seat with
`amendment.create`. A model seat's `adopt` is not a door in its grant;
the draft waits in `replayed` with the owner as its holder, so
`waiting_on` and in-flight show it as the owner's to tap.

**R-A.2.7** `adopt` refuses stale: when the target row changed after
the replay was read, it answers with the fields that moved, and the
amendment goes back through `restate` to be replayed again. A replay
is of the row as it stands.

## 3 · Honest replay

**R-A.3.1** **What replay is.** Replay answers one question: had the
target row held the new values for the window, what would the engine's
own computations have answered differently? It runs each computation
twice, under the `before` values and under the patched ones, over the
same reconstructed history, and reports the difference. It moves
nothing and wakes nobody.

**R-A.3.2** **The window.** 14 days ending at the replay, by default.
The filer may name 1 to 30 days. A window that starts before the oldest
transition of a kind it reads is cut there and says so (`before_record`,
R-A.3.5).

**R-A.3.3** **What it recomputes.**

- **Walk membership.** For a seat change that touches `walk`,
  `walk_promises` or `judgment`: at each transition in the window on the
  walked kind, which rows `walk-admits?` (`live_cracks.clj:383`) hands
  the seat under each value. The report names the rows each side adds
  and drops, with their state at the time. This is the Oct 30 check.
- **Holder choice.** For the same rows: the holder `choose`
  (`waiting_on.clj:164`) picks under each walk. The report names rows
  whose holder moves, and calls out every row that would move from a
  model seat to a person or to nobody.
- **Promise evaluations, query checks only.** For a promise change that
  touches `holds`, `by` or `remedy`, and for every promise in `answers`
  whose `about` the change touches: each `query` check re-run at the
  times the sweep and `hear!` ran it, against the reconstructed rows.
  The report counts the breaks and keeps each side would have logged,
  and the debtor `debtor` (`waiting_on.clj:187`) would have named.
- **Guard refusals, where the input was kept.** For each logged move
  in the window through a door whose guards read a patched field, and
  whose input was retained (`:record`, or `:retain {:judgment true}`):
  would those guards refuse it now? The log holds moves that happened,
  so replay can say a move would now be refused. It cannot say that a
  move once refused would now pass, and says so (`refusals`).

**R-A.3.4** **Reconstructing rows.** A row's state over the window is
read from the `from` and `to` of its transitions (`store.clj:180`),
which every kind logs. Its fields come from `:after`
(`store.clj:212-219`) where the kind retains data, and from the row as
it stands now otherwise. A filter on a field the kind does not retain
is therefore judged against today's value, and the report says so for
that kind (`current_data`).

**R-A.3.5** **What replay cannot see.** Every report carries `blind`, a
list of tokens, each with the count of moments it touched. The tokens
are:

| token | why replay cannot answer it |
|---|---|
| `model_judgment` | a changed `charter` or `instructions` changes what a model does; no log says what it would have done |
| `power_calls` | an outside tool's answer is not on the log |
| `probes` | a probe check reads the world at that moment (R-P.3.3) |
| `judgment_checks` | a judgment check is a model's verdict (R-P.3.4) |
| `outside_events` | mail, webhooks and people who would have acted differently |
| `current_data:<kind>` | the kind keeps no after-image, so fields are today's (R-A.3.4) |
| `before_record:<kind>` | the window starts before the kind's oldest transition |
| `refusals` | moves once refused are not on the log (R-A.3.3) |
| `capped` | the replay stopped at the cost cap (R-A.3.6) |

A change of `charter` alone replays as an empty difference with
`model_judgment` in `blind`. An empty difference with a blind spot is
not "no effect": the adopt screen shows `blind` above the difference,
and the trial (§5) is what judges a model's change.

**R-A.3.6** **The cost cap.** Replay reads at most `measure/read-cap`
(`measure.clj:42-45`, 10 000) log rows across every kind it touches.
Past the cap it stops at the oldest moment it reached, answers the
difference over the part it read, and names `capped` with the moment it
stopped. It never answers a partial number as a whole one. `adopt` is
not refused on a capped replay; the owner sees the cut.

**R-A.3.7** **The report.**

```json
{"window": {"from": "2026-10-18T00:00Z", "until": "2026-11-01T00:00Z"},
 "rows_read": 4120,
 "walk":     {"added": [{"kind": "plan", "id": "…", "state": "planned"}], "dropped": []},
 "holders":  {"moved": [], "to_person": [], "to_nobody": []},
 "promises": {"<promise id>": {"breaks": [2, 0], "keeps": [1, 2]}},
 "refusals": {"would_refuse": 0},
 "blind":    [{"token": "model_judgment", "moments": 1},
              {"token": "current_data:plan", "moments": 14}]}
```

Each pair is `[before, after]`.

## 4 · Adoption

**R-A.4.1** **The apply step is the target's own door.** `adopt` reads
the target row, writes `before`, overlays `patch` on the row's current
values to make the door's whole input (the seat restate states the
office whole, `seats.clj:4780-4785`), and invokes the target's
`restate` under the owner's principal, in one transaction with the
amendment's own move. Every guard on that door judges it as it would
judge the owner's own restate. A refusal refuses the adopt with the
door's own sentence and moves nothing.

**R-A.4.2** The warnings the replay already showed are acknowledged by
the adopt: `opens-no-crack` and the replay warning of R-A.8.3. Any
other warning refuses the adopt, so the owner reads it.

**R-A.4.3** `after` is the patched fields as the restate wrote them,
read back in the same transaction.

## 5 · Trial and verdict

**R-A.5.1** **The matched window.** The trial length `T` is the longest
`:over` of the measures that judge (one week for the promise measures,
`promises.clj:333-342`). The after window is `[adopted_at, adopted_at +
T)`. The before window is the `T` that ends at `adopted_at`, aligned in
the governing work calendar's zone as readings are
(`maintainer.clj:974-988`). When that span overlaps another amendment's
trial on the same target, it moves back to end where that trial began,
at most 30 days back; past that the verdict is `unjudged`, "no clean
window before".

**R-A.5.2** **What judges.** Only the promises in `answers`, read
from the log:

- `breaks`: the count of their `break` transitions in each window
  (`store.clj:204`).
- `breach_seconds`: the seconds they stood `broken` in each window,
  computed from their `break` and `keep` transitions clipped to the
  window. A breach still standing at the window's end counts to the end.
  The declared `breach_seconds` measure sums at `keep`
  (`promises.clj:339-342`) and would read an unkept breach as zero, so
  the trial does not read the measure's sum.

An amendment that answers no promise (a person's, R-A.2.1) is judged by
nothing: its verdict is `unjudged`, "answers no promise", and it settles.

**R-A.5.3** **What counts as worse.** `worse` when the after window has
more breaks than the before window, or the same breaks and more than
1.25 × the breach seconds. `better` when it has fewer breaks, or the
same breaks and at most 0.75 × the breach seconds. `same` otherwise.

**R-A.5.4** **The other verdicts.**

- `quiet`: no break and no keep of an answered promise in either
  window. A quiet window is no evidence: it is neither better nor
  worse, it reverts nothing, and the amendment settles with `quiet`
  (ticket `d4109ee8`, as `measures.clj:315-324` answers `unjudged`
  below `:at-least`).
- `confounded`: another hand restated a patched field of the target
  during the after window (R-A.6.3). The trial cannot say whose change
  the window measured, so it reverts nothing.
- `unjudged`: an answered promise was retired or sat in `proposed`
  during the after window, or the log read was over the cap. The
  reason rides the verdict.

**R-A.5.5** At `adopted_at + T` the engine writes `trial.readings` and
`verdict` and moves the amendment by `settle`, or by `revert` under
R-A.6.1. The verdict is a logged transition either way, so history and
subscriptions see it.

## 6 · Revert

**R-A.6.1** **When the engine reverts.** Only in `mode revert`, only on
the verdict `worse`, and only once: the engine's `revert` is keyed
`amendment:<id>:revert`. Every other verdict settles.

**R-A.6.2** **What revert restores.** Exactly `before`, through the
target's own `restate` door, overlaid on the target's current values as
R-A.4.1 builds the input. The engine's revert invokes it as the engine
under the amendment's id; the owner's revert invokes it as the owner. It
writes one transition on the target and one on the amendment. Revert is
idempotent: a field already holding its `before` value is left as it is,
and a revert of a row whose every field already holds `before` writes
the amendment's move and no restate.

**R-A.6.3** **Someone else changed the field.** For each patched field,
revert compares the target's value with `after`. A field still holding
`after` is restored to `before`. A field holding anything else was
changed by another hand during the trial; revert leaves it, and lists it
under `conflicts` on the amendment's transition. When every field
conflicts, revert writes no restate. The trial that saw such a change
reads `confounded` (R-A.5.4), so the engine never reverts over another
hand; only the owner's revert meets conflicts.

**R-A.6.4** **A revert that would be held.** When the restore would be
held by a `:hold` guard (a crack it would reopen, for one), the engine
does not route around it: it writes nothing on the target, proposes one
`held_call` to the owner (`held_calls.clj:1242`) whose choices are
"revert as written" and "keep it", and leaves the amendment in `trial`
until the owner answers.

## 7 · Watch first

**R-A.7.1** **Born watch.** Every amendment is adopted in `mode
watch` unless its policy says `revert`. A watched amendment runs its
trial and writes its verdict, and nothing reverts. The owner sees
`worse` in history and in-flight, and taps `revert` if they agree.

**R-A.7.2** **The policy.** An `amendment_policy` row per amendment
kind, where the kind is the target kind and door (`seat.restate`,
`promise.restate`), holds `auto_revert`, false by default. The owner
flips it by the policy's `arm` and `disarm` doors, person-held, one
tap per kind. `adopt` copies `mode` from the policy in force, so a
later flip never changes an amendment already on trial.

**R-A.7.3** Self-opened drafts (§9) stay off until the promise epic
(`2993ea03`) meets its done-when. Filed amendments and replay do not
wait for it.

## 8 · Composition

**R-A.8.1** **Amendments answer breaches.** `answers` names `break`
transitions of promises. A seat files an amendment against the promises
it owes: its grant's `amendment.create` admits a target only when the
seat is a debtor written on one of the named breaches (R-P.5.1), or the
owner names it.

**R-A.8.2** **A restate becomes an apply step.** The seat and promise
restate doors are the only apply steps, and an amendment never grows a
second way to write a seat or a promise. A new restatable kind joins by
naming its door in the amendment's allowed targets, one line, and a
spec of its own lane (R-A.10.2).

**R-A.8.3** **Every restate is replayed first.** The raw restate doors
stay for the person. Each gains an advisory guard,
`a-restate-says-what-it-drops`, of the shape of `opens-no-crack`
(`seats.clj:4001-4006`): before the write it runs the walk and holder
parts of replay (R-A.3.3) over 14 days, and when the restate would drop
a row from a walk or move a row's holder to a person or nobody, it warns
with the rows named and is acknowledged by name. The Oct 30 restate would
have named the rows it dropped from world-keeper's walk before it landed.
The guard reads under the cap of R-A.3.6 and names `capped` when cut;
it never refuses on a cut.

## 9 · Self-opened drafts

**R-A.9.1** **The pattern.** When one promise logs its third `break`
within 14 days and the same seat is the debtor written on all three, the
engine creates a draft amendment: `answers` the three breaches,
`changes` naming the debtor seat and its `restate` door with an empty
patch, `owner` the promise's owner. A draft with an empty patch cannot
be replayed; the mayor or the owner writes the patch.

**R-A.9.2** **The key.** `key` is `promise:<promise id>:debtor:<seat
name>`. While an amendment with that key is open (`draft`, `replayed`
or `trial`), a further break of that promise with that debtor appends
its breach to `answers` and files nothing. After it settles, reverts or
is withdrawn, the count starts again from the next break.

**R-A.9.3** Off until R-A.7.3 lifts. The engine counts nothing while
it is off.

## 10 · What an amendment may never do on its own

**R-A.10.1** "On its own" is every move the engine makes without the
owner's tap: replay, settle, its revert, and a self-opened draft. None
of them passes a `:hold` guard (`authors-within-the-ceiling`,
`delegation.clj:376`; `a-crack-waits-on-the-person`,
`seats.clj:4019`); a move a held call guards today waits for the
person's tap (R-A.6.4).

**R-A.10.2** **The lane.** `changes.patch` may name only:

- on a `seat`: `charter`, `instructions`, `walk`, `walk_promises`,
  `judgment`, `rows_per_firing`, `cadence_seconds` only to lengthen
  it, and `walk_filter`;
- on a `promise`: `name`, `holds`, `by`, `remedy`.

`walk_filter` is the one thing in `scope` an amendment may touch: the
`filter` of the scope entry whose `kind` is the seat's `walk` (the entry
that already filters, else the first on that kind), one field=value map,
or null to drop it. No seat stores a `walk_filter` field: adopt and
revert write it as that entry of `scope`, through the seat's restate
under the owner, so the person-only guard on a seat's scope (Invariant 1)
judges it. A `walk_filter` that names an entry's `kind`, `actions`,
`args`, `hashed` or `filter`, or a seat with no entry on its walk, is
refused with the raw door `seat.restate` named. The engine never reverts
a `walk_filter` amendment on its own, even in `mode revert`: a `worse`
verdict proposes the revert as a held call to the owner (R-A.6.4).

The create and restate doors refuse any other field with the guard
`an-amendment-stays-in-its-lane`, naming the field and the raw door the
person may use instead. Never: `scope`, `held_for`, `substitute_for`,
`standing_ttl_seconds`, `budget_usd_per_week`, `sitting_budget_tokens`,
`delegates`, `serves`, `domain`, `acts_as_cast`, any scope entry but the
walk's `filter` (no entry added or removed, no `actions`, `args` or
`hashed`, no other kind's entry), a promise's `debtor`
(it needs the seat's accept, R-P.2.6) or `mode`, and any door but
`restate`: no seat is created, parked, unparked, merged or retired by an
amendment.

**R-A.10.3** **Never wider.** No patch names a grant's scope, and the
apply runs under the owner's principal, so an amendment can do nothing
the owner could not. `walk` may name only an entry already in the
seat's scope (`walk-names-a-kind-in-scope`, `seats.clj:4970`).

## 11 · What it retires

- **The check-back by hand.** A charter change today is written, then
  remembered. The trial and its verdict replace the remembering.
- **The silent restate.** R-A.8.3 makes every restate that drops walk
  rows say so, the gap `opens-no-crack` left open (it judges the law,
  not the rows).
- **Nothing in code is deleted.** The raw restate doors stay, so does
  `cracks-opened`; `reread-sealed` stays the measure's own law sweep.

## 12 · Proving grounds

**R-A.12.1** **meal-planner's charter fix (`0b07bfad`).** The household
has no promise yet, so the amendment needs one first: "a plan exists
for the coming week", a `query` promise about mealplan10's `plan`
(`workqueue10/src/mealplan10/resources/plan.clj:312`) whose
`start_date` (filterable by range, :334) falls in the coming week and
whose state is `planned` or `active` (:314), `by {window 1 day}`, the
remedy meal-planner's door that plans. The amendment patches
meal-planner's `walk`, `walk_filter` and `charter`. Replay can show the walk part: the
plan rows the new walk would hand it and the stale October draft it
would drop. `plan` retains judgment and not data (:327), so the report
names `current_data:plan`, and the charter part names `model_judgment`.
The claim "would have produced a plan each week" is the trial's to
make, not the replay's.

**R-A.12.2** **The seat health retune (`18137dd5`, `a7cf5d27`).** Health
thresholds are code today (`health-alert-defaults`, `seats.clj:264`;
`health-breach`, :7731); the per-seat `health_alerts` field was dropped
(`seats.clj:1799-1805`). So no amendment can retune health today. It
can once R-P.11 retires health breaches into promises: the retune is
then a `promise.restate` of `holds` on the health promise, replayed over
the sittings' outcomes, which are query checks. This proving ground
waits on that step.

**R-A.12.3** **One deliberately bad amendment.** In a test house: a
promise "every open ticket is walked within a day", a seat that keeps it,
and an amendment whose `walk_filter` narrows the seat's walk to
`priority=0`. Replay names the dropped rows and the rows that move to a
person. The owner arms `seat.restate`, adopts anyway, the trial reads
more breaks, and the verdict is `worse`. A `walk_filter` is never
reverted by the engine: the verdict proposes the revert as one held call
to the owner, and the owner's tap puts the seat's walk filter back to
the exact prior value, both rows log the move, and a second revert finds
nothing to do.

## 13 · Not in this spec

- Changing code. An amendment changes rows; a change to declarations
  is a ticket and a pull request.
- Replaying model behavior. No simulation of what a model would have
  done; `model_judgment` is named and left to the trial.
- Several rows in one amendment (R-A.2.2).
- Amendments to grants, budgets or seat lifecycles (R-A.10.2).

## 14 · Build plan

Each step is one ticket, one pull request and one module, under the
800-line ceiling with its tests. The promise epic's steps 4 to 7 came
back over the ceiling, so these are cut finer: no step both declares a
kind and computes over the log. Step 1 is this spec.

2. **The kind (`server/amendments.clj`).** R-A.2.1-2.7: fields, states,
   doors and their guards, `an-amendment-stays-in-its-lane`
   (R-A.10.2), engine-only walls. `replay` is a stub that writes an
   empty report with `blind ["capped"]`. No adopt yet. Tests: a seat
   cannot replay or settle; a patch naming `scope` is refused with the
   raw door named.
3. **History reconstruction (`server/replay.clj`).** R-A.3.2, 3.4, 3.6:
   each row's state timeline over a window from `store/transitions`,
   fields from `:after` or today's row, the cap, and the `blind` tokens
   `current_data`, `before_record` and `capped`. Pure reads. Tests: a
   retained kind reads its past field; an unretained one names
   `current_data`; the cap cuts and says where.
4. **Walk and holder replay (`server/replay_walks.clj`).** R-A.3.3's
   first two bullets over step 3's timelines, with `walk-admits?` and
   `choose`. Tests: a walk narrowed to nothing drops every row; a row
   whose only walker leaves moves to the person.
5. **Promise replay (`server/replay_promises.clj`).** R-A.3.3's third
   bullet: query checks re-run at the logged times; probe and judgment
   checks named blind. Tests: a looser `by` logs fewer breaks; a probe
   promise answers `probes` and no count.
6. **Guard replay (`server/replay_guards.clj`).** R-A.3.3's fourth
   bullet over retained inputs, and `refusals` in `blind`. Tests: a
   recorded move a new value would refuse is counted.
7. **Adopt (`server/amendments.clj`).** R-A.4.1-4.3 and R-A.2.7: the
   real `replay` door calling steps 3-6, `adopt` through the target's
   restate under the owner, `before` and `after`. Tests: a guard on the
   seat restate refuses the adopt with its own sentence; a stale target
   refuses.
8. **Trial and verdict (`server/amendment_trial.clj`).** R-A.5.1-5.5
   on a sweep beside `promises/sweep!`: matched windows, clipped breach
   seconds, every verdict, `settle`. Tests: a quiet pair reads `quiet`;
   an unkept breach counts to the window's end; an overlapping trial
   moves the before window.
9. **Revert (`server/amendment_revert.clj`).** R-A.6.1-6.4: the
   owner's and the engine's revert, conflicts, idempotence, the held
   restore. Tests: a conflicted field is left and named; a second
   revert writes no restate.
10. **Policy (`server/amendment_policy.clj`).** R-A.7.1-7.2: the
    `amendment_policy` kind, `arm` and `disarm`, and `mode` copied at
    adopt. Tests: a watched `worse` reverts nothing; an armed one
    reverts once.
11. **The seat restate says what it drops (`server/seats.clj`).**
    R-A.8.3 on the seat restate door, calling step 4. Tests: a restate
    that drops a walk filter warns with the rows named; acknowledged,
    it lands.
12. **The promise restate says what it changes (`server/promises.clj`).**
    R-A.8.3 on the promise restate door, calling step 5.
13. **Self-opened drafts (`server/amendment_drafts.clj`).** R-A.9.1-9.3,
    off by default. Tests: a third break opens one draft; a fourth
    appends to it. Lands after `2993ea03` meets its done-when.
14. **Proving grounds.** R-A.12.1 and R-A.12.3 on a clone of the
    household and a test house; R-A.12.2 after the promise epic's
    step 7.
