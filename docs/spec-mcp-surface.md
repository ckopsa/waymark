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

## Built (2026-08-22, waymark-4mk)

It landed as a **module**, which was the second decision recorded after the
spec was written and the more consequential one: `{:module :mcp :routes
mcp-routes/routes :pack packs/mcp}` is the whole entry in
`waymark10/modules.clj` — no kind, no lifecycle hook, no line anywhere else.
It is the first module built *on* the modularization seams
([spec](spec-modularization.md)) rather than retrofitted onto them, and the
seams held: an engine assembled without `:mcp` has no `/api/-/mcp` and owes
none of its six obligations.

Two namespaces, split where a second transport would want the seam:

- `server/mcp.clj` — the six tools and MCP's JSON-RPC exchange as a function
  of one parsed message. No HTTP anywhere in it.
- `server/routes/mcp.clj` — Streamable HTTP: `POST /api/-/mcp`, JSON in, JSON
  out, riding the router's own identity middleware.

The **door** is the shape worth recording. Each tool builds a ring request and
sends it through `router/assemble-routes` — core's real routes, in process, no
socket — carrying the principal and visibility the outer boundary *already*
resolved. It wears `router/wrap-problems` and deliberately does not wear
`wrap-identity`: identity resolves once, at the edge, and a tool that could
re-authenticate itself is a door into somebody else's session. The alternative,
each tool reaching for `collections/envelope` and `inv/invoke!` itself, is how
a second and quietly divergent copy of the concealment checks gets written.

Because the tools go through the real routes, grant projection is not
implemented here at all — it is *inherited*. An ungranted kind is absent from
`waymark_discover`, its schema 404s, its rows 404, its actions 404, and none of
that is code in this module.

### Readings and trades

- **`waymark_invoke` reads the row before it moves it.** Not overhead: the read
  is what makes concealment honest before a verb is composed, it is where the
  consequence sentence and the ETag come from, and it is what lets the tool
  follow the envelope's *own* action href instead of building an address out of
  string parts — rule 1 of `waymark10.client`, and the reason a prompt-injected
  `POST /api/plans/{id}/-/delete_everything` has nothing to hold on to. The
  same read supplies rules 3 and 4: a non-idempotent action gets an
  Idempotency-Key, a fenced one gets `If-Match` of the row the agent saw.
- **`id` is optional on `waymark_invoke`, and that is the create door.** The
  spec's six say "one action on one row"; an agent with no grant would then
  have had no way to file an `approval_request`, which is how it asks for
  everything else. Omitting `id` posts the collection, and the action must be
  the kind's declared create verb. A seventh tool for create would have been
  the wrong trade — the six are a *promise*.
- **The confirm gate is the one refusal MCP issues in its own voice.**
  Everything else an agent is told here is the engine's own RFC 9457 document,
  passed through byte for byte. The gate reads `display.description` — the same
  accessor `waymark10.client` uses, deliberately, because two readings of one
  sentence is a gate that can be walked around — and compares exactly. A
  paraphrase is a gate a model talks its way through.
- **Refusals are tool output with `isError`, never JSON-RPC errors.** An agent
  learns from an honest refusal and nothing from a fault. The one exception is
  an unknown *tool name*, which is a protocol error because MCP says so.
- **`waymark_schema` is a static reading of the declaration** — action inputs,
  safety, consequence sentences, effort — beside the real
  `/api/schemas/{kind}` answer. It does not pretend to be an envelope entry:
  no row exists there, so acceptance sets are unfolded and availability is
  unjudged, and the tool says so. It *is* projected: `render/project-input-js`
  went public so the argument a grant denies is absent here for the same
  reason it is absent from an envelope.
- **`waymark_history` is the one tool with no route beneath it.**
  `GET /api/{plural}/{id}/-/history` is [time travel](spec-time-travel.md)'s,
  unbuilt. It reads the transition log directly, but only *after* reading the
  row through the real route — that read is the whole of its authorization, so
  a concealed row 404s before the log is touched. When the history route lands,
  this tool becomes a call onto it like the other five (waymark-zp5).
- **The conformance pack proves the flow end to end**, through `POST
  /api/-/mcp` and back out of a tool result, never against the tool layer
  directly: the handshake (including that an unknown protocol revision is
  negotiated, not refused — MCP's next release must not be a waymark outage),
  that `tools/list` is exactly six, that a minted-and-accepted grant makes one
  kind visible and another *absent*, that `grant.revoke` refuses without its
  sentence and runs with it, and that a guard's refusal reaches the model as
  the guard's own words. The fixture is core's `grant` kind, so every
  obligation holds on an engine with no application kinds at all.

### Punts kept, and one added

Every punt above stayed put — no streaming (a `GET` on the door answers 405
saying so out loud), no worksheet upload, tool descriptions still generated
from prose written for humans. The **prompt-injection** punt was about
mitigation only: saying so plainly was not deferred, and `/api/-/welcome` now
carries a `trust` section naming row data untrusted input, with the same
sentence riding MCP's `instructions` at `initialize`.

Two new punts, both filed:

- **stdio** (waymark-iwi). The CLI is an HTTP *client* against a remote
  base-url, so it holds no engine to serve from; stdio wants either a new
  in-process entry point or a stdio↔HTTP proxy. Neither is the cheap wiring
  the bead hoped for. The tool layer is already split for it.
- **The welcome doc cannot advertise the MCP door**, because a module has no
  way to contribute a line to a core document and the contribution table is
  closed at four on purpose (spec-modularization § the closure). Recorded
  rather than solved: a fifth column to publish one href is a worse trade than
  an agent finding `/api/-/mcp` in its client config, which is where MCP
  clients look anyway.
