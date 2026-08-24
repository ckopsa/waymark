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
