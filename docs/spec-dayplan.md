# Spec — the day plan: blocks, decisions, and the opening move

**Thesis.** The feed is context-blind. Its seed is member plus day, its
populations are per-kind row queries, and the only domain question it asks
of a row is *is this row's work over?* (`waymark10/server/feed.clj`,
`next-actions`' `work-over?`). The whole fairness machinery of laws v3 —
floors, cooling, the readable formula — exists to make a context-blind
contest fair. But a feed that knows it is five in the afternoon on a
workday, in the block the person set aside for the shop, has **no contest to
be fair about**: the answer is the card. This spec adds the thing the feed
never had, which is a **day** — one row per member per date, holding
**blocks** (a context's presence on the day), each block owning **spans**
(the windows it occupies) and **decisions** (what the person meant to do in
it). The feed reads the block whose span contains *now* and puts its
decisions on top, each with `start` as the card's verb. Tapping `start` is
the verdict.

Decided 2026-09-05 in conversation (the complexity-audit session), and
recorded here so that the slice beads waymark-i89n.2 through .7 can quote a
paragraph rather than re-derive one. No code is written by this bead.

## Epistemic status

Written against the tree on 2026-09-05, before any dayplan10 namespace
exists. Three things to hold it by.

**The modest claim is the load-bearing one.** Nothing here is new
mechanism. A kind that owns child rows through `:owns` with a `:via` ref is
`eveningplan10/resources/evening_plan.clj`. A parent that births its
children inside its own create is `mealplan10/resources/plan.clj`'s
`plan-on-create` walking the week through the ctx `:create` door
(vocabulary § 9). A cascade riding an `:owns` edge's `:on` map is
`plan.clj:410`. A clock-derived fact over `:now` is `prep_task.clj`'s
`overdue`. Two endings said out loud through `:over`, with `:undo` pairs on
the doors, is `choreplan10/resources/chore_run.clj`. An address checked at
a create door is `workqueue10/resources/insight.clj`'s
`cites-what-it-claims`. A generic Home Assistant service call is
`workqueue10/sources/homeassistant.clj`'s `service-call!`. The feed's
population registry is a closed map that has grown one entry per bead
since waymark-iqa.2. What this module adds is five declarations, one
population, one recipe line, and one grant scope.

**What deserves suspicion** is the same thing `spec-feed.md` names about
itself twice: this is a product-shaped surface, and the temptation once a
day has blocks is to let the system *infer* them — from the calendar, from
the clock, from what the person did last Tuesday. The wall against that is
recorded in the forks below and is not negotiable in this epic: **contexts
are explicit, the clock picks the default, the person overrides.** The one
thing this design does on a person's behalf is `extend` sliding a
neighbour's start, and § *The forks* keeps it to exactly that.

**Nothing in laws v3 moves.** The epic's own sentence: *nothing in laws v3
moves, and nothing in it grows until this epic has shipped.* The current
block sits above the crown, outside `contested-sections` (`feed.clj:352`)
by construction, and is judged by none of the contest's arithmetic — see
§ *Nothing from laws v3 applies inside a block*. The nine laws that carry
over are named there so a bead can check itself against them.

## What exists

**`eveningplan10/resources/evening_plan.clj` and `evening_session.clj`** —
the owns-rows precedent, and the docstring that argues it: *"a
day-in-a-plan is a real evening_session row … not data embedded in this
resource."* `:owns {:sessions {:kind :evening_session :via :plan_id}}` plus
an embedded `:links` entry, so a GET on the parent carries the children
without a second request. One caution the file itself carries and this spec
does not inherit: its populating consumer (`eveningplan10/consumers.clj`)
was written when *"the engine's create hooks are read-only across kinds."*
That is no longer the framework's posture — the ctx `:create` door is open
to `:on-create` hooks and defers the births to land right after the parent's
insert (vocabulary § 9; `plan.clj:218`) — so day_plan materialises its
blocks in its own create, not in a log consumer.

**`mealplan10/resources/plan.clj` and `plan_day.clj`** — `plan-on-create`
births one `plan_day` per date through `((:create ctx) :plan_day …)`;
`:unique [[:plan_id :date]]` on the child; `:owns [{:kind :prep_task :via
:plan_id :on {:abandon :cancel}}]` on the parent, so abandoning a plan
cancels its open prep tasks in the same transaction, advertised by
`:touches`. This is the exact shape of day_plan → block → span.

**`mealplan10/resources/prep_task.clj`** — `due_at` is `:waymark/instant`
(*"a point in time the clock can compare"*), and `overdue` is `{:over
[:due_at :now] :expr '(< (var :due_at) (var :now))}`: the clock flips it,
no write, no poll. `span.current` and `span.missed` are that fact twice.
The prep task itself is a **native** mealplan10 row that reaches the
household queue as a `task` through `workqueue10/sources/mealplan.clj`, a
`TaskSource` over `prep_tasks?state=pending,scheduled` — the confluence
drinks the authority. See the prep fork for what that means here.

**`choreplan10/resources/chore_run.clj`** — `:over {:accomplished #{:done}
:let-go #{:skipped}}` with neither ending terminal, `:undo` pairs on
`complete`/`skip`, `:display {:label "Done" :style :primary :order 1}`, and
the `:fields` lifecycle dialect (`:at-create`, `:while-open`, `:open`,
`:facts`). block and decision wear this vocabulary.

**`workqueue10/resources/insight.clj`** — `row-address` reads
`/api/<plural>/<id>` and refuses everything else (*"query strings, action
doors and bare ids are not addresses"*); `cites-what-it-claims` is a
`defguardfn` reading `:storage` that today checks the SHAPE and the plural.
Bug **waymark-79f** records that it accepted `/api/people/<invented-uuid>`
and says the fix: *resolve every evidence address when a read is available
and refuse naming the missing ones.* The decision's `subject` field is one
address and reuses exactly that check, post-fix.

**`workqueue10/sources/homeassistant.clj`** — `service-call!` POSTs to
`/api/services/<service>` with a bearer token and a JSON body, throws on
non-2xx, and is what `todo/update_item` and `todo/add_item` already ride.
It is `defn-` and a method's helper on the `HomeAssistantSource` record; the
decision's `:service` launch needs it public and reachable from a handler
ctx, which is a small change in that file and none in the wire.

**`waymark10/server/feed.clj`** — `populations` (`:3733`) is *"a closed
map, never a classpath scan"*; a population is `(fn [ctx])` over `{:eng
:principal :visibility :now :seed :day}` answering `{:kind :id}` candidates,
optionally with `:row`, `:at`, `:sentence` and `:lane`; the mixer renders,
projects and sorts, once. `census` (`:198`) is `[:outcomes :do_now :decide
:fuel :seam :archive]` and `check-recipe!` refuses a recipe out of census
order. `today` (`:901`) reads `(:zone recipe "UTC")` — and bug
**waymark-rptq** records that the household never sets it, so the day rolls
at 18:00 Mountain. `members/spellings-of` (`letters`, `:1554`) is how a
principal becomes a member row.

**`workqueue10/main.clj`** — `resources` builds the one household registry
domain by domain (`in-domain :evenings [activity evening-plan
evening-session]`); `feed-recipe` (`:513`) is the framework default with
one do-now line split in two, `:says` on each. dayplan10 enrols in the
first and adds one line to the second.

**`docs/spec-addressed-notice.md`** — a `:notice_rule` addressed to a
member through a ref field, delivered off the transition log by the
webhooks deliverer. Not yet built; no `notice_rule` kind exists in the
tree. Block start is its first per-row-instant trigger, which is why S7
lands last and conditionally.

**`docs/waymark10-vocabulary.md`** — the three declaration dialects
(§ 1, § 7): `:schema` + `:actions`, `:flow` rows, and `:fields` groups. §
*The kinds* says which each kind uses and why.

## The shape

```
day_plan (member, date, shape)
 └─ block (context on this day; stance)            ← decisions live here
     ├─ span {starts_at ends_at}                    ← the morning
     ├─ span {starts_at ends_at}                    ← after lunch
     └─ decision (kind, text, subject, launch, prep)
```

A **day** has blocks. A **block** is one context's presence on the day —
the workday, the shop, the evening — and it can occupy several windows,
because the workday continues after lunch. So a block owns **spans**, and a
span is one contiguous `{starts_at ends_at}`. **Decisions** belong to the
block and never to a span: *finish the deck estimate* is a workday
intention whether the workday is one window or two.

The day has **default blocks from context templates**, materialised when
the plan is created for a shape (`:workday` or `:off`). But the day's blocks
are the truth: the person mixes, swaps, moves, extends and splits them, and
nothing re-reads the template into a day that exists. A block the template
left out is the ordinary `block` create door plus one span.

## The kinds

Five kinds in a new module `dayplan10` under `workqueue10/src`, beside
`eveningplan10`: one `resources/` namespace per kind, a `main.clj` whose
`resources` vector the declaration gate assembles, enrolled in
`workqueue10.main/resources` as `(in-domain :day [context day-plan block
span decision])`. The sketches below are the declaration dialect and are
sketches: a slice bead fixes the exact prose on each `:x-display`.

**Which dialect, and why.** `block`, `span` and `decision` use the
`:fields` lifecycle groups (vocabulary § 7), the way `chore_run` and
`evening_session` do: each has facts that never move after birth
(`:at-create`), prose decided as the day approaches (`:while-open`), and
engine-maintained clock facts (`:facts` beside `:derived`), and the dialect
says which is which in one map. `context` and `day_plan` use `:schema` +
`:actions`: a template's fields are all authored and none is a lifecycle
phase, and `day_plan`'s doors carry inputs and handlers (`reshape`) that a
generated `update_fields` editor is the wrong shape for. Every guard that
reads only its own row and input is a sentence guard (`defguard`); every
guard that reads another row — the plan's other spans, the subject's
existence — stays a `defguardfn` with its `:reads` declared (§ 6), ending
in the optimistic `(t/allow)` when the render probe carries no hooks.

### context — the template

```clojure
{:kind :context :plural "contexts" :nav :secondary
 :states [:active :retired] :initial :active
 :summary "{data.name} · {state}"
 :label-template "{data.name}"
 :schema [:map
  [:name            [:string {:min 1 :max 80}]]                ; "Workday", "Shop", "Evening"
  [:default_shapes  [:set [:enum "workday" "off"]]]             ; which shapes materialise it
  [:default_spans   [:vector [:map [:from :string] [:to :string]]]] ; local times "09:00" "12:00"
  [:default_order   :int]                                        ; where it sits among the day's blocks
  [:with            {:optional true} [:vector :waymark/ref]]     ; :kind :member — who is usually in it
  [:seam            {:optional true} [:string {:max 240}]]       ; the sentence when its work is done
  [:feed_recipe_id  {:optional true :kind :feed_recipe} [:maybe :waymark/ref]]]
 :unique [[:name]]
 :actions {:revise  {:from #{:active} :to :active …}
           :retire  {:from #{:active} :to :retired :undo :restore …}
           :restore {:from #{:retired} :to :active :undo :retire …}}}
```

A context is what a day is made from, never what a day is. `default_spans`
are local clock times, because a template says *nine to noon*, not an
instant; materialisation turns them into instants on the plan's date in the
household zone (§ *The day boundary*). `with` is optional member refs — the
evening block is *with* the kids — and reads nothing yet; it is there so a
planning chat can say who the block is for. `seam` is the context's own
*that's everything* sentence, riding the block's section the way the
recipe's seam rides the feed. `feed_recipe_id` is optional and unused by
this epic: a context that wants its own recipe under its block is a later
bead, and the ref costs nothing now.

### day_plan — the day

```clojure
{:kind :day_plan :plural "day_plans" :nav :primary
 :states [:drafting :set :closed] :initial :drafting :terminal #{:closed}
 :summary "{data.date} · {data.shape} · {state}"
 :label-template "{data.date}"
 :schema [:map
  [:date   {:filter #{:eq :range} :sort :default} :waymark/date]
  [:member {:kind :member :filter #{:eq} :label :member_name} :waymark/ref]
  [:shape  {:filter #{:eq}} [:enum "workday" "off"]]           ; defaulted from the weekday when absent
  [:notes  {:optional true :x-display {:widget "prose"}} [:maybe [:string {:max 2000}]]]]
 :unique [[:member :date]]
 :on-create materialise-blocks                                  ; the ctx :create door, plan.clj's shape
 :owns {:blocks {:kind :block :via :plan_id :on {:close :retire}}}
 :links [{:rel "blocks" :owns :block :embed true :summary "The day's blocks"}]
 :actions
 {:set     {:from #{:drafting} :to :set …}
  :replan  {:from #{:set} :to :drafting
            :guards [blocks-still-ahead] …}                    ; only while something is still ahead
  :reshape {:from #{:drafting :set} :to :drafting
            :input [:map [:shape [:enum "workday" "off"]]]
            :handler rematerialise-ahead …}
  :close   {:from #{:drafting :set} :to :closed
            :touches [{:kind :block :action :retire :may true}] …}}}
```

One date, one member, one shape; `:unique [[:member :date]]` is the
identity, enforced by the index as a 409 (vocabulary § 11). **The shape is
defaulted from the weekday at the create door when absent** — Monday
through Friday `workday`, the weekend `off` — and overridable by naming it,
which is the clock picking the default and the person overriding it.
`?context=` on the feed and the plan's own shape are the two overrides, in
that order of transience (§ *The forks*, d).

**Materialisation** is `:on-create`: for every active `context` whose
`default_shapes` holds the plan's shape, mint one `block` and its spans from
`default_spans` on that date, in `default_order` — the `plan_day` fan-out
one module over, the births deferred to land right after the plan's own
insert so a block's `plan_id` label reads a real parent. **`reshape`** takes
a shape, re-materialises **spans still ahead** from the new shape, keeps
blocks whose context survives into the new shape (with their decisions),
and retires the rest; a span already past is history and is not touched.
**`replan`** returns a set plan to drafting while at least one span is still
ahead — the door the after-lunch notice carries (S7) — and refuses, with the
sentence naming what would open it, once the day is spent. **`close`**
retires the blocks through the `:owns` edge's `:on` map, advertised by
`:touches` with `:may true` (an already-retired block has nothing to do).

### block — one context on this day

```clojure
{:kind :block :plural "blocks" :nav :secondary
 :states [:planned :skipped :done] :initial :planned
 :over {:accomplished #{:done} :let-go #{:skipped}}
 :summary "{data.context_name} · {data.date} · {state}"
 :label-template "{data.context_name} · {data.date}"
 :filterable {:plan_id #{:eq} :context_id #{:eq} :current #{:eq}}
 :fields
 {:at-create  [[:plan_id    (ref-to :day_plan)]
               [:context_id (ref-to :context {:label :context_name
                                              :carry {:seam :context_seam}})]
               [:date       :waymark/date]]
  :while-open [[:stance (prose "The block's stance")]]          ; free text, no launch, no verdict
  :open       #{:planned}
  :facts      [[:current :boolean]]}
 :derived {:current {:count {:owns :span :where {:current true}}}} ; any span current — see the note
 :owns {:spans     {:kind :span     :via :block_id :on {:skip :skip   :retire :retire}}
        :decisions {:kind :decision :via :block_id :on {:skip :skip   :retire :retire}}}
 :links [{:rel "spans" :owns :span :embed true}
         {:rel "decisions" :owns :decision :embed true}]
 :actions
 {:skip   {:from #{:planned} :to :skipped :undo :unskip
           :touches [{:kind :span :action :skip :may true}
                     {:kind :decision :action :skip :may true}] …}
  :unskip {:from #{:skipped} :to :planned :undo :skip …}
  :finish {:from #{:planned} :to :done …}
  :retire {:from #{:planned :skipped} :to :retired …}}}          ; the plan's close/reshape door only
```

A block carries a **stance** — free text, *"heads down, phone off"* — and
nothing else that is a verb: no launch, no verdict. Its `context_name` is
the ref's engine-maintained label copy and its `context_seam` the carried
sentence (`:carry`, vocabulary § 5), so the card reads *your Workday block*
without a join. **`current`** is the fact *any span of mine contains now*.
Recorded honestly: the sketch spells it as a count over the child's own
clock fact, and whether the aggregate grammar folds a child's `:now`-derived
fact live is S3's first question; if it does not, `current` is answered by
the population (one indexed query on `span` by `plan_id` and the two
instants) and the block declares no fact. Either way the **fact is derived,
never stored** — a stored `current` would be a second writer of the clock.

`retire` is the door the plan's `close` and `reshape` cascade through and
is admitted to no hand directly (`(:within ctx)`, vocabulary § 6: *this
door opens for another kind's own handler and for nobody's hand*).

### span — one contiguous window

```clojure
{:kind :span :plural "spans" :nav :secondary
 :states [:planned :done :skipped] :initial :planned
 :over {:accomplished #{:done} :let-go #{:skipped}}
 :summary "{data.starts_at} → {data.ends_at} · {state}"
 :filterable {:plan_id #{:eq} :block_id #{:eq} :current #{:eq} :missed #{:eq}}
 :sortable {:fields [:starts_at] :default "starts_at"}
 :fields
 {:at-create  [[:block_id (ref-to :block {:label :block_label})]
               [:plan_id  (ref-to :day_plan)]                    ; denormalised: the guard is ONE indexed query
               [:starts_at :waymark/instant]
               [:ends_at   :waymark/instant]]
  :open       #{:planned}
  :facts      [[:current :boolean] [:missed :boolean]]}
 :derived
 {:current {:over [:starts_at :ends_at :now]
            :expr '(and (<= (var :starts_at) (var :now)) (< (var :now) (var :ends_at)))}
  :missed  {:over [:ends_at :now :state]
            :expr '(and (< (var :ends_at) (var :now)) (= (var :state) "planned"))}}
 :create-guards [no-overlap-in-plan ends-after-starts]
 :actions
 {:move   {:from #{:planned} :to :planned
           :input [:map [:starts_at :waymark/instant] [:ends_at :waymark/instant]]
           :guards [still-ahead no-overlap-in-plan ends-after-starts] …}
  :swap   {:from #{:planned} :to :planned
           :input [:map [:with_span_id (ref-to :span)]]
           :guards [still-ahead same-plan both-still-ahead]
           :touches [{:kind :span :action :move}] …}              ; exchanges the two windows
  :extend {:from #{:planned} :to :planned
           :input [:map [:ends_at :waymark/instant]]
           :guards [neighbour-keeps-some-width]
           :touches [{:kind :span :action :move :may true}] …}    ; slides ONLY the next span's start
  :split  {:from #{:planned} :to :planned
           :input [:map [:at :waymark/instant]
                        [:gap_minutes {:optional true :default 60} [:maybe :int]]]
           :guards [at-inside-window]
           :touches [{:kind :span :action :create}] …}            ; this span ends at :at; a new one starts after the gap
  :skip   {:from #{:planned} :to :skipped :undo :unskip …}
  :unskip {:from #{:skipped} :to :planned :undo :skip …}
  :finish {:from #{:planned} :to :done …}}}
```

`plan_id` is **denormalised onto the span** on purpose, so the one guard
that matters is one indexed query: **`no-overlap-in-plan`** reads
`(:find ctx) :span {:plan_id … :state "planned"}` and refuses a window that
intersects any other planned span of the plan. Gaps are allowed; overlaps
never; therefore `current` is unique per plan and the feed's *the* current
block is well-defined without a tie-break. Every refusal names what would
make the door available — *"09:00–12:00 overlaps your Shop span (10:00–
11:00); move that one first"* — the house's refusal rule.

The doors, and what each may touch:

- **`move`** takes a new window. Refuses on a span whose window has passed
  (`still-ahead`: `ends_at` is behind `(now)`); refuses an overlap.
- **`swap`** takes another span of the same plan and exchanges the two
  windows. Both must be still ahead. It is the one door whose `:touches`
  names another span's `move` without `:may`, because a swap that moved one
  side would be a lie.
- **`extend`** takes a later `ends_at`. If the new end reaches into the
  next planned span of the plan, that span's **`starts_at`** slides to the
  new end — **only its start, never its end** — and the door **refuses**
  when the slide would leave the neighbour with zero width, saying so:
  *"extending to 13:30 would leave your Shop span no time at all; shorten
  it or skip it first."* This is the one thing the model does on a person's
  behalf.
- **`split`** takes an instant inside the window and an optional gap
  (default the lunch hour, sixty minutes): this span ends at `:at`, and a
  new span of the same block is born through the ctx `:create` door
  starting `:at` + gap and ending at the old `ends_at`. The birth passes
  `no-overlap-in-plan` like any other create.
- **`skip`** / **`unskip`** are an `:undo` pair, the chore-run shape; a
  skipped span is *let go* and stops being a candidate for `current` and
  for the notice.

### decision — the unit of intention

```clojure
{:kind :decision :plural "decisions" :nav :secondary
 :states [:planned :started :done :skipped :changed] :initial :planned
 :over {:accomplished #{:done :changed} :let-go #{:skipped}}
 :summary "{data.text} · {state}"
 :label-template "{data.text}"
 :filterable {:block_id #{:eq} :kind #{:eq :in}}
 :sortable {:fields [:order] :default "order"}
 :fields
 {:at-create  [[:block_id (ref-to :block {:label :block_label})]
               [:kind     (one-of :pick :agenda :prepare :work)]
               [:text     [:string {:min 1 :max 240}]]
               [:subject  {:optional true} [:maybe [:string {:max 200}]]]  ; an ADDRESS, /api/media/<id>
               [:launch   {:optional true}
                          [:maybe [:map [:type [:enum "href" "service" "text"]]
                                        [:href {:optional true} :string]
                                        [:service {:optional true} :string]  ; "light/turn_on"
                                        [:data {:optional true} :map]
                                        [:text {:optional true} :string]]]]
               [:prep     {:optional true} [:maybe [:string {:max 240}]]]
               [:order    :int]]
  :open       #{:planned :started}
  :support    [[:changed_to (prose "What it became")]]}          ; written by change, never by hand
 :create-guards [subject-resolves]                                ; waymark-79f's rule, insight's check reused
 :on-create mint-prep-task                                        ; when :prep is present — see the prep fork
 :actions
 {:start  {:from #{:planned} :to :started
           :handler fire-launch                                   ; HA service when launch.type is :service
           :safety {:idempotent true :reversible false :confirm false
                    :one-way "Starting is the verdict: the record says you went."}
           :display {:label "Go" :style :primary :order 1}}
  :finish {:from #{:started :planned} :to :done :display {:label "Done" :order 2}}
  :skip   {:from #{:planned :started} :to :skipped :undo :reopen :display {:label "Skip" :order 3}}
  :reopen {:from #{:skipped} :to :planned :undo :skip}
  :change {:from #{:planned :started} :to :changed
           :input [:map [:changed_to [:string {:min 1 :max 240}]]] …}}}
```

The four **kinds** are the four things a person means by *I'll do X in
that block*: `pick` (choose one from a queue — tonight's film), `agenda`
(bring this up — the deck estimate with the contractor), `prepare` (get
this ready — the bag by the door), `work` (do this — the porch railing).
`text` is the sentence. **`subject`** is optional and, when present, **an
address that must resolve** — `/api/media/<id>`, `/api/tasks/<id>`,
`/api/threads/<id>` — checked at the create door by the same resolving
guard waymark-79f asks of `cites-what-it-claims`: the shape, the plural,
**and the row**. An address that resolves to nothing is refused naming it.
**`launch`** is what `start` does beyond recording: an `href` the card
opens, a Home Assistant `service` with its `data`, or a `text` the card
shows (*"the drill is in the blue case"*). When `subject` resolves to a
`media` or `task` row and `launch` is absent, the card's launch href is the
subject's `source_ui_href` or `source_href` — projection, not storage.
**`prep`** is one sentence of what must be ready the evening before, and it
becomes a task (below). **`changed_to`** is written by `change` and by no
form: the decision said *this*, the day said *that*, and both are kept.

**`start` is the verdict.** It takes no input, so `demand/effort` renders it
`"assent"` — the class the household calls a tap — and it rides the card
under `spec-feed.md`'s ≤-selection rule without a special case. Its handler
fires the Home Assistant service when `launch.type` is `service`, through
the same client that already calls `/api/services/todo/update_item`; **a
missing HA configuration refuses, never silently no-ops**, because a `start`
that recorded *went* while the lights stayed off would be a record lying
about the room. `:over` reads `done` and `changed` as accomplished and
`skipped` as let go, so the feed's `work-over?` needs no new vocabulary, and
a `started` decision is still work.

## The forks decided, with reasons

### (a) Spans are rows, not a vector on the block

A block's windows could have been `[:windows [:vector [:map …]]]` on the
block row, the meal plan's original embedded `:days`. They are rows because
**every door in this module is span-shaped**: `move`, `swap`, `extend`,
`split`, `skip` each act on one window, and `mealplan10/resources/
plan_day.clj` records the promotion rule that decided this once already —
*"promoted from the plan's embedded :days the moment the day earned its own
machine."* A window that can be skipped, missed and current has a machine.
Rows also make the no-overlap guard one indexed query over `plan_id`, make
`current` a derived fact the maintainer sweeps at the flip boundary
(vocabulary § 3), and make the notice's *once per span start* a row
instant rather than an element index. The cost — a second table, a
denormalised `plan_id` — is the cost `evening_session` already paid for the
same reason.

### (b) A decision is never pinned to a span

The obvious refinement — *finish the estimate, in the morning span* — is
refused. Two reasons. First, **the subject's due-by already says it**: a
task with `due_at` before lunch reads as *morning* on the card without a
second field. Second, and disqualifying: **pinning makes `swap` and `split`
ambiguous**. Swap two spans and a pinned decision either follows its window
(now in the wrong part of the day) or stays with its clock (now on a span
of a different block); split a span and the pin points at half of it. The
block is the unit that survives every span door unchanged, so the block
owns the decisions. Recorded as a punt, not a refusal forever: if a context
ever needs *this, and only before lunch*, the field is `span_id`, optional,
and the doors above must say what they do with it.

### (c) `extend` slides the neighbour's START only, and refuses at zero

`extend` could have refused every collision (pure, but useless — the one
thing a person wants at 12:05 is to keep going), or slid the neighbour
whole (its end too — which slides the neighbour's neighbour, and the day's
end, and is the model rearranging a day nobody asked it to). It slides
**only the next span's `starts_at`**, never its `ends_at`, so the change is
bounded to one row and one field and the rest of the day stands exactly as
planned. And it **refuses when the slide would squeeze the neighbour to
nothing**: a span of zero width is a span the person did not skip and
cannot see, which is the worst of both. The refusal names the neighbour and
the two doors that would clear it (shorten it, or skip it). *This is the
ONE thing this model does on the person's behalf; keep it to exactly that.*

### (d) Explicit contexts; the clock picks the default; the person overrides

Contexts could be inferred — from the calendar's busy blocks, from
Location, from what the person opened last week. They are **explicit rows
a person authors**, and the day's shape is the only thing the clock decides
(weekday → `workday`, weekend → `off`), overridable at the plan's create
door, by `reshape`, and transiently by `?context=` on the feed for one
read. The reason is the whole posture of this house: *the pool is
declared; every source is intentional* (law 1) and *the order is data a
person can read* (law 5). An inferred context is a model of the person,
and a card that says *your Shop block* because the system guessed is a
card whose why cannot be cited. Inference is a recorded punt below, and
the seam for it is the shape default — one function — so adding it later
moves nothing else.

### (e) Prep is a task, not a kind

A `prepare` decision's `prep` sentence names something that must be ready
**the evening before**, and the evening before is another day, another
block, and very often another person. The household already has one place
where *what must be done by when* lives and is read by whoever holds it:
the `task` queue. So the decision's create handler mints a task due the
evening before the block's date, `source "day_plan"`, `source_href` back to
the decision's own address, and skipping or retiring the decision retires
the task — **the meal plan's thaw-task pattern**, where a meal's
`thaw_hours` becomes a `prep_task` the day before dinner and that row
reaches the queue. A fifth kind (`preparation`) would be a task with a
smaller vocabulary and a second screen to check.

**Recorded tension, left for S4 to resolve with the owner.** The precedent
is more particular than the epic's sentence. `task` is a **mirror** kind
(`workqueue10/resources/task.clj`): its `:source` enum is `["chore" "meal"
"todo" "gtasks"]`, its create door admits only the two pocket authorities
(`"todo"`, `"gtasks"`) and **pushes the birth** to that authority
(`:create-push`), and *"the waymark engines' rows are born of their own
law"* — a prep task is a native `prep_task` row that the queue **drinks**
through `workqueue10/sources/mealplan.clj`, a `TaskSource` over the
authority's own collection. A handler calling `task`'s create door with
`source "day_plan"` is refused by the enum and would push to nobody. The
pattern, followed literally, is therefore **a `TaskSource` over decisions
whose `prep` is set** (`decisions?kind=prepare&state=planned,started`,
`prep->task` folding `prep` into the title, due the evening before,
`source_href` the decision's address), registered in `main.clj`'s source
map under `"day_plan"` with the enum widened by one word — and the queue
mirrors it in on the discovery beat, the way it mirrors thaw tasks. That
is *prep is a task* with the mechanism the house actually has; whether S4
takes it or grows a native birth door is the slice's first decision, and
this spec records the choice rather than making it.

### (f) Block start is an addressed notice, never the feed pushing

*"When the span starts, tell me"* is a push, and `spec-feed.md` is
unambiguous twice over: *the feed is not the notifier*, and laws v3 added
*no badge, no unread state, and no push*. So block start is
`spec-addressed-notice.md`'s job — a notice addressed to the plan's
`member` through the ref, fired at the span's `starts_at`, carrying the
block's name, its stance and its decisions with their launches. It is the
person's own alarm going off, not the feed poking. It is also that spec's
**first per-row-instant trigger** (its `:when` matches transitions today),
which is why S7 is P3 and lands after a week of living with the card alone.
Skipping the span cancels the notice; a preview reader receives none.

### (g) The feed falls back to the shape's defaults when no plan exists

A day nobody planned still has a shape. So the `current_block` population,
finding no `day_plan` for the reader and today, answers from the **active
contexts whose `default_shapes` hold the weekday's shape**, at their
`default_spans` on today's date — the block the plan *would* have
materialised — with no decisions, and a single card offering `day_plan`'s
create door: **plan today**. The alternative, an empty top section, is a
surface that lies once on every unplanned morning. Nothing is written: the
fallback is a read over `context` rows, and the card's verb is the ordinary
create door of an ordinary kind.

### (h) Nothing from laws v3 applies inside a block

The current block's decisions are a handful of candidates with an `order`
the person wrote and, through the subject, a deadline. There is nothing to
be fair about: **no cooling step, no exposure floor, no rank, no diagnosis
duty.** The population hands the mixer candidates in `order` with a
`:lane` of their index, the section sits above `:outcomes` in the census,
and `contested-sections` (`feed.clj:352`) does not name it — so the
formula cannot touch it and no recipe field can put it in the contest.
What DOES carry over, verbatim, and every card must satisfy: *the pool is
declared* (a decision is a row a person or their delegate wrote), *the
order is data a person can read* (`order`), *the GET writes nothing*,
*every card is grant-projected*, *every card cites why* (`your Workday
block, decided <when>`), and *the person spins* — there is no auto-refresh
at the block boundary; the next read answers the next block.

## The feed: one population, one line

```clojure
;; feed.clj populations — one entry, beside :outcomes
:current_block current-block

;; workqueue10.main/feed-recipe — one line at the TOP
{:section :now :population :current_block :take 6
 :says "Now: the block you are in, its decisions in the order you set them. Go is the verdict."}
```

`current-block` is `(fn [ctx])` like every other population. It reads the
reader's member row (`members/spellings-of`, the letters population's own
move), today's `day_plan` for that member, the block whose span is
`current`, and its decisions not yet over, in `order`, each carrying `start`
as the card verb (rendered `assent`, so it survives the ≤-selection
partition) and a `:sentence` of *your <context_name> block, decided <at>*.
The block's `stance` rides the section's sentence, the way `crown-and-floor`
rides the outcomes section. `:now` joins `census` at the front —
`[:now :outcomes :do_now …]` — which widens `feed_recipe`'s `:section` enum
for free and moves no fingerprint, exactly as `:outcomes` did
(waymark-jfv.4). With no plan the population answers fork (g)'s single
card. The document below `:now` is byte-identical to before this slice,
and the pack obligation says so.

### The day boundary

`feed/today` reads `(:zone recipe "UTC")` and the household never sets it
(waymark-rptq): the day rolls at 18:00 Mountain, and a `current_block`
population that read *today* six hours early would answer tomorrow's plan
at dinner. S5 sets `:zone` at the app's build site from the environment
beside `WORKQUEUE10_HA_ZONE` (or one new `WORKQUEUE10_ZONE`) and says so in
the recipe's docstring. dayplan10's materialisation needs the **same zone**
to turn `default_spans`' local times into instants, so the zone is read once
in `main.clj` and handed to both — one household, one clock.

## The planning chat

Claude works the MCP. There is nothing new at the MCP surface: the plan and
its decisions are ordinary kinds with ordinary doors, and the fixed tool
list does not grow — an agent reaching for tomorrow's plan wears
`waymark_discover` / `waymark_schema` / `waymark_query` / `waymark_get` /
`waymark_invoke` exactly as the compiler contract in `spec-feed.md` said it
would. (The epic says *the six tools stay six*; `spec-mcp-surface.md`
§ *The seventh tool* records that `waymark_resolve` joined on 2026-09-03.
The sentence's force is unchanged — **no tool for this module** — and the
count is seven.)

**What the grant admits.** The agent's grant — the connector door's
delegate (`spec-connector-door.md` § 3), the standing agent's leash, and
the sitting's scope — names: `day_plan` create, `set`, `reshape`; `block`
create, `skip`; `span` `move`, `swap`, `extend`, `split`; `decision`
create, `change`; and **read** on `media`, `task`, `plan_day`, `thread`,
`event`, so the chat can see tomorrow's calendar, meal, due tasks, media
queue and open threads before it proposes anything. **`start`, `finish`
and `skip` on a decision stay the person's**: the verdict is a person's
word, and the guards spell it with `g/unless-granted` naming the kind's own
sentence (vocabulary § 6, waymark-sfe), so a future household that wants to
grant it can, and this one does not.

**The duty, in one paragraph, in two places.** `SITTING.md`'s clerk work
orders and `.claude/skills/sitting/SKILL.md` each gain one duty: **plan
tomorrow** — when a `composition_request` or a person's turn asks for it,
read tomorrow's calendar, meal, due tasks, media queue and open threads;
propose one decision per block; write only what the person accepted. A
decision the chat proposes and the person declines is not written; there
is no draft state on `decision` because the chat IS the draft. The four
eyes wall `insight` carries (`:decider {:not {:field :authored_by}}`) has
no analogue here because the decision has no verdict door an agent could
reach: the only doors it holds are create and change.

## Landing order

```
.1 (this spec)
.2 ──▶ .3 ──▶ .4 ──▶ .5 ──▶ .7   (after a week with .5, and only if needed)
               └───▶ .6
```

**`.2` first: context and day_plan, shape-materialised.** It creates the
module (`dayplan10/main.clj`, the `in-domain :day` enrolment in
`workqueue10.main`, the migration plan printing five new tables), the two
authored kinds, the weekday default at the create door, and
`materialise-blocks` — which needs `block` and `span` to compile, so `.2`
and `.3` land together if the handler demands it, each declaration in its
own file. A conformance test walks both kinds; creating a workday plan
mints the workday shape's blocks and spans; `reshape` to `off` replaces only
spans still ahead.

**`.3` second: block and span, moved and adjusted.** The five span doors
and their guards, `current` and `missed`, `no-overlap-in-plan` as one
indexed query. `defscenario` proves: an overlap is refused; `move` on a past
span is refused; `swap` exchanges two windows; `extend` slides the
neighbour's start and refuses at zero width; `split` yields two spans of one
block; `current` is unique across a plan.

**`.4` third: decision.** The kind, `subject-resolves` (waymark-79f's fix
reused — **if 79f has not landed, `.4` lands it in `cites-what-it-claims`
first and reuses it, never a second address checker**), `fire-launch` over
a now-public `service-call!`, and the prep fork's mechanism decided.
Scenarios: an unresolvable subject refused naming the address; `start` on
a `:service` launch calls the HA client exactly once and records `started`;
a decision with `prep` yields one task with `source "day_plan"`; `change`
records `changed_to` and lands in `:changed`.

**`.5` after `.4`: the feed reads the current block.** One population, one
recipe line, `:now` in the census, the zone fix (waymark-rptq folded in).
Pack obligation: with a set plan the top section is the current block's
decisions with `start` verbs; with no plan the top card is *plan today*;
the document is byte-identical below `:now`; `today()` rolls at local
midnight under a fixed now-fn.

**`.6` after `.4`, beside `.5`: the grant.** The scope names the dayplan10
kinds and doors; `waymark_discover` shows them to the delegate; a `dry_run`
of `decision` create through `waymark_invoke` succeeds; `SITTING.md` and the
skill carry the duty in one paragraph each. It touches no file `.5` touches.

**`.7` last, and conditionally: block start as an addressed notice.** Land
after a week of living with `.5`, and only if the block card alone proves
not to be enough. It also depends on `spec-addressed-notice.md` being
built, which it is not; if the card suffices, `.7` closes unbuilt.

### What cannot run concurrently, and why

| pair | why not |
|---|---|
| `.2` ∥ `.3` | `materialise-blocks` births `block` and `span` rows; the handler and the kinds it births are one compile. Land together or `.2` then `.3`. |
| `.3` ∥ `.4` | `decision`'s `block_id` ref and the block's `:owns {:decisions …}` cascade are two halves of one edge. |
| `.4` ∥ `.5` | `.5` renders `decision` cards with `start` as the verb; a field or door moving under it is rework. |
| `.5` ∥ `.7` | `.7` reads the same span instants `.5`'s population reads and must not learn a different day boundary. |

**`.5` ∥ `.6` is the one honest concurrency**: a population and a recipe
line in one pair of files, a scope and two prose paragraphs in another.

## Recorded punts

- **A task size field.** *Trim the decisions by the gap to the next
  event* wants a `minutes` on the decision. Add it only when a context
  wants to trim by the gap — the calendar's `event` kind is already a row
  the population could read — and never as a ranking input.
- **Inferred contexts.** Explicit first; the clock picks the shape; the
  person overrides. The seam is the one shape-default function at the
  create door. An inferred context is a model of the person and its card
  could not cite why.
- **Span-pinned decisions.** Fork (b). The field would be `span_id`,
  optional, and `swap`/`split` would have to say what they do with it.
- **The agent invoking `start`.** The verdict stays the person's. The wall
  is `g/unless-granted`, so a household that wants a delegate to *go* on
  its behalf grants `decision.start` by name and nothing else moves; this
  house does not, and the sitting's scope names none of the three verdict
  doors.
- **A context's own recipe.** `feed_recipe_id` is declared and read by
  nothing. A block that wants its own populations under its decisions —
  the shop block reading the media queue — is a later bead over a ref that
  already exists.
- **Materialisation re-reading a template into an existing day.** Never:
  the day's blocks are the truth. A retired or revised context changes
  tomorrow's plan and no plan that exists.
- **The evening-plan sessions consumer.** `eveningplan10/consumers.clj`
  predates the ctx birth door and its docstring's claim about `:on-create`
  is stale. Not this epic's to fix; recorded so nobody copies it.
- **HA service discovery.** `launch.service` is a string the person or the
  chat spells; nothing reads HA's service registry to offer a picker. A
  wrong service refuses at `start` with HA's own status.

## Effort

**Medium, and front-loaded.** `.2` and `.3` are the whole design cost: five
declarations, one `:on-create` fan-out in the meal plan's shape, and the
five span doors with guards that must refuse in sentences — a day or two,
most of it scenarios. `.4` is one declaration, one resolving guard the
house already owes (waymark-79f), one public function in
`homeassistant.clj`, and the prep mechanism, which is the only place a
choice is still open. `.5` is hours once the kinds exist: a population in
the registry's own shape, one census member, one recipe line, one env read.
`.6` is a scope and two paragraphs. `.7` waits on a spec that is not built
and on a week that has not happened.

The genuinely new thought is three sentences long: **spans are rows and
decisions are the block's; extend slides one start and refuses at zero;
the block above the crown is outside the contest.** Everything else is
`evening_plan`'s edge, `plan_day`'s birth, `prep_task`'s clock,
`chore_run`'s two endings, `insight`'s address and `homeassistant`'s POST,
instantiated once more. The risk is not the code — it is that a plan gets
written on Sunday night and nobody opens Monday's card, which is why the
verdicts themselves (`started`/`done` against `skipped`/`missed`) are the
success metric beside the feed's origin key, a person's own word and never
engagement analytics.
