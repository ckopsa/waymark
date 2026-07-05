# Agent links: least-privilege access, negotiated as ordinary Waymark

A human mints an **agent link** and hands its token to an agent — an LLM,
a cron script, anything that can speak HTTP. By default the token grants
**nothing**. Given its task, the agent requests the minimum it believes it
needs; a person approves; enforcement is rendering. Implementation:
`waymark2/server/grants.py`; end-to-end proof:
`tests/waymark2/test_agent_links.py`.

## The model

Per **data field** (kind → field → mode):

| mode | the agent sees |
|---|---|
| `clear` | the value |
| `hashed` | `sha256:…` — equality without content |
| *(unlisted)* | nothing; the field does not exist in its envelopes |

`summary` is grantable like a field (kind-level key `"summary"`); without
it, documents render as "*kind* (scoped view)".

Per **affordance** (kind → action → mode):

| mode | invoking it |
|---|---|
| `open` | executes normally, audited as the agent |
| `approval` | becomes a **pending approval resource** (202); a human approves (or rejects), then `run` executes what was approved |
| *(unlisted)* | renders `unavailable` with the reason naming the remedy: `agent_grant.request_access` |

Per **argument** (kind → action → arg → mode): `edit` (default for granted
actions), `approval` (supplying it routes the invocation to a human), or
`none` — supplying it is a 422, and if the argument is *required*, the
invocation still works: it becomes an approval whose `missing` list names
what the human must fill in when approving. Plus a **duration**
(`requested_hours`); expiry returns everything to none.

## Everything is a resource — that's the whole trick

The grant is a state machine: `draft → requested ⇄ granted → revoked`.
"Generated agent links along with their approval status" is the
`agent_grants` collection in the generic UI — no new screens. Approving is
a confirm-gated action with a declared consequence; every negotiation step
lands in the audit log; a human can *follow* the agent through its own
permission request. Amendments are `request_access` again from `granted` —
the old grant stays in force until the new ask is approved (`deny`
withdraws everything).

An approval-mode invocation is a resource too:
`pending → approved → closed | rejected`. The agent gets the approval's
envelope back and can watch it; the human approves with `overrides` (any
arguments the agent couldn't supply); `run` — open to the agent — executes
the stored invocation via `ctx.invoke` with the fence satisfied for the
approved state, and records the outcome either way. One accountable audit
entry; the actor is whoever ran it.

Both kinds pass the full conformance suite like any application resource —
transition truth, safety truth, prose honesty — because they *are*
application resources that happen to ship with the engine.

## Enforcement is rendering

`apply_scope` redacts the envelope: an ungrated field isn't dimmed or
locked, it is **absent**; an ungrated action is honestly `unavailable`;
approval-mode actions carry `access: "approval"` so a 202 is never a
surprise (`AgentClient.act` returns the approval envelope; `mcp_tools`
says so in the tool description). Post-invoke documents and idempotent
replays are scoped identically — a stored reply may not out-say a live
one. The agent's own grant renders fully, with exactly one action:
`request_access`. *Yours to request; a person's to decide* — the
`no_self_dealing` guard holds that line on approvals too.

## The token

`Authorization: Bearer wmk_…` (or `?agent-token=` where headers can't go).
The CLI takes `--token` / `WAYMARK_TOKEN`. A presented token **is** the
credential: unknown or revoked tokens resolve to a scoped-to-nothing
principal, never to anonymous — a dead link can't fall through to another
auth path. What stays open to any token: discovery
(`/.well-known/waymark`), published schemas, and OpenAPI — the catalog the
agent needs to compose its request; shapes, never data. Workspace streams
(events/presence) are not for scoped agents; they read the documents they
were granted.

## Caveats (v1 of this feature)

- **Create** supports `open` only; approval-mode create is refused with an
  explanation (an approval targets an existing resource id).
- **Bulk** actions are not scope-aware yet; grant them `open` or not at all.
- `parts` and `display` are dropped from scoped envelopes (they mirror
  data); acceptance-set enums inside *granted* actions' schemas are visible
  by design — what you may act on, you may see the choices for.
- Grant lookups are one indexed query per request; no cache yet.
- Expiry is enforced at request time; there is no sweeper marking expired
  grants (their scope is already dead — the state token just still says
  `granted`).
