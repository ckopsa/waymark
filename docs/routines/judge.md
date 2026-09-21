# Routine: judge

A judge on any kind is one row and one seat. The judgment row holds
the subject kind, the queue, the verdict names with a sentence for
each name, and the remedy ceiling (docs/spec-seat.md § 20, R-13.1 to
R-13.9). The seat names that row, and the engine makes the queue, the
vocabulary and the guards from it. Nobody writes a door, and nobody
makes a deployment. This file opens a judge in three steps. The worked
example is a judge on the red `ci_run` rows, because that queue is the
one the house already knows.

This file holds what is different from the CI classifier. The chair
key, the fire link, the seat's place, the environment and the Stop
hook are the same, and docs/routines/ci-classifier.md says them once.

Written in ASD-STE100 Simplified Technical English.

## Step 1: the judgment row

Make the row with `create`. The row is a draft, and a draft judges
nothing.

```json
{
  "kind": "judgment",
  "action": "create",
  "input": {
    "name": "ci-verdict",
    "subject_kind": "ci_run",
    "queue": {"state": "red"},
    "verdicts": [
      {"name": "infra",
       "sentence": "The cache, the runner or the network broke. The change is not the cause."},
      {"name": "base_red",
       "sentence": "The base branch already failed this way before the change. Fix the base, not the change."},
      {"name": "this_change",
       "sentence": "The change itself is wrong. Say what to fix, in one line."}
    ],
    "remedy_max": 240,
    "notes": "The first judge on a factory kind. The verdict is the product: no door moves the run."
  }
}
```

Read the three parts of the row.

- `queue` is the filter that makes the work. `{"state": "red"}` is a
  plain equality on one field of `ci_run`. An empty map is the whole
  kind.
- `verdicts` is the vocabulary. The name is what the seat says. The
  sentence says when to say it. The sit gives the seat both, so the
  charter does not repeat them.
- `remedy_max` is the ceiling on one remedy, in characters. 240 is the
  default, and one sentence fits in it.

This judgment names no `consequence`. The verdict is the product. A
judgment with a consequence names one door of the subject kind, and
the engine walks that door on the subject after each verdict (R-13.5).
The seat never sees that door.

Then promote the row. The declaration checks run here.

```json
{
  "kind": "judgment",
  "action": "promote",
  "id": "<the judgment's id>",
  "input": {}
}
```

`promote` is a person's door. It refuses a judgment whose subject kind
the engine does not serve, whose verdict names repeat, whose queue
names a field that is not filterable, or whose consequence is not a
door of the subject kind. The refusal names the check. A judgment that
fails a check does not project, so no seat can walk it.

## Step 2: the seat

Create the seat with these values. A restate changes the next firing
with no change to the Routine.

| field | value | why |
|---|---|---|
| name | `ci-verdict-judge` | one spelling |
| mode | `fired` | the Routine and the wake open the sittings |
| walk | `ci_run` | the judgment's subject kind. A seat that names a judgment must walk that kind (R-13.4) |
| judgment | the id of the `ci-verdict` row | the queue, the vocabulary and the ceiling come from that row, and not from this one |
| rows_per_firing | 5 | one verdict is one log tail and one sentence. Five of them is a small sitting |
| held_for | the model the Routine runs | the seat's place on the ladder. The sit frames the whole question, and the vocabulary is the whole answer |
| cadence_seconds | 3600 | the wake on `ci_run` create fires it sooner (R-12.22) |
| fire_interval_seconds | 300 | a red pipeline mints many runs in one minute; the damper holds them to one firing |
| sitting_budget_tokens | 200000 | five log tails, at 20,000 characters each |
| budget_usd_per_week | 5 | the fuel |
| charter | the text under "The charter" | the residual |
| scope | the entries under "The scope" | the authority |

The judgment makes the queue, and the seat does not. The seat walks
the `ci_run` rows under `{"state": "red"}`, minus the runs that carry
a standing verdict under this judgment. A run leaves the queue at the
moment the seat judges it. The sit answers those rows as it answers
every walk (R-12.28), and it answers one more block, `judgment`: the
verdict names, the sentence of each name, and `remedy_max`.

## The scope

The `ci_run` entry is read-only, with no action at all: it lets the
seat read the run it judges. The `verdict` entry carries one door,
`judge`. There is no third entry.

```json
[
  {"kind": "ci_run", "actions": []},
  {"kind": "verdict", "actions": ["judge"]}
]
```

The scope does not name `correct`, and it must not. A correction is a
person's work (R-13.3). An agent that could correct its own verdict
could answer its own question, and the count would measure nothing.

## The charter

At most 1200 characters. The charter is judgment, not procedure. It
does not list the verdict names, because the sit carries them.

