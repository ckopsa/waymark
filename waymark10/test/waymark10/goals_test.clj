(ns waymark10.goals-test
  "Measures step 2 (docs/spec-measures.md §3): the owner sets and
  restates a measure's target as a `goal` row, with no law revision. A
  second active goal is refused, naming the first; a restate keeps the
  old target in the log; a non-owner person is refused; a declared
  :default-target is written once by the maintainer, and a malformed
  one is [measure-shape]. In memory."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.goals :as goals]
            [waymark10.server.invoke :as inv]
            [waymark10.server.maintainer :as maintainer]
            [waymark10.server.members :as members]
            [waymark10.server.roles :as roles]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private token "gl_order.shipped_on_time")

(def ^:private on-time
  {:label "Orders shipped within 3 days"
   :stat :rate
   :among "state=shipped"
   :where '(<= (data :shipped_at) (data :placed_at))
   :at :placed_at
   :over '(weeks 4)})

(defn- order-decl [measure]
  {:kind :gl_order
   :plural "gl_orders"
   :states [:placed :shipped]
   :initial :placed
   :terminal #{:shipped}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:max 40}]]
            [:placed_at :waymark/instant]
            [:shipped_at {:optional true} [:maybe :waymark/instant]]]
   :filterable {:state #{:eq :in} :placed_at #{:eq}}
   :measures {:shipped_on_time measure}
   :actions {:ship {:from #{:placed} :to :shipped
                    :safety {:idempotent false :reversible false :confirm false
                             :one-way "Shipped is shipped."}
                    :display {:label "Ship"}}}})

(defn- eng! [measure]
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources [(r/resource (order-decl measure)) goals/goal]})]
    (roles/ensure-factory-roles! eng)
    eng))

(defn- as [id] {:principal (t/principal {:id id :display id})})

(defn- member! [eng id]
  (:row (inv/create! eng :member {:display id :actor_type "human"}
                     {:principal members/registrar :id id})))

(defn- owned! [eng owner]
  (member! eng owner)
  (inv/create! eng :domain {:name "factory" :charter "Some work."
                            :budget_usd_per_week 40M :owner owner}
               (as owner)))

(defn- refusal [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- goal! [eng who inp]
  (:row (inv/create! eng :goal inp (as who))))

(defn- goals-of [eng]
  (let [rdef (get (inv/resources eng) :goal)]
    (mapv #(inv/decode-row rdef %)
          (store/with-tx (:storage eng)
            (fn [tx] (store/query-rows (:storage eng) tx :goal {:measure token} {:limit 50}))))))

(defn- active-goals [eng]
  (filterv #(= "active" (name (:state %))) (goals-of eng)))

(defn- dec= [a b] (zero? (compare (bigdec (str a)) (bigdec (str b)))))

(deftest a-second-active-goal-refuses-naming-the-first
  (let [eng (eng! on-time)]
    (owned! eng "olive")
    (let [g (goal! eng "olive" {:measure token :op ">=" :target 0.95M})
          r (refusal #(goal! eng "olive" {:measure token :op ">=" :target 0.9M}))]
      (is (some? (:id g)))
      (is (some? r))
      (is (re-find #"`gl_order.shipped_on_time` already has a goal of >= 95%; restate it"
                   (pr-str r)))
      (is (re-find (re-pattern (str (:id g))) (pr-str r)))
      (testing "retired, the measure is free again"
        (inv/invoke! eng :goal (str (:id g)) :retire nil (as "olive"))
        (is (some? (:id (goal! eng "olive" {:measure token :op ">=" :target 0.9M}))))))))

(deftest a-restate-keeps-the-old-target-in-the-log
  (let [eng (eng! on-time)]
    (owned! eng "olive")
    (let [g (goal! eng "olive" {:measure token :op ">=" :target 0.95M})
          id (str (:id g))]
      (inv/invoke! eng :goal id :restate {:target 0.9M :note "Holiday week."} (as "olive"))
      (inv/invoke! eng :goal id :restate {:target 0.85M} (as "olive"))
      (let [[now] (active-goals eng)
            logged (->> (store/with-tx (:storage eng)
                          (fn [tx] (store/transitions (:storage eng) tx
                                                      {:kind :goal :resource-id id} {})))
                        (filter #(= :restate (keyword (name (:action %)))))
                        (map #(get-in % [:inputs :target])))]
        (is (dec= 0.85M (get-in now [:data :target])))
        (is (= "Holiday week." (get-in now [:data :note])))
        (is (= ">=" (get-in now [:data :op])))
        (is (= 2 (count logged)))
        (is (some #(dec= 0.9M %) logged) (pr-str logged))))))

(deftest a-non-owner-person-is-refused
  (let [eng (eng! on-time)]
    (owned! eng "olive")
    (member! eng "cole")
    (is (some? (refusal #(goal! eng "cole" {:measure token :op ">=" :target 0.95M}))))
    (let [g (goal! eng "olive" {:measure token :op ">=" :target 0.95M})]
      (is (some? (refusal #(inv/invoke! eng :goal (str (:id g)) :restate
                                        {:target 0.5M} (as "cole")))))
      (is (some? (refusal #(inv/invoke! eng :goal (str (:id g)) :retire nil (as "cole"))))))))

(deftest a-default-target-writes-one-goal-once
  (let [eng (eng! (assoc on-time :default-target {:op ">=" :target 0.95M}))]
    (is (= 1 (maintainer/default-goals! eng)))
    (is (= 0 (maintainer/default-goals! eng)) "a second call writes nothing")
    (let [gs (active-goals eng)]
      (is (= 1 (count gs)))
      (is (dec= 0.95M (get-in (first gs) [:data :target])))
      (is (= ">=" (get-in (first gs) [:data :op])))))
  (testing "none when the measure already has a goal"
    (let [eng (eng! (assoc on-time :default-target {:op ">=" :target 0.95M}))]
      (owned! eng "olive")
      (goal! eng "olive" {:measure token :op ">=" :target 0.8M})
      (is (= 0 (maintainer/default-goals! eng)))
      (is (= 1 (count (active-goals eng))))
      (is (dec= 0.8M (get-in (first (active-goals eng)) [:data :target]))))))

(deftest a-malformed-default-target-is-measure-shape
  (doseq [bad [0.95M {:op ">="} {:op ">" :target 0.95M} {:op ">=" :target "95%"}]]
    (let [msg (str (try (r/resource (order-decl (assoc on-time :default-target bad))) nil
                        (catch Exception e (ex-message e))))]
      (is (re-find #"gl_order \[measure-shape\] measure shipped_on_time :default-target " msg)
          (pr-str bad)))))
