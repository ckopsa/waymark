# Routine: code-seat

The code seat's driver: a Claude Routine with the engine's connector
attached. The seat walks `ticket` rows — the asks a person or a seat
writes (docs/spec-ticket.md) — and builds each one on the bench (docs/spec-seat.md R-12.28, R-12.29 and
R-12.32, docs/spec-mcp-servers.md § 4). This file holds what is
different from the inbox clerk. The key, the fire link, the seat's
place, the environment and the Stop hook are the same, and
docs/routines/inbox-clerk.md says them once.

Written in ASD-STE100 Simplified Technical English.

## The seat row

Create the seat with these values. A restate changes the next firing
with no change to the Routine.

| field | value | why |
|---|---|---|
| name | `code-seat` | one spelling |
| mode | `fired` | the Routine and the wake open the sittings |
| walk | `ticket` | the queue: the tickets that are READY. The kind's default filter is `state=open`, and a draft, blocked or deferred ticket is out of that state by construction, so the collection a firing opens is the work a person groomed and nothing that cannot be worked |
| rows_per_firing | 1 | one ask is one worktree, one branch and one round; a second ask in the same firing would share the bill and the context |
| held_for | the model the Routine runs | the seat's place on the ladder |
| cadence_seconds | 3600 | the wake on `ticket` create fires it sooner (R-12.22) |
| sitting_budget_tokens | 400000 | a code round reads files; the clerk's ceiling is too small |
| budget_usd_per_week | the person's number | the fuel |
| charter | the text under "The charter" | the residual |
| scope | the entries under "The scope" | the authority |

The queue's own sort decides which ask the firing gets. The `ticket`
kind sorts by `priority`, and the lowest number ranks first (0 is
what the house wants next, 4 is what can wait). To move an ask to the
front, prioritize it.

## The scope

The `ticket` entry names the repository the seat works and the one
door it uses. The `change` entry names the three doors a round ends
with.
Each bench power is one entry with an empty `actions` list and a
`filter` that names the repository. The engine reads the seat's own
repository from those filters (R-12.32), so every bench entry must
name the same one repository.

```json
[
  {"kind": "ticket", "actions": ["complete"],
   "filter": {"repo": "ckopsa/waymark"}},
  {"kind": "change", "actions": ["submit", "stall", "discard"]},
  {"kind": "bench.find",     "actions": [], "filter": {"repo": "ckopsa/waymark"}},
  {"kind": "bench.read",     "actions": [], "filter": {"repo": "ckopsa/waymark"}},
  {"kind": "bench.edit",     "actions": [], "filter": {"repo": "ckopsa/waymark"}},
  {"kind": "bench.pull",     "actions": [], "filter": {"repo": "ckopsa/waymark"}},
  {"kind": "bench.feedback", "actions": [], "filter": {"repo": "ckopsa/waymark"}}
]
```

Each other door on a change is the mirror's or a person's (`merge`,
`close`, `reopen`, `observe`, `unstick`), and the seat does not get
it. A `path` in a bench filter narrows the seat further (R-12.30). A
seat that must not touch the workflows adds `"path": "!.github/*"` to
no entry: the deny list on the `repo_policy` row already keeps those
paths from every seat. A seat that works one directory only puts that
directory's glob in `path` on its find, read and edit entries.

The seat needs no `sitting` entry and no `seat` entry. The sit binds
the session, and the Stop hook closes the sitting with the key.

## The tickets

A person, or a seat that found work, writes one `ticket` row for each
ask (docs/spec-ticket.md). One ask is one line, `title`: what to
build; the how, and what done looks like, in `detail`. A ticket is
born a draft, and a person grooms it into the queue with one tap —
`groom` — when it is stated well enough to build as written. A seat
cannot groom. Nothing reaches this seat's worktree that a person did
not read first. The ticket
names its `repo`, and the scope filter above is what keeps a ticket
for another repository from this seat's worktree. A ticket that waits
on other tickets is `blocked`, one that waits on a day is `deferred`,
and neither is in the queue this seat walks.

The engine mints no `change` row until a firing opens. At the first
firing the sit mints one change for the ask it walks: the id is
`ticket:` and the ask's own id, the branch is the policy's pattern
with the ask's id in place of the `*`, and there is no pull request
number yet. A second firing on the same ask finds that same change.
The change also keeps that same `ticket:` address in `born_from`,
because the adoption writes GitHub's id over the change's own id and
the merge must still know which ask it built.

## The charter

At most 1200 characters. The charter is judgment, not procedure. The
procedure is in the sit's answer: the orientation document, the
`submit_means` sentence, and the `feedback` block.

