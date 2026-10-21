# Spec: calendar time and recurrence as declared law

Concept 2 of epic `2bb0aed8` (ticket `d93da044`). A spec only: no engine
code lands with it. Its last section is the build plan the mayor splits
into children, the way `docs/spec-related-row-law.md` (concept 1) ends.

> A landscaping owner books the Hendersons' patio for Thursday with the
> same crew already at the Parks' yard. The house refuses the booking the
> moment it is made, in one sentence naming the Parks' job and the free
> crew. And every second Tuesday from April to October, a visit appears on
> the Parks' contract by itself.

Concept 1 gave the refusal its law. This spec gives that law the time it
reads: dates and date-times you can add to, a working calendar, a booking
window on a resource, and a rule that makes rows on a schedule. Three
rehearsals logged 25 workarounds of this concept (shop 7, clinic 10,
landscape 8). Each one is in §8 with the construct that replaces it.

---

## 0 · Today

- **The types exist.** `:waymark/date` (a `LocalDate`, ISO on the wire)
  and `:waymark/instant` (an `Instant`, RFC 3339) are registered in
  `waymark10/schema.clj`, have a field word (`declare/date`), project to
  `date` / `timestamptz` in storage, and filter in the collection grammar.
  The rehearsals' "dates are strings" comes from kinds that did not use
  these types. The gap is in arithmetic, zone and calendar, not in the type.
