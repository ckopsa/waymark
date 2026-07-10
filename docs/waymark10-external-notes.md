# waymark10 — batch E notes (external completeness)

Destined for the design doc; the maintainer folds these. Three
deliverables landed: RRULE expansion in the mealplan event adapter,
the predecessor resolver (design E7), and Mirror push/write-back with
the conflicted-state machine (waymark9 `push_mirror` + reconcile, at
a recorded scope).

## RRULE expansion (mealplan10.event-source)

The pure expander `expand-rrule` closes mealplan10's biggest recorded
deviation from mealplan9 (which leaned on `recurring_ical_events`).
The profile implemented honestly — the one real family calendars use:

- `FREQ=DAILY/WEEKLY/MONTHLY`, `INTERVAL`
- `BYDAY` (weekly only, plain two-letter codes; WKST=MO grid)
- a single `BYMONTHDAY` (monthly); otherwise DTSTART's day-of-month,
  months lacking that day skipped (RFC 5545's rule — and skipped
  months do not count against COUNT)
- `UNTIL` (inclusive) or `COUNT` — COUNT limits the generated set
  BEFORE the window filter and BEFORE EXDATE removal (RFC set
  semantics)
- `EXDATE`, accumulated across repeated lines and comma lists

Outside the profile — `BYSETPOS`, `FREQ=YEARLY`, positional BYDAY
(`2TU`), BYDAY on MONTHLY, multiple BYMONTHDAYs, non-Monday WKST on a
multi-week interval, and `RDATE` (which ADDS occurrences we would
silently lose) — the boundary is recorded, never a crash and never a
silent partial expansion: THAT event is skipped whole with a `*err*`
warning naming the offending part and the full rule. The grammar is
case-insensitive.

Identity is waymark9's: every occurrence is its own mirrored resource,
`external_id = {uid}@{date}` — exactly what the plan's overlap
predicate needs. `feed-occurrences` is the pure heart (iCal body +
window in, `{external-id doc}` out); the HTTP fetch stays a thin
shell. FakeEvents gained `seed-recurring!`, which drives the SAME
expander, so family-week-style tests put a weekly recital on the fake
calendar and prove plan conflicts flip on exactly the occurrence
weeks (the EXDATE'd week stays clear).

Recorded simplification: occurrences follow the rule grid — a DTSTART
off its own BYDAY grid (which real calendars don't emit) contributes
no extra occurrence.

## The predecessor resolver (design E7)

Period chaining is data, not date arithmetic. A `:waymark/ref` schema
entry declaring

    [:previous_plan {:optional true :kind :plan
                     :predecessor {:order :start_date
                                   :partition :ledger}}   ; partition optional
     [:maybe :waymark/ref]]

resolves at CREATE when the body left it blank: the newest existing
row of the target kind by `:order` — ties break toward the smallest
id (search-rows' id tiebreak, so resolution never flaps) — within the
same `:partition` value when declared; no sibling → nil. A supplied
body value always wins. The waymark9 ≤-seeding survives: when the new
row already carries its own `:order` value, only siblings at or
before it qualify — a backdated period links backward, never forward;
a blank order value (an `:on-create` default not yet applied) takes
the newest sibling overall.

Where it lives: `waymark10.server.predecessor` owns all machinery;
`create!` runs it at ONE surgical, documented seam — after decode,
before `:on-create` (waymark9 invoke.py's step order, so the hook may
read the resolved sibling for carry-forward). That is the whole
invoke.clj diff: one require, one threading line with its comment.

Recorded boundaries:

- `:order` must be promoted on the TARGET kind (filterable or
  sortable — the resolving query orders by its generated column);
  refused loudly at create. waymark9 checked this at assembly
  (`_check_predecessor`); the v10 assembly check is a named punt.
- a declared `:partition` whose value is blank on the new row
  resolves nothing — half a partition key must not link across
  partitions.
- mealplan10 wires `plan.previous_plan {:order :start_date}`; the
  schema projection already carries `:predecessor` into `x-ref`.

## Mirror push/write-back and the conflicted state

`sync-states` is now `fresh / stale / unreachable / conflicted` —
waymark9's full machine. The adapter protocol gained
`(push adapter external-id document) → new-etag`.

A mirror may declare `{:push-on-write true}` in its mirror spec, and
ONLY then may it declare its own domain actions (local writes; moves
between non-conflicted sync states — the machine stays the sync
machine, domain state stays in data; names may not shadow the sync
doors). After such a write commits:

- push succeeds → `observe_external` (system actor) stamps the new
  etag + synced_at; the response tells the post-push truth.
- push fails → `mark_conflicted` with the adapter's own words: the
  LOCAL document stands, `conflict_reason` renders, and the state
  tells the truth about the gap. At this scope every push failure is
  the conflicted state (unreachable-on-push vs true etag conflict is
  a recorded non-distinction; the resolve covers both).

`resolve_conflict` (from `conflicted` → `fresh`, confirm required) is
the ONE human door on the sync machine — never a silent
last-writer-wins: `keep=remote` re-pulls the authority's truth and
adopts it; `keep=local` re-pushes ours and adopts the new etag. The
adapter call runs inside the invoke — the same recorded impurity
waymark9's reconcile carried; an unreachable adapter fails the invoke
loudly and the row stays conflicted. A conflicted row never
pull-through-refreshes and takes no further local writes: leaving
conflicted is a person's move, not the clock's.

Wiring: the push pass rides the engine's post-commit `:maintain` hook
via `(mirror/with-push eng)` — an embedding that serves a
push-on-write mirror wraps its engine before building the handler.
Recorded punt: engine.clj's boot does not auto-wire it (no enrolled
app declares one; mealplan's calendar stays pull-only — its adapters
implement `push` only to refuse loudly / to serve tests). Creates
never push (a locally-minted row reaching the authority is a named
punt with the cursors).

## Remaining punts, named

- RRULE: `BYSETPOS`, yearly rules, positional BYDAY, `RDATE`,
  non-Monday WKST on multi-week intervals — each skips its event with
  a warning naming it.
- Predecessor: the assembly-time promotion check (create-time refusal
  holds the line today); `pick`-style carry-forward of sibling fields
  stays the apps'.
- Mirror: per-kind discovery cursors, mirror webhooks/change feeds,
  per-field authority (AuthoredMeta), pushing locally-minted rows,
  auto-wiring `with-push` in the engine boot.
