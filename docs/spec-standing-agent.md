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

### The extension MERGES, and never appends (waymark-ycp)

The loop ate itself twice before this was written down. An anchored
approval used to CONCATENATE the ask's scope onto the grant's, and the
ask copies the grant's scope — so every renewal doubled the list. The
standing composer's grant reached 74 entries for ~20 kinds, with a
filtered `feed.preview_as` spelled several times over, and the ask door
refuses a capability filter-scoped twice ("only ONE entry may filter a
kind"). The next ask was refused; a refused ask is no ask; the leash
lapsed on its own clock with nobody asking (2026-08-28 18:15Z, again
2026-08-29 14:05Z), and every sitting was dark until a person
re-granted by hand.

The ruling, in both halves:

- **The engine.** On approval of an anchored ask the grant's scope
  becomes the ask's scope MERGED PER KIND with the one already stored —
  one entry per kind, actions unioned, ids unioned with openness
  absorbing (an entry naming no ids is the whole kind), `hashed`
  unioned because a tokenised field is never absorbed, and for the two
  narrowings that cannot be unioned — `filter` and `fields` — the last
  entry that SPELLS the key wins while silence INHERITS. A merge writes
  the leash down permanently, so it keeps the narrower reading; an ask
  that means to drop a filter says so out loud with an explicit null.
  The same fold runs on the stored scope, so a grant already carrying
  74 entries collapses to one per kind the first time an approval
  lands: the fix is also the heal.
- **The driver.** It folds the grant's scope the same way BEFORE
  filing, so a not-yet-healed grant still produces a fileable ask —
  keeping `actions` on every entry, empty list and all, because the
  field is required even when empty and a merge that drops it takes a
  422.

### A leash is short — and the ask says so itself

`asks-are-short` refuses anything past the house's grant-max-ttl of 24
hours, so the driver caps what it proposes at 24h minus a minute
(travel time for the request) whatever `WAYMARK_EXTEND_S` says. **The
daily human tap is the law working, not a bug to route around** — every
leash expires, and a person deciding once a day is the whole point of
the leash. What the machine owes is that the ask is always THERE to
tap.

So the ask can never be quiet. `grant_watch` in the manifest carries
`stands` (is an ask of ours open?) beside `inside_window` (does that
matter yet?); when the two disagree the `why` opens with **NO ASK
STANDS — a person must re-grant:** and carries the door's own refusal
sentence, and the run prints that as its FIRST LINE, above the title,
with the instant the grant dies. A refusal nobody read is what turned a
one-line bug into two dark days.

## The sitting rides the pulse

The tick keeps the credential alive; it never thinks. The thinking is
`/sitting` (.claude/skills/sitting/SKILL.md) — the composer contract
as a runnable walk: read the house, answer standing composition
requests, stage every distinct bundle the evidence supports (ranked,
not capped), honor the diagnosis duty, score what it did not write,
journal. Scheduled beside the tick (the skill's own cron pairing),
the two lines are the whole automated composer: outcomes generate on
a cadence, and every one still lands as a proposal a person answers.

## Two runs: the sitting and the reading (waymark-nl0)

The owner's ruling, 2026-08-29, after four Jules/Gemini sittings and
three Fable sittings on the live house: **both models execute
spelled-out orders reliably; only the strong one reads intent behind a
note, checks a question against the record, joins rows nobody pointed
at, does zone arithmetic unaided, and says honestly what it did not
do.** Every wall built on 2026-08-28 had encoded an editor's judgment
as a clerk's form. So there are two runs, two words, two formulas, and
**one driver**.

| | the SITTING | the READING |
| --- | --- | --- |
| the word | the clerk's run — **it fills forms** | the editor's run — **it writes them** |
| the law | `SITTING.md`, `.beads/formulas/sitting.formula.toml`, `/sitting` | `READING.md`, `.beads/formulas/reading.formula.toml`, `/reading` |
| who | Jules/Gemini, queued by `scripts/queue-sitting.sh` on the ten-minute timer | a strong model, locally — `claude -p "/reading"`, via `scripts/queue-reading.sh` |
| when | the timer | morning and evening, or on demand when a sitting has printed a bundle under *Waiting for a reading* |
| the driver | `scripts/sitting-run.sh` (`WAYMARK_RUN=sitting`, the default) | the same, `WAYMARK_RUN=reading` |
| the forms | opens the letters on its own shelf and works their lines ahead of the probes' | writes them: a `notes_for_sittings` block, journaled **and mailed** as one letter to `WAYMARK_SITTING_PRINCIPAL` |

**The label.** The driver labels every order — the probes' and the
owed lists' — by one rule: an order is **`editor`** when its expected
write is an outcome, an unmarked rework, an answer to a person's
question, an extra, or a contradiction between rows; **`clerk`** when
the write is one row at one door with the material inline. Over the
probes that is: an outcome write is the editor's; the two contradiction
probes (`stale-relative-date`, `far-event-names-a-task`, waymark-63s)
are the editor's; an insight or a journal-only skip is a clerk form. A
person's turn is labeled by its shape — a FACT stated is a clerk form,
a QUESTION (a `?`, or an opening *can/could/should/is/what/when…*) is
the editor's, because the answer has to come from the record
(waymark-frv).

**Every person turn, not the last turn** (waymark-3wh). A thread is
read at both ends now: the driver splits its turns into PERSON turns
and AGENT turns — a speaker is an agent when that principal has
composed an outcome, authored an insight or judged a ranking note in
this house, and the owner is a person whatever else is true — and
carries each person turn with the agent turns that followed it. A FACT
is owed while no agent has spoken since it (the old rule, one turn
further in). A **QUESTION is owed until THIS PRINCIPAL answers it**:
another agent's reply is not a check, it is the sentence that has to be
checked, which is a reading's work with the record in front of it. The
thread's shape and label are read off its owed turns, so a thread whose
last word is another agent's is no longer listed as an unanswered FACT
— it was, nine times over, on the morning a person's question sat four
turns up unanswered. (Gemini, 2026-08-29: answered the owner's placard
question falsely and the thread read as *last turn is gemini's — not
owed*.) A remark says who wrote it and not which run wrote it, so under
a single principal a sitting that answered a question it was told to
leave alone would clear it for the reading too — a recorded limit of
merging the two, like the one on `notes_for_sittings`, and not a
licence. A handed-back bundle is a clerk form when the household
MARKED pieces or the note carries a clock time (the suggested re-time
is the form), and the editor's when it is unmarked and unclocked. The
ceiling is applied per label — `WAYMARK_WORK_ORDERS` clerk orders
(2), `WAYMARK_EDITOR_ORDERS` editor orders (3) — so an editor's order
never crowds a clerk's form off the sitting's manifest.

**The sitting's manifest** prints **FORMS FROM THE LAST READING**
first (the letters waiting on this principal's shelf, with the open
door and every `- do:` line as a clerk order — see *The notes cross as
a letter* below), then the clerk orders as *YOUR WORK ORDERS*, the
editor half under **"Waiting for a reading"** (one line each, with why
it is the editor's), and does not count that half as owed. The queued Jules prompt says so in its own words. `verify`
grades a sitting against the sitting's formula: an editor order prints
`WAITING FOR A READING — never a fault against one`; an unmarked,
unclocked rework prints `HANDED BACK, WAITING FOR A READING`; and an
outcome or insight beyond everything the manifest named — its orders,
its owed lists, its arrivals, its bare tasks, its uncited facts — is
`FILLER`, because a sitting has no extra (waymark-kfm: what the
manifest itself asked for is named for what it is, never filler).

**The reading's manifest** opens with **THE HOUSE BRIEF** (waymark-xnf,
built here): mechanical, from rows the house holds, in an order that is
itself the priority (waymark-wfa) — the current people with relation
and age from `born`; the values with the words they love; **the next
thirty days of the calendar**, one line each, the hour converted to the
household's zone with the UTC beside it and any start before 06:00 or
after 22:00 local called **ODD HOUR** in the line; the open threads
with a person turn still owed an answer, quoted, with any agent reply
under it marked unchecked; the last five journals' `notes_for_sittings`
(or their first line); and **last** every published finding, newest
first, grouped by the person, value or list its evidence names.
**`WAYMARK_BRIEF_LINES` (80) trims the findings and nothing else** —
everything above them prints whole however long it runs — and the
findings that only say a row back are FOLDED into one line naming how
many and why: a verdict word repeated as a fact, a cited row's title
with fewer than three words of its own added, or the sentence `verify`
already calls THIN. Nothing is dismissed by the fold; the rows stand at
their own addresses. (Before this, the 80-line cap fell on the calendar
— nine lines, the last five days of the window and both sections under
it — while some 17 of the 40 findings above them were filler.) The
whole brief is `derived/brief.json`. Then both
halves of the orders, labeled; WHAT THE HOUSE ALREADY SAYS under every
unanswered thread and every handed-back bundle (waymark-frv: the
bundle's cited rows with title and detail, starts and ends, name and
relation, and every published finding naming one of them); the
**REVIEW** — the sittings since the last reading on this machine with
the grade lines `verify` filed for each (`grades.txt` in the run dir;
on an ephemeral runner there are none, and the manifest says so), the
forms the last reading left and whether a row now speaks for each, the
five doors a review needs (`insight.dismiss`, `person.dismiss`,
`ranking_note.dismiss`, `outcome.not_this_week` and — since
waymark-hcr, so a dismissal can say why on the record rather than in
the journal — `verdict_reason.create`) and whether the leash admits
them, the THIN findings by other hands as the mechanical
candidates, and the anchored ask body (the grant's scope plus those
doors, on rows by id where the review names them); and the closing
**one extra, or none** paragraph (waymark-mqo). Inside the ask window a
reading's one extend-ask carries the review scope with it — one ask at
a time, the task naming exactly what widens; outside it the reading
files the printed body itself when it holds a row to act on. `verify`
grades a reading against the reading's formula: `EDITOR ORDER …
answered by` / `SKIPPED OUT LOUD` (the journal names it) /
`UNANSWERED AND UNSAID` (the fault, printed, never blocked); `EXTRA:
cited, distinct` or `FILLER` (uncited, a twin, a second one, or one the
journal never explains); `QUESTION CORRECTED` (a question an agent had
already answered and this run checked against the record) / `QUESTION
ANSWERED` / `UNCHECKED QUESTION`, waymark-3wh; `SAYS-SO` for a finding
with no task, event, person, thread or value row behind it; `NOTES FOR
SITTINGS: N form(s) left`.

**What `verify` reads, and how much of it** (waymark-alj). Three of
those grades were lying on the first reading, and all three for the
same kind of reason. The journal text every "was it said out loud"
grade asks was built off `.fields.body` on the JOURNALS COLLECTION,
which is a projection carrying no body at all — so a 13k-character
journal ending in four `notes_for_sittings` forms graded as `NOTES FOR
SITTINGS: none`; the bodies are now read at each row's own address, the
way `hydrate` does on the read side. The written-rows listing asked for
no page size, took the door's default of 25, and reported "31 row(s)
written" on a run that wrote 45; it pages now, to `WAYMARK_MAX_PAGES`.
And an EXTRA was folded into an order because it CITED that order's
subject: an order absorbs only its own **expected write**, so a chat
fact citing the thread an order was about is still the reading's one
extra, while the owed lists (threads, reworks, pulls, declines) keep
matching by citation whatever shape the answer takes.

**Every row, classified** (waymark-kfm, 2026-08-31). The grader knew
two shapes — a row that cited an order's subject, and a row that did
not — so every other kind of work the manifest itself asked for came
out as `FILLER`. That evening the sitting's manifest owed
`advance_arrivals: 7`, `enrich_a_bare_task: 3`, `index_facts: 3`; the
clerk published nine typed insights doing exactly that and read back
seven FILLER lines, and the reading beside it was told its one lawful
extra was a second one because the row it wrote first had enriched the
bare task the manifest named. Both lists were sitting in the manifest
the grade is read from. So `verify` now prints ONE LINE PER ROW beyond
the orders, naming which lawful thing it was, in precedence:
`ORDER-ANSWER` (graded on the order's own line, never repeated),
`FORM-ANSWER`, `FACT INDEXED`, `ARRIVAL ADVANCED`, `ENRICHED`,
`EXTRA`, `FILLER`. The middle four are the assignment and never spend
the reading's one extra; `FILLER` keeps its warning tone and is now
the fault it always claimed to be. Two smaller holes closed with it: a
form whose expected write is a REMARK is answered by the remark —
remarks carry no evidence list, so they are read by `in_reply_to` and
by the row the conversation sits on (2026-08-31, remark `0a134a13`
replying to `f511bd44` on outcome `217f5678`, graded UNANSWERED) — and
a manifest too old to carry one of the owed lists says `CLASSES
UNAVAILABLE` rather than letting the absence read as nothing arrived.
The classification is `scripts/verify-classes.jq`, a pure function
with a fixture of its own: `bash scripts/verify-classes-fixture.sh`,
one synthetic row of each class, no house at all.

**The notes cross as a LETTER** (waymark-bbb). A reading ends its
journal with a `notes_for_sittings` block — one form per line, `- do:
<write> at <door> citing </api/…> — <the sentence>` — and then MAILS
that block. The journal alone cannot deliver it: a journal is
ONE-party own-surface (owner sees own, never grantable), so notes left
there reach only a sitting wearing the reading's own principal, and
the first reading (the claude agent, 2026-08-29) wrote four forms the
gemini sittings they were addressed to could never see. The house
already had the addressed kind for exactly this — `:letter`
(`workqueue10/src/workqueue10/resources/letters.clj`, waymark-tti.3):
TWO-party own-surface, where a row is yours iff you wrote it *or* it
is addressed to you, so writer and addressee each see it with no
grant, a third agent 404s it by construction, and no scope may name
the kind at all. Nothing in the kind forbids an agent writing to
another agent: the create guards are the pace, `letter-author-is-self`
(the engine stamps you as author and refuses any other `owner`) and
`letter-to-is-a-member` (`to` resolves to a delivery identity or is
refused at the door, in one sentence that narrates no roster).

So the reading writes **one letter per run** to
`WAYMARK_SITTING_PRINCIPAL` (default the gemini member
`813b24c2-18f3-481a-b62f-6095cd8a81e8`), body the same `- do:` lines,
title naming the reading — and keeps the block in its journal as its
own record. The driver reads the sitting's own shelf
(`GET /api/letters?to=<me>&state=waiting`, no grant needed), prints it
under **FORMS FROM THE LAST READING** — each letter, its
`POST /api/letters/<id>/-/open` door, and every line rendered as a
clerk order with its own door and cites — and puts the unanswered
lines ahead of the probes' orders. The sitting's formula OPENS each
letter first: opening is the recipient's act alone and the transition
is the only audit the house keeps that the form was read. `verify`
grades both halves, by rows and never by prose: `LETTER <address>:
OPENED` / `STILL WAITING` / `DISCARDED`, and `FORM <letter>
<subject>: answered by <row>` / `SKIPPED OUT LOUD` / `UNANSWERED` —
answered exactly when a row this principal wrote since the mark cites
the addresses the form's line names. On the reading side it says
`LETTER FOR THE SITTINGS: sent — <address>` or `NOT SENT`. A sitting
running under the reading's own principal still reads the journal
block the old way; the two paths agree, and neither is the other's
fallback.

**Kept shared, on purpose:** the walls, the journal, the leash, the
driver. No engine door exists for the reading; everything it does is
an ordinary grantable write, and four eyes hold whatever the grant
says — a row this principal wrote is never its own to dismiss,
whichever run wrote it.

**The cron lines**, beside the tick and the sitting timer:

```cron
*/30 * * * *  WAYMARK_AGENT_STATE=$HOME/.waymark-agent.json /path/to/waymark/scripts/standing-agent-tick.sh
0 6,18 * * *  /path/to/waymark/scripts/queue-reading.sh
```

`queue-reading.sh` is not a Jules queue: it takes a lock, sets
`WAYMARK_RUN=reading`, runs `claude -p "/reading"` in the repo, and
logs to `~/.waymark-reading-cron.log`. The `/reading` skill is
self-sufficient — it runs the driver, reads the manifest, follows
READING.md, and reports with `verify`. By hand, the same line answers
a bundle a sitting left under *Waiting for a reading*.

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
   dowel, glue, saw, workbench — but never a Trello, kanban, Jira,
   scrum or sprint board, because *board* is the shop word that
   leaks), the paperwork hour (form, application, insurance, 401k,
   address, policy, placard, tax, account) — and, only when none of
   those holds two free tasks, a set that shares a task list, under
   the household's own NAME for that list (see *shape first* below).
   Car repairs are in no cluster at all (see *the garage is not an
   hour*). Material: the nearest-due free task, the other free tasks
   the block holds, and the EXCLUDED ones (see below). Expected write:
   an **outcome** whose goal is the session itself, with pieces that
   hold the block on the calendar (`event.create`, the instant already
   converted) and raise each held task into it (`task.prioritize`,
   legal now only while the task is open) — **five pieces at most**
   (see *five pieces is the ceiling*).
2. `commitments-in-messages` — a task or an event that exists only in
   a text or an email (waymark-is7). The owner's ideal outcome,
   2026-08-28: *"digging through old messages to find tasks/calendar
   events."* This one is a structural gap, not a preference: Gate
   material is read-only and `cites-what-it-claims` means an insight
   must name a house address, so a commitment found only in a message
   has exactly ONE lawful landing — a **piece** inside an outcome
   whose evidence cites house rows. The probe reads the last seven
   days of the chosen conversations whole (see *thread selection*,
   below) and asks the inbox for everything `SINCE` that date, keeps
   the lines that say **both** a WHEN and an ASK, drops the ones the
   house already holds, and emits at most ONE order per run.

   **The order anchors on the THREAD ROW** (waymark-36s). Until the
   `:thread` kind existed the chat had no address, so the order
   anchored on whichever companion the traffic happened to name, the
   conversation lived in prose, and when no companion was named there
   was **no order at all** — the fact was simply dropped. Now the
   subject is `/api/threads/<id>` and the evidence is that thread plus
   the person rows it names. The inbox arm keeps the old law exactly
   where it is still the only one: mail is not a conversation this
   house holds a row for, so those lines still anchor on the person
   (only `companion_id` needs affirmation; a person row is a lawful
   evidence address). Either way the message body is never copied into
   a row — the source is named in the prose, *"from the Bros. thread,
   Aug 25"*, and paraphrase is the law.
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

These rules hold the whole thing up:

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
- **Shape first; a list only as a last resort, and only by name**
  (waymark-dgh). A shared task list used to compete with the four
  shapes on size alone, and on 2026-08-28 it won: the order that
  shipped was *"one task list (c07c9da8)"* — a raw id standing in for
  a goal, over a mixed bag of a realtor list, a lapsed life-insurance
  policy, a brake booster, a steering pump and a 401k. A list is a set
  the household already grouped, but the grouping can be a junk
  drawer, and an id is a handle rather than a name, so that is not one
  hour of one shape. The four shapes are therefore ranked AHEAD of
  every same-list cluster: a list cluster ships only when no shape
  cluster has two free tasks in it, and it ships under the
  household's own name for the list, resolved from the `task_lists`
  rows the driver now reads. A list this grant cannot name — the
  `task_list` kind answers the concealment 404 outside a grant that
  admits it — is not a cluster at all, because an hour nobody can say
  out loud is not an hour anyone holds. Within a rank, most free
  tasks wins, then the nearest due.
- **The garage is not an hour** (waymark-dgh). A car repair — a title
  saying *replace / repair / install / fix / swap / bleed / rotate /
  change* against a vehicle word (*Edge, Odyssey, car, van, truck,
  brake, booster, pump, tire, oil, alternator, radiator,
  transmission*) — is a parts-and-lift job that ends when the part
  arrives, not at 11:00. It could have been given a shape of its own,
  a Saturday block in the driveway; it is not, because the held block
  is this probe's whole promise and a two-hour hold on *"replace the
  brake booster"* is a promise the household would decline — and
  folded in beside three phone calls it poisons the hour those three
  would have said yes to. So car repairs are lifted out before
  anything is clustered, and the order's NOTE names the ones it left
  out and why. They stay visible to every other probe as the bare
  tasks they are.
- **Five pieces is the ceiling** (waymark-dgh). The outcome wall takes
  2–5 pieces, and the session order used to prescribe the calendar
  hold plus one `prioritize` per free task — six pieces on a cluster
  of five, refused at the door. The block now holds the hold plus the
  **four nearest-due** free tasks, cites exactly those, and lists the
  rest under *next session* in its NOTE rather than staging them. The
  NOTE says the ceiling in so many words, so a model reading only the
  order knows why the sixth task is named and not held.
- **An arrival is a creation** (waymark-dgh). `advance_arrivals` is
  the list of rows that did not exist at the watermark — a new or
  Gate-synced task, a new event, a person's remark — plus one thing no
  clock can see: a person's turn stays an arrival for as long as it is
  the last word of its thread, whenever it was said, because it is
  still owed an answer. It is read from the engine's own log: a
  collection asked with `?as-of=<instant>` answers the rows that
  existed then (the router's time-travel tier 1), so *now minus then*,
  keyed on `self`, is exactly the set created since. That needs no
  prior snapshot, which is what makes it right on the ephemeral
  runner, and a Gate-synced task new to the house counts by
  construction — the mirror's create IS when the house first saw it,
  and the row is simply absent from the as-of read. Neither available
  alternative works: `meta` carries no `created_at` to compare, and
  `version` cannot stand in for one, because a mirrored row is born at
  version 2 (the create, then the observation) and every synced
  arrival would be missed. Until 2026-08-28 the no-snapshot arm keyed
  on `meta.updated_at`, and a mirror resync bumps every row it
  touches: the evening the owner swept 28 stale offers, the list read
  **291** arrivals on a house where nothing at all had been created
  since the last journal. An as-of read takes no other parameter (not
  even `page[size]` — the log carries state, not data), so it is one
  GET per kind; a refusal falls back to the prior snapshot's
  self-diff, then to nothing for that kind, and the manifest prints
  which basis each kind got, because *"no arrivals"* and *"we could
  not tell"* are different sentences.
- **A conversation that MOVED is the second arm a clock can see**
  (waymark-36s). A thread row is created once and then it moves, so the
  creation diff above can never see one: what makes it an arrival is
  its `last_message_at` crossing the watermark — *"something was said
  in the Bros. thread"*, with a read work order. It sits beside the
  unanswered turn for the same reason that one does: it is still owed
  an answer. A rig that answers no timestamp for its threads (the
  phone's texts do not) can contribute no arrival, and the basis line
  says exactly that rather than reading as quiet.
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
  also carries the last seven days of the chosen threads, because what
  an event needs beforehand is said where a household says it and no
  keyword search sees *"what time tomorrow?"*. Of each week the
  manifest keeps the three messages that say one of the keys, or the
  three most recent when none does.
- **Thread selection is a READ, not a heuristic** (waymark-36s). The
  rule is one sentence: **every `/api/threads` row whose
  `last_message_at` falls inside the seven-day window, ranked newest
  first, capped at `WAYMARK_THREADS` (4), groups included.** Nothing is
  matched against the roster, because a conversation does not need a
  companion to be worth reading — that was the bug: the old heuristic
  took the most recently active 1:1 chat whose title matched a
  companion, read ONE thread out of ten and never a group, which is how
  the Utah Kopsas group carried an unanswered birthday invitation
  straight past a sitting. The confluence's source tag IS the Gate rig's
  prefix, so a row says which rig to ask; the per-chat history tool is
  still the one in `gate.json` whose only required argument is a chat
  id, and the row's title and its rig-local id are both tried because
  which handle a rig answers to is the rig's business.

  A house that does not serve the kind yet degrades to the old
  heuristic and **says so**: the manifest's `conversations.basis` reads
  either `rows — …` or `heuristic (the thread kind is not served yet)
  — …`. "We could not tell" and "there was nothing" are different
  sentences, and a reader must never have to infer which one produced
  the material.
- **A picture is a fact.** Both rigs answer an EMPTY text for a
  media-only message, so a photo used to render as a bare timestamp and
  read as nothing at all. It renders `[picture]` with its date and
  sender — not a body, just the fact that one was sent.
- **Sender ids read back as names.** `tgram__get_messages` answers
  sender ids and no names at all, but a DIRECT thread row's external id
  IS the peer's sender id — so the mirrored rows are the directory the
  rig will not answer. An id the house has no row for stands as the
  number.
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

## The rework order carries a clock, and the note carries times

Two faults found on one bundle, on the evening of 2026-08-29, and both
of them live in the same gap: **a rework order used to hand a small
model a note and expect arithmetic.**

**The clock (waymark-thn).** Handed the order *"add Howie's 11AM
birthday party in Spanish Fork"*, a composer staged
`event.create` at `starts_at 2026-08-29T11:00:00Z` — five in the
morning, Mountain. The manifest said *"times are America/Denver"* in
prose and left the conversion to the model. The commitments probe had
already solved this for its own orders: it prints a **household
clock**, the coming days with a couple of local hours pre-converted,
so a small model PICKS A ROW instead of doing zone arithmetic in its
head. The rework section now prints the same table, from the same
`den_utc` — the zone lives inside the date string, so it is
DST-correct on both sides of the change rather than carrying today's
offset a week forward.

It is **today plus seven days**, at `08:00 09:30 11:00 13:00 14:00
17:00 19:00` local, and it prints **once, at the top of the section**,
with every bundle below pointing at it. The probes' own table starts
*tomorrow*, because every instant a probe prepares has to be ahead of
the run; a rework is usually about a day the household is already
standing in, so this one starts today. Once rather than per bundle
because the table is eight identical lines and the per-bundle text
that has to stay legible is the marks lists. Under it, the rule in one
sentence: *a clock time a person says is LOCAL — write the UTC beside
it from the rows above, and never write the local hour with a Z.*

**The note (waymark-o04).** The marks wall (waymark-wxk) is the
enforced path, and it can only fire on marks. On the same bundle the
household marked **nothing** and wrote three clock times into the
note —

> Howie's Party at 11AM in Spanish Fork
> Wilfred's Party at 1PM in Provo at our home.
> Payson inspection maybe 9:30-10:30?

— and the composer committed a round that staged nothing, withdrew
nothing, and said *"I've added the Payson inspection and Howie's
party, and kept Wilfred's party."* That commit is **lawful**
(waymark-vf8: in an unmarked round, standing by the plan is an
answer), the wall could not fire, and the bundle went back on the
household's feed reading as answered while every hour in it was still
wrong. The promise had become a claim.

So the driver **reads the note**. Every turn on a bundle's thread that
is not the composer's own is split into sentences; each sentence is
scanned for **clock atoms**; and each atom's sentence is matched to a
piece by **shared nouns** — case-folded, four letters or longer,
stopwords and when-words out, so *Howie / party / Spanish / Fork*
matches the Howie piece and *Payson / inspection* matches the Payson
hold. A lone GENERIC word is not a match (every party in the note
would otherwise land on the first party in the plan), most shared
nouns wins, and the longest shared noun breaks the tie. What comes out
is three lists, printed under the rework order beside the marks:

| the sentence matches | and then |
| --- | --- |
| a piece whose hour differs | **SUGGESTED RE-TIME** — the piece, its current local time, the note's time, and the UTC |
| …a piece already **taken** | **SUGGESTED INVOKE** — its row exists; offer that row's own light door, or, when it exposes none, say plainly that the event exists and a **person** moves it |
| no piece at all | **SUGGESTED ADD** — a new piece at that hour |

**They are SUGGESTIONS, not marks.** Nothing here refuses anything —
the marks wall is the enforced path and this is a reading printed
beside it, in the manifest, where the composer can disagree with it.
The sentence that carries the point sits above the list: *a note that
names a time for a piece is a RE-TIME even when nobody tapped Wrong
time.*

Three details that keep the list honest. A phrase whose piece
**already holds the hour prints nothing**, so the list empties itself
as the rework lands. A household that says one thing across three
turns gets **one** suggestion, citing the latest turn. And an
**ADD only fires on a bundle actually handed back**: on an offered
bundle nobody asked for a re-plan, and a time said in passing (*"we
are at the gym from 8:30 to 10:00"*) is a constraint rather than a
request, so there only a time that CONTRADICTS a staged piece is
printed — under its own heading, *Offered, and a time the household
named is STILL UNHELD*, which is exactly the shape a no-change rework
leaves behind.

**A bare number is never a time.** A phrase counts only when it
carries a colon, an `am`/`pm`, or the word *noon* or *midnight* —
otherwise *"Aug 29"* reads as half past eight and every note in the
house grows a clock it never said. A dash or the word *to* between two
atoms makes one phrase, and the second lends the first its meridiem
(*"9 to 11am"*). The DAY is the weekday or month-day the sentence
names, and otherwise **the day the matched piece already sits on** —
a re-time keeps the day unless the note says otherwise, which is what
makes *"at 11AM"* an answer rather than a question.

**`verify` grades all of it, by the rows.**

- `CLAIMED, NOT STAGED: <outcome> — says "<…>" but no piece changed` —
  the revision moved (a round was committed), no piece was staged and
  none withdrawn inside it, and the words posted with it match
  `/\b(added|moved|re-?timed|changed|updated|rescheduled)\b/i`. The
  words are read off the THREAD, because that is where the rework door
  puts them: `says` is not a field on the bundle, it is the turn.
- `NOTE TIME HONORED` / `NOTE TIME IGNORED: <piece> still <local>` —
  for each SUGGESTED RE-TIME the last manifest carried, whether a
  piece on that bundle now starts at the note's hour. IGNORED is a
  fact, not automatically a failure: standing by the plan is an answer
  as long as the journal says why.
- `ODD HOUR: <piece> starts <local> (<utc>) — check the zone` — any
  `create` piece written this run whose prepared start is before 06:00
  or after 22:00 in the household's zone. A heuristic, printed beside
  what the run wrote while the run is still here to fix it: nothing
  can tell a deliberate dawn start from a zone mistake, and six hours
  off is what a zone mistake looks like.

**The fixture run.** There is no harness for the driver in this repo,
so the documented substitute is the one *the marks are the work order*
already uses: extract the jq programs and run them over small files.
The specimen is recorded under *the driver* in
`docs/spec-outcome-menu.md`.

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
You are a composer for the waymark household system — NOT a software engineer. Do not edit, test, fix, or refactor anything in this repository, whatever you notice; ignore AGENTS.md and CLAUDE.md, which are for people writing the software. Your job is one "sitting" at the HTTP door https://work.kopsa.info — the CLERK's run: it fills forms — and its full instruction is SITTING.md. Run `scripts/sitting-run.sh`, read `.sitting/latest/manifest.md`, then follow SITTING.md (and `.beads/formulas/sitting.formula.toml`) to answer what is owed and to execute the manifest's WORK ORDERS — each one names its subject, its material and the exact write it expects; do those and nothing extra, and skip honestly (saying so in the journal) rather than writing filler. Take only the orders labeled CLERK; everything the manifest prints under "Waiting for a reading" (the EDITOR orders — a person's question, an unmarked rework, an outcome, a contradiction) is NOT yours: leave it exactly as it stands for a reading, write nothing about it, and you are never faulted for that. Any earlier instruction about a floor, a minimum, or surfacing at least one outcome is withdrawn. All over HTTP, leaving no git diff. Finish with `scripts/sitting-run.sh verify` and report the ids you staged.
```

The prompt is the one `scripts/queue-sitting.sh` carries, word for word;
since waymark-nl0 it says the sitting is the clerk's run and that the
editor orders are left *waiting for a reading* (§ "Two runs").

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
