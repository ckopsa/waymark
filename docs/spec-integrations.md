# Spec: integrations as declarations

Concept 6 of epic `2bb0aed8` (ticket `98a98359`). A spec only: no engine
code lands with it. Its last section is the build plan the mayor splits
into children, the way `docs/spec-calendar-time.md` (concept 2) ends.

> Later the Hendersons pay online: the payment arrives, the job moves to
> paid by itself, and nobody retyped a thing.

Concepts 1 and 2 gave the house its refusals and its time. This spec gives
it the outside world: an event from another system that moves a door, a
door that calls another system, and the undo that a later door owes. Three
rehearsals logged 16 workarounds of this concept (org_plan `16d15da4`
shop, `e749a045` clinic, `bba84cf0` landscape, concept "6"). Each shape is
in §9 with the construct that replaces it.

---

## 0 · Today

- **Powers reach outside.** An `mcp_server` row's `powers` list is the
  policy (`docs/spec-mcp-servers.md`). A caller reaches a tool through the
  power door (`gate-proxy/invoke-for`), with a `why`. An entry that says
  `approval person` mints a `held_call` (R-14), which a person allows or
  refuses, and the engine forwards it once.
- **A handler can already call out.** `(:power ctx)` is the engine's own
  hand on a power (`invoke.clj`, `make-ctx`). It is absent for a request
  with no live grant, guards never get it, and a birth carries none.
  `inbox_item.research` is its one reader. It is code in a handler, not a
  declaration, so no audit sees it and no scenario fakes it. The engine's
  own calls never hold.
- **`scheduled_action`** (`docs/spec-scheduled-actions.md`) stores one
  call for one `run_at`, a door or a power (R-4.4). A power run that ends
  unknown is `failed` with *"It may or may not have been sent"*, and it is
  never retried. This spec keeps that sentence.
- **Invoke is idempotent by key.** An `Idempotency-Key` replays the stored
  answer byte for byte, and `land!` stamps the key on the transition.
- **Webhooks go OUT, not in.** `server/webhooks.clj` delivers the log to a
  `subscription`'s url, at least once, signed `X-Waymark-Signature: hex
  hmac-sha256(secret, body)`, with the id in `X-Waymark-Event-Id`. The
  secret may be a `secret` row's id. The seat inbox
  (`/api/-/sittings/inbox`) tails the log for a sitting. **No route accepts
  a POST from an outside system and checks its signature.** The ticket's
  "inbound plumbing" is this outbound half and the inbox; the inbound
  receiver is the new piece, and it mirrors the outbound signature, the
  secret row and the at-least-once consumer (`server/consumers.clj`).
- **The rehearsals type the world in.** A processor creates a row, and the
  mayor applies it in two doors. A label, a refund or a claim is made
  outside the house, and somebody types `processor_ref` or
  `carrier_claim_ref` afterwards. The undo ("void the unused label") is a
  door the mayor must remember.

## 1 · The decision in one paragraph

**An integration is a principal, an event is a door, and an effect is a
row.** An `integration` row names an outside system, holds its secret by
reference, and is a principal with its own grant, which a person approves
one time. A kind declares `:on-event`: the engine checks the source's
signature, keeps one receipt per event id, finds the row by a match, and
runs the named door as the integration. A refusal stays on the receipt. A
door declares `:effect`: its transition commits with an `effect_call` row
in the same transaction (an outbox), and a runner makes the call once per
key, writes the result onto the row, or moves the row to the declared
failure state. An effect names its undo (`:compensate`), and a later door
says which effects it unwinds (`:unwinds`). Scenarios replay recorded
events and fake effect results; clones send every effect to the wall. No
second path: the call goes through the power door, the run is an ordinary
invoke, and the delay is a `scheduled_action`.

---

## 2 · Who holds the integration — `integration` (decision 4)

### Decision

An integration is a principal, `integration:<id>`, with its own grant. Its
moves are in the log beside every seat's. A person approves its grant one
time. It does not act under the mayor's name, and the mayor does not act
under its name.

### Shape

An engine-served kind, enrolled by a new `:integrations` module.

| Field | Who | What it is |
|---|---|---|
| `name` | person | Unique. The path segment of its receiver: `/api/-/events/<name>`. |
| `system` | person | One line: "Stripe, the payment processor". The audit reads it. |
| `secret` | person | The id of a `secret` row. Never the value. |
| `signature` | person | `{header, scheme, prefix, timestamp_header, tolerance_s}`. Default `X-Signature`, `hmac-sha256`, no prefix, no timestamp. |
| `event_id` | person | The path to the event's own id in the body, e.g. `["id"]`. |
| `event_type` | person | The path to its type, e.g. `["type"]`. |
| `id_header` | person | Optional. The header the event's id is in; set, it wins over `event_id`. |
| `type_header` | person | Optional. The header the event's type is in; set, it wins over `event_type`. |
| `server` | person | The `mcp_server` row its effects call, or empty for an inbound-only one. |
| `grant` | engine | The grant it wears. |
| `scope_wanted` | engine | What the law asks of it (below). |

States: `proposed`, `live`, `paused`, `retired`. Doors: `create` (a
person; born `proposed`), `approve` (a person who is not the creator,
the held_call's `the-caller-does-not-decide` wall reused), `pause`,
`resume`, `rotate` (names a new secret row; the old one is accepted for
24 hours), `retire`.

### The grant comes from the law

The engine reads every `:on-event` that names the integration (its
`kind/door`) and every `:effect` whose power lives on its `server`. That
list is `scope_wanted`. `approve` mints the grant with exactly that scope,
through the ordinary approval path. A deploy that widens `scope_wanted`
moves the row to `proposed` again: events are received and kept, but not
applied, until a person approves again. A narrowing applies at once.

---

## 3 · Inbound — `:on-event` (decision 1)

### Decision

A kind declares which outside event moves which of its doors. The
mapping is law; the event's body is data.

```clojure
(declare/kind :job
  ...
  :on-event
  [{:source :payments                     ; an integration's name
    :type   "payment_intent.succeeded"
    :match  {:payment_ref (event "data" "object" "id")}
    :door   :mark_paid
    :input  {:amount_cents (event "data" "object" "amount_received")
             :paid_at      (instant (event "created"))}}])
