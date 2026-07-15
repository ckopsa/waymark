# Waymark 10.0 — the language migration

A rewrite of waymark9 in Clojure, as a clean break (wire format
`"10"`), at `waymark10/` + `mealplan10/`. Companion documents:
`waymark8-design.md` (the law becomes data), `waymark9-design.md`
(the law binds the row's judgment), and the approved plan
(full-parity endpoint, phased; milestone 1 = phases 0–3).

**Epistemic status.** Written to a directive — "let's rewrite waymark9
in Clojure" — but the trigger is the lineage's own record. Every
version since 7.0 has been making the law representable, diffable,
storable, and evaluable per revision, and every step fought the host
language: the `E.*` builder facade exists because Python code has no
readable form; `to_wire`/`from_wire` exist because the builder's
objects aren't data; `callable_hash` hashes source *text* because
that is all `inspect` can see; the expr/fn declaration cliff exists
because Python has two ontologies — objects you can read and code you
cannot. In Clojure the law is a form: the tree the reviewer diffs,
the fingerprint stores, the wire carries, and the interpreter
evaluates are one value. 10.0 is not a feature version; it is the
medium catching up with the law.

What does **not** change: the totality boundary. Arbitrary Clojure is
refused exactly as arbitrary Python was — homoiconicity makes the
gate cheap (a set-membership walk), not open. The earned-vocabulary
rule loses its accidental enforcement (in Python, every node cost
builder boilerplate; here a node costs one line) and becomes review
law: **a node is added only when a ported declaration or a findings
doc demands it, and each addition is recorded here.**

## Why 10.0

Three facts, all recorded:

*The builder is the tax, not the law.* waymark8 §1's vocabulary was
right; its spelling (`E.f("start_date") + E.days(...)`, operator
overloads, `E.lit("0.02")` because floats can't be trusted) is
scaffolding around a language that wants to be written directly. In
10.0 the reconciliation rule is `(<= (abs (var :difference)) 0.02M)`
— and that s-expression *is* the storage form, the diff surface, and
the wire tree, with exact decimals as literals.

*The overlays are interpreters wearing mechanism's clothes.* waymark9
§1's judgment overlay (`judgment_served`, `judgment_laws`,
`guards_for`) is bookkeeping for one idea: evaluate revision N's
stored trees instead of the resident objects. When every guard and
derivation is a form and the interpreter is ~200 lines, "serve the
row's law" is a lookup plus `evaluate` — the mechanism shrinks toward
the principle.

*The fingerprint can finally read almost everything.* With resources
as maps and (phase 1) malli schemas as data, `callable_hash` shrinks
to one rule for the imperative residue: handlers and code guards
declared through `defhandler`/`defguard` hash by their **canonical
printed form** (comments and whitespace vanish in the reader), never
by file text. A bare fn with no form is a check error in strict mode:
identity must be stateable.

# 1. The form grammar (phase 0, landed)

A guard verdict, a derivation, or vars-garnish is an EDN form in a
closed vocabulary (`waymark10.expr/ops`):

| Category | Forms |
| --- | --- |
| References | `(data :f)` · `(input :f)` · `(now)` · `(var :f)` (derived scope) · `(get <e> :f)` → nil on missing |
| Literals | strings, booleans, nil, longs, exact decimals `0.02M`, `(date "2026-07-09")` |
| Comparison | `= not= < <= > >=` — binary; nil satisfies no ordering; long↔decimal promotes exactly |
| Boolean | `and or` (variadic) · `not` |
| Arithmetic | `+ - *` · `min max abs` — nil propagates; **no `/`, not earned** |
| Temporal | `(days n)` · date ± days · `(date-of ts)` (UTC) |
| Presence | `(is-set e)` |
| Quantifiers | `(every [d <coll>] <pred>)` · `(some [d <coll>] <pred>)` — named binders in authored form, de Bruijn `(it n)` in canonical form; empty/missing collections: `every` → true, `some` → false |

The scope split is waymark9's, verbatim: derived scope reads only
declared `:over` names via `(var …)` (a clock derivation declares its
clock input; `(now)` is refused); guard scope reads
`(data …)`/`(input …)`/`(now)` and refuses bare `(var …)`. Judges and
reads are derived from the tree (`expr/info`), never sniffed.

The boundary (`read-form`, `wire->form`) refuses: unknown operators,
tagged literals, floats (write `0.5M` — a float is a rounding, not a
law), unbound symbols, binder references outside their quantifiers,
oversized input. Total evaluation: no calls, no recursion, no reads;
calendar overflow and type mismatch flow as nil/false, never as a
throw (property-tested over generated forms × generated scopes).

# 2. Normalization — only meaning revises

`normalize` is idempotent and applied at declaration and before any
fingerprint/wire emission. Two spellings that mean the same thing are
the same tree; a reformat mints no revision.

1. Binder names erase to de Bruijn indices — alpha-equivalent
   quantifiers are structurally identical.
2. `>`/`>=` rewrite to `<`/`<=` with swapped operands.
3. `and`/`or` flatten nested same-op and drop duplicate operands
   (authored order otherwise preserved — conjunct order is the
   author's reading order, not meaning).
4. `not` canonicalizes: `(not (= …))` → `(not= …)` and back to `=`;
   double negation eliminates.
5. Commutative operands (`= not= min max + *`) sort by printed form.
6. Decimals strip trailing zeros; exactness is meaning, so `1.5M`
   stays decimal and never becomes a float.

**The value-domain scar** (found by the meaning-preservation
property, recorded here as 10.0's first deviation from the plan): the
plan said single-operand `and`/`or` "collapses to the operand" and
`(not (not x))` → `x`. Both are wrong when `x` is not
boolean-valued — `(and (data :a) (data :a))` evaluates to a boolean,
bare `(data :a)` to the raw field. Collapse therefore applies only
around boolean-valued operators (`= not= < <= > >= and or not is-set
every some`); a non-boolean operand keeps its coercing wrapper, and
the canonical grammar admits 1-ary `and`/`or` (e.g.
`(and (data :a))`) as the honest spelling of "coerce and insist".

A second recorded divergence: truthiness. Python's `bool()` made `0`
and `""` falsey inside `and`/`or`/quantifier predicates; Clojure
truthiness (only nil/false are falsey) is the 10.0 rule. Predicates
that mean emptiness should say it (`(= (get d :qty) 0)`), not lean on
a host language's coercion table.

# 3. The wire encoding

Forms cross and persist as a lossless JSON tree — never an opaque
string — so fingerprints diff path-by-path and every stored revision
reads back into an evaluable form:

```
(every [d (var :days)] (or (is-set (get d :meal_id))
                           (= (get d :eating_out) true)))
⇢
["every", ["var","days"],
  ["or", ["is-set", ["get",["it",0],"meal_id"]],
         ["=", ["get",["it",0],"eating_out"], true]]]
```

Decimals cross as `{"dec":"0.02"}`, date literals as
`{"date":"2026-07-09"}`. `wire->form ∘ form->wire = identity` on
canonical forms (property-tested). Canonical bytes (digests,
fingerprint hashes) admit only nil/boolean/string/long/keyword/
map/vector with sorted keys — floats and raw BigDecimals are refused,
which forces every decimal through the wire encoding and keeps hashes
byte-stable across processes.

# 4. The fingerprint reads forms

`fingerprint-of` projects the declaration map; expression laws are
stored as trees under `derived.<fact>.expr` and
`machine.actions.<name>.guards.<i>.expr`; only handlers and code
guards carry hashes. Ported one-for-one from waymark9
`core/fingerprint.py`, semantics intact:

- `classify-path` → `:shape`/`:judgment`/`:truth`/`:advertisement`,
  innermost owning surface wins, derived `explain`/`vars` are
  advertisement, unmatched defaults to `:truth`.
- `classify-diff` → `:data-law` iff every changed path is a
  derivation's tolerance/expr/edge-where or a recoverable leaf of a
  top-level expression guard; else `:code-or-shape`. A derived
  `explain`-only change stays `:code-or-shape` (render reads garnish
  from resident objects; a hold would serve new prose under an old
  law id) — ported, not revisited.
- `stale-facts` → facts whose semantic surface moved, garnish
  excluded; judgment diffs mark nothing stale.

One implementation scar worth recording: the first diff used a map as
a membership predicate, so paths whose leaf value was `nil` or
`false` (a guard's `check`, an off safety flag) read as added+removed
and poisoned the gate toward `code_or_shape`. Presence is
`contains?`, never truthiness — the regression test pins it.

# 5. The declaration layer (phase 1, landed)

A resource is one map; `defresource` normalizes it, runs the check
battery, and refuses at load what waymark9 refused at import. Guards
are maps evaluated by one function — the same `[verdict denier]`
resolution feeds the probe (render) and the invoke (enforcement),
which is the 2.0 unification carried into the new medium. Nothing is
sniffed: an `accepts` fn's call shape comes from `:reads`, the probe
short-circuit from `:needs-input`, and `:needs-input` defaults to
"grades input" — where grading is a `:check` **or a `:when` tree
whose leaves read `(input …)`** (the expression guard is code's
peer, so it inherits code's probe discipline).

Recorded adaptations from the Python original:

- **`bind_data` becomes data.** waymark9's `FactRequired.bind_data`
  mutated the guard at check time. Guards are immutable maps here, so
  `normalize-resource` *enriches* every require-leaf with its fact's
  derived spec (`:require/spec`) — the functional spelling of the
  same late binding.
- **Declaration order is not law.** Clojure map literals over eight
  entries lose authoring order, so action iteration is name-sorted
  everywhere (fingerprint, machine queries). Display order was always
  declared (`:display :order`); guard evaluation order lives in each
  action's guard vector, which survives.
- **Handler identity is a warning, then a gate.** A handler without
  `defhandler`'s form metadata gets a usability warning in phase 1;
  the strict mode that refuses it arrives with the registry, when
  grandfathered laws make unstateable identity an actual lie.
- **`async` evaporates.** Guard checks and accepts fns are plain
  functions; concurrency is the server's problem (threads), not the
  declaration's.

# 6. Phase-0 status and acceptance

Landed: `waymark10.expr` (vocabulary, validation, normalization,
total interpreter, scope validators, `read-form`), `waymark10.wire`
(form⇄JSON tree, canonical bytes, digests), `waymark10.fingerprint`
(projection, positional diff, classify, stale-facts). `make test10` /
`cd waymark10 && clojure -M:test` — 31 tests, 103 assertions, green;
properties: generated forms are well-formed; normalize is idempotent
and meaning-preserving; evaluation is total; wire round-trip is
identity; respelling a law is not a revision; a diff confined to a
`derived.*.expr` leaf classifies `data_law` with the fact stale.

## Explicit 10.0 punts (inherited and new)

- **`count`/`sum` quantifiers** — waymark9 has them; no ported
  declaration demands them yet. Added with the port that does.
  (Earned since: batch C, §19 — the blast-radius meter and the
  meal-prep sum rollup demanded them.)
- **Division** — never earned in any version.
- **String operations, `where=` unification** — inherited verbatim
  from 8.0's punt list.
- **Vocabulary growth without friction** — the standing risk of the
  medium; the rule above (recorded demand or no node) is the
  mitigation, enforced in review.

## Vocabulary additions log

| Node | Demanded by | Date |
| --- | --- | --- |
| (v1 set: see §1) | the waymark8 vocabulary, verbatim | 2026-07-09 |

# 7. Phase 8 — the mealplan10 dogfood, and the engine it forced

mealplan10/ is the full family meal planner (mealplan9's six kinds:
meal, rotation, plan, grocery_list, prep_task, event) ported onto the
Clojure engine at full fidelity — declaration-first, prose carried.
`make test-mealplan10` runs its conformance suite (all six kinds
enrolled in the waymark10.test library, over the ring handler) and the
Priya family-week story; `make dev10` serves it on :8010 against
mealplan10_dev (FakeEvents unless `MEALPLAN_GCAL_ICS_URL` is set).

Engine features the port forced into existence, each with its spelling
and its tests in waymark10's own suite (`waymark10.phase8-test`):

- **Cross-resource guard reads (C1).** `make-ctx` gains `:read`
  `(fn [kind id] → decoded row | nil)` and `:find` `(fn [kind where
  opts] → decoded rows)`, same transaction as the write. Guards keep
  declaring `:reads [:meal]`; the hook is what landed. Render's probe
  ctx stays storage-free (render is pure), so a reading acceptance
  set/check must decline (`nil`/allow) without the hook — the
  conformance probe (`factories/probe-ctx`) carries own-transaction
  hooks, so the walker still holds advertisement = enforcement.
- **Ref labels (C2).** A `:waymark/ref` entry declaring `{:kind :meal
  :label :meal_name}` gets its label engine-written at every write
  that changes the ref (data root and vector-of-map items; create
  treats every set ref as changed). The label is the target kind's
  `:label-template`, default `"{data.name}"` when the target declares
  `:name`. Recorded scope: a target rename does not fan back out
  (waymark9's maintainer did — named punt).
- **One-of clears (`waymark10.groups`).** `{:one-of {name {:in
  [:days] :arms {…} :clears true}}}` enforces post-handler: filling
  one arm clears the others (labels included — the label pass runs
  first); two newly-filled arms refuse as a definition bug. Recorded
  deviation: cleared fields become nil, not a model default (malli
  declares none).
- **The owns cascade (C3).** An owns edge's `:on {parent-action
  child-action}` fans out in `after-write!`, before the maintainer
  pass: each eligible child (selected by the child action's `:from`
  states, so redelivery is a natural no-op) moves through an ordinary
  `invoke!` — system actor `waymark-cascade`, the parent transition's
  correlation id. The rollup half is the phase-6 count fact: the
  `{:rollups …}` edge spelling is SUBSUMED by `:derived {:open_tasks
  {:count {:owns :prep_task :where …}}}` — one fact, one writer — and
  the gate is a plain expr guard over the stored count.
- **The Mirror (C4, `waymark10.server.mirror`).** `mirror/declaration`
  weaves the sync machine (fresh/stale/unreachable) and bookkeeping
  fields (external_id/external_etag/synced_at) into an app map, with
  `{:mirror {:adapter … :ttl-seconds … :discover-every …}}`. The
  adapter protocol is discover/pull/pull-many; sync transitions are
  system-actor-only and hidden. Seams: pull-through on GET (router;
  `:suppress-mirror-refresh` is the walker-scoped conformance escape,
  waymark9's `_suppress_mirror_refresh`), and discovery
  (mint-per-unknown-id + eager pull-many; a daemon on the engine
  runtime). Scoped to what the event kind needs — no push, no
  conflicted/reconcile, no per-field authority, no discovery cursor.
- **Shape upcasts (C5).** `inv/decode-row` (now the one load boundary
  for invoke, router, collections, and the maintainer) folds a stored
  row through `(:upcasts rdef)` when its shape lags, and the declared
  shape stamps at the next write. Upcasts must be idempotent: a
  maintenance write persists upcast data without the stamp.
- **`:waymark/instant`** joins the schema vocabulary (prep_task's
  `due_at` demanded a point the clock can compare): Instant in the
  law, RFC 3339 on the wire, timestamptz in the promoted column, flip
  candidates for the clock sweep.
- **Two wire-honesty amendments the conformance suite forced:**
  in-state concealment now precedes the fence, input validation, and
  natural replay (a hidden door must 404, never 422 or replay-200);
  and an available action whose REQUIRED field's folded acceptance
  set is empty narrates as unavailable ("No date currently qualifies
  for 'Add side dish'.") instead of advertising an empty enum —
  both are waymark9 render/external semantics, ported.
- **`factories/state-factory!`** — waymark9's `@state_factory`,
  ported: mealplan10 registers plan and grocery_list factories
  (finalize's require gate needs seven `mark_eating_out` self-loops a
  shortest-path walk cannot spell).

mealplan10's own recorded punts, as of phase 8: previous_plan
declared but predecessor-unresolved (since closed — batch E's
predecessor resolver, §13); links declared and assembly-checked but
unrendered (since closed — batch A, §14); part-scope "parts" envelope
rendering unbuilt (since closed — batch A, §14, which binds the
placed actions' `:place` keys); WeekBoard/spans/profiles have no v10
spelling (WeekBoard since landed as a phase-9b surface, §9;
spans/profiles still have none); the real iCal adapter parses VEVENTs
without RRULE expansion (since closed at a recorded profile — batch
E, §13); v10 summary templates have no |join/|len filters; no field
defaults (rotation/plan defaults land in :on-create).

The hand walk (`make dev10`, then): create + activate a rotation
(`POST /api/rotations {}`, `POST /api/rotations/{id}/-/activate`);
suggest and accept a meal (`POST /api/meals`, `…/-/accept`); create
the plan (`POST /api/plans {"start_date":"2026-07-14","weeks":1}` —
days pre-themed, Sunday from the rotation); assign and mark days
(`…/-/assign_meal`, `…/-/mark_eating_out`); finalize; `begin` answers
409 with `becomes_available.at` until Tuesday; `POST /api/prep_tasks`
then watch `data.open_tasks` gate `plan complete`; the grocery list
add/finalize/check_item/complete flow; `POST /api/events
{"external_id":"…"}` mints a mirror whose first GET pulls it through,
and `mark_stale` as a human answers 404 (concealed).

## Vocabulary additions log (phase 8)

| Node | Demanded by | Date |
| --- | --- | --- |
| `:waymark/instant` (schema type, not an expr node) | prep_task.due_at | 2026-07-10 |

# 8. Phase 9a — identity and access

Members, roles, OIDC, grants and attachments land as engine-served
resource kinds (every engine now enrolls definition + member + role +
grant + attachment; well-known lists them), plus one new router
boundary. `wrap-identity` (inside the problem boundary) resolves the
request's identity ONCE, judgment-style: the principal — the OIDC
bearer resolver when the engine configures `{:oidc {:issuer :audience
:jwks-uri|:jwks :roles-claim :type-claim}}` (buddy-sign RS256 against
a cached JWKS with kid-rotation refetch; expired/bad-signature/wrong-
audience → one 401 problem carrying WWW-Authenticate; absent config =
dev headers unchanged, and dev headers stay the no-Bearer fallback) —
then the members gate (`members/gate!`: auto-provision on first sight
as a logged system-actor create, waymark9's invite→bind flow scoped
down to it; a suspended member's every request is one 403 problem
BEFORE any handler — authentication-adjacent gating, documented as
such, guards stay the only authorization concept; member-held roles
union onto the credential), then the grant visibility (X-Waymark-
Grant names a grant id whose audience must be the principal; the
resolved `{:kind? :row? :action? :ids-of}` closures ride the request).
Enforcement is rendering at the source, waymark9's discipline at
phase-9a fidelity: render drops non-granted actions from actions AND
unavailable (absence, never narration), the router 404s non-granted
kinds/rows/actions (concealment), collections push granted ids down
as a real cond so totals stay honest, and a dead grant (unknown,
unaccepted, revoked, expired, wrong audience) scopes to NOTHING.
Grants themselves are offered → accepted → revoked/expired with
`accept` audience-gated, `revoke` no-self-dealing, `expire` clock-
gated bookkeeping — expiry is enforced live either way. Attachments
are pending → stored → deleted metadata plus PUT/GET
`/api/attachments/{id}/bytes` against `:attachment-dir` (default
target/attachments) with `:attachment-max-bytes` (default 10 MiB,
413 problem); a successful PUT runs `mark_stored` as the bytes system
actor (hidden — a human's direct invoke 404s), a same-size re-PUT
natural-replays. The conformance library grows three additive
obligations: grant concealment (a scoped envelope never NAMES an
ungranted action), the suspended-refusal shape, and the attachment
byte round-trip. mealplan10 needed no wiring. Named punts, each
scoped deliberately: waymark9's grant negotiation machine
(request_access, approver-edited maps, attenuation ceilings), the
ApprovalRequest flow, field/argument modes (v10 grants grade kinds,
ids and actions only), the agent's own-grant negotiation surface,
grant-projected SSE (a scoped request 404s the event routes),
idempotency replays predate the projection (the phase-3 render-fn
punt, extended), invited-only membership, role uniqueness under race
(a create guard, no unique index), OIDC's browser dance/sessions/
logout, byte purge on delete (waymark9's BlobJanitor), duplication/
sha256/S3/presigned URLs, and blob-write/metadata atomicity
(waymark9's log-consumer choreography). (Batch B, §17, since closed
the negotiation machine at scope, the ApprovalRequest flow,
field/argument modes, the own-grant surface, and invited-only
membership; batch F, §16, closed byte purge, duplication, and
sha256. Still open, by choice: approver-edited maps, the attenuation
ceiling, grant-projected SSE, projection-blind idempotency replays,
role uniqueness under race, OIDC's browser dance/sessions/logout,
S3/presigned URLs, and blob-write/metadata atomicity.)

# 9. Phase 9b — the outbox is a product, the screen is a declaration

Webhooks, deferred jobs, surfaces, live collab and the OpenAPI
overlay land, and with them the parity ledger below: every waymark9
server module now has either a waymark10 home or a named punt.

**Webhooks** (`server/webhooks.clj`). The `:subscription` kind
(url, kind filter, optional secret; active/paused/failed) enrolls on
every engine beside member/role/grant/attachment. One deliverer
thread rides the events dispatcher as its wake signal and drains
each active subscription's cursor (`waymark10_cursors`, additive
DDL: consumer PK, position, updated_at) — at-least-once off the
log, per-event checkpoint, restart replays instead of dropping. The
body is the SSE frame's data verbatim (snake keys); the event id
rides `X-Waymark-Event-Id`; a declared secret signs the body:
`X-Waymark-Signature: hex hmac-sha256(secret, body)`. Failure
discipline deliberately differs from waymark9's skip-and-advance:
after the declared attempts (default 3, exponential backoff) the
SUBSCRIPTION transitions to failed (`mark_failed`, system actor,
logged, reason recorded) with the cursor parked at the refusing
event — resume replays from exactly there; nothing silently drops,
and one broken endpoint stops only its own stream. A new
subscription hears the world from its own creation transition.
Recorded: subscription bookkeeping is never delivered; waymark9's
revoked terminal state is unported (pause is the off switch).
(Since closed — batch F, §16: `revoked` lands owner-gated, and the
failure discipline becomes a per-subscription `:delivery_policy`,
"fail" default or waymark9's "skip".)

**Deferred jobs** (`server/jobs.clj`). The phase-7 `:defer-over`
punt closes: an over-threshold bulk call now answers **202** with
the `:job` envelope as body and its self in Location. The job is an
ordinary engine-served resource — `{action, kind, ids, input,
requested_by, progress {done, total, refusals}}`, born `:running`,
terminal completed/cancelled — minted by the system actor (wire
creation refuses), the requester recorded in data. The worker claims
through `waymark10_job_leases` (additive DDL: job_id PK, holder,
expires_at) with claim-or-steal-on-expiry — a died worker's job is
picked up by the next pass; the steal IS the resume, which retires
waymark9's orphan-cancel sweep. Items execute through
`inv/bulk-item!` — the SAME per-item algorithm a synchronous bulk
runs, own transaction, after-write! hooks included — under a
principal reconstructed from `requested_by` (roles not carried,
recorded). Progress persists batchwise as a maintenance write (no
version bump, no transition — the items already log on their own
rows); cancel is a confirm-gated wire action that takes effect
between batches. Recorded: waymark9's queued state and job
artifacts are unported (both since restored — batch F, §16, with an
orphan sweep beside the lease steal); deferred calls skip whole-call
idempotency (the job row is the record).

**Surfaces** (`server/surface.clj`). The composed decision screen:
`{:name :anchor :members :showcase :attention}` — members name
DECLARED edges of the anchor (`:related` joins or `:owns`; the
surface composes what the law relates and smuggles no new joins),
validated at engine assembly against the full registry. Served
read-only at `GET /api/surfaces/{name}/{anchor-id}`: the anchor's
FULL envelope, each member's rows as envelope-minus-data summaries
(related joins inverted exactly as the maintainer inverts them),
and the attention map evaluated (declared field = nominated value →
boolean per flag). Engine opt `:surfaces […]`; well-known lists
them. mealplan10 declares WeekBoard (`plan.clj`): the plan anchor
with the family calendar co-present, finalize showcased,
`has_conflicts` nominated — covered in the family-week story.
Recorded (what waymark9's surface.py has that this does not):
surfaces are not fingerprinted (no `surface:{name}` definition rows,
no revise transitions), not grantable (a scoped request 404s, the
SSE precedent), no member table hints or title template, attention
is schema-field equality rather than the query grammar, and the
anchor's envelope does not link back to its surfaces.

**Live collab** (`server/collab.clj`). waymark-relay over http-kit
websockets at `GET /api/{plural}/{id}/-/{action}/draft/collab`, for
`:edit` actions whose draft policy is `{:shared true :live true}`.
Frames: client `{"type": "set", field, value}` → the same partial
validation a draft PUT runs, persisted through the shared draft row
in the set's own transaction, then `{"type": "update", field,
value, rev, author}` broadcast to the others; `{"type": "sync"}`
answers the full draft. Per-field last-writer-wins, server-ordered
(a room lock serializes), rev = a per-draft atom that resets with
the room (room-lifetime, deliberately — LWW ordering matters only
among live participants). Rooms clean up on last disconnect; the
act consumes the draft in its own commit as before. Scoped honestly:
no OT/CRDT, no relay/2 staleness rejection (`base_rev`/reject), no
saved-acks, no presence frames, no affordance regate, no
cross-worker bus, no closed frame on consumption — each recorded.
(All of it since closed — batch D, §18, builds relay/2 with
server-authoritative OT for prose, acks, staleness rejection,
presence, regate, gone-on-consumption, and the cross-process relay;
only the UI's collab chrome stays punted.)

**OpenAPI** (`server/openapi.clj`). `GET /api/openapi.json` — a
derived 3.1 overlay: per kind the collection (query parameters from
the filter grammar), create, get, and every action's act path
(+bulk/batch/draft) with the REAL input schemas from the
declarations, descriptions from display + the machine + safety, and
the problem responses referenced once in `components.responses`.
Enough for /docs-style tooling; recorded: response body schemas are
stubs, SSE/attachments/surfaces/collab/well-known are undocumented,
no securitySchemes, scoped requests 404. (Batch F, §16, since landed
the shared response schemas, securitySchemes, and the surfaces
routes; SSE/attachments/collab/well-known stay undocumented and
scoped requests still 404.)

## The parity ledger — waymark9's server inventory → waymark10

Kept current through the post-parity batches (§§11–19); this table is
the standing truth, and each row names the section that moved it.

| waymark9 module | waymark10 home | Scope notes / named punts |
| --- | --- | --- |
| invoke.py | `server/invoke.clj` | full transition algorithm; bulk/batch/bulk-item; the predecessor seam (one threading line, §13) |
| definitions.py | `server/definitions.clj` | promote/pilot/withdraw; population grammar gate landed (§19 — pilot's where= validated through the collections parser) |
| router.py | `server/router.clj` | linear reitit router, problem + identity boundaries; kind-schema projection, check-args!, approval effects, invite token (§17's flagged seams) |
| render.py | `server/render.clj` | links, parts, effort/demand classes, depth=summary, rows=none landed (§14); depth=expanded embed profiles and declared collection columns still punted |
| storage/ | `server/store.clj` + `store/postgres.clj` + `store/memory.clj` | Postgres backend + the in-memory twin (§16); dispatcher/LISTEN liveness (SSE, webhooks, observations) stays Postgres-only |
| grants.py | `server/grants.clj` | kinds/ids/actions + field/argument modes, the negotiation machine (approval_request), the own-grant surface (§17); hashed mode, approver-edited scopes, attenuation ceiling punted |
| derived.py (maintainer) | `server/maintainer.clj` + `derived.clj` | counts, sums, clocks, backfill; derived-law overlay (`specs-under`) and derivation-class events landed (§19) |
| external.py (Mirror) | `server/mirror.clj` | pull-through, discovery, push/write-back + conflicted + resolve_conflict (§13); external-keyed refs landed beyond waymark9 (`:external-key` on a ref entry — sync writes resolve external ids to mirror row ids, discovery backfills via resolve-refs!; paydesk's assignment demanded it, 2026-07-13); per-field authority, discovery cursors, change feeds, pushing minted rows punted |
| engine.py | `server/engine.clj` | boot, runtime lifecycle (dispatcher / coherence-elected deliverer+sweeper (§12) / discovery / jobs / orphan+purge sweepers (§16)) |
| collab.py (waymark-relay/2) | `server/collab.clj` + `server/drafts.clj` | full relay/2 (state, acks, stale, presence, regate) + server-authoritative OT for prose (§18 — waymark9 named character-merge a different protocol token and never built it); cross-process relay on waymark10_collab with origin nonces + heartbeat-evicted merged rosters; UI chrome punted |
| events.py | `server/events.clj` | transitions + the observation (derivation) class (§19); no Last-Event-ID replay for derivations |
| attachments.py | `server/attachments.clj` | bytes on disk + sha256, duplicate-replay, purge sweep (§16); S3/presigned and blob-write/metadata atomicity punted |
| oidc.py | `server/oidc.clj` | bearer RS256 verification; browser dance/sessions/logout punted (reaffirmed by name, §17) |
| drafts.py | `server/drafts.clj` | per-field revs/authors/op logs persist in the draft document (§18); pre-envelope rows read as rev-0, no migration |
| subscriptions.py | `server/webhooks.clj` | per-subscription `:delivery_policy` — "fail" (parked cursor) default, waymark9's "skip" opt-in; revoked terminal state landed (§16) |
| migrate.py | `server/store/migrate.clj` | plan/apply from ONE projection (§11); expression drift invisible (name+type compare); engine tables additive-only; state renames the sole destructive class; GIN entries reconciled by name (§16) |
| bus.py | `server/coherence.clj` | law refresh rides the outbox (guarded boot-revise!, never mints); deliverer + sweepers elected by advisory lock (WM10 keyspace) (§12); SSE per-process off the shared log; cross-process collab relay landed later (§18) |
| members.py | `server/members.clj` | auto-provision or `{:members :invited-only}`; invite→bind landed (§17); unbind, SECRET_FIELDS owner-gating, token uniqueness under race punted |
| judgment.py | `server/judgment.clj` | stored-tree overlay for actions AND (via `derived/specs-under`, §19) derivations; `fn=` facts stay resident |
| jobs.py | `server/jobs.clj` | queued state, artifacts (the report), orphan sweep landed (§16); lease steal still resumes; whole-call idempotency skipped (recorded) |
| problems.py | `server/problems.clj` | RFC 9457 projection |
| openapi.py | `server/openapi.clj` | overlay + shared response schemas, securitySchemes, surfaces routes (§16); no docs UI; per-kind response models stay structural |
| owns.py | `invoke.clj` cascade + `maintainer.clj` rollups | rollup_is subsumed by count facts |
| consumers.py | `server/consumers.clj` | named durable log consumers, park-on-throw, drain-consumer! (§16); the webhook deliverer's unification onto it deferred until a third consumer |
| pipeline.py | **punt** | no pipeline/choreography surface |
| roles.py | `server/roles.clj` | registry + uniqueness-under-race punt |
| surface.py | `server/surface.clj` | fingerprint/grant/links/table-hints punted (see above); routes documented in OpenAPI (§16) |
| idempotency.py | `invoke.clj` + `waymark10_idempotency` | byte-identical replay; replays predate grant projection (recorded, re-extended §17) |

Standing cross-cutting punts as phase 9b named them, each since
tracked: RRULE/recurrence (closed at a recorded profile, §13), spans
(still open), the predecessor resolver (closed, §13), rows=none/depth=
collection modes (closed, §14), GIN indexes for vocab arrays (closed,
§16), and the grant-projected SSE/surface/openapi routes (still
open). The full remaining list lives in the final section.

## The wire walk (curl + websocat)

Webhooks — subscribe, transition, verify:

    # subscribe (secret optional; omit it for unsigned deliveries)
    curl -s -X POST localhost:8010/api/subscriptions \
      -H 'x-waymark-principal: priya' \
      -d '{"url": "http://localhost:9999/hook",
           "kinds": ["plan"], "secret": "whsec-demo"}'

    # any plan transition now POSTs to the hook: body = the SSE data
    # shape; verify with
    #   hmac_sha256_hex("whsec-demo", body) == X-Waymark-Signature
    # and resume-after-outage is automatic (the cursor is parked)

    # a broken endpoint fails the subscription after 3 attempts:
    curl -s localhost:8010/api/subscriptions | jq '.data.items[].state'
    # … fix the endpoint, then
    curl -s -X POST localhost:8010/api/subscriptions/{id}/-/resume \
      -H 'x-waymark-principal: priya'

Deferred bulk — 202, watch, cancel:

    curl -si -X POST localhost:8010/api/meals/-/accept_many \
      -H 'x-waymark-principal: priya' \
      -d '{"ids": [ …51 ids… ]}' | grep -e HTTP -e Location
    # HTTP/1.1 202  /  Location: /api/jobs/{job-id}
    curl -s localhost:8010/api/jobs/{job-id} | jq .data.progress
    curl -s -X POST localhost:8010/api/jobs/{job-id}/-/cancel \
      -H 'x-waymark-principal: priya'   # stops between batches

Surfaces:

    curl -s localhost:8010/api/surfaces/week-board/{plan-id} \
      -H 'x-waymark-principal: priya' \
      | jq '{attention, showcase, calendar: [.members.calendar.items[].summary]}'

Live collab (an action with :draft {:shared true :live true}):

    websocat -H 'x-waymark-principal: priya' \
      ws://localhost:8010/api/pads/{id}/-/revise/draft/collab
    > {"type": "set", "field": "title", "value": "Family week"}
    # every OTHER participant sees:
    # {"type":"update","field":"title","value":"Family week",
    #  "rev":1,"author":{"id":"priya",…}}
    > {"type": "sync"}
    # {"type":"sync","values":{"title":"Family week"},"rev":1}
    # then the act consumes the draft:
    curl -s -X POST localhost:8010/api/pads/{id}/-/revise \
      -H 'x-waymark-principal: priya' -H 'if-match: W/"pad-{id}-v1"' \
      -d '{"title": "Family week"}'

OpenAPI:

    curl -s localhost:8010/api/openapi.json | jq '.paths | keys'

Migrate — the plan is the deploy gate (§11):

    make migrate10                         # print the plan; exit 1 while steps remain
    make migrate10 APPLY=1                 # execute the non-destructive steps
    make migrate10 APPLY=1 DESTRUCTIVE=1   # include the state-rename UPDATEs
    # a production boot REFUSES on drift, listing these same steps;
    # `make dev10` opts into self-reconciliation by passing
    # WAYMARK10_AUTO_MIGRATE=1 explicitly — never a default

# 10. Phase 10 — the clients close the loop

The engine has spoken wire "10" since phase 3; phase 10 gives the
wire its three consumers — the affordance-following client library,
the CLI over it, and the envelope-driven generic UI — and with them
the lineage's final claim is testable end to end: a client that
knows NOTHING about meal planning drives the whole family week,
because everything it needs was declared once and projected.

## The client contract, as enforced (`waymark10.client`)

The library is the reference implementation of spec Part IV: each
agent-client rule lives in exactly one named place, and its
docstrings teach the contract. The rules as they landed, with every
adaptation recorded:

1. **Act only on declared actions.** `act!` looks the action up in
   the document's `actions`; absent means a LOCAL refusal carrying
   the server's own `unavailable.reason` when it narrates one. The
   namespace contains no URL constructor for writes — the test pins
   that an unknown action produces zero HTTP requests. Reads are
   `self`, well-known hrefs, and `links` (`follow`); an undeclared
   rel refuses locally too.
2. **`safety.confirm=true` is a hard stop.** waymark9's
   `PendingConfirmation` object becomes the `:confirm!` callback —
   the seam where a human says yes. No callback, or a falsey return,
   refuses locally with the consequence text (the declaration's
   `:consequence`, riding the wire as `display.description`).
3. **Idempotency-Keys are persisted before the first attempt.** The
   session's `:key-store` (an atom the caller may persist — the CLI
   does) maps the LOGICAL attempt (href + input hash) to a generated
   key; an ambiguous transport failure retries once with the same
   key, and a deliberate identical re-call replays byte-identically
   instead of duplicating. Recorded nuance: same href + same input
   IS the same attempt, by design — a distinct attempt needs
   distinct input (waymark9's client chose the same).
4. **Fenced actions auto-send If-Match** from the document's
   `meta.etag`. A stale document's write is a 412 problem, honestly
   surfaced; re-reading heals the fence.
5. **`dry-run`** pre-validates schema AND guards server-side
   (`?dry_run=1`) before anyone is asked to confirm anything.
6. **Warning 409s surface as data**: `{:warnings … :acknowledge!}`.
   Calling `(acknowledge!)` retries with `Waymark-Acknowledge`
   naming exactly the warnings the caller saw — same idempotency
   key, because it is the same attempt.
7. **Plan over `effect.to`, verify every landing.** The session
   learns `state → action → to` edges from every document seen;
   `plan` BFSes the learned graph (an unroutable goal refuses with
   the widen-the-graph hint), `follow-plan!` executes and re-reads
   between steps, and every `act!` compares the landed `state`
   against the declared prediction — a mismatch rides the result as
   `:waymark10.client/diverged` (`diverged` reads it), surfaced,
   never improvised around.

The library-wide adaptation, recorded once: **refusals are data,
never exceptions** — `{:problem …}` (the RFC 9457 body, whole),
`{:refused …}` (local), `{:transport …}` (the wire itself), plus
the warnings shape above; predicates `problem?`/`refused?`/
`transport?`/`warnings?`/`doc?` grade results. waymark9's exception
hierarchy is a Python idiom; in Clojure the refusal IS the return
value, and nothing is ever swallowed into nil.

`tools` is the MCP projection: the document's CURRENT actions as
`[{:name "kind.action" :description … :input_schema …}]`, purely
mechanical — folded acceptance sets arrive as enums for free,
confirm gates annotate the description, terminal effects warn.
`watch!` tails the SSE firehose as parsed frames (real HTTP only —
the `:handler` ring transport, the test seam, has no stream).

**The engine gap the client found** (and phase 10 closed): `create!`
silently dropped a present `Idempotency-Key` — waymark9 stored and
replayed create keys (invoke.py's create path), v10's router never
passed the header down. Creates now honor a present key exactly as
invoke does (byte-identical replay through the render-fn seam,
409 on body-different reuse); the recorded deviation is that
waymark9's **428 requirement on keyless creates stays waived** —
v10 never demanded it, every enrolled app creates bare, and the
affordance-following client always sends one anyway. A replayed
create serves the stored bytes without the Location header (the
body's `self` carries the same href).

## The CLI (`waymark10.cli`, `clojure -M:cli`)

Thin over the library — one shell call per affordance, every rule
enforced by the client rather than remembered by the operator:

    clojure -M:cli <base-url> index
    clojure -M:cli <base-url> get <href>
    clojure -M:cli <base-url> act <href> <action> [--input '{json}'] [--yes]
    clojure -M:cli <base-url> watch [--kinds a,b]

Auth (`--as id [--roles a,b]` dev headers, `--bearer` OIDC,
`--grant` the scope selector) persists per base-url in the session
file (`~/.waymark10/session.edn`, `--session` overrides) beside the
idempotency key store — so a re-run of the same act with the same
input REPLAYS across processes. Confirm gates prompt on the
terminal; `--yes` is the recorded human approval (and acknowledges
warnings). Exit codes: **0** ok · **1** the server refused
(problem) · **2** refused locally (confirm/acknowledge declined,
unknown action, usage) · **3** transport. waymark9's separate
divergence code (4) folds into 0-with-a-loud-line: the write
LANDED, the surprise is narrated on stdout.

The dev10 transcript (2026-07-10, `make dev10` with a blocking
"Piano recital" seeded on the FakeEvents feed for 2026-07-16;
`--as priya` given once, persisted; every id from a prior answer):

    $ cli index                                          # exit 0
    waymark 10
      meal            /api/meals
      plan            /api/plans …
      surface week-board /api/surfaces/week-board/{anchor-id}

    $ cli act /api/rotations create --input '{}'         # exit 0
    rotation /api/rotations/032e… · state=inactive · v1
    actions:  activate  → active
    unavailable:  add_theme: Available in state(s) Active; …
    $ cli act /api/rotations/032e… activate              # exit 0

    # six meals created + accepted (create → suggested, accept →
    # on_list), then:
    $ cli act /api/plans create --input '{"weeks": 1}'   # exit 0
    plan /api/plans/00ef… · state=draft · v1
    data: …"days":[{"date":"2026-07-14","theme":"mexican"}, …
          "calendar_conflicts":1,"has_conflicts":true …
    unavailable:
      finalize: Every day needs a meal or an eating-out mark …
      add_side_dish: No date currently qualifies for 'Add side dish'.

    $ cli act /api/plans/00ef… assign_meal \
        --input '{"date":"2026-07-15","meal_id":"<tacos>"}'   # exit 1
    refused by the server: 409 Refused
    That meal doesn't serve 2026-07-15's theme night. Pick the
    Sunday theme first if the day still rotates, or assign
    off-theme with confirmation.

    # six assign_meal + one mark_eating_out (v2…v8), then:
    $ cli act /api/plans/00ef… finalize                  # exit 2
    the server warns:
      calendar-clear: 1 calendar conflict(s) overlap this week —
      move or cancel them on the calendar itself, or acknowledge …
    Acknowledge and retry? [y/N] not acknowledged; nothing done

    $ cli act /api/plans/00ef… finalize --yes            # exit 0
    plan /api/plans/00ef… · state=planned · v9

    $ cli act /api/plans/00ef… begin                     # exit 2
    refused locally: The plan starts 2026-07-14.
    # (unavailable.reason rode the local refusal — no request left)

    $ cli act /api/plans create --input '{"weeks": 1}'   # exit 0
    plan /api/plans/00ef… · state=draft · v1
    # the SAME plan, v1 bytes: the persisted key replayed the first
    # execution instead of minting a second week

    $ cli watch --kinds meal
    14:17:07  colton retire  meal on_list → retired  /api/meals/14ad…

## The generic UI, decided and executed

**The decision:** adapt waymark9's `ui.html` approach — the envelope
IS the screen — to wire "10", rewritten from scratch rather than
ported (waymark9's page consumes parts/relay/presence/approvals,
none of which v10 serves), at roughly a third the size. One
self-contained page (vanilla JS, zero external hosts — pinned by
test) served at `GET /api/-/ui` from the engine's own classpath
(`waymark10/resources/waymark10/ui.html`), zero app knowledge: the
one baked-in list is the ENGINE kinds (definition, member, role,
grant, attachment, subscription, job — wire-10 knowledge, grouped
in the nav, never hidden).

What it renders, each from the wire alone: nav and home from
well-known (declared surfaces listed); collection screens from the
query grammar (state/sort selects and facet chips from the
advertised query input schema — `x-facets` counts become lit
chips — items as state + summary rows, next/prev from links, bulk
actions as a checkbox column whose reports render verbatim);
resource screens as the envelope (data as a kv table, vector-of-map
fields as nested tables, `x-display` prose as preformatted blocks;
actions as buttons styled by `display.style`, ordered by
`display.order`; **unavailable as disabled-with-tooltip buttons
PLUS the narrated not-now list** with `becomes_available` and
remedies); action dialogs generated from the input schemas (folded
acceptance enums as selects — the assign_meal date field offers
exactly the plan's seven days — `waymark-ref` fields as pickers
populated from the target kind's collection labeled by summary,
booleans/dates/numbers/arrays as their honest widgets); the confirm
gate as a consequence box with an explicit "Confirm & …" button;
`dry_run=1` as a Check button; warning 409s as an in-dialog
acknowledge box that retries with the header; 412s as
re-read-and-retry; 422s as per-field errors; fenced actions
auto-If-Match; non-idempotent actions mint one key per dialog-open
(retries within the dialog reuse it); `:edit` drafts load on open
(GET `draft.href`: values + prefill), **save on blur** (PUT), offer
discard (DELETE), and are consumed by the act; surface screens
render the anchor, the attention flags, showcased actions and
member tables; and the SSE firehose drives a one-line ticker plus a
debounced refetch of whatever envelope or collection is open.

Scope boundaries, each recorded: no live-collab websocket join (the
draft's shared row still syncs on blur/reopen), no batch surface,
no attachment byte upload, no OpenAPI/docs screen, no vocab
combobox (arrays of strings are comma-separated text), arrays of
objects input as a JSON textarea, `date-time` inputs as plain RFC
3339 text, dry-run on demand rather than on blur, no grant-scoped
chrome (a scoped request's data is projected by the API either
way), no i18n, and no undo toast (v10 envelopes advertise the
inverse action; the button is there, the toast isn't). (Batch A,
§14, since closed two of these: facet-fed vocab comboboxes, and
dry-run moved to blur — scoped to single-resource actions after
fixing the live Check-button bug. §23 then finished the blur story:
the blur judge now speaks the PARTIAL rehearsal's verdict —
judged-when-answerable — and the create/bulk doors rehearse too.
The live-collab join remains open after batch D built relay/2: the
UI chrome is §18's named punt.)

**Verified against live dev10** (2026-07-10) two ways: the
automated floor (`waymark10.ui-test`: serves, self-contained,
consumes the wire) and a scripted headless-chromium drive over CDP
(node + `--headless=new`) that clicked the real page through the
family-week story — 33 checks, zero console errors: home/nav from
well-known; meals collection with grammar-built filter bar; state
filter round-tripping through the collection self href; meal
created through the generated form (required marks, prose
textarea); accept invoked; the confirm-gated retire showing its
declared consequence and landing only through "Confirm &";
unavailable narration on the planned plan (begin's clock sentence
as tooltip AND not-now line with `becomes_available.at`); the
week-board chip earned by anchor probe; reopen; assign_meal with
the folded 7-day enum and summary-labeled meal picker; the
wrong-theme guard refusing IN the form with its own sentence, then
the corrected assign landing; dry-run's green verdict; finalize's
warning box acknowledging and landing planned; the surface's
attention flag up with the Piano recital riding the calendar
member; update_recipe's draft saved on blur, prefilled on reopen,
consumed by the act; and a foreign retire refetching the open
envelope through SSE with the ticker narrating it.

# 11. Migrate — the largest punt comes home

The parity ledger's biggest named punt was migrate.py: no schema
diff, no planner, `ensure-kind!` additive-only — a new filterable
field on a deployed kind simply never got its column. This section
is the planner (`waymark10.server.store.migrate`), and the insight
that makes it smaller than its ancestor is the design's spine:

**In waymark10, ALL row data lives in the JSONB document.** Every
per-kind column beyond the engine's fixed set is a GENERATED column
derived from that document (the phase-7 promotion rule: filterable ∪
sortable). So dropping or recreating a promoted column is ALWAYS
data-safe — Postgres backfills a generated column on ADD, and a
dropped one is regenerable from the document it derived from. Where
waymark9 emitted `-- REVIEW:` comments and made a human finish the
sentence, v10 can be aggressive about column reconciliation and
conservative about exactly one thing: UPDATEs that rewrite state
tokens. Those are the only steps marked `:destructive? true`, and
nothing ever applies them silently.

## One projection, three consumers

`store/kind-projection` is the single description of a kind's table —
the engine's fixed columns, one generated column per promoted field,
the standard indexes. Three things read it and can therefore never
disagree:

1. the DDL (`postgres/kind-ddl` renders CREATE TABLE + indexes from
   it; the engine's own five tables carry the same projection shape,
   `postgres/engine-projections`);
2. the desired snapshot (`postgres/desired-snapshot` canonicalizes it
   for comparison against `postgres/table-snapshot`, the live shape
   read from information_schema/pg_indexes);
3. the fingerprint's **storage facet** (`"storage"` in
   `fingerprint-of`: table, columns sorted by name with type-as-string
   and the generated flag, index names). classify-path already filed
   `storage.*` under :shape, so a promotion change is now LAW — the
   diff is :code-or-shape and the boot promotes totally. Consequence,
   recorded: the facet's landing re-hashed every kind once — each
   kind minted one `code_or_shape` revision at its first boot after
   this change (the suites, which drop their worlds, never noticed;
   a long-lived database sees one extra revision row per kind).

## The planner's step taxonomy

`(plan st resources)` → ordered steps, each
`{:kind … :table … :sql … :destructive? bool :reason "one sentence"}`:

| :kind | when | destructive? |
| --- | --- | --- |
| `:create-table` | the table does not exist — the full kind DDL | no |
| `:add-column` | a declared column (promotion or engine-fixed) has no live twin; Postgres backfills generated columns on ADD | no |
| `:drop-column` | a live `f_*` generated column no declaration promotes — derived data, regenerable | no |
| `:recreate-column` | a promoted column's declared type ≠ live type — two steps, DROP then ADD (honest about being a rebuild) | no |
| `:add-index` / `:drop-index` | the engine's standard indexes, reconciled by NAME | no |
| `:rename-state` | live rows occupy a token `:renames {:states …}` retires — `UPDATE … SET state = …` | **yes** |

`(apply! st steps {:destructive? bool})` executes in order; steps
marked destructive are skipped and returned under `:skipped` unless
opted in.

Recorded boundaries (the planner's honesty, not its gaps):

- **Expression drift is invisible.** Postgres normalizes stored
  generation expressions past honest text comparison, so drift
  compares by column name + data type only. Acceptable because the
  expression derives mechanically from (field, type) — it cannot
  move unless one of them did, and the storage facet excludes it for
  the same reason.
- **Engine tables reconcile additively only** (transitions,
  idempotency, drafts, cursors, job_leases): missing tables, columns,
  indexes are created; engine-column drops and retypes are out of
  scope — those columns hold real data, not derivations.
- **A live non-generated column the projection does not declare is
  left standing, unlisted**: only `f_*` columns are known-derived; a
  hand-added column is someone's data, not the planner's to drop.

## State tokens: the continuity map

A declaration may carry `:renames {:states {old new} :actions {old
new}}` — validated by the named `:renames` check (retired keys must
not still be declared; every target reaches a declared token,
directly or through the chain; no cycles). Three consumers:

- **The boot's state-token gate** (waymark9's `check_state_tokens`):
  after the schema gate, any kind whose live rows occupy a state
  neither declared nor mapped refuses the boot with the fix named —
  "declare :renames or migrate". With `:renames` declared but rows
  unmoved, the PLAN gate refuses first (the rename step is pending),
  so a serving engine never holds rows the machine cannot judge.
- **The planner** emits the `:rename-state` UPDATE per retired token
  with live rows.
- **replay-history** (`conformance/replay-violations`) reads logged
  action/state names AND the stored law's names FORWARD through the
  resident chain before judging legality — so a grandfathered row
  (adoption :never) acting after a rename, logging NEW tokens under
  the OLD revision's stamp, still replays legal.

Renames are boot/replay metadata, not fingerprinted law (recorded):
the machine facet already moved when the states changed; the rename
map only says where the old spellings went.

## Boot posture and the CLI

`engine/engine` runs the gate after `ensure-kind!`: non-empty plan →
refuse to serve with a definition-error listing every step and the
remedy. `{:auto-migrate true}` (passed explicitly — `make dev10`
sets `WAYMARK10_AUTO_MIGRATE=1`; never a default) applies the
non-destructive steps in place; a destructive remainder still
refuses, naming `DESTRUCTIVE=1`. Production posture is refuse. The
CLI is `make migrate10` (§9's walk): print / `APPLY=1` /
`DESTRUCTIVE=1`, exit 1 while steps remain — a scriptable deploy
gate. Acceptance held: a freshly-booted mealplan10_dev plans empty.

The suites after this phase: `make test10` **196 tests, 1352
assertions** (the migrate scenario suite: add-column backfills and
serves the collection surface, drop, retype, the rename story
refuse→migrate→serve→replay-green, drift-free empty plan, and the
storage facet minting its revision); `make test-mealplan10` **11
tests, 145 assertions**, untouched.

# 12. Multi-process coherence — two processes, one law

Home: `server/coherence.clj`. Tests: `test/waymark10/coherence_test.clj`
(two engine instances — separate pools, separate registry atoms — over
one database in one JVM: the faithful two-process simulation). This
section retires the bus.py punt ("single-process engines"); the parity
ledger's row is updated above.

The problem it closes: the law slots (`:current-law`,
`:judgment-laws`, the proposed/piloted overlays, the judgment caches)
live in each engine's registry ATOM, and the definitions lifecycle
updates the local atom only. A second engine process against the same
database kept serving the OLD law after a promote on the first —
violating "every path that applies law to a row applies the row's
law". And two running surfaces assumed they were singletons: the
webhook deliverer (the per-subscription cursor is shared and
unguarded — two processes double-deliver) and the clock sweeper
(double work, lock contention on the due pages).

## Law-slot refresh rides the outbox

Definition transitions are ordinary logged transitions (kind
`definition`), so the events dispatcher already delivers them to every
process. The refresh consumer subscribes to exactly those, debounces
bursts (a promote's effect logs several transitions — retire,
supersede, adopt — and one refresh at the end of the burst covers them
all; default 1s), and calls `definitions/boot-revise!`: on UNCHANGED
code boot-revise! is idempotent — hash-equal against the stored
current row, it adopts revisions, holds, pilots, and overlays from the
store, writing nothing. definitions.clj is untouched.

The concurrency reading of boot-revise! (the coherence finding):

- **Safe beside traffic on the adoption paths.** Each kind's slots
  install through ONE `swap!` (definitions' `install!`, which also
  resets the judgment cache), so a concurrent request sees the old
  slots or the new, never a partial set; every invocation resolves its
  rdef from a single registry snapshot.
- **Not safe unconditionally.** On a process whose resident code
  matches no stored current/proposed/piloted revision (the mixed-code
  window of a rolling deploy), boot-revise! would MINT law —
  re-proposing (in `:promote` mode re-promoting) the old law from a
  non-deploy context, and two processes with different resident code
  would mint and withdraw each other's rows forever. Its
  unchanged-code path also withdraws "lingering" proposals — which
  from a refresh would withdraw a LIVE hold minted by a newer-code
  peer. So `refresh!` guards: it runs boot-revise! only when every
  application kind would take a pure-adoption path (resident hash
  equals the stored current's with no foreign proposal rows, or equals
  a stored proposed/piloted row's). Anything else warns and skips —
  a mixed-code process serves what it has, and its replacement is the
  rolling deploy's job, not the refresh's. **A refresh never mints
  law** (tested: the skip, the surviving hold, the unmoved row count).
- **Residual windows, recorded.** Refreshes serialize on the one
  consumer thread, but a lifecycle effect invoked THROUGH a process
  runs its installs on the request thread beside a peer-triggered
  refresh; both derive from the store, every effect step logs a
  transition, and the re-fired consumer converges on the committed
  store. The guard's check and boot-revise!'s own read are separate
  transactions (TOCTOU) — a transition landing between them likewise
  re-fires the consumer. And between a peer's promote commit and this
  process's debounced refresh (~debounce + dispatcher poll), the stale
  slots still stamp creates and adopt targets with the prior law; rows
  already stamped keep being judged by their own law where an overlay
  entry exists, and by the resident code where the stamp is unknown
  (judgment.clj's recorded fallback).

## Singleton roles by advisory lock

`start-role!` elects one holder per role name across every process
sharing the database: `pg_try_advisory_lock` on a well-known bigint,
held on a DEDICATED raw connection — never from the Hikari pool; the
lock is session-scoped and a recycled session would drop it silently
(the dispatcher's LISTEN-connection discipline). The holder runs the
role's start-fn and holds until stopped or the session dies (checked
every retry interval); non-holders retry every `:retry-ms` (default
5s). Closing the session releases the lock, so a clean stop OR a
crashed process hands the role over within one retry interval.

The lock keyspace: the key's high 32 bits are the fixed namespace
`0x574D3130` (the ASCII bytes "WM10"); the low 32 bits are the CRC32
of the role name's UTF-8 bytes — deterministic across JVMs, disjoint
from any other tenant's advisory keys unless it also claims the WM10
word. Two roles existed at this batch's landing —
`webhooks-deliverer` and `clock-sweeper`; batch F (§16) added
`jobs-orphan-sweeper` and `attachments-purge` to the same keyspace.

Tested: exactly one deliverer delivers (event-id counted at an
in-process receiver), the cursor persists past delivery so a takeover
replays nothing, the survivor acquires within the retry interval and
delivery resumes; exactly one sweeper ever starts while a due clock
flip lands (version untouched — maintenance, not a write).

## What stays process-local, recorded

- **SSE subscribers** — correct: each process serves its own
  connections off the shared log; every dispatcher reads every
  transition.
- **Collab rooms** — cross-process live relay NOT built at this
  batch: edits persisted through the shared draft rows, so late
  joiners (and joiners on the other process) converged at sync/rejoin
  only. **Since closed — batch D (§18) built the cross-process relay
  on `waymark10_collab`;** the boundary is recorded here because the
  batches landed in this order.
- **Idempotency and natural replay** — DB-anchored, already safe.
- **Jobs** — the worker claims leases (claim-or-steal on expiry),
  already safe; it stays a per-process start, NOT a role.

## The two-process wire walk

    # process 1 and process 2, one database
    PORT=8010 WAYMARK10_DEV_DSN=$DSN clojure -M:fx -e "(start-dev!)" &
    PORT=8011 WAYMARK10_DEV_DSN=$DSN clojure -M:fx -e "(start-dev!)" &

    # the law, revised through process 1 (propose-mode deploy + promote
    # by a second principal) …
    curl -s -X POST localhost:8010/api/definitions/$DEF_ID/-/promote \
      -H 'x-waymark-principal: elena' -d '{}'

    # … governs process 2 within its refresh debounce, no reboot:
    curl -s localhost:8011/api/plans/$PLAN_ID | jq .meta.law_revision

    # exactly one process delivers each webhook event (advisory-lock
    # election); kill the holder and the other resumes within its
    # retry interval — the cursor rides the database, nothing replays.

## Integration, applied

Coherence owns the deliverer and the sweeper — `coherence/start!`
REPLACED the direct `webhooks/start-deliverer!` and
`maintainer/start-sweeper!` calls: `engine/start!`'s runtime map
carries `:coherence (coherence/start! eng dispatcher {})` in place of
the `:sweeper`/`:webhooks` entries, and `engine/stop!` stops it
before the dispatcher. The jobs worker and mirror discovery entries
stayed as they were.

# 13. Batch E — the outside world, at fidelity

Three deliverables: RRULE expansion in the mealplan event adapter,
the predecessor resolver (design E7), and Mirror push/write-back with
the conflicted-state machine (waymark9 `push_mirror` + reconcile, at
a recorded scope).

## RRULE expansion (mealplan10.event-source)

The pure expander `expand-rrule` closes mealplan10's biggest recorded
deviation from mealplan9 (which leaned on `recurring_ical_events`).
The profile implemented honestly — the one real family calendars use:

- `FREQ=DAILY/WEEKLY/MONTHLY`, `INTERVAL`
- `BYDAY` (weekly only, plain two-letter codes; WKST=MO grid)
- a single `BYMONTHDAY` (monthly); otherwise DTSTART's day-of-month,
  months lacking that day skipped (RFC 5545's rule — and skipped
  months do not count against COUNT)
- `UNTIL` (inclusive) or `COUNT` — COUNT limits the generated set
  BEFORE the window filter and BEFORE EXDATE removal (RFC set
  semantics)
- `EXDATE`, accumulated across repeated lines and comma lists

Outside the profile — `BYSETPOS`, `FREQ=YEARLY`, positional BYDAY
(`2TU`), BYDAY on MONTHLY, multiple BYMONTHDAYs, non-Monday WKST on a
multi-week interval, and `RDATE` (which ADDS occurrences we would
silently lose) — the boundary is recorded, never a crash and never a
silent partial expansion: THAT event is skipped whole with a `*err*`
warning naming the offending part and the full rule. The grammar is
case-insensitive.

Identity is waymark9's: every occurrence is its own mirrored resource,
`external_id = {uid}@{date}` — exactly what the plan's overlap
predicate needs. `feed-occurrences` is the pure heart (iCal body +
window in, `{external-id doc}` out); the HTTP fetch stays a thin
shell. FakeEvents gained `seed-recurring!`, which drives the SAME
expander, so family-week-style tests put a weekly recital on the fake
calendar and prove plan conflicts flip on exactly the occurrence
weeks (the EXDATE'd week stays clear).

Recorded simplification: occurrences follow the rule grid — a DTSTART
off its own BYDAY grid (which real calendars don't emit) contributes
no extra occurrence.

## The predecessor resolver (design E7)

Period chaining is data, not date arithmetic. A `:waymark/ref` schema
entry declaring

    [:previous_plan {:optional true :kind :plan
                     :predecessor {:order :start_date
                                   :partition :ledger}}   ; partition optional
     [:maybe :waymark/ref]]

resolves at CREATE when the body left it blank: the newest existing
row of the target kind by `:order` — ties break toward the smallest
id (search-rows' id tiebreak, so resolution never flaps) — within the
same `:partition` value when declared; no sibling → nil. A supplied
body value always wins. The waymark9 ≤-seeding survives: when the new
row already carries its own `:order` value, only siblings at or
before it qualify — a backdated period links backward, never forward;
a blank order value (an `:on-create` default not yet applied) takes
the newest sibling overall.

Where it lives: `waymark10.server.predecessor` owns all machinery;
`create!` runs it at ONE surgical, documented seam — after decode,
before `:on-create` (waymark9 invoke.py's step order, so the hook may
read the resolved sibling for carry-forward). That is the whole
invoke.clj diff: one require, one threading line with its comment.

Recorded boundaries:

- `:order` must be promoted on the TARGET kind (filterable or
  sortable — the resolving query orders by its generated column);
  refused loudly at create. waymark9 checked this at assembly
  (`_check_predecessor`); the v10 assembly check is a named punt.
- a declared `:partition` whose value is blank on the new row
  resolves nothing — half a partition key must not link across
  partitions.
- mealplan10 wires `plan.previous_plan {:order :start_date}`; the
  schema projection already carries `:predecessor` into `x-ref`.

## Mirror push/write-back and the conflicted state

`sync-states` is now `fresh / stale / unreachable / conflicted` —
waymark9's full machine. The adapter protocol gained
`(push adapter external-id document) → new-etag`.

A mirror may declare `{:push-on-write true}` in its mirror spec, and
ONLY then may it declare its own domain actions (local writes; moves
between non-conflicted sync states — the machine stays the sync
machine, domain state stays in data; names may not shadow the sync
doors). After such a write commits:

- push succeeds → `observe_external` (system actor) stamps the new
  etag + synced_at; the response tells the post-push truth.
- push fails → `mark_conflicted` with the adapter's own words: the
  LOCAL document stands, `conflict_reason` renders, and the state
  tells the truth about the gap. At this scope every push failure is
  the conflicted state (unreachable-on-push vs true etag conflict is
  a recorded non-distinction; the resolve covers both).

`resolve_conflict` (from `conflicted` → `fresh`, confirm required) is
the ONE human door on the sync machine — never a silent
last-writer-wins: `keep=remote` re-pulls the authority's truth and
adopts it; `keep=local` re-pushes ours and adopts the new etag. The
adapter call runs inside the invoke — the same recorded impurity
waymark9's reconcile carried; an unreachable adapter fails the invoke
loudly and the row stays conflicted. A conflicted row never
pull-through-refreshes and takes no further local writes: leaving
conflicted is a person's move, not the clock's.

Wiring: the push pass rides the engine's post-commit `:maintain` hook
via `(mirror/with-push eng)` — an embedding that serves a
push-on-write mirror wraps its engine before building the handler.
Recorded punt: engine.clj's boot does not auto-wire it (no enrolled
app declares one; mealplan's calendar stays pull-only — its adapters
implement `push` only to refuse loudly / to serve tests). Creates
never push (a locally-minted row reaching the authority is a named
punt with the cursors).

## Remaining punts, named

- RRULE: `BYSETPOS`, yearly rules, positional BYDAY, `RDATE`,
  non-Monday WKST on multi-week intervals — each skips its event with
  a warning naming it.
- Predecessor: the assembly-time promotion check (create-time refusal
  holds the line today); `pick`-style carry-forward of sibling fields
  stays the apps'.
- Mirror: per-kind discovery cursors, mirror webhooks/change feeds,
  per-field authority (AuthoredMeta), pushing locally-minted rows,
  auto-wiring `with-push` in the engine boot.

# 14. Batch A — the envelope grows parts, links, and effort

The one post-parity batch that shipped without a notes file; recorded
here from its commit (d8bb3e7), because it closes punts other
sections name. Placed actions render where the user acts: `parts`
projects each part-scope group per item — key const-bound, acceptance
sets folded per item, per-item narration only where reasons differ
(rendered from the same leaf enforcement uses; the 409 detail matches
verbatim). Refinement, not replacement: render.py was checked against
the brief's guess and top-level actions stay complete, with the why
recorded (summary depth drops parts; the client and walker read the
top-level map). Links render from the declared edges with badges from
the row's own materialized facts (no N+1 — made checkable: the badge
must equal the envelope's own data value), embeds capped and spliced
through the link's own href so the two can never disagree.
`depth=summary` and `rows=none` land (any other depth value is one
422); every action carries its demand class
(assent/selection/recall/composition — envelope-only, never law, in
`demand.clj`).

The UI gets its hands back: per-day action buttons, keystroke
validation from the schema, blur-time dry-run with server field
errors inline, facet-fed vocab comboboxes, effort-aware emphasis —
12 headless-browser checks, zero console errors. One live bug fixed:
the old Check button really fired creates and bulks (paths that
ignore `dry_run`); dry-run UI is now scoped to single-resource
actions.

Six new obligations extend the conformance suite (parts truth, parts
enforcement, links truth + wire, depth, rows=none, effort truth);
`:parts` joins the envelope's reserved keys as optional. Home:
`demand.clj`, `server/render.clj`, `server/router.clj`,
`test/envelope_obligations.clj`, `batch_a_envelope_test.clj`. Still
open after this batch: waymark9's `depth=expanded` embed profiles and
declared collection columns/table hints.

# 15. Batch G — declaration ergonomics (the defresource split)

The declaration remains ONE EDN-able map after `normalize-resource`:
the fingerprint, the diff gate, the registry, and the overlays all
read that value. Everything in this batch is sugar that PROJECTS INTO
the canonical map; normalize is the seam. The governing invariant:
**two spellings, one law** — colocated vs split vs def'd vs inline
spellings of the same declaration fingerprint identically, so a pure
style refactor mints zero revisions. `batch_g_invariance_test` pins
it: the rewritten fixtures against their old split spellings
(byte-identical hashes), and a property over generated declarations
rendered both ways (identical normalized maps AND identical
fingerprint hashes).

## Schema-entry colocation

Field-scoped law may be declared on the schema entry's property map;
`normalize-resource` (first step of its pipeline,
`project-colocated` in `waymark10.resource`) projects it to the
canonical top-level keys:

| colocated (entry props)      | projects to                                    |
|------------------------------|------------------------------------------------|
| `{:derived spec}`            | `:derived {field spec}` — the fact's field IS the entry (waymark9's `Derived()`-as-field-default, restored) |
| `{:filter #{:eq :range}}`    | `:filterable {field ops}`                      |
| `{:sort true}`               | field joins `:sortable :fields`                |
| `{:sort :default}`           | …and claims the default (`"field"`)            |
| `{:sort :default-desc}`      | …and claims the descending default (`"-field"`) |
| `{:part-scope {:key :date}}` | `:part-scopes {field {:path field :key …}}`    |

Rules:

- The sugar keys are **stripped from the entry props before the
  schema compiles or fingerprints** — they are declaration
  ergonomics, never schema properties. The stripped schema form is
  the split spelling's form, and the published JSON Schema is
  unchanged (pinned in `batch_g_declare_test`).
- **Exactly one home.** Declaring a concern both colocated and
  top-level for the same field is a definition error whose ex-data
  names the check: `{:check :one-home}`. Different fields may split
  across homes freely (e.g. `:state` filtering stays top-level — the
  engine's state is not a schema entry).
- **At most one sort default**, counting a top-level `:default` —
  two claims are refused at normalize.
- A colocated part scope's `:path` is its entry; naming a different
  `:path` is refused. The scope's name is the field.
- Colocation applies to the top-level entries of a `[:map …]`
  resource schema — item fields of nested vectors have no top-level
  concern to project.
- Projection runs before vocab self-merge, so a vocab field may still
  colocate an explicit `:filter` and win over the `#{:eq :in}`
  default.

## `waymark10.declare` — defaction and defderived

In the `defguard` mold: each defs a **plain map identical to the
inline spelling**, so the def'd value drops in anywhere the inline
value does — directly in `:actions`/`:derived`, or colocated on a
schema entry (`[:end_date {:derived end-date} …]`).

Validation timing — the def site validates exactly what it can see:

- **defaction** runs `resource/normalize-action` (now public — the
  same construction-shape gate defresource runs) eagerly and
  discards the result: a missing `:safety`, a draft on a bulk, a
  fenced batch fails **at the def line** (`defaction/<name>: …`).
  Normalization itself still happens once, at defresource; the
  cross-referencing checks (states exist, judged fields are input
  fields, place names a part scope) honestly wait there. An inline
  `(fn …)` `:handler` gets its canonical printed form captured as
  `:waymark10/form` metadata — the same identity `defhandler` mints —
  so the fingerprint hashes the law, never the object.
- **defderived** normalizes the spec (`normalize-derived-spec`,
  factored public and idempotent: expression trees canonical, count
  `:where` values as sets) and scope-validates at def time — `:over`
  is right there, so an out-of-scope `(var …)`, a spec with both/
  neither of `:expr`/`:count`, or `:over` on a count fails at the def
  line. Whether the fact is a schema field, whether the edge exists,
  whether facts cycle — defresource's and assembly's questions.

## Blessed idioms

- **Action groups**: a var holding a map of actions, merged into
  `:actions` — `(merge {:assign_meal …} closing-actions)`. The merge
  result is the same map the monolith spelled.
- **Named safety values**: `(def routine {:idempotent true
  :reversible true :confirm false})`, cited as `:safety routine`.
  This honors safety-never-inferred: reference is explicit
  declaration, not inference — the map is still spelled once, in
  full, and every citation names it; no property is ever computed
  from the action's behavior. What the rule forbids is the engine
  *guessing* safety; a name is the opposite of a guess.
- **Local builder fns** returning plain maps, when a family of
  declarations differs by one parameter (the fixtures'
  `calendar-clear-guard` is the house example).

## The proof shape

- `test/waymark10/fixtures.clj` is rewritten in the new style:
  plan's derived/filterable/part-scope/sort colocated onto entries,
  `update_recipe`/`assign_meal` def'd (one with an inline captured
  handler, one referencing a `defhandler`), the closing pair as an
  action group, `routine` as a named safety.
- `batch_g_invariance_test` keeps the OLD split spellings alive,
  constructed in the test **sharing the fixtures' guard/handler
  objects** (a code guard without a stateable form hashes by printed
  fn identity, so the comparison must share instances — exactly what
  a style refactor does), and pins fingerprint hashes byte-identical
  for both kinds, plus full normalized-map equality for plan.
- The property (`a-style-refactor-mints-zero-revisions`, 100 trials):
  a generator over small declarations (fields with optional
  filter/sort law, at most one default, an optional derived fact, an
  optional part-scoped vector) rendered colocated and split →
  identical normalized maps and identical fingerprint hashes.

## The mealplan10 rewrite (the follow-up, done)

All six mealplan10 kinds now live in the batch-G spelling;
`mealplan10/test/mealplan10/style_invariance_test.clj` keeps the old
split spellings alive (batch-G technique: constructed as data,
sharing the namespaces' guard/handler objects — and the hoisted
`g/require` gate, since `g/require` mints a fresh `:check` fn per
call) and pins all six fingerprint hashes byte-identical, plus full
normalized-map equality.

Field notes from the real consumer, for the style guide:

- **defderived + entry citation is what keeps colocation readable.**
  plan's biggest derived (`all_days_covered`, the `every` tree) would
  read badly inlined in entry props; def'd and cited
  (`[:all_days_covered {:optional true :derived all-days-covered} …]`)
  it reads better than the old top-level `:derived` block, because
  the fact sits on its field. Only true one-liners
  (`has_conflicts`, prep_task's `overdue`) went inline.
- **A Mirror kind colocates fine.** `mirror/declaration` returns a
  plain map, so entry-level `:filter`/`:sort` on the app schema
  project through the weave. Pin around the SAME wrapper on both
  sides; the weave re-mints the `observe_external` and
  `resolve_conflict` handler fns per call — identical canonical-form
  hashes (so the hash pin is exact) but distinct objects (so
  normalized-map equality holds only modulo those two handlers).
- **Some kinds have nothing to colocate.** rotation declares no
  field-scoped law at all (only `:state` filtering, which is not a
  schema entry); its whole style rewrite is two named safety values.
  The style guide is a default, not a law — an honest no-op is fine.
- **No colocated home exists for `:one-of`.** plan's `days/coverage`
  group is field-scoped in spirit (it governs `:days` arms) but stays
  top-level; a possible future projection, not claimed here.
  *(Claimed since: the part spelling — `defpart` + `:part` entry
  citation — gives the group its colocated home;
  `docs/waymark10-spellings.md`.)*
- **Line counts go up, not down** (+18/+13/+46/+33/+9/+7 across
  meal/rotation/plan/grocery_list/prep_task/event): docstring style
  notes, def headers, and named-safety comments cost lines. The win
  is locality (the law on its field, the day-shaped actions one
  group), not brevity.

# 16. Batch F — engine odds-and-ends

Seven deliverables, each with its own test namespace
(`test/waymark10/batch_f_*.clj`).

## GIN indexes on vocab arrays (phase 7's named punt, closed)

`store/kind-projection` gains a GIN entry per vector-typed
`:waymark/vocab` filterable field —
`ix_<table>_<field>_gin ON <table> USING gin ((data->'<field>'))` —
so the DDL renders it, the migrate planner reconciles it by name
(add on promotion, drop on retirement; the existing `ix_*` index
discipline needed nothing new), and the fingerprint's storage facet
records it (index names are already part of the facet). Vocab arrays
still have no single-value promotion: the GIN entry is their whole
storage story.

The load-bearing companion change: the `:in-any` cond now spells the
jsonb `?|` operator (JDBC-escaped `??|`) instead of
`jsonb_exists_any(...)`. **Postgres matches indexes through operators
only** — the function spelling could never walk the index. Semantics
identical (`jsonb_exists_any` IS `?|`'s backing function); the test
EXPLAINs the operator over a seeded table and asserts the plan names
the index.

Boundaries, each a sentence:
- Scope is vector-of-vocab filterable fields only — a bare (scalar)
  vocab keeps its promoted text column; `:string` arrays and other
  array shapes stay unindexed (nothing filters them today).
- Because vocab fields self-merge into `:filterable`
  (resource.clj's rule), **every kind with a vocab array gains the
  index and therefore a new storage-facet fingerprint** — mealplan10's
  `meal` (themes) will mint a `code_or_shape` revision at its next
  boot and its migrate plan will carry one `add-index`; both are the
  designed paths, but the deploy should expect them.

## Jobs completeness (waymark9's lifecycle pieces, restored)

- **`:queued` is back**: a job is born `:queued`; the worker's claim
  starts it (`:start`, queued → running, worker-gated and hidden).
  The 202 envelope now honestly says nobody works the job yet.
- **Job artifacts**: the worker persists the final per-item report on
  the row's data (`:report` — action, kind, total/succeeded/refused/
  failed, the refusal list with a per-entry `class` of
  `refused`/`failed`) via a maintenance write just before `:complete`
  fires, so the completed envelope carries the whole outcome, not
  just the running progress.
- **The orphan sweep**: `jobs/sweep-orphans!` re-queues `:running`
  jobs whose lease is absent or expired (`store/job-lease`, a new
  additive protocol read) — the re-queue is a logged transition
  (`:requeue`, system actor), so the outage is in the audit trail.
  `jobs/start-orphan-sweeper!` elects ONE sweeper per database via
  `coherence/start-role!` (role `:jobs-orphan-sweeper`) — a role
  ADDED to coherence's keyspace, nothing restructured. The lease
  steal still resumes too; the sweep just makes the orphan visible as
  queued instead of leaving it wearing a dead worker's `:running`.

`engine/start!` now starts the orphan sweeper in its `:runtime` map
(the one-liner landed post-merge). Two existing assertions
(`jobs_test.clj:106`, `bulk_batch_test.clj:178`) were updated
`"running"` → `"queued"` — the minimal tracking of the restored
state; nothing else in either suite moved.

## Webhooks: `:revoked` and the per-subscription delivery policy

- `:revoke` (active/paused/failed → revoked, terminal) is
  owner-gated: only the principal whose id is the row's owner may
  revoke; the guard's refusal names the pause alternative. Revoked
  never resumes and never hears another event.
- `:delivery_policy` (`"fail"` default | `"skip"`), declaration-
  driven on the subscription row: `"fail"` keeps v10's discipline
  (bounded retries, then mark_failed, cursor PARKED at the refusing
  event); `"skip"` is waymark9's liveness posture (log to `*err*`,
  advance the cursor past the refusing event, stay active) — the
  trade is now chosen per subscription instead of imposed globally.

## Attachments: sha256, duplicate detection, the purge sweep

- The byte PUT computes the content's sha256 and stamps it beside the
  size (`mark_stored`'s input; the handler writes both to data, so
  the envelope exposes them like any field).
- Duplicate content detects by the sha: a re-PUT of byte-identical
  content on a stored row natural-replays (same input digest → the
  2.0 replay, 200); different content — same size or not — refuses
  409 before the file is touched. Boundary: a row stored BEFORE this
  batch carries no sha, so a re-PUT on it refuses 409 (the pre-sha
  digest can never replay) — the honest answer for bytes whose
  provenance was never recorded.
- `attachments/purge-deleted!` removes `:deleted` attachments' bytes
  from the directory (metadata rows stay — the audited record;
  idempotent re-runs); `start-purge-sweeper!` elects one sweeper per
  database via `coherence/start-role!` (role `:attachments-purge`).
  Like the orphan sweeper, it is wired into `engine/start!`
  (landed post-merge).

## Consumers-as-API (`server/consumers.clj`, new)

`(consumers/register-consumer! eng name f)` — named, durable log
consumers: cursor rows in `waymark10_cursors` (`consumer:<name>`),
checkpointed per processed event, riding the dispatcher as the wake
signal exactly as the webhook deliverer does. At-least-once by
construction (checkpoint after the call); a throwing consumer PARKS
at the refusing event (nothing skipped silently — the webhook "fail"
posture); registration seeds at the newest transition
(`:from-origin? true` hears history); `drain-consumer!` is the
deterministic test entry.

**Deferred unification, named**: the webhook deliverer is NOT
refactored onto this API. It predates it, its drain is
per-subscription (N cursors behind one thread) where a consumer is
one cursor, and its failure policy is a resource-state machine rather
than a park. Folding the deliverer onto `register-consumer!` (one
consumer per subscription, policy as the consumer's error fn) is a
real simplification — deferred until a third consumer of the pattern
exists, so the abstraction is grown from three points, not two.

## OpenAPI: response schemas, security, surfaces

`components.schemas` gains the four shared shapes — `envelope`,
`collection` (items reference the envelope), `problem`,
`bulk_report` — and every route references them (act/GET 200s and
create 201s → envelope, collection GETs → collection, bulk/batch
reports → bulk_report, 202 defers → envelope, problem responses →
problem). `securitySchemes` names both identity doors (`bearer` —
the OIDC relying party; `devHeader` — X-Waymark-Principal) and the
document declares them as alternatives. The surfaces routes document
per declared surface (`/api/surfaces/<name>/{id}`). Still structural,
not per-kind: the per-kind data model rides `/api/schemas/{kind}`
and the envelope's own affordances — recorded scope, unchanged.

## The in-memory Storage twin (`store/memory.clj`, new)

The full Storage protocol over one atom — tables, the transition log
with assigned ids, idempotency, drafts, cursors, leases — faithful to
the Postgres MEANINGS:

- Documents round-trip through wire JSON on every write, so reads
  hand back exactly what JSONB would (keyword keys, exact decimals,
  keywords collapsed to strings).
- The cond grammar interprets casts as value coercions (both sides
  parse to the cast's type and compare with `compare` — numeric
  `1.0 = 1` holds, unparseable values throw, exactly as SQL would);
  nil left values fail every comparison (SQL NULL); `:in-any` matches
  string elements only (jsonb `?|`'s reading).
- Transactions are a global monitor + snapshot-rollback: writes
  serialize (the row lock, coarsened to the store) and an exception
  restores the snapshot — atomic bulk/batch refusals roll back
  exactly as Postgres does; nested with-tx snapshots independently.
- **The notify seam, recorded**: `pg_notify` becomes an in-process
  callback registry (`memory/subscribe-notify!`), flushed when the
  outermost with-tx completes — an event exists iff its transaction
  did, like the wire original — but the events DISPATCHER (a LISTEN
  connection) does not run over this storage; an engine over the twin
  runs without a dispatcher or polls the log. SSE/webhook liveness is
  therefore Postgres-only; the twin's consumers drain by hand.

Acceptance: `batch_f_memory_test.clj` runs the invoke-test scenario
suite (create + derived materialization, guards, acceptance sets,
natural replay, idempotency discipline, the acknowledge protocol,
serialized concurrent writes) plus collections (state filters, vocab
membership, facet unrolling, typed range casts, paging with the id
tiebreak, sort) and atomic-rollback against
`(inv/engine {:storage (memory/storage) …})` — no database.

## The focused runs

    cd waymark10 && WAYMARK10_TEST_DSN="jdbc:postgresql://localhost:5433/waymark10_f_test?user=ckopsa" \
      clojure -M:test --focus waymark10.batch-f-gin-test \
                      --focus waymark10.batch-f-jobs-test \
                      --focus waymark10.batch-f-webhooks-test \
                      --focus waymark10.batch-f-attachments-test \
                      --focus waymark10.batch-f-consumers-test \
                      --focus waymark10.batch-f-openapi-test \
                      --focus waymark10.batch-f-memory-test

28 tests, 196 assertions, 0 failures at landing. Regression (same
DSN, focused): jobs/bulk-batch/webhooks (15 tests, 155),
phase9a/phase9b/collections (20, 226), invoke/migrate/fingerprint
(16, 112), conformance/drafts (12, 191) — all green; the suites now
ride `make test10`.

# 17. Batch B — access completeness

Phase 9a landed identity and the kind/id/action grant. Batch B closes
four of its named punts: **field/argument visibility modes**, **the
negotiation machine** (waymark9's request_access, resized), **the
own-grant surface**, and **invite → bind membership**. Everything
rides the existing seams — the visibility closure map the router
resolves once per request, and render's projection — no new
middleware, one new resource kind. Home:
`server/{grants,members,oidc,render}.clj`, five flagged router seams,
`test/waymark10/batch_b_{access,members}_test.clj`.

## Field and argument modes

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
  state checks.
- Collection `query`/`create` input schemas and facet counts are
  unprojected — items themselves project fully. Drafts likewise.
- Create-argument modes are unported: `:args` grades declared actions.
- Field granularity is the top-level data field; item fields inside a
  part array are not separately gradable — redact the array.
- Grant projection of SSE / surfaces / openapi / collab **stays the
  phase-9b named punt**: a scoped request still 404s those routes.
- Idempotency replays still serve the first execution's unprojected
  bytes (the phase-3 render-fn punt, re-extended).

## The negotiation machine

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
named grant rather than minting a sibling (superseded for the
anchorless ask by the mint fix, below); the effect rides the wire
boundary — an engine-internal invoke of approve does not extend.

## The own-grant surface

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

## Invite → bind membership

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

## Merge items, landed post-merge

The two engine.clj one-liners the batch could not land itself are in:
`full-registry` enrolls `grants/approval-request` beside
`grants/grant`, and `:members` survives `engine`'s opts whitelist so
`{:members :invited-only}` survives boot. The two phase9a_test pins
that asserted the punt this batch removes (`grant-scoped-surface`:
the own grant's 404, well-known's kinds list) were updated to the new
truth.

## Runs

```
docker exec waymark-test-pg psql -U ckopsa -d postgres -c "CREATE DATABASE waymark10_b_test"  # once
cd waymark10 && WAYMARK10_TEST_DSN="jdbc:postgresql://localhost:5433/waymark10_b_test?user=ckopsa" \
  clojure -M:test --config-file /tmp/kaocha-b.edn        # ns-patterns waymark10\.batch-b-.*-test
```

Batch B at landing: **8 tests, 140 assertions, 0 failures.** Scoped
regression (phase9a / router / batch-a-envelope / collections,
read-only): 33 tests, 338 assertions, 2 failures — both the stale
pins above (since updated), nothing else moved. The suites now ride
`make test10`.

## The mint fix — approval mints the bootstrap grant

Batch B's negotiation machine shipped extension-only: an
approval_request was create-guarded to a grant its requester already
held, so bootstrap-from-zero was unreachable and someone had to
author grants ahead of time. That inverted the design intent —
recorded as such; this is a fix, not a feature. The original purpose
restored: **an agent requests the access it needs; a human approves;
the approval MINTS the grant.**

- **The create opens.** `grant_id` is optional; any NAMED
  (non-anonymous) principal may file an ask — `{kind, ids?, actions,
  fields?}` of what it believes exists, requester stamped by
  on-create as before. An ask that names a grant still takes the
  extension path unchanged (`requester-holds-the-grant` now judges
  only when an anchor is present).
- **The mint flow's state path.** Approve (four-eyes'd from the
  requester, as before) stamps the minted grant's name onto the ask —
  `grant-{approval-id}`, deterministic, so the requester reads where
  to go and a replay restamps the same. The post-commit effect
  (`approval-effects!`, keyed on the approval id exactly like the
  :extend precedent) then lands the grant: EXTEND when the named
  grant exists, MINT when it does not — `grant/create` (audience =
  requester, scope = exactly the approved ask) then `grant/accept`,
  both by the `waymark10-grants` system actor, both logged, both
  idempotency-keyed (`approval-mint-{id}`, `approval-accept-{id}`).
  Accepted on mint through the machine's own accept because the ask
  WAS the audience's consent; the requester's next presentation of
  the stamped grant id scopes it in. Deny stays deny: note recorded,
  no grant moves or exists.
- **The abuse surface.** Anchorless creates are paced to **20 per
  rolling hour per principal** and open asks capped at **10 per
  requester**; both guards read the requester's own rows through ctx
  `:find` and refuse with sentences — the cap names every pending
  ask's id, the pace names when the window reopens. Recorded
  deviation: the `guards/rate-limit` builder wants the engine's
  `:rate` hook, which v10 never wired, so the rows are the record;
  past the 500th lifetime ask the pace window reads a stale
  oldest-first page (query-rows' one ordering) — the open cap, whose
  churn needs a human verdict per ask, is the standing wall.
- **The concealment constraint, byte-pinned.** An ungranted kind's
  404 grew NOTHING — no request-access remedy, which would leak
  existence. The body's exact bytes are pinned in the test
  (`ungranted-404`), live grant and dead grant answering identically.
  Discoverability lives on the negotiation surface instead: `grant`
  and `approval_request` now ride EVERY named principal's scoped
  request — live, dead, foreign or unknown grant alike — so the
  asking door is never concealed (it is how access starts, and how a
  dead grant's holder asks again). Anonymous stays outside: scoped
  anonymous sees no door (404), unscoped anonymous refuses by
  sentence (an ask that would grant nobody).

Run (own database, focused):

```
docker exec waymark-test-pg psql -U ckopsa -d postgres -c "CREATE DATABASE waymark10_grants_test"  # once
cd waymark10 && WAYMARK10_TEST_DSN="jdbc:postgresql://localhost:5433/waymark10_grants_test?user=ckopsa" \
  clojure -M:test --focus waymark10.batch-b-mint-test
```

At landing: **7 tests, 80 assertions, 0 failures**; grants-adjacent
regression (batch-b-access / batch-b-members / phase9a / router): 28
tests, 320 assertions, 0 failures; declaration suites (coherence /
registry / batch-f-openapi): 32 tests, 107 assertions, 0 failures.

# 18. Batch D — waymark-relay/2 and concurrent text

This batch closes the phase-9b collab punts (staleness rejection,
acks, presence, regate, the cross-worker bus) and §12's "collab live
relay still process-local" boundary, and it adds what waymark9 never
had: server-authoritative OT for prose fields, proved generatively.

Home: `server/collab.clj` (protocol, OT core, cross-process relay)
and `server/drafts.clj` (the draft document shape). Tests:
`test/waymark10/batch_d_ot_test.clj` (the pure OT proof),
`batch_d_collab_test.clj` (relay/2 on the wire, one engine),
`batch_d_relay_test.clj` (two engine instances over one database —
the faithful two-process simulation).

## The draft document (drafts.clj owns the shape)

Per-field revs and authors persist INSIDE the draft row's values
jsonb — no schema change, `waymark10_drafts` is untouched:

    {"_doc": 2, "values": {…}, "revs": {field: n},
     "authors": {field: actor}, "ops": {field: [{rev, ops}]}}

`drafts/document` and `drafts/envelope` are the only readers and
writers; a pre-envelope row (a plain values map) reads as a rev-0
document, so old rows keep working with no migration. The wire view
still answers `{values, base_version, prefill}` — now with `revs`
and `authors` beside them, additively. A draft PUT is a whole-
document replace: changed fields' revs bump, their authors restamp,
their op logs clear. Acting consumes the draft row and everything in
it — op logs and revs are consumed, not archived.

## The protocol

    server → joiner   {type: "state", values, revs, authors,
                       base_version, stale, participants}
    client → server   {type: "set", field, value, rev}      (rev = base)
    client → server   {type: "edit", field, rev, ops}       (prose only)
    client → server   {type: "sync"}
    server → sender   {type: "ack", field, rev}
    server → sender   {type: "stale", field, rev, value}
    server → others   {type: "update", field, value, rev, author}
    server → others   {type: "edit", field, rev, ops, author}  (TRANSFORMED)
    server → room     {type: "presence", event: joined|left|roster,
                       actor?, participants}
    server → room     {type: "regate", base_version, revs?, gone?}
    server → room     {type: "resync", field}   (oversized-relay fallback)
    server → sender   {type: "error", errors}

Scalar fields: per-field revisions with explicit staleness rejection
— base ≠ current answers `stale` with the field's truth; the silent
per-field LWW of phase 9b is gone. Prose fields (`{:x-display
{:widget "prose"}}`) take operation frames; a `set` on a prose field
is still legal and acts as a rebase point (rev bumps, op log
clears — in-flight edits against older revs go stale).

## The OT core (in-house, no deps)

Ops are `[{retain n} | {insert s} | {delete n}]`, normalized (no
zeros, no adjacent same-type components). `transform-pair a b →
[a' b']` is the component-wise text transform with `a` as the
earlier/priority side (its inserts land first at a tie); the server
holds a canonical per-field op log in the draft document and
transforms an arriving edit against every op applied since the
client's base rev, applies it, acks the author with the new rev, and
broadcasts the TRANSFORMED op. The log is capped at **op-log-cap =
200 entries per field** and compacts by dropping the oldest; an edit
whose base rev predates the retained horizon answers `stale` and the
client resyncs — compaction trades memory for a resync, never for
corruption.

THE PROOF (the acceptance bar): `batch_d_ot_test` drives exactly the
functions the wire handlers call. TP1 (the transform identity,
`apply(apply(s,a),b') = apply(apply(s,b),a')`) over 300 generated
doc/op pairs; then the full convergence property — 2–4 model clients,
each the protocol's real client half (one op in flight, unacked queue,
incoming server ops transformed through the queue with the same
transform-pair), issuing arbitrary interleaved insert/delete batches
from arbitrary base revs under an arbitrary delivery schedule; after
quiescence every client's locally-composed document must equal the
server's, and every rev the server's rev — **250 trials per run,
shrinkable, plus 61 wire assertions and the two-engine story; the
suite has passed repeatedly (5+ consecutive full runs)**. A transform
bug shrinks to a minimal action list here before it touches a socket.

## The one write path, cross-process safe

Every accepted set/edit persists through the same draft row a PUT
writes, in a transaction that takes the resource row's FOR UPDATE
lock — the same fence an invoke takes — so collab writers on separate
processes serialize and rev assignment is race-free. pg_notify on
**waymark10_collab** rides that same transaction (the outbox
discipline: a relayed frame exists iff its commit does), stamped with
a per-process origin nonce so the publisher skips its own echo.

Each engine runs one dedicated LISTEN connection (raw, never from
the Hikari pool), started lazily with its first room and stopped with
its last. Presence rosters merge across origins: join/leave frames
adjust a per-origin remote roster, a first-seen origin is answered
with one targeted heartbeat (rosters converge in a round trip), the
heartbeat (`:collab-heartbeat-ms`, default 5000) re-asserts each
process's local members, and an origin silent for three heartbeats
is evicted — a crashed process's ghosts leave on the clock. Frames
over 7000 bytes relay as `{type: "resync", field}` (pg_notify's
payload ceiling), recorded.

## Regate

When the row's version moves (any transition — the fence bumps):
every field's rev bumps, prose op logs clear, the bumped document
persists with the new base_version, and the room hears
`{type: "regate", base_version, revs}`; sets/edits against the old
base answer stale until clients re-pull the prefill. Detected on the
write path (stored base_version ≠ the locked row's version) AND
proactively by a per-relay consumer on the engine's events
dispatcher — every process's rooms hear about an act that happened
anywhere. An act that CONSUMES the draft broadcasts
`{type: "regate", gone: true}`: the draft (op logs, revs, all of it)
went with the act's commit; composition starts anew at rev 0. An
engine that never started (no dispatcher) keeps write-path detection
only, recorded.

## Punts, each named

- UI chrome: ui.html still speaks nothing of collab — cursors,
  presence avatars, and its relay/2 client are future work. Any
  client MUST now send the field's base rev on set (a rev-less set is
  base 0 and goes stale after the first write).
- Cursor positions / selections on the wire: not in relay/2's
  vocabulary here (waymark9 didn't carry them either).
- A plain draft PUT beside a live room bumps revs but broadcasts no
  frame — live clients converge at their next stale/sync.
- Presence rosters dedupe by principal id; the same principal on two
  sockets of one origin leaves the roster on the first close and is
  corrected by the next heartbeat.
- `stale` on a prose edit answers the field's full current value,
  not a patch — the resync is one frame.

## Integration, applied post-merge

`test/waymark10/collab_test.clj` (phase 9b) asserted the OLD relay/1
semantics in three places (the sender-hears-no-echo assertion —
relay/2 acks the sender by design — the room-global rev, and the old
sync shape); it is superseded by `batch_d_collab_test.clj` and was
deleted post-merge. No engine/router/store changes were needed:
collab reads the dispatcher from the engine's `:runtime` atom when
present and carries everything else in the draft document. The one
new engine opt, `:collab-heartbeat-ms` (default 5000), survives
`engine/engine`'s select-keys whitelist.

## Focused runs (batch D's own database — waymark10_d_test)

    cd waymark10
    clojure -M:test --focus waymark10.batch-d-ot-test \
      --focus waymark10.batch-d-collab-test --focus waymark10.batch-d-relay-test

11 tests, 123 assertions (300 TP1 trials + 250 convergence trials
per run inside them), 0 failures at landing. `WAYMARK10_D_DSN`
overrides the default
`jdbc:postgresql://localhost:5433/waymark10_d_test?user=ckopsa`. The
suites now ride `make test10`.

# 19. Batch C — law completeness

Batch C closes the derivation-law gap: the derived-law overlay
(judgment's structural twin), the derivation event class, the
blast-radius meter, the population grammar gate, the earned
count/sum vocabulary, and the cross-kind fact DAG. Owned files:
`server/{definitions,judgment,maintainer,events}.clj`,
`fingerprint.clj`, `derived.clj`, `expr.clj`, `checks_assembly.clj`,
`resources/waymark10/ui.html` (one handler),
`test/waymark10/batch_c_*.clj`.

## The derived-law overlay

`waymark10.derived/specs-under` — waymark9 `derived.py specs_for`,
in the v10 medium: the rdef's `:judgment-laws` slot ({revision →
stored fingerprint}, the SAME slot the judgment overlay reads,
installed by the definitions lifecycle) resolves a row's derivation
specs from its revision's stored trees. Recoverable leaves: the
`expr` tree and the aggregate `where` filters — exactly the paths
`classify-diff` files as `data_law`, so the overlay is EXACT for
every law the slot can hold. Edge identity (`:over`, `:owns`,
`:related`, `:of`) rides resident, the judgment precedent verbatim;
`fn=` facts stay resident (a hash is not a law — the v7 deviation,
now confined to fn facts). Resolved maps cache on `:judgment-cache`
under `[revision ::waymark10.derived/specs]` keys, disjoint from
judgment's `[revision action-name]` keys; every definitions
`install!` resets the cache, so invalidation is free.

Consumers, wired:

- `derived/materialize` resolves `(:law-revision row)` itself — the
  write boundary (invoke's finish! and create!) needed NO edit; a
  grandfathered row's expr facts recompute under its birth law at
  every write. `tampered` judges by the same law.
- the maintainer: `maintain-row!*` computes aggregates from
  `aggregate-specs rdef revision`, materializes (row-law-aware), and
  derives the clock index from the row's law's trees
  (`next-flip-at` gained a revision arity). The REVERSE dependency
  map stays resident — edges are `:code-or-shape`, no live revision's
  edges can differ (recorded boundary).
- `backfill!` recomputes each row under ITS OWN law, which makes it
  the correct repair for every restamp: adopted rows land the new
  values, grandfathered survivors repair under their birth law.

The phase-6 named seam is wired: promote (and pilot, and a withdraw
that moved rows — the returning population's facts were computed
under the pilot) call `repair-stale!` → `maintainer/backfill!` with
the diff's `stale-facts`, markers naming since-removed facts dropped
with a *err* line (waymark9's `_still_declared`).

## Derivation-class events

waymark9's second channel comes home WITHOUT making maintenance a
transition. The observations table (events.clj owns the DDL — a
store-protocol surface for it is the named follow-up):

    waymark10_observations (
      id bigserial PRIMARY KEY,
      kind text NOT NULL,
      resource_id text,          -- NULL = the whole kind (bulk restamp)
      class text NOT NULL,       -- recompute | flip | restamp
      changed jsonb,             -- fact names, or {law_revision, rows}
      at timestamptz DEFAULT now())
    + index (kind, id), pg_notify on waymark10_observations

Appended INSIDE the maintenance write's transaction
(`events/record-observation!`), so an observation exists iff its
commit does — the outbox discipline. Emitters, covering the three
live-update gaps:

- cross-row count/sum recomputes: `maintain-row!*` when facts moved
  (class `recompute`; backfill repairs use the same class),
- clock flips: the sweep passes class `flip`,
- bulk law restamps: `definitions/restamp!` (pilot, promote's
  immediate adopt, withdraw's return) emits one kind-wide
  observation (`restamp`, resource_id NULL, changed carries
  `{law_revision, rows}`) beside the per-row backfill recomputes.
- the measure report landing on `data.measure` announces itself
  (`recompute` on the definition row).

The dispatcher LISTENs the second channel on the same parked
connection and drains both tables on every wake-up, each with its
own id horizon. Subscriptions opt into classes (`:classes`, default
`#{:transition}` — the coherence consumer and the webhook deliverer
never see a shape they predate); the SSE handler subscribes to both
and frames derivations as `event: derivation` with NO id line.

Recorded scope: no Last-Event-ID replay for derivations (the table
is the record; a missed flip re-derives from the envelope);
derivations bypass a replaying subscription's transition pause, so
ordering ACROSS classes is not promised; observations (like the SUM
SQL) are a Postgres surface — any other backend warns and drops.

ui.html: one handler beside the transition refetch — a derivation
event whose self (or kind, for kind-wide restamps) matches the open
screen debounces into the same refetch.

## Blast radius (measure)

waymark9's `measure` action + `BlastRadiusMeter`, synchronous (no
meter job — v10 job artifacts landed in batch F, but the meter stays
synchronous, recorded): `maintainer/blast-radius` full-scans the
target kind in id-keyset pages and, per redefined derived fact,
evaluates BOTH laws' specs over current data — expr facts through
`compute-facts`, aggregates through both where-filters' SQL —
counting the rows whose value differs. Population-scoped when
piloted. Report:
`{:facts [{:fact "kind.fact" :flips n :of total :sample [≤20 ids]}]
:scan "full" :population … :from_revision :to_revision :at}` on the
definition row's `data.measure`.

Recorded deviations: the proposed/piloted self-loop is spelled as
TWO actions (`measure`, `measure_pilot`) because a v10 action
declares one `:to`; the report lands via a maintenance write AFTER
the transition commits (the measure POST's response predates it —
GET re-reads; waymark9's answer carried a job id, same shape of
honesty). Guards are input-free and probe-able: `data-law-measures`
(expr over `diff_class`) and `redefines-derived-facts` (a
judgment-only data-law diff has no blast radius — waymark9's exact
refusal sentence). Judgment blast radius (newly-refused rows) stays
punted, named.

## Population grammar

waymark9's `check_population` punt closes: pilot's `where=` runs
each field through the collections parser's PUBLIC `parse-query`
(collections.clj untouched) against the TARGET kind's grammar, and
only plain equality conds pass — range suffixes, multi-values and
non-scalar values are refused by sentence (a restamp is an equality,
not a query). The guard needs the engine's registry, which a static
declaration cannot reach, so `boot-revise!` appends the
engine-closed guard to the pilot action (idempotent by name);
engines that never run the definitions boot also never pilot.

## Vocabulary growth, earned

| Node | Demanded by | Date |
| --- | --- | --- |
| `(count <coll>)` / `(count [d <coll>] <pred>)` expr quantifier | the blast-radius acceptance laws (batch_c_measure_test's flip-count shapes) — waymark8 §1 had the pair; v10 ports it with the meter that reads it | 2026-07-10 |
| `(sum [d <coll>] <expr>)` expr quantifier | same admission — the summed-quantity flip the meter measures | 2026-07-10 |
| `{:sum {:related|:owns …, :where …, :of field}}` aggregate spec | the meal-prep quantity rollup (batch_c_sum_test's total_qty) — mirrors `:count`: fingerprint facet, load/assembly checks, maintainer SQL | 2026-07-10 |

Semantics recorded: `count`/`sum` of a missing/non-sequential
collection are 0 (the quantifier empty rule, numerically); `sum`
nil-propagates on any non-numeric item value (a missing addend is a
missing sum, arithmetic's rule — never a silent skip). Neither is
boolean-valued, so neither joins the and/or collapse set. Division
stays unearned — no batch-C test needed it.

`sum.where` classifies `data_law` (the count.where rule verbatim);
`sum.of` is edge identity — `:code-or-shape`, with the edges. The
SUM SQL renders in maintainer.clj against the maintainer's own cond
grammar (a local twin of the storage renderer; a `sum-matching`
protocol op is the named follow-up). An `:int` sum fact must sum an
`:int` column (assembly check) — an integer fact cannot hold a
fractional sum.

## The cross-kind fact DAG

`checks-assembly/check-fact-dag` (closing phase 6's named punt):
nodes are `kind.fact`, edges are same-kind `:over` dependencies plus
— through each aggregate's declared edge — the target facts its
`where` filters and `of` read. DFS refuses cycles naming the path
(`kind.fact → kind.fact → …`); the maintainer's per-write visited
set now only ever truncates chains the assembly proved finite.

## Ownership deviations, flagged (and since resolved)

- `checks.clj` (2 scoped edits): the exactly-one-of gate and the
  per-declaration aggregate-spec check live there and REFUSE any
  `:sum` spec at `defresource` — the deliverable could not land
  without admitting the key at the load boundary.
- `resource.clj` (2 lines): `normalize-derived-spec` canonicalizes
  `:sum :where` values to sets, the `:count` rule verbatim.
- `test/waymark10/definitions_test.clj`: the pre-existing pilot test
  piloted on `weeks`, an unpromoted field — legal only while
  populations went unvalidated; it now splits its population on
  `start_date` (filterable), asserting the same overlay story.
- `waymark10.router-test/well-known-lists-the-kinds` failed at the
  batch's landing on the then-in-flight `approval_request` kind from
  batch B's working tree — resolved when both merged.

# The 9→10 wire, closed: divergences the lineage should remember

Wire "10" is a clean break, not a superset. Everything a waymark9
client must relearn, in one table:

| Surface | waymark9 | waymark10 |
| --- | --- | --- |
| Format marker | `"waymark": "9"` | `"waymark": "10"` |
| Dev principal | `X-Principal-Id` / `-Type` / `-Display` | `x-waymark-principal` / `x-waymark-roles` / `x-waymark-actor-type` |
| Grant credential | minted opaque `wmk_…` bearer token | `X-Waymark-Grant: <grant-id>` — a scope SELECTOR, not a credential; the principal must be the grant's audience |
| Guard names on the wire (warnings, acknowledge, `guard`) | snake (Python identifiers: `calendar_clear`) | kebab (Clojure keywords: `calendar-clear`) — problem KEYS are snake in both; only guard-name VALUES differ |
| Count facts in stored/wire law trees | `["count", …]` expression node | cross-row aggregates are declarative `{:count …}`/`{:sum …}` specs compiled by the maintainer; in-row `(count …)`/`(sum …)` expr quantifiers were earned later (batch C, §19) — v9 and v10 definition fingerprints are still not comparable |
| Collection shape | `data.items` negotiable: `depth=`, `rows=none`, declared columns | envelope-minus-data summaries; `depth=summary` and `rows=none` landed (batch A, §14); declared columns and `depth=expanded` profiles still absent |
| Envelope `links` / `parts` | rendered (embeds, parts groups, placed actions) | links render from declared edges (badges from the row's own facts, capped embeds) and `parts` projects part-scope groups per item (batch A, §14 — the phase-3 punt, closed) |
| Draft GET with nothing stored | 200 empty open draft | 404 (a draft that was never saved does not exist) |
| Create idempotency | key REQUIRED (428) + replay | key honored when present + replay; keyless creates accepted (recorded deviation, phase 10) |
| Webhook failure | skip-and-advance per event | per-subscription `:delivery_policy` (batch F, §16): "fail" (default) parks the cursor and fails the subscription — resume replays; "skip" is waymark9's advance |
| Deferred bulk | job + queued state + artifacts | 202 + job envelope; queued → running with artifacts restored (batch F, §16); lease steal + orphan sweep are the resume |
| Batch refusal | judged every input, full verdict report | first refusal aborts with its index (recorded deviation, phase 7) |
| CLI exit codes | 1 problem/transport · 2 not afforded · 3 confirm · 4 divergence | 0 ok · 1 problem · 2 refused locally · 3 transport; divergence is a loud line on a landed write |

## The final state of the ledger

`make test10`: **301 tests, 2152 assertions**. `make
test-mealplan10`: **23 tests, 217 assertions** — the conformance
walk, the family-week story (now with a recurring recital on the
fake calendar), the batch-E recurrence and RRULE suites, and the
style-invariance pins. Earlier sections quote the suite as it stood
at their phase (31/103 at phase 0, 196/1352 after migrate, and the
batch notes' focused runs); these two numbers are the standing truth.

The git lineage, one commit per landing: phases 0–10 — Phase 0 (the
law speaks Clojure), 1 (the declaration layer), 2 (storage + the
transition algorithm), the registry, 3 (the first envelope), 4a (the
conformance foundation), 5 (the law binds the row's judgment), 4b
(the envelope keeps the declaration's promises), 6 (the maintainer
and the outbox find their consumers), 7 (collections, bulk, batch,
drafts), 8 (mealplan10, the dogfood that earned the engine), 9a
(identity and access), 9b (async and composition), 10 (the clients
close the loop) — then eleven post-parity commits: migrate (§11),
coherence (§12), batch E (§13), batch A (§14), batch G (§15), batch
F (§16), the mealplan10 style rewrite (§15's follow-up), batch B
(§17), batch D (§18), batch C (§19), and this consolidation.

The parity ledger (§9) is kept current through those batches: every
waymark9 server module has a waymark10 home or a named punt. Phase
10 added the client column — waymark9's `client/py.py` +
`client/agent.py` → `waymark10.client` (refusals as data;
PendingConfirmation → the `:confirm!` seam; Divergence → a result
key), `cli/client.py` → `waymark10.cli` (session file; exit-code
table above), `server/static/ui.html` →
`resources/waymark10/ui.html` (scope boundaries above; batch A gave
it parts, links, effort and comboboxes — the collab presence/relay
chrome stays punted with §18).

## Remaining punts, by choice

Everything still open, in one place, so nobody re-discovers a punt
or re-litigates a closure — cross-checked against every batch's own
list:

**Engine-wide**
- Division — never earned, in any version.
- String operations and `where=` unification (8.0's inheritance).
- Spans; `depth=expanded` embed profiles and declared collection
  columns/table hints.
- Ref-label fan-out on target rename (the maintainer does not re-fan
  out; §7's C2 punt stands).
- Pipeline/choreography surface (pipeline.py).

**Grants and identity**
- The grant-projected firehose: SSE, surfaces, openapi and collab
  routes answer a scoped principal with concealment-404 today, never
  a projection; idempotency replays serve the first execution's
  unprojected bytes (the phase-3 render-fn punt, twice re-extended).
  One exception since: the presence stream (§20) IS projected — a
  scoped viewer gets the stream filtered per its row visibility,
  concealed presences byte-level absent.
- Visibility modes: waymark9's `hashed` field mode; create-argument
  modes; per-item grading inside part arrays (redact the array);
  collection `query`/`create` input schemas and facet counts
  unprojected; acceptance enums and deny vars may reflect redacted
  values (guards probe the full row — advertisement equals
  enforcement); the denied-arg 422 runs at the router boundary,
  ahead of invoke's step order (order-distinguishable by a probe).
- Negotiation: approver-edited scope maps; the attenuation ceiling;
  approval extends the named grant (no sibling minting);
  engine-internal approve does not extend.
- Membership: `unbind`; SECRET_FIELDS owner-gating (`bind_token`
  renders to unscoped readers); invite-token uniqueness under race;
  no email/outbox (the token travels out of band); the invited-only
  mode is per engine, not per kind.
- Role uniqueness under race (a create guard, no unique index).
- OIDC browser dance / session cookies / RP-initiated logout.

**Attachments**
- S3/presigned byte storage (bytes stay on `:attachment-dir` disk).
- Blob-write/metadata atomicity (waymark9's log-consumer
  choreography); pre-sha rows refuse re-PUTs 409 (recorded boundary).

**The outside world**
- RRULE outside the profile: `BYSETPOS`, `FREQ=YEARLY`, positional
  BYDAY, BYDAY on MONTHLY, multiple BYMONTHDAYs, `RDATE`, non-Monday
  WKST on multi-week intervals — each skips its event with a warning
  naming it.
- Predecessor: the assembly-time promotion check (the create-time
  refusal holds the line); carry-forward of sibling fields stays the
  apps'.
- Mirror: per-kind discovery cursors; mirror webhooks/change feeds;
  per-field authority (AuthoredMeta); pushing locally-minted rows;
  auto-wiring `with-push` at boot.

**Events, jobs, surfaces, storage**
- Observations: no store-protocol op yet (a Postgres surface; the
  memory twin's consumers drain by hand); no Last-Event-ID replay
  for derivations; ordering across event classes unpromised; the
  maintainer's SUM SQL awaits a `sum-matching` protocol op.
- Judgment blast radius (newly-refused rows) unmeasured; the measure
  runs synchronous (no meter job).
- The webhook deliverer's unification onto consumers-as-API —
  deferred until a third consumer of the pattern exists.
- The memory twin runs no dispatcher: SSE/webhook/observation
  liveness is Postgres-only.
- Surfaces: not fingerprinted, not grantable, no member table hints
  or title template, attention is field equality, no envelope
  back-links.
- OpenAPI: no docs UI; per-kind response models stay structural;
  SSE/attachments/collab/well-known routes undocumented.

**Collab and the generic UI**
- UI collab chrome: the relay/2 client, cursors/selections (not in
  relay/2's wire vocabulary either), presence avatars.
- A plain draft PUT beside a live room broadcasts no frame (clients
  converge at their next stale/sync).
- i18n; the undo toast; attachment byte upload, the batch surface,
  and a docs screen in the generic UI; arrays of objects as a JSON
  textarea; `date-time` inputs as plain text; grant-scoped chrome.

**mealplan10**
- Summary templates have no |join/|len filters; no field defaults
  (rotation/plan defaults land in :on-create); one-of clears to nil,
  not a model default; spans/profiles have no v10 spelling.

**Declaration ergonomics**
- `:one-of`'s missing colocated home was batch G's one named punt
  here; since claimed by the part spelling (`defpart` + `:part` entry
  citation — the spelling law and its ledger live in
  `docs/waymark10-spellings.md`).

Recorded deviations that are choices, not gaps, restated once:
Clojure truthiness in predicates (§2); keyless creates accepted (the
428 waived, §10); batch refusal aborts at the first index (phase 7);
deferred calls skip whole-call idempotency (the job row is the
record) and the reconstructed requester carries no roles; renames
are boot/replay metadata, never fingerprinted law (§11).

The law is a form, the wire is its projection, and every client in
phase 10 proved it can follow the projection without ever being told
what the application is; everything since has widened what the
projection carries without moving that claim.

# 20. Presence — follow-me restored (the where-they-look surface)

waymark9's `/-/presence` comes home: your screen goes where the
followed principal LOOKS, not just where they write. The firehose
already steered follows on committed transitions (phase 10's UI
port, its one loudly-named gap); this section closes the gap with a
presence surface that is EPHEMERAL STATE, never law — no table, no
transitions, no fingerprint, nothing the conformance walker or the
migrate planner will ever meet. `waymark10.server.presence` owns it
whole; the seams elsewhere are deliberately small: a subscription
hook in events.clj, two routes in router.clj, start/stop beside the
dispatcher in engine.clj.

## The registry

One in-process registry per engine, fanned across processes on its
own pg_notify channel (`waymark10_presence`), origin-nonce'd so a
publisher skips its own echo — the collab relay's precedent, resized:
every local report notifies `{origin, pid, entry}` (drops notify
`{origin, pid}`), each process re-asserts its local entries every
`:presence-heartbeat-ms` (default 15s), and a remote entry silent for
three intervals is evicted — a crashed peer's ghosts leave on the
clock, not never. Frames a viewer sees derive from ONE merged-view
diff (freshest entry per principal across origins), so local reports,
remote frames and TTL evictions all speak through the same mouth:
join when a principal appears, move when its self changes, leave when
it goes. A restart forgets everyone; the next heartbeat re-teaches.

## Two reporting doors, both marked by source

- **Implicit** — a per-resource SSE subscription IS presence: the
  engine already knows the principal and the resource, so the
  router's stream hook registers on subscribe and drops on disconnect
  (`source: "stream"`). Streams refcount per self; the last close
  drops the principal (unless a fresh heartbeat still holds it).
- **Explicit** — `POST /api/-/presence {self}` is a heartbeat for
  clients that only hold the firehose, the ported UI's case
  (`source: "heartbeat"`). Three missed heartbeats evict. Selves cap
  at 512 chars (each entry rides one pg_notify payload); an anonymous
  heartbeat answers 422 — it would mark nobody.

## The stream, and its concealment

`GET /api/-/presence` (SSE): a snapshot frame on connect, then
join/move/leave frames `{principal {id, display, type}, self,
source, at}` — no id lines and no replay; presence is liveness, the
snapshot on connect is the truth, and Last-Event-ID means nothing
here. Unlike the firehose (still concealment-404 to a scoped
request), the presence stream is PROJECTED: a scoped principal sees
only presences on selves it could GET — the request's own visibility
closures judge each frame's self, and a filtered frame is byte-level
absent, never narrated (pinned by test: the concealed row's id and
its viewer's name never cross the wire, snapshot or live). An
unscoped viewer sees all. A scoped principal's own reporting is
always accepted: where it looks is its own to say; who gets to watch
is the grant's.

## The UI

The ported page (resources/waymark10/ui.html) reports its own gaze by
explicit heartbeat — on navigation and every 10s (it holds only the
firehose, never per-resource streams). While following, a presence
move for the followed principal navigates the screen, debounced,
never yanking a human out of an open dialog — the same discipline
transition-steering already kept, and transitions still steer as
before (look AND write, two feeds, one follow). Resource screens grow
viewing dots ("● colton is here") repainted as frames arrive; member
envelopes grow a Follow button (the row names a principal — subject
when bound, else the member id). scripts/ui-drive.mjs pins three
checks: heartbeat → dot; the member Follow affordance; follow +
simulated move → navigation.

## Recorded boundaries, each a sentence

- A principal mid-request (an invoke, a GET) is invisible — only held
  streams and explicit heartbeats register, so firehose-only agents
  appear exactly when they choose to say where they look.
- A followed principal's move onto a concealed self reads as
  stillness to a scoped viewer — byte-level absence beats an
  honest-looking narrated departure.
- Presence fan-out is a Postgres surface; other backends stay
  process-local, warned once at start.
- A slow presence subscriber's full queue (256) drops frames; the
  snapshot on reconnect is the whole recovery.
- A self that names no row (a collection screen, the workspace) is
  concealed from scoped viewers: what cannot be GETed row-wise cannot
  be watched.

## The http-kit finding (verified, fixed in events.clj)

The SSE docstring's claim that "send! returns false on a closed
channel" was FALSE for plain-HTTP streaming on http-kit 2.8.0: the
#375 per-request AsyncChannel is not the channel `closeKey` notifies,
so a streaming response's `on-close` never fires and `send!` answers
true into a closed socket forever — every SSE writer thread leaked on
silent disconnects (websockets were fine; collab never saw it).
`events/channel-alive?` now probes the underlying SelectionKey
(reflection, guarded — an unreadable field degrades to the old leak,
never a wrong disconnect) on every heartbeat tick, which is also what
makes "drop presence on disconnect" true.

## Runs

Its own database, the batch-D discipline:

    createdb waymark10_presence_test   # once, on the :5433 container
    cd waymark10 && clojure -M:test --focus waymark10.presence-test

Cross-process joins/moves/leaves, TTL eviction, crashed-peer
eviction, both sources, the scoped stream's byte-level absences, the
never-started engine's 503 — 3 tests, 33 assertions. The full suites
after this landing: `make test10` 316 tests / 2297 assertions,
`make test-mealplan10` 23 / 217; the story drive
(scripts/ui-drive.mjs) 43 checks, no console errors.

# 21. Hand in hand — intent frames, the asking surface, the socket's name

The hand-in-hand charter (`waymark10-hand-in-hand.md`) audited the
story against the tree and named three genuinely new mechanisms, all
small, all owed to the presence precedent: **intent frames** (beat 3
— thinking out loud), **the asking surface** (beat 5 — the wall as a
question addressed to the human), and **identity over the collab
socket** (beat 4 — authors lost to anonymous joins). This section is
their landing. The shared discipline, stated once: EPHEMERAL, NEVER
LAW, CONCEALMENT-PROJECTED — no table, no transitions, no
fingerprint, nothing the walker or the migrate planner will ever
meet. Home: `server/intents.clj` (the first two mechanisms are one
channel), ticket fns in `server/collab.clj`, seams in router and
engine. Tests: `test/waymark10/intents_test.clj` (its own database)
and `collab_ticket_test.clj` (batch D's).

## Intent frames (beat 3)

`waymark10.server.intents` is presence's structural twin: an
in-process registry fanned across processes on its own pg_notify
channel (`waymark10_intents`, origin-nonce'd), re-asserted every
`:intents-heartbeat-ms` (default 15s), remote origins evicted after
three silent intervals, every viewer's frames derived from ONE
merged-view diff. One card per **(principal, action, self)** — the
id is that triple spelled out (`sous:finalize@/api/plans/p1`), so a
re-considered dry-run refreshes the card instead of dealing a second
one.

The doors, the presence pattern again:
- **implicit** — a row-level `?dry_run=1` through the router IS a
  considering: the invoke-action seam reports
  `{status: "considering"}` after a valid dry-run (TTL
  `:intent-ttl-ms`, default 30s — gone in a moment if abandoned);
- **explicit** — `POST /api/-/intents {self, action, question?}` for
  a client surfacing what the router cannot see;
  `POST /api/-/intents/abandon {self, action}` clears the caller's
  own card (the id derives from the request's own principal — nobody
  abandons anyone else's thought).

Resolution is the story's point: a per-registry consumer on the
engine's events dispatcher (the collab regate thread's precedent)
purges every local card whose (self, action) a COMMITTED transition
names — whoever acted, the consideration is moot — and peers hear
the drop. Drops carry their outcome on the wire: `resolved` and
`abandoned` are authoritative (the id purges everywhere), `expired`
is origin-scoped (a fresher copy on another origin — an answer — may
legitimately outlive it).

The stream: `GET /api/-/intents` (SSE) — a snapshot frame on
connect, then `open`/`update`/`close` frames (`close` names its
outcome). No id lines, no replay; an intent is liveness. Concealment
reuses `presence/self-visible?` verbatim: a scoped viewer sees an
intent iff it could GET the self it names, and a filtered frame is
byte-level absent — pinned by the same never-names-the-concealed-row
test presence carries.

## The asking surface (beat 5)

The doc's own hint held: an agent's pending gate IS an intent that
lingers until answered — the same channel, a longer leash. When a
real (non-dry-run) invoke refuses `warning-required`, the router's
seam reports the refusal as `{status: "asking"}` before rethrowing:
the guard's own sentence as `question`, the warnings and the E1
acknowledge names riding along, TTL `:intent-ask-ttl-ms` (default 10
minutes). On Priya's screen the wall arrives as the card the story
describes — the guard's sentence, the agent's pending intent, her
decision.

Her yes is `POST /api/-/intents/answer {id, names?}` (names default
to the ask's own): the card restamps `answered` with `{by, names,
at}` and the update fans to every watcher — including the asking
agent. **The answer only delivers; it never overrides.** The agent's
retry still passes the guard through the existing
acknowledge-by-name header — one acknowledgement path, exactly as
E1 built it — and the retry's committed transition resolves the card
through the same consumer every act feeds. Answering a concealed ask
is the same 404 as answering none; answering a considering is a 409
(nothing was asked).

The confirm gate is the recorded asymmetry: it lives in clients (the
server never refuses on it), so its ask arrives through the explicit
door — a client's `confirm!` hook posts the consequence sentence as
the question and the human's answer rides back the same way.

## Identity over the collab socket (beat 4)

The regression: a browser WebSocket cannot send
`x-waymark-principal` or a bearer header, so collab joins resolved
to `t/anonymous` and per-field `:authors` lost their names — four
hands, no signatures. The fix follows the identity boundary rather
than widening it: `POST /api/-/collab-ticket` (an ordinary
authenticated request — wrap-identity, suspension gate and all)
mints a short-lived (`:collab-ticket-ttl-ms`, default 60s), ONE-TIME
ticket; the join URL presents it as `?ticket=`. Query param over a
first-frame handshake, recorded why: the join must know its
principal BEFORE the upgrade — the state frame and the presence
join carry the author, and a bad ticket refuses 401 as plain HTTP,
never a half-open socket. Redemption consumes the nonce
(`swap-vals!` — two racing joins admit one); tickets live in an
atom on the engine, ephemeral, never law.

Recorded boundaries: a ticket redeems only on the process that
minted it (fan-out earns its keep for frames, not credentials — a
sticky LB or a re-mint is the multi-process answer); the suspension
gate runs at the mint, so a member suspended inside the ticket's 60
seconds joins once more. A join with neither ticket nor header stays
anonymous — exactly the door that existed before, pinned by test.

## Recorded boundaries, each a sentence

- Only row-level dry-runs report intents — bulk/batch dry-runs and
  direct `inv/invoke!` calls are invisible (the router's seams are
  the doors; presence's mid-request invisibility, again). (§23 has
  since opened the create/bulk/batch doors — one card per door, full
  rehearsals only; direct `inv/` calls stay invisible.)
- Intent reporting is best-effort: a failed report warns on *err*
  and the invoke answers untouched — company must never cost the
  work.
- Resolution matches (self, action), not the actor: an act by anyone
  moots everyone's consideration of it.
- Freshest-entry merging trusts one wall clock across origins (the
  presence surface's own assumption).
- Intents fan-out is a Postgres surface; other backends stay
  process-local, warned once at start.
- The UI speaks none of this yet — intent cards and the answer
  button on the approver's screen are the story's polish pass, named
  future work beside batch D's cursor chrome.

## Runs

Its own database, the presence discipline:

    createdb waymark10_intents_test   # once, on the :5433 container
    cd waymark10 && clojure -M:test --focus waymark10.intents-test \
      --focus waymark10.collab-ticket-test

Cross-process considerings, TTL expiry, cross-process abandon, the
lingering ask and its answer, crashed-peer eviction, the dry-run and
warning-wall doors, E1 release resolving the card, the scoped
stream's byte-level absences, the 503; the minted ticket naming the
roster/presence/authors, spent and expired tickets' 401, and the
unchanged header/anonymous joins — 4 tests, 77 assertions. The full
suite after this landing: 317 tests / 2358 assertions, 0 failures.

# 22. Batch H — the spelling catches up with the design conversation

The intake disbursement transaction was the first declaration to be
written the way its designers actually talk — "a transaction goes to
review with its fields set", "cancelling a COMPLETED transaction is a
different sentence from cancelling a draft", "the amount is measured
by its value type" — and the flat `:actions`/`:schema` spelling made
every one of those sentences a translation exercise. Batch H closes
the gap. The discipline is unchanged and non-negotiable: **two
spellings, one law**. Every new spelling is sugar that desugars at the
declaration gate (`normalize-resource`, before anything compiles or
fingerprints) into the SAME plain map the split spelling writes by
hand, so the fingerprint cannot tell the spellings apart.
`ideal_declaration_test` is the acceptance: the disbursement
declaration in the new spelling, its fully desugared twin, normalized
maps equal, hashes byte-identical, and the whole lifecycle walked over
the wire — draft → review → done, kick-backs, counts, the acknowledge
protocol, the cancel cascade.

## Typed field words (`waymark10.declare`)

`one-of`, `date`, `flag`, `quantity`, `money`, `percent`, `prose`,
`ref`, `measured-by` — plain functions returning the exact malli form
the inline spelling writes (`(one-of :dollars :pct)` IS
`[:enum "dollars" "pct"]`). Entry properties (a ref's `{:kind …}`, the
prose/money/percent `:x-display`) and editor policy (a prose draft)
ride as namespaced METADATA: a keyword cannot carry meta, so a word
with properties wraps its form in a one-element vector the `:fields`
reader unwraps — the form the schema compiles is identical either
way. Outside `:fields`, spell the entry properties yourself; the
words' metadata is `:fields` vocabulary.

Money and percent forced `:decimal` into the schema registry — the
exact-BigDecimal type the storage projection, the checks, and the
collection grammar already spoke but nobody had registered (the
`E.lit("0.02")` lesson: never floats; a double fails validation).
`{:min … :max …}` are honored exactly; wire decode coerces JSON
numbers and strings through `bigdec`. And the first `:decimal` INPUT
field found a real engine gap: the input digest's canonical bytes
refuse raw BigDecimals by design, so `body-digest` now re-spells
decimals as the wire's `{"dec" "…"}` nodes before hashing
(`wire/dec-nodes`) — the one lawful door the refusal message always
named. No existing digest changes (a decimal would have thrown).

## `measured-by` — the discriminated amount, scoped honestly

`(measured-by :value_type {:dollars (money :usd) :shares (quantity)
:pct (percent)})` wants a sibling-dispatched union: a Pct amount under
a `:dollars` value type should be unrepresentable. **Recorded gap:**
malli validates one entry's VALUE — a `:multi` dispatching on a
sibling cannot live in the entry's form, and wrapping the whole data
schema breaks every `:map` introspection. The closest check-based
equivalent landed instead: the stored form is the arms' one shared
scalar family (`:decimal`; mixed families refuse at the word), the arm
map rides as `x-display` advertisement for the client, and the
cross-field law lands as a generated code guard on the group's editor
(`resource/measured-guard`, judging `[amount value_type]`, validating
the input-else-stored amount against the input-else-stored measure's
arm schema). Refused at the write, not unrepresentable — a true
`:multi`-entry spelling is a recorded demand. The guard's check hashes
by its canonical printed form over the sorted arm map, and the builder
is memoized so both spellings of one editor hold one value.

## `:flow` — the machine as rows

    [[:draft :submit_for_review :ready_for_review
      {:requires [fields-complete] :undo :kick_back}]
     …]

Rows of `[from action to opts?]` normalize into today's `:actions`
map. Rules, each a refusal at the gate:

- Rows sharing an action name union their origins; they must agree on
  one destination and on everything except `:confirm` (per-origin
  `:requires` has no spelling — a recorded demand; align the rows or
  split the action).
- `:requires` maps to `:guards`; `:args [[field (word …)] …]` builds
  the required-input schema (`:input` stays legal, exclusive with
  `:args`); `:record`, `:display`, `:edit`, `:place`, `:handler`,
  `:emits`, `:waives`, `:unless` pass through; an unknown opt refuses
  by name (the silent-drift rule).
- Every non-self-loop row declares its safety story: `:undo` (the
  honest reverse), `:confirm` (with its consequence), `:one-way`
  (acknowledged), or an explicit `:safety`. The minted safety is
  `{:idempotent true :reversible (from :undo) :confirm (from
  :confirm)}` — a flow row is a lifecycle step, idempotent by
  declaration.
- When `:states` is not spelled, the rows ARE the machine: initial
  first, then first appearance in row order, then unreached terminals.

**Per-origin consequence — the one render capability.** An action
reachable from several states may cost something different from each,
and the engine had one consequence slot per action. `t/safety` now
accepts `:consequence` as a `{from-state sentence}` map (every
origin's sentence written — a state without one is a blind confirm
from that state); flow rows' `:confirm` sentences assemble it
(identical sentences collapse to the plain string — one meaning, one
spelling). `render/action-entry` resolves the sentence by the row's
CURRENT state — the one moment the origin is known — into
`display.description`, exactly where a string consequence already
rode. Consequence sentences are advertisement, never law: the
fingerprint is untouched. Recorded limit: consequence sentences do not
interpolate (`{prepared}` in a consequence renders literally) — the
landed intake sentences are static.

## `:undo` pointers — the honest reverse, by name

`:undo :kick_back` declares reversibility by naming the reverse. At
defresource time, once every action is normalized, the pointer is
VERIFIED: the named action exists, departs from this edge's
destination (`(:to a) ∈ (:from undo)`), and lands exactly where this
edge began (`(:from a) = #{(:to undo)}` — so a multi-origin action has
no single honest reverse, and says so). A verified pointer stamps
`:safety :reversible true` — the one key the render layer reads — and
is stripped; a lying pointer is a definition error at the declaration
site. Bare `:reversible true` stays legal (check-reversible still
guards it graph-wide); the pointer is the spelling that cannot rot.
Works on flow rows and on plain `:actions` entries alike.

## `defguard` — sentence-first guards

    (defguard blocking-items-reviewed
      (refuse "Every compliance-class checklist item is reviewed —
               {open_blocking} remain.")
      '(zero? (var :open_blocking)))

`waymark10.declare/defguard` (the CODE-guard `g/defguard` is
untouched) defs the plain expression-guard map `g/expr` builds — pure
data, so the def'd and inline spellings are one value. Validation runs
at the def line (the defaction pattern); a sentence with no law
refuses ("a guard is a verdict"). Three authored conveniences desugar
at the gate, before `g/expr` validates — they are defguard spellings,
NOT expression-vocabulary nodes (the evaluator and the wire encoding
never see them):

- `(var :fact)` → `(data :fact)` — in a guard, a bare fact name reads
  the stored document.
- `(zero? e)` → `(= 0 e)`.
- `(present? :a :b)` → `(and (is-set (data :a)) (is-set (data :b)))`.

Sentence placeholders the law's own `(input …)` reads do not cover
land as `(data placeholder)` `:vars` garnish — the same garnish the
split spelling writes by hand, rendered by the existing
`render-reason` machinery (no new interpolation). `(warn "…"
:acknowledge-by-name)` is severity `:warning`; the flag documents the
standing E1 protocol (warnings are always acknowledged by guard name)
and changes nothing.

**Named demands, not fakes:** `(sole-preparer? (var :actor)
:checklist_items)` needs a query over the owned items' transition
logs, `(pushed? :beacon)` needs mirror push state — engine facts that
do not exist. The four-eyes and already-pushed guards stay OUT of the
landed acceptance declaration; they are the checklist batch's and the
Beacon batch's first demands, recorded in the log below.

## `:fields` — lifecycle groups

    :fields {:at-create […] :while-open […] :support […]
             :when {:initial_subscription [[:risk_rating (ref …)]]}
             :open #{:draft :ready_for_review}}

Normalizes to `:schema` + `:create-schema` + generated editors + the
`:when` create gates (one home: declaring `:schema`/`:create-schema`
beside `:fields` refuses). Group semantics, each a sentence:

- `:at-create` fields are create input and fixed after — required in
  both schemas, written by no generated editor.
- `:while-open` fields get a generated editor (`update_fields`) in
  each OPEN state. `:open` (default `#{initial}`) names where
  authoring still happens — the machine cannot infer where authoring
  ends, so the declaration says.
- `:support` fields' editor (`update_support`) exists in every
  non-terminal state, carrying the union of the group's prose draft
  policy (`{:shared true :live true}` — the update_recipe concept).
- `:when {value rows}` fields are optional everywhere plus a
  conditional-required create gate (a pure expr guard: `(or (not=
  (input :type) "initial_subscription") (is-set (input
  :risk_rating)))`) keyed on the one `:at-create` one-of field that
  offers every `:when` value. Recorded scope: `:when` fields are
  create-time only — no generated editor writes them.
- A top-level `:derived` COUNT fact with no entry gets its
  `[:maybe :int]` entry appended (a count fact is an `:int` by law);
  any other derived fact still declares its own shape.

Generated editors: all-optional `[:maybe …]` inputs, `:edit
{:prefill …}` (so the fence rides — an Edit implies If-Match), the
`overwrite` safety, and a handler whose whole behavior is
`resource/apply-field-edits` (write exactly the keys sent), its
identity the canonical printed form, its builder memoized so both
spellings hold one value. **Recorded limit — the multi-state
self-loop:** an action has one `:to`, so "editable in draft AND
ready_for_review" cannot be one action; several open states mean
several editors (`update_fields_in_<state>`; a single state keeps the
bare name). A `:to :stay` spelling would touch the transition
algorithm and is left as a named demand.

## The owns map

`:owns {:checklist_items {:kind :checklist_item :on {:cancel
:cancel}}}` normalizes to today's vector spelling, `:via` defaulting
to `<kind>_id` (the ref back at the parent), and every aggregate that
names the EDGE (`{:count {:owns :checklist_items}}`) renames onto the
child kind the engine's aggregate grammar speaks. Alongside it,
`normalize-derived-spec` canonicalizes aggregate `:where` values one
step further: scalars become one-value sets (`{:blocking true}` ≡
`{:blocking #{true}}`) and keyword tokens become their names
(`#{:reviewed}` ≡ `#{"reviewed"}` — stored data is JSON and never
holds a keyword; the maintainer's SQL always compared them equal, so
now the fingerprint does too).

## What the acceptance declaration could not say, recorded

- `{:not :reviewed}` count-wheres have no spelling — the landed law
  names the open states positively (`#{:pending :prepared}`), which is
  also the truth cancelled items demanded.
- `:overdue?` is out: a derived fact cannot read the machine `:state`,
  and `past?` (a clock-relative comparison word) lands only with the
  declaration that can carry it — both named demands.
- Summary templates still read `{data.…}` roots; `{fund/name}`-style
  ref-label paths in summaries are unspelled (the `:label`-maintained
  name field is the current door).
- Field names keep the token rule (`:blocking?`/`:end_of_period?` →
  `:blocking`/`:end_of_period` — a promoted column is a snake token).

## The proof and the runs

    cd waymark10 && clojure -M:test \
      --focus waymark10.batch-h-defguard-test \
      --focus waymark10.batch-h-flow-test \
      --focus waymark10.batch-h-fields-test \
      --focus waymark10.ideal-declaration-test

Per-delta invariance in the batch-G pattern (sugared vs split →
identical normalized maps AND identical fingerprint hashes), behavior
where the engine grew capability (per-origin consequence renders per
state; undo verification rejects the lying pointer three ways;
measured-by refuses the mismatched amount over the wire; the
conditional requirement fires only for the matching type; the E1
acknowledge walk), and the disbursement acceptance end to end
against the Postgres test engine. Full suite after this landing: 339
tests / 2503 assertions, 0 failures (the pre-existing events-test
dispatcher/table-drop flake under some namespace orderings is
unrelated and passes focused); mealplan10 stays green.

## Vocabulary additions log (batch H)

| Node | Demanded by | Date |
| --- | --- | --- |
| `:decimal` (schema type, not an expr node) | disbursement.amount / the fee percents — exact money, never floats | 2026-07-10 |
| `one-of`/`date`/`flag`/`quantity`/`money`/`percent`/`prose`/`ref` (declare words, not expr nodes) | the disbursement `:fields` groups | 2026-07-10 |
| `measured-by` (declare word + generated editor check) | disbursement.amount measured by value_type | 2026-07-10 |
| `zero?`, `present?`, guard-scope `(var …)` (defguard authored spellings, desugared at the def line — the expr vocabulary itself is unchanged) | fields-complete / blocking-items-reviewed / all-items-reviewed | 2026-07-10 |
| `:flow` rows, `:undo` pointers, `:fields` groups, owns map (declaration sugar) | the disbursement lifecycle | 2026-07-10 |
| per-origin `:consequence` map (safety + render) | cancel's three sentences | 2026-07-10 |
| `wire/dec-nodes` at the input digest | the first `:decimal` input body | 2026-07-10 |
| NAMED DEMANDS (not landed): `sole-preparer?` (checklist batch), `pushed?` (Beacon/mirror batch), `{:not …}` wheres, `past?` + state-reading derived facts (`:overdue?`), per-origin `:requires`, `:to :stay` multi-state self-loops, consequence interpolation, `{ref/label}` summary paths | the ideal disbursement spelling, §22 | 2026-07-10 |

# 23. Dry-run parity — the rehearsal reaches every door

waymark9's dry-run story was richer than v10's, and this section
closes the gap. The survey first, because it corrects the folklore:
v10's SINGLE-RESOURCE door was already at full waymark9 parity —
`invoke-in-tx!` loads without the FOR UPDATE lock, demands and reads
no idempotency key, resolves the row's own law revision before
judging, walks state gate / fence / schema / guards identically to
the real path, and exits at step 10 with `{:valid true}` and any
pending warnings as data. What lagged: the CREATE door ignored
`dry_run` outright (the batch-A UI grew a `dryRunnable` guard
precisely because the old Check button really fired creates), the
BULK and BATCH doors executed regardless, the client's rule 5 was
offered but not enforced at the confirm gate, the CLI had no
`--dry-run`, and the UI's blur judge had been muted to schema-only.
Everything below is engine/router/client behavior — no declaration,
no fingerprint, no expr moved, and the mealplan10 style-invariance
suite pins that the law held still.

## The create tiers (waymark9 `_create_entry`, reproduced)

`?dry_run=1` on `POST /api/{plural}` (engine: `create!` with
`:dry-run`) answers in two tiers. No declared create guards: schema
validation IS the answer — no session is even opened, `:on-create`
must not fire (its side effects are the whole reason this tier
touches nothing), no idempotency key is demanded, read, or stored,
nothing inserts, and the wire answers `{"valid": true}`. Declared
create guards: judged exactly as the real path — one shared reduce
(`create-guard-pass`) so the two judgments cannot drift — with
refuse-severity throwing its 409 and pending warnings riding the
body as `{"valid": true, "warnings": […]}`. Acknowledged names pass,
as ever. The recorded deviation stands unchanged from §10: v10 never
demanded the 428 on keyless creates, and the rehearsal doesn't
either.

## Bulk and batch verdicts

`?dry_run=1` on the bulk and batch routes judges every item through
the SAME per-item algorithm (`invoke-in-tx!` with `:dry-run` riding
along) and answers `{"valid": <all-ok>, "verdicts": […]}` — verdicts
self-keyed on the bulk door (`{"self", "verdict", "reason"?}`),
index-keyed on the batch door, warnings riding each ok verdict when
guards warn. Nothing locks, nothing commits, no key is demanded or
recorded. Three recorded choices:

- **waymark9 never had a bulk dry-run** (its `bulk()` took no flag);
  v10 adds one in the image of 9's BATCH dry-run, self-keyed because
  bulk is N resources.
- **9's batch dry-run executed under a doomed transaction** —
  handlers ran, then rollback — so verdict i saw input i-1's
  effects. v10's iron rule is that a rehearsal never fires a
  handler, so each input is judged independently against the row as
  it stands. In exchange the batch rehearsal reports EVERY verdict
  (9's own dry-run virtue), where the real atomic batch still aborts
  at its first refusal (§7's recorded deviation).
- **A dry-run never defers.** Deferral is an execution strategy and
  a job row is an effect; an over-threshold bulk rehearsal judges
  inline, still capped by `:max-items`.

## The partial rehearsal — judged when answerable

The phase-10 blur story now has three chapters, and each verdict
speaks at the earliest moment it can be true. Chapter one (§10):
dry-run on demand — the Check button — because a guard 409
mid-typing would only nag. Chapter two (batch A, §14): blur-time
dry-run, muted to schema 422s inline for exactly that reason.
Chapter three (this section): the nag was never the dry-run, it was
judging questions the form hadn't answered yet — so the wire grows
`?dry_run=partial`, the judged-when-answerable mode:

- **Schema**: only the entries PRESENT in the payload are validated —
  type/range/format errors (and unknown keys) refuse per provided
  field, and a missing required field is not an error, because
  absence mid-composition is not a claim. Silence on unprovided
  fields is a pinned obligation.
- **Guards**: only the guard leaves whose entire `:judges` set the
  provided keys cover are evaluated — the same field metadata
  acceptance folding reads. A leaf still waiting on a field is named
  in `awaiting`, never failed; a judged leaf refuses or warns
  exactly as the full loop (one shared grading, `deny-outcome`).
  Row-only leaves (no `:judges`) drop entirely — the envelope's
  available/unavailable already told that truth — and an `:any`
  composite carries no `:judges` of its own, so it waits with the
  rest (recorded: an OR cannot honestly be judged one arm at a
  time).
- **Everything else is the full rehearsal's discipline**: state gate,
  fence, and concealment judge as ever; no lock, no idempotency, no
  transition, no handler, nothing committed. The 200 answers
  `{"valid": true, "judged": […], "awaiting": […]}` (both arrays
  always present in partial mode — the mode is recognizable on the
  wire); refusals answer the usual problem shapes.

Partial composes with every door: it rides `create!` (provided
entries, covered create-guard leaves) and, per item, the bulk/batch
fan-outs — for free, because they all run the one per-item
algorithm.

## The UI blur judge, one coherent behavior

On focusout the form sends ONLY the fields the user has touched
(`?dry_run=partial`, a touched-set beside the draft code's dirty
set) and paints what came back: provided-field errors inline under
their fields, a judged guard's own sentence on a quiet verdict line
("✗ <reason>"), and "✓ so far" when everything answerable passed —
never a modal. The Check button and submit remain the FULL
rehearsal, and both disarm the pending blur timer so the two doors
never speak over each other. `dryRunnable` now admits create and
bulk hrefs (the engine honors them since this section; batch remains
the one door the page does not drive), and Check renders a bulk
rehearsal's refusing verdicts row by row.

## Intents at the doors, recorded

The §21 boundary — "only row-level dry-runs report" — is superseded:
every FULL dry-run door now reports a considering through the same
best-effort seam (a failed report warns on `*err*`; company never
costs the work). One card per door: the single and batch doors name
the row's self; the create and bulk doors name the COLLECTION self,
because no row exists yet (create) and a card per id would deal a
hand per Check (bulk). Two consequences, accepted for a 30-second
shadow and recorded: a collection-self card expires on the TTL
rather than resolving (the resolution consumer matches row selves),
and a scoped viewer never sees it (`self-visible?` conceals non-row
selves). The PARTIAL door is deliberately mute on the intents
channel — it fires at typing cadence, and a card per keystroke would
make company cost the work; the full rehearsal (Check, the client's
confirm gate, the CLI's `--dry-run`) is the considering door.

## Client rule 5 enforced; the CLI's `--dry-run`

The client's rule 5 was a public `dry-run` fn and a docstring; now
the confirm gate runs it. When `act!` meets `safety.confirm=true`
and a `:confirm!` seam exists, the input is dry-run FIRST: a refusal
returns as the problem and the human is never asked to approve what
cannot land; pending warnings ride the confirm payload's `:warnings`
so the yes is an informed one. No callback still refuses locally
without a wire call (the pinned rule-2 behavior). The CLI gains
`act <href> <action> --dry-run` — the verdict, not the act: ✓ with
warnings on 0, the problem (or a bulk door's refusing verdicts) on
1; creates ride `act` as ever, so waymark9's separate `create
--dry-run` needs no twin.

## The obligations, pinned

`conformance_test` (engine level): dry-run of valid input answers
valid, version unchanged, no transition appended; a dry-run neither
demands, consumes, nor RECORDS an idempotency key (a real invoke
with the same key executes fresh, then replays as ever); the create
tiers (nothing minted, `:on-create` never fires — a counting hook
proves it — warnings as data, acknowledged names pass); the partial
obligations (silence on unprovided fields, provided-field errors
keyed only by provided fields, covered leaves judged now, uncovered
leaves named `awaiting`). `router_test` (wire): the create door's
200/verdict/422 and the partial door's shape. `bulk_batch_test`:
mixed verdicts with the guard's own sentence per item, no row moved,
no job minted on an over-threshold rehearsal, nothing stored under a
presented key, the batch rehearsal's full verdict list, and the 428
still guarding the real batch. `client_test`/`cli_test`: the confirm
gate's pre-validation (a doomed input never earns a prompt) and the
shell's exit codes. `intents_test`: the create and bulk doors'
considering cards on the live stream.

## Runs

    cd waymark10 && clojure -M:test --focus waymark10.conformance-test \
      --focus waymark10.router-test --focus waymark10.bulk-batch-test \
      --focus waymark10.client-test --focus waymark10.cli-test \
      --focus waymark10.intents-test
    cd mealplan10 && clojure -M:test   # style invariance: the law held still

## Vocabulary / decisions log (§23)

| Decision | Why | Date |
| --- | --- | --- |
| `?dry_run=partial` (wire mode; engine `:dry-run :partial`) | the blur judge should judge only what the form has answered — judged-when-answerable | 2026-07-10 |
| `judged`/`awaiting` (partial verdict body), `verdicts` (bulk/batch dry-run body) | the rehearsal names what it judged and what still waits | 2026-07-10 |
| create dry-run tiers = waymark9 `_create_entry` | schema-only without create guards; guards judged with warnings as data | 2026-07-10 |
| bulk dry-run exists (9 had none); batch dry-run judges independently (9 rolled back real executions) | the iron rule: a rehearsal never fires a handler | 2026-07-10 |
| dry-runs never defer, never touch idempotency | a job is an effect; a key is a record | 2026-07-10 |
| intents: one card per door, collection self for create/bulk, partial mode mute | company must never cost the work | 2026-07-10 |
| client confirm gate dry-runs before the `:confirm!` seam | rule 5 enforced, not remembered | 2026-07-10 |
| blur = partial, Check/submit = full, timers disarmed across doors | one coherent blur behavior | 2026-07-10 |

# 24. The pantry-prices parity batch — the dogfood demands, recorded

mealplan9's final era (pantry prices, 2026-07-11..13) is the demanding
declaration for this batch: porting it to mealplan10 at full parity —
no experience regressions recorded as deviations — required growing
the framework where v9 had spellings v10 lacked. Each act below is one
commit, one test namespace, one vocabulary-doc row.

## ctx `:invoke` — the handler's cross-write door

waymark9's `Ctx.invoke`, ported. A handler (or `:on-create` hook) may
write OTHER rows through the very transaction it runs in, each inner
write walking the full per-item algorithm (idempotency, state, guards,
tamper, log). The demanding declaration: `ingredient.absorb` must
rematch the DUPLICATE's products (an input-carrying write on another
row's children) and retire the duplicate — the owns cascade cannot say
that (it carries no input and fans to the acting row's own children).

- `make-ctx` carries `:invoke` ONLY in `:invoke` mode — probe and
  dry-run ctxs hold no pen, and guard evaluation receives a ctx with
  the door removed (guards judge; handlers write).
- Inner results ride the outer result as `:inner-writes`;
  `after-write!` drains them FIRST, so the outer response's rollups
  tell the post-inner truth (absorb answers with the survivor's
  repointed products already counted).
- Inner transitions wear the outer correlation id; a natural replay of
  the outer skips inner re-execution wholesale (the first execution's
  writes are the record).
- The owns cascade's pagination now survives SELF-LOOP child actions
  (a cascaded child that stays in the `:from` filter): a seen set +
  growing fetch window replaces the leave-the-filter assumption.

Proof: `waymark10/test/waymark10/ctx_invoke_test.clj` (6 tests — the
merge world: inner writes land, maintenance truth, correlation ids,
natural replay, dry-run writes nothing, 201-child self-loop cascade).

## Vocabulary / decisions log (§24)

| Decision | Why | Date |
| --- | --- | --- |
| ctx `:invoke` (handlers + on-create only; guards and rehearsals never see it) | absorb's cascade writes another row's children with input — the owns cascade cannot say that | 2026-07-15 |
| `:inner-writes` drain before the outer's own after-write! pass | the response's rollups tell the post-inner truth | 2026-07-15 |
| cascade! seen-set + growing window | a self-loop cascade target must terminate past the 200-row page | 2026-07-15 |
