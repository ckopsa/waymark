# waymark6 implementation notes (handoff)

The package began as a fork of `waymark5/` (working-tree state including
the 5.x co-presence wave: `link(embed=, badge=)`, `remedies` on warning
entries, the renderer's remedy chips and suggested-action prominence —
all of which §1/§4 stand on). Design: `docs/waymark6-design.md`. Storage
and channels are namespaced `waymark6_*`, so v5 and v6 engines coexist
in one database.

## Map

| Area | Where | The one thing to know |
| --- | --- | --- |
| Relationship declarations (§1) | `waymark6/core/related.py` | `Related(kind, on=(On(ours=, op=, theirs=), ...))`; `forward_filters`/`inverted_filters` are the two halves of one predicate — the same helper serves the read and the invalidation |
| Assembly checks | `waymark6/core/checks.py` (`check_related`) | Both sides' join fields must be promoted; date and date-time are distinct type families (date promotes to Text, date-time to timestamptz) — mixing is refused |
| Facts over relations (§2) | `waymark6/server/derived.py` | `_reverse_related` maps target kind → inverted-predicate descriptors; `_recompute_related` runs old∪new inverted queries inside the causing session; `compute` hands the pass's accumulating values to the forward read (`_FreshView`) |
| Ordering | `waymark6/core/derived.py` (`ordered_specs`) | A Related input's `ours` join keys are topo-sort dependencies — a fact over a relation computes after the derived boundary that defines its window |
| Temporal ranges (§3) | `waymark6/core/schemas.py`, `server/router.py` (`_parse_temporal`), `server/storage/postgres.py` (`_filter_value`) | Boundary params typed by the promoted column; asyncpg needs real datetimes for timestamptz and ISO text for date-as-Text — `_filter_value` coerces both directions |
| Edge-cited links | `waymark6/core/related.py` + `server/render.py` (`compile_edge_links`) | `link(rel_name, edge=_edge, ...)`; href compiled at assembly, resolves through the §3 grammar; `<`/`>` predicates are refused on links (public grammar has only `_gte`/`_lte`) while staying legal for derivations |
| Surfaces (§4) | `waymark6/core/surface.py`, `server/definitions.py`, `server/router.py` | `Surface`/`Member`; fingerprinted as `surface:{name}` definition rows via the `__registry__` treatment; served at `/surfaces/{name}/{anchor_id}` with members pre-embedded through the same `_collection_doc` path the real collection route runs — one authorization, no drift |
| Surface grants | `waymark6/server/grants.py` maps (no schema change) | A grant names `surface:{name}` / `"view"`; anchor owners pass without a grant (`owner=` threaded through the gate) |
| Generic client | `waymark6/server/static/ui.html` | `renderSurface` (declared title, showcased buttons ringed, member tables honoring `x-display.columns`); dashboard strip reads declared `attention=` surfaces first, engine-kind defaults as fallback |
| Dogfood | `mealplan6/` (+ `tests/waymark6_dogfood/test_priya_week.py`) | The design's driving story end-to-end: `_calendar` Related edge over stored week boundaries, `calendar_conflicts`/`has_conflicts`, warning-severity `calendar_clear` on finalize with remedies |

## Deviations from the design text (deliberate, tested)

1. **`link(edge=...)`, not `link(rel=...)`.** The design's spelling
   collides with `link()`'s existing first positional parameter `rel`
   (the relation *name*). Shipped as `edge=`; `href=`+`edge=` together,
   or neither, is a `DefinitionError`.
2. **Surface members name link rels, not edges directly.** The design
   prose says members cite "declared edges"; the shipped rule is one
   step looser — members name the anchor's declared **link relations**
   (hand-templated or edge-cited), still refused at assembly when
   undeclared. The composition remains law-checked; it simply composes
   what the anchor's links declare rather than requiring every member
   to be edge-backed.
3. **Ordered-comparison links are refused at assembly.** An edge whose
   predicate uses `<`/`>` cannot be cited by a link — the public query
   grammar deliberately speaks only `_gte`/`_lte`. Strict ops remain
   fully legal as §2 derivation inputs (internal `_gt`/`_lt` filter
   suffixes exist in storage, never advertised).
4. **Anchor owners see their surfaces without a surface grant.** In
   granted-visibility mode the surface gate threads `owner=` through, so
   the standing "full over what you own" rule opens the named view of a
   resource you own. Everyone else needs the `surface:{name}` grant —
   composition-as-sensitive holds for non-owners.