```

`(event "a" "b")` is a new expression reader: the value at that path in
the event's body, or nil. `:match` names one or more fields of the kind,
each `:filter` on it, and the value each must equal. `:input` is judged by
the door's own input schema, as any input is.

### The pipeline

1. **Verify.** `POST /api/-/events/<name>`. The engine reads the secret
   row's value and checks the signature in constant time; with a timestamp
   header, it refuses one older than `tolerance_s` (default 300). A bad
   signature answers 401 and stores nothing. A `paused` or `retired`
   integration answers 503 and 410, so the source retries or stops.
   A `signature.prefix` (as `sha256=`) must open the header's value and
   is stripped before the compare; a value without it answers 401. A
   source that sends no signing timestamp leaves `timestamp_header`
   empty, and replay is held by the unique event id alone.
   With `id_header` set, the event's id is read from that header, not the
   body, and a delivery without it answers 422 and stores nothing;
   `type_header` does the same for its type. So a source that signs as
   `sha256=<hex>` in `X-Hub-Signature-256` and names its delivery and
   event in `X-GitHub-Delivery` and `X-GitHub-Event` is heard by setting
   these fields; the engine names no source.
2. **Keep one receipt.** The engine inserts an `integration_event` row,
   unique on `(integration, event_id)`, with the body (cut at 64 KB, the
   bytes counted), its type and `received`. A duplicate answers 200 with
   the first receipt's id and changes nothing. The answer is 202 the
   moment the receipt commits; the source never waits on the door.
3. **Apply.** An elected consumer, with the at-least-once discipline of
   `server/consumers.clj`, takes each `received` receipt. It finds the
   `:on-event` entries for that source and type, finds the row by
   `:match`, and invokes the door as `integration:<id>` with
   `Idempotency-Key: event:<integration>:<event_id>`. So a crash between
   the door and the receipt's update replays the stored answer and never
   moves the row twice.
4. **Record the ending.** The receipt moves to one of:
   - `applied`: it carries the transition's id.
   - `refused`: it carries the refusal document whole (reason, guards,
     remedies). It is never dropped.
   - `unmatched`: no row has that match. `ambiguous`: more than one does;
     the engine does not guess, as `waymark_resolve` does not.
   - `ignored`: no entry names the type. It is kept 30 days, then purged.

`integration_event` has two doors for a person or a seat: `retry` (apply
again, under the law of now) and `dismiss {reason}`. An entry may declare
`:retry-unmatched-for 600` for a source that can send its event before
the row exists; the consumer tries again until then, then lands
`unmatched`. A refused receipt wakes the seats the kind's `:on-event`
entry names in `:tell`, or the integration's approver. `[refused-event-untold]`
(§8) catches one that reaches nobody.

### Option kept: the inbox

`:on-event {... :via :inbox :makes :payment_notice}` does not run a door.
The receipt's body becomes a row of the named kind, created as the
integration, and a seat applies it, which is today's shape. It is for a
source that cannot sign, or whose events a person must read first. The
signature step is then skipped only when the integration says
`signature: {scheme: none}`, which the audit names.

---

## 4 · Outbound — `:effect` (decision 2)

### Decision

A door declares the call it makes. The door's transition and an
`effect_call` row commit in one transaction; the call is made after the
commit, never inside it, because a wire cannot roll back.

```clojure
:buy_label
{:from [:packed] :to :label_asked
 :effect {:power           "postage__create_label"
          :input           {:to_address (data :ship_to)
                            :weight_g   (data :weight_g)}
          :why             "Buy the postage label for order {number}."
          :idempotency-key (str "label:" (id))
          :writes          {:label_ref (result "object_id")
                            :tracking  (result "tracking_number")}
          :lands           :labelled
          :fails-to        :label_failed
          :compensate      :void_label}}
