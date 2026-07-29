(ns waymark10.batch-c-sum-test
  "Batch C, the :sum aggregate and the cross-kind fact DAG. The sum
  mirrors the count: declared over an owns/related edge, computed by
  the maintainer's SQL, fingerprinted as a facet whose where is
  data-law; an expression fact composes over it. The DAG walk refuses
  aggregate cycles by their kind.fact path at assembly. Since the
  sum-over-empty growth (waymark-vpv): {:when-empty :absent} lands
  nil where nothing contributes — zero matching rows, or matches
  whose :of are all nil — over Postgres and the memory twin alike;
  the option is fingerprint law (code-or-shape, backfilled on
  promote) and the default stays 0-over-empty, hash-identical to
  before. Suite-local kinds; real Postgres (the DAG tests are
  registry-pure)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.checks-assembly :as ca]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["c_sum_crates" "c_sum_items" "definitions" "waymark10_transitions"
   "waymark10_idempotency" "waymark10_observations"])

(defn- fresh! []
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st)))))

(defn- with-eng
  ([resources f] (with-eng resources nil f))
  ([resources mode f]
   (let [st (pg/storage db/dsn)]
     (try
       (f (engine/engine (cond-> {:storage st :resources resources}
                           mode (assoc :deploy-mode mode))))
       (finally (pg/close! st))))))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- reload [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind id {}))))

;; ── fixtures ────────────────────────────────────────────────────────

