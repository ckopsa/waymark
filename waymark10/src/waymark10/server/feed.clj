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
  preference. do-now (the next physical action) → decide (what is
  waiting on this reader) → fuel (history as momentum) → the caught-up
  seam → the archive interleave. A recipe that puts fuel above do-now
  is a typo, and `check-recipe!` says so rather than serving it."
  [:do_now :decide :fuel :seam :archive])

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

  `:memories` is the bottomless tail and `:events` is one of the two
  sources it reads (waymark-iqa.8): check (3) admits exactly one
  bottomless entry, so the archive's stand-in did not move down the
  page — it moved INSIDE the population that replaced it, and the
  archive got the anniversary read on top rather than a card fewer."
  {:salt "waymark-feed"
   :zone "UTC"
   :order
   [{:section :do_now  :population :next_actions :take 5}
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
    {:section :archive :population :memories :take 6 :bottomless true}]})

;; ── the seed ────────────────────────────────────────────────────────

(defn today
  "The recipe's own idea of what day it is: `(:now-fn eng)` read in the
  recipe's `:zone`. Midnight there is when the feed rolls."
  ^String [eng recipe]
  (str (LocalDate/ofInstant ^Instant ((:now-fn eng))
                            (ZoneId/of (:zone recipe "UTC")))))

(defn seed-of
  "sha256(salt ‖ member-principal-id ‖ local-date). Same member, same
  day, same feed; midnight rolls it; two members on the same day see
  different worlds because the id is in the hash. It stores NOTHING,
  which is the whole of 'stable within a day' and the answer to the
  materializing job the spec declined to build."
  ^String [recipe pid ^String day]
  (wire/sha256-hex (str (:salt recipe "waymark-feed") "\u001f" pid "\u001f" day)))

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
  "One opaque base64 token over {:day :seed :offset}. Opaque because a
  client that could edit the seed could re-roll its own feed until it
  liked the order, which is the ranking model coming in through a
  query parameter."
  ^String [{:keys [day seed offset]}]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (.getBytes (wire/write-json {"day" day "seed" seed
                                                "offset" (long offset)})
                              StandardCharsets/UTF_8)))

(defn decode-cursor
  "The token, read back. A token this engine did not mint is a 422 that
  names the parameter rather than a silent fall back to the top of the
  feed — a cursor that quietly answered page one would make deep paging
  look like an infinite loop of the same six cards."
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
                     (int? (:offset m)) (nat-int? (:offset m)))
        (bad))
      {:day (:day m) :seed (:seed m) :offset (long (:offset m))})))

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
  (waymark-0k4). A later population arrives the same way, and swapping
  one entry for a materializing read is fork (a)'s recorded punt
  working exactly as promised."
  {:next_actions next-actions
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
    4. sections appear in census order.

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
  `heavier` is that a card does not lie about a door it has."
  [ctx section population {:keys [kind id row at sentence]}]
  (let [rdef (get (resources ctx) kind)
        vis (:visibility ctx)]
    (when (and rdef (or (nil? vis) ((:row? vis) kind id)))
      (when-some [decoded (if row (inv/decode-row rdef row)
                              (load-decoded ctx rdef id))]
        (let [body (dissoc (render/envelope-summary rdef decoded (ctx-opts ctx))
                           "waymark" "unavailable")]
          (cond-> (assoc (split-verbs body (get body "self"))
                         "card_id" (card-id section kind id)
                         "section" (name section)
                         "population" (name population))
            at (assoc "at" (str at))
            ;; the seam's own key, and deliberately the same one: a
            ;; `sentence` is the element of this document that is
            ;; PROSE rather than a projection, and a fuel card says
            ;; the thing its row cannot say about itself
            sentence (assoc "sentence" sentence)))))))

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
;; late lines up by `card_id` and cannot be a different day's feed.
;; Always-on would have doubled a fuel card, which is mostly a
;; sentence already, for a disclosure most reads never open.

(def population-says
  "What each population goes looking for, in household words. It is
  the framework describing its own code — a population is a `defn` up
  this file, so this is the one place its intent can be said in a
  sentence a parent reads on a phone. A recipe line may override the
  whole narration with `:says`; this is what a line that says nothing
  falls back to."
  {:next_actions "rows nobody has finished yet, from the kinds this house goes to"
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
  `letters`, `ticklers`, `conflicts`, `insights` and `proposals` all
  choose by a STATE their own kind declares and by whose row it is,
  which the line's own sentence already says and the card's own
  `state` already shows. `proposals` adds a clock read on top of that
  and still declares nothing here, for the same reason: `expires_at`
  is a field of the row, not a trait of the kind, and the card carries
  it."
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
      (empty? moves)
      ["Nothing changes — this is the order already in force, line for line."]

      (not= (count was) (count now))
      (into [(str "The order goes from " (count was) " line"
                  (when (not= 1 (count was)) "s") " to " (count now) ".")]
            moves)

      :else moves)))

