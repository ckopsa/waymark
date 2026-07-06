# Waymark 3.0 — design

A redesign of Waymark that starts from what 2.0 learned the hard way.
Companion documents: `waymark2-design.md` (the 2.0 case), `waymark2-notes.md`
(what 2.0 shipped and where it deviated), and the post-rebuild commit
history from `ec49829` to `079e0e6`, which is the evidence base here the
way v0.1's three layers were the evidence base for 2.0.

## Why 3.0

2.0's law — *nothing about a resource is inferred that the definition could
have declared* — worked, and the post-rebuild history proves it lopsidedly.
Every feature that could be modeled as **a declaration, a resource, or a
transition** snapped in: the mealplan port collapsed five check bodies into
`accepts` sets and 5 factories + 11 example inputs into 2 + 4 (`ec49829`);
follow-a-principal shipped with "no new wire vocabulary" because
transitions were already the quantum of accountability (`4e3bd9b`); agent
grants and approval requests pass the full conformance suite *because they
are ordinary resources* (`793aaa4`); mobile support was CSS only because
rendering is envelope-driven (`6e1740c`).

And every feature that was none of those three things had to be hand-woven.
Presence took three corrective commits (`f047ded`, `1470348`, `ddb08c7`) —
a parallel hub, an inferred event vocabulary, then an invented `peek=1`
wire flag to un-announce plumbing GETs. Agent-link *enforcement* (unlike
the grant resources) could not be expressed in any existing seam, so
`apply_scope` was threaded through every endpoint and `Principal` grew a
duck-typed `scope: Any`. Facets touched nine files across every layer
(`add8795`). The tally inside `build_router` today: one ~514-line closure
holding 81 grant/scope touchpoints, 65 draft/collab touchpoints, 26
presence touchpoints, and the "scoped agents can't read streams" gate
copy-pasted verbatim three times.

The strain moved from the definition to the **delivery pipeline**. So the
3.0 law is 2.0's law aimed at the server:

> **Nothing about a request's handling is woven that a declaration could
> have staged. Every concern is exactly one of: a declaration on the
> definition, a resource, or an event class on the bus. A feature that
> needs a fourth kind of thing is a design error, not an implementation
> detail.**

The admission test for any 3.0 feature is the one 2.0 passed for
guards-as-data and failed for everything added after it shipped: can it
snap in via one declaration consumed by both the advertiser and the
enforcer — or must it be threaded by hand through the router, the
renderer, and a redactor?

## What carries over unchanged

These survived the post-rebuild feature wave without churn and are kept
verbatim:

- The envelope: `state`/`data`/`actions`/`unavailable`/`links`; `display`
  quarantined; `summary` budgeted; `parts` as refinement.
- Uniform invocation, the single invoker, idempotency replay before the
  state check, natural replay before guards (the 2.0 deviation that proved
  byte-honest).
- The transition log as audit trail + outbox + idempotency anchor + event
  stream; the `?actor=` firehose; `Last-Event-ID` replay.
- Errors as hypermedia; the drain rule; discovery via
  `/.well-known/waymark`; the generic clients that invent no affordances.
- Drafts as engine-owned sub-resources with per-field `revs`/`authors`;
  `waymark-relay/2`; the single `DraftStore.save_fields` write path.
- Agent links **as resources**: `agent_grant` and `approval_request` state
  machines, negotiated as ordinary Waymark. (Their *enforcement* changes —
  §1.)
- The declaration vocabulary — `Guard`, `Ref`, `Safety`, `Edit`,
  `DraftPolicy`, `PartScope`, demand classes — upgraded below, not
  replaced.
- Migrations as snapshot-diffed SQL revisions with the CI round-trip.
- Conformance as the proof that wire, enforcement, audit, events, and
  safety agree with the definition module.

Everything below is what changes.

---

# 1. Visibility is projection, not redaction

The 2.0 scar: least-privilege enforcement is a ~500-line post-hoc envelope
rewriter (`apply_scope`) bolted *after* render — driven by
`getattr(principal, "scope", None)` duck-sniffing at ~a dozen call sites,
detecting collections by the string suffix `_collection`, recursing into
arrays by `isinstance(value[0], dict)` sniffing, and shipping
`out.pop("parts")  # re-admit later if needed` — a TODO in disguise. Its
three parallel maps (`FieldMap`/`ActionMap`/`ArgMap`) each carry a
*different* "unlisted means…" default the reader must memorize. This is
exactly the shape 2.0 §1 outlawed for guards: enforcement in one place,
advertisement in another, drift in between.

In 3.0 the guard unification is applied to grants: **one visibility
declaration, two consumers.** `project(instance, principal, depth, now)`
already takes the principal — 3.0 makes it honor it.

- `Principal.visibility` is a typed `Visibility` capability (the full-view
  singleton for humans; grant-derived for `wmk_` tokens), not `scope: Any`.
- The projector consults it while *building* the envelope. A field the
  principal cannot see is never rendered — so it can't need popping, and
  `parts` inherit visibility for free because a bound action (§4) is the
  same object as its parent.
