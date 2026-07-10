(ns waymark10.batch-e-predecessor-test
  "The predecessor resolver (batch E, design E7): a :waymark/ref entry
  declaring {:predecessor {:order … :partition …}} resolves at create
  to the newest existing sibling — spec extraction pure, resolution
  against a real engine (partitions respected, a supplied value wins,
  ≤-seeding links backward never forward, id tie-break deterministic),
  and the recorded refusals loud. Needs the batch-E database:
  WAYMARK10_TEST_DSN=…waymark10_ext_test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.predecessor :as pred]
            [waymark10.server.store :as store]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ana (t/principal {:id "ana" :type :human :display "Ana"}))

;; ── the chained kind: partitioned periods ───────────────────────────

(def period
  (r/resource
   {:kind :period
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:min 1 :max 60}]]
             [:ledger [:string {:min 1 :max 60}]]
             [:starts_on :waymark/date]
             [:previous_period
              {:optional true :kind :period
               :predecessor {:order :starts_on :partition :ledger}}
              [:maybe :waymark/ref]]]
    :filterable {:state #{:eq} :ledger #{:eq} :starts_on #{:eq :range}}
    :sortable {:fields [:starts_on] :default "-starts_on"}
    :actions
    {:close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A closed period stays readable as history."}}}}))

(defn- create! [eng body]
  (:row (inv/create! eng :period body {:principal ana})))

;; ── the pure surface ────────────────────────────────────────────────

(deftest specs-read-the-declaration
  (is (= {:previous_period {:kind :period
                            :order :starts_on
                            :partition :ledger}}
         (pred/specs (:schema period)))
      "the entry property is the whole declaration")
  (is (= {} (pred/specs [:map [:name [:string {:max 10}]]]))
      "no predecessor entries, no specs")
  (is (= {} (pred/specs [:map [:other_id {:kind :period
                                          :predecessor {:order :starts_on}}
                               [:maybe :int]]]))
      "a predecessor property on a non-ref entry is ignored"))

;; ── resolution against the engine ───────────────────────────────────

(deftest predecessor-resolves-at-create
  (db/with-test-engine
    [period]
    (fn [eng]
      (let [p1 (create! eng {:name "July I" :ledger "family"
                             :starts_on "2026-07-01"})]
        (testing "the first row of its partition links nothing"
          (is (nil? (get-in p1 [:data :previous_period]))))

        (testing "the second links the first — the newest sibling by order"
          (let [p2 (create! eng {:name "July II" :ledger "family"
                                 :starts_on "2026-07-15"})]
            (is (= (:id p1) (get-in p2 [:data :previous_period])))

            (testing "the third links the second, not the first"
              (let [p3 (create! eng {:name "August" :ledger "family"
                                     :starts_on "2026-08-01"})]
                (is (= (:id p2) (get-in p3 [:data :previous_period])))))))

        (testing "partitions are respected — another ledger starts fresh"
          (let [q1 (create! eng {:name "Biz July" :ledger "business"
                                 :starts_on "2026-07-10"})]
            (is (nil? (get-in q1 [:data :previous_period]))
                "family periods are not this ledger's siblings")
            (let [q2 (create! eng {:name "Biz August" :ledger "business"
                                   :starts_on "2026-08-10"})]
              (is (= (:id q1) (get-in q2 [:data :previous_period]))))))

        (testing "a backdated row links backward, never forward (≤-seeding)"
          (let [p0 (create! eng {:name "June" :ledger "family"
                                 :starts_on "2026-06-01"})]
            (is (nil? (get-in p0 [:data :previous_period]))
                "nothing starts at or before June — later rows never qualify")))

        (testing "an explicit body value wins over resolution"
          (let [px (create! eng {:name "Hand-linked" :ledger "family"
                                 :starts_on "2026-09-01"
                                 :previous_period "chosen-by-hand"})]
            (is (= "chosen-by-hand" (get-in px [:data :previous_period])))))))))

(deftest order-ties-break-deterministically
  (db/with-test-engine
    [period]
    (fn [eng]
      (let [a (create! eng {:name "Twin A" :ledger "tie"
                            :starts_on "2026-07-01"})
            b (create! eng {:name "Twin B" :ledger "tie"
                            :starts_on "2026-07-01"})
            c (create! eng {:name "After" :ledger "tie"
                            :starts_on "2026-07-02"})]
        ;; Twin B links Twin A (its only earlier-or-equal sibling);
        ;; the follower ties break toward the smallest id — the
        ;; search-rows id tiebreak, so resolution never flaps
        (is (= (:id a) (get-in b [:data :previous_period])))
        (is (= (first (sort [(:id a) (:id b)]))
               (get-in c [:data :previous_period])))))))

;; ── the recorded refusals ───────────────────────────────────────────

(deftest unpromoted-order-refuses-loudly
  (db/with-test-engine
    [period]
    (fn [eng]
      ;; a hand-built rdef whose :order the target never promoted —
      ;; the resolver refuses with the waymark9 _check_predecessor
      ;; sentence instead of a bare SQL error over a missing column
      (let [bad {:kind :period
                 :schema [:map
                          [:name [:string {:max 60}]]
                          [:previous_period
                           {:optional true :kind :period
                            :predecessor {:order :name}}
                           [:maybe :waymark/ref]]]}
            e (try
                (store/with-tx (:storage eng)
                  (fn [tx]
                    (pred/resolve! (:storage eng) tx {:period period} bad
                                   {:data {:name "x"}})))
                nil
                (catch Exception e e))]
        (is (some? e))
        (is (str/includes? (ex-message e)
                           "must be filterable or sortable on period"))))))

(deftest blank-partition-value-resolves-nothing
  (db/with-test-engine
    [period]
    (fn [eng]
      (create! eng {:name "Seed" :ledger "family" :starts_on "2026-07-01"})
      ;; half a partition key must not link across partitions: resolve!
      ;; with a row whose :ledger is blank leaves the ref blank
      (let [row (store/with-tx (:storage eng)
                  (fn [tx]
                    (pred/resolve! (:storage eng) tx {:period period} period
                                   {:data {:name "no-ledger"}})))]
        (is (nil? (get-in row [:data :previous_period])))))))
