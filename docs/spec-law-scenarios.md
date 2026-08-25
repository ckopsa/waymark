# Spec — law scenarios: the policy proves itself

**Thesis.** The conformance suite proves the *machinery* — that an advertised
action is an enforced action, that a refusal is shaped like a refusal. Nothing
proves the *policy*. "A kid asking to open the garage after 21:00 without a
parent is refused, and the refusal names the approval path" is a sentence the
household can check and the engine cannot. Make it a declaration: a **scenario**
is data beside the resource — rows in named states, a principal, an attempted
action, an expected verdict — and the framework judges it. The authoring order
flips: write the scenario, then the guard.

## Epistemic status

Not an invention, and barely a mechanism. Guards are maps and their law is a
form (`guards.clj`, `expr.clj`); `g/evaluate` is a pure function of
`(guard, row, input, ctx)` returning `[verdict denier]`; `render`'s probe and
`invoke`'s enforcement loop are two callers of it. A scenario is a third
caller, with the row written down instead of walked to.

What makes it worth a spec rather than an afternoon is that the third caller
wants to run **where the other two cannot**: at declaration time, with no
database, in the same breath as `make check-queue`. `check.clj` already holds
that posture ("nothing here touches storage" — `modules.clj`), and the
question this spec has to answer is exactly which scenarios may keep it.

The risk is a second test framework growing inside a framework whose whole
thesis is that the declaration is the only source. The discipline against it:
a scenario declares no fixtures, no setup code, no assertions — only a row, a
principal, an attempt, and a verdict. Anything a scenario cannot say, it does
not get to say; it becomes a `deftest` like anything else.

## What exists

- **`guards.clj:225` `evaluate`** — `[verdict denier]`, the denier being the leaf
  whose `:explain` renders the refusal. `render-reason` (`:268`) formats it from
  the deny `:vars` over a `:vars-fn` garnish; `becomes-available` (`:277`) is the
  structured hope. Every one of those is reachable without storage.
- **`guards.clj:49` `:reads`** — the declared external dependency set: `:now`,
  `:principal`, `:storage`, `:transitions`, `:services.features`, kind keywords.
  "Empty ⇒ the verdict is a pure fn of (row, input)". It is *declared*, and the
  ns docstring is emphatic that call shape "come[s] from `:reads`/`:judges`
  declarations, never from arity inspection". This spec's tiering rides that
  same declaration and sniffs nothing.
- **`render.clj:153` `probe-transition`** — the availability loop, verbatim what
  a scenario wants: guards in order, first non-warning deny wins, `:hide`
  becomes concealment rather than narration.
- **`check.clj:48` `report`** — enrollment warnings, `engine/full-registry`, then
  per-kind warnings and deviations, printed where the author looks, with an exit
  code. No engine, no store, no `start!`.
- **`test/suite.clj:337` `check!`** and **`test/packs.clj:494` `core`** — the
  conformance driver and core's pack. An obligation is
  `{:name … :needs #{…} :run (fn [ctx] → violation strings)}`; the ctx already
  carries `:row-in-state`, `:invoke`, `:get-env`, `:transitions`.
- **`declaration.clj:26` `top-level-keys`** — CLOSED on purpose: "A new
  declaration key that skips this list refuses every boot — loudly, at the def
  site, which is the point."
- **`declare.clj:367` `defguard`** — the sentence-first precedent: a guard is
  spelled `(defguard name (refuse "…") '(law))`, construction-validated at the
  def line. A scenario is spelled the same way, and for the same reason.
- **`fingerprint.clj:229` `fingerprint-of`** — a *whitelist* projection: it names
  the facets it captures and nothing else reaches a hash.

## The design

### One key, one macro

`:scenarios` joins `declaration.clj/top-level-keys` under a new comment group —
*proof* — as a vector of scenario maps. `defscenario` builds one, sentence-first:

```clojure
(defscenario only-the-recipient-opens
  "A letter addressed to someone else does not open for a curious sibling,
   and the refusal says which wall it hit."
  {:kind    :letter
   :attempt :open
   :row     {:state :sent :data {:owner "mom" :to "iris" :body "…"}}
   :as      {:id "otto" :type :person}
   :expect  {:refused :opener-is-recipient
             :because "Only the letter's recipient may open it."}})
```

