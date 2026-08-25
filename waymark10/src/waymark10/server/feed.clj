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
           (java.time Instant LocalDate ZoneId ZoneOffset)
           (java.time.temporal ChronoUnit)
           (java.time.zone ZoneRulesException)
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
  measured — and the WEEKLY CAP is nowhere near here: it is
  `outcome/outcomes-are-few`, a wall at the create door, because a
  filter in a population would bury what the door already let be
  staged and would teach a composer nothing. The recipe has no word
  for a week and must not grow one; `:take` is per PAGE.

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
  `:take` IS the exposure floor law 3 asks for, so weighting it would
  be the contest eating the measurement it exists to be measured by.

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
  [ctx kind where]
  (let [st (:storage (:eng ctx))]
    (try
      (store/with-tx st
        (fn [tx] (store/query-rows st tx kind where
                                   {:limit row-scan-cap :newest-first true})))
      ;; a kind in the registry whose table this engine never made is
      ;; an assembly the feed does not get to fail over: the population
      ;; contributes nothing and the seam moves up
      (catch Exception _ []))))

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
            label (fn [v] (or (get choices (str v))
                              (get choices (keyword (str v)))
                              (str v)))]
        (when (seq (:enum prop))
          {:post_to post-to
           :field "reason"
           :choices (mapv (fn [v] {:value (str v) :label (label v)})
                          (:enum prop))
           :says (str "A decline that has landed may say why, in one more"
                      " optional tap. Nothing is written unless somebody"
                      " taps — silence is a complete answer — and the"
                      " sentence a quick word could not carry lives one"
                      " screen deeper, at " post-to ".")})))))

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

  The cost is bounded the way every read-time population's is: at
  most `row-scan-cap` markers are read, and only the DUE ones cost a
  subject read. A household with a hundred ticklers due on one day
  has a filing problem the feed cannot fix."
  [ctx]
  (if-some [rdef (get (resources ctx) :tickler)]
    (let [now (:now ctx)
          due? (fn [t] (or (nil? t) (not (pos? (compare t now)))))]
      (into []
            (keep (fn [raw]
                    (let [d (inv/decode-row rdef raw)]
                      (when (and (due? (get-in d [:data :next_offer_at]))
                                 (set-aside?
                                  ctx
                                  (some-> (get-in d [:data :subject_kind])
                                          str not-empty keyword)
                                  (get-in d [:data :subject_id])))
                        {:kind :tickler :id (:id raw) :row raw}))))
            (rows-of ctx :tickler {:state "offered"})))
    []))

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

  The bound is the read-time posture's: at most `row-scan-cap`
  findings are read, and only the ones that survive the first two
  filters cost a subject read. The kind's own daily cap
  (`insight/insights-are-capped`, three an author) is what keeps that
  number small at the source, which is the point of putting the wall
  at the door instead of here."
  [ctx]
  (if-some [rdef (get (resources ctx) :insight)]
    (let [pid (:id (:principal ctx))]
      (into []
            (keep (fn [raw]
                    (let [d (inv/decode-row rdef raw)]
                      (when (and (not= pid (get-in d [:data :authored_by]))
                                 (set-aside?
                                  ctx
                                  (some-> (get-in d [:data :offer_kind])
                                          str not-empty keyword)
                                  (get-in d [:data :offer_id])))
                        {:kind :insight :id (:id raw) :row raw}))))
            (rows-of ctx :insight {:state "published"})))
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
;; form, and today the enum closes at one: `:create`. waymark-jfv.9's
;; general piece — {kind, row id, action, prepared} — slots its own
;; arm in beside this one ("Yes will <action> <that row>: <the
;; engine's diff>") without touching it, which is why the create arm
;; is spelled as an arm rather than as the whole function.

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

(defn piece-impact
  "The engine's reading of one still-offered piece's tap, in the
  household's own words — the CREATE arm, which is the whole of the
  target form enum today.

  Every word of it is derived: the verb from the piece kind's primary
  action, the noun from the target's declaration, the name from the
  target's `:label-template` over the prepared body, and the mirror
  clause from the target's `:mirror`. nil when the target kind is not
  one this engine serves, which is the same answer the card gives —
  no line rather than a guess."
  [prdef trdef prepared]
  (when (and prdef trdef (map? prepared))
    (let [noun (kind-noun trdef)
          label (prepared-label trdef prepared)]
      (str (or (affirming-verb prdef) "This") " will create one " noun
           (when label (str ": " (pr-str label)))
           " — in this house's own record"
           (mirror-clause trdef noun)
           ". Nothing else."))))

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
        ev (count (remove str/blank? (map str (:evidence data))))]
    (str/trim
     (str (when v
            (str "For " v
                 (when observed?
                   (str " — a value observed in your record, not yet"
                        " affirmed, so say whether it is yours before a"
                        " week goes to it"))
                 ". "))
          routing
          (when (pos? ev)
            (str " " ev " row" (when (not= 1 ev) "s") " behind it."))))))

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
  same sentence, and a piece keeps whichever it was born with."
  [ctx prdef pd]
  (or (some-> (get-in pd [:data :impact]) str not-empty)
      (piece-impact prdef
                    (get (resources ctx)
                         (keyword (str (get-in pd [:data :target_kind]))))
                    (get-in pd [:data :prepared]))))