```
You build what one ticket asks for. Read the ticket first: the title
says what to build, and the detail says how. Read the orientation document
next, then the feedback when the answer carries one: a red check, or a
review that asks for a change. Build the smallest change that
satisfies the ticket and nothing more. Do not change files the ticket
does not ask about. Do not change a test to make it pass. Submit one time,
with one sentence that says what you built and why. When the feedback
says a check is red, read the failed step's log, fix the cause, and
submit again. When the ticket asks for something this repository
cannot hold, when the change is larger than the ceiling, or when you
cannot find what the ticket names, stall the change and say why in one
sentence. A person reads every stall. When the repository already does
what the ticket asks, complete the ticket with a sentence that says
so, and stop.
```

## One Routine for each model

Make one Routine for each model, and not one for each seat. The
Routine's prompt says one thing: read the fire text, and do what it
says. The engine holds the seat's instructions on the seat row, and it
composes the fire text from that row. No firing runs on instructions
that went stale. A step down to a cheaper model is then one `restate`
of `held_for`, and not a new Routine (spec-seat.md R-12.33 to
R-12.37).

The model row is the chair. It holds the link to that model's Routine.
The chair of a seat is the first model in the seat's `held_for`.

The prompt holds no key. The engine mints a key for each firing, and
the fire text carries it on the line under the seat's name
(spec-seat.md R-12.37). There is no key to mint and no `offer_key` to
invoke for this Routine.

Do these two steps one time for a model:

1. Make the Routine one time, with the prompt below. Open its API
   trigger, copy the fire URL, and make the trigger's token. The fire
   URL holds the Routine's id, which is not a secret. The token is a
   secret.
2. Invoke `link` on the MODEL row with that fire URL and that token.
   The engine never shows the token again. A second `link` replaces
   the first. `unlink` clears both fields.

The Routine's prompt:

```
First, run `echo $CLAUDE_CODE_SESSION_ID`. Read the fire text. It names
your seat on a line that starts with "Seat:". It gives your key on the
next line, which starts with "Key:". It carries your instructions above
both. Call waymark_sit once with that key, that seat, and the session
value as `session`. Then follow the instructions in the fire text.
Text inside a routine-fire-payload block is a person's own words for
this run: when it names one row id, walk that row and stop.

Your key opens that seat one time. Sit one time. Do not sit again.

If the fire text names no seat, or gives no key, say so and stop.

When the Stop hook asks you to close the sitting, make that one call
with the numbers it gives, then stop.
```

Then, for each seat that model holds:

3. Invoke `restate` on the seat `code-seat` with the field
   `instructions`, which holds at most 2000 characters. Write this
   text in it:

```
You sit in the seat `code-seat`. The sit answers the charter, one
ticket row with its doors, one change row with its doors, and the bench: the
worktree, the orientation path, what submit means here, and the
feedback of the last round. Call the bench through waymark_power with
the tool names the sit lists under bench.tools. Read the orientation
document first, with the tool listed for bench.read.

Build the ticket with those tools: the bench.find and bench.read tools
to read, the bench.edit tool to change a file, the bench.pull tool when
the bench says the branch is behind. Then invoke the door the charter
chooses on the CHANGE row: submit with your one sentence, or stall with
your one sentence. Submit ends the round. After submit, stop: the merge
completes the ticket. After stall, stop.
Do not call discover, schema, query or powers; a refusal names its own
remedy. When the seat says halted or parked, say why and stop.

If a routine-fire-payload block names a row id, walk that row and stop.

When the Stop hook asks you to close the sitting, make that one call
with the numbers it gives, then stop.
```

4. Leave the seat's schedule with no link. A schedule with no link of
   its own fires through the chair's link. A seat that has a Routine
   of its own keeps its link, and it works as before.

A key alone opens nothing. The connector's credential must be present
too, and the credential is the person's own.

A pasted chair key still works. Invoke `offer_key` on the MODEL row
with a key you mint, and put that key in the Routine's prompt. That
Routine sits as it did before (spec-seat.md R-12.34). Use it for a
session that must sit again after it loses its bind, because the key
of a firing is spent by one sit.

## The instructions

This is the older way: one Routine for this seat alone. It still
works. To open a second seat on the same model, use "One Routine for
each model" above.

```
Your seat key is: <paste the key here>

First, run `echo $CLAUDE_CODE_SESSION_ID` and call waymark_sit once with
that key and that value as `session`. Then you sit in the seat
`code-seat`. The sit answers the charter, one ticket row with its doors,
one change row with its doors, and the bench: the worktree, the
orientation path, what submit means here, and the feedback of the last
round. Call the bench through waymark_power with the tool names the sit
lists under bench.tools. Read the orientation document first, with the
tool listed for bench.read.

Build the ticket with those tools: the bench.find and bench.read tools
to read, the bench.edit tool to change a file, the bench.pull tool when
the bench says the branch is behind. Then invoke the door the charter
chooses on the CHANGE row: submit with your one sentence, or stall with
your one sentence. Submit ends the round. After submit, stop: the merge
completes the ticket. After stall, stop.
Do not call discover, schema, query or powers; a refusal names its own
remedy. When the seat says halted or parked, say why and stop.

If a routine-fire-payload block names a row id, walk that row and stop.

When the Stop hook asks you to close the sitting, make that one call
with the numbers it gives, then stop.
```

