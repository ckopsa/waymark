(ns waymark10.migrate-test
  "The migrate acceptance: schema evolution as a planned, ordered,
  honest diff against the real database. Scenarios (waymark9
  server/migrate.py's obligations, restated for the v10 taming
  insight — every per-kind column beyond the engine's fixed set is
  GENERATED from the JSONB document, so column reconciliation is
  data-safe and only state-token rewrites are destructive):

  (a) a new filterable field plans an add-column, backfills
      pre-existing rows, and the collection surface uses it;
  (b) a dropped promotion plans a drop-column;
  (c) a retype plans a recreate (drop + add);
  (d) a state rename: boot refuses before migrate, the destructive
      apply moves the rows, boot serves, and replay-history stays
      green across the rename;
  (e) a drift-free database plans empty and the boot serves;
  (f) the fingerprint's storage facet makes a promotion change LAW —
      :code-or-shape, one revision minted at the next boot.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.migrate :as migrate]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.conformance :as conf]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["widgets" "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(defn- fresh! []
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st)))))

(defn- with-eng
  "One boot: (f eng); the pool closes either way."
  [resources opts f]
  (let [st (pg/storage db/dsn)]
    (try
      (f (engine/engine (merge {:storage st :resources resources} opts)))
      (finally (pg/close! st)))))

(defn- boot-error
  "The ex-data of the definition error a boot throws; nil when it
  serves."
  [resources opts]
  (let [st (pg/storage db/dsn)]
    (try
      (engine/engine (merge {:storage st :resources resources} opts))
      nil
      (catch clojure.lang.ExceptionInfo e (ex-data e))
      (finally (pg/close! st)))))

(defn- with-st [f]
  (let [st (pg/storage db/dsn)]
    (try (f st) (finally (pg/close! st)))))

(defn- q [st sql]
  (store/with-tx st
    (fn [tx] (jdbc/execute! tx [sql] {:builder-fn rs/as-unqualified-maps}))))

(def elena (t/principal {:id "elena" :display "Elena"}))

;; ── the widget, in four editions ────────────────────────────────────

