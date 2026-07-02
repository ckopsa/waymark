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
activity feed + live refresh come from the SSE stream. A principal switcher
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
