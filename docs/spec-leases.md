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

The traces of 2026-10-23 are the evidence: about 69 `store/update-data!`
call sites that write a row without a transition, and an idle sitting that
held org_plan bba84cf0 with nothing recording or releasing the claim until
the sweep. The functions named below were read for this pass. The full
inventory of the 69 was **not** re-read call by call; section 4 gives the
rule and the first build ticket applies it site by site. What was not read
is in *To confirm* at the end.

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
  can be handed their rows. `still-quiet-for-the-sweep` re-judges the
  silence under the close's lock.
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

### The FOR UPDATE ordering

`claim-rows-atomically!` keeps its shape and its lock order; only its last
step changes. Inside one `store/with-tx`:

1. the seat row, FOR UPDATE (one claim of a seat at a time);
2. the subject rows, FOR UPDATE, **sorted by id** (two seats asking for one
   row run one at a time, and no two claims lock in opposite orders);
3. the live leases on those subjects, read — any held by another holder →
   `{:claimed? false :taken …}`, nothing written;
4. stale leases on those subjects → `expire`;
5. one `acquire` per subject, holder = this sitting, `expires_at` null.

The acquires are ordinary transitions run with the caller's `tx` (the way
`held_calls/forward-door!` runs a door inside a larger write), so all or
nothing still holds: a refused acquire rolls the claim back. Steps 3–4
replace the three readings `walked_rows` + `graced-rows` +
`other-seats-rows` with one query on `lease`.

`walked_rows` stays on the sitting for one release as a **derived** field
(the ids of its live leases, written by the same claim) so readers can
move one at a time; build ticket 6 removes it.

## 2. Renewal without a transition per call

**Decision: the half-life rule, on the holder only.**

Row leases follow their holder (`expires_at` null), so there is exactly
one clock to renew per run: the sitting's. Each tool call that today runs
`stamp-call!` or `bump-counter!` checks, in the write it already makes:

> if `expires_at − now < lease_seconds / 2`, run the sitting's `renew`
> door; otherwise do nothing extra.

A run of steady calls logs at most two renewals per lease length — a
10-minute fired lease over a 40-minute run logs about eight `renew`
transitions instead of hundreds of stamps. This is the rule
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

### Lease length is set by mode

| mode | `lease_seconds` | cap (renew refused past it) |
|---|---|---|
| fired | the seat's `claim_silence_seconds` | `started_at` + two cadences |
| interactive | the seat's `sitting_idle_seconds` | none |

`expires_at` at birth is `started_at + lease_seconds`. The fired cap keeps
today's outer bound; the interactive sitting is still never judged by a
cadence (R-10.8). The two seat fields keep their names, so no seat is
restated.

### One ending

`expire` is the one way the engine ends a sitting nobody ended. It:

1. ends the sitting in state `closed` with `closed_by: "expiry"`;
2. takes the bill from the **last usage report** the sitting received (the
   spend spec's report, which a run sends as it goes and which survives a
   crash). No report → a zero bill marked `unreported`, the same judgment
   the take-over's close already makes. There is no `abandon` from the
   sweep any more: "no bill" is a bill of zero that says why.
3. ends every lease the sitting holds with `end_reason: expired` — no
   grace, because an expired holder is not mid-call.

A sitting that ends by its own `close` (the Stop hook) runs `release` on
its leases with the seat's `release_grace_seconds`, which is the only case
the grace was built for.

### What goes away

- `take-over-silent!`: a silent sitting's lease runs out on its own; the
  next claim's step 4 expires it under the claim's lock. The new sit no
  longer closes its siblings.
- `mark-silent!`, its undo and `still-quiet-for-the-sweep`: there is no
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
`#{:counter :stamp :cache :mirror :append :seed}` — and writes the reason
nowhere but the code: the check reads it, a reader of the call site reads
it. Anything else is a door.

### The 69, by group