(defn- widget-map [{:keys [states initial terminal renames from
                           priority-schema filterable]}]
  {:kind :widget
   :states (or states [:draft :done])
   :initial (or initial :draft)
   :terminal (or terminal #{:done})
   :adoption :never
   :renames renames
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 40}]]
            [:priority {:optional true} (or priority-schema [:maybe :int])]]
   :filterable (merge {:state #{:eq :in}} filterable)
   :actions
   {:tick {:from (or from #{:draft}) :to (or initial :draft)
           :safety {:idempotent true :reversible true :confirm false}}
    :finish {:from (or from #{:draft}) :to :done
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "Done is done."}}}})

(def v-base (r/resource (widget-map {})))
(def v-promoted (r/resource (widget-map {:filterable {:priority #{:eq}}})))
(def v-retyped (r/resource (widget-map {:priority-schema [:maybe [:string {:max 9}]]
                                        :filterable {:priority #{:eq}}})))
(def v-renamed (r/resource (widget-map {:states [:open :done]
                                        :initial :open
                                        :from #{:open}
                                        :renames {:states {:draft :open}}})))
;; the same machine change with NO continuity map — the boot's
;; state-token check must name the missing declaration
(def v-renamed-bare (r/resource (widget-map {:states [:open :done]
                                             :initial :open
                                             :from #{:open}})))

(defn- steps-of [resources]
  (with-st (fn [st] (migrate/plan st resources))))

;; ── (a) add-column, (b) drop-column, (c) recreate — one storyline ───

(deftest column-reconciliation-add-drop-recreate
  (fresh!)
  (with-eng [v-base] {}
    (fn [eng]
      (inv/create! eng :widget {:name "w1" :priority 2} {:principal elena})
      (inv/create! eng :widget {:name "w2" :priority 1} {:principal elena})))
  (testing "(a) a drifted boot refuses, naming the plan"
    (let [d (boot-error [v-promoted] {})]
      (is (= :migrate (:check d)))
      (is (= [:add-column] (mapv :kind (:steps d))))))
  (testing "(a) the plan is one non-destructive add-column"
    (let [steps (steps-of [v-promoted])]
      (is (= 1 (count steps)))
      (let [s (first steps)]
        (is (= :add-column (:kind s)))
        (is (= "widgets" (:table s)))
        (is (false? (:destructive? s)))
        (is (re-find #"ADD COLUMN f_priority bigint GENERATED" (:sql s))))))
  (with-st
    (fn [st]
      (let [{:keys [applied skipped]}
            (migrate/apply! st (migrate/plan st [v-promoted]) {})]
        (is (= 1 (count applied)))
        (is (empty? skipped)))
      (testing "(a) Postgres backfilled the generated column for old rows"
        (is (= #{["w1" 2] ["w2" 1]}
               (into #{} (map (juxt :name :f_priority))
                     (q st "SELECT data->>'name' AS name, f_priority FROM widgets")))))
      (is (= [] (migrate/plan st [v-promoted])) "applied means drift-free")))
  (with-eng [v-promoted] {}
    (fn [eng]
      (let [st (:storage eng)]
        (store/with-tx st
          (fn [tx]
            (testing "(a) collections filter on the new promotion"
              (is (= ["w1"]
                     (mapv #(get-in % [:data :name])
                           (store/search-rows st tx :widget
                                              [{:target :data :field :priority
                                                :cast "bigint" :op := :value 2}]
                                              {})))))
            (testing "(a) sort orders by the generated column"
              (is (= ["w2" "w1"]
                     (mapv #(get-in % [:data :name])
                           (store/search-rows st tx :widget []
                                              {:order-by :priority}))))))))))
  (testing "(b) a dropped promotion drops its column — derived data"
    (let [steps (steps-of [v-base])]
      (is (= [:drop-column] (mapv :kind steps)))
      (is (every? (comp false? :destructive?) steps))
      (with-st
        (fn [st]
          (migrate/apply! st steps {})
          (is (not (contains? (:columns (pg/table-snapshot st "widgets"))
                              "f_priority")))
          (is (= [] (migrate/plan st [v-base])))))))
  (testing "(c) a retype recreates the column at the declared type"
    (with-st
      (fn [st]
        (migrate/apply! st (migrate/plan st [v-promoted]) {}) ; bigint again
        (let [steps (migrate/plan st [v-retyped])]
          (is (= [:recreate-column :recreate-column] (mapv :kind steps)))
          (is (every? (comp false? :destructive?) steps))
          (migrate/apply! st steps {})
          (is (= {:type "text" :generated? true}
                 (get (:columns (pg/table-snapshot st "widgets")) "f_priority")))
          (testing "the recreated column re-derives from the document"
            (is (= #{["w1" "2"] ["w2" "1"]}
                   (into #{} (map (juxt :name :f_priority))
                         (q st "SELECT data->>'name' AS name, f_priority FROM widgets")))))
          (is (= [] (migrate/plan st [v-retyped]))))))))

;; ── (d) the one destructive class: state renames ────────────────────

(deftest d-state-rename-refuses-then-migrates-then-replays
  (fresh!)
  (let [wid (atom nil)]
    (with-eng [v-base] {}
      (fn [eng]
        (let [{:keys [row]} (inv/create! eng :widget {:name "w1"}
                                         {:principal elena})]
          (reset! wid (:id row))
          ;; a logged pre-rename transition, spelled in old tokens
          (inv/invoke! eng :widget (:id row) :tick nil {:principal elena}))))
    (testing "boot refuses while live rows sit in the retired token"
      (testing "no :renames declared → the state-token check names the fix"
        (let [d (boot-error [v-renamed-bare] {})]
          (is (= :state-tokens (:check d)))
          (is (= ["draft"] (:states d)))))
      (let [d (boot-error [v-renamed] {})]
        (is (= :migrate (:check d)))
        (is (= [:rename-state] (mapv :kind (:steps d))))
        (is (true? (:destructive? (first (:steps d))))))
      (testing "auto-migrate does NOT cover the destructive class"
        (let [d (boot-error [v-renamed] {:auto-migrate true})]
          (is (= :migrate (:check d)))
          (is (= [:rename-state] (mapv :kind (:steps d)))))))
    (with-st
      (fn [st]
        (let [steps (migrate/plan st [v-renamed])]
          (testing "apply! skips destructive steps unless opted in"
            (let [{:keys [applied skipped]} (migrate/apply! st steps {})]
              (is (empty? applied))
              (is (= 1 (count skipped)))))
          (let [{:keys [applied skipped]}
                (migrate/apply! st steps {:destructive? true})]
            (is (= 1 (count applied)))
            (is (empty? skipped))))
        (is (= #{"open"} (set (pg/distinct-states st "widgets"))))
        (is (= [] (migrate/plan st [v-renamed])))))
    (with-eng [v-renamed] {}
      (fn [eng]
        (testing "the boot serves; the machine change minted a revision"
          (is (= 2 (:current-law (get (inv/resources eng) :widget)))))
        (testing "a grandfathered row acts in the NEW token under the OLD
                  law's stamp — the rename chain keeps replay green"
          (let [{:keys [row transition]}
                (inv/invoke! eng :widget @wid :tick nil {:principal elena})]
            (is (= :open (:state row)))
            (is (= 1 (:law-revision transition))
                "adoption :never — the row still cites revision 1"))
          (is (= [] (conf/replay-violations eng))))))))

;; ── (e) drift-free is an empty plan and a serving boot ──────────────

(deftest e-drift-free-plans-empty
  (fresh!)
  (with-eng [v-base] {} (fn [_eng]))
  (is (= [] (steps-of [v-base])))
  (with-eng [v-base] {}
    (fn [eng] (is (some? (get (inv/resources eng) :widget)))))
  (testing "engine tables reconcile additively"
    (with-st
      (fn [st]
        (store/with-tx st
          (fn [tx] (jdbc/execute! tx ["ALTER TABLE waymark10_transitions DROP COLUMN summary"])))
        (let [steps (migrate/plan st [v-base])]
          (is (= [:add-column] (mapv :kind steps)))
          (is (= "waymark10_transitions" (:table (first steps))))
          (migrate/apply! st steps {})
          (is (= [] (migrate/plan st [v-base]))))))))

;; ── (f) the storage facet is law ────────────────────────────────────

(deftest f-a-promotion-change-is-code-or-shape-law
  (testing "the facet projects the same table the DDL creates"
    (let [facet (get (fp/fingerprint-of v-promoted) "storage")]
      (is (= "widgets" (get facet "table")))
      (is (= {"type" "bigint" "generated" true}
             (get-in facet ["columns" "f_priority"])))))
  (testing "a promotion change diffs under storage.* and promotes totally"
    (let [d (fp/diff-fingerprints (fp/fingerprint-of v-base)
                                  (fp/fingerprint-of v-promoted))]
      (is (= [:shape] (distinct (map :class (:added d)))))
      (is (= :code-or-shape (fp/classify-diff d)))))
  (fresh!)
  (with-eng [v-base] {} (fn [_eng]))
  (with-eng [v-promoted] {:auto-migrate true}
    (fn [eng]
      (let [rd (get (inv/resources eng) :widget)
            rows (store/with-tx (:storage eng)
                   (fn [tx] (store/query-rows (:storage eng) tx :definition
                                              {:target_kind "widget"}
                                              {:limit 10})))
            rev2 (first (filter #(= 2 (get-in % [:data :revision])) rows))]
        (is (= 2 (:current-law rd)) "the boot minted and promoted revision 2")
        (is (= "code_or_shape" (get-in rev2 [:data :diff_class])))))))
