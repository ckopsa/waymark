# Spec — events: one matcher and one consumer for every kind of listening

**Purpose.** This document gives the requirements for one predicate
over a committed transition, one durable consumer per listener over the
transition log, and the receivers a listener can name. Today the engine
has seven or more matchers that share no core, and each one reads an
empty list in its own way. A seat's wake, a notifier, a notice rule, a
webhook, the SSE feed, a seat's inbox and its cues each decide "does
this transition concern me" in different code. This spec makes that one
function, and it makes the listening itself one machine.

This document is written in ASD-STE100 Simplified Technical English.
Technical names from the codebase keep their spelling. "Must" gives a
requirement. "Can" gives a permission. "Is" gives a fact.

Ticket: 5b759712, under the epic 215e28f4 (a seat is a member, a grant,
a subscription that opens its fire door, a lease and a runner link).
Design: this pass, 2026-10-23. It merges as a document. Its build
tickets (section 8) are filed from it, under the same epic.

Requirement ids are E-n.m.

## 1. The survey (2026-10-23)

Each line names the function, its file, and what an empty list means.
Line numbers drift; find each one by name.

| Listener | Function | Fields it reads | Empty action list |
|---|---|---|---|
| seat wake | `wakes/matches?`, `wakes/moved-under?` | kind, actions, filter (moved row, after commit), at_least / at_most | NOTHING on a transition wake; EVERY action on a count wake |
| notifier | `held_calls/hears?` | `on: [{kind, actions}]` | every action |
| notice rule | `held_calls/rule-matches?` | kind, `when.action`, `when.from_state`, `when.to_state` | blank matches anything |
| webhook | `webhooks/wants?` (private) | kinds, skip_actors, domains, service_alerts | no action field; an empty `kinds` is every kind |
| SSE | `events/wants?` (private) | class, one resource, or kinds | no action field; no kinds is every kind |
| seat inbox | `routes/seats/inbox-match` | `inbox.only {kind [actions]}`, the seat's scope, domain, own actor | every action |
| seat cue | `routes/seats/cue-match` | kind, actions, filter (row now, `grants/row-matches?`) | every action |
| walkthrough step | `walkthroughs/agent-step-ended!` | the step's door and row | not a list |
| cracks audit | `cracks/tellers` (private) | a fourth copy of the notice rule, subscription and cue readings | as each source |

Three helpers are already shared by two of them, and this spec keeps
them: `webhooks/by-actor?`, `webhooks/in-domains?` and
`grants/row-matches?`.

The durable machine is also split. `server/consumers` is the named
cursor with at-least-once delivery. Wakes (`:wakes`), the notifiers and
notice rules (one consumer), the walkthroughs, the quests, the
schedules mirror, the judgments, the invitations, the integrations and
the effect runner each register one. The webhook deliverer does NOT
use it: it keeps one cursor for each subscription (`webhook:<id>`)
behind one thread. The `consumers` namespace records that this split
waits "until a third consumer of the pattern exists". Wakes are that
third consumer.

### 1.1 The departure this spec records

spec-seat R-12.22 says: "Each entry is a subscription over the
transition log. [...] The receiver of a `wake_on` entry is not a URL.
The receiver is the seat's `fire` door." The build did not do this.
The namespace note of `wakes.clj` gives the reason: a subscription row
is an endpoint, a secret and a delivery log, and a wake has no URL and
nothing to sign. So a wake rides its own consumer and no subscription
row is made. The spec was not changed. This document is the record of
that departure, and section 4 removes its cause: a receiver becomes
data on the subscription, and a URL is one receiver of four.

## 2. The predicate

**E-1.1** The engine must have one predicate of a listener entry and a
committed transition, in a new namespace `waymark10.server.listen`:

```clojure
(listen/matches? eng entry t)   ; → boolean
```

Every listener in section 1 must judge a transition with it. No other
function may compare a transition's kind or action to a listener's
list.

**E-1.2** An entry is a map. Each field is optional.

