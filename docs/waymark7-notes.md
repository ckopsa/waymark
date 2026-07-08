# waymark7 implementation notes (handoff — IN PROGRESS)

This is a **mid-implementation handoff**, not a completed notes doc. The
package is a fork of `waymark6` implementing `docs/waymark7-design.md`
(the revised "law binds rows" design). Phases 0 and 1 are done, committed,
and green; Phases 2 and 3 remain. Read the design first, then this, then
the Phase 1 API surface (which is the contract Phase 2 builds on).

## Status at handoff

| Phase | What | State |
| --- | --- | --- |
| 0 | Fork `waymark6/` → `waymark7/` | **done** — byte-identical modulo rename; `waymark7_*` storage; `--waymark7` plugin |
| 1 | Definition lifecycle (§1, §2, §5) | **done** — 342 tests green |
| 2 | Law binds rows (§3, §4 row halves) | **not started** — died on a session limit before writing anything; brief below is complete |
| 3 | ledger7 dogfood (the four appendix stories) | not started |
| — | `waymark7-notes.md` finalize, full cross-version gate, final push | pending |

Committed checkpoint: `0aaf9e0` ("Implement Waymark 7.0 (WIP)"). Working
tree at handoff is clean at that commit. Everything since the v6 waves
is on `origin/main`.

**Verify the baseline before continuing** (Postgres on port **5433**, not
5432 — a native Postgres + SSH tunnel shadow 5432 on this machine):
```
WAYMARK_TEST_DSN="postgresql+asyncpg://ckopsa@localhost:5433/waymark_test" \
  uv run pytest tests/waymark7 -n auto -q      # expect 342 passed
```

## The thesis (design, one line)

The law binds the **row**, not the reader. A deploy becomes a workflow on
the Definition resource (`proposed → piloted → current`), and two
revisions of a kind's law may be live at once — assigned to rows by
population (by period, by birth, by declared filter), never to
principals (that would break multi-participant controls like four-eyes:
a preparer and reviewer under different laws confirm nothing). One value
per row survives untouched, because one law per row. The design was
revised once — the first draft was reader-scoped with shadow columns;
the user-story pass killed it. Do not reintroduce reader cohorts.

## Phase 1 — what is built (THE CONTRACT for Phase 2)

The Definition resource's machine now has
`draft, proposed, piloted, current, superseded, withdrawn`
(`initial=current`; terminal `{superseded, withdrawn}`;
`allow_dead={draft, proposed, piloted, withdrawn}`). `piloted` and `draft`
are declared but **not yet wired** — that is Phase 2. Actions:

- **`promote`** (proposed→current), guards `(proposal_is_resident,
  four_eyes(of="propose"))`. In-transaction it supersedes the prior
  current row as the deploy actor; post-commit (via the lifecycle seam's
  `after_commit` hook, which only fires on entry-point invokes, NOT
  nested `ctx.invoke`) it flips the served law, drops the overlay, and
  runs the standard §4 backfill + `settle`.
- **`withdraw`** ({proposed, piloted}→withdrawn), `input=WithdrawInput`,
  `record=Inputs()`.
- **`measure`** (proposed→proposed), the §2 blast-radius job.

### Deploy modes
`Engine(..., deploy="auto" | "propose")`, default `"auto"`.
- **auto**: byte-identical to v6 — one `revise` create born `current` +
  `supersede`, one transaction. (Design ideal was "propose+promote in one
  breath / two transitions"; NOT taken — baseline `test_definition.py`
  asserts last-transition == `revise`. Recorded deviation.)
