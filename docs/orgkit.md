# The org kit

An org is code-first: a thin directory of declarations that a seat
writes and the factory builds. Boot, conformance, onboarding and
clones all read this one shape. `orgkit/template/` is the starter
directory every org copies; `orgs/example/` is a filled copy, a tiny
two-kind org with one seat.

## The shape

```
orgs/<org>/
  deps.edn                 waymark10 and factorykit by local root
  org.edn                  the manifest: {:name "<org>" :live false}
  src/<org>/org.clj        the module: resources, check-resources, domain
  src/<org>/kinds/*.clj    one defresource per kind, guards as defguard sentences
  resources/<org>/seed.edn what a fresh engine holds at boot
  resources/<org>/week.edn the first week, as a script (§ The week)
  README.md                what each file is
```

To start an org, copy `orgkit/template/` to `orgs/<org>/` and replace
every `myorg` with the org's name: the directories under `src/` and
`resources/`, the namespaces, the domain keyword and the seed. The
`deps.edn` roots are relative to `orgs/<org>/`, so a copy keeps them.
`clojure -M:check` in the org's directory runs the declaration gate,
and `factory10.orgkit-test` loads the template and the example.

## The manifest

`org.edn` is the org's manifest: at least `{:name "<org>" :live
false}`. The template writes it with `:live false`, and going live
flips it to `:live true`. The conformance suite requires it: a missing
one is the finding `manifest/missing/<org>`. The house engine reads
it: at boot it lists the org directories under `WAYMARK10_ORGS_DIR`
(`../orgs` from the engine's working directory unless set; the image
copies `orgs/` there), keeps the live ones, loads each one's `src` and
`resources` through its own class loader, then serves its kinds and
runs its seed, as `WAYMARK10_ORG` does for one org on the classpath.
No live org is a deps root of the engine.

## Naming rules

- The org name is the namespace root and the domain name: org
  `example` is `example.org`, `example.kinds.*`, `resources/example/`
  and the domain `:example`. It is snake_case: `[a-z][a-z0-9_]*`.
  The namespace root is the org's name exactly, underscores included:
  org `two_words` is `(ns two_words.org)` and `two_words.kinds.*`,
  never `two-words.org`.
- A kind name is the org's to choose, but it may not be one the
  engine already enrols (`job`, `member`, `grant`, `seat`, `ticket`
  and the rest of `waymark10.modules/inventory`): the registry holds
  one law per kind. That is why the example's work is a `work_order`.
- The module is `<org>.org`, and it declares `resources` (its kinds,
  stamped by `in-domain`) and `check-resources` (zero-arg, for the
  gate).

## Writing a kind

A kind is one `defresource` in `src/<org>/kinds/`, its guards as
`defguard` sentences: `(defguard name (refuse "the sentence") 'law)`.
`orgkit/template/src/myorg/kinds/item.clj` carries each form below as
a comment; `docs/spec-related-row-law.md` is the whole grammar.

**Reference forms.** A law reads a field through a reference form:
`(data :k)` is what the row already holds, `(input :k)` is what the
caller sent to this door. A bare keyword anywhere else is refused at
load ("keyword outside a reference form"); `(present? :k)` is the one
shorthand that takes one. `(is-set (input :k))` asks whether an
optional input was sent.

**A related-row guard** names other rows: `exists` holds when one
matches, `none` when none does. The binder is `[name kind where]`;
`where` maps a field of the other kind to `(op <expr>)`, with `op` one
of `= not= < <= > >=` or `(in v …)`. The binder's name is the
refusal's `{name.field}`:

```clojure
(defguard crew-is-free
  (refuse "{b.title} already has this crew that day; pick another crew or day.")
  '(none [b :booking {:crew  (= (input :crew))
                      :day   (= (input :day))
                      :state (in "scheduled" "in_progress")}]))
```

`(follow (data :customer_id) :status)` reads one field of the row a
ref names.

**A door's input.** A door that takes arguments declares them as a
malli map; its guards read them as `(input :k)`:

```clojure
:schedule {:from #{:requested} :to :scheduled
           :input [:map
                   [:crew [:string {:min 1 :max 80}]]
                   [:day :waymark/date]]
           :guards [crew-is-free]
           :safety {:idempotent true :reversible true :confirm false}}
```

**Dates.** `:waymark/date` is a calendar date, an ISO string on the
wire (`"2026-10-22"`); `:waymark/instant` is a point in time, RFC 3339
on the wire. A schema field is `[:due_on {:optional true} [:maybe
:waymark/date]]`. Keep dates out of plain strings.

**Fields that hold an id.** The `unref'd-ids` check refuses a short
string field (a `:max` of 64 or less, not a `prose` widget) whose name
reads as an id and that nothing declares a ref: a name ending `_by`,
one of `owner author caller member sitter person`, or a served kind's
name, bare or with `_id` (`request`, `request_id`). Declare a row ref
with its kind, a member with `:x-ref {:principal true}`, or waive a
field that holds no id with `{:not-a-ref "why"}`:

```clojure
[:request_id {:optional true :kind :request} [:maybe :waymark/ref]]
[:checked_by {:optional true :x-ref {:principal true}} [:maybe [:string {:max 80}]]]
```

**Money.** Hold money as `:waymark/money`. The `[money-no-currency]`
warning names an `:int` or `:decimal` field whose name or label says
money (`price`, `amount`, `fee`, `copay`, `*_cents`, a `$` in the
label). Waive a field that is no amount — a rate, or an estimate never
posted — with `{:not-money "why"}`, the way `{:not-a-ref "why"}` waives
an id:

```clojure
[:est_cost_cents {:optional true :not-money "household estimate in cents, never posted"} [:maybe :int]]
```

## Door-time inputs

Some values belong to a move, not to the row's birth: the day the
letter was signed belongs to `engage`, the crew that took the job to
`schedule`, the amount billed to `invoice`. The person knows them only
when the door runs. Ask for them there, in three places on the one
door:

1. **Declare it as the door's input**, in the door's `:input` map.
   A required entry is the schema's to refuse when it is missing.
2. **Write it to the row** with the door's handler,
   `(r/field-writer [:k …])` from `waymark10.resource` (require it
   `:as r`). It writes the named fields the caller sent and nothing
   else; without it the input is judged and then dropped. Keep the
   field in the kind's `:schema` as optional, so the row holds it after.
3. **Guard on it as input**: the guards run before the write, so they
   read `(input :k)`, not `(data :k)` or `(present? :k)`.

The R&D tax org's `engage` takes the day its letter was signed, and
refuses a day still to come:

```clojure
(defguard letter-signed
  (refuse "Nothing starts until the client signs the engagement letter; give the day they signed, not one still to come.")
  '(<= (input :letter_signed_on) (today)))

;; :schema    [:letter_signed_on {:optional true} [:maybe :waymark/date]]
:engage {:from #{:qualified} :to :engaged
         :input [:map [:letter_signed_on :waymark/date]]
         :handler (r/field-writer [:letter_signed_on])
         :guards [letter-signed]
         :safety {:idempotent true :reversible false :confirm false
                  :one-way "An engaged prospect is a client; its studies carry it from here."}}
```

Its `records_in` is the same shape with two inputs,
`[:records_complete_on :waymark/date]` and `[:records_confirmed_by
{:x-ref {:principal true}} [:string {:max 80}]]`, both in its
`field-writer`. The law page reads the guard's sentence as the
door's rule:

```
- **engage**: a qualified prospect becomes engaged.
  - Rule: Nothing starts until the client signs the engagement letter; give the day they signed, not one still to come.
  - It cannot be undone: An engaged prospect is a client; its studies carry it from here.
```

**When not to.** A value known when the row is written belongs on
create, in the `:schema` as a required field: the company's name, the
tax year. A door input is for what only the move knows.

**The smell.** A create field that is null on every create and
filled by an edit right after, or a door's value kept in a free
`notes` field, is a door-time input written in the wrong place. Move
it to the door that needs it.

## Calendar time

A deadline, a business day, a visit every other Tuesday and a crew
booked once are law, not hand-written guards. This is the part of
`docs/spec-calendar-time.md` an org author uses; the examples are in
the shape of `orgs/example`'s kinds.

**Periods.** `(days n) (weeks n) (months n) (years n)` are calendar
arithmetic on a date; `(hours n) (minutes n)` are clock arithmetic on
an instant. A date plus a period is a date (a month past the 31st is
the month's last day); an instant plus hours is an instant; a date plus
hours is nil, as a date has no hour. `(weeks 2)` is `(days 14)`, one
law. `(days-between a b)` is the whole days from date `a` to date `b`,
negative when `b` is earlier:

```clojure
(defguard prescription-is-live
  (refuse "The prescription was signed {data.signed_on} and ran out after 90 days.")
  '(< (today) (+ (data :signed_on) (days 90))))

(defguard note-in-time
  (refuse "The note is due within a day of the visit.")
  '(<= (now) (+ (data :visit_at) (hours 24))))

(defguard long-past-due
  (refuse "This invoice is not yet 15 days past due.")
  '(> (days-between (data :due_on) (today)) 15))
```

**Readers and the house zone.** `(today)` is the date of `(now)` in
the governing calendar's zone: the house's day, the same for every
reader. `(local-date (data :starts_at))` is an instant's date in that
zone. `(date-of (now))` stays Greenwich's day; do not compare it with
a date field. A derived fact spells the day `(var :today)` and names
`:today` in `:over`:

```clojure
:derived {:overdue {:over [:issued_on :today]
                    :expr '(> (var :today) (+ (var :issued_on) (days 30)))}}
```

The business-day readers walk the governing calendar's workdays and
holidays:

```clojure
'(<= (today) (+ (data :ordered_on) (business-days 3)))   ; ships within 3 business days
'(business-day? (data :visit_on))
'(= (data :due_on) (next-business-day (data :due_on)))   ; the day itself when it is one
'(working? (input :starts_at) (input :ends_at))          ; on a workday, inside hours
```

`(working? from to)` takes a third argument, a calendar ref, when the
resource names its own calendar. Every reader is unknown, so pending
and never refused, where no calendar governs; `business-days` walks at
most 366 days.

**`work_calendar`.** A calendar is a row the engine serves, not a
declaration, so a holiday added in December moves verdicts without a
deploy. Its fields are `name`, `zone` (IANA, required), `workdays`
(default `mon`–`fri`), `hours` `{from "07:00" to "17:00"}` (none means
the whole day), `holidays` (kept by the `add_holiday` and
`remove_holiday` doors) and `default`, true on the one row that governs
when nothing names another. A kind whose rows keep their own calendar
holds a ref to one and names it with `:calendar`:

```clojure
(defresource crew
  {:kind :crew
   :calendar :calendar_id
   :schema [:map
            [:name [:string {:min 1 :max 80}]]
            [:calendar_id {:optional true :kind :work_calendar} [:maybe :waymark/ref]]]
   ...})
```

A scenario whose law reads the calendar names one, as it names `:at`;
the engine never makes one up:

```clojure
{:kind :work_order :attempt :ship
 :at "2027-05-13T14:00:00Z"
 :calendar {:zone "America/Denver" :workdays #{:mon :tue :wed :thu :fri}
            :hours {:from "07:00" :to "17:00"} :holidays ["2027-05-31"]}
 ...}
```

**`:expected-age` from a date.** Beside seconds, a state's age may be
`{:business-days n}`, counted from state entry, or `{:until <form>}`,
the date or instant after which the row is overdue. `:until` reads only
`(data …)`, periods, the calendar and one hop to a linked row,
`(follow (data :payment_id) :cleared_on)`, so a deadline counts from
the date where it lives instead of a copy; an unset ref or date is no
age. Once it
passes, the row is filed once as an `aged` draft ticket. A deadline
that must change the law (an expired prescription refuses `refill`) is
a guard over `(today)`, not an age:

```clojure
:expected-age {:open        43200
               :in_billing  {:business-days 2}
               :sent        {:until '(+ (data :due_on) (days 15))}
               :inactive    {:until '(+ (data :last_visit_on) (years 7))}}
```

A dated age is proved by an `:age` scenario at the check tier, read by
the same deadline function the live pass uses — so do not write a warn
guard that restates the age just to have something to test. `:due` is
the last day still on time (or the deadline's instant); `:late` is
judged at `:at`; a `{:business-days n}` age counts from `:entered`. The
scenario's `:calendar` gives the zone and the holidays:

```clojure
{:kind :claim :age :submitted
 :row {:state :submitted :data {:submitted_on "2027-03-01"}}  ; :until '(+ (data :submitted_on) (days 30))
 :calendar {:zone "America/Denver"}
 :expect {:due "2027-03-31"}}

{:kind :claim :age :denied                      ; {:business-days 2}
 :entered "2027-05-28T15:00:00Z" :at "2027-06-02T15:01:00Z"
 :calendar {:zone "America/Denver" :workdays #{:mon :tue :wed :thu :fri}
            :holidays ["2027-05-31"]}
 :expect {:due "2027-06-02" :late true}}
```

`check` counts the dated ages an `:age` scenario names beside the
refusing guards.

**`:recurs`.** A rule on the source kind makes rows of another kind on
its dates. The engine keeps one `scheduled_action` ahead per source row
and rule, run as `:owner` under that principal's grant, and never makes
two rows for one date. `:every`, `:from`, `:until`, `:lead` and
`:owner` are law over the source row; `(occurrence)` is the date, legal
only in `:input`, and `(self)` the source row's id:

```clojure
:recurs [{:name     :visits
          :makes    :visit
          :every    '(weeks 2)
          :on       #{:tue}                     ; weekdays in the period; else :from's day
          :from     '(data :season_starts_on)
          :until    '(data :season_ends_on)     ; or :times 12, or both
          :while    #{:active}                  ; leaving these cancels what is armed
          :holidays :next                       ; :skip (default) | :next | :previous | :keep
          :lead     '(days 14)
          :owner    '(data :account_manager_id)
          :input    {:contract_id '(self)
                     :visit_on    '(occurrence)}}]
```

The dates are a pure function, so a scenario lists them at the check
tier:

```clojure
{:kind :contract :recurs :visits
 :row {:state "active" :data {:season_starts_on "2027-04-01" :season_ends_on "2027-10-31"}}
 :calendar {:zone "America/Denver"}
 :expect {:occurrences {:first "2027-04-06" :count 15}}}
```

**`:occupies`.** A row holds a resource (any kind a ref names) for the
window `[from, to)` while it is in `:in`; `:grain :day` reads two dates
as whole days. On each door in `:doors` the engine adds two guards,
named from the resource field: `crew-is-free`, a `none` over every kind
that occupies the same resource kind, and `crew-is-working`, `working?`
in the resource's calendar. On an instant window a refusal carries
`next-free`: the next window as long, within 30 business days, in which
the crew is working and free, and a remedy that takes the door again
there. `:explain {:crew-is-free "…"}` replaces a derived sentence;
do not write your own copy of the guard:

```clojure
;; on :booking
:occupies [{:resource :crew_id :from :starts_at :to :ends_at
            :in #{:scheduled :in_progress} :doors #{:create :reschedule}}]
;; on :time_off, the same crew held by something that is not a job
:occupies [{:resource :crew_id :from :starts_at :to :ends_at
            :in #{:approved} :doors #{:create :approve}}]
```

**What each replaces.**

| The workaround | Write instead |
|---|---|
| A deadline from a date: expiry, net-30, quote validity, returns, a note within 24h, a purge | period arithmetic and `(today)` in a guard; `:expected-age :until` where someone must be told |
| Past due by N days | `days-between` in a guard or derived fact |
| Business days and holidays | a `work_calendar` row and `business-days` |
| A season, N a week, a monthly close | `:recurs` |
| One job per crew, equipment per day, open slots, the next free day | `:occupies`, and the `next-free` it derives |
| A rain-delay door that takes a typed new date, with no calendar and no crew availability | an `:occupies` entry's `:bumps #{:rain_day}`, the door `:bulk {:atomic true}`, called with `where` (crew, day) |

**Findings you may meet.**

- `[utc-today]`: a guard or fact compares `(date-of (now))` with a
  date field; use `(today)`, or `(var :today)` in a fact.
- `[date-unread]`: a date or instant field nothing reads; give it a
  guard or an `:expected-age :until`, or mark it `:record-only true`.
- `deadline_untold` (cracks audit): an `:until` age on a state no seat
  is woken to; wake a seat there, or add a notice rule.
- `[recurs-no-owner]`: name `:owner`, the field or seat whose act each
  made row is.
- `[recurs-no-stop]`: give the rule `:until`, `:times` or `:while`.
- `[recurs-unmakeable]`: `:makes` names a kind with a create door, and
  `:input` fills each field that door requires.
- `[occupies-unfiltered]`: declare the resource, `from` and `to`
  fields `:filterable` on the occupying kind.
- `[resource-uncalendared]` (info): the occupied kind names no
  `:calendar`, so the default row's hours judge it; name one when its
  hours differ.

## The seed

`resources/<org>/seed.edn` is the demo seed's grammar
(`docs/spec-demo-clones.md` § 1), with an org's own sections beside
it:

- `:seed`, `:version` and `:org`, the org's name.
- `:domains`: each `{:name, :label}`; the first is the org's own. The
  first boot makes each a `domain` row through its create door, as the
  owner and before the seats: its charter is its `:charter` or its
  `:label`, its budget its `:budget_usd_per_week` or the factory's 3600.
  A `:ref` names the row, so a seat template's `:domain_id` can be
  `[:ref …]`.
- `:cast`: the members, as in the demo seed. On the house, the
  `:owner`'s `:id` is the filing person's member id, a member the seed
  reuses; the onboarding seat writes it from the plan's owner. An org
  whose seed is refused at boot is skipped with a draft ticket, and the
  house boots without it. A member may declare
  `:scope`, in a seat's own shape (`[{:kind "request" :actions
  ["accept"]}]`, `"*"` for every action of a kind): its ceiling, what
  any seat it owns may hold. A member with no `:scope` holds the whole
  org: every kind and action the engine serves.
- `:seats`: each a factorykit seat template and its params,
  `{:template "mayor" :params {...}}`, or a whole seat map under
  `:seat` when no template walks the org's kinds. Either may name
  `:owner`, a `:cast` key: the member whose ceiling the seat's scope
  sits inside. A seat with no owner is judged only against the kinds
  the engine serves.
- `:judgments`: each a factorykit judgment template and its params.
- `:steps`: the starting rows, each an ordinary create or invoke by a
  cast member, as in the demo seed.
- `:rehearsal`: the outside world, `{:cast {...} :seats [...] :steps
  [...]}`, which only a clone loads (ticket a4149376): the loader merges
  it onto the seed when the engine boots as a clone and drops it
  everywhere else, so a live org never holds a pretend client nor
  seats the world. Its cast are the people outside the business who
  start work, each with a `:scope` that creates the kind they send;
  its seats hold the `world` template. A seed whose own `:seats` hold
  an `acts_as_cast` seat may set `:rehearsal-seats :skip-off-clone` in
  org.edn: off a clone the loader skips that seat with a line instead
  of refusing it. Conformance answers `no-outside-world/<org>` for a
  seed with no such cast member or no world seat; `:world :none` in
  org.edn waives it for an org that only works inside.

The loader that walks it on a fresh engine is M1b's.

## The week

`resources/<org>/week.edn` is the org's first week as a script, beside
the first-week scene the director films: each day's calls, in order,
and who makes each.

```clojure
{:day-one "2026-10-21"
 :goal {:kind "prospect" :state "engaged"}
 :days [{:day 1
         :calls [{:as :world :kind "prospect" :action "create" :name "hillside"
                  :input {:company "Hillside Labs"}}
                 {:as :mayor :kind "prospect" :action "qualify" :row "hillside"
                  :input {:note "Referred by Jordan Lee."}}]}
        {:day 2
         :calls [{:as :mayor :kind "study" :action "create"
                  :input {:prospect_id {"$row" "hillside"}}}]}]}
```

- `:as` is a cast role: `:world`, a rehearsal cast member (the call's
  `:cast` key, or the first); `:mayor`, the org's mayor, an agent
  acting for the owner; or `:person`, the seed's `:owner`.
- A call with `:row` walks that row's `:action`; one without it walks a
  create door of the kind. `:name` names the row a create made, and
  `{"$row" "hillside"}` anywhere in a later input is that row's id.
- A day's `:person-allows` is `:today` unless it says `:yesterday` or
  `:none`: after the day's calls the owner allows each call held that
  day, as the director does, since a held call expires 24h after it is
  made. `:yesterday` allows them at the start of the next day instead,
  and the report warns `[week-allow-late]` beside each such allow: its
  call had expired by then. `:none` allows nothing that day.
- `:goal` is the payoff: the rows of that kind in that state.
- The first-week scene plays the week as the camera rolls: day 1 in
  full, a later day only the calls marked `:show true` (with no mark, a
  person's call and the day's last); a call not shown is still played,
  its shots trimmed. A person's call on a `:row` is filmed as the
  owner's tap on that row's held call, framing the fields its `:focus`
  lists.
- `:card`, on the week or on a day, is a line of text of at most 280
  characters: the week's (the seed's) opens the first-week scene as a
  full-screen card, and each day's comes before that day's shots.

`clojure -M -m workqueue10.week <org-dir> [--json]`, from
`workqueue10/`, plays it headlessly. It boots the org in-process as an
org clone over a memory store, seeded with its rehearsal, sets the
demo clock to 09:00 UTC on `:day-one`, makes each day's calls through
the engine's doors as their cast, and shifts the clock P1D between
days. The mayor wears a grant the owner mints over the doors the
script's mayor calls name. It prints a report: per call its day, cast,
kind, action and outcome, `moved`, `held` or `refused`, with the guard
and its sentence when refused; `person_asks`, the calls held for the
owner; and the goal's rows. It exits non-zero only for a broken script
(a row name no earlier call made, a cast or kind it does not know),
never for a refusal: a refusal is part of the report. Nothing renders
and nothing leaves the process. `orgs/week_fixture` is a week that
plays a refusal, a hold allowed the same day, and its payoff.

The week is judged three ways, each an error that blocks propose:
`[week-stalls]`, one for each refused call, naming its day, door and
guard; `[week-no-ask]` when no call was held for the owner's tap; and
`[week-no-payoff]` when the goal counts no row by the last day. The
audit's `org-findings` job plays the week.edn and hands the report to
the findings pass, which records these findings on the org_plan and
the compact week as `dry_week`; an org with no week.edn carries
`[week-missing]`, a warning for now. The bench check
(`cd workqueue10 && clojure -M:check`) plays and judges the week of
each org the change touches, so a seat sees the three before it
submits.

## Conformance

`factorykit.conform` judges an org the way the engine would:
`(conform org-dir)` answers findings, each `{:key :severity :class
:subject :sentence :remedy}`, and an org passes when it answers none.
It loads the module, fills the seed's seats, runs the defresource and
registry checks, the cracks audit over the kinds and the seed's seats,
the sentence checks on guards and confirm doors, a memory engine's
discover, schema, query, get and dry-run create for every kind, and
the seats' scopes against the kinds the engine serves and against
each owner's ceiling, and the person's business questions (the
`decision` class, below).
`clojure -M:conform` runs it in an org's directory, and
`clojure -M:conform ../orgs` in `factorykit/` runs it over every org;
CI runs the latter.

## Decisions

The `decision` class is the questions only the org's person can
answer. Each finding's sentence is the question, put to the person in
their words; the onboarding seat asks it and writes the answer into
the code, and the finding clears on the next run. It reads only the
declarations and the seed's seats:

- `decision/unheld_final/<kind>`: a door with `:safety {:reversible
  false}` that a seed seat no person sits holds, with no guard that
  holds it. "Should the AI do this on its own, or wait for a tap?"
- `decision/no_wait_limit/<kind>/<state>`: a state that is not
  terminal and has no expected age. "How long may a row stay here
  before someone is told, and who?"
- `decision/money_unbounded/<kind>/<action>`: a door a model seat
  holds whose input names money (`amount`, `fee`, `price`, a field
  ending `_cents` or `_usd`) that none of its guards reads, with no
  guard that holds it. "Above what amount should this wait for you?"
- `decision/decided_says_held/<kind>/<action>`: a door a model seat
  holds whose `:decided` sentence says it waits for a person ("only
  on", "'s tap", "waits for", "never on its own") while no guard
  holds it. Add a `:hold` guard for that person, or reword the
  decision if the AI may act alone; `:decided-held {:none "why"}`
  keeps the sentence as narrative, and the law page says so.

Each clears in code, one of two ways. Either the law says it:

- a hold on the door, so an agent's call waits as a held_call for the
  person's tap while a person's own call goes through. It is one
  line, and the one way to write "wait for me":
  `(defguard principal-signs-off (hold "A study is signed off only on The Principal's tap."))`
  (unheld_final). Its law is optional; with one, it holds only while
  the law is false, so for money it reads the amount and holds above
  the line:
  `(defguard big-spends-wait (hold "Spending $5,000 or more waits for your tap.") '(< (data :amount_usd) 5000))`
  (money_unbounded). By default the tap is the agent's own person's;
  `:for :role` after the sentence names whose it is instead, read from
  the seed's cast roles, and the Allow door refuses a person without
  that role:
  `(defguard return-is-filed (hold "A return is filed only on a tax specialist's tap." :for :role "tax_specialist"))`;
- the kind's `:expected-age {<state> <seconds>}` (no_wait_limit).

Or the person's answer is written down as `:decided`, a sentence in
their own words:

- on an action, `:decided "The mayor marks an item done on its own
  once its assignee has done the work."` (unheld_final,
  money_unbounded);
- at a kind's top level, `:decided {<state> "sentence"}`, as
  `:decided {:open "An item may stay open for a week; past that, the
  mayor tells the owner."}` (no_wait_limit).

`:decided` is prose, never law: the engine does not enforce it, so an
answer of "wait for me" is a `:hold` guard, its reason the guard's
`:explain`, and `:decided` says only what the AI may do on its own.
`factory10.law-text` renders every one under "What you decided", a
kind's states first and then its doors, so the person reads back what
they said. `orgkit/template/` and `orgs/example/` answer theirs this
way.

## What an org may not do

- No engine code: an org declares kinds, guards and its seed. A
  framework gap is a ticket against `waymark10`, not a workaround in
  the org.
- No new top-level deps beyond the kit: `deps.edn` names Clojure,
  waymark10 and factorykit and nothing else.
