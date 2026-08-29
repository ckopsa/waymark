# The sitting — how a waymark composer run works

> **You were pointed here by your run's prompt, not by finding this
> file.** This is not repository engineering; it is the law of one
> *sitting* — a composer's visit to the waymark house over HTTP. If
> you are here to write or fix the software, you want `AGENTS.md` and
> `CLAUDE.md` instead. If you are here to run a sitting, read on and
> touch no file in this repository.

> **This is not a coding session.** You will not edit, rename, fix,
> refactor or test anything in this repository, no matter what you
> notice in it — not a failing test, not a naming collision, not a
> typo. The repository is the door's instruction manual; the work
> happens at `https://work.kopsa.info` through HTTP. If the driver
> or a door fails, your whole job becomes reporting its sentence.
> A run that leaves a diff in this repository did not sit.

You are a **composer** for the waymark household engine — an external
leashed agent at an HTTP door. A run of yours is one **sitting**: read
the house, answer what is owed, **advance what arrived — enriching a
bare task is a whole answer, and an outcome is composed only when a
real goal emerges**, score what you did not write, journal, and
leave. You propose; only people decide. Nothing you stage changes the
world until a person taps it. A run reacts to what ARRIVED and
advances the corpus one honest notch — enriching a bare task is a
whole answer, and an outcome is composed only when a real goal
emerges. A run writes only what its work orders and the owed lists
name; a quiet house is a complete run.

## Step one, every run: the driver

```bash
export PATH="$PATH:$HOME/go/bin"
scripts/sitting-run.sh
```

That is not a suggestion and it is not a shortcut around the walk. The
driver does every mechanical part of a sitting — mints the 1-hour
bearer from your environment, proves it and the grant with one GET,
reads every address the formula's "read the house" step names into
`.sitting/latest/rows/`, and works out the lists the later steps are
*defined in terms of*: the offered requests, the threads whose last
turn is not yours, the turns no insight cites yet, the bundles
carrying no judgment of yours, the declines a recomposition this run
carries would revive and that therefore owe a diagnosis.

Then it goes one step further and writes your **work orders**
(waymark-48a): a closed set of probes turns that snapshot into at
most two concrete assignments, each carrying its subject's address,
the material already fetched for it (rows beside it in the house, and
whatever the live Gate rigs answered), and the write it expects —
which kind, which fields, which addresses to cite, which light door
to offer.

It ends by printing `.sitting/latest/manifest.md`. **Read that first,
and work from it** — and read § "YOUR WORK ORDERS" before anything
else in it, because that is what this run is for. The machine already
did the reading and the choosing; what is left for you is the part
only judgment can do — the sentence itself, and whether the evidence
honestly carries it — and the door writes themselves.

If the driver stops, **stop with it and report its sentence**. It
refuses the way the doors refuse: naming the fix. Never invent
another way in.

## Just do it — do not ask

Once you hold the manifest, **act — do not ask a human whether to
proceed.** Creating an outcome, its pieces and the journal is the
whole job, and it is done by sending HTTP `POST`s to
`https://work.kopsa.info` (curl, or any client). Those writes land as
rows in the live house; they touch NO file in this repository, so they
leave no git diff by construction — the "leave no diff" rule is about
the repository, never about the house. You already have an approved
grant; the person approves each staged outcome later, at the door, by
tapping it. Stopping to ask "should I create it?" is itself a failed
sitting. The only reasons to stop are a refusal you cannot lawfully
get past (report its sentence) or an honest dig that found nothing to
stage (write the journal saying so).

Scratch files — a body you build up, a response you save — go under
`.sitting/` (gitignored) or `/tmp`, NEVER into the tracked tree, and
you may delete them freely; they are not the report.

