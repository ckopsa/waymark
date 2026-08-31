# The reading — the editor's run beside the clerk's sitting

> **You were pointed here by your run's prompt, not by finding this
> file.** This is the law of one *reading* — a composer's visit to the
> waymark house over HTTP, the same visit `SITTING.md` describes,
> with a different set of duties. Everything SITTING.md says about
> the door, the driver, the walls, the leash and the journal holds
> here word for word; this file says only what a reading does that a
> sitting does not. If you are here to write or fix the software, you
> want `AGENTS.md` and `CLAUDE.md`. If you are here to run a reading,
> read on and touch no file in this repository.

**A sitting fills forms; a reading writes them.** The evidence that
split the two (2026-08-28/29, on the live house): both models execute
a spelled-out order reliably; only the strong one reads the intent
behind a note, checks a person's question against the record, joins
rows nobody pointed at, does zone arithmetic unaided, and says
honestly what it did not do. Every wall built that day had encoded an
editor's judgment as a clerk's form. So the runs are two:

| | the SITTING (the clerk) | the READING (the editor) |
| --- | --- | --- |
| who | a small model on the timer (Jules/Gemini, every ten minutes) | a strong model, locally (`claude -p "/reading"`), morning and evening, or on demand when a bundle is handed back unmarked |
| takes | the **clerk** orders: one row at one door, the material inline | the clerk orders **and** the **editor** orders |
| a person's turn | a FACT stated: index it, reply | a QUESTION: answered **from the record**, never from the question |
| a rework | MARKED pieces, and a clock time in the note (the suggested re-time) | UNMARKED, the note read against the pieces |
| an outcome | never composes; a form that would need one is the editor's | composes only where a goal is larger than any row |
| extras | none — the orders are the ceiling | **one** cited, distinct finding of its own, or none |
| contradictions | none | the four 63s shapes, two of them mechanical orders |
| review | none | the sittings since the last reading: their grade lines, the bundles they left unscored, the thin rows |
| leaves | a journal | a journal ending in **`notes_for_sittings`** — forms the next sittings work from — **and one LETTER carrying that block to the sitting principal** |

**Same walls, same leash, same driver.** No engine door exists for a
reading; everything it does is an ordinary grantable write.

**The journal is not the delivery** (waymark-bbb). A journal is
ONE-party own-surface — private to its own member, never grantable —
so notes left there and nowhere else reach only a sitting wearing the
reading's own principal. The gemini sittings, the runs the forms are
FOR, never saw one word of the first reading's block. So a reading
also MAILS it: one `:letter` to the sitting principal, body the same
`- do:` lines. A letter is TWO-party own-surface — writer and
addressee each see it with no grant, a third agent 404s it — so the
sitting reads its own shelf with the leash it already holds and OPENS
each letter before working it; the open transition is the audit that
the form was read. The journal keeps the block too: that is the
reading's own record, and the letter is the delivery.

## Step one, every reading: the driver, in reading mode

```bash
export PATH="$PATH:$HOME/go/bin"
WAYMARK_RUN=reading scripts/sitting-run.sh
```

The same driver, the same snapshot. What differs in the manifest it
prints:

- **THE HOUSE BRIEF** opens it (waymark-xnf), in this order
  (waymark-wfa): the people with relation and age, the values with the
  words they love, **the next thirty days of the calendar** — one line
  each, the hour in the household's zone with the UTC beside it, and
  any start before 06:00 or after 22:00 local flagged **ODD HOUR** —
  the open threads with a person turn still owed an answer, the last
  five journals' notes, **WHAT MOVED THIS WEEK**, and **last** the
  published findings, newest
  first. Mechanical, from rows the house already holds. **The cap
  (`WAYMARK_BRIEF_LINES`, 80) trims the findings and nothing else**:
  everything above them is printed whole, and the findings that only
  say a row back — a verdict word repeated, a task title restated, a
  sentence `verify` would call THIN — are folded into one line saying
  how many and why. **Read it before any order.** It is the story the
  rows tell; most of what a reading finds comes from it.
