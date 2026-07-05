# Waymark 2.0 — design

A redesign of Waymark that starts from what v0.1 learned the hard way.
Companion documents: `implementation-notes.md` (what v0.1 shipped and where
it strained), `usability-roadmap.md` (the doctrine and the unshipped rules).

## Why 2.0

v0.1's history is three layers — core, usability program, collab — and the
diff tells one story: **everywhere the server declared what it knew, the
later layers snapped in; everywhere we inferred it, we paid.** Guard bodies
were opaque, so we AST-scanned them (`_scan_dependencies`) and shipped five
waiver tokens for the false positives. References were a naming convention,
so `opaque_ref` guesses. The draft was two booleans, so a `"*"` principal
sentinel threads through five call sites. The envelope had no field-level
concurrency, so the collab UI improvised last-writer-wins in a form handler
and needed a patch to stop dropping remote edits. Render claimed purity it
no longer had, defended by a swallowed exception.

Meanwhile the parts that were declarations from day one — one guard as both
advertisement and enforcement, every write through one invoker, conformance
parametrized from the registry — never churned.

2.0 is that observation made law:

> **Nothing about a resource is inferred that the definition could have
> declared. Every declaration is used at least twice** — once to enforce,
> once to advertise/verify — **so it cannot silently rot.**

## What carries over unchanged

These survived three layers without churn and are kept verbatim:

- The envelope shape: `state`/`data`/`actions`/`unavailable`/`links` as the
  only actionable namespaces; `display` quarantined; `summary` budgeted.
- Uniform invocation: reads are GET on `links`, writes are POST on `actions`.
- The single invoker: every write — create, action, bulk item, cascade,
  draft drain — is one code path.
- Idempotency replay before the state check; natural replay for idempotent
  actions.
- The transition log as audit trail + outbox + idempotency anchor + event
  stream, with `actor.type ∈ {human, agent, system}`.
- Errors are hypermedia (RFC 9457 + `actions` + fresh `resource`).
- The drain rule: effort may be spent off-wire; it may never be stranded
  off-wire.
- The generic clients (HTML, agent, CLI) that consume only the envelope and
  invent no affordances; agent exit codes 2/3/4.
- Conformance as the proof that wire format, enforcement, audit, events, and
  safety semantics agree with the definition module.

Everything below is what changes.

---

# 1. Guards are declarations

The v1 scar: the usability program needed to know, per guard, which input
fields it judges, whether it reads other state, and what values it would
accept — and recovered all three by inspecting guard *source*. Fragile
(lambdas, helpers, aliased imports, composites all defeat it), and the
shipped fix (`admits=`) made apps write each acceptance set twice: once as
the advertisement, again as the check.

In 2.0 a guard is a **data object**, and the callable is the last field of
it, not the whole of it:

```python
date_in_plan = Guard(
    judges=("date",),                      # input fields this guard grades
    reads=(),                              # other state it consults: Ref kinds, "services.x", "now"
    accepts=lambda r: {d.date for d in r.data.days},   # THE acceptance set
    explain="{date} is not a day of this plan ({first}–{last}).",
    vars=lambda r: {"first": r.data.days[0].date, "last": r.data.days[-1].date},
)
```

**One `accepts`, two consumers.** Render folds `accepts(r, ctx)` into the
advertised schema as an `enum`; the engine evaluates *membership in the same
set* as the enforcement. There is no separate body to drift. Guards that
need logic beyond set membership (cross-field relations, external calls)
declare `check=` instead of / alongside `accepts=` — but then the closure
rule below applies.

**The closure rule (definition-time error, not lint).** A guard whose
`judges` names an input field must give clients *something* to go on for
that field: an `accepts` set, a schema constraint (enum/const/format/range),
a declared relation (`relates=("start", "<=", "end")` → rendered as
`x-display.relation`), or an explicit `open=Judged(reason="…")`
acknowledgment. v1's `open_input` warning + waiver becomes a `TypeError` at
import with an escape hatch you must *write a sentence into*. The
schema-guard gap stops being a fuzzer's job and becomes unrepresentable.

