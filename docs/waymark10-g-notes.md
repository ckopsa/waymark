# Waymark 10 — batch G: declaration ergonomics (the defresource split)

The declaration remains ONE EDN-able map after `normalize-resource`:
the fingerprint, the diff gate, the registry, and the overlays all
read that value. Everything in this batch is sugar that PROJECTS INTO
the canonical map; normalize is the seam. The governing invariant:
**two spellings, one law** — colocated vs split vs def'd vs inline
spellings of the same declaration fingerprint identically, so a pure
style refactor mints zero revisions. `batch_g_invariance_test` pins
it: the rewritten fixtures against their old split spellings
(byte-identical hashes), and a property over generated declarations
rendered both ways (identical normalized maps AND identical
fingerprint hashes).

## 1. Schema-entry colocation

Field-scoped law may be declared on the schema entry's property map;
`normalize-resource` (first step of its pipeline,
`project-colocated` in `waymark10.resource`) projects it to the
canonical top-level keys:

| colocated (entry props)      | projects to                                    |
|------------------------------|------------------------------------------------|
| `{:derived spec}`            | `:derived {field spec}` — the fact's field IS the entry (waymark9's `Derived()`-as-field-default, restored) |
| `{:filter #{:eq :range}}`    | `:filterable {field ops}`                      |
| `{:sort true}`               | field joins `:sortable :fields`                |
| `{:sort :default}`           | …and claims the default (`"field"`)            |
| `{:sort :default-desc}`      | …and claims the descending default (`"-field"`) |
| `{:part-scope {:key :date}}` | `:part-scopes {field {:path field :key …}}`    |

Rules:

- The sugar keys are **stripped from the entry props before the
  schema compiles or fingerprints** — they are declaration
  ergonomics, never schema properties. The stripped schema form is
  the split spelling's form, and the published JSON Schema is
  unchanged (pinned in `batch_g_declare_test`).
- **Exactly one home.** Declaring a concern both colocated and
  top-level for the same field is a definition error whose ex-data
  names the check: `{:check :one-home}`. Different fields may split
  across homes freely (e.g. `:state` filtering stays top-level — the
  engine's state is not a schema entry).
- **At most one sort default**, counting a top-level `:default` —
  two claims are refused at normalize.
- A colocated part scope's `:path` is its entry; naming a different
  `:path` is refused. The scope's name is the field.
- Colocation applies to the top-level entries of a `[:map …]`
  resource schema — item fields of nested vectors have no top-level
  concern to project.
- Projection runs before vocab self-merge, so a vocab field may still
  colocate an explicit `:filter` and win over the `#{:eq :in}`
  default.

## 2. `waymark10.declare` — defaction and defderived

In the `defguard` mold: each defs a **plain map identical to the
inline spelling**, so the def'd value drops in anywhere the inline
value does — directly in `:actions`/`:derived`, or colocated on a
schema entry (`[:end_date {:derived end-date} …]`).

Validation timing — the def site validates exactly what it can see:

- **defaction** runs `resource/normalize-action` (now public — the
  same construction-shape gate defresource runs) eagerly and
  discards the result: a missing `:safety`, a draft on a bulk, a
  fenced batch fails **at the def line** (`defaction/<name>: …`).
  Normalization itself still happens once, at defresource; the
  cross-referencing checks (states exist, judged fields are input
  fields, place names a part scope) honestly wait there. An inline
  `(fn …)` `:handler` gets its canonical printed form captured as
  `:waymark10/form` metadata — the same identity `defhandler` mints —
  so the fingerprint hashes the law, never the object.
- **defderived** normalizes the spec (`normalize-derived-spec`,
  factored public and idempotent: expression trees canonical, count
  `:where` values as sets) and scope-validates at def time — `:over`
  is right there, so an out-of-scope `(var …)`, a spec with both/
  neither of `:expr`/`:count`, or `:over` on a count fails at the def
  line. Whether the fact is a schema field, whether the edge exists,
  whether facts cycle — defresource's and assembly's questions.

## 3. Blessed idioms

- **Action groups**: a var holding a map of actions, merged into
  `:actions` — `(merge {:assign_meal …} closing-actions)`. The merge
  result is the same map the monolith spelled.
- **Named safety values**: `(def routine {:idempotent true
  :reversible true :confirm false})`, cited as `:safety routine`.
  This honors safety-never-inferred: reference is explicit
  declaration, not inference — the map is still spelled once, in
  full, and every citation names it; no property is ever computed
  from the action's behavior. What the rule forbids is the engine
  *guessing* safety; a name is the opposite of a guess.
- **Local builder fns** returning plain maps, when a family of
  declarations differs by one parameter (the fixtures'
  `calendar-clear-guard` is the house example).

## 4. The proof shape

- `test/waymark10/fixtures.clj` is rewritten in the new style:
  plan's derived/filterable/part-scope/sort colocated onto entries,
  `update_recipe`/`assign_meal` def'd (one with an inline captured
  handler, one referencing a `defhandler`), the closing pair as an
  action group, `routine` as a named safety.
