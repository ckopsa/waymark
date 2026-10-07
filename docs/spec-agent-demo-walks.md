# Spec — agent-made demo walks

**Thesis.** A marketing walk needs no person in it. One agent, alone in
an engine that holds only invented rows, decides what to show, does it
through the connector it already uses, says one line about each step,
and seals the walk. A headless browser then plays the walk and films
it. The product is a video file. Nobody signs in and nobody watches.

This document is the design pass ticket 20d9202f asks for, and it files
no code. It amends `docs/spec-guided-follow.md` § 4 and
`docs/spec-demo-clones.md`; section 9 says exactly what changes in each.

## Epistemic status

A design, read against ckopsa/waymark as it stands on 2026-10-01. The
owner decided four things that day, and they are taken as given:

- The output is a **video file**.
- The agent **improvises** from its instructions. A declarative script
  is a later child, and only if runs drift (child 9).
- **Stage A runs on the working engine first** (children 1 to 4).
  Stage B moves it into a demo clone (children 5 to 8).
- demo-clones child 4, the browser route, is **dropped**.

One half was read and one half was not, as in spec-demo-clones. The
engine's side was read here and is cited. ckopsa/waymark-doors could
not be opened from this bench, so what is said about clone-mcp (`up`,
`down`, the reaper, the wall) is restated from the ticket and from
spec-demo-clones. Where a child needs something of the door that this
pass could not check, the child says "check" and names it.

One finding changes the list. The owner's slice 8 plays the walk in
replay and films it. Replay today does not draw the product's screens
(section 8). A video of it would show a stand-in panel. So slice 8 is
three children here, and the first of them is one the owner's list
does not have.

## What exists

- **A self walk, and an agent may make one.** `walk` and `walk_frame`
  (`server/walks.clj`). The create guard `a-named-recorder` refuses
  only the anonymous principal, so a seat's sitter may create a walk
  whose `followed` is itself. Frame types are `move`, `ui`,
  `transition` and `invitation`.
- **The beat is tapped.** `POST /api/-/presence {self, ui}`
  (`presence-report` in `server/routes/realtime.clj`) calls
  `presence/report!` with `walks/self-recorder`'s tap. The tap is shown
  a `move` when the gaze changed and the `ui` frame when the beat
  carried one. The gaze changed when the beat's `self` is not the
  `self` the principal's last beat reported. A per-row stream
  (`stream-open!`) and a read (`read!`) move the presence entry with no
  beat, and they are not counted: the beat that brings the entry back to
  the row the tab never left records no `move`. `report!` takes any
  named principal and needs no stream.
- **The write doors hand over transitions** (`walks/record-own!`), but
  only the HTTP create and action routes do. The connector and the
  bulk door do not yet. That is slice 1, ticket 0096e862.
- **The connector has eleven fixed tools** (`tools` in
  `server/mcp.clj`). None of them reports presence with a `ui` part,
  and none reads a walk's export: the export is the route
  `GET /api/walks/{id}/export` and not an action, because it answers
  newline-delimited JSON and not an envelope.
- **The invitation** (`server/invitations.clj`) is a row addressed to a
  subject, on one `field` of one `action`, with a `note`. It is
  recorded as an `invitation` frame, and replay opens the dialog,
  lights the field and shows the note (`openReplayInvitation` in
  `ui/200-events-follow.js`). It closes when a recorded transition
  matches its row and action.
- **Replay** (`ui/200-events-follow.js`). `REPLAY_MAX_GAP` cuts a long
  silence to 3000 ms. `renderReplay` draws every `/api/` screen from
  the recording alone: a panel that says "Replay", the row's summary
  and state, and its recorded transitions. A dialog is built from the
  frame's field names, each as a text area, because "the export
  carries no schema, so none is invented".
- **The clock** is `:now-fn` on the engine (`server/engine.clj`), and
  the scheduled-actions sweep reads it (`scheduled/sweep-due!`). The
  sweep loop runs every thirty seconds (`default-sweep-ms`).
- **The seed and the expiry are merged.** `server/seed.clj` loads the
  demo seed and `seed/admit!` refuses an engine that is not named
  `demo-…` or that has an IdP. `WAYMARK_ENGINE_EXPIRES_AT` is read at
  boot. These are demo-clones children 1 and 2.
- **clone-mcp** (not read) has `up(commit, snapshot, ttl)` and no
  `seed`. It is not deployed: bbc7d338 waits on the owner.
- **Attachments** (`server/attachments.clj`) take bytes only by HTTP
  PUT, up to 10 MiB. No connector tool carries bytes.

## The two stages

**Stage A, on the working engine.** An agent records a self walk of
real work, with the screen driven and captioned, and reads its file
back. A person plays it in the working engine's own replay. Such a walk
holds real rows. It is for onboarding inside that engine, as
spec-guided-follow § 4 already says, and **it is never filmed**.

**Stage B, in a demo clone.** The same agent does the same thing in a
seeded clone, where every row is invented. The clone films the walk and
hands the video out through the door. **A video is made only from a
walk recorded in a seeded clone.** That rule is kept by where the
renderer runs (section 8), and not by a check that could be forgotten.

## 2. The agent drives the screen through the connector

*Story: a viewer watches the agent go to the row, open the form and
type each value before it submits.*

**No new tool, and no argument that turns it on.** The connector stages the calls
the agent already makes. It does so while, and only while, the
session's principal has a self walk in `recording`.

The reasons:

- The tool list is the same for every caller and costs every caller
  context. A twelfth tool that only a demo agent uses is paid for by
  every seat.
- An improvising agent that must call a second tool to "show" each
  step will sometimes forget, and the walk then has holes. A walk made
  from the calls themselves cannot disagree with what was done.
- The opt-in that spec-guided-follow § 2 requires is still there.
  Creating the self walk is the agent's choice to share, as ● Record
  is the person's.

What each call becomes:

