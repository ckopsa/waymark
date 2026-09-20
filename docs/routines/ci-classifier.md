# Routine: ci-classifier

The CI failure classifier's driver: a Claude Routine with the engine's
connector attached. The seat walks `ci_run` rows — the red check runs
the GitHub source mints — and writes one of three verdicts on each
one (docs/spec-seat.md R-12.28, factory10/src/factory10/resources/
ci_run.clj). It is a READ-AND-LABEL seat: it reads the end of a log
and writes a verdict and a sentence. It changes no code and it opens
no worktree.

This file holds what is different from the inbox clerk. The key, the
fire link, the seat's place, the environment and the Stop hook are the
same, and docs/routines/inbox-clerk.md says them once.

Written in ASD-STE100 Simplified Technical English.

## The seat row

Create the seat with these values. A restate changes the next firing
with no change to the Routine.

| field | value | why |
|---|---|---|
| name | `ci-classifier` | one spelling |
| mode | `fired` | the Routine and the wake open the sittings |
| walk | `ci_run` | the queue: the red runs nobody has classified. The kind's default filter is `state=red`, so the collection a firing opens is exactly that work |
| rows_per_firing | 5 | one verdict is one log tail and two short sentences. Five of them is a small sitting, and a sixth red run waits one cadence |
| held_for | the model the Routine runs | the seat's place on the ladder. This seat starts low: the envelope frames the whole question, and the three doors are the whole answer |
| cadence_seconds | 3600 | the wake on `ci_run` create fires it sooner (R-12.22) |
| wake_on | not set | a walk seat with no `wake_on` wakes on the walk kind's `create`, so a new red run starts a sitting (R-12.22) |
| fire_interval_seconds | 300 | a red pipeline mints many runs in one minute; the damper holds them to one firing, and that firing walks five |
| sitting_budget_tokens | 200000 | five log tails, at 20,000 characters each |
| budget_usd_per_week | 5 | the fuel |
| charter | the text under "The charter" | the residual |
| scope | the entries under "The scope" | the authority |

The queue's own sort decides which runs the firing gets. The `ci_run`
kind sorts by `started_at`, oldest first: the house answers its red
builds in the order they broke.

## The scope

The `ci_run` entry names the queue the seat walks and the three doors
it writes with. The `change` entry is read-only, with no action at
all: it lets the seat read the change a run ran on. There is no third
entry.

```json
[
  {"kind": "ci_run", "actions": ["classify_infra",
                                 "classify_base_red",
                                 "classify_this_change"]},
  {"kind": "change", "actions": []}
]
```

Each other door on a `ci_run` is the person's or the mirror's, and the
seat does not get it:

- `reclassify` is the person's correction. It is not grantable. A
  scope that named it would be refused, and the wall would refuse the
  hand as well.
- `stamp_label` is the mirror's record of its own push.
- `supersede` is the mirror's door for a run whose commit is gone.

The seat needs no `sitting` entry, no `seat` entry and no bench power.
A seat with no bench power opens no worktree, so the sit answers no
bench and asks the rig for nothing.

## The seat holds no GitHub power

The label is the mirror's job, not the model's. A classified run earns
one label on its pull request: `ci:infra`, `ci:base-red` or
`ci:this-change`. The seat never holds a GitHub power. The source
reads the verdict, pushes the label, and then walks `stamp_label` on
the row. The seat does not see that door and it does not see the
label.

## The person's door is the measurement

`reclassify` is the person's door and nobody else's. A
reclassification is a CORRECTION: it is how the house counts what the
classifier got wrong, and the count per transition is what decides
whether a cheaper model holds this seat. An agent that could correct
its own verdict could answer its own question, and the count would
measure nothing. Read the seat's ledger against that count, and step
the model down when the corrections stop.

## A run can leave the queue with no verdict

A run ran on one commit. When the change's head moves, that commit is
gone and there is nothing left to classify. The source then walks the
`supersede` door and the row leaves the queue. The seat does nothing
about this, and it must not wait for a run that is no longer there.

## The charter

At most 1200 characters. The charter is judgment, not procedure.