- `batch_g_invariance_test` keeps the OLD split spellings alive,
  constructed in the test **sharing the fixtures' guard/handler
  objects** (a code guard without a stateable form hashes by printed
  fn identity, so the comparison must share instances — exactly what
  a style refactor does), and pins fingerprint hashes byte-identical
  for both kinds, plus full normalized-map equality for plan.
- The property (`a-style-refactor-mints-zero-revisions`, 100 trials):
  a generator over small declarations (fields with optional
  filter/sort law, at most one default, an optional derived fact, an
  optional part-scoped vector) rendered colocated and split →
  identical normalized maps and identical fingerprint hashes.

Follow-up: the mealplan10 declarations are the real consumers of this
style; their rewrite is a separate batch (owned elsewhere).

## 5. The mealplan10 rewrite (the follow-up, done)

All six mealplan10 kinds now live in the batch-G spelling;
`mealplan10/test/mealplan10/style_invariance_test.clj` keeps the old
split spellings alive (batch-G technique: constructed as data,
sharing the namespaces' guard/handler objects — and the hoisted
`g/require` gate, since `g/require` mints a fresh `:check` fn per
call) and pins all six fingerprint hashes byte-identical, plus full
normalized-map equality.

Field notes from the real consumer, for the style guide:

- **defderived + entry citation is what keeps colocation readable.**
  plan's biggest derived (`all_days_covered`, the `every` tree) would
  read badly inlined in entry props; def'd and cited
  (`[:all_days_covered {:optional true :derived all-days-covered} …]`)
  it reads better than the old top-level `:derived` block, because
  the fact sits on its field. Only true one-liners
  (`has_conflicts`, prep_task's `overdue`) went inline.
- **A Mirror kind colocates fine.** `mirror/declaration` returns a
  plain map, so entry-level `:filter`/`:sort` on the app schema
  project through the weave. Pin around the SAME wrapper on both
  sides; the weave re-mints the `observe_external` and
  `resolve_conflict` handler fns per call — identical canonical-form
  hashes (so the hash pin is exact) but distinct objects (so
  normalized-map equality holds only modulo those two handlers).
- **Some kinds have nothing to colocate.** rotation declares no
  field-scoped law at all (only `:state` filtering, which is not a
  schema entry); its whole style rewrite is two named safety values.
  The style guide is a default, not a law — an honest no-op is fine.
- **No colocated home exists for `:one-of`.** plan's `days/coverage`
  group is field-scoped in spirit (it governs `:days` arms) but stays
  top-level; a possible future projection, not claimed here.
- **Line counts go up, not down** (+18/+13/+46/+33/+9/+7 across
  meal/rotation/plan/grocery_list/prep_task/event): docstring style
  notes, def headers, and named-safety comments cost lines. The win
  is locality (the law on its field, the day-shaped actions one
  group), not brevity.