**Composites compose metadata.** `all_of(a, b)` has
`judges = a.judges | b.judges`, intersects `accepts` per shared field, and
unions `reads`. v1's composites reported "unknowable."

**What this deletes:** `_scan_dependencies`, `_scan_deny_vars`, the
`open_input`/`altitude` AST heuristics, the `admits`-vs-body drift
conformance test, and most `@example_input` registrations (see §9) — the
suite can synthesize passing inputs from `accepts` directly.

# 2. References are typed

v1 detected references by the `{kind}_id` naming convention and granted
pickers via hand-written `x-display` dicts. 2.0 has one field type:

```python
class DayEntry(BaseModel):
    meal: Ref["meal"] | None = RefField(default=None, label="meal_name",
                                        pick=Query(state="on_list", theme="{item.theme}"))
```

One declaration generates: the wire `x-display {widget: "resource", kind,
label_field}` hint, the picker (with per-part `{item.*}` params, §3), a
`links` entry when the field is top-level and single-valued, the
denormalized label column behavior in collections, and the `dangling_ref`
conformance check (every rendered ref must resolve). The v1 lexical
heuristic survives only as the lint that tells you to use `Ref`. Raw ids on
the wire require `RefField(raw=True)` — the acknowledgment is in the type,
not a parallel waiver channel.

# 3. Actions are structured; placement is declared once

v1's `@action` grew to 24 flat keyword arguments, and `mealplan/plan.py`
needed five near-identical scoped actions each re-declaring
`scope=("days", "date")`. 2.0 groups the declaration into objects with
their own invariants, and hoists shared placement to the resource:

```python
class MealPlan(Resource):
    days = PartScope("days", key="date")        # declared once; renders `parts.days`

    @action(from_=S.DRAFT, to=S.DRAFT,
            place=days,                          # per-item; key field auto-const
            guards=[meal_matches_theme],
            safety=Safety(idempotent=True, reversible=True))
    async def assign_meal(self, inp: AssignInput, ctx: Ctx) -> None: ...

    @action(from_=S.DRAFT, to=S.DRAFT,
            edit=Edit(prefill=("notes",), draft=DraftPolicy(shared=True, live=True)),
            safety=Safety(idempotent=True))
    async def update_notes(self, inp: NotesInput, ctx: Ctx) -> None: ...
```

