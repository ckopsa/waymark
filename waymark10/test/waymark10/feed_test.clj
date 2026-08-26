(ns waymark10.feed-test
  "The feed (waymark-iqa.2, docs/spec-feed.md): one sequential read of
  the house, mixed by a declared recipe, seeded by (member, day), and
  projected through the reader's own grant.

  The conformance pack proves the door's promises against whatever an
  application declared. What belongs HERE is what a driver with one
  world cannot arrange: two kinds and two principals built to disagree,
  a recipe handed to the engine as an opt, and the assembly checks read
  one at a time so a red run names WHICH check spoke.

  The in-memory twin hosts it. Every read the mixer makes goes through
  the storage protocol — `query-rows`, `transitions`, `load-row` — and
  nothing else, so the twin is not a convenience: it is the proof that
  the surface is portable."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.feed :as feed]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.wire :as wire]))

;; ── the world: two kinds nobody's leash covers at once ──────────────

(defn- kind-of [k plural]
  (r/resource
   {:kind k
    :plural plural
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :display {:title "{data.title}"}
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:note {:optional true :x-display {:widget "prose"}}
              [:string {:max 400}]]]
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Done is done."}}
     ;; a selection — choosing, not typing. It STAYS under the thumb.
     :prioritize {:from #{:open} :to :open
                  :input [:map [:rank [:enum "high" "low"]]]
                  :display {:label "Prioritize"}
                  :safety {:idempotent false :reversible true :confirm false}}
     ;; a composition — prose, a keyboard and a way back. It is a
     ;; SCREEN, and the card says so rather than dropping it
     :annotate {:from #{:open} :to :open
                :input [:map [:note {:x-display {:widget "prose"}}
                              [:string {:max 400}]]]
                :edit {:prefill [:note]}
                :safety {:idempotent false :reversible true :confirm false}}}}))

(def ^:private errand (kind-of :fd_errand "fd_errands"))
(def ^:private parcel (kind-of :fd_parcel "fd_parcels"))

(defn- boot [& [opts]]
  (engine/engine (merge {:storage (memory/storage)
                         ;; the capability registry rides the app's own
                         ;; resources (:app-opt-in), and feed.preview_as
                         ;; is the one capability THIS engine enforces
                         :resources [errand parcel caps/capability]}
                        opts)))

(defn- call!
  [eng method uri & {:keys [body query headers]}]
  (let [resp ((engine/handler eng)
              (cond-> {:request-method method :uri uri
                       :headers (merge {"x-waymark-principal" "mom"
                                        "content-type" "application/json"}
                                       headers)}
                query (assoc :query-string query)
                body (assoc :body (wire/write-json body))))]
    (assoc resp :doc (some-> (:body resp) wire/read-json))))

(defn- make! [eng kind title & [headers]]
  (let [plural (if (= :fd_errand kind) "fd_errands" "fd_parcels")]
    (get-in (call! eng :post (str "/api/" plural) :body {:title title}
                   :headers headers)
            [:doc :self])))

(defn- feed! [eng & {:keys [headers query]}]
  (call! eng :get "/api/-/feed" :headers headers :query query))

(defn- card-ids [doc] (mapv :card_id (:cards doc)))
(defn- rows-of [doc] (into [] (comp (remove #(= "seam" (:card_id %)))
                                    (map (comp str :kind)))
                           (:cards doc)))

;; ── the seed ────────────────────────────────────────────────────────

(deftest the-seed-is-the-whole-of-stable-within-a-day
  (let [recipe feed/default-recipe]
    (testing "same member, same day, same seed — and nothing stored"
      (is (= (feed/seed-of recipe "mom" "2026-08-24")
             (feed/seed-of recipe "mom" "2026-08-24"))))
    (testing "two members on one day read two different worlds"
      (is (not= (feed/seed-of recipe "mom" "2026-08-24")
                (feed/seed-of recipe "dad" "2026-08-24"))))
    (testing "midnight rolls it"
      (is (not= (feed/seed-of recipe "mom" "2026-08-24")
                (feed/seed-of recipe "mom" "2026-08-25"))))
    (testing "and the salt is the recipe's, so two engines need not agree"
      (is (not= (feed/seed-of recipe "mom" "2026-08-24")
                (feed/seed-of (assoc recipe :salt "other") "mom"
                              "2026-08-24"))))))

(deftest the-day-is-read-in-the-recipes-own-zone
  (let [eng (boot {:now-fn (constantly (java.time.Instant/parse
                                        "2026-08-24T23:30:00Z"))})]
    (is (= "2026-08-24" (feed/today eng feed/default-recipe)))
    (testing "an hour east it is already tomorrow — midnight is the zone's"
      (is (= "2026-08-25" (feed/today eng (assoc feed/default-recipe
                                                 :zone "Europe/Berlin")))))))

;; ── the recipe's four assembly checks ───────────────────────────────

(defn- refused [order]
  (try (feed/check-recipe! (assoc feed/default-recipe :order order))
       nil
       (catch clojure.lang.ExceptionInfo e
         (when (:waymark10/definition-error (ex-data e)) (ex-message e)))))

(deftest a-bad-recipe-refuses-at-assembly-and-names-the-fix
  (testing "1 — a population the registry does not hold"
    (is (str/includes? (str (refused [{:section :do_now :population :telepathy
                                       :take 1}
                                      {:seam true}]))
                       "no population named :telepathy")))
  (testing "2 — no seam, and two seams"
    (is (str/includes? (str (refused [{:section :do_now
                                       :population :next_actions :take 1}]))
                       "exactly one entry carries :seam true"))
    (is (str/includes? (str (refused [{:section :do_now
                                       :population :next_actions :take 1}
                                      {:seam true} {:seam true}]))
                       "exactly one entry carries :seam true")))
  (testing "3 — a bottomless section that is not last"
    (is (str/includes? (str (refused [{:section :archive :population :events
                                       :take 1 :bottomless true}
                                      {:seam true}]))
                       "is LAST")))
  (testing "4 — the census is law, not a preference"
    (is (str/includes? (str (refused [{:section :fuel :population :events
                                       :take 1}
                                      {:section :do_now
                                       :population :next_actions :take 1}
                                      {:seam true}]))
                       "census is law"))
    (is (str/includes? (str (refused [{:section :nowhere :population :events
                                       :take 1}
                                      {:seam true}]))
                       "no section named")))
  (testing "and a page size that is not a page"
    (is (str/includes? (str (refused [{:section :do_now
                                       :population :next_actions}
                                      {:seam true}]))
                       "positive :take")))
  (testing "the default recipe passes its own checks"
    (is (= feed/default-recipe (feed/check-recipe! feed/default-recipe)))))

(deftest the-recipe-is-an-engine-opt-read-at-the-build-site
  (testing "a recipe the engine carries is the one the door serves"
    (let [eng (boot {:feed (assoc feed/default-recipe
                                  :order [{:seam true
                                           :sentence "Nothing doing."}])})]
      (make! eng :fd_errand "Call the dentist")
      (let [doc (:doc (feed! eng))]
        (is (= ["seam"] (card-ids doc)))
        (is (= "Nothing doing." (:sentence (first (:cards doc))))))))
  (testing "and a bad one refuses the ASSEMBLY, not the request"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"feed recipe"
         (engine/handler (boot {:feed (assoc feed/default-recipe
                                             :order [{:section :do_now
                                                      :population :telepathy
                                                      :take 1}
                                                     {:seam true}])}))))))

;; ── the document ────────────────────────────────────────────────────

