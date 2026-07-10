# Waymark 8.0 — the law becomes data

A redesign of Waymark that starts from the boundary 7.0 drew on purpose:
declared *parameters* pilot per-row, declared *code* cannot. Companion
documents: `waymark7-design.md` (§4 draws the boundary and names the
pressure), `waymark7-notes.md` (deviation #6 records what the boundary
costs), and the dogfood findings series.

**Epistemic status.** Written to a directive, ahead of new dogfood
friction — but the trigger is not invented. 7.0 §4 closed with: *"the
honest long-term answer is making more of the law data (the
`within=Tolerance` precedent: every fn made declarative is a fn made
pilotable)"* — and its punt list made the pressure deliberate: refusing
to pilot code changes "rewards making law data." 8.0 is that reward
collected. The scope is disciplined by the same rule that produced
`Tolerance`: the expression vocabulary is earned from the fns and
checks the ported domains actually wrote, not from a language designer's
ambitions. When a shape the vocabulary cannot say arrives in a findings
doc, the vocabulary grows one node; it never grows speculatively.

## Why 8.0

Three facts, all recorded, all pointing at one seam:

*The pilot gate bites exactly where the law is code.* The cash-recon
tolerance change pilots per-fund because `Tolerance` is data. The same
change expressed as `fn=lambda d: abs(d) <= 0.02` is refused at the gate
— `code_or_shape` — and must promote totally. The difference between
the two deploys is not risk, blast radius, or reviewability; it is
*spelling*. A capability boundary that hinges on spelling is a
declaration cliff, and every prior version exists to delete one.

*Grandfathered laws are served approximately.* 7.0 deviation #6: the
overlay can recover a superseded revision's `Tolerance` and `where=`
from its stored fingerprint, but an `fn` body is stored only as a hash —
so a grandfathered row computes under *resident code with old
parameters*. For the dogfooded diffs that is exact; the moment a
grandfathered revision's fn differs, the row's law is being
approximated by a law that never governed it. One fact, one law, one
value — *almost*. 8.0 makes it exact for every declaration that takes
the offer.

*The fingerprint hashes text, not meaning.* The accepted liberty of §1:
callables hash by source text, so a whitespace edit, a renamed lambda
argument, or a `black` run revises the law and marks facts stale. The
reviewer diffing two revisions of an fn diffs *strings*. A derivation
whose definition is a tree diffs path-by-path — `derived.reconciled.
expr.cmp.right: 1e-5 → 0.02` — which is what "the diff is data on the
definition row" (7.0 §1) always wanted to say.

> **A pure function of declared inputs whose body the fingerprint
> cannot read is a declaration wearing code's clothes.**

The admission test for 8.0: take a semantic change that yesterday was
an fn edit — refused at the pilot gate, promoted totally, grandfathered
approximately — spell it as an expression, and the full 7.0 lifecycle
must open for it with **zero Python deployed**: registered as
`proposed` through the API or the boot, diffed path-by-path as data,
blast-radius measured, piloted for a declared population, grandfathered
*exactly* (the old revision's expression evaluates, not the new code
with old constants), and superseded when empty.

## What carries over unchanged

- The envelope, invocation, idempotency, drafts, jobs, events,
  populations, adoption, the definition machine — untouched. 8.0 adds
  no lifecycle state and no wire key to the row envelope.
- 7.0's row-law seam (`specs_for`, `law_revision`, `LawOverride`) —
  extended, not replaced: the overlay learns one more recoverable
  parameter, the expression tree.
- `fn=` / `check=` keep working forever. An expression is an *offer*
  with a capability attached, the `Tolerance` precedent generalized —
  never a migration deadline. The gate keeps refusing what is still
  code, and the refusal message now names the alternative.
- The materialization law, `defined_by`, conformance replay — untouched;
  an expr-declared fact materializes through the same maintainer paths.

Everything below is what changes.

---

# 1. The expression language

A derivation or a pure guard verdict becomes a value in a deliberately
small language — `waymark8/core/expr.py`:

```python
end_date = Derived(over=("start_date", "weeks"),
                   expr=E.f("start_date") + E.days(7 * E.f("weeks") - 1))

all_items_checked = Derived(over=("items",),
                            expr=E.all(E.f("items"), E.it.have))

overdue = Derived(over=("due_at", Clock), expr=E.f("now") > E.f("due_at"))

reconciled = Derived(over=("difference",),
                     expr=E.f("difference").abs() <= E.lit("0.02"))
```

