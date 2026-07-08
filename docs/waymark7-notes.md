# waymark7 implementation notes

The package began as a byte-identical fork of `waymark6/` (modulo the
rename: `waymark7_*` storage and channels, `--waymark7` plugin, media
type v=7), implementing `docs/waymark7-design.md` — the revised "law
binds rows" design. Read the design first; its first draft was
reader-scoped with shadow columns and the user-story pass killed it —
do not reintroduce reader cohorts.

Built in three phases on one thesis: **the law binds the row, not the
reader.** A deploy is a workflow on the Definition resource
(`proposed → piloted → current`), and two revisions of a kind's law may
be live at once — assigned to rows by population (by filter, by birth),
never to principals (a preparer and reviewer under different laws
confirm nothing). One value per row survives untouched, because one law
per row.

## Map

| Area | Where | The one thing to know |
| --- | --- | --- |
| The definition machine (§1) | `waymark7/server/definitions.py` | `draft, proposed, piloted, current, grandfathered, superseded, withdrawn`; actions `promote` (proposed/piloted→current), `pilot`, `withdraw`, `measure`, `settle`, `supersede`, `grandfather`; `draft` declared, unwired (`allow_dead`) |
| Deploy modes (§1) | `Engine(..., deploy="auto"\|"propose")` | auto = the v6 single-breath revise, byte-identical; propose + data-law diff = held at `proposed` behind the served-law overlay; propose + code/shape diff = revise-to-current with the recorded `deploy_note` marker |
| The diff gate | `waymark7/core/fingerprint.py` (`classify_diff`) | `data_law` iff every changed path is `derived.<fact>.tolerance` or `derived.<fact>.over.<i>.(child\|related).where…`; everything else is `code_or_shape` — code does not interpret per-row (design §4) |
| The served-law overlay (§1) | `waymark7/server/derived.py` (`DerivedMaintainer.overlay`) | `(kind, fact) → LawOverride(tolerance, where)` built from the CURRENT revision's stored fingerprint while a hold or pilot keeps newer code resident |
| The row's law (§3) | `law_revision` column on every kind's table | An integer stamp (nullable — the upgrade ALTER is plain ADD COLUMN), set at create to the revision whose population claims the validated input, moved only by adopt/pilot/withdraw restamps and the Immediate bulk adopt |
| Row-law evaluation (§3/§4) | `DerivedMaintainer.specs_for(rdef, revision=…)` | THE per-row seam. Int resolution: per-revision `law_overlay` first (grandfathered laws, parameter-served pilots), then piloted-resident-verbatim, then the served law. `compute`/`materialize` default `revision` to the row's stamp, so every write path computes under the row's law with no caller changes |
| Populations (§3) | `definitions.Population`, `check_population`, `population_claims` | `{"where": {…}}` or `{"after": true}`, exactly one; `where` validated against the target kind's query grammar, promoted stored input fields only; `population_claims` is the Python half of the same grammar (Eq, In, `_gte`/`_lte`, Vocab membership) the SQL restamp runs |
| Adoption (§3) | `Resource.adoption = Immediate \| Never` | Fingerprinted (emitted only when `Never`). Immediate: the promote/revise backfill restamps every row — the bulk adopt, today's behavior spelled out. Never: rows keep their stamp; the old revision grandfathers |
| The adopt affordance (§3) | `Invoker._adopt_in_session`, `render._adopt_entry`, `ENGINE_ACTIONS` | Engine-injected on every kind (like `create`): a same-state transition that restamps to current and recomputes; advertised only when `row.law_revision < current` and the row is non-terminal; idempotent (an adopted row replays its document); terminal rows are refused — the finished keep their law |
| Supersede-when-empty (§1/§3) | `definitions.DefinitionLifecycle` (`supersede_prior`, `_sweep_empty_laws`), `storage.law_survivors` | `current → grandfathered` at a promote/revise with Never-kind survivors; `grandfathered → superseded` lazily on every entry-point adopt/terminal transition of the kind (system actor) when the survivor count hits zero |
| The lifecycle seam | `Ctx._lifecycle` / `Invoker.lifecycle` = `DefinitionLifecycle(engine)` | `.meter`, `.is_pending`, `.supersede_prior`, `.after_commit` (promote flip + backfill, pilot install + restamp, pilot-withdraw rollback, the lazy sweep) — definition-only plumbing, never app-visible; `after_commit` fires on entry-point invokes only, never nested `ctx.invoke` |
| Blast radius (§2) | `definitions.BlastRadiusMeter`, `measure` action | Per-fact deferred job comparing stored (current-law) values against a fresh compute under the resident proposed specs; report on `JobArtifact.detail`, job id on `data.measure_job` |
| Recorded inputs (§5) | `@action(..., record=Inputs())`, `Resource.record_inputs` | The validated input payload on the transition row (nullable `inputs` JSONB); `record=` without `input=` is a DefinitionError; the definition kind records unconditionally |
| meta.law / defined_by | `render._row_law`, `Invoker._law_for`, `ResourceDef.law_ids` | Both resolve the ROW's stamp through `law_ids` (revision number → definition row id, every stored revision) with the kind's current law as the shared fallback — the envelope and the audit anchor cannot disagree |
| Conformance | `waymark7/testing/conformance.py` (`replay_history`) | `adopt` joined `ENGINE_ACTIONS` (same-state check); mixed-`law_revision` histories walk because every transition already carried `defined_by` and is checked against ITS revision's machine |
| Phase 2 proofs | `tests/waymark7/test_populations.py`, `test_adoption.py`, `test_supersede_when_empty.py` | The §3 tolerance proof, creates routed by population, `after=True`, four-eyes on pilot, withdraw rollback, pilot reboot survival; Never/Immediate/adopt; the February story end to end |
| Dogfood | `ledger7/` + `tests/ledger7/`; `mealplan7/` + `tests/waymark7_dogfood/` | ledger7 = the four appendix stories (see Phase 3); mealplan7 = the v6 family-meal app forked forward (Priya's week), both enrolled in `--waymark7` conformance |

## The definition lifecycle (Phase 1, the contract)

- **`promote`** (proposed/piloted→current), guards `(proposal_is_resident,
  four_eyes(of="propose"))`. In-transaction it retires the prior current
  row as the deploy actor (supersede, or grandfather for a Never kind
  with survivors); post-commit (`after_commit`) it flips the served law,
  drops the overlay, installs grandfathered-law overlays, and runs the
  standard §4 backfill + `settle`.
- **`pilot`** (proposed→piloted), `input=Population`, `record=Inputs()`,
  same guard pair. Gate: data-law diffs only; refused while a
  grandfathered revision of the kind still has rows (two live revisions
  max — a stage, never a lattice). Post-commit the population installs
  and existing `where` matches restamp + recompute (paged like the §4
  backfill, run inline in the pilot request).
- **`withdraw`** ({proposed, piloted}→withdrawn), reason recorded. A
  withdrawn PILOT's rows restamp back to current and recompute
  post-commit; a withdrawn proposal changes nothing and the overlay
  stays (redeploying the current law's code clears it naturally).
- **`measure`** (proposed→proposed): the §2 blast-radius job.
- **`grandfather`** (current→grandfathered) and **`supersede`**
  ({current, grandfathered}→superseded): deploy-actor bookkeeping.

### Boot re-detection (`_revise_kind`)

Per kind, ordered: stamp NULL `law_revision`s to the STORED current
revision (the law those rows were living under — before anything decides
by stamps); build `law_ids` from every stored revision and `law_overlay`
entries from every grandfathered row's fingerprint; then

- resident hash == current → nothing minted; a lingering proposal
  withdraws; a lingering PILOT keeps living from its stored parameters
  (`law_overlay`), promote honestly unavailable until its code returns.
- resident hash == a piloted revision → the pilot continues (any deploy
  mode: the deploy already happened; this boot is its restart) — the
  propose-hold shape with a population living under the resident code.
- a piloted revision exists and resident matches neither → **boot
  refuses** (RuntimeError): a new deploy would strand the pilot's rows;
  promote or withdraw first.
- otherwise: the Phase 1 propose/auto flow, with the auto branch's
  retirement choosing grandfather for Never kinds with survivors, and a
  first-law boot stamping pre-existing rows to revision 1.

## Deviations from the design text (deliberate, tested)

Phase 1's (carried): auto-mode deploys stay the v6 single-breath revise
(one `revise` + `supersede`, one transaction — not the design's
"propose+promote in one breath"); advertisement-only diffs classify
`code_or_shape` and auto-promote (render reads resident objects — a hold
would serve new prose under an old law id); recorded inputs are exposed
to full-visibility principals only (subscriptions are principal-less);
`__registry__` and surface targets always revise in auto semantics; a
promote's backfill runs inside the promote request (a declared
`Deferred` is honored at boot only).

Phase 2's:

1. **`grandfathered` is a state, not a lingering `current`.** The design
   implied the old revision "is not history; it is law" — but two
   `current` rows per kind would be a lie. A distinct state names the
   in-between; the anticipated deviation, taken.
2. **`meta.law` keeps id semantics.** The row's stamp resolves to its
   definition revision ROW ID (via `rdef.law_ids`), not the fingerprint
   hash — `meta.law`, `defined_by`, and `engine.current_law()` stay one
   vocabulary, and the Phase 1 baseline asserts the id. The revision
   NUMBER rides beside it as `meta.law_revision` (the row's stamp).
3. **`adopt` is an engine action, not a machine action.** It joins
   `ENGINE_ACTIONS` (the `create`/`observe_authored` precedent): a
   same-state transition no ActionDef could express (`to=` names one
   state). Advertised full-visibility only; no domain guards in 7.0 (the
   design's "the domain says when a row may adopt" is future work); a
   machine that declares its own `adopt` shadows the engine's;
   single-row only — the bulk adopt IS the promote/revise backfill.
4. **The bulk adopt records no per-row transitions.** An Immediate
   kind's backfill restamps silently (the §4 discipline: the revise or
   promote is the one loud event; per-row adopt rows would be noise).
   The explicit `adopt` action records; the bulk path does not.
5. **Populations claim by promoted stored input fields only.** `state`
   and derived facts are refused at pilot time — a population over
   either would select rows by the very machine/law being piloted. This
   also keeps the SQL restamp filter and the create-time Python claim
   (`population_claims`) one grammar.
6. **Grandfathered laws under a code/shape diff evaluate resident code
   with their revision's stored data-law parameters.** The overlay can
   recover `Tolerance`/`where=` from a fingerprint; it cannot recover an
   fn body (stored only as a hash — the §4 boundary). Grandfathering a
   code diff therefore serves old parameters over new code. The dogfood
   stories are tolerance/filter changes, where the overlay is exact.
7. **The lazy supersede check triggers on entry-point transitions
   only.** A cascade or nested `ctx.invoke` that closes the last
   survivor waits for the next entry-point adopt/terminal transition of
   the kind (or the next boot's sweep-by-detection). Lazy is the
   declaration; the February story's direct close fires it on the spot.
8. **A live pilot pins the deployable code.** Boot refuses a deploy that
   matches neither the pilot nor the current law (see above) — recorded
   as the honest reading of "a stage, never a lattice" at the process
   boundary.
9. **Writes anchor to the row's law.** `defined_by` on every transition
   resolves through the row's stamp (`_law_for`), falling back to the
   kind's current law; an `adopt` anchors to the law the row moved TO.

## Phase 3 — the ledger7 dogfood

Fork of `ledger6/` (imports waymark6→waymark7), with the four
appendix stories as tests (`tests/ledger7/`):

- **§1–§2**: a `Tolerance` change to `account.reconciled` deploys as
  `proposed`; `measure` reports the per-fact flip count over live
  accounts as a job artifact; the proposer cannot promote (four-eyes);
  Elena promotes and the accounts recompute. (The story LOOSENS the
  tolerance, `1e-5` → `0.02` — the shipped default is already the
  tightest sensible value, so a tightening story would have no flip to
  measure.)
- **§3**: the tolerance pilots for `Population(where={"fund":
  "fund-alpha"})`. Accounts carry a denormalized `fund` (copied by the
  workbook's registry Seed — populations filter a kind's OWN promoted
  fields), so the account population can filter it. Marcus and Elena
  both see fund-alpha accounts under the new tolerance, fund-beta under
  the old.
- **§3 grandfathering**: `Workbook.adoption = Never` — an in-flight
  close finishes under its birth law; the old workbook revision is
  `grandfathered` and supersedes when its last workbook reaches
  terminal.
- **§5**: `reject` declares `record=Inputs()` and the `reject_notes`
  field is DELETED (friction #8 dies) — the reviewer's words live on
  the transition row, next to the `defined_by` stamp naming the law
  that demanded them.
- `build_account(tolerance=…)` / `build_workbook(registry_states=…)`
  factories replace the bare classes so tests can deploy revised laws
  (two calls with equal arguments yield byte-identical fingerprints —
  the lambdas hash by source text).
- Makefile: `ledger7` target (the `ledger5-pg` container on
  :15433, `ledger7_dev`, port 8012).

## Mirror-aware conformance (7.x extension)

An **extension, not waymark8**: no runtime law, wire format, or envelope
changes — only the conformance walker's reach. The Mirror runtime
(pull-through-on-read, system-only sync transitions) already shipped and
is correct; the harness just could not verify a resource shape the
runtime already sanctions. Lives in `waymark7/testing/` plus one inert,
default-off seam line in `waymark7/server/external.py`.

**The two walker assumptions a Mirror breaks — both at once.** The
generic walker's loop is "reach state S, GET it, assert the envelope
shows S." A pure `Mirror` (`waymark7/server/external.py`) breaks:

1. **Reads are pure.** A Mirror does pull-through-on-read: a GET past the
   TTL (or in a non-fresh state) fires `refresh_mirror`, calls the
   adapter's `pull`, and applies an `observe_external` / `mark_unreachable`
   **system transition**. So a walker that stages `unreachable` and GETs
   to check it heals the mirror to `fresh` on that very read — observing
   changed the observed.
2. **State comes from principal-invoked transitions.** The sync states
   (`fresh/stale/conflicted/unreachable`) are entered only through
   `system_only`-guarded transitions (`observe_external`, `mark_stale`,
   `mark_unreachable`, `mark_conflicted`), invokable only by
   `SYSTEM_OBSERVER` and advertised to no walker principal — so the walker
   can neither drive them nor reach them any declared way. (`reconcile` is
   the exception: a *human* transition, conflicted → fresh.)

`job`/`attachment` have some system transitions but a principal-driven
primary lifecycle and pure reads, so the walker's existing hidden-action
skip handles them. A pure Mirror is almost entirely system/external
lifecycle, so that skip is not enough.

**Read purity — option (b), the refresh-suppression seam (chosen).** A
walker-scoped, **default-OFF** switch suppresses pull-through for the
duration of a conformance read, so the walker observes the *stored* state
deterministically in every sync state (not only `fresh`, which option (a)
— a fresh-only factory — would have limited us to). The only runtime-file
change is one inert early-return in `refresh_mirror`, gated on
`getattr(engine, "_suppress_mirror_refresh", False)`, which the
conformance engine fixture (`testing/pytest_plugin.py`, `wm7`) sets.
Production behaviour is unchanged; `test_suppression_seam_is_default_off`
asserts a plain engine still pulls through on read. No wall-clock TTL
crossing is relied on — the suppressed read is deterministic by
construction.

**Staging — the system-actor factory.** `testing/factories.make_state`
detects Mirror kinds (`issubclass(cls, Mirror)`) and, unless the app
registers its own `@state_factory`, routes them to `make_mirror_state`,
which the harness provides automatically: it mints the mirror (external
identity only, as a `Discover` sweep does), pulls once through the adapter
*directly* (not the read path, so it is independent of the suppression
seam) and records it via `observe_external` — landing `fresh` with the
authority's real fields so the summary orients by a name — then drives to
the requested state with `SYSTEM_OBSERVER` and the declared sync
transitions. A read-only mirror (`push_on_write=False`, like ledger7's
`fund`) `SkipState`s `conflicted`: nothing local is ever pushed, so the
external etag cannot diverge under our write — the runtime can never
produce that state, and the factory does not fake it.

**Two harness gaps the Mirror shape exposed** (both fixed in
`testing/conformance.py`; both also needed for the fund's full
enrollment):

- `replay_history` treated `mark_stale`/`mark_conflicted`/`mark_unreachable`
  as `ENGINE_ACTIONS` (same-state authored-sync bookkeeping) — but on a
  whole-resource Mirror those names are **real state-changing
  transitions** (`fresh → stale`). The bookkeeping branch now yields to
  the declared-transition branch when the name is in the revision's
  `machine["actions"]`.
- `test_affordance_completeness` demanded every hidden action 404 on
  invoke. An input-less idempotent system mark (`mark_stale`) re-invoked
  in its own target state instead **naturally replays** (200, no version
  advance) before visibility is evaluated — the replay contract, not a
  silent fall-through. The check now accepts that one shape and asserts it
  did not advance.

The system-only transitions need no other exemption: they are hidden for
every walker principal, so `test_transition_truth_available/unavailable`
and `test_input_contract` find no principal advertising them and skip —
the existing precedent, reused.

**This closes the seam the ledger7 Fund mirror recorded** (the first
real Mirror in a dogfood, enrolled minimally as `fresh`-only). A proof
lives in `tests/waymark7/test_mirror_conformance.py`: a minimal read/write
`NoteMirror` + scriptable adapter, walked through representation,
affordance, orientation, collection, replay, the sync-transition matrix,
and `reconcile` — plus the default-off assertion.

## Nav prominence (7.x extension)

Another **extension, not waymark8**: no runtime law or envelope change,
only a new *advertisement* declaration. A sufficiently helpful domain
grows past a handful of kinds, and a flat nav bar becomes a wall. The
fix follows the `engine_kinds` precedent exactly — the law declares, the
client renders the tiers it is told about, and **never guesses relevance
of its own** (the one move the generic client is built to refuse).

- **Declaration**: `Resource.nav = "primary" | "secondary"` (default
  `"primary"`). `secondary` folds a real domain kind behind the nav
  overflow — the sibling of the engine fold (machinery hides by
  ownership, the secondary domain by declared prominence). Validated in
  `__init_subclass__`.
- **Fingerprinted as advertisement**, emitted only when `secondary` (the
  `adoption`/`created_as` emission discipline — adding the mechanism
  re-hashes no kind that never touched it). Revising prominence is a law
  change, but a cosmetic one: `classify_path("nav") == "advertisement"`,
  so it marks no fact stale and recomputes nothing.
- **Discovery**: `.well-known/waymark` gains `secondary_kinds`
  (`registry.secondary_kinds()`, parallel to `engine_kinds`).
- **Client**: `renderNav()` seats primary domain kinds inline; secondary
  domain kinds *and* engine kinds fold into one `⋯` overflow
  (`overflowMenu`), secondary domain first, then the engine machinery
  below a labeled rule. A kind marked both stays with the machinery
  (ownership is the stronger fold). A pure-engine overflow reads exactly
  as before. The home dashboard is deliberately unchanged — it charts
  every domain kind with counts; prominence is a *navigation* concern.
- Tests: `test_discovery_marks_secondary_kinds` (parallel to the
  engine_kinds test) and `test_nav_secondary_is_an_advertisement_revision`
  in `tests/waymark7/test_definition.py`, plus a parametrized `make_note`
  fixture.

## Attachment upload affordance (7.x extension)

Same shape as nav prominence — **no runtime law or envelope change, one
new discovery template plus client rendering.** The generic client had no
file picker and never PUT the bytes, so "file a statement against this
account" was a five-field metadata form (`resource_kind`, `resource_id`,
`name`, `mime`, `notes`) that reserved a row against an empty blob.

- **Discovery**: `.well-known/waymark` gains `attachment_bytes`
  (`{base}/attachments/{id}/bytes`), emitted only when the engine
  registered the attachment kind. It is the one URL the client can't
  derive from a collection envelope; the create action's href it already
  has.
- **Client**: on `attachment_collection`, the create button becomes
  `uploadButton`→`uploadDialog`. A file picker derives `name`+`mime` from
  the chosen `File`; the target (`resource_kind`/`resource_id`) comes from
  the collection filter (locked, shown as a `resourceRef`) or, from the
  raw collection, two inputs. Submit = reserve via the create action
  (reusing `invoke()`), then PUT the bytes to the advertised route. A
  failed PUT keeps the reserved id so a retry re-sends into the same row
  (bytes write once) rather than minting an orphan. **Falls back to the
  generic `actionDialog` when `attachment_bytes` is absent** — the client
  drives no route it wasn't handed.
- Attachments are treated as a platform mechanism (like `definition`/
  surfaces), so keying the affordance on the engine kind is consistent
  with the client's existing first-class renderers — it still invents no
  app affordance and no URL.
- **Getting bytes back** (the GET half of the same route): View and
  Download on the attachment detail page, and a per-row `↓ Download` in
  the statements table (`itemTable` gained an optional `rowAction`). Both
  fetch WITH the principal headers and hand off a blob — a bare `<a href>`
  would hit the act-gated bytes route unauthenticated (the dev client
  carries identity in `X-Principal-*`). View (new tab) shows only for
  browser-renderable mimes (`image/*`, `text/*`, `application/pdf`); both
  appear only while the row is `uploaded`.
- Test: `test_discovery_advertises_the_bytes_route` in
  `tests/waymark7/test_attachments.py` asserts the template matches the
  live route.

## Gotchas carried forward (learned across v5–v7)

- **Postgres port**: tests use the docker container on **5433**; a
  native Postgres + SSH tunnel shadow 5432; ledger dev servers use
  the dedicated `ledger5-pg` container on **15433**. Never point a
  dev server at 5433. Never run two xdist pytest runs concurrently.
- **State factories that defer jobs must await them** before returning
  (`_cr_job_done` pattern); **conformance state factories mint a fresh
  fund/workbook per call** — never cache an id.
- **`link(edge=...)` not `rel=`**; **surface members name link rels,
  not edges** (v6 deviations, unchanged).
- **The clock sweep still does not chain cross-kind** (recorded since
  v6; no dogfood shape hits it).
- **The backfill/restamp paths do not recompute cross-kind dependents**
  (the boot backfill never did): a fact another kind derives over a
  restamped fact catches up at that row's next write. No dogfood shape
  hits it; recorded.
- **`pytest --waymark7` (the conformance walk) is wired** — the root
  conftest's `waymark7_engine` fixture spans both v7 dogfoods (mealplan7
  + ledger7), a 1:1 mirror of the v6 section, with the two factory
  disciplines above (fresh fund per call; await the deferred refresh
  job). The sweep is 5,580 parametrized items over ~19 kinds
  (properties × kind × state × action); a full local run is 2,772
  passed / 2,808 skipped / 0 failing, ~37 min single-machine (it is
  DB-bound: every test rebuilds the whole schema in a fresh function-
  scoped engine, so wall-clock is Postgres DDL, not CPU).

## Status

| Phase | What | State |
| --- | --- | --- |
| 0 | Fork `waymark6/` → `waymark7/` | done — byte-identical modulo rename |
| 1 | Definition lifecycle (§1, §2, §5) | done — 342 tests green |
| 2 | Law binds rows (§3, §4 row halves) | done — 356 tests green (`test_populations`, `test_adoption`, `test_supersede_when_empty`); waymark6 + ledger6 untouched (364 green) |
| 3 | ledger7 dogfood (the four appendix stories) | done — 36 tests green (32 forked + 4 stories); ledger6 untouched |
| 4 | mealplan7 dogfood + `--waymark7` conformance wiring | done — `waymark7_engine` fixture over mealplan7 + ledger7; conformance 2,772 passed / 2,808 skipped / 0 failing |
