# TODO — Mirror-aware conformance (waymark7 7.x extension)

**Status:** not started (the agent assigned to it died on transient API
529s before writing anything; nothing of it is on disk). This is a
self-contained brief for a fresh instance. Its sibling — the ledger7
Fund mirror that *motivated* this — is being finished separately; see the
"Relationship to the Fund work" section.

**Classification (settled):** this is an **extension, not waymark8.** It
changes no runtime law, no wire format, no envelope — only the
conformance walker's reach. The Mirror runtime (pull-through-on-read,
system-only sync transitions) already ships and is correct; the harness
just can't yet verify a resource shape the runtime already sanctions.
Work lives in `waymark7/testing/` (+ a proof test), with at most one
inert, default-off seam line in `waymark7/server/external.py`.

## The problem

The conformance walker (`waymark7/testing/conformance.py`) rests on two
assumptions that a `Mirror` resource (`waymark7/server/external.py`)
deliberately breaks — and it breaks **both at once**, which nothing else
in the system does:

1. **Reads are pure.** The walker's loop is "reach state S, GET it,
   assert the envelope shows S with the right actions." But a Mirror
   does pull-through-on-read: `refresh_mirror` (external.py:349) fires on
   a GET past `ttl_seconds` (or in a non-fresh state), calls the
   adapter's `pull`, and applies an `observe_external` / `mark_unreachable`
   **system transition**. So the walker sets up `unreachable`, GETs to
   check it, and the read itself heals it to `fresh` — the assertion
   fails because observing changed the thing observed.
2. **State comes from principal-invoked transitions.** The sync states
   (`fresh/stale/conflicted/unreachable`) are entered only through
   `system_only`-guarded transitions (`observe_external`, `mark_stale`,
   `mark_unreachable`, `mark_conflicted`, `reconcile` — external.py
   ~275–330), invokable only by `SYSTEM_OBSERVER` (external.py:209), and
   advertised to no walker principal. So the walker can neither *drive*
   the resource into those states through its action walk nor reach them
   any other declared way.

Net: the sync states are **un-drivable** (no principal may enter them)
*and* **un-observable** (reading destroys them). `job`/`attachment` have
some system transitions but a principal-driven primary lifecycle and pure
reads, so the walker's existing hidden/system-action skip handles them.
A pure Mirror is almost entirely system/external lifecycle, so that skip
isn't enough.

## What to build

Detect Mirror kinds (`issubclass(cls, Mirror)`, or robustly:
`getattr(cls, "adapter", None) is not None` and the MirrorMeta sync
machine is present) and give the walker a Mirror mode:

1. **Keep reads pure during the walk.** Two honest options — pick and
   justify in the notes:
   - **(a) fresh-only, by construction.** Enroll Mirror kinds with a
     factory that produces a `fresh` instance against an adapter pinned
     deterministically *up* and inside TTL, so `refresh_mirror` is a
     genuine no-op (etag unchanged → no transition). Simplest; covers
     only the `fresh` state.
   - **(b) refresh-suppression seam (preferred if clean).** A
     walker-scoped, **default-OFF** switch that suppresses pull-through
     for the duration of a conformance read, so the walker observes the
     stored state deterministically in *every* sync state. The ONLY
     permitted runtime-file change: one inert early-return in
     `refresh_mirror` gated on a test-set attribute (e.g.
     `if getattr(engine, "_suppress_mirror_refresh", False): return
     instance`), which the walker's engine fixture sets. Production
     behavior is unchanged and a proof test MUST assert the seam is
     default-off (a normal engine still pulls through on read). If (b)
     crosses your "testing-only" line, fall back to (a) and record it.
2. **Stage sync states via the system actor.** Supply the walker a
   Mirror factory that places an instance in each requested sync state
   using `SYSTEM_OBSERVER` + the declared sync transitions — so
   `make_state("unreachable")` etc. work for the representation /
   orientation / collection tests. The harness should provide this
   automatically for Mirror kinds (an app shouldn't hand-write one per
   mirror, though it MAY override). `SkipState` any sync state that
   genuinely can't be staged deterministically, with a clear reason —
   don't fake it.
