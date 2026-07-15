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

Earlier generations (Python, waymark v0.1 through waymark9) live on the
`history` branch; `main` carries the standalone waymark7 package.

## The dogfood apps

Three applications drive the framework, each a directory of declarations:

- **`mealplan10/`** — the family meal planner (Tue→Tue themed weeks,
  grocery lists, prep tasks, rotations).
- **`eveningplan10/`** — evening plans.
- **`paydesk/`** — the assignment/employee/fund/team mirror over an external
  warehouse: discovery, pull-through sync, push-on-write, conflict states,
  and the assignment worksheet (download a filtered subset as xlsx,
  hand-edit, upload → staged worksheet row → apply).

## Quickstart

Everything runs against one dockerized Postgres on `:5433`
(`make db10` bootstraps the container and databases).

```bash
make test10                # waymark10 framework tests
make test-mealplan10       # mealplan10 conformance + the family-week story
make test-eveningplan10    # eveningplan10 conformance suite
make test-paydesk              # paydesk conformance suite

make check10               # declaration-time checks + usability warnings
make check-eveningplan10   #   (no database; also check-paydesk)

make dev10                 # serve mealplan10 on :8010 (UI at /api/-/ui)
make dev-eveningplan10     # :8011
make dev-paydesk               # :8012

make migrate10             # print the schema plan; APPLY=1 executes
```

REPL entry point: `waymark10.dev/scratch!` (see
[`docs/waymark10-vocabulary.md`](docs/waymark10-vocabulary.md)).
