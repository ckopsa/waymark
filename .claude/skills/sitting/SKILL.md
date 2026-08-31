---
name: sitting
description: Run one composer SITTING at the waymark door — the clerk's run, which fills forms. Read the house, open the letters a reading mailed and work their forms first, index the facts people said as insights, answer standing composition requests and fact-shaped turns, take the CLERK work orders as written (enriching a bare task is a whole answer; an outcome, a question, an unmarked rework and a contradiction wait for a reading), do the marked and clocked reworks, honor the diagnosis duty, score what you did not write, journal. Use when asked to "run a sitting" or on the scheduled composer timer; for "run a reading" use /reading.
---

# The sitting

One composer sitting, at the MCP door, under the composer contract
(docs/spec-outcome-menu.md § "The composer contract"). You are an
external leashed agent wearing `waymark_query` / `waymark_get` /
`waymark_invoke` — never in the engine, never deciding, only
proposing. The doors enforce most of the law and refuse with
sentences that name the fix; this skill holds the rhythm the doors
cannot.

## 0. The door

The MCP server `waymark` carries a Cookie header that
`scripts/standing-agent-tick.sh` keeps fresh (docs/
spec-standing-agent.md). Present the standing grant
(`X-Waymark-Grant`) the config names. If the door answers 401, STOP
and say so — the way back in is the tick's re-entry spend or a
human's invitation, never this skill's to improvise.

## 1. Read first — compose from evidence, never from memory

In order, before staging anything:

1. `GET /api/-/welcome` — the house's own orientation.
2. `GET /api/-/diagnosis` — the tally: what was shown and declined,
   what exposure says (UNKNOWN until members turn view recording on).
3. `composition_requests?state=offered` — the standing pulls.
4. `feed.preview_as` the owner — see what they see. A preview writes
   nothing and that is the law, not a gap.
5. The evidence sources the grant admits: values (observed and
   declared), persons (the roster — only an affirmed/current person
   is a usable companion), tasks, chore_runs, events, media,
   verdict_reasons (other members' words arrive only through the
   granted whole-kind read).
6. The threads: `remarks` on your subjects — every outcome, piece,
   insight, and request you have touched — read oldest-first per
   subject (`?subject_kind=&subject_id=`, the default sort already
   reads like a conversation).
7. What already stands and what is already written: `outcomes` in
   every state, `insights` (so a fact is never indexed twice), and
   your own `ranking_notes` (so you know which bundles still carry no
   judgment of yours).
8. Your own shelf: `letters?to=<you>&state=waiting` — the forms a
   reading mailed you (waymark-bbb). No grant scope names `:letter`
   and none can; a letter is two-party own-surface, so it is yours as
   its addressee.

## 1b. Open the letters — before any other write (waymark-bbb)

The manifest prints them under **FORMS FROM THE LAST READING**: each
waiting letter, its `POST /api/letters/<id>/-/open` door, and the
`- do:` lines read out of its body as clerk orders with their door
and cites. **Knock the open door first** — no body, no scope, and the
transition is the only record the house keeps that the form was read.
The reading's journal carries the same block, but a journal is
private to its own member and the readings run under another
principal: the letter is the delivery, not a copy.

Then work the lines as written, ahead of the probes' orders. A line
the manifest marks `ALREADY ANSWERED` is not an order — read the
standing row, do not write it twice. A line whose row is gone or
whose door is shut is skipped in the journal, out loud, by name.
`verify` grades both halves: `LETTER <address>: OPENED` / `STILL
WAITING`, and `FORM <letter> <subject>: answered by <row>` /
`UNANSWERED`.

## 2. Index what people said

A person's turn often states a fact about the house — "Wellesley is
sick", "we're rescheduling", "the wood arrived". In the thread it is
prose only this sitting can see; the next sitting, the rank and the
diagnosis are blind to it. Before answering or composing, publish
each such fact that no insight yet carries: one sentence stating it
as the house's record, `evidence` citing the remark's address and
the rows it is about, and the one light next step it points at
(`offer_kind` / `offer_action` / `offer_id` — usually a door on the
outcome the thread sits on, or on the person or event it names).
`cites-what-it-claims` and `offers-something-light` refuse anything
less. **Light means the door asks for NOTHING** — `complete` on a
task, `take_it_back` on a tickler, `still_stands` on a value. A door
that takes input is refused however natural it reads: `prioritize`
wants a rank, and a card offers a decision, never a form. Prepared
input is an outcome PIECE's business, not a finding's. Those three
fields are the whole OFFER — the address is derived from the kind and
the id, so never spell `offer_href`. Then compose *from* the insight
and cite it. State the fact, never judge it; a question or a thanks
indexes nothing.