- **The tree is the law.** Every node serializes to JSON
  (`to_wire`/`from_wire` round-trip exactly); the fingerprint stores the
  tree itself under `derived.<fact>.expr`, never a hash of it. Two
  spellings that build the same tree are the same law — reformatting
  stops minting revisions, and a real change diffs at the exact leaf
  that moved.
- **Total by construction.** Literals; input references by declared
  name (`E.f("weeks")`; `Clock` binds as `"now"`, an edge input as
  `"kind.field"`); attribute access on item objects; comparison,
  boolean composition, arithmetic; date offsets (`E.days(...)`);
  null/presence tests; and quantifiers over list inputs — `all`, `any`,
  `count`, `sum`, each over an item expression (`E.it`). No calls, no
  recursion, no names beyond the declared inputs, no reads, no string
  building. Evaluation cannot fail to terminate and cannot touch
  anything the declaration didn't name — the purity `DerivedSpec.apply`
  always promised, now checkable instead of trusted.
- **Checked at import.** Every input reference must name an `over=`
  entry (a derivation of an input it never declared is refused, same as
  ever); `expr=` / `fn=` / `within=` are exactly-one-of. `within=
  Tolerance(...)` stays — it is the one-node special case that seeded
  this design, and its spelling is already ubiquitous.

The vocabulary above covers, verbatim, every `fn=` in the two ported
domains except the garnish (`vars=` builds strings; strings are
advertisement, §5). That is not a coincidence; it is the sizing rule.

# 2. The fingerprint reads the tree

- `derived.<fact>.expr` carries the wire tree; `fn` is emitted only for
  fn-declared facts (the emission discipline: adding the mechanism
  re-hashes nothing that never touched it).
- `stale_facts` needs no change: an expr path under `derived.<fact>.` is
  semantic, so an expr edit marks the fact stale-by-definition exactly
  as an fn edit did.
- `classify_diff` admits `derived.<fact>.expr…` paths as **data_law** —
  the whole point. A pure expr diff can be held at `proposed` behind
  the overlay and piloted per-population. A diff that touches an fn
  hash, the machine, schemas, or guards classifies as before.
- The published data schema's `x-derived` (the §4-follow-up wire hint)
  gains the expression tree: the generic UI can finally show *what the
  derivation is*, not only what it is derived from. Stripped from the
  fingerprinted schema copies exactly as `x-derived` already is —
  `derived.<fact>.expr` stays the one canonical entry.

# 3. The overlay learns to read

`LawOverride` gains `expr`: the stored wire tree of the revision whose
law the row lives under, reconstructed by `from_wire` and evaluated in
place of the resident declaration — alongside `tolerance` and `where`,
which become the degenerate cases they always were.

- **The propose-hold is exact**: the current law's expr serves while
  the proposal's code is resident.
- **A pilot is exact**: fund-alpha's rows evaluate the piloted tree,
  fund-beta's the current one — one process, two laws, both read from
  declarations (7.0 §4's sentence, now with the load it was written
  for).
- **A grandfathered law is exact**: deviation #6 dies for expr-declared
  facts. The February workbook's `reconciled` evaluates February's
  expression on the day it closes, months after the code that shipped
  it was replaced. For fn-declared facts the deviation stands,
  recorded, as the standing pressure to convert.

# 4. Guard expressions

The same language serves a guard's verdict where the verdict is a pure
function of the row, the input, and the clock:

```python
plan_started = guard.expr(
    when=E.now().date() >= E.data("start_date"),
    explain="The plan starts {start}.",
    vars={"start": E.data("start_date")}, reads=("now",))

calendar_clear = guard.expr(
    when=~E.data("has_conflicts"), severity="warning",
    explain="{n} calendar conflict(s) overlap this week — …",
    vars={"n": E.data("calendar_conflicts")})
```

Scope roles instead of positional inputs: `E.data(...)`, `E.input(...)`,
`E.now()`. The fingerprint stores the tree under the guard's `check`
facet — a judgment change finally diffs semantically — and `vars` as
expression trees kill the `vars_fn` lambda for the enumerable cases.

