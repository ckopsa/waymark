(ns waymark10.kind-subscriptions-test
  "A kind declares the door subscriptions it manages, and the boot
  writes, keeps, restates and pauses them under the principal each
  names (docs/spec-events.md E-5.4, docs/spec-change.md R-C.12). Real
  Postgres."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.door-receivers :as doors]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ^:private ship-walks-finish
  {:name "ship-walks-finish"
   :kinds ["kind_source"] :actions ["ship"]
   :receiver {:type "door" :kind "kind_target" :door "finish" :id_from "target"}
   :owner "factory10-source"})

(defn- kind-source
  "A source kind that declares `entries` as its subscriptions."
  [entries]
  (r/resource
   {:kind :kind_source :plural "kind_sources" :summary "{data.title} · {state}"
    :states [:open :shipped] :initial :open :terminal #{:shipped}
    :schema [:map
             [:title {:examples ["Ship the parcel"]
                      :x-display {:label "What it is" :help "One line naming the work."}}
              [:string {:min 1 :max 80}]]
             [:target {:optional true
                       :examples ["0b6f1c1e-0000-4000-8000-000000000000"]
                       :x-display {:label "The row it names"
                                   :help "The id of the row a walk moves."}}
              [:maybe [:string {:min 1 :max 120}]]]]
    :filterable {:state #{:eq :in}} :default-filters {:state "open"}
    :subscriptions entries
    :actions {:ship {:from #{:open} :to :shipped :display {:label "Ship" :order 1}
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "Shipped is shipped."}}}}))

