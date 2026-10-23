# Spec: spend is a ledger the engine keeps

Ticket `0b6d6ccd`, under epic `215e28f4` (a seat is a member, a grant, a
subscription, a lease and a runner link). This is a spec only. No engine
code lands with it. Its last section is the build plan, and the build
tickets are filed from it under the same epic.

> A run crashes at minute forty. Nobody closes its sitting. An hour
> later the sweep ends it, and the seat's week still shows what those
> forty minutes cost, marked "estimated from transcript". The wall
> that stops the seat reads one indexed sum, and the domain's spend
> is a balance, not a sum somebody recomputes.

The ledger already exists: `docs/spec-ledger-money.md` built accounts,
`:posts`, the engine-served `posting` kind (`waymark10/server/postings.clj`),
`:waymark/money` and house accounts. This spec does not add a second
ledger. It makes spend one more thing that ledger counts.

---

## 0 · Today (traces of 2026-10-23)

Line numbers are as of this commit. Find them by name.

- **Spend is a field.** `sitting.cost_usd` is written by two handlers in
  `waymark10/server/seats.clj`. `close-sitting` prices the counts at
  `prices-now` and copies the `prices` map down beside the bill.
  `tally-sitting` writes a running `cost_usd` with no prices map. Both
  use `cost-of` and `token-counts`.
- **The counts come from one client script.** `.claude/hooks/sitting-close.sh`
  (682 lines) reads the local transcript, counts each request once
  (several content blocks share one `requestId`), and calls `tally`
  every turn and `close` at the stop.
- **A lost run loses its bill.** When the hook never runs (a crash, a
  container restart), `sweep-sittings!` in
  `waymark10/server/definitions.clj` abandons a fired sitting "with NO
  tokens, because the absence of a bill is the honest record". The
  money was spent anyway, and no wall saw it.
- **The wall re-sums every request.** `spent-this-week` in
  `waymark10/server/grants.clj` sums `sitting.cost_usd` over the seat's
  sittings started in the rolling window, closed or open. Its own
  docstring says no index serves the `data->>` conds.
- **The domain has no spend.** `domain.budget_usd_per_week` only caps the
  sum of its seats' budgets (`set_budget` in `seats.clj`, the move in
  `domains.clj`). What a domain spent is a sum nobody keeps.
- **The engine already holds the usage.** `derive-line` in
  `waymark10/server/transcripts.clj` keeps each line's `usage` (input,
  output, cache read, cache write) on `transcript_entry`, and nothing
  prices it. It does not keep the `requestId`, so a sum of those
  lines today would count one request several times.
- **A posting has no settable instant.** `posting` (in
  `waymark10/server/postings.clj`) stores `at`, the commit instant, which
  no door sets and no filter names, and `effective_on`, a date. A wall
  over `at` would put every migrated posting in the week it was migrated.
- **An ended sitting has no door out.** `sitting` declares
  `:terminal #{:closed :abandoned}` in `seats.clj`, and
  `check-terminal-no-exit` in `waymark10/checks.clj` refuses any door
  out of a terminal state, `:to :same` included. A door that reports on
  an ended sitting needs a licence that does not exist yet (§3).

## 1 · The decision in one paragraph

**A usage report posts. A seat's spend is the balance of its account.
The wall reads the balance.** `seat` declares a `spend` account and
`domain` declares a `spend` account, both `[:money "USD"]`. A new door
on the sitting, `report_usage`, takes the harness's cumulative counts for
one harness session. It prices only the tokens that are new since that
session's last report, and it posts that amount to the seat's account
and to the domain's account, in the same transaction as its log row. A
report that adds nothing is a no-op. `close` and `tally` stop
billing: `close` ends the sitting, and its counts, if it carries any,
are a last `report_usage`. When the leases spec's `expire` ends a
sitting, the engine prices the usage it already holds on the sitting's
transcript lines, and posts whatever of it the reports did not cover,
marked `estimated_from_transcript`. Every posting carries an
`effective_at` instant, and the weekly wall and every spend view read
postings by it. `sitting.cost_usd` stays, as a read-only cache of
the sitting's own postings.

