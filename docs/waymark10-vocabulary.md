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
| `:filter #{…}` | this field is queryable; ops from `#{:eq :in :ne :range :after :before :set :contains}` → `field=`, `field=a,b`, `field_ne=` (comma list negates as not-in), `field_gte=`/`field_lte=`, `field_after=`/`field_before=` (strict bounds), `field_set=true\|false` (presence), `field_contains=` (case-insensitive substring) | `[:start_date {:filter #{:eq :range}} :waymark/date]` |
| `:sort` | sortable — `true`, `:default`, or `:default-desc` | `[:name {:sort :default} [:string {:min 1 :max 200}]]` |
| `:derived spec` | the fact's law rides its own entry (spec: `{:over … :expr …}`, `{:count {:owns …/:related … :where …}}`, or a `defderived` value) | §3 above |
| `:part-scope {:key …}` | the collection field the part-scoped actions (`:place`) bind items of | `[:days {:part-scope {:key :date}} [:vector …]]` |
| `:kind` | on a `:waymark/ref` entry: the target kind — the picker, the navigable link, and the assembly ref-check all read it | `[:plan_id {:kind :plan} :waymark/ref]` |
| `:external-key` | on a **mirror kind's** `:waymark/ref` entry (or `[:vector :waymark/ref]`): the sibling field whose external id(s) the sync write resolves to target mirror row ids (scalar: nil until the target exists; vector: the resolvable projection of the array, grow-only under the discovery heal) | `[:employee_id {:kind :employee :external-key :employee_zenefits_id} :waymark/ref]`, `[:member_ids {:kind :employee :external-key :member_zenefits_ids} [:vector :waymark/ref]]` |
| `:adopts` | on a **mirror kind's** entry: the ISO date external authority begins — the feed is ignored (local territory) before it; law content evaluated against each sync write's clock, fingerprinted in the authority facet | `[:net_aum {:adopts "2026-09-01"} …]` |
| `:frozen` | on a **mirror kind's** entry: the ISO date external authority ends (or `true` — frozen as of now) — syncs until the boundary, stands as history after; absence BEFORE a declared sunset holds the stored value | `[:total_aum {:frozen "2026-08-01"} …]` |
| `:expect` | on a **mirror kind's** entry: the field's declared dynamics — `:immutable` (a document moving a set-once value lands the row conflicted; resolve_conflict ratifies), `:volatile` (a pass where it moves nowhere warns — the feed may be stale), `{:churn n}` (at most n% of rows may change per pass; a breach is held like the mass-absence census; widen the bound to ratify a legitimate mass change) | `[:started_at {:expect :immutable} …]`, `[:department {:expect {:churn 10}} …]` |
| `:label` | the sibling field the engine maintains the ref target's label into | `[:meal_id {:kind :meal :label :meal_name} …]` |
| `:carry` | on a `:waymark/ref` entry: `{source-field target-field}` — further target data fields the engine copies alongside the label (generated, never hand-copied; same freshness scope — stamped at the write that sets the ref; the carried entries are engine garnish in data, no schema entry declared) | `[:chore_id {:kind :chore :label :chore_name :carry {:notes :chore_notes}} …]` |
| `:predecessor {:order …}` | resolve at create to the newest sibling by that field (period chaining) | `[:previous_plan {:kind :plan :predecessor {:order :start_date}} …]` |
| `:pick` | the picker's collection query — the generic client fetches the ref's options WITH these params (presentation, never fingerprinted; enforcement stays with guards); keys are `:state` or an `:eq`/`:in`-filterable field of the target, values scalar or list (`a,b` in-list on the wire); the assembly refuses a pick the target collection would 400 | `[:ingredient_id {:kind :ingredient :pick {:state :active}} …]` |
| `:x-display` | advertisement: `{:label …}`, `{:help "…"}` (the hint sentence rendered under the field, and the one an MCP agent reads), `{:choices {"token" "the sentence a person reads"}}` (enum value prose — a select shows the sentence, the wire keeps the token), `{:widget "prose"}`, `{:hidden true}`, `{:showcase true}` — showcase renders a filterable field's filter as a standing control above the collection table (a select for enum'd/faceted fields, a min–max pair for `:range`, a date input for `:after`) instead of tucking it in the Filters popover | `[:kind {:x-display {:showcase true}} …]` |
| `:examples` | scaffolding for a composition field: a vector riding as standard JSON-Schema `examples`, offered as the textarea's placeholder and read by agents. Unlike `:default`, nothing is ever applied from it — an example is a starting point offered, never a value assumed (`docs/spec-usability-battery.md`) | `[:recipe {:examples ["Brown the onions, then …"]} …]` |

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
One more ctx fact, and it is about the CALL rather than the world:
`(:within ctx)` is the `{:kind … :action …}` of the write a nested
`ctx :invoke` / `ctx :create` was opened inside of, and nil at the wire
boundary, on the render probe and in every rehearsal (waymark-jfv.20). A
guard that reads it declares `:reads [:within]` — the check tier serves it as
nil, which is the same answer a client's knock gets — and it is the one
honest way to say *this door opens for another kind's own handler and for
nobody's hand*: `composition_request`'s `answer` admits `outcome`'s create
and refuses everything else by name.

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
| `:facts` | ENGINE-maintained entries (optional, nullable, no generated editor, absent from the create schema) — each row names a top-level `:derived` law, e.g. `:facts [[:overdue :boolean]]` beside `:derived {:overdue {:over [:due_date :now] :expr '(< (var :due_date) (date-of (var :now)))}}` (chore_run's clock-flipped overdue demanded the group) |

The `:when` discriminator is found, not named: exactly one `:at-create`
`one-of` field must offer every `:when` key, or the declaration refuses
with the fix spelled out. Generated editors prefill their group, carry
the union of the group's prose draft policies, mint the idempotent
overwrite safety, and refuse at the def site if they'd collide with a
declared action name. A top-level `:derived` **count** fact with no
declared entry gets its `[:maybe :int]` entry appended; every other
derived fact declares its own shape as a `:facts` row.

## 8 · Declaration and action key sets

The closed sets themselves (what the declaration gate and the kondo hook
refuse against) print from the REPL — `(waymark10.dev/vocab)` — and live in
`waymark10.declaration/top-level-keys`, `…/action-keys`, and
`waymark10.resource/flow-opt-keys`. Flow rows are
`[from action to opts?]`; per-origin rows of one action must agree on
everything but `:confirm`.

`:answered-at-a-door` names the states of this kind in which **words are
not an answer** (waymark-vf8) — a row waiting on an ACT by whoever holds
one particular door:

```clojure
:answered-at-a-door
{:iterating {:door :rework
             :whose :composed_by
             :explain "This bundle is handed back for a rework. Answer at {door} — … Words alone do not answer an iterate."}}
```

`remark`'s create wall (`words-do-not-answer`) reads it off the SUBJECT's
declaration and refuses a turn from the one hand that could act: an AGENT
that is the row's `:whose`, or that presented a grant admitting
`kind`.`:door` on that row. `{door}` in the sentence is filled with the
row's own address (`/api/<plural>/<id>/-/<door>`); a person's turn, a
bystanding agent's turn, and any turn a door files from inside itself
(`(:within ctx)` — the wall fires at the wire only) all pass. Each clause validates at the def
site: the state is declared, `:door` is an action of this kind, `:whose`
is a schema field, and `:explain` says `{door}`. Advertisement-class — no
fingerprint facet names it.

An action's `:display {:marks "<rel>"}` says that this verb is answered
about the row's PARTS before it is answered about the row (waymark-wxk),
and names the **link rel** whose rows those parts are:

```clojure
:iterate {… :display {:label "Iterate" :order 3 :marks "pieces"}}
```

The generic screens read it and nothing else: the feed card opens a
per-part selection in place before the verb's own note, and `ui_lite.html`
follows the named rel off the row's declared links. Each part's own
selection door is found by the `:display {:reasons true}` it already
advertises, and the words offered are the reason kind's own enum — so no
application kind or door name reaches either page. Advertisement-class,
like `:reasons` beside it: it rides no fingerprint facet.

`:views` declares alternate collection views, each its own closed map
(`waymark10.declaration/view-keys`):

```clojure
:views [{:name :triage :kind :deck :where {:state "pending"}
         :right :approve :left :flag
         :card [:title] :display {:label "Triage"}}
        {:name :review :kind :feed :where {:state "pending"}}]
```

`:name` is a unique snake token (`view=<name>` in the client's hash picks
it), `:kind` is `:deck` (swipe triage) or `:feed` (sequential read).
`:where` carries the `:default-filters` wire semantics — an ordinary
filter the caller could have typed, values normalized to wire strings at
the def site — and `:card` names the data-field subset a card shows. A
`:deck` is a queue, so its rules are checked by name (`[views]`): `:where`
must constrain `:state`, both gestures are required and must name declared
REVERSIBLE actions (a swipe is a snap judgment — `:undo` is the honest
reverse), each gesture departs from every `:where` state and lands outside
them (the queue drains itself). A `:feed` refuses gestures. Views are
advertisement, never law: the envelope carries them beside
`actions`/`links` (deck entries with `gestures {right {action label} …}`,
labels from the action's own display), the fingerprint never moves, and
the per-item affordances still gate what a gesture may actually do. The
generic client renders switcher chips; a view kind with no registered
renderer falls back to the table.

Views are also authorable at RUNTIME (waymark-rla): the framework ships a
ready-made `saved_view` kind (`waymark10.saved-view/saved-view`) an app
opts into by adding it to its resources vector, like any app kind —
storage, forms, grants, and events come free. A row carries the same
surface as a declared view, as wire strings: a `label`, the `target`
collection (kind name or plural), `view_kind` (`deck`|`feed`), `where`
(filter params in the collection's own wire grammar, e.g.
`state=pending&owner=ana` — the same string the filter bar puts in the
hash), `card`, and the deck gestures `right`/`left`. The declaration-time
`[views]` rules are enforced at WRITE time instead, by the same extracted
battery (`waymark10.checks/view-problems`) judged against the live
registry (`ctx :rdef-of`): create and revise refuse a view its target's
declaration would refuse. ACTIVE rows merge into the target collection's
envelope `views` beside the declared entries, marked `source: "saved"` and
carrying the row's own `href`; a row stranded by a redeploy (its gesture
retired, say) is skipped there with a warning — never a broken page — and
stays visible in the `saved_views` collection for its owner to fix or
retire (`retire`/`restore` are an `:undo` pair; `clone` forks a copy
through the same create gate). The client adds one affordance: a "save
view" chip on any filtered collection, prefilled from the current params.

Surfaces are user-composable the same way (waymark-ggw): the framework
ships a `dashboard` kind that OWNS `dashboard_slot` parts
(`waymark10.dashboard/resources` — an app opts into the pair like any app
kinds). A dashboard is the anchorless declared surface's user-authored
sibling: each slot is a collection query composed from declared primitives
only — a `target` (kind name or plural), a `where` in the collection's own
wire grammar, an optional `view` deep link (a declared view token, or the
`sv-<id>` name the envelope mints for an active saved view targeting the
same kind), and a `seat` ordering int — never a query/layout DSL. Slot
create/revise run a write gate in the saved_view tradition: `ctx :rdef-of`
resolves the target, the shared `checks/view-where-problems` battery
judges the `where`, and a `view` reference must resolve. The dashboard's
GET splices its ACTIVE slots at `links.slots.embedded` (the render
contract); the generic client forks on the kind and renders a grid of live
panels — label, truthful count, a few top rows, a click-through carrying
the slot's params (+ `view=`) — each panel fetched concurrently and
degrading alone: a slot stranded by a redeploy wears the collection's own
refusal with a retry and the door to revise or remove it, never a broken
page. `retire`/`restore` and `remove`/`restore` are `:undo` pairs;
`clone` deep-copies the active slots through the same create gate, so a
stale slot cannot propagate.

`:retain` is what the transition log carries forward past the write —
`{:judgment bool? :data bool?}`, per kind, **default off**, and closed to
those two entries:

```clojure
:retain {:judgment true}
```

`{:judgment true}` is the decision record
([spec](spec-decision-record.md)): each committed transition of this kind
carries a `judgment` object naming the guards that judged, the verdict
each returned, and — for a guard whose verdict is a form — its declared
`:vars` evaluated over the scope it judged. *The decision record is the
refusal sentence the guard did not have to give*, so an author who wants a
fuller record declares fuller `:vars` and gets a better refusal for free.
A code guard or a composite records `opaque` (its check is a closure, or
its arms are law the declaration folded on purpose); a `{:secret true}`
field is never captured at all; `:adopt` and dry runs record nothing.

Which guards judged needs no retention and no column: it derives from the
row's `law_revision` through
`(waymark10.server.decision/basis rdef action revision)`, free and
retroactive to every transition ever logged. Retention buys only the
evidence. `:retain` is not law — `fingerprint-of` does not name it, so
declaring it mints no revision — but it IS bytes on every transition of
this kind forever, which is why it is off until an author says otherwise.

`:decision` is one key that projects a whole verdict machine
([spec](spec-decision-kind.md)) — for a decision that is not a transition on
a domain row, where the decision IS the thing:

```clojure
:decision
{:asks    :for_what              ; the question's field
 :by      :asked_by              ; stamped from the principal at birth
 :decider {:not  {:field :asked_by :name :the-asker-does-not-sign
                  :explain "The person who asked cannot be the one to sign it."}
           :role {:name "parent" :as :a-grown-up-signs
                  :explain "A grown-up signs a permission slip."}}
 :stamps  {:decided-by :signed_by}
 :expires {:field :good_until :default 43200 :max 604800}
 :pacing  {:limit 12 :per :hour :open-cap 4}
 :own-surface true
 :verdicts [{:name :allow  :to :allowed :label "Yes" :note :answer}
            {:name :refuse :to :refused :label "Not this time" :note :answer}]}
```

It desugars FIRST in `normalize-resource`'s thread, ahead of `:flow` (a
decision *is* a flow), and projects `:states`, `:initial`, `:terminal`, the
verdict actions with their guards and note editors, the schema entries the
engine owns, the `:create-schema` that omits the stamped ones, `:on-create`,
the create guards for the leash and the pacing, `:filterable` over state and
the asker, `:default-filters {:state "offered"}`, `:sortable "-created_at"`
and `:nav :system`. Everything projected is an ordinary declaration value;
nothing downstream — router, render, collections, OpenAPI, MCP, conformance
— learns a new noun. It is a **spelling, not a mechanism**, and the proof is
`approval_request`: it was respelled through this key and its fingerprint
hash did not move one byte (`waymark10.decision-sugar-test`).

