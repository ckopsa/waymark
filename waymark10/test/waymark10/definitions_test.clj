(ns waymark10.definitions-test
  "Phase-5 acceptance: the law lifecycle and the judgment overlay.
  Every deftest owns its world — drop, boot, sometimes re-boot with
  different resident code (the deploy, simulated in-process): the
  admission test is waymark9's own, run against real Postgres. Needs
  the waymark10_test database; WAYMARK10_TEST_DSN overrides the DSN."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]
            [waymark10.server.definitions :as defs]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.conformance :as conf]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["plans" "meals" "gates" "definitions"
   "waymark10_transitions" "waymark10_idempotency"])

(defn- fresh! []
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st)))))

(defn- with-eng
  "One boot — one deploy: storage + resources + mode → (f eng)."
  [resources mode f]
  (let [st (pg/storage db/dsn)]
    (try
      (f (engine/engine {:storage st :resources resources :deploy-mode mode}))
      (finally (pg/close! st)))))

(def elena (t/principal {:id "elena" :display "Elena"}))

;; ── readers ─────────────────────────────────────────────────────────

(defn- rdef [eng kind] (get (inv/resources eng) kind))

(defn- defs-of [eng kind]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/query-rows (:storage eng) tx :definition
                        {:target_kind (name kind)} {:limit 100}))))

(defn- def-row [eng kind rev]
  (first (filter #(= rev (get-in % [:data :revision])) (defs-of eng kind))))

(defn- reload [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind id {}))))

(defn- envelope [eng kind id]
  (let [rd (rdef eng kind)
        row (update (reload eng kind id) :data
                    #(schema/decode (:schema rd) %))]
    (render/envelope rd row {:now ((:now-fn eng))})))

(defn- problem-of [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d) d (throw e))))))

;; ── the plan deploys ────────────────────────────────────────────────

(def ^:private strict-warning "1 calendar conflict(s) overlap this week.")