5. **The surface grant rides the existing grant maps** (`granted_actions
   ["surface:{name}"]["view"]`) rather than a new grant field —
   negotiable via `request_access` unchanged.
6. **`Member.table` is a display hint** (`x-display: {"columns": [...]}`
   on the embedded entry), never a data filter — visibility alone
   governs field presence.
7. **Event `kind` in mealplan6 is a `Literal`**, per the sibling
   precedent (`prep_task.task_type`), not a `VocabField`.

## Build findings (fixed in-build, worth knowing)

- **The mutable-derived-join-key lag.** As first built, a fact over a
  relation whose join key was itself derived (a plan's `end_date` over
  `start_date`) was computed against the *previous* materialization's
  boundary when a transition moved it — one write late, the exact
  disagreement §2 declares unrepresentable. Two coupled fixes:
  `ordered_specs` treats a Related input's `ours` fields as topo-sort
  dependencies, and `compute` hands its accumulating pass values to the
  forward read (`_FreshView`). Regression test with negative control:
  `test_a_shifted_window_relates_against_its_new_boundaries` fails
  without the fix. Discovered by the mealplan6 dogfood agent reading the
  maintainer, not by a failing test — the dogfood app itself never
  mutates its boundaries.
- **Registering a real `event` kind exposed `prep_task`'s string-typed
  `event_id`/`calendar_event_id`** (the naming-convention usability
  check fired once the kind existed). Both are now `Ref["event"]` — the
  calendar linkage the app always meant, forced honest by declaration.
- **Create materializes twice by design** (`server/invoke.py`: once
  before insert, again when the resource has child/related inputs), so
  derived join keys are correct at create even before the ordering fix;
  the transition path was the exposed one.

## Dogfood

`mealplan6/` (fork of mealplan5 + the `Event` kind). The Priya story
(`tests/waymark6_dogfood/test_priya_week.py`): a blocking event created
inside the week flips `calendar_conflicts` in the same request; finalize
409s `warning-required` with "1 calendar conflict(s) overlap this week",
remedies `event.cancel`/`event.reschedule`, and the acknowledged
finalize lands with `acknowledged: ["calendar_clear"]` on its transition
row; rescheduling the recital between two plans' windows recomputes both
(the old∪new set union); the compiled calendar link is
`/events?date_gte=…&date_lte=…` with `embed=true` and the conflict count
as its badge. `make mealplan6` (port 8004), `make conformance6`.

## Verified

- `tests/waymark6`: 312 (266 forked baseline + 30 Related track + 15
  Surface + 1 mutable-join-key regression).
- `tests/waymark6_dogfood`: 3 (the Priya story).
- `pytest --waymark6 -n auto`: 2015 passed, 0 failed over the mealplan6
  dogfood (per-kind `event,plan` scope: 184/0).
- v5 collateral: `tests/waymark5` + `tests/ledger5` + `--waymark5`
  unchanged and green throughout.

## Recorded for the next wave

- The §2 conformance replay does not yet drive the mutable-join-key
  shape per-kind (the regression test covers the engine; a conformance
  case generalizing it would cover every app).
- `attention=` surfaces feed the dashboard strip; nothing yet ranks or
  caps nominations — fine at one or two surfaces, unstudied at twenty.
- Cross-engine members, clock-referencing predicates, aggregates beyond
  Count/Sum: the design's punts, untouched.

## Extension wave: relations evaluate against inputs and identities

