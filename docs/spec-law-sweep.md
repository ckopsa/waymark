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

## Amendments (2026-08-23, waymark-442.1)

The spec above stands. Four corrections and one reuse, recorded against the
tree rather than folded in, so the original reading survives beside them.

**Enrollment is not a deploy mode.** "A `:law_sweep` kind, enrolled only on
engines running `:propose` mode" names a verb `waymark10.modules` does not have
and must not grow: enrollment is `:always` | `:when-declared` | `:app-opt-in`,
and it is what `check` and the conformance driver select on. Making it depend
on a runtime environment variable is the failure `modules.clj`'s no-discovery
paragraph exists to forbid — `boot-revise!` would write a different law in a
REPL than in a container. So: a `:law-sweep` **module**, whose kind enrols
`:always` within it, and propose-only becomes a `deploy-only`-shaped guard on
the create door (`definitions.clj:115` is the precedent). An engine that does
not want the kind does not assemble the module.

**The two probes are already in hand, and the naive reading is backwards.**
Under a propose hold the boot installs the **current** revision's stored
fingerprint into `:judgment-laws` while the **resident** code is the proposed
law (`judgment.clj:12-17`). So "under current" is
`judgment/resolve-action rdef defn' current-revision` — the overlay — and
"under proposed" is `defn'` verbatim. The sweep needs no new judgment mechanism
at all; it needs a caller that asks for a revision other than the row's own
stamp.

**Derivation drift is already built.** Finding class (4) is the definition
kind's `:measure` / `:measure_pilot` lifecycle: it evaluates *both* laws'
specs over current data and reports `{:facts [{:fact … :flips n :of total
:sample […]}] :population … :from_revision :to_revision}` onto `data.measure`
(`definitions.clj:288-303`, `derived/specs-under`,
`waymark10-design.md:2139-2162`). The sweep calls it and projects the result as
class `derivation`; it does not grow a second recomputation.

**Availability drift is the design doc's own named punt.** *"Judgment blast
radius (newly-refused rows) unmeasured"* (`waymark10-design.md:2161`, restated
`:2341`). Class (2) is the one finding nothing in the tree answers, which is
the sweep's whole justification and worth naming as the punt it closes.

**Reuse: one evaluator, two callers.** Once
[law scenarios](spec-law-scenarios.md) land, the per-row probe is
`waymark10.scenario`'s judge with the revision as a parameter, and a sweep
finding's `because` is a scenario's refusal rendered by the same code path. Two
probe loops over `g/evaluate` would be one too many.

**Sequencing.** Build order is unchanged — the sweep first — but see
[time travel](spec-time-travel.md)'s amendment: the two share less than
`waymark10-next.md` promised, and the sweep landing does not make tier 2 free.

## Built (2026-08-23, waymark-442.3)

It landed on the amendments, not on the original, and the three places the
two disagreed are the three decisions worth reading.

`waymark10/src/waymark10/server/law_sweep.clj` is the whole computation:
the door's checks, the four finding classes, the notes, and one `report`
function. `waymark10/src/waymark10/server/routes/law_sweep.clj` mounts its
one address. `waymark10.modules` grew the `:law-sweep` entry — routes and a
pack, no kinds, no hooks — and `waymark10.test.packs` grew `packs/law-sweep`.
`waymark10.scenario/verdict` went public, which was the whole of the
mechanism-sharing: **one evaluator, two callers**, and a sweep finding's
`because` is a scenario's refusal rendered by that very line.

### The three departures

**No `:law_sweep` kind.** The spec drew `requested → running → reported` with a
post-commit pass, and the module table has no column for a post-commit pass:
the engine's `:maintain` and `:lifecycle` slots are core literals, and
`waymark10.modules`' closed table says out loud that a fifth kind of
contribution is a proposal to change core, reviewed as one. A sweep commits
nothing, so the three states collapse into one answered document — the request
*is* the GET, and `reported` is the only state anyone was ever going to read it
in. What the kind would have bought (a durable record on the proposal) the
`:measure` lifecycle already buys for the class that needs it.

**A GET, not a `POST /api/law_sweeps`.** `GET /api/definitions/{id}/sweep`,
in the `:static` bucket beside `/api/attachments/{id}/bytes`. The report is
idempotent, cacheable and linkable — a proposer pastes the URL into the
conversation about the promote — and the collection query grammar it scopes by
already lives in a query string, so *"sweep only the active chores"* is
`?state=active`, verbatim, 422s and all. Pagination is ignored, exactly as the
worksheet export ignores it: a sweep is the whole subset, up to `sweep-cap`.

