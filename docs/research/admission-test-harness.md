# The admission-test harness — design

The eight-beat story of `docs/waymark10-hand-in-hand.md` ("The
admission test"), executable as a two-client drive: one headless
chromium as **Priya**, one `waymark10.client` session as **Sous**,
walking all eight beats end to end. This document is the harness
design only — no code was changed. It answers five questions:
topology, the beat/assertion table (runnable-today vs blocked), time
control, flake discipline, and where it lives / how it gates.

Sources read: `waymark10/scripts/ui-drive.mjs` (the existing CDP
harness), `mealplan10/test/mealplan10/family_week_test.clj` (the
ring-driven story), `waymark10/src/waymark10/client.clj` +
`cli.clj`, `test/waymark10/coherence_test.clj`, `presence_test.clj`,
`batch_d_collab_test.clj`, `server/engine.clj`, `server/grants.clj`,
`server/presence.clj`. Companion:
`docs/research/live-layer-design.md` (the intents/asks/ws-ticket
design) — this harness's stage-2 assertions are written against that
document's "admission-test hooks" section, so the two land
hand-in-glove: the live layer's `live_layer_test.clj` proves each
mechanism at framework level in `make test10`; *this* test weaves
them into the eight-beat story in `make test-mealplan10`.

---

## 1. Topology: one JVM conducts everything

**Recommendation: one test JVM is the conductor.** It hosts the
engine (a real `engine/start!` on an ephemeral port, so SSE,
presence, and collab are live), drives Sous through
`waymark10.client` in-process over real HTTP against localhost, and
drives Priya's chromium **directly over CDP from the same JVM** —
`java.net.http`'s WebSocket client (already the pattern in
`batch_d_collab_test.clj`'s `ws-connect`) speaking the same
`Runtime.evaluate` frames `ui-drive.mjs` sends. No `.mjs` sidecar,
no IPC seam, no second orchestrator.

Why this and not node-orchestrated with Sous over HTTP:

- **Ordered beats are free.** The story is a strict sequence of
  "Sous acts → Priya's screen reacts → assert → next beat." With one
  conductor, every beat is sequential Clojure; the only concurrency
  left is the engine's own cadences (dispatcher, presence heartbeat),
  which bounded waits absorb. Node-orchestrated, every Sous act is a
  `clojure -M:cli` subprocess — a multi-second JVM boot *per act*
  (blowing the runtime budget by itself), exit codes instead of data,
  and no access to the client's seams (`:confirm!`, `:acknowledge!`,
  the warnings-as-data result the whole beat-5 assertion is about).
- **The clock must live where the test lives.** Beat 8 needs the
  engine's `:now-fn` jumped mid-story (§3). Only an in-process engine
  gives the test the clock atom; a node orchestrator would need a
  test-only clock route — a hole we'd be drilling in the server for
  the harness's convenience.