| call | beats, in order |
|---|---|
| `waymark_get` of a row | `move` to the row |
| `waymark_query` that returns rows | `move` to the collection, then `ui` with `collection {self, filter, sort, page}` |
| `waymark_invoke`, `dry_run` | `move` to the row; `ui` with `dialog {self, action}` and no fields; then one `ui` per argument, each adding one value. The dialog stays open. |
| `waymark_invoke` | the same beats, unless the last `ui` already shows this dialog with these values; then the write; then `ui` with `dialog: null` |
| a create (no `id`) | as an invoke, with the collection as `self` |
| a `quest` create, `dry_run` | `move` to the goal's row, then `ui` with `quest {goal, label, seen {ok, body}}`: the quest's sheet. No dialog and no typing. |
| a `quest` create, its goal's sheet open | the write, then `ui` with `quest: null` |
| any other staged call, a sheet open | first `ui` with `quest: null`, then its own beats |
| a bulk invoke | `move` to the collection, then the writes |

`waymark_discover`, `waymark_schema`, `waymark_resolve` and
`waymark_history` make no beat. They are how the agent finds its way,
and a viewer does not need to see them. A query with `rows: "none"`
makes none either. `waymark_pursue` is ordinary invokes, so each step
is staged as one. A call with `at` stages its dialog, and the
`scheduled_action` it creates is the write.

A quest's preview is staged the way the page makes it. A person taps
the goal's shut door and sees the sheet, not the quests create form
(docs/spec-quests.md, "A tap previews, Accept creates"). So a rehearsed
`quest` create whose answer carries `preview`, or a refusal, writes one
`ui` beat on the goal's row with the `quest` part the page's sheet
reports: the goal, the goal door's label on its row, and the `preview`
or the refusal's title and detail. It passes `presence/clean-quest` and
the gates of every staged beat. The next `quest` create for that goal
is Accept: the write, then a `ui` beat with no `quest` part. Any other
staged call closes the sheet first with such a beat, which a replay
shows as Not now.

Arguments are typed in the order of the action's input schema. A call
with more than twelve arguments types the first eleven one by one and
the rest in the last beat. A refused call makes no closing beat: the
form stays open with what was typed, as it would on a person's screen.

A refused write is recorded as a `refusal` frame, for a person's own
write and for a staged call alike. The action route writes it
(`walks/record-refused!`) to every self walk the caller is recording,
under the request's own visibility. The create route writes it too,
with the collection as `self` and the create verb as `action`, so the
recorder must see the whole kind or be allowed that create verb, as for
the create form's own `ui` beats. A recorder whose scope names only
some rows of a kind, and who may create there, therefore keeps their
own create form and its refusal; the export gives those lines back to
that recorder alone, and any other exporter must see the whole kind.
Permission to create is not sight of the list: the `move` to that
collection, a caption on it and its screen still need the whole kind.
Such a walk therefore has a create form's `ui` beat with no `move` before
it and no `doc` behind it. Replay opens that form from the beat's own
`self`: it goes to the collection the beat names, draws the recording's
own panel for it, and draws the form and its refusal there. A story of
`waymark10/scripts/ui-drive.mjs guided` replays such a walk and checks
the form, what was typed and the refusal.
A bulk write records one frame for each row it refused, about that row
and with that row's own problem, in an atomic call as in a partial
one; a bulk call refused whole, before any row was tried, records
none. Its body is `{principal, self,
action, title, detail, remedies, errors}`: what the refusal's box and
the form's fields show, and no more of the problem. `errors` holds a
schema refusal's sentences by argument, and a secret argument's entry
is left out, as `clean-ui` leaves out its value. A rehearsal's refusal
and the acknowledge wall record none. The export carries the frame when
the exporter sees its `self`, with the remedies whose doors the exporter
may invoke and the errors whose arguments the exporter may read. Replay
draws it in the open form with the code that draws a person's own
refusal, each field's message under its field, and with "Accept as
quest" under it when the recording keeps
the refusal as a goal. A walk recorded before this frame existed has
that button drawn in the form's footer instead.

A bulk write opens no form, so its refusals have none to be drawn in.
Replay draws a refusal in the caption band when the recording had no
form open for its door: the same hand's last `ui` beat before the frame
shows no dialog, or another door's, or a `move` came after that beat
(`replayFormless`). The band shows one line for each refused row,
"Refused: ", the row's summary as the recording has it or its address,
and the problem's detail. The lines are held, in the notice's italic,
until the recorder's next `move` or `ui` beat, and each is given a
caption's reading time. The export is not changed. A story of
`waymark10/scripts/ui-drive.mjs guided` replays such a walk in a browser
and checks the lines and their going.

**How a sitter with no browser reports presence.** The connector calls
`presence/report!` in process, with the session's principal, the `self`
and the `ui` part, and `walks/self-recorder`'s tap built from the
session's visibility. It is the call `presence-report` makes for a
browser. Nothing is posted over HTTP and no stream is opened. The
sitter sends no heartbeat between calls, so the registry evicts it
after the TTL. That is correct: a live follower sees it leave, and the
walk already holds its frames, because the tap runs at the beat.

Everything section 1 of spec-guided-follow says still binds, because
the beats go through the same door: `clean-ui` removes secret
arguments, the 6000-byte cap elides the longest values, and a curtained
principal's beat publishes nothing and so records nothing. A demo
agent must not have drawn the curtain; the routine says so (child 6).

**The beats of one call arrive within milliseconds.** The recording
keeps their true times. Replay paces them: two frames less than 50 ms
apart are played `REPLAY_BURST_GAP` apart, which is the floor of the
next paragraph (1000 ms). No browser makes
such a burst, since the form's own reports are debounced at 150 ms. A
`move` and a `ui` from one browser beat share a time and are now played
the floor apart, which is harmless. The staging does not sleep: holding a
tool call to make a pause would bill the agent for it.

