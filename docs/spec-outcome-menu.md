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
the owner to retire the value it serves.

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
| `the-composer-does-not-decide` | the stager answering its own plan or piece (`g/not-the-field :composed_by`) |
| `a-person-answers` | **any agent** tapping any verdict, with the refusal naming the lawful path |

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
  wrong*.
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
same question twice.

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
