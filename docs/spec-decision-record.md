# Spec — the decision record: why it was allowed

**Thesis.** Refusals narrate. Approvals are silent. A committed transition
records who moved the row, from where to where, and under which law revision —
and not one word about the judgment that let it through. Close the asymmetry,
but close it cheaply: **most of "why" is already recoverable, and only one
part is not.** Which guards judged is a function of
`(kind, action, law_revision)` and the stored fingerprint. What those guards
*read* is gone the instant the transaction commits. Record that, and derive
the rest.

## Epistemic status

The gap is real and old. `docs/waymark10-design.md:2161` carries it as a named
punt — *"Judgment blast radius (newly-refused rows) unmeasured"* — and
[spec-time-travel](spec-time-travel.md) carries its twin: the log records what
happened, not what the row looked like. This spec is the narrow half of both,
and it is narrow on purpose.

What makes it possible here and nowhere else is the same property the rest of
this framework keeps cashing: the law is a tree in a table. `judgment.clj` can
already rebuild any stored revision's guard vector; a decision record therefore
does not need to store its own copy of the law, only the values the law looked
at. In a system where authorization is code, the record would have to be the
whole story or nothing, because nothing else could reconstruct the question.

The trap is treating this as an audit feature and writing everything. Write
amplification on the one write path is not a performance footnote — it is
bytes on every transition of every kind forever, and the framework has one
recorded posture about that (`:retain`, proposed in the time-travel spec):
**default off, grown by declaration.**

## What exists

`waymark10_transitions`, projected at `store/postgres.clj:70-98` (a
`table-ddl` render, not a literal), written at three sites in
`server/invoke.clj`:

| site | lines | append at |
|---|---|---|
| `finish!` — the declared action | 616-682 | 642-658 |
| `create-in-tx!` — the birth | 1486-1628 | 1576-1590 |
| `adopt!` — the engine's law restamp | 757-797 | 780-793 |

Columns: `id kind resource_id action from_state to_state actor at law_revision
input_digest inputs acknowledged correlation_id idempotency_key summary`.

Three of those matter here.

- **`law_revision`** — on every row. With `judgment/resolve-action` and the
  definition row's stored fingerprint, it determines the exact guard vector
  that judged. The basis is already a lookup; it has simply never been looked
  up.
- **`acknowledged`** — "names of warning-severity guards the caller
  acknowledged". This is a decision record. It is the only one, it covers one
  severity, and it exists because someone needed it once.
- **`inputs`** — nullable, written only under `:record true`. Precedent for a
  per-kind opt-in on this very table, and `chore.clj`'s comment is the reason
  it exists: *"an audit of the blank-form era found 13 of these transitions and
  not one recoverable value behind them."*

What is thrown away, precisely: `run-guards` (`invoke.clj:558-569`) reduces over
`(:guards defn')` calling `g/evaluate`, and drops every allow on the floor
(`if-not (t/deny? v) acc`). It returns `{:warned […] :overridden […]}`.
`invoke-in-tx!` (962-973) destructures `overridden` alone and hands it to
`finish!`. By the time the transition map is built, every verdict, every
denier, every `:vars` value is gone. The create path is identical
(`create-guard-pass`, 1344-1368).

What is *still in scope* at the append site and needs no new plumbing: `defn'`
— already law-resolved at `invoke-in-tx!:899` via
`judgment/resolve-action rdef defn' (:law-revision row)`, so `(:guards defn')`
is the exact tree that judged — plus `row`, `inp`, `principal`, and the row's
revision. Note that `finish!` receives `ctx`, not `guard-ctx`
(`= (dissoc ctx :invoke :create :inner-sink)`, `invoke.clj:905`).

## The design

### Two halves, and only one of them is stored

**The basis is derived, not written.** A read of a transition answers with the
guards that judged it, reconstructed from `law_revision` + the definition row's
fingerprint through `judgment/rebuild-guards`. This costs no column, no byte,
and no migration — and it is **retroactive**: every transition ever logged
gains an answer the day this ships, which no stored record can offer.

**The evidence is written, and declared.** A kind declares

```clojure
:retain {:judgment true}
```

and each committed transition then carries a `judgment jsonb` column:

```json
{"revision": 12,
 "guards": [{"name": "opener-is-recipient", "verdict": "allow",
             "reads": ["principal"], "read": {"to": "iris"}},
            {"name": "letters-are-paced", "verdict": "allow",
             "reads": ["principal", "now"], "opaque": true}],
 "acknowledged": ["letter-is-long"]}
```

`:retain` is one map with two entries — `{:data true}` is
[time travel](spec-time-travel.md) tier 3's, `{:judgment true}` is this one —
so an engine grows at most one new declaration key across both specs, and
either one alone is a partial declaration of the same intent.

### What `read` may contain, and why it is small