| Field | Type | Matches when |
|---|---|---|
| `kinds` | list of kind names | the transition's kind is in the list |
| `actions` | list of action names | the transition's action is in the list |
| `from` | list of state names | the transition's from state is in the list |
| `to` | list of state names | the transition's to state is in the list |
| `ids` | list of row ids | the moved row's id is in the list |
| `domains` | list of domain names | `webhooks/in-domains?` passes for the set |
| `skip_actors` | list of actor addresses | `webhooks/by-actor?` is false for the set |
| `filter` | list of filter maps, the grammar of a scope entry's `filter` | `grants/row-matches?` passes on the moved row as it stands after the commit |
| `service_alerts` | boolean | with `domains`: also passes a service alert of a seat in those domains (today's webhook reading) |

All the stated fields must hold (AND). Inside one list, any member can
hold (OR).

**E-1.3** An empty list and an absent field have ONE meaning: the field
does not narrow. An empty `actions` is every action. An empty `kinds`
is every kind. This is how the notifier, the notice rule, the inbox,
the cue and the webhook read it today. Only the transition wake reads
it as "nothing", and E-6.2 migrates it.

The reason: "nothing" is not a listener. An entry that can match no
transition must be refused when it is written, not kept as a row that
looks active and does nothing. A seat's `wake_on` entry `{kind task,
actions []}` today is that row.

**E-1.4** The `filter` reads the moved row AFTER the transition
committed, as `wakes/moved-under?` does now. A row that moved into the
filter matches. A row that moved out of it does not. A row that is gone
(a delete) matches no filter. The kind's DEFAULT filters do not apply,
for the reason `moved-under?` gives: a default is a collection's
opening view, not a statement about one row.

**E-1.5** The predicate must judge a filter with `grants/row-matches?`,
so a grant, a cue and a wake read `a,b` and `state` in the same way.
The write door of each listener kind must refuse a filter field that
the kind does not declare filterable, with the query's own sentence.
This is the check `moved-under?` makes at read time today. It moves to
write time, so a listener cannot be saved in a state it cannot judge.

**E-1.6** The predicate must read the moved row at most one time for
one transition, and only when a stated field needs it (`filter`,
`domains` on a kind that keeps its domain in the row). The caller can
pass a row it already holds.

**E-1.7** `listen/matches?` is a pure function of the entry, the
transition and that one row. It writes nothing. A count condition
(section 4.2), a damper (section 4.2) and an SSE class (section 5.3)
are NOT predicate fields. They belong to the receiver or to the
reader.

**E-1.8** Each old shape must have one reader into an entry, in
`listen`, and no other code may read the old shape:

| Old shape | Entry |
|---|---|
| `wake_on` `{kind, actions, filter}` | `{kinds [kind], actions, filter}` |
| notifier `on` item `{kind, actions}` | `{kinds [kind], actions}` |
| notice rule `{kind, when {action, from_state, to_state}}` | `{kinds [kind], actions [action], from [from_state], to [to_state]}`, a blank one left out |
| subscription `{kinds, skip_actors, domains, service_alerts}` | the same names |
| inbox `only {kind [actions]}` | one entry for each key |
| cue `{kind, actions, filter}` | `{kinds [kind], actions, filter}` |

## 3. The consumer

**E-2.1** Every durable listener must hear the log through
`server/consumers`. The webhook deliverer must move onto it. The
sentence in the `consumers` namespace note that defers this must go.

**E-2.2** `consumers` must get a FAMILY: one drain thread that reads
the log one time, and one cursor for each member of the family. A
member is one listener row. Its cursor is a row in `waymark10_cursors`
named `<family>:<row id>`. This is the shape the webhook deliverer has
today (N cursors behind one thread), made general.

```clojure
(consumers/register-family! eng :subscriptions
  {:members  (fn [] ...)          ; the active listener rows
   :entry    (fn [row] ...)       ; the row as a listen entry
   :deliver  (fn [row t] ...)     ; the receiver; may throw
   :policy   (fn [row] ...)})     ; :fail or :skip
