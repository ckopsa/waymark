# Spec — scenes

**Thesis.** A demo is written as data before it is filmed. A `scene` is
the row that holds it: who is in it, what is set up, and the shots in
order, each with what the screen and the rows must show after it. A
`take` is the record of one performance. The engine judges a scene at
one door, `check`, and runs nothing.

This document covers the two kinds alone
(`waymark10/src/waymark10/server/scenes.clj`, tests in
`waymark10/test/waymark10/scenes_test.clj`). The runner that performs a
ready scene and writes its takes is not part of them.

## The scene

`scene`, plural `scenes`, core's, `:nav :secondary`. States: `draft`
(initial), `ready`, `retired`. Filterable: `state`, `seed`, `author`.
The collection opens on `draft` and `ready`.

The author writes:

| Field | Form | What it is |
|---|---|---|
| `title` | 1 to 120 characters | one line that names the demo |
| `seed` | 1 to 80 characters | the demo seed the runner loads before the first call |
| `devices` | one or two of `phone`, `desktop` | each device it is performed on; one take per device |
| `cast` | 1 to 12 ids | cast ids from the seed; the first is the person filmed |
| `given` | optional, at most 40 calls | setup calls made before filming, in order |
| `shots` | 1 to 200 shots | the filmed moments, in order |

The engine stamps `author` (the creating principal) and `version` (1)
at birth. `given` and `shots` are lists of maps the schema does not
look into, so a draft may be saved half written.

| Door | From → to | What it does |
|---|---|---|
| `check` | `draft` → `ready` | judges every call and shot; nothing is run |
| `revise` | `draft`, `ready` → `draft` | takes any of the six fields; a field not named is kept; `version` goes up by one |
| `retire` | `draft`, `ready` → `retired` | the scene is no longer performed; its takes stay |
| `restore` | `retired` → `draft` | brings it back; it must be checked again |

## A call

A `given` entry and a `meanwhile` shot are both a call:
`{as, tool, arguments, bind?}`. `as` is a cast id, `tool` is a connector
tool's name, and `arguments` are that tool's own. `bind` names the
result for later `$refs`.

A `waymark_invoke` or `waymark_pursue` call is judged as the door it
names: `arguments.kind` is a kind this engine serves, `arguments.action`
is a door of it, and every key of `arguments.input` is a field that door
takes. A call with no `id` whose action the kind does not declare is the
kind's create, and its input is judged against the create form. A call
to any other tool is judged only where it names a `kind`.

## A shot

A shot is a map with exactly one verb. Beside the verb it may hold
`say` (the caption), `expect`, `bind`, `role`, `speed` and `trim`. Any
other key is refused as an unknown verb.

`role` is the shot's part in the story: `friction`, `turn` or `payoff`
(`film-rules/roles`). A shot need not have one. The film rule `arc`
reads the roles of a take's shots, so the scene is where they are
written.

`speed` is how fast the shot's frames play in the film: a number from
0.25 to 8, where 2 is twice as fast. `trim: true` cuts the shot's frames
from the film. A trimmed shot is still performed, checked and kept in
the take, and it holds no `say`, because its caption would never show.
The engine only checks the two keys; the filmer reads them off the
scene's shots.

| Verb | Keys | What `check` judges |
|---|---|---|
| `open` | `self` or `collection` | `self` is a bound `$ref` or `/api/<plural>/<id>`; `collection` is a kind this engine serves |
| `tap` | `surface` | the target (below) |
| `press` | `surface` | the target; judged as `tap` is |
| `type` | `field`, `value` | `field` is `dialog.field:<name>`; where the door last tapped is known, the first dotted part of `<name>` is a field it takes |
| `hold` | `ms`, `on?` | `ms` is a whole number above zero; `on`, when given, is a target |
| `wait_for` | `expect`, `within_ms` | `within_ms` is a whole number above zero; `expect` as below |
| `meanwhile` | a call | as a call: another cast member acts while the camera stays |

The walk carries three things from one shot to the next: the names
bound so far and the kind each holds, the kind of the row last opened,
and the door last tapped. `open` sets the row and clears the door. A
tap or press on `door:<action>` sets the door. A tap or press on
`tracker.go` clears it, because the tracker opens whatever door heads
the plan. Where a kind is not known, that kind's own law is not judged.

