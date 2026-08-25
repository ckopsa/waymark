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
         own feed until it liked the order")))

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
