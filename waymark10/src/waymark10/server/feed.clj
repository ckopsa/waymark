(ns waymark10.server.feed
  "The feed: one sequential read of the house, mixed by a declared
  recipe, seeded by (member, day), and projected through the reader's
  own grant (docs/spec-feed.md, waymark-iqa.2).

  The household has a hundred rows of work and a person who opens the
  app, reads a table and closes it. Every fact is already a row; what
  is missing is an ORDER — history for fuel, the one next action under
  the thumb — and a SEAM that says *that's everything*, because a
  surface that never ends is a surface that never finishes.

  Almost nothing here is new mechanism. The populations are queries
  over rows and logs that already exist; the card body is
  `render/envelope-summary` with `:visibility` in its ctx-opts, the
  same projection a collection item already gets. Three things are
  genuinely new and all three are small: a RECIPE (static data, an
  ordering of populations), a SEED (a hash, so the order is stable
  within a day without storing anything), and a SEAM (one element in
  the answer that is not a row).

  ── THE DOCUMENT IS A FEED, NOT AN ENVELOPE ──

  `GET /api/-/feed` answers a dedicated document, the way `?as-of=`
  answers an `as_of` one. That is the escape from
  docs/spec-modularization.md's punt — *'a module that wanted to add an
  envelope key would be a core change wearing a module's clothes;
  refuse it'* — because `card_id`, `section`, `population` and
  `heavier` are keys of a NEW DOCUMENT TYPE, never additions to the
  wire's envelope. A module may mint a document; it may not widen the
  envelope, and this one does not.

  `history/row-as-of` refuses an envelope for a reason that does NOT
  apply here, and the difference is worth stating: an as-of read's
  verbs would be *today's doors probed against a historical document*,
  so a client whose first rule is 'follow the envelope's own href'
  would be misled. A feed card's SUBJECT may be historical — the
  archive's are — but its `self` href and its `actions` are the row AS
  OF NOW. Following a card's own href is correct, and client rule 1
  holds. So every card carries a real `envelope-summary` body and
  nothing here hand-rolls a projection.

  ── THE FOURTH LAW, AND THE ONE THAT COSTS ──

  *Every card is grant-projected through the reader's own surface —
  one endpoint, per-member worlds.* docs/spec-modularization.md records
  that a module's routes inherit the router's 404-a-scoped-request
  posture and `routes/law_sweep.clj` takes that exit out loud. The feed
  MAY NOT: a feed that refused every grant-scoped reader would serve
  nobody but the unscoped humans, and per-member worlds is the whole
  point. So it PROJECTS, and inherits spec-time-travel's one security
  clause verbatim — an as-of read must project through the SAME
  visibility, or time travel becomes a disclosure channel.

  The projection happens ONCE, in `card` below, and every population
  reaches the wire through it. Order matters and is a security
  property: project FIRST (`envelope-summary` conceals an ungranted
  action from `actions` AND `unavailable` alike), then partition. So
  `heavier` can never reveal a door the grant conceals; it only ever
  names doors the reader already holds, on a screen where they do not
  fit. `split-verbs` reads the SURVIVING map and nothing else — it
  never consults the declaration, because the one thing it must not
  be able to do is recover an action the projection dropped.

  ── THE ≤-SELECTION RULE IS A PROJECTION, NOT A CHECK ──

  A card may only offer actions of effort ≤ selection; anything
  heavier links to the row's own screen. There is NOTHING to refuse at
  declaration time and no battery to extend: a kind's composition
  actions are legitimate on the row's own screen, and a
  declaration-time check would have to refuse law that is correct. So
  the projection IS the enforcement, and its proof is a conformance
  obligation over a live answer (`:feed/verbs-are-light`). The one
  place the rule is enforceable at a DOOR is `insight`'s declared
  `:offer_action` (waymark-iqa.6), because that is the one place a
  card's verb is declared rather than inherited.

  ── ACTIONS FROM THE FEED, IN THE AUDIT TRAIL ──

  The success metric is written into the audit trail rather than into
  an analytics table, and it costs no column: a client invoking FROM a
  card sends `Idempotency-Key: feed/<day>/<card_id>/<nonce>`, and
  `invoke/finish!` stamps that string into
  `waymark10_transitions.idempotency_key` whenever it is present. See
  `origin-key` for the spelling and `actions-from-feed` for the read.
  Recorded honestly as a smuggle: the key's declared job is replay
  identity and this reads it as provenance too.

  ── NO PUBLICATION WITHOUT CITATION ──

  The rule `insight` imposes at its create door binds this surface's
  own editorial choices (waymark-iqa.29): every card carries a `why`
  naming the recipe line that admitted it and where the day's seed
  drew it, the document carries the `recipe` narrated line by line,
  and `?explain=1` spells the whole citation out in sentences — the
  declared traits included, in the DECLARATION's own words. It is a
  projection like everything else here: the population is named, the
  traits are declared, the recipe is data, the seed already decided.
  See § 'the citation' below for the cost split and why the prose is
  the opt-in half. Explaining ABSENCE is not attempted and the reason
  is filed (waymark-ck7): *why is this card here* is a projection,
  *why is some other row not* is a search over everything.

  ── THE PERSON SPINS, AND THIS FILE NEVER DOES ──

  Law 6 (laws v3, waymark-8um): *the person spins; the system never
  spins for them.* The mechanism is one optional ingredient of the
  seed — a `draw`, a nonce the CLIENT mints on a tap — and everything
  else about it is a refusal. Nothing here invents a draw; nothing
  here suggests one; the document does not carry an affordance to
  spin, because a document that offered one on every read would be
  the system asking. With no draw the seed, the cards, the notes and
  the cursor are byte-for-byte what they were before the parameter
  existed, so the daily order is not the default draw by convention:
  it is the same hash. See `parse-draw`, `seed-of` and `document`'s
  `:draw`.

  ── THE CONTEST, AND WHY IT IS NOT A MODEL ──

  Law 5 (laws v3, waymark-8um.3): *the ranking formula is DATA the
  owner can read, never a hidden model.* The whole of it is two numbers
  on the recipe (`:formula {:window-days :cools-after}`) and one line
  of arithmetic (`cooling-step`): a card that has been on this
  reader's own feed N days without being acted on sits a step back in
  its own line, the seed still decides inside a step, and a card
  nobody has been shown is FRESH — unseen, never unloved.

  Three walls, and none of them is a promise:

  - It reorders and never filters. The step is a sort key between the
    lane and the hash, so a line shows exactly as many cards as its
    `:take` says whatever the view data. The exposure floor (law 3)
    therefore cannot be starved by arithmetic that has no way to drop
    a card.
  - It touches three sections and never the others
    (`contested-sections`, read off laws 2 and 3, and not a recipe
    field). The crown's floor and everything waiting on an answer are
    outside the contest by construction.
  - It reads the READER's OWN rows and nobody else's, and there are
    none until that member turned their own record on. Off is the
    default, so for a household that has said nothing the whole
    mechanism is inert and the document is byte-identical to what it
    was before this bead.

  ── NO DISCOVERY, TWICE ──

  The recipe is DATA and the populations are CODE, and neither is
  discovered. `populations` is a closed map a reviewer reads on one
  screen, for the reason `waymark10.modules` gives at length; the
  recipe is an engine opt read at the route's build site with its
  default, the spelling that namespace already uses for
  `:events-poll-ms` and the rest. If an implementation of any bead in
  this epic finds itself writing a scoring function, a per-card click
  counter or an 'engagement' anything, the bead is wrong and the
  epic's own paragraph is the citation.

  No seen state, ever. A card acted on falls out of its population on
  the next read by the deck's own rule; there is no dismissal row, no
  badge, nothing to sweep. The feed is a PULL — the addressed notice
  owns push, separately judged — and a surface that also poked people
  would be two products in one door.

  ── NOT AN MCP TOOL ──

  The spec's compiler contract settles this and the answer is no: an
  agent reaching for the feed is an external agent at the MCP door
  wearing `waymark_query` / `waymark_get` / `waymark_invoke` like any
  other leash. The six tools stay six. Nor can this door advertise
  itself on `.well-known` — the same wall spec-mcp-surface hit, the
  contribution table being closed at four on purpose — so the feed is
  found by a client knowing it exists, exactly as the MCP door is."
  (:require [clojure.string :as str]
            [waymark10.demand :as demand]
            [waymark10.server.collections :as coll]
            [waymark10.server.history :as history]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.problems :as p]
            [waymark10.server.render :as render]
            [waymark10.server.seasons :as seasons]
            [waymark10.server.store :as store]
            [waymark10.schema :as schema]
            [waymark10.summary :as summary]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.net URLDecoder URLEncoder)
           (java.nio.charset StandardCharsets)
           (java.time Duration Instant LocalDate ZoneId ZoneOffset)
           (java.time.temporal ChronoUnit)
           (java.time.zone ZoneRulesException)
           (java.util.concurrent CountDownLatch TimeUnit)
           (java.util Base64)))

(set! *warn-on-reflection* true)

;; ── the census ──────────────────────────────────────────────────────

(def census
  "The card census, top to bottom — the epic's own law, not a
  preference. outcomes (what this week could hold) → do-now (the next
  physical action) → decide (what is waiting on this reader) → fuel
  (history as momentum) → the caught-up seam → the archive interleave.
  A recipe that puts fuel above do-now is a typo, and `check-recipe!`
  says so rather than serving it.

  `:outcomes` is the newest member and the only one above do-now
  (waymark-jfv.4). It is the crown: the one place in this feed where a
  card is AUTHORED rather than projected, and everything beneath it
  stays the ledger it is audited against. Adding a member here widens
  `feed_recipe`'s `:section` enum for free — the enum is generated
  from this vector — and moves no fingerprint, because `:schema` is
  not one of `fingerprint-of`'s facets."
  [:outcomes :do_now :decide :fuel :seam :archive])

(def ^:private census-rank
  (into {} (map-indexed (fn [i s] [s i])) census))

;; ── the caps ────────────────────────────────────────────────────────

(def row-scan-cap
  "Rows one population scans per kind, newest first. The read-time
  posture (spec-feed fork (a)) has a cost and this is its bound: a
  population names CANDIDATES cheaply and only the handful that reach
  a page are ever loaded and rendered."
  100)

(def log-scan-cap
  "Transitions the archive folds for one read — `history/fold-cap`'s
  posture at a smaller number, and the same honesty beside it: scan to
  the cap and SAY the remainder is there, because truncation announced
  beats totality implied."
  500)

;; ── the recipe (static data) ────────────────────────────────────────

(def crown-and-floor
  "The outcomes section's own sentence, quoted verbatim from
  docs/spec-outcome-menu.md § 'The outcomes section — the crown, and
  its floor' (waymark-jfv.1 wrote it so waymark-jfv.4 could quote it
  rather than re-derive it; `plan` reads `outcome` throughout, the
  mechanical substitution waymark-5c4 made).

  It is the SECTION's own sentence, so it rides `card-says`' section
  clause — beside do-now's and the archive's, which is where a
  section says the bargain it strikes — and every outcome card that
  is asked why it is here reads it back verbatim. It could not ride
  the recipe line's `:says` instead: that field is a household's own
  words in a form, capped at 400 characters, and a paragraph the
  framework wrote is not a household's sentence anyway. The line's
  `:says` carries the short version, in the shape the other lines
  wear."
  (str "The outcomes section is the one place in this feed where a card"
       " is AUTHORED rather than projected, and it sits on top because"
       " that is what it is for. Everything beneath it stays the"
       " incorruptible ledger — rows nobody wrote for effect — and that"
       " is precisely what makes it a fair audit of the crown. The floor"
       " exists so the contest can be MEASURED, not so the crown can"
       " escape it: an outcome that keeps being passed over is a"
       " diagnosis waiting to be written, and the sections below will"
       " still say, in the household's own rows, whether the week"
       " actually held it."))

(def default-recipe
  "The v1 recipe: the vector's ORDER is the feed's order, and there is
  no other ordering input anywhere in this design. `:take` is how many
  cards a population contributes to one page; a population with
  nothing to say contributes nothing and the seam moves up.

  Every population docs/spec-feed.md § 'The recipe is static data'
  sketched is here now, and each arrived with the bead that built the
  mechanism it names rather than ahead of it: check (1) below refuses
  a recipe naming a population the registry does not hold, so a bead
  adds its population AND its line here together. That is exactly the
  seam fork (a) promised — swapping one entry changes no other line.
  `:ticklers` (waymark-iqa.4) walked it first, the three fuel entries
  and `:memories` (waymark-iqa.5) walked it again, and `:insights`
  (waymark-iqa.6) walked it last.

  `:proposals` (waymark-0k4) is the newest line and it walked the same
  seam: an agent may not write the feed's order, so it STAGES the
  exact revision and the household's tap applies it — a decide card
  like every other, one population entry and one line here, together.

  `:outcomes` (waymark-jfv.4) is the newest line and the first one
  ever to arrive ABOVE do-now. Its `:take` is the exposure floor laws
  v3 law 3 asks for — guaranteed slots, so the contest can be
  measured — and NO CAP stands anywhere near it any more: the weekly
  cap at the create door left with waymark-1uv.3, and the crown's own
  rank (`default-crown-rank`) chooses which bundles fill the slots.
  What was always true stays true: a read-side WINDOW must not come
  here, because a filter in a population would hide what the rank
  should merely place last. The recipe has no word for a week and
  must not grow one; `:take` is per PAGE.

  `:memories` is the bottomless tail and `:events` is one of the two
  sources it reads (waymark-iqa.8): check (3) admits exactly one
  bottomless entry, so the archive's stand-in did not move down the
  page — it moved INSIDE the population that replaced it, and the
  archive got the anniversary read on top rather than a card fewer."
  {:salt "waymark-feed"
   :zone "UTC"
   :order
   [{:section :outcomes :population :outcomes :take 2
     :says (str "What this week could hold: composed bundles with the"
                " friction already paid, waiting on a thumb. This is the"
                " crown — the one authored card on the page — and its take"
                " is the exposure floor, so the ledger below stays a fair"
                " audit of it rather than a competitor for its place.")}
    {:section :do_now  :population :next_actions :take 5}
    {:section :decide  :population :asks         :take 3}
    {:section :decide  :population :letters      :take 3}
    {:section :decide  :population :ticklers     :take 2}
    {:section :decide  :population :conflicts    :take 2}
    {:section :decide  :population :insights     :take 2}
    {:section :decide  :population :proposals    :take 2}
    {:section :fuel    :population :cleared      :take 1}
    {:section :fuel    :population :streaks      :take 1}
    {:section :fuel    :population :finished     :take 2}
    {:seam true :sentence "That's the house, caught up."}
    {:section :archive :population :memories :take 6 :bottomless true}]
   ;; the contest's two numbers (waymark-8um.3, laws v3 law 5). They
   ;; ride the recipe because that is where a household can READ them,
   ;; edit them through the ordinary form, and be shown a diff before a
   ;; tap changes them.
   ;;
   ;; The crown's four (waymark-1uv.2) ride the same row under
   ;; `:crown-rank`, and are absent here on purpose: `crown-rank-of`
   ;; fills the deployment's own in, exactly as `formula-of` would for
   ;; a row that named no contest, and `default-crown-rank` is where
   ;; the numbers and their reasons are written once.
   :formula {:window-days 14 :cools-after 3}})

;; ── the contest: a formula the household can read (waymark-8um.3) ───
;;
;; Laws v3, law 5: *the ranking formula is DATA the owner can read — a
;; declared formula over view counts, never a hidden model.* What was
;; forbidden did not weaken, it got a name: a scoring function the
;; household CANNOT READ. So the whole of the contest is two numbers on
;; the recipe and one line of arithmetic (`cooling-step`), and every
;; card it touches says so in its own citation, in the household's
;; words, with the numbers quoted back.
;;
;; Laws 2 and 3 say WHERE it may operate, and that half is the
;; framework's rather than the household's — see `contested-sections`.
;;
;; And law 7 says whether it operates at all: the formula reads the
;; reader's OWN `feed_view` rows and there are none until that member
;; turned their own record on. Off is the default, so for a household
;; that has said nothing this whole section is inert and the feed is
;; the byte-identical document it was before this bead.

