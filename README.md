# Waymark

**An affordance-oriented hypermedia format and server framework for mixed
human/agent clients.**

Waymark applications are **declarative resource definitions** — state
machines with guarded transitions. Everything else is a mechanical projection
of those definitions: routing, serialization, validation, the authorization
surface, live events, documentation, the agent tool surface, and the
conformance test suite. The full specification is
[`waymark-spec.md`](waymark-spec.md); the wire format alone is
[`docs/format.md`](docs/format.md); deviations, extensions, known gaps, and
operational caveats are in
[`docs/implementation-notes.md`](docs/implementation-notes.md) — read that
before extending the engine.

The one-sentence pitch: **the advertised affordances and the enforced rules
cannot drift**, because one guard function is both the advertisement
(`unavailable.reason`) and the enforcement (the 409 detail), and the
conformance suite proves it by invoking everything.

## Waymark 2.0

This repository also contains **`waymark2/`** — the 2.0 framework designed
in [`docs/waymark2-design.md`](docs/waymark2-design.md) from v0.1's
retrospective: declarative guards (one `accepts` set is both the rendered
enum and the enforcement; no AST inspection), typed `Ref` fields, structured
`@action` declarations (`Safety`/`Edit`/`Bulk`/`PartScope`), drafts as
sub-resources with per-field revisions (`waymark-relay/2`), a project/resolve
render split, one cross-process bus, emitted SQL migrations, and a
conformance suite that synthesizes inputs from the acceptance sets
(`pytest --waymark2`), and **agent links** — least-privilege agent access
negotiated as ordinary resources (default-deny tokens, per-field
clear/hashed views, per-action open/approval modes, human-filled arguments;
[`docs/agent-links.md`](docs/agent-links.md)). The meal-plan app
(`mealplan/`) runs on it; the v0.1 example shop (`app/`) remains on
`waymark/`. Build-time deviations and caveats:
[`docs/waymark2-notes.md`](docs/waymark2-notes.md).

## Waymark 3.0

**`waymark3/`** is the 3.0 framework designed in
[`docs/waymark3-design.md`](docs/waymark3-design.md) from 2.0's
retrospective — v2's law aimed at the delivery pipeline: *every concern is
a declaration, a resource, or an event class*. What that buys, concretely:
**visibility is projection, not redaction** (a grant-scoped principal's
envelope is built already-narrowed; `apply_scope` is gone), grants take
**instance selectors** ("edit access to *this* meal plan"), `Relation` and
`OneOf` catch the cross-field logic that fell off v2's declaration cliff,
`Vocab` folds filterable/faceted/membership into one field type, events
split into declared **transition/observation classes** (the `peek=1` flag
died to a lookup route class), the rate limiter rides the bus, data shapes
carry declared **upcasts**, approval-mode create composes, and the
enterprise seams land as engine kinds: **`member`** (invite → first-login
bind via the **OIDC relying party** in `waymark3/server/oidc.py`),
**`subscription`** (the transition log delivered as signed webhooks), and
**`Mirror`** (external truth as a full citizen with honest sync states —
observed external changes are system-actor transitions). The meal-plan
app migrated per the design's sketch lives in **`mealplan3/`**
(`make mealplan3`, `pytest --waymark3`). Build-time deviations and caveats:
[`docs/waymark3-notes.md`](docs/waymark3-notes.md).

## The later lineage: 7 → 10

The line continued fork-by-fork, each version a design doc plus a full
framework plus the meal-plan app migrated onto it: **`waymark7/`**
(`docs/waymark7-design.md`, `make conformance`), **`waymark8/`** — the law
becomes data: guards and derivations as expression trees, fingerprinted and
diffable (`docs/waymark8-design.md`, `make conformance8`), and
**`waymark9/`** — the law binds the row's judgment: every row is judged by
the law revision stamped on it (`docs/waymark9-design.md`,
`make conformance9`).

