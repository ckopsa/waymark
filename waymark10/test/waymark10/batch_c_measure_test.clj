(ns waymark10.batch-c-measure-test
  "Batch C, blast radius: the definitions measure action computes,
  for a data-law diff, the per-fact flip counts — both laws evaluated
  over current data — scoped to the pilot's population when piloted,
  and refuses when the diff redefines no derived fact. Suite-local
  kind; real Postgres."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["c_m_accts" "definitions" "waymark10_transitions"
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

(defn- reload [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind id {}))))

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

;; ── fixtures ────────────────────────────────────────────────────────

(defn- acct
  "The measured kind: :flag-law is the derived expr (a data-law fact
  flip); :gate-law is a touch guard's expr (a judgment-only data-law
  flip — no derived fact moves)."
  [{:keys [flag-law gate-law]}]
  (r/resource
   {:kind :c_m_acct
    :plural "c_m_accts"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:min 1 :max 40}]]
             [:balance {:filter #{:eq}} :int]
             [:flagged {:optional true} [:maybe :boolean]]]
    :derived {:flagged {:over [:balance]
                        :expr (or flag-law '(< 100 (var :balance)))}}
    :actions {:touch {:from #{:open} :to :open
                      :guards [(g/expr {:name :within-bounds
                                        :when (or gate-law
                                                  '(< (data :balance) 1000))
                                        :explain "Out of bounds."})]
                      :safety {:idempotent true :reversible true
                               :confirm false}}
              :close {:from #{:open} :to :closed
                      :safety {:idempotent true :reversible false
                               :confirm false
                               :one-way "Closed is history."}}}}))

;; ── 1. measure on a held proposal: flips, full scan, sample ─────────

(deftest measure-reports-the-flip-counts
  (fresh!)
  (let [flip-id (atom nil)]
    (with-eng [(acct {})] :promote
      (fn [eng]
        (reset! flip-id
                (:id (:row (inv/create! eng :c_m_acct
                                        {:name "a" :balance 60}
                                        {:principal elena}))))
        (inv/create! eng :c_m_acct {:name "b" :balance 200}
                     {:principal elena})
        (inv/create! eng :c_m_acct {:name "c" :balance 10}
                     {:principal elena})))
    (with-eng [(acct {:flag-law '(< 50 (var :balance))})] :propose
      (fn [eng]
        (let [d2 (def-row eng :c_m_acct 2)]
          (is (= :proposed (:state d2)))
          (inv/invoke! eng :definition (:id d2) :measure nil
                       {:principal elena
                        :idempotency-key (str (random-uuid))})
          (let [m (get-in (reload eng :definition (:id d2))
                          [:data :measure])]
            (testing "the report: one redefined fact, 1 of 3 rows flips
                      (60 crosses 50 but not 100; 200 and 10 agree)"
              (is (= [{:fact "c_m_acct.flagged" :flips 1 :of 3
                       :sample [@flip-id]}]
                     (:facts m)))
              (is (= "full" (:scan m)))
              (is (nil? (:population m)))
              (is (= 1 (:from_revision m)))
              (is (= 2 (:to_revision m))))
            (testing "measuring is bookkeeping: the target rows did not move"
              (is (false? (get-in (reload eng :c_m_acct @flip-id)
                                  [:data :flagged]))))))))))

;; ── 2. piloted: the meter scopes to the population ──────────────────

(deftest measure-pilot-scopes-to-the-population
  (fresh!)
  (with-eng [(acct {})] :promote
    (fn [eng]
      (doseq [[n b] [["a" 60] ["b" 70] ["c" 200]]]
        (inv/create! eng :c_m_acct {:name n :balance b}
                     {:principal elena}))))
  (with-eng [(acct {:flag-law '(< 50 (var :balance))})] :propose
    (fn [eng]
      (let [d2 (def-row eng :c_m_acct 2)]
        (inv/invoke! eng :definition (:id d2) :pilot
                     {:where {:balance 60}}
                     {:principal elena
                      :idempotency-key (str (random-uuid))})
        (inv/invoke! eng :definition (:id d2) :measure_pilot nil
                     {:principal elena
                      :idempotency-key (str (random-uuid))})
        (let [m (get-in (reload eng :definition (:id d2)) [:data :measure])]
          (is (= {:balance 60} (:population m)))
          (is (= [1] (mapv :flips (:facts m))) "only the population's row")
          (is (= [1] (mapv :of (:facts m)))
              "of counts the population, not the kind"))))))

;; ── 3. a judgment-only data-law diff has no blast radius ────────────

(deftest measure-refuses-when-no-fact-is-redefined
  (fresh!)
  (with-eng [(acct {})] :promote
    (fn [eng]
      (inv/create! eng :c_m_acct {:name "a" :balance 60}
                   {:principal elena})))
  (with-eng [(acct {:gate-law '(< (data :balance) 2000)})] :propose
    (fn [eng]
      (let [d2 (def-row eng :c_m_acct 2)]
        (is (= "data_law" (get-in d2 [:data :diff_class]))
            "a guard expr leaf flip holds — but redefines no fact")
        (let [p (problem-of
                 #(inv/invoke! eng :definition (:id d2) :measure nil
                               {:principal elena
                                :idempotency-key (str (random-uuid))}))]
          (is (= 409 (:status p)))
          (is (= :redefines-derived-facts (:guard p)))
          (is (nil? (get-in (reload eng :definition (:id d2))
                            [:data :measure]))))))))
