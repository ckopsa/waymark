# The watch

Ticket 14cbd2f4, child (a) of 874da2f1: the root page as held watches.
The kind is `waymark10.server.watches`.

## 1. The kind

A `watch` row says what one member wants shown.

| field | what it says |
|---|---|
| `for` | The member (or seat) id it is for. Left out at create, it is the creator. |
| `name` | What it is called where it is shown. |
| `cues` | One to twenty cues, each the shape of a seat's inbox cue — `kind`, `actions`, `filter`, `note` — and `states`. |
| `mode` | `hold` (the default) or `stream`. |
| `required` | True and none of its rows may be dismissed. Default false. |

The engine writes `seeded` (true on the default watch) and `dismissed`.

The doors: `create`, `restate` (cues, name), `drop`, `dismiss` and
`undismiss` (each naming one row by `row_id` and `row_kind`). A member
creates and moves the watches whose `for` is themself; the mayor seat
does so for any member; the engine seeds them. `dismiss` and
`undismiss` refuse on a required watch.

No kind decides whether it is shown to a person; watches do. A kind
names no audience, and a row shows on a person's page because a watch
of theirs holds it.

## 2. The two modes

- `hold`: the watch is read when the person looks (§ 3).
- `stream`: each event the cues match is delivered as it happens. The
  schema takes the mode; nothing delivers it yet. A seat's inline
  `inbox` is a stream-mode watch in all but storage, and stays on the
  seat row.

## 3. The held read

`GET /api/-/watches/held`, and the MCP door `waymark_held`, answer for
the signed-in member every row a cue of their active held watches
matches NOW:

- a cue with `states` holds every row of its kind in one of them;
- a cue with none holds every row of its kind whose `waiting_on` names
  the member — the rows the in-flight `holder=me` read answers;
- a cue's `filter` narrows either, field by field (`state` included);
- a cue of kind `*` is every kind.

A row the grant does not admit is absent. A row two cues match is
answered once, from the first watch (the seeded one first) and the
first cue.

The order: the pinned quest head, held calls, approval requests, then
by `waiting_on.since`, oldest first. Each item carries the row's `id`,
`kind`, `state` and `summary`, the cue's `note` rendered against the
row (`seats.inbox/cue-note`: its `{data.field}`, `{state}` and `{id}`
holes), `door` — the first door of `waiting_on.next` a person or member
takes — `since`, and `watch`, the id and name of the watch it came
from.

```json
{"waymark": "held", "for": "<member id>",
 "watches": [{"id": "…", "name": "Waiting on you", "required": false}],
 "total": 2, "rows": [ … ],
 "dismissed": {"count": 1, "rows": [ … ]}, "at": "…"}
```

## 4. Dismiss

`dismiss` marks one row on one watch with the state the row is in. The
held read answers a marked row under `dismissed`, counted, and not
among `rows`. `undismiss` removes the mark. A mark lasts until the
row's state changes: then it is inert at once — the row shows again —
and the next write of the watch drops it.

## 5. The default watch

Every member gets one held watch, "Waiting on you", at boot (for the
members there are) and at their first sign-in (the watches consumer,
on the member's transition). Its cues: the pinned quest head, held
calls, approval requests and questions waiting on the member, then
every other kind waiting on them (`*`), so it answers the rows the
in-flight `holder=me` read does. It is a row: its member may restate
it or drop it, and a dropped one is not seeded again. A seat's sitter
is not seeded.

## Out of scope

Rendering (child b), setup cards (child c), stream delivery.
