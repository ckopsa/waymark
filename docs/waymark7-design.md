# Waymark 7.0 — the deploy is a workflow

A redesign of Waymark that starts from what 6.0 cannot say: that the law
itself is becoming. Companion documents: `waymark7-seeds.md` (the four
punts this aligns and the question that aligned them),
`waymark6-design.md`, and the dogfood findings series.

**Epistemic status.** Written ahead of dogfood evidence, on an explicit
premise — willingness to change the laws the seeds document named as
blocking — and revised once already: the first draft piloted law
per-*principal* (feature-flag shaped, with shadow-column dual
materialization to keep one-fact-one-value honest per reader). A
user-story pass across the actual domains killed that center. Cash
reconciliation's controls broke it — four-eyes exists so two people
confirm one judgment, and a preparer and reviewer under different laws
are not confirming anything — and the stories the domains genuinely
demand (effective-dated regulation, grandfathered workflows, population
pilots) all share one shape the first draft missed: **the law is a
property of the row, not the reader.** This revision makes that the
core. The finding strengthens the seeds' inviolate law rather than
bending it: one fact, one definition, one value per row survives
*untouched*, because a row lives under exactly one law. When
implementation friction arrives, this document gets rewritten again;
that is what it is for.

## Why 7.0

Three stories, all real in the domains already ported, all currently
expressible only as woven code:

*The regulator moves.* A rule changes effective March 1. The February
close must close under February's law even though March's law deploys
mid-February — which law governs is a fact about *the period being
closed*, not about when the code shipped or who is looking. Today that
is `if period >= date(2026, 3, 1)` inside a guard: two laws hiding in
one, invisible to the fingerprint, the diff, and the audit trail.

*The workflow outlives the law.* An onboarding started under checklist
v3 must not sprout v4 steps mid-flight; an in-flight wire approval
finishes under the chain it began under. `Seed(retro=Never)` already
grants this to seeded children — template edits govern future
instantiations only — but guard and machine changes still strike
in-flight rows immediately. Grandfathering is the single most common
rollout need in workflow domains, and it has no declaration.

*The pilot is a population.* "The new close process pilots with
fund-alpha's workbooks this quarter." Marcus *and* Elena both see
fund-alpha under the new law and fund-beta under the old — the controls
stay coherent because everyone at a given workbook shares its law. The
cohort that works in control-heavy domains is a set of rows, never a
set of readers.

