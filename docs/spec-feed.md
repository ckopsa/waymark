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
