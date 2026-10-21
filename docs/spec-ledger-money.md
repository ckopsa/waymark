# Spec: ledger money as declared law

Concept 3 of epic `2bb0aed8` (ticket `4c82fb58`). A spec only: no engine
code lands with it. Its last section is the build plan the mayor splits
into children, the way `docs/spec-related-row-law.md` (concept 1) and
`docs/spec-calendar-time.md` (concept 2) end.

> Later the Hendersons pay online: the payment arrives, the job moves to
> paid by itself, and nobody retyped a thing. At the shop, the last two
> mugs cannot be sold three times, and on the first of the month the
> books close from what happened, not from four totals typed by hand.

Concept 1 gave the law reads of other rows. Concept 2 gave it time. This
spec gives it quantities that move: money with a currency, stock that a
door adds and takes, and balances that are the sum of what the doors did.
Three rehearsals logged 16 workarounds of this concept (org_plan
`16d15da4` shop, `e749a045` clinic, `bba84cf0` landscape, concept "3").
Their shapes are in §11 with the construct that replaces each.

---

## 0 · Today

- **Exact numbers exist.** `:decimal` is registered in
  `waymark10/schema.clj` (an exact `BigDecimal`; batch H's "never
  floats"), projects to `numeric`, honours `{:min :max}` exactly, and
  fingerprints as `{"dec" …}`. A JSON float decodes to a `BigDecimal`, so
  no float reaches the law. There is no currency anywhere: the
  rehearsals' money is `:int` cents or `:decimal`, with USD implied.
- **Balances are typed.** A rehearsal's `on_hand` is an `:int` field. A
  door moves a state and writes the fields its input names; nothing adds
  to a number on a row the door is not moving. "A kiln batch adds stock to
  each product" is a person retyping each product's count.
- **Aggregates are stored, after commit.** `defderived` takes `:count` or
  `:sum` over a declared edge (`declare/derived`), and the maintainer
  recomputes it after a commit. Its `:where` is a constant set. It cannot
  judge a proposed write, and it cannot sum over a period that is input.
- **Concept 1 queries at guard time.** `exists`, `none` and `count` ask
  `(:find ctx)` for rows matching a `where` that may read input, and name
  the first match as evidence. There is no `sum` among them.
- **Concept 2 gives dates.** `(today)` in the governing `work_calendar`'s
  zone, periods (`months`), and `days-between`. A month is a pair of dates
  the law can compute.
- **Line items are prose.** An order's "2 × mug at $18" is a text field.
  Nothing derives a total, and nothing reserves the mugs.
- **Tax is code or a typed number.** No row holds a rate the law reads.

## 1 · The decision in one paragraph

**A door posts; a balance is the sum of its postings; the law reads the
balance.** A kind declares named **accounts** (`product` has `on_hand`
and `reserved`; `order` has `paid` and `refunded`), each with a unit and
optional bounds. A door declares **`:posts`**: entries of account, amount
expression and sign, which the engine writes into an engine-owned
`posting` table in the same transaction as the transition and its log
row. A posting is never edited; a mistake is a reversing posting. The
balance of an account is the sum of its postings, readable in law as
`(balance …)`, projected onto the row as a read-only field, and bounded by
`:never-below` / `:never-above`, from which the engine writes the guard
tree. Money becomes a value, `:waymark/money`, a decimal with an ISO 4217
currency, rounded only where the law says `round`, half-even unless the
house declares otherwise. Line items are a declared child collection whose
totals derive. A period close is an author's kind that reads sums of
postings between two dates and names the gap. Tax rates are rows.

---

## 2 · Money — `:waymark/money`

### Decision

A **decimal amount plus an ISO currency**, one value. Not a pair of
fields the author keeps in step: the type carries both, so `+` can refuse
to add dollars to euros.

```clojure
[:price :waymark/money]                        ; any currency
[:price [:waymark/money {:currency "USD"}]]    ; this one only
[:copay [:waymark/money {:min "0"}]]           ; bounds compare amounts
```

- **Wire:** `{"amount": "18.00", "currency": "USD"}`. The amount is a
  **string**. A JSON number is refused for a money field, so neither a
  float nor a client's rounding reaches it. A house with one currency may
  send a bare string (`"18.00"`); the field's declared or the house's
  default currency fills it, and the stored value always has one.
- **Storage:** two columns, `<field>` `numeric` and `<field>_currency`
  `char(3)`. Filters and sorts read the amount; a sort across currencies
  is refused, not guessed.
- **Law value:** a record `{:amount BigDecimal :currency "USD"}`.
  `(money "18.00" "USD")` is its literal. `(amount m)` and `(currency m)`
  read it.

### Arithmetic

| left | op | right | result |
|---|---|---|---|
| money | `+` `-` | money, same currency | money |
| money | `+` `-` | money, other currency | **unknown**, and the refusal says which two currencies |
| money | `*` | decimal or int | money, unrounded |
| money | `/` | decimal or int | money, unrounded, to 12 places (never a non-terminating `ArithmeticException`) |
| money | `<` `<=` `=` `>=` `>` | money, same currency | boolean; other currency: unknown |
| money | `+` | decimal or int | unknown: a bare number is not money |

**Rounding is declared, never implicit.** Arithmetic keeps every digit.
`(round m)` rounds to the currency's minor unit (ISO 4217: USD 2, JPY 0,
KWD 3) in the house's rounding mode. The mode is a `:rounding` setting on
the house (`:half-even` by default; `:half-up` for a house whose
processor or tax authority demands it). A value written to a money field
or posted to a money account must already be at the currency's scale; an
unrounded write is refused (`[money-unrounded]`), with the rounded value
in the remedy. That is where a forgotten `round` surfaces: at the write,
not a month later in the close.