- **propose** + unknown fingerprint + **data-law diff** → revision N+1
  created `proposed`, `data.held=True`, current row untouched, **overlay
  installed** (see below). Re-boot of the same proposal is a no-op; a
  different pending proposal gets withdrawn ("a newer deploy replaced
  this proposal") — a stage, never a lattice.
- **propose** + **diff exceeds data-law** → single revise-to-current with
  `data.deploy_note="promoted without hold: diff exceeds data-law"`,
  `diff_class="code_or_shape"`, marker on the transition's recorded
  `inputs`.

### The overlay (the seam Phase 2 makes per-row)
In propose mode the resident Python is the NEW law but the engine must
serve the CURRENT one — possible exactly when the diff is data-law only
(changed `Tolerance` literals, changed Derived-input `where=` filters),
because those are recoverable from the current revision's stored
fingerprint; `fn`/`check` are stored only as hashes, which is why code
cannot overlay.
- `LawOverride(tolerance, where)` built by `definitions._overrides_for`
  from a revision's stored fingerprint.
- `DerivedMaintainer.overlay: dict[(kind, fact) → LawOverride]`,
  engine-wide in Phase 1.
- **`DerivedMaintainer.specs_for(rdef, *, revision=None)`** — `None` =
  served law (resident specs, parameter-overlaid while held);
  `revision == rdef.proposed_law` = resident verbatim; anything else
  resolves to served law THIS phase. **Phase 2 threads the row's
  `law_revision` here.**
- `compute(..., revision=None, specs=None)` and
  `materialize(..., revision=None)` already thread it; every write path
  (create, transition, owner/related recompute, backfill, clock tick)
  computes through it.
- `classify_diff(diff) → "data_law" | "code_or_shape"`
  (`core/fingerprint.py`): `data_law` iff every changed path is
  `derived.<fact>.tolerance` or `derived.<fact>.over.<i>.(child|related).where…`.
  Advertisement-only diffs (a changed `explain=`) classify `code_or_shape`
  and auto-promote — render reads resident objects, so holding would
  serve new prose under an old law id (recorded deviation).

### §5 record=Inputs()
- `Inputs` exported from `waymark7`; `@action(..., record=Inputs())`
  stores the validated input on the transition (`record` without
  `input=` is a DefinitionError). `Resource.record_inputs` classvar for
  unconditional kind-level recording; the Definition resource sets it.
- Storage: nullable `inputs` JSONB on `waymark7_transitions` (both
  `create_all` and the `waymark7_schema_migrations` differ handle it);
  `TransitionRecord.inputs` threaded through `_append`.
- Exposure: streams pass `include_inputs = visibility_of(principal).full`
  — **full-visibility principals only** (subscriptions are principal-less
  and unprojected; recorded deviation — row-projected exposure is future
  work).

### New core seams Phase 1 added (Phase 2/3 will use)
- `Resource.created_in()` + `create_state_names` (mirror of
  `created_as`/`create_action_names`; fingerprinted as `create_states`;
  replay conformance updated to accept declared landings).
- `Ctx._lifecycle` / `Invoker.lifecycle` = `DefinitionLifecycle(engine)`
  with `.meter`, `.is_pending`, `.supersede_prior`, `.after_commit` —
  definition-only plumbing, never app-visible.
- `ResourceDef.proposed_law` / `.proposed_law_revision`.
- `JobArtifact.detail: dict | None`.

## Phase 2 — law binds rows (NEXT; full brief)

Design §3 + row-halves of §1/§4. The February-close appendix story is the
acceptance narrative. All work in `waymark7/` + `tests/waymark7/`.

1. **`law_revision` column on every kind's table** (schema-migrations
   mechanism), stamped at create with the kind's current definition
   revision int. `meta.law_revision` becomes the ROW's (currently emits
   the kind's current); `meta.law` becomes the row's revision's
   fingerprint hash. Upgrade migration: nullable column, boot stamps
   NULLs to the kind's current revision (design migration sketch).
2. **`pilot` (proposed→piloted)**, `record=Inputs()`, four-eyes with
   propose. Input `Population`:
   - `Population(where={...})` — validate `where` against the kind's query
     grammar (reuse the surface `attention=` checker). Existing matching
     rows restamp to the piloted revision and recompute under it
     (paged like §4 backfill; inline acceptable for 7.0 with a note);
     creates whose validated input matches the filter stamp piloted
     (reuse the require-at-create input-evaluation precedent), others
     stamp current.
   - `Population(after=True)` — no existing rows move; creates from now on
     stamp piloted.
3. **Row-law evaluation**: everywhere facts compute for a row, resolve
   `specs_for(rdef, revision=row.law_revision)`. Three cases to keep
   honest: row under current; row under piloted-while-resident (resident
   specs verbatim); row under a revision whose params come from its
   stored fingerprint (overlay). Two live revisions max.
4. **`adoption = Never | Immediate`** per-kind classvar (import tokens,
   the `Seed.Never` precedent; default `Immediate` = today's behavior;
   fingerprinted). Engine-injected `adopt` action, advertised only when
   `row.law_revision < kind.current_revision`: restamp + recompute +
   log. On **promote**: `Immediate` kinds bulk-adopt (the promote
   backfill IS the bulk adopt); `Never` kinds keep their stamp
   (grandfathered).