- `Safety(idempotent, reversible, confirm, fence)` — one object, and its
  internal invariants live in its constructor: `confirm=True` requires a
  consequence description (v1's planned `blind_confirm`); irreversible +
  unconfirmed + state-leaving requires `Safety(one_way=Acknowledged("…"))`
  (v1's planned `one_way_door`).
- `Edit(prefill, draft)` — an edit-shaped action is one concept. `Edit`
  implies `fence` (If-Match) unless explicitly waived with a reason; prefill
  truth is a constructor-level contract, not a pair of separate warnings
  (`blank_edit`/`unfenced_edit` become unrepresentable).
- `place=PartScope(...)` — the scope is a named object; drafts are keyed
  per `(action, part key)` (fixing v1's "don't combine scope with draft"
  caveat); the key field is `const` per part; `Ref` picker `Query` params
  may template over `{item.*}`.
- `bulk=Bulk(atomic=False, max_items=500, defer_over=100)`.
- `DraftPolicy(shared, live)` — collab is a property of the draft policy
  (see §4); `live=True` without a draft cannot be expressed at all.

Wire format: unchanged in shape — `parts` remains a refinement, top-level
`actions` remains the complete truth. What changes is that every rendered
action also carries its computed **demand class** (§10):
`"effort": "selection"`.

# 4. Drafts are resources; collab is a draft policy

The v1 scar: shared drafts were retrofitted with a `"*"` principal sentinel
special-cased in engine, invoker, and router; the WS drain and the plain
draft-PUT were two parallel writers to the same row.

In 2.0 the draft is a first-class engine-owned sub-resource — an envelope
like everything else:

```json
{
  "waymark": "2", "kind": "draft",
  "self": "/api/plans/88/-/update_notes/draft",
  "state": "open",
  "data": {
    "for_action": "update_notes",
    "base_version": 7,
    "values": { "notes": "half-written…" },
    "revs":   { "notes": 14 },
    "authors": { "notes": { "id": "dana", "at": "2026-07-04T06:32:23Z" } }
  },
  "actions": { "save": {"…":"…"}, "discard": {"…":"…"} },
  "links":   { "channel": { "href": "…/draft/collab", "kind": "relay",
                            "summary": "Live co-editing (waymark-relay/2)" } },
  "meta": { "version": 14, "etag": "W/\"draft-…-v14\"" }
}
```

- **Ownership is scoping, not a sentinel.** A draft row is keyed
  `(resource, action, part_key?, audience)` where
  `audience ∈ {principal p, shared}` comes from the `DraftPolicy` — decided
  at declaration time, invisible to the write path. The invoker deletes "the
  draft this action would consume"; it never chooses a principal string.
- **One write path.** `save` on the draft resource *is* the drain target.
  The relay's accepted frames, the plain PUT, and the generic UI's autosave
  all invoke the same draft transition through the same invoker — it lands
  in the transition log like every other write, which also gives drafts an
  audit trail and makes the drain rule *observable* rather than promised.
- **Lifecycle is a state machine** (`open → consumed | discarded`), so room
  closure is an ordinary transition event on the bus (§7) — the router no
  longer reaches into live sockets after a 200.
- The parent action's entry advertises the draft as a **link with a
  summary** (author, staleness, demand), not an inlined blob; clients GET
  the draft when opening the form, which v1 already required in practice.

# 5. Field-level concurrency is in the wire format

The v1 scar: commit 41b0eac — remote edits dropped when a field was
focused, patched with a client-side `dirty` set and caret juggling, because
nothing on the wire versioned a *field*.

2.0 keeps the document fence (`If-Match` on invoke) and adds a **per-field
revision** to drafts, as shown above (`revs`). The reference protocol
becomes `waymark-relay/2`:

```
client → { "type": "update", "field": "notes", "value": "…", "base_rev": 14 }
server → { "type": "saved",  "field": "notes", "rev": 15 }            (to author)
server → { "type": "update", "field": "notes", "value": "…", "rev": 15,
           "actor": {...} }                                            (to room)
server → { "type": "reject", "field": "notes", "rev": 17, "value": "…" }
                                          (base_rev stale: here is the truth)
```

The merge discipline is deliberately modest — server-ordered per-field
last-write-wins with explicit staleness rejection — but it is *declared*,
enforced server-side, and testable, instead of emergent from two debounce
timers. A client applies a remote update to a focused field iff its own
`base_rev` is not ahead — the 41b0eac semantics, derived from the protocol
instead of improvised against it. Character-level merge (CRDT/OT) remains a
different `protocol` token; the drain rule and the `revs` map bind those
too.

# 6. Rendering: project, then resolve

The v1 scar: `render.py` still says "pure projection" while `accepts`
functions read other resources through an engine ctx, held together by
`except Exception: continue` — silent under-advertisement when run pure.

2.0 names the two stages:

```
project(instance, principal, depth, now)      → Envelope     # pure, total, fast
resolve(envelope, ctx)                        → Envelope     # + ctx-derived refinements
```

- `project` uses only the instance: schema, prefill defaults, `accepts`
  sets whose `reads=()` (document-derivable), parts, safety, display. It is
  the testing/snapshot/cache contract and can never fail for lack of a ctx.
- `resolve` applies exactly the refinements whose guards declared
  `reads≠()`: cross-resource `accepts` enums, picker facets, draft links.
  It has the ctx *because the guard's declaration said it needs one* — the
  engine wires it from `reads`, and a missing dependency is a loud
  configuration error at assembly, not a swallowed exception at render.
- The wire is identical either way; `resolve` only ever *tightens*. GET
  serves `resolve∘project`; conformance runs both and asserts the
  tightening relation (resolved schemas ⊆ projected schemas).

# 7. One bus

v1 built cross-process fan-out three times: SSE got LISTEN/NOTIFY with a
poll fallback, collab rooms got an in-process dict and a "pin to one
worker" caveat, the rate limiter got a per-process counter.

2.0 has one seam:

```python
class Bus(Protocol):
    async def publish(self, channel: str, payload: bytes) -> None
    def subscribe(self, channel: str) -> AsyncIterator[bytes]
```

Two shipped implementations — in-process (dev/test) and Postgres
LISTEN/NOTIFY over the transition log (prod) — and four consumers: the SSE
dispatcher, collab room relay, deferred-job progress, and the shared rate
limiter's coordination. Multi-worker collab stops being a caveat because
collab was never given private plumbing to outgrow.

# 8. Storage: migrations are the contract, not a promise

v1's "largest unpaid promise" was Alembic emission — deferred, then
compounding, because filterable fields become generated columns and every
schema evolution on a deployed app hits it.

2.0 makes the declared schema an artifact with a diff:

- `waymark migrate` snapshots the registry (tables, JSONB shape versions,
  generated columns, indexes) and emits the Alembic revision for the delta.
  `create_all` survives only behind `--dev`.
- Conformance includes a **migration round-trip**: empty DB → all
  migrations → schema equals a fresh snapshot. The promise can't silently
  rot because CI replays it.
- Datetime generated columns use a shipped `IMMUTABLE` conversion function
  from day one, so `*_after` filters actually use their indexes (v1 knew
  the fix and deferred it).
- `data` rows carry a `shape` version; resources may declare `upcast`
  functions for lazy read-time migration of JSONB, so most Data-model edits
  need no table migration at all.

# 9. Conformance: derive more, register less

v1 needed, per app: a hand-written transition-walking `@state_factory` per
resource plus ~11 `@example_input` registrations — scaffolding that mostly
re-states what guards already knew but didn't declare.

With guards as declarations (§1), the suite synthesizes most of it:

- **Input synthesis from `accepts`.** For every action, valid input =
  schema sample ∩ each guard's acceptance set (the sets are callable
  against the factory instance). `@example_input` remains only for
  `check=`-style guards with acknowledged open judgment — and the closure
  rule keeps those rare and labeled.
- **Reachability from the machine.** `waymark check` computes a path from
  `initial` to every state; the default state factory *walks it* using
  synthesized inputs. `@state_factory` becomes the override for resources
  whose paths need semantic setup (seeded siblings, clock control), not the
  baseline tax. The suite's `ctx.clock` is injectable so date-dependent
  guards (v1's `PLAN_START` constants) are controlled, not hard-coded.
- The v1 suite's collab special-case branches disappear: drafts are
  resources (§4), so `draft_protection` and `draft_drain` are ordinary
  resource conformance over `kind: draft` plus one relay-protocol test per
  declared protocol token.

# 10. The usability doctrine is Part 0, not layer two

v1 discovered its doctrine after shipping: *everything the server knows,
the human must be able to see without decoding — expressed as obligations
on server-emitted data, never guidelines for clients.* In 2.0 this is spec
Part 0, and its main instrument is computed, not policed.

**Demand classes are derived and rendered.** From any action declaration
the framework computes the demand class — `traversal | assent | selection |
recall | composition` — and renders it (`"effort": …`) so clients, agents,
and checks share the vocabulary. The **knowledge floor** rule runs at
import: if a declaration demands effort above what the server provably
knows (a `recall` field whose guard has a computable `accepts` → should be
`selection`; a `composition` field without a draft → should be durable),
that's a warning with the demotion named. v1's checks map as follows:

| v1 warning | 2.0 fate |
|---|---|
| `open_input` | unrepresentable (closure rule, §1) |
| `altitude` | unrepresentable when `place=` is used; floor check otherwise |
| `blank_edit`, `unfenced_edit` | unrepresentable (`Edit` constructor, §3) |
| `large_effort` | floor check (`composition` without `DraftPolicy`) |
| `long_text` | kept as-is (a wire budget, still the right tool) |
| `opaque_ref` | lint that says "use `Ref`" (§2) |
| planned `one_way_door`, `blind_confirm` | `Safety` constructor invariants (§3) |
| planned `token_prose`, `orientation` | conformance from day one — the suite renders every state and refusal; asserting no UUIDs/snake_case/unresolved placeholders in prose is cheap and ships in the box |

The roadmap's Tier-4 items (the text-only comprehension judge, regret
telemetry from the transition log's human/agent split) remain post-1.0 of
2.0 — they are measurements, and 2.0's job is to leave them more signal:
demand classes in the envelope and problem/dry-run outcomes in the log give
`usability-report` its columns for free.

# 11. Principals are transport-neutral

v1 resolved principals from headers, then bent (query params) when browsers
couldn't set headers on WebSocket upgrades — and shipped collab gating on
resource visibility rather than per-principal affordance.

2.0 declares one resolver interface fed by an opaque **credential**
extracted per transport (header, cookie, WS subprotocol / first frame), and
one rule: **joining a draft channel requires the parent action to render in
`actions` for that principal** — checked at join and re-checked on the
bus's affordance-changing transitions. The dev principal is one
implementation of the interface, not the interface.

---

## Wire format delta (v1 → v2)

| Surface | v1 | v2 |
|---|---|---|
| `waymark` | `"1"` | `"2"` |
| Action entry | — | `+ effort` (computed demand class) |
| Draft | inlined blob on the action | sub-resource envelope with `revs`/`authors`; action links to it |
| Relay | `waymark-relay/1`, whole-values frames | `waymark-relay/2`, per-field `base_rev`/`rev`/`reject` |
| Reference fields | ad-hoc `x-display` | emitted by `Ref` (same wire hint, now guaranteed + `label_field` consistent) |
| Field relations | — | `x-display.relation` from `relates=` |
| Everything else | | unchanged — envelope, parts, unavailable, links, events, problems, bulk, discovery |

A v1 generic client pointed at a v2 server loses only the new refinements;
the deliberate conservatism is the point — 2.0 spends its novelty on the
*definition* side, where v1 actually hurt.

## Migration sketch (v1 apps)

Mechanical, mostly local to definition modules: `@guard` functions become
`Guard(...)` objects (the `admits=` pair collapses into `accepts=`); flat
`@action` kwargs regroup into `Safety`/`Edit`/`Bulk`/`place=`; `{kind}_id`
fields become `Ref[...]`. The wire compatibility above means clients
migrate independently. A `waymark upgrade` codemod can do the kwarg
regrouping and flag every guard whose body it cannot express as `accepts` —
which is exactly the list of guards that were never honest about what they
judge.

## Explicit 2.0 punts

Punted, with the v1 lesson applied — punt things that don't compound:

- **i18n** — still deferred, but every human string now flows through one
  emission point (`explain` templates, `display`, summaries), so
  `extract-messages` is a walk, not an excavation.
- **CRDT/OT merge** — the seam (`protocol` tokens + `revs` + drain rule) is
  contract; the fancy merge is an implementation someone can ship without
  touching the format.
- **Facet counts beyond `state`** — same as v1; cheap, cosmetic, safe to
  defer.
- Not punted, on principle: migrations (§8) and multi-worker liveness (§7).
  v1 proved those two compound.
