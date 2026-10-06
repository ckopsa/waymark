# Spec — quests

**Thesis.** A refusal names what stands between a person and a door.
A `quest` is the row that keeps that goal: one door on one row, and
the steps that lead to it. The person accepts the goal. The engine
finds the steps, and finds them again after every move.

Epic: 899239fc. This document covers Quests 1, the kind alone
(`waymark10/src/waymark10/server/quests.clj`). The kind plans nothing.
The consumer that hears `create` and `replan` and writes the plan is
Quests 1b.

## The kind

`quest`, plural `quests`, core's beside the walkthrough. States:
`active` (initial), `paused`, `finished`, `abandoned`. The last two
are terminal. Filterable: `owner`, `state`, `pinned`. The read stays
with grants.

The person writes, at create:

- `self`: the goal row's path, `/api/<plural>/<id>`;
- `action`: the goal door;
- `input`: optional, that door's input;
- `title`: optional, one line. Left empty, it is the door's display
  label and the row's label.

The engine stamps at birth, never from the body:

- `owner`: the creating principal;
- `grant`: the id of the grant the create was made under, or nothing
  for a person acting as themselves. Quests 1b plans as that caller.

A quest is born with an empty `plan`, no `planned_at` and `pinned`
false. A reader shows "planning" until the first plan lands.

## The doors

| Door | From → to | Who | What it does |
|---|---|---|---|
| `create` | → `active` | anyone who sees the goal row and, under a grant, is admitted to the goal door | accepts the goal |
| `pin` | `active` | owner | sets `pinned`, and unpins the owner's other quests |
| `unpin` | `active` | owner, or the engine inside a `pin` | clears `pinned` |
| `pause` | `active` → `paused` | owner | sets it aside, and unpins it |
| `resume` | `paused` → `active` | owner | takes it up again |
| `replan` | `active` | owner | no input; stamps `replan_requested_at` |
| `abandon` | `active`, `paused` → `abandoned` | owner | lets the goal go |
| `plan` | `active` | engine | writes the plan fields |
| `finish` | `active` → `finished` | engine | the goal door was taken |

The guards:

- at create, the owner sees the goal row and the goal door exists on
  its kind. The judge is the invitation's own (`sight-problem`), and
  this is law, not an accident of the reuse: a quest's goal is a door
  its owner could take. A caller wearing a grant accepts a quest only
  when the grant sees the goal row AND admits the goal door. A person
  acting as themselves (no grant) is judged by the row's existence.
  The reason: an owner whose grant can never take the goal would hold
  a quest that can never finish, and the planner's rehearsal under
  that grant could not even try the goal door. The refusal is the
  invitation's one sentence, "your grant does not see that row or
  does not admit that door.", so a row that does not exist, a row out
  of scope and a door the grant does not admit all read the same;
- one owner holds at most 20 active quests. Create and `resume` are
  both judged by it;
- only the owner takes `pin`, `unpin`, `pause`, `resume`, `abandon`
  and `replan`. `unpin` also admits the engine, and no other of these
  doors does;
- only the engine (a `:system` principal) takes `plan` and `finish`.

The engine keeps the one-pinned rule. One pinned quest per owner is
the engine's invariant, not a second move the caller makes. `pin`
unpins the others through their own `unpin` door, in the same
transaction, as the engine's actor (`waymark10-quests`) and under no
grant. So a grant that offers `pin` needs no `unpin`, and it need not
see the other quest. Each quest's history says when it left view and
that the engine took it out. Only an active quest is pinned: `pause`,
`abandon` and `finish` unpin.

## The plan and the step vocabulary

`plan` writes five fields and nothing else does:

- `plan`: the steps, in order;
- `planned_at`: when the plan was written (now, when the input names
  no time);
- `blocked_reason`: one sentence when no step can be taken now;
- `plan_is_estimate`: true when later steps may appear after the
  known ones are taken;
- `waiting_on`: who the head step waits on, by name.

