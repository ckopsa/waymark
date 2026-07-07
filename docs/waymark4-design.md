# Waymark 4.0 — design

A redesign of Waymark that starts from what 3.0 learned the hard way.
Companion documents: `waymark3-design.md` (the 3.0 case),
`waymark3-design-enterprise.md` (the E1–E9 wave), `waymark3-notes.md` (what
3.0 shipped and where it deviated), and the six enterprise mapping exercises
(`waymark3-mapping-*.md`, `waymark3-stories/`), which are the evidence base
here the way the post-rebuild commit history was the evidence base for 3.0.

## Why 4.0

3.0's law — *every concern is exactly one of: a declaration, a resource, or
an event class on the bus* — held, and the enterprise wave proves it
lopsidedly. Every gap that could be closed by one of the three citizens
snapped in: uniqueness landed as `unique=` consumed by the migration, the
invoker, and the Problem (E2); attachments landed as an engine kind with a
two-phase state machine (E5) after three apps re-invented "three scripts, a
path convention, and a timing hack"; async egress landed as the `job` kind
with system-actor transitions (E6); period chaining landed as a declared
`Predecessor` resolved at create (E7).

And then the residue. Read across the six mappings, the strains that remain
are not six problems — they are one problem in six coats:

- `recon_difference`: one predicate, **four definitions, three tolerances**
  — Python at 1e-5, the browser formatter at 1e-2, JS truthiness, and a
  client-side gate (`waymark3-mapping-cash-recon.md` story 2).
- `overdue`: computed in **twelve parallel queries** — six filters, six
  counts — and it is a fact clients want to *watch*, but "step became
  overdue" is neither a transition (nothing wrote) nor an observation
  (nothing looked); 3.0's taxonomy has no class for the clock crossing a
  declared line (`waymark3-stories/deliverable-tracker.md` story 3).
- `variance_breach`: the app named after the predicate **shipped without
  it**, because in a hand-built stack a predicate is five edits
  (`waymark3-mapping-price-validation.md` story 2).
- Four-eyes: "anyone but the actor who did X" is a fact over the transition
  log. E3 made it *enforceable*; nothing makes it *projectable* — Sam sees
  an approve button that refuses him, the exact advertisement/enforcement
  drift the whole series exists to kill, shipped as a feature
  (`waymark3-stories/payouts.md` story 3).
- Parent-gated-on-children: "a wire cannot complete unless all its IFTs are
  terminal" judges a rollup over children and falls off the declaration
  cliff into prose `check=` (`waymark3-stories/payouts.md` story 5).
- Conditional demand: "if completing late, `client_caused` and a reason are
  required; otherwise forbidden" — requiredness governed by a runtime
  comparison, expressible only as refuse-after-submit
  (`waymark3-stories/deliverable-tracker.md` story 1).

Every survivor is a **derived fact**: a function of declared data — own
fields, owned children, `Ref`'d peers, the transition log, the clock — with
no declared home, so it is recomputed at every point of use and the
definitions drift. 3.0 named three citizens; the apps kept smuggling in a
fourth kind of thing, and it was always the computed fact. Two of the
E-mechanisms are the tell: E3 ships a log-derived fact as enforcement the
projector cannot see, and E4 ships rollups as a one-off aggregate pair
(count, sum) rather than the general thing seven sites sighted.

There is a second, smaller cluster, where the *act* outgrew the
single-resource transition the same way the fact outgrew the
single-resource field: the wire carve-out is "one business act, three
resources, a blob side effect" implemented as one big service method with
flushes and an uncompensated S3 copy
(`waymark3-mapping-payouts-fullstack.md` story 4); intake's bulk import
mints UUIDs in the browser and writes three tables non-atomically because
3.0 has no batch envelope (`waymark3-stories/intake.md` story 4).

2.0 unified advertisement and enforcement for *guards*. 3.0 did it for
*visibility*. 4.0 does it for *truth itself*:

> **Nothing is recomputed at a point of use that a declaration could have
> derived at the source. Every field's truth has exactly one declared
> origin — written by an action, derived by a named derivation, or authored
> by an external authority — and every consumer (render, filter, guard,
> visibility, event) reads that one definition. A fact defined in two
> places is a design error, not an implementation detail.**

