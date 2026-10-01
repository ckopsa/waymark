# Spec — MCP Apps: the person's tap, inside the conversation

**Thesis.** The engine already knows which calls wait on a person and
which doors a row affords that person. Today the person leaves the
conversation to tap them. The MCP Apps extension lets the engine's MCP
server hand the host one small page that shows a row and the person's
doors on it, so the tap happens where the reading happens. The page is
not a new authority. It is one more way for the **person** to reach
doors they already hold, and every wall that judged the tap in the feed
judges it here.

Design pass for epic 789fe009. No code in this change.

## Epistemic status

Low novelty in the protocol, one real decision in the law. MCP Apps is
somebody else's extension and the engine would merely speak it. What is
decided here is **whose tap a `tools/call` is** when the agent and the
person share one connection, and section 1 writes the answer down so it
is chosen and not tripped over.

The extension was read by the mayor on 2026-10-01 (spec 2026-01-26,
`modelcontextprotocol/ext-apps`) and restated in the ticket. The bench
has no web access, so this document relies on that restatement and on
nothing else about the extension. Four things could **not** be verified
from here, and the design is built so that none of them is load-bearing
for safety. Each is a question the first child answers against a live
host before it merges (ticket filed, see *Follow-up*):

- Whether a host renders a tool's UI on **every** call of that tool, or
  lets a result opt out. Assumed: every call. Section 3 follows from it.
- Whether a host puts a result's `structuredContent` and `_meta` in the
  model's context. Assumed: it may. Nothing secret rides either.
- Whether claude.ai declares the extension at `initialize` and sends
  `Mcp-Session-Id` back on later requests. Assumed: yes to both. A
  client that does neither gets the fallback, which is correct.
- The exact handshake messages beyond the names the ticket lists
  (`ui/initialize` and the five host notifications).

Two things in the engine were not read in this pass and are named where
they matter: the verdict doors and walls of `approval_request`, and the
function that resolves a person's principal from a subject.

## What exists

- `server/mcp.clj` — eleven fixed tools, `message` as a function of one
  parsed JSON-RPC message, `call-tool` dispatching by name, `listing`
  taking **no caller** (waymark-912p), `initialize` reading `params`
  and answering `{:tools {:listChanged true}}` and nothing else. There
  is no `resources/*` method: `message` answers method-not-found.
- `server/routes/mcp.clj` — `rpc-post`. `named-principal!` resolves the
  **bearer**. An `initialize` mints a session (`mcp/open-session!`) and
  answers its id in a header. When the session entry is `:bound`,
  `sitter-session` replaces the principal and the visibility with the
  seat's sitter (`seat:<id>`, spec-seat.md R-12.15), so every tool call
  the message makes is the seat's. The bearer's principal is dropped at
  that point and no tool sees it.
- [The connector door](spec-connector-door.md) § 3 — the bearer on a
  claude.ai connection is a **delegate**: an agent principal
  `<client>:<sub>` with no roles from the credential and `:acts-for`
  the person's subject. Option (a), *Claude is the person*, was
  rejected there because the log would record the owner tapping things
  the owner never saw.
- `server/held_calls.clj` — a call that waits on a person is a
  `held_call` row. The power door answers `{held true, held_call <id>,
  note "waiting on a person's tap"}` with `isError` false. Two walls
  stand before `allow` and `refuse {reason}`:
  `the-caller-does-not-decide` (the `caller` field is not the actor)
  and `an-approver-decides` (the `approver` role for a tool call; for a
  seat or judgment door, `the-owner?`: the person the author acts for,
  or a tool that person is signed in to, and never a `seat:` sitter).
  The row carries what a person reads: `tool`, `why`, `shown`,
  `changes`, the computed `call`, `door`, `owner`, `waits_on`.
- `server/invitations.clj` — an `invitation` row hands one field of one
  action on one row to a person. The person submits **under their own
  grant** at the invited row's own door, and a log consumer walks
  `answer` when it hears that transition.
- `waymark_invoke`'s confirm gate — an action whose `safety.confirm` is
  true runs only when the call echoes the consequence sentence exactly
  ([MCP surface](spec-mcp-surface.md) § *Safety rides the
  declaration*).
- `server/ui_assembly.clj` — the generic UI is one self-contained page
  assembled from ordered fragments under `resources/waymark10/ui/`.
  It talks to `/api` over HTTP with the browser's own session.
- `mcp/origin-key` — every invoke this door forwards is stamped
  `mcp/<principal-id>/<nonce>`, and `mcp/actions-from-mcp` counts them.

## The design

### 1. Whose tap it is