- **The arithmetic is half there.** `expr/ops` has `(date "…")`,
  `(days n)` (a `Period`), `date-of` (an instant's **UTC** date), and the
  ordering ops over two dates or two instants. `add2` adds a period to a
  date. Nothing adds anything to an instant: `(+ (data :starts_at) (days
  1))` is nil. There are no weeks, months, years, hours or minutes, and no
  difference of two dates.
- **`(now)` is an instant, and nothing knows the house's day.** A guard
  reads `(now)` (`:reads [:now]`). A derived fact reads the clock as
  `(var :now)` through `:over [... :now]`. Both get "today" only through
  `date-of`, which is UTC. `clock`, `day` and `iso` use the reader's
  `:zone`, and only for sentences, never for verdicts.
- **Clock facts flip on time.** `server/maintainer.clj` keeps
  `next_flip_at` per row. For the shape `(< (date-of (var :now)) (var
  :f))`, the flip instant comes from the field (a date contributes its
  **UTC** midnights). Any other shape re-checks every 15 minutes.
- **`:expected-age` counts from state entry** (vocabulary §, ticket
  `21b49b28`). `live-cracks` files an `aged` draft ticket when a row has
  rested past it. It cannot count from a date the row holds.
- **`scheduled_action`** (`docs/spec-scheduled-actions.md`) stores one
  call for one `run_at`, judges it again at that time under a validity
  rule, runs it as a principal (R-4), and is idempotent by
  `scheduled_action:<id>`. Nothing makes these rows on a rule.
- **Law scenarios set the clock** with `:at` (`docs/spec-law-scenarios.md`).
  A clock-reading law with no `:at` is refused, not defaulted. There is no
  zone and no calendar to set.
- **No calendar exists.** No row says which days a house works, which days
  are holidays, or what hours a crew keeps. `calendar10` has its own
  `event` kind, which is an app's data and not a calendar the law reads.

## 1 · The decision in one paragraph

**Time stays in the expression language, and the calendar becomes a row.**
The ops gain the missing periods (`weeks`, `months`, `years`, `hours`,
`minutes`), arithmetic on instants, a difference op (`days-between`), and
three calendar readers (`(today)`, `(local-date i)`, `(business-days n)`).
The readers are judged against an engine-served `work_calendar` row through
a new `:calendar` hook, unknown without one, exactly as `follow` is unknown
without `:read`. `:expected-age` may name a deadline expression instead of
a number of seconds. Two new kind declarations are law and are
fingerprinted. `:recurs` makes rows of another kind on a rule, through
`scheduled_action`, one occurrence at a time, with an owner and a stop.
`:occupies` says that a row holds a resource for a window. From it the
engine writes concept 1's `none` guard and a working-hours guard as
trees. The author does not write them.

---

## 2 · Dates and date-times in law

### Periods and durations

```clojure
(days n) (weeks n) (months n) (years n)   ; a Period: calendar arithmetic
(hours n) (minutes n)                     ; a Duration: clock arithmetic
```

| left | `+` / `-` right | result |
|---|---|---|
| date | Period | date (as today) |
| date | Duration | nil, because a date has no hour |
| instant | Duration | instant |
| instant | Period | instant, the same local time that many days later **in the governing calendar's zone** (§3). With no calendar it is unknown, never UTC by accident. |
| Period | Period | Period (as today) |

A month past the 31st is the month's last day (`LocalDate.plus`), as
`java.time` answers. Overflow stays a missing value (`add2`'s `catch`).

### Readers

```clojure
(today)                         ; the date of (now) in the governing calendar's zone
(local-date (data :starts_at))  ; an instant's date in that zone
(days-between a b)              ; whole days from date a to date b; negative when b is earlier
```

- `(today)` adds `:now` and `:calendar` to `:reads`. Its value does not
  come from the reader. A verdict judged for someone in Denver and someone
  in Tokyo is one verdict. `clock`/`day`/`iso` keep the reader's `:zone`,
  because a sentence is for its reader.
- `date-of` stays UTC. It is in fingerprints today, and changing its
  meaning would silently move every law that uses it. `[utc-today]` (§7)
  says when a guard compares `(date-of (now))` with a date field.
- In a derived fact, `(today)` is spelled `(var :today)` with `:today` in
  `:over`, which binds it the way `:now` binds the clock. That keeps the
  rule "derived expressions bind the clock via a declared input"
  (`derived-scope-problems`).

### Worked: the shapes in the ticket

```clojure
'(< (today) (+ (data :signed_on) (days 90)))        ; a prescription is live
'(+ (data :issued_on) (days 30))                    ; net-30 due_on, a derived fact
'(<= (today) (+ (data :quoted_on) (days 30)))       ; the quote is still good
'(<= (today) (+ (data :delivered_on) (days 30)))    ; returns accepted
'(<= (now) (+ (data :visit_at) (hours 24)))         ; the note is signed in time
'(> (days-between (data :due_on) (today)) 15)       ; more than 15 days past due
```

### Clock facts flip in the house's zone

The maintainer's flip candidates for a date field become the midnights
that bound it **in the governing calendar's zone** when the fact reads
`:today`. A fact that reads `(date-of (var :now))` keeps its UTC
midnights. The pattern list grows by one shape, `(op (var :today) <date
expr>)`, where `<date expr>` is a field plus or minus a constant period.
Its flip is the field moved by that period. Anything else still falls back
to the 15-minute re-check.

### `:expected-age` from a date

```clojure
:expected-age {:open 43200
               :sent {:until '(+ (data :due_on) (days 15))}
               :inactive {:until '(+ (data :last_visit_on) (years 7))}}
```

A map value names **when the row is overdue**, not how long it may rest.
The expression reads only `(data …)` and periods. It must evaluate to a
date (overdue after that day ends, in the calendar's zone) or an instant.
`live-cracks` reads it exactly as it reads seconds today: once the moment
has passed, the row is filed once as an `aged` draft ticket. A nil
expression (an unset date) is "no age". It stays advertisement-class, with
no fingerprint facet, because it moves no row. A deadline that must
**change the law** (an expired prescription refuses `refill`) is a guard
that reads `(today)`, not an age.

---

## 3 · Calendars as data — `work_calendar`

### Decision

A calendar is a **row**, not a declaration. A holiday is decided in
December by the person who runs the house, not by a deploy. Rows are
read at judgment, like any row `exists` reads, so adding a holiday moves
verdicts without a law revision. The decision record keeps the calendar's
id and version as evidence, so each verdict says which calendar judged
it.

The name is `work_calendar`, so it never collides with an app's own
`calendar` (`calendar10`).

### Shape

| field | what it is |
|---|---|
| `name` | "Elm Street Landscaping", "Clinic" |
| `zone` | an IANA zone; required |
| `workdays` | a set of weekday tokens, default `mon`–`fri` |
| `hours` | `{from "07:00" to "17:00"}`, local; optional (no hours means the whole day) |
| `holidays` | a list of `{on <date>, name}`; a person edits it with `add_holiday` / `remove_holiday` |
| `default` | true on one row per engine: the governing calendar when nothing names another |

A row may name its own calendar with a `:waymark/ref` field `{:kind
:work_calendar}`. A kind says which field governs with `:calendar
:calendar_id`. Otherwise the default row governs. A crew with its own
hours points at its own calendar.

### Ops

```clojure
(+ (data :starts_on) (business-days 3))   ; skip non-workdays and holidays
(- (local-date (data :starts_at)) (business-days 2))
(business-day? (data :visit_on))
(next-business-day (data :due_on))        ; the day itself when it is one
```

`(business-days n)` is a period that only means something next to a date
and a calendar. Adding it walks the days one at a time, skipping
non-workdays and holidays. The walk is capped at 366 days and returns nil
past the cap. The calendar comes from the `:calendar` hook, which takes the
row's calendar ref or nothing (then the default). No hook means unknown.
A calendar ref that names no row also means unknown, never an exception.

### Where business days are counted

- `:expected-age {:in_billing {:business-days 2}}` counts business days
  from state entry: "to billing within 2 business days".
- `:expected-age {:sent {:until '(+ (data :ordered_on) (business-days 3))}}`
  covers "ships within 3 business days".

---

## 4 · Recurrence — `:recurs`

### Decision

A recurrence is a **declaration on the source kind** (the contract, the
treatment plan, the books), not a row of its own. The rule is law: it
writes rows. A row-level schedule would be a second place where law lives.
The *values* it reads (season start, visit weekday) are the source row's
data, so each contract has its own dates under one rule.

It **builds on `scheduled_action`**, not beside it. The engine keeps
exactly one future occurrence per source row and rule as a
`scheduled_action` targeting the made kind's create door. When that
occurrence runs, the engine schedules the next one. Everything
`scheduled_action` already promises carries over unchanged: the second
judgment, the principal, R-4 approval, the grace window and the outcome
sentence.

### Shape

```clojure
:recurs
[{:name   :visits
  :makes  :visit                                  ; a kind with a :create door
  :every  '(weeks 2)                              ; a Period expression
  :on     #{:tue}                                 ; optional weekdays inside each period
  :from   '(data :season_starts_on)               ; the first occurrence on or after
  :until  '(data :season_ends_on)                 ; a stop: a date expression …
  ;; :times 12                                    ; … or a count, or both
  :while  #{:active}                              ; the source row's states that keep it going
  :holidays :next                                 ; :skip | :next | :previous | :keep (default :skip)
  :lead   '(days 14)                              ; how far ahead the row is made
  :owner  '(data :account_manager_id)             ; whose act the create is
  :input  {:contract_id '(self)
           :visit_on    '(occurrence)
           :crew_id     '(data :crew_id)}}]
```

- `(occurrence)` is the occurrence's date. It is legal only inside a
  `:recurs` `:input`.
- **Occurrences** are a pure function of the rule, the source row's data
  and the calendar. They are listed from `:from`, every `:every`, at each
  `:on` weekday in the first week of the period (or at `:from`'s own
  weekday): `:every (weeks 2) :on #{:tue}` is one Tuesday every two
  weeks, not two. Each one is then moved under `:holidays`, and the list is cut at `:until` and
  `:times`. Pure means testable at the check tier (§6).
- **Shapes in the ticket:** every 2 weeks from April to October is the
  example above. Two visits a week for six weeks is `:every (weeks 1) :on
  #{:tue :fri} :times 12`. A monthly close row is `:every (months 1)
  :while #{:open}` on the books row. Skip company holidays is `:holidays
  :skip`.

### Running it

1. **Arming.** When a source row enters a `:while` state, or its data
   changes a field the rule reads, the engine computes the next occurrence
   on or after `(today)`. It creates a `scheduled_action` with `run_at` =
   that date minus `:lead` (at the calendar's opening hour, else 00:00, in
   its zone, and never earlier than now plus one minute). An outstanding
   one for the same rule is cancelled first. The scheduled action carries a
   new field, `recurrence {source_kind, source_id, rule, occurs_on}`.
2. **Judging.** At `run_at`, besides its own validity, the scheduled action
   checks that the source row is still in a `:while` state and that
   `occurs_on` is still in its occurrence list. If not, it is `skipped`,
   with a sentence naming the rule and the reason.
3. **Idempotency.** The made row's create runs under the idempotency key
   `recurs:<source_id>:<rule>:<occurs_on>`, not `scheduled_action:<id>`.
   Cancelling and re-arming can never make a visit twice for one date.
   The made row records it in an engine-written `recurs_from` field.
4. **Next.** When it lands (or is skipped for the occurrence's own
   reasons), the engine arms the next. When there is none, the rule is
   finished for that row.
5. **Owner.** `:owner` evaluates to a principal id (a member or a seat).
   The run acts as that principal under R-4. An owner who could not make
   the create is refused at arming, in a sentence on the source row's
   history. The rule does not silently run as the engine. An unset owner
   arms nothing, and `live-cracks` files it once.
6. **Stop.** A rule must have at least one of `:until`, `:times` and
   `:while` (`[recurs-no-stop]`). Leaving `:while` cancels the outstanding
   occurrence. Deleting or closing the source does the same.

**Bound.** One outstanding scheduled action per source row and rule. The
scheduler's limit of 100 rows (spec-scheduled-actions *Limits*) does not
count these, because they are bounded by their sources. Only the engine
makes them.

---

## 5 · Availability — `:occupies`

### Decision

Concept 1 already owns the overlap. This concept adds the **declaration
it reads**, so that the overlap rule, the working-hours rule and the time
off are one statement rather than three hand-written guards.

```clojure
;; on :booking
:occupies [{:resource :crew_id            ; a :waymark/ref to the resource kind
            :from     :starts_at          ; instants, or dates with :grain :day
            :to       :ends_at
            :in       #{:scheduled :in_progress}
            :doors    #{:create :reschedule}}]

;; on :time_off — the same crew, held by something that is not a job
:occupies [{:resource :crew_id :from :starts_at :to :ends_at :in #{:approved}
            :doors #{:create :approve}}]

;; on :crew
:calendar :calendar_id
```

- **The resource** is any kind a ref points at: a crew, a therapist, a
  truck. It declares nothing new except an optional `:calendar`.
- **The window** is half-open `[from, to)`. With `:grain :day`, `from` and
  `to` are dates and the window is the whole days `[from, to]`, which is
  "equipment booked once a day".

### What the engine writes

On each door in `:doors` it adds guards as **trees**, fingerprinted like
any authored guard, with names derived from the resource field:

1. `<resource>-is-free`: one `none` per kind that occupies the same
   resource kind, in concept 1's exact form, with the row itself left out.
   For a booking's crew:
   ```clojure
   '(none [b :booking  {:crew_id (= (input :crew_id)) :starts_at (< (input :ends_at))
                        :ends_at (> (input :starts_at)) :state (in "scheduled" "in_progress")}])
   '(none [t :time_off {:crew_id (= (input :crew_id)) :starts_at (< (input :ends_at))
                        :ends_at (> (input :starts_at)) :state (in "approved")}])
   ```
   The refusal's evidence and remedies are concept 1's: the oldest
   grant-visible match, `{b.field}` in the sentence, and `first` for the
   free crew.
2. `<resource>-is-working`: `(working? (input :starts_at) (input
   :ends_at) (follow (input :crew_id) :calendar_id))`. It is true when the
   window falls on workdays that are not holidays, inside `hours`, in the
   resource's calendar. It is unknown without the `:calendar` hook.

When a door's input does not carry the field (`reschedule` takes only the
times), the generated form reads `(data …)` for the missing side. This is
the one place where the derivation needs both scopes. An author who wants
a different sentence names the generated guard in `:explain` overrides,
not by writing their own copy.

### Open slots

`(next-free <resource> <from> <grain>)` is **evidence-only**, like
`first`. It is the next window of the same length on that resource that
is working and free. It scans at most 30 business days and runs only when
the guard refuses. It gives the sentence "the crew is next free Monday at
8:00" and a remedy binding `{:door :booking/reschedule :input {:starts_at
(evidence :slot)}}`.

---

## 6 · Honest on the probe, in rehearsal, in scenarios (c6e4e283)

The concept-1 rule holds unchanged. **An op whose hook is missing is
unknown, and unknown is pending, never refused.**

| Where | `:calendar` hook | `(today)`, `business-days`, `working?` |
|---|---|---|
| Render probe | lent by the router | judged; otherwise `pending: ["reads :calendar"]` |
| `dry_run`, invoke | the real ctx | judged in full |
| law-sweep | `render-hooks` plus `:calendar` | judged in full |
| scenarios, check tier | **new:** served from the scenario | judged, not deferred |

**Scenarios set the clock and the calendar.** `:at` stays the moment.
There are two new keys:

```clojure
{:kind :booking :attempt :create
 :at "2027-05-13T14:00:00Z"
 :calendar {:zone "America/Denver" :workdays #{:mon :tue :wed :thu :fri}
            :hours {:from "07:00" :to "17:00"} :holidays ["2027-05-31"]}
 :given [{:kind :booking :data {:crew_id "elm" :starts_at "…" :ends_at "…"} :state "scheduled"}]
 :input {:crew_id "elm" :starts_at "…" :ends_at "…"}
 :expect {:refused :crew_id-is-free :because "Parks"}}
