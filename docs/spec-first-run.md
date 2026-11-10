# Spec — first run: the front page is what waits on you

**Thesis.** A fresh Waymark's front page is the list of what waits on
the person reading it. On a fresh engine that list is the setup: point
it at a codebase, give it GitHub access, let seats run, connect the
mayor, send a first ticket. Clearing the list is the quickstart.
Afterwards the same page holds held calls, approval requests and every
other row whose next move is the reader's, so first run is not a mode
and not a wizard.

Epic: 7f588123. This document is its first child (ticket d5c54696). It
writes no code; §12 is the build plan the epic chains.

## 1. Where main stands

Read on main when this spec was written:

- `SELFHOST.md` is seven steps for Claude Code to follow. Step 2 fills
  `selfhost/.env` by hand: seven values, three made with `openssl`
  (`POSTGRES_PASSWORD`, `WAYMARK_PULL_TOKEN`, `WAYMARK_DATA_KEY`).
  Step 4 writes `selfhost/localfire.edn` by hand and starts localfire
  with a `docker run` outside compose. Step 5 copies `selfhost/repo-kit`
  into the person's repository and opens its pull request by hand.
  Step 6 writes the mayor's `CLAUDE.md` by hand. The first factory pull
  request comes at step 7.
- `selfhost/compose.yml` runs `postgres`, `engine` and `bench`. Every
  secret enters as an environment variable from `.env`; compose refuses
  to start with one empty (`${X:?set X in .env}`). The engine is
  published on `127.0.0.1` only. The bench reads `BENCH_GIT_TOKEN` from
  its environment.
- The factory seed (`waymark10/resources/waymark10/factory/seed.edn`)
  takes `owner`, `repo`, `bench_url`, `model` and `pull_token` as
  `WAYMARK10_SEED_PARAMS`. So the pull token, a secret, rides in seed
  params today. It makes the two judgments, the bench's `mcp_server`,
  the `repo_policy`, the `model`, the pull `runner_link`, the four seats
  and their schedules. It is `:live-ok` and loads on an engine with no
  members only.
- `WAYMARK10_DATA_KEY` is read from the environment and nowhere else
  (`server/at_rest.clj`). The solo owner is `WAYMARK10_SOLO_OWNER`
  (`server/engine.clj`): a solo engine's dev headers are its only
  identity.
- `secret` (`server/secrets.clj`) is write-only: `value` is `:secret`,
  a person enters it at create or through `replace`, a seat may create
  a row without a value as a request, and a power call names it by id
  through an `x-secret-ref` argument resolved at `mcp-servers/call!`.
- `seat` has `offer_key`; `runner_link` has `puller_seen_at`;
  `repo_policy` (`factory10/resources/repo_policy.clj`) has
  `enrolled_at` and `note`, and its `restate` enrolls the repository on
  the bench.
- Quests: the kind (Quests 1) and its planning consumer (Quests 1b,
  `quests/start!` in `modules.clj`, `answer->plan` in
  `server/quests.clj`) are on main and enrolled in every engine. The
  tracker (Quests 5, `#questbar`, `ui/120-nav-home.js`) is on main.
  Missing: no seed creates a quest, and the setup's rows have no guards
  whose refusals name the setup's doors, so a planner today has nothing
  to order. §7 adds both.
- `GET /api/-/in-flight` (`routes/seats.clj`, `server/in_flight.clj`)
  answers every open row the caller's grant admits, with its
  `waiting_on`, grouped by holder, and takes `holder=`.
- `renderHome` (`ui/120-nav-home.js`) is a dashboard: one card of
  counts per primary kind, per domain, and a strip of running jobs. It
  says nothing about what waits on the reader.
- `selfhost/ci/walk.py` walks `SELFHOST.md` in its own order with a
  stub in Claude's chair: `connect`, `link`, `enroll`, `ticket`,
  `submit`, `mayor`.

## 2. Definitions

- **The viewer**: the principal reading the page.
- **Waits on the viewer**: an open row whose `waiting_on.holder` is the
  viewer, or a seat the viewer holds as a person (`person: true` on a
  `waiting_on.next` entry).
- **A card**: one such row on the front page, with one door.
- **The setup quest**: the quest the first-run seed creates, §7.
- **The keys volume**: a compose volume, `keys`, apart from the
  database's, §4.

## 3. Requirements: the front page

**R-3.1** The front page must be one read: `GET
/api/-/in-flight?holder=me`. `me` names the viewer; the read answers
the one group the viewer holds. Nothing on the page lists a kind by
name: a kind is on it because a row of it waits on the viewer.

