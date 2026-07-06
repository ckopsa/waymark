# Waymark 3.0 — the enterprise wave

Extensions to `waymark3-design.md`, held to the same law. The evidence
base is the app-mapping series — nine internal business apps (admin-tool
and full-stack) tested against the 3.0 design the way the post-rebuild
commit history tested 2.0: `waymark3-mapping-onboarding-tracker.md`,
`waymark3-mapping-intake.md`, `waymark3-mapping-cash-recon.md`,
`waymark3-mapping-payouts-fullstack.md`,
`waymark3-mapping-holdings-recon.md`,
`waymark3-mapping-price-validation.md`, and the three story docs under
`waymark3-stories/`. Every mechanism here recurs in at least two of
those apps, hand-built and divergent; every one passes the 3.0
admission test — a declaration, a resource, or an event class, consumed
by the advertiser and the enforcer alike. Nothing here needed a fourth
kind of thing, which is why this is an extension of 3.0 and not a 4.0.

Sections land with their implementations; an unfilled section is a
placeholder, not a promise.

---

# E1. A warning is a guard with an audited override

The scar, three times over: payouts ships
`CustomHTTPException(is_warning=…)` with a `should_ignore_warnings=true`
retry loop; holdings recon reinvents it as
`InvalidRequestException(is_warning=True)` overridable by
`ignore_recon_timestamps=True`; price validation raises `is_warning`
exceptions with **no** override path at all. Three teams, one need —
"this rule is advisory; a human may accept responsibility and proceed" —
and zero of the three record *who accepted, when, past what*. The
override event is precisely what an auditor asks for and precisely what
every hand-built version fails to keep.

In the enterprise wave a warning is not a new kind of thing — it is a
**guard with a declared severity**:

```python
unusually_large = Guard(
    severity="warning",                      # default: "refuse"
    explain="{amount} is over the {limit} review threshold…",
    check=_check_amount, ...)
```

One declaration, three consumers:

- **Projection.** A warning-severity Deny at probe does not
  un-advertise the action. The entry carries
  `warnings: [{name, reason}]` — the client renders
  confirm-with-reason up front, with the same sentence enforcement
  will use.
- **Enforcement.** An unacknowledged warning refuses with a
  `warning-required` Problem that *is* the override affordance:
  `severity`, per-guard `warnings`, and
  `acknowledge: {header: "Waymark-Acknowledge", names: […]}`. The
  caller resubmits with `Waymark-Acknowledge: <names>`; refuse-severity
  guards ignore the header — severity is the declaration's, never the
  caller's.
- **Audit.** The transition row gains a nullable `acknowledged` column
  holding exactly the warning names that denied *and were overridden*.
  "Sam saw the warning and proceeded" is a queryable fact of the log,
  not a lost dialog.

Dry-run reports the pending warnings (`{"valid": true, "warnings":
[…]}`) — blur-time forms tell the truth before submission. The
conformance walker acknowledges advertised warnings on retry: a warning
is not a wall, and the suite treats it as the design says.

What this deletes: every app's bespoke warning-exception class, its
override flag, its client-side warning dialog protocol — and the
unaudited override, which becomes unrepresentable: there is no way to
pass a warning without the log recording it.

# E2. Uniqueness refusals carry the conflict

The scar, four times over: cash recon, holdings recon, and price
validation all smuggle `error_code: "already_exists"` plus an
`existing_id` through exception payloads so the client can offer "go to
the existing one" — and price validation's copy is structurally dead
(the recovery dialog can never open in its create mode). The grocery
list dogfood dedupes by hand inside a handler. Everyone needs the same
two things: the constraint enforced where races can't beat it, and the
refusal pointing at the resource that holds the value.

The declaration:

```python
class Workbook(Resource):
    unique = (("fund_id", "as_of"),)     # one workbook per fund × period

class Role(Resource):
    unique = ("name",)                   # one spelling per role
```

- **Storage.** Each group becomes a `UNIQUE` constraint on the promoted
  columns (`uq_{plural}_{fields}`) — so unique fields must be
  filterable/sortable, checked at import, and arrays can't apply. The
  database is the arbiter; two racing creates cannot both win.
- **The refusal is hypermedia.** A violation surfaces as the
  `already-exists` Problem (409) with the violated `fields` and
  `existing: {href, id}` — looked up *after* the aborted transaction,
  in a fresh session, because an aborted transaction answers nothing.
  The recovery every app hand-built from error payloads is now the
  error itself.
