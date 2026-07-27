# Spec — what a collection page opens on

**Thesis.** A collection's first screen is a decision the declaration should
make, and today it can only make half of it. Default *sorts* exist but cannot
express "most recent" for any kind. Default *filters* do not exist at all.

## Epistemic status

Two small framework changes with reuse across every collection page in every
app. The second one carries a real hazard — a filter that hides rows can make
"nothing here" and "nothing in the slice I silently chose" look identical — and
most of this spec is about refusing that.

Prompted by the approval page: an administrator wants the newest requests
first, and that is currently unexpressible.

## Part 1 — sortable timestamps

### What exists

`:sortable {:fields [:due_date] :default "due_date"}`, with colocated sugar
(`:sort :default` / `:default-desc` on a schema entry, `resource.clj/project-sort`).
`collections.clj/parse-query` applies `[:sortable :default]` when no `sort`
param is given, and `query-input-schema` advertises it as the param's
`:default`. So a declared default sort already works, end to end.

### The gap

`parse-query` validates the requested sort against
`(mapv name (get-in rdef [:sortable :fields]))`, and `store.clj` orders by the
**promoted generated column** of a schema field. Every kind table already
carries `created_at` and `updated_at` (`store/postgres.clj`'s kind DDL), but
they are not schema fields, so they cannot be named in `:sortable` and cannot
be sorted by.

The consequence is broader than one page: **recency — the most natural default
ordering there is — is unexpressible for every kind in the system** unless it
happens to carry its own timestamp field. `approval_request` declares no
`:sortable` at all, and has nothing it could declare.

### The design

Let `:sortable :fields` name `:created_at` and `:updated_at`. They are not
schema entries, so:

- `parse-query`'s validation admits them alongside the promoted fields.
- `store.clj`'s ordering maps them to the table columns directly rather than to
  `f_<field>`.
- `query-input-schema` advertises them in the `sort` enum like any other, so
  the UI's column headers and the wire stay generated rather than special-cased.
- The declaration check refuses a kind that declares a schema field *named*
  `created_at`/`updated_at` — the collision must be a definition error, not a
  silent shadowing.

**An index is required, not optional.** Ordering a large collection by an
unindexed column is a sort of the whole table. Add `ix_<table>_created` (and
`_updated`) as a standard per-kind index, in the same additive shape as
`ix_<table>_state` — which means a migration, and the plan must be read rather
than assumed.

Then the approval page is one line: `:sortable {:fields [:created_at]
:default "-created_at"}`.

### Punt

Sorting by `created_at` on a **mirror** kind orders by when the local row was
minted, not when the authority created the thing. That is honest but can
surprise; the mirror kinds that care already carry their own timestamps.
Record it, do not fix it.

## Part 2 — default filters

### The gap

There is no mechanism, no wire representation, and no way to express "this page
opens on the requests that need action."

### The design

`:filterable` gains a sibling — `:defaults`, a map of param → value, applied by
`parse-query` **only when the caller named no filter on that field**:

```clojure
:filterable {:state #{:eq :in}}
:default-filters {:state "offered"}
```

Explicit beats default, always. `?state=denied` overrides; `?state=` (empty)
clears without substituting; any other filter on another field leaves the
default in place.

### The hazard, and the rule that contains it

**A default filter hides rows.** If it applies invisibly, an empty page and a
filtered-empty page are indistinguishable, and a denied request becomes
unreachable to someone who does not know to look for it. That failure is
silent, which is the kind this codebase refuses.

So the default is **advertised and rendered as an ordinary active filter**:

- `query-input-schema` carries `:default` on the param, exactly as `sort`
  already does.
- The collection's `self` href carries the applied filter explicitly, so the
  URL a person copies is the view they saw.
- The UI renders it through the **existing chip machinery** — a set,
  removable chip — so it looks like a filter someone applied, because it is
  one. `filterChips` needs no new concept; it already skips showcased fields
  and renders the rest.
- The envelope's `summary` already says `filtered: state=offered`; that stays
  true and becomes load-bearing.

A default filter naming a field that is not `:eq`-filterable, or a value the
field's schema refuses, is a **definition error** — caught at assembly, not at
the first request.

### Punts

- **Defaults on embedded collections** (`embed.<rel>.*`) are out of the first
  cut; the parent's own filters already scope those.
- **Per-principal defaults** ("my requests") need an actor-dependent value and
  a rendering that explains itself. Different feature.
- **Interaction with `:showcase`.** A showcased field's standing control must
  show the default as its selected value rather than "All", or the control and
  the result set disagree on screen. One clause, easy to miss.

## Tests this needs

Default sort already covered; extend for the timestamps. New: recency ordering
both directions; an explicit `sort` beating the default; a default filter
applied and visible in `self` and in the advertised schema; an explicit filter
on the same field overriding it; an empty value clearing it; a filter on a
*different* field leaving it in place; a bad default refused at assembly; and
the showcase interaction above.

## Effort

**Part 1: small**, plus a migration for the indexes. **Part 2: small-to-medium**,
almost entirely in making the default visible rather than in applying it.
