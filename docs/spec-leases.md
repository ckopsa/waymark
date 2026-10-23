# Spec — leases: who holds a row is recorded, and a sitting is a lease

**Thesis.** The engine already knows how to say *"this is mine until
then"*: a grant has `expires_at` and is reminted when less than half of it
is left (`standing-seat-grant`, mcp.clj). It says the same thing about rows
in four other spellings: a sitting's `walked_rows`, the release grace
(`graced-rows`), the silent take-over (`take-over-silent!`) and the idle
sweep (`sweep-sittings!`). None of them is a row, so none of them is in the
audit. This spec makes **holding** one primitive — a `lease` row with a
holder, a subject and an expiry — and makes the sitting itself a lease, so
"who holds this and until when" is a read, and "how did it end" has one
answer.

Design pass for epic 215e28f4 (ticket 30cdaac2). No code lands with this
document; its build tickets are filed from it.

## Epistemic status

The traces of 2026-10-23 are the evidence: 69 `store/update-data!`
call sites that write a row without a transition, and an idle sitting that
held org_plan bba84cf0 with nothing recording or releasing the claim until
the sweep. The functions named below were read for this pass, and so was
each of the 69: section 4 classifies them site by site, with the rule that
judges each. This revision answers the review of 2026-10-23; what the
first draft left to confirm is answered at the end (*Settled*).

## What exists

- `seats/claim-rows-atomically!` — reads the seat row FOR UPDATE, then the
  asked-for walk rows FOR UPDATE **in the order of their ids**, then every
  open sitting of the seat, the closed ones still in grace
  (`graced-rows`) and the other seats' claims (`other-seats-rows`). All or
  nothing: if any asked-for row is taken, nothing is written. Otherwise it
  writes `walked_rows` onto the sitting with `update-data!` — no
  transition, no log line.
- `seats/graced-rows` — a CLOSED sitting still holds its rows for the
  seat's `release_grace_seconds`, unless it was closed by `takeover` or
  the row's work is over. The hold is inferred from `ended_at`; it is
  written nowhere.
- `seats/take-over-silent!` — at a sit, the seat's OTHER open sittings
  that hold rows and made no call for `claim_silence_seconds` are closed
  through their own `close` door (`:within :takeover`), so the new sitting
  can be handed their rows. The silence is judged again under the
  close's own row lock by the guard `still-quiet-for-the-sweep`
  (`g/defguard` at seats.clj:1250, declared on the sitting's `close` and
  `abandon`), which compares `last_call_at` with the `:idle-seconds` the
  take-over passes in `:within`.
- `definitions/sweep-sittings!` — four rules over `mode`, `tallied_at`,
  `started_at`, `last_call_at` and two of the seat's cadences. It ends a
  sitting two ways: **close** (interactive, tallied: the bill is the last
  tally) and **abandon** (everything else: no bill). `mark-silent!` and
  its undo write the sweep's note with `update-data!`.
- `seats/bump-counter!` and `seats/stamp-call!` — MAINTENANCE writes on
  an open sitting: `transitions`/`refusals` counts, `last_refusal`,
  `last_call_at`, `last_answer_at`. Document only, version untouched.
  `last_call_at` is the only thing that tells the sweep a fired run is
  alive.
- `mcp/standing-seat-grant` — the half-life rule: a grant with less than
  half of `standing_ttl_seconds` left is not standing, and the sit mints
  afresh.
- The sit answer's `withheld` list (*"another open sitting of this seat
  holds it"*, mcp.clj) and the cracks audit (`routes/seats/cracks-for`,
  `server/cracks`) each read holding through the seat code above.

## 1. The lease primitive

**Decision: `lease` is a kind**, served by the engine like `grant` and
`sitting`, not a declared property of each walked kind.

A property (`:leasable true` on `ticket`, `org_plan`, …) would put
`held_by` and `held_until` on every walked row, and every renewal would be
a write on a row people edit — bumping its version, fencing their
`if_version`, and showing in its history as noise. A kind keeps the
subject row untouched: holding is about the row, not in it. It also makes
the history question trivial: `waymark_history` on the lease answers who
took it, who renewed it, and how it ended.

