# waymark10 batch C — law completeness (notes)

Batch C closes the derivation-law gap: the derived-law overlay
(judgment's structural twin), the derivation event class, the
blast-radius meter, the population grammar gate, the earned
count/sum vocabulary, and the cross-kind fact DAG. Owned files:
`server/{definitions,judgment,maintainer,events}.clj`,
`fingerprint.clj`, `derived.clj`, `expr.clj`, `checks_assembly.clj`,
`resources/waymark10/ui.html` (one handler), `test/waymark10/
batch_c_*.clj`, this file.

## 1. The derived-law overlay

`waymark10.derived/specs-under` — waymark9 `derived.py specs_for`,
in the v10 medium: the rdef's `:judgment-laws` slot ({revision →
stored fingerprint}, the SAME slot the judgment overlay reads,
installed by the definitions lifecycle) resolves a row's derivation
specs from its revision's stored trees. Recoverable leaves: the
`expr` tree and the aggregate `where` filters — exactly the paths
`classify-diff` files as `data_law`, so the overlay is EXACT for
every law the slot can hold. Edge identity (`:over`, `:owns`,
`:related`, `:of`) rides resident, the judgment precedent verbatim;
`fn=` facts stay resident (a hash is not a law — the v7 deviation,
now confined to fn facts). Resolved maps cache on `:judgment-cache`
under `[revision ::waymark10.derived/specs]` keys, disjoint from
judgment's `[revision action-name]` keys; every definitions
`install!` resets the cache, so invalidation is free.

Consumers, wired:

- `derived/materialize` resolves `(:law-revision row)` itself — the
  write boundary (invoke's finish! and create!) needed NO edit; a
  grandfathered row's expr facts recompute under its birth law at
  every write. `tampered` judges by the same law.
- the maintainer: `maintain-row!*` computes aggregates from
  `aggregate-specs rdef revision`, materializes (row-law-aware), and
  derives the clock index from the row's law's trees
  (`next-flip-at` gained a revision arity). The REVERSE dependency
  map stays resident — edges are `:code-or-shape`, no live revision's
  edges can differ (recorded boundary).
- `backfill!` recomputes each row under ITS OWN law, which makes it
  the correct repair for every restamp: adopted rows land the new
  values, grandfathered survivors repair under their birth law.

The phase-6 named seam is wired: promote (and pilot, and a withdraw
that moved rows — the returning population's facts were computed
under the pilot) call `repair-stale!` → `maintainer/backfill!` with
the diff's `stale-facts`, markers naming since-removed facts dropped
with a *err* line (waymark9's `_still_declared`).

## 2. Derivation-class events

waymark9's second channel comes home WITHOUT making maintenance a
transition. The observations table (events.clj owns the DDL — the
store protocol files are another batch's; a protocol surface is the
named follow-up):

    waymark10_observations (
      id bigserial PRIMARY KEY,
      kind text NOT NULL,
      resource_id text,          -- NULL = the whole kind (bulk restamp)
      class text NOT NULL,       -- recompute | flip | restamp
      changed jsonb,             -- fact names, or {law_revision, rows}
      at timestamptz DEFAULT now())
    + index (kind, id), pg_notify on waymark10_observations

Appended INSIDE the maintenance write's transaction
(`events/record-observation!`), so an observation exists iff its
commit does — the outbox discipline. Emitters, covering the three
live-update gaps:

- cross-row count/sum recomputes: `maintain-row!*` when facts moved
  (class `recompute`; backfill repairs use the same class),
- clock flips: the sweep passes class `flip`,
- bulk law restamps: `definitions/restamp!` (pilot, promote's
  immediate adopt, withdraw's return) emits one kind-wide
  observation (`restamp`, resource_id NULL, changed carries
  `{law_revision, rows}`) beside the per-row backfill recomputes.
- the measure report landing on `data.measure` announces itself
  (`recompute` on the definition row).

The dispatcher LISTENs the second channel on the same parked
connection and drains both tables on every wake-up, each with its
own id horizon. Subscriptions opt into classes (`:classes`, default
`#{:transition}` — the coherence consumer and the webhook deliverer
never see a shape they predate); the SSE handler subscribes to both
and frames derivations as `event: derivation` with NO id line.

Recorded scope: no Last-Event-ID replay for derivations (the table
is the record; a missed flip re-derives from the envelope);
derivations bypass a replaying subscription's transition pause, so
ordering ACROSS classes is not promised; observations (like the SUM
SQL) are a Postgres surface — any other backend warns and drops.

ui.html: one handler beside the transition refetch — a derivation
event whose self (or kind, for kind-wide restamps) matches the open
screen debounces into the same refetch.

## 3. Blast radius (measure)

waymark9's `measure` action + `BlastRadiusMeter`, synchronous (no
meter job — v10 job artifacts are unported, recorded):
`maintainer/blast-radius` full-scans the target kind in id-keyset
pages and, per redefined derived fact, evaluates BOTH laws' specs
over current data — expr facts through `compute-facts`, aggregates
through both where-filters' SQL — counting the rows whose value
differs. Population-scoped when piloted. Report:
`{:facts [{:fact "kind.fact" :flips n :of total :sample [≤20 ids]}]
:scan "full" :population … :from_revision :to_revision :at}` on the
definition row's `data.measure`.

Recorded deviations: the proposed/piloted self-loop is spelled as
TWO actions (`measure`, `measure_pilot`) because a v10 action
declares one `:to`; the report lands via a maintenance write AFTER
the transition commits (the measure POST's response predates it —
GET re-reads; waymark9's answer carried a job id, same shape of
honesty). Guards are input-free and probe-able: `data-law-measures`
(expr over `diff_class`) and `redefines-derived-facts` (a
judgment-only data-law diff has no blast radius — waymark9's exact
refusal sentence). Judgment blast radius (newly-refused rows) stays
punted, named.

## 4. Population grammar

waymark9's `check_population` punt closes: pilot's `where=` runs
each field through the collections parser's PUBLIC `parse-query`
(collections.clj untouched) against the TARGET kind's grammar, and
only plain equality conds pass — range suffixes, multi-values and
non-scalar values are refused by sentence (a restamp is an equality,
not a query). The guard needs the engine's registry, which a static
declaration cannot reach, so `boot-revise!` appends the
engine-closed guard to the pilot action (idempotent by name);
engines that never run the definitions boot also never pilot.

## 5. Vocabulary growth, earned

| Node | Demanded by | Date |
| --- | --- | --- |
| `(count <coll>)` / `(count [d <coll>] <pred>)` expr quantifier | the blast-radius acceptance laws (batch_c_measure_test's flip-count shapes) — waymark8 §1 had the pair; v10 ports it with the meter that reads it | 2026-07-10 |
| `(sum [d <coll>] <expr>)` expr quantifier | same admission — the summed-quantity flip the meter measures | 2026-07-10 |
| `{:sum {:related|:owns …, :where …, :of field}}` aggregate spec | the meal-prep quantity rollup (batch_c_sum_test's total_qty) — mirrors `:count`: fingerprint facet, load/assembly checks, maintainer SQL | 2026-07-10 |

Semantics recorded: `count`/`sum` of a missing/non-sequential
collection are 0 (the quantifier empty rule, numerically); `sum`
nil-propagates on any non-numeric item value (a missing addend is a
missing sum, arithmetic's rule — never a silent skip). Neither is
boolean-valued, so neither joins the and/or collapse set. Division
stays unearned — no batch-C test needed it.

`sum.where` classifies `data_law` (the count.where rule verbatim);
`sum.of` is edge identity — `:code-or-shape`, with the edges. The
SUM SQL renders in maintainer.clj against the maintainer's own cond
grammar (a local twin of the storage renderer — the store protocol
is batch F's hot file; a `sum-matching` protocol op is the named
follow-up). An `:int` sum fact must sum an `:int` column (assembly
check) — an integer fact cannot hold a fractional sum.

## 6. The cross-kind fact DAG

`checks-assembly/check-fact-dag` (closing phase 6's named punt):
nodes are `kind.fact`, edges are same-kind `:over` dependencies plus
— through each aggregate's declared edge — the target facts its
`where` filters and `of` read. DFS refuses cycles naming the path
(`kind.fact → kind.fact → …`); the maintainer's per-write visited
set now only ever truncates chains the assembly proved finite.

## Ownership deviations, flagged

- `checks.clj` (2 scoped edits): the exactly-one-of gate and the
  per-declaration aggregate-spec check live there and REFUSE any
  `:sum` spec at `defresource` — the deliverable could not land
  without admitting the key at the load boundary.
- `resource.clj` (2 lines): `normalize-derived-spec` canonicalizes
  `:sum :where` values to sets, the `:count` rule verbatim.
- `test/waymark10/definitions_test.clj`: the pre-existing pilot test
  piloted on `weeks`, an unpromoted field — legal only while
  populations went unvalidated; it now splits its population on
  `start_date` (filterable), asserting the same overlay story.
- `waymark10.router-test/well-known-lists-the-kinds` fails on an
  in-flight `approval_request` kind from another batch's working
  tree — not a batch-C change.