**What 8.0 does not claim for them:** per-row pilotability. Judgment
diffs classify `code_or_shape` exactly as in 7.0, because guards are
evaluated from the resident machine's objects on the render and invoke
hot paths; a per-revision *guard* overlay is its own version-sized seam
(the conformance replay already walks stored machines per revision —
the data exists; the live paths don't read it). Named as the standing
punt. What guard expressions buy in 8.0 is the same thing `accepts=`
bought in 2.0: one declaration the fingerprint, the reviewer, and the
enforcement all read — plus a shrinking `check=` residue.

# 5. What stays code, and why that is the design

- **Handlers** — the imperative residue behind `Compound`. The
  declarative act language already exists (6.0); its adoption is a
  migration question, not a language question.
- **Cross-resource guard checks** (`meal_is_listed`: read the input's
  ref, judge its state). An expression that *reads* is not pure over
  its declared scope; giving the language a sanctioned read node drags
  async evaluation and a visibility question into every consumer.
  `reads=` already names the dependency honestly. Punted, with the
  suspicion that the right answer is a new *declaration* (a Ref-state
  acceptance set) rather than a richer expression.
- **Garnish** — `vars=` lambdas that build prose, `flips_at=`,
  `accepts=` set builders. All advertisement or scheduling, none of it
  the value of a fact; the diff classes already treat them as such.
  Each is convertible later if a findings doc asks.
- **Upcasts, service adapters, Mirror adapters** — the world's edge.
  Not law; never were.

# The scar table

| Strain (recorded, cited) | 8.0 fate |
| --- | --- |
| 7.0 §4: "code does not interpret per-row"; the pilot gate refuses fn diffs | §1–§3: the fn that becomes an expression becomes holdable, pilotable, measurable |
| 7.0 deviation #6: grandfathered laws serve resident code with stored parameters | §3: exact per-revision evaluation for expr facts; the deviation survives only where fn= survives |
| Fingerprint's "accepted liberty": source-text hashing; whitespace revises the law | §1: the tree is canonical; only meaning revises |
| `within=Tolerance` as the lone declarative derivation | §1 generalizes it; `Tolerance` remains as the one-node case |
| v7 x-derived follow-up: the wire names a derivation's inputs but not its meaning | §2: `x-derived.expr` — the law readable on the wire |
| mealplan/ledger fn inventory: date arithmetic, quantifiers, clock and tolerance comparisons | §1's node vocabulary — all of it, nothing more |

## Wire format delta

| Surface | v7 | v8 |
| --- | --- | --- |
| `derived.<fact>` fingerprint entry | `fn` source hash | `expr` tree (when declared); diffs path-by-path |
| Guard fingerprint `check` | source hash | expr tree (when declared) |
| Data schema `x-derived` | `{over, explain}` | `+ expr` (same strip-from-fingerprint discipline) |
| Row envelope, definition machine | — | unchanged |

## Migration sketch (v7 apps)

Mechanical and optional, one declaration at a time. For mealplan:
`end_date`, `all_days_covered`, `has_conflicts`, `all_items_checked`,
`overdue` convert to `expr=`; `plan_started` and `calendar_clear` to
`guard.expr`; `meal_is_listed` / `plan_is_planned` stay `check=`
(cross-resource reads — the recorded residue); `vars=` garnish stays
where it builds strings, converts where it names stored values. A kind
that converts nothing behaves exactly as on 7.0 and hashes identically
modulo the format version.

## Explicit 8.0 punts

- **The guard overlay** (per-row judgment law) — the data exists in
  every stored fingerprint; the render/invoke hot paths don't read it.
  Version-sized. The recorded trigger: a domain that needs a *guard*
  change grandfathered or piloted, not just previewed.
- **Read expressions** (`E.ref(...)`) — see §5; likely a declaration,
  not an expression.
- **`accepts=` / `vars=` / `flips_at=` as expressions** — convert on
  recorded friction, garnish last.
- **String operations** — prose is advertisement; templates already
  own it.
- **A general `where=` unification** (populations, edge filters, and
  expr predicates are three spellings of one predicate language) —
  real, tempting, and not earned yet.

---

## Appendix: the before/after story

**Before (v7):** the reconciliation rule for one fund class must switch
from an absolute tolerance to a relative one — `abs(d) <= 0.02` becomes
`abs(d) <= max(0.02, 0.001 * amount)`. That is an fn edit:
`code_or_shape`, no hold, no pilot. It previews (§2), promotes totally,
and strikes fund-beta the same instant as fund-alpha. Rolled back, the
old law returns as *new code* — another total strike.

**After (v8):** the rule is an expression. The deploy registers as
`proposed`; the diff pins the exact subtree that changed; `measure`
reports the flip count; Elena pilots it for
`Population(where={"fund": "fund-alpha"})`. Fund-beta's workbooks keep
evaluating the *stored current tree*; fund-alpha's evaluate the piloted
one; a withdrawal restamps and recomputes exactly. No Python shipped,
so nothing needs shipping back.
