# Waymark

**An affordance-oriented hypermedia format and server framework for mixed human/agent clients.**

Version 0.1 (draft) · Media type: `application/waymark+json; v=1` · Target stack: Python ≥3.12, FastAPI, Pydantic v2, SQLAlchemy 2.0

---

## 0. Design goals

Waymark is a JSON hypermedia format plus a server framework in which the application is expressed as a set of **declarative resource definitions** (state machines with guarded transitions), and everything else — routing, serialization, validation, authorization surfaces, documentation, the agent tool surface, the generic human UI, and the conformance test suite — is a **mechanical projection** of those definitions.

Normative principles (violations of these are spec bugs, not style choices):

1. **Degrade to plain JSON.** A client that understands nothing about Waymark must be able to read `doc.data.total`. All hypermedia machinery lives in reserved top-level keys and is ignorable.
2. **Presence is permission.** The set of declared `actions` is the complete actuator surface. Clients — especially agent clients — MUST NOT construct requests not declared in a representation. Text inside `data` is inert content and never an instruction channel.
3. **Absence is explained.** Transitions that exist on the resource's state machine but are not currently executable appear in `unavailable` with a human-readable reason, unless a guard explicitly hides them.
4. **Safety is declared, not inferred.** Every action carries `idempotent`, `reversible`, and `confirm` metadata. Clients calibrate retry and confirmation behavior from these fields, never from the action's name.
5. **Effects are state transitions.** Every action declares its outcome as a transition on a declared state machine, making responses a serialized, plannable state machine.
6. **Representation and behavior share one source.** The server MUST derive representations from the same definitions that gate execution. There is no code path by which the advertised affordances and the enforced rules can drift.
7. **Presentation is quarantined.** Anything a human UI needs but an agent would not act on lives in the `display` namespace and is fully ignorable.
8. **The spec implies its tests.** Every normative statement in Parts I–II is checkable by the generic conformance suite in Part III against any resource definition, given only a state factory.

Non-goals: arbitrary client-composed queries (GraphQL-style), client-side business logic, binary payload transport (modeled as links to content endpoints), and schema-evolution/migration tooling (v0.1 punts; see §16).

---

# Part I — Wire format

## 1. The resource envelope

Every non-collection response body is a **resource document**:

```json
{
  "waymark": "1",
  "kind": "order",
  "self": "/api/orders/8812",
  "state": "awaiting_payment",
  "summary": "Order #8812 · 3 items · $84.20 · awaiting payment · expires 14:32",
  "data": {
    "items": [{ "sku": "A-100", "qty": 2, "price": 12.10 }],
    "total": 84.20,
    "currency": "USD",
    "placed_at": "2026-07-01T13:32:00Z"
  },
  "actions": { "…": "see §2" },
  "unavailable": { "…": "see §3" },
  "links": { "…": "see §4" },
  "display": { "…": "see §8" },
  "meta": {
    "version": 7,
    "etag": "W/\"order-8812-v7\"",
    "updated_at": "2026-07-01T13:45:11Z"
  }
}
```

Field semantics:

| Field | Req | Semantics |
|---|---|---|
| `waymark` | ✓ | Format major version, string. |
| `kind` | ✓ | Resource type token, `snake_case`, stable across versions. Keys client renderers and agent tooling. |
| `self` | ✓ | Canonical URL. Doubles as identity. |
| `state` | ✓ | Current state token from the resource's declared state machine. Machine-readable; display string lives in `display.state`. |
| `summary` | ✓ | One-line, server-rendered, localized prose orientation of the whole resource. Budget: ≤140 chars. Exists so a client (or agent) can decide whether to care without reading `data`. |
| `data` | ✓ | The plain-JSON representation. MUST validate against the resource's published data schema. MUST NOT contain affordance information. |
| `actions` | ✓ | Executable transitions from the current state for the current principal. May be `{}`. |
| `unavailable` | ✓ | Declared-but-not-executable transitions, with reasons. May be `{}`. |
| `links` | ✓ | Traversal affordances (safe GETs). May be `{}`. |
| `display` |  | Presentation hints (§8). Servers MAY omit; clients MUST tolerate absence. |
| `meta` | ✓ | Concurrency and cache metadata. `version` is a monotonically increasing integer; `etag` mirrors it. |

Unknown top-level keys MUST be ignored by clients. Servers MUST NOT put semantics agents should act on outside `state`, `data`, `actions`, `unavailable`, `links`.

## 2. Actions

`actions` is an object keyed by **stable action name** (the name is API contract; the `href` is opaque and may change):

