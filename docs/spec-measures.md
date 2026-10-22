# Spec: goals and measures as declared law

Concept 7 of epic `2bb0aed8` (ticket `f12094a5`). A spec only: no engine
code lands with it. Its last section is the build plan the mayor splits
into children, the way `docs/spec-calendar-time.md` (concept 2) and
`docs/spec-ledger-money.md` (concept 3) end. It follows the owner's
defaults of 2026-10-22: strong defaults now, flexible later.

> At the shop, the owner never counts late orders by hand. On Monday the
> feed says once, in one sentence, that last month only 91% of orders
> shipped within 3 business days against a goal of 95%, and names three of
> the late ones. The owner moves the goal to 93% for the holidays without
> asking anyone to change the law.

Concept 1 gave the law reads of other rows, concept 2 gave it time, and
concept 3 gave it quantities. This spec gives the house a way to say how
well it is doing over many rows, against a target the owner holds. It
retires the logged workarounds "95% of orders shipped within 3 business
days" (shop), "no-show rate under 8%" (clinic), "owner sees jobs more than
15% over quote" (landscape), and any of the same shape on
`dress_rehearsal`. §9 maps each to its construct.

---

## 0 · Today

- **A dashboard slot can show one number.** `dashboard_slot` carries an
  optional `:measure {stat field at window_seconds buckets}`
  (`waymark10/dashboard.clj`). `stat` is one of `count`, `sum`, `median`,
  `p90` (`measure-stats`). `at` is a time field of the target, or
  `transition:<action>`, which counts that action's logged transitions.
  `field` is a number field or `duration(<time>,<time>)`. The window is at
  most thirty days (`max-window-seconds`) and the sparkline at most 60
  buckets. `measure-problems` judges all of this at slot write.
- **The number is computed on read and kept nowhere.**
  `server/measure.clj` serves `GET /api/dashboard_slots/{id}/-/measure`:
  it reads the target collection under the reader's grant, filtered by the
  slot's `:where`, and answers `{:value :previous :buckets}`. More than
  10 000 rows refuses 422 rather than truncate. Nothing is stored, so
  there is no history of readings, and a number nobody opens is never
  computed.
- **No stat is a share.** There is no "how many of these met that". A
  `count` of late orders over a `count` of all orders is two slots and a
  person dividing.
- **No number has a target.** A slot cannot say what the number should
  be, so it cannot be red. A target lives in the slot's label or in a
  rehearsal's prose.
- **No number tells anyone.** `notice_rule`
  (`server/held_calls.clj`) hears one row's transition (`when` on
  `action`, `from_state`, `to_state`) or one row's instant (`at`), and
  addresses a member through a ref field. Nothing hears a number over
  many rows. Nothing hears a derived fact becoming true.
- **Per-row facts exist.** A derived fact can compare two fields of one
  row (concept 1), with dates (concept 2) and money (concept 3). What is
  missing for "a job 15% over quote" is the notice, not the fact.

## 1 · The decision in one paragraph

**The measure is law; the target is data; a miss is a notice.** A kind
declares named **`:measures`**: which rows count (`:among`, a filter in
the collection grammar), which of those succeed (`:where`, an expression),
the stat (`:rate` is new: the share of `:among` rows where `:where`
holds), the window (`:over`, a period) and the seat to tell (`:tell`). The
target is a **`goal` row** the owner writes and restates without a law
revision. A **sweep** at the `:tell` seat's cadence reads each measure
and writes a **`measure_reading` row** for the current window, judged
against the goal in force. The first sweep in a window that finds a miss
sends **one notice** to the `:tell` seat, and the reading shows red on
any dashboard slot that names the measure. A miss does not file a ticket.
A threshold on one row, such as a job 15% over its quote, is not a
measure: it is a derived fact and a notice when the fact becomes true.

---

## 2 · Measures — `:measures`

### Decision