One step is `{n, door, self, whose, note, needs, waiting_on, state}`:

- `n`: its number, counted from one;
- `door` and `self`: the action and the row path it acts on;
- `whose`: `person` (the owner takes it), `seat` (an agent's seat
  takes it), `held` (it waits for a person's approval), `confirm`
  (the owner must acknowledge a consequence), `choice` (the owner
  must choose an input);
- `note`: one sentence the owner reads;
- `needs`: the arguments of the door still to be given;
- `waiting_on`: who the step waits on, when it is not the owner's;
- `state`: `done`, `next` (the one to take now), `waiting` (on
  someone else), `later` (a step before it is not done).

**A step that needs input on a bound row is the person's.** When a
refusal binds the row its remedy acts on and that door still needs an
argument, the plan carries a step on that row with the argument in
`needs`, and the owner takes it each time. `children-are-finished`
binds the oldest unfinished child, so each child's `ticket.complete`
is its own step with `needs` `close_reason`: no guard declares one
sentence for every child. The tracker's Go opens that row's dialog
for the door with the `needs` fields lit.

**A shut goal is the last step, from the first plan.** A door its row
does not afford shows no form, so the rehearsal names no `needs` for
it. When the goal's row does not afford the goal's door, the planner
reads the goal from the kind's declaration (`declared-goal` in
`waymark10/src/waymark10/server/quests.clj`) and `answer->plan` puts
it last, after the steps that open it. Its `needs` are the door's
required arguments in the resource definition, in the declaration's
order, less those the quest's stored `input` gives, and of those only
the ones an invitation may show. Its `note` stays empty, because no
guard was asked about the form; only when a seat's step stands before
it does it carry the planner's own sentence that more may follow. The owner's ruling of 2026-10-06
(bd1fd962): the goal's form is the quest's last step, shown from the
first plan. `declared-goal` answers nothing when the goal's row is of
no served kind or the kind has no such door, and the plan is then the
rehearsal's answer alone.

**Counts.** The engine learns steps as the house moves, so a plan is
never a total. A reader counts "k done, n known so far": k steps in
`done`, n steps in the plan. It never says "k of n".

## What a reader sees

