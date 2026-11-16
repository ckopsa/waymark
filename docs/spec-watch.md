# The watch

Ticket 14cbd2f4, child (a) of 874da2f1: the root page as held watches.
The kind is `waymark10.server.watches`.

## 1. The kind

A `watch` row says what one member wants shown.

| field | what it says |
|---|---|
| `for` | The member (or seat) id it is for. Left out at create, it is the creator. |
| `name` | What it is called where it is shown. |
| `cues` | One to twenty cues, each the shape of a seat's inbox cue — `kind`, `actions`, `filter`, `note` — and `states` and `note_hosted`. |
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
  `id` names one row, and the cue then holds that row alone while it
  passes the cue's `states` and the rest of the filter, on any kind;
  `<field>_set` with `true` or `false` — the collection grammar's
  presence spelling — asks whether the field holds a value (nil,
  missing and `""` do not), on any field the reader's grant admits,
  declared filterable or not; any other key is equality, and a nil
  field equals nothing, so its row is not held;
- a filter key on a field the reader's grant does not admit holds
  nothing, and so does an equality key on a field the grant shows
  only hashed: it is not compared against the hashed token either, so
  a guessed value is never confirmed; create and restate refuse a `_set` key whose value is not
  `true` or `false`, naming the key;
- a cue of kind `*` is every kind.

A row the grant does not admit is absent. The watches are read in
this order: the `required` ones first, then the member's other
watches, and the seeded one last; within each group by id. A row two
cues match is answered once, from the first watch in that order that
matches it and that watch's first matching cue: with that cue's note,
and that watch's `required`, so a row a required watch holds refuses
dismiss even when the seeded watch matches it too.

The order: watch order, then cue order, then the pinned quest head,
held calls, approval requests, then by `waiting_on.since`, oldest
first. The rows of the first watch in the order above come first, so
a required watch's rows come before any other's. Inside one watch,
every row its first cue holds comes before every row its second cue
holds, and so on, so a watch can be an ordered list of steps. Inside
one cue the quest head, held calls and approval requests and then
`waiting_on.since` (then kind, then id) break the tie.
`GET /api/-/watches/held` and `waymark_held` answer the same order.
Each item carries the row's `id`,
`kind`, `state` and `summary`, the cue's `note` rendered against the
row (its `{data.field}`, `{state}` and `{id}` holes, as
`seats.inbox/cue-note` fills them), `door` — the first door of
`waiting_on.next` a person or member takes — `since`, and `watch`, the
id and name of the watch it came from.

The note is filled when the held read answers, never when it is
written: the stored watch keeps the text as written, placeholders
included, and the watch's own page and its `restate` show that text.

- `{engine_url}` is this engine's public MCP URL: the MCP door's own
  address as its protected-resource document names it, the `:oidc`
  config's `:app-url` followed by `/api/-/mcp`. An engine with no
  `:app-url` leaves the hole out: the spaces it leaves close up, a
  space before punctuation drops, and a colon left with nothing after
  it drops, so `Connect your Claude: {engine_url}` reads `Connect
  your Claude`. Write it where the sentence stands without it.
- `{place_command}` is the one line that fetches a seat's place
  (docs/spec-first-run.md R-5.6), for the seat row the cue holds and
  the engine's solo owner: `curl -fsS -H 'X-Waymark-Principal:
  <owner>' -H 'X-Waymark-Actor-Type: human' <place-origin>/api/seats/<id>/-/place | tar x`,
  the same command the mayor's setup card composes, with the place
  origin the running engine knows. On a row that is not a seat, and on
  an engine with no solo owner, the value is unknown and the hole is
  left out as `{engine_url}`'s is.
- A cue's optional `note_hosted` is answered in place of `note` on an
  engine with `:oidc` sign-in configured; on any other engine, and on
  a cue with no `note_hosted`, `note` is. `{engine_url}` and
  `{place_command}` work in both.
- No other placeholder is filled: any other `{...}` text is left as
  written.

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
in-flight `holder=me` read does, and the dark connections (kind
`connection` in state `dark`), whose note names the connection. It is a
row: its member may restate it or drop it, and a dropped one is not
seeded again. A seat's sitter is not seeded. A watch seeded before the
dark connection cue gains it at boot, once, only while its cues are
still exactly the ones it was seeded with; a restated one is left
alone.

## 6. The root page

The root (`GET /`, hash empty) is the navigation bar and one list: the
held read, which discover advertises as the door `held`. One card per
row, in the read's order, whatever watch it came from: the row's
summary as a link, the cue's note, ONE button for the row's `door`
(opened through `openDoor`), and "Not now", the watch's `dismiss` door
with the row's id and kind, absent when the watch is `required`. A card
names its watch in small text only when it is not the seeded one. The
pinned quest's head leads, with its Go. At the foot, when
`dismissed.count` is above zero, "Dismissed (n)" opens the dismissed
rows, each with "Bring back" (`undismiss`). Empty, it says "Nothing
waits on you." with a link to Everything. The kinds grid, the attention
strip, Seasons and the chip rows live on the Everything tab
(`#everything`).

## 7. The two setup watches

A fresh factory engine's seed (docs/spec-first-run.md § 5) gives its
owner two held watches beside the default one:

- "Setting up", `required`: the owner's `member` row, then the seeded
  pull `runner_link`. None of its rows may be dismissed, and setup is
  done when it holds nothing.
- "Next steps", not required: the `github` secret, then the `house`
  org_plan. Either may be set aside with "Not now".

Each cue names its row by `id`, holds it in its live state, and asks
with `<field>_set` false that the field its step writes is still empty
(`puller_seen_at`, `value_set_at`, `brief`), so a card leaves the held
read when its step is done. The member cue has no such filter yet:
ticket 2c87c3c6 says how "Connect your Claude" clears. Both are
ordinary watches the owner may restate or drop, and an engine seeded
before them is not given them.

## Out of scope

Stream delivery.