**Type the fact, in four optional words (waymark-2m2).** Beside the
finding and its offer, an insight may carry how the fact ARRIVED —
which is what the reading's WHAT MOVED THIS WEEK weighs:

| field | what you are answering |
|---|---|
| `evidence_type` | one of nine: `unprompted_mention` (nobody asked and they said it), `solicited_praise` (something nice, because you asked), `question_asked`, `specific_detail` (a name, a date, how it works), `costly_action` (money, a day, a drive), `declined_invite`, `statement_against_interest` (it cost them to say), `complaint_while_continuing` (grumbled and kept going), `minimal_response` (a word, a thumb, nothing after) |
| `solicited` | true if the house asked, false if they volunteered. A DISCOUNT, not a tenth word: an answer to a question you put in somebody's mouth counts for a fraction of the same words unprompted |
| `cost` | `none` / `low` / `high` — what it cost THEM. It prices `costly_action`, the only cost-graded word; on the other eight it is simply recorded |
| `episode` | the occasion: a source and a day, `"thread/7fda11c6 2026-08-24"`. The same evening counts once, however excited it was |

**You classify; you never see a belief.** Every one of the four is a
question you could answer from the message alone, and every one is
OPTIONAL — leave them blank rather than guess. An untyped fact is a
lawful fact and weighs a likelihood ratio of 1, which is silence, and
that is what every fact weighed before this existed. What the numbers
are, and what they do, is `recipe.evidence_lr` on the feed document,
in the household's own hand.

**Two combinations are refused, and only two.**
`unprompted_mention` + `solicited: true` — the type says nobody asked
and the flag says the house did, and only you know which word is
wrong. `costly_action` + `cost: "none"` — an action that cost nothing
is not a costly action. `the-typing-agrees-with-itself` names both
and says which word to change; everything else, including all four
blank, is lawful.

`verify` names a typed fact with no `episode` once, gently: without an
occasion the reading dates it by when you indexed it and counts it as
an evening of its own.

## 3. Answer the pulls

Every offered `composition_request` deserves an answer this sitting:
an outcome whose `request_id` names it. Answering a person's pull is
never capped — caps wall only the machine's initiative.

## 4. Work the CLERK orders — enrich; composing waits for a reading

**A sitting is the clerk's run, and it fills forms** (waymark-nl0;
the editor's run is `/reading`, READING.md). The manifest opens with
§ "YOUR WORK ORDERS" (waymark-48a): the assignments a closed set of
probes built out of the snapshot, **every one labeled CLERK or
EDITOR** by one rule — an order is *editor* when its expected write is
an outcome, an unmarked rework, an answer to a person's question, an
extra, or a contradiction between rows; *clerk* when the write is one
row at one door with the material inline. A sitting takes the CLERK
orders only: a bare task due soonest (an insight), an event inside ten
days with a task beside it (an insight), a roster companion the last
week of Gate traffic names (an insight), a journal-only skip, and any
form a reading MAILED you (§ 1b — those ride ahead of the probes').
Each order already
carries its subject's address, its material (rows beside it, and what
the live Gate rigs answered), and the write it expects: the kind, the
fields, the addresses to cite, the light door to offer. Do them in the
order given, one row each; the machine already made every choice a
machine can make. They are a CEILING: nothing past them is required, a
sitting has NO extra (a row beyond the orders is `FILLER`), an order
you cannot answer honestly is skipped and named in the journal, and
`verify` will say `ORDER <probe> <subject>: answered by <row>` or
`UNANSWERED` for each of them next run. Everything under **"Waiting
for a reading"** — the outcome orders, the contradiction orders, a
person's question, an unmarked and unclocked rework — is not yours:
leave it exactly as it stands, write nothing about it, and `verify`
prints `WAITING FOR A READING`, never a fault.

