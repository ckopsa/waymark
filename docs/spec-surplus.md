# Spec — surplus: the package outlives the recipe

**Thesis.** The cheapest tracked cut of chicken thighs is a 2.72 kg Costco
pack. The week's recipes ask for 900 g. The shop buys the pack anyway — and
1.8 kg of surplus lands in the fridge with a clock on it
(`opened_shelf_life_days` and `shelf_life_days` are declared facts since the
pantry eras) and **nothing plans for it**: no record says the surplus exists,
no day offers to eat it, and the window closes in silence. Yet the arithmetic
is already lying on the table — the compile groups the plan's needs per
ingredient (`Σ meal_line.grams`), the pricing law already chooses the product
(`preferred_stores` order, then `cents_per_100g`), and that product carries
`package_grams`. **Surplus = packages × package_grams − Σ needs** is
computable from promoted rows the moment the list compiles. This spec makes
the surplus a record, makes "which days may bend around it" a mark, and
finally gives `leftover_days` — declared and unconsumed since spec-pantry —
its consumer.

## Epistemic status

Design agreed 2026-07-28; this round is design-only, nothing here is built.
Nothing invents machinery: the surplus record is `assumed_on_hand`'s shape (a
compiler-owned field, replaced wholesale each compile), the anchor mark is a
field plus a verdict pair (`mark_out`'s shape, with the honest `:undo`
check/uncheck already model), leftover night is a new state in an existing
machine plus an acceptance-set guard (`meal-fits-day`'s shape), and the
suggestion loop is existing doors aimed by two reads. The only new *concept*
is that a purchase's remainder is a planning input.

**A naming note, up front.** Spec-pantry's era 4 already spent "anchor/flex"
on *purchases*: anchors ride trip 1, flex rides the later trip. This spec
reuses the words for *days*: an anchor day must hold, a flex day may be
re-aimed. The reuse is deliberate — same instinct, what is fixed and what may
move — but the two live on different kinds and never meet in code.

## What exists

- **The pantry eras** (docs/spec-pantry.md, all landed): the `stocked` clock
  on `ingredient` (`stocked_on + shelf_life_days`, `:flips-at`, the `out`
  override); `opened_shelf_life_days` declared, consumed only by the era-4
  anchor/flex *solver* — the coverage law in `grocery_list`'s `trip-uses`,
  where a purchase covers a use iff both raw clocks hold; the purchase stamp
  (`complete` → `restock` per checked ref'd item).
- `mealplan10/src/mealplan10/resources/meal.clj` — `leftover_days`, written
  through `update_recipe` beside `prep_minutes`/`thaw_hours`, **declared but
  unconsumed** (the recorded punt: "waits for leftover-night planning").
- `.../product.clj` — `package_grams` (and `package_count` for counted
  goods); `cents_per_100g`; the pricing chooser's rule.
- `.../meal_line.clj` — `grams` per recipe line; `best-unit-price` picks the
  product by preferred-store order then unit price (it returns only the unit
  — the surplus consult needs the row, a sibling walk, recorded below).
- `.../plan_day.clj` — the day's machine IS the decision (`undecided →
  planned / eating_out`, `clear_day` walks back); dates, themes, the
  `meal-fits-day` acceptance set, each edge nulling what it leaves.
- `.../plan.clj` — coverage = zero `undecided` children; the week-board
  surface (anchor `:plan`, members calendar + days) already served at
  `/api/surfaces/week-board/{plan-id}`.
- `.../grocery_list.clj` — `compile_from_plan` (the era 1–4 walk, honestly
  non-idempotent), `assumed_on_hand` (the compiler-owned record shape),
  items carrying `ingredient_id` and the grams sum.
- `dev/leftover_probe.clj` — the unregistered specimen: a fridge `:leftover`
  with `stored_on`/`eat_by` and the `past_eat_by` clock fact. The promoted
  spelling of fridge-reality, if that ever becomes rows.

## The design

### Era 1 — surplus as a compile-time record

`compile_from_plan` grows one consult: for each **unstocked** group (stocked
groups buy nothing, so they surplus nothing), read the ingredient's tracked
products and choose by the pricing law's own rule — preferred-store order,
then `cents_per_100g` — the same rule `best-unit-price` applies, restated
because that fn returns only the unit; the fn= boundary the compile already
records, one sentence wider. With the chosen product's `package_grams`:

```
need_grams    = the group's summed grams (the item's quantity, un-stringed)
packages      = ceil(need_grams / package_grams)
surplus_grams = packages × package_grams − need_grams
window_ends   = min over the set clocks of
                  trip_date  + shelf_life_days          (raw)
                  first_use  + opened_shelf_life_days   (opened)
```

`trip_date` is the list's `covers_from`, else the plan's `start_date` — the
compile's honest approximation of the purchase date; `first_use` is the
earliest use date in this list's slice (era 4 already keeps use dates).

The result lands in a new compiler-owned field on the list, **`surplus`** —
`assumed_on_hand`'s lifecycle exactly: written only by `compile_from_plan`
(no create-schema entry, no action input admits it), replaced wholesale each
compile, and as non-idempotent as the compile that writes it. Per entry:

```json
{"name": "chicken thighs", "ingredient_id": "…",
 "need_grams": 900, "package_grams": 2720, "packages": 1,
 "surplus_grams": 1820, "window_ends": "2026-08-06",
 "meals": ["Traeger chicken thighs"]}
```

**Why a record and not a law.** The join (group → chosen product →
`package_grams`, ceil, min-over-clocks) crosses kinds and argmaxes — twice
outside the expression grammar, the boundary `price-line`, `trip-uses`, and
the compile itself already record. A "derived fact" here would be a
handler-written field wearing a law's costume; `assumed_on_hand` already
chose honesty over costume, and surplus follows it.

**Honest silences, recorded:** an entry appears only when `surplus_grams >
0` **and at least one clock is set**. Shelf-stable surplus (rice by the
kilo) is not a planning problem — it is *the pantry*, and the purchase stamp
already marks it `stocked` at the shop. Counted goods (`package_count`, no
`package_grams`) and ingredients with no unit-priceable product contribute
no entry — the compile cannot know their remainder and does not pretend to.

**The loop converges at plan time.** Plan a second thigh meal this week and
recompile: `need_grams` rises, `packages` holds, `surplus_grams` shrinks —
possibly to zero, and the entry vanishes. The best moment to consume surplus
is *before* the shop, and the record serves that moment as well as the
after-shop one.

### Era 2 — anchor and flex days

The planner (era 4's AI loop) needs to know which days it may re-aim.
Definition first: **flex = `undecided` ∪ (`planned` ∧ ¬anchored)**.
`eating_out` (and era 3's `leftover_night`) are never flex — they are human
decisions the compile already ignores.

**Field, not state — argued.** `plan_day`'s machine has one law, stated in
its own docstring: *state is the decision* — what covers the day. Anchored
does not change what covers the day; it constrains future *re*-decisions. A
state spelling would double the planned arm (`planned` ×
`planned_anchored`), double every transition into and out of it, and run
straight against the precedent that shaped this machine: the `eating_out`
bool died *because* it duplicated the state. Here the bool lives because it
is not the state's fact. (Supporting, not leading: a mark meaningful across
states would need multi-state self-loops, which the action grammar's single
`:to` cannot spell anyway.)

So: `plan_day` gains **`anchored`** (`[:maybe :boolean]`, `:filter #{:eq}`,
nil = flex — existing rows read as flex, no shape bump, the `source: "plan"`
precedent) and a verdict pair on the `:planned` self-loop:

- `anchor` — "this meal must hold: guests, the theme night that IS the
  point." `anchored = true`.
- `unanchor` — `anchored = nil/false`.

Both idempotent overwrites, both guarded by `plan-editable`, and — unlike
`restock`/`mark_out` — **an honest `:undo` pair**: no clock, no date stamp,
exact inverses, the check/uncheck precedent. Anchored on `:planned` only:
an undecided day has nothing to hold.

**The mark is advice, not law.** No guard reads `anchored`: `clear_day` and
`assign_meal` stay open on an anchored day, because walling the AI would
wall the human with the same stone. The AI's contract is the filter
(`?state=planned&anchored=true` names what it must not touch); a warning
guard is a recorded punt if the contract is ever broken.

### Era 3 — leftover night is a decision

"Chili from Monday is still good Thursday" is a coverage decision, the same
rank as eating out — so it is a **state**, exactly where anchored was not:

```
undecided ──assign_meal──▶ planned
    │                         │
    ├──mark_eating_out──▶ eating_out
    │                         │
    └──mark_leftovers──▶ leftover_night     (clear_day walks all three back)
```

- `mark_leftovers` — `:from #{:undecided :planned}` → `:leftover_night`,
  input `leftover_of_day_id` (`:kind :plan_day`, its engine label riding the
  ref the way `meal_name` rides `meal_id`). Multi-origin, so no `:undo` —
  `clear_day` is the acknowledged way back (the machine's standing rule).
  The handler nulls the departing arm's facts; every other edge learns to
  null `leftover_of_day_id` on the way out — each edge nulls what it
  leaves, spelled once per edge, as ever.
- **The leftover-window acceptance set** (the `meal-fits-day` shape: one
  set drives the picker, availability, and enforcement): admitted source
  days = this plan's `planned` days strictly before this date whose meal
  carries `leftover_days` and `source.date + leftover_days ≥ this.date`.
  Explain: "Nothing cooked earlier this week is still good on {date}."
  Cross-kind and date arithmetic → a code guard with `:reads
  [:plan_day :meal]`, the recorded boundary.

**Everything else falls out with zero edits** — the strongest argument for
the state spelling: coverage is already "zero undecided children," so a
leftover night counts covered; the compile already walks `state :planned`
only, so a leftover night buys nothing; the day's summary already renders
`{date} · {state}`.

**Probe rows vs plan arithmetic — argued both ways.**

*For promoting `dev/leftover_probe.clj` to real rows:* fridge reality
includes leftovers no plan predicted (double batches, restaurant boxes);
`eat`/`toss` are real verdicts; the `past_eat_by` clock is already authored;
the specimen is the promoted spelling waiting.

*For plan arithmetic:* (a) nothing births rows — `plan_day` has no per-day
"cooked" transition, so a fridge kind needs a new writer and a human
stamping every supper, the exact bookkeeping this app exists to kill;
(b) rows invite "how much chili remains," the depletion tracking spec-pantry
explicitly refused — rough is the model; (c) the one consumer is one
question — "is Thursday a leftover night?" — answerable entirely from rows
that already exist, in a guard shape that already exists.

**Recommendation: plan arithmetic.** The probe stays in `dev/`, off the
classpath, as the promoted spelling *if* fridge reality ever earns a machine
of its own — and the promotion rule's test is unmet until something reads
the eat/toss verdicts, which nothing does.

### Era 4 — the suggestion loop, and the surface question

The AI reads two things — the surplus record ("1.8 kg chicken thighs,
window ends Thursday") and the flex set — and drives **doors that all
exist**: suggest a meal (`meal` `:suggested`, recipe attached) that consumes
the surplus ingredient, human accepts, `assign_meal` on the flex day
(`meal-fits-day` still holds the theme line; an off-theme surplus rescue
spends `assign_off_theme`'s confirm, honestly), recompile, watch the
surplus entry shrink. No new action anywhere.

**What serves the read — argued from what exists.** Three candidates:

1. *A derived fact on the list* — can't be one honestly: the join is the
   fn= boundary again; it would be era 1's record wearing the costume.
   Era 1 already is the answer here.
2. *A new surface* (`surplus-board`, week-board's spelling) — machinery
   exists (phase 9b serves surfaces), but it duplicates a composition the
   plan already anchors.
3. *No new surface*: two collection reads —
   `grocery_lists?plan_id=X` (the `surplus` vectors) and
   `plan_days?plan_id=X` filtered to the flex set — and the loop runs.

**Recommendation: (3) now; when the two-read composition hurts, grow
week-board a `:groceries` member** rather than mint a sibling surface — the
week's decision surface already exists, already carries the days, and
finalize already consults it. The restock_many precedent governs: promote
the composed door once it hurts, not before.

## The three clocks, kept apart — surplus adds no fourth

1. **Unopened raw** — `purchase → shelf_life_days`. The `stocked` fact; now
   also the surplus window's first clause, read from the trip date.
2. **Opened residual** — `first use → opened_shelf_life_days`. The era-4
   solver's input; now also the surplus window's second clause — its second
   consumer, same field, no new law on `ingredient`.
3. **Cooked leftover** — `cook date → leftover_days`. A property of the
   *meal*; era 3's acceptance set is its **first consumer** — the
   declared-but-unconsumed punt finally closes.

Surplus is not a fourth clock: `window_ends` is clocks 1 and 2 read from the
remainder's side (what's left, not what's needed), min'd honestly. The
standing law of this section, extended: surplus arithmetic never touches
clock 3 (cooked chili is not raw thighs), and leftover-night never reads
clocks 1–2 (the fridge container doesn't care what the package promised).

## Recorded punts

- **Still no depletion tracking**: `surplus_grams` is compile arithmetic,
  never a decremented balance — no "1.3 kg remain after Tuesday." Rough is
  the model, carried.
- **The record is a snapshot**: `complete` does not resweep `surplus`
  against `stocked_on` (the true purchase date) — a later refinement if
  windows prove wrong in practice.
- **No significance floor**: a 40 g cilantro surplus appears in the record;
  the AI client judges what's worth a meal. No knob until it hurts.
- **Cost stays linear in grams**: the surplus record does not restate
  package-rounded spend; the pricing law is untouched.
- **Counted goods and unpriceable ingredients** surplus nothing — honest
  silence, not a TODO.
- **Cross-plan leftovers**: the era-3 acceptance set reads this plan's days
  only; last week's chili via `previous_plan` chaining is a named punt.
- **No anchor guard**: the mark is planning advice the AI respects through
  the filter, not law against the human — a warning guard only if the
  contract is ever broken.
- **Servings scaling** (eating-out and leftover nights reducing quantities)
  stays spec-pantry's punt, unchanged — though era 3 narrows it: leftover
  nights already buy nothing.

## Suggested era/bead breakdown

- **Era 1 — the surplus record** (one bead): `compile_from_plan` reads each
  unstocked group's chosen product's `package_grams`, computes
  packages/surplus/window, and writes the list's compiler-owned `surplus`
  vector — `assumed_on_hand`'s shape and lifecycle, replaced wholesale each
  compile.
- **Era 2 — anchor and flex days** (one bead): `plan_day` gains `anchored`
  (`:filter #{:eq}`, nil = flex, no shape bump) and the `anchor`/`unanchor`
  verdict pair — `:planned` self-loops, an honest `:undo` pair, no guard
  reads the mark.
- **Era 3 — leftover night** (one bead, the largest): the `:leftover_night`
  state, `mark_leftovers` with the leftover-window acceptance set
  (`meal-fits-day`'s shape over this plan's earlier planned days),
  `clear_day` widened, every edge nulling `leftover_of_day_id` on the way
  out — `leftover_days` consumed at last.
- **Era 4 — the suggestion loop** (one bead, mostly client + prose): the AI
  workflow documented against the two reads (surplus vectors + the flex
  filter) driving existing doors; no server change unless the composition
  hurts — then week-board grows a `:groceries` member (never a sibling
  surface).