```

- A scenario whose law reads `:calendar` and that names no `:calendar` is
  refused, as a missing `:at` is today. The engine never makes up a
  plausible calendar.
- **A recurrence scenario** asks for the occurrence list, which is a pure
  function, so it always runs at the check tier:
  ```clojure
  {:kind :contract :recurs :visits
   :row {:state "active" :data {:season_starts_on "2027-04-01" :season_ends_on "2027-10-31"}}
   :calendar {…}
   :expect {:occurrences {:first "2027-04-06" :last "2027-10-19" :count 15}}}
  ```

---

## 7 · Fingerprint and audit

**Fingerprint.** The new ops are nodes of the canonical tree. `(weeks n)`
normalizes to `(days (* 7 n))`, so the two spellings are one law.
`:recurs` and `:occupies` are law facets, because they write rows and
refuse writes. A change to either is a revision. The guards that
`:occupies` derives hash as their trees, so an author's hand-written twin
of the same law has the same fingerprint. `:calendar` (which field
governs) is law. A `work_calendar` row's contents are data.
`:expected-age`, `:until` forms included, stays advertisement-class.

**Audit findings.** These belong to the declaration battery in `checks.clj`
(`make check-queue`). Each finding has its sentence, its remedy and one
plain question for the person who owns the kind:

| Finding | Level | Says | Remedy | Question |
|---|---|---|---|---|
| `[date-unread]` | info | A `:waymark/date` or `:waymark/instant` field that no guard, fact, age, recurrence or occupancy reads. | Give it a deadline (an `:expected-age :until`), a guard, or a new `:record-only true` entry property that says it is kept, not acted on. | "When {field} passes, should anything happen or anyone hear?" |
| `[deadline-untold]` | warning | An `:expected-age :until` on a state that no seat is woken to, so the aged ticket reaches nobody (it reads the cracks audit). | Wake a seat on that state, or add a notice rule. | "Who should hear when a {kind} is past {field}?" |
| `[recurs-no-owner]` | error | A `:recurs` rule with no `:owner`. | Name the field or seat whose act the made row is. | "Whose name goes on each {makes} this makes?" |
| `[recurs-no-stop]` | error | A rule with none of `:until`, `:times` or `:while`. | Add the season's end, a count, or the states that keep it going. | "When should the {makes}s stop coming?" |
| `[recurs-unmakeable]` | error | `:makes` has no create door, or `:input` misses a required create field. | Fill the input from the source row. | — |
| `[occupies-unfiltered]` | error | The resource, `from`, `to` or state field is not `:filter` on the occupying kind (`[exists-unfiltered]`'s reason). | Declare them filterable. | — |
| `[resource-uncalendared]` | info | A kind is occupied, and neither it nor the default names hours. | Give the resource a calendar, or accept the whole day. | "What hours does a {resource} work?" |
| `[utc-today]` | warning | A guard or fact compares `(date-of (now))` (or `(var :now)`) with a date field. | Use `(today)`, which is the house's day and not Greenwich's. | — |
| `[business-days-no-calendar]` | finding | The law uses `business-days`, `(today)` or `working?`, and the engine has no default `work_calendar`. It never stops a boot: the boot makes the house's default (Monday to Friday, the house zone, no holidays) and says so. | Restate the default, or add the house's holidays. | "Which days does the house work, and in which zone?" |

---

## 8 · Migration — the 25 workarounds

The ticket gives the totals by app (shop 7, clinic 10, landscape 8) and
the shapes. It does not give the count per shape, and this spec does not
guess one. Step 12 counts them against the logs. Each shape, and what
replaces it:

| Shape | Replaced by |
|---|---|
| A deadline from a date in the data: expiry, net-30, quote validity, materials before a start, returns, a note within 24h, the 7-year purge | §2 arithmetic plus `(today)` in a guard; `:expected-age :until` where someone must be told |
| Past due by N days | `days-between` in a guard or derived fact |
| Business days and holidays | §3 `work_calendar` and `business-days` |
| Recurrence: a season, N a week, a monthly close | §4 `:recurs` |
| Availability: one job per crew or therapist, equipment per day, open slots, the next free business day | §5 `:occupies` and `next-free` |

---

## 9 · The film

The showcase is the epic's, and this concept adds its second half:

1. The owner books the Hendersons' patio for Thursday with the Elm crew.
   The refusal reads: *That crew is already at the Parks' yard from 8:00
   to 12:00. The Oak crew is free.* This is concept 1's sentence, now
   produced by `:occupies` with no hand-written guard.
2. The owner books it on Monday 31 May instead, which is a holiday in the
   house's calendar. The refusal: *Monday 31 May is Memorial Day. The Elm
   crew is next free Tuesday at 8:00.* (`crew_id-is-working`, `next-free`).
3. The owner signs the Parks' contract: every second Tuesday from 1 April
   to 31 October, with a 14-day lead. The first visit appears on the
   contract at once, because its date is inside the lead. Its history line
   says *made by the visits rule of the Parks' contract, for Tuesday 6
   April*.

On a demo clone the walk's clock is the scenario clock (§6), so step 3 can
also show a second visit appearing when the clock passes the first.

---

## 10 · Not in this spec

- Times without dates ("every weekday at 9:00") for seats. Seat cadence
  stays `cadence_seconds`.
- Recurring rows that are edited one by one as a series ("move all future
  visits"). A made row is an ordinary row after it is made.
- Several calendars on one row (a crew's calendar intersected with the
  customer's). One calendar governs per judgment.
- Capacity above one ("two chairs"): `count-rows` against a number is
  already concept 1's, and `:occupies` gains `:capacity` when a case asks.
- The reaction half of the scene (the payment moves the job to paid) is a
  later concept.

---

## 11 · Build plan

Each step is one ticket and one pull request, in order. Steps 1 to 4 add
language, 5 to 8 add declarations, and nothing in an app moves until
step 9.

1. **Periods and instant arithmetic.** `weeks`, `months`, `years`,
   `hours`, `minutes`; instant ± Duration; `days-between`; `weeks`
   normalized to `days`. Tests: the §2 table, and that no existing law's
   fingerprint moves.
2. **`work_calendar` and the `:calendar` hook.** The engine-served kind,
   its doors, the default row, the hook on the invoke ctx, the render
   probe and the law-sweep. Test: the hook's answers, and `unknown`
   without it.
3. **Calendar readers.** `(today)`, `(local-date …)`, instant ± Period in
   the zone, `business-days`, `business-day?`, `next-business-day`,
   `working?`, `:today` in `:over`, `[utc-today]`,
   `[business-days-no-calendar]`.
4. **Clock facts in the house's zone.** The maintainer's flip candidates
   for `:today` facts and the field-plus-period shape. Test: a net-30
   overdue fact flips at the Denver midnight.
5. **`:expected-age` from a date and in business days.** `live-cracks`
   reads `:until` and `:business-days`; `[date-unread]`,
   `[deadline-untold]`.
6. **Scenario `:calendar`.** `offline-ctx` serves the hook from the
   scenario; a calendar-reading law without one is refused.
7. **`:occupies`.** The declaration, the derived `-is-free` and
   `-is-working` trees, the data-side fill for doors without the field,
   `[occupies-unfiltered]`, `[resource-uncalendared]`. Test: the
   landscaping fixture pair from concept 1 step 12 migrated to `:occupies`,
   with the same refusal sentence.
8. **`:recurs`.** The occurrence function (pure, with recurrence scenarios
   at the check tier), the `recurrence` field on `scheduled_action`,
   arming, the source re-check, the `recurs:` idempotency key, the owner
   rule, `[recurs-no-owner]`, `[recurs-no-stop]`, `[recurs-unmakeable]`.
9. **`next-free` in evidence.** Bounded at 30 business days; the holiday
   refusal of film step 2.
10. **Landscape.** The rehearsal's crew, booking, time off and contract
    kinds take `:occupies` and `:recurs`; the film's three steps are its
    acceptance walk.
11. **Clinic and shop.** Prescription expiry, note-within-24h, net-30,
    returns, purge, ships-within: guards and ages, each with a scenario.
12. **Survey.** Re-read the three rehearsal logs, check §8's counts, and
    file one ticket for each workaround these steps did not retire.