(defn- bundle-parts
  "The pieces of one bundle, oldest first — the order `take-the-rest`
  fans out in, so the household reads them in the order the engine
  would take them and a refusal names the piece they read first."
  [ctx oid]
  (vec (sort-by (juxt #(str (:created-at %)) #(str (:id %)))
                (rows-of ctx :outcome_piece {:outcome_id (str oid)}))))

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
    terminal, so an answered outcome leaves the feed by construction.
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

  THE WEEKLY CAP IS NOT HERE and must not come here. It is
  `outcome/outcomes-are-few`, two per author per calendar week, at the
  create door — so a composer has to RANK. A read-side window would
  bury what the door already let be staged, would leave those rows
  sitting offered teaching the composer that this house ignores it,
  and would make laws v3's exposure floor unmeasurable: a learner
  cannot learn about a card it never shows.

  The cost is the read-time posture's, bounded twice over: at most
  `row-scan-cap` bundles are scanned, each surviving one costs a value
  read and a piece query, and `a-bundle-is-small` caps the pieces at
  five. The create-door cap is what keeps the number of bundles small
  at the source, which is the point of putting the wall there."
  [ctx]
  (let [rdef (get (resources ctx) :outcome)
        prdef (get (resources ctx) :outcome_piece)]
    (if-not (and rdef prdef)
      []
      (let [pid (:id (:principal ctx))
            now (:now ctx)]
        (into []
              (keep (fn [raw]
                      (let [d (inv/decode-row rdef raw)
                            good (get-in d [:data :good_until])
                            parts (bundle-parts ctx (:id raw))
                            standing (value-standing ctx d)]
                        (when (and (not= pid (get-in d [:data :composed_by]))
                                   (or (nil? good) (pos? (compare good now)))
                                   (<= (long bundle-floor) (count parts))
                                   (some offered? parts)
                                   (some? standing))
                          {:kind :outcome :id (:id raw) :row raw
                           :sentence (outcome-says d (= :observed standing))
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
              (rows-of ctx :outcome {:state "offered"}))))))

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
  action is idempotent, so actions-from-the-feed is one prefix away —
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

(defn recipe-diff
  "The whole of a staged change, in the household's own words: what
  moves in the ORDER, and what moves in the CONTEST. Both halves are
  positional-and-pure like everything else here, and an order that
  moved nothing says so rather than vanishing — but only when the
  contest moved nothing either, because *nothing changes* beside a
  sentence saying what changes would be the diff arguing with itself.

  Both arguments are recipe maps, `{:order … :formula …}`."
  [was now]
  (let [moves (order-diff (:order was) (:order now))
        moved? (not= moves [order-unchanged])
        f (formula-diff (:formula was) (:formula now))]
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
       " engine actually holds; and the contest is two numbers a person can"
       " read. A recipe that broke any of those would have refused to start"
       " rather than serve you a surprise."))

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
  [section {:keys [formula seen step]}]
  (cond
    (= :outcomes section)
    (str "Held by the floor: this section's take is a guaranteed slot, so"
         " this card is here because the floor says so and not because it"
         " won anything. Nothing about what you have already been shown"
         " moves it — a contest nobody can measure is not a contest.")

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
                     (sort-by (if cool
                                (juxt #(long (:lane % 0))
                                      #(cool (cid %))
                                      #(rank seed (cid %)))
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
                     draw (cond-> {:rank (inc (+ off i)) :of offered
                                   :lane (:lane cand 0) :kind (:kind cand)
                                   :section section :day (:day ctx)
                                   ;; …and which draw's order these
                                   ;; numbers belong to, where the
                                   ;; person dealt again
                                   :draw (:draw ctx)
                                   :seeded-for (:seeded-for ctx "you")}
                            cool (assoc :formula (:formula (:cooling ctx))
                                        :seen seen :step step))
                     rdef (get (resources ctx) (:kind cand))]
                 (assoc c "why"
                        (cond-> {"line" (:line entry)
                                 "rank" (:rank draw) "of" offered}
                          cool (assoc "seen" seen "cooled" step)
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
        archive-only? (some? offset)
        lines (into [] (map-indexed (fn [i e] (assoc e :line i)))
                    (:order recipe))
        {:keys [cards more? capped walked counts]}
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
                           (update :capped #(or % (:reached-cap got)))
                           (assoc-in [:counts (:line e)]
                                     {"offered" (:offered got)
                                      "claimed_above" (:claimed-above got)
                                      "showed" (:showed got)}))
                 (:bottomless e)
                 (-> (assoc :more? (:more? got))
                     (update :walked + (long (:consumed got 0))))))))
         {:cards [] :seen #{} :more? false :capped false :walked 0 :counts {}}
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