---

## 2 · The accounts

### Decision

```clojure
(defresource seat
  …
  :accounts {:spend {:unit [:money "USD"]}})

(defresource domain
  …
  :accounts {:spend {:unit [:money "USD"]}})
```

- **Account = seat, parent = domain.** The ledger has no parent
  account. The parent is a second entry on the same report: each
  report posts the same amount to `seat/<seat id>/spend` and to
  `domain/<domain id>/spend` (the ledger's own addresses,
  `account-address` in `postings.clj`). A domain's spend is then one
  balance, and it stays correct when a seat moves domain, because past
  postings keep the domain they were posted to.
- **The domain is resolved at posting time.** A sitting has no
  `domain` field, and a seat stores its domain's name while an account
  wants the domain row's id. The door reads the sitting's seat, resolves
  that seat's domain name to the domain row's id in the same
  transaction, and posts there. A seat with no domain posts only the
  seat entry.
- **Dated by an instant.** `posting` gains `effective_at`, a promoted,
  indexed, settable instant beside `at` and `effective_on`. A door's
  posting sets it to its commit instant unless the entry names one; the
  migration (§8) names one. `effective_on` stays the house's day of
  `effective_at`, so periods are unchanged.
- **Not conserved.** Spend is a flow out of the house. Neither account
  is a conserved pair, so `[unbalanced-door]` does not judge them. The
  two entries are a roll-up, not a debit and a credit.
- **No bound.** Neither account declares `:never-above`. The wall is a
  rolling window over the postings (§5), and a lifetime balance is not
  a budget.
- **The projection is computed on read.** `seat.spend` and
  `domain.spend` appear as read-only fields, the lifetime balance, but
  they are summed from the postings when the row is read, not stored
  balances on the row. The ledger's `rebalance!` (in `postings.clj`)
  skips these two accounts. So a report adds no version to the seat or
  the domain row. If it did, every turn of every sitting would fence a
  person's `if_version` edit of a seat, the cost spec-leases §1
  rejects.
- **Attribution.** Each posting's source is the transition that posted
  it (`report_usage`, `open_ledger` or `expire`), and that log row is on
  the sitting. "What did this sitting cost" is the sum of the postings
  whose source is that sitting. No third account is needed. `posting`
  `source_id` becomes filterable by `:eq`, because this section, §6 and
  §8 all read postings by source.

### Option kept: a `sitting` account

The sitting could declare `:accounts {:cost {…}}`, making its field a
ledger cache like any other. That is three entries per report and a
field rename (`cost_usd` to an account field) across every view. It is
not in the first build. §6 keeps `cost_usd` as a cache written in the
same transaction.

---

## 3 · One door: `report_usage`

### Decision

```clojure
:report_usage
{:from #{:open :closed :abandoned} :to :same
 :allow-after-end true
 :input [:map
         [:harness_session :string]
         [:input_tokens :int] [:output_tokens :int]
         [:cache_read_tokens :int] [:cache_write_tokens :int]
         [:turns {:optional true} :int]
         [:model {:optional true} :string]]
 :posts [{:account [:seat (data :seat) :spend]
          :amount  (usage-delta)}
         {:account [:domain (seat-domain-id (data :seat)) :spend]
          :amount  (usage-delta)}]}
```

- **Cumulative in, delta out (replace, not add).** The counts are the
  session's whole total so far, as the hook sends them today. The
  sitting keeps `usage_by_session`, a map from harness session to its
  last reported counts. The door writes the new counts there and posts
  the price of the difference: `(cost-of (- new last) prices-now)`. Two
  sessions on one sitting (a reconnect, a resumed run) each have their
  own key, so neither replaces the other. The optional `turns` is
  cumulative per session in the same way, and is kept beside the counts.
- **A report that changes nothing is a no-op.** When the delta is zero
  and the counts equal the session's last ones, the door answers the
  row as it is: no transition, no log row, no posting. A hook that
  reports every turn through a long wait costs nothing.
