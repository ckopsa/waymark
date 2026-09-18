# Routine: inbox-clerk

The clerk's driver: a Claude Routine with the engine's connector
attached (docs/spec-seat.md section 12.1). The person makes it once,
by hand, from this file. The engine owns a `schedule` row for the
seat. The Routines API has no door that makes a Routine, so this
file is the copy the row describes. The person links this Routine to
that row, and the engine fires it (see "The fire link").

Written in ASD-STE100 Simplified Technical English.

## The seat comes first

The seat `inbox-clerk` is a row (spec-seat.md 13.9). It holds the
charter, the scope, the walk, the models, and the budgets. The
Routine holds none of them. A restate of the seat changes the next
firing with no change to the Routine.

## The key

1. Mint a key by machine, 128 bits. For example:
   `openssl rand -base64 24`.
2. Invoke `offer_key` on the seat with the key. The engine stores
   it and never shows it again. A second `offer_key` replaces the
   first. `revoke_key` clears it.
3. Paste the key into the Routine's instructions, below.

The key alone opens nothing. The connector's credential must be
present too, and the credential is the person's own.

## The Routine's fields

| field | value | why |
|---|---|---|
| name | `inbox-clerk` | the seat's name, one spelling |
| model | the seat's first `held_for` | the schedule row is linked, so its copy is not pushed; keep the Routine's model equal to `held_for` by hand |
| repository | `ckopsa/waymark-seat` | the seat's place: the close hook and nothing to read; the clerk touches no code. One place serves every seat. See "The seat's place". |
| trigger | Schedule, `0 * * * *` | `cadence_seconds` 3600. Keep the API trigger too: it gives the fire URL. |
| fire URL | the API trigger's URL | the engine fires the Routine through it. See "The fire link". |
| connectors | Waymark only | the research door reads the mail through the seat's `email.read` power |
| instructions | the text below | the key, then the pointer of R-12.3 |

## The fire link

The engine cannot make this Routine, and it cannot read it. The
Routines API fires a Routine and does nothing else. The link is the
by-hand path (spec-seat.md R-12.18).

1. Open the Routine and find its API trigger. Copy the fire URL. It
   holds the Routine's id, which is not a secret.
2. Make the trigger's token and copy it. The token is a secret.
   Never paste it into a transcript.
3. Invoke `link` on the seat's schedule row, with the fire URL and
   the token. The row moves to `live`.

The engine holds the token as it holds the seat's key. It never
shows it again. A second `link` replaces the first. `unlink` clears
both fields and moves the row back to `broken`.

After the link, three things start a firing: the Routine's own
schedule, a person's `fire` on the seat, and a transition the seat
asked to be woken by (R-12.19, R-12.22).

## The seat's place

The Routine attaches the repository `ckopsa/waymark-seat`, and not
this one. A Routine attaches a repository and not a branch. The
seat's place holds three files for the session, and nothing else:

- `CLAUDE.md`, a note of at most 20 lines. It says that this is a
  seat's session, that the instructions are in the Routine, and
  that the session reads nothing else.
- `.claude/settings.json`, with the `Stop` entry and the
  `SessionEnd` entry only. There is no `SessionStart` entry, so a
  firing builds nothing.
- `.claude/hooks/sitting-close.sh`, the same script that `main`
  holds here.

There is no skill, no document and no code in that place. A
sitter's turn therefore reads the harness's prefix and the
Routine's instructions, and not this repository. The place names
no seat, so one place serves every seat: the key in each Routine's
instructions tells the seats apart.

The three files have one source, and it is here. `seat/CLAUDE.md`
and `seat/.claude/settings.json` on `main` are two of them. The
hook keeps its source at `.claude/hooks/sitting-close.sh`, because
an interactive sitting in this repository uses the same script.
Two workflows carry them, and neither holds a secret:

1. `.github/workflows/seat-place.yml` here runs on each push to
   `main` that changes `seat/**`, the hook, or the workflow itself.
   It puts the three files in a clean tree, counts them, measures
   `CLAUDE.md`, and pushes the tree as the orphan branch `seat` of
   this repository. A fourth file fails the run.
2. `.github/workflows/sync.yml` in `ckopsa/waymark-seat` runs each
   hour and by hand. It checks out that branch, which is public,
   copies the three files into its own `main`, counts them again,
   and commits with its own token when they changed.

