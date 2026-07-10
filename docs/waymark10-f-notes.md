# Batch F — engine odds-and-ends (the ledger update)

The maintainer folds this into waymark10-design.md post-merge. Seven
deliverables, each with its own test namespace
(`test/waymark10/batch_f_*.clj`); focused runs against the
`waymark10_f_test` database (`WAYMARK10_TEST_DSN`).

## What landed

### 1. GIN indexes on vocab arrays (phase 7's named punt, closed)

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

### 2. Jobs completeness (waymark9's lifecycle pieces, restored)

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

Recorded: `engine/start!` does not yet start the orphan sweeper (that
file is another batch's; the role is one
`(jobs/start-orphan-sweeper! eng {})` in the `:runtime` map when the
maintainer folds it in). Two existing assertions
(`jobs_test.clj:106`, `bulk_batch_test.clj:178`) were updated
`"running"` → `"queued"` — the minimal tracking of the restored
state; nothing else in either suite moved.

### 3. Webhooks: `:revoked` and the per-subscription delivery policy

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

### 4. Attachments: sha256, duplicate detection, the purge sweep

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
  Like the orphan sweeper, wiring it into `engine/start!` is the
  maintainer's one-liner.

### 5. Consumers-as-API (`server/consumers.clj`, new)

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

### 6. OpenAPI: response schemas, security, surfaces

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

### 7. The in-memory Storage twin (`store/memory.clj`, new)

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

28 tests, 196 assertions, 0 failures at time of writing. Regression
(same DSN, focused): jobs/bulk-batch/webhooks (15 tests, 155),
phase9a/phase9b/collections (20, 226), invoke/migrate/fingerprint
(16, 112), conformance/drafts (12, 191) — all green.
