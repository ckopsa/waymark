# Routine: code-seat

The code seat's driver: a Claude Routine with the engine's connector
attached. The seat walks `change` rows and works each one on the
bench (docs/spec-seat.md R-12.29, docs/spec-mcp-servers.md § 4).
This file holds what is different from the inbox clerk. The key, the
fire link, the seat's place, the environment and the Stop hook are
the same, and docs/routines/inbox-clerk.md says them once.

Written in ASD-STE100 Simplified Technical English.

## The seat row

Create the seat with these values. A restate changes the next firing
with no change to the Routine.

| field | value | why |
|---|---|---|
| name | `code-seat` | one spelling |
| mode | `fired` | the Routine and the wake open the sittings |
| walk | `change` | the queue: open and submitted pull requests of the enrolled repositories, oldest first |
| rows_per_firing | 1 | one change is one worktree, one branch and one round; a second change in the same firing would share the bill and the context |
| held_for | the model the Routine runs | the seat's place on the ladder |
| cadence_seconds | 3600 | the wake on `change` create fires it sooner (R-12.22) |
| sitting_budget_tokens | 400000 | a code round reads files; the clerk's ceiling is too small |
| budget_usd_per_week | the person's number | the fuel |
| charter | the text under "The charter" | the residual |
| scope | the entries under "The scope" | the authority |

## The scope

Each bench power is one entry with an empty `actions` list and a
`filter` that names the repository. Write one entry for each
repository the seat works. The `change` entry names the three doors
the seat uses. Each other door on the row is the mirror's or a
person's (`merge`, `close`, `reopen`, `observe`, `unstick`), and the
seat does not get it.

```json
[
  {"kind": "change", "actions": ["submit", "stall", "discard"]},
  {"kind": "bench.find",     "actions": [], "filter": {"repo": "ckopsa/waymark"}},
  {"kind": "bench.read",     "actions": [], "filter": {"repo": "ckopsa/waymark"}},
  {"kind": "bench.edit",     "actions": [], "filter": {"repo": "ckopsa/waymark"}},
  {"kind": "bench.pull",     "actions": [], "filter": {"repo": "ckopsa/waymark"}},
  {"kind": "bench.feedback", "actions": [], "filter": {"repo": "ckopsa/waymark"}}
]
```

A `path` in a filter narrows the seat further (R-12.30). A seat that
must not touch the workflows adds `"path": "!.github/*"` to no entry:
the deny list on the `repo_policy` row already keeps those paths
from every seat. A seat that works one directory only puts that
directory's glob in `path` on its find, read and edit entries.

The seat needs no `sitting` entry and no `seat` entry. The sit binds
the session, and the Stop hook closes the sitting with the key.

## The charter

At most 1200 characters. The charter is judgment, not procedure. The
procedure is in the sit's answer: the orientation document, the
`submit_means` sentence, and the `feedback` block.

```
You fix pull requests that a person opened, and you finish changes a
person asked for. Read the feedback first: a red check, a review that
asks for a change, or a pull request with nothing wrong. When nothing
is wrong, stall the change with the sentence "Nothing to do" and stop.
When a check is red, read the failed step's log, find the cause in
the files it names, and change the least that makes it green. When a
reviewer asked for a change, make that change and no other. Do not
change a test to make it pass. Do not change files the request does
not name. Submit one time, with one sentence that says what you
changed and why. When the cause is not in this repository, or the fix
is larger than the ceiling, stall the change and say why in one
sentence. A person reads every stall.
```

## The instructions

```
Your seat key is: <paste the key here>

First, run `echo $CLAUDE_CODE_SESSION_ID` and call waymark_sit once with
that key and that value as `session`. Then you sit in the seat
`code-seat`. The sit answers the charter, one change row with its
doors, and the bench: the worktree, the orientation path, what submit
means here, and the feedback of the last round. Read the orientation
document with waymark_power bench.read before you read anything else.

Work the change with waymark_power: bench.find and bench.read to read,
bench.edit to change a file, bench.pull when the bench says the branch
is behind. Then invoke the door the charter chooses on the change row:
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
2. The sit's answer carries the charter and one `change` row with
   its doors (R-12.28). It also carries the bench (R-12.29): the
   engine made the worktree with the rig's `prepare`, on the
   change's head branch, or on the policy's branch pattern with the
   change's id in place of the `*`. The answer names the orientation
   document when the worktree holds one, and says what submit means
   here from the `repo_policy` row. When the change has a pull
   request, the answer also carries `feedback` (R-12.31): the pull
   request's state, and one finding for each failed step, each red
   status and each review comment.
3. The session reads the orientation, reads the findings, and reads
   the files the findings name. It edits with `bench.edit`. Each call
   goes through the seat's grant: a path the `repo_policy` denies is
   refused by the rig, and a repository outside the filter is refused
   by the engine before the rig sees it (R-12.30).
4. The session invokes `submit` on the change row with one sentence.
   The engine commits the worktree with that sentence and two
   trailers that name the seat and the sitting, pushes the branch,
   and the rig opens the pull request when the policy says so. The
   row moves to `submitted` and its `rounds` count grows by one. At
   the policy's `rounds_per_change` the door refuses, and the
   session stalls instead.
5. The session stops. The Stop hook sums the transcript and closes
   the sitting through `POST /api/-/sittings/close`, as the clerk's
   does. The bill of the round is on the sitting row.
6. The mirror follows the pull request. A green check and a merge
   move the row to `merged`. A red check on the new head mints a
   `ci_run` row, and the change's `create` wake fires the seat for
   the next round.

## Before the first firing

1. The `bench` mcp_server row is live, and its powers list the five
   bench tokens with their constraints.
2. A `repo_policy` row is active for the repository, with
   `enrolled_at` set. The engine sent the rig the clone, the deny
   list and the land block.
3. The seat exists and is active, with the scope above and
   `held_for` naming the model the Routine runs.
4. `offer_key` has been invoked, and the key is in the instructions.
5. `link` has been invoked on the schedule row with the fire URL and
   the token, and the row is `live`.
6. The repository holds `docs/orientation.md`, or the person accepts
   the one-sentence default that says what submit means.
7. One open pull request of the repository has a red check or a
   review that asks for a change. The first firing is watched by a
   person, who reads the sitting row, the change row and the pull
   request afterwards.

## To pause

Park the seat. Retire the `repo_policy` row to take the repository
from every seat at once: the rig unenrolls it, and the mirror stops.