Every projection fills a blank and never overwrites, so a kind declares the
extra law only it has beside the sugar. Two refusals: an action also named
in `:actions` (*one home per action*), and a verdict list under two, or one
that never leaves the open state (a single-verdict decision is a task with a
checkbox; a decision that lands nowhere is a queue that never drains).

`:on-create` is the one slot that **composes** rather than refusing: the
decision's own stamps run first — the requester is written before an authored
hook can read it — and the author's hook runs on the stamped row. A third
refusal stood here until **waymark-42m** (*one home per hook* — composing
would mint a wrapper fn, and a wrapper fn is a hash that moves for nothing);
it was recorded against a cost that had already gone, because `:on-create`
rides no fingerprint facet and, since waymark-j82, a bare fn hashes by its
address. What demanded the composition is the `insight` kind: the address its
offer is reached at is derived at birth from the kind and id the author named,
so no composer supplies a hidden field, and a decision kind had nowhere to put
that derivation.

`:decider` is the eligibility dimension. `{:not <field>}` is a FIELD
four-eyes wall (`guards/not-the-field`), not `guards/four-eyes` — the latter
is a transition-history wall over `(:actor-of ctx)`, and a decision row's
asker is stamped at birth, before any transition exists to be the actor of.
`{:field <field>}` is `guards/is-the-field`, "the person this row names
decides it". `{:role …}` composes with either. The walls land as SEPARATE
guards in the verdict's `:guards` vector, never folded under `g/and`: an
action's guard list is already a conjunction, and a composite records
`opaque` in the decision record — a folded wall would leave the log a name
with nothing behind it. Each wall carries its own `:name`/`:explain`; a
sentence spelled at the `:decider` level is shorthand for the one-wall case
and refuses beside a second wall.