**Propose-only is the door's state, not an enrollment verb.** The amendment's
correction, taken: `swept-from` is `#{:proposed :piloted}` and anything else
answers the ordinary `wrong-state` 409 naming the two states it is available
in. An engine that does not want the surface does not assemble the module.

### What availability drift actually cost

Almost nothing, which is the amendment's point made concrete. Under a hold the
boot has already installed the current revision's stored fingerprint in
`:judgment-laws` while the resident code IS the proposal, so per row per
action:

```clojure
under-current  (judgment/resolve-action rdef a (:law-revision row))
under-proposed a
```

— the overlay and the declaration, both in hand, no loader. Both go to
`scenario/verdict` with **one ctx**, and that identity is the method: hold the
row, the input and the world fixed and a difference in verdict is attributable
to the law and to nothing else. The ctx is `:probe` with a nil input (render's
own posture, so a guard that grades input pends instead of refusing and the
sweep never reports a drift nobody has typed yet) and it carries live hooks —
`inv/render-hooks` plus an `:actor-of`, because a guard barring whoever created
the row is exactly the kind of law a proposal moves.

Two cheap exits keep it honest and fast: an action `resolve-action` returns
untouched has no comparison to make, and a row that would not *adopt* on
promote cannot drift at all (`install-current!` restamps only the prior current
revision's rows, and only for `:adoption :immediate`).

### The other three classes

- **derivation** is a CALL. `maintainer/blast-radius` is the `:measure`
  lifecycle's own meter; the sweep runs it over `fp/stale-facts` of the
  proposal's diff and projects `{:fact :flips :of :sample}` as findings of
  class `derivation`. Their grain is the FACT, not the row, and the finding
  wears that — no `id`, a sample instead. No second recomputation was grown,
  and when the diff stales no declared fact the meter is not called at all.
- **schema** is `schema/errors` — malli's own humanized explain, never
  re-worded.
- **state** is a declaration read, not a `migrate` call: `store/migrate` reaches
  `pg/distinct-states`, and the sweep holds the row already. So the in-memory
  twin answers it too, and the finding does what the planner cannot — it names
  the rows, and the `:renames` clause that says where each is going.

**And a discovery worth recording.** A held diff is `data_law` by construction
(`fp/classify-diff`: a schema or state-machine change classifies
`code_or_shape` and auto-promotes, hold or no hold). So under a hold, classes 1
and 3 *cannot be consequences of promoting* — they can only be pre-existing
drift, stored rows the law already does not admit. They are still scanned,
because this is the only place anyone asks, and the report's notes say which
they are rather than letting a proposer read them as blast radius. The four
classes really are two and a half, and now the document says so.

### What a proposer sees

A chore kind whose queue guard reads *"under 30 minutes"*, three live rows, and
a proposal that moves the number to 60:

```json
{"waymark": "10", "kind": "law_sweep", "state": "reported",
 "self": "/api/definitions/e3cf964e…/sweep",
 "summary": "Law sweep · chore · revision 1 → 2 · 3 findings over 3 rows",
 "data": {
   "target_kind": "chore", "from_revision": 1, "to_revision": 2,
   "diff_class": "data_law", "adoption": "immediate",
   "scanned": 3, "of": 3, "truncated": false, "filters": {},
   "totals": {"schema": 0, "availability": 2, "state": 0, "derivation": 1},
   "notes": [
     "Probed as elena — a guard that reads the principal answers for them alone.",
     "The held diff is data-law: the schema and the state machine are identical under both laws, so any schema or state finding below is pre-existing drift, not a consequence of promoting."],
   "findings": [
     {"kind": "chore", "id": "458d…", "summary": "Garage · Idle",
      "class": "availability",
      "detail": {"action": "queue",
                 "under_current": "refused", "under_proposed": "available",
                 "guard": "short-enough-to-queue",
                 "because": "A chore over 30 minutes needs a grown-up to queue it."}},
     {"kind": "chore", "id": "24f9…", "summary": "Laundry · Idle",
      "class": "availability",
      "detail": {"action": "queue",
                 "under_current": "refused", "under_proposed": "refused",
                 "guard": "short-enough-to-queue",
                 "because": "A chore over 60 minutes needs a grown-up to queue it.",
                 "remedies": ["approval_request.create"]}},
     {"kind": "chore", "class": "derivation",
      "summary": "chore.big · 1 of 3 rows would change value",
      "detail": {"fact": "chore.big", "flips": 1, "of": 3, "sample": ["458d…"]}}]}}
```

Three things to notice, because each is a decision. **Dishes is absent** — it
queued under both laws, and a sweep that listed it would be a diff of the
corpus rather than of the law. **Laundry is present and still refuses**: the
wall did not move but its *sentence* did, and a household that reads its
refusals is entitled to know the words changed. And **the derivation finding
has no `id`** — it is the meter's answer, and the meter counts facts.

### The notes, which are the spec's punts said out loud

Every recorded punt became a sentence in `data.notes`, met before the findings:
the principal it probed as (a guard reading `:principal` answers for them
alone); *a snapshot, not a proof* when any guard on the kind declares reads
beyond the offline set; `{:any …}` naming the composite and not the arm; a
kind declaring `:adoption :never` explaining why its page is empty; and the cap.

### Two things grown, and why

- **A residency check.** `definitions.clj` records the unported `_resident_only`
  guard as a named punt. Harmless there, fatal here: if the resident code no
  longer expresses the proposal, *"under proposed"* would be a third law nobody
  proposed, and the report would be confident nonsense. The door refuses with
  that sentence.
- **A concealment refusal, not a projection.** A grant-scoped caller is refused
  at the door (404, the worksheet-upload posture) rather than having the sweep
  narrowed to their grant: a sweep names the ids and summaries of every row of a
  kind, and narrowing that would be a second visibility surface with its own
  bugs, on a door whose subject is a law nobody scoped has business promoting.
  Anonymous gets the same 404 `/api/-/seasons` gives. **This is the clause
  `spec-time-travel` will have to solve properly** — an as-of read must project
  through the same grant visibility — and this door sidesteps it by refusing.

### How it is proved

`waymark10.law-sweep-test` boots twice over one **in-memory** storage, because
a hold is not a state you can stage — it is two codebases against one database,
and the second boot's resident code IS the proposal. The twin is not a
convenience: the sweep reads rows through the storage protocol and nothing
else, and the test is the proof that the surface is portable. It pins the
flipped row, the old law's own explain as the `because`, the delegated
derivation report, the empty schema/state pages with their note, the
collection-grammar filter, the 409/404/anonymous doors and the residency
refusal.

`packs/law-sweep` pays the half a conformance driver *can* pay — a driver has
one classpath, so it can never stage a hold. It walks every definition row this
engine holds: a row that is not a proposal must answer 409, a row that is one
must answer a well-formed report (totals adding up, classes in the closed four,
an availability finding naming an action the kind actually declares, notes
present), and a definition that does not exist is a 404. Every application
suite runs in `:promote` mode, so what they exercise is the refusal branch —
which is the branch a proposer hits by mistake.

### Punts kept

The spec's four survive contact — storage-reading guards make a snapshot,
`{:any …}` names the composite, `:definition` is never fingerprinted and the
sweep inherits the exclusion, and the sweep still does not promote. Two more:

- **The job past the cap.** `sweep-cap` is 500 and truncation is *announced*
  (`scanned`, `of`, `truncated`, and a note) rather than deferred to
  `server/jobs`. Announced truncation beats implied totality; the job stays the
  spec's recommendation and nobody's code.
- **A warning that changed is not a finding.** `verdict` collects `:warning`
  denies without refusing, so a proposal that edits only a warning's explain
  sweeps clean. Availability is availability; the acknowledge protocol owns
  warnings.

- **The proposal does not advertise its sweep.** A definition envelope carries
  no `links.sweep`, so the door is discovered from this spec and not from the
  law. Adding one would be `definitions.clj` — a CORE kind — linking into a
  module's route, which is the reach-in `waymark10.server.seams` exists to
  refuse doing by name. (`:definition` is never fingerprinted, so the link
  would cost no revision; the coupling is the objection, not the hash.) The
  honest fix is a seam that lets an assembled module contribute a link to a
  core kind, and that is a modularization bead, not this one.

And one inherited, found while building: **the create door is outside the law
lifecycle entirely.** `fingerprint-of` records `create.defaults` and no create
GUARDS at all, so editing one does not move the hash, mint a revision or
trigger a hold — there is no proposal for a sweep to be asked about. That is a
gap in the fingerprint's whitelist, not in the sweep, and it is worth its own
bead: a widened create gate ships today with no record and no evidence.