A click in the app reaches the server as a `tools/call` on the
connection the agent uses, with the same bearer. **Nothing on the wire
says which of the two caused it.** The engine cannot prove a call came
from the frame. So the design does not pretend to, and says what it
leans on instead.

**App-only tools.** Two tools carry `_meta.ui.visibility: ["app"]`:

| tool | purpose |
|---|---|
| `waymark_app_read` | one row as the **person** sees it: its fields and the doors the surface admits, plus a ticket |
| `waymark_app_act` | one of those doors, taken as the person, with the ticket |

The host hides them from the model and lets only the app call them.
They are generic for the reason the eleven are: a row's doors arrive as
data, so a tool per verb (`allow`, `refuse`, `decline`, `submit`) would
be four tools today and a new one with every kind.

**What the engine trusts the host for.** Exactly one thing: that a
`tools/call` naming an app-only tool was sent by the frame the host
rendered, and was not composed by the model. That is the whole of it,
and it is trust in a client, so it is given per client and by the
owner:

- The app tools exist only on a session whose **bearer** is a delegate
  of a client the owner listed in `:app-clients`
  (`WAYMARK10_MCP_APP_CLIENTS`, a subset of `:delegate-clients`), and
  whose `initialize` declared the extension. For every other caller
  they are absent: an agent presenting its own bearer, a script, a
  delegate of an unlisted client. A call naming them is the unknown-tool
  protocol error the door already gives. This is concealment, the
  door's existing posture, and it is why a harness that merely *claims*
  the capability gains nothing.
- Listing a client in `:app-clients` is the owner saying: this host
  keeps the wall between its model and its frames. The ceremony section
  of the connector door gains one line when the first child lands.

**A host that draws pages and does not declare.** Claude Code
(`clientInfo.name` `claude-code`, observed 2026-10-01) renders MCP Apps
and initializes without the extension, so the gate above never lists
`waymark_show` to it. `:app-undeclared-clients`
(`WAYMARK10_MCP_APP_UNDECLARED_CLIENTS`, a comma list of clientInfo
names, empty by default) treats a session whose recorded `client_name`
is on it as having declared. Every other condition stays: the bearer is
a delegate of a client in `:app-clients`, and the ticket secret is set.
The trade is that clientInfo is self-reported, so this list is the
owner trusting that host **by name**, behind the connector login, with
no declaration to check the name against. It is an experiment, and the
owner's mayor session verifies it: with the list on, the model in
Claude Code must **not** see `waymark_app_read` or `waymark_app_act` in
its tool list, and `waymark_show`'s text content must carry no ticket.
If either shows, the owner empties the list. A client on neither list
is listed as before.

**The per-render ticket, and what it is not.** `waymark_app_read`
answers a ticket beside the row. `waymark_app_act` requires it. The
ticket is a signed statement of `{session, person, kind, id, version,
doors, expires, nonce}` with a ten-minute life. It is stateless: no
table holds it.

It is **not** a second factor against a host that fails the one trust
above. A model that could call `waymark_app_act` could call
`waymark_app_read` first and hold a ticket. Any channel that keeps a
nonce from the model is the same channel that keeps the tool from the
model. The spec says so plainly, so that nobody later reads the ticket
as the wall.

What the ticket does buy:

- **The tap is bound to what was rendered.** `version` goes out as
  `If-Match`. A row that moved between the render and the tap refuses
  stale, as a fenced door does, and the page reads again.
- **The door set is closed at the render.** `doors` names the actions
  the surface admitted (section 3). An act naming another is refused
  before any route is touched.
- **A replay lands nothing.** The nonce becomes the idempotency key
  (section 2), so the engine's own store answers a second send with the
  first result.
- **No act without a read in the same session by the same person.**

**The page is the engine's own code and never runs row data.** A frame
that can call `waymark_app_act` is a frame where injected script is an
approval. Every value from a row is written with `textContent`.
Markdown fields are shown as plain text. No row value becomes an
attribute, a URL or HTML. The one link the page offers is the row's own
address on the engine's `:app-url`, built by the server and opened with
`ui/open-link`.

**The page shows what the engine says, not what the model said.** The
tool input the host passes to the frame names a kind and an id and
nothing else is read from it. What the person reads (`why`, `shown`,
`changes`, `call`) comes from `waymark_app_read`, which is the row.

**The walls are untouched.** A tap that reaches `held_call.allow` is
judged by `the-caller-does-not-decide` and `an-approver-decides` as any
tap is. The app adds no path around either.

### 2. Under which grant

**The person's own, never the seat's, and never the delegate's.**

