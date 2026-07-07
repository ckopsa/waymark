# waymark5 implementation notes (handoff)

What you need beyond `waymark5-design.md` to work on the `waymark5/`
package: where the implementation deviates from the design text, what the
build taught us, and the operational caveats. The same discipline as the
v1–v4 notes — read this before extending the engine.

The package began as a fork of `waymark4/` (retargeted imports,
`FORMAT_VERSION = "5"`, `waymark5_*` engine tables) and was verified
behavior-identical under its own conformance sweep before any 5.0 change
landed. It was then transformed section by section. `mealplan5/` is the
same app, **deliberately unchanged**: 5.0 adds no app-facing declaration
— the law versions itself. The dogfood claim is that an untouched v4 app
boots, mints its definition history, anchors every write, and passes the
full sweep with zero code changes; it does.

## Map

| Area | Where | The one thing to know |
|---|---|---|
| Fingerprint | `core/fingerprint.py` (`fingerprint_of`, `fingerprint_hash`, `canonical_json`) | a *projection of the registry*, not a second description: machine, guards (callable source hashes), derivations (fn hash, `Tolerance` literal), `When`/`OneOf`/`unique`/`Owns`/`Vocab`/`unless`, compound blast radii, rendered schemas byte-for-byte, the storage facet (per-table `schema_snapshot` absorbed), handler source hashes. Deterministic across processes (tested via subprocess) |
| Callable hashing | `core/fingerprint.py` (`callable_hash`) | `inspect.getsource`, dedented/stripped; fallback `qualname` + `co_code`. Accepted semantics, stated in the docstring: **the text is the law** — a formatting-only edit of a lambda revises. Cheap, and honest |
| Diff classes | `core/fingerprint.py` (`diff_fingerprints`, `classify_path`) | every changed path classifies `advertisement \| judgment \| truth \| shape`, innermost surface wins — a `Derived.explain` edit is `advertisement` (garnish), its `fn` is `truth` |
| Definition kind | `server/definitions.py` (`Definition`, `revise_definitions`) | one row per **revision** per target kind (`data`: target_kind, revision, fingerprint_hash, fingerprint, diff); machine `current → superseded`; read-only on the wire; a `__registry__` row makes the deploy one readable object. All writes through the single invoker, system deploy actor, one correlation per boot |
| Revise at boot | `server/engine.py` (startup) + `definitions.py` | fingerprint → compare → write only on change (a restart costs nothing); rollback detection stamps `reverts_to`. The definition kind revises **first**, and pre-seeds its own law from the stored current row, so revision rows are themselves anchored to the law in force when written |
| Anchor plumbing | `server/invoke.py` (`Invoker._append`) | THE `append_transition` wrapper injecting `defined_by` — the only caller of the storage method, so no write path (create, action, batch, bulk, compound child, authored sync, jobs, cascades) can forget the anchor. The lookup reads `rdef.current_law`, stamped by the revise |
| Anchor on the wire | `server/render.py` (`meta.law`, `meta.law_revision`) + `server/events.py` (`event_payload`) | envelopes and collections carry `meta.law` (revision row id) **and** `meta.law_revision` (the revision NUMBER, stamped on `rdef.current_law_revision` wherever `current_law` is stamped) so a client renders "rev N" with no resolution fetch; one payload serializer feeds SSE and webhooks, so both carry `defined_by` — and `class: "transition"`, completing the one-taxonomy rule (every event class names itself; observation/derivation already did). All additive — a v4 client keeps working |
| Pre-law horizon | `storage/postgres.py` (`defined_by` nullable) | NULL means "before the law" (migrated v4 logs, and the first boot's own definition rows — no law existed when the law was first written). Replay skips NULLs; nothing fabricates an anchor |
| Continuity map | `core/resource.py` (`renamed_actions`, `renamed_fields`) + `storage/postgres.py` (`check_state_tokens`) | boot scans `SELECT DISTINCT kind, action` over the log and refuses names that neither the machine nor a rename chain reaches — the refusal message contains the declaration to write. Rename declarations are themselves fingerprinted (`truth`), so declaring one is a revise |
| Replay conformance | `testing/conformance.py` (`replay_history`) | every non-NULL `defined_by` must name a stored revision (anchor integrity) and the row's `(action, from, to)` must be legal under **that** revision's machine, rename chains applied forward. Plus: log prose is never re-rendered — event-surface summaries byte-identical to stored rows |
| Stale by definition | `core/fingerprint.py` (`stale_facts`) + `definitions.py` | a revision whose diff touches a fact's semantic surface (`fn`, `over.*`, `tolerance`, `flips_at` — not explain/vars garnish) marks the fact stale; the revision row carries the set as `backfill_pending` — the durable catch-up marker, one insert with the revise, so law and debt commit or vanish together — and `revise_definitions` returns `(law_map, stale_by_kind)` with any unsettled marker from a crashed prior boot unioned in. A marker naming a fact the current law no longer declares is dropped with a log line; a further revise inherits unpaid debt onto the new row |
| Backfill | `server/derived.py` (`DerivedMaintainer.backfill`) + engine startup order + `definitions.py` (`settle_backfill`) | keyset-paged, `FOR UPDATE` per batch, `materialize` + `update_data`: **no derivation events, no version bumps**, `next_flip_at` refreshed in the same pass. Immediate mode runs after revise and before anything serves or delivers; the revise transition is the one loud event. The marker settles (`settle`, a deploy-actor self-transition on the definition row) only after the recompute commits — the catch-up lifecycle is two joined transitions in the deploy history. A crash anywhere in the window re-detects the debt on the next boot and re-runs the *whole* backfill, mid-batch progress included — idempotent by construction, recompute writes the same values |
| Deferred backfill | `core/derived.py` (`Deferred`) + render/schemas/router/problems | `backfill = Deferred(batch, pause)`: kind serves immediately, `meta.recomputing: [fact,…]` on envelopes, the fact's query params dropped from the advertised schema, its facet skipped, both sort spellings (`fact`, `-fact`) dropped from the sort enum, `parse_query` refuses filter *and* sort names with a 503 `FactRecomputing` Problem (the Service-down honesty precedent). The value still renders in `data`; other filters and sorts work throughout. The drain settles the marker per kind and clears the mark; a failed or crashed drain leaves the marker unsettled, and the next boot restores `rdef.recomputing` from it and resumes — honest, never mixed-law, across restarts |
| Seed retro | `core/owns.py` (`Seed(retro=Never)`) | E4's "template edits never retro-propagate" prose policy, promoted to a declaration with a diff class; `Never` is the only shipped policy — others are a `DefinitionError` naming the punt |
| Revise spelling | `core/resource.py` (`created_as`, `create_action_names`) + `server/definitions.py` + `server/invoke.py` (`_create_core`) | deploys are nameable on the wire (design §2): a kind declares what the log calls its creation — one invoker path, one declared label. The definition kind logs revision N+1's create as `revise` (revision 1 stays `create`: nothing was revised). `create_action_names` is the continuity vocabulary, read from the **current class** like `renamed_actions`: `check_state_tokens` and the replay conformance accept the declared spellings as create rows, which also keeps `create`-spelled revision->1 rows from pre-rename boots legal (`create` never leaves `ENGINE_ACTIONS`). The declaration is fingerprinted (`created_as` facet, emitted only when non-default), so adopting it revises the definition kind once — and that revise is itself the first `revise` row |
| Engine kinds in discovery | `core/registry.py` (`ResourceDef.engine_owned`, `Registry.engine_kinds`) + `server/router.py` (`/.well-known/waymark`) | discovery advertises `engine_kinds`: the kinds the engine contributed (definition, job, grant, approval_request, member, role, subscription, attachment — whichever this engine's flags registered), marked at the registration site (`register(cls, engine_owned=True)`), never re-derived from a hardcoded list. The generic UI folds them behind the nav's ⋯ menu; a server without the field gets the flat nav |

## Deviations from the design text (deliberate, tested)

1. **Tolerance classifies `judgment`, marks stale anyway.** Staleness
   detection is by semantic surface, not by diff class — the taxonomy
   stays true to the design's table while the recompute stays true to
   the values.
2. **`renamed_fields` is declared and fingerprinted but not replayed** —
   the replay check maps actions and states; fact renames have no log
   column to validate against. The declaration exists so the diff and
   the continuity vocabulary are complete.
3. **Anchor lookup lives on `rdef.current_law`**, not a live
   `engine.current_law()` call — the invoker, renderer, and maintainer
   have the registry, not the engine; the revise stamps both and they
   cannot drift (one writer).
4. **No strict meta schema admits `law`** — v4's envelope validation has
   no closed meta schema, so `meta.law`/`meta.recomputing` are additive
   with nothing to relax. Noted because a future strict-schema effort
   must include them.
5. **The definition kind is read-only on the wire and excluded from
   client-create conformance** (the `job` precedent): its rows are only
   ever written by the boot's system deploy actor — `supersede` and
   `settle` alike.

## Verified

- Framework tests: `tests/waymark5/` — the forked baseline plus
  `test_definition` (8 — including discovery's `engine_kinds` and the
  `revise` spelling), `test_anchoring` (8, now asserting
  `meta.law_revision`), `test_continuity` (5), `test_backfill` (7),
  `test_backfill_recovery` (6 — the crash window: durable marker,
  resumed drains, sort blocking, removed-fact markers, marker/revise
  atomicity): 260 passed.
- Conformance: the full `--waymark5` sweep over the unchanged
  `mealplan5` — 1537 passed on the untouched fork before the
  transformation, 1624 passed / 0 failed after it, with definition rows
  minted, every transition anchored, the `definition` kind's own
  conformance included, and the per-kind replay checks active.
- Postgres: dockerized `waymark-test-pg` on :5433 (`make db`);
  `make test` / `make conformance5`. Per-worker databases; never run two
  xdist invocations concurrently against one DSN (see the waymark4 notes
  for the failure signature).
