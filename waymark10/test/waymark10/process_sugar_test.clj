(ns waymark10.process-sugar-test
  "The :process key (docs/spec-process.md): a workflow as a resource.

  Three things are pinned here, in the decision-sugar tradition:

  1. the PROJECTION — the step machine, the doors, the derived
     :touches and schema entries a declaration yields, and that the
     fingerprint is a function of the steps (same steps, same hash;
     a moved step input, a moved hash);
  2. the BEHAVIOUR against the in-memory storage twin — a durable
     process walks its steps through the cross-write doors as the
     tapping principal, binds the ids it births, rolls back through
     the declared undo doors in reverse, refuses when a target's own
     guard refuses (and lands nothing), and an atomic process lands
     all or none; every inner transition wears the outer correlation
     id, so the touches promise holds by the conformance library's
     own measure;
  3. the REFUSALS — the def-site sentences (waymark10.process/desugar)
     and the assembly-time ones (checks_assembly/check-process), each
     checked where a reader can see it."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.fingerprint :as fp]
            [waymark10.guards :as g]
            [waymark10.registry :as registry]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.test.conformance :as conf]
            [waymark10.types :as t]))

;; ── the world: a plan, its list, and the rollover that ties them ────

(def ^:private plan-ready
  (g/expr {:name :plan-ready
           :when '(= (data :ready) true)
           :explain "A plan is finalized once it is marked ready."}))