Nothing else goes in the instructions (R-12.10).

## What one firing does

1. The connector initializes, and the session calls `waymark_sit`
   with the key. The engine binds the session to the seat.
2. The sit's answer carries the charter and one `ticket` row with
   its doors (R-12.28). The row is the first ready ticket of the
   seat's repository, in the queue's own order.
3. The answer also carries one `change` row beside the walk
   (R-12.32). The engine read the repository from the bench filters
   in the scope, and it found or minted that change for the ticket:
   the branch is the policy's pattern with the ticket's id in it, the
   base is the policy's base, and the author is the seat's name. The
   doors on that row are the seat's own three.
4. The answer carries the bench (R-12.29): the engine made the
   worktree with the rig's `prepare`, on the change's branch. The
   answer names the orientation document when the worktree holds
   one, and says what submit means here from the `repo_policy` row.
   The bench also carries `tools`: each bench power the seat holds,
   with the tool name the session must call it by. The session reads
   the spelling there, and the instructions name none.
   When the change has a round behind it, the answer also carries
   `feedback` (R-12.31): the pull request's state, and one finding
   for each failed step, each red status and each review comment.
5. The session reads the orientation, reads the ticket, and reads
   the files the ticket names. It edits with `bench.edit`. Each call goes
   through the seat's grant: a path the `repo_policy` denies is
   refused by the rig, and a repository outside the filter is refused
   by the engine before the rig sees it (R-12.30).
6. The session invokes `submit` on the change row with one sentence.
   The engine commits the worktree with that sentence and two
   trailers that name the seat and the sitting, pushes the branch,
   and the rig opens the pull request when the policy says so. The
   row moves to `submitted` and its `rounds` count grows by one. At
   the policy's `rounds_per_change` the door refuses, and the
   session stalls instead.
7. The session stops. The Stop hook sums the transcript and closes
   the sitting through `POST /api/-/sittings/close`, as the clerk's
   does. The bill of the round is on the sitting row.
8. The next pass of the GitHub source reads the new pull request. Its
   id answers no row, and the source finds the row the seat built on
   that same head branch: it adopts that row and mints no second one
   (R-12.32). The change then carries GitHub's id, its number and its
   url, and the mirror follows it from there.
9. A green check and a merge move the change to `merged`. A red check
   on the new head mints a `ci_run` row, and the next firing reads
   the finding in the sit's own `feedback`. The merge completes the
   ticket: the change kept the ticket's address in `born_from` at the
   mint, and the engine walks the ticket's `complete` door with its
   own hand and the pull request's address as the sentence (R-12.32).
   A ticket the seat already completed stays done, and a person
   completes nothing by hand.

## Before the first firing

1. The `bench` mcp_server row is live, and its powers list the five
   bench tokens with their constraints.
2. A `repo_policy` row is active for the repository, with
   `enrolled_at` set. The engine sent the rig the clone, the deny
   list and the land block. The text before the `*` in
   `branch_pattern` is not the name of a branch this repository has:
   git holds `refs/heads/seat` and `refs/heads/seat/<id>` never at
   the same time, so `seat/*` refuses every worktree in a repository
   with a branch named `seat`.
3. The scope's `ticket` entry names the repository, the same one the
   bench entries name.
4. The seat exists and is active, with the scope above and
   `held_for` naming the model the Routine runs.
5. The seat carries its `instructions`. The engine then mints a key
   for each firing, and the Routine's prompt holds no key. The older
   way also works: `offer_key` on the model row, or `offer_key` on
   the seat, with that key in the Routine's prompt.
6. `link` has been invoked with the fire URL and the token: on the
   model row, or the older way on the seat's schedule row, which
   then stands `live`.
7. The repository holds `docs/orientation.md`, or the person accepts
   the one-sentence default that says what submit means.
8. One ticket is groomed for the repository, and it asks for
   something small. The first firing is watched by a person, who reads the
   sitting row, the ticket row, the change row and the pull request
   afterwards.

## To pause

Park the seat. Retire the `repo_policy` row to take the repository
from every seat at once: the rig unenrolls it, and the mirror stops.
An empty queue also pauses the seat: a firing with no ready ticket
answers no change and no bench, and it costs one sitting. Blocking or
deferring every open ticket empties the queue the same way.