- **Sous's duality is the design center, and it works today.**
  `client/connect` with the real `http-request-fn` gives Sous
  everything the story needs from a separate client session:
  `act!` with rule-1/rule-2 local refusals, `dry-run`, the
  warning-409 `:acknowledge!` protocol, `watch!` (real SSE — the ring
  transport can't stream, which is exactly why the engine must be
  `start!`ed, not handler-only), and grant headers via `:grant`.
  For beat 4, Sous's collab socket is `java.net.http` WebSocket with
  the `x-waymark-principal` header — the identity that the *browser*
  socket cannot send (the WS-identity gap is Priya's side only).
- **One assertion vocabulary.** Every check is a `clojure.test`
  `is` with a beat-named message; the CDP helper's `wait-for` throws
  a named timeout (§4). ui-drive's `ok`/`waitFor` style ports almost
  verbatim because its check payloads are *JS strings* — the same
  strings ride `Runtime.evaluate` no matter which language sends
  them.

What the JVM owns: dropping/reseeding the database, booting the
engine with injected clock + short cadences, seeding the world
through the API as priya (family-week style), spawning chromium
(`--headless=new --remote-debugging-port=0 --no-sandbox
--user-data-dir=<tmp>` — ui-drive's flags, but fixture-owned and
killed in `finally`), the CDP session, Sous's client session and
collab socket, and the beat sequence.

`ui-drive.mjs` is **not** replaced: it remains the single-actor
generic-UI regression drive. The admission test is a different gate
(two actors, story semantics), sharing the recipes, not the file.

### The flake this design must not inherit (diagnosed)

The ui-drive report's one flake was in the stale-check section
(`draft stale-rejection recovery (relay/2)`, ui-drive.mjs:411–452).
The mechanism is the **dispatcher-drain regate race**, and
`batch_d_collab_test.clj` documents the same race explicitly
(lines 156–160):

> `;; let the dispatcher drain the create's transition before any
> room subscribes — a late drain would regate a room over a
> transition older than it` — guarded there by `Thread/sleep 500`.

ui-drive has no such guard: it creates `staleMeal` and POSTs
`accept` (a version-moving transition) over HTTP, then immediately
opens the recipe dialog, whose relay/2 socket joins the collab room.
The events dispatcher polls (default 2s); if it drains the accept's
transition *after* the room subscribed, the room is **regated** —
`base_version` bumps and every field rev bumps mid-scenario. Then
either (a) the first save's `set rev 0` answers `stale` instead of
`ack`, starving the "first save acked over the socket" wait, or
(b) the regate lands between the first ack and the deliberately
stale second save, so the recovery text is `"v1 typed in this ui"`
(the regate's truth) instead of `"the external truth"` — the check
fails on value. Usually the navigation + dialog waits outlast the
2s poll, so it mostly passes — a classic timing flake.

**The harness rule that closes it:** never subscribe to (or assert
against) a collab room until the *ticker has narrated the last
version-moving act on that row* — an observable drain wait (the
dispatcher already feeds the browser's ticker, and the JVM side can
watch the firehose or the dispatcher cursor directly), never a
sleep. The same rule generalizes: **every cross-surface handoff
waits on an observable, not a duration** (§4).

---

## 2. The beat-by-beat assertion table

Fixed story clock (§3): boot at `T₀ = 2026-07-14T18:12:00Z`
(Tuesday evening). Seed, family-week §-by-§ over the API as priya:
rotation created+activated; the eight meals accepted; a **draft plan
for the week of the 21st** (`{start_date 2026-07-21, weeks 1}` —
the story's "draft next week"); the Piano recital seeded on the
FakeEvents feed at `2026-07-23` (the Thursday of *that* week),
`mirror/discover!` run, `has_conflicts` true on the plan.

Sous's principal: `{:id "sous" :type :agent :display "Sous"}`.

Legend: **TODAY** = assertable now, stage 1. **BLOCKED(x)** = waits
on one of the three live-layer mechanisms — **IF** intent frames,
**AS** asking surface, **WS** identity over the collab socket — each
with its stage-2 assertion pinned to the exact hook
`live-layer-design.md` specifies (`/api/-/intents` frames, the
`asking` card's string-equal guard sentence, `?ticket=` joins).

| Beat | Sous's act (client.clj) | Priya's observable (CDP) | Wire/DB proof | Status |
|---|---|---|---|---|
| **1 The knock** | fresh session, *no grant*: `create!` on `approval_requests` — `{task "Draft the week of the 21st — assign_meal, finalize on plan; accept, update_recipe on meal", scope [{kind "plan" actions ["assign_meal" "finalize"]} {kind "meal" actions ["accept" "update_recipe"]}]}` → 201, envelope's `expires_at` = `T₀+24h` (`grants.clj` on-create stamps the default TTL — "until tomorrow, 6:12 pm", literally) | (a) **live arrival**: `#ticker`/`#feed` narrates the `approval_request` birth without a reload (creates are transitions; the firehose already carries them); (b) Priya navigates to the ask envelope; **Approve** renders with the confirm consequence `"The requester's grant gains exactly the scope shown, immediately."`; she confirms | grant row `grant-<ask-id>` state `accepted`, audience `sous`, `expires_at = T₀+24h`; Sous reads `:data :grant_id` off the ask envelope, reconnects with `:grant`; scoped GET plan → 200, scoped GET rotations → **404** (concealment) | **TODAY** — except the *addressed ask card* appearing unprompted on her screen (today she navigates; the ticker line is the honest stage-1 proxy). Card assert: **BLOCKED(AS)** — stage 2 per live-layer item 5 ("the live knock"): the UI paints the card off the firehose's `approval_request` create; assert the card appears without navigation and Approve on the *card* mints the grant |
| **2 Company** | Sous heartbeats `POST /api/-/presence {self <plan-self>}` on a cadence (needs a one-liner `presence!` helper beside `watch!` — presence is ephemeral surface, not law, so a dedicated door doesn't violate rule 1) | with the plan open: `[data-presence]` grows `"sous is here"`; Priya opens `/api/members/sous` (auto-provisioned on first sight), clicks **Follow** → `#followchip`; Sous's next heartbeat on the meal list **navigates her screen there** (`location.hash` follows), then back to the plan | presence frames on `/api/-/presence` SSE carry `{principal {id "sous" type "agent"}, source "heartbeat"}` | **TODAY** (audit row 2: landed) |
| **3 Thinking out loud** | `dry-run` `assign_meal {date "2026-07-21", meal_id <tacos>}` → `{:valid true}` (the considering, server-judged, invisible to Priya today); then `act!` the same → 200 | following, on the plan: the day row fills (`"Carnitas tacos"` under `2026-07-21`) via SSE refetch, **no reload**; ticker narrates `assign_meal` with actor `sous` | transition row actor `sous`; envelope `meal_name` label engine-written | act + ledger: **TODAY**. The *considering surfaced to the follower*: **BLOCKED(IF)** — stage 2, the live-layer hooks verbatim: Sous's `dry-run` (the implicit door — a dry-run IS a considering) puts a `considering` frame naming `sous`/self/`assign_meal` on Priya's `/api/-/intents` stream within one heartbeat, and her followed screen paints the ghost; Sous's act delivers **`acted`** for that intent (resolved into the act, not merely expired); a second dry-run abandoned past its TTL is *absent from a fresh snapshot* (gone in a moment); a grant-scoped third session that cannot GET the self sees byte-level nothing |
| **4 Four hands** | wait for the ticker to narrate the last plan/meal act (**the regate-drain rule**, §1); Sous opens WS `/api/meals/<brisket>/-/update_recipe/draft/collab` with `x-waymark-principal: sous` (no grant header — collab grant projection is the recorded 9b punt; noted in-test); syncs, then sends OT `edit` ops reworking the rub into grams *around* Priya's sentence | Priya opens the Update-recipe dialog; wait for the relay-live roster note (ui-drive's own discipline); she types `"Dad likes it smokier"` (input + focusout); after Sous's ops: her textarea **converges** to one text containing both her sentence and the grams | draft GET shows per-field `revs` and `authors` — Sous's edit authored `sous`; after Sous `act!`s `update_recipe` with the converged text: browser hears **regate `gone:true`**, draft GET → 404 (consumed in the act's commit), the meal envelope carries the two-author recipe | convergence, authorship *of Sous*, consumption: **TODAY**. Priya's paragraphs attributed (her browser socket joins anonymous): **BLOCKED(WS)** — stage 2, the ticketed join: both sides mint `POST /api/-/ws-ticket` and join with `?ticket=` (Sous's header join retires so both clients walk the same door); the state frame's participants name `sous` (type `agent`) *and* `priya` — not anonymous; each side's update frames carry the real `author`, and the per-field author survives to the consumed act; a spent/expired ticket refuses **401 before the upgrade** (a plain HTTP problem); Priya's cursor frame arrives at Sous attributed and is provably absent from the persisted draft document. Cursors in the *UI* close as the live-layer's item 6 side effect |
| **5 The ask** | `act!` `finalize` → `{:warnings [...] :acknowledge! f}` — assert the guard's sentence (`"1 calendar conflict(s) overlap this week"`, recital named), and that the plan is **still draft** on re-read: the wall held, Sous *cannot* push through | stage 1: Priya's own Finalize dialog shows the **identical** guard sentence in its warnbox (advertisement = refusal, the v10 invariant ui-drive already pins) — the "her decision" seam is then scripted: the harness, *as Priya's yes*, invokes Sous's `(:acknowledge!)` | plan → `planned`; the transition's actor is `sous` and it carries acknowledge `calendar-clear`; ticker narrates | wall + sentence-identity + release-by-acknowledge: **TODAY**. The question *addressed to her on screen* whose tap releases Sous: **BLOCKED(AS)** — stage 2, the live-layer hooks verbatim: Sous's warning wrapper (`intents/asking-confirm!`'s sibling) posts the ask and **blocks**; Priya's stream delivers `{kind "asking"}` whose `warnings` carry the guard's sentence **string-equal across all three surfaces** — the 409 body, her Finalize warnbox, and the ask card; her tap (`answer "approve"`) releases Sous's blocked call, which re-invokes with `Waymark-Acknowledge`, and the landed transition's `:acknowledged` names the guard (wire release and law record agree); the negative arms: self-answer refused, decline releases into exit-2 with her note, an unanswered ask expires on the clock |
| **6 Standing behind it** | (a) rule 1: `act!` `retire` on a granted meal → the scoped envelope *conceals* the ungranted action → local refusal `:unknown-action` (never a constructed URL); (b) rule 2 already proven at beat 1's approve-side; Sous's granted actions are deliberately confirm-free | Priya clicks **Reopen** on the planned plan → the undo toast (`#toast button[data-undo]`, ui-drive's pinned affordance) → she undoes → planned again; the feed rows for Sous's acts all carry `sous` | every transition row carries the actor; the feed renders `actor · action · kind · from → to` | **TODAY** — except the composed display name `"Sous (for Priya)"` in the feed (actor display exists on the wire; the ask/grant flow doesn't carry the on-behalf-of yet — audit row 6) |
| **7 Trust, inspected** | — (Sous is done working) | `/api/members/sous`: the member envelope + **Follow**; `/api/grants?audience=sous`: the accepted grant, `expires_at` shown (assert **absolute** text, never a browser-relative countdown, §3); `/api/approval_requests?requested_by=sous`: the approved ask (and, if the test files a denied one, the deny with its note) | DB: `waymark10_transitions` rows actor `sous` with their law revisions (replayable under the law each was judged by) | **TODAY**, across separate screens. The composed relationship screen lands as the live-layer's item 7 — `/api/surfaces/relationship/{member-id}` (the law half: member + grants + asks) with ui.html's three live overlay strips (presence dot, pending intents, follow ledger); stage 1.5 asserts the surface envelope, stage 2 the strips |
| **8 The leash ends** | after the **clock jump** to `T₀+24h+1s` (§3): scoped GET plan → `{:problem …, :status 404}` — dead means scoped-to-nothing (verified: visibility resolves through `((:now-fn eng))`, grants.clj:627); Sous's heartbeat loop stopped | grant envelope now affords `expire` (the `past-expiry` guard's `becomes_available` was `T₀+24h`); anyone runs the bookkeeping `expire` → state `expired`, ticker narrates; `[data-presence]` loses `"sous is here"` within ~1s wall (presence TTL is real-time, compressed via `:presence-heartbeat-ms 300`, §3) | plan, recipe (two authors), and every transition row **persist** — "the power expires on schedule; the story of what it did never does" | **TODAY**, fully |

**Tally.** All eight beats have stage-1 assertions. Beats **2, 6, 7,
8** prove their story observable completely today; beats **1** and
**4** are complete minus one deferred assertion each (the addressed
card; Priya's WS attribution + cursors); beats **3** and **5** prove
the law side today (the dry-run, the wall, the sentence, the
release) while their *defining felt observable* — thinking and
asking made visible on Priya's screen — is precisely what the three
missing mechanisms exist to provide.

### The two stages, mechanically

- **Stage 1 (lands now, goes green now):** the table's TODAY column,
  as one `deftest` walking the eight beats in order. The blocked
  assertions are written into the test *as named pending blocks* —
  a `(pending :beat-3/intent-frame "blocked on intent frames")`
  helper that reports (not silently skips) so the gate's output
  narrates what the soul still owes.
- **Stage 2 (completes with the live layer):** each mechanism, as it
  lands, converts its pending block to the assertion
  `live-layer-design.md`'s hooks section already specifies — the
  `considering`→`acted` frame pair flips beat 3's, the asking channel
  flips beat 1's card and beat 5's (the scripted `(:acknowledge!)` is
  replaced by Priya's DOM tap releasing Sous's *blocked* call, with
  the guard sentence asserted string-equal across 409, warnbox, and
  card), and ticketed joins flip beat 4's attribution + cursors.
  The test's *skeleton does not change* — that is the point of
  designing the harness now, and of the live layer publishing its
  hooks: the framework-level `live_layer_test.clj` (live-layer item
  8) proves each mechanism in isolation; this story test asserts the
  same observables *in the story's own order*.

---

## 3. Time control: one virtual law-clock, real-but-short lived time

Two kinds of time run through the story, and they compress
differently — honestly:

1. **Law time** (grant TTL, `becomes_available`, guard `(now)`,
   sweeper flips) — everything judged server-side flows through the
   engine's injected `:now-fn`. The harness boots with a
   `(atom (Instant/parse "2026-07-14T18:12:00Z"))` clock, exactly
   `family_week_test`'s pattern, and performs **one jump** at the
   beat-8 boundary (`reset!` to `T₀+24h+1s`). Verified seams: grant
   visibility (`grants.clj:627`), the `past-expiry` guard, the clock
   sweeper (`coherence_test` `clock-sweeper-election` drives it with
   the same atom), presence frame `:at` (presence.clj:105).
2. **Lived time** (presence TTL/eviction, SSE heartbeats, dispatcher
   polls, collab room heartbeats) — these run on wall-clock
   milliseconds (`System/currentTimeMillis`, presence.clj:146/198/
   280/406) and are *not* `now-fn`-driven. They compress by
   configuration, all existing engine opts: `:events-poll-ms 100`,
   `:presence-heartbeat-ms 300` (3 missed beats → ~1s eviction, the
   beat-8 fade), `:sse-heartbeat-ms 500`, `:collab-heartbeat-ms
   1000`, `:sweep-interval-ms 100`.

**Chromium's clock is never injected — by design, not concession.**
Everything the browser shows is server-rendered state delivered over
SSE; the only browser-`Date` artifacts are *relative* countdown
displays. So the harness's standing rule: **assert absolute
server-sent time text (the `expires_at`, the `becomes_available at
…` narration), never a relative countdown.** After the beat-8 jump,
the server clock and the browser wall clock disagree — and no
assertion cares, because every post-jump observable (the 404, the
`expire` affordance, `expired`, the presence fade) is
server-judged; the browser merely refetches when the ticker frame
arrives. (CDP's `Emulation.setVirtualTimePolicy` exists but is
exactly the kind of half-supported seam that breeds new flakes;
rejected.)

The fixed story clock also buys deterministic dates in every
assertion (the week of the 21st, the recital on the 23rd, "until
tomorrow, 6:12 pm") — the family-week test's proven trade.

---

## 4. Flake discipline: what the harness owns

- **Fresh world per run, its own database.** The batch-D discipline:
  `mealplan10_admission_test` (created once on the `waymark-test-pg`
  container, `:5433`), never the suite's `waymark10_test` — this
  test runs a *started* engine (sweepers, dispatcher, presence,
  advisory-lock roles) and must never share tables with a
  handler-only suite. The fixture drops the app tables + engine
  tables (family-week's list + `waymark10_cursors`,
  `waymark10_job_leases`, `waymark10_observations`, members/roles/
  grants/approval_requests) and reseeds through the API. No
  self-normalizing checks, no partial-rerun tolerance — ui-drive
  needs those because it borrows a long-lived dev world; the
  admission test owns its bytes.
- **Fixture-owned chromium.** Spawned by the test (`ProcessBuilder`),
  ephemeral debug port read from chromium's own stderr
  (`DevTools listening on ws://…`), temp `--user-data-dir`, killed
  in `finally`. No "step 2: start chromium yourself" — the ui-drive
  header recipe becomes code. `CHROMIUM_BIN` overrides discovery
  (`chromium`, `chromium-browser`, `google-chrome` on PATH).
- **Seeded FakeEvents.** `mealplan10.event-source/fake-events` +
  `es/seed!` + `mirror/discover!` — the family-week §, verbatim; the
  engine is built with `(main/resources feed)` so the test scripts
  the calendar.
- **Every wait bounded and beat-named.** One helper:
  `(await! :beat-5 "guard sentence in Priya's warnbox" pred-or-js
  timeout-ms)` — on starvation it throws
  `"beat-5 starved: guard sentence in Priya's warnbox (waited 8000ms)"`.
  The beat name is the failure's first word; nobody greps a stack
  trace to learn which beat died. Applies uniformly to CDP DOM
  waits, SSE-frame waits (Sous's `watch!` feeding an atom, asserted
  with the same helper), and collab-frame waits (batch-D's
  `await-frame`, lifted).
- **No bare sleeps.** ui-drive's `sleep(1200)` boot waits become
  waits on DOM-ready predicates; batch-D's `Thread/sleep 500`
  dispatcher guard becomes the **regate-drain rule** (§1): after any
  HTTP act on a row whose collab room is (or is about to be) open,
  wait for the ticker/firehose to narrate that transition before the
  next room interaction. This is the fix for the one diagnosed
  flake, stated as a rule instead of a pause.
- **Console errors are failures.** The CDP session subscribes to
  `Runtime.exceptionThrown` + `consoleAPICalled(error)` (ui-drive's
  collector); the fixture asserts the list empty at the end of every
  beat, so a beat that *passes its DOM check while the page errors*
  still fails, named.
- **Serial by construction.** One JVM, one browser page, one Sous
  session; the test never runs concurrently with itself (kaocha runs
  the ns serially within `make test-mealplan10`).

---

## 5. Where it lives, and how it gates

- **The test:** `mealplan10/test/mealplan10/admission_test.clj` —
  beside `family_week_test.clj`, on the path `make test-mealplan10`
  already runs (kaocha `:unit`, `test-paths ["test"]`). It is the
  *second standing story* the hand-in-hand doc names, in the same
  gate as the first.
- **The helpers:** `waymark10/src/waymark10/test/cdp.clj` (CDP over
  `java.net.http` WebSocket: connect, `eval-js`, `await!`, console
  collector, chromium spawn/kill) and a small
  `waymark10/src/waymark10/test/collab.clj` (batch-D's `ws-connect`
  / `send!` / `await-frame`, extracted). Precedent: shared test
  infrastructure already lives on `src` under `waymark10.test.*`
  (`waymark10.test.db` — which is how mealplan10's tests see it
  through the `:local/root` dep).
- **Client additions (product code, small):** `client/presence!`
  (the heartbeat POST — the ephemeral surface's door, beside
  `watch!`).
- **Gating, mechanically.** "No version ships without it" already
  has a mechanical meaning in this repo: every waymark10 landing's
  commit message quotes the final gate — `make test10` + `make
  test-mealplan10` counts over a quiet tree (the recorded ritual
  since phase 0). Joining `test-mealplan10` *is* joining the gate.
  Two teeth make it bite: (1) **missing chromium is a red failure,
  not a skip** — `beat-0 starved: no chromium (set CHROMIUM_BIN)`;
  a gate that silently skips its soul-check is the migration scar
  again; (2) the stage-2 pending blocks print in the gate's output,
  so the owed assertions are narrated on every run until the live
  layer pays them.
- **Runtime budget:** JVM + deps ~20s, engine boot + migrate ~5s,
  API seed ~2s, chromium ~1s, eight beats of bounded waits (typically
  sub-second each; worst-case timeouts never stack on the green
  path) ~40s. **≈ 70–90s total, comfortably under the 3-minute
  budget**, and `clojure -M:test --focus mealplan10.admission-test`
  runs it alone.

---

## 6. Build plan

| # | Piece | Size | Notes |
|---|---|---|---|
| 1 | `waymark10.test.cdp` — chromium spawn/kill, WS CDP session, `eval-js`, `await!` (beat-named timeouts), console-error collector | **M** | ~150 lines; the send/pending-map/evaljs core is a direct port of ui-drive.mjs:57–102 onto `java.net.http` WebSocket (batch-D's listener pattern) |
| 2 | `waymark10.test.collab` — extract batch-D's `ws-connect`/`send!`/`await-frame`/`sync-state` | **S** | move + rename; batch_d_collab_test requires it back |
| 3 | `client/presence!` — the heartbeat door | **S** | one POST helper + a docstring sentence on why it isn't `act!` |
| 4 | Admission fixture — `mealplan10_admission_test` DB (createdb note in the ns docstring), drop-tables list, engine boot (clock atom, short cadences, FakeEvents, `main/resources` + `main/surfaces`, `engine/start!` port 0), API seed as priya, chromium + CDP + Priya boot (`wm10.principal`) | **M** | family-week's fixture + ui-drive's boot recipe, fused |
| 5 | Beats 1–8, stage 1 — the table's TODAY column, with pending-block helper for the blocked assertions and the regate-drain rule as a named helper | **L** | the bulk; each beat is a `testing` block whose string is the beat's name |
| 6 | Gate wiring — nothing to add to the Makefile (it rides `test-mealplan10`); chromium red-failure message; docstring cross-link to hand-in-hand | **S** | |
| 7 | Stage 2 conversions — as each live-layer track lands: `considering`/`acted` frames on `/api/-/intents` (beat 3), the ask card with the string-equal guard sentence + blocked-call release via the client's asking seams (beats 1, 5), `?ticket=` joins with real participants/authors/cursors (beat 4), "(for Priya)" display (beat 6) | **M** (test-side; the mechanisms are live-layer items 1–6) | assertions pre-specified in live-layer-design.md's hooks section — conversion is mechanical |
| 8 | Stage 1.5 — beat-7 assert against `/api/surfaces/relationship/{member-id}` once the live-layer's item 7 declares it; strips assert with stage 2 | **S** | |

Order: 1→4 in one sitting (the harness boots and beat 1 runs), then
5 beat by beat, 6 with the first green run. Total stage 1: one
focused day of work; the test is then the standing second story, and
stage 2 is three small diffs away from the story being whole.
