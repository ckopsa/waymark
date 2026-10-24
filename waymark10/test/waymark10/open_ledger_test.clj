(ns waymark10.open-ledger-test
  "The opening (docs/spec-spend.md §8, spend step 4): a sitting billed
  before the ledger is posted onto it once, unchanged and dated when it
  was spent, its counters seed `usage_by_session` so the next report
  posts only what grew, the sitting leg of [balance-drift] holds
  `cost_usd` to its postings, and the agreement check lists each seat
  whose wall and ledger differ, with the sittings that cause it.

  Real Postgres. Needs the test database; WAYMARK10_TEST_DSN overrides
  the DSN."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.live-cracks :as live]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private tables
  ["domains" "seats" "models" "sittings" "grants" "schedules"
   "approval_requests" "members" "roles" "definitions" "capabilities"
   "postings" "leases"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"])

(def ^:dynamic *eng* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*eng* (engine/engine {:storage st :resources []})]
          (f))
        (finally (pg/close! st))))))

(def ^:private model-name "opening-economy")

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))
(def ^:private clerk
  (t/principal {:id "opening-clerk" :type :agent :display "Clerk"
                :model model-name}))

(defn- row-of [kind id]
  (let [rdef (get (inv/resources *eng*) kind)]
    (some->> (store/with-tx (:storage *eng*)
               (fn [tx] (store/load-row (:storage *eng*) tx kind (str id) {})))
             (inv/decode-row rdef))))

(defn- refusal [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d) d (throw e))))))

(defn- the-model []
  (or (first (store/with-tx (:storage *eng*)
               (fn [tx] (store/query-rows (:storage *eng*) tx :model
                                          {:name model-name} {:limit 1}))))
      (:row (inv/create! *eng* :model
                         {:name model-name :display model-name
                          :vendor "anthropic" :tier "economy"
                          :price_input_per_mtok 1M
                          :price_output_per_mtok 5M
                          :price_cache_read_per_mtok 0.1M
                          :price_cache_write_per_mtok 1.25M}
                         {:principal colton}))))

(defn- sit!
  "The sitter asks to sit in the office by name and a person approves.
  → the grant's id."
  [seat-name]
  (let [ask (:row (inv/create! *eng* :approval_request
                               {:task "Walk this seat's rows." :seat seat-name}
                               {:principal clerk}))]
    (grants/approval-effects!
     *eng* (get (inv/resources *eng*) :approval_request) :approve
     (inv/invoke! *eng* :approval_request (:id ask) :approve nil
                  {:principal colton}))
    (get-in (row-of :approval_request (:id ask)) [:data :grant_id])))

(defn- sitting!
  "An open sitting of a fresh seat in a fresh domain. → {:sitting :seat :domain}."
  [seat-name]
  (let [domain (:id (:row (inv/create! *eng* :domain
                                       {:name (str seat-name "-house")
                                        :charter "Keep the house running."
                                        :budget_usd_per_week 40M}
                                       {:principal colton})))
        seat (:row (inv/create! *eng* :seat
                                {:name seat-name
                                 :charter "Decide whether a message asks something."
                                 :scope [{:kind "model" :actions ["retire"]}]
                                 :standing_ttl_seconds 604800
                                 :cadence_seconds 3600
                                 :budget_usd_per_week 5M
                                 :sitting_budget_tokens 60000
                                 :domain (str domain)}
                                {:principal colton}))
        grant (sit! seat-name)
        row (:row (inv/create! *eng* :sitting
                               {:seat (:id seat) :model (:id (the-model))
                                :grant (str grant)}
                               {:principal clerk}))]
    {:sitting (:id row) :seat (:id seat) :domain domain}))

(defn- as-old!
  "Make the sitting a row billed before the ledger: `changes` written
  over it, with no usage report and nothing that rests on one. A
  maintenance write, as the rows the opening finds were."
  [id changes]
  (let [st (:storage *eng*)]
    (store/with-tx st
      (fn [tx]
        (let [raw (store/load-row st tx :sitting (str id) {:for-update true})]
          (store/update-data! st tx :sitting (str id)
                              (merge (dissoc (:data raw) :usage_by_session
                                             :last_report :bill_basis)
                                     changes)
                              nil))))))

