# waymark9 implementation notes

The package began as a byte-identical fork of `waymark8/` (modulo the
rename: `waymark9_*` storage and channels, `--waymark9` plugin, `wm9`
fixtures, media type v=9), implementing `docs/waymark9-design.md` —
"the law binds the row's judgment." Read the design first; its center
is deliberately narrow: judgment becomes row-scoped exactly where the
verdict is a stored tree, and nowhere else.

Built on one move: **retire a prohibition, install a mechanism.**
7.0's "writes are judged by the row's law" held because the pilot gate
forbade judgment divergence between live revisions; 9.0 widens the gate
by one class (recoverable expression-guard leaves) and makes the three
judgment paths — render's probe, the out-of-state concealment probe,
and the invoker's enforcement loop — resolve each row's guards under
the row's revision, reconstructed from stored fingerprints.

## Map

| Area | Where | The one thing to know |
| --- | --- | --- |
| The overlay (§1) | `waymark9/server/judgment.py` | `judgment_raw(fp)` extracts top-level expr-guard entries per action/index; `build_overlay(rdef, raw)` rebuilds them into ordinary `guard.expr` guards against the resident machine (positional + NAME matched — mismatch means the resident guard serves); `resolve_action(rdef, defn, revision)` is THE seam, returning a `dataclasses.replace`d ActionDef with the row's guards |
| Resolution order | `judgment._select` | `specs_for`'s order verbatim: per-revision `judgment_laws` first (grandfathered laws, parameter-served pilots), then piloted-resident-verbatim, then `judgment_served` (the current law while a hold/pilot keeps newer code resident), then resident |
| The gate (§2) | `core/fingerprint.py` (`_JUDGMENT_LAW_PATH`) | `machine.actions.<name>.guards.<i>.(expr\|vars_exprs\|explain\|remedies\|hide\|severity\|requires_token)` joins data_law; `classify_diff` now ORs the derived and judgment patterns. check hashes, guard add/remove (positional shifts), composites, safety, machine shape, create guards all stay code_or_shape |
| Consumers (§1) | `server/render.py` (main action loop), `server/invoke.py` (post-load), `server/collab.py` (both probe sites) | one `resolve_action(...)` call each, keyed on `instance.law_revision`; `probe_transition`/`probe_hidden_only` signatures untouched — they receive the substituted defn |
| Lifecycle wiring | `server/definitions.py` | `KindRevise` gains `judgment_served`/`judgment_laws` (RAW fp entries; rebuilt at the apply site where the rdef is at hand); installed/dropped at exactly the derived overlay's sites: boot collect (grandfathered rows + reboot-pilot), hold, pilot-continue, auto-grandfather, promote flip (served cleared, lingering grandfathers reinstalled, promoted pilot popped), pilot-withdraw pop, supersede-sweep pop |
| Proofs | `tests/waymark9/test_judgment_law.py` | the admission test: leaf-vs-structure classification, hold-serves-the-current-gate (advertised AND enforced), pilot per population (envelope + 409 honest per row, both readers, comply-with-your-own-gate), grandfather (the February workbook closes under its birth gate and its law supersedes that day), and the check= contrast |
| Dogfood | `mealplan9/` + `tests/waymark9_dogfood/` + `--waymark9` | forked from mealplan8 unchanged — its guards were already trees, which is the point: a v8 app gets row-scoped judgment by owning expression guards, no migration |

## Deviations / judgment calls (deliberate, tested or recorded)

1. **Reconstruction is name-checked, not just positional.** Across a
   code-or-shape grandfather the guard lists may differ; an override
   applies only where index AND name match the resident guard. A
   mismatch (or an unreadable stored entry — logged, never silent)
   leaves the resident guard serving: exactly v8's behavior, never
   worse.
2. **`becomes_available_at` rides the resident guard** — a callable
   can't be read back from a hash. v7 deviation #6's family, confined
   to scheduling garnish.
3. **Everything else on the ActionDef is resident by construction.**
   `resolve_action` substitutes guards only; safety, display, input
   schema, and handler cannot differ between live revisions because the
   gate refuses those paths. (For grandfathered *code* diffs they CAN
   differ — there the resident action serves except for its recoverable
   guards, the same best-effort the derived overlay already makes.)
4. **`explain`/`severity`-only guard diffs are now holdable** — under
   v8 an advertisement-adjacent judgment change auto-promoted because
   render read resident objects; the judgment overlay is precisely
   render reading the store, so the honesty objection dissolves for
   guards (it still stands for non-guard advertisement: summaries,
   labels, descriptions).
5. **Warning-severity overrides join E1 as reconstructed** — a
   grandfathered `severity="warning"` gate acknowledges past exactly
   like a resident one; the rebuilt guard runs the same evaluate path.

## Gotchas carried forward (learned across v5–v9)

- Postgres on **5433** (docker `waymark-test-pg`); never two concurrent
  xdist runs; single-process for targeted files is fine, `-n auto` for
  conformance sweeps.
- Callables hash by SOURCE TEXT: a factory that closes over a
  threshold mints ONE law for every threshold — write distinct check
  bodies at call sites in tests (bit the v9 contrast test; the
  expression law is immune, which is its §1 point).
- The clock sweep still does not chain cross-kind; the backfill/restamp
  paths do not recompute cross-kind dependents (recorded since v6; no
  dogfood shape hits either).

## Explicit residue (design punts, unchanged)

Guard blast radius (`measure` counting newly-refused rows — first 9.x
extension), create-guard routing, safety overlay, composite internals
and `require`/`rollup_is` parameters as overridable leaves,
machine-shape pilots (the handler/code boundary).

## Status

| Phase | What | State |
| --- | --- | --- |
| 0 | Fork `waymark8/` → `waymark9/` (+ mealplan9, conformance wiring) | done — 31 framework + 5 dogfood spot-checks green on the fork |
| 1 | The judgment overlay (gate, module, three consumers, lifecycle wiring) | done — law suites (44) + guard-heavy regression sweep (83) green |
| 2 | The admission test | done — `test_judgment_law.py`: 5/5 (hold, pilot, grandfather-supersede, classification, contrast) |
| 3 | Conformance | `--waymark9=plan` slice run at build time (see commit); full sweep belongs on the off-laptop Nomad runner |