(deftest the-feed-is-a-document-that-can-name-itself
  (let [eng (boot)]
    (make! eng :fd_errand "Call the dentist")
    (let [{:keys [status doc]} (feed! eng)]
      (is (= 200 status))
      (is (= "feed" (:kind doc))
          "fork (c) forks a render() on document kind; a document that
           cannot name itself cannot be forked on")
      (is (= "/api/-/feed" (:self doc)))
      (is (= (feed/today eng feed/default-recipe) (:day doc)))
      (is (string? (:seed doc)))
      (testing "the seam is a real element, keyed on card_id and rowless"
        (let [seam (first (filter #(= "seam" (:card_id %)) (:cards doc)))]
          (is (some? seam))
          (is (nil? (:self seam)))
          (is (= 1 (:above seam)))
          (is (= "That's the house, caught up." (:sentence seam)))))
      (testing "and a card is its row's own envelope, with four keys added"
        (let [c (first (:cards doc))]
          (is (= "do_now/fd_errand/" (subs (:card_id c) 0
                                           (count "do_now/fd_errand/"))))
          (is (= "do_now" (:section c)))
          (is (= "next_actions" (:population c)))
          (is (str/starts-with? (str (:self c)) "/api/fd_errands/"))
          (is (= "Call the dentist" (get-in c [:display :title])))
          (is (some? (get-in c [:actions :finish :href]))
              "a next action with no verb is a row on a list")
          (is (nil? (:unavailable c))
              "a card has no room for the narration of doors that are shut")
          (is (nil? (:waymark c))
              "a card is an element of a document, not a document"))))))

(deftest an-empty-population-contributes-nothing-and-the-seam-moves-up
  (let [eng (boot)
        doc (:doc (feed! eng))]
    (is (= ["seam"] (card-ids doc)))
    (is (= 0 (:above (first (:cards doc)))))
    (is (= ["seam"] (:sections doc))
        "sections names what the ANSWER carries, never the census")))

(deftest two-reads-one-day-one-order
  (let [eng (boot)]
    (doseq [t ["one" "two" "three" "four" "five" "six" "seven"]]
      (make! eng :fd_errand t))
    (let [a (:doc (feed! eng)) b (:doc (feed! eng))]
      (is (= (:seed a) (:seed b)))
      (is (= (card-ids a) (card-ids b))))
    (testing "and another member's day is another order"
      (is (not= (card-ids (:doc (feed! eng)))
                (card-ids (:doc (feed! eng :headers
                                       {"x-waymark-principal" "dad"}))))))))

;; ── the ≤-selection partition (waymark-iqa.3) ───────────────────────

(deftest a-card-offers-what-fits-under-a-thumb-and-points-at-the-rest
  (let [eng (boot)]
    (make! eng :fd_errand "Call the dentist")
    (let [c (first (:cards (:doc (feed! eng))))]
      (testing "assent and selection stay — one tap, or choosing"
        (is (= #{:finish :prioritize} (set (keys (:actions c)))))
        (is (= #{"assent" "selection"}
               (set (map :effort (vals (:actions c)))))))
      (testing "the composition becomes a heavier entry, not a silence"
        (is (= 1 (count (:heavier c))))
        (let [h (first (:heavier c))]
          (is (= "annotate" (:name h)))
          (is (= "composition" (:effort h)))
          (is (= "annotate" (:label h))
              "no declared label falls back to the humanized action name,
               render/no-admissible-entry's own spelling")))
      (testing "and it points at the ROW's screen, never the action's door"
        (let [h (first (:heavier c))]
          (is (= (str "/#" (:self c)) (:href h)))
          (is (not (str/includes? (str (:href h)) "/-/"))
              "a heavier entry is a place to GO, not a verb to fire"))))))

(deftest the-partition-reads-the-projected-map-and-nothing-else
  (testing "it is a pure function of the body it is handed — hand it a
            body with one action and one action is all it can see"
    (let [body {"self" "/api/fd_errands/1"
                "actions" {"finish" {"effort" "assent"}
                           "compose" {"effort" "composition"}}}
          out (feed/split-verbs body (get body "self"))]
      (is (= ["finish"] (keys (get out "actions"))))
      (is (= [{"name" "compose" "effort" "composition" "label" "compose"
               "href" "/#/api/fd_errands/1"}]
             (get out "heavier")))))
  (testing "a declared label is the one the card shows"
    (is (= "Add a note"
           (get-in (feed/split-verbs
                    {"actions" {"annotate" {"effort" "composition"
                                            "display" {"label" "Add a note"}}}}
                    "/api/fd_errands/1")
                   ["heavier" 0 "label"]))))
  (testing "an empty actions map partitions into two empties, never nil"
    (let [out (feed/split-verbs {"self" "/api/fd_errands/1" "actions" {}} "x")]
      (is (= {} (get out "actions")))
      (is (= [] (get out "heavier"))))))

(deftest a-card-whose-only-verb-is-heavy-is-not-a-do-now
  (testing "do-now is the one physical next action under the thumb, so a
            card that could only send you somewhere to type is not one"
    (let [only-heavy (r/resource
                      {:kind :fd_essay :plural "fd_essays"
                       :states [:open :done] :initial :open :terminal #{:done}
                       :summary "{data.title}"
                       :schema [:map [:title [:string {:min 1 :max 60}]]]
                       :actions
                       {:write {:from #{:open} :to :done
                                :input [:map
                                        [:body {:x-display {:widget "prose"}}
                                         [:string {:max 400}]]]
                                :safety {:idempotent false :reversible false
                                         :confirm false
                                         :one-way "Written is written."}}}})
          eng (engine/engine {:storage (memory/storage)
                              :resources [errand only-heavy]})]
      (call! eng :post "/api/fd_essays" :body {:title "The long one"})
      (is (empty? (filter #(= "fd_essay" (:kind %))
                          (:cards (:doc (feed! eng)))))))))

;; ── the origin convention ───────────────────────────────────────────

(deftest the-origin-key-round-trips-and-names-the-recipe-back
  (let [k (feed/origin-key "2026-08-24" "do_now/task/01HZ" "9f3c1a")]
    (is (= "feed/2026-08-24/do_now%2Ftask%2F01HZ/9f3c1a" k)
        "the card id is percent-encoded: a card_id carries slashes of its
         own, and a metric that could not tell them from the key's would
         be a metric that guessed")
    (is (= {:day "2026-08-24" :card-id "do_now/task/01HZ"
            :section "do_now" :kind "task" :id "01HZ" :nonce "9f3c1a"}
           (feed/origin-of k))
        "section, kind and id come back out of the audit trail with no join"))
  (testing "and every other key is somebody else's — nil, never a guess"
    (is (nil? (feed/origin-of nil)))
    (is (nil? (feed/origin-of "")))
    (is (nil? (feed/origin-of (str (random-uuid)))))
    (is (nil? (feed/origin-of "feed/2026-08-24/do_now%2Ftask%2F01HZ")))
    (is (nil? (feed/origin-of "deck/2026-08-24/do_now%2Ftask%2F01HZ/9f"))
        "the prefix is the whole of the claim")
    (is (nil? (feed/origin-of "feed/2026-08-24/notacardid/9f3c1a"))
        "a card id that is not section/kind/id names no card")))

(deftest a-verb-invoked-from-a-card-lands-its-origin-on-the-transition
  (let [eng (boot)
        _ (make! eng :fd_errand "Call the dentist")
        doc (:doc (feed! eng))
        c (first (:cards doc))
        id (last (str/split (str (:self c)) #"/"))
        key' (feed/origin-key (:day doc) (:card_id c) "9f3c1a")
        done (call! eng :post (get-in c [:actions :finish :href])
                    :headers {"idempotency-key" key'})]
    (is (= 200 (:status done)))
    (testing "invoke/finish! stamps a present key whether or not the
              action is idempotent — finish declares :idempotent true,
              and that is the case a new column would have been bought for"
      (let [log (store/with-tx (:storage eng)
                  (fn [tx] (store/transitions (:storage eng) tx
                                              {:kind :fd_errand
                                               :resource-id id}
                                              {})))
            moved (first (filter #(= :finish (:action %)) log))]
        (is (= key' (:idempotency-key moved)))
        (is (= "do_now" (:section (feed/origin-of (:idempotency-key moved)))))))
    (testing "and the metric reads it back — no column, no analytics table"
      (let [m (feed/actions-from-feed eng {:day (:day doc)})]
        (is (= 1 (:total m)))
        (is (= {"do_now" 1} (:by-section m)))
        (is (= {"fd_errand" 1} (:by-kind m)))
        (is (= {"fd_errand.finish" 1} (:by-action m)))
        (is (false? (:reached-cap m)))))
    (testing "a day that is not the feed's counts nothing"
      (is (= 0 (:total (feed/actions-from-feed eng {:day "1999-01-01"})))))
    (testing "and an ordinary write is not a feed action"
      (make! eng :fd_errand "not from a card")
      (is (= 1 (:total (feed/actions-from-feed eng {:day (:day doc)})))))))

;; ── the fourth law: two principals, one door ────────────────────────

(deftest a-reader-without-a-grant-never-sees-the-card
  (let [eng (boot)]
    (doseq [t ["e1" "e2" "e3"]] (make! eng :fd_errand t))
    (doseq [t ["p1" "p2" "p3"]] (make! eng :fd_parcel t))
    (testing "unscoped, the house is both kinds"
      (is (= #{"fd_errand" "fd_parcel"} (set (rows-of (:doc (feed! eng)))))))
    (let [minted (call! eng :post "/api/grants"
                        :body {:audience "nanny"
                               :scope [{:kind "fd_errand"
                                        :actions ["finish"]}]})
          gid (last (str/split (get-in minted [:doc :self]) #"/"))
          as-nanny {"x-waymark-principal" "nanny"
                    "x-waymark-actor-type" "agent"}
          accepted (call! eng :post (str "/api/grants/" gid "/-/accept")
                          :headers as-nanny)
          scoped (feed! eng :headers (assoc as-nanny "x-waymark-grant" gid))]
      (is (= 201 (:status minted)))
      (is (= 200 (:status accepted)))
      (testing "the feed PROJECTS a scoped reader rather than refusing —
                the exit the sweep takes and this door may not"
        (is (= 200 (:status scoped))))
      (testing "and the kind the leash never named is ABSENT, not refused"
        (is (= #{"fd_errand"} (set (rows-of (:doc scoped)))))
        (is (seq (rows-of (:doc scoped)))
            "concealment that concealed everything would prove nothing"))
      (testing "the document says out loud that it was read through a grant"
        (is (some #(str/includes? % "Read through your grant")
                  (:notes (:doc scoped)))))
      (testing "and an action the leash conceals is in NEITHER list —
                the whole reason heavier is drawn from the SURVIVORS"
        (let [cards (remove #(= "seam" (:card_id %)) (:cards (:doc scoped)))]
          (is (seq cards))
          (is (every? #(= [:finish] (keys (:actions %))) cards)
              "the leash names finish and nothing else")
          (is (every? #(empty? (:heavier %)) cards)
              "annotate is a composition the unscoped reader sees in
               heavier; a concealed door may not reappear there as a
               link, or concealment has become narration"))))))

;; ── fuel and the archive (waymark-iqa.5) ────────────────────────────
;;
;; The two stateless halves: fuel between decide and the seam, the
;; archive below it. Both are READ-TIME SEEDED QUERIES over the log and
;; the rows — no job, no column, nothing stored — so what has to be
;; arranged here is TIME, which is the one thing a conformance driver
;; with one world cannot arrange either.

(defn- rewind!
  "Move part of the twin's world back in time.

  A streak is consecutive WEEKS and 'a year ago this week' is a year
  ago; neither can be lived in a test, and both stores stamp `at` and
  `updated_at` themselves rather than taking the engine's clock. So the
  memory twin's log (and, when `rows?`, its tables) is rewritten in
  place. It is the one thing in this file that reaches past a door, and
  it reaches only for the clock."
  [eng days {:keys [rows? of]}]
  (let [back (fn [t] (some-> ^java.time.Instant t
                             (.minus (long days)
                                     java.time.temporal.ChronoUnit/DAYS)))
        hit? (or of (constantly true))]
    (swap! (:state (:storage eng))
           (fn [s]
             (cond-> (update s :transitions
                             (fn [ts] (mapv #(if (hit? %) (update % :at back) %)
                                            ts)))
               rows?
               (update :tables
                       (fn [tables]
                         (reduce-kv
                          (fn [acc kind rows]
                            (assoc acc kind
                                   (reduce-kv (fn [a id row]
                                                (assoc a id
                                                       (-> row
                                                           (update :created-at back)
                                                           (update :updated-at back))))
                                              {} rows)))
                          {} tables))))))))

(defn- section-cards [doc s]
  (filterv #(= s (str (:section %))) (:cards doc)))

(deftest a-queue-that-went-to-zero-is-fuel-and-says-so
  (let [eng (boot)]
    (doseq [t ["one" "two" "three"]]
      (call! eng :post (str (make! eng :fd_errand t) "/-/finish")))
    ;; one parcel finished, one still open — a kind with work left in
    ;; it has not cleared anything, and that is what makes the other
    ;; card `finished` rather than `cleared`
    (call! eng :post (str (make! eng :fd_parcel "posted") "/-/finish"))
    (make! eng :fd_parcel "still waiting")
    (let [doc (:doc (feed! eng))
          fuel (section-cards doc "fuel")
          cleared (first (filter #(= "cleared" (str (:population %))) fuel))
          finished (first (filter #(= "finished" (str (:population %))) fuel))]
      (testing "the cleared card is the row that emptied the list, plus the
                sentence the row itself cannot say"
        (is (some? cleared))
        (is (= "fd_errand" (str (:kind cleared))))
        (is (str/includes? (str (:sentence cleared)) "Nothing is left in fd_errands"))
        (is (str/includes? (str (:sentence cleared)) "3 finished"))
        (is (str/starts-with? (str (:card_id cleared)) "fuel/fd_errand/")
            "a fuel card is a ROW card: it names its origin row and
             invents no identity"))
      (testing "a kind with work left in it clears nothing"
        (is (empty? (filter #(= "fd_parcel" (str (:kind %)))
                            (filter #(= "cleared" (str (:population %))) fuel)))))
      (testing "what the house finished this week is fuel, with no sentence —
                the row's own summary is already the sentence"
        (is (some? finished))
        (is (= "fd_parcel" (str (:kind finished))))
        (is (nil? (:sentence finished))))
      (testing "and the census holds: fuel is below decide and above the seam"
        (is (= ["do_now" "fuel" "seam" "archive"] (:sections doc)))))))

(deftest a-streak-is-weeks-in-a-row-and-two-is-the-floor
  (let [eng (boot)
        selves (mapv #(make! eng :fd_errand (str "week " %)) (range 4))]
    ;; one errand finished per week for four weeks — and one left open,
    ;; so the queue never clears and the streak card is the one under test
    (make! eng :fd_errand "the one still going")
    (doseq [[i self] (map-indexed vector selves)]
      (call! eng :post (str self "/-/finish"))
      (let [id (last (str/split self #"/"))]
        (rewind! eng (* 7 i) {:of #(= id (:resource-id %))})))
    (let [doc (:doc (feed! eng))
          streak (first (filter #(= "streaks" (str (:population %)))
                                (section-cards doc "fuel")))]
      (is (some? streak))
      (is (str/includes? (str (:sentence streak)) "4 weeks running"))
      (is (str/includes? (str (:sentence streak)) "every week since"))
      (testing "and a single week is not a run"
        (let [eng2 (boot)]
          (call! eng2 :post (str (make! eng2 :fd_errand "just the one")
                                 "/-/finish"))
          (make! eng2 :fd_errand "and one open")
          (is (empty? (filter #(= "streaks" (str (:population %)))
                              (:cards (:doc (feed! eng2)))))))))))

(deftest a-year-ago-this-week-is-an-archive-card-that-quotes-the-day
  (let [eng (boot)]
    (call! eng :post (str (make! eng :fd_errand "Repaint the porch") "/-/finish"))
    ;; 52 weeks, so the weekday lines up and the fold lands in the
    ;; anniversary week rather than beside it
    (rewind! eng 364 {:rows? true})
    (let [doc (:doc (feed! eng))
          memory (first (section-cards doc "archive"))]
      (is (some? memory))
      (is (= "memories" (str (:population memory))))
      (is (str/starts-with? (str (:sentence memory)) "A year ago this week: ")
          "the transition's own stored summary — what invoke rendered on
           the day, never a re-render of today's row against yesterday's
           law")
      (is (str/includes? (str (:sentence memory)) "Repaint the porch"))
      (testing "and a row whose work is a year old is nobody's fuel"
        (is (empty? (section-cards doc "fuel")))))))

(deftest the-archive-pages-deep-without-serving-a-card-twice
  (testing "a concealed candidate consumes the cursor's offset without
            producing a card — advance by the CARDS and page two
            re-serves the tail of page one"
    (let [eng (boot)]
      (dotimes [i 8]
        (call! eng :post (str (make! eng :fd_errand (str "errand " i))
                              "/-/finish"))
        (call! eng :post (str (make! eng :fd_parcel (str "parcel " i))
                              "/-/finish")))
      (let [minted (call! eng :post "/api/grants"
                          :body {:audience "nanny"
                                 :scope [{:kind "fd_errand" :actions []}]})
            gid (last (str/split (get-in minted [:doc :self]) #"/"))
            as-nanny {"x-waymark-principal" "nanny"
                      "x-waymark-actor-type" "agent"
                      "x-waymark-grant" gid}]
        (call! eng :post (str "/api/grants/" gid "/-/accept")
               :headers {"x-waymark-principal" "nanny"
                         "x-waymark-actor-type" "agent"})
        (let [pages (loop [q nil acc []]
                      (let [doc (:doc (feed! eng :headers as-nanny :query q))
                            acc (conj acc doc)
                            href (get-in doc [:links :next :href])]
                        (if (or (nil? href) (> (count acc) 10))
                          acc
                          (recur (second (str/split (str href) #"\?" 2)) acc))))
              archive (mapcat #(section-cards % "archive") pages)]
          (is (< 1 (count pages)) "one page proves nothing about paging")
          (is (= (count archive) (count (set (map :card_id archive))))
              "no card_id repeats within a day, however deep the walk goes")
          (is (= #{"fd_errand"} (set (map (comp str :kind) archive)))
              "the parcels are concealed, and a concealed candidate is
               absent rather than a hole in the page")
          (is (= 7 (count archive))
              "eight finished errands, one of them claimed by the fuel
               section's cleared card")
          (is (nil? (get-in (last pages) [:links :next]))
              "the tail is honest: the walk runs out and says so"))))))

;; ── what the first real read found (waymark-iqa.24, .15, .25) ───────
;;
;; Four corrections, and every one of them was invisible to a smoke
;; test because a smoke test has three rows in it. What a household
;; has is a HUNDRED — lopsided by kind, half of it finished, some of
;; it let go — and the shapes below are that world in miniature.

(def ^:private chore-run
  "A kind whose two endings are NOT terminal, because both have an
  honest way back — choreplan10's own shape. The machine reads a run
  skipped a fortnight ago as work still waiting; `:over` is the
  household saying which states are endings and what they meant."
  (r/resource
   {:kind :fd_run :plural "fd_runs"
    :states [:due :done :skipped] :initial :due
    :over {:accomplished #{:done} :let-go #{:skipped}}
    :summary "{data.title} · {state}"
    :display {:title "{data.title}"}
    :schema [:map [:title [:string {:min 1 :max 60}]]]
    :actions {:complete {:from #{:due} :to :done :undo :reopen
                         :safety {:idempotent true :reversible true
                                  :confirm false}}
              :skip {:from #{:due} :to :skipped :undo :unskip
                     :safety {:idempotent true :reversible true
                              :confirm false}}
              :reopen {:from #{:done} :to :due :undo :complete
                       :safety {:idempotent true :reversible true
                                :confirm false}}
              :unskip {:from #{:skipped} :to :due :undo :skip
                       :safety {:idempotent true :reversible true
                                :confirm false}}}}))

(def ^:private queued
  "A MIRROR-shaped kind: the machine is the sync machine (nothing is
  terminal, ever) and the lifecycle is a field. `:over` says where the
  words live and what they mean, so the framework holds no
  application's enum."
  (r/resource
   {:kind :fd_queued :plural "fd_queueds"
    :states [:fresh] :initial :fresh
    :over {:field :status :accomplished #{"finished"} :let-go #{"abandoned"}}
    :summary "{data.title} · {data.status}"
    :display {:title "{data.title}"}
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:status {:optional true}
              [:maybe [:enum "queued" "active" "finished" "abandoned"]]]]
    :actions {:finish {:from #{:fresh} :to :fresh
                       :safety {:idempotent true :reversible false
                                :confirm false :one-way "Finished."}
                       :handler (fn [row _ _] (assoc-in row [:data :status]
                                                        "finished"))}
              :abandon {:from #{:fresh} :to :fresh
                        :safety {:idempotent true :reversible false
                                 :confirm false :one-way "Let go."}
                        :handler (fn [row _ _] (assoc-in row [:data :status]
                                                         "abandoned"))}}}))

(def ^:private listing
  "A kind with two TERMINAL endings the machine cannot tell apart: the
  trip taken and the list nobody used."
  (r/resource
   {:kind :fd_list :plural "fd_lists"
    :states [:draft :done :discarded] :initial :draft
    :terminal #{:done :discarded}
    :over {:accomplished #{:done} :let-go #{:discarded}}
    :summary "{data.title} · {state}"
    :display {:title "{data.title}"}
    :schema [:map [:title [:string {:min 1 :max 60}]]]
    :actions {:finish {:from #{:draft} :to :done
                       :safety {:idempotent true :reversible false
                                :confirm false :one-way "Done is done."}}
              :discard {:from #{:draft} :to :discarded
                        :safety {:idempotent true :reversible false
                                 :confirm false
                                 :one-way "The list is let go."}}}}))

(defn- boot-house [& [opts]]
  (engine/engine (merge {:storage (memory/storage)
                         :resources [errand parcel chore-run queued listing
                                     caps/capability]}
                        opts)))

(defn- post! [eng plural body]
  (get-in (call! eng :post (str "/api/" plural) :body body) [:doc :self]))

(defn- kinds-in [doc section]
  (into [] (comp (filter #(= section (str (:section %))))
                 (map (comp str :kind)))
        (:cards doc)))

(deftest do-now-spreads-across-the-kinds-instead-of-going-by-volume
  (testing "thirty of one kind and two of another: the seeded draw was
            a lottery weighted by row count, so the small kind lost
            every morning. The lane is composition, not a score —
            every kind's first row is considered before any kind's
            second"
    (let [eng (boot-house)]
      (dotimes [i 30] (post! eng "fd_errands" {:title (str "errand " i)}))
      (dotimes [i 2] (post! eng "fd_parcels" {:title (str "parcel " i)}))
      (let [do-now (kinds-in (:doc (feed! eng)) "do_now")]
        (is (= 5 (count do-now)))
        (is (some #{"fd_parcel"} do-now)
            "the two-row kind reaches the five slots beside the thirty-row one")
        (is (some #{"fd_errand"} do-now))
        (is (>= 2 (count (filter #{"fd_parcel"} do-now)))
            "and it takes no more than it has")))))

(deftest a-recipe-line-may-be-dedicated-to-particular-kinds
  (let [recipe (assoc feed/default-recipe
                      :order [{:section :do_now :population :next_actions
                               :take 2 :kinds [:fd_parcel]}
                              {:section :do_now :population :next_actions
                               :take 3}
                              {:seam true :sentence "That's the house, caught up."}])
        eng (boot-house {:feed recipe})]
    (dotimes [i 20] (post! eng "fd_errands" {:title (str "errand " i)}))
    (dotimes [i 4] (post! eng "fd_parcels" {:title (str "parcel " i)}))
    (let [do-now (kinds-in (:doc (feed! eng)) "do_now")]
      (is (= 5 (count do-now)))
      (is (= 2 (count (filter #{"fd_parcel"} do-now)))
          "the dedicated line is the household saying so in static data —
           two of the five slots are the parcels' before anything else is
           considered")
      (is (= 3 (count (filter #{"fd_errand"} do-now))))
      (testing "and no row is served twice: the first line CLAIMS every
                parcel, shown or not, so the general line has none left"
        (let [ids (mapv :card_id (:cards (:doc (feed! eng))))]
          (is (= (count ids) (count (set ids)))))))
    (testing "a :kinds that is not a vector of kinds refuses at assembly"
      (is (str/includes?
           (str (refused [{:section :do_now :population :next_actions
                           :take 1 :kinds "task"}
                          {:seam true}]))
           ":kinds is a non-empty vector")))))

(deftest work-that-is-over-is-not-a-next-action-however-it-ended
  (let [eng (boot-house)]
    (call! eng :post (str (post! eng "fd_runs" {:title "Bins, last Tuesday"})
                          "/-/skip"))
    (call! eng :post (str (post! eng "fd_runs" {:title "Dishes, Monday"})
                          "/-/complete"))
    (post! eng "fd_runs" {:title "Sweep the porch"})
    (let [doc (:doc (feed! eng))
          do-now (filterv #(= "do_now" (str (:section %))) (:cards doc))]
      (testing "the run still due is the morning's; the skipped and the
                done ones are history, undo doors or not"
        (is (= 1 (count do-now)))
        (is (= "Sweep the porch" (get-in (first do-now) [:display :title]))))
      (testing "and what is over is where history lives: the turn taken
                is this week's fuel, the turn let go is a memory"
        (is (= ["fd_run"] (kinds-in doc "fuel")))
        (is (= "Dishes, Monday"
               (get-in (first (section-cards doc "fuel")) [:display :title])))
        (is (= "Bins, last Tuesday"
               (get-in (first (section-cards doc "archive"))
                       [:display :title])))))))

(deftest a-mirror-shaped-kind-keeps-its-endings-in-its-own-data
  (let [eng (boot-house)]
    (call! eng :post (str (post! eng "fd_queueds" {:title "The long film"
                                                   :status "active"})
                          "/-/finish"))
    (post! eng "fd_queueds" {:title "The one we are watching"
                             :status "active"})
    (let [doc (:doc (feed! eng))]
      (testing "the sync machine says fresh for both; the STATUS is what
                says the work is over, and the kind declares the word"
        (is (= ["fd_queued"] (kinds-in doc "do_now")))
        (is (= "The one we are watching"
               (get-in (first (:cards doc)) [:display :title]))))
      (testing "and the finished one reaches the archive — the epic's own
                photo of the thing you finished, which the do-now claim
                used to swallow before anybody saw it"
        (is (= ["fd_queued"] (kinds-in doc "archive")))))))

(deftest fuel-is-deeds-and-a-list-nobody-used-is-not-one
  (let [eng (boot-house)]
    (call! eng :post (str (post! eng "fd_lists" {:title "Saturday's shop"})
                          "/-/discard"))
    (post! eng "fd_lists" {:title "Next Saturday"})
    (let [doc (:doc (feed! eng))
          fuel (section-cards doc "fuel")]
      (is (empty? (filter #(= "fd_list" (str (:kind %))) fuel))
          "terminal was the old question and it was one word too wide")
      (testing "and it is still remembered — the archive keeps what the
                house let go, it simply does not congratulate anybody"
        (is (some #(= "fd_list" (str (:kind %))) (section-cards doc "archive")))))
    (testing "the trip actually taken IS a deed"
      (let [eng2 (boot-house)]
        (call! eng2 :post (str (post! eng2 "fd_lists" {:title "Sunday's shop"})
                               "/-/finish"))
        (post! eng2 "fd_lists" {:title "Still a draft"})
        (is (some #(= "fd_list" (str (:kind %)))
                  (section-cards (:doc (feed! eng2)) "fuel")))))))

(deftest the-archive-never-cards-a-row-that-is-still-live
  (testing "memories match on what MOVED, and a row that moved and is
            still open is not a memory — four shows the household is
            halfway through carded as history, wearing their verbs"
    (let [eng (boot-house)]
      ;; a row that moves twice and stays open: the log has it, and
      ;; the do-now claim is not what keeps it out of the archive —
      ;; the archive asks the row where it stands NOW
      (dotimes [i 8]
        (let [self (post! eng "fd_queueds" {:title (str "show " i)
                                            :status "queued"})]
          (call! eng :post (str self "/-/finish"))))
      (dotimes [i 3]
        (post! eng "fd_queueds" {:title (str "watching " i)
                                 :status "active"}))
      (let [doc (:doc (feed! eng))
            archive (section-cards doc "archive")]
        (is (seq archive) "an empty archive would prove nothing")
        (is (every? #(= "finished" (get-in % [:fields :status])) archive)
            "every card below the seam is a row whose work is over")
        (is (not-any? #(= "active" (get-in % [:fields :status])) archive)
            "and never one the house is halfway through")
        (is (= 3 (count (section-cards doc "do_now")))
            "the live ones are where a live row belongs")))))

;; ── the cursor ──────────────────────────────────────────────────────

(deftest the-cursor-serves-the-archive-and-rolls-at-midnight
  (let [eng (boot)]
    ;; ten finished parcels: an open row is do-now's, and a row an
    ;; earlier section claims is never a memory
    (dotimes [i 10]
      (let [self (make! eng :fd_parcel (str "parcel " i))]
        (call! eng :post (str self "/-/finish"))))
    (let [p1 (:doc (feed! eng))
          href (get-in p1 [:links :next :href])
          p2 (:doc (feed! eng :query (second (str/split (str href) #"\?" 2))))]
      (testing "the first read is the whole feed and the first archive page"
        (is (= 6 (count (filter #(= "archive" (:section %)) (:cards p1)))))
        (is (some? href)))
      (testing "following it answers ARCHIVE ONLY — the seam happens once"
        (is (= #{"archive"} (set (map :section (:cards p2)))))
        (is (empty? (filter #(= "seam" (:card_id %)) (:cards p2)))))
      (testing "and no card is served twice within a day"
        (let [all (concat (card-ids p1) (card-ids p2))]
          (is (= (count all) (count (set all))))))
      (testing "the tail is honest: no links.next when the walk runs out"
        (is (nil? (get-in p2 [:links :next])))))
    (testing "a cursor from another day is refused, and the sentence says why"
      (let [stale (feed/encode-cursor {:day "1999-01-01" :seed "x" :offset 0})
            r (feed! eng :query (str "cursor=" stale))]
        (is (= 409 (:status r)))
        (is (str/includes? (str (get-in r [:doc :detail])) "rolls at midnight"))))
    (testing "and a token this engine never minted is a 422 that names it"
      (let [r (feed! eng :query "cursor=not-a-cursor")]
        (is (= 422 (:status r)))
        (is (some? (get-in r [:doc :errors :cursor])))))))

(deftest the-cursor-round-trips-and-hides-its-own-arithmetic
  (let [c {:day "2026-08-24" :seed "abc" :offset 12}
        token (feed/encode-cursor c)]
    (is (= c (feed/decode-cursor token)))
    (is (not (str/includes? token "2026"))
        "opaque: a client that could edit the seed could re-roll its
         own feed until it liked the order")
    (testing "and it carries a draw where one is riding (waymark-8um.2)"
      (let [d (assoc c :draw "abc123")]
        (is (= d (feed/decode-cursor (feed/encode-cursor d))))
        (is (= token (feed/encode-cursor (dissoc d :draw)))
            "a daily-order cursor is the token this engine always minted")))))

;; ── deal again: the person spins (waymark-8um.2, laws v3 § 6) ───────

(deftest the-draw-is-the-last-ingredient-and-the-daily-seed-is-unchanged
  (let [recipe feed/default-recipe]
    (testing "no draw hashes exactly what it always hashed"
      (is (= (feed/seed-of recipe "mom" "2026-08-24")
             (feed/seed-of recipe "mom" "2026-08-24" nil))
          "the daily order is not the default draw by convention — it is
           the same hash, so nothing moves for a reader who never taps"))
    (testing "a draw joins the seed, and two draws are two orders"
      (is (not= (feed/seed-of recipe "mom" "2026-08-24")
                (feed/seed-of recipe "mom" "2026-08-24" "aaa")))
      (is (not= (feed/seed-of recipe "mom" "2026-08-24" "aaa")
                (feed/seed-of recipe "mom" "2026-08-24" "bbb"))))
    (testing "and one draw is as stable as the day"
      (is (= (feed/seed-of recipe "mom" "2026-08-24" "aaa")
             (feed/seed-of recipe "mom" "2026-08-24" "aaa"))))
    (testing "the day is still in the hash under a draw — midnight rolls
              every draw, which is why a stale cursor is still a 409"
      (is (not= (feed/seed-of recipe "mom" "2026-08-24" "aaa")
                (feed/seed-of recipe "mom" "2026-08-25" "aaa"))))
    (testing "a nonce this door cannot spell is refused rather than
              quietly read as the daily order"
      (is (nil? (feed/parse-draw nil)))
      (is (nil? (feed/parse-draw "  ")))
      (is (= "a-B_9.z" (feed/parse-draw " a-B_9.z ")))
      (doseq [bad ["with space" "semi;colon" (apply str (repeat 65 "a"))]]
        (is (thrown? clojure.lang.ExceptionInfo (feed/parse-draw bad))
            (str (pr-str bad) " is not a draw"))))))

(deftest dealing-again-answers-a-fresh-order-and-leaves-the-day-alone
  (let [eng (boot)]
    (dotimes [i 10]
      (let [self (make! eng :fd_parcel (str "parcel " i))]
        (call! eng :post (str self "/-/finish"))))
    (dotimes [i 6] (make! eng :fd_errand (str "errand " i)))
    (let [daily (:doc (feed! eng))
          drawn (:doc (feed! eng :query "draw=spin1"))
          again (:doc (feed! eng :query "draw=spin1"))
          other (:doc (feed! eng :query "draw=spin2"))
          after (:doc (feed! eng))]
      (testing "the draw reaches the seed"
        (is (not= (:seed daily) (:seed drawn)))
        (is (not= (:seed drawn) (:seed other))))
      (testing "the document names the draw, and a daily read names none"
        (is (= "spin1" (:draw drawn)))
        (is (nil? (:draw daily)))
        (is (str/includes? (str (:self drawn)) "draw=spin1"))
        (is (str/includes? (str (:summary drawn)) "draw spin1")))
      (testing "and says so in the household's own words"
        (is (some #(str/includes? (str %) "You dealt again") (:notes drawn)))
        (is (not-any? #(str/includes? (str %) "dealt again") (:notes daily))))
      (testing "one draw is stable, with the same cards in the same order"
        (is (= (:seed drawn) (:seed again)))
        (is (= (card-ids drawn) (card-ids again))))
      (testing "the day's own order is untouched — a draw lives in the
                address and nowhere else"
        (is (= (:seed daily) (:seed after)))
        (is (= (card-ids daily) (card-ids after)))))))

(deftest the-pages-of-a-draw-continue-that-draw
  (let [eng (boot)]
    (dotimes [i 10]
      (let [self (make! eng :fd_parcel (str "parcel " i))]
        (call! eng :post (str self "/-/finish"))))
    (let [p1 (:doc (feed! eng :query "draw=spin1"))
          href (get-in p1 [:links :next :href])
          p2 (:doc (feed! eng :query (second (str/split (str href) #"\?" 2))))
          daily (:doc (feed! eng))
          d-href (get-in daily [:links :next :href])]
      (testing "links.next carries the draw, in the href and in the cursor"
        (is (str/includes? (str href) "draw=spin1"))
        (is (= "spin1" (:draw (feed/decode-cursor
                               (second (str/split (str href) #"cursor="))))))
        (is (not (str/includes? (str d-href) "draw=")))
        (is (nil? (:draw (feed/decode-cursor
                          (second (str/split (str d-href) #"cursor=")))))))
      (testing "and page two is page two of THAT draw"
        (is (= "spin1" (:draw p2)))
        (is (= #{"archive"} (set (map :section (:cards p2)))))
        (let [all (concat (card-ids p1) (card-ids p2))]
          (is (= (count all) (count (set all))))))
      (testing "a cursor alone continues its own draw — a client may drop
                the parameter and still walk the spin it started"
        (let [bare (str "cursor=" (second (str/split (str href) #"cursor=")))]
          (is (= "spin1" (:draw (:doc (feed! eng :query bare)))))))
      (testing "but two halves that disagree are refused, never guessed at"
        (let [crossed (feed! eng :query
                             (str "draw=spin2&cursor="
                                  (second (str/split (str href) #"cursor="))))]
          (is (= 422 (:status crossed)))
          (is (some? (get-in crossed [:doc :errors :draw])))))
      (testing "and a mangled nonce is a 422 that names the parameter"
        (let [r (feed! eng :query "draw=not%20a%20draw")]
          (is (= 422 (:status r)))
          (is (some? (get-in r [:doc :errors :draw]))))))))

;; ── the door's own posture ──────────────────────────────────────────

(deftest anonymous-has-no-feed-because-a-feed-is-somebodys
  (let [eng (boot)]
    (is (= 404 (:status ((engine/handler eng)
                         {:request-method :get :uri "/api/-/feed"
                          :headers {}}))))))

;; ── feed.preview_as (waymark-iqa.23) ────────────────────────────────
;;
;; The lawful way to read somebody else's feed, and the only one. The
;; conformance pack proves it against whatever an application
;; declared; what belongs HERE is the thing a driver with one world
;; cannot arrange — THREE identities built to disagree, so that "the
;; preview is the member's own read" is a comparison rather than a
;; shape check, and so the previewer's own feed is demonstrably a
;; different document from the one the preview answers.

(defn- preview-world
  "A house with rows, a member to be previewed, the capability row,
  and an accepted grant filtered to that member. → the ids and the
  headers three principals arrive with."
  [eng & [{:keys [member-in-filter]}]]
  (doseq [t ["e1" "e2" "e3"]] (make! eng :fd_errand t))
  (doseq [t ["p1" "p2" "p3"]] (make! eng :fd_parcel t))
  (let [mid (last (str/split (get-in (call! eng :post "/api/members"
                                            :body {:display "Jack"
                                                   :actor_type "human"})
                                     [:doc :self])
                             #"/"))
        cap (call! eng :post "/api/capabilities" :body caps/feed-preview-as)
        minted (call! eng :post "/api/grants"
                      :body {:audience "claude"
                             :scope [(cond-> {:kind caps/feed-preview-as-token
                                              :actions []}
                                       (not= :none member-in-filter)
                                       (assoc :filter
                                              {:member (or member-in-filter
                                                           mid)}))]})
        gid (last (str/split (str (get-in minted [:doc :self])) #"/"))
        as-claude {"x-waymark-principal" "claude"
                   "x-waymark-actor-type" "agent"}
        accepted (call! eng :post (str "/api/grants/" gid "/-/accept")
                        :headers as-claude)]
    {:member mid
     :cap-status (:status cap)
     :mint-status (:status minted)
     :mint-doc (:doc minted)
     :accept-status (:status accepted)
     :as-member {"x-waymark-principal" mid}
     :as-claude as-claude
     :worn (assoc as-claude "x-waymark-grant" gid)}))

(deftest a-preview-is-the-members-own-feed-computed-through-their-own-sight
  (let [eng (boot)
        {:keys [member cap-status mint-status accept-status
                as-member as-claude worn]} (preview-world eng)]
    (is (= 201 cap-status) "the capability is a ROW, and it is created once")
    (is (= 201 mint-status) "a dotted token the registry carries mints")
    (is (= 200 accept-status))

    (let [theirs (feed! eng :headers as-member)
          preview (feed! eng :headers worn :query (str "preview_as=" member))
          mine (feed! eng :headers worn)]
      (testing "the previewer's OWN feed is not the member's — one narrow
                capability grant and no kind at all"
        (is (= 200 (:status mine)))
        (is (empty? (rows-of (:doc mine)))
            "if this were ever non-empty the comparison below would prove
             nothing"))

      (testing "the preview is what the member reads, card for card"
        (is (= 200 (:status preview)) (pr-str (:doc preview)))
        (is (seq (rows-of (:doc theirs)))
            "a member with an empty feed would make the equality free")
        (is (= (card-ids (:doc theirs)) (card-ids (:doc preview))))
        (is (= (:seed (:doc theirs)) (:seed (:doc preview)))
            "the seed is (salt, THE MEMBER, today) — a preview seeded by
             the caller would be a fourth member's order, belonging to
             nobody"))

      (testing "and it is never silent about being one"
        (let [doc (:doc preview)]
          (is (= member (get-in doc [:preview :of :id])))
          (is (= "Jack" (get-in doc [:preview :of :display])))
          (is (= "claude" (get-in doc [:preview :by :id])))
          (is (str/includes? (str (:summary doc)) "PREVIEW"))
          (is (some #(str/includes? % "feed.preview_as") (:notes doc)))
          (is (str/includes? (str (:self doc)) "preview_as=")
              "the address a client would re-fetch keeps the preview, or
               a reload would quietly become the caller's own feed")))

      (testing "a previewer may deal again, because dealing again is a
                READ — the draw rides the preview, the order is the
                member's own re-drawn, and nothing is written
                (waymark-8um.2)"
        (let [spun (feed! eng :headers worn
                          :query (str "preview_as=" member "&draw=spin1"))
              doc (:doc spun)
              theirs-spun (feed! eng :headers as-member :query "draw=spin1")]
          (is (= 200 (:status spun)))
          (is (= "spin1" (:draw doc)))
          (is (not= (:seed (:doc preview)) (:seed doc))
              "the tap reached the seed")
          (is (= (:seed (:doc theirs-spun)) (:seed doc))
              "and it is still (salt, THE MEMBER, today, draw) — a preview
               of a spin is the member's spin, not the previewer's")
          (is (= (card-ids (:doc theirs-spun)) (card-ids doc)))
          (is (= member (get-in doc [:preview :of :id]))
              "still stamped: a preview is never quiet, spun or not")
          (is (str/includes? (str (:self doc)) "preview_as="))
          (is (str/includes? (str (:self doc)) "draw=spin1"))
          (is (not (true? (get-in doc [:views :recording])))
              "and a previewer's page still has nothing to beacon about")
          (is (= (card-ids (:doc theirs))
                 (card-ids (:doc (feed! eng :headers as-member))))
              "…and the member's own daily order is where it was")))

      (testing "the verbs render and they are the MEMBER's — a previewer
                who fires one is judged as themselves"
        (let [card (first (remove #(= "seam" (:card_id %))
                                  (:cards (:doc preview))))
              verb (first (keys (:actions card)))]
          (is (some? verb) "a card with no verb proves nothing here")
          (is (= 404 (:status (call! eng :post
                                     (str (:self card) "/-/" (name verb))
                                     :headers worn)))
              "the router judges the ACTUAL caller at the row's own door,
               and this one's leash names one capability and no kind"))))))

(deftest the-capability-is-the-door-and-the-filter-is-the-constraint
  (let [eng (boot)
        {:keys [member as-claude worn]} (preview-world eng)
        q (str "preview_as=" member)]
    (testing "no grant presented, no preview — and the refusal says what
              to ask for, because capabilities are words"
      (let [r (feed! eng :headers as-claude :query q)]
        (is (= 403 (:status r)))
        (is (str/includes? (str (get-in r [:doc :detail])) "feed.preview_as"))
        (is (seq (get-in r [:doc :remedies])))))

    (testing "an unscoped human is not excused either — a capability is
              worn, never inherited from being trusted"
      (is (= 403 (:status (feed! eng :query q)))))

    (testing "the grant admits ONE member, and a request for another is
              refused by name"
      (let [r (feed! eng :headers worn
                     :query "preview_as=01HZZZZZZZZZZZZZZZZZZZZZZZ")]
        (is (= 403 (:status r)))
        (is (str/includes? (str (get-in r [:doc :detail])) member))))

    (testing "and no preview_as at all is the caller's own feed, unstamped"
      (let [r (feed! eng :headers worn)]
        (is (= 200 (:status r)))
        (is (nil? (:preview (:doc r))))
        (is (= "/api/-/feed" (:self (:doc r))))))))

(deftest an-unfiltered-preview-grant-is-refused-at-the-door
  (let [eng (boot)
        {:keys [member mint-status worn]} (preview-world
                                           eng {:member-in-filter :none})]
    (is (= 201 mint-status)
        "the MINT succeeds — waymark validates a filter's shape and never
         its meaning, so 'unfiltered is too wide' is a decision only the
         enforcement point can make")
    (let [r (feed! eng :headers worn :query (str "preview_as=" member))]
      (is (= 403 (:status r)))
      (is (str/includes? (str (get-in r [:doc :detail])) "no filter")
          (pr-str (:doc r))))))

;; ── the feed explains itself (waymark-iqa.29) ───────────────────────
;;
;; The owner found a movie in do-now and could not find out why. Four
;; layers had agreed to put it there — a framework predicate, a
;; declared trait, a recipe line and the day's seed — and none of them
;; said so anywhere a person could read. What belongs in THIS file
;; rather than in the pack is the half a driver with one world cannot
;; arrange: one fixture kind DECLARED TWO WAYS, so the claim 'break a
;; trait and the citation changes' is proved by changing a trait
;; rather than by reading a docstring.

(defn- shelf-kind
  "One fixture kind, declared with `:over` and without it. Breaking a
  trait means declaring it differently HERE; a household's own kind is
  never edited to make a test go red."
  [over?]
  (r/resource
   (cond-> {:kind :fd_shelf :plural "fd_shelves"
            :states [:open :done :shelved] :initial :open
            :terminal #{:done :shelved}
            :summary "{data.title} · {state}"
            :display {:title "{data.title}"}
            :schema [:map [:title [:string {:min 1 :max 60}]]]
            :actions {:finish {:from #{:open} :to :done
                               :safety {:idempotent true :reversible false
                                        :confirm false :one-way "Done."}}
                      :shelve {:from #{:open} :to :shelved
                               :safety {:idempotent true :reversible false
                                        :confirm false :one-way "Shelved."}}}}
     over? (assoc :over {:accomplished #{:done} :let-go #{:shelved}}))))

(def ^:private shelf-recipe
  {:salt "waymark-feed" :zone "UTC"
   :order [{:section :do_now :population :next_actions :take 3}
           {:seam true :sentence "That's the house, caught up."}]})

(defn- shelf-eng [over?]
  (engine/engine {:storage (memory/storage)
                  :resources [(shelf-kind over?)]
                  :feed shelf-recipe}))

(defn- says-of [doc card-id]
  (str/join " " (:says (:why (first (filter #(= card-id (:card_id %))
                                            (:cards doc)))))))

(deftest break-a-trait-and-the-citation-changes
  (testing "the citation quotes the DECLARATION, so a kind that spells
            :over reads differently from one that does not — and the
            only thing that moved between these two engines is the
            trait"
    (let [spelled (shelf-eng true)
          mute (shelf-eng false)]
      (doseq [eng [spelled mute]]
        (call! eng :post "/api/fd_shelves" :body {:title "Half a novel"}))
      (let [a (:doc (feed! spelled :query "explain=1"))
            b (:doc (feed! mute :query "explain=1"))
            said (fn [doc] (says-of doc (:card_id (first (:cards doc)))))]
        (is (= (mapv :section (:cards a)) (mapv :section (:cards b)))
            "same fixture, same shape of feed — the trait is the only
             variable, and it moves the CITATION rather than the order")
        (is (str/includes? (said a) ":over says done is a deed"))
        (is (str/includes? (said a) "shelved is let go"))
        (is (str/includes? (said a) "which is neither")
            "and the row's own word is quoted back at it")
        (is (str/includes? (said b) "spells no :over")
            "unspelled, the machine's endings are the endings — which is
             exactly what the code meant before the trait existed")
        (is (not (str/includes? (said b) "let go")))))))

(deftest a-card-cites-the-recipe-line-that-actually-admitted-it
  (testing "two do-now lines and only one of them is the parcels': a
            citation that named the wrong one would be a citation of a
            layer that did not decide anything"
    (let [recipe (assoc feed/default-recipe
                        :order [{:section :do_now :population :next_actions
                                 :take 2 :kinds [:fd_parcel]
                                 :says "Do now, first two: the parcels."}
                                {:section :do_now :population :next_actions
                                 :take 3}
                                {:seam true
                                 :sentence "That's the house, caught up."}])
          eng (boot-house {:feed recipe})]
      (dotimes [i 20] (post! eng "fd_errands" {:title (str "errand " i)}))
      (dotimes [i 4] (post! eng "fd_parcels" {:title (str "parcel " i)}))
      (let [doc (:doc (feed! eng))
            rows (remove #(= "seam" (:card_id %)) (:cards doc))]
        (doseq [c rows]
          (is (= (if (= "fd_parcel" (str (:kind c))) 0 1)
                 (:line (:why c)))
              (str (:card_id c) " cites line " (:line (:why c)))))
        (testing "and the draw is honest: a place inside the size of the
                  draw, never a place inside the page"
          (doseq [c rows]
            (is (<= 1 (:rank (:why c)) (:of (:why c))))))
        (is (= 4 (:of (:why (first (filter #(= "fd_parcel" (str (:kind %)))
                                           rows)))))
            "the parcels line was offered every parcel, not every row")
        (testing "the seam cites its own line too"
          (is (= 2 (:line (:why (first (filter #(= "seam" (:card_id %))
                                               (:cards doc)))))))))))

  (testing "and the second line reports what the first one had already
            claimed — the one cheap half of explaining absence"
    (let [recipe (assoc feed/default-recipe
                        :order [{:section :do_now :population :next_actions
                                 :take 1 :kinds [:fd_parcel]}
                                {:section :do_now :population :next_actions
                                 :take 3}
                                {:seam true
                                 :sentence "That's the house, caught up."}])
          eng (boot-house {:feed recipe})]
      (dotimes [i 4] (post! eng "fd_parcels" {:title (str "parcel " i)}))
      (dotimes [i 2] (post! eng "fd_errands" {:title (str "errand " i)}))
      (let [lines (:lines (:recipe (:doc (feed! eng))))]
        (is (= 4 (:offered (first lines))))
        (is (= 1 (:showed (first lines))))
        (is (= 4 (:claimed_above (second lines)))
            "every parcel was the first line's, shown or not — the mixer's
             total claim, said out loud")
        (is (= 2 (:offered (second lines))))))))

(deftest the-recipe-reads-back-narrated
  (let [eng (boot-house {:feed (assoc feed/default-recipe
                                      :order
                                      [{:section :do_now
                                        :population :next_actions :take 2
                                        :says "Two from the queue, first."}
                                       {:seam true
                                        :sentence "That's the house, caught up."}
                                       {:section :archive :population :memories
                                        :take 3 :bottomless true}])})
        recipe (:recipe (:doc (feed! eng)))]
    (is (= 3 (count (:lines recipe))))
    (is (every? (comp seq :says) (:lines recipe))
        "every line narrates itself, whether or not the household wrote it")
    (is (= "Two from the queue, first." (:says (first (:lines recipe))))
        "a household's own sentence wins — the recipe is an engine opt and
         its entries are data, so this costs no declaration a fingerprint")
    (is (str/includes? (:says (nth (:lines recipe) 2))
                       "what this house was doing a year ago this week")
        "and a line with no sentence of its own gets the population's")
    (is (str/includes? (:says (second (:lines recipe))) "The seam"))
    (is (str/includes? (:guarantees recipe) "exactly one card is the seam")
        "the four assembly checks, as the one sentence they buy a reader")))

(deftest explain-is-a-read-flag-and-nothing-else
  (testing "the cards, the order and the seed are identical with the
            sentences and without them — which is the law that lets a
            client fetch the citation LATE and line it up by card_id"
    (let [eng (boot-house)]
      (dotimes [i 6] (post! eng "fd_errands" {:title (str "errand " i)}))
      (let [plain (:doc (feed! eng))
            spelled (:doc (feed! eng :query "explain=1"))]
        (is (= (card-ids plain) (card-ids spelled)))
        (is (= (:seed plain) (:seed spelled)))
        (is (every? #(nil? (:says (:why %))) (:cards plain))
            "a plain read carries the citation's NUMBERS and no prose")
        (is (every? #(or (= "seam" (:card_id %)) (seq (:says (:why %))))
                    (:cards spelled)))
        (testing "and links.next keeps the parameter, so a reader who
                  asked why is not answered a silent archive"
          (let [href (str (get-in spelled [:links :next :href]))]
            (is (or (str/blank? href) (str/includes? href "explain=1")))))))))

;; ── the contest (waymark-8um.3, laws v3 law 5) ──────────────────────
;;
;; What belongs here rather than in the pack is what a driver with one
;; world cannot arrange: a member whose whole view history the test
;; wrote, a second member built to prove the formula never reads across
;; the house, and a do-now lane with two kinds in it so a step BACK is
;; something a reader could see happen.

(defn- consent! [eng who]
  (call! eng :post "/api/feed_view_consents" :body {}
         :headers {"x-waymark-principal" who}))

(defn- saw! [eng who card-id population day]
  (call! eng :post "/api/feed_views"
         :body {:card_id card-id :population population :day day}
         :headers {"x-waymark-principal" who}))

(defn- days-before [^String day ^long n]
  (mapv #(str (.minusDays (java.time.LocalDate/parse day) (long (inc %))))
        (range n)))

(defn- why-of [doc card-id]
  (:why (first (filter #(= card-id (str (:card_id %))) (:cards doc)))))

(deftest the-formula-is-two-numbers-and-they-ride-the-wire
  (testing "law 5: the ranking formula is DATA the owner can read. It is
            on every answer, in the editor's own shape, narrated"
    (let [eng (boot-house)]
      (post! eng "fd_errands" {:title "one"})
      (let [recipe (:recipe (:doc (feed! eng)))]
        (is (= {:window_days 14 :cools_after 3} (:formula recipe)))
        (is (str/includes? (:formula_says recipe) "cools one step"))
        (is (str/includes? (:formula_says recipe) "14"))
        (is (str/includes? (:formula_says recipe) "nobody else's")))))
  (testing "and the household's own numbers answer instead when it wrote
            some — a row that names none keeps the deployment's"
    (is (= {:window-days 14 :cools-after 3} (feed/formula-of {})))
    (is (= {:window-days 30 :cools-after 3}
           (feed/formula-of {:formula {:window-days 30}})))
    (is (= 0 (feed/cooling-step {:cools-after 3} 2)))
    (is (= 1 (feed/cooling-step {:cools-after 3} 3)))
    (is (= 4 (feed/cooling-step {:cools-after 3} 14)))
    (is (= 0 (feed/cooling-step {:cools-after 0} 99))
        "cools_after 0 is the contest turned off, and it is a number a
         person can see rather than a key they have to know to delete"))
  (testing "a formula that is not two numbers refuses at assembly"
    (is (str/includes? (str (try (feed/check-recipe!
                                  (assoc feed/default-recipe
                                         :formula {:cools-after "soon"}))
                                 (catch Exception e (ex-message e))))
                       ":formula :cools-after"))))

(deftest a-non-recording-member-reads-the-feed-they-always-read
  (testing "off is the default, so the whole contest is inert: no query
            is run, no card carries a cooling key, and the document is
            the one this engine answered before the formula existed"
    (let [eng (boot-house)]
      (dotimes [i 3] (post! eng "fd_errands" {:title (str "errand " i)}))
      (dotimes [i 3] (post! eng "fd_parcels" {:title (str "parcel " i)}))
      (let [before (:doc (feed! eng))
            day (str (:day before))
            ;; a SECOND member records everything she is shown — and the
            ;; formula reads the reader's own rows, so none of it may
            ;; reach mom's morning
            _ (consent! eng "iris")
            _ (doseq [c (:cards (:doc (feed! eng :headers
                                             {"x-waymark-principal" "iris"})))
                      :when (not= "seam" (str (:card_id c)))
                      d (days-before day 9)]
                (saw! eng "iris" (str (:card_id c)) (str (:population c)) d))
            after (:doc (feed! eng))]
        (is (= before after)
            "the same document, key for key: same seed, same order, same
             notes, same cards, same narrated recipe — a non-recording
             member's feed is not merely similar to what it was, it is the
             same value, and the contest ran no query to compute it")
        (is (every? #(and (nil? (:seen (:why %))) (nil? (:cooled (:why %))))
                    (:cards after))
            "and no card carries a cooling key, because there is nothing
             to say")
        (is (not-any? #(str/includes? (str %) "weighted by what you have")
                      (:notes after))
            "nor does the document mention a mechanism that did nothing —
             a surface that explained an inert contest every morning
             would be advertising it")))))

(deftest a-cooled-card-steps-back-inside-its-own-lane-and-says-why
  (let [eng (boot-house)]
    (dotimes [i 3] (post! eng "fd_errands" {:title (str "errand " i)}))
    (dotimes [i 3] (post! eng "fd_parcels" {:title (str "parcel " i)}))
    (consent! eng "mom")
    (let [before (:doc (feed! eng))
          day (str (:day before))
          top (first (filter #(= "do_now" (str (:section %))) (:cards before)))
          cid (str (:card_id top))
          _ (is (zero? (long (:seen (:why top))))
                "a card nobody has been shown is FRESH — it ranks as unseen
                 rather than as unloved")
          seen (doseq [d (days-before day 3)]
                 (saw! eng "mom" cid (str (:population top)) d))
          after (:doc (feed! eng :query "explain=1"))
          do-now (filter #(= "do_now" (str (:section %))) (:cards after))]
      (is (nil? seen))
      (is (= 3 (long (:seen (why-of after cid))))
          "three days on the feed with nothing done — PER DAY, because the
           storage counts one row per card per day and a person who deals
           again three times is having one morning (waymark-dtv)")
      (is (= 1 (long (:cooled (why-of after cid))))
          "…which is exactly one step at cools_after 3")
      (is (not= cid (str (:card_id (first do-now))))
          "so it is behind its lane-mate now: the spread still decides
           whose turn it is, and the formula only weights WITHIN a lane")
      (is (= (count (filter #(= "do_now" (str (:section %))) (:cards before)))
             (count do-now))
          "and the line shows exactly as many cards as it did — cooling
           reorders, it never starves")
      (is (some #(str/includes? (str %) "Cooled — shown 3 days")
                (:says (why-of after cid)))
          "and the card says so, in the household's own words, with the
           recipe's own numbers in the sentence")
      (is (some #(str/includes? (str %) "Fresh —")
                (:says (why-of after (str (:card_id (first do-now))))))
          "while the card that overtook it says it has never been shown")
      (is (some #(str/includes? (str %) "weighted by what you have already")
                (:notes after))
          "and the document says the contest weighted this read"))))

(deftest the-floor-holds-under-any-view-data
  (testing "law 3: a floored line never empties. The formula is a sort
            key and there is no arithmetic anywhere that can drop a card,
            so however much a member has seen, every line shows what its
            take says it shows"
    (let [eng (boot-house)]
      (dotimes [i 4] (post! eng "fd_errands" {:title (str "errand " i)}))
      (dotimes [i 4] (post! eng "fd_parcels" {:title (str "parcel " i)}))
      (consent! eng "mom")
      (let [before (:doc (feed! eng))
            day (str (:day before))
            showed (fn [doc] (mapv #(get % :showed) (:lines (:recipe doc))))]
        ;; every card the house has, seen on every day of the window
        (doseq [c (:cards before)
                :when (not= "seam" (str (:card_id c)))
                d (days-before day 13)]
          (saw! eng "mom" (str (:card_id c)) (str (:population c)) d))
        (let [after (:doc (feed! eng))]
          (is (= (showed before) (showed after))
              "line for line, the same number of cards")
          (is (= (count (:cards before)) (count (:cards after))))
          (let [do-now (filter #(= "do_now" (str (:section %))) (:cards after))]
            (is (every? #(or (zero? (long (:seen (:why %) 0)))
                             (<= 4 (long (:cooled (:why %) 0))))
                        do-now)
                "every card the member actually saw is thoroughly cold, and
                 still on the page — which is the whole of the floor: a
                 contest that could bury a line could not be measured")
            (is (some #(zero? (long (:seen (:why %) 0))) do-now)
                "and a card the member has never been shown has come
                 forward into the page the cold ones used to fill, which is
                 the contest doing the one thing it is for")))))))

(deftest a-household-writes-its-own-contest-and-the-next-read-obeys
  (testing "the two numbers are a field on the recipe ROW, so tuning the
            contest is the form that already exists — and the very next
            read is answered by what was written, uncached (waymark-4yn's
            own decision, inherited)"
    (let [eng (boot-house)]
      (dotimes [i 3] (post! eng "fd_errands" {:title (str "errand " i)}))
      (dotimes [i 3] (post! eng "fd_parcels" {:title (str "parcel " i)}))
      (consent! eng "mom")
      (let [before (:doc (feed! eng))
            day (str (:day before))
            top (first (filter #(= "do_now" (str (:section %))) (:cards before)))
            made (call! eng :post "/api/feed_recipes"
                        :body {:label "One morning is enough"
                               :scope "household"
                               :order (get-in before [:recipe :order])
                               :formula {:window_days 30 :cools_after 1}})]
        (is (= 201 (:status made)) (pr-str (:doc made)))
        ;; one morning, and at cools_after 1 that is already a step
        (saw! eng "mom" (str (:card_id top)) (str (:population top))
              (first (days-before day 1)))
        (let [after (:doc (feed! eng))]
          (is (= {:window_days 30 :cools_after 1}
                 (get-in after [:recipe :formula]))
              "the household's own numbers answer this read")
          (is (str/includes? (get-in after [:recipe :formula_says])
                             "1 day inside the last 30"))
          (is (= 1 (long (:cooled (why-of after (str (:card_id top))))))
              "…and the arithmetic is the one the household just wrote")
          (is (not= (str (:card_id top))
                    (str (:card_id (first (filter #(= "do_now" (str (:section %)))
                                                  (:cards after))))))))
        (testing "and turning the contest off is a number, not a deletion"
          (let [rid (last (str/split (str (:self (:doc made))) #"/"))
                revised (call! eng :post (str "/api/feed_recipes/" rid "/-/revise")
                               :body {:label "The seed alone"
                                      :order (get-in before [:recipe :order])
                                      :formula {:window_days 30 :cools_after 0}}
                               ;; an edit implies the fence
                               :headers {"if-match"
                                         (get-in made [:doc :meta :etag])})
                after (:doc (feed! eng))]
            (is (= 200 (:status revised)) (pr-str (:doc revised)))
            (is (str/includes? (get-in after [:recipe :formula_says])
                               "The contest is off"))
            (is (every? #(nil? (:seen (:why %))) (:cards after))
                "no card carries a cooling key, because nothing is cooling")
            (is (= (mapv :card_id (:cards before)) (mapv :card_id (:cards after)))
                "and the order is the seeded one again")))))))

;; ── the crown's rank (waymark-1uv.2) ────────────────────────────────
;;
;; This world holds no outcome kinds, so what can be proved here is the
;; half a driver with one world cannot arrange anyway: the arithmetic
;; as a PURE FUNCTION — seed-independent given its inputs — and the
;; recipe's four numbers on the wire, narrated, checked at assembly and
;; diffed. The live half (a real bundle, a real request, a real word
;; said about a real decline) is workqueue10.outcome-test's, over the
;; household's own registry.

(deftest the-crown-ranks-by-four-numbers-and-the-seed-only-breaks-ties
  (let [w feed/default-crown-rank
        fresh {:asked false :value :declared :days-left 7}
        observed {:asked false :value :observed :days-left 7}
        cooled {:asked false :value :declared :seen 3 :cooled 1 :days-left 7}
        never {:asked false :value :declared :declined "never_this" :days-left 7}
        wrong-time {:asked false :value :declared :declined "wrong_time" :days-left 7}
        lapsing {:asked false :value :declared :days-left 1}
        early {:asked false :value :declared :days-left 7 :early 7 :turned-down 1}
        on-time {:asked false :value :declared :days-left 7 :early 0 :turned-down 3}
        pulled {:asked true :value :observed :declined "never_this" :days-left 0}
        ;; an agent's word (waymark-1uv.6): the same fresh bundle, scored
        judged-up {:asked false :value :declared :days-left 7
                   :judged {:score 0.9M :by "cairn" :says "This one."}}
        judged-down {:asked false :value :declared :days-left 7
                     :judged {:score 0.1M :by "cairn" :says "Not this one."}}
        judged-half {:asked false :value :declared :days-left 7
                     :judged {:score 0.5M :by "cairn" :says "No view."}}]
    (testing "the arithmetic, predictable from the four numbers a household reads"
      (is (= 17 (feed/crown-lift w fresh)) "10 declared + 7 days left")
      (is (= 7 (feed/crown-lift w observed)) "an observed value lifts nothing")
      (is (= 15 (feed/crown-lift w cooled)) "one cooled step holds 2")
      (is (= 9 (feed/crown-lift w never)) "never this holds 4 × 2")
      (is (= 15 (feed/crown-lift w wrong-time)) "wrong time holds 1 × 2")
      (is (= 11 (feed/crown-lift w lapsing)) "a day left lifts 1, not 7")
      (is (= 3 (feed/crown-lift w early)) "a week early holds 7 × 2 (waymark-1uv.10)")
      (is (= 17 (feed/crown-lift w on-time))
          "past the date nothing holds it, however long the chain")
      (is (= -8 (feed/crown-lift w pulled))
          "the lift can go negative and the asked tier does not care"))
    (testing "an agent's score is one more weighted number, centred on a half
              (waymark-1uv.6): 0.9 lifts one at the default weight, 0.1 holds
              one, a half is silence and so is no score at all"
      (is (= 18 (feed/crown-lift w judged-up)))
      (is (= 16 (feed/crown-lift w judged-down)))
      (is (= 17 (feed/crown-lift w judged-half)))
      (is (= 1 (feed/judged-lift 1 0.9M)))
      (is (= 0 (feed/judged-lift 1 0.6M)) "a nudge that rounds to nothing")
      (is (= -1 (feed/judged-lift 1 0.1M)))
      (is (= 0 (feed/judged-lift 1 nil)) "nobody's score is silence")
      (is (= 0 (feed/judged-lift 0 1M)) "weight 0 makes it inert")
      (is (= 5 (feed/judged-lift 10 0.75M)))
      (is (= -10 (feed/judged-lift 10 0M)))
      (is (= 17 (feed/crown-lift (assoc w :judged 0) judged-up))
          "the weight turned down leaves the score unread"))
    (testing "the four words weigh in the epic's order, and an unknown word
              still says the house turned it down"
      (is (> (feed/reason-weight "never_this") (feed/reason-weight "wrong_way")
             (feed/reason-weight "wrong_piece") (feed/reason-weight "wrong_time")
             (feed/reason-weight nil)))
      (is (= 1 (feed/reason-weight "some_fifth_word"))))
    (testing "the order is a pure function of the inputs; the seed only breaks
              ties. Swap every hash and a strict order does not move"
      (let [cands [[:fresh fresh] [:observed observed] [:cooled cooled]
                   [:never never] [:lapsing lapsing] [:pulled pulled]
                   [:early early] [:judged-up judged-up]]
            order (fn [hash-of]
                    (mapv first (sort-by (fn [[k in]] (feed/crown-key w in (hash-of k)))
                                         cands)))
            a (order #(wire/sha256-hex (str "seed-a" %)))
            b (order #(wire/sha256-hex (str "seed-b" %)))]
        (is (= [:pulled :judged-up :fresh :cooled :lapsing :never :observed :early] a))
        (is (= a b) "two seeds, one order — nothing here was tied")
        (is (= :pulled (first a))
            "a bundle answering the person's own request stands first with a
             NEGATIVE lift — asked-for is a tier, never a weight")))
    (testing "…and between equals the seed decides, both ways"
      (let [twin {:asked false :value :declared :days-left 7}
            key-a (fn [k] (feed/crown-key w twin (wire/sha256-hex (str "a" k))))
            key-b (fn [k] (feed/crown-key w twin (wire/sha256-hex (str "b" k))))
            names [:p :q :r :s :t :u]]
        (is (not= (sort-by key-a names) (sort-by key-b names))
            "given six equal keys some seed disagrees — or the hash is not
             the last key")))
    (testing "all five at zero is the seed alone, with the person's request
              still first"
      (let [off {:declared 0 :cooled 0 :declined 0 :fresh 0 :early 0 :judged 0}]
        (is (= 0 (feed/crown-lift off never)))
        (is (= [0 0 "h"] (feed/crown-key off pulled "h")))
        (is (= [1 0 "h"] (feed/crown-key off fresh "h")))))))

(deftest the-crowns-rank-rides-the-recipe-and-refuses-at-assembly
  (testing "law 5 at the crown: five numbers on every answer, in the editor's
            shape, narrated with the numbers in the sentence"
    (let [eng (boot-house)]
      (post! eng "fd_errands" {:title "one"})
      (let [recipe (:recipe (:doc (feed! eng)))]
        (is (= {:declared 10 :cooled 2 :declined 2 :fresh 1 :early 2 :judged 1}
               (:crown_rank recipe)))
        (is (str/includes? (:crown_rank_says recipe) "lifts a bundle 10"))
        (is (str/includes? (:crown_rank_says recipe) "8 for never this"))
        (is (str/includes? (:crown_rank_says recipe) "stands above every one"))
        (is (str/includes? (:crown_rank_says recipe) "recomposition arrives"))
        (is (str/includes? (:crown_rank_says recipe) "a score of 1 lifts it 1")
            "the agent's number is narrated while it is non-zero (waymark-1uv.6)")
        (is (str/includes? (:guarantees recipe) "the crown's rank is six")))))
  (testing "a row that names none keeps the deployment's, and one that names
            some keeps the rest"
    (is (= {:declared 10 :cooled 2 :declined 2 :fresh 1 :early 2 :judged 1} (feed/crown-rank-of {})))
    (is (= {:declared 25 :cooled 2 :declined 2 :fresh 1 :early 2 :judged 1}
           (feed/crown-rank-of {:crown-rank {:declared 25}}))))
  (testing "a number that is not one refuses at assembly, by name"
    (is (str/includes? (str (try (feed/check-recipe!
                                  (assoc feed/default-recipe
                                         :crown-rank {:declined "lots"}))
                                 (catch Exception e (ex-message e))))
                       ":crown-rank :declined"))
    (is (str/includes? (str (try (feed/check-recipe!
                                  (assoc feed/default-recipe
                                         :crown-rank {:fresh 101}))
                                 (catch Exception e (ex-message e))))
                       "0–100")))
  (testing "the diff says what moved, in the household's words, beside the
            contest's own"
    (is (= [] (feed/crown-rank-diff nil {:declared 10})))
    (is (= ["In the crown, serving a value this house declared lifts a bundle 20 instead of 10."]
           (feed/crown-rank-diff nil {:declared 20})))
    (is (str/includes? (first (feed/crown-rank-diff
                               nil {:declared 0 :cooled 0 :declined 0 :fresh 0 :early 0 :judged 0}))
                       "turns OFF"))
    (is (= ["In the crown, each day early a recomposition arrives — before the day the house said it would hear that line of thinking again — holds it 5 instead of 2."]
           (feed/crown-rank-diff nil {:early 5})))
    (is (= ["In the crown, an agent's own score of a bundle — 0 to 1, with one sentence, quoted on the card as the agent's — moves it up to 3 either way instead of 1."]
           (feed/crown-rank-diff nil {:judged 3})))
    (is (= ["The order itself is unchanged, line for line."
            "In the crown, each day left on a bundle's week lifts it 3 instead of 1."]
           (feed/recipe-diff {:order (:order feed/default-recipe)}
                             {:order (:order feed/default-recipe)
                              :crown-rank {:fresh 3}}))))
  (testing "the household writes its own four numbers on the recipe row and the
            next read is answered by them, and turning them off says so"
    (let [eng (boot-house)]
      (post! eng "fd_errands" {:title "one"})
      (let [before (:doc (feed! eng))
            made (call! eng :post "/api/feed_recipes"
                        :body {:label "Declared values matter more"
                               :scope "household"
                               :order (get-in before [:recipe :order])
                               :crown_rank {:declared 40 :fresh 0}})
            after (:doc (feed! eng))]
        (is (= 201 (:status made)) (pr-str (:doc made)))
        (is (= {:declared 40 :cooled 2 :declined 2 :fresh 0 :early 2 :judged 1}
               (get-in after [:recipe :crown_rank])))
        (is (str/includes? (get-in after [:recipe :crown_rank_says])
                           "lifts a bundle 40"))
        (is (= (get-in before [:recipe :formula])
               (get-in after [:recipe :formula]))
            "the contest's two numbers are untouched by a row that named only
             the crown's")
        (let [rid (last (str/split (str (:self (:doc made))) #"/"))
              revised (call! eng :post (str "/api/feed_recipes/" rid "/-/revise")
                             :body {:label "The seed alone"
                                    :order (get-in before [:recipe :order])
                                    :crown_rank {:declared 0 :cooled 0
                                                 :declined 0 :fresh 0
                                                 :early 0 :judged 0}}
                             :headers {"if-match" (get-in made [:doc :meta :etag])})
              off (:doc (feed! eng))]
          (is (= 200 (:status revised)) (pr-str (:doc revised)))
          (is (str/includes? (get-in off [:recipe :crown_rank_says])
                             "The crown's rank is off")))
        ;; …and with only the agent's number at 0 (waymark-1uv.6) the
        ;; sentence counts five and says nothing about a judgment
        ;; nobody weighs — the member's own row, read ahead of the
        ;; household's
        (let [quiet (call! eng :post "/api/feed_recipes"
                           :body {:label "No agent's word"
                                  :scope "mine"
                                  :order (get-in before [:recipe :order])
                                  :crown_rank {:judged 0}})
              said (get-in (:doc (feed! eng)) [:recipe :crown_rank_says])]
          (is (= 201 (:status quiet)) (pr-str (:doc quiet)))
          (is (str/includes? said "five numbers a person can read"))
          (is (not (str/includes? said "may score it"))))))))