- **Prices are frozen per posting.** Each report prices only its new
  tokens at the prices of that moment, and the posting's log row
  carries the prices used. A reprice tomorrow moves no posted dollar,
  which is R-10.4, kept per posting instead of per close.
- **The basis is on the log row.** Each posting transition's log row
  carries its `basis`: `reported` for `report_usage`, `opening` for
  `open_ledger` (§8), `estimated_from_transcript` for `expire`'s floor
  (§4). The sitting's `bill_basis` (§6) summarizes those rows; it is not
  the record.
- **A count that goes down is refused.** The harness's totals only grow.
  A report below the last one names the field and the last value. It
  is a hook bug, and a silent negative posting would hide it.
- **The door may run on an ended sitting.** A hook that runs late, after
  the sweep has already abandoned the row, still reports. Today
  `check-terminal-no-exit` refuses that (§0), so the engine gains one
  narrow licence, `:allow-after-end`. It is allowed only on a door that
  is `:to :same` and writes no state field: the only data it may write
  is `usage_by_session` (and the caches the posting door keeps, §6), and
  its only other effect is postings. Its own check in `checks.clj`
  holds that at declaration time. `report_usage`, `open_ledger` (§8) and
  the floor's re-run after a late report (§4) use it, and nothing else.
  `closed` and `abandoned` stay terminal: no door leaves them. A late
  report on an estimated bill reverses the estimate first (§4).
- **Who may call it.** The sitter whose sitting it is, and the engine.
  It reuses the guard `tally` uses today.
- **`close` and `tally`.** `tally` becomes an alias of `report_usage`,
  and stays one until every seat's hook reports through `report_usage`;
  a later ticket removes it. `close` keeps its note,
  `ended_at`, `closed_by` and health stamp. Counts given to `close`
  are applied as a `report_usage` in the same transaction. `close`
  stops writing `cost_usd` and `prices` itself.

### Cost

`report_usage` adds one transition per reporting turn (an unchanged
report adds none). Every transition is judged by every listener's
predicate — the subscriptions and event matchers that watch sittings —
so a busy seat adds that many predicate evaluations per turn. A
listener that wakes on sitting moves should name the doors it wants
rather than match every transition.

---

## 4 · The engine's floor: a lost run still leaves its bill

### Decision: yes

The floor hangs on the leases spec's `expire` (its §3, "One ending"),
the one way the engine ends a sitting nobody ended. It does not hang on
abandon or on the interactive idle close: the leases spec removes both.

On `expire`, the engine sums the usage on the sitting's
`transcript_entry` lines (one per `request_id`, below) and the counts
in `usage_by_session` across sessions. For each count it takes
`max(0, transcript - reported)`, prices that, and posts it. The
`expire` transition itself carries the postings, and its log row has
`basis: "estimated_from_transcript"`, so the reversals of the `expire`
log id are exactly the estimate and nothing else. A run that reported
once and then crashed still leaves its tail, and one tiny early report
no longer dodges the floor.

The sitting's `bill_basis` is then `estimated_from_transcript` when the
estimate posted anything, `reported` when it did not and a report did,
and `none` when neither posted. Every spend view shows the mark. This
replaces the leases spec's "no report: a zero bill marked
`unreported`" when build step 6 lands.

- **This is not estimating a token.** R-10.5 says the engine never
  estimates a token, and it still does not. The counts on a
  transcript line are the harness's own, copied from the model's
  response. What is estimated is completeness: lines after the last
  upload are missing, so the floor is a lower bound. The mark says
  so. R-10.5 is amended to say "counts are the harness's, from its
  report or from its transcript, and a transcript bill is marked."
- **Count each request once.** One request appears as several lines,
  each with the same `usage`. The hook already counts by `requestId`.
  `derive-line` must keep `requestId` (or the message id when there
  is none) as `request_id` on `transcript_entry`, and the floor sums
  one usage per `request_id`. Without that, the floor overcounts
  several times over. It is the first build ticket for that reason.