**The pointer leaves a screen only after it has been still.** An
agent's recorded gaps are milliseconds, so replay's floors set the
pace, and each floor is stillness: the time from the last change of the
screen to the start of the gesture toward the next act (the next
paragraph). There is one floor: after any change of the screen, the
next change waits at least `REPLAY_MIN_STILL` (1000 ms). A change is a
move, a hop's list, a walk's row, a dialog opening or closing, a typed
value, a caption, and a `doc` frame that draws the row or the list on
screen again with other content. A landed transition is still for
longer, `REPLAY_WRITE_HOLD` (1500 ms), and a caption's reading time
and the long-silence cut stay as they are. The stillness is counted
from the moment the change is drawn (`replayDrawn`, `replayStillLeft`)
and not from the frame that asked for it, so a list drawn late is
still for its whole floor. The guarantee is for 1×, which the film
plays; 2× and 4× divide the floor as they divide every wait. The gesture's
900 ms come after the stillness and not inside it, so a frame waits the
larger of its recorded gap and stillness + glide + press
(`replaySchedule`, `replayLinger`), and speed divides all of it. The
arrival outline lasts `REPLAY_GAZE_MS`, which is `REPLAY_MIN_STILL`:
it is off when the pointer leaves. A frame that changes nothing on
screen waits nothing (`replayShows`): a `doc` for an address that is
not on screen, one equal to the last recorded for its screen, and one
in the burst of the frame before it.

**Replay makes each beat with a pointer.** A small arrow is drawn over
the page (`replayPointerTo`). Before a frame is applied, the pointer
glides for `REPLAY_GLIDE_MS` (600 ms) to the element a person would
press to cause that frame (`replayGestureTarget`), and the press takes
`REPLAY_PRESS_MS` (300 ms). The gesture is made after the
screen's stillness, and the frame waits for both. The pointer is the
only way the screen changes:

- **A move** presses the link to that row on the page: a collection
  row, a ref link or a breadcrumb. With no such link it presses the
  navigation entry of the row, or of the row's kind. There is no jump
  to a row that is not on screen: the navigation entry draws the kind's
  list first, as a screen of its own, and the row's link is pressed in
  that list (`replayHop`). One hop is made for a frame. When the list
  does not show the row either, the move is applied with the arrival
  outline alone.
- **A move to the row a hand's open form stands on** moves no screen
  and is left out (`replayFormMoves`). The form stays open until the
  `ui` beat that closes it. The engine no longer records such a frame.
  A walk recorded before ticket 5c1acbcb may hold one, from a beat that
  brought presence back after a stream or a read had moved it.
- **A dialog beat, a write of the recorder's or an invitation on a row
  that is not on screen** goes to that row first, the same way
  (`replayWalkOf`), and the frame is applied on the row it leads to.
- **A beat that opens a form** presses the action button of that door
  on that row.
- **A typing beat** clicks its field. The field is the one the beat's
  `focus` names, or the first whose value differs from the beat before
  it. The clicked field is not lit: it alone wears a focus ring
  (`[data-replay-click]` in `ui/030-screens.css`), because a replayed
  form's fields are disabled and take no focus. That ring is the
  browser's own and not the outline the app's `:focus-visible` rule
  draws; ticket d4040832 is to make them the same. The lit field of a
  typing beat is now live follow's alone.
- **A ref field** names its row as a live form does. A staged typing
  beat on a `:kind` ref argument carries `labels: {<field>: <the row's
  summary line>}`, read under the recorder's own grant: a list of refs
  has one label for each id, and a row the recorder may not see has
  none. An export keeps a label only for a row its reader may see.
  Replay fetches no collection, so it seats the picker with that one
  row: the beat's own label, else the summary of the walk's last `doc`
  for the row, else the id.
- **The submit** is pressed for the beat that closes the form after its
  write. The button that writes is drawn unlit until the pointer
  presses it. A form that closed with no write was cancelled, and
  nothing is pressed.
- **A collection beat** presses the filter control of the list shown,
  or the link or the navigation entry of another list.

A pressed link or button wears the invitation's lit style for the
press, until its frame is applied. A beat equal to the one before it is
on screen already and gets no click.

**A step with no click behind it is a notice** (`replayNotice`). The
recorder did not press anything for a transition by another principal
(a scheduled action firing, a seat, another person), for a
`clock_shift`, or for an invitation the recorder did not write. Such a
frame makes no gesture and moves no pointer. The caption band shows one
line for it, in italics, in the caption's place, until the next frame
is applied: "Scheduled: …" or the actor's display name, "Later: …", or
"Invited: …" with the invitation's note. Only an invitation's row is
walked to before it.

## 3. Captions anywhere

*Story: one line per step, even with no form open.*

**A `caption` frame, the fifth `walk_frame` type. Not an invitation.**
An invitation is a hand-off. It is a row with a subject, it is audited,
it must name an action and a field, and it waits for a person. A
caption hands nothing to anyone, has nobody to wait for, and must work
on a collection screen where there is no action at all. Bending the
invitation to that would put rows in the log that mean nothing.

**The door is an argument, `caption`, on the staged tools**:
`waymark_get`, `waymark_query` and `waymark_invoke`. It is one line of
at most 140 characters. The frame is written before the call's beats,
so the viewer reads the line and then sees the act. With no recording
self walk the argument is accepted and does nothing, so an agent's
instructions need not branch.

**A client may hold an old tool list.** The engine's `tools/list`
carries `caption` on the three tools and `caption_field` on
`waymark_invoke`. A Claude Code session read on 2026-10-01 had neither,
before and after its connector was reconnected: the client keeps the
tool list it read first, the tools are `additionalProperties: false`,
and so it could not send the arguments. For that reason `caption` and
`caption_field` also ride inside an invoke's `input`. The connector
moves them out before the form is typed and before the door validates
its input, so they do not reach the door. The argument beside `input`
wins when a call carries both. A key the door's own input declares is
the door's argument and is not moved. A get and a query have no `input`,
so a session with an old list captions its invokes only.

**The anchor is the step's own `self`.** A query anchors the caption to
the screen, a get to the row, an invoke to the form. One more argument,
`caption_field`, names one argument of the invoked action; the caption
is then drawn beside that field, with the field lit, by the code that
draws an invitation's note. It is refused when the action has no such
argument or the argument is secret, by the invitation's own rule. In
replay that is the one lit field of a form: a typing beat's field is
clicked by the pointer and wears a focus ring (section 2).

