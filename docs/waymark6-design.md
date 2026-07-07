# Waymark 6.0 — the decision surface is a resource

A redesign of Waymark that starts from what 5.0 could not show. Companion
documents: `waymark5-design.md` (the definition as a resource),
`waymark5-notes.md` (where the 5.0 build deviated and why), and
`_dogfood-ledger5-findings.md` — the first full-stack, real-data port,
whose headline finding is the evidence base here the way the 4.0
materialized build was the evidence base for 5.0.

## Why 6.0

The cash reconciliation port put the generic client in front of a real
decision and watched it fail politely. Marcus, on an account, choosing
between recording a balance, overriding it, entering a break, and marking
the account reconciled: the arithmetic was on the page — 4.0's `Derived`
chain, every intermediate a field — but the breaks the refusal told him
to record were nowhere. Elena, on a workbook, choosing between refresh,
prepare, and review: the aggregates were on the page, the per-account
breakdown a traversal away, the job that would tell her whether the
refresh actually succeeded three clicks into engine chrome. Every fact
was true, every affordance honest, and the decision still could not be
made at a glance.

The law already knew better. Look at what the definitions declare:
`workbook_open` reads the account's parent. `fund_has_registry_accounts`
queries a kind that is nobody's child. `beacon_fresh` reads a service and
the clock. `Predecessor` composes with a sibling. Every `reads=` is the
definition confessing that a decision's inputs cross resource boundaries
in whatever direction the domain runs. The enforcement layer composes
freely; only the presentation layer was stuck at single-resource
granularity — and so was the *fact* layer: `Derived(over=)` accepts own
fields, `Owns` children, and `Clock`, nothing else. A meal plan cannot
carry `has_conflicts` over the calendar events that overlap its week,
because the calendar is no one's child — the relation is a date-overlap
predicate, not an edge. The 4.0 notes recorded this exact gap and its
price: *"honest unification needs a reverse invalidation map … both
halves of this are one future change."* 6.0 is that change.

The ownership bias was never a principle; it was an implementation
convenience. `Owns` gave the derivation maintainer a scalar foreign key
to dereference — one indexed point lookup from a child write to its
dirtied parent. Everything downstream inherited the bias: rollups,
cascade, `edge.field()`, and finally the co-presence wave (`embed`,
`badge`), which — tellingly — landed on `link()`, not on `Owns`, and
worked for any templated query. The fragments already compose by
predicate. 6.0 is the fragments made law.

2.0 unified advertisement and enforcement for guards; 3.0 for
visibility; 4.0 for facts; 5.0 for history's relationship to the law
that governed it. 6.0 unifies them for **the surface a decision is made
on**: the composition of resources a choice requires is declared, the
engine maintains facts across it, the client renders it, and a deploy
that changes what a decision-maker sees is a transition in the audit
trail like any other change of law.

> **A decision whose inputs the definition could have composed,
> assembled by hand in a client, is a design error, not a UX detail.**

The admission test for any 6.0 feature: can a client that renders only
envelopes co-present every declared input of a decision — and can the
engine maintain a fact over every declared relationship with the same
materialization guarantee facts over children have — with zero
app-specific client code? A relationship the app must re-join in a
handler, or a screen the app must rebuild in React, fails.

## What carries over unchanged

- The envelope, uniform invocation, the single invoker, idempotency
  replay, errors as hypermedia, drafts, discovery — untouched.
- The 4.0 field origins: `Derived` and the materialization law,
  `Authored` and per-field authority, the three event classes, `Unless`,
  `When`, `Compound`, `Batch` — kept. §2 extends what `over=` may name;
  it does not reshape what a derived field *is*.
- The 5.0 anchor: definitions as fingerprinted resources, deploys as
  `revise` transitions, `defined_by` on every transition, backfill on
  redefinition, continuity maps — kept, and §4 stands on it.
