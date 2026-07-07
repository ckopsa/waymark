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
