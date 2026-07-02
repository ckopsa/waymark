# Implementation notes (handoff)

What you need beyond `waymark-spec.md` and the README to work on this
codebase: where the spec and the implementation differ, what was added beyond
the spec, what's deliberately unfinished, and the operational caveats that
aren't visible from the happy path.

## Map

| Area | Where | The one thing to know |
|---|---|---|
| DSL + import-time checks | `waymark/core/` | `Resource.__init_subclass__` assembles the machine and runs every §10.1 check; a broken definition module cannot be imported |
| Rendering | `waymark/server/render.py` | pure given `(instance, principal, depth, now)`; guards run in probe mode (`inp=None`); out-of-state transitions get generated `unavailable` entries |
| The write path | `waymark/server/invoke.py` | **every** write is `Invoker._invoke_in_session`; bulk, create, and workflow cascades all funnel through it |
| Storage | `waymark/server/storage/postgres.py` | engine-owned tables built dynamically per resource; `waymark_transitions` is audit trail + outbox + idempotency anchor |
| Events | `waymark/server/events.py` | one dispatcher task per process tails the transition log (LISTEN/NOTIFY + poll fallback) and fans out to SSE subscribers |
| Conformance | `waymark/testing/` | parametrized from the `@state_factory` registry at collection time; the `wm` fixture builds a test app with its own principal resolver |
| Clients | `waymark/client/` | `AgentClient` enforces Part IV; `mcp_tools()` is the §9 projection |
| Generic UI | `waymark/server/static/ui.html` | served at `GET {base}/-/ui`; consumes only the envelope, invents no affordances |

## Deviations from the spec text (deliberate, tested)

1. **Idempotency replay precedes the state check.** The §11.3 pseudocode
   looks up the idempotency key *after* guards, but then a retried
   state-changing action would 409 on wrong-state instead of replaying
   byte-for-byte as §7.3 requires. Lookup happens right after the 428 check.
2. **Natural replay for idempotent actions.** If the resource's latest
   transition is the same action with the same input digest and the state
   equals the action's `effect.to`, invocation returns the current document
   without re-running the handler. This is what makes the Part III "safety
   truth" double-invoke test pass *by observation* for state-changing
   idempotent actions, and it protects handlers like `payments.charge` from
   double execution.
3. **`unavailable` includes out-of-state transitions** (with
   `becomes_available.in_states`), matching the §3 *example* rather than the
   §3 invariant's literal `transitions_from(state)`. The conformance
   completeness check verifies: `actions ⊆ transitions_from(state)` and
   `actions ∪ unavailable ∪ hidden == all declared non-bulk transitions`.
4. **`depth=summary` = full envelope minus `display`.** §4.1 only says
   "links carry summaries only"; we interpret summary depth as the agent
   default and strip the presentation namespace. Collection items therefore
   carry no `display`; the generic UI humanizes tokens client-side as a
   fallback.
5. **Reversibility check approximates "unconditional-or-time-based"**: the
   reverse edge must exist for *every* source state and must not be hidden
   or role-token-gated. Deeper guard classification was punted.
6. **Missing `If-Match` on a `requires_if_match` action → 412** (same as
   stale), not 428. The spec names only the mismatch case.

## Extensions beyond the spec (would need spec text if upstreamed)

- **`ctx.read(Resource, id)`** — the read half of cross-resource guards
  (§14 only shows `ctx.invoke`). Loads in the same transaction.
- **Server-synthesized `x-display.label`** — every action-input field and
  every query parameter carries a label (precedence: action `field_display`
  map → `Field(json_schema_extra={"x-display": …})` → title-cased field
  name). Rationale: labels must be server-emitted to be localizable; clients
  render, never invent.
- **SSE `Last-Event-ID` resume** — the event `id` is the global transition
  id; reconnecting with `Last-Event-ID` replays from the log (the paused-
  subscription dance in `events.py` keeps replay and live delivery ordered).
- **The `job` resource kind** — auto-registered by the Engine for deferred
  bulk (§7.4's 202 path). Its state factory ships with the conformance
  suite, not the app. Bulk actions declare `defer_over=N` to opt in.
- **Bulk declaration** — the spec never shows how `cancel_many` is declared;
  here it's `@action(..., bulk=True, atomic=False, max_items=N,
  defer_over=N)` on the resource, rendered only on the collection.
- **The generic HTML client** at `{base}/-/ui` — Part IV's "human generic
  client" made real; principal switching via the dev `X-Principal-*` headers.

## Known gaps / not implemented

- **i18n** (the one agreed deferral): all strings English;
  `waymark extract-messages` is a stub; the per-locale conformance check
  from §12 does not exist.
- **Alembic migration emission** (§11.1 "the engine emits the Alembic
  migration"): **not implemented** — `alembic` is a declared dependency but
  unused. Dev/test/boot use `storage.create_all()`. Adding a column to
  `filterable` on an existing deployment requires a hand-written migration
  for the generated column + index. This is the largest unpaid promise.
- **Date-time generated columns are TEXT**, cast at query time —
  `::timestamptz` is only STABLE and Postgres rejects it in generated
  columns. Numeric range filters use their indexes; `*_after` filters
  effectively don't. Fine at small scale; revisit with an immutable
  conversion function if it matters.
- **`x-facets` counts** are computed only for `state` (one GROUP BY per
  collection GET), not for other enum filters.
- **`guard.rate_limit` is in-memory per process** — honest `retry_at`
  rendering, but not a distributed limiter.
- **Recursive Pydantic models** keep their `$ref` (schemas are otherwise
  fully inlined); nothing in the example exercises this.
- **Embedding profiles resolve single-resource links only**; a profile
  naming a collection-shaped link is skipped. `load_many` exists so
  cross-item batching is wired, but collection GETs don't accept
  `depth=expanded:*`.

## Operational caveats

- **One dispatcher per process.** Multiple uvicorn workers each tail the
  log independently — subscribers on different workers all get events
  (correct), but there's no shared backpressure. Slow SSE consumers get
  events dropped after a 1000-item queue with a warning log.
- **Idempotency purge is manual** — `IdempotencyStore.purge(now=…)` honors
  the 24h retention; nothing schedules it. Cron it or accept table growth.
- **`Engine(base_path=…)` must match the `include_router(prefix=…)`** —
  hrefs are rendered from `base_path`, routing from the prefix; they don't
  see each other.
- **Testing SSE needs real sockets**: httpx's `ASGITransport` buffers whole
  responses, so infinite streams hang. `tests/server/test_events.py` spins
  an in-process uvicorn; conformance's events test subscribes to the
  dispatcher directly.
- **Conformance registrations live in the *root* `conftest.py`** — the
  suite is collected from inside the installed package, so only rootdir
  conftests apply to it.
- **`asyncio_default_fixture_loop_scope` must be `"function"`** (asyncpg
  connections are loop-bound; session-scoped fixture loops cause
  "another operation is in progress").

## Adding a resource: the checklist

1. `uv run waymark new-resource thing` (or copy `app/resources/order.py`).
2. Define states (StrEnum), `Data`, transitions with **explicit**
   `idempotent/reversible/confirm`, guards with `else_` reasons.
3. Register in `Engine(resources=[...])`.
4. Root `conftest.py`: one `@state_factory(Thing)` (walk real transitions),
   plus `@example_input` for any action whose guards need semantically valid
   input (the suite tells you which, by name, when you forget).
5. `uv run waymark check` → `uv run pytest --waymark=thing` → green means
   wire format, enforcement, audit, events, and safety semantics all agree
   with your definition. Review the definition module for *business*
   correctness — that's the part no suite can check.
