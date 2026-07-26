# Spec — the law sweep: judge the corpus before the law ships

**Thesis.** `:propose` holds a law diff at `proposed` and asks a human to
promote it. Nothing tells that human what promoting would *do*. The sweep
answers one question — **"which live rows does this law change, and how?"** —
before the promote, as a resource.

## Epistemic status

This is the only spec in this set that describes a capability no other engine
can offer, and it is not ambition: it falls out of a design choice waymark
already paid for. Because the law is data (`definitions.clj`), and because
`judgment.clj` can evaluate a row against *any* stored revision, both sides of
the comparison are already addressable in one process. Everywhere else, "what
will this rule change break?" is answered by deploying and watching.

## What exists

- `server/definitions.clj` — one row per revision per kind; data is the
  canonical fingerprint. `boot-revise!` fingerprints every resident kind and
  compares to the stored current row. `:propose` mode holds the diff at
  `proposed` while the boot keeps serving the **current** law by installing its
  stored fingerprint in the rdef's `:judgment-laws` slot.
- `server/judgment.clj` — `{revision → stored fingerprint}`; a row whose stamp
  has an entry is judged from that revision's stored guard trees. Substitution
  is positional *and* name-checked.
- `server/render.clj` — the availability probe already computes, per row, which
  actions are available and which refuse with what reason.
- `server/invoke.clj` — `:dry-run` (and `:dry-run :partial`) judges without
  committing.

So: resident code is the proposed law, the store holds the current law, the
probe evaluates either, and nothing commits. The sweep is a loop, not an
invention.

## The gap, precisely

"Invalidate" is four different questions, and the spec must keep them apart:

1. **Schema drift** — stored `data` that no longer validates against the
   proposed schema (a widened `:min`, a removed enum arm, a field turned
   required).
2. **Availability drift** — a row for which an action was available under
   revision N and refuses under N+1, or the reverse. This is the interesting
   one: it is a *guard* change, invisible to any schema diff.
3. **State drift** — rows sitting in a state the proposed machine no longer
   declares. `store/migrate` already plans state renames, but destructively and
   without naming the rows.
4. **Derivation drift** — a `:derived` fact whose expression changed, so the
   stored value and the recomputed value disagree.

Today (2) and (4) are entirely invisible until promote.

## The design

A `:law_sweep` kind, enrolled only on engines running `:propose` mode — the
worksheet's own "no app declares it, no kind appears" rule.

```
requested ──run──▶ running ──record_report──▶ reported
                                (system)          │
                                                  └──promote──▶ (definitions)
```

`POST /api/law_sweeps {:kind :chore :revision 12}` stages a request; the
post-commit pass walks the target kind's rows in `page[size]` batches and, per
row, asks four questions above. The report lands through the system door, so
the 201 already carries it — the worksheet's discipline verbatim
(`server/worksheet.clj`, the plan computed by `after-write!`).

Report shape, per finding:

```json
{"kind": "chore", "id": "…", "summary": "Dishes · Active",
 "class": "availability",
 "detail": {"action": "queue",
            "under_current": "available",
            "under_proposed": "refused",
            "because": "No active role named manager"}}
```

`:class` is one of `schema` | `availability` | `state` | `derivation`, and the
report totals by class. `because` is the guard's own `:explain`, already
rendered — the sweep invents no prose.

**Scoping.** The sweep takes the collection query grammar verbatim
(`server/collections.clj`), so "sweep only the active chores" is a filter, not
a flag. Reuse here is not cosmetic: it means the sweep can be narrowed the same
way every other read is, including by the ref filters.

**Cost.** One row load and two probes per row. At the household's scale this is
milliseconds; at paydesk scale it is a job (`server/jobs.clj` already gives
deferred execution with leases and progress). Recommend: synchronous under
`export-cap`-ish thresholds, a job past it, matching the worksheet's own cap
posture.

## Recorded punts

- **Guards that read storage.** A guard declaring `:reads [:storage]` probes
  against *today's* rows, so a sweep result is a snapshot, not a proof. Say so
  in the report header rather than pretending to totality.
- **Composite guards.** `{:any [...]}` advertises nothing (`guards.clj`), so an
  availability finding under an `:any` can name the composite but not the arm.
  Acceptable: the finding still names the row and the action.
- **The law of the law.** `:definition` is never fingerprinted; the sweep
  inherits that exclusion without comment.
- The sweep does **not** promote. Promotion stays a human act on the definition
  row; the sweep is evidence, not a gate. A future `:require-sweep` deploy mode
  could make it one.

## Effort

**Medium.** The probes and the report resource are small; the honest work is
the four finding classes and their prose. No new storage, no new dependency.
