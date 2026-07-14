# The waymark10 vocabulary — the language the framework enforces, taught

The authoring probe's first run (docs/waymark10-authoring-probe.md, D1/D2)
found that waymark10 refuses unknown spellings precisely but teaches the
known ones nowhere: the expression ops lived only in `expr.clj`, the
colocated-law keys only in `resource.clj`'s normalizers and in neighboring
resources' style. This page is the fix. Its REPL twin is
`(waymark10.dev/vocab)`, which prints the same sets read from the live vars —
if this page and the REPL ever disagree, the REPL is right and this page has
a bug.

Everything here is enforced: an unknown op refuses at the def site
(`expr/problems`), an unknown declaration key refuses with a path
(`waymark10.declaration`), and the same typo squiggles in the editor
(the shipped clj-kondo exports).

---

## 1 · Where laws are spelled

A law is a quoted tree — one value that is authored, stored, diffed, wired,
and evaluated. Trees appear in three places, with three small dialects:

| Context | Reads fields as | Clock | Example |
|---|---|---|---|
| **Derived fact** (`:derived {:over […] :expr '…}`) | `(var :field)` — only names listed in `:over` | put `:now` in `:over`, read `(var :now)` | `'(< (var :due_at) (var :now))` |
| **Expression guard** (`expr-guard {:when '…}`) | `(data :field)` for the stored row, `(input :field)` for the action's input | `(now)` | `'(>= (input :amount) (data :total))` |
| **Sentence guard** (`defguard` + `refuse`/`warn`) | `(var :field)` — sugar for `(data :field)` | `(now)` | see §4 |

The guard's own declarations are **derived from the tree, never sniffed**:
`(input …)` reads become `:judges`, `(now)` becomes `:reads [:now]`. A
derived `:expr` may only `(var …)` what `:over` lists — an out-of-scope name
refuses at the def line.

## 2 · The expression ops — the whole legal language

From `waymark10.expr/ops`; anything else is refused with
"unknown form (… …) — not in the law's vocabulary".

| Ops | Meaning |
|---|---|
| `data` `input` `var` | one keyword field read from the row document / the validated input / the `:over`-bound scope |
| `now` | the engine clock, an `Instant` — `(now)`, no arguments |
| `it` | a quantifier's bound item in canonical form — you write named binders (§below); `(it n)` is what normalization erases them to |
| `get` | `(get <expr> :field)` — a map field off an item |
| `date` | `(date "2026-07-14")` — an ISO date literal, validated at the def site |
| `= not= < <= > >=` | comparison, two arguments |
| `and or not` | boolean; only `nil`/`false` are falsey |
| `+ - *` | exact arithmetic — division never earned its way in |
| `min max abs` | numeric |
| `days` | `(days 7)` — a whole-day span, for date arithmetic: `(+ (var :start_date) (days 6))` |
| `date-of` | an `Instant` → its UTC `LocalDate`: `(date-of (now))`, `(date-of (var :now))` |
| `is-set` | `(is-set (data :field))` — present and non-nil |
| `every some count sum` | over a collection, binder-first: `(every [d (var :days)] (is-set (get d :meal_id)))`, `(count [i (var :items)] (get i :urgent))` — or just `(count (var :items))` for the plain size — `(sum [l (var :lines)] (get l :amount))` |

**Totality rules** (why a law never throws):

- **Floats are refused at the def site** — write exact decimals: `0.02M`.
- **`nil` propagates through arithmetic** (a missing addend is a missing
  sum); **orderings over `nil` or incomparable values are `false`**.
- A missing or non-sequential collection quantifies as empty: `every` → true,
  `some` → false, `count` → 0, `sum` → 0.
- Binder names are erased to de Bruijn indices at normalization — two
  alpha-equivalent laws are one law and one fingerprint.

## 3 · The clock convention (the worked derived fact)

A clock-flipped fact lists `:now` in `:over` — that is the whole spelling of
"the clock is an input to this fact"; the maintainer indexes the flip
boundary (`next-flip-at`) and sweeps it, no write, no poll:

```clojure
[:overdue {:optional true :filter #{:eq}
           :derived {:over [:due_at :now]
                     :expr '(< (var :due_at) (var :now))}}
 [:maybe :boolean]]
```

Date-typed variant (compare dates, not instants):
`'(< (var :eat_by) (date-of (var :now)))`.

## 4 · Sentence guards — the `defguard` sugar

`(defguard name (refuse "…") '(law))` — or `(warn "…")` for an
acknowledgable warning. Three desugars run before validation:

- `(var :f)` → `(data :f)` — in a guard, a bare fact name reads the row;
- `(zero? e)` → `(= 0 e)`;
- `(present? :a :b)` → `(and (is-set (data :a)) (is-set (data :b)))`.

Every `{placeholder}` in the sentence that the law doesn't already read
becomes a `(data :placeholder)` garnish — the sentence names the numbers it
shows:

```clojure
(defguard fields-complete
  (refuse "A transaction goes to review with its value type, amount, and effective date set.")
  '(present? :value_type :amount :effective_date))
```

## 5 · Colocated entry properties — field law that rides the schema entry

Written on a schema entry's property map; normalization projects each into
the canonical top-level key, so the colocated and split spellings are one
law and one fingerprint. Declaring a concern both ways is the `:one-home`
refusal; a typo'd law key is the `:unknown-law-key` refusal.

| Property | Means | Example |
|---|---|---|
| `:filter #{…}` | this field is queryable; ops from `#{:eq :in :range :after}` → `field=`, `field=a,b`, `field_gte=`/`field_lte=`, `field_after=` | `[:start_date {:filter #{:eq :range}} :waymark/date]` |
| `:sort` | sortable — `true`, `:default`, or `:default-desc` | `[:name {:sort :default} [:string {:min 1 :max 200}]]` |
| `:derived spec` | the fact's law rides its own entry (spec: `{:over … :expr …}`, `{:count {:owns …/:related … :where …}}`, or a `defderived` value) | §3 above |
| `:part-scope {:key …}` | the collection field the part-scoped actions (`:place`) bind items of | `[:days {:part-scope {:key :date}} [:vector …]]` |
| `:kind` | on a `:waymark/ref` entry: the target kind — the picker, the navigable link, and the assembly ref-check all read it | `[:plan_id {:kind :plan} :waymark/ref]` |
| `:external-key` | on a **mirror kind's** `:waymark/ref` entry: the sibling field whose external id the sync write resolves to the target mirror row's id (nil until the target exists; discovery heals) | `[:employee_id {:kind :employee :external-key :employee_zenefits_id} :waymark/ref]` |
| `:label` | the sibling field the engine maintains the ref target's label into | `[:meal_id {:kind :meal :label :meal_name} …]` |
| `:predecessor {:order …}` | resolve at create to the newest sibling by that field (period chaining) | `[:previous_plan {:kind :plan :predecessor {:order :start_date}} …]` |
| `:x-display` | advertisement: `{:label …}`, `{:widget "prose"}`, `{:hidden true}` | `[:notes {:x-display {:widget "prose"}} …]` |

**Waymark schema types:** `:waymark/date` (LocalDate), `:waymark/instant`
(a point the clock can compare), `:waymark/ref` (a reference; give it
`:kind`), `[:waymark/vocab {:open true}]` (a vocabulary token — membership
filtering and observed facets derive from the declaration itself),
`:decimal` (exact, never float).

**Typed field words** (`waymark10.dsl`): `one-of`, `date`, `flag`,
`quantity`, `money`, `percent`, `prose`, `ref-to`, `measured-by` — plain
functions returning exactly these forms; each carries its own docstring.

## 6 · Cross-resource guards — when the verdict reads another kind

A verdict that reads another kind's state is not pure over (row, input,
clock), so it stays code — `defguardfn`, with the dependency declared:

```clojure
(defguardfn meal-is-listed
  {:judges [:meal_id] :reads [:meal]
   :explain "That meal is not on the family meal list yet."
   :remedies [:meal/accept]}
  [_row inp ctx]
  (if-some [read (:read ctx)]
    (let [meal (read :meal (:meal_id inp))]
      (if (and meal (= :on_list (:state meal)))
        (t/allow)
        (t/deny {:errors {:meal_id ["not an on-list meal"]}})))
    (t/allow)))   ; the pure render probe carries no :read — advertise optimistically
```

The contract, in three sentences: `:reads [:kind]` names what the check
consults beyond the document; `(:read ctx)` is `(fn [kind id] → row-or-nil)`
and `(:find ctx)` is `(fn [kind where opts] → rows)`, present on every
enforcement ctx and absent on the pure render probe — so end with the
optimistic `(t/allow)` when the hooks are missing; return `(t/allow)` /
`(t/deny)` / `(t/deny {:errors {:field ["why"]}})` from `waymark10.types`.
An acceptance-set guard (`guard` with `:accepts (fn [row ctx] …)` and
`:reads`) does the same through its set: the rendered enum, the picker, and
the enforcement are one declaration.

## 7 · `:fields` lifecycle groups — the schema by phase

The third authoring dialect (besides `:schema` + `:actions` and `:flow`
rows): declare each field by **when it matters in the resource's life**,
and the schema, the create form, the editors, and the create gates all
derive. `:fields` and `:schema`/`:create-schema` are exclusive — the
groups ARE the schema, one home per concern. The machine's states must
be known (declare `:states`, or let `:flow` rows name them — flow
desugars first).

```clojure
:fields
{:at-create  [[:recipient [:string {:min 1 :max 80}]]
              [:occasion  (one-of :birthday :christmas :other)]]
 :when       {:other [[:occasion_note [:string {:min 1 :max 120}]]]}
 :while-open [[:idea_notes (prose "Ideas" {:shared true})]]
 :open       #{:idea}
 :support    [[:budget (money :usd)]]}
```

Every row is a `[field word-or-form]` pair — the typed field words
(§5) are the natural spelling because their entry properties ride as
metadata and get hoisted; a plain malli form works but carries no
properties. The five group keys, each a sentence:

| Group | Means |
|---|---|
| `:at-create` | create input, fixed after — required in both schemas, written by no generated editor |
| `:while-open` | authoring-phase fields: one generated editor per **open** state — `update_fields` (one open state) or `update_fields_in_<state>` (several) |
| `:open` | the still-authoring states (default `#{initial}`); must be declared, non-terminal states |
| `:support` | bookkeeping fields whose generated editor (`update_support`/`…_in_<state>`) exists in every non-terminal state |
| `:when` | `{discriminating-value [[field word] …]}` — fields optional everywhere plus a generated create gate: required exactly when the discriminator holds that value |

The `:when` discriminator is found, not named: exactly one `:at-create`
`one-of` field must offer every `:when` key, or the declaration refuses
with the fix spelled out. Generated editors prefill their group, carry
the union of the group's prose draft policies, mint the idempotent
overwrite safety, and refuse at the def site if they'd collide with a
declared action name. A top-level `:derived` **count** fact with no
declared entry gets its `[:maybe :int]` entry appended; every other
derived fact still declares its own shape.

## 8 · Declaration and action key sets

The closed sets themselves (what the declaration gate and the kondo hook
refuse against) print from the REPL — `(waymark10.dev/vocab)` — and live in
`waymark10.declaration/top-level-keys`, `…/action-keys`, and
`waymark10.resource/flow-opt-keys`. Flow rows are
`[from action to opts?]`; per-origin rows of one action must agree on
everything but `:confirm`.
