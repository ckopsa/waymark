# Project Instructions for AI Agents

This file provides instructions and context for AI coding agents working on this project.

## Build & Test

Everything runs against one dockerized Postgres on `:5433`
(`make db10` bootstraps the container and databases).

```bash
make test10                # waymark10 framework tests
make test-mealplan10       # mealplan10 conformance + the family-week story
make test-eveningplan10    # eveningplan10 conformance suite
make check10               # declaration-time checks + usability warnings (no database)
```

## Architecture Overview

`waymark10/` is the framework: declarative resource definitions (state
machines with guarded transitions), from which routing, serialization,
validation, authorization, live events, documentation, and the conformance
suite are mechanically projected. The application directories
(`mealplan10/`, `eveningplan10/`, `choreplan10/`, `workqueue10/`,
`calendar10/`) are declarations driving that engine. Start with
`README.md`, then `docs/waymark10-design.md` and
`docs/waymark10-vocabulary.md`.

## Conventions & Patterns

- REPL entry point: `waymark10.dev/scratch!`
- Declarations are data; prefer growing the framework over app-local
  workarounds.