```json
"actions": {
  "submit_payment": {
    "method": "POST",
    "href": "/api/orders/8812/-/submit_payment",
    "input": {
      "type": "object",
      "required": ["payment_method_id"],
      "properties": {
        "payment_method_id": { "type": "string", "format": "uuid" },
        "tip": { "type": "number", "minimum": 0 }
      },
      "additionalProperties": false
    },
    "effect": { "to": "paid" },
    "safety": {
      "idempotent": true,
      "reversible": false,
      "confirm": false,
      "requires_if_match": true
    },
    "display": { "label": "Pay now", "style": "primary", "order": 1 }
  },
  "cancel": {
    "method": "POST",
    "href": "/api/orders/8812/-/cancel",
    "input": {
      "type": "object",
      "properties": { "reason": { "type": "string", "maxLength": 500 } }
    },
    "effect": { "to": "cancelled", "terminal": true },
    "safety": { "idempotent": true, "reversible": false, "confirm": true },
    "display": { "label": "Cancel order", "style": "danger", "order": 9 }
  }
}
```

Normative rules:

- **`method`** is always `POST` in v0.1. Uniform invocation deliberately trades REST-verb aesthetics for a single, cacheable client rule: *reads are GET on `links`, writes are POST on `actions`.* (`GET`-shaped affordances are `links` or collection queries, §5.)
- **`input`** is inline JSON Schema (Draft 2020-12), generated from a Pydantic model (§10.3). Small schemas (< ~4 KB) MUST be inlined; larger ones MAY be `{"$ref": "/api/schemas/SubmitPayment"}`. Servers MUST validate submitted bodies against exactly this schema before guard evaluation. Absent `input` means the action takes an empty body.
- **`effect.to`** names the destination state. `effect.terminal: true` marks entry into a terminal state. Optional `effect.emits` lists declared side-effect events (e.g. `"email:receipt"`) — informational, so clients and reviewers can see blast radius.
- **`safety.idempotent`** — a client MAY silently retry on ambiguous network failure. Non-idempotent actions require an `Idempotency-Key` header (§7.3).
- **`safety.reversible`** — `true` iff the destination state declares a transition back whose guard is unconditional-or-time-based. Renderers derive undo affordances from the *post-action* representation, not from this flag; the flag exists for pre-action client judgment.
- **`safety.confirm`** — server-side judgment that a human should confirm and an agent MUST pause for user confirmation before invoking. Agents treat this as binding.
- **`safety.requires_if_match`** — invocation MUST carry `If-Match` with the current etag; server responds `412` with a fresh representation on mismatch (§7.2).

## 3. Unavailable

Transitions declared on the state machine but not currently executable:

```json
"unavailable": {
  "refund": {
    "reason": "Order has not been paid. Refund becomes available in state 'paid'.",
    "becomes_available": { "in_states": ["paid"] }
  },
  "expedite": {
    "reason": "Expedited shipping requires manager approval for orders over $500.",
    "becomes_available": { "requires": "role:manager" }
  }
}
```

- `reason` is required, localized prose, and MUST be generated from the failing guard's declared explanation (§10.4) — never hand-written per endpoint.
- `becomes_available` is optional structured hope: `in_states`, `at` (RFC 3339 timestamp), or `requires` (opaque requirement token).
- Guards may be declared `hide=True` (e.g., existence-concealing permission checks), in which case the action appears in neither `actions` nor `unavailable`. Hiding is the exception and must be opted into per guard.
- Invariant checked by conformance: for any principal, `keys(actions) ∪ keys(unavailable) ∪ hidden(principal) == transitions_from(state)`. Nothing falls through the cracks silently.

## 4. Links

Safe traversals. Each link carries enough context to decide whether to follow it:

```json
"links": {
  "customer": {
    "href": "/api/customers/331",
    "kind": "customer",
    "summary": "Dana K. · member since 2023 · 2 open orders"
  },
  "parent": { "href": "/api/orders", "kind": "order_collection", "summary": "All orders" },
  "events": { "href": "/api/orders/8812/-/events", "kind": "event_stream", "summary": "Live transitions (SSE)" }
}
```

Reserved link relations: `self` (top-level, not in `links`), `parent` (breadcrumb chain), `events` (subscription affordance, §6), `collection` (containing collection). All other relation names are application vocabulary.

**Embedding.** A link MAY carry an `embedded` key containing a full resource document at the negotiated depth. Embedding is *always* the server honoring a depth request (§4.1), never a substitute for the `href`.

### 4.1 Depth negotiation

Clients request representation depth per request:

```
GET /api/orders/8812?depth=summary        # links carry summaries only (agent default)
GET /api/orders/8812?depth=full           # full data, no embedding (spec default)
GET /api/orders/8812?depth=expanded:checkout   # named embedding profile (human client)
```

Embedding profiles are declared per resource (§10.6): a profile names which links embed and at what depth, so human clients get one-round-trip screens while agent clients get small documents. Servers MUST honor `summary` and `full`; unknown profile names degrade to `full` with a `Waymark-Depth: full` response header.

