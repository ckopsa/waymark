# Spec — the pantry: the list compiles itself, the pantry decays itself

**Thesis.** The grocery list is compiled by hand (the AI walks the plan and
types `add_item`), and the system has no idea what the family already owns —
so ~20% of every fortnight's list is salt, spices, oils, and vinegars that get
struck off in the same conversation every time. Both problems have the same
root: the derivation chain `plan → plan_day → meal_line → grocery item`
already exists as promoted rows, and nobody walks it. This spec makes the walk
an action, and makes "do we have this?" a stored fact with a clock — the
`price_is_stale` pattern, pointed at the pantry.

## Epistemic status

Design agreed 2026-07-28. Nothing here invents machinery: the compile is a
handler-code walk over ctx `:find` (the `absorb-duplicate` shape), the stocked
fact is a derived clock law with `:flips-at` (the `price_is_stale` shape), and
the purchase stamp is a `:touches`-advertised cascade (the absorb shape
again). The only new *concept* is provenance on list items.

## What exists

- `mealplan10/src/mealplan10/resources/grocery_list.clj` — AI-compiled list;
  `add_item` free-form, `ingredient_id` optional, items are embedded parts
  keyed by `:name`. `complete` gates on all-checked and has no handler.
- `.../ingredient.clj` — "the canonical pantry concept": the *catalog* half
  (aliases, category, unit, preferred_stores). No stock story.
- `.../meal_line.clj` — promoted recipe lines `(meal_id, ingredient_id,
  grams, est_cost_cents)` with the write-time pricing law.
- `.../plan.clj` / `plan_day.clj` — the week; each day's decision is the
  day-row's state, `planned` days carry `meal_id`.
- `.../product.clj` — `price_is_stale`: the derived clock fact with
  `:flips-at`, the pattern era 2 copies.
- `dev/leftover_probe.clj` — the authoring-probe specimen: a fridge leftover
  with `stored_on`/`eat_by` and a `past_eat_by` clock fact. Not registered;
  it is the promoted spelling of the side-thread if that ever becomes rows.

## The design

### Era 1 — the list compiles itself

`grocery_list` gains **`compile_from_plan`** (`:draft → :draft`):

- Handler, ctx `:find`: the plan's `plan_day` rows in `planned` → their
  `meal_id`s → `meal_line` rows in `on_recipe` → group by `ingredient_id`.
  Per group: `quantity` = summed grams (`"600 g"`), `category` from the
  ingredient, `meals` = contributing meal names, `est_cost_cents` = the sum
  of the lines' write-time estimates — pricing is linear in grams, so the sum
  of line ests IS the total est; no second pricing pass.
- **Honestly non-idempotent** (`:idempotent false`): the outcome depends on
  the plan and the price world outside the row — the `reprice` story.
- The cross-kind walk is handler code, not law — join-and-group sits outside
  the expression grammar, the fn= boundary `price-line` and
  `absorb-duplicate` already record. A `:deviations` sentence.

Items gain **provenance**: optional `source` (`"plan"`; nil = manual).
Recompile replaces only its own `source: "plan"` rows; hand-added extras
survive. No shape bump — nil already reads as manual on existing rows.

**Ingredient policy, resolved:** compiled items carry `ingredient_id` by
construction (meal_lines have it). Manual `add_item` keeps it optional —
"birthday candles" stays legal, and pantry logic simply doesn't apply to
unref'd items. No `product` ref on items at all: store choice already lives
in the pricing law (`preferred_stores` → cheapest tracked product), and the
list shouldn't restate it.

Undecided and eating-out days contribute nothing; recompile after plan edits
is the workflow, and `plan-is-planned` already fences `finalize` from getting
ahead of the plan.

### Era 2 — the pantry as three fields and one law

All on `ingredient` — stock is the missing half of its story.

| field | writer | meaning |
|---|---|---|
| `shelf_life_days` | human (`update_details` grows it) | nil = **shelf-stable** — the absence of a clock, not a big number |
| `stocked_on` | `restock` | last date it was known on-hand |
| `out` | `mark_out` sets; `restock` clears | the human override; beats the clock both ways |

One derived clock fact, `:filter #{:eq}`, `:flips-at` = `stocked_on +
shelf_life_days` at start-of-day UTC:

```
stocked = (not out)
          AND is-set(stocked_on)
          AND (shelf-stable OR today < stocked_on + shelf_life_days)
```

Two regimes fall out:

- **Perishables** (`shelf_life_days` set): the clock flips them; the purchase
  stamp resets them. No human in the loop.
- **Staples** (nil): `stocked` is sticky — once true, true forever, because
  for salt the real signal isn't time, it's a human noticing the jar is
  empty. Marked stocked **once ever** (seeding), unmarked only by `mark_out`.

Two human verdicts, idempotent overwrites, **no `:undo` pair** (restock
stamps a date — they are not exact inverses):