**R-3.2** `held_call` and `approval_request` rows must carry a
`waiting_on` that names the person they wait on, so R-3.1 reads them
with no second query. The decision kinds (a ticket in `draft` held by a
person's seat, a `change` waiting on its merge, an `invitation`) are
reached the same way.

**R-3.3** The order: the pinned quest's head card first; then held
calls, because a sitting stands waiting on them; then approval
requests; then every other card by `waiting_on.since`, oldest first.
Cards group under their domain's heading when the engine has more than
one domain.

**R-3.4** A card shows the row's summary line, the sentence it waits
with (§6: the guard's `explain` for a setup card, else the row's
`waiting_on.verdict`), and one door: the first `waiting_on.next` entry
the viewer holds. The door opens through `openDoor`
(`ui/180-action-dialog.js`) with its input schema, as the tracker's
Go does. A secret argument renders as a password field and is never
prefilled.

**R-3.5** The empty state is one sentence, "Nothing waits on you.",
with a link to the in-flight page for what waits on others.

**R-3.6** The front page leads the home view. The dashboard's cards
follow it, under a "Everything" heading, unchanged. The page follows
the live stream it already follows and reads R-3.1 again on a
transition of any row on it.

## 4. Requirements: first boot with no `.env`

**R-4.1** `selfhost/compose.yml` must start with no `.env`. Every
variable keeps a default; none is `:?`-required.

**R-4.2** A one-shot `keys` service runs before the others and writes,
once, owner-only, into the keys volume:

- `data.key`: 32 random bytes, base64;
- `postgres.password`;
- `pull.token`;
- `localfire.key`: the key localfire seals its Claude token with (R-12.2
  of `docs/spec-local-fire.md`).

A file that exists is never written again. The service prints each
file's name and never its value.

**R-4.3** Each consumer reads its file, read-only: Postgres through
`POSTGRES_PASSWORD_FILE`; the engine through `WAYMARK10_DATA_KEY_FILE`
(new, beside `WAYMARK10_DATA_KEY`, which still wins when set) and a
password file for its DSN; localfire through `LOCALFIRE_PULL_TOKEN_FILE`
and `LOCALFIRE_TOKEN_KEY_FILE`.

**R-4.4** The data key survives outside the database: it lives in the
keys volume, which `docker compose down -v` deletes with the database
and nothing else does. `SELFHOST.md` says once to back the volume up,
and that a database without its key cannot be read.

**R-4.5** An engine with no members and no `WAYMARK10_SOLO_OWNER`
boots unclaimed. It prints one log line, `claim code: <code>`, a fresh
single-use code of at least 128 bits that lasts until it is used, and
serves only the claim page and the well-known door. Every other route
answers 503 naming the claim page.

**R-4.6** The claim page asks for the code, the person's name (their
member id; the GitHub user is the suggestion) and the repository the
factory builds in, as `owner/repo`. A repository of another shape is
refused at the claim with a sentence saying so, before the code is
judged. A right code makes that member the solo owner, keeps the
owner's id in the keys volume (`owner`), so later boots resolve it as
`WAYMARK10_SOLO_OWNER` does, and runs the first-run seed (§5) with the
owner and the repository. A wrong code answers 403 and five wrong codes
end the code; a restart prints a new one.

**R-4.7** The engine stays published on `127.0.0.1`. The claim code
keeps a second local user or process from becoming owner before the
person does. After the claim the engine trusts this machine as a solo
engine does today; the claim is no wider boundary than that, and this
spec says so in `SELFHOST.md`.