## Targets and `$refs`

A target is `surface` or `surface@row`. The surface is a name from the
page's registry (`GET /api/-/ui/surfaces`, `ui-routes/surfaces`): a
whole name such as `dialog.submit`, or a stem completed, such as
`door:complete`, `door-shut:complete`, `nav.ticket`,
`dialog.field:title` or `sheet.step:1`. The row is a `$ref` or
`/api/<plural>/<id>`. With no `@row`, the row is the one last opened.

- `nav.<kind>`: the kind is one this engine serves.
- `door:<action>` and `door-shut:<action>`: where the row's kind is
  known, the action is a door of it.

`$name` reads a name bound earlier: by a call's `bind`, or by a shot's
`bind`. A name bound by a call that names a `kind`, or by an `open`,
holds that kind. A `$ref` may stand in a call's `arguments`, in `self`,
after `@`, and in a `value`. One that no earlier call or shot bound is
refused.

## `expect`

`expect` is a map of `envelope` and `screen`, and nothing else. Each is
a list of expectations, `{path, op, value}`:

- an `envelope` expectation also names `self`, the row it reads;
- a `screen` expectation also names `surface`, a target.

The ops are `=`, `!=`, `contains`, `>=`, `<=`, `exists` and `absent`.
`path` must be named. `check` does not read into it: the runner does. A
shot's `expect` is judged after its own `bind`, so it may name what the
shot bound.

## What `check` refuses

The guard is `every-shot-can-be-performed`. It reads `given` first and
then `shots`, and refuses with the first that cannot be performed, as
`given N: <why>` or `shot N: <why>`, counted from 1. The scene stays a
draft. The sentences:

| Where | Why |
|---|---|
| a call | a call is a map of `as`, `tool` and `arguments`. |
| a call | `as` names `<id>`, which is not in the cast. |
| a call | a call names its `tool`. |
| a call | `<kind>` is not a kind this engine serves. |
| a call | a `<tool>` call names its `kind`. |
| a call | `<action>` is not a door of `<kind>`. |
| a call, `type` | `<field>` is not a field `<action>` takes. |
| anywhere | `$<name>` is never bound. |
| a shot | a shot is a map with one verb. |
| a shot | unknown verb `<key>`; the verbs are open, tap, press, type, hold, wait_for, meanwhile. |
| a shot | a shot has one verb, and this one has `<n>`. |
| a shot | `<role>` is not a role; the roles are friction, turn, payoff. |
| a shot | `speed` is a number from 0.25 to 8, and this one is `<speed>`. |
| a shot | `trim` is true or false, and this one is `<trim>`. |
| a shot | a shot with `trim` is cut from the film, so it has no `say`. |
| `open` | `open` names `self` or `collection`. |
| `open`, a target | `<address>` names no kind this engine serves. |
| a target | `<name>` is not a surface in the registry (GET /api/-/ui/surfaces). |
| a target | `<action>` is not a door of `<kind>`. |
| `type` | `type` names its field as `dialog.field:<name>`. |
| `hold` | `hold` names `ms`, a whole number above zero. |
| `wait_for` | `wait_for` names `within_ms`, a whole number above zero. |
| `expect` | `expect` is a map of `envelope` and `screen`. |
| `expect` | `expect` holds `envelope` and `screen`, and not `<key>`. |
| `expect` | an expectation is a map of `path`, `op` and `value`. |
| `expect` | `<op>` is not an op; the ops are = != contains >= <= exists absent. |
| `expect` | an expectation names its `path`. |
| `expect` | an envelope expectation names its `self`. |

## The take

`take`, plural `takes`, `:nav :secondary`. One state, `recorded`, which
is terminal: a take is written once, by the runner, and never moves.
Filterable: `scene`, `device`. It links to its scene.

The runner writes `scene`, `scene_version`, `device` (`phone` or
`desktop`), `commit` (the commit of the house that was filmed), `film`
(optional, the id of the film it made) and `shots`. Each shot result is
`{shot, ok, pressed?, expects?, why?, still?}`: the shot's place counted
from 1, whether it passed, the surface the runner acted on, one entry
per expectation with what was read, the runner's sentence for why a
failed shot failed (at most 500 characters), and where the still is
kept. A take that stopped holds the failing shot last.

