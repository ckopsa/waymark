(ns waymark10.report-usage-test
  "A sitting's usage report (docs/spec-spend.md §3, spend step 3c):
  cumulative counts in, the price of what grew out, posted to the
  seat's and the domain's spend with basis reported. A repeat is no
  move, a decrease is refused, two sessions post their own, an ended
  sitting still posts, and tally and close go through the same report.

  Real Postgres. Needs the test database; WAYMARK10_TEST_DSN overrides
  the DSN."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

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

(def ^:private model-name "report-economy")

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))
(def ^:private clerk
  (t/principal {:id "report-clerk" :type :agent :display "Clerk"
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

(defn- open-seat! [name' extra]
  (:row (inv/create! *eng* :seat
                     (merge {:name name'
                             :charter "Decide whether a message asks something."
                             :scope [{:kind "model" :actions ["retire"]}]
                             :standing_ttl_seconds 604800
                             :cadence_seconds 3600
                             :budget_usd_per_week 5M
                             :sitting_budget_tokens 60000}
                            extra)
                     {:principal colton})))

(defn- sit!
  "The sitter asks to sit in the office by name, a person approves, and
  the minted grant cites the seat. → the grant's id."
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
  "An open sitting of a fresh seat; `domain?` puts the seat in a domain.
  → {:sitting :seat :domain}."
  [seat-name domain?]
  (let [domain (when domain?
                 (:id (:row (inv/create! *eng* :domain
                                         {:name (str seat-name "-house")
                                          :charter "Keep the house running."
                                          :budget_usd_per_week 40M}
                                         {:principal colton}))))
        seat (open-seat! seat-name (cond-> {} domain (assoc :domain (str domain))))
        grant (sit! seat-name)
        row (:row (inv/create! *eng* :sitting
                               {:seat (:id seat) :model (:id (the-model))
                                :grant (str grant)}
                               {:principal clerk}))]
    {:sitting (:id row) :seat (:id seat) :domain domain}))

(defn- counts [session in & {:as more}]
  (merge {:harness_session session :input_tokens in :output_tokens 0
          :cache_read_tokens 0 :cache_write_tokens 0}
         more))

(defn- report! [id body]
  (inv/invoke! *eng* :sitting (str id) :report_usage body {:principal clerk}))

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

(defn- transitions-of [id]
  (count (store/with-tx (:storage *eng*)
           (fn [tx] (store/transitions (:storage *eng*) tx
                                       {:kind :sitting :resource-id id} {})))))

(defn- same? [a b] (zero? (compare (bigdec a) (bigdec b))))

(deftest cumulative-reports-post-only-their-deltas
  (let [{:keys [sitting seat domain]} (sitting! "report-deltas" true)]
    (report! sitting (counts "s1" 1000000))
    (let [ps (postings-of sitting)
          row (row-of :sitting sitting)]
      (testing "the first report posts its whole price to the seat and the domain"
        (is (same? 1 (sum-of ps "seat")))
        (is (same? 1 (sum-of ps "domain")))
        (is (every? #(= "reported" (:basis %)) ps))
        (is (some #(str/includes? (:account %) (str seat)) ps))
        (is (some #(str/includes? (:account %) (str domain)) ps)))
      (testing "last_report names the posted amount, cost_usd caches it"
        (is (same? 1 (get-in row [:data :last_report :delta_usd])))
        (is (same? 1 (get-in row [:data :cost_usd])))
        (is (= "reported" (get-in row [:data :bill_basis])))))
    (testing "a repeat is no move: no log row and no posting"
      (let [before (transitions-of sitting)]
        (report! sitting (counts "s1" 1000000))
        (is (= before (transitions-of sitting)))
        (is (= 2 (count (postings-of sitting))))))
    (testing "growth posts only the growth"
      (report! sitting (counts "s1" 3000000))
      (is (same? 3 (sum-of (postings-of sitting) "seat")))
      (is (same? 2 (get-in (row-of :sitting sitting) [:data :last_report :delta_usd]))))
    (testing "a decrease is refused, naming the field and the last value"
      (let [d (refusal #(report! sitting (counts "s1" 2000000)))]
        (is (some? d))
        (is (str/includes? (pr-str d) "input_tokens"))
        (is (str/includes? (pr-str d) "3000000"))))
    (testing "a second session posts its own delta"
      (report! sitting (counts "s2" 500000))
      (is (same? 3.5M (sum-of (postings-of sitting) "seat")))
      (is (same? 3.5M (get-in (row-of :sitting sitting) [:data :cost_usd]))))
    (testing "a reprice moves no posted dollar"
      (let [model (the-model)]
        (inv/invoke! *eng* :model (str (:id model)) :reprice
                     {:price_input_per_mtok 10M :price_output_per_mtok 5M
                      :price_cache_read_per_mtok 0.1M :price_cache_write_per_mtok 1.25M}
                     {:principal colton
                      :if-match (inv/etag :model (str (:id model)) (:version model))})
        (is (same? 3.5M (sum-of (postings-of sitting) "seat")))
        (report! sitting (counts "s2" 600000))
        (is (same? 4.5M (sum-of (postings-of sitting) "seat"))
            "only the new tokens, at the new price")
        (inv/invoke! *eng* :model (str (:id model)) :reprice
                     {:price_input_per_mtok 1M :price_output_per_mtok 5M
                      :price_cache_read_per_mtok 0.1M :price_cache_write_per_mtok 1.25M}
                     {:principal colton
                      :if-match (inv/etag :model (str (:id model))
                                          (:version (row-of :model (:id model))))})))))

(deftest a-domainless-seat-posts-once
  (let [{:keys [sitting]} (sitting! "report-bare" false)]
    (report! sitting (counts "s1" 1000000))
    (let [ps (postings-of sitting)]
      (is (= 1 (count ps)))
      (is (= "seat" (:account_kind (first ps)))))))

(deftest tally-and-close-go-through-the-report
  (let [{:keys [sitting]} (sitting! "report-close" true)
        body {:input_tokens 1000000 :output_tokens 0 :cache_read_tokens 0
              :cache_write_tokens 0 :turns 1 :harness_session "s1"}]
    (inv/invoke! *eng* :sitting (str sitting) :tally body {:principal clerk})
    (is (same? 1 (sum-of (postings-of sitting) "seat")))
    (inv/invoke! *eng* :sitting (str sitting) :close
                 (assoc body :input_tokens 2000000) {:principal clerk})
    (let [row (row-of :sitting sitting)]
      (is (= "closed" (name (:state row))))
      (is (same? 2 (sum-of (postings-of sitting) "seat")))
      (is (same? 2 (get-in row [:data :cost_usd])))
      (is (nil? (get-in row [:data :prices])) "the close no longer copies prices"))
    (testing "a report on a closed sitting posts without moving its state"
      (report! sitting (counts "s1" 2500000))
      (let [row (row-of :sitting sitting)]
        (is (= "closed" (name (:state row))))
        (is (same? 2.5M (sum-of (postings-of sitting) "seat")))
        (is (same? 2.5M (get-in row [:data :cost_usd])))))))
