# Waymark 5.0 — design

A redesign of Waymark that starts from what 4.0 learned the hard way — or
rather, from what 4.0 *created*. Companion documents: `waymark4-design.md`
(the 4.0 case), `waymark4-notes.md` (what 4.0 shipped and where it
deviated), and the 4.0 build itself, whose materialized truth is the
evidence base here the way the enterprise mappings were the evidence base
for 4.0.

## Why 5.0

Each version's law has been the previous law aimed at where the strain
moved, and each version's law has quietly created its successor's strain.
2.0's declarations gave the delivery pipeline more concerns to hand-weave
— 3.0. 3.0's three citizens gave the apps a fourth kind of thing to
smuggle — the computed fact — 4.0. 4.0 made truth cheap: one definition,
materialized everywhere, every consumer reading the one name.

And in doing so it made *redefinition* expensive for the first time. Look
at what the 4.0 engine now holds that is a pure function of the definition
module:

- Every derived fact, **materialized as a column** — computed by a
  specific `fn`, judged against a specific `Tolerance`, at a specific
  moment, under a specific declaration. Change the `fn` and every stored
  value is stale *by definition*; `shape`/`upcasts` migrate data shape,
  but nothing notices that fact **semantics** changed, and nothing forces
  the backfill. The migration sketch says "backfill = run the derivation
  once per row" — as a step someone remembers, which is exactly the shape
  of promise the 2.0 design said compounds.
- Every refusal reason, `explain=` template, `Unless` sentence, and
  demand class — recorded in the transition log **as rendered under the
  definition that was live**. The log says a guard refused with reason X;
  under which guard text? Conformance proves the *present* agrees with
  the definition module. Nothing proves the past did, or says which past
  definition it agreed with.
- `migrations/waymark4/snapshot.json` — a versioned artifact of the
  declared schema… of its **storage** half only. The columns are
  snapshotted; the `fn` that fills them, the tolerance that judges them,
  the guard that advertises them are not. The one artifact that versions
  anything versions the least meaningful part.

Meanwhile, everything *inside* the boundary is a resource — identity, a
state machine, an audit trail: members, grants, drafts, jobs, webhooks,
even attachments. Everything except the definition that produces them
all. It lives in git, outside the envelope, and a deploy changes the law
silently mid-history. A rollback is an undeclared mass transition of
meaning: every affordance, reason, and fact in the system changes what it
means, and the audit trail — the thing the framework has defended since
v0.1 — records nothing.

The series' own precedent points at the answer. `check_state_tokens`
already refuses to boot when a row's state has no home in the machine —
the engine already knows that a definition change can orphan history, for
exactly one field. v1's `renames`/`maps_to` punt already admitted states
get renamed. E4's "template edits never retro-propagate" is a *recorded
policy* about definition change with nowhere structural to live. The
mechanism exists in fragments; 5.0 is the fragments made law:

> **Nothing about the past is reinterpreted that a declaration could have
> versioned. The definition is a resource: every deploy is a transition
> on it, every write is anchored to the definition version that governed
> it, and current truth is recomputed under the current law before it is
> served. A change of meaning that the audit trail cannot show is a
> design error, not an operational detail.**

This is the same move a fourth time. 2.0 unified advertisement and
enforcement for guards; 3.0 for visibility; 4.0 for facts; 5.0 unifies
them for **history's relationship to the law that governed it**.

The admission test for any 5.0 feature: can a client that watches the
event stream see the law change the way it sees every other change — as a
transition, with an actor, a diff, and an anchor history can be joined
against? A deploy that alters what a fact means without a transition
recording it fails.

## What carries over unchanged

- The envelope, uniform invocation, the single invoker, idempotency
  replay, errors as hypermedia, drafts, relay/2, discovery — untouched.
- The 4.0 field origins (written / derived / authored), `Derived` and the
  materialization law, the three event classes, `Unless`, `When`,
  `Compound`, `Batch`, per-field authority — all kept; 5.0 anchors them,
  it does not reshape them.
