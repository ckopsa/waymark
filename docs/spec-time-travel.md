# Spec — time travel: as-of reads, in two dimensions

**Thesis.** `waymark10_transitions` is a complete record of every write this
engine has ever made, and nothing can query it as history. Two of the three
tiers below are nearly free; the third needs a decision about bytes, and this
spec exists mostly to force that decision.

## Epistemic status

Bitemporality — *"the board as it stood July 1, judged by July 1's law"* — is
rare in large systems and essentially unheard of in small ones. Waymark is one
declaration away from it because it already stamps every transition with the
law revision that judged it. The trap is assuming the whole thing is free. It
is not: **the log records what happened, not what the row looked like.**

## What exists

`server/store/postgres.clj:73` — the transition log's columns:

```
id  kind  resource_id  action  from_state  to_state  actor  at
law_revision  input_digest  inputs  acknowledged  correlation_id
idempotency_key  summary
```

Note what is present and what is absent. `from_state`/`to_state` are on every
row. `law_revision` is on every row. `actor` is on every row. `inputs` is
**nullable and written only when the action declares `:record true`** — and
`chore.clj`'s own comment records why that matters:

> the overwrite writes the WHOLE detail set and is declared non-reversible, so
> the log has to carry what was written — an audit of the blank-form era
> (waymark-wnh) found 13 of these transitions and not one recoverable value
> behind them.

That comment is this spec's origin story. Someone already went looking for the
past and found it gone.

## The three tiers

### Tier 1 — state and law as-of (free)

A fold over `(kind, resource_id, id)` — an index that already exists — gives,
for any instant: the row's state, the law revision judging it, the actor who
put it there, and the action that did. No new storage, no new column.

Wire: `GET /api/chores/{id}?as-of=2026-07-01T00:00:00Z` answers the envelope
with `state`, `law_revision` and `summary` as of that instant, and a
`x-as-of` marker so no client mistakes it for live. Collections take the same
parameter and answer the set of rows that existed then, in the states they held.

Also free, and arguably the more useful surface: `GET /api/chores/{id}/-/history`
— the row's transitions as a first-class read rather than a debugging query.

### Tier 2 — law-aware replay (free, given tier 1 and the sweep)

Because tier 1 recovers the revision, and `judgment.clj` can serve any stored
revision's guard trees, an as-of read can be *judged as it was judged then* —
the actions that were available on July 1, not the ones available now. This is
the tier that makes the feature more than nostalgia: it is how you answer "why
was this allowed?" a year later, and it shares its whole mechanism with
[the law sweep](spec-law-sweep.md).

### Tier 3 — data as-of (needs a decision)

The log does not carry the row's data, so `data` at an instant is **not
recoverable today**. Three ways out, and they are not equal:

- **(a) An `after` jsonb column on the transition.** Every committed write
  stores the resulting `data`. Simple, exact, replay-free, and pairs with the
  existing `summary` column. Costs bytes proportional to write volume ×
  document size.
- **(b) Universal `:record true`.** Insufficient, not merely expensive:
  handlers compute (`queue-run` mints a child; the mirror's observe writes a
  whole document), so inputs do not determine the result. Rejected.
- **(c) Periodic snapshots + replay.** Requires pure, replayable handlers —
  which waymark does not have and should not promise, since handlers reach the
  `ctx :create` door and push-on-write crosses process boundaries. Rejected.

**Recommendation: (a), declared per kind.** A `:retain {:data true}` entry on
the resource turns it on where history is worth bytes and leaves it off
elsewhere — the household's chores yes, a high-churn mirror's rows probably no.
Default off, so no existing engine's storage grows without a declaration.

## Recorded punts

- **The pre-law horizon.** Rows written before their kind's first definition
  revision carry a nil stamp; tier 2 serves them the resident guards, as
  `judgment.clj` already does. An as-of read before the horizon is honest about
  it rather than guessing.
- **Deleted rows.** Waymark retires rather than deletes, so the log is
  complete — but a hard `DROP TABLE` (every test fixture does it) severs the
  history from the rows. Not a bug; worth one sentence in the docs.
- **Tier 3 retention.** Retained data is a *copy of the document at a past
  instant*, which means a redacted field's old value survives a redaction.
  Grants project the schema (`grants/project-json-schema`); an as-of read must
  project through the SAME visibility, or time travel becomes a disclosure
  channel. This is the one security-shaped clause here and it is not optional.