- The invoker consults the **same object** to enforce, and the stream
  dispatchers consult it to filter — deleting the gate that is currently
  copy-pasted across `firehose`, `presence`, and `resource_events`.
- One `unlisted=` default, stated once on `Visibility`, not three
  conventions across three maps.
- Default deny remains absence — but absence produced at the source.

What this deletes: `apply_scope`, `_scope_fields`' type-sniffing recursion,
the `_collection` suffix dispatch, the `dead_grant()` null-object sentinel
(an unknown token resolves to the empty `Visibility`, which needs no
special object), and the parts-redaction TODO — unrepresentable rather
than fixed.

# 2. The router is assembled, not written

The 2.0 scar: `build_router` is one hand-woven closure. Draft routes are
registered twice per verb by hand; `act` interleaves five features in
fifty lines (grant gating, arg approval, dry-run presence, invoke,
collab-room close, scoped response); "Approval-mode create is not
supported yet" ships as a prose string in a handler. Every post-design
feature was edits to fifteen handlers.

In 3.0 the router is **generated from the registry**, the way conformance
already is. Each route class is a declared pipeline:

```
read:   authenticate → project → resolve → deliver
write:  authenticate → fence → guards → invoke → respond
stream: authenticate → subscribe → filter → deliver
lookup: authenticate → project → deliver          (§3 — plumbing, no presence)
```

- A cross-cutting feature ships as a **named stage with a declared
  position** — visibility is a parameter of `project` (§1), engagement
  events are emitted by the draft/dry-run stages themselves (§3), the
  draft-consume room close is a bus consumer of the draft's transition
  (already its design intent), not a tail on `act`.
- Draft routes, verb aliases, and part addressing are emitted from the
  declarations that imply them; nothing is registered twice by hand.
- The approval-create gap closes structurally: approval capture is a stage
  on the write pipeline, and create *is* a write, so it composes instead
  of being a 501 in prose.
- The collab join gate and the act gate become the same authenticate-
  against-affordance stage, re-run on affordance-changing bus transitions
  — retiring the "a lapsed principal keeps the socket" caveat.

Conformance gains the corresponding proof: for every route class, the
suite walks the declared stages and asserts the pipeline's composition
equals the observed wire behavior — the router can no longer drift from
the registry because it *is* a projection of the registry.

# 3. Two event classes, one taxonomy

The 2.0 scar: presence never stabilized because it was inference stacked
on inference. `PresenceHub` is a parallel fan-out beside the dispatcher
with its own queues and channel; the event *type* is derived from whether
the payload happens to contain an `action` key
(`"engaged" if payload.get("action") else "viewed"`); `viewed`/`engaged`
are reverse-engineered from unrelated wire facts in the router; and
`peek=1` was invented so picker sub-fetches stop announcing places the
principal never looked. Three commits of chasing over-announcement.

3.0 declares the taxonomy the bus already implies. Every event is one of
two classes:

| class | durability | ordering | storage | examples |
|---|---|---|---|---|
| `transition` | at-least-once, replayable (`Last-Event-ID`) | log-ordered | the transition log | every write, draft consume/discard |
| `observation` | at-most-once, drop-on-pressure | best-effort | none, ever | `viewed`, `engaged`, room membership |

- Same bus, same `actor`/`kind`/`at` envelope; `class` is a declared field
  on the event, not a property of which hub you subscribed to.
  `PresenceHub` folds into the one dispatcher with a per-class delivery
  discipline.