- Visibility as projection input; identity and grants; Services, Mirrors,
  subscriptions.
- The transition log as audit trail + outbox + idempotency anchor — 5.0's
  whole design is a compliment to it: the log was always the system's
  memory; now it remembers the law too.
- Migrations as snapshot-diffed revisions — *subsumed but not broken*:
  the storage snapshot becomes one facet of the definition revision (§1).
- Conformance as the proof that wire, enforcement, audit, events, and
  safety agree with the definition module — extended backward in time
  (§5).

Everything below is what changes.

---

# 1. The definition is a resource

An engine-owned kind, `definition` — one instance per resource kind, plus
one for the registry as a whole. Its `data` is the **canonical
fingerprint** of the declaration: the machine (states, transitions,
safety), every guard's metadata (`judges`, `reads`, `accepts` shape,
`explain` template, severity), every derivation (`over=`, a content hash
of `fn`, the `Tolerance` literal, `explain=`), visibility templates,
`unique=`, `Owns` edges, `When` predicates, the query surface
(filterable/sortable/facets), and the storage shape (today's
`snapshot.json`, folded in as one facet). Deterministically serialized,
content-hashed, diffable in review.

- The fingerprint is computed at import from the same objects the engine
  already consumes — it is a *projection of the registry*, not a second
  description that can drift. Anything that changes what the engine would
  advertise, enforce, compute, or store changes the hash.
- The definition resource's state machine is small and honest:
  `current → superseded`, by exactly one transition, `revise` (§2).
  Its collection is the deploy history; its envelope is readable by the
  generic UI like any other resource. What "the law" has been, when, is a
  screen, not an archaeology of git and deploy logs.
- Code does not move out of git. The resource is the **record and the
  anchor**, not the source: git holds the text, the definition resource
  holds "which text was live, when, and what it meant" — the same
  division of labor the transition log already has with the database.

# 2. Deploys are transitions

At startup the engine fingerprints the registry and compares it to the
current `definition` rows.

- **Unchanged** → boot proceeds; nothing is written (the common restart
  costs nothing).
- **Changed** → the boot IS a `revise` transition on each changed
  definition, by a `system` deploy actor (the process identity, or a
  declared deployer), carrying the diff summary in the transition — which
  declarations changed, at what severity (§4). It lands in the log, rides
  the bus, notifies subscriptions: the law change is an event a dashboard,
  an agent, or a third-party webhook consumer sees like any other.
- **A rollback is just another revise** — to a fingerprint the history
  has seen before, which the diff names as such. The undeclared mass
  transition of meaning becomes a declared one, with an actor and a
  timestamp, in the same audit trail as everything it reinterprets.

# 3. Every write is anchored

- The transition log gains one column: `defined_by` — the definition
  revision under which the write was validated, guarded, and rendered.
  Joinable: "the log records that `approve` was refused with reason X"
  now answers *under which guard text* by construction.
- Envelopes carry `meta.law` (the current revision id) so a client — or
  a follower reading a transcript — can correlate what it saw with the
  law that produced it.