```clojure
(defresource order
  {...
   :measures
   {:shipped_on_time
    {:label "Orders shipped within 3 business days"
     :of    :order                                   ; defaults to this kind
     :stat  :rate
     :among "state=shipped,delivered,returned"       ; the denominator
     :where '(<= (local-date (data :shipped_at))
                 (+ (local-date (data :placed_at)) (business-days 3)))
     :at    :placed_at                               ; places a row in a window
     :over  '(weeks 4)
     :tell  :owner-seat}}})
```

| Key | Required | Meaning |
|---|---|---|
| `:label` | yes | The sentence the dashboard and the notice print. |
| `:of` | no | The kind whose rows are read. Defaults to the declaring kind, which is where a measure belongs unless a person would look for it on another kind. |
| `:stat` | yes | `:rate`, or one of today's `:count`, `:sum`, `:median`, `:p90`. |
| `:among` | yes | A filter in the `:of` kind's collection grammar, the same string a dashboard slot's `:where` holds. It picks the rows the stat reads. An empty string is refused by `[measure-shape]`: a measure says which rows it reads. |
| `:where` | `:rate` only | An expression over one row (concept 1's language, with concept 2's dates and concept 3's money). The rows where it is true are the numerator. Unknown counts as not met, and the reading says how many were unknown. |
| `:field` | `:sum`, `:median`, `:p90` | Today's slot spelling: a number field or `duration(a,b)`. |
| `:at` | yes | A date or instant field of the `:of` kind, or `transition:<action>`, as a slot's `:at` today. |
| `:over` | yes | A period: `(days n)`, `(weeks n)` or `(months n)`. At most `(months 3)`. |
| `:tell` | yes | A seat name. `[measure-untold]` (§8) is the finding when it is missing. |
| `:at-least` | no | The fewest `:among` rows a window needs before it is judged. Defaults to 10. Below it the reading is `unjudged`, never a miss. |

`:rate` is a decimal between 0 and 1, never a float, and prints as a
percentage with one decimal. A window with no `:among` rows has no rate:
the reading's value is null, not 0 and not 1.

The measure's name is `<kind>.<name>`, here `order.shipped_on_time`. A
goal, a reading and a dashboard slot each name a measure by that token,
and `waymark_discover` lists the tokens per kind.

### Windows

Windows are **aligned**, not rolling. `(weeks n)` windows start on the
first weekday of the governing `work_calendar` (concept 2), `(months n)`
on the first of a month, `(days n)` at midnight, all in the house's zone.
The epoch is 2026-01-05, a Monday, so `(weeks 4)` windows are the same
four weeks for every measure that says it. A row belongs to the window
its `:at` falls in. A sweep reads the current window to date.

### Option kept: rolling windows

A rolling window (the last 28 days, read now) is the dashboard slot's
form today and stays there. For a measure it gives no stable "window" to
say "one notice per" about, and every reading has a different
denominator. Aligned windows give each reading a name a person can say
("the four weeks from 5 October"). The slot form is not changed.

---

## 3 · Goals — the target is data

### Decision

`goal` is an engine-served kind, opted into like `dashboard`:

| Field | Type | Meaning |
|---|---|---|
| `measure` | string, `:filter` | The measure token, `order.shipped_on_time`. Its options come from discover. |
| `op` | enum `>=`, `<=` | Which side of the target is met. |
| `target` | `:decimal` | `0.95` for a rate; a number in the measure's unit otherwise. |
| `note` | prose, optional | Why this target, in the owner's words. |

States: `active` and `retired`. Doors: `create` (to `active`), `restate`
(edits `target`, `op` and `note` in place; the log keeps each earlier
target), and `retire`. At most one `active` goal per measure: `create`
refuses with "`order.shipped_on_time` already has a goal of ≥ 95%;
restate it", naming the row. The grant that may write goals is the
house's own decision, as for any kind; the default seed gives it to the
owner's seat.

A reading copies the goal's id and target at the time it is judged, so a
restated goal never rewrites an old verdict.

A measure with no active goal still sweeps and still shows its number. Its
readings are `unjudged` and nobody is told.

### Option kept: `:target` in the declaration

`:target 0.95` in `:measures` would hash into the fingerprint, and the
owner would need a law revision to move it for a holiday. The ticket rules
this out. A declaration may still carry `:default-target`, which the
boot writes as the first `goal` row when none exists. It is a seed, not
law, and changing it later does not touch the row.

---

## 4 · Readings and the sweep

### Decision

`measure_reading` is an engine-owned, read-only kind:

| Field | Meaning |
|---|---|
| `measure` | The token. `:filter`. |
| `window_start`, `window_end` | The aligned window, dates in the house's zone. |
| `among` | How many rows the window holds. |
| `met`, `unknown` | For `:rate`: how many met `:where`, how many were unknown. |
| `value` | The stat, a decimal, or null. |
| `goal_id`, `op`, `target` | The goal the reading was judged against, copied. |
| `verdict` | `met`, `missed` or `unjudged`. |
| `evidence` | Up to five ids of `:among` rows that did not meet `:where`, newest first. |
| `swept_at` | The last sweep that wrote it. |
| `told_at` | When the miss notice for this window went out; null until then. |

States: `open` while its window runs, `sealed` after. There is **one
reading per measure per window**: a sweep updates the open reading in
place, and the first sweep after the window ends writes its final values
and seals it. A sealed reading is never written again.

The sweep lives in `server/maintainer.clj` beside the clock flips. Each
measure is swept at the cadence of its `:tell` seat (`cadence_seconds`;
hourly when the seat has none), and once more at each window end. It
reads the `:of` kind as the engine, not as a reader: the measure is the
house's number, and a dashboard shows it to whoever may read
`measure_reading`. It reads through `:find` with the `:among` filter and
the window on `:at`, evaluates `:where` per row, and keeps today's
10 000-row honesty: over the cap, the reading is `unjudged` with the
reason in `evidence`, never a number from part of the window.

### A miss tells once per window

When a sweep moves an open reading's verdict to `missed` and that reading
has not told before, the engine sends **one** notice and stamps
`told_at` on the reading. A later sweep in the same window does not tell
again, even if the verdict goes `met` and back. The next window may tell
again.

The notice is an engine-declared rule, the way `scheduled.clj` declares
one rule per ending (`held-calls/engine-rules`). It is addressed to the
`:tell` seat: a person seat's person through their own notifier, a model
seat by waking it on `measure_reading/missed`. Its sentence:

> Orders shipped within 3 business days: 91.2% for the four weeks from
> 5 October, against a goal of ≥ 95% (114 of 125). Late: #1042, #1057,
> #1060.

**A miss files no ticket.** It is the owner's number, not a defect in
the house. A person who wants work to come of it writes the ticket.

### Option kept: a reading per sweep

One row per sweep gives a finer history but writes 24 rows a day per
measure for an hourly seat. The sealed readings are the history that
matters; the open one is the live number. If someone later needs the
intra-window curve, `measure_reading` can grow a child kind.

---

## 5 · Dashboards name the measure

### Decision

A slot's `:measure` takes a second form: `{:declared
"order.shipped_on_time"}`. With it, `stat`, `field`, `at`,
`window_seconds` and `buckets` are absent, and `measure-problems` judges
only that the token names a declared measure the slot's `:target` kind
carries (the slot's `:target` is the `:of` kind). The
`/api/dashboard_slots/{id}/-/measure` answer for such a slot reads the
readings, not the rows:

```json
{"value": 0.912, "previous": 0.964,
 "goal": {"op": ">=", "target": 0.95, "id": "…"},
 "verdict": "missed",
 "window": {"start": "2026-10-05", "end": "2026-11-01"},
 "buckets": [{"start": "2026-05-25", "value": 0.97, "verdict": "met"}, …]}
