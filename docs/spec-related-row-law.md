# Spec: declarative related-row law

Concept 1 of epic `2bb0aed8` (ticket `9fb85e01`). A spec only: no engine
code lands with it. Its last section is the build plan the implementation
child is groomed from.

> A landscaping owner books the Hendersons' patio for Thursday with the
> same crew already at the Parks' yard. The house refuses the booking the
> moment it is made, in one sentence naming the Parks' job and the free
> crew.

That refusal is a law about **another row**. Today waymark can only say
it in code, and code is the one place the audit, the fingerprint and the
check tier cannot see into. This spec gives the three common shapes of
related-row law a spelling in the expression language, so they are data
like every other law.

---

## 0 · Today

- A declarative law reads the row, the input and the clock
  (`docs/waymark10-vocabulary.md` §1–§2, `waymark10.expr/ops`).
- A **derived aggregate** reads other rows, but only as a stored fact:
  `{:count|:sum {:owns <child> | :related <edge> :where {field #{vals}}}}`
  (`waymark10/src/waymark10/derived.clj`), maintained by SQL in
  `server/maintainer.clj` on the row that DECLARES it. A `:related` edge is
  `{:kind K :on [[ours op theirs] …]}` with `op` in `#{:< :<= := :>= :>}`
  (`checks_assembly.clj`, `join-ops`).
- Everything else is a `defguardfn` that declares `:reads [:kind]` and
  calls `(:read ctx)` / `(:find ctx)` (vocabulary §6). The pure render
  probe and the check tier carry no hooks, so such a guard ends in an
  optimistic `(t/allow)`. The fingerprint hashes its printed form; the
  decision record stores it as `"opaque": true` (`server/decision.clj`).
- Separation of duties exists, as two code factories in `guards.clj`:
  `unless` / `four-eyes` (the newest actor of a transition, through the
  `:actor-of` hook wired in `server/invoke.clj`) and `not-the-field` /
  `is-the-field` (a principal written in the row). Their checks carry a
  hand-built canonical form, so they fingerprint stably, but they are still
  code to the decision record and the audit.

## 1 · The decision in one paragraph

Add **four expression ops** — `follow`, `exists`/`none` (one form, two
polarities), `count` over a kind, and `did` — plus two nullary readers,
`(me)` and `(self)`. Each names its target statically, so the guard's
`:reads` is **derived from the tree**, exactly as `(input …)` already
becomes `:judges` and `(now)` becomes `:reads [:now]`. Each evaluates
through the hooks the ctx already carries (`:read`, `:find`, `:actor-of`)
and is **total**: a missing hook makes the op *unknown*, never an
exception, and an unknown verdict is never a refusal. Stored aggregates are
not extended: they answer about stored rows, and these laws must judge a
write that has not happened yet.

---

## 2 · One-hop reads — `follow`

```clojure
(follow (input :meal_id) :state)          ; the meal the input names
(follow (data :customer_id) :status)      ; the customer this row names
(follow (data :plan_id) :start_date)
```

**Shape.** `(follow <ref> <field>)`. `<ref>` must be literally
`(data :f)` or `(input :f)` where `:f` is a `:waymark/ref` (or
`[:maybe :waymark/ref]`) entry carrying `:kind`. `<field>` is a data field
of the target kind, or `:state`, or `:id`. One hop only: `<ref>` may not be
a `follow`. Two hops (day → plan → rotation) stay code; the build plan
revisits this only if the migration finds a third case.

**Normalization.** At the def site the kind is read off the schema and
written into the tree: `(follow (input :meal_id) :state)` normalizes to
`(follow :meal (input :meal_id) :state)`. Retargeting a ref therefore moves
the law's fingerprint, and the stored tree can be evaluated by a later
revision without the resident schema.

**Values.** `:state` answers the state's name as a string (`"on_list"`),
the same spelling an aggregate's `:where` uses. Data fields answer their
wire value, as `(data …)` does.

**Nil.** A nil ref, an id no row has, and a row the caller's grant cannot
see all answer `nil`. So `(= (follow … :state) "on_list")` is false: the
refusal reads *not an on-list meal*, the same sentence `meal-is-listed`
gives today for a missing meal. Existence alone is `(is-set (follow r :id))`.
An optional ref is guarded by the author: `(or (not (is-set (input :parent)))
…)`.

