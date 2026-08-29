---
name: reading
description: Run one editor's READING of the waymark house — the strong model's run beside the clerk's sitting. Runs the driver in reading mode, reads the house brief first, answers a person's questions from the record, reworks the unmarked bundles, composes only where a goal is larger than any row, allows itself ONE cited extra, reads the contradictions between rows, reviews the sittings since the last reading (grade lines, scores, dismissals under a grant), and leaves notes_for_sittings as forms — journaled AND mailed to the sitting principal as a letter, because the journal is private to its own member. Use when asked to "run a reading", "read the house", "review the sittings", or on the morning/evening reading cron.
---

# The reading

One editor's reading, at the HTTP door, under the composer contract
(docs/spec-outcome-menu.md § "The composer contract"). The law is
`READING.md`, which stands on `SITTING.md`: same door, same walls,
same leash, same journal, same driver — a different set of duties.
**A sitting fills forms; a reading writes them.** This skill is
self-sufficient: it runs the driver, reads the manifest, follows
READING.md, and reports with `verify`.

This is not a coding session. Edit, fix, test or refactor nothing in
this repository; the work is HTTP writes to the house. Act — do not
ask a human whether to proceed. Scratch goes under `.sitting/`
(gitignored) or `/tmp`. Report by posting the journal and pasting
`scripts/sitting-run.sh verify`.

## 0. The driver, in reading mode

```bash
export PATH="$PATH:$HOME/go/bin"
WAYMARK_RUN=reading scripts/sitting-run.sh
```

Credentials are the sitting's (`WAYMARK_AGENT` or the two Keycloak
env vars, plus `WAYMARK_GRANT_ID`); the bearer lands at
`.sitting/latest/bearer` and every request wears it with
`X-Waymark-Grant`. If the driver stops, stop with it and report its
sentence. Then read `.sitting/latest/manifest.md` top to bottom — the
brief first, then the orders, then the rest.

## 1. The brief, whole

The manifest opens with THE HOUSE BRIEF: the current people with
relation and age, the values with the words they love, every published
finding grouped by who or what it names, the next thirty days as one
list, the open threads the owner spoke in, the last five journals'
notes. It is mechanical and capped (`WAYMARK_BRIEF_LINES`), and the
manifest says what was cut; the whole thing is
`derived/brief.json`. Read it before any order — the far appointment,
the booked day, the person who left are here and nowhere else.

## 2. The orders, both labels

Every order is labeled. **CLERK** — one row at one door, the material
inline: take it exactly as written (an insight enriching a bare task,
a journal-only skip, a form a prior reading left under
`notes-for-sittings`). **EDITOR** — the write is an outcome, an
unmarked rework, an answer to a person's question, an extra, or a
contradiction between rows: yours, and yours alone. Do what is owed
first (a person's pull, a person's turn), then the clerk forms, then
the editor orders. An editor order the rows do not carry is skipped
**and said so in the journal** — `verify` prints `UNANSWERED AND
UNSAID` against one that got neither.

## 3. A question is answered from the record

Under every thread whose last turn is a person's, the manifest prints
WHAT THE HOUSE ALREADY SAYS — the rows the bundle cites (title and
detail, starts and ends, name and relation) and every published
finding naming one of them, with addresses. A FACT the person stated
is a clerk's form (index it, reply). A QUESTION is answered FROM those
rows with a reply remark (`in_reply_to` naming their turn): cite the
row that answers it; if the rows contradict the person, say which and
quote it; if they are silent, say so and what would settle it. Never
index a question as a fact (`SAYS-SO`). On a bundle of yours in
`iterating`, the reply door is closed and `says` on the rework is your
turn.

## 4. The unmarked rework

A handed-back bundle with no mark and no clock time in the note is
labeled EDITOR and listed under *Handed back for a rework* with the
note quoted and the house's own rows beneath it. Read the note against
the pieces: withdraw (`outcome_pieces/<id>/-/rework`), stage the
replacement pieces (every instant from the clock table, UTC beside the
local hour, never the local hour with a Z), commit
(`outcomes/<id>/-/rework {says}`). A no-change round is a lawful answer
when the plan honestly stands; a no-change round whose `says` claims a
change is `CLAIMED, NOT STAGED`. Leaving it in `iterating` is the one
wrong answer.

## 5. Compose only for a real goal; ONE extra, or none

