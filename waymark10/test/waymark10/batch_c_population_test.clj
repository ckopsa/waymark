(ns waymark10.batch-c-population-test
  "Batch C, the population grammar: pilot's where= validates against
  the target kind's filter grammar through the collections parser —
  unknown fields, malformed values, unknown state tokens, range
  suffixes and multi-values refuse by sentence; a filterable equality
  pilots. Suite-local kind; real Postgres."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["c_pop_accts" "definitions" "waymark10_transitions"
   "waymark10_idempotency" "waymark10_observations"])

(defn- fresh! []
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st)))))

(defn- with-eng [resources mode f]
  (let [st (pg/storage db/dsn)]
    (try
      (f (engine/engine {:storage st :resources resources :deploy-mode mode}))
      (finally (pg/close! st)))))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- acct [law]
  (r/resource
   {:kind :c_pop_acct
    :plural "c_pop_accts"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:min 1 :max 40}]]
             [:balance {:filter #{:eq :range}} :int]
             [:flagged {:optional true} [:maybe :boolean]]]
    :derived {:flagged {:over [:balance] :expr law}}
    :actions {:close {:from #{:open} :to :closed
                      :safety {:idempotent true :reversible false
                               :confirm false
                               :one-way "Closed is history."}}}}))

(defn- def-row [eng kind rev]
  (first (filter #(= rev (get-in % [:data :revision]))
                 (store/with-tx (:storage eng)
                   (fn [tx] (store/query-rows (:storage eng) tx :definition
                                              {:target_kind (name kind)}
                                              {:limit 100}))))))

(defn- problem-of [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d) d (throw e))))))

(deftest pilot-population-speaks-the-filter-grammar
  (fresh!)
  (with-eng [(acct '(< 100 (var :balance)))] :promote
    (fn [eng]
      (inv/create! eng :c_pop_acct {:name "a" :balance 60}
                   {:principal elena})))
  (with-eng [(acct '(< 50 (var :balance)))] :propose
    (fn [eng]
      (let [d2 (def-row eng :c_pop_acct 2)
            pilot! (fn [where]
                     (problem-of
                      #(inv/invoke! eng :definition (:id d2) :pilot
                                    {:where where}
                                    {:principal elena
                                     :idempotency-key (str (random-uuid))})))]
        (testing "an unfilterable field refuses"
          (let [p (pilot! {:name "a"})]
            (is (= 409 (:status p)))
            (is (= :population-grammar (:guard p)))
            (is (str/includes? (:detail p) "unknown query parameter"))))
        (testing "a malformed value refuses with the field's sentence"
          (let [p (pilot! {:balance "sixty"})]
            (is (= :population-grammar (:guard p)))
            (is (str/includes? (:detail p) "must be an integer"))))
        (testing "an unknown state token refuses"
          (let [p (pilot! {:state "nope"})]
            (is (= :population-grammar (:guard p)))
            (is (str/includes? (:detail p) "is not a state"))))
        (testing "a range suffix has no restamp meaning"
          (let [p (pilot! {:balance_gte 5})]
            (is (= :population-grammar (:guard p)))
            (is (str/includes? (:detail p) "range"))))
        (testing "a population pins one value per field"
          (let [p (pilot! {:balance [60 70]})]
            (is (= :population-grammar (:guard p)))
            (is (str/includes? (:detail p) "pins one value"))))
        (testing "a filterable equality pilots — and a state token does"
          (is (nil? (pilot! {:balance 60})))
          (is (= :piloted (:state (def-row eng :c_pop_acct 2)))))))))
