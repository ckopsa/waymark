# Implementation notes (handoff)

What you need beyond `waymark-spec.md` and the README to work on this
codebase: where the spec and the implementation differ, what was added beyond
the spec, what's deliberately unfinished, and the operational caveats that
aren't visible from the happy path.

The human-usability program (admits, scope/parts, the `UsabilityWarning`
checks) is catalogued here as shipped; where it goes next — the rules not yet
verified and their proposed mechanisms — lives in `usability-roadmap.md`.

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
- **`ctx.find(kind, **filters)`** — the list half of cross-resource reads:
  a filtered query (collection query field names) in the same transaction.
  Enables "the active rotation"-style lookups from guards and hooks.
- **`Resource.on_create(ctx)`** — engine hook after create-validation,
  before the first insert, with full `Ctx` (`read`/`find`/`invoke`); for
  initial data that depends on other resources. Mutates `data` only — the
  initial state stays the machine's. Not run on create dry-runs (its
  `ctx.invoke` side effects must not fire).
- **Create dry-run** — `POST /{plural}?dry_run=1` validates the create body
  and returns `{"valid": true}` without creating (mirrors action dry-run;
  the generic UI's blur-time validation needs it).
- **`x-display` `{widget: "resource", kind, params?}`** — reference-field
  hint (§8 reserves `widget`); the generic UI renders a picker populated
  from that kind's collection.
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
- **`@guard(admits=(field, fn))`** — a guard declares its acceptance set for
  one input field. `fn(r)` derives it from the document; `fn(r, ctx)` (sync
  or async) may `ctx.read`/`ctx.find` other resources or use services — the
  GET render path carries an engine-wired ctx, so cross-resource sets (e.g.
  the linked rotation's themes) advertise at render time. Returning `None`
  declines to constrain that render. Render folds the result into the
  advertised input schema as an `enum` (intersected when several guards
  admit the same field). Advertisement only: the guard body stays the
  enforcement, and the conformance gap check verifies the two agree (admits
  guards are evaluated with an engine-wired ctx there too). This is the
  "error prevention" usability rule made mechanical — the form never offers
  a value the server already knows it will refuse, and never renders a blank
  the server could have filled with choices.
- **The collab seam** (`@action(draft=True, collab=True)`, format §2.3) —
  the draft entry advertises `draft.collab {href, protocol:
  "waymark-relay/1"}`, a WebSocket relay (`server/collab.py`) whose every
  accepted update is persisted through the draft-PUT path *before* it is
  acked or relayed (the drain rule). Collab drafts are **shared**: stored
  under the `"*"` principal sentinel, rendered for every principal, and the
  plain draft `PUT`/`GET`/`DELETE` route to the shared row (a PUT broadcasts
  into the room). A successful invoke or a DELETE closes the room with
  `{"type": "closed", reason}`. `collab=True` without `draft=True` is a
  decoration-time TypeError — the channel is a property of the draft it
  drains into.
- **Usability warnings at import time** (`UsabilityWarning`, §10.1 layer):
  `open_input` — a guard judges exactly one input field that carries no
  guidance: no enum/const, no `admits`, no picker widget (checked against
  the registry-grade schema, so `field_display`-granted widgets count). The
  warning's hint depends on the guard: ctx-free → `admits=(f, fn(r))`;
  ctx-reading → `admits=(f, fn(r, ctx))`, since the render ctx can read what
  the guard reads (fix: `admits`, a tighter schema, a widget, or
  `@action(waives=("open_input",))`); `altitude` — an input field judged by a
  ctx-free guard mirrors the items of a `data` array, i.e. the form re-asks
  which item the user is already looking at (fix: `scope` the action to the
  item, or `waives=("altitude",)`). Guard purity and field usage come from
  AST inspection of the guard source (`_scan_dependencies` in
  `core/guards.py`), conservative on unreadable source or wholesale `inp`
  escape.
- **`prefill` and the edit-shape checks** — `@action(prefill=("recipe", …))`
  renders the document's current values as schema `default`s (editing is not
  re-authoring); the generic UI and agents get filled forms for free. Two
  import-time warnings enforce the shape: `blank_edit` (input fields mirror
  top-level Data fields — optionality ignored — but no prefill declared) and
  `unfenced_edit` (prefill without `requires_if_match=True`: a prefilled form
  is a snapshot, and without the etag fence editors silently clobber each
  other). Conformance `test_prefill_truth` asserts rendered defaults equal
  the document's current values.
- **Server-side drafts (`draft=True`)** — declared effort must not be
  losable. The engine persists per-principal partial input in
  `waymark_drafts` (engine-owned, like idempotency), exposed at
  `GET/PUT/DELETE {self}/-/{action}/draft` and advertised on the action entry
  (`draft.href`, plus `values`/`saved_at`/`stale` for the author only).
  Draft bodies are stored as-is (invalid-mid-edit is fine; full validation
  happens on invoke); fields outside the action schema are 422. A successful
  invoke consumes the draft in the same transaction; `stale` flags drafts
  whose base version the resource has outrun. The generic UI autosaves
  (debounced PUT, flushed on dismissal so keystrokes inside the debounce
  window survive; disarmed on submit/discard so a pending save can't
  resurrect a consumed draft), GETs the draft fresh on every form open (the
  page envelope is a snapshot from before typing began), re-renders on
  dialog close, and prefers draft values over prefill defaults. Because
  drafts live in the envelope, they survive browsers and devices, and an
  agent can see and finish a human's half-written effort. Import-time
  `large_effort` warns when a *required* prose-widget input has no draft.
  Caveats: drafts are per-action, not per-scope-key (a scoped draftable
  action shares one draft across parts — don't combine yet); nothing purges
  abandoned drafts (they're tiny; add a cron if it matters). `long_text` now
  also covers action input models, so unbudgeted long-form inputs must
  declare `widget: "prose"` — which is also what makes the generic UI render
  a textarea instead of a one-line input.
- **Long-form text and the `long_text` check** — a Data string field with no
  `max_length`, or one admitting ≥ 280 chars (two summary budgets), warns at
  import time unless it declares `x-display {widget: "prose"}` (the generic
  UI renders a scrollable pre-wrap block on detail pages and keeps the
  column out of every table), a real budget, or `hidden`/`raw`. The UI also
  defensively drops any table column whose observed strings exceed ~120
  chars — declared or not, a column of paragraphs orients nobody. Length
  budgets are a wire contract, not a style preference.
- **Reference fields and the `opaque_ref` check** — raw ids are machine
  plumbing, not human information. Data fields (top-level or list-item)
  declare `x-display {widget: "resource", kind, label_field?}`; the generic
  UI renders them as navigable references — labeled by the denormalized
  sibling (`label_field`, whose own column is then dropped) or by the
  referenced resource's summary, lazily fetched. `hidden: true` drops a
  field from human display; `raw: true` acknowledges a deliberate raw id.
  Enforcement: `check_opaque_refs` runs at Engine assembly (it needs every
  kind registered) and warns on any `{kind}_id` data field carrying none of
  those hints; ids not matching a registered kind (external systems) are out
  of scope. The UI reads hints from the published data schema
  (`GET /schemas/{kind}`, cached), so this stays server-declared, never
  client-invented; collection rows and breadcrumbs likewise identify
  themselves by summary/title with the id relegated to href/tooltip.
- **Scoped actions and the `parts` namespace** —
  `@action(..., scope=("days", "date"))` re-renders the action once per item
  of `data.days` under a new top-level envelope key:
  `parts: {days: {key: "date", items: [{key, actions}, …]}}`. Per part, the
  key field becomes a `const` (the generic UI submits it silently and never
  asks), and picker params templated over the item —
  `field_display={"meal_id": {"params": {"theme": "{item.theme}"}}}` —
  resolve to that item's values, giving per-row, context-filtered pickers.
  Per-item availability falls out of the `admits` intersection for the key
  field (e.g. `set_sunday_theme` appears only on the Sunday part). Top-level
  `actions` stays the complete truth (agents and old clients lose nothing;
  unresolvable `{item.*}` params are stripped there); `parts` is a
  refinement, omitted at `depth=summary`. The generic UI renders parts
  actions as per-row buttons and drops them from the top action bar.
- **Conformance `test_schema_guard_gap`** — fuzzes the *rendered* input
  schema (hypothesis-jsonschema) and evaluates ctx-free guards directly:
  a guard declaring `admits` must accept everything its own advertisement
  emits; an undeclared ctx-free guard judging exactly one input field (the
  static `open_input` scope, honoring the same waive token) refusing >50% of
  schema-valid inputs fails as a schema-guard gap. Multi-field ctx-free
  guards (cross-field constraints, decision inputs like `condition_ok`) are
  out of scope — refusing a decision the user just made is honest workflow,
  not a gap.

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
- **`pytest -n auto` is supported and ~6× faster** — every DSN constant is
  wrapped in `waymark.testing.per_worker_dsn`, which routes each xdist
  worker to its own database (`waymark_test_gw0`, …, created on first use
  with `synchronous_commit = off`; the schema-per-test fixtures make a
  shared database worker-hostile). Serial runs are unaffected: without
  `PYTEST_XDIST_WORKER` the DSN passes through untouched.
- **`asyncio_default_fixture_loop_scope` must be `"function"`** (asyncpg
  connections are loop-bound; session-scoped fixture loops cause
  "another operation is in progress").
- **Collab rooms are per-process.** Participants connected to different
  uvicorn workers won't see each other's live updates (the *drain* still
  makes every update visible via draft GET / envelope render — liveness
  degrades, truth doesn't). Pin collab traffic to one worker or extend
  `CollabRooms.broadcast` over LISTEN/NOTIFY like the event dispatcher.
- **The collab endpoint gates on resource visibility, not per-principal
  affordance** — a principal for whom the action is `unavailable` (or
  hidden) can still join the room in v0.1. Gate at the principal dependency
  if that matters before it's fixed properly.
- **Browsers can't set headers on WebSocket upgrades**, so the dev
  `header_principal` also accepts `principal-id`/`principal-type`/
  `principal-roles`/`principal-display` query params (the generic UI uses
  them for the collab channel). Production auth should use cookies or a
  real dependency — §15 still applies.

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
