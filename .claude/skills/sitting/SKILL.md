---
name: sitting
description: Run one composer sitting at the waymark MCP door — read the house, index the facts people said as insights, answer standing composition requests and threads, advance the arrivals (enrich a bare task at least, compose an outcome only for a real goal — no floor), honor the diagnosis duty, score what you did not write, journal. Use when asked to "run a sitting", "compose outcomes", or on a scheduled composer run.
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
fields are all you send — the address is derived from the kind and
the id, so never spell `offer_href`. Then compose *from* the insight
and cite it. State the fact, never judge it; a question or a thanks
indexes nothing.

## 3. Answer the pulls

Every offered `composition_request` deserves an answer this sitting:
an outcome whose `request_id` names it. Answering a person's pull is
never capped — caps wall only the machine's initiative.

## 4. Work the work orders — enrich, or compose for a real goal

The manifest opens with § "YOUR WORK ORDERS" (waymark-48a): at most
two assignments a closed set of probes built out of the snapshot —
a bare task due soonest, an event inside ten days nothing prepares
for, a roster companion the last week of Gate traffic names and no
insight records, a live value nothing standing serves. Each order
already carries its subject's address, its material (rows beside it,
and what the live Gate rigs answered), and the write it expects: the
kind, the fields, the addresses to cite, the light door to offer. Do
them in the order given, one row each; the machine already made every
choice a machine can make. They are a CEILING: nothing past them is
required, an order you cannot answer honestly is skipped and named in
the journal, and `verify` will say `ORDER <probe> <subject>: answered
by <row>` or `UNANSWERED` for each of them next run.

There is NO floor (waymark-mho): a run advances what ARRIVED (the
manifest's `arrivals`, new since the last run — the material the
probes drew from) and never manufactures an outcome to have done
something. The MINIMUM is to enrich a bare task, and that is what a
`bare-task-due-soon` order asks for: for a `bare_tasks` entry
(actionable, no detail, unspoken-for)
publish an insight that ANNOTATES it — evidence citing the task and
the source you read, a finding that makes it actionable (what it is
for, where/when/with what, its next physical step), an offer naming
its next door. Never edit the task's fields. Dig beyond the house for
that context through the Gate door (`GET /api/-/gate`: email,
Telegram, texts) — evidence to act on, never an address to cite; name
the source in prose, copy no message body, read tools only. COMPOSE
an outcome only when an arrival plus its graph implies a GOAL LARGER
THAN ANY SINGLE ROW — an end-state, not a task restated; a bundle
whose goal equals one task, or that only re-prioritizes one, is a
wrapper, enrich instead. Never a twin of a standing outcome. A run
that advanced its arrivals, enriched what was bare, and owed nothing
else may write nothing — a lawful no-op. Only an honest dig that
finds nothing distinct stages nothing, and then the journal (step 9)
notes what was
searched and why. There is no cap on staging (waymark-1uv.3): the
machine writes without limit and the crown's declared rank chooses
what fills the person's attention. Stage every DISTINCT bundle the evidence
honestly supports — but never a twin of one that already stands
(the manifest's `standing_outcomes` names them; a candidate with the
same goal, or citing the same evidence row, is a twin): the rank
cannot tell twins apart, so sameness is the only scarcity.
A declined prior recomposes only after its diagnosis (step 6) and
re-enters cooled, never buried. The journal (step 9) still records
what you chose not to compose and why.

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
- Never answer your own plan or piece; never tap any verdict; never
  `still_stands` / `revise` / `retire` a value or affirm a person —
  observed `create` / `restate` only. Affirmation is a person's act.
- The engine writes every impact line. **Read your own impact lines
  back** before the staging counts as done — a composer that never
  read its own is proposing blind (waymark-jfv.23).

## 6. The diagnosis duty (8um law 4)

Non-engagement with a high-value plan is your work order, not a
verdict on the person. Before any recomposition of a
shown-and-declined prior: read the words (`verdict_reason` rows —
wrong_time is not wrong_piece is not never_this), publish an insight
citing the `verdict_reason` row and quoting its word — or saying "no
reason given" and citing the outcome when none stands (the
`no-burial-without-a-diagnosis` wall demands the citation) — only
for a decline that still owes one, never twice — whose offered step
is a no-input door a *standing* row admits now (a prior's evidence
task, `complete`); never `prioritize`, which takes a rank and is
refused at the insight door — a rank belongs in an outcome piece.
The declined prior is terminal and admits none, so `expire`/`retire`
on it is burial, not a step. Respect the
recomposition floor the prior verdict set. A decline for timing means hold things ready, not hours.

## 7. The unanswered turn

A thread whose last turn is a person's and not yours is a work order
(waymark-b4s): they said something about your subject and nothing
answered. Answer every one, this sitting — with a reply `remark`
(`in_reply_to` naming their turn), with a recomposition whose
evidence cites what they said, or both. A question gets words; a
fact ("the wood arrived") gets a composition that uses it; a
suggestion gets either an outcome that takes it or a turn saying
honestly why not. When the turn changes the standing of the outcome
it sits on — a date slid, a plan on hold, someone sick — say so on
that outcome in a reply: what changed, what you did about it (the
insight indexed, the follow-up staged, by id), what you left alone.
The feed must be able to read why a bundle slipped without opening
the thread. Never reword anybody's turn but your own; never read a
remark as a verdict — words decline nothing, and the person's taps
stay the only doors that decide.

**The iterate request is a rework order (waymark-9j2).** When a person
taps `iterate` on an outcome — the goal is right, the PLAN is wrong,
workshop it — the outcome stays offered, the note joins its thread,
and the manifest flags the standing outcome `iterate_open`. Answer it
by revising the pieces IN PLACE, not by staging a twin or waiting for
a decline: `outcome_pieces/{id}/-/rework` withdraws a piece of yours
that was wrong (re-time or replace = withdraw then stage a new piece;
add = just stage; the withdrawn piece goes `reworked`, never
declined), and `outcomes/{id}/-/rework {says: "…"}` commits the round
— it bumps the plan version, replies on the thread (your turn, so the
work order reads answered), and closes the invitation until the next
iterate. Both doors are yours alone (only the composer that staged a
row reworks it) and open only while an iterate stands unanswered.
Stage the replacements before you commit. This is the tuning loop the
whole system is for — the person critiques, you re-plan, they tap the
revised bundle, and away you go.

## 8. Score what you did not write

Every sitting, composed or not — ranking is its own duty, not a tail
on staging. For every bundle you can see that carries no live
`ranking_note` of yours, newest first, at least three when three
exist: `{subject_kind, subject_id}`, a score 0–1, one sentence saying
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
and why (the wood not yet in the house; a floor not yet expired; a
person still unaffirmed). The next sitting reads this first. There
is NO floor: a run that advanced its arrivals (or had none), enriched
what was bare, and owed nothing else writes nothing at all — a lawful
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