A summary row carries no vector, so the kind works out two lines at
read time: `progress` ("k done, n known so far") and `next_step` (the
head step's note, or "waiting on <name>"). The collection orders the
pinned quest first and then the newest.

The quest's row page shows the plan as a checklist (`questPlan` in
`ui/200-events-follow.js`): a `done` step is struck through, the
`next` step is lit and carries **Go**, a `waiting` step names who it
waits on ("your tap" for a held call, linked to the step's row), a
`later` step is dim, and a `choice` step names what must be picked.
Go opens the viewer's open invitation for that row and door, and
goes to the step's row when there is none.

## A tap previews, Accept creates

A shut door that names a way out is drawn as a reachable button
(`shutDoor` in `ui/140-links-access.js`). A tap on it makes no quest.
It rehearses the quest's create door (`POST /api/quests?dry_run=1` with
the goal's `self` and `action`), and the rehearsal answers a `preview`
beside its verdict:

- `plan`, `plan_is_estimate` and `blocked_reason`, in the shape of a
  planned quest's: the plan the planner would write for this owner
  under this grant (`rehearsed`), with the goal's own step last and its
  `needs`. Each step is said in words too (`in-words`): `door_label`,
  the door's display label, `row_label`, the row's label when the
  principal's grant sees the row, and `needs_labels`, the display label
  of each of `needs` from the door's input schema. A door or a field
  that declares no label is its name in words;
- `goal`, the line the quest's title defaults to;
- `shut_reason`, the goal door's own refusal now.

The preview is a read. It writes no row, makes no invitation and fires
no transition, and there is no `proposed` state. The kind declares it
as `:on-rehearse` (`preview` in `quests.clj`), which the create door
asks only on a full rehearsal and only after the create guards passed.
So a goal that create would refuse (`the-owner-sees-the-goal`,
`active-quests-are-few`) is refused by the rehearsal with the same
sentence.

The page shows the preview in a sheet (`questSheet`): the goal as the
door's label on this row, with `goal` under it, why it is not available
yet, the numbered steps with whose turn each is (the door's label, the
row's label with its path as the step's title, and the labels of the
fields it asks for), the estimate note, and **Not now** and **Accept quest**. Not now closes the
sheet. Accept creates the quest and pins it. A refusal is said in the
sheet, and Accept is disabled with that line.

A recording keeps the sheet. While it is open, the recorder's `ui` beat
carries a `quest` part: the goal, the door's label and what the
rehearsal answered (docs/spec-guided-follow.md § 1). The beat after it
closes carries none. A replay draws the sheet from that part
(`replayQuestSheet`), asks the engine nothing and writes nothing. Its
pointer presses the shut door, then **Accept quest** at the recorded
create, or **Not now** at the beat that closes a sheet no quest was
made from. An Accept the recording has refused (the create or the pin:
a `refusal` frame while the sheet is open) is a press on **Accept
quest** too, and the replay says the sentence in the sheet's own box
with Accept disabled (`replaySheetRefused`), not in the caption band.

A person following live in guided mode sees the same sheet from the
same part, read-only, and it closes with the followed person's
(docs/spec-guided-follow.md § 2).

An agent's recording keeps the sheet too. While the caller records a
self walk, the connector stages a rehearsed quest create
(`waymark_invoke kind:quest action:create dry_run:true`) as that same
`ui` beat on the goal's row, and no create form. The caller's next
quest create for that goal is Accept, and any other staged call is Not
now (docs/spec-agent-demo-walks.md § 2).

## What this change does not do

- It plans nothing and finishes nothing: no consumer is started.
  (Quests 1b.)
- It adds no UI: no "Accept as quest" on a refusal and no tracker.

## The tracker (Quests 5)

The generic UI shows the viewer's pinned active quest in the header's
own row (`#questbar`, `ui/120-nav-home.js`), so it is on every page and
covers none of a page's actions. It reads the quest row and nothing
else: `/api/quests?state=active&pinned=true&owner=<viewer>`, then that
row's envelope.

- The title links to the quest's row page.
- "planning…" stands until `planned_at` is written.
- The count is "k done, n known so far", with a thin bar of done over
  known.
- The head step is the first one not `done`. Its note stands beside the
  count. A `seat` or `held` head, or one in `waiting`, reads "waiting
  on <waiting_on>" with a live dot.
- **Go** opens the head step's `door` on its `self`, with its `needs`
  lit and its note shown. On the goal's own step the form holds the
  quest's stored `input` already, as the owner's own values and with no
  suggestion mark; the step's invitation carries the same values as
  `given`, which only the engine writes, so the quest page's Go shows
  them too. A stored key the invitation may not show, a secret argument
  or one the door does not take, is left out alone. It goes through `openDoor`
  (`ui/180-action-dialog.js`), the helper an invitation opens through.
  Go is disabled while the head waits. It is not shown when the plan
  has no step to take or `blocked_reason` is written; the reason is
  shown then.
- A small menu offers `pause`, `unpin` and `replan`, each only when the
  row offers that door to the viewer.

The tracker follows the quest in hand on that quest's own event stream
(`/api/quests/<id>/-/events`), as a row page follows one row, for every
viewer, granted or not: a grant that admits the quest admits its
stream, and under a grant the live stream carries no row events. A
transition of the quest reads it again, and `finish` shows "Quest
complete" with the title for four seconds before the tracker hides. The
stream closes when the tracker lets the quest go and another opens when
it takes one up; the quest is read again each time a stream opens and
each time the tab becomes visible. A `pin` of a quest not in hand still
comes off the live stream, so under a grant a first pin shows at the
next page load or the next return to the tab.