## Effort

**Tier 1+2: small.** A fold, a query parameter, a route. **Tier 3: medium**,
and mostly the retention declaration, the migration that adds the column, and
the grant projection above.

## Amendments (2026-08-23, waymark-442.1)

**The DDL citation is stale in form, not in content.** `store/postgres.clj:73`
is no longer a literal `CREATE TABLE`: the transitions table is a projection in
`pg/engine-projections` (`postgres.clj:70-98`) rendered by `table-ddl`
(`:49-59`), and `migrate/plan` diffs it additively against
`information_schema`. The column list above is still exactly right. Adding one
is four edits — the projection, the INSERT's column and param (`:546-563`),
`transition->map` (`:234-250`, a column absent here is invisible to every
reader), and one `jsonish` line in `memory.clj:273-275` — and it mints **no law
revision**, because engine-table columns are not part of any kind's
fingerprint.

**Tier 2 and the sweep share less than advertised.** `waymark10-next.md` says
they "share their whole mechanism". They share `judgment/rebuild-guards`. They
do not share *revision acquisition*, and that is the expensive half: the
sweep's two revisions are both in hand under a propose hold, while tier 2 needs
an **arbitrary historical** revision — and `:judgment-laws` deliberately holds
only the revisions that must be served. `install-current!` dissocs the newly
promoted revision (`definitions.clj:491`) and `sweep!` dissocs on supersede
(`:415`). Tier 2's real work is therefore an on-demand fingerprint loader from
the definition row (`:law-ids {revision → row id}` already exists on the rdef)
plus a cache keyed like `:judgment-cache`. Build the sweep first — but do not
budget tier 2 as free.

**One `:retain` map, not two.** Tier 3's proposed `after` jsonb column and
[the decision record](spec-decision-record.md)'s `judgment` column are the same
migration shape and the same posture (default off, grown by declaration). They
share one key: `:retain {:data true :judgment true}`. Either alone is a partial
declaration of the same intent, and an engine grows at most one new opt across
both specs.

> **Landed 2026-08-24 (waymark-442.5).** The `:retain` key exists —
> `declaration/top-level-keys` carries it, `resource/normalize-resource` closes
> it to exactly `{:judgment bool? :data bool?}`, and `fingerprint-of`'s
> whitelist does not name it, so declaring retention mints **no law revision**
> (pinned by `mealplan10.style-invariance-test`). Tier 3 adds its `after`
> column and reads `(get-in rdef [:retain :data])`; nothing else about the key
> is left to build.

**Tier 3's security clause is inherited, and it decides the order.** "An as-of
read must project through the SAME visibility, or time travel becomes a
disclosure channel" applies verbatim to a decision record's read values — they
are field values. So the decision record lands **before** the history route,
and the route ships with the projection rather than gaining it. A route that
shipped an unprojected `judgment` object would be a disclosure channel with a
URL.

**`waymark-zp5` closes here, in one move.** When
`GET /api/{plural}/{id}/-/history` lands it carries the judgment object too, so
`mcp/history`'s local projection is **deleted** rather than widened. That bead
was filed precisely to record that a transition's meaning had two homes; adding
a third first and unifying second would be the wrong order.

## Built (2026-08-24, waymark-442.4)

Tiers 1 and 2 landed on the amendments. Tier 3 is still the punt this spec was
written to force, and `:retain {:data true}` is still where it will go.

`waymark10/src/waymark10/server/history.clj` is the whole computation — the
history document, the two as-of folds, the honesty notes, and the `law-of`
seam that decides how a revision's law was reached.
`waymark10.server.definitions/stored-fingerprint` is the on-demand loader the
amendment asked for. `waymark10.server.router` mounts
`GET /api/{plural}/{id}/-/history` and forks the row and collection reads on
`?as-of=`. `waymark10.server.mcp/history` lost its local projection.

### The loader, and where its cache lives

The amendment was right that this was the expensive half, and it is still
small: `:law-ids {revision → definition row id}` was already on the rdef,
written by `boot-revise!` over **every** stored definition row of the kind
whatever its state, so an arbitrary historical revision is one `load-row` away.
`stored-fingerprint` tries `:judgment-laws` first (free when the revision is
one the engine must serve anyway), then its cache, then the row; only hits are
remembered, because a miss is waiting on a write that may land a second later.

