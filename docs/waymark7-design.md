# Waymark 7.0 — the deploy is a workflow

A redesign of Waymark that starts from what 6.0 cannot say: that the law
itself is becoming. Companion documents: `waymark7-seeds.md` (the four
punts this aligns and the question that aligned them),
`waymark6-design.md`, and the dogfood findings series.

**Epistemic status, stated up front.** The house discipline is that a
design earns itself from dogfood friction, and this one is written ahead
of it, on an explicit premise: *we are willing to change the laws the
seeds document named as blocking.* The seeds marked one law inviolate —
one fact, one definition, one value per row — and this design's first
job is to show that the premise does not actually require shattering it,
or the one-current-law anchor either. Both laws **generalize**: they
were written against a world where the law changes only *sequentially*,
and 5.0 already built every mechanism needed for laws to coexist —
across *time*. 7.0 lets exactly two coexist across *principals*, using
the same mechanisms. When real friction arrives, this document gets
rewritten against it; until then it is the strongest sketch the premise
supports, not settled law.

## Why 7.0

A developer ships a feature and wants three users living under it before
everyone does. 6.0's honest answers are two: if the feature is new
surface area, gate it on a role — rollout as authorization, one law,
already free. If the feature changes *meaning* — a guard's threshold, a
derived field's `fn`, a machine's shape — the answer is a second engine
on a second database: environments. Environments work, and they forfeit
everything this framework exists for: one log, one identity, one audit
trail, provable truth. The pilot users' actions land in a database that
will be thrown away; the compliance question "what did the pilot
reviewer see, under which law?" has no resource for an answer; the
promotion is a re-deploy whose relationship to the pilot is a matter of
discipline, not record.

Look at what 5.0 already built. Every transition is stamped
`defined_by` — the fingerprint of the law that governed it. The replay
conformance walks history written under superseded laws and finds it
legal *under the law of its day*. Continuity maps carry renamed actions
and states across revisions. The log, in other words, is **already
multi-law** — sequentially. Two revisions already coexist in every
system that has ever deployed twice; they merely never coexist *at the
same moment*. The entire apparatus for answering "which law governed
this?" exists and is load-bearing. What 6.0 lacks is only the present
tense: two revisions live at once, one of them for a declared few.