- **WHAT MOVED THIS WEEK** sits inside that brief, just above the
  findings (waymark-2m2), and it is the only section of it that is
  ARITHMETIC rather than a list. When a clerk indexes a fact it may
  type it — how the fact arrived, in one of nine words, plus what it
  cost and which evening it was — and the reading turns those typed
  facts into log-odds and prints the ten biggest movers:

  ```
  CLAIM-LESS MOVER: Iris  /api/people/iris moved +4.48 this week
    (standing at +3.18) — atoms: thread/7fda11c6 2026-08-24 —
    costly_action, unprompted_mention (2 facts, one occasion —
    counted once and 50% again); thread/9c02af31 2026-05-02 —
    declined_invite
  ```

  **What it is for, and what it is not.** The house stores NO belief
  anywhere: this is recomputed from scratch on every reading, off
  `scripts/movements.jq`, and nothing it produces is written back.
  There is no hypothesis kind yet, so the thing that moves is the
  ABOUT-ROW — the person, the value, the thread a fact cites — which
  is why it says CLAIM-LESS. **The claim is yours.** The number tells
  you where to look; saying what it MEANS, in a sentence, with the
  atoms cited, is the reading's own work and the only place a claim
  may be made at all.

  Three rules, and `docs/spec-hypotheses.md` is the design record for
  all of them. **One:** each fact contributes `ln(likelihood ratio)`,
  cost-graded where somebody spent something, discounted where the
  house ASKED, and the sum is clamped — no pile of facts becomes
  certainty. **Two:** one occasion contributes its strongest fact,
  half again if it carried more than one, because enthusiasm in a
  single conversation is warmth and not four observations. **Three:**
  every contribution decays by `2^(−age ÷ half-life)`, per type, so a
  row nothing has fed for two years does not reverse — it forgets.
  *Moved this week* is that fold run twice, today and with the clock
  set back seven days; so a row moves when a new fact lands **and**
  when an old one fades, and both are real news.

  The numbers are the household's, on the feed document at
  `recipe.evidence_lr`, narrated at `recipe.evidence_lr_says`, and
  editable in the recipe's own form or through a `recipe_proposal` a
  person taps. A finding the house DISMISSED is left out — the house
  said the claim was wrong, and an atom on a rejected claim is not
  evidence. **If nothing is typed, the section says so and stops**,
  which is where every house starts. The working — every atom, every
  fold, and which document the table came from — is in the manifest at
  `movements`.
- **Every order is labeled** `CLERK` or `EDITOR`, and a reading owns
  both. The rule: an order is *editor* when its expected write is an
  outcome, an unmarked rework, an answer to a person's question, an
  extra, or a contradiction between rows; *clerk* when the write is
  one row at one door with the material inline.
- **EVERY PERSON TURN A THREAD STILL OWES** is printed, not just the
  last one (waymark-3wh): under each thread the manifest lists each
  person's turn that is still owed an answer, with the agent turns
  that followed it quoted beneath. A FACT is owed while no agent has
  spoken since it; a **QUESTION is owed until this principal answers
  it** — another agent's reply is not a check, it is the sentence to
  check, and the manifest says so under it. `verify` grades a question
  you answered where an agent had already replied `QUESTION
  CORRECTED`, one you left `UNCHECKED QUESTION`.
- **WHAT THE HOUSE ALREADY SAYS** is printed under every unanswered
  thread and every handed-back bundle (waymark-frv): the rows the
  bundle cites with their title and detail, and every published
  finding that names one of them, each with its address.
- **REVIEW** lists the sittings since the last reading with their
  `verify` grade lines, the forms the last reading left and whether a
  row now speaks for each, the doors a review needs and whether the
  leash admits them, and the anchored ask that would open them.
- The closing paragraph is the **one extra, or none**.

Then, as ever: **act, do not ask**; report by posting the journal and
pasting `scripts/sitting-run.sh verify`.

## The duties, in order

1. **The brief first.** Read it whole. What the sittings could not see
   is in it: the appointment the placard is for, the summer somebody
   left, the day the household said was booked.
