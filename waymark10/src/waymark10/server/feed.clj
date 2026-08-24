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

  It is deliberately SHORTER than the illustrative recipe in
  docs/spec-feed.md § 'The recipe is static data', and that is the
  landing order rather than a disagreement: `:insights` (.6) names a
  kind that does not exist yet, and check (1) below refuses a recipe
  naming a population the registry does not hold. Each bead adds its
  population AND its line here, together, which is exactly the seam
  fork (a) promised: swapping one entry changes no other line —
  `:ticklers` (waymark-iqa.4) walked it first, and the three fuel
  entries and `:memories` (waymark-iqa.5) walked it again.

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
  kinds' OPEN rows and the seed picks; a context-aware do-now waits
  for the spine the epic parks in its v2 lot."
  [ctx levels]
  (->> (resources ctx)
       (keep (fn [[k rdef]] (when (contains? levels (:nav rdef :primary)) k)))
       sort
       vec))

(defn next-actions
  "do-now: open rows of the front-door kinds, seeded. The card builder
  keeps only those whose projected envelope actually OFFERS something —
  a next action with no verb is a row, not an action — so a reader
  whose grant confers sight but no doors gets a shorter do-now rather
  than a page of dead ends."
  [ctx]
  (into []
        (mapcat (fn [k]
                  (let [rdef (get (resources ctx) k)]
                    (candidates-of k (filterv #(open? rdef %)
                                              (rows-of ctx k {}))))))
        (nav-kinds ctx #{:primary})))

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
  addressed, and `data.to` is the address."
  [ctx]
  (let [pid (:id (:principal ctx))]
    (if-not (get (resources ctx) :letter)
      []
      (candidates-of :letter (rows-of ctx :letter {:to pid :state "waiting"})))))

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

(def set-aside-status
  "The mirror convention's own word for finished work. `task`'s
  docstring states the rule this reads — *'every source's own states
  normalize to :status open|done|dropped; the machine here is the sync
  machine'* — so a mirrored row's DOMAIN state is data and its
  framework state is the authority's freshness. A tickler over such a
  row must ask the data, or it would call a done task set-aside
  forever because its sync state says `fresh`."
  "done")

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
  - the row carries the mirror's `status` and it says `done`.

  Everything else is still set aside, and the deliberate consequence
  is worth saying: a dropped task somebody REOPENED and left open
  keeps its tickler, because 'still not done' is exactly what a
  someday/maybe list is for. The household's own way to say
  otherwise is the `take_it_back` verdict, which is a person
  answering rather than the engine inferring.

  It never asks the reader's GRANT. The marker is its own row with
  its own visibility and `card` projects it; whether this reader may
  see the SUBJECT is a different question from whether the subject is
  still waiting, and conflating them would make one household's
  someday list flicker according to who was holding the phone."
  [ctx kind id]
  (boolean
   (when-some [rdef (and kind (get (resources ctx) kind))]
     (when-some [raw (load-raw ctx kind id)]
       (and (open? rdef raw)
            (not= set-aside-status (str (get-in raw [:data :status]))))))))

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
             (nav-kinds ctx #{:primary :secondary}))))

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
  "The kind's most recently moved terminal row, or nil. `:updated_at`
  is one of the two engine columns `store/sortable-timestamps` admits,
  so this is an ordered LIMIT 1 rather than a scan."
  [ctx kind]
  (first (in-states ctx kind (terminal-names (get (resources ctx) kind))
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
                               (seq (terminal-names rdef))
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

  It reads the wider kind list rather than `whole-kinds`, and that is
  the aggregate/row line: this card claims nothing about rows the
  reader cannot see, so `card`'s `:row?` gate is the only one it
  needs."
  [ctx]
  (let [cutoff (.minus (day-start ctx) (long fuel-window-days) ChronoUnit/DAYS)]
    (into []
          (keep (fn [k]
                  (when-some [row (last-finished ctx k)]
                    (when (some-> ^Instant (:updated-at row) (.isAfter cutoff))
                      {:kind k :id (:id row) :row row :at (:updated-at row)}))))
          (nav-kinds ctx #{:primary :secondary}))))

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
  show and the `:sentence` the card says on its own behalf. It may
  answer a bare vector, or {:candidates […] :reached-cap bool} when it
  scanned to a cap and the document should say so. It never renders,
  never projects and never sorts: the mixer does all three, once, so
  the fourth law is enforced in one place.

  Later beads extend it — .6 `:insights` — each adding its entry HERE
  and its line in `default-recipe`, together, the way `:ticklers`
  (waymark-iqa.4) and the fuel populations (waymark-iqa.5) already
  did."
  {:next_actions next-actions
   :asks asks
   :letters letters
   :ticklers ticklers
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
                {:entry e})))
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
  {:principal (:principal ctx)
   :now (:now ctx)
   :services (:services (:eng ctx))
   :visibility (:visibility ctx)
   :resources (resources ctx)})

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
  envelopes — and contribute nothing but their claim."
  [ctx {:keys [section population take* offset bottomless render?]} seen]
  (let [out ((get populations population) ctx)
        {:keys [candidates reached-cap]} (if (map? out)
                                           out
                                           {:candidates out})
        seed (:seed ctx)
        ordered (->> candidates
                     (remove #(contains? seen [(:kind %) (:id %)]))
                     (sort-by #(rank seed (card-id section (:kind %) (:id %)))))
        claimed (into #{} (map (juxt :kind :id)) ordered)
        ordered (cond->> ordered bottomless (drop (long (or offset 0))))
        keep? (if (= :do_now section) offers-something? (constantly true))
        n (long take*)
        ;; [candidate-index card] pairs, one past the page: the index
        ;; is what the cursor advances by and the card is what the
        ;; page shows
        taken (if (false? render?)
                []
                (into []
                      (comp (keep-indexed
                             (fn [i cand]
                               (when-some [c (card ctx section population cand)]
                                 (when (keep? c) [(long i) c]))))
                            (take (inc n)))
                      ordered))
        page (mapv second (take n taken))]
    {:cards page
     :claimed claimed
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
  disguise."
  [eng recipe {:keys [principal visibility offset]}]
  (let [day (today eng recipe)
        pid (:id principal)
        seed (seed-of recipe pid day)
        ctx {:eng eng :principal principal :visibility visibility
             :now ((:now-fn eng)) :seed seed :day day}
        archive-only? (some? offset)
        {:keys [cards more? capped walked]}
        (reduce
         (fn [acc e]
           (cond
             ;; a cursor page is the bottomless section ALONE: the
             ;; seam happens once, above, on the day's first read
             (:seam e) (cond-> acc
                         (not archive-only?)
                         (update :cards conj
                                 (seam-card
                                  (count (:cards acc))
                                  (:sentence e
                                             "That's the house, caught up."))))
             :else
             (let [got (entry-cards ctx
                                    {:section (:section e)
                                     :population (:population e)
                                     :take* (:take e)
                                     :offset (when (:bottomless e) offset)
                                     :bottomless (:bottomless e)
                                     :render? (or (not archive-only?)
                                                  (boolean (:bottomless e)))}
                                    (:seen acc))]
               (cond-> (-> acc
                           (update :cards into (:cards got))
                           (update :seen into (:claimed got))
                           (update :capped #(or % (:reached-cap got))))
                 (:bottomless e)
                 (-> (assoc :more? (:more? got))
                     (update :walked + (long (:consumed got 0))))))))
         {:cards [] :seen #{} :more? false :capped false :walked 0}
         (:order recipe))
        bottomless (some :bottomless (:order recipe))
        next-offset (+ (long (or offset 0)) (long walked))
        sections (into [] (distinct) (map #(get % "section") cards))
        notes (into []
                    (remove nil?)
                    [(str "One order, seeded by (you, " day ") — stable until"
                          " midnight and stored nowhere. Two members read two"
                          " different feeds on the same day.")
                     (when visibility
                       (str "Read through your grant: a row your leash does not"
                            " confer is ABSENT here, never narrowed and never"
                            " refused — one endpoint, per-member worlds."))
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
                     :self "/api/-/feed"
                     :day day
                     :seed seed
                     :summary (str "Feed · " day " · " (count cards)
                                   " card" (when (not= 1 (count cards)) "s"))
                     :sections sections
                     :notes notes}
              (and bottomless more?)
              (assoc :links
                     {:next {:href (str "/api/-/feed?cursor="
                                        (encode-cursor
                                         {:day day :seed seed
                                          :offset next-offset}))}})))
           "cards" cards)))