And the definition is already a resource (5.0's thesis) — with a
degenerate machine. Boot writes it; it is current; the predecessor is
superseded. A resource whose machine has no intermediate states cannot
represent *becoming*, and a deploy IS a becoming: authored, reviewed,
tried against a bounded population, adopted or withdrawn. Every shop
rebuilds this lifecycle outside the system — effective-date branches,
version columns, flag dashboards, runbooks — precisely the
woven-concern shape every prior version exists to delete. The law
changes the way nothing else in the system is permitted to change:
silently, totally, and without a workflow. 7.0 ends the exemption.

> **A row that cannot name the law it lives under is a design error,
> not a deployment detail.**

The admission test for any 7.0 feature: can a client watching the event
stream see the proposal arrive, its blast radius measured, a declared
population begin living under it, and the promotion — each as an
ordinary transition on the definition resource — while every envelope
names the revision that governs its row? A revision that reaches
`current` with none of that history in the log fails the test.

2.0 unified advertisement and enforcement for guards; 3.0 for
visibility; 4.0 for facts; 5.0 for history's relationship to the law;
6.0 for the surface a decision is made on. 7.0 unifies them for **the
law's own becoming**: the deploy gets the machine, the guards, the
audit trail, and the bounded trial that every other change in the
system always had.

## What carries over unchanged

- The envelope, invocation, invoker, idempotency, drafts, jobs,
  attachments, events — untouched.
- 4.0's field origins and the materialization law — untouched, and this
  revision restores the seeds' inviolate reading: one value per row,
  because one law per row.
- 5.0's anchor — fingerprints, `defined_by`, continuity maps, backfill,
  stale-by-definition — promoted from mechanism to load-bearing wall.
  §3 is `defined_by` applied to rows' *lives*, not only their writes.
- 6.0's Related, Surfaces, the co-presence wave — untouched.
- Rollout as authorization — kept as the documented **first resort**. A
  feature that is new surface area (an action, a kind, a surface) pilots
  as a role or grant: one law, no lifecycle needed. The machinery below
  is for changes of *meaning*, and the design says so at every entrance.
- Per-principal semantic pilots — **demoted to a punt** (see there; the
  agent-canary is the one recorded trigger). The multi-participant
  control argument above is structural, not incidental: reader-scoped
  law breaks four-eyes by construction; row-scoped law satisfies it by
  construction.

Everything below is what changes.

---

# 1. The definition has a machine

```
draft → proposed → piloted → current → superseded
            ↘         ↘
            withdrawn  withdrawn
```

- **draft**: a revision being authored. Declarations that are data
  (machines, guards' declared facets, `Tolerance`, acceptance sets,
  `where=` filters, seeds, links, surfaces, summaries) are editable
  through the API — 5.0 §7's "definition as editable resource", finally
  with a state to be edited in. Declarations that are code (`fn=`,
  `check=`) arrive the way code arrives: a deploy whose boot finds an
  unknown fingerprint registers it as *proposed* instead of seizing
  `current`. Data edits and code deploys meet in the same proposed
  revision. (The data/code split returns with teeth in §4.)
- **proposed**: validated — the full import-time check suite runs at
  propose time; a proposal that could not boot cannot be proposed —
  diffed against current (the diff is data on the definition row), and
  reviewable. Promotion is **four-eyes guarded by default**: whoever
  authored the revision cannot promote it. The law's own E3.
- **piloted**: live for a declared *population of rows* (§3). At most
  one piloted revision per engine: the lifecycle is a stage, not a
  branch — no lattice, no merge, no flag combinatorics. Revision N+1
  pilots while N is current; nothing else is representable.
- **current**: the law of new rows (and of every population promoted
  into it). **superseded**: reached only when *no row lives under the
  revision* — laws die the way states do, when they are empty. A
  revision with grandfathered rows still living under it is not
  history; it is law, and the envelope of every such row says so.
- **withdrawn**: the honest exit, a transition with a reason, in the
  log. Flag systems bury their dead; this one records them.

# 2. Proposals show their blast radius

Before any row lives under a proposal, the system can say what it would
do — recomputation against declared inputs is already the pure function
the conformance replay uses. On demand (an action on the proposed
definition, deferred as an ordinary job): for each fact the proposal
redefines, the flip count over live rows — "`reconciled` changes on 3
of 4,102 accounts, these three"; for each tightened guard, the
newly-refused count; for each machine change, the §3 continuity
verdict. The report is a job artifact, linkable from the review. The
reviewer promoting a revision finally has what every other reviewer in
the system has had since 3.0: the decision's inputs co-present with the
affordance. Nothing here requires two live laws; this section could
ship as a 6.x wave, and if 7.0 stalls, should.

# 3. The law binds rows

Every row carries its law: a `law_revision` stamp, set at create to the
revision whose population claims it, changed only by an explicit
transition. This is not a new idea in the architecture — every
*transition* has carried `defined_by` since 5.0; 7.0 extends the stamp
from the row's writes to the row's *life*.

Piloting begins with a declaration on the pilot transition:

```
Population(after=True)                    # grandfathering: rows created
                                          # from now on; existing rows
                                          # keep their law
Population(where={"fund": "fund-alpha"})  # a bounded pilot population
Population(where={"period_gte": "2026-03"})   # effective-dated law
```

- **Reads are row-honest.** A workbook under revision 41 renders under
  41 — its machine, guards, surfaces, summaries — for *everyone*;
  `meta.law_revision` says so, as it always has, now varying by row
  rather than only by deploy date. A collection may list rows under two
  laws; each item's envelope is self-describing, which is what
  envelopes have been for since 1.0 — the generic client needs nothing.
- **Writes are judged by the row's law.** Marcus preparing a fund-alpha
  workbook is judged by revision 41's guards; preparing fund-beta's, by
  40's. Elena reviews each under the law of its row — both eyes, one
  law, per decision. Creates are judged by the revision whose
  population claims the input (the §-of-6.x create-time evaluation,
  pointed at populations).
- **Adoption is a transition.** A row moves to the newer law by
  `adopt` — explicit, guarded (the domain says when a February workbook
  may adopt March's rules: usually never; a draft onboarding, perhaps
  freely), bulk-capable, and recorded. Adoption triggers the row's
  recompute under its new law — 5.0's stale-by-definition machinery,
  applied per-row instead of per-boot. Promotion of a population is
  bulk adoption with the same honesty (`meta.recomputing` while a large
  population settles — the §4-of-6.0 discipline, unchanged).
- **The continuity requirement.** Two revisions live means one storage
  serving both: a proposal may enter `piloted` only if its continuity
  map covers both directions — states and actions it adds or removes
  are mapped, so a mixed collection renders and the replay conformance
  walks every row under the law of its stamp. A machine change too
  radical to map is too radical to pilot; environments remain the
  honest fallback, and the design says so rather than pretending.

The log stays single. Identity stays single. The audit trail answers
"under which law?" for every row and every write, because both carry
the stamp. What environments forfeit, populations keep.

# 4. One row, one law, one process

The first draft's hardest apparatus — shadow columns, dual
materialization, asymmetric maintenance with lag markers — existed only
to serve reader-scoped cohorts, and dies with them. Row-scoped law
needs none of it: a row's facts are computed under the row's law and
stored once. The seeds' inviolate law holds as written.

What row-scoped law does demand is that one process *interpret* two
revisions. The §1 data/code split becomes the capability boundary:

- **The declared law is data, and data interprets per-row.** Machines,
  guards' declared facets, acceptance sets, `Tolerance`, `where=`
  filters, seeds, surfaces — the definition store already holds every
  revision's serialized form (the fingerprint's source material). The
  engine evaluates the row's revision the way it already evaluates the
  *current* one: from declarations. The cash-recon tolerance story, the
  effective-dated threshold, the checklist template, the machine
  reshape — all data, all pilotable per-row.
