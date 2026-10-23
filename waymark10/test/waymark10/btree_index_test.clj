(ns waymark10.btree-index-test
  "Ticket 68ebaa0f: a field filterable by :eq carries a btree index
  over its promoted column unless its :index says :none, and the
  postgres filter reads that column — so EXPLAIN over the engine's own
  born_from filter, on a change-shaped kind, names the index. Needs
  the test database (WAYMARK10_TEST_DSN)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world: a change with a born_from, in two editions ─────────────

(defn- change-map [index]
  (cond-> {:kind :bt_change
           :plural "bt_changes"
           :states [:open :closed]
           :initial :open
           :terminal #{:closed}
           :summary "{data.title} · {state}"
           :schema [:map
                    [:title [:string {:min 1 :max 60}]]
                    [:born_from {:optional true} [:maybe [:string {:max 80}]]]]
           :filterable {:state #{:eq :in} :born_from #{:eq}}
           :actions
           {:close {:from #{:open} :to :closed
                    :safety {:idempotent true :reversible false :confirm false
                             :one-way "Closed is closed."}}}}
    index (assoc :index index)))

(def ^:private change-indexed (r/resource (change-map nil)))
(def ^:private change-bare (r/resource (change-map {:born_from :none})))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- with-st [f]
  (let [st (pg/storage db/dsn)]
    (try (f st) (finally (pg/close! st)))))

(defn- fresh! []
  (with-st
    (fn [st]
      (store/with-tx st
        (fn [tx]
          (doseq [table ["bt_changes" "waymark10_transitions"
                         "waymark10_idempotency" "waymark10_drafts"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")])))))))

;; ── the projection ────────────────────────────────────────────────────

(deftest an-eq-field-projects-a-btree-index
  (is (= "CREATE INDEX IF NOT EXISTS ix_bt_changes_f_born_from ON bt_changes (f_born_from)"
         (get (:indexes (store/kind-projection change-indexed))
              "ix_bt_changes_f_born_from")))
  (testing ":index :none declines it, and the column still promotes"
    (let [{:keys [indexes columns]} (store/kind-projection change-bare)]
      (is (not (contains? indexes "ix_bt_changes_f_born_from")))
      (is (some #(= "f_born_from" (:name %)) columns)))))

;; ── the filter walks the index ──────────────────────────────────────

(deftest the-born-from-filter-uses-the-btree-index
  (fresh!)
  (with-st
    (fn [st]
      (let [eng (inv/engine {:storage st :resources [change-indexed]})
            c {:target :data :field :born_from :op := :value "ticket:7"}]
        (doseq [i (range 50)]
          (inv/create! eng :bt_change
                       {:title (str "c" i) :born_from (str "ticket:" i)}
                       {:principal elena}))
        (testing "the engine's own path answers the filter"
          (is (= ["c7"]
                 (mapv #(get-in % [:data :title])
                       (store/with-tx st
                         (fn [tx] (store/search-rows st tx :bt_change [c] {:all true})))))))
        (let [[sql params] (#'pg/cond-sql (get @(:promoted st) :bt_change) c)]
          (testing "the filter reads the promoted column"
            (is (str/starts-with? sql "f_born_from ") sql))
          (testing "EXPLAIN over the filter's own SQL names the index"
            (let [plan (store/with-tx st
                         (fn [tx]
                           ;; a 50-row table seq-scans by cost; forcing the
                           ;; planner's hand proves the index is USABLE by
                           ;; the filter as the store spells it
                           (jdbc/execute! tx ["SET LOCAL enable_seqscan = off"])
                           (->> (jdbc/execute!
                                 tx
                                 (into [(str "EXPLAIN SELECT * FROM bt_changes WHERE " sql)]
                                       params)
                                 {:builder-fn rs/as-unqualified-maps})
                                (map (comp str vals))
                                (str/join "\n"))))]
              (is (str/includes? plan "ix_bt_changes_f_born_from")
                  (str "expected the btree index in the plan:\n" plan)))))))))
