# Spec — guided follow

**Thesis.** Following already shows a person where an agent LOOKS and
what it WRITES. It does not show what the agent is doing between the
two: a dialog opening, a form filling in, a filter being set. Carry
that (opt-in), let the agent hand a step to the person, and record the
whole walk, and following becomes onboarding: the agent shows a person
what to do the way they would do it.

Epic: de2e2f45 ("An agent can walk a person through the UI"). This
document is the design pass the epic asks for, and it files no code.

## Epistemic status

A design, read against the code as it stands on 2026-09-30. The three
stages are usable alone, in order: 1 without 2 is "watch me work", 2
without 3 is "do this step", 3 is "show it again later". Nothing here
changes law. Stage 1 is ephemeral state, like presence and intents;
stages 2 and 3 add kinds, because what they hold must outlive a
restart and be audited.

## What exists

Following is three streams and a client-side filter.

- **The firehose**, `GET /api/-/events` (SSE, with ids and
  Last-Event-ID resume). Every committed transition names its actor.
  This is where the followed principal WRITES.
- **Presence**, `GET /api/-/presence` (`server/presence.clj`). An
  ephemeral registry, fanned across processes on its own `pg_notify`
  channel (`waymark10_presence`, origin-nonce'd) and TTL-evicted.
  Frames are `join`/`move`/`leave` with `{principal {id, display,
  type}, self, source, at}`, plus a snapshot on connect, with no ids
  and no replay. There are three reporting doors: a per-resource SSE
  subscription (`stream`), `POST /api/-/presence {self}` (`heartbeat`)
  and a grant-scoped principal's successful GET (`read`). This is
  where the followed principal LOOKS.
- **Intents**, `GET /api/-/intents` (`server/intents.clj`). A dry-run
  is broadcast as "considering", and a warning wall as "asking". The
  registry is the same kind as presence, on its own channel, and a
  consumer on the engine's events dispatcher clears a card when the
  real transition commits.
- **The concealment rule**, which all three share. A scoped viewer sees
  a frame only if its own visibility (`grants/visibility`, the closures
  `{:kind? :row? :action? :field? :arg? :ids-of}`) could GET the
  `self` the frame names. A filtered frame is **byte-level absent**,
  never narrated. A bare collection self shows to a viewer with
  whole-kind sight.
- **The curtain** (`server/curtain.clj`). A member who has drawn it is
  never published by any ephemeral surface. There is one reader and
  one cache, and a failed lookup counts as curtained.
- **The follow view**, `200-events-follow.js`. `follow(actor)` stores
  `followId` in localStorage and shows the chip. The firehose and
  presence handlers then navigate this screen to the followed
  principal's writes and gaze. The Access panel parks navigation, and
  an open dialog guards it. `followRequester` is the approve hand-off.
  **The server does not know who follows whom.** Following is a
  filter the client applies to streams every viewer already receives.
- **Collab** (`server/collab.clj`) already relays form fields as they
  are typed. It does so only for a shared, live `:draft` on an `:edit`
  action, and inside a draft room whose members are its writers. Guided
  follow is not collab: the follower watches and does not co-edit.
  Collab's frame vocabulary (`set`, `update`, `field`, `value`) is the
  precedent for the field part of the event below. Where the open
  dialog's action already has a shared live draft, the draft is the
  source of `fields`: the reporter reads the draft's values rather
  than keeping a second copy, so the two never disagree.

The gap is plain. A dialog, a form value that has not been submitted,
a filter, a sort, a page and a focused row are client state. No stream
carries them.

## 1. The UI-state event

### The states carried

| part         | what it is                                   | when it changes           |
|--------------|----------------------------------------------|---------------------------|
| `dialog`     | `{self, action}` of the open action dialog, or `null` when it closes | open, close |
| `fields`     | `{name: value}`: the dialog form's values as typed | input, debounced 150 ms |
| `collection` | `{self, filter, sort, page}` of the collection screen | query change |
| `focus`      | the `self` of the focused row, or `null`       | selection, keyboard focus |

The states are exactly these four. Scroll, hover and cursor position
are not carried: they are noise for a person learning a task and
expensive on the wire. The event says what is open, what it holds and
which row is focused. It does not say where the pointer is. Stage 2's
pointer is an invitation, not a mouse.

### The shape

The event is one more presence frame type, `ui`, and it carries the
whole current state, not a diff:

```json
{"type": "ui",
 "principal": {"id": "agent-7", "display": "Planner", "type": "agent"},
 "self": "/api/tickets/0abb…",
 "at": "2026-09-30T17:02:11Z",
 "seq": 42,
 "ui": {"dialog": {"self": "/api/tickets/0abb…", "action": "assign"},
        "fields": {"assignee": "someone", "note": "Take this one"},
        "collection": null,
        "focus": "/api/tickets/0abb…"}}
```

The frame carries the whole state because presence has no ids and no
replay: the snapshot on connect is the truth. A whole-state frame
keeps that true. A follower who joins late sees the dialog already
open, with its fields filled in. `seq` counts up per principal, so a
follower drops a frame that arrives out of order across processes.

The reporter is whoever the followed principal is:

- the browser, on the UI's own state changes; or
- an agent, through `POST /api/-/presence {self, ui}`. An agent has no
  screen, but it can say "I am opening assign on this row, with these
  values". The follower's UI then renders that as if a screen had.
  This is the point of the epic, since the agent is usually the one
  showing the way.

### Redaction, per follower

For each frame, the server judges every part against the follower's
own visibility before the frame is written to that follower's stream.
The reporter's visibility is not used for this.

- The frame as a whole passes presence's existing `self` rule, or it
  is absent.
- `dialog` crosses only if `:row?` admits its self and `:action?`
  admits `(kind, action)`. Otherwise `dialog` is `null`, and `fields`
  goes with it.
- Each key of `fields` crosses only if `:arg?` admits it (a form field
  is an action argument). A key that fails is **removed**, not blanked.
  A follower cannot tell a field it may not read from a field that was
  never typed in.
- `collection.filter` keeps only the keys `:field?` admits.
  `collection.self` follows presence's whole-kind rule. If `sort` names
  a field that fails `:field?`, it is dropped.
- `focus` crosses only if `:row?` admits it. Otherwise it is `null`.
- **Secrets never cross, whatever the grant.** A field the schema marks
  `writeOnly`, `format: password` or `x-secret` is removed on the
  reporting side, and the server removes it again at report time. It
  is never stored in the registry, so it never reaches `pg_notify`.
- The curtain binds as it does for every presence frame.

A frame whose every part was redacted still crosses as a `move`
(presence already showed that `self`). It never says that something
was hidden.

### Size

The registry fans out on `pg_notify`, whose payload limit is 8000
bytes. A `ui` part is capped at 6000 serialized bytes. Over the cap,
the longest `fields` values are replaced by `{"elided": true}`,
longest first, until it fits. A prose field being typed into is the
usual cause, and a follower sees that it is being written without
seeing every keystroke. The report door answers 422 when even an
empty `fields` does not fit.

## 2. Transport, and the opt-in

The event **rides the presence registry**, as a new frame type on the
existing stream and on the existing `waymark10_presence` channel. It
does not get a fourth SSE stream. The page already holds three streams
against Chromium's six-connection cap (waymark-dxnp), and a UI state is
presence in the plainest sense: where someone is, in more detail.

The registry keeps the last `ui` beside each principal's presence
entry. The TTL and eviction are the same, and a `leave` clears it.

**Opt-in is on both sides, and both are needed:**

1. **The follower asks.** `GET /api/-/presence?ui=<pid>` names ONE
   followed principal. `ui` frames for that pid, and only for it, are
   written to that stream. A stream opened without `?ui=` receives
   exactly the frames it receives today, byte for byte. That is
   today's follow, unchanged. In the UI, `follow(actor, {ui: true})`
   reopens the presence stream with the parameter, and the chip gains a
   "guided" mark. `unfollow()` reopens it without the parameter.
2. **The followed principal shares.** A `ui` part is stored only if the
   report carried it. The UI sends one only while the person has turned
   on "share my screen with followers", a per-tab toggle that is off by
   default. An agent sends one when it chooses to. A person's form is
   never broadcast because someone else decided to watch it.

The server still does not learn who follows whom. `?ui=` is a filter
on the follower's own stream, as `followId` is today. Stage 2 makes
the relationship a row, when it needs to be one.

Applying the state on the follower's screen reuses what the follow
view already does. It navigates to `self`, opens the dialog
read-only (inputs disabled and marked "Planner is filling this
in"), sets the collection query and highlights the focused row. The
existing guards still apply: the Access panel parks, and a dialog
the follower opened themselves is never replaced.

## 3. Invited input

An agent points at one field of one action and hands the step to the
person. **The invitation is a row**, the kind `invitation`, and not a
door or an ephemeral frame, for three reasons:

- It must outlive the TTL of the ephemeral registries. A person may
  answer tomorrow.
- It must be audited like every other hand-off: who asked, what for,
  and what answered it. A row's history already says that.
- It is addressed to one person, who must be able to find it again
  outside the follow view.

### The kind

```
invitation
  fields: author (pid, set by the engine), subject (pid: the person),
          self (the row the step acts on), action, field (the input
          pointed at), note (one sentence: "pick the repository here"),
          suggest ({field: value}, optional, shown and never submitted),
          answered_by (transition ref), expires_at
  states: open → answered | declined | withdrawn | expired
  doors:  create    (the author, under its own grant)
          decline   (the subject)
          withdraw  (the author)
          (answer is not a door: see resolution)
```

Guards on `create`:

- The author's own grant must see `self` and admit `action`. An agent
  cannot invite a person onto a row the agent cannot see.
- `field` must be an argument of `action`'s input schema, and a
  secret-marked field is refused. An agent may not point a person at a
  password field with this door.

The guards do NOT judge whether the subject can perform `action`.
That is judged where it always is, at the subject's own invoke. An
invitation to a door the person lacks shows up with the door's own
refusal, which is the honest answer.

### Resolution

Resolution is the consumer pattern intents already use: a consumer on
the engine's events dispatcher. When a committed transition's actor is
the subject and its `(self, action)` match an open invitation, the
engine moves the invitation to `answered` with its own hand and stores
the transition's ref in `answered_by`. The walk continues when the
person acts. An agent waits on the invitation row itself: it reads the
row (or its history) until the row leaves `open`, and then reads
`answered_by`.

**The person submits, under their own grant.** The invitation carries
no credential and no delegation. `suggest` values prefill the form as
suggestions (marked, editable), and they are submitted only if the
person presses submit. The transition the person commits is theirs in
history, and the invitation's `answered_by` points at it. The audit
reads: the agent asked, the person did it.

### In the UI

A follower in guided mode who is the subject of an open invitation
sees it arrive on the firehose (a row transition). Their screen
navigates to `self`, opens the action's dialog in their own hand (the
inputs are enabled, because this is their form now), scrolls the
invited `field` into view with a short animation, highlights it and
shows `note` beside it. Decline is one button. Outside guided mode,
the invitation is an ordinary row in the person's collection, and
opening it does the same: navigate to `self`, open the dialog in the
person's hand, animate to the field, show the note. That is why stage 2
stands without stage 1.

## 4. Recording

A recorded walk is **two kinds**, following the precedent of
`transcript` and `transcript_entry` (`server/transcripts.clj`):

```
walk        one row per recording
  fields: recorder (pid), followed (pid), title, started_at, ended_at,
          frame_count, retention_days (default 30)
  states: recording → sealed → purged
  doors:  create (start; the recorder is a follower in guided mode,
                  or the person themselves: a self walk, below)
          seal   (stop)
          export (sealed only; answers the export document)
          purge  (the recorder, or the sweep after retention_days)

walk_frame  one row per frame, written quietly with the engine's
            own hand (inv/insert-quiet!, as transcript_entry is):
  fields: walk, t (ms since started_at), type (move | ui | transition
          | invitation), body
```

**A walk is recorded under the recorder's sight.** Each frame is
stored as the recorder's stream received it, after section 1's
redaction. A recording therefore never holds more than its recorder
could see, and nothing needs to be redacted after the fact.
`transition` frames carry the firehose's event projected by the
recorder's visibility. Request headers, grant ids, keys and
credentials are never part of a frame.

**A self walk records your own screen, with nobody following.** A walk
whose `followed` is its `recorder` is a person showing how a piece of
work is done, to replay or export afterwards. No follower stream is
open, so the doors that make the frames hand them to the walk: the
beat (`presence/report!`) hands over the `move` and `ui` frames it
made, and the write doors hand over each transition the person
committed (`walks/self-recorder`, `walks/record-own!`). The `ui` frames
exist only while the tab shares, so the UI's ● Record button turns ⧉
sharing on and ■ Stop turns it off again, seals the walk and opens its
row page. The sight needs no extra rule: the recorder is the person
whose screen it is, each frame is judged under the visibility of the
request that made it, and a row that person cannot see is not written.
The walk's own create and seal are not recorded in it. The create and
action routes hand over their one transition and the bulk route one per
row it moved; the connector's `waymark_invoke` rides those same routes,
so an agent's own walk holds what the agent did. A held call's replay
after the person's allow is not recorded yet (the forward keeps no
sight of the author's session to judge the frame under), and neither
is a batch on one row or a bulk call deferred to a job.

**Retention.** `retention_days` defaults to 30. A sweep purges
`walk_frame` rows after that time and moves the walk to `purged`. The
transcript sweep is the precedent. The row itself stays, with its
title and counts, as the audit that a walk existed.

**Export.** The `export` door answers `application/x-ndjson` in the
format `waymark-walk/1`:

```
{"format": "waymark-walk/1", "title": "…", "recorded": "2026-09-30",
 "engine": "demo",
 "cast": {"a1": {"display": "Planner", "type": "agent"},
          "p1": {"display": "Person", "type": "human"}}}
{"t": 0,    "type": "move", "who": "a1", "self": "/api/tickets/…"}
{"t": 1830, "type": "ui",   "who": "a1", "ui": {…}}
```

The export has no keys and no secrets:

- Principal ids are replaced by cast aliases (`a1`, `p1`). The export
  carries no pid, no grant id and no sitting id.
- There is no header, token, key or URL origin. A `self` is a path.
- Every frame is **re-redacted under the exporting viewer's
  visibility**, which may be narrower than the recorder's (a walk
  shared with someone else). The exporter sees the intersection. A
  frame that fails entirely is left out, and `t` keeps the gap.
