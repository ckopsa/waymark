# Routine: inbox-clerk

The clerk trial's driver (docs/spec-seat.md section 12, the owner's
ruling of 2026-09-16: use the harness's native scheduler). This file
is the record of what the schedule holds. A person creates the
Routine from it, in Claude, and updates it from it. Nothing here runs
on its own.

Written in ASD-STE100 Simplified Technical English.

## The fields

| field | value | why |
|---|---|---|
| name | `inbox-clerk` | the seat's name, one spelling |
| schedule | `0 * * * *` (hourly, UTC) | `cadence_seconds` 3600 |
| model | `claude-sonnet-5` | the seat's `held_for` for the trial |
| session | a fresh session on each firing | one firing is one sitting |
| prompt | the text below | the charter and the walk rule |

For the trial the prompt carries the charter itself, because no seat
row exists yet. When the seat kind lands, the prompt becomes the
pointer of R-12.2 and the charter moves to the row.

## The prompt

```
You triage Colton's inbox. For each message the queue offers, take
the one door the envelope shows. Research first. Then say yes with
the action item in one sentence, or no. A request that names Colton
and asks for something is a yes.

The walk rule. Call waymark_query on the kind inbox_item; the
collection under its default filter is the queue, oldest first. For
each row: read the row with waymark_get; take the one door its
envelope offers, and no other; to research, read the message through
waymark_power (the email.read power, by the row's message_id) and
invoke research with a summary; then the envelope offers yes and no,
take one. When a row is at a leaf, move to the next. Stop after 20
rows, or when the queue is empty. When you stop, say in one line why
you stopped and how many rows you moved.

If a door refuses, read its reason and do what it says once. Do not
retry a second time. Do not read any document, file, or rule outside
the engine's answers.
```

## The grant

The session sits under a plain scope grant, filed through the ask
door once and extended by the leash keeper
(`scripts/standing-agent-tick.sh`).

```json
{
  "task": "Sit as the inbox clerk for one week.",
  "scope": [
    {"kind": "email.read", "actions": []},
    {"kind": "inbox_item", "actions": ["research", "yes", "no"]}
  ],
  "expires_at": "<seven days from the ask>"
}
```

The scope does not name `task.create`. The yes door births the task
inside the engine, under the outer principal, and `:touches` says
so. The scope does not name `reopen`: that door is the person's.

## Before the first firing

1. The `inbox_item` kind is deployed and `make check-queue` is green.
2. The inbox source has run one pass and the queue holds rows.
3. The leash keeper's cron is live and its cookie is in the MCP
   config the Routine's session reads.
4. The grant is accepted.
5. The Routine exists with the fields above, and its first firing is
   watched by a person.

## The ledger for the week

No sitting kind exists yet. The harness's own session record is the
ledger. After each firing, a person or a script writes one line:

| firing | rows moved | research | yes | no | tokens in | tokens out | cost | reopens later |
|---|---|---|---|---|---|---|---|---|

At the end of the week these answer: what one email cost, how many
action items the clerk found, how many a person reopened, how many
times a person touched the system, and whether anything failed in
silence. Those numbers decide which parts of the seat spec are built
first (spec-seat.md 13.7).

## To pause

Pause the Routine. Nothing else. The grant stays and expires on its
own leash. To resume, unpause it.

## Later

- The source fires the Routine through the API with one row's id,
  so one session walks one row (R-12.7).
- An engine effect updates the Routine when the seat restates its
  name, cadence, or model (section 17).