```json
{"t": 5210, "type": "caption", "who": "a1",
 "self": "/api/tickets/0abb…", "action": "groom", "field": "priority",
 "text": "The agent sets the priority before it grooms the ticket."}
```

**How long it stays.** Until the next caption replaces it, or a call
carries `caption: ""`, which clears it. Replay also holds the frame
after a caption for reading time: 55 ms for each character, at least
1500 ms and at most 6000 ms, before `REPLAY_MAX_GAP` is applied.

**Sight.** The text is the agent's own words. It is recorded if the
recorder can see `self`, and it crosses an export if the exporter can.
`never-recorded` keys cannot occur in it, since it is a string. Nothing
stops an agent from writing a real name into a caption on a working
engine, as nothing stops it in a comment. That is one more reason
Stage A walks are not filmed.

The invitation stays what it is. An agent that wants to show the
hand-off itself creates one, addressed to a cast member, and the walk
records it as today.

## 4. The agent gets its file

*Story: no one presses Export.*

**`waymark_get` on a sealed walk takes `return: "export"`.** The answer
is not the file. It is what an agent needs to know about the file:

```json
{"format": "waymark-walk/1", "header": {…}, "frames": 212,
 "bytes": 148230, "sha256": "…", "href": "/api/walks/…/export",
 "lines": ["…"], "truncated": true}
```

`lines` holds the export's first lines, up to 64 KB, and `truncated`
says whether that was all of it. A walk that is not sealed is refused
with `seal` as the remedy. The export is made by `walks/export` under
the session's visibility, so it is the same bytes the route answers.

The reason for the cap: a megabyte of frames in a model's context is
cost and no use. The agent reads the answer to check its walk: the
count, the cast, the first captions. Whoever needs the whole file reads
`href` over HTTP. That is a person's browser in Stage A and the
renderer in Stage B (section 8). The connector never carries the whole
of a large file, and this spec adds no paging to pretend it could.

**Size limits on the walk itself.** Today a walk nobody seals grows
without bound and `export` reads every frame into memory. With an agent
recording, that is a real risk. A walk takes at most 20 000 frames.
At that count the engine seals it with its own hand
(`the-recorder-or-the-sweep` already admits the system actor), and the
walk's history says why.

## 5. A demo engine from the seed

*Story: `up(commit, seed: "demo")` answers an engine that holds the
seed and nothing else.*

This is spec-demo-clones child 3, unchanged, in ckopsa/waymark-doors.
Two things are added to what `up` sets and answers:

- `up` answers the seed's **cast** (id, display name, type) beside the
  clone's id. The seat needs it to choose whom it acts as (child 6a).
  It answers no URL: there is no route (section 9).
- Nothing new in the environment. The demo clock of section 7 takes
  its switch from `WAYMARK10_SEED`, which child 3 already sets.

## 6. A seat works inside it end to end

*Story (aea8ec93): up, walk, export, film, file back, and the clone
dies. Nobody is present.*

**6a. The door forwards a connector call as a cast member**
(waymark-doors). A bug clone is reachable only through the door, so
clone-mcp already forwards something. **Check** what. What this child
needs is `call(clone, as, tool, arguments)`: one connector tool call,
made inside the wall against the clone's own connector, with the dev
principal header set to the cast member `as`. `as` is admitted only on
a seeded clone and only when it names a member of the cast `up`
answered. On a bug clone it is refused: a clone with production rows
takes no chosen identity. The seat reaches it as it reaches any power,
through `waymark_power`.

The agent acts as the seed's agent member, so the export's cast reads
"Planner" and never a seat's id.

**6b. The seat and its routine** (waymark). A seat, `demo-walker`,
whose grant holds the clone door's powers and nothing of the working
engine but the ticket that asked for the walk. Its routine is
`docs/routines/demo-walker.md`. One sitting:

1. `up(commit, seed: "demo", ttl)`. Two hours is enough.
2. In the clone, as the cast's agent: create a self walk with
   `docs: true` (section 8), titled as the ticket says.
3. Do the work the ticket describes, with a `caption` on each step.
   Shift the clock where the story needs it (section 7).
4. `seal`. Read `return: "export"` and check it: the engine is
   `demo-…`, the cast holds only seeded names, the frames are there.
5. `render(clone, walk)` (section 8). It answers the artifact.
6. File back: one comment on the ticket in the working engine, with
   each file's name, size, sha256 and link.
7. `down(clone)`. If the seat dies first, the reaper does it.

This replaces spec-demo-clones child 5, the experiment a person was to
run. The first run of this seat is that experiment, and it passes on
the same terms, with "the video plays" in place of "the file plays in a
working engine's replay view".

## 7. Fast-forward time, in a demo engine only

*Story: the agent schedules a call for 08:30 tomorrow, and the viewer
sees it run seconds later.*

**The door is a kind, `clock_shift`.** An agent reaches a kind with the
tools it has, a create is audited like any write, and its history says
who moved the clock and when. A bare route would need a twelfth tool.

```
clock_shift
  fields: to (instant) or by (ISO-8601 duration), exactly one;
          offset_seconds (the engine's total shift after this row,
          stamped by the engine)
  states: applied (terminal)
  doors:  create
```

The shift is forward only. The total is at most 14 days, which keeps
it inside every retention the engine sweeps on (a walk's frames are
kept 30). The engine's `:now-fn` adds the latest row's `offset_seconds`
to the real clock, and reads it at boot, so a restarted task keeps its
time. After the create commits, the door runs the clock's passes once,
`scheduled/sweep-due!` and `invitations/sweep-expired!`, so the viewer
does not wait thirty seconds for the loop. `caption` works on this
create as on any invoke. With none, the staging writes "Time moves to
08:30 the next day." in the scheduler's zone.