(defn- postings-of [id]
  (let [rdef (get (inv/resources *eng*) :posting)]
    (->> (store/with-tx (:storage *eng*)
           (fn [tx] (store/query-rows (:storage *eng*) tx :posting
                                      {:source_id (str id)} {:limit 100})))
         (map #(:data (inv/decode-row rdef %))))))

(defn- dollars [posting]
  (bigdec (str (get-in posting [:amount :amount]))))

(defn- sum-of [ps kind]
  (reduce + 0M (map dollars (filter #(= kind (:account_kind %)) ps))))

(defn- same? [a b] (zero? (compare (bigdec (str a)) (bigdec (str b)))))

(defn- counts [session in]
  {:harness_session session :input_tokens in :output_tokens 0
   :cache_read_tokens 0 :cache_write_tokens 0})

(deftest an-old-bill-is-opened-once-and-dated-when-it-was-spent
  (let [{:keys [sitting seat domain]} (sitting! "opening-once")]
    (as-old! sitting {:cost_usd "2.5" :input_tokens 1000000 :output_tokens 300000
                      :cache_read_tokens 0 :cache_write_tokens 0 :turns 4
                      :harness_session "h1"
                      :tallied_at "2026-09-30T10:00:00Z"
                      :ended_at "2026-10-01T10:00:00Z"})
    (is (pos? (seats/open-ledger! *eng*)))
    (let [ps (postings-of sitting)
          row (row-of :sitting sitting)]
      (testing "the cost is posted unchanged to the seat's spend and the domain's"
        (is (= 2 (count ps)))
        (is (same? 2.5M (sum-of ps "seat")))
        (is (same? 2.5M (sum-of ps "domain")))
        (is (some #(= (str "seat/" seat "/spend") (:account %)) ps))
        (is (some #(= (str "domain/" domain "/spend") (:account %)) ps))
        (is (every? #(= "opening" (:basis %)) ps)))
      (testing "each posting counts at the sitting's end"
        (is (every? #(= "2026-10-01T10:00:00Z" (str (:effective_at %))) ps)))
      (testing "the counters seed usage_by_session under the row's session"
        (is (= 1000000 (get-in row [:data :usage_by_session :h1 :input_tokens])))
        (is (same? 2.5M (get-in row [:data :usage_by_session :h1 :posted_usd])))
        (is (same? 2.5M (get-in row [:data :cost_usd])))
        (is (= "opening" (get-in row [:data :bill_basis])))))
    (testing "run twice, it posts once"
      (is (= 0 (seats/open-ledger! *eng*)))
      (is (= 2 (count (postings-of sitting))))
      ;; the door itself, walked again by the engine's hand, posts nothing
      (refusal #(inv/invoke! *eng* :sitting (str sitting) :open_ledger
                             {:basis "opening"}
                             {:principal seats/seats-actor}))
      (is (= 2 (count (postings-of sitting)))))
    (testing "no hand at the wire opens a bill"
      (let [fresh (:sitting (sitting! "opening-by-hand"))]
        (as-old! fresh {:cost_usd "1"})
        (is (some? (refusal #(inv/invoke! *eng* :sitting (str fresh) :open_ledger
                                          {:basis "opening"} {:principal colton}))))))
    (testing "the first real report after it posts only what is new"
      (inv/invoke! *eng* :sitting (str sitting) :report_usage
                   (assoc (counts "h1" 3000000) :output_tokens 300000)
                   {:principal clerk})
      (let [ps (postings-of sitting)]
        (is (same? 4.5M (sum-of ps "seat")))
        (is (same? 4.5M (get-in (row-of :sitting sitting) [:data :cost_usd])))))))

(deftest effective-at-is-the-end-else-the-tally-else-the-start
  (let [tallied (:sitting (sitting! "opening-tallied"))
        started (:sitting (sitting! "opening-started"))]
    (as-old! tallied {:cost_usd "1" :tallied_at "2026-09-30T10:00:00Z"})
    (as-old! started {:cost_usd "1" :started_at "2026-09-29T10:00:00Z"})
    (seats/open-ledger! *eng*)
    (testing "no end: the last tally"
      (is (every? #(= "2026-09-30T10:00:00Z" (str (:effective_at %)))
                  (postings-of tallied))))
    (testing "no end and no tally: the start"
      (is (every? #(= "2026-09-29T10:00:00Z" (str (:effective_at %)))
                  (postings-of started))))
    (testing "a session-less row seeds the opening's own line"
      (is (some? (get-in (row-of :sitting started) [:data :usage_by_session :opening]))))))

(deftest the-sitting-leg-holds-cost-usd-to-its-postings
  (let [opened (:sitting (sitting! "opening-drift"))
        waiting (:sitting (sitting! "opening-waiting"))]
    (as-old! opened {:cost_usd "2" :ended_at "2026-10-01T10:00:00Z"})
    (seats/open-ledger! *eng*)
    (testing "an opened sitting does not drift"
      (is (not-any? #(= (str opened) (:id %)) (live/balance-drift! *eng*))))
    (let [st (:storage *eng*)]
      (store/with-tx st
        (fn [tx]
          (let [raw (store/load-row st tx :sitting (str opened) {:for-update true})]
            (store/update-data! st tx :sitting (str opened)
                                (assoc (:data raw) :cost_usd "9") nil)))))
    (as-old! waiting {:cost_usd "3"})
    (let [cs (live/balance-drift! *eng*)]
      (testing "a cost_usd off its postings is a crack, rewritten from them"
        (is (some #(and (= (str opened) (:id %)) (= "balance_drift" (:crack %))) cs))
        (is (same? 2 (get-in (row-of :sitting opened) [:data :cost_usd]))))
      (testing "a sitting not yet opened is not judged"
        (is (not-any? #(= (str waiting) (:id %)) cs))))
    (testing "once rewritten, the leg is clean"
      (is (not-any? #(= (str opened) (:id %)) (live/balance-drift! *eng*))))))

(deftest the-agreement-check-lists-each-difference-with-its-cause
  (let [now (Instant/now)
        early (sitting! "opening-early")
        agreed (sitting! "opening-agreed")]
    ;; started before the window, ended inside it: the wall misses it,
    ;; the ledger counts it
    (as-old! (:sitting early) {:cost_usd "1"
                               :started_at (str (.minusSeconds now (* 8 86400)))
                               :ended_at (str (.minusSeconds now 86400))})
    (seats/open-ledger! *eng*)
    (inv/invoke! *eng* :sitting (str (:sitting agreed)) :report_usage
                 (counts "a1" 1000000) {:principal clerk})
    (let [report (seats/ledger-agreement *eng* now)
          entry (first (filter #(= (str (:seat early)) (:seat %)) report))]
      (testing "the seat whose numbers differ is listed, with the sitting that causes it"
        (is (some? entry))
        (is (same? 0 (:wall entry)))
        (is (same? 1 (:ledger entry)))
        (is (= [(str (:sitting early))] (mapv :id (:sittings entry)))))
      (testing "a seat whose wall and ledger agree is not listed"
        (is (not-any? #(= (str (:seat agreed)) (:seat %)) report))))))