- **A late real report wins, across sessions.** If a real report
  arrives after an estimate, in one transaction the door:
  1. posts the reverse of the standing estimate (the ledger's own
     reversing posting, never an edit);
  2. posts its own delta from that session's last reported counts;
  3. re-runs the floor against the new reported totals across all
     sessions, and posts any remainder above zero as a new estimate.

  A report from one session therefore does not erase the estimate for
  another session's tail. `bill_basis` stays
  `estimated_from_transcript` while a remainder is above zero, and
  moves to `reported` when the remainder is zero.
- **The model.** The floor prices at the sitting's model row, as `close`
  does. `transcript_entry` keeps `model` beside `request_id`, and a
  line whose model differs is priced at that model's row if one exists,
  and at the sitting's otherwise. The posting's log row names the models
  used.

### Option rejected: abandon with nothing

This is today's rule. It is honest about the report and wrong about the
money. The wall lets the seat spend again what it already spent, and a
crash loop is invisible until the invoice comes.

### Option rejected: estimate only a sitting with no report

A run that reported once and then crashed would lose everything after
that report, and one tiny report at the start would exempt a run from
the floor entirely. The floor posts the difference instead.

---

## 5 · What reads the ledger

- **The weekly wall.** `spent-with` and `spent-this-week` sum the
  postings to `seat/<id>/spend` whose `effective_at` falls in the
  rolling window `[now - budget-window-seconds, now)`. That is one
  `store/sum-amounts` over promoted, indexed posting columns (account
  address and `effective_at`). It does not read `at`: the commit
  instant is not settable, so migrated postings would all count in the
  migration's week and every seat would hit its wall that day. The
  window stays rolling seconds, not calendar days, so the wall's
  meaning does not change.
  One difference: a long sitting's spend now lands when it is
  reported, not on the day the sitting started. That is more correct.
- **The fire door.** It already calls `spent-with`. It changes with it.
- **The domain.** `domain.spend` is the lifetime balance. The domain's
  week is the same window sum over `domain/<id>/spend`. A domain wall,
  refusing a fire when the domain's week reaches
  `domain.budget_usd_per_week`, is the last build ticket. Until then the
  domain budget keeps its current meaning, a cap on allocations.
- **The views.** The seat routes' spend summaries (`routes/seats.clj`)
  and the sittings roll-up in `seats.clj` read postings, not
  `cost_usd`.

---

## 6 · What stays on the sitting

- `cost_usd` stays, read-only, as the sum of the sitting's postings,
  written by the posting door in the same transaction. The
  `[balance-drift]` sweep (in `live_cracks.clj`) gains a sitting leg:
  `cost_usd` must equal its postings' sum. The leg lands with the
  migration (§10 step 4), not before, so an old row does not drift
  before it is opened.
- `usage_by_session` (per session counts) is new, and the counters
  `close` writes today (`write-counts`) become its sum across sessions.
  It is not named `usage`, so it is not confused with
  `transcript_entry.usage`, one line's counts.
- `prices` stays on old rows and is no longer written. A posting's log
  row carries its prices.
- `bill_basis` is new: `reported`, `estimated_from_transcript` or `none`.
  It summarizes the `basis` on the sitting's posting log rows (§3);
  the log rows are the record.

## 7 · What stays client-side

Token counting is the harness's, so the hook stays. It shrinks to
two things: upload new transcript lines, and call `report_usage` with
the session's cumulative counts. Each turn it does both. At the stop it
does both, then `close` with the note. Everything else in the 682
lines that exists to keep a bill from being lost (retrying the close,
tallying through a long wait, carrying a session id across a
reconnect) can go, because a lost hook no longer loses the bill. The
hook's own size target is under 200 lines. It is a separate ticket
because it lives under `.claude/hooks/`.

## 8 · Migration

The opening is a door, `open_ledger`, on `sitting`: engine-only,
`:to :same`, under the `:allow-after-end` licence (§3), so it runs on
closed and abandoned rows without moving them. For each existing
sitting with a non-zero `cost_usd` it posts that `cost_usd` unchanged,
not repriced, to its seat's and its domain's `spend` accounts. Its log
row carries `basis: "opening"` and the prices the sitting was billed
at (its `prices` map, where it has one). The posting's `effective_at`
is the sitting's `ended_at`, else its `tallied_at`, else its
`started_at`. The sitting's `usage_by_session` is seeded from its
counters under its `harness_session` (or `"opening"` when it has
none), so the first real report after the migration posts only what is
new. The migration is idempotent: a sitting whose postings already
exist is skipped.