- `Owns` — kept in full, and re-read: ownership is a *relation with
  obligations* (cascade, seed, the child's lifetime bounded by the
  parent's). §1 gives it a supertype, not a replacement; nothing about
  `Owns` weakens.
- The 5.x co-presence wave — `link(embed=, badge=)`, `remedies` on
  warnings, the renderer's remedy chips and suggested-action prominence —
  kept as shipped, and *subsumed but not broken*: an embed-bearing link
  is the degenerate, anonymous form of the §4 surface.
- Jobs, attachments, members/roles/grants, webhooks, Mirror, agent
  links, the generic client — untouched except where §3–§4 name them.

Everything below is what changes.

---

# 1. Relationships are declarations

The declaration:

```python
_calendar = Related("event", on=(
    On(ours="start_date", op="<=", theirs="date"),
    On(ours="end_date",   op=">=", theirs="date"),
))

_registry = Related("account_template", on=(
    On(ours="fund", op="==", theirs="fund"),
))
```

A `Related` edge names a target kind and a conjunction of field
comparisons between *our* stored fields and *theirs*. Equality and the
ordered comparisons ship; the two-sided date containment above is the
motivating composite. The predicate is data about the definition — it
rides the fingerprint, it is validated at assembly, and both consumers
below read the one declaration.

What the checks refuse at import (`check_related`, in the
`check_derived_edges` tradition — "the recompute query must be
indexable" was already law for `where=`):

- a target kind not registered;
- a join field, either side, that is not a promoted (filterable or
  sortable) column — a predicate the storage layer cannot index is a
  predicate the maintainer cannot honor;
- an operator the promoted column types cannot serve;
- a predicate referencing `Clock`. A relation whose membership changes
  as time passes, with no write on either side, would make §2's
  guarantee a lie on a timer. The plan's week is *stored* boundaries
  (`start_date`, `end_date`); "the current week" is the app's own
  clocked derivation over its own fields, exactly as today. Recorded
  punt, revisited when a real domain needs it (§punts).

`Owns` is hereby a subtype: an `Owns` edge *is* a `Related` edge whose
predicate is `On(ours="id", op="==", theirs=via)`, plus the obligations
only ownership can claim — cascade transitions, seed, the rollup sugar.
Consumers that take a relation (`.field()`, links, §4 members) accept
either; consumers that take ownership (cascade, `Seed`) still demand
`Owns`. The type system says what the domain means: *related* is a fact
about data, *owned* is a fact about lifetimes.

`Related` edges also feed links. Today `link(href="/events?...")` is a
hand-templated query string that the checks cannot see — a typo'd param
is a 422 at click time. A link may now cite the edge:

```python
links = (link("calendar", rel=_calendar, embed=True,
              summary="What the family already has planned"),)
```

The href is compiled from the predicate; the target kind, the params,
and their types are checked where everything else is. Hand-templated
hrefs keep working — the escape hatch stays honest.

# 2. Facts over relations

```python
class PlanData(BaseModel):
    start_date: date
    end_date: date
    calendar_conflicts: int = Count(
        _calendar, where={"kind": ("blocking",)})
    has_conflicts: bool = Derived(
        over=(_calendar.field("kind"),),
        fn=lambda kinds: any(k == "blocking" for k in kinds),
        explain="{n} calendar conflict(s) overlap this week.",
        vars=lambda kinds: {"n": sum(k == "blocking" for k in kinds)})
```

`Related.field()` is a Derived input exactly as `Owns.field()` is: the
values arrive as a list, `Count`/`Sum` are the same library derivations,
`require("has_conflicts")` gates a transition with the generated
sentence, the field is filterable, and the value is materialized in the
row — one definition serving envelope, guard, filter, badge, and bus.

The maintenance guarantee is the 4.0 materialization law, unchanged in
statement and extended in mechanism. For `Owns` inputs the maintainer's
reverse map is `(child_kind, via)`: a child write dereferences one
scalar and dirties one parent (two, when the reference moved). For
`Related` inputs the reverse map entry is the **inverted predicate**: an
event write runs an indexed query for the plans whose stored boundaries
contain it — `plans WHERE start_date <= :date AND end_date >= :date` —
and dirties the *set*. Edits run the inverted query over the old field
values and the new (the `before`-value discipline `recompute_owners`
already practices, generalized from two ids to two sets); deletes run it
over the old. The recompute rides the causing transaction's commit, rows
locked, flips published through the outbox after — the same sentence
that governs children today, verbatim.

This is why §1's import checks are strict: invertibility is not an
optimization, it is the guarantee. A predicate the engine cannot invert
into an indexed query is refused at import, not degraded at runtime.
What is unrepresentable, extended to relations: **a stored fact that
disagrees with the rows its declared relation currently selects.** There
is no "eventually consistent" tier; a relation that cannot afford the
law does not get a derived field.

Cost honesty, in the definition where it belongs: a `Related` write
fans out to a query's worth of parents, not a FK's worth. The checks
require the index; the design requires the app to feel the write
amplification at declaration time, not discover it in production — a
`Related` edge declares, and the fingerprint records, the direction of
the fan-out.

# 3. The range speaks its own type

Small, and load-bearing. The 5.0 filter grammar advertises every
`RANGE` boundary param as `{"type": "number"}` unless the field is
already numeric — so a date field's honest btree comparison (which the
storage layer performs correctly) is *advertised* as a lie, and a
client sending "2026-06-15" is 422'd for not being a float. The
ledger6 findings called this a papercut and then tripped over it
being the join predicate of the whole thesis: "events during this
plan's week" *is* a date-range comparison.

6.0: boundary params are typed by the promoted column —
`{"type": "string", "format": "date"}` / `"date-time"` for temporal
fields (the `_after` param already does this; the range params join
it), numeric for numeric. `On(op="<=")` over dates and
`?transaction_date_gte=2026-06-01` are the same grammar, checked by the
same schema, served by the same index.

# 4. The decision surface is a resource

5.0's law: a change of meaning the audit trail cannot show is a design
error. 6.0 extends *meaning* to the surface: what a decision-maker is
shown, when the definition composed it, is part of what the system
means — and a deploy that quietly changes it fails the same admission
test.

```python
class CloseReview(Surface):
    name = "close-review"
    anchor = "workbook"
    title = "Monthly close — {anchor.data.fund} {anchor.data.period}"
    members = (
        Member("accounts", table=("name", "effective_balance",
                                  "beacon_balance", "unexplained",
                                  "reconciled")),
        Member("statements"),
    )
    showcase = ("prepare", "review", "reject")
```

A `Surface` is a declared composition: an anchor kind, members naming
the anchor's declared edges (`Owns` or `Related` — a member the anchor
has no edge to is a `DefinitionError`; the surface composes what the
law relates, it does not smuggle new joins), the facts and affordances
it showcases. It is served at `/surfaces/{name}/{anchor_id}` as an
ordinary envelope whose data is the anchor's, whose declared members
arrive embedded, and whose showcased affordances render first. The
generic client needs nothing new beyond what the embed wave taught it —
a surface is the named, law-governed form of what an embed-bearing
detail page already is.