**`:reads`.** Derived: every `follow` adds its normalized kind. Writing
`:reads` by hand on a `:when` guard becomes an error once the derivation
lands (`[reads-handwritten]`, §6), as `:judges` is already never
hand-written for an expression guard.

**Evaluation.** Through `(:read ctx)`. Each distinct `(kind, id)` is read
once per verdict (a per-evaluation memo), so a law that follows the same
ref three times costs one read.

### Worked: the household meal list (`meal-is-listed`)

Today, `workqueue10/src/mealplan10/resources/plan_day.clj`:

```clojure
(defguardfn meal-is-listed
  {:judges [:meal_id] :reads [:meal]
   :explain "That meal is not on the family meal list yet. …"
   :remedies [{:door :meal/accept :id '(input :meal_id)}]}
  [_row inp ctx]
  (if-some [read (:read ctx)]
    (let [meal (read :meal (:meal_id inp))]
      (if (and meal (= :on_list (:state meal))) (t/allow)
          (t/deny {:errors {:meal_id ["not an on-list meal"]}})))
    (t/allow)))
```

Declarative:

```clojure
(defguard meal-is-listed
  (refuse "That meal is not on the family meal list yet. Accept a suggestion (or ask the AI for one) first.")
  '(= (follow (input :meal_id) :state) "on_list")
  {:remedies [{:door :meal/accept :id '(input :meal_id)}]
   :errors {:meal_id "not an on-list meal"}})
```

`:judges [:meal_id]` and `:reads [:meal]` are derived. The
`:errors` option is new: a field-error sentence attached to a declarative
refusal, so the form keeps the per-field error the code returns today.

---

## 3 · Existence and overlap — `exists`, `none`, `count`

### Decision

A **guard-time query in the expression language**, not a new derived
aggregate. Three reasons, each from the code:

1. An aggregate is a stored fact on the declaring row, recomputed after a
   commit (`maintainer.clj`). A booking guard judges the *proposed* start
   and end, which are input, before any row holds them.
2. An aggregate's `:where` is a constant set (`{:state #{"open"}}`); its
   only row-relative part is the edge's `:on`, which binds the declaring
   row's stored data. Overlap needs the input on one side.
3. A refusal must name the conflicting row. An aggregate stores a number.

An aggregate stays the right tool for a badge, a filter or a rollup
(`calendar-conflicts` in `mealplan10/resources/plan.clj`). A guard may
keep reading one through `(data :fact)`, as it does now.

### Shape

```clojure
(exists [b :booking {:crew_id  (= (input :crew_id))
                     :starts_at (< (input :ends_at))
                     :ends_at   (> (input :starts_at))
                     :state     (in "scheduled" "in_progress")}])
(none   [b :booking {…same…}])           ; ≡ (not (exists …))
(count  [b :booking {…}])                ; the number, for capacity
```

- The binder is `[name kind where]`. The binder name is erased to a de
  Bruijn index at normalization, as quantifier binders are today, so two
  laws differing only in it are one law.
- `where` maps a target field (or `:state`, `:id`) to `(op <expr>)`, with
  `op` in the edge's `join-ops` `#{< <= = >= >}`, plus `not=` and
  `(in v …)`. `<expr>` is any own-scope expression: `(input …)`,
  `(data …)`, `(now)`, `(self)`, arithmetic, or a `follow`. It may not
  read the binder: these are filters, not joins.
- **The row itself is excluded.** On an existing row, `:id (not= (self))`
  is implied, so moving a booking never collides with its old self. At a
  create there is no self and nothing to exclude.
- `count` over a kind shares the name of the existing collection `count`.
  The binder's second element (a kind keyword where a collection
  expression would stand) tells them apart, and normalization writes it as
  `(count-rows …)` so the two never share a fingerprint.
- Half-open intervals are the author's to spell (`<` and `>` above).
  The spec does not add an `overlaps` op: the two comparisons are the
  law, and a closed-interval house writes `<=`.

**Evaluation.** Through `(:find ctx)`, translated to the same condition
list `store/ids-matching` takes for a `:related` edge (`{:field :op :value
:cast}`), with the field's cast read from the target schema. `exists`
and `none` ask for one row; `count` asks for a SQL count. Nothing is
loaded that is not needed.

