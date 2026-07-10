(ns waymark10.batch-c-overlay-test
  "Batch C, the derived-law overlay — judgment's structural twin.
  Suite-local kinds against real Postgres: an expression fact whose
  law flips between revisions (c_acct), and an owns count whose
  where-filter flips (c_crate ← c_box). The acceptance is waymark9's
  v7 deviation dying for expr facts: a held proposal's rows and a
  grandfathered row's facts recompute under the ROW's law, and the
  promote's wired backfill repairs stale facts with no row writes.
  WAYMARK10_TEST_DSN overrides the DSN."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.derived :as derived]
            [waymark10.expr :as expr]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["c_accts" "c_crates" "c_boxes" "definitions"
   "waymark10_transitions" "waymark10_idempotency"
   "waymark10_observations"])

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

(defn- transition-count [eng kind id]
  (count (store/with-tx (:storage eng)
           (fn [tx] (store/transitions (:storage eng) tx
                                       {:kind kind :resource-id id} {})))))

;; ── fixtures ────────────────────────────────────────────────────────

(def ^:private v1-law '(< 100 (var :balance)))
(def ^:private v2-law '(< 50 (var :balance)))

(defn- acct [law & [{:keys [adoption]}]]
  (r/resource
   (cond-> {:kind :c_acct
            :plural "c_accts"
            :states [:open :closed]
            :initial :open
            :terminal #{:closed}
            :summary "{data.name} · {state}"
            :schema [:map
                     [:name [:string {:min 1 :max 40}]]
                     [:balance {:filter #{:eq}} :int]
                     [:flagged {:optional true} [:maybe :boolean]]]
            :derived {:flagged {:over [:balance] :expr law}}
            :actions {:touch {:from #{:open} :to :open
                              :safety {:idempotent true :reversible true
                                       :confirm false}}
                      :close {:from #{:open} :to :closed
                              :safety {:idempotent true :reversible false
                                       :confirm false
                                       :one-way "Closed is history."}}}}
     adoption (assoc :adoption adoption))))

(defn- crate [open-where]
  (r/resource
   {:kind :c_crate
    :plural "c_crates"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:open_boxes {:optional true} [:maybe :int]]]
    :owns [{:kind :c_box :via :crate_id}]
    :derived {:open_boxes {:count {:owns :c_box
                                   :where {:state open-where}}}}
    :actions {:finish {:from #{:open} :to :done
                       :safety {:idempotent true :reversible false
                                :confirm false
                                :one-way "Done is history."}}}}))

(def ^:private box
  (r/resource
   {:kind :c_box
    :plural "c_boxes"
    :states [:todo :done]
    :initial :todo
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:crate_id {:kind :c_crate} :waymark/ref]]
    :filterable {:crate_id #{:eq}}
    :actions {:finish {:from #{:todo} :to :done
                       :safety {:idempotent true :reversible false
                                :confirm false
                                :one-way "Done is history."}}}}))

;; ── 1. specs-under, the unit ────────────────────────────────────────

(deftest specs-under-substitutes-stored-trees
  (let [resident (acct v2-law)
        stored-fp (fp/fingerprint-of (acct v1-law))
        rd (assoc resident
                  :judgment-laws {1 stored-fp}
                  :judgment-cache (atom {}))]
    (testing "an overlaid revision evaluates ITS tree"
      (let [spec (get (derived/specs-under rd 1) :flagged)]
        (is (false? (expr/evaluate (:expr spec) {:vars {:balance 60}}))
            "v1 law: 60 does not exceed 100")
        (is (= [:balance] (:over spec)) "edge identity rides resident")))
    (testing "no entry → resident (the common case costs one lookup)"
      (let [spec (get (derived/specs-under rd 2) :flagged)]
        (is (true? (expr/evaluate (:expr spec) {:vars {:balance 60}}))))
      (is (identical? (:derived rd) (derived/specs-under rd nil))))
    (testing "the resolved map caches on :judgment-cache"
      (derived/specs-under rd 1)
      (is (contains? @(:judgment-cache rd)
                     [1 :waymark10.derived/specs])))))

;; ── 2. the hold is exact for derivations ────────────────────────────

(deftest hold-serves-the-current-law-for-facts
  (fresh!)
  (let [id (atom nil)]
    (with-eng [(acct v1-law)] :promote
      (fn [eng]
        (let [row (:row (inv/create! eng :c_acct {:name "a" :balance 60}
                                     {:principal elena}))]
          (reset! id (:id row))
          (is (false? (get-in row [:data :flagged])) "v1: 60 ≤ 100"))))
    ;; the deploy: v2 flips the expr leaf — data_law, held at proposed
    (with-eng [(acct v2-law)] :propose
      (fn [eng]
        (is (some? (:proposed-law (get (inv/resources eng) :c_acct)))
            "the diff held")
        (testing "a write on a held-law row materializes under the
                  STORED current trees, not the resident proposal —
                  the v7 deviation dies live"
          (let [{:keys [row]} (inv/invoke! eng :c_acct @id :touch nil
                                           {:principal elena})]
            (is (false? (get-in row [:data :flagged]))
                "resident v2 code says true; the row's law (v1) says false")
            (is (false? (get-in (reload eng :c_acct @id)
                                [:data :flagged])))))
        (testing "a create during the hold is stamped and judged current"
          (let [row (:row (inv/create! eng :c_acct {:name "b" :balance 60}
                                       {:principal elena}))]
            (is (= 1 (:law-revision row)))
            (is (false? (get-in row [:data :flagged])))))))))

;; ── 3. grandfathered rows keep their birth law; adopt flips ─────────

(deftest grandfathered-facts-recompute-under-birth-law
  (fresh!)
  (let [id (atom nil)]
    (with-eng [(acct v1-law {:adoption :never})] :promote
      (fn [eng]
        (reset! id (:id (:row (inv/create! eng :c_acct
                                           {:name "a" :balance 60}
                                           {:principal elena}))))))
    (with-eng [(acct v2-law {:adoption :never})] :promote
      (fn [eng]
        (testing "the promote grandfathered revision 1 (a surviving row)"
          (is (contains? (:judgment-laws (get (inv/resources eng) :c_acct))
                         1)))
        (testing "the wired backfill repaired under the ROW's law: no flip"
          (is (false? (get-in (reload eng :c_acct @id) [:data :flagged]))))
        (testing "a write keeps computing under the birth law"
          (let [{:keys [row]} (inv/invoke! eng :c_acct @id :touch nil
                                           {:principal elena})]
            (is (false? (get-in row [:data :flagged])))))
        (testing "a new row lives (and computes) under v2"
          (is (true? (get-in (:row (inv/create! eng :c_acct
                                                {:name "b" :balance 60}
                                                {:principal elena}))
                             [:data :flagged]))))
        (testing "adopt restamps; the next write computes under v2"
          (inv/invoke! eng :c_acct @id :adopt nil {:principal elena})
          (let [{:keys [row]} (inv/invoke! eng :c_acct @id :touch nil
                                           {:principal elena})]
            (is (true? (get-in row [:data :flagged])))))))))

;; ── 4. immediate adoption: the promote's backfill repairs, no writes ─

(deftest promote-backfills-stale-facts
  (fresh!)
  (let [id (atom nil)]
    (with-eng [(acct v1-law {:adoption :immediate})] :promote
      (fn [eng]
        (reset! id (:id (:row (inv/create! eng :c_acct
                                           {:name "a" :balance 60}
                                           {:principal elena}))))))
    (with-eng [(acct v2-law {:adoption :immediate})] :promote
      (fn [eng]
        (let [row (reload eng :c_acct @id)]
          (testing "restamped to the new law and recomputed under it —
                    with NO row write (maintenance: v1, one transition)"
            (is (= 2 (:law-revision row)))
            (is (true? (get-in row [:data :flagged])))
            (is (= 1 (:version row)))
            (is (= 1 (transition-count eng :c_acct @id))))
          (testing "the restamp announced itself (kind-wide observation)
                    and the repair recorded its recompute"
            (let [obs (store/with-tx (:storage eng)
                        (fn [tx]
                          (jdbc/execute!
                           tx ["SELECT kind, resource_id, class
                                FROM waymark10_observations
                                WHERE kind = 'c_acct' ORDER BY id"])))
                  classes (mapv :waymark10_observations/class obs)]
              (is (some #{"restamp"} classes))
              (is (some #{"recompute"} classes))
              (is (some #(and (= "restamp" (:waymark10_observations/class %))
                              (nil? (:waymark10_observations/resource_id %)))
                        obs)
                  "the bulk restamp is kind-wide: resource_id is NULL"))))))))

;; ── 5. the aggregate where-filter overlays too ──────────────────────

(deftest count-where-overlays-under-the-hold
  (fresh!)
  (let [crate-id (atom nil)]
    (with-eng [(crate #{"todo"}) box] :promote
      (fn [eng]
        (let [c (:row (inv/create! eng :c_crate {:title "c"}
                                   {:principal elena}))
              b (:row (inv/create! eng :c_box {:title "b" :crate_id (:id c)}
                                   {:principal elena}))]
          (reset! crate-id (:id c))
          (is (= 1 (get-in (reload eng :c_crate (:id c)) [:data :open_boxes])))
          (inv/invoke! eng :c_box (:id b) :finish nil {:principal elena})
          (is (= 0 (get-in (reload eng :c_crate (:id c)) [:data :open_boxes]))))))
    ;; v2 widens the where — count.where is data_law, so propose HOLDS
    (with-eng [(crate #{"todo" "done"}) box] :propose
      (fn [eng]
        (is (some? (:proposed-law (get (inv/resources eng) :c_crate)))
            "a count.where flip alone holds — the gate is EXACT for it")
        (let [b2 (:row (inv/create! eng :c_box {:title "b2"
                                                :crate_id @crate-id}
                                    {:principal elena}))]
          (is (= 1 (get-in (reload eng :c_crate @crate-id)
                           [:data :open_boxes]))
              "todo counts under both laws")
          (inv/invoke! eng :c_box (:id b2) :finish nil {:principal elena})
          (is (= 0 (get-in (reload eng :c_crate @crate-id)
                           [:data :open_boxes]))
              "the crate's law is v1: done boxes do not count, though
               the resident (proposed) where would count 2"))))))
