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

mealplan10's own recorded punts: previous_plan declared but
predecessor-unresolved (E7 has no v10 spelling); links declared and
assembly-checked but unrendered (envelope `:links` stays the phase-3
punt); part-scope "parts" envelope rendering unbuilt (the placed
actions carry `:place`, the key pre-binding waits for the parts
surface); WeekBoard/spans/profiles have no v10 spelling; the real
iCal adapter parses VEVENTs without RRULE expansion (a recurring
series contributes its DTSTART only — revisit when the family's
recurring events matter); v10 summary templates have no |join/|len
filters; no field defaults (rotation/plan defaults land in
:on-create).

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
(waymark9's log-consumer choreography).

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
artifacts are unported; deferred calls skip whole-call idempotency
(the job row is the record).

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

**OpenAPI** (`server/openapi.clj`). `GET /api/openapi.json` — a
derived 3.1 overlay: per kind the collection (query parameters from
the filter grammar), create, get, and every action's act path
(+bulk/batch/draft) with the REAL input schemas from the
declarations, descriptions from display + the machine + safety, and
the problem responses referenced once in `components.responses`.
Enough for /docs-style tooling; recorded: response body schemas are
stubs, SSE/attachments/surfaces/collab/well-known are undocumented,
no securitySchemes, scoped requests 404.

## The parity ledger — waymark9's server inventory → waymark10

| waymark9 module | waymark10 home | Scope notes / named punts |
| --- | --- | --- |
| invoke.py | `server/invoke.clj` | full transition algorithm; bulk/batch/bulk-item |
| definitions.py | `server/definitions.clj` | promote/pilot/withdraw; population grammar check punted |
| router.py | `server/router.clj` | linear reitit router, problem + identity boundaries |
| render.py | `server/render.clj` | envelope links still the phase-3 punt; depth=expanded profiles punted |
| storage/ | `server/store.clj` + `store/postgres.clj` | one backend (Postgres); memory twin punted |
| grants.py | `server/grants.clj` | kinds/ids/actions only — field/argument visibility MODES punted; negotiation machine punted |
| derived.py (maintainer) | `server/maintainer.clj` | counts, clocks, backfill; derivation-class events punted |
| external.py (Mirror) | `server/mirror.clj` | pull-through + discovery only — external PUSH / write-back beyond pull is a punt |
| engine.py | `server/engine.clj` | boot, runtime lifecycle (dispatcher/sweeper/discovery/webhooks/jobs) |
| collab.py | `server/collab.clj` | LWW relay; relay/2 staleness rejection, presence, regate, bus **punted** |
| events.py | `server/events.clj` | transitions only; derivation event class punted |
| attachments.py | `server/attachments.clj` | bytes on disk; purge/S3/sha256 punted |
| oidc.py | `server/oidc.clj` | bearer RS256 verification; browser dance/sessions punted |
| drafts.py | `server/drafts.clj` | per-field revs/authors unported (collab holds revs room-local) |
| subscriptions.py | `server/webhooks.clj` | fail-the-subscription instead of skip-and-advance; revoked state punted |
| migrate.py | **punt** | no schema diff/migration planner; ensure-kind! is additive-only |
| bus.py | **punt** | single-process engines; rooms and dispatcher are process-local |
| members.py | `server/members.clj` | auto-provision on first sight; invite→bind flow punted |
| judgment.py | `server/judgment.clj` | stored-tree overlay; derived-law overlay punted |
| jobs.py | `server/jobs.clj` | queued state, artifacts, orphan sweep punted (lease steal resumes) |
| problems.py | `server/problems.clj` | RFC 9457 projection |
| openapi.py | `server/openapi.clj` | overlay only; no docs UI, response schemas stubbed |
| owns.py | `invoke.clj` cascade + `maintainer.clj` rollups | rollup_is subsumed by count facts |
| consumers.py | **punt** | consumers-as-API (registered log consumers) unbuilt; webhooks cover the external case |
| pipeline.py | **punt** | no pipeline/choreography surface |
| roles.py | `server/roles.clj` | registry + uniqueness-under-race punt |
| surface.py | `server/surface.clj` | fingerprint/grant/links/table-hints punted (see above) |
| idempotency.py | `invoke.clj` + `waymark10_idempotency` | byte-identical replay; replays predate grant projection (recorded) |

Standing cross-cutting punts, restated so nobody re-discovers them:
RRULE/recurrence, spans, the predecessor resolver (period chaining),
rows=none/depth= collection modes, GIN indexes for vocab arrays, and
the grant-projected SSE/surface/openapi routes.

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
inverse action; the button is there, the toast isn't).

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

# The 9→10 wire, closed: divergences the lineage should remember

Wire "10" is a clean break, not a superset. Everything a waymark9
client must relearn, in one table:

| Surface | waymark9 | waymark10 |
| --- | --- | --- |
| Format marker | `"waymark": "9"` | `"waymark": "10"` |
| Dev principal | `X-Principal-Id` / `-Type` / `-Display` | `x-waymark-principal` / `x-waymark-roles` / `x-waymark-actor-type` |
| Grant credential | minted opaque `wmk_…` bearer token | `X-Waymark-Grant: <grant-id>` — a scope SELECTOR, not a credential; the principal must be the grant's audience |
| Guard names on the wire (warnings, acknowledge, `guard`) | snake (Python identifiers: `calendar_clear`) | kebab (Clojure keywords: `calendar-clear`) — problem KEYS are snake in both; only guard-name VALUES differ |
| Count facts in stored/wire law trees | `["count", …]` expression node | no count node (unearned): a declarative `{:count {:owns … :where …}}` spec compiled by the maintainer — v9 and v10 definition fingerprints are not comparable |
| Collection shape | `data.items` negotiable: `depth=`, `rows=none`, declared columns | fixed envelope-minus-data summaries; no depth param, no rows=none (named punt) |
| Envelope `links` / `parts` | rendered (embeds, parts groups, placed actions) | `links` always `{}`, no parts surface (the standing phase-3 punt) |
| Draft GET with nothing stored | 200 empty open draft | 404 (a draft that was never saved does not exist) |
| Create idempotency | key REQUIRED (428) + replay | key honored when present + replay; keyless creates accepted (recorded deviation, phase 10) |
| Webhook failure | skip-and-advance per event | the subscription FAILS with the cursor parked; resume replays |
| Deferred bulk | job + queued state + artifacts | 202 + job envelope; no queue state, lease-steal is the resume |
| Batch refusal | judged every input, full verdict report | first refusal aborts with its index (recorded deviation, phase 7) |
| CLI exit codes | 1 problem/transport · 2 not afforded · 3 confirm · 4 divergence | 0 ok · 1 problem · 2 refused locally · 3 transport; divergence is a loud line on a landed write |

## The final state of the ledger

`make test10`: **192 tests, 1302 assertions** (phase 9b's 179/1165
plus the phase-10 client, CLI and UI suites). `make
test-mealplan10`: **11 tests, 145 assertions** — the conformance
walk and the family-week story, untouched by phase 10 (the one
engine change, create-key honoring, is additive).

The parity ledger (§9) stands as written: every waymark9 server
module has a waymark10 home or a named punt, and phase 10 adds the
client column — waymark9's `client/py.py` + `client/agent.py` →
`waymark10.client` (refusals as data; PendingConfirmation → the
`:confirm!` seam; Divergence → a result key), `cli/client.py` →
`waymark10.cli` (session file; exit-code table above),
`server/static/ui.html` → `resources/waymark10/ui.html` (scope
boundaries above; approvals/presence/relay/parts screens punted
with their servers). Standing cross-cutting punts, restated one
last time so nobody re-discovers them: RRULE/recurrence, spans, the
predecessor resolver, `rows=none`/`depth=` collection modes, GIN
indexes for vocab arrays, grant-projected SSE/surface/openapi
routes, the grant negotiation machine and ApprovalRequest flow, the
cross-process bus, and the migration planner. The law is a form,
the wire is its projection, and every client in this phase proved
it can follow the projection without ever being told what the
application is.
