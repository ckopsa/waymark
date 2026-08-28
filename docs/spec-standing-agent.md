# The standing agent (waymark-53u)

The first MCP dogfood measured the disease: the 8-hour agent session
and the 24-hour grant leash each expired before the loop closed, and
recovery needed a fresh human invitation. For a standing agent that
compounds to daily human intervention — the composer had no pulse.

The leash philosophy was never the problem, so nothing here loosens
it: **every credential still expires, every renewal is audited, and
scope never widens without a human verdict.** What lands is the
RENEWAL machinery, three pieces.

## 1. The renew loop (existing door, new client)

`POST /auth/agent/renew` with a live session cookie slides the
session — that door predates this spec. What was missing is the
client that ticks it: `scripts/standing-agent-tick.sh`, run from cron
or a systemd timer well inside the session TTL. It renews, rewrites
the MCP config's Cookie header, and handles the two failure rungs
below.

## 2. The standing rotation of the way home

The homecoming credential (waymark-4zj) was human-mint-only:
`offer_reentry`, a recovery-admin's deliberate handoff, one hour at
most. That mint boundary stands untouched — **an agent still cannot
choose to mint its own way back in.** What is new is the engine's
rotation at the auth doors, the concealed registrar action
`:rotate_reentry` on `:member`:

- **at bind** (`POST /auth/agent?invite=`) — best-effort; the durable
  guard refuses guest rows, so only an IdP-backed agent walks away
  holding a way home;
- **at homecoming** (`POST /auth/agent` with the token in the body) —
  the spend nulls the presented token, the rotation mints its
  replacement, and the loop closes with no human hand;
- **at renew** — only past half-life, so an hourly tick writes the
  member row every few days, not every tick.

Why this is not self-escalation: the engine rotates only at the
moment the agent presents a still-live credential whose chain of
custody began in a human act (the invitation, or a recovery-admin's
hand), and each mint *replaces* the prior token — at most one live
credential per member, so rotation renews what a human handed out
and never widens it. The standing TTL is seven days
(`reentry-standing-ttl-seconds`), its own constant beside the
one-hour handoff ceiling: a handoff is a moment, a standing
credential is a season. Suspend still revokes; the raw token still
never persists outside the row's `:secret` field; each mint is one
audited transition row.

The bootstrap ceremony, once: a recovery-admin offers re-entry to the
agent's durable member row (`offer_reentry`, the existing door); the
agent comes home through it and rotation self-sustains from there.

## 3. The anchored extend-ask, filed before expiry

"An approved follow-up ask can always extend" was already the grants
machine's law — an anchored `approval_request` (grant_id names a
grant its requester holds) extends that grant in place on approval.
Nothing filed it. Now the tick does: inside the ask window
(default 12h before grant expiry), with no prior ask of its own still
open, the agent files the anchored ask — same scope, more time — and
the human's tap moves from "re-invite the agent" to "approve the ask
in the feed". The asking door was always open to a named principal;
scope still only widens through a human verdict.

## The sitting rides the pulse

