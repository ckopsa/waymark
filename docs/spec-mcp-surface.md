# Spec — MCP as a generated surface

**Thesis.** Every fact an agent needs to drive a waymark engine is already on
the wire, and no agent can use it without bespoke glue. An MCP server generated
*from the declaration* makes any waymark engine natively drivable, with grants
as the safety boundary — no per-app code, ever.

## Epistemic status

This is the highest reach-per-line item in the set, and the least novel: MCP is
someone else's protocol and waymark would merely speak it. Its claim to a spec
is that waymark is unusually well-shaped for it. Most systems bolting on MCP
must invent tool descriptions, argument schemas, and permission semantics.
Waymark has all three already, as data, because it had to have them for the
generic UI.

## What exists

- `GET /api/.well-known/waymark` — kinds, their hrefs, their action names, nav
  tier, domains, and the resolved principal.
- `GET /api/schemas/:kind` — the published JSON Schema, `x-ref`/`x-display`
  and all, already projected per grant (`grants/project-json-schema`).
- Every envelope carries its available actions **with their input schemas**,
  their `:display` prose, their `safety` (`:idempotent :reversible :confirm`,
  and the `:one-way`/`:consequence` sentences), and their refusal reasons.
- `demand.clj` — each action's `effort` class (assent / selection / recall /
  composition), already on the wire.
- `GET /api/-/welcome` — a document that *already exists to teach an invited
  agent its protocol*. This spec is that document made executable.
- `server/grants.clj` + `members.clj` — an agent sees nothing ungranted, reads
  included; the ask→approve→`X-Waymark-Grant` loop is built.

## The design

**Not one tool per action.** Fifteen kinds × ~6 actions is ninety tools before
the household adds anything; it drowns the model's tool list and re-generates
on every law change. Instead, a **fixed small toolset over a dynamic surface**:

| tool | purpose |
|---|---|
| `waymark_discover` | kinds, domains, and what this principal may see |
| `waymark_schema` | one kind's schema + its actions, inputs, safety prose |
| `waymark_query` | the collection grammar — filters, sort, page, facets |
| `waymark_get` | one row, its fields, links, and available actions |
| `waymark_invoke` | one action on one row, with input |
| `waymark_history` | the row's transitions (pairs with [time travel](spec-time-travel.md)) |

Six tools, stable across every waymark engine forever. The *interesting*
surface — which kinds, which actions, which fields — arrives as data through
`discover`/`schema`, which is exactly how the generic UI already works. An
agent that can read a schema needs no bespoke tool.

**Safety rides the declaration.** `waymark_invoke` refuses to run an action
whose `safety.confirm` is true unless the call carries
`acknowledge: "<the consequence sentence>"` echoed back. The engine already
computes and renders that sentence; MCP makes echoing it the price of a
dangerous verb. Likewise `:dry-run` is a first-class argument, so an agent can
probe before it writes — beat 3 of the hand-in-hand story, for free.

**Auth.** The existing bearer. An agent onboards through the invite door
(`/auth/agent`) exactly as it does today; the MCP server holds the token and
nothing about the grant model changes. An ungranted kind is simply absent from
`discover` — concealment, not refusal, which is the posture `router.clj`
already takes.

**Transport.** stdio for a local agent, HTTP for a hosted one. The HTTP flavor
is a thin route inside the existing router rather than a second deployable, so
it inherits the OIDC middleware and the identity gate untouched.

## Recorded punts

- **Streaming.** SSE events (`server/events.clj`) map to MCP notifications, but
  the first cut is request/response only. A follow behaviour ("tell me when
  this row moves") wants [addressed notice](spec-addressed-notice.md) first.
- **Worksheets.** Binary upload through MCP is possible and unpleasant; the
  first cut points at the HTTP door instead.
- **Tool descriptions.** Generated from `:display` prose, which was written for
  humans. Expect to find kinds whose prose reads badly out of context — a
  useful forcing function on the declarations themselves.
- **Prompt injection.** An engine's row data reaches the model as tool output.
  Nothing here changes waymark's posture; it does mean row *summaries* are now
  an untrusted-input surface, and the welcome doc should say so plainly.

## Effort

**Small-to-medium**, and unusually predictable: six tools, each a thin call
onto a route that exists, plus a server skeleton. The risk is not the code,
it is deciding to own a second protocol's compatibility surface.
