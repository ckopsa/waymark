# waymark8 implementation notes

The package began as a byte-identical fork of `waymark7/` (modulo the
rename: `waymark8_*` storage and channels, `--waymark8` plugin, `wm8`
fixtures, media type v=8), implementing `docs/waymark8-design.md` — "the
law becomes data." Read the design first; its scope rule matters: the
expression vocabulary is earned from the fns and checks the ported
domains actually wrote, and grows only on recorded friction.

Built on one thesis: **a pure function of declared inputs whose body
the fingerprint cannot read is a declaration wearing code's clothes.**
`Derived(expr=)` and `guard.expr()` say the same functions as
JSON-serializable trees; the fingerprint stores the tree itself, so a
semantic change is holdable at `proposed`, pilotable per-population,
blast-radius-measurable, and grandfathered *exactly* — with zero Python
deployed.

## Map

| Area | Where | The one thing to know |
| --- | --- | --- |
| The language (§1) | `waymark8/core/expr.py` | Frozen dataclass nodes, `eq=False` (semantic equality is wire equality); `to_wire`/`from_wire` round-trip exactly; `from_wire` refuses unknown nodes loudly. Vocabulary: literals, `Num` decimal literals, `Var`, item `Get`, cmp/bool/arith, `Days`, `DateOf`, min/max, `IsSet`, all/any/count/sum quantifiers |
| Two scopes (§1, §4) | `expr.check_derived_expr` / `check_guard_expr` | derived exprs speak `Var` names bound from `over=` (`Clock` → `"now"`, edge input → `"kind.field"`, `_describe_input`); guard exprs speak roles (`E.data`/`E.input`/`E.now`) — each refuses the other's references at import |
| The third arm (§2) | `core/derived.py` (`Derived(expr=)`, `DerivedSpec.expr`) | exactly-one-of fn/within/expr; `apply()` binds args to names and evaluates; ambiguous over-names refused when an expr binds them; `Count`/`Sum` are expression-bodied now |
| Fingerprint (§2) | `core/fingerprint.py` (`_derived_fp`, `_guard_fp`) | `derived.<fact>.expr` = the wire tree (emitted only when declared); an expr guard's `check` is `None` and `expr`/`vars_exprs` carry trees — hashing the compiled closure would claim every expr guard shares one body |
| The gate (§3) | `fingerprint._DATA_LAW_PATH` | `derived.<fact>.expr…` paths are data_law; an `over=` reshape is NOT (it changes the invalidation map, which is built from resident declarations) and any fn/check hash change keeps its 7.0 fate |
| The overlay (§3) | `server/derived.py` (`LawOverride.expr`, `_overlaid`) | the overlay carries the stored revision's semantic arm verbatim: `expr` → evaluate that tree (fn/tolerance cleared); `tolerance` → that arm; neither → parameters only, which is the surviving (fn-confined) deviation #6 |
| Overlay source | `server/definitions.py` (`_overrides_for`) | one added line: `expr=entry.get("expr")` — `_overrides_all` (grandfathered/piloted laws) routes through it, so every consumer learned to read trees at once |
| Wire hint | `Derived.mark()` → `x-derived.expr` | the generic UI can show what a derivation IS; stripped from fingerprinted schema copies (`_strip_derived_marks`, unchanged) — `derived.<fact>.expr` stays canonical |
| Guard sugar (§4) | `core/guards.py` (`_GuardFactory.expr`) | `judges` derives from the when-tree's `E.input()` refs, `reads=("now",)` from clock use — the tree IS the declaration, nothing sniffed; `vars=` takes name→expr garnish (evaluated on refusal, failures swallowed); `becomes_available_at` stays a callable (scheduling garnish) |
| Dogfood | `mealplan8/` + `tests/waymark8_dogfood/` + `--waymark8` (root conftest) | forked from mealplan7; every derivation is an expression; `plan_started`/`calendar_clear` are `guard.expr`; `meal_is_listed`/`plan_is_planned` stay `check=` — the recorded §5 residue (verdicts that READ) |
| Proofs | `tests/waymark8/test_expr.py`, `test_expr_law.py` | the admission test end to end: hold-serves-the-current-tree, pilot-per-population, grandfather-evaluates-its-own-tree, and the fn contrast (still `code_or_shape`) |