### Option kept: integer minor units

Storing `1800` with `"USD"` is exact too, and some processors speak it.
The spec keeps it as a **wire option**, not a law value: a field may
declare `{:wire :minor-units}` so its JSON is `{"minor": 1800,
"currency": "USD"}`, and the law still sees `18.00`. Storage stays
`numeric`. Two law values for one amount would fork every fingerprint.

---

## 3 · Accounts

### Decision

An account is a **declared name scoped to a row**, not a kind of its own.
Its address is `[kind row-id name]`, spelled `product:<id>:on_hand` in
sentences and on the wire.

```clojure
(defresource product
  …
  :accounts {:on_hand  {:unit :count :never-below 0}
             :reserved {:unit :count :never-below 0}})

(defresource order
  …
  :accounts {:paid     {:unit [:money "USD"]}
             :refunded {:unit [:money "USD"] :never-above :paid}})
```

- **Units.** `:count` (an integer quantity), `[:money cur]`, or
  `:decimal` (kilos of clay). A posting's amount must be of its account's
  unit; a mismatch is a def-time error where the amount's type is known,
  and a refusal where it is not.
- **Projection.** Each account appears on its row as a read-only field of
  the same name (`on_hand`), the balance as of the last commit. It is
  filterable and sortable like any stored fact, so "products under 5" is a
  collection filter and a derived fact can read `(data :on_hand)`. No door
  input may name it: the input schema leaves it out, and an edit that
  tries is refused with "on_hand moves only by postings".
- **House accounts.** Money that leaves the house's rows (processor fees,
  the processor's deposits) needs an account no product or order owns. A
  house declares them once: `:house-accounts {:processor_in {:unit [:money
  "USD"]} :fees {…}}`, addressed `[:house name]`.

### Option kept: a `ledger_account` kind