**A guard's evidence is exactly its declared `:vars`, evaluated over the scope
it judged.** For an expression guard that is `(:vars g)` over
`{:data (:data row) :input inp :now (:now ctx)}` — the same map `expr-check`
already builds at `guards.clj:145`, and the same values the refusal sentence
would have interpolated had the guard denied.

That is the whole rule, and it is worth stating as the spec's second thesis:
**the decision record is the refusal sentence the guard did not have to give.**

It bounds the bytes by construction (a guard declares two or three vars, not a
document), it needs no new authored surface, and it creates the right
incentive: an author who wants a fuller record declares fuller `:vars`, and
gets a better refusal sentence for free.

**A code guard records nothing but its name and its declared `:reads`**, marked
`"opaque": true`. Its `:check` is a function; the fingerprint already refuses
to store more than its hash (`fingerprint.clj:46`), and a decision record that
guessed at a closure's inputs would be the forms-only rule broken from the
inside. Same boundary, drawn twice, deliberately.

### Where the bytes stop

- **Secret fields are never captured.** `schema/secret-fields` is subtracted at
  *write* time, not projected at read time. Cheaper and stricter.
- **Reads project through the grant.** Everything else in `read` is a field
  value, so a history read of a decision record must pass the same visibility
  closures a row read does (`grants/visibility`, `project-json-schema`). This
  is [spec-time-travel](spec-time-travel.md)'s one security clause inherited
  verbatim, and it is the reason this spec should land *before* the history
  route rather than after: a route that ships an unprojected `judgment` object
  is a disclosure channel with a URL.
- **`adopt!` writes no judgment.** A restamp is the engine moving a row's law,
  not a judgment of it; there are no guards to record and inventing an empty
  object would make the column lie about coverage.
- **Dry runs write nothing.** Already true and must stay true: `partial-verdict`
  (`invoke.clj:594-614`) builds `{:judged :awaiting :warnings}` and is
  rehearsal-only. A rehearsal that left a record would break the §23 obligation
  that a dry run "neither demands, consumes, nor RECORDS" anything.

### The plumbing, exactly

Four edits for the column, following the store's own convention — there are no
numbered migrations; the desired shape is data and `migrate/plan` diffs it:

1. one column map in `pg/engine-projections` (`postgres.clj:91`),
2. one column + param in the INSERT (`postgres.clj:546-563`),
3. one line in `transition->map` (`postgres.clj:234-250`) — a column absent
   here is invisible to every reader,
4. one `jsonish` line in `memory.clj:273-275` (the twin conjes records
   verbatim, so a field Postgres silently drops will *pass* against memory —
   the twin is the weaker witness here, and the obligation must run against
   both).

`migrate/plan` emits the `ALTER TABLE … ADD COLUMN` for existing databases and
the boot refuses to serve until it is applied. Engine-table columns are not
part of any kind projection, so this mints **no law revision**.

And two in the invoke path: `run-guards` (558-569) returns a third key,
`:basis`, alongside `:warned`/`:overridden`; `invoke-in-tx!` (962-973) threads
it to `finish!`. Nothing is re-evaluated — the basis is built in the loop that
was already running, so a kind with retention off pays one `nil` check.

### The read surface

A decision record has no route of its own. It rides
`GET /api/{plural}/{id}/-/history` — [time travel](spec-time-travel.md) tier 1's
route — as a `judgment` object per transition, derived where nothing is stored
and derived-plus-retained where something is. That is also the moment
`waymark-zp5` closes: `mcp/history`'s local projection is deleted rather than
widened, which is precisely the complaint that bead was filed to record.

## Recorded punts

- **The pre-record horizon.** Retention starts the day it is declared. Older
  transitions answer with the derived basis and no evidence, and the envelope
  says so rather than implying the guards read nothing. Same posture as the
  time-travel spec's pre-law horizon.
- **Guard order is positional.** The fingerprint's guard list is positional
  (`fingerprint.clj`, `diff-fingerprints`), so reordering a guard vector reads
  as a changed law and the derived basis for an old revision is only as good as
  the stored fingerprint — which is exactly as good as `judgment.clj` already
  is. Not a new weakness; a shared one.
- **Composite deniers.** An `{:all […]}` records under the composite's
  generated name (`a&b`), because that is the name the guard vector carries.
  Naming the arms would mean recording law the declaration folded on purpose.
- **Handlers are not judged.** A handler can refuse by throwing; that is a
  failure, not a verdict, and it never reaches the append. The record covers
  the guard layer and says so.
- **No "why refused" record.** Refusals do not commit, so they have no
  transition to hang from. A refusal record is a log, not an audit trail, and
  it wants a retention policy, a volume story, and an abuse-detection use case
  that nobody in this house has asked for. Named so it is not re-proposed.

## Effort

**Medium.** The derived basis is a small function over machinery that already
exists and is the larger half of the value. The retained evidence is four
store edits, two invoke edits, one declaration key, and the two locks on field
visibility — of which the read-time grant projection is the only part that can
be got wrong quietly.
