# Waymark 10 — batch B: access completeness

Phase 9a landed identity and the kind/id/action grant. Batch B closes
four of its named punts: **field/argument visibility modes**, **the
negotiation machine** (waymark9's request_access, resized), **the
own-grant surface**, and **invite → bind membership**. Everything
rides the existing seams — the visibility closure map the router
resolves once per request, and render's projection — no new
middleware, one new resource kind.

Owned files: `server/{grants,members,oidc,render}.clj`,
`test/waymark10/batch_b_{access,members}_test.clj`, this note.
Router edits: five, each minimal and flagged inline (listed below).

## 1. Field and argument modes

A grant scope entry grows two optional keys:

```clojure
{:kind "plan"
 :actions ["assign_meal" "finalize"]
 :fields {:mode "deny" :names ["notes" "start_date"]}
 :args   [{:action "assign_meal" :mode "deny" :names ["meal_id"]}]}
```

- `:fields` — `allow` renders only the named data fields, `deny`
  renders all but. Entries sharing a kind UNION their admissions
  (visible under any entry = visible); an entry without `:fields`
  leaves the kind unrestricted and absorbs any sibling's narrowing —
  the ids absorption rule, applied twice more.
- `:args` — the same modes per granted action's input arguments.
  **Recorded spelling deviation:** a vector of `{action, mode,
  names}` entries, not waymark9's `kind→action→arg→mode` nested maps
  — a shape the schema layer and fingerprint already hash.
  waymark9's `hashed` field mode (render the digest) is unported.

**A redacted field is ABSENT** — never nulled, never narrated:

| surface                            | where enforced                        |
|------------------------------------|---------------------------------------|
| envelope `data`                    | render, post-probe projection          |
| the summary                        | render (the honesty trap, below)       |
| `parts` (a redacted path drops its whole group) | render                    |
| links (badge, edge params, `{data.x}` templates) | render — the link pass reads the redacted row, so a hidden field omits its link exactly as a nil would |
| `/api/schemas/{kind}`              | router → `grants/project-json-schema` |
| collection items (every depth, stub included) | render (`envelope-summary`/`envelope-stub` share the projection) |

A denied argument is absent from every advertised input schema (its
folded acceptance enum with it), `required` shrinks to match, and
`effort` recomputes over the projected schema.

**The honesty trap, closed:** summaries render from templates that may
read redacted fields (`"Week of {data.start_date} …"`). The recorded
choice is **fallback, not re-rendering**: a template naming any
redacted field renders as the honest generic line `"Plan · Draft"`.
A template with a redaction hole would leak the shape of what it
hides and read as a broken sentence besides.

**Enforcement:** a denied arg arriving in a body — single invoke,
bulk, batch, dry-run alike — answers a 422 with malli's own
closed-map words (`"disallowed key"`), byte-identical to a field that
never existed (`grants/check-args!`, pinned against a genuine unknown
field in the test). **Denying a REQUIRED argument denies the action**
(`prune-unusable`): an action advertised with an unsatisfiable form
is a lie, and its missing-key 422 would name the hidden argument — so
the surface drops it whole, concealment-style. waymark9 routed such
invocations to approval mode, which is unported.

**Recorded seams** (each deliberate):
- Guards still probe the FULL row — advertisement equals enforcement;
  the projection governs what leaves the building. Consequently an
  acceptance-set enum folded from a redacted field (e.g. the `date`
  enum folded from hidden `days`) may reflect hidden values, and an
  `unavailable` narration's deny vars may name one. Named punts.
- The 422 runs at the router boundary, AHEAD of invoke's step order;
  a probing client could distinguish by ordering against the fence /
  state checks (invoke.clj is not this batch's file).
- Collection `query`/`create` input schemas and facet counts are
  unprojected (collections.clj is not this batch's file) — items
  themselves project fully. Drafts likewise (drafts.clj).
- Create-argument modes are unported: `:args` grades declared actions.
- Field granularity is the top-level data field; item fields inside a
  part array are not separately gradable — redact the array.
- Grant projection of SSE / surfaces / openapi / collab **stays the
  phase-9b named punt** (those modules are other batches' files this
  run): a scoped request still 404s those routes.
- Idempotency replays still serve the first execution's unprojected
  bytes (the phase-3 render-fn punt, re-extended).

## 2. The negotiation machine

`:approval_request` — `{grant_id, task, scope, expires_at}` through
**offered → approved / denied** — ports grants.py's core shape onto
the v10 grant:

- The scoped principal itself files the request (`POST
  /api/approval_requests`) — the ONE affordance its own-surface
  grants. `requested_by` is stamped by the engine (`on-create`), the
  create schema omits it; a create guard requires the named grant's
  audience to be the requester.
- **Four-eyes:** `someone-else-decides` refuses the requester's own
  approve/deny. `grant-still-accepting` refuses approving onto a
  revoked/expired grant, with the remedy named.
- **Approve extends the grant** through the grant's new `:extend`
  transition — concealed (hide guard, system-only, exactly the
  attachments `mark_stored` discipline: absent from every envelope,
  404 by hand), `record: true` so the widened scope is in the log,
  idempotent (deliberately: a non-idempotent action's 428 fires
  before the hide guard can conceal; a keyless human probe must see
  404). The effect runs post-commit at the router
  (`grants/approval-effects!` — the jobs-enqueue / put-bytes
  precedent), system actor `waymark10-grants`, keyed
  `approval-extend-{id}` so redelivery replays; a natural replay of
  approve does not extend twice (tested).
- **Deny** records the note; the grant never moves — the 404s persist
  (tested).

**Recorded punts:** approver-edited scope maps (approve grants the
ask as-is; the send-back is deny-with-note, a new ask is a new
request); waymark9's **attenuation ceiling** — the approver's own live
visibility intersected onto the holder's — is unported by name (v10
has no per-member visibility to intersect; grants.py has no simpler
max-grantable check to port instead); approval extends the request's
named grant rather than minting a sibling; the effect rides the wire
boundary — an engine-internal invoke of approve does not extend.

## 3. The own-grant surface

`:grant` and `:approval_request` join a scoped principal's kinds
whenever the presented grant row EXISTS with it as audience — any
state, deliberately: dead scopes the DOMAIN to nothing, but the
negotiation surface is how a dead grant's holder asks again (tested:
revoke, then file a new request). Rows gate per row (audience /
requested_by = self); collections narrow through the same `:ids-of`
visibility cond every id-scoped grant uses (own ids queried, capped
at 200; an empty surface pushes an impossible id so the total stays
honestly zero) — **no special route**. GET-only besides
`approval_request/create`: envelopes render with empty action maps,
concealment unchanged; well-known lists the two kinds beside the
granted ones.

## 4. Invite → bind membership

The member machine grows `:invited` (entered only by the on-create
landing — a create carrying `:bind_token` — the definitions
born-`:proposed` precedent, `:allow-dead` annotated). The first
authenticated principal presenting the token (`X-Waymark-Invite`
header, any resolver) binds: the concealed `:bind` transition
(registrar system actor, logged, input recorded) writes the principal
id into `:subject` and lands `:active`. The gate resolves by id, then
by bound subject, then by binding a presented token; the token is
spent because `:bind` fires only from `:invited` — the second binder
is refused (tested). The MODE decides only the unknown-principal
fallback: `{:members :invited-only}` → one 403 problem
(`membership-invited`); default → auto-provision unchanged. Binding
works under either mode.

**Recorded:** no email, no outbox — the token travels out of band;
`bind_token` renders like any field to an unscoped members reader
(waymark9's SECRET_FIELDS owner-gating unported, beside the
role-uniqueness race punt); token uniqueness under race unenforced;
waymark9's `unbind` unported (suspend, or a fresh invite); the mode
is per engine, not per kind.

**OIDC sessions: punted by name.** A cookie wrapper around bearer
verification would mint a second credential without the login flow
that justifies one — it does not fit cleanly in oidc.clj, so the
browser dance / session cookies / RP-initiated logout stay unported
(docstring updated). The invite bind rides the members gate under the
bearer resolver unchanged.

## Flagged edits outside the owned four files

`server/router.clj`, five minimal seams, each commented `batch B
(flagged)`: kind-schema projection; `check-args!` on invoke / bulk /
batch bodies; `approval-effects!` wrapping the single-invoke result;
the invite token passed into `members/gate!`.

## Merge items this batch could not land (files in flight elsewhere)

1. **engine.clj, two one-liners** (read-only this run):
   - `full-registry`: add `grants/approval-request` beside
     `grants/grant` so production engines enroll it (the batch tests
     enroll it via `:resources`).
   - `engine`: add `:members` to the opts `select-keys` whitelist so
     `{:members :invited-only}` survives boot (tests assoc it onto
     the engine map directly).
2. **phase9a_test.clj, two stale pins** (batch B removes the punt
   they pin — `grant-scoped-surface`):
   - line ~289: `(is (= 404 … (req :get (str "/api/grants/" gid) nil (scoped gid))))`
     → the own grant now answers **200** (deliverable 3).
   - line ~292: `(is (= ["plan"] (:kinds b)))` → now
     `["approval_request" "grant" "plan"]` on an engine that enrolls
     approval_request, `["grant" "plan"]` on one that does not.
   Verified by a scoped read-only run: those are the only two
   failures across phase9a/router/batch-a-envelope/collections.

## Runs

```
docker exec waymark-test-pg psql -U ckopsa -d postgres -c "CREATE DATABASE waymark10_b_test"  # once
cd waymark10 && WAYMARK10_TEST_DSN="jdbc:postgresql://localhost:5433/waymark10_b_test?user=ckopsa" \
  clojure -M:test --config-file /tmp/kaocha-b.edn        # ns-patterns waymark10\.batch-b-.*-test
```

Batch B: **8 tests, 140 assertions, 0 failures.** Scoped regression
(phase9a / router / batch-a-envelope / collections, read-only): 33
tests, 338 assertions, 2 failures — both the stale pins above,
nothing else moved.