**The wall's number is not the same before and after.** Today's wall
filters sittings on `started_at` (`spent-this-week` in `grants.clj`),
and the ledger's wall filters postings on `effective_at`. A sitting
that started before the window and ended inside it counts after the
switch and did not before; one that started inside and has no end yet
counts the same. That difference is intended (§5). The migration runs
before the wall switches to the ledger (step 5), and ships with an
agreement check that compares both answers for every seat and lists
each seat whose number differs, with the sittings that cause the
difference, so a person can see that every difference is one of these.

## 9 · Not in this spec

- Budget alerts and burn-rate projections. They read the same postings.
- A chart of accounts per model or per provider.
- Billing anything other than model tokens (bench minutes, CI). The
  `spend` account takes them later as more postings.

---

## 10 · Build plan

In order. Each is one change under 800 lines, with its design decided
above; the estimate is changed lines, tests included. Across specs the
order is spend 3, then leases 3 (whose `expire` reads `report_usage`),
then spend 6 (which hooks the floor on that `expire`).

1. **Transcript request ids.** `derive-line` keeps `requestId` (or the
   message id) as `request_id` on `transcript_entry`, and keeps the
   line's `model` beside it. Add `transcript-usage`, the deduplicated
   sum of a sitting's usage by request id, per model. Tests: several lines of one request count once. (§4)
   About 200 lines.
2. **Spend accounts.** `seat` and `domain` declare `:accounts {:spend
   …}`. The `posting` kind is enrolled for them, and gains
   `effective_at`, promoted, indexed with the account address, and
   settable by a posting entry; its `source_id` becomes filterable by
   `:eq`. `seat.spend` and `domain.spend` are computed on read, and
   `rebalance!` skips them. No door posts yet. (§2, §5) About 350
   lines.
3. **`report_usage`.** The `:allow-after-end` licence and its check in
   `checks.clj`, the door, the `usage_by_session` map with optional
   `turns`, delta pricing, the no-op on an unchanged report, the
   refused decrease, the domain resolved through the seat, the two
   postings, and `basis: "reported"` on the log row. `cost_usd` becomes
   the cache of the sitting's postings. `tally` becomes its alias, and
   `close` applies its counts through it. (§3, §6) About 650 lines.
4. **Opening postings.** The engine-only `open_ledger` door of §8, run
   once over every sitting as an idempotent engine task, setting
   `effective_at`, with its agreement check listing each differing seat
   and its sittings. The `[balance-drift]` sitting leg lands here.
   (§6, §8) About 300 lines.
5. **The wall reads the ledger.** `spent-with`, `spent-this-week` and
   the fire door sum postings by `effective_at` in the window. The seat
   routes and the sittings roll-up read postings. (§5) About 400 lines.
6. **The floor.** Waits on leases 3. The `expire` transition itself
   posts `max(0, transcript - reported)` per count as an estimate, and
   marks `bill_basis`. A late real report reverses the estimate, posts
   its delta and re-runs the floor, posting any remainder as a new
   estimate. Amend R-10.5. (§4) About 450 lines.
7. **The hook shrinks.** `.claude/hooks/sitting-close.sh` calls
   `report_usage` and uploads lines, and drops the bill-saving
   machinery. There is no copy under `seat/`:
   `seat/.claude/settings.json` runs `.claude/hooks/sitting-close.sh`,
   so this step changes `.claude/hooks/` only, and seat repos pick it
   up through their own sync. `tally` stays as an alias; a later
   ticket removes it once every seat's hook reports through
   `report_usage`. (§7) About 600 lines, mostly deletion.
8. **The domain wall.** A fire is refused when the domain's window spend
   reaches `domain.budget_usd_per_week`, with the evidence of its
   largest seats. (§5) About 250 lines.
