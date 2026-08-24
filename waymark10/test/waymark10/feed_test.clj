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
    :schema [:map [:title [:string {:min 1 :max 60}]]]
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Done is done."}}}}))

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
          (is (= [] (:heavier c)) "the ≤-selection partition is .3's")
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
                  (:notes (:doc scoped))))))))

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