**The run must reach the walk.** A scheduled run is made by the sweep,
not by a request, so no write door hands its transition to
`record-own!`. The run is the scheduler's own act, under the grant read
at `run_at`. This child hands it over with that principal and that
grant's visibility. Without it the 08:30 run happens and the walk does
not show it.

**A frame's `t` is recording time, not engine time.** **Check** which
clock `record-frame!` reads. It must be the real one, so that a shift
does not reorder frames. The test below pins it.

**The wall is three walls, and any one is enough:**

1. **The kind is absent.** `clock_shift` is in its own module, and the
   boot assembles that module only when `WAYMARK10_SEED` is set. That
   boot already refuses, through `seed/admit!`, an engine not named
   `demo-…` and an engine with an IdP. A working engine does not serve
   the kind: not in discovery, not in a grant, 404.
2. **The guard refuses.** The create guard asks `seed/admit!`'s
   question again of the live engine.
3. **There is nothing to set.** An engine built without the module
   gets the plain `:now-fn`. It has no offset to read, so a row that
   somehow existed would move nothing.

The UI is not taught the shifted time. A live screen in a shifted
clone shows relative times against the browser's clock. Nobody looks at
a live screen in this design, and replay draws from frames.

## 8. Render the walk to a video file

*Story: the walk becomes an MP4 with no person at a screen.*

### What replay draws today

It draws the recording and nothing else, which is right for a file
played on someone else's engine and wrong for a film. A row is a panel
headed "Replay" with a state chip and a list of transitions. A form is
text areas named after the arguments. A viewer of that video does not
see the product. spec-demo-clones saw the same gap for the standalone
player and left it as "the first thing the child must decide". It is
decided here.

The alternative, filming a headless browser that follows the agent
live, does draw real screens. It is rejected: the film would hold every
pause in which the model thinks, and every burst in which a form fills
in 3 ms. Replay is where pacing can be made right.

### 8a. The walk carries the screens it visits (waymark)

A walk created with `docs: true` records one more frame type, `doc`:
the document of a `self`, as the recorder's own read would answer it at
that moment.

- A `move` to a row is followed by that row's envelope. A `move` to a
  collection is followed by the collection page under the `ui` frame's
  query. A `transition` is followed by the row's envelope after it.
  An invoke's dialog needs no frame of its own: the envelope already
  holds the action's input schema.
