# Spec — the connector door: a person's tool at the MCP surface (waymark-kkx)

**Thesis.** The MCP surface already makes any waymark engine drivable by
an agent that can present a bearer and a grant header. The claude.ai
custom connector is an agent that can present a bearer and *nothing
else*: it learns where to log in by OAuth discovery, it logs the
**person** in, and it cannot set `X-Waymark-Grant`. Three small things
make that client a first-class caller without loosening one law: the
engine says where its authorization server is; every 401 points at that
document; and a token minted through a connector client resolves to a
**delegate** — an agent acting for the person, wearing a grant the
engine reads off its member row because no header will ever carry it.

## Why this door, in the owner's own words

The three most product-shaped specs in this tree diagnose the same
thing. The feed opens with *"a person who opens the app, reads a table,
and closes it"*; the addressed notice says *"one place nobody looks at is
not obviously better than three"*; the outcome menu says time follows the
friction gradient, not the values. Every answer so far built another
surface **inside** waymark that the person has to go to. The one surface
the owner goes to without being asked is the conversation with Claude.
This spec puts the reading where the reader already is, and it does so
as a *client* of the engine, not a new mechanism inside it: waymark stays
the policy engine over the household's data; Claude becomes one more
principal at its door.

## Epistemic status

Low novelty, one real decision. The protected-resource document and the
challenge header are RFC 9728 plumbing. The delegate is the guest door's
own shape — a principal whose worn grant rides the principal rather than
a header (`oidc-rp/resolve-session` and `:session-grant`, the
`standing-grant-for` courtesy at `/auth/agent`) — applied to a bearer
that arrives with no session. What is genuinely decided here is **who
Claude is at the door**, and the answer is written down in § 3 so it is
chosen rather than tripped over.

Two facts checked against the current docs before writing, because they
bound the claim:

- Custom connectors accept a **static bearer header** in beta, so the
  door could be wired today with a long-lived token. The house has no
  long-lived credential by law (`spec-standing-agent.md`: *every
  credential still expires*), so OAuth stays the shape.
- Custom connectors reach the claude.ai apps (web, desktop, mobile).
  They reportedly do **not** yet reach Claude Code on the web, where
  only first-party connectors are attached. This door lands in the chat
  app, which is the side-by-side picture, not in a remote coding session.

One thing could not be verified: whether claude.ai's client tolerates a
server that answers **405 to the stream GET**. The spec allows it; if the
client does not, the streaming half is `spec-mcp-surface.md`'s own
recorded punt and becomes the first fix.

## What exists

- `routes/mcp.clj` — `POST /api/-/mcp`, stateless, JSON-RPC 2.0, the
  2025-06-18 revision negotiated; the GET is the deliberate 405.
- `oidc.clj` — the bearer resolver: RS256 against the issuer's JWKS,
  audience checked, claims → `Principal`. Every refusal is one 401
  carrying `WWW-Authenticate`.
- `oidc_rp.clj` — the browser flow, the agent doors, and `wrap`, which
  under `:require-auth?` closes the surface to anonymous requests except
  a named list of doors that exist precisely for the caller holding
  nothing yet.
- `router.clj` `wrap-identity` — principal, members gate, and visibility
  resolve once. A named **agent** presenting no grant gets the bootstrap
  surface (waymark-rci): the asking door and the vocabulary to ask with,
  never full sight. A human presenting no grant runs unscoped.
- `members.clj` `gate!` — first sight provisions a durable (`idp`) row
  through the registrar; `provision!` stamps `:subject`.
- `grants.clj` — `standing-grant-for` (newest accepted-or-offered grant
  for an audience) and `accept-as-audience!` (the first-arrival
  courtesy), both already public for the guest door.
- Keycloak (`domestic-realm`) is the authorization server. It serves
  `/.well-known/openid-configuration` and
  `/.well-known/oauth-authorization-server` per realm, PKCE S256, refresh
  tokens; the engine's audience arrives on a token through a client
  scope with an audience mapper (`scripts/agent-client.sh` attaches it).

## The design

### 1. The engine names its authorization server (RFC 9728)

`GET /.well-known/oauth-protected-resource` and the path-inserted
spelling `GET /.well-known/oauth-protected-resource/api/-/mcp` answer the
same document:

```json
{"resource": "https://work.kopsa.info/api/-/mcp",
 "authorization_servers": ["https://keycloak.kopsa.info/realms/domestic-realm"],
 "bearer_methods_supported": ["header"],
 "resource_name": "waymark",
 "scopes_supported": ["waymark-workqueue10"]}
```