(def contested-sections
  "The sections the formula may weight, and the whole of the answer to
  *where does the contest operate*. It is read off laws 2 and 3 and it
  is deliberately NOT tunable — a recipe field for it would be a field
  that could put the letters into the contest.

  `:decide` is the obligations — an ask that expires, a conflict
  waiting on a verdict, mail on a shelf, a staged proposal — and law 2
  says they appear because they MUST. `:outcomes` is the crown, whose
  `:take` IS the exposure floor law 3 asks for: the contest's step is
  never its sort key. Since waymark-1uv.2 the crown has a RANK OF ITS
  OWN (`crown-key`), which reads the same view rows through the same
  `cooling-step` as one of four inputs — so what the reader has been
  shown does move a crown card, by a number the household wrote and
  the card quotes back, while the floor still shows every slot the
  take promises. The contest and the rank are two formulas because
  they answer two questions: *which of these do you keep scrolling
  past* is the contest's, *which of these is worth your Saturday* is
  the crown's. The `:insights` line inside `:decide` has the same
  arrangement since waymark-1uv.8 (`insight-key`): outside the
  contest, inside a rank of its own that reads the same rows — the
  one decide line that is the contest's output rather than an
  obligation, and its card says so.

  What is left is exactly where the owner's own case for adaptivity
  lives: do-now (which of the five you have already scrolled past four
  mornings running), fuel, and the archive — the boredom sink."
  #{:do_now :fuel :archive})

(def default-formula
  "The contest, as two numbers, and there is no third.

  `:window-days` — how far back the counting looks. Outside it a card
  is unseen again, because a card you scrolled past in June is not a
  card you are bored of in August.

  `:cools-after` — how many DAYS a card may sit on your feed untouched
  before it cools one step and sits behind the fresher cards in its own
  line. The steps keep accruing and the window bounds them on its own
  (fourteen days over three is four), which is why there is no third
  constant: law 5 asks for a formula a household can read, and a
  household can read two numbers.

  `:cools-after 0` turns the contest OFF and the recipe view says so
  out loud — a household's own way to say *the seed alone*, with no
  code edit and no deploy."
  {:window-days 14
   :cools-after 3})

(def view-scan-cap
  "View rows one read folds, newest day first. A recording member
  writes on the order of forty rows a day (docs/spec-feed.md § 'Volume,
  honestly'), so this holds a fortnight of one person's own looking
  with room over.

  Truncation fails toward SHOWING rather than burying, which is the
  only direction it could honestly fail: the rows dropped are the
  OLDEST days in the window, so a card reads as cooler by fewer steps
  than it has earned, never by more. The document says so when it
  happens, `history/fold-cap`'s posture inherited whole."
  2000)

(defn formula-of
  "The formula this recipe reads: the household's own numbers, with the
  deployment's filled in for anything it did not state. A row that
  names none inherits the built-in's — the same shape `:salt` and
  `:zone` already have — and the way to say *no contest* is
  `cools_after 0`, which is a number a person can see rather than a
  key they have to know to delete."
  [recipe]
  (merge default-formula (:formula recipe)))

(defn cooling-step
  "THE FORMULA, and this is the whole of it:

      step = seen ÷ cools-after, rounded down

  where `seen` is how many DAYS this card was on THIS reader's own feed
  inside the window. Zero when nothing has been shown — an unseen card
  ranks as unseen, never as unloved — and zero when the household set
  `:cools-after` to nothing.

  It is only ever an ORDER key. The step joins the sort behind the lane
  and in front of the hash, so a line still shows exactly as many cards
  as its `:take` says, the seed still decides inside a step, and there
  is no arithmetic anywhere that can drop a card."
  ^long [formula ^long seen]
  (let [after (long (:cools-after formula 0))]
    (if (pos? after) (quot seen after) 0)))

;; ── the crown's rank: a second formula the household can read ───────
;; (waymark-1uv.2, the epic 'Ranked, not capped')
;;
;; The owner's ruling: *it makes more sense to just rank them.* A cap
;; on WRITING protects a person's attention by proxy; a RANK protects
;; it directly. Until this bead the crown showed `:take` bundles by
;; the day's seed and its citation said *held by the floor … not
;; because it won anything* — true while two a week was the most a
;; composer could stage, and false the morning the cap came off
;; (waymark-1uv.3, which landed after this and never could before).
;;
;; So the crown now RANKS what it shows, and the rank is the same kind
;; of thing the contest below it is: DATA on the recipe row (six
;; numbers beside the contest's two), one line of arithmetic
;; (`crown-lift`), narrated on every answer (`crown-rank-says`), and
;; read back on every crown card with the numbers that placed it. Law
;; 5 holds for the same reason it holds one section down: a household
;; can read six numbers, and the moment this needs a model it cannot
;; read, somebody is building the thing the law forbids.
;;
;; THE FLOOR STAYS. `:take` is still a guaranteed slot; the rank only
;; chooses WHICH bundles fill it, and the seed still decides between
;; equals. Nothing here can drop a candidate, for the contest's own
;; reason: the lift is a sort key and never a filter.
;;
;; THE AGENT IS NOT THE RANK. The epic weighed three shapes for the
;; composer's part and ruled: an agent may TUNE these numbers through
;; a staged proposal (waymark-1uv.5), and may supply one readable
;; judgment as an input beside these (waymark-1uv.6: a score and a
;; sentence on a `ranking_note` row, read at `:judged`'s weight and
;; quoted on the card as the agent's) — never be the rank itself,
;; because the crown is the one place a person acts on a machine's
;; word and that word must be readable.

(def default-crown-rank
  "The crown's rank, as six numbers — the weights of the six inputs
  a household can argue with. The seventh input, *asked for*, is a
  TIER above all six and not a weight (see `crown-key`): no number a
  household writes may put the machine's initiative above a person's
  own request, because that is law 6 read at the crown.

  `:declared` — what serving a value a PERSON declared lifts a bundle
  over one serving a value an agent only observed (waymark-jfv.10's
  `observed` state, read off `value-standing`).

  `:cooled` — what each step the contest's own arithmetic says a bundle
  has cooled holds it back. The step is `cooling-step` over the same
  view rows, the same window and the same `cools-after` as everything
  below the crown, so the household's two contest numbers govern the
  crown's cooling too; this number only says how much a step weighs
  here.

  `:declined` — what the strongest quick word the house said about the
  line of thinking this bundle recomposes holds it back, PER RANK OF
  THE WORD: never this weighs four of these, not this way three, wrong
  piece two, wrong time one (`reason-weights`). One number rather than
  four, so the order of the words is law by construction and no edit
  can invert it.

  `:fresh` — what each day still left on a bundle's week lifts it, so
  a bundle nearer its lapse ranks lower than one the composer just
  staged.

  `:early` — what each day a RECOMPOSITION arrives before the day the
  house said it would hear that line of thinking again holds it back
  (waymark-1uv.10). The day is the person's own decline, stamped as
  `not_before` off the tickler's schedule — a week, three, two months,
  half a year, lengthening with `declined_count` — and until
  waymark-1uv.10 it was a WALL at the create door: the composer could
  not stage the recomposition at all. The verdict is real and is still
  honoured; it is honoured HERE now, as a number on the card, because
  a wall on writing was a proxy for this rank and law 4 wants the
  recomposition written (it is the diagnosis). Per DAY rather than per
  decline, and at twice `:fresh`, so the arithmetic is the mirror of
  freshness — every day early costs what two days left would lift —
  and a recomposition a week early sits below every fresh bundle
  serving a declared value while still standing on the page when the
  floor reaches it. `declined_count` is not a second multiplier: the
  schedule already spent it on the distance, and charging it again
  would be the house's verdict counted twice. Once the day has passed
  nothing here holds it, because that is what the house said.

  `:judged` — how far an AGENT'S OWN SCORE of a bundle may move it,
  either way (waymark-1uv.6, option M of the epic). The score is a
  `ranking_note` row: 0 to 1 and one sentence, written by an agent
  that read the bundle and cited what it read, never by the agent
  that staged it, and quoted on the card under the agent's own name.
  The formula reads it CENTRED — a score of 1 lifts the whole weight,
  0 holds the whole weight, ½ is silence, and a bundle nobody scored
  reads as silence too (`judged-lift`) — so an agent can say *not this
  one* as well as *this one*, and saying nothing is the middle. ONE
  by default, which is the epic's own sentence: a wrong judgment is a
  nudge, never a verdict. At 1 only a confident score moves anything
  and it moves it one place among equals — less than a day of
  freshness; a household that has learned to trust its agent's eye
  raises it through the same form as every other number here, and one
  that has not sets it to 0 without deleting a word the agent wrote.

  All six at zero is *the seed alone*, with a person's own request
  still first — a number a person can see rather than a key they have
  to know to delete, the contest's own posture."
  {:declared 10
   :cooled 2
   :declined 2
   :fresh 1
   :early 2
   :judged 1})

(def reason-weights
  "How much each of the household's four quick words weighs on the
  crown's rank, before the recipe's `:declined` number multiplies it.
  The ORDER is the epic's ruling — never this > not this way > wrong
  piece > wrong time — and it is the order `waymark10.verdict-reason`
  lists them in, read from the last word back: the four axes a decline
  runs along are EVER, HOW, WHAT and WHEN, and a house that said *never
  this* about a line of thinking has said the most a decline can say.

  Spelled here as tokens rather than read off the reason kind's enum,
  for the reason `reason-kind` is a keyword and not a require: this
  namespace reads kinds the module enrols without requiring the
  namespaces that declare them. A word this map does not know weighs
  ONE — any word said is at least *wrong time*, because the house
  turned the line down and said so.

  THE SECOND FOUR ARE THE FINDING'S (waymark-hcr), and they are read
  the same way: a dismissed CLAIM runs along WORTH, BACKING, NEWNESS
  and TRUTH rather than along WHEN, WHAT, HOW and EVER, so a house
  that said *not true* about a finding on a next step has said the
  most a dismissal can say about it, and *too thin* the least. Which
  four a subject may carry is `verdict-reason/reason-sets`; that this
  map holds both is the same posture as above — tokens, not a
  require."
  {"never_this" 4
   "wrong_way" 3
   "wrong_piece" 2
   "wrong_time" 1
   "untrue" 4
   "restated" 3
   "unfounded" 2
   "thin" 1})

(defn reason-weight
  "The weight of one quick word, or zero for none said."
  ^long [word]
  (if (nil? word) 0 (long (get reason-weights (str word) 1))))

(def crown-scan-cap
  "Offered bundles the crown ranks on one read, newest first. The
  population's cost is per CANDIDATE — a value read, a piece query and
  a walk up the supersedes chain — and since the cap on staging went
  (waymark-1uv.3) no create door keeps the number of offered bundles
  small, so the read bounds itself instead.

  Fifty is a week of a busy household's composing several times over:
  a bundle stands seven days (`good_until`), so the offered set is
  whatever every composer staged this week, and a house with fifty of
  those on offer at once has a composer to talk to before it has a cap
  to raise. A STORED score was weighed and refused for this bead: it
  would need a writer (the staging hook cannot know what the reader
  has been shown, and a sweeper would be the feed writing) and it
  would go stale the moment a view row or a reason landed. The bound
  is honest and cheap; the document says so when it is reached
  (`history/fold-cap`'s posture, `row-scan-cap`'s precedent).

  Truncation drops the OLDEST bundles, which is the fairest direction
  it could fail: those are the ones nearest their lapse, which the
  rank's own `:fresh` term already places last."
  50)

(def supersedes-chain-cap
  "How far up a bundle's `supersedes` chain the crown reads for the
  house's quick words. Five hops is half a year of recompositions on
  the tickler's own schedule (a week, three, two months, half a year);
  a line of thinking recomposed more often than that has been said no
  to more often than any word can add to."
  5)

(defn crown-rank-of
  "The crown's six numbers this recipe reads: the household's own,
  with the deployment's filled in for anything it did not state —
  `formula-of`'s shape, one field over."
  [recipe]
  (merge default-crown-rank (:crown-rank recipe)))

(defn judged-lift
  "What an agent's score does to a bundle at the recipe's `:judged`
  weight (waymark-1uv.6): the score, 0 to 1, read CENTRED on a half
  and scaled to the weight — `weight × (2·score − 1)`, rounded to the
  nearest whole number, half away from zero — so 1 lifts the whole
  weight, 0 holds the whole weight, ½ is nothing, and no score at all
  is nothing too. Whole numbers because the lift is one and the card
  quotes it; at the default weight of 1 only a score past ¾ or under
  ¼ moves anything, which is what *a nudge, never a verdict* means in
  arithmetic."
  ^long [^long weight score]
  (if (nil? score)
    0
    (let [x (* (double weight) (- (* 2.0 (double score)) 1.0))]
      (long (* (Math/signum x) (Math/round (Math/abs x)))))))

(defn crown-lift
  "THE CROWN'S ARITHMETIC, and this is the whole of it:

      lift = declared × [the value is declared]
           − cooled   × steps cooled
           − declined × weight of the strongest word on the chain
           + fresh    × days left on the week
           − early    × days before the house said it would hear it
           + judged   × (2 × the agent's score − 1)

  over one bundle's inputs — `{:value :declared|:observed, :cooled n,
  :declined word-or-nil, :days-left n, :early n, :judged {:score s
  :by who :says sentence}}` — and the recipe's six numbers. Higher
  stands higher. It is only ever a SORT KEY: the crown still shows
  exactly as many bundles as its `:take` says whenever that many
  exist, and there is no arithmetic here that can drop one — a
  recomposition the house said not to hear yet is COOLED by every day
  it is early (waymark-1uv.10), never hidden, and the card says so.

  The last line is the agent's (waymark-1uv.6, `judged-lift`): a
  score nobody wrote is silence, a score of a half is silence, and
  the weight is the household's to turn down to nothing.

  `:asked` is not in it, on purpose: a bundle that answers a person's
  own request is a tier above every uncited one (`crown-key`), and no
  weight a household writes moves it down."
  ^long [weights {:keys [value cooled declined days-left early judged]}]
  (let [w (fn ^long [k] (long (get weights k 0)))]
    (+ (if (= :declared value) (w :declared) 0)
       (- (* (w :cooled) (long (or cooled 0))))
       (- (* (w :declined) (reason-weight declined)))
       (* (w :fresh) (long (or days-left 0)))
       (- (* (w :early) (long (or early 0))))
       (judged-lift (w :judged) (:score judged)))))

(defn crown-key
  "One crown candidate's place in the order, as a vector `sort-by`
  reads ascending: the person's own request first, then the lift
  (higher first), then the seed's hash — so the answer is a pure
  function of (the recipe's numbers, this bundle's inputs, the seed)
  and two bundles the formula cannot tell apart are still placed by
  the day rather than by arrival."
  [weights inputs ^String hash]
  [(if (:asked inputs) 0 1)
   (- (crown-lift weights inputs))
   hash])

;; ── the findings' rank: the crown's shape, one section down ─────────
;; (waymark-1uv.8, the epic 'Ranked, not capped')
;;
;; The insight was the precedent the outcome cap copied — three a day
;; per author, *the cap is what makes a compiler rank rather than
;; dump* — and under the owner's ruling it is the same proxy: a wall
;; on the writer standing in for a rank on the reader. The insight IS
;; the indexing the ruling names; its write pushes nothing, mails
;; nobody, and its offer is an address that writes no other row. So
;; the `:insights` line now RANKS what it shows, in exactly the
;; crown's shape: six numbers on the recipe row (`insight_rank`), one
;; line of arithmetic (`insight-lift`), narrated on every answer
;; (`insight-rank-says`), and read back on every insight card with the
;; numbers that placed it (`why.insight`). `insights-are-capped` left
;; the create door in the same bead, AFTER the rank and never before —
;; with no cap and no rank the decide section would show two of two
;; hundred by seed.
;;
;; THE FLOOR STAYS. `:take 2` is still the exposure floor (law 3); the
;; rank only chooses WHICH findings fill it, and the seed decides
;; between equals. Nothing here can drop a finding.
;;
;; NO TIER. The crown has one because a person's own request is law 6
;; read at the crown; a finding is always the machine's initiative,
;; and there is nothing of a person's in the line to stand above
;; every number. Law 4's diagnosis is a WEIGHT (`:diagnosis`) rather
;; than a tier for the same reason: the duty is the system's, and a
;; household may turn a number down where it could not turn a tier.
;;
;; WHERE THE LINE STANDS. It is still in `:decide`, and it is still
;; outside the CONTEST (`contested-sections`), the way the crown is:
;; the contest's step is never its sort key, while its own rank reads
;; the same view rows through the same `cooling-step` as one input.
;; What changed is the sentence: `cooling-says` no longer tells an
;; insight card it is *outside the contest* as an obligation, because
;; it is not one — it is the contest's own output (law 4 calls the
;; diagnosis an insight). The section's OTHER citizens — an ask that
;; expires, a conflict, mail on a shelf, a change staged for a tap —
;; are still law 2's, and the insight arm says so out loud.

(def default-insight-rank
  "The findings' rank, as six numbers — the weights of the six inputs
  a household can argue with, in the order the arithmetic reads them.

  `:diagnosis` — what a finding that IS a law-4 diagnosis is lifted
  over a plain one: its next step is a value's or a person's own
  affirmation (`affirmation-doors`), or it offers a step on an
  outcome's own row, or it cites an outcome the house declined. Laws
  v3 law 4 — *the composer's duty fires first* — read as a number.

  `:declared` — what a finding whose offered row serves a value a
  PERSON declared is lifted over one whose row serves a value an
  agent only observed (waymark-jfv.10's `observed` state), or none.

  `:cooled` — what each step the contest's own arithmetic says this
  finding has cooled holds it back: `cooling-step` over the same view
  rows, the same window and the same `cools-after` as everything in
  the contest, so the household's two contest numbers govern the
  findings' cooling too.

  `:dismissed` — what each finding the house already DISMISSED on the
  same offer — the same `{offer_kind, offer_id, offer_action}` — holds
  this one back. The house's verdict record on the same question,
  counted (`dismissal-record-cap` newest).

  `:declined` — what the strongest quick word said on those
  dismissals holds it back, PER RANK OF THE WORD (`reason-weights`,
  the crown's own order: never this four, not this way three, wrong
  piece two, wrong time one). One number, so the order of the words
  is law by construction.

  `:fresh` — what each day of freshness still left in the contest's
  `:window-days` lifts it: a finding published today is lifted the
  whole window, one published two weeks ago is lifted nothing, and
  older than that is simply old — newer first among equals, bounded
  so an old finding sinks to the bottom and no further.

  All six at zero is *the seed alone* — a number a person can see
  rather than a key they have to know to delete."
  {:diagnosis 10
   :declared 5
   :cooled 2
   :dismissed 3
   :declined 2
   :fresh 1})

(def affirmation-doors
  "The doors whose offer makes a finding a law-4 diagnosis of the
  first kind: a value's or a person's OWN affirmation, the petition
  path waymark-jfv.2 opened and jfv.10 / jfv.11 kept as the one wall
  still standing against an agent — an observer marking its own
  reading confirmed would be speaking in the owner's voice, so it
  publishes a finding and offers this tap instead.

  Spelled here as keywords rather than read off the kinds, for the
  reason `reason-weights` gives: this namespace names kinds the module
  enrols without requiring them, and the two doors are the two kinds'
  own (`value/still_stands`, `person/still_with_us`). A third kind
  with an affirmation door adds its pair here."
  {:value #{:still_stands}
   :person #{:still_with_us}})

(def insight-scan-cap
  "Published findings the line ranks on one read, newest first —
  `crown-scan-cap`'s posture one section down, and the bound that
  replaces the cap at the door. The population's cost is per
  CANDIDATE — a subject read, a dismissal query and a reason query per
  prior dismissal, sometimes a value read — and since the daily cap
  went nothing at the door keeps the number of published findings
  small, so the read bounds itself instead. Fifty is more findings
  than a household answers in a month; the document says when the
  cap was reached, and truncation drops the OLDEST, which `:fresh`
  already places last."
  50)

(def dismissal-record-cap
  "How many prior dismissals on the same offer the rank reads, newest
  first. Five is a line the house has already said no to five times;
  the number counted stops there, and the word read is the heaviest
  among those five."
  5)

(def evidence-read-cap
  "How many of a finding's cited addresses the rank follows looking
  for a declined outcome. Eight is more rows than a card can honestly
  cite for one sentence."
  8)

(defn insight-rank-of
  "The findings' six numbers this recipe reads: the household's own,
  with the deployment's filled in for anything it did not state —
  `crown-rank-of`'s shape, one field over."
  [recipe]
  (merge default-insight-rank (:insight-rank recipe)))

(defn insight-lift
  "THE FINDINGS' ARITHMETIC, and this is the whole of it:

      lift = diagnosis × [the finding is a law-4 diagnosis]
           + declared  × [its offered row serves a declared value]
           − cooled    × steps cooled
           − dismissed × findings the house dismissed on the same offer
           − declined  × weight of the strongest word said on those
           + fresh     × days of freshness left in the window

  over one finding's inputs — `{:diagnosis kw-or-nil, :value
  :declared|:observed|nil, :cooled n, :dismissed n, :declined
  word-or-nil, :fresh-days n}` — and the recipe's six numbers. Higher
  stands higher. It is only ever a SORT KEY: the line still shows
  exactly as many findings as its `:take` says whenever that many
  exist, and there is no arithmetic here that can drop one."
  ^long [weights {:keys [diagnosis value cooled dismissed declined fresh-days]}]
  (let [w (fn ^long [k] (long (get weights k 0)))]
    (+ (if diagnosis (w :diagnosis) 0)
       (if (= :declared value) (w :declared) 0)
       (- (* (w :cooled) (long (or cooled 0))))
       (- (* (w :dismissed) (long (or dismissed 0))))
       (- (* (w :declined) (reason-weight declined)))
       (* (w :fresh) (long (or fresh-days 0))))))

(defn insight-key
  "One finding's place in the line, as a vector `sort-by` reads
  ascending: the lift (higher first), then the seed's hash — a pure
  function of (the recipe's numbers, this finding's inputs, the seed),
  and two findings the formula cannot tell apart are still placed by
  the day rather than by arrival. No tier: nothing in this line is a
  person's own request."
  [weights inputs ^String hash]
  [(- (insight-lift weights inputs))
   hash])

;; ── the seed ────────────────────────────────────────────────────────

(defn today
  "The recipe's own idea of what day it is: `(:now-fn eng)` read in the
  recipe's `:zone`. Midnight there is when the feed rolls."
  ^String [eng recipe]
  (str (LocalDate/ofInstant ^Instant ((:now-fn eng))
                            (ZoneId/of (:zone recipe "UTC")))))

(def ^:private draw-pattern
  "What a draw may be spelled with (waymark-8um.2). A draw is a NONCE:
  the client mints it, the seed hashes it, and nothing anywhere reads
  a meaning out of it — it is not a page number, not a count, and not
  a secret. So the only rule is that it stay a short safe token, long
  enough never to collide by accident and bounded so a megabyte of
  query string cannot arrive as an ingredient of a hash."
  #"[A-Za-z0-9._-]{1,64}")

(defn parse-draw
  "The `draw` parameter, read. Absent or blank is the DAILY draw and
  answers nil — the day's own order is what a reader who never taps
  gets, which is law 6's whole promise that the system never spins for
  anybody.

  A value this pattern refuses is a 422 naming the parameter rather
  than a quiet fall back to the daily order: a client that mangled its
  nonce and was handed the day's order twice would conclude that
  dealing again does not work.

  It lives here rather than at the door because the door is only a
  transport — a second one asking for a second draw asks this same
  question and gets this same answer."
  [s]
  (when-some [v (some-> s str str/trim not-empty)]
    (when-not (re-matches draw-pattern v)
      (throw (p/schema-invalid
              :query
              {"draw" [(str "must be 1–64 characters of letters, digits,"
                            " dot, dash or underscore — a draw is a nonce"
                            " your own tap mints, and leaving it off reads"
                            " the day's own order")]})))
    v))

(defn seed-of
  "sha256(salt ‖ member-principal-id ‖ local-date[ ‖ draw]). Same
  member, same day, same feed; midnight rolls it; two members see
  different worlds because the id is in the hash. It stores NOTHING,
  which is the whole of 'stable within a day' and the answer to the
  materializing job the spec declined to build.

  THE DRAW IS THE LAST INGREDIENT, AND IT IS OPTIONAL (waymark-8um.2,
  law 6: the person spins, the system never spins for them). A tap
  mints a nonce, the nonce joins the seed, and what comes back is a
  fresh order exactly as stable as the day's — same member, same day,
  same draw, same feed, page after page. With NO draw the string
  hashed is byte-for-byte the string this function has always hashed,
  so the daily seed is not the default draw by convention: it IS the
  same hash, and a reader who never taps reads the order they would
  have read before this parameter existed."
  (^String [recipe pid ^String day] (seed-of recipe pid day nil))
  (^String [recipe pid ^String day draw]
   (wire/sha256-hex (str (:salt recipe "waymark-feed") "\u001f" pid "\u001f" day
                         (when draw (str "\u001f" draw))))))

(defn- rank
  "A candidate's place in the day's order: hash(seed ‖ card_id). Every
  population that must choose k of n sorts by this and takes the
  first k — no scoring function anywhere, and none is admissible."
  ^String [^String seed ^String card-id]
  (wire/sha256-hex (str seed "\u001f" card-id)))

(defn- card-id
  "A card's identity within the day. The client keys on THIS, never on
  `self`, because the seam has no row — and that one difference is why
  `ui/134-feed.js` cannot be reused unmodified."
  ^String [section kind id]
  (str (name section) "/" (name kind) "/" id))

(defn card-ids
  "Every card id ONE ROW can wear — one per census section, the seam
  excluded because nothing rows there (waymark-8um.4).

  A view row is keyed by the card id the screen actually showed, and
  the section half of that id is the household's recipe to say: the
  built-in cards the crown under `:outcomes`, but a stored
  `feed_recipe` may line the same population up under another band,
  and nothing in `check-recipe!` forbids it. So a reader asking *was
  this row ever on a screen* — the composer's diagnosis, and the wall
  that reads it — asks under every section rather than guessing one,
  and the unique index on `(card_id, day, member)` makes each of the
  five asks a prefix scan. `outcome-card`'s posture in the conformance
  pack, one register over: which band the line sits in is the
  recipe's, and the claim that it is `outcomes` is made separately."
  [kind id]
  (into [] (comp (remove #{:seam}) (map #(card-id % kind id))) census))

;; ── the cursor ──────────────────────────────────────────────────────

(defn encode-cursor
  "One opaque base64 token over {:day :seed :offset} — and `:draw`
  where a draw is riding (waymark-8um.2). Opaque because a client that
  could edit the SEED could re-roll its own feed until it liked the
  order, which is the ranking model coming in through a query
  parameter. That reason survives law 6 intact and is worth saying
  again now that re-rolling is a legitimate tap: a person may deal
  again as often as they like, and what they get is a whole fresh
  draw, honestly labelled, from the top. What nobody gets is a
  half-draw — page four of one order spliced onto pages one to three
  of another — and that is exactly what an editable seed would buy.

  THE CURSOR CARRIES ITS DRAW, so a page continues the draw it came
  from even if the client drops the query parameter on the floor. A
  cursor minted under the daily draw carries no `draw` key at all and
  is byte-identical to the token this function has always minted."
  ^String [{:keys [day seed offset draw]}]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (.getBytes (wire/write-json
                               (cond-> {"day" day "seed" seed
                                        "offset" (long offset)}
                                 draw (assoc "draw" draw)))
                              StandardCharsets/UTF_8)))

(defn decode-cursor
  "The token, read back. A token this engine did not mint is a 422 that
  names the parameter rather than a silent fall back to the top of the
  feed — a cursor that quietly answered page one would make deep paging
  look like an infinite loop of the same six cards.

  A token with no `draw` is a page of the DAILY draw, which is what
  every cursor this engine minted before waymark-8um.2 is — so old
  tokens read exactly as they always did, and the absent key means the
  same thing here that an absent parameter means at the door."
  [^String s]
  (let [bad (fn [] (throw (p/schema-invalid
                           :query
                           {"cursor" [(str "must be a cursor this engine"
                                           " minted — follow links.next"
                                           " rather than composing one")]})))]
    (let [m (try (wire/read-json
                  (String. (.decode (Base64/getUrlDecoder) s)
                           StandardCharsets/UTF_8))
                 (catch Exception _ (bad)))]
      (when-not (and (map? m) (string? (:day m)) (string? (:seed m))
                     (int? (:offset m)) (nat-int? (:offset m))
                     (or (nil? (:draw m))
                         (and (string? (:draw m))
                              (re-matches draw-pattern (:draw m)))))
        (bad))
      (cond-> {:day (:day m) :seed (:seed m) :offset (long (:offset m))}
        (:draw m) (assoc :draw (:draw m))))))

(defn rolled
  "The refusal a stale cursor earns. Serving yesterday's seed today
  would be a second definition of 'stable within a day', so the day is
  named in the sentence and the client re-reads from the top."
  [was now]
  (p/problem :feed-rolled 409 "The feed rolled"
             {:detail (str "That cursor is " was "'s feed and today is " now
                           " — the feed rolls at midnight and never serves"
                           " yesterday's order. Read /api/-/feed again from"
                           " the top.")
              :day now}))

(defn draw-mismatch
  "The refusal a cursor from ANOTHER draw earns beside an explicit
  `draw` (waymark-8um.2). The two halves of one request disagreed
  about which order is being walked, and there is no honest way to
  guess: honouring the parameter would serve page four of the tapped
  draw at an offset counted in the cursor's, and honouring the cursor
  would answer a page of an order the caller did not ask for.

  It is a 422 and not the roll's 409 because nothing moved — every
  `links.next` this engine mints carries both halves and carries them
  agreeing, so a request in which they differ was composed by hand."
  [cursor-draw asked]
  (p/schema-invalid
   :query
   {"draw" [(str "this cursor walks the " (or cursor-draw "daily")
                 " draw and the request asks for the " (or asked "daily")
                 " one — one read, one draw. Follow links.next, which"
                 " carries both and carries them agreeing, or read"
                 " /api/-/feed again from the top with the draw you want.")]}))

;; ── the reader's own row reads ──────────────────────────────────────

(defn- resources [ctx] (inv/resources (:eng ctx)))

(defn- rows-of
  "One kind's newest rows matching an equality map, capped. The raw
  store row: a candidate is a NAME, and only the handful that reach a
  page are ever decoded and rendered."
  ([ctx kind where] (rows-of ctx kind where row-scan-cap))
  ([ctx kind where ^long limit]
   (let [st (:storage (:eng ctx))]
     (try
       (store/with-tx st
         (fn [tx] (store/query-rows st tx kind where
                                    {:limit limit :newest-first true})))
       ;; a kind in the registry whose table this engine never made is
       ;; an assembly the feed does not get to fail over: the population
       ;; contributes nothing and the seam moves up
       (catch Exception _ [])))))

;; ── the view door's own two names (waymark-8um.1) ───────────────────
;;
;; Named here as KEYWORDS and nowhere else, the way `insight`,
;; `tickler` and `recipe_proposal` are already named by the populations
;; above: this namespace reads kinds the feed module enrols without
;; requiring the namespaces that declare them, because
;; `waymark10.feed-view` requires nothing from here and must be free to
;; require this file later if it ever needs to.

(def view-consent-kind
  "The per-member switch that decides whether a screen may report what
  it showed (waymark10.feed-view)."
  :feed_view_consent)

(def view-kind
  "The record a screen posts when the switch is on."
  :feed_view)

(def note-kind
  "An agent's score and sentence about a ranked row
  (waymark10.ranking-note, waymark-1uv.6) — the crown's sixth input,
  named here by keyword for the reason kind's own reason."
  :ranking_note)

(def reason-kind
  "The row a SETTLED card posts when somebody taps one of the quick
  reasons after a decline (waymark10.verdict-reason, waymark-jfv.16).
  Named here as a keyword for the same reason the two above are: this
  namespace reads kinds the feed module enrols without requiring the
  namespaces that declare them."
  :verdict_reason)

(defn- collection-of
  "The address of a kind's collection, or nil when this engine holds no
  such kind. Read off the declaration's own `:plural` rather than
  spelled here, so the one place a plural is decided stays the one
  place."
  [ctx kind]
  (some->> (get (resources ctx) kind) :plural (str "/api/")))

(defn recording?
  "Has this reader turned their own view record on? One indexed read
  per feed, and the answer is FALSE for every member who has never
  said anything — which is the whole of the seventh law's second half.

  Public because `document` is not the only honest caller: anything
  that wants to know whether a screen may beacon should ask the same
  question of the same rows rather than keep a second opinion."
  [ctx]
  (boolean
   (and (get (resources ctx) view-consent-kind)
        (seq (rows-of ctx view-consent-kind
                      {:member (:id (:principal ctx)) :state "recording"})))))

(defn- views-doc
  "What this document says about the record of itself — or nil, when
  the engine holds no view kinds at all and there is nothing to say.

  Under a PREVIEW it is always `recording false`, and that is the
  preview exclusion's server-side half rather than a courtesy the
  client is trusted with: a previewer's screen is handed a document
  that gives it nothing to beacon about, and the door would refuse the
  attribution anyway (`feed-view/a-view-is-your-own`). The two halves
  are belt and braces on purpose — a promise kept only in a client is
  a promise kept only until somebody writes a second client."
  [ctx preview recording]
  (when-some [switch (collection-of ctx view-consent-kind)]
    (let [on (and (nil? preview) (boolean recording))]
      {:recording on
       :switch switch
       :post_to (collection-of ctx view-kind)
       :says (cond
               preview
               (str "A preview records nothing. This is somebody else's"
                    " feed read through their sight, and what THEY were"
                    " shown is not something your looking gets to write.")
               on
               (str "This screen tells the house which cards it showed"
                    " you — you turned that on, and " switch " is where"
                    " you turn it off again. The read itself still"
                    " writes nothing; the screen posts, once per card"
                    " per day, and you can read every row it wrote.")
               :else
               (str "Nothing is being recorded about what you were"
                    " shown. It is off for everybody until each person"
                    " turns their own on, at " switch "."))})))

;; ── the reason door (waymark-jfv.16) ────────────────────────────────

(defn- reasons-doc
  "Where a settled card sends a quick reason, and the four words it may
  send — or nil, when this engine holds no reason kind and a card has
  nothing to offer after a decline lands.

  IT IS A DOOR, NOT AN ANSWER, which is the difference from `views`
  above. `views` says whether this reader's screen MAY beacon, and the
  preview half of that had to be the server's. A reason is an ordinary
  write under the tapper's own name: whoever taps is who the row is
  about (`verdict-reason/a-reason-is-your-own`), exactly as whoever
  taps a verb chip is who fired the verb — so there is no preview
  clause here and none would mean anything.

  BOTH HALVES ARE READ OFF THE DECLARATION. The address is the kind's
  own `:plural`, through `collection-of`, so the one place a plural is
  decided stays the one place; the words are the create model's own
  enum and its own `:x-display :choices`, so the chip on a card and
  the select on a form render one vocabulary and a second copy cannot
  drift from it. An engine whose reason kind grew a fifth word grows a
  fifth chip with nothing here changed.

  AND WHICH FOUR RIDES THE SAME READ (waymark-hcr). The reason kind
  may declare `:x-display {:sets {…}}` — a subject kind to its own
  tokens — because a house says different things about what it was
  OFFERED and about what it was TOLD, and eight chips on one settled
  card would be the form this bead's whole design refused. `choices`
  stays the default set, so a screen that knows nothing of `by_kind`
  still offers the right four to a declined piece; `by_kind` carries
  the overrides, and the card picks by the subject it settled. A kind
  the map does not name gets the default, which is what makes the flag
  safe on a kind whose own words nobody has thought about yet.

  WHICH VERBS OFFER THEM IS NOT THIS FUNCTION'S BUSINESS. A verdict
  says so itself, in `:display {:reasons true}` — advertisement, which
  rides no fingerprint facet — and it rides the action entry through
  the ordinary grant projection, so a decline a reader does not hold
  carries no chips for the same structural reason it carries no verb."
  [ctx]
  (when-some [rdef (get (resources ctx) reason-kind)]
    (when-some [post-to (collection-of ctx reason-kind)]
      (let [model (or (:create-schema rdef) (:schema rdef))
            prop (get-in (schema/json-schema model) [:properties :reason])
            choices (get-in prop [:x-display :choices])
            sets (get-in prop [:x-display :sets])
            ;; a declaration may write these keys either way, and a
            ;; door that read only one spelling would answer an
            ;; engine's own declaration with silence
            nm (fn [k] (if (keyword? k) (name k) (str k)))
            at (fn [m k] (when (map? m)
                           (or (get m (str k)) (get m (keyword (str k))))))
            label (fn [v] (or (at choices v) (str v)))
            words (fn [tokens]
                    (mapv (fn [v] {:value (str v) :label (label v)}) tokens))
            dflt (or (seq (at sets "default")) (:enum prop))
            by-kind (into (sorted-map)
                          (keep (fn [[k tokens]]
                                  (when (and (not= "default" (nm k))
                                             (seq tokens))
                                    [(nm k) (words tokens)])))
                          sets)]
        (when (seq (:enum prop))
          (cond-> {:post_to post-to
                   :field "reason"
                   :choices (words dflt)
                   :says (str "A verdict that has landed may say why, in one"
                              " more optional tap. Nothing is written unless"
                              " somebody taps — silence is a complete answer"
                              " — and the sentence a quick word could not"
                              " carry lives one screen deeper, at " post-to
                              ". Some kinds are answered with their own four:"
                              " what the house was offered runs along when,"
                              " what, how and ever; what an agent told it runs"
                              " along worth, backing, newness and truth.")}
            (seq by-kind) (assoc :by_kind by-kind)))))))

;; ── the contest's one read (waymark-8um.3) ──────────────────────────

(defn- view-days
  "How many DAYS each card was on THIS reader's own feed inside the
  formula's window — `{card_id days}` — plus whether the fold reached
  its cap.

  ONE indexed query, through the ordinary collection grammar
  (`member=`, `day_gte=`) the way `in-states` asks its question: a
  second way to ask *which rows are mine* is a second answer waiting to
  disagree. It reads the reader's OWN rows and nothing else, which is
  not a filter that could be relaxed but the whole shape of the law —
  `feed_view`'s `member` is engine-stamped from whoever posted it, so
  there are no other rows this read could name.

  PER DAY, NOT PER DRAW (waymark-dtv, decided here). The storage
  already answers the day-level question and only that one:
  `feed_view`'s `:unique [[:card_id :day :member]]` collapses a card
  seen in three spins of one evening into ONE row, and
  `this-card-is-counted-once-a-day` refuses the second report by name.
  So `count` IS days-shown, with no `distinct` anywhere and none
  possible. The grain is also the honest one: what the formula wants to
  know is how many MORNINGS a card has been in front of somebody
  without being acted on, and a person who deals again three times is
  having one morning. Per-draw would be a schema change (the unique
  grows a draw column) and a law change (the volume arithmetic in
  § 'Volume, honestly' assumes one row per card per day) bought for a
  distinction the household never asked about."
  [ctx formula]
  (let [rdef (get (resources ctx) view-kind)
        st (:storage (:eng ctx))
        from (str (.minusDays (LocalDate/parse ^String (:day ctx))
                              (long (:window-days formula 0))))
        pid (str (:id (:principal ctx)))]
    (try
      (let [{:keys [conds]} (coll/parse-query rdef
                                              {"member" pid "day_gte" from}
                                              {:defaults? false})
            rows (store/with-tx
                   st (fn [tx] (store/search-rows st tx view-kind conds
                                                  {:order-by :day :desc true
                                                   :limit (inc view-scan-cap)})))]
        {:counts (frequencies (keep #(some-> (get-in % [:data :card_id]) str)
                                    rows))
         :reached-cap (> (count rows) view-scan-cap)})
      ;; a kind whose table this engine never made is not something the
      ;; feed gets to fail over — `rows-of`'s posture, and here it is
      ;; also the honest answer: no rows is no contest
      (catch Exception _ nil))))

(defn- reader-cooling
  "The contest's whole state for one read, or NIL — and nil is the
  DEFAULT, because the view record is off for everybody until each
  person turns their own on (law 7).

  Nil means inert: no query is run, no key is added to any card, and
  the sort below is the two-key sort it has always been. That is what
  makes *a non-recording member's feed is unchanged* a structural claim
  rather than a promise.

  Under a PREVIEW this reads the PREVIEWED member's rows, because
  `:principal` here is theirs — and it must, or a preview would answer
  an order the member does not have, which is the one failure a preview
  may not have. It is the same sentence `for-reader` and the visibility
  already say one door over. `views.recording` is a different question
  (may the PREVIEWER's screen beacon — always no) and is answered
  elsewhere."
  [ctx recipe recording]
  (let [formula (formula-of recipe)]
    (when (and recording
               (pos? (long (:cools-after formula 0)))
               (get (resources ctx) view-kind))
      (when-some [{:keys [counts reached-cap]} (view-days ctx formula)]
        {:formula formula :counts counts :reached-cap reached-cap}))))

(defn- cooler
  "`(fn [card-id] → step)` for a section the contest may weight, or nil
  — which is both the inert case and every section laws 2 and 3 keep
  out of the contest."
  [ctx section]
  (when-some [{:keys [formula counts]} (:cooling ctx)]
    (when (contains? contested-sections section)
      (fn ^long [cid] (cooling-step formula (long (get counts cid 0)))))))

(defn- candidates-of
  "Raw rows → candidates, carrying the row so the card builder need not
  read it twice."
  [kind rows]
  (mapv (fn [r] {:kind kind :id (:id r) :row r}) rows))

(defn- open?
  "Is the row still somewhere a person can act? A terminal row is
  history, and history is the archive's business, not do-now's."
  [rdef row]
  (not (contains? (:terminal rdef #{}) (keyword (:state row)))))

;; ── how a row's work ends (waymark-iqa.24, .25) ─────────────────────
;;
;; The machine knows a row is TERMINAL and nothing more. It cannot
;; know that a skipped chore run is over while a due one is not (both
;; are non-terminal, because un-skip is a real verb), nor that a
;; DISCARDED grocery list is not an accomplishment (both endings are
;; terminal), nor that a mirrored task whose `:status` says `done` is
;; finished while its sync state says `fresh`. Those are the
;; household's own words, so the DECLARATION says them (`:over`) and
;; this is the one place that reads it. Two questions, and they are
;; deliberately different:
;;
;;   work-over?    — is this row's work OVER? Nothing over is anybody's
;;                   next action, and only what is over is a memory.
;;   accomplished? — did the house FINISH it, rather than let it go?
;;                   Fuel is deeds; a discarded list is not one.
;;
;; A kind that declares nothing keeps exactly the old meaning: its
;; terminal states are its endings and every one of them counts.

(defn over-vocabulary
  "The kind's declared `:over`, with the machine's own defaults filled
  in — `{:field :accomplished :let-go}`. Public because the feed's
  populations, the archive's gate and `set-aside?` must all read one
  vocabulary; a second opinion about what 'finished' means would let
  one section card what another calls history."
  [rdef]
  (let [o (:over rdef)]
    {:field (:field o)
     :accomplished (set (if o (:accomplished o) (:terminal rdef)))
     :let-go (set (:let-go o))}))

(defn- ending-word
  "The word this row's ending would be spelled with: its machine state,
  or — when the kind declared a `:field` — the value that field holds.
  A mirror's lifecycle is data (task.clj's own rule), so a mirrored
  row's ending is read off the document the authority sent."
  [rdef row]
  (let [{:keys [field]} (over-vocabulary rdef)]
    (if field
      (some-> (get-in row [:data field]) str not-empty)
      (keyword (:state row)))))

(defn work-over?
  "Is this row's work OVER — terminal by the machine, or resting in an
  ending the kind declared? A row that is over is never a next action
  and never anything but history."
  [rdef row]
  (let [{:keys [accomplished let-go]} (over-vocabulary rdef)
        w (ending-word rdef row)]
    (boolean (or (not (open? rdef row))
                 (and w (or (contains? accomplished w)
                            (contains? let-go w)))))))

(defn accomplished?
  "Did the household FINISH this row, rather than let it go? Fuel's
  question, and the narrower one: every accomplishment is over, and a
  discarded list, an abandoned book and a skipped chore are over
  without being deeds."
  [rdef row]
  (boolean (contains? (:accomplished (over-vocabulary rdef))
                      (ending-word rdef row))))

(defn- dedupe-by
  "Keep the first sighting of each key — the archive's one-card-per-row
  rule, applied where the log is still newest-first."
  [f]
  (fn [rf]
    (let [seen (volatile! #{})]
      (fn ([] (rf))
        ([acc] (rf acc))
        ([acc x] (let [k (f x)]
                   (if (contains? @seen k)
                     acc
                     (do (vswap! seen conj k) (rf acc x)))))))))

;; ── the populations (a closed registry) ─────────────────────────────

(defn- nav-kinds
  "The kinds a person NAVIGATES to at the given nav levels, in name
  order — the household's own. `:nav` is the only trait this framework
  declares about who a kind is FOR — every framework kind takes
  `:nav :system` (all ten of them), every application kind takes
  `:primary` by default — and it is not fingerprinted law, so reading
  it here adds nothing to any declaration's hash.

  It is also the whole of the answer to 'what is do-now made of' on an
  engine with no vocabulary for a due date. There is none: waymark
  declares no `:due`, and inventing one to rank by would be the
  scoring function the third law forbids. So do-now is the front-door
  kinds' rows whose work is not over, spread across the kinds and
  picked by the seed; a context-aware do-now waits for the spine the
  epic parks in its v2 lot.

  It answers the FUEL question too, one nav level narrower
  (`fuel-kinds`): a deed is a front door's, and a kind nobody
  navigates to is a line item inside somebody else's row."
  [ctx levels]
  (->> (resources ctx)
       (keep (fn [[k rdef]] (when (contains? levels (:nav rdef :primary)) k)))
       sort
       vec))

(defn next-actions
  "do-now: the front-door kinds' rows whose work is NOT over, seeded
  and SPREAD across the kinds. The card builder keeps only those whose
  projected envelope actually OFFERS something — a next action with no
  verb is a row, not an action — so a reader whose grant confers sight
  but no doors gets a shorter do-now rather than a page of dead ends.

  TWO CORRECTIONS THE FIRST REAL READ FORCED (waymark-iqa.24, .15),
  and neither is a ranking:

  1. `work-over?` rather than `open?`. `open?` asks the FRAMEWORK
     machine, and a mirror kind's machine is the sync machine — so a
     task whose `:status` says `done` and a movie the house finished
     were do-now candidates forever, and worse, the mixer's total
     claim then barred them from the archive. The kind's own `:over`
     is the one vocabulary; the framework holds no app's enum.
  2. A SPREAD, so no kind crowds out another by volume. Every
     candidate carries the `:lane` it occupies in its OWN kind's
     seeded order — its first row is lane 0, its second lane 1 — and
     the mixer orders by lane before hash. Five slots therefore draw
     from five kinds when five kinds have work, instead of from
     whichever kind happens to hold the most rows. A house with two
     hundred queued movies and thirty-three open errands had a do-now
     of movies, which is arithmetic, not a household's morning.

     It is COMPOSITION, not a score: nothing is compared to anything.
     Within a lane the order is still `hash(seed ‖ card_id)` and
     within a kind the order is untouched. The recipe can go further
     and dedicate a line to particular kinds (`:kinds`), which is the
     household saying so in static data rather than the engine
     inferring it."
  [ctx]
  (let [seed (:seed ctx)]
    (into []
          (mapcat (fn [k]
                    (let [rdef (get (resources ctx) k)]
                      (->> (rows-of ctx k {})
                           (remove #(work-over? rdef %))
                           (candidates-of k)
                           (sort-by #(rank seed (card-id :do_now k (:id %))))
                           (map-indexed (fn [i c] (assoc c :lane i)))))))
          (nav-kinds ctx #{:primary}))))

(defn asks
  "decide: access asks awaiting SOMEBODY ELSE's verdict — core's own
  `approval_request`, offered, minus the reader's own, because the
  four-eyes wall means a requester's own ask is the one row it may not
  decide. `requested_by` is named here rather than read off the
  declaration on purpose: `:decision` desugars and dissocs itself, so
  there is no marker left to ask, and this population addresses ONE
  core kind whose field is core's own (`grants/approval-request`).
  waymark-iqa.4 and .6 declare decision kinds of their own and bring
  populations of their own; this one must not silently swallow them,
  or a tickler would arrive twice under two names."
  [ctx]
  (let [pid (:id (:principal ctx))]
    (if-not (get (resources ctx) :approval_request)
      []
      (candidates-of :approval_request
                     (filterv #(not= pid (get-in % [:data :requested_by]))
                              (rows-of ctx :approval_request
                                       {:state "offered"}))))))

(defn letters
  "decide: the mail on this reader's shelf, unopened. The
  `welcome-home` precedent exactly — a core reader naming an optional
  application kind and answering with nothing when the engine holds
  none — and own-surface law makes the query its own gate: a letter is
  addressed, and `data.to` is the address.

  IT ASKS FOR EVERY SPELLING THE READER ANSWERS TO (waymark-1zq), not
  only the principal id. `members/spellings-of` is the one definition
  — the gate's own two-step read backwards: the principal id, plus
  the member ROW whose `:subject` is that principal, whose id is a
  perfectly ordinary way for a person to have addressed a letter (it
  is the id on the roster screen). A letter carrying that spelling was
  invisible here while sitting in plain sight on the row, which is a
  shelf that swallows mail: the recipient never learns there was
  anything to open. The letter kind's own guards read the same
  function, so a card this population offers is a card whose Open
  really opens."
  [ctx]
  (if-not (get (resources ctx) :letter)
    []
    (into []
          (comp (mapcat (fn [addr]
                          (rows-of ctx :letter {:to addr :state "waiting"})))
                (dedupe-by :id)
                (map (fn [r] {:kind :letter :id (:id r) :row r})))
          (members/spellings-of (:eng ctx) (:id (:principal ctx))))))

(defn- load-raw
  "One row of any kind, straight from the store — no decode, no
  projection. The liveness question a tickler asks about its subject
  needs the STATE and one field, and paying for a full decode (and a
  render's worth of schema work) to answer it would be paying for the
  card the population is about to decide not to build."
  [ctx kind id]
  (let [st (:storage (:eng ctx))]
    (try
      (store/with-tx st #(store/load-row st % kind (str id) {}))
      (catch Exception _ nil))))

(defn set-aside?
  "Is a tickler's subject still something the house could pick up?
  The ONE spelling of retire-at-offer-time (docs/spec-feed.md fork
  (b)), public because the conformance pack judges against it.

  Three answers, all no:

  - the kind is not one this engine serves, or the row is GONE — the
    authority deleted it, `:on-gone` retired it, somebody purged it.
    A marker can outlive its subject; the spec accepts that and this
    is where it is paid for.
  - the row is TERMINAL. A finished row is history, and history is
    the archive's business; asking whether to carry it further is
    asking about work that is over.
  - the row rests in an ending the kind declared as ACCOMPLISHED
    (`over-vocabulary`) — the state, or the field a mirror keeps its
    lifecycle in. Until waymark-iqa.15 this was the literal string
    `\"done\"` in this namespace: one application's enum, held by the
    framework, which every other application's vocabulary then read
    as a deviation. The word now belongs to the kind that speaks it.

  A row the house LET GO is deliberately still set aside, and this is
  the one place the two questions part company: `work-over?` (do-now's
  and the archive's) counts a skipped chore and a dropped task as
  over, while a someday/maybe marker over one of them stands, because
  'still not done' is exactly what a someday list is for. The
  household's own way to say otherwise is the `take_it_back` verdict,
  which is a person answering rather than the engine inferring.

  It never asks the reader's GRANT. The marker is its own row with
  its own visibility and `card` projects it; whether this reader may
  see the SUBJECT is a different question from whether the subject is
  still waiting, and conflating them would make one household's
  someday list flicker according to who was holding the phone."
  [ctx kind id]
  (boolean
   (when-some [rdef (and kind (get (resources ctx) kind))]
     (when-some [raw (load-raw ctx kind id)]
       (and (open? rdef raw) (not (accomplished? rdef raw)))))))

;; ── the ticklers line's rank: the due pile, ranked not capped ───────
;; (waymark-1uv.9, the epic 'Ranked, not capped'; the crown's rank one
;; section up is the model, and the shape is deliberately the same)
;;
;; waymark-iqa.13 wants a sweep over the dropped pile, and the
;; temptation was a ceiling on how many markers one sweep may mint.
;; waymark-1uv.7 said no: a tickler writes nothing to its subject
;; (spec-feed fork (b), reason 1 — no cascade into a :push-on-write
;; mirror), it is no letter and no notification, and a marker over a
;; dropped task is the machine INDEXING the pile, which is the owner's
;; own word for what must not be limited. Twenty-five markers born at
;; once is a rank question for the :ticklers line, and this is the
;; rank: five numbers on the recipe row beside the crown's five, one
;; line of arithmetic (`tickler-lift`), narrated on every answer
;; (`tickler-rank-says`), and read back on every tickler card with
;; the numbers that placed it (`why.tickler`).
;;
;; WHAT THE POPULATION HID. A person's own marker — 'bring this back
;; on the 3rd', or 'this, and now' — is an obligation the person set:
;; law 2 puts it outside the contest, and it appears because they
;; asked. A sweep-born marker (the engine's own hand, next_offer_at
;; unset, which means now) is indexing, and contends. Until this bead
;; both were merely DUE and the seed picked two. So a person's own
;; hand is a TIER above every machine-born marker (`tickler-key`),
;; the way asked-for is a tier in the crown, and no number a
;; household writes moves a machine's marker above a person's — law
;; 6 read at the fridge. The five numbers rank the rest.
;;
;; NOT A CAP, AND UNTOUCHED: the backoff. `next_offer_at` is a
;; person's own not-now written as a date, a backed-off marker is
;; simply not a candidate, and that was the tree's first read-side
;; rank input all along. The DEDUPE iqa.13 asked for — one live
;; marker per subject — is a LAW and lands as one, at the tickler's
;; own create door (workqueue10.resources.tickler, `one-live-marker-
;; per-subject`), where it refuses a second sweep and a second hand
;; alike; the sweep below asks first only so it does not knock on a
;; door it knows is shut.
;;
;; THE AGENT IS NOT THE RANK, here as at the crown: an agent may tune
;; these numbers through a staged proposal once `recipe_proposal`
;; carries this field (waymark-1uv.5's door, filed under the epic),
;; and never be the rank.

(def default-tickler-rank
  "The ticklers line's rank, as five numbers — the weights of the five
  inputs a household can argue with. The sixth input, *a person's own
  hand*, is a TIER above all five and not a weight (see `tickler-key`):
  no number a household writes may put the machine's indexing above a
  person's own asking, because that is law 6 read at the fridge.

  `:overdue` — what each whole day past `next_offer_at` lifts a marker.
  The date is the person's own — set by hand at birth, or written by
  their own not-now off the backoff — and it is honoured before the
  sweep's *now*: a marker with no date is due today and not overdue,
  so a hand-dated marker the house has walked past for a week stands
  above one the sweep found this morning.

  `:not_now` — what each not-now the house has already said holds a
  marker back. `offer_count` is the household's own record — the
  tickler's whole reason for server-side state — and a marker the
  house has put off three times has been answered three times; the
  rank reads that as cooler, not as louder.

  `:cooled` — what each step the contest's own arithmetic says a
  marker has cooled holds it back: `cooling-step` over the same view
  rows, the same window and the same `cools-after` as the sections
  below, so the household's two contest numbers govern this cooling
  too, and only while the reader is recording.

  `:front_door` — what a subject this house goes to (`:nav :primary`,
  the one trait the framework declares about who a kind is FOR) lifts
  a marker over one whose subject is a line inside somebody else's
  row. The other half of waymark-1uv.9's fourth input — the VALUE a
  subject serves — is not read, because no kind a tickler marks names
  a value today; the day one does, `value-standing` is the read and a
  sixth number is its weight.

  `:age` — what each thirty days the subject has sat unmoved on the
  dropped pile lifts a marker: oldest dropped first among equals,
  because the someday list exists for the things the house forgot.
  Read off the subject row's own last write, which for a dropped
  mirror row is when the authority let it go — or the last resync that
  touched it, which is the honest bound on the word *age* here.

  All five at zero is *the seed alone*, with a person's own hand still
  first — a number a person can see rather than a key they have to
  know to delete, the contest's own posture. Overdue and age are per
  day and per month and neither is capped: a marker a year past its
  date is a year's worth of the house walking past a note it wrote,
  and that is exactly what should stand first among the machine's."
  {:overdue 1
   :not_now 4
   :cooled 2
   :front_door 5
   :age 1})

(def tickler-scan-cap
  "Offered markers the ticklers line ranks on one read, newest first.
  The population's cost is per DUE candidate — a subject read and a
  read of the marker's own create transition — and with a swept pile
  every marker is due on day one, so the bound is the read's and never
  the birth's (waymark-1uv.9's own sentence: *a stored score or a cap
  on the read, never on the birth*). A stored score was weighed and
  refused for the crown's reason: it would need a writer and would go
  stale on every view row and every not-now. The document says so
  when the cap is reached, in the crown's own posture."
  100)

(defn tickler-rank-of
  "The ticklers line's five numbers this recipe reads: the household's
  own, with the deployment's filled in for anything it did not state —
  `crown-rank-of`'s shape, one field over."
  [recipe]
  (merge default-tickler-rank (:tickler-rank recipe)))

(defn tickler-lift
  "THE TICKLERS LINE'S ARITHMETIC, and this is the whole of it:

      lift = overdue    × whole days past next_offer_at
           − not_now    × times the house has said not now
           − cooled     × steps cooled
           + front_door × [the subject is a kind this house goes to]
           + age        × months the subject has sat on the dropped pile

  over one marker's inputs — `{:overdue n, :not-now n, :cooled n,
  :front-door bool, :age n}` — and the recipe's five numbers. Higher
  stands higher. It is only ever a SORT KEY: the line still shows
  exactly as many markers as its `:take` says whenever that many are
  due, and there is no arithmetic here that can drop one.

  `:own` is not in it, on purpose: a marker a person set aside by
  their own hand is a tier above every machine-born one
  (`tickler-key`), and no weight a household writes moves it down."
  ^long [weights {:keys [overdue not-now cooled front-door age]}]
  (let [w (fn ^long [k] (long (get weights k 0)))]
    (+ (* (w :overdue) (long (or overdue 0)))
       (- (* (w :not_now) (long (or not-now 0))))
       (- (* (w :cooled) (long (or cooled 0))))
       (if front-door (w :front_door) 0)
       (* (w :age) (long (or age 0))))))

(defn tickler-key
  "One due marker's place in the order, as a vector `sort-by` reads
  ascending: a person's own hand first, then the lift (higher first),
  then the seed's hash — so the answer is a pure function of (the
  recipe's numbers, this marker's inputs, the seed) and two markers
  the formula cannot tell apart are still placed by the day rather
  than by arrival."
  [weights inputs ^String hash]
  [(if (:own inputs) 0 1)
   (- (tickler-lift weights inputs))
   hash])

(defn- whole-days
  "Whole days from one instant to a later one; zero when `to` is not
  later. The unit both `:overdue` and `:age` count in."
  ^long [^Instant from ^Instant to]
  (max 0 (.toDays (Duration/between from to))))

(defn- born-by
  "Who created this marker, as the log's own actor `{:id :type}` — the
  create transition is the marker's first, and the actor's TYPE is what
  the tier reads: a human's hand is a person's own asking; an agent's
  or the engine's is indexing. Read off the log rather than off
  `set_aside_by`, because that field holds an id and an id does not
  say whose kind of hand wrote it. One indexed read per DUE, standing
  marker, and none for the rest; nil when the log cannot answer, which
  the tier reads as *not a person's own* rather than guessing."
  [ctx id]
  (let [st (:storage (:eng ctx))]
    (try
      (:actor (first (store/with-tx
                       st (fn [tx] (store/transitions
                                    st tx {:kind :tickler :resource-id (str id)}
                                    {:limit 1})))))
      (catch Exception _ nil))))

(defn- standing-subject
  "The subject's raw row when the house could still pick it up, else
  nil — `set-aside?` handing the row back instead of a boolean, spelled
  with the same three predicates in the same order, because the rank
  reads two facts off the row the population is already paying to
  load: its kind's `:nav` and its own last write."
  [ctx kind id]
  (when-some [rdef (and kind (get (resources ctx) kind))]
    (when-some [raw (load-raw ctx kind id)]
      (when (and (open? rdef raw) (not (accomplished? rdef raw)))
        raw))))

(defn- tickler-inputs
  "One due marker's inputs for `tickler-lift`, read here and weighed in
  `entry-cards` — the crown's own split: the population READS, it does
  not rank. `:seen` and `:cooled` are the one input only a read knows
  and are filled in there, off the reader's own view rows."
  [ctx d subject skind ^Instant now]
  (let [at (get-in d [:data :next_offer_at])
        srdef (get (resources ctx) skind)
        moved (or (:updated-at subject) (:created-at subject))]
    {:own (= "human" (some-> (born-by ctx (:id d)) :type str))
     :overdue (if at (whole-days at now) 0)
     :not-now (long (or (get-in d [:data :offer_count]) 0))
     :front-door (= :primary (:nav srdef :primary))
     :age (if moved (quot (whole-days moved now) 30) 0)
     ;; carried for the card's sentence, not for the arithmetic
     :next-offer-at at
     :subject-kind (name skind)}))

;; ── the sweep: the dropped pile births its own markers ──────────────
;;
;; The someday/maybe list the epic opened with — twenty-five dropped
;; of a hundred and thirteen — was not a list until somebody swept it
;; once (waymark-iqa.13). This is the sweep, and it is the elected
;; kind of job the orphan sweeper is (`jobs/start-orphan-sweeper!`,
;; waymark-db9.4's shape): one process per storage, a loop on a
;; latch, a function a test can call by name with no loop at all.
;;
;; WHAT IT WALKS: every row whose kind declares a `:let-go` word in
;; its `:over` and which rests on that word — a task the authority
;; dropped, a film the house abandoned, a chore run somebody skipped —
;; and which `set-aside?` would still card, so the sweep births
;; nothing the population would retire at offer time. The vocabulary
;; is the kind's own (`over-vocabulary`), never a string held here.
;;
;; WHAT IT WRITES: one tickler per subject, through the tickler's own
;; create door under the engine's own actor, so `set_aside_by` says
;; the sweep did it and the log says so too. The marker's `what` is
;; the subject's own label, as the by-hand door asks a person to
;; spell it — denormalized at birth so the card reads when the row
;; behind it is gone. No date: unset means now, and a swept marker is
;; on the fridge the morning the sweep found it. NO CAP on births —
;; that was the whole of waymark-1uv.9's answer — and the dedupe is
;; the door's own guard; the read of standing markers below is only
;; the sweep declining to knock on a door it knows is shut.
;;
;; WHAT IT NEVER DOES: write the subject (fork (b), reason 1), mint a
;; marker over a row the population would not card, or run under a
;; test's feet — the first pass is one interval after election, the
;; orphan sweeper's own posture, so a boot mints nothing.

(def sweep-actor
  "The engine's own hand on the dropped pile: the principal every
  swept marker is born under, so `set_aside_by` names the sweep and
  the tier reads it as the machine's."
  (t/principal {:id "waymark10-tickler-sweep" :type :system
                :display "Tickler sweep"}))

(def dropped-scan-cap
  "Rows one sweep reads per (kind, let-go word), newest first. A
  bound on the READ and never on the birth: every row the read
  reaches and no marker yet names is born a marker. A house with more
  than five hundred dropped rows of one kind has a pile the next pass
  keeps walking, and the pass says when it stopped short."
  500)

(defn- let-go?
  "Does this row rest on a word its kind declared as LET GO, while the
  machine still holds it open? The sweep's own question — narrower
  than `work-over?`, which counts the accomplished too, and the same
  as `set-aside?` restricted to the let-go half."
  [rdef raw]
  (let [{:keys [let-go]} (over-vocabulary rdef)
        w (ending-word rdef raw)]
    (boolean (and (open? rdef raw) w (contains? let-go w)))))

(defn dropped-pile
  "Every row this house let go and could still pick up, across every
  kind whose `:over` names a let-go word: `{:rows [{:kind :id :row
  :rdef} …] :reached-cap bool}`. Kinds in name order, rows newest
  first, at most `dropped-scan-cap` per (kind, word). Public because
  the live tests and a REPL read the pile the sweep will walk."
  [ctx]
  (reduce
   (fn [acc [k rdef]]
     (let [{:keys [field let-go]} (over-vocabulary rdef)]
       (reduce
        (fn [acc w]
          (let [where (if field
                        {field (if (keyword? w) (name w) (str w))}
                        {:state (if (keyword? w) (name w) (str w))})
                rows (rows-of ctx k where (inc (long dropped-scan-cap)))]
            (-> acc
                (update :reached-cap
                        #(or % (> (count rows) (long dropped-scan-cap))))
                (update :rows into
                        (comp (take dropped-scan-cap)
                              (filter #(let-go? rdef %))
                              (map (fn [r] {:kind k :id (:id r)
                                            :row r :rdef rdef})))
                        rows))))
        acc
        (sort-by str let-go))))
   {:rows [] :reached-cap false}
   (sort-by (comp name key) (resources ctx))))

(defn- swept-what
  "The marker's `what`, denormalized from the subject the way the
  by-hand door asks a person to spell it: the row's own label, or its
  summary line when the kind declares no label, cut to the field's own
  ceiling."
  [rdef decoded]
  (let [s (str/trim (str (or (some-> (:label-template rdef)
                                     (summary/render decoded))
                             (some-> (:summary rdef)
                                     (summary/render decoded)))))
        s (if (str/blank? s) (str (name (:kind rdef)) " " (:id decoded)) s)]
    (subs s 0 (min 200 (count s)))))

(defn serves-ticklers?
  "Does this engine hold a tickler kind at all? The feed module's
  `:when` gate for the sweep (`waymark10.modules`, the mirror
  module's discovery precedent): an engine that serves no tickler
  starts no sweeper and pays nothing for it."
  [eng]
  (some? (get (inv/resources eng) :tickler)))

(defn sweep-dropped!
  "One pass over the dropped pile: a marker per let-go row nobody has
  yet set aside, born through the tickler's own create door under
  `sweep-actor`, with no cap. → `{:born n :standing n :refused n
  :scanned n :reached-cap bool}` — `standing` is how many rows already
  carried a live marker and were passed over, `refused` how many the
  door itself turned away (the dedupe guard winning a race this pass
  did not see, or any other wall the kind grew). Zero everything on an
  engine that serves no tickler kind.

  A test drives this directly (`workqueue10.tickler-rank-test`), the
  way batch_f_jobs_test drives `sweep-orphans!`: the loop below is
  only the clock."
  [eng]
  (let [ctx {:eng eng}]
    (if-not (get (inv/resources eng) :tickler)
      {:born 0 :standing 0 :refused 0 :scanned 0 :reached-cap false}
      (let [{:keys [rows reached-cap]} (dropped-pile ctx)
            live (into #{}
                       (map (fn [r] [(str (get-in r [:data :subject_kind]))
                                     (str (get-in r [:data :subject_id]))]))
                       (rows-of ctx :tickler {:state "offered"} 5000))]
        (reduce
         (fn [acc {:keys [kind id row rdef]}]
           (if (contains? live [(name kind) (str id)])
             (update acc :standing inc)
             (let [d (inv/decode-row rdef row)
                   body {:what (swept-what rdef d)
                         :subject_kind (name kind)
                         :subject_id (str id)
                         :subject_href (str "/api/" (:plural rdef) "/" id)}]
               (try
                 (inv/create! eng :tickler body {:principal sweep-actor})
                 (update acc :born inc)
                 (catch Exception e
                   (if (inv/refusal? e)
                     (update acc :refused inc)
                     (do (binding [*out* *err*]
                           (println "waymark10 feed: tickler sweep could not"
                                    " set aside " (name kind) " " id ": "
                                    (ex-message e)))
                         acc)))))))
         {:born 0 :standing 0 :refused 0
          :scanned (count rows) :reached-cap (boolean reached-cap)}
         rows)))))

(defn start-tickler-sweeper!
  "The sweep's loop: every `:interval-ms` (default an hour),
  `sweep-dropped!` walks the pile. Returns the handle
  `stop-tickler-sweeper!` takes. One process per storage runs it, and
  that is decided where the orphan sweeper's is: the feed module's
  lifecycle hook carries `:elected :tickler-sweeper`. The first pass
  is one interval after the start — a boot mints nothing, so no test
  finds markers it did not make, and a household's first morning
  waits an hour, which the marker's own *unset means now* then
  honours."
  [eng {:keys [interval-ms] :or {interval-ms 3600000}}]
  (let [stop (CountDownLatch. 1)
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (when-not (.await stop (long interval-ms)
                                         TimeUnit/MILLISECONDS)
                         (try (sweep-dropped! eng)
                              (catch Exception e
                                (binding [*out* *err*]
                                  (println "waymark10 feed: tickler sweep"
                                           " failed: " (ex-message e)))))
                         (recur))))
                   "waymark10-tickler-sweep")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop}))

(defn stop-tickler-sweeper! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)

(defn ticklers
  "decide: the house's someday/maybe list, the items whose date has
  come (waymark-iqa.4). The `letters` precedent exactly — a core
  reader naming an optional application kind and answering with
  nothing when the engine holds none.

  Two filters and a retirement:

  - `offered` markers only. `let_go` and `taken` are terminal, so a
    let-go item never returns by construction rather than by a query
    remembering to exclude it.
  - DUE: `next_offer_at` has passed, or was never set. Unset means
    now — a tickler set aside with no date is already on the fridge.
    Each `not now` writes the field further out, which is the whole
    of the backoff on the read side: a backed-off marker is simply
    not a candidate, and nothing sweeps it.
  - RETIRED AT OFFER TIME: a marker whose subject is finished or gone
    says nothing, and says it at the moment it would have spoken.
    That is the spec's own posture rather than a sweeper — a tickler
    that quietly withdrew on a clock would be worse than one that
    stayed on the fridge.

  EACH CANDIDATE CARRIES THE RANK'S INPUTS (waymark-1uv.9), under
  `:tickler`: whose hand set it aside, how many days past its own
  date it stands, how many times the house has said not now, whether
  its subject is a kind this house goes to, and how long that subject
  has sat on the dropped pile. The population READS; it does not
  rank. `entry-cards` adds the one input only a read knows — how many
  mornings THIS reader has been shown it — and sorts by
  `tickler-key`, so the arithmetic lives in one place and the card's
  citation quotes the same numbers the sort used.

  The cost is the read-time posture's, and since the sweep
  (`sweep-dropped!`) keeps no cap on births the read bounds itself:
  at most `tickler-scan-cap` markers are scanned, newest first, and
  only the DUE ones whose subject still stands cost a subject read
  and a log read. The answer says when the cap was reached, and
  `document` tells the reader."
  [ctx]
  (if-some [rdef (get (resources ctx) :tickler)]
    (let [now (:now ctx)
          due? (fn [t] (or (nil? t) (not (pos? (compare t now)))))
          scanned (rows-of ctx :tickler {:state "offered"}
                           (inc (long tickler-scan-cap)))]
      {:reached-cap (> (count scanned) (long tickler-scan-cap))
       :candidates
       (into []
             (keep (fn [raw]
                     (let [d (inv/decode-row rdef raw)
                           skind (some-> (get-in d [:data :subject_kind])
                                         str not-empty keyword)]
                       (when (due? (get-in d [:data :next_offer_at]))
                         (when-some [subject (standing-subject
                                              ctx skind
                                              (get-in d [:data :subject_id]))]
                           {:kind :tickler :id (:id raw) :row raw
                            ;; the rank's inputs, read here and weighed
                            ;; in `entry-cards` (waymark-1uv.9)
                            :tickler (tickler-inputs ctx d subject skind
                                                     now)})))))
             (take tickler-scan-cap scanned))})
    []))

;; ── the findings' rank, read off the rows (waymark-1uv.8) ───────────
;;
;; The readers for `insight-lift`'s inputs, each bounded, each a law
;; read off a declaration rather than a preference. They live beside
;; the population that calls them; the arithmetic lives one block up
;; beside the crown's, so the two ranks read as one shape.

(defn- kind-of-plural
  "The kind keyword a collection address names, or nil — read off the
  registry's own `:plural`, so the one place a plural is decided stays
  the one place."
  [ctx plural]
  (some (fn [[k rd]] (when (= (str plural) (str (:plural rd))) k))
        (resources ctx)))

(defn- cited-declined-outcome?
  "Does this finding cite an outcome the house DECLINED? The second
  shape of a law-4 diagnosis: a finding built on a losing bundle. Reads
  at most `evidence-read-cap` addresses and only follows the ones
  whose collection is the outcome kind's."
  [ctx d]
  (when (get (resources ctx) :outcome)
    (boolean
     (some (fn [href]
             (let [parts (str/split (str href) #"/")]
               (when (and (= 4 (count parts)) (= "api" (nth parts 1))
                          (= :outcome (kind-of-plural ctx (nth parts 2))))
                 (when-some [raw (load-raw ctx :outcome (nth parts 3))]
                   (= :declined (keyword (name (:state raw))))))))
           (take evidence-read-cap (get-in d [:data :evidence]))))))

(defn- insight-diagnosis
  "Is this finding a law-4 diagnosis, and of which shape — the rank's
  first input. `:affirmation` when its next step is a value's or a
  person's own affirmation (`affirmation-doors`); `:recomposition`
  when it offers a step on an outcome's own row or cites an outcome
  the house declined; nil for a plain finding."
  [ctx d offer-kind]
  (let [action (some-> (get-in d [:data :offer_action]) str not-empty keyword)]
    (cond
      (contains? (get affirmation-doors offer-kind) action) :affirmation
      (= :outcome offer-kind) :recomposition
      (cited-declined-outcome? ctx d) :recomposition
      :else nil)))

(defn- offer-serves
  "How the value the OFFERED row serves stands — `:declared`,
  `:observed`, or nil when the row serves none this house holds. The
  row itself when the offer is on a value; the row's own `value_id`
  otherwise (an outcome's, a request's). `value-standing`'s reading,
  one row removed."
  [ctx offer-kind offer-raw]
  (when (get (resources ctx) :value)
    (when-some [raw (if (= :value offer-kind)
                      offer-raw
                      (when-some [vid (some-> (get-in offer-raw [:data :value_id])
                                              str not-empty)]
                        (load-raw ctx :value vid)))]
      (#{:declared :observed} (keyword (name (:state raw)))))))

(defn- insight-record
  "The house's verdict record on the SAME OFFER — the rank's fourth
  and fifth inputs: how many findings on this `{offer_kind, offer_id,
  offer_action}` the house already dismissed (newest
  `dismissal-record-cap`), and the strongest quick word said on any
  of them (`reason-weights`, the heaviest kept). Silence is read as
  silence: a dismissal with no word counts as one and weighs no word."
  [ctx d]
  (let [data (:data d)
        prior (rows-of ctx :insight
                       {:state "dismissed"
                        :offer_kind (str (:offer_kind data))
                        :offer_id (str (:offer_id data))
                        :offer_action (str (:offer_action data))}
                       dismissal-record-cap)
        words (when (and (seq prior) (get (resources ctx) reason-kind))
                (mapcat (fn [r]
                          (keep #(some-> (get-in % [:data :reason]) str not-empty)
                                (rows-of ctx reason-kind
                                         {:subject_kind "insight"
                                          :subject_id (str (:id r))}
                                         4)))
                        prior))]
    {:dismissed (count prior)
     :declined (reduce (fn [b w] (if (> (reason-weight w) (reason-weight b)) w b))
                       nil words)}))

(defn- days-old
  "Whole days since a row was born, never negative."
  ^long [^Instant now created]
  (if (instance? Instant created)
    (max 0 (quot (- (.getEpochSecond now) (.getEpochSecond ^Instant created))
                 86400))
    0))

(defn insights
  "decide: the findings an agent published and nobody has answered
  (waymark-iqa.6). The `letters` and `ticklers` precedent exactly — a
  core reader naming an OPTIONAL application kind and answering with
  nothing when the engine holds none.

  Three filters, and each is a law read off the declaration rather
  than a preference:

  - `published` markers only. `taken` and `dismissed` are terminal, so
    an answered finding leaves the feed by construction.
  - NOT THE READER'S OWN. The four-eyes wall on the kind
    (`:decider {:not {:field :authored_by}}`) means an author is
    structurally incapable of accepting its own finding, so carding it
    to the author would be offering a door that answers 409. `asks`
    does the same thing one population up, for the same reason.
  - THE OFFER IS STILL LIVE. `set-aside?` is the one spelling of
    'could the house still pick this up', and an insight offering a
    next step on a row that is finished or gone is a dead offer. It
    retires AT OFFER TIME, with no sweeper and no write — the
    tickler's own posture, inherited whole rather than re-decided.

  EACH CANDIDATE CARRIES THE RANK'S INPUTS (waymark-1uv.8), under
  `:insight`: whether it is a law-4 diagnosis and of which shape, how
  the value its offered row serves stands, how many findings the house
  already dismissed on the same offer and the strongest word said on
  them, and how many days of freshness it has left in the contest's
  window. The population READS; it does not rank. `entry-cards` adds
  the one input only a read knows — how many mornings THIS reader has
  been shown it, off the same view rows the contest reads — and sorts
  by `insight-key`, so the arithmetic lives in one place and the
  card's citation quotes the same numbers the sort used.

  THERE IS NO CAP, HERE OR AT THE DOOR. `insight/insights-are-capped`
  (three a day, per author) left the create door with waymark-1uv.8,
  the epic's ruling one section down from the crown's: a cap on
  writing protects attention by proxy, a rank protects it directly,
  and the finding is the indexing the ruling said not to limit. The
  read bounds itself instead: at most `insight-scan-cap` findings are
  scanned, newest first, and only the ones that survive the first two
  filters cost a subject read, a dismissal query and — for a diagnosis
  — up to `evidence-read-cap` citation reads. The answer says when the
  cap was reached, and `document` tells the reader."
  [ctx]
  (if-some [rdef (get (resources ctx) :insight)]
    (let [pid (:id (:principal ctx))
          ^Instant now (:now ctx)
          window (long (or (:window-days ctx) 0))
          scanned (rows-of ctx :insight {:state "published"}
                           (inc (long insight-scan-cap)))]
      {:reached-cap (> (count scanned) (long insight-scan-cap))
       :candidates
       (into []
             (keep (fn [raw]
                     (let [d (inv/decode-row rdef raw)
                           okind (some-> (get-in d [:data :offer_kind])
                                         str not-empty keyword)
                           oid (get-in d [:data :offer_id])]
                       (when (and (not= pid (get-in d [:data :authored_by]))
                                  (set-aside? ctx okind oid))
                         (let [offer-raw (load-raw ctx okind oid)
                               old (days-old now (:created-at raw))]
                           {:kind :insight :id (:id raw) :row raw
                            ;; the rank's inputs, read here and weighed
                            ;; in `entry-cards` (waymark-1uv.8)
                            :insight (merge
                                      {:diagnosis (insight-diagnosis ctx d okind)
                                       :value (offer-serves ctx okind offer-raw)
                                       :days-old old
                                       :fresh-days (max 0 (- window old))}
                                      (insight-record ctx d))})))))
             (take insight-scan-cap scanned))})
    []))

(defn- proposal-says
  "One staged proposal's card sentence: what it is staged against, the
  diff the engine wrote at staging, and how many rows are behind it.

  The diff is READ, never recomputed — `recipe_proposal` writes it at
  birth from the two orders, and a card that re-derived it could
  narrate a change nobody agreed to. What this adds is the two facts
  the row's own document holds but a diff cannot: WHICH order is being
  changed, and how much reading stands behind the claim."
  [d]
  (let [data (:data d)
        tid (some-> (:target_id data) str not-empty)
        ev (count (remove str/blank? (map str (:evidence data))))]
    (str (if tid
           (str "Staged against this house's own order, "
                (pr-str (str (:label data))) ". ")
           (str "Staged against the order this deployment ships with —"
                " the house has written none of its own yet. "))
         (str/join " " (:diff data))
         (when (pos? ev)
           (str " " ev " row" (when (not= 1 ev) "s") " behind it.")))))

(defn proposals
  "decide: exact revisions of the feed's own order, staged by somebody
  and waiting on a person's tap (waymark-0k4). The `insights`
  precedent one turn further along — a core reader naming a kind the
  feed module enrols, answering with nothing when the engine holds
  none — and the same three filters, each read off the declaration:

  - `offered` proposals only. `applied`, `declined` and `expired` are
    terminal, so an answered proposal leaves the feed by construction.
  - NOT THE READER'S OWN. `recipe_proposal`'s four-eyes wall means the
    principal that staged a proposal is structurally incapable of
    applying it, so carding it to the stager would be offering a door
    that answers 409. `asks` and `insights` do the same thing, for the
    same reason.
  - THE LEASH IS STILL ON. A proposal past its `expires_at` is not
    offered — the same read-side retirement the tickler's backoff
    already uses, with no sweeper and no write: a lapsed proposal is
    simply not a candidate, and `expire` is the bookkeeping verb that
    tidies the row when somebody gets to it.

  It hands the card its own `sentence`, which no other decide
  population does and this one must: a proposal's whole claim is WHAT
  CHANGES, and a summary line naming the row cannot say it."
  [ctx]
  (if-some [rdef (get (resources ctx) :recipe_proposal)]
    (let [pid (:id (:principal ctx))
          now (:now ctx)]
      (into []
            (keep (fn [raw]
                    (let [d (inv/decode-row rdef raw)
                          exp (get-in d [:data :expires_at])]
                      (when (and (not= pid (get-in d [:data :proposed_by]))
                                 (or (nil? exp) (pos? (compare exp now))))
                        {:kind :recipe_proposal :id (:id raw) :row raw
                         :sentence (proposal-says d)}))))
            (rows-of ctx :recipe_proposal {:state "offered"})))
    []))

;; ── the crown: what this week could hold (waymark-jfv.4) ────────────
;;
;; The census's first section, and the only population in this file
;; whose card is AUTHORED. It sits down here beside `proposals`
;; because it reads `load-raw` and `rows-of`, which are defined above
;; it; the ORDER of the page is `census` and `default-recipe`, never
;; the order of the defns.

(def bundle-floor
  "The fewest pieces a bundle may card with. TWO, and it is
  `outcome/a-bundle-is-small`'s own sentence — *an outcome with one
  piece is a finding; publish an insight* — finally judged somewhere
  it can be judged honestly.

  The ceiling stands at the piece's create door, counting siblings.
  The FLOOR cannot: the parent row is born before any piece exists, so
  no create door can count what has not been written yet, and putting
  it on `make_it_so` would refuse the legitimate partial accept
  (decline all but one, then take that one — exactly the shape the
  epic asked for). So it is judged AT OFFER TIME, the way `ticklers`
  retires a marker whose subject is finished: the bundle simply is not
  a candidate, nothing sweeps it, and no row is written. waymark-jfv.7
  is where the two homes were weighed; this is the population's half
  of that bead."
  2)

;; ── the impact line (waymark-jfv.17) ────────────────────────────────
;;
;; THE OWNER'S DISCOMFORT, verbatim, is what this is downstream of:
;; *I'm not yet comfortable using the crown because I'm not sure what
;; impact the actions will have.* A piece card carried the COMPOSER's
;; prose — `says`, what the piece IS — and nothing the ENGINE said
;; about what the tap DOES. This is that sentence, and the whole of
;; its integrity is that it is COMPUTED rather than authored: a
;; function of {the target kind's own declaration, the prepared
;; input}, with no clause the stager can reach. waymark-jfv.9's
;; ruling is the reason it has to be that way — *the impact statement
;; is engine-written; the agent's description never stands alone.*
;;
;; IT IS WRITTEN AT STAGING onto the piece row (`recipe_proposal`'s
;; `diff` posture, one kind over: the engine's reading, written at
;; birth, so the sentence a person taps under is never the stager's),
;; and it is the SAME function here — so a piece staged before this
;; law existed reads its line at the READ rather than waiting for a
;; backfill. `piece-impact` is public for exactly those two callers.
;;
;; THE SHAPE IS A FUNCTION OF THE PIECE'S TARGET FORM, one arm per
;; form. jfv.17 wrote the CREATE arm and left the seat beside it warm;
;; waymark-jfv.9 sat down in it. The two arms are now:
;;
;;   :create — "Yes will create one task: …"
;;   :invoke — "Yes will use the \"Done\" door on one task that
;;              already stands: …"
;;
;; and there is no third, because an UPDATE is not a form: a revise is
;; an ACTION, so editing an existing row is the invoke arm naming that
;; kind's own wording door. That was weighed at jfv.9 and written down
;; rather than left implicit.
;;
;; THE OWNER'S RULING is what took the wall down and what put this
;; sentence in its place: *a piece can do whatever it wants, but I
;; just need to be able to inspect the impact — what it's actually
;; going to do.* So the enum of targets died and this function became
;; load-bearing: it is the ONLY place a household reads what a tap
;; reaches, and every word of it is derived from a declaration.

(defn- affirming-verb
  "The word the household will actually tap, read off the kind's own
  declaration: the label of its primary-styled action. `Yes` and
  `Make it so` are an application's words and this is the framework's
  page, so the sentence BORROWS them rather than spelling them."
  [rdef]
  (some (fn [[_ a]]
          (when (= :primary (get-in a [:display :style]))
            (some-> (get-in a [:display :label]) str str/trim not-empty)))
        (sort-by key (:actions rdef))))

(defn- kind-noun
  "The household's word for ONE row of a kind: its `:display :title`
  when that heading is a static noun, and the kind's own name
  otherwise. A TEMPLATED title is the ROW's name rather than the
  kind's, and a sentence that dropped one in would name the same row
  twice."
  [rdef]
  (let [t (str/trim (str (get-in rdef [:display :title])))]
    (if (and (seq t) (not (str/includes? t "{")))
      t
      (str/replace (name (:kind rdef)) "_" " "))))

(defn- prepared-label
  "What the prepared input will BE CALLED once it is a row — the
  target's own `:label-template` rendered over the prepared body, the
  same template `invoke/label-of` writes into every labelled ref, so
  the name in the sentence is the name the house will read on the row
  it lands as. Keys are keywordized shallowly because a piece's
  `prepared` crosses the wire as an object and arrives either way."
  [trdef prepared]
  (when-some [tpl (or (:label-template trdef)
                      (when (contains? (set (schema/entry-keys (:schema trdef)))
                                       :name)
                        "{data.name}"))]
    (let [d (into {} (map (fn [[k v]] [(keyword k) v])) prepared)
          s (str/trim (summary/render tpl {:data d}))]
      (when (and (seq s) (not= "—" s))
        (if (< 200 (count s)) (str (subs s 0 199) "…") s)))))

(defn- mirror-clause
  "The mirrored-kind consequence, which is PART OF THE TRUTH about a
  tap and not a footnote: a `task` born here is pushed to the
  authority it mirrors, so a person answering the piece is answering
  for two records rather than one.

  Read off the `:mirror` DECLARATION and nothing else. What that
  declaration actually holds is machinery — adapter, cadences,
  document mode, `:push-on-write`, `:create-push` — and NO display
  name for the authority: `mirror/declaration` mints a `Spec` whose
  keys `fingerprint/authority-fp` reads by name, and none of them is
  prose. So the clause names the source WITHOUT naming it, because
  hard-coding `Google Tasks` here would be the framework's own page
  saying a word only one deployment's adapter knows. The day a
  declaration carries a household name for its authority, this is the
  one line that has to change.

  `:create-push` is the exact condition and not `:mirror`: a
  pull-only mirror could not have been born by this tap at all, and a
  push-on-write kind without it pushes EDITS rather than births."
  [trdef noun]
  (when (:create-push (:mirror trdef))
    (str ", and at the source it mirrors to, the way any " noun " does")))

(defn- mirror-edit-clause
  "The same clause for a tap that MOVES a row rather than birthing
  one, and the condition is `:push-on-write` rather than
  `:create-push`: a kind that pushes its edits carries this tap out to
  the authority too, whether or not it was ever allowed to push a
  birth."
  [trdef noun]
  (when (:push-on-write (:mirror trdef))
    (str ", and at the source it mirrors to, the way any " noun " does")))

(defn- row-label
  "What the house already calls the row this tap would move — its own
  `:label-template`, rendered over its own data. The same template
  `invoke/label-of` writes into every labelled ref, so the name in the
  sentence is the name on the card the household would open."
  [trdef row]
  (when (and trdef row)
    (when-some [tpl (:label-template trdef)]
      (let [s (str/trim (summary/render tpl row))]
        (when (and (seq s) (not= "—" s))
          (if (< 200 (count s)) (str (subs s 0 199) "…") s))))))

(defn- state-word
  "A state token mid-sentence — `summary/state-label`'s prose without
  its capital, because this one is never the start of a sentence."
  [s]
  (some-> s name (str/replace "_" " ")))

(defn- carried-clause
  "The prepared input, said out loud. An action that takes no input
  has nothing to say here; one that does gets its body rendered rather
  than summarized, because the whole promise of this line is that the
  household can read what the tap carries."
  [prepared]
  (when (and (map? prepared) (seq prepared))
    (let [s (pr-str (into (sorted-map) prepared))]
      (str ", carrying " (if (< 240 (count s)) (str (subs s 0 239) "…") s)))))

(defn- create-arm
  "jfv.17's sentence, unchanged to the byte: what a tap that BIRTHS a
  row does."
  [prdef trdef prepared]
  (when (map? prepared)
    (let [noun (kind-noun trdef)
          label (prepared-label trdef prepared)]
      (str (or (affirming-verb prdef) "This") " will create one " noun
           (when label (str ": " (pr-str label)))
           " — in this house's own record"
           (mirror-clause trdef noun)
           ". Nothing else."))))

(defn- invoke-arm
  "waymark-jfv.9's sentence: what a tap that MOVES a row already
  standing does. Four derived clauses and not one authored word —

  - the DOOR, by the label the target kind put on it, quoted, so a
    household reads the same word it would read on that row's own
    screen (and a rename renames it here);
  - the ROW, by the target's own `:label-template`, so the sentence
    names the thing rather than an id;
  - the MOVE, from the state the row is resting in to the state that
    door declares as `:to` — or `stays`, when the door is a self-loop
    like a value's `still_stands`;
  - what the tap CARRIES, the prepared input rendered, because an
    input the household cannot see is exactly the half of a tap the
    ruling is about.

  A target row this engine cannot find drops the row clauses rather
  than guessing at them — jfv.17's own failure direction, less said
  and nothing false."
  [prdef trdef adefn trow prepared]
  (let [noun (kind-noun trdef)
        label (row-label trdef trow)
        door (or (some-> (get-in adefn [:display :label]) str str/trim
                         not-empty)
                 (some-> (:name adefn) name (str/replace "_" " ")))
        from (state-word (:state trow))
        to (state-word (:to adefn))]
    (str (or (affirming-verb prdef) "This") " will use the " (pr-str door)
         " door on one " noun " that already stands"
         (when label (str ": " (pr-str label)))
         ;; SILENT ON A SELF-LOOP, and on a mirrored kind whose machine
         ;; state is its SYNC state rather than its household one. A
         ;; door that lands where the row already is has no move to
         ;; report, and "which stays fresh" about a task somebody is
         ;; completing would be a true sentence saying nothing — the
         ;; door's own label and what it carries are the change.
         (when (and from to (not= from to))
           (str ", which reads " from " now and " to " after"))
         (carried-clause prepared)
         " — in this house's own record"
         (mirror-edit-clause trdef noun)
         ". Nothing else.")))

(defn piece-impact
  "The engine's reading of one still-offered piece's tap, in the
  household's own words — one arm per target FORM, and the form is
  read off the piece's own row rather than guessed at.

  Every word of it is derived: the verb from the piece kind's primary
  action, the noun and the mirror clause from the target's own
  declaration, the name from a `:label-template`, and — for the invoke
  arm — the door's label and its `:to` from the target ACTION's own
  declaration. nil when the target kind or door is not one this engine
  serves, which is the same answer the card gives: no line rather than
  a guess.

  `trow` is the target row the invoke arm is about, decoded, and is
  ignored by the create arm (which has no row yet — that is the whole
  difference between the two forms)."
  ([prdef trdef prepared] (piece-impact prdef trdef {:prepared prepared} nil))
  ([prdef trdef pdata trow]
   (when (and prdef trdef)
     (let [prepared (:prepared pdata)
           action (some-> (:target_action pdata) str str/trim not-empty)]
       (if (or (= "invoke" (some-> (:form pdata) str)) action)
         (when-some [adefn (some-> (get-in trdef [:actions (keyword action)])
                                   (assoc :name (keyword action)))]
           (invoke-arm prdef trdef adefn trow prepared))
         (create-arm prdef trdef prepared))))))

(defn- bundle-impact
  "The bundle's own line: `make_it_so` is the UNION of the pieces
  still on offer and nothing more — the confirmation story this epic
  owed, said on the card rather than in a dialog.

  It cannot be stored at staging the way a piece's can, and the
  reason is structural rather than a preference: the parent row is
  born before any piece exists (`a-bundle-is-small`'s floor has the
  same problem), and the union CHANGES as pieces are answered. So it
  is computed at the read, from the pieces still standing at that
  read — which is the same thing `take-the-rest` will count inside
  the transaction.

  It states a COUNT and never a piece's content, so it says the same
  true thing to a reader whose leash names the bundle and not its
  parts: that reader's own tap really would take all of them."
  [rdef n]
  (when (pos? n)
    (str (or (affirming-verb rdef) "This") " = "
         (if (= 1 n)
           "the one piece still on offer in this bundle"
           (str "all " n " pieces still on offer in this bundle, taken"
                " together"))
         " — and nothing that has already been answered.")))

(defn- outcome-says
  "One offered outcome's card sentence — the four things its summary
  line cannot say.

  The GOAL is the row's own summary and the card's heading, so it is
  not repeated here. What this adds is WHICH value the week would be
  spent on, WHETHER THIS HOUSE HAS ACTUALLY SAID SO, WHY it is cheap
  to start (the routing citation, in the household's own words,
  written by whoever composed it), and HOW MANY rows were read before
  any of it was claimed. The routing is READ, never re-derived: a card
  that paraphrased the composer's claim would be a second author on
  somebody else's sentence.

  THE OBSERVED CLAUSE (waymark-jfv.10) is the second of those and it
  is not a garnish. An agent may write a value now, and a value an
  agent wrote is born `observed` — nobody's law yet, just somebody's
  reading of this house. An outcome may be composed against one, which
  means a card can arrive asking for a Saturday on the strength of a
  guess. It says so, here, where the person answering it is looking:
  a value the house never affirmed is named as one, and the way to
  settle it is one tap on the value's own door. Silence would have
  been the composer borrowing the owner's voice through the card
  instead of through the value."
  [d observed?]
  (let [data (:data d)
        v (some-> (:value_name data) str not-empty)
        routing (str/trim (str (:routing data)))
        ;; the composer's prose is quoted, so it decides its own words
        ;; — all this does is make sure the count that follows starts a
        ;; sentence rather than continuing somebody else's
        routing (cond-> routing
                  (and (seq routing) (not (re-find #"[.!?…]$" routing)))
                  (str "."))
        ev (count (remove str/blank? (map str (:evidence data))))
        ;; THE REWORKED CLAUSE (waymark-9j2), and it is ENGINE-OWNED
        ;; prose, never the composer's — the same wall the routing above
        ;; respects, one clause over: the composer's reason for the
        ;; rework lives in its thread reply, and this line only says
        ;; THAT the plan was reworked and how many rounds, a fact the
        ;; engine stamps and the composer cannot reach a word of. It
        ;; reads `reworked_at`/`plan_revision`, both engine-written; an
        ;; outcome the composer has not reworked has neither and says
        ;; nothing here.
        reworked? (some-> (:reworked_at data) str not-empty)
        rounds (long (or (:plan_revision data) 0))]
    (str/trim
     (str ;; THE PERSON'S OWN PULL, said first (waymark-jfv.20): a
          ;; bundle that answers a request is here because somebody
          ;; asked, and the card should say so before it says anything
          ;; the composer chose
          (when (some-> (:request_id data) str not-empty)
            "You asked for another, and this is the composer's answer. ")
          (when v
            (str "For " v
                 (when observed?
                   (str " — a value observed in your record, not yet"
                        " affirmed, so say whether it is yours before a"
                        " week goes to it"))
                 ". "))
          routing
          (when (pos? ev)
            (str " " ev " row" (when (not= 1 ev) "s") " behind it."))
          (when reworked?
            (str " Reworked from your note"
                 (when (pos? rounds)
                   (str " — plan v" rounds))
                 "."))))))

(defn- value-standing
  "How the value this bundle serves stands right now — `:declared`
  when a person put their name to it, `:observed` when it is still
  something an agent noticed and nobody has answered, and NIL when
  this house is not holding it at all (retired, dismissed, or gone).

  A retired value is exactly how a household stops being offered
  outcomes that serve it (`names-a-value` says so at the create door),
  and this is the same law read at offer time — no sweeper, no write,
  the tickler's own posture. The `observed` arm is waymark-jfv.10's:
  the bundle stands, and `outcome-says` puts the standing on its card
  in the household's own words.

  An engine with no `value` kind cannot answer, so it does not
  pretend to: `:declared`, and the bundle's own create door was the
  wall."
  [ctx d]
  (if-not (get (resources ctx) :value)
    :declared
    (when-some [vid (some-> (get-in d [:data :value_id]) str not-empty)]
      (when-some [raw (load-raw ctx :value vid)]
        (#{:declared :observed} (keyword (name (:state raw))))))))

(defn- offered?
  "Is this piece still asking? The one spelling, because three
  populations of one card read it now: the bundle's own candidacy,
  its union line, and whether a piece's impact line has a tap left to
  describe."
  [p]
  (= :offered (keyword (name (:state p)))))

(defn- piece-impact-of
  "One piece's impact line: the one WRITTEN AT STAGING when the row
  carries it, and the same derivation run at the READ when it does
  not.

  The fallback is not a courtesy — it is the honest answer to the
  rows that already exist. Four pieces were offered in production
  before this law, staged with no such field, and a backfill would
  have written the engine's sentence onto them from outside the
  staging door it belongs to. Both paths call one function over one
  pair of declarations, so a stored line and a derived line are the
  same sentence, and a piece keeps whichever it was born with.

  THE INVOKE ARM READS THE TARGET ROW, and only when it has to — the
  fallback is for pieces born before their law existed, so the read
  costs nothing on the ordinary path where the line is already on the
  row (waymark-jfv.9)."
  [ctx prdef pd]
  (or (some-> (get-in pd [:data :impact]) str not-empty)
      (let [d (:data pd)
            tk (keyword (str (:target_kind d)))
            trdef (get (resources ctx) tk)
            tid (some-> (:target_id d) str not-empty)]
        (piece-impact prdef trdef d
                      (when (and trdef tid)
                        (some->> (load-raw ctx tk tid)
                                 (inv/decode-row trdef)))))))

(defn- bundle-parts
  "The pieces of one bundle, oldest first — the order `take-the-rest`
  fans out in, so the household reads them in the order the engine
  would take them and a refusal names the piece they read first."
  [ctx oid]
  (vec (sort-by (juxt #(str (:created-at %)) #(str (:id %)))
                (rows-of ctx :outcome_piece {:outcome_id (str oid)}))))

(defn- crown-word
  "The strongest quick word the house said about the line of thinking
  this bundle recomposes, or nil when nothing on its record was said
  in words (waymark-1uv.2, the rank's fourth input).

  It walks the `supersedes` chain — this bundle recomposes one the
  house declined, which may itself have recomposed another — and reads
  the `verdict_reason` rows filed against each prior outcome, at most
  `supersedes-chain-cap` hops up. The word that comes back is the
  HEAVIEST said anywhere on the chain (`reason-weights`), because a
  house that said *never this* two recompositions ago has not unsaid
  it by being offered a new shape.

  Silence is read as silence HERE. A decline with no word leaves
  nothing in this input, on purpose: the quick word is optional and
  *not tapping is a complete answer* (waymark-jfv.16). What a bare
  decline buys the house is the SCHEDULE — `not_before`, stamped off
  the tickler's steps and lengthening with `declined_count` — which
  since waymark-1uv.10 is the rank's own `:early` input rather than a
  wall at the create door: a recomposition staged before that day is
  cooled by every day it is early. The word and the date are two
  inputs because they say two things — *this line, never* is not
  *not until March*.

  The reason kind is the framework's and enrolled `:always`, but the
  read still asks whether this engine holds it, `reasons-doc`'s own
  courtesy."
  [ctx d]
  (when (get (resources ctx) reason-kind)
    (loop [sid (some-> (get-in d [:data :supersedes]) str not-empty)
           hops 0
           best nil]
      (if (or (nil? sid) (<= (long supersedes-chain-cap) (long hops)))
        best
        (let [said (keep #(some-> (get-in % [:data :reason]) str not-empty)
                         (rows-of ctx reason-kind
                                  {:subject_kind "outcome" :subject_id sid}
                                  8))
              best (reduce (fn [b w] (if (> (reason-weight w) (reason-weight b))
                                       w b))
                           best said)
              prior (load-raw ctx :outcome sid)]
          (recur (some-> (get-in prior [:data :supersedes]) str not-empty)
                 (inc (long hops))
                 best))))))

(defn- crown-judgment
  "The agent's word about this bundle, or nil when no agent has said
  one (waymark-1uv.6, the rank's sixth input): the NEWEST live
  `ranking_note` naming the row, as `{:score :by :says}` — the score
  `crown-lift` weighs, the name the card quotes it under, and the one
  sentence it quotes. One query per candidate, limit one, inside the
  bound `crown-scan-cap` already puts on the population.

  THE NEWEST ONE, WHOEVER WROTE IT, and that is a decision rather
  than an accident. Two agents scoring one bundle is a house running
  two judges, and the honest reading of two opinions is not their
  mean — it is that the card quotes ONE agent by name and the
  household can read whether that agent was right. A dismissed note
  is not live and is not read; a restated one is the same row with
  its new score.

  The note kind is the framework's and enrolled `:always`, but the
  read still asks whether this engine holds it, `crown-word`'s own
  courtesy."
  [ctx oid]
  (when (get (resources ctx) note-kind)
    (when-some [raw (first (rows-of ctx note-kind
                                    {:subject_kind "outcome"
                                     :subject_id (str oid)
                                     :state "live"}
                                    1))]
      (when-some [score (get-in raw [:data :score])]
        {:score score
         :by (str (get-in raw [:data :judged_by]))
         :says (str (get-in raw [:data :says]))}))))

(defn- days-left
  "Whole days between now and a bundle's `good_until`, never negative
  — the rank's freshness input, and zero for a bundle with no leash at
  all (none is staged that way; the engine stamps one)."
  ^long [^Instant now good]
  (if (instance? Instant good)
    (max 0 (quot (- (.getEpochSecond ^Instant good) (.getEpochSecond now))
                 86400))
    0))

(defn- days-early
  "How many days before the house said it would hear this line of
  thinking again a recomposition is standing — the rank's `:early`
  input (waymark-1uv.10). Read off the SUPERSEDED outcome's
  `not_before`, because that is the row the decline stamped; zero once
  the day has passed, and zero for a bundle that recomposes nothing.
  Rounded UP where `days-left` rounds down: a recomposition an hour
  early is a day early, because the person's date is a day and the
  house has not reached it."
  ^long [^Instant now prior]
  (let [nb (get-in prior [:data :not_before])]
    (if (instance? Instant nb)
      (max 0 (quot (+ (- (.getEpochSecond ^Instant nb) (.getEpochSecond now))
                      86399)
                   86400))
      0)))

(defn crown-inputs
  "ONE bundle's rank inputs, read off its row at `(:now ctx)` — the
  `:crown` map every crown candidate carries (waymark-1uv.2), and the
  same reading the composer's diagnosis makes of an outcome that never
  reached a screen (waymark-1uv.4), so the number a composer reads
  there is the number the crown sorted by and never a second opinion
  about it: whether it answers a person's own request; how the value
  it serves stands (`:declared`, `:observed`, or NIL when this house
  no longer holds it — `value-standing`'s three answers, and the
  population drops the third); the strongest word the house said
  about the line of thinking it recomposes; the days left on its
  week; for a recomposition, how early it stands against the day the
  house named, how many times the line was turned down, and the day
  itself (waymark-1uv.10); and the agent's word, when one was said
  (waymark-1uv.6).

  `seen` and `cooled` are not here, on purpose: they are the one
  input only a READ knows — how many mornings THIS reader has been
  shown it — and `entry-cards` fills them in off the reader's own view
  rows. A caller reading a bundle as it stood when it was staged
  passes that instant as `:now`, and every calendar input (`days-left`,
  `early`) reads against it; the rows (the value, the chain, the note)
  read as they stand now, because that is all the record holds."
  [ctx rdef d]
  (let [now (:now ctx)
        ;; the row this one recomposes, DECODED, for the day the house
        ;; said it would hear the line again (waymark-1uv.10) —
        ;; `not_before` is an instant only after the kind's own schema
        ;; has read it
        prior (when-some [sid (some-> (get-in d [:data :supersedes])
                                      str not-empty)]
                (some->> (load-raw ctx :outcome sid)
                         (inv/decode-row rdef)))]
    (cond-> {:asked (some? (some-> (get-in d [:data :request_id])
                                   str not-empty))
             :value (value-standing ctx d)
             :declined (crown-word ctx d)
             :days-left (days-left now (get-in d [:data :good_until]))
             ;; the agent's word, when one was said (waymark-1uv.6)
             :judged (crown-judgment ctx (:id d))}
      ;; only a recomposition carries the schedule: how early it
      ;; stands and how many times the line was turned down —
      ;; `declined_count` is the bundle's own, inherited at birth
      prior
      (assoc :early (days-early now prior)
             :turned-down (long (or (get-in d [:data :declined_count]) 0))
             :not-before (get-in prior [:data :not_before])))))

(defn outcomes
  "outcomes: the composed bundles this house has not answered — the
  feed's crown, *This week could hold* (waymark-jfv.4). The `letters`
  / `ticklers` / `insights` / `proposals` precedent one section
  further up: a core reader naming OPTIONAL application kinds by
  keyword and answering with nothing when the engine holds neither.
  These are the fourth and fifth such names; a sixth would be the
  moment to declare a read-trait instead, and the spec records that
  tipping point rather than pretending it is far away.

  Four filters and two retirements, each a law read off the
  declaration rather than a preference:

  - `offered` bundles only. `accepted`, `declined` and `expired` are
    terminal, so an answered outcome leaves the feed by construction —
    and since waymark-9xn so does `iterating`, which is not terminal
    at all: a bundle the household handed back for a re-plan is in the
    composer's hands, not asking anybody anything, and it returns to
    this query the moment `rework` puts it back in `offered`. What is
    said instead is one line under the crown (`reworking-doc`), so the
    disappearance reads as answered rather than as lost.
  - NOT THE READER'S OWN. `the-composer-does-not-decide` means the
    principal that staged a bundle is structurally incapable of
    answering any part of it, so carding it to the composer would be
    offering doors that answer 409. `asks`, `insights` and
    `proposals` all do this, for the same reason.
  - THE LEASH IS STILL ON. Past `good_until` the week the bundle was
    for is over; `expire` is bookkeeping anybody may run, and nothing
    here sweeps.
  - THE VALUE IS STILL HELD — declared by a member, or observed in
    this house's record and not yet answered (waymark-jfv.10). An
    observed value does not hide behind the card: its bundle's
    sentence names it as observed, so the person deciding whether to
    spend a Saturday knows whose sentence he is spending it on.
  - EVERY PIECE IS NOT ALREADY ANSWERED. That one and the value both
    retire at offer time, the way a tickler's finished subject
    does. A bundle with nothing left on offer would card `Make it so`
    over a tap that could land nothing — `something-is-still-on-offer`
    refuses it at the door, and a card that offered it anyway would be
    a button that fails.
  - THE BUNDLE FLOOR, `bundle-floor`: fewer than two pieces is a
    finding rather than an outcome, and the population is the only
    honest place that count can be made.

  It hands each candidate a `:sentence` — the value named, the routing
  cited, the reading counted — and a `:parts` vector, which is the one
  wire widening this epic asks for: `card` renders each piece through
  the SAME `envelope-summary` → `:row?` → `split-verbs` path and hangs
  the results on the card as `pieces`, so a piece's chips are the
  piece row's own projected verbs and concealment holds exactly as it
  does for the parent.

  EACH CANDIDATE CARRIES THE RANK'S INPUTS (waymark-1uv.2), under
  `:crown` — read by `crown-inputs`, which the composer's diagnosis
  reads the same rows through (waymark-1uv.4), so the number it quotes
  for a bundle the crown never showed is the crown's own number:
  whether it answers a person's own request, how the value
  it serves stands, the strongest word the house said about the line
  of thinking it recomposes, how many days are left on its week, and
  — for a recomposition — how many days early it stands against the
  day the house said it would hear that line again, with how many
  times the line was turned down beside it (waymark-1uv.10) — and
  what an agent said about it, when one did: the newest live
  `ranking_note`'s score, author and sentence (waymark-1uv.6). The
  population READS; it does not rank. `entry-cards` adds the one
  input only a read knows — how many mornings THIS reader has been
  shown it, off the same view rows the contest reads — and sorts by
  `crown-key`, so the arithmetic lives in one place and the card's
  citation quotes the same numbers the sort used.

  THERE IS NO CAP, HERE OR AT THE DOOR. `outcome/outcomes-are-few`
  left the create door with waymark-1uv.3, the epic's ruling: a cap on
  writing protects attention by proxy, a rank protects it directly,
  and a wall that refused a staging taught the composer nothing an
  unanswered row would not. What stays true is that a read-side WINDOW
  must not come here — a bundle the rank places last is still a
  candidate, still named to `claimed_above`, and still shown the
  morning the floor reaches it. That includes a recomposition the
  house said not to hear yet: cooled to the bottom by `:early`, never
  dropped, because the rank chooses WHICH and never WHETHER.

  The cost is the read-time posture's, and since no door keeps the
  number small the read bounds itself: at most
  `crown-scan-cap` bundles are scanned, newest first, each surviving
  one costing a value read, a piece query, a walk up its supersedes
  chain and one note query, and `a-bundle-is-small` caps the pieces
  at five.
  The answer says when the cap was reached, and `document` tells the
  reader — truncation announced beats totality implied."
  [ctx]
  (let [rdef (get (resources ctx) :outcome)
        prdef (get (resources ctx) :outcome_piece)]
    (if-not (and rdef prdef)
      []
      (let [pid (:id (:principal ctx))
            now (:now ctx)
            ;; THE STATE IS THE FILTER (waymark-9xn), and it is worth
            ;; saying out loud because a whole bead rests on it: this
            ;; query takes `offered` and nothing else, so an outcome the
            ;; household handed back for a re-plan (`iterating`) leaves
            ;; the crown BY CONSTRUCTION — no flag on the row, no second
            ;; filter here to keep in step with the machine, and no way
            ;; for the two to disagree. It comes back the moment the
            ;; composer's `rework` puts it in `offered` again, re-ranked
            ;; as the fresh thing it now is.
            scanned (rows-of ctx :outcome {:state "offered"}
                             (inc (long crown-scan-cap)))]
        {:reached-cap (> (count scanned) (long crown-scan-cap))
         :candidates
         (into []
               (keep (fn [raw]
                       (let [d (inv/decode-row rdef raw)
                             good (get-in d [:data :good_until])
                             parts (bundle-parts ctx (:id raw))
                             ;; the rank's inputs, read here and weighed
                             ;; in `entry-cards` (waymark-1uv.2); the
                             ;; value's standing is the one of them the
                             ;; population also filters on
                             crown (crown-inputs ctx rdef d)
                             standing (:value crown)]
                         (when (and (not= pid (get-in d [:data :composed_by]))
                                    (or (nil? good) (pos? (compare good now)))
                                    (<= (long bundle-floor) (count parts))
                                    (some offered? parts)
                                    (some? standing))
                           {:kind :outcome :id (:id raw) :row raw
                            :sentence (outcome-says d (= :observed standing))
                            :crown crown
                            ;; the union, said out loud (waymark-jfv.17)
                            :impact (bundle-impact
                                     rdef
                                     (count (filter offered? parts)))
                            :parts (mapv
                                    (fn [p]
                                      (let [pd (inv/decode-row prdef p)]
                                        {:kind :outcome_piece :id (:id p) :row p
                                         :says (str (get-in pd [:data :says]))
                                         ;; ONLY ON A PIECE STILL ON
                                         ;; OFFER: the line is what the
                                         ;; tap WILL do, and a piece
                                         ;; already answered has no tap
                                         ;; left to describe
                                         :impact (when (offered? p)
                                                   (piece-impact-of
                                                    ctx prdef pd))}))
                                    parts)}))))
               (take crown-scan-cap scanned))}))))

;; ── compose me another (waymark-jfv.20) ─────────────────────────────
;;
;; THE PERSON PULLS, and the rank puts the answer first. The owner's
;; sentence — *I want to be able to just keep requesting outcomes* —
;; is law 6 (the person spins; the system never spins for them)
;; applied to composition. The ask was born to get a person's own
;; pull past the weekly cap on the composer's initiative; the cap has
;; since left (waymark-1uv.3) and the ask outlived it with a different
;; meaning: a bundle that answers a request stands in the crown's
;; FIRST TIER (`crown-key`), above everything nobody asked for. The
;; ask is a row (`composition_request`, born by one tap, a person's
;; and never an agent's), and the crown is where it lives on the page,
;; because the crown is where the person who asked is already looking.
;;
;; NOT A CARD, and the absence is the design. A card is a projection
;; of a row with a verb on it; the chip is not one, because a request
;; standing open is not something to answer, so it carries no verb at
;; all. `document` hands the whole thing over under one key, `crown`,
;; beside `views` and `reasons`: whether the crown carded nothing,
;; the door the chip knocks on, and the requests this reader already
;; has standing. The chip rides the origin key like a card verb, under
;; a card_id that names the section, the kind the tap creates, and
;; `ask` where a row id would stand — so `origin-of` parses it and
;; `actions-from-feed` counts the pull under `outcomes`, per day.
;;
;; THE CHIP RIDES ALWAYS (waymark-1uv.3 re-read jfv.20's rule). Under
;; the cap the ask rode only when the crown carded nothing, because
;; asking meant *let one more in* and a chip beside an unanswered
;; bundle was the page asking for more before the person had said
;; what they thought of what was there. Under the rank asking means
;; *rank mine first*: a person may want a request standing while two
;; bundles are on offer — neither is the Saturday they had in mind —
;; and the rank puts the answer first when it arrives, so the offer
;; on the page and the ask beside it are not in competition. Holding
;; the chip back would be the page deciding when the person is
;; allowed to want, which is the wall the cap was.

(def ask-card-id
  "The `card_id` the crown's chip rides the origin key under — see the
  block above. Public because the conformance pack mints the same key
  the screen does."
  "outcomes/composition_request/ask")

(defn- reworking-doc
  "THE LINE UNDER THE CROWN (waymark-9xn): how many of this reader's
  bundles are away being reworked, and the sentence that says the
  disappearance was ANSWERED rather than lost.

  It exists because the state alone would be a silence. A person taps
  `iterate`, the bundle leaves the feed — which is exactly what they
  asked for — and the next morning the crown is one card shorter with
  nothing anywhere saying why. That reads as the house forgetting, and
  a system that quietly drops what somebody said is the thing this
  whole kind is against. One line, no card and no verb: there is
  nothing to answer here, which is the point of it being a line.

  It counts what the crown WOULD have shown — the reader's own
  composed bundles are excluded (`the-composer-does-not-decide` makes
  them unanswerable, which is the population's own rule one filter
  over) and a lapsed one is not coming back, so its week is checked
  the way the population checks it. Nil when this engine holds no
  outcome kind, or when nothing of this reader's is being reworked:
  an empty count said out loud would be a line about nothing."
  [ctx]
  (when (get (resources ctx) :outcome)
    (let [pid (:id (:principal ctx))
          now (:now ctx)
          rdef (get (resources ctx) :outcome)
          mine (into []
                     (keep (fn [raw]
                             (let [d (inv/decode-row rdef raw)
                                   good (get-in d [:data :good_until])]
                               (when (and (not= pid (get-in d [:data :composed_by]))
                                          (or (nil? good)
                                              (pos? (compare good now))))
                                 (:id raw)))))
                     (rows-of ctx :outcome {:state "iterating"}))
          n (count mine)]
      (when (pos? n)
        {"count" n
         ;; THE SENTENCE SAYS WHO ASKED ONLY AS "THE HOUSE", and the
         ;; caution is honesty rather than style: nothing on the row
         ;; records which member tapped `iterate` (the note is a remark
         ;; on the thread, said in their own voice, and that is where
         ;; the reader goes for it), so a line telling THIS reader
         ;; "you said the plan was wrong" would be a guess about a
         ;; household with more than one adult in it.
         "says" (str n " bundle" (when (not= 1 n) "s")
                     (if (= 1 n) " is" " are")
                     " being reworked — the house asked for the plan to"
                     " change, and you'll see " (if (= 1 n) "it" "them")
                     " again when the composer answers.")}))))

(defn- crown-says
  "The one sentence the chip stands beside, in the household's words —
  three, since the chip rides whether or not the crown carded anything:
  the standing request, the empty crown, and the crown with bundles on
  offer that are not the one the person had in mind."
  [empty? standing]
  (cond
    (seq standing)
    (str "You asked for another"
         (when (> (count standing) 1)
           (str " — " (count standing) " requests are standing"))
         ". The composer answers at its next sitting, and the answer stands"
         " first in the crown when it arrives, above anything nobody asked"
         " for.")

    empty?
    (str "Nothing composed is on offer. Ask, and the composer stages one"
         " at its next sitting; a bundle you asked for stands first in the"
         " crown.")

    :else
    (str "Not the week you had in mind? Ask, and the composer stages"
         " another at its next sitting — it stands first in the crown when"
         " it arrives, above anything nobody asked for.")))

(defn- crown-doc
  "The crown's own chip and standing (waymark-jfv.20), or nil when this
  engine holds no `composition_request` or no `outcome` kind, or the
  read is an archive page (the crown lives on the day's first page,
  and page two of the archive is not where anybody asks).

  `empty` is whether the crown carded nothing on THIS read — the
  reader's own bundles, lapsed ones and answered ones all having been
  dropped by the population already — and it is said so the screen
  can say it; `ask` rides WHETHER OR NOT the crown is empty
  (waymark-1uv.3; the block above says why the old rule went with the
  cap). `standing` is the reader's own open requests whose week has
  not run out, each with the value it aims at when it names one; it
  rides in every case too, because the sentence 'you asked, and the
  composer has not sat down yet' is true either way and the person
  who asked deserves to read it.

  Under a preview the principal is the PREVIEWED member (`document`'s
  own rule), so the standing requests are theirs and the ask is their
  door — which the router judges the actual caller at, exactly as it
  judges a card verb."
  [ctx cards archive-only?]
  (let [rdef (get (resources ctx) :composition_request)]
    (when (and rdef (get (resources ctx) :outcome) (not archive-only?))
      (let [pid (:id (:principal ctx))
            now (:now ctx)
            empty? (not-any? #(= "outcome" (get % "kind")) cards)
            standing (into []
                           (keep (fn [raw]
                                   (let [d (inv/decode-row rdef raw)
                                         good (get-in d [:data :good_until])
                                         vid (get-in d [:data :value_id])
                                         vname (get-in d [:data :value_name])]
                                     (when (or (nil? good)
                                               (pos? (compare good now)))
                                       (cond-> {"self" (str (collection-of ctx :composition_request)
                                                            "/" (:id raw))
                                                "asked_at" (str (:created-at d))}
                                         good (assoc "good_until" (str good))
                                         (some-> vid str not-empty)
                                         (assoc "value" (str "/api/values/" vid))
                                         (some-> vname str not-empty)
                                         (assoc "value_name" (str vname)))))))
                           (rows-of ctx :composition_request
                                    {:requested_by pid :state "offered"}))
            reworking (reworking-doc ctx)]
        (cond-> {"empty" empty?
                 "card_id" ask-card-id
                 "says" (crown-says empty? standing)
                 ;; …and one line for what is NOT here (waymark-9xn) —
                 ;; see reworking-doc. It rides `crown` beside the ask
                 ;; because that is where the reader is already looking
                 ;; for the bundle that has gone quiet.
                 "ask" {"href" (collection-of ctx :composition_request)
                        "method" "POST"
                        "label" "Compose me another"
                        "note" (str "One tap writes a request under your name;"
                                    " the composer reads it at its next"
                                    " sitting and stages an outcome that"
                                    " cites it, and that one stands first"
                                    " in the crown. It stands a week.")}}
          (seq standing) (assoc "standing" standing)
          reworking (assoc "reworking" reworking))))))

(defn conflicts
  "decide: mirrored rows whose authority and household disagree. A
  conflicted row takes no local writes until a person decides, which
  makes it the most literal decide card in the census — and the kinds
  are read off the DECLARATION (`:mirror` on the rdef), never through
  the mirror module, so an engine that assembled the feed without the
  mirror's routes still surfaces the rows its own kinds declared."
  [ctx]
  (into []
        (mapcat (fn [[k rdef]]
                  (when (:mirror rdef)
                    (candidates-of k (rows-of ctx k {:state "conflicted"})))))
        (sort-by key (resources ctx))))

;; ── fuel: history as momentum (waymark-iqa.5) ───────────────────────
;;
;; Three populations between decide and the seam, and all three are
;; READ-TIME SEEDED QUERIES over rows and logs that already exist
;; (docs/spec-feed.md fork (a)): no materializing job, no hook, no
;; column, no new store method. What makes them fuel rather than a
;; second collection is that each one says something the row itself
;; cannot — the queue is EMPTY, the house has finished something every
;; week SINCE JUNE — and it says it in a `sentence`, the seam's own
;; key, because the seam is already the element of this document that
;; is prose rather than a projection.
;;
;; THE AGGREGATE HALF IS GATED DIFFERENTLY FROM THE ROW HALF, and that
;; is the fourth law read carefully. A row card needs `card`'s `:row?`
;; and nothing more. A COUNT over a kind is a statement about rows the
;; reader may not hold, so `cleared` and `streaks` speak only about
;; kinds this reader sees WHOLE (`seasons/whole-kind-sight?`, the one
;; definition, the same seam the seasons door projects through).
;;
;; AND EACH NAMES AT MOST ONE CANDIDATE PER KIND, deliberately. The
;; mixer's claim is TOTAL — every candidate a population names is that
;; population's for the day, shown or not, which is what keeps a
;; do-now row out of the archive on page four — so a fuel population
;; that named a hundred finished rows to show two would have barred
;; ninety-eight of them from the archive. Fuel is the LAST thing the
;; house finished; everything before it is a memory, and the archive
;; is where memories live.

(def fuel-weeks
  "Weeks of the log one fuel aggregate reads. `seasons` clamps its own
  window to 12 and this is the same number for the same reason: a
  streak nobody could remember starting is a statistic, not fuel."
  12)

(def fuel-window-days
  "How recently a row must have moved to be FUEL rather than a memory.
  It is the one line between the two halves of this bead: inside the
  window a finished row is *what you got done*, outside it the archive
  has it."
  7)

(defn- day-start
  "The feed's own day as an instant. Every fuel and archive window is
  anchored HERE and never on `(:now ctx)`: a window that moved with
  the clock would answer two candidate sets to two reads of one day,
  and the cursor's `:offset` would be walking a set that shifted under
  it. The day is the seed's day, so the windows roll when the feed
  rolls and never between."
  ^Instant [ctx]
  (let [d (LocalDate/parse ^String (:day ctx))]
    (.toInstant (.atStartOfDay d ZoneOffset/UTC))))

(defn- fuel-kinds
  "The kinds fuel speaks about at all: the FRONT-DOOR kinds
  (`:nav :primary`) and no others (waymark-iqa.25).

  Fuel is deeds — what the house got done — and `:nav` is already the
  household's own answer to which rows are things a person does. A
  meal's ingredient line, a substitution, a plan day: those are
  `:secondary` precisely because nobody navigates to them, and a card
  reading *you finished 'Ingredient: 2 tbsp butter'* is the surface
  congratulating somebody on a row that moved. The archive keeps the
  wider list — a memory may be of anything a person can reach — but
  a deed is a front door's."
  [ctx]
  (nav-kinds ctx #{:primary}))

(defn- whole-kinds
  "The kinds a fuel AGGREGATE may speak about: the household's own,
  seen WHOLE. `seasons/whole-kind-sight?` is the one definition of
  that question and this is its second consumer — a count over a kind
  a reader sees only in part is a disclosure with a number on it, so
  the card is simply not built. Fail toward concealment, the seasons
  door's own posture, inherited rather than re-decided."
  [ctx]
  (let [vis (:visibility ctx)]
    (filterv #(or (nil? vis) (seasons/whole-kind-sight? vis %))
             (fuel-kinds ctx))))

(defn- week-starts
  "The `fuel-weeks` UTC week buckets ending with the feed's own day,
  oldest first — `store/utc-week-start`'s truncation, which is the ONE
  truncation every backend's `transition-stats` shares."
  [ctx]
  (let [now-week (store/utc-week-start (day-start ctx))]
    (mapv (fn [i]
            (.minus now-week (* 7 (long (- fuel-weeks 1 (long i)))) ChronoUnit/DAYS))
          (range fuel-weeks))))

(defn- completed-by-week
  "{kind {week-start n}} — how many rows of each kind FINISHED in each
  of the last `fuel-weeks` weeks.

  It is `seasons/report`'s own aggregate minus the aging read the feed
  does not need: `store/transition-stats` bucketed weekly at the store,
  `seasons/classify` deciding from the DECLARATION which action names
  close something, and `include-system? false` dropping the mirror's
  sync beat so a household's week is the household's work rather than
  its plumbing.

  No store method was added and none is admissible: the protocol is
  closed, four stores implement it, and a bespoke aggregate for a fuel
  card would be a core change wearing a module's clothes."
  [ctx kinds]
  (let [st (:storage (:eng ctx))
        from (first (week-starts ctx))
        classifiers (into {}
                          (map (fn [k]
                                 [(name k)
                                  (seasons/classify (get (resources ctx) k))]))
                          kinds)
        stats (store/with-tx st (fn [tx] (store/transition-stats st tx from false)))]
    (reduce (fn [m {:keys [week-start kind action n]}]
              (if-some [cf (get classifiers kind)]
                (if (= :completed (cf action))
                  (update-in m [(keyword kind) week-start] (fnil + 0) (long n))
                  m)
                m))
            {}
            stats)))

(defn- terminal-names [rdef] (mapv name (:terminal rdef)))

(defn- accomplished-names
  "The STATE names a fuel row may rest in: the endings this kind
  stands behind (waymark-iqa.25). Unspelled, that is the terminal set
  and fuel reads exactly as it did; a kind that declares an
  abandonment — a discarded grocery list, an abandoned book — takes it
  out of the deeds without taking it out of the archive.

  A kind whose endings live in a data field (a mirror's) has no state
  to query and answers none: its finished rows reach the archive
  through the log, which is where a finished movie belonged all along."
  [rdef]
  (let [{:keys [field accomplished]} (over-vocabulary rdef)]
    (if field [] (mapv name accomplished))))

(defn- open-state-names [rdef]
  (into [] (comp (remove (set (:terminal rdef))) (map name)) (:states rdef)))

(defn- in-states
  "Rows of one kind in the named states, through the ORDINARY
  collection query grammar: `collections/parse-query` over `state=`
  (which every kind's grammar speaks, declared filters or not), then
  `search-rows` over the conds it compiled. That is the same wire
  grammar a `saved_view`'s `:where` speaks and the same path
  `law_sweep` hands a caller's filters to — never a bespoke SQL query,
  because a second way to ask 'which rows are done' is a second answer
  waiting to disagree.

  `:defaults? false` on purpose: a declared `:default-filters` is a
  COLLECTION's opening view (*offered asks first*), and the fuel
  section is asking a different question of the same rows."
  [ctx kind state-names opts]
  (let [rdef (get (resources ctx) kind)
        st (:storage (:eng ctx))]
    (if-not (seq state-names)
      []
      (try
        (let [{:keys [conds]} (coll/parse-query rdef
                                                {"state" (str/join "," state-names)}
                                                {:defaults? false})]
          (store/with-tx st (fn [tx] (store/search-rows st tx kind conds opts))))
        ;; a kind whose table this engine never made contributes
        ;; nothing; the feed does not get to fail over an assembly
        (catch Exception _ [])))))

(defn- last-finished
  "The kind's most recently moved ACCOMPLISHED row, or nil.
  `:updated_at` is one of the two engine columns
  `store/sortable-timestamps` admits, so this is an ordered LIMIT 1
  rather than a scan.

  Terminal was the old question and it was too wide by exactly one
  word: a grocery list the household DISCARDED is terminal, and it
  carded as *what you got done this week* (waymark-iqa.25). Fuel asks
  the narrower one."
  [ctx kind]
  (first (in-states ctx kind (accomplished-names (get (resources ctx) kind))
                    {:order-by :updated_at :desc true :limit 1})))

(defn cleared
  "fuel: a queue that went to zero.

  Three facts have to line up before this says anything, and each one
  is a cheap query: the kind CAN end (it declares terminal states),
  nothing of it is open right now (one LIMIT 1 over the open states),
  and something of it actually finished inside the aggregate's window
  (so a kind that has been empty since it was declared is not a daily
  achievement). The card is the LAST row finished — the one that
  emptied the list — and the sentence is what the row itself cannot
  say.

  A mirror kind never clears, and that is correct rather than a gap:
  its machine is the sync machine and its states are freshness, so
  there is no state in which its work is over. What ended is `:status`
  in the document, which is the authority's word and not this
  engine's."
  [ctx]
  (let [kinds (whole-kinds ctx)
        done (completed-by-week ctx kinds)]
    (into []
          (keep (fn [k]
                  (let [rdef (get (resources ctx) k)
                        n (reduce + 0 (vals (get done k)))]
                    (when (and (pos? n)
                               (seq (accomplished-names rdef))
                               (empty? (in-states ctx k (open-state-names rdef)
                                                  {:limit 1})))
                      (when-some [row (last-finished ctx k)]
                        {:kind k :id (:id row) :row row :at (:updated-at row)
                         :sentence (str "Nothing is left in " (:plural rdef)
                                        " — " n " finished in the last "
                                        fuel-weeks
                                        " weeks, and this was the last of"
                                        " them.")})))))
          kinds)))

(defn- trailing-run
  "The weeks, newest first, that a kind has finished something in
  without a gap. The CURRENT week is skipped when it is still empty
  rather than counted against the run: on a Monday morning every
  streak in the house would otherwise read as broken, which is a
  calendar artefact and not a fact about anybody."
  [by-week weeks]
  (let [newest-first (reverse weeks)
        from (if (pos? (long (get by-week (first newest-first) 0)))
               newest-first
               (rest newest-first))]
    (vec (take-while #(pos? (long (get by-week % 0))) from))))

(defn streaks
  "fuel: the weeks in a row.

  The same aggregate `cleared` reads, asked the other way: consecutive
  weeks in which SOMETHING of this kind finished. Two weeks is the
  floor because one week is not a run, and the sentence names the week
  it started so the number is checkable against the household's own
  memory rather than being a badge.

  Nothing is stored and nothing decays: a streak is a fact about the
  log, so it is retroactive to every week this engine has ever run,
  and breaking one costs nothing but the sentence."
  [ctx]
  (let [kinds (whole-kinds ctx)
        done (completed-by-week ctx kinds)
        weeks (week-starts ctx)]
    (into []
          (keep (fn [k]
                  (let [rdef (get (resources ctx) k)
                        run (trailing-run (get done k) weeks)]
                    (when (>= (count run) 2)
                      (when-some [row (last-finished ctx k)]
                        {:kind k :id (:id row) :row row :at (:updated-at row)
                         :sentence (str (count run) " weeks running — something"
                                        " has finished in " (:plural rdef)
                                        " every week since "
                                        (LocalDate/ofInstant ^Instant (last run)
                                                             ZoneOffset/UTC)
                                        ".")})))))
          kinds)))

(defn finished
  "fuel: what the house got done this week.

  One row per kind — the last one finished, inside `fuel-window-days`
  — through the ordinary collection grammar. No sentence: the row's
  own summary is already the sentence, which is the whole reason the
  card is `envelope-summary` and not a rendering of its own.

  It reads the fuel kinds without `whole-kinds`' sight test, and that
  is the aggregate/row line: this card claims nothing about rows the
  reader cannot see, so `card`'s `:row?` gate is the only one it
  needs."
  [ctx]
  (let [cutoff (.minus (day-start ctx) (long fuel-window-days) ChronoUnit/DAYS)]
    (into []
          (keep (fn [k]
                  (when-some [row (last-finished ctx k)]
                    (when (some-> ^Instant (:updated-at row) (.isAfter cutoff))
                      {:kind k :id (:id row) :row row :at (:updated-at row)}))))
          (fuel-kinds ctx))))

(defn events
  "archive: the household's rows that moved lately, one card per row,
  newest transition wins. The transition log has been a complete record
  of every write this engine ever made and nothing read it as a feed;
  this is the bottomless half — stateless and LONG, never infinite, and
  the document says which when the fold reaches its cap.

  It draws from the kinds a person navigates to, never from the
  `:nav :system` machinery, and the reason is the epic's rather than
  taste: the archive is history AS FUEL. A law revision and a member
  row are true things that happened and neither is anybody's memory of
  a day. Which leaves — since every OPEN front-door row is already a
  do-now candidate and the mixer never cards a row twice — the rows
  the household FINISHED, newest move first. That is the archive this
  bead can honestly build.

  IT IS NO LONGER THE RECIPE'S ARCHIVE ENTRY AND IT DID NOT SHRINK
  (waymark-iqa.8): `memories` below is the bottomless tail, and this
  is one of the two sources it reads. Check (3) admits exactly one
  bottomless section, so the reconciliation could not be *both
  entries, one under the other* — but nothing about the interleave
  wanted to be two entries. It is one population with two ways of
  remembering, and this is the one that never runs out. It stays in
  the registry on its own account, because a household that wants a
  plain log of what moved should be able to say so in a recipe."
  [ctx]
  (let [st (:storage (:eng ctx))
        log (store/with-tx st
              (fn [tx] (store/transitions st tx {} {:limit log-scan-cap
                                                    :newest-first true})))
        household (set (nav-kinds ctx #{:primary :secondary}))]
    {:reached-cap (= (count log) log-scan-cap)
     :candidates (into []
                       (comp (filter #(contains? household (keyword (:kind %))))
                             (map (fn [tr] {:kind (keyword (:kind tr))
                                            :id (:resource-id tr)
                                            :at (:at tr)}))
                             ;; newest first, so the first sighting of a
                             ;; row is its latest move — one card per
                             ;; row, never one per transition
                             (dedupe-by (juxt :kind :id)))
                       log)}))

(def anniversary-weeks
  "How far back 'a year ago this week' reaches. 52 weeks rather than
  one calendar year so the WEEKDAY lines up: a Tuesday in August is
  read against a Tuesday in August, and `store/utc-week-start` then
  picks out that week whole."
  52)

(defn anniversaries
  "archive: what the house was doing a year ago this week.

  `history/collection-as-of` is the read — the fold from the beginning
  of a kind's log to the rows that existed then, in the states they
  held, projected through `:row?` exactly as the live collection is —
  and the window keeps the items whose last move BEFORE that instant
  falls inside the week itself. So this is not 'rows that existed a
  year ago' (which is most of them) but 'rows somebody was working on
  a year ago this week', which is the only version of it that reads
  like a memory.

  THE SENTENCE IS THE TRANSITION'S OWN STORED SUMMARY — what `invoke`
  rendered on the day — and never a re-render of today's row against
  yesterday's law. That is docs/spec-time-travel.md's tier-3 punt,
  inherited whole: a card built from the log says what HAPPENED, never
  what the row looked like, and a transition's `inputs` are not read
  here at all — they have no field projection of their own and growing
  one would be a second visibility surface. The card BODY is
  the row as of now, which is the departure `card` documents and the
  reason a card's own href is safe to follow.

  ONE QUERY GATES THE EXPENSIVE HALF. Each kind's fold costs up to
  `history/fold-cap` transitions, so the oldest transition in the log
  is read first (one row, ascending, LIMIT 1): a house younger than
  the window has no anniversary of any kind and pays for exactly that
  one read. `complete` false on any fold is reported as the cap being
  reached — truncation announced beats totality implied."
  [ctx]
  (let [eng (:eng ctx)
        st (:storage eng)
        start (store/utc-week-start (.minus (day-start ctx)
                                            (* 7 (long anniversary-weeks))
                                            ChronoUnit/DAYS))
        end (.plus start 7 ChronoUnit/DAYS)
        oldest (:at (first (store/with-tx st (fn [tx] (store/transitions
                                                       st tx {} {:limit 1})))))
        in-week? (fn [^Instant at]
                   (and at (not (.isBefore at start)) (.isBefore at end)))]
    (if-not (and oldest (.isBefore ^Instant oldest ^Instant end))
      {:candidates [] :reached-cap false}
      (reduce
       (fn [acc k]
         (let [rdef (get (resources ctx) k)
               doc (try (history/collection-as-of eng rdef end {} (:visibility ctx))
                        (catch Exception _ nil))
               items (filterv #(in-week? (:at %)) (get-in doc [:data :items]))]
           (-> acc
               (update :reached-cap #(or % (and (some? doc)
                                                (false? (get-in doc [:data :complete])))))
               (update :candidates into
                       (map (fn [i]
                              {:kind k
                               :id (:id i)
                               :at (:at i)
                               :sentence (str "A year ago this week: "
                                              (or (not-empty (str (:summary i)))
                                                  (str (some-> (:action i) name)
                                                       " · "
                                                       (some-> (:state i) name))))}))
                       items))))
       {:candidates [] :reached-cap false}
       (nav-kinds ctx #{:primary :secondary})))))

(defn memories
  "archive: the bottomless interleave — one population, two ways of
  remembering, seeded together into one order by the mixer.

  `anniversaries` first and `events` behind it, and the order is what
  decides a tie rather than a ranking: a row that carries a story from
  a year ago keeps the story, and a row that only moved keeps its own
  summary. `dedupe-by` is the one-card-per-row rule, applied across
  the sources exactly as `events` applies it across transitions.

  'BOTTOMLESS' MEANS STATELESS AND LONG, NOT INFINITE, and the spec
  says so rather than letting the UI find out at the bottom: this is a
  seeded ordering over a BOUNDED candidate set, `:offset` walks it,
  and when it runs out the document omits `links.next`."
  [ctx]
  (let [ann (anniversaries ctx)
        ev (events ctx)]
    {:reached-cap (boolean (or (:reached-cap ann) (:reached-cap ev)))
     :candidates (into []
                       (dedupe-by (juxt :kind :id))
                       (concat (:candidates ann) (:candidates ev)))}))

(def populations
  "Every population this engine can name, and the whole of what a
  recipe may ask for. A closed map, never a classpath scan, for the
  reason `waymark10.modules` gives at length: a discovered feed would
  serve a different order in a dev REPL than in a container with one
  extra jar on the path, silently.

  A population is `(fn [ctx])` over {:eng :principal :visibility :now
  :seed :day}, answering CANDIDATES — `{:kind :id}` maps, optionally
  carrying the raw `:row` it already read, the `:at` its card should
  show, the `:sentence` the card says on its own behalf, and the
  `:lane` it holds in a SPREAD. It may answer a bare vector, or
  {:candidates […] :reached-cap bool} when it scanned to a cap and the
  document should say so. It never renders, never projects and never
  sorts: the mixer does all three, once, so the fourth law is enforced
  in one place.

  `:lane` is the one addition since `.2` and it is not an ordering of
  its own (waymark-iqa.24). Every candidate is lane 0 unless a
  population says otherwise, and a population that draws from several
  kinds hands each candidate its place in its OWN kind's seeded order.
  The mixer then sorts by lane and, inside a lane, by the same hash it
  always used — a round-robin, which is composition, not a score.

  The registry is complete against docs/spec-feed.md's census as of
  waymark-iqa.6, and it grew one entry at a time: each bead added its
  entry HERE and its line in `default-recipe`, together — `:ticklers`
  (waymark-iqa.4), the fuel populations and `:memories`
  (waymark-iqa.5), `:insights` (waymark-iqa.6), `:proposals`
  (waymark-0k4), `:outcomes` (waymark-jfv.4, and the first entry whose
  section was new too). A later population arrives the same way, and swapping
  one entry for a materializing read is fork (a)'s recorded punt
  working exactly as promised."
  {:outcomes outcomes
   :next_actions next-actions
   :asks asks
   :letters letters
   :ticklers ticklers
   :insights insights
   :proposals proposals
   :conflicts conflicts
   :cleared cleared
   :streaks streaks
   :finished finished
   :events events
   :memories memories})

;; ── the recipe's assembly checks ────────────────────────────────────

(defn- refuse [msg data]
  (throw (t/definition-error (str "feed recipe: " msg)
                             (merge {:check :feed-recipe} data))))

(defn check-recipe!
  "The four checks the spec asks for, run at the route's BUILD site so
  a bad recipe refuses the boot rather than the request — the way
  `modules/selected` refuses an unknown label:

    1. every `:population` names a member of the closed registry;
    2. exactly one entry carries `:seam true`;
    3. at most one `:bottomless`, and it is last;
    4. sections appear in census order;
    5. the contest's two numbers are numbers (waymark-8um.3).

  Plus the shape of `:kinds`, the one entry key that arrived after
  `.2` (waymark-iqa.24): an optional vector of kind keywords
  narrowing that LINE's candidates. A household with a work queue it
  means to see every morning writes two do-now lines — the queue's,
  then everything else — and the mixer's total claim makes them
  disjoint for free, because the first line claims every task whether
  it showed one or five. It is the recipe doing the only thing the
  recipe has ever done: saying, in static data, what this house reads
  first.

  …and of `:says`, the second such key (waymark-iqa.29): the line's
  own sentence in the household's own words, for the narrated recipe
  the feed now serves. Optional — a line with none narrates itself
  from its section, its take, its `:kinds` and its population's own
  sentence — and free of any fingerprint, because the recipe is an
  engine opt and not a declaration.

  Returns the recipe, so a build site reads
  `(feed/check-recipe! (:feed eng feed/default-recipe))` and has both."
  [recipe]
  (when-not (map? recipe)
    (refuse "is a map of {:salt :zone :order}" {:recipe recipe}))
  (let [order (:order recipe)]
    (when-not (and (sequential? order) (seq order))
      (refuse ":order is a non-empty vector of entries" {:order order}))
    (try (ZoneId/of (:zone recipe "UTC"))
         (catch ZoneRulesException _
           (refuse (str ":zone " (pr-str (:zone recipe))
                        " is not a zone this JVM knows")
                   {:zone (:zone recipe)})))
    (doseq [e order]
      (when-not (map? e) (refuse "every entry is a map" {:entry e}))
      (when-not (or (:seam e) (contains? populations (:population e)))
        (refuse (str "no population named " (pr-str (:population e))
                     " — this engine offers " (vec (sort (keys populations))))
                {:population (:population e)}))
      (when-not (or (:seam e) (pos-int? (:take e)))
        (refuse (str (pr-str (:population e))
                     " must declare a positive :take — how many cards it"
                     " contributes to one page")
                {:entry e}))
      (when-some [ks (:kinds e)]
        (when-not (and (sequential? ks) (seq ks) (every? keyword? ks))
          (refuse (str (pr-str (:population e))
                       " :kinds is a non-empty vector of kind keywords —"
                       " the line a household dedicates to particular rows")
                  {:entry e})))
      (when-some [s (:says e)]
        (when-not (and (string? s) (seq (str/trim s)))
          (refuse (str (pr-str (:population e))
                       " :says is a sentence in the household's own words,"
                       " or absent — the line then narrates itself"
                       " (waymark-iqa.29)")
                  {:entry e}))))
    (let [seams (filterv :seam order)]
      (when-not (= 1 (count seams))
        (refuse (str "exactly one entry carries :seam true, found "
                     (count seams)
                     " — a feed with no seam never finishes, and a feed with"
                     " two says 'that's everything' twice")
                {:seams (count seams)})))
    (let [bottomless (filterv :bottomless order)]
      (when (> (count bottomless) 1)
        (refuse (str "at most one :bottomless entry, found " (count bottomless))
                {:bottomless (count bottomless)}))
      (when (and (seq bottomless) (not (:bottomless (last order))))
        (refuse (str "the :bottomless entry is LAST — a section that never"
                     " ends cannot have anything below it")
                {:last (last order)})))
    ;; …and the fifth (waymark-8um.3): the contest's two numbers are
    ;; numbers. A formula is DATA a household reads and edits, so it is
    ;; judged here beside the order rather than trusted at read time —
    ;; the same reason the four above run at the door: a recipe that
    ;; will not assemble refuses where it is written.
    (when-some [f (:formula recipe)]
      (when-not (map? f)
        (refuse (str ":formula is a map of {:window-days :cools-after} — the"
                     " two numbers the contest is made of, or absent for the"
                     " deployment's own")
                {:formula f}))
      (doseq [[k lo hi what]
              [[:window-days 1 365 "how far back the counting looks"]
               [:cools-after 0 365 (str "how many days a card may sit unacted"
                                        " on before it cools a step — 0 is"
                                        " the contest turned off")]]]
        (when-some [v (get f k)]
          (when-not (and (int? v) (<= (long lo) (long v) (long hi)))
            (refuse (str ":formula " k " is " what ", " lo "–" hi
                         " — read " (pr-str v))
                    {:formula f})))))
    ;; …and the sixth (waymark-1uv.2): the crown's six numbers are
    ;; numbers, for the fifth check's own reason. Zero is legal for
    ;; every one of them — all six at zero is the seed alone, with a
    ;; person's own request still first.
    (when-some [c (:crown-rank recipe)]
      (when-not (map? c)
        (refuse (str ":crown-rank is a map of {:declared :cooled :declined"
                     " :fresh :early :judged} — the six numbers the crown's"
                     " rank is made of, or absent for the deployment's own")
                {:crown-rank c}))
      (doseq [[k what]
              [[:declared "what a declared value lifts a bundle over an observed one"]
               [:cooled "what each cooled step holds a bundle back"]
               [:declined "what each rank of the house's quick word holds a bundle back"]
               [:fresh "what each day left on its week lifts a bundle"]
               [:early "what each day early a recomposition arrives holds it back"]
               [:judged "how far an agent's own score, 0 to 1, moves a bundle either way"]]]
        (when-some [v (get c k)]
          (when-not (and (int? v) (<= 0 (long v) 100))
            (refuse (str ":crown-rank " k " is " what ", 0–100 — read "
                         (pr-str v))
                    {:crown-rank c})))))
    ;; …and the seventh (waymark-1uv.8): the findings' six numbers are
    ;; numbers, for the sixth check's own reason. Zero is legal for
    ;; every one of them — all six at zero is the seed alone.
    (when-some [c (:insight-rank recipe)]
      (when-not (map? c)
        (refuse (str ":insight-rank is a map of {:diagnosis :declared :cooled"
                     " :dismissed :declined :fresh} — the six numbers the"
                     " findings' rank is made of, or absent for the"
                     " deployment's own")
                {:insight-rank c}))
      (doseq [[k what]
              [[:diagnosis "what a law-4 diagnosis is lifted over a plain finding"]
               [:declared "what a finding whose offered row serves a declared value is lifted"]
               [:cooled "what each cooled step holds a finding back"]
               [:dismissed "what each finding the house dismissed on the same offer holds it back"]
               [:declined "what each rank of the house's quick word on those dismissals holds it back"]
               [:fresh "what each day of freshness left in the window lifts a finding"]]]
        (when-some [v (get c k)]
          (when-not (and (int? v) (<= 0 (long v) 100))
            (refuse (str ":insight-rank " k " is " what ", 0–100 — read "
                         (pr-str v))
                    {:insight-rank c})))))
    ;; …and the seventh (waymark-1uv.9): the fridge's five numbers are
    ;; numbers, for the sixth check's own reason. Zero is legal for
    ;; every one of them — all five at zero is the seed alone, with a
    ;; person's own hand still first.
    (when-some [c (:tickler-rank recipe)]
      (when-not (map? c)
        (refuse (str ":tickler-rank is a map of {:overdue :not_now :cooled"
                     " :front_door :age} — the five numbers the ticklers"
                     " line's rank is made of, or absent for the"
                     " deployment's own")
                {:tickler-rank c}))
      (doseq [[k what]
              [[:overdue "what each day past its own date lifts a set-aside item"]
               [:not_now "what each not-now already said holds a set-aside item back"]
               [:cooled "what each cooled step holds a set-aside item back"]
               [:front_door "what a subject this house goes to lifts a set-aside item"]
               [:age "what each month on the dropped pile lifts a set-aside item"]]]
        (when-some [v (get c k)]
          (when-not (and (int? v) (<= 0 (long v) 100))
            (refuse (str ":tickler-rank " k " is " what ", 0–100 — read "
                         (pr-str v))
                    {:tickler-rank c})))))
    (reduce (fn [seen e]
              (let [s (if (:seam e) :seam (:section e))
                    r (census-rank s)]
                (when (nil? r)
                  (refuse (str "no section named " (pr-str s) " — the census is "
                               (vec census))
                          {:section s}))
                (when (< (long r) (long seen))
                  (refuse (str (name s) " appears after a later section — the"
                               " census is law, so a recipe out of its order is"
                               " a typo, not a preference: " (vec census))
                          {:section s}))
                r))
            0
            order))
  recipe)

;; ── the ≤-selection partition ───────────────────────────────────────

(def card-ceiling
  "The heaviest demand a card may put under the thumb —
  `demand.clj`'s own vocabulary, the class name as a string because
  that is what a rendered action entry carries. assent is one tap and
  selection is choosing rather than typing; recall and composition
  need a keyboard, a form and a way back, which is a screen.

  It is a var rather than a literal for one reason: `heavier?` is
  asked here and in `waymark10.usability`, and a second spelling of
  the ceiling would be a second opinion about what fits under a
  thumb."
  "selection")

(defn screen-of
  "The row's own SCREEN, from its API href. The generic UI's URL hash
  IS the resource href — `routes/ui.clj` serves one page and the row
  behind `/#/api/tasks/01HZ…` is the row behind `/api/tasks/01HZ…` —
  so a screen is `/#` and the `self` a card already carries. Core
  spells it this way already: the agent door's `:handoff` template
  (`router.clj`) hands a human `/#/api/approval_requests/{ask-id}`
  with the note *'it opens the ask directly'*, and
  `workqueue10.sources.waymark/with-origin` derives `source_ui_href`
  the same way for the same reason.

  DELIBERATELY NOT the card's `self`, which docs/spec-feed.md's
  illustrative JSON sketches. A card already carries `self`; naming
  the same API address twice would say nothing, and worse, it would
  invite a client to treat a `heavier` entry as a door it could POST
  to. A heavier entry is a place to GO, not a verb to fire — that is
  the whole distinction the partition exists to draw."
  ^String [^String self]
  (str "/#" self))

(defn- heavier-entry
  "One heavier verb, as the card names it: `{name effort label href}`.
  The label is the action's own display label, falling back to the
  humanized action name — `render/no-admissible-entry`'s spelling
  exactly, so a card and a refusal call the same door the same thing.
  The href is the ROW's screen and never the action's own href: the
  card is saying *this door is real and it is over there*, not
  offering it."
  [self [aname entry]]
  {"name" aname
   "effort" (get entry "effort")
   "label" (or (get-in entry ["display" "label"])
               (str/replace (str aname) "_" " "))
   "href" (screen-of self)})

(defn split-verbs
  "The ≤-selection partition (waymark-iqa.3), over the ALREADY
  PROJECTED body: the survivors of effort ≤ `card-ceiling` stay in
  `actions`, the rest become `heavier` entries pointing at the row's
  own screen.

  It reads `(get body \"actions\")` and NOTHING ELSE — not the rdef,
  not the machine, not the visibility. That is the design, not a
  convenience: `card` has already dropped every action this reader's
  grant conceals from `actions` and `unavailable` alike, so a
  partition that can only see what survived structurally cannot name
  a door the grant hid. The moment this function needed the
  declaration to answer a question about an action, the concealment
  would be one bug away from narration.

  `heavier` exists so the card does not lie. Silently dropping an
  action a reader HOLDS is `router.clj`'s own *'a surface that
  silently stopped existing'* in another register — and it is a
  different failure from concealment, which is the grant's answer and
  is supposed to be silent.

  Entries are name-ordered so two reads of one day answer one wire."
  [body self]
  (let [actions (get body "actions")
        heavy? (fn [[_ entry]]
                 (demand/heavier? (get entry "effort") card-ceiling))]
    (assoc body
           "actions" (into {} (remove heavy?) actions)
           "heavier" (into []
                           (comp (filter heavy?)
                                 (map #(heavier-entry self %)))
                           (sort-by key actions)))))

;; ── the origin convention ───────────────────────────────────────────

(def origin-prefix
  "The `Idempotency-Key` prefix a card verb rides under. One string,
  named once, because waymark-iqa.7's client must send exactly what
  `origin-of` reads."
  "feed")

(defn- url-encode ^String [^String s] (URLEncoder/encode s "UTF-8"))
(defn- url-decode ^String [^String s] (URLDecoder/decode s "UTF-8"))

(defn origin-key
  "The `Idempotency-Key` a client sends when it invokes FROM a card:

      feed/2026-08-24/do_now%2Ftask%2F01HZ…/9f3c1a

  Four slash-separated segments — the prefix, the feed's day, the
  card's id percent-encoded (a `card_id` carries slashes of its own,
  and a metric that could not tell them from the key's would be a
  metric that guessed), and a nonce.

  NO NEW COLUMN, and that is the point. `invoke/finish!` stamps a
  present idempotency key into the transition row whether or not the
  action is idempotent — and since waymark-jfv.20 `create-in-tx!`
  stamps it on a birth's transition too, so a create tapped from a
  card (a quick reason, the crown's own ask) counts the same as a
  move — so actions-from-the-feed is one prefix away —
  per day, per section, per kind, forever, and RETROACTIVE to the day
  the convention lands. Two alternatives were weighed and rejected in
  docs/spec-feed.md: a new `origin` column (a migration and the
  store's four-edit, for a metric) and `correlation_id` (engine-minted
  for cascade parentage; the router never lets a caller set it, and
  one column meaning two things is the failure a sibling spec refuses
  in another register).

  THE NONCE IS LOAD-BEARING. `idempotency-lookup` is scoped (key,
  kind) and `p/idempotency-key-reuse` throws when one key returns with
  a different digest, so two taps of one verb on one card on one day
  must not collide — and a replayed tap that SHOULD collide is the
  client resending the same nonce, which is the header's declared job
  working normally."
  ^String [^String day ^String card-id ^String nonce]
  (str origin-prefix "/" day "/" (url-encode card-id) "/" nonce))

(defn origin-of
  "The feed origin a key names, or nil for every key that is not one —
  `{:day :card-id :section :kind :id :nonce}`. A key of any other
  shape is somebody else's idempotency key and this reader says so by
  answering nil rather than by guessing.

  The `card_id` is `section/kind/id` (`card-id` above), so the three
  names the recipe declared come back out of the audit trail without
  a join. A seam has no verb, so no key ever names one."
  [k]
  (when (string? k)
    (let [segs (str/split k #"/")]
      (when (and (= 4 (count segs)) (= origin-prefix (first segs)))
        (let [cid (url-decode (nth segs 2))
              [section kind id] (str/split cid #"/" 3)]
          (when (and (not-empty section) (not-empty kind) (not-empty id))
            {:day (nth segs 1)
             :card-id cid
             :section section
             :kind kind
             :id id
             :nonce (nth segs 3)}))))))

(defn actions-from-feed
  "The success metric, made queryable: how many writes a day's feed
  produced, by section, kind and action.

  It folds the newest `:limit` transitions (`log-scan-cap` by default)
  and keeps the ones whose `idempotency_key` `origin-of` recognizes,
  optionally narrowed to one `:day`. → `{:day :total :by-section
  :by-kind :by-action :scanned :reached-cap}`.

  THE TRADE, RECORDED. `store/transitions` takes `{:kind :resource-id
  :since}` and no LIKE, so this is a bounded newest-first window
  scanned in memory rather than a prefix predicate pushed into
  Postgres. That is deliberate: the alternative is a new argument on a
  protocol method four stores implement, bought for an ad-hoc number,
  and the epic's own posture is that this metric is derived until it
  earns more. The bound is announced the way `history/fold-cap`
  announces its own — `:reached-cap` says the window filled, because
  truncation announced beats totality implied — and `:since` is
  already there for a caller walking further back a page at a time. If
  actions-from-the-feed ever becomes a first-class report rather than
  a question somebody asks at a REPL, the predicate (and then the
  column) earns its place then.

  Time-on-feed is NOT measured, on purpose. If this surface works,
  people close it sooner."
  ([eng] (actions-from-feed eng {}))
  ([eng {:keys [day limit since]}]
   (let [st (:storage eng)
         n (long (or limit log-scan-cap))
         log (store/with-tx st
               (fn [tx] (store/transitions st tx (cond-> {} since (assoc :since since))
                                           {:limit n :newest-first true})))
         hits (into []
                    (keep (fn [tr]
                            (when-some [o (origin-of (:idempotency-key tr))]
                              (when (or (nil? day) (= day (:day o)))
                                (assoc o :action (name (:action tr))
                                       ;; the transition's OWN kind is the
                                       ;; authority; the card id's is the
                                       ;; client's claim about itself
                                       :kind (name (:kind tr)))))))
                    log)]
     {:day day
      :total (count hits)
      :by-section (frequencies (map :section hits))
      :by-kind (frequencies (map :kind hits))
      :by-action (frequencies (map (fn [h] (str (:kind h) "." (:action h)))
                                   hits))
      :scanned (count log)
      :reached-cap (= (count log) n)})))

;; ── one card ────────────────────────────────────────────────────────

(defn- ctx-opts
  "The one ctx-opts every card body is rendered through — identity,
  clock, services, THE VISIBILITY, and the kind map link targets
  resolve through. `router/render-opts` for a route that answers a
  document instead of an envelope."
  [ctx]
  (cond-> {:principal (:principal ctx)
           :now (:now ctx)
           :services (:services (:eng ctx))
           :visibility (:visibility ctx)
           :resources (resources ctx)}
    ;; …and the probe's read hooks when the engine carries them
    ;; (waymark-1pq, waymark-1zq). `router/render-opts` has always
    ;; merged these and this map was a hand-built twin that forgot
    ;; them, so a card's verbs were the OPTIMISTIC advertisement while
    ;; the row's own screen showed the honest one — two surfaces
    ;; disagreeing about the same door. It bit where a guard judges
    ;; against another ROW: the letter whose address names a member
    ;; row (`opener-is-recipient`) carded with no Open at all, on the
    ;; one reader's feed it was written for.
    ;;
    ;; ONE instance per READ, minted in `document` and carried in the
    ;; ctx — the router mints one per request for its cache's scope,
    ;; and a fresh cache per card would pay for the same member row
    ;; once a card instead of once a page.
    (:render-hooks ctx) (merge (:render-hooks ctx))))

(defn- load-decoded [ctx rdef id]
  (let [st (:storage (:eng ctx))]
    (some->> (store/with-tx st #(store/load-row st % (:kind rdef) id {}))
             (inv/decode-row rdef))))

(defn- piece-card
  "One PIECE of a bundle, as a mini-card — the one wire widening the
  outcome epic asks for (waymark-jfv.4).

  It is `card`'s own three gates, in `card`'s own order, over a
  different row: `:row?` first (a piece this grant does not confer is
  ABSENT from the bundle, never narrowed), then `envelope-summary`
  with `:visibility`, then `split-verbs` over what survived. So a
  piece's chips are the piece ROW's own projected verbs — `take`,
  `not_this`, `moot`, each note-free and input-free and therefore
  `assent`, which is why they stay under a thumb — and concealment
  holds exactly as it does for the parent. Nothing here re-derives a
  verb and nothing here consults the declaration; if it did, the
  partition could name a door the projection had just hidden.

  It carries its OWN `card_id` (`<section>/outcome_piece/<id>`), so a
  piece verb rides `origin-key` like any other card verb and
  `actions-from-feed` counts the tap that actually happened rather
  than attributing it to the bundle.

  The `says` is the population's, the `sentence` precedent one row
  down: a piece's whole claim is what it IS, and the summary line the
  wire projects wears the state on the end of it.

  And `impact` beside it (waymark-jfv.17) is the OTHER voice on the
  same line: `says` is the composer's prose and `impact` is the
  engine's reading of the tap, computed at staging from the prepared
  input and stored on the row. Both carry the prepared work's own
  words, so they are one sensitivity class and this gate is the right
  one for both — a piece a reader does not hold is absent, sentence
  and all."
  [ctx section {:keys [kind id row says impact]}]
  (let [rdef (get (resources ctx) kind)
        vis (:visibility ctx)]
    (when (and rdef (or (nil? vis) ((:row? vis) kind id)))
      (when-some [decoded (if row (inv/decode-row rdef row)
                              (load-decoded ctx rdef id))]
        (let [body (dissoc (render/envelope-summary rdef decoded (ctx-opts ctx))
                           "waymark" "unavailable")]
          (cond-> (assoc (split-verbs body (get body "self"))
                         "card_id" (card-id section kind id))
            (not (str/blank? (str says))) (assoc "says" says)
            (not (str/blank? (str impact))) (assoc "impact" impact)))))))

(defn- card
  "One card, or nil — the ONE place a row becomes wire, so the fourth
  law is enforced once and inherited by every population.

  The order of the three gates is the security property. `:row?`
  first: a row this grant does not confer is not narrowed, it is
  ABSENT, the same concealment the router answers a scoped collection
  with. Then `envelope-summary` with `:visibility`, which drops every
  ungranted action from `actions` and `unavailable` alike. Only THEN
  `split-verbs`, over what survived — never before, or the partition
  would name doors the projection just concealed.

  A candidate whose row has vanished between the population's scan and
  this read is simply no card: the feed is a read, and a read that
  404'd because a row retired mid-page would make the whole day's order
  a coin flip.

  Two keys of the summary are dropped and neither is a projection:
  `waymark`, because a card is an element of a document rather than a
  document, and `unavailable`, because a card has no room for the
  narration of doors that are shut. What a reader HOLDS is never
  dropped — it is `actions` plus `heavier`, and the whole point of
  `heavier` is that a card does not lie about a door it has.

  A candidate carrying `:parts` cards as a BUNDLE (waymark-jfv.4):
  each part goes through `piece-card` — the same three gates over the
  child row — and the survivors ride as `pieces`. A part the grant
  conceals is simply absent from the vector, and a bundle whose parts
  are all concealed carries no `pieces` key at all rather than an
  empty one, because a client that saw `pieces: []` would have to
  decide whether the bundle was empty or hidden."
  [ctx section population {:keys [kind id row at sentence parts impact]}]
  (let [rdef (get (resources ctx) kind)
        vis (:visibility ctx)]
    (when (and rdef (or (nil? vis) ((:row? vis) kind id)))
      (when-some [decoded (if row (inv/decode-row rdef row)
                              (load-decoded ctx rdef id))]
        (let [body (dissoc (render/envelope-summary rdef decoded (ctx-opts ctx))
                           "waymark" "unavailable")
              pieces (when (seq parts)
                       (into [] (keep #(piece-card ctx section %)) parts))]
          (cond-> (assoc (split-verbs body (get body "self"))
                         "card_id" (card-id section kind id)
                         "section" (name section)
                         "population" (name population))
            at (assoc "at" (str at))
            ;; the seam's own key, and deliberately the same one: a
            ;; `sentence` is the element of this document that is
            ;; PROSE rather than a projection, and a fuel card says
            ;; the thing its row cannot say about itself
            sentence (assoc "sentence" sentence)
            ;; the ENGINE's line beside the composer's (waymark-jfv.17):
            ;; on a bundle it is the union its own verb would take. It
            ;; is not gated on the pieces surviving projection, and
            ;; that is the honest way round: it states what THIS
            ;; reader's tap would do, which is the same true thing
            ;; whether or not their leash lets them read the parts
            (not (str/blank? (str impact))) (assoc "impact" impact)
            (seq pieces) (assoc "pieces" pieces)))))))

(defn- offers-something?
  "Does this card put a verb under the thumb? do-now's own filter: a
  next action with no available action is a row on a list.

  It reads `actions` AFTER the ≤-selection partition, so a row whose
  only surviving verb is a composition drops out of do-now with its
  `heavier` link. That is the section's own bargain rather than an
  oversight — do-now is the one physical next action under the thumb,
  and a card there that could only send you somewhere else to type is
  a link wearing a verb's clothes. The row is still the household's:
  it keeps its own screen, its collection and (once its last move is
  its latest) the archive."
  [c]
  (seq (get c "actions")))

(defn- finished-history?
  "The ARCHIVE's own gate (waymark-iqa.25): is this candidate's row
  over AS IT STANDS NOW?

  The archive's two ways of remembering both look at the PAST — a
  transition from a year ago, a row that moved lately — and neither
  of them says a word about where the row is today. So four shows the
  household is halfway through carded as memories, under the seam,
  wearing their full verb sets: an active row dressed as history,
  which reads as the surface having lost track of what is going on.
  `.5`'s own report claimed the archive was 'effectively the rows the
  household finished'; this is that sentence made true rather than
  merely hoped for.

  It is a POINT READ per candidate WALKED, not per candidate named —
  the mixer's `keep-indexed` is lazy and `take` short-circuits it — so
  a page of six pays for six or seven, exactly as the card renders
  do. A row that vanished between the scan and this read is no card
  either way."
  [ctx {:keys [kind id row]}]
  (boolean
   (when-some [rdef (get (resources ctx) kind)]
     (when-some [raw (or row (load-raw ctx kind id))]
       (work-over? rdef raw)))))

;; ── the citation: why this card is here (waymark-iqa.29) ────────────
;;
;; The owner opened the feed, found a movie in do-now, and could not
;; find out why. Four layers had agreed to put it there — a framework
;; predicate (`work-over?`), a declared trait (`:nav`, `:over`), a
;; recipe line, and the day's seed — and not one of them said so
;; anywhere a person could read. In an engine whose signature move is
;; that refusals narrate themselves, that is a surface publishing
;; without citing.
;;
;; So every card cites itself, and the whole of it is a PROJECTION:
;; the population is named, the traits are declared, the recipe is
;; data, and the seed already decided the order. Nothing below infers
;; anything, and nothing below invents prose a declaration does not
;; carry — where a trait is spelled, the sentence quotes the spelling.
;;
;; THE COST, DECIDED. The citation's inputs split cleanly in two: the
;; RECIPE half is one narration per line, identical for every card
;; that line admitted, and the CARD half is three or four numbers. So
;; the recipe half rides the document ONCE (`recipe` below) and the
;; card half rides every card always (`why` — line, rank, of), which
;; costs about thirty bytes. The PROSE half — the assembled sentences,
;; which would repeat one kind's `:over` on every card of that kind —
;; is `?explain=1`, and the law that makes an opt-in read sound is the
;; feed's own: `:feed/day-stable` says two reads by one member on one
;; day answer the same cards in the same order, so a citation fetched
;; late lines up by `card_id` and cannot be a different day's feed —
;; and `:feed/deal-again` says the same of a DRAW (waymark-8um.2), so
;; a late read of an address that carries one lines up too, provided
;; the client asks the address it was answered at (`self` carries the
;; draw for exactly this reason).
;; Always-on would have doubled a fuel card, which is mostly a
;; sentence already, for a disclosure most reads never open.

(def population-says
  "What each population goes looking for, in household words. It is
  the framework describing its own code — a population is a `defn` up
  this file, so this is the one place its intent can be said in a
  sentence a parent reads on a phone. A recipe line may override the
  whole narration with `:says`; this is what a line that says nothing
  falls back to."
  {:outcomes "what this week could hold — composed bundles, with the friction already paid, waiting on a thumb"
   :next_actions "rows nobody has finished yet, from the kinds this house goes to"
   :asks "access somebody has asked for and somebody else must answer"
   :letters "mail on your shelf you have not opened"
   :ticklers "things you set aside, whose date has come round again"
   :conflicts "rows where the outside authority and this house disagree"
   :insights "findings an agent published that nobody has answered"
   :proposals "exact changes to this feed's own order, staged and waiting on a tap"
   :cleared "a queue that went all the way to zero"
   :streaks "the weeks in a row this house finished something"
   :finished "the last thing each front door finished this week"
   :events "every row that has moved lately, newest move first"
   :memories "what this house was doing a year ago this week, and behind it everything that has moved"})

(def population-reads
  "Which DECLARED traits a population consults, so a card's citation
  can quote the kind's own declaration rather than describe it from
  outside. A population reading none of them is not a gap: `asks`,
  `letters`, `ticklers`, `conflicts`, `insights`, `proposals` and
  `outcomes` all choose by a STATE their own kind declares and by
  whose row it is, which the line's own sentence already says and the
  card's own `state` already shows. `proposals` adds a clock read on
  top of that and still declares nothing here, for the same reason:
  `expires_at` is a field of the row, not a trait of the kind, and the
  card carries it. `outcomes` reads a clock, another row's state and
  a count of its own children, and declares nothing here for exactly
  that reason — none of the three is a TRAIT, and a citation that
  quoted `:nav :system` at the crown would be quoting the one trait
  that says the opposite of what the section is for."
  {:next_actions [:nav :machine :over]
   :cleared [:nav :over]
   :streaks [:nav :over]
   :finished [:nav :over]
   :memories [:over]
   :events [:nav]})

(defn- ordinal ^String [^long n]
  (str n (if (<= 11 (mod n 100) 13)
           "th"
           (get {1 "st" 2 "nd" 3 "rd"} (mod n 10) "th"))))

(defn- words
  "A set of state keywords or field strings, as one readable list."
  ^String [xs]
  (str/join ", " (map #(str (if (keyword? %) (name %) %)) (sort-by str xs))))

(defn- nav-says
  "`:nav`, in its own words. It is the only trait this framework
  declares about who a kind is FOR, and it is the whole of do-now's
  and fuel's answer to which rows belong to a person."
  [rdef]
  (let [k (name (:kind rdef))]
    (case (:nav rdef :primary)
      :primary (str k " is a front door in this house — its declaration says"
                    " :nav :primary — and do-now and fuel are made of front"
                    " doors.")
      :secondary (str k " is :nav :secondary — a line inside somebody else's"
                      " row. The archive remembers those; fuel does not"
                      " celebrate them.")
      :system (str k " is :nav :system — house machinery rather than anybody's"
                   " next step.")
      nil)))

(defn- machine-says
  "The kind's own state machine, read against this row. `open?` is the
  predicate; this is the sentence it would have said."
  [rdef row]
  (let [terminal (:terminal rdef #{})
        k (name (:kind rdef))
        st (some-> (:state row) name)]
    (cond
      (and (empty? terminal) (:mirror rdef))
      (str k " is mirrored, so its machine is the SYNC machine — "
           (or st "its state") " is a word about the last time this house and"
           " the authority spoke, never about the work. What ended lives in"
           " its data, which is what :over reads.")

      (empty? terminal)
      (str k "'s machine declares no ending at all: every state it can reach"
           " has a way back out, which is exactly why :over is the thing that"
           " says when the work is over.")

      (contains? terminal (keyword st))
      (str "State " st " is an ending by " k "'s own machine.")

      :else
      (str "State " st " is live work by " k "'s own machine — its endings are "
           (words terminal) ", and this row is in none of them."))))

(defn- over-says
  "`:over`, in the declaration's own words, read against this row. This
  is the sentence the movie in do-now was missing: a kind whose
  lifecycle lives in a data field says so, names the word it finishes
  on and the word it lets go on, and the row's own word is quoted back."
  [rdef row]
  (let [{:keys [field accomplished let-go]} (over-vocabulary rdef)
        k (name (:kind rdef))
        w (ending-word rdef row)
        w' (when w (if (keyword? w) (name w) (str w)))]
    (cond
      (not (:over rdef))
      (str k " spells no :over, so the machine's endings are the endings and"
           " every one of them counts as finished.")

      field
      (str k "'s :over reads its " (name field) " field: " (words accomplished)
           " is a deed"
           (when (seq let-go) (str ", " (words let-go) " is let go"))
           ". This row's " (name field) " says "
           (if w' w' "nothing at all") ", which is "
           (cond (contains? accomplished w) "an ending it stands behind."
                 (contains? let-go w) "one it let go."
                 :else "neither, so its work is not over."))

      :else
      (str k "'s :over says " (words accomplished) " is a deed"
           (when (seq let-go) (str " and " (words let-go) " is let go"))
           ". This row is " (or w' "nowhere named") ", which is "
           (cond (contains? accomplished w) "an ending it stands behind."
                 (contains? let-go w) "one it let go."
                 :else "neither, so its work is not over.")))))

(defn- trait-says
  "The declared traits this population read, each in the declaration's
  own words, against this row."
  [rdef row traits]
  (if-not (and rdef row)
    ;; a row that vanished between the scan and this read has no
    ;; declaration to quote, and a citation that guessed would be
    ;; worse than one that is short
    []
    (into []
          (comp (map (fn [t]
                       (case t
                         :nav (nav-says rdef)
                         :machine (machine-says rdef row)
                         :over (over-says rdef row)
                         nil)))
                (remove nil?))
          traits)))

(defn line-says
  "One recipe line, narrated. A household may write the whole sentence
  itself (`:says` on the entry — the recipe is an engine opt and its
  entries are data, so this costs no declaration a fingerprint), and
  otherwise the line narrates itself from what it already carries: the
  section, the take, the kinds it dedicates itself to, and the
  population's own sentence."
  ^String [entry]
  (or (:says entry)
      (if (:seam entry)
        (str "The seam: " (:sentence entry "That's the house, caught up.")
             " Exactly one card in the answer says that, and everything below"
             " it is history.")
        (let [n (long (:take entry 0))
              sect (str/replace (name (:section entry)) "_" " ")]
          (str (str/upper-case (subs sect 0 1)) (subs sect 1)
               ", up to " n " card" (when (not= 1 n) "s") ": "
               (population-says (:population entry)
                                (str "the " (name (:population entry))
                                     " population"))
               (when-some [ks (seq (:kinds entry))]
                 (str " — and this line is " (str/join " and " (map name ks))
                      "'s alone"))
               ".")))))

;; ── two orders, read side by side (waymark-0k4) ─────────────────────
;;
;; A staged proposal is an EXACT revision somebody will apply with one
;; tap, so the one thing it owes the person tapping is an honest
;; account of what changes. That account is computed HERE, from the
;; two orders and nothing else — pure, so the sentence a person reads
;; under their thumb is a function of the two things being compared
;; and never of who is asking or when.
;;
;; It is POSITIONAL, and that is not a shortcut: the vector's order IS
;; the feed's order (`default-recipe`'s own first sentence), so line 2
;; is a place on the page and not an identity. Inserting a line near
;; the top therefore reads as several lines moving, which is exactly
;; what a reader would see happen.

(defn- section-words ^String [e]
  (str/replace (name (:section e)) "_" " "))

(defn- takes-words ^String [n]
  (str n " card" (when (not= 1 (long n)) "s")))

(defn- population-words ^String [p]
  (population-says p (str "the " (name p) " population")))

(defn- line-changes
  "What changed between two lines standing in the same place, key by
  key and each in the household's own words. Empty when the two lines
  say the same thing."
  [was now]
  (cond-> []
    (not= (boolean (:seam was)) (boolean (:seam now)))
    (conj (if (:seam now)
            "becomes the caught-up line"
            (str "stops being the caught-up line and becomes "
                 (section-words now))))

    (and (not (:seam now)) (not= (:section was) (:section now)))
    (conj (str "moves to " (section-words now)))

    (and (not (:seam now)) (not= (:population was) (:population now)))
    (conj (str "reads " (population-words (:population now))
               " instead of " (population-words (:population was))))

    (and (not (:seam now)) (not= (:take was) (:take now)))
    (conj (str "shows " (takes-words (:take now 0))
               " instead of " (takes-words (:take was 0))))

    (not= (vec (:kinds was)) (vec (:kinds now)))
    (conj (if (seq (:kinds now))
            (str "is " (str/join " and " (map name (:kinds now))) "'s alone")
            "stops being any one kind's alone"))

    (not= (boolean (:bottomless was)) (boolean (:bottomless now)))
    (conj (if (:bottomless now)
            "never ends — it pages forever"
            "stops paging forever"))

    (not= (:sentence was) (:sentence now))
    (conj (str "reads " (pr-str (str (:sentence now)))))

    (not= (:says was) (:says now))
    (conj (if (:says now)
            (str "says of itself " (pr-str (str (:says now))))
            "drops its own sentence and narrates itself again"))))

(def order-unchanged
  "The sentence an order that moved nothing says. Spelled once because
  `recipe-diff` reads it back to decide whether the ORDER half of a
  staged change said anything at all — an empty list under a verdict
  button is the one thing a person cannot read, and two different
  spellings of *nothing changed* would be two."
  "Nothing changes — this is the order already in force, line for line.")

(defn order-diff
  "Two orders — the one in force and the one proposed — read side by
  side, as the sentences a person reads before they tap. Both are in
  the RECIPE MAP shape `check-recipe!` and `line-says` speak (keyword
  sections, keyword populations), never the editor's wire spelling;
  `waymark10.feed-recipe/recipe-of` is the one converter.

  A line that is unchanged says nothing at all — a diff that recited
  every line would bury the one that moved. A line with no counterpart
  arrives or goes whole, narrated by `line-says` so the reader meets
  it in the same words the narrated recipe uses. And an order that
  changes nothing says SO, out loud, rather than answering with
  silence: an empty list under a verdict button is the one thing a
  person cannot read."
  [was now]
  (let [was (vec was)
        now (vec now)
        n (max (count was) (count now))
        moves (into []
                    (keep
                     (fn [i]
                       (let [a (nth was i nil)
                             b (nth now i nil)]
                         (cond
                           (= a b) nil
                           (nil? a) (str "Line " (inc i) " is new: "
                                         (line-says b))
                           (nil? b) (str "Line " (inc i) " goes — it used"
                                         " to be: " (line-says a))
                           :else
                           (let [cs (line-changes a b)]
                             (if (seq cs)
                               (str "Line " (inc i)
                                    (when (and (:seam a) (:seam b))
                                      ", the caught-up line,")
                                    " " (str/join "; " cs) ".")
                               (str "Line " (inc i) " changes: "
                                    (line-says b))))))))
                    (range n))]
    (cond
      (empty? moves) [order-unchanged]

      (not= (count was) (count now))
      (into [(str "The order goes from " (count was) " line"
                  (when (not= 1 (count was)) "s") " to " (count now) ".")]
            moves)

      :else moves)))

(defn formula-diff
  "The contest's two numbers, read side by side (waymark-8um.3). Both
  arguments are recipe-map formulas — nil for *whatever the deployment
  says*, which is what `formula-of` fills in, so the comparison is
  between the numbers a household would actually READ rather than
  between one number and an absence.

  Empty when nothing moved, so `recipe-diff` can tell an order-only
  change from a contest-only one and say so."
  [was now]
  (let [a (formula-of {:formula was})
        b (formula-of {:formula now})
        wa (long (:window-days a)) wb (long (:window-days b))
        ca (long (:cools-after a)) cb (long (:cools-after b))]
    (cond-> []
      (and (pos? ca) (zero? cb))
      (conj (str "The contest turns OFF: nothing below the crown is weighted"
                 " by what anybody has already been shown, and the seed alone"
                 " decides the order."))

      (and (zero? ca) (pos? cb))
      (conj (str "The contest turns ON: a card that has been on your feed "
                 cb " days inside the last " wb " with nothing done cools a"
                 " step and sits behind the fresher cards in its own line."))

      (and (pos? ca) (pos? cb) (not= ca cb))
      (conj (str "A card cools a step after " cb " day"
                 (when (not= 1 cb) "s") " untouched instead of " ca "."))

      (and (pos? cb) (not= wa wb))
      (conj (str "The contest counts the last " wb " days of your own looking"
                 " instead of " wa ".")))))

(def crown-rank-words
  "The household's own sentence for a change to each of the crown's
  numbers, keyed the way `default-crown-rank` is: a function of the
  number before and the number after. Spelled as a MAP and read
  generically by `crown-rank-diff` rather than as four lines inside
  it, for one reason — the rank is about to grow (waymark-1uv.10's
  cooling input, waymark-1uv.6's judgment), and a diff that named its
  four keys would render a fifth number's change as silence, which is
  the one thing a person tapping under a diff cannot read. A key with
  no words here still says which number moved, by its wire name.

  `:declined` says the arithmetic out loud — *a never-this line of
  thinking is held 12 instead of 8* — because the number a household
  writes is per RANK of the word and the number it feels is the
  strongest word's, and a proposal from a machine that changes it
  should be readable without doing the multiplication in your head."
  {:declared
   (fn [a b]
     (str "In the crown, serving a value this house declared lifts a bundle "
          b " instead of " a "."))
   :cooled
   (fn [a b]
     (str "In the crown, each step a bundle has cooled holds it "
          b " instead of " a "."))
   :declined
   (fn [a b]
     (str "In the crown, each rank of the house's quick word about a line of"
          " thinking holds a bundle " b " instead of " a
          " — a never-this line of thinking is held "
          (* 4 (long b)) " instead of " (* 4 (long a)) "."))
   :fresh
   (fn [a b]
     (str "In the crown, each day left on a bundle's week lifts it "
          b " instead of " a "."))
   ;; the fifth number (waymark-1uv.10): a recomposition arriving before
   ;; the day the house said it would hear that line of thinking again
   :early
   (fn [a b]
     (str "In the crown, each day early a recomposition arrives — before"
          " the day the house said it would hear that line of thinking"
          " again — holds it " b " instead of " a "."))
   ;; the sixth number (waymark-1uv.6): how far an agent's own score of
   ;; a bundle may move it, either way
   :judged
   (fn [a b]
     (str "In the crown, an agent's own score of a bundle — 0 to 1, with"
          " one sentence, quoted on the card as the agent's — moves it up"
          " to " b " either way instead of " a "."))})

(defn crown-rank-diff
  "The crown's numbers, read side by side (waymark-1uv.2) —
  `formula-diff`'s shape one field over, and empty when nothing moved.
  Both arguments are recipe-map crown ranks, nil for *whatever the
  deployment says*.

  It walks the KEYS of the two maps — the deployment's first, in
  `default-crown-rank`'s own order, then anything either side names
  beyond those — and says each moved number in `crown-rank-words`'
  sentence for it, so the rank may grow a number without this
  function learning its name (waymark-1uv.5's one rule for the diff)."
  [was now]
  (let [a (crown-rank-of {:crown-rank was})
        b (crown-rank-of {:crown-rank now})
        ks (distinct (concat (keys default-crown-rank)
                             (sort (keys a)) (sort (keys b))))
        num (fn ^long [m k] (long (or (get m k) 0)))
        off? (fn [m] (every? #(zero? (num m %)) ks))]
    (cond
      (and (off? b) (not (off? a)))
      [(str "The crown's rank turns OFF: every one of its " (count ks)
            " numbers is 0, so a bundle answering your own request still"
            " stands first and the seed alone places the rest.")]

      :else
      (into []
            (keep (fn [k]
                    (let [x (num a k) y (num b k)]
                      (when (not= x y)
                        (if-some [say (get crown-rank-words k)]
                          (say x y)
                          (str "In the crown, crown_rank " (name k) " is "
                               y " instead of " x "."))))))
            ks))))

;; ── the findings' numbers, in the household's words (waymark-1uv.8) ─
;;
;; A SIBLING map and a sibling diff rather than a widening of the
;; crown's, on purpose: the two ranks are two fields on the row with
;; two sentences each, and a diff that walked one map for both would
;; have to be told which field a key came from. The shape is
;; identical, so a reader of one can read the other.

(def insight-rank-words
  "The household's own sentence for a change to each of the findings'
  numbers, keyed the way `default-insight-rank` is — `crown-rank-words`'
  shape one field over, read generically by `insight-rank-diff` so the
  rank may grow a number without the diff learning its name. A key
  with no words here still says which number moved, by its wire name.

  `:declined` says the arithmetic out loud, for the crown's reason: the
  number a household writes is per RANK of the word and the number it
  feels is the strongest word's."
  {:diagnosis
   (fn [a b]
     (str "Among findings, one that is a diagnosis — offering a value's or"
          " a person's own affirmation, a step on an outcome, or built on"
          " an outcome the house declined — is lifted " b " instead of "
          a "."))
   :declared
   (fn [a b]
     (str "Among findings, one whose next step serves a value this house"
          " declared is lifted " b " instead of " a "."))
   :cooled
   (fn [a b]
     (str "Among findings, each step one has cooled holds it " b
          " instead of " a "."))
   :dismissed
   (fn [a b]
     (str "Among findings, each finding the house already dismissed on the"
          " same next step holds a new one " b " instead of " a "."))
   :declined
   (fn [a b]
     (str "Among findings, each rank of the house's quick word on those"
          " dismissals holds it " b " instead of " a
          " — a next step the house said never this about is held "
          (* 4 (long b)) " instead of " (* 4 (long a)) "."))
   :fresh
   (fn [a b]
     (str "Among findings, each day of freshness left in the window lifts"
          " one " b " instead of " a "."))})

(defn insight-rank-diff
  "The findings' numbers, read side by side — `crown-rank-diff`'s shape
  one field over, and empty when nothing moved. Both arguments are
  recipe-map insight ranks, nil for *whatever the deployment says*. It
  walks the KEYS of the two maps — the deployment's first, then
  anything either side names beyond those — and says each moved number
  in `insight-rank-words`' sentence for it."
  [was now]
  (let [a (insight-rank-of {:insight-rank was})
        b (insight-rank-of {:insight-rank now})
        ks (distinct (concat (keys default-insight-rank)
                             (sort (keys a)) (sort (keys b))))
        num (fn ^long [m k] (long (or (get m k) 0)))
        off? (fn [m] (every? #(zero? (num m %)) ks))]
    (cond
      (and (off? b) (not (off? a)))
      [(str "The findings' rank turns OFF: every one of its " (count ks)
            " numbers is 0, so the seed alone places the findings in their"
            " line.")]

      :else
      (into []
            (keep (fn [k]
                    (let [x (num a k) y (num b k)]
                      (when (not= x y)
                        (if-some [say (get insight-rank-words k)]
                          (say x y)
                          (str "Among findings, insight_rank " (name k) " is "
                               y " instead of " x "."))))))
            ks))))

;; ── the ticklers line's rank, narrated (waymark-1uv.9) ──────────────
;; The crown's four sentences, one line down: the words a diff says
;; for each moved number, the numbers as the editor takes them, the
;; recipe view's narration, and what the rank did to THIS card.

(def tickler-rank-words
  "The household's own sentence for a change to each of the ticklers
  line's numbers, keyed the way `default-tickler-rank` is: a function
  of the number before and the number after. A NEW map rather than
  entries in `crown-rank-words`, because the two ranks are two fields
  on the recipe row and a diff that read one map for both would say
  *in the crown* about the fridge. Read generically by
  `tickler-rank-diff`, `crown-rank-diff`'s rule: a key with no words
  here still says which number moved, by its wire name."
  {:overdue
   (fn [a b]
     (str "On the fridge, each day a set-aside item stands past its own"
          " date lifts it " b " instead of " a "."))
   :not_now
   (fn [a b]
     (str "On the fridge, each time the house has already said not now"
          " holds an item " b " instead of " a "."))
   :cooled
   (fn [a b]
     (str "On the fridge, each step an item has cooled holds it "
          b " instead of " a "."))
   :front_door
   (fn [a b]
     (str "On the fridge, an item whose row is a kind this house goes to"
          " is lifted " b " instead of " a "."))
   :age
   (fn [a b]
     (str "On the fridge, each month an item's row has sat on the dropped"
          " pile lifts it " b " instead of " a "."))})

(defn tickler-rank-diff
  "The ticklers line's numbers, read side by side — `crown-rank-diff`'s
  shape one field over, and empty when nothing moved. Both arguments
  are recipe-map tickler ranks, nil for *whatever the deployment
  says*. It walks the KEYS of the two maps, the deployment's first in
  `default-tickler-rank`'s own order, so the rank may grow a number
  without this function learning its name."
  [was now]
  (let [a (tickler-rank-of {:tickler-rank was})
        b (tickler-rank-of {:tickler-rank now})
        ks (distinct (concat (keys default-tickler-rank)
                             (sort (keys a)) (sort (keys b))))
        num (fn ^long [m k] (long (or (get m k) 0)))
        off? (fn [m] (every? #(zero? (num m %)) ks))]
    (cond
      (and (off? b) (not (off? a)))
      [(str "The fridge's rank turns OFF: every one of its " (count ks)
            " numbers is 0, so an item you set aside by your own hand"
            " still stands first and the seed alone places the rest.")]

      :else
      (into []
            (keep (fn [k]
                    (let [x (num a k) y (num b k)]
                      (when (not= x y)
                        (if-some [say (get tickler-rank-words k)]
                          (say x y)
                          (str "On the fridge, tickler_rank " (name k) " is "
                               y " instead of " x "."))))))
            ks))))

(defn tickler-rank-as-written
  "The ticklers line's five numbers in the shape the EDITOR takes — the
  wire spelling of `waymark10.feed-recipe`'s `tickler_rank` field, so
  what a person copies out of a feed document is what the form takes
  back (`crown-rank-as-written`'s sentence, one field over)."
  [recipe]
  (let [c (tickler-rank-of recipe)]
    {"overdue" (:overdue c)
     "not_now" (:not_now c)
     "cooled" (:cooled c)
     "front_door" (:front_door c)
     "age" (:age c)}))

(defn tickler-rank-says
  "The ticklers line's rank, narrated in household words with its own
  numbers quoted back — the recipe view's half of law 5 at the fridge
  (waymark-1uv.9). A pure function of the recipe, like
  `crown-rank-says`: what the rank did to THIS card is the card's
  business (`tickler-card-says`), because only a read knows it."
  ^String [recipe]
  (let [{:keys [overdue not_now cooled front_door age]} (tickler-rank-of recipe)
        {:keys [window-days cools-after]} (formula-of recipe)]
    (if (every? zero? (map long [overdue not_now cooled front_door age]))
      (str "The fridge's rank is off: every number in tickler_rank is 0,"
           " so an item you set aside by your own hand still stands first"
           " and the seed alone places the rest. Turning it back on is a"
           " number in this same form.")
      (str "The things you set aside are ranked when their date comes"
           " round, and this is the whole of it. An item a person set aside"
           " by their own hand stands above every one the house's sweep"
           " set aside for you, and no number here changes that. Among the"
           " rest, five numbers a person can read: each day an item stands"
           " past its own date lifts it " overdue "; each time the house"
           " has already said not now holds it " not_now "; each step the"
           " contest says it has cooled — "
           (if (pos? (long cools-after))
             (str "the same " cools-after " day"
                  (when (not= 1 (long cools-after)) "s") " in " window-days
                  " as the sections below, read off your own record —")
             "nothing, while the contest is off at cools_after 0 —")
           " holds it " cooled "; an item whose row is a kind this house"
           " goes to is lifted " front_door "; and each month its row has"
           " sat on the dropped pile lifts it " age ", so the things the"
           " house forgot longest come back first among equals. The floor"
           " still holds — the line shows as many items as its take says"
           " whenever that many are due; the rank only chooses which, and"
           " the seed decides between equals. Nothing here is a cap: the"
           " sweep sets aside every dropped row it finds, and a not-now"
           " pushes an item's date out rather than counting against a"
           " limit. Until you turn the record of what you were shown on,"
           " nothing about seeing moves anything here."))))

(defn tickler-as-cited
  "The ticklers line's numbers as they ride a card's always-on
  `why.tickler` (waymark-1uv.9): the lift, and every input that went
  into it, in the wire's spelling. `seen`/`cooled` ride only when the
  reader is recording, the contest's own posture one key over;
  `next_offer_at` rides only when the marker carries a date, so a
  reader can tell *due today* from *overdue by nothing*. Public
  because the packs assert the shape."
  [weights {:keys [own overdue not-now seen cooled front-door age
                   next-offer-at] :as inputs}]
  (cond-> {"lift" (tickler-lift weights inputs)
           "own" (boolean own)
           "overdue" (long (or overdue 0))
           "not_now" (long (or not-now 0))
           "front_door" (boolean front-door)
           "age" (long (or age 0))}
    (some? seen) (assoc "seen" seen "cooled" cooled)
    next-offer-at (assoc "next_offer_at" (str next-offer-at))))

(defn- tickler-card-says
  "What the ticklers line's rank did to THIS card, in the household's
  own words and with the recipe's own numbers quoted back — law 5's
  *a card's why says what lifted or held it*, at the fridge. Every
  clause names an input and the number it contributed, and the last
  clause is the floor, because the floor is still true."
  ^String [{:keys [rank of tickler tickler-weights formula]}]
  (let [w (fn ^long [k] (long (get tickler-weights k 0)))
        {:keys [own overdue not-now seen cooled front-door age
                next-offer-at subject-kind]} tickler
        lift (tickler-lift tickler-weights tickler)
        window (:window-days formula)
        after (long (or (:cools-after formula) 0))
        plural (fn [n word] (str n " " word (when (not= 1 (long n)) "s")))]
    (str "Ranked " (ordinal rank) " of " of " on the fridge by"
         " recipe.tickler_rank — five numbers this house can read — and"
         " this is the arithmetic for this card. "
         (if own
           (str "A person set this aside by their own hand, so it stands"
                " above everything the house's sweep set aside; no number"
                " here moves it below one. ")
           (str "The house's sweep set this aside, not a person, so anything"
                " a person set aside by hand stands above it. "))
         (cond
           (nil? next-offer-at)
           (str "It carries no date — unset means now — so nothing lifts it"
                " for standing past one. ")
           (zero? (long (or overdue 0)))
           (str "Its date came round today, so nothing lifts it for standing"
                " past one yet. ")
           :else
           (str "It has stood " (plural overdue "day") " past its own date,"
                " lifting it " (* (w :overdue) (long overdue)) ". "))
         (if (pos? (long (or not-now 0)))
           (str "The house has said not now to it " (plural not-now "time")
                ", holding it " (* (w :not_now) (long not-now)) ". ")
           (str "Nobody has said not now to it yet, so nothing holds it"
                " for that. "))
         (cond
           (nil? seen)
           (str "Nothing about what you have been shown moves it, because"
                " you are not recording what you were shown. ")
           (zero? (long seen))
           (str "You have not been shown it in the last " window " days, so"
                " nothing holds it there. ")
           (zero? after)
           (str "Shown " (plural seen "day") " in the last " window
                ", and nothing cools while the contest is off (cools_after"
                " 0). ")
           (zero? (long cooled))
           (str "Shown " (plural seen "day") " in the last " window
                " with nothing done, which is not yet a step: it cools one"
                " after " after ". ")
           :else
           (str "Shown " (plural seen "day") " in the last " window
                " with nothing done — " (plural cooled "step")
                " cooled, holding it " (* (long cooled) (w :cooled)) ". "))
         (if front-door
           (str "Its row is a " (or subject-kind "row") ", a kind this house"
                " goes to, lifting it " (w :front_door) ". ")
           (str "Its row is a " (or subject-kind "row") ", a line inside"
                " somebody else's row rather than a front door, so the "
                (w :front_door) " a front door would lift it is not there. "))
         (if (pos? (long (or age 0)))
           (str "That row has sat on the dropped pile " (plural age "month")
                ", lifting it " (* (w :age) (long age)) ". ")
           (str "That row was let go less than a month ago, so nothing"
                " lifts it for age yet. "))
         "Lift " lift " in all; the seed decides between equals. The floor"
         " still holds: this line shows as many items as its take says"
         " whenever that many are due, and the rank only chooses which.")))

(defn recipe-diff
  "The whole of a staged change, in the household's own words: what
  moves in the ORDER, what moves in the CONTEST, and what moves in the
  CROWN'S RANK (waymark-1uv.2). All three halves are positional-and-
  pure like everything else here, and an order that moved nothing says
  so rather than vanishing — but only when neither formula moved
  either, because *nothing changes* beside a sentence saying what
  changes would be the diff arguing with itself.

  Both arguments are recipe maps, `{:order … :formula … :crown-rank …
  :insight-rank …}` — the findings' numbers joined with waymark-1uv.8,
  narrated the same way."
  [was now]
  (let [moves (order-diff (:order was) (:order now))
        moved? (not= moves [order-unchanged])
        f (-> (formula-diff (:formula was) (:formula now))
              (into (crown-rank-diff (:crown-rank was) (:crown-rank now)))
              (into (insight-rank-diff (:insight-rank was) (:insight-rank now)))
              ;; …and the fridge's (waymark-1uv.9)
              (into (tickler-rank-diff (:tickler-rank was) (:tickler-rank now))))]
    (cond
      (and (not moved?) (empty? f)) [order-unchanged]
      (not moved?) (into ["The order itself is unchanged, line for line."] f)
      :else (into moves f))))

(def recipe-guarantees
  "The four assembly checks, as the one sentence they buy a reader.
  `check-recipe!` runs them at the route's build site, so a recipe
  that broke any of them refused the boot rather than this request —
  which is why this can be said in the present tense.

  The order is READ OFF `census` rather than spelled again here: a
  sentence that named the sections in a second place would be the
  place that drifts the first time one is added, and waymark-jfv.4
  added one."
  (str "The sections always come in this order — "
       (str/join ", " (map #(str/replace (name %) "_" " ") census))
       "; exactly one card is the seam; the archive"
       " is last and bottomless; every line names a population this"
       " engine actually holds; the contest is two numbers a person can"
       " read; the crown's rank is six, the findings' rank is six, and the"
       " fridge's is five, for the things set aside. A recipe that broke"
       " any of those would have refused to start rather than serve you a"
       " surprise."))

(defn formula-as-written
  "The contest's two numbers in the shape the EDITOR takes — the wire
  spelling of `waymark10.feed-recipe`'s `formula` field, so what a
  person copies out of a feed document is what the form takes back.
  `recipe.order`'s own sentence, one field over."
  [recipe]
  (let [f (formula-of recipe)]
    {"window_days" (:window-days f)
     "cools_after" (:cools-after f)}))

(defn formula-says
  "The contest, narrated in household words with its own numbers quoted
  back — the recipe view's half of law 5. A pure function of the
  recipe, like every other line of `recipe-view`: what THIS reader's
  own looking did to THIS read is a card's business (`cooling-says`)
  and a note's, because only a read knows it."
  ^String [recipe]
  (let [{:keys [window-days cools-after]} (formula-of recipe)]
    (if-not (pos? (long cools-after))
      (str "The contest is off: this order says cools_after 0, so nothing"
           " below is weighted by what anybody has already been shown and"
           " the seed alone decides. Turning it back on is a number in this"
           " same form.")
      (str "Below the crown and outside everything waiting on an answer, the"
           " order is weighted by what you have already seen — and this is"
           " the whole of it. A card that has been on your feed "
           cools-after " day" (when (not= 1 (long cools-after)) "s")
           " inside the last " window-days
           " without being acted on cools one step and sits behind the"
           " fresher cards IN ITS OWN LINE; " (* 2 (long cools-after))
           " days is two steps, and the window lets a card go cold again"
           " on its own. A card you have never been shown is fresh, and"
           " ranks as unseen rather than as unloved. Cooling never removes"
           " a card and never empties a line — the seed still decides"
           " inside a step, and every line still shows as many cards as it"
           " says. It reads your own rows and nobody else's, and it does"
           " nothing at all until you turn the record of what you were"
           " shown on."))))

(defn crown-rank-as-written
  "The crown's six numbers in the shape the EDITOR takes — the wire
  spelling of `waymark10.feed-recipe`'s `crown_rank` field, so what a
  person copies out of a feed document is what the form takes back
  (`formula-as-written`'s sentence, one field over)."
  [recipe]
  (let [c (crown-rank-of recipe)]
    {"declared" (:declared c)
     "cooled" (:cooled c)
     "declined" (:declined c)
     "fresh" (:fresh c)
     "early" (:early c)
     "judged" (:judged c)}))

(defn crown-rank-says
  "The crown's rank, narrated in household words with its own numbers
  quoted back — the recipe view's half of law 5 at the crown
  (waymark-1uv.2). A pure function of the recipe, like `formula-says`:
  what the rank did to THIS card is the card's business
  (`crown-card-says`), because only a read knows it.

  The agent's number (waymark-1uv.6) is narrated only while it is
  non-zero: a house that turned it off is a house that does not want
  the sentence, and the numbers it can still read are the five."
  ^String [recipe]
  (let [{:keys [declared cooled declined fresh early judged]} (crown-rank-of recipe)
        {:keys [window-days cools-after]} (formula-of recipe)
        judged (long (or judged 0))]
    (if (every? zero? (map long [declared cooled declined fresh early judged]))
      (str "The crown's rank is off: every number in crown_rank is 0, so a"
           " bundle answering your own request still stands first and the"
           " seed alone places the rest. Turning it back on is a number in"
           " this same form.")
      (str "The crown ranks what it shows, and this is the whole of it. A"
           " bundle that answers a request you made stands above every one"
           " nobody asked for, and no number here changes that. Among the"
           " rest, " (if (pos? judged) "six" "five")
           " numbers a person can read: serving a value this"
           " house declared lifts a bundle " declared " over one serving a"
           " value an agent only observed; each step the contest says it has"
           " cooled — "
           (if (pos? (long cools-after))
             (str "the same " cools-after " day"
                  (when (not= 1 (long cools-after)) "s") " in " window-days
                  " as the sections below, read off your own record —")
             "nothing, while the contest is off at cools_after 0 —")
           " holds it " cooled "; the strongest quick word the house said"
           " about the line of thinking it recomposes holds it "
           (* 1 (long declined)) " for wrong time, " (* 2 (long declined))
           " for wrong piece, " (* 3 (long declined)) " for not this way and "
           (* 4 (long declined)) " for never this; each day left on its"
           " week lifts it " fresh ", so a bundle nearer its lapse ranks"
           " lower; and each day a recomposition arrives before the day the"
           " house said it would hear that line of thinking again holds it "
           early ", so a bundle the house said not to hear yet sits last"
           " rather than out of sight"
           (when (pos? judged)
             (str "; and an agent that read a bundle may score it, 0 to 1,"
                  " with one sentence the card quotes as the agent's — a"
                  " score of 1 lifts it " judged ", 0 holds it " judged
                  ", and a bundle nobody scored reads as a half, which is"
                  " silence"))
           ". The floor still holds — the crown shows as many bundles"
           " as its take says whenever that many exist; the rank only"
           " chooses which, and the seed decides between equals. Until you"
           " turn the record of what you were shown on, nothing about"
           " seeing moves anything here."))))

(defn insight-rank-as-written
  "The findings' six numbers in the shape the EDITOR takes — the wire
  spelling of `waymark10.feed-recipe`'s `insight_rank` field
  (`crown-rank-as-written`'s sentence, one field over)."
  [recipe]
  (let [c (insight-rank-of recipe)]
    {"diagnosis" (:diagnosis c)
     "declared" (:declared c)
     "cooled" (:cooled c)
     "dismissed" (:dismissed c)
     "declined" (:declined c)
     "fresh" (:fresh c)}))

(defn insight-rank-says
  "The findings' rank, narrated in household words with its own
  numbers quoted back — the recipe view's half of law 5 at the
  insights line (waymark-1uv.8). A pure function of the recipe, like
  `crown-rank-says`: what the rank did to THIS card is the card's
  business (`insight-card-says`), because only a read knows it."
  ^String [recipe]
  (let [{:keys [diagnosis declared cooled dismissed declined fresh]}
        (insight-rank-of recipe)
        {:keys [window-days cools-after]} (formula-of recipe)]
    (if (every? zero? (map long [diagnosis declared cooled dismissed declined fresh]))
      (str "The findings' rank is off: every number in insight_rank is 0, so"
           " the seed alone places the findings in their line. Turning it"
           " back on is a number in this same form.")
      (str "Findings are ranked, not capped: an agent may publish as many as"
           " it finds, the line shows as many as its take says, and this is"
           " the whole of how it chooses which. A finding is the one card"
           " waiting on your answer that is not an obligation — it is the"
           " contest's own output — so unlike the ask, the conflict, the"
           " letter and the staged change beside it, it is ranked, by six"
           " numbers a person can read: a diagnosis — a finding offering a"
           " value's or a person's own affirmation, a step on an outcome,"
           " or built on an outcome the house declined — is lifted "
           diagnosis " over a plain finding; one whose next step serves a"
           " value this house declared is lifted " declared "; each step"
           " the contest says it has cooled — "
           (if (pos? (long cools-after))
             (str "the same " cools-after " day"
                  (when (not= 1 (long cools-after)) "s") " in " window-days
                  " as the sections below, read off your own record —")
             "nothing, while the contest is off at cools_after 0 —")
           " holds it " cooled "; each finding you already dismissed on the"
           " same next step holds a new one " dismissed ", and the strongest"
           " quick word you said on those holds it " (* 1 (long declined))
           " for too thin, " (* 2 (long declined)) " for not backed, "
           (* 3 (long declined)) " for already known and " (* 4 (long declined))
           " for not true; and each day of freshness left in the same "
           window-days " lifts it " fresh ", so a finding published today"
           " stands above one from last week and an old one sinks to the"
           " bottom and no further. The floor still holds — the line shows"
           " as many findings as its take says whenever that many exist;"
           " the rank only chooses which, and the seed decides between"
           " equals. Until you turn the record of what you were shown on,"
           " nothing about seeing moves anything here."))))

(defn order-as-written
  "The recipe's order in the shape the EDITOR takes — the wire spelling
  of `waymark10.feed-recipe`'s `order` field, which is `line-of`'s
  inverse (waymark-4yn).

  It rides the document beside the narrated `lines` because the two
  answer different questions and neither answers the other's. `lines`
  is prose plus this read's own counts, for a person asking why a card
  is here; this is DATA a person copies into the create form when they
  want to start from what the house already reads. That copy is the
  whole of `create-from-current`: no new door, no prefill machinery,
  and the starting point is the order actually in force rather than
  whatever the framework happened to ship."
  [recipe]
  (into []
        (map (fn [e]
               (if (:seam e)
                 (cond-> {"section" "seam"}
                   (:sentence e) (assoc "sentence" (:sentence e))
                   (:says e) (assoc "says" (:says e)))
                 (cond-> {"section" (name (:section e))}
                   (:population e) (assoc "population" (name (:population e)))
                   (:take e) (assoc "take" (:take e))
                   (seq (:kinds e)) (assoc "kinds" (mapv name (:kinds e)))
                   (:says e) (assoc "says" (:says e))
                   (:bottomless e) (assoc "bottomless" true)))))
        (:order recipe)))

(defn recipe-view
  "The household's declared order, narrated — the deliverable half of
  waymark-iqa.29 that is not about any one card.

  It is a pure function of the recipe, so a reader (or a test, or the
  pack) can read the order without a request, and the counts a real
  read knows — how many candidates a line was offered, how many a
  section above had already claimed, how many it showed — are merged
  in by `document`. Since waymark-4yn it is no longer viewing only:
  `order` below is the same order in the editor's own shape, and
  `source` — stamped in by `document`, because only a READ knows which
  recipe answered it — names the row that wrote it."
  [recipe]
  {"guarantees" recipe-guarantees
   "order" (order-as-written recipe)
   ;; the contest, as DATA and as a sentence (waymark-8um.3). Two keys
   ;; because they answer different questions and neither answers the
   ;; other's — `formula` is what a person copies into the form,
   ;; `formula_says` is what they read before they decide to.
   "formula" (formula-as-written recipe)
   "formula_says" (formula-says recipe)
   ;; …and the crown's rank, the same two ways (waymark-1uv.2)
   "crown_rank" (crown-rank-as-written recipe)
   "crown_rank_says" (crown-rank-says recipe)
   ;; …and the findings' rank, the same two ways (waymark-1uv.8)
   "insight_rank" (insight-rank-as-written recipe)
   "insight_rank_says" (insight-rank-says recipe)
   ;; …and the fridge's rank, the same two ways (waymark-1uv.9)
   "tickler_rank" (tickler-rank-as-written recipe)
   "tickler_rank_says" (tickler-rank-says recipe)
   "lines" (into []
                 (map-indexed
                  (fn [i e]
                    (cond-> {"line" i "says" (line-says e)}
                      (:seam e) (assoc "seam" true "section" "seam")
                      (not (:seam e))
                      (assoc "section" (name (:section e))
                             "population" (name (:population e))
                             "take" (:take e))
                      (seq (:kinds e))
                      (assoc "kinds" (mapv name (:kinds e)))
                      ;; which line never ends — the guarantees sentence
                      ;; promises exactly one, and until waymark-8um.3
                      ;; the narrated half was the only place a reader
                      ;; could not tell WHICH. `order` carried it all
                      ;; along; this is the same fact said where the
                      ;; prose is.
                      (:bottomless e) (assoc "bottomless" true))))
                 (:order recipe))})

(defn- drawn-says
  "The seed's own half of the citation, honestly: which of how many,
  and — where a population SPREAD its candidates — whose turn it was.
  Nothing here compares two cards; `rank` is the place
  `hash(seed ‖ card_id)` put this one and `lane` is the place it holds
  in its own kind's order, which is composition and not a score.

  Under a DRAW (waymark-8um.2) the numbers are this draw's and the
  sentence says so — the draw joins the seed, so `rank` and `of` are
  as true here as they are on the daily order, and the only thing that
  changed is which order they are true of."
  [{:keys [rank of lane kind day seeded-for draw]}]
  (str "Drawn " (ordinal rank) " of " of " this line offered today, by ("
       seeded-for ", " day (when draw (str ", draw " draw)) ")'s seed."
       (when (and lane (pos? (long lane)))
         (str " It came up on " (name kind) "'s turn — lane " lane " of its"
              " own kind's order, so the slots go round the kinds rather than"
              " to whichever kind holds the most rows."))
       " Nothing was ranked against anything: the seed decides the order, and"
       (if draw
         " this draw holds until you deal again."
         " it decides once a day.")))

(def ^:private reason-words
  "The quick words as a sentence says them — the labels
  `waymark10.verdict-reason` renders on the chip, lower-cased for the
  middle of a clause, both sets of them. An unknown word reads as its
  token with the underscore taken out, which is what a fifth word the
  house added would look like until somebody wrote its label here."
  {"never_this" "never this"
   "wrong_way" "not this way"
   "wrong_piece" "wrong piece"
   "wrong_time" "wrong time"
   ;; the finding's own four (waymark-hcr)
   "untrue" "not true"
   "restated" "already known"
   "unfounded" "not backed"
   "thin" "too thin"})

(defn- reason-word ^String [token]
  (get reason-words (str token) (str/replace (str token) "_" " ")))

(defn crown-as-cited
  "The crown's numbers as they ride a card's always-on `why.crown`
  (waymark-1uv.2): the lift, and every input that went into it, in the
  wire's spelling. `seen`/`cooled` ride only when the reader is
  recording, the contest's own posture one key over; `declined` rides
  only when a word was said, so a reader can tell *no word* from a
  word; `early`, `turned_down` and `not_before` ride only on a
  RECOMPOSITION (waymark-1uv.10), so a reader can tell *nothing to be
  early for* from *on time*; `judged` rides only when an agent said a
  word (waymark-1uv.6), as `{score, by, says}` — the number the sort
  read, the name the sentence is quoted under, and the sentence,
  which is the agent's and never the engine's. Public because the
  packs assert the shape."
  [weights {:keys [asked value seen cooled declined days-left
                   early turned-down not-before judged] :as inputs}]
  (cond-> {"lift" (crown-lift weights inputs)
           "asked" (boolean asked)
           "value" (name (or value :declared))
           "days_left" (long (or days-left 0))}
    (some? seen) (assoc "seen" seen "cooled" cooled)
    (some? declined) (assoc "declined" (str declined))
    (some? early) (assoc "early" (long early)
                         "turned_down" (long (or turned-down 0)))
    (and (some? early) (pos? (long early)) not-before)
    (assoc "not_before" (str not-before))
    (some? judged) (assoc "judged" {"score" (:score judged)
                                    "by" (:by judged)
                                    "says" (:says judged)})))

(defn insight-as-cited
  "The findings' numbers as they ride an insight card's always-on
  `why.insight` (waymark-1uv.8): the lift, and every input that went
  into it, in the wire's spelling. `seen`/`cooled` ride only when the
  reader is recording, the contest's own posture; `value` rides only
  when the offered row serves a value at all, so a reader can tell *no
  value* from *observed*; `declined` rides only when a word was said.
  `diagnosis` is always there, as `none`, `affirmation` or
  `recomposition`. Public because the packs assert the shape."
  [weights {:keys [diagnosis value seen cooled dismissed declined
                   days-old fresh-days] :as inputs}]
  (cond-> {"lift" (insight-lift weights inputs)
           "diagnosis" (name (or diagnosis :none))
           "dismissed" (long (or dismissed 0))
           "days_old" (long (or days-old 0))
           "fresh_days" (long (or fresh-days 0))}
    (some? value) (assoc "value" (name value))
    (some? seen) (assoc "seen" seen "cooled" cooled)
    (some? declined) (assoc "declined" (str declined))))

(defn- crown-card-says
  "What the crown's rank did to THIS card, in the household's own words
  and with the recipe's own numbers quoted back — law 5's *a card's why
  says what lifted or held it*, at the one section where a person acts
  on a machine's word. Every clause names an input and the number it
  contributed, and the last clause is the floor, because the floor is
  still true — and for a recomposition the house said not to hear yet,
  the floor clause is the one place the person's dated verdict and the
  guaranteed slot meet (waymark-1uv.10): the card stands last, says
  how early it is, and is shown when the take reaches it, because a
  rank that hid it would be the window the epic refused.

  THE AGENT'S CLAUSE IS QUOTED, NOT PARAPHRASED (waymark-1uv.6). When
  an agent scored the bundle the sentence says who, the score, and the
  agent's own words inside quotation marks, then what the house's
  weight made of it — the way the card quotes the composer's routing
  and never lets the engine's impact line blur into it. It is said
  even when the weight is 0, because the word is still the agent's
  and the household may want to read it; only the number it moved is
  nothing then."
  ^String [{:keys [rank of crown crown-weights formula]}]
  (let [w (fn ^long [k] (long (get crown-weights k 0)))
        {:keys [asked value seen cooled declined days-left
                early turned-down not-before judged]} crown
        lift (crown-lift crown-weights crown)
        window (:window-days formula)
        after (long (or (:cools-after formula) 0))
        plural (fn [n word] (str n " " word (when (not= 1 (long n)) "s")))]
    (str "Ranked " (ordinal rank) " of " of " in the crown by recipe.crown_rank"
         " — six numbers this house can read — and this is the arithmetic"
         " for this card. "
         (if asked
           (str "You asked for another and this is the composer's answer, so"
                " it stands above every bundle nobody asked for; no number"
                " here moves it below one. ")
           (str "Nobody asked for this one, so any bundle that answers a"
                " request stands above it. "))
         (if (= :declared value)
           (str "It serves a value this house declared, which lifts it "
                (w :declared) ". ")
           (str "It serves a value an agent observed and nobody has yet"
                " affirmed, so the " (w :declared) " a declared value would"
                " lift it is not there. "))
         (cond
           (nil? seen)
           (str "Nothing about what you have been shown moves it, because"
                " you are not recording what you were shown. ")
           (zero? (long seen))
           (str "You have not been shown it in the last " window " days, so"
                " nothing holds it there. ")
           (zero? after)
           (str "Shown " (plural seen "day") " in the last " window
                ", and nothing cools while the contest is off (cools_after"
                " 0). ")
           (zero? (long cooled))
           (str "Shown " (plural seen "day") " in the last " window
                " with nothing done, which is not yet a step: it cools one"
                " after " after ". ")
           :else
           (str "Shown " (plural seen "day") " in the last " window
                " with nothing done — " (plural cooled "step")
                " cooled, holding it " (* (long cooled) (w :cooled)) ". "))
         (if declined
           (str "The house said " (reason-word declined) " about the line of"
                " thinking it recomposes"
                (when (= 4 (reason-weight declined))
                  " — the heaviest of the four words")
                ", holding it " (* (w :declined) (reason-weight declined))
                ". ")
           (str "Nothing on its record says in words that the house turned"
                " this line of thinking down. "))
         (plural (long (or days-left 0)) "day") " left on its week, lifting it "
         (* (w :fresh) (long (or days-left 0))) ". "
         (cond
           (nil? early) ""
           (pos? (long early))
           (str "The house said not this week about this line of thinking"
                (when (pos? (long (or turned-down 0)))
                  (str " " (plural turned-down "time")))
                " and meant it until " (some-> not-before str (subs 0 10))
                " — this recomposition is " (plural early "day") " early,"
                " holding it " (* (w :early) (long early)) ". ")
           :else
           (str "It recomposes a line of thinking the house turned down"
                (when (pos? (long (or turned-down 0)))
                  (str " " (plural turned-down "time")))
                ", and the day the house said it would hear it again has"
                " passed, so nothing holds it for that. "))
         (when judged
           (let [j (judged-lift (w :judged) (:score judged))]
             (str (:by judged) " scores this " (:score judged) ": “"
                  (str/trim (str (:says judged))) "” — "
                  (cond
                    (pos? j) (str "lifting it " j ". ")
                    (neg? j) (str "holding it " (- j) ". ")
                    (zero? (w :judged))
                    "and this house weighs an agent's judgment 0, so it moves nothing. "
                    :else (str "a nudge that rounds to nothing at a weight of "
                               (w :judged) ". ")))))
         "Lift " lift " in all; the seed decides between equals. The floor"
         " still holds: this section shows as many bundles as its take"
         " says whenever that many exist, and the rank only chooses"
         " which.")))

(defn- insight-card-says
  "What the findings' rank did to THIS card, in the household's own
  words and with the recipe's own numbers quoted back — law 5's *a
  card's why says what lifted or held it*, at the insights line
  (waymark-1uv.8). `crown-card-says`' shape one section down: every
  clause names an input and the number it contributed, and the last
  clause is the floor.

  The first sentence is the one this arm exists to say honestly.
  `cooling-says` tells every other decide card it is *outside the
  contest* because it is an obligation with a deadline (laws v3 law
  2); a finding is not one — it is the contest's own output — so its
  card says that it is ranked, and that the section's OTHER citizens
  are the ones law 2 is about."
  ^String [{:keys [rank of insight insight-weights formula]}]
  (let [w (fn ^long [k] (long (get insight-weights k 0)))
        {:keys [diagnosis value seen cooled dismissed declined
                days-old fresh-days]} insight
        lift (insight-lift insight-weights insight)
        window (:window-days formula)
        after (long (or (:cools-after formula) 0))
        plural (fn [n word] (str n " " word (when (not= 1 (long n)) "s")))]
    (str "Ranked " (ordinal rank) " of " of " among findings by"
         " recipe.insight_rank — six numbers this house can read — and this"
         " is the arithmetic for this card. A finding is not an obligation:"
         " the ask that expires, the conflict, the letter and the staged"
         " change beside it in this section stand outside the contest"
         " because they must appear; a finding is the contest's own output"
         " and is ranked instead. "
         (case diagnosis
           :affirmation
           (str "It is a diagnosis — its next step is a value's or a person's"
                " own affirmation, the composer's duty firing first — which"
                " lifts it " (w :diagnosis) ". ")
           :recomposition
           (str "It is a diagnosis — it offers a step on an outcome, or is"
                " built on one the house declined, the composer's duty firing"
                " first — which lifts it " (w :diagnosis) ". ")
           (str "It is a plain finding, not a diagnosis, so the "
                (w :diagnosis) " a diagnosis is lifted is not there. "))
         (case value
           :declared
           (str "Its next step serves a value this house declared, which"
                " lifts it " (w :declared) ". ")
           :observed
           (str "Its next step serves a value an agent observed and nobody"
                " has yet affirmed, so the " (w :declared) " a declared value"
                " would lift it is not there. ")
           (str "Its next step serves no value this house holds, so nothing"
                " lifts it for that. "))
         (cond
           (nil? seen)
           (str "Nothing about what you have been shown moves it, because"
                " you are not recording what you were shown. ")
           (zero? (long seen))
           (str "You have not been shown it in the last " window " days, so"
                " nothing holds it there. ")
           (zero? after)
           (str "Shown " (plural seen "day") " in the last " window
                ", and nothing cools while the contest is off (cools_after"
                " 0). ")
           (zero? (long cooled))
           (str "Shown " (plural seen "day") " in the last " window
                " with nothing done, which is not yet a step: it cools one"
                " after " after ". ")
           :else
           (str "Shown " (plural seen "day") " in the last " window
                " with nothing done — " (plural cooled "step")
                " cooled, holding it " (* (long cooled) (w :cooled)) ". "))
         (if (pos? (long (or dismissed 0)))
           (str "You already dismissed " (plural dismissed "finding")
                " on this same next step, holding it "
                (* (long dismissed) (w :dismissed))
                (if declined
                  (str ", and said " (reason-word declined) " about it"
                       (when (= 4 (reason-weight declined))
                         " — the heaviest of the four words")
                       ", holding it " (* (w :declined) (reason-weight declined))
                       ". ")
                  ", and said no word about it. "))
           (str "Nothing on the record says you dismissed a finding on this"
                " same next step. "))
         "Published " (plural (long (or days-old 0)) "day") " ago, with "
         (plural (long (or fresh-days 0)) "day") " of freshness left in the"
         " window, lifting it " (* (w :fresh) (long (or fresh-days 0))) ". "
         "Lift " lift " in all; the seed decides between equals. The floor"
         " still holds: this line shows as many findings as its take says"
         " whenever that many exist, and the rank only chooses which.")))

(defn- cooling-says
  "What the contest did to THIS card, in the household's own words —
  law 5's *a card's why says what lifted or held it*.

  Three of the five sentences are the law rather than the arithmetic,
  and they are said whether or not anybody is recording, because they
  are true either way: the crown is held by its floor, and an
  obligation is outside the contest. The other two are the formula
  read back against this card with the recipe's own numbers in them.

  ONE DEVIATION, RECORDED: law 5's illustrative sentence is *'shown
  because you lingered here'*, and this file cannot honestly say it.
  waymark-8um.1 refused to store a dwell time or an impression count on
  purpose (*'the exposure is the fact and the number of times a thumb
  scrolled back past it is not'*), so there is no lingering in the
  record to cite. What the record holds is the other half of the same
  law — how many mornings a card was in front of somebody and nothing
  happened — and that is what these sentences say."
  [section {:keys [formula seen step crown insight tickler] :as draw}]
  (cond
    ;; the crown, RANKED (waymark-1uv.2): the five numbers, the inputs
    ;; and the arithmetic for this card. The sentence this replaced —
    ;; *held by the floor … not because it won anything* — stopped
    ;; being true the moment the crown chose which bundles fill its
    ;; slots, and a citation that kept saying it would be the lie law
    ;; 5 exists to forbid. The floor's half of it survives at the end,
    ;; because it is still true.
    (and (= :outcomes section) crown)
    (crown-card-says draw)

    ;; a finding, RANKED (waymark-1uv.8): the one decide card the
    ;; *outside the contest* sentence below never honestly described.
    ;; Law 2's list — an ask that expires, a conflict, mail on a shelf
    ;; — names no insight, because an insight is not an obligation; it
    ;; is the contest's own output, and since this bead it has a rank
    ;; of its own. The arm says both halves: this card is ranked, and
    ;; the section's other citizens are still law 2's.
    (and (= :decide section) insight)
    (insight-card-says draw)

    (= :outcomes section)
    (str "Held by the floor: this section's take is a guaranteed slot, so"
         " this card is here because the floor says so. Nothing ranked it"
         " — this read resolved no crown rank, so the seed alone placed"
         " it.")

    ;; the fridge, RANKED (waymark-1uv.9): the five numbers, the
    ;; inputs and the arithmetic for this card. The decide sentence
    ;; below stays true of it — a set-aside item appears because it
    ;; must — and the rank only says WHICH of the due ones.
    (and (= :decide section) tickler)
    (tickler-card-says draw)

    (= :decide section)
    (str "Outside the contest: something waiting on your answer appears"
         " because it must — an ask that expires, a conflict, mail on your"
         " shelf, a change staged for a tap — and what you have already"
         " been shown never moves it.")

    (nil? formula) nil

    (zero? (long seen))
    (str "Fresh — this card has not been on your feed in the last "
         (:window-days formula) " days, so it ranks as unseen rather than"
         " as unloved and the seed alone placed it.")

    (zero? (long step))
    (str "Shown " seen " day" (when (not= 1 (long seen)) "s") " in the last "
         (:window-days formula) " with nothing done, which is not yet enough"
         " to cool it: a card steps back after " (:cools-after formula)
         ".")

    :else
    (str "Cooled — shown " seen " day" (when (not= 1 (long seen)) "s")
         " in the last " (:window-days formula)
         " with nothing done, so it sits " step " step"
         (when (not= 1 (long step)) "s") " back in its own line. Cooling"
         " reorders inside a line and never empties one, the seed still"
         " decides inside a step, and this reads your own rows and nobody"
         " else's.")))

(defn card-says
  "The whole citation for one card, as sentences a parent reads.

  Five parts and each is somebody's declaration rather than this
  function's opinion: the RECIPE LINE that admitted it, the DECLARED
  TRAITS that population reads (in the kind's own words, quoted back
  against this row), the section's own extra bargain where it has one,
  what the CONTEST did to it (waymark-8um.3 — the recipe's own two
  numbers, read back), and the SEED's draw."
  [entry rdef row {:keys [section] :as draw}]
  (into []
        (remove nil?)
        (concat
         ;; the line is numbered as a READER counts, from one; the wire's
         ;; own `why.line` stays a zero-based index into `recipe.lines`
         [(str "Recipe line " (inc (long (:line entry 0))) " — "
               (line-says entry))]
         (trait-says rdef row (population-reads (:population entry)))
         [(when (= :outcomes section) crown-and-floor)
          (when (= :do_now section)
            (str "It kept its place in do now because it still has a verb"
                 " light enough to tap — a next action with nothing under"
                 " the thumb is a row on a list, and drops out."))
          (when (= :archive section)
            (str "It is below the seam because its work is over as the row"
                 " stands now, not merely because it moved a while ago."))
          (cooling-says section draw)
          (drawn-says draw)])))

;; ── the mixer ───────────────────────────────────────────────────────

(defn- entry-cards
  "One recipe entry's contribution: its population's candidates, minus
  anything a section above already carded, seeded, offset-walked for a
  bottomless section, and rendered LAZILY — `take` short-circuits the
  `keep`, so a page of six costs six or seven envelope renders and not
  one per candidate.

  → {:cards [...] :claimed #{[kind id] …} :more? bool :consumed n
  :reached-cap bool}. `:more?` is the remainder question answered the
  way `history/row-history` answers it: render one past the page
  rather than counting the whole set.

  `:consumed` is how far into the ordering this page reached, and it
  is the cursor's arithmetic rather than the page's size (waymark-
  iqa.5). They differ exactly when a candidate renders no card — a row
  retired between the scan and the read, or one this grant conceals —
  and advancing the offset by the CARDS would then re-serve the first
  card of the next page as the last of this one. A duplicate within a
  day is the one thing the archive may not do, so the offset counts
  candidates walked and `:feed/archive-pages` proves it.

  `:claimed` is every candidate this population NAMED, not merely the
  ones that fit — and that is what keeps a row out of the archive on
  page four as surely as on page one. A `seen` set built from the cards
  actually shown would be page-local, and page two, which re-runs the
  populations above the seam without rendering them, would have nothing
  to subtract. So a row an earlier section claims is that section's for
  the day, shown or not: an open errand is do-now's business and never
  a memory.

  `:render? false` is how a cursor page pays for that: the populations
  above the seam run for their CANDIDATES — one query each, no
  envelopes — and contribute nothing but their claim.

  IT ALSO CITES ITSELF (waymark-iqa.29). Every card leaves here with a
  `why` — the line that admitted it, its place in the day's draw, and
  the size of the draw — and the entry reports back the two counts a
  reader wants about the LINE: how many candidates it was offered, and
  how many a section above had already claimed. That last one is the
  cheap half of explaining ABSENCE, and it is the only half this
  document attempts: *why is this card here* is a projection, *why is
  some other row not* is a search over everything."
  [ctx {:keys [section population take* offset bottomless render? kinds
               entry explain?]}
   seen]
  (let [out ((get populations population) ctx)
        {:keys [candidates reached-cap]} (if (map? out)
                                           out
                                           {:candidates out})
        seed (:seed ctx)
        of-kind? (if (seq kinds) (comp (set kinds) :kind) (constantly true))
        unseen (remove #(contains? seen [(:kind %) (:id %)]) candidates)
        claimed-above (- (count candidates) (count unseen))
        cid (fn ^String [c] (card-id section (:kind c) (:id c)))
        ;; the contest, where it is allowed to operate at all
        ;; (waymark-8um.3). Nil is the default and the inert case, and
        ;; the branch below is what makes *inert means unchanged* a
        ;; structural claim: with no cooler the sort is the two-key
        ;; sort it has always been, not a three-key sort whose third
        ;; key happens to be zero.
        cool (cooler ctx section)
        ;; THE CROWN'S OWN RANK (waymark-1uv.2): the recipe's four
        ;; numbers, when this is the crown and the read resolved them.
        ;; A candidate carries its inputs from the population; the one
        ;; input only a read knows — how many mornings THIS reader has
        ;; been shown it — is filled in here off the same view rows and
        ;; the same `cooling-step` the contest uses, or left absent
        ;; when the reader is not recording, which is the contest's own
        ;; inert posture: no record, no key, nothing moves.
        crown-w (when (= :outcomes section) (:crown-rank ctx))
        crown-inputs (fn [c]
                       (let [in (:crown c)]
                         (if-some [{:keys [formula counts]} (:cooling ctx)]
                           (let [seen (long (get counts (cid c) 0))]
                             (assoc in :seen seen
                                    :cooled (cooling-step formula seen)))
                           in)))
        ;; THE FINDINGS' OWN RANK (waymark-1uv.8): the crown's
        ;; arrangement one section down, keyed on the POPULATION
        ;; because `:decide` holds several lines and only this one is
        ;; ranked. The read's own input — how many mornings THIS
        ;; reader has been shown it — is filled in exactly as the
        ;; crown's is, or left absent when nobody is recording.
        insight-w (when (= :insights population) (:insight-rank ctx))
        insight-inputs (fn [c]
                         (let [in (:insight c)]
                           (if-some [{:keys [formula counts]} (:cooling ctx)]
                             (let [seen (long (get counts (cid c) 0))]
                               (assoc in :seen seen
                                      :cooled (cooling-step formula seen)))
                             in)))
        ;; THE TICKLERS LINE'S OWN RANK (waymark-1uv.9): the crown's
        ;; posture at the fridge, keyed by POPULATION because decide
        ;; holds several lines and only this one ranks. The same read
        ;; fills in the same one input, off the same view rows.
        tickler-w (when (= :ticklers population) (:tickler-rank ctx))
        tickler-inputs (fn [c]
                         (let [in (:tickler c)]
                           (if-some [{:keys [formula counts]} (:cooling ctx)]
                             (let [seen (long (get counts (cid c) 0))]
                               (assoc in :seen seen
                                      :cooled (cooling-step formula seen)))
                             in)))
        ordered (->> unseen
                     (filter of-kind?)
                     ;; the LANE first, the hash inside it (waymark-
                     ;; iqa.24). A population that spreads its
                     ;; candidates hands each one the place it holds
                     ;; in its own kind's order; everything else
                     ;; carries lane 0 and this is the sort it always
                     ;; was. Nothing is compared to anything: the lane
                     ;; is composition, the hash is the order.
                     ;;
                     ;; …AND THE COOLING STEP BETWEEN THEM, when the
                     ;; reader is recording and this section is one of
                     ;; the three the contest may weight. The spread
                     ;; stays outermost — the formula weights WITHIN a
                     ;; lane, so no kind can cool its way out of its
                     ;; turn — and the hash stays innermost, so the
                     ;; seed still decides inside a step and the whole
                     ;; answer is a pure function of (member, day,
                     ;; draw, this reader's own view rows).
                     ;;
                     ;; …OR THE CROWN'S KEY, which is the same shape
                     ;; with the person's own request where the lane
                     ;; was and the lift where the step was: asked
                     ;; first, then the lift, then the hash. Still a
                     ;; pure function of (the recipe's numbers, the
                     ;; bundle's own record, this reader's own view
                     ;; rows, the seed); still a sort and never a
                     ;; filter.
                     (sort-by (cond
                                crown-w
                                (fn [c] (crown-key crown-w (crown-inputs c)
                                                   (rank seed (cid c))))
                                ;; …or the findings' key (waymark-1uv.8):
                                ;; the lift, then the hash — the same
                                ;; shape with no tier, because nothing
                                ;; in this line is a person's own request
                                insight-w
                                (fn [c] (insight-key insight-w (insight-inputs c)
                                                     (rank seed (cid c))))
                                ;; …or the fridge's (waymark-1uv.9):
                                ;; a person's own hand where the lane
                                ;; was, the lift where the step was
                                tickler-w
                                (fn [c] (tickler-key tickler-w (tickler-inputs c)
                                                     (rank seed (cid c))))
                                cool
                                (juxt #(long (:lane % 0))
                                      #(cool (cid %))
                                      #(rank seed (cid %)))
                                :else
                                (juxt #(long (:lane % 0))
                                      #(rank seed (cid %))))))
        claimed (into #{} (map (juxt :kind :id)) ordered)
        offered (count ordered)
        off (long (or offset 0))
        ordered (cond->> ordered bottomless (drop off))
        admits? (if (= :archive section)
                  #(finished-history? ctx %)
                  (constantly true))
        keep? (if (= :do_now section) offers-something? (constantly true))
        n (long take*)
        ;; the citation's own numbers: the line, the place the seed put
        ;; this candidate in the whole ordering (never in the page —
        ;; a card on archive page three is drawn 19th of 40, not 1st
        ;; of 6), and the sentences when the reader asked for them
        cite (fn [c cand ^long i]
               (let [;; the contest's own two numbers for THIS card, or
                     ;; nothing at all when it is inert or this section
                     ;; is outside the contest (waymark-8um.3). Both
                     ;; ride the always-on `why`, because a card that
                     ;; moved for a reason and would not say so on a
                     ;; plain read is the thing law 5 forbids; together
                     ;; they are about twenty bytes.
                     seen (when cool
                            (long (get-in (:cooling ctx) [:counts (cid cand)] 0)))
                     step (when cool (long (cool (cid cand))))
                     ;; the crown's numbers for THIS card (waymark-
                     ;; 1uv.2): the inputs the sort read and the lift
                     ;; they add up to, on the plain read, for the
                     ;; contest's own reason — a card that stands
                     ;; where it stands for a reason and would not say
                     ;; so unless asked is the thing law 5 forbids
                     crown (when crown-w (crown-inputs cand))
                     ;; …and the findings' numbers for THIS card
                     ;; (waymark-1uv.8), on the plain read, for the
                     ;; same reason
                     insight (when insight-w (insight-inputs cand))
                     ;; …and the fridge's for THIS card (waymark-1uv.9)
                     tickler (when tickler-w (tickler-inputs cand))
                     draw (cond-> {:rank (inc (+ off i)) :of offered
                                   :lane (:lane cand 0) :kind (:kind cand)
                                   :section section :day (:day ctx)
                                   ;; …and which draw's order these
                                   ;; numbers belong to, where the
                                   ;; person dealt again
                                   :draw (:draw ctx)
                                   :seeded-for (:seeded-for ctx "you")}
                            cool (assoc :formula (:formula (:cooling ctx))
                                        :seen seen :step step)
                            crown (assoc :crown crown
                                         :crown-weights crown-w
                                         :formula (:formula (:cooling ctx)))
                            insight (assoc :insight insight
                                           :insight-weights insight-w
                                           :formula (or (:formula (:cooling ctx))
                                                        {:window-days (:window-days ctx)}))
                            tickler (assoc :tickler tickler
                                           :tickler-weights tickler-w
                                           :formula (:formula (:cooling ctx))))
                     rdef (get (resources ctx) (:kind cand))]
                 (assoc c "why"
                        (cond-> {"line" (:line entry)
                                 "rank" (:rank draw) "of" offered}
                          cool (assoc "seen" seen "cooled" step)
                          crown (assoc "crown" (crown-as-cited crown-w crown))
                          insight (assoc "insight" (insight-as-cited insight-w insight))
                          tickler (assoc "tickler"
                                         (tickler-as-cited tickler-w tickler))
                          explain?
                          (assoc "says"
                                 (card-says entry rdef
                                            (or (:row cand)
                                                (load-raw ctx (:kind cand)
                                                          (:id cand)))
                                            draw))))))
        ;; [candidate-index card] pairs, one past the page: the index
        ;; is what the cursor advances by and the card is what the
        ;; page shows
        taken (if (false? render?)
                []
                (into []
                      (comp (keep-indexed
                             (fn [i cand]
                               (when (admits? cand)
                                 (when-some [c (card ctx section population cand)]
                                   (when (keep? c)
                                     [(long i) (cite c cand (long i))])))))
                            (take (inc n)))
                      ordered))
        page (mapv second (take n taken))]
    {:cards page
     :claimed claimed
     :offered offered
     :claimed-above claimed-above
     :showed (count page)
     :more? (> (count taken) n)
     :consumed (if (< (count page) n)
                 ;; the ordering ran out inside this page: everything
                 ;; left was walked, whether it carded or not
                 (count ordered)
                 (inc (long (first (nth taken (dec n))))))
     :reached-cap (boolean reached-cap)}))

(defn- seam-card [above sentence]
  {"card_id" "seam"
   "section" "seam"
   "above" above
   "sentence" sentence})

(defn document
  "The feed, as one document.

  With no cursor: every section the recipe declares, the seam, and the
  first page of the bottomless section. With a cursor: the bottomless
  section ALONE, walked by `:offset` — the mixed sections are done
  above the seam and re-serving them would be the duplication the epic
  forbids.

  `:sections` names the sections this ANSWER actually carries, in
  recipe order. An empty population contributes nothing and the seam
  moves up; a placeholder card saying 'nothing here' would be the
  manufactured engagement the third law forbids, in its politest
  disguise.

  `:preview` (waymark-iqa.23) is the ONE opt that changes nothing
  about how the document is computed and everything about how it must
  be read. When present, `:principal` and `:visibility` are the
  PREVIEWED member's, not the caller's — the door built them through
  the member gate and the unscoped-visibility expression, so every
  line below runs the member's own read, unaltered — and this key is
  what keeps that from being a silent impersonation: it says whose
  feed and who is looking, in the document, in the summary, and in the
  first note. A preview is never quiet. `{:of {…} :by {…} :grant id}`;
  the grant id is there because the grant IS the durable record of
  this reading (the read itself writes nothing — the feed is a GET).

  `:explain?` (waymark-iqa.29) is the other opt that changes nothing
  about WHICH cards are answered — the day's order is the day's order
  — and only what each one says about itself. Off, a card carries the
  citation's numbers (`why`: the line, the rank, the size of the
  draw); on, it carries the sentences too. The narrated `recipe` rides
  the document either way, because it is one narration per LINE rather
  than one per card and the counts beside it are the read's own.

  `:views` (waymark-8um.1) is not an opt at all but an answer: whether
  this reader's screen may report the cards it showed, and where the
  switch lives either way. The GET still writes nothing — this key is
  what lets the SCREEN write, through its own declared door, and it
  reads `recording false` on every preview so a previewer's page has
  nothing to beacon about.

  `:recipe-source` (waymark-4yn) is the third such opt and the one that
  makes the other two honest once the recipe is EDITABLE: the stamp
  saying which recipe answered — a stored row by id and version, or
  the built-in. Without it, `recipe` would narrate an order without
  ever saying whose, and a mid-day edit would be invisible to the
  surface whose whole job is explaining itself. The route resolves it
  (`waymark10.feed-recipe/for-reader`); this function is handed the
  answer, exactly as it is handed the recipe.

  `:draw` (waymark-8um.2) is the fourth, and the only one that changes
  the ORDER — which is why it exists at all and why nothing but a
  person's tap ever supplies it. It joins the seed; the answer is a
  fresh order, as stable as the day's, walked by cursors that carry
  the draw with them. Nil is the daily draw, and a nil draw computes
  the byte-identical document this function computed before the
  parameter existed: same seed, same cards, same notes, same cursor.
  The person spins; the system never spins for them, and a document
  that advertised the spin on every read would be the system asking."
  [eng recipe {:keys [principal visibility offset preview explain?
                      recipe-source draw]}]
  (let [day (today eng recipe)
        pid (:id principal)
        seed (seed-of recipe pid day draw)
        ctx (cond-> {:eng eng :principal principal :visibility visibility
                     :now ((:now-fn eng)) :seed seed :day day :draw draw
                     ;; whose seed this is, in the citation's own
                     ;; sentence — under a preview "you" is a lie, the
                     ;; same correction the first note already makes
                     :seeded-for (or (get-in preview [:of :display]) "you")}
              ;; one render-probe instance for the whole read (the
              ;; router's own posture, one per request): a card's
              ;; verbs are then the honest ones rather than the
              ;; optimistic advertisement, and the cache keeps the
              ;; repeated probe of one member row to a single query
              (:probe-reads eng) (assoc :render-hooks (inv/render-hooks eng)))
        ;; the view switch, asked ONCE and answered twice: `views`
        ;; below says whether this reader's SCREEN may report what it
        ;; showed, and the contest reads the rows that switch let be
        ;; written. Two questions, one indexed row read.
        recording (recording? ctx)
        ;; the contest's state for this read, or nil — and nil is what
        ;; a household that has said nothing gets, which is everybody
        ;; until somebody chooses otherwise (waymark-8um.3)
        cooling (reader-cooling ctx recipe recording)
        ctx (cond-> ctx cooling (assoc :cooling cooling))
        ;; the crown's five numbers, resolved once for the read
        ;; (waymark-1uv.2) — `entry-cards` sorts the crown by them and
        ;; every crown card quotes them back
        ctx (assoc ctx :crown-rank (crown-rank-of recipe))
        ;; …and the findings' six, with the contest's window beside
        ;; them (waymark-1uv.8): the population reads the window to
        ;; count a finding's freshness, whether or not anybody is
        ;; recording — freshness is the row's own age, not a view.
        ;; …and the fridge's five (waymark-1uv.9), the same way.
        ctx (assoc ctx :insight-rank (insight-rank-of recipe)
                   :window-days (:window-days (formula-of recipe))
                   :tickler-rank (tickler-rank-of recipe))
        archive-only? (some? offset)
        lines (into [] (map-indexed (fn [i e] (assoc e :line i)))
                    (:order recipe))
        {:keys [cards more? capped crown-capped insight-capped ticklers-capped
                walked counts]}
        (reduce
         (fn [acc e]
           (cond
             ;; a cursor page is the bottomless section ALONE: the
             ;; seam happens once, above, on the day's first read
             (:seam e) (cond-> acc
                         (not archive-only?)
                         (update :cards conj
                                 (assoc (seam-card
                                         (count (:cards acc))
                                         (:sentence e
                                                    "That's the house, caught up."))
                                        "why"
                                        (cond-> {"line" (:line e)}
                                          explain?
                                          (assoc "says" [(line-says e)
                                                         recipe-guarantees])))))
             :else
             (let [got (entry-cards ctx
                                    {:section (:section e)
                                     :population (:population e)
                                     :take* (:take e)
                                     :kinds (:kinds e)
                                     :offset (when (:bottomless e) offset)
                                     :bottomless (:bottomless e)
                                     :entry e
                                     :explain? explain?
                                     :render? (or (not archive-only?)
                                                  (boolean (:bottomless e)))}
                                    (:seen acc))]
               (cond-> (-> acc
                           (update :cards into (:cards got))
                           (update :seen into (:claimed got))
                           ;; the archive's cap, the crown's, the
                           ;; findings' and the fridge's are four
                           ;; different bounds with four different
                           ;; notes, so the crown's is folded by
                           ;; section (waymark-1uv.2) and the other two
                           ;; by population (waymark-1uv.8, -1uv.9)
                           (update (cond
                                     (= :outcomes (:section e)) :crown-capped
                                     (= :insights (:population e)) :insight-capped
                                     (= :ticklers (:population e)) :ticklers-capped
                                     :else :capped)
                                   #(or % (:reached-cap got)))
                           (assoc-in [:counts (:line e)]
                                     {"offered" (:offered got)
                                      "claimed_above" (:claimed-above got)
                                      "showed" (:showed got)}))
                 (:bottomless e)
                 (-> (assoc :more? (:more? got))
                     (update :walked + (long (:consumed got 0))))))))
         {:cards [] :seen #{} :more? false :capped false :crown-capped false
          :insight-capped false :ticklers-capped false :walked 0 :counts {}}
         lines)
        bottomless (some :bottomless (:order recipe))
        next-offset (+ (long (or offset 0)) (long walked))
        sections (into [] (distinct) (map #(get % "section") cards))
        of (get-in preview [:of :display])
        by (get-in preview [:by :display])
        ;; the preview's address rides every link this document hands
        ;; out, or page two of an archive walk would silently become
        ;; the PREVIEWER's own feed — the one place a stamp could be
        ;; told the truth and the hrefs a lie
        base (str "/api/-/feed"
                  (when preview
                    (str "?preview_as="
                         (url-encode (str (get-in preview [:of :id])))))
                  ;; …and the DRAW rides them too (waymark-8um.2), for
                  ;; the same reason one register over: `self` is the
                  ;; address of THIS read, and a self that dropped the
                  ;; draw would name the daily order while the cards
                  ;; below it were somebody's tap. It is in the cursor
                  ;; as well, and the two must agree — see draw-mismatch
                  (when draw (str (if preview "&" "?") "draw="
                                  (url-encode (str draw))))
                  ;; an explained read stays explained page after page:
                  ;; a `links.next` that dropped the parameter would
                  ;; hand a reader who asked why an archive that would
                  ;; not say
                  (when explain? (if (or preview draw)
                                   "&explain=1" "?explain=1")))
        ;; the narrated recipe, with the read's own counts folded in —
        ;; the static half is a pure function of the recipe and the
        ;; counts are what THIS read saw each line offered
        ;; the view door's standing (waymark-8um.1): whether this
        ;; reader's screen may report what it showed, and where the
        ;; switch is either way. Computed here rather than left to the
        ;; client because the PREVIEW half has to be the server's — see
        ;; views-doc
        views (views-doc ctx preview recording)
        ;; the reason door (waymark-jfv.16): where a SETTLED card sends
        ;; the quick word somebody taps after a decline lands, and the
        ;; four words it may send. A door rather than an answer, so
        ;; unlike `views` it has no preview clause — see reasons-doc.
        reasons (reasons-doc ctx)
        ;; the crown's own chip and standing (waymark-jfv.20): whether
        ;; the crown carded nothing, the door 'compose me another'
        ;; knocks on, and the requests this reader already has open —
        ;; see crown-doc
        crown (crown-doc ctx cards archive-only?)
        recipe-doc (cond-> (update (recipe-view recipe) "lines"
                                   (fn [ls] (mapv (fn [l]
                                                    (merge l (get counts (get l "line"))))
                                                  ls)))
                     recipe-source (assoc "source" recipe-source))
        notes (into []
                    (remove nil?)
                    [(when preview
                       (str "A PREVIEW, and not your own feed: this is " of
                            "'s, computed for " of " through " of "'s own"
                            " sight, and read by " by " under a "
                            "feed.preview_as grant. The verbs below are "
                            of "'s — each action href is " of "'s door, and"
                            " a request " by " sends there is judged as " by
                            " and refused. Nothing was written by this read;"
                            " the lasting record of it is the grant itself ("
                            (:grant preview) ") — its ask, its approval, its"
                            " expiry and the door that revokes it."))
                     ;; the seed's own sentence — and where somebody
                     ;; DEALT AGAIN (waymark-8um.2), the sentence says
                     ;; so plainly and says how to come back. The daily
                     ;; half is unchanged to the byte: a reader who
                     ;; never taps is told exactly what they were told
                     ;; before this parameter existed, because a
                     ;; surface that mentioned the spin on every read
                     ;; would be the system asking a person to spin.
                     (if draw
                       (str "You dealt again — this is draw " draw " of ("
                            (or of "you") ", " day "), a fresh order over the"
                            " same house. It holds while you read it and the"
                            " pages below continue THIS draw. The house's"
                            " usual order for today is one read away — drop"
                            " the draw — and it comes back on its own"
                            " tomorrow. Nothing about the spin was written"
                            " down.")
                       (str "One order, seeded by (" (or of "you") ", " day
                            ") — stable until midnight and stored nowhere. Two"
                            " members read two different feeds on the same day."))
                     (when (and visibility (not preview))
                       (str "Read through your grant: a row your leash does not"
                            " confer is ABSENT here, never narrowed and never"
                            " refused — one endpoint, per-member worlds."))
                     (when (and visibility preview)
                       (str "Read through " of "'s OWN sight, whatever your"
                            " grant confers: a preview that projected through"
                            " the previewer's leash would answer a feed"
                            " nobody has."))
                     (if explain?
                       (str "Every card says why it is here: recipe carries"
                            " the order line by line, and each card's why"
                            " names the line that admitted it, the traits its"
                            " own declaration spells, and where today's seed"
                            " drew it.")
                       (str "Every card carries a why — the recipe line that"
                            " admitted it and where today's seed drew it —"
                            " and recipe below narrates the order itself. Add"
                            " ?explain=1 to have each card spell its citation"
                            " out in sentences."))
                     ;; …and whether the contest weighted THIS read
                     ;; (waymark-8um.3). Said only when it did, and for
                     ;; the same reason the sentence above is: a
                     ;; surface that explained an inert mechanism every
                     ;; morning would be advertising it. When it is
                     ;; inert the recipe view still carries the formula
                     ;; and its sentence, for whoever asks.
                     (when cooling
                       (let [{:keys [window-days cools-after]} (:formula cooling)]
                         (str "This order was weighted by what you have"
                              " already been shown: a card that has been on"
                              " your feed " cools-after " day"
                              (when (not= 1 (long cools-after)) "s")
                              " inside the last " window-days
                              " with nothing done cools"
                              " a step and sits behind the fresher cards in"
                              " its own line. It reads your own rows and"
                              " nobody else's, it never empties a line, and"
                              " the crown and everything waiting on your"
                              " answer are outside it. Every card says which"
                              " of those it is; recipe.formula is the whole"
                              " of the arithmetic; stop the record and this"
                              " stops with it.")))
                     (when (:reached-cap cooling)
                       (str "The contest read to its cap and stopped — the"
                            " newest " view-scan-cap " of your own view rows."
                            " Older days in the window are unread, so a card"
                            " may read as WARMER than it has earned, never"
                            " cooler."))
                     ;; …and whether this read is being remembered
                     ;; (waymark-8um.1). Said out loud only when it IS,
                     ;; because a surface that announced its own
                     ;; silence on every read would be asking for a
                     ;; permission it was right not to have.
                     (when (:recording views) (:says views))
                     ;; whose order this was, and how to change it
                     ;; (waymark-4yn) — one sentence, because a surface
                     ;; that narrates its order and will not say where
                     ;; the order came from is explaining the wrong half
                     (get recipe-source "says")
                     (get recipe-source "stranded")
                     ;; …and whether the crown ranked everything on
                     ;; offer (waymark-1uv.2). Said only when it did
                     ;; not, in the archive's own posture one note
                     ;; down: the bound is honest and the reader is
                     ;; told which way it failed.
                     (when crown-capped
                       (str "The crown read to its cap and stopped — the"
                            " newest " crown-scan-cap " bundles on offer"
                            " were ranked, and older ones were not read"
                            " today. Those are the ones nearest their lapse,"
                            " which the rank already places last; a house"
                            " with more than " crown-scan-cap " on offer at"
                            " once has a composer to talk to before it has"
                            " a cap to raise."))
                     ;; …and whether the findings' line ranked everything
                     ;; published (waymark-1uv.8), the same posture
                     (when insight-capped
                       (str "The findings' line read to its cap and stopped"
                            " — the newest " insight-scan-cap " published"
                            " findings were ranked, and older ones were not"
                            " read today. Those are the ones the rank's own"
                            " freshness already places last; a house with"
                            " more than " insight-scan-cap " unanswered"
                            " findings has an agent to talk to before it has"
                            " a cap to raise."))
                     ;; …and whether the fridge ranked every marker
                     ;; on offer (waymark-1uv.9), the crown's note one
                     ;; line down
                     (when ticklers-capped
                       (str "The fridge read to its cap and stopped — the"
                            " newest " tickler-scan-cap " set-aside markers"
                            " were ranked, and older ones were not read"
                            " today. Nothing was dropped: they stand, and a"
                            " morning with fewer due will reach them."))
                     (when capped
                       (str "The archive read to its cap and stopped — the"
                            " newest " log-scan-cap " transitions for what"
                            " moved, " history/fold-cap " for a year ago this"
                            " week; older moves are unread. A population that"
                            " outgrows its cap earns a job of its own, and"
                            " only it."))])]
    (assoc (p/wire-value
            (cond-> {:waymark "10"
                     :kind "feed"
                     :self base
                     :day day
                     :seed seed
                     :summary (str "Feed · " day " · " (count cards)
                                   " card" (when (not= 1 (count cards)) "s")
                                   (when draw (str " · draw " draw))
                                   (when preview
                                     (str " · PREVIEW of " of " · read by "
                                          by)))
                     :sections sections
                     :recipe recipe-doc
                     :notes notes}
              ;; the draw, named in the document that answered it —
              ;; absent on the daily order, because the daily order is
              ;; the absence of a draw and not a draw with a name
              draw (assoc :draw draw)
              preview (assoc :preview preview)
              views (assoc :views views)
              reasons (assoc :reasons reasons)
              crown (assoc :crown crown)
              (and bottomless more?)
              (assoc :links
                     {:next {:href (str base
                                        (if (str/includes? base "?") "&" "?")
                                        "cursor="
                                        (encode-cursor
                                         (cond-> {:day day :seed seed
                                                  :offset next-offset}
                                           draw (assoc :draw draw))))}})))
           "cards" cards)))