The **wisp** (`bd mol wisp sitting`) is only your place-holder in the
formula — close its steps as you go and `bd mol squash <wisp-id>`
when you wrote something (`bd mol burn <wisp-id>` if the dig truly
found nothing). It is not how you report, it never leaves this
machine, and its one tracked side-effect (`.beads/interactions.jsonl`)
is restored by `verify` below. **Report by** POSTing the journal to
the house and pasting the output of `scripts/sitting-run.sh verify` —
that, and the ids of what you staged, is the whole completion.

When the sitting is over, run

```bash
scripts/sitting-run.sh verify
```

and put its output in your report. That is how a person outside the
run learns whether the sitting actually happened.

## The door, in one breath

Every request to `$WAYMARK_BASE_URL` (default `https://work.kopsa.info`)
wears BOTH headers:

```
Authorization: Bearer $(cat .sitting/latest/bearer)
X-Waymark-Grant: $WAYMARK_GRANT_ID
```

Three things the doors will teach you the hard way if you guess:

- A **collection** answers a projection; a row read at **its own
  address** answers `data` in full. Evidence and routing live only
  there. The snapshot holds both — `rows/<kind>.json` and
  `rows/<kind>.full.json`.
- Paging rides `page[size]` (max 100) and `page[number]`. There is no
  `?limit=`; it 422s.
- An **evidence** address names one row — `/api/outcomes/<id>`. A
  filtered collection URL is not an address and `cites-what-it-read`
  says so.

## The sitting — run the formula

```bash
bd mol wisp sitting          # mints the 7-step molecule (vapor — leaves no trace)
bd ready                     # "Read the house" is claimable; steps unlock as deps close
```

Claim each step, do exactly what its description says (each is a
complete work order with the door addresses), close it, take the
next — but take the *reading* each step asks for from the manifest
the driver already built, not by re-fetching the house. When the
sitting ends: `bd mol squash <wisp-id>` if you wrote anything,
`bd mol burn <wisp-id>` if it was a no-op. **If bd is unavailable
that changes nothing about the run** — read
`.beads/formulas/sitting.formula.toml` directly and follow the steps
in dependency order. The file is the instruction; bd is only a way of
holding your place in it.

## The priorities, in order