3. **Exempt system-only sync transitions from the principal×action
   matrix**, the way hidden system actions already are:
   `test_transition_truth_available` must not demand any principal can
   invoke them; `test_transition_truth_unavailable` / `test_input_contract`
   treat them as system-only (in neither `actions` nor necessarily
   `unavailable`). Reuse the existing hidden/system skip; extend if
   Mirror's shape differs.
4. **Determinism.** No dependence on a wall-clock TTL crossing during a
   case; pin the clock via the existing test-clock mechanism (grep how
   conformance controls `ctx.now` / the engine clock).

## Files

- `waymark7/testing/conformance.py`, `factories.py` (and `walker.py` /
  `pytest_plugin.py` only if the enrollment path needs it).
- At most one default-off line in `waymark7/server/external.py`
  (`refresh_mirror`) — only for option (b).
- New `tests/waymark7/test_mirror_conformance.py`: a minimal `Mirror`
  subclass + scriptable in-memory adapter (crib `tests/waymark7/
  test_external.py`), enrolled through the Mirror-aware path. Assert: the
  walker checks representation in `fresh` without the read flipping
  state; (if option b) reaches and checks `unreachable`/`stale`
  deterministically; does not fail on the system-only transitions;
  `Discover`-minted instances don't break the walk; and — critically —
  the suppression seam is **default-off** (a plain engine still refreshes
  on read).

Do NOT touch: `waymark7/core/`, other `waymark7/server/` files, any
other waymark version, `ledger7/`, or root `conftest.py`.

## Reference points (verified)

- `waymark7/server/external.py`: `Mirror` (~229), `MirrorMeta`,
  `MirrorAdapter` Protocol (`pull`/`push`), `refresh_mirror` (349),
  `SYSTEM_OBSERVER` (209), `system_only` (219), the sync machine
  (~275–330), `Discover` (~560, `every`/`query`; the clock sweep mints a
  resource per new external id carrying only `{"external_id": id}`).
- `waymark7/testing/conformance.py`: `make`/`make_state`,
  `principals_with`, and the case tests (`test_representation`,
  `test_transition_truth_available/unavailable`, `test_input_contract`,
  `test_safety_*`, `test_collection_contract`, `test_replay_history`,
  `test_orientation`, `test_events`).
- `waymark7/testing/factories.py`: `_FACTORIES`, `_RESOURCES`,
  `conformance_resource`, `state_factory`, `SkipState`, and the existing
  hidden/system-action skip (grep `hide`, `system`, `SkipState`) — the
  precedent that let ledger's hidden `sync_beacon_balance` be skipped.
- `tests/waymark7/test_external.py`, `test_authored.py` (uses
  `Discover`): the working Mirror + adapter declaration shapes.

## Gates (Postgres on :5433, NOT 5432)

1. `WAYMARK_TEST_DSN="postgresql+asyncpg://ckopsa@localhost:5433/waymark_test" uv run pytest tests/waymark7/test_mirror_conformance.py tests/waymark7/test_external.py -q` — green.
2. `... uv run pytest tests/waymark7 -n auto -q` — whole framework suite still green (you changed the harness; prove nothing else broke).
3. `... uv run pytest --waymark7 -n auto -q` — mealplan7 conformance still green. ledger7 now has a `fund` Mirror; once this extension lands, ledger7's fund enrollment can drop its minimal-only workaround and walk the sync states too.
4. `... uv run pytest tests/waymark6 -n auto -q` — v6 untouched.

## Documentation

Append a section to `docs/waymark7-notes.md` (house voice):
"Mirror-aware conformance (7.x extension)" — the two walker assumptions
Mirror breaks, which read-purity option you took (a or b) and why, how
sync states are staged (system-actor factory), the default-off
suppression seam if any, and that this closes the seam the ledger7
Fund mirror recorded.

## Relationship to the Fund work

The ledger7 Fund kind (`docs/todo/fund-mirror.md` if that exists, or
already committed) is the first real Mirror in a dogfood and is what
surfaced this seam. It enrolls `fund` **minimally** — a `fresh` fund
against a pinned-up fake adapter, system-only transitions skipped — and
records "full Mirror sync-state conformance is a 7.x harness extension"
as a pending seam. When THIS extension lands, revisit ledger7's
`fund` enrollment (in root `conftest.py`, the `waymark7_engine` block)
and upgrade it from minimal to full sync-state walking, then delete the
pending-seam note.