Both live in the **mcp module's static routes**: the document is the
MCP door's own metadata, so a deployment assembled without `:mcp` has no
resource to advertise and the address is nobody's. The resource is
`:app-url` + `/api/-/mcp`; the authorization server is the browser-facing
issuer when the deployment splits issuers, because the client that reads
this lives outside the LAN; `scopes_supported` is
`WAYMARK10_OIDC_RESOURCE_SCOPES` and is omitted when unset. **Without
`:app-url` the route answers 404**: an engine that cannot name itself
advertises nothing.

`oidc-rp/wrap` adds the well-known prefix to its open list. Under
`:require-auth?` discovery must stay reachable by the anonymous, because
discovery is how the anonymous stop being anonymous.

### 2. Every 401 says where to start

`oidc/challenge` is now the one spelling of `WWW-Authenticate`:

```
Bearer realm="waymark", error="invalid_token", resource_metadata="https://work.kopsa.info/.well-known/oauth-protected-resource/api/-/mcp"
```

`error` rides only when a credential was presented and refused. The
three 401s that used to disagree — the bearer resolver's, the MCP
route's anonymous refusal (which carried no challenge at all), and
`require-auth`'s — all read it. Without `:app-url` the parameter is
absent and the header is what it was.

### 3. Who Claude is at the door: the delegate

The connector logs the **person** in. The token's `sub` is the owner,
its `actor_type` is human, and today's resolver would hand Claude the
owner's unscoped sight while the router's own sentence — *a named agent
NEVER runs unscoped* — is bypassed by a claim on a token. The fork:

- **(a) Claude is the person.** Zero engine change. Every law about
  leashes, grants and audit is silent for this caller, and the transition
  log records the owner tapping things the owner never saw. Rejected.
- **(b) The connector client is one agent for everybody.** A single
  member row shared by every household member who connects. Sight cannot
  be per person, and the roster cannot say who Claude was acting for.
  Rejected.
- **(c) One delegate per (tool, person).** Chosen. A token whose `azp`
  names a client in `:delegate-clients` resolves to an **agent**
  principal with id `<client>:<sub>`, display `<Tool> for <Person>`, no
  roles from the credential, and `:acts-for` = the person's subject. The
  members gate provisions it at first sight like any durable identity
  (`provenance` "idp", `acts_for` stamped by the registrar and by nobody
  else — the `:subject` write fence, made real for one more field). In
  `:invited-only` mode the delegate is admitted exactly when its person
  is an active member: the person's membership is the invitation.

Then `wrap-identity`, finding no header and no session grant on a
principal that **has** an `:acts-for`, reads `standing-grant-for` on the
delegate's own id, accepts a still-offered one as the audience (the
guest door's courtesy), and wears it. Nothing standing means the
bootstrap surface, exactly as for any agent. So the loop is the house's
own: Claude connects, sees the asking door, files an `approval_request`;
the owner approves it in the feed; the next call wears the grant. Scope
still only widens through a human verdict, the roles stay with the
person, and an agent holding `recovery-admin` — the one thing
`members.clj` says must never be minted — cannot arrive through a
credential.

Why the lookup is limited to delegates and not every grantless agent: an
agent that *can* present a header must. The engine looks for a grant only
on behalf of a caller that has no way to say which one it holds, so
waymark-rci's meaning for every other agent is untouched.

### 4. What does not change

The six tools, the grant projection of discovery, the Gate projection,
the confirm gate, the refusals. A delegate is an ordinary agent principal
by the time a tool runs; `mcp.clj` never learns the word.

## Recorded costs and punts

- **One grant query per delegate request.** `standing-grant-for` is a
  filtered read on `grant` by audience, limit 100, decoded. The guest
  door pays it once per session; the connector pays it per call because
  the transport is stateless. Acceptable for a household engine; a
  short-lived per-principal cache is the fix if it ever shows in a trace,
  and it must key on the grant's own revision so a revoke lands on the
  next call.
- **`acts_for` is a schema field, not a filterable one.** A filterable
  field becomes a generated, indexed column in the Postgres store, so
  promoting it would be a schema migration on every deployment for a
  question nothing asks over the wire yet. The row carries the fact; the
  roster's "which agents act for me" filter is one line and one
  migration away when somebody asks it.
- **No dynamic client registration.** Keycloak's anonymous registration
  needs trusted hosts or an initial access token, and claude.ai accepts a
  pre-registered client id and secret. One confidential client, minted
  once, is the whole registration.
- **The resource indicator is not enforced.** The client sends
  `resource=` on its token request (RFC 8707); Keycloak ignores it; the
  engine's audience check is the wall it was always. The document's
  `resource` is therefore descriptive. Enforcing it would mean teaching
  Keycloak to mint per-resource audiences, which is a realm decision, not
  an engine one.