- **Code does not interpret per-row.** A changed `fn=` or `check=` body
  is one Python function resident in one process; two revisions of it
  live at once is the blue/green problem the first draft paid for with
  its worst section. Refused at the pilot gate instead: a revision that
  changes derivation or check *code* cannot pilot per-population — it
  previews (§2) and promotes totally, as today. In the ported domains
  this bites rarely: the meaningful changes were tolerances, gates,
  machines, and templates — data all. Where it bites, the punt names
  the fallback (environments), and the honest long-term answer is
  making more of the law data (the `within=Tolerance` precedent:
  every fn made declarative is a fn made pilotable).

# 5. The log decides what it is

As-of rendering has been possible-for-law and impossible-for-data since
5.0, because transition rows store `input_digest`, not payloads
(friction #8, standing since the v3 port). A lifecycle multiplies its
value — reviewing a proposal, auditing a pilot population, forensics on
a withdrawal — so 7.0 finally takes the question, declared per action,
opt-in:

```
@action(..., record=Inputs())
```

Compliance domains declare it where reasons must be readable (cash
recon's `reject`; the definition's own transitions record
unconditionally — the law does not get privacy from its subjects).
Everything else keeps the digest. Historical as-of ships exactly as far
as retention was declared — honest, partial, stated — and the log
remains an audit trail that can optionally afford replay, not a
database pretending otherwise.

# 6. The scar table

| Strain (recorded, cited) | 7.0 fate |
| --- | --- |
| Effective-dated regulation as `if period >= date` woven inside guards | §3 `Population(where={"period_gte": ...})` — two laws, declared, diffed, audited |
| In-flight workflows struck by guard/machine changes; `Seed(retro=Never)` covering children only | §3 `Population(after=True)` — grandfathering as law; the row finishes under its birth law |
| "Pilot the new close with fund-alpha" — today an environment with diverging data | §3 bounded populations over one log, one identity |
| v5 §7 definition-as-editable-resource | §1 draft/proposed, with the data/code split |
| Deploys reviewed in pull requests the audit trail never sees | §1 four-eyes promotion + §2 blast radius, as transitions |
| v3 friction #8: digest-not-payload | §5 declared retention |
| First draft of this design: reader-scoped cohorts + shadow columns | Withdrawn by the user-story pass; §4 records what replaced it; per-principal survives only as the agent-canary punt |
| Seeds' inviolate law (one value per row), marked at risk by the premise | Untouched — one law per row restores it exactly |

## Wire format delta

| Surface | v6 | v7 |
| --- | --- | --- |
| `meta.law` / `law_revision` | one value engine-wide per deploy | the row's — varies within a collection; the envelope was always self-describing, now it has a reason to be |
| Definition envelopes | current/superseded | the §1 machine; transitions carry diffs, blast-radius artifacts, population declarations |
| Row lifecycle | — | `adopt` transitions (single and bulk), recorded like any other |
| Recorded inputs | digest only | `record=Inputs()` actions expose readable inputs under row visibility |

A v6 client keeps working: every row it sees is under the current
revision until someone pilots one, and a mixed collection is just
envelopes that differ — which they always could.

## Migration sketch (v6 apps)

- Mechanical: every existing row is stamped with the current revision
  at upgrade. An app that never uses the lifecycle behaves exactly as
  on 6.0 — boot's auto-revise becomes propose+promote in one recorded
  breath, and every row adopts immediately (today's behavior, now
  spelled out in the log).
- Where a guard branches on an effective date: split into two revisions
  with a `Population(where=...)`, delete the branch.
- Where a kind carries a `template_version` column: that column was
  `law_revision` wearing app clothes; migrate and delete.
- Where reject-style actions denormalize reasons into data: declare
  `record=Inputs()`, delete the denormalization.

## Explicit 7.0 punts

- **Per-principal semantic pilots** — the first draft's center, demoted
  by the user-story pass: reader-scoped law structurally breaks
  multi-participant controls, and the domains' real stories are all
  row-scoped. One recorded trigger could revive a narrow form: the
  **agent canary** (agent principals living under the proposal first —
  read-heavy, tireless, not co-signing human controls). It would drag
  the shadow-column apparatus back with it; the trigger had better be
  real.
- **Piloting code changes per-population** (§4) — refused at the gate;
  preview-then-promote-totally, or environments. The pressure this
  creates is deliberate: it rewards making law data.
- **More than one concurrent pilot / branching** — a stage, not a
  lattice. Two concurrent semantic experiments on one engine is
  evidence you need two engines.
- **Percentage rollouts, sticky sampling** — a population is a declared
  predicate over rows. Randomized assignment is a *process* for
  choosing populations and belongs to apps or agents, not the engine.
- **Federation** — unchanged; still waits for the second real server.

Not punted, on principle: **the single log** (pilot writes landing
anywhere else is the environments pattern in a costume), and now its
twin: **every row names its law.** A row served under a revision its
envelope does not admit to would be the old silent deploy, rebuilt one
row at a time.

---

## Appendix: before/after stories

### §1–§2 — Colton proposes, Elena promotes

**Before (v6):** The reconciliation tolerance is wrong for one fund
class; Colton edits `Tolerance`, deploys to staging, eyeballs a few
workbooks, deploys to production. The boot auto-revises; the first
person to live under the new law is everyone; the review happened in a
pull request the audit trail has never heard of.

**After (v7):** The deploy lands as *proposed*. Its blast-radius job
reports: `reconciled` flips on 3 of 4,102 accounts — named, linked.
Elena (not Colton — four-eyes on the law) reviews the diff and the
three accounts, and pilots it for
`Population(where={"fund": "fund-alpha"})`. Proposal, report, review,
pilot: four transitions on the definition resource, in the same log as
everything they govern.

### §3 — two laws, both eyes coherent

**Before (v6, first draft of v7):** Marcus pilots the new tolerance
personally; he prepares a workbook his law calls reconciled and Elena
reviews it under a law that disagrees — four-eyes confirming nothing.

**After (v7):** fund-alpha's workbooks are under revision 41 for
*everyone*. Marcus prepares and Elena reviews the same workbook under
the same law; fund-beta's close proceeds under 40, likewise coherent.
`meta.law_revision` on each envelope says which — the auditor can
group-by it.

### §3 — the February close closes under February's law

**Before (v6):** The March rule change ships as `if period >= date(2026,
3, 1)` inside the prepare guard — invisible to the fingerprint, the
diff, and the reviewer.

**After (v7):** Revision 42 pilots with
`Population(where={"period_gte": "2026-03"})`. February workbooks live
and die under 41 — no adoption path, by domain choice. Revision 41 is
superseded months later, on the day its last workbook closes: laws die
when they are empty, and the log knows the day.

### §5 — the auditor, one year later

**Before (v6):** "Why was this workbook rejected?" — a digest and a
shrug; the notes were denormalized into data and cleared on the next
prepare.

**After (v7):** `reject` declared `record=Inputs()`. The transition row
holds the reviewer's words next to the `defined_by` stamp naming the
law that demanded them. A resource for an answer.