```
You judge rows under one judgment. The sit names that judgment. It
gives you the verdict names and one sentence for each name.

Read the row first. Read the end of the log when the row carries one.

Pick one verdict name. Pick the name whose sentence is true of this
row.

Write one remedy sentence with the verdict. Say the ACT, and not the
diagnosis. Say what somebody must do next, in one line.

Judge each row one time.

Do not guess. When the row does not say which verdict is true, leave
the row in the queue. Say why in the close of the sitting.

Never correct a verdict. A person does that.
```

## The instructions

This seat uses the "One Routine for each model" way of
docs/routines/ci-classifier.md. That file gives the four steps for the
model row: mint the chair key, invoke `offer_key` on the MODEL row,
make the one Routine with its prompt, and invoke `link` with the fire
URL and the token. Do those steps one time for a model, and then do
this one step for this seat.

Invoke `restate` on the seat `ci-verdict-judge` with the field
`instructions`, which holds at most 2000 characters. Write this text
in it:

```
You sit in the seat `ci-verdict-judge`. The sit answers the charter,
your rows, and a judgment block: the verdict names, one sentence for
each name, and the longest remedy you can write.

For each row in the walk, invoke `judge` on the kind `verdict`. Give
the judgment, the subject kind, the subject id, one verdict name from
the sit's judgment block, and one remedy sentence. Then stop.

The walk carries the row's own fields; when it does not carry the
field you must read, read the row with waymark_get first. Do not call
discover, schema, query or powers; a refusal names its own remedy.
When the seat says halted or parked, say why and stop.

Leave a row in the queue when it does not say which verdict is true.
Stop when every row is done.

If a routine-fire-payload block names a row id, walk that row and stop.

When the Stop hook asks you to close the sitting, make that one call
with the numbers it gives, then stop.
```

Leave the seat's schedule with no link. A schedule with no link of its
own fires through the chair's link (R-12.36). Nothing else goes in the
instructions (R-12.10).

One verdict looks like this:

```json
{
  "kind": "verdict",
  "action": "judge",
  "input": {
    "judgment": "<the judgment's id>",
    "subject_kind": "ci_run",
    "subject_id": "<the run's id>",
    "verdict": "base_red",
    "remedy": "Fix the base branch: the same test failed on main before this change."
  }
}
```

The engine writes `said_by`. The door refuses a name outside the
vocabulary, a remedy past the ceiling, an empty remedy, and a second
verdict on a run that already carries one under this judgment
(R-13.2).

## Step 3: the correction

A person who disagrees judges again, and the second verdict carries
`corrects`.

```json
{
  "kind": "verdict",
  "action": "judge",
  "input": {
    "judgment": "<the judgment's id>",
    "subject_kind": "ci_run",
    "subject_id": "<the run's id>",
    "verdict": "this_change",
    "remedy": "Fix the change: it renamed the column and left one query behind.",
    "corrects": "<the first verdict's id>"
  }
}
```

The correction is a second row. It cites the first row. The engine
moves the first row to `overruled` with its own hand, and the first
row stays on the record. An agent that carries `corrects` is refused.

The count is the measurement. The ledger counts the corrections for
each seat and for each judgment (R-13.3). Read the seat's ledger
against that count, and step the model down when the corrections stop.
A correction fires no consequence.

## A judgment on the engine's own rows

The subject can be a row of the engine itself. A judgment can name
`sitting`, `grant` or `seat` as its subject kind, and the audit brief
of waymark-fp62.8 is that judgment on `sitting` (R-13.7).

## What ci_run keeps

The `ci_run` kind keeps its three classify doors, its stamp door and
its reclassify door (R-13.8). Its verdict moves the run's own state,
and the GitHub source's label push hangs on that state. That is the
one special case. The judgment above is the worked example of this
routine, and it does not retire those doors. Two judgments can stand
on one kind, and each holds its own verdict on one row (R-13.6).

## Before the first firing

1. The judgment row stands at `promoted`.
2. The queue holds rows: the subject kind has rows under the
   judgment's filter that carry no standing verdict.
3. The seat exists and is active, with the scope above, the judgment
   in its `judgment` field, and `held_for` naming the model the
   Routine runs.
4. `offer_key` and `link` have been invoked on the model row
   (ci-classifier.md, "One Routine for each model"), and the seat
   carries its `instructions`.
5. The environment carries the URL and the key, or it carries nothing
   and the hook holds the stop (inbox-clerk.md, "The environment").
6. A person watches the first firing. The person reads the sitting
   row, the verdicts it wrote, and the corrections that followed them.

## To pause

Park the seat. The next firing meets the wall, says so, and stops. To
stop the judge for good, supersede the judgment: the queue projects no
more, and the verdicts stay on the record.
