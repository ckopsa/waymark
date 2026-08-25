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
make the exposure floor unmeasurable.

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
- **A person's `relation` carries no filter**, deliberately: exact-match
  on free prose is a trap, and the words there are the family's rather
  than a vocabulary.
- **The roster does not populate the feed and owes no pack obligation.**
  Nothing about a person is a thing to do, and the feed's job is what the
  house could do next. A roster screen with the unanswered rows on top is
  the obvious first ask; that is when a population and its obligation are
  earned. Filed (`waymark-jfv.13`).