## 5. Collections

Collections are first-class resources (`kind` conventionally `{kind}_collection`):

```json
{
  "waymark": "1",
  "kind": "order_collection",
  "self": "/api/orders?state=awaiting_payment&sort=-placed_at&page[size]=25",
  "state": "ok",
  "summary": "Orders · 12 of 147 shown · filtered: state=awaiting_payment",
  "data": {
    "items": [ { "…": "resource documents at depth=summary" } ],
    "total": 147,
    "page": { "size": 25, "number": 1 }
  },
  "actions": {
    "create": { "method": "POST", "href": "/api/orders", "input": { "…": "…" },
                "effect": { "to": "draft" },
                "safety": { "idempotent": false, "reversible": true, "confirm": false } },
    "query": {
      "method": "GET",
      "href": "/api/orders",
      "input": {
        "type": "object",
        "properties": {
          "state": { "type": "string", "enum": ["draft","awaiting_payment","paid","fulfilled","cancelled"],
                     "x-facets": { "awaiting_payment": 12, "paid": 98 } },
          "total_gte": { "type": "number" },
          "total_lte": { "type": "number" },
          "placed_after": { "type": "string", "format": "date-time" },
          "sort": { "type": "string", "enum": ["placed_at","-placed_at","total","-total"] },
          "page[size]": { "type": "integer", "maximum": 100, "default": 25 },
          "page[number]": { "type": "integer", "minimum": 1 }
        }
      },
      "safety": { "idempotent": true, "reversible": true, "confirm": false }
    },
    "cancel_many": {
      "method": "POST",
      "href": "/api/orders/-/cancel_many",
      "input": { "type": "object", "required": ["ids"],
                 "properties": { "ids": { "type": "array", "items": { "type": "string" }, "maxItems": 500 },
                                  "reason": { "type": "string" } } },
      "effect": { "to": "cancelled", "bulk": true },
      "safety": { "idempotent": true, "reversible": false, "confirm": true }
    }
  },
  "links": {
    "next": { "href": "/api/orders?...&page[number]=2", "kind": "order_collection", "summary": "Page 2 of 6" },
    "prev": null
  }
}
```

- The **`query` action** is the machine-readable filter/sort contract; its schema is generated from the resource's declared `filterable`/`sortable` sets (§10.6) and rendered by generic clients as the filter bar. `x-facets` counts are optional and best-effort.
- The **`query` action is the only GET-shaped action**, exempted from the POST rule because it is safe by construction; its `input` maps to query parameters.
- **Bulk actions** (`effect.bulk: true`) evaluate guards per item and return a **partial-success report** (§7.4), never all-or-nothing, unless declared `atomic: true`.
- Items MUST be full envelopes at `depth=summary` (with `actions`/`unavailable` computed), so list rows can render honest per-row buttons. Servers concerned about cost MAY declare a collection `row_affordances=False`, in which case items carry `"actions": null` (explicitly unknown, distinct from `{}` = none) and clients fetch the resource before acting.

## 6. Events

Every resource exposes an `events` link (SSE). Events are **transitions**, in a fixed envelope:

```
event: transition
data: {
  "kind": "order", "self": "/api/orders/8812",
  "action": "submit_payment", "from": "awaiting_payment", "to": "paid",
  "actor": { "type": "agent", "id": "claude-session-9f2", "display": "Claude (for Dana K.)" },
  "at": "2026-07-01T13:45:11Z",
  "version": 8,
  "summary": "Payment submitted · $84.20 via card •• 4421"
}
```

`actor.type ∈ {human, agent, system}`. The event stream is the audit log is the activity feed is the cache-invalidation signal; clients re-fetch (or patch `state`/`meta.version` optimistically) on receipt. A workspace-level stream at `/api/-/events?kinds=order,shipment` supports dashboards. Delivery is at-least-once; clients dedupe on `(self, version)`.

## 7. Invocation, errors, concurrency, idempotency

### 7.1 Invoking an action

```
POST /api/orders/8812/-/submit_payment
Content-Type: application/json
If-Match: W/"order-8812-v7"
Idempotency-Key: 018f3c…        (required when idempotent=false)

{ "payment_method_id": "…" }
```

Success → `200` with the **post-transition resource document** (never a bare status). The new document's `actions` are how clients discover undo, next steps, etc.

**Dry run:** append `?dry_run=1`. Server runs schema validation and guards, performs no transition, returns `200 {"valid": true}` or the error document below with `422`. This is the inline-validation affordance; it MUST be side-effect free.

### 7.2 Errors are hypermedia

Errors extend RFC 9457 `application/problem+json` with Waymark affordances:

```json
{
  "type": "https://waymark.dev/problems/guard-failed",
  "title": "Payment method expired",
  "status": 409,
  "detail": "Card ending 4421 expired 05/2026. Update it, then retry payment.",
  "action_attempted": "submit_payment",
  "state": "awaiting_payment",
  "errors": { "payment_method_id": ["Card expired 05/2026"] },
  "actions": {
    "update_payment_method": { "method": "POST", "href": "/api/customers/331/-/update_payment_method", "…": "…" }
  },
  "resource": { "…": "fresh resource document, depth=summary" }
}
```

Canonical statuses: `422` schema-invalid input (with per-field `errors`), `409` guard refused (with the guard's `reason`, i.e. exactly the string that would have appeared in `unavailable`), `412` version conflict, `404` not-found-or-hidden, `403` only when concealment is not required. Every error response answers "what would a competent person do next" via `actions`.

### 7.3 Idempotency

Non-idempotent actions require `Idempotency-Key`. The server stores `(key → response)` for ≥24h and replays the stored response byte-for-byte on retry; a reused key with a different request body → `409 problem:idempotency-key-reuse`.

### 7.4 Bulk report

```json
{
  "kind": "bulk_report", "action": "cancel_many",
  "data": {
    "succeeded": 37, "refused": 3, "failed": 0,
    "refusals": [
      { "self": "/api/orders/8791", "reason": "Already shipped; cancellation unavailable after fulfilment." }
    ]
  },
  "links": { "job": null }
}
```

If execution is deferred, the response is instead `202` with a **workflow resource** (§10.7) representing the job — progress, per-item outcomes, and a `cancel` action, all in ordinary Waymark.

## 8. The display namespace (presentation hints)

Everything humans need and agents ignore. Quarantine rule: **no client may change *what it does* based on `display`; only *how it shows it*.**

```json
"display": {
  "title": "Order #8812",
  "state": { "label": "Awaiting payment", "tone": "warning", "icon": "clock" },
  "groups": [
    { "id": "main", "label": "Items", "fields": ["items", "total"] },
    { "id": "detail", "label": "Details", "fields": ["currency", "placed_at"], "collapsed": true }
  ]
}
```

Per-action `display`: `label`, `description`, `style ∈ {primary, default, danger}`, `group`, `order`, `icon`. Per-field hints ride the JSON Schema as `x-display: {label, help, widget, order}`. All display strings are server-localized via `Accept-Language`; `summary`, `unavailable.reason`, and problem `detail` are likewise localized. Conformance checks display-string coverage per declared locale (§12).

## 9. Discovery and the agent tool surface

`GET /api/.well-known/waymark` returns the index: format version, resource kinds, root collection links, locales, and profiles. Additionally, every resource document is mechanically projectable to an MCP-style tool list — each entry in `actions` maps to `{name: f"{kind}.{action}", description: display.description or generated, input_schema: input}` — and the reference client library ships this projection, so an agent harness can mount "whatever this resource currently affords" as its tool surface with zero application code. This projection is derived, never hand-maintained.

---

# Part II — Server framework (`waymark` Python package)

Target: Python ≥3.12, FastAPI ≥0.115, Pydantic v2, SQLAlchemy 2.0 (async), Postgres as the reference database (LISTEN/NOTIFY for events). Everything below is the *engine*: written once, generic, heavily tested. Application code is resource definitions only.

## 10. Resource definitions (the DSL)

### 10.1 Shape of a definition

One resource per module. The definition is the spec, the docs, the contract, and the test oracle; it must fit on roughly one screen and be reviewable by a domain expert who reads no other code.

```python
# app/resources/order.py
from __future__ import annotations
from datetime import timedelta
from enum import StrEnum
from pydantic import BaseModel, Field
from waymark import (
    Resource, action, guard, Allow, Deny, Ctx,
    filterable, sortable, profile, emits,
)

class OrderState(StrEnum):
    DRAFT = "draft"
    AWAITING_PAYMENT = "awaiting_payment"
    PAID = "paid"
    FULFILLED = "fulfilled"
    CANCELLED = "cancelled"

class LineItem(BaseModel):
    sku: str
    qty: int = Field(ge=1)
    price: float = Field(ge=0)

class OrderData(BaseModel):
    items: list[LineItem]
    total: float
    currency: str = Field(pattern="^[A-Z]{3}$")
    placed_at: AwareDatetime | None = None
    paid_at: AwareDatetime | None = None

class SubmitPayment(BaseModel):
    payment_method_id: UUID
    tip: float = Field(default=0, ge=0)

class CancelInput(BaseModel):
    reason: str | None = Field(default=None, max_length=500)

# ── Guards ──────────────────────────────────────────────────────────
@guard(else_="Payment method is invalid or expired. Update it, then retry.",
       remedies=["customer.update_payment_method"])
async def payment_method_valid(r: "Order", inp: SubmitPayment, ctx: Ctx) -> Allow | Deny:
    pm = await ctx.services.payments.get_method(inp.payment_method_id)
    return Allow() if pm and not pm.expired else Deny(
        errors={"payment_method_id": [f"Card expired {pm.expiry:%m/%Y}"]} if pm else None
    )

@guard(else_="Refund window closed on {deadline:%Y-%m-%d}.",
       becomes_available_at=lambda r: r.data.paid_at + timedelta(days=30))
async def within_refund_window(r: "Order", inp, ctx: Ctx) -> Allow | Deny:
    deadline = r.data.paid_at + timedelta(days=30)
    return Allow() if ctx.now < deadline else Deny(vars={"deadline": deadline})

manager_only = guard.role("manager",
    else_="Requires manager approval.",
    requires_token="role:manager")

# ── Resource ────────────────────────────────────────────────────────
class Order(Resource):
    kind = "order"
    State = OrderState
    Data = OrderData

    initial = OrderState.DRAFT
    terminal = {OrderState.FULFILLED, OrderState.CANCELLED}

    summary = "Order #{id} · {data.items|len} items · {data.total:.2f} {data.currency} · {state.label}"

    filterable = filterable(
        state=filterable.Eq | filterable.In,
        total=filterable.Range,          # → total_gte / total_lte
        placed_at=filterable.After,      # → placed_after
    )
    sortable = sortable("placed_at", "total", default="-placed_at")

    profiles = {
        "checkout": profile(embed={"customer": "summary", "items": "full"}),
    }

    # ── Transitions ────────────────────────────────────────────────
    @action(from_=OrderState.DRAFT, to=OrderState.AWAITING_PAYMENT,
            idempotent=True, reversible=True,
            display=dict(label="Place order", style="primary"))
    async def place(self, inp: None, ctx: Ctx) -> None:
        self.data.placed_at = ctx.now

    @action(from_=OrderState.AWAITING_PAYMENT, to=OrderState.PAID,
            input=SubmitPayment, guards=[payment_method_valid],
            idempotent=True, reversible=False, requires_if_match=True,
            side_effects=emits("email:receipt", "webhook:order.paid"),
            display=dict(label="Pay now", style="primary"))
    async def submit_payment(self, inp: SubmitPayment, ctx: Ctx) -> None:
        await ctx.services.payments.charge(self, inp)
        self.data.paid_at = ctx.now

    @action(from_={OrderState.DRAFT, OrderState.AWAITING_PAYMENT},
            to=OrderState.CANCELLED,
            input=CancelInput,
            idempotent=True, reversible=False, confirm=True,
            display=dict(label="Cancel order", style="danger"))
    async def cancel(self, inp: CancelInput, ctx: Ctx) -> None:
        ...

    @action(from_=OrderState.PAID, to=OrderState.PAID,   # self-transition
            guards=[within_refund_window, manager_only],
            idempotent=False, reversible=False, confirm=True,
            side_effects=emits("payment:refund"),
            display=dict(label="Refund", style="danger"))
    async def refund(self, inp: "RefundInput", ctx: Ctx) -> None:
        await ctx.services.payments.refund(self, inp)
```

Rules the engine enforces at import time (fail-fast, before the app serves a request):

- Every non-initial, non-terminal state is reachable and can exit (no accidental dead states) unless annotated `allow_dead=True`.
- Every `@action` names `from_`, `to`, and all four safety fields (safety is never defaulted-by-omission; `idempotent`/`reversible`/`confirm` have no implicit values — TypeError if missing).
- Guard `else_` templates reference only variables the guard's `Deny(vars=…)` can supply.
- Action handler signatures match the declared `input` model.
- `reversible=True` is verified against the machine: a declared transition `to → from_` must exist, else import error (the flag is *checked*, not trusted).

### 10.2 Guards

```python
Guard = Callable[[ResourceInstance, InputModel | None, Ctx], Awaitable[Allow | Deny]]
```

- Declared with `@guard(else_=…, hide=False, remedies=[…], becomes_available_at=…, requires_token=…)`.
- `Deny` may carry `vars` (for the `else_` template), `errors` (per-field), and `retry_at`.
- Composable: `g1 & g2` (all must allow; first Deny wins and supplies the reason), `g1 | g2`.
- **Dual-phase execution, single implementation:** at *render* time each guard runs in `probe` mode with `inp=None` to compute `actions` vs `unavailable`; at *invoke* time it runs with the validated input. Guards that need input to decide return `Allow(pending_input=True)` from probe — the action renders as available, and the guard actually gates at invocation. This is the mechanism behind principle 6: one guard function is both the advertisement and the enforcement.
- Built-ins: `guard.role(name)`, `guard.owner(field="customer_id")`, `guard.in_state(…)` (implicit from `from_`), `guard.rate_limit(…)`, `guard.feature_flag(…)`. Permission guards default `hide=False`; pass `hide=True` for existence-concealing checks (renders `404` on invoke, absent from both affordance maps).

### 10.3 Schemas

Action `input` schemas are `Model.model_json_schema(mode="validation")` with `x-display` merged from `Field(json_schema_extra=…)` or the action's `field_display` map. The resource's `Data` schema is published at `/api/schemas/{kind}`. Schemas are generated at import time, cached, and byte-stable across processes (canonical key ordering) so the conformance suite can diff them in CI.

## 11. Persistence (SQLAlchemy 2.0)

### 11.1 Storage model

Default: the engine owns the tables. Per resource:

```python
class OrderRow(WaymarkRow, Base):
    __tablename__ = "orders"
    id: Mapped[UUID] = mapped_column(primary_key=True, default=uuid7)
    state: Mapped[str] = mapped_column(String(64), index=True)
    version: Mapped[int] = mapped_column(default=1)
    data: Mapped[dict] = mapped_column(JSONB)          # validates against OrderData
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(onupdate=func.now())
```

`data` as JSONB, validated through the Pydantic model at every read/write boundary, is the default because it keeps schema evolution in one artifact. Fields named in `filterable`/`sortable` are promoted to **generated columns** with indexes (the engine emits the Alembic migration), so query affordances compile to indexed SQL, not JSONB scans. Teams that need fully relational storage implement the `Storage` protocol (`load`, `save`, `query`, `append_transition`) — ~5 methods — and keep everything else.

### 11.2 The transition log (single table, four jobs)

```python
class Transition(Base):
    __tablename__ = "waymark_transitions"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)   # global ordering
    kind: Mapped[str]; resource_id: Mapped[str]
    action: Mapped[str]; from_state: Mapped[str]; to_state: Mapped[str]
    version: Mapped[int]                     # resource version after transition
    actor_type: Mapped[str]; actor_id: Mapped[str]; actor_display: Mapped[str]
    input_digest: Mapped[str]                # sha256 of canonical input; raw input NOT stored by default
    correlation_id: Mapped[str | None]       # groups workflow-driven cascades
    summary: Mapped[str]                     # pre-rendered, localized at write in default locale
    at: Mapped[datetime]
```

This one append-only table is: (a) the audit trail, (b) the SSE **outbox** — a dispatcher tails it via Postgres `LISTEN/NOTIFY` with polling fallback and fans out to subscribers, so events are transactional with the state change, (c) the activity feed / notification source, (d) the idempotency anchor. There is no second eventing system to drift.

### 11.3 The transition algorithm (the whole engine, honestly)

Every write in the application is this one function:

```python
async def invoke(kind, id, action_name, body, *, principal, if_match, idem_key, dry_run):
    async with storage.session() as s:                       # one txn
        r = await storage.load(s, kind, id, for_update=True)  # SELECT … FOR UPDATE
        defn = registry[kind].actions[action_name]            # 404 if unknown
        check_state(r, defn.from_)                            # 409 wrong-state → unavailable reason
        check_if_match(r, if_match, defn)                     # 412 + fresh doc
        inp = defn.input.model_validate(body) if defn.input else None   # 422 + field errors
        for g in defn.guards:
            if isinstance(res := await g(r, inp, ctx), Deny):
                raise GuardRefused(g, res)                    # 409 + reason + remedies
        if dry_run: return Valid()
        if idem_key and (hit := await idem.lookup(s, idem_key)): return hit  # replay
        await defn.handler(r, inp, ctx)                       # domain effect
        r.state = defn.to; r.version += 1
        t = await storage.append_transition(s, r, defn, principal)
        await idem.store(s, idem_key, render_later_ref(t))
        await s.commit()
    return await render(r, principal=principal, depth=requested_depth)
```

Properties worth stating: exactly one row lock per invocation; the transition row commits atomically with the state change; side effects declared in `emits` are dispatched by outbox consumers *after* commit (at-least-once, so external effects like `payments.charge` that must be in-transaction are called in the handler and must themselves be idempotent — the spec requires handlers of `idempotent=True` actions to be safe under replay, and the conformance suite exercises this).

## 12. Rendering

```python
async def render(r, *, principal, depth, locale) -> WaymarkDoc
```

For each declared transition from `r.state`: run guards in probe mode → sort into `actions` / `unavailable` / hidden; generate `summary` from the template; attach `links` from declared relations; resolve embedding per depth profile (batched loads — the engine issues one query per embedded relation across the whole document, never per-link, which is how the N+1 the format invites gets killed centrally); merge `display` with locale catalogs. Rendering is pure given `(row, principal, depth, locale, now)` — property-testable, and cacheable keyed on `(etag, principal-scope, depth, locale)`.

Localization: all human strings (`summary` templates, `else_` templates, display labels) are message-catalog keys under the hood; `waymark extract-messages` emits the catalog; missing translations are conformance failures per declared locale, falling back to the default locale at runtime.

## 13. FastAPI integration

```python
# app/main.py
import waymark
from app.resources import order, shipment, customer, return_workflow

app = FastAPI()
wm = waymark.Engine(
    resources=[order.Order, shipment.Shipment, customer.Customer, return_workflow.Return],
    storage=waymark.PostgresStorage(engine),
    principal=get_principal,          # FastAPI dependency → Principal(id, type, roles, locale)
    services=Services,                # DI container passed to guards/handlers via ctx.services
)
app.include_router(wm.router, prefix="/api")
```

Generated routes per resource (uniform; applications add none):

```
GET    /api/{plural}                    collection + query affordance
POST   /api/{plural}                    create (initial-state transition)
GET    /api/{plural}/{id}               resource document
POST   /api/{plural}/{id}/-/{action}    invoke (+ ?dry_run=1)
POST   /api/{plural}/-/{bulk_action}    bulk invoke
GET    /api/{plural}/{id}/-/events      SSE, per-resource
GET    /api/-/events                    SSE, filtered firehose
GET    /api/schemas/{name}              published schemas
GET    /api/.well-known/waymark         index
```

The engine also emits an OpenAPI overlay so FastAPI's `/docs` shows every action with its real input schema — derived, like everything else.

`Ctx` fields: `principal`, `now` (injectable clock), `locale`, `services`, `session` (the ambient SQLAlchemy session — handlers share the invocation transaction), `correlation_id`.

## 14. Workflow resources

A workflow is a `Resource` whose handlers invoke transitions on other resources **through the engine** (never by mutating rows directly), sharing the transaction and `correlation_id`:

```python
class Return(Resource):
    kind = "return"
    State = ReturnState            # awaiting_item → inspecting → refunding → done | rejected
    spans = (Order, Shipment)

    @action(from_=ReturnState.INSPECTING, to=ReturnState.REFUNDING,
            guards=[item_condition_ok, order_is(OrderState.PAID)],
            idempotent=True, reversible=False, confirm=True)
    async def approve(self, inp, ctx):
        await ctx.invoke(Order, self.data.order_id, "refund", {...})
```

Cross-resource invariants get their explicit home here: `order_is(...)`, `customer_not_suspended`, etc. are ordinary guards that read other resources. The engine records the cascade under one `correlation_id`, so the audit trail shows the workflow transition and its child transitions as a unit. Long-running bulk jobs (§7.4) are engine-provided workflow resources (`kind="job"`) with progress in `data` and a `cancel` action.

## 15. Security posture

- Authentication is upstream (any FastAPI auth dependency → `Principal`). Authorization is **only** guards; the engine has no other permission concept, so the affordance surface and the enforcement surface are provably identical.
- Actions are the sole actuator surface; `data` is inert. The reference agent client refuses to construct URLs and treats `safety.confirm` as a hard stop pending user approval — giving a real prompt-injection boundary: injected text can at most *ask*; only declared, guarded, confirm-gated affordances can *do*.
- CSRF: browser clients use standard token/SameSite defenses; the uniform POST rule makes the write surface trivially enumerable for middleware.
- Rate limits are declarable guards, so exhaustion renders as an honest `unavailable` with `retry_at`, not a mystery 429.
- The transition log is the forensic record: every state change has an actor, an action, a version, and an input digest.

## 16. Explicit v0.1 punts

State-machine *migrations* (states removed while live rows occupy them): v0.1 requires a `renames`/`maps_to` declaration and refuses to boot if any row's state is unmapped — crude but safe. Binary content: modeled as links to ordinary content endpoints. Multi-region/offline sync, per-field authorization redaction, and schema version negotiation: out of scope, noted for v0.2.


---

# Part III — Conformance suite

The suite is generic: it knows Waymark, not orders. Applications supply exactly one thing per resource — a **state factory**:

```python
# tests/conftest.py
from waymark.testing import state_factory

@state_factory(Order)
async def make_order(state: OrderState, db, services) -> Order:
    """Return a persisted Order in the requested state, by any means honest
    (walking real transitions preferred; direct construction allowed)."""
```

Then:

```
pytest --waymark            # full suite, all resources × states × principals
pytest --waymark=Order      # one resource
waymark check               # import-time checks only (CI fast path)
```

Generated test matrix, per resource × state × principal-profile (principal profiles are declared in test config: `anonymous`, `owner`, `manager`, `agent`):

**Representation.** Document validates against the envelope schema; `data` validates against the published `Data` schema; `summary` ≤140 chars and non-empty; every state token has display coverage in every declared locale; `meta.version` matches storage.

**Affordance completeness.** `actions ∪ unavailable ∪ hidden(principal) == transitions_from(state)`, computed per principal profile. Every `unavailable` entry has a non-empty localized `reason`. Every hidden action invoked directly returns `404`.

**Transition truth.** For every available action: build minimal valid input via schema-driven generation (Hypothesis `from_schema`, with per-action example overrides where generation can't satisfy semantic guards); invoke; assert resulting `state == effect.to`, `version` incremented by 1, a transition row appended with correct actor, and the response is the post-transition document. For every `unavailable` action: invoke anyway; assert `409` whose `detail` equals the advertised `reason` (advertisement and enforcement produce the same string, mechanically).

**Safety truth.** `idempotent=True`: invoke twice with identical input; second call must succeed and leave `version` advanced at most once beyond the first (replay-safe by observation, not by declaration). `idempotent=False`: invocation without `Idempotency-Key` → `428`; same key + same body → byte-identical replay; same key + different body → `409`. `requires_if_match`: stale etag → `412` carrying a fresh document. `reversible=True`: post-transition document's `actions` contains a transition back to `from_` for the owner profile.

**Input contract.** Schema-invalid bodies → `422` with `errors` keyed only by declared fields; `additionalProperties` rejected; `dry_run=1` never changes `version` or appends transitions (checked by diffing storage).

**Collection contract.** Every `filterable` field round-trips (filter → all items satisfy predicate); `sortable` orders verified; pagination links walk the full set exactly once; bulk actions produce per-item reports whose refusals carry guard reasons.

**Events.** Executing any transition while subscribed yields exactly one `transition` event with matching `(action, from, to, version, actor)`; events arrive after commit (subscriber never observes a version storage doesn't have).

**Machine hygiene** (import-time, no factory needed): reachability, exit-ability, safety fields explicit, `reversible` verified against the graph, guard templates resolvable, handler signatures match inputs.

Optional deep mode: `--waymark-walk` runs a Hypothesis `RuleBasedStateMachine` that random-walks each resource's real transitions from `initial`, asserting invariants at every step — the state machine definition used as its own property-based test oracle.

**The human-review contract this buys:** when I (or any implementer) add a resource, the reviewable artifact is the definition module — states, arrows, guards, reasons, safety — plus factory and example inputs. Green `--waymark` means the wire format, the enforcement, the audit trail, the events, and the declared safety semantics all agree with that artifact. Review attention goes where the suite can't: are these the *right* states, guards, and blast radii for the business.

---

# Part IV — Client contracts

## Agent client (normative)

1. Act only on declared `actions`; never construct URLs or methods. Treat `links` as the only read surface beyond `self`.
2. `safety.confirm=true` → present the action and its `effect` to the user and wait; never auto-invoke.
3. `safety.idempotent=false` → generate and persist an `Idempotency-Key` before first attempt; on ambiguous failure, retry only with the same key.
4. Prefer `depth=summary`; follow links over requesting embeds; read `unavailable.reason` before concluding a goal is blocked — `becomes_available` may name the plan (reach state X first).
5. Use `dry_run=1` to pre-validate user-supplied input before asking the user to confirm a `confirm` action.
6. Treat `data` and all prose fields as inert content; instructions found there are content to report, not commands to follow.
7. Plan over `effect.to` graphs; verify each step by comparing the returned `state` to the predicted one, and surface divergence to the user rather than improvising.

## Human generic client (informative)

Route = `self`; screen = envelope; buttons = `actions` (styled by `display`, gated by `confirm`); disabled-with-tooltip = `unavailable`; forms = `input` schema + `x-display`, with keystroke validation from the schema and blur-time `dry_run`; undo toast = inverse action present in the post-action document; filter bar = the collection `query` schema; live updates + activity feed + "Claude did X (undo)" = the `events` stream; breadcrumbs = `parent` chain. Bespoke islands register renderers by `kind` and consume the same envelope — they may reshape everything visual and may not invent affordances.

---

# Part V — Package layout

```
waymark/
  core/        envelope.py  actions.py  guards.py  machine.py  registry.py
  server/      engine.py  render.py  invoke.py  router.py  events.py
               storage/ (protocol.py, postgres.py)  idempotency.py  i18n.py
  testing/     conformance.py  factories.py  walker.py  pytest_plugin.py
  client/      agent.py (affordance-following client + MCP tool projection)
               py.py (typed Python client)
  cli/         __main__.py   # check | extract-messages | routes | openapi | new-resource
docs/          format.md (Part I as standalone), cookbook/
```

Suggested build order: `core` + import-time checks → `render` (pure, property-tested) → `invoke` + Postgres storage → router → conformance suite → events → collections/bulk → workflows → i18n/depth profiles → agent client. The conformance suite lands *before* the feature surface widens, because from that point forward it is the definition of done.