```

- `:to` is the state that means **asked**. `:lands` is the state the
  engine moves the row to when the call answers; it is optional, and with
  none the row stays in `:to` and only the fields move. `(result "…")`
  reads the answer.
- `:idempotency-key` is required (`[effect-no-key]`). `effect_call` is
  unique on `(power, key)`. A door whose key already has a `done` call
  makes no call: it writes that call's result again. This is "one label
  per order".
- `:fails-to` is required (`[effect-no-failure-state]`).

### The runner

An elected runner takes each `pending` `effect_call` and calls the power
through `gate-proxy/invoke-for` **as the integration** (§2), with the
door's actor on the row as `on_behalf_of`. So the power entry's filter,
`allow` globs and `approval` apply as they do to any caller:

| The call ends | `effect_call` | The row |
|---|---|---|
| answered | `done`, answer kept (16 KB, as a held call's) | the engine's hidden `effect_landed` door writes `:writes` and moves to `:lands` |
| a definite refusal from the server | `failed`, the server's sentence | the hidden `effect_failed` door moves it to `:fails-to` with that sentence |
| unknown (the wire dropped), and the power entry says `idempotent: true` | stays `pending`; retried with the same key, 3 tries, backoff | unchanged until it ends |
| unknown, and the entry does not | `unknown` | `:fails-to` with *"It may or may not have been sent"* (R-4.4's sentence) |
| held for a person | `held`, the `held_call` named | unchanged; a refusal there is a failure |

`idempotent: true` is a new, optional field of a powers entry. It says the
server itself dedupes on the key the runner passes (Stripe's
`Idempotency-Key`, a carrier's request id). Only then is a retry safe.
Otherwise this spec does not pretend it is.

The two hidden doors are the engine's; their transitions name the
integration and the `effect_call`, so `waymark_history` on the order says
who asked, what was called and what came back.

### Time

"Text a reminder 24 hours ahead" is an effect door that a
`scheduled_action` targets (or concept 2's `:recurs` arms). There is no
delay inside `:effect`.

---

## 5 · Compensation — `:compensate` and `:unwinds` (decision 3)

An effect names its undo: a door on the same kind (which is usually an
effect door itself) or a power with its input.

```clojure
:void_label {:from [:labelled :label_asked] :to :label_voided
             :effect {:power "postage__refund_label"
                      :input {:label (data :label_ref)}
                      :idempotency-key (str "void:" (id))
                      :fails-to :void_failed}}

