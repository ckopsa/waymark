# Routine: demo-walker

The seat `demo-walker` makes one demo walk, alone. A ticket in the
working engine asks for the walk. The seat brings up a seeded clone,
records a self walk in it as the seed's agent, seals the walk, has the
clone film it, and files the video back on the ticket. Then the clone
goes down. Nobody signs in and nobody watches
(docs/spec-agent-demo-walks.md, section 6).

This file holds what is different from the CI classifier. The chair
key, the fire link, the seat's place, the environment and the Stop
hook are the same, and docs/routines/ci-classifier.md says them once.

Written in ASD-STE100 Simplified Technical English.

## What must be there first

The seat works through the clone door, which is in
ckopsa/waymark-doors. This repository could not read that door. Each
**Check** below names a thing to read on the door before the first
firing.

- clone-mcp is deployed, and its `up` takes `seed` and answers the
  seed's cast (child 5).
- The door has `call(clone, as, tool, arguments)` (child 6a). It makes
  one connector tool call inside the wall, as the cast member `as`.
- The door has `render(clone, walk)` (child 8c). It answers the
  artifact: each file's name, size, sha256 and link.
- **Check** the four tool names as `waymark_powers` lists them, and
  the power token of each. This file writes them `clone__up`,
  `clone__call`, `clone__render` and `clone__down`.

The engine's side is merged: the connector stages its calls, a call
takes a `caption`, `waymark_get` answers a sealed walk's export, a walk
made with `docs: true` carries its screens, and a seeded engine serves
`clock_shift`. `waymark10.demo-walk-test` makes steps 2 to 4 of the
sitting below over the in-memory twin with the demo seed.

## The seat

Create the seat with these values.

| field | value | why |
|---|---|---|
| name | `demo-walker` | one spelling |
| mode | `fired` | a person fires it for one ticket |
| walk | `ticket` | the row that asks for the walk |
| rows_per_firing | 1 | one walk is one sitting |
| held_for | the row id of the model the Routine runs (`waymark_query` kind `model`) | the agent chooses what to show, so the seat needs a model that can plan |
| sitting_budget_tokens | 400000 | one walk is about forty calls, and each call through the door carries an envelope |
| budget_usd_per_week | 10 | the fuel |
| charter | the text under "The charter" | the residual |
| scope | the entries under "The scope" | the authority |

The seat has no cadence of its own. A person fires it with the ticket's
id in the fire payload, and the seat walks that row and stops.

## The scope

The grant holds the clone door's powers, and nothing of the working
engine but the ticket that asked for the walk.

```json
[
  {"kind": "ticket", "actions": []},
  {"kind": "clone.up"},
  {"kind": "clone.call"},
  {"kind": "clone.render"},
  {"kind": "clone.down"}
]
```

The `ticket` entry lets the seat read the ticket. The seat also files
one comment on that ticket. **Check** the door that takes a comment on
a ticket (docs/spec-threads.md), and add that one door to the scope.
The scope names no other door of `ticket`: the seat does not groom,
complete or drop the ticket.

The scope names no kind of the clone. Inside the clone the seat is not
itself. Each call is the cast member's call, and the clone's law
judges it as that member.

## The charter

At most 1200 characters.

```
You make one demo walk for one ticket. The ticket says what the walk
must show.

You work in a clone that holds only invented rows. You act there as
the seed's agent, and never as yourself.

Show the product as a person uses it. Read a row before you change it.
Say one short line about each step, in plain words, for a viewer who
does not know the product. Say what happens and why it helps. Do not
name a tool, an id or a field.

Keep the walk short: ten to twenty steps. A step that the law refuses
is not a failure. Read the refusal, do what it says, and go on.

Never film a walk that fails a check. Bring the clone down, and say on
the ticket which check failed.

Always bring the clone down before you stop.
```

## The instructions

This seat uses the "One Routine for each model" way of
docs/routines/ci-classifier.md. Do those steps one time for a model,
and then invoke `restate` on the seat `demo-walker` with the field
`instructions`, which holds at most 2000 characters. Write this text in
it:

```
You sit in the seat `demo-walker`. The sit answers the charter and one
ticket. Read the ticket with waymark_get.

All your work goes through waymark_power.

1. clone__up {commit, seed: "demo", ttl}. Two hours is enough. It
   answers the clone and the cast. Find the cast member of type agent.
2. clone__call {clone, as: that member, tool, arguments} makes one
   connector call in the clone. First create a walk: waymark_invoke,
   kind "walk", action "create", input {followed: that member's id,
   title, docs: true}.
3. Do what the ticket says, with waymark_query, waymark_get and
   waymark_invoke. Give each call a `caption`. To move time, create a
   `clock_shift` with input {by} or {to}.
4. Invoke `seal` on the walk. Then waymark_get the walk with return
   "export". Check three things: header.engine starts with "demo-";
   each display in header.cast is a name the cast of step 1 holds;
   frames is more than zero.
5. clone__render {clone, walk}. It answers the files.
6. File one comment on the ticket: each file's name, size, sha256 and
   link.
7. clone__down {clone}.

When a check of step 4 fails, do not render. Do step 7, and say on the
ticket which check failed.

Do not call discover, schema, query or powers in the working engine.
When the seat says halted or parked, say why and stop.

When the Stop hook asks you to close the sitting, make that one call
with the numbers it gives, then stop.
```

Leave the seat's schedule with no link. A schedule with no link of its
own fires through the chair's link (R-12.36).

## One sitting, call by call

Steps 2 to 4 are connector calls in the clone. Each one is the
`arguments` of a `clone__call`, and `tool` names the connector tool.
The examples use the demo seed: the agent member is `plan`, and its
display name is "Juniper".

**The walk.** `docs: true` makes each move and each write carry the
screen it shows, so the film draws the product's own screens.

```json
{
  "kind": "walk",
  "action": "create",
  "input": {"followed": "plan", "title": "Juniper puts the chore list first", "docs": true}
}
```

**A step.** While the walk records, the connector stages each call: a
get moves the gaze to the row, a query shows its collection, and an
invoke opens the form, types each argument, writes, and closes. The
`caption` is written before those beats. `caption_field` puts the line
beside one argument of the action.

```json
{
  "kind": "ticket",
  "id": "<the ticket's id>",
  "action": "prioritize",
  "input": {"priority": 1},
  "caption": "The house wants it soon, so it goes first.",
  "caption_field": "priority"
}
```

**The clock.** A demo engine serves `clock_shift`. The shift is forward
only, and at most 14 days in total. After the create, the engine runs
its due scheduled actions one time, so a call that was scheduled for
the next morning runs now and the walk shows it.

```json
{
  "kind": "clock_shift",
  "action": "create",
  "input": {"by": "PT18H"},
  "caption": "The next morning."
}
```

**The seal and the export.** `seal` takes no input. Then read the file:

```json
{"kind": "walk", "id": "<the walk's id>", "return": "export"}
```

The answer holds `format`, `header`, `frames`, `bytes`, `sha256`,
`href`, the first `lines` of the file, and `truncated`. A walk that is
not sealed is refused, and the refusal names `walk.seal` as the remedy.

## The three checks

The seat makes these checks on the export before it renders.

| check | what the seat reads | why |
|---|---|---|
| the engine is a demo | `header.engine` starts with `demo-` | a video is made only from a walk recorded in a seeded clone |
| the cast is the seed's | each `display` in `header.cast` is a display name that `up` answered | a name that is not in the seed is a real name, or a seat's id. The agent's own entry reads "Juniper" |
| the frames are there | `frames` is more than zero | a walk with no frame is a film of nothing |

`render` runs only in a seeded clone, so the first check is kept by
where the renderer runs also. The seat makes the check all the same,
because a failed check costs one call and a failed film costs minutes.

`waymark10.demo-walk-test` holds the same checks:
`an-agent-alone-records-seals-and-exports-a-demo-walk`,
`the-demo-walks-cast-holds-only-seeded-names` and
`the-demo-walks-header-names-a-demo-engine`.

## When the sitting does not end well

- **A check fails.** The seat does not render. It brings the clone
  down and says on the ticket which check failed, in one sentence.
- **`render` fails.** The door keeps nothing of a failed film. The seat
  brings the clone down and says on the ticket what the door answered.
- **The seat dies.** The clone's reaper brings the clone down at its
  `ttl`. The walk is gone with the clone, and the next firing starts
  from step 1.

The first run of this seat is the experiment that
docs/spec-demo-clones.md child 5 gave to a person. It passes when the
video plays.