- The `peek` sentinel dies by construction. Framework-generated plumbing
  hrefs — ref-label resolution, picker option queries, facet fetches — are
  emitted onto the **lookup** route class (§2), so "was this navigation?"
  is answered by *which declared route was traversed*: a server-observed
  fact, no client cooperation, no wire flag. The 2.0 principle ("derived
  from wire facts only") is kept; the facts become declarations.
- `engaged` is emitted by the stages that own the semantics — the draft
  GET stage (`via: form`), the dry-run stage (`via: dry_run`), the discard
  transition (`via: discard`) — not by the router sniffing endpoints.
- What stays deliberately invisible stays invisible: a modal closed
  without wire traffic, draft autosave cadence. Observation events remain
  unstored and unreplayable; reads still never enter the audit trail.

# 4. Binding: parts stop being a parallel universe

The 2.0 scar — and where the app author actually bled. Placed actions
re-implement everything top-level actions get: `_bind_part_entry`
re-renders the draft advert with a string-patched
`collab["href"] += f"?part={part_key}"`; the meal picker is declared
**twice** — once as `RefField(pick=Query(state="on_list"))` and again as
`field_display={"meal_id": {"params": {"state": "on_list",
"themes": "{item.theme}"}}}` — because per-part templating can't reach the
`Ref`; and the denormalized `meal_name` that `Ref` promises is hand-copied
in `_assign` because the declaration doesn't reach nested arrays. Three
call sites re-derive `(audience, part_key)` to address "the same" draft.

In 3.0 **binding is a first-class operation**:

```python
bound = action.bind(part=days, key="2026-07-07")   # the engine does this;
                                                   # shown for the contract
```

- `bind` yields the *same* `ActionDef` closed over a `Binding` context —
  not a re-rendered dict. Every consumer reads the one context: the draft
  key (`resource, action, binding, audience`), picker `Query` templates
  (`{item.*}` resolves against the binding), demand class (bound key
  fields are `const`, hence no demand — as today, but derived once),
  visibility (§1), and hrefs (emitted, never string-patched).
- `Ref` inside a nested model gets the same generated label behavior as
  top-level: declare `label="meal_name"` and the engine maintains the
  denormalized column on assign — the hand-copy in `_assign` deletes.
- The picker is declared once, on the `Ref`, where `{item.*}` params now
  belong: `pick=Query(state="on_list", themes="{item.theme}")`. The
  `field_display` duplicate — two declarations that can silently disagree
  — becomes unrepresentable.
- The `SHARED = "*"` audience string — v1's sentinel, relocated rather
  than killed by 2.0 — becomes a typed `Audience` (`Principal(p) |
  Shared`), decided by `DraftPolicy` exactly as today but no longer a
  magic string any code path can accidentally match.

# 5. Guards grow relations and one-of groups

The 2.0 scar: cross-field logic falls off the declaration cliff. `accepts`
and the closure rule require exactly one judged field, so
`meal_matches_theme` (meal × date × theme) drops to a `check=` with four
hand-written `reason` strings — one of which *duplicates* `date_in_plan`'s
message — and hardcodes the `ROTATING` branch. Eating-out is four parallel
fields (`meal_id`, `meal_name`, `eating_out`, `eating_out_where`) whose
mutual exclusion is re-maintained by hand in three handlers, and "day is
covered" is re-derived as a boolean expression in a fourth place.

3.0 adds the two declarations the app has been paying for by hand:

**`Relation` — multi-field acceptance.**

```python
meal_fits_day = Relation(
    judges=("meal", "date"),
    reads=(Ref["meal"],),
    accepts=lambda r, ctx: {(m.id, d.date)
                            for d in r.data.days
                            for m in ctx.matching(d.theme)},
    explain="{meal} does not serve {theme} night ({date}).",
)
```

One acceptance set, two consumers — 2.0 §1 extended past single fields.
Render folds the relation into the bound action's schema (per-part enums:
*this* day's admissible meals); the engine enforces membership in the same
set. `relates=("start", "<=", "end")` becomes the comparison special case
of `Relation`, and the generic UI finally sets min/max between related
inputs — closing the 2.0 known gap. `check=` survives only for judgment
that genuinely isn't set membership, and the closure rule now counts a
`Relation` as closing every field it judges.

**`OneOf` — declared exclusivity.**

```python
class DayPlan(BaseModel):
    coverage = OneOf("meal", "eating_out", clears=True)
    meal: Ref["meal"] | None = RefField(default=None, label="meal_name")
    eating_out: Outing | None = None      # {where: str | None}
```

One declaration yields: the mutual-exclusion invariant (setting one arm
clears the other — the three hand-written clearing blocks delete), the
wire `x-display.one_of` hint so clients render the choice as a choice, a
derived predicate (`coverage.filled`) that `all_days_covered` consumes
instead of re-deriving `not d.eating_out and d.meal_id is None`, and the
conformance case that tries to set both arms and must be refused.

# 6. Vocabularies are a type

The 2.0 scar: the `themes` need was met one layer at a time. Array
membership required engine + storage + schema + router changes in one
commit (`ee4b9a9`); faceting required declaring `filterable(themes=…)`
*and* `faceted = ("themes",)` plus an import-time check to keep the two
agreeing; storage sniffs `promoted.get(name) == "array"` to pick
operators; render synthesizes the enum from observed data; and `In`
filters shipped as a stringified Python list no client could re-read
until `add8795` fixed the wire. Meanwhile `ROTATING = "rotating"` — "not
yet chosen" with no representation — is special-cased in four files.

3.0 collapses the whole column into one field type:

```python
class MealData(BaseModel):
    themes: Vocab[str] = VocabField(
        open=True,                 # values minted by use (closed=Enum-like)
        filter=Membership,         # ANY/ALL on the wire as comma-lists
        facet=Observed(counts=True),
        placeholder="rotating",    # a declared member meaning "not chosen"
    )
```

One declaration generates: the JSONB generated column and its GIN index,
the `@>`/`?|` conditions, the comma-list wire serialization *and parsing*,
the facet computation with its render-time enum (still observed — an open
vocabulary's values are genuinely data — but now an explicit property of
a declared type, not inference the design has to apologize for), the
`x-in` advertisement, and the picker behavior. `faceted`-alongside-
`filterable` and its consistency check delete. `placeholder=` gives
"Sunday's theme is not yet chosen" a first-class representation: it
renders distinctly, it is excluded from "covered" predicates by
declaration, and the four hand-threaded `ROTATING` branches reduce to the
one in `themes.py` that declares it.

# 7. The query grammar is spec

The 2.0 scar: the collection query language was the single most bug-prone
surface, and both bugs reached production. `depth` passed as httpx params
*replaced* the href's query string, silently dropping filters — found live
when a follower watched an agent link a prep task to the wrong plan
(`10d5278`). `In` filters round-tripped as `['a', 'b']` — a stringified
Python list (`add8795`). Both were failures of an *unspecified* wire
grammar that each client re-implemented.

In 3.0 the query grammar is spec text with conformance teeth:

- One grammar: `?field=v`, `?field=a,b` (membership per the field's
  declared `filter`), `?field_after=`/`_before=`, `?state=`, `?depth=`,
  `?part=` — every parameter either declared by the resource or owned by
  the spec; unknown parameters are a Problem, not a silent no-op.
- **Hrefs are authoritative.** Clients merge parameters into an
  advertised href; they never rebuild it. The client contract makes
  `10d5278` unrepresentable: the reference clients ship one
  `merge_params` and the suite property-tests it.
- Conformance round-trips every advertised collection href — including
  facet-refined and `x-in` hrefs — through parse → serialize → parse and
  asserts the fixpoint, for the generic UI, the agent client, and the CLI
  alike.

# 8. Ship the punts that compounded; kill the residual inference

2.0's punt rule — *punt things that don't compound* — was right, and its
punt list mostly held (i18n and CRDT/OT stayed cheap to defer). But two
things it shipped as *promises* compounded anyway, and three pieces of
v1-style inference survived in new coats:

- **`shape`/`upcast` never landed** (design §8 specified it), and the
  proof is `meal.py`'s `_legacy_theme` — a `model_validator(mode="before")`
  doing read-time migration with no shape version, exactly the ad-hoc
  form the mechanism was designed to replace. 3.0 ships the declared
  upcast chain (`shape=2, upcasts={1: fold_theme}`), and conformance
  replays every declared upcast against stored fixtures of the prior
  shape.
- **Signature sniffing is v1's AST heuristics in an `inspect` coat.**
  `_fn_needs_input`, `_wants_ctx`, and the `vars_fn` "empirical:
  token_prose covers it" punt recover guard behavior from callables at
  runtime. In 3.0 the callable's needs are declared (`accepts` takes
  `(r)` or `(r, ctx)` per the guard's `reads` — which the declaration
  already states; `vars` callables declare the names they produce), and
  the checker reads declarations, not signatures.
