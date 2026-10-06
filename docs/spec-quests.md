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
| `create` | → `active` | anyone who sees the goal | accepts the goal |
| `pin` | `active` | owner | sets `pinned`, and unpins the owner's other quests |
| `unpin` | `active` | owner | clears `pinned` |
| `pause` | `active` → `paused` | owner | sets it aside, and unpins it |
| `resume` | `paused` → `active` | owner | takes it up again |
| `replan` | `active` | owner | no input; stamps `replan_requested_at` |
| `abandon` | `active`, `paused` → `abandoned` | owner | lets the goal go |
| `plan` | `active` | engine | writes the plan fields |
| `finish` | `active` → `finished` | engine | the goal door was taken |

The guards:

- at create, the owner sees the goal row and the goal door exists on
  its kind. The judge is the invitation's own (`sight-problem`), so a
  granted caller's grant must also admit that door, and a row that
  does not exist reads the same as a row out of scope;
- one owner holds at most 20 active quests. Create and `resume` are
  both judged by it;
- only the owner takes `pin`, `unpin`, `pause`, `resume`, `abandon`
  and `replan`;
- only the engine (a `:system` principal) takes `plan` and `finish`.

`pin` unpins the others through their own `unpin` door, in the same
transaction, so each quest's history says when it left view. Only an
active quest is pinned: `pause`, `abandon` and `finish` unpin.

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

**Counts.** The engine learns steps as the house moves, so a plan is
never a total. A reader counts "k done, n known so far": k steps in
`done`, n steps in the plan. It never says "k of n".

## What this change does not do

- It plans nothing and finishes nothing: no consumer is started.
  (Quests 1b.)
- It adds no UI: no "Accept as quest" on a refusal and no tracker.
