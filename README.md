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

Two framework kinds hold judgment as data, so a person opens a judge on
any kind with one row and one seat and no deployment
([`docs/spec-seat.md`](docs/spec-seat.md) § 20, R-13.1 to R-13.9):

| kind | what it is | the walk |
| --- | --- | --- |
| `judgment` | one judge, declared: the subject kind, the queue as a filter on it, the verdict names with a sentence for each, the remedy ceiling, and the consequence door the engine walks on the subject | draft → promoted, by `promote`, where the declaration checks run; promoted → superseded, a person's door, which can name the successor |
| `verdict` | one row about one row: the judgment, the subject, one verdict name, the remedy in one sentence, and who said it | said → overruled; the seat's create door is `judge`, one verdict for each subject under each judgment; a person judges again with `corrects`, and the engine overrules the first row, which stays |

Earlier generations (Python, waymark v0.1 through waymark9) are not part
of this repository; `main`'s history records the Clojure lineage.

## The application

**`workqueue10/`** is the household app — one engine serving every
domain of family life, each a module of declarations under its
`src/`: the unified work queue (`workqueue10.*` — tasks from every
authority, media, the household's conversations as addresses
(`thread`), the breaker panel, the dwelling kinds), the meal
plan (`mealplan10.*` — Tue→Tue themed weeks, grocery lists, prep
tasks, rotations, the pantry), chores (`choreplan10.*`), and the day
plan (`dayplan10.*`). The calendar (`calendar10/`) stays its own
module — a writable domain the queue and the meal plan both cite.

**`factory10/`** is the software factory's module, beside the
household rather than inside it (waymark-fp62.6.2). It holds the
factory's ask, and two mirrors of GitHub that a source writes and a
person reads:

| kind | what it is | the walk |
| --- | --- | --- |
| `ticket` | one ask of the factory — title, the how in `detail`, type, priority, its parent, what it waits on, what it was found in ([`docs/spec-ticket.md`](docs/spec-ticket.md)). The tracker: a person or a seat files one, the code seat walks the ready ones | open → blocked (`block`, the whole set stated), blocked → open; open → deferred (`defer`, until a day), deferred → open; open → done (`complete`) or dropped (`drop`), each demanding one sentence, and a parent only after its children; done or dropped → open by `reopen`, a person's door and nobody else's |
| `change` | one pull request — repository, number, branches, head sha, counts, labels, review state | open → merged, open → closed, closed → open; every door is the mirror's |
| `ci_run` | one red check run, with the last 200 lines of the failed job — or, when the log could not be read, `log_note` saying why | red → classified, by `classify_infra`, `classify_base_red` or `classify_this_change`, each demanding the remedy in one sentence; classified → reclassified, a person's door and nobody else's; red → superseded, the mirror's `supersede` for a run whose commit is not the head any more |

It boots alone (`make check-factory`, `cd factory10 && clojure -M:test`)
and beside the household: `workqueue10.main` folds its kinds into the
household registry when `FACTORY10=1`, so this repository's own gate is
the classifier's first proving ground.

**`localfire/`** is the local fire server, so a seat can also fire on a
machine of the house. The engine fires a seat by one POST to a
`fire_url` with a bearer token, and this server answers that wire the
way a Claude Routine does. A person invokes `link` on the model row with
the server's URL, and the same seat, the same key and the same sitting
then run a headless Claude Code on the LAN. The engine does not change.
Requirements:
[`docs/spec-local-fire.md`](docs/spec-local-fire.md).

## Quickstart

Everything runs against one dockerized Postgres on `:5433`
(`make db10` bootstraps the container and databases).

```bash
make test10                # waymark10 framework tests
make test-queue            # the household suite: queue + chores + meals + day plan
make test-calendar         # calendar transport tests

make check-queue           # declaration-time checks + usability warnings (no database)
make check-factory         # the same, for factory10's kinds
make test-factory          # the factory's suite (no database, no network)
make check-localfire       # the local fire server's suite (no database, loopback only)

make dev-queue             # serve the household engine on :8014 (UI at /api/-/ui)

make migrate-queue         # print the schema plan; APPLY=1 executes
```

REPL entry point: `waymark10.dev/scratch!` (see
[`docs/waymark10-vocabulary.md`](docs/waymark10-vocabulary.md)).
