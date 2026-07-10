# The live layer — intents, the asking surface, and a name on the socket

The design for the three mechanisms the hand-in-hand audit names as
genuinely new: **intent frames** (beat 3), **the asking surface**
(beat 5), and **identity over the collab socket** (beat 4). Companion
documents: `waymark10-hand-in-hand.md` (the charter this exists to
serve), `server/presence.clj` (the precedent all three follow), and
the family-week story that will one day execute against what is
designed here.

**Epistemic status.** A design, not a report of built things. Every
decision below was read against the working tree at `mealplan8`
(presence, collab, events, invoke, client, cli, ui.html) and against
waymark9's `collab.py`/`router.py`/`engine.py` for ancestral shapes.
Where the tree and the ancestors disagree, the charter decides.

## The one discipline, restated

Presence set the house rules for everything live, and this layer
inherits them verbatim rather than re-deriving them:

- **Ephemeral, never law.** No table, no transitions, no fingerprint.
  A restart forgets everything; the next report re-teaches it.
- **Concealment-projected.** A scoped viewer sees a frame iff it
  could GET the self the frame names — `presence/self-visible?`, the
  same closure, reused. A concealed frame is byte-level absent, never
  narrated.
- **Cross-process via its own pg_notify channel**, origin-nonce'd,
  one dedicated LISTEN connection, heartbeat re-assertion, three
  missed intervals evict. On any other backend the registry stays
  process-local, warned once (recorded scope, presence's own).
- **TTL-evicted.** Liveness is the only promise; the snapshot on
  connect is the recovery.

The law machinery is untouched by all three mechanisms. The one place
the live layer and the law touch is deliberate and one-directional:
the live layer *listens* to the transition dispatcher (the collab
regate consumer's precedent) so that an intent can resolve into its
act — the law never listens back.

## 1 — One channel: an ask is an intent that lingers

**Decision: one channel.** Intents and asks are one registry
(`server/intents.clj`, new), one pg_notify channel
(`waymark10_intents`), one SSE stream (`GET /api/-/intents`), one
frame vocabulary. An ask *is* an intent whose `kind` is `asking` and
whose TTL is minutes instead of seconds; a considering is the same
entry gone in a breath. The audit already suspected this ("likely the
same channel"), and the follower's screen confirms it: Priya watches
one stream and sees the thinking shade into the asking — two streams
would make her client re-invent the join. It is a *separate* registry
from presence (different lifecycle: presence is one merged entry per
principal, intents are a set of items each with its own clock) but
the same file-level discipline, near-verbatim.

**Two doors, presence's own pattern, both marked by `:source`:**

- *implicit* — a **dry-run IS a considering**: the router's
  invoke-action handler, on `dry_run=1` from any named principal,
  reports `{kind "considering", source "dry-run"}` to the registry
  after the engine answers. The agent that pre-validates before
  acting (client rule 5) broadcasts its thinking for free, exactly as
  a per-resource SSE subscription IS presence. The router owns this
  seam; `invoke.clj` is never touched — the live layer does not reach
  into the transition algorithm.
- *explicit* — `POST /api/-/intents` is a report: a considering with
  the agent's own one-line note, or an ask (`kind "asking"`) carrying
  a question. `DELETE /api/-/intents/{id}` withdraws (abandoned means
  gone, not answered-no).

**The frame vocabulary.** One entry, wire-shaped:

```
{ id:         server-minted uuid
  principal:  {id, type, display}          — the asker, resolved identity
  self:       "/api/…"                     — ≤512 chars, presence's cap
  action:     "finalize"                   — the declared action name
  kind:       "considering" | "asking"
  source:     "dry-run" | "report"
  gate:       "warning" | "confirm" | null — asking only: which wall
  question:   "Sous wants to finalize. …"  — ≤240 chars (the ask-in-one-
                                             sentence cap, approval_request's)
  warnings:   [{name, reason}]             — asking/warning only: the 409's
                                             own pruned warnings, the guard's
                                             sentence verbatim
  note:       "Tacos → Tuesday"            — ≤240; the agent's claim,
                                             display only, never judged
  options:    ["approve", "decline"]       — fixed vocabulary, v1
  at, expires: iso instants }
```

Stream events: `snapshot` on connect (presence's discipline, no id
lines, no replay), then `intent` (new/refreshed), `answered`
(`{id, answer, by {id,type,display}, acknowledge [names]?, note?}`),
`acted` (`{id}` — the registry's dispatcher consumer matched a landed
transition on the same principal+self+action: the considering
resolved into the act, beat 3's exact sentence), `withdrawn`, and
`expired`. TTLs: a considering expires in one presence heartbeat
interval (15s default), refreshed by each repeated dry-run; an ask
defaults to 5 minutes, caller-settable, capped at 30 — the card on
Priya's screen carries the clock.

**What must never ride it.** Input bodies — an action's input may
carry private prose, and the intent stream fans wider than the row's
writers. Not input digests either (useless to a human, and a digest
of a low-entropy input is an oracle). Not draft contents, not grant
internals, not field values of any sort. What DOES ride is only text
the wire already speaks publicly at this self: the action name, the
guard's rendered reason (identical in advertisement and refusal —
beat 5's own requirement), the declaration's consequence sentence,
the row's rendered summary if the reporter includes one in `note`,
and the agent's ≤240-char claim. Concealment then governs the whole
frame: `self-visible?` judges each frame's self per viewer, and a
frame the viewer's grant could not GET is byte-level absent — what
you cannot read you cannot watch being considered. A report whose
entry exceeds the notify payload ceiling (7000 bytes, collab's
recorded number) refuses 422: an ask must fit in one breath, which is
the charter's security model, not a transport limit wearing a
costume.

## 2 — The answer path: the wire releases the agent, the law re-judges it

**Decision: wire push over the same channel; the answer is
conversation, the transition is the record.**

The mechanics, end to end. Sous hits the warning wall (409,
`warning-refused`, the guard's sentence in the body). Sous — not the
server — posts the ask: `{kind "asking", gate "warning", self,
action, question, warnings}` (the client library composes the
question from the 409's own words; nothing is re-judged or
re-worded). Priya's screen, holding `GET /api/-/intents`, paints the
card: the guard's sentence, the agent's name, approve/decline, the
clock. She answers: `POST /api/-/intents/{id}/answer {answer:
"approve"}`. The registry stamps her resolved principal on the
answer, fans `answered` cross-process, and evicts the entry on its
normal clock. Sous, blocked on the same SSE stream filtered to its
own ask (`?intent={id}` — one query param, frames for other intents
byte-level absent), receives the release and **re-invokes with the
headers**: `Waymark-Acknowledge: <names>` for a warning wall, the
confirmed retry for a confirm gate. The transition lands with
`:acknowledged` on it, exactly as a terminal-prompted acknowledge
does today.

Why this and not the alternatives, each weighed:

- *Polling* (the agent re-GETs its ask) — wrong shape: the house
  already owns SSE with heartbeat-probed disconnects on both server
  surfaces and a frame reader in the client (`watch!`); polling adds
  latency to the one moment the story calls a conversation.
- *The ask as a short-lived resource* — honest about audit but wrong
  about weight: a resource means a kind, a law, transitions, a
  fingerprint, concealment rules, collection grammar — the whole
  skeleton — for a question that lives five minutes. The
  approval_request already IS the durable ask where durability is the
  point (beat 1, beat 7); minting a second ask-kind would blur the
  one distinction this codebase never blurs.

**The audit question, answered honestly.** Does an ANSWERED confirm
need a durable trace? The trace that matters already lands in law
twice: the transition records the acknowledged guard names, and the
grant records `approved_by` — so every acknowledged act is
answerable to the human who granted the agent its power. What is NOT
recorded is which human said yes at this particular gate. Recorded
boundary, a sentence: the answerer's identity is ephemeral; the
answer authorizes nothing (the agent still needs its grant, its
guards, its headers — the law re-judges the release at the invoke,
so a forged or replayed answer buys an attacker exactly one more
409). If a future version wants the gate-answerer in the record, the
seam is one field — an `:acknowledged-by` stamp on the transition,
server-verified — and it is deliberately not built now, because it
would require the *server* to broker the answer into the invoke, and
the smallest honest mechanism keeps the agent's re-invoke an
ordinary, fully-judged write.

**Who may answer:** any *named* principal other than the asker
(`someone-else-decides`, mirrored ephemerally — the registry refuses
self-answers and anonymous answers with one 422 each). The frame
carries the answerer's `type`; the *client* library treats only a
human's approve as satisfying a confirm gate — rule 2 ("a human must
approve") stays a client-enforced Part IV rule, exactly where it
lives today.

## 3 — Identity over the socket: a ticket, minted where identity already resolves

**Decision: a short-lived, single-use ticket minted per socket, spent
as a query param on the upgrade.**

Browsers cannot set headers on a WebSocket handshake — this is the
whole anonymous-join limitation, and waymark9 answered it with
`?agent-token=` ("WS upgrades can't set headers", engine.py's own
comment). v10's version, smaller and honest about OIDC:

- `POST /api/-/ws-ticket` (authenticated like any request — dev
  headers today, `Authorization: Bearer` when the engine configures
  :oidc, `X-Waymark-Grant` included) answers `{ticket, expires_at}`.
  The ticket is an opaque nonce bound to the *resolved* principal and
  visibility, 60-second TTL, single-use.
- `wrap-identity` grows one clause: a `?ticket=` on the request
  resolves to the minted principal+visibility *before* the dev-header
  fallback. That is the entire integration — `collab/join` is
  untouched and simply stops receiving `t/anonymous`; every
  pre-upgrade refusal (no live draft: 404; concealed row: 404) keeps
  answering as plain HTTP problems, which first-frame auth would have
  demoted to WS close codes after letting a scoped-out upgrade
  through.
- Cross-process: tickets fan over a pg_notify channel exactly as
  presence entries do (mint notifies `{ticket-hash, principal, vis,
  expires}`; spend notifies the burn), so a mint on one process
  honors an upgrade on another. Single-use is best-effort across a
  race window of one notify round-trip; a 60-second opaque nonce that
  buys only what the principal already had is an acceptable recorded
  boundary. Non-Postgres backends keep tickets process-local, warned
  once.

Why not the alternatives: putting the *credential itself* in the URL
(v9's token, or `?as=priya`) leaks a long-lived secret into access
logs and proxies — a ticket leaks only a spent nonce. First-frame
auth avoids storage but moves identity past the upgrade, after the
router's concealment checks have already run blind, and turns every
auth refusal into a close code no problem boundary renders. The
ticket is also *reusable beyond collab*: EventSource cannot set
headers either, so a scoped browser's presence/intents/events streams
can present the same ticket the same way — one mechanism, recorded as
the door OIDC-authenticated browsers will use for every live surface.

With a principal on the socket, two committed collab features finish
themselves: `author-of` attributes fields to real names (the
per-paragraph authorship beat 4 promises), and **cursor frames**
become meaningful — one new broadcast-only frame type in collab.clj,
`{type: "cursor", field, anchor, head}` relayed like presence frames
(never persisted, never in the draft document), painted by ui.html as
the second cursor in the prose field. The named batch-D punt closes
as a side effect of knowing who is holding the cursor.

## 4 — The agent's side: `--ask` means wait, and waiting has a clock

**Decision: the client library gains asking seams that compose with
`act!` unchanged; the CLI gains `--ask`, which turns both human walls
into wire questions with a timeout.**

`client.clj` — three additions, no changes to `act!`'s contract:

- `(intents/report! session {…})`, `(intents/withdraw! session id)` —
  the explicit door.
- `(intents/await-answer session id {:timeout-ms …})` — tails
  `GET /api/-/intents?intent={id}` (the `watch!` SSE reader,
  generalized by one event name) and returns the `answered` /
  `expired` / `withdrawn` frame as data. Refusals are data, house
  style — never exceptions swallowed into nil.
- `(intents/asking-confirm! session {:timeout-ms …})` — returns a
  function usable as `act!`'s `:confirm!` callback: it posts the ask
  with the consequence sentence as the question, blocks on
  `await-answer`, and answers truthy iff a *human* approved in time.
  The symmetric warning helper wraps an `{:warnings … :acknowledge!}`
  result: post the ask carrying the 409's warnings, await, and on
  approve call the result's own `(acknowledge!)` — so nothing is
  acknowledged that a human did not see, rule 6's sentence with the
  human moved to the other end of a wire.

`cli.clj` — the UX, spelled:

```
$ waymark act /api/plans/w21 finalize --ask --ask-timeout 300
the server warns:
  calendar_conflict: the recital overlaps Thursday
asking — the question is on the board (expires 18:42) …
released by priya (approve) at 18:39
plan /api/plans/w21 · state=planned · v7
```

`--ask` changes exactly two behaviors: a confirm gate posts the ask
and waits instead of prompting the terminal, and a warning 409 posts
the ask and waits instead of prompting to acknowledge. A decline
prints the decliner and their note and exits 2 (refused locally — the
human seam said no); a timeout withdraws the ask and exits 2 ("asked;
nobody answered by 18:42"); transport loss while waiting exits 3.
`--yes` still bypasses both walls as the recorded human approval;
`--ask --yes` is a usage error (two humans is one too many). Exit
codes unchanged — beat 5 does not add a verdict, it moves where the
verdict comes from.

## 5 — The relationship screen (beat 7), one paragraph

A surface declaration for the half that is law, a UI composition for
the half that is deliberately not — and this split is the design, not
a compromise. The surface (`:anchor :member`, members over the
declared grant/approval_request edges, served at
`/api/surfaces/relationship/{member-id}`) composes what the law
relates: the member envelope, the grants with their expiry clocks,
the week's asks approved/denied/expired — durable, replayable,
concealment-governed, everything a surface already knows how to
promise. What a surface must never contain is exactly what the rest
of this document builds: presence, live intents, and per-actor
transition history are ephemera and ledger, not kinds, and a surface
that smuggled them in would put the creature inside the skeleton.
So ui.html renders the surface and overlays three live strips it
already knows how to paint: the presence dot, the intents stream
filtered to this principal (pending asks, live), and the follow
ledger filtered to this actor — plus the Follow button it already
draws on member envelopes. One screen, one URL, and the line between
what survives a restart and what honestly should not stays visible
to anyone who reads the declaration.

## The work breakdown

Ordered; sizes S/M/L; one owner-file per item; parallel tracks named.

| # | Item | Size | Files | Depends on |
|---|------|------|-------|------------|
| 1 | **The intents registry**: entries, two doors (report!/answer!/withdraw!), TTL sweep, `waymark10_intents` notify fan, subscribe/snapshot/SSE handler, the dispatcher consumer that resolves intents into acts, concealment via `self-visible?` | M | `server/intents.clj` (new; presence.clj is the template, near-verbatim) | — |
| 2 | **Routes + the implicit door**: GET/POST `/api/-/intents`, POST `…/{id}/answer`, DELETE `…/{id}`; the dry-run considering report in invoke-action; registry start/stop in the runtime | S | `server/router.clj`, `server/engine.clj` | 1 |
| 3 | **The ws-ticket**: mint endpoint, notify-fanned single-use store, the `?ticket=` clause in wrap-identity | S | `server/tickets.clj` (new), `server/router.clj` | — (parallel with 1–2) |
| 4 | **Client asking seams + CLI `--ask`**: report!/await-answer/asking-confirm!, the warning wrapper; `--ask`/`--ask-timeout`, the waiting printout, exit paths | M | `client.clj`, `cli.clj` | 1, 2 |
| 5 | **The UI's live layer**: intents SSE consumption; considering ghosts on followed screens; the addressed ask card with approve/decline; the live knock (approval_request creates off the firehose → a card, closing beat 1's gap); ticket-authenticated collab join; cursor paint | M/L | `resources/waymark10/ui.html` | 1–3 |
| 6 | **Cursor frames on collab**: accept + broadcast `{type "cursor"}`, never persisted; participants already carry real authors via the ticket | S | `server/collab.clj` | 3 |
| 7 | **The relationship surface**: the declaration + the member-page overlay strips | S | app-level surface declaration, `ui.html` | 1, 5 |
| 8 | **The admission test**: the two-client drive of beats 3/4/5 (below), joining `make test10` as a standing story | M/L | `test/waymark10/live_layer_test.clj` (new) | all |

Parallelizes as three tracks: **A** (1→2→4), **B** (3→6), **C** (5
needs A+B's wire shapes but its card/ghost chrome can start against
the frame vocabulary on paper); 7 and 8 close serially.

## The admission-test hooks

Each mechanism must be provable by a two-client drive — one session
as Priya (SSE + HTTP), one as Sous (the client library) — against a
started engine on Postgres:

**Intent frames (beat 3).**
- Sous dry-runs `assign_meal`; Priya's `/api/-/intents` stream
  delivers a `considering` frame naming Sous, the self, the action —
  no refresh, within one heartbeat.
- Sous acts; the stream delivers `acted` for that intent — the
  considering resolved, not merely expired.
- Sous dry-runs and walks away; after the TTL a fresh snapshot omits
  the entry (gone in a moment if abandoned).
- A third session scoped by a grant that cannot GET the self sees
  byte-level nothing — no frame, no absence narration.

**The asking surface (beat 5).**
- Sous `act!`s finalize, hits the warning 409, posts the ask, blocks;
  Priya's stream delivers `{kind "asking"}` whose `warnings` carry
  the guard's sentence *verbatim* (string-equal to the 409's).
- Priya answers approve; Sous's blocked call releases, re-invokes
  with `Waymark-Acknowledge`, and the landed transition's
  `:acknowledged` names the guard — the wire's release and the law's
  record agree.
- Priya answering her own ask refuses; an anonymous answer refuses;
  a decline releases Sous into exit-2 with her note; an unanswered
  ask expires and the CLI exits 2 on the clock.

**Identity over the socket (beat 4).**
- Sous mints a ticket, joins the collab room with `?ticket=`; the
  state frame's participants name `sous` with type `agent` — not
  anonymous.
- Sous sets a field; Priya's frame carries `author: sous`, and the
  draft document's per-field author survives to the consumed act.
- A spent ticket refuses the second upgrade 401 *before* the upgrade
  (a plain HTTP problem); an expired ticket likewise.
- Priya's cursor frame arrives at Sous attributed and is absent from
  the persisted draft document (ephemeral, provably).

Green on all three, woven into the eight-beat story drive, is the
charter's own bar: the soul back, as a test that runs.

## What this document is not

It is not new law, and it must never become law by accretion: no
mechanism above writes a table, mints a kind, or stamps a
fingerprint, and the only durable marks the whole layer leaves are
the ones the law already makes — the transition with its
acknowledged names, the grant with its approver. The recorded
boundaries, gathered: the gate-answerer's identity is ephemeral (the
`:acknowledged-by` transition stamp is the named future seam);
ticket single-use is best-effort across one notify round-trip;
intents, tickets, and their fan-out are Postgres surfaces,
process-local elsewhere; the agent's `note` is its claim, displayed
and never judged; and an ask that cannot fit one pg_notify payload
does not get to be an ask, because scope-in-one-breath was never a
transport constraint — it is beat 1's four seconds, kept.