**R-4.8** The claim's IdP-backed counterpart. An engine with an
identity provider (`:oidc`) arms no claim and has no solo owner, and
its membership is invited, so an empty one could never be entered.
`WAYMARK10_FIRST_OWNERS` names its first owners: comma-separated IdP
subjects (the token's `sub`), read at boot and only beside `:oidc`.
When the store holds no member row, each named subject is born an
active member holding manager and recovery-admin (the solo owner's
standing), by the registrar, its log saying it came from
`WAYMARK10_FIRST_OWNERS`. Once any member exists the variable is
ignored and boot says so in one line: it is a bootstrap, never a
backdoor, so a member removed later is not brought back by a restart.
Blank or malformed entries do nothing but warn, and nobody the
variable does not name gets past invited membership
(`waymark10.server.members.first-owners`).

## 5. Requirements: the first-run seed

**R-5.1** The seed takes `owner` and `repo` from the claim (R-4.6);
no card asks for the repository (R-5.3). It takes no secret: the pull token is
`pull_token_file`, a path, and the seed reads it from the keys volume.
`bench_url` and `model` keep their defaults.

**R-5.2** Each setup card is a row the seed makes, waiting on the owner
while its guard refuses, with the guard's `explain` as its card text:

| Card | Row | Waits until | Card text |
|---|---|---|---|
| GitHub access | a `secret` named `github`, no value | a value is entered | "Paste a fine-grained GitHub token on that repository: contents, pull requests and actions, read and write. It is never shown back." |
| Let seats run | the pull `runner_link` | `puller_seen_at` is recent | "Open localfire at http://127.0.0.1:8112 and paste the token `claude setup-token` prints." |
| Connect the mayor | the `mayor` seat | it has sat once | "Run this one command in a new folder, then open Claude Code there." |
| A first ticket | a `ticket` in `draft`, pre-filled | it is groomed | "Read the first ticket and send it." |

**R-5.3** No card asks for the repository: the claim does (R-4.6), so
the seed makes the `repo_policy` whole, with `repository` from the
claim and the rest (`required_checks`, `test`, `orientation`) its own,
as today, and its first ticket names that repository. A `repo_policy`
made without `repository` is not needed.

**R-5.4** GitHub access is one `secret` the person fills. The bench
and the engine's forge readers name it by id; neither reads a
`BENCH_GIT_TOKEN` when the row exists. For the bench this needs
`mcp_server` to carry a header whose value is an `x-secret-ref`,
resolved where every secret is (`mcp-servers/call!`), and
`ckopsa/waymark-bench` to take its git token from that header when its
environment has none. The bench step of §11 is two pull requests, one
in each repository.

**R-5.5** localfire is a compose service, `localfire`, published on
`127.0.0.1:8112`, with its config baked from compose's environment, so
no `localfire.edn` is written. It is idle until it holds a Claude token:
it does not pull. Its token page is R-12.2's `/me` without `:oidc`: one
local person, the token sealed under `localfire.key`, never rendered,
never logged. The Claude token never enters the engine: the engine is
reachable by every seat's session, and localfire is not.

**R-5.6** The mayor card's command is one line the engine composes:
`curl` of a new seat door, `GET /api/seats/<id>/-/place`, answering a
tar of `CLAUDE.md` and `inbox-feed.sh`, piped to `tar x`. The door
runs `offer_key` and writes the key into that `CLAUDE.md` alone; the
key is in no row, log or transition, as `offer_key` already promises.
The person writes no file. On a solo engine the line carries the solo
identity headers the person's Claude Code already uses (`SELFHOST.md`
step 3), `curl -fsS -H 'X-Waymark-Principal: <owner>' -H
'X-Waymark-Actor-Type: human' http://127.0.0.1:<port>/api/seats/<id>/-/place
| tar x`: that is R-4.7's trust, this machine and nothing wider, and the
key still lands only in that `CLAUDE.md`.

**R-5.7** The first ticket is pre-filled with the repo kit (§8) and
waits on the owner's `groom`: the person's tap is the groom, and
code-seat fires on it as it does on draft-judge's.

## 6. Requirements: the guard sentences

**R-6.1** Each setup row's waiting state must be judged by a guard on
the setup quest's goal door (R-7.1), one guard per card, each naming
its row's door as its remedy. The card's text is that guard's
`explain`, so the page, the refusal and the quest's step say the same
sentence.

**R-6.2** The guards read rows and nothing else: `enrolled_at`, the
secret's having a value (never the value), `puller_seen_at` within
three poll windows, the mayor's first sitting. None calls out.

## 7. Requirements: order is a quest

**R-7.1** The seed creates one `quest`, owned by the owner and pinned:
its `self` is the first ticket, its `action` is `groom`. The groom of a
factory ticket whose repository is not ready refuses with R-6.1's
guards, so the planner (Quests 1b) orders the cards from refusals, and
the tracker (Quests 5) shows the head card on every page. No list of
cards is written in code.

**R-7.2** The quest finishes at the groom. The first factory pull
request is the measure's end (§10), one seat's move later; a quest's
goal is a door its owner could take, and the submit is code-seat's.

**R-7.3** On main, the kind, the consumer and the tracker exist. What
this spec adds: the seed step that creates and pins the quest, the
guards of R-6.1, and the `waiting_on` of R-3.2 and R-5.2.

## 8. Requirements: the repo kit

**R-8.1** The factory's own first ticket opens the repo-kit pull
request: its detail carries `selfhost/repo-kit`'s two files, and
code-seat writes them. So the first factory pull request is the kit,
and the person copies nothing.

