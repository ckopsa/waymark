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
