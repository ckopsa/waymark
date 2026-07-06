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
| Roles | `server/roles.py` (kind `role`) + `grants.py` (`role_registered`) | the role registry (rides `members=`): `active ⇄ retired`; a grant with `holder_kind="role"` must name an active role — enforced by the `role_registered` guard on `request_access` and by `create_guards = (role_registered_create,)` at create (design E9) |
| Warning guards | `core/guards.py` (`severity=`) + `server/invoke.py` + `problems.py` (`WarningRefused`) | design E1: warning Denies ride the action entry (`warnings`), refuse with the `Waymark-Acknowledge` affordance, and record overridden names in the transitions row (`acknowledged`, nullable). Warnings gate create too via `create_guards` (design E9); the walker auto-acknowledges on retry for actions and creates alike |
| Uniqueness | `core/resource.py` (`unique=`) + `storage/postgres.py` (`UniqueViolation`) + `invoke.py` (`_conflict`) | design E2: DB constraints on promoted columns; refusal = `already-exists` Problem with `existing.href` (looked up in a fresh session — the violating txn is aborted). Inside bulk items the plain Conflict (no href) is raised instead. Unique create examples for conformance must mint fresh values (see the role example) |
| Four eyes | `core/guards.py` (`four_eyes(of=…)`) + `storage.transition_actor` + `ctx.actor_of` | design E3: denies the latest performer of `of`; probes at render so the performer sees `unavailable` with the same reason. System actors are NOT exempt — a cascade that performed `of` is barred like anyone; compose with a `system_only` bypass explicitly if an app wants otherwise |
| Ownership | `core/owns.py` (`Owns`/`Rollup`/`Seed`/`rollup_is`) + `server/owns.py` (`CascadeRunner`, `compute_rollups`) + `server/consumers.py` (`LogConsumer`) | design E4: cascades drain `transitions_since` behind the `waymark3_cursors` row (seeded at head on first boot — no history replay); child transitions ride the parent's correlation id as `waymark-cascade`; rollups (count and sum-of-promoted-field) are a top-level envelope key, `vis.full` only, computed per render call site. `Seed` instantiates children at parent create, same txn, creator-audited, correlation shared; template edits never retro-propagate (recorded policy). Rollups are also collection query params (`?name=`, `_gte`/`_lte`, `?sort=±name`) compiled as correlated subqueries — names checked against parent param collisions at assembly. An `Owns` edge makes the child kind REQUIRED at assembly. `LogConsumer` is the shared cursor-drain base (cascade, blob janitor) |
| Attachments | `server/attachments.py` (kind `attachment`, `BlobStore`, `Memory`/`File` stores, `BlobJanitor`) + router bytes routes | design E5: `reserved → uploaded → removed`; bytes via `PUT/GET /attachments/{id}/bytes`; size/sha stamped by the `attachment-bytes` system actor. Retention purges via the `blob-purge` log consumer — post-commit, durable, covering every invoke path. `duplicate` (design E5/E8) mints a `reserved` copy for a new target in the invoking txn+correlation (`Creates("attachment")`, provenance in hidden `copied_from`); the `blob-copy` consumer copies bytes post-commit via `BlobStore.copy` and marks the copy uploaded as the `attachment-bytes` actor — byte availability is eventual, and a purged-source copy honestly stays `reserved`. The memory store is the dev default; production must wire real blobs |
| Service jobs | `ctx.defer` (`invoke.py`) + `jobs.py` (`JobArtifact`, `sweep_orphan_jobs`) + `ServiceDown.cause` / `ServiceCallError` | design E6: job created in the handler's txn (the runner waits out the commit — bounded 5s); artifacts run through `Service.call` — one failure downs the service and the rest fail fast with `retry at`, unless the service declares `down_on_error=False` (intake's independent sub-imports), where each artifact fails alone with the adapter's words and the service stays up. Multi-worker safe: runners hold a `waymark3_job_leases` row (claim-or-steal on expiry, 30s ttl renewed per item — a pathological item blocking past the ttl can lose it), the boot sweep skips a lease with a future expiry and cancels once it lapses. Resume-instead-of-cancel is the remaining punt |
| Predecessor refs | `core/refs.py` (`Predecessor`, `ref_predecessor`) + `invoke._resolve_predecessors` | design E7: resolved at create before `on_create` (carry-forward reads the ref there); the ≤ comparison seeds from the new instance's own order value, so backdated creates slot in. The declaration home is `Data` — a `Create` override hiding the field from the form keeps the opts on `Data` |
| Create guards | `core/resource.py` (`create_guards`) + `server/invoke.py` (`_create_guards`) | design E9: guards over the VALIDATED create input, `check(None, data, ctx)` with r=None; severity splits exactly as on actions (E1) — refuse is a `GuardRefused` with no `resource=` embed, a warning demands `Waymark-Acknowledge` and the override lands on the create transition's `acknowledged`. Enforced, not yet projected (dry-run is the preview); `vars_fn` never garnishes create refusals (r=None); `ctx.create` cannot acknowledge |
| Touches | `core/touches.py` (`Creates`/`Advances`/`Delegated`) + `invoke.py` (`_touch_scope` around the handler; enforcement in `_child_create`/`_child_invoke`) | design E8: rendered inside `effect.touches`; undeclared ctx writes raise `DefinitionError` naming the missing declaration, aborting the txn. Exempt by construction: seeds, the cascade runner, `ctx.defer`'s job row; `approval_request.run` declares `Delegated`. Conformance `test_touch_truth` verifies declared-vs-log per correlation. There is deliberately no `Moves` — re-parenting is a child action |

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
3. **Engine kinds are open under member scoping — except credentials.**
   Grants, approvals, members, roles, subscriptions, and jobs render for
   every member — they are the negotiation and administration surface,
   and their guards (`no_self_dealing`: a holder never judges their own
   access) still judge. The two credential fields are the exception:
   `grant.data.token` and `subscription.data.secret` render only for the
   resource's owner (`MemberVisibility.SECRET_FIELDS`); an agent still
   reads its own grant's token through the negotiation surface. Finer-
   grained scoping of the admin surface itself is the next refinement.