**Bound.** A `where` field must be declared `:filter` on the target kind
(an index exists, or will). `[exists-unfiltered]` is an error at the def
site, not a warning, because an unindexed guard query runs on every write.

**Evidence.** The first match is the evidence. When the guard refuses, the
engine binds the binder name in `:evidence` to the oldest match by
`created_at` (the id breaking a tie), the order `children-are-finished` in
`factory10/.../ticket.clj` already uses. The match is chosen among rows the
**caller's grant can see**, so a refusal never names a row its reader could
not open; when every match is hidden, the refusal stands and names none.
The sentence and the remedies read it:

```clojure
{:explain "{b.crew_name} is at {b.customer_name} on {b.starts_at}. Pick another crew or another time."
 :remedies [{:door :booking/reschedule :id (evidence :b)}]}
```

`{b.field}` reads a field of the evidence row, under the same visibility as
a row read (secret fields render empty, as in the decision record).
`(evidence :b)` in a remedy binding is its id.

**No visible evidence.** When every match is hidden, a `{b.field}` never
renders literally. The explain is split into sentences, and each sentence
that reads the unseen binder is replaced by the guard's `:hidden` sentence
for that binder, or dropped when the guard gives none. When no sentence
would remain, the engine's own stands: "What stops this is outside what
you can see." A number the refusal still owes its reader, such as how many
rows match, comes from `:vars`, which `defguard` evaluates as the law is:

```clojure
(defguard the-crew-is-finished
  (refuse "{count} of this crew's bookings are not finished. The oldest is \"{b.customer_name}\". Finish each one first.")
  '(none [b :booking {:crew (= (input :crew)) :state (in "scheduled")}])
  {:remedies [{:door :booking/finish :id '(evidence :b)}]
   :vars {:count '(count [o :booking {:crew (= (input :crew)) :state (in "scheduled")}])}
   :hidden {:b "They are outside what you can see."}})
```

With every match hidden this reads "2 of this crew's bookings are not
finished. They are outside what you can see. Finish each one first.";
without `:hidden`, the middle sentence drops.

### Worked: the landscaping booking

```clojure
[:customer_id {:kind :customer} :waymark/ref]
[:crew_id     {:kind :crew}     :waymark/ref]
[:starts_at :waymark/instant] [:ends_at :waymark/instant]

(defguard the-customer-is-active
  (refuse "{customer_id} is not an active customer; reactivate them first.")
  '(= (follow (input :customer_id) :status) "active")
  {:remedies [{:door :customer/reactivate :id '(input :customer_id)}]})

(defguard the-crew-is-free
  (refuse "That crew is already at {b.customer_name} from {b.starts_at} to {b.ends_at}.{free}")
  '(none [b :booking {:crew_id  (= (input :crew_id))
                      :starts_at (< (input :ends_at))
                      :ends_at   (> (input :starts_at))
                      :state     (in "scheduled" "in_progress")}])
  {:remedies [{:door :booking/reschedule :id '(evidence :b)}]
   :evidence {:free '(first [c :crew {:state (= "active")}]
                       (none [o :booking {:crew_id (= (get c :id))
                                          :starts_at (< (input :ends_at))
                                          :ends_at (> (input :starts_at))
                                          :state (in "scheduled" "in_progress")}]))}})
```

The refusal: *That crew is already at the Parks' yard from 8:00 to 12:00.
The Elm Street crew is free.*

`first` is the one correlated form, and it is **evidence-only**: it may
appear in an `:evidence` map, never in a verdict. It scans at most one page
(50 rows) of its kind, and runs only when the guard refuses — the posture
`:evidence {name (fn …)}` already has. Its inner `where` may read the outer
binder (`(get c :id)`); that is what makes it correlated, and why it is
kept out of the verdict.

---

## 4 · Separation of duties — `did` and `(me)`

### Decision

It reads the **transition log**, through the `:actor-of` hook that already
serves `four-eyes`, widened to answer every actor of a door rather than
the newest. The existing factories become sugar over the new ops.

```clojure
(did :prepare)                  ; the acting principal ever did :prepare on this row
(= (data :requested_by) (me))   ; the principal this row names is the actor
```

