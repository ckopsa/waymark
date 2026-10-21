# The org kit

An org is code-first: a thin directory of declarations that a seat
writes and the factory builds. Boot, conformance, onboarding and
clones all read this one shape. `orgkit/template/` is the starter
directory every org copies; `orgs/example/` is a filled copy, a tiny
two-kind org with one seat.

## The shape

```
orgs/<org>/
  deps.edn                 waymark10 and factorykit by local root
  org.edn                  the manifest: {:name "<org>" :live false}
  src/<org>/org.clj        the module: resources, check-resources, domain
  src/<org>/kinds/*.clj    one defresource per kind, guards as defguard sentences
  resources/<org>/seed.edn what a fresh engine holds at boot
  README.md                what each file is
```

To start an org, copy `orgkit/template/` to `orgs/<org>/` and replace
every `myorg` with the org's name: the directories under `src/` and
`resources/`, the namespaces, the domain keyword and the seed. The
`deps.edn` roots are relative to `orgs/<org>/`, so a copy keeps them.
`clojure -M:check` in the org's directory runs the declaration gate,
and `factory10.orgkit-test` loads the template and the example.

## The manifest

`org.edn` is the org's manifest: at least `{:name "<org>" :live
false}`. The template writes it with `:live false`, and going live
flips it to `:live true`. The conformance suite requires it: a missing
one is the finding `manifest/missing/<org>`. The house engine reads
it: at boot it lists the org directories under `WAYMARK10_ORGS_DIR`
(`../orgs` from the engine's working directory unless set; the image
copies `orgs/` there), keeps the live ones, loads each one's `src` and
`resources` through its own class loader, then serves its kinds and
runs its seed, as `WAYMARK10_ORG` does for one org on the classpath.
No live org is a deps root of the engine.

## Naming rules

- The org name is the namespace root and the domain name: org
  `example` is `example.org`, `example.kinds.*`, `resources/example/`
  and the domain `:example`. It is snake_case: `[a-z][a-z0-9_]*`.
  The namespace root is the org's name exactly, underscores included:
  org `two_words` is `(ns two_words.org)` and `two_words.kinds.*`,
  never `two-words.org`.
- A kind name is the org's to choose, but it may not be one the
  engine already enrols (`job`, `member`, `grant`, `seat`, `ticket`
  and the rest of `waymark10.modules/inventory`): the registry holds
  one law per kind. That is why the example's work is a `work_order`.
- The module is `<org>.org`, and it declares `resources` (its kinds,
  stamped by `in-domain`) and `check-resources` (zero-arg, for the
  gate).

## Writing a kind

A kind is one `defresource` in `src/<org>/kinds/`, its guards as
`defguard` sentences: `(defguard name (refuse "the sentence") 'law)`.
`orgkit/template/src/myorg/kinds/item.clj` carries each form below as
a comment; `docs/spec-related-row-law.md` is the whole grammar.

**Reference forms.** A law reads a field through a reference form:
`(data :k)` is what the row already holds, `(input :k)` is what the
caller sent to this door. A bare keyword anywhere else is refused at
load ("keyword outside a reference form"); `(present? :k)` is the one
shorthand that takes one. `(is-set (input :k))` asks whether an
optional input was sent.

**A related-row guard** names other rows: `exists` holds when one
matches, `none` when none does. The binder is `[name kind where]`;
`where` maps a field of the other kind to `(op <expr>)`, with `op` one
of `= not= < <= > >=` or `(in v …)`. The binder's name is the
refusal's `{name.field}`:

```clojure
(defguard crew-is-free
  (refuse "{b.title} already has this crew that day; pick another crew or day.")
  '(none [b :booking {:crew  (= (input :crew))
                      :day   (= (input :day))
                      :state (in "scheduled" "in_progress")}]))
```

`(follow (data :customer_id) :status)` reads one field of the row a
ref names.

**A door's input.** A door that takes arguments declares them as a
malli map; its guards read them as `(input :k)`:

```clojure
:schedule {:from #{:requested} :to :scheduled
           :input [:map
                   [:crew [:string {:min 1 :max 80}]]
                   [:day :waymark/date]]
           :guards [crew-is-free]
           :safety {:idempotent true :reversible true :confirm false}}
```

**Dates.** `:waymark/date` is a calendar date, an ISO string on the
wire (`"2026-10-22"`); `:waymark/instant` is a point in time, RFC 3339
on the wire. A schema field is `[:due_on {:optional true} [:maybe
:waymark/date]]`. Keep dates out of plain strings.

**Fields that hold an id.** The `unref'd-ids` check refuses a short
string field (a `:max` of 64 or less, not a `prose` widget) whose name
reads as an id and that nothing declares a ref: a name ending `_by`,
one of `owner author caller member sitter person`, or a served kind's
name, bare or with `_id` (`request`, `request_id`). Declare a row ref
with its kind, a member with `:x-ref {:principal true}`, or waive a
field that holds no id with `{:not-a-ref "why"}`:

```clojure
[:request_id {:optional true :kind :request} [:maybe :waymark/ref]]
[:checked_by {:optional true :x-ref {:principal true}} [:maybe [:string {:max 80}]]]
```

## The seed

`resources/<org>/seed.edn` is the demo seed's grammar
(`docs/spec-demo-clones.md` § 1), with an org's own sections beside
it:

- `:seed`, `:version` and `:org`, the org's name.
- `:domains`: each `{:name, :label}`; the first is the org's own. The
  first boot makes each a `domain` row through its create door, as the
  owner and before the seats: its charter is its `:charter` or its
  `:label`, its budget its `:budget_usd_per_week` or the factory's 3600.
  A `:ref` names the row, so a seat template's `:domain_id` can be
  `[:ref …]`.
- `:cast`: the members, as in the demo seed. On the house, the
  `:owner`'s `:id` is the filing person's member id, a member the seed
  reuses; the onboarding seat writes it from the plan's owner. An org
  whose seed is refused at boot is skipped with a draft ticket, and the
  house boots without it. A member may declare
  `:scope`, in a seat's own shape (`[{:kind "request" :actions
  ["accept"]}]`, `"*"` for every action of a kind): its ceiling, what
  any seat it owns may hold. A member with no `:scope` holds the whole
  org: every kind and action the engine serves.
- `:seats`: each a factorykit seat template and its params,
  `{:template "mayor" :params {...}}`, or a whole seat map under
  `:seat` when no template walks the org's kinds. Either may name
  `:owner`, a `:cast` key: the member whose ceiling the seat's scope
  sits inside. A seat with no owner is judged only against the kinds
  the engine serves.
- `:judgments`: each a factorykit judgment template and its params.
- `:steps`: the starting rows, each an ordinary create or invoke by a
  cast member, as in the demo seed.
- `:rehearsal`: the outside world, `{:cast {...} :seats [...] :steps
  [...]}`, which only a clone loads (ticket a4149376): the loader merges
  it onto the seed when the engine boots as a clone and drops it
  everywhere else, so a live org never holds a pretend client nor
  seats the world. Its cast are the people outside the business who
  start work, each with a `:scope` that creates the kind they send;
  its seats hold the `world` template. A seed whose own `:seats` hold
  an `acts_as_cast` seat may set `:rehearsal-seats :skip-off-clone` in
  org.edn: off a clone the loader skips that seat with a line instead
  of refusing it. Conformance answers `no-outside-world/<org>` for a
  seed with no such cast member or no world seat; `:world :none` in
  org.edn waives it for an org that only works inside.

The loader that walks it on a fresh engine is M1b's.

## Conformance

`factorykit.conform` judges an org the way the engine would:
`(conform org-dir)` answers findings, each `{:key :severity :class
:subject :sentence :remedy}`, and an org passes when it answers none.
It loads the module, fills the seed's seats, runs the defresource and
registry checks, the cracks audit over the kinds and the seed's seats,
the sentence checks on guards and confirm doors, a memory engine's
discover, schema, query, get and dry-run create for every kind, and
the seats' scopes against the kinds the engine serves and against
each owner's ceiling, and the person's business questions (the
`decision` class, below).
`clojure -M:conform` runs it in an org's directory, and
`clojure -M:conform ../orgs` in `factorykit/` runs it over every org;
CI runs the latter.

## Decisions

The `decision` class is the questions only the org's person can
answer. Each finding's sentence is the question, put to the person in
their words; the onboarding seat asks it and writes the answer into
the code, and the finding clears on the next run. It reads only the
declarations and the seed's seats:

- `decision/unheld_final/<kind>`: a door with `:safety {:reversible
  false}` that a seed seat no person sits holds, with no guard that
  holds it. "Should the AI do this on its own, or wait for a tap?"
- `decision/no_wait_limit/<kind>/<state>`: a state that is not
  terminal and has no expected age. "How long may a row stay here
  before someone is told, and who?"
- `decision/money_unbounded/<kind>/<action>`: a door a model seat
  holds whose input names money (`amount`, `fee`, `price`, a field
  ending `_cents` or `_usd`) that none of its guards reads, with no
  guard that holds it. "Above what amount should this wait for you?"
- `decision/decided_says_held/<kind>/<action>`: a door a model seat
  holds whose `:decided` sentence says it waits for a person ("only
  on", "'s tap", "waits for", "never on its own") while no guard
  holds it. Add a `:hold` guard for that person, or reword the
  decision if the AI may act alone; `:decided-held {:none "why"}`
  keeps the sentence as narrative, and the law page says so.

Each clears in code, one of two ways. Either the law says it:

- a hold on the door, so an agent's call waits as a held_call for the
  person's tap while a person's own call goes through. It is one
  line, and the one way to write "wait for me":
  `(defguard principal-signs-off (hold "A study is signed off only on The Principal's tap."))`
  (unheld_final). Its law is optional; with one, it holds only while
  the law is false, so for money it reads the amount and holds above
  the line:
  `(defguard big-spends-wait (hold "Spending $5,000 or more waits for your tap.") '(< (data :amount_usd) 5000))`
  (money_unbounded). By default the tap is the agent's own person's;
  `:for :role` after the sentence names whose it is instead, read from
  the seed's cast roles, and the Allow door refuses a person without
  that role:
  `(defguard return-is-filed (hold "A return is filed only on a tax specialist's tap." :for :role "tax_specialist"))`;
- the kind's `:expected-age {<state> <seconds>}` (no_wait_limit).

Or the person's answer is written down as `:decided`, a sentence in
their own words:

- on an action, `:decided "The mayor marks an item done on its own
  once its assignee has done the work."` (unheld_final,
  money_unbounded);
- at a kind's top level, `:decided {<state> "sentence"}`, as
  `:decided {:open "An item may stay open for a week; past that, the
  mayor tells the owner."}` (no_wait_limit).

`:decided` is prose, never law: the engine does not enforce it, so an
answer of "wait for me" is a `:hold` guard, its reason the guard's
`:explain`, and `:decided` says only what the AI may do on its own.
`factory10.law-text` renders every one under "What you decided", a
kind's states first and then its doors, so the person reads back what
they said. `orgkit/template/` and `orgs/example/` answer theirs this
way.

## What an org may not do

- No engine code: an org declares kinds, guards and its seed. A
  framework gap is a ticket against `waymark10`, not a workaround in
  the org.
- No new top-level deps beyond the kit: `deps.edn` names Clojure,
  waymark10 and factorykit and nothing else.