The two ledger6 findings-doc seams (#1 and #2 under "Seams, named")
pointed one direction — the relation machinery judged only *stored
rows*, never the input a create carries or the identity a ref names —
and this wave closes both. Coverage grows; every §1–§2 law holds at
full strength.

**Identity joins (seam #2).** `On(theirs="id", op="==")` is now legal:
a `Related` predicate may compare a stored field of ours against the
target's primary key — the child→parent direction (`Related("account",
on=(On(ours="account_id", op="==", theirs="id"),))`) that every port
hand-wrote as `workbook_open`/`account_open` prose. The promoted-fields
law is unweakened, re-read: it exists because a predicate storage cannot
index cannot be honored, and the id IS the indexed column. Only equality
is representable (`op` other than `==` against an identity is refused at
`On` declaration), `ours="id"` stays refused pointing the author at
`Owns` (that direction carries obligations), and the `ours` side must
still be a promoted string column — the inverted map is an indexed point
lookup on it. Pitfalls resolved by name:

- *Identity is not in the data dumps.* The inverted predicate runs over
  `model_dump` value sets, and `id` appears in no dump —
  `_recompute_related` now rides the target row's id alongside both the
  old and new values (it never changes between them, so the two identity
  filters dedupe like any unmoved join value).
- *The forward read needed no special case.* `storage.query` (and
  `ctx.find` over it) accepts an `id` filter — the conditions builder
  binds `table.c["id"]` like any column — so the forward read, the
  garnish resolver, and the backfill all speak the one query path;
  no `load` carve-out exists to drift.
- *Edge-cited links refuse identity predicates.* The public collection
  grammar has no `id` param, and the Ref field already renders the
  navigable reference — a `link(edge=)` over an identity join is a
  `DefinitionError`, not a 422 at click time.
- The predicate rides `_derived_fp` as data like any `on=`; moving a
  join from a data field to the identity is a redefinition, and the
  fingerprint (and therefore backfill-on-revise) says so.

**Chained recompute, built where "verify" found it missing.** The brief
for this wave assumed derived-over-derived already chained across kinds;
an empirical probe (a break create explaining an account under a
workbook) showed it did not — `recompute_owners` materialized one hop
and stopped, so ledger6's `workbook.open_unexplained` was silently
stale after any break write. The maintainer now propagates: a dirtied
row whose facts *flipped* runs `recompute_owners` itself — same commit,
same cause, `before`-dump discipline intact — so
break → account.unexplained → workbook.all_accounts_reconciled settles
in the causing request, and the new parent→child direction rides the
identical mechanism. The transition path also refreshes the written
row's in-memory data after the chain (the create path's post-seed
discipline, applied to transitions), because the chain can now circle
back to the very row being rendered.

**Cross-kind cycles are refused at assembly.** With facts flowing both
directions, two kinds could each derive over the other's derived fact
for the first time. `check_derived_cycles` (engine assembly, beside
`check_related`) builds the cross-kind fact graph — own-field derivation
deps plus the *derived* target fields reached through
`ChildField`/`RelatedField` inputs (read field, `theirs` join keys,
`where=` filters) — and refuses cycles with the loop named. This is the
chain's termination proof, not a lint: each propagation hop settles a
strictly deeper fact of a DAG. Legitimate chains of any length pass.

**require() evaluates at create (seam #1).** `FactRequired` read the
materialized fact off `r.data`; at create `r is None` and no row exists
for the maintainer to have written, so a Related fact could not gate its
own create and the relation-fact and the create-guard stayed two
spellings of one predicate. Extended on the E9 precedent (guards already
judge inputs): with `r=None` the fact is computed from the validated
create input by `compute_from_input` — the spec's own `over=` resolved
input-side (own fields off the input; `ChildField` → empty list, because
a new row owning nothing is *true*, not a fallback; `RelatedField` → the
forward read with the input's join values, identity joins included;
`Clock` → the ctx's now) and folded through the same pure `apply` that
materialization and the conformance replay run. One truth, third calling
context — which is the consistency guarantee, tested: the value the
create guard judged equals the value the same create materializes.
Pitfalls resolved by name:

- *Class binding.* With `r=None` there is no `type(r)` to find the Data
  model on; `checks.check_require` — which already validated `require()`
  facts per class, and now covers `create_guards` too — binds the owning
  Data model onto the guard instance where the class is known. Each
  `require()` call site is a fresh instance, so the binding is
  per-class; one instance reaching two unrelated classes is a
  `DefinitionError`.
- `require(..., severity=...)` passes through (the registry gate wants a
  warning at create), the `explain=`/`vars=` garnish resolves from the
  same input-computed args (no second read), and the acknowledge path is
  the standard `Waymark-Acknowledge` header with the guard's
  `require:{fact}` name on the create's own transition row.
- `ctx.find` grew a `page=` keyword so the create-time fact walks every
  match exactly as the maintainer does (200 a page), instead of trusting
  one truncated read.

Tests: `tests/waymark6/test_identity_joins.py` (12) and
`tests/waymark6/test_require_at_create.py` (8); `tests/waymark6` at 332,
`--waymark6` conformance 2348/0, ledger6 + the Priya dogfood
untouched and green. Recorded for the wave after: the clock sweep
(`tick()`) still materializes per row without chaining into cross-kind
facts — a clocked fact someone derives over goes stale until the next
write on either side; the gap predates this wave (it exists for `Owns`
too) and no dogfood shape hits it yet, but the chain now exists to wire
it to.
