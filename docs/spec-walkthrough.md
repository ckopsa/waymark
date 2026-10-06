# Spec — walkthroughs

**Thesis.** An invitation hands a person one step. A real task is
several steps in an order, some the person's and some the agent's. A
`walkthrough` is the row that holds that order. The engine opens each
step when the one before it ends, the person sees how far along they
are, and the agent is woken when it is its turn. Nobody polls.

Epic: 3eb945cf ("Walkthroughs: an agent leads a person through an
ordered workflow"). This document is the design pass the epic asks
for, and it files no code. It stands beside
`docs/spec-guided-follow.md`, and section numbers below are this
document's own unless they say "guided follow".

## Epistemic status

A design, read against the code as it stands on 2026-10-01. It adds
one kind and one consumer, and it grows the `invitation` kind by four
fields. It changes no law that exists: a person still submits every
step under their own grant, and the engine still submits nothing for
anyone.

## What exists

- **The invitation** (`server/invitations.clj`, guided follow § 3).
  One row points one person at one `field` of one `action` on one row
  path (`/api/<plural>/<id>`). The create guards judge the author's
  grant and the row's own availability, never the subject. `suggest`
  is shown and never submitted by the engine.
- **Its resolution.** A durable log consumer (`:invitations`) hears
  every committed transition. When the actor is an open invitation's
  subject and `(self, action)` match, the engine walks `answer` and
  stores the transition's log id in `answered_by`. A sweep walks
  `expire` past `expires_at`, a week out by default.
- **The wake** (`server/wakes.clj`). A seat's `wake_on` is a list of
  entries `{kind, actions, filter}`. A committed transition that
  matches one fires the seat with the text `{kind, id, action, from,
  to}`. The `filter` is read against the row AFTER the transition
  committed (`moved-under?`), with no default filters. Only `seat`,
  `sitting`, `schedule` and `subscription` never wake a seat. A seat
  with an open sitting is not fired a second time: the match is kept
  as `wake_pending`, and the next fire names no row. An interactive
  seat is never fired.
- **So an invitation's author can already be woken.** The entry
  `{kind: "invitation", actions: ["answer", "decline", "expire"],
  filter: {author: "<pid>"}}` is legal today (`author` is filterable).
  Guided follow § 3 says the agent "reads the row until it leaves
  open"; that sentence predates the wake and is no longer the only
  way.
- **Guided mode** (`200-events-follow.js`). `follow(actor, {ui:
  true})` shows the followed principal's `ui` frames read-only. An
  agent reports them with `POST /api/-/presence {self, ui}`.
- **The UI's invitation** (`openInvitation` in `180-action-dialog.js`).
  It opens from the invitation's row page and from its collection row
  ("Take this step"). It navigates to `self`, opens the dialog in the
  person's hand, lights the one field and shows the note, with a
  Decline button. An invitation that arrives while the person is on
  another screen opens nothing: no code hears it on the firehose yet.
- **Walks** (`server/walks.clj`). `walk` and `walk_frame`, export and
  replay exist. `walks/record-frame!` has no caller outside tests, so
  nothing is recorded for real until 78cce1e5 lands. Replay counts an
  `invitation` frame and passes over it.

The gap: nothing groups invitations, orders them, shows progress, or
tells the agent that it is its turn.

## 1. The `walkthrough` kind

```
walkthrough
  fields: author (pid, set by the engine), subject (pid: the person),
          subject_name (set by the engine, as invitation does),
          title (one line: "Grooming a ticket"),
          steps (1 to 20, in order: section 2),
          current (1-based; the step that is open; count + 1 when
                   every step has ended),
          waiting_on ("person" | "agent": who the current step is),
          step_opened_at (when `current` last became current, or was
                          resumed),
          outcomes (one entry per ended step: {step, outcome, at, by}),
          stopped_by (pid), stop_reason (one sentence),
          walk (the recording, optional: section 6)
  states: open -> running <-> stopped
          running -> finished
          open | running | stopped -> withdrawn
  doors:  create    the author, under its own grant
          start     the subject                    open -> running
          stop      the subject, the author, or the engine
                                                   running -> stopped
          resume    the subject; the author too, unless the subject
                    was the one who stopped        stopped -> running
          advance   the author, on an agent step   running -> running
          withdraw  the author           open|running|stopped -> withdrawn
          step      the engine only (hidden)       running -> running
          finish    the engine only (hidden)       running -> finished
```

`author`, `current`, `waiting_on`, `step_opened_at`, `outcomes`,
`stopped_by` and `subject_name` are the engine's to write. The create
model is `subject`, `title` and `steps`.

**`open` is an offer.** A walkthrough is born `open` and nothing
happens until the subject taps Start. The person agrees to be led
before the first step opens, and an agent cannot fill a person's
invitation list by itself. A person who does not want it leaves it
`open`; the author may withdraw it.

**The author is any named principal.** The epic says "agent", and an
agent is the usual author. Nothing in the kind requires it: a person
leading another person works the same way.

**Who may do what.**

| act      | who                                   | why |
|----------|---------------------------------------|-----|
| start    | the subject                           | consent to be led |
| skip     | the subject, on a person step         | it is their step to refuse (section 5) |
| stop     | the subject or the author; the engine when a step cannot open or expires | either side may pause |
| resume   | the subject always; the author unless `stopped_by` is the subject | an author never overrides a person's stop |
| advance  | the author, on an agent step          | only the agent knows its own step ended |
| withdraw | the author                            | it takes back what it offered |

Skip is not a door of this kind. It is the invitation's own `decline`
door, drawn as "Skip" inside a walkthrough (section 5). The person
cannot skip an agent step; they can stop.

**Guards on `create`.** Each step is judged as an invitation is
judged, under the author's own grant:

- For every step that names `self` and `action`, the author's grant
  must see the row and admit the action. The refusal is the
  invitation's one sentence, so it never says whether a row the author
  cannot see exists.
- On a person step, every name in `fields` must be an argument of the
  action's input, and none may be secret-marked. Every key of
  `suggest` is judged the same way.
- Whether the row can take the door NOW is **not** judged at create.
  Step 3's door is often shut until step 2's transition commits. It is
  judged when the step opens (section 3).

The guards do not judge whether the subject can take any door. That is
judged at the subject's own invoke, as it is for an invitation.

**Handlers.** `start` and `resume` stamp `step_opened_at` and
`waiting_on`. `advance` and `step` share one handler: it appends the
outcome, adds one to `current`, and stamps `step_opened_at` and
`waiting_on` for the new step (`waiting_on` is null past the last
step). `stop` stamps `stopped_by` and `stop_reason`. None of them
touches another row; section 3's consumer does that.

**Filterable:** `state` (eq, in), `subject`, `author`, `waiting_on`
(eq). The default filter is `state=open,running,stopped`. An agent's
queue is `?author=<pid>&state=running&waiting_on=agent`.

## 2. A step

A step is a map with `who`, and the rest depends on it.

```json
{"who": "person",
 "self": "/api/tickets/0abb…", "action": "groom",
 "fields": ["priority", "type"],
 "note": "Set the priority and the type, then groom it.",
 "suggest": {"priority": 2}}

{"who": "agent",
 "self": "/api/tickets/0abb…", "action": "assign",
 "note": "Watch me: assigning the ticket to the code seat."}
```

**A person step** names `self`, `action`, `fields` (one to eight
arguments of that action), `note` and optional `suggest`. These are
the invitation's own fields with the invitation's own limits, because
the engine makes an invitation from them, unchanged.

**An agent step** names `note` (what the person reads while the agent
works) and, optionally, `self` and `action`:

- `self` is where the person's screen goes while they watch.
- With `self` and `action`, the step ends when the author's own
  committed transition matches them. The engine sees it and no door is
  tapped.
- Without `action`, the step is the agent's turn with no single
  transition to wait for ("Watch me: reading the three open tickets").
  It ends when the author takes `advance`.

`advance` is open to the author on every agent step, so an agent whose
work went another way than the step named can still move on. The
outcome says which happened: `by` holds the transition's log id when
the engine matched one, and is absent when the author advanced by
hand.

**Outcomes.** `answered` (the person took the step), `skipped` (the
person declined it), `done` (the agent's step ended). Each entry is
`{step, outcome, at, by}`. `by` is a log id: the person's own
transition for `answered`, the person's `decline` of the invitation
for `skipped`, the author's transition for a matched `done`.

## 3. How the next step opens, and how the author learns

### One consumer, and one rule

A second durable log consumer, `:walkthroughs`, in a new
`server/walkthroughs.clj`, started beside the invitations consumer in
`modules.clj`. The agent never opens a step and never polls for one.

It is a second consumer and not more code inside the invitations
consumer. The invitation's `answer` is itself a logged transition, so
the chain "person acts, invitation is answered, walkthrough steps,
next invitation opens" is four log entries, each heard from the log. A
crash between any two is replayed from the cursor. Doing the advance
inside the invitations consumer would put one link of the chain in
memory.

What it hears, and what it does with the engine's hand:

| it hears | it walks |
|----------|----------|
| `invitation.answer` on the current step's invitation | `step {outcome: "answered", by: answered_by}` |
| `invitation.decline` on it | `step {outcome: "skipped", by: <the decline's log id>}` |
| `invitation.expire` on it | `stop {reason: "Step N waited past its time."}` |
| `invitation.withdraw` on it, by the author's own hand | `stop {reason: "The author took step N back."}` |
| the author's transition matching the current agent step's `(self, action)`, committed after `step_opened_at` | `step {outcome: "done", by: <its log id>}` |
| any `walkthrough` transition | **reconcile** that row |

**Reconcile** makes the world match the row, and it is the only place
an invitation is opened or closed:

- `running`, and `current` is past the last step: walk `finish`.
- `running`, and the current step is a person step with no open
  invitation of this walkthrough for that step: create one.
- not `running`: withdraw every open invitation of this walkthrough.
- `finished` or `withdrawn`, with a `walk` still `recording`: seal it
  (section 6).

Reconcile reads before it writes, so a replayed transition changes
nothing. The invitation's create also carries an idempotency key made
of the walkthrough id, the step number and `step_opened_at`: a replay
opens no second invitation, and a resume (a new `step_opened_at`)
opens a fresh one.

**A step that cannot open stops the walkthrough.** The engine's create
meets the invitation's own guards. When the row cannot take the door
now, or the row is gone, the consumer walks `stop` with the refusal's
sentence as `stop_reason`. The author is woken by that `stop` and
reads why.

### What the invitation gains

```
invitation
  + fields       section 4
  + walkthrough  ref to the walkthrough, or null
  + step, of     the step's number and the step count (2 and 4)
```

- `walkthrough`, `step` and `of` are written by the engine only. A
  create that names `walkthrough` from any other hand is refused.
- An invitation the engine makes for a walkthrough is stamped with the
  walkthrough's `author`, not the engine's id. The audit still reads
  "the agent asked, the person did it", the author can still read and
  withdraw it, and an `author` filter still finds it.
- `withdraw` admits the engine on an invitation that has a
  `walkthrough`, and nobody else new.
- `walkthrough` is filterable (eq).
- **A transition older than the invitation does not answer it.** The
  invitations consumer compares the transition's time with the
  invitation's birth. Without this, two neighbouring steps on the same
  `(self, action)` could both be answered by one replayed transition.
  The same rule is why the agent-step match reads `step_opened_at`.

### How the author learns

The author is woken by the walkthrough, not by its invitations. A seat
that leads walkthroughs writes one `wake_on` entry:

```json
{"kind": "walkthrough",
 "actions": ["start", "step", "resume", "stop", "finish"],
 "filter": {"author": "seat:<seat id>", "waiting_on": "agent"}}
```

The filter is read after the transition commits, so this entry fires
only when the row now waits on the agent: the person started and step
1 is the agent's, or a person step ended and the next is the agent's.
A seat that also wants to hear a stop or the finish adds a second
entry without `waiting_on` and with `actions: ["stop", "finish"]`.

The fire's text names the walkthrough. The session reads the row:
`current` says which step, `outcomes` says how each earlier step ended
and which transition ended it. It does its step and stops. When the
wake was damped (the seat was still sitting), the next fire names no
row and the session walks its queue, which is section 1's
`waiting_on=agent` query.

This needs no change to `wakes.clj` or to the seat's schema:
`walkthrough` is an ordinary kind, and `author` and `waiting_on` are
filterable. The child is the test and the routine text.

An author with no seat (a connector session, an interactive seat) has
no wake, because nothing can fire it. It reads the one walkthrough row
instead of every invitation.

## 4. Several fields on one invitation

**`field` becomes `fields`**, a list of one to eight argument names.

- `fields` is what the row stores and what every reader should read.
- `create` still accepts `field` alone, as the spelling for a list of
  one. A body that names both is refused.
- The engine stamps `field` with the first of `fields` on every new
  row, so a reader that knows only `field` still works: the walk frame
  body 78cce1e5 pins, `walks/export-part`, and an older page.
- Rows born before this hold `field` and no `fields`. They are not
  rewritten. A reader takes `fields`, or `[field]` when it is absent.
- `the-field-is-an-open-argument` judges every name. One bad name
  refuses the whole create and the refusal names it. The same guard
  now judges the keys of `suggest`: today nothing does, so `suggest`
  can carry a value for a secret argument.

**In the UI** the list's order is the author's reading order:

- Every named field gets the `.invited` ring. The animation plays once
  on all of them.
- The first named field is scrolled into view and focused, and the
  note sits beside it, once.
- Each further field shows a small ordinal ("2", "3") in its label, so
  the note's "then" has something to point at.
- A named field the person's own form does not show is passed over
  without a message. Their grant's projection of the form is the law,
  and the page does not say that a field was hidden.

## 5. What the person sees

**The offer.** An `open` walkthrough addressed to the person is a row
in their walkthrough collection and a line on Home: "Planner wants to
walk you through: Grooming a ticket (4 steps)", with Start. Its row
page lists every step's note in order. The person sees the whole path
before they agree to it.

**The walkthrough chip.** Once started, the walkthrough is "in hand"
in this tab (`walkthroughId` in localStorage, as `followId` is). A
chip beside the follow chip shows it on every screen:

```
Step 2 of 4 · Grooming a ticket      [Take this step] [Skip] [Stop]
```

- On a person step: Take this step (opens the invitation, as today),
  Skip and Stop.
- On an agent step: "Step 3 of 4 · Planner is working: <note>" and
  Stop. There is no Skip.
- Stopped: "Stopped at step 2 of 4" and Resume.

The chip is why the progress shows on the invited row: it is on every
row. A tab with nothing in hand asks once at boot for the person's
newest `running` walkthrough and takes it in hand.

**The dialog.** An invitation that has a `walkthrough` opens as today,
with a line above the form, "Step 2 of 4 · Grooming a ticket". The
Decline button reads Skip, and Stop sits beside it.

**The invitation list.** A walkthrough's invitation shows a "step 2 of
4" mark before its note, from the row's own `step` and `of`, with no
second read.

**The next step arrives by itself.** The page hears the firehose. On
an `invitation` `create` it reads that row; when its `walkthrough` is
the one in hand and its subject is the viewer, it opens it
(`openInvitation`), unless a dialog the person opened is on screen. On
a `walkthrough` transition of the row in hand it reads the row again
and redraws the chip. No stream is added.

**Watching an agent step.** Tapping Start or Resume also follows the
author in guided mode in this tab (`follow(author, {ui: true})`): the
tap is the follower's ask that guided follow § 2 requires. On an agent
step the screen goes to the step's `self` and shows what the agent
reports, read-only. The agent shares its `ui` frames or it does not;
the chip's note is there either way. When the next person step opens,
it opens over any guided dialog, as an invitation already does.

**What Skip records.** Skip invokes the invitation's `decline` door in
the person's own hand. Three records follow, and each is true:

- the invitation is `declined`, by the subject, in its own history;
- the walkthrough's `outcomes` gains `{step, outcome: "skipped", by}`,
  where `by` is that decline's log id;
- the walkthrough's history shows the engine's `step`, and the next
  step opens.

Skipping the last step finishes the walkthrough. The author is woken
as for any step, reads `skipped`, and decides whether the rest still
makes sense; it may `stop` or `withdraw`.

**What Stop records.** `stop` on the walkthrough, with `stopped_by`.
Reconcile withdraws the open invitation, so it leaves the person's
list. Resume opens a fresh invitation for the same step.

## 6. Recording

A walkthrough can be recorded as a walk (guided follow § 4) and
replayed as onboarding material. **This needs 78cce1e5 on main**:
until a follower's stream writes frames for real, there is nothing to
record.

**The person is the recorder.** A walk is recorded under its
recorder's sight, and its recorder is whoever created it. So the walk
is created in the person's hand, not the engine's and not the
agent's:

- Start offers "Record this walkthrough". When ticked, the page
  creates a `walk` (`followed` = the author, `title` = the
  walkthrough's title) and passes its id as `start`'s `walk` input.
- `start` refuses a `walk` that is not the subject's own, is not
  `recording`, or follows anyone but the author.
- Stop does not seal it. A resumed walkthrough keeps recording, and
  replay already caps a long gap.
- `finish` and `withdraw` seal it, through reconcile, with the
  engine's hand (`the-recorder-or-the-sweep` already admits it).
- A walkthrough left stopped is covered by the walk's own retention
  sweep, which purges a `recording` walk too.

**What 78cce1e5 records is the agent's half.** It writes the followed
principal's `move` and `ui` frames, its transitions, and an
`invitation` frame when the followed principal creates one. A
walkthrough needs three more things:

1. *The invitation frame is keyed on the invitation's `author`, not on
   the hand that created it.* The engine creates a walkthrough's
   invitations. A frame is written when an invitation whose `author`
   is the followed pid and whose `subject` is the recorder is born.
   Its pinned body gains `fields`, `walkthrough`, `step` and `of`. It
   carries `given` beside `suggest` when the invitation has given
   values (engine-written, guided follow § 3), and the export's
   invitation line does too. Both keep only the keys the reader's
   `:arg?` admits, as `suggest` does.
2. *The recorder's own answer is a frame.* When the recorder's own
   transition answers such an invitation, a `transition` frame is
   written for it, projected by the recorder's visibility like any
   other. Without it the replay shows the question and never the
   answer.
3. *Replay draws an `invitation` frame.* It opens the dialog with the
   named fields lit, the note and the "Step 2 of 4" line, read-only.
   `suggest` values fill their fields marked as suggestions, and
   `given` values fill theirs with no mark. Nothing submits either.
   The next `transition` frame closes it.

What the person typed is not recorded. Their form is theirs unless
they turned on "share my screen" (guided follow § 2), and a recording
does not change that. The replay shows what was asked and what the
row became.

## 7. The children

Each child is one PR under the bench's ceiling, in this order. 1 to 4
are the engine; 5 and 6 are the page; 7 waits for 78cce1e5.

1. *An invitation names several fields.* Covers `fields` on the kind,
   `field` as the one-field spelling and the stamped first, the guard
   over every name and over `suggest`'s keys, and the UI lighting each
   field with the note beside the first (`180-action-dialog.js`).
   Tests in `waymark10.invitations-test`:
   `an-invitation-names-several-fields`,
   `field-alone-still-creates-and-reads-as-a-list-of-one`,
   `field-and-fields-together-are-refused`,
   `one-secret-among-the-fields-refuses-the-create`, and
   `a-suggest-key-that-is-secret-or-no-argument-is-refused`. In
   `waymark10.ui-test`: `ui-lights-every-invited-field`.
2. *The `walkthrough` kind.* Covers the schema, the create guards over
   every step, the doors and their handlers, and the filters. No
   consumer. Tests in a new `waymark10.walkthroughs-test`:
   `a-walkthrough-is-born-open-with-its-author-stamped`,
   `an-author-cannot-name-a-step-it-cannot-see`,
   `a-secret-field-in-any-step-refuses-the-create`,
   `a-door-shut-now-does-not-refuse-the-create`,
   `only-the-subject-starts`,
   `the-author-does-not-resume-the-subjects-stop`,
   `advance-is-the-authors-and-only-on-an-agent-step`, and
   `step-and-finish-are-the-engines-alone`.
3. *The consumer.* Covers `:walkthroughs`, reconcile, the invitation's
   `walkthrough`, `step` and `of`, the engine-made invitation's author
   stamp, the engine's `withdraw`, and the older-transition rule.
   Tests in `waymark10.walkthroughs-test`:
   `start-opens-the-first-person-step`,
   `an-answer-opens-the-next-step`,
   `a-declined-step-is-recorded-skipped-and-the-next-opens`,
   `the-authors-own-transition-ends-an-agent-step`,
   `a-stop-withdraws-the-open-invitation-and-a-resume-opens-a-fresh-one`,
   `an-expired-step-stops-the-walkthrough`,
   `a-step-the-row-cannot-take-stops-it-with-the-reason`,
   `the-last-step-finishes-it`, and
   `a-replayed-transition-opens-no-second-invitation`. In
   `waymark10.invitations-test`:
   `only-the-engine-names-a-walkthrough`, and
   `a-transition-older-than-the-invitation-does-not-answer-it`.
4. *The author is woken on its turn.* Covers the `wake_on` entry of
   section 3 and a walkthrough recipe in `docs/routines/`. No engine
   code is expected; if the tests show otherwise, the fix belongs
   here. Tests beside the existing wake tests:
   `a-seat-is-woken-when-its-walkthrough-waits-on-it`,
   `a-person-step-opening-does-not-wake-the-author`, and
   `a-damped-wake-leaves-the-walkthrough-in-the-seats-queue`.
5. *The page leads a person through the person steps.* Covers the
   walkthrough chip, the step line and Skip and Stop in the dialog,
   the step mark in the invitation list, the step list on the row
   page, and the next step opening from the firehose. Tests in
   `waymark10.ui-test`: `ui-draws-step-n-of-m-on-an-invited-dialog`,
   `ui-offers-skip-and-stop-inside-a-walkthrough`, and
   `ui-walkthrough-page-lists-its-steps`. **The browser drive** is a
   new section of `ui-drive.mjs`'s `access` mode (the `ui-access` job).
   `waymark10.access-dev` seeds a three-step walkthrough by the
   mail-desk sitter for the member the drive signs in as: a person
   step naming two fields, an agent step with no `action`, a person
   step. The drive taps Start, reads "Step 1 of 3" and two lit fields,
   submits, reads the chip's agent line, takes `advance` as the sitter
   through the API, sees step 3's dialog open with no reload, taps
   Skip, and reads the walkthrough `finished` with outcomes answered,
   done, skipped. `access-dev`'s engine must run both consumers.
6. *The page shows an agent step.* Covers Start and Resume following
   the author in guided mode, the screen going to the agent step's
   `self`, and the person step opening over a guided dialog. Tests in
   `waymark10.ui-test`: `ui-start-follows-the-author-in-guided-mode`
   and `ui-an-agent-step-offers-stop-and-no-skip`. The `guided` drive
   gains one check: an agent step's `ui` frame shows on the subject's
   screen under the chip.
7. *A walkthrough is recorded.* After 78cce1e5. Covers `start`'s
   `walk` input and its guard, the seal on finish and withdraw, the
   three additions of section 6, and replay drawing the invitation
   frame. Tests in `waymark10.walks-test`:
   `a-walkthroughs-invitation-is-recorded-whatever-hand-made-it`,
   `the-recorders-own-answer-is-a-frame`,
   `finish-seals-the-walkthroughs-walk`, and
   `start-refuses-a-walk-that-is-not-the-subjects-own`. In
   `waymark10.ui-test`: `ui-replay-draws-an-invitation-frame`.

## Punts

- **A step on a collection's create door.** An invitation's `self` is
  a row path, so "create a ticket" cannot be a person step today. That
  is the invitation's limit and is its own ticket.
- **A step whose row an earlier step makes.** Steps are fixed at
  create. The way through, when it is wanted, is an `amend` door: the
  author replaces the steps after `current`, under the create guards,
  while an agent step holds the walkthrough. It is not in the children
  above.
- **Required steps.** Every person step can be skipped. The author
  reads `skipped` and may stop. A step that offers no Skip waits for a
  case that needs it.
- **The author's grant at each step.** Steps are judged once, at
  create. A grant that narrows later does not close a walkthrough
  already written: the steps carry paths and notes the author was
  admitted to write, no credential, and the person acts under their
  own grant. Judging again as each step opens needs the engine to hold
  the author's grant, and it does not.
- **Two walkthroughs at once.** Nothing forbids it. One is in hand per
  tab, and the other waits in the list.
- **A walkthrough-level expiry.** There is none and no new sweeper.
  The current invitation's own expiry stops the walkthrough, and a
  stopped walkthrough waits until someone resumes or withdraws it.