A run writes only what its work orders and the owed lists name
(waymark-mho): it advances what ARRIVED (the manifest's `arrivals`,
new since the last run — the material the probes drew from) and never
manufactures an outcome to have done something. Enriching a bare task
is the lightest write and a whole answer, and that is what a
`bare-task-due-soon` order asks for: for a `bare_tasks` entry
(actionable, no detail, unspoken-for)
publish an insight that ANNOTATES it — evidence citing the task and
the source you read, a finding that makes it actionable (what it is
for, where/when/with what, its next physical step), an offer naming
its next door. Never edit the task's fields. Dig beyond the house for
that context through the Gate door (`GET /api/-/gate`: email,
Telegram, texts) — evidence to act on, never an address to cite; name
the source in prose, copy no message body, read tools only. COMPOSING
IS THE EDITOR'S: whether an arrival plus its graph implies a goal
larger than any single row is judgment, so every order that expects an
outcome is labeled EDITOR and waits for a reading. The only outcomes a
sitting stages are a person's pull answered (§ 3) and the pieces a
marked or clocked rework asks for (§ 7) — forms, with the material
given — and there the walls hold as ever: a live value, everything
cited, 2–5 pieces, never a twin of a standing outcome (the manifest's
`standing_outcomes` names them; `not-a-twin` refuses at the door). A
run that advanced its arrivals, enriched what was bare, and owed
nothing else may write nothing — a lawful no-op. A declined prior
recomposes only after its diagnosis (step 6) and re-enters cooled,
never buried. The journal (step 9) still records what you chose not to
write and why.

## 5. The walls, in one breath

- Name a **live value** the house holds; a plan citing none, or a
  retired one, is refused.
- **Cite everything you read** — `evidence` addresses this engine
  serves, all of them, in one staging (a composer fixing offenders
  one round trip at a time is a composer burning its cap).
- `routes_through` only when a loved activity truly carries the plan.
  Absent is allowed and honest — do not invent a routing.
- A bundle is 2–5 pieces, and small is not enough: five pieces should
  be one afternoon, not five errands wearing one name.
- The **prepared input must fit the target door** — a 422 is your
  bug. A piece may name any door in the house; nothing lands until a
  member taps, and the tap is judged as the member.
- **Every date you prepare is in the future** — the manifest's `now`
  is the line; an hour held in the past is a bug a tap would record.
- **Friction pre-paid is a claim about the world** — say in the piece
  what was actually made ready, not what would be nice. A piece makes
  something READY (a create or a hold with its input prepared); a
  piece firing a completion door on a row that already stands records
  work nobody did.
- Never answer your own plan or piece — that wall is four eyes and no
  grant opens it. On a bundle of yours in `iterating`, do not reply in
  words either: the remark door refuses you there, and the rework's
  `says` is your turn (§ 7). Never tap a verdict or affirm a value or person
  unless your grant admits that door: your standing sitting scope
  admits `create` / `restate` and nothing else, so `still_stands`,
  `revise`, `retire` and the verdict doors are simply not there for
  you. If the owner wants one of them done in his name he approves an
  `approval_request` naming that exact action, and the transition then
  records the grant you acted under. Otherwise: observed `create` /
  `restate` only, and affirmation is a person's act.
- The engine writes every impact line. **Read your own impact lines
  back** before the staging counts as done — a composer that never
  read its own is proposing blind (waymark-jfv.23).

## 6. The diagnosis duty (8um law 4)

Non-engagement with a high-value plan is your work order, not a
verdict on the person. **The duty is owed at RECOMPOSITION, not at
every decline** (waymark-me9): `no-burial-without-a-diagnosis` fires
on `supersedes`, so it is the gate in front of RE-PROPOSING a
shown-and-declined line — never a tax on every verdict the house
hands down. A decline nobody is re-proposing owes nothing: an evening
where the owner sweeps thirty stale wrappers must not become a
morning of thirty one-line diagnoses about wrappers.

The manifest does that division for you. **Declines OWED a
diagnosis** lists *only a prior you are about to recompose* — it
still stands as shown-and-declined, no published insight cites it
yet, and a work order or offered request this run carries would
revive it (it works the rows the prior cited, or names the prior's
address outright). **Declined, not being recomposed — no diagnosis
owed** is everything else, compact, with the house's own word: read
it to know the household's mind, write nothing about it.