- `(did :door …)` takes one or more door names of this kind and is true
  when the principal acting now appears as the actor of any logged
  transition through one of them, on this row. A door this kind does not
  declare is an error at the def site (`[did-unknown-door]`).
- **Acting as.** A seat acting as a cast member (`as`) is logged as both
  (`acted by you, as them`). `did` matches either id: whoever prepared,
  directly or through a seat, does not sign off by either face.
- `(me)` is the acting principal's id. `not-the-field` becomes
  `(not= (data f) (me))` and `is-the-field` `(= (data f) (me))` — with an
  unset field refused, as `is-the-field` refuses today, by
  `(and (is-set (data f)) …)`.
- `:reads` derives `[:transitions :principal]` from `did`, `[:principal]`
  from `(me)`.
- **The newest actor.** `unless` asks for the newest actor of one door;
  `did` asks for any. For a door taken once per row these agree. For one
  taken many times (`revise`), *any* is the separation a sign-off means,
  so `four-eyes` changes meaning there. The migration step lists every
  carrier and its door's arity so the reviewer sees each.

**With `:role`.** A guard vector is a conjunction, so role and separation
compose by being listed together; no new combinator:

```clojure
:guards [(role :reviewer) the-preparer-does-not-sign]
```

The refusal names the guard that failed first, as today.

### Worked: the R&D study sign-off

```clojure
(defguard the-preparer-does-not-sign
  (refuse "You prepared this study, so someone else signs it off.")
  '(not (did :prepare :revise)))

(defguard the-engagement-is-open
  (refuse "The engagement {engagement_id} is closed; reopen it before signing.")
  '(= (follow (data :engagement_id) :state) "open"))
```

---

## 5 · Honest on the probe, in rehearsal, in the sweep, in scenarios

The rule: **a related-row op with no hook to answer it is *unknown*, and an
unknown verdict is neither allow nor deny.** `expr` grows one sentinel value;
every op propagates it (`(and false unknown)` is false, `(and true
unknown)` is unknown, `(or true unknown)` is true). A guard whose `:when`
resolves to unknown is reported as **pending**, exactly as a probe without
input is already pending (`:pending-input`).

| Where | Hooks | What the law answers |
|---|---|---|
| Render probe (envelope) | `:evidence-reads` lent by the router | Judged for real through the lent hooks. Where none are lent, unknown: the door is advertised with `pending: ["reads :meal"]`, never refused. This replaces the hand-written optimistic `(t/allow)` with a stated one. |
| `dry_run` | the real ctx, rolled back | Judged in full. A dry run's verdict is the write's verdict. |
| law-sweep (`server/law_sweep.clj`) | `render-hooks` plus `:actor-of` | Judged in full; the report header keeps its snapshot sentence (`docs/spec-law-sweep.md`, *Guards that read storage*). |
| law-scenarios, check tier (`scenario.clj`, `offline-ctx`) | **new:** served from the scenario | Judged, not deferred — see below. |
| law-scenarios, conformance tier | the HTTP door | As today. |

**The check tier gains reads.** Because the reads are declared data, the
check tier can answer them without storage. `offline-ctx` grows three
hooks served from the scenario itself:

- `:read` and `:find` over the scenario's `:given` rows (already a
  scenario key, staged today only at the conformance tier), filtered in
  memory with the same `where` semantics;
- `:actor-of` over a new `:history` key: `[{:action :prepare :as
  "principal-id"} …]`, the log the row is to have.

A scenario whose guards read only through these ops therefore stays
check-tier. A `defguardfn` that declares `:reads [:kind]` still defers, as
now, because the tier cannot know what its body asks. This is the concrete
payoff of migration: `mealplan10`'s list guards become check-tier
scenarios, proved at `make check-queue` with no database.

---

## 6 · Fingerprint and audit

**Fingerprint.** Every new form is a node of the canonical tree and is
hashed by `fingerprint-of` with the rest of the `:when`, after
normalization: the target kind written into `follow`, binder names erased,
`count` over a kind written as `count-rows`, `where` maps sorted by key.
Two consequences:

- The decision record stops recording these guards as `opaque`: their
  `:vars` and evidence are law the record can name.
- `four-eyes`, `unless`, `not-the-field` and `is-the-field` move their
  fingerprint once, from a hand-built `fn` form to a tree. That is a law
  revision on every kind carrying them (`approval_request` in
  `server/grants.clj`, the definitions kind in `server/definitions.clj`,
  and the rest the migration step lists). It lands alone, so the
  revision's diff is that and nothing else.

