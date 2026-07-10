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
