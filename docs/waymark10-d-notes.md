# Collab relay/2 + concurrent text editing — the section for waymark10-design.md

The maintainer folds this in post-merge. It closes the phase-9b
collab punts (staleness rejection, acks, presence, regate, the
cross-worker bus) and the coherence notes' "collab live relay still
process-local" boundary, and it adds what waymark9 never had:
server-authoritative OT for prose fields, proved generatively.

## Live collab: waymark-relay/2 and concurrent text (batch D)

Home: `server/collab.clj` (protocol, OT core, cross-process relay)
and `server/drafts.clj` (the draft document shape). Tests:
`test/waymark10/batch_d_ot_test.clj` (the pure OT proof),
`batch_d_collab_test.clj` (relay/2 on the wire, one engine),
`batch_d_relay_test.clj` (two engine instances over one database —
the faithful two-process simulation).

### The draft document (drafts.clj owns the shape)

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

### The protocol

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

### The OT core (in-house, no deps)

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

### The one write path, cross-process safe

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

### Regate

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

### Punts, each named

- UI chrome: ui.html (another batch's file) still speaks nothing of
  collab — cursors, presence avatars, and its relay/2 client are
  future work. Any client MUST now send the field's base rev on set
  (a rev-less set is base 0 and goes stale after the first write).
- Cursor positions / selections on the wire: not in relay/2's
  vocabulary here (waymark9 didn't carry them either).
- A plain draft PUT beside a live room bumps revs but broadcasts no
  frame — live clients converge at their next stale/sync.
- Presence rosters dedupe by principal id; the same principal on two
  sockets of one origin leaves the roster on the first close and is
  corrected by the next heartbeat.
- `stale` on a prose edit answers the field's full current value,
  not a patch — the resync is one frame.

### The parity ledger row

| waymark9 module | waymark10 home | Scope notes / named punts |
| --- | --- | --- |
| collab.py (waymark-relay/2) | `server/collab.clj` + `server/drafts.clj` | full relay/2 (state, acks, stale, presence, regate) + server-authoritative OT for prose (waymark9 named character-merge a different protocol token and never built it); cross-process relay on waymark10_collab with origin nonces + heartbeat-evicted merged rosters; revs/authors/op logs persist in the draft document; UI chrome punted |

The coherence notes' rows update: "collab live relay still
process-local (punt)" is CLOSED — drop it from the standing
cross-cutting punt list; the drafts row's "per-field revs/authors
unported" note is closed too.

### Integration notes for the maintainer

- `test/waymark10/collab_test.clj` (phase 9b) asserts the OLD
  relay/1 semantics in three places and now fails exactly there:
  the sender-hears-no-echo assertion (relay/2 acks the sender by
  design), the room-global rev (revs are per field now), and the old
  sync shape. 17 of its 20 assertions still pass. It is superseded
  by `batch_d_collab_test.clj` — delete it (or rewrite its three
  assertions) post-merge; batch D does not own the file.
- No engine/router/store changes were needed: collab reads the
  dispatcher from the engine's `:runtime` atom when present and
  carries everything else in the draft document.
- New engine opt (read, not declared): `:collab-heartbeat-ms`
  (default 5000). Tests assoc it onto the engine after construction;
  if it should survive `engine/engine`'s select-keys whitelist, that
  one-line addition belongs to engine.clj's owner.

### Focused runs (batch D's own database — waymark10_d_test)

    cd waymark10
    clojure -Sdeps '{:aliases {:x {:extra-paths ["test"] :extra-deps {org.clojure/test.check {:mvn/version "1.1.1"}}}}}' \
      -M:x -e "(require '[clojure.test :as t] 'waymark10.batch-d-ot-test 'waymark10.batch-d-collab-test 'waymark10.batch-d-relay-test) (t/run-tests 'waymark10.batch-d-ot-test 'waymark10.batch-d-collab-test 'waymark10.batch-d-relay-test)"

    # or, once the shared tree is green, through kaocha:
    clojure -M:test --focus waymark10.batch-d-ot-test \
      --focus waymark10.batch-d-collab-test --focus waymark10.batch-d-relay-test

11 tests, 123 assertions (300 TP1 trials + 250 convergence trials
per run inside them), 0 failures. `WAYMARK10_D_DSN` overrides the
default `jdbc:postgresql://localhost:5433/waymark10_d_test?user=ckopsa`.