The person is `:acts-for` of the **bearer's** principal. It is read
from the principal `named-principal!` returned, before `sitter-session`
replaces it. `rpc-post` therefore carries one more key on the session
map, `:bearer`, and the app tools read the person from it and from
nowhere else. A bearer with no `:acts-for` has no person, and the app
tools are absent for it.

The app tools then run a session of their own:

- **Principal:** the person, resolved the way the identity boundary
  resolves that person's own credential: the member row for the
  subject, type human, its roles read **now** (`members/held-roles`, as
  `sitter-session` reads the sitter's). The child must reuse the
  boundary's resolver. A principal built by hand in `mcp.clj` is a
  second copy of the members gate. An inactive member has no doors.
- **Visibility:** what a human presenting no grant has at
  `wrap-identity`. The delegate's worn grant is not consulted. The
  seat's grant is not consulted.
- **The route:** the same in-process `door` every tool uses, so guards,
  fences and concealment are inherited and none is written twice.

**When the agent session sat as a seat.** This is the owner's daily
case: the conversation is bound to a delegating seat, the seat's call
is held, and `caller` is `seat:<id>`. The app tools ignore the binding:

- The person comes from `:bearer`, so the tap is the person's while
  every other tool call on the session stays the sitter's.
- `the-caller-does-not-decide` passes because the person is not the
  sitter. `the-owner?` passes when the person is the row's `owner`, and
  refuses when the bearer's person is somebody else. A key can hold
  several offices, so the two may differ, and the wall is what says so.
- `closed-sitting-refusal` does not apply. A person answers a call
  after the sitting that made it has closed.
- `router/mind-the-wall!` is not run. A halted seat's person can still
  answer what the seat left waiting.
- `count-served!` and `count-refused!` do not count app calls on the
  sitting. The bytes were read by a person and the refusals were a
  person's.

**The log stays honest.** The connector door rejected *Claude is the
person* because the log would show taps the owner never saw. Here the
owner saw the page and tapped, and the log says how: the actor is the
person, and the transition carries the key
`mcp-app/<delegate-id>/<ticket-nonce>`. `mcp-app/` does not begin with
`mcp/`, so `actions-from-mcp` keeps counting the model's writes alone,
and the same fold with the new prefix counts the taps.

### 3. Which surfaces get a UI

**One resource: `ui://waymark/row`.** Every surface the ticket lists is
the same thing, a row and the person's doors on it. That is the generic
UI's own claim, and one page that renders an envelope keeps it. The
surfaces differ in which doors the read admits. That is law on the
server, per kind, and the page does not know it.

**One model-visible tool links to it: `waymark_show {kind, id,
action?}`.** It reads the row through the real route **as the session's
own principal**, so a row the agent may not see answers the usual 404
and the tool shows nothing the agent could not already read. Its text
result is the row's summary (`row-summary`'s shape) and one sentence:
the row is in front of the person, and the tap is theirs. Its
description tells the model when to call it: after a call answers
`held: true`, after it files an `approval_request` or an `invitation`,
and when `waymark_invoke` meets the confirm gate on a door that is the
person's to take.

The link is on a new tool and not on `waymark_power`, `waymark_get` or
`waymark_invoke`, because a link is declared per tool and (as assumed
above) the host renders it on every call. A code seat calls
`waymark_power` some hundreds of times in a sitting, and a frame beside
each read is not a surface. The cost is one more model call between the
hold and the buttons, and the tool list reading twelve on a session
that has the extension. If a host is found to let a result opt out of
its UI, linking `waymark_power` directly is the better trade, and it is
a change to one map.

The surfaces, in build order, and the doors each read admits:

1. **`held_call`** — `allow`, `refuse {reason}`. The page shows `tool`,
   `why`, `shown`, `changes` and `call`, and for a door hold the
   `door`. After the tap it shows the state the row reached.
2. **`approval_request`** — its verdict doors, as the kind declares
   them. This is the tap that widens the agent's own leash, and it
   stays exactly the human verdict the asking loop requires. The page
   shows the scope asked for, entry by entry, from the row.
3. **`invitation`** — *Take this step*: the action the invitation
   names, on the row it names, with `field` focused and `suggest`
   shown and never submitted for the person. Beside it, the
   invitation's own decline door. The read follows `self` to the
   invited row. The engine's consumer walks `answer` as it does today.
4. **A confirm-gated door** — `waymark_show` with `action`. The read
   admits that one door. The page shows the consequence sentence and a
   button whose label is the action. The page sends the sentence as
   `acknowledge`, read from the envelope, after the person taps. The
   person read it, which is what the gate was always for.