- **Streaming stays punted.** See epistemic status.

## The ceremony (Keycloak and claude.ai — the owner's half)

1. **One confidential client per tool**, e.g. `waymark10-connector-claude`
   (`scripts/connector-client.sh new claude` mints exactly this shape
   and files the secret beside the agent clients'). A second tool is a
   second client with its own callback — Gemini's is the
   `oauth-redirect.googleusercontent.com/r/…` address its connector
   shows in the failed login's URL — and a second entry in
   `WAYMARK10_OIDC_DELEGATE_CLIENTS`; sharing one client would make
   two tools one delegate wearing one grant (2026-09-02, when Gemini
   was pointed at Claude's client and Keycloak refused the redirect):
   standard flow on, client credentials off, PKCE S256, refresh tokens
   on, consent required (the login page then states what Claude is being
   given, which is the house's own posture), redirect URI exactly
   `https://claude.ai/api/mcp/auth_callback`. Attach the engine's
   audience scope (`waymark-workqueue10`) as a **default** scope, not an
   optional one: the connector cannot be relied on to request it, and a
   token without the audience is refused at the door.
2. **Session lifetime.** The client's SSO idle/max settings decide how
   often the owner logs in again inside claude.ai. `spec-standing-agent`
   already says expiry is fine; a week is a reasonable season.
3. **Engine environment.**
   `WAYMARK10_OIDC_APP_URL=https://work.kopsa.info` (already set for the
   RP flow; now also read at the top level),
   `WAYMARK10_OIDC_DELEGATE_CLIENTS=waymark10-connector-claude=Claude`,
   `WAYMARK10_OIDC_RESOURCE_SCOPES=waymark-workqueue10`.
4. **claude.ai → Settings → Connectors → Add custom connector**: URL
   `https://work.kopsa.info/api/-/mcp`, client id and secret under
   advanced settings. Log in through Keycloak. The six tools appear, plus
   whatever Gate tools the grant admits.
5. **The first grant.** Claude, seeing only the asking door, files an
   `approval_request` naming read on the kinds it would consult (task,
   outcome and its pieces, insight, tickler, value, calendar events) and
   the light doors it would offer (start and done on a task, the
   tickler's three answers, accept and decline on an outcome piece). The
   owner approves it in the feed. Read-only first is the recommended
   posture.

## Verification

- `oidc_claims_test` — a delegate token resolves to `<client>:<sub>`,
  type agent, no roles, display `<Tool> for <Person>`, `:acts-for` set;
  an `azp` nobody named is the person unchanged; a refused token's
  challenge carries `resource_metadata` when `:app-url` is known.
- `oidc_rp_test` — `from-env` reads the three new variables.
- `connector_door_test` (memory storage, no database) — both discovery
  addresses answer the document and 404 without `:app-url`; an anonymous
  MCP POST is a 401 pointing at discovery; a delegate's first sight
  provisions the bound row; the delegate is concealed until a grant is
  offered and wears it on the next request with no header; `acts_for` by
  hand is refused; `require-auth` leaves discovery open.
- The `mcp` conformance pack's `routes-mounted` obligation covers the
  two new static routes by construction.

## The experiment this door exists for

The feed spec defined the success metric before any of this: **actions
taken**, counted by prefix on the idempotency key, and it recorded that
the convention is still unplanted. The connector's first two weeks are
that experiment. Every invoke Claude makes from the conversation carries
a `claude/` prefix. A nonzero count says build the surface further; zero
says the problem is upstream, in what the composer stages, and no door
fixes that.

**Planted (waymark-kkx.5, 2026-09-02), with one respelling.** The
prefix is the *door's*, not the tool's: `mcp.clj` stamps
`mcp/<principal-id>/<nonce>` on every invoke and create it forwards,
idempotent or not (`mcp/origin-key`; the feed's `feed/<day>/<card>/
<nonce>` is the precedent, and `invoke/finish!` already stamps a
present key on the transition whatever the action's safety says). A
bare `claude/` could not tell the connector's delegates from Claude
Code presenting a bearer at the same door, and the count must; the
principal segment does — every delegate the connector minted is
`waymark10-connector-claude:<sub>`. The read is `mcp/actions-from-mcp`,
`actions-from-feed`'s sibling with the same bounded window and the
same `:reached-cap` honesty:

```clojure
(mcp/actions-from-mcp eng {:principal-prefix "waymark10-connector-claude:"})
;; → {:total … :by-principal … :by-kind … :by-action … :scanned … :reached-cap …}
```

Two weeks from the day the connector goes live, that number is the
verdict.