| group (sites) | becomes | why |
|---|---|---|
| seats (21): `claim-rows-atomically!`, the take-over, walk claims | **lease transitions** | holding is §1 |
| seats: `bump-counter!`, `stamp-call!`, `stamp-walked-nothing!` | maintenance (`:counter`, `:stamp`) | only seat health reads them; liveness moves to `expires_at` |
| definitions (3): `mark-silent!` and its undo | **removed** | §3 |
| held_calls (8): a hold's run, forward and failure stamps | **transitions** | they decide whether a person's call ran |
| wakes (10): a wake's fired/pending marks | **transitions** where they decide the next fire; `:stamp` where they only record the last one | the next fire is another row's fate |
| runner_links (2) | **transitions** | a new fire link must take effect at once and be seen to |
| schedules (5): `next_run_at` and friends | maintenance (`:cache`) — derived from the schedule's own transitions | recomputable |
| mirror (5) | maintenance (`:mirror`) | a copy of an outside source |
| transcripts (4) | maintenance (`:append`) | lines appended to their own row |
| seed (4) | maintenance (`:seed`) | bootstrap, before there is a log to write to |
| maintainer (3), others | judged site by site in build ticket 4 | not read for this pass |

The counts are the trace's; ticket 4 re-counts them and its check makes
the count exact.

## 5. `not_held` and the cracks audit read leases

Today the `withheld` list and the in-flight `waiting_on` infer holding
from seat code. With leases both are one query:

- **`waiting_on.held`** on any row: its live lease, if any — holder,
  seat, `expires_at`. The in-flight read gains `held_by` beside `holder`:
  *who should move it* next to *who has it now*.
- **`not_held`** (the in-flight item that named org_plan bba84cf0): a row
  whose `waiting_on.holder` is a seat and which has **no** live lease, or
  whose lease's holder is a sitting with no call in its last half-lease.
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
and the engine behaving as before until the ticket that switches it.

1. **The `lease` kind.** Declaration, states and doors of §1, the partial
   unique index, `acquire` expiring stale leases in its transaction, the
   shared `half-left?` helper (moved out of `standing-seat-grant`). No
   caller yet. Tests: one live lease per subject; a stale lease is
   expired by the next acquire; release with grace stays live until it
   runs out.
2. **Claims write leases.** `claim-rows-atomically!` keeps steps 1–2 and
   switches 3–5 to leases; `walked_rows` becomes derived; `graced-rows`
   and `other-seats-rows` read leases; a sitting's `close` releases with
   grace; a walk row's work-over runs `finish`. Tests: two seats, one row;
   the grace; all-or-nothing.
3. **The sitting is a lease.** `expires_at`, `renew`, `expire`, the
   mode table, the half-life renewal in `stamp-call!`/`bump-counter!`;
   `sweep-sittings!` becomes "expire the past-due"; `take-over-silent!`,
   `mark-silent!` and its undo are removed. The bill on expiry reads the
   last usage report — **this ticket waits on the spend spec's report
   ticket**; until it lands, expiry bills the last tally as the
   interactive close does today.
4. **`store/maintain!` and the check.** The reason set, the
   declaration-time check over raw `update-data!`, and every site the
   table calls maintenance moved onto it. The sites it calls transitions
   are listed in the check as known exceptions, each naming ticket 5.
5. **The recorded writes become doors.** `held_calls`, `wakes`,
   `runner_links` and any `maintainer`/other site ticket 4 judged a
   transition; the exceptions list empties. Split by module if it passes
   the ceiling.
6. **Readers read leases.** `waiting_on.held`, `not_held`, the withheld
   reason, the two cracks findings; `walked_rows` removed with its
   remaining readers.

## To confirm

- The site-by-site list of the 69 writes: the groups and counts above are
  the trace's, not re-read here.
- Whether `server/cracks` can take a finding that is not per-state (the
  two in §5 are per-row).
- Whether a transition can run inside another call's `tx` in the general
  case, or only on the paths `held_calls` uses; ticket 2 depends on it.
- The name and shape of the spend spec's usage report; §3 names it only
  by role.