**R-8.2** What stays the person's: the merge, editing `tests.yml`'s
test command, and making `tests` a required check. After the merge, a
card waits on the owner until `tests` is required (read from the
policy's base pass, `note_base`), with the sentence "Make `tests` a
required check on your default branch: it is what the merge waits
on."

**R-8.3** The kit pull request edits `.github/workflows/`, which a
fine-grained token pushes only with the workflows permission. The
GitHub card's sentence names it.

**R-8.4** Punted: a sandbox template repository, so the first pull
request lands before the real repository is wired. It is a later
ticket if §10's measure misses its target.

## 9. Requirements: security

**R-9.1** A secret is pasted into a `secret` row (GitHub) or
localfire's token page (Claude), and nowhere else. It is never echoed
back, logged, carried in seed params, or written to a file a person
writes.

**R-9.2** Nothing is published on `0.0.0.0`: the engine and localfire
on `127.0.0.1`, the bench and Postgres not at all.

**R-9.3** No seat gains a standing power. The cards are the owner's
doors; the seats' scopes are the seed's, as today.

**R-9.4** The keys service, the claim code and the mayor's place are
the only new places a secret is minted; each is in R-4 or R-5.6 and
none outlives its file.

## 10. Proving grounds and measures

**R-10.1** The measure: from `docker compose up` to the first factory
pull request, the time, and the count of answers, pasted secrets and
hand-written files. The target is 15 minutes, 3 answers (the name at
the claim, the repository, the first ticket's send), 2 secrets
(GitHub, Claude) and 0 files. The claim code is read from the log,
not answered.

**R-10.2** `selfhost/ci/walk.py` walks the front page until it is
empty: it reads the claim code from the engine's log, claims, then
loops on R-3.1 as the owner and takes each card's door with the
fixture's values and the stub's, failing with the card's own sentence
when a card does not clear. Its stages are `claim`, `cards`, `submit`
and `mayor`; the order is the quest's, not the script's.

**R-10.3** The CI walk counts what it answers and pastes and fails
when either passes R-10.1's target.

**R-10.4** A cold run by a person who never saw Waymark is timed once
before the epic closes, and its numbers are written into the epic.

**R-10.5** Optional: a `promise` on the selfhost job's walk, kept by
its green run. `promise` is app-opt-in, so this needs it enrolled in
the factory-only engine first.

## 11. What `SELFHOST.md` becomes

Three steps: `docker compose -f selfhost/compose.yml up`; open
`http://127.0.0.1:8014` and enter the claim code the log prints; clear
the page. Then one paragraph on what the page holds afterwards, one on
backing up the keys volume, and the troubleshooting lines of today's
steps that still apply. The `.env` and `localfire.edn` sections go;
`.env.example` stays for the variables a person may override.

## 12. Build plan

Each step is its own child ticket, under 800 changed lines. Steps that
share a file chain in this order.

1. **The front page's read.** `held_call` and `approval_request` carry
   `waiting_on` (R-3.2); `in-flight` takes `holder=me` (R-3.1).
   `server/held_calls.clj`, `server/grants.clj`, `server/in_flight.clj`,
   `routes/seats.clj`.
2. **The front page.** R-3.3 to R-3.6. `ui/120-nav-home.js`, its CSS.
   After 1.
3. **Keys.** The `keys` service and the `_FILE` reads (R-4.1 to R-4.4).
   `selfhost/compose.yml`, `selfhost/.env.example`,
   `server/at_rest.clj`, the engine's DSN reader.
4. **The claim.** R-4.5 to R-4.7. `server/engine.clj`, a new
   `server/claim.clj`, its UI page. After 3.
5. **The bench's credential.** R-5.4: an `x-secret-ref` header on
   `mcp_server` (`server/mcp_servers.clj`), and in `ckopsa/waymark-bench`
   the header read. Two pull requests.
6. **localfire in compose.** R-5.5: the solo token page and the
   `_FILE` reads in `localfire/`, the service in `selfhost/compose.yml`.
   After 3.
7. **The mayor's place.** R-5.6: the seat door in `server/seats.clj`
   and `routes/seats.clj`. After 1 (shares `routes/seats.clj`).
8. **The first-run seed.** R-5.1 to R-5.3, R-5.7, R-7.1, R-8.1: the
   cards, the first ticket with the kit, the quest.
   `waymark10/resources/waymark10/factory/seed.edn`,
   `factory10/resources/repo_policy.clj`. After 4, 5, 6 and 7.
9. **The guards.** R-6.1, R-6.2, R-8.2:
   `factory10/resources/ticket.clj`. After 8.
10. **The walk and the guide.** R-10.2, R-10.3, §11: `selfhost/ci/walk.py`,
    `SELFHOST.md`, the `selfhost` job in `.github/workflows/tests.yml`.
    After 9.
11. **The measure.** R-10.4, and R-10.5 if wanted: a timed cold run
    and its numbers on the epic. After 10.

## 13. Recorded punts

- A quest whose goal is the submit itself (R-7.2): a later ticket if
  persons may hold a change's submit as a quest goal.
- The sandbox template repository (R-8.4).
- More than one person on a fresh engine: the claim makes one owner;
  inviting others is the members' existing doors.