2. **A person's question is answered from the record — including the
   ones an agent already answered** (waymark-3wh). Under each thread
   the manifest prints every person turn still owed and what the house
   already says. If those rows answer the question, cite them and say
   so; if they contradict the person, say which row and quote it; if
   they are silent, say that and what would settle it. Where a sitting
   has already replied under the question, that reply is the thing to
   check: read it against the rows and correct it in your own reply
   where it is wrong. Never index a question as a fact —
   `verify` prints `SAYS-SO` against a finding with no task, event,
   person, thread or value row behind it. (Gemini, 2026-08-29:
   answered "Rod needs a state ID" while the TC-842 task detail said
   SSN suffices — and published it as a finding.)
3. **The unmarked rework.** A bundle handed back with no piece marked
   and no clock time in the note is yours: read the note against the
   pieces and the rows the manifest prints under it, withdraw what the
   note says is wrong, stage what it asks, commit with `says` (the
   marks law, the clock table and the CLAIMED/NOT STAGED grade all
   still apply — SITTING.md § 3). A round that changes no piece is a
   lawful answer when the plan honestly stands; say so.
4. **Compose only where a goal is larger than any row.** The editor
   orders that expect an outcome (a session of like tasks, a
   commitment in a message, an event with no task beside it, a value
   nothing serves) are yours; so is a goal the brief implies and no
   probe reached. A wrapper is still a wrapper; twins are still
   refused at the door; an outcome the rows do not imply is a skip
   said out loud.
5. **ONE extra, or none** (waymark-mqo). Beyond everything the
   manifest named, one row it did not: cited to the rows you actually
   read, distinct from everything standing, with one sentence in the
   journal on why it was worth a row. `verify` grades it `EXTRA:
   cited, distinct` or `FILLER`. If nothing deserves it, none — and
   the journal says so. (Fable, 2026-08-28: read Grandpa-care facts
   and a VA caretaker stipend in chats and wrote none of it, because
   the sitting's ceiling forbade extras. That is what this line is
   for.) **The cap counts extras only** (waymark-kfm): a row that
   advances an arrival, indexes a listed fact or enriches a bare task
   is the assignment, not the freedom, and it never spends the extra.