- **Migrations.** `schema_snapshot()` serializes constraints and the
  differ emits `ADD CONSTRAINT` / review-gated changes — and the
  conformance round-trip asserts the migrated schema carries every
  declared constraint, so a uniqueness that exists only under
  `create_all` is unrepresentable.

What this deletes: the `already_exists` error-code dialects and their
client-side `existing_id` plumbing; the handler-level dedupe loops; and
the class of bug where the "check then insert" race admits duplicates
that no code path can explain afterward.

# E3. Four eyes: authority conditioned on history

The scar, three times over, always compliance-core: the admin-tool wire
payments enforced "you can't approve your own wire" by client-side
*display-name string equality*; its full-stack rebuild got it
server-side but couldn't project it (the preparer saw an approve button
the server would refuse); cash recon and holdings recon enforce it only
in the browser (`myId === prepared_by.id`) — curl walks past. Every
version also depends on a hand-maintained `prepared_by` column that
duplicates what the transition log already knows.

The declaration is a stock guard over the log:

```python
@action(from_=PREPARED, to=REVIEWED,
        guards=[four_eyes(of="prepare")], ...)
async def review(self, inp, ctx): ...
```

`four_eyes(of=…)` reads the latest performer of the ``of`` transition
through `ctx.actor_of` — a new one-line Ctx surface over the log's
existing `(kind, resource_id)` index — and denies when it is the
invoking principal. Because it is an ordinary check guard with no
judged input, the projector probes it at render: the preparer's own
envelope shows `review` in `unavailable` with the same sentence the
invoker will refuse with. Advertisement and enforcement are one
declaration; the "approve button that refuses you" — the exact drift
the payouts rebuild shipped — is unrepresentable.

The bar follows the log's latest word: after someone else re-performs
``of``, the bar moves to them. No `prepared_by` column, no cleanup —
the log was always the truth; this reads it.

