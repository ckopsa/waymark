# Project Instructions for AI Agents

This file provides instructions and context for AI coding agents working on this project.

## Build & Test

Tests run in **CI, not locally**: the GitHub Actions pipeline
(`.github/workflows/tests.yml`) shards `test10`, `test-queue`,
`test-calendar` and `test-factory` across runners on every push — push your branch and
read the `gate` check. The matching `make` targets now just point you
there (they print the by-hand `clojure -M:test` command if you insist
on running a suite locally). Don't burn local cycles standing up
Postgres to run the suites; let CI be the gate.

The one thing worth running locally is the fast, no-database
declaration gate:

```bash
make check-queue    # declaration-time checks + usability warnings (no DB)
make check-factory  # the same, for factory10's two kinds
```

The suites that do need the dockerized Postgres on `:5433`
(`make db10`) are the ones CI stands that database up for.

## Architecture Overview

`waymark10/` is the framework: declarative resource definitions (state
machines with guarded transitions), from which routing, serialization,
validation, authorization, live events, documentation, and the conformance
suite are mechanically projected. The application directories
(`mealplan10/`, `choreplan10/`, `dayplan10/`, `workqueue10/`,
`calendar10/`, `factory10/`) are declarations driving that engine. Start with
`README.md`, then `docs/waymark10-design.md` and
`docs/waymark10-vocabulary.md`.

## Conventions & Patterns

- REPL entry point: `waymark10.dev/scratch!`
- Declarations are data; prefer growing the framework over app-local
  workarounds.


## Pull Requests

Agent-opened PRs get **auto-merge enabled at open time** (owner's
standing authorization, 2026-08-27): open the PR, enable auto-merge
(repository default merge method), and let the required `gate` check
do the deciding. `gate` lives in `tests.yml`, it aggregates every
suite, and a first `changes` job in that same workflow decides from
the diff whether the suites run at all — so on a codeless diff `gate`
reports green within seconds and GitHub refuses to arm auto-merge on
an already-clean PR; merging directly is then the same authorized
decision, not a new one. (There is no longer a second workflow
answering for those diffs: a mirror gate could report green on a
MIXED diff before the real suites finished — waymark-a7t, PR #29.)
A red `gate` blocks the merge; fix and push rather than waiting.

Otherwise, do not commit or push unless the task or the user asks; at
handoff, report changed files, validation, and suggested next commands.

## Issue Tracking: tickets, in waymark

Work is tracked as `ticket` rows in the engine itself
(`factory10/src/factory10/resources/ticket.clj`, spec
`docs/spec-ticket.md`), at `https://work.kopsa.info/api/tickets` or
through the connector's `waymark_query` on `ticket`. The
beads tracker this replaced was imported on 2026-09-26 — every issue
became a ticket carrying its old id in `bead_id` (filterable:
`?bead_id=waymark-xyz&state=`), so a `waymark-…` id quoted in a
comment or doc is still findable. Beads, `bd` and `.beads/` are gone;
do not reinstall them.

A ticket is born a `draft`; a person grooms it into the queue. An
agent reaches tickets the way it reaches any kind: mint a bearer
(`scripts/agent-token.sh --agent claude work`), and if the kind 404s,
file an `approval_request` naming `ticket` and exactly the actions the
task needs, then send the approved grant as `X-Waymark-Grant`.

Standing knowledge that used to live in `bd remember` is in
`docs/agent-notes.md` — read it before framework work.