A house that keeps a chart of accounts (the clinic's insurer receivables
by payer, an accountant's numbered accounts) may later get an
engine-served `ledger_account` kind whose rows are accounts, addressed
`[:ledger_account id]`. Everything below works on that address unchanged;
only the declaration moves from the kind to a row. It is not in the first
build (§13).

---

## 4 · Postings — `:posts`

### Decision

A door declares the entries it writes. The engine writes them atomically
with the transition. A balance is never typed.

```clojure
(defaction fire_batch
  {:from #{"firing"} :to "fired"
   :posts [{:account [:product (data :product) :on_hand]
            :amount  (input :sound_pieces)
            :sign    :+}]})

(defaction place
  {:from #{"cart"} :to "placed"
   :posts [{:each    (lines)
            :account [:product (line :product) :on_hand]
            :amount  (line :qty) :sign :-}
           {:each    (lines)
            :account [:product (line :product) :reserved]
            :amount  (line :qty) :sign :+}]})
```

- **Entry.** `:account` is an address; its row part is any own-scope
  expression (`(self)`, `(data :product)`, `(input :product)`, a
  `follow`). `:amount` is an expression of the account's unit, never
  negative: a negative amount is refused, because `:sign` says the
  direction and a sign hidden in data is how a refund becomes a charge.
  `:sign` is `:+` or `:-`. `:when` (an expression) makes an entry
  conditional. `:each` (a collection expression, usually `(lines)`, §6)
  repeats the entry per element, bound as `(line …)`.
- **`:move` sugar.** `{:move (line :qty) :from [:product … :on_hand] :to
  [:product … :reserved]}` is the two entries above. It normalizes to
  them, so the two spellings are one law (§10). A `:move` keeps a
  conservation equation true by construction: what leaves one account
  arrives in another.
- **Atomic.** The postings, the row's new state and data, and the log row
  commit together or not at all. Each posting carries the log row's id,
  so a retried invoke (its idempotency key) never posts twice.
- **Immutable.** No door edits or deletes a posting. A correction is a
  door that posts the reverse; `undo` (`docs/spec-undo.md`) of a posting
  door writes the reversing entries with `reverses` set, and refuses when
  a reversal would break a bound.
- **Dated.** A posting stores `at` (the commit instant) and `effective_on`
  (a date: `(today)` in the governing calendar's zone unless the entry
  declares `:on`). Periods read `effective_on` (§7).

### The `posting` table

Engine-owned, like the log: `id, house, account_kind, account_row,
account_name, unit, currency, amount (signed numeric), at, effective_on,
source_kind, source_id, action, log_id, reverses`. Indexed on the account
address and on `(account, effective_on)`. It is readable as an
engine-served kind (`posting`, read-only, granted like any kind), so a
row's envelope can link to the postings that made its balance, and the
history view can say "fired batch #12 added 40 to on_hand".

### Balances

The engine keeps a `balance` row per account, updated in the same
transaction as the postings: the projected field (§3) reads it, and the
conservation guards (§5) **lock it** (`SELECT … FOR UPDATE`), so two
orders placed in the same second for the last mug are judged one after the
other. The sum of postings is the truth; the `balance` row is its cache,
and the audit's `[balance-drift]` compares them (§10).

```clojure
(balance [:product (input :product) :on_hand])            ; now
(balance [:house :processor_in] {:from d1 :until d2})     ; effective_on in [d1, d2)
```

`(balance …)` is evaluated through the `:find` hook (a locked read of the
`balance` row, or a SQL `sum` over a period), so it is **unknown** on a
probe with no hook, exactly as concept 1's `count` is.

### Option kept: a typed balance with a drift finding

A house may keep `on_hand` as an `:int` field the doors write, and
declare `:balance-of [:product … :on_hand]` on it; the audit then reports
when the field and the postings disagree. This is the migration path for
rows that already hold typed counts (§11), not a design anyone should
start with: `[balance-typed]` (§10) asks the person to move off it.

---

## 5 · Conservation as law

### Decision

A **bound on the account declaration**, from which the engine writes the
guard; and the `(balance …)` reader for anything the bounds do not say.

- `:never-below 0` on `on_hand` means every door whose `:posts` can
  subtract from it gains a guard: for each such account, `(>= (+ (balance
  acct) net-delta) 0)`, where `net-delta` is the door's signed sum for that
  account (several lines of one product add up first). The engine writes
  the guard as a tree, so an author's hand-written twin of it has the same
  fingerprint.
- `:never-above :paid` on `refunded` bounds an account by a sibling
  account on the same row: a refund posts `+` to `refunded`, and the guard
  is `(<= (+ (balance refunded) delta) (balance paid))`.
- **The refusal names the account and the gap**, in one sentence built
  from the account's label: "Only 2 Speckled mug on hand; this order takes
  3." / "$40.00 was paid; $30.00 is already refunded, so at most $10.00
  more." The evidence is the balance row read under its lock.
- **Doors that only add** gain no guard: a kiln batch never checks
  `:never-below`.
- A guard may read `(balance …)` directly for anything else, e.g. the
  clinic's "a claim's write-off never exceeds what the insurer did not
  pay".

### Worked: the shop's equation

"Stock in = sold + reserved + on hand" is not a guard; it is what
`:move` makes true. `fire_batch` posts `+ on_hand` from the house account
`made`; `place` moves `on_hand → reserved`; `ship` moves `reserved →
sold`; `restock_return` moves `sold → on_hand`. Every posting after the
first is a move, so `made = sold + reserved + on_hand` holds at every
commit, and the audit's `[unbalanced-door]` (§10) says which door, if
any, posts to one side only on a kind declared `:conserves`.

---

## 6 · Line items — `:lines`

### Decision

A **declared child collection**: a kind of its own, created and edited
through its parent's doors, whose totals derive.

```clojure
(defresource order
  …
  :lines {:kind  :order_line
          :parent :order                      ; the line's ref field to its order
          :fields [[:product [:waymark/ref {:kind :product}]]
                   [:qty [:int {:min 1}]]
                   [:unit_price :waymark/money]]
          :defaults {:unit_price (follow (line :product) :price)}
          :totals {:subtotal (sum (* (line :qty) (line :unit_price)))}})
```

- **One kind, engine-written.** `:lines` declares `order_line` with its
  fields, a ref to the parent, and no doors of its own. The parent's create
  and edit doors take a `lines` input (an array of line objects); the
  engine writes the line rows in the door's transaction. A line is
  addressable and readable, and its product ref is filterable, so "orders
  holding mug 12" is a query.
- **`(lines)` in law** is the door's proposed lines (the input when the
  door takes `lines`, the stored lines otherwise). `(line :f)` reads a
  field inside `:each` and inside `sum`/`count` over `(lines)`.