- Derivation events and webhook deliveries carry the same anchor, so a
  third party that stored a fact can later ask whether the fact's
  definition has changed since (§2's revise event tells them *when*).
- Rows keep carrying `shape` for data; they gain nothing — row-level law
  is not stored, because current truth always follows the current law
  (§4), and past truth lives in the log, which is anchored.

# 4. Current truth follows the current law — provably

The 4.0 materialization law said a derived fact the engine cannot maintain
is a definition error. 5.0 extends it across deploys:

- Every `Derived` (and `Tolerance`, and relation-derived enum fold)
  contributes a **semantic hash** to the fingerprint. A deploy whose diff
  touches a fact's semantic hash marks that fact **stale by definition**.
- The engine refuses to serve stale-by-definition truth, on the
  `check_state_tokens` precedent: boot runs the backfill — recompute the
  fact over its rows, batched, before the kind serves — or, for large
  tables, the deploy declares `backfill=Deferred(...)` and the engine
  serves the kind with the fact honestly marked (`meta.recomputing`,
  the fact absent from filters until caught up — un-advertised, not
  wrong, the same honesty Service-down gets). What is unrepresentable:
  serving a materialized value as current truth when the current law
  disagrees with it. "A step someone remembers" becomes a boot invariant.
- Backfill recomputation emits **no derivation events** — per-row flips
  during a redefinition are noise; the `revise` transition (§2) is the
  one loud event, and it is exactly as loud as the change deserves.
- E4's seed-template policy gets its structural home: a revision's diff
  classifies each change as `advertisement` (display, labels), `judgment`
  (guards, tolerances, demand), `truth` (derivations, semantics), or
  `shape` (storage) — and declared policies (`retro=never` for seeds,
  backfill for facts) attach to the class, not to prose in a notes file.

# 5. History keeps its own law

The other half: the past is never recomputed, and never orphaned.

- **Conformance proves the past.** The definition revision stores enough
  of each machine (states, transitions, action names) to validate
  history: the suite gains a replay check — every stored transition's
  `(action, from_state, to_state)` must be legal under the revision it is
  anchored to. Today the suite proves the present agrees with the module;
  5.0 proves every recorded write agreed with *its own* law. A corrupted
  anchor, a hand-edited row, or a mis-mapped rename is a conformance
  failure, not a mystery.
- **The continuity map.** v1's `renames` punt generalizes: a revision
  that removes or renames a state, action, or fact must carry a declared
  map (`renames={"approve": "authorize"}`, `maps_to`, `retired=`), and
  boot refuses history it cannot map — the `check_state_tokens` refusal,
  applied to the whole vocabulary. The map is data on the `revise`
  transition, so the audit trail reads continuously across renames: a
  follower scrolling two years of log never hits a name that means
  nothing under any law it can reach.
- Rendered strings in the log (summaries, reasons) were always written at
  write time — v0.1 got this right by accident of design; 5.0 states it
  as law: **log prose is never re-rendered**. The anchor makes the
  original definition reachable; the text stays what the actor actually
  saw.

# 6. The constants are the law

The three-tolerances scar closed in 4.0 by making the tolerance one
declaration. 5.0 closes its temporal sequel: *which* tolerance was in
force when Elena approved the reconciliation. The `Tolerance` literal,
`accepts` shapes, `When` comparisons, rate limits, retention windows —
everything reviewable in code review is in the fingerprint, so changing
any of it is a `revise` with a diff a reviewer (and the log) sees. "Why
did this pass in March?" is answered by joining March's transitions to
March's law — two indexed reads, not a git bisect against deploy
timestamps reconstructed from a CI dashboard.

# 7. What the anchor makes possible (and 5.0 does not build)

Stated so the seam is deliberate, per the punt rule:

- **As-of reads** — rendering an envelope under a past law — become
  *possible* (the revision stores the machine and surfaces) but are not
  built; the known consumers (audit review, dispute resolution) read the
  log, which is already anchored and immutable.
- **Cross-server law** — a federated peer's definition changes are
  visible through its own `definition` kind and subscriptions; a shared
  vocabulary protocol still waits for the second real server.
- **Definition-as-editable-resource** — writing the law through the API
  (declarations edited via envelopes, approved via four-eyes, deployed
  via a transition) is the natural end state of this line; 5.0 stops at
  read-only + boot-written, because the write path for code is git and
  review, and pretending otherwise would put policy outside code review —
  the exact thing §9 of the 3.0 design refused.

# 8. The scar table

4.0's strains — mostly predicted by its own design and notes rather than
bled in production, which is itself the argument for acting now, while
the count is low:

| 4.0 strain | 5.0 fate |
|---|---|
| `Derived.fn`/`Tolerance` change leaves materialized lies until someone remembers the backfill | stale-by-definition is unbootable; backfill is an invariant or a declared deferral (§4) |
| "guard refused with reason X" — under which guard text? | `defined_by` on every transition; revisions store the texts (§3, §1) |
| a rollback silently reinterprets everything | a `revise` transition like any other, diff naming it a reversion (§2) |
| `snapshot.json` versions storage only | one facet of the definition fingerprint (§1) |
| conformance proves only the present | replay check: every stored transition legal under its own law (§5) |
| `renames`/`maps_to` covers states only | the continuity map covers states, actions, facts; unmapped history refuses boot (§5) |
| seed-template retro-propagation as prose policy | declared `retro=` policy on a diff class (§4) |
| deploy visibility = ops tooling outside the boundary | the deploy is on the bus, in the log, in the webhook stream (§2) |

## Wire format delta (v4 → v5)

| Surface | v4 | v5 |
|---|---|---|
| `waymark` | `"4"` | `"5"` |
| `meta` | version, etag, synced_at… | + `law` (current definition revision id) |
| Transitions (log + events) | actor, correlation, acknowledged… | + `defined_by` |
| Engine kinds | member, grant, job, attachment… | + `definition` (read-only; `revise` is boot's transition) |
| Derivation events / webhooks | fact, cause | + `defined_by` |
| Everything else | | unchanged — the novelty is spent on anchoring, not reshaping |

A v4 client keeps working everywhere: `meta.law` and `defined_by` are
additive, and `definition` is one more kind it can render generically.

## Migration sketch (v4 apps)

- First boot fingerprints the registry and creates the `definition` rows
  — revision 1, by the deploy actor, one transition per kind. Existing
  log rows get `defined_by = NULL`, read as "pre-law" (the replay check
  skips them; honesty about the horizon beats a fabricated anchor).
- `snapshot.json` is absorbed: `waymark5 migrate` reads it as the storage
  facet of revision 1; the file stops being separately maintained.
- No resource-table changes; one column on the transitions table; the
  engine tables version (`waymark5_*`).

## Explicit 5.0 punts

- **i18n** — still deferred; revision diffs add no new emission points.
- **CRDT/OT** — unchanged.
- **As-of rendering, editable definitions, federation protocol** — §7,
  deliberately.
- Not punted, on principle: the boot-time backfill invariant (§4) and the
  continuity map (§5) — each is a fragment that already exists for one
  field (`check_state_tokens`, `renames`) and the fragments are the
  evidence they compound.

---

## Appendix: before/after stories

### §1–§2 The definition is a resource; deploys are transitions

**Before (v4):** Colton tightens the theme-matching relation and deploys
over dinner. Dana's picker quietly offers fewer meals than it did at
lunch. Nothing anywhere records that the rules changed — not the log, not
the feed; the only witness is a git commit on a laptop.

**After (v5):** The boot writes `revise` on `definition:meal_plan` — the
family feed shows "the law changed: assign_meal accepts fewer
combinations," with the diff. When he rolls back an hour later, that's in
the feed too, named as a reversion.

### §3, §6 Every write is anchored; the constants are the law

**Before (v4):** An auditor asks why a reconciliation passed in March at
a difference of 0.00004. The March tolerance lives only in git history,
correlated to production by deploy-log timestamps someone screenshots.

**After (v5):** The March transition carries `defined_by`; the revision
it names holds `Tolerance("0.00005")`. Two reads, one join, no
archaeology.

### §4 Current truth follows the current law

**Before (v4):** Colton redefines `overdue` to respect a grace period.
The maintained column keeps its old values until each row happens to be
touched; `?overdue=true` returns a mixture of two laws and nobody knows.

**After (v5):** The deploy marks `overdue` stale by definition; boot
backfills before prep tasks serve (or declares the deferral and the
filter honestly un-advertises until caught up). There is no mixture to
observe — a served fact and the served law cannot disagree.

### §5 History keeps its own law

**Before (v4):** A rename of `approve` to `authorize` makes two years of
audit log read as invocations of an action that doesn't exist; the intern
writing the compliance report greps both spellings and hopes.

**After (v5):** The rename ships with `renames={"approve": "authorize"}`
on the revision — boot refuses without it — and the log reads
continuously: same lineage, one law change, machine-checkable end to end
by the replay conformance.