5. **Supersede-when-empty**: a revision with rows still stamped to it must
   not be superseded at promote, but two `current` rows per kind is
   wrong. Add ONE machine state (suggest `grandfathered`):
   current→grandfathered at promote-with-survivors; grandfathered→
   superseded when the last stamped row adopts or reaches terminal
   (checked lazily on every adopt/terminal transition of the kind, system
   actor). The February story asserts it: the old revision supersedes the
   day its last workbook closes. (Design implied lingering-current; a
   distinct state is more honest — record the deviation.)
6. **Conformance/replay**: mixed-`law_revision` rows must both walk;
   transitions already carry `defined_by`, so extend only if a check
   breaks.

Tests (crib the two-boot factory pattern from
`test_definition_lifecycle.py`): `test_populations.py` (the §3 tolerance
proof — one collection, alpha rows reconciled under 1e-2, beta under
1e-5, each `meta.law_revision` honest; creates route by population;
`after=True` grandfathers; input recorded; four-eyes),
`test_adoption.py`, `test_supersede_when_empty.py` (the February story).

**Gate**: `pytest tests/waymark7 -n auto` all green (342 + new);
`pytest tests/waymark6 tests/ledger6 -n auto` untouched.

## Phase 3 — ledger7 dogfood

Fork `ledger6/` → `ledger7/` (and `tests/ledger6/` →
`tests/ledger7/`), imports `waymark6`→`waymark7`. The four appendix
stories become tests:
- **§1–§2**: propose a `Tolerance` change to `account.reconciled`; the
  blast-radius `measure` job reports the flip count; Elena (not the
  proposer) promotes.
- **§3**: pilot that tolerance for `Population(where={"fund":
  "fund-alpha"})`. **Gotcha, confirmed with Phase 2's author:** `account`
  has no `fund` field — populations filter a kind's OWN promoted fields
  only. Denormalize `fund` onto accounts via the existing `Seed` (the
  workbook seeds accounts; add `fund` to the copied fields), so the
  account population can filter `fund`. Both Marcus and Elena see
  fund-alpha accounts under the new tolerance, fund-beta under the old.
- **§3 grandfathering / supersede-when-empty**: a workbook with
  `adoption = Never` finishes under its birth law; the old revision
  supersedes when its last workbook closes.
- **§5**: `reject` declares `record=Inputs()`; **delete** the
  `reject_notes` field and the handler lines that set/clear it (friction
  #8 dies) — the reviewer's words live on the transition row. Update the
  story3 tests that assert on `data.reject_notes` to read the transition
  instead.
- Makefile: `ledger7` target (reuse the `ledger5-pg` container on
  :15433, `createdb ledger7_dev`, `PORT_LEDGER7 ?= 8012`);
  `conformance7` if wiring ledger7 into the shared `waymark7_engine`
  conformance fixture (mirror how ledger6 folded into
  `waymark6_engine` in root `conftest.py`, with the two hard-won factory
  disciplines: fresh fund per call; await the deferred refresh job before
  returning).

## Gotchas carried forward (learned across v5/v6)

- **Postgres port**: tests use the docker container on **5433**; a native
  Postgres + SSH tunnel shadow both 5432 AND (for dev servers) 5433 —
  ledger dev servers use a dedicated `ledger5-pg` container on
  **15433**. Never point a dev server at 5433.
- **State factories that defer jobs must await them** before returning
  (`_cr_job_done` pattern) — an orphaned refresh job's async transitions
  race later conformance cases' transition-count windows.
- **Conformance state factories mint a fresh fund/workbook per call** —
  never cache an id (a factory runs once per candidate principal; a
  cached id already at the target state can't be re-driven, and
  `unique=(fund, period)` 409s a reused fund).
- **`link(edge=...)` not `rel=`** (v6 deviation); **surface members name
  link rels, not edges** (v6 deviation).
- **Chained cross-kind recompute exists in v6+** (a break write flips its
  account's facts AND the workbook's aggregates over them, same commit) —
  but the **clock sweep still does not chain cross-kind** (recorded, no
  dogfood shape hits it; would bite a Clock-derived fact that another
  kind derives over).
- **waymark5 retains a latent one-hop recompute gap** (the v6 chained-
  recompute fix was not backported) — irrelevant to v7 but noted.

## Design deviations already recorded (do not re-litigate)

Phase 1's: auto-mode single-breath (not two transactions); advertisement-
only diffs auto-promote (render reads resident objects); recorded inputs
exposed to full-visibility principals only; `__registry__`/surface
targets always revise in auto semantics. All are in the Phase 1 agent's
report and reflected above; carry them into the final notes doc.