`:own-surface` says who sees their OWN rows of a kind with **no grant at
all** — the courtesy that used to be a literal set of seven kind names
inside `waymark10.server.grants`:

```clojure
:own-surface {:by [:owner :to] :actions #{"create" "open" "discard"}}
```

`:by` is a vector of branches, because ownership is not always one-party (a
letter is yours as its AUTHOR or its RECIPIENT, and the branches union — the
store's cond map is a conjunction with no OR). A branch is a field keyword,
pushed down as a query cond, or a PATH vector into the document, filtered in
memory (`:job`'s requester rides as an object). A bare keyword spells the
one-branch case, and `:decision {:own-surface true}` spells "by the
decision's own `:by` field, with the verdict doors". `:all true` is the
vocabulary posture — every row is everyone's words, not anyone's data (the
`:capability` registry) — deliberately a separate key from an empty `:by`,
so *owned by nobody* and *owned by everybody* are not one typo apart.
`:actions` names what the courtesy makes VISIBLE; the guards still judge
every invoke, so a self-judging asker meets the wall's honest 409 rather
than a mute 404. Neither key is law: `fingerprint-of` names neither, so
declaring either mints no revision.

## 9 · `:touches` — the declared cross-write set

An action that advances OTHER rows says so on its declaration:

```clojure
:absorb {:from #{:active} :to :active
         :touches [{:kind :product :action :rematch :may true}
                   {:kind :ingredient :action :retire}]
         …}
```

Blast radius is law (fingerprinted, code-or-shape): the envelope
renders the set on the action entry so an agent reads what a confirm
will reach before it confirms, the assembly refuses a touch naming an
unregistered kind or a missing action, an owns-cascade `:on` target
the parent action does not advertise draws a coverage warning, and the
conformance library (`touches-violations`) holds every logged run to
the promise by correlation id — `:may true` tolerates a touch that had
nothing to do on a given run (an empty cascade, a conditional write).
The writes themselves come from the owns cascade, the handler's
ctx `:invoke` door, or the ctx `:create` door (chore's queue verb
demanded it): a handler births a row of ANOTHER kind through the same
transaction and the full create algorithm, the born row riding the
inner sink so its lifecycle/cascade/maintenance run post-commit; the
touch names the target's create action
(`{:kind :chore_run :action :create}`). `:touches` is the
advertisement, never the mechanism. The door is open to `:on-create`
hooks too (the plan_day fan-out demanded it), where it DEFERS: births
queue and land right after the hook's own row inserts, so a child's
ref label reads a real parent and the log orders parent before child;
a birth cycle in the declarations refuses at depth 8.

## 10 · `:default` — the declared field default

```clojure
[:ratio {:optional true :default 1M} [:maybe [:decimal {:gt 0 :max 100}]]]
[:quantity {:optional true :default 1} [:maybe [:int {:min 1}]]]
```

The doors (create + action input) fill ABSENT keys with the declared
default before validation — an explicit null stays (the author said
blank), a present value is never touched, vector-of-map items fill
their own item defaults, and a mirror mint takes none (the authority's
absence means absence). The projection carries the standard
JSON-Schema `default` keyword, so the generic client's form prefills
it. Defaults are LAW: a default changes what a blank write stores —
the fingerprint's `create` facet (`{"defaults" {…}}`) and each
action's `input_defaults` project them, non-empty-only, so the
default-free world hashes as ever. The checks refuse a default the
field would not accept and any default on a derived field (one fact,
one writer).

## 11 · `:unique` — declared uniqueness, enforced

```clojure
:unique [[:plan_id :date]]   ; one plan_day per plan and date
```

Each group emits a UNIQUE index over its promoted generated columns
(every named field must be `:filter`able or `:sort`able — the check
that always held). A colliding write refuses as a 409 problem
(`unique-conflict`), never a 500. Uniqueness is the row's identity,
not its state: a released/removed row still holds its slot — design
for that (or scope the group) deliberately.

## 12 · What a collection page opens on — the sort and filter defaults

```clojure
:sortable {:fields [:created_at] :default "-created_at"}
:default-filters {:state "offered"}
```

`:sortable :fields` may name `:created_at` and `:updated_at` beside the
kind's own schema fields. They are not schema entries — they promote no
`f_` column, they advertise no filter param, and ordering by one runs over
the engine column the table already carries — so recency is expressible for
every kind, not only for the ones that happen to carry a timestamp of their
own. A schema field NAMED `created_at`/`updated_at` refuses at the def site
(`[sortable]`): one word, one meaning. Naming one also mints its index
(`ix_<table>_created` / `_updated`) — the migration is additive, and a kind
that never asked to sort by the clock keeps the fingerprint it always had.
Recorded punt: on a mirror kind `created_at` is when the LOCAL row was
minted, not when the authority created the thing.

`:default-filters` is a `{field value}` map applied only when the caller
named no filter on that field — an ordinary filter the declaration types
for you. Explicit always beats it: `?state=denied` overrides, `?state=`
(empty) clears it without re-substituting, and a filter on any other field
leaves it standing. A default naming a field that is not `:eq`/`:in`
filterable, or carrying a value the field's schema would refuse, is a
definition error (`[default-filters]`) — caught at the def site, not at the
first request.

Because a default filter HIDES rows, it is never allowed to apply
invisibly. It is advertised on the query action's input (`"default"` on the
param, exactly as sort's already rides `sort`), spelled into the
collection's `self` href so the URL a person copies is the view they saw,
echoed in the envelope's summary (`filtered: state=offered`), and rendered
by the generic client as an ordinary removable chip whose ✕ sends the param
EMPTY rather than dropping it. Embedded collections (`embed.<rel>.*`) take
no default filters — their href is the parent's, and their advertised
columns drop the `default` the parent will not apply.