- Secret-marked fields were never recorded (section 1).

**Replay.** The UI can play an export in a read-only follow view. The
same code that applies live `ui` and `move` frames applies the frames
from the file, on a timer. It makes no network writes, and a
`transition` frame renders from its body, not from a live read. The
same file is the marketing artifact: it shows how the work is done,
with only the rows its exporter could see.

**Marketing walks are recorded on a demo engine.** Redaction bounds a
recording by what its recorder could see, and that is still real data:
real tickets, repositories and people. A walk meant for the public is
recorded on a separate engine seeded with demo rows. The export's
header carries `"engine"` (the engine's own name), so a reader can
tell a demo walk from a real one. Walks recorded on a working engine
are for onboarding inside it.

**Amended by `docs/spec-agent-demo-walks.md` (2026-10-01).** A
marketing walk is made by one agent alone in a demo clone and filmed
there. That spec adds two frame types (`caption`, `doc`), the
connector's staging of an agent's calls as `move` and `ui` beats, the
export's summary through the connector, a frame ceiling, and replay's
pacing. Its section 9 lists each change to this section.

## 5. The children

Each child is one PR under the bench's ceiling. **Build stage 2
first.** Its children (3 and 4) depend on nothing in stage 1: an
invitation is an ordinary row, and child 4's dialog-open, highlight
and note work from the row page and the person's own collection, with
guided mode as an extra entry point once stage 1 lands. The "agent
prepares, person taps" hand-off is most of the onboarding value, and
held calls already prove the shape. Stage 1 follows, then stage 3,
whose children depend on stage 1.

**Stage 1: follow carries UI state**

1. *Presence carries a redacted `ui` frame for opted-in followers.*
   Covers `report!` accepting `ui`, the per-follower redaction in
   section 1, the `?ui=<pid>` stream parameter, the 6000-byte cap with
   elision, removal of secret fields at report time, and the last `ui`
   in the snapshot. Tests in `waymark10.presence-test`:
   `ui-frames-cross-only-to-a-follower-that-asked`,
   `a-stream-without-ui-is-byte-for-byte-today`,
   `ui-fields-the-follower-cannot-read-are-absent`,
   `secret-fields-never-reach-the-registry`,
   `ui-over-the-cap-elides-longest-first`, and
   `ui-frames-honour-the-curtain` (beside `waymark10.curtain-test`).
2. *The UI reports and applies UI state.* Covers the "share my screen"
   toggle, the reporting from the dialog, form, collection and focus
   (`180-action-dialog.js`, `170-forms.js`, `130-collection.js`),
   `follow(actor, {ui: true})` and the read-only application in
   `200-events-follow.js`. Tests in `waymark10.ui-test`:
   `ui-follow-offers-guided-mode` and
   `ui-sharing-is-off-by-default`.

**Stage 2: invited input**

3. *The `invitation` kind and its resolution.* Covers the kind, its
   create guards, decline, withdraw, the expiry sweep, and the
   events-dispatcher consumer that answers it. Tests in a new
   `waymark10.invitations-test`:
   `the-persons-own-transition-answers-the-invitation`,
   `an-author-cannot-invite-onto-a-row-it-cannot-see`,
   `a-secret-field-cannot-be-invited`,
   `another-actors-transition-does-not-answer-it`,
   `an-open-invitation-expires`, and
   `suggest-is-never-submitted-by-the-engine`.
4. *The UI receives an invitation.* Covers navigating, opening the
   dialog in the person's hand, animating to and highlighting the
   field, the note and decline. Tests in `waymark10.ui-test`:
   `ui-renders-an-invitation-in-guided-mode`.

**Stage 3: record and export**

5. *The `walk` and `walk_frame` kinds, recording and retention.*
   Covers start and seal, quiet frame writes from the recorder's
   redacted stream, and the retention purge. Tests in a new
   `waymark10.walks-test`:
   `a-walk-records-only-what-its-recorder-saw`,
   `a-sealed-walk-takes-no-frames`, and
   `retention-purges-frames-and-keeps-the-row`.
6. *Export and replay.* Covers the `export` door (`waymark-walk/1`,
   cast aliases, re-redaction under the exporter) and the UI's replay
   view. Tests in `waymark10.walks-test`:
   `an-export-carries-no-pid-grant-or-key`,
   `an-export-re-redacts-under-the-exporting-grant`, and
   `an-export-round-trips-through-replay`.

## Punts

- **Pointer and scroll.** Not carried (section 1). Revisit only if a
  recorded walk reads as jumpy without them.
- **Two visible tabs.** The six-connection cap still applies
  (waymark-p5tg). Guided follow adds no stream, so it makes this no
  worse.
- **Server-known following.** `?ui=` keeps following a client-side
  filter. A "who is watching me" list for the followed person would
  need the relationship on the server, and this spec does not add one.