**Audit findings** (the declaration battery in `checks.clj`, run by
`make check-queue`):

| Finding | Level | Says |
|---|---|---|
| `[follow-not-a-ref]` | error | `follow` over a field with no `:kind`. |
| `[follow-unknown-field]` | error | The field is not on the target kind. |
| `[exists-unfiltered]` | error | A `where` field the target kind does not declare `:filter`. |
| `[did-unknown-door]` | error | `did` names a door this kind does not declare. |
| `[reads-handwritten]` | error | A `:when` guard also writes `:reads`; it is derived. |
| `[ref-unjudged]` | warning | A door's input takes a `:kind` ref, and no guard on that door reads that kind and the field carries no `:pick`. The door accepts any id of that kind. |
| `[remedy-on-evidence-kind]` | error | A remedy bound to `(evidence :b)` names a door not on `b`'s kind. |
| `[convertible-guard]` | info | A `defguardfn` whose `:reads` names one kind and whose `:judges` is one ref field of that kind: a `follow` candidate. Counted, not enforced, as scenario coverage is. Not raised when a declarative guard on the same door already follows that field. |
| `[stays-code]` | info / error | A `defguardfn` that would be `[convertible-guard]` and declares `:stays-code "<why>"` is listed with that sentence instead (the reparent guards: they walk the ticket tree to any depth). A `:stays-code` that is not one sentence is an error. |

`[ref-unjudged]` is the one the ticket asks for by example. It is a
warning because a ref that may name any row is sometimes the law (a note
that cites anything).

The seat **cracks** audit (`server/routes/seats.clj`, `cracks-for`) gains
one input: a remedy bound to evidence now names a door on another kind,
so *a remedy no seat holds* is judged against that kind's seats.

---

## 7 · Migration

Read in this branch; each is a `defguardfn` today.

### Become declarative

| Guard | Path | Form |
|---|---|---|
| `meal-is-listed` | `workqueue10/src/mealplan10/resources/plan_day.clj` | `follow` (§2) |
| `side-is-listed` | same | `follow` over `:side_id` |
| `plan-editable` | same | `(or (= (follow (data :plan_id) :state) "draft") (= … "active"))` |
| `plan-is-planned` | `workqueue10/src/mealplan10/resources/grocery_list.clj` | `follow` over `:plan_id`, state in planned/active |
| `on-an-open-days-plan` | `workqueue10/src/dayplan10/resources/block.clj` | Two guards: the plan exists, the plan is not closed. Its one `{why}` sentence becomes two sentences. |
| `on-a-planned-block` | `workqueue10/src/dayplan10/resources/decision.clj` | Same split: exists, `:state` is planned. |
| `the-parent-is-open-at-birth` | `factory10/src/factory10/resources/ticket.clj` | `(or (not (is-set (input :parent))) (and (is-set (follow (input :parent) :id)) (not= (follow (input :parent) :state) "done") …))`, one `not=` per ended state. (`in` is `where` grammar only; an expression `in` is not added.) |
| `children-are-finished` | same | `(none [c :ticket {:parent (= (self)) :state (in …unfinished)}])`, evidence `c`. Its `{count}` becomes `(count-rows …)` in `:vars`. |
| `its-change-is-under-review` | same | `(exists [c :change {:born_from (= …) :state (= "submitted")}])`. Its render posture is *deny* without a hook; under §5 it becomes *pending*, which the envelope must still not advertise as open. The step keeps its deny by marking the guard `:pending :deny`. |
| `the-repository-has-a-policy` | `factory10/src/factory10/resources/change.clj` | `exists` over `:repo_policy` by repository, if `bench/policy-of` is a plain filter; the step reads it first. |
| `four-eyes`, `unless`, `not-the-field`, `is-the-field` | `waymark10/src/waymark10/guards.clj` | The factories emit trees (§4, §6). |

### Partial

| Guard | Path | Why |
|---|---|---|
| `date-in-plan-range` | `mealplan10/resources/plan_day.clj` | `follow` covers the reads, but the law needs `(days (* 7 weeks))`; it converts only if `days` takes an expression. |
| `no-overlap-in-plan` | `workqueue10/src/dayplan10/resources/span.clj` | The overlap is `none`. The carve-out for `:within` `swap`/`split` is a fact about the call, not the world; the guard converts when expression guards can read `:within`, which this spec does not add. |