- A transition on a quest the recorder owns, made by anyone (the
  engine's `plan`, `finish` and `unpin` among them), is followed by
  that quest's envelope in the recorder's self walk. The quests'
  consumer hands it over, under the quest's grant.
- A transition by another principal that moves such a quest (a step on
  a row its plan names) is recorded in that walk as a `transition`
  frame, just before the quest's envelope, when the recorder may see
  the row. Replay shows it as a notice. Replay and film mode draw the
  quest tracker from these envelopes and read nothing: the latest one
  of a pinned, active quest, with every door disabled.
- A document is rendered under the sight the frame was recorded under.
  A walk still holds no more than its recorder saw.
- A document over 64 KB is not recorded. One byte-equal to the last
  recorded for that `self` is not recorded again. A walk's documents
  stop at 8 MiB in total. In each case replay falls back to today's
  panel for that screen.
- **Export.** A `doc` line crosses under the export's rules: the row
  must pass the exporter's `:row?`, `data` keeps the keys `:field?`
  admits, `actions` keeps the entries `:action?` admits, and a value of
  a principal-ref field becomes its cast alias. The format stays
  `waymark-walk/1`: `parseWalk` keeps any line with a `type`, and
  today's replay ignores a type it does not know.
- **Replay.** `renderReplay` hands the screen's latest `doc` at or
  before the playhead to the code that draws a live row or collection,
  with every action disabled. A dialog is drawn from the document's
  input schema. It still makes no read and no write.

`docs` defaults to false. An onboarding walk on a working engine may
ask for it too. This is the largest child. If it does not fit the
bench's ceiling it splits at the line between recording with export and
replay.

### 8b. Film mode (waymark)

`/#/api/walks/<id>?film=1` plays a sealed walk for a camera.

- The shell's chrome is hidden: the nav's dev principal box, the replay
  chip, the demo banner and every toast.
- A title card shows the walk's title for 2 s. Play starts by itself at
  1×. The last screen holds for 1.5 s.
- **Captions on video.** A caption is a band across the bottom of the
  screen: at most two lines, large type, the product's own tokens. A
  caption with a field is drawn beside the lit field instead, as an
  invitation's note is. An **invitation** looks as it does in replay.
- **The pointer is filmed.** It is drawn in film mode as it is in
  replay (section 2): it glides to each link, button and field, a
  clicked field wears the focus ring, the submit is pressed, and a row
  that is not on screen is reached by the navigation entry and the
  kind's list. A step with no click behind it shows its notice in the
  caption band.
- The page says where it is in `data-film` on the root element:
  `ready`, `playing`, then `ended`. The camera reads that and nothing
  else.
- **The page says each beat.** A reader that cannot watch the film
  reads what it showed. For each frame the replay steps to, the page
  dispatches a `waymark:film-beat` CustomEvent on `document`; its
  `detail` is the beat, and `window.wmFilmBeats` is the array of every
  beat of the take, in order. This is the contract the renderer (8c)
  and the scorer read. Outside film mode nothing is dispatched. A beat is plain JSON:
  - `i` is the frame's index and `t` its own time. `type` is the
    frame's type. `who` (the cast id), `self`, `action` and `kind` are
    there when the frame has them.
  - `pressed` is what the pointer pressed for the frame, as
    `{label, target}`, or null. `label` is the button's visible text.
    `target` is a stable name where one exists: `door:<action>`,
    `sheet.accept`, `sheet.decline`, `tracker.go`, `dialog.submit`, and
    `dialog.accept` for "Accept as quest" under a form's refusal, and
    `nav:<path>` for a link or a navigation entry, where `<path>` is
    the address it leads to. A click on a form's field is no press
    here: it is in `presses`.
  - `presses` is every press the pointer made on the way to the frame,
    in order, each as `{label, target}`. It is empty when the frame had
    no gesture. It holds a click on a form's field as `field:<name>`,
    with the field's label as `label` or null; the navigation entry of
    a hop and the link of a walk to another row as `nav:<path>`; and
    last the frame's own press, the same value as `pressed`. So a
    typing beat has `pressed` null and one `field:<name>` press, and a
    frame on a row that was not on screen lists the entry and the link
    pressed to reach it before its button.
  - `screen` is the address shown.
  - `dialog` is the open form as `{self, action, lit}`, or null. `lit`
    lists the names of the fields that are lit.
  - `sheet` is the open quest's sheet as
    `{goal, steps, shut_reason, refused}`, or null. Each step is
    `{n, label, whose, state}`. `label` is the step's door and row as
    the sheet says them. `state` is null, because the sheet draws none.
  - `tracker` is `{title, next, waiting_on, progress, text}`, or null
    when no tracker shows. `text` is the whole bar without its menu.
  - `caption` is the caption band's text, or the caption beside a
    field, or null. `refusal` is the refusal line on screen, or null.
  - `text` is the text a viewer could read: every run of text that is
    drawn and in the viewport, in reading order, cut at 2048
    characters. An open modal's text comes first, because it is drawn
    over the page; the page's follows. A field's value is not in it,
    and the secret dialog gives its heading and its buttons only.
  - `viewport` is `{w, h}`. Every rect and position in a beat is in
    CSS pixels of the viewport, rounded.
  - `boxes` lists every named surface in the viewport (8a) as
    `{name, rect}`, where `rect` is its client rect `{x, y, w, h}`.
  - `focus` is the surface the beat is about, as `{name, rect, text}`,
    or null. It is the pressed element's surface while that element is
    still drawn and no modal has opened over it; a pressed element
    with no surface around it is named by the press's `target`.
    Otherwise it is the open sheet (`sheet`), then the open form
    (`dialog`), then the tracker (`tracker`) when it differs from the
    beat before. So the beat of a press that opened the sheet has
    `focus.name` = `sheet`. `text` is the focus's readable text, cut at
    1024 characters.
  - `type_px` is the computed font size of the largest run of the
    focus text. `contrast` is that run's WCAG contrast ratio, to two
    places, against the background colours drawn behind it: its own
    and its ancestors', to the first opaque one, over white where
    there is none. A background image is not read. Both are null when
    the focus has no readable text, and `contrast` is null for a colour
    the page cannot read as sRGB.
  - `pointer` is where the replay pointer is drawn, as `{x, y}`, or
    null when there is none.

  A beat stays under about 8 KB: `text` is cut first, then the focus's
  text, then `boxes` from its end.

  Every value but the frame's own (`i` to `kind`) is read from the page
  as drawn, in its visible words. The press is read as the frame is
  applied, and each earlier press as it is made. The rest is read when
  the frame has had its hold: just
  before the next frame's gesture or act, and before `ended` for the
  last. A frame that waits behind an open form has its beat when it is
  stepped to, so its beat shows the form.

### 8c. The renderer and its door (waymark-doors)

**It runs inside the clone job.** `render(clone, walk, as)` starts one
more task in the clone's own job: a headless Chromium under Playwright.
It opens the clone's engine at its job-local address, in film mode, as
the cast member that recorded the walk.

- It is inside the wall, so it has no route out and holds no
  credential.
- It reads the engine it films, so the UI and the walk are the same
  commit. spec-demo-clones' "version rule" for the player does not
  arise.
- It cannot film a working engine, because it exists only in a clone's
  job, and `render` is refused on a clone that restored a snapshot.
  This is how Stage A walks are never filmed.

**Format.** 1920×1080, 30 frames a second, no sound. Playwright records
WebM. The task transcodes it to MP4 (H.264, yuv420p) with ffmpeg and
keeps both. A film is at most ten minutes and 200 MB. Past either, the
render fails with a sentence and keeps nothing.

**Where the file goes.** Out through the door, and not through the
model. The task writes to the job's allocation directory. The door
copies three files, the `.mp4`, the `.webm` and the walk's `.ndjson`,
into its own artifact store, which outlives the clone. `render`
answers:

```json
{"artifact": "…", "engine": "demo-3f9a1c2e", "expires": "…",
 "files": [{"name": "walk-3f9a1c2e.mp4", "bytes": 9120331,
            "sha256": "…", "url": "…"}]}
```

Each `url` is unlisted and carries a token of at least 80 random bits,
the rule spec-demo-clones wrote for the browser route. An artifact is
kept seven days. The seat files the answer on the ticket (6b, step 6),
and a person downloads the video from there.

The files do not become attachments of the working engine. That would
need a byte door on the connector or a credential in the clone door,
and the attachment cap is 10 MiB. It is a punt.

## 8a. The surfaces a scene names

A scene and a browser drive address the screen by meaning, not by a
selector. Every interactive surface a demo can name carries
`data-surface="<name>"`, from one vocabulary:

| Name | What it is |
|---|---|
| `nav.<kind>` | A kind's tab in the navigation bar, or its line in the ⋯ menu when it has no tab. |
| `nav-home` | The Home tab of the phone's navigation bar. |
| `nav-domain` | The active application's name in the navigation bar, a link to its home. |
| `nav-access` | The Access tab in the navigation bar. |
| `nav-more` | The navigation bar's ⋯ button, which opens the menu of the kinds without a tab. |
| `nav-jump` | The ⋯ menu's 'Jump to a kind…' line, which opens the jump box. |
| `nav-shell` | The ⋯ menu's Desktop view or Mobile view line, which reloads the page in the other shell. |
| `row` | One row of a collection's table; `data-self` carries its address. |
| `door:<action>` | An action's button on the shown row, open or shut; `data-row` carries the row's address. |
| `door-shut:<action>` | The dotted 'not yet' button of a shut action that a quest can reach. |
| `dialog` | An action's open form; `data-self` and `data-action` say whose it is. |
| `dialog.field:<name>` | One field of a form, its label and its input. |
| `dialog.submit` | The button that writes an action's form. |
| `dialog.cancel` | The button that closes an action's form and writes nothing. |
| `dialog.check` | The form's Check button, which rehearses the write. |
| `dialog.discard` | The form's Discard draft button. |
| `dialog.later` | The form's Do this later button. |
| `dialog.decline` | The form's Decline button for an invitation; it reads Skip in a led walk. |
| `dialog.stop` | The form's Stop button in a led walk. |
| `dialog.accept` | The form's Accept as quest button, offered under a refusal. |
| `secret` | The dialog that shows a secret one time, with its Copy button. |
| `secret.copy` | The secret dialog's first Copy button. |
| `secret.copy-other` | The secret dialog's second copy button, where the dialog has one. |
| `secret.close` | The secret dialog's Done button, which closes it. |
| `report` | The dialog that reports a bulk action's verdicts. |
| `report.close` | The report dialog's Close button. |
| `upload` | The dialog that uploads a file as an attachment. |
| `upload.file` | The upload dialog's file input. |
| `upload.submit` | The upload dialog's Upload button, shut until a file is chosen. |
| `upload.cancel` | The upload dialog's Cancel button. |
| `sheet` | The quest sheet a tap on a dotted button opens. |
| `sheet.step:<n>` | The sheet's step n of the plan, counted from 1. |
| `sheet.accept` | The sheet's Accept quest button. |
| `sheet.decline` | The sheet's Not now button. |
| `tracker` | The bar that shows the pinned quest. |
| `tracker.go` | The tracker's Go button, for the step at the plan's head. |
| `tracker.next` | The tracker's line for the step at the plan's head. |
| `tracker.more` | The tracker's ⋯ menu; the quest's own actions inside it are `door:<action>`. |
| `quest.go` | The Go button of the next step on a quest's own page. |
| `caption` | The caption band of a replay. |
| `refusal` | The line a refused write is said in, in a form or in the sheet. |

The fixed parts of the navigation bar are spelled with a hyphen
(`nav-home`), so that no kind's name can make `nav.<kind>` mean one of
them. A kind folded behind ⋯ is drawn only while the menu is open: a
scene presses `nav-more` first. The same holds for `nav-jump` and
`nav-shell`, which are lines of that menu. A press on `nav-shell`
loads the page again, so a scene's next step waits for the new page.
The upload dialog says a refused upload in a `refusal` line of its own.

The same list is served as JSON at `GET /api/-/ui/surfaces`:
`{"surfaces": [{"name": "tracker.go", "is": "…"}, …]}`. The page's own
code is the source. A test (`ui-surfaces-are-the-names-the-page-sets`)
fails when the page sets a `data-surface` value the list does not
hold, or the list holds a name the page never sets.

Where several elements carry one name, the scene qualifies it by row:
`door:complete@/api/tickets/<id>`. The row is the address the element
carries in `data-row`, or the one the row or the dialog around it
carries in `data-self`. Of several that remain, the one drawn is meant.

The page answers two functions over these names. `surfaceNode(name)`
answers the element. `readSurface(name)` answers what the surface
shows now, read from the page as drawn, and null when it is not on the
screen:

- `sheet`: `{goal, steps: [{n, label, whose, state}], shut_reason, refused}`;
- `tracker`: `{title, next, waiting_on, progress, text}`;
- `dialog`: `{self, action, lit: [field names]}`;
- `secret`: `{heading, copy, copy_other}`, the heading and the labels
  of the two copy buttons. It does not answer the secret, because a
  beat is kept and a secret is shown one time;
- `report`: `{heading, totals, refused: [{row, reason}]}`;
- `upload`: `{file, ready, refused}`: the line under the file input,
  whether Upload can be pressed, and the refusal's text;
- `caption` and `refusal`: the line's text;
- any other name: `{text, disabled}`.

These are the shapes a film beat's `screen` carries (§ 8), so a beat
and a scene's screen check read the same thing.

## 9. What this amends

**docs/spec-guided-follow.md § 4.**

- `walk_frame.type` gains `caption` (section 3) and `doc` (8a). `walk`
  gains the create field `docs`.
- "A self walk records your own screen": the recorder may be an agent.
  Its beats come from the connector's staging (section 2), and no tab
  shares.
- "Export": the connector answers a summary of it (section 4). A walk
  seals itself at 20 000 frames.
- "Replay": bursts are paced, a caption is held for reading time, and a
  walk with documents draws the product's screens.
- "Marketing walks are recorded on a demo engine": and made by an
  agent, and filmed there.

**docs/spec-demo-clones.md.**

- § 2, "The person's browser reaches it", is **withdrawn** with child
  4. A demo clone has no hostname and no route in but the door. The
  pairing rule of § 4 becomes: a chosen identity (`as`) and a renderer
  are given only to a seeded clone, and a seeded clone never restores
  a snapshot.
- § 3: the walk leaves through `render`, as a video and its file, and
  not as a download in a person's browser. The TTL banner stays in the
  engine; nobody reads it.
- § 5: child 3 is child 5 here. Child 4 is dropped. Child 5, the
  experiment, is replaced by the first run of 6b.
- "Later, its own child: the standalone player": its first decision,
  the documents in the file, is made by 8a. The rest of it stands.
- The punt "Backdated history" stands. The demo clock moves forward
  only.

## 10. The children

Each is one pull request with its tests named. Numbers are the owner's
slices; a letter marks a slice that is more than one pull request.

**Stage A, on the working engine**

1. **Connector and bulk writes land in a self walk** (waymark). Ticket
   0096e862, already filed. Needs nothing.
2. **The connector stages its calls** (waymark). The table of section
   2, the in-process beat, the skip when the dialog is already shown,
   and `REPLAY_BURST_GAP`. Needs 1. Tests, `waymark10.mcp-staging-test`:
   `a-get-moves-the-gaze-to-the-row`,
   `a-query-reports-its-collection`,
   `an-invoke-types-each-argument-then-writes-then-closes`,
   `a-dry-run-leaves-the-dialog-open-and-the-invoke-does-not-retype`,
   `a-refused-invoke-leaves-the-dialog-open`,
   `a-secret-argument-is-never-typed`,
   `no-recording-walk-means-no-beat`, and
   `discover-and-schema-make-no-beat`. In `waymark10.ui-test`:
   `ui-replay-paces-a-burst`.
3. **Captions** (waymark). The `caption` frame, the `caption` and
   `caption_field` arguments, the export's rule for it, and replay's
   band, field anchor and reading time. Needs 2. Tests,
   `waymark10.walks-test`: `a-caption-is-recorded-before-its-step`,
   `a-caption-on-a-row-the-recorder-cannot-see-is-not-written`,
   `a-caption-field-must-be-an-open-argument`,
   `an-empty-caption-clears`, and
   `a-caption-crosses-an-export-only-with-its-self`. In
   `waymark10.ui-test`: `ui-replay-shows-a-caption-and-holds-for-it`
   and `ui-replay-anchors-a-caption-to-its-field`.
4. **The export through the connector, and the frame ceiling**
   (waymark). Needs nothing. Tests, `waymark10.mcp-walk-export-test`:
   `an-export-answers-its-size-digest-and-first-lines`,
   `an-export-over-the-cap-is-truncated-and-says-so`,
   `an-unsealed-walk-is-refused-with-seal-as-the-remedy`, and
   `the-connector-export-is-the-routes-bytes`. In
   `waymark10.walks-test`: `a-walk-seals-itself-at-the-frame-ceiling`.

**Stage B, in a demo clone**

5. **`seed` on clone-mcp's `up`** (waymark-doors). spec-demo-clones
   child 3 and its six tests, and the cast in `up`'s answer:
   `up-with-a-seed-answers-the-cast-and-no-url`. Needs the seed loader,
   which is merged, and clone-mcp deployed (bbc7d338).
6. **a. `call` as a cast member** (waymark-doors). Needs 5. Tests:
   `a-call-acts-as-the-named-cast-member`,
   `as-is-refused-on-a-bug-clone`, and
   `as-must-name-a-member-of-the-seeds-cast`.
   **b. The `demo-walker` seat and its routine** (waymark). The
   routine document and the walk's whole path over the in-memory twin
   with the demo seed. Needs 2, 3, 4, 7 and 8a merged; its first live
   run needs 6a and 8c deployed. Tests, `waymark10.demo-walk-test`:
   `an-agent-alone-records-seals-and-exports-a-demo-walk`,
   `the-demo-walks-cast-holds-only-seeded-names`, and
   `the-demo-walks-header-names-a-demo-engine`.
7. **The demo clock** (waymark). The `clock_shift` kind in its own
   module, the three walls, the passes run after a shift, and the
   scheduled run handed to the scheduler's walk. Needs 1, for the
   hand-over seam. Tests, `waymark10.demo-clock-test`:
   `a-working-engine-does-not-serve-the-kind`,
   `a-shift-is-refused-on-an-engine-not-named-demo`,
   `a-shift-is-refused-on-an-engine-with-an-idp`,
   `a-shift-moves-only-forward-and-at-most-fourteen-days`,
   `a-due-scheduled-action-runs-when-the-clock-passes-it`,
   `the-run-is-recorded-in-the-schedulers-walk`,
   `a-frames-t-is-recording-time`, and
   `a-restarted-engine-keeps-its-shift`.
8. **a. The walk carries the screens it visits** (waymark). Needs
   nothing. Tests, `waymark10.walks-test`:
   `a-doc-follows-a-move-and-a-transition`,
   `a-doc-holds-only-what-the-recorder-saw`,
   `an-unchanged-doc-is-not-recorded-twice`,
   `a-doc-over-the-cap-is-left-out`,
   `an-exported-doc-is-redacted-under-the-exporter`, and
   `an-exported-doc-names-principals-by-alias`. In `waymark10.ui-test`:
   `ui-replay-draws-a-row-from-its-doc` and
   `ui-replay-falls-back-without-a-doc`.
   **b. Film mode** (waymark). Needs 3 and 8a. Tests,
   `waymark10.ui-test`: `ui-film-mode-hides-the-chrome`,
   `ui-film-mode-says-when-it-has-ended`, and
   `ui-film-mode-draws-a-caption-as-a-band`.
   **c. The renderer and `render`** (waymark-doors). Needs 5 and 8b.
   Tests: `render-answers-an-mp4-of-the-walk`,
   `render-is-refused-on-a-bug-clone`,
   `the-renderer-has-no-route-out`,
   `a-film-over-the-limit-fails-and-keeps-nothing`, and
   `the-artifact-outlives-the-clone-and-then-expires`.

**Later, only if runs drift**

9. **A declarative script** (waymark). The agent writes the walk as
   data, a list of steps each with its call and its caption, and the
   engine checks every step as a dry run before any is played. It is
   not designed here. Build it when two runs of 6b on one ticket give
   films that differ in what they show.

The shortest path to a first film is 1, 2, 3, 8a, 8b, then 5, 6a, 8c,
then 6b. Children 4 and 7 make the film better and block nothing but
6b's check and the scheduling story.

## Punts

- **Captions from a person's ● Record.** The argument is the
  connector's. A browser recorder has no way to write one yet.
- **Staging without a walk.** An agent followed live in guided mode,
  with no walk recording, is still not staged.
- **Long values on film.** The 6000-byte cap elides a long argument in
  a `ui` frame, so the film shows that it was written and not its
  text. The row's document after the write shows it whole.
- **A withdrawn invitation in replay.** Replay closes an invitation's
  dialog only on the transition that answers it. In a walk with nobody
  to answer, the agent withdraws, and the dialog stays. Ticketed apart.
- **Sound and voice.** The film is silent. A narration track would be
  made from the captions.
- **Films as attachments.** Section 8c.
- **The UI on a shifted clock.** Section 7.
- **More than one agent in a walk.** A self walk has one recorder. A
  film of an agent leading a second principal needs a follower's walk,
  and so a second connector session in the clone.