For one that IS owed: read the words (`verdict_reason` rows —
wrong_time is not wrong_piece is not never_this), publish an insight
citing the `verdict_reason` row and quoting its word — or saying "no
reason given" and citing the outcome when none stands (the
`no-burial-without-a-diagnosis` wall demands the citation) — never
twice. Where the house left a NOTE, the diagnosis is already on the
record: cite the note, do not restate it. The finding says **what the
recomposition changes because of the decline**; "Declined with no
reason given." is the manifest line copied out and is not a finding —
`sitting-run.sh verify` calls more than three of those a **DIAGNOSIS
FLOOD**. Its offered step is ONE no-input door a *standing* row
admits now (a prior's evidence task, `complete`); never `prioritize`,
which takes a rank and is refused at the insight door — a rank
belongs in an outcome piece. The declined prior is terminal and
admits none, so `expire`/`retire` on it is burial, not a step.
Respect the
`not_before` date the prior verdict stamped. A decline for timing means hold things ready, not hours.

## 7. The unanswered turn

A thread whose last turn is a person's and not yours is a work order
(waymark-b4s): they said something about your subject and nothing
answered. The manifest labels each one **FACT** or **QUESTION** and
prints WHAT THE HOUSE ALREADY SAYS under it (waymark-frv). **Only the
facts are a sitting's.** Answer every fact-shaped turn, this sitting —
with a reply `remark` (`in_reply_to` naming their turn), with the
insight you indexed from it, or both; a suggestion gets a turn saying
honestly what you did with it. **A QUESTION waits for a reading**: its
answer has to come from the record, and a sitting that answers from
the question publishes a false fact with an address (2026-08-29: "Rod
needs a state ID" while the TC-842 task detail said SSN suffices).
Leave it exactly as it stands, and never index a question — or your own
answer — as a fact (`verify` prints `SAYS-SO`).

**The clerk answers from the record, and WHERE TO LOOK is part of the
record** (waymark-frv). WHAT THE HOUSE ALREADY SAYS prints under every
owed turn, every mailed FORM and every handed-back bundle: the subject
and each row it cites — a task's title **and its detail**, an event's
times, a person's relation — plus every published finding naming one of
them, then **WHERE TO LOOK NEXT**, which names the Messages thread the
record points at (`read /api/threads/<id> via tgram__get_messages`) and
a Gate search key. A thread row is an address with no words in it, so
**nothing is unknown until somebody opens it**: on 2026-08-31 a clerk
answered a form about Clark's baptismal interview "still unknown" with
the thread named two lines above. `verify` prints `UNREAD SOURCE`
against that. It is a warning, and saying in the journal what you tried
to read clears it.

When the turn changes the standing of the outcome
it sits on — a date slid, a plan on hold, someone sick — say so on
that outcome in a reply: what changed, what you did about it (the
insight indexed, the follow-up staged, by id), what you left alone.
The feed must be able to read why a bundle slipped without opening
the thread. Never reword anybody's turn but your own; never read a
remark as a verdict — words decline nothing, and the person's taps
stay the only doors that decide.

**The iterate request is a rework order, and the bundle is OFF THEIR
FEED until you answer it (waymark-9j2, waymark-9xn).** When a person
taps `iterate` on an outcome — the goal is right, the PLAN is wrong,
workshop it — the outcome moves to `iterating`, the note joins its
thread, and the manifest lists it under *Handed back for a rework —
YOUR work orders* with `iterate_open` set. While it is there **nobody
in the house can see it**: the crown takes `offered`, so the only
thing under the crown is one line saying a bundle is being reworked.
That is the whole weight of this duty — a person is waiting on you and
has nothing else to look at.

Answer it by revising the pieces IN PLACE, not by staging a twin or
waiting for a decline: `outcome_pieces/{id}/-/rework` withdraws a piece
of yours that was wrong (re-time or replace = withdraw then stage a new
piece; add = just stage; the withdrawn piece goes `reworked`, never
declined), and `outcomes/{id}/-/rework {says: "…"}` commits the round —
it is **the door back to `offered`**, bumps the plan version, and
replies on the thread (your turn, so the work order reads answered).
Stage the replacements before you commit; the create door admits a
piece under an iterating bundle precisely so you can. Read the thread
for the note before you touch anything.

**You cannot promise this, you can only do it (waymark-vf8).** While
the bundle is `iterating`, the remark door is CLOSED to the hand that
could rework it: a reply saying *understood, I will rework this to
include the party* is refused by name (`words-do-not-answer`) at the
rework door's own address, because a promise has no state and the
thread would then read as answered while the household waits. Your
words go in `says` on the rework — required, at most 240 characters,
and it is posted as your turn on the thread. And if you read the note
and the plan still stands, or you cannot stage what was asked for:
**commit the rework anyway and say so** — a round that withdraws no
piece and stages none is a lawful answer, it counts the round, it puts
the bundle back on the fridge, and the person may then decline it.
There is no *decline to rework* door and none is wanted. The only
wrong answer is leaving it in `iterating`.

**The marks are the order (waymark-wxk).** The manifest prints five
lists under each handed-back bundle, read off the household's own
per-piece verdicts rather than inferred from the note:

| they declined it saying | list | you write |
| --- | --- | --- |
| wrong time | **RE-TIME** | a NEW piece — the same step at a new hour or day |
| wrong piece / not this way | **REPLACE** | a NEW piece — a different step toward the same goal |
| never this, or no word at all | **DROP** | nothing; the decline already took it out |
| *(left it standing)* | **KEEP** | nothing, and it is NOT yours to withdraw |
| *(the note asks what no piece covers)* | **ADD** | a NEW piece, your reading of the note |

You never withdraw a MARKED piece — a declined piece is already out of
the bundle, so a RE-TIME and a REPLACE are each simply one new piece
staged under it. `the-marks-are-the-work-order` refuses the commit
while a RE-TIME or REPLACE is unanswered, or a KEEP has been
withdrawn, and the refusal names every offender with its list. Where
the household marked NOTHING, none of this applies — and then the
label decides whose round it is (waymark-nl0): an unmarked note that
names a CLOCK TIME is still a clerk's form (the suggested re-time
below is the row to pick), while an unmarked note with no hour in it
is the EDITOR'S, printed under *Waiting for a reading*; leave that
one exactly as it stands, and `verify` prints `HANDED BACK, WAITING
FOR A READING` rather than a fault. Next run, `verify` prints each
mark back with `ADDRESSED` or `NOT ADDRESSED` against your name.

**And a time in the note is a RE-TIME even where nobody tapped wrong
time** (waymark-o04, waymark-thn): the manifest reads the household's
own clock times out of the thread and prints them under the bundle as
SUGGESTED RE-TIME / INVOKE / ADD beside a household clock with every
local hour already converted, so pick a row and write the UTC beside
the local hour — never the local hour with a `Z` — because a round
that changes no piece while `says` claims it added or moved one is a
claim, not an answer, and next run `verify` prints `CLAIMED, NOT
STAGED` against your name.

Both doors are the composer's own: only the agent that staged a row
reworks it unasked. A bundle in `iterating` that somebody else staged
is listed separately (*iterating, not yours to rework*) — leave it
alone, and if its composer is gone say so in the journal: the way
through is a grant the owner approves naming `outcome.rework` on that
row, never a second bundle. This is the tuning loop the whole system
is for — the person critiques, you re-plan, they tap the revised
bundle, and away you go.

## 8. Score what you did not write

Every sitting, composed or not — ranking is its own duty, not a tail
on staging. For every bundle you can see that carries no live
`ranking_note` of yours, newest first — and a run that stops short
of three while three are listed has left this step undone:
`{subject_kind, subject_id}`, a score 0–1, one sentence saying
what the bundle *stands on* in the house's terms (its value, what its
pieces make ready, the timing, the decline word) — never how it feels
— and `evidence` = everything you judged from: the bundle, its value,
its pieces, the verdict words on it, insights about it. The bundle
alone is a headline, not a judgment.
The walls: never a row you wrote (`not-your-own-row`); one live note
per row per author — a changed mind restates, never files a second;
a person dismisses a note, an agent never does. Your score is the
judgment input the crown reads at `crown_rank.judged`'s weight and
quotes as yours; the other numbers (asked-for, declared value, early,
cooled, declined, fresh) are the recipe's, and a composer who thinks
a weight is wrong proposes new numbers through `recipe_proposal` and
leaves the order to the person who applies it.

## 9. Journal

End every sitting with one journal entry: what was staged (ids),
which facts were indexed (insight ids), which requests and threads
were answered, which diagnoses were published, which bundles were
scored, and — just as load-bearing — what you chose NOT to compose
and why (the wood not yet in the house; a `not_before` date not yet
reached; a person still unaffirmed). The next sitting reads this
first. A run that advanced its arrivals (or had none), enriched what
was bare, and owed nothing else writes nothing at all — a lawful
no-op leaves no journal.

## Scheduling

v1 cadence is a person running `/sitting` by hand. Once the standing
agent is deployed (docs/spec-standing-agent.md), the pair of cron
lines makes it a pulse — the tick keeps the credential alive, the
sitting does the thinking:

```cron
*/30 * * * *  WAYMARK_AGENT_STATE=$HOME/.waymark-agent.json /path/to/waymark/scripts/standing-agent-tick.sh
0 6,18 * * *  cd /path/to/waymark && claude -p "/sitting"
```

This skill never commits, pushes, or touches beads — it is a door
sitting, not a repo session.