:refund {:from [:paid :labelled] :to :refunded
         :effect {... the processor's refund ...}
         :unwinds [{:effect :buy_label :when (nil? (data :shipped_at))}]}
```

When `refund` lands, the engine looks at each named effect. For one whose
`effect_call` is `done` and whose `:when` holds, it adds the compensation's
`effect_call` in the same transaction, keyed `undo:<the original key>`. So
a refunded order voids its unused label, and nobody remembers to.
Compensations run newest effect first. A compensation that fails does not
roll the refund back (there is no rollback of the outside world); its own
`:fails-to` holds the row, and the `effect_call` waits for a person.

---

## 6 · Testing (decision 5)

**Law scenarios** (`docs/spec-law-scenarios.md`) gain two keys:

```clojure
{:events  [{:source :payments :body {...recorded...}}]   ; replayed in order
 :effects {"postage__create_label" {:answer {"object_id" "L1"}}
           "postage__refund_label" {:fails "Label already used."}}}
```

An event runs the pipeline from step 2 (no signature: the scenario is the
source). An effect is answered by the fake, and `:unknown` is a fake too.
A scenario that reaches an effect door with no fake for its power is
refused, not defaulted, as a clock-reading law with no `:at` is. The
scenario's answer lists each `effect_call` it made, so a test asserts
"one label, then one void".

**Clones** point every effect at the wall (`recipe.py`, in
`ckopsa/waymark-doors`). The wall records the call, with its key, and
answers the recipe's canned result. A clone's integrations get a clone
secret, and the rehearsal driver signs and posts recorded events to the
clone's receiver. Nothing in a clone reaches a real processor.

---

## 7 · Honest on the probe and in rehearsal

- An effect door's `dry_run` judges the door and the `effect_call` it
  would add, and says so: *"Would call postage__create_label once, key
  label:…"*. It calls nothing; an outside server has no dry run.
- A door whose key already has a `done` call says on the probe that it
  will reuse that answer.
- `waymark_get` on a row with a pending effect shows it under `meta`
  (`effects: [{power, state, since}]`), so a reader does not mistake
  `label_asked` for stuck.

## 8 · Fingerprint and audit (decision 6)

**Fingerprint.** `:on-event`, `:effect`, `:compensate` and `:unwinds` are
law facets: they move rows and call out, so a change is a revision.
`(event …)` and `(result …)` are nodes of the canonical tree. An
`integration` row's secret and signature settings are data.

**Audit findings,** in the declaration battery (`checks.clj`, `make
check-queue`). Each has its sentence, its remedy and one plain question:

| Finding | Level | Says | Remedy | Question |
|---|---|---|---|---|
| `[integration-in-prose]` | info | A field, door or kind note names an outside system (a `*_ref` field, "processor", "carrier", "insurer") that no `:on-event` or `:effect` writes. | Declare the integration, then the event or effect that writes the field. | "When your payment processor says a payment cleared, what should happen?" |
| `[typed-after-the-fact]` | warning | A door whose input is only an outside reference (`processor_ref`, `carrier_claim_ref`) and which a known event type could move. | Give the kind an `:on-event` for it, or make the door an effect that writes the reference. | "Who types {field} today, and where do they copy it from?" |
| `[effect-no-key]` | error | An `:effect` without `:idempotency-key`. | Key it on what makes it once: the row's id, plus a count if it repeats. | "If this ran twice, how many {power}s should there be?" |
| `[effect-no-failure-state]` | error | An `:effect` without `:fails-to`. | Name the state a row waits in when the call fails. | "When {power} fails, who should pick it up?" |
| `[event-match-unfiltered]` | error | A `:match` field that is not `:filter` on the kind. | Declare it filterable. | — |
| `[event-door-unreachable]` | error | `:door` has no state from which an event could move it, or `:input` misses a required field. | Fill the input from the event, or fix the door's `:from`. | — |
| `[unwinds-no-compensate]` | error | `:unwinds` names an effect that declares no `:compensate`. | Name its undo. | "What has to be reversed when this {door} happens?" |
| `[refused-event-untold]` | warning | An `:on-event` with no `:tell` on a kind whose integration has no approver seat woken to `integration_event`. | Name a seat to tell. | "Who should hear when a payment cannot be applied?" |
| `[source-unsigned]` | warning | An integration with `signature: {scheme: none}`. | Use a signing source, or keep it on `:via :inbox` so a seat reads each event. | — |

---

## 9 · Migration — the 16 workarounds

The ticket gives the total (16) and the shapes, not the count per app or
per shape, and this spec does not guess one. Step 14 counts them against
the logs.

| Shape | Replaced by |
|---|---|
| A payment that clears moves the order to paid (today: the processor creates a row, the mayor applies it in two doors) | §3 `:on-event` on the order, `:door :mark_paid` |
| Carrier tracking marks delivered | §3 `:on-event`, match on `tracking` |
| An insurer's remittance marks a claim paid | §3 `:on-event`, match on the claim's number |
| Buy a postage label, once per order | §4 `:effect`, key `label:<id>` |
| Send a refund through the processor | §4 `:effect` on `refund` |
| File a carrier claim; submit an insurance claim | §4 `:effect`, `:writes` the claim reference |
| Verify insurance coverage | §4 `:effect` with `:lands :verified` and `:fails-to :unverified` |
| Text a reminder 24 hours ahead | §4 `:effect` door, targeted by a `scheduled_action` |
| `processor_ref`, `carrier_claim_ref` typed after the fact | §4 `:writes` |
| A refunded order voids its unused label | §5 `:unwinds` with `:when` |

---

## 10 · The film (decision 7)

The landscaping showcase's second half:

1. The owner marks the Hendersons' patio job done and sends the invoice.
   `send_invoice` is an effect door: it asks the processor for a payment
   link (key `invoice:<job id>`), and the link lands on the job.
2. That evening the Hendersons pay online. The processor posts
   `payment_intent.succeeded`. The receipt is kept, the job is found by
   `payment_ref`, and `mark_paid` runs as *Payments (Stripe)*.
3. The next morning the owner opens the job. It reads **Paid**. Its
   history says *"mark_paid by Payments (Stripe), from event evt_…, $1,840
   received"*. Nobody retyped a thing. The processor sent the same event
   twice; the second was answered with the first receipt and moved nothing.

The film is step 12's acceptance walk, in a clone, with the event from the
rehearsal driver and the payment link from the wall.

---

## 11 · Not in this spec

- **Polling** a source that has no webhooks. A `:recurs` rule that calls
  an effect and writes what it reads covers the cases seen so far.
- **OAuth handshakes** for a new integration. The `mcp_server` row and its
  `auth_env` stay where credentials live.
- **Ordering** across events. Each event is judged under the law of the
  moment it is applied; one that comes too early is `refused` or
  `unmatched`, and `retry` is the remedy.
- **Money reconciliation**: matching a payout to its payments is an app's
  kind, which can use `:on-event` like any other.

## 12 · Build plan

Each step is one ticket and one pull request, in order, each under the
800-line ceiling. Steps 1 to 4 are inbound, 5 to 8 are outbound, and no
app moves until step 12. The repository is named on each step.

1. **Engine — `integration` kind and principal.** The `:integrations`
   module, the kind, its doors, the `integration:<id>` principal, the
   approver wall. Test: approve mints a grant; the creator cannot approve.
2. **Engine — the receiver.** `POST /api/-/events/<name>`, the signature
   check through the `secret` row, the timestamp tolerance, the
   `integration_event` kind, the unique receipt, 401/503/410/202/200.
   Test: a bad signature stores nothing; a duplicate changes nothing.
3. **Engine — `:on-event` and the applier.** The declaration, `(event …)`,
   `scope_wanted` from the law, the consumer, the `event:` idempotency key,
   the endings, `retry`, `dismiss`, `:retry-unmatched-for`, `:tell`.
   Test: a refused door is a `refused` receipt with the refusal whole.
4. **Engine — `:via :inbox`.** The option kept, and `[source-unsigned]`.
5. **Engine — `effect_call` and `:effect`.** The kind, the outbox insert
   in the door's transaction, `(result …)`, `:writes`, `:lands`,
   `:fails-to`, the two hidden doors, reuse of a `done` key, the probe's
   sentence. Test: two invokes, one `effect_call`.
6. **Engine — the runner.** Through `gate-proxy/invoke-for` as the
   integration, `on_behalf_of`, held effects, the unknown ending, and the
   `idempotent` field on a powers entry (`mcp_servers.clj`). Test: each row
   of §4's table.
7. **Engine — `:compensate` and `:unwinds`.** The `undo:` key, `:when`,
   newest first, a failed compensation held; the undo a door or a power
   with its input (`<effect>_undo`, failing through `<effect>_undo_failed`).
   Test: refund voids an unshipped label and not a shipped one, and a
   power undo adds that call.
8. **Engine — scenario `:events` and `:effects`.** `offline-ctx` serves
   both; an effect law without a fake is refused.
9. **ckopsa/waymark-doors (clone) — the wall records effects.**
   `recipe.py` answers effect calls from the recipe and keeps each call
   with its key; the rehearsal driver signs and posts recorded events to a
   clone's receiver.
10. **ckopsa/waymark-doors (gate) — keys reach the provider.** The payment
    and postage tools pass the runner's key through as the provider's own
    idempotency key, so their entries may say `idempotent: true`.
11. **Engine — the audit.** The nine findings of §8 in `checks.clj`, with
    their questions.
12. **Engine — landscape.** The rehearsal's job kind takes `send_invoice`
    and `:on-event`; §10's three steps are its acceptance walk.
13. **Engine — shop and clinic.** Labels, refunds and the void, carrier
    tracking and claims; coverage, claims, remittance and reminders. Each
    with a scenario.
14. **Survey.** Re-read the three rehearsal logs, check §9 against the 16,
    and file one ticket for each workaround these steps did not retire.