- **Defaults read related rows.** `:unit_price` defaults to the product's
  price through concept 1's `follow`, judged at the door, so the price on
  the order is the price when it was placed.
- **Totals derive.** `:totals` are stored facts on the parent, computed
  in the door's transaction from the lines (not after commit, as today's
  `:sum` aggregate is), so a guard and a posting may read `(data
  :subtotal)` in the same door. They are read-only fields like balances.
- **Tax** is a total too: `:tax (round (* (data :subtotal) (rate
  :sales_tax {:state (data :ship_state)})))` (§8).

### Option kept: lines as a JSON field

A `[:vector line-schema]` field on the order is cheaper and needs no
kind. It is rejected as the default because a line could then not be
queried, referenced by a return, or posted per product without the law
reaching inside a blob. It remains open for a house whose lines are only
printed.

---

## 7 · Period reconciliation — a close kind

### Decision

An **author's kind** whose doors read sums of postings over the period it
names. Nobody types a total; the close's fields are facts.

```clojure
(defresource month_close
  :fields [[:period_start :waymark/date] [:period_end :waymark/date]]
  :reconciles
  {:period [(data :period_start) (data :period_end)]
   :sides  {:processor_in (balance [:house :processor_in])
            :expected     (- (balance [:all :order :paid])
                             (balance [:all :order :refunded])
                             (balance [:house :fees]))}
   :tolerance (money "0.00" "USD")}
  :actions [balance close])
```