- **The rate limiter finally rides the bus.** The 2.0 deviation note
  ("the bus exists but the limiter doesn't ride it yet") is the last
  per-process counter; §7's original argument — don't give features
  private plumbing to outgrow — applies to it verbatim.
- **One spelling per concept.** `Safety.fence` vs wire
  `requires_if_match` collapses to `fence` on both sides of a major
  version; the property bridge deletes.
- **Token transport is declared, not sniffed.** `_bearer_token` checking
  both a header and an `agent-token` query param is 2.0 §11's
  transport bend reappearing for agents. The credential extractors are
  per-transport declarations on the resolver, as §11 intended — the WS
  and SSE extractors are listed, not fallen back to.

# 9. Identity is a resource; authority is a grant

The 2.0 scar here is mostly an absence with one real scar inside it. The
dev principal is a dropdown — a resolver implementation that was never
joined by a second one. And agent links, the one authorization feature
that exists, is a one-off: grants hold only token principals, select only
whole kinds, and their self-negotiation actions are hardcoded string sets
(`AGENT_GRANT_ACTIONS = {"request_access"}`) imported by the router and
re-checked there. There is no member, no role, no owner, and no way to
grant less than a kind.

**AuthN is externalized; authZ never is.** Keycloak (or any OIDC IdP)
owns login, passwords, MFA, federation. Waymark ships the relying-party
dance — auth code + PKCE, session cookie, refresh — as one more
implementation of 2.0 §11's credential-resolver interface; the dev
dropdown becomes what it always secretly was, one resolver among several,
kept for tests. Authorization cannot follow identity out the door, for a
reason specific to this framework: affordances are advertised per
principal. `project(instance, principal)` must know what you may do in
order to render what you may do — an external policy engine cannot fold
into projection. IdP claims (groups, roles) are *inputs* to the
visibility computation; the computation stays in the engine, where
advertisement and enforcement are one object (§1).

**`member` is an engine-owned kind.** The pattern that made agent links
snap in, applied to people: an admin's invite is a create into `invited`
(the outbox sends the email); first OIDC login binds `sub` to the invited
member → `active`; deactivation is a transition. Audit trail, generic UI,
and conformance come free — the admin console is a collection view.

**One `grant` kind, three holders.**

```
grant(holder, over, visibility, grantor)
  holder ∈ member | token principal (agent) | role
  over   ∈ Kind("meal_plan") | Resource("meal_plan", 88) | Owned(by=grantor)
```

- **RBAC**: a `Role` is a named, *declared* `Visibility` template —
  policy belongs in code review — and a role *assignment* is a grant
  whose holder is a member. What "admin" means is reviewable; who is
  admin is auditable.
