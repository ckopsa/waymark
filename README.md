# Waymark 10

**An affordance-oriented hypermedia format and server framework for mixed
human/agent clients — the Clojure generation.**

Waymark applications are **declarative resource definitions** — state
machines with guarded transitions. Everything else is a mechanical
projection of those definitions: routing, serialization, validation, the
authorization surface, live events, documentation, the agent tool surface,
and the conformance suite. The one-sentence pitch: **the advertised
affordances and the enforced rules cannot drift**, because one guard is both
the advertisement (`unavailable.reason`) and the enforcement (the 409
detail), and the conformance suite proves it by invoking everything.

**`waymark10/`** is the current head of the lineage (wire format `"10"`, a
clean break from the Python generations): the law is a form — the tree the
reviewer diffs, the fingerprint stores, the wire carries, and the
interpreter evaluates are one value. Engine, conformance library, the
affordance-following client (`waymark10.client`), the CLI
(`clojure -M:cli`), and the envelope-driven generic UI (`GET /api/-/ui`)
live in `waymark10/`. Design record with the 9→10 wire divergence table:
[`docs/waymark10-design.md`](docs/waymark10-design.md); the authoring
vocabulary (all three dialects):
[`docs/waymark10-vocabulary.md`](docs/waymark10-vocabulary.md).

Earlier generations (Python, waymark v0.1 through waymark9) are not part
of this repository; `main`'s history records the Clojure lineage.

## The application

**`workqueue10/`** is the household app — one engine serving every
domain of family life, each a module of declarations under its
`src/`: the unified work queue (`workqueue10.*` — tasks from every
authority, media, the household's conversations as addresses
(`thread`), the breaker panel, the dwelling kinds), the meal
plan (`mealplan10.*` — Tue→Tue themed weeks, grocery lists, prep
tasks, rotations, the pantry), chores (`choreplan10.*`), and evening
plans (`eveningplan10.*`). The calendar (`calendar10/`) stays its own
module — a writable domain the queue and the meal plan both cite.

## Quickstart

Everything runs against one dockerized Postgres on `:5433`
(`make db10` bootstraps the container and databases).

```bash
make test10                # waymark10 framework tests
make test-queue            # the household suite: queue + chores + meals + evenings
make test-calendar         # calendar transport tests

make check-queue           # declaration-time checks + usability warnings (no database)

make dev-queue             # serve the household engine on :8014 (UI at /api/-/ui)

make migrate-queue         # print the schema plan; APPLY=1 executes
```

REPL entry point: `waymark10.dev/scratch!` (see
[`docs/waymark10-vocabulary.md`](docs/waymark10-vocabulary.md)).