(defn- crate-map [open-where]
  {:kind :c_sum_crate
   :plural "c_sum_crates"
   :states [:open :done]
   :initial :open
   :terminal #{:done}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 40}]]
            [:total_qty {:optional true} [:maybe :int]]
            [:heavy {:optional true} [:maybe :boolean]]]
   :owns [{:kind :c_sum_item :via :crate_id}]
   :derived {:total_qty {:sum {:owns :c_sum_item
                               :where {:state open-where}
                               :of :qty}}
             :heavy {:over [:total_qty]
                     :expr '(< 10 (var :total_qty))}}
   :actions {:finish {:from #{:open} :to :done
                      :safety {:idempotent true :reversible false
                               :confirm false
                               :one-way "Done is history."}}}})

(def ^:private crate (r/resource (crate-map #{"todo"})))

(defn- absent-crate-map
  "The crate with the sum opted into absent-over-empty: zero matching
  rows (or matches whose :of are all nil) land nil, not 0."
  [open-where]
  (assoc-in (crate-map open-where)
            [:derived :total_qty :sum :when-empty] :absent))

(def ^:private absent-crate (r/resource (absent-crate-map #{"todo"})))

(def ^:private item
  (r/resource
   {:kind :c_sum_item
    :plural "c_sum_items"
    :states [:todo :done]
    :initial :todo
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             ;; :maybe deliberately: an unquantified item is the
             ;; nil-:of contributor the absent tests need
             [:qty {:optional true :filter #{:eq}} [:maybe :int]]
             [:crate_id {:kind :c_sum_crate} :waymark/ref]]
    :filterable {:crate_id #{:eq}}
    :actions {:finish {:from #{:todo} :to :done
                       :safety {:idempotent true :reversible false
                                :confirm false
                                :one-way "Done is history."}}}}))

;; ── 1. the maintained sum, composing ────────────────────────────────

(deftest sum-fact-maintained-and-composed
  (fresh!)
  (with-eng [crate item]
    (fn [eng]
      (let [c (:row (inv/create! eng :c_sum_crate {:title "c"}
                                 {:principal elena}))
            _ (is (= 0 (get-in (reload eng :c_sum_crate (:id c))
                               [:data :total_qty]))
                  "the empty set sums to 0")
            i1 (:row (inv/create! eng :c_sum_item
                                  {:title "i1" :qty 4 :crate_id (:id c)}
                                  {:principal elena}))]
        (is (= 4 (get-in (reload eng :c_sum_crate (:id c))
                         [:data :total_qty])))
        (inv/create! eng :c_sum_item {:title "i2" :qty 8 :crate_id (:id c)}
                     {:principal elena})
        (let [row (reload eng :c_sum_crate (:id c))]
          (is (= 12 (get-in row [:data :total_qty])))
          (is (true? (get-in row [:data :heavy]))
              "the expression fact composes over the sum"))
        (testing "a finished item leaves the where — the sum follows"
          (inv/invoke! eng :c_sum_item (:id i1) :finish nil
                       {:principal elena})
          (let [row (reload eng :c_sum_crate (:id c))]
            (is (= 8 (get-in row [:data :total_qty])))
            (is (false? (get-in row [:data :heavy])))))
        (testing "maintenance announced itself (derivation observations)"
          (let [classes (store/with-tx (:storage eng)
                          (fn [tx]
                            (mapv :waymark10_observations/class
                                  (jdbc/execute!
                                   tx ["SELECT class FROM waymark10_observations
                                       WHERE kind = 'c_sum_crate'"]))))]
            (is (seq classes))
            (is (every? #{"recompute"} classes))))))))

(deftest default-sum-lands-zero-when-nothing-contributes
  ;; the pre-option law, unchanged: matched rows whose :of are all nil
  ;; still land 0 under the default spelling (COALESCE semantics kept)
  (fresh!)
  (with-eng [crate item]
    (fn [eng]
      (let [c (:row (inv/create! eng :c_sum_crate {:title "c"}
                                 {:principal elena}))]
        (inv/create! eng :c_sum_item {:title "i" :crate_id (:id c)}
                     {:principal elena})
        (is (= 0 (get-in (reload eng :c_sum_crate (:id c))
                         [:data :total_qty]))
            "one matching row, nil :of — the default still says 0")))))

;; ── 1b. :when-empty :absent — an empty sum is unknown, not free ─────

(deftest absent-sum-lands-nil-not-zero
  (fresh!)
  (with-eng [absent-crate item]
    (fn [eng]
      (let [c (:row (inv/create! eng :c_sum_crate {:title "c"}
                                 {:principal elena}))]
        (is (nil? (get-in (reload eng :c_sum_crate (:id c))
                          [:data :total_qty]))
            "zero matching rows land ABSENT, not 0")
        (let [i1 (:row (inv/create! eng :c_sum_item
                                    {:title "i1" :qty 4 :crate_id (:id c)}
                                    {:principal elena}))]
          (is (= 4 (get-in (reload eng :c_sum_crate (:id c))
                           [:data :total_qty]))
              "matching rows sum normally")
          (inv/create! eng :c_sum_item {:title "i2" :crate_id (:id c)}
                       {:principal elena})
          (let [row (reload eng :c_sum_crate (:id c))]
            (is (= 4 (get-in row [:data :total_qty]))
                "a nil :of contributes nothing")
            (is (false? (get-in row [:data :heavy]))
                "the composed expression fact reads the sum as any field"))
          (testing "the only quantified match leaves the where — no
                    priced information remains, so the sum is absent"
            (inv/invoke! eng :c_sum_item (:id i1) :finish nil
                         {:principal elena})
            (let [row (reload eng :c_sum_crate (:id c))]
              (is (nil? (get-in row [:data :total_qty]))
                  "a match with a nil :of is no information — nil, the
                   same answer SQL's un-coalesced SUM gives")
              (is (false? (get-in row [:data :heavy]))
                  "ordering over nil is false — the expression grammar
                   stays total"))))))))

(deftest absent-sum-agrees-over-the-memory-twin
  ;; the same scenario over the memory storage: the twin must answer
  ;; EXACTLY as Postgres's un-coalesced SUM does at every step
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources [absent-crate item]})
        c (:row (inv/create! eng :c_sum_crate {:title "c"}
                             {:principal elena}))]
    (is (nil? (get-in (reload eng :c_sum_crate (:id c)) [:data :total_qty]))
        "zero matching rows: nil over memory too")
    (let [i1 (:row (inv/create! eng :c_sum_item
                                {:title "i1" :qty 4 :crate_id (:id c)}
                                {:principal elena}))]
      (inv/create! eng :c_sum_item {:title "i2" :crate_id (:id c)}
                   {:principal elena})
      (is (= 4 (get-in (reload eng :c_sum_crate (:id c)) [:data :total_qty]))
          "sums normally; the nil :of contributes nothing")
      (inv/invoke! eng :c_sum_item (:id i1) :finish nil {:principal elena})
      (is (nil? (get-in (reload eng :c_sum_crate (:id c)) [:data :total_qty]))
          "all-nil matches: nil over memory too"))))

(deftest when-empty-is-checked-at-declaration
  (testing "the only spelling is :absent"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #":when-empty"
         (r/resource (assoc-in (crate-map #{"todo"})
                               [:derived :total_qty :sum :when-empty]
                               :zero)))))
  (testing "an unknown aggregate key is refused (the closed grammar)"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"exactly one edge"
         (r/resource (assoc-in (crate-map #{"todo"})
                               [:derived :total_qty :sum :on-empty]
                               :absent)))))
  (testing "an absent sum fact must accept nil in the schema"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"\[:maybe"
         (r/resource
          (-> (absent-crate-map #{"todo"})
              (assoc :schema [:map
                              [:title [:string {:min 1 :max 40}]]
                              [:total_qty {:optional true} :int]
                              [:heavy {:optional true} [:maybe :boolean]]]))))))
  (testing ":count refuses the option — an empty count is honestly 0"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"exactly one edge"
         (r/resource
          (-> (crate-map #{"todo"})
              (assoc-in [:derived :open_count]
                        {:count {:owns :c_sum_item
                                 :where {:state #{"todo"}}
                                 :when-empty :absent}})
              (update :schema conj
                      [:open_count {:optional true} [:maybe :int]])))))))

(deftest promote-backfill-turns-stored-zeros-absent
  ;; the prod story: rows landed 0 under the old law; the redeclared
  ;; sum's fingerprint moves (code-or-shape), the boot promotes, and
  ;; the wired backfill restamps the stored 0 to nil — no row writes
  (fresh!)
  (let [id (atom nil)]
    (with-eng [crate item] :promote
      (fn [eng]
        (let [c (:row (inv/create! eng :c_sum_crate {:title "c"}
                                   {:principal elena}))]
          (reset! id (:id c))
          (is (= 0 (get-in (reload eng :c_sum_crate (:id c))
                           [:data :total_qty]))
              "the old law landed 0 over the empty edge"))))
    (with-eng [absent-crate item] :promote
      (fn [eng]
        (let [row (reload eng :c_sum_crate @id)]
          (is (nil? (get-in row [:data :total_qty]))
              "the promote's backfill restamped the stored 0 to nil")
          (is (= 1 (:version row))
              "maintenance, not a write — the version never moved"))))))

;; ── 2. the fingerprint facet and its law class ──────────────────────

(deftest sum-facet-fingerprints-and-classifies
  (let [fp1 (fp/fingerprint-of crate)]
    (testing "the facet, canonical"
      (is (= {"owns" "c_sum_item" "of" "qty" "where" {"state" ["todo"]}}
             (get-in fp1 ["derived" "total_qty" "sum"]))))
    (testing "sum.where is data-law (the count.where rule)"
      (let [fp2 (fp/fingerprint-of (r/resource (crate-map #{"todo" "done"})))]
        (is (= :data-law (fp/classify-diff (fp/diff-fingerprints fp1 fp2))))
        (is (= ["total_qty"]
               (fp/stale-facts (fp/diff-fingerprints fp1 fp2))))))
    (testing "sum.of is edge identity — code-or-shape"
      (let [fp3 (fp/fingerprint-of
                 (r/resource (-> (crate-map #{"todo"})
                                 (assoc-in [:derived :total_qty :sum :of]
                                           :crate_id))))]
        (is (= :code-or-shape
               (fp/classify-diff (fp/diff-fingerprints fp1 fp3))))))
    (testing "sum.when_empty is law: the facet carries it, the hash
              moves, the diff promotes totally, the fact goes stale"
      (let [fpa (fp/fingerprint-of absent-crate)
            diff (fp/diff-fingerprints fp1 fpa)]
        (is (= {"owns" "c_sum_item" "of" "qty" "when_empty" "absent"
                "where" {"state" ["todo"]}}
               (get-in fpa ["derived" "total_qty" "sum"])))
        (is (not= (fp/fingerprint-hash fp1) (fp/fingerprint-hash fpa))
            "adding the option mints a revision")
        (is (= :code-or-shape (fp/classify-diff diff))
            "empty-sum semantics are the resident read, never held")
        (is (= ["total_qty"] (fp/stale-facts diff))
            "the promote's backfill restamps the landed values")))))

;; ── 3. the cross-kind fact DAG ──────────────────────────────────────

(defn- dag-a [where-field]
  (r/resource
   {:kind :c_dag_a
    :plural "c_dag_as"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:busy_kids {:optional true} [:maybe :int]]]
    :filterable {:busy_kids #{:eq}}
    :owns [{:kind :c_dag_b :via :a_id}]
    :derived {:busy_kids {:count {:owns :c_dag_b
                                  :where {where-field #{"1"}}}}}
    :actions {:finish {:from #{:open} :to :done
                       :safety {:idempotent true :reversible false
                                :confirm false
                                :one-way "Done is history."}}}}))

(defn- dag-b [busy-derived?]
  (r/resource
   (cond-> {:kind :c_dag_b
            :plural "c_dag_bs"
            :states [:todo :done]
            :initial :todo
            :terminal #{:done}
            :summary "{data.title} · {state}"
            :schema [:map
                     [:title [:string {:min 1 :max 40}]]
                     [:plain {:optional true :filter #{:eq}} [:maybe :int]]
                     [:busy {:optional true :filter #{:eq}} [:maybe :int]]
                     [:a_id {:kind :c_dag_a :filter #{:eq}} :waymark/ref]]
            :actions {:finish {:from #{:todo} :to :done
                               :safety {:idempotent true :reversible false
                                        :confirm false
                                        :one-way "Done is history."}}}}
     busy-derived?
     (assoc :related {:parent {:kind :c_dag_a :on [[:a_id := :id]]}}
            :derived {:busy {:count {:related :parent
                                     :where {:busy_kids #{"1"}}}}}))))

(deftest cross-kind-fact-dag-refuses-cycles
  (testing "a's count reads b.busy, whose count reads a.busy_kids — refused
            by the kind.fact path"
    (let [e (try (ca/run-all {:kinds {:c_dag_a (dag-a :busy)
                                      :c_dag_b (dag-b true)}})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= :derived-cycles (:check (ex-data e))))
      (is (str/includes? (ex-message e) "cross-kind derived facts form a cycle"))
      (is (str/includes? (ex-message e) "c_dag_a.busy_kids"))
      (is (str/includes? (ex-message e) "c_dag_b.busy"))))
  (testing "the same edges over a plain field assemble fine"
    (is (map? (ca/run-all {:kinds {:c_dag_a (dag-a :plain)
                                   :c_dag_b (dag-b false)}})))))

;; ── 4. the assembly holds :of to its numbers ────────────────────────

(deftest sum-of-must-be-numeric-and-fit-the-fact
  (testing "an :int sum fact cannot sum a non-numeric column"
    (let [bad (r/resource
               (-> (crate-map #{"todo"})
                   ;; crate_id is promoted (the ref column) but a string
                   (assoc-in [:derived :total_qty :sum :of] :crate_id)))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"not numeric"
           (ca/run-all {:kinds {:c_sum_crate bad :c_sum_item item}}))))))