(def pp-plan
  (r/resource
   {:kind :pp_plan
    :states [:draft :final :closed]
    :initial :draft
    :terminal #{:closed}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:max 80}]]
             [:ready {:optional true} [:maybe :boolean]]]
    :actions
    {:finalize {:from #{:draft} :to :final
                :guards [plan-ready]
                :undo :reopen
                :safety {:idempotent true :reversible false :confirm false}}
     :reopen {:from #{:final} :to :draft
              :undo :finalize
              :safety {:idempotent true :reversible false :confirm false}}
     :close {:from #{:final} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A closed plan is history."}}}}))

(def pp-list
  (r/resource
   {:kind :pp_list
    :states [:draft :discarded]
    :initial :draft
    :terminal #{:discarded}
    :summary "list for {data.plan_id} · {state}"
    :schema [:map
             [:plan_id {:kind :pp_plan :filter #{:eq}} :waymark/ref]
             [:note {:optional true} [:maybe [:string {:max 80}]]]]
    :actions
    {:discard {:from #{:draft} :to :discarded
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A discarded list stays readable as history."}}}}))

(def ^:private durable-steps
  [{:name :close
    :do [:pp_plan :plan_id :finalize]
    :undo [:pp_plan :plan_id :reopen]}
   {:name :compile
    :do [:pp_list :create {:plan_id '(data :plan_id) :note "compiled"}]
    :binds :list_id
    :undo [:pp_list :list_id :discard]}
   {:name :seal
    :do [:pp_plan :plan_id :close]
    :pivot true}])

(def ^:private rollover-decl
  {:kind :pp_rollover
   :plural "pp_rollovers"
   :process {:mode :durable
             :binds {:plan_id :pp_plan}
             :steps durable-steps}})

(def pp-rollover (r/resource rollover-decl))

(def ^:private atomic-steps
  [{:name :close :do [:pp_plan :plan_id :finalize]}
   {:name :compile
    :do [:pp_list :create {:plan_id '(data :plan_id) :note "compiled"}]
    :binds :list_id}
   {:name :seal :do [:pp_plan :plan_id :close]}])

(def pp-rollover-atomic
  (r/resource
   {:kind :pp_rollover_atomic
    :plural "pp_rollover_atomics"
    :process {:mode :atomic
              :binds {:plan_id :pp_plan}
              :steps atomic-steps}}))

(def ^:private ana (t/principal {:id "ana" :display "Ana"}))

(defn- fresh-engine []
  (inv/engine {:storage (memory/storage)
               :resources [pp-plan pp-list pp-rollover pp-rollover-atomic]}))

(defn- opts [& [cid]]
  (cond-> {:principal ana}
    cid (assoc :correlation-id cid)))

(defn- problem-of [thunk]
  (try (thunk) nil
       (catch Exception e (or (ex-data e) {:message (ex-message e)}))))

(defn- reload [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind id {}))))

(defn- transitions-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/transitions (:storage eng) tx
                                {:kind kind :resource-id id} {}))))

(defn- lists-of [eng pid]
  (store/with-tx (:storage eng)
    (fn [tx] (store/query-rows (:storage eng) tx :pp_list {:plan_id pid} {}))))

;; ── 1. the projection ───────────────────────────────────────────────

(deftest the-durable-projection
  (testing "the steps are the machine"
    (is (= [:staged :close_done :compile_done :done :rolled_back :abandoned]
           (:states pp-rollover)))
    (is (= :staged (:initial pp-rollover)))
    (is (= #{:done :rolled_back :abandoned} (:terminal pp-rollover))))
  (testing "one door per step, in step order"
    (let [a (:actions pp-rollover)]
      (is (= #{:close :compile :seal :roll_back :abandon} (set (keys a))))
      (is (= [#{:staged} :close_done] ((juxt :from :to) (:close a))))
      (is (= [#{:close_done} :compile_done] ((juxt :from :to) (:compile a))))
      (is (= [#{:compile_done} :done] ((juxt :from :to) (:seal a))))
      (is (= [1 2 3] (map #(get-in a [% :display :order]) [:close :compile :seal])))))
  (testing "roll_back is offered from every landing before the pivot"
    (is (= #{:close_done :compile_done} (get-in pp-rollover [:actions :roll_back :from])))
    (is (= :rolled_back (get-in pp-rollover [:actions :roll_back :to]))))
  (testing ":touches derive from the steps — never typed"
    (let [a (:actions pp-rollover)]
      (is (= [{:kind :pp_plan :action :finalize}] (get-in a [:close :touches])))
      (is (= [{:kind :pp_list :action :create}] (get-in a [:compile :touches])))
      (is (= [{:kind :pp_plan :action :close}] (get-in a [:seal :touches])))
      (is (= [{:kind :pp_plan :action :reopen :may true}
              {:kind :pp_list :action :discard :may true}]
             (get-in a [:roll_back :touches]))
          "a roll-back reaches a different set from each origin, so every undo is :may")))
  (testing "the bound ids are ref entries; only the create :binds is create input"
    (let [entries (schema/entry-map (:schema pp-rollover))]
      (is (= :pp_plan (get-in entries [:plan_id :properties :kind])))
      (is (false? (get-in entries [:plan_id :optional])))
      (is (= :pp_list (get-in entries [:list_id :properties :kind])))
      (is (true? (get-in entries [:list_id :optional]))))
    (is (= [:plan_id] (schema/entry-keys (:create-schema pp-rollover)))))
  (testing "every projected handler carries a stateable identity"
    (doseq [[aname a] (:actions pp-rollover) :when (:handler a)]
      (is (some? (:waymark10/form (meta (:handler a))))
          (str (name aname) " hashes by its form, not its address"))))
  (testing "the normalized process rides the declaration for the assembly"
    (is (= :durable (get-in pp-rollover [:process :mode])))
    (is (= [:close :compile :seal] (map :name (get-in pp-rollover [:process :steps]))))))

(deftest the-atomic-projection
  (is (= [:staged :done :abandoned] (:states pp-rollover-atomic)))
  (is (= #{:run :abandon} (set (keys (:actions pp-rollover-atomic)))))
  (is (= [{:kind :pp_plan :action :finalize}
          {:kind :pp_list :action :create}
          {:kind :pp_plan :action :close}]
         (get-in pp-rollover-atomic [:actions :run :touches]))
      "one door advertises the whole blast radius"))

(deftest the-fingerprint-is-a-function-of-the-steps
  (let [h (fn [decl] (fp/fingerprint-hash (r/fingerprint (r/resource decl))))]
    (is (= (h rollover-decl) (h rollover-decl))
        "the same steps hash the same across two loads")
    (is (not= (h rollover-decl)
              (h (assoc-in rollover-decl [:process :steps 1 :do 2 :note] "recompiled")))
        "a moved step input moves the law")))

;; ── 2. the behaviour, against the in-memory twin ────────────────────

(defn- staged-rollover!
  "A ready plan and a rollover staged on it."
  [eng]
  (let [pid (:id (:row (inv/create! eng :pp_plan {:title "week 37" :ready true} (opts))))
        rid (:id (:row (inv/create! eng :pp_rollover {:plan_id pid} (opts))))]
    [pid rid]))

(deftest a-durable-process-walks-its-steps-as-the-tapping-hand
  (let [eng (fresh-engine)
        [pid rid] (staged-rollover! eng)]
    (testing "step one moves the plan through its own door"
      (let [{:keys [row]} (inv/invoke! eng :pp_rollover rid :close nil (opts "cid-close"))]
        (is (= :close_done (:state row)))
        (is (= :final (:state (reload eng :pp_plan pid))))
        (is (= "cid-close"
               (:correlation-id (last (transitions-of eng :pp_plan pid))))
            "the inner transition wears the step's correlation id")
        (is (= "ana" (get-in (last (transitions-of eng :pp_plan pid)) [:actor :id]))
            "…and the tapping principal's own name, never a system actor")))
    (testing "a create step births the row and binds its id"
      (let [{:keys [row]} (inv/invoke! eng :pp_rollover rid :compile nil (opts "cid-compile"))
            lists (lists-of eng pid)]
        (is (= :compile_done (:state row)))
        (is (= 1 (count lists)))
        (is (= (:id (first lists)) (get-in row [:data :list_id])))
        (is (= "compiled" (get-in (first lists) [:data :note]))
            "a literal input value lands as written")
        (is (= pid (get-in (first lists) [:data :plan_id]))
            "a (data …) input value reads the process row")))
    (testing "the pivot step finishes the process"
      (let [{:keys [row]} (inv/invoke! eng :pp_rollover rid :seal nil (opts "cid-seal"))]
        (is (= :done (:state row)))
        (is (= :closed (:state (reload eng :pp_plan pid))))))
    (testing "roll_back is not offered past the pivot"
      (is (some? (problem-of #(inv/invoke! eng :pp_rollover rid :roll_back nil (opts)))))
      (is (= :done (:state (reload eng :pp_rollover rid)))))
    (testing "the touches promise held, by the conformance library's own measure"
      (is (= [] (conf/touches-violations eng))))))

(deftest roll-back-reverses-the-completed-steps-newest-first
  (let [eng (fresh-engine)
        [pid rid] (staged-rollover! eng)]
    (inv/invoke! eng :pp_rollover rid :close nil (opts "c1"))
    (inv/invoke! eng :pp_rollover rid :compile nil (opts "c2"))
    (let [{:keys [row]} (inv/invoke! eng :pp_rollover rid :roll_back nil (opts "c3"))
          lst (reload eng :pp_list (get-in row [:data :list_id]))]
      (is (= :rolled_back (:state row)))
      (is (= :discarded (:state lst)) "the born list is discarded")
      (is (= :draft (:state (reload eng :pp_plan pid))) "the plan is reopened")
      (is (= ["discard" "reopen"]
             (->> (store/with-tx (:storage eng)
                    (fn [tx] (store/transitions (:storage eng) tx {} {:limit 1000})))
                  (filter #(= "c3" (:correlation-id %)))
                  (remove #(= :pp_rollover (keyword (:kind %))))
                  (map (comp name :action))))
          "newest step first: the list before the plan")
      (is (= [] (conf/touches-violations eng))))))

(deftest a-refused-inner-write-refuses-the-step-and-lands-nothing
  (let [eng (fresh-engine)
        pid (:id (:row (inv/create! eng :pp_plan {:title "not ready"} (opts))))
        rid (:id (:row (inv/create! eng :pp_rollover {:plan_id pid} (opts))))
        p (problem-of #(inv/invoke! eng :pp_rollover rid :close nil (opts "c-refused")))]
    (is (some? p) "the plan's own guard refuses through the process door")
    (is (= :staged (:state (reload eng :pp_rollover rid))) "the process did not move")
    (is (= :draft (:state (reload eng :pp_plan pid))) "…and neither did the plan")))

(deftest an-atomic-process-lands-all-or-none
  (testing "all"
    (let [eng (fresh-engine)
          pid (:id (:row (inv/create! eng :pp_plan {:title "ready" :ready true} (opts))))
          rid (:id (:row (inv/create! eng :pp_rollover_atomic {:plan_id pid} (opts))))
          {:keys [row]} (inv/invoke! eng :pp_rollover_atomic rid :run nil (opts "atomic-1"))]
      (is (= :done (:state row)))
      (is (= :closed (:state (reload eng :pp_plan pid))))
      (is (= 1 (count (lists-of eng pid))))
      (is (= (:id (first (lists-of eng pid))) (get-in row [:data :list_id])))
      (is (= [] (conf/touches-violations eng)))))
  (testing "none"
    (let [eng (fresh-engine)
          pid (:id (:row (inv/create! eng :pp_plan {:title "not ready"} (opts))))
          rid (:id (:row (inv/create! eng :pp_rollover_atomic {:plan_id pid} (opts))))]
      (is (some? (problem-of #(inv/invoke! eng :pp_rollover_atomic rid :run nil (opts "atomic-2")))))
      (is (= :staged (:state (reload eng :pp_rollover_atomic rid))))
      (is (= :draft (:state (reload eng :pp_plan pid))))
      (is (empty? (lists-of eng pid)) "the one transaction rolled the birth back too"))))

(deftest a-staged-process-can-be-abandoned
  (let [eng (fresh-engine)
        [pid rid] (staged-rollover! eng)
        {:keys [row]} (inv/invoke! eng :pp_rollover rid :abandon nil (opts))]
    (is (= :abandoned (:state row)))
    (is (= :draft (:state (reload eng :pp_plan pid))) "nothing ran")))

;; ── 3. the refusals ─────────────────────────────────────────────────

(defn- refusal [rmap]
  (try (r/normalize-resource rmap) nil
       (catch Exception e (ex-message e))))

(defn- assembly-refusal [rdefs]
  (try (registry/registry rdefs) nil
       (catch Exception e (ex-message e))))

(defn- process [p] (assoc rollover-decl :process p))

(deftest the-def-site-refusals
  (testing "an unknown key is named with the legal set"
    (is (re-find #"unknown key" (refusal (process {:steps durable-steps :mood :durable})))))
  (testing "the steps are the machine"
    (is (re-find #"steps are the machine" (refusal (assoc rollover-decl :states [:x])))))
  (testing "one machine per kind"
    (is (re-find #"one machine per kind"
                 (refusal (assoc rollover-decl :decision {:asks :q :by :who :verdicts []})))))
  (testing "a step wearing a projected door is refused by name"
    (is (re-find #"wears a door the sugar projects"
                 (refusal (process {:steps [{:name :run :do [:pp_plan :plan_id :close]}]
                                    :binds {:plan_id :pp_plan}})))))
  (testing "durable: a step before the pivot must spell its undo"
    (is (re-find #"has no :undo"
                 (refusal (process {:mode :durable
                                    :binds {:plan_id :pp_plan}
                                    :steps [{:name :close :do [:pp_plan :plan_id :finalize]}
                                            {:name :seal :do [:pp_plan :plan_id :close]}]})))))
  (testing "atomic: no pivot, no undo — one transaction lands all or none"
    (is (re-find #"no partial completion"
                 (refusal (process {:mode :atomic
                                    :binds {:plan_id :pp_plan}
                                    :steps [{:name :close :do [:pp_plan :plan_id :finalize] :pivot true}]}))))
    (is (re-find #"never compensates"
                 (refusal (process {:mode :atomic
                                    :binds {:plan_id :pp_plan}
                                    :steps [{:name :close :do [:pp_plan :plan_id :finalize]
                                             :undo [:pp_plan :plan_id :reopen]}]})))))
  (testing "a step reads only its own row and the clock"
    (is (re-find #"reads \[input\]"
                 (refusal (process {:binds {:plan_id :pp_plan}
                                    :steps [{:name :compile
                                             :do [:pp_list :create {:plan_id '(input :plan_id)}]}]})))))
  (testing "an invoked id must come from somewhere"
    (is (re-find #"no :binds, step :binds or :schema entry supplies"
                 (refusal (process {:steps [{:name :close :do [:pp_plan :plan_id :finalize]}]})))))
  (testing "a birth with an undo binds the id the undo needs"
    (is (re-find #"must :bind the born id"
                 (refusal (process {:mode :durable
                                    :binds {:plan_id :pp_plan}
                                    :steps [{:name :compile
                                             :do [:pp_list :create {:plan_id '(data :plan_id)}]
                                             :undo [:pp_list :list_id :discard]}
                                            {:name :seal :do [:pp_plan :plan_id :close]}]})))))
  (testing "one home per action"
    (is (re-find #"one home per action"
                 (refusal (assoc rollover-decl
                                 :actions {:close {:from #{:staged} :to :done
                                                   :safety {:idempotent true
                                                            :reversible false
                                                            :confirm false}}}))))))

(deftest the-assembly-refusals
  (let [with-steps (fn [steps]
                     (r/resource (assoc rollover-decl
                                        :process {:mode :durable
                                                  :binds {:plan_id :pp_plan}
                                                  :steps steps})))]
    (testing "a step naming an unregistered kind"
      (is (re-find #"not registered"
                   (assembly-refusal [pp-plan pp-list
                                      (with-steps [{:name :close
                                                    :do [:pp_ledger :plan_id :finalize]}])]))
          "…is caught only where the engine's kinds are all known"))
    (testing "an undo that does not depart from where the do landed"
      (is (re-find #"not this step's reverse"
                   (assembly-refusal [pp-plan pp-list
                                      (with-steps [{:name :close
                                                    :do [:pp_plan :plan_id :finalize]
                                                    :undo [:pp_plan :plan_id :finalize]}
                                                   {:name :seal
                                                    :do [:pp_plan :plan_id :close]}])]))))
    (testing "a door that is not an action of the target"
      (is (re-find #"not an action"
                   (assembly-refusal [pp-plan pp-list
                                      (with-steps [{:name :close
                                                    :do [:pp_plan :plan_id :archive]}])]))))
    (testing "the whole world assembles clean"
      (is (nil? (assembly-refusal [pp-plan pp-list pp-rollover pp-rollover-atomic]))))))
