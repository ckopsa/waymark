# Spec — addressed notice: the queue reaches a person

**Thesis.** Delivery is built. **Addressing is not.** A subscription names a
URL; nothing names a *person*. The missing primitive is "who cares about this
row?" — and as of the assignee ref, waymark can finally answer it.

## Epistemic status

This is the most product-shaped spec here and the least framework-shaped, which
is a reason for suspicion: notification systems are where well-designed engines
go to accumulate cruft. It earns its place on one observation — a household work
queue that nobody is told about is a queue people forget to check, and the whole
`workqueue10` fold was justified by putting the household's work in one place.
One place nobody looks at is not obviously better than three.

Build it when someone misses a chore and says so. Not before.

## What exists

More than you would expect:

- `server/webhooks.clj` — `:subscription` is already an engine-served resource
  (url, kind filter, optional secret). Delivery is at-least-once off the
  transition log, one cursor per subscription in `waymark10_cursors`, drained on
  every wake, cursor persisted per delivered event. "The events dispatcher is
  only the wake signal; the log carries truth."
- `server/consumers.clj` — named durable log consumers with the same cursor
  discipline, a parking failure mode, and registration-time seeding so history
  is not replayed into a new consumer.
- `server/events.clj` — SSE, per-kind, with the transition payload shaped.
- `server/members.clj` — people are rows, with `display`, `handle`, roles, and
  actor type.

So the outbox, the cursors, the retry posture, and the payload all exist. What
does not exist is the sentence *"this transition concerns Jack."*

## Why now: the ref is the addressing primitive

Until this week, `chore.assignee` was an open vocab word. A notice rule over it
could only have matched a *spelling* — and `members.clj` records exactly why
that fails: two members sharing a handle "send every ref that matches it to
whichever row the query returns first," and an assignee with no handle set
resolves to nobody at all.

Now `chore.assignee` is a `:waymark/ref` to `:member`. "Notify the assignee" is
a join the engine can make correctly, once, for every kind that declares such a
field. That is the unlock, and it is why this spec is written now rather than
six months ago. It also means `waymark-aqj` and `waymark-i6i` (the remaining
vocab assignees, on `chore_run` and `prep_task`) are prerequisites, not
neighbours: a notice addressed to `prep_task.assignee` cannot be built until
that field names a person.

## The design

A `:notice_rule` resource — the subscription's sibling, addressed to a member
rather than a URL:

```clojure
{:kind :chore
 :when {:to_state "active" :action :queue}   ; a transition-shaped match
 :address {:field :assignee}                 ; the ref that names the person
 :channel :telegram
 :quiet {:from "21:00" :to "07:00" :zone "America/Denver"}}
```

- **`:when`** matches the transition record — the same fields the SSE payload
  carries. Deliberately *not* an expression language in the first cut: equality
  on `action`, `from_state`, `to_state`, and kind. If that proves too blunt,
  `expr.clj` is already the answer and already gated.
- **`:address`** names a ref field on the row whose target is `:member`. Checked
  at assembly like every other ref: unknown field, wrong target kind, or a
  non-ref field is a definition error, not a runtime surprise.
- **`:channel`** is a member-held destination, not a rule-held one — the rule
  says *notify the assignee*, the member row says *how to reach me*. This keeps
  one person's contact details in one place and out of N rules.
- **`:quiet`** holds delivery until the window closes and coalesces what
  accumulated into one digest. A household queue that pages at 03:00 gets
  muted, permanently, by a human.

**Delivery reuses the deliverer verbatim** — one cursor per rule, at-least-once,
park on throw. Nothing new about the hard part.

**Channels.** The engine should own exactly one transport concept — an
outbound HTTP POST it already knows how to make — and let the household's own
MCP (Telegram, email) be the thing on the other end. Resist growing an SMTP
client, a push-certificate story, and a Telegram bot token inside waymark10.

## Recorded punts

- **No read receipts, no per-notice state.** A notice is a delivery, not a row.
  If "did Jack see it?" matters, that is a different feature and probably an
  `intents`-shaped one.
- **Self-notification.** The actor who caused a transition should not be told
  about it. One clause, easy to forget, deeply annoying when missing.
- **Unaddressed rows.** A chore with no assignee matches no address and notifies
  nobody. That is correct, and it is also how an unassigned backlog goes
  unnoticed — a household-level digest is the answer, not a fallback recipient.
- **Escalation, snoozing, per-rule preferences.** All plausible, none in the
  first cut. This is the accumulation risk named at the top.

## Effort

**Medium**, almost entirely in the addressing and the quiet-hours digest. The
delivery half is done.
