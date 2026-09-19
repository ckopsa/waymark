# Routine: code-seat

The code seat's driver: a Claude Routine with the engine's connector
attached. The seat walks `task` rows — the asks a person writes — and
builds each one on the bench (docs/spec-seat.md R-12.28, R-12.29 and
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
| walk | `task` | the queue: the open tasks of the seat's own list. The kind's default filter is `status=open`, so the collection a firing opens is the work that waits |
| rows_per_firing | 1 | one ask is one worktree, one branch and one round; a second ask in the same firing would share the bill and the context |
| held_for | the model the Routine runs | the seat's place on the ladder |
| cadence_seconds | 3600 | the wake on `task` create fires it sooner (R-12.22) |
| sitting_budget_tokens | 400000 | a code round reads files; the clerk's ceiling is too small |
| budget_usd_per_week | the person's number | the fuel |
| charter | the text under "The charter" | the residual |
| scope | the entries under "The scope" | the authority |

The queue's own sort decides which ask the firing gets. The `task`
kind sorts by `priority`, and the lowest number ranks first. A task
with no priority rides behind the ranked ones. To move an ask to the
front, prioritize it.

## The scope

The `task` entry names the list the seat works and the one door it
uses. The `change` entry names the three doors a round ends with.
Each bench power is one entry with an empty `actions` list and a
`filter` that names the repository. The engine reads the seat's own
repository from those filters (R-12.32), so every bench entry must
name the same one repository.

```json
[
  {"kind": "task",   "actions": ["complete"],
   "filter": {"task_list": "<the task list row id>"}},
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

## The task list

A person makes one `task_list` row for this seat and puts the asks in
it. One ask is one line: what to build, and the how in `detail`. The
list keeps the seat's work apart from the family's queue, and the
scope filter above is what makes that separation law.

The engine mints no `change` row until a firing opens. At the first
firing the sit mints one change for the ask it walks: the id is
`task:` and the ask's own id, the branch is the policy's pattern with
the ask's id in place of the `*`, and there is no pull request number
yet. A second firing on the same ask finds that same change.

## The charter

At most 1200 characters. The charter is judgment, not procedure. The
procedure is in the sit's answer: the orientation document, the
`submit_means` sentence, and the `feedback` block.

```
You build what one task asks for. Read the task first: the title says
what to build, and the detail says how. Read the orientation document
next, then the feedback when the answer carries one: a red check, or a
review that asks for a change. Build the smallest change that
satisfies the task and nothing more. Do not change files the task does
not ask about. Do not change a test to make it pass. Submit one time,
with one sentence that says what you built and why. When the feedback
says a check is red, read the failed step's log, fix the cause, and
submit again. When the task asks for something this repository cannot
hold, when the change is larger than the ceiling, or when you cannot
find what the task names, stall the change and say why in one
sentence. A person reads every stall. Complete the task only when the
repository already does what the task asks; then say so in the stall
sentence and stop.
```

## The instructions

```
Your seat key is: <paste the key here>

First, run `echo $CLAUDE_CODE_SESSION_ID` and call waymark_sit once with
that key and that value as `session`. Then you sit in the seat
`code-seat`. The sit answers the charter, one task row with its doors,
one change row with its doors, and the bench: the worktree, the
orientation path, what submit means here, and the feedback of the last
round. Read the orientation document with waymark_power bench.read
before you read anything else.

Build the task with waymark_power: bench.find and bench.read to read,
bench.edit to change a file, bench.pull when the bench says the branch
is behind. Then invoke the door the charter chooses on the CHANGE row:
submit with your one sentence, or stall with your one sentence. Submit
ends the round. After submit or stall, stop. Do not call discover,
schema, query or powers; a refusal names its own remedy. When the seat
says halted or parked, say why and stop.

If a routine-fire-payload block names a row id, walk that row and stop.

When the Stop hook asks you to close the sitting, make that one call
with the numbers it gives, then stop.
```

Nothing else goes in the instructions (R-12.10).

## What one firing does

1. The connector initializes, and the session calls `waymark_sit`
   with the key. The engine binds the session to the seat.
2. The sit's answer carries the charter and one `task` row with its
   doors (R-12.28). The row is the first open task of the seat's
   list, in the queue's own order.
3. The answer also carries one `change` row beside the walk
   (R-12.32). The engine read the repository from the bench filters
   in the scope, and it found or minted that change for the task:
   the branch is the policy's pattern with the task's id in it, the
   base is the policy's base, and the author is the seat's name. The
   doors on that row are the seat's own three.
4. The answer carries the bench (R-12.29): the engine made the
   worktree with the rig's `prepare`, on the change's branch. The
   answer names the orientation document when the worktree holds
   one, and says what submit means here from the `repo_policy` row.
   When the change has a round behind it, the answer also carries
   `feedback` (R-12.31): the pull request's state, and one finding
   for each failed step, each red status and each review comment.
5. The session reads the orientation, reads the task, and reads the
   files the task names. It edits with `bench.edit`. Each call goes
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
   the finding in the sit's own `feedback`. When the work is done, a
   person completes the task.

## Before the first firing

1. The `bench` mcp_server row is live, and its powers list the five
   bench tokens with their constraints.
2. A `repo_policy` row is active for the repository, with
   `enrolled_at` set. The engine sent the rig the clone, the deny
   list and the land block.
3. A `task_list` row exists for this seat, and its id is in the
   scope's `task` filter.
4. The seat exists and is active, with the scope above and
   `held_for` naming the model the Routine runs.
5. `offer_key` has been invoked, and the key is in the instructions.
6. `link` has been invoked on the schedule row with the fire URL and
   the token, and the row is `live`.
7. The repository holds `docs/orientation.md`, or the person accepts
   the one-sentence default that says what submit means.
8. One task is in the list, and it asks for something small. The
   first firing is watched by a person, who reads the sitting row,
   the task row, the change row and the pull request afterwards.

## To pause

Park the seat. Retire the `repo_policy` row to take the repository
from every seat at once: the rig unenrolls it, and the mirror stops.
An empty task list also pauses the seat: a firing with no open task
answers no change and no bench, and it costs one sitting.
