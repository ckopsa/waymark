# Spec — the feed: history for fuel, one tap to the next action

**Thesis.** The household has 113 rows of work and a person who opens the app,
reads a table, and closes it. The gap is not information — every fact is
already a row — it is *motivation* and *friction*. A feed closes both: history
as fuel (the queue you cleared, the streak you are on, the photo of the thing
you finished) scrolled beside the one physical next action, with the verb under
the thumb. It is a **projection**, not a subsystem: the transition log already
holds the events, standing state already holds the lookahead, time travel
already holds last July, and `render/envelope-summary` already knows how to
show one row through one reader's grant. What the feed adds is an **order** —
declared as static data, seeded by (member, day) — and a **seam** that says
*that's everything*, because a surface that never ends is a surface that never
finishes.

The success metric is written into the audit trail rather than into an
analytics table: **actions taken from the feed**, countable because every card
verb rides `invoke` and every invoke from a card carries a declared prefix on
its `Idempotency-Key`. Time-on-feed is not measured, on purpose. If this
surface works, people close it sooner.

## Epistemic status

The modest claim is the load-bearing one: **almost nothing here is new
mechanism.** Six of the seven card populations are queries over rows and logs
that exist today; only `insight` mints a row. The card body is
`render/envelope-summary` with `:visibility` in its ctx-opts — the same
projection a collection item already gets. The paging is `links.next` over an
opaque cursor. The verb filter is `demand/heavier?`, already public precisely
so a second surface could ask it (`demand.clj:60`, made public for
`usability/gesture-duties`). The tickler and the insight are both `:decision`
kinds, and `:decision` landed two days ago (`spec-decision-kind.md`).

What is genuinely new is exactly three things, and they are all small: a
**recipe** (static data, an ordering of populations), a **seed** (a hash, so
the order is stable within a day without storing anything), and a **seam**
(one element in the answer that is not a row).

What deserves suspicion is the same thing `spec-addressed-notice.md` says
about itself: this is the most product-shaped spec in the tree, and feeds are
where well-designed engines go to grow a ranking model. The wall against that
is stated as law below and is not negotiable — **the recipe is static data.**
If an implementation of any bead in this epic finds itself writing a scoring
function, a per-card click counter, or an "engagement" anything, the bead is
wrong and the epic's own paragraph is the citation.

Second suspicion, named so it is not discovered later: **the feed is not the
notifier.** `spec-addressed-notice.md` owns push, and its own recorded punt —
*"a household-level digest is the answer, not a fallback recipient"* — is the
sentence this surface claims. The feed is a **pull**. It has no badge count, no
unread state, no per-card seen row, and it must never grow one; the addressed
notice will be separately judged, and a feed that also poked people would be
two products in one door.

## What exists

**The order is already refused a home.** `checks.clj:605` closes view kinds at
`#{:deck :feed}` and `checks.clj:744-747` refuses a gesture side on a `:feed`
view with *"is a :feed — a sequential read takes no {side} gesture"*. So the
framework already has an opinion about what a sequential read is, and it is
*not* a swipe deck. That opinion is inherited below, and it corrects one
sibling bead's framing.

**`saved_view` (`saved_view.clj`)** — `:view_kind [:enum "deck" "feed"]`,
`:target` a single kind name or plural, `:where` in the collection wire
grammar, `:card` a field subset, `:right`/`:left` naming reversible actions.
The write gate is `composes-declared-primitives`, which runs
`checks/view-problems` against **the one target kind's declaration**. One
target. That singularity is the whole reason for the UI fork's verdict.

