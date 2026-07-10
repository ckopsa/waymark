(ns waymark10.batch-f-gin-test
  "Batch F, deliverable 1: GIN indexes on vocab arrays. The
  kind-projection carries a GIN entry per vector-typed :waymark/vocab
  filterable field, the DDL renders it, the migrate planner
  reconciles it (add on promotion, drop on retirement), and a vocab
  membership filter actually WALKS it — EXPLAIN over a seeded table
  names the index. Needs the batch-F test database
  (WAYMARK10_TEST_DSN)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.migrate :as migrate]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world: a kind with a vocab-array field, in two editions ─────

(defn- gadget-map [tagged?]
  {:kind :f_gadget
   :plural "f_gadgets"
   :states [:open :done]
   :initial :open
   :terminal #{:done}
   :summary "{data.name} · {state}"
   :schema (into [:map [:name [:string {:min 1 :max 60}]]]
                 (when tagged?
                   [[:tags [:vector [:waymark/vocab {:open true}]]]]))
   ;; tagged?: :tags self-merges into :filterable via the vocab rule
   :actions
   {:finish {:from #{:open} :to :done
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Done is done."}}}})

(def ^:private gadget-bare (r/resource (gadget-map false)))
(def ^:private gadget-tagged (r/resource (gadget-map true)))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- with-st [f]
  (let [st (pg/storage db/dsn)]
    (try (f st) (finally (pg/close! st)))))

(defn- fresh! []
  (with-st
    (fn [st]
      (store/with-tx st
        (fn [tx]
          (doseq [table ["f_gadgets" "waymark10_transitions"
                         "waymark10_idempotency" "waymark10_drafts"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")])))))))

;; ── the projection and the DDL ──────────────────────────────────────

(deftest projection-carries-the-gin-entry
  (testing "a vocab-array filterable field projects a GIN index"
    (let [{:keys [indexes columns]} (store/kind-projection gadget-tagged)]
      (is (contains? indexes "ix_f_gadgets_tags_gin"))
      (is (str/includes? (get indexes "ix_f_gadgets_tags_gin")
                         "USING gin ((data->'tags'))"))
      (testing "…and still no single-value promotion"
        (is (not-any? #(= "f_tags" (:name %)) columns)))))
  (testing "the DDL renders it beside the standard indexes"
    (is (some #(str/includes? % "ix_f_gadgets_tags_gin")
              (pg/kind-ddl gadget-tagged))))
  (testing "an untagged kind projects no GIN entry"
    (is (not-any? #(str/includes? % "_gin")
                  (keys (:indexes (store/kind-projection gadget-bare)))))))

;; ── the migrate planner reconciles it ───────────────────────────────

(deftest migrate-adds-and-drops-the-gin-index
  (fresh!)
  ;; boot the bare edition: the live table has no GIN index
  (with-st
    (fn [st]
      (let [eng (inv/engine {:storage st :resources [gadget-bare]})]
        (inv/create! eng :f_gadget {:name "g1"} {:principal elena}))))
  (testing "promoting the vocab array plans exactly the add-column +
            add-index (no generated column exists for an array)"
    (with-st
      (fn [st]
        (let [steps (migrate/plan st [gadget-tagged])
              by-kind (group-by :kind steps)]
          (is (contains? (set (map :kind steps)) :add-index))
          (is (some #(str/includes? (:sql %) "ix_f_gadgets_tags_gin")
                    (:add-index by-kind)))
          (is (every? (comp false? :destructive?) steps))
          (migrate/apply! st steps {})
          (is (= [] (migrate/plan st [gadget-tagged])) "applied means drift-free")
          (is (contains? (:indexes (pg/table-snapshot st "f_gadgets"))
                         "ix_f_gadgets_tags_gin"))))))
  (testing "retiring the field drops the index (derived by construction)"
    (with-st
      (fn [st]
        (let [steps (migrate/plan st [gadget-bare])
              drops (filter #(= :drop-index (:kind %)) steps)]
          (is (some #(str/includes? (:sql %) "ix_f_gadgets_tags_gin") drops))
          (migrate/apply! st steps {})
          (is (not (contains? (:indexes (pg/table-snapshot st "f_gadgets"))
                              "ix_f_gadgets_tags_gin"))))))))

;; ── the filter walks the index ──────────────────────────────────────

(deftest vocab-membership-filter-uses-the-gin-index
  (fresh!)
  (with-st
    (fn [st]
      (let [eng (inv/engine {:storage st :resources [gadget-tagged]})]
        (doseq [i (range 50)]
          (inv/create! eng :f_gadget
                       {:name (str "g" i)
                        :tags [(str "tag-" (mod i 10)) "common"]}
                       {:principal elena}))
        (testing "the engine's own path answers the membership filter"
          (let [rows (store/with-tx st
                       (fn [tx]
                         (store/search-rows st tx :f_gadget
                                            [{:target :data :field :tags
                                              :op :in-any
                                              :values ["tag-3"]}]
                                            {})))]
            (is (= 5 (count rows)))
            (is (every? #(some #{"tag-3"} (get-in % [:data :tags])) rows))))
        (testing "EXPLAIN shows the index on the same operator the
                  storage layer spells (?|)"
          (let [plan (store/with-tx st
                       (fn [tx]
                         ;; a 50-row table seq-scans by cost; forcing the
                         ;; planner's hand proves the index is USABLE by
                         ;; the filter's operator — the loose assertion
                         (jdbc/execute! tx ["SET LOCAL enable_seqscan = off"])
                         (->> (jdbc/execute!
                               tx
                               ["EXPLAIN SELECT * FROM f_gadgets WHERE data->'tags' ??| ARRAY[?]"
                                "tag-3"]
                               {:builder-fn rs/as-unqualified-maps})
                              (map (comp str vals))
                              (str/join "\n"))))]
            (is (str/includes? plan "ix_f_gadgets_tags_gin")
                (str "expected the GIN index in the plan:\n" plan))))))))
