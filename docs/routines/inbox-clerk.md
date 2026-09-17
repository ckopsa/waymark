# Routine: inbox-clerk

The clerk's driver: a Claude Routine with the engine's connector
attached (docs/spec-seat.md section 12.1). The person makes it once,
by hand, from this file. The engine owns a `schedule` row for the
seat; until the adapter can reach the Routines scheduler, that row
is `broken` and this file is the copy it describes.

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
| repository | none | the clerk never touches code |
| trigger | Schedule, `0 * * * *` | `cadence_seconds` 3600 |
| connectors | Waymark only | mail is read through the seat's `email.read` power |
| instructions | the text below | the key, then the pointer of R-12.3 |

## The instructions

```
Your seat key is: <paste the key here>

Call waymark_sit with that key once, first. Then you sit in the seat
`inbox-clerk`. Read the seat row with waymark_get and do what its
charter says. Take only the doors the envelope offers. When the seat
says halted or parked, say why and stop.
```

Nothing else goes in the instructions (R-12.10). The charter is on
the seat row. The walk rule is the seat's `walk`. A refusal carries
its own reason.

## What one firing does

1. The connector initializes. The engine answers a session id.
2. The session calls `waymark_sit` with the key. The engine binds
   the session to the seat. From here the session is the sitter
   `seat:{seat id}`, wearing the seat's grant, with the schedule's
   model as its claim.
3. `waymark_discover` shows `doors.ask.seat`. A halt or a parked
   state means say why and stop.
4. The session reads the seat row for the charter, then walks
   `inbox_item` under its default filter, oldest first, up to
   `rows_per_firing` rows. For each row it takes the one door the
   envelope offers. The first request opened a sitting; the router
   counts each transition and each refusal against it.
5. The session says in one line why it stopped and how many rows it
   moved. The sitting is closed by the sweep after two cadences
   (R-12.16); its cost is a follow-up.

## Before the first firing

1. The seat exists and is active, with `held_for` naming the model
   the Routine runs.
2. The inbox source has run one pass and the queue holds rows.
3. `offer_key` has been invoked, and the key is in the instructions.
4. The first firing is watched by a person, who reads the sitting
   row and the seat's ledger afterwards.

## To pause

Park the seat. The next firing meets the wall, says so, and stops.
Pausing the Routine as well saves the wake. To resume, unpark.

## Later

- The adapter pushes this Routine from the schedule row, and reads
  it back for drift, once the Routines API is pinned
  (waymark-fp62.7).
- The source fires the schedule with one row's id, so one session
  walks one row (R-12.9).
- A hook at the session's end closes the sitting with its usage
  (waymark-fp62.6.1).