Stage an outcome only where the rows imply an end-state larger than
any single row — the editor orders that expect one, or a goal the
brief implies that no probe reached. Wrapper, twin and dead-book
bundles are refused at the door; a goal the rows do not carry is a
skip said out loud. Then the reading's one freedom (waymark-mqo): ONE
row the manifest did not order, cited to what you read, distinct from
what stands, with a sentence in the journal on why — or none, said on
purpose. `verify` grades it `EXTRA: cited, distinct` or `FILLER`.

## 6. Contradictions

Two are mechanical editor orders on the manifest —
`stale-relative-date`, `far-event-names-a-task` — each expecting an
insight citing both rows. Two you read from the brief: a booked day
with no event on it (an outcome whose pieces create the missing
events, and a remark on any bundle holding that day), and two task
details that cancel each other (an insight offering the moot task's
`complete`). Never hold a block on a day the record calls booked.

## 7. Diagnoses

As in a sitting (waymark-me9): only a prior about to be recomposed —
the manifest's *Declines OWED a diagnosis* list — one each, never
twice, the finding saying what changes because of the decline.

## 8. The review

The REVIEW section lists the sittings since the last reading with
their `verify` grade lines (or says there are none on this machine —
an ephemeral runner keeps nothing). Read them for what the clerk got
wrong; say it in the journal. Then **score** every standing bundle you
did not write (`ranking_note`: score 0–1, one sentence ≤240 chars on
what the bundle stands on, evidence = the whole cite list). Then
**dismiss** thin or false rows where your grant admits the door: the
manifest names the five doors a review needs (`insight.dismiss`,
`person.dismiss`, `ranking_note.dismiss`, `outcome.not_this_week`,
`verdict_reason.create`), whether the leash admits each, the THIN
findings by other hands, and the anchored ask body. **File the word
with the dismissal** (waymark-hcr): `POST /api/verdict_reasons` with
`subject_kind`, `subject_id`, `subject_href`, `about`, `verdict`
`"dismiss"` and one word — a finding or a judgment takes the words a
CLAIM runs along (`thin`, `unfounded`, `restated`, `untrue`), never
the four about something the house was offered, and `wrong_time` on a
finding is refused by name. The reason belongs on the row, never only
in the journal: the rank reads it, and so does the next reading. Inside the ask window the driver filed it as
the one extend-ask (see `grant_watch`); otherwise file it yourself
(`POST /api/approval_requests`) only when you hold a row to act on and
no ask of yours stands, and report the ask id. Four eyes: a row this
principal wrote is never yours to dismiss, whichever run wrote it.

## 9. The journal, ending in notes_for_sittings

One entry (`POST /api/journals`): what was written, by id; what was
not, and why; the extra and why it was worth a row, or that there was
none; the review paragraph. Then end the body with:

```
## notes_for_sittings
- do: publish an INSIGHT at POST /api/insights citing /api/tasks/<id> and /api/events/<id> — <the complete sentence>
- do: reply with a REMARK at POST /api/remarks on outcome/<id> (in_reply_to /api/remarks/<id>) — <the words>
```

Forms, not thoughts — one row, one door, every address one the house
serves.

## 10. Mail the block — the journal does not deliver it (waymark-bbb)

A journal is one-party own-surface: private to its own member, never
grantable. The sittings run under another principal and never see it.
So send the same lines as ONE letter, to the address the driver
prints (`WAYMARK_SITTING_PRINCIPAL`):

```
POST /api/letters
{"to": "<the sitting principal>",
 "title": "Forms from the reading of <date>",
 "body": "<the same `- do:` lines, verbatim>"}
```

No grant scope names `:letter` and none can — a letter is two-party
own-surface, so writer and addressee each see it with no grant. Supply
no `owner`; the door signs it in your own name and refuses any other.
Once sent it cannot be edited or taken back. The next sitting's
manifest prints it under **FORMS FROM THE LAST READING**, each line a
clerk order ahead of the probes'; the sitting opens the letter first,
because the open transition is the audit that the form was read. The
journal keeps the block as this run's own record.

Then `scripts/sitting-run.sh verify`, and paste it.

## Scheduling

A reading runs where a strong model runs — locally, not on Jules.
Beside the sitting timer (`scripts/queue-sitting.sh`, every ten
minutes) the reading is two cron lines, or `scripts/queue-reading.sh`
as the cron target:

```cron
0 6,18 * * *  cd /path/to/waymark && WAYMARK_RUN=reading claude -p "/reading"
```

and on demand — `claude -p "/reading"` — when a bundle is handed back
unmarked and a sitting has printed it under *Waiting for a reading*.
This skill never commits, pushes, or touches beads outside the wisp.