5. **A row and the person's doors** — `waymark_show` with no `action`
   on any other kind. The read admits every action the envelope affords
   the person. This is the widest surface and it lands last, after the
   first three have a count behind them (*The experiment*).

A door whose input the page cannot render (section 4) is listed with
its name and a link to the row in the engine's own UI. It is never
silently dropped.

**What the page tells the model.** After a tap the page sends
`ui/update-model-context` with one engine-made line: the kind, the id,
the action and the state reached. The model then reads the row itself.
The page never sends `ui/message`: that speaks in the conversation as
the person, and the person tapped a button and said nothing. The page
never asks for another display mode. It is inline.

### 4. How the UI is built

**A small standalone page, not the generic UI's fragments.** The
generic UI fetches `/api` with the browser's session. In the host's
frame there is no session and, with no `csp` declared, no fetch at all.
Reusing it would mean declaring `connectDomains` and putting a
credential inside a frame that sits beside a model. That is the
opposite of what section 1 leans on.

- **Source:** `waymark10/resources/waymark10/mcp_app/`, ordered
  fragments assembled once at handler construction by the pattern of
  `ui_assembly.clj`, so a missing fragment fails startup and never a
  request. The fragment list includes the generic UI's own
  `020-base.css` by path, so the look has one source and no fork.
- **One HTML document**, inline style and inline script, no external
  load of any kind. The extension's SDK is an npm package and this
  tree has no JavaScript build, so the page speaks the bridge by hand:
  JSON-RPC over `postMessage`, `ui/initialize`, the tool-input and
  tool-result notifications, `tools/call`, `ui/open-link`,
  `ui/update-model-context`, `ui/resource-teardown`. That is small,
  and it is the part the first child checks against the published
  spec.
- **CSP: none declared.** The host's default (`default-src 'none'`,
  inline script and style only) is the policy wanted. `permissions`
  and `domain` are not set. `prefersBorder` is true.
- **Data rides tool results.** The page learns the kind and id from
  the tool input, calls `waymark_app_read`, and renders what comes
  back. It holds no token and knows no address but the one link.
- **Forms are deliberately few.** The page renders an action's input
  when every field is a string, prose, number, boolean or an enum:
  `refuse {reason}` and most light doors. A ref picker, a list, an
  attachment or a worksheet is the generic UI's work, and such a door
  gets the link instead.
- **Refusals are shown in the engine's words.** `waymark_app_act`
  answers the RFC 9457 document as `isError` tool output, like every
  refusal at this door, and the page prints its `detail`.

### 5. The fallback

A client that does not declare
`capabilities.extensions["io.modelcontextprotocol/ui"]` with
`text/html;profile=mcp-app` among its `mimeTypes` gets **today's
answers, byte for byte**:

- `initialize` answers the capabilities it answers today.
- `tools/list` is the eleven, with no `_meta`.
- `waymark_show`, `waymark_app_read` and `waymark_app_act` are absent,
  and a call naming one is the unknown-tool error.
- `resources/list` and `resources/read` answer method-not-found.
- The held answer is unchanged in **both** cases. The hint to show the
  row lives in `waymark_show`'s description, so the power door's bytes
  do not depend on the client.

The declaration is remembered on the session entry `open-session!`
writes at `initialize`. A caller that sends no `Mcp-Session-Id` is the
stateless caller the door has always served, and it is text-only.

Over Postgres the entry is a row of `waymark10_mcp_sessions`, which
gains three columns: `app_ui boolean NOT NULL DEFAULT false`,
`client_name text` and `client_version text` (the `clientInfo` the
client sent). A row written before them reads `app_ui` false, and the
in-memory entry keeps the same three keys. The bearer is **not**
stored: `rpc-post` resolves it per request, as it always did.

`listing` therefore takes the session after all. The promise of
waymark-912p is kept in the form that matters: the list does not move
with a **grant** or a law change. It now has two shapes, chosen once
per session by what the client said it can render and by which client
it is.

## Recorded costs and punts

- **The host is trusted, and the trust cannot be tested from inside.**
  No engine test can prove a host keeps its model from an app-only
  tool. The `:app-clients` list is where that judgment is recorded.
  It is empty by default, so the module ships dark.
- **The ticket needs a signing key.** It is
  `WAYMARK10_MCP_APP_TICKET_SECRET`, read where
  `WAYMARK10_MCP_APP_CLIENTS` is read, and the ticket is HMAC-SHA256
  over its own bytes. Unset or empty, the whole module is dark: the
  three tools are absent from every listing, even for a listed client,
  and the server logs one line at boot saying so. It is the engine's
  own secret, never an OIDC client secret and never a seat key.