The admission test for any 4.0 feature: can the fact be named once, with
declared inputs, such that the projector renders it, storage indexes it,
the invoker judges it, visibility consults it, and the bus announces its
flips — all from the one name? A feature that needs the same truth stated
in a second place fails.

## What carries over unchanged

These survived the enterprise wave without churn and are kept verbatim:

- The envelope: `state`/`data`/`actions`/`unavailable`/`links`; `display`
  quarantined; `summary` budgeted; `parts` as refinement; binding.
- Uniform invocation, the single invoker, idempotency replay before the
  state check; errors as hypermedia; the drain rule; discovery.
- Visibility as projection input (3.0 §1) — extended in §4 below, never
  bypassed.
- The route-class pipelines (3.0 §2) and the event taxonomy (3.0 §3) —
  the taxonomy gains one class (§3), the pipelines gain two route shapes
  (§6, §7); neither is replaced.
- Drafts as engine-owned sub-resources; `waymark-relay/2`; per-field
  `revs`/`authors`.
- Identity and grants (3.0 §9): `member`, `grant`, roles as declared
  `Visibility` templates, delegation as attenuation.
- `Service`, `subscription`, and the outbox-as-product (3.0 §10). `Mirror`
  is generalized (§8), not removed.
- The E-wave mechanisms E1 (warning-severity guards + `Waymark-Acknowledge`),
  E2 (uniqueness with the `existing` pointer), E5 (attachments), E6 (jobs
  and leasing), E7 (`Predecessor`), E9 (create guards). E3, E4, and E8 are
  *generalized* below — their behavior is kept, their one-off-ness is not.
- Migrations as snapshot-diffed revisions with the CI round-trip;
  conformance as the proof that wire, enforcement, audit, events, and
  safety agree with the definition module.

Everything below is what changes.

---

# 1. Truth declares its source

The organizing move, from which the rest follows. In 4.0 every field of a
resource has exactly one declared origin:

| origin | declared as | written by | rendered as |
|---|---|---|---|
| **written** | a plain field (today's default) | actions, through the invoker | `data.x` |
| **derived** | `Derived(...)` (§2) | the engine's derivation maintainer | `data.x`, schema `readOnly`, `x-source: derived` |
| **authored** | `Authored(by=service)` (§8) | the authority's adapter | `data.x`, schema `readOnly`, `x-source: authored` |

The origins are exclusive and checked at import: an action whose input
model names a derived or authored field is a `DefinitionError`, not a
runtime surprise. The three-tolerance drift becomes unrepresentable the
same way the schema-guard gap did in 2.0 — there is no second place to
write the fact, so there is no second definition to disagree with the
first.

The frame also retroactively names two things the engine already does:
the denormalized `Ref` label (3.0 §4's engine-maintained copy) was always
a derivation over a peer, and a `Mirror` was always a resource whose every
field is authored. Both fold in (§8, §10) — one concept, one spelling.

# 2. `Derived` — one definition, every consumer

A derivation is a named, typed field whose value is a pure function of
declared inputs:

```python
class Wire(Resource):
    kind = "wire"
    ifts = Owns("ift", via="wire_id")            # E4, unchanged

    all_ifts_terminal: bool = Derived(
        over=(ifts.field("state"),),
        fn=lambda states: all(s in IFT.TERMINAL for s in states),
        explain="{open} transfer(s) are still open.",
        vars=lambda states: {"open": sum(s not in IFT.TERMINAL for s in states)},
    )

class Account(Resource):
    recon_difference: Decimal = Derived(
        over=(Owns("breaks").field("amount"), Ref["account"].via("twin").field("balance")),
        fn=lambda breaks, twin_balance: sum(breaks) - twin_balance,
    )
    reconciled: bool = Derived(
        over=("recon_difference",),
        within=Tolerance("0.00001"),             # THE tolerance, stated once
    )

class Step(Resource):
    overdue: bool = Derived(
        over=("due_date", Clock),
        fn=lambda due, now: now.date() > due,
    )
```

Declared inputs may be: own fields, owned children via an `Owns` edge
(E4's rollups are now library derivations over this — `Count`, `Sum` —
not a separate concept), `Ref`'d peers, transition-log facts (§4), and
the clock (§3). One definition, five consumers:

- **Render.** The fact is data — `data.recon_difference` — because Elena
  reviews the differences themselves; a derived fact that exists only
  inside a guard verdict covers none of the sightings.
- **Filter, sort, facet.** Derived fields join `filterable`/`sortable`
  and `Vocab`-style faceting like any field. Twelve overdue queries
  become `?overdue=true`.
- **Guards.** Guards and `Relation`s judge derived fields exactly like
  written ones, because they are fields (§5).
- **Visibility.** Visibility entries may reference derived and log facts
  (§4).
- **Events.** A flip is announced on the bus (§3).

**The materialization law** — parallel to 3.0's "selectors must be
indexable," and law, not guidance. Because `over=` is declared, the engine
knows the complete invalidation map: which transitions on which kinds, and
which clock crossings, can change which rows' facts. Every derivation that
any filter, facet, guard, or visibility entry references is **materialized
as an engine-maintained, indexed column**, updated transactionally with
the causing transition (it rides the same commit, like the transition row
itself) or by the clock consumer (§3). A derivation consumed only by
render may stay virtual; the moment a second consumer names it, the
maintainer takes over — the engine can enforce this because consumers are
declarations too. A derived fact recomputed per-request across a
collection is `apply_scope` reborn — the exact scar 3.0 §1 exists to kill.

Conformance gains the corresponding proof: for every derivation, the suite
walks transitions that dirty its inputs and asserts
recompute-from-inputs == materialized value at every step. A derivation
whose `fn` is not a pure function of its declared `over=` fails the walk
loudly — the 2.0 closure rule, applied to facts.

# 3. The clock is a publisher

3.0's taxonomy declared two event classes and the deliverable tracker
found the third: *the world's declared facts changed without a write*.

| class | provenance | durability | ordering | storage |
|---|---|---|---|---|
| `transition` | someone wrote | at-least-once, replayable | log-ordered | the transition log |
| `observation` | someone looked | at-most-once, drop-on-pressure | best-effort | none, ever |
| `derivation` | a declared fact flipped | at-least-once, **replayable by recomputation** | anchored to cause | none — the log + the clock are the basis |

- A derivation event carries the fact's name, `from`, `to`, `at`, and a
  `cause`: the transition id that dirtied its inputs, or `clock`. When
  transition-caused, it is emitted in the same commit as the cause
  (the outbox discipline, unchanged).
- **The transition log plus the clock is a complete basis for every
  derived fact.** Derivation events are an index into that basis, not a
  second source of truth: a consumer that missed a flip re-derives it —
  `Last-Event-ID` replay re-emits transition-caused flips off the log, and
  a missed "became overdue" is still overdue on the next fetch. Nothing
  new is stored.
- **One clock consumer.** For derivations whose `over=` includes `Clock`,
  the comparison must be *extractable*: the engine requires the
  against-`now` operand to be a stored or derived timestamp, so each row's
  next flip time is itself a maintained column. The consumer sweeps one
  index (`next_flip_at <= now`), flips the fact, emits the event, and
  keeps a tick cursor so a restart cannot skip a crossing. Dashboards
  subscribe; agents escalate; nobody polls.

This is where 3.0's "scheduled evaluation — time-as-event-source is now
silent in four apps" note lands, inside the three-citizen frame rather
than beside it: the clock is a bus publisher whose events are derivation
flips, declared like everything else.

# 4. History is an input

The transition log has been a first-class *store* since v0.1 — audit,
outbox, idempotency anchor — but never a first-class *input to
declarations*. Four-eyes needed it and got enforcement-only (E3 reads
`ctx.actor_of` at invoke); nothing lets projection see the same fact, so
the button renders and refuses.

4.0 admits log facts as derivation and visibility inputs:

```python
Log.actor_of("submit")          # the principal who performed a transition
Log.performed("review", by=Self)
Log.count(action="reject")
```

They are indexable by construction — the log already carries
`(kind, resource_id, action, actor_id)` — so they obey §2's
materialization law like any input.

**Per-action relative visibility.** Actions gain the negated,
history-dependent form the payouts story asked for by name:

```python
@action(from_=W.SUBMITTED, to=W.APPROVED,
        unless=Unless(actor_of("submit")),
        safety=Safety(idempotent=True, reversible=False, confirm=True))
async def approve(self, inp, ctx): ...
```

One declaration, two consumers: the projector omits `approve` from Sam's
envelope (he sees it in `unavailable` with the derivation's `explain`, or
not at all if the guard hides), and the invoker refuses the client that
ignores advertisement — with the same string. E3's `four_eyes(of=...)`
survives as sugar for exactly this, which is the general pattern of the
E-wave's fate: kept in behavior, generalized in mechanism.

# 5. Guards judge facts; demand is conditional

**Parent-gated-on-children collapses into an ordinary guard**, because the
rollup is an ordinary field:

```python
@action(from_=W.ACTIVE, to=W.COMPLETED,
        guards=[require("all_ifts_terminal")],
        safety=Safety(idempotent=True, reversible=False))
async def complete(self, inp, ctx): ...
```

The renderer folds it — `complete` sits in `unavailable` with "2
transfer(s) are still open," generated from the derivation's `explain`,
never hand-written — and the invoker enforces membership in the same
truth. Where the gating fact is clock-extractable (§3), the refusal gets
`becomes_available: {at: …}` for free: the engine knows when the fact
flips, so it can say so. `check=` shrinks again, exactly as `Relation`
shrank it in 3.0 §5: every children-state gate and every threshold gate
comes off the prose cliff.

**Conditional demand.** Requiredness governed by a declared predicate:

```python
class CompleteInput(BaseModel):
    date_completed: date
    client_caused: bool | None = None
    reason: str | None = None

    late = When(("date_completed", ">", Field.of("due_date")),
                requires=("client_caused", "reason"),
                forbids_otherwise=True)
```

One declaration, three consumers: the schema carries it as JSON Schema
`if/then` so the generic UI reveals the arm the moment the date crosses
the line (client-side, from the declared schema — no bespoke JS); the
invoker enforces the same predicate; and the demand class is computed
per branch, so the action honestly reads `assent` on time and
`composition` when late. Refuse-after-submit — the trial-and-error 3.0
§5 exists to kill — stops being the only encoding.

# 6. The compound act

The fact outgrew the field; the act outgrew the transition. Three apps
chose "one big service method with flushes" because nothing in the design
defines a transition whose effect spans resources — let alone one with an
external side effect needing compensation. E8 declared the *touches*
(`Advances`/`Creates`, with undeclared ctx writes aborting); 4.0 makes the
same declaration the whole contract:

```python
carve_out = Compound(
    on=Wire, from_=W.ACTIVE, to=W.ACTIVE,
    creates=(Create(Event, seed=from_parent("event_template")),),
    advances=(Each(Owns("rows"), where=Selected, action="reassign"),
              Advance(Ref["event"], action="decrement"),),
    effects=(blob.copy("docs/{id}/", to="docs/{child.id}/",
                       compensate=blob.delete),),
)
```

- **Resource writes are atomic.** Every child transition goes through the
  single invoker inside one transaction under one `correlation_id` — the
  audit trail shows the act and its cascade as a unit, which the log has
  supported since v0.1 §14.
- **External effects are honest.** An effect is a declared `Service` call
  with a declared compensator; the engine runs compensators on abort, in
  reverse order, each attempt audited. The posture is stated, not implied:
  at-least-once with declared compensation, because the envelope promises
  only what the adapter can — the `Mirror` honesty rule, applied to
  writes. Deferred compounds execute on the E6 job kind, which becomes
  *the* executor rather than a parallel one.
- **The blast radius renders.** What E8 enforced, 4.0 advertises: the
  compound's `effect` on the wire lists its `creates`/`advances`/`effects`
  — v0.1's informational `emits`, finally grown into the contract. An
  agent planning over `effect.to` graphs sees the whole act before
  confirming it.

The pipeline gains one route class (`compound: authenticate → fence →
guards → orchestrate → respond`), emitted from the declaration like every
other route — which also forces the last of `build_router`'s hand-written
handlers into emitted stages, closing the "full generator remains open"
deviation from the 3.0 notes.

# 7. Batch is the collection form of the act

intake rejected "200 CSV lines as 200 wire invocations" — chatty,
non-atomic — and then built the thing the law forbids: client-side
validation, browser-minted UUIDs, three tables written non-atomically.
Fourth sighting; payouts' "rule bulk a generic-UI affordance" answer
stops sufficing when the workload is ingestion, not grid convenience.

4.0 gives the action a batch form:

```python
@action(from_=S.OPEN, to=S.OPEN, input=RowInput,
        batch=Batch(atomic=True, max_items=5000),
        safety=Safety(idempotent=True, reversible=False))
async def upsert_row(self, inp: RowInput, ctx: Ctx) -> None: ...
```

- The wire shape is N inputs → N verdicts, in the bulk-report shape v0.1
  §7.4 established: `succeeded` / `refused` / `failed` with per-item
  reasons from the same guards that would refuse a single invocation.
- `atomic=True` means one audited commit or a refuse-all whose report
  still carries every verdict — validation always completes even when
  commitment doesn't, so the grid can show all 200 problems at once.
- **Drafts extend to batches.** A batch draft stages N rows with per-row
  dry-run verdicts (the engine already owns drafts and dry-run; this is
  their product), so the import flow composes from parts that all exist:
  upload an `attachment` (E5) → parse into a batch draft → verdicts render
  in the grid → drain as one act, one `correlation_id`, one transition-log
  unit. No browser UUIDs, no third table, nothing off-wire.

# 8. Authority is per-field

The entity-list's central strain: the HubSpot fields are externally owned
and read-only, the crosswalk and budgets are local and writable — but they
are *one entity* to the user, and 3.0 offers only whole-resource `Mirror`
or a satellite-kind composition the envelope has no name for.
Master-data-around-a-CRM-object is the normal case for internal tools,
not an edge.

§1's field-origin frame dissolves the dichotomy:

```python
class Entity(Resource):
    kind = "entity"
    name: str = Authored(by=hubspot)
    stage: str = Authored(by=hubspot,
                          follows={"closedwon": "activate",
                                   "closedlost": "suspend"})
    crosswalk_id: str | None = None                # written
    health: Badge = Derived(over=("crosswalk_id", "stage"), fn=...)
```

- Authored fields are read-only inside the boundary and updated by the
  authority's adapter under the declared sync policy; external changes
  arrive as `system`-actor transitions (3.0 §10, unchanged), which means
  they dirty derivations and flip facts like any other write.
- Sync state becomes per-authority: `stale`/`conflicted`/`unreachable`
  scope to the fields that authority owns; a conflict over `name` never
  blocks an edit to `crosswalk_id`. `reconcile` remains a rendered action,
  not a silent overwrite.
- **`follows=` promotes the hand-written consumer to a declaration** —
  the onboarding tracker's second-sighting ask, verbatim: an
  authority-owned field change may trigger a declared transition, which
  the engine can render, verify, and audit, instead of opaque code it
  can't.
- `Mirror` survives as the degenerate case — a resource whose every field
  is authored by one service — and the `Mirror` discovery gap closes the
  same way: `Discover(query=..., every=...)` on the authority mints
  mirrors on the §3 clock consumer, a declared sweep instead of "create
  mirrors explicitly."

# 9. The collection grammar completes

Entity-list, on its very first screen: "§7's grammar is missing half of
what a real list needs" — no `?sort=`, no pagination, no count without
rows. The workarounds are an ORDER BY ladder and a hand-synced count
query, which is a derived fact defined twice, i.e., §2's problem wearing
a collection coat.

- `?sort=` returns to the spec grammar (declared `sortable`, `-` prefix
  for descending — v0.1 had this; 3.0 §7 forgot to say it), and derived
  fields sort because they are maintained columns.
- `?page[size]=`/`?page[number]=` are spec-owned parameters; `next`/`prev`
  stay envelope links; hrefs stay authoritative and `merge_params` stays
  the only way to refine one.
- `?rows=none` returns the collection envelope — `total`, facets, the
  `query` action — with no items. Counts and facets are collection-level
  derivations riding the same maintained columns, so the count is one
  indexed read that cannot disagree with the rows it summarizes.
- Unknown parameters remain Problems (3.0 §7, unchanged).

# 10. One spelling per concept; the create surface joins projection

The 3.0-notes deviation list, held to the law:

- **Create is projected, not just enforced.** The closure rule applies to
  the create surface: create-guard `accepts=` folds into the create
  schema's enums, warning-severity create guards render their warnings,
  and approval-mode creates carry acknowledgments — deleting the notes'
  own admission that "dry run is the honest preview." Advertisement/
  enforcement drift on create was the original sin with a new address;
  it gets the original fix.
- **E4 rollups fold into `Derived`.** `Count`/`Sum` become library
  derivations over `Owns` — same wire, one concept. The "aggregates
  beyond count/sum" punt is re-decided by §2: whatever a pure function
  over declared inputs expresses is in; report-shaped analytics stay out
  (see punts).
- **`Ref` labels are derivations.** The engine-maintained denormalized
  label (3.0 §4) is re-founded on the §2 maintainer — `label=` becomes
  sugar for a `Derived` over the peer. One maintainer, not two.
- **Member roles validate at invite.** The notes' recorded gap — a typo'd
  role on an invite names nobody until a grant fails — closes by checking
  invites against the role registry at create, now that create guards
  project.
- **The router generator finishes.** §6's and §7's route classes are
  emitted from declarations; the remaining hand-written handlers in
  `build_router` become emitted stages, completing what 3.0 §2 started
  and the notes left open.

# 11. The scar table

3.0 closed its case with the fate of every 2.0 scar. The same table for
3.0's own:

| 3.0 scar | 4.0 fate |
|---|---|
| `recon_difference`: 4 definitions, 3 tolerances | unrepresentable — one `Derived`, one `Tolerance` (§1, §2) |
| `overdue` in 12 parallel queries | one derivation + `?overdue=true` (§2) |
| `variance_breach` collected, never computed | a predicate is one declaration, not five edits (§2) |
| "became overdue" unwatchable — neither transition nor observation | `derivation` event class; the clock is a publisher (§3) |
| four-eyes enforced (E3) but not projected — the button that refuses | `Unless(actor_of(…))`, one declaration, two consumers (§4) |
| parent-gated-on-children as prose `check=` | guard on a declared rollup; `explain` folds; `becomes_available.at` derived (§5) |
| conditional args refuse-after-submit | `When(...)` — schema `if/then`, per-branch demand (§5) |
| carve-out as service method with flushes; uncompensated S3 copy | `Compound` with declared compensation; blast radius renders (§6) |
| bulk import: browser UUIDs, three tables, non-atomic | batch drafts → one audited act with per-row verdicts (§7) |
| split-ownership entity unnameable (Mirror is whole-resource) | per-field `Authored`; sync states scope to the authority's fields (§8) |
| mirrored-field change → hand-written state-machine consumer ×2 | declared `follows=` (§8) |
| no sort / pagination / count in the §7 grammar | grammar completes; counts are collection derivations (§9) |
| create guards enforced, not projected; "dry run is the honest preview" | create surface joins projection (§10) |
| stale-data / value-date "hot" flags computed at render per app | clock-input derivations (§2, §3) |
| hand-synced count queries | one maintained column, one definition (§9) |

And the E-wave mechanisms, held to the same law:

| E-mechanism | 4.0 fate |
|---|---|
| E3 `four_eyes` (enforce-only) | sugar for `Unless(actor_of(…))` — projected and enforced (§4) |
| E4 `Owns` rollups (count/sum, one-off) | library derivations; `Derived` subsumes (§2) |
| E8 `Advances`/`Creates` (abort on undeclared writes) | the enforcement half of `Compound`; the declaration now also advertises (§6) |
| E1, E2, E5, E7, E9 | kept as shipped; E2's `existing` pointer stays the model for constraint refusals that carry links |
| E6 jobs | kept; becomes the executor for deferred compounds and batches (§6, §7) |

---

## Wire format delta (v3 → v4)

| Surface | v3 | v4 |
|---|---|---|
| `waymark` | `"3"` | `"4"` |
| Data schema | all fields writable-shaped | `readOnly` + `x-source: derived\|authored` on non-written fields |
| Events | `class: transition\|observation` | + `class: derivation` with `fact`/`from`/`to`/`cause` |
| Action input | static requiredness | + `if/then` from `When(...)`; `effort` computed per branch |
| Action effect | `to` (+ `emits`) | + compound blast radius: `creates`/`advances`/`effects` |
| Unavailable | `reason`, `becomes_available` | `becomes_available.at` derived from clock-extractable facts |
| Bulk | id-list bulk, partial report | + N-input batch with per-item verdicts; `atomic` declared |
| Collections | filter grammar, unknown params are Problems | + `?sort=`, `?page[…]=`, `?rows=none`; `total`/facets from maintained derivations |
| Engine kinds | member, grant, subscription, attachment, job… | **no new kinds** — 4.0 spends its novelty on fields and acts |

A v3 generic client pointed at a v4 server keeps working: it reads every
envelope, treats derived fields as ordinary data, misses conditional-
demand folding (it discovers requiredness at dry-run, exactly as today),
and can filter the new event class by `class`. As with every major
version, the novelty is spent where the last one actually hurt.

## Migration sketch (v3 apps)

Mechanical, and mostly *centralizing* — the scars are facts apps computed
in many places:

- Every `@property`/hybrid-property on a Data model, every helper
  predicate, and every tolerance literal is flagged by the codemod; each
  becomes a `Derived` or is acknowledged as render-only. Sites where the
  flagged definitions disagree — the three-tolerance list — are exactly
  the facts that were already drifting in production.
- `four_eyes(of=…)` declarations rewrite to `unless=Unless(actor_of(…))`;
  wire behavior changes only in that the button now honestly disappears.
- `check=` guards over children's states rewrite to guards over a
  declared rollup; the codemod flags every `check=` whose `reads=` names
  an `Owns` edge — the list of refusals that were prose because 3.0
  couldn't render their truth.
- E4 `rollups=` declarations rewrite to `Derived` library calls; no wire
  change, no storage change (same maintained column).
- Multi-resource service methods become `Compound`s; E8's touch
  declarations already name the write set, so the codemod scaffolds the
  compound from them and flags every external call without a declared
  compensator.
- Client-side import loops become batch drafts; the browser stops minting
  identity.
- `Mirror` kinds become all-fields-`Authored` resources unchanged; where
  an app shipped a Mirror plus satellite kinds for one user-visible
  entity, folding them into one mixed-origin kind is optional and local.
- Backfill for new maintained columns is "run the derivation once per
  row," emitted as an ordinary snapshot-diff migration. Engine tables
  version again (`waymark4_*`).

## Explicit 4.0 punts

Punted, with the rule kept — punt things that don't compound:

- **i18n** — still deferred; derivation `explain` strings join the one
  emission point, so the walk stays a walk.
- **CRDT/OT merge** — unchanged; the seam is still the contract.
- **Cryptographic attenuation** — unchanged; grants remain the contract.
- **Report-shaped analytics** (percentiles, time-series, min/avg
  dashboards) — E4's punt, held: derivations are per-resource facts, not
  a query language; the reporting boundary remains subscriptions off the
  log into whatever warehouse the org already trusts.
- **Job resume-instead-of-cancel** — still the recorded engineering-depth
  punt; §6's declared compensation shrinks what cancellation strands.
- **Cross-server derivations** (facts over a peer server's resources) —
  waits, with the rest of federation, for a second real Waymark server.
- Not punted, on principle: the materialization maintainer (§2), the
  clock consumer (§3), and create-surface projection (§10) — each is a
  3.0 deviation or silence the mappings prove compounds.

---

## Appendix: before/after user stories

One pair per numbered section, cast with the enterprise mappings' actual
users: **Elena** (reconciliation reviewer), **Sam** (wire submitter),
**Priya** (approver), **Marcus** (delivery lead), **the agent** (on a
`wmk_` token), and **Colton** (platform operator).

### §1–§2 Truth declares its source / `Derived`

**Before (v3):** Elena's recon screen shows a difference the browser
rounds at 1e-2; the server's guard judges 1e-5; the "prepare" button's
client gate uses JS truthiness. Two of the three say reconciled. She
escalates a break that the invoker would have accepted, and nobody can
say which definition is *the* definition, because there isn't one.

**After (v4):** `recon_difference` and `reconciled` are declared once,
with one `Tolerance`. The grid renders the maintained value, the filter
pushes to its index, and the guard judges the same column. There is
nothing to disagree — the browser never computes anything.

### §3 The clock is a publisher

**Before (v3):** Marcus's dashboard polls twelve queries to paint
"overdue," and the agent tasked with escalation polls too, because "step
became overdue" is an event no class can carry — nothing wrote, nothing
looked.

**After (v4):** The clock consumer flips `overdue` when `due_date`
crosses midnight and the bus carries one `derivation` event. The
dashboard subscribes; the agent escalates on receipt; the twelve queries
are one indexed filter.

### §4 History is an input

**Before (v3):** Sam submits a wire, then sees the approve button — E3
will refuse him, but only after he clicks. The advertisement lies,
precisely the drift the framework exists to kill.

**After (v4):** `unless=Unless(actor_of("submit"))` is consulted by the
projector: Priya sees approve; Sam sees `unavailable` — "submitted by
you; a second reviewer must approve." Same declaration refuses the
client that ignores advertisement, with the same sentence.

### §5 Guards judge facts; demand is conditional

**Before (v3):** Sam tries to complete a wire with two open transfers
and gets a hand-written prose refusal from a `check=` the renderer can't
see into. Marcus completes a step late and only learns *after* submitting
that `client_caused` and a reason were required.

**After (v4):** Complete sits in `unavailable` with "2 transfer(s) are
still open" — generated, not written — and flips available the moment the
last IFT lands, announced on the bus. Marcus's form reveals the
late-completion arm the instant the date crosses the deadline, straight
from the schema's `if/then`.

### §6 The compound act

**Before (v3):** The carve-out is one big service method: child event
created, rows moved, parent decremented, S3 docs copied mid-transaction.
When the commit fails after the copy, orphaned blobs sit in the bucket
and the audit trail shows nothing at all.

**After (v4):** `carve_out` is a `Compound`: every resource write is a
child transition under one `correlation_id`, the blob copy declares
`compensate=blob.delete`, and an abort runs the compensators — audited.
The agent sees the whole blast radius in `effect` before Priya confirms.

### §7 Batch is the collection form of the act

**Before (v3):** intake's import validates 200 CSV rows in the browser,
mints UUIDs client-side, and writes three tables non-atomically. Row 141
fails; rows 1–140 are in; nobody is sure what state the book is in.

**After (v4):** The CSV lands as an attachment, parses into a batch
draft, and every row's verdict renders in the grid before anything
commits. Drain is one act: 200 transitions or zero, one entry in the
audit trail either way.

### §8 Authority is per-field

**Before (v3):** The entity is either a whole-resource Mirror (so the
crosswalk is uneditable) or a Mirror plus three satellite kinds the user
never asked to know about. A HubSpot sync conflict on the name blocks
editing the budget.

**After (v4):** One kind: `name` and `stage` are `Authored(by=hubspot)`,
the crosswalk is written, `health` is derived. A conflict scopes to
HubSpot's fields; `stage.follows` moves the onboarding machine when the
deal closes — as a declaration the engine renders and verifies, not a
consumer someone remembered to write.

### §9 The collection grammar completes

**Before (v3):** The entity list's first screen needs a sort ladder, a
page, and a count — none of which the grammar names, so the app ships an
ORDER BY ladder and a count query that drifts from the filtered rows.

**After (v4):** `?sort=-health&page[size]=50` is spec, and
`?rows=none` returns the count from the same maintained column the rows
filter on. The number in the tab and the rows in the grid cannot
disagree; they are one definition.

### §10 One spelling per concept

**Before (v3):** A warning-severity create guard fails an approval-mode
create outright, with the warning buried in `outcome`; a typo'd role on
an invite names nobody until a grant fails weeks later; and the notes
file itself admits "dry run is the honest preview."

**After (v4):** Create projects like everything else: the create form
folds its guards' `accepts=`, renders its warnings, and carries
acknowledgments through approval mode. The invite refuses the typo at
create, where the reviewer is still looking at it.