**`ui/134-feed.js`** — `VIEW_RENDERERS.feed` (waymark-h50), *"the collection,
one full-screen panel at a time"*. It derives `kind` from `doc.kind`, arms
`watchScope({kind})`, builds card fields from that kind's `gridColumns`, keys
items on `item.self`, pages through `doc.links.next` behind an
`IntersectionObserver`, renders each item's **own** `actions` through
`actionButton` (*"grants and state keep gating and the feed invents no
affordance"*), and paints an honest terminal panel. Single-kind, and rich in
exactly the mechanics a mixed feed needs.

**`ui/136-dashboard.js`** — the heterogeneous-page precedent: *"render() forks
here by kind, the deploy-history tradition"*, panels filling concurrently and
degrading alone.

**`modules.clj`** — the inventory, four closed contribution columns (`:enrols`,
`:routes`, `:hooks`, `:pack`), engine opts read at the start site with their
defaults, and the no-discovery paragraph.

**`grants/visibility`** — `{:kind? :row? :action? :field? :arg? :ids-of
:conds-of :whole-kind? :hashed? :hash}`. `render/envelope` and
`envelope-summary` take it in ctx-opts and project: *"only granted actions
survive, absent from `actions` AND `unavailable` alike (concealment, never
narration)"*.

**`demand.clj`** — `classes ["assent" "selection" "recall" "composition"]`,
strings not keywords; `(heavier? a b)`; effort computed at render time
(`render.clj:313`, `:437`) and stamped on every action entry. Envelope-only,
never fingerprinted.

**`store/transition-stats`** — weekly buckets over the log by (week-start,
kind, action, actor-type), `include-system?` false dropping the mirror beat.
`seasons/report` already folds it into weeks and aging, projected per grant
through `whole-kind-sight?`. This is the fuel population's engine and it needs
no new store method.

**`history/collection-as-of`, `history/row-history`, `history/row-as-of`** —
the "a year ago this week" reads, with `fold-cap` 2000 and an honest
`complete` flag.

**`:decision` (`resource.clj:1179`)** — `:asks :by :decider :verdicts :stamps
:expires :pacing :offered :own-surface` → states, verdict actions, guards,
schema, create door, `:default-filters {:state "offered"}`, `:sortable
"-created_at"`, `:nav :system`. Two verdicts minimum; **at least one must leave
the open state**, which means a verdict may also *return* to it. `permission_slip`
is the first house-read instance.

**`invoke/finish!`** — the transition row: `kind resource_id action from_state
to_state actor at law_revision input_digest inputs acknowledged judgment
correlation_id idempotency_key summary`. `idempotency_key` is caller-supplied,
opaque, and stored whenever present. `correlation_id` is engine-minted for
cascade parentage. **There is no origin column and this spec does not add one.**

**`task` (`workqueue10/resources/task.clj`)** — a mirror kind,
`:push-on-write true`, `:create-push true`, `:document :partial`, `:on-gone
{:set {:status "dropped"}}`. Twenty-five of a hundred and thirteen rows are
`dropped`. That pile is the tickler's population and the epic's reason.

## The laws of the feed

These four are carried verbatim from `waymark-iqa` because they are law, not
design, and a bead that finds one inconvenient is a bead that must argue with
the epic rather than with an implementation.

> - A card may only offer actions of effort ≤ selection (`demand.clj`'s
>   taxonomy; the usability battery's gesture-duties policy is the enforcement
>   precedent). Anything heavier links to the row's own screen.
> - No insight without an offered action — the compiler's job is decomposition
>   (the one physical next step of a stale project), proposed for one-tap
>   assent; it only ever OFFERS, acceptance is the member's, audited. Evidence
>   guard: no citation, no publish. Hard daily cap so the compiler must rank.
> - No manufactured engagement: the mixer recipe is STATIC DATA — declared
>   ratios, seeded by (member, day) so the feed is stable within a day; no
>   ranking model, no per-card analytics feeding order, no notifications
>   (addressed-notice's job, separately judged).
> - Every card is grant-projected through the reader's own surface — one
>   endpoint, per-member worlds.

The fourth is the one that costs something and the one that is easiest to
skip. `spec-modularization.md` records that *"a module's routes inherit"* the
router's default posture — SSE, openapi, surface and collab **404 a
grant-scoped request rather than projecting** — and `routes/law_sweep.clj`
takes that exit out loud (*"this door sidesteps it by refusing, and says so"*).
The feed may not. A feed that refused every grant-scoped reader would serve
nobody but the unscoped humans, and per-member worlds is the point. So the feed
route **projects**, and inherits `spec-time-travel.md`'s one security clause
verbatim: *an as-of read must project through the SAME visibility, or time
travel becomes a disclosure channel.* Every fuel and archive card is a
historical read. The clause binds them all.

## The design

### The document is a `feed`, not an envelope

`GET /api/-/feed` answers a dedicated document, the way `?as-of=` answers an
`as_of` document rather than a row envelope. This is the escape from
`spec-modularization.md`'s punt — *"a module that wanted to add an envelope key
would be a core change wearing a module's clothes; refuse it"* — because
`card_id`, `section`, `population` and `heavier` are keys of a **new document
type**, not additions to the standard envelope. A module may mint a document;
it may not widen the wire's envelope.

`spec-time-travel.md`'s reason for refusing an envelope was that *"a client
whose first rule is follow the envelope's own href would find live verbs
hanging off a historical document."* That reason **does not apply here and the
difference is worth stating**: a fuel card's *subject* is historical, but its
`self` href and its `actions` are the row **as of now**. Following a card's own
href is correct. So each card carries a real `render/envelope-summary` body,
projected through the reader's visibility, and the client's rule 1 holds.

```json
{ "waymark": "10",
  "self": "/api/-/feed",
  "day": "2026-08-24",
  "seed": "b1f0…",
  "sections": ["do_now", "decide", "fuel", "seam", "archive"],
  "cards": [
    { "card_id": "do_now/task/01HZ…",
      "section": "do_now",
      "population": "next_actions",
      "kind": "task",
      "self": "/api/tasks/01HZ…",
      "state": "fresh",
      "summary": "Call the dentist · open",
      "display": {"title": "Call the dentist"},
      "fields": {"due_at": "2026-08-25T06:00:00Z", "detail": "…"},
      "actions": {"complete": {"effort": "assent", "href": "…", "display": {…}}},
      "heavier": [{"name": "rewrite", "effort": "composition",
                   "label": "Rewrite", "href": "/#/api/tasks/01HZ…"}],
      "links": {"origin": {"href": "…", "external": true}} },

    { "card_id": "seam", "section": "seam",
      "above": 9, "sentence": "That's the house, caught up." },

    { "card_id": "archive/media/01J2…", "section": "archive", … } ],
  "links": {"next": {"href": "/api/-/feed?cursor=eyJ…"}} }
```

Four things about that shape:

- **A card names its origin row and never invents an identity.** `kind` +
  `self` are the row; `card_id` is the *card's* identity within the day
  (`section/kind/id`, or the literal `"seam"`). The client keys on `card_id`,
  not on `self`, because the seam has no row — and that one difference is why
  `134-feed.js` cannot be reused unmodified.
- **A card names its population.** `section` and `population` are the two
  names the recipe declared. They are for styling and for grouping the audit
  query, and for nothing else: no client may reorder on them.
- **A card's verbs are the row's own, projected then partitioned.** Order
  matters and is a security property: **project first** (`envelope-summary`
  with `:visibility`, which conceals ungranted actions from `actions` *and*
  `unavailable` alike), **then** split the survivors on
  `(demand/heavier? effort "selection")`. `heavier` therefore never reveals a
  door the grant conceals; it only names doors the reader already has, on a
  screen where they do not fit.
- **`heavier` exists so the card does not lie.** Silently dropping an action a
  reader holds is the failure `router.clj`'s own docstring names in another
  register — *"a surface that silently stopped existing"*. A card says *this
  door is real, and it is over there.*

### The recipe is static data

The recipe lives as an **engine opt**, read at the route's build site with its
default — the spelling `modules.clj` already uses for `:events-poll-ms`,
`:curtain-ttl-ms` and the rest (*"One engine, one opts map"*). It is not a
fifth module column; the contribution table is closed at four and stays closed.

```clojure
(def default-recipe
  {:salt "waymark-feed"
   :zone "UTC"
   :order
   [{:section :do_now  :population :next_actions :take 5}
    {:section :decide  :population :asks         :take 3}
    {:section :decide  :population :ticklers     :take 2}
    {:section :decide  :population :conflicts    :take 2}
    {:section :decide  :population :insights     :take 2}
    {:section :fuel    :population :cleared      :take 1}
    {:section :fuel    :population :streaks      :take 1}
    {:section :fuel    :population :finished     :take 2}
    {:seam true :sentence "That's the house, caught up."}
    {:section :archive :population :memories     :take 6 :bottomless true}]})
```

The **vector's order is the feed's order**. It is the epic's card census read
top to bottom, and there is no other ordering input anywhere in this design.
`:take` is how many cards that population contributes to one page. A
population with nothing to say contributes nothing and the seam moves up.

Four things about the recipe are checkable and should be checked at assembly,
in the module's route builder, throwing `t/definition-error` the way
`modules/selected` refuses an unknown label:

1. every `:population` names a member of the closed population registry;
2. exactly one entry carries `:seam true`;
3. at most one `:bottomless`, and it is last;
4. sections appear in census order (`do_now`, `decide`, `fuel`, seam,
   `archive`) — the census is law, so a recipe that puts fuel above do-now is a
   typo, not a preference.

The **population registry** is a closed map in the module namespace,
`{:next_actions (fn [ctx] …) :ticklers (fn [ctx] …) …}` — a var a reviewer
reads on one screen, never a classpath scan, for the reason `modules.clj`
already gives at length. The recipe is data; the populations are code; neither
is discovered.

### The seed

```
seed = sha256(salt ‖ member-principal-id ‖ local-date)
```

`local-date` is `((:now-fn eng))` in the recipe's `:zone`. Every population
that must choose *k* of *n* orders its candidates by `hash(seed ‖ card_id)` and
takes the first *k*. Same member, same day, same feed; midnight rolls it; two
members on the same day see different worlds because the id is in the hash.

This is the whole of "stable within a day", and it stores **nothing**. That is
deliberate and it is the answer to the first delegated fork below: determinism
was the only thing materialization was going to buy, and a hash buys it for
free and retroactively.

A card acted on falls out of its population on the next read, by the deck's own
rule — *"a committed card's action moves the row out of the view's `:where`
filter, so removal is local"*. No seen-state, no dismissal row, nothing to
sweep.

### The cursor

Above the seam the feed is **finite**: the recipe's `:take`s sum to one page,
and that page is the whole of do-now, decide and fuel. So:

- `GET /api/-/feed` answers the full above-seam feed, the seam, and the first
  archive page.
- `links.next` carries one opaque base64 cursor over `{:day :seed :offset}`.
  Following it answers **archive cards only** — the mixed sections are done,
  and re-serving them would be the duplication the epic forbids.
- A cursor whose `:day` is not today is refused with 409 and a sentence saying
  the feed rolled; the client re-reads from the top. Serving yesterday's seed
  today would be a second definition of "stable within a day".

**"Bottomless" means stateless and long, not infinite,** and the spec says so
rather than letting the UI discover it: the archive population is a seeded
ordering over a bounded candidate set, `:offset` walks it, and when it runs out
the answer omits `links.next` and `134-feed.js`'s honest terminal panel already
knows what to do. A surface that pretends to be infinite is a surface that
lies once, at the bottom, to whoever scrolled the furthest.

### Actions from the feed, in the audit trail

Every card verb is an ordinary `invoke` through the row's own action href. The
origin rides the **`Idempotency-Key` header**, by convention:

```
Idempotency-Key: feed/2026-08-24/do_now%2Ftask%2F01HZ…/9f3c1a
```

`invoke/finish!` stamps it into `waymark10_transitions.idempotency_key`
whenever it is present — not only when the action is non-idempotent — so
actions-from-the-feed is one `LIKE 'feed/%'` away, per day, per section, per
kind, forever, retroactive to the day the convention lands.

The trailing nonce is not decoration: `idempotency-lookup` is scoped `(key,
kind)` and `p/idempotency-key-reuse` throws when the same key returns with a
different digest, so two taps of the same verb on the same card on the same day
must not collide.

Two alternatives were considered and rejected:

- **A new `origin` column.** A migration and a store-convention four-edit
  (`spec-decision-record.md` names the four sites) for a *metric*. The decision
  record's own posture is that derived beats stored; a prefix on a
  caller-owned opaque string is derived enough.
- **`correlation_id`.** The cascade machinery owns it — `invoke` mints one for
  fan-out parentage — and the router never lets a caller set it. Hijacking it
  would make one column mean two things, which is the failure the modularization
  spec refuses in a different register.

Recorded honestly: this **is** a smuggle. The idempotency key's declared job is
replay identity, and we are also reading it as provenance. It is worth it
because the alternative is a schema change for a number, and it is reversible
— if actions-from-the-feed ever becomes a first-class report rather than an ad
hoc query, the column earns its place then.

### The compiler contract

The compiler is an **external agent at the MCP door**, exactly the
dispatch-probe pattern. It is not in the engine, it holds a grant, and it wears
`waymark_query` / `waymark_get` / `waymark_invoke` like any other leash. The
engine's half of the contract is the `insight` kind's law:

- **It may only create.** `:decider {:not {:field :authored_by …}}` — the four
  eyes wall doing real work: the agent that published an insight is
  structurally incapable of accepting it. Acceptance is a member's, through the
  member's own principal, so grants and state gate the offered action exactly
  as the row's own screen would.
- **Evidence or nothing.** A create guard `cites-what-it-claims` refuses an
  empty `:evidence` vector and refuses an href naming no kind this engine
  serves. Its `:vars` carry the count and the offending href, so the refusal
  sentence names the fix — which, per `spec-decision-record.md`'s second
  thesis, is also the evidence the decision record will keep.
- **An offered action or nothing.** `:offer_kind` / `:offer_id` /
  `:offer_action`, refused at create when the pair is not in the registry, and
  refused when the action's rendered effort is heavier than `"selection"`. This
  is the one place the ≤-selection rule is enforceable at a **door** rather
  than at a projection, because it is the one place a verb is *declared* rather
  than inherited.
- **A hard daily cap.** So the compiler must rank rather than dump. See the
  hazard in the bead note for `.6`: `:pacing {:per :day}` computes the right
  window and prints the wrong sentence.
- **Authorship is visible.** `:by :authored_by` is a principal id, `:raw` by
  the sugar's own rule — a display layer must not dress it up as a person.

`waymark-53u` (standing agent renewal) is what an *automated* cadence needs. A
**manual compilation is a valid v1** and is the epic's own position: a human
runs the probe, the insights land, the feed offers them, the cap holds.

### Where the law is proved

- **`defscenario`, check tier** — the tickler's two sentences (a let-go item
  never returns; a not-now returns later, not tomorrow) and the insight's
  refusals. Check tier needs no `:given` rows and guards reading only
  `#{:now :principal :services.features}`; the evidence guard reads the
  registry, which is not in that set, so the citation scenario defers to the
  conformance tier and should be written expecting that.
- **A conformance pack**, `{:module :feed :obligations […]}`, riding the
  inventory's `:pack` column with `:needs #{[:route :feed]}` — so an engine
  assembled without the module **skips** rather than fails.
- The obligations, named: `:feed/recipe-order` (sections in census order, seam
  present), `:feed/day-stable` (two reads, same seed, same cards),
  `:feed/projection` (a reader without a grant on a kind never sees its card —
  the fourth law, from the wire), `:feed/verbs-are-light` (no entry in any
  card's `actions` has effort heavier than `"selection"`),
  `:feed/archive-pages` (deep paging without duplication within a day).

## The three delegated forks, decided

### (a) Non-tickler resurfacers compute at READ TIME, seeded

**Verdict: read-time seeded queries. No materializing job.**

Every non-tickler population is already a query over rows and logs that exist,
and the framework's own posture on exactly this trade is on the record twice:
`decision/basis` is derived rather than stored because it *"costs no column, no
byte, and no migration, and it is RETROACTIVE"*, and the same paragraph in
`history.clj` names retroactivity as the thing no stored record could offer. A
materialized feed would be a second copy of a derivable fact with a staleness
window in front of it.

Three more reasons, in descending weight:

1. **Materialization buys nothing on the security path.** Every card must be
   projected through the *reading* member's visibility, so a materialized row
   would have to be re-projected at read time anyway — or be stored per member,
   which is N members × M cards of duplicated derivable state.
2. **A daily job is a whole extra failure mode.** A member who joins at noon
   has no feed; a law promoted at 3pm serves the morning's judgment until
   tomorrow; the job is elected, so a lost lease is a household with no feed and
   nothing in the UI that could say why.
3. **The seed already delivers what the job was for.** Stability within a day
   was the requirement, and a hash over (salt, member, date) is stable without a
   row.

**The cost, accepted and bounded.** An expensive population — the archive
interleave over the transition log — pays on every read. The bound is the
posture `history/fold-cap` already takes: scan to a cap and report honestly
whether the cap was reached, *"because truncation announced beats totality
implied"*. If one population outgrows its cap in practice, **that population**
earns a materializing job, and only it; the seam is the population registry, so
swapping one entry for a job-backed one changes no other line. Recorded as a
punt, not pre-built.

### (b) Tickler state lives in a MARKER KIND, not on task rows

**Verdict: a `tickler` kind, declared through `:decision`.**

Five reasons, and the first two are disqualifying rather than merely
persuasive.

1. **`task` is `:push-on-write true`, and every one of its own domain actions
   pushes.** `mirror/push-after-write!` exports the whole document and calls the
   authority on any action in `(:actions rdef)` that is not a sync door; a
   failed push lands the row `mark_conflicted`. So a *"not now"* tapped in the
   feed would be an HTTP call to Google Tasks or Home Assistant, and Google
   being down would leave a household task **conflicted** because someone
   deferred it. `prioritize` escapes this only because the workqueue confluence
   adapter carries an explicit `:noop` push plan for it — meaning a tickler
   field on `task` is a **workqueue10 adapter change to buy a waymark10
   feature**, which is backwards.
2. **`check-domain-actions!` will not let the verdict be spelled.** A mirror's
   own actions must move between `#{:fresh :stale :unreachable}` — *"a mirror's
   machine IS the sync machine"* — and a `conflicted` row *"takes no local
   writes until a person decides"*. The tickler has to work on a row whose
   authority is sulking; that is precisely the row most likely to have been
   dropped.
3. **The tickler is not task-only.** Abandoned media, dropped chore runs,
   letters left waiting — a field on `task` cannot hold a household-wide
   someday/maybe list. A marker names `{kind, id}` and covers all of them with
   one population and one law.
4. **The epic already said so.** *"The tickler answer is a tiny verdict — the
   permission-slip/`:decision` pattern's smallest cousin."* A `:decision` kind
   is a row by construction, and the sugar hands over states, verdict actions,
   `:own-surface`, `:filterable`, `:default-filters {:state "offered"}`,
   `:sortable "-created_at"` and `:nav :system` for free. Choosing row-fields
   means hand-writing every one of those onto a kind that cannot take them.
5. **`:document :partial` is a shield, not a licence.** It is true that
   `task`'s `:priority` proves a hub-local field survives the pull. It does not
   prove a hub-local *action* survives the push, and the push is where this
   breaks.

**The cost, accepted.** A second row per tickled item, and a marker can outlive
its subject (the authority deleted the task; `:on-gone` dropped it; the row is
gone). Two mitigations, both cheap: the marker denormalizes the subject's
summary at birth — `:asks` is a required string field anyway, so this costs
nothing extra — and the ticklers population **retires a marker at offer time**
when its subject has moved on (no longer `dropped`, or unreadable), rather than
running a sweeper for it. A tickler that quietly withdrew itself on a clock
would be worse than one that stayed on the fridge; a tickler that notices at
the moment it would have spoken is honest.

### (c) The v1 UI is a DEDICATED SCREEN in the ui module

**Verdict: a dedicated feed screen — a `render()` fork by document kind, the
dashboard's precedent — not a `saved_view` feed-mode probe.**

The probe cannot express the thing:

1. **`saved_view` has exactly one `:target`,** and
   `composes-declared-primitives` judges `:where`, `:card`, `:right` and
   `:left` against **that one kind's** declaration. A mixed card census has no
   single target to name, so a feed-mode saved view would have to lie about
   `:target` in order to exist.
2. **`134-feed.js` is single-kind by construction** — `doc.kind`,
   `watchScope({kind})`, `gridColumns` and `hints` from one declaration.
3. **The seam has no row,** and `appendItems` skips any item without a `self`.
   A structural element cannot ride a collection's item list.
4. **`checks.clj:744` already refuses a gesture on a feed view.** A saved-view
   probe would be barred from the swipe vocabulary anyway.

But the harvest is what makes this cheap, and it is the real reason the verdict
is not "build a page from scratch": the feed document is deliberately shaped so
that `134-feed.js`'s mechanics port almost verbatim — per-item panels built
from the item's own envelope through `actionButton` and `fieldCell`, the
`IntersectionObserver` on `links.next`, the panel snapping, the honest terminal
panel, `refreshPanel` after a verb lands (*"the fresh envelope answers — the
feed never guesses an outcome"*). The new file is `135-feed-screen.js` beside
the old one, forked on document kind the way `136-dashboard.js` forks. Two
substantive differences: it keys on `card_id`, and it renders a seam panel.

**And the gestures are buttons.** The epic's "one tap" and the framework's own
`checks.clj:744` agree: a sequential read takes no swipe. Card verbs are
chips — short label, ≤ selection, and a way back — which is `gesture-duties`'
three duties applied to a tap. See the note on `.7`.

## Landing order

```
.2  ──▶  .3  ──▶  .4  ──▶  .6  ──▶  .7
                    └──▶ .5 ──────────┘
```

**`.2` first, alone.** It creates everything the others are shaped by: the
module entry in `modules.clj`, the route, the recipe and its assembly checks,
the seed, the cursor, the card envelope, the population registry, and
`packs/feed` in `test/packs.clj`. Those last two are the file collisions named
in this bead's brief, and sequencing `.2` fully first is what makes them
disappear.

**`.3` second.** The ≤-selection partition lives in the card builder `.2`
writes; doing it inside `.2` makes `.2` two beads, doing it after `.4`/`.5`
means three populations get built against a card shape that then moves. `.3`
also plants the `Idempotency-Key` origin convention, which `.7` must use from
its first line.

**`.4` before `.6`.** Both lean on `:decision`, and `.4` is the first
declaration anywhere that needs a verdict to **return** to `:offered`
(`not_now`). `desugar-decision` permits it — *"at least one verdict must leave
`offered`"*, not all — but nothing has exercised it, and if `verdict-action`
needs any give there, `.6` should inherit the fix rather than rediscover it.
That is mechanism sharing doing real work, not a guess.

**`.5` any time after `.3`.** Two stateless populations, no new kind, no
sugar.

**`.7` last.** It depends on `.2` by declaration and on every other bead in
practice — it renders every population — and it is the largest bead and the
half that decides whether the feed is reached for.

### What cannot run concurrently, and why

| pair | why not |
|---|---|
| `.2` ∥ `.3` | `.3` rewrites the card builder `.2` is still writing. Same function, same file. |
| `.2` ∥ `.5` | `.5` registers populations in the registry var `.2` creates, and appends to `packs/feed`. |
| `.3` ∥ `.5` | Both append obligations to `packs/feed`; a merge conflict, not a design conflict, but a needless one. |
| `.4` ∥ `.6` | Shared `:decision` sugar, and `.4` is the first non-terminal verdict. A sugar fix discovered twice is a sugar fix merged badly. |
| `.7` ∥ anything | It renders all of them. A card shape moving underneath it is pure rework. |

**`.4` ∥ `.5` is the one honest concurrency.** They touch no common function —
`.4` adds a kind and one population, `.5` adds two — but they share the
population registry map and the pack vector. Two agents, one careful merge. If
only one pair may run at once, run this one; if serial is affordable, `.4` then
`.5`.

## Recorded punts

- **GTD contexts.** The epic's own v2 parking lot. The HA lists
  (`todo.phone_calls`, `todo.computer`) already encode them and a context-aware
  do-now section waits for the spine to exist. Not designed here on purpose.
- **Automated compiler cadence.** `waymark-53u`. A manual probe is the v1, and
  the daily cap holds either way.
- **A materializing job for one expensive population.** Named in fork (a). The
  population registry is the seam; nothing else moves when one entry is swapped.
- **No seen/unseen state, ever.** `spec-addressed-notice.md` already punted
  read receipts — *"a notice is a delivery, not a row"* — and the feed inherits
  that. The moment a card has per-member seen state, the recipe stops being
  static data and the third law is gone.
- **Tier 3 data as-of is still open** (`spec-time-travel.md`), which bounds what
  a fuel card may say about a row's July shape. A fuel card says what happened —
  the transition and its stored `summary`, which `invoke` rendered on the day —
  never what the row looked like. `history.clj`'s first departure, inherited.
- **A transition's `inputs` are not served.** `spec-time-travel.md`'s clause,
  binding on every feed card built from the log: `inputs` has no field
  projection of its own, and growing one here would be a second visibility
  surface with its own bugs.
- **`:pacing`'s explain sentence is wrong for `:per :day`.** `resource.clj:970`
  reads *"Asks are paced to {limit} an hour"* regardless of the declared window.
  Fixing it in the sugar risks moving `approval_request`'s fingerprint, which
  `decision-sugar-test` pins by design. See the `.6` note.
- **The feed cannot advertise itself on `.well-known`.** The same wall
  `spec-mcp-surface.md` hit: *"a module has no way to contribute a line to a
  core document and the contribution table is closed at four on purpose."* The
  feed's door is found by the UI knowing it exists, exactly as the MCP door is.
- **Media dedup across authorities.** `media.clj`'s own healable punt — one
  work under two catalogs shows as two archive cards. Visible, not resolved.
- **Pacing rides no coordinator.** Inherited from `spec-decision-kind.md`: the
  insight cap is per-process and unshared. One compiler, one process, today.
- **The insight's byline is a principal id.** `:raw` by the sugar's rule. A
  friendlier agent byline is a display concern and a separate bead.

## Effort

**Large, and lopsided.** `.2` is the only bead with genuine design left in it
and it is a day; `.3` and `.5` are hours each once `.2` exists; `.4` and `.6`
are each one `:decision` declaration, two or three scenarios and a create guard.
`.7` is half the epic on its own and every hour of it is UI, which this repo
verifies by hand (`waymark10-design.md` §10). The risk is not the code — it is
that the surface ships and nobody opens it, which is why the actions-from-feed
number is designed in from the first bead rather than added when someone asks.

## Built — `.2`, the surface (2026-08-24, waymark-iqa.2)

The mixer, the seam and the grant-projected card landed whole and alone, as the
landing order asks. `waymark10/src/waymark10/server/feed.clj` is the recipe, the
seed, the cursor, the population registry and the mixer;
`waymark10/src/waymark10/server/routes/feed.clj` mounts `GET /api/-/feed` in the
`:static` bucket and reads the recipe once at the build site;
`waymark10.modules` grew the two-column `:feed` entry (routes and a pack, no
kinds, no hooks) and `waymark10.test.packs` grew `packs/feed`.
`waymark10/test/waymark10/feed_test.clj` holds what a conformance driver with
one world cannot arrange: two kinds and two principals built to disagree, the
recipe handed over as an opt, and the assembly checks read one at a time.

One line of core moved, and it is the sentence `modules.clj` already writes:
`:feed` joins `engine/engine`'s `select-keys` beside `:curtain-ttl-ms` and
`:webhooks-poll-ms`. That list IS *'one engine, one opts map'* — an opt not
named there does not reach the engine at all — so a module knob has to be
admitted to it, and admitting one is not the fifth contribution column the
table refuses.

The document is a `feed`, with `kind: "feed"` — one key beyond the spec's
sketch, and a load-bearing one: fork (c)'s verdict is *a `render()` fork by
document kind*, and a document that cannot name itself cannot be forked on.

### What shipped, and what did not

**Five populations, all v1-cheap.** `next_actions` (the open rows of the
front-door kinds), `asks` (offered `approval_request`s that are not the
reader's own), `letters` (the reader's unopened mail, when the engine holds a
`letter` kind — the `welcome-home` precedent), `conflicts` (mirrored rows whose
authority and household disagree, read off `:mirror` on the rdef rather than
through the mirror module), and `events` (the transition log as the bottomless
archive). The recipe that names them is therefore SHORTER than the illustrative
one above, and deliberately: check (1) refuses a recipe naming a population the
registry does not hold, so `:ticklers` (.4), the fuel populations (.5) and
`:insights` (.6) each add their registry entry and their recipe line together.
That is fork (a)'s seam doing exactly what it promised.

**`:nav` is the do-now question's answer.** waymark declares no vocabulary for a
due date, and inventing one to rank by would be the scoring function the third
law forbids. `:nav` is the only trait a declaration carries about who a kind is
FOR — every framework kind takes `:system`, every application kind takes
`:primary` by default — and it is not fingerprinted, so reading it costs no
declaration a hash. do-now is the `:primary` kinds' open rows; the seed picks;
a card with no available action is dropped, because a next action with no verb
is a row on a list.

**A row an earlier section claims is that section's for the day.** The mixer's
`seen` set is built from every candidate a population NAMED, not from the cards
that fit, and a cursor page re-runs the sections above the seam for their
candidates alone (`:render? false`, one query each, no envelopes). Without that,
page four would re-serve as a memory what page one showed as a next action.
Which leaves the archive exactly the rows the household FINISHED — the honest
v1 of *history as fuel*, and the shape `.5` will fill in properly.

**The card is `envelope-summary` minus two keys.** `waymark`, because a card is
an element of a document rather than a document, and `unavailable`, because a
card has no room for the narration of doors that are shut. Neither is a
projection: what a reader HOLDS is untouched, which is the half `heavier` exists
to keep honest.

**No seventh MCP tool**, per § 'The compiler contract': an agent reaching for
the household wears `waymark_query` / `waymark_get` / `waymark_invoke` like any
other leash, and the six-tool pin in `packs/mcp` did not move.

### The obligations

`packs/feed` carries `:feed/routes-mounted`, `:feed/recipe-order` (the census
read off the wire, plus five bad recipes each refused by name at assembly),
`:feed/day-stable` (two reads, one seed, one order), `:feed/projection` (the
two-principal proof: a leash over one kind is minted, accepted and worn, and no
card of any other kind survives) and `:feed/cursor-rolls` (a cursor from another
day is 409, a token this engine never minted is 422, and a cursor page carries
archive cards only — no seam, no do-now). `:feed/verbs-are-light` belongs to
`.3` and `:feed/archive-pages` to `.5`, each with the bead that lands the
mechanism it judges.

### Recorded here, for `.3` and after

- **`heavier` is `[]` on every card and the split site is `feed/card`.** Project
  first is already the shape: the partition has only the surviving `actions` to
  read, so it cannot name a door the grant concealed.
- **The `Idempotency-Key` origin convention is unplanted.** `.3` owns it; no
  card carries a prefix yet and no query counts one.
- **`row-scan-cap` 100 and `log-scan-cap` 500** bound the read-time cost, and
  the document's `notes` say when the archive fold reached its cap —
  `history/fold-cap`'s posture, at a smaller number.
- **waymark-j82 is real and it is not this bead's.** Loading any code before the
  application's declarations moves `ingredient`'s fingerprint hash, and nine
  other app kinds' hashes are not even stable run to run: `fingerprint/
  callable-hash` falls back to `pr-str` on a bare fn, which prints a
  compiler-assigned class id and an identity hashcode. Proved by requiring four
  unrelated `clojure.*` namespaces with the feed reverted — same move, no feed.
  The twenty kinds whose hashes ARE deterministic did not move by one byte.

## Built — `.3`, the card's verbs (2026-08-24, waymark-iqa.3)

The ≤-selection rule landed as what `.1` corrected it into: a **read-time
projection**, in one function, over a map that has already been through the
grant. `waymark10.server.feed/split-verbs` is the whole of it, and its
argument list is the design — it takes the card **body** and the row's
`self`, and it never sees the rdef, the machine or the visibility. `card`
calls it third, after `:row?` and after `envelope-summary`, so the partition
can only ever divide doors the reader already holds.

Nothing was added at declaration time and nothing should be. A kind's
composition actions are correct on the kind's own screen; a check that refused
them would be refusing law that is right. The one declaration-time instance of
the rule remains `insight`'s `:offer_action`, and it is still `.6`'s.

```clojure
(let [actions (get body "actions")
      heavy? (fn [[_ entry]]
               (demand/heavier? (get entry "effort") card-ceiling))]
  (assoc body
         "actions" (into {} (remove heavy?) actions)
         "heavier" (into [] (comp (filter heavy?) (map #(heavier-entry self %)))
                         (sort-by key actions))))
```

A card with both halves populated, off the wire:

```json
{ "card_id": "do_now/pr_task/714f17e4-…",
  "section": "do_now", "population": "next_actions",
  "kind": "pr_task", "self": "/api/pr_tasks/714f17e4-…",
  "state": "open", "summary": "Call the dentist · Open",
  "display": {"title": "Call the dentist"},
  "fields": {"title": "Call the dentist"},
  "actions": {
    "complete":   {"effort": "assent",    "method": "POST",
                   "href": "/api/pr_tasks/714f17e4-…/-/complete",
                   "effect": {"to": "done", "terminal": true}},
    "prioritize": {"effort": "selection", "method": "POST",
                   "href": "/api/pr_tasks/714f17e4-…/-/prioritize",
                   "display": {"label": "Prioritize"},
                   "input": {"properties": {"rank": {"enum": ["high","low"]}}}}},
  "heavier": [{"name": "annotate", "effort": "composition",
               "label": "Add a note",
               "href": "/#/api/pr_tasks/714f17e4-…"}],
  "links": {}, "meta": {…} }
```

**The `href` is the row's SCREEN, and that is a deliberate departure from this
spec's own illustrative JSON**, which sketched the API `self`. A card already
carries `self`; naming the same address twice says nothing, and worse, it
invites a client to read a `heavier` entry as a door it could POST to. A
heavier entry is a place to **go**, not a verb to fire. The screen is `"/#"`
prepended to the row's href, which is not an invention: the agent door's
`:handoff` template already hands a human
`/#/api/approval_requests/{ask-id}` with the note *"it opens the ask
directly"*, and `workqueue10.sources.waymark/with-origin` derives
`source_ui_href` as `(str base "/#" self)` for the same reason — *the URL hash
IS the resource href*. `feed/screen-of` is the one spelling, public because the
pack judges against it.

**Labels come from the action's own `:display :label`, falling back to the
humanized action name** — `render/no-admissible-entry`'s spelling exactly, so a
card and a refusal call one door one thing.

**do-now's filter now reads the LIGHT half.** A row whose only surviving verb
is a composition drops out of do-now entirely, `heavier` and all. That is the
section's bargain rather than an oversight: do-now is the one physical next
action under the thumb, and a card there that could only send you somewhere to
type is a link wearing a verb's clothes. The row keeps its screen, its
collection and its place in the archive. Recorded here because it is the one
behaviour change `.7` will see that is not visible in the card shape.

### The origin convention, as spelled

```
Idempotency-Key: feed/<day>/<card_id percent-encoded>/<nonce>
Idempotency-Key: feed/2026-08-24/do_now%2Ftask%2F01HZ…/9f3c1a
```

Four slash-separated segments, minted by `feed/origin-key` and read back by
`feed/origin-of` — which answers `{:day :card-id :section :kind :id :nonce}`
or **nil**, never a guess. The card id is percent-encoded because a `card_id`
carries slashes of its own and a metric that could not tell them from the
key's would be a metric that guessed. `.7` sends exactly this.

Nothing was built server-side and nothing needed to be, which was `.1`'s claim
and is now proved: `invoke/finish!` already stamps a present `:idempotency-key`
into the transition row whether or not the action is idempotent, and
`router/invoke-opts` already reads it off the header. The proof is
`a-verb-invoked-from-a-card-lands-its-origin-on-the-transition` in
`feed_test.clj` — a card's own `finish` href invoked under the key, and the key
read back off `waymark10_transitions` — and the conformance obligation does the
same thing against whatever an application declared, reporting `:covered`.

`feed/actions-from-feed` is the read: `{:day :total :by-section :by-kind
:by-action :scanned :reached-cap}`. **The trade, recorded.**
`store/transitions` takes `{:kind :resource-id :since}` and no `LIKE`, so this
is a bounded newest-first window (`log-scan-cap`, 500) scanned in memory rather
than a prefix predicate pushed into Postgres. **No store protocol surface was
added** — the alternative is a new argument on a method four stores implement,
bought for an ad-hoc number, and the epic's posture is that this metric stays
derived until it earns more. The bound is announced the way `history/fold-cap`
announces its own, and `:since` is already there for a caller walking further
back a page at a time.

### The obligation

`:feed/verbs-are-light` in `packs/feed`, and it is deliberately the **last**
feed obligation because it is the only one that WRITES. Three claims, each
proved to fail when it should:

1. **Light.** No entry in any card's `actions` is heavier than `"selection"`,
   and every `heavier` entry is well-formed — it names an action, its effort
   really is heavier (a verb that fits belongs where it can be tapped), it
   carries a label, its href is not an action door (`/-/`), and it equals
   `feed/screen-of` on the card's own `self`.
2. **Concealed appears in NEITHER list.** `mint-grant!` mints `:actions []` —
   read-only sight of one kind — so the audience holds the kind and no verb of
   it. Every card it reads must carry an empty `actions` **and** an empty
   `heavier`. This is the exact place a `heavier` built from the declaration
   would reappear as a link, and concealment would have become narration; it
   was verified by building one that way and watching the obligation catch it.
3. **The origin rides the audit trail.** One assent-effort card verb is invoked
   for real under `feed/origin-key`, and the row's own transition log is read
   for the key, with the parsed section/kind/id checked against the card.

`:feed/archive-pages` is still `.5`'s, and remains named rather than pending.

### Recorded here, for `.4`, `.5` and `.7`

- **`feed/card-ceiling` is `"selection"`, once.** `demand/heavier?` is asked
  here and in `waymark10.usability`; a second spelling of the ceiling would be
  a second opinion about what fits under a thumb.
- **A new population inherits the partition for free.** `card` is still the one
  place a row becomes wire, and `split-verbs` is inside it. `.4` and `.5` add
  a registry entry and a recipe line and get the rule.
- **`.7` must send `feed/origin-key`'s spelling** and must render `heavier`
  as a LINK, never as a button: the href is a screen, and a client that POSTed
  to it would get the UI's index page.
- **No hash moved.** `feed.clj` is engine-side and carries no declarations;
  waymark-j82's fingerprint fix was not touched and the twenty deterministic
  kinds are byte-identical.

## Built — `.4`, the tickler (2026-08-24, waymark-iqa.4)

Fork (b) landed as written: `workqueue10/src/workqueue10/resources/tickler.clj`
is a `:decision` kind, a marker naming `{subject_kind, subject_id}` anywhere in
the house, with three one-tap answers and a date it comes back on. Nothing in
it writes another kind's row, which is the whole of the fork's first reason —
a *"not now"* must never call Google Tasks, and here it structurally cannot.

```clojure
:verdicts
[{:name :not_now :to :offered            ; ← the non-terminal one
  :label "Not now" :order 1
  :handler push-the-offer-out
  :safety {:idempotent false :reversible false :confirm false
           :one-way "Each 'not now' pushes the next offer further out…"}}
 {:name :let_it_go :to :let_go   :label "Let it go"   :order 2 …}
 {:name :take_it_back :to :taken :label "Take it back" :order 3 …}]
```

`desugar-decision` took the non-terminal verdict **without a change**, exactly
as `.1` predicted it would: `:states` distinct-conses the open state, so
`[:offered :let_go :taken]`; `:terminal` removes it, so `#{:let_go :taken}`;
`check-one-way` exempts a self-loop by its own rule (*"re-doing is its own
undo"*). The one line that needed thought is `:safety :idempotent false` — two
taps ARE two not-nows and push the date twice, so the door asks for an
`Idempotency-Key` and a double-tap replays instead of compounding. The feed's
own `origin-key` is that key, so a card verb pays nothing for the honesty.

**The backoff is data and a pure function**, and the schedule is one vector:

```clojure
(def backoff-days [7 21 60 180])          ; a week, three weeks, two months, half a year
(defn days-out  ^long [said] …)           ; 1-based, the last step repeats
(defn next-offer ^Instant [now said] (.plusSeconds now (* 86400 (days-out said))))
```

`now` is HANDED in from the engine's `:now` and no clock is read anywhere in
the file, so the same inputs answer the same instant in a test, in a scenario
and in the house. The last step repeats rather than running out: half a year
forever, because the only honest way to never see something again is to LET IT
GO, which is a verdict and not a slow fade. The handler bumps `offer_count`,
moves `next_offer_at` and stamps `answered_by` — the household record the epic
asked for, so *"I have said not-now to this twice"* is a row and not a
memory.

### How a tickler is born, and how it retires

**Born at its own create door, by whoever set the thing aside** — a person, or
an agent at the MCP door sweeping the dropped pile, which is the dispatch-probe
pattern this spec already blesses for the compiler. The spec was silent here
and this is the cheapest honest mechanism: the feed is a GET and a read that
minted rows would be a read with a side effect, `:on-gone` takes a `:set` map
and not a hook, and the `:decision` sugar refuses an `:on-create` beside it
("one home per hook"), so a birth-time sweep had nowhere to live that was not a
job. **`next_offer_at` is optional and unset means NOW** — a tickler set aside
with no date is already on the fridge — which is what lets the create door stay
four fields wide. An automatic sweep of the dropped pile is filed, not built.

**Retired at offer time, by the population, with no sweeper and no write.**
`feed/set-aside?` is the one spelling and it is public because the pack judges
against it: a subject that is gone, terminal, or carries the mirror
convention's `status: "done"` gets no card. A marker may outlive its subject —
the spec accepted that cost and this is where it is paid. The deliberate
consequence, recorded: a dropped task somebody REOPENED and left open KEEPS its
tickler, because *still not done* is exactly what a someday/maybe list is for.
The household's way to say otherwise is `take_it_back`, which is a person
answering rather than an engine inferring.

**And that is all `take_it_back` does.** It retires the marker and writes
nothing to the origin row. Two reasons, both structural: a cascade from a
marker into a `:push-on-write` mirror is precisely the coupling fork (b)
forbade, and there is no generic un-drop verb a household-wide marker could
name. The way back to the work is a LINK — the marker's declared `:links
{:rel "subject"}` over an optional `subject_href`, `/#` + the row's own
address, `screen-of`'s spelling by hand because a declared link cannot ask the
engine for a plural. A tickler that claimed to reopen a task while the task
stayed dropped would be a card that lies, and `heavier` exists in this document
precisely so cards do not.

### The sugar's one give: `:decider :anyone`

A tickler has no eligibility dimension. The person who set an item aside is
exactly the person who should get to say "not now" again, so a four-eyes wall
would be backwards and a role wall would be invented — and `decider-guards`
refused `{}` and had no other spelling, which made `:decider` effectively
required. So the sugar grew one keyword: **`:decider :anyone` is a wall's
absence, said out loud.** Silence still refuses, because an omitted `:decider`
is a typo far more often than it is a policy.

The change is a two-line fork in front of the old body (`decider-walls`), and
**no hash moved**, proved rather than argued: `approval_request` is
`01ca868b…` before and after (its pinned literal in `decision-sugar-test`), and
`permission_slip` is `d4af2fcf…` before and after (computed against `HEAD`'s
`resource.clj` and against this one). That is the legitimate shape of a sugar
change — it can only move a kind that uses the changed feature, and no kind
used this one before today.

**`.6` inherits two findings, and the second is the load-bearing one.**

1. `:decider :anyone` is there if an insight ever wants it. It will not: the
   evidence wall (`:decider {:not {:field :authored_by}}`) is the four-eyes rule
   doing real work, and it is the one thing that keeps a compiler from
   accepting its own proposal.
2. **A verdict with a `:note` falls off the card.** The sugar's note input is
   `[:maybe [:string {:max 240}]]`, which `demand/field-class` reads as
   `recall` — heavier than `card-ceiling` — so `split-verbs` moves that verdict
   out of `actions` and into `heavier`, and the one-tap assent becomes a link.
   The tickler therefore takes no notes on any verdict, deliberately.
   `permission_slip` spells `:note :answer` on both of its verdicts and is
   right to; it is not a feed card. Any `.6` verdict meant to be tapped FROM
   the feed must be note-free, and if an insight wants both a note and a card
   it needs two doors, not one.

### The population, the recipe line, and the obligation

`feed/populations` gained `:ticklers` and `feed/default-recipe` gained
`{:section :decide :population :ticklers :take 2}` in the same commit, which is
check (1)'s seam working exactly as fork (a) promised — no other line moved.
The population is the `letters` precedent (a core reader naming an OPTIONAL
application kind, answering `[]` when the engine holds none), filters `offered`
markers whose `next_offer_at` has passed or was never set, and drops the ones
whose subject has moved on. The backoff needs no query of its own on the read
side: a pushed-out marker is simply not a candidate, and nothing sweeps it.

The bound is the read-time posture's: at most `row-scan-cap` markers are read,
and only the DUE ones cost a subject read.

**Where the law is proved, both tiers.** Three `defscenario`s in the
declaration, all CHECK tier because `:decider :anyone` leaves the verdict doors
with no guards at all — *a let-go item never returns* and *a taken-back one
stops asking* are the reserved `:out-of-state` denier with no guard behind it,
and *anyone in the house answers a tickler* is the positive statement of the
sugar's give. `make check-queue` reads 31 kinds, 11 warnings (unmoved), 14
scenarios.

The backoff is not a scenario and could not be — `scenario.clj` is explicit
that *"no scenario ever writes"* — so it is judged twice instead:
`workqueue10/test/workqueue10/tickler_test.clj` calls the pure function and the
handler with no database at all, and **`:feed/ticklers` in `packs/feed`** proves
the same law over the wire against a real row: a marker is created over the
feed's own first card, appears in `decide`, offers all three verbs, backs off on
`not_now` (`offer_count` 1, `next_offer_at` past TOMORROW), leaves the feed,
retires on `let_it_go`, and answers 409 to a second `not_now`. A second marker
naming a row this engine does not serve is created beside it and never cards,
which is retire-at-offer-time from the other side. It judges the LAW and never
the schedule: how far a house pushes its first not-now is the house's to
declare, that it is further out than tomorrow is the epic's.

It is the pack's new LAST obligation, below `:feed/verbs-are-light`, and
deliberately rather than by append: it is the only obligation that MINTS rows,
and a minted marker is a card the counting obligations above would have to
count.

## Built — `.5`, fuel and the archive (2026-08-24, waymark-iqa.5)

Fork (a) landed as decided and the whole bead is what that verdict promised:
**four populations, no job, no hook, no column, no store method.**
`waymark10.server.feed` grew `cleared`, `streaks`, `finished` and `memories`;
`default-recipe` grew three fuel lines and swapped its archive entry; nothing
else in the engine moved except one `defn-` that became a `defn`
(`seasons/classify`, public for its second consumer exactly as
`demand/heavier?` is).

```clojure
{:section :fuel    :population :cleared  :take 1}
{:section :fuel    :population :streaks  :take 1}
{:section :fuel    :population :finished :take 2}
{:seam true :sentence "That's the house, caught up."}
{:section :archive :population :memories :take 6 :bottomless true}
```

### A fuel card is a ROW card with a sentence

The spec's own card law says a card names its origin row and never invents an
identity — *`section/kind/id`, or the literal `"seam"`* — and a cleared queue
is not a row. The resolution is that **it is**: the card is the row that
emptied the list, and the thing the row cannot say about itself rides a
`sentence`, which is the seam's own key. The seam is already the one element of
this document that is prose rather than a projection; a second one costs the
client nothing and the card shape nothing.

```json
{ "card_id": "fuel/fd_errand/303be824-…",
  "section": "fuel", "population": "cleared",
  "kind": "fd_errand", "self": "/api/fd_errands/303be824-…",
  "state": "done", "summary": "Fold the laundry · Done",
  "sentence": "Nothing is left in fd_errands — 3 finished in the last 12 weeks, and this was the last of them.",
  "display": {"title": "Fold the laundry"},
  "fields": {"title": "Fold the laundry"},
  "actions": {}, "heavier": [],
  "at": "2026-08-24T19:42:20.975473659Z",
  "links": {}, "meta": {…} }
```

**`actions` is empty and that is correct.** A done row has no verbs left, so
`split-verbs` partitions two empties and the card is something to read rather
than something to tap. It is the one section of the census where that is the
point: do-now drops a card with no verb, and fuel is made of them.

### The aggregate half is gated differently from the row half

`cleared` and `streaks` say a number about a KIND, so they speak only about
kinds this reader sees WHOLE — `seasons/whole-kind-sight?`, the one definition,
the same seam the seasons door projects through, failing toward concealment.
`finished` claims nothing about rows the reader cannot see, so it needs
`card`'s `:row?` and nothing more and reads the wider kind list. That line —
*a count over a partly-seen kind is a disclosure with a number on it* — is the
fourth law read carefully rather than a new rule.

The aggregate itself is `seasons/report`'s minus the aging read the feed does
not need: `store/transition-stats` bucketed weekly at the store,
`seasons/classify` deciding from the DECLARATION which action names close
something, `include-system? false` dropping the mirror's sync beat. **No store
protocol method was added and none is admissible** — the protocol is closed,
four stores implement it, and a bespoke aggregate for a fuel card would be a
core change wearing a module's clothes.

- **`cleared`** — three cheap facts have to line up: the kind CAN end (it
  declares terminal states), nothing of it is open right now (one `LIMIT 1`
  over the open states), and something of it actually finished inside the
  twelve-week window, so a kind that has been empty since it was declared is
  not a daily achievement. A mirror kind never clears, and that is right rather
  than a gap: its machine is the sync machine, so it has no state in which its
  work is over.
- **`streaks`** — consecutive weeks with at least one completion, floor of two
  because one week is not a run. An empty CURRENT week is skipped rather than
  counted against the run: on a Monday morning every streak in the house would
  otherwise read as broken, which is a calendar artefact and not a fact about
  anybody. The sentence names the week it started, so the number is checkable
  against the household's own memory instead of being a badge.
- **`finished`** — the last terminal row of each kind, inside
  `fuel-window-days` (7), through the ORDINARY collection query grammar:
  `collections/parse-query` over `state=` (which every kind's grammar speaks)
  and then `search-rows` over the conds it compiled — the same path `law_sweep`
  hands a caller's filters to, never a bespoke SQL query. `:defaults? false`,
  because a declared `:default-filters` is a collection's opening view and the
  fuel section is asking a different question of the same rows.

### There is still no scoring function, and `:order-by` is not one

`finished` asks for the newest terminal row and `events` asks for the newest
transitions, so recency chooses the CANDIDATE SET — a bounded window over rows
that already exist, which is what `rows-of`'s `:newest-first` has always been.
What orders the cards inside a population is `hash(seed ‖ card_id)` and nothing
else, exactly as it was before this bead. The distinction is worth stating
because it is the one a ranking model would blur: a window says *these are the
rows worth considering today*, a score says *this one is better than that one*,
and only the second is the thing the third law forbids. Nothing here ranks two
cards against each other by any property of either.

### One candidate per kind, and that is a load-bearing restraint

The mixer's claim is TOTAL: every candidate a population NAMES is that
population's for the day, shown or not, which is what keeps a do-now row out of
the archive on page four. So a fuel population that named a hundred finished
rows in order to show two would have barred ninety-eight of them from the
archive to do it. Each fuel population therefore names **at most one candidate
per kind**. Fuel is the last thing the house finished; everything before it is
a memory, and the archive is where memories live.

`fuel-window-days` is the same line drawn in time: inside the window a finished
row is *what you got done*, outside it the archive has it.

### The archive: `:memories`, and the `:events` reconciliation (waymark-iqa.8)

**Verdict: `:memories` replaces `:events` in the recipe and READS it.** Check
(3) admits exactly one bottomless entry, so the reconciliation could not have
been *both entries, one under the other* — but nothing about the interleave
wanted to be two entries. It is one population with two ways of remembering:

1. **`anniversaries`** — what the house was doing a year ago this week, through
   `history/collection-as-of` at the anniversary instant, keeping the items
   whose last move before it falls inside that week. Not *rows that existed a
   year ago* (which is most of them) but *rows somebody was working on a year
   ago this week*, which is the only version that reads like a memory. 52 weeks
   rather than one calendar year, so the weekday lines up and
   `store/utc-week-start` picks the week out whole.
2. **`events`** — `.2`'s stand-in, unchanged and unshrunk: every row that has
   moved, newest move first, one card per row. It stays in the registry on its
   own account too, because a household that wants a plain log of what moved
   should be able to say so in a recipe.

`anniversaries` first and `events` behind it, `dedupe-by` across both: a row
that carries a story from a year ago keeps the story, and the order is what
decides a tie rather than a ranking.

```json
{ "card_id": "archive/fd_errand/88dfe943-…",
  "section": "archive", "population": "memories",
  "kind": "fd_errand", "self": "/api/fd_errands/88dfe943-…",
  "state": "done", "summary": "Repaint the porch · Done",
  "sentence": "A year ago this week: Repaint the porch · Done",
  "at": "2025-08-25T19:42:21.680621239Z",
  "actions": {}, "heavier": [], "links": {}, "meta": {…} }
```

**The sentence is the transition's own stored summary** — what `invoke`
rendered on the day — and never a re-render of today's row against yesterday's
law. That is the first of the two inherited departures, and the second is
inherited by not existing: a transition's `inputs` are not read here and have
no field projection of their own. The card BODY is the row as of now, which is
the departure `card` already documents and the reason following a card's own
href stays correct.

**One query gates the expensive half.** Each kind's fold costs up to
`history/fold-cap` transitions, so the oldest transition in the log is read
first — one row, ascending, `LIMIT 1`. A house younger than the window has no
anniversary of any kind and pays for exactly that one read. Any fold reporting
`complete` false raises the document's cap note, `history/fold-cap`'s posture
inherited whole.

### The cursor now counts CANDIDATES, not cards

This is the bug `:feed/archive-pages` was always going to find, and it was
real. `entry-cards` advanced the offset by the cards it emitted; a candidate
that renders no card — a row retired between the population's scan and the
read, **or one this reader's grant conceals** — is walked but not shown, so the
next page started before the end of this one and re-served its tail. A
grant-scoped reader paging an archive of mixed kinds hits it on page two.

`entry-cards` now answers `:consumed` beside `:cards`: the index of the last
card on the page, plus one — or the whole remaining ordering when the page ran
short. `document` walks the offset by that. Deep paging is unchanged for an
unscoped reader with no vanished rows, which is why `.2`'s hand-run smoke test
did not see it.

### The obligation

`:feed/archive-pages` sits between `:feed/cursor-rolls` and the two writing
obligations — the counting obligations still read above `:feed/verbs-are-light`
and `:feed/ticklers`, exactly as `.3` and `.4` arranged. It walks up to eight
pages and claims four things:

1. **No `card_id` repeats, however deep the walk** — the claim the offset fix
   exists for.
2. **The walk is deterministic**: the same cursor, followed twice, answers the
   same cards. If it ever does not, what failed is the seed, and the archive
   was a live scan re-rolled per request.
3. **The tail is honest**: a walk that runs out drops `links.next`.
4. **The fuel section is day-stable**: two reads, identical `card_id`s.
   `:feed/day-stable` asserts it for the document whole; this asserts it where
   the aggregate populations live, because a `cleared` card is a fold over the
   log and a fold that drifted would drift here first.

It reports `:covered`, because an engine whose archive fits on one page has
proved nothing about depth and should say so rather than pass quietly.

`waymark10/test/waymark10/feed_test.clj` holds the four things a conformance
driver cannot arrange, because all four are TIME: a queue driven to zero, four
weekly finishes in a row, a transition backdated 52 weeks, and a two-principal
deep walk where the concealed candidates are what move the offset. The twin's
log is rewritten in place for the first three — both stores stamp `at` and
`updated_at` themselves rather than taking the engine's clock — and that helper
is the one thing in the file that reaches past a door.

### Recorded here, for `.6` and `.7`

- **Every window is anchored on the feed's DAY, never on `(:now ctx)`.** A
  window that moved with the clock would answer two candidate sets to two reads
  of one day, and the cursor's offset would be walking a set that shifted under
  it. `day-start` is the one spelling; `.6`'s insight populations should use
  it.
- **`.7` renders `sentence` wherever it appears.** The seam has one, a `cleared`
  card has one, a memory usually has one, and a `finished` card has none — the
  row's own summary is already the sentence, which is the whole reason a card
  is `envelope-summary` and not a rendering of its own.
- **A fuel card is usually verb-less**, so the panel `.7` draws for it needs a
  read-only shape. It still carries `self`, and the row's own screen is still
  one tap away.
- **A cursor page re-runs the fuel populations for their claims** (`:render?
  false`), and `cleared` and `streaks` fold the aggregate separately, so a deep
  walk pays two `transition-stats` queries and a `LIMIT 1` per kind per page.
  That is the price of the seen-set being page-independent, and it is the same
  price `.2` set. Left unoptimized on purpose: a per-read memo in the ctx is the
  obvious first move and it should be made when somebody has MEASURED the cost
  (waymark-iqa.16), not before, because the population registry is still the
  seam fork (a) named and a memo is the smaller of the two changes it invites.
- **The archive's candidate set is a bounded newest-first window over the log**,
  so a write landing between two pages can shift it. Within a day and a quiet
  log the order holds, and the day boundary is the cursor's own 409. Named
  rather than fixed (waymark-iqa.17): the honest fix is a job for that
  population alone, which is fork (a)'s recorded punt.
- **Media memories are not a population of their own.** A finished work reaches
  the archive through `events` like every other row, carrying its `origin` link
  because the card is the row's own envelope — a feed card that links a photo
  needs no bespoke reader. What actually keeps such works out of today's
  archive is that `next_actions` claims every OPEN row of a front-door kind,
  and a mirror row whose domain `status` says the work is over is still
  framework-open. That is do-now's bug rather than the archive's
  (waymark-iqa.15); `feed/set-aside?` is already the one spelling of the
  question it needs to ask. When it lands, `media.clj`'s own healable punt arrives with it
  and is still a punt: one work under two authorities is two rows, so it will
  be two archive cards. Visible, not resolved, and no merge belongs here.
- **No hash moved.** `feed.clj`, `seasons.clj` and `packs.clj` are engine-side
  and carry no declarations; the twenty deterministic kinds are byte-identical
  and the nine waymark-j82 names are as unstable as they were.

## Built — `.6`, the insight (2026-08-24, waymark-iqa.6)

The one row-creating population landed as the contract asks:
`workqueue10/src/workqueue10/resources/insight.clj` is a `:decision` kind whose
open state is **`published`**, with two note-free one-tap answers, three create
walls, and a four-eyes wall that makes *"it only ever offers"* structural rather
than promised. `feed/populations` gained `:insights` and `feed/default-recipe`
gained `{:section :decide :population :insights :take 2}` in the same commit —
check (1)'s seam for the fourth and last time, and the registry is now complete
against this document's own census.

```clojure
:decision
{:offered :published
 :asks    {:field :finding :max 240 :x-display {…}}
 :by      :authored_by
 :decider {:not {:field :authored_by
                 :name :the-finder-does-not-decide
                 :explain "The finding is yours; the answer is the household's. Whoever published this cannot be the one to accept it."}}
 :stamps  {:decided-by :decided_by}
 :own-surface true
 :verdicts
 [{:name :take    :to :taken     :label "Do it"     :style :primary :order 1 …}
  {:name :dismiss :to :dismissed :label "Not useful" :order 2 …}]}
:create-guards [cites-what-it-claims offers-something-light insights-are-capped]
```

**`seen` and `pinned` are dropped and the drop is the point.** The epic listed
`published → seen/pinned/dismissed`; per-card seen state is exactly what the
third law forbids and what this document's own punt already refused — *"No
seen/unseen state, ever."* If pinning is ever wanted it is a third **verdict**,
not a read receipt. `:offered :published` is the sugar's own rename key, so the
states read `published → taken / dismissed` with no bespoke machine.

**Neither verdict takes a `:note`, deliberately**, and `.4` is the citation: the
sugar's note input is `[:maybe [:string {:max 240}]]`, `demand/field-class`
reads it as `recall`, and `split-verbs` would move that verdict off the card
into `heavier`. Both answers here are meant to be tapped. A finding that wants a
written reason wants a second door.

### The three walls, and where each one refuses

**`cites-what-it-claims`** — `:reads [:storage]`, the `saved_view` write-gate
shape exactly (`(:rdef-of ctx)`, and *allow* when the registry is absent because
that is the storage-free render probe). Evidence is a vector of **addresses**,
`/api/<collection>/<id>`, which is this document's own word for a citation and
also the shape `feed/screen-of` and `sources.waymark/with-origin` already read.
An empty list refuses; an address naming a collection this engine does not serve
refuses and **names every offender**, not the first — a compiler fixing them one
round trip at a time is a compiler burning the cap.

The `:min 1` is on the GUARD and not on the schema, deliberately: a schema
minimum would 422 before anybody said why, and the sentence that names the fix
is the half `spec-decision-record.md`'s second thesis wants kept in the record.

**`offers-something-light`** — the offer is four agreeing parts, `offer_kind` /
`offer_id` / `offer_action` / `offer_href`, and the guard proves they say the
same thing, so an author cannot name one row and link another. It refuses a
kind this engine does not serve, an action that kind does not declare, and —
the one that matters — an action whose rendered effort is heavier than
`"selection"`, computed the way `usability/gesture-duties` computes it
(`(demand/effort a (schema/json-schema (:input a)) key-field)`, classes as
strings). **This is the one place in the tree where the ≤-selection rule is a
door rather than a projection**, because it is the one place a verb is
*declared* — in data, by the author — rather than inherited from a row.

**`insights-are-capped`** — three findings a day, per author, and `:pacing` is
NOT spelled. The bead's option (c), taken for the reason it gave: `:pacing
{:limit 3 :per :day}` computes the right window and prints *"Asks are paced to
{limit} an hour"* (`resource.clj:970`), and fixing that in the sugar would move
`approval_request`'s pinned fingerprint to correct one word. So the cap is
insight's own create guard, ~15 lines mirroring `pacing-guards`, counting rows
through `(:find ctx)` — and its window is the **calendar day**, UTC midnight,
the same midnight `feed/today` rolls the feed on. A rolling twenty-four hours
would have been cheaper and would have made the sentence a lie: the household
reads *three a day* and means the day it is having. The sugar bug is filed as
**waymark-iqa.19**.

Per AUTHOR rather than per house, which is `pacing-guards`' own shape (`{by
pid}`) and the honest reading: the cap exists to make an author rank its own
findings, and a house-wide cap would let a noisy author silence a quiet one.
Unlike the in-process pacing atoms this one counts rows, so it is shared across
processes; the recorded punt about coordinators is inherited anyway, because a
house running two compilers is a house that declared two authors.

The walls run **shape first, pace last**. `letters-are-paced` rides first
because its atom must count the attempt; this cap counts ROWS, so a refused
create spends nothing, and a malformed finding should hear what is wrong with it
rather than that the house is full.

### Accepting does not fire the offer, and the reason is not 442.14

`.1` recorded the hazard as *"this bead depends on waymark-442.14"*. It does
not, and the check closed the worry: `invoke/make-ctx` hands a handler's `ctx
:invoke` the **outer principal** (`{:principal principal}`, `invoke.clj ~:294`),
which is exactly right here — the outer principal IS the accepting member.
442.14's blocker is the opposite need (a handler acting as a *system* actor,
plus an `:id` on `ctx :create`), and neither applies.

**A different wall is the one that closes it, and it is sharper.** Grant
projection is a REQUEST-level concern: `router/check-row!`, `check-action!` and
`grants/check-args!` all read `(:waymark10/visibility req)`, and a handler ctx
carries no visibility. So a cross-write from a handler is gated by the
DECLARATION, never by the caller's grant — which every other cross-write in the
tree can afford, because every one of them names its target kind and action as
**literals** (`ingredient/absorb-duplicate`, `grocery_list`; only the id is
data) and advertises the pair in `:touches`, which
`checks_assembly/check-touches` verifies at assembly and `render` puts on the
wire. An insight's target is data chosen by its author. It is therefore the one
cross-write no declaration could name, no `:touches` could advertise, and no
grant would re-gate — and the author is a leashed agent by design, so the
primitive would let a compiler propose `grant.extend` on its own leash and have
one member's tap widen it. The four-eyes wall stops the agent accepting; it does
not make the proposal safe.

So **the offer is an ADDRESS rather than a trigger**: `take` records the
household's answer, and `offer_href` rides a declared `:links {:rel "offer"}` as
`/#` + the row's own address — the tickler's spelling, for the reason it gave,
*a link is the honest way back to work that lives somewhere else*. The address
is **checked at the door** against the registry, so the author cannot invent it.
The seam that would change this answer is filed as **waymark-iqa.18**: a handler
ctx that carries the caller's visibility. It is the sibling of the door
`server/seams.clj` refuses and wants the same review 442.14 wants.

`.7` gets one tap out of this anyway, and better gated than a handler could
manage: the card carries the offer's address, so the primary chip can POST to
the row's own action door **through the router**, where the reader's own grant
gates it exactly as this document asks — carrying `feed/origin-key`, so it
counts as an action-from-the-feed like every other card verb.

### The population, and the two retirements it inherits

`feed/insights` is the `ticklers` reader one turn over: `published` markers
only, minus **the reader's own** (the four-eyes wall means carding a finding to
its author would be offering a door that answers 409 — `asks` does the same
thing one population up, for the same reason), and retired at offer time by
`feed/set-aside?` when the offered row is finished or gone. A finding whose next
step is over is a dead offer, and it says nothing at the moment it would have
spoken rather than being swept. No new mechanism: `set-aside?` was already
public because `.4`'s pack judges against it.

The bound is the read-time posture's — at most `row-scan-cap` findings, and only
the survivors of the first two filters cost a subject read. The daily cap is
what keeps that number small **at the source**, which is the point of putting
the wall at the door instead of in the query.

### Where the law is proved

Four `defscenario`s, and the tier split is read off declarations exactly as
`.1` predicted:

- **check tier** — `the-finder-does-not-decide` (the four-eyes wall,
  `:reads [:principal]`) and `a-dismissed-finding-does-not-come-back` (the
  reserved `:out-of-state` denier with no guard behind it).
- **deferred to the suite** — `no-citation-no-publish` and
  `no-offered-action-no-publish`. Both create-door scenarios carry every create
  guard, and two of the three reach past `offline-reads` (`:storage`,
  `:insight`), so `check` prints *"2 deferred to the suite: reads :insight,
  :storage"* and `:core/law-scenarios` runs them through the real HTTP door.
  Written expecting that, not fighting it.

`make check-queue` reads **32 kinds, 11 warnings, 16 scenarios** — one kind
more, two check-tier scenarios more, and the warning count unmoved.

**`:feed/insights` in `packs/feed`** is the pack's new LAST obligation, below
`:feed/ticklers` for one turn of the same reason: it mints the most rows of any
obligation, because the only honest way to watch a daily cap fill is to fill it,
and three fresh findings in the decide section are cards the two ticklers above
would have had to share with. It runs on **two principals** — the four-eyes wall
cannot be watched doing its work by one — and claims, from the wire:

1. **No citation, no publish**, twice: an empty `evidence`, and an address
   naming a collection this house does not serve.
2. **No offered action, no publish.**
3. **Nothing heavier than a tap**, proved with the subject card's own `heavier`
   entry — `.3`'s partition has already named a verb of that kind that is too
   heavy for a thumb, so the obligation offers exactly that one and watches the
   door refuse it. A kind with no heavy verb skips the claim rather than
   inventing one.
4. **The cap refuses the N+1th**, discovered rather than known: it publishes
   until the door says no, and asserts only that the refusal came and that it
   named `insights-are-capped`. How many a house allows is the house's to
   declare — the same restraint `:feed/ticklers` keeps about the backoff
   schedule.
5. **The card is real**: it lands in `decide`, offers both verdicts in `actions`
   (never in `heavier` — the note-free proof), and carries its `offer` link.
6. **The finder cannot answer its own finding** — 409, by name — and the
   finder's own feed never cards it.
7. **An answered finding leaves the feed**, because `taken` is terminal.

Two things the obligation had to learn, and both are the law being right rather
than the test being clever:

- **It mints a FRESH author every run.** A daily cap counts ROWS, so unlike the
  in-process pacing atoms it survives a restart: an obligation with a fixed
  author id spends the allowance on its first run and is refused at its first
  create on every run after, in a suite whose fixture is under no obligation to
  drop that table. A new author has a new allowance by the law's own shape. (The
  household's own fixture now drops `insights` too, so the decide section does
  not accumulate last week's findings in its two slots.)
- **It answers WHICHEVER finding the day's order carded**, not the first one
  published. `:take 2` is smaller than the cap on purpose and `hash(seed ‖
  card_id)` decides which two of three a member reads today; an obligation that
  insisted on the first would have been asserting a ranking the third law
  forbids. It cost a red run to notice, which is the obligation earning its
  place.

### Recorded here, for `.7`

- **An insight card is a `decide` card with an `offer` link**, and the link is
  the one thing on it that is not a verb. Render it as the PRIMARY affordance
  (it is the next physical step) and the two verdict chips beside it; the link's
  href is a screen, so it is a link and never a POST — `.3`'s rule for
  `heavier`, and the same reason.
- **The byline is a principal id and must stay one.** `:by :authored_by` is
  `:raw` by the sugar's own rule; the agent badge posture
  (`ui/140-links-access.js`) is how agent-ness is shown, not by dressing the id
  up as a person. A friendlier byline is a separate bead and this document's own
  punt.
- **`take` and `dismiss` are both assent-effort and both must stay so.** The
  moment either grows an input the card loses it to `heavier` and the epic's one
  tap is gone.
- **The one-tap "Do it" is `.7`'s to compose**: POST the offer's own action door
  (`offer_href` + `/-/` + `offer_action`) under `feed/origin-key`, so the
  reader's grant gates it at the router. Doing it from the insight's own handler
  is refused above and filed as waymark-iqa.18.
- **No hash moved that was not meant to.** `insight` is a new kind and mints a
  fresh fingerprint; the sugar was not touched, so `approval_request`,
  `permission_slip` and `tickler` are byte-identical, and `feed.clj`/`packs.clj`
  are engine-side and carry no declarations.

## Built — `.7`, the screen (2026-08-24, waymark-iqa.7)

Fork (c) landed as decided and the epic's last implementation bead with it:
`waymark10/resources/waymark10/ui/135-feed-screen.js` is a **dedicated screen**,
reached by a `render()` fork on the document's own `kind` —
`110-discovery-routing.js` gained four lines beside the dashboard's, the
deploy-history tradition one document later. No saved view, no `:target` to lie
about, no gesture bound anywhere.

**What a reader sees, top to bottom.** A head that says *The feed* and carries
the server's own summary sentence (`Feed · 2026-08-24 · 17 cards`) with the
document's `notes` folded behind a *Why this order* disclosure — the seed
sentence, the grant sentence when one applies, the archive's cap note when it
was reached. Then the census, painted **from the card stream and never from
`sections`**: a quiet heading when the section changes (*DO NOW · one physical
next step, under the thumb*), the cards of that section, and so on down. The
seam is its own element — a rule, the sentence, and *n above · everything below
is history* — and everything under it reads quieter by declaration
(`.fcard[data-section="archive"]` drops its panel background). The tail is a
sentinel: an `IntersectionObserver` takes `links.next` when it comes within a
screenful, the same door stands as a *Further back ↓* button for a thumb that
got there first, and when the archive runs out the page says *— that's the whole
archive —* rather than spinning.

**Each population, one line.** *do-now* — the row's title, its teaser line, and
its light verbs as tap chips. *decide/ticklers* — the three verdicts as three
chips plus the declared `subject` link back to the work. *decide/insights* — the
finding, the evidence it read (fetched late, see below), the **offer as the
primary affordance**, then both verdict chips and the byline. *fuel* and
*archive* — a read-only panel: the server's `sentence` in prose, the row's own
summary as a link under it, and no chips at all, because a done row has no verbs
left and that is the point rather than a gap. An unknown card degrades into the
row shape; a card that throws becomes a problem panel wearing its own refusal,
never its neighbours' problem.

### The dispatch is on the card's SHAPE, not on its kind

The bead's design said *"a small dispatch map on `card.kind`"*. That is the one
place the plan could not survive contact: `tickler` and `insight` are
**application** kinds, and the generic UI is the framework's — a renderer keyed
on those names would put workqueue10's vocabulary inside waymark10's page, which
is the coupling this repo refuses everywhere else. What the screen keys on
instead is what the WIRE says: a card carrying a `links.offer` is a finding with
a next step attached; a card with no verb of any weight below the seam is
something to read; everything else is a row speaking for itself. `section`,
`population` and `kind` ride as `data-` attributes, which is exactly the styling
job the spec gives them and no more — nothing reorders on them.

### The verbs are chips, and every one of them carries the origin

`feedOriginKey(day, card_id)` is `feed/origin-key`'s spelling in JavaScript —
`feed/<day>/<encodeURIComponent(card_id)>/<12 hex>` — and it is minted per TAP,
so two taps of one verb on one card are two attempts and never a collision.

- A verb with no input and no confirmation is **one tap**: a bare POST to the
  action's own href, `If-Match` when the declaration fences it, the key always.
- A verb that wants a form or a confirmation is not a tap and opens the ordinary
  `actionDialog` — which grew ONE optional argument, `idemKey`, so a card verb
  rides the same origin whichever door it opened. A metric that counted only the
  one-tap half would be a metric that flattered the screen.
- After it lands, the row's own fresh envelope decides what the card says: the
  chips are replaced by *✓ Done · now retired* and the summary is repainted from
  the answer. **The verbs do not come back**, and that is deliberate: a fresh
  envelope carries every effort, and re-deriving the ≤-selection partition in the
  client would be a second opinion about what fits under a thumb. The card that
  was answered is answered; the next read of the day will not carry it.
- A refusal renders **on the card that asked for it**, through `problemBox` —
  the engine's own sentence, where the thing it is about still is.
- `heavier` renders as a LINK, `.3`'s rule kept: `/#/api/…` with the leading
  slash dropped so the same address costs no page load.

### The offer is two affordances, and they are different things

`.6` recorded that accepting a finding does not fire its offer. The card says so
by carrying both: a **primary chip** that POSTs the offered action's own door
(`offer_href` + `/-/` + `offer_action`, under the origin key, gated at the router
by the reader's own grant — better than any handler ctx could manage), and the
declared `offer` **link** to the row's screen. The verdict chips sit beside them
and answer the finding rather than doing the work; the offer chip settles alone
when it lands, because the house has still not said yes. The byline is the
principal id in a badge whose title says exactly what it is — no display name is
invented for it, per this document's own punt.

An insight's `evidence` is a vector, and a vector does not ride `:fields`
(`render/grid-fields` — a flat cell cannot hold one), so the count is not on the
card. The screen fetches the finding's own envelope once, late, and appends
*read 2 rows: 77041f15, a4238126* with each address a live link. It degrades to
nothing at all. **No document gap was opened for it and `feed.clj` was not
touched.**

### Three things the card does NOT do

1. **No field table.** A saved view names a deck's card fields and a table has
   the query grammar's grid columns; a feed card has neither, its kinds are
   mixed, and nothing declared which three fields matter here — so any three the
   page picked would be three it guessed. What it shows instead is the one line
   the declaration already marked for this: a prose field with `:x-display
   {:teaser true}`, the same quiet second line the collection table gives it.
2. **No external byte is fetched.** A media card's `origin` rides as a link chip
   and never as an `<img src>`: this page is self-contained by declaration
   (`020-base.css`), and an image element pointed at a third party is a beacon
   wearing a picture's clothes.
3. **No collection link.** A card renders a declared link only when it is a
   PLACE TO GO — a screen the declaration spelled with `/#`, a download, or a
   door out of the house. A link naming a collection is a query, and a card has
   no room for a query.

### Two departures from the bead's design, both recorded

**A card LIST, not snapping panels.** The bead asked for `134-feed.js`'s panel
snapping and `Escape` to leave. Neither survives the move from an overlay to a
screen: the feed is a hash destination, so `Escape` has nowhere honest to go
(the browser's own Back is the way out), and one-card-per-viewport would put a
two-line memory alone on a phone screen and make the seam a page you must
dismiss rather than one you scroll past. The cards are as tall as what they have
to say. The IntersectionObserver's margin moved with it — 100% of the viewport
rather than 200% of a panel-sized scroller, because 200% of a page swallows ten
cards at a time and the archive stops being something you walk.

**The nav door is a probe.** The recorded punt stands — a module cannot
contribute a line to `.well-known` — so the page knows the address and asks,
once per load, with a cursor this engine could not have minted: mounted answers
422 before reading a single row, an engine without the module answers 404, and
so does an anonymous reader, who has no feed either. `feedDoor()` caches the
promise the way `wellKnown()` caches its own, and the nav's two awaits both
happen before the bar is cleared, so a late probe can never append to a bar a
newer render already emptied.

### Verified by hand, and the walk is written down

`make check-queue` is unmoved at **32 kinds, 11 warnings, 16 scenarios** — this
bead ships JavaScript, and the battery has nothing of its own to judge, which is
what the bead said to expect. The screen was verified the way this repo verifies
screens (`waymark10-design.md` §10), and the walk is now a script rather than a
memory: **`waymark10/scripts/feed-smoke.sh`** boots headless chromium if needed,
runs `ui-drive.mjs feed` — a new third mode beside the family-week story and
batch A, which seeds its own day through the API and then reads the screen — and
finishes with the half a browser cannot see:

```
· actions from the feed, off the audit trail:
 feed/2026-08-24/do_now%2Ftask%2Fad5595df-…/7a2bd47f2323 | task | complete
```

Nineteen checks, no console errors: the nav door, the fork, the census order and
the seam, a tap chip labelled as declared, no gesture bound anywhere, a
composition verb rendered as a link to a screen and not as a POST target, the
tickler's three verdicts, the insight's offer/verdicts/byline/evidence, the
read-only fuel and archive cards, an archive page followed off `links.next` with
no `card_id` repeated, the honest tail, a verb tapped and settled on the fresh
envelope, the `Idempotency-Key` it sent, and the four-eyes wall read off the
author's own feed. Screenshots of both shells (desktop and `?ui=mobile`) were
read by eye; two defects found that way were fixed here rather than filed — a
stray `null` beside the evidence line, and a tail that kept saying *reading
further back…* after the page had landed because `paintEnd` ran while the flag
still stood.

**One hazard found and NOT fixed here, because it is not this bead's and it is
not new** (waymark-iqa.20): the page holds three SSE streams — `/api/-/events`,
`/api/-/intents`, `/api/-/presence` — and HTTP/1.1 gives a browser six sockets
per origin, shared across TABS. Two tabs of the generic UI therefore exhaust the
pool and every later fetch stalls forever: a cold deep link renders a blank
page, and an archive page never lands. It reproduces identically with this
bead's changes reverted, and it is why the drive wants a clean browser.

### Recorded here, for whoever comes next

- **`ui_lite.html` has no feed and none was added.** The waymark-6ey precedent
  is that a lite-client upgrade is its own bead; filed as waymark-iqa.21. The
  lite page carries no dashboard and no seasons either, so the feed is in
  company.
- **Nothing in `feed.clj` moved.** The one document gap a renderer might have
  claimed — an evidence count on the card — is answered by one late read of the
  finding's own envelope instead, which costs the wire nothing and keeps
  `grid-fields`' rule (a vector does not fit a flat cell) unbent.
- **Four framework files changed beside the new one**: the render fork
  (`110`), the nav door (`120`), the dialog's optional caller key (`180`), and
  the two stylesheets; `ui_assembly.clj` grew the fragment's slot, which is the
  only Clojure line this bead wrote. **No declaration was touched and no
  fingerprint could move.**
- **An undo tapped from the toast rides its own key, not the card's.**
  `maybeUndoToast` is the deck's own affordance and the third gesture duty's
  *way back*; it mints a fresh key inside `invokeBare`, so an undo does not
  count as an action-from-the-feed. Named rather than fixed: the toast is not
  the card.

## Built — `.23`, `feed.preview_as` (2026-08-24, waymark-iqa.23)

The feed is per-member by construction and the wire rightly refuses
impersonation. *"See my feed as I see it"* is nevertheless a real want — an
agent debugging card order, the insight compiler previewing what its audience
will actually read before it publishes — and the deterministic workaround (a
replica plus the dev-posture header) is a workaround: it lives below auth,
which is exactly why it is not the answer.

The answer is a **capability**, `feed.preview_as`, granted like any other
capability and judged at the feed door.

### The one inversion, and why it is not a special case

`server/capabilities.clj`'s own docstring says what a capability is for:
waymark holds the law about access and never the credential, so the registry
NAMES an external power and the system fronting the data — Gate, a telemetry
proxy — asks `/api/-/grant-check` and forwards or refuses with its own hands.
It names the trade that follows in one sentence: *enforcement is cooperative —
waymark cannot see whether the enforcement point honors the leash; own the
enforcement point.*

`feed.preview_as` is that sentence's own answer. **We own this enforcement
point.** The power granted is waymark's own feed route, so the data and the law
are in one process and there is nothing cooperative about it. That inversion is
recorded in three places so it cannot be discovered later: the capability
row's `:enforced_by` (*"this engine's own feed route — waymark holds the data
AND the law here, unlike every other capability in the registry"*), the
namespace docstring, and this section.

What did **not** change is everything else. The ask is an ordinary
`approval_request` with a dotted token in its scope; `scope-names-real-kinds`
judges it against the ACTIVE registry exactly as it judges `telegram.send`
(verified, not assumed — the guard's dotted branch was already there);
four-eyes approval, attenuated delegation, the expiry, the revoke door and the
magic links are untouched. A capability whose `:enforced_by` names a file in
this repository is the shape to expect of the ones that follow. It is a special
case of *who holds the data*, never of the machinery.

### The door

```
GET /api/-/feed?preview_as=<member-id>
X-Waymark-Grant: <grant-id>
```

`preview_as`, snake, beside `cursor` — the wire's own field spelling. The
member is named by row id **or** by the subject a binding wrote, because those
are the two spellings a human filling in a grant filter can have in front of
them (the roster's id, and the id an agent knows itself by); it is
`gate!`'s own two-step, not a new lookup.

The grant that opens it, verbatim:

```json
{"audience": "claude-code",
 "scope": [{"kind": "feed.preview_as",
            "actions": [],
            "filter": {"member": "<member-id>"}}],
 "expires_at": "2026-09-24T00:00:00Z"}
```

`"actions": []` is the read-only ask and is the honest shape here: capability
`:actions` are uninterpreted in v1 (the registry's recorded trade), and there
is exactly one thing this power does.

### The filter vocabulary, decided

**One member id, required.** `{"member": "<id>"}` — one key, one value.

*Absent means any* was weighed and refused. An unfiltered `feed.preview_as`
grant MINTS fine (waymark validates a filter's shape and never its meaning —
that is the registry's own recorded trade, and this door does not get to bend
it at the mint) and is **refused at the door** with a sentence: *"an unfiltered
preview grant is refused here: the filter is WHOSE feed may be read, and a
grant that names nobody would name everybody."* The reason is not caution, it
is the whole value proposition: absent-means-any is exactly the grant a tired
human approves without reading, and the point of a capability over a role is
that the approval names the thing.

**A list was not added, and the guard is why.** `scope-filters-are-filterable`
already refuses two filtered entries of one kind — a rule about grants in
general, written long before this — so one grant previews one member and a
second member is a second ask. The shape is the guard's; this door simply did
not fight it. If the two-member case ever bites, the honest fix is a filter
grammar for capability entries, not a comma in a string.

An unrecognised filter key refuses too, naming what it found. A door that
honoured a constraint it had not understood would be honouring nothing.

### The crux: whose sight

The preview must see **exactly** what the member sees, through the same code
path, never a re-implementation. Two expressions carry that, and both are
*called*, not copied:

- **`members/principal-for`** — resolves the row (by id, then by subject),
  refuses anything not `:active`, and hands a bare principal to **`gate!`**,
  which unions the member's durably held roles and re-applies the suspension
  refusal. A hand-built principal that forgot the roles union would answer a
  preview through a smaller world than the member lives in, and that is the one
  failure a preview may not have, because it looks like a correct answer.
- **`grants/unscoped-visibility`** — `wrap-identity`'s own else-branch, lifted
  out of the router and now called from both places: `nil` for a human or
  system actor, `bootstrap-visibility` for an agent, which never runs unscoped.
  `wrap-identity` was rewritten to call it, so there is one expression and it
  cannot rot.

`feed/document` is then called unchanged with the member's principal and the
member's visibility. The seed is `(salt, THE MEMBER, today)` for free, because
`document` derives it from `(:id principal)` — a preview seeded by the caller
would be a fourth member's order, belonging to nobody.

### The stamp — never silent

```json
"preview": {"of":    {"id": "…", "display": "Jack"},
            "by":    {"id": "…", "display": "claude-code"},
            "grant": "…"},
"summary": "Feed · 2026-08-24 · 7 cards · PREVIEW of Jack · read by claude-code",
"self":    "/api/-/feed?preview_as=…"
```

Four surfaces, because a client that renders one line must still see it: the
`preview` key, the summary, a note that leads the `notes` list, and the `self`
address. `links.next` carries `preview_as` too — the one place a stamp could
tell the truth while the hrefs quietly lied, and page two of an archive walk
would have become the previewer's own feed.

The first note also replaces the day-seed sentence's *"seeded by (you, …)"*
with the member's name, because under a preview "you" is a lie.

### The verbs render, and they are the member's

The bead left this open between *render disabled with a sentence* and *strip
them*. **They render**, and the refusal to strip has two independent reasons:

1. **Honesty.** The stated use is *"preview what my audience will actually
   see."* A preview with the affordances removed is a preview of a different
   surface, and the compiler use — does this finding offer something tappable
   to the person I am publishing it to — evaporates entirely.
2. **Arithmetic.** `do_now` keeps only cards that `offers-something?`. Stripping
   verbs *before* the mixer would change **which cards appear**; stripping them
   *after* would leave a section whose whole rule is *a verb under the thumb*
   full of rows with none. Either way the preview stops being the member's feed.

They are **disabled by truth**, not by a flag: every href is the member's own
door, `wrap-identity` resolves the ACTUAL caller at it, and a previewer whose
leash names one capability and no kind at all is answered 404 by
`check-kind!`. Nothing was added to make that true — it was already true — but
"already true" is not proof, so it is asserted from the wire in two places (see
below). The document says it in prose as well: *"the verbs below are Jack's —
each action href is Jack's door, and a request you send there is judged as you
and refused."*

### Audit — the honest answer, and the recorded punt

**Nothing durable records the READ.** The feed is a `GET`, and this epic's
laws forbid writes-on-read (`:feed/day-stable` would be the first casualty: a
surface that logged its own reads would have per-read state, and the badge
count is one refactor away). So the audit rides what already exists, and the
document says which artifact that is: **the grant** — its `approval_request`,
its four-eyes verdict, its expiry, its revoke door, and the fact that it is
scoped to one named member — is the record, and its id is in the stamp.

That is a real and honest limitation, stated rather than papered over: **you
can prove who was ALLOWED to preview whom and when, and you cannot prove from
waymark's own rows that they DID.** The remedy is not a write-on-read invented
here; it is the usage-report ping the capabilities docstring already carries as
a punt (`waymark-44h.3`), which is a capability-wide answer and not a feed one.
Filed as **waymark-iqa.23.1** so the punt has an address.

### Where the law is proved

- **`:feed/preview-as`** (`test/packs.clj`, `:needs #{[:route :feed] [:kind
  :capability] [:kind :member]}`) — **three identities through one door**,
  because *"the preview is the member's own read"* is not a claim one principal
  can check, and a preview computed for the CALLER would pass every
  single-principal assertion anybody would think to write. It ensures the
  capability row, mints a member to preview, mints and accepts a filtered grant
  for a probe agent, and asserts: card-for-card equality against **the member
  reading their own feed**; that the previewer's own feed is a *different*
  document (so the equality is not free); the four stamp surfaces; 403 with the
  capability named when the grant header is dropped; 403 for a different
  member; 403 for the unfiltered grant; and — reporting `:covered` — a verb off
  a previewed card **invoked by the previewer**, which must not land. It runs
  LAST in the feed pack, because it is the only obligation that mints a MEMBER
  and a new member is a new row every obligation above would have had to share
  its deck with.
- **`feed_test.clj`**, three deftests over the in-memory twin, for the half a
  driver with one world cannot arrange: a house with rows, a member built to
  have a feed, and a previewer built to have none.

### Recorded here, for whoever comes next

- **The refusal is a 403 that NAMES the capability**, which is a departure from
  the feed's usual concealing 404 and is deliberate. The registry already took
  the vocabulary posture — capabilities are WORDS, readable by every named
  principal without a grant, because *an agent that cannot read what powers
  exist cannot compose its ask* — so the sentence discloses nothing a `GET
  /api/capabilities` would not, and it buys the thing concealment costs: the
  refusal carries the exact `approval_request` body to send.
- **The wrong-member refusal names the member the grant admits.** That is the
  caller's OWN grant, readable on their own surface, so it leaks nothing they
  do not hold — and a refusal that would not say which member is admitted makes
  a typo indistinguishable from a revocation.
- **The MCP surface has no feed tool**, so an agent at the MCP door reads this
  over plain HTTP with its grant header. Six tools is a design decision the MCP
  spec defends and this bead did not reopen it; filed as **waymark-iqa.23.2**.
- **No declaration moved and no fingerprint could.** The `capability` kind
  gained two `def`s of DATA in its namespace (the token and the row a boot seed
  ensures) and prose in its docstring; its schema, states, guards and actions
  are untouched. The feed route is a handler, not law.
- **The suspended member refuses out loud**, with the same 403 their own
  request would meet, rather than reading as *no such member*. A grant
  explicitly naming them is the reason: the previewer already knows the member
  exists, so concealing the suspension would only make the refusal confusing.

## Built — the first real read, and the four things it found (2026-08-24, waymark-iqa.24, .15, .25, waymark-1zq)

The feed shipped, deployed, and was read for the first time as the person who
lives in it — Colton's own feed, through `feed.preview_as`. Everything worked
and almost nothing was right.

do-now held three movies, a chore run somebody skipped seventeen days earlier,
and a Google task that had been done for a month. It held **none** of the
household's thirty-three open tasks — sixteen of them overdue, a brake booster
and a caregiving cluster and a lapsed insurance policy among them. Below the
seam, four shows the family is halfway through carded as *memories*, wearing
their full verb sets. Fuel congratulated the house on a grocery list it had
thrown away and on an ingredient line inside a recipe. And a letter written to
Colton two days earlier had never appeared anywhere at all.

Each of those is a shape a smoke test cannot have, because a smoke test has
three rows in it and a household has a hundred, lopsided by kind, half of them
finished and some of them let go. So the first thing this bead built was the
**world**, and everything else follows from reading the feed against it:
`workqueue10/test/workqueue10/feed_shape_test.clj` boots the declarations
production serves and the recipe production reads, seeds thirteen open tasks
(five overdue) beside two done and one dropped, twenty media rows of which four
are over, chore runs due and done and long skipped, a discarded grocery list, a
removed meal line, and one unopened letter — and then asserts the shape of every
section. That file is the regression net, and it is the deliverable of this
bead as much as any line of feed.clj.

### `:over` — the one thing the machine could not know

Three of the four findings are the same missing sentence. The machine knows a
row is TERMINAL and nothing else, and terminal is both too narrow and too wide:

- too NARROW, because `chore_run`'s `:done` and `:skipped` are not terminal at
  all (`:reopen` and `:unskip` are what made the triage deck honest), so a
  three-week-old skip read as work still waiting; and because a mirror kind's
  machine is the SYNC machine, so a task the authority calls `done` reads as
  live forever;
- too WIDE, because `grocery_list`'s `:done` and `:discarded` are both terminal
  and only one of them is an accomplishment.

So the declaration says it, in the household's own words:

```clojure
:over {:accomplished #{:done} :let-go #{:skipped}}                     ; states
:over {:field :status :accomplished #{"done"} :let-go #{"dropped"}}    ; a mirror's data
```

The optional `:field` is the mirror seam waymark-iqa.15 asked for, and it is
what keeps the framework from holding any application's enum: `feed.clj` used
to carry the literal string `"done"` — one app's vocabulary, held by core,
which made `media`'s honest `queued/active/finished/abandoned` read as a
deviation from a word core happened to know. That literal is gone; the kind
that speaks the vocabulary declares it.

Unspelled, `:over` means exactly what the code meant before: terminal states are
endings and every one of them counts. **It is not fingerprinted** — the `:nav`
precedent, and for the same reason: no door consults it, only the read side
does, so a household can teach the difference between finishing and giving up
without minting a law revision.

Two questions read it, and they are deliberately different:

- **`work-over?`** — is this row's work over? Nothing over is a next action, and
  only what is over is a memory. It counts *let go* as over.
- **`accomplished?`** — did the house FINISH it? Fuel is deeds. A discarded
  list, an abandoned book and a skipped chore are over without being deeds.

`set-aside?` — the tickler's and the insight's retirement question — asks the
NARROWER one on purpose, so a someday/maybe marker over a dropped task still
stands. *Still not done* is precisely what a someday list is for, and the
household's way to say otherwise is the `take_it_back` verdict: a person
answering rather than an engine inferring.

`seasons/classify` reads `:over` too, one line, for the same reason both had to
agree about what finishing means: an action landing in a declared let-go state
is no longer counted as `:completed`, so the weekly bars and the `cleared` card
say the same thing about the same week.

### The spread — composition, not a score

do-now's five slots were drawn by `hash(seed ‖ card_id)` over every open row of
every front-door kind, pooled. That is fair per ROW and therefore a lottery
weighted by row count: two hundred queued films against thirty-three errands is
a do-now of films, forever, and no amount of re-reading changes it because the
seed is stable. Arithmetic, not a household's morning.

Every `next_actions` candidate now carries the **`:lane`** it occupies in its
OWN kind's seeded order — its kind's first row is lane 0, the second lane 1 —
and the mixer sorts by lane before hash. Five slots therefore draw from five
kinds when five kinds have work. Within a lane the order is the same hash it
always was; within a kind nothing moved. **Nothing is compared to anything**,
which is the line the third law draws: a spread is composition, a score is a
claim that this row is better than that one, and only the second is forbidden.

The recipe can go further, and this household does. A recipe entry may name
`:kinds`, narrowing that LINE to particular rows, so `workqueue10/main` declares:

```clojure
{:section :do_now :population :next_actions :take 2 :kinds [:task]}
{:section :do_now :population :next_actions :take 3}
```

Two of the five slots are the queue's before anything else is considered. It is
static data saying what this house reads first — the recipe doing the only job
the recipe has ever had — and the mixer's total claim makes the two lines
disjoint for free: the first claims every task, shown or not, so the second
never re-offers one.

### The archive asks the row where it stands NOW

`memories` matches on the PAST — a transition from a year ago, a row that moved
lately — and said nothing about the present. An active show that moved last
Tuesday was therefore a memory, with its verbs, below the seam. `.5`'s own
report claimed the archive was *"effectively the rows the household finished"*;
that was true only because do-now's claim happened to swallow the live ones
first, and `row-scan-cap` is 100, so in a house with more than a hundred media
rows it stopped being true silently.

The archive SECTION now gates on `work-over?` — the row as it stands, not as it
moved. The cost is one point read per candidate WALKED, not per candidate named:
the mixer's `keep-indexed` is lazy and `take` short-circuits it, so a page of
six usually pays for six or seven, exactly as the card renders already do. The
honest worst case is a house whose every recent mover is still live — then the
walk reads the whole ordering to fill a page, bounded by `log-scan-cap` and no
worse than the scan the population already did. If that ever bites, it bites
where waymark-iqa.16 is already watching.

One thing the gate does not do, and it is worth stating rather than hiding: an
archive card of a MIRROR kind still carries that kind's verbs, because a card's
body is the row AS OF NOW (the departure `card` has always documented) and a
finished film's `start` really is a door — *we changed our minds* is a thing
households do. The archive is history; it is not read-only.

### Fuel is deeds, and a line item is not one

`finished` read *the last terminal row of every `:primary` or `:secondary`
kind*, which is how *you finished 'Ingredient: 2 tbsp butter'* reached a
household's feed. Two corrections, both using vocabulary that was already there:

- fuel (`cleared`, `streaks`, `finished`) speaks only about **`:nav :primary`**
  kinds. `:nav` is already the household's own answer to which rows a person
  goes to; a line item is `:secondary` precisely because nobody navigates to
  one. The archive keeps the wider list — a memory may be of anything a person
  can reach — but a deed is a front door's.
- `last-finished` asks for the last **accomplished** row rather than the last
  terminal one, so the discarded grocery list is remembered without being
  celebrated.

### The letter that never arrived (waymark-1zq)

`letters.clj` resolves `:to` at the door — a member ROW id becomes the delivery
identity its `:subject` names — so mail sent from here has always carried the
one spelling the shelf matches. What the door could not do was reach the mail
already ON the shelf: the feed's `letters` population asked for
`{:to <principal-id>}` and nothing else, so a letter carrying any other accepted
spelling was invisible to the person it was for, sitting in plain sight on its
own row. A shelf that swallows mail is worse than a shelf with no floor: the
recipient never learns there was something to open.

Three changes, and they are one idea:

1. **`members/spellings-of`** — the gate's own resolution read backwards. The
   ids a principal answers to are its principal id and the member row whose
   `:subject` is that principal. One query, nothing written, and the one
   definition of the question.
2. **The feed's `letters` population asks for every one of them**, deduped by
   row.
3. **`opener-is-recipient` and `discarder-is-recipient` ask the same question**
   — via the row's own `:subject`, the same fact `delivery-id` uses going the
   other way. A card offering an Open the guard would refuse is a dead end
   wearing a verb, so the reading side and the opening side had to agree.

And the create door's refusal now says what a good address LOOKS like — *the
member's id as the roster shows it, or the id they sign in under* — which is a
fact about the household's roster in general and not about the id in the
caller's hand. Every unresolvable recipient still renders that ONE body, so the
door still narrates no roster, and `letters-are-paced` still stops the sweep.

**The production letter is not fixed by any of this, and should not be.**
`letters/f5415d68` is addressed to a member row; whether it becomes reachable
depends on a fact only production holds — whether that row's `:subject` is the
principal Colton actually signs in as. A letter has no amend and no re-address
by design (*once sent, is sent*), so the honest remedy is a fresh letter to an
address the door resolves, and the old one discarded by its recipient if it ever
becomes theirs to discard.

### Recorded here, for whoever comes next

- **`:over` is read-side only, and the temptation to make it law should be
  resisted deliberately, not by accident.** The moment a GUARD reads it — *you
  may not reopen an accomplished row* — it becomes fingerprinted law and every
  declaration that carries it mints a revision. It is a read trait today.
- **Fuel is still silent about the WORK QUEUE, and that is a choice, not an
  oversight.** A mirror kind has no accomplished STATE to query — its endings
  live in a data field — and the obvious extension (query `status=done`, newest
  `updated_at` first) would date the deed by the SYNC stamp: a row re-observed
  today reads as *what you got done today* even if the authority marked it done
  a month ago. Claiming a deed on the wrong day is the same over-claim
  waymark-iqa.25 exists to stop, so the queue's finished rows reach the archive
  and not the fuel line. Filed.
- **`cleared`'s count still comes from `seasons/report`'s aggregate**, which is
  now let-go-aware but is still an ACTION-level classification: a kind that
  ends the same state through two different actions counts both. Filed rather
  than fixed.
- **The own-surface pushdown still matches one spelling.** An AGENT addressed by
  a member row id sees its own letter only where the reader is unscoped; the
  feed and the guards agree, `grants/own-ids` does not yet. Filed.
- **`row-scan-cap` is still 100 per kind**, and the spread makes it matter less
  (a kind now contributes its whole lane 0 whatever else exists) but not zero:
  a household with more than a hundred open rows of one kind still has a window,
  and waymark-iqa.16 is where that lives.

## Built — `.29`, the feed explains itself (2026-08-24, waymark-iqa.29)

The owner opened the feed, found a movie in do-now, and could not find out
why. Four layers had agreed to put it there — a framework predicate
(`work-over?`), a declared trait (`:nav`, `:over`), a recipe line, and the
day's seed — and not one of them said so anywhere a person could read; the
recipe itself was a vector in `workqueue10/main.clj` that no reader would
ever see. In an engine whose signature move is that refusals narrate
themselves, that is a surface **publishing without citing**. The rule the
`insight` kind imposes at its create door — *no publication without
citation* — now binds the feed's own editorial choices.

Nothing below infers anything. The population is named, the traits are
declared, the recipe is data and the seed already decided the order, so the
whole of this bead is a **projection** — the same claim `.2` made about the
feed itself, one turn in.

### The cost, decided: factor the prose, don't repeat it

The bead named the trade as always-on citations against `?explain=1`. The
answer is neither, and it falls out of looking at what a citation is made
of: the **recipe half** is one narration per LINE, identical for every card
that line admitted, and the **card half** is three numbers.

So the halves ride differently.

- **`recipe` rides every answer, always.** One narrated line per recipe
  entry, plus the four assembly checks as one sentence, plus what this read
  saw each line offered. It is paid once per read, not once per card.
- **`why` rides every card, always, and it is thirty bytes** —
  `{"line": 1, "rank": 2, "of": 19}`. The line is the thing that cannot be
  recovered any other way: this household writes TWO `next_actions` lines,
  so `section` and `population` do not identify which one admitted a card.
- **`?explain=1` is the prose.** `why.says` — the assembled sentences,
  including the kind's declared traits quoted back against this row. That
  is the half that would have repeated one kind's `:over` on every card of
  that kind, and it more than doubles a fuel card, which is mostly a
  sentence already.

**The law that makes the opt-in sound is the feed's own.**
`:feed/day-stable` says two reads by one member on one day answer the same
cards in the same order, so a citation fetched LATE lines up by `card_id`
and cannot be a different day's feed. That is exactly what the screen does,
and it is asserted rather than assumed (`:feed/citations`, claim 4). The
parameter rides `links.next` too: an explained read stays explained page
after page, or a reader who asked why would be handed an archive that would
not say.

`?explain=1` is a **read flag** and nothing else — same cards, same seed,
same order — which is also why it needs no refusal for a value it does not
recognise. There is nothing here to get wrong and no reason to refuse a
reader their own feed over a query string.

### The trait words are the declaration's own

```
-- do_now/media/8a3523a6-… | Show 5 · show · queued
   Recipe line 2 — Do now, three more: anything else the house goes to and has
     not finished — a chore run, a film, an errand — one kind at a time so no
     pile crowds the others out.
   media is a front door in this house — its declaration says :nav :primary —
     and do-now and fuel are made of front doors.
   media is mirrored, so its machine is the SYNC machine — fresh is a word
     about the last time this house and the authority spoke, never about the
     work. What ended lives in its data, which is what :over reads.
   media's :over reads its status field: finished is a deed, abandoned is let
     go. This row's status says queued, which is neither, so its work is not
     over.
   It kept its place in do now because it still has a verb light enough to tap
     — a next action with nothing under the thumb is a row on a list, and
     drops out.
   Drawn 2nd of 19 this line offered today, by (you, 2026-08-24)'s seed.
     Nothing was ranked against anything: the seed decides the order, and it
     decides once a day.
```

That is the movie, answered. **No prose was added to any declaration**, and
that was the point of writing the sentences this way: they are assembled
from the trait's own spelling — the field name, the enum words, the state,
the terminal set — so a kind that changes its `:over` changes its citation
without anybody remembering to update a sentence. `population-reads` is the
small map that says which traits a population consults (`:nav :machine
:over` for do-now, `:nav :over` for fuel, `:over` for the archive), and a
population that reads none of them is not a gap: `asks`, `letters`,
`ticklers`, `conflicts` and `insights` choose by a STATE their own kind
declares and by whose row it is, which the line's own sentence already says.

The one prose a household may write is **`:says` on a recipe entry** — the
line's own sentence, in the household's own words. It is free of any
fingerprint because the recipe is an **engine opt** and not a declaration,
and `workqueue10/main` uses it for exactly the two lines that encode a
decision the framework would never have inferred:

```
1. Do now, first two slots: the work queue. In this house the queue is what the
   morning is for, so two cards are the queue's before anything else is
   considered.   [13 offered, 0 claimed above, 2 shown]
2. Do now, three more: anything else the house goes to and has not finished —
   a chore run, a film, an errand — one kind at a time so no pile crowds the
   others out.   [19 offered, 13 claimed above, 3 shown]
…
11. The seam: That's the house, caught up. Exactly one card in the answer says
    that, and everything below it is history.
12. Archive, up to 6 cards: what this house was doing a year ago this week, and
    behind it everything that has moved.   [19 offered, 33 claimed above, 6 shown]
GUARANTEES: The sections always come in this order — do now, decide, fuel, the
seam, the archive; exactly one card is the seam; the archive is last and
bottomless; and every line names a population this engine actually holds. A
recipe that broke any of those would have refused to start rather than serve
you a surprise.
```

A line with no `:says` narrates itself from what it already carries — the
section, the take, its `:kinds`, and the population's own sentence — so the
recipe reads whole on any engine, household prose or not.

### The one cheap half of ABSENCE, and the rest is a punt

*Why is this card here* is a projection. *Why is some other row NOT here* is
a **search over everything** — every population's candidate set, every
line's `:kinds` narrowing, the mixer's seen-set, the seed's ordering, the
grant's `:row?` gate and each section's own filter — asked about a row the
reader has to name first. It is a different door with a different cost and
it is filed rather than built (**waymark-ck7**), with the grant hazard named
in the bead: an absence explainer is the natural place to break concealment,
which is supposed to be silent.

What DID ship is the half the seen-set makes cheap, and it is per LINE
rather than per row: every narrated line reports `claimed_above`, the count
of its own candidates a section above had already claimed. *The queue line
took all thirteen* is the most common absence question in this house, and
`[19 offered, 13 claimed above, 3 shown]` answers it without a search. It is
the mixer's total claim, said out loud for the first time.

### The screen

`Why this order` was already a disclosure over the document's `notes`; it
now **opens into the recipe** — the numbered lines, their counts, the
guarantees sentence, and the notes underneath, because a note is about this
READ and a line is about the order itself. Editing is explicitly not here
(**waymark-4yn**): the recipe is an engine opt read once at the route's
build site, so an edited recipe is a stored row and the `saved_view`
precedent's composition scaffolding, which is its own bead.

Each card grew a **`Why this card?`** disclosure that opens with something
true before any network happens — the line's own sentence, joined out of
`why.line` against the recipe the document already carries, and the size of
the draw — and replaces it with the server's sentences the first time
anybody actually asks. The explain read is fetched **once per page** and
cached by the href the cards came through, so an archive card on page four
is explained by page four's own read. **The page authors no prose and
derives no citation**: `.3`'s lesson about re-deriving a server decision in
the client is exactly the mistake a client-side "why" would be, so what the
client does is concatenate server strings.

### The obligation, and where the trait gets broken

**`:feed/citations`** is the pack's last READER, above the three that write.
Five claims: the recipe reads back narrated with its guarantees; every card
cites a line the recipe actually holds and a place inside a draw it was
actually part of; **the citation matches the layer that admitted it** (the
line agrees with the card about section and population, and a line dedicated
to particular `:kinds` is never cited by a card of another kind);
`?explain=1` answers the same cards in the same order; and the sentences
quote the declaration — a card whose population reads `:over` names `:over`,
and every citation reaches the seed. It reports `:covered`, because an
engine whose feed has no row card has proved nothing.

*Break a trait, the citation changes* could not be a conformance claim —
the pack judges whatever an application declared and may not edit it — so it
is `feed_test.clj`'s, with a **fixture kind declared twice**:
`break-a-trait-and-the-citation-changes` boots two engines whose only
difference is whether `fd_shelf` spells `:over`, and reads the same card out
of both. Spelled: *"fd_shelf's :over says done is a deed and shelved is let
go. This row is open, which is neither…"*. Unspelled: *"fd_shelf spells no
:over, so the machine's endings are the endings and every one of them counts
as finished."* A household's own kind is never edited to make a test go red.

`workqueue10/feed_shape_test.clj` — the world `.24` built — carries
`every-card-says-why-it-is-here`, which is where the movie case is asserted
against a house that actually has movies in it, and `:feed/citations` joined
the five readers that run there.

### Verified by hand, and one of `.7`'s own checks was wrong

`waymark10/scripts/feed-smoke.sh` grew two checks and reads **21**, no console
errors: *Why this order* opens into the numbered recipe with its guarantees
sentence, and a do-now card's *Why this card?* opens into the six sentences
above — printed into the run, so the walk is a record and not a memory.

Fixing a red run found a defect in `.7`'s **own** archive check, and it was
the check rather than the screen. The tail is two doors on one hinge — the
`IntersectionObserver` and the *Further back ↓* button — and on a page whose
sentinel starts in view the observer wins every race, so the button never
appears and *"0 page(s) followed off links.next"* was counting one door and
calling the other a failure. It now waits for the tail to settle and judges
what LANDED: nineteen archive cards against the six page one carried, with
`links.next`'s presence deciding which way the claim points. Same law, a
claim that can actually see it.

### Recorded here, for whoever comes next

- **An explained read pays one extra point read per card whose candidate did
  not already carry its row** (`memories`, mostly). It is per card SHOWN,
  never per candidate named, and only on `?explain=1`. If it ever matters it
  matters where waymark-iqa.16 is already watching.
- **`why.line` is a zero-based index into `recipe.lines`; the PROSE counts
  from one.** A reader counts lines from one and a client indexes from zero,
  and the two spellings must not be confused — `:feed/citations` asserts the
  prose against `(inc line)` for exactly that reason.
- **The citation's seed sentence says whose seed it is.** Under a preview
  *"you"* is a lie, so `:seeded-for` carries the previewed member's display
  name — the same correction the first note has always made.
- **No declaration moved and no fingerprint could.** `feed.clj`,
  `routes/feed.clj` and `packs.clj` are engine-side; the household's change
  is two `:says` strings on a recipe entry, and the recipe is an engine opt.
  `make check-queue` is unmoved at **32 kinds, 11 warnings, 16 scenarios**.
- **The recipe view is READ-ONLY on purpose and the editor is filed**
  (waymark-4yn). `check-recipe!` is already the write gate an editor would
  need, which is the half that is done.

## Built — 4yn, the recipe becomes a row (2026-08-24, waymark-4yn)

`.29` made the recipe **readable**: `GET /api/-/feed` carries `recipe`, every
line narrated in household words, and *Why this order* opens into it. Viewing
only, deliberately — and the moment a person can read the order, the next
thing they want is to change it. *Do not put films in do-now. Give the queue
three slots, not two.* Every one of those was a code edit in `main.clj` plus
a deploy.

It is a row now.

### The kind

`feed_recipe` — the saved_view precedent, followed line for line: a developer
declares the order once per deploy (`feed/default-recipe`, or the app's
`:feed` opt), and a `feed_recipe` row is the same shape authored at RUNTIME by
a person, with storage, forms, grants, events and transitions coming free.

```clojure
(defresource feed-recipe
  {:kind :feed_recipe
   :plural "feed_recipes"
   :states [:active :retired] :initial :active :terminal #{}
   :summary "{data.label} · {data.scope} · {state}"
   :schema [:map
            [:label …  [:string {:min 1 :max 60}]]
            [:scope …  [:enum "household" "mine"]]   ; whose morning
            [:owner …  [:maybe [:string {:max 128}]]] ; the ENGINE's stamp
            [:order …  order-schema]]
   :create-schema  …                                  ; label, scope, order
   :filterable {:state #{:eq :in}}
   :scenarios [an-agent-does-not-write-the-order
               a-person-writes-the-order
               a-feed-with-two-seams-is-refused]
   :on-create stamp-owner
   :create-guards [written-by-a-person the-assembly-checks-pass]
   :actions {:revise  {… :guards [written-by-a-person the-assembly-checks-pass]}
             :retire  {… :undo :restore :guards [written-by-a-person]}
             :restore {… :undo :retire  :guards [written-by-a-person]}}})
```

`order-schema` is one entry per recipe line, and the vector's order is the
feed's order — the same sentence `default-recipe` has always carried:

```clojure
[:vector
 [:map
  [:section …    (into [:enum] (map name) feed/census)]
  [:population … [:maybe (into [:enum] (map name) (sort (keys feed/populations)))]]
  [:take …       [:maybe [:int {:min 1 :max 50}]]]
  [:kinds  {:x-options {:from :kinds} …} [:maybe [:vector [:string …]]]]
  [:says   {:x-display {:widget "prose"} …} [:maybe [:string {:max 400}]]]
  [:sentence …   [:maybe [:string {:max 200}]]]   ; the seam's own words
  [:bottomless … [:maybe :boolean]]]]
```

Three shape decisions, each with a reason:

- **`section "seam"` IS the seam.** `line-of` sets `:seam true` from it, so
  nobody ticks a box beside a word that already says it — one fewer way to
  get a recipe half-right.
- **The two closed vocabularies are `:enum`s, not `:x-options` recipes.** The
  census and the population registry are literals a reviewer reads on one
  screen, so their legal words belong in the published schema itself rather
  than behind a fetch. `:kinds` is the one genuinely runtime vocabulary and
  wears the ordinary `{:from :kinds}` recipe. (The consequence is honest and
  wanted: adding a population now moves this kind's fingerprint, because the
  set of legal populations is part of its law.)
- **No `:salt` and no `:zone`.** Salt is the seed's input, and a recipe that
  could rewrite it would be a re-roll button — the ranking model arriving
  through the editor instead of through a query parameter. Zone is a fact
  about where the house *is*, not a taste the morning is tuned by; it stayed
  an engine opt, and the usability battery agreed from the other side (a
  free-text zone box is a blank rectangle judged against a vocabulary the JVM
  enumerates exhaustively).

**One kind, not two.** The dashboard/dashboard_slot pair was the obvious
shape for an ordered composition and it was refused: the four assembly checks
are properties of a WHOLE recipe — exactly one seam, the bottomless line last,
the census order — and a parent-plus-parts spelling would have had to
re-assemble the set at every door of both kinds to ask them. One row is one
whole recipe is one atomic judgment, which is what lets `check-recipe!` move
to the doors *unchanged*.

### Enrollment: `:always`, on the `:feed` module

The kind rides `modules.clj`'s `:feed` entry, which grew its first `:enrols`
column. `:always` rather than saved_view's `:app-opt-in`, and the difference
is whose vocabulary is being composed: a saved view names APP kinds and an app
may reasonably not want the surface at all, while a feed recipe names this
module's own census and its own population registry. An engine that serves
the feed serves the feed's recipe; there is nothing left to opt into.

### The resolution, and the cache decision

`feed-recipe/for-reader`, once per read:

1. the reading member's own ACTIVE row (`scope "mine"`, `owner` = the
   principal id), newest first;
2. the household's ACTIVE default (`scope "household"`);
3. the engine opt — the **built-in**, which stays the ultimate fallback so an
   engine holding no rows at all still serves.

Under a preview it is asked about the **previewed member**, not the previewer,
for the same reason the visibility is theirs: a preview computed through the
reader's own order would be a preview of a feed nobody has.

**No cache, and the trade is recorded.** The day-stable law would make a
per-day memo safe against the *seed*, but a recipe is edited in the middle of
a morning and the whole point of this bead is that the next read shows the
change. A cache measured in hours would make the editor feel broken. What it
saves is one indexed row read beside the dozen population queries the same
request already runs.

**No singleton guard, either, and that one bought something.** Two active
rows in one scope do not fight in the dark — the newest wins and the STAMP
names it by id and version — and a singleton would have had to read storage,
which would have dropped this kind's whole create door out of the no-database
check tier. The agent wall is a sentence worth proving where the author looks.

`for-reader` also re-judges the stored row and **degrades leniently** (the
saved_view render tradition): a redeploy that retires a population strands a
row, and a stranded row must not take the morning down with it. It is skipped,
the next recipe down answers, and the stamp says which row was skipped and why.

### The four assembly checks, at the doors

`the-assembly-checks-pass` calls `feed/check-recipe!` — the same function,
unchanged — on `recipe-of` the input, and denies with the sentence it already
knew:

> *exactly one entry carries :seam true, found 2 — a feed with no seam never
> finishes, and a feed with two says 'that's everything' twice*

The build-time check stays exactly where it was, because the built-in is still
a DECLARATION and a broken one should still refuse the boot. An invalid recipe
therefore cannot be stored, and the feed cannot break at read time from a bad
row.

### The third-law wall

```clojure
(g/defguard written-by-a-person
  {:reads [:principal]
   :explain "The feed's order is written by a person. An agent that could
             rewrite the recipe would be a ranking model editing its own
             editorial frame — publish an insight instead, with its citations
             and its one next step, and a member answers it with a tap."}
  [_row _inp ctx]
  (if (= :agent (:type (:principal ctx))) (t/deny) (t/allow)))
```

It stands at create, revise, retire and restore. A pure function of the
principal's kind, so the render probe and the real invoke read the same fact
and no probe path opens a door. `:system` is deliberately not walled — that
is the engine's own actor (a migration, the conformance walker), not the
composer this wall is about.

The refusal **names the lawful path**, which is the half that makes it
usable: an agent that wants the order changed publishes an `insight`, with
citations and one physical next step, and a member answers it with a tap. The
sentence is asserted, not just written.

**Four-eyes on the household default: NOT built, and the reason is
`waymark-l81`.** A second-adult approval was weighed. Against it: the change
is reversible through the row's own doors, visible to everyone on the next
read (the stamp names it), and the wall a role would need cannot be assigned
to anybody today because `assign_roles` is broken — a role wall here would be
a wall against everybody, which is a wall against fixing your own morning
before the other adult is awake. Filed rather than faked (**waymark-pcr**).

The other half of that wall is filed too: the refusal points an agent at the
insight path, and that path is honest but only half-built — an insight's offer
cannot carry the PROPOSED ORDER as the input a member one-taps, so today the
member reads the finding and retypes the order (**waymark-xw3**).

### The stamp

`recipe.source` rides every feed document:

```json
{"source": "household", "label": "The school-run morning",
 "self": "/api/feed_recipes/01HZ…", "id": "01HZ…", "version": 3,
 "says": "This house's own order answered this read: …"}
```

…or `{"source": "built-in", "says": "No stored recipe answered this read…"}`.
The `says` sentence joins the document's `notes`, so a mid-day edit is visible
and *explain* stays truthful about **whose** order it is narrating. The row's
transitions are the tuning history — every edit, who made it, when — and the
way back is `revise` or `retire`.

### Seeding: no row until the first edit

Decided, and the reasoning is the honest one: **the built-in IS the household
default**, and a row is a deliberate override. A boot-time seed
(`ensure-capabilities!`'s precedent) would have manufactured a row nobody
asked for, made every deploy's recipe change a merge question, and put a
household's order one bad migration away from an empty table.

What that owes is a starting point for the first edit, and it is paid in the
document rather than in a door: `recipe.order` is the order **in the editor's
own shape** — `line-of`'s inverse — beside the narrated `lines`. Create-from-
current is *read the order you have, edit one line, post it back*. No new
door, no prefill machinery, and the starting point is the order actually in
force rather than whatever the framework happened to ship. The `:examples`
placeholder on the field is a legal four-line recipe, for the case where even
that document is not in front of you.

### The editor is the generic form, and one thing was missing

`waymark-7rw` already taught the generic client to draw a list-of-entries as a
JSON textarea with a row of `x-options` chips per item field. What it could
not do was offer the words a DECLARATION already knows: an item property with
an `:enum` got a blank textarea and a memory test. `itemEnumFields` closes
that in `170-forms.js` — an enum'd item field earns the same row of chips,
with nothing to fetch, because its legal words are already in the schema in
front of the form. Section and population are pickers now; `kinds` fetches
its chips the old way; `says` and `sentence` stay free prose.

No bespoke editor was built and none is filed: the generic form does this.

### Verified by hand, and the walk is written down

`ui-drive.mjs` grew a fourth mode — **`node waymark10/scripts/ui-drive.mjs
recipe`**, against `make dev-queue` on :8014 — so the next person takes the
same walk. **19 checks, no console errors.** It needs no seeding, because the
observable is the seam's own sentence: the one recipe field that reaches a
card verbatim, so what a population happens to hold today never enters the
claim. It retires its row before it returns.

What the walk actually saw, in the browser:

- the create form's `order` box is the JSON textarea, with **three rows of
  chips** under it — *Section · 5* and *Population · 11* offered straight out
  of the published schema with no fetch at all, *Only these kinds · 24*
  fetched the ordinary `x-options` way;
- the order was filled by pasting the document's own `recipe.order`, one seam
  sentence changed — create-from-current, with no door built for it;
- **Create** landed the row, and the very next `GET /api/-/feed` read in it,
  with `recipe.source` naming the row by id and version;
- **Revise** on the row's own screen prefilled the order it was editing, and
  the read after it carried the new sentence and a bumped version — a mid-day
  edit lands mid-day;
- **Retire** put the deployment's own order back, seam sentence and all.

One honest note from the walk: an **unleashed** agent's create is answered
**404**, not the guard's 409 — the router's default deny conceals the
collection before any guard runs, which is correct and proves nothing about
the recipe. That is exactly why the pack mints the composer a `feed_recipe`
write grant first; the wall this bead is about only becomes visible once the
concealment is out of the way.

### Where the law is proved

- **`:feed/recipe-is-a-row`** (the pack, and it runs LAST of all the feed
  obligations — it is the only one that changes the ORDER ITSELF, so every
  claim above it reads a feed under the deployment's own recipe; it retires
  its row before it returns). The seam's sentence is the observable, because
  it is the one recipe field that appears verbatim on a card. It asserts:
  the built-in answers when no row is stored; `recipe.order` is non-empty;
  a created household row changes the next read; the stamp names it by id and
  version; a revise lands on the read after it (no cache); an AGENT's create
  is refused **by `written-by-a-person`** and the refusal names the insight
  path; a two-seam recipe is refused **by `the-assembly-checks-pass`**; and a
  retire puts the deployment's own order back.
- **Three scenarios**, in the check tier, with no database at all:
  `an-agent-does-not-write-the-order`, `a-person-writes-the-order`,
  `a-feed-with-two-seams-is-refused`.

### One thing this bead found: enrolled kinds' scenarios were judged nowhere

`waymark10.check` walked only the application's own resources, and the
`:core/law-scenarios` obligation deliberately skips whatever the check tier
can judge for free (*"re-running them here would be the same evaluator
answering the same question twice"*). Both halves were right, and the gap
between them swallowed `approval_request`'s four-eyes scenario — core's own
most-quoted wall — and every other framework scenario written the same way.

The question was already filed — **waymark-442.8**, which asked for the
decision to be made *on its own line rather than as a side effect*. It became
a side effect anyway, and the reason is worth recording: this bead's most
load-bearing wall is an actor-type refusal on an ENROLLED kind, its scenario
is storage-free by construction, and the one place it belonged was the one
place that would not read it. The decision is written up where the punt was,
in `docs/spec-law-scenarios.md`.

`check.clj` now judges an enrolled kind's scenarios too, and prints the kind
when it has either a battery warning or a declared law. `make check-queue`
goes from **16 to 21 scenarios judged** with no new law: three are this
bead's, and two are `approval_request`'s, finally read where the author looks.
The burndown it exposed — most enrolled framework kinds have written no wall
down at all, `member` alone carrying five refusing guards nobody has stated as
a sentence — is **waymark-a2b**.

### Recorded here, for whoever comes next

- **A new kind is a new table.** Production needs `feed_recipes` created
  before the deploy that serves it — `make migrate-queue-prod` prints the
  plan, and a person runs it through `nomad alloc exec … psql`. Nothing here
  touched production.
- **`recipe.order` and `recipe.lines` answer different questions.** `lines`
  is prose plus this read's own counts, for a person asking *why is this card
  here*; `order` is data a person copies into the form. Neither substitutes
  for the other, and a client that renders `order` as prose has read the
  wrong key.
- **Adding a population moves `feed_recipe`'s fingerprint.** The population
  enum is part of this kind's declared law, which is correct and worth saying
  out loud: a new population is a law revision, and `boot-revise!` will write
  one.
- **Reverting is copy-and-paste.** `:revise` records its whole authored
  surface, so the transitions log carries every order that was ever written —
  which is the tuning history the kind's prose promises. Taking one back is
  reading it out of the ledger and pasting it into the revise form. A one-tap
  way back belongs to the LEDGER rather than to this kind (**waymark-by4**).
- **A member's own recipe is household-visible, not own-surface.** The bead's
  design said own-surface for `scope "mine"` rows; workqueue10's household
  kinds are shared among humans and reached by agents only through the
  ordinary grant machinery (main.clj says so of weather), and the wall that
  actually matters — you cannot rearrange somebody else's morning — is the
  engine's `owner` stamp, not concealment. Recorded as a deviation.

## Built — 0k4, the staged proposal (2026-08-25, waymark-0k4)

`4yn` put a wall at `feed_recipe`'s doors — *the order the feed is read in is
written by a person* — and named the lawful path for an agent that disagrees:
**publish an insight**. That path is honest and it was half-built. An insight
carries prose and an address; a member who agreed with a finding still had to
re-type the order by hand into a form, from a page that was not showing them
the change.

A proposal closes it. The agent stages the **exact** revision as a row; the
household reads a diff on a decide card; **one tap applies it, through the
recipe's own door, with the member's name on the transition.**

The wall did not move. Not one line of `feed_recipe`'s declaration changed.

### The kind

`recipe_proposal` — framework-side, enrolled `:always` by the `:feed` module
beside `feed_recipe` itself, because it names that kind's own doors and this
module's own census. An engine that serves the recipe serves the way to
propose changes to it; the asymmetry below is the wall's other half, and half
a wall is not a thing to opt into.

```clojure
(defresource recipe-proposal
  {:kind :recipe_proposal
   :plural "recipe_proposals"
   :states [:offered :applied :declined :expired]
   :initial :offered :terminal #{:applied :declined :expired}
   :schema [:map
            [:proposal …]        ; one sentence, in the household's words
            [:label …]           ; what the order will be called
            [:target_id …]       ; the feed_recipe row — EMPTY means the built-in
            [:current_order …]   ; the order this was staged against
            [:order …]           ; the order proposed in its place
            [:evidence …]        ; what was read
            ;; engine-written, all four, and none of them anybody's to supply
            [:diff …] [:proposed_by …] [:decided_by …] [:applied_to …]
            [:expires_at …]]
   :on-create stage-the-proposal
   :create-guards [the-prepared-input-fits-the-door
                   the-order-will-assemble
                   it-cites-what-it-read
                   the-staging-is-current
                   staged-changes-are-few]
   :actions
   {:apply   {:from #{:offered} :to :applied
              :guards [the-proposer-does-not-decide a-person-answers
                       the-leash-has-not-run-out the-order-has-not-moved]
              :handler apply-the-order
              :touches [{:kind :feed_recipe :action :revise}
                        {:kind :feed_recipe :action :create}]}
    :decline {:from #{:offered} :to :declined
              :guards [the-proposer-does-not-decide a-person-answers]}
    :expire  {:from #{:offered} :to :expired
              :guards [the-leash-has-run-out]}}})
```

`:label` and `:order` are declared with **`feed-recipe/order-schema` itself**,
and `the-prepared-input-fits-the-door` validates them against
`feed-recipe/recipe-input` — literally the value `:revise` takes. That var was
`^:private`; it is public now, which changed no declaration and moved no hash.
The point is that the schema a proposal is judged against cannot drift away
from the door it is about, because it *is* that door's schema.

### The asymmetry, said out loud

An agent may create one of these and may not create a `feed_recipe`. That is
not a loophole in the wall; it is what the wall is for. A proposal changes
nothing until a member says so, and the member sees in full what they are
saying yes to. Put the other way round: `recipe_proposal` is **grantable** at
the MCP door precisely *because* holding that grant confers no power over the
feed's order.

```
{:kind "recipe_proposal" :actions ["create"]}
```

The kind is not one of the private own-surface trio (`self`, `journal`,
`letter`), so `scope-omits-private-kinds` admits it; the composer stages
through `waymark_invoke {kind: "recipe_proposal", action: "create", …}` like
any other leashed write. The obligation mints the composer **both** leashes —
proposals *and* recipes, the careless pair a household might actually approve
— and the claim it makes is that the second one buys nothing.

An agent **needs** that grant, and this is where the kind departs from the
insight precedent on purpose. `insight`'s own-surface carries `create`, so an
unleashed agent may publish a finding and the daily cap is the only wall. A
finding is a sentence the household reads; a proposal is a prepared WRITE a
member enacts with one tap, and which agents may put one of those in front of
the house is a decision the house should get to make. So `recipe_proposal`'s
own-surface is **read-only** — `{:by :proposed_by :actions #{}}` — the stager
reads back what it staged (else it cannot tell an applied change from a
declined one, and stages it again tomorrow) and nothing more. Humans are
unscoped and stage without a grant, as they always could.

### The apply IS the member's write

```clojure
(defhandler apply-the-order
  [row _inp ctx]
  (let [input {:label … :order …}
        res (if target-id
              (let [target ((:read ctx) :feed_recipe target-id)]
                ((:invoke ctx) :feed_recipe target-id :revise input
                 {:if-match (inv/etag :feed_recipe target-id (:version target))}))
              ((:create ctx) :feed_recipe (assoc input :scope "household")))]
    …stamp decided_by and applied_to…))
```

`ctx :invoke` and `ctx :create` carry the **outer** principal
(`server/invoke`'s `make-ctx` — the finding `waymark-iqa.6` recorded), so the
recipe's transition names the member who tapped, the recipe's own guards judge
the write, and `written-by-a-person` passes for the honest reason: a person is
writing. The obligation and the unit test both read it off the audit trail
rather than off anything the write reports about itself.

Three things were decided here and each could have gone the other way:

1. **One transaction.** `grants/approval-effects!` was the available
   precedent and it is declined on purpose: it runs POST-COMMIT at the wire
   boundary, under a SYSTEM actor, and warns to `*err*` when its effect
   refuses. All three are wrong here — the actor has to be the member, an
   apply that landed nothing must not read as applied, and a refusal inside
   must roll the whole tap back.
2. **No deterministic idempotency key for the inner write.** The bead
   proposed one (`proposal-<id>`, the approvals precedent). It is unnecessary
   and would have been a second idempotency boundary inside one transaction:
   the verdict door IS the boundary, and a second tap meets a terminal row.
3. **The fence is supplied, not waived.** `feed_recipe`'s `:revise` declares
   an `:edit`, and *an edit implies the fence* (`resource.clj`), so the
   cross-write hands over the target's current etag exactly as an honest
   client would — the **worksheet's** own spelling
   (`worksheet/apply-invocations!`), which is the other place in this tree
   that applies a staged change through a target kind's own doors. This needed
   one framework line: `ctx :invoke` now passes `:if-match` from its opts. A
   cross-write that quietly waived the fence would be the one door in the tree
   where *"the resource changed since you read it"* stopped being asked.

### The diff, computed at staging and stored

`feed/order-diff` takes two orders and answers the sentences a person taps
under. It lives in `feed.clj` beside `line-says`, because narrating the feed's
own order is the feed's business, and it is pure — the same two orders answer
the same sentences in a test, in the obligation and in the house.

```
The order goes from 12 lines to 13.
Line 1 shows 1 card instead of 5 cards; is media's alone.
Line 6 shows 1 card instead of 2 cards.
Line 10 moves to decide; reads mail on your shelf you have not opened instead
  of the last thing each front door finished this week; shows 1 card instead
  of 2 cards.
Line 11, the caught-up line, reads "Everything the house had, and that is all.".
Line 13 is new: Archive, up to 6 cards: what this house was doing a year ago
  this week, and behind it everything that has moved.
```

It is **positional**, and that is not a shortcut: the vector's order IS the
feed's order, so line 2 is a place on the page and not an identity. Inserting
a line near the top reads as several lines moving, which is exactly what a
reader would see happen. An order that changes nothing says so out loud —
an empty list under a verdict button is the one thing a person cannot read.

The engine writes it at birth, in `:on-create`, so the sentence a person taps
under is the engine's reading of the two orders and never the stager's
description of it. That is why the `:decision` sugar is **not** spelled here,
and the reason is the sugar's own recorded limit in its own words: *"a
decision kind that needs an extra birth stamp has no spelling yet"*. This kind
needs two — the diff and the leash — on top of the stager's name. The
four-eyes wall is still `g/not-the-field`, the very guard `desugar-decision`
would have minted, so the law is the sugar's law and not a lookalike.

### Validated at staging; stale at the tap

A proposal that would be refused when somebody taps it is refused when it is
**staged** — the letter-addressing lesson, and the reason is household rather
than technical: a button that fails is worse than a button that was never
offered. Four walls judge the body and the world before a row exists; a fifth
paces it.

And the same fact is asked **again** at the tap, because the world moves in
between:

> The order changed since this was staged — /api/feed_recipes/01H… reads
> differently now than it did when this was staged. Re-stage against the
> current order and the diff will say what is true now.

It does not apply over the top. The diff a person read describes the world
they read it in; writing over somebody else's edit would make the tap mean
something it never said.

### The built-in is a target too, and it is production's target

Production today holds **no** `feed_recipe` row: the built-in IS the household
default until somebody deliberately overrides it (4yn's own seeding decision).
So a proposal with an empty `target_id` stages a **create** — scope
`household`, the proposed order — and its staleness question is the mirror
image: *has the house written its own order since?* If it has, the proposal
refuses and says where to re-stage, because applying would have thrown away
what the house wrote.

The card says which of the two worlds it is in, out loud, because they are
different proposals.

### The card

A `:proposals` population joins the decide section — one registry entry and
one line in `default-recipe`, together, the `.4`/`.5`/`.6` pattern — and it is
the first decide population to hand its card a `sentence` of its own. It has
to: a proposal's whole claim is WHAT CHANGES, and a summary line naming the
row cannot say it.

```
Staged against the order this deployment ships with — the house has written
none of its own yet. Line 11, the caught-up line, reads "Everything the house
had, and that is all.". 1 row behind it.

                                            [ Apply ]  [ Decline ]
```

Both verdicts are note-free and both are one tap — `waymark-iqa.4`'s finding
inherited whole: a `:note` makes a verdict a `recall` demand and
`feed/split-verbs` moves it off the card into `heavier`. The population also
drops the stager's own proposals from the stager's own feed, and lapsed ones
from everybody's, for the reasons `insights` and `ticklers` already gave.

### The leash

Seven days, engine-stamped at birth, not author-settable. Enforcement is live
at the door and at the read; `expire` is bookkeeping anybody may run once the
clock has passed, and no sweeper drives it — `grant`'s own recorded posture,
inherited rather than re-decided. The clock sweeper was considered and does
not fit: it maintains **derived facts**, not states.

`staged-changes-are-few` is the wall that keeps the decide section from being
filled: three waiting per principal, counted over rows the way the insight cap
is. It is the second of two walls on the asking, and they answer different
questions — the grant says WHICH agents may reach the staging door at all, the
cap says how often anybody may walk through it. A household with one trusted
composer still does not want its decide section filled.

### Where the law is proved

The tiers are read off the declarations, never chosen, and they fell in a way
worth recording. `apply` carries a wall that reads the house's own recipe
rows, so it is conformance tier — and a conformance-tier ACTION scenario
stages its row through the kind's own create door **as the walker**, which
would stamp the walker's name into `proposed_by` and make the four-eyes wall
answer about the wrong person. So:

- **Check tier (4 scenarios, no database):** the four-eyes wall and the
  person wall, both proved on `decline` — the same guard objects `apply`
  carries; the machine's own refusal of a second answer; and the leash's
  other side, on `expire`.
- **Conformance tier (2 scenarios):** the two staging refusals that fire
  *before* the world-reading wall, so what they claim is true whatever order
  the engine they meet is reading.
- **`:feed/staged-proposals`, the pack's new last obligation:** the whole
  apply path from the wire — a leashed composer stages, the same leashed
  composer's direct recipe write still refuses by name, the card lands in
  decide with the diff in its sentence, the stager and a second leashed agent
  are both refused, a member's tap lands it, **the recipe's transition names
  the member**, and a proposal whose target moved refuses. It ends where it
  began: the recipe it wrote retired, the proposal it left declined.
- **`recipe-proposal-test`:** what a driver with one world cannot arrange —
  a CLOCK the test holds, so the leash can be watched running out; both target
  shapes in one run, so the built-in case is not left to whatever the suite
  happened to leave behind; and the diff read as sentences.
- **And the obligation is pinned as having RUN.**
  `runtime-conformance-test` asserts `:feed/staged-proposals` is in the
  report's `ran` set with positive coverage, the same way the five runtime
  obligations are — because a `:needs` that quietly went unmet would be a
  green run over the bead's own sentence, and nothing else would have said so.

`make check-queue` goes from **21 to 25 scenarios judged**, battery warnings
unchanged at 11.

### Recorded here, for whoever comes next

- **A new kind is a new table.** Production needs `recipe_proposals` created
  before the deploy that serves it — `make migrate-queue-prod` prints the
  plan, and a person runs it through `nomad alloc exec … psql`. Nothing here
  touched production.
- **`feed_recipe`'s fingerprint did NOT move, and 4yn's note that it would
  was wrong.** The prediction was *"adding a population moves `feed_recipe`'s
  fingerprint — the population enum is part of this kind's declared law"*. It
  is not: `fingerprint-of` is a whitelist over `kind`, `machine`, `derived`
  and `storage`, and a kind's `:schema` is not in it. Adding `:proposals` to
  the population registry (and so to `order-schema`'s enum) leaves the hash
  byte-identical, proved by removing the value again and re-hashing. The only
  new fingerprint in this bead is `recipe_proposal`'s own.
- **The wall's sentence still says "publish an insight", and was left
  alone.** After this bead the lawful path for an *exact* change is a
  proposal, so the refusal arguably owes a second clause. Changing it would
  move `feed_recipe`'s judgment surface to say a sentence, and the insight
  path is still true — it is the path for a finding that has no exact revision
  behind it. Filed rather than done.
- **The two staging scenarios are a PERSON's mistakes, not an agent's.** The
  walls they name judge the BODY, so they say the same thing to whoever wrote
  it; who may reach the door at all is the leash's question and a different
  sentence. Written as an agent they would have met the router's own 404
  (an unleashed agent reaches nothing) and proved concealment instead of law.
- **`:open` is not a decoration.** Two of the staging walls judge SHAPE (an
  input against another door's schema, an address against its form) rather
  than a token set, so neither wears `:open` — which everywhere else in this
  tree means *the legal tokens are the registry's, one GET away*. Wearing it
  would have claimed a gap that is not there and earned an `effort-honesty`
  warning nobody could clear.
- **A conformance-tier action scenario cannot test a stamped-`by` wall.**
  The staging walker is not the scenario's principal (deliberately — *"a
  scenario that wants to say that says it as its own attempt"*), so any kind
  whose four-eyes field is written by `:on-create` must prove that wall on a
  door whose every guard is offline, or in a pack. Worth knowing before the
  next decision kind is written.
