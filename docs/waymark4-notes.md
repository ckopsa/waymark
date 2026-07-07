# waymark4 implementation notes (handoff)

What you need beyond `waymark4-design.md` to work on the `waymark4/`
package: where the implementation deviates from the design text, what the
build taught us, and the operational caveats. The same discipline as the
v1–v3 notes — read this before extending the engine.

The package began as a fork of `waymark3/` (the design's "carries over
unchanged" list, taken literally: retargeted imports, `FORMAT_VERSION =
"4"`, `waymark4_*` engine tables) and was verified behavior-identical
under its own conformance sweep *before* any 4.0 change landed. It was
then transformed section by section; `mealplan4/` is the same app
migrated per the design's migration sketch, and is the dogfood for the
new declarations.

## Map

| Area | Where | The one thing to know |
|---|---|---|
| Field origins | `core/derived.py` (`derived_spec`) + `core/authored.py` (`authored_spec`) | origins are exclusive and discovered per-field off the pydantic `FieldInfo`; the spec rides a **callable** `json_schema_extra` (pydantic rejects non-serializable dicts), which is also what stamps `readOnly` + `x-source` on every schema emission — Data schema, create schema, anything inheriting the field |
| Derived | `core/derived.py` (`Derived`, `Tolerance`, `Clock`, `Count`, `Sum`) + `server/derived.py` (`DerivedMaintainer`) | `over=` names own fields, `Owns.field("state")` children, or `Clock`; exactly one of `fn=`/`within=`. Values materialize into the row's `data` JSONB inside the invoking transaction (`invoke.py` create/transition hooks), so the existing promoted-column machinery serves filter/sort/facet unchanged. `resolve_inputs` is the one sanctioned re-computation path (conformance + `require()` vars) |
| Owner recompute | `server/derived.py` (`recompute_owners`) | a child transition recomputes the parent's Owns-based facts in the same commit — old **and** new parent on a `via` reassignment, row `FOR UPDATE`, via `storage.update_data` (deliberately no version bump: versions narrate transitions, not maintenance) |
| Derivation events | `server/events.py` (`DERIVATION_CHANNEL`) + `server/invoke.py` (`_flip_session`) | third event class: `{class: "derivation", fact, from, to, at, cause}`, `cause` = the causing transition id or `"clock"`. Published post-commit through the one dispatcher; an abort discards the pending flips. SSE default is `{transition, derivation}`; `?classes=` narrows |
| Clock | `core/derived.py` (`Clock`, `flips_at=`) + `server/derived.py` (`tick`) + `server/engine.py` | clocked kinds get a maintained `next_flip_at` promoted column + index; `await engine.tick(now)` sweeps due rows (tests drive it by hand; a background task runs it every `derived_tick_interval=30.0`s, started only when a clocked or discovering kind is registered); a monotonic `clock` cursor in `waymark4_cursors` keeps restarts from double-skipping |
| Log facts | `core/history.py` (`LogFact`, `ActorOf`, `actor_of`) | the fixed shape is `async value(r, ctx)` answered by the indexed `storage.transition_actor` query; future `count`/`performed` facts are subclasses, not new plumbing |
| Unless | `core/history.py` (`Unless`) + `core/actions.py` (`unless=`) | compiles once to a single `Guard`; the projector folds the Deny into `unavailable` and the invoker 409s with the identical sentence. `four_eyes(of=…)` is now sugar over it (`guards.py`) — same API, same default sentence, so E3 call sites are unchanged |
| require() | `core/guards.py` (`FactRequired`, `require`) | a guard over a **stored** boolean derived fact — render fold and invoke refusal read the same bytes; the reason comes from the `Derived` spec's `explain=`/`vars=` (their consumer); clocked gates carry `becomes_available: {"at": …}` from `flips_at`/`next_flip_at` on both the envelope and the 409 |
| When | `core/when.py` (`When`, `Field.of`) | declared as a `ClassVar` on input models (the `OneOf` discovery pattern); three consumers: `if/then(/else)` folded into the advertised schema with resource comparands resolved per document, field-keyed 422s at validation (dry-run included), and demand class computed on the base branch |
| Compound | `core/compound.py` (`Compound`, `Create`, `Advance`, `Each`, `ServiceEffect`) | a class attribute that compiles via `__set_name__` into an ordinary `ActionDef` — same route, guards, safety, dry-run, invoker. Children run as real `ctx.create`/`ctx.invoke` (one txn, parent's correlation id, child guards can 409 the whole act); the touch set is compiled from the declaration, so E8 enforcement can't drift from the advertisement; the entry's `effect` renders the blast radius (`creates`/`advances`/`effects`) |
| Effects | `server/invoke.py` (`_child_effects`, `_settle_effects`) + `server/external.py` (`Service.call_op`) | compensator is **mandatory** (constructor error without one); on abort, compensators run in reverse order over executed effects only; every attempt is audited as an E6 `job` (`target_kind="compound_effects"`) under the act's correlation id, so `transitions_by_correlation` reads as one story. `defer=True` runs effects post-commit on the E6 executor with leases; committed resource writes then stand and failures compensate the effects only |
| Batch | `core/actions.py` (`Batch`) + `server/invoke.py` (`Invoker.batch`) + `server/router.py` | one generic `POST …/-/{action}/batch` route; every item goes through the single invoke algorithm. Atomic = one txn, one correlation id, and **every item's verdict is still collected after a refusal** (a rollback sentinel aborts after the full pass); partial = per-item commits. Idempotency-Key covers the whole batch (byte replay); `?dry_run=1` executes-then-rolls-back for would-it-commit verdicts; a batch action may stage `{"items": […]}` in its draft |
| Authored | `core/authored.py` + `server/external.py` (`AuthoredMeta`, `refresh_authored`, `refresh_external`) + `server/invoke.py` (`sync_authored`, `mark_authored`) | per-field authority riding Mirror's machinery: same adapter protocol, same TTL/unreachable/conflicted discipline, but `sync_state` is *data* scoped to the authority — it never gates the domain machine, so a conflict on authored fields can't block writes to written fields. The sync applies only the authored subset and commits through the engine's write tail (`observe_authored`, `mirror-sync` system actor, derivations materialized in the same commit). Request bodies and handlers writing an authored field are refused like derived tampering |
| follows= | `core/authored.py` (`Authored(follows={value: transition})`) | fires per changed field after the sync commit, through the invoker as the system actor; a guard refusal is logged and the value change stands — the authority's fact is never held hostage to the machine |
| Discovery | `server/external.py` (`Discover`, `run_discovery`) | declared class attr (`every=`, `query=`); rides `engine.tick` behind a per-kind ms cursor; adapter `discover() → [external ids]`; unknown ids mint via `invoker.create` with a deterministic idempotency key (`discover:{kind}:{id}`) |
| Grammar | `core/schemas.py` (`rows` param) + `server/router.py` + `storage/postgres.py` | `?sort=`/`page[…]` were already grammar; new `?rows=none` skips the row fetch and answers `items: null` + `total` from **exactly the same conds** as the row SELECT — one WHERE, one definition. Unknown `rows` values are Problems like any unknown param; hrefs round-trip through `merge_params`' fixpoint |
| Booleans | `server/router.py` (`parse_query`) + `storage/postgres.py` (`_generated_column`) + `testing/conformance.py` | the grammar speaks JSON scalars: a boolean filter value is `true`/`false` and anything else (`True`, `1`, `yes`) is a 422 Problem; boolean filterables promote to a real `boolean` generated column, and the conformance walker serializes targets in wire spelling. Found by the sweep the first time a dogfood resource declared a filterable boolean (`prep_task.overdue`) — the fix is framework-side, never a dodge in the app |
| Create projection | `server/render.py` (`_create_surface`) + `core/guards.py` (`declared_needs_input`) | create-guard `accepts=` folds into the advertised create schema via the same `_admits_schema` fold actions use; warning create guards whose checks declare `needs_input=False` ride the create entry as E1 `warnings`; approval-mode creates carry the agent's `Waymark-Acknowledge` names through `ApprovalData.acknowledged` → `ctx.create(..., acknowledged=…)` — the header never travels implicitly, only surface-recorded data does |
| Members | `server/members.py` (`roles_registered_at_invite`) | invite refuses a role name absent from the registry, reusing the grant path's `_active_role_named` — one source of truth, checked where the reviewer is still looking |

## Deviations from the design text (deliberate, tested)

1. **`rollups=` is kept verbatim; `Count`/`Sum` are the documented
   spelling.** Design §10 wants E4 rollups *compiled down* to `Derived`.
   What shipped: both exist — the old envelope `rollups` key and
   correlated-subquery filters are untouched (the v3 wire and the
   baseline suite stay intact), and `Count`/`Sum` are real derivations
   over `Owns` living in `data`. Folding the old spelling onto the new
   maintainer is open work; the module docstring records it.
2. **`Ref`-peer derivation inputs don't exist.** Design §2's
   `Ref["account"].via("twin").field(…)` input kind is unimplemented —
   `over=` accepts own fields, `Owns` children, and `Clock`. The same
   deviation keeps `_maintain_ref_labels` as the second maintainer
   (design §10's label re-founding): honest unification needs a reverse
   invalidation map (target writes propagating to referrers) that
   today's label machinery doesn't have either. Both halves of this are
   one future change.
3. **All derivations materialize.** The design allows a virtual
   render-only tier; the implementation materializes everything — a
   superset of the law. "Must be maintainable" holds structurally: the
   `Derived` constructor refuses non-extractable Clock shapes unless the
   declaration supplies `flips_at=`.
4. **Maintenance writes don't bump `version`/`updated_at`.**
   `storage.update_data`'s docstring argues it: versions narrate
   transitions; a parent whose rollup moved has not transitioned. The
   invoked resource's own recompute rides its transition's normal save.
5. **Derivation events are not in `Last-Event-ID` replay.** Per the
   design's own basis argument (the log + the clock suffice; replay is
   by recomputation), flips are bus-published post-commit and never
   stored; a subscriber that missed one re-derives by fetching. Create's
   initial computation emits no events (nothing existed to change);
   child-create-caused parent flips do.
6. **Clock extraction assumes UTC midnights** for date-typed comparands;
   spurious flip candidates self-clear on sweep (recompute finds no
   change, emits nothing). `flips_at=` is the declared escape hatch for
   shapes the extractor can't read.
7. **`When` folds what JSON Schema can say.** Input-vs-input comparands
   emit no `if/then` (there is no cross-field constant in the language)
   — enforcement still runs; ISO date/datetime comparands use the
   numeric-range keywords on strings (same-format ISO orders
   lexicographically as it orders temporally); `effort` reflects the
   base branch, with the `then` block as the advertisement of the
   conditional arm.
8. **`Compound` has no `on=`** — the declaration site (a class attribute
   of the resource) is the target; `__set_name__` compiles it before
   `__init_subclass__` runs the machine checks. The effect class is
   `ServiceEffect` (`core.types.Effect` already owns the wire name).
   `Each` fans out over declared, promoted `where=` filters (bounded by
   `limit=`), not arbitrary id lists; `{created.<kind>}` names the last
   create of that kind.
9. **Batch scope guards.** Batch requires an input model and refuses
   `place=`, `bulk=`, and fenced actions at import (the batch-level
   Idempotency-Key is the replay unit, which is also why non-idempotent
   actions are fine). Approval-mode grants get a 403 on batch routes —
   approval capture of whole batches is a recorded punt. If a
   `UniqueViolation` poisons an atomic transaction mid-pass, the
   remaining items report `failed`/"not evaluated" (Postgres cannot
   judge inside an aborted txn) — the verdict says so.
10. **The authored sync is an engine write tail, not a declared
    action.** An `@action`'s `to=` names one state and a sync must move
    none; `sync_authored`'s docstring records the argument. One
    resource-level `AuthoredMeta` surface serves the one-authority case
    (the design's own allowance); conflict marks are feed-fed
    (`mark_authored`) since a pull-only authority has no push race.
    Discovery mints `{"external_id": …}` only — values arrive on the
    first pull-through read.
11. **The router remains class-assembled** (inherited v3 deviation,
    reduced again): compound and batch routes are emitted from
    declarations, but `build_router`'s handlers are still functions.
12. **Member scoping default is unchanged** (`member_visibility="full"`),
    and i18n stays where every version has left it — the new human
    strings (`Unless` reasons, `Derived.explain`, verdict reasons) all
    flow through the existing single emission points, so the walk stays
    a walk.

## Dogfood (mealplan4)

The migration-sketch upgrades applied to the same app: coverage is a
declared fact (`Derived` over the plan's days) gating `finalize` through
`require()` — the refusal reason is generated from the declaration, and
the fact renders in `data`; prep tasks carry a clocked `overdue`
derivation the sweep flips and dashboards can watch. See the bottom
section of the root `conftest.py` for what conformance factories look
like against 4.0 declarations.

## Verified

- Framework tests: `tests/waymark4/` — the forked baseline plus new
  suites for every section (`test_derived`, `test_clock`, `test_unless`,
  `test_when`, `test_compound`, `test_batch`, `test_authored`,
  `test_grammar4`, `test_create_projection`); the repo-wide run (all four
  package versions) is 531 tests, green.
- Conformance: the full `--waymark4` sweep over `mealplan4` (every kind ×
  state × principal) — 1311 passed, 0 failed. Green on the untouched fork
  before the transformation began, and green after it with the dogfood
  upgraded to the 4.0 declarations.
- Postgres for both: dockerized `waymark-test-pg` on :5433 (`make db`);
  `make test` / `make conformance4`. The suites use per-worker databases
  (`per_worker_dsn`); never run two xdist invocations concurrently
  against one DSN — they share worker databases, and the resulting
  drop/create races produce phantom missing-relation and duplicate-type
  failures that look like engine bugs.
