# Adding a resource to mealplan9 — the field guide

What an agent has to know to add a waymark9 resource to this app without
re-deriving it from the framework source. Written after adding
`ingredient`/`product`; every rule below was learned by hitting it.

## Where everything goes

A new resource touches exactly four places:

1. `mealplan9/resources/<kind>.py` — the resource module.
2. `mealplan9/main.py` — add it to the `Engine(resources=[...])` list.
3. Repo-root `conftest.py`, the "waymark9 half" — add it to the
   `waymark9_engine` fixture's resource list, then either
   `w9_conformance_resource(Cls)` (walker reaches every state from the
   declarations alone) or a `@w9_state_factory(Cls)` when states need
   semantic setup, plus `@w9_example_input(Cls, "<action>")` for any
   action whose input needs a *real* id (Refs).
4. `mealplan9/README.md` — the resource roster and, if the AI drives it,
   the "Where the AI sits" workflow list.

Read `resources/grocery_list.py` (parts, acceptance-set guards, derived
rollup) and `resources/plan.py` (Refs, Owns, Related, clock guards,
cascades) before writing anything — most idioms you need already exist in
one of them, and new code should quote them.

## Import-time checks that will refuse your first draft

`Resource.__init_subclass__` runs `checks.run_all`; these are the ones
that actually fired:

- **`Safety(reversible=True)`** requires an *unconditional* transition
  back: `A → B` reversible means some action goes `B → A` with no guards.
  A multi-source action (`from_={A, B}, to=B`) can't claim it — the
  `A → B` leg has no way back. Use `one_way=Acknowledged("…")` instead.
- **`one_way` vs `confirm`**: mutually exclusive by constructor.
  `confirm=True` requires `consequence="…"`. An irreversible, unconfirmed,
  state-*leaving* action needs `one_way`; self-loops don't.
- **Edit-shaped actions**: if an input model's fields mirror Data fields,
  a UsabilityWarning demands `edit=Edit(prefill=(...))`. `Edit` defaults
  `fence=True` (If-Match required) — and **a `ctx.invoke` cascade is not
  exempt from the fence**, so an action a handler invokes on other rows
  should either not be an Edit or declare
  `Edit(..., fence=False, unfenced_reason="…")`.
- **Long text fields**: any string with `max_length ≥ 280` (or unbounded)
  must say what it is: `x-display {"widget": "prose"}` for long-form,
  `{"raw": True}` for machine-shaped text (URLs, receipt ids, keys), or
  `hidden`. Notes fields here use `max_length=2000` + prose.

Fast feedback loop: `uv run python -W error::UserWarning -c "import
mealplan9.main"` surfaces every DefinitionError and UsabilityWarning
without a database.

## Derived fields (`Derived`, `Count`, `Sum`)

- Exactly one of `fn=` / `expr=` / `within=`. Prefer `expr=` (E-trees,
  fingerprintable law) when the grammar can express it; argmax-over-parts
  and similar can't be, so `fn=` lambdas are fine — say why in a comment.
- `over=` names inputs positionally: own Data fields (including other
  derived fields — dependency order is resolved, cycles refused),
  `edge.field("state", where=...)` for Owns/Related children (arrives as
  a list), or `Clock` for now.