- **`:reconciles`** names two sides, each an expression over `(balance
  …)` evaluated with the period applied: inside `:reconciles` every
  `balance` reads postings with `effective_on` in `[period_start,
  period_end)`. `[:all kind name]` sums an account across every row of the
  kind.
- The engine projects each side and their `gap` as read-only facts on the
  close row and writes the guard of the `close` door: `(<= (abs gap)
  tolerance)`. Its refusal names the gap and the largest contributors on
  each side (the five postings with the largest amounts, as evidence): "The
  processor deposited $4,210.00 and the orders say $4,250.00: $40.00 is
  missing. Largest refunds: order #118 $40.00 …"
- **A closed period stays closed.** After `close`, a posting whose
  `effective_on` falls inside a closed period of that account is refused
  (`[period-closed]` as a refusal guard the engine adds to every posting
  door), and its remedy says to post it to the open period. A reopen door
  is the author's to declare, and is logged like any door.
- **Dates are concept 2's.** The period is two dates; `effective_on` is
  the house's day. A close made on the first of the month for the month
  before is `(months -1)` from `(today)`.

---

## 8 · Rate tables — rows the law reads

### Decision

A rate is a **row of an author's kind** declared as a table, read by key
and date.

```clojure
(defresource tax_rate
  :fields [[:state :string] [:rate :decimal] [:effective_on :waymark/date]]
  :rate-table {:name :sales_tax :key [:state] :value :rate :effective :effective_on})

(rate :sales_tax {:state (data :ship_state)})              ; on (today)
(rate :sales_tax {:state (data :ship_state)} (data :placed_on))
```

- `rate` answers the `value` of the row whose key matches and whose
  `effective` is the latest on or before the date. It goes through `:find`
  like `exists`; the key and effective fields must be `:filter`
  (`[rate-unfiltered]`).
- **No row is unknown, not zero.** A tax of zero for a state nobody
  entered is the bug this replaces. The refusal says "No sales_tax rate
  for NV on 2026-10-21" and its remedy is to add the row.
- The clinic's fee schedule (`:key [:payer :code]`) and copay tables use
  the same declaration.

---

## 9 · Honest on the probe, in rehearsal, in scenarios

- **Probe.** `(balance …)`, `(rate …)` and the derived conservation
  guards read through `:find`. Without it they are unknown, and a door
  that depends on them is shown as "depends on stock" rather than
  available, the way concept 1's `none` is.
- **Law scenarios** (`docs/spec-law-scenarios.md`) gain `:postings`, a
  list of `[account amount effective_on]` seeded before the walk, and
  `:rates`. A balance-reading law with neither is refused, not
  defaulted to zero. A scenario's expectations may name balances:
  `:expect {:balances {[:product "p1" :on_hand] 0}}`.
- **Rehearsal and demo clones** copy postings with their rows, so a
  clone's balances are the source's.
- **Law sweep.** A new `:never-below` is swept against the corpus like
  any guard: a product already below zero is reported before the bound
  ships, with the postings that put it there.

---

## 10 · Fingerprint and audit

**Fingerprint.** `:accounts` (names, units, bounds), `:posts`,
`:lines`, `:reconciles` and `:rate-table` are law facets: they write rows
and refuse writes. `:move` normalizes to its two entries. The guards the
bounds derive hash as their trees. A money literal hashes as `{"dec" …,
"cur" …}`. `:rounding` on the house is law. Postings, balances and rate
rows are data.

**Audit findings.** They join the declaration battery in `checks.clj`
(`make check-queue`), except where marked live. Each has its sentence, its
remedy and one plain question for the person who owns the kind:

| Finding | Level | Says | Remedy | Question |
|---|---|---|---|---|
| `[balance-typed]` | warning | A numeric field named like a balance (`on_hand`, `stock`, `balance`, `paid`, `*_total`) that doors write from input and no posting moves. | Declare it an account and give the doors that change it `:posts`. | "What makes {field} go up or down, and should that be the only way?" |
| `[money-no-currency]` | warning | An `:int` or `:decimal` field whose name or label says money (`price`, `amount`, `fee`, `copay`, `*_cents`, `$`). | Make it `:waymark/money`; the migration fills the house's currency. | "Which currency is {field} in?" |
| `[reconcile-typed]` | warning | A kind whose guard compares two or more typed money fields of its own row, none of them a fact. | Declare `:reconciles` with each side as a balance. | "Where do the numbers in {kind} come from before someone types them?" |
| `[account-unbounded]` | info | A `:count` account with no `:never-below`. | Add `:never-below 0`, or say `:may-go-negative true`. | "Can there ever be fewer than none of {account}?" |
| `[unbalanced-door]` | warning | On a kind declared `:conserves`, a door posts to one side only. | Make it a `:move`, or post from a house account. | — |
| `[money-unrounded]` | error at the write | A money write or posting not at the currency's scale. | Wrap the expression in `round`. | — |
| `[mixed-currency]` | error | An expression adds or compares money of two declared currencies. | Convert explicitly, or keep the sides apart. | — |
| `[rate-unfiltered]`, `[posts-unfiltered]` | error | A rate table's key or effective field, or an account's row ref, is not `:filter` (`[exists-unfiltered]`'s reason). | Declare them filterable. | — |
| `[balance-drift]` | live, error | A `balance` row differs from the sum of its postings. | The engine recomputes it from the postings and files a ticket with both numbers. | — |

---

## 11 · Migration — the 16 workarounds

The rehearsal logs name the workarounds by shape; step 12 re-reads them
and checks this table's mapping row by row.

| Shape (the rehearsal's words) | Rehearsal | Construct |
|---|---|---|
| "a kiln batch adds stock to each product" | shop | `:accounts on_hand` + `fire_batch :posts` |
| "an order reserves its items the moment it is placed" | shop | `:lines` + `place` `:move on_hand → reserved` |
| a restocked return goes back into stock | shop | `restock_return` `:move sold → on_hand` |
| "the stock it took never goes below zero" | shop | `:never-below 0` |
| a refund "never more than was paid" | shop, clinic | `refunded :never-above :paid` |
| "every month the books balance: processor in = orders paid − refunds − fees" | shop | `month_close :reconciles` |
| "under 5 of a product, the owner is told" | shop | a fact `(< (data :on_hand) 5)` on the projected balance, with the existing notice rule |
| line items as prose | shop, landscape | `:lines` with `:totals` |
| integer cents, USD implied | all three | `:waymark/money` |
| tax by state | shop | `:rate-table :sales_tax` + `:tax` total |
| copay and balance billing; what the insurer paid against a claim | clinic | `claim` accounts `billed`, `insurer_paid`, `patient_owes` with `:move`, and `(balance …)` guards |
| a job moves to paid when the payment arrives | landscape | the payment's webhook door posts `+ paid`; the job's `mark_paid` is a fact-driven move on `(>= (balance paid) (data :total))` |

A typed balance moves in two steps: declare the account with
`:balance-of` (§4's option) and post an opening entry equal to today's
field, then switch the doors to `:posts` and drop the field.

---

## 12 · The film

One shop week, played by the shop rehearsal's cast:

1. **Monday.** The potter fires a batch: 40 Speckled mugs. `on_hand`
   reads 40 on the product, and its history says "batch #12 added 40".
   Nobody typed 40.
2. **Wednesday.** Three orders for the mug arrive in a minute; the last
   two mugs go to the first two. The third is refused at the moment it is
   placed: "Only 0 Speckled mug on hand; this order takes 1." `reserved`
   reads 2.
3. **Thursday.** `on_hand` falls to 4. The owner's feed says "Speckled mug
   is under 5" from the fact on the balance.
4. **Friday.** A customer returns a chipped mug; the refund is $18.00 of
   the $18.00 paid. A second refund on the same order is refused: "$18.00
   was paid; $18.00 is already refunded."
5. **The first of the month.** The owner opens a `month_close` for the
   month before. Both sides are already there, summed from postings. The
   gap is $0.00 and `close` is available; the month is closed, and a
   late posting dated inside it is refused with "post it to this month".

---

## 13 · Not in this spec

- Currency conversion and exchange rates. Mixed currencies are unknown,
  never converted.
- A general ledger, double-entry journals, or a chart of accounts as rows
  (`ledger_account` is kept as an option, §3).
- Payment processing itself. A processor's webhook arrives through the
  existing webhook door; this spec only says what that door posts.
- Inventory valuation (FIFO, average cost) and cost of goods.
- Tax filing, and tax rules beyond a rate looked up by key and date.

---

## 14 · Build plan

Each step is one ticket and one pull request, in order, each under the
800-line ceiling. **C1** marks a step that needs concept 1's related-row
reads (`follow`, `:find` at guard time); **C2** marks a step that needs
concept 2's dates (`(today)`, periods, the governing calendar).

1. **`:waymark/money`.** The type, its wire form (string amount, refused
   number), the two storage columns, the law record, `money`/`amount`/
   `currency`, the arithmetic table of §2, and the fingerprint form.
   Tests: the §2 table, and that no existing law's fingerprint moves.
2. **Rounding.** The house's `:rounding`, `(round m)` by ISO minor unit,
   `[money-unrounded]` at the write.
3. **Accounts and the `posting` table.** `:accounts` and
   `:house-accounts`, units, the table and its indexes, the `balance` cache
   row, the read-only projected field, and the engine-served read-only
   `posting` kind. No door posts yet.
4. **`:posts`.** Entries, `:sign`, `:when`, `:move` normalization, the
   atomic write with the log row, idempotency by log id, and `undo`'s
   reversal. `effective_on` defaults to the commit's UTC date until step 9.
5. **`(balance …)` and the bounds (C1).** The reader through `:find`, the
   locked read, `:never-below`/`:never-above` as derived guard trees, and
   the refusal sentence. Test: two concurrent orders for the last mug, one
   refused.
6. **`:lines` (C1).** The child kind, the parent doors' `lines` input,
   `(lines)`/`(line …)`, `:each` in `:posts`, defaults through `follow`,
   and `:totals` computed in the door's transaction.
7. **`:rate-table` and `(rate …)` (C1, C2).** Lookup by key and the latest
   `effective` on or before the date; unknown when absent;
   `[rate-unfiltered]`.
8. **Scenarios and the probe.** `:postings` and `:rates` in law
   scenarios, balance expectations, unknown on the probe, clones copying
   postings, the law sweep for new bounds.
9. **Periods and `:reconciles` (C2).** `effective_on` in the house's zone,
   `(balance … {:from :until})`, `[:all kind name]`, the close's sides and
   gap facts, the derived `close` guard with its evidence, and
   `[period-closed]`.
10. **Audit.** `[balance-typed]`, `[money-no-currency]`,
    `[reconcile-typed]`, `[account-unbounded]`, `[unbalanced-door]`,
    `[mixed-currency]`, `[posts-unfiltered]`, and the live
    `[balance-drift]`, each with its question.
11. **Shop.** The rehearsal's product, order, return and month-close kinds
    take accounts, lines, rates and `:reconciles`; the film's five steps
    are its acceptance walk. Clinic claims and landscape jobs follow in
    their own tickets if this one would pass the ceiling.
12. **Survey.** Re-read the three rehearsal logs, check §11's mapping, and
    file one ticket for each workaround these steps did not retire.
