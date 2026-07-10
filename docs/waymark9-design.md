# Waymark 9.0 — the law binds the row's judgment

A redesign of Waymark that finishes a sentence 7.0 wrote and 8.0 made
finishable: *writes are judged by the row's law*. Companion documents:
`waymark7-design.md` §3 (which wrote it), `waymark8-design.md` §4
(which made judgment legible but left it resident-evaluated), and
`waymark8-notes.md` (deviations #3 and the guard-overlay punt this
design collects).

**Epistemic status.** Written to a directive, immediately after 8.0,
ahead of new dogfood friction — but against the most-predicted punt in
the lineage: 8.0's notes name the guard overlay as the recorded trigger
("a domain that needs a *guard* change grandfathered or piloted, not
just previewed"), and grandfathering a gate is the single most common
rollout ask in the workflow domains already ported (the onboarding that
must not sprout v4 steps; the wire approval that finishes under the
chain it began under — 7.0's own motivating stories, § "Why 7.0",
solved there for *facts* only). The scope discipline is inherited from
8.0 and applied harder, because judgment touches hot paths: the
capability extends exactly as far as the stored law can be read back,
and not one leaf further.

## Why 9.0

Three facts, all in the record:

*The promise is true by prohibition.* 7.0 §3: "writes are judged by the
row's law." Today that holds because the pilot gate refuses judgment
diffs, so two live revisions can never disagree about a guard — the
promise is vacuously true. Vacuous truths are load-bearing in the worst
way: every hot path (render, probe, invoke) reads the *resident*
machine's guard objects unconditionally, and nothing notices, because
nothing may differ. The moment something may differ, everything that
leaned on the prohibition must lean on a mechanism instead.

*The workflow still does not outlive its judgment.* 7.0 §3 grandfathered
a row's *facts*: the February workbook's `reconciled` evaluates
February's law. But relax or tighten the close *gate* and every open
workbook is judged by the new gate at its next write — draft, in-flight,
and about-to-close alike. `Population(after=True)` grandfathers what a
row's values mean, not what a row may *do*. Half a birthright.

*8.0 stored the judgment and then didn't read it.* A `guard.expr`
verdict is a tree in every revision's fingerprint — diffable to the
leaf, reconstructable by `from_wire`, exactly as recoverable as the
`Tolerance` that started all of this. The conformance replay already
walks historical transitions against each revision's stored machine.
The data exists; the live paths are the last consumers that don't read
it. That is 8.0's own law — *the law is stored, not hashed* — satisfied
in the store and violated at the point of use.

> **Every path that applies law to a row applies the row's law.**

Not a new principle: the enforcement of two existing ones (7.0's
row-scoped law, 8.0's stored law) at the three paths that decide what a
row *affords* — which actions render, with which reasons, and which
verdicts gate the write. 9.0 is an enforcement version, the way 3.0 was
for visibility: no new inviolate law, several operative laws changed —
which is exactly why it cannot be an 8.x extension (the gate's classes
widen; the envelope's `law_revision` promise extends from values to
affordances; grandfathered rows' behavior changes without opt-in).

The admission test: a `guard.expr` leaf change — a gate threshold, a
relaxed date comparison — that 8.0 classifies `code_or_shape` and
promotes totally must get the full lifecycle: held at `proposed` while
every row is still judged (and advertised!) under the current tree,
piloted for a declared population — one collection, two gates, each
row's `actions`/`unavailable`/refusal reasons and its *enforcement*
agreeing under the row's own revision, for every reader — and
grandfathered, so an `adoption=Never` row finishes under the gate it
was born under. With zero Python deployed.

## What carries over unchanged

- The envelope, the definition machine, populations, adoption,
  `law_revision` — untouched. 9.0 adds no wire key: the envelope was
  always self-describing per row; its action surface now varies by row
  for the same reason its facts already did, and a v8 client that reads
  what it is served keeps working.
- 8.0's expression language and the derived-law overlay — untouched;
  the judgment overlay is its structural twin, not its replacement.
- The advertisement/enforcement unification — *strengthened*: the same
  resolved guard list feeds the probe and the invoke, so a row can
  never advertise one law and enforce another.

# 1. The judgment overlay

The `DerivedMaintainer` pattern, applied to guards. Each `ResourceDef`
carries two stores, installed and dropped at exactly the sites that
manage the derived `overlay`/`law_overlay` today (boot re-detection,
the propose-hold, pilot, promote, withdraw, the supersede sweep):

- **`judgment_served`** — action → per-guard overrides from the CURRENT
  revision's stored fingerprint, installed while a judgment proposal is
  held or piloted (the resident guard objects are the *new* law; the
  current one serves from the store).
- **`judgment_laws`** — revision number → the same, one entry per
  non-resident revision that still has rows (grandfathered laws, and a
  parameter-served pilot across a reboot).

Resolution mirrors `specs_for`, per row: the piloted/proposed
revision's rows get the resident guards verbatim (the resident code IS
that law); a revision with an installed entry gets the resident guards
with its stored overrides substituted; everything else gets the served
law. One function — `guards_for(rdef, action, defn, revision)` — and
three call sites: `probe_transition`, `probe_hidden_only`, and the
invoker's guard loop. Render and enforcement read the same resolution,
which is the 2.0 unification surviving row-scoping.

An override substitutes what the fingerprint can read back of an
**expression guard**: the `when` tree, the `vars` expression garnish,
`explain`, `remedies`, `hide`, `severity`, `requires_token`. It rides
the resident guard for what a fingerprint cannot hold —
`becomes_available_at` is a callable, so structured hope evaluates
resident (the recorded deviation, same family as v7's #6, confined to
scheduling garnish).

# 2. The gate widens by one honest class

`classify_diff` admits judgment paths as `data_law` exactly when every
changed leaf is a recoverable field of a **top-level expression guard
present in both revisions**:

```
machine.actions.<name>.guards.<i>.(expr…|vars_exprs…|explain|
                                   remedies…|hide|severity|requires_token)
```

Everything else keeps its 8.0 fate, and each refusal is a reason, not a
limitation:

- **A `check=` hash** — code does not interpret per-row (the §4
  boundary, verbatim). Converting a check to an expression necessarily
  changes the `check` leaf too, so a convert-and-change deploy
  promotes totally *once*; thereafter the guard is pilotable forever.
  The pressure points at conversion, exactly as designed.
- **Adding or removing a guard** — the guard *list* is machine
  structure; positional paths shift and the gate refuses. Two-step it:
  ship the guard inert (`when=E.lit(True)`), then pilot the meaning —
  the expand/contract discipline again.
- **Machine shape** (states, transitions, action sets, input schemas)
  — actions have handlers and handlers are code; a row whose law
  affords an action the resident process cannot execute is not a
  pilot, it is an outage. Environments remain the honest fallback.
- **Safety flags, composite (`&`/`|`) internals, `require`/`rollup_is`
  parameters, create guards** — each recoverable in principle, each
  punted with its own trigger (below). The boundary is drawn where the
  dogfood stories live: gates whose *verdict* moved.

Judgment diffs mark no facts stale — a gate change flips no stored
value — so a judgment-only promote is instant: restamp, no recompute.

# 3. What two live judgments must keep coherent

- **Advertisement equals enforcement, per row.** The probe that renders
  `actions`/`unavailable` and the invoke that refuses resolve the same
  guard list for the same row. A 409's detail is the same sentence the
  envelope's `unavailable.reason` showed — under the row's law, not
  the deploy's.
- **Four-eyes stays row-coherent** — 7.0 §3's argument, unchanged:
  Marcus prepares and Elena reviews one workbook under one gate,
  because the gate is the row's.
- **The audit trail answers "under which gate?"** for free: every
  transition already carries `defined_by`, resolved through the row's
  stamp since 7.0. The conformance replay already re-walks each
  transition against its revision's stored machine — 9.0 makes live
  behavior match what replay always assumed.
- **Acceptance sets are out of scope and unaffected**: expression
  guards declare no `accepts`, so the admits-folding that tightens
  schemas keeps reading resident declarations — which the gate
  guarantees are identical across live revisions.

# The scar table

| Strain (recorded, cited) | 9.0 fate |
| --- | --- |
| 7.0 "the workflow outlives the law" — solved for facts, open for gates | §1–§2: the gate is the row's; `after=True` and `Never` finally cover what a row may *do* |
| 7.0 §3 "writes are judged by the row's law" — true by prohibition | §1: true by mechanism; the prohibition (the gate) relaxes by exactly the class the mechanism can serve |
| 8.0 §4 / notes: guard.expr diffs "not per-row pilotable; the guard overlay is the named punt" | Collected: §1 is that overlay |
| 8.0's law violated at the point of use (stored judgment, resident evaluation) | §1: the last consumers read the store |
| conformance replay walks stored machines; live paths don't | §3: live and replay finally agree on whose machine judges |

## Wire format delta

None. `meta.law_revision` already named the row's law; `actions`,
`unavailable`, and refusal details were always row-rendered. What
changes is which revision's declarations they render *from* — the
envelope's existing promise, kept more thoroughly.

## Migration sketch (v8 apps)

Mechanical: nothing. A kind that never pilots a judgment diff behaves
byte-identically. The one visible change without opt-in: an
`adoption=Never` kind grandfathered across a judgment change now judges
old rows by their birth gate instead of the resident one — which is the
bug fix wearing a behavior-change label, and the reason this is a
version, not an extension.

## Explicit 9.0 punts

- **Guard blast radius** (`measure` counting newly-refused rows per
  tightened gate) — 7.0 §2 promised it, the meter is derived-only, and
  probe-able (input-free) expression guards make it computable.
  First 9.x extension; needs no new law.
- **Create-guard routing** — creates are already *claimed* by
  population but still judged by resident create guards. Trigger: a
  domain that pilots a create-time gate.
- **Safety overlay** (per-row `confirm`/`consequence`) — recoverable
  data, but `idempotent`/fence flags feed replay semantics; needs its
  own honesty argument.
- **Composite internals, `require`/`rollup_is` parameters as
  overridable leaves** — each a small vocabulary extension once a
  story demands it.
- **Machine-shape pilots** — refused on the handler/code boundary;
  the long-term answer is the same as ever: make more of the act
  declarative (`Compound` is the existing precedent).

---

## Appendix: before/after stories

### The gate that outlives the deploy

**Before (v8):** The family's rule is that a week can't start before
its Tuesday. Colton proposes letting it start a day early. The
`plan_started` tree diffs at one leaf — beautifully reviewable — and
then promotes totally: every plan, including the two mid-flight, gets
the lenient gate at its next write. Rolling back re-strikes everyone
again.

**After (v9):** The deploy holds at `proposed`; every plan still
renders and enforces the strict gate from the stored current tree.
Elena pilots it for `Population(after=True)`: plans created from now on
may start early; the two in-flight weeks keep the gate they were
planned under, and their envelopes say so. Promote when the family
likes it; the old revision supersedes when its last plan completes.

### The auditor's question, closed

**Before (v8):** "Why was Marcus allowed to close this workbook on
March 3rd?" — the transition's `defined_by` names revision 41, and the
auditor must *trust* that the resident code that day enforced 41's
gate, because nothing but deploy archaeology says which gate actually
ran.

**After (v9):** The question is answerable from the store alone: the
row was stamped 41, the three paths judge by the stamp, and the
conformance replay demonstrates it — the same stored machine that
explains the past is the one that governed the write.