- **No list surface.** *What is waiting on me* as one frame of several
  held calls is not here. One show is one row. It is the natural sixth
  surface if the count says the first is used.
- **No streaming into the frame.** A row that moves while the page is
  open is noticed at the tap, by `If-Match`, and not before.
- **Text-only hosts get no new sentence.** A person on a client
  without the extension still goes to the feed. That is today.
- **A fired seat's run has nobody watching.** A Routine's harness that
  declared the extension would be offered `waymark_show` and nobody
  would tap. The held call waits and expires as it does now.
- **The module boundary.** This belongs to the `:mcp` module and adds
  no kind. Whether the page's fragments count as a fifth contribution
  is not raised: they are the module's own resources.

## The children

Each is one pull request under the bench's ceiling, with its tests
named. All are `waymark10` tests on memory storage unless said.

1. **`held_call` Allow and Refuse inline.** The whole thin slice, and
   the owner's daily friction. The extension is read at `initialize`
   and remembered on the session. `rpc-post` carries `:bearer`.
   `:app-clients` is read from the environment. `listing` takes the
   session. `resources/list` and `resources/read` serve
   `ui://waymark/row`. `waymark_show`, `waymark_app_read` and
   `waymark_app_act` exist, with the door law holding one entry:
   `held_call` admits `allow` and `refuse`. The page renders a held
   call and its two buttons. If the slice passes the ceiling, the
   page's styling splits off and the walls do not.
   `mcp_apps_test`:
   - a client without the extension gets eleven tools with no `_meta`,
     method-not-found on `resources/read`, and a held answer equal
     byte for byte to today's;
   - a client with it, on a listed delegate client, lists
     `waymark_show` with `_meta.ui.resourceUri` and the two app tools
     with `visibility ["app"]`, and reads the resource with mimeType
     `text/html;profile=mcp-app`;
   - the same client on an **unlisted** delegate client, and an agent
     bearer with no `:acts-for`, get the fallback;
   - on a session bound to a seat, `waymark_app_act allow` moves the
     seat's held call to `allowed` with the **person** as actor and an
     `mcp-app/` key, and `waymark_invoke allow` from the same session
     still meets `the-caller-does-not-decide`;
   - a person who is not the row's `owner` is refused by
     `an-approver-decides`;
   - an act with no ticket, an expired one, one minted for another
     session, one naming a door outside `doors`, and one whose row
     moved are each refused, and a replayed act lands one transition;
   - an app call counts nothing on the sitting and runs after the
     sitting closed;
   - `waymark_show` on a row the agent may not see answers the uniform
     404.
   The `mcp` conformance pack's fixed-tool obligation is run without
   the extension and stays as it is.
2. **`approval_request`.** One more entry in the door law, and the
   page's reading of a scope. `mcp_apps_approval_test`: the person's
   verdict mints or widens the grant as the feed's tap does; the
   delegate's own `waymark_invoke` on the same door is refused as
   today; the list-changed notice still fires.
3. **`invitation`.** The read follows `self`, admits the named action
   and the decline door, and the page focuses `field`.
   `mcp_apps_invitation_test`: the person's submit lands under the
   person's grant and the consumer walks `answer`; `suggest` is in the
   read and absent from the act unless the person sent it; an
   invitation to a door the person lacks shows that door's refusal.
4. **The confirm door.** `waymark_show` takes `action`.
   `mcp_apps_confirm_test`: the read admits that door alone and
   carries the sentence; an act without `acknowledge`, or with a
   paraphrase, is refused by the gate that refuses the model.
5. **A row's doors, and the few forms.** The read admits what the
   envelope affords the person, and a door the page cannot render
   answers with the link. `mcp_apps_row_test`: the doors are the
   person's and not the agent's on a row where the two differ; a door
   with a ref input is listed with a link and no form.
6. **The count.** `actions-from-mcp`'s fold with the `mcp-app/`
   prefix, beside the connector door's experiment. `mcp_apps_count_test`.

## The experiment

The connector door counts actions the model took from the conversation.
This counts the taps the **person** took there. Two weeks after the
first child is live with the owner's client listed, the number of
`mcp-app/` transitions on `held_call`, against the held calls answered
in the feed over the same days, says whether the friction was where the
epic thinks it is. A count near zero says the hold is not the wait, and
surfaces two to five should not be built on its strength.

## Follow-up

- *Check the MCP Apps host behaviours this spec assumes* (ticket filed
  with this change): the four unverified points under *Epistemic
  status*, answered against claude.ai and the published extension
  before the first child merges.