What this deletes: hand-stamped `prepared_by`/`initiated_by` attribution
columns kept solely for this rule, the client-side self-comparison in
three apps, and the four-eyes-by-name-equality bug class (two employees
named alike could never approve each other's work).

# E4. Ownership: one edge, many consumers

The strongest multi-app finding: six apps hand-built some face of *a
kind that owns a kind*. Cascade lifecycle (onboarding's three-query
delete saga, run from the browser, copied per dashboard tab), child
rollups gating the parent (holdings recon's "every break has a note"
prepare guard — the one server-enforced example, built in raw
SQLAlchemy), dashboard aggregates (the onboarding tracker's ~90-line
`events.sql`), and per-parent counts recomputed in every client. The
relationship was always real; only the declaration was missing.

The edge is declared once, on the parent:

```python
class MealPlan(Resource):
    owns = (Owns("prep_task", via="plan_id",
                 on={"abandon": "cancel"},
                 rollups={"open_tasks": Rollup(
                     filters={"state": ("pending", "scheduled")})}),)
```

`via` names the child's `Ref` back to the parent — association was
already declared; `Owns` gives the edge its lifecycle and its
aggregates. Validated at engine assembly like `check_refs`: the child
kind exists, `via` is a `Ref[parent]` on an Eq-filterable (promoted,
indexed) column, cascade endpoints are real actions the runner can
drive (no input, unfenced — deferred loudly otherwise), rollup filters
are filterable child fields.

**Cascade rides the log.** The runner is an engine-internal consumer in
the webhook deliverer's discipline: the dispatcher is only a wake
signal; the feed is `transitions_since` behind a durable cursor
(`waymark3_cursors`), so an outage drains instead of dropping, and
first boot seeds at the log's head — pre-feature history never replays.
Children are selected by the target action's `from_` states, which
makes redelivery a natural no-op and skips terminal children by
construction. Each child transition is performed by the
`waymark-cascade` system actor **with the parent transition's
correlation id** — the follower sees one story: *plan abandoned → its
prep tasks cancelled*. Per-child failures log and the cursor advances
(liveness over completeness, the webhook precedent).

**Rollups are computed truth on the envelope.** A declared rollup rides
a top-level `rollups` key — derived, deliberately outside `data` — on
resource, collection-row, and post-invoke envelopes alike (one GROUP BY
per rollup per page, on the promoted column). Scoped views drop them: a
count over children the principal may not see is a leak. And the guard
half is one more consumer of the same declaration:

```python
@action(..., guards=[rollup_is("open_tasks", "==", 0,
        explain="{open_tasks} prep task(s) are still open — …")])
async def complete(self, inp, ctx): ...
```

`rollup_is` re-queries authoritatively at evaluate time (envelope
values may be stale by invoke), saturates at threshold+1, and probes at
render — the count folds into `unavailable` with the same sentence
enforcement refuses with.

**Template instantiation is the edge's third consumer.** Three apps
seeded children at parent create — onboarding's `create_event` stored
procedure, intake's `INSERT … SELECT` checklist copy, cash recon's
account-registry clone — each an imperative expansion invisible to
conformance. Declared:

```python
owns = (Owns("item", via="event_id",
             seed=Seed(kind="item_template",
                       where={"fund_type": "{data.fund_type}",
                              "state": "active"},
                       copy={"name": "name", "amount": "amount"},
                       defaults={"source": "template"})),)
```

Seeds run through `ctx.create` in the **same transaction** as the
parent's create — a parent cannot exist with half its declared
children — as the creating actor's own audited creates, sharing the
parent's correlation id. `where` values template over the parent
(`{data.*}`); assembly checks keep source kind, filters, and both
sides of every `copy` honest. Deliberately *not* decided here:
retroactive propagation when templates later change — onboarding
story 4 showed that policy is domain judgment (which open events? only
unstarted groups?), so a template edit touches nothing already seeded,
and the test suite asserts exactly that.

**Sums join counts.** Cash recon's core predicate is a *sum* — Σbreaks
gating prepare — so `Rollup(agg="sum", of="amount")` totals a promoted
numeric child column in the same GROUP BY, renders in `rollups`, and
`rollup_is` gates on it with the amount in the refusal.

**Rollups filter and sort the collection.** The onboarding dashboard's
status filter — "in progress" re-derived from child aggregates in a
CASE ladder — compiles instead of post-filtering: every declared
rollup becomes collection query params (`?open_items=0`,
`?open_amount_gte=50`, `?sort=-open_items`), advertised in the query
schema (`x-rollup`) so a typo stays a loud Problem, and lands in SQL
as a correlated scalar subquery beside the visibility pushdown — the
same law §9 set for grants: derived truth must reach the WHERE clause,
because post-filtering rendered envelopes is `apply_scope` reborn.
Zero children honestly counts as zero. Rollup names may not collide
with the parent's own filter/sort params — checked at assembly.

**Punts, classified — because "punted" must not blur into "unneeded":**

- *Was here, now shipped*: multi-resource transitions ran their design
  cycle against the 4.0 tripwire and landed as **E8** — the quantum
  held because the one breaking shape (parents writing child rows
  directly) is refused, decomposed into child actions.
- *Demanded, deferred for engineering depth*: **multi-worker job
  leasing** — no app built it because every app simply had the bug,
  and their multi-worker deployments will hit it. (Rollup-valued
  filter/sort shipped above; it left this list.)
- *Evidence-thin, awaiting a driving case*: **visibility inheritance**
  (no investigated app had read-side visibility at all — the need
  derives from this framework's own model; the first scoped app is the
  trigger); **fenced/input-taking cascade targets** and **non-create
  seed triggers** (no sightings); **aggregates beyond count/sum** in
  the gating shape — the min/avg sightings (earliest-reconciled MIN,
  DAR's mean) are report-shaped and route to the reporting boundary.

What this deletes: browser-run cascade sagas and their per-tab copies;
hand-maintained counter columns and the dashboard subqueries that
recompute them; the child-set predicates re-derived in JS per screen;
and the class of orphan (mid-saga tab close) that reconciliation jobs
exist to sweep up.

# E5. Attachments: bytes behind the envelope

The series' only outright fail, three apps deep. payouts (admin-tool)
sequenced S3 uploads with a two-second timer so the insert had
"probably finished"; cash recon ships whole files base64 both ways
through JSON responses (a presigned-URL helper sits unused beside it)
and its "soft delete" flips a flag *and permanently deletes the S3
object*; holdings recon inlines base64 into list responses. Every app
re-invented path conventions, upload phases, and deletion semantics —
and every reinvention got at least one of them wrong.

The engine owns the kind. `attachment` (rides
`Engine(attachments=True)`) is ordinary Waymark: `resource_kind` +
`resource_id` name the target (a dangling target refuses at create —
the `event_id`-into-the-void scar, closed), `name`/`mime` describe the
file, and the machine is the two-phase upload:

    reserved → uploaded → removed

Bytes never touch the envelope. Two dedicated routes carry them against
a declared `BlobStore` (`Engine(blobs=…)`; put/get/delete, with memory
and filesystem stores shipped — the memory default is the dev-resolver
precedent): `PUT /attachments/{id}/bytes` accepts the body once, into a
reserved attachment, stores it, and stamps `size`/`sha256` through a
system-actor `mark_uploaded` transition — the audit trail records what
was received, by measurement, not by claim. `GET …/bytes` serves the
declared mime for uploaded attachments only, under the same visibility
projection as the metadata.

Deletion is a declaration: `Engine(blob_retention="purge" | "keep")`.
`remove` is confirm-gated with the consequence naming the policy;
under purge a log consumer (`BlobJanitor`, the cascade runner's
discipline: durable cursor, post-commit by construction) deletes the
bytes — whichever path invoked the remove, router or `ctx.invoke` or
an approval's run — and the metadata row remains as the audited
record. Cash recon's inverted soft-delete becomes unrepresentable,
because what happens to bytes is the engine's reviewed setting, not a
handler's accident. "Attachments of X" is a
filterable query (`?resource_kind=…&resource_id=…`), so any envelope
can link its evidence without a new mechanism.

What this deletes: the timer-sequenced upload, the base64 transports,
the per-app S3 key conventions, and the guard nobody could write —
"a wire cannot go for approval without documentation" is now an
ordinary rollup/find over a declared kind.

# E6. Async egress is a job resource

Five hand-built implementations of the same absence, each wrong its own
way: intake's Beacon queue tab ("check back at…", five sub-statuses per
push, error files in columns); cash recon's *blocking* sync whose error
strings are masked so CloudWatch won't alert, with freshness lost to a
phantom column; holdings recon MVP's job rows behind a manual poll
button whose terminal-state handling stalls on mixed outcomes; its NS
rewrite regressing to inline pulls with a one-hour HTTP timeout; price
validation's write-only `DataPullStatus` telemetry that nothing reads.

The mechanism was already half-shipped: deferred bulk lands on the
engine's `job` kind. This wave points *services* at it:

```python
async def pull_reports(self, inp, ctx):
    self.data.pull_job_id = await ctx.defer(
        ctx.services.beacon,
        [("balance_sheet", (fund_id, "BS")),
         ("general_ledger", (fund_id, "GL"))],
        action="pull_reports")
```

- **The job row rides the handler's transaction** — created by the
  acting principal (audited as theirs), returned as an id the handler
  stores or links. The invocation and its job cannot disagree about
  having happened.
- **Artifacts are the sub-statuses every import queue grew.**
  `JobData.artifacts` carries `(name, status, message)` per named piece;
  the runner drives each through `Service.call`, so §10's declared
  timeout/backoff compose. Progress is data (no transition spam);
  `start`/`finish`/`cancel` are system-actor transitions in the one log,
  SSE-visible — the queue tab is just the job's envelope.
- **Failure is recorded, never masked.** An artifact's failure keeps the
  adapter's own words (`ServiceDown.cause`); a service failure is an
  outage by declaration, so the remaining artifacts fail fast with
  `retry at …` instead of hammering — partial success is a state of the
  data, not an unhandled branch.
- **A dead worker cannot leave a running lie.** Startup sweeps
  queued/running jobs with no live task: unfinished artifacts get
  "orphaned by a worker restart" and the job is cancelled by the
  system — honest, visible, queryable.

**One live worker per job, by lease.** Runners claim a
`waymark3_job_leases` row before `start` — an atomic claim-or-steal
keyed on expiry (an absent or expired lease moves; a live one held
elsewhere refuses) — renew it on every item, and release it after
`finish`. The startup sweep consults the same row: a lease with a
future expiry is a neighbor's live job and is skipped; no lease, or a
lapsed one, is a dead worker's job and is cancelled as before, stale
lease row and all. A multi-worker deployment no longer needs a
one-job-bearing-worker rule; a died worker's job is cancelled — not
resumed — once its lease expires, and *resume* is the punt this wave
records where the last one recorded leasing.

**Not every failed call is an outage.** `Service.call`'s default
stands — an adapter failure downs the service for the declared backoff,
and remaining artifacts fail fast with `retry at …`. But intake's five
sub-imports fail *independently*: one malformed export is not a Beacon
outage, and downing the service for it would un-advertise four healthy
imports. `Service(down_on_error=False)` declares that shape: a failed
call raises `ServiceCallError` carrying the adapter's own words, the
service stays up, and the job runner records that artifact failed and
moves on. Which failures are outages is a declaration on the service,
not a branch in a handler.

What this deletes: the queue tables and their poll buttons, the
blocking syncs and their masked errors, the write-only telemetry — and
the five divergent answers to "how do I know my pull finished?", which
become one: follow the job.

# E7. The predecessor is a declaration, not date math

Two sightings, two shapes of the same absence. Holdings recon's
prior-period check refuses unless *last month's* workbook is reviewed —
locating it by fund + date arithmetic in a query no declaration
captures. Price validation copies the previous workbook's fund config
forward at create, recording provenance as the prose string
`"Copied from workbook {id}"`. Period-close domains chain resources in
time, and the chain lived nowhere but in WHERE clauses.

The declaration is a `Ref` resolution mode:

```python
class PlanData(BaseModel):
    previous_plan: Ref["plan"] | None = RefField(
        default=None, predecessor=Predecessor(order="start_date"))

class LedgerData(BaseModel):
    previous: Ref["ledger"] | None = RefField(
        default=None,
        predecessor=Predecessor(order="as_of", partition="fund_id"))
```

At create — before `on_create`, so apps can read the resolved sibling
for carry-forward — the engine fills a still-empty predecessor field
with the latest sibling by the declared `order` (≤ the new instance's
own value, so backdated creates slot into their true position), within
the declared `partition` when one is named. A supplied value wins;
the first of a partition follows nothing. Assembly checks keep the
resolving query honest: `order` and `partition` must be promoted
(filterable/sortable) columns on the target and fields of the
declaring Data.

Carry-forward stays app logic — one `ctx.read(previous)` in
`on_create` — because *what* carries is domain judgment; a `carry=`
declaration is a recorded punt. What the engine owns is the part the
apps got wrong: the reference itself, as data, labeled and linkable
like any `Ref`, instead of arithmetic re-derived per query.

What this deletes: the latest-prior-row lookups scattered through
period-close services, the prose-comment provenance, and — combined
with E3/E4 — the "prior period must be closed" rule becomes an
ordinary guard reading a declared ref instead of a query that four
apps would each write differently.

# E8. A transition declares what it touches

The series' sharpest sighting, three apps deep, and the one E4
classified *demanded, deferred for design risk*. payouts'
carve-out is one 190-line service method: create a child event from
the selected wire rows, move those rows, decrement the parent's amount
and count, link the pair, copy S3 documents mid-transaction —
uncompensated if the commit later fails — and write synthetic audit
diffs on both sides. intake's transfer is *two* transactions linked by a
join that matches either side in either direction, whose legs advance
independently until a cancelled twin is findable only by audit
archaeology. The onboarding tracker's "mark all tasks complete" is a
raw UPDATE stamping Devon's name on rows he never looked at — because
N wire calls was the chatty, non-atomic alternative the app had
already rejected. Three teams, one need — *one business act that
lawfully spans resources* — and every hand-built answer lost either
atomicity, attribution, or both.

Held to the law, the finding is narrower than it looks. The engine
already has lawful multi-resource *execution*: `ctx.create` and
`ctx.invoke` run in the enclosing invocation's transaction and
correlation id, each touched resource takes its own transition row
through its own machine, and the follower already reads the whole act
as one story. What a handler that quietly creates a peer lacks is the
same thing composition's `check=` lacked: a **declaration**.

```python
@action(from_=OPEN, to=OPEN, input=CarveInput,
        touches=(Creates("event"),
                 Advances("wire_row", "reassign")), ...)
async def carve_out(self, inp, ctx):
    child = await ctx.create("event", {...})          # the peer
    for row_id in inp.wire_ids:                       # the move — each
        await ctx.invoke("wire_row", row_id,          # row's OWN
                         "reassign", {"event_id": …}) # transition
    self.data.amount -= moved                         # the self-write
```

One declaration, three consumers:

- **Projection.** The touch set rides the action entry's `effect`
  (`touches: [{"creates": "event"}, {"advances":
  "wire_row.reassign"}]`) — an agent sees *this action creates another
  event* before invoking, the same way it sees `to`. Additive for v3
  clients.
- **Enforcement.** The ctx a handler receives carries its action's
  declared touches: a `ctx.create` of an undeclared kind or a
  `ctx.invoke` of an undeclared pair refuses with the error naming the
  missing declaration — always, not only under test. Engine surfaces
  stay exempt **by construction, never by flag**: seeds are already
  declared (`Owns.seed`), the cascade runner never rides the app ctx,
  `ctx.defer`'s job row is E6's own surface, and `approval_request.run`
  — whose target is data a human just approved — declares
  `Delegated("…")`: the `Acknowledged` discipline, the hatch is a
  sentence.
- **Conformance.** The suite invokes every touching action and reads
  the log: every non-`may` declared touch must appear as a correlated
  transition by the invoking actor, and every correlated same-actor
  row must be a declared touch (seeded descendants of created kinds
  excepted — they are the child's own declaration).

The three evidence shapes, resolved:

- **Carve-out.** `Creates` the peer; the parent's decrement is the
  transition's ordinary self-write; the *move* is the tripwire
  finding. Re-parenting a child means writing its `via` ref — a data
  change on a resource whose narrative the parent's machine does not
  own. A parent handler writing child rows directly would be a
  resource mutated outside any transition of its own: the exact
  quantum that idempotency, replay, and follower accountability anchor
  to. So the move is a declared **child action** (`reassign`),
  advanced via `Advances` — each moved row gets its own log entry, its
  own guards, its own replay discipline. There is no `Moves`
  declaration because there is nothing lawful left for it to say.
- **Transfer pair.** One action `Creates` the second leg and links the
  pair by `Ref`s, atomically, one correlation — the intake twin that can
  no longer be born alone. *Standing* cross-leg consistency is not
  this mechanism: between peers with no owning machine it would be a
  fourth kind of thing; the recommended modeling is the fold the intake
  mapping itself proposed — a `transfer` kind that owns both legs,
  where the invariant is an ordinary E4 cascade.
- **`complete_all`.** A parent transition declaring
  `Advances("assignment", "complete", may=True)`; the handler selects
  children by the target's `from_` states and invokes each —
  synchronous, atomic, retry a natural no-op. Where E4's async cascade
  attributes children to `waymark-cascade`, these rows are **Devon's**:
  *bulk-completed by Devon*, distinguishable from row-by-row work,
  which is precisely what the raw UPDATE destroyed.

What this deletes: the carve-out service method and its scattered
twice-checked guards; the client-supplied arithmetic the stale-total
guard existed to distrust (the server re-reads rows under its own
transaction); the synthetic double-sided audit diffs; intake's
either-side-either-direction transfer join and the orphaned twin; and
the bulk UPDATE's false attribution. And one thing becomes
unrepresentable rather than fixed: a handler whose writes out-run its
advertisement — composition's `check=`, closed for the write path.

**Punts, classified:**

- *Refused on principle, not punted*: parent handlers writing owned
  children's data directly. The quantum holds because we keep it.
- *Was here, now shipped*: blob side effects of carve-outs — closed by
  `attachment.duplicate` + `BlobStore.copy` + the `blob-copy` consumer
  (see E5's duplication paragraph).
- *Evidence-thin, awaiting a driving case*: standing peer invariants
  without an owning kind (the transfer fold covers the known case);
  declared touch surfaces on `create` (`Seed` is the declared path);
  generated fan-out handlers (the `complete_all` loop is four lines —
  earn the abstraction).

The admission test, passing: no new wire vocabulary beyond an additive
`effect` key; no new event class; no new resource; the quantum
untouched — every touched resource still transitions through its own
machine, one row each, one correlation. The new thing is only a
declaration over calls that were already lawful, which is why this is
E8 and not waymark4.

# E9. Create is guarded like everything else

The scar, from inside the framework and out: `create` ran no guards, and
three recorded caveats stem from that one gap. Holdings recon's
`validate_recon_timestamps` (`recon_event_service.py:546`) fires **on
create** of an OTTO recon event — the E1 warning protocol at the one
moment the engine could not run a warning; the E1 walker consequently
had nothing to acknowledge at create. `Grant.on_create` hand-raised a
`GuardRefused` for the unregistered-role check because there was no
guard surface at create — enforcement smuggled through a data hook, with
the refusal shape rebuilt by hand. And the notes admitted the gap
outright. One missing surface, three workarounds.

The declaration:

```python
class ReconEvent(Resource):
    create_guards = (desk_covers_fund, over_threshold)   # severity as on actions
```

Create guards judge the **validated create input**: their checks are
called `check(None, data, ctx)` with `r=None` — no instance exists yet —
`judges=` names create-model fields (`Create`, else `Data`), and
`accepts=` constrains input as on any guard.

- **Enforcement.** `_create_core` evaluates them after validation and
  before any side effect — predecessor resolution, `on_create`, and the
  insert all wait on the verdict. The severity split is the action
  path's (E1), verbatim: refuse-severity Deny raises the same
  `GuardRefused` shape minus the `resource=` embed (nothing exists to
  render); an unacknowledged warning raises `warning-required` with the
  identical acknowledge affordance; an acknowledged one passes AND lands
  as the create transition's `acknowledged` column. Child creates
  (`ctx.create`) cannot acknowledge — the enclosing actor's header does
  not travel.
- **Dry run.** A kind with declared create guards opens a session for
  `POST /{plural}?dry_run=1`: refuse-severity raises, pending warnings
  ride the body (`{"valid": true, "warnings": […]}`) — the same
  blur-time truth actions already tell. Guard-less kinds keep the
  schema-only, sessionless dry run.
- **Definition checks.** `check_create_guards` holds `judges` to the
  create model's fields and explain templates to the
  `check_guard_templates` coverage rule, at import.
- **The walker** acknowledges advertised warnings on create retry,
  exactly as `_walk_invoke` does for actions.

What this deletes: `Grant.on_create`'s hand-raised `GuardRefused` (the
role check is now `create_guards = (role_registered_create,)` — same
sentence, same remedy, one declaration), the "warnings can't gate
create" caveat holdings recon's intake rule ran into, and the class of
enforcement that hides in `on_create` because there was nowhere declared
to put it.

**Caveats, recorded:** create guards are enforced, not yet projected
(the create schema does not yet carry `warnings` or fold create-guard
`accepts=` into enums — dry run is the honest preview); the closure
rule is not applied to the create surface yet; `vars_fn=` never
garnishes a create refusal (`r=None` — put template needs in
`Deny(vars=…)`); approval-mode creates run with no acknowledgments, so
a warning-severity create guard fails the run with the warning in
`outcome`.

---

## The finding table

Filled as sections land: each mapping-series finding → its mechanism →
what the apps hand-built that now deletes.

| Series finding (apps) | Mechanism | What deletes |
|---|---|---|
| two-tier guards (payouts, holdings recon, price validation) | E1 `Guard(severity="warning")` | three `is_warning` dialects, their override flags, and the unaudited override |
| uniqueness + conflict links (4 apps) | E2 `unique = (…)` | `already_exists` payload dialects, `existing_id` client plumbing, check-then-insert races |
| four-eyes (payouts, cash recon, holdings recon) | E3 `four_eyes(of=…)` | client-side self-comparisons, hand-stamped `prepared_by` columns, the advertised-but-refused approve button |
| composition between kinds (6 apps) | E4 `Owns(via=…, on=…, rollups=…)` | browser cascade sagas, counter columns, dashboard rollup SQL, client-side child predicates |
| attachments (payouts, cash recon, holdings recon) | E5 `attachment` kind + `BlobStore` | timer-sequenced uploads, base64-through-JSON, per-app S3 conventions, byte-destroying "soft" deletes |
| job resource / async egress (5 implementations) | E6 `ctx.defer` + job artifacts | queue tables, poll buttons, blocking syncs, masked errors, write-only telemetry |
| period chaining (holdings recon, price validation) | E7 `Predecessor(order=…, partition=…)` | latest-prior-row date arithmetic, prose-comment provenance |
| multi-resource transitions (payouts carve-out, intake transfers, onboarding complete_all) | E8 `touches=(Creates(…), Advances(…))` | the carve-out saga method, either-direction transfer joins, bulk-UPDATE misattribution, and the undeclared-write bug class |
| warning-gated creation (holdings recon's recon-timestamp intake rule) | E9 `create_guards` | on_create enforcement hacks, the "warnings can't gate create" caveat |