(def ^:private lenient-calendar
  ;; the single-leaf judgment flip: 8.0 would classify a guard change
  ;; code_or_shape and promote totally; 9.0/10 holds it
  '(<= (data :calendar_conflicts) 1))

(defn- plan-body [conflicts & [{:keys [weeks]}]]
  {:start_date "2026-07-14" :weeks (or weeks 1)
   :days [{:date "2026-07-14" :eating_out true}
          {:date "2026-07-15" :eating_out true}]
   :calendar_conflicts conflicts})

(defn- finalize-warnings
  "The probe half of finalize's verdict: dry-run collects the warning
  reasons without moving the row."
  [eng pid]
  (mapv :reason (:warnings (inv/invoke! eng :plan pid :finalize nil
                                        {:principal elena :dry-run true}))))

;; ── 1. boot mints revision 1 ────────────────────────────────────────

(deftest boot-mints-revision-one
  (fresh!)
  (with-eng [fx/meal (fx/plan-resource)] :promote
    (fn [eng]
      (doseq [kind [:meal :plan]]
        (testing (name kind)
          (let [rows (defs-of eng kind)
                row (first rows)]
            (is (= 1 (count rows)))
            (is (= :current (:state row)))
            (is (= 1 (get-in row [:data :revision])))
            (is (= "initial" (get-in row [:data :diff_class])))
            (is (= (:fingerprint-hash (rdef eng kind))
                   (get-in row [:data :fingerprint_hash])))
            (is (= 1 (:current-law (rdef eng kind)))))))
      (testing "created rows stamp the current law; the envelope says so"
        (let [{:keys [row]} (inv/create! eng :plan (plan-body 0)
                                         {:principal elena})]
          (is (= 1 (:law-revision row)))
          (is (= 1 (get-in (envelope eng :plan (:id row))
                           ["meta" "law_revision"]))))))))

;; ── 2. reboot is a no-op ────────────────────────────────────────────

(deftest reboot-is-a-no-op
  (fresh!)
  (with-eng [fx/meal (fx/plan-resource)] :promote (fn [_eng]))
  (with-eng [fx/meal (fx/plan-resource)] :promote
    (fn [eng]
      (doseq [kind [:meal :plan]]
        (testing (name kind)
          (is (= 1 (count (defs-of eng kind)))
              "matching hashes write nothing")
          (is (= 1 (:current-law (rdef eng kind))))
          (is (= (:fingerprint-hash (rdef eng kind))
                 (get-in (first (defs-of eng kind))
                         [:data :fingerprint_hash]))))))))

;; ── 3. the admission test (waymark9's own) ──────────────────────────

(deftest the-admission-test
  (fresh!)
  (let [before (atom nil)]
    ;; deploy 1: the strict law — any conflict warns
    (with-eng [fx/meal (fx/plan-resource)] :promote
      (fn [eng]
        (let [{:keys [row]} (inv/create! eng :plan (plan-body 1)
                                         {:principal elena})]
          (reset! before (:id row))
          (is (= 1 (:law-revision row)))
          (is (= [strict-warning] (finalize-warnings eng (:id row)))
              "before the deploy: the strict law warns on one conflict"))))
    ;; deploy 2: one judgment leaf flips, in propose mode — held
    (with-eng [fx/meal (fx/plan-resource {:calendar-when lenient-calendar})]
      :propose
      (fn [eng]
        (testing "a proposed revision 2 exists; the current law stays 1"
          (let [rd (rdef eng :plan)
                d2 (def-row eng :plan 2)]
            (is (= 1 (:current-law rd)))
            (is (= 2 (get-in rd [:proposed-law :revision])))
            (is (contains? (:judgment-laws rd) 1)
                "the overlay serves revision 1 from its stored trees")
            (is (= :proposed (:state d2)))
            (is (= "data_law" (get-in d2 [:data :diff_class])))
            (is (true? (get-in d2 [:data :held])))))
        (let [after (:id (:row (inv/create! eng :plan (plan-body 1)
                                            {:principal elena})))]
          (testing "rows created before AND after the deploy still judge
                    and render under revision 1's stored tree"
            (is (= 1 (:law-revision (reload eng :plan after))))
            (doseq [pid [@before after]]
              (is (= [strict-warning] (finalize-warnings eng pid))
                  "the probe warns from the stored law")
              (let [p (problem-of #(inv/invoke! eng :plan pid :finalize nil
                                                {:principal elena}))]
                (is (= 409 (:status p)))
                (is (= :warning-required (:waymark10/problem p)))
                (is (= strict-warning (-> p :warnings first :reason))
                    "enforcement refuses with the stored law's sentence"))
              (is (= 1 (get-in (envelope eng :plan pid)
                               ["meta" "law_revision"])))))
          (testing "the proposer cannot promote (four-eyes on :create)"
            (let [p (problem-of
                     #(inv/invoke! eng :definition
                                   (:id (def-row eng :plan 2))
                                   :promote nil {:principal defs/deploy}))]
              (is (= 409 (:status p)))
              (is (= :guard-refused (:waymark10/problem p)))))
          (testing "a different principal promotes; :immediate rows restamp"
            (inv/invoke! eng :definition (:id (def-row eng :plan 2))
                         :promote nil {:principal elena})
            (let [rd (rdef eng :plan)]
              (is (= 2 (:current-law rd)))
              (is (nil? (:proposed-law rd)))
              (is (empty? (:judgment-laws rd))
                  "the promoted law is resident; nothing serves from the store"))
            (doseq [pid [@before after]]
              (is (= 2 (:law-revision (reload eng :plan pid))))
              (is (= [] (finalize-warnings eng pid))
                  "after the promote: one conflict no longer warns")))
          (testing "revision 1 superseded once empty"
            (is (= :superseded (:state (def-row eng :plan 1)))))
          (testing "the write lands under revision 2, replayable"
            (let [{:keys [row transition]}
                  (inv/invoke! eng :plan @before :finalize nil
                               {:principal elena})]
              (is (= :planned (:state row)))
              (is (= 2 (:law-revision transition)))))
          (testing "replay-history obligation holds"
            (is (= [] (conf/replay-violations eng)))))))))