- **Ownership** is a declared rule, not a mechanism: resources carry
  `owner: Ref["member"]`, auto-set from the creating actor (which the
  transition log already knows), and visibility entries may be relative —
  `Where(owner=Self)`. The baseline role reads: full control over what
  you created, nothing of anyone else's until granted.
- **Sharing** is the negotiation loop agent links proved, with humans as
  holders: request access → owner approves → a grant exists. Same state
  machine, same UI, same audit.
- **`agent_grant` folds in.** An agent link is a grant whose holder is a
  token principal; the hardcoded self-negotiation sets become ordinary
  declared actions on the `grant` kind.

**Granularity is a selector; authority to grant derives from ownership.**
The selector answers *what*; ownership answers *who may permission it*.
The owner of plan 88 approves a grant over `Resource("meal_plan", 88)`;
only a role holding kind-level authority approves `Kind("meal_plan")`.
The request-access flow routes to whoever holds that authority — which
the engine can compute, because authority is itself visibility. One
constraint is law, not guidance: **selectors must be indexable** (kind,
id, owner, a declared filterable field), because effective visibility
must push into collection SQL as
`WHERE owner = :me OR id IN (:granted)`. A "plans I can see" computed by
post-filtering rendered envelopes is `apply_scope` reborn — the exact
scar §1 exists to kill.

**Delegation is attenuation.** A member granting their agent "a subset
of my controls" is an invariant of the grant state machine —
`granted ⊆ grantor's effective visibility` — checked at mint *and*
evaluated as a live intersection at projection time. If your access to
plan 88 lapses, your agent's lapses in the same render: revocation
cascades by construction, not by cleanup job. (This is Macaroon-style
capability attenuation expressed in resources instead of cryptography;
the cryptographic form is a punt that doesn't compound — see below.)

# 10. The world outside the envelope

The 2.0 posture is that everything inside the boundary plays by the
rules and nothing outside it exists. The one seam that acknowledges an
outside — a guard's `reads=("services.x",)` — is a naming convention,
2.0's own definition of a scar. External truth (a household todo list, a
calendar) cannot be represented at all: the mealplan's prep tasks carry
a raw `event_id` into a calendar the framework knows nothing about. And
the only consumer of the transition log is a client that already speaks
Waymark.

3.0 cannot make third parties speak the format; it can be honest about
the boundary in both directions.

**Declared services (egress).** `Service` promotes the string convention
to a declaration: name, operations, timeout/retry policy, credential
reference. Conformance gets an auto-stub per declared service. The
payoff is the honest one: when a service is down, every action that
declared it renders in `unavailable` with a reason and `retry_at`. Most
frameworks 500 at invoke; this one un-advertises the button.

**The outbox is a product (subscriptions).** The transition log is
already an outbox — expose it. A `subscription` is an engine-owned
resource (url, kind filter, secret); delivery is at-least-once off the
log with backoff, and each attempt is auditable. Third parties integrate
by consuming signed transition JSON without ever learning the envelope.

**Mirrors (the anti-corruption layer).** For truth that lives elsewhere,
a `Mirror` is a resource whose adapter implements
`pull(id) → (doc, etag)` / `push(doc) → etag` under a declared sync
policy (TTL pull, push-on-write, webhook-fed). Inside the boundary it is
a full citizen — envelope, guards, single-invoker writes, drafts,
visibility. What keeps it honest rather than leaky:

- Its state machine includes sync states —
  `fresh / stale / conflicted / unreachable`. Staleness renders
  (`meta.synced_at`); `conflicted` is a state with a `reconcile` action,
  not a silent last-writer-wins.
- External mutations we observe arrive as transitions by a `system`
  actor, so audit, SSE, and even follow work over changes we didn't
  make.
- The envelope promises only what the adapter can: no idempotent-replay
  claim beyond the external system's etag discipline.

**MCP both ways; federation is already cheap.** Adapters may be MCP
clients — a uniform wrapper for the growing set of services that already
expose MCP. And Waymark-to-Waymark needs almost nothing new: links are
URLs, discovery is `/.well-known/waymark`, and a cross-server grant is a
grant whose holder authenticates with the other server's token.
Hypermedia was always the federation story.

The through-line, and the admission test passing: identity lands as
resources (`member`, `grant`) plus declarations (`Role` templates,
ownership rules); integration lands as declarations (`Service`,
`Mirror`) plus an event discipline (system-actor observed transitions,
subscriptions off the log). Neither needs a fourth kind of thing.

# 11. The scar table

2.0 closed its case with the fate of every v1 warning. The same table for
2.0's own scars:

| 2.0 scar | 3.0 fate |
|---|---|
| `apply_scope` post-hoc redaction; `scope: Any` sniffing | unrepresentable — visibility is a projection input (§1) |
| `_collection` suffix dispatch; `dead_grant()` sentinel | unrepresentable (§1) |
| `parts` redaction TODO | unrepresentable — bound actions inherit visibility (§1, §4) |
| stream gate copy-pasted ×3 | one pipeline stage (§2) |
| "Approval-mode create is not supported yet" | composes — approval is a write-pipeline stage (§2) |
| lapsed principal keeps the collab socket | re-gated on bus transitions (§2) |
| `PresenceHub` parallel fan-out; event type from payload shape | one bus, declared event classes (§3) |
| `peek=1` wire flag | unrepresentable — lookup route class (§3) |
| presence semantics sniffed in the router | emitted by owning stages (§3) |
| `_bind_part_entry` re-render; href string-patching | binding is first-class (§4) |
| picker declared twice (`Ref` + `field_display`) | unrepresentable — `{item.*}` lives on the `Ref` (§4) |
| hand-copied `meal_name` in `_assign` | generated — `Ref` labels reach nested models (§4) |
| `SHARED = "*"` audience string | typed `Audience` (§4) |
| cross-field `check=` with prose reasons | `Relation` — one set, two consumers (§5) |
| eating-out exclusion by hand ×3; "covered" re-derived | `OneOf` (§5) |
| `relates=` unenforced in the generic UI | comparison `Relation`, UI-bound (§5) |
| `faceted` + `filterable` dual declaration + checker | `Vocab` field type (§6) |
| array ops from column-type sniffing | declared `filter=Membership` (§6) |
| `ROTATING` sentinel ×4 files | declared `placeholder=` member (§6) |
| depth clobbering hrefs; stringified-list filters | spec'd grammar; hrefs authoritative; round-trip conformance (§7) |
| `_legacy_theme` before-validator | declared `shape`/`upcast` (§8) |
| `_fn_needs_input` / `_wants_ctx` / empirical `vars` | declared arity and vars (§8) |
| per-process rate limiter | rides the bus (§8) |
| `fence` vs `requires_if_match` | one spelling (§8) |
| header-or-query token sniffing | per-transport credential extractors (§8) |

And the enterprise gaps — absences rather than scars, held to the same
law:

| 2.0 gap | 3.0 fate |
|---|---|
| dev principal dropdown is the only resolver | OIDC relying party ships; the dropdown is one resolver, kept for tests (§9) |
| grants hold only token principals, select only kinds | one `grant` kind; member/agent/role holders; instance selectors (§9) |
| `AGENT_GRANT_ACTIONS` hardcoded sets, imported by the router | ordinary declared actions on `grant` (§9) |
| no member, no owner, no invite | `member` engine kind; `owner: Ref["member"]`; invite as transition (§9) |
| `reads=("services.x",)` naming convention | declared `Service` (§10) |
| external truth unrepresentable (`event_id` into the void) | `Mirror` with sync states; system-actor observed transitions (§10) |
| transition log consumable only by Waymark clients | `subscription` resources; signed webhook delivery (§10) |

---

## Wire format delta (v2 → v3)

| Surface | v2 | v3 |
|---|---|---|
| `waymark` | `"2"` | `"3"` |
| Events | one stream + separate presence stream | one taxonomy; events carry `class: transition\|observation` |
| Plumbing GETs | `peek=1` query flag | distinct lookup hrefs (flag retired) |
| Field groups | — | `x-display.one_of` from `OneOf` |
| Relations | `x-display.relation` (comparisons only) | + relation-derived per-binding enums |
| Collection queries | ad-hoc; comma-lists de facto | spec'd grammar; unknown params are Problems |
| Engine kinds | `agent_grant`, `approval_request` | + `member`, `grant` (agent links fold in), `subscription` |
| Mirrors | — | sync state in `state`; `meta.synced_at` |
| Everything else | | unchanged — envelope, parts, actions, `effort`, drafts, relay/2, problems, discovery |

A v2 generic client pointed at a v3 server loses only the refinements: it
still reads every envelope, misses `one_of` grouping, and sees observation
events it can filter by the new `class` field. As with 1→2, the novelty is
spent on the definition and delivery side, where 2.0 actually hurt.

## Migration sketch (v2 apps)

Mechanical and mostly deletion — the scars are things apps wrote *around*:

- Grant `FieldMap`/`ActionMap`/`ArgMap` dicts become one `Visibility`
  declaration; behavior is identical for grants that never relied on the
  three different "unlisted" defaults (the codemod flags those).
- The `field_display` picker duplicate deletes; its params move onto the
  `Ref`. Hand-maintained label copies delete where `label=` covers them.
- Hand-written mutual-exclusion blocks collapse into `OneOf`; the codemod
  flags every handler that writes to more than one arm.
- `check=` guards go through the same triage as v1's: every one
  expressible as a `Relation` is flagged — which is exactly the list of
  guards whose refusals were prose because 2.0 couldn't render their
  truth.
- `filterable`+`faceted` pairs fold into `Vocab` fields; the emitted
  migration is a no-op on storage (same generated column, same index).
- Before-validators doing shape folding become declared `upcasts`.
- `agent_grant` rows migrate into `grant` with a token holder; the
  negotiation flow is unchanged on the wire.
- Apps adopting members backfill `owner` from each resource's first
  transition actor — the log already knows who created everything.