The engine stamps `first_failing_shot` (the first result whose `ok` is
false) and `ok` (true when there is none). The create guard
`the-take-is-of-a-version-the-scene-had` refuses a take whose scene
the caller cannot see, or whose `scene_version` is not from 1 up to the
version the scene holds now.

## A worked scene: the Quests demo

The demo of journal 0393143a, in twelve shots. An epic is open and its
film is not linked, so Complete is shut and drawn dotted. The person
taps it, reads the sheet, accepts the quest, and follows the tracker to
the film-link step and back to an open Complete.

```json
{"title": "Quests, from a dotted Complete",
 "seed": "factory",
 "devices": ["phone", "desktop"],
 "cast": ["colton", "mayor"],
 "given": [
  {"as": "mayor", "tool": "waymark_invoke", "bind": "epic",
   "arguments": {"kind": "ticket", "action": "create",
                 "input": {"title": "[epic] A demo is written as a scene",
                           "type": "feature",
                           "repo": "ckopsa/waymark",
                           "showcase": {"format": "film",
                                        "scene": "The mayor sends a film it has never watched. Then it writes the scene, and the take says which shot failed."}}}},
  {"as": "colton", "tool": "waymark_invoke",
   "arguments": {"kind": "ticket", "id": "$epic", "action": "groom"}}],
 "shots": [
  {"open": {"collection": "tickets"}, "say": "Every ticket of the house."},
  {"open": {"self": "$epic"}, "say": "The epic. Its film is not linked yet."},
  {"tap": {"surface": "door-shut:complete@$epic"},
   "role": "friction",
   "say": "Complete is dotted: not yet. A tap asks why.",
   "expect": {"screen": [
     {"surface": "sheet", "path": "goal.action", "op": "=", "value": "complete"},
     {"surface": "sheet.step:1", "path": "text", "op": "exists"},
     {"surface": "refusal", "path": "text", "op": "contains", "value": "film_url"}]}},
  {"hold": {"ms": 1500, "on": "sheet"}},
  {"press": {"surface": "sheet.accept"},
   "role": "turn",
   "say": "Accept keeps the goal.",
   "expect": {"screen": [
     {"surface": "sheet", "path": "text", "op": "absent"},
     {"surface": "tracker.next", "path": "text", "op": "exists"}]}},
  {"tap": {"surface": "tracker.go"}, "say": "Go takes the step at the head of the plan."},
  {"wait_for": {"within_ms": 5000,
                "expect": {"envelope": [
                  {"self": "$epic", "path": "state", "op": "=", "value": "draft"}]}}},
  {"tap": {"surface": "tracker.go"},
   "say": "The film-link step.",
   "expect": {"screen": [
     {"surface": "dialog", "path": "action", "op": "=", "value": "restate"}]}},
  {"type": {"field": "dialog.field:showcase.evidence.film_url",
            "value": "https://work.kopsa.info/films/quests-demo"}},
  {"press": {"surface": "dialog.submit"},
   "expect": {"envelope": [
     {"self": "$epic", "path": "data.showcase.evidence.film_url",
      "op": "=", "value": "https://work.kopsa.info/films/quests-demo"}]}},
  {"tap": {"surface": "tracker.go"}, "say": "Back into the queue."},
  {"role": "payoff",
   "wait_for": {"within_ms": 5000,
                "expect": {"envelope": [
                  {"self": "$epic", "path": "state", "op": "=", "value": "open"}],
                 "screen": [
                  {"surface": "door:complete@$epic", "path": "text", "op": "exists"}]}}}]}
```

`factory10/test/factory10/scene_doc_test.clj` reads this block out of
this file, creates it as a scene beside the `ticket` kind, and asserts
that `check` answers `ready`. It also asserts that this document names
every verb and every op the kind declares.

What `check` judged here: `ticket` is served; `groom` and `complete`
are doors of it; `title`, `type`, `repo` and `showcase` are fields its
create takes; every surface is in the registry; `$epic` is bound before
it is read; each `role` is one of the three. What it did not judge: the `type` shot, because `tracker.go`
left no door known; every `path`; and the plan itself. That the plan
runs ungroom, restate, groom is this scene's expectation of the quest
planner, and only a take can say whether it holds.