- **Clock derivations must be schedulable.** Either the extractable shape
  (`now` compared against exactly one stored date/datetime field) or an
  explicit `flips_at=lambda r: <datetime>` naming the next flip moment.
  `flips_at` must return an *aware datetime* (it's compared against the
  engine's `now`), so a date-based window needs
  `datetime.combine(d + timedelta(...), time.min, tzinfo=timezone.utc)`.
  `engine.tick(now=...)` drives the sweep by hand in tests.
- Handlers must never assign a derived field; create bodies that supply
  one are refused. They filter and sort like any promoted column
  (`filterable(price_is_stale=filterable.Eq)` works).
- `Count(edge, where={...})`/`Sum(edge, of, ...)` are library rollups.
  Declare the edge once at module level (`_products = Owns("product",
  via="ingredient_id")`), reference it from both `Data` and the class's
  `owns = (_products,)`. Rollups are maintained in the same commit as the
  child write — including a child *re-parent*: repointing the FK updates
  both the old and new parent's counts before the transaction's later
  guards run (the absorb-then-retire cascade relies on this).

## Vocab, Refs, guards

- `Vocab[T] = VocabField(...)` is a *list* field carrying its own
  membership filter, GIN index, facet (`facet=Observed(counts=True)`),
  and wire hints — do **not** also list it in `filterable`. Single-valued
  enumerable fields (a product's `store`) are plain `str` + `filterable`;
  Vocab is only for tag-lists.
- `Ref["kind"] = RefField(label="<name_field>", pick=Query(state=...))`:
  the engine maintains the companion label field (declare it as a plain
  optional field; hide it from Create with `SkipJsonSchema`). Handlers
  never write labels.
- Money is integer cents on the wire; declare
  `x-display {"widget": "money", "currency"?}` on the field and the
  generic client renders it as dollars (display-only — forms still take
  cents). Works on plain, Derived, and Sum fields alike.
- Guard flavors, in preference order: `require("<derived bool>")` for
  gates over declared facts; declarative `Guard(judges=, accepts=,
  explain=)` when the verdict is "input value ∈ set computable from the
  row" (the set also becomes the rendered enum); `guard.expr(when=E...,
  severity=...)` for expression verdicts over stored facts
  (`severity="warning"` = acknowledgeable); `@guard("reason",
  judges=, reads=("other_kind",))` async code only when the verdict reads
  another row — name the read honestly.

## Cross-resource writes

Handlers write other rows only through `ctx.invoke`/`ctx.create`, and the
action must declare it: `touches=(Advances("product", "rematch"),
Creates("x"))`. There is no "move" primitive — re-parenting is a declared
action *on the child* that the parent's handler invokes. `ctx.find`
pages: loop `page=` until a short batch, don't trust one read. A cascade
still hits the target's guards (a warning guard will 409 the whole
transaction), so sequence handler work to make them pass — e.g. rematch
every product away *before* invoking the duplicate's `retire`.

## Parts (embedded item lists)

`PartScope("<array>", key="<field>")` gives per-item action placement —
but the key must identify one part, so design the array around a unique
key (price sightings are one-per-`seen_on`; a re-record *replaces* that
day). Write add/record handlers as upserts and removes as filters, so
retries and replays are no-ops.

## Deploying schema changes (learned from an outage)

Boot runs `create_all` + backfill — it creates missing **tables** but never
ALTERs existing ones. A field addition is safe as long as it stays in
JSONB; the moment a NEW field on an EXISTING kind is `filterable`/
`sortable` (or a Vocab), it promotes to a generated column and the deploy
**crash-loops at boot backfill** (`UndefinedColumnError`). Before such a
deploy, apply the DDL to prod yourself (pattern:
`ALTER TABLE <t> ADD COLUMN <f> bigint GENERATED ALWAYS AS
(((data ->> '<f>'))::bigint) STORED;` + a btree index named
`ix_<table>_<field>`), or adopt the `waymark9 migrate` revision workflow.
Note the prod DB port is dynamic per allocation — re-resolve with
`nomad service info mealplan-db` after any restart.

## Removing or moving a Data field (learned from a near data-loss)

- **JSONB residue does NOT survive the first boot.** Boot backfill loads
  every row through the current model and writes it back — keys the model
  no longer declares are silently dropped. If a migration plans to read a
  removed field's residue "after deploy", it will find nothing. Extract
  the data to a file (or migrate it) BEFORE deploying the model change.
- **Removing an action needs a rename chain.** The boot guard refuses to
  start if the transition log records actions the machine no longer
  reaches: declare `renamed_actions = {"old": "surviving_action"}` on the
  class in the SAME deploy that removes the action.
- The transition log is the recovery map: `select distinct resource_id
  from waymark9_transitions where kind='X' and action in (...)` tells you
  exactly which rows had used the removed surface.

## Safety declarations are load-bearing (learned from a silent no-op)

`Safety(idempotent=True)` is not just advertisement — the engine's
**natural replay** returns the document unchanged, without running the
handler, when the row's latest transition is the same action with the
same input digest. An action whose outcome depends on state OUTSIDE the
row (repricing from product prices, any refresh-from-world act) is NOT
idempotent and must say so, or its second invocation silently does
nothing. Genuinely idempotent upserts (same input → same outcome) keep
the declaration and get replay safety for free.

## Conformance enrollment rules (learned from failures)

- **A `@state_factory` must mint exactly ONE row of its own kind** — the
  collection contract counts the factory's returned rows against the
  table total, so a helper row of the same kind (e.g. a merge target)
  breaks it. Rows of *other* kinds are fine.
- **`@example_input` functions may be async** — when an action's input
  needs a fresh peer row (absorb's duplicate), mint it inside the example
  through an engine handle stashed on services by the fixture
  (`services.engine = engine`), not in the factory.
- **`touches=` entries are verified against the transition log**: a
  declared touch that doesn't happen on every walked invocation must say
  `may=True` (e.g. `Advances("product", "rematch", may=True)` — a
  duplicate with no products absorbs without rematching anything).
- Scope conformance runs to both the kinds AND the module, or pytest
  collects the whole repo:
  `pytest --waymark9 kind1,kind2 waymark9/testing/conformance.py`.

## Agent links / grants (the asking surface)

- The grantable menu (`/api/-/grantable`) lists only *authorable* fields;
  **derived fields and summaries attenuate to hidden** for scoped agents
  (rows render "(scoped view)"). Agents recompute from granted inputs
  (e.g. prices from `sightings`) or the design grants them explicitly.
- `create` is a valid requested-action token even though the menu only
  shows transitions.
- Cost/estimate pattern (grocery list phase 2): cross-resource judgments
  (item → product price) are stamped by the AI client through the normal
  action (`add_item` upserts), and the resource owns only the arithmetic
  over its own parts (`Derived` + `E.sum(items, of=..., where=...)`).
  There is no declared edge from an embedded part to another kind's rows.

## Testing against this repo

- No system Postgres. `make db` starts docker container `waymark-test-pg`
  on **:5433** (port 5432 belongs to another project). Then:
  `WAYMARK_TEST_DSN=postgresql+asyncpg://ckopsa@localhost:5433/waymark_test
  uv run pytest tests/waymark9_dogfood/<file>.py -x -q`.
- Never run two xdist pytest runs concurrently; on this laptop prefer
  targeted files over the full suite.
- Scoped conformance: `pytest --waymark9 ingredient,product` runs the
  generated suite for just those kinds (comma list of kind tokens or
  class names).
- Wire shapes that will trip you: an envelope has **no top-level `id`**
  (derive `doc["self"].rsplit("/", 1)[-1]`); collections are
  `resp.json()["data"]["items"]`; every POST needs an `Idempotency-Key`
  header; actions live at `{self}/-/{action}`; kinds pluralize with a
  bare `s` (`/api/ingredients`).
- Dogfood tests (`tests/waymark9_dogfood/`) are narrative: one story per
  file, asserting the *facts* (derived values, filters, refusals), not
  the mechanics. Copy `test_priya_week.py`'s fixture/helper shape.

## App conventions (family rules, enforced by review not code)

- Quantities in grams (`unit="g"` default; `each` for counted goods),
  temperatures in °F, money in integer cents.
- The AI is a **client**: receipt parsing, webscraping, and suggestion
  flows are agents driving the same declared actions a human would —
  never services inside resource code. Fallible AI judgments (a match, a
  suggestion) enter through a `suggested` state and a human verdict.