The engine tables version again (`waymark3_*`); resource tables migrate in
place via the ordinary snapshot-diff, since §6 changes no column shapes.

## Explicit 3.0 punts

Punted, with the rule kept — punt things that don't compound:

- **i18n** — still deferred; §5's `Relation.explain` and §6's declared
  placeholders keep every human string at the one emission point, so the
  walk stays a walk.
- **CRDT/OT merge** — unchanged; the seam (`protocol` tokens + `revs` +
  drain rule) is still the contract.
- **Browser-platform workarounds** — the Chromium focusout hang, clipboard
  secure-context and `crypto.randomUUID` fallbacks are the platform's
  scars, not Waymark's; they stay quarantined in the generic UI and are
  explicitly out of scope for the model.
- **Regret telemetry / comprehension judging** — still measurements, still
  post-1.0; §3's event classes give them a cleaner substrate (observations
  are the missing half of the human/agent split).
- **Cryptographic attenuation (Macaroons/Biscuit)** — the grant-resource
  model is the contract; offline-verifiable tokens are an implementation
  someone can ship without changing it.
- **A federation protocol beyond links + discovery** — cross-server
  grants and mirrors cover the known cases; anything richer waits for a
  second real Waymark server to exist.
- Not punted, on principle: parts visibility (§1), approval-create (§2),
  and the bus-backed rate limiter (§8) — each is a 2.0 caveat that 2.0's
  own history proves compounds.

---

## Appendix: before/after user stories

One pair per numbered section, grounded in the mealplan app and cast with
its actual users: **Colton** (app author/operator), **Dana** (family
member in the generic UI), **the agent** (Claude on a `wmk_` token), and
**a follower** (someone supervising the agent).

### §1 Visibility is projection, not redaction

