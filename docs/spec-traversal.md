# Spec — one-hop traversal over declared refs

**Thesis.** `?assignee=<id>` filters chores by a person. `?assignee.state=suspended`
— *chores assigned to someone suspended* — is unanswerable, and it is the shape
of most real questions. Add exactly one hop, and refuse the second loudly.

## Epistemic status

The weakest of these specs, and included because the bound is the design. Two
hops in, this is a query language, and a query language over jsonb is a worse
Postgres wearing waymark's clothes. The case for one hop is that refs are now
declared, checked, and promoted — the engine already knows the join key and
already enforces that it is indexed — so a single hop is nearly free while an
arbitrary one is a project.

Ship it only if a real question demands it. Two candidates already exist: *"whose
chores have no runs this week"* and *"tasks whose assignee has no handle"* (the
gap `waymark-6w3` worries about). Neither is urgent.

## What exists

- Refs are declared with `{:kind :member}` and now advertise `x-ref` on both the
  field and the filter param (`server/collections.clj`, `schema/ref-props`).
- `checks_assembly.clj` already enforces the rule this feature depends on: an
  external-keyed ref's `:match` field **must be `:eq`-filterable on the target**,
  because "resolution is one indexed read on the promoted column or not at all."
  A traversal is the same bargain.
- `store.clj` promotes every filterable/sortable field to a generated column and
  indexes it. So the target side of any legal hop is already an indexed column.
- The filter grammar (`collections.clj/grammar`) is a declared param → cond
  mapping with a closed op set and a 422 that names every bad parameter.

## The design

**Grammar.** `?<ref-field>.<target-field>=<value>`, with the existing operator
suffixes riding the *target* field: `?assignee.state=suspended`,
`?assignee.display_contains=kop`. One dot. Two dots is a 422 naming the limit.

**Legality, checked at assembly, not at request time.** A hop is legal when:

1. `<ref-field>` is a `:waymark/ref` on this kind declaring `:kind`, and
2. `<target-field>` is `:eq`-filterable (or otherwise filterable for the
   suffix used) on the target kind.

Both are knowable from the registry, so `checks_assembly.clj` can publish the
legal hop set at boot — which means the query schema can *advertise* it rather
than making clients guess. That is the whole reason to do this in waymark
rather than in SQL: the traversal is discoverable.

**Wire.** Each legal hop appears in `actions.query.input.properties` as a
dotted param carrying the target field's own type, plus the target's `x-ref`
lineage:

```json
"assignee.state": {"type": "string", "enum": ["invited","active","suspended"],
                   "x-hop": {"via": "assignee", "kind": "member",
                             "field": "state"}}
```

The filter popover then renders a hop exactly like any other column — the
picker work done for ref filters generalises without a second UI concept.

**Execution.** One `EXISTS` subquery against the target table's promoted
column, ANDed into the existing cond grammar:

```sql
EXISTS (SELECT 1 FROM members m
         WHERE m.id = chores.f_assignee AND m.state = ?)
```

Indexed on both sides by construction — `f_assignee` because the ref is
filterable here, `m.state` because the rule above requires it there.

**Facets.** A hop does not facet. Counting distinct target values per source row
is a second query per hop, and the facet machinery is already documented as
best-effort. Refuse rather than half-deliver.

## Recorded punts

- **One hop, hard.** No `a.b.c`. If that need arrives, it is a signal to
  reconsider the storage model, not to extend this grammar.
- **No reverse hop.** "Members who have chores" is the `:owns`/`:links`
  direction and already has an answer (`chore.links.runs`). Adding a reverse
  traversal would give two spellings for one question.
- **Dangling refs.** A row whose ref names a deleted target simply fails the
  `EXISTS` — it is excluded, not errored. Worth one sentence on the wire, since
  "not matching" and "pointing at nothing" look identical in a result count.
- **Vector-of-ref fields** (a mirror's `:many` external-keyed refs) are out of
  scope for the first cut; the containment operator that would serve them is a
  separate grammar.

## Effort

**Medium.** The SQL is trivial; the work is the legality check, the advertised
schema, and resisting the second hop.
