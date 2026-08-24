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
  `heavier` — waymark-iqa.3's half, empty here — can never reveal a
  door the grant conceals; it will only ever name doors the reader
  already holds, on a screen where they do not fit.

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
  (:require [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.nio.charset StandardCharsets)
           (java.time Instant LocalDate ZoneId)
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
  landing order rather than a disagreement: `:ticklers` (waymark-iqa.4),
  `:insights` (.6) and the fuel/memory populations (.5) name kinds and
  queries that do not exist yet, and check (1) below refuses a recipe
  naming a population the registry does not hold. Each of those beads
  adds its population AND its line here, together, which is exactly
  the seam fork (a) promised: swapping one entry changes no other
  line."
  {:salt "waymark-feed"
   :zone "UTC"
   :order
   [{:section :do_now  :population :next_actions :take 5}
    {:section :decide  :population :asks         :take 3}
    {:section :decide  :population :letters      :take 3}
    {:section :decide  :population :conflicts    :take 2}
    {:seam true :sentence "That's the house, caught up."}
    {:section :archive :population :events :take 6 :bottomless true}]})

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

  waymark-iqa.5 brings the populations that make the section fuel
  proper (cleared queues, streaks, finished-project photos). This one
  is the interleave those will sit beside, and it is here in .2
  because a bottomless section is what gives the cursor something to
  walk."
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

(def populations
  "Every population this engine can name, and the whole of what a
  recipe may ask for. A closed map, never a classpath scan, for the
  reason `waymark10.modules` gives at length: a discovered feed would
  serve a different order in a dev REPL than in a container with one
  extra jar on the path, silently.

  A population is `(fn [ctx])` over {:eng :principal :visibility :now
  :seed :day}, answering CANDIDATES — `{:kind :id}` maps, optionally
  carrying the raw `:row` it already read and the `:at` its card
  should show. It may answer a bare vector, or {:candidates […]
  :reached-cap bool} when it scanned to a cap and the document should
  say so. It never renders, never projects and never sorts: the mixer
  does all three, once, so the fourth law is enforced in one place.

  Later beads extend it — .4 `:ticklers`, .5 the fuel and memory
  populations, .6 `:insights` — each adding its entry HERE and its
  line in `default-recipe`, together."
  {:next_actions next-actions
   :asks asks
   :letters letters
   :conflicts conflicts
   :events events})

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

  The order of the two gates is the security property. `:row?` first:
  a row this grant does not confer is not narrowed, it is ABSENT, the
  same concealment the router answers a scoped collection with. Then
  `envelope-summary` with `:visibility`, which drops every ungranted
  action from `actions` and `unavailable` alike. Only what survives
  both is a card, and `heavier` (waymark-iqa.3's half, empty here) will
  be split off AFTER that — never before, or it would name doors the
  projection just concealed.

  A candidate whose row has vanished between the population's scan and
  this read is simply no card: the feed is a read, and a read that
  404'd because a row retired mid-page would make the whole day's order
  a coin flip.

  Two keys of the summary are dropped and neither is a projection:
  `waymark`, because a card is an element of a document rather than a
  document, and `unavailable`, because a card has no room for the
  narration of doors that are shut. What a reader HOLDS is never
  dropped — that is `actions` plus (from .3) `heavier`, and the whole
  point of `heavier` is that a card does not lie about a door it has."
  [ctx section population {:keys [kind id row at]}]
  (let [rdef (get (resources ctx) kind)
        vis (:visibility ctx)]
    (when (and rdef (or (nil? vis) ((:row? vis) kind id)))
      (when-some [decoded (if row (inv/decode-row rdef row)
                              (load-decoded ctx rdef id))]
        (let [body (dissoc (render/envelope-summary rdef decoded (ctx-opts ctx))
                           "waymark" "unavailable")]
          (cond-> (assoc body
                         "card_id" (card-id section kind id)
                         "section" (name section)
                         "population" (name population)
                         ;; .3 partitions the row's own verbs on
                         ;; (demand/heavier? effort "selection") and
                         ;; fills this; until then a card offers what
                         ;; the projection left it and claims nothing
                         ;; is elsewhere
                         "heavier" [])
            at (assoc "at" (str at))))))))

(defn- offers-something?
  "Does this card put a verb under the thumb? do-now's own filter: a
  next action with no available action is a row on a list."
  [c]
  (seq (get c "actions")))

;; ── the mixer ───────────────────────────────────────────────────────

(defn- entry-cards
  "One recipe entry's contribution: its population's candidates, minus
  anything a section above already carded, seeded, offset-walked for a
  bottomless section, and rendered LAZILY — `take` short-circuits the
  `keep`, so a page of six costs six or seven envelope renders and not
  one per candidate.

  → {:cards [...] :claimed #{[kind id] …} :more? bool :reached-cap
  bool}. `:more?` is the remainder question answered the way
  `history/row-history` answers it: render one past the page rather
  than counting the whole set.

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
        page (if (false? render?)
               []
               (into []
                     (comp (keep (fn [cand]
                                   (when-some [c (card ctx section population
                                                       cand)]
                                     (when (keep? c) c))))
                           (take (inc n)))
                     ordered))]
    {:cards (vec (take n page))
     :claimed claimed
     :more? (> (count page) n)
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
                     (update :walked + (count (:cards got))))))))
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
                       (str "The archive folded the newest " log-scan-cap
                            " transitions only; older ones are unread. A"
                            " population that outgrows its cap earns a job of"
                            " its own, and only it."))])]
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