Three things make it a *resource* rather than a template:

- **It is fingerprinted.** A surface has no rows (`row_model is None` —
  the fingerprint layer already speaks storage-less definitions, and
  `__registry__` is the standing precedent for a definition spanning
  kinds). Its fingerprint hashes the anchor, members, showcase, and the
  cited edges' predicates. Reordering the columns Elena reviews the
  close by is a `revise` transition on the surface's definition, in the
  same log, with the same diff discipline, as changing a guard. What
  the reviewer saw is now as answerable as what the law required.
- **It is the grant target for composed visibility.** Per-kind grants
  answer "may Priya read plans" and "may Priya read events"; they
  answer "may Priya see the week-with-calendar view" only by
  conjunction, which is the wrong altitude when the composition itself
  is the sensitive thing. A grant may name a surface; the surface's
  render applies member visibility per 3.0 rules *and* the surface
  grant gates the composition.
- **It is discoverable.** Surfaces list in the well-known document;
  an anchor's envelope links to the surfaces anchored on it. The
  client's "needs attention" chrome — hardcoded in 5.x, recorded as
  friction — becomes: a surface may declare `attention=` (a filter over
  its anchor kind), and the dashboard renders what the definitions
  nominate. Nothing inferred that a declaration could have said,
  applied at last to the client's own chrome.

What a surface deliberately is not: a layout language. Members declare
*what* is co-present and *which* columns matter; arrangement remains
the renderer's. The moment a surface declares pixels it becomes the
React app it exists to delete.

# 5. The scar table

| Strain (recorded, cited) | 6.0 fate |
| --- | --- |
| 4.0 notes: `over=` limited to own/Owns/Clock; "honest unification needs a reverse invalidation map" | §1–§2: `Related` + inverted-predicate invalidation — the recorded future change, built |
| ledger5 finding 1: decisions are compositions; law composes, presentation doesn't | §1 (declared relations), §4 (declared surfaces) |
| ledger5 finding 2: date-range filter params advertised as numbers | §3, and prerequisite to §1's temporal joins |
| ledger5 finding 6 / 5.x chrome: "needs attention" hardcoded to engine kinds | §4 `attention=` — the strip reads declarations |
| mealplan (all versions): the calendar the plan decision actually consults has no home | §1–§2: the canonical `Related`; the mealplan6 dogfood's driving story |
| 5.x embed/badge landing on `link()` with hand-templated hrefs the checks can't see | §1: links may cite edges; templates stay as the escape hatch |

## Wire format delta

| Surface | v5 | v6 |
| --- | --- | --- |
| Derived fields over relations | not expressible | indistinguishable from any derived field: `readOnly`, `x-source: "derived"` — the wire does not care what the input was |
| Range filter params on temporal fields | `{"type": "number"}` (dishonest) | `{"type": "string", "format": "date"/"date-time"}` |
| Links citing a declared edge | n/a (templated href only) | same link object; href compiled, no new keys |
| Surface envelope | n/a | ordinary envelope: anchor's `data`, members under `embedded`, showcased actions first; `kind: "surface:close-review"`; `meta.law`/`law_revision` from the surface's own definition |
| Well-known document | kinds | kinds + surfaces |

