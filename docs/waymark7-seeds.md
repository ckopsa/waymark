# Waymark 7.0 seeds — the lifecycle of the law itself

This is not a design. The house discipline is that a version's design
earns itself from dogfood evidence, and 7.0's evidence base does not
exist yet — 6.0 is days old. This document records the candidate thesis
so that when the friction arrives it lands somewhere, the way the
ledger5 findings doc held "composition" until it became the 6.0
design. Companion evidence so far: `waymark5-design.md` §7 (the punts
that started this), `waymark6-design.md` "Explicit punts",
`_dogfood-ledger6-findings.md` "Verdict", and a developer question
recorded below verbatim, because it did what questions sometimes do —
showed that four standing punts are one thesis seen from four sides.

## The question that aligned the seeds

> As a developer, if we develop a new feature, but we want to have a
> few users test it before a broader rollout, how would we set that up
> in waymark?

The answer splits on the design's own sorting rule (extension = coverage
grows while every law holds; version = a law must change), and the split
is the whole insight:

**Expressible today — rollout as authorization.** A feature that is new
*surface area* (a new action, kind, or surface) pilots with no new
machinery: guard it on a role or grant. A new action behind
`role:beta` advertises only to holders (hidden or honestly unavailable
for everyone else); a new decision surface is directly grantable —
`surface:x / view` to three pilot principals IS the rollout. The key
reframe: the flag is not a fork of the law, it is a **fact about the
principal**. One law, which says "this affordance requires the role";
conformance walks it, the fingerprint hashes it, and the broad rollout
is one `revise` that drops the guard — a better rollout record than any
flag dashboard. Nobody should build 7.0 for this case.

**Not expressible, refused on principle — cohort-relative meaning.** A
changed guard threshold, a changed derived `fn`, a changed machine, live
for some principals and not others. Two laws block it, and they are the
architecture's two best laws: one-fact-one-definition (a derived field
whose `fn` differs per cohort is one fact with two *values*, and facts
are materialized into the row — a row cannot store `reconciled = true`
for Elena and `false` for Marcus), and current-truth-follows-THE-current-
law (backfill, `defined_by`, replay conformance all assume one live law
per engine). Cohort-relative semantics make truth principal-relative,
which this architecture exists to prevent. Today's honest answer for
semantic changes is **environments, not flags**: a second engine with
the new law — and the fingerprint machinery makes the difference between
the two environments *provable*, a diff of two definition fingerprints.

## The thesis, provisionally

> **The deploy is a workflow.**

The Definition resource today has a degenerate machine: boot writes it,
it is current, the predecessor is superseded. 7.0 would give the law a
real lifecycle — something like `proposed → piloted → current` — where:

- `proposed` is a revision written through the API (v5 §7's
  "definition-as-editable-resource", verbatim), reviewable as an
  ordinary resource with an ordinary diff;
- promotion is four-eyes guarded — the law's own E3, applied to the law;
- `piloted` means live-for-a-declared-cohort, and the scoping writes
  itself from the materialization constraint: **additive revisions may
  pilot per-cohort** (new actions, kinds, surfaces — the storage-safe
  half, which the authorization story above shows is nearly free
  already; a pilot is sugar for a generated role-guard the promotion
  removes), while **meaning-changing revisions pilot per-environment
  only**, because one row cannot hold two truths. That restriction is
  not a compromise; it is the expand/contract migration discipline
  elevated to law.

## The four sides that converge on it

1. **Editable definitions** (v5 §7, "the natural end state of this
   line") — `proposed` is that feature; the lifecycle gives it the
   review-and-promote shape it always needed.
2. **Staged rollout** (this document's question) — `piloted`, with the
   additive/semantic split above.
3. **As-of rendering** (v5 §7) — a lifecycle multiplies the value of
   "render under revision N," and it is still blocked by the standing
   digest-not-payload decision (transition rows store `input_digest`,
   not payloads — ledger friction #8, unresolved since v3). If 7.0
   takes this side seriously, the log finally has to decide whether it
   is an audit trail or a database; that decision is version-sized on
   its own.
4. **Federation / cross-engine** (v6 punt) — two engines under
   *provably diffed* laws is the environment story above; a shared
   vocabulary for "my law revision N corresponds to your M" is where
   piloting-per-environment stops being ad hoc. Still waits for the
   second real server.

## What would trigger the design

Per the methodology, not calendar time but recorded friction: an app
that genuinely needs a semantic pilot and finds environments too heavy;
a compliance ask that "what did the reviewer see" must extend to "under
which *proposed* law"; the second real server. Until one of those lands
in a findings doc, this stays a seed.

## Laws that would change (named now, so the cost is visible)

- "One current law per engine" relaxes to "one current law per engine
  *per cohort scope*, additive-only below `current`."
- The definition's machine grows states — which means the definition
  kind's own conformance, fingerprint, and continuity story recurse one
  level (the law about laws gets a law). v5's `__registry__` precedent
  suggests this is tractable; nobody should pretend it is small.
- Untouched, inviolate: one fact, one definition, one value per row.
  Any 7.0 sketch that bends this is wrong, per the two versions of
  evidence that produced it.
