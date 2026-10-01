# Routine: a seat that leads walkthroughs

A walkthrough is one row that holds the steps of a task in their
order (docs/spec-walkthrough.md). Some steps are the person's and some
are the agent's. The engine opens each step. This file tells you how
to make a fired seat the author of walkthroughs, so that the engine
wakes the seat when a step is the agent's. The seat does not poll.

This file holds what is different for a walkthrough. The chair key,
the fire link, the seat's place, the environment and the Stop hook are
the same as for every fired seat, and docs/routines/ci-classifier.md
says them once.

Written in ASD-STE100 Simplified Technical English.

## Step 1: the seat

Create the seat as a `fired` seat. Its scope must name `walkthrough`
with the doors the author takes, and each kind and door that a step
names. The create guards of a walkthrough judge each step under the
author's own grant, so a step that the scope does not admit is refused
at `create`.

```json
[
  {"kind": "walkthrough",
   "actions": ["create", "advance", "stop", "resume", "withdraw"]},
  {"kind": "ticket", "actions": ["groom", "assign"]}
]
```

The `ticket` entry is an example. Write the kinds that your steps
name.

## Step 2: the wake

The create answers the seat's id. A sitting of the seat acts as the
principal `seat:<seat id>`, and the engine stamps that id as the
`author` of each walkthrough the seat creates. The wake entry names
that id, so write the entry with `restate` after the create.

```json
{"kind": "walkthrough",
 "actions": ["start", "step", "resume", "stop", "finish"],
 "filter": {"author": "seat:<seat id>", "waiting_on": "agent"}}
```

The engine reads the filter after the transition commits. Thus the
entry fires only when the row now waits on the agent:

- The person takes `start`, and step 1 is an agent step.
- A person step ends, and the next step is an agent step. The engine
  takes `step` for this.
- The person takes `resume` on an agent step.

A step that opens for the person does not wake the seat. An `advance`
by the seat does not wake the seat.

A seat that must also hear each stop and the finish adds a second
entry:

```json
{"kind": "walkthrough",
 "actions": ["stop", "finish"],
 "filter": {"author": "seat:<seat id>"}}
```

A stop by the engine tells the seat that a step could not open or
that a step waited past its time. The sentence is in `stop_reason`.

## Step 3: the charter

Put these instructions in the charter.

```
You lead walkthroughs. The fire text can name one walkthrough.

When the fire text names a walkthrough, read that row. `current` is
the number of the open step. `outcomes` says how each earlier step
ended. Do the agent step that `current` names. If the step names an
`action`, take that action on the row at `self`, and the engine ends
the step. If the step names no `action`, do the work and then take
`advance` on the walkthrough. Then stop.

When the fire text names no row, read your queue: the walkthroughs
with author = you, state = running and waiting_on = agent. Do the
agent step of each row, as above. Then stop.

When a person step was skipped and the task cannot continue without
it, take `stop` with one sentence that says why.

Do not take a person's step. Do not take `resume` on a walkthrough
that the person stopped.
```

## The queue

The wake is damped when the seat has an open sitting or when the seat
fired less than `fire_interval_seconds` ago. The engine keeps a damped
wake, and the next fire names no row. The walkthrough stays in the
seat's queue until the seat ends the agent step:

```
waymark_query kind "walkthrough"
  filter {"author": "seat:<seat id>", "state": "running",
          "waiting_on": "agent"}
```

The session reads this queue itself. The seat does not name
`walkthrough` as its `walk`.

## What the tests prove

`waymark10.wakes-test` holds three cases for the entry of step 2:

- `a-seat-is-woken-when-its-walkthrough-waits-on-it`
- `a-person-step-opening-does-not-wake-the-author`
- `a-damped-wake-leaves-the-walkthrough-in-the-seats-queue`
