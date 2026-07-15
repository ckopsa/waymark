(ns waymark10.ctx-invoke-test
  "The handler's cross-write door (waymark9 Ctx.invoke, ported): a
  handler writes OTHER rows through the same transaction and the full
  per-item algorithm; inner writes ride the result as :inner-writes
  and after-write! runs their post-commit passes (maintenance first,
  so the outer response's rollups tell the post-inner truth). Guards
  never see the pen; dry-run carries none; a natural replay of the
  outer skips the inner re-execution wholesale. Suite-local kinds;
  real Postgres; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.guards :as g]
            [waymark10.resource :as r :refer [defhandler]]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["ci_folders" "ci_docs" "definitions"
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

(defn- with-eng [resources f]
  (let [st (pg/storage db/dsn)]
    (try
      (f (engine/engine {:storage st :resources resources}))
      (finally (pg/close! st)))))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- reload [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind id {}))))

(defn- transitions-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/transitions (:storage eng) tx
                                {:kind kind :resource-id id} {}))))

;; ── fixtures: a folder merges another folder's docs into itself ─────

(def ^:private guards-have-no-pen
  ;; the tripwire: merge_from refuses outright if its guard ever
  ;; receives the cross-write door — guards judge, handlers write
  (g/guard {:name :guards-have-no-pen
            :explain "A guard holding the pen is a definition bug."
            :check (fn [_row _inp ctx]
                     (if (:invoke ctx) (t/deny) (t/allow)))}))

(defhandler merge-from [row inp ctx]
  (let [invoke! (:invoke ctx)
        source (:source_id inp)]
    (doseq [doc ((:find ctx) :ci_doc {:folder_id source} {:limit 100})
            :when (= :filed (:state doc))]
      (invoke! :ci_doc (:id doc) :move {:folder_id (:id row)}))
    (invoke! :ci_folder source :retire nil)
    row))

(defhandler move-doc [row inp _ctx]
  (assoc-in row [:data :folder_id] (:folder_id inp)))

(defhandler merge-nothing [row _inp ctx]
  ;; the door answers a bad id with the same problem the wire would
  ((:invoke ctx) :ci_doc "no-such-doc" :move {:folder_id (:id row)})
  row)