## Semantics pinned by tests (the lambda parity contract)

- **Tolerance parity**: a float promotes through `Decimal(str(x))` at
  the comparison — the stored value's own float error is kept (it is
  the stored truth); `None` fails every ordering claim (`Tolerance`'s
  None→False, generalized).
- **Arithmetic over `None` is `None`**; `eq`/`ne` compare plainly.
- **Quantifier empties are Python's**: `all` → True, `any` → False,
  `count`/`sum` → 0; `sum` replaces a `None` item value with
  `default=0` (the `v or 0` idiom, said honestly).
- **`E.date(...)`** coerces a datetime through UTC to a calendar date —
  the `now.date()` half of every clock-vs-date gate.
- **Formatting stops revising the law**: two spellings that build the
  same tree fingerprint identically (`test_two_spellings_one_law`).

## Deviations / judgment calls (deliberate, tested)

1. **`Count`/`Sum` re-founded on expressions.** Every kind using them
   re-fingerprints relative to waymark7 — irrelevant across packages
   (v8 stores are fresh), and it makes library rollups data-law end to
   end.
2. **An `over=` reshape stays `code_or_shape`** even when both arms are
   expressions: the maintainer's reverse/invalidation maps are built
   from resident declarations, and an overlay that changed the input
   set would serve facts nobody recomputes. Tested explicitly.
3. **Guard exprs are fingerprint-data but not row-law.** Judgment diffs
   classify exactly as in 7.0; the guard overlay (render/invoke reading
   stored machines per row) is the design's named punt. What ships is
   the semantic diff and the shrinking `check=` residue.
4. **`vars=` garnish**: expressions on `guard.expr` (enumerable values),
   still lambdas on `Derived` (string-building — prose is advertisement,
   design §5). A derived fact's expr change with untouched garnish is
   pure data-law; touching the garnish lambda adds an advertisement
   path and honestly breaks the hold (render reads resident objects).
5. **`Get` on a field named `of`/`attr`** would collide with the node's
   own dataclass fields (`E.it.of` returns the child expr, not a Get).
   No dogfood model has such fields; recorded, not defended.

## Gotchas carried forward (learned across v5–v8)

- **Postgres port**: tests use the docker container on **5433**; never
  point a dev server at it; never run two xdist pytest runs
  concurrently.
- State factories that defer jobs must await them; conformance
  factories mint fresh rows per call — never cache an id.
- The clock sweep still does not chain cross-kind; the backfill/restamp
  paths do not recompute cross-kind dependents (recorded since v6/v7;
  no dogfood shape hits either).

## Explicit residue in the dogfood (design §5, on purpose)

- `meal_is_listed` / `plan_is_planned` — cross-resource reads; the punt
  suspects a *declaration* (a Ref-state acceptance set), not a read
  node.
- `accepts=` set builders, `Derived(vars=)` string garnish, `flips_at`,
  `_start_of` (becomes_available_at), upcasts, handlers — all named in
  the design's §5 with their reasons.

## Status

| Phase | What | State |
| --- | --- | --- |
| 0 | Fork `waymark7/` → `waymark8/` | done — byte-identical modulo rename; spot-checks green both sides |
| 1 | The expression language + Derived/Guard/fingerprint/overlay wiring | done — `test_expr.py` (21) green; targeted framework suites (derived, definition, populations, blast radius, backfill, adoption, supersede, related, clock, lifecycle — 75) green |
| 2 | The admission test | done — `test_expr_law.py`: hold, pilot, grandfather-exactly, fn contrast |
| 3 | mealplan8 dogfood | done — law converted to expressions; `waymark8 check` clean; `tests/waymark8_dogfood` green; `--waymark8` conformance wired (root conftest; `make conformance8`) |