### Stay code

| Guard | Path | Why |
|---|---|---|
| `theme-in-rotation` | `mealplan10/resources/plan_day.clj` | Two hops (day → plan → rotation). |
| `meal-fits-day`, `side-fits-day` | same | Acceptance sets (`defacceptsfn`) whose set is a filtered list; an `:accepts` spelling is a separate concept. |
| `the-merge-order-makes-no-cycle`, `the-parent-is-not-below-it` | `factory10/.../ticket.clj` | Transitive walks. |
| `only-your-own-last-tap` | `waymark10/src/waymark10/guards.clj` | Reads the newest transition's time and from-state (`docs/spec-undo.md`); `did` answers *who*, not *when* or *from where*. |
| Every guard reading `:grant`, `:seat`, `:domain` or `:within` | `server/grants.clj`, `server/delegation.clj`, `server/domains.clj`, the factory walls | Engine machinery and call facts, not related rows. |

The build plan's survey step reads the remaining `:reads [:kind]` guards
(`hypothesis.clj`, `insight.clj`, `letters.clj`, `ingredient.clj`,
`dwelling.clj`, `decision.clj`'s `subject-resolves`) and sorts each into
these three tables before any of them is touched.

---

## 8 · Not in this spec

- The scene's second half — *the payment arrives and the job moves to paid
  by itself* — is a reaction, not a guard; it is a later concept of the
  epic.
- Reading `:within` from an expression.
- Two-hop `follow`, and `follow` over a vector of refs. `(every [r (data
  :blockers)] …)` over refs is the natural next step and waits for a
  second case.

---

## 9 · Build plan

Each step is one ticket and one pull request, in order. The first five
add language; nothing in the apps moves until step 6.

1. **Unknown in `expr`.** Add the unknown sentinel and its propagation
   through every existing op; a guard whose `:when` is unknown answers
   pending. Tests: the truth tables in §5, and that no existing law's
   verdict or fingerprint moves.
2. **`follow`.** The op, its normalization (kind written in), the derived
   `:reads`, the per-verdict read memo, `[follow-not-a-ref]`,
   `[follow-unknown-field]`, `[reads-handwritten]`, and the `:errors`
   option on a declarative guard. Test over a fixture pair of kinds.
3. **`exists` / `none` / `count-rows`.** The binder form, `where` to
   `ids-matching` conditions, self-exclusion, `[exists-unfiltered]`, and
   evidence: the oldest grant-visible match bound under the binder name,
   `{b.field}` in sentences, `(evidence :b)` in remedies,
   `[remedy-on-evidence-kind]`.
4. **`did` and `(me)`, `(self)`.** Widen `:actor-of` to answer every actor
   (both faces of an acted-as transition), `[did-unknown-door]`.
5. **Check-tier reads.** `offline-ctx` serves `:read`/`:find` from
   `:given` and `:actor-of` from a new `:history` scenario key; the tier
   rule stops deferring guards whose reads are all declarative.
6. **Household meals.** Migrate the four `mealplan10` guards (§7) and give
   each a check-tier scenario. This is the first app step and the proof of
   steps 2 and 5.
7. **Dayplan.** `on-an-open-days-plan`, `on-a-planned-block`.
8. **Factory.** `the-parent-is-open-at-birth`, `children-are-finished`,
   `its-change-is-under-review` (with `:pending :deny`),
   `the-repository-has-a-policy` if it reads as a filter.
9. **The separation factories.** `four-eyes`, `unless`, `not-the-field`,
   `is-the-field` emit trees; the PR lists every carrier, its door's
   arity, and the fingerprint each kind moves to.
10. **`[ref-unjudged]` and `[convertible-guard]`.** Last, so the warning
    lands on a tree that has already taken the conversions it would ask
    for.
11. **Survey.** Sort the remaining `:reads [:kind]` guards (§7) and file
    one ticket per conversion found.
12. **`first` in evidence** and the landscaping example as a fixture kind
    pair (`crew`, `booking`), with the refusal sentence of the scene as
    its acceptance test.