(def ^:private folder
  (r/resource
   {:kind :ci_folder
    :plural "ci_folders"
    :states [:open :retired]
    :initial :open
    :terminal #{:retired}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:doc_count {:optional true} [:maybe :int]]]
    :owns [{:kind :ci_doc :via :folder_id :on {:touch_all :touch}}]
    :derived {:doc_count {:count {:owns :ci_doc
                                  :where {:state #{"filed"}}}}}
    :actions {:touch_all
              {:from #{:open} :to :open
               :safety {:idempotent true :reversible false :confirm false}}
              :merge_from
              {:from #{:open} :to :open
               :input [:map [:source_id {:kind :ci_folder} :waymark/ref]]
               :guards [guards-have-no-pen]
               :safety {:idempotent true :reversible false :confirm false}
               :handler merge-from}
              :merge_nothing
              {:from #{:open} :to :open
               :safety {:idempotent true :reversible false :confirm false}
               :handler merge-nothing}
              :retire
              {:from #{:open} :to :retired
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "Retired folders stay retired."}}}}))

(def ^:private doc
  (r/resource
   {:kind :ci_doc
    :plural "ci_docs"
    :states [:filed :void]
    :initial :filed
    :terminal #{:void}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:folder_id {:kind :ci_folder :filter #{:eq}} :waymark/ref]]
    :actions {:move
              {:from #{:filed} :to :filed
               :input [:map [:folder_id {:kind :ci_folder} :waymark/ref]]
               :edit {:prefill [:folder_id] :fence false
                      :unfenced-reason "A move is a one-field verdict the
                      merge cascade issues without a rendered form."}
               :safety {:idempotent true :reversible false :confirm false}
               :handler move-doc}
              :touch
              {:from #{:filed} :to :filed
               :safety {:idempotent true :reversible false :confirm false}}
              :void
              {:from #{:filed} :to :void
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "Voided is history."}}}}))

(defn- seed!
  "Folder a with one doc, folder b with three. → [a-id b-id doc-ids-of-b]"
  [eng]
  (let [a (:row (inv/create! eng :ci_folder {:title "a"} {:principal elena}))
        b (:row (inv/create! eng :ci_folder {:title "b"} {:principal elena}))]
    (inv/create! eng :ci_doc {:title "a1" :folder_id (:id a)}
                 {:principal elena})
    [(:id a) (:id b)
     (mapv (fn [i]
             (:id (:row (inv/create! eng :ci_doc
                                     {:title (str "b" i) :folder_id (:id b)}
                                     {:principal elena}))))
           (range 3))]))

;; ── 1. the merge: same call, whole truth ────────────────────────────

(deftest inner-writes-land-and-maintenance-tells-the-truth
  (fresh!)
  (with-eng [folder doc]
    (fn [eng]
      (let [[a b doc-ids] (seed! eng)]
        (inv/invoke! eng :ci_folder a :merge_from {:source_id b}
                     {:principal elena})
        (testing "every filed doc of b now files under a"
          (doseq [d doc-ids]
            (is (= a (get-in (reload eng :ci_doc d) [:data :folder_id])))))
        (testing "the source folder retired in the same call"
          (is (= :retired (:state (reload eng :ci_folder b)))))
        (testing "maintenance ran for the INNER writes: both parents'
                  counts tell the post-merge truth"
          (is (= 4 (get-in (reload eng :ci_folder a) [:data :doc_count])))
          (is (= 0 (get-in (reload eng :ci_folder b) [:data :doc_count]))))))))

;; ── 2. inner transitions wear the outer correlation id ──────────────

(deftest inner-transitions-share-the-outer-correlation-id
  (fresh!)
  (with-eng [folder doc]
    (fn [eng]
      (let [[a b doc-ids] (seed! eng)]
        (inv/invoke! eng :ci_folder a :merge_from {:source_id b}
                     {:principal elena :correlation-id "merge-cid-1"})
        (doseq [d doc-ids]
          (is (= "merge-cid-1"
                 (:correlation-id
                  (last (transitions-of eng :ci_doc d))))
              "the doc's move records the merge that caused it"))
        (is (= "merge-cid-1"
               (:correlation-id (last (transitions-of eng :ci_folder b))))
            "the source's retire records the merge that caused it")))))

;; ── 3. a natural replay of the outer skips the inner wholesale ──────

(deftest natural-replay-skips-inner-re-execution
  (fresh!)
  (with-eng [folder doc]
    (fn [eng]
      (let [[a b doc-ids] (seed! eng)]
        (inv/invoke! eng :ci_folder a :merge_from {:source_id b}
                     {:principal elena})
        (let [versions (mapv #(:version (reload eng :ci_doc %)) doc-ids)
              replay (inv/invoke! eng :ci_folder a :merge_from
                                  {:source_id b} {:principal elena})]
          (is (= :natural (:replayed? replay)))
          (is (= versions (mapv #(:version (reload eng :ci_doc %)) doc-ids))
              "no doc moved twice — the handler never re-ran"))))))

;; ── 4. dry-run carries no pen and writes nothing ────────────────────

(deftest dry-run-writes-nothing
  (fresh!)
  (with-eng [folder doc]
    (fn [eng]
      (let [[a b doc-ids] (seed! eng)
            res (inv/invoke! eng :ci_folder a :merge_from {:source_id b}
                             {:principal elena :dry-run true})]
        (is (:valid? res))
        (doseq [d doc-ids]
          (is (= b (get-in (reload eng :ci_doc d) [:data :folder_id]))
              "the rehearsal moved nothing"))
        (is (= :open (:state (reload eng :ci_folder b))))))))

;; ── 5. a self-loop cascade terminates past the page boundary ────────

(deftest self-loop-cascade-terminates-and-touches-once
  ;; touch stays :filed — the cascaded child never leaves the :from
  ;; filter, so termination rests on the seen set + growing window,
  ;; not on children leaving. 201 docs crosses the 200-row page.
  (fresh!)
  (with-eng [folder doc]
    (fn [eng]
      (let [a (:id (:row (inv/create! eng :ci_folder {:title "a"}
                                      {:principal elena})))
            doc-ids (mapv (fn [i]
                            (:id (:row (inv/create!
                                        eng :ci_doc
                                        {:title (str "d" i) :folder_id a}
                                        {:principal elena}))))
                          (range 201))]
        (inv/invoke! eng :ci_folder a :touch_all nil {:principal elena})
        (is (= {2 201}
               (frequencies (map #(:version (reload eng :ci_doc %))
                                 doc-ids)))
            "every doc touched exactly once — full coverage, no loop")))))

;; ── 6. the door refuses what the wire refuses ───────────────────────

(deftest unknown-targets-refuse-as-problems
  (fresh!)
  (with-eng [folder doc]
    (fn [eng]
      (let [[a _ _] (seed! eng)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"(?i)not.found|no such"
             (inv/invoke! eng :ci_folder a :merge_nothing nil
                          {:principal elena})))))))
