# Multi-process coherence — the section for waymark10-design.md

The maintainer folds this in post-merge. It retires the bus.py punt
("single-process engines") and updates the parity-ledger rows at the
bottom.

## Multi-process coherence (phase 11)

Home: `server/coherence.clj`. Tests: `test/waymark10/coherence_test.clj`
(two engine instances — separate pools, separate registry atoms — over
one database in one JVM: the faithful two-process simulation).

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

### Law-slot refresh rides the outbox

Definition transitions are ordinary logged transitions (kind
`definition`), so the events dispatcher already delivers them to every
process. The refresh consumer subscribes to exactly those, debounces
bursts (a promote's effect logs several transitions — retire,
supersede, adopt — and one refresh at the end of the burst covers them
all; default 1s), and calls `definitions/boot-revise!`: on UNCHANGED
code boot-revise! is idempotent — hash-equal against the stored
current row, it adopts revisions, holds, pilots, and overlays from the
store, writing nothing. definitions.clj is untouched.

The concurrency reading of boot-revise! (the phase-11 finding):

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

### Singleton roles by advisory lock

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
word. Two roles exist: `webhooks-deliverer` and `clock-sweeper`.

Tested: exactly one deliverer delivers (event-id counted at an
in-process receiver), the cursor persists past delivery so a takeover
replays nothing, the survivor acquires within the retry interval and
delivery resumes; exactly one sweeper ever starts while a due clock
flip lands (version untouched — maintenance, not a write).

### What stays process-local, recorded

- **SSE subscribers** — correct: each process serves its own
  connections off the shared log; every dispatcher reads every
  transition.
- **Collab rooms** — cross-process live relay NOT built. Edits persist
  through the shared draft rows, so late joiners (and joiners on the
  other process) converge on the persisted values; live frames do not
  cross processes — two clients on different processes editing one
  draft see each other only at sync/rejoin. The remaining
  relay/2-adjacent punt, named so nobody re-discovers it.
- **Idempotency and natural replay** — DB-anchored, already safe.
- **Jobs** — the worker claims leases (claim-or-steal on expiry),
  already safe; it stays a per-process start, NOT a role.

### The two-process wire walk

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

### Parity-ledger row updates

| waymark9 module | waymark10 home | Scope notes / named punts |
| --- | --- | --- |
| bus.py | `server/coherence.clj` | law refresh rides the outbox (guarded boot-revise!, never mints); deliverer + sweeper elected by advisory lock (WM10 keyspace); SSE per-process off the shared log; collab live relay still process-local (punt) |

And the collab.py row's scope note gains: "… bus **punted** →
cross-process live relay still punted; draft-row persistence is the
convergence path (coherence, phase 11)".

The standing cross-cutting punt list drops "the cross-process bus" and
gains "cross-process collab relay (drafts converge, frames don't)".

### Integration (engine/start!, applied by the maintainer)

Coherence owns the deliverer and the sweeper — `coherence/start!`
REPLACES the direct `webhooks/start-deliverer!` and
`maintainer/start-sweeper!` calls. In `engine/start!`'s runtime map,
drop the `:sweeper` and `:webhooks` entries and add

    :coherence (coherence/start! eng dispatcher {})

and in `engine/stop!`, before stopping the dispatcher,

    (some-> coherence coherence/stop!)

(requiring `[waymark10.server.coherence :as coherence]`; the stop!
destructuring gains `:coherence` and loses `:sweeper`/`:webhooks`).
The jobs worker and mirror discovery entries stay as they are.