### Fields

| field | meaning |
|---|---|
| `holder_kind`, `holder_id` | what holds it. In this epic always a `sitting`; the shape admits any row with an end. |
| `subject_kind`, `subject_id` | the row held. |
| `seat` | the seat whose walk took it (denormalised: the claim reads by seat). |
| `acquired_at` | when it was taken. |
| `expires_at` | the lease's own end, or null when it **follows its holder** (below). |
| `ended_at`, `end_reason` | `released`, `expired`, `finished` (the subject's work is over), `taken_over`. |

### States and doors

```
           acquire                release / finish
  (none) ─────────▶ held ─────────────────────────▶ ended
                     │  ▲                           ▲
                     │  └── renew (half-life rule)   │
                     └──────────── expire ──────────┘
```

- `acquire` — the create verb. Refused when a **live** lease on the same
  subject exists (`subject_held`), naming its holder.
- `renew` — moves `expires_at`; refused past the holder's cap (§3).
- `release` — the holder lets go; takes an optional `grace_seconds`, which
  sets `expires_at = now + grace` and leaves the lease `held` until then
  (this is `graced-rows`, written instead of inferred).
- `finish` — the subject's work is over (`machine/work-over?`); ends at
  once, no grace.
- `expire` — engine-only, run by the sweep and by `acquire` itself (below).

**Live** means: state `held`, and `expires_at` in the future, or
`expires_at` null and the holder live. A lease that follows its holder is
live exactly as long as the sitting is.

### One subject, one live lease

A partial unique index on `(subject_kind, subject_id) WHERE state = 'held'`
is the law; the doors are its explanation. A lease past its `expires_at`
but not yet swept still occupies the index, so `acquire` **expires stale
leases on its subjects in the same transaction** before inserting. Nobody
waits for the sweep to free a row.

### The lock order

One order, for every write that touches holding — the claim, `renew`,
`expire`, the sweep, a sitting's `close` and a subject's `finish`:

> **seat → subjects → sittings → leases**, each tier sorted by id.

A write may skip tiers but never takes one after a later one: `renew`
takes only its sitting; the sweep's `expire` takes the sitting, then its
leases; a `close` takes the sitting, then its leases; a subject's
`finish` takes the subject, then its lease. No two writes lock in
opposite orders, so none deadlocks another.

The order relies on the spend spec: a spend posting never locks the seat
or the domain row (docs/spec-spend.md §2, decision 8dc00d43 #6). So
`expire`, which posts the floor while it holds a sitting's lock, takes no
tier above the sitting, and `report_usage` takes none either.

`claim-rows-atomically!` keeps its shape and its first two steps. Inside
one `store/with-tx`:

1. the seat row, FOR UPDATE (one claim of a seat at a time);
2. the subject rows, FOR UPDATE, **sorted by id** (two seats asking for one
   row run one at a time);
3. the held leases on those subjects, read. One whose holder reads live
   and is not this sitting → `{:claimed? false :taken …}`, nothing
   written;
4. **the stale holders, under their own lock.** The sittings that hold
   those leases and read expired are locked FOR UPDATE, sorted by id,
   with this claim's own sitting among them. Under that lock each one's
   `expires_at` is read again:
   - still past → the claim runs that **sitting's** `expire` (§3), which
     ends every lease it holds — these included — and runs what a close
     runs. The claim never expires the lease alone: a sitting whose rows
     were taken is ended, not left open holding nothing;
   - renewed since step 3 (a call landed before the lock) → the row is
     taken after all: `{:claimed? false :taken …}`, nothing written, and
     the renewal stands;
   - ended since step 3 (it closed before the lock) → nothing is expired
     here; its leases are re-read in step 5, and one still live there (a
     release with grace) answers `{:claimed? false :taken …}`, not a
     throw;
5. the leases on those subjects, FOR UPDATE. A lease past its own
   `expires_at` (released with grace) is expired on its own — its holder
   has already ended — and so is any held lease whose holder has ended,
   whatever the end; then one `acquire` per subject, holder = this
   sitting, `expires_at` null.

The `renew` in the race takes only the sitting's row lock (§2), the lock
step 4 takes, so the two are ordered by it: the renewal lands first and
the claim reads it, or the claim's `expire` lands first and the renewal
is refused `lease_expired`.

The expires and acquires are ordinary transitions run in the claim's
`tx` through `inv/invoke-in!` and `inv/create-in!` (build ticket 1; see
*Settled*), so all or nothing still holds: a refused acquire rolls the
claim back, its expires included. Steps 3–5 replace the three readings
`walked_rows` + `graced-rows` + `other-seats-rows` with one query on
`lease`.

`walked_rows` stays on the sitting for one release as a **derived** field
(the ids of its live leases, written by the same claim) so readers can
move one at a time; build ticket 6 removes it.

## 2. Renewal without a transition per call

**Decision: the half-life rule, on the holder only.**

Row leases follow their holder (`expires_at` null), so there is exactly
one clock to renew per run: the sitting's. Each tool call that today runs
`stamp-call!` or `bump-counter!` judges `half-left?` in the write it
already makes, and acts after that write commits:

> if `expires_at − now < lease_seconds / 2`, run the sitting's `renew`
> door in its own transaction once the stamp has committed; otherwise do
> nothing extra.

- **Never inside the stamp's transaction.** `stamp-call!` and
  `bump-counter!` hold the sitting's row FOR UPDATE (seats.clj:5807-5810,
  5844-5847), and `renew` takes that same lock: invoked there it would
  wait on its own transaction. The stamp only judges; `renew` runs after
  the commit.
- **An ended sitting never renews.** `report_usage` on a `closed` or
  `abandoned` sitting (spend's `:allow-after-end`) posts and renews
  nothing.
- **A lapsed lease is not renewable.** A sitting whose `expires_at` has
  passed is refused `renew` with `lease_expired`, whether or not the sweep
  has expired it yet; the session sits afresh.

A run of steady calls logs at most two renewals per lease length — a
one-hour lease (the default `sitting_idle_seconds`) over a three-hour run
logs about six `renew` transitions instead of hundreds of stamps. This is the rule
`standing-seat-grant` already applies to grants; the two should share one
helper (`half-left?`).

`last_call_at` stays as a maintenance stamp (seat health and the close's
report read it), but **nothing decides on it any more**: liveness is
`expires_at`.

A lease with its own `expires_at` (one released with grace) is never
renewed; it runs out.

## 3. The sitting is a lease

The sitting gains `expires_at` and two doors, `renew` and `expire`. The
four sweep rules become one:

> an open sitting whose `expires_at` is past is expired.

### Lease length

| mode | `lease_seconds` | cap (renew refused past it) |
|---|---|---|
| fired | the seat's `sitting_idle_seconds` | none |
| interactive | the seat's `sitting_idle_seconds` | none |

One length for both modes: `sitting_idle_seconds`, the clock today's
silence rule reads for a fired sitting (`stale-since? last-call now idle`
in `definitions/sweep-sittings!`) and the idle close reads for an
interactive one. It is **not** `claim_silence_seconds`: that one governs
only the take-over of a row-holding sibling at a sit
(`seats/take-over-silent!`), and as a lease it would expire any fired run
quiet past the take-over window — a long build, a long test wait — that
today lives. `claim_silence_seconds` is read by nothing once the take-over
goes; the seat schema keeps the field for one release, so no seat is
restated.

`expires_at` at birth is `started_at + lease_seconds`; a renewal sets
`now + lease_seconds`. An interactive sitting's `report_usage` (the spend
spec's door, today's `tally`) renews as a call does, on an open sitting
only (§2): the idle close reads
`tallied_at` today, and the lease must not end a person's session that
reports and makes no engine call.

**The two-cadence outer bound is not kept.** Today's fourth rule
(`started_at` older than two cadences) ends a fired sitting only when it
is *also* quiet for `sitting_idle_seconds` — `quiet?` is a clause of every
rule — so it never ends a sitting the lease would keep: it is subsumed.
A cap that refused `renew` past two cadences would end a busy run that
today lives, the failure the review names. A run that loops forever
making calls is a spend problem, and the spend walls stop it. R-10.8
holds: no sitting is judged by a cadence.

### One ending

`expire` is the one way the engine ends a sitting nobody ended. It takes
the sitting's row lock, then its leases' (the lock order above), and it:

1. ends the sitting in state `closed` with `closed_by: "expiry"`;
2. takes the bill **from the spend spec**: `expire` itself posts spend's
   floor (docs/spec-spend.md §4, decision 8dc00d43 #3 and #5). For each
   count it posts the price of `max(0, transcript − reported)`, carried
   by the `expire` transition, each posting with
   `basis: "estimated_from_transcript"` (the basis is per posting, spend
   §3). What `report_usage` already
   posted stands. `bill_basis` is added by spend ticket 6, not here.
   There is no `abandon` from the sweep any more;
3. ends every lease the sitting holds with `end_reason: expired` — no
   grace, because an expired holder is not mid-call;
4. **runs what a close runs today.** Today the `:wakes` consumer hears a
   sitting's `close` and `abandon` (`wakes/handle-transition!`) and runs
   `rewake-undelivered!` and `release-for-sitting!`: the pending-wake
   release, and `judge-count-on-close`, which judges the seat's count
   wakes once more. Events 4 stops the `:wakes` consumer and moves that
   work into the `:sitting-end` member of the consumer family, which
   hears `close`, `abandon` and `expire` on `sitting`
   (docs/spec-events.md E-5.4). Ticket 3b follows events 4 and hooks
   `expire` into `:sitting-end`, not `:wakes`, so a seat whose sitting
   expired is woken exactly as one whose sitting closed. With this order
   there is no window in which a sitting's end goes unheard: before
   events 4 there is no `expire` and `:wakes` hears the other two; from
   events 4 on, `:sitting-end` hears all three.

A sitting that ends by its own `close` (the Stop hook) runs `release` on
its leases with the seat's `release_grace_seconds`, which is the only case
the grace was built for. A close `:within :takeover` releases with grace
0, as `graced-rows` does today. A sitting ended by `abandon` (a person's
door) releases its leases with grace 0 (build ticket 2).

### "Open" means a live lease

Between its `expires_at` and the sweep's pass a sitting is still in state
`open`, and nothing may count it as open for that span.
`max_open_sittings` and the damper count sittings whose `expires_at` is in
the future, not sittings in state `open`. The readers are
`seats/open-sitting-for-seat`, `seats/open-sitting-count` and
`seats/busy-sitting-count`, read by `wakes/damped?`, `wakes/slots` and
`wakes/free-slot?`; each adds `expires_at > now` to `state = open`. The
resit (`open-sitting-for-grant`) does the same, so a connector that comes
back after its lease ran out is sat afresh, not handed a sitting the sweep
is about to end.

### Credentials follow the lease

- **Feed token and inbox key** answer exactly as long as the sitting's
  lease is live, and renew with it. `inbox-sitting-by-key` and
  `feed-seat-by-token` read sittings whose `expires_at` is in the future
  (today: state `open`). A feed token keeps no end of its own:
  `feed-token-seconds`, the per-token end in `with-feed-token` and the
  rotation in `renew-feed-token!` go, and the key check door answers the
  sitting's `expires_at` as the token's and mints no `next_token`. A
  renewal of the sitting renews every token and key it was issued; its
  expiry stops them all in the same transaction.
- **The fire key** is the window *before* the lease. Its `expires_at` is
  `fired_at + sitting_idle_seconds` (`seats/hold-fire-key!`, the
  `fire-keys-schema` entry): how long a fired run has to sit. The sit
  spends it (`spend-fire-key!`) and births the sitting, whose own
  `expires_at` starts at `started_at + sitting_idle_seconds`. The two
  windows are one length, back to back, and do not overlap: an unspent
  key has no lease, and a lease has no unspent key. The key's `expires_at`
  does not move with the lease. The trace a spent key leaves on the
  sitting (`fire_key_hash`, `keep-fire-key!`) answers a resit while the
  lease is live. The missed-fire sweep (`wakes/sweep-missed!`) still reads
  the unspent key's `fired_at` and `expires_at`, unchanged.

### What goes away

- `take-over-silent!`: a silent sitting's lease runs out on its own; the
  next claim's step 4 expires it under the claim's lock. The new sit no
  longer closes its siblings.
- `mark-silent!`, its undo, and the guard `still-quiet-for-the-sweep`
  (seats.clj, on the sitting's `close` and `abandon`): there is no
  "silent since" to judge twice. `renew` and `expire` both take the
  sitting's row lock, so a renewal racing the sweep either lands first
  (and the sweep finds the lease live) or loses (and is refused
  `lease_expired`).
- `graced-rows`: grace is a lease's `expires_at`.
- The `abandon` leg of `sweep-sittings!`. The `abandon` door itself stays
  for a person's use.

## 4. Which silent writes become transitions

**The rule a future write is judged by:**

> A write is a **transition** when another row's fate, or a principal's
> authority, is decided by what it wrote — when *"why did this happen to
> that row?"* cannot be answered without it. It is **maintenance** when
> only the row itself reads it back, and losing it changes no decision
> but a count.

Corollaries:

- Who holds what, who may act, what runs next and when something ended
  are transitions.
- Counters, last-seen stamps, caches of another source, appended lines,
  and the seed's own birth writes are maintenance.
- A maintenance field a guard reads is a smell: either the guard moves to
  a transition's field, or the write becomes a transition.

**Enforcement.** Raw `store/update-data!` outside `store`, `invoke` and
the migration code is forbidden by a declaration-time check (run by
`make check-queue` / `check-factory`). A maintenance write goes through
`store/maintain!`, which takes a reason from a closed set —
`#{:counter :stamp :cache :mirror :append :seed :credential}` — and writes the reason
nowhere but the code: the check reads it, a reader of the call site reads
it. Anything else is a door.

**`:credential` is the one carve-out from "a principal's authority is a
transition".** It is the hash of a key or token, kept beside the
transition it serves (a fire, a sit, a seal), whose authority ends with a
lease or a state, not with the hash. The log keeps no record of a
credential (R-12.37); the transition beside it is the record.

### The 69, site by site

Every `store/update-data!` call under `waymark10/src` outside `store`,
read at the bench's base (`main` 6ab1a25). Line numbers drift; the
function names do not. The trace's first grouping was wrong in two
places: the eight `held_calls` sites are notifier tallies, notice marks
and digests, not a hold's run; and of the five `schedules` sites two
decide the next fire.

**Lease transitions (2) — build ticket 2.** Who holds what.

| site | writes |
|---|---|
| seats `claim-rows!` (8155) | `walked_rows` |
| seats `claim-rows-atomically!` (8203) | `walked_rows` |

**Transitions (11) — build tickets 3a and 5.** What runs next, or who
holds the sitting.

| site | writes | the decision it makes |
|---|---|---|
| seats `claim-sitting!` (5859) | `connector_session`, `claimed_at` | which session may sit on the sitting — holding; written by the sit's transition (3a) |
| wakes `stamp-fired!` (541) | the last fire | `fired-recently?`: the damper holds the next fire |
| wakes `write-pending!` (564) | `wake_pending` | a fire is owed |
| wakes `remember-heard!` (635) | the heard heads | whether a transition wakes the seat again |
| wakes `mark-settling!` (690) | the settle's start | `due-at`: when the next fire runs |
| wakes `hold-at-the-wall!` (784) | the wall mark | the fuel wall holds fires |
| wakes `record-missed!` (2023, the schedule) | `wake_pending` | a fire is owed again |
| wakes `record-unsent!` (2198) | `wake_pending` | a fire is owed again |
| runner_links `rearm!` (741) | `wake_pending` | a fire is owed again |
| schedules `defer-fire!` (2004) | the deferral | when the next fire runs |
| schedules `defer-until-elsewhere-lifts!` (2035) | the deferral | when the next fire runs |

**Removed (2) — build ticket 3b.** definitions `mark-silent!` (1122) and
`unmark-silent!` (1144): §3.

**Credentials (10) — `:credential`.**

| site | writes |
|---|---|
| seats `hold-fire-key!` (6939) | a fire key's hash onto the seat |
| seats `spend-fire-key!` (6990) | the spent key off the seat |
| seats `rewrite-fire-key!` (7020), for `take-fire-key!` / `revoke-fire-key!` | a pull fire's key: taken or revoked |
| seats `keep-fire-key!` (7070) | `fire_key_hash` on the sitting |
| seats `issue-inbox-key!` (7168) | `inbox_key_hash` |
| seats `issue-feed-token!` (7302) | the feed token's hash |
| seats `renew-feed-token!` (7372) | the next token's hash — removed by 3a (§3) |
| wakes `record-missed!` (1997, the seat) | the unspent key off the seat, beside the missed sitting's birth |
| transcripts `issue-key!` (735) | the upload key's hash |
| transcripts `seal!` (1090) | drops `key_hash`, stamps `sealed_at`, beside the `seal` transition |

**Maintenance (44).** Only the row reads it back, or losing it changes
only a count.

| reason | sites |
|---|---|
| `:counter` (9) | seats `bump-counter!` (5799), `add-cancelled-run!` (5911), `count-correction!` (6481), `add-served!` (6536); gate_proxy `note-repeat-calls!` (984); held_calls `tally-row!` (1935), `tell-due!`'s outcome counts (2677); jobs progress (308); walks `frame_count` (453) |
| `:stamp` (14) | seats `stamp-call!` (5830), `stamp-client!` (5887), `stamp-breach-ticket!` (6268), `stamp-walked-nothing!` (8240), `stamp-halted-schedule!` (8262); wakes `stamp-withheld!` (808), `stamp-silent!` (1134); schedules `fire-through-pool!`'s note (2326), `stamp-seen!` (2418); runner_links `seen!` (1021); held_calls `note-at-fault!` (2620), `tell-due!`'s told marks (2654), `no-carrier!` (2752); maintainer `tell-miss!` (925) |
| `:cache` (7) | belief's posterior (181); definitions' blast-radius `measure` (726); maintainer `maintain-row!*` (389), `write-reading!` (836); seats `backfill-health!` (5989), `roll-health!` (6361); schedules `stamp-seat!` (1579) |
| `:mirror` (5) | mirror `refresh!` (1377, 1395), `resolve-refs!` (1619), `resync!` (1910, 1924) |
| `:append` (5) | transcripts `append!` (879, 1009); held_calls `hold-quiet!` (1998), `digest-failed!` (2730), `digest!` (2777) — the member's own `quiet_held` queue |
| `:seed` (4) | seed `org-recorded!` (412), `walk-recorded!` (480), `bound-recorded!` (504), `marker-data!` (517) |

Two judgments the rule had to make:

- **A "told once" mark is a `:stamp`** (`tell-due!`'s `told`,
  `tell-miss!`): losing one tells a person twice, which is a count, not a
  row's fate. A wake's heard heads are a transition, because losing one
  fires a run.
- **Smells left as `:stamp`.** `stamp-call!`'s `last_call_at` stops being
  read by any decision in 3b (§2). runner_links `seen!`'s
  `puller_seen_at` is read by `runner_links/pull-waiting` to hold a fire
  off a pull link no localfire polls: a heartbeat that decides, the same
  shape as `last_call_at`. The check lists it as a known smell; whether
  the puller holds a lease on its link is a follow-up ticket, not this
  epic.

### Cost

`lease` and `renew` transitions add log volume, and every listener's
predicate judges every transition (docs/spec-events.md E-2.4): a claim
adds one `acquire` per row, an ending one `release`, `finish` or `expire`
per lease, and a run adds its renewals. Renewal at half-life bounds the
last: at most two `renew` per lease length per sitting, however many
calls it makes.

## 5. `not_held` and the cracks audit read leases

Today the `withheld` list and the in-flight `waiting_on` infer holding
from seat code. With leases both are one query:

- **`waiting_on.held`** on any row: its live lease, if any — holder,
  seat, `expires_at`. The in-flight read gains `held_by` beside `holder`:
  *who should move it* next to *who has it now*.
- **`not_held`** (the in-flight item that named org_plan bba84cf0): a row
  whose `waiting_on.holder` is a seat and which has **no** live lease, or
  whose lease's holder is a sitting that `half-left?` judges past half
  its lease, read on the sitting's `expires_at` (not `last_call_at`).
  It names the lease and its `expires_at`, so "held by an idle sitting
  until 19:20" is a sentence, not a mystery.
- **The withheld reason** reads the lease: *"sitting f835… holds it until
  19:20"*, not *"another open sitting of this seat holds it"*.
- **Cracks** gain two generic findings over every kind: rows held by a
  lease past its `expires_at` (the sweep is behind), and leases whose
  holder is ended (an `expire` that did not cascade). Both should be
  empty; either nonempty is a bug report, not a queue.

None of this is seat-specific: a future holder kind is read the same way.

## 6. Build tickets, in order

Each is under 800 changed lines with its tests; each leaves `main` green
and the engine behaving as before until the ticket that switches it. The
estimate after each title is its changed lines, tests included.

**Order with the other specs.** Spend 3 (`report_usage`) lands before
leases 3a; leases 3b lands before spend 6 (the floor), which then fills in
the bill `expire` takes. Leases 3b also lands after events 4, which
builds the `:sitting-end` consumer 3b hooks `expire` into (§3, step 4);
with this order no sitting's end goes unheard. Leases 5a lands after
events 4 (§6, 5a).
Leases 7 (cracks) lands after events 5 (*Cracks
and the seat envelope*, docs/spec-events.md), which reshapes the document
it adds to. Every other ticket waits only on the ones before it here.

1. **The `lease` kind** (~450). Declaration, states and doors of §1, the
   partial unique index, `acquire` expiring stale leases in its
   transaction, the shared `half-left?` helper (moved out of
   `standing-seat-grant`), and `inv/invoke-in!` / `inv/create-in!`
   (*Settled*). No caller yet. Tests: one live lease per subject; a stale
   lease is expired by the next acquire; release with grace stays live
   until it runs out; a refused inner acquire rolls the outer write back.
2. **Claims write leases** (~550). `claim-rows-atomically!` takes the lock
   order of §1 and its steps 3–5; `walked_rows` becomes derived;
   `graced-rows` and `other-seats-rows` read leases; a sitting's `close`
   releases with grace; its `abandon` releases its leases with grace 0;
   a walk row's work-over runs `finish`. Until 3b
   there is no `expire`, so step 4 releases the leases of a holder no
   longer open and otherwise reads the row as taken. A close `:within
   :takeover` releases with grace 0. Its backfill, an idempotent
   migration, writes one lease per `walked_rows` id of each open sitting
   (`expires_at` null) and one per row of a closed sitting still in grace
   (`expires_at = ended_at + grace`). Tests: two seats, one row; the
   grace; all-or-nothing; the backfill run twice.
3a. **The sitting's `expires_at` and `renew`** (~400). `expires_at` set at
   birth from `sitting_idle_seconds` in both modes; the `renew` door and
   the half-life renewal in `stamp-call!`, `bump-counter!` and
   `report_usage`; `claim-sitting!`'s connector claim written by the
   sit's transition; "open" read as a live lease by the counts of §3;
   the feed token and the inbox key bound to the lease, and
   `renew-feed-token!` removed with `next_token` (routes/seats.clj:1172-1209
   and docs/spec-seat.md:3628). Its backfill, an idempotent migration,
   sets `expires_at` on each open sitting to max(`started_at`,
   `last_call_at`, `tallied_at`) + `sitting_idle_seconds`. The sweep
   still runs today's rules.
   Waits on spend 3. Tests: renewal at half-life only; a fired run quiet
   past `claim_silence_seconds` but inside `sitting_idle_seconds` is
   live; a token stops when its lease does.
3b. **The sweep expires; take-over and mark-silent go** (~600). The
   `expire` door, with what a close runs (hooked into the `:sitting-end`
   consumer events 4 builds, not `:wakes`, which events 4 stops) and the
   bill from the last `report_usage`; `sweep-sittings!`
   becomes "expire the past-due"; the claim's step 4 runs the stale
   holder's `expire`; `take-over-silent!`, `mark-silent!`,
   `unmark-silent!` and the guard `still-quiet-for-the-sweep` removed.
   Until spend 6, `expire` posts nothing beyond what was reported; spend
   6 adds the floor's posting and `bill_basis` at `expire`. Waits on 3a
   and events 4. Tests: the
   claim and renew race, both ways; an expiry wakes a count wake; a claim
   and a sweep on one sitting.
4. **`store/maintain!` and the check** (~500). The reason set with
   `:credential`; the declaration-time check over raw `update-data!`; the
   44 maintenance and 10 credential sites of §4 moved onto it. The 11
   transition sites are the check's known exceptions, each naming the
   ticket that ends it (3a or 5a/5b), and `puller_seen_at` its one known
   smell.
5a. **Wakes' writes become doors** (~550). After events 4. The seven
   `wakes` sites of §4, and `deliver`'s writes of `wake_pending`,
   `wake_due_at` and `wake_first_at` (decision e222c39f #6), become
   `schedule` doors. Once events 4 stops the `:wakes` consumer,
   `own-kinds` in `handle-transition!` (wakes.clj:196) no longer guards,
   so 5a asserts that no subscription matches `schedule` transitions of
   its own seat: the new doors wake no seat. Tests: the wakes suite
   unchanged, one log line for each pending mark, and the assertion.
5b. **Schedules' and runner links' writes become doors** (~300).
   `defer-fire!`, `defer-until-elsewhere-lifts!` and `rearm!`. The
   exceptions list empties.
6. **Readers read leases** (~450). `waiting_on.held`, `not_held`, the
   withheld reason; `walked_rows` removed with its remaining readers.
7. **The two cracks findings** (~250), in the shape *Settled* gives.
   After events 5.

## Settled

The first draft's *To confirm*, answered from the code.

- **The 69 writes.** Read site by site; §4 lists each with its class.
- **Can cracks hold per-row findings? Yes, with no change to the
  document's shape.** `cracks/report` already carries findings that are
  not per-state: `value-cracks` (`unwalked_value`) is computed over live
  rows and reported with `:state nil` and a `:rows` count, and
  `wait-cracks` and `deadline-cracks` read rows through `live-rows` too.
  The two lease findings take that shape: `{:kind "lease" :state nil
  :crack "lease_past_due" :rows n …}` and `"lease_holder_ended"`, each
  with its `what_would_close_it`.
- **Can a transition run inside another call's transaction? Not in
  general, and the design no longer depends on it.** `inv/invoke!` and
  `inv/create!` open their own transaction; `invoke-in-tx!` and
  `create-in-tx!`, which take one, are private. The one way a door joins
  an outer write today is a handler's ctx `:invoke` (`ctx-invoke` in
  invoke.clj), whose refusal rolls the outer write back.
  `held_calls/forward-door!`, the draft's precedent, does not share a
  transaction: it calls `inv/invoke!`. So build ticket 1 adds
  `inv/invoke-in!` and `inv/create-in!`: public, the engine's actor only,
  over the two private bodies, taking the caller's `tx`, with the
  after-write pass run after the commit as the bulk create's is. Four
  callers use them, and no other gets them:
  - the claim (§1);
  - `expire`'s cascade (§3);
  - a `close` or `abandon` releasing its leases (§3);
  - a work-over running `finish` (§1).
- **The spend spec's usage report** is the door `report_usage`
  (docs/spec-spend.md §3), and the bill on expiry is that spec's floor
  (§4).