(def recipe-guarantees
  "The four assembly checks, as the one sentence they buy a reader.
  `check-recipe!` runs them at the route's build site, so a recipe
  that broke any of them refused the boot rather than this request —
  which is why this can be said in the present tense."
  (str "The sections always come in this order — do now, decide, fuel,"
       " the seam, the archive; exactly one card is the seam; the archive"
       " is last and bottomless; and every line names a population this"
       " engine actually holds. A recipe that broke any of those would have"
       " refused to start rather than serve you a surprise."))

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
                      (assoc "kinds" (mapv name (:kinds e))))))
                 (:order recipe))})

(defn- drawn-says
  "The seed's own half of the citation, honestly: which of how many,
  and — where a population SPREAD its candidates — whose turn it was.
  Nothing here compares two cards; `rank` is the place
  `hash(seed ‖ card_id)` put this one and `lane` is the place it holds
  in its own kind's order, which is composition and not a score."
  [{:keys [rank of lane kind day seeded-for]}]
  (str "Drawn " (ordinal rank) " of " of " this line offered today, by ("
       seeded-for ", " day ")'s seed."
       (when (and lane (pos? (long lane)))
         (str " It came up on " (name kind) "'s turn — lane " lane " of its"
              " own kind's order, so the slots go round the kinds rather than"
              " to whichever kind holds the most rows."))
       " Nothing was ranked against anything: the seed decides the order, and"
       " it decides once a day."))

(defn card-says
  "The whole citation for one card, as sentences a parent reads.

  Four parts and each is somebody's declaration rather than this
  function's opinion: the RECIPE LINE that admitted it, the DECLARED
  TRAITS that population reads (in the kind's own words, quoted back
  against this row), the section's own extra bargain where it has one,
  and the SEED's draw."
  [entry rdef row {:keys [section] :as draw}]
  (into []
        (remove nil?)
        (concat
         ;; the line is numbered as a READER counts, from one; the wire's
         ;; own `why.line` stays a zero-based index into `recipe.lines`
         [(str "Recipe line " (inc (long (:line entry 0))) " — "
               (line-says entry))]
         (trait-says rdef row (population-reads (:population entry)))
         [(when (= :do_now section)
            (str "It kept its place in do now because it still has a verb"
                 " light enough to tap — a next action with nothing under"
                 " the thumb is a row on a list, and drops out."))
          (when (= :archive section)
            (str "It is below the seam because its work is over as the row"
                 " stands now, not merely because it moved a while ago."))
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
        ordered (->> unseen
                     (filter of-kind?)
                     ;; the LANE first, the hash inside it (waymark-
                     ;; iqa.24). A population that spreads its
                     ;; candidates hands each one the place it holds
                     ;; in its own kind's order; everything else
                     ;; carries lane 0 and this is the sort it always
                     ;; was. Nothing is compared to anything: the lane
                     ;; is composition, the hash is the order.
                     (sort-by (juxt #(long (:lane % 0))
                                    #(rank seed (card-id section (:kind %)
                                                          (:id %))))))
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
               (let [draw {:rank (inc (+ off i)) :of offered
                           :lane (:lane cand 0) :kind (:kind cand)
                           :section section :day (:day ctx)
                           :seeded-for (:seeded-for ctx "you")}
                     rdef (get (resources ctx) (:kind cand))]
                 (assoc c "why"
                        (cond-> {"line" (:line entry)
                                 "rank" (:rank draw) "of" offered}
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

  `:recipe-source` (waymark-4yn) is the third such opt and the one that
  makes the other two honest once the recipe is EDITABLE: the stamp
  saying which recipe answered — a stored row by id and version, or
  the built-in. Without it, `recipe` would narrate an order without
  ever saying whose, and a mid-day edit would be invisible to the
  surface whose whole job is explaining itself. The route resolves it
  (`waymark10.feed-recipe/for-reader`); this function is handed the
  answer, exactly as it is handed the recipe."
  [eng recipe {:keys [principal visibility offset preview explain?
                      recipe-source]}]
  (let [day (today eng recipe)
        pid (:id principal)
        seed (seed-of recipe pid day)
        ctx (cond-> {:eng eng :principal principal :visibility visibility
                     :now ((:now-fn eng)) :seed seed :day day
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
                  ;; an explained read stays explained page after page:
                  ;; a `links.next` that dropped the parameter would
                  ;; hand a reader who asked why an archive that would
                  ;; not say
                  (when explain? (if preview "&explain=1" "?explain=1")))
        ;; the narrated recipe, with the read's own counts folded in —
        ;; the static half is a pure function of the recipe and the
        ;; counts are what THIS read saw each line offered
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
                     (str "One order, seeded by (" (or of "you") ", " day
                          ") — stable until midnight and stored nowhere. Two"
                          " members read two different feeds on the same day.")
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
                                   (when preview
                                     (str " · PREVIEW of " of " · read by "
                                          by)))
                     :sections sections
                     :recipe recipe-doc
                     :notes notes}
              preview (assoc :preview preview)
              (and bottomless more?)
              (assoc :links
                     {:next {:href (str base
                                        (if (str/includes? base "?") "&" "?")
                                        "cursor="
                                        (encode-cursor
                                         {:day day :seed seed
                                          :offset next-offset}))}})))
           "cards" cards)))