The tick keeps the credential alive; it never thinks. The thinking is
`/sitting` (.claude/skills/sitting/SKILL.md) — the composer contract
as a runnable walk: read the house, answer standing composition
requests, stage every distinct bundle the evidence supports (ranked,
not capped), honor the diagnosis duty, score what it did not write,
journal. Scheduled beside the tick (the skill's own cron pairing),
the two lines are the whole automated composer: outcomes generate on
a cadence, and every one still lands as a proposal a person answers.

## The bearer-only runner (Jules and kin)

A cloud runner has no cron, no state file and no session cookie — it
has an *environment* and one hour of life. So the tick's five rungs
collapse to two, and both live in `scripts/sitting-run.sh`:

- **The credential is minted, used and dropped.** No renew, no
  re-entry, no rotation: `scripts/agent-bearer.sh` turns the two
  Keycloak env vars into a 1-hour bearer at run start, the run spends
  it, and it dies. There is nothing to keep alive between runs
  because nothing persists between runs.
- **The leash is still watched.** Rung 4 survives whole: the driver
  reads the grant, and inside the ask window (12h by default) with no
  ask of its own still open, it files the same anchored
  `approval_request` in the same words — `{grant_id, task, scope,
  expires_at}`, the scope copied off the grant so nothing widens. The
  human's tap is still what extends it. Verified against the engine
  2026-08-27: ask `feb7912e` was accepted from a bearer-only agent
  and a second run filed nothing, finding the first still offered.

The rest of what the driver does is not leash work at all — it is the
sitting's *reading*, done mechanically so the model spends its
attention on judgment. It writes a snapshot and one manifest; a
`verify` pass afterwards reports what the principal actually wrote,
so a run's success is observable from outside the run. That last part
is the whole point: two Jules sittings had already "completed" while
leaving zero rows on the engine, and nothing in the loop noticed.

## The composer probes — a work order, not a candidate set

The owner's observation, 2026-08-27: **the weaker the model, the more
direction it needs.** Handed the same manifest, Fable read a long
candidate list and picked well; Gemini wrote filler. A candidate set
asks the model to CHOOSE; a work order asks it to EXECUTE. So the
driver moves the line one notch further toward the machine
(waymark-48a).

A **probe** is a deterministic query over the run's snapshot plus
whatever material it pre-fetches, emitting **at most one work order**.
The set is CLOSED — the same shape as the feed's `populations`
(`waymark10.server.feed`), a map a reviewer reads on one screen rather
than a discovered registry that would behave differently on two
machines. Today it holds six, and the order below is also the
tie-break:

1. `session-of-like-tasks` — two or more *free* open tasks of one
   shape, gathered into one held block (waymark-q23). The owner's
   ideal outcome, 2026-08-28: *"group like tasks into a work session
   and suggest I schedule it."* Every other probe is single-subject
   and the ceiling text forbids extras, so nothing ever batched. The
   shapes are the four batches the household's own values name — the
   phone calls (a title beginning *Call / Contact / Phone / Ring*, or
   one carrying a phone number), the errand loop (*Pick up / Drop off
   / Mail / Buy / Return / Go to*), the shop (woodworking, boards,
   dowel, glue, saw, workbench), the paperwork hour (form,
   application, insurance, 401k, address, policy, placard, tax,
   account) — plus, purely mechanically, every set that shares a task
   list, because a task list is a set the household already grouped.
   Material: the nearest-due free task, the other free tasks beside
   it, and the EXCLUDED ones (see below). Expected write: an
   **outcome** whose goal is the session itself, with pieces that hold
   the block on the calendar (`event.create`, the instant already
   converted) and raise each free task into it (`task.prioritize`,
   legal now only while the task is open).
2. `commitments-in-messages` — a task or an event that exists only in
   a text or an email (waymark-is7). The owner's ideal outcome,
   2026-08-28: *"digging through old messages to find tasks/calendar
   events."* This one is a structural gap, not a preference: Gate
   material is read-only and `cites-what-it-claims` means an insight
   must name a house address, so a commitment found only in a message
   has exactly ONE lawful landing — a **piece** inside an outcome
   whose evidence cites house rows. The person who said it is such a
   row (only `companion_id` needs affirmation; a person row is a
   lawful evidence address), and the source is named in the prose —
   *"from Wellesley's text of Aug 27"* — never copied into a row. The
   probe reads the last seven days of the household thread whole and
   asks the inbox for everything `SINCE` that date, keeps the lines
   that say **both** a WHEN and an ASK, drops the ones the house
   already holds, and emits at most ONE order per run.
3. `bare-task-due-soon` — an open, detail-less task no outcome or
   insight speaks for, nearest `due_at` first. Material: the task's
   full row, the open tasks sharing its `task_list` or assignee, and
   Gate hits on the task's **search keys**. Expected write: an
   **insight** citing the task, offering `complete` on it.
4. `event-without-prep` — an event starting inside ten days that no
   outcome and no insight cites. Material: the event row, open tasks
   whose title carries a word of its title, Gate hits on the event's
   **search keys**, and the **last seven days of the household
   thread**. Expected write: an **insight** offering `complete` on
   that task when one exists; when none does, an event admits no door
   a card can tap, so the order asks for an **outcome** whose pieces
   create the prep — but only when a live value actually fits it, and
   otherwise for the journal sentence alone (see *value-fit* below).
5. `person-mentioned-unrecorded` — a roster companion (affirmed /
   `current`; an observed person is not usable) whom the last seven
   days of Gate traffic names and no insight cites in that window.
   Material: the person row and the hits. Expected write: an
   **insight** stating the fact, offering `still_with_us`.
6. `value-with-no-live-outcome` — a live value no offered or accepted
   outcome serves. Material: the value, and the open tasks and coming
   events that say one of the value's own words. Expected write: an
   **outcome** when there is such a row; when there is none, the value
   carries nothing live and the order is the journal sentence instead.

The two new probes go FIRST, and rank only breaks a tie — a date
always outranks it, so an event today still ships ahead of a session
next Saturday. What the order settles is what wins when two probes
carry the same clock, and there the answer is the one that clears more
of the house: a session finishes four tasks where `bare-task-due-soon`
annotates one, and a commitment nobody wrote down is the only kind of
subject invisible to every other probe — it has no row yet to be found
by. (A person's pull and a person's turn still outrank all six: those
are owed lists, not probes, and `SITTING.md` puts them first.)

Nine rules hold the whole thing up:

- **Machine dedupe first.** Every probe drops any subject the cited
  set already names. The `not-a-twin` door would refuse a duplicate
  outcome at staging anyway, but a refusal costs a round trip and
  teaches the model nothing; the list it is handed is clean before it
  reads it.
- **Clusters are seen whole; orders are built from what is free**
  (waymark-q23). Dedupe applied before the clustering would also hide
  the cluster — a house whose phone calls are all spoken for would
  look like a house with no phone calls in it. So `session-of-like-
  tasks` forms its clusters over every open task and then splits them:
  the free ones become the evidence and the pieces, the cited ones
  ride the order as **EXCLUDED**, each printed beside the standing
  bundle that already speaks for it and the reason (*"citing it too
  would be the twin `not-a-twin` refuses"*). A cluster needs two FREE
  tasks to be worth an hour.
- **The crowd-out line** (waymark-q23). Machine dedupe is right and it
  can also leave nothing to do: on 2026-08-28 thirty-one standing
  offered bundles cited every one of the sixteen open tasks, so no
  session could ship and no new bundle could cite anything. That is
  not a fault in a probe, it is a fact about the fridge, and a
  manifest that only said *"no work orders"* would leave a run unable
  to tell a quiet house from a jammed one. So whenever standing
  offered outcomes speak for **half the open tasks or more**, the
  manifest says it with the count — *"the fridge is crowding out
  composition: 32 standing offered outcomes cite 15/16 open tasks, so
  machine dedupe frees nothing…"* — and adds who can fix it, which is
  not us: a person's verdicts on those offers are what free the tasks
  again. `verify` reprints the same line off the manifest the run was
  handed, so a run that wrote nothing is read against the reason.
- **Every prepared instant is a household wall clock, rendered UTC**
  (waymark-q23, waymark-is7). The house keeps `America/Denver` time.
  *Saturday morning* is 09:00 there, which is 15:00Z in August and
  16:00Z in November, so a probe that shipped a bare `09:00Z` would be
  holding a block at three in the morning — and a model handed a bare
  hour would have to do the arithmetic itself. The driver asks `date`
  for the conversion with the zone INSIDE the string, which is
  DST-correct on both sides of the change rather than carrying today's
  offset a week forward, and it checks that what came back is ahead of
  the run before shipping it. A session order carries its block
  already converted; a commitments order carries the **household
  clock** — the next seven local days, each with a 9am and a 7pm start
  already rendered UTC — so a small model picks a row from a table
  instead of doing timezone arithmetic.
- **A ceiling.** The manifest presents the top
  `WAYMARK_WORK_ORDERS` (default 2), ordered by urgency — soonest due
  or soonest starting first, then probe order. Anything past them is
  optional, a run writes only what its orders and the owed lists name,
  and a run with no orders and nothing owed writes nothing at all:
  still a lawful run (waymark-mho).
- **Gate is read-only and never fatal.** Material comes from the one
  search tool each *answering* rig exposes that takes a free-text
  `query` and requires nothing else — read off `gate.json`, because
  Gate's tool list is an aggregation that changes without telling us.
  A rig that refuses contributes its SENTENCE instead of its hits, and
  the manifest says so, because a refusal is what the model must
  report in place of filler. Hits are capped at three per rig and cut
  at 200 characters, marked *material, not an address*: a message body
  is never copied into a row and a Gate hit is never cited.
  `WAYMARK_NO_GATE_PROBE=1` skips all of it.
- **Search the short key, not the title** (waymark-jux). The first real
  work order queried both rigs with the whole event title —
  *"Breakfast with Kev Gallagher"* — and got nothing from either;
  *"Gallagher"* alone found the friend who moved here in 2024 and the
  breakfast before this one. A title is a household's own sentence,
  not a search term, so the keys are DERIVED from it: the capitalized
  tokens that survive the stopword list and the generic calendar words
  (*Breakfast, Meeting, Call, Appointment…*), a **surname first** — the
  second of two adjacent capitalized tokens — then the rest
  longest-first; and when a line carries no name at all, the line minus
  stopwords. Keys of three letters or fewer are dropped whenever the
  line offers a longer one, because IMAP `TEXT` search is substring
  search and *"Kev"* answered 248 messages — the whole mailbox, dressed
  as material. At most **two keys**, tried in that order, and the
  second one costs a call only on the rigs where the first came back
  empty, which keeps the three-hits-per-rig cap exactly where it was.
  The manifest prints what was searched (`searched "Gallagher"`), so a
  fruitless probe is legible rather than mysterious. An **event** order
  also carries the last seven days of ONE named thread, because what an
  event needs beforehand is said where a household says it and no
  keyword search sees *"what time tomorrow?"*: the per-chat history
  tool is the one in `gate.json` whose only required argument is a chat
  id, its listing partner is the argument-free tool on the same rig
  that lists chats, and the chat is a roster companion's thread when
  the roster names one, else the most recently active thread that is
  not a bot. Of that week the manifest keeps the three messages that
  say one of the keys, or the three most recent when none does. No such
  pair of tools means no thread material, which is not a fault.
- **Value-fit before an outcome is ordered** (waymark-jux). The same
  first work order demanded *"one OUTCOME naming a value from the live
  list"* as though every event maps onto some live value; a friend's
  breakfast served none of the house's four, and the only honest move
  left was the escape hatch — which a weaker model would not have
  taken. So fit is tested MECHANICALLY before an outcome is asked for.
  A value owns the words of its **name**, of every activity it
  **loves**, and the six-letter-and-longer words of what it **says**
  (prose filler is short: a value whose `says` reads *"a long healthy
  life … the God of War game"* otherwise owns *long* and *game*, and
  matched a woodworking task on *long*). A value FITS a subject when it
  owns a non-stopword word of four letters or more that the subject's
  own title, location or material says. For `event-without-prep` the
  order then names that value in so many words — `value:
  /api/values/… (Making and building) — matched on "woodworking"` —
  and for `value-with-no-live-outcome` the fit runs the other way: the
  material is the open tasks and coming events that say one of the
  value's words (a task's TITLE decides the match; a paragraph of
  detail shares a word with everything), and a value with no such row
  carries nothing live. When nothing fits, the order's WRITE block
  becomes **journal-only**: *"No live value carries this. Write nothing
  at the outcome door; in the journal say what value this would need,
  in one sentence — that skip IS the answer."* The escape hatch stays
  beside it, because an event that honestly needs nothing prepared is
  the same answer. `verify` prints such an order as `JOURNAL-ONLY`
  rather than `UNANSWERED`: no door write was ever asked for.
- **An observed value is named as observed.** `outcome/names-a-value`
  holds `observed` and `declared` alike and refuses only `retired`,
  and the crown ranks an observed value LOWER rather than turning the
  bundle away — so value-fit considers observed values too. What it
  must not do is let one pass silently as something the household said
  in so many words, so an order landing on one says which it is in the
  same sentence that names it, and asks the goal to own that it serves
  a reading of this household rather than a word the household gave.
- **A message body never becomes a row.** The commitments probe uses
  read tools only. Its hits are capped and trimmed to the same 200
  characters every other Gate hit is, the piece title PARAPHRASES, the
  evidence is the person row (plus any house row the model actually
  read), and the source is named in prose. Candidates are deduped
  against the house by **key word and date**: a line sharing a
  distinctive word with an open task or an event in the window around
  now is the household talking over a row that exists, and so is a
  line naming a day an event already sits on. The match reads the
  message BODY rather than the rendered line — the line carries
  whatever timestamp the rig printed in front of it, and a date is not
  a subject — and ignores bare figures, because a mirror appends the
  date to `display.title` and *"2026"* is a word every row in the
  house shares.
- **`verify` grades the orders.** Next run it prints one line per
  order from the previous manifest — `ORDER <probe> <subject>:
  answered by <row>` or `UNANSWERED` — where *answered* means a row
  this principal wrote since the mark CITES the subject's address.
  That is a fact about evidence, which a script can check and a report
  cannot fake. An UNANSWERED order is lawful; an UNANSWERED order
  beside a journal that never mentions it is a run that ignored its
  assignment.

**Adding a seventh probe** means adding it in two places at once, the
way a feed population is added: the probe itself in
`scripts/sitting-run.sh` (a `jq` block appending one candidate object
to `$CAND`, carrying `probe`, `rank`, `subject`, `subject_says`,
`urgency_at`, `urgency_says`, `why`, `gate_keys`, `material` and
`write`), and its entry in the closed list in the block's own header
comment. Give it the next `rank`; leave `material.gate` null and set
`gate_keys` (`<a line> | wm_keys`) if it wants Gate material, plus
`gate_thread: true` for the household thread, and the ceiling will
fetch both only when the order actually ships. `wm_value_fit(<the
subject's words>; $values)` is there when the write it expects is an
outcome, and `wm_value_note(<the fit>)` renders the sentence that
names it (and says so when it is observed). Nothing else changes: the
render and the grading are generic over the shape, and a `write.kind`
of `journal` renders and grades as journal-only. Three optional keys
the render already knows: `material.excluded` (rows machine dedupe
took out of reach, each with the bundle that cites it),
`material.day_table` (the household clock), and `write.pieces` (the
`outcome_piece` bodies, printed as JSON ready to POST).

## Running a sitting on Jules

Everything below is set once, by the owner, in the Jules web UI — the
`jules` CLI can queue a session but cannot set environment variables.

**Environment variables** (Environment → Variables):

| name | value |
|---|---|
| `WAYMARK_KC_CLIENT_ID` | `waymark10-agent-gemini` |
| `WAYMARK_KC_CLIENT_SECRET` | the client's secret — `.waymark10_agent_clients.gemini` in the infra repo's `terraform/secrets.local.json`. Paste the raw value: no quotes, no trailing newline. |
| `WAYMARK_GRANT_ID` | the accepted grant whose audience is that agent's member id |
| `WAYMARK_BASE_URL` | optional; `https://work.kopsa.info` is the default |

The secret is the rung that has actually broken. A wrong or stale
paste refuses at the mint with *"Invalid client or Invalid client
credentials"*, the sitting reaches nothing, and the session still
reports itself complete — which is exactly why
`scripts/jules-setup.sh` now ends with a credential check that mints
a bearer, opens `/api/-/welcome` and shouts `SETUP FAILURE` with the
refusal sentence. Read the setup log.

**Setup script** (Environment → Setup script):

```bash
bash scripts/jules-setup.sh
```

It installs `jq`/`curl` if the image lacks them (a fresh Ubuntu Jules
VM has jq, curl, bash 5.2 and go; it does **not** have bd), builds bd
from source once into the snapshot, and never fails the build — bd is
optional, and a run without it reads
`.beads/formulas/sitting.formula.toml` instead.

**The queued prompt** carries the whole framing — it does not lean on
`AGENTS.md`, which is now the repository's *engineering* doc and would
tell a coding agent the wrong thing. The prompt says what the run is
NOT before what it is (a coding agent plans from the prompt before it
reads any file, and one handed a repository will start fixing what it
notices — sitting 5, 2026-08-27, renamed a test kind instead of
sitting), and it names `SITTING.md` as the law:

```
You are a composer for the waymark household system — NOT a software engineer. Do not edit, test, fix, or refactor anything in this repository, whatever you notice; ignore AGENTS.md and CLAUDE.md, which are for people writing the software. Your job is one "sitting" at the HTTP door https://work.kopsa.info, and its full instruction is SITTING.md. Run `scripts/sitting-run.sh`, read `.sitting/latest/manifest.md`, then follow SITTING.md (and `.beads/formulas/sitting.formula.toml`) to answer what is owed and to execute the manifest's WORK ORDERS — each one names its subject, its material and the exact write it expects; do those and nothing extra, and skip honestly (saying so in the journal) rather than writing filler. Any earlier instruction about a floor, a minimum, or surfacing at least one outcome is withdrawn. All over HTTP, leaving no git diff. Finish with `scripts/sitting-run.sh verify` and report the ids you staged.
```

`SITTING.md`'s first instruction is `scripts/sitting-run.sh`, so the
session mints, reads the house, and arrives at its judgment with the
manifest in hand.

**What a Jules session carries in from the last one** (checked
2026-08-28, after a session journaled *"To satisfy the floor
requirement…"* against texts that had said the opposite for a day —
waymark-mbq). Almost nothing we can see, and nothing we can set. The
CLI's whole surface is `login`/`logout`, `new`, `remote new|list|
pull` and `teleport`; `jules new` takes `--repo` and `--parallel` and
no flag that attaches memory, a prior session, or extra context, and
`remote list --session` shows only id, first line of the prompt,
repo, age and status. On the queuing machine `~/.jules/` holds one
file, `cache/cli.log` — no token-scoped memory, no per-repo state —
and there is no `.jules/` anywhere in this repository; the only file
Jules opens on its own initiative is `AGENTS.md`, which is why that
file leads with a banner sending a sitting to `SITTING.md` instead.
What genuinely persists is the environment the owner set once in the
web UI: the variables above, and the VM snapshot
`scripts/jules-setup.sh` builds (bd compiled in once). Everything
else about a session comes from a fresh clone at the branch tip plus
the queued prompt — which means the stale wording cannot have come
from a repository file at that session's commit. That leaves the
model's own priors, or session memory held on Google's side that the
CLI can neither read nor clear. Since we cannot inspect or clear it
from here, the prompt now retracts the old rule in its own words —
*"Any earlier instruction about a floor, a minimum, or surfacing at
least one outcome is withdrawn."* — and the texts themselves no
longer contain a noun for a weaker model to keep after dropping the
negation.

## What "done" looks like (the acceptance)

A standing agent survives a week without human re-invitation while
every grant still expires on its leash and every renewal is audited.
The failure rungs stay honest: a lapsed session falls back to the
re-entry token; a spent-or-dead token stops the loop and says a human
must re-invite — the script never knocks on its own.