4. **Facets are computed within the restricted scope.** `storage.facets`
   takes the same `restrict` pushdown as `query()`: a member whose view
   is owner-plus-granted-ids gets facet counts over exactly those rows —
   the counts can neither leak nor lie.
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
10. **Concealment holds out-of-state.** An action a hide-flagged guard
    conceals returns 404 on invoke even when the state check would have
    said "wrong state" — the wire must not narrate what render hides.
    Caught by conformance the first time a hide-guarded engine action
    (`attachment.mark_uploaded`) was walked.

## Known gaps

- **Closed vocabularies cover fields declared with `VocabField`.**
  `VocabField(open=False, values=…)` emits `items.enum` on the wire and
  the invoker refuses unknown members (create and action inputs, one
  chokepoint: `_validate` → `closed_vocab_errors`). An input model that
  declares a look-alike plain `list[str]` field is NOT chased — the
  declaration is the enforcement's address; declare the input field with
  the same `VocabField` to get the same judgment.
- **`x-display.relation` UI behavior** — done for comparison relations:
  the generic UI's action form wires related inputs so the left side of
  `a<=b` takes `max` from the right and the right takes `min` from the
  left (mirrored for `>=`/`>`); `==` stays enforcement-only. No UI test
  harness exists — the change is minimal and untested by design.
- **Relation-aware input synthesis** — done: `synthesize_input` overlays
  one admissible relation tuple (components in `judges` order, preferring
  tuples that agree with the single-field acceptance sets); an empty
  tuple set raises `SkipState` naming the relation. `assign_meal` no
  longer needs an `@example_input`; comparison relations (`op=`) still
  synthesize from the schema alone.
- **`waymark3 extract-messages`** — still the v1 stub (i18n punt).
- **OIDC single logout** — done: `/auth/logout` 302s to the IdP's
  `end_session_endpoint` (with `post_logout_redirect_uri` + `client_id`)
  when discovery advertises one; the local cookie clears either way, and
  an unreachable IdP falls back to the local-only logout.
- **Mirror `list`/discovery sync** — mirrors sync per-resource; there is
  no "discover new external documents" sweep. Create mirrors explicitly.
- **Member approval-mode** (a member-held grant saying `approval`) runs
  the same `approval_request` machinery as token grants, now covered
  end-to-end (`test_member_approval_mode_runs_end_to_end`: 202 → approve
  → run, audit actor = the runner).
- **`Member.data.roles` entries are not validated against the role
  registry** — grants to roles are (the `role_registered` guard +
  `Grant.on_create`), but create runs no guards, so a typo'd role on an
  *invite* still names nobody until a grant tries to use it. Validate at
  the grant, where authority is actually conferred.

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