**Before (v2):** Colton mints the agent a grant scoped to grocery lists
and meal assignment. The agent GETs the plan and gets an envelope whose
`parts` are simply gone — `apply_scope` pops them wholesale ("re-admit
later if needed") — so even though the grant *allows* `assign_meal`, the
agent can't see the per-day bindings it needs to use it. Meanwhile
Colton, writing the grant, has to remember that an unlisted action means
*denied*, an unlisted field means *hidden*, but an unlisted argument
means *editable* — three defaults for three maps, and getting one wrong
is silent.

**After (v3):** The same grant compiles to one `Visibility`. The
projector builds the agent's envelope with it in hand: the days the
agent may act on render with their bound actions; the fields it can't
see were never rendered, so there's nothing to strip and nothing to
leak. One `unlisted=` rule, stated on the declaration. The agent's view
of the plan is just… a smaller plan, parts and all.

### §2 The router is assembled, not written

**Before (v2):** Colton wants the agent to be able to *propose* new
grocery items with human approval. The server answers with prose:
*"Approval-mode create is not supported yet; request open access to
create, or ask a person to create it."* Later he revokes a grant
mid-session — but the agent had a collab socket open on the notes draft,
and it keeps receiving the room until the draft closes, because the join
gate only ran at join.

**After (v3):** Approval capture is a stage on the write pipeline, and
create is a write — so "propose an item, Dana approves it" works the day
approval-mode ships, with no create-specific code. And revocation is an
affordance-changing transition on the bus, which re-runs the
authenticate stage on every route that principal holds open: the socket
drops the moment the grant does.

### §3 Two event classes, one taxonomy

**Before (v2):** Dana follows the agent to watch it plan the week. Her
screen whiplashes: the agent's client resolves eight meal-name labels
and a picker's options, and every one of those GETs announces presence,
so the follow view "visits" places the agent never looked. Three patches
later (`viewed`, then `engaged`, then `peek=1`) it's mostly quiet — but
only because every plumbing fetch now remembers to carry a flag.

**After (v3):** Label and picker fetches ride lookup-class routes, which
never emit observations — no flag to remember, nothing for a client to
forget. Dana's follow view shows exactly the agent's narrative: *opened
the active plan → opened assign-meal on Tuesday → dry-ran an input →
committed.* The `engaged` beats come from the draft and dry-run stages
themselves, so they're right by construction.

### §4 Binding: parts stop being a parallel universe

**Before (v2):** Colton declares the meal picker on the `Ref` with
`state="on_list"`. Then he wants Tuesday's picker to prefer taco-night
meals — but `{item.theme}` templates only work through a *second*
declaration, `field_display`, which must repeat `state="on_list"`.
Months later he tightens the first one and forgets the copy; the per-day
picker silently offers off-list meals. Separately, `meal_name` labels
are hand-copied in `_assign` — a new action that sets a meal but forgets
the copy ships a day showing the *old* meal's name.

**After (v3):** One declaration:
`pick=Query(state="on_list", themes="{item.theme}")`, on the `Ref`,
resolved against the binding at render. There is no second place to
disagree with the first. And `label="meal_name"` means the engine
maintains the denormalized label wherever the `Ref` lives — including
inside the days array — so a stale label is no longer a bug an action
can have.

### §5 Guards grow relations and one-of groups

**Before (v2):** Dana opens Tuesday, and the meal picker offers *every*
on-list meal — the theme rule lives in a `check=` guard the renderer
can't see into. She picks lasagna, submits, and gets a hand-written
refusal: *"Lasagna doesn't serve tacos night."* Trial and error, on a
form that knew the answer. And when she marks Friday as eating-out, the
handler must remember to clear `meal_id` *and* `meal_name` — the one
time a handler forgot, a day showed "Eating out — Lasagna."

**After (v3):** The `Relation(("meal", "date"))` acceptance set folds
into each bound form: Tuesday's picker *only offers meals that serve
taco night*, and the demand class honestly drops from recall to
selection — Dana can't submit a refusable combination.
`OneOf("meal", "eating_out", clears=True)` renders as an actual
either/or in the form, and choosing one arm clears the other atomically,
in the engine, for every action ever written.

### §6 Vocabularies are a type

**Before (v2):** Colton wants meals to serve multiple theme nights.
Getting there took: an engine feature (JSONB array columns + GIN +
membership operators, four layers in one commit), a migration with
backfill, a `faceted = ("themes",)` declaration *alongside*
`filterable(themes=…)` plus a checker to keep them agreeing — and for a
while, filter hrefs serialized as `['tacos', 'soup']`, a stringified
Python list no client could re-read. Meanwhile Sunday renders its
placeholder as if "rotating" were a cuisine.

**After (v3):** `themes: Vocab[str]` with `open=True,
filter=Membership, facet=Observed(counts=True), placeholder="rotating"`
— one field declaration. Column, index, operators, comma-list wire
format, facet counts, and picker behavior all fall out of it. Sunday
renders as *"theme not chosen yet"* and is excluded from "covered" by
declaration, not by four hand-threaded `ROTATING` branches.

### §7 The query grammar is spec

**Before (v2):** This one happened live. The agent fetched the
advertised href `/api/plans?state=active` — but the client passed
`depth` as request params, which *replaced* the query string. The filter
vanished, "all plans" came back newest-first, and the agent linked a
prep task to a draft plan nobody was cooking from. A human following the
agent caught it; nothing else would have.

**After (v3):** Hrefs are authoritative: clients merge parameters into
an advertised href through one property-tested `merge_params` —
rebuilding a query string is not a thing a conforming client does. And
an unknown or malformed parameter is a Problem, not a silent no-op, so
the failure mode is a loud 4xx on the agent's transcript instead of a
quietly wrong plan.

### §8 Ship the punts that compounded; kill the residual inference

**Before (v2):** The `theme`→`themes` change left a
`model_validator(mode="before")` in `meal.py` silently folding old rows
forever — no version, no test replaying it, invisible to conformance.
Colton scales the deployment to two Nomad workers and every rate limit
silently doubles: the counter is per-process, and the bus that was built
to prevent exactly this sits unused by it. And a new guard author
watches `_wants_ctx` sniff their lambda's signature to decide what to
pass it — v1's AST scanning wearing an `inspect` coat.

**After (v3):** The fold is a declared upcast — `shape=2,
upcasts={1: fold_theme}` — and conformance replays it against stored
shape-1 fixtures every run, so it can't rot. The limiter coordinates
over the bus, so two workers enforce one limit. And a guard's needs are
read from its declaration (`reads=` already says whether it gets a ctx),
so what the engine passes a callable is decided by what the author
wrote, not by what a signature-sniffer guessed.

### §9 Identity is a resource; authority is a grant

**Before (v2):** Login is a dropdown: anyone at the kitchen tablet can
*be* Colton. Every family member sees every resource, and there is no
way to invite grandma without her becoming everyone. Granting the agent
"edit meal plans" means every meal plan there will ever be — and the
grant keeps working even if the person who minted it loses that access
themselves.

**After (v3):** The tablet redirects to Keycloak; Dana is Dana. Colton
invites his mom from the members view — she arrives `invited`, activates
on first login, and sees nothing but what's shared with her. She
requests access to next week's plan; Colton approves from the request
feed, and the grant names `Resource("meal_plan", 88)`, not the kind.
Dana delegates her agent a slice of her own rights — and when her access
to a plan lapses, the agent's lapses in the same render.

### §10 The world outside the envelope

**Before (v2):** The grocery list lives twice — once as a Waymark
resource, once as the household todo list — and the two drift until
someone reconciles them at the store. A prep task carries an `event_id`
into a calendar the framework cannot see: if the event moves, nothing
here knows. And a budget app that wants to know when a plan is finalized
has to poll an envelope format it doesn't speak.

**After (v3):** The grocery list is a `Mirror` of the household todo
list: checking an item off at the store arrives as a `system`-actor
transition in the family's feed, and a sync conflict is a rendered state
with a `reconcile` button, not a silent overwrite. The calendar is a
declared `Service`, so when it's unreachable, `schedule` renders in
`unavailable` with `retry_at` — the button un-advertises instead of
failing at invoke. The budget app holds a `subscription` and receives
signed transition JSON; it never learns the envelope.