A change to the hook here reaches the seat's place within the
hour. GitHub stops a schedule after sixty days with no commit in
that repository. When the hook changes after a quiet season, run
`sync` there by hand once.

One hand step stays. Open the Routine and set its repository field
to `ckopsa/waymark-seat`.

## The instructions

```
Your seat key is: <paste the key here>

First, run `echo $CLAUDE_CODE_SESSION_ID` and call waymark_sit once with
that key and that value as `session`. Then you sit in the seat
`inbox-clerk`. The sit answers the charter and your rows, each with its
doors. For each row, invoke the door the charter chooses. Do not call
discover, schema, query or powers; a refusal names its own remedy. When
the seat says halted or parked, say why and stop.

The research door reads the message for you: after it, the row's
body_excerpt holds the first part of the plain text, and body_cut
says how much was cut. Use waymark_power only when that excerpt is
not enough to decide, and ask for text_only with max_chars 4000.

If a routine-fire-payload block names a row id, walk that row and stop.

When the Stop hook asks you to close the sitting, make that one call
with the numbers it gives, then stop.
```

Nothing else goes in the instructions (R-12.10). The charter is on
the seat row. The walk rule is the seat's `walk`. A refusal carries
its own reason.

## What one firing does

1. The connector initializes. The engine answers a session id.
2. The session calls `waymark_sit` with the key and with its own
   session id, which the container gives it as
   `CLAUDE_CODE_SESSION_ID`. The engine binds the session to the
   seat, and the sitting is born with `harness_session` set to that
   id. From here the session is the sitter `seat:{seat id}`, wearing
   the seat's grant, with the seat's first `held_for` as its claim
   (the schedule is linked, so its own copy of the model is not the
   declaration).
3. The sit's own answer carries the walk (R-12.28): the charter, and
   the `inbox_item` rows under the kind's default filter, oldest
   first, up to `rows_per_firing` of them. Each row carries its
   summary and the doors its envelope offers, and each door carries
   the input it takes. The engine read those rows as the sitter,
   under the seat's grant, so a row the grant does not admit is
   absent. For each row the session invokes the door the charter
   chooses. It calls no discover, no schema, no query and no powers
   before that first invoke: the sit already answered them, and a
   refusal names its own remedy. A halt or a parked state means say
   why and stop. The sitting is open from the bind; the router counts
   each transition and each refusal against it.

   THE RESEARCH DOOR READS THE MESSAGE FOR THE SITTER. The handler
   calls the `email.read` power itself, under the same grant the
   session wears, and writes the first 4,000 characters of the plain
   text on the row as `body_excerpt`, with `body_cut` to say how much
   it removed. The session therefore does not open the mail to
   research it: it says what the message asks, and the words are in
   the envelope it already has. This is the largest lever on the
   bill. One mail answer of 179 KB was 80 percent of what a whole
   sitting read, and each turn after it read the same bytes again.

   When the engine cannot read the message — no grant, a dark Gate,
   a rig that says no — the door still opens and the excerpt is
   empty. Nothing about the walk changes.

   A fired run differs here. The engine puts the fire's text into the
   session in a `routine-fire-payload` block. When that block names
   one row id, the instructions above tell the session to walk that
   row and to stop. A fire with no text walks the queue, as a
   schedule firing does.
4. The session stops and says in one line why it stopped and how
   many rows it moved. The harness then raises its Stop event, and
   the Stop hook sums the transcript's usage.

   With the repository alone, the hook holds the stop one time. It
   gives the session the sitting's id and the counts. The session
   makes one `waymark_invoke` call, `close` on the sitting, with
   those numbers, and then it stops. The hook holds the stop one
   time only, so the second Stop event ends the session.

   When the environment carries the URL, the hook posts the counts
   to `POST /api/-/sittings/close` instead, with the key in the
   header `Waymark-Seat-Key`. The engine finds the seat by the key,
   pairs the report to the sitting by `harness_session`, and closes
   it.

   By either path, the cost is on the sitting row and in the seat's
   ledger within the minute (R-12.17).

## The environment

The Routine's cloud environment needs no settings. With the
repository alone, the Stop hook holds the session's stop one time.
It gives the session the sitting's id and the token counts, and the
session closes its sitting through the connector. No variable, no
credential and no allowed domain are necessary, because the
connector is already attached.

An environment that can carry settings has a second way: the hook
posts the counts to the engine itself, and the session stops one
turn earlier. Three settings make that path.

