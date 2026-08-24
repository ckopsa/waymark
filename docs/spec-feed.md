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
