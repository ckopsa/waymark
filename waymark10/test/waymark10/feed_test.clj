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
                         :resources [errand parcel]}
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
