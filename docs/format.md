# The Waymark wire format (v0.1)

**Media type:** `application/waymark+json; v=1`

This document is Part I of the Waymark specification, published standalone.
It defines the JSON hypermedia format; the server framework and conformance
suite are described in the full spec (`waymark-spec.md` at the repo root).

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
- **`input`** is inline JSON Schema (Draft 2020-12), generated from a Pydantic model (§10.3). Small schemas (< ~4 KB) MUST be inlined; larger ones MAY be `{"$ref": "/api/schemas/SubmitPayment"}`. Servers MUST validate submitted bodies against exactly this schema before guard evaluation. Absent `input` means the action takes an empty body. Servers SHOULD tighten a field to an instance-derived `enum` when a guard's acceptance set is knowable from the current document (§10.2 `admits`) — the rendered form MUST NOT offer a value the server already knows it will refuse. Edit-shaped actions SHOULD carry the document's current values as field `default`s (§10.1 `prefill`), fenced by `requires_if_match` — editing is not re-authoring.
- **`effect.to`** names the destination state. `effect.terminal: true` marks entry into a terminal state. Optional `effect.emits` lists declared side-effect events (e.g. `"email:receipt"`) — informational, so clients and reviewers can see blast radius.
- **`safety.idempotent`** — a client MAY silently retry on ambiguous network failure. Non-idempotent actions require an `Idempotency-Key` header (§7.3).
- **`safety.reversible`** — `true` iff the destination state declares a transition back whose guard is unconditional-or-time-based. Renderers derive undo affordances from the *post-action* representation, not from this flag; the flag exists for pre-action client judgment.
- **`safety.confirm`** — server-side judgment that a human should confirm and an agent MUST pause for user confirmation before invoking. Agents treat this as binding.
- **`safety.requires_if_match`** — invocation MUST carry `If-Match` with the current etag; server responds `412` with a fresh representation on mismatch (§7.2).

### 2.1 Scoped actions: the `parts` namespace

An action whose input identifies an item of a `data` array MAY additionally be
rendered per item, under the optional top-level `parts` key:

```json
"parts": {
  "days": {
    "key": "date",
    "items": [
      { "key": "2026-07-14",
        "actions": {
          "assign_meal": {
            "method": "POST", "href": "/api/plans/88/-/assign_meal",
            "input": { "type": "object",
              "properties": {
                "date": { "type": "string", "format": "date", "const": "2026-07-14" },
                "meal_id": { "type": "string", "x-display": {
                  "widget": "resource", "kind": "meal",
                  "params": { "state": "on_list", "theme": "mexican" } } } } },
            "…": "effect/safety/display as in §2" } } }
    ]
  }
}
```

- `parts.<field>` mirrors `data.<field>` (an array of objects); `key` names the
  item field that identifies a row, and each entry's `key` value matches it.
- The scope key field is bound with `const`: clients submit it, humans are
  never re-asked for context the screen already shows.
- Picker `params` MAY be resolved per item (e.g. filtered by the row's theme).
- `parts` is a *refinement*: top-level `actions` remains the complete truth,
  and an item appears only when the action's advertised acceptance set admits
  its key. Clients that ignore `parts` lose convenience, not capability.
  Servers omit `parts` at `depth=summary`.

### 2.2 Drafts: effort is server-state

An action declared draftable persists per-principal partial input server-side,
advertised on its entry:

```json
"actions": {
  "update_recipe": {
    "…": "…",
    "draft": {
      "href": "/api/meals/437d/-/update_recipe/draft",
      "values": { "recipe": "half-written…" },
      "saved_at": "2026-07-04T06:32:23Z",
      "stale": false
    }
  }
}
```

- `PUT {href}` stores partial input (fields must be a subset of the action's
  schema; values may be invalid mid-edit — full validation happens on invoke,
  as ever). `GET {href}` returns the draft's current truth (`204` when none)
  — clients MUST read it when opening a form rather than trusting a document
  rendered before typing began. `DELETE {href}` discards. A successful invoke
  consumes the draft.
- `values`/`saved_at`/`stale` appear only for the draft's author; other
  principals see just the `href`. `stale: true` means the resource has
  changed since the draft was saved — clients MUST surface this rather than
  silently restore over newer edits.
- Because the draft lives in the envelope, it survives devices and browsers,
  and an agent reading the document can see and help finish a human's
  half-written effort (or vice versa).

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

One widget value is defined: `{widget: "resource", kind, params?}` marks a field holding another resource's id. Human clients SHOULD render it as a picker populated from that kind's collection (index → collection, optionally narrowed by `params` as query parameters), showing each item's `summary` and submitting its id — still envelope-driven, no invented affordances. Clients that don't implement it fall back to a plain text field, so the hint is purely progressive.

## 9. Discovery and the agent tool surface

`GET /api/.well-known/waymark` returns the index: format version, resource kinds, root collection links, locales, and profiles. Additionally, every resource document is mechanically projectable to an MCP-style tool list — each entry in `actions` maps to `{name: f"{kind}.{action}", description: display.description or generated, input_schema: input}` — and the reference client library ships this projection, so an agent harness can mount "whatever this resource currently affords" as its tool surface with zero application code. This projection is derived, never hand-maintained.

---