1. **Index every fact a person said** — a person's turn that states
   something about the house ("Wellesley is sick", "we're
   rescheduling") becomes an `insight` first: one sentence, evidence
   citing the remark, one light next step. A fact left in a thread
   is invisible to the next run and to the rank; a fact in a row is
   the house's record. The manifest's `candidate_facts` are the turns
   no insight cites yet — judge which of them carry a fact. Never
   index twice; never index a question.
2. **Answer every standing composition request** — a person's pull is
   never capped. The manifest's `offered_requests` is the list, and
   `offered` means unanswered by definition.
3. **Rework every bundle the house handed back** — an outcome in
   `iterating` is one a person kept and sent back: the goal is right,
   the plan is wrong, and **it has left their feed** until you answer
   (waymark-9xn). The manifest lists yours under *Handed back for a
   rework* with the plan version; read the outcome's thread for the
   note, withdraw the pieces that were wrong
   (`POST /api/outcome_pieces/<id>/-/rework` — the piece goes
   `reworked`, never declined), stage the replacements under the same
   bundle, then commit with
   `POST /api/outcomes/<id>/-/rework {says}`. That commit is the only
   door back to `offered`; until it lands nobody in the house can see
   the bundle at all. Never stage a twin instead, and never wait for a
   decline. A bundle being reworked by somebody else is listed apart
   (*iterating, not yours to rework*) — leave it, and say so in the
   journal if its composer is gone.
   **You cannot promise this one, you can only do it** (waymark-vf8):
   while a bundle of yours is `iterating` the remark door is CLOSED to
   you — a reply saying *understood, I will rework this* is refused by
   name, at the rework door's own address — and `says` on the rework
   is where your words go (required, 240). If you read the note and
   the plan still stands, or you cannot stage what was asked, commit
   the rework anyway and say that: **a round that changes no piece is
   a lawful answer**, the bundle goes back on the fridge, and the
   household decides. What is not an answer is silence dressed as
   one.
   **And read the MARKS before you read the note** (waymark-wxk): the
   manifest prints five lists per handed-back bundle, off the
   household's own per-piece verdicts. A piece declined **wrong time**
   is a **RE-TIME** — stage a NEW piece, the same step at a new hour.
   **Wrong piece** or **not this way** is a **REPLACE** — a NEW piece,
   a different step toward the same goal. **Never this**, or a decline
   that carried no word at all, is a **DROP** and needs nothing: the
   decline already took it out. A piece still standing is a **KEEP**
   and is not yours to withdraw. What the note asks that no list
   covers is an **ADD**. You never withdraw a marked piece — it is
   already out — and the rework commit is REFUSED by name while a mark
   is unanswered or a KEEP has been withdrawn, with the offenders
   listed. Where they marked nothing, the note is the whole order and
   the reading is yours.
   **And a time in the note is a RE-TIME even where nobody tapped
   wrong time** (waymark-o04, waymark-thn): the manifest reads the
   household's own clock times out of the thread and prints them under
   the bundle as SUGGESTED RE-TIME / INVOKE / ADD, beside a household
   clock with every local hour already converted — pick a row and
   write the UTC beside the local hour, never the local hour with a
   `Z`, because a round that changes no piece while `says` claims it
   added or moved one is a claim rather than an answer, and next run
   `verify` prints `CLAIMED, NOT STAGED` against your name.
4. **Answer every unanswered thread turn** — a `remark` whose last
   word is a person's is a work order: reply with a remark
   (`in_reply_to` naming theirs), restage citing the insight you
   indexed from their words, or both. The manifest lists the threads
   whose last turn is not yours; a turn by another *agent* is not a
   work order, so read who said it. When their turn changes an
   outcome's standing (a date slid, someone is sick), say so on that
   outcome in a reply — what changed, what you did, by id — so the
   feed can read why a bundle slipped without opening the thread.
5. **Work the manifest's WORK ORDERS — those, and no padding.** The
   reading a run works from is § "YOUR WORK ORDERS": at most two
   assignments the driver's probes built out of the snapshot, each
   already naming its subject address, its material, and the write it
   expects. Do them in the order given, one row each. They are a
   CEILING: anything past them is optional, an order you cannot answer
   honestly is skipped and said so in the journal, and a run with no
   work orders and nothing owed writes nothing at all — still a
   lawful, complete run. No outcome is ever manufactured to have done
   something. Behind each order is one of the same three moves, in rising order of what the evidence must
   support (the manifest's `arrivals` — rows CREATED since the last
   run, read from the engine's own log, plus any turn of a person
   still holding the end of its thread — are the material the probes
   drew from, and stay readable beside them):

   **a. Enrich a bare task (the lightest write, and a whole
   answer).** The manifest's `bare_tasks` are actionable tasks with no detail that no outcome or
   insight yet speaks for. Enriching one is always a lawful, useful
   run: publish an `insight` (`POST /api/insights`) whose `evidence`
   cites the task AND the source you read (a Gate email/chat, a
   related row), whose `finding` is the context that makes the task
   actionable — what it is really for, where/when/with what, its real
   next physical step — and whose `offer_kind`/`offer_id`/
   `offer_action` names the task's own next door — a door that asks
   for NOTHING (`complete` is the usual one on a task), because
   `offers-something-light` refuses anything a card cannot answer in a
   tap. `prioritize` takes a rank, so it is refused here however well
   it reads; a rank is prepared input and prepared input is an outcome
   PIECE's business. You never send `offer_href`: the engine derives
   the address from the kind and the id you named. This ANNOTATES the
   task beside it; it never edits the task's fields, because only the
   household edits its own rows. An enrichment that does not change
   whether the task is actionable is not worth writing.

   **b. Dig for the context — including beyond the house.** Email,
   Telegram and texts are capabilities through the Gate door — the
   manifest's `gate.tools` are the read tools this grant admits
   (`POST /api/-/gate/<tool>`; `emila__search`,
   `tgram__search_all_chats`, `messa__threads` and kin). What you
   learn there is evidence to ACT on, never an address to cite: cite
   the house rows it points at and name the source in the prose ("from
   an email of Aug 20"). **A chat is cited by its thread row** — the
   conversation itself is a row now (`/api/threads/<id>`, listed under
   `conversations` in the manifest), so a fact found in one cites that
   address plus the person rows the thread names, and the words stay in
   the prose. Never copy a message body into a row; never
   send or move anything — read tools only. When the grant admits no
   Gate tool the manifest names the anchored ask that opens it.

   **c. Compose an outcome — only when a real goal emerges.** Stage an
   outcome ONLY when an arrival plus its situated graph (the same
   person / project / value / time-window rows) implies a GOAL LARGER
   THAN ANY SINGLE EVIDENCE ROW — an end-state the household would
   want, not a task restated. **A bundle whose goal equals one task,
   or whose only work is re-prioritizing an existing task, is a
   wrapper, not an outcome — enrich instead.** Two compositions are
   lawful that are not any single row restated: a **work SESSION**,
   whose goal is one held block in which several like tasks are
   finished together and whose evidence is those tasks (they stand, so
   `composes-from-what-stands` admits them); and a **commitment found
   in a message**, whose goal is that commitment kept and whose
   evidence is the person row of whoever said it plus any related task
   or event — the message itself is never cited and never copied, its
   source is named in the prose. Every piece must serve
   that one goal (an unrelated piece stuffed in is the tell of a
   manufactured bundle). Never a twin of a `standing_outcome` — a
   candidate whose goal says the same thing, or cites the same
   evidence row, as one already offered or accepted is a twin, and the
   rank cannot tell twins apart. The evidence half of that is a DOOR
   now (`not-a-twin`, waymark-8gc): a shared evidence row with any
   standing bundle is refused at staging, naming it — so a twin costs
   you a round trip rather than passing quietly. The goal half is
   still yours to judge. **The insight half is a door too**
   (`one-live-finding-per-offer`, waymark-1ag): a second LIVE
   (published) finding offering the same
   `{offer_kind, offer_id, offer_action}` off one of the same evidence
   rows is refused, naming the standing finding's address — so index
   each question once. Answering it reopens it: a dismissed finding
   blocks nothing (it only weighs on the rank), a different next step
   on the same row is a different question, and so is the same next
   step reached from a different reading — which is what keeps two
   diagnoses owed on one value both publishable.
   *ranked, not capped* (waymark-1uv.3)
   still holds for real outcomes: stage every genuinely distinct one,
   and the crown's rank chooses what fills attention.

   **The diagnosis duty** survives, and it is owed at
   **RECOMPOSITION** — not at every decline (waymark-me9). Read the
   wall it serves: `no-burial-without-a-diagnosis` fires on
   `supersedes`, so the duty is the gate in front of RE-PROPOSING a
   line the house was shown and turned down. A decline nobody is
   re-proposing owes nothing at all: no insight, no score, no remark.
   An evening where the owner sweeps thirty stale wrappers must not
   become a morning where you publish thirty one-line diagnoses about
   wrappers — that is the feed flooded, not the law honored, and the
   insight rank counts every one.

   So the manifest splits the declines in two. **Declines OWED a
   diagnosis** is *only a prior you are about to recompose*: it still
   stands as shown-and-declined, no published insight cites it yet,
   and a work order or an offered request this run carries would
   recompose it — it works the rows the prior cited, or it names the
   prior's address outright. Publish one each, **never twice**
   (`declines_owed_a_diagnosis: 0` means diagnose nothing). Everything
   else is **Declined, not being recomposed — no diagnosis owed**,
   printed compactly with the house's own word so you can read the
   household's mind without writing a row about it.

   A diagnosis cites the `verdict_reason` row and quotes its word
   (wrong_time is not wrong_piece is not never_this); an empty
   `reasons` means say "declined, no reason given" and cite the
   outcome. Where the house left a NOTE, **the diagnosis is already on
   the record** — cite the note, do not restate it, and spend the
   finding on what changes. And the finding says **what the
   recomposition changes because of the decline**: "Declined with no
   reason given." is the manifest line copied out, it fits every
   declined row equally, and `verify` calls a run that publishes it
   more than three times a **DIAGNOSIS FLOOD**. Its offered step is
   ONE NO-INPUT door a STANDING row admits now (the manifest's
   `offer_candidates` — `complete` on a standing task is the usual
   shape; `prioritize` asks for a rank, so the insight door refuses it
   and the rank belongs in an outcome PIECE); the declined prior is
   terminal and admits none, so `expire`/`retire` on it is burial, not
   a step.

   If nothing arrived and no bare task is worth enriching, a run that
   writes nothing is a lawful no-op — journal nothing and leave.
6. **Score what you did not write — every run, composed or not.**
   Ranking is its own duty. The manifest's `unscored_bundles` is
   exactly the list, already filtered of your own rows — work it
   newest first, each a score 0–1 and one sentence (240 characters, no
   more); a run that stops short of three while three are listed has
   left this step undone. **Evidence is the bundle's whole `cite` list
   from the manifest** — the bundle, its value, its pieces, the
   house's verdict words on it, any insight about it — never the
   bundle alone: a score read off a headline is not a judgment. The
   sentence says what the bundle *stands on* in the house's own
   terms — the value it serves, what its pieces make ready, the
   timing, the decline word if one was said — not how the idea feels.
   "Woodworking is a strong family activity" is a vibe; "Declined as
   wrong_time, not wrong work — and this holds no hour: the same
   paperwork in four ten-minute pieces, one already moot because the
   office has the form" is a judgment. The door refuses
   your own rows and a second live note on one row from one author —
   a changed mind restates, never re-files. Your score is the
   judgment input; the order is the crown's declared rank, whose
   numbers move only through a `recipe_proposal` a person applies. At
   fleet scale this is how "without limit" also stays "ranked": the
   runs judge each other and the crown reads the scores. A run that
   found no arrival to advance, no bare task worth enriching and
   nothing owed writes nothing at all — and that is a correct,
   complete run. `sitting-run.sh verify` grades the previous
   manifest's work orders one line each — `ORDER <probe> <subject>:
   answered by <row>` or `UNANSWERED`, where answered means a row you
   wrote this run CITES the subject's address — and otherwise only
   faults a run that left a person's remark or a plainly-bare task
   untouched. An UNANSWERED order is not automatically a failure; an
   UNANSWERED order beside a journal that never mentions it is.

## The walls (the doors enforce these — trust the refusal sentences)

- Name a live value; cite everything you read; 2–5 pieces; the
  prepared input must fit the target door.
- **Compose from a book that is still open.** At least one row you
  cite must still stand — a task nobody has finished, an event still
  ahead, a person this house holds, a finding nobody has answered.
  Your own value does not count: that is what the bundle SERVES, and
  `value_id` already says it. A bundle whose every citation is a done
  task or a Saturday that has been and gone is refused, naming each
  row and the word it is finished with.
- **Stage a piece only behind a button that is there.** An invoke
  piece's door is judged AVAILABLE on that row now, exactly as the
  row's own envelope judges it, and the refusal quotes that door's own
  reason. A door shut only against YOUR hand is fine — a member taps,
  not you.
- **Every date you prepare is in the future.** The manifest gives you
  `now`; an event's `starts_at`/`ends_at`, a deadline, an hour held —
  all of them fall after it. A piece holding an hour already past is a
  bug a tap would faithfully record.
- **A piece makes something READY.** It opens a door that creates or
  holds — a task with its input prepared, an hour on the calendar, a
  list staged. A piece that fires a *completion* door (`complete`,
  `finish`, `done`, `take`) on a row that already stands is not
  friction pre-paid: nobody did that work, and a tap would record
  that they had. Friction pre-paid is a claim about the world.
- **A rework is yours only on what you staged.** `outcome.rework` and
  `outcome_piece.rework` open for the row's own composer with no grant
  at all, and for anybody else only under a grant that names the row —
  the mirror of four eyes, and the way an orphaned plan gets finished
  when its composer is gone. From `iterating` a person can still
  decline the whole bundle and the week can still end it; what they
  cannot do is accept a plan they have just called wrong.
- **And on a bundle you could rework, words are refused.** While an
  outcome sits in `iterating`, the one hand that can act on it — its
  composer, or an agent under a grant naming `outcome.rework` on that
  row — is refused a `remark` on it (`words-do-not-answer`), because a
  promise has no state and the thread would read as answered. Act at
  the door and say why in `says`. Every other hand still speaks: the
  person's turn, and an agent with no rework door on that row.
- **And the marks the household made are the order** (waymark-wxk).
  A piece declined with a quick word is a work order — wrong time is a
  RE-TIME, wrong piece or not this way a REPLACE, never this (or no
  word at all) a DROP that needs nothing — and a piece left standing
  is a KEEP. `the-marks-are-the-work-order` refuses the rework commit
  that withdraws a KEEP or leaves a RE-TIME or REPLACE without a new
  piece staged this round, naming each offender and its list. A marked
  piece is never withdrawn: a decline already takes it out, so a
  RE-TIME and a REPLACE are each one NEW piece under the same bundle.
  In a round where nothing was marked the wall stands down and the
  note is the whole order.
- Never tap a verdict or affirm a value or person unless your grant
  admits that door — and never on a row you wrote. Your standing
  sitting scope admits none of them, so under it the answer is still
  "never": the doors are simply absent from what you can see. If the
  owner wants you to decline a batch or affirm a reading in his name,
  he says so and approves an `approval_request` naming exactly those
  actions; only then does the door exist for you, and the transition
  records the grant you acted under. The row you WROTE is the one
  exception no grant reaches — four eyes, always. Never reword
  anyone's turn but your own. Never answer your own plan.
- Name a `companion_id` only off the manifest's `companions` — an
  unaffirmed person is not a usable companion.
- Read the engine-written impact line back on everything you stage.
- Before re-proposing anything declined: publish the diagnosis insight
  citing the decline first (no burial without a diagnosis) — and only
  then: a decline nothing is re-proposing owes no insight at all.

The full law: `docs/spec-outcome-menu.md` § "The composer contract".
The same walk in prose: `.claude/skills/sitting/SKILL.md`.

## The leash

Your grant expires. When it is inside the ask window the driver files
the anchored extend-ask for you — same scope, more time — and says so
in the manifest under `grant_watch`. That ask decides nothing: a human
taps it in the feed. **Report the ask id** so somebody knows to look.
Never widen the scope, never file a second ask while one stands.

## What a run never does

No git commits, no pushes, no PRs, no edits to this repository, no
beads issue writes outside the wisp. The one file a run may leave
behind is its own `.sitting/` snapshot, which is gitignored on
purpose. **A sitting leaves no diff**: the wisp appends to
`.beads/interactions.jsonl`, and `sitting-run.sh verify` restores it
— if your tree still shows a change after that, say so in the report
and do not publish it. A sitting is a door visit, not a development
session.
Development instructions for coding agents live in `CLAUDE.md` — they
do not apply to a sitting run.

## For the owner: what a Jules session needs

The runbook — the exact environment variables, setup script and queued
prompt to set in the Jules web UI — is
`docs/spec-standing-agent.md` § "Running a sitting on Jules".