```

**E-2.3** The contract of a family member is the contract of a
consumer today, for each member alone:

- At-least-once: the member's cursor moves after `deliver` returns.
- `:fail` parks the member at the refusing event. The other members
  go on. `:skip` records the failure on the member and moves on.
- A new member hears the log from its own create transition, as
  `webhooks/seed-cursor!` reads it now: the oldest transition of the
  listener row, not the newest of the log.
- A member that is paused keeps its cursor. On resume it hears what it
  missed.

**E-2.4** A transition that matches no member must cost one predicate
call for each member and nothing else. A member whose cursor is
behind the family's read position (it was parked, or it is new) must
read the log from its own cursor on its own. It must not hold the
others back.

**E-2.5** The single-cursor consumers that are not listeners (the
schedules mirror, the judgments, the effect runner, the integrations,
the invitations, the quests, the walkthroughs) stay as they are. A
walkthrough step and a quest step are not listener rows. They are
written by the engine for one row and one door, and they end on that
transition. They can call `listen/matches?` with a one-field entry,
but this spec does not require it.

## 4. Receivers

**E-3.1** The `subscription` kind must get a field `receiver`. It is a
map with a `type`, one of `url`, `door`, `pull`, `tell`, and the fields
that type names. The predicate fields of E-1.2 are the subscription's
own fields, beside `receiver`.

### 4.1 `url`

**E-3.2** `{type url, url, secret}` is today's webhook: the signed
POST, the delivery log, the fail or skip policy. A subscription with
no `receiver` reads as this type, so every webhook row keeps working
with no write.

### 4.2 `door`

**E-3.3** `{type door, kind, id, action, settle_seconds,
max_wait_seconds, at_least, at_most, count_filter}` invokes the door
`action` on the row `kind`/`id` for each match. The input is the
transition as `wakes/wake-text` writes it today: kind, row id, action,
from state, to state.

The one door this spec requires is `seat.fire`. A build ticket can
open others later. A door receiver on any other door must be refused
at write until then.

**E-3.4** The ACTOR of the invoke is the engine, recorded as
`subscription:<id>`, so the seat's history names which subscription
woke it.

**E-3.5** `settle_seconds` and `max_wait_seconds` are receiver
properties. They keep their R-12.22 meaning: a match sets the due time,
the next match moves it, and it never passes the first match plus
`max_wait_seconds`.

**E-3.6** `at_least` and `at_most` make a COUNT receiver (R-12.24).
The predicate decides WHEN the count is read. The count is the
collection's (`wakes/count-under`), under `count_filter`, with the
kind's default filters. The door is invoked only when the count meets
the size. A receiver can name one of the two, not both.

**E-3.7** The DAMPER AND THE SLOTS STAY ON THE SEAT. This is a
departure from the ticket's first statement, and this is the reason.
`max_open_sittings`, `fire_interval_seconds` and `wake_pending` count
all the fires of one seat. A seat can have several subscriptions that
fire it. A damper on each receiver would let two subscriptions fire
one seat inside one gap. So the `seat.fire` door reads the damper from
the seat, as `wakes/wake-seat!` does now, and a fire the damper stops
sets `wake_pending` on the seat's schedule row, as now. The receiver
carries only what is its own: the settle and the count.

**E-3.8** A door receiver delivers into the engine, so it never has a
network failure. A refusal of the door (the seat is halted, parked or
gone) is a `:skip`: it is recorded on the subscription, the cursor
moves on, and the next match tries again. A door receiver must never
park: a parked wake would stop every later wake of that seat.

### 4.3 `pull`

**E-3.9** `{type pull, cues}` is the inbox. It has no engine cursor:
the reader holds the position, as a seat's inbox tail does now (the
tail starts at its sitting's create). The engine stores only the
entry, and the inbox door filters the log with `listen/matches?`.

**E-3.10** The rules the inbox adds today stay as inbox rules, not
predicate fields, because they read the sitting and not the row: a
transcript kind is left out, the sitting's own row is left out, the
seat's own moves are left out (`skip_actors [seat:<id>]`, written into
the entry), and the kind must be readable by the seat's whole scope.

**E-3.11** `cues` is a list of `{entry, note}`. A cue is a predicate
and a sentence. The inbox door adds the notes of every cue whose entry
matches, in list order, as `cue-match` does now.

### 4.4 `tell`

**E-3.12** The NOTIFIER AND THE NOTICE RULE STAY KINDS. They do not
become subscriptions. They use the predicate (E-1.8), and their drain
joins the family of E-2.2 as two more member sources. The reasons:

- A notice rule with `at` hears a TIME, not a transition. It is a
  schedule over a row's field, and it is not a listener at all.
- A notice rule addresses a member by a path of hops through the moved
  row (`addressee-of`), with quiet hours and a digest. That is a
  receiver with its own state, and it is already a kind with doors a
  person uses.
- A notifier carries a tool and a server of the Gate. Its row is the
  address book entry for that channel.

So `tell` names the reading that these two kinds share, and the
`receiver.type` value `tell` is not written on a subscription row. A
subscription row with `type tell` must be refused.

## 5. What reads the listeners

### 5.1 The cracks audit

**E-4.1** The audit's wake coverage ("no seat is woken to this
state") must read the subscriptions whose receiver is `door` on
`seat.fire`, keyed by the seat id in the receiver. It must not read
`wake_on` once section 6 has run.

**E-4.2** `cracks/tellers` must judge each source with
`listen/matches?` over a synthetic transition into the state, not with
its own copy of the readings.

**E-4.3** `tellers` counts a subscription as "tells a person" only when
its receiver type is `url`. A `door` receiver fires a seat; it tells
no person. A `pull` receiver counts only as today's `inbox_cue`: a cue
in the inbox of an interactive seat (`seats/interactive-seat?`).

### 5.2 Seat audit

**E-4.4** The seat's envelope must show the subscriptions that fire it,
with each one's cursor position and its last skip, so the question
"who woke this seat, and why not" has an answer on the seat.

### 5.3 SSE

**E-4.5** The SSE feed stays a live, per-connection, in-memory queue.
It does not become a durable member: a connection is not a row, and a
reconnect resumes by its last event id. Its `wants?` must call
`listen/matches?` for the kind and the one resource
(`{kinds [k], ids [id]}`). The CLASS of an event (transition or
derivation) stays an SSE check before the predicate, because a
derivation is not a transition and the predicate does not judge it.
The rule that a kind-wide observation (no resource id) reaches every
watcher of the kind stays an SSE rule.

## 6. Migration

Each step is one build ticket in section 8. Each step leaves the
engine whole: a step that is merged and not followed by the next one
changes nothing a person sees.

**E-5.1** The predicate lands first, with the old shapes read into it
(E-1.8), and every matcher of section 1 calls it. The wake matcher
keeps its old empty-list reading for one release through an explicit
flag in its reader, `:empty-actions :none`, so this step changes no
behaviour.

**E-5.2** Before the flag goes, a migration must find every seat whose
`wake_on` has a TRANSITION entry (no `at_least`, no `at_most`) with an
empty `actions`. Such an entry matches nothing today. The migration
must remove that entry and record the removal on the seat's history
with the sentence "the entry matched no transition and was removed".
The flag then goes. After this step the seat's write door accepts an
entry with an empty `actions`, and it means every action.

**E-5.3** The family (E-2.2) lands, and the webhook deliverer moves
onto it. Every `webhook:<id>` cursor row keeps its position: the
family's name for the subscriptions is `webhook`, so no row is
renamed.

**E-5.4** `receiver` lands on `subscription`, with `url` and `door`.
For each seat, the engine must make one subscription for each
`wake_on` entry, with `receiver {type door, kind seat, id <seat>,
action fire}` and the entry's settle and count. A walk seat with no
`wake_on` gets one subscription for its computed default (R-12.22), and
the subscription is marked `managed_by: seat`. When the seat's walk
changes, the seat's write door restates its managed subscription. Each
new member's cursor is seeded from the `:wakes` consumer's cursor, not
from its own create, so no wake between the two is lost or doubled.
Then the `:wakes` consumer stops, and the `wake_on` field becomes read
only. A later ticket removes it from the seat's schema.

**E-5.5** The seat's `inbox.only` and `inbox.cues` become one
subscription with `receiver {type pull, cues}`, owned by the seat. The
inbox door reads it. The computed default inbox of an interactive seat
(`seats/inbox-of`) becomes a managed subscription, as in E-5.4.

**E-5.6** The notifier and notice rule consumer joins the family, one
member for each active notifier row and each active notice rule row
without `at`. Its single cursor seeds every new member.

**E-5.7** The cracks audit moves to E-4.1 to E-4.3, and SSE to E-4.5.

**E-5.8** spec-seat R-12.22 must be changed in the same ticket as
E-5.4: "Each entry is a subscription over the transition log" becomes
the fact, and the sentence points here.

## 7. What this spec does not change

- The wake's text and R-12.21 (a fire with a row walks that row).
- The damper's law (R-12.22's three parts), which stays on the seat.
- The webhook wire: the signature, the headers, the body.
- The scenario matcher in `scenario.clj` (`matches?` over an
  integration event's body). It judges an inbound event, not a
  transition.

## 8. Build tickets, in order

Each is under 800 changed lines. Each names its tests; none changes a
test to make it pass.

1. **The predicate.** New `server/listen.clj` with `matches?` and the
   readers of E-1.8. Every matcher of section 1 calls it: wakes (with
   `:empty-actions :none`), notifier, notice rule, webhook, inbox, cue.
   The write-time filter check of E-1.5 on `wake_on`, `subscription`
   and cues. One test namespace for `listen`, a table of every old
   shape against the same transitions. (E-1, E-5.1)
2. **One empty list.** The migration of E-5.2, then the flag goes.
   Tests: a seat with an empty transition entry loses it with the
   history sentence; an entry `{kind task, actions []}` written after
   wakes on any action of a task. (E-5.2)
3. **The family.** `consumers/register-family!` with per-member
   cursors, policy and seed (E-2.2 to E-2.4). The webhook deliverer
   moves onto it with its cursor names kept. The namespace note's
   deferral goes. Tests: the existing webhook delivery tests pass
   unchanged; a parked member does not hold another. (E-2, E-5.3)
4. **Door receivers and seat subscriptions.** `receiver` on
   `subscription` (`url`, `door` on `seat.fire`); the wake path reads
   subscriptions; the migration and cursor hand-over of E-5.4;
   `wake_on` read only; spec-seat R-12.22 rewritten. Tests: the wake
   tests run against subscriptions; the seat history names
   `subscription:<id>`. (E-3.1 to E-3.8, E-5.4, E-5.8)
5. **Cracks and the seat envelope.** E-4.1 to E-4.4. Tests: a `door`
   subscription is not a teller; wake coverage counts a seat's
   subscription. (E-4.1 to E-4.4, part of E-5.7)
6. **Pull receivers.** The inbox and cues as a `pull` subscription
   (E-3.9 to E-3.11, E-5.5). Tests: the inbox and cue tests pass
   unchanged against the migrated rows.
7. **Notifiers and notice rules join the family.** E-3.12, E-5.6.
   Tests: the notifier and notice rule tests pass unchanged; a failing
   notifier no longer holds back another.
8. **SSE.** E-4.5. Tests: the events tests pass unchanged.
9. **Remove `wake_on`.** The field leaves the seat's schema; the routine
   documents (`docs/routines/*.md`) that name it point to the seat's
   subscriptions.

Ticket 5 needs 4. Tickets 6, 7 and 8 need only 1 and 3, and can run in
any order beside 4 and 5. Ticket 9 is last.