The keys, closed the way `action-keys` and `link-keys` are closed:

| key | meaning |
|---|---|
| `:kind` `:attempt` | the kind and the action name (`:create` for the create door) |
| `:row` | `{:state … :data {…}}` — the row as a literal document |
| `:input` | the action's input body |
| `:as` | the principal: `{:id … :type … :roles #{…}}` |
| `:at` | `:now`, as an ISO instant; absent means the clock is not consulted |
| `:given` | rows that must genuinely exist — see the tiers |
| `:expect` | `{:allowed true}` or `{:refused <guard-name> :because "…" :remedies […]}` |

`:expect :refused` names the **guard**, not the sentence — that is the point.
A scenario that only pinned prose would fail on every polish and pass on a
guard swapped for a different guard with the same words. `:because` is checked
as a substring of the *rendered* reason (garnish and all) and is optional;
`:remedies` asserts the affordance tokens the refusal offers, which is how a
scenario says "and it names the way out".

A scenario whose `:row :state` is outside the action's `:from` set expects the
out-of-state refusal by the reserved name `:out-of-state` — the one verdict
that has no guard behind it (`render.clj:346`).

The sentence is required and non-blank, exactly as `:explain` is required of a
guard. It is not documentation: **it is the violation string.** A broken guard
fails with the household's own words, not with `expected :deny, got :allow`.

### Two tiers, chosen by declaration

A scenario runs in the **check tier** — in process, with no storage of any kind
— when both hold:

1. it declares no `:given` rows, and
2. every guard on the attempted action declares
   `:reads ⊆ #{:now :principal :services.features}`.

Otherwise it runs in the **conformance tier**, as a core obligation over the
application's real engine, through the HTTP door.

Both conditions are read off declarations, never off behaviour. `check` prints
the split, so an author knows which of their scenarios the no-database gate
actually covers:

```
  letter — 4 scenarios ✓  (1 deferred to the suite: reads :storage)
```

The check tier calls `g/evaluate` over `(:guards defn')` in order with
`{:mode :invoke :now … :principal … :rate nil}` — `:invoke` and not `:probe`
because a scenario supplies its input and should not get the probe's
pending-input short-circuit; `:rate nil` because an engine without a rate
coordinator is what `guards/rate-limit` already allows for. Nothing is
constructed that a running engine would not construct.

The conformance tier is one obligation, `:core/law-scenarios`, in
`packs/core`, `:needs` nothing beyond the kinds it names: it stages `:given`
through the plural create door, walks or POSTs the subject row, then invokes as
the declared principal and reads the envelope — so what a conformance-tier
scenario proves is the refusal *a client sees*, not the one a guard returned.

### Where it lands, and where it does not

- **Core, not a module.** A scenario judges core's law with core's evaluator.
  There is no fifth column on `modules.clj/inventory` and no new pack.
- **`waymark10.scenario`** is the new namespace: validation, tiering, and the
  check-tier judge. `check.clj` and `packs.clj` are both callers; neither owns
  the logic, which is the same shape `conformance.clj`-as-library already has.
- **Never fingerprinted.** `fingerprint-of` projects named facets, so
  `:scenarios` is excluded by construction rather than by an exclusion list.
  This is load-bearing and gets a pinned test in the invariance suite: adding,
  editing or deleting a scenario must leave every kind's fingerprint hash
  byte-identical. A test that minted a law revision would be a test that
  triggered a propose-mode hold, and the framework would be at war with itself.
