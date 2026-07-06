# waymark3 implementation notes (handoff)

What you need beyond `waymark3-design.md` to work on the `waymark3/`
package: where the implementation deviates from the design text, what the
build taught us, and the operational caveats. The same discipline as the
v1 and v2 notes — read this before extending the engine.

The package began as a fork of `waymark2/` (the design's "carries over
unchanged" list, taken literally) and was then transformed section by
section; `mealplan3/` is the same app migrated per the design's migration
sketch, and is the dogfood for every new declaration.

## Map

| Area | Where | The one thing to know |
|---|---|---|
| Relations | `core/guards.py` (`Relation`) | one tuple set (`accepts=`) or one comparison (`op="<="` — v2's `relates=`, retired with a pointing error), two consumers: render folds/advertises, `evaluate` enforces the same declaration |
| OneOf groups | `core/groups.py` | declared as `ClassVar[OneOf]` on a data model; the invoker enforces post-handler (clears or raises); wire hint rides `display.one_of` / the parts group |
| Vocab | `core/vocab.py` | `Vocab[str] = VocabField(...)` merges itself into `filterable`/`faceted` at import (`resource.py`); storage promotes it to `array` *by declaration*, not schema sniffing |
| Visibility | `core/visibility.py` + `server/grants.py` | `visibility_of(principal, now)` → FULL, `GrantVisibility` (token; optionally ceilinged by its approver), or `MemberVisibility` (owned + grant union); **render consults it while building**, the act path and the collection SQL (`restrict()`) consult the same object |
| Grants | `server/grants.py` (`Grant`, kind `grant`) | holder ∈ token\|member\|role; `granted_over` instance selectors; `approved_by` is the attenuation ceiling; `AgentGrant` is an import alias |
| Members | `server/members.py` | engine kind; invite → `activate` (first-login bind, run by the OIDC resolver as a system actor) → active |
| OIDC | `server/oidc.py` | code + PKCE relying party; id_token via issuer JWKS; HMAC-signed session cookie; invited-only by default (`open_registration=` to relax) |
| Event classes | `server/events.py` | one Dispatcher, `Subscription.classes ∈ {transition, observation}`; observations are at-most-once, no ids, no replay; `publish_observation` declares the event *name* at the emit site |
| Lookup routes | `server/router.py` + `pipeline.py` | `GET /-/lookup/{plural}[/{id}]` — plumbing is a route class; `peek=1` is gone (an unknown param now, loudly) |
| Route classes | `server/pipeline.py` | the class table (gates/emits/redacts) + `DRAFT_VERBS`; `gate("stream", …)` is the once-declared scoped-agent stream refusal |
| Rate limiter | `server/bus.py` (`RateCoordinator`) | hits ride the bus; **the echo is the count** (no origin skip) so N workers enforce one limit; guard-local fallback outside an engine |
| Upcasts | `core/resource.py` + `storage/postgres.py` | `shape=N, upcasts={n: fn}`; rows carry a `shape` column; `_hydrate` applies the chain lazily; the stored row is untouched until its next write |
| Audience | `server/drafts.py` | a frozen value type; the `"*"` string survives only as the storage encoding inside `Audience.token` |
| Ref labels | `server/invoke.py` (`_maintain_ref_labels`) | the engine writes `label=`-declared sibling fields on create and on every changed ref, one nesting level deep (where parts live); target label = `label_template` or `"{data.name}"` |
| Services | `server/external.py` (`Service`, `service_up`) | a failed call marks the service down for `backoff_seconds`; the guard renders `unavailable` + `retry_at`; conformance stubs by swapping `handler` |
| Mirrors | `server/external.py` (`Mirror`) | the machine IS the sync machine (`fresh/stale/conflicted/unreachable`); pull-on-read via `refresh_mirror` (router `_resource_doc`), push-on-write via `push_mirror` (router `act`); external changes land as `observe_external` by the `mirror-sync` system actor |
| Webhooks | `server/subscriptions.py` | at-least-once off the log: per-subscription cursors (`waymark3_webhook_cursors`) replay across restarts; HMAC `X-Waymark-Signature`; a subscription hears the world from its own creation |
| Approval-create | `server/router.py` + `grants.py` (`run`) | create with an approval-mode grant → 202 `approval_request` with `target_action: "create"`; `run` goes through `ctx.create` (same txn machinery) |
| Regate | `server/collab.py` | a dispatcher subscription re-runs the join gate on `grant` transitions and on the room's own resource's transitions; lapsed members get `closed` + 4403 |
| Query grammar | `server/router.py` (`parse_query`) + `client/py.py` (`merge_params`) | unknown params are Problems; clients merge into hrefs through one property-tested helper |
| Ownership | `storage/postgres.py` + `core/resource.py` | resource tables carry `owner` (creator principal, engine-stamped, `meta.owner` on the wire); `restrict()` pushes owner+granted ids into WHERE |
| Members (scoped) | `server/grants.py` (`MemberVisibility`) | under `member_visibility="granted"`: full over owned, grant union otherwise; engine kinds stay open; member approval-mode actions route through `approval_request` via the matching grant |

## Deviations from the design text (deliberate, tested)

1. **The router is class-assembled, not fully generated.** Design §2
   wants routes emitted from the registry as declared stage pipelines.
   What shipped: the route-class table (`pipeline.py`) with its gates
   consumed declaratively, draft verbs from `DRAFT_VERBS`, and the
   cross-cutting stages (visibility, observations, mirror hooks) applied
   per class — but handlers still exist as functions in `build_router`.
   The concern-weaving the section attacks is gone (the stream gate is
   declared once; redaction is not in the router at all anymore); the
   full generator remains open.
2. **Member scoping is opt-in per engine.**
   `Engine(member_visibility="granted")` turns §9's human half on: every
   unscoped human principal gets a `MemberVisibility` — full over what
   they own (the engine stamps `owner` at create), the union of their
   member- and role-held grants otherwise, pushed into collection SQL.
   The default stays `"full"` (v2's trust model) so single-family and
   dev-header apps keep working unchanged; production identity should
   pair `"granted"` with the OIDC resolver, whose `member:<id>`
   principals are exactly what grants' `holder_id` names.
3. **Engine kinds are open under member scoping.** Grants, approvals,
   members, subscriptions, and jobs render for every member — they are
   the negotiation and administration surface, and their guards
   (`no_self_dealing`: a holder never judges their own access) still
   judge. Finer-grained scoping of the admin surface itself is the next
   refinement, not this one.
4. **Facets are skipped under a restricted listing.** Facet counts are
   storage-wide; a member whose view is owner-plus-granted-ids gets no
   facets rather than a leak. Restricted-scope facet computation is a
   follow-up.
5. **Mirror staleness is pull-through, not a stored `stale` state.** A
   TTL-expired pull-on-read mirror re-pulls on every GET (no "observed,
   unchanged" transition — that would be audit noise) and only writes
   when something changed. The `stale` state exists for feed-fed mirrors
   (`mark_stale`); pull-on-read mirrors normally never occupy it.
6. **Webhook delivery skips a poisoned event after its retries.**
   Delivery is at-least-once off the log via per-subscription cursors
   (an outage replays across restarts), but after the declared attempts
   fail the cursor advances past the event with a warning — a broken
   endpoint must not dam the log. The trade is stated where it happens.
7. **Same-shape rate limiters share a window.** Cross-worker keys must be
   process-stable, so the window key is the guard's parameter shape (or
   an explicit `scope=`), not the guard instance. Two `rate_limit(5, 60)`
   declarations on different actions share a budget unless scoped apart.
8. **Idempotent replays return the stored body.** With projection at
   render, a replayed response equals what that principal saw when it
   executed. v2's `_scoped_response` re-redaction is gone; the stored
   reply cannot out-say a live one *for the principal that stored it* —
   idempotency keys are per-credential in practice.
9. **`AgentGrant` survives as an import alias.** The kind is `grant`
   (holder ∈ token|member|role, design §9); token-held grants keep the
   agent-link UX (minted token, `agent_principal` for following).

## Known gaps

- **Vocab `values=` (closed vocabularies)** render as `x-vocab.values`
  but do not yet emit a static schema enum or reject unknown members at
  the storage layer; open vocabularies (the dogfood) are complete.
- **`x-display.relation` UI behavior** — unchanged v2 gap: the generic
  UI still doesn't set min/max between related inputs.
- **Relation-aware input synthesis** — conformance's walker still leans
  on `@example_input` for relation-guarded actions (the gap *fuzzer* is
  relation-aware; the synthesizer is not).
- **`waymark3 extract-messages`** — still the v1 stub (i18n punt).
- **OIDC logout** clears the session cookie but does not call the IdP's
  end-session endpoint (single logout).
- **Mirror `list`/discovery sync** — mirrors sync per-resource; there is
  no "discover new external documents" sweep. Create mirrors explicitly.
- **Member approval-mode** (a member-held grant saying `approval`) is
  wired through the same `approval_request` machinery as token grants but
  exercised by fewer tests than the token path.
- **Closed-vocabulary role sets** — roles ride `Principal.roles` (dev
  headers or OIDC member roles); there is no role *registry* resource,
  so a typo'd role name in a grant silently grants nobody.

## Operational caveats

- **Engine tables are `waymark3_*`** and resource tables gained a `shape`
  column — do not point a v2 stack at v3 resource tables.
- The **initial migration** for the mealplan3 registry is
  `migrations/waymark3/0001_initial.sql`; conformance round-trips the
  emitted DDL against the declared snapshot every run, so `waymark3
  migrate` after any schema-affecting change keeps the contract.
- **The OIDC resolver needs cookies over HTTPS in production** (the
  session cookie is HMAC-signed but not encrypted; set `session_secret`
  from a real secret store). Keycloak: create a confidential client, set
  the redirect URI to `<host>/auth/callback`.
- **`Engine(members=False, webhooks=False, agent_links=False,
  presence=False)`** turns each engine kind/stream off independently.
- **Mirror adapters run inside request handling** (pull on GET, push
  after act). A slow external system makes those requests slow — set
  `ttl_seconds` accordingly and keep adapters timeout-disciplined.
- **The v2 conformance suite for `plan`/`grocery_list` was broken at
  HEAD** (the conftest factory still sent the single-theme-era `theme`
  key, which the wire's additionalProperties check rejects before the
  model's fold). Fixed in `conftest.py` (`themes=["mexican"]`) — this
  also un-breaks `pytest --waymark2 plan`.
- **`pytest --waymark3` composes with the v1 and v2 plugins** — fixtures
  are namespaced (`wm3`, `waymark3_engine`); all three suites share
  `WAYMARK_TEST_DSN`. Full conformance for all mealplan3 kinds runs
  ~25 minutes; per-kind (`--waymark3 plan`) is the dev loop.
- **Parallelism is per-worker databases, not shared ones.** `make test`
  and both conformance targets run `pytest -n auto`: xdist workers each
  get `<db>_gwN` (created on demand by `per_worker_dsn`), which cuts the
  full waymark3 conformance from ~40 minutes to a few. What is still NOT
  safe is two concurrent *invocations* sharing one base DSN — they derive
  the same worker names and `drop_all` each other mid-walk; give each
  invocation its own database.