1. Set the variable `WAYMARK_SEAT_URL` to
   `https://<engine host>/api/-/sittings/close`. The hook takes the
   first path only when this variable is set. A session of this
   repository that did not sit gets neither path: the hook reads no
   sitting in the transcript, and it is silent.
2. Give the environment the seat's key. On Pro or Max, store it as
   an API credential: type Bearer, header name `Waymark-Seat-Key`,
   prefix cleared, host the engine host. The proxy then adds the
   header after the request leaves the container, and the key never
   enters the session. On a plan with no API credential, set the
   variable `WAYMARK_SEAT_KEY` to the key instead.
3. Put the engine host on the environment's allowed domains when no
   credential covers it. A host with a credential is reachable at
   any network level.

The session-start hook of the same repository reads
`WAYMARK_SEAT_URL` too. When the variable is set, the hook stops
before it builds `bd`, because a seat's session never reads beads
and the build costs the firing a minute.

## The chair

The clerk has a training day as well as a work day. The chair is a
second seat, `inbox-clerk-chair`, with the same charter, the same
scope and the same walk as `inbox-clerk`. Its `mode` is
`interactive`. Nothing fires it: it has no Routine, the engine mints
no schedule row for it, a wake passes it by, and its `fire` door is
refused. A person sits in it, from that person's own machine
(spec-seat.md R-10.8). A Routine's run that sits with the chair's key
is refused: `The seat inbox-clerk-chair is an interactive seat. A
person sits here.`

To sit in the chair:

1. Invoke `offer_key` on the chair with a key of its own. Mint it the
   way "The key" above says.
2. Put the key and the engine's URL in the shell of the machine:

   ```
   export WAYMARK_SEAT_KEY=<the chair's key>
   export WAYMARK_SEAT_URL=https://<engine host>/api/-/sittings/close
   ```

   One variable covers the two doors. The hook makes the tally URL
   from `WAYMARK_SEAT_URL` and puts `/tally` in the place of
   `/close`.
3. Start a session on the machine and call `waymark_sit` once with
   the chair's key. The engine answers the mode `interactive`, and
   the sitting is born with that mode and with the person's member
   id in `person`.
4. Work with the model. Read the row, correct the verdict, and say
   what to do next. The sitting stays open between the turns, and the
   hook holds no stop, so the wait is the person's.
5. On each turn's stop, the hook posts the counts to the tally door.
   The counts are cumulative, so the last tally is the sitting's sum
   (spec-seat.md R-12.25).
6. Close the session when the work is done. The `SessionEnd` hook
   posts the final counts to the close door. When the machine is
   closed instead, the sweep ends the sitting after the seat's
   `sitting_idle_seconds`. A sitting that tallied is closed, with the
   last tally as its counts. A sitting that never tallied is
   abandoned, with no tokens (spec-seat.md R-7.6).

The running cost is on the open sitting from the first tally, so the
seat's week and the sitting's own ceiling both see the chair while it
sits. The ledger reports the two seats apart, and a step-down
judgment on `inbox-clerk` reads its fired sittings only.

## Before the first firing

1. The seat exists and is active, with `held_for` naming the model
   the Routine runs.
2. The inbox source has run one pass and the queue holds rows.
3. `offer_key` has been invoked, and the key is in the instructions.
4. `link` has been invoked on the schedule row with the fire URL and
   the token, and the row is `live`.
5. The environment carries the URL and the key, or it carries
   nothing and the hook holds the stop, as the section above says.
6. The first firing is watched by a person, who reads the sitting
   row and the seat's ledger afterwards.

## To pause

Park the seat. The next firing meets the wall, says so, and stops.
Pausing the Routine as well saves the wake. To resume, unpark.

## Later

- The Routines API stays fire-only. It has no door that makes,
  changes or reads a Routine. The engine therefore cannot push this
  Routine from the schedule row, and it cannot read it back. The
  row's `drift` stays empty.
- The link and the fire are built (waymark-fp62.7.3). A person links
  the fire URL and the token one time. The engine then fires the seat
  on demand, and a fire that names one row id makes one session walk
  that one row (R-12.19, R-12.21).
- The seat's `wake_on` fires the seat on a transition. A walk seat
  with no `wake_on` wakes on the walk kind's `create`, so a new
  `inbox_item` starts a sitting. The damper holds the fire while a
  sitting is open, and to one fire in `fire_interval_seconds`
  (R-12.22).