A v5 client keeps working: it never sees a relation (only the fields
derived over it), never fetches a surface unless it follows the new
links, and the only in-place change — temporal range param types — makes
previously-impossible requests representable rather than breaking any
that worked.

## Migration sketch (v5 apps)

- Mechanical: `import waymark6 as waymark`; nothing existing changes
  meaning. `Owns` declarations are already `Related` subtypes; no
  rewrite.
- Where a handler or client re-joins across kinds (the `ctx.find` in a
  `check=` guard that §2 could gate declaratively; the client-side
  "fetch the children and count" no generic client should ever have
  needed): declare the `Related` edge, lift the fact, delete the prose.
- Where a detail page grew embed-links into a de-facto screen: name it —
  a `Surface` declaration, and the composition enters the audit trail.
- Temporal `filterable.Range` declarations that were avoided or
  worked around (ledger5's `transaction_date`): restore them; the
  grammar is honest now.

## Explicit 6.0 punts

- **Clock-referencing predicates** (§1) — a relation whose membership
  drifts with no write. The stored-boundaries discipline covers every
  case the dogfoods have produced; the punt is recorded with its
  trigger: a real domain whose join genuinely cannot be stored.
- **Cross-engine composition** — a surface member living in another
  waymark server (or a foreign API) without full mirroring. Mirror and
  `Authored` remain the boundary story; the federation protocol still
  waits for the second real server, exactly as 5.0 left it.
- **Aggregations beyond Count/Sum over relations** — same rule as E4:
  punt things that don't compound.
- **Surface layout** — arrangement, grouping, theming stay the
  renderer's. Declaring intent ("table", column order) is in; declaring
  geometry is refused on principle (§4).
- **As-of rendering, editable definitions** — still §7 of the 5.0
  design, still waiting; a surface's revision history makes as-of
  *surfaces* newly tempting, and still not built.

Not punted, on principle: **the materialization guarantee over
relations.** A `Related` derived field that could be stale — computed
lazily, refreshed on a timer, "eventually" right — would be 3.0's
`apply_scope` reborn wearing a join. Either the inverted predicate is
indexable and the fact rides the causing commit, or the declaration is
refused at import. There is no third tier.

---

## Appendix: before/after stories

### §1–§2 — Priya plans the week

**Before (v5):** Priya finalizes Thursday's dinner. The family calendar
— a kind in the same engine — says Thursday is the school recital. The
plan's definition cannot say `Related("event", ...)`, so the conflict
is nobody's fact: the client can't render what no envelope carries, the
finalize guard can't gate on what no field holds, and the family learns
about the collision at 5pm Thursday. The workaround is a `check=` guard
doing `ctx.find("event", ...)` — enforcement without a renderable value,
the exact seam the 3.0 mappings called "the computed fact with no
declared home."

**After (v6):** The plan declares `_calendar` with stored week
boundaries. `has_conflicts` is a field: it renders beside finalize,
filters the plan list, and `require("has_conflicts")`-style gates speak
the generated sentence — "1 calendar conflict(s) overlap this week" —
with the recital embedded on the plan's page under the declared link.
When Sam adds an event, the inverted predicate finds the two plans
whose weeks contain it, in the same commit; the plans' facts flip; the
bus tells anyone watching.

### §3 — Sam narrows the ledger

**Before (v5):** `?transaction_date_gte=2026-06-01` → 422: "could not
convert string to float." The column compares dates perfectly well; the
advertised schema lies about it. ledger5 shipped the field Eq-only
and wrote the workaround into its findings.

**After (v6):** The param is `{"type": "string", "format": "date"}`.
The same grammar serves Sam's filter and the plan-event join; one truth
about what a date range is.

### §4 — Elena's close review, and the auditor after her

**Before (v5):** Elena reviews the January close on the workbook's
detail page — aggregates on the page, accounts a click away, statements
two. After the co-presence wave the embeds bring the table to her, but
what she sees is the renderer's default assembly of link declarations;
when a deploy adds a column to the account embed, nothing in any log
says the review surface changed. The auditor asking "what did the
reviewer see when she signed off?" gets an honest shrug.

**After (v6):** `close-review` is a surface: anchor workbook, members
accounts (five named columns) and statements, showcase prepare/review/
reject. Elena opens one URL and the decision's inputs are one page, by
law. When Colton's deploy adds `active_breaks` to the member table, the
surface's definition revises — a transition, with a diff, in the same
log as everything else. The auditor's question has a resource for an
answer: the surface at revision 3, and the envelope Elena's review
transition was rendered under.