6. **Contradictions between rows** (waymark-63s). Two are mechanical
   orders on the manifest: `stale-relative-date` (a task overdue whose
   detail still says "Monday") and `far-event-names-a-task` (an event
   10–45 days out whose words match an open task's purpose). Two are
   yours to read from the brief: a day an insight or remark calls
   *booked* with no event on it, and two task details that cancel each
   other. A held block never lands on a day the record calls booked.
7. **Diagnoses only for priors being recomposed** (waymark-me9), as in
   a sitting: the manifest's *Declines OWED a diagnosis* list, one
   each, never twice, never the manifest line copied out.
8. **REVIEW the sittings since the last reading.** Read their grade
   lines under REVIEW: an `UNANSWERED` order beside a silent journal, a
   `THIN` finding, a `TWIN`, a `CLAIMED, NOT STAGED`, an `ODD HOUR`.
   Then: **score** every standing bundle you did not write
   (`ranking_note`, the unscored list, the whole cite pack); **dismiss**
   a thin or false row where your grant admits the door — the formula
   names the scope a reading needs (`insight.dismiss`,
   `person.dismiss`, `ranking_note.dismiss`, `outcome.not_this_week`,
   on rows by id, and `verdict_reason.create`), the driver builds the
   anchored ask and files it as the one extend-ask when inside the
   window, and otherwise you file it yourself when you hold a row to
   act on and no ask of yours stands.

   **THE REASON RIDES THE DISMISSAL** (waymark-hcr). The dismiss door
   itself takes no body — it is one tap and stays one — so the why is
   a row of its own, filed straight after the verdict lands:

   ```
   POST /api/verdict_reasons
   {"subject_kind": "insight", "subject_id": "<id>",
    "subject_href": "/api/insights/<id>",
    "about": "<the finding, as it read>",
    "verdict": "dismiss", "reason": "thin"}
   ```

   A dismissed finding or judgment takes the words a CLAIM runs along
   — `thin` (nothing to do with it), `unfounded` (nothing cited backs
   it), `restated` (the house already holds it), `untrue` (the record
   says otherwise) — never the four a house says about something it
   was offered; `wrong_time` on a finding is refused by name. One row
   per verdict, and `say_more` adds the sentence the word could not
   carry. File one for every row you dismiss: the rank reads the word
   on the next finding about the same next step, and the next reading
   reads it off the row rather than out of your journal, which is
   private. Say the same thing in the journal if you like — but the
   journal is never where the reason lives.
   Four eyes hold whatever the grant says: a row this principal wrote
   is never yours to dismiss, whichever run wrote it — say so in the
   journal and leave it for the owner.
9. **Notes for the next sittings.** End the journal with a
   `notes_for_sittings` block, one form per line:

   ```
   ## notes_for_sittings
   - do: publish an INSIGHT at POST /api/insights citing /api/tasks/<id> and /api/events/<id> — <the sentence, complete>
   - do: reply with a REMARK at POST /api/remarks on outcome/<id> (in_reply_to /api/remarks/<id>) — <the words>
   ```

   Write forms, not thoughts: one row, one door, the material in the
   sentence. A reading that leaves no notes says so on purpose;
   `verify` prints `NOTES FOR SITTINGS: none`.

10. **Mail the block** (waymark-bbb). The journal is private to this
    principal; the sittings are not. So send the same lines as one
    letter — the driver prints the address it is expecting:

    ```
    POST /api/letters
    {"to": "<WAYMARK_SITTING_PRINCIPAL>",
     "title": "Forms from the reading of <date>",
     "body": "<the same `- do:` lines, verbatim>"}
    ```

    ONE letter per reading. No grant scope names `:letter` and none
    can — it is a private own-surface kind — so this write rides your
    own identity: the door stamps you as the author and refuses any
    other `owner`, and `to` must be a member the household actually
    has (the refusal names the shape of a good address, never who is
    on the roster). Once sent it cannot be edited, re-addressed or
    taken back, so say it the way you mean it. The next sitting's
    manifest prints the letter under **FORMS FROM THE LAST READING**,
    each line a clerk order ahead of the probes', dropping a line
    whose subject a standing row already speaks for; the sitting opens
    the letter first, and `verify` grades the run on both — whether
    the letter was opened, and whether a row it wrote cites each
    form's addresses.

## What verify says about a reading

Everything it says about a sitting, and: `EDITOR ORDER … answered by
<row>` / `SKIPPED OUT LOUD` / `UNANSWERED AND UNSAID` (the fault —
printed, never blocked); `EXTRA: cited, distinct` / `FILLER` — and an
extra is judged against the write each order asked for, so a row that
merely cites an order's subject is still your extra (waymark-alj), and
only an `EXTRA`-class row spends the one extra (waymark-kfm);
`QUESTION CORRECTED` / `QUESTION ANSWERED` / `UNCHECKED QUESTION`
(waymark-3wh); `SAYS-SO`; `NOTES FOR SITTINGS: N form(s) left` —
counted off the journal body read at its own address, where a body
actually lives; and `LETTER FOR THE SITTINGS: sent — <address>` or
`NOT SENT` (the fault the journal alone cannot cure: the forms stayed
where the sittings cannot read them). A sitting is never
faulted for a line under *Waiting for a reading*; a reading is faulted
for leaving one unaddressed and unsaid.

Both runs get the same one-line-per-row classification (waymark-kfm),
in this precedence: `ORDER-ANSWER` (graded on the order's own line, so
never repeated), `FORM-ANSWER`, `FACT INDEXED`, `ARRIVAL ADVANCED`,
`ENRICHED`, `EXTRA`, `FILLER` — SITTING.md's priority 6, where verify
is described, has the definitions. The four in the middle are the
assignment, not the freedom: an insight that advances an arrival was
never a second extra, whatever the grader used to print.

## What a reading never does

Everything SITTING.md's *What a run never does* names, and: it never
rewrites a sitting's row (it dismisses, scores, or answers beside it);
it never scores its own rows; it never widens its own scope — an ask
decides nothing, a person taps it.

The formula: `.beads/formulas/reading.formula.toml`. The same walk in
prose: `.claude/skills/reading/SKILL.md`. The schedule and the cron
line: `docs/spec-standing-agent.md` § "Two runs".