```
You classify red check runs. Read the end of the log first. It is on
the run, in log_excerpt. Read the run with waymark_get when the walk
does not carry it.

Say which of three things went wrong.

Infrastructure: the cache, the runner or the network broke. The change
is not the cause.

Base was red: the base branch already failed this way before the
change. Fix the base, not the change.

This change: the change itself is wrong.

Write one remedy sentence with each verdict. Say the ACT and not the
diagnosis. Say what somebody must do next, in one line.

Classify each red run one time.

Do not guess. When the log does not say which of the three it is,
leave the run red. Say why in the close of the sitting.

Never correct a verdict. A person does that.
```

## The instructions

```
Your seat key is: <paste the key here>

First, run `echo $CLAUDE_CODE_SESSION_ID` and call waymark_sit once with
that key and that value as `session`. Then you sit in the seat
`ci-classifier`. The sit answers the charter and your rows, each with
its doors and the input each door takes.

For each row, read the end of the log and invoke one classify door with
your remedy sentence. The walk carries the run's own fields; when it
does not carry log_excerpt, read the run with waymark_get first. Do not
call discover, schema, query or powers; a refusal names its own remedy.
When the seat says halted or parked, say why and stop.

Leave a run red when the log does not say which of the three it is.
Stop when every row is done.

If a routine-fire-payload block names a row id, walk that row and stop.

When the Stop hook asks you to close the sitting, make that one call
with the numbers it gives, then stop.
```

Nothing else goes in the instructions (R-12.10).

## What one firing does

1. The connector initializes, and the session calls `waymark_sit`
   with the key. The engine binds the session to the seat.
2. The sit's answer carries the charter and up to five `ci_run` rows
   (R-12.28). The rows are the red runs under the kind's default
   filter, oldest first. A run the head moved under is superseded, so
   it is not one of them.
3. Each row carries its summary projection and its doors. The doors
   are the seat's own three, because the engine read the rows as the
   sitter under the seat's grant. `reclassify`, `stamp_label` and
   `supersede` are absent from the row, not refused on it.
4. The answer carries no bench and no change. The seat holds no bench
   power, so no worktree is made and the rig is asked for nothing.
5. The session reads the end of each log. `log_excerpt` is the last
   200 lines of the failed job, as the source read them at mint time.
   The walk's grid projection does not carry that field, so the
   session reads the run with `waymark_get` and gets it whole. When
   the log could not be read at all, `log_note` says why, and it IS on
   the walk: the excerpt is empty and the seat does not spend a read
   to learn that.
6. For each run the session invokes one classify door with the remedy
   sentence. The door refuses with no sentence, and the refusal names
   the field. The row moves to `classified` and leaves the queue.
7. The session stops and says in one line how many runs it classified
   and how many it left red. The Stop hook sums the transcript and
   closes the sitting, as the clerk's does.
8. The next pass of the GitHub source reads each classified run,
   pushes its one label to the pull request, and walks `stamp_label`.
   The push is within one cadence of the verdict.
9. A person who disagrees taps `reclassify` on the row. The
   correction stands on the record beside the verdict it overrules,
   and the ledger counts it.

## Before the first firing

1. The GitHub source has run one pass and the queue holds red runs.
2. The seat exists and is active, with the scope above and `held_for`
   naming the model the Routine runs.
3. `offer_key` has been invoked, and the key is in the instructions.
4. `link` has been invoked on the schedule row with the fire URL and
   the token, and the row is `live`.
5. The environment carries the URL and the key, or it carries nothing
   and the hook holds the stop (inbox-clerk.md, "The environment").
6. The first firing is watched by a person, who reads the sitting row,
   the runs it classified and the labels the next source pass pushed.
   This seat is the first cost-per-outcome number the house has: one
   sitting's bill against the verdicts it wrote and the corrections
   that followed them.

## To pause

Park the seat. The next firing meets the wall, says so, and stops.
Pausing the Routine as well saves the wake. To resume, unpark. An
empty queue also pauses the seat: a firing with no red run answers no
rows, and it costs one sitting.