**`waymark10/`** is the current head: a ground-up **Clojure** rewrite
(wire format `"10"`, a clean break) where the law is a form — the tree the
reviewer diffs, the fingerprint stores, the wire carries, and the
interpreter evaluates are one value. Engine, conformance library, the
affordance-following client (`waymark10.client`, spec Part IV enforced),
the CLI (`clojure -M:cli`), and the envelope-driven generic UI
(`GET /api/-/ui`) live in `waymark10/`; the dogfood app is
**`mealplan10/`**. Design record with the 9→10 wire divergence table:
[`docs/waymark10-design.md`](docs/waymark10-design.md).

```bash
make test10            # waymark10 framework tests (Clojure, Postgres :5433)
make test-mealplan10   # mealplan10 conformance + the family-week story
make dev10             # serve mealplan10 on :8010 (UI at /api/-/ui)
```

Everything below this line describes the original v0.1 `waymark/` package.

## Status

Complete implementation of spec v0.1 **except i18n** (all human strings are
English; `waymark extract-messages` is a stub) and **Alembic migration
emission** (schema management is `create_all`; see implementation notes).
Reference stack: Python ≥ 3.12, FastAPI, Pydantic v2, SQLAlchemy 2.0 async,
PostgreSQL (JSONB + LISTEN/NOTIFY; filterable/sortable fields promoted to
indexed generated columns).

Documented deviations from the spec text:

- **Idempotency replay happens before the state check** (§11.3 pseudocode
  puts it after guards) — otherwise a retried state-changing action gets a
  409 instead of the byte-identical replay §7.3 promises.
- **Idempotent actions get "natural replay"**: if the latest transition is
  the same action with the same input digest and the state matches its
  outcome, the invocation returns the current document without re-running
  the handler — double-invoke is replay-safe by construction.