The cache lives in the **registry atom**, under `:law-fingerprints`, and not on
the rdef. `install!` rebuilds `[:kinds kind]` wholesale on every law install
and hands the kind a fresh `:judgment-cache`, so an rdef-borne cache of
historical law would be discarded every time a proposal moved. The registry's
top level survives that, and it is safe to survive it: a revision's stored
fingerprint is written once and never rewritten. Rebuilt *guard vectors* still
cache on `:judgment-cache` by `[revision action]`, shared by identity with the
live rdef — the overlay is `assoc`'d onto a local copy — so the reset costs a
rebuild and never a wrong answer.

### Tier 2 is two answers, and the document says which

With [the decision record](spec-decision-record.md) landed first, the *"why was
this allowed"* question splits exactly as that spec predicted, and every
transition on `/-/history` wears both halves plus a label:

- `basis` — **derived**, from `decision/basis` under the law of the day. Free,
  retroactive, and present on every transition ever logged.
- `judgment` — **stored**, for a kind that declares `:retain {:judgment true}`,
  projected through the caller's grant.
- `evidence` — `recorded`, `before_the_record`, or `not_retained`. A retaining
  kind's pre-retention transitions say so rather than answering with an empty
  object; a kind that never declared retention says *that*, and still answers
  which guards judged.

`basis.law` grew a fourth value here. `decision/basis` answers `:resident` both
when the resident code genuinely **is** that law (the pre-law horizon, the
current revision, a pilot) and when the revision simply could not be found —
one word for a true sentence and a hopeful one. The loader knows which
happened, so `history/law-of` names the second `unrecoverable`, and the notes
say the guards listed are today's.

### The three departures

**No re-judgment of today's document.** The spec's tier 2 asks for *the actions
that were available on July 1st*. This does not answer that, and the omission
is the whole reason the decision record landed first: the log records what
happened, not what the row looked like, so re-judging July's law against
today's document is a plausible-looking wrong answer. Tier 3 is what closes it.

**The as-of read is not an envelope.** The spec says it *"answers the envelope
with `state`, `law_revision` and `summary`"*. It answers a dedicated `as_of`
document instead, with the `X-As-Of` header the spec asked for. An envelope
carries `data`, `actions`, `links` and an ETag, and every one of them is a
statement about now: the data is not recoverable at all, the actions would be
today's doors probed against today's document, and a client whose first rule is
*follow the envelope's own href* would find live verbs hanging off a historical
document. The `summary` is not reconstructed either — `invoke` renders it at
write time and the transition keeps the sentence, so the as-of read answers
with the words the household actually read that day.

**An as-of collection takes no filters.** The collection grammar queries stored
`data`, and `data` at a past instant is exactly what the log does not carry. A
filter answered against today's rows would name a set nobody could describe, so
any parameter beside `as-of` is a 422 that says why. The fold is bounded, and
`complete` is false only when the bound was reached *before* the instant asked
about — reaching it after costs nothing, because those transitions could not
have changed the answer.

### The disclosure clause, inherited rather than reinvented

`decision/project` already existed, beside the write, and the route calls it
with `(fn [field-name] → bool)` built from the same `:field?` closure a row read
narrows its data with. Concealment is the row's and is checked first, so a row
a grant hides has no history either. A transition's stored `inputs` are
deliberately **not** served: a raw input map has no field projection of its own,
and growing one would be a second visibility surface with its own bugs.

### `waymark-zp5`, closed by deletion

`mcp/history` is now `(call (request session :get ".../-/history"))` and nothing
else — a pass-through like the other five. The projection it used to choose for
itself is gone rather than widened, which was the whole content of that bead.

### Proved by

`waymark10.time-travel-test` boots twice over one storage, the law sweep's
shape for the opposite reason: the sweep needs two laws in hand at once, and
this needs a law that is **gone** — a revision the engine promoted past, whose
rows all restamped, whose `:judgment-laws` entry `sweep!` therefore dropped.
The severity of one guard moves between the boots with its name and position
held fixed (`rebuild-guards` substitutes positionally *and* by name, so a
rename would prove nothing), and a transition stamped revision 1 answering
`warning` while the resident code says `refuse` is the proof that the law of
the day served. `packs/core` grew `:core/history`, which holds every engine to
the retention agreement from the wire rather than from the column.