- **`:reads` is asserted, not inferred.** A code guard that reaches past its
  declared `:reads` — an in-process atom, a clock read — will answer the check
  tier's question with the offline verdict. `letters-are-paced` already handles
  this correctly and by hand (`(nil? (:read ctx))` ⇒ allow: "the storage-free
  probe … never spends a slot"), which is the pattern, not the exception. A
  scenario that wants the live verdict declares `:given` and drops to the
  conformance tier.

## Recorded punts

- **`require-fact` over-declares.** `guards/require` declares `:reads [:storage]`
  but its check consults `(get-in row [:data fact])` — the row it was already
  handed. Under this spec's rule every `require-fact` scenario drops to the
  conformance tier, which is a real loss for mealplan10, whose law is mostly
  fact gates. Narrowing it (a `:reads [:facts]` verb; `:reads` is absent from
  `guard-fp`, so the change mints no revision) is the obvious follow-on and is
  deliberately **not** in this spec: it touches every declaration's read set
  and deserves its own review.
- **Cross-kind scenarios have one home.** A scenario lives on the kind whose
  action it attempts, even when its `:given` names three other kinds. There is
  no shared scenario file and there should not be; the alternative is a
  test-suite-shaped thing sitting outside every declaration.
- **Coverage is counted, never enforced.** `check` reports "9 refusing guards,
  4 named by a scenario". It does not warn on the other five. A usability
  warning here would fire on every action in the tree on the day it landed,
  and a warning nobody can clear is a warning nobody reads. The count is the
  pressure; `checks.clj` stays quiet.
- **`{:any [...]}` composites.** An OR advertises nothing (`guards.clj:107`),
  so `:expect :refused` under an `:any` can name the composite but not the arm
  — the same clause the [law sweep](spec-law-sweep.md) and the
  [refusal plan](spec-refusal-plan.md) already carry.
- **No scenario ever writes.** The conformance tier stages rows and invokes,
  which is a write; but a scenario has no `:then`, no follow-up attempt, no
  assertion about the row after. A scenario is a verdict, not a story. A story
  is a `deftest` — `family_week_test.clj` exists and is not being replaced.

## Effort

**Medium.** The check-tier judge is a loop over `g/evaluate` and is small; the
declaration key, its closure, and the macro are an afternoon. The honest work
is the conformance-tier obligation's staging — `:given` rows, a declared
principal that is not the system walker, and the envelope reading — plus the
three household scenarios that prove it and the invariance test that pins the
fingerprint.

## Built (2026-08-23, waymark-442.2)

It landed as specced, and the parts that moved were small enough to name.

`:scenarios` joined `declaration.clj/top-level-keys` under a new *proof*
comment group (and the shipped clj-kondo hook's copy of that set, which
`declaration-test` holds equal). `waymark10/scenario.clj` is the new namespace:
the closed key sets, the construction gate, the tier rule, the check-tier
judge, and the one narration both tiers share. `declare.clj/defscenario` is
sentence-first beside `defguard`, aliased in `dsl.clj`; `check.clj/report`
grew a per-kind scenarios section; `packs/core` grew one obligation,
`:core/law-scenarios`.

Eight household scenarios landed in workqueue10 — four on `letter` (the open
wall, its allowed twin, the discard wall, and the second knock on an opened
letter), two on `weather` (the first-person wall at the create door and the
report that passes), two on `self` (another agent's words, and your own) — plus
two on core's own `approval_request`, which is where the four-eyes rule finally
became a sentence the framework checks. Every one of them is check tier: `make
check-queue` judges all eight with no database, and prints the split.

### Readings and trades

- **The sentence leads, the diagnosis rides after.** A broken wall reads
  `only-the-recipient-opens [letter/open] A letter addressed to someone else
  does not open for a curious sibling, and the refusal names the wall it hit.
  — the law allowed it`. The clause is the only part the framework wrote.
- **A broken scenario exits 1; a usability warning still exits 0.** That is a
  new exit-code meaning for `waymark10.check` and it is the right one: a
  warning is an opinion about how a declaration reads, a scenario is a promise
  the household wrote down and the law stopped keeping. `make check-queue`
  goes red.
- **Structural faults read as themselves, not as the household's sentence.**
  An `:expect :refused` naming a guard that is not on the action, a `:row` in
  an undeclared state, an attempt the kind does not carry — each is an
  authoring fault and says so. The sentence is reserved for the law actually
  breaking. This is also what makes the guard-swap case land: swapping a guard
  for a different guard with the same words fails with *"`:expect :refused`
  names `:curator-only`, which is not a guard on `:close`"*.
- **`:reads` exists in exactly the shape the spec assumed**, so the tier rule
  needed no new metadata and sniffs nothing. `check.clj` prints the deferral
  reason in the author's terms — `stages 1 given row`, `reads :storage` — so
  nobody has to guess which half of the rule they tripped.
- **A missing `:at` under a clock-reading law is a refusal, not a default.**
  The spec's table says an absent `:at` means the clock is not consulted; a
  scenario whose action carries a `:reads [:now]` guard is told to name its
  moment rather than being handed `Instant/now` and a plausible-looking wrong
  answer.
- **The conformance tier stages as the walker and attempts as the declared
  principal.** Staging as the scenario's own principal would prove a different
  sentence — that this person may create the setup — and a scenario that wants
  to say that says it as its own attempt. `:given` rows and the subject row
  both go through the plural create door and then along `machine/path-to`; the
  verdict is read off the RFC 9457 document, so what a conformance-tier
  scenario proves is the refusal a *client* sees.
- **A hide-flagged guard cannot be named through the door, and the obligation
  says so** rather than reporting a mismatch: concealment answers 404 and
  never names itself. Such a scenario belongs to the check tier, where the
  denier is in hand.
- **The obligation reports `:covered`**, the folded-enums precedent, so an app
  can tell *no scenarios declared* from *no scenarios deferred*.
- **The invariance pin holds by construction and is tested anyway.**
  `fingerprint-of` is a whitelist, so `:scenarios` never reaches a hash;
  `law_scenarios_test` pins that adding, editing and deleting one leaves the
  hash byte-identical, and asserts the projection has no `"scenarios"` key. A
  test that minted a law revision would have triggered a propose-mode hold on
  every scenario edit.

### Punts kept, and two added

Every punt above survives contact: `require-fact` still over-declares
`:reads [:storage]` (so mealplan10's fact gates all defer, and narrowing it
stays its own review); a cross-kind scenario still lives on the kind whose
action it attempts; coverage is still counted and never enforced —
`check` prints *"5 refusing guards, 2 named by a scenario"* and `checks.clj`
stays quiet; `{:any […]}` composites can still be named only as the composite.
Two more were added by building it:

- **The check tier's world has no features enabled.** `:services.features` is
  in the offline set because the spec put it there, and the storage-free ctx
  serves it as empty — so a scenario over a `feature-flag` guard sees the flag
  *off*. Honest for a declaration-time world, and a scenario that wants the
  live answer declares `:given`. No workqueue10 scenario is affected today.
- **`waymark10.check` judges the application's own declarations only.** Core's
  enrolled kinds — `approval_request` among them — are assembled there but
  their scenarios are judged by the framework's own suite instead
  (`law_scenarios_test/core-kinds-keep-their-own-scenarios`, over
  `engine/full-registry`). Widening `check` to the full registry would make
  every app's gate report core's law, which is a decision about what `check`
  is *for* and deserves its own line rather than a side effect of this one.

  **DECIDED, and widened — waymark-442.8, closed by waymark-4yn.** The
  decision is *yes*: `check` judges an ENROLLED kind's scenarios too. What
  forced it was building a framework kind whose most load-bearing wall is an
  actor-type refusal (`feed_recipe`'s agent wall): the scenario proving it is
  storage-free by construction, and the one place it belonged — where the
  author looks — was the one place that would not read it. The gap was
  narrower and worse than it looked from here: `:core/law-scenarios`
  deliberately skips whatever the check tier can judge for free, so a
  CHECK-TIER scenario on an ENROLLED kind was judged by neither side.
  `approval_request`'s four-eyes pair had been in that hole since the day it
  was written.

  What widened is only the scenarios (and the row that prints them); the
  `kinds` tally still counts the application's own declarations, so *"32
  kinds"* still means what it always did. `make check-queue` went from 16 to
  21 scenarios judged with no new law: three of `feed_recipe`'s and two of
  `approval_request`'s. The framework suite's own
  `core-kinds-keep-their-own-scenarios` stays — it asks the same question of
  a different caller — and the burndown the widening exposed (most enrolled
  kinds have written no wall down at all) is `waymark-a2b`.