- `unavailable` includes out-of-state transitions with
  `becomes_available.in_states` (matching the §3 example; the §3 invariant is
  checked as "actions ⊆ transitions_from(state) and the union covers all
  declared transitions").
- `depth=summary` renders the full envelope minus the `display` namespace
  (the agent default: presentation is quarantined payload agents never act on).

## Quickstart

```bash
brew services start postgresql@17
createdb waymark_dev && createdb waymark_test

uv sync --extra testing

uv run uvicorn app.main:app          # the example shop (order/shipment/return)
curl -s localhost:8000/api/.well-known/waymark | jq
open http://localhost:8000/api/-/ui  # the generic human client (see below)
open http://localhost:8000/docs      # every action with its real input schema
```

Talk to it (the write surface is uniform: reads are GET on `links`, writes
are POST on `actions`):

```bash
ORDER=$(curl -s -X POST localhost:8000/api/orders \
  -H 'Idempotency-Key: demo-1' -H 'X-Principal-Id: dana' \
  -d '{"items":[{"sku":"A-100","qty":2,"price":12.10}],"total":84.20,"currency":"USD"}' \
  | jq -r .self)
curl -s -X POST "localhost:8000$ORDER/-/place" -H 'X-Principal-Id: dana' | jq .actions
curl -sN "localhost:8000$ORDER/-/events" &     # live transition stream (SSE)
```

Agent demo (plans over `effect.to`, hard-stops at `safety.confirm`):

```bash
uv run python scripts/agent_demo.py
```

## Defining a resource

```python
class Order(Resource):
    kind = "order"
    State = OrderState            # a StrEnum
    Data = OrderData              # a Pydantic model, stored as JSONB
    initial = OrderState.DRAFT
    terminal = {OrderState.FULFILLED, OrderState.CANCELLED}
    summary = "Order #{id} · {data.total:.2f} {data.currency} · {state.label}"
    filterable = filterable(state=filterable.Eq | filterable.In,
                            total=filterable.Range)
    sortable = sortable("placed_at", "total", default="-placed_at")

    @action(from_=OrderState.AWAITING_PAYMENT, to=OrderState.PAID,
            input=SubmitPayment, guards=[payment_method_valid],
            idempotent=True, reversible=False, confirm=False,
            requires_if_match=True)
    async def submit_payment(self, inp: SubmitPayment, ctx: Ctx) -> None:
        await ctx.services.payments.charge(self, inp)
```

Every §10.1 rule is enforced at import time (dead states, missing safety
fields, `reversible=True` without a reverse edge, unresolvable guard
templates, handler signature mismatches). `uv run waymark check` is the CI
fast path.

Wire it up (§13 — applications add no routes):

```python
engine = waymark.Engine(resources=[Order, Shipment, Return],
                        storage="postgresql+asyncpg://localhost/waymark_dev",
                        principal=my_auth_dependency, services=Services())
app.include_router(engine.router, prefix="/api")
```

## The conformance suite

Applications supply exactly one thing per resource — a state factory — plus
example inputs where schema generation can't satisfy semantic guards, in the
**root** `conftest.py`:

```python
@state_factory(Order)
async def make_order(state, engine, services) -> Order: ...

@example_input(Order, "submit_payment")
def submit_payment_example(services): ...
```

```bash
uv run pytest --waymark            # full matrix: resources × states × principals
uv run pytest --waymark=Order      # one resource
uv run pytest --waymark-walk       # random-walk the real machines
uv run waymark check               # import-time checks only
```

Green `--waymark` means the wire format, the enforcement, the audit trail,
the events, and the declared safety semantics all agree with the definition
module. Review attention goes where the suite can't: are these the *right*
states, guards, and blast radii for the business.

## The generic human client

Every engine serves a browsable UI at `GET {base}/-/ui` — Part IV's "human
generic client" as one self-contained HTML page. It consumes only the
envelope and invents no affordances: buttons are `actions` (styled by
`display`, gated by `confirm`), disabled-with-reason entries are
`unavailable`, forms are generated from each action's `input` schema with
blur-time `dry_run` validation, the filter bar is the collection `query`
schema (with live `x-facets` counts), collection rows are a table derived
from the items' scalar data fields with honest per-row buttons, and the
activity feed + live refresh come from the SSE stream. Forms for
`collab=True` actions join the draft's live channel (format §2.3): presence,
collaborators' edits applied as they land, every update drained into the
shared server-side draft. A principal switcher
(dev `X-Principal-*` headers) lets you watch the affordance surface change
per role. Add a resource to the engine and the UI can browse and operate it
with zero frontend changes.

## Programmatic clients

```python
from waymark.client import AgentClient, mcp_tools

agent = AgentClient("http://localhost:8000", headers=auth)
doc = await agent.fetch("/api/orders/8812")
pending = await agent.act(doc, "cancel", {"reason": "dup"})  # confirm-gated
after = await pending.confirm()                              # human said yes
tools = mcp_tools(doc)   # {kind}.{action} tool list for an agent harness
```

The agent client refuses to construct URLs, treats `safety.confirm` as a
hard stop, auto-manages `Idempotency-Key`s and `If-Match`, pre-validates via
`?dry_run=1`, plans over learned `effect.to` graphs, and surfaces divergence
instead of improvising.

The same client is a CLI — one shell call per affordance, for humans and
shell-driven agent harnesses alike:

```bash
export WAYMARK_AS=claude::Claude          # dev principal (id[:type[:Display]])
waymark client index
waymark client get /api/orders/8812
waymark client act /api/orders/8812 submit_payment --json '{"payment_method_id":"…"}'
waymark client act /api/orders/8812 cancel        # confirm-gated → exit 3, no-op
waymark client act /api/orders/8812 cancel --confirmed   # the human said yes
```

Exit codes make the Part IV rules scriptable: `2` not afforded (with the
server's reason), `3` confirmation required, `4` declared-effect divergence.
A per-server session file (`~/.waymark/cli/`) persists idempotency keys and
the learned state graph across invocations, so retries replay instead of
duplicating and `waymark client plan <href> <goal-state>` can route over
previously seen states.

## Layout

```
waymark/core      DSL, state machine, registry, schemas, import-time checks
waymark/server    engine, render, invoke, router, events (SSE), storage, jobs
waymark/testing   conformance suite, pytest plugin, walker, factories
waymark/client    typed client + agent client + MCP projection
waymark/cli       check | routes | openapi | new-resource | extract-messages
app/              example shop: Order, Shipment, Return workflow
tests/            unit/integration tests for the framework itself
```

## Development

```bash
uv run pytest                # framework tests (needs waymark_test database)
uv run pytest --waymark      # conformance against the example app
WAYMARK_TEST_DSN=postgresql+asyncpg://localhost/waymark_test  # override
```