;; ── 4 & 6. pilot where= and withdraw ────────────────────────────────

(deftest pilot-where-and-withdraw
  (fresh!)
  (let [ids (atom {})]
    (with-eng [fx/meal (fx/plan-resource)] :promote
      (fn [eng]
        (swap! ids assoc
               :p1 (:id (:row (inv/create! eng :plan (plan-body 1 {:weeks 1})
                                           {:principal elena})))
               :p2 (:id (:row (inv/create! eng :plan (plan-body 1 {:weeks 2})
                                           {:principal elena}))))))
    (with-eng [fx/meal (fx/plan-resource {:calendar-when lenient-calendar})]
      :propose
      (fn [eng]
        (let [{:keys [p1 p2]} @ids
              d2 (def-row eng :plan 2)]
          (inv/invoke! eng :definition (:id d2) :pilot {:where {:weeks 1}}
                       {:principal elena
                        :idempotency-key (str (random-uuid))})
          (testing "the population restamps; everyone else keeps current"
            (is (= :piloted (:state (def-row eng :plan 2))))
            (is (= {:weeks 1}
                   (get-in (rdef eng :plan)
                           [:piloted-law :population :where])))
            (is (= 2 (:law-revision (reload eng :plan p1))))
            (is (= 1 (:law-revision (reload eng :plan p2)))))
          (testing "one probe, two verdicts — each row under its own law"
            (is (= [] (finalize-warnings eng p1))
                "the piloted row judges under the resident (lenient) law")
            (is (= [strict-warning] (finalize-warnings eng p2))
                "the rest judge under the stored current (strict) law"))
          (testing "withdraw: the population returns to the current law"
            (inv/invoke! eng :definition (:id d2) :withdraw nil
                         {:principal elena})
            (is (= :withdrawn (:state (def-row eng :plan 2))))
            (is (nil? (:piloted-law (rdef eng :plan))))
            (is (= 1 (:law-revision (reload eng :plan p1))))
            ;; the overlay STAYS (ns docstring's withdraw semantics):
            ;; the resident code still expresses the withdrawn law, so
            ;; revision 1 keeps serving from its stored strict trees
            (is (contains? (:judgment-laws (rdef eng :plan)) 1))
            (is (= [strict-warning] (finalize-warnings eng p1)))))))))

;; ── 5. pilot after= ─────────────────────────────────────────────────

(deftest pilot-after-population
  (fresh!)
  (let [ids (atom {})]
    (with-eng [fx/meal (fx/plan-resource)] :promote
      (fn [eng]
        (swap! ids assoc
               :p1 (:id (:row (inv/create! eng :plan (plan-body 1)
                                           {:principal elena}))))))
    (with-eng [fx/meal (fx/plan-resource {:calendar-when lenient-calendar})]
      :propose
      (fn [eng]
        (inv/invoke! eng :definition (:id (def-row eng :plan 2))
                     :pilot {:after true}
                     {:principal elena :idempotency-key (str (random-uuid))})
        (testing "grandfathering forward: existing rows keep their law"
          (is (= 1 (:law-revision (reload eng :plan (:p1 @ids)))))
          (is (= [strict-warning] (finalize-warnings eng (:p1 @ids)))))
        (testing "new creates stamp the piloted revision and judge under it"
          (let [{:keys [row]} (inv/create! eng :plan (plan-body 1)
                                           {:principal elena})]
            (is (= 2 (:law-revision row)))
            (is (= [] (finalize-warnings eng (:id row))))))))))

;; ── 7. grandfather (adoption :never) + adopt ────────────────────────

(defn- gated-resource
  "A task-like kind whose rows finish under the gate they were born
  under (:adoption :never). The close gate's law is the parameter."
  [close-when]
  (r/resource
   {:kind :gated
    :plural "gates"
    :adoption :never
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:ticks {:optional true} [:maybe :int]]]
    :actions
    {:close {:from #{:open} :to :done
             :guards [(g/expr {:name :enough-ticks
                               :when close-when
                               :explain "Not enough ticks yet ({n})."
                               :vars {:n '(data :ticks)}})]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A closed row is history."}}}}))

(deftest grandfather-never-adoption
  (fresh!)
  (let [gid (atom nil)]
    ;; deploy 1: the strict gate — three ticks to close
    (with-eng [(gated-resource '(<= 3 (data :ticks)))] :promote
      (fn [eng]
        (reset! gid (:id (:row (inv/create! eng :gated
                                            {:title "g1" :ticks 2}
                                            {:principal elena}))))))
    ;; deploy 2: the lenient gate, held then promoted
    (with-eng [(gated-resource '(<= 1 (data :ticks)))] :propose
      (fn [eng]
        (testing "held: the row refuses AND renders under revision 1's
                  stored tree, though the resident code is lenient"
          (let [env (envelope eng :gated @gid)]
            (is (= "Not enough ticks yet (2)."
                   (get-in env ["unavailable" "close" "reason"])))
            (is (not (contains? (get env "actions") "close"))))
          (let [p (problem-of #(inv/invoke! eng :gated @gid :close nil
                                            {:principal elena}))]
            (is (= 409 (:status p)))
            (is (= "Not enough ticks yet (2)." (:detail p))
                "advertisement and enforcement share the row's law")))
        (inv/invoke! eng :definition (:id (def-row eng :gated 2))
                     :promote nil {:principal elena})
        (testing "the old revision grandfathers; its row keeps its birth
                  gate forever, with adopt advertised"
          (is (= :grandfathered (:state (def-row eng :gated 1))))
          (is (= 1 (:law-revision (reload eng :gated @gid))))
          (let [env (envelope eng :gated @gid)]
            (is (= "Not enough ticks yet (2)."
                   (get-in env ["unavailable" "close" "reason"])))
            (is (= (str "/api/gates/" @gid "/-/adopt")
                   (get-in env ["actions" "adopt" "href"])))
            (is (= {"to" "open"} (get-in env ["actions" "adopt" "effect"])))))
        (testing "a new row is born under the current (lenient) law —
                  two live gates, one collection"
          (let [{:keys [row]} (inv/create! eng :gated
                                           {:title "g2" :ticks 2}
                                           {:principal elena})]
            (is (= 2 (:law-revision row)))
            (is (contains? (get (envelope eng :gated (:id row)) "actions")
                           "close"))))
        (testing "adopt restamps, flips the verdict, retires the empty law"
          (let [{:keys [row transition]}
                (inv/invoke! eng :gated @gid :adopt nil {:principal elena})]
            (is (= 2 (:law-revision row)))
            (is (= :adopt (:action transition)))
            (is (= :open (:from-state transition)))
            (is (= :open (:to-state transition)))
            (is (= 2 (:law-revision transition))))
          (let [env (envelope eng :gated @gid)]
            (is (contains? (get env "actions") "close"))
            (is (not (contains? (get env "actions") "adopt"))))
          (is (= :superseded (:state (def-row eng :gated 1)))
              "revision 1 supersedes when its last row adopts")
          (is (not (contains? (:judgment-laws (rdef eng :gated)) 1))))
        (testing "the close lands under revision 2; history replays"
          (let [{:keys [row]} (inv/invoke! eng :gated @gid :close nil
                                           {:principal elena})]
            (is (= :done (:state row))))
          (is (= [] (conf/replay-violations eng))))))))
