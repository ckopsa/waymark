# waymark2 implementation notes (handoff)

What you need beyond `waymark2-design.md` to work on the `waymark2/`
package: where the implementation deviates from the design text, what the
build taught us, and the operational caveats. The same discipline as v1's
`implementation-notes.md` — read this before extending the engine.

## Map

| Area | Where | The one thing to know |
|---|---|---|
| Declarative guards | `waymark2/core/guards.py` | no AST inspection anywhere; `accepts` is evaluated for both the rendered enum and the enforcement; composites compose `judges`/`reads` |
| Structured actions | `waymark2/core/actions.py` | `Safety`/`Edit`/`Bulk`/`PartScope`; `ActionDef` exposes v1-compat derived properties (`scope`, `prefill`, `draft`, `collab`, `bulk`) so the server reads one source of truth |
| Checks | `waymark2/core/checks.py` | the closure rule and `one_way` are **errors**; `WAIVABLE` is down to `{altitude, large_effort}` |
| Demand classes | `waymark2/core/demand.py` | computed per action, rendered as `"effort"`; place-key `const` fields count as no demand |
| Refs | `waymark2/core/refs.py` | `Ref["kind"]` works top-level and inside unions; `x-ref-opts` is DSL plumbing stripped from wire schemas |
| Render | `waymark2/server/render.py` | `render(resolved=…)`; project skips `reads≠()` refinements *by declaration*, resolve lets failures propagate — there is no `except Exception: continue` |
| Drafts | `waymark2/server/drafts.py` | `DraftStore.save_fields` is the single write path (router PUT, relay drain, autosave); audience and part key are computed from declarations in exactly one place each |
| Relay/2 | `waymark2/server/collab.py` | per-field `base_rev`; stale → `reject` with the field's truth; rooms are local, liveness crosses workers on the bus |
| Bus | `waymark2/server/bus.py` | in-process + Postgres LISTEN/NOTIFY; consumers skip their own echo via `_origin`; >7.5 KB payloads degrade to a `sync` marker (tables are the truth, the bus is liveness) |
| Storage | `waymark2/server/storage/postgres.py` | engine tables are `waymark2_*` (coexists with a v1 schema); date-time promotions are real `timestamptz` generated columns via the `waymark2_ts` IMMUTABLE function |
| Migrations | `waymark2/server/migrate.py` | snapshot + diff → numbered `.sql` revisions; `apply()` refuses unresolved review lines; conformance round-trips the initial revision every run |
| Conformance | `waymark2/testing/` | inputs synthesized from acceptance sets (intersected per field, same as render); the derived walker replaces most state factories; fixtures are `wm2*` so both plugins coexist |

## Deviations from the design text (deliberate, tested)

1. **Natural replay precedes guards** (design §4 said nothing; v1 ran
   guards first). With `accepts` as enforcement, a second identical invoke
   can be *honestly* denied — the first invoke consumed the value the set
   no longer contains (`remove_theme` removes the theme from its own
   acceptance set). Replay before guards is the byte-honest answer, exactly
   as with idempotency keys: the first execution's guards already passed.
2. **Draft saves are not transition-log rows.** The design wanted drafts
   "through the same invoker … in the transition log"; autosave cadence
   (a row per debounce flush) would drown the activity feed. Instead:
   one write path (`DraftStore.save_fields`) shared by every producer,
   per-field `revs`/`authors` as the draft's own audit, and
   consume/discard as room lifecycle events. Promoting saves to the log
   stays open — behind sampling, if regret telemetry ever needs it.
3. **Migrations emit plain SQL revisions, not Alembic scripts.** Same
   contract (diff of declared schema, ordered revisions, recorded applies,
   CI round-trip) with less machinery; the files are reviewable and
   runnable by anything that can execute SQL. Alembic can wrap them later
   without changing the emission.
4. **The draft envelope's `save`/`discard`** are POST endpoints on the
   draft path (plus PUT/DELETE aliases); the draft's state machine
   (`open → consumed | discarded`) is conceptual — rows are deleted on
   consume/discard rather than kept as terminal-state rows.
5. **`guard.rate_limit` remains per-process.** The bus exists (design §7)
   but the limiter doesn't ride it yet; `retry_at` rendering is honest
   either way.

## Known gaps

- **i18n** — unchanged deferral, same emission-point argument as v1.
- **`x-display.relation`** is rendered from `relates=` and auto-enforced
  for check-less guards, but the generic UI doesn't yet set min/max
  between related inputs, and the gap fuzzer doesn't yet sample against
  declared relations (design §3.5 territory).
- **`dangling_ref` conformance** (every rendered Ref resolves) is not yet
  a suite test; `check_refs` covers declaration-time validity only.
- **`waymark2 extract-messages`** is still the v1 stub.
- **The agent/CLI clients are the v1 fork** — wire-compatible (they ignore
  `effort` and the draft advert changes), but they don't yet *use* revs or
  the draft envelope.

## Operational caveats

- **Engine tables are `waymark2_*`**, so a v1 and a v2 app can share a
  database — but *resource* tables keep their plural names; don't point
  both stacks at the same resource tables (column shapes differ:
  date-time promotions are `timestamptz` in v2).
- **PostgresBus needs LISTEN privileges** and one extra connection per
  worker (plus a lazy publisher connection). `Engine(bus=InProcessBus())`
  for tests/single-worker.
- **Dispatcher construction order matters**: it registers its bus channel
  in `__init__`, which must happen before `bus.start()` LISTENs — the
  engine's `startup()` does this; keep the order if you re-wire it.
- **Collab join gate**: the WS handshake probes the action for the joining
  principal (available ⇒ join). A principal whose affordance *lapses*
  mid-session keeps the socket until the room closes — re-probe on frame
  receipt if that matters before it's fixed properly.
- **The conformance walker acts as a `system` principal** (`WALKER`).
  Role-gated paths need a registered `@state_factory`; the skip/fail
  messages name the exact registration to add.
- **`pytest --waymark2` composes with the v1 plugin** — fixtures are
  namespaced (`wm2`, `waymark2_engine`). Run both suites in one repo; they
  share `WAYMARK_TEST_DSN` (and per-worker databases under xdist).
