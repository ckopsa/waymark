# Waymark 10.0 — the language migration

A rewrite of waymark9 in Clojure, as a clean break (wire format
`"10"`), at `waymark10/` + `mealplan10/`. Companion documents:
`waymark8-design.md` (the law becomes data), `waymark9-design.md`
(the law binds the row's judgment), and the approved plan
(full-parity endpoint, phased; milestone 1 = phases 0–3).

**Epistemic status.** Written to a directive — "let's rewrite waymark9
in Clojure" — but the trigger is the lineage's own record. Every
version since 7.0 has been making the law representable, diffable,
storable, and evaluable per revision, and every step fought the host
language: the `E.*` builder facade exists because Python code has no
readable form; `to_wire`/`from_wire` exist because the builder's
objects aren't data; `callable_hash` hashes source *text* because
that is all `inspect` can see; the expr/fn declaration cliff exists
because Python has two ontologies — objects you can read and code you
cannot. In Clojure the law is a form: the tree the reviewer diffs,
the fingerprint stores, the wire carries, and the interpreter
evaluates are one value. 10.0 is not a feature version; it is the
medium catching up with the law.

What does **not** change: the totality boundary. Arbitrary Clojure is
refused exactly as arbitrary Python was — homoiconicity makes the
gate cheap (a set-membership walk), not open. The earned-vocabulary
rule loses its accidental enforcement (in Python, every node cost
builder boilerplate; here a node costs one line) and becomes review
law: **a node is added only when a ported declaration or a findings
doc demands it, and each addition is recorded here.**

## Why 10.0

Three facts, all recorded:

*The builder is the tax, not the law.* waymark8 §1's vocabulary was
right; its spelling (`E.f("start_date") + E.days(...)`, operator
overloads, `E.lit("0.02")` because floats can't be trusted) is
scaffolding around a language that wants to be written directly. In
10.0 the reconciliation rule is `(<= (abs (var :difference)) 0.02M)`
— and that s-expression *is* the storage form, the diff surface, and
the wire tree, with exact decimals as literals.

*The overlays are interpreters wearing mechanism's clothes.* waymark9
§1's judgment overlay (`judgment_served`, `judgment_laws`,
`guards_for`) is bookkeeping for one idea: evaluate revision N's
stored trees instead of the resident objects. When every guard and
derivation is a form and the interpreter is ~200 lines, "serve the
row's law" is a lookup plus `evaluate` — the mechanism shrinks toward
the principle.

*The fingerprint can finally read almost everything.* With resources
as maps and (phase 1) malli schemas as data, `callable_hash` shrinks
to one rule for the imperative residue: handlers and code guards
declared through `defhandler`/`defguard` hash by their **canonical
printed form** (comments and whitespace vanish in the reader), never
by file text. A bare fn with no form is a check error in strict mode:
identity must be stateable.

# 1. The form grammar (phase 0, landed)

A guard verdict, a derivation, or vars-garnish is an EDN form in a
closed vocabulary (`waymark10.expr/ops`):

| Category | Forms |
| --- | --- |
| References | `(data :f)` · `(input :f)` · `(now)` · `(var :f)` (derived scope) · `(get <e> :f)` → nil on missing |
| Literals | strings, booleans, nil, longs, exact decimals `0.02M`, `(date "2026-07-09")` |
| Comparison | `= not= < <= > >=` — binary; nil satisfies no ordering; long↔decimal promotes exactly |
| Boolean | `and or` (variadic) · `not` |
| Arithmetic | `+ - *` · `min max abs` — nil propagates; **no `/`, not earned** |
| Temporal | `(days n)` · date ± days · `(date-of ts)` (UTC) |
| Presence | `(is-set e)` |
| Quantifiers | `(every [d <coll>] <pred>)` · `(some [d <coll>] <pred>)` — named binders in authored form, de Bruijn `(it n)` in canonical form; empty/missing collections: `every` → true, `some` → false |

The scope split is waymark9's, verbatim: derived scope reads only
declared `:over` names via `(var …)` (a clock derivation declares its
clock input; `(now)` is refused); guard scope reads
`(data …)`/`(input …)`/`(now)` and refuses bare `(var …)`. Judges and
reads are derived from the tree (`expr/info`), never sniffed.

The boundary (`read-form`, `wire->form`) refuses: unknown operators,
tagged literals, floats (write `0.5M` — a float is a rounding, not a
law), unbound symbols, binder references outside their quantifiers,
oversized input. Total evaluation: no calls, no recursion, no reads;
calendar overflow and type mismatch flow as nil/false, never as a
throw (property-tested over generated forms × generated scopes).

# 2. Normalization — only meaning revises

`normalize` is idempotent and applied at declaration and before any
fingerprint/wire emission. Two spellings that mean the same thing are
the same tree; a reformat mints no revision.

1. Binder names erase to de Bruijn indices — alpha-equivalent
   quantifiers are structurally identical.
2. `>`/`>=` rewrite to `<`/`<=` with swapped operands.
3. `and`/`or` flatten nested same-op and drop duplicate operands
   (authored order otherwise preserved — conjunct order is the
   author's reading order, not meaning).
4. `not` canonicalizes: `(not (= …))` → `(not= …)` and back to `=`;
   double negation eliminates.
5. Commutative operands (`= not= min max + *`) sort by printed form.
6. Decimals strip trailing zeros; exactness is meaning, so `1.5M`
   stays decimal and never becomes a float.

**The value-domain scar** (found by the meaning-preservation
property, recorded here as 10.0's first deviation from the plan): the
plan said single-operand `and`/`or` "collapses to the operand" and
`(not (not x))` → `x`. Both are wrong when `x` is not
boolean-valued — `(and (data :a) (data :a))` evaluates to a boolean,
bare `(data :a)` to the raw field. Collapse therefore applies only
around boolean-valued operators (`= not= < <= > >= and or not is-set
every some`); a non-boolean operand keeps its coercing wrapper, and
the canonical grammar admits 1-ary `and`/`or` (e.g.
`(and (data :a))`) as the honest spelling of "coerce and insist".

A second recorded divergence: truthiness. Python's `bool()` made `0`
and `""` falsey inside `and`/`or`/quantifier predicates; Clojure
truthiness (only nil/false are falsey) is the 10.0 rule. Predicates
that mean emptiness should say it (`(= (get d :qty) 0)`), not lean on
a host language's coercion table.

# 3. The wire encoding

Forms cross and persist as a lossless JSON tree — never an opaque
string — so fingerprints diff path-by-path and every stored revision
reads back into an evaluable form:

```
(every [d (var :days)] (or (is-set (get d :meal_id))
                           (= (get d :eating_out) true)))
⇢
["every", ["var","days"],
  ["or", ["is-set", ["get",["it",0],"meal_id"]],
         ["=", ["get",["it",0],"eating_out"], true]]]
```

Decimals cross as `{"dec":"0.02"}`, date literals as
`{"date":"2026-07-09"}`. `wire->form ∘ form->wire = identity` on
canonical forms (property-tested). Canonical bytes (digests,
fingerprint hashes) admit only nil/boolean/string/long/keyword/
map/vector with sorted keys — floats and raw BigDecimals are refused,
which forces every decimal through the wire encoding and keeps hashes
byte-stable across processes.

# 4. The fingerprint reads forms

`fingerprint-of` projects the declaration map; expression laws are
stored as trees under `derived.<fact>.expr` and
`machine.actions.<name>.guards.<i>.expr`; only handlers and code
guards carry hashes. Ported one-for-one from waymark9
`core/fingerprint.py`, semantics intact:

- `classify-path` → `:shape`/`:judgment`/`:truth`/`:advertisement`,
  innermost owning surface wins, derived `explain`/`vars` are
  advertisement, unmatched defaults to `:truth`.
- `classify-diff` → `:data-law` iff every changed path is a
  derivation's tolerance/expr/edge-where or a recoverable leaf of a
  top-level expression guard; else `:code-or-shape`. A derived
  `explain`-only change stays `:code-or-shape` (render reads garnish
  from resident objects; a hold would serve new prose under an old
  law id) — ported, not revisited.
- `stale-facts` → facts whose semantic surface moved, garnish
  excluded; judgment diffs mark nothing stale.

One implementation scar worth recording: the first diff used a map as
a membership predicate, so paths whose leaf value was `nil` or
`false` (a guard's `check`, an off safety flag) read as added+removed
and poisoned the gate toward `code_or_shape`. Presence is
`contains?`, never truthiness — the regression test pins it.

# 5. The declaration layer (phase 1, landed)

A resource is one map; `defresource` normalizes it, runs the check
battery, and refuses at load what waymark9 refused at import. Guards
are maps evaluated by one function — the same `[verdict denier]`
resolution feeds the probe (render) and the invoke (enforcement),
which is the 2.0 unification carried into the new medium. Nothing is
sniffed: an `accepts` fn's call shape comes from `:reads`, the probe
short-circuit from `:needs-input`, and `:needs-input` defaults to
"grades input" — where grading is a `:check` **or a `:when` tree
whose leaves read `(input …)`** (the expression guard is code's
peer, so it inherits code's probe discipline).

Recorded adaptations from the Python original:

- **`bind_data` becomes data.** waymark9's `FactRequired.bind_data`
  mutated the guard at check time. Guards are immutable maps here, so
  `normalize-resource` *enriches* every require-leaf with its fact's
  derived spec (`:require/spec`) — the functional spelling of the
  same late binding.
- **Declaration order is not law.** Clojure map literals over eight
  entries lose authoring order, so action iteration is name-sorted
  everywhere (fingerprint, machine queries). Display order was always
  declared (`:display :order`); guard evaluation order lives in each
  action's guard vector, which survives.
- **Handler identity is a warning, then a gate.** A handler without
  `defhandler`'s form metadata gets a usability warning in phase 1;
  the strict mode that refuses it arrives with the registry, when
  grandfathered laws make unstateable identity an actual lie.
- **`async` evaporates.** Guard checks and accepts fns are plain
  functions; concurrency is the server's problem (threads), not the
  declaration's.

# 6. Phase-0 status and acceptance

Landed: `waymark10.expr` (vocabulary, validation, normalization,
total interpreter, scope validators, `read-form`), `waymark10.wire`
(form⇄JSON tree, canonical bytes, digests), `waymark10.fingerprint`
(projection, positional diff, classify, stale-facts). `make test10` /
`cd waymark10 && clojure -M:test` — 31 tests, 103 assertions, green;
properties: generated forms are well-formed; normalize is idempotent
and meaning-preserving; evaluation is total; wire round-trip is
identity; respelling a law is not a revision; a diff confined to a
`derived.*.expr` leaf classifies `data_law` with the fact stale.

## Explicit 10.0 punts (inherited and new)

- **`count`/`sum` quantifiers** — waymark9 has them; no ported
  declaration demands them yet. Added with the port that does.
- **Division** — never earned in any version.
- **String operations, `where=` unification** — inherited verbatim
  from 8.0's punt list.
- **Vocabulary growth without friction** — the standing risk of the
  medium; the rule above (recorded demand or no node) is the
  mitigation, enforced in review.

## Vocabulary additions log

| Node | Demanded by | Date |
| --- | --- | --- |
| (v1 set: see §1) | the waymark8 vocabulary, verbatim | 2026-07-09 |

# 7. Phase 8 — the mealplan10 dogfood, and the engine it forced

mealplan10/ is the full family meal planner (mealplan9's six kinds:
meal, rotation, plan, grocery_list, prep_task, event) ported onto the
Clojure engine at full fidelity — declaration-first, prose carried.
`make test-mealplan10` runs its conformance suite (all six kinds
enrolled in the waymark10.test library, over the ring handler) and the
Priya family-week story; `make dev10` serves it on :8010 against
mealplan10_dev (FakeEvents unless `MEALPLAN_GCAL_ICS_URL` is set).

Engine features the port forced into existence, each with its spelling
and its tests in waymark10's own suite (`waymark10.phase8-test`):

- **Cross-resource guard reads (C1).** `make-ctx` gains `:read`
  `(fn [kind id] → decoded row | nil)` and `:find` `(fn [kind where
  opts] → decoded rows)`, same transaction as the write. Guards keep
  declaring `:reads [:meal]`; the hook is what landed. Render's probe
  ctx stays storage-free (render is pure), so a reading acceptance
  set/check must decline (`nil`/allow) without the hook — the
  conformance probe (`factories/probe-ctx`) carries own-transaction
  hooks, so the walker still holds advertisement = enforcement.
- **Ref labels (C2).** A `:waymark/ref` entry declaring `{:kind :meal
  :label :meal_name}` gets its label engine-written at every write
  that changes the ref (data root and vector-of-map items; create
  treats every set ref as changed). The label is the target kind's
  `:label-template`, default `"{data.name}"` when the target declares
  `:name`. Recorded scope: a target rename does not fan back out
  (waymark9's maintainer did — named punt).
- **One-of clears (`waymark10.groups`).** `{:one-of {name {:in
  [:days] :arms {…} :clears true}}}` enforces post-handler: filling
  one arm clears the others (labels included — the label pass runs
  first); two newly-filled arms refuse as a definition bug. Recorded
  deviation: cleared fields become nil, not a model default (malli
  declares none).
- **The owns cascade (C3).** An owns edge's `:on {parent-action
  child-action}` fans out in `after-write!`, before the maintainer
  pass: each eligible child (selected by the child action's `:from`
  states, so redelivery is a natural no-op) moves through an ordinary
  `invoke!` — system actor `waymark-cascade`, the parent transition's
  correlation id. The rollup half is the phase-6 count fact: the
  `{:rollups …}` edge spelling is SUBSUMED by `:derived {:open_tasks
  {:count {:owns :prep_task :where …}}}` — one fact, one writer — and
  the gate is a plain expr guard over the stored count.
- **The Mirror (C4, `waymark10.server.mirror`).** `mirror/declaration`
  weaves the sync machine (fresh/stale/unreachable) and bookkeeping
  fields (external_id/external_etag/synced_at) into an app map, with
  `{:mirror {:adapter … :ttl-seconds … :discover-every …}}`. The
  adapter protocol is discover/pull/pull-many; sync transitions are
  system-actor-only and hidden. Seams: pull-through on GET (router;
  `:suppress-mirror-refresh` is the walker-scoped conformance escape,
  waymark9's `_suppress_mirror_refresh`), and discovery
  (mint-per-unknown-id + eager pull-many; a daemon on the engine
  runtime). Scoped to what the event kind needs — no push, no
  conflicted/reconcile, no per-field authority, no discovery cursor.
- **Shape upcasts (C5).** `inv/decode-row` (now the one load boundary
  for invoke, router, collections, and the maintainer) folds a stored
  row through `(:upcasts rdef)` when its shape lags, and the declared
  shape stamps at the next write. Upcasts must be idempotent: a
  maintenance write persists upcast data without the stamp.
- **`:waymark/instant`** joins the schema vocabulary (prep_task's
  `due_at` demanded a point the clock can compare): Instant in the
  law, RFC 3339 on the wire, timestamptz in the promoted column, flip
  candidates for the clock sweep.
- **Two wire-honesty amendments the conformance suite forced:**
  in-state concealment now precedes the fence, input validation, and
  natural replay (a hidden door must 404, never 422 or replay-200);
  and an available action whose REQUIRED field's folded acceptance
  set is empty narrates as unavailable ("No date currently qualifies
  for 'Add side dish'.") instead of advertising an empty enum —
  both are waymark9 render/external semantics, ported.
- **`factories/state-factory!`** — waymark9's `@state_factory`,
  ported: mealplan10 registers plan and grocery_list factories
  (finalize's require gate needs seven `mark_eating_out` self-loops a
  shortest-path walk cannot spell).

mealplan10's own recorded punts: previous_plan declared but
predecessor-unresolved (E7 has no v10 spelling); links declared and
assembly-checked but unrendered (envelope `:links` stays the phase-3
punt); part-scope "parts" envelope rendering unbuilt (the placed
actions carry `:place`, the key pre-binding waits for the parts
surface); WeekBoard/spans/profiles have no v10 spelling; the real
iCal adapter parses VEVENTs without RRULE expansion (a recurring
series contributes its DTSTART only — revisit when the family's
recurring events matter); v10 summary templates have no |join/|len
filters; no field defaults (rotation/plan defaults land in
:on-create).

The hand walk (`make dev10`, then): create + activate a rotation
(`POST /api/rotations {}`, `POST /api/rotations/{id}/-/activate`);
suggest and accept a meal (`POST /api/meals`, `…/-/accept`); create
the plan (`POST /api/plans {"start_date":"2026-07-14","weeks":1}` —
days pre-themed, Sunday from the rotation); assign and mark days
(`…/-/assign_meal`, `…/-/mark_eating_out`); finalize; `begin` answers
409 with `becomes_available.at` until Tuesday; `POST /api/prep_tasks`
then watch `data.open_tasks` gate `plan complete`; the grocery list
add/finalize/check_item/complete flow; `POST /api/events
{"external_id":"…"}` mints a mirror whose first GET pulls it through,
and `mark_stale` as a human answers 404 (concealed).

## Vocabulary additions log (phase 8)

| Node | Demanded by | Date |
| --- | --- | --- |
| `:waymark/instant` (schema type, not an expr node) | prep_task.due_at | 2026-07-10 |
