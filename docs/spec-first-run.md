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
with a link to Everything.

**R-3.6** The front page is the whole root, and it is the viewer's
held watches (docs/spec-watch.md §6): the held read, `GET
/api/-/watches/held`, whose seeded watch answers the rows R-3.1 names,
as one list of cards. The dashboard — the kinds grid, the attention
strip, Seasons and the chip rows — lives on the Everything tab
(`#everything`). The page follows the live stream it already follows
and reads the held read again on a transition of any row on it.

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
(`waymark10.server.members.first-owners`). The owners born there get
the setup cards and nothing else of the seed (§5's R-5.2 shapes, no
bench, repository, seat or GitHub card): each is given "Setting up",
`required`, its one cue "Connect your Claude" on their own member row,
with the hosted text that names no mayor. Where the engine serves
`org_plan`, the first owner the variable lists files one `house` plan,
drafting, with no brief, and is given "Next steps" with its one cue
"Say what the house is for"; the plan's doors are its owner's alone,
so no other owner gets that card. Each step skips what is there, and a
boot that finds only the variable's people and no watch but the
seeded ones and these cards files what is missing: a boot that died
between the birth and the cards is resumed
(`waymark10.server.members.setup-cards`).

## 5. Requirements: the first-run seed

**R-5.1** The seed takes `owner` and `repo` from the claim (R-4.6);
no card asks for the repository (R-5.3). It takes no secret: the pull token is
`pull_token_file`, a path, and the seed reads it from the keys volume.
`bench_url` and `model` keep their defaults.

**R-5.2** Each setup card is a row the seed makes, held by one of two
watches the seed gives the owner (docs/spec-watch.md § 7) while its
step is not done: "Setting up", `required`, one cue per required row,
and "Next steps", not required, one cue per optional row, each in
table order. A cue names its row by `id`, holds it in its live state,
and asks that the field the step writes is still empty. The card's text
is the cue's `note`, with `{engine_url}` filled; on an engine with
`:oidc` sign-in its `note_hosted` is said where it has one. A hosted
engine that runs no seed files its cards at its first owners' birth
instead (R-4.8).

| Card | Required | Row | Clears when | `note` (solo) | `note_hosted` |
|---|---|---|---|---|---|
| Connect your Claude | yes | the owner's `member` row, named by `id` | a later ticket (2c87c3c6) defines it; until then it does not clear, and the note promises no clearing | Steps, one to a line (the card keeps the note's line breaks; its heading links to no row): "1. In Claude (claude.ai or the Claude app), open Settings, then Connectors, and add a custom connector.<br>2. Name it after your house and give it this engine's address:<br>{engine_url}<br>3. Claude sends you here to sign in with your house account; allow it.<br>4. Open a new chat with that connector on and say what you want this house to do. The Claude you connect is your mayor.<br>You know it worked when Claude answers from this house.<br>Already added it? Open a chat with it on; if Claude says it needs to sign in again, reconnect it." | none (a hosted engine's own cards, R-4.8, say the same without the mayor sentence, and add a last line: "If Claude says it could not register, tell your house's operator: the connector client is missing or not public.") |
| Let seats run | yes | the seeded pull `runner_link` (fixed id `10ca1f12-e000-4000-8000-000000000001`) | `puller_seen_at` is set: its first pull | "Open localfire on 127.0.0.1:8112 and give it your Claude token: paste the one `claude setup-token` prints." (R-5.5) | "Seats run once this engine's runner has pulled for the first time. That is the operator's to start; nothing here is yours to do." |
| GitHub access | no | the `secret` named `github` (R-5.4) | `value_set_at` is set | "Paste your GitHub token: a fine-grained token on the repository, with contents, pull requests, actions and workflows read and write. It is never shown back." | none |
| Say what the house is for | no | the `org_plan` named `house`, drafting, empty brief, owned by the owner | its `brief` is written (`write_brief`) | "Write a paragraph on what this house runs for you, in your own words. The onboarding seat turns it into a tree of parts for you to agree to." | none |

The `house` plan is filed through the ordinary `org_plan` create as the
owner, with no brief, so it seats no onboarding seat until its
`write_brief` seats `onboarding-house`. A card's heading is its cue's
`title`, the Card column above. A card's one button is the
held read's `door` for its row, or, for a row that waits on nobody, its
cue's first named action (`write_brief` on the house card); a step done
outside the engine (Claude, localfire) draws no door button. A required card has no "Not now"; an
optional one may be set aside and brought back.

**R-5.3** No card asks for the repository: the claim does (R-4.6), so
the seed makes the `repo_policy` whole, with `repository` from the
claim and the rest (`required_checks`, `test`, `orientation`) its own,
as today. A `repo_policy`
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

**R-5.3a** The owner gives Claude the address and nothing else: no
client ID and no secret on any card. On a hosted engine the engine
answers Claude's client registration itself (spec-connector-door.md,
"The engine answers Claude's registration"), which needs the per-house
connector client to be public, PKCE S256, exact redirect URI
(`https://claude.ai/api/mcp/auth_callback`), consent on. The hosted
engine's twin of the card (seed.edn's `:hosted` watch and
`setup_cards`) ends with the line that says who to tell when it fails.

**R-5.6** The owner meets their mayor by connecting their Claude: the
Claude they add to this engine as a connector is their mayor, and the
"Connect your Claude" card says so. There is no "Meet your mayor" card,
and no setup card carries a terminal command.

**R-5.7** Removed (2026-11-15): the seed files no first ticket.

**R-5.8** The card steps are new to the seed and marked `:fresh`: an
engine that holds the seed's completion marker skips them on every
load, so an engine seeded before them is given no new row or watch, and
the setup quest and first ticket an older seed made stay as they are.
One seed serves solo and hosted engines.

## 6. Requirements: the guard sentences

**R-6.1** Each setup row's waiting state must be judged by a guard on
the setup quest's goal door (R-7.1), one guard per card, each naming
its row's door as its remedy. The card's text is that guard's
`explain`, so the page, the refusal and the quest's step say the same
sentence.

**R-6.2** The guards read rows and nothing else: `enrolled_at`, the
secret's having a value (never the value), `puller_seen_at` within
three poll windows, the mayor's first sitting. None calls out.

## 7. Requirements: setup is done when "Setting up" holds nothing

**R-7.1** The required watch is the list of setup steps: setup is done
when the owner's "Setting up" watch holds nothing. The seed creates no
setup quest and pins none; the order of the cards is the order of the
watches' cues (docs/spec-watch.md § 3).

**R-7.2** An engine that already has a setup quest keeps it: nothing is
deleted, and the quest is not moved.

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
- A hosted engine's runner needs no person's sign-in (ticket
  9ef6e515): a run a pulled fire starts presents the pull link's
  token at the MCP door in `Waymark-Runner-Token`, and is let in as
  nobody until it sits with its fire's key (`localfire/README.md`).
  Before, a holder of the pull token could pull fires, which carry
  seat keys; now it can also open the MCP door and sit with those
  keys, and nothing more. A solo engine still signs its runs in with
  the owner's delegate headers, which act before the sit; moving it
  to the token too is a later ticket. The `Let seats run` card's
  hosted note stays: nothing here is the owner's to do.