- `restock` — `stocked_on = today, out = false`. Doubles as the seeding
  action: one pass over the ingredient list ("which of these do you just
  have?").
- `mark_out` — `out = true`. "We're out of cumin" / "the cream went off
  early."

**The purchase stamp:** `grocery_list` `complete` gains a handler invoking
`:ingredient restock` for every *checked* item carrying an `ingredient_id`,
advertised as `:touches [{:kind :ingredient :action :restock :may true}]` —
the absorb-cascade shape, one commit, one writer for `stocked_on`. The
all-checked gate already guarantees checked = bought.

### Era 3 — compile consults the pantry (the noise fix)

`compile_from_plan` reads each grouped ingredient's `stocked`:

- **Not stocked** → on the list, as era 1.
- **Stocked** → not on the list, but not silent: the handler writes the
  list's new **`assumed_on_hand`** field (a vector of `{name, meals}`) —
  "assumed on hand: salt, cumin, olive oil…". One glance verifies twenty
  items; if the assumption is wrong, `mark_out` + recompile, or just
  `add_item`. The list stays honest about what the meals need without
  re-litigating the pantry every fortnight.

This is where the 20% disappears.

### Era 4 — the anchor/flex solver (two-week stretches)

- `ingredient` gains `opened_shelf_life_days` (nil = opening changes
  nothing).
- A two-week plan gets **one list per trip**: `grocery_list` gains an
  optional `covers_from`/`covers_until` window; the plan's rollups already
  sum across its lists unchanged. A list with no window covers the whole
  plan (era-1 behavior, and every existing row).
- The solver is a coverage law inside compile: a purchase at trip *t* covers
  a use at date *d* iff `d − trip_date ≤ shelf_life_days` AND `d −
  first_use_date ≤ opened_shelf_life_days`. Walk each ingredient's use dates
  (the plan_day dates of its meals); an item lands on the latest trip that
  still covers its uses — anchors (flour) ride trip 1, flex (cilantro,
  cream that won't survive opened to day 12) ride the later trip, and an
  ingredient used day 2 AND day 12 appears on both trips exactly when the
  opened clock says one purchase can't span them. Shelf-stable = covered by
  any prior trip, always.

### Side-thread — the cooked-leftover clock

`meal` gains optional `leftover_days` (beside `prep_minutes`/`thaw_hours`,
written through `update_recipe`). Its consumer is leftover-*night* planning
on `plan_day` ("chili from Monday is still good Thursday") — a later
feature; the field is declared now so recipes can start carrying it. The
promoted spelling of fridge-reality is `dev/leftover_probe.clj`, if it ever
becomes rows.

## The three clocks, kept apart

1. **Unopened raw** — `purchase → shelf_life_days`. Era 2's stocked fact;
   kills the staple noise.
2. **Opened residual** — `first use → opened_shelf_life_days`. Era 4's
   solver input only; before the solver it is precision the "roughly" model
   doesn't need.
3. **Cooked leftover** — `cook date → leftover_days`. A property of the
   *meal*, not the ingredient; feeds leftover-night planning, not the list.

## Recorded punts

- No quantity/depletion tracking — rough is the model: the clock and the
  human verdict, never "300 g remain".
- No servings scaling at compile; eating-out and leftover nights don't yet
  reduce quantities.
- ~~Seeding is per-row `restock` (idempotent; the AI can loop) — no bulk
  action until it hurts.~~ Landed 2026-07-29: it hurt (716 ingredients, one
  call each) — `restock_many` is the bulk door, accept_many's shape with
  restock's honest non-idempotence (the whole call demands one
  Idempotency-Key; over 50 ids defers to a job).
- `leftover_days` is declared but unconsumed until leftover-night planning
  exists.

## Implementation record (2026-07-28, all four eras + side-thread landed)

Refinements the build discovered, each deliberate:

- **`restock` is honestly non-idempotent**, not the overwrite this spec first
  said: it has no input and reads the clock, so natural replay (same action,
  same digest, same state) would swallow a fortnight-later purchase stamp
  forever and the perishable clock could never rewind — the same reasoning
  `compile_from_plan` and `reprice` already record. `mark_out` keeps the
  overwrite safety (same truth twice, no clock).
- **The era-4 window is a create-time law, not a handler check**: a
  `window_paired` derived fact + `require-fact` at `:create-guards` (the
  substitution `:distinct` pattern) refuses a half-set or inverted
  `covers_from`/`covers_until` at birth.
- **The solver's shape**: uses walk in date order under one reduce — a use
  *rides* the newest purchase while both clocks hold, else a new purchase
  *opens* at the latest trip the raw clock allows (the opened clock starts at
  the use itself, so it never bars an open; greedy is safe because the newest
  purchase carries both the latest trip and the latest first-use). An
  uncoverable use lands best-effort on the latest trip ≤ it — narrated, never
  dropped. The trip schedule is the plan's other lists' `covers_from` dates,
  all states (draft/ready/done are all real trips).
- **Two framework fixes fell out** (waymark10/src/waymark10/expr.clj):
  `whole-days` accepted only `Long`, so `(days (var :shelf_life_days))` was
  silently nil on jsonista's `Integer`s — it now takes the whole long family
  (`int?`), plus integral `BigDecimal`s whose stripped scale is ≤ 0. Both
  covered in the framework suite. Parity over punts: the stocked law is law,
  not a handler deviation.
- **Item names are the part key**: a manual item whose name a compile claims
  is replaced by the compiled row (recorded in the handler); a manual item for
  an *assumed-on-hand* ingredient survives beside the assumption.
- The altitude checker false-positived the item actions against
  `assumed_on_hand` (same `:name` spelling); waived with `:waives
  #{:altitude}`, outside the fingerprint.

## Test surface

Compile, the stamp cascade, and the stocked fact each get conformance
coverage; `grocery_list`, `ingredient`, and `meal` fingerprints change
legitimately (real law changes), so `style-invariance-test` pins move with
them. `make check10` stays 0/0; `make test-mealplan10` is the gate.