(def ^:private kind-target
  (r/resource
   {:kind :kind_target :plural "kind_targets" :summary "{data.title} · {state}"
    :states [:waiting :done] :initial :waiting :terminal #{:done}
    :schema [:map [:title {:examples ["Receive the parcel"]
                           :x-display {:label "What it is" :help "One line naming the work."}}
                   [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}} :default-filters {:state "waiting"}
    :actions {:finish {:from #{:waiting} :to :done :display {:label "Finish" :order 1}
                       :safety {:idempotent true :reversible false :confirm false
                                :one-way "Done is done."}}}}))

(def ^:private tables
  ["kind_sources" "kind_targets" "schedules" "seats" "models" "sittings" "definitions"
   "members" "roles" "grants" "approval_requests" "attachments" "subscriptions" "jobs"
   "invitations" "walkthroughs" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_cursors" "waymark10_drafts"])

(def ^:dynamic *st* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st (fn [tx] (doseq [table tables]
                                     (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*st* st] (f))
        (finally (pg/close! st))))))

(def ^:private elena (t/principal {:id "elena" :type :human :display "Elena"}))
(def ^:private model (t/principal {:id "agent-x" :type :agent :display "A model"}))

(defn- boot!
  "An engine booted on the one store, its source kind declaring `entries`."
  [entries]
  (engine/engine {:storage *st* :resources [(kind-source entries) kind-target]}))

(defn- kept
  "The active and paused rows a kind keeps."
  []
  (into []
        (comp (mapcat (fn [s] (store/with-tx *st* #(store/query-rows *st* % :subscription {:state s} {:all true}))))
              (filter #(= "kind" (some-> (get-in % [:data :managed_by]) name))))
        [:active :paused]))

(defn- raw [kind id]
  (store/with-tx *st* #(store/load-row *st* % kind (str id) {})))

(defn- moves [kind id action]
  (filterv #(= action (keyword (name (:action %))))
           (store/with-tx *st* #(store/transitions *st* % {:kind kind :resource-id (str id)} {}))))

(defn- actor [m k] (let [a (:actor m)] (when (map? a) (str (get a k)))))

(defn- refusal [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (if (:waymark10/problem (ex-data e)) (ex-data e) (throw e)))))

(deftest a-kind-keeps-the-subscriptions-it-declares
  (let [eng (boot! [ship-walks-finish])
        [sub & more] (kept)
        id (str (:id sub))]
    (testing "the boot writes one active row, the kind's own, owned by the declared principal"
      (is (some? sub))
      (is (empty? more))
      (is (= "active" (name (:state sub))))
      (is (= "kind_source" (get-in sub [:data :managed_kind])))
      (is (= "ship-walks-finish" (get-in sub [:data :managed_entry])))
      (is (= "factory10-source" (str (get-in sub [:data :owner]))))
      (is (nil? (get-in sub [:data :owner_grant]))))

    (testing "a second boot writes nothing"
      (boot! [ship-walks-finish])
      (let [subs (kept)]
        (is (= [id] (mapv (comp str :id) subs)))
        (is (= (:version sub) (:version (first subs))))
        (is (empty? (moves :subscription id :restate)))))

    (testing "its walk is the declared principal's hand, carrying the subscription"
      (let [target (:id (:row (inv/create! eng :kind_target {:title "parcel"} {:principal elena})))
            source (:id (:row (inv/create! eng :kind_source {:title "walked" :target (str target)}
                                           {:principal elena})))]
        (inv/invoke! eng :kind_source (str source) :ship nil {:principal elena})
        (doors/drain! eng)
        (is (= "done" (some-> (:state (raw :kind_target target)) name)))
        (let [ms (moves :kind_target target :finish)]
          (is (= 1 (count ms)))
          (is (= "factory10-source" (actor (first ms) :id)))
          (is (= id (actor (first ms) :subscription))))))

    (testing "no person or model restates, pauses or revokes it, nor writes one"
      (doseq [p [elena model]]
        (is (some? (refusal #(inv/invoke! eng :subscription id :restate
                                          {:description "mine now"} {:principal p}))))
        (is (some? (refusal #(inv/invoke! eng :subscription id :pause nil {:principal p}))))
        (is (some? (refusal #(inv/invoke! eng :subscription id :revoke nil {:principal p}))))
        (is (some? (refusal #(inv/create! eng :subscription
                                          (assoc (dissoc ship-walks-finish :name :owner)
                                                 :managed_by "kind")
                                          {:principal p})))))
      (is (= "active" (name (:state (raw :subscription id)))))
      (is (nil? (get-in (raw :subscription id) [:data :description]))))

    (testing "a changed declaration restates the row in place"
      (boot! [(assoc ship-walks-finish :description "Ships finish their targets")])
      (let [[s & more] (kept)]
        (is (= id (str (:id s))))
        (is (empty? more))
        (is (= "Ships finish their targets" (get-in s [:data :description])))
        (is (= "factory10-source" (str (get-in s [:data :owner]))))
        (is (= 1 (count (moves :subscription id :restate))))))

    (testing "a removed entry pauses its row"
      (boot! nil)
      (let [[s & more] (kept)]
        (is (= id (str (:id s))))
        (is (empty? more))
        (is (= "paused" (name (:state s))))))

    (testing "declaring it again resumes the same row"
      (boot! [ship-walks-finish])
      (let [[s & more] (kept)]
        (is (= id (str (:id s))))
        (is (empty? more))
        (is (= "active" (name (:state s))))
        (is (nil? (get-in s [:data :description])))))

    (testing "a changed owner pauses the old row and writes a fresh one it owns"
      (let [restates (count (moves :subscription id :restate))
            _ (boot! [(assoc ship-walks-finish :owner "factory10-other")])
            subs (kept)
            {active "active" paused "paused"} (group-by (comp name :state) subs)]
        (is (= 2 (count subs)))
        (is (= [id] (mapv (comp str :id) paused)))
        (is (= 1 (count active)))
        (is (not= id (str (:id (first active)))))
        (is (= "factory10-other" (str (get-in (first active) [:data :owner]))))
        (is (= "factory10-source" (str (get-in (first paused) [:data :owner]))))
        (is (= restates (count (moves :subscription id :restate))))))

    (testing "a second boot under the new owner writes nothing"
      (let [before (kept)]
        (boot! [(assoc ship-walks-finish :owner "factory10-other")])
        (is (= (set (map (juxt (comp str :id) :version) before))
               (set (map (juxt (comp str :id) :version) (kept)))))))))