And the definition is already a resource (5.0's thesis) — with a
degenerate machine. Boot writes it; it is current; its predecessor is
superseded. A resource whose machine has no intermediate states is a
resource that cannot represent *becoming* — and a deploy IS a becoming:
authored, reviewed, tried by a few, adopted or abandoned. Every shop
re-invents this lifecycle outside the system — feature-flag dashboards,
deploy runbooks, canary configs — precisely the woven-concern shape
every prior version exists to delete. The law changes the way nothing
else in the system is permitted to change: silently, totally, and
without a workflow. 7.0 ends the exemption.

> **A change of law nobody could live under before it bound everyone is
> a design error, not a deployment detail.**

The admission test for any 7.0 feature: can a client watching the event
stream see the proposal arrive, its blast radius measured, a cohort
begin living under it, and the promotion bind everyone — each as an
ordinary transition on the definition resource? A revision that reaches
`current` with none of that history in the log fails the test.

2.0 unified advertisement and enforcement for guards; 3.0 for
visibility; 4.0 for facts; 5.0 for history's relationship to the law;
6.0 for the surface a decision is made on. 7.0 unifies them for **the
law's own becoming**: the deploy gets the machine, the guards, the
audit trail, and the pilot that every other change in the system always
had.

## What carries over unchanged

- The envelope, invocation, invoker, idempotency, drafts, jobs,
  attachments, events — untouched.
- 4.0's field origins and the materialization law — kept, and §4 leans
  on the law's exact wording: a stored fact never disagrees with what
  its law selects. §4's move is to be precise about *whose law*.
- 5.0's anchor — fingerprints, `defined_by`, continuity maps, backfill,
  stale-by-definition — not only kept but promoted: these are 7.0's
  load-bearing mechanisms, exercised in the present tense.
- 6.0's Related, Surfaces, the co-presence wave — untouched. Surfaces
  gain one dividend for free: "what did the reviewer see" already has a
  law stamp; §3 makes the stamp cohort-honest.
- Rollout as authorization (the seeds' Case 1) — kept as the documented
  **first resort**. A feature that is new surface area needs a role
  guard, not a pilot. The pilot machinery below is for changes of
  meaning, and the design says so at every entrance.

Everything below is what changes.

---

# 1. The definition has a machine

The Definition resource's degenerate machine grows honest states:

```
draft → proposed → piloted → current → superseded
            ↘         ↘
            withdrawn  withdrawn
```

- **draft**: a revision being authored. Where serializable declarations
  are concerned (states, actions' shapes, guards' declared facets,
  filterable/sortable, links, surfaces, summaries), a draft is editable
  through the API like any resource — 5.0 §7's "definition as editable
  resource", finally with a state to be edited *in*. Where code is
  concerned (`fn=`, `check=`), a draft arrives the way code arrives: a
  deploy whose boot finds a fingerprint the store does not know and —
  under the 7.0 regime — registers it as *proposed* instead of seizing
  `current`. The API edits what is data; the repo edits what is code;
  both meet in the same proposed revision.
- **proposed**: validated (the full import-time check suite runs at
  propose time — a proposal that could not boot cannot be proposed),
  diffed against current (the diff is data on the definition row, as
  revisions' diffs already are), and reviewable. Promotion out of
  `proposed` is **four-eyes guarded by default**: whoever authored the
  revision cannot promote it. The law's own E3, applied to the law.
- **piloted**: live for a declared cohort (§3). At most **one** piloted
  revision per engine: the lifecycle is a *stage*, not a branch. There
  is no merge, no lattice, no flag combinatorics — revision N+1 pilots
  while N is current, and nothing else is representable. This single
  restriction is what keeps the rest of the design small.
- **current / superseded / withdrawn**: as today, plus the honest exit —
  a pilot that fails is withdrawn by a transition, with a reason, in
  the log. Feature-flag systems bury their dead; this one records them.

# 2. Proposals show their blast radius

Before anyone lives under a proposal, the system can say what it would
do — because recomputation against declared inputs is already a pure
function the conformance replay uses. On demand (an action on the
proposed definition, deferred as an ordinary job):

- for each fact the proposal redefines: recompute over the live rows (or
  a declared sample) and report the flip count — "`reconciled` changes
  on 3 of 4,102 accounts, these three";
- for each guard the proposal tightens: evaluate against current rows
  and report newly-refused counts;
- for each machine change: the continuity check (§3) verdict.

The report is a job artifact — data, linkable from the review. The
reviewer approving a promotion has, for the first time, the same thing
every other reviewer in the system has had since 3.0: the decision's
inputs co-present with the affordance. Nothing in this section requires
two live laws; it could ship as a 6.x wave, and if 7.0 stalls, should.

# 3. Two laws, one log

Piloting begins with a declaration on the promotion:

```
pilot = Cohort(role="beta")        # membership is authorization, §Case 1
```

- **Reads are law-honest.** A pilot principal's envelopes render under
  the piloted revision — its actions, guards, surfaces, summaries — and
  say so: `meta.law` already exists; it now tells each reader the truth
  about *their* law. A non-pilot reader's envelope is untouched. "What
  did Elena see" was answered by 6.0's surfaces; "under which law" was
  already stamped; 7.0 makes the stamp vary honestly.
- **Writes are law-stamped — which they already are.** A transition
  performed by a pilot principal is judged by the piloted law and
  recorded with `defined_by` = the piloted fingerprint. This is not new
  machinery; it is the 5.0 stamp doing in the present tense what it has
  always done for the past. The replay conformance needs no new theory:
  it already judges each transition under the law of its stamp.
- **The one new invariant** (replacing "one current law per engine"):
  **every row must be renderable under every live law.** Rows are
  shared; a pilot user's write lands in the same storage everyone
  reads. So a proposal may enter `piloted` only if its continuity map
  covers both directions — every state/action it adds is mapped for
  current-law renderers, every one it removes is mapped for its own.
  The check runs at the pilot transition, not at promote: you may not
  begin the experiment whose results the control group cannot see.
  (This is v1's `renames` and v5's `renamed_actions`, load-bearing at
  last.) A machine change too radical to map is too radical to pilot —
  environments remain the honest fallback, and the design says so
  rather than pretending.

The log stays single. The audit trail stays single. Identity stays
single. That is the entire point: what environments forfeit, piloting
keeps.

# 4. Truth under two laws

The seeds called one law inviolate: one fact, one definition, one value
per row. The premise of this document is willingness to break it; the
finding is that it **generalizes instead**: one fact, one definition,
one value per row *per live law* — and there are at most two live laws,
by §1's single-pilot rule. Concretely:

- A fact the piloted revision redefines is materialized **twice**: the
  current column (untouched, serving everyone as today) and a **shadow
  column** owned by the piloted revision. Facts the pilot does not
  redefine have one column, as today. The shadow set is bounded by the
  diff, not the schema.
- Pilot readers' envelopes, filters, sorts, and guards read the shadow
  where one exists; everyone else never sees it. The 4.0 law holds for
  each reader exactly as written: *their* stored fact never disagrees
  with *their* law.
- **Maintenance topology is asymmetric, and honest about it.** Two code
  versions cannot share a process (a piloted `fn` is code), so the
  pilot runs as a second process over the same database — blue/green,
  except the green half is not an environment: same rows, same log,
  same identity. Writes processed by the pilot process materialize both
  column sets in the causing commit (it has both laws' code — the old
  fns ride the current fingerprint's source, or the previous image).
  Writes processed by the current process materialize the current
  columns in-commit and *announce*; the pilot process recomputes
  shadows from the bus. Shadow facts therefore carry the honest-lag
  marker the framework already owns (`meta.recomputing` /
  `synced_at`-style bookkeeping, the §4-of-6.0 and Mirror precedents):
  a pilot reader can see that a shadow fact is settling. Stated as law:
  **current-law truth rides the commit, always; pilot-law truth rides
  the commit when the pilot's engine wrote, and follows honestly
  marked when it didn't.** A pilot is an experiment; an experiment
  that says "this number may be seconds old" is telling the truth. A
  *promotion* may not: see next.
- **Promotion is a pre-paid backfill.** The shadow columns ARE the
  backfill 5.0 would run at boot — run early, amortized across the
  pilot, settled before the flip (the promote transition refuses while
  any shadow lags, the §4 `Deferred` discipline inverted). Promote
  renames shadow to primary and retires the old columns; the flip is
  O(catalog), not O(rows). Withdrawal drops shadows and nothing else
  changed: the pilot's *writes* remain — legal under the law of their
  stamp, exactly like every transition ever recorded under a
  now-superseded law. A withdrawn pilot is history, not a rollback.

# 5. The log decides what it is

As-of rendering — "show me this workbook as Elena saw it" — has been
possible-for-law and impossible-for-data since 5.0, because transition
rows store `input_digest`, not payloads (friction #8, standing since the
v3 port). 7.0 must finally take the question, because a lifecycle
multiplies its value: the review of a proposal (§2), the audit of a
pilot (§3), and the forensics of a withdrawal all want yesterday's
envelope. The answer is declared, per action, opt-in:

```
@action(..., record=Inputs())     # this action's inputs are retained,
                                  # readable, under the row's visibility
```

Compliance domains declare it on the actions whose reasons must be
readable (cash recon's `reject`, the promotion itself — the definition's
own transitions record their inputs unconditionally; the law does not
get privacy from its subjects). Everything else keeps the digest.
Historical as-of ships exactly as far as retention was declared —
honest, partial, and stated, rather than total and imaginary. The log
remains an audit trail that can *optionally* afford replay, not a
database pretending otherwise.

# 6. The scar table

| Strain (recorded, cited) | 7.0 fate |
| --- | --- |
| v5 §7: definition-as-editable-resource, "the natural end state of this line" | §1 `draft`/`proposed` — with the honest code/data split |
| The seeds' developer question: pilot a feature for a few users | §3 for changes of meaning; rollout-as-authorization kept as first resort for surface area |
| Environments forfeit the log, identity, audit trail | §3–§4: one database, one log, two laws — the forfeit was the design error |
| v5 §7 as-of reads; v3 friction #8 digest-not-payload | §5: declared retention; the definition's own transitions record unconditionally |
| Feature-flag systems: unrecorded pilots, buried failures, flag debt | §1: pilots are states with transitions; withdrawal is a recorded death; at most one pilot exists, so flag debt is unrepresentable |
| "One current law per engine" | Generalized: one law per *transition* (already true), one *ordered* pipeline of revisions, at most two live |
| "One value per row" (marked inviolate in the seeds) | Generalized, not broken: one value per row per live law, bounded at two, shadows dying at promote/withdraw |

## Wire format delta

| Surface | v6 | v7 |
| --- | --- | --- |
| `meta.law` / `law_revision` | the engine's current law | the *reader's* law — piloted for cohort members, with `meta.pilot: true` |
| Definition envelopes | `current`/`superseded` | the §1 machine, its transitions carrying diffs and blast-radius artifacts |
| Derivation events | flips | flips tagged with the revision that computed them; pilot consumers filter to their law |
| Shadow lag | n/a | `meta.recomputing`-style marker on pilot envelopes whose shadow facts are settling |
| Recorded inputs (§5) | digest only | `record=Inputs()` actions expose readable inputs under row visibility |

A v6 client keeps working: it never holds the beta role, so it never
sees a piloted law, a pilot marker, or a shadow value.

## Migration sketch (v6 apps)

- Nothing mandatory. An app that never proposes a revision through the
  lifecycle behaves exactly as on 6.0 — boot's auto-revise remains the
  degenerate path (propose+promote in one breath, recorded as such).
- Where a team runs staging environments to test semantic changes:
  adopt the lifecycle; retire the second database; keep the second
  process (it becomes the pilot half of §4's topology).
- Where reject-style actions denormalize their reasons into data
  (cash recon's `reject_notes`): declare `record=Inputs()` and delete
  the denormalization — friction #8's workaround finally dies.

## Explicit 7.0 punts

- **More than one concurrent pilot / branching lattices** — the
  single-pilot rule is the design's load-bearing simplification;
  evidence that a real team needs two concurrent semantic pilots on one
  engine is evidence they need two engines.
- **Piloting machine changes with no possible continuity map** —
  refused at the pilot gate by design, punted to environments, honestly.
- **Federation** — unchanged from v6; the second real server is still
  the trigger. (Two *processes* over one database, §4, is not
  federation — it is one engine with two bodies.)
- **Editing `fn=`/`check=` code through the API** — code arrives by
  deploy, forever. The API edits declarations that are data; the
  lifecycle governs both once they are proposed.
- **Cohort predicates beyond authorization** (percentage rollouts,
  sticky sampling) — a cohort is a role or grant, full stop. Percentage
  rollout is a *process* for granting roles, and processes that assign
  authorization belong to the apps (or an agent), not the engine.

Not punted, on principle: **the single log.** Any variant of this
design in which pilot writes land somewhere other than the one
transition log — a branch, a staging table, a replayed queue — is the
environments pattern wearing a costume, and forfeits the only thing
that makes the rest worth building.

---

## Appendix: before/after stories

### §1–§2 — Colton proposes a tolerance change

**Before (v6):** The reconciliation tolerance is wrong for one fund
class; Colton edits `TOLERANCE`, deploys to staging, eyeballs a few
workbooks, deploys to production. The boot auto-revises; the deploy IS
the rollout; the first person to live under the new law is everyone.
The review happened in a pull request the audit trail has never heard
of.

**After (v7):** The deploy lands as a *proposed* revision. Its
blast-radius job reports: `reconciled` flips on 3 of 4,102 accounts —
named, linked. Elena (not Colton — four-eyes on the law) reviews the
diff and the three accounts, and promotes to *piloted* for the
ops-beta role. The proposal, the report, the review, and the promotion
are four transitions on the definition resource, in the same log as
everything they will govern.

### §3–§4 — Marcus pilots, Priya doesn't

**Before (v6):** Marcus tests the new close flow in the staging
environment, against copied data that diverged two weeks ago, as a
user that isn't quite him. His test sign-offs are thrown away with the
database.

**After (v7):** Marcus holds `ops-beta`; his envelopes render under
revision 41 and say so (`meta.law_revision: 41, meta.pilot: true`). He
prepares a real workbook under the piloted guard; the transition is
stamped with the law that judged it. Priya, no role, sees the same
workbook under revision 40 — same row, her law, her columns; Marcus's
prepare is in her audit view too, stamped as pilot-law. When the shadow
`reconciled` is still settling after a write Priya's engine processed,
Marcus's envelope says it is settling. Nothing is hidden; two laws, one
log, every sentence true.

### §4 — the promotion that was already paid for

**Before (v6):** Promoting a redefined fact means boot-time backfill —
4,102 rows recomputed while the deploy holds the door (or a `Deferred`
un-advertises the fact while it drains).

**After (v7):** The shadow columns have been current for the whole
pilot. Promote refuses until the last lag settles, then flips shadow to
primary — a catalog operation. The rollout's cost was paid during the
rollout, which is where it belonged.

### §5 — the auditor, one year later

**Before (v6):** "Why was this workbook rejected?" — the notes were
denormalized into `data.reject_notes` and cleared on the next prepare;
the transition row holds a digest. The answer is a shrug with a
checksum.

**After (v7):** `reject` declared `record=Inputs()`. The transition row
holds the reviewer's words, readable under the row's visibility, next
to the `defined_by` stamp naming the law that demanded them. The
auditor's question has what it has needed since the v3 port: a
resource for an answer.
