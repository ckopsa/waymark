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

`bash scripts/ns-size-check.sh` (run by the `changes` job) fails a
`.clj` under a src tree past 100 KB unless `ci/ns-size-grandfather.edn`
records it, and a recorded one that grows past its entry.

`bash scripts/carve-alias-check.sh` (run by the `changes` job) counts
each src file's carve aliases, `(def x sub/x)` or `(def x @#'sub/x)`
into its own sub-directory, and fails one past its entry in
`ci/carve-alias-grandfather.edn` or a new one off the list. An alias
ending `; api` (a public name offered on purpose, never a `@#'`) or
`; test: <test ns>` is not counted.

The suites that do need the dockerized Postgres on `:5433`
(`make db10`) are the ones CI stands that database up for.

## Architecture Overview

`waymark10/` is the framework: declarative resource definitions (state
machines with guarded transitions), from which routing, serialization,
validation, authorization, live events, documentation, and the conformance
suite are mechanically projected. The other top-level directories are
declarations driving that engine, or the kit around it:

- `waymark10/` — the engine itself: the declaration language, the server
  (`src/waymark10/server/`), the store and the conformance suite.
- `workqueue10/` — the household apps' kinds and sources: its `src/` holds
  the `workqueue10`, `mealplan10`, `choreplan10` and `dayplan10`
  namespaces (these are namespaces here, not top-level directories),
  with fixture orgs.
- `calendar10/` — the calendar and time kinds.
- `factory10/` — the factory's two kinds and their law (`make check-factory`).
- `factorykit/` — the kit an org's factory is built from, with fixture
  orgs that exercise its checks.
- `localfire/` — firing seats locally (see `localfire/README.md`).
- `orgkit/` — the template a new org is started from.
- `orgs/*` — the orgs themselves (`example`, `vanilla_plus`,
  `dress_rehearsal`, `landscape_rehearsal`, `week_fixture`), each its own
  `deps.edn` of declarations.
- `seat/` — the files a seat's session place is copied from each hour
  (its `CLAUDE.md`, settings and hooks).
- `selfhost/` — self-hosting: `compose.yml`, `.env.example`, a CI walk
  script and `repo-kit/` for a hosted repository.
- `docs/` — the design, the vocabulary, the agent notes and the specs.

## Reading order for agents

1. `README.md`
2. `docs/waymark10-design.md` — skim it.
3. `docs/waymark10-vocabulary.md`
4. `docs/agent-notes.md`
5. The spec for the kind you touch (`docs/spec-<kind>.md`).

`docs/` holds dozens of specs. Read one when its kind is in your ticket;
do not read them all.

## Where the mass is

`waymark10/src/waymark10/server/{seats,mcp,invoke,grants,held_calls,router}.clj`
are each over 150 KB. Do not read one whole: find the function first
(bench find / symbols, or `grep -n`), then read only that window.

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
