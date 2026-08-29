# Spec — the outcome menu: values declared, plans composed, friction pre-paid

**Thesis.** The owner's own words, 2026-08-25, and everything below is
downstream of them:

> Use AI to create a menu of outcomes aligned with my values, and reduce the
> friction of reaching them by aligning the actions with the activities I love.

The claim underneath it is sharper than a feature request, and it is the one
this spec is built on: **time follows the friction gradient across values, not
the values themselves.** Building with AI is low-friction and wins the evening;
family memories are valued *more* and cost more to start. A feed that merely
shows the house what is true will lose that contest every night, because the
truth is that the cheap thing is cheap. Waymark's job is **arbitrage** — spend
the low-friction loved activity on lowering the friction of the high-friction
valued one. A plan that says *make a memory with Jack* is a slogan; a plan that
says *the finger-joint box, Saturday 2pm, stock already cut, and it uses the
shop because you declared you love the shop* has moved the gradient.

Three kinds of thing carry that, and they are the epic's own list: a **value**
the owner declares (law, amended only by him, petitioned by evidence); a
**plan** the composer stages (a bundle of pieces with the friction pre-paid,
each piece one tap to accept or refuse); and the **outcomes section**, the
feed's crown — *This week could hold* — capped hard, on top, and audited
against the projections beneath it.

## Epistemic status

**The modest claim first: this epic invents no mechanism.** Every part of it
has a landed precedent, and most of them landed inside the last two days.

- A row an agent stages and a person's tap applies, through the target's own
  door, as the person: `recipe_proposal` (waymark-0k4, `spec-feed.md`).
- A one-tap verdict that only ever offers, with a four-eyes wall making
  *"the composer cannot accept its own proposal"* structural: `insight`.
- A citation wall at a create door, naming every offender: `cites-what-it-claims`.
- A door that refuses an agent by kind of principal and **names the lawful
  path** instead: `feed_recipe/written-by-a-person`.
- A read-side section that names optional application kinds and answers `[]`
  when the engine holds none: `ticklers`, `insights`, `letters`, `proposals`.
- A card that says something its row cannot say about itself: `sentence`
  (waymark-iqa.5), and a card that cites why it is here: `why` (waymark-iqa.29).

What is genuinely new is exactly three things: a **declared value** (the first
row in this tree whose content is what a person cares about rather than what
the house has to do), a **parent/child consent shape** (a bundle whose pieces
are each their own tappable row), and a **section above do-now**.

**What deserves suspicion, named so it is not discovered later.** This is the
most product-shaped epic in the tree, and it is the one where an engine grows a
recommender. Three walls, and they are not negotiable:

1. **Nothing here ranks two cards against each other.** The outcomes section is
   ordered by `hash(seed ‖ card_id)` like every other section, and its cap is a
   refusal at a create door, never a filter in a query. The third feed law
   stands; when the contest of `waymark-8um` lands, this section is a *floored*
   line under it and never an exempt one.
2. **The composer only proposes.** Every verdict on every row this epic mints
   is walled against the principal that staged it *and* against agents in
   general. There is no path by which a composed plan becomes a household's
   Saturday without a person's tap, and there is no path by which an agent
   edits a value.
3. **A plan is authored interpretation and says so.** It sits above the ledger,
   not inside it. The sections below it stay projections of rows nobody wrote
   for effect, which is precisely what makes them a fair audit of the crown.

The second suspicion is the honest one: **this epic can be built perfectly and
still fail**, because whether the house's Saturday actually holds the box is
not a property of any door. The proof is jfv.5 — two real plans, from real
data, in front of the real owner, at least one accepted and materialized — and
the spec is written so that bead is reachable in a week rather than a quarter.

## What exists

**`:decision` (`resource.clj:1253-1352`)** — nine keys projecting states,
verdict actions, walls, schema, create model, queue filters and `:own-surface`.
Four instances (`approval_request`, `permission_slip`, `tickler`, `insight`)
and one recorded **non**-instance (`recipe_proposal`). Its limits are what
shape this epic, and three of them are load-bearing here:

- **A verdict cannot carry a structured payload.** `verdict-action:1179` gates
  `:input` on `note-field`, so a verdict spelling `:input` without `:note` has
  it silently dropped. The entire payload surface of a verdict transition is
  one optional `[:maybe [:string {:max 240}]]`.
- **A verdict cannot declare `:touches`.** `verdict-keys` is
  `#{:name :to :label :style :order :note :guards :handler :safety :display
  :edit :input}` — no `:touches`, no `:effects`. A verdict whose handler
  cross-writes cannot advertise its blast radius, and `checks_assembly/
  check-touches` therefore cannot verify it.
- **`:decider` has no principal-type dimension.** `{:not …}`, `{:field …}`,
  `{:role …}`, `:anyone`. It cannot say *a person, never an agent*.

`recipe_proposal` declines the sugar for a fourth limit (*"a decision kind that
needs an extra birth stamp has no spelling yet"*) while keeping
`g/not-the-field` — *the law is the sugar's law and not a lookalike.* That is
the posture both kinds below inherit.

**`demand.clj`** — `assent < selection < recall < composition`, and the mapping
is stricter than it reads. `field-class` never consults `:x-options`,
`x-vocab`, `:maxLength` or `:required`. A closed `:enum`, a `:const`, a
boolean, a `:kind` ref (`x-ref` / `format "waymark-ref"`), a `"resource"`
widget, or a field a guard's `:accepts` set closes → **selection**. A plain
string, a number, an **array** (`[:vector [:enum …]]` included — `:items` is
never inspected), an object → **recall**. `feed/card-ceiling` is `"selection"`,
so anything at `recall` or above leaves the card for `heavier`.

**`feed.clj`** — `census [:do_now :decide :fuel :seam :archive]` (a literal,
and the `:section` enum of `feed_recipe/order-schema` is generated from it);
`populations`, a closed map of twelve `(fn [ctx])`s answering **candidates**
(`{:kind :id}`, optionally `:row`, `:at`, `:sentence`, `:lane`); `check-recipe!`'s
four assembly checks; the mixer, whose claim is **total** (every candidate a
population *names* is claimed for the day, shown or not); `day-start` (every
window anchors here, never on `(:now ctx)`); `rank = hash(seed ‖ card_id)`, the
only ordering input anywhere.

**`recipe_proposal/apply-the-order`** — the multi-door precedent at N = 1:
`ctx :invoke` and `ctx :create` carry the **outer** principal, run in the outer
transaction, and hand the target its own etag (`:if-match`). Its three recorded
decisions are inherited whole below: one transaction, no deterministic inner
key, the fence supplied rather than waived.

**`grants/approval-effects!`** — the *other* apply posture, and it is declined
here for the reasons 0k4 already gave: post-commit at the wire boundary, under
a **system** actor, warning to `*err*` when its effect refuses. Its
deterministic-key convention (`"approval-mint-" + ask-id`, and a `grant_id`
stamped at the verdict as a pure function of the ask's id) is what an
out-of-transaction effect needs and is exactly what an in-transaction verdict
door does not.

**`insight`'s three create walls** — `cites-what-it-claims` (evidence as
`/api/<plural>/<id>` addresses, every offender named, `(nil? rdef-of) → allow`
for the storage-free probe), `offers-something-light` (the ≤-selection rule at
a **door**, the only such place in the tree), `insights-are-capped` (three a
day, per author, calendar day, counting rows because a refused create must
spend nothing). Shape first, pace last.

**The reference design** is the artifact *The Feed, Composed* named in the
epic: an outcomes section titled **This week could hold**, above everything;
plan cards carrying the value they serve, the routing rule cited, the evidence
read, the pieces with their friction already paid, and **Make it so** /
**Not this week** chips.

## The forks, decided

| fork | verdict | one-line reason |
|---|---|---|
| value shape | a `value` kind with free-word `loved` activities, `scope` + engine-stamped `owner`, `:nav :secondary` | the vocabulary of loved things *is* the declaration; a second kind to hold a list of words would be a noun for a noun's sake |
| amendment | an agent publishes an **`insight`** offering the value's one-tap `still_stands`; the owner's own `revise` is the ratification | an insight's offer must be ≤ selection, and no door that takes the new wording can be — so the tap is *"I have read this"* and the amendment is the owner's form |
| verdict structure | **per-piece child rows**, each with its own note-free verdict; the bundle is the parent | a selection-carrying verdict is not merely heavy, it is *inexpressible*: the sugar drops a verdict's `:input`, and a hand-written `[:vector [:enum …]]` renders `recall` and falls off the card |
| materialization | N taps or one fan-out, all in-transaction under the accepting member, target kinds a declared **enum**, `:touches` advertising the union | it converts iqa.6's refused primitive (a cross-write whose target is free data) into the declarable kind `ingredient/absorb-duplicate` already is |
| the weekly cap | a **create-door** wall, two plans per author per calendar week; `:take` on the recipe line is the read-side floor | the insight cap's own reasoning — a wall at the source makes the composer rank; a filter in the population would bury what it already let be staged |
| framework vs app | `value`, `plan`, `plan_piece` are **workqueue10** kinds; the census entry, the population and the card's `pieces` key are **waymark10** | tickler and insight decided this exactly once already, and the feed already names optional app kinds by keyword |

## The design

### The value kind — declared law, petitioned by evidence

`workqueue10/src/workqueue10/resources/value.clj`, beside `tickler` and
`insight` and domainless for the same family reason: what the house *cares
about* is not a domain of logistics beside queue/chores/meals.

```clojure
{:kind :value :plural "values"
 :nav :secondary                     ; ← load-bearing, see below
 :states [:declared :retired] :initial :declared :terminal #{}
 :summary "{data.name} · {data.scope} · {state}"
 :schema [:map
          [:name    …  [:string {:min 1 :max 80}]]   ; the owner's own words
          [:says    …  prose]                        ; why it matters, in a paragraph
          [:loved   …  [:maybe [:vector [:string {:min 1 :max 40}]]]]
          [:scope   …  [:enum "household" "mine"]]
          [:owner   …  [:maybe [:string {:max 128}]]] ; ENGINE-stamped
          [:reviewed_at … ] [:reviewed_by … ]]        ; ENGINE-stamped
 :create-schema  …                                    ; name, says, loved, scope
 :on-create stamp-owner
 :create-guards [written-by-a-person]
 :actions {:revise       {… :edit {:prefill [:name :says :loved]}
                          :guards [written-by-a-person this-is-yours-to-declare]}
           :still_stands {:from #{:declared} :to :declared :handler stamp-review
                          :guards [written-by-a-person]}
           :retire  {… :undo :restore :guards [written-by-a-person this-is-yours-to-declare]}
           :restore {… :undo :retire  :guards [written-by-a-person this-is-yours-to-declare]}}}
```

**`:nav :secondary`, and it is not cosmetic.** `next_actions` claims the open
rows of every `:nav :primary` kind, and `fuel` speaks only about `:nav
:primary` kinds. A value is permanently "open" by construction, so a
`:primary` value would card in do-now forever and a retired value would be
congratulated as a deed. `feed_recipe` is `:secondary` for the same reason and
is the precedent to cite. (The `:decision` sugar hands `:nav :system` out for
free; these kinds are hand-written and inherit nothing.)

**`loved` is free words, and that set IS the vocabulary.** The alternatives
were weighed: linking to eveningplan10's `activity` kind (borrowing another
domain's nouns for a word like *woodworking*), or minting a `loved_activity`
kind (a row per word). Free words on the value win because the household's own
sentence — *I love the shop, I love building with the boys, I love cooking with
a podcast on* — is the declaration, and a value with no loved activity beside
it is precisely the high-friction value the thesis is about. What the words buy
downstream is a **checkable citation**: a plan's `routes_through`, when it
names one, must match a word some active value declares, and the refusal
narrates the legal words. The picker cannot be published — a row-backed
`x-options` source does not exist (**waymark-90k**) — so v1 is a guard and a
good sentence, which is this tree's usual first answer anyway.

**Scope is `feed_recipe`'s, verbatim.** `{"household", "mine"}` with an
engine-stamped `owner`, and the wall that matters is the stamp rather than
concealment: `this-is-yours-to-declare` refuses a `"mine"` value to anyone but
its owner, and a `"household"` value is any adult's to write — the deviation
4yn already recorded (*household kinds are shared among humans; the wall that
matters is the owner stamp, not own-surface*). A second pair of eyes on a
household value is **waymark-pcr**'s question and is not re-decided here.

**The amendment flow, and the constraint that decides it.** The epic asks for
learned signals to *petition* an amendment that only the owner ratifies. The
existing door for *an agent says something with evidence and offers a next
step* is the `insight`, and its create wall `offers-something-light` refuses
any offered action heavier than `selection`. Every door that could take a new
wording takes a string, and a string is `recall`. **So no petition can offer
the amendment itself.** Two honest shapes remain and one of them exists:

- **v1, built:** the petition is an `insight` — the finding in one sentence,
  `evidence` naming the rows it read (the transition log's own addresses; view
  events once **waymark-8um.1** lands), and its offer is the value's
  `still_stands` door: no input, `assent`, one tap, stamping `reviewed_by` and
  `reviewed_at` on a self-loop. The feed screen already renders both halves —
  the offered action as the primary chip and the row's screen as the offer link
  — so the owner taps *these still stand* or opens the value and revises. The
  ratification is the owner's own `revise`, and **the value's transitions are
  the record**: who amended, when, from what, under which law revision.
- **The punt, named:** a one-tap ratification of an *exact proposed wording*
  wants 0k4's staged proposal generalized past `feed_recipe`. That is the other
  half of **waymark-xw3** and it is filed rather than built, because
  generalizing a staged apply is a bead with its own security review and this
  epic already spends its cross-write budget on the plan.

`still_stands` is not a hollow tap and the reason is worth stating: an
unreviewed petition and a reviewed-but-unchanged declaration are different
facts, and the second one is the composer's most useful signal — *the owner
read the evidence and the value stands anyway*, which means the friction is in
the plan and not in the declaration. That is `waymark-8um` law 4's raw material.

### The plan and its pieces — honest consent, one tap at a time

Two kinds, and the split is the epic's consent-granularity centerpiece.

```clojure
;; the parent — the bundle
{:kind :plan :plural "plans" :nav :system
 :states [:offered :accepted :declined :expired] :initial :offered
 :terminal #{:accepted :declined :expired}
 :schema [:map
          [:goal …]            ; the outcome in VALUE terms, one sentence
          [:value_id {:kind :value} …]
          [:routing …]         ; the citation, in household words
          [:routes_through …]  ; optional: a loved activity some value declares
          [:evidence …]        ; /api/<plural>/<id> addresses, the insight's shape
          [:composed_by …] [:decided_by …] [:good_until …]]  ; engine-written
 :on-create stage-the-plan                       ; composer stamp + leash
 :create-guards [names-a-value cites-what-it-read routes-through-something-loved
                 a-bundle-is-small plans-are-few]
 :actions
 {:make_it_so    {:from #{:offered} :to :accepted
                  :guards [the-composer-does-not-decide a-person-answers
                           the-leash-has-not-run-out]
                  :handler take-the-rest
                  :touches [{:kind :plan_piece :action :take}]}
  :not_this_week {:from #{:offered} :to :declined
                  :guards [the-composer-does-not-decide a-person-answers]
                  :handler moot-the-rest
                  :touches [{:kind :plan_piece :action :moot}]}
  :expire        {:from #{:offered} :to :expired :guards [the-leash-has-run-out]}}}

;; the child — one piece, one tap
{:kind :plan_piece :plural "plan_pieces" :nav :system
 :states [:offered :taken :declined :moot] :initial :offered
 :terminal #{:taken :declined :moot}
 :schema [:map
          [:plan_id {:kind :plan} …]
          [:says …]                 ; what this piece IS, in the house's words
          [:target_kind  (into [:enum] materializable)]   ; ← a DECLARED set
          [:prepared     …]         ; the input the door will take
          [:materialized …]         ; engine-written: the address that landed
          [:composed_by …] [:decided_by …]]
 :create-guards [the-prepared-input-fits-the-door the-plan-is-still-offered
                 a-bundle-is-small]
 :actions
 {:take     {:from #{:offered} :to :taken :handler materialize
             :guards [the-composer-does-not-decide a-person-answers]
             :touches [{:kind :task :action :create} …]}   ; the union, literal
  :not_this {:from #{:offered} :to :declined
             :guards [the-composer-does-not-decide a-person-answers]}
  :moot     {:from #{:offered} :to :moot
             :guards [a-person-answers]}}}
```

**Why the `:decision` sugar is declined for both.** Not taste — three of its
recorded limits, all of them fatal here, and the fourth (`:on-create`, "one
home per hook") on top: a verdict cannot declare `:touches`, and both accepts
cross-write; `:decider` cannot say *a person, never an agent*, and that wall is
half of what makes the composer safe; and the sugar's single open state cannot
express a machine with a `moot` arm. As `recipe_proposal` did, **the walls are
still the sugar's guards** — `g/not-the-field :composed_by` is the very guard
`desugar-decision` would have minted — so the law is the sugar's law and not a
lookalike. That the sugar cannot spell a cross-writing verdict is filed
(**waymark-bro**) rather than fixed inside this epic.

**Why per-piece rows, decided against a selection-carrying verdict.** The
question was whether one verdict on the parent could carry *which pieces*. It
cannot, and the finding is structural rather than aesthetic:

1. **The sugar drops it.** A verdict's `:input` is read only when `:note` is
   spelled, so `accept {pieces: […]}` does not exist in the spelling the other
   three instances use.
2. **Hand-written, it still falls off the card.** `demand/field-class` never
   inspects an array's `:items`, so `[:vector [:enum …]]` is `recall`, heavier
   than `card-ceiling`, and `split-verbs` moves the verdict into `heavier` — a
   link to a screen. waymark-iqa.4 found this with a note and both `insight`
   and `recipe_proposal` inherited it; this is the same finding one shape over.
3. **A single `:kind` ref would render `selection`** — so *decline one named
   piece* is technically expressible on the parent — but an action with an
   input is not a tap: `.7` opens `actionDialog` for it. The epic's unit of
   consent is a thumb, and a picker is not a thumb.

So each piece is a row, each verdict is note-free and input-free, and the
bundle's coherence lives in the parent — which is exactly where the epic put it
(*bundle size tracks real-world coherence: a Saturday afternoon, not a whole
week*). `a-bundle-is-small` refuses fewer than two pieces (*"a plan with one
piece is a finding — publish an insight"*) and more than five (*"that is a
week, not an afternoon"*).

**This is the opposite of 0k4's one-kind decision, and the contrast is the
reason.** `recipe_proposal` refused a parent/parts pair because the four
assembly checks are properties of a **whole** recipe and a split would have had
to re-assemble the set at every door of both kinds. A plan is the mirror image:
its pieces are independently materializable, independently refusable, and the
epic's whole demand is that a decline **name which one**. Where a recipe needed
one atomic judgment, a plan needs N addressable ones.

**What a partial accept is.** Decline the pieces you do not want — one tap each
— then **Make it so**, which accepts *the pieces still offered*. The reverse
order works identically. There is no third verb and no partial-accept payload:
the partial-ness is the state of the piece rows at the moment of the tap.

**What a decline teaches, and the distinction that makes it teach.** Two
refusals mean different things and must stay different:

- `not_this` on a **piece** says *this part was wrong* — the composition failed.
- `not_this_week` on the **plan** says *the week was wrong* — the timing failed,
  and the parent's handler moves every still-offered piece to `moot` rather
  than to `declined`, so the composer never reads a timing refusal as a
  verdict on a piece it should stop proposing.

That is the whole of the decline signal, and it is rows and transitions rather
than prose: who answered, when, which piece, which way. The composer reads it
through its own `:own-surface {:by :composed_by}` sight of the plans it staged.
**A declined plan does not resurface as itself** — the tickler's self-loop was
right for a marker and is wrong here, because a recomposed plan has different
pieces and a self-loop would make the row lie about what it offers.
Recomposition is a **new plan** citing `supersedes`, and the backoff is the
tickler's posture spelled as a floor: the decline stamps `not_before` (the
`backoff-days [7 21 60 180]` schedule, the same pure function with `now` handed
in), and `plans-are-few` refuses a superseding plan before it. Half a year
forever, because the only honest way to stop hearing about an outcome is for
the owner to retire the value it serves. *(The refusal half is superseded:
since waymark-1uv.10 the date is the crown rank's `:early` input and the
recomposition is admitted and cooled — § *Built — 1uv.3 and 1uv.10*.)*

### Materialization — the tap is the write, N times

```clojure
(defhandler materialize [row _inp ctx]
  (let [k   (keyword (get-in row [:data :target_kind]))
        res ((:create ctx) k (prepared-input row))]
    (assoc-in row [:data :materialized]
              (str "/api/" (plural-of ctx k) "/" (get-in res [:row :id])))))
```

**The principal is the accepting member.** `invoke/make-ctx` hands `ctx :invoke`
and `ctx :create` the outer principal (the waymark-iqa.6 finding, confirmed by
0k4), so the task, the event, the list that lands carries the **member's** name
on its create transition and is judged by the target kind's own guards as that
member. Nothing acts as a system actor and nothing is minted post-commit.

**The target is not free data, and this is the answer to the primitive
iqa.6 refused.** `insight`'s offer is an *address* rather than a trigger, and
the reason recorded there is exact: *"an insight's target is data chosen by its
author — the one cross-write no declaration could name, no `:touches` could
advertise, and no grant would re-gate"*, so a leashed compiler could have
proposed `grant.extend` on its own leash. A plan piece closes every clause of
that sentence:

- `target_kind` is an **`:enum`** the application declares — the household's
  *work* kinds (`task`, `chore_run`, `event`, `grocery_list`, `media`, …) and
  nothing governance-shaped. `grant`, `capability`, `approval_request`,
  `member`, `role`, `feed_recipe`, `recipe_proposal`, `value`, `plan` and
  `plan_piece` are not in it, by construction rather than by a blocklist.
- `:touches` names the union literally, so `checks_assembly/check-touches`
  verifies it at assembly and `render` puts it on the wire. Only the *input* is
  data — the `ingredient/absorb-duplicate` shape, one notch wider (the kind
  varies within a declared set).
- `target_action` is **`:create` in v1.** A piece makes a new row; it does not
  move an existing one. The single-row *revise* case is precisely what 0k4
  already built, and generalizing a staged revise to arbitrary targets is its
  own bead. Recorded as a punt with its cost: *"reopen the abandoned show"*
  cannot be a piece today, only a task that says so.

**Atomicity: per tap, and the taps are per piece.** A piece's `take` is one
transaction: the piece moves and the target row lands, or neither does. The
parent's `make_it_so` fans out through `ctx :invoke` **inside the same
transaction**, so it is all-or-nothing across the pieces it accepts — 0k4's
first recorded decision, for its reason: *an apply that landed nothing must not
read as applied*, and a refusal inside must roll the whole tap back. A
household would rather have three of four things on the calendar than none,
which is why the *pieces* are separately tappable in the first place; what it
must never have is a bundle that reads accepted while a piece silently did not
land.

**Idempotency: the verdict doors are the boundary.** No deterministic inner
key, 0k4's second recorded decision, for the same reason — a second tap meets a
terminal row. This is the sharp difference from `approval-effects!`, which
needed `"approval-mint-" + ask-id` *because* it runs post-commit under a system
actor with no verdict row of its own to be the boundary. Here the row is the
key. The outer tap still carries `feed/origin-key` with a fresh nonce per tap,
so a double-tap is two attempts and the second is a 409 rather than a second
task on the calendar.

**The stale target, and it needs no new mechanism.** Staging validates the
prepared input against the target's own create schema
(`the-prepared-input-fits-the-door`, 0k4's letter-addressing lesson: *a button
that fails is worse than a button that was never offered*). The world moves
between staging and the tap anyway — the Wednesday slot fills, the list gets
discarded, the authority conflicts the row — and the answer is that **the
target's own create guards judge at the tap, inside the transaction**, and the
refusal is rendered on the card that asked for it, naming the piece. The
household's next move is two taps: `Not this` on the stale piece, `Make it so`
again. What is deliberately *not* built is a second staleness oracle in the
plan: a wall that tried to predict another kind's guards would be a second
opinion about that kind's law, and it would be wrong first.

### The outcomes section — the crown, and its floor

**`:outcomes` joins `feed/census` at the top:**
`[:outcomes :do_now :decide :fuel :seam :archive]`. The census is law, so
`check-recipe!` then refuses any recipe that puts *This week could hold* below
the queue, with the sentence it already knows. The `:section` enum of
`feed_recipe/order-schema` is generated from `census`, so the picker widens for
free — and per 0k4's correction, `feed_recipe`'s **fingerprint does not move**
(`fingerprint-of` is a whitelist over kind/machine/derived/storage; `:schema`
is not in it).

The recipe line, in `default-recipe`:

```clojure
{:section :outcomes :population :plans :take 2}
```

**The weekly cap lives at the create door, not in the recipe and not in the
population.** `plans-are-few`: two offered plans per author per **calendar
week** (`store/utc-week-start`, the week the household is having, not a rolling
168 hours — `insights-are-capped`'s own reasoning about the calendar day),
counting rows so a refused create spends nothing. Three reasons the alternatives
lose:

1. **A filter buries what a wall would have refused.** The cap exists so the
   composer must *rank* — the insight cap's whole point, put at the source. A
   population that showed two of ten staged plans would let a composer dump ten
   and learn nothing, and eight rows would sit `offered` teaching the composer
   that the household ignores it.
2. **The recipe has no word for a week and should not grow one.** `:take` is
   per page; adding a window would make the recipe mean a second thing, in a
   schema a person edits in a form.
3. **A weekly filter fights the floor.** 8um law 3 wants guaranteed exposure so
   the contest can be measured; a read-side window would hide cards that had
   already spent the week's allowance, and *a learner cannot learn about a card
   it never shows*.

**Superseded, 2026-08-26 (waymark-1uv.1 ruled it; waymark-1uv.3 built it) —
see § *Ranked, not capped — the principle* and § *Built — 1uv.3 and 1uv.10*
below.** The paragraph above stands as history. The cap was a wall on WRITING
standing in for a rank on ATTENTION, and its first reason stopped being true
when the view-event door (waymark-8um.1) made *never shown* distinguishable
from *shown and passed over*; its second and third are about where a rank
lives, not whether one exists. `outcomes-are-few` is gone from the create
door; the crown's declared rank (`feed/default-crown-rank`, waymark-1uv.2)
chooses which bundles fill the slots; the floor below is untouched, and the
"not in the population" half of the sentence is still law — a rank places
last, a window would hide.

**The exposure floor is the line's `:take`, and this is the section 8um law 3
was written for.** Today nothing ranks, so the floor is trivially honored: the
line shows up to two plan cards whenever two exist. When **waymark-8um.3**
lands its declared formula, the rule this spec fixes in advance is that the
formula **may not reduce this line below its `:take`** while candidates exist,
and that a card held by the floor says so in its `why` (*"held by the floor"*).
The population must therefore keep naming candidates it did not show, so the
`claimed_above` counting the citation already does stays truthful.

**The crown-and-floor sentence, recorded so a later bead can quote it:**

> The outcomes section is the one place in this feed where a card is
> **authored** rather than projected, and it sits on top because that is what
> it is for. Everything beneath it stays the incorruptible ledger — rows nobody
> wrote for effect — and that is precisely what makes it a fair audit of the
> crown. The floor exists so the contest can be **measured**, not so the crown
> can escape it: a plan that keeps being passed over is a diagnosis waiting to
> be written, and the sections below will still say, in the household's own
> rows, whether the week actually held it.

**The population.** `plans` names `:plan` and `:plan_piece` by keyword and
answers `[]` when the engine holds neither — the `letters` / `ticklers` /
`insights` precedent, and this is the fourth and fifth optional application
kind core names by keyword. It reads `offered` plans whose leash has not run
out, drops **the reader's own** (the four-eyes wall means carding a plan to its
composer would offer a door that answers 409 — what `asks` and `insights`
already do one section down), retires at offer time when the value it serves is
retired or every piece is answered, and hands each candidate a `:sentence`: the
goal in value terms, the value named, the routing cited. Bounded by
`row-scan-cap` like every other population; the create-door cap is what keeps
the number small at the source.

**The card carries `pieces`, and that is the one wire widening this epic
asks.** A candidate gains an optional `:parts` key; `feed/card` renders each
piece through the same `envelope-summary` → `:row?` → `split-verbs` path and
copies the results onto the card as `"pieces"` — so a piece's chips are the
piece row's **own** projected verbs and concealment holds exactly as it does
for the parent. The justification is `.5`'s: the seam was already the one
element of this document that is not a projection, `sentence` was the second,
and a third costs the client nothing. The cost is bounded and stated: at most
`5 pieces × 2 cards` extra envelope renders per read.

**The join list, for jfv.4** — every site a new section touches, in order:
`feed/census`; the `plans` population `defn`; the `populations` registry entry;
`population-says`; `population-reads` (`[:nav]` — the section reads no `:over`);
`entry-cards`' `admits?`/`keep?` if the section takes a bargain (it does not —
a plan card with no verb left is retired at offer time instead); `card-says`'
section clause (the section's own sentence, so the citation does not lie by
omission); `default-recipe`; `135-feed-screen.js`'s `FEED_SECTION_LABEL` and
`FEED_SECTION_HINT`; and **`packs.clj`'s `above-seam #{"do_now" "decide"}`
literal**, which is hard-coded twice and must be widened or the pack will judge
the new section as though it were below the seam.

**The screen dispatches on the card's SHAPE, never on its kind** — `.7`'s own
correction, and it applies here doubly because `plan` is a workqueue10 kind and
`135-feed-screen.js` is the framework's page. **A card carrying `pieces` is a
bundle**: the goal as the heading, the value and routing as its why, the pieces
as lines each with a `Not this` chip, and `Make it so` / `Not this week` as the
card's own chips. `section`, `population` and `kind` ride as `data-` attributes
and nothing reorders on them.

### The composer contract

The composer is an **external leashed agent at the MCP door** — not in the
engine, not in the tree, wearing `waymark_query` / `waymark_get` /
`waymark_invoke` like any other leash. v1 is **manual**: a person runs it, the
plans land, the caps hold. Automation waits on **waymark-53u**, paused by the
owner's own choice.

**What the doors enforce, and it is most of it.** Each of these is a create
guard, refuses with a sentence that names the fix, and is proved by a scenario:

| wall | what it refuses |
|---|---|
| `names-a-value` | a plan citing no value, a value that is retired, or one this house does not hold |
| `cites-what-it-read` | empty `evidence`, or an address naming a collection this engine does not serve — **every** offender named, because a composer fixing them one round trip at a time is a composer burning its cap |
| `routes-through-something-loved` | a `routes_through` no active value declares; **absent is allowed**, deliberately (see below) |
| `a-bundle-is-small` | fewer than two pieces, more than five |
| `plans-are-few` | the third offered plan of the week, per author; and a superseding plan before its `not_before` |
| `the-prepared-input-fits-the-door` | a piece whose prepared input would 422 at its target's create door |
| `not-a-twin` | *(since waymark-8gc)* a bundle citing an evidence row a standing — `offered` or `accepted` — outcome already cites, any composer; the refusal names that outcome and the shared address. A cited `request_id` is exempt, and a row's own value address is not a reading |
| `composes-from-what-stands` | *(since waymark-euj)* a bundle whose **every** citation is a finished row — a done or dropped task, an event that has ended, a retired value, a past person, an answered finding — refused naming each row and the word it is finished with. One standing citation is enough; a row this house cannot classify **stands**; the bundle's own value address is subtracted first, so citing the value alone is not a reading |
| `the-door-is-open-now` | *(since waymark-euj)* an invoke piece staged behind a button that is not there — the target action judged **unavailable** on that row now, by `render/action-availability`, the envelope's own partition asked one door at a time; the refusal quotes **that door's** unavailable reason. A denier reading `:principal` is about the composer's hand rather than the row, and the wall stands down for it |
| `the-composer-does-not-decide` | the stager answering its own plan or piece (`g/not-the-field :composed_by`) — **absolute**, and no grant opens it |
| `a-person-answers` | an agent tapping a verdict **whose grant does not admit that door** (`g/unless-granted`, since waymark-sfe / 2026-08-28) — and, on every door, **the agent that composed the row**, grant or no grant. The refusal names the token a scope would have to carry |
| `only-its-composer-reworks` | *(since waymark-9j2; grantable since waymark-9xn / 2026-08-28)* anybody but the row's own composer walking `outcome.rework` or `outcome_piece.rework` **without a grant that admits that door on that row** (`g/author-or-granted`). It is `a-person-answers` with the own-field exemption **inverted**: authorship is what opens this door rather than what closes it, so the composer walks it unasked and a second agent walks it only on a person's approved errand — which is what makes an ORPHANED bundle finishable |
| `words-do-not-answer` | *(since waymark-vf8 / 2026-08-29; the one wall in this table that stands on ANOTHER kind's door)* an AGENT posting a `remark` whose subject is an outcome in `iterating` **when that agent is the one that could rework it** — its own `composed_by`, or a grant admitting `outcome.rework` on that row. It refuses with the door's own address, and it is declared by this kind (`:answered-at-a-door`) and enforced by the framework's thread kind, so the predicate is generic and the sentence is the household's. A person's turn, a bystanding agent's turn, and any turn a door files from inside itself (`:within`) all pass |
| `the-marks-are-the-work-order` | *(since waymark-wxk / 2026-08-29)* a `rework` commit that, in a round where the household MARKED pieces, withdrew one they left standing (a KEEP) or left a RE-TIME or REPLACE without a new piece staged inside the round. A mark is the piece's own `not_this` plus a `verdict_reason` word — wrong time is a RE-TIME, wrong piece or not this way a REPLACE, never this (or no word at all) a DROP that needs nothing — and the round is the interval since `reworked_at`. The refusal names every offender with its list. In a round where NOTHING was marked the wall says nothing: the note is then the whole order |

**The outcome's states, as they now stand** (waymark-9xn, 2026-08-28):
`offered` → `iterating` → back to `offered`, with the three terminal answers
reachable from either standing state.

| from | door | to | who |
|---|---|---|---|
| `offered` | `iterate` | `iterating` | a person (an agent only under a grant naming `outcome.iterate`; never the composer) |
| `iterating` | `iterate` | `iterating` | the same — a second note is another turn on the thread |
| `iterating` | `rework` | `offered` | the row's own composer; another agent only under a grant naming the row |
| `offered` | `make_it_so` | `accepted` | a person (grantable) — **refused from `iterating`** by `the-plan-is-not-under-rework`, in the house's own words |
| `offered` / `iterating` | `not_this_week` | `declined` | a person (grantable) — a decline is always allowed |
| `offered` / `iterating` | `expire` | `expired` | anybody, once `good_until` has passed — the leash keeps running while a bundle is being reworked |

A piece's own doors read the parent's state: `take` is refused while the
bundle is `iterating` (`the-bundle-is-taking-answers`), `rework` (withdraw)
opens **only** while it is (`the-parent-invited-a-rework`), and a replacement
piece **stages** under an iterating bundle, which is where a re-plan happens.
`not_this` reads **nothing** about the parent, and since waymark-wxk that is
load-bearing rather than incidental: a decline is how the household MARKS the
piece that is wrong, so it stays open under a bundle being reworked — what
`iterating` closes is the tap that would LAND a step of a plan the person
called wrong, never the saying of what is wrong with it.

And since waymark-vf8 the composer's **only** door on an iterating bundle is
`rework`: the thread's own create door refuses that one hand a turn there, so
a promise cannot stand in for the act. `rework` requires `says` (1–240) and
admits a round that changes **no** piece — the composer read the note and
stands by the plan, which the household may then decline.

**The two walls above are different in kind, and the difference is the
whole of waymark-sfe.** `the-composer-does-not-decide` is *four eyes*:
it refuses whoever staged the row, person or agent, always. It is not
grantable and will not become grantable. `a-person-answers` is a
*permission*: since the owner's ruling of 2026-08-28 — "*it doesn't
make sense to disallow it, it just makes sense to permission it*" — an
agent presenting a grant that names `outcome.not_this_week` (or
`make_it_so`, `iterate`, `outcome_piece.take` / `not_this` / `moot`)
may tap it, because that scope exists only where a person approved an
`approval_request` in the feed. The agent asks, the person approves,
the agent acts on instruction; **an agent's own initiative still cannot
widen anything**, which is what law 6 has always been about. Because
`iterate` and `moot` never carried a separate four-eyes wall — refusing
every agent gave them one for free — `a-person-answers` now carries it
itself (`:own-field :composed_by`), so a composer answering its own row
is refused at every one of the six doors. The transition records the
grant on its actor, so the history reads *declined by `<agent>` under
`grant-…`*.

The same ruling reaches three doors outside this table, the same way and
with four eyes kept the same way: `person.still_with_us` / `revise` /
`now_past` / `dismiss` / `restore` and `value.still_stands` / `revise` /
`retire` / `dismiss` / `restore` (four eyes on `written_by`),
`ranking_note.dismiss` (four eyes on `judged_by`), and
`composition_request.create` (no row yet, so nothing for four eyes to be
about — law 6 holds because the scope is a person's tap). It does **not**
reach `feed_recipe` or `recipe_proposal`: who orders the feed is law 6's
own subject and stays a person's hand outright.

`routes_through` is optional on purpose, and the reason is the thesis read
honestly: *Grandpa's paperwork* routes through nothing anybody loves, and a
door that forced a routing citation would be a door that taught the composer to
invent one. A plan with no loved activity says so, and its `routing` prose has
to earn the ask some other way.

**What only prose can hold**, and it is named here so `jfv.5` writes it down
rather than assuming it:

- **The diagnosis duty (8um law 4).** Non-engagement with a high-value plan is
  the composer's **work order**: diagnose the friction, recompose, re-offer,
  before any burial. No door can enforce this — the door cannot see engagement
  (view events are **waymark-8um.1**'s), and it cannot tell a recomposition
  from a repeat. What *is* enforceable, once **8um.3** exists, is the
  **ordering**: a diagnosis must exist before a floored line may be demoted,
  and that is **8um.4**'s bead. This spec's contribution to it is the raw
  material: a piece-level decline signal, a plan-level timing signal, and a
  `still_stands` tap that separates *the value is wrong* from *the plan is
  wrong*. *(History since 2026-08-26: the door CAN see engagement now, and
  half of this moved out of prose — `no-burial-without-a-diagnosis` is a
  wall on `supersedes` and `GET /api/-/diagnosis` is the read; what prose
  still holds is the duty's rhythm, under § "The composer's duty under the
  rank" below and § "Built — 1uv.4".)*
- **Bundle coherence.** `a-bundle-is-small` counts; it cannot judge whether
  five pieces are one Saturday afternoon.
- **"Friction pre-paid" is a claim about the world.** The door validates that
  the prepared input fits; it cannot know whether the stock is really cut.
- **Ranking.** The cap forces the composer to choose; the door never sees what
  was discarded, and it should not.

**The grant scope, exactly** — the `insight` precedent, widened by exactly what
a composer must read:

```json
{"audience": "claude-code",
 "scope": [{"kind": "plan",        "actions": ["create"]},
           {"kind": "plan_piece",  "actions": ["create"]},
           {"kind": "insight",     "actions": ["create"]},
           {"kind": "value",       "actions": []},
           {"kind": "task",        "actions": []},
           {"kind": "chore_run",   "actions": []},
           {"kind": "media",       "actions": []},
           {"kind": "event",       "actions": []},
           {"kind": "feed.preview_as", "actions": [],
            "filter": {"member": "<the owner's member id>"}}],
 "expires_at": "…"}
```

**Since waymark-jfv.20, one more read-only line:** `{"kind":
"composition_request", "actions": []}`. A composer reads the requests the
household has standing (`?state=offered`) and answers one by staging an
outcome whose `request_id` names it — no door on the request kind is granted
because none is needed: the staging itself moves the request, and the only
door that could is walled to the staging's own hand (§ 'Built — jfv.20').

**Since waymark-1uv.5, the tuning agent's four lines** — one write door,
which is the staging door, and three read-only lines for what a rank proposal
cites: `{"kind": "recipe_proposal", "actions": ["create"]}`, `{"kind":
"feed_view", "actions": []}`, `{"kind": "verdict_reason", "actions": []}`,
`{"kind": "feed_recipe", "actions": []}`. A composer that tunes the crown's
rank reads exposure, the house's verdict words and the recipe's numbers in
force, proposes new numbers through `recipe_proposal` with `crown_rank` /
`current_crown_rank` beside the order, and cites the rows it read; a member
applies. Never `feed_recipe` revise — § 'Built — 1uv.5' has the table of
what is read per input and the reason the reads are enough.

**Since waymark-1uv.6, the scoring agent's two lines** — `{"kind":
"ranking_note", "actions": ["create"]}` and a read-only line over the kind it
scores, `{"kind": "outcome", "actions": []}`. An agent that read a bundle
writes a score, 0 to 1, and one sentence, citing what it read; the crown
reads the score at `crown_rank.judged`'s weight and the card quotes the
sentence as the agent's. A composer holding the note door is refused on
anything it staged (`not-your-own-row`, off the subject kind's own
`:own-surface`), so the two lines may ride the composer's own leash or a
second agent's alone — § 'Built — 1uv.6' has the walls and the tiers.

**Since waymark-8um.4 and waymark-1uv.4, the diagnosis reader's two lines,
and one document over HTTP.** The composer's diagnosis is a document beside
the feed — `GET /api/-/diagnosis`, `?outcome=<id>` for one — and a document
has no plural, so the six MCP tools cannot name it (**waymark-8um.5**, open
and deliberately not built here): the composer reads it **over HTTP with its
bearer**, exactly as it reads `/api/-/welcome`, and the wall's own refusal
sentence carries the address. The document folds the composer's own outcomes
with no grant; the two records it reads about them are other members' — a
view row is the member's, a reason is the sayer's — and each half is admitted
only by the whole-kind read grant designed for it, **read-only, both**:

```json
{"kind": "feed_view",      "actions": []},
{"kind": "verdict_reason", "actions": []}
```

A leash naming neither reads a diagnosis that says *withheld* per half and
which line to ask for — never a quiet zero. A composer that also tunes
already holds both (the 1uv.5 block above); nothing else on the leash moves,
and `diagnosis_id` on a recomposition is an `insight` the composer's own
`insight.create` already writes.

**Since waymark-9xn (2026-08-28), one more errand the same scope can carry:**
`{"kind": "outcome", "actions": ["rework"], "ids": ["<the bundle>"]}` — the
household's way to get an ORPHANED plan finished when the composer that staged
it is gone. The row's own composer never needs it; anybody else does, and the
grant names the row.

**Since waymark-sfe (2026-08-28), a DELEGATION scope the standing leash does
not carry and never should.** The verdict and affirmation doors are
grantable now (§ *The composer contract*, the wall table's addendum), which
means an agent may hold one **only when a person has approved an
`approval_request` naming it**. It is a separate, short-lived grant, filed
per errand, and it is spelled exactly like any other scope — this is what
the owner approves when he says *decline these thirty-one, with these
words*:

```json
{"audience": "claude-code",
 "scope": [{"kind": "outcome",
            "actions": ["not_this_week"],
            "filter": {"composed_by": "some-other-composer"}},
           {"kind": "person",  "actions": ["still_with_us"]},
           {"kind": "value",   "actions": ["still_stands", "revise"]},
           {"kind": "composition_request", "actions": ["create"]}],
 "expires_at": "…"}
```

Read it door by door. `outcome.not_this_week` lets the holder decline a
bundle — and the `filter` narrows it to rows whose `composed_by` is that
value, so a row outside the leash **does not exist** for this grant, in the
collection, on a GET, and at the door. `person.still_with_us` and
`value.still_stands` / `value.revise` are the affirmations; `revise` also
rewrites the words, which is why an owner granting it is granting more than
a tap. `composition_request.create` files the household's own pull. Two
things no scope in this shape can buy: **the row the holder itself wrote**
(four eyes, on `written_by` / `composed_by` / `judged_by`), and **anything
the holder asked for and nobody approved**. And note what
`outcome.make_it_so` costs: taking a bundle fans out to each piece's own
`take` door, so a scope meaning "accept it for me" is *two* lines,
`{"kind": "outcome", "actions": ["make_it_so"]}` **and**
`{"kind": "outcome_piece", "actions": ["take"]}` — the leash follows the
hand into the cross-write rather than being waived by it.

**The composer's duty under the rank** — the epic's reading of law 4, and
the sentence the composer is held to now that the cap is gone and most of
what it stages will never reach a screen:

1. **Read the tally before every sitting.** `tally.lessons` counts the
   document's outcomes per lesson; `tally.owing` is the work order's length;
   `tally.never_shown` is the rank's pile with the lift each carried.
2. **Recompose only shown-and-declined and shown-and-passed-over lines, with
   a diagnosis** — an insight citing the prior, named as `diagnosis_id` on
   the new outcome. The wall refuses the recomposition without one; the
   contract refuses the *fresh* outcome that is the same line in a new hat.
3. **Treat never-shown as the rank's verdict, not the house's.** Read
   `rank` on each — the lift as staged, the inputs, its place among the
   composer's own — and answer it by staging differently, by proposing new
   numbers through `recipe_proposal` (1uv.5), or by waiting for the floor.
   Never by an insight about the house: nobody in the house saw it.
4. **Unknown is unknown.** No diagnosis is owed and none can be honest;
   the honest move is to ask a member to turn their record on.

Write three kinds, all create-only; read the values it must name, the work
kinds it composes over and cites as evidence, and the owner's feed as the
owner sees it. **Never** a verdict action, never `value` create or revise,
never `grant` / `capability` / `role` / `member`, never a write door on any
target kind — materialization happens under the member's tap or it does not
happen.

0k4's sentence transfers whole and is the reason this grant is approvable:
**`plan` is grantable at the MCP door precisely because holding that grant
confers no power over the household's Saturday.** Neither kind is one of the
private own-surface trio, so `scope-omits-private-kinds` admits both.

**The first real composition — the walk for jfv.5**, step by step, because a
bead whose acceptance criterion is *the owner taps it* deserves a script:

1. **The owner declares his values first**, by hand, in the app — two to four
   rows with their `loved` words. No agent, no seed, no fixture. Nothing else
   in this epic is meaningful until this exists.
2. **The composer files its own ask.** An `approval_request` carrying the scope
   above, created by the agent — so `someone-else-decides` is satisfied
   structurally and the owner approves rather than approving his own ask.
   Watch **waymark-h6y**: an approval-minted grant inherits the ask's offer TTL
   as its lifetime, which would leave the composer a thirty-minute leash. Set
   `expires_at` on the ask explicitly.
3. **Claude reads the house** through the grant: the values, the open work, the
   log. It drafts two plans against the reference design — the finger-joint box
   with Jack, and Grandpa's paperwork sitting.
4. **It stages them**: `plan` create, then two to five `plan_piece` creates
   each, every prepared input fitting its target's door. The walls refuse
   whatever is malformed **before the owner sees anything** — that is the point
   of staging validation.
5. **It previews the owner's own feed** (`GET /api/-/feed?preview_as=<owner>`
   with the grant header) and confirms, before saying a word to anybody: both
   plans card in `outcomes`, the pieces render with their chips, the value and
   the routing are on the card, and nothing fell into `heavier`.
6. **The owner opens the feed** and reads *This week could hold*. He taps
   `Not this` on any piece he does not want and `Make it so`.
7. **The rows land** through the ordinary doors with the owner as actor.
8. **The loop is read back off the audit trail**: the plan's create transition
   naming the agent; each piece's verdict naming the owner; each materialized
   row's create transition naming the owner; and `feed/actions-from-feed` for
   the day showing the taps as actions-from-the-feed.
9. **What the owner actually said about the cards is written into the bead.**
   That sentence is the deliverable; everything above it is machinery.

## Landing order

```
.1 (this spec) ──▶ .2 value ──▶ .3 plan + pieces ──▶ .4 the section ──▶ .5 the composer
```

**Confirmed as wired, and each dependency is real rather than tidy.**

- **`.2` first** because `.3`'s `names-a-value` and `routes-through-something-loved`
  read value rows, and because the loved-words vocabulary is what a plan's
  routing citation is checked against. A plan built before values exist would
  have to invent a stand-in and then have it moved.
- **`.3` second**, and it lands entirely as declarations and doors: a plan is a
  row long before it is a card, exactly as `tickler` and `insight` were rows
  before they were populations. It touches no framework file.
- **`.4` third**, and it is the only bead of the four that edits waymark10:
  `census`, the population, the card's `pieces` branch, `packs.clj`,
  `135-feed-screen.js`. It cannot start before `.3` because a population with
  no kind to read is a registry entry that cannot be judged.
- **`.5` last**, because it needs a feed that shows plans; and because its real
  content is operational — a grant, a composition, an owner's tap.

**One amendment, and it is a warning rather than a re-wiring:** `.4` carries
the epic's largest single piece of work, and it is UI. The feed epic's own `.7`
was *half the epic on its own* and this is the same shape one card further on —
a card with sub-rows, each with its own verbs. If `.4` grows past a day, split
the screen half out rather than letting the wire half wait for it; the wire
half is independently provable (the pack judges `pieces` off the document) and
the screen half is verified by hand the way this repo verifies screens.

### What cannot run concurrently — including the `waymark-8um` chain

`waymark-8um.1` and `.2` are blocked on `waymark-0k4`, which is landed in the
spec and in flight in the tree. `8um.3` needs view events from `8um.1`; `8um.4`
needs `8um.3` **and** says in its own description that it may wait for this
epic's composer contract.

| pair | why not, and which file |
|---|---|
| `jfv.4` ∥ `8um.1` | both edit `feed.clj` and `135-feed-screen.js` and `packs.clj`; `8um.1` also rewrites `spec-feed.md`'s law section. **Run `8um.1` first**: its view-event door is what makes the diagnosis duty a duty rather than a paragraph, and `jfv.4` is the harder rebase because it moves the census. |
| `jfv.4` ∥ `8um.2` | deal-again puts a nonce in the seed and the cursor — `feed/document`, `feed/seed-of`, the same functions the outcomes line is being threaded through. |
| `jfv.4` ∥ `8um.3` | the formula reorders the draw and must honor a floor. **Run `jfv.4` first**: `8um.3` should prove the floor against a line that is actually floored, and a floor law written with no floored line is a law with no witness. |
| `jfv.5` ∥ `8um.4` | `8um.4` is explicitly allowed to wait for the composer contract this epic writes. **Run `jfv.5` first** and have `8um.4` cite § *The composer contract* rather than re-deciding it. |
| `jfv.3` ∥ `jfv.4` | the population reads the kinds `.3` is still shaping. |

**The honest concurrencies.** `jfv.2` and `jfv.3` touch **no framework file at
all** — two declarations in `workqueue10/src/workqueue10/resources/` and their
entries in `main.clj`'s resources vector — so either may run beside any `8um`
bead. The one collision to expect is `main.clj`'s vector if `8um.1` chooses to
declare its view-event kind application-side; if it enrols the kind
framework-side on the `:feed` module (the `feed_recipe` / `recipe_proposal`
precedent, and the right answer for a kind the feed's own screen writes), there
is no shared file and the two chains are independent until `jfv.4`.

A suggested total order, if one agent runs everything:
`0k4 → 8um.1 → jfv.2 → jfv.3 → 8um.2 → jfv.4 → 8um.3 → jfv.5 → 8um.4`.

## Recorded punts

- **A one-tap ratification of an exact amendment.** The petition offers
  `still_stands`, not the new wording, because no door taking a string is ≤
  selection. The fix is 0k4's staged proposal generalized past `feed_recipe`,
  which is the other half of **waymark-xw3** and wants its own security review.
- **A piece materializes with `:create` only.** Moving an existing row — *start
  the show again*, *reopen the dropped task* — is not expressible as a piece
  today. The single-row revise case is 0k4's; generalizing it is filed.
- **The `:decision` sugar cannot spell either kind here** (no verdict
  `:touches`, no principal-type wall, one open state, one home per hook), so
  both are hand-written with the sugar's own guards. **waymark-bro.**
- **The loved-activity vocabulary has no picker.** A row-backed `x-options`
  source does not exist (**waymark-90k**), so `routes_through` is a plain
  string judged by a guard whose refusal names the legal words. It will earn an
  `effort-honesty` warning nobody can clear until 90k lands — expect it, do not
  paper over it.
- **No second pair of eyes on a household value.** Same reason 4yn gave for the
  household recipe: the role wall would be a wall against everybody while
  `assign_roles` is broken (`spec-feed.md` § *Built — 4yn* carries the
  citation), and a value is reversible through its own doors with the stamp
  naming who moved it. **waymark-pcr.**
- **Nothing sweeps a lapsed plan.** `expire` is bookkeeping anybody may run once
  the clock has passed; the population drops lapsed plans at offer time.
  `grant`'s own posture, inherited rather than re-decided — and `recipe_proposal`
  inherited it one bead ago.
- **A plan cites evidence as addresses, and the addresses are not re-read.**
  `cites-what-it-read` proves an address names a collection this house serves;
  it does not prove the row says what the plan claims it says. That is the same
  bound `insight`'s wall has always had.
- **View events are not this epic's.** Every sentence here about what the owner
  *did not* engage with is a promissory note against **waymark-8um.1**. Until
  it lands, the composer's only engagement signal is the verdict trail — which
  is real, and is exactly what per-piece rows exist to make legible.
- **The composer's cadence is manual.** **waymark-53u** — a lapsed session plus
  a leashed grant is two human taps a day, and the owner paused the automation
  deliberately.
- **The plan's own fuel is unclaimed.** A week whose plans were accepted and
  materialized is a deed, and nothing celebrates it: `plan` is `:nav :system`,
  so no fuel population speaks about it. Deliberate for v1 — a house
  congratulating itself on *accepting proposals* rather than on doing the work
  would be the exact self-referential loop this epic is trying not to build.

## Effort

**Large, and lopsided in the now-familiar direction.** `.2` is one declaration,
one stamp, four actions and three scenarios — half a day, and most of it is
writing the household's sentences. `.3` is the real design bead: two kinds,
eight guards, two cross-writing handlers, the enum of materializable kinds, and
the scenarios that prove a person-only wall on a hand-written verdict — a day,
maybe two, and the fan-out handler is where a careful hour buys a week. `.4` is
a day of wire and a day of screen. `.5` is hours of machinery and an evening of
the owner's actual attention, which is the only part of this epic that cannot
be scheduled.

The risk is not the code and it is not the walls. It is that two beautifully
staged plans land in front of the owner and he reads them the way he read the
first feed: everything worked and almost nothing was right. That is why `.5`
ends by writing down what he actually said.

## Built — jfv.2, the value kind (2026-08-24, waymark-jfv.2)

The first row in this tree whose content is **what a person cares about**
rather than what the house has to do. One file,
`workqueue10/src/workqueue10/resources/value.clj`, and its entry in
`main.clj`'s resources vector. **No framework file was touched**, which is
what the landing order promised of this bead and of `.3`.

### The declaration, as it landed

```clojure
(defresource value
  {:kind :value
   :plural "values"
   :nav :secondary                     ; ← load-bearing, see the spec above
   :states [:declared :retired] :initial :declared :terminal #{}
   :summary "{data.name} · {data.scope} · {state}"
   :label-template "{data.name}"
   :display {:title "Value"}
   :schema [:map
            (entry :name  {:sort :default}          [:string {:min 1 :max 80}])
            (entry :says  {}                        [:string {:min 1 :max 2000}])
            (entry :loved {:optional true}
                   [:maybe [:vector {:max 12} [:string {:min 1 :max 40}]]])
            (entry :scope {:filter #{:eq}}          [:enum "household" "mine"])
            (entry :owner {:optional true :filter #{:eq}}
                   [:maybe [:string {:max 128}]])
            (entry :reviewed_at {:optional true :sort true}
                   [:maybe :waymark/instant])
            (entry :reviewed_by {:optional true :filter #{:eq}}
                   [:maybe [:string {:max 128}]])]
   :create-schema [:map … name, says, loved, scope …]   ; owner is the stamp
   :filterable {:state #{:eq :in}}
   :create-guards [written-by-a-person]
   :on-create stamp-owner
   :actions
   {:revise       {:from #{:declared} :to :declared
                   :input revise-input :edit {:prefill [:name :says :loved]}
                   :record true :waives #{:large-effort}
                   :guards [written-by-a-person this-is-yours-to-declare]
                   :handler apply-revision}
    :still_stands {:from #{:declared} :to :declared      ; NO :input
                   :guards [written-by-a-person]
                   :handler stamp-the-review}
    :retire  {:from #{:declared} :to :retired :undo :restore
              :guards [written-by-a-person this-is-yours-to-declare]}
    :restore {:from #{:retired} :to :declared :undo :retire
              :guards [written-by-a-person this-is-yours-to-declare]}}})
```

The design section above is the decision record and nothing in it was
re-opened. What follows is only what the landing itself decided or found.

### The states, decided: the smallest honest machine

`declared` and `retired`, neither terminal, reversible both ways. No
`proposed` — nothing but a person may declare one, and a person declaring one
has already decided. No `amended` — an amendment is a **revision of a standing
law**, not a different state of it, and `:record true` on `revise` puts the
prior words, the actor and the law revision in the transitions log, which is
the amendment history the epic asked for. `still_stands` is the self-loop that
stamps `reviewed_at` / `reviewed_by`; it does not mint a state either, because
*reviewed* is a date, not a condition.

### The petition flow, as it actually works now

1. An agent reads the house through its leash, notices something, and
   publishes an **`insight`**: the finding in one sentence, `evidence` naming
   the rows it read, and its offer set to `{"offer_kind": "value",
   "offer_action": "still_stands", "offer_href": "/api/values/<id>"}`.
2. `offers-something-light` **admits it**, and that is the whole reason
   `still_stands` takes no input: an offer must be no heavier than
   `selection`, `demand/effort` answers `"assent"` for an action with no
   `:input`, and every door that could take the new wording takes a string,
   which is `recall`. This is proved rather than asserted — a new
   conformance-tier scenario on `insight`, `a-value-may-be-petitioned`, files
   exactly that insight through the real door.
3. The owner reads the card and does one of two things. **Tap** — one tap, no
   form, `reviewed_by` and `reviewed_at` stamped, meaning *I read the evidence
   and it stands anyway*, which is the signal that separates *the value is
   wrong* from *the plan is wrong*. Or **open the value and reword it** —
   `revise`, prefilled, by his own hand, which is the ratification.

### The agent wall, and its sentence

`written-by-a-person` stands at **every** door this kind has — create,
`revise`, `still_stands`, `retire`, `restore` — and it names the lawful path
rather than merely closing:

> What this house cares about is written by a person. An agent that could
> declare or reword a value would be a composer grading its own homework —
> publish an insight instead, citing the rows you read, and offer this value's
> own "these still stand" as the one next step; the owner answers with a tap
> and does his own rewording.

`this-is-yours-to-declare` is the second wall, on the three doors that change
the declaration:

> This one is somebody else's to say. A value scoped "mine" belongs to the
> member the engine stamped on it; a value the whole house holds is scoped
> "household", and anybody here may write that one.

Both read only the principal and the row, so both are judged with **no
database at all**.

### Where the law is proved

Five scenarios on `value`, all check tier, and the two refusing guards are
both named by one:

- `an-agent-does-not-declare-a-value` — create, as an agent, refused by
  `written-by-a-person`, `:because "publish an insight instead"`.
- `an-agent-does-not-amend-one` — `revise`, as an agent, same wall. This is
  the half that matters, because rewording is the quiet way to author.
- `a-person-amends-what-the-house-declared` — allowed; the wall bars the
  composer, not the family.
- `somebody-elses-value-is-not-yours-to-reword` — `revise` of a `"mine"` value
  by another adult, refused by `this-is-yours-to-declare`.
- `a-petition-is-answered-with-one-tap` — `still_stands`, allowed, no input.

And one scenario on `insight`, deferred to the suite because
`offers-something-light` consults the registry: `a-value-may-be-petitioned`.

`make check-queue` goes from **32 to 33 kinds** and **27 to 32 scenarios
judged**, with the battery's warning count unchanged at 11 — `value` reports
clean.

### Recorded here, for whoever comes next

- **A new kind is a new table.** Production needs `values` created before the
  deploy that serves it. `make migrate-queue-prod` prints the plan (read-only,
  and it refuses `APPLY` on purpose); a person runs the statements through
  `nomad alloc exec -task postgres <alloc> psql -U workqueue -d workqueue10`.
  Nothing here touched production.
- **Exactly one fingerprint appeared and nothing moved.** `value` is new;
  every other kind's hash is byte-identical. `insight` gained a scenario and
  did **not** move, because `fingerprint-of` is a whitelist that never names
  `:scenarios`. The census in
  `workqueue10/test/workqueue10/fingerprint_stability_test.clj` went 32 → 33.
- **`revise` waives `:large-effort`, on the journal's own reasoning.** `says`
  is required prose, but the door prefills the paragraph that already stands
  and is `:record true`, so a mis-click loses an in-progress edit and not the
  declaration. A shared live draft is not warranted for a law one person
  writes.
- **`loved` stayed a plain vector of strings, and `:waymark/vocab` is the
  question left open.** The tree has a first-class vocabulary token type
  (`meal/theme-schema`, `ingredient`'s tags) which self-merges into
  `:filterable` and `:faceted` — observed facets over the household's own
  loved words, plus a GIN index, for one keyword. That is arguably the honest
  partial answer to **waymark-90k** for this field. It was not taken here
  because the spec decided the schema literally and a facet surface is a
  read-side decision this bead did not own. Filed rather than done.
- **`still_stands` carries only the agent wall, and the spec chose that.** A
  `"mine"` value can therefore be affirmed by another adult in the house, who
  is then named in `reviewed_by`. Whether the affirmation of a private value
  should be owner-only is a real question and it is filed, not decided here.
- **`:plan` is already taken.** `mealplan10` declares `:kind :plan` — the
  week's meal plan — and `.3`'s parent bundle cannot have that name. Filed
  before `.3` starts, because renaming a kind after it has a table is not a
  rename.

## Built — jfv.3, the outcome and its pieces (2026-08-25, waymark-jfv.3)

The composed bundle, and the pieces the household answers one thumb at
a time. One file, `workqueue10/src/workqueue10/resources/outcome.clj`,
holding **both** kinds, plus their entry in `main.clj`'s resources
vector. **No framework file was touched**, which is what the landing
order promised of this bead.

### The name, and everything this section supersedes

**The kind is `:outcome`; its child is `:outcome_piece`.** `mealplan10`
has declared `:kind :plan` since era 1 — the week's meal plan, with
`plan_day`, `prep_task` and `grocery_list` all carrying refs to it —
and one engine means one registry. **waymark-5c4** decided the name
before this bead staged a row, because renaming a kind after it has a
table is not a rename but a migration plus a law revision on every row
that ever cited it.

Everything above this section that reads `plan` as *the composed
bundle* is superseded, and the substitution is mechanical:

| the spec above says | as it landed |
|---|---|
| `:kind :plan`, `plans` | `:kind :outcome`, `outcomes` |
| `:kind :plan_piece`, `plan_pieces` | `:kind :outcome_piece`, `outcome_pieces` |
| `plan_id` | `outcome_id` |
| `plans-are-few` | `outcomes-are-few` (the cap) + `a-recomposition-waits-its-turn` (the floor) |
| `the-plan-is-still-offered` | `the-outcome-is-still-open` |
| the grant scope's `"plan"` / `"plan_piece"` | `"outcome"` / `"outcome_piece"` |

`mealplan10`'s `:plan` is untouched. `jfv.4`'s population, card key and
census entry, and `jfv.5`'s grant scope, read the new keywords.

Three other names moved with it, all cosmetic: the parent's leash field
is `good_until` (as the design section had it), the piece's verdicts are
`take` / `not_this` / `moot` and the parent's are `make_it_so` /
`not_this_week` / `expire` (unchanged), and the composer stamp is
`composed_by` on **both** kinds — the piece carries its own rather than
reaching up to the bundle's, because a four-eyes wall that read the
parent would be a wall a piece staged by somebody else walked straight
through.

### What the landing decided that the design section did not

**The materializable set is `[:task :event]`, and the smallness is the
decision.** The design section wrote `task`, `chore_run`, `event`,
`grocery_list`, `media`, `…`; the reference composition composed
exactly three sorts of thing — errands, calendar holds, grocery lines —
and only two of them are rows a composer can honestly birth:

- **`grocery_list` is out** because a grocery *line* is not a row at
  all. It is an item inside a list, added by that kind's own
  `add_item`, and `target_action` is `:create` in v1 — so the honest
  spelling of *buy the stock* today is a task that says so. The list
  itself also demands a `plan_id` ref into mealplan10 and passes
  `plan-is-planned`, which is a meal-planning door rather than a
  composer's.
- **`chore_run` is out** because a run is an occurrence of a chore the
  house **declared**, and its schedule is the chore's own law. A
  composer birthing one-off runs would be editing that schedule
  sideways.
- **`media` is out** because a shelf entry is a wish, not friction
  pre-paid. Adding one costs the household nothing and moves no
  gradient, which is the whole test this epic applies.

It grows by law revision, which is the point of it being declared:
widening the set is a change a reader can see, not a field a composer
fills in. `:touches` is built from the same vector
(`touched-creates`), so the advertisement and the enum cannot drift.

**The weekly cap's boundary is Monday 00:00 UTC** —
`store/utc-week-start`, called by name so there is never a second
truncation, and the refusal names the next Monday. Two per **author**,
counting rows, `insights-are-capped`'s shape one window up. The
alternative (a rolling 168 hours) was cheaper and would have made the
sentence a lie: the house reads *two a week* and means the week it is
in.

**`plans-are-few` split into two guards.** The design section gave one
wall both jobs — the weekly cap and the supersession floor. They landed
as `outcomes-are-few` and `a-recomposition-waits-its-turn`, because
each refusal names exactly one fix, which is the tree's own habit
(`recipe_proposal` has five create walls, not one with five arms). The
floor also refuses a superseding outcome whose predecessor is **still
offered** — recomposing something nobody has declined yet is asking the
same question twice. *(History as of 2026-08-26: `outcomes-are-few` is
gone (waymark-1uv.3) and `a-recomposition-waits-its-turn` keeps only its
shape arms — the date became the rank's input (waymark-1uv.10).)*

**The backoff chain is carried in `declined_count`, inherited at
birth.** `not_this_week` stamps `not_before` from
`tickler/next-offer` — the same pure function, `now` handed in — and
`stage-the-outcome` reads the superseded row through `ctx :read` so the
count carries down the chain instead of resetting each time the
composer rephrases. A week, three weeks, two months, half a year, then
half a year forever.

**`a-bundle-is-small` is a CEILING only, and the floor has no door.**
The parent is born before any piece exists, so no create door can count
what has not been written yet. The ceiling (refuse the sixth piece)
stands at the piece's create door, counting siblings. The floor — *an
outcome with one piece is a finding; publish an insight* — is the
composer's duty in prose (`jfv.5`) and the population's judgment at
offer time (`jfv.4`), and it is filed rather than smuggled into a door
that could not hold it honestly.

**A new wall the design section did not name: `something-is-still-on-offer`.**
`make_it_so` on an outcome whose every piece has been answered would be
a tap that landed nothing while the row read `accepted` — waymark-0k4's
first recorded decision, met from the other side. It refuses by name
and points at the answer that teaches: *if the whole bundle was wrong,
say not this week*.

**No fence is supplied for the fan-out, and the absence is the rule
rather than a waiver.** 0k4 handed `feed_recipe`'s `:revise` an
`:if-match` because *an `:edit` implies the fence*. A piece's `take`
declares no edit and takes no input, so `:safety :fence` is absent and
there is nothing to waive — the piece's own terminal state is the
boundary, and the engine's natural replay answers a double tap with the
first tap's own response rather than a second task.

**`the-outcome-is-still-open` carries no `:judges`.** It stands at the
piece's create door (where the outcome arrives in the input) *and* at
`take` (where it is already on the row and the action takes no input at
all), and `check-guard-declarations` refuses a `:judges` on an
input-free action — rightly. Its subject is named as what it truly is,
the outcome **row**, through `:reads`.

### The two declines, proved

| tap | the row it moves | what the composer learns |
|---|---|---|
| `not_this` on a **piece** | that piece → `declined` | the composition was wrong — do not bring this part back |
| `moot` on a **piece** | that piece → `moot` | nothing, deliberately: beside the point now |
| `not_this_week` on the **outcome** | outcome → `declined`, every still-offered piece → `moot` | the **timing** was wrong; no piece is graded, and `not_before` says when the house will hear it recomposed |
| `make_it_so` | outcome → `accepted`, every still-offered piece → `taken` | the pieces still standing were right |

`moot` is offered on a piece in its own right and carries **only** the
agent wall — no four-eyes, because nothing is created and nothing is
refused, so there is nothing there for a stager to grade. Whether the
card shows it as a third chip is `jfv.4`'s shape question.

### Where the law is proved

**Seven check-tier scenarios**, judged with no database in the same
breath as the usability warnings, all on the **decline** doors — which
is not a soft choice. A conformance-tier *action* scenario stages its
subject through the kind's own create door **as the walker**, which
would stamp the walker's id into `composed_by` and make the four-eyes
wall answer about the wrong person (`recipe_proposal` recorded this
exactly). So the two verdict walls are proved where they can be proved
honestly, one guard object across five doors:

- `the-composer-does-not-answer-its-own-outcome` / `…-its-own-piece`
- `an-agent-does-not-answer-an-outcome` / `…-a-piece`
- `an-answered-outcome-does-not-come-back`, `a-taken-piece-does-not-come-back`
- `a-live-outcome-is-not-expired-out-of-the-way`

**Three conformance-tier scenarios**, attempted **as a person**
deliberately (the staging walls judge the body, so they say the same
thing to whoever wrote it; who may reach the door at all is the grant's
question and a different sentence): `an-outcome-with-nothing-behind-it-is-refused`,
`an-outcome-names-a-value-this-house-holds`, and
`a-piece-fits-the-door-it-will-knock-on` — a composer that invented a
field name the calendar has never heard of.

**Ten deftests in `workqueue10/test/workqueue10/outcome_test.clj`**, over
the real ring handler and the household's whole registry, for
everything a declaration-time world cannot answer: the routing checked
against a real value's own loved words; a retired value taking its
outcomes with it; the cap Monday to Monday; the tap materializing a
task with the **member** on its create transition and the composer
nowhere in it; the partial accept (decline one, `Make it so`, the
declined one untouched); `not this week` mooting the rest and stamping
the floor; and **atomicity** — a piece the target's own `one-due` guard
refuses at the tap rolls the whole thing back, so the outcome is still
`offered` and both pieces still `offered`, and the way out is the two
taps the design promised.

`make check-queue` goes from **33 to 35 kinds** and **32 to 39
scenarios judged**, with the battery's warning count unchanged at 11 —
both kinds report clean.

### Recorded here, for whoever comes next

- **Two new kinds are two new tables.** Production needs `outcomes` and
  `outcome_pieces` created before the deploy that serves them.
  `make migrate-queue-prod` prints the plan (read-only, and it refuses
  `APPLY` on purpose); a person runs the statements through
  `nomad alloc exec -task postgres <alloc> psql -U workqueue -d workqueue10`.
  Two `CREATE TABLE`s and eight `CREATE INDEX`es, with promoted columns
  `f_composed_by / f_value_id / f_supersedes / f_good_until /
  f_not_before` on the parent and `f_composed_by / f_outcome_id /
  f_target_kind` on the child. Nothing here touched production.
- **Exactly two fingerprints appeared and nothing moved.** `outcome`
  and `outcome_piece` are new; every other kind's hash is
  byte-identical, verified by computing the whole census with and
  without this bead's two files. The census in
  `workqueue10/test/workqueue10/fingerprint_stability_test.clj` went
  33 → 35.
- **`prepared` is `[:map-of :keyword :any]`, the first free-form map in
  a row schema anywhere in this tree.** It is safe because the enum
  above it closes the KIND: the shape varies, the target does not. It
  is judged at staging by the target's own create model in the target
  door's own three steps and order — decode, `apply-defaults`,
  `closed-errors` — read off `ctx :rdef-of` rather than copied, so it
  cannot drift from the door it is about. `demand/field-class` reads an
  object as `recall`, which is correct and costs nothing: the create
  form is an agent's, not a card's.
- **A fan-out refusal names the target kind's guard, not which piece.**
  When `make_it_so`'s inner create is refused, the household reads the
  target's own sentence (`one-due`, `ends-after-it-starts`) — which is
  right, because that is the law that actually refused — but nothing
  says *which of the four pieces*. A handler has no refusal spelling to
  attribute it with, and rewriting another kind's problem document
  would be the second-opinion anti-pattern this bead is careful about.
  The way through is the pieces' own taps. Filed.
- **An outcome nobody answered stamps no floor.** `not_before` follows
  a **decline** only; an `expired` outcome may be recomposed the next
  morning. That is deliberate for v1 — non-engagement is
  `waymark-8um.1`'s signal and `8um` law 4's work order, not a clock's
  — but it is the one gap in the backoff and it is written down here
  rather than discovered later.
- **Neither kind declares `:owns`.** A piece's lifecycle is answered by
  its own doors and the parent's fan-out; a cascade edge would have
  made `make_it_so`'s `:touches` a lie by omission and given `moot` a
  second, silent driver. The `pieces` link is a filtered collection
  href, which is what the household's own screen wants anyway.

## Built — jfv.4, the outcomes section (2026-08-25, waymark-jfv.4)

The crown, on the feed. **`:outcomes` joins `feed/census` on top** —
`[:outcomes :do_now :decide :fuel :seam :archive]` — and everything else
this bead touched follows from that one literal moving.

### The join list, as it landed

Every site the design section named, plus three it did not:

| site | what changed |
|---|---|
| `feed/census` | `:outcomes` first; the docstring says why it is the only member above do-now |
| `feed/outcomes` | the population (below) |
| `feed/populations` | `:outcomes outcomes` — the registry entry |
| `feed/population-says` | *"what this week could hold — composed bundles, with the friction already paid, waiting on a thumb"* |
| `feed/population-reads` | **nothing**, and deliberately — see below |
| `feed/card-says` | the section clause, quoting `crown-and-floor` verbatim |
| `feed/default-recipe` | `{:section :outcomes :population :outcomes :take 2 :says …}` |
| `feed/recipe-guarantees` | **new**: the sections sentence now READS `census` instead of spelling it |
| `feed_recipe/order-schema` | the `:section` help now reads `census` too — the enum already did |
| `135-feed-screen.js` | `FEED_SECTION_LABEL` / `FEED_SECTION_HINT`, and the pieces sub-list |
| `test/packs.clj` | the `above-seam` literal, hard-coded twice, is now one derivation |
| `scripts/ui-drive.mjs` | the census literal, twice in one expression, is now one `CENSUS` |

`entry-cards`' `admits?` / `keep?` needed no new branch, exactly as the
design section predicted: a bundle with no verb left is retired at offer
time instead.

**`population-reads` gets NO entry, and that is a correction to the design
section's `[:nav]`.** The population reads a clock, another row's state and
a count of its own children — not one of them is a declared *trait*, which is
what that map is for (`asks`, `letters`, `ticklers`, `conflicts`, `insights`
and `proposals` all declare nothing for the same reason). And `:nav` in
particular would have made the citation say *"outcome is `:nav :system` —
house machinery rather than anybody's next step"* at the top of the page,
which is the one trait word that means the opposite of what the section is
for.

**Three fingerprint-free literals were widened by DERIVATION rather than by
editing.** `recipe-guarantees`, `order-schema`'s `:section` help and the
pack's `above-seam` all named the census in prose or in a set; all three now
compute it. A fourth (`ui-drive.mjs`) named it twice inside one comparator.
The next section to join the census edits one vector.

### The population

`feed/outcomes` names `:outcome` and `:outcome_piece` by keyword and answers
`[]` when the engine holds either without the other — the fourth and fifth
optional application kind core names, and the tipping point the spec already
recorded stands where it was.

Four filters and three retirements, each read off a declaration:

- `offered` bundles only; the other three states are terminal.
  **(Ruling, 2026-08-28, waymark-9xn: there is a fifth state now, `iterating`,
  and it is NOT terminal — a bundle the household handed back for a re-plan
  leaves this population by the same filter and comes back when the composer's
  `rework` returns it to `offered`. See § *Built — 9xn*.)**
- **not the reader's own** — `the-composer-does-not-decide` makes the stager
  structurally incapable of answering, so carding it would offer three doors
  that all answer 409. `asks`, `insights` and `proposals` already do this.
- **the leash is still on** (`good_until`), the proposal's own read-side
  retirement, with no sweeper and no write.
- **the value is still held** — a retired value takes its outcomes with it,
  which is `names-a-value` read at offer time.
- **something is still on offer** — a bundle whose every piece is answered
  would card `Make it so` over a tap that `something-is-still-on-offer`
  refuses, and a button that fails is worse than one that was never offered.
- **the bundle floor**, `feed/bundle-floor` = 2. This is **waymark-jfv.7**'s
  population half, taken here because it was one `<=` on a count the
  population already had: *an outcome with one piece is a finding — publish
  an insight.* The ceiling stays at the piece's create door where it can
  count siblings; the floor could never stand at a door, because the parent
  is born before any piece exists. jfv.7 keeps its prose half for `jfv.5`.

Each candidate carries a `:sentence` — the value named, the composer's
routing prose **quoted rather than paraphrased**, and how many rows were read
— and a `:parts` vector, oldest first (the order `take-the-rest` fans out in,
so a refusal names the piece the household read first).

The cap is **not here and must not come here**. `outcomes-are-few` is two per
author per calendar week at the create door, so the composer has to rank; a
read-side window would bury what the door already let be staged and would
make the exposure floor unmeasurable. *(The door half is history since
waymark-1uv.3; the "not here" half is still law — the rank places, a window
would hide.)*

### The card carries `pieces`, and that is the one wire widening

`feed/piece-card` is `card`'s own three gates over a child row: `:row?`
first (a piece this grant does not confer is **absent**, never narrowed),
then `envelope-summary` with `:visibility`, then `split-verbs` over what
survived. A bundle whose parts are all concealed carries **no `pieces` key
at all** rather than an empty one, because a client reading `pieces: []`
would have to guess whether the bundle was empty or hidden.

Each piece carries its **own** `card_id` — `outcomes/outcome_piece/<id>` —
so a piece verb rides `origin-key` like any other card verb and
`actions-from-feed` counts the tap that happened rather than filing it under
the bundle. Under a DRAW the spelling is unchanged (waymark-mbr's
draw-not-named punt stands).

The pieces' verbs are `assent` without help: `take`, `not_this` and `moot`
are note-free and input-free, so `demand/effort` answers `"assent"` and the
≤-selection partition keeps all three under the thumb. The pack asserts it
rather than trusting it.

A real card, off the wire (trimmed to the keys this bead added):

```json
{"card_id": "outcomes/outcome/aa23a926-…", "section": "outcomes",
 "population": "outcomes", "kind": "outcome", "state": "offered",
 "summary": "One Saturday afternoon in the shop with Jack · for Making things with the boys · Offered",
 "sentence": "For Making things with the boys. It runs through the shop, which this house wrote down as something it loves — so the expensive part, getting started, is already paid. 2 rows behind it.",
 "why": {"line": 0, "rank": 1, "of": 1},
 "fields": {"composed_by": "sous", "goal": "One Saturday afternoon in the shop with Jack",
            "routes_through": "the shop", "value_name": "Making things with the boys",
            "good_until": "2026-09-01T07:17:02Z"},
 "actions": {"make_it_so":    {"effort": "assent", "label": "Make it so"},
             "not_this_week": {"effort": "assent", "label": "Not this week"}},
 "heavier": [],
 "pieces": [
  {"card_id": "outcomes/outcome_piece/b2620592-…", "kind": "outcome_piece",
   "state": "taken",
   "says": "Cut the box stock to length — twenty minutes, and Saturday opens with the glue-up",
   "actions": {}, "heavier": []},
  {"card_id": "outcomes/outcome_piece/4cae1eea-…", "kind": "outcome_piece",
   "state": "offered",
   "says": "Clear the bench Friday evening — twenty minutes, and Saturday opens with the glue-up",
   "actions": {"take":     {"effort": "assent", "label": "Yes"},
               "not_this": {"effort": "assent", "label": "Not this"},
               "moot":     {"effort": "assent", "label": "Beside the point"}},
   "heavier": []}]}
```

The taken piece is the honest half of that: it is still on the card, wearing
its state, with **no verbs** — because the projection answered, not because
the page decided.

### The crown-and-floor sentence, and where it could not go

It is quoted verbatim in `feed/crown-and-floor` and reaches every outcome
card's `?explain=1` citation through `card-says`' section clause, beside
do-now's and the archive's.

**It could not ride the recipe line's `:says`**, which is where the bead's
own framing put it: that field is capped at **400 characters** by
`feed_recipe/order-schema` — it is a household's own sentence, typed into a
form — and the paragraph is six hundred. The line carries the short version
instead, in the shape the other lines wear, and it says the floor out loud:
*"its take is the exposure floor, so the ledger below stays a fair audit of
it rather than a competitor for its place."* The cap is a real wall and
widening it to fit a paragraph the framework wrote would have been the wrong
half to move.

### The screen

`135-feed-screen.js` dispatches on `card.pieces` and never on a kind name —
`.7`'s own correction, binding doubly here because `outcome` is a workqueue10
kind and this is the framework's page. A bundle renders as: the goal as the
heading, the value and routing as the say-line, the composer's principal id
as a badge (the `bylineChip` posture, unchanged — an agent is never dressed
up as a person), the evidence read late as live links, then a `<ul>` of
pieces each with its own state chip, its own sentence and its own chips, and
finally `Make it so` / `Not this week` as the card's own.

Three behaviours are worth writing down:

- **A piece settles ITSELF.** `scope: "piece"` replaces that line's verb bar
  and leaves the rest of the bundle standing — which is what a partial accept
  *looks like*, one row at a time.
- **A piece's refusal stays on the piece.** The target's own create guards
  judge at the tap, and the household has to read which LINE was refused.
- **The bundle's verdict answers the pieces too.** `settle` clears every
  piece bar to *"answered with the bundle"*, because the server took them in
  one transaction and chips offering a closed door are a lie.

`030-screens.css` and `040-mobile.css` gained the piece styles and one
section rule: the crown wears `--law`, the colour of the one card here that
was authored.

### One declaration moved, and it moves no hash

`outcome`'s `:display {:title "Outcome"}` became `{:title "{data.goal}"}`.
A static noun was fine while the kind had no card; the feed's crown takes its
heading from `:display :title` like every other card, and a card at the top
of the page headed *"Outcome"* would have been the one element there that
said nothing. `:display` is advertisement and rides no `fingerprint-of`
facet — verified, not assumed: the whole 35-kind census was computed with and
without this bead and **every hash is byte-identical**.

### Where the law is proved

**`:feed/outcomes`**, a new obligation in `packs/feed`, gated on
`#{[:route :feed] [:kind :value] [:kind :outcome] [:kind :outcome_piece]}`
and placed below every obligation that counts cards — it mints more rows than
any other (a value, a bundle, three pieces, and the work rows two of those
pieces become) and its bundle cards **above** everything. It ends with the
bundle accepted and every piece answered, so the population retires it and
the engine it hands on is the engine it found.

**Three principals**, and none decoration: a MEMBER declares the value
(`written-by-a-person` stands at that door, so the composer could not have
written what its own bundle cites); a COMPOSER, an agent leashed to two
create doors and nothing else, stages; and a THIRD reader holds a leash over
`outcome` and **not** over `outcome_piece`, which is how the new `pieces`
key's concealment is watched rather than asserted.

Its claims, in order: the bundle cards in `outcomes` with nothing above it;
its sentence cites the routing; it carries three pieces; the parent offers
`make_it_so`; a piece offers `not_this`; no piece verb is heavier than
`card-ceiling`; a piece's `card_id` is its own; **the composer sees nothing**;
the half-sighted reader sees the bundle and **no pieces**; a decline settles
one piece; a tap materializes a row whose **create transition names the
member**; the origin key parses back to `outcomes` / `outcome_piece` / the
piece's id with no join; `make_it_so` takes the piece still offered; and the
answered bundle leaves the feed. `workqueue10.conformance-test` now asserts
its **coverage** is positive, because an obligation whose whole claim is that
a tap wrote something proves nothing over zero taps.

**Two driver widenings**, both small and both the same shape as something
already there:

- `fac/create-body` — the body `create-example` would send, without sending
  it, exposed on the driver ctx as `:create-body`. A piece's `prepared` is a
  CREATE's input the way `:input-for` is an action's, and staging a piece
  with a hand-written map that happens to fit today's schema would prove
  something narrower than the law.
- `packs/create-actor` — the CREATE transition's actor rather than the newest
  one's. A mirrored kind pushes to its authority the instant a row lands and
  appends a sync transition on top, so `newest-actor` answered `"mirror-sync"`
  about a task the member had just made. `newest-actor` is still right for
  `feed_recipe`, which is not mirrored; this is the same question one kind
  over.

**Two fixtures gained three tables.** `conformance_test` and
`feed_shape_test` now drop `outcome_pieces`, `outcomes` and `values` — the
rule both files already state, for the same two reasons: an outcome another
run left `offered` is a card ABOVE do-now, and a `value` is never terminal so
it outlives everything. `feed_shape_test` found this by failing.

**`feed_shape_test`'s two do-now line assertions now find their lines by
SENTENCE rather than by index.** They pinned line 0 and line 1; a section
above them made both wrong. A test that pins a place on the page was pinning
the wrong thing — the line the household wrote is the thing being asserted.

### Verified by hand, and the walk is written down

`waymark10/scripts/ui-drive.mjs feed` against `make dev-queue`: **41 checks,
no console errors** (was 32). The feed walk now seeds a value, a bundle
composed by `sous` and two prepared pieces, and adds eleven checks:

- the bundle cards in `outcomes` with nothing above it, carrying a piece list;
- its heading is the goal, not the kind's name;
- it says the value it serves and cites the loved activity;
- the composer rides as a principal id;
- it names what the composer read (two rows, each a live link);
- two pieces render as sub-rows, each with `Yes` / `Not this` /
  `Beside the point`;
- the bundle offers exactly `Make it so` and `Not this week`;
- no piece verb is heavier than a tap;
- **the tapped piece settles on its own line and its chips do not come back**
  (`✓ Yes · now taken`);
- **the other piece is untouched** — a partial accept is one row at a time;
- the piece's verb carried the PIECE's card id in the origin key
  (`feed/2026-08-25/outcomes%2Foutcome_piece%2F…/79377f672e76`), and the row
  it wrote is really there;
- and, reading as `sous`, **the bundle does not card to whoever composed it**.

The whole deal-again block runs after all of that and passes unchanged, so
the crown behaves under a draw.

**One fixture bug fell out of running the walk on a FRESH database.** The
`links.next continues the SAME draw` claim needs more than six archive cards
and the walk retired three chores; on a dev database that had been
accumulating for weeks it passed by accident. It retires eight now.

### Recorded here, for whoever comes next

- **No new kinds, and the migration plan is empty** —
  `make migrate-queue` says *"storage matches the declarations"*. Production
  needs nothing before this deploys that jfv.2 and jfv.3 did not already ask
  for.
- **Every fingerprint is byte-identical.** 35 kinds before, 35 after,
  computed with and without this bead. `feed_recipe`'s `:section` enum gained
  a word and did not move, which is 0k4's correction re-verified rather than
  re-asserted; `outcome`'s `:display` changed and did not move either.
- **`make check-queue` is unchanged**: 35 kinds, 11 warnings, 39 scenarios.
  This bead declared no scenario, because everything it added is a READ over
  a live engine and `scenario.clj` never writes.
- **The raw store row's `:state` is a KEYWORD.** `rows-of` and `load-raw`
  answer `{:state :offered}`, not `"offered"` — `open?` has always spelled it
  `(keyword (:state row))` and this population's first draft did not. It cost
  an hour and an empty section; written down so it costs nobody else one.
- **A `rows-of` whose where-key is not a promoted column answers `[]`
  silently** (the `catch Exception _ []` is deliberate — a kind whose table
  this engine never made must not fail the whole read). It is the right
  posture and it is also the reason an empty population is hard to diagnose.
- **The exposure floor is a rule, not yet a mechanism.** Nothing ranks, so
  the line's `:take` is trivially honored. What **waymark-8um.3** inherits is
  fixed here: the formula may not reduce this line below its take while
  candidates exist, a card held by the floor says so in its `why`, and the
  population keeps NAMING candidates it did not show so `claimed_above` stays
  truthful. It names all of them today.
- **A piece's `says` rides the wire from the POPULATION, not from the
  projection.** It is the `sentence` precedent one row down. A grant that
  concealed the `says` field specifically (rather than the row) would leave
  the piece's own sentence on the card; the row-level concealment that
  matters is exact, and this one is filed rather than defended.
- **`moot` renders as a third chip.** jfv.3 left the shape question open;
  the answer is yes, because the projection offered it and a page that
  dropped a door the reader holds would be lying in the direction `heavier`
  exists to prevent.

## Built — jfv.10, values learned in the open (2026-08-25, waymark-jfv.10)

**The owner's ruling, verbatim, 2026-08-25:**

> Discovering what you value and what you love to do is a process and there's
> nothing wrong with you learning what my values are and writing them to
> waymark — just so long as I can adjust them too.

It overrules the first law of § *The value kind* and of `value.clj`'s
own docstring — *declared is law, learned is evidence that files asks*,
enforced by `written-by-a-person` at every door the kind had. The
§ *Built — jfv.2* section above stands as history and is not edited;
what follows supersedes it wherever the two disagree.

**What is superseded, exactly:**

| jfv.2 said | as it stands after jfv.10 |
|---|---|
| `written-by-a-person` at **every** door | off `create`; still on `revise`, `still_stands`, `retire`, `restore`, `dismiss` |
| `:states [:declared :retired]`, `:initial :declared` | `[:observed :declared :retired]`, `:initial :observed` |
| `reviewed_at` / `reviewed_by` (the petition stamp) | `affirmed_at` / `affirmed_by` (the same tap, its honest name) |
| an agent's only path is an `insight` | an agent WRITES the value; the insight is still its only path to **affirming** one |
| `names-a-value` admits `declared` only | admits `observed` too, and the card says which |
| the grant scope's `{"kind": "value", "actions": []}`, *never `value` create or revise* | `{"kind": "value", "actions": ["create", "restate"]}`; **never `still_stands`, never `revise`, never `retire`** |
| the `an-agent-does-not-declare-a-value` scenario | inverted to `an-agent-writes-what-it-observes` |

The rest of jfv.2 — `:nav :secondary`, free-word `loved`, scope and the
owner stamp, the insight-shaped petition, the `still_stands` tap taking
no input — is untouched.

### The affirmation is a STATE, and the weighing is the bead

A stamp was the cheaper answer and it lost. `affirmed_by` / `affirmed_at`
alone (the `reviewed_by` shape jfv.2 already had) would have kept **one**
`revise` door and cost nothing. It loses on the requirement that decided
the bead — *an unaffirmed value must SAY so wherever it is cited*:

1. **`summary/render` has no conditional.** A missing field renders an
   em-dash, never a sentence, so a value's own summary line cannot speak a
   stamp's absence. `{state}` speaks it for free, in household words, on
   every envelope, every list line and every transition record.
2. **The affirmation becomes a TRANSITION**, which is how the row shows
   *both hands*: `create` by the agent, `still_stands` by the member, on
   one row's log. A stamp would have left the row reading `declared` from
   birth with no machine memory of having been a guess.
3. **`:filterable {:state #{:eq :in}}` already exists**, so *what has been
   observed and not yet affirmed* is a query this house already owns. A
   stamp would want a null-filter the framework does not have.
4. **`outcome/names-a-value` and `feed/value-still-held?` already read
   state**, so admitting an observed value widens a check rather than
   adding a field read.

`:on-create` branching the birth state is the framework's own spelling
(`definitions`: a held revision is born `:proposed`). **The initial is
`observed` and a person's create promotes out of it**, deliberately: a
birth nobody's hand can be found on reads as a guess, which is the safe
direction for a bug to fail in. The engine's own actors — a seed, a
migration, the conformance walker — land `observed` for the same reason.

### The cost, paid in the open: the wording door splits by hand

`:to` is a static keyword, so **one door cannot land in two states**. A
single `revise` open to both hands would have had an agent's own
rewording land in `declared` — the observer affirming its own guess,
which is the one thing this bead exists to forbid. So:

- **`revise`** — *Reword*, `#{:observed :declared} → :declared`, walled by
  `written-by-a-person` and `this-is-yours-to-declare`. A person's hand,
  and rewording an observed value **claims it in the same stroke** — the
  ruling read literally.
- **`restate`** — *Correct what was observed*, `:observed → :observed`,
  walled by the new `only-the-observer-restates` (the mirror: it refuses a
  PERSON). The observer's own door. The same input, the same overwrite, no
  affirmation stamp: the row is still a guess when the handler is done.

Both walls are pure functions of the principal, so the render probe and
the invoke read the same fact and **each hand is offered exactly one
wording door** rather than two that look alike. That is the recorded
deviation from the bead's point 1, which asked for the wall to come off
`revise`: it could not, because `revise` is where the affirmation
happens.

**Agent revise on an affirmed row: refused, and the sentence points at
the petition.** The bead asked this to be decided honestly and the answer
is no — rewording an affirmed value overwrites the owner's chosen words.
`restate` covers the legitimate case (the observer learns more) without
touching anything a person said.

### The one remaining wall, and its new sentence

`written-by-a-person` now guards the affirmation rather than the writing:

> Affirming is a person's word. You may write down what you observed —
> that row is born observed and says so wherever this house cites it — but
> marking your own reading affirmed would be speaking in the owner's voice
> about the owner's own life. Leave it observed and say what you found:
> publish an insight, cite the rows you read, and offer this value's own
> "yes, this one's ours" as the one next step. The owner answers with a
> tap, or rewords it himself, and either way the row becomes his.

`still_stands` is now one tap doing two jobs, and both are honest: from
`observed` it is the whole affirmation; from `declared` it is jfv.2's
*I read the evidence and it stands anyway*. Its label moved to **"Yes —
this one's ours"** so it reads truthfully from either side.

### A hole the ruling opened, closed by its own wall

`stamp-owner` writes the WRITER's id, so an agent writing a `"mine"`
value would own a sentence about somebody else's life — and
`this-is-yours-to-declare` would then refuse the very person it is about,
breaking the ruling's one condition. **`a-private-value-is-a-persons-own`**
stands at the create door: an observer writes what the HOUSE holds, and
if it turns out to be one member's alone he says so when he claims it.
Every `observed` row in this engine is therefore `household`-scoped by
construction, which is why `restate` carries no ownership wall.

### `dismiss` — the other answer to a guess

`retire`'s `:undo :restore` must return exactly where it departed from,
and `restore` lands in `declared`, so an action leaving from either
`observed` or `declared` could not declare one. The household's reason is
the better one anyway: **retiring a value you held and telling an observer
it read you wrong are different sentences**, and the composer reading the
log should be able to tell them apart. `dismiss` runs
`:observed → :retired`, label *Not one of ours*. Both land in `retired`,
because a value this house is not holding is a value this house is not
holding — and `names-a-value` refuses either with one sentence.

A restored value comes back **`declared`**, not `observed`: a person
reaching for `restore` has held it again with his own hand, so it stamps
like every other landing in `declared`.

### The observed clause, where the person answering is looking

`feed/outcome-says` gained one clause, and `feed/value-still-held?`
became `feed/value-standing` (`:declared` / `:observed` / nil) so the
population reads the standing once and hands it to the sentence. A real
card sentence, off the wire:

> For unhurried Saturdays — a value observed in your record, not yet
> affirmed, so say whether it is yours before a week goes to it. It runs
> through the shop, which this house wrote down as something it loves.
> 1 row behind it.

Silence would have been the composer borrowing the owner's voice through
the card instead of through the value. The moment the owner taps, the
same bundle stops saying it — nothing about the outcome changed, only
what is true about the value under it.

### Where the law is proved

**Nine check-tier scenarios on `value`** (was five), and all four
refusing guards are now named by one:

- `an-agent-writes-what-it-observes` — create, as an agent, **allowed**.
  The inversion of jfv.2's `an-agent-does-not-declare-a-value`.
- `an-agent-does-not-affirm` — `still_stands` as an agent, refused.
- `an-agent-does-not-reword-what-the-owner-affirmed` — `revise` as an
  agent, refused.
- `an-agent-corrects-what-it-observed` — `restate`, allowed.
- `a-person-rewords-rather-than-restates` — `restate` as a person,
  refused by `only-the-observer-restates`.
- `an-observed-value-is-the-houses` — an agent's `"mine"` create, refused.
- `a-person-amends-what-the-house-declared`,
  `somebody-elses-value-is-not-yours-to-reword`,
  `a-petition-is-answered-with-one-tap` — jfv.2's, unchanged.

**Six deftests in `workqueue10/test/workqueue10/value_test.clj`**, over
the real ring handler and the whole registry, for everything a
declaration-time world cannot answer: the birth state and its stamps; the
affirmation with **both hands in the transition log**; the two wording
doors and neither hand reaching the other's; the private-value wall; the
**observed clause on a real outcome card**, appearing and then
disappearing when the owner taps; and `dismiss` retiring a wrong reading
so `names-a-value` stops composing against it. **Every agent in that file
holds a leash** — `packs/leash!`'s own sentence: an unleashed agent is
already 404 by the router's default deny, which proves nothing about any
wall.

`make check-queue` goes from **39 to 43 scenarios judged**; 35 kinds and
11 warnings, both unchanged. `value` reports clean.

### Recorded here, for whoever comes next

- **Exactly one fingerprint moved.** `value`:
  `e6943e49…` → `39108e56…`, one legitimate law revision carrying the new
  state, the split wording doors, the new walls and the renamed stamps.
  Every other kind is **byte-identical**, verified by computing the whole
  35-kind census with and without this bead.
- **`outcome` did NOT move, and that is a finding rather than a relief.**
  `names-a-value` is a **create** guard, and `fingerprint-of` projects
  only `machine.actions.*.guards.*` — a create door's law changes mint no
  revision and show in no diff. **waymark-442.9** already files it; this
  bead is a second witness and did not fix it here.
- **Production: 5 migration steps, no state work, and no rows at risk.**
  `make migrate-queue-prod` prints `ADD COLUMN f_affirmed_at / f_affirmed_by
  / f_written_by` and `DROP COLUMN f_reviewed_at / f_reviewed_by` on
  `values` — all five generated columns, regenerable from the document.
  **The `values` table holds zero rows in production** (confirmed by
  count), so the `reviewed_*` → `affirmed_*` rename is free. Adding a state
  token needs no DDL at all: `state` is plain text and the boot's
  `check-state-tokens` compares live tokens against the declaration, and
  `observed` is an addition rather than a removal.
- **A value's scope cannot be changed after birth.** `authored-fields` is
  `[:name :says :loved]`, so an observed household value the owner wants
  as *his own alone* can only be retired and re-declared. Filed.
- **waymark-04t got sharper and was not decided here.** *Should a private
  value's `still_stands` be owner-only?* is no longer only a question about
  a stamp: the tap now MOVES the row into `declared`. Its two readings are
  unchanged; the stakes are not.
- **The composer's grant scope for jfv.5 widens by exactly two actions.**
  `value: ["create", "restate"]`. Never `still_stands`, never `revise`,
  never `retire` or `dismiss` — the affirmation and the owner's own words
  stay behind the one wall this bead left standing. jfv.5's walk step 1
  (*the owner declares his values first, by hand*) is now optional rather
  than a precondition: a composer may write what it observes and compose
  against it the same day, and the card will say so until he answers.

## Built — jfv.11, the person kind (2026-08-25, waymark-jfv.11)

**Why it exists, in one incident.** A composer read this house's record,
found a woodworking build, found a caregiver's name in the same
neighbourhood of rows, and composed *build the finger-joint box with
him* — a Saturday afternoon with a son. He is a grandparent's CNA.
**Every row the composer read was correct**; the relationship it
assembled out of them was invented, because relationships were nowhere
in the record. The owner's own reaction — *we may even need a resource
for this* — is this bead.

`person` is a **roster**, not a genealogy and not an address book: their
everyday name, how they relate to this house in the owner's own words,
who they relate **through**, when they were born if the family knows,
and whether they are in this house's life now or were. Its whole job is
that a sentence with a name in it can be **checked**.

### The declaration, as it landed

```clojure
{:kind :person
 :plural "people"
 :nav :secondary
 :states [:observed :current :past]
 :initial :observed
 :terminal #{}
 :summary "{data.name} · {data.relation} · {state}"
 :label-template "{data.name}"
 :links [{:rel "through" :kind :person
          :href "/api/people/{data.through_id}"}]
 :schema [:map
          [:name        {:sort :default}                     [:string {:min 1 :max 80}]]
          [:relation    {}                                   [:string {:min 1 :max 80}]]
          [:through_id  {:optional true :kind :person
                         :label :through_name :filter #{:eq}} [:maybe :waymark/ref]]
          [:through_name {:optional true}                    [:maybe [:string {:max 80}]]]
          [:born        {:optional true}                     [:maybe :waymark/date]]
          [:written_by  {:optional true :filter #{:eq}}      [:maybe [:string {:max 128}]]]
          [:affirmed_at {:optional true :sort true}          [:maybe :waymark/instant]]
          [:affirmed_by {:optional true :filter #{:eq}}      [:maybe [:string {:max 128}]]]]
 :create-schema [:map [:name …] [:relation …] [:through_id …] [:born …]]
 :filterable {:state #{:eq :in}}
 :create-guards [relates-through-somebody-here]
 :on-create born-into
 :actions
 {:revise        {:from #{:observed :current} :to :current   ; "Put it in your words"
                  :guards [only-a-person-says-who-we-know
                           relates-through-somebody-here]}
  :restate       {:from #{:observed}          :to :observed  ; "Correct what was observed"
                  :guards [only-the-observer-corrects
                           relates-through-somebody-here]}
  :still_with_us {:from #{:observed :current} :to :current   ; "Yes — still with us"
                  :guards [only-a-person-says-who-we-know]}
  :now_past      {:from #{:observed :current} :to :past      ; "That's past now"
                  :guards [only-a-person-says-who-we-know]}
  :dismiss       {:from #{:observed}          :to :past      ; "Not somebody we know"
                  :guards [only-a-person-says-who-we-know]}
  :restore       {:from #{:past}              :to :current   ; "With us again"
                  :guards [only-a-person-says-who-we-know]}}}
```

### `born`, not `age` — decided and recorded

An age is a fact about the morning somebody typed it. *the middle boy,
son, 7* is true today, quietly wrong next spring, embarrassingly wrong
in three years — and this kind exists precisely so a composer stops
working from numbers nobody checked. A **date** never goes stale, it is
what a family already knows about its own children, and it is the only
spelling that can answer the question a planner asks next (*is
somebody's birthday inside this week?*). A birth **year** was the middle
road and loses on the youngest person in a house: a year is ±12 months,
and twelve months is the whole difference between a one-year-old and a
two-year-old.

**Optional, and the optionality is the honest half.** Nobody writes down
a grandfather's birthday to satisfy a form. An empty `born` says *this
house does not track that about them*; a guessed year would have said
something false with a number's confidence. There is no `age` field at
all — an age stored beside a date is a second answer waiting to
disagree with the first.

### `through` — a ref, not a name

The caregiver does not relate to this house. He relates to the
grandfather, and the house relates to the grandfather. **That is the
fact the composer did not have**, and it is one optional ref carrying
the maintained `through_name` garnish (`value_id`/`value_name`'s label
doctrine, one field over) so a card reads `Bram · Odell's CNA · through
Odell` without a join.

A free-text `through` would have been *the same class of thing this bead
is about* — a name in a sentence that nothing checks. The roster is
small and is seeded in dependency order, so
**`relates-through-somebody-here`** refuses a dangling ref at every door
that can write one, and refuses a row that relates through **itself**.
Deeper rings (A through B through A) are **not** walled and are recorded
rather than pretended about. Filed (`waymark-jfv.15`).

### The affirmation machine: two axes, one column

`current` / `past` is the bead's own ruling — *a CNA who leaves is a
transition worth a record*, not a boolean that flips in silence. jfv.10's
observed/affirmed axis rides the **same column**, for jfv.10's own
reason: `summary/render` has no conditional, so a missing stamp renders
an em-dash and can never speak its own absence, while `{state}` speaks
on every envelope, every list line and every transition record;
`:filterable :state` then makes *who has been written down and not yet
answered* a query this house already owns.

The combination the column cannot hold is **`observed` AND `past`** — an
unaffirmed departure — and it is not a loss, because nothing may plan
with a `past` person and nothing may plan with an `observed` one either.
`observed` means *somebody wrote this down and the house has not
answered*; the house's answer is **which door the row walks out
through**.

**Inherited from jfv.10 whole:** the wording door splits by hand
(`revise` is a person's and claims the row; `restate` is the observer's
and leaves it a guess), both walls are pure functions of the principal
so each hand is offered exactly one, and the birth state is a `:on-create`
hook — an agent's row lands `observed`, a person's lands `current` and is
stamped in the same breath.

**Two costs paid differently from jfv.10:**

1. **`now_past` leaves from `observed` too, and therefore carries no
   `:undo`.** An `:undo` must return exactly where it departed from, so
   jfv.10 split `retire` (from `declared`, with an undo) from `dismiss`
   (from `observed`). Here the departure from `observed` is **the first
   case, not an edge case**: the motivating row is a caregiver an agent
   found in the record who has *already left*. Making the owner affirm
   her as current on the way to past would write a lie for one
   transaction. `restore` is its own door, landing in `current`.
2. **`dismiss` survives for the household's reason rather than the
   mechanical one.** *She left* and *that is not somebody we know* are
   different sentences, and a composer reading the log must be able to
   tell a **staffing change** from a **bad guess**. Both land in `past` —
   the one place the mirror strains, since `past` carries a *was here
   once* that a hallucination never earned. The doors keep them apart;
   the state only keeps them out of plans.

### What jfv.10 has that this kind does not, and why

- **No scope.** A value carries `household` / `mine` because it is a
  sentence about one member's inner life. A person is a fact about the
  household's **world**, and there is no honest *mine* reading of *he is
  the grandfather's CNA*. So no scope, no owner stamp, and no
  `a-private-value-is-a-persons-own` — which is also why `restate` needs
  no ownership wall. `written_by` stays; it answers the question scope
  never did.
- **No `member_id`.** Persons are **not** members: a member is a login
  principal and most of these people will never log in. The link looks
  like one cheap optional field and is not — setting it is an **identity
  assertion**, and the hand most likely to reach for it is the
  observer's, which is the one hand that must never make one. An honest
  link needs its own wall, and that wall is worth writing when something
  reads the link. **Filed** (`waymark-jfv.12`).
- **No `:default-filters`.** A roster opening on `state=current` would
  hide exactly the rows that need a person.

### The composer contract hook: `outcome.companion_id`, checked

**Decided: add the checked field now.** The miscomposition that filed
this bead is the argument — prose alone would let it happen again, and a
roster nothing consults is a document. `outcome` gains
`companion_id` (optional ref to `:person`, with the `companion_name`
label garnish) and one create wall, **`names-a-person`**:

- **absent → allowed**, and that is the common case. Grandpa's paperwork
  is nobody's afternoon but the owner's, and a door that *demanded* a
  companion would teach the composer to invent one — which is the bug.
- **not there → refused**, naming `/api/people` and the fix.
- **`observed` → refused**, and **this is where jfv.10's widening
  honestly stops.** `held-states` admits an observed *value* because
  refusing would have made the owner's ruling a permission with nothing
  behind it. That argument does not carry: a wrong value is a wrong
  sentence about what somebody cares about; a wrong **person** is the
  exact failure this wall exists to stop, and an agent that could compose
  against its own unanswered reading of who somebody is would make the
  wall paper. The refusal names the lawful path (publish an insight,
  offer that row's own tap). **Consequence: no card sentence is needed
  and no feed file was touched** — an observed person can never reach a
  card.
- **`past` → refused**, with the relation and the finding in the
  sentence. This is the bead's own insight: the dropped cleaning cluster
  of Aug 8–15 is assigned to a caregiver who left. *A plan built around
  somebody who is gone is not a plan that needs pushing harder.*

**What it does not check is FIT**, and saying so is the whole of the v1
scope. Whether a CNA is the right person for a box in the shop is a
judgment, not a predicate, and a guard that pretended otherwise would be
a second opinion about the household's own life —
`the-prepared-input-fits-the-door`'s own refusal, one kind over. The
relation is on the row to be **read**, the wall's `:open` sentence says
to read it, and holding a composer to it is **jfv.5's contract**.

**`companion_id` carries no `:filter`, deliberately.** Only
`filterable ∪ sortable` fields become generated columns, so a filter
would move `outcome`'s **storage** facet and mint a law revision on a
kind whose machine did not change — for a query nothing asks yet. Filed
(`waymark-jfv.14`).

### Where the law is proved

**Six scenarios on `person`** — three check-tier (`still_with_us` twice,
`now_past` once) and three deferred to the suite (`restate` as a person,
`revise` on a `past` row, and a dangling `through` at create). All three
refusing guards are named by one.

**A framework finding, recorded because it shaped the scenario set: a
conformance-tier scenario cannot be attempted AS AN AGENT.** The walker
presents no grant, the router default-denies with 404, and
`packs/wire-verdict` reads a 404 as *unreadable* on purpose — a
hide-flagged guard conceals rather than narrates, so no scenario may name
one through the door. Every door this kind lets an agent **write**
through checks the `through` ref, and checking it means reading the
roster, so *an agent may write down somebody it found* and *an agent may
correct what it observed* have **no expressible scenario**. They are
proved over the real handler by an agent **holding a leash** instead,
which is the stronger sentence anyway. Filed as `waymark-zs9`.

**`names-a-person` names no scenario either, and that is structural
too.** A scenario's `:input` is a literal map and a `:given` row's id is
minted by the walker, so no scenario can cite a row it staged — the only
arm reachable is a dangling companion, and `names-a-value` stands in
front of it and refuses the same body first.

**Eight deftests in `workqueue10/test/workqueue10/person_test.clj`**, over
the real ring handler and the whole registry: the birth state and its
stamps (including `born` round-tripping as a date); the answer with
**both hands in the transition log**; the two correcting doors and
neither hand reaching the other's; `through` checked at both ends
(dangling refused, self refused, the good one carrying `through_name`,
and dropping it really clearing); the departure straight out of
`observed`; `dismiss` distinguished from `now_past` in the log; and
**all three arms of `names-a-person`** plus the two allowed ones.

`make check-queue` goes from **35 to 36 kinds** and **43 to 46 scenarios
judged**; 11 warnings, unchanged. `person` reports clean.

### Recorded here, for whoever comes next

- **Exactly one fingerprint moved, and it is the new one.** `person`
  lands at `12a4a98e…`; every other kind is **byte-identical**, verified
  by computing the whole census before and after. **`outcome` did not
  move** (`5de724bc…`) even though it gained two schema fields, a link
  and a create guard: `fingerprint-of` projects
  `machine.actions.*.guards.*` and a storage facet built only from
  promoted columns, so a create door's law and an unfiltered field are
  both invisible. **waymark-442.9** files that; jfv.10 was the second
  witness and this bead is the third.
- **Production: one new table, four steps, no state work, nothing at
  risk.** `make migrate-queue-prod` prints
  `create-table people: CREATE TABLE IF NOT EXISTS people (…)` with five
  generated columns (`f_affirmed_at`, `f_affirmed_by`, `f_name`,
  `f_through_id`, `f_written_by`) and three indexes (`ix_people_flip`,
  `ix_people_law`, `ix_people_state`). **`outcomes` needs no DDL at
  all** — `companion_id` and `companion_name` are document fields with
  no promotion.
- **The roster is seeded THROUGH THE DOOR, after the deploy, by the
  family.** It is production data and appears in no file in this tree;
  the cast in `person.clj` and `person_test.clj` is invented and says so.
  Order matters: anybody who relates **through** somebody must be written
  after them, because the ref is checked.
- **The composer's grant scope for jfv.5 widens by exactly two actions
  on this kind.** `person: ["create", "restate"]`. Never
  `still_with_us`, never `revise`, never `now_past`, `dismiss` or
  `restore` — who is in this family's life is the family's sentence, in
  both directions.

  **AMENDED 2026-08-28 (waymark-sfe).** The owner ruled on the wall
  itself, not on the composer's scope:

  > The whole reason we have the access controls we have is so that I
  > can ask you to do what I want when I want. It doesn't make sense to
  > disallow it, it just makes sense to permission it.

  So the sentence above is now about the STANDING composer scope rather
  than about what is possible: `only-a-person-says-who-we-know` refuses
  an agent **unless the grant it presents admits that very door**, and
  the sitting composer's standing grant still names only `create` and
  `restate`. What changed is that the owner may hand a delegate
  `person.still_with_us` for an afternoon, through the
  `approval_request` door, and read afterwards which grant it acted
  under (the transition's actor carries `grant`). One half stays
  absolute and is four eyes rather than permission: **the agent that
  WROTE the row never answers it**, whatever it holds — an observer
  answering its own reading is the failure this kind exists to stop.
- **A person's `relation` carries no filter**, deliberately: exact-match
  on free prose is a trap, and the words there are the family's rather
  than a vocabulary.
- **The roster does not populate the feed and owes no pack obligation.**
  Nothing about a person is a thing to do, and the feed's job is what the
  house could do next. A roster screen with the unanswered rows on top is
  the obvious first ask; that is when a population and its obligation are
  earned. Filed (`waymark-jfv.13`).

## Built — jfv.16, the taps learn to speak (2026-08-25, waymark-jfv.16)

The owner's ruling: *we should be able to reject with feedback, so the
system can learn over time beyond just inaction why we're not
engaging.* It landed as **one framework kind and one word of
advertisement**, and the whole shape of it was decided by a constraint
this document already carried.

### The constraint, and what it kills

`demand/effort` reduces an action to its **worst input field**. An
optional `[:maybe [:string {:max 240}]]` renders `anyOf [string, null]`,
`demand/field-class` reads that as `recall`, and `feed/split-verbs`
moves anything heavier than `feed/card-ceiling` out of `actions` and
into `heavier` — a link, not a thumb. That is waymark-iqa.4's second
finding (the sugar's `:note`) and jfv.3's second (a `[:vector [:enum]]`
of pieces), and it is the same finding a third time.

Three shapes were weighed against it and **two are refused by this
tree's own gates rather than merely being heavy**:

| shape | verdict |
|---|---|
| (b) an OPTIONAL input on the verdict door | **Dead twice.** It changes the demand class, so the one-tap decline becomes a dialog (`135-feed-screen.js` opens `actionDialog` for any entry carrying `input`); and the row a decline leaves behind is **terminal**, so there is no second firing to supply the reason with — the engine's replay answers a second tap with the first tap's own response. |
| (c) the reason as an AMENDMENT — a `:record true` revision on the declined row | **Refused by name.** `checks/check-terminal-no-exit` errors on any action whose `:from` intersects `:terminal`. Terminal states carry no actions in this framework, self-loops included. The bead asked whether the machine allows a terminal self-loop; it does not, and the refusal is a definition ERROR rather than a warning. |
| (a) a ROW OF ITS OWN | **Wins.** A create is always open — there is no state to be out of — and naming the subject as `{subject_kind, subject_id}` (the tickler's shape) makes **one kind serve every verdict in the house**. |

### Two layers, and the first one is one more tap

1. **ON THE CARD.** The decline stays exactly what it was: input-free,
   `assent`, one tap. After it **settles**, the settled bar grows four
   chips — `wrong_time` / `wrong_piece` / `wrong_way` / `never_this`,
   rendered as **"Wrong time" · "Wrong piece" · "Not this way" ·
   "Never this"**. Tapping one is a `POST` that creates a row. Tapping
   none is a complete answer and writes nothing.
2. **ONE SCREEN DEEPER.** `words` is free text in the member's own
   voice, optional at birth and addable afterwards through `say_more`
   — an `:edit` of one prose field, which is `composition` by
   construction, so it can never climb back onto a card. The link to it
   is what the collapsed chip hands over.

**The quick word is REQUIRED and the sentence is not**, and that order
is the design rather than an accident. A row exists because a chip was
tapped; the sentence deepens a word already on the record. The
alternative — both optional, with a wall refusing the row that said
neither — was written and taken out again: it made a `composition`
field guard-judged, which is precisely what `usability/effort-honesty`
warns about (a blank box where a picker belongs), and it bought a case
the four words already cover by picking the closest and saying the rest
underneath.

### Where it lives: `waymark10.verdict-reason`, in the `:feed` module

`feed_view` is the precedent and the reasoning is its own
(waymark-8um.1): a record a **screen** posts, about cards the feed
itself minted, named in `server/feed.clj` as a keyword and enrolled
`:always`. The chips are drawn by `135-feed-screen.js`, which is the
generic page and knows no application's kind names — an application
kind would have made the framework's own screen reach for a name only
one deployment has. The kind itself names **no application vocabulary
at all**, which is what lets it answer a declined piece, a declined
bundle, a let-go tickler and a dismissed finding under one law.

```clojure
(defresource verdict-reason
  {:kind :verdict_reason
   :plural "verdict_reasons"
   :nav :system
   ;; ONE STATE, and deliberately NOT terminal: the second layer is an
   ;; edit of this row, and check-terminal-no-exit refuses an action
   ;; out of a terminal state by name.
   :states [:noted] :initial :noted :terminal #{}
   :unique [[:subject_kind :subject_id :verdict]]
   :summary "{data.verdict} on {data.about} · {data.reason}"
   :links [{:rel "subject" :href "/#{data.subject_href}"}]
   :schema [:map
            [:subject_kind {:filter #{:eq}} [:string {:min 1 :max 64}]]
            [:subject_id   {:filter #{:eq}} [:string {:min 1 :max 64}]]
            [:subject_href {:optional true}  [:maybe [:string {:max 500}]]]
            [:about        {:optional true}  [:maybe [:string {:max 200}]]]
            [:verdict      {:filter #{:eq}} [:string {:min 1 :max 64}]]
            [:reason       {:filter #{:eq}} reason-enum]
            [:words        {:optional true}  [:maybe [:string {:max 600}]]]
            [:said_by      {:optional true :filter #{:eq}}
                                             [:maybe [:string {:max 128}]]]]
   :own-surface {:by :said_by :actions #{:say_more}}
   :on-create stamp-the-sayer
   :create-guards [a-reason-is-your-own one-reason-per-verdict]
   :actions
   {:say_more {:from #{:noted} :to :noted
               :input [:map [:words {:optional true
                                     :x-display {:widget "prose" …}}
                             [:maybe [:string {:max 600}]]]]
               :edit {:prefill [:words]}
               :record true
               :handler write-the-words
               :display {:label "Say more" :order 1}}}})

(def reasons
  [["wrong_time"  "Wrong time"]
   ["wrong_piece" "Wrong piece"]
   ["wrong_way"   "Not this way"]
   ["never_this"  "Never this"]])
```

Two walls, and they are `feed_view`'s two, one law over.
`a-reason-is-your-own` refuses a body that names somebody else —
**nobody explains another member's no**, which matters more here than
anywhere because this is the one kind whose whole purpose is to be read
back later as what somebody meant. `one-reason-per-verdict` is the
household's sentence and `:unique [[:subject_kind :subject_id
:verdict]]` is the fact under a race — `feed_view`'s belt and braces
exactly.

### The wire: a door on the document, a word on the action

Neither half is derived and neither is hard-coded.

- **Where to send it** rides the DOCUMENT, beside `views`:
  `feed/reasons-doc` answers `{post_to, field, choices, says}`, with
  the address read off the kind's own `:plural` (`collection-of`) and
  the choices read off the create model's own `:enum` and
  `:x-display {:choices …}`. A fifth word declared server-side is a
  fifth chip with nothing else changed, and the chip on a card and the
  select on a form render one vocabulary. **Unlike `views` it has no
  preview clause**, and that is the honest difference: `views` says
  whether a screen may beacon about somebody else's page, while a
  reason is an ordinary write under the tapper's own name — exactly as
  a verb chip is.
- **Whether to ask** rides the ACTION, as `:display {:reasons true}`.
  `:display` is advertisement: `fingerprint/action-fp` projects
  `from`, `to`, `safety`, `guards`, `handler`, `input_defaults` and
  `touches`, and **not** `:display` — so the flag moves no hash. It
  also rides the ordinary grant projection, which means **a decline a
  reader does not hold carries no chips for the same structural reason
  it carries no verb.**

### The v1 set, and the two deliberate absences

| door | why |
|---|---|
| `outcome.not_this_week` | the timing was wrong, and *which* wrong timing is what a recomposition needs |
| `outcome_piece.not_this` | the teaching refusal; this is the bead's centre |
| `tickler.let_it_go` | the household-wide decline that sticks |
| `insight.dismiss` | the finding the house did not want |

`outcome_piece.moot` carries none **on purpose**: that verdict's whole
meaning is *there is nothing here to learn*, so offering four words
that teach a composer something would be the verdict contradicting
itself on the same line. `insight.take` carries none either, and the
asymmetry is the point — a composer learns from what the house turned
down; why somebody said yes is the work itself, on its own rows.

`tickler.not_now` was in the bead's candidate list and is **not** in
v1, for a mechanical reason worth keeping: a not-now is said again and
again by design, and `one-reason-per-verdict` holds one row per
`(subject, verdict)`, so only the first of four could ever carry a
word. Filed (`waymark-jfv.18`) rather than smuggled.

`value.dismiss` and `person.dismiss` are **ready and not wired**, and
the honest reason is that neither kind cards: they have no population,
so their verdicts are answered on the row's own screen, and the row
screen (`160-resource-surface.js`) does not render reason chips. The
mechanism is kind-agnostic and the flag is one word; the missing half
is a screen, not a law. Filed (`waymark-jfv.19`).

### The composer read — 8um.4's input now exists

`:own-surface {:by :said_by :actions #{:say_more}}`: the person who
said it reads their own and may add to them, with no grant. A composer
reads them through an ordinary grant the household approves by name —
`{:kind "verdict_reason" :actions []}` — the insight precedent, which
confers reading and nothing else because there is nothing else to
confer. **That grant is the input waymark-8um law 4's diagnosis duty
has been waiting for: non-engagement made of words instead of
silence.**

**A `{:actions []}` grant is not the whole of the read-only story, and
the pack found it.** `grants/visibility`'s `:action?` answers the
own-surface affordance at **kind** level —
`(contains? (:actions (own-of k)) a)`, with no row in the question —
so a composer holding a read grant over this kind is **advertised**
`say_more` on rows that are not theirs. That advertisement is the
framework's shape and was not this bead's to change; what *is* this
bead's is that the door **refuse**. `the-reason-is-your-own-hand` is
that refusal, in the household's own words —
*read it, learn from it, recompose against it — but the words in it
stay theirs* — and without it a read-only diagnosis grant would have
carried a quiet edit on the very sentences it was granted to read.
Worth knowing for every future kind that pairs `:own-surface
{:actions #{…}}` with grantability: **the advertisement is kind-level,
so the row-level wall has to be declared.**

### Where the law is proved

- **Two scenarios, one per wall about whose words these are.**
  `nobody-explains-somebody-elses-no` defers to the suite — and the
  deferral is the guard *chain's* rather than the wall's:
  `a-reason-is-your-own` reads only the caller and the body, but a
  create scenario is judged against the whole chain and
  `one-reason-per-verdict` reads rows.
  `nobody-rewrites-somebody-elses-reason` is judged at **check tier**,
  with no database, because `say_more` carries exactly one guard and it
  reads the principal and the row.
- **`:feed/verdict-reasons` in `packs/feed`**, below `:feed/outcomes`
  because it mints a second bundle and answers it the *other* way —
  every piece declined and the week refused. Nineteen claims, and the
  load-bearing ones are the shape rather than the words: the decline
  carries **no input** and reads `assent`; the decline advertises
  `display.reasons` and the **accept does not**; a reason posted
  against an already-terminal row lands `201` (the create-is-always-open
  proof); the row names its subject kind, its subject id, its verdict
  and its word, and is stamped with the tapper; a **second** reason for
  the same verdict is `409` by `one-reason-per-verdict`; a reason filed
  under somebody else is `409` by `a-reason-is-your-own`; a **second
  piece declined with no reason at all** stays `declined` and leaves
  **zero** rows behind; a composer holding `{:actions []}` reads the
  row; that same composer's attempt to **rewrite** it is `409` by
  `the-reason-is-your-own-hand`; and `say_more` reads `composition`,
  which is heavier than `feed/card-ceiling` — the second layer is a
  screen by construction. `workqueue10.conformance-test` asserts its
  **coverage is positive**, beside `:feed/outcomes`' own: an obligation
  that ran over zero reasons would be a green run over this bead's own
  sentence.
- **`ui-drive.mjs`'s feed walk**, extended: the second piece is
  declined in the browser, no dialog opens, the settled line grows the
  four household words, one is tapped, the chips **collapse to the word
  that was chosen** and hand over a `#/api/verdict_reasons/…` link, the
  row is read back over the API with its verdict and its word on it,
  and the POST is checked for the feed's own `Idempotency-Key`. Run
  against a fresh dev database: **49 checks passed, no console
  errors**, and it is what caught the `about` line reading
  *"Piece of an outcome"* on every reason row — a piece's `says` is
  what it IS, and its `display.title` is the kind's static heading.
- `make check-queue` goes from **46 to 47 scenarios judged**, with
  **36 kinds** and **11 warnings** both unmoved. The kind count does
  not move because `check/report` counts the APPLICATION's own
  resources and `verdict_reason` is the framework's; it appears in the
  listing as `verdict_reason (enrolled) ✓`. The battery is at zero for
  this kind, and getting there is the record of two design decisions
  rather than three cosmetic fixes — see the required-quick-word
  paragraph above.

### Recorded here, for whoever comes next

- **Exactly one fingerprint appeared and nothing moved.** The whole
  census — the household's own kinds plus everything the module table
  enrols — goes **48 → 49**, and the one new line is
  `verdict_reason 8213d8c5…`. Every other kind is **byte-identical**,
  including the four that gained a `:display` key: `outcome`
  `5de724bc…`, `outcome_piece` `672d914f…`, `tickler` `d2b11408…`,
  `insight` `d5b2724b…` — the same prints they had at `HEAD`, computed
  both ways. That is the legitimate shape of an advertisement change:
  `action-fp` never reads `:display`.
- **One new table, and it is the FRAMEWORK's, so every waymark engine
  grows it.** `make migrate-queue-prod` prints one
  `CREATE TABLE verdict_reasons` with generated columns `f_reason /
  f_said_by / f_subject_id / f_subject_kind / f_verdict`, plus four
  standard indexes and **one unique index**,
  `ux_verdict_reasons_subject_kind_subject_id_verdict`. A person runs
  the statements through
  `nomad alloc exec -task postgres <alloc> psql -U workqueue -d workqueue10`
  before the deploy that serves them. Nothing here touched production.
- **Six framework kind-name assertions moved**, and every one of them
  moved the same way `feed_view` moved them: `verdict_reason` joins the
  own-surface list every leashed principal sees on
  `.well-known/waymark`, because a reason is the sayer's own.
- **A reason may outlive its subject, and that is the tickler's own
  accepted cost.** Nothing here reads the subject — a marker naming any
  row in the house cannot ask a kind-specific question of it, and a
  wall that tried would be a wall that guessed. `verdict` is a plain
  string for `feed_view`'s reason one field over: a record whose schema
  refused an action name the engine has since renamed would be a record
  nobody could write.
- **There is no way to say why about a decline you made yesterday from
  the card**, because the card is gone. The generic create form at
  `/#/api/verdict_reasons` is always there and takes the same four
  fields, so the path exists; it is simply not a thumb. That is the
  honest v1 and it is written down rather than discovered later.
- **AMENDED 2026-08-28 (waymark-sfe): whose hand may tap the verdict
  these words ride on.** The owner ruled:

  > The whole reason we have the access controls we have is so that I
  > can ask you to do what I want when I want. It doesn't make sense to
  > disallow it, it just makes sense to permission it.

  `a-person-answers` no longer refuses every agent outright; it refuses
  one that presents no grant admitting the door it is knocking on
  (`outcome.not_this_week`, `outcome.make_it_so`, `outcome.iterate`,
  `outcome_piece.take` / `not_this` / `moot`). Nothing about this
  section's mechanism changes — `:reasons` is still advertisement, the
  tap is still input-free and `assent`, the reason still rides as its
  own `verdict_reason` row a screen deeper — and the reason kind's own
  `stamp-the-sayer` now records a delegate honestly, because the
  transition it hangs off carries the grant it was made under. **Four
  eyes did not move**: `the-composer-does-not-decide` still stands
  first on `make_it_so`, `not_this_week`, `take` and `not_this`, and
  `iterate` and `moot` — which never carried it, because refusing every
  agent gave it to them for free — grew it inside `a-person-answers`
  (`:own-field :composed_by`). A composer can never answer its own
  bundle, grant or no grant.

## Built — jfv.17, the impact line (2026-08-25, waymark-jfv.17)

The owner's discomfort, verbatim, is what this bead is downstream of:
*I'm not yet comfortable using the crown because I'm not sure what
impact the actions will have.* Every piece card carried the
**composer's** prose — `says`, what the piece IS — and nothing the
**engine** said about what the tap DOES. It landed as **one derivation
function, one optional field, and two quiet lines on the card**, and
no fingerprint moved.

### The rule it is built on, and why the function had to be shared

`waymark-jfv.9`'s ruling: *the IMPACT STATEMENT is engine-written at
staging (the `recipe_proposal` diff posture — the engine's reading of
what the tap will do, in household words; the agent's description
never stands alone).* Two consequences, and both are structural.

**It is written at STAGING, onto the row.** `stamp-the-composer` — the
piece's on-create hook — now writes two stamps rather than one, and
the second is `impact`. It runs *after* `the-prepared-input-fits-the-
door`, which is the whole reason it can be trusted: create guards are
judged before `:on-create`, so by the time the hook reads `prepared`
that map has already been decoded, defaulted and closed against the
very create model the tap will knock on. A sentence about an input the
door would refuse is a sentence that never gets written, because the
row does not.

**But the same function has to be reachable at the READ**, and that is
what decided where it lives. `feed.clj` is the framework and
`outcome.clj` is an application, so the derivation went into the
framework — `waymark10.server.feed/piece-impact`, beside `outcome-
says`, which is the other engine sentence on this same card — and the
application resource requires it. `waymark10.recipe_proposal` already
reaches for `feed/recipe-diff` from a resource namespace for exactly
this reason, one kind over.

### The derivation, and every word of it read off a declaration

```clojure
(defn piece-impact [prdef trdef prepared]
  (when (and prdef trdef (map? prepared))
    (let [noun  (kind-noun trdef)
          label (prepared-label trdef prepared)]
      (str (or (affirming-verb prdef) "This") " will create one " noun
           (when label (str ": " (pr-str label)))
           " — in this house's own record"
           (mirror-clause trdef noun)
           ". Nothing else."))))
```

and the real sentence a piece carries today:

> Yes will create one task: "Cut the box stock to length" — in this
> house's own record, and at the source it mirrors to, the way any
> task does. Nothing else.

Four private helpers, and none of them spells an application's word:

- **`affirming-verb`** — the label of the kind's own **primary-styled**
  action. `Yes` and `Make it so` are workqueue10's words and this is
  the framework's page, so the sentence *borrows* them. Rename the tap
  and the sentence renames with it.
- **`kind-noun`** — `:display :title` when that heading is a **static**
  noun, and the kind's own name otherwise. A templated title
  (`{data.title}`) is the ROW's name rather than the kind's, and a
  sentence that dropped one in would name the same row twice. `task`
  and `event` both template, so both read their keyword.
- **`prepared-label`** — the target's own `:label-template` rendered
  over the prepared body, which is the same template `invoke/label-of`
  writes into every labelled ref: **the name in the sentence is the
  name the house will read on the row it lands as.** Both
  materializable kinds declare `{data.title}`, verified rather than
  assumed. Keys are keywordized shallowly (a `prepared` map crosses
  the wire as an object and arrives either way) and the label is
  clamped at 200 characters.
- **`mirror-clause`** — below.

**The shape is a function of the piece's TARGET FORM, one arm per
form, and the enum closes at one today: `:create`.** jfv.9's general
piece — `{kind, row id, action, prepared}` — slots its own arm in
beside this one (*"Yes will `<action>` `<that row>`: `<the engine's
diff>`"*) without touching it, which is why the create arm is written
as an arm rather than as the whole function.

### The mirror clause: what a `:mirror` declaration actually holds

The bead asked for the clause to name the authority if the declaration
carried a display name for it, and to say something honest if it did
not. **It does not.** `mirror/declaration` mints a `Spec` whose keys
are `adapter / ttl-seconds / discover-every / document / push-on-write
/ create-push / local-rows / priority / on-gone / resync-every` —
machinery, every one of them, and `fingerprint/authority-fp` reads
them by name. There is no household word for *Google Tasks* anywhere
in it; the adapter is a protocol object. So the clause **names the
source without naming it**:

> …and at the source it mirrors to, the way any task does…

Hard-coding `Google Tasks` here would have been the framework's own
page saying a word only one deployment's adapter knows. **The day a
mirror declaration carries a household name for its authority,
`mirror-clause` is the one line that has to change** — recorded here
so it is a one-line change and not an archaeology.

**The condition is `:create-push`, not `:mirror`.** A pull-only mirror
could not have been born by this tap at all, and a `:push-on-write`
kind without `:create-push` pushes *edits* rather than births. `task`
and `event` both declare it, which is exactly why the clause is true
of them.

### The bundle's own line, and why it cannot be stored

`make_it_so`'s confirmation story is a second line, on the bundle
card, in the same voice:

> Make it so = all 3 pieces still on offer in this bundle, taken
> together — and nothing that has already been answered.

**It is computed at the read and not at staging, and the reason is
structural rather than a preference:** the parent row is born before
any piece exists (`a-bundle-is-small`'s floor has this same problem
and `bundle-floor` is where it landed), and the union CHANGES as
pieces are answered. `bundle-impact` counts the pieces still `offered`
at that read — the same set `take-the-rest` will fan out over inside
the transaction — so one decline and one tap leaves it reading *the
one piece still on offer*. That is a claim in the pack.

**It states a COUNT and never a piece's content**, which is what lets
it ride ungated: a reader whose leash names the bundle and not its
parts sees no `pieces` key at all, and telling that reader their own
`make_it_so` would take three is a true statement about **their own
tap** rather than a projection of rows they do not hold. Gating it on
the surviving pieces was considered and refused — the tap really does
take all three, and a line that said *two* would be the card lying to
make a projection tidy.

### Where it renders, and the decision that it renders at all

**Visibly, on the card, not behind a disclosure** — the bead's own
ruling and the owner's discomfort's own answer: a sentence behind a
toggle is a sentence nobody read. A piece's line sits in `pieceLine`
between the composer's prose and the chips (`.fpiece-impact`); the
bundle's sits between the pieces list and the bundle's own verbs
(`.fcard-impact`), directly above the button it describes. Both are
quieter than the say-line and in the **sans** face, because the
difference between them is whose sentence it is — the serif is
somebody's writing, this is the machine reading itself back.

**`impact` is one wire key at both levels**, beside `says`, so
`135-feed-screen.js` renders one thing in two places and jfv.9's
future arm needs no new key. **The battery stays at zero**: the field
is out of the create model, so `demand/effort` never sees it and no
verb's weight moved. `make check-queue` reads **36 kinds, 11 warnings,
47 scenarios judged** — every number unmoved.

### The pieces already on offer, and the answer that needed no backfill

**Four pieces were offered in production when this law landed**,
staged before it existed and carrying no such sentence. The field is
therefore **optional**, and `feed/piece-impact-of` runs the identical
derivation at the read for any piece that has none:

```clojure
(or (some-> (get-in pd [:data :impact]) str not-empty)
    (piece-impact prdef (get (resources ctx) target-kind) prepared))
```

So the four live pieces gained their line on the next morning's feed
and **nothing was written to get it there.** A backfill was the
alternative and it was refused by name: writing the engine's reading
onto those rows from *outside* the staging door that owns it is
precisely the property this bead exists to establish. `outcome-test`'s
`the-engine-says-what-the-tap-will-do` manufactures the case —
`store/update-data!` strips the stored line, no transition, no version
bump — and reads the card back.

**The line is only on a piece still OFFERED.** It describes a tap, and
an answered piece has no tap left to describe; a future-tense sentence
over a settled row would read as an offer the card is no longer
making. Also a pack claim.

### Staleness needs nothing new, and here is why

The stored line plus the existing re-judge at the tap already cover
the world moving, and the reason is that **the line is a function of
`{target_kind, prepared}` and neither of those can change after
staging.** `prepared` is written once at birth and has no door that
edits it; `target_kind` likewise. So there is no world-state the
sentence could go stale *about*: what can go stale is whether the
target's own guards will still let the row be born, and that is judged
at the tap by those guards, whose refusal is what the household reads
(`materialize`, and § *Validated at staging, judged again at the tap*).
A second staleness oracle over another kind's law is the thing this
file has refused twice already. Nothing more was needed and this
paragraph is the record of asking.

### Where the law is proved

- **Six claims added to `:feed/outcomes`** rather than a new
  obligation, because they are claims about the crown's own card and a
  second bundle would have been a second crown in the deck the
  counting obligations read. Every piece still on offer carries a
  line; **it names the row the tap would create** (the load-bearing
  one — a line saying only *this makes a task* would render and teach
  nothing, and the obligation renders the target's `:label-template`
  itself so it is a witness rather than a second call to the thing
  under test); a mirrored target's push is named; the bundle states
  the union; the union **moves** to *the one piece* after a decline
  and a tap; and an answered piece carries no line at all. The naming
  claim was proved to FIRE by deliberately dropping the label from the
  derivation and watching the obligation refuse.
- **`workqueue10.outcome-test/the-engine-says-what-the-tap-will-do`**,
  over the real ring handler, for the half a wire document cannot
  answer: the line is on the ROW (written at staging, not at the
  read); it names the prepared title, opens with the verb the
  household will tap, carries the mirror clause and closes with
  *Nothing else*; **a composer that puts `impact` in its own create
  body gets 422** — the field is out of the create model, so the door
  refuses it rather than ignoring it, which is what makes *the
  engine's reading* a fact about the row instead of a promise about
  the composer; and the read-time fallback over a row whose line has
  been stripped.
- **`ui-drive.mjs`'s feed walk**, extended by four checks: both pieces
  state what their own tap will do, the two prepared titles appear in
  the two lines, the mirrored consequence is named on both, and the
  bundle's own line reads *Make it so = all 2 pieces*. Run against a
  fresh dev database: **53 checks passed, no console errors.**

### Recorded here, for whoever comes next

- **No fingerprint moved, and it was computed both ways rather than
  argued.** The whole census — 36 kinds — is **byte-identical** to
  `HEAD`, `outcome_piece` included. `impact` declares no `:filter` and
  no `:sort`, and only `filterable ∪ sortable` becomes a generated
  column, so `store/kind-projection` renders the same table, the same
  columns and the same indexes; the storage facet is the only one a
  plain schema field can reach, and `:display`, `:x-display` and prose
  ride none. (442.9's witnesses, applied: a schema change that adds no
  column adds no law.)
- **The migrate plan is EMPTY.** `impact` lands in the `data` jsonb.
  `make migrate-queue` against a `workqueue10_dev` whose
  `outcome_pieces` predates this bead prints *storage matches the
  declarations — empty plan*, and the table still carries exactly
  `f_composed_by / f_outcome_id / f_target_kind`. **Production needs
  no DDL for this deploy.**
- **`impact` is the same sensitivity class as `says`, and the gate was
  already right.** Both carry the prepared work's own words — in fact
  `impact` carries the prepared TITLE, which `says` does not — so both
  ride `piece-card`'s three gates, and a piece a reader does not hold
  is absent, sentence and all. The bundle's line is the exception and
  it is a count, argued above.
- **A kind with no primary action, or no label template, still gets a
  sentence.** `affirming-verb` falls back to *This* and the naming
  clause simply drops. Neither arm fires for `task` or `event`, but a
  future materializable kind that labels its rows some other way will
  read *This will create one thing — in this house's own record.
  Nothing else.* rather than crashing or lying, and that is the
  correct failure: less said, nothing false.
- **`offered?` is now spelled once in `feed.clj`** and read by three
  things about one card: the bundle's candidacy, its union line, and
  whether a piece's line has a tap left to describe. It was inline
  before and would have been inline three times after.

## Built — jfv.9, the open piece (2026-08-25, waymark-jfv.9)

**The owner's ruling, verbatim, 2026-08-25:**

> A piece can do whatever it wants, but I just need to be able to inspect the
> impact — what it's actually going to do.

It overrules § *The design* → *Materialization*'s third bullet (*"`target_kind`
is an **`:enum`** the application declares … `target_action` is **`:create`**
in v1"*) and jfv.3's `materializable [:task :event]`. The § *Built — jfv.3*
section above stands as history and is not edited; what follows supersedes it
wherever the two disagree.

**What is superseded, exactly:**

| jfv.3 said | as it stands after jfv.9 |
|---|---|
| `target_kind` is a closed `:enum` of `[:task :event]` | a plain `[:string {:min 1 :max 64}]` — any kind this engine serves |
| `target_action` is `:create`, implicitly and only | a `form` field, `create` or `invoke`, EXPLICIT on the row |
| `:touches` names the union LITERALLY, so *"only the input is data"* is true | `:touches` advertises the two ordinary create doors, each `:may true`, and says out loud what it cannot name |
| governance kinds are out *"by construction rather than by a blocklist"* | reachable; their own guards judge, under the member's hand |
| *"reopen the abandoned show cannot be a piece today, only a task that says so"* | it is a piece: `{kind, id, action, prepared}` |
| the enum refuses an unlisted kind with a **422** at the schema | the target's own door judges the body with a **409** at a wall |

The rest of jfv.3 — two kinds, per-piece consent, the two declines, the weekly
cap, the bundle ceiling, the leash, the four-eyes wall, one transaction per
tap, no deterministic inner key — is untouched.

### Three forms weighed, two landed, and the third is a mirage

```clojure
(def forms ["create" "invoke"])
```

- **`create`** births a row. jfv.3's whole world.
- **`invoke`** moves a row that already stands, through that row's own named
  door: `{target_kind, target_id, target_action, prepared}`.
- **`update` is NOT a third form**, and saying so is the bead's own answer to
  its own question. In this framework a rewording IS an action — `revise`,
  `restate`, `prioritize` — declared on the kind with its own `:input`, its own
  guards and its own `:to`. So an edit is the invoke arm naming a wording door,
  and a third form would have bought a second spelling for one law plus a third
  arm for the impact line to drift in.

**The form is EXPLICIT on the row, not derived from which fields are
present** — the bead's own hunch, confirmed at the walls. Three guards read it,
and a wall that had to infer its subject from an absence would be a wall that
guessed: a create carrying a stray `target_id` and an invoke that forgot one are
different mistakes and each deserves its own sentence.

**It is REQUIRED in the create model and optional in the row schema.** A
default was the cheaper spelling and lost twice: a composer that forgot the
field would silently get `create`, and the form is precisely the difference
between birthing a row and moving one; and a declared default lands in
`fingerprint-of`'s `create` facet, so the cheaper spelling would have moved a
hash to say something a required field says for free. Absent on a ROW reads as
`create`, which is what every piece staged before this law is — no backfill,
and nothing written to those rows to tell them what they already are.

### The schema, as it landed

```clojure
;; ROW
(pe :form          {:optional true}  [:maybe form-enum])
(pe :target_kind   {:filter #{:eq}}  [:string {:min 1 :max 64}])   ; ← was an :enum
(pe :target_id     {:optional true}  [:maybe [:string {:max 64}]])
(pe :target_action {:optional true}  [:maybe [:string {:min 1 :max 64}]])
(pe :target_version {:optional true} [:maybe [:int {:min 0}]])     ; ENGINE-written
(pe :prepared      {}                [:map-of :keyword :any])
(pe :impact        {:optional true}  [:maybe [:string {:max 600}]]) ; ENGINE-written

;; CREATE MODEL
(pe :form          {}                form-enum)                    ; ← required
(pe :target_kind   {}                [:string {:min 1 :max 64}])
(pe :target_id     {:optional true}  [:maybe [:string {:max 64}]])
(pe :target_action {:optional true}  [:maybe [:string {:min 1 :max 64}]])
(pe :prepared      {}                [:map-of :keyword :any])
```

**`target_id` is a plain string and not a `:waymark/ref`**, deliberately: a ref
names ONE kind at declaration time and this one is chosen at staging.
`recipe_proposal/target_id` is the same shape for a narrower version of the same
reason. **No new field carries `:filter` or `:sort`** — only `filterable ∪
sortable` becomes a generated column, which is what keeps the migrate plan
empty.

A real invoke piece, off the wire, with the line the engine wrote for it:

```json
{"kind": "outcome_piece", "state": "offered",
 "data": {
   "says": "Mark the stock cut once Friday is done",
   "form": "invoke",
   "target_kind": "task",
   "target_id": "5446dda0-00bf-4e77-8bc0-7a241245742b",
   "target_action": "complete",
   "target_version": 1,
   "prepared": {},
   "impact": "Yes will use the \"Done\" door on one task that already stands: \"Cut the box stock to length\" — in this house's own record, and at the source it mirrors to, the way any task does. Nothing else.",
   "composed_by": "ari"}}
```

Every word of that sentence is read off a declaration. `Yes` is
`outcome_piece`'s own primary label; `"Done"` is `task.complete`'s own
`:display :label`; `task` is the kind's noun; the title is the target's own
`:label-template` rendered over the target's own row; the mirror clause is
`task`'s `:mirror :push-on-write`. The composer cannot reach a word of it, and
`impact` is out of the create model, so a composer that supplies one gets 422.

### The impact line: one new arm, no new key, no new mechanism

`feed/piece-impact` was written by jfv.17 as *an arm rather than the whole
function*, with the seat beside it named and left warm. This is what sat down in
it — `create-arm` is jfv.17's sentence to the byte, `invoke-arm` is new, and the
dispatch is the row's `form`:

```clojure
(str verb " will use the " (pr-str door) " door on one " noun
     " that already stands" (when label (str ": " (pr-str label)))
     (when (and from to (not= from to))
       (str ", which reads " from " now and " to " after"))
     (carried-clause prepared)
     " — in this house's own record" (mirror-edit-clause trdef noun)
     ". Nothing else.")
```

Four decisions inside it, each recorded:

- **The mirror clause reads `:push-on-write`, not `:create-push`.** A birth and
  an edit are different pushes; a kind that pushes edits carries this tap out to
  the authority whether or not it may push births.
- **The move clause is SILENT on a self-loop and on a mirrored kind** whose
  machine state is its SYNC state. *"which stays fresh"* about a task somebody
  is completing is a true sentence saying nothing — the door's own label and
  what it carries are the change, and the rest is noise.
- **`carried-clause` renders the prepared body rather than summarizing it.** An
  input the household cannot see is exactly the half of a tap the ruling is
  about. Clamped at 240 characters.
- **A door or kind this engine does not serve yields NO line**, which is the
  same answer the card gives: no sentence rather than a guess.

**`piece-impact-of`'s read-time fallback now reads the target row**, and only
when it has to — the stored line is preferred, so the ordinary path costs
nothing.

**And the staleness paragraph jfv.17 wrote has to be amended.** It said *"the
line is a function of `{target_kind, prepared}` and neither can change after
staging, so there is no world-state the sentence could go stale about."* For the
invoke arm that is **no longer true**: the target row's label and state can both
move. The answer is not a second derivation — it is the fence below. A stale
line can never be tapped into effect, because the tap that would have acted on
it refuses first, naming the drift.

### The fence, and why an `:if-match` was not enough

Two halves, and the second one is the finding:

1. **`materialize` hands the target its own etag.** `ctx :invoke` passes
   `:if-match` (waymark-0k4's rule — a cross-write SUPPLIES the fence rather
   than waiving it), and `recipe_proposal/apply-the-order` is the spelling
   copied.
2. **…and `invoke-in-tx!` step 6 consults it ONLY when the target action
   declares `:safety :fence`**, which in this framework is implied by an
   `:edit` and absent everywhere else. `task.complete` declares none. So the
   framework's own fence would have let a stale tap through **without a word**,
   on most doors in the house.

So the wall that always fires is a guard: **`the-target-has-not-moved`** on the
piece's `take`, which is `recipe_proposal/the-order-has-not-moved` generalized
past one kind. `stamp-the-composer` writes `target_version` at staging (nobody
may supply it — a piece that could name its own version could name the one the
row is about to reach); the guard reads the row again at the tap and refuses:

> That row has moved since this was staged — `/api/tasks/5446dda0…` was at v1
> when this was staged and is at v2 now — it reads fresh today. The way through
> is two taps: not this on the stale piece, and ask for it again against what
> the house reads now.

The walk, proved end to end in `the-fence-refuses-a-target-that-moved`: stage
against v1 → somebody completes the task → tap → 409 by name, both versions in
the sentence, the piece still `offered`, and `not_this` is the way out.

### The security question, answered honestly rather than tidily

The bead asked what a piece-fired `ctx :invoke` can and cannot be refused on
compared with the member tapping the target directly. It was read off the code
rather than assumed, and here is the whole of it.

**Applied on both paths, identically** — `invoke-in-tx!` steps 3–15: the row
lock, the judgment overlay, the `:from` state check, hide-flagged concealment
(`probe-hidden-only?` → 404), the fence when the action declares one, input
validation against the action's own `:input`, **every guard**, warnings refused
unless explicitly acknowledged, the transition, and the actor — which is the
**outer** principal, the member.

**Skipped by `ctx :invoke`** — the router's request-level layer:
`router/check-row!`, `router/check-action!`, `grants/check-args!`, the leash and
its default-deny, and `grants/approval-effects!`. All four of the first read
`(:waymark10/visibility req)`, which lives on the Ring request; a handler ctx
has none. **That is waymark-iqa.18, unchanged and not fixed here.**

**Why it is equivalent protection for THIS door, and where it is not:**

- The tapper is a **member**. `a-person-answers` refuses every agent at every
  verdict of both kinds, so an agent can never be the principal on a
  piece-fired invoke.
- A human presenting no grant header gets `unscoped-visibility` → **nil**, so
  the router's four checks check nothing. For that member — which is every
  member on the household's own feed — the two paths are **the same set of
  walls**.
- **The gap that is real:** a member who IS wearing a narrowed scope (a
  presented `x-waymark-grant`, the guest door's session) would have that
  narrowing applied to their own request and **not** to a piece-fired invoke.
  Filed (**waymark-jfv.20**) rather than papered over. It is iqa.18's own shape,
  and this bead is its second witness.
- **The gap that was found and CLOSED:** `grants/approval-effects!` mints an
  approved ask's grant **post-commit, at the wire boundary**. A piece invoking
  `approval_request.approve` would have moved the ask to `approved` —
  terminally — and minted nothing: the household would read an approved ask and
  the composer would still have no leash. That is not a capability question and
  no wall about authority would have caught it.

  `grants/wire-boundary-effects` is now a `def` — `#{[:approval_request
  :approve]}` — read both by `approval-effects!` itself and by the piece's new
  create wall **`the-door-carries-its-own-effect`**, so the wall and the effect
  cannot drift. **waymark-442.14** (move the effect into the verdict handler)
  empties the set and dissolves the wall with it. It is a wall about where this
  engine keeps one effect, not about anybody's authority, and that distinction
  is the reason it is allowed to exist under the ruling.

### The governance proof pair — the ruling's whole safety story in two tests

Both in `workqueue10/test/workqueue10/outcome_test.clj`, over the real ring
handler:

- **`a-four-eyes-wall-on-the-target-holds-at-the-tap`.** Colton stages a bundle
  of his own; a second composer stages an invoke piece naming that bundle's
  `not_this_week`; Colton taps it. **409, refused by
  `the-composer-does-not-decide` — the TARGET's own wall, judged in-transaction,
  about the member whose hand is on the tap.** Nothing moved: the target is
  still `offered` and so is the piece. That wall is `g/not-the-field`, the very
  guard `desugar-decision` mints for every `:decision` kind's `:decider`, so it
  is the same wall an `approval_request` wears — proved on the door this file
  can stage both sides of.
- **`a-value-is-affirmed-through-a-piece`.** A piece names a value's own
  `still_stands`; the member taps; **200**, the value moves, and `affirmed_by`
  is the member's own id. `written-by-a-person` passes for the honest reason: a
  person is tapping.

Together they are the ruling read literally. **The wall is not the enum; the
wall is whose hand it is** — and the pair exists so that neither half can be
read alone.

### `:touches` on an open piece — the iqa.6 question, answered in the open

An open piece cannot enumerate its targets at declaration time. That is
waymark-iqa.6's refused primitive returning, and it was resolved by reading what
the framework actually enforces rather than by inventing a spelling:

- **`checks_assembly/check-touches` errors only on OVER-declaration** — a touch
  naming a kind or door that does not exist. Under-declaration is checked
  nowhere, except as a warning for `:owns` cascade edges.
- **Nothing at runtime consults `:touches`.** `ctx :invoke` and `ctx :create`
  never read the calling action's declared set.
- **The conformance pack's `:core/touches` asks the OTHER direction** — every
  declared touch must have fired under the same correlation id, with `:may true`
  tolerating absence.
- **`resource.clj` admits exactly `:kind`, `:action`, `:may`.** There is no
  dynamic-touch spelling and this bead did not mint one.
- **The worksheet is the precedent and it declares NO `:touches` at all.**
  `worksheet/apply` replays arbitrary lines through arbitrary targets and
  carries its blast radius entirely in `:safety :consequence` prose — legitimate
  there because its writes run post-commit rather than through a handler ctx.

So the resolution is the middle, and it is written down rather than smuggled:

1. **`advertised-creates [:task :event]` survives, demoted from a wall to an
   advertisement.** `touched-creates` builds `:touches` from it with **`:may
   true`** on each entry — because a given tap walks through one of those doors,
   or through neither when the piece is an invoke. (Without `:may` the pack
   would have judged every invoke tap a `:touch-did-not-fire`.)
2. **The honest statement lives in three places**, one of them machine-readable:
   `take`'s `:safety :one-way`, in the household's own words (*"It reaches
   exactly what that line names and nothing else"*); the **impact line on the
   row**, which names the exact kind, door and row this particular tap
   reaches — the per-ROW blast radius the per-ACTION declaration cannot carry;
   and `target_kind` / `target_action` / `target_id` as ordinary wire fields, so
   a client reads the pair without parsing prose.
3. **A piece that reaches some other kind is LAWFUL and simply unadvertised at
   the declaration.** That is the cost of the ruling, stated.

`packs/piece-target` was the one reader of the dead enum and now reads
`:touches` instead — a declaration either way, and never the word `task`.

### Where the law is proved

**Three new scenarios** on `outcome_piece` (7 total, 3 judged at check tier and
4 deferred — `make check-queue` reads **36 kinds, 11 warnings, 47 scenarios
judged**, every number unmoved):

- `an-invoke-piece-fits-the-door-it-will-knock-on` — a composer offering to rank
  a task at minus one, refused against `task.prioritize`'s **own** `:input`
  model.
- `an-invoke-piece-names-a-door-that-exists` — an invented door name, refused
  with the kind's real doors LISTED, because a composer discovering a vocabulary
  one round trip at a time is a composer burning its cap.
- `a-piece-does-not-half-approve-an-ask` — the wire-boundary refusal above.

**Five deftests** in `outcome_test.clj`: `an-invoke-piece-moves-the-row-it-names`
(the form on the row, the door named, the row named, the version stamped, the
target really `done`, the MEMBER on that transition and the composer nowhere in
it), `the-fence-refuses-a-target-that-moved`, the governance pair above, and
`a-piece-may-not-half-approve-an-ask`. One existing test was **inverted** and
the inversion is the bead: `a-piece-is-judged-by-the-door-it-will-knock-on` used
to assert **422** for `target_kind "grant"` (*"the enum refuses it"*); it now
asserts **409** from `grant`'s own create model, plus a second arm for a kind
this house does not serve at all.

**Seven claims added to `:feed/outcomes`** rather than a new obligation: the
bundle grows a fourth piece — an invoke on the very row the third piece's tap
just created — staged AFTER the union line is read so that claim keeps its own
world. It cards; it carries an impact line; the line NAMES THE DOOR by the
target's own label; `make it so` takes it like any other; it cites the row it
moved; and the actor on **the target's own door** is the member, found by the
action rather than by recency (a mirrored kind appends a sync transition on
top — `create-actor`'s own finding, one door over).

**`ui-drive.mjs`'s feed walk, extended by seven checks** and run against a fresh
dev database: **60 checks passed, no console errors** (was 53). It stages a
second bundle — one create piece and one invoke piece completing the task the
first tap made — reads the line off the page, taps it, and reads the task back
`done`. The line it printed:

> Yes will use the "Done" door on one task that already stands: "Cut the box
> stock to length 457761" — in this house's own record, and at the source it
> mirrors to, the way any task does. Nothing else.

**Two walk fixtures moved and both are load-bearing**, in the way jfv.4's eight
retired chores were. The second bundle is staged by a **second composer**
(`ari`), because `outcomes-are-few` is two a week PER AUTHOR and one name
staging both would have spent its whole allowance; and the walk **seeds a third
task**, because it now completes two before the deal-again block runs and a
do-now line with too few candidates left can honestly deal itself the same order
three times. The walk also **answers the second bundle** before moving on, so
the engine it hands to the deal-again block is the engine it found.

### Recorded here, for whoever comes next

- **Exactly two fingerprints moved, computed both ways rather than argued.**
  `outcome` `5de724bc…` → `6f57c0d5…`, `outcome_piece` `672d914f…` →
  `8db51a4b…`. The other **34** kinds are byte-identical to `HEAD`. The reasons
  are exactly two and both are legitimate law: `:may true` joined the create
  touches on `make_it_so` and on `take` (`action-fp` projects `touches`), and
  `take` gained the guard `the-target-has-not-moved` and a new `materialize`
  body (`action-fp` projects `guards` and `handler`).
- **The FOUR new create walls minted no revision at all, and that is
  waymark-442.9's third witness rather than a relief.** `the-prepared-input-fits-
  the-door`'s widening, `the-door-carries-its-own-effect`, `the-row-it-names-is-
  there` and the `:on-create` stamp are all invisible to `fingerprint-of`, which
  projects only `machine.actions.*`. **A create door's law can change completely
  and show in no diff.** This bead widened the most permissive door in the tree
  and moved no hash for it.
- **The migrate plan is EMPTY, verified rather than argued.** `make
  migrate-queue` against a `workqueue10_dev` whose `outcome_pieces` predates this
  bead prints *storage matches the declarations — empty plan*, and the table
  still carries exactly `f_composed_by / f_outcome_id / f_target_kind`. The enum
  → string change on `target_kind` did **not** move the storage facet: a
  generated column's type is the same either way, which is what 442.9's
  witnesses predicted and what the empty plan confirms. **Production needs no
  DDL for this deploy.**
- **The battery is unmoved at 11, and the reason is a narrowing worth knowing.**
  A free-text `target_kind` judged by an `:open` guard is exactly the shape
  `usability/effort-honesty` warns about — except that the policy exempts guards
  which `reads-rows?` (a `:storage` or kind keyword in `:reads`), and
  `the-prepared-input-fits-the-door` reads `:storage` by necessity. So the wall
  that had to read the registry is the wall that earns no warning, which is the
  right answer for the right reason: no picker could enumerate the registry into
  a form.
- **The sharpest edge, named so it is not discovered later.** A piece may now
  name `grant.create`, `member.suspend`, `capability.revoke` — anything this
  engine serves. Nothing is walled; the impact line says what the tap does, and
  the target's own guards judge under the member's hand. That IS the ruling, and
  it is the owner's to revisit. What this bead adds beside it is that **the
  household reads the sentence before the thumb lands**, on the card, in the
  serif's quieter sibling, above the button it describes.
- **A `target_kind` that names a kind and a `target_action` that names a bulk
  door are both refused at staging.** `ctx :invoke` refuses a bulk action by
  name — its row form does not exist — so a piece naming one could never be
  tapped, and the wall says so where it was written.
- **An invoke piece's `materialized` is the row it MOVED**, not a row it made.
  Same field, same meaning one level up: the address of what this tap reached.

## Built — jfv.20, the composition request (2026-08-25, waymark-jfv.20)

**The owner's ruling, verbatim:** *I want to be able to just keep requesting
outcomes.* The weekly cap (`outcomes-are-few`, two per composer, Monday to
Monday) walls the **machine's initiative**, and the reasoning behind it is
unchanged: a composer that could stage ten would never rank. But a person
asking for another is consent given in advance — waymark-8um law 6 (*the
person spins; the system never spins for them*) applied to composition — and
a wall that refused the person's own pull would be the system deciding how
much the household is allowed to want. So the pull is a row, the row admits
one outcome past the cap, and the cap is otherwise untouched to the letter.
**Superseded in principle, 2026-08-26 — see § *Ranked, not capped* below
(waymark-1uv.1): the cap is a wall on writing standing in for a rank on
attention, and it leaves when the crown's rank lands (waymark-1uv.2, then .3).**

### The kind, as it landed

`composition_request` (`/api/composition_requests`), `:nav :system`,
`offered → answered | expired`. The create model is **one optional field**,
`value_id` — the value the next outcome should serve, when the person has one
in mind; *compose me another* with no aim is the common case and the one the
crown's chip mints. The engine stamps `requested_by` and `good_until` (seven
days — `leash-days`, the outcome's own number for the outcome's own sentence),
and `answered_by` at the answer. Three walls:

| wall | where | what it refuses |
|---|---|---|
| `only-a-person-asks` | create | an **agent whose grant does not admit `composition_request.create`** (`g/unless-granted`, since waymark-sfe / 2026-08-28; before that, every agent). A composer that could ask *itself* for a third outcome would have walked around the cap through the back door — and it still cannot, because a scope naming this door exists only where a person approved an `approval_request`, so what a holder files is the person's own pull. `:system` stays admitted; the wall is about the composer's initiative, not about its hand. |
| `aims-at-a-value-this-house-holds` | create | an aim naming a value this house does not hold — `names-a-value`'s two states, so a request cannot admit an outcome past the cap for a retired value |
| `answered-by-a-composition` | `answer` | **everyone but `outcome`'s own create** — see the seam below |

No pace wall, on purpose: a cap on asking would be the cap this kind exists
to get past, one door over.

### The outcome's side

`outcome` gains an optional `request_id` (a `:waymark/ref` to the request,
**no `:filter`** — the join runs the other way, the request stamps the outcome
that answered it, so nothing queries outcomes by request and the field lands
in `data`; storage facet unmoved, migrate plan empty, fingerprint unmoved).
`the-request-is-open` stands **directly in front of** `outcomes-are-few` in
the guard order and refuses, by name: no such request; already answered (and
by which outcome); expired; the leash run out; an aim not served (*the pull
was for THAT, not for anything*). `outcomes-are-few` then admits any cited
outcome without counting — it can trust a citation it did not read, because
by the time the count runs a citation has either been refused or is known
good. `stage-the-outcome` (the `:on-create`) invokes
`composition_request.answer {outcome_id (:id row)}` through `ctx :invoke` —
**before the outcome's own insert**, which works because the create algorithm
mints the row id ahead of the hook and the hook's ctx keeps `:invoke` live
(only `:create` defers). So the request reads `answered` in the same
transaction, names the outcome that answered it, and a second outcome citing
it meets a **state**, not a count. A refusal inside rolls the staging back.

### The seam: `(:within ctx)` — the one framework growth

*One request, one outcome* needed a wall that opens for another kind's own
handler and for nobody's hand, and the framework had no word for that: a
guard could read the principal, the clock, rows — never *which write opened
this door*. `invoke/make-ctx` now takes `:self` (the `{:kind :action}` of
the write it serves) and `:within` (the `:self` of the write it was opened
inside of); `invoke-in-tx!` and `create-in-tx!` pass their own `:self`, the
`ctx :invoke` / `ctx :create` doors hand it down as `:within`, and the
deferred `:on-create` births carry it too. At the wire, on the render probe
and in every rehearsal it is nil. A guard declares `:reads [:within]`;
`usability/declaration-reads` lists it (a fact about the call, like the
principal — effort-honesty stays silent) and `scenario/offline-reads` lists it
(the check tier serves nil, which is the same answer a client's knock gets).
`answered-by-a-composition` is the first reader: `outcome`/`create` opens it,
everything else is refused with the lawful path in the sentence. **This is
structural where a grant would have been a promise** — the composer's leash
never lists a door on the request kind, but the wall would hold even if it
did.

### Not a decide-section citizen — decided and recorded

The bead asked whether an undecided request should nag. **It should not.**
Your own request is not a decision to make, and a card reminding you that you
asked would be the feed manufacturing a thing to answer. The crown carries it
instead.

### The crown, on the wire and on the screen

`feed/document` gains a `crown` key beside `views` and `reasons` — never a
card, because a card projects a row with a verb and the chip exists precisely
when there is **no** row to project:

```json
"crown": {"empty": true,
          "card_id": "outcomes/composition_request/ask",
          "says": "Nothing composed is on offer. Ask, and …",
          "ask": {"href": "/api/composition_requests", "method": "POST",
                  "label": "Compose me another", "note": "…"},
          "standing": [{"self": "/api/composition_requests/01H…",
                        "asked_at": "…", "good_until": "…",
                        "value": "/api/values/…", "value_name": "…"}]}
```

Two rules, both the server's: `ask` rides **only when the crown carded
nothing** on the day's first page (answer what is there first — a chip beside
an unanswered bundle is the page asking for more before the person has said
what they think); `standing` rides whenever the reader has open requests,
crown empty or not, because *you asked, and the composer has not sat down
yet* is true either way. Under a preview the principal is the previewed
member, so both are theirs. The chip rides the origin key under
`outcomes/composition_request/ask` — `origin-of` parses it and
`actions-from-feed` counts the pull under `outcomes`. `135-feed-screen.js`
paints exactly that: one tap, then the settled sentence *the composer answers
at its next sitting*; a standing request is a link with no verb on it. A
bundle that answers a request says so first on its card (*You asked for
another, and this is the composer's answer.*).

### The honest note about time

A request is answered at the composer's **next sitting**, not on the tap.
Until **waymark-53u** gives the composer a pulse, the tap writes an invitation
and the answer arrives when somebody sits the composer down. The kind's prose
(`value_id` help, `expire`'s sentence) and the chip's settled line say so
rather than letting a button imply a vending machine.

### The composer contract, one line wider

The grant gains `{"kind": "composition_request", "actions": []}` — read only.
The composer reads `?state=offered`, stages an outcome citing one, and the
staging answers it. No door on the request kind is granted because none is
needed and the only one that could is walled to the staging's own hand.

### Where the law is proved

- **Two scenarios on the request** (check tier, no database):
  `nothing-but-a-staging-answers-a-request` (a client's knock arrives with no
  `:within` and is refused by name) and
  `a-live-request-is-not-expired-out-of-the-way`. `make check-queue` reads
  **37 kinds, 11 warnings, 49 scenarios judged** — the battery unmoved.
- **No scenario names `only-a-person-asks`, and the absence is forced**: a
  create attempt runs every create guard, `aims-at-a-value-this-house-holds`
  reads a kind, so the scenario defers to the conformance tier — where it is
  attempted as an agent with **no leash** and the router's default deny answers
  404 before any wall speaks (**waymark-zs9**, met again; the first run of the
  suite proved it). The wall is proved where an agent can be leashed instead.
- **And none names `the-request-is-open`**, for `names-a-person`'s reason: a
  scenario's `:input` is a literal, so the only request it could cite is a
  dangling one, and `names-a-value` refuses the same body first because its
  value is dangling too (the suite's first run said so: *a different wall*).
  Five arms, all proved over the live engine.
- **Five deftests** in `outcome_test.clj` § 15:
  `a-persons-request-admits-one-outcome-past-the-cap` (two staged, the uncited
  third refused, the cited third admitted, the request `answered` naming it
  with the **composer's** hand on the `answer` transition, a second citation
  refused, an uncited fourth still refused),
  `a-request-that-names-a-value-admits-only-an-outcome-serving-it`,
  `an-agent-does-not-mint-a-request` (leashed),
  `nothing-but-a-staging-answers-a-request-over-the-wire` (a person's tap and
  a leashed agent's post both refused; the door absent from the row's own
  envelope), `the-crown-carries-the-pull` (standing on the document; the ask
  standing down when a bundle cards; an answered request leaving the list).
- **Seventeen claims added to `:feed/outcomes`** (conditional on the engine
  holding the kind): the document carries `crown`; empty ⇒ ask, not empty ⇒
  no ask; the tap lands under the member's name with the origin key on its
  transition, parsing to `outcomes` / `composition_request`; the next read
  says it is standing; a composer leashed to the kind with no doors reads it;
  an outcome citing it is admitted; the request reads answered naming that
  outcome with the composer's hand; a second citation is refused; a person's
  by-hand `answer` is refused; a leashed agent's create is refused.
- **`ui-drive.mjs`'s feed walk, extended by five checks** after the deal-again
  block (both bundles answered ⇒ the crown offers the chip; one tap; the
  settled sentence; the row under the asker's name with the origin key on its
  log; the standing line on re-read).

### Recorded here, for whoever comes next

- **Only the new kind's fingerprint is new; `outcome`'s does not move.** A
  create-schema field and a create guard are outside `fingerprint-of`
  (442.9's witnesses, once more); `request_id` carries no `:filter`, so no
  generated column, no storage facet, **no DDL on `outcomes`**. Production
  needs one `CREATE TABLE composition_requests` — the migrate plan says so.
- **The battery found the naming.** The answer door's input was first spelled
  `outcome_id` beside a document field `outcome_id`, and the battery read the
  door as an *edit* (an edit implies a fence, and this door is opened by
  another kind's hand with no etag to give). The stamp is `answered_by` — named
  for what it is — and the input stays the typed ref.
- **A deferred `:on-create` birth is `:within` its parent's create**, and the
  drain says so. Nothing reads it yet; it is there so the first thing that
  does is not surprised.
- **A CREATE's transition now carries the client's idempotency key.** The
  pack's origin-key claim on the ask failed on the first run: `create-in-tx!`
  never stamped the key on the birth transition, while `finish!` stamps it on
  every invoke — so `actions-from-feed` could count a tap that *moved* a row
  and never one that *made* one. The quick reasons (jfv.16) have been ridden
  under the origin key uncounted since they landed. One line in the framework;
  the column already existed.

## Ranked, not capped — the principle (2026-08-26, waymark-1uv.1)

**The owner's ruling, verbatim, 2026-08-26:**

> I have a lot of raw data about myself that I can't use easily. Agents have
> infinite patience and time to sift through that data and index it.
> Compositions are a type of that indexing. I don't think it makes sense to
> limit the indexing. It makes more sense to just rank them.

The epic (`waymark-1uv`) carries the second half of the ruling in its own
words rather than the owner's: *an agent may tune the rank, or supply a
judgment to it.* That half is decided below, under § *The agent's part*.

It reverses a recorded ruling — § *The outcomes section — the crown, and its
floor* above, *"The weekly cap lives at the create door, not in the recipe and
not in the population"* — and the § *Built — jfv.20* paragraph that restated
it. Both stand as history and are not edited beyond a forward pointer; what
follows supersedes them wherever they disagree, which is the jfv.10 convention
for a reversal.

**The principle, in one sentence:** a cap on WRITING protects a person's
attention by proxy; a RANK protects it directly. The house already rules this
way twice over. jfv.10: an agent writes a value without limit, the row is born
`observed` and *says so wherever this house cites it*, and the owner's hand is
the safeguard. jfv.11: the same for a person. Neither kind has a pace wall and
nobody has asked for one, because the danger was never the number of rows — it
was a row reaching a person's eyes as though it were the person's own word.
The outcome cap (`outcomes-are-few`, two a week per composer) is the odd one
out: the one place an agent's writing is walled by count rather than by what
the reader is shown.

### What the cap was for — the three recorded reasons

Quoted from § *The outcomes section*, because a reversal that paraphrases what
it reverses is arguing with a straw man:

> 1. **A filter buries what a wall would have refused.** The cap exists so the
>    composer must *rank* — the insight cap's whole point, put at the source. A
>    population that showed two of ten staged plans would let a composer dump
>    ten and learn nothing, and eight rows would sit `offered` teaching the
>    composer that the household ignores it.
> 2. **The recipe has no word for a week and should not grow one.** `:take` is
>    per page; adding a window would make the recipe mean a second thing, in a
>    schema a person edits in a form.
> 3. **A weekly filter fights the floor.** 8um law 3 wants guaranteed exposure
>    so the contest can be measured; a read-side window would hide cards that
>    had already spent the week's allowance, and *a learner cannot learn about
>    a card it never shows*.

And `weekly-cap`'s own docstring, which is the same argument compressed to its
load-bearing clause: *"a filter would bury what a wall would have refused, and
eight ignored rows would teach the composer that this house does not care."*

| reason | what it is actually about | standing today |
|---|---|---|
| 1 — a filter buries; unanswered rows teach the wrong lesson | what an unanswered `offered` row MEANS to the composer | **dissolved** by the view-event door — below |
| 2 — the recipe has no word for a week | WHERE the rank lives — not in `:take` | still true, and the rank does not live there |
| 3 — a window fights the floor | WHERE the rank lives — not in a read-side filter that hides | still true, and the floor stays |

Reasons 2 and 3 were never reasons for a cap; they were reasons against two
particular places a rank could have been put. They survive intact, as
constraints on the rank (§ *The rule going forward*, below).

### What changed — an unanswered row is no longer a verdict on the composer

Reason 1 rested on one fact about the record as it stood on 2026-08-25: an
`offered` outcome with no verdict on it was **indistinguishable** from an
outcome the household had looked at and passed over. The feed READ writes
nothing — the law that did not move — so the store knew that a row existed and
that nobody had answered it, and nothing in between. The only safe reading of
silence was the worst one, *the house ignores it*, and the cap was the device
that made silence honest: keep the count low enough that every staged outcome
is on the crown every morning, and then an unanswered row really has been
shown and passed over.

**waymark-8um.1 made the middle knowable.** `feed_view` records, per member
who has turned it on, that a card was on THAT member's screen on THAT day
(`member`, `card_id` whole, `population`, `day`); and *whether an action
followed* is a join on `(day, card_id)` against the audit trail that already
knows (`spec-feed.md` § *Built — 8um.1*). So for any outcome the record now
distinguishes three things it used to collapse into one:

| the row reads | before 8um.1 | after 8um.1 |
|---|---|---|
| `offered`, no verdict, no view row | *the house ignores it* | **never shown** — a fact about the rank; the composer learns nothing about the house from it |
| `offered`, no verdict, view rows | *the house ignores it* | **shown and passed over** — the signal 8um law 4 calls the composer's work order |
| a verdict, with jfv.16's reason | a verdict | a verdict, and the reason names the line of thinking |

A composer that stages fifty and sees five shown learns about the **rank**,
not about the house — `waymark-1uv.4` makes the diagnosis duty read the record
in that order. That is the whole of the change: the cap was protecting the
composer from a misreading the record can no longer produce. Take the
misreading away and reason 1 becomes a reason for a rank that says what it
did — which is 8um law 5, built for three sections and not yet for the crown.

### The rule going forward

Stated as law, in the shape the feed's laws wear, because it governs more than
outcomes:

> 1. **The machine may write without limit.** Indexing is the agent's work and
>    a count is not a judgment. No kind grows a pace wall because a person
>    might otherwise be shown too much of it; what a person is shown is the
>    rank's to decide, never the create door's.
> 2. **The rank decides what reaches a person's attention.** It is declared
>    data on the recipe row, readable on every card it touched (8um law 5),
>    and the exposure floor stands under it: `:take` is a guaranteed slot and
>    the rank chooses WHICH, never WHETHER. A rank that hides is the window
>    reason 3 refused.
> 3. **A cap stays only where the write itself costs the household** — a push
>    to an authority (a mirrored kind's `:push-on-write`), a letter to a
>    person (`letters-are-paced`), a mirror write onto a source the house does
>    not own. There the cost lands when the row is written, before any rank
>    could stand between it and a person, and a wall at the door is the only
>    place left to stand.
> 4. **Each such cap must say so in its docstring** — name the cost the write
>    incurs, not the ranking problem it makes the author solve. A docstring
>    whose whole argument is *"so the author must rank"* is describing a rank
>    on attention and asking a wall to do its job.

`weekly-cap`'s docstring is exactly what clause 4 describes, and it stands
today with one sentence pointing here, because the rank it defers to does not
exist yet (`waymark-1uv.2`). **The guard is not removed by this bead and may
not be removed before the rank lands** — `waymark-1uv.3`'s own warning: with
no cap and no rank the crown shows two of two hundred by the day's seed, which
is worse than what stands.

### The agent's part — A first, M when the formula wants a judgment, never B

The second half of the ruling — *an agent may tune the rank, or supply a
judgment to it* — admits three shapes, weighed 2026-08-26:

| option | the agent … | what the card can say | the revert | what it needs |
|---|---|---|---|---|
| **A** — tunes | proposes numbers for a DECLARED, readable formula through `recipe_proposal` (waymark-0k4) | *asked-for counts 3× — applied by the household on Tuesday*; 8um law 5 holds | one tap, the proposal's own machinery | nothing new — the door exists |
| **M** — a score and a sentence | writes a `0..1` score and one sentence on a row, as DATA, stamped with who wrote it; the formula reads the score as one weighted input beside the others | quotes the sentence **as the agent's**, the way the routing is quoted as the composer's | one weight, turned down to zero | a field or a small kind — `waymark-1uv.6` decides which |
| **B** — is the rank | orders the crown itself, at read time or by writing the order | nothing it can prove — *the agent put it here* | none short of un-leashing the agent | a pulse at every read |

**Decision: A first; M when the formula wants a judgment it cannot compute
from counts; never B.** Four reasons, each a law or a bead this house already
holds:

1. **8um law 5.** The formula is data the owner can read. An agent that IS
   the rank is a hidden model whatever its candour at the prompt, because the
   household cannot read a prompt off the wire and cannot edit one in a form.
   A and M both leave the formula readable: A changes its numbers, M adds an
   input the card names.
2. **`?explain=1`.** Every card's `why` says what lifted or held it, and the
   `.29` machinery quotes the declaration's own words. Under A the why is
   arithmetic over numbers the household applied; under M it is that
   arithmetic plus a quoted sentence with a name on it; under B it is an
   assertion.
3. **The pulse (waymark-53u).** The composer has no standing presence — a
   composition request is answered *at the composer's next sitting*, and
   § *Built — jfv.20* says so rather than let a chip imply a vending machine.
   A rank that waits on a sitting is a crown that does not load. A and M are
   read at the feed's read, from rows already written; B needs an agent awake
   at every GET, which this house has deliberately not built.
4. **One-tap revert.** A rides `recipe_proposal`: *the apply IS the member's
   write*, the diff is stored, and the previous numbers are the row's own
   transitions. M reverts by one weight. B has no revert but distrust — and
   the crown is the one place a person acts on a machine's word, so that word
   must be readable and a person must be able to take it back.

The order A → M is not a ranking of merit; it is the order in which the
evidence arrives. A tuning agent reads exposure, verdicts and reasons per
input and proposes numbers citing them, the way an insight cites rows
(`waymark-1uv.5`). Only when that agent finds the counts cannot express the
thing it knows — *this is the outcome he has been circling for a month* — does
M earn its field, and when it does the card quotes the sentence, and the
household can read whether the agent was right.

### Where each part lands

| this section says | the bead that builds it | lands after |
|---|---|---|
| the rank decides what reaches attention — declared, readable on every card, the floor kept | **waymark-1uv.2** — the crown's declared rank: asked-for first, declared over observed, shown-and-passed-over cools, verdict reasons cool a line of thinking, freshness; the population stops scanning | this bead |
| the machine writes without limit — the cap goes; the request kind stays as the rank's first input; the crown chip's rule re-read | **waymark-1uv.3** — remove `outcomes-are-few` | .2, never before it |
| never-shown is not a decline — the diagnosis reads exposure before verdicts | **waymark-1uv.4** — the diagnosis duty counts exposure | waymark-8um.4 |
| option A — the agent tunes through a proposal a person applies; the diff speaks the household's words | **waymark-1uv.5** — the rank is tunable through `recipe_proposal` | .2 |
| option M — the agent's score and sentence, one input, quoted as the agent's | **waymark-1uv.6** | .2 and .5 |
| the principle is wider than outcomes — `insights-are-capped`, the tickler pile; clause 3's caps say why they stay | **waymark-1uv.7** | — |

### Recorded here, for whoever comes next

- **Nothing in code moved but one sentence.** `weekly-cap` is still `2`,
  `outcomes-are-few` still stands at the create door behind
  `the-request-is-open`, and `make check-queue` reads 37 kinds, 11 warnings,
  49 scenarios judged — the jfv.20 battery to the number. The docstring gained
  one sentence pointing here, so a reader of the code finds the reversal where
  the number is.
- **jfv.20's opening paragraph is now history**, including *"the reasoning
  behind it is unchanged: a composer that could stage ten would never rank"*.
  It stands as written with a forward pointer. Its mechanism — the person's
  pull admits an outcome past the cap — survives the cap's removal as the
  rank's first input (*asked-for first*), which is waymark-1uv.3's sentence
  and not this bead's to build.
- **`staged-changes-are-few` (three OPEN `recipe_proposal`s per author) was
  not decided here, and the epic does not list it.** Its docstring argues both
  ways at once: *"a composer with three proposals already on the fridge has a
  ranking problem"* is clause 4's tell, but a proposal is a question on the
  decide section — an obligation outside the contest (8um law 2) that costs a
  person a verdict the moment it is written, which is clause 3's exemption.
  `waymark-1uv.7` asks this question of insights and ticklers; this cap wants
  the same question, and it is recorded here for that bead to take up.
- **The floor is not the cap and does not go with it.** `:take 2` on the
  outcomes line is 8um law 3's mechanism and clause 2 keeps it; what
  waymark-1uv.3 removes is the count at the create door and nothing on the
  recipe row.
## The other caps, answered (2026-08-26, waymark-1uv.7)

**The question, asked of every pace wall in the tree.** The epic's principle
(`waymark-1uv`, the owner's ruling verbatim: *"Agents have infinite patience
and time to sift through that data and index it. Compositions are a type of
that indexing. I don't think it makes sense to limit the indexing. It makes
more sense to just rank them."*) is wider than `outcomes-are-few`, and this
section walks it past every other cap the tree holds, with one test per cap:
**is the cap a proxy for a rank on attention** — then it should become a rank
input and go — **or does the write itself cost the household** — a push to an
authority, a letter to a person, a verdict owed by a date, a leash asked for —
in which case the cap stays and its docstring now says so. The grep that
found them: `pacing|are-capped|are-few|are-paced` over `workqueue10/src` and
`waymark10/src`. Nothing here changes a guard's behavior; the answers are
recorded, the follow-ups are filed, and the caps that stay each carry one new
sentence naming the cost.

**The test, stated once so every row below can be checked against it.** A
write costs the household when something *outside the rank's reach* happens
because of it. Three shapes qualify, and only three were found: the write
calls an authority (`:push-on-write` — `task`, `event`, `media`, `prep_task`
call Google or Home Assistant on a write, which is why fork (b) in
`spec-feed.md` refused to let a tickler be a field on `task`); the write puts
an **obligation on a person** that laws v3 law 2 places *outside the contest*
(*a conflict needing a verdict, an ask that expires, a letter waiting* — these
appear because they must, so no rank stands between the writer and the
reader's attention); or the write is about the rank itself (law 8), which the
rank cannot judge. Anything else that lands in the feed is contending for
attention, and a wall on writing it is the proxy the ruling names.

| kind | guard | what it walls | proxy or cost | recommendation |
|---|---|---|---|---|
| `outcome` | `outcomes-are-few` | two staged outcomes a week per composer | **proxy** — the epic's own subject | goes with **1uv.3**, never before **1uv.2**; nothing touched here |
| `insight` | `insights-are-capped` | three findings a day per author | **proxy** — the precedent the outcome cap copied; the insight IS the indexing the ruling names; the write pushes nothing, mails nobody, and the offer is an address | becomes a rank; **waymark-1uv.8** names the inputs; the cap goes after the rank — **built**, § Built — 1uv.8 |
| `tickler` | the backoff (`backoff-days`, 7/21/60/180) | when a not-now'd marker comes back | **not a cap** — a person's own verdict written as a date (law 6), and already a read-side rank input (`next_offer_at`) | untouched; the sweep (`waymark-iqa.13`) gets **no per-sweep cap**; the due pile gets a rank — **waymark-1uv.9**; the dedupe iqa.13 wants is a law, not a cap |
| `outcome` | `a-recomposition-waits-its-turn`, arm (c) | a superseding outcome before the prior's `not_before` | **proxy, but the person's** — a decline stamped as a date, then used as a wall on the composer's writing; 1uv.2 already names the verdict chain as the rank's input (4) | the date arm becomes the cooling input — **waymark-1uv.10**, with 1uv.3; arms (a) and (b) are shape and stay |
| `letter` | `letters-are-paced` | 60 create *attempts* an hour per principal | **cost** — mail to a person lands on their shelf outside the contest (law 2, *a letter waiting*), and every refused attempt is a roster probe (L4) | stays; docstring says so |
| `permission_slip` (via `resource/pacing-guards`) | `asks-are-paced` (12/hour), `asks-are-few` (4 open) | a child's asks for a parent's signature | **cost** — an obligation with a deadline on a person, outside the contest by law 2, where no rank can protect the decider | stays; the sugar's docstring says so, and says when `:pacing` is the wrong spelling |
| `approval_request` | `asks-are-paced` (20/hour), `asks-are-few` (10 open) | fresh access asks; anchored asks exempt | **cost** — a verdict owed on a leash, by a date, outside the contest; the thing asked for is access | stays; both limits' docstrings say so |
| `recipe_proposal` | `staged-changes-are-few` | three staged proposals waiting per principal | **cost** — a question to the owner about the contest's own rules (law 8), carded outside the contest (law 2); the rank cannot judge proposals to replace the rank | stays; docstring says so — and 1uv.5 rides this door, three waiting being plenty for one agent tuning numbers |
| `/auth/agent`, `/auth` knock | `reentry-door-log`, `invite-door-log`, `knock-log` | pre-auth attempts per rolling hour | **neither** — not a write and not attention; a wall against guessing beside 128-bit tokens | stays without argument; one sentence in the docstring |

### `insights-are-capped` — the same proxy, one window up

The cap's own recorded reasons are the outcome cap's three, said first: *a
surface that can be filled is a surface that will be*; *the cap is what makes
a compiler rank rather than dump*; and, in the population's docstring, the cap
*is what keeps `row-scan-cap` small at the source*. The first two are one
reason wearing two sentences — **a wall on the writer standing in for a rank
on the reader** — and the epic has already answered it: the strongest form of
that reason (*unanswered rows teach the composer the house ignores it*)
stopped being true when `feed_view` (waymark-8um.1) made NEVER SHOWN
distinguishable from SHOWN AND PASSED OVER. A finding the house was shown and
declined now carries a quick reason (jfv.16); a finding the house was never
shown carries no view row; the compiler can read the difference at its own
address and rank on it. The third reason is about *where the bound lives*
(a stored score or a bound on the read), not whether there is one — the same
question 1uv.2 answers for the crown.

**And the insight is misfiled.** The `:insights` line sits in `:decide`, and
`cooling-says` tells every decide card it is *outside the contest: something
waiting on your answer appears because it must — an ask that expires, a
conflict, mail on your shelf, a change staged for a tap*. That sentence does
not name the insight, and law 2's own list does not either, because an
insight is not an obligation with a deadline. It is the contest's **output**:
law 4 calls the diagnosis *an insight proposing a recomposition*. So the line
is outside the contest by placement and not by nature, and the cap has been
doing, badly, the job a rank does well.

The write costs nothing the test recognises: no push (`insight` is a plain
kind), no letter, no notification (*the feed is not the notifier*, unchanged
and unchallenged), and the offer is an **address** that writes no other row —
`.6` went to some length to make it so. The rank's inputs, each a number a
person reads on the card's why, are recorded in **waymark-1uv.8**: the verdict
record on the same offer (a dismissed finding on the same `{offer_kind,
offer_id, offer_action}` cools hard, by its quick reason); law 4 first (a
finding that offers a recomposition of a losing outcome outranks a plain
finding, because the composer's duty fires first); the value the offered row
serves, declared over observed; shown-and-passed-over cools, off the view
record; freshness; and the agent's own score and sentence (1uv.6, option M).
**Dedupe is a law, not a cap** — one live finding per offer — and `:take 2`
stays as the exposure floor. The order is the epic's: the rank first, the cap
second, never the cap alone.

### The tickler — one thing that is not a cap, and one that must not become one

**The backoff is not a cap on writing.** `backoff-days` is a person's own
*not now* written down as a date — law 6's *the person spins* — and the
machine writes nothing because of it. It is, in fact, the tree's first
read-side rank input in disguise: `next_offer_at` past is the whole of the
population's *due* filter (*a backed-off marker is simply not a candidate*).
It stays exactly as it is, and its docstring is not touched because there is
nothing to defend.

**A cap on ticklers born per sweep would be the same mistake.** waymark-iqa.13
wants a sweep over the dropped pile, and the temptation is a ceiling on how
many markers one sweep may mint. The test says no: a tickler writes nothing
to its subject (fork (b), reason 1 — no cascade into a `:push-on-write`
mirror), it is no letter and no notification, and a marker over a dropped
task is the machine **indexing the pile**, which is the ruling's own word for
what must not be limited. Twenty-five markers born at once is a **rank
question for the `:ticklers` line**, not a wall at the sweep. The dedupe
iqa.13 already wants — one live marker per subject, a create guard over the
`:filterable` `subject_kind` + `subject_id` — is a law and must land as one.

**What the population hides, and the rank must not.** A person's dated
tickler (*bring this back on the 3rd*) is an obligation the person set — it
is outside the contest by law 2 and appears because they asked. A sweep-born
marker (`set_aside_by` an agent, `next_offer_at` unset, which means *now*) is
indexing and contends. Today both are *due* and the seed picks two. The
rank's inputs are recorded in **waymark-1uv.9**: who set it aside (a person's
own dated marker first, as the obligation it is); overdue-ness; `offer_count`
(more not-nows cools — the household's own record, the tickler's whole reason
for server-side state); the subject's kind and, when it has one, the value it
serves; shown-and-passed-over, off the view record; and the subject's own age
on the dropped pile. `:take 2` stays as the floor. With a swept pile every
marker is due on day one, so the bound question is real and it is the same
one: **a stored score or a bound on the read, never on the birth.**

### `a-recomposition-waits-its-turn` — the person's verdict, used as a wall

The grep found a third pace wall on `outcome`, and it is the subtle one. The
guard has three arms. *No such outcome* and *still offered — asking the same
question twice* are shape and a dedupe law; they stay. The third — *the house
said not this week, and meant it until `not_before`* — takes a person's
decline, stamped as a date off the tickler's own schedule, and uses it as a
**wall on the composer's writing**: the recomposition may not even be staged
until the date. That is a proxy with an honest face, because the verdict it
honours is real; but 1uv.2 already names the verdict chain as the rank's
input (4) — *the person's verdict reasons cool a line of thinking, read off
the supersedes chain and the reason rows* — and a recomposition written early
would be **cooled** by the fresh decline until the date, the card's why
saying so, rather than refused at a door. Law 4 wants it written: the
diagnosis is the composer's work order, and a wall that refuses the
recomposition for two months is a wall against the diagnosis duty. Filed as
**waymark-1uv.10**, to land with 1uv.3 and never before 1uv.2. It carries one
real open question the bead records rather than hides: the exposure floor and
a cooled-to-zero card collide precisely here — the crown's `:take` must not
surface a recomposition the person said not to hear yet — and if that has no
clean answer the fallback is to keep the arm and say why.

### The caps that stay, and the sentence each now carries

Each of these guards' nearest docstring gained one sentence saying it stays
under this epic's ruling and naming the cost — so the next reader who arrives
holding the principle finds the answer where the number is, not in this
section.

- **`letters-are-paced`** (`letter-pace-limit`): a letter is mail to a
  person; it lands on their shelf outside the contest (law 2), so no rank can
  stand between the sender and the recipient's attention — and the door's
  refusals are a roster to enumerate, which is the L4 reason the pace counts
  *attempts* and not rows. Two costs, and either alone would keep it.
- **`resource/pacing-guards`** (`permission_slip`'s `:pacing`): a `:decision`
  ask is an obligation with a deadline placed on a person, outside the contest
  by law 2. The sentence also says the converse, because it is the useful
  half: *a kind whose rows merely contend for attention wants a rank, never
  `:pacing`* — the sugar is for obligations, and the insight was right not to
  spell it (for the wrong reason, but right).
- **`asks-are-paced` / `asks-are-few`** (`approval_request`, hand-written for
  the anchored-ask exemption): a verdict owed on a leash, by a date; the thing
  asked for is access. A rank over access asks would be a rank deciding which
  leash the household hears about, and law 2 refuses that on purpose.
- **`staged-changes-are-few`** (`recipe_proposal`): its docstring argues both
  ways — *a composer with three proposals already on the fridge has a ranking
  problem* is the proxy's tell — but the write is a question put to the owner
  about the contest's own rules, and it costs a person a verdict the moment it
  is written. The rank cannot be the judge of proposals to replace the rank:
  the incumbent formula ranking its own successor is the one conflict of
  interest a readable formula cannot read its way out of. **1uv.5** rides
  this door, and three waiting is plenty for one agent tuning numbers.
- **The `/auth/agent` and knock windows**: not a write, not attention. Outside
  the principle rather than an exception to it.

### Recorded here, for whoever comes next

- **The v1 law bullet is superseded for insights, not deleted.** *"Hard daily
  cap so the compiler must rank"* stands in `spec-feed.md`'s original four as
  a true account of what the feed was; the owner's ruling supersedes it the
  way laws v3 superseded day-stability, and **1uv.1**'s pointer in the laws is
  where that is said. The compiler must still rank — the ruling moves *where*:
  into a formula the household can read, with the compiler's own score as one
  input (1uv.6), instead of into a wall the compiler meets at the door.
- **Two ceilings the grep found are not caps and were not asked the
  question.** `bundle-ceiling` (`a-bundle-is-small`, five pieces) is a size,
  and `asks-are-short` / `:expires :max` are leash lengths; neither walls how
  often anything is written. `outcomes-are-few`'s sibling `the-request-is-open`
  is a wall on *citing*, not on writing, and 1uv.3 keeps it.
- **`:push-on-write` kinds carry no cap today, and that is worth saying so
  nobody adds one thinking the principle requires it.** They are the reference
  case of a write that costs — a call to Google on every action — and the sync
  machine, not a pace, is what bounds them. If a cap is ever wanted there it
  stays for that reason, and its docstring should say so from the first line.
- **Three of the four proxies share one bound question.** The insight
  population, the ticklers population and the crown all read at most
  `row-scan-cap` rows and pay a subject read per candidate; each cap was, in
  its third reason, the thing keeping that number small at the source. 1uv.2
  answers the question for the crown (a stored score or a bound, and which);
  1uv.8 and 1uv.9 must answer it the same way rather than each inventing a
  second spelling.
- **`make check-queue` after this bead reads 37 kinds, 11 warnings, 49
  scenarios judged** — the battery unmoved, because no guard's behavior moved
  and a docstring is not a facet.
## Built — 1uv.2, the crown's rank (2026-08-26, waymark-1uv.2)

**The owner's ruling, verbatim:** *I don't think it makes sense to limit the
indexing. It makes more sense to just rank them.* The epic (waymark-1uv,
*Ranked, not capped*) takes the weekly cap off the create door in its third
bead; this is its second, and the epic's own order says it lands first and
the cap comes off after, never the other way round — *with no cap and no
rank the crown shows two of two hundred by seed, which is worse than today.*

So the crown now **ranks what it shows**. Until this bead the outcomes
section carded its `:take` bundles by the day's seed and every crown card's
citation read *held by the floor … not because it won anything* — true while
two a week was the most a composer could stage, and a lie the morning the cap
comes off. It landed as **four numbers on the recipe row beside the contest's
two, one line of arithmetic, one new key on every crown card's `why`, and a
sentence that replaces the one that was about to become false.** The floor
stays. The seed still breaks ties. No hash the household's own kinds carry
moved.

### The rank, as landed

```clojure
;; feed/default-crown-rank
{:declared 10   ; what a value a PERSON declared lifts a bundle over an observed one
 :cooled 2      ; what each step the contest says it has cooled holds it back
 :declined 2    ; what each RANK of the house's strongest quick word holds it back
 :fresh 1}      ; what each day left on its week lifts it
```

```clojure
;; feed/crown-lift — the whole of the arithmetic
lift = declared × [the value is declared]
     − cooled   × steps cooled
     − declined × weight of the strongest word on the supersedes chain
     + fresh    × days left on the week

;; feed/crown-key — the sort, ascending
[asked-for-first?  −lift  hash(seed ‖ card_id)]
```

Five inputs, and each is a number a person reads on the card:

| input | read off | as it rides `why.crown` |
|---|---|---|
| **asked for** | `outcome.request_id` names a `composition_request` (jfv.20) | `"asked": true` |
| **the value's standing** | `feed/value-standing` — `declared` by a member, or `observed` by an agent and not yet affirmed (jfv.10) | `"value": "declared"` |
| **shown and passed over** | the same `feed_view` rows, the same window and the same `cooling-step` the contest reads (8um.3) | `"seen": 3, "cooled": 1` — only while the reader is recording |
| **the house's word about this line of thinking** | the `verdict_reason` rows filed against each outcome up the `supersedes` chain (jfv.16), the heaviest word kept | `"declined": "never_this"` — only when a word was said |
| **freshness** | whole days until `good_until` | `"days_left": 5` |

…and the lift they add up to, `"lift": 13`, on the **plain read**, for the
contest's own reason: a card that stands where it stands for a reason and
would not say so unless asked is the thing law 5 forbids. The sentences are
still the `?explain=1` half.

On the wire, on every read, beside `recipe.formula`:

```json
"recipe": {
  "guarantees": "… the contest is two numbers a person can read; and the crown's rank is four. …",
  "formula":    {"window_days": 14, "cools_after": 3},
  "crown_rank": {"declared": 10, "cooled": 2, "declined": 2, "fresh": 1},
  "crown_rank_says": "The crown ranks what it shows, and this is the whole of it. A bundle that answers a request you made stands above every one nobody asked for, and no number here changes that. Among the rest, four numbers a person can read: serving a value this house declared lifts a bundle 10 over one serving a value an agent only observed; each step the contest says it has cooled — the same 3 days in 14 as the sections below, read off your own record — holds it 2; the strongest quick word the house said about the line of thinking it recomposes holds it 2 for wrong time, 4 for wrong piece, 6 for not this way and 8 for never this; and each day left on its week lifts it 1, so a bundle nearer its lapse ranks lower. The floor still holds — the crown shows as many bundles as its take says whenever that many exist; the rank only chooses which, and the seed decides between equals. Until you turn the record of what you were shown on, nothing about seeing moves anything here."
}
```

### Asked-for is a tier, not a weight — decided and recorded

The bead listed *asked-for first* as the first input and said each input is a
number. It is the first input and it is **not a number**, on purpose. A
bundle citing a person's own `composition_request` sorts ahead of every
uncited one before any lift is compared, and there is no field on the recipe
that can move it below one — because that is waymark-8um law 6 read at the
crown: *the person spins; the system never spins for them.* A household
number that could put the machine's initiative above a person's own ask
would be the recipe overruling the person through the form, and the pure
test asserts the tier holds under a **negative** lift.

That is why the recipe holds four numbers and not five, and why the four
quick words are ONE number rather than four: `:declined` multiplies the
word's rank — never this 4, not this way 3, wrong piece 2, wrong time 1
(`feed/reason-weights`, the epic's own order, which is also the reason kind's
own order read from the last word back) — so no edit can put *wrong time*
above *never this*. A word the map does not know weighs one: any word said is
at least *wrong time*, because the house turned the line down and said so.

### Silence is read as silence

A decline with no quick word leaves nothing on the rank. `crown-word` walks
the `supersedes` chain (at most `supersedes-chain-cap` = 5 hops — half a year
of recompositions on the tickler's own schedule) and reads only the reason
rows; `declined_count` is not an input. That is jfv.16's own sentence — *not
tapping is a complete answer* — kept rather than re-litigated: what a bare
decline buys the house is the `not_before` floor at the create door, which is
a different mechanism and stays one. A house that wants a line of thinking
held back says so with a word, and the word is one optional tap on the card
it already declined.

### The cost, decided: a bound with a note, not a stored score

The population already paid a value read and a piece query per candidate;
the rank adds a walk up the chain with a reason query per hop. With the cap
gone there is no door keeping the number of offered bundles small, so the
bead asked for either a stored score or a bound. **The bound:**
`feed/crown-scan-cap` = 50 offered bundles, newest first, and the document
says so when it is reached —

> The crown read to its cap and stopped — the newest 50 bundles on offer
> were ranked, and older ones were not read today. Those are the ones nearest
> their lapse, which the rank already places last; a house with more than 50
> on offer at once has a composer to talk to before it has a cap to raise.

— the archive's `log-scan-cap` posture and the contest's `view-scan-cap`
posture, one section up. It fails in the fairest direction available: the
bundles dropped are the oldest, which `:fresh` already places last.

A stored score was weighed and refused **for this bead**, for two reasons
that are structural rather than lazy. It needs a WRITER, and there is no
honest one: the staging hook cannot know what the reader has been shown
(three of the five inputs are the reader's own — their views, their words,
their day), and a sweeper that wrote scores would be the feed writing, which
law 7 forbids from the read side and nothing licenses from the other. And it
goes STALE the moment a view row or a reason lands, which is exactly when it
would matter. Fifty is a week of a busy household's composing several times
over — a bundle stands seven days — and the note is the honest answer until
somebody measures a house that reaches it (filed below).

### The why sentences, as they actually read

Two, and the second replaces the one that was about to become false. On a
crown card, with the reader recording:

```
Ranked 2nd of 3 in the crown by recipe.crown_rank — four numbers this house
can read — and this is the arithmetic for this card. Nobody asked for this
one, so any bundle that answers a request stands above it. It serves a value
this house declared, which lifts it 10. Shown 3 days in the last 14 with
nothing done — 1 step cooled, holding it 2. The house said never this about
the line of thinking it recomposes — the heaviest of the four words, holding
it 8. 5 days left on its week, lifting it 5. Lift 5 in all; the seed decides
between equals. The floor still holds: this section shows as many bundles as
its take says whenever that many exist, and the rank only chooses which.
```

Every clause names an input and the number it contributed, and the clauses
change with the inputs: *You asked for another and this is the composer's
answer, so it stands above every bundle nobody asked for; no number here
moves it below one* / *It serves a value an agent observed and nobody has yet
affirmed, so the 10 a declared value would lift it is not there* / *Nothing
about what you have been shown moves it, because you are not recording what
you were shown* / *Nothing on its record says in words that the house turned
this line of thinking down.* The floor's half of the old sentence survives at
the end, because it is still true; the *not because it won anything* half is
gone, because it is not. (A read that resolved no crown rank at all — none
can today; `document` always resolves one — still says *held by the floor*.)

### Where the numbers live, and what moved with them

**On the `feed_recipe` row**, as `crown_rank` beside `formula`, in
`recipe-fields`, in all three schemas, in `:revise`'s prefill, and in
`recipe-of`'s wire→map spelling — so the same form, the same
`written-by-a-person` wall, the same transitions history and the same
one-tap revert govern it with nothing new built. `check-recipe!` grew a
**sixth** assembly check (four ints, 0–100, zero legal for each) at the same
door as the fifth; `recipe-guarantees` says so; `formula-schema`'s own
*"the moment this needs a third field"* sentence is untouched, because the
crown's rank is a **sibling** field for a different question — *which of
these do you keep scrolling past* is the contest's, *which of these is worth
your Saturday* is the crown's.

`feed/recipe-diff` narrates the crown's numbers beside the contest's
(`crown-rank-diff`: *In the crown, serving a value this house declared lifts a
bundle 20 instead of 10.* / *The crown's rank turns OFF …*), so the
proposal's diff is ready for waymark-1uv.5 to feed.

**One thing that did not come free, met before it could bite.**
`feed_recipe/:revise` overwrites `recipe-fields` wholesale — *an omitted
optional clears, so the stored fields are exactly the set the guard judged* —
which is the right law for a form and the exact clearing 8um.3 discovered for
the contest: a `recipe_proposal` staged today has no word for `crown_rank`,
so `apply-the-order` would have reset a household's four numbers to the
deployment's on every order-only apply. It now reads the target's current
`crown_rank` and carries it through unchanged. That is a change to what the
apply door WRITES, and it is the one fingerprint that moved (below).

### What this bead deliberately did not do

- **`outcomes-are-few` stands.** Removing it is waymark-1uv.3 and the epic's
  order forbids it landing first. Every docstring this bead touched says the
  cap is *still there today and ruled out of the door*, in those words.
- **The agent is not the rank.** Nothing here reads an agent's opinion. The
  epic's A/M/never-B ruling is written into the block comment above
  `default-crown-rank`, where the next person to add an input will read it.
- **The crown chip's rule (`ask` rides only when the crown carded nothing) is
  unchanged.** 1uv.3 re-reads it; with a rank, asking may mean *rank mine
  first* rather than *let one more in*, and that is that bead's call.
- **`contested-sections` is unchanged.** The crown is still outside the
  contest — the contest's step is never its sort key — while its own rank
  reads the same rows. The docstring says which is which.
- **The screen joins, it does not derive.** *Why this order* opens into
  `recipe.crown_rank_says` beneath the contest's sentence; a crown card's
  *Why this card?* opens with the lift and the inputs off its own
  `why.crown` before any network, replaced by the server's sentence when
  anybody asks. `ui-drive.mjs` was not extended (filed).

### Where the law is proved

- **`feed-test`**, two deftests, for the half a driver with one world cannot
  arrange: the arithmetic as a **pure function** — six bundles ordered
  identically under two unrelated seeds, a request-answering bundle first
  with a negative lift, all four at zero reducing to `[tier 0 hash]` — and the
  field: the four numbers on the wire narrated with the numbers in the
  sentence, a row naming some keeping the rest, a nonsense number refused at
  assembly by name, the diff's sentences, and a household POSTing its own
  `crown_rank`, the very next read answered by it with the contest's two
  numbers untouched, and a revise to four zeros saying *The crown's rank is
  off*.
- **`workqueue10.outcome-test` § 16**, over the live ring handler and the
  household's own registry: a bundle the house declined **in words**
  (`verdict_reason never_this` against a `not_this_week`), the clock walked a
  week and a day so the recomposition may be staged past `not_before`, then
  three bundles on offer — one nobody asked for, one answering the member's
  own `composition_request`, one superseding the declined line — under the
  member's own `scope "mine"` recipe with the crown widened to ten. The cited
  bundle stands first with `asked true`; the fresh line reads lift 17 and the
  never-this line lift 9 with `declined "never_this"`, and both citations
  quote the numbers; nobody recording means no `seen` and the sentence says
  so; then the member turns their record on, is shown the fresh bundle three
  mornings, and it reads `seen 3, cooled 1, lift 15` with *1 step cooled,
  holding it 2* in the citation — still second, and all three still on the
  page, which is the floor. The fixture's engine gained a `:now-fn` over an
  atom for this test alone; the atom is nil — the real clock — for every
  other test and is put back in a `finally`.
- **`:feed/outcomes`**, nine claims added: `recipe.crown_rank` is four ints;
  `crown_rank_says` quotes the `declared` number and *never this* back; a
  crown card's `why.crown` carries an int `lift`, a boolean `asked`, a
  `value` in {declared, observed} and an int `days_left`; an uncited bundle
  reads `asked false` and a member-declared value reads `declared`; the
  explained citation says *Ranked*; and — the load-bearing pair — with the
  jfv.20 bundle that answers the member's own request and a new uncited
  bundle both carded (two pieces each, the bundle floor), the cited one reads
  `asked true`, stands **first** in the crown, and its citation says why.
  Both are declined before the obligation returns, so the engine handed on is
  the engine found. `:feed/formula`'s *walled* claim now names `decide`
  alone, with the reason in a comment: the crown is no longer a witness that
  a section can be untouched by the record.

### Recorded here, for whoever comes next

- **`make check-queue` is unmoved: 37 kinds, 11 warnings, 49 scenarios.**
  This bead declared no scenario — everything it added is a READ over a live
  engine and `scenario.clj` never writes — and no guard.
- **No new kind, no new table, no migration.** `feed_recipe` grew a SCHEMA
  field, and `:schema` is not one of `fingerprint-of`'s facets (0k4's
  correction, re-verified rather than re-asserted): `feed_recipe` is
  `9e5ba71d…` on `HEAD` and on this tree.
- **ONE fingerprint moved, and it is the same one 8um.3 moved, for the same
  kind of reason.** Computed over the whole 50-kind census — the household's
  own plus everything the module table enrols — on `HEAD` (`0c34b8c`) and on
  this tree: 49 byte-identical, including `outcome` `6f57c0d5…`,
  `outcome_piece` `8db51a4b…`, `verdict_reason` `8213d8c5…`, `feed_view`
  `c45ce1de…` and `composition_request` `eeb69200…`. `recipe_proposal` goes
  `782e6b4e…` → `d26ac9e4…`, and the mover is `apply-the-order` — a
  `defhandler` on `:apply`, so its body is inside `machine.actions`. The door
  now writes one more thing (the target's own `crown_rank`, carried through),
  which is a real change to what a tap lands, so a law revision is the honest
  answer; `boot-revise!` writes one at the next boot. The bead predicted zero
  movement; the prediction missed the carry-through, and the alternative —
  letting an order-only apply silently reset a household's four numbers —
  was not worth a clean census. waymark-1uv.5 will move this kind again when
  the proposal learns the field, and that is fine.
- **The rank reads the CHAIN's words and not the offered bundle's own
  pieces' words.** A piece declined `not_this` with a quick word on the
  bundle currently on offer is a said word about THIS bundle, and it is not
  an input today — it would be one reason query per declined piece on top of
  the per-candidate cost, for a signal the bundle's own `impact` union
  already reflects (an answered piece is off the verb). Filed as
  waymark-1uv.11.
- **`not_before` and `declined_count` are on the row and are NOT inputs
  yet.** That is waymark-1uv.10's design — the decline's date arm at
  `a-recomposition-waits-its-turn` becomes the rank's cooling input when the
  cap comes off, and it has to answer how a floor and a cooled-to-zero
  recomposition meet. `crown-lift` takes an inputs map, so a sixth clause is
  one key and one weight; nothing here has to be undone for it.
- **`crown-scan-cap` has arithmetic behind it and no measurement**, exactly
  `view-scan-cap`'s position at 8um.3 (waymark-hge). Filed beside it as
  waymark-1uv.12; the screen walk is waymark-1uv.13.
- **`why.crown` is nested, not flattened, on purpose.** The contest's
  `why.seen` / `why.cooled` stay the contest's, absent on every crown card;
  the crown's own `seen` / `cooled` ride inside `why.crown`, so a reader can
  tell *the contest cooled this* from *the crown read the same rows*, and
  `:feed/formula`'s existing claim that no crown card carries `why.seen`
  stays literally true.
- **Under a preview the rank reads the PREVIEWED member's rows and requests**,
  because `:principal` is theirs — the contest's own sentence, and the crown
  chip's. The previewer can therefore read `why.crown.asked` and
  `why.crown.seen` about that member, inside the contract `feed.preview_as`
  already has.
- **A cursor page re-ranks the crown, and it must not matter:** the crown
  lives on the day's first page (`render? false` on an archive walk), so the
  archive offset never counts into it.
- **`reason-weights` spells the four tokens as a literal.** The reason kind's
  enum is read at runtime by `reasons-doc` for the chips; the WEIGHTS are law
  from the epic's ruling, and reading them off the enum's order would make a
  fifth word inserted in the middle re-weigh the others silently. The two
  spellings should be kept in step by hand, and the docstring says so.

## Built — 1uv.5, the rank is tunable (2026-08-26, waymark-1uv.5)

Option A of § *The agent's part*, landed: **an agent proposes numbers for the
crown's declared formula through `recipe_proposal`; a person applies; the diff
speaks the household's words; the numbers it replaced stay readable.** The
epic's ruling was that the crown is the one place a person acts on a machine's
word and that word must be readable — so the whole of this bead is that a
machine's *"count declared values 12, not 10"* arrives as a sentence a person
taps under, through the same door, past the same walls, as any change to the
order the morning is read in. Nothing new was built at the recipe's wall.
`feed_recipe.clj` is untouched.

### The door, one field wider — as landed

`recipe_proposal` grew `crown_rank` and `current_crown_rank`, both optional,
mirroring `formula` / `current_formula` line for line (8um.3's spelling), on
the row schema, the create form and the prose. Both wear
`feed-recipe/crown-rank-schema` — the recipe's own — so the form refuses a
nonsense number before any wall is reached: `{"declared": 101}` is a 422 at
the proposal's own door, the same 422 the recipe's revise form would have
given. That is why **no scenario was declared for the crown at the create
door**: `the-prepared-input-fits-the-door` cannot be reached with a
`crown_rank` the schema admits, and a scenario that expected the guard would
have met the form. `make check-queue` is unmoved — 37 kinds, 11 warnings, 49
scenarios judged.

Every wall that read the contest now reads the crown beside it, in the same
breath and for the same reason (`:revise` overwrites the authored surface
wholesale):

- `the-prepared-input-fits-the-door` judges `{label, order, formula,
  crown_rank}` against `feed-recipe/recipe-input`.
- `the-order-will-assemble` hands `:crown-rank` to `feed/check-recipe!`, so
  the sixth assembly check judges a proposal as it judges a row.
- `the-staging-is-current` asks whether `current_crown_rank` is what the
  target row reads today — *ranks its crown differently today than this
  proposal says it does* — compared after the deployment's numbers are filled
  in (`same-crown-rank?`), so an absent rank and one spelling the built-in's
  numbers are the same crown and neither is refused for the difference.
- `the-order-has-not-moved` asks it again at the tap — *ranks its crown
  differently now than it did when this was staged* — so a member who moved
  the numbers themselves between staging and tapping meets a refusal by name
  and never a stale rank written back over their edit.
- `apply-the-order` writes the numbers the proposal named, else the numbers it
  was staged against, else — for a proposal staged before the field existed —
  the target's own, read now. The last arm is 1uv.2's carry-through kept
  whole; the first two make it the proposal's own sentence rather than a
  read behind its back.

**One consequence worth saying plainly.** An order-only proposal staged
against a row whose crown the house has tuned must now carry
`current_crown_rank`, or it is refused at staging — the contest's own law,
one field over. A composer staging a seam change reads `recipe.crown_rank`
off the feed document first, exactly as it reads `recipe.order` and
`recipe.formula`. The twin proves both halves: the blind one refused, the
carried one applied with the tuned numbers intact.

### The diff speaks the household's words — and walks the map's keys

```
The order itself is unchanged, line for line.
In the crown, serving a value this house declared lifts a bundle 12 instead of 10.
In the crown, each rank of the house's quick word about a line of thinking holds a bundle 3 instead of 2 — a never-this line of thinking is held 12 instead of 8.
```

`feed/crown-rank-diff` is no longer four lines naming four keys. It walks the
keys of the two rank maps — `default-crown-rank`'s first, in its own order,
then anything either side names beyond those — and says each moved number in
`feed/crown-rank-words`' sentence for it. A key with no words yet renders as
*In the crown, crown_rank recomposed is 3 instead of 0.* rather than as
silence. This is the one rule the diff was written to: **waymark-1uv.10 adds
a cooling input and waymark-1uv.6 a judgment, and a diff that named its four
keys would have rendered a fifth number's change as nothing, which is the one
thing a person tapping under a diff cannot read.** The OFF sentence counts the
keys the same way (*every one of its 5 numbers is 0*).

The `:declined` line does the multiplication out loud, on purpose. The
number a household writes is per RANK of the word and the number it feels is
the strongest word's; a machine proposing to move it should be readable
without doing ×4 in your head. The 4 is `reason-weights`' top and is kept in
step by hand, like that map's own note.

The card is `proposal-says` unchanged: the stored diff, read never
recomputed, plus what it is staged against and *N rows behind it*.

### What the tuning agent reads, and the grant it needs

A tuning agent proposes numbers the way an insight proposes a finding: from
rows it read, cited. What it reads, per input of the rank:

| the rank's input | what the agent reads | where |
|---|---|---|
| shown-and-passed-over (`:cooled`) | the member's own exposure rows — one per card per day | `feed_view` (`?member=…`), read-only |
| the house's quick words (`:declined`) | which line of thinking was declined and in what word | `verdict_reason`, read-only |
| declared over observed (`:declared`), freshness (`:fresh`) | each crown card's `why.crown` — lift, `value`, `days_left`, `seen`, `declined` | the owner's feed under `feed.preview_as` with `?explain=1`, the line the composer already holds |
| the numbers in force | `recipe.crown_rank` / `crown_rank_says` on the feed document; the row's own `crown_rank` (nil is *the deployment's*) | the feed, and `feed_recipe` read-only |

**The grant scope line, exactly** — `recipe_proposal:create` plus read-only
lines; it is the composer contract's block widened by these four:

```json
{"kind": "recipe_proposal", "actions": ["create"]},
{"kind": "feed_view",       "actions": []},
{"kind": "verdict_reason",  "actions": []},
{"kind": "feed_recipe",     "actions": []}
```

Never `feed_recipe` revise or create — that is the wall the whole surface
exists to keep, and the pack re-proves it in the same breath as the staging.
`workqueue10.rank-tuning-test` mints exactly this scope through the real grant
door and proves the three reads answer 200 under it.

**Evidence: the outcome's discipline, already here, and one thing not added.**
`it-cites-what-it-read` stands on the create door as it did — at least one
address, each `/api/<collection>/<id>` — and a rank proposal is held to it
like any other. The outcome's guard goes one step further and consults the
registry (`:reads [:storage]`, *naming a collection this house serves*); that
step was weighed and **not** added, for the reason `address?` already records:
the create door's first three walls are declaration-time, and a registry
consult would drop the birth of every proposal out of the no-database tier to
catch a dead link on a card. One consequence the live test pins down: a
filtered listing is not an address. `/api/feed_views?member=…` is what the
agent READS; what it CITES is the rows that listing returned, by their own
addresses — and a proposal citing the query string is refused by name.

### The revert — what is one tap today, and what is not

The epic's table said *one tap, the proposal's own machinery*. Read
carefully, that is true of the apply and half-true of the way back, and the
half is worth writing down:

- **One tap back to the deployment's numbers:** `retire` on the recipe row
  (when it is the house's only row) — the feed falls through to the
  built-in, crown and all; `restore` undoes it. Already there.
- **Back to the numbers a proposal replaced: two copies, not one tap.**
  waymark-by4 is still open, and `history.clj` deliberately does not serve a
  transition's recorded `inputs` on the wire. What IS on the wire is the
  applied proposal's own row: `current_crown_rank`, `current_order` and
  `current_formula` are, by construction, the exact `revise` input that puts
  the recipe back — read them off `/api/recipe_proposals/<id>`, post them
  through `feed_recipe`'s own revise door with the row's etag. Both suites
  prove the round trip restores `{declared 10 …}` and `crown_rank_says` says
  *lifts a bundle 10* again.
- **The cheap one tap this suggests** is not by4's ledger affordance but a
  `revert` verb on an APPLIED proposal — re-post its `current_*` through
  `revise` as the member, past the same walls, under the same fence. Filed as
  waymark-1uv.14 rather than built here, because it is a new door on
  the kind and by4 should decide whether the ledger or the proposal owns it.

### Where the law is proved

- **`recipe-proposal-test`**, on the twin, two deftests: the diff as a pure
  function (the crown beside the contest; an absent rank and the built-in's
  numbers the same; the four-argument spelling silent about the crown; a
  fifth key rendering by its wire name, in both directions, and counted in
  the OFF sentence), and the whole loop against a household row — the card's
  sentence with both numbers and *2 rows behind it*, a nonsense number
  refused by the form, a misread crown refused at staging by name, the
  member's tap landing the numbers through `:revise` under the member's name
  with order and contest untouched, the order-only proposal refused blind and
  applied when it carries the numbers, the fence refusing a rank proposal
  after the member moved the crown, and the revert from the applied row's
  `current_crown_rank`.
- **`workqueue10.rank-tuning-test`**, live, the household's own registry: the
  tuning scope minted through the real grant door; the three reads answering
  under it; a query-string citation refused; the decide card's sentence; the
  agent's own apply concealed by its create-only leash (404 — the four-eyes
  wall is the twin's to prove); the member's apply; `recipe.crown_rank` and
  `crown_rank_says` on the next read and `crown_rank` on the row; the fence
  after the member's own revise; the revert.
- **`:feed/staged-proposals`**, nine claims added after the stale flow and
  before the retire: the document carries a `crown_rank` map to stage
  against; the same leashed composer stages one number moved; the card
  reaches the member's decide section, says *In the crown* and quotes the
  number moving; the tap lands; the next read's `crown_rank` equals what was
  proposed and `crown_rank_says` quotes the new number; the order did not
  move. The obligation still ends where it began.

### Recorded here, for whoever comes next

- **`make check-queue` is unmoved: 37 kinds, 11 warnings, 49 scenarios
  judged.** No scenario, for the reason above; no new guard — every wall
  that grew is one that already stood.
- **Suites:** framework 768 tests, 5816 assertions, green (was 766 / 5776); household 251 tests, 2793 assertions, green (was 250 / 2757) — both in private
  databases (`wm_1uv5`, `wm_1uv5_f`).
- **ONE fingerprint moved, and it is the expected one.** Over the 50-kind
  census on `main` (`1d3f0ea`, the integration branch carrying 1uv.1, .7
  and .2) and this tree: 49 byte-identical — `feed_recipe` stays
  `9e5ba71d…`, `outcome`, `outcome_piece`, `verdict_reason`, `feed_view` and
  `composition_request` unmoved. `recipe_proposal` goes `d26ac9e4…` →
  `908661e4…`; the movers are `the-prepared-input-fits-the-door`'s
  `:judges` (one key wider), the bodies of the two staleness guards (one
  more question each) and `apply-the-order`'s body (what the tap writes).
  Each is a real change to what a door judges or lands; `boot-revise!`
  mints one revision. 1uv.2 predicted this move by name.
- **`feed_recipe.clj` was not touched, and the one place that still names
  four keys is `recipe-of`'s wire→map spelling** (beside `crown-rank-schema`
  and `check-recipe!`'s sixth check). The fifth number's owner extends those
  three — never the diff, which needs no edit.
- **`outcome.clj` was not touched** (waymark-1uv.3's, in flight beside this).
- **`staged-changes-are-few` stays at three** and is now the pace on rank
  tuning too; 1uv.7's sentence — three waiting being plenty for one agent
  tuning numbers — held in practice: the twin's tuning flow never had more
  than two open.
- **Not done, on purpose:** no `revert` door (waymark-1uv.14); no ledger affordance
  (by4); no agent input to the rank (1uv.6); `ui-drive.mjs` not walked
  (1uv.13); the composer's grant-scope block in § *Built — jfv.5* is widened
  by a sentence pointing here rather than rewritten.
## Built — 1uv.3 and 1uv.10, the cap leaves and the date becomes a number (2026-08-26, waymark-1uv.3, waymark-1uv.10)

**The owner's ruling, verbatim:** *I don't think it makes sense to limit the
indexing. It makes more sense to just rank them.* This is the epic's third
bead and its tenth, landed together because both take an arm off the same
create door for the same reason — a wall on writing standing in for a rank on
attention — and because 1uv.7 filed the tenth to land *with 1uv.3, never
before 1uv.2*. One section for both, under its own headings, so the reader
who wants only the cap's removal can stop at the first horizontal rule.

`make check-queue` reads **37 kinds, 11 warnings, 49 scenarios judged** —
the numbers unmoved, and one line under them moved: `outcome` reads *11
refusing guards, 5 named by a scenario* where it read 12. No scenario ever
named the cap, which is its own small verdict on what it was for.

### 1uv.3 — `outcomes-are-few` leaves, and the request kind stays

**What went.** `weekly-cap` (the number, 2), `outcomes-are-few` (the guard,
its count over the week's rows, its *next Monday* refusal), the private
`week-start` helper, the `ChronoUnit` import and the `store` require nothing
else in the file used — and the guard's place in `:create-guards`. Nothing
replaced it at the door: the vector ends at `the-request-is-open`, and the
comment above it says why there is no *pace last* any more. The section
header that held the number is now the place the removal is explained, so a
reader of the code finds the ruling where the number was.

**What stayed, and changed its meaning.** `the-request-is-open` stands
exactly as it did — a wall on CITING, all four arms: no such request; already
answered (and by which outcome); expired; the leash run out; an aim not
served. It stays because a cited outcome still answers the request *in the
same stroke* (`stage-the-outcome` invokes the request's `answer` door before
its own insert), and a citation that was not good would burn a person's pull
on an outcome that answered nothing. What the citation BUYS is what moved:
under the cap it was admission past the count; under the rank it is the
crown's first tier (`feed/crown-key`, waymark-1uv.2's *asked-for is a tier,
not a weight*). Every sentence that said *admitted past the cap* — the
guard's own refusal (*or cite none and stage inside the week's allowance*),
`cited-request`'s docstring, `request_id`'s help, `composition_request`'s
namespace doc, `only-a-person-asks`'s explain, `answered_by`'s help, the
`answer` door's input help and one-way sentence, `main.clj`'s enrolment
comment — now says *stands first in the crown* instead. `only-a-person-asks`
keeps its wall with a new reason: an agent that could mint a request would
put its own initiative in the tier only a person's ask may stand in, which is
law 6 (*the system never spins for them*) read at the crown rather than at a
cap. *(Amended 2026-08-28, waymark-sfe: the wall is now a permission — an
agent holding a grant that names `composition_request.create` may file the
ask. Law 6 survives word for word, because the only way that scope exists is
a person's approval of an `approval_request`; what an agent's own initiative
reaches is still nothing.)*

**The crown chip's rule, re-read — decided: the ask rides always.** jfv.20
had `ask` ride only when the crown carded nothing (*answer what is there
first — a chip beside an unanswered bundle is the page asking for more
before the person has said what they think*). That rule was the cap's: when
asking meant *let one more in*, an ask beside an offer was a request to
exceed the allowance before the allowance had been judged. Under the rank
asking means *rank mine first*, and the two are not in competition — a
person may want a request standing while two bundles are on offer (neither is
the Saturday they had in mind), the rank puts the answer first when it
arrives, and the bundles on offer are still there to be answered. Holding the
chip back would be the page deciding when the person is allowed to want,
which is the wall the cap was, one door over. So `feed/crown-doc` rides `ask`
and `says` on every first-page read where the engine holds both kinds;
`empty` is still said, so the screen can paint the difference; and
`crown-says` gained a third sentence for the crown with bundles on offer and
nothing standing — *Not the week you had in mind? Ask, and the composer
stages another at its next sitting — it stands first in the crown when it
arrives.* The two older sentences dropped their *past the week's cap* clause
and say *stands first in the crown* instead. The chip's `note` says the same.
`135-feed-screen.js` needed no change — it painted whatever the server
handed it — and `ui-drive.mjs`'s walk still checks the chip on the empty
crown, because that is where it stands alone; its comment says the chip now
rides everywhere.

**The pack.** `:feed/outcomes` lost the claim *a bundle is on offer and the
crown still offers 'compose me another'* (it is now false by design) and
gained its inverse: the crown, empty or not, offers no ask is a violation,
and `empty` not being a boolean is one too. The setup comment that stood the
ask claim down when a neighbouring run left a bundle on offer is gone with
the rule that needed it. The rank flow's comment no longer says the cap
admits the composer's second of the week; it says the third staging of the
run is counted by nobody.

**The tests.** § 3 is the inverse of the test that stood there:
`a-composer-stages-without-limit-and-the-rank-decides-what-shows` — five
stagings by one composer in one call, five 201s, five distinct ids, and a
second composer equally unbounded. § 15's
`a-persons-request-admits-one-outcome-past-the-cap` became
`a-persons-request-is-answered-in-the-same-stroke-and-nothing-counts`: the
uncited third is admitted, the cited one answers the request and is refused
a second time by `the-request-is-open`, and an uncited fifth is admitted like
every other. `the-crown-carries-the-pull` now asserts the ask rides beside a
bundle on offer, and that with nothing standing the chip's sentence says
*first in the crown*; it also declines its bundle at the end, which it did
not before. The namespace docstring's *the weekly cap, Monday to Monday,
counting rows* bullet is replaced.

---

### 1uv.10 — the date leaves the door and becomes `:early`

**The guard, as it stands now.** `a-recomposition-waits-its-turn` keeps arms
(a) *this house serves no outcome …* and (b) *… is still on the fridge
waiting for an answer* exactly as they were — shape and a dedupe law. Arm
(c), *the house said not this week, and meant it until `not_before`*, is
gone; the guard no longer reads `:now`. Its `:open` sentence says where the
question went: *How soon it comes back is not this door's question — the
decline stamps when the house is willing to hear it again, and the crown's
rank holds an early recomposition back by every day it is early, on the card,
in words.* `moot-the-rest` still stamps `not_before` and `declined_count`,
and its comment now says what they are: the person's verdict in the shape the
rank reads. `not_before`'s help says a recomposition staged before it is
*admitted and RANKED for it*.

**The rank's fifth number.** `feed/default-crown-rank` gained `:early 2`,
and `crown-lift` a fifth line:

```clojure
lift = declared × [the value is declared]
     − cooled   × steps cooled
     − declined × weight of the strongest word on the chain
     + fresh    × days left on the week
     − early    × days before the house said it would hear it   ; 1uv.10
```

The input is **days early** — whole days from now until the SUPERSEDED
outcome's `not_before`, read off that row (decoded through the kind's own
schema, since the raw row holds a string), rounded UP where `days-left`
rounds down (*a recomposition an hour early is a day early, because the
person's date is a day and the house has not reached it*), zero once the day
has passed, absent for a bundle that recomposes nothing. `declined_count`
rides beside it on every recomposition's `why.crown` as `turned_down`, and
the date itself as `not_before` while the bundle is early; neither key rides
on a bundle that supersedes nothing, so a reader can tell *nothing to be
early for* from *on time* — the pack asserts the silence.

**Why per day, why twice `:fresh`, and why `declined_count` is not a second
multiplier — decided and recorded.** The arithmetic is the mirror of
freshness: a day left lifts 1, a day early holds 2, so a recomposition a
week early (10 declared + 7 left − 14) reads lift 3 and sits below every
fresh bundle serving a declared value (17) and below one serving an observed
value (7), while a recomposition on the day the house named reads exactly as
a fresh line does. The chain's length is already spent on the distance — one
decline puts the day a week out, two put it three weeks out, four put it
half a year out — so a recomposition four declines deep and staged the next
morning is 180 days early and reads lift −343: cooled to the bottom of any
crown, and still on the page if the take reaches it. Multiplying by
`declined_count` on top of that would charge the house's verdict twice, and
the sentence a person reads (*the house said not this week 2 times and meant
it until …*) already carries the count as words. 1uv.7's *the decline's
quick reason* (never_this retires, wrong_time cools, not_this_way /
wrong_piece cool less) was **not** folded in here: the word is already the
rank's `:declined` input, read off the same chain, and the date and the word
are two inputs because they say two things — *this line, never* is not *not
until March*. A word that should retire a line outright is waymark-1uv.11's
question about the offered bundle's own pieces' words and the reason kind's
own `never_this`, not this bead's.

**Cool to a floor or to zero — decided: cool, never hide.** 1uv.7 left one
real question open: the exposure floor and a recomposition the person said
not to hear yet collide precisely at the crown's `:take`. The answer is the
principle's own clause 2 — *the rank chooses WHICH, never WHETHER; a rank
that hides is the window reason 3 refused* — and it is recorded here with its
cost said out loud. The recomposition is cooled by every day it is early and
is never dropped; when the crown holds more candidates than its take (the
ordinary case for a house whose composer works, and the case the cap's
removal makes common) it is simply off the page; when the crown holds fewer,
the floor reaches it and it is SHOWN — last, with its card saying *The house
said not this week about this line of thinking 1 time and meant it until
2026-09-02 — this recomposition is 7 days early, holding it 14*, and the
floor clause after it. That is the person's verdict honoured where the
person can read it, not overridden: a card that says *you said not yet, and
here is how early this is* is a different thing from a card that pretends
the decline never happened, and the person's next tap — `not_this_week`
again — pushes the day three weeks out and the number with it. The fallback
1uv.7 recorded (keep arm (c) and say it enforces the person's verdict) was
not needed, and the reason it was not is worth keeping: a wall enforces a
verdict by forbidding a ROW, and the verdict was never about the row — it was
about the person's attention, which the rank protects directly. The pure
test proves the sort holds it last; § 17 proves it is on the page.

**Where the numbers live.** `feed_recipe/crown-rank-schema` gained `:early`
with its own label and help; `recipe-of` reads it off the wire; the field
prose's example and help say five. `check-recipe!`'s sixth check names five
keys; `recipe-guarantees` says *the crown's rank is five*; `crown-rank-as-
written` writes `early`; `crown-rank-says` gained its clause (*… and each
day a recomposition arrives before the day the house said it would hear that
line of thinking again holds it 2, so a bundle the house said not to hear yet
sits last rather than out of sight*); `crown-rank-diff` narrates it (*In the
crown, each day early a recomposition arrives … holds it 5 instead of 2*)
and the *turns OFF* sentence counts five. `recipe_proposal/apply-the-order`
carries the whole map through unchanged, so nothing there moved.
`crown-card-says` says *five numbers* and gained two clauses — the early one
above, and for a recomposition past its day: *It recomposes a line of
thinking the house turned down 1 time, and the day the house said it would
hear it again has passed, so nothing holds it for that.*

**The tests.** `feed-test`'s pure deftest gained `early` (lift 3) and
`on-time` (lift 17 with a chain of three — past the date nothing holds it),
the early bundle last in the seed-independent order, `:early 0` in the
all-zero case, and the diff sentence; the recipe deftest expects five
numbers everywhere, `recomposition arrives` in the sentence, and turns the
rank off with five zeros. `outcome-test` § 7 flips its refusal into an
admission (*the date is the rank's input now, not the door's*) and keeps arm
(b)'s refusal; § 16's clock walk is now explained as making the
recomposition ON TIME (early 0, `turned_down` 1, no `not_before` quoted, the
citation saying *has passed*) rather than getting it past a door; and § 17
`an-early-recomposition-is-admitted-and-cooled-not-refused` pins the clock,
declines a bundle, recomposes it the same morning (201, `declined_count` 1),
stages a fresh line beside it, widens the reader's own crown to ten, and
reads: both on the page, the fresh one above, the early one at `early 7`,
`turned_down 1`, the decline's own `not_before` quoted back, lift 3, and the
citation saying *7 days early*, *holding it 14*, the date, and the floor
clause; the fresh card carries neither key. It declines both at the end.

---

### Fingerprints, and what did not move

Computed over `workqueue10.main/check-resources` (37 kinds) on `main`
(`1d3f0ea`) and on this tree: **all 37 byte-identical.** `outcome` is
`6f57c0d5…` on both — create guards are outside `fingerprint-of`, and
neither bead touched `:on-create`, a handler or an action guard.
`composition_request` is `eeb69200…` on both — every change there was
prose, and `safety-fp` projects only the three booleans, so the `:one-way`
sentence is not law. `feed_recipe`'s schema grew a field and `:schema` is
not a facet (0k4's correction, re-verified rather than re-asserted).
`recipe_proposal` is unmoved because `apply-the-order` carries `crown_rank`
as one map.

### Recorded here, for whoever comes next

- **`crown-scan-cap` (50) is now the only thing bounding the crown's cost**,
  and it has arithmetic behind it and no measurement (waymark-1uv.12). The
  cap's removal is what makes that bead matter; nothing here changed the
  number.
- **`insights-are-capped` still stands** (waymark-1uv.8, after its rank).
  The two removals here are the outcome kind's; the principle is wider and
  the order the epic set is still the order. *(It went, after its rank, in
  § Built — 1uv.8 below.)*
- **The decline's quick reason as a retire rather than a cool** is not
  built. `:declined` at `never_this` holds a bundle 8; the epic's own
  sentence *never_this retires the line* would want a tier below, not a
  weight, and that is a decision about the reason kind's meaning rather than
  this bead's arithmetic. Filed as a note on waymark-1uv.11.
- **A recomposition citing a request sits in the asked tier whatever its
  `early` says** — the tier is above every weight by construction. A person
  who declined a line and then asked for another gets the recomposition
  first if the composer cites the ask. That is law 6 and it is intended, but
  it is worth knowing.
- **The screen renders `why.crown.early` only through the server's
  sentence** (`?explain=1`), the way it renders every other input; the plain
  read's opener (`135-feed-screen.js`) quotes the lift and does not itemise.
  waymark-1uv.13's walk is where the crown's numbers reach a screenshot.
- **The tickler kind is untouched.** `next_offer_at` on a tickler is still a
  read-side rank input for the ticklers line and was never a wall
  (waymark-1uv.7's row); this bead borrowed its schedule and nothing else.

## Built — 1uv.8, the findings' rank and the cap that followed it out (2026-08-26, waymark-1uv.8)

**The owner's ruling, verbatim:** *Agents have infinite patience and time to
sift through that data and index it. … I don't think it makes sense to limit
the indexing. It makes more sense to just rank them.* 1uv.7 asked the ruling's
question of `insights-are-capped` and answered it: the insight IS the
indexing, the cap was the outcome cap's precedent and the same proxy, and the
line was misfiled as *outside the contest*. This bead is the answer built —
**in the epic's order: the rank first, the cap second, never the cap alone.**

So the `:insights` line now **ranks what it shows**, in exactly the crown's
shape one section down: **six numbers on the recipe row beside the crown's
five, one line of arithmetic, one new key on every insight card's `why`, and a
sentence that replaces the one that was never honestly about it.** The floor
stays at `:take 2`. The seed still breaks ties. `insights-are-capped`, its
number and its scenario-less wall are gone from the create door. No hash the
household's own kinds carry moved.

### The rank, as landed

```clojure
;; feed/default-insight-rank
{:diagnosis 10   ; what a law-4 diagnosis is lifted over a plain finding
 :declared 5     ; what a finding whose offered row serves a DECLARED value is lifted
 :cooled 2       ; what each step the contest says it has cooled holds it back
 :dismissed 3    ; what each finding the house DISMISSED on the same offer holds it back
 :declined 2     ; what each RANK of the strongest word said on those holds it back
 :fresh 1}       ; what each day of freshness left in the contest's window lifts it
```

```clojure
;; feed/insight-lift — the whole of the arithmetic
lift = diagnosis × [the finding is a law-4 diagnosis]
     + declared  × [its offered row serves a declared value]
     − cooled    × steps cooled
     − dismissed × findings the house dismissed on the same offer
     − declined  × weight of the strongest word said on those
     + fresh     × days of freshness left in the window

;; feed/insight-key — the sort, ascending. NO TIER.
[−lift  hash(seed ‖ card_id)]
```

Six inputs, and each is a number a person reads on the card:

| input | read off | as it rides `why.insight` |
|---|---|---|
| **a diagnosis** | the offer is a value's or a person's own affirmation (`feed/affirmation-doors`: `value.still_stands`, `person.still_with_us`); or the offer is on an outcome's own row; or the evidence cites an outcome the house `declined` (at most `evidence-read-cap` = 8 addresses followed) | `"diagnosis": "affirmation"` / `"recomposition"` / `"none"` — always present |
| **the value its offered row serves** | the offered row itself when the offer is on a value, else the offered row's own `value_id`; `declared` / `observed` off the value's state | `"value": "observed"` — only when the row serves one at all |
| **shown and passed over** | the same `feed_view` rows, window and `cooling-step` the contest reads | `"seen": 3, "cooled": 1` — only while recording |
| **the house's verdict record on the same offer** | `dismissed` findings on the same `{offer_kind, offer_id, offer_action}`, newest `dismissal-record-cap` = 5, counted | `"dismissed": 2` — always present |
| **the strongest word said on those** | the `verdict_reason` rows filed against each of them, the heaviest kept (`reason-weights`, the crown's own order) | `"declined": "wrong_time"` — only when a word was said |
| **freshness** | `max(0, window_days − days since created)` — the contest's own `:window-days` (14) | `"days_old": 0, "fresh_days": 14` |

…and the lift, `"lift": 24`, on the **plain read**, for the crown's reason.

On the wire, on every read, beside `recipe.crown_rank`:

```json
"recipe": {
  "guarantees": "… the contest is two numbers a person can read; the crown's rank is five; and the findings' rank is six. …",
  "insight_rank": {"diagnosis": 10, "declared": 5, "cooled": 2, "dismissed": 3, "declined": 2, "fresh": 1},
  "insight_rank_says": "Findings are ranked, not capped: an agent may publish as many as it finds, the line shows as many as its take says, and this is the whole of how it chooses which. A finding is the one card waiting on your answer that is not an obligation — it is the contest's own output — so unlike the ask, the conflict, the letter and the staged change beside it, it is ranked, by six numbers a person can read: a diagnosis — … — is lifted 10 over a plain finding; one whose next step serves a value this house declared is lifted 5; each step the contest says it has cooled — the same 3 days in 14 as the sections below, read off your own record — holds it 2; each finding you already dismissed on the same next step holds a new one 3, and the strongest quick word you said on those holds it 2 for wrong time, 4 for wrong piece, 6 for not this way and 8 for never this; and each day of freshness left in the same 14 lifts it 1, so a finding published today stands above one from last week and an old one sinks to the bottom and no further. The floor still holds — …"
}
```

### No tier, and the diagnosis is a weight — decided and recorded

The crown has a tier because a person's own `composition_request` is law 6
read at the crown, and no number a household writes may put the machine's
initiative above it. **Nothing in the findings' line is a person's.** Every
insight is the machine's initiative by construction (`:by :authored_by`, the
four-eyes wall), so `insight-key` is `[−lift hash]` and the pure test asserts
there is nothing above the lift.

1uv.7 said *law 4 first — the composer's duty fires first*, and the bead
heading said *first or lifted*. **Lifted.** Law 4 is a duty on the SYSTEM, not
a person's word, and a household should be able to turn a system's duty down
where it could not turn a person's request down; a tier cannot be set to zero
and a number can. At the deployment's numbers a fresh diagnosis (24) stands
above every fresh plain finding (14) and a two-week-old diagnosis (10) still
stands above a week-old plain one (7), which is the duty firing first in
practice; a household that writes `diagnosis 0` has said the duty does not
outrank the rest, and the diff tells them so in a sentence.

### The dismissal record is two numbers, and *never this* is not a retire

1uv.7's sentence was *a dismissed finding on the same offer cools hard, by its
quick reason — never_this retires; wrong_time cools on the tickler schedule;
not_this_way / wrong_piece cool the offer, not the subject.* What landed is
the crown's own reading of the same words: `:dismissed` counts the record and
`:declined` weighs the strongest word per rank, so a line dismissed twice with
*wrong time* said once is held 6 + 2 and a line dismissed once with *never
this* is held 3 + 8. **A retire is not built**, for 1uv.3's recorded reason —
*never_this as a retire rather than a cool is a decision about the reason
kind's meaning*, filed on waymark-1uv.11 — and a *tickler schedule* for *wrong
time* would be a `not_before` on the insight kind that nothing stamps. The
verdict record stays on the rank as numbers, which is where the epic put the
crown's, and the same follow-up decides both.

### The sentence that was never about it — what law 2 says now

`cooling-says` told every decide card: *Outside the contest: something
waiting on your answer appears because it must — an ask that expires, a
conflict, mail on your shelf, a change staged for a tap.* That list is law 2's
and it names no insight, because an insight is not an obligation with a
deadline; law 4 calls the diagnosis *an insight*. The line was outside the
contest by placement and not by nature.

**Decided: the line stays in `:decide`, and `cooling-says` learns an insight
is ranked.** The census does not move (a finding is still something to
DECIDE, and the pack's own claim says so), `contested-sections` does not move
(the contest's step is still never an insight's sort key — the rank reads the
same rows as its own input, the crown's arrangement exactly, and
`:feed/formula`'s walled claim stays literally true because the view count
rides inside `why.insight` and never as `why.seen`), and the decide arm's
sentence is **unchanged and still true of everything else in the section.**
What changed is that an insight card no longer hears it. Its arm opens:

```
Ranked 1st of 2 among findings by recipe.insight_rank — six numbers this house
can read — and this is the arithmetic for this card. A finding is not an
obligation: the ask that expires, the conflict, the letter and the staged
change beside it in this section stand outside the contest because they must
appear; a finding is the contest's own output and is ranked instead. It is a
diagnosis — its next step is a value's or a person's own affirmation, the
composer's duty firing first — which lifts it 10. Its next step serves a value
an agent observed and nobody has yet affirmed, so the 5 a declared value would
lift it is not there. Nothing about what you have been shown moves it, because
you are not recording what you were shown. Nothing on the record says you
dismissed a finding on this same next step. Published 0 days ago, with 14 days
of freshness left in the window, lifting it 14. Lift 24 in all; the seed
decides between equals. The floor still holds: this line shows as many
findings as its take says whenever that many exist, and the rank only chooses
which.
```

So law 2's *outside the contest* now describes the decide section's OTHER
citizens — asks, letters, conflicts, staged proposals — and the insight arm
says so in its second sentence. (The ticklers line is waymark-1uv.9's, running
beside this bead; nothing here touched it or the population, and its own
answer to the same sentence is its own.)

### The cost, decided: a bound with a note, the crown's answer again

`feed/insight-scan-cap` = 50 published findings, newest first, and the
document says when it is reached — *The findings' line read to its cap and
stopped — the newest 50 published findings were ranked, and older ones were
not read today. Those are the ones the rank's own freshness already places
last; a house with more than 50 unanswered findings has an agent to talk to
before it has a cap to raise.* Per candidate: a subject read (`set-aside?`, as
before), one more raw load of the offered row, one dismissal query on the
offer's three fields, a reason query per prior dismissal (at most five), a
value read where the row names one, and — only for a finding that is neither
an affirmation nor an outcome offer — up to eight citation reads looking for a
declined outcome. A stored score was refused for 1uv.2's two reasons, which
hold here unchanged: no honest writer, stale on every view row and every
reason. The archive's, the crown's and the findings' caps are three bounds
with three notes, folded by section for the crown and by POPULATION for the
findings, because `:decide` holds several lines and only this one is ranked.

### Where the numbers live, and what moved with them

**On the `feed_recipe` row**, as `insight_rank` beside `crown_rank`: in
`recipe-fields`, in all three schemas (`insight-rank-schema`, the crown's
shape one field over), in `:revise`'s prefill, and in `recipe-of`'s wire→map
spelling — single-line additions beside the crown's, so the same form, the
same `written-by-a-person` wall and the same transitions govern it.
`check-recipe!` grew a **seventh** assembly check (six ints, 0–100, zero legal
for each); `recipe-guarantees` says so.

**`feed/insight-rank-words` and `feed/insight-rank-diff`** are a SIBLING map
and a sibling diff rather than a widening of `crown-rank-words`, on purpose:
the two ranks are two fields with two sentences each, and a diff that walked
one map for both would have to be told which field a key came from. The shape
is identical and generic over the map's keys; `recipe-diff` folds the findings'
sentences after the crown's (*Among findings, each finding the house already
dismissed on the same next step holds a new one 5 instead of 3.* / *The
findings' rank turns OFF …*). **A person tunes it today** through
`feed_recipe`'s own create and revise. **The agent's path is not built**:
`recipe_proposal` carries `crown_rank` / `current_crown_rank` and no
`insight_rank`, so the diff is ready and the proposal cannot yet feed it —
left out to keep `recipe_proposal.clj` untouched while 1uv.6 and 1uv.9 run
beside this bead, and filed (waymark-bck), with one hazard named: `apply-the-order`'s
carry-through reads only `crown_rank` off the target, so an order-only apply
on a row that names `insight_rank` would reset the findings' six.

**On the insight kind**, `daily-cap`, `insights-are-capped`, the `Instant` and
`ChronoUnit` imports nothing else used, and the guard's place in
`:create-guards` — gone. The ns docstring's *THREE A DAY* bullet is now
*RANKED, NOT CAPPED* and says why; the create-walls comment no longer says
*pace last*; `a-dismissed-finding-does-not-come-back`'s docstring no longer
calls re-publishing *the cap doing its work* and says what the rank reads
instead; `listed` no longer burns a cap. The screen (`135-feed-screen.js`)
opens *Why this order* into `insight_rank_says` beneath the crown's sentence
and an insight card's *Why this card?* with its own lift and inputs, the
crown's two additions mirrored.

### Where the law is proved

- **`feed-test`**, two deftests, the crown's pair one section down: the
  arithmetic as a **pure function** — eight findings ordered identically under
  two unrelated seeds, `insight-key` two elements long with nothing above the
  lift, all six at zero reducing to `[0 hash]` — and the field: six numbers on
  the wire narrated with *ranked, not capped* and *8 for never this*, a row
  naming some keeping the rest, a nonsense number refused at assembly by name,
  the diff's sentences beside the crown's in one `recipe-diff`, a household
  POSTing `insight_rank {diagnosis 40 fresh 0}` and the next read answered by
  it with the crown's five untouched, and a revise to six zeros saying *The
  findings' rank is off*.
- **`workqueue10.insight-rank-test`** (new), over the live ring handler and the
  household's own registry, one deftest of 61 assertions: a leashed finder
  (`insight:create` + `value:create`) writes an OBSERVED value and publishes a
  finding offering its `still_stands` beside a plain finding offering a task's
  own light verb, under the member's `scope "mine"` recipe with the insights
  line widened to ten; the diagnosis stands first at lift 24 with
  `diagnosis "affirmation"` and `value "observed"`, the plain one at 14 with no
  `value` key, both citations quoting the numbers, the plain card saying
  *outside the contest because they must appear* of its neighbours and
  carrying no `why.seen`. Then the member dismisses two findings on one task —
  the first with `verdict_reason wrong_time` — and a third on the same offer
  reads `dismissed 2, declined "wrong_time", lift 6` and stands below the
  fresh plain finding. Then **six findings in one day, one author, one offer:
  six 201s**, all six on the page beside the other three (`why.of` 9), the
  diagnosis still first and the dismissed line still last; and with the line
  narrowed back to the deployment's `:take 2`, the page shows exactly two —
  the diagnosis and the freshest plain finding — with the dismissed line
  below the floor rather than refused. Every finding is dismissed before the
  test returns.
- **`:feed/insights`**, inverted and widened: the fill loop that published
  until the door said no and asserted `insights-are-capped` now publishes six
  in one breath and asserts every one landed; eight claims added —
  `recipe.insight_rank` is six ints, `insight_rank_says` quotes the `diagnosis`
  number, *never this* and *not capped*, the carded finding's `why.insight`
  carries an int `lift`, a `diagnosis` in {none, affirmation, recomposition},
  int `dismissed` / `days_old` / `fresh_days`, reads `dismissed 0` on an offer
  nobody dismissed, carries no `why.seen`, and its explained citation says
  *Ranked*, *among findings* and *outside the contest*. The obligation still
  answers whichever finding the day's order carded, now because six equals on
  one offer are the seed's to place.

### Recorded here, for whoever comes next

- **`make check-queue` reads 37 kinds, 11 warnings, 49 scenarios judged** —
  the numbers unmoved. One line under them moved: `insight` reads *3 refusing
  guards, 3 named by a scenario* where it read 4/3, and its deferred reads no
  longer name `:insight`. No scenario ever named the cap, the outcome cap's
  own verdict one kind over.
- **Fingerprints: nothing moved.** Computed over the household's 37 kinds plus
  `feed_recipe`, `recipe_proposal` and `verdict_reason` on `main` (`842395d`)
  and on this tree: **all 40 byte-identical.** `insight` is `d5b2724b…` on
  both — create guards are outside `fingerprint-of`. `feed_recipe` is
  `9e5ba71d…` on both (`:schema` is not a facet). `recipe_proposal` is
  `908661e4…` on both, because this bead did not touch it.
- **Suites:** framework 770 tests, 5856 assertions, green (private database
  `wm_1uv8f`); household 253 tests, 2883 assertions, green (`wm_1uv8`) —
  two deftests and one deftest more than the integration branch, and the
  pack's eight new claims inside the household count.
- **The insight arm reads `formula` off the cooling state when recording and
  off the recipe's window otherwise** — the sentence needs `window_days`
  whether or not anybody is recording, because freshness is the row's age and
  not a view; `document` puts `:window-days` on the ctx for the population
  for the same reason.
- **`affirmation-doors` spells two application action names in the
  framework**, as keywords, the way `reason-weights` spells four tokens and
  the populations spell `:insight` / `:tickler` / `:outcome`. A third kind
  with an affirmation door adds its pair there; a declared trait for it would
  be the moment this map has three entries.
- **The verdict record is read by the offer's three fields**, so a finding
  on the same row with a DIFFERENT action is a different question and hears
  no dismissal — *not_this_way / wrong_piece cool the offer, not the
  subject*, 1uv.7's own words, by construction.
- **Dedupe is not built** (one live finding per offer, a create guard reading
  `(:find ctx)`) — filed as **waymark-1ag**. The pack and the live test both
  publish six on one offer to prove *ranked, not capped*; with dedupe that
  becomes one admitted and five refused by name, a different claim wanting
  its own inversion.
- **`insight_rank` through `recipe_proposal`** — filed as **waymark-bck**,
  with the carry-through hazard named above.
- **`insight-scan-cap` has arithmetic behind it and no measurement** — filed
  as **waymark-czo**, beside waymark-1uv.12.
- **The fixture comment in `conformance_test`** about dropping `insights` now
  gives the surviving half of its reason (stale cards contending) and not the
  spent-allowance half.
- **`why.insight` is nested, not flattened**, for `why.crown`'s reason: the
  contest's `why.seen` stays the contest's and absent on every insight card,
  so `:feed/formula`'s walled claim over `decide` stays literally true.

## Built — 1uv.9, the fridge's rank and the dropped pile's sweep (2026-08-26, waymark-1uv.9, for waymark-iqa.13)

**The question, as filed:** waymark-iqa.13 wanted a sweep over the dropped
pile — *25 dropped of 113* was not a someday/maybe list until somebody swept it
once — and waymark-1uv.7 answered the cap question before the sweep existed:
**a cap on ticklers born per sweep would be the same mistake the outcome cap
was.** A marker writes nothing to its subject (fork (b), reason 1), it is no
letter and no notification, and a marker over a dropped task is the machine
*indexing* the pile — the owner's own word for what must not be limited.
Twenty-five markers born at once is a **rank question for the `:ticklers`
line**, and the dedupe iqa.13 wanted is a **law, not a cap**.

So three things landed together, and the order they are written in here is
the order they depend on: the rank (so a swept pile is a ranked pile the
morning it arrives), the dedupe law (so a sweep cannot double itself), and
the sweep (which now has nowhere to be a cap). The crown's rank
(§ *Built — 1uv.2*) is the model throughout, and the shape is deliberately
the same: five numbers on the recipe row, one line of arithmetic, `why` on
every card, a sentence on every answer, a diff in the household's words. The
spec calls the ticklers line **the fridge** in its sentences, because that is
where a household pins the note.

### The rank, as landed

```clojure
;; feed/default-tickler-rank
{:overdue 1      ; what each whole day past next_offer_at lifts a marker
 :not_now 4      ; what each not-now the house has already said holds it back
 :cooled 2       ; what each step the contest says it has cooled holds it back
 :front_door 5   ; what a subject this house goes to (:nav :primary) lifts it
 :age 1}         ; what each thirty days the subject has sat on the pile lifts it
```

```clojure
;; feed/tickler-lift — the whole of the arithmetic
lift = overdue    × whole days past next_offer_at
     − not_now    × times the house has said not now (offer_count)
     − cooled     × steps cooled
     + front_door × [the subject is a kind this house goes to]
     + age        × months the subject has sat on the dropped pile

;; feed/tickler-key — the sort, ascending
[a-person's-own-hand-first?  −lift  hash(seed ‖ card_id)]
```

Six inputs, each a number or a word a person reads on the card:

| input | read off | as it rides `why.tickler` |
|---|---|---|
| **a person's own hand** | the marker's **create transition's actor type** in the log — `human` is a person's own asking; `agent` and `system` are indexing | `"own": true` — the **tier** |
| **overdue-ness** | whole days from `next_offer_at` to now; a marker with no date is *due today*, never overdue | `"overdue": 5`, `"next_offer_at": "…"` only when a date exists |
| **the house's not-nows** | `offer_count`, the household's own record | `"not_now": 3` |
| **shown and passed over** | the same `feed_view` rows, window and `cooling-step` the contest reads | `"seen": 3, "cooled": 1` — only while the reader is recording |
| **the subject's kind** | its declaration's `:nav` — the one trait the framework declares about who a kind is FOR | `"front_door": true` |
| **age on the pile** | whole months since the subject row's own last write | `"age": 2` |

…and the lift they add up to, `"lift": 7`, on the **plain read**, for the
crown's reason. The sentences are the `?explain=1` half (`tickler-card-says`):
*Ranked 2nd of 3 on the fridge by recipe.tickler_rank … The house's sweep set
this aside, not a person, so anything a person set aside by hand stands above
it. It carries no date — unset means now … The house has said not now to it 3
times, holding it 12 … Its row is a task, a kind this house goes to, lifting
it 5 … Lift −5 in all; the seed decides between equals.*

On the wire, on every read, beside `recipe.crown_rank`:

```json
"recipe": {
  "guarantees": "… the crown's rank is five; and so is the fridge's, for the things set aside. …",
  "tickler_rank": {"overdue": 1, "not_now": 4, "cooled": 2, "front_door": 5, "age": 1},
  "tickler_rank_says": "The things you set aside are ranked when their date comes round, and this is the whole of it. An item a person set aside by their own hand stands above every one the house's sweep set aside for you, and no number here changes that. Among the rest, five numbers a person can read: …"
}
```

### A person's own hand is a tier, not a weight — decided and recorded

1uv.7 named the split the population hid: a person's dated tickler is an
obligation the person set, outside the contest by law 2; a sweep-born one is
indexing and contends. This bead makes the split a **tier** — `tickler-key`'s
first element — exactly as *asked for* is a tier in the crown, and for the
same reason: no number a household writes may put the machine's indexing
above a person's own asking (law 6 read at the fridge). Two refinements to
1uv.7's sentence, both recorded:

- **The hand, not the date, is what makes a marker the person's.** A person
  who sets something aside *now* (no date) has asked as surely as one who
  dated it; the date is `:overdue`'s business. So the tier reads **who
  created the marker**, and it reads it off the **log** — the create
  transition's actor type — rather than off `set_aside_by`, because that
  field holds an id and an id does not say whose kind of hand wrote it. One
  indexed read per due, standing marker; none for the rest.
- **An agent's hand is the machine's.** A marker an agent set aside at the MCP
  door contends with the sweep's rather than standing above them, which is
  what 1uv.7 said (*set_aside_by an agent … is indexing*) and what the live
  test proves. A person saying *not now* to a sweep-born marker does not
  promote it — the not-now is a dismissal, not an asking, and it is what
  `:not_now` counts.

### What is NOT a cap, and what is a law

- **The backoff is untouched.** `backoff-days`, `next-offer`,
  `push-the-offer-out` — not a byte moved. `next_offer_at` past is still the
  whole of the population's *due* filter, and now it is also `:overdue`'s
  input: a person's own date is honoured before the sweep's *now*.
- **`:take 2` stays as the floor.** The line shows as many markers as its take
  says whenever that many are due; the rank chooses which.
- **One live marker per subject is a LAW**, at the tickler's own create door:
  `workqueue10.resources.tickler/one-live-marker-per-subject`, a `defguardfn`
  reading `:tickler` through `(:find ctx)` for an `offered` marker naming the
  same `subject_kind` + `subject_id`, refusing with the standing marker's id
  in the sentence (*That row is already on the fridge — tickler … is still
  asking about it. Answer that one … rather than pinning a second note beside
  it*). LIVE means `offered`: a row the house let go or took back may be set
  aside again by a later hand, and that is a new asking, not a duplicate. It
  is a conformance-tier wall (it reads a kind), so it is a deftest and not a
  scenario; the storage-free probe answers allow, `insights-are-capped`'s own
  discipline. Create guards are outside `fingerprint-of`, so the tickler's
  hash is unmoved.

### The sweep, as landed — and where it lives

`feed/sweep-dropped!` is the pass and `feed/start-tickler-sweeper!` is the
clock, in the orphan sweeper's shape (`jobs/start-orphan-sweeper!`,
waymark-db9.4): a function a test drives by name, a loop on a latch, and an
**elected** lifecycle hook on the `:feed` module (`:hook :tickler-sweeper`,
`:elected :tickler-sweeper`, `:tickler-sweep-ms` engine opt, an hour by
default) — the first thing the feed module has ever started, and `:when`-gated
on a tickler kind being served (`feed/serves-ticklers?`, the mirror module's
discovery precedent), so an engine with no tickler starts nothing and pays
nothing. Elected for the orphan sweeper's reason: two processes sweeping one pile would knock on the
dedupe guard twice for nothing. db9.9's finding was read and does not bite
here: the orphan sweeper's obligation needed a process with no *worker* in it
because the worker races the sweep; nothing races this one, so the live proof
drives the pass directly, `batch_f_jobs_test`'s own posture.

**What it walks** (`feed/dropped-pile`): every kind whose `:over` names a
`:let-go` word — `task`'s `dropped`, `media`'s `abandoned`, a skipped chore
run — and every row resting on that word while the machine still holds it
open (`let-go?`), which is exactly the set `set-aside?` would still card. The
vocabulary is the kind's own; the framework holds no string. Rows newest
first, at most `dropped-scan-cap` (500) per (kind, word), and the pass says
when it stopped short — **a bound on the read and never on the birth.**

**What it writes:** one tickler per subject, through the tickler's own create
door, under the engine's own actor (`feed/sweep-actor`,
`waymark10-tickler-sweep`, `:system`), so `set_aside_by` says the sweep did it
and the log's actor says so too — which is what the tier reads. The marker's
`what` is the subject's own `:label-template` (its summary line when a kind
declares none), cut to the field's ceiling — denormalized at birth, as the
by-hand door asks a person to spell it. `subject_href` is the row's own
address. No date: *unset means now*, and a swept marker is on the fridge the
morning the sweep found it. **No cap on births.** The pass reads the standing
markers once before it starts, only so it does not knock on a door it knows is
shut; the door's guard is the law, and a refusal there is counted, not
retried.

**The first pass is one interval after election.** A boot mints nothing, so
no test finds markers it did not make; a household's first morning waits an
hour, which the marker's own *unset means now* then honours.

### Where the numbers live, and what moved with them

On the `feed_recipe` row as `tickler_rank` beside `crown_rank`, in
`recipe-fields`, `tickler-rank-schema`, `prose`, all three schemas,
`:revise`'s prefill and `recipe-of`. `check-recipe!` grew a **seventh** check
(five ints, 0–100, zero legal); `recipe-guarantees` says so; `recipe-view`
carries `tickler_rank` and `tickler_rank_says`; `recipe-diff` folds
`tickler-rank-diff`, which reads a **new** `tickler-rank-words` map — new
rather than entries in `crown-rank-words`, because a diff that read one map
for both would say *in the crown* about the fridge, and generic over its keys
for 1uv.5's one rule. `entry-cards` keys the rank by **population**
(`:ticklers`) rather than by section, because decide holds several lines and
only this one ranks; `cooling-says` gained its arm before the decide sentence,
which stays true of a tickler — it appears because it must; the rank only
says which of the due ones. The population now scans to `tickler-scan-cap`
(100), reports it, and `document` notes it in the crown's posture.

**One thing that did not come free, met before it could bite** — the same one
1uv.2 met: `recipe_proposal/apply-the-order` writes `feed_recipe/:revise`
wholesale, so an order-only apply would have reset a household's `tickler_rank`
to the deployment's. It now reads the target's own `tickler_rank` and carries
it through, one clause beside the crown's. That is the **one fingerprint that
moved** (below). A proposal cannot *propose* `tickler_rank` yet — the field on
`recipe_proposal`, its four staleness guards and its diff-of are 1uv.5's wiring
one field over — filed under the epic rather than smuggled in beside three
other agents' edits to the same file.

### Where the law is proved

- **`feed-test`**, two deftests: the arithmetic as a pure function — seven
  markers ordered identically under two unrelated seeds, a person's own hand
  first with a negative lift, `offer_count 3` below `offer_count 0` with
  everything else equal, all five at zero reducing to `[tier 0 hash]`,
  `tickler-as-cited`'s shape — and the field: five numbers on the wire
  narrated, a row naming some keeping the rest, a nonsense number refused at
  assembly by name, the diff's sentences beside the crown's, and a household
  POSTing its own `tickler_rank` with the crown's untouched.
- **`workqueue10.tickler-rank-test`** (new, live), over the household's own
  registry with the authority's fakes so a task can really be dropped: three
  todos the authority dropped and one it holds open; `dropped-pile` names the
  three and not the open one; one sweep births three markers under
  `waymark10-tickler-sweep` with the task's own title and address on them and
  no date; a second sweep births none and passes three over; every swept
  marker cards with `own false`, `front_door true`, lift 5, and says *sweep
  set this aside* when asked; a person's own marker over the open task, dated
  by hand and put off once (lift 1), stands **first** above every swept one
  (lift 5) — the tier; three not-nows on one swept marker and the clock walked
  to its day back: lift −5 below its twin's 7, *said not now to it 3 times,
  holding it 12*; the member turns their record on and is shown the twin three
  mornings: `seen 3, cooled 1`, lift 5, still above the put-off one; a second
  marker over the fence answers **409 `one-live-marker-per-subject`** naming
  the standing marker; after `let_it_go` a new one is admitted; an agent's
  marker reads `own false` and stands below the person's. The fixture's clock
  is an atom, nil — the real clock — for everything else, put back in a
  `finally`.
- **`:feed/ticklers`**, six claims added: `recipe.tickler_rank` is five ints;
  `tickler_rank_says` quotes `not_now`'s number, *own hand* and *not a cap*;
  a tickler card's `why.tickler` carries an int `lift`, a boolean `own`, ints
  `overdue`/`not_now`/`age` and a boolean `front_door`; the walker's own
  marker — a SYSTEM hand — reads `own false`; a marker nobody has put off
  reads `not_now 0`; the explained citation says *Ranked*.

### Fingerprints, and what did not move

Computed over `engine/full-registry` of `main/check-resources` (50 kinds) on
`main` (`842395d`) and on this tree: **49 byte-identical.** `tickler` is
unmoved — a create guard is outside `fingerprint-of`, and no verdict, handler
or schedule was touched. `feed_recipe` grew a field and `:schema` is not a
facet. **One moved:** `recipe_proposal` `908661e4…` → `721b1e7a…`, mover
`apply-the-order` (defhandler body: carries `tickler_rank` through) — a real
change to what the apply writes, the same mover 1uv.2 and 1uv.5 recorded;
`boot-revise!` mints one revision.

`make check-queue`: 37 kinds, 11 warnings, 49 scenarios judged — unmoved.
The tickler now reads *1 refusing guard, 0 named by a scenario*, which is the
dedupe wall deferring to the suite as a `:reads` guard must.

### Recorded here, for whoever comes next

- **`recipe_proposal` cannot propose `tickler_rank` yet.** The apply carries it
  through; proposing it is the field, four guards and `diff-of` one field
  over from the crown's — filed under the epic. `tickler-rank-diff` and its
  words map are ready for it, the way 1uv.2 left `crown-rank-diff` for 1uv.5.
- **The value a subject serves is not an input.** 1uv.7's fourth input was
  *the subject's kind and, when it has one, the value it serves*; no kind a
  tickler marks names a value today, so the kind half landed (`:front_door`,
  off `:nav`) and the value half is recorded, not faked: the day a mirror kind
  carries a `value_id`, `value-standing` is the read and a sixth number is
  its weight.
- **`age` reads the subject row's last write.** For a dropped mirror row that
  is when the authority let it go — or the last resync that touched it. That
  is the honest bound on the word; a resync that rewrites an unchanged row
  would reset a subject's age, and whether resync does that is the mirror's
  business to say.
- **`:overdue` and `:age` are uncapped.** A hand-dated marker a year past its
  date is a year of the house walking past a note it wrote, and it stands
  first among the machine's for exactly that reason; a household that wants
  the slope flatter writes a smaller number.
- **The sweep's first pass waits an interval.** Deliberate (a boot mints
  nothing under a test's feet); a household that wants the pile swept on the
  first morning sets `:tickler-sweep-ms` short or calls `feed/sweep-dropped!`
  from the REPL once.
- **`tickler-scan-cap` (100) and `dropped-scan-cap` (500)** have arithmetic
  behind them and no measurement — waymark-1uv.12's question, asked of two
  more numbers. With a swept pile every marker is due on day one, so the
  first number is the one to watch.
- **The screen joins, it does not derive.** *Why this order* opens into
  `recipe.tickler_rank_says` beneath the crown's; a tickler card's *Why this
  card?* opens with the lift and the inputs off its own `why.tickler` before
  any network, replaced by the server's sentence when anybody asks.

## Built — 1uv.6, the agent's score and sentence (2026-08-26, waymark-1uv.6)

**The owner's ruling, verbatim:** *an agent may tune the rank, or supply a
judgment to it.* Tuning is § *Built — 1uv.5*. This is the judgment — option
M of § *The agent's part*, landed: **an agent that read a bundle writes a
score, 0 to 1, and one sentence, as data with its name on it; the crown's
formula reads the score as one more weighted number beside its counts; the
card quotes the sentence as the agent's, the way it quotes the composer's
routing; a row nobody scored ranks without it; an agent that is wrong is one
weight turned down.** The epic's own sentence for the weight — *a nudge,
never a verdict* — is the arithmetic here, not a hope.

### The shape, decided: a kind, not a field

The bead asked whether the score lives on `outcome` or in a small kind of
its own. **The kind — `ranking_note`, `waymark10/src/waymark10/ranking_note.clj`,
enrolled `:always` by the feed module beside `verdict_reason`** — and the
recommendation is the spec's for three reasons, each of them a thing a field
could not do:

1. **It is not the outcome's fact.** A score is an agent's opinion ABOUT the
   bundle, and a field on the bundle would put the agent's word inside the
   row the composer wrote — the exact blur this card exists to refuse. On a
   row of its own it is stamped with who wrote it (`judged_by`, the engine's
   stamp off the principal, never the body's), and the crown quotes it under
   that name.
2. **It generalizes without a second build.** The subject is
   `{subject_kind, subject_id}` — the tickler's and the reason kind's
   spelling — so one kind serves every row a rank places. The insights line
   (waymark-1uv.8) and the ticklers line (waymark-1uv.9) are getting ranks of
   their own; when they want an agent's judgment the read is
   `feed/crown-judgment`'s shape with a different `subject_kind`, and the
   kind's docstring says so. Nothing is wired for them here.
3. **It moves no hash the household holds.** A field on `outcome` would have
   moved that kind's schema; `outcome` is `6f57c0d5…` on `main` and on this
   tree. A new kind adds one hash and one table, and that is the whole cost
   (below).

**Whose it is, and whose it never becomes.** A note is born the agent's and
stays the agent's. The affirmation axis a value has (jfv.10: an agent's
`observed` row a person affirms into `declared`) was read and deliberately
**not copied**: a value is the house's law and the observer was guessing at
it, so a person can make it theirs; a judgment about which bundle deserves a
Saturday is not a fact about the house a person could confirm — it is an
agent's opinion, weighed as one, and a person who agrees says so by tapping
the bundle. So the machine is `live → dismissed`, and nothing else:

- an **agent** creates one (through an ordinary grant — outcome's posture,
  not insight's open door, because a nudge on the crown is a thing the house
  says out loud once, at the grant) and **restates** its own — a self-loop
  on the live row (`:record true`, the tickler's `not_now` and the reason
  kind's `say_more` are the precedents), so the newest judgment IS the row
  and the older scores are its transitions;
- a **person dismisses** one — *not this agent's word, not on this row* —
  and the row stays on record under the agent's name; the same agent may
  judge the row again in a new note, which the person may dismiss again;
- a **person does not write one.** A person ranking a bundle first has a door
  for that already (*compose me another* is the rank's first tier), and a
  judgment with a member's name on it would ride the crown as the house's
  own word wearing an agent's weight. `a-judgment-is-an-agents` refuses
  `:human` and `:system` alike — a seed that wrote judgments would be the
  engine having opinions about its own crown.

### The walls, and which tier proves each

| wall | what it reads | proved |
|---|---|---|
| `a-judgment-is-an-agents` — a person does not write one | the principal | declared as a scenario (`a-person-does-not-write-a-judgment`); the create chain reads rows, so it defers to the suite by the chain rule and the walker attempts it as the person it names; § 18 proves it live too |
| `cites-what-it-read` — at least one address, each `/api/<collection>/<id>` naming a collection this house serves | the registry (`outcome/cites-what-it-read` to the letter, including *a listing is not an address*) | § 18, live: an empty list and a query string both refused by name |
| `not-your-own-row` — the four-eyes wall, **generic**: the subject exists, and it is not a row the agent wrote | the subject row, and the subject KIND's own `:own-surface :by` — `composed_by` for an outcome, `authored_by` for an insight — so the wall is the subject's own sentence about ownership and never a copy of it | § 18, live, and `:feed/outcomes`: the composer, handed the note door, scores its own bundle and is refused by name. No scenario can arrange it — the walker stages `:given` rows under its own name |
| `one-live-note-per-row-and-author` | this kind's rows | § 18, live: the second is refused and told to restate |
| `the-judgment-is-your-own` — restating is the author's hand | the principal | **check tier**, `nobody-restates-somebody-elses-judgment` |
| `a-person-dismisses` — an agent dismisses nothing, not even its own | the principal | **check tier**, `an-agent-does-not-dismiss-a-judgment` |
| a dismissed note is answered | the machine | **check tier**, `a-dismissed-judgment-is-not-restated` (`out-of-state`) |

An out-of-range score is the schema's own 422 (`[:decimal {:min 0 :max 1}]`),
so no scenario was declared for it — 1uv.5's reasoning for the crown's
numbers, one kind over.

**The visibility wall is the router's, and the spec says so rather than
pretend.** *An agent scores only rows it can read* is kept by the grant the
agent holds: a guard ctx carries no visibility (insight's recorded finding,
the seam waymark-iqa.18 names), so what the birth door can honestly check is
that the row EXISTS and is not the agent's own. What the grant confers is a
read-only line over the kind it scores; § 18 and the pack both prove the
agent's read answers 200 under exactly that scope before it scores.

**Restate takes the score and the sentence, not the citations — decided,
with the alternative recorded.** What the agent READ is the fact the note
was born from, checked once under the registry consult; a restatement is a
change of mind about that reading. A shape-only evidence wall on the restate
door was written and taken out: an action-door guard that judges free text
must escape closure with `:open` — which `usability/effort-honesty` re-raises
as a warning, and the battery's count is a number this epic holds at 11 — or
read rows, which drops that door's scenarios out of the no-database tier,
and the walker (a system actor) could not stage a note through a birth door
that admits only agents to attempt them over the wire. A judgment resting on
new rows is a new note after a dismissal, or the sentence says what changed.

### The arithmetic, as landed

```clojure
;; feed/default-crown-rank — one line added
{:declared 10 :cooled 2 :declined 2 :fresh 1 :early 2 :judged 1}

;; feed/crown-lift — the sixth line
lift = … + judged × (2 × score − 1)      ; feed/judged-lift, rounded, half away from zero
```

**Centred on a half, on purpose.** A score of 1 lifts the whole weight, 0
holds the whole weight, ½ is nothing, and no score at all is nothing too
(`judged-lift` of nil is 0). So an agent can say *not this one* as well as
*this one*, silence is the middle rather than the bottom, and an agent that
scored everything 1 would have moved nothing relative to anything. The
literal reading the bead offered — *the score as one more weighted number*,
`judged × score` — was weighed and refused because under it an unscored row
and a row scored 0 are the same row, which is the one distinction a person
reading the card most needs.

**Whole numbers, and ONE by default.** The lift is a long everywhere the
wire and the packs read it, so the contribution rounds (half away from
zero). At the recommended weight of 1 only a score past ¾ or under ¼ moves
anything, and it moves it one place among equals — less than a day of
freshness. That is what *a nudge, never a verdict* means in arithmetic: two
otherwise equal bundles, one scored 0.9, and the scored one is first; a
bundle serving a declared value is never outranked by an observed one on an
agent's word. A household that has learned to trust its agent's eye raises
the number through the same door as every other (1uv.5's proposal, 1uv.2's
form), and one that has not sets it to 0 without deleting a word the agent
wrote — the card still quotes the sentence, and says the number it moved is
nothing.

**The newest live note, whoever wrote it — decided and recorded.**
`feed/crown-judgment` reads ONE note per candidate: the newest live
`ranking_note` naming the row, one query, limit one, inside the bound
`crown-scan-cap` already puts on the population. Two agents scoring one
bundle is a house running two judges, and the honest reading of two opinions
is not their mean — it is that the card quotes one agent BY NAME and the
household can read whether that agent was right. A mean would have put a
number on the card no agent said. This is the decision most worth the
owner's eye, and it is one function.

### The why sentences, as they actually read

On the crown card, `why.crown` gains one key when — and only when — an agent
said a word:

```json
"crown": {"lift": 18, "asked": false, "value": "declared", "days_left": 7,
          "judged": {"score": 0.9, "by": "cairn-judge",
                     "says": "This is the outcome he has been circling for a month."}}
```

`crown-rank-words` gains the key's sentence, so 1uv.5's diff renders it the
day a proposal moves it: *In the crown, an agent's own score of a bundle — 0
to 1, with one sentence, quoted on the card as the agent's — moves it up to 3
either way instead of 1.* `crown-rank-says` mentions the agent's number only
while it is non-zero — *…and an agent that read a bundle may score it, 0 to
1, with one sentence the card quotes as the agent's — a score of 1 lifts it
1, 0 holds it 1, and a bundle nobody scored reads as a half, which is silence*
— and counts *five numbers a person can read* when it is 0, because a house
that turned it off does not want the sentence. And the card, asked why:

```
… 7 days left on its week, lifting it 7. cairn-judge scores this 0.9: “This is
the outcome he has been circling for a month.” — lifting it 1. Lift 18 in all;
the seed decides between equals. …
```

The clause is **quoted, not paraphrased** — the agent's own words inside
quotation marks, under the agent's name, then what the house's weight made
of them (*lifting it 1* / *holding it 1* / *a nudge that rounds to nothing at
a weight of 1* / *and this house weighs an agent's judgment 0, so it moves
nothing*). It is said even at weight 0, because the word is still the
agent's and the household may want to read it. The engine's `impact` line
never carries it, on the bundle or on a piece, and the pack asserts the
absence. The plain read's opener on the screen (`135-feed-screen.js`) names
the score and the agent (*cairn-judge scores it 0.9*) and leaves the sentence
to the server's clause, the way it leaves every other input's words.

### Where the numbers live, and what moved with them

- `feed_recipe/crown-rank-schema` — `:judged`, one entry, with its label and
  help; `recipe-of` reads it; the field prose's example and help say six.
- `feed/default-crown-rank` — `:judged 1`, one line; `crown-lift`'s sixth
  line and `judged-lift`; `crown-judgment` and the `:judged` input on every
  candidate in `outcomes`; `crown-as-cited` rides `judged` only when a word
  was said; `crown-card-says` says *six numbers* and gained the quoted
  clause; `crown-rank-says` gained its clause and its five/six count;
  `crown-rank-words` gained the key; `check-recipe!`'s sixth check names it;
  `recipe-guarantees` says *the crown's rank is six*; `crown-rank-as-written`
  writes it. `crown-rank-diff` needed no edit — 1uv.5's one rule for the
  diff, kept.
- `recipe_proposal` needed no edit: `apply-the-order` carries the map whole,
  and a proposal wears `crown-rank-schema`, so `{"judged": 3}` is proposable
  today and the diff says it.
- `modules.clj` — the sixth `:always` kind on the feed module, with the
  reason kind's reason.

### What the scoring agent reads, and the grant it needs

A scoring agent judges the way a composer composes and a tuner tunes: from
rows it read, cited. The bundle itself, the value it serves, the request it
answers if any, and whatever in the house's record made it *the one he has
been circling* — each an address in `evidence`.

**The grant scope line, exactly** — `ranking_note:create` plus a read-only
line over the kind it scores; it is the composer contract's block widened by
these two, and it is deliberately NOT the composer's own leash:

```json
{"kind": "ranking_note", "actions": ["create"]},
{"kind": "outcome",      "actions": []}
```

A composer given the note door scores nothing it staged — `not-your-own-row`
refuses it by name — so a house that wants its one agent to both compose and
judge may leash it to both and the four-eyes wall holds row by row; a house
that wants a second pair of eyes leashes a second agent to this scope alone.
`restate` rides the own-surface (`{:by :judged_by :actions #{:restate}}`) and
needs no line; `dismiss` is a person's and is never granted.
`workqueue10.outcome-test` § 18 mints a composer-and-judge through the real
grant door and proves both halves.

### Where the law is proved

- **`feed-test`**, the pure half: `judged-lift` at every edge — 0.9 lifts one
  at weight 1, 0.6 rounds to nothing, 0.1 holds one, nil is silence, weight 0
  is inert, 0.75 at weight 10 is 5, 0 at weight 10 is −10; the scored bundle
  standing second only to the person's own request in the seed-independent
  order; the diff's sentence; the recipe's sentence quoting *a score of 1
  lifts it 1* at the default and counting five with the number at 0.
- **`workqueue10.outcome-test` § 18**, live, the household's own registry: a
  composer-and-judge minted through the real grant door reads the bundle it
  will score; is refused on its OWN bundle by name; is refused an empty
  citation and a query-string citation; a person is refused the door; the
  note lands with the engine's stamp; a second live note is refused and told
  to restate; two otherwise equal bundles — the scored one above (lift 18 to
  17), `why.crown.judged` carrying `{score, by, says}` under the agent's name
  and absent on the unscored card, the citation quoting the sentence under
  *cairn-judge scores this 0.9* and the impact lines carrying none of it,
  `crown_rank_says` saying the number; the agent restates to 0.2 and the
  other stands above (lift 16, *holding it 1*); the member revises the crown
  to `judged 0` and both read 17 with the word still quoted and *weighs an
  agent's judgment 0* said; the agent's dismiss is concealed by its
  create-only leash and the person's lands, after which the card carries no
  `judged` key at all; and the same agent may judge the row again. Both
  bundles are declined at the end.
- **`:feed/outcomes`**, ten claims added after the rank flow and before the
  declines: the judge's read answers under its scope; the score lands; the
  author is the engine's stamp; the composer scoring its own bundle is
  refused, and by name; the scored bundle stays on the feed; `why.crown.judged`
  carries the score, the name and the sentence; asked why, the citation
  quotes the sentence under the agent's name; the sentence is in neither the
  bundle's nor any piece's `impact`; and a bundle nobody scored carries no
  `judged` key. The `crown_rank` shape claim now names six ints.
- **The check tier**: three scenarios judged with no database, one deferred
  by the chain rule and attempted over the wire as the person it names.

### Recorded here, for whoever comes next

- **`make check-queue`: 37 kinds, 11 warnings, 52 scenarios judged** — the
  warnings unmoved, three check-tier scenarios added, `ranking_note
  (enrolled) ✓ — 3 scenarios (1 deferred to the suite: reads :ranking_note,
  :storage), 6 refusing guards, 3 named by a scenario`. The battery's *37
  kinds* counts the household's own; the enrolled kind is listed beneath
  them the way `verdict_reason` is. One waiver, value's own for value's own
  reason: `restate` prefills a required sentence and is `:record true`, so
  `:waives #{:large-effort}`.
- **Suites:** framework 768 tests, 5840 assertions, green (was 766 / 5779 on `main`; six enrolled-kind pins moved — `router-test`, `phase9a-test`, `batch-b-mint-test`, `batch-b-access-test`, `modules-test` list the sixth kind, `law-scenarios-test` counts its one deferred scenario); household 253 tests, 2879 assertions, green (was 251 / 2786) — both in private databases (`wm_1uv6`,
  `wm_1uv6f`).
- **ONE fingerprint added, none moved.** Over the 50-kind census on `main`
  (`842395d`, the integration branch carrying 1uv.1, .2, .3, .5, .7 and
  .10) and this tree: all 50 byte-identical — `outcome` `6f57c0d5…`,
  `outcome_piece` `8db51a4b…`, `verdict_reason` `8213d8c5…`, `feed_recipe`
  `9e5ba71d…` (a schema field is not a facet, re-verified once more),
  `recipe_proposal` `908661e4…`, `insight` `d5b2724b…`, `tickler`
  `d2b11408…`. The 51st is `ranking_note` `07999ed2…`.
- **The migrate plan is one CREATE TABLE.** Against a database carrying
  `main`'s applied schema (`wm_1uv6m`), this tree plans exactly five steps,
  all on the new table: `CREATE TABLE IF NOT EXISTS ranking_notes (…)` with
  `f_judged_by`, `f_subject_id` and `f_subject_kind` as generated columns
  (the three `:filter #{:eq}` fields — the crown's join and the one-live-note
  wall push down as query conds), and the four standard indexes of a table
  being created. No existing table is touched.
- **The centred score and the rounding are the two numbers most worth an
  owner's second look**, and both are one function (`judged-lift`). If the
  house wants a finer nudge than *one place among equals* the honest change
  is the weight, not the rounding.
- **The newest note wins, not the mean** — `crown-judgment`, above. If a
  house ever runs two judging agents and wants both read, that is a decision
  about what the card can honestly quote, and it is filed rather than built.
- **No `withdraw` door for the author.** An agent that changed its mind
  restates; an agent that wants its word gone asks a person, who dismisses.
  Filed as a follow-up rather than added, because a note an agent could
  silently withdraw is a note the household never got to read.
- **The insights and ticklers lines do not read it yet.** The kind is built
  to score any row and says so in its docstring; wiring those reads is
  waymark-1uv.8's and waymark-1uv.9's, after their own ranks.
- **The screen walk is still waymark-1uv.13's.** `135-feed-screen.js` names
  the score and the agent in the plain opener and nothing else moved there.

## Built — 1uv.4, the diagnosis counts exposure (2026-08-26, waymark-1uv.4)

**The bead's sentence:** *a composer that stages fifty and sees five shown
learns about the RANK, not about the house.* waymark-8um.4 landed the
distinction this bead was filed for — `GET /api/-/diagnosis` reads exposure
off the view record before it reads verdicts, says *unknown* as a third
answer, and teaches one lesson per outcome; the wall
`no-burial-without-a-diagnosis` fires on shown-and-declined and
shown-and-passed-over only — and left a note here saying what remained. This
section is that remainder, decided against the record rather than re-done:
the tally over a composer's outcomes now that the cap is gone, the rank's own
reading of the never-shown pile, the N question, and the composer's duty
written down where the composer reads it.

### The tally, and the rank's own reading — as landed

The document (`waymark10.server.diagnosis`) carries one new top-level key
and every outcome carries one new key:

```json
"tally": {"outcomes": 6,
          "lessons": {"shown_and_declined": 1, "shown_and_passed_over": 1,
                      "never_shown": 3, "unknown": 1,
                      "accepted": 0, "still_offered": 0},
          "owing": 2,
          "never_shown": [{"self": "/api/outcomes/…", "lift": 17, "place": 2}, …],
          "crown": {"take": 2, "recipe": {…the for-reader stamp…},
                    "crown_rank": {"declared": 10, "cooled": 2, "declined": 2,
                                   "fresh": 1, "early": 2, "judged": 1}},
          "says": "Two diagnoses, and this document keeps them apart: …"}

"rank": {"lift": 17, "asked": false, "value": "declared", "days_left": 7,
         "seen": 0, "cooled": 0,
         "place": 2, "of": 6,
         "says": "As staged the rank read it at lift 17: it serves a value this
                  house declared, lifting it 10; 7 days left on its week as
                  staged, lifting it 7. That reading places it 2 of 6 among the
                  outcomes here, and the crown shows 2 a morning by these
                  numbers, house-wide; which bundles it stood under on each
                  morning it lost is not in the record. This is the rank's
                  verdict, not the house's."}
```

**`rank` is `why.crown`'s shape, read through the crown's own function.**
The one framework growth is `feed/crown-inputs` — the `:crown` map every crown
candidate already carried, extracted from `feed/outcomes` and made public — so
the diagnosis reads a never-shown bundle's inputs (asked-for, the value's
standing, the strongest word on its chain, days left, how early against the
day the house named, the agent's score) through exactly the reader the crown
sorts by, and weighs them with `feed/crown-lift` under the household's own
`crown_rank`. The number a composer reads is the number the crown used and
never a second opinion about it. `feed/outcomes` now calls the same function,
so the extraction moved nothing on the feed.

**As staged, decided.** The calendar inputs are read at the row's own
`created_at`: `days_left` is the whole week, `early` is how early it stood
the morning it entered the crown, `seen` is 0 because nobody had been shown
it yet. A lapsed row read *now* would carry `days_left 0` on every entry and
say nothing about why one lost to another; read as staged, the lift is the
one fixed number a composer can compare across its pile. The rows (the value,
the chain's words, the agent's note) read as they stand now, because that is
all the record holds; a value the house has retired since reads `gone`,
which lifts nothing, so *observed* and *retired since* are two answers.

**On every outcome, not only the never-shown ones — decided.** A lift means
nothing alone. The never-shown pile is read against the lifts of the shown
ones (in the deftest all six read 17: the rank did not choose between them,
the seed did — which is itself the lesson), and `place` is where the
crown's own key (`feed/crown-key`, tier then lift then hash) puts each among
the outcomes this document folds. That is the composer's diagnosis of its
own staging, and it is the only place the record can honestly give it — the
morning's house-wide crown, with everybody's bundles in it, is not stored,
and `rank.says` says so in so many words rather than imply a standing it
cannot know.

**Which diagnosis is whose, in one sentence** — `tally.says`, quoted on every
read: *shown_and_declined and shown_and_passed_over are the HOUSE's — people
saw these and answered them, or let them lapse, and each owes an insight
before its line comes back — while never_shown is the RANK's — the crown had
these and showed its take by the numbers under crown, and what this pile
teaches is where your bundles stood, not what the house thinks of them:
stage differently, propose new numbers through recipe_proposal, or wait for
the floor, and write no insight about the house from it; unknown is neither,
and accepted and still_offered owe nothing.*

**The recipe is the household's.** The route resolves
`feed-recipe/for-reader` with no member — the household's row, or the
built-in — because the composer has no feed and the numbers its bundles lost
under are the house's. `tally.crown.recipe` is the same stamp the feed
carries, so the composer can read whose numbers it was judged by; a member
reading under a recipe of their own is one screen among the measured.

**Bounded as the document was.** The tally counts the outcomes the document
folds — the newest twenty, or the one `?outcome=` names — and the last note
says so when the cap is reached. Each outcome now costs one value read, a
chain walk of at most five hops and one note query beside the exposure and
reason scans 8um.4 recorded.

### The N question — decided: no, and not on the recipe

8um.4 hard-coded the threshold that turns *expired* into *shown and passed
over* at ONE recorded morning and asked whether a declared N belongs on the
recipe row beside the other numbers once single mornings become common.
**No**, and the reason is the row's own: the six numbers on `crown_rank` say
how the crown CHOOSES; a threshold says when a person's screen counts as
having shown them something, which is a fact about the view record's grain
and not a weight the household tunes. Putting it on the recipe would make a
guard read the formula — the thing 8um.4 refused for 8um.3's reason (*the
moment a guard reads the formula it becomes law with a revision behind it*)
— and would give a household a form field whose only effect is to let
bundles lapse unremarked. The wall's docstring and `diagnosis/lesson`'s say
so where the number is. The question reopens only if a real house wants a
glance not to count, and then it is a question about `feed_view`'s grain
(waymark-dtv's per-day decision), filed then.

### The composer over MCP — not built, and the contract says what it does instead

waymark-8um.5 (a seventh MCP tool for documents, or `waymark_get` learning
the three-segment shape) stays open and was deliberately not built here.
§ *The composer contract* now says what the composer does today: it reads
the diagnosis **over HTTP with its bearer**, as it reads the welcome
document; the two lines it needs are read-only, `{feed_view []}` and
`{verdict_reason []}`; `diagnosis_id` is an `insight` its own `insight.create`
writes. And the duty under the epic is written there in four lines: read
the tally before every sitting; recompose only shown-and-declined and
shown-and-passed-over lines, with a diagnosis; treat never-shown as the
rank's verdict, not the house's; unknown is unknown. That closes
waymark-8um.7 as well, which asked for exactly that text.

### Where the law is proved

- **`workqueue10.outcome-test` § 20**, live, one deftest
  (`the-tally-counts-exposure-and-never-shown-names-its-lift`): one composer,
  six bundles — one lapsed before anybody was recording; then, under a
  recording member, five staged at one pinned instant, two of them viewed,
  one of those declined, and the clock moved a week on so the engine's own
  wall answers `expire`. The tally reads `{shown_and_declined 1,
  shown_and_passed_over 1, never_shown 3, unknown 1, accepted 0,
  still_offered 0}`, `owing 2`, the crown's six numbers and `take 2` with
  the recipe stamp; the never-shown pile names three lifts of 17; each
  never-shown outcome carries `rank` with `value declared`, `days_left 7`,
  `seen 0`, no `declined`, no `early`, `of 6`, and a sentence saying *lift
  17*, *declared, lifting it 10*, *7 days left* and *the rank's verdict, not
  the house's*; the shown ones read the same 17; the six places are 1–6, one
  each; and the unrecorded lapse reads `unknown`, out of the never-shown
  pile, with a lift all the same.
- **`:feed/diagnosis`**, four claims added after the fresh read: the staged
  bundle carries an integer `rank.lift` with `value declared`, an integer
  `days_left`, a positive `place` and a sentence; the tally is on the
  document with all six lessons as integers summing to the outcomes folded,
  `still_offered` positive, a vector under `never_shown`, a map under
  `crown.crown_rank` and *RANK* in its sentence; after the decline the tally
  counts one `shown_and_declined`. A never-shown bundle cannot be arranged
  over the wire — it takes an expired week, and the clock is the engine's —
  and the pack's docstring says the deftest is where that is proved.
- **One claim in the same pack was wrong on `main` and is corrected here.**
  8um.4's pack still asserted that a diagnosed recomposition staged the
  morning after the decline is refused by `a-recomposition-waits-its-turn`
  (*the floor still stands behind the diagnosis*) — true when it was
  written, and false since 1uv.10 moved the date from the door to the
  rank's `:early` input; the integration branch's household suite was red on
  it (`answered 201`). The claim now asserts the admission, that the
  recomposition carries its chain's `declined_count 1`, and the obligation
  declines the admitted row on its way out so the engine it hands on is the
  engine it found. The deftest `the-duty-fires-before-the-date` had already
  said the same thing from its side.

### Recorded here, for whoever comes next

- **`make check-queue`: 37 kinds, 11 warnings, 52 scenarios judged** —
  unmoved; nothing declared changed.
- **Suites:** household 262 tests, 3188 assertions, green (the integration
  branch read one failure, the pack claim above; 261 tests before this
  bead's deftest); framework 772 tests, 5944 assertions, green — both in
  private databases (`wm_1uv4`, `wm_1uv4f`). The two household focuses
  (`outcome-test`, `conformance-test`) must run one at a time per database:
  run together in one process, the pack's recording member is a measured
  screen for the deftest's composer and *unknown* reads *never shown*.
- **No fingerprint moved, computed over the 51-kind full registry**
  (`engine/full-registry` over `workqueue10.main/check-resources`) on
  `main` (`dfdcbee`) and on this tree: all 51 byte-identical — `outcome`
  `6f57c0d5…`, `feed_recipe` `9e5ba71d…`, `insight` `d5b2724b…`. A document
  route, a public function and a document key are outside every facet. No
  table, no DDL, an empty migrate plan.
- **Files:** `waymark10/src/waymark10/server/diagnosis.clj` (the tally, the
  rank reading, the recipe opt, the N decision in `lesson`'s docstring);
  `waymark10/src/waymark10/server/feed.clj` (`crown-inputs` extracted and
  public; `outcomes` reads through it); `waymark10/src/waymark10/server/routes/feed.clj`
  (the diagnosis door resolves the household's recipe);
  `waymark10/src/waymark10/test/packs.clj` (the claims above, the corrected
  floor claim, the tidy); `workqueue10/test/workqueue10/outcome_test.clj`
  (§ 20); this file and `docs/spec-feed.md` § *Built — 8um.4* (pointers).
- **The place is among the composer's own, never the morning's crown.**
  Recording the crown's house-wide order per morning would need the read to
  write — the law that did not move — or a sweeper composing feeds for
  everybody; filed as a note under the epic rather than built.
- **The tally is over the folded twenty.** A composer with more staged reads
  the newest twenty tallied and asks about older rows by name. A count-only
  pass over every row would still cost the exposure scans per row, since the
  lesson needs them; if a real composer's pile outgrows the fold, the honest
  change is a `?since=` or a wider cap said out loud, not a cheaper lesson.
- **waymark-8um.5 stays open**; the contract says the composer reads over
  HTTP until it lands. **waymark-8um.7 is closed by this section.**

## Built — 9j2, the iterate loop (2026-08-27, waymark-9j2)

**The owner's ruling (bd memory `iterate-on-outcomes-is-the-core-loop`):** the
feed's only outcome verbs are `make_it_so` / `not_this_week` / `expire` —
accept, defer, kill. *There is no way to say 'the outcome is right, the
IMPLEMENTATION is wrong, iterate on it with me', which is the project's core
value proposition (tune the STEPS of desirable outcomes over time).* Observed
live: the owner read the Sunday breakfast-and-park-walk bundle and said the
walk clashes with 9am church and it is too hot later — a critique of the
**plan**, not the goal — and it had nowhere to land. `supersedes` wants a
declined prior; an offered outcome shows the composer `actions:[]`; the crown
rank reads no remark or insight, so the card stayed #1 unchanged. This section
builds the loop: a person gesture that keeps the outcome and asks for a
re-plan, a composer power to revise a **standing** outcome's pieces in place,
and the thread (waymark-b4s) as the workshop the two turn in.

### The forks, decided

- **A self-loop, not a new state.** `iterate` and `rework` are both
  `offered → offered`. A `:workshopping` state was the alternative and it was
  declined: the crown reads `state=offered` by default, so a new state would
  drop the outcome off the feed the instant a person asked to keep it — the
  exact opposite of the gesture — or it would have to duplicate everything
  `offered` already means about carding, for no gain. The outcome is not
  *answered*, it is still asking with a different plan; `offered` is the honest
  state, and the invitation rides two engine stamps beside it, the way the
  decline's `not_before` / `declined_count` do (waymark-1uv.10).

  **OVERTURNED by the owner, 2026-08-28 (waymark-9xn).** He read the fork's
  own reasoning back as the bug: *when I iterate on an outcome, it doesn't
  remove it from my feed. I think it should. It should probably go into a
  state of 'Needs Iterating' and then I shouldn't see it until it's been
  iterated on* — and he named it as a reason he had stopped answering the
  fridge at all. The argument above assumed dropping off the feed was the
  opposite of the gesture; it is the gesture. A person who has said *the plan
  is wrong* has ANSWERED that card, and re-asking them the next morning is the
  system ignoring them. `iterating` is a real state now, non-terminal, and
  `rework` is the door back. See § *Built — 9xn*.

- **The invitation is two stamps, read as a comparison, not a boolean.**
  `iterate` stamps `iterate_requested_at`; `rework` (on the outcome) stamps
  `reworked_at` and bumps `plan_revision`. An outcome is *inviting a rework*
  while `iterate_requested_at` stands later than `reworked_at` (or the latter
  is absent) — `iterate-open?`. A fresh iterate after a round reopens the door,
  so the loop converges in named rounds the card can count. None of the three
  carries a `:filter`: the composer reads them off its own outcomes
  (own-surface), the guards read them off the row in hand, and *an open
  request* is a two-field comparison a generated column cannot express anyway —
  so all three land in `data`, the storage facet does not move and the migrate
  plan stays empty (`request_id`'s reasoning, once more).

  **Since waymark-9xn (2026-08-28) the invitation is the STATE, and these are
  the record.** `iterate-open?` and `the-outcome-invited-this-rework` are gone:
  a bundle invites a rework when it is `iterating`, which is one fact instead
  of two stamps that had to be kept in step, and the wall is the machine's own
  `:from`. The three stamps stay, written by the same handlers, because the
  card counts the rounds off `plan_revision` and says *Reworked from your note*
  off `reworked_at`.

- **Revise in place = withdraw + re-stage, gated to an open invitation.** The
  genuinely new power the composer lacked was **retracting its own unanswered
  piece** — `not_this` / `moot` are the household's verdicts, walled off from
  the stager by four-eyes, so a composer could add pieces but never take a bad
  one back. `outcome_piece.rework` (`offered → reworked`) is that withdrawal.
  Re-time and replace are withdraw-then-stage; add is the ordinary create door;
  remove is withdraw alone. The `reworked` state is a fifth piece state and
  deliberately **neither decline**: not `declined` (which teaches *never this
  piece*) and not `moot` (which teaches *beside the point*) — it is the
  composer un-proposing its own offer, and `make_it_so` takes only what is
  still `offered`, so a reworked piece lands nothing.

- **The rework walls are the INVERSE of four-eyes, and that is why an agent may
  walk them.** A rework decides nothing the household consented to and
  materializes nothing — it withdraws a proposal so a better one can stand — so
  the law that keeps it honest is **authorship, not personhood**.
  `only-its-composer-reworks` is `g/is-the-field :composed_by`, the exact
  mirror of `the-composer-does-not-decide`: only the composer that staged a row
  may rework it, and a person or a second agent is refused. **(Ruling,
  2026-08-28, waymark-9xn: still authorship, but GRANTABLE — `g/author-or-
  granted`, which is `unless-granted` with the own-field exemption inverted.
  The composer walks it unasked; anybody else walks it under a grant that names
  the row. Refusing outright left a bundle whose composer is gone with a plan
  nobody in the house could ever revise, which is the wall waymark-sfe's ruling
  is against.)** `a-person-answers`
  is deliberately **not** on either rework door — that wall is what keeps
  materialization a member's tap, and a rework materializes nothing. The person
  still taps the revised pieces; a rework changes what is on offer, never what
  the house has said.

- **`iterate` is the household's, and the note is a thread turn.**
  `a-person-answers` is its one wall (which is also four-eyes for free, the
  composer being an agent): an agent that thinks the plan is wrong reworks it
  when asked or speaks on the thread; it does not tap the house's own *iterate*
  for it. The gesture carries a `:says` note, and `ask-to-iterate` files it as
  a `remark` on the outcome through `ctx :create` — the member's own hand, so
  `said_by` is the person and the critique is a turn in the thread the composer
  already answers (waymark-b4s), not a fourth verdict word. `rework` on the
  outcome replies the same way, so the thread's last word is the composer's and
  the sitting reads the work order as answered.

### The doors, as they landed

| door | kind | move | walls | what it does |
|---|---|---|---|---|
| `iterate` | `outcome` | `offered → offered` | `a-person-answers` | stamps `iterate_requested_at`; files the note as a remark on the thread |
| `rework` | `outcome` | `offered → offered` | `only-its-composer-reworks`, `the-outcome-invited-this-rework` | commits a round: stamps `reworked_at`, bumps `plan_revision`, replies on the thread |
| `rework` | `outcome_piece` | `offered → reworked` | `only-its-composer-reworks`, `the-parent-invited-a-rework` | withdraws the composer's own piece so a replacement can stand |

Three new engine-written fields on `outcome` (`iterate_requested_at`,
`reworked_at`, `plan_revision`), out of the create model like the other stamps;
one new `outcome_piece` state (`reworked`); a `thread` link on the outcome to
`GET /api/remarks?subject_kind=outcome&subject_id={id}`. `own-surface`
`:actions` gains `:rework` on both kinds — the one door the stager may walk, so
the composer sees it on its own rows, hidden by its own guards until an
invitation is open.

### One existing wall moved, and it moves no hash

`a-bundle-is-small` now counts only pieces in state `offered`, not every
sibling. The ceiling is a claim about the world — how many things one
afternoon can hold — so a piece the household declined, the week mooted, or the
composer reworked away is not one of them and must not spend the slot the
replacement needs. Before the loop a piece under an offered outcome was always
offered, so the filter changes no answer that stood then; it is what lets
replace-in-place stay under five across rounds. It reads a `state` the guard's
`:find` already admits, so no facet moves.

### How the revision shows

`feed/outcome-says` gains one **engine-owned** clause: when `reworked_at`
stands, the card says *Reworked from your note — plan v{n}*. It honours the
same wall the routing clause beside it respects — the composer's *reason* for
the rework lives in its thread reply, and this line only states the **fact**
that the plan was reworked and how many rounds, a sentence the engine stamps
and the composer cannot reach a word of. The composer's manifest
(`scripts/sitting-run.sh`) flags each `standing_outcome` with `iterate_open`
and its `plan_revision`; the note itself also rides `unanswered_threads`, so
the sitting reads the work order two ways.

### The composer's grant, one write line wider on each kind

The composer already holds `outcome` / `outcome_piece` `create`. To answer an
iterate it needs the rework doors added:
`{"kind": "outcome", "actions": ["create", "rework"]}` and
`{"kind": "outcome_piece", "actions": ["create", "rework"]}`. `iterate` needs
no grant — it is a household member's door, not an agent's. Filing the note is
`ctx :create` on `remark` inside the person's own transaction, so the composer
needs nothing new to *read* the thread beyond the `remark` read it already
holds (waymark-b4s).

### The church example, end to end

1. The composer staged *A slow Sunday: breakfast in, then time outside
   together* with pieces `breakfast` and `walk`.
2. The owner taps **`outcomes/{o}/-/iterate`** `{says: "…clashes with 9am
   church, and it is too hot for a walk later."}`. The outcome stays `offered`;
   the note is turn one on its thread.
3. The composer taps **`outcome_pieces/{walk}/-/rework`** — the walk is
   `reworked` — then stages a replacement piece (*the shaded creek trail at
   8am*) through the ordinary create door.
4. The composer taps **`outcomes/{o}/-/rework`** `{says: "swapped the walk for
   the creek trail…"}` — `plan_revision` is 1, `reworked_at` stands, the reply
   is turn two, the invitation closes.
5. The owner taps **`outcomes/{o}/-/make_it_so`** — `breakfast` and the creek
   trail materialize under the owner's name; the withdrawn `walk` lands
   nothing. The outcome never left the fridge.

### Where the law is proved

- **Check tier (no DB):** `an-agent-does-not-iterate-an-outcome`
  (`a-person-answers`), `only-the-composer-that-staged-an-outcome-reworks-it`
  (`only-its-composer-reworks`, the one guard object both rework doors share).
- **The suite (`workqueue10.outcome-test` § 21):** the whole church flow over
  the real ring handler — the note on the thread, the piece withdrawal, the
  ownership and invitation refusals, the version bump and the reply, the
  engine's *Reworked from your note* clause, the closed invitation, and the
  partial accept that takes only what is on offer. The piece `rework`'s
  ownership wall is proved here rather than by a scenario for the structural
  reason `names-a-person` records: its guard set reads the parent `:outcome`,
  so a conformance-tier scenario would stage the subject as the walker and
  stamp `composed_by` to the very actor it means to refuse — the outcome-level
  twin proves the shared guard object at check tier instead.

### Left conservative, for the owner to decide

- **The crown rank is untouched.** An open iterate request does not re-order
  the person's feed — ranking is the crown's declared formula and the owner's
  ruling is that agents change weights only through `recipe_proposal`. The
  composer's *read* surfaces the work order (manifest, thread, the card's
  reworked line); whether *asking to iterate* should also lift a card in the
  crown is a rank change filed for the owner, not made here.
- **Piece-level `iterate` was not built.** The gesture is outcome-level: the
  person says *the plan is wrong* and the composer decides which pieces move.
  A per-piece *this specific piece is wrong, keep the rest and rework just it*
  is expressible today as `not_this` on that piece plus a thread note, and a
  dedicated piece-level iterate is a follow-up if the coarser gesture proves
  too blunt.
- **`iterate` does not touch the leash.** The outcome's `good_until` is
  unchanged by a re-plan — an outcome reworked on day six still expires on day
  seven. Whether a live workshop should extend the week is a real question and
  deliberately not answered here; the honest conservative default is that the
  week is the week.

- **Files:** `workqueue10/src/workqueue10/resources/outcome.clj` (the two
  rework guards, `iterate-open?`, `only-its-composer-reworks`, the three
  handlers, the `iterate` / `rework` doors on both kinds, the `reworked` state,
  the three stamps, the thread link, the scenarios, and the `a-bundle-is-small`
  refinement); `waymark10/src/waymark10/server/feed.clj` (`outcome-says`'
  reworked clause); `scripts/sitting-run.sh` (`standing_outcomes` carries
  `iterate_open`); `workqueue10/test/workqueue10/outcome_test.clj` (§ 21); this
  file.

## Built — 8gc, not a twin (2026-08-28, waymark-8gc)

**A law with no door behind it.** SITTING.md has told the composer since it
had a manifest: *never a twin of a `standing_outcome` — a candidate whose goal
says the same thing, or cites the same evidence row, as one already offered or
accepted is a twin, and the rank cannot tell twins apart.* The manifest even
prints the standing bundles under the heading *Already standing — NEVER twin
one of these*. Nothing enforced it. `outcome`'s create guards judged the
candidate's own shape and its own prior and never once looked at what
**another** outcome cites, so a sitting on 2026-08-27 staged duplicates and
every one was admitted. This section is that sentence made structural:
`not-a-twin`, a `(:find ctx)` guard on the create door.

**It is not the cap coming back.** § *Ranked, not capped* law 1 says the
machine may write without limit, and this wall does not touch it: a cap
refuses the Nth row **because it is the Nth**, and this refuses a row because
the house is already holding one built on the same reading, whoever composed
it. A duplicate is not indexing — it is one index written twice, and a rank
cannot choose between two cards that say the same thing. waymark-1ag's own
compression: *dedupe is a law, not a cap.*

### The rule, exactly

For a candidate with **no `request_id`**, its evidence set is compared against
the evidence of every `offered` and `accepted` outcome, **any composer**; one
shared row address refuses the staging, and the sentence names the standing
outcome's address, the state it stands in, and the shared address.

### The forks, decided

- **Exact address overlap, and nothing cleverer.** No goal-text similarity.
  A door that judged whether two goals *say the same thing* would be a door
  guessing, and the refusal sentence could not name the offending row — which
  is the one thing every wall on this kind does. Two bundles over disjoint
  evidence with near-identical prose are admitted, and that is the honest
  boundary of what an address comparison can claim.

- **A row's own value address is subtracted from both sides.** `value_id` is a
  declared field, so an outcome that also lists its own value in `evidence` has
  read nothing the field did not already say. Counting that as a shared row
  would have made the wall *one standing outcome per value* — the cap
  waymark-1uv.3 removed, restored by accident under a new name — and it would
  have refused the conformance pack's own third staging, which exists to prove
  that no door counts a composer's week. **Another** outcome's value, cited as
  a reading, is an ordinary shared row and still twins.

- **The person's pull is the one exemption.** A candidate carrying a
  `request_id` passes whatever it cites; `the-request-is-open` stands right
  behind this wall and checks the citation itself, so the exemption cannot be
  bought with an invented request. The reason is the epic's own: a pull is
  consent given in advance, and a person holding the standing bundle who asks
  anyway is not being twinned — he is asking.

- **`supersedes` needed no exemption written for it, and got a small one
  anyway.** A recomposition of a **declined** prior legitimately re-cites the
  prior's evidence, and it passes because a declined outcome is not standing —
  it falls out of the state list rather than out of a special case (proved,
  not assumed: § 22's last deftest). The row named in `supersedes` is
  additionally left out of the comparison, which matters only for an
  **accepted** prior: refusing a recomposition as a twin of the very row it
  names would be this wall answering a question `a-recomposition-waits-its-turn`
  and `no-burial-without-a-diagnosis` already own.

- **A bounded read, and the direction it fails in.** The wall reads 200 rows
  per standing state. A household's fridge holds tens; if a pathological store
  ever ran past the window the wall lets a twin **through** rather than
  refusing a bundle over a row it could not see, which is the right way for a
  wall reading a window to be wrong.

- **The insight arm is not built here.** waymark-1ag holds it — identical
  evidence set plus identical `offer_kind` / `offer_id` / `offer_action` — and
  it is left alone on purpose: the conformance pack publishes six findings on
  one offer to prove *ranked, not capped*, and folding five of those into
  refusals is a different claim wanting its own inversion. Until then
  `sitting-run.sh verify` is the only check on that side, and it wears 1ag's
  shape rather than bare evidence overlap.

### Where the law is proved

- **`workqueue10.outcome-test` § 22**, four deftests over the real ring
  handler: a second bundle over a standing row refused by name, with the
  standing address and the shared address in the sentence and from a *second*
  composer; distinct evidence admitted; an accepted bundle twinning just as an
  offered one does, and its own recomposition admitted; the person's pull
  admitted over the same overlap; a recomposition of a declined prior
  re-citing exactly what the prior cited; and two bundles serving one value
  both admitted, which is the value-subtraction fork asserted rather than
  described.
- **No scenario names it**, for the structural reason `names-a-person`,
  `the-request-is-open` and `no-burial-without-a-diagnosis` have none: the
  wall's whole question is what another row cites, and a scenario holds one
  literal input over an empty store, so every scenario reaching this door
  would be an allow. `check-queue` reads the outcome row as 15 refusing
  guards, 6 named by a scenario.
- **`scripts/sitting-run.sh verify`** prints a fault line —
  `TWIN: <self> shares <evidence> with <other>` — for any outcome or insight
  written this run whose evidence overlaps another standing row of the same
  kind (insights additionally needing the same offer triple). It hydrates each
  row at its own address, because evidence lives only there, and it makes the
  same value subtraction the door makes so the report and the wall never
  disagree.

- **Files:** `workqueue10/src/workqueue10/resources/outcome.clj` (`not-a-twin`,
  `standing-states`, `own-value-address`, `read-rows`, the create-guard list,
  the ns docstring's own section and the scenario-absence note);
  `scripts/sitting-run.sh` (`verify`'s twin fault lines);
  `workqueue10/test/workqueue10/outcome_test.clj` (§ 22); this file.

## Built — 1ag, one live finding per offer (2026-08-28, waymark-1ag)

**The insight arm of the same law.** § *Built — 8gc* closed with the insight
arm explicitly unbuilt: *identical evidence set plus identical `offer_kind` /
`offer_id` / `offer_action`*, left alone because the conformance pack published
six findings on one offer to prove *ranked, not capped* and folding five of
them into refusals is a different claim. Both halves are here now:
`one-live-finding-per-offer`, a `(:find ctx)` guard on `insight`'s create door,
and the pack's six findings moved onto six distinct offers so each claim has
its own fixture.

**It is not the cap coming back either.** `insights-are-capped` refused the
Nth finding **because it was the Nth**, and it left with waymark-1uv.8. This
refuses a finding because the house is already asking exactly that question,
off exactly that reading, and nobody has answered it. waymark-1ag's own
compression, which § *Built — 8gc* borrowed: *dedupe is a law, not a cap.*

### The rule, exactly

A candidate is refused when a **`published`** insight carries the same
`{offer_kind, offer_id, offer_action}` **and** shares at least one address with
the candidate's `evidence`. The sentence names that finding's address, the
action and row it offers, and the shared evidence row. `taken` and `dismissed`
are terminal, so neither blocks: answering a question reopens it.

### The forks, decided

- **The evidence half was not in the bead, and building the wall is what
  found it.** waymark-1ag's own sentence keyed on the offer triple alone. That
  key **deadlocks the diagnosis duty**: `no-burial-without-a-diagnosis` makes a
  composer publish an insight citing the declined prior and offering the
  *value's* `still_stands` before it may recompose, so two bundles on one value
  declined a fortnight apart owe two diagnoses with the identical triple — and
  the composer cannot clear the first itself, because the four-eyes wall means
  the finder does not decide. A wall keyed on the triple alone would leave a
  composer unable to discharge a duty the house imposes on it, which is not a
  law, it is a trap. It is also the shape the house had already written down
  for this arm before the door existed: waymark-8gc's bead text, and
  `sitting-run.sh verify`'s insight fault line, which needed **no edit** —
  report and door now agree by construction rather than by care.

- **Exact address overlap, and nothing cleverer.** `not-a-twin`'s fork,
  repeated for the same reason: a door that judged whether two findings *say*
  the same thing would be a door guessing, and the refusal could not name the
  offending row. A compiler that genuinely read somewhere else is asking about
  something else, and that is the honest boundary of what an address
  comparison can claim.

- **The refusal carries the live finding's address, in `:vars`.** The bead
  asked whether it should; it does. A wall that cannot say WHICH row already
  asks the question leaves the author guessing, and the address is what makes
  *read that one, and dismiss it if it is wrong* an instruction rather than
  advice.

- **The rank's `:dismissed` input stays exactly as it was.**
  `feed/insight-record` counts DISMISSED priors on the same offer and holds a
  repeat DOWN; this wall counts only the live one. The two never overlap, and
  the division is the point: what the house answered is the rank's business,
  the question still open is the door's.

- **The fingerprint does not move.** Create guards are outside
  `fingerprint-of`. `check-queue` reads the insight row as **4 refusing
  guards**, 3 named by a scenario — the count the bead predicted.

### What the pack's change costs, and does not

`:feed/insights` published **six findings on one offer**; it now publishes six
on six **distinct** offers, walked off the feed's own above-seam cards crossed
with the light verbs each advertises (a page with fewer than six of those asks
the question at the size it can). The claim is untouched: *ranked, not capped*
asks whether the door counts a **writer's rows**, and six well-formed findings
from one author in one day, every one admitted, is that question either way.
The dedupe claim then gets the fixture it needs — a seventh finding repeating
the first fill's offer, refused by name, with the live finding's address in the
sentence.

### Where the law is proved

- **`workqueue10.insight-rank-test`**, a new deftest over the real ring
  handler: a second live finding on one offer refused by name **from a second
  author**, with the live finding's address, the offered row's address and
  *one question at a time* in the sentence, and the first still `published`;
  a different `offer_action` on the same row admitted; the same action read off
  a different row admitted (the diagnosis case); a DISMISSED prior admitting a
  fresh finding, which the rank then holds down by counting it.
- **`:feed/insights` in the conformance pack**, from the wire: the duplicate
  refused by `one-live-finding-per-offer`, and its sentence naming the live
  finding.
- **No scenario names it**, for the structural reason `not-a-twin` has none:
  the wall's whole question is what another row already offers, and a scenario
  holds one literal input over an empty store, so every scenario reaching this
  door would be an allow.

### One thing found on the way

`workqueue10.insight-rank-test`'s first deftest was **one closing paren
short**, so `the-offers-address-is-derived-and-still-checked` (waymark-42m's
live proof) was read as a form inside its body: the var was def'd when the
outer test ran — after kaocha had finished collecting — and its assertions
never executed once. The suite reported `1 tests` and looked green. Fixed
here; the namespace docstring says what to check if a deftest ever stops
appearing under `--reporter kaocha.report/documentation`.

- **Files:** `workqueue10/src/workqueue10/resources/insight.clj`
  (`one-live-finding-per-offer`, `live-state`, `live-page`, `offer-key`,
  `read-rows`, the create-guard list, the ns docstring's bullet and the
  scenario-absence note); `waymark10/src/waymark10/test/packs.clj`
  (`:feed/insights`'s six distinct offers and its duplicate claim);
  `workqueue10/test/workqueue10/insight_rank_test.clj`;
  `scripts/sitting-run.sh` and `SITTING.md` (the twin sentence, now naming
  both doors); this file.

## Built — g4e, one `listed` (2026-08-28, waymark-g4e)

Three namespaces — `outcome`, `insight`, `ranking_note` — each carried a
private `listed`, identical to the character, rendering a refusal's offenders
as a sorted, de-duplicated, quoted list. `clojure.core/distinct` destructures
`[f :as xs]`, which calls `nth`, which a `PersistentHashSet` does not support,
so a caller that built its offenders with `(into #{} …)` **threw where it meant
to refuse** — the router turned the throw into a 500 and the refusal sentence
was lost. waymark-8gc did exactly that on 2026-08-28 and every `not-a-twin`
refusal answered 500 until PR #34 fixed the call site.

The trap is gone rather than patched: one `waymark10.guards/listed`, beside
`render-reason` where the other refusal-sentence machinery lives, seq'ing
before `distinct` — a no-op for every vector caller, and what makes a set an
ordinary argument. Nil and empty render as the empty string, because a guard's
sentence must never be the thing that fails. Proved by
`waymark10.batch-h-defguard-test/listed-renders-every-offender-from-any-collection`
over a vector with duplicates, a set (small and large enough to leave the
array-set path), nil and empty.

- **Files:** `waymark10/src/waymark10/guards.clj` (`listed`);
  `workqueue10/src/workqueue10/resources/outcome.clj`,
  `workqueue10/src/workqueue10/resources/insight.clj`,
  `waymark10/src/waymark10/ranking_note.clj` (the three copies removed, the
  callers reading `g/listed`);
  `waymark10/test/waymark10/batch_h_defguard_test.clj`; this file.
## Built — euj, composed from what stands (2026-08-28, waymark-euj)

**The specimen.** A Gemini sitting at 03:22Z on 2026-08-28 staged outcome
`0bf19726`, *"Sacrament talk drafted and ready for August 23"* — five days
into the past — citing **one** row: `/api/tasks/51b49195`, a mirrored task
whose `status` said `done` and whose due date was August 20th. Under it hung a
piece that **prioritized that finished task**. Its journal said, verbatim: *to
satisfy the floor requirement, I staged an outcome.* There is no floor
(waymark-1uv.3 removed the cap and § *Ranked, not capped* removed the reason).

Every door passed it, and each for its own correct reason:
`cites-what-it-read` — the address is real and the collection is served;
`names-a-value` — the value is held; `not-a-twin` — no standing bundle cites
that task, **precisely because it is finished**. The composer had read the
house honestly and composed from the part of it that was already over.

Two walls land here. One asks whether anything the bundle read is still open;
the other asks whether the button a piece is staged behind is there.

### `composes-from-what-stands` — at least one row still standing

A candidate's evidence, less its own value address, must contain **one** row
the wall can read as still open. Refused only when **every** readable citation
is closed, and the sentence names each one with the word it is closed with:

> Nothing this bundle cites is still standing: `/api/tasks/51b49195` is done;
> `/api/events/…` ended 2026-08-26T14:00:00Z.

**"Stands" is read off the kind, never listed.** In order:

1. **The vocabularies this file already keeps.** `held-states` for a value
   (observed or declared; `retired` is shut), `standing-states` for an outcome
   (`offered` or `accepted`), and *not past* for a person. The outcome case is
   the one worth saying out loud: `accepted` is **terminal to the machine and
   live to the household** — the bundle is being done — so the machine's word
   is the wrong one and the wall says so where a reader can see it.
2. **The kind's own ending vocabulary** — `feed/work-over?`, which reads the
   declared `:over` when there is one and the terminal states when there is
   not. This is what catches the specimen: `task` declares
   `:over {:field :status :accomplished #{"done"} :let-go #{"dropped"}}`, so a
   done task is over however fresh its **sync** state reads. Reusing the feed's
   own function rather than re-deriving it means this wall and the archive can
   never disagree about whether a row is finished.
3. **The clock**, for a kind with no ending to declare. An `event`'s machine is
   the sync machine and is never terminal, so *is this still ahead of us* is a
   fact about `ends_at` — or `starts_at` when nothing named an end — against
   `(:now ctx)`. Only a real `Instant` is judged.
4. **Anything else stands.** A row this house has not got, a kind with no
   ending and no clock: the wall refuses what it can **read** is finished and
   never guesses past that, the same rule `no-burial-without-a-diagnosis`
   holds about exposure. A wall that refused on what it could not classify
   would refuse honest bundles for a living.

### The forks, decided

- **Person is `current` *or* `observed`, which is wider than
  `names-a-person`'s `in-our-lives`.** Those are different questions.
  `names-a-person` refuses composing a Saturday around an unanswered guess
  about who somebody is — a wall about **affirmation**. This wall is about
  **closed books**, and a person nobody has answered yet is not a closed book.
  Only `past` shuts it.

- **The bundle's own value is subtracted, and citing it alone is refused.**
  Same subtraction `not-a-twin` makes and the same reason: `value_id` is a
  declared field, so listing it under `evidence` reads nothing the field did
  not already say. The consequence is deliberate rather than incidental —
  **serving a value is not the same act as reading the house** — and it cost
  the fixtures: `stage-outcome!`, two application suites and six stagings in
  the conformance pack all cited their own value and nothing else, and every
  one now cites a row it read. That churn is the finding, not a side effect:
  the shape the specimen wore was the shape every fixture wore.

- **Refuse only when EVERY citation is closed**, never when one is. A bundle
  built on an open task and the finished one that came before it is a bundle
  reading the record properly, and demanding every citation be live would
  forbid exactly the reasoning a composer is for.

- **No floor, and this is not one.** It does not ask for two rows, or for a
  particular kind of row. It asks that the record the bundle stands on not be
  entirely shut.

### `the-door-is-open-now` — the button has to be there

An invoke piece names a row and a door. `the-row-it-names-is-there` already
checked that the row exists and that the door leaves from its state; this adds
the case that wall cannot see — the row **is** in a state the door leaves from
and the door's own guards refuse it anyway.

**It asks the engine rather than itself.** `render/action-availability` is new
and public: the envelope's `actions` / `unavailable` partition asked one door
at a time, with the same law resolution (`judgment/resolve-action`), the same
guards in the same order, and the same no-admissible-input narration. The
refusal quotes **that door's own** unavailable reason. The ns's standing rule —
*a wall that tried to predict another kind's guards would be a second opinion
about that kind's law, and it would be wrong first* — is kept rather than
broken: the wall does not predict those guards, it runs them the way the
household's screen does.

- **A denier reading `:principal` is about the HAND, and the wall stands
  down.** The probe can only ask as the principal it has — the composer —
  while the tap will be a **member's**. `g/role`, `g/not-the-field` and the
  actor-type walls all declare `:reads [:principal]`, so the distinction is a
  declaration rather than a heuristic. Refusing a piece because the *composer*
  could not tap it would refuse the household its own Saturday; a plan a member
  may lawfully take is precisely what a composer is for, and
  `outcome_piece.take` judges the hand again, as the member, in the
  transaction.
- **A hidden door is left alone.** Concealment means the action does not exist
  for this principal, and narrating *it is hidden from you* would be the leak
  the hide flag exists to prevent. `the-prepared-input-fits-the-door`'s
  sentence about a door this kind does not have is the honest one.
- **It still does not predict the tap.** The world moves between staging and
  Saturday; `the-target-has-not-moved` and the target's own guards answer that.
  This closes only the case where the door was **already** shut at staging,
  which is not a race and never was.

### What it deliberately does not catch, recorded rather than hidden

**The specimen's own piece stages, and it should.** `task.prioritize` leaves
from the sync states, and a done task is `fresh`; nothing in `task`'s
declaration shuts its rank on a finished row, because the rank is hub-local and
the lifecycle is data (`:over`) and the two never meet. The one guard that does
refuse there is `role:ranker`, which reads `:principal` — the composer's hand,
not the row. So the piece is admitted and the **bundle** is refused, which is
where the specimen's fault actually lay: it composed from a closed book, and
the piece was the symptom. That a finished task still offers *Done* and
*Prioritize* on the household's own screen is a real gap, and the wall for it
belongs on `task`'s declaration where every reader of that kind would see it —
**waymark-tgy**.

### Where the law is proved

- **`workqueue10.outcome-test` § 23**, five deftests over the real ring
  handler: the specimen re-run and refused, naming the row and `is done`; a
  finished row beside an open one admitted; a past event refused by its own
  clock; a row this house has not got admitted, which is the never-guess arm
  asserted rather than described; the value-only citation refused with
  *value_id already said that*; a piece against a door the **row** has shut
  (`make_it_so` on a pieceless bundle) refused in that door's own words and
  admitted the moment the bundle has a piece; a piece against an open door and
  a create piece both untouched; and the specimen's `prioritize` piece staging,
  with the reason written down.
- **No scenario names either wall**, for `not-a-twin`'s reason exactly: both
  questions are about **another row**, and a scenario holds one literal input
  over an empty store, so every scenario reaching these doors is an allow.
  `check-queue` reads outcome as 16 refusing guards and outcome_piece as 11.
- **The conformance pack was staging what this now refuses**, and it was fixed
  rather than the wall narrowed: six `feed-*-violations` stagings cited
  `[vself]` and nothing else. `packs.clj/a-row-read` mints one address per
  staging — a collection this house serves, a row it has not got — which is
  what `not-a-twin` (no two bundles sharing a row) and this wall (something
  still open) want at the same time.

- **Files:** `waymark10/src/waymark10/server/render.clj`
  (`action-availability`, public); `workqueue10/src/workqueue10/resources/outcome.clj`
  (`composes-from-what-stands`, `the-door-is-open-now`, `still-with-us`,
  `standing-words`, `ending-word`, `past-word`, `closed-word`, both
  create-guard lists, the ns docstring's two sections);
  `waymark10/src/waymark10/test/packs.clj` (`a-row-read` and six stagings);
  `workqueue10/test/workqueue10/outcome_test.clj` (§ 23, `a-row-read`);
  `workqueue10/test/workqueue10/value_test.clj`,
  `workqueue10/test/workqueue10/person_test.clj` (their stagings);
  `SITTING.md`; this file.

## Built — 9xn, iterate is a state (2026-08-28, waymark-9xn)

**The owner, reading his own fridge:** *when I iterate on an outcome, it
doesn't remove it from my feed. I think it should. It should probably go into
a state of 'Needs Iterating' and then I shouldn't see it until it's been
iterated on.* He named it as a reason he had stopped answering yes/no on the
fridge at all. waymark-9j2 built the loop as a **self-loop** — `iterate` kept
the outcome `offered`, stamped `iterate_requested_at`, and the crown, which
reads `state=offered`, went on showing it — so the morning after a person said
*the plan is wrong* the house asked them the same question again. A card that
re-asks what you have already answered teaches you to stop answering.

This section makes the gesture a **state**. `offered --iterate--> iterating
--rework--> offered`: the plan is in the composer's hands, the bundle is off
the feed, and the composer's own commit is the only door back.

### The forks, decided

- **A state, not a flag.** The population is `rows-of ctx :outcome {:state
  "offered"}` and nothing else, so `iterating` leaves the crown **by
  construction** — no boolean on the row, no second filter beside the query,
  and no way for the machine and the feed to drift apart about what is being
  shown. 9j2's fork note argued a new state would "drop the outcome off the
  feed the instant a person asked to keep it"; that IS the ask, and keeping
  the outcome is what `iterating` does — the goal stands, the row stands, only
  the question stops.

- **The state is the invitation, so the invitation wall is gone.**
  `iterate-open?` (two stamps compared) and `the-outcome-invited-this-rework`
  are deleted; `rework` simply `:from #{:iterating}` and the machine answers.
  `the-parent-invited-a-rework` survives on the piece and now reads the
  parent's **state** rather than its stamps. The three stamps stay as the
  RECORD of the round — the card still says *Reworked from your note — plan
  v1* off `reworked_at` / `plan_revision` — and no longer as anybody's wall.

- **One verdict closes, with a sentence, and the other two stay open.**
  `make_it_so` from `iterating` would take the very pieces the person just
  called wrong, so it is refused — and refused by a GUARD
  (`the-plan-is-not-under-rework`, `:from` naming `iterating` so the door is
  reached) rather than by the machine's out-of-state 409, because *Available
  in state(s) Offered; the resource is Iterating* is true and says nothing
  about what happened or when the bundle comes back. `not_this_week` stays
  open from `iterating`: a decline is always allowed, and holding a person's
  own no hostage to an agent's turnaround would be law 6 inverted. `expire`
  stays open too — the leash keeps running, so a composer that goes quiet
  costs the household a week, never a stuck row.

- **The pieces read the parent's state, in three different directions.**
  `take` is refused while the bundle is `iterating`
  (`the-bundle-is-taking-answers`, its own sentence: *you said the plan was
  wrong and the composer has not answered yet*). `rework` (withdraw) opens
  **only** while it is. And the CREATE door widens to admit `iterating`
  (`open-to-a-piece`), because staging the replacements is exactly what a
  re-plan is — a wall that read `offered` alone would have refused the rework
  it invited.

- **`iterate` from `iterating` is a self-loop, and that one is right.** A
  second thought about the same unanswered plan is another turn on the thread;
  nothing about the row's standing changed, so there is no state to move to.

- **The authorship wall becomes grantable, in the direction four eyes never
  will.** `only-its-composer-reworks` was `g/is-the-field :composed_by`, which
  refused every other hand outright — so a bundle in `iterating` whose composer
  is gone (a suspended principal, a lapsed leash, an agent retired between
  sittings) was a plan nobody in the house could ever revise, and the household
  could not delegate the revision either. It is now
  `g/author-or-granted` — **`unless-granted` with the own-field exemption
  inverted**: the row's own composer passes with no grant at all, and anybody
  else passes only under a presented grant admitting `<kind>.rework` on that
  row. Four eyes is untouched and stays absolute: `the-composer-does-not-
  decide` still refuses the stager at every verdict door, grant or no grant.
  The two laws are mirror images and the inversion is the whole point —
  authorship is what CLOSES a verdict door and what OPENS a rework one.

- **One line under the crown, and it is a line rather than a card.** A bundle
  vanishing with nothing said reads as the house forgetting. `crown.reworking`
  carries `{count, says}` — *2 bundles are being reworked — the house asked for
  the plan to change, and you'll see them again when the composer answers* —
  beside the ask chip, where the reader is already looking for the bundle that
  went quiet. No verb, because there is nothing here to answer; that is what
  makes it a line. It counts what the crown WOULD have shown: the reader's own
  compositions are excluded and a lapsed one is not coming back, the
  population's own two filters. Who asked is said as *the house* rather than as
  *you*: nothing on the row records which member tapped `iterate` — the note is
  a thread remark in their own voice — so a line addressing this reader as the
  one who said it would be a guess about a household with more than one adult
  in it.

- **The driver reads the state, and says what it cannot do.** `iterate_open`
  on a `standing_outcome` is `.state=="iterating"` now, not a comparison of two
  stamps; the manifest gains a duty count (`rework_iterating`) and two
  headings — *Handed back for a rework — YOUR work orders*, and **iterating,
  not yours to rework**, which lists an iterating bundle whose composer is
  somebody else, names that composer, and says the way through is a grant the
  owner approves rather than a twin. `standing` is three states everywhere it
  is read (the cited set, the twin report, the crowd-out reading, the
  value-with-no-live-outcome probe): a bundle being reworked speaks for the
  rows it cites exactly as loudly as one on the fridge — more so, since a
  composer is under orders about it.

### Recorded here, for whoever comes next

- **`make check-queue`: 38 kinds, 11 warnings, 57 scenarios judged** (was 38 /
  11 / 56 on `main`) — one scenario added,
  `a-decline-is-allowed-from-under-a-rework`, check tier. `outcome` reads
  *7 scenarios ✓ (2 deferred), 16 refusing guards, 6 named by a scenario*;
  `outcome_piece` *3 scenarios ✓ (4 deferred), 12 refusing guards*.
- **`the-plan-is-not-under-rework` is named by no scenario, and the reason is
  the WALKER rather than the wall.** A scenario on `make_it_so` defers to the
  suite whatever it is about, because a sibling guard on that door
  (`something-is-still-on-offer`) reads `:outcome_piece` — and the conformance
  walker stages its subject through the kind's OWN create door, which for an
  outcome demands a value this house holds and evidence it can read, rows a
  declaration-time literal cannot mint. The first cut of this bead shipped that
  scenario and the suite answered *the row it describes could not be staged
  through its own door*, which is the honest verdict: it is proved in
  § 21 instead, over the real ring handler against a real value and a real
  bundle. Third time this file has had to write that sentence down
  (`names-a-person`, the piece's `only-its-composer-reworks`).
- **Exactly two fingerprints moved, and they are the two whose machines
  changed.** Computed over the whole 53-kind census
  (`engine/full-registry` over `workqueue10.main/check-resources`) on `main`
  (`c160d21`) and on this tree: 51 byte-identical — `feed_recipe`
  `9e5ba71d…`, `insight` `d5b2724b…`, `ranking_note` `c863e054…`,
  `verdict_reason`, `feed_view` and `composition_request` unmoved.
  `outcome` goes `1b0e6c24…` → `334d3a45…` (a state, four doors' `:from`/`:to`,
  two new guards, one guard deleted, one wall re-minted) and `outcome_piece`
  `e6b59232…` → `0479ff85…` (`take` gains a wall, both rework walls change).
  `boot-revise!` mints one revision on each.
- **No migration, and it was computed rather than argued.** `state` is a plain
  `state text NOT NULL` engine column (`store/engine-columns`) — no enum, no
  check constraint, no per-state index — and this bead declares no new
  filterable or sortable field, so nothing is promoted. The two kinds' STORAGE
  facets were printed on `main` and on this tree and diffed **byte-identical**,
  which is exactly what an empty migrate plan is: same table, same fifteen
  columns, same four indexes. No `:renames {:states …}` is needed either — a
  token is being ADDED, and `assert-known-states!` only refuses tokens live
  rows occupy that no declaration names. `image.yml` applies the plan on
  deploy with a human tap when it is non-empty; here it is empty.
- **Tests:** `workqueue10/test/workqueue10/outcome_test.clj` § 21, rewritten
  and grown to four deftests — the church example end to end (crown before,
  gone after with the line under it, pieces untappable, `make_it_so` refused in
  the house's words, a replacement staged under the iterating bundle, the
  commit that returns it, the crown again, the accept); a decline from under a
  rework; a bundle nobody reworks lapsing on its own week; and a second agent
  reworking an orphan under a grant naming the row, refused without it, with
  the transition reading *under grant-…*.
- **Files:** `waymark10/src/waymark10/guards.clj` (`author-or-granted`,
  `not-yours-sentence`); `workqueue10/src/workqueue10/resources/outcome.clj`
  (the `iterating` state, four doors' `:from`/`:to`, `open-to-a-piece`,
  `the-bundle-is-taking-answers`, `the-plan-is-not-under-rework`,
  `reworks-wall`, `the-parent-invited-a-rework` re-read, `standing-states`,
  `iterate-open?` and `the-outcome-invited-this-rework` deleted, two
  one scenario); `waymark10/src/waymark10/server/feed.clj` (`reworking-doc`, the
  crown document, the population's docstring); `waymark10/src/waymark10/server/diagnosis.clj`
  (`answered` lapses from `iterating` too, `lesson` reads it as
  `still_offered`, and the sentence says it is a work order rather than a
  silence); `scripts/sitting-run.sh` (the states read, `iterate_open`,
  `iterating_not_mine`, the duty count, two manifest headings);
  `workqueue10/test/workqueue10/outcome_test.clj` (§ 21); `SITTING.md`;
  `.claude/skills/sitting/SKILL.md`; this file.
- **Left standing, deliberately.** `iterate` still does not touch the leash
  (9j2's own note: the week is the week), there is still no piece-level
  iterate, and nothing sweeps `iterating` — the clock and the composer are the
  only two things that move a bundle out of it, which is the same posture
  `offered` has always had.

### Ruling, 2026-08-29 (waymark-vf8): a composer cannot PROMISE a rework

**The specimen.** The sitting of 00:15Z answered two iterate notes with
remarks — *Understood. I will rework this to include getting Howie to his
friend's birthday party*, *…a good step to include* — and reworked nothing.
Both bundles (`217f5678`, `99c62050`) sat in `iterating` at revision 0, off
the owner's feed, until some later composer acted, while their threads read as
though they had been answered. **The owner:** *should we make it so it can't
promise, it can only act — by only giving the option to resolve the iteration
and include a comment?* Yes. The tap is the write; a promise has no state.

- **The wall stands on the `remark` door, and it binds exactly one hand.** An
  agent may not post a remark whose subject is an outcome in `iterating` when
  it is that bundle's `composed_by` **or** holds a grant admitting
  `outcome.rework` on that row (the admission is asked of `(:grant ctx)` —
  `grants/visibility`'s own closure, the one `unless-granted` and
  `author-or-granted` ask, so the wall and the projection cannot disagree).
  The refusal is this kind's own sentence, with the address filled in: *This
  bundle is handed back for a rework. Answer at /api/outcomes/<id>/-/rework —
  withdraw or stage what changes and say why in says; a rework that changes
  nothing is a lawful answer too. Words alone do not answer an iterate.*
  Untouched: a **person's** remark (the act was never theirs to make), a
  remark on an `offered` or answered bundle, the turn of an agent that holds
  **no** rework door on that row — it has nothing but words, and taking those
  would leave it mute — and any turn a DOOR files from inside itself.

- **The framework owns the predicate; this kind owns the sentence.** `remark`
  is a framework kind enrolled `:always` (one law per kind — an app cannot
  re-declare it), and its whole posture is that it asks no kind-specific
  question of the subject. So the SUBJECT declares the clause and the thread
  kind reads it: a new top-level declaration key, `:answered-at-a-door {state
  {:door … :whose … :explain …}}`, validated at the def site
  (`checks/check-answered-at-a-door`: the state is declared, the door is an
  action of this kind, `:whose` is a schema field, `:explain` names `{door}`),
  read by `remark`'s `words-do-not-answer` through `(:rdef-of ctx)` and
  `(:read ctx)` — `ranking_note/not-your-own-row`'s idiom exactly, one kind
  over. Nothing in `waymark10/` learns the word *outcome*.

- **The rework's own reply is not a promise, and `:within` is what says so.**
  `rework-the-plan` posts its `says` as a turn on this very thread
  (`:touches {:kind :remark :action :create}`), from the composer's own hand,
  while the row it names is still `iterating`. A wall that could not tell the
  answer from the promise would have closed the loop it exists to protect —
  and would also have refused a granted delegate's second `iterate` note,
  which rides the same seam. So the wall fires **at the wire only**: a remark
  a door files from inside itself is that door's own record, judged by that
  door's walls, and a promise is always a client's POST. `(:within ctx)`,
  waymark-jfv.20's seam, whose second reader this is.

- **`says` is required and short.** It was already required (`:min 1`); the
  cap moves 600 → **240**, the house's own note ceiling
  (`resource/verdict-action`). It is the composer's only turn on a handed-back
  bundle, and one turn back to the household answering their note is a
  sentence or two — the round's real answer is what the pieces now say. An
  empty `says` is a 422 at the door, not a guard.

- **A rework that changes nothing is a LAWFUL answer, and no new door was
  added for it.** No guard ever demanded a diff (*stage the replacements
  before you commit* was prose, in the skill and in `:one-way`), and none is
  added: a round with no withdrawn and no added piece still bumps
  `plan_revision`, stamps `reworked_at`, returns the bundle to `offered` and
  replies with `says`. The composer read the note and stands by the plan, or
  cannot stage what was asked; the crown shows the bundle again and the
  person may decline it. A door that demanded a diff would teach the composer
  to stage a cosmetic one, and a separate *decline to rework* door would be a
  second way to say the one thing this door already says — **deliberately not
  built**.

- **The driver says it plainly, and grades it.** *Handed back for a rework —
  YOUR work orders* now says the reply door is closed and the rework door is
  the answer; `sitting-run.sh verify` prints **HANDED BACK, NOT REWORKED:
  `<self>`** for every bundle of this principal that the last manifest listed
  as `iterating` and that still stands at the same `plan_revision`. A promise
  in the thread no longer reads as an answer anywhere.

**Recorded for whoever comes next:** `make check-queue` is **38 kinds, 11
warnings, 56 scenarios judged** (was 57). One scenario moved from judged to
DEFERRED and no scenario was deleted: `remark`'s
`nobody-speaks-in-somebody-elses-voice` walks the create door, which now
carries a guard that reads `:storage`, and the declaration-time walker defers
any door it cannot stage a world for — `ranking_note`'s posture exactly, whose
create scenario defers for the same reason. **A deferral is not a loss**: the
conformance tier picks it up, and `waymark10.law-scenarios-test/the-deferred-
half-is-paid-by-the-suite` — the pin that says the two tiers add up to every
scenario and no more — moves **7 → 8** with `remark`'s name added to its
sentence. CI caught that one, which is what the pin is for. **No fingerprint moves.** Action
`:input` schemas ride `fingerprint-of` only through their DEFAULTS
(`create`/`input_defaults`), so the `says` cap does not mint a revision;
`:create-guards` are outside the projection altogether, so `remark` does not
move either; and `:answered-at-a-door` is a new key the projection does not
name — advertisement-class, like `:nav` and `:over`. No migration: no state,
column, filter or sort changed.

## Built — wxk (2026-08-29): the marks ARE the rework order

**The owner:** *part of my revising should be picking the pieces that need
revision so that the AI can focus its attention.* Until this bead an iterate
was **one note about a whole bundle**, and which pieces it meant was a guess.
A strong model read the note and revised the right two; a weak one rewrote
everything, or nothing, and neither could be held to it — the composer's
attention was spent on inference the household could have spent one tap on.

**Nothing new was added to say it, and that is the whole of why it is cheap.**
The pieces already carry the household's own per-piece verdicts and
`verdict_reason` already carries waymark-jfv.16's four quick words behind them.
So a **MARK** is a piece's `not_this` plus a word, and the five lists are that
word read as an order:

| what the household did | list | what the composer owes |
| --- | --- | --- |
| declined it, **wrong time** | **RE-TIME** | one NEW piece — the same step at a new hour or on a new day |
| declined it, **wrong piece** or **not this way** | **REPLACE** | one NEW piece — a different step toward the same goal |
| declined it, **never this** | **DROP** | nothing. The decline already took it out |
| declined it and said **no word at all** | **DROP** | nothing (see below) |
| left it **offered** | **KEEP** | nothing — and it is not the composer's to withdraw |
| asked in the note for something no piece covers | **ADD** | one NEW piece, the composer's reading of the note |

**A wordless decline is a DROP**, which is waymark-jfv.16 read literally rather
than a lenience: the quick word is optional there and *not tapping is a
complete answer*, so a decline that said nothing spelled no order beyond *not
this one* — and *not this one* is a piece already out of the bundle. A composer
may well replace it, and the manifest says so; the wall holds it only to what
the household actually said.

**Nothing is withdrawn to answer a mark.** A declined piece is already out of
the bundle (`make_it_so` takes only what is `offered`, and `a-bundle-is-small`
counts only what is `offered`), and `outcome_piece.rework` leaves from
`offered` alone — so a marked piece **cannot** be withdrawn and does not need
to be. A RE-TIME and a REPLACE are each **one new piece staged under the same
bundle**, and that is all.

### The commit wall

`the-marks-are-the-work-order` stands on `outcomes/{id}/-/rework`, beside
waymark-vf8's `only-its-composer-reworks`. **The predicate**, in order:

1. **The round has two boundaries because it has two sides.** What the
   HOUSEHOLD said runs from the last commit (`reworked_at`, or the beginning of
   time for a bundle nobody has reworked yet) — which is what admits a piece
   declined *before* the iterate was tapped: marking the pieces and then
   handing the plan back is the same gesture in the other order, and a person
   does both. What the COMPOSER staged runs from the ask
   (`iterate_requested_at`): a piece staged before the person tapped iterate is
   part of the plan they were looking at when they marked it, so it cannot also
   be the answer to those marks. One boundary for both was written first and
   was wrong in exactly one place, which CI caught — with no commit yet
   `reworked_at` is absent, so every original piece of a first round counted as
   a replacement and the wall admitted a round that had staged nothing at all.
   A bundle with no `iterate_requested_at` (no such row exists; the door stamps
   it) falls back to the household's boundary, which is the lenient direction.
2. **If nothing was marked in this round, the wall says nothing.** The note is
   then the whole order and which pieces move is the composer's reading of it —
   *add what it asks, or stand by the plan and say which in `says`*. Howie's
   ride to the party was exactly that shape. A wall that read an unmarked round
   as five KEEPs would have refused the honest re-plan of a note reading *move
   the whole Saturday to Sunday*, which is a wall against the work it exists to
   get.
3. **Otherwise** it refuses when either arm holds, naming every offender with
   its list:
   - a piece the household **left standing** was withdrawn this round (state
     `reworked`, `updated_at` inside the round) — *you withdrew
     `/api/outcome_pieces/…` — … The household left that one standing while
     marking others, which is a KEEP — a piece nobody marked is one they want,
     and withdrawing it answers a question they did not ask.*
   - fewer pieces stand than the marks owe — *the household marked N pieces for
     a new one and this round has staged M — RE-TIME `/api/…` — said
     wrong_time; REPLACE `/api/…` — said wrong_piece. …*

**The arithmetic is a COUNT and is said as one.** Nothing pairs a replacement
to the piece it replaces — no row records that, and the wall will not guess —
so what it can honestly ask is that as many pieces stand as the marks owe. The
refusal names every owed mark, so the composer knows what the count is about.

**A no-change round stays lawful exactly where waymark-vf8 left it**: in a
round where nothing was marked. In a marked round, *the plan stands* is not an
answer to *this piece is at the wrong hour* — the person picked it out.

### The card, and the lite page

The iterate chip **expands in place** into one row per piece —
**Keep · Wrong time · Wrong piece · Not this way · Never this** — and then the
note. Tapping a word fires that piece's own decline and posts the word behind
it, through the two doors the settled card already used; tapping the last chip
opens the verb's ordinary note dialog. Every control is a tap; the only place
anybody types is the note field that was always there.

**No application name reaches either page.** The dispatch is a new
advertisement on the action, `:display {:marks "pieces"}` — `:reasons`' own
class, riding no fingerprint facet — whose **value is the link rel whose rows
are the parts**. The feed already has those parts on the card (`pieces`, from
the population) and needs only to know that this verb marks them;
`ui_lite.html` has no card, so it follows that rel off the row's own declared
links. Each part's decline door is found by the `display.reasons` it already
advertises, never by name, and the four words come off the feed document's
`reasons` — or, in the lite page, off the framework's own `verdict_reason`
schema, that kind being waymark10's and enrolled into every engine, so the lite
page naming it is the framework naming itself. Colours are tokens only; the
assembly test greps for both.

### The driver

Under *Handed back for a rework — YOUR work orders*, each bundle now prints its
**note beside its lists**, with the expected write spelled per list. A fixture
run of the manifest section (`derived/rework_orders.json` over one bundle at
plan v1 whose owner marked four of five pieces and whose composer has staged
one replacement):

```
  ─ /api/outcomes/O1 — One Sunday that holds church, a walk and Howie at the party
    THE NOTE: colton said “Sunday breakfast is fine but the walk clashes with
              church, and Howie needs a ride to the party at 2.”  (/api/remarks/M1)
    KEEP — write nothing, and withdrawing one of these is REFUSED:
      · /api/outcome_pieces/P1 — Load the truck Friday evening — twenty minutes
    MARKED — each is an order, and the commit is refused until it is answered:
      · RE-TIME  /api/outcome_pieces/P2 — Park walk at 9am Sunday
          said wrong_time — “It clashes with 9am church.”
          write: POST /api/outcome_pieces — the SAME step at a new hour or on a
                 new day, citing this bundle
      · REPLACE  /api/outcome_pieces/P3 — Drive to the lumber yard for stock
          said wrong_piece
          write: POST /api/outcome_pieces — a DIFFERENT step toward the same
                 goal, citing this bundle
      · DROP  /api/outcome_pieces/P4 — Rope Grandma into babysitting
          said never_this — “Never ask her for that.”
          write: nothing — the decline already took it out of the bundle
      · DROP  /api/outcome_pieces/P5 — Wash the car first
          said no word at all, so it is a DROP
          write: nothing — the decline already took it out of the bundle
    ADD — whatever the note asks that no list above covers: a NEW piece citing
          this bundle. Nothing else in the note is an order.
    THIS ROUND SO FAR: 1 staged, 2 owed — 1 STILL UNANSWERED
```

`sitting-run.sh verify` grades the same order after the run, per handed-back
bundle: each mark with its list, a DROP marked *answered by the decline
itself*, then `ADDRESSED: N owed a new piece, M staged this round` or `NOT
ADDRESSED: …`, plus `WITHDREW A KEEP: /api/outcome_pieces/…` for a piece the
manifest listed under KEEP that now reads `reworked`. It grades against the
boundary the MANIFEST recorded (`rework_orders[].boundary`), so a commit that
has since moved `reworked_at` does not erase the evidence.

**Reproducing the fixture run by hand** — there is no harness for the driver in
this repo, and this is the documented substitute. Extract the two jq programs
from `scripts/sitting-run.sh` (the one writing `derived/rework_orders.json`,
and the one printing the manifest section) and run them over four small files:
`pieces` (six `outcome_piece` rows — one offered before the boundary, four
declined inside it, one offered inside it), `reasons` (three `verdict_reason`
rows — `wrong_time`, `wrong_piece`, `never_this`), `threads` (one turn by the
member), and `standing` (one iterating bundle at plan v1 whose `reworked_at`
precedes the declines). The block above is what they print. Flipping the KEEP
piece to `reworked` and adding a second replacement turns the verify grading
from `NOT ADDRESSED` to `ADDRESSED … WITHDREW A KEEP`, which is the other arm.

**Recorded for whoever comes next.** `make check-queue` is **38 kinds, 11
warnings, 55 scenarios judged** (was 56), and the scenario that went is
`only-the-composer-that-staged-an-outcome-reworks-it` — **deleted, not
deferred**, and the deletion is forced rather than a retreat. It walked
`outcome.rework`, which now carries a guard reading `:outcome_piece` and
`:verdict_reason`, and a scenario is judged against its door's WHOLE guard
chain (the chain rule that moved `remark`'s create scenario one bead ago,
arriving here on an ACTION door). Deferred, it goes to the conformance walker,
which stages its subject through the kind's own create door — and for an
outcome that door demands a value this house holds and evidence it can read,
rows a declaration-time literal cannot mint. `:core/law-scenarios` runs over
the whole registry regardless of the pack's `:kinds`, so the deferral was a red
suite, not a quiet no-op: *the row it describes could not be staged through its
own door*. That is the sentence the note above `a-decline-is-allowed-from-under
-a-rework` had already predicted for `the-plan-is-not-under-rework`, said out
loud by CI. The refusal it stated is proved where it can be —
`workqueue10.outcome-test` § 21's loop deftest, over the real ring handler,
against a real value and a real bundle. `the-deferred-half-is-paid-by-the-suite`
counts the FRAMEWORK's kinds and stays at **8**. **The `outcome`
kind's fingerprint MOVES**: an action guard rides `machine.actions.*.guards.N`,
so adding one to `rework` mints a law revision. **No migration**: no state, no
field, no filter and no sort changed, so the storage facet is byte-identical
and the migrate plan stays empty. `:display {:marks …}` is advertisement-class
and moves nothing on its own.