```

`value` is the open reading, `previous` the last sealed one, and
`buckets` the sealed readings, oldest first, at most 12. The client
paints the panel red when `verdict` is `missed`. Today's ad-hoc form is
unchanged.

---

## 6 · Per-row thresholds are facts, not measures

"Owner sees jobs more than 15% over quote" is about one job at a time.
It has no window and no share, so it is not a measure. It is a derived
fact on the job:

```clojure
:facts {:over_quote '(> (data :actual_cost) (* (data :quote) 1.15M))}
```

and a notice when the fact becomes true. A `notice_rule` hears
transitions and instants today, not facts. This spec adds a third form
of its `when`: `{"fact": "over_quote", "becomes": true}`, heard when the
maintainer stores the fact's change on a row. The rule addresses a member
as it does today. The 15% is in the law here, because it is part of what
"over quote" means for this house. A house that wants to move it without
a revision reads it from a rate table (concept 3, `(rate …)`).

---

## 7 · Honest on the probe, in rehearsal, in scenarios

- **Probe.** A measure is not a guard and refuses nothing, so the probe
  is unchanged. `waymark_get` on a goal shows its last reading.
- **Law scenarios** (`docs/spec-law-scenarios.md`) gain `:goals`, and
  `:expect {:readings {"order.shipped_on_time" {:value 0.5 :verdict
  :missed}}}` after a walk with `:at` set. A scenario that expects a
  reading and sets no clock is refused, as a clock-reading law is today.
- **Rehearsal and demo clones** copy goals. They do not copy readings:
  the clone sweeps its own.
- **Law sweep.** A changed `:where` or `:among` is swept over the last
  sealed window, and the revision's report shows the old and the new
  value side by side before it ships.

---

## 8 · Fingerprint and audit

**Fingerprint.** `:measures` is a law facet: `:of`, `:stat`, `:among`
(normalized as the collection grammar parses it), `:where` (its canonical
tree), `:at`, `:over`, `:tell` and `:at-least`. `:label` is prose and
does not hash. `:default-target` does not hash. Goals and readings are
data.

**Audit findings.** They join the declaration battery in `checks.clj`
(`make check-queue`). Each has its sentence, its remedy and one plain
question for the person who owns the kind:

| Finding | Level | Says | Remedy | Question |
|---|---|---|---|---|
| `[measure-untold]` | warning | A measure with no `:tell`, or a `:tell` naming no seat the house has. | Name the seat that should hear a miss. | "Who should hear when {label} misses its goal?" |
| `[measure-unfiltered]` | error | A field named in `:among`, or the `:at` field, is not `:filter` (`[exists-unfiltered]`'s reason: the sweep reads through `:find`). | Declare the field filterable. | — |
| `[goal-in-prose]` | info | A kind's description, a slot's label or a rehearsal's notes say a target in words: a percentage, "under N%", "within N days" beside "of orders", "rate". | Declare the measure and write the target as a `goal` row. | "Is {phrase} a goal the house should track?" |
| `[measure-shape]` | error | `:where` on a stat other than `:rate`, `:field` missing on a stat that reads one, `:over` past `(months 3)`, an empty `:among`. | The sentence names the key. | — |

---

## 9 · Migration — the workarounds

| Shape (the rehearsal's words) | Rehearsal | Construct |
|---|---|---|
| "95% of orders shipped within 3 business days" | shop | `order.shipped_on_time`, `:rate` over `(weeks 4)` with `business-days` in `:where`; goal `>= 0.95` |
| "no-show rate under 8%" | clinic | `appointment.no_show`, `:rate`, `:among "state=completed,no_show"`, `:where '(= (state) :no_show)`, `:at :starts_at`, `:over (months 1)`; goal `<= 0.08` |
| "owner sees jobs more than 15% over quote" | landscape | §6: fact `over_quote` and a `notice_rule` on `{"fact": "over_quote", "becomes": true}` addressed to the owner |
| any target in prose | `dress_rehearsal` | `[goal-in-prose]` finds it; each becomes a measure and a goal, or a §6 fact |

The last step of §12 re-reads the logs and checks this table row by row.

---

## 10 · The film

The shop, four weeks from 5 October:

1. **Monday 5th.** The owner opens the dashboard. The panel "Orders
   shipped within 3 business days" is grey: "3 orders so far; judged
   from 10".
2. **Thursday 15th.** A kiln breaks; nine orders wait. The hourly sweep
   finds 91.2% against ≥ 95%. The owner's feed gets one notice naming
   three late orders. The panel is red.
3. **Friday 16th.** The orders ship. The rate climbs and falls through
   95% twice in the week. Nobody is told again.
4. **Monday 19th.** The owner restates the goal to ≥ 93% for the
   holidays, with the note "kiln repair". No law changes; the history of
   the goal says who moved it and when.
5. **Monday 2 November.** The window seals at 93.8%, met against 93%. The
   new window opens grey. The sparkline shows the last twelve windows,
   one of them red.

---

## 11 · Not in this spec

- `:rate` in a dashboard slot's ad-hoc form. The ad-hoc `:where` is a
  filter string, not an expression; a slot that wants a share names a
  declared measure.
- Measures over more than one kind (a ratio of two kinds' counts).
- Forecasts, trends and anomaly detection. A reading is what happened.
- Goals that change on a schedule. A restate can be a `scheduled_action`.
- Filing tickets from misses. §4 says why.

---

## 12 · Build plan

Each step is one ticket and one pull request, in order, each under the
800-line ceiling. **C1** marks a step that needs concept 1's `:find` at
guard time and its expression reads; **C2** marks a step that needs
concept 2's `(today)`, periods, `business-days` and the governing
calendar.

1. **`:measures` declaration (C1).** The facet, its keys and defaults,
   the token, `[measure-shape]`, `[measure-unfiltered]`, the fingerprint,
   and discover's list of tokens. Nothing sweeps yet. Tests: a well-formed
   measure passes `make check-queue`; each malformed key is named.
2. **`goal` kind.** The kind, its doors, one active goal per measure,
   `:default-target` as a boot seed. Tests: the refusal sentence; a
   restate keeps the old target in history.
3. **Aligned windows (C2).** Window arithmetic for `days`, `weeks` and
   `months` from the epoch in the house's zone and the governing
   calendar's first weekday. Tests: a window across a zone change; a
   `(months 1)` window in February.
4. **`measure_reading` and the sweep (C1).** The kind, the maintainer
   sweep at the `:tell` seat's cadence, `:rate` with `met` and
   `unknown`, the other stats reused from `server/measure.clj`, the
   read cap, sealing at window end, and judging against the goal.
5. **The miss notice.** The engine-declared rule, `told_at`, once per
   window, person seat and model seat. Test: two misses in one window
   tell once; a miss in the next window tells again.
6. **Dashboard slot `{:declared …}`.** The second `:measure` form, its
   judgment in `measure-problems`, the reading-backed answer, and the
   red panel in the client.
7. **Fact notices.** `notice_rule` `when {fact becomes}`, heard when the
   maintainer stores a fact's change.
8. **Scenarios and clones.** `:goals` and `:expect {:readings …}` in law
   scenarios, clones copying goals, the law sweep comparing a changed
   measure over the last sealed window.
9. **Audit.** `[measure-untold]` and `[goal-in-prose]`, each with its
   question.
10. **Rehearsals.** The shop's and the clinic's measures and goals, and
    the landscape job's `over_quote` notice; the film is the shop's
    acceptance walk.
11. **Survey.** Re-read the three rehearsal logs and `dress_rehearsal`,
    check §9's mapping, and file one ticket for each workaround these
    steps did not retire.
