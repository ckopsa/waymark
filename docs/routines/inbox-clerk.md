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
| model | Opus 5 | the seat's `held_for`; the schedule row mirrors it |
| repository | `ckopsa/waymark` | the Stop hook that closes the sitting lives in its `.claude/settings.json`; the clerk still touches no code |
| trigger | Schedule, `0 * * * *` | `cadence_seconds` 3600. Keep the API trigger too: it gives the fire URL. |
| fire URL | the API trigger's URL | the engine fires the Routine through it. See "The fire link". |
| connectors | Waymark only | mail is read through the seat's `email.read` power |
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

## The instructions

```
Your seat key is: <paste the key here>

First, run `echo $CLAUDE_CODE_SESSION_ID` and call waymark_sit once with
that key and that value as `session`. Then you sit in the seat
`inbox-clerk`. Read the seat row with waymark_get and do what its
charter says. Take only the doors the envelope offers. When the seat
says halted or parked, say why and stop.

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
   the seat's grant, with the schedule's model as its claim.
3. `waymark_discover` shows `doors.ask.seat`. A halt or a parked
   state means say why and stop.
4. The session reads the seat row for the charter, then walks
   `inbox_item` under its default filter, oldest first, up to
   `rows_per_firing` rows. For each row it takes the one door the
   envelope offers. The first request opened a sitting; the router
   counts each transition and each refusal against it.

   A fired run differs here. The engine puts the fire's text into the
   session in a `routine-fire-payload` block. When that block names
   one row id, the instructions above tell the session to walk that
   row and to stop. A fire with no text walks the queue, as a
   schedule firing does.
5. The session stops and says in one line why it stopped and how
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
