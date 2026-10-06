(ns waymark10.quests-test
  "The quest kind (docs/spec-quests.md): the birth stamps, the create
  guards, who may take which door, and the engine's `plan`. Memory
  storage and the real engine. No consumer runs: the engine's own doors
  are walked by hand with the engine's actor."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.quests :as quests]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]))

(def ^:private chore
  "The row a quest's goal is a door of."
  (r/resource
   {:kind :chore
    :plural "chores"
    :states [:open :done]
    :initial :open
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 1}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 2}}}}))

(def ^:private person (t/principal {:id "colton" :display "Colton"}))
(def ^:private other (t/principal {:id "iris" :display "Iris"}))
(def ^:private planner (t/principal {:id "planner" :type :agent}))

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage) :resources [chore]}))

(defn- data-of [eng id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) :quest)]
    (store/with-tx st
      (fn [tx]
        (some->> (store/load-row st tx :quest (str id) {})
                 (inv/decode-row rdef)
                 :data)))))

(defn- chore! [eng title]
  (:id (:row (inv/create! eng :chore {:title title} {:principal person}))))

(defn- grant-seeing
  "The guard's-eye view of a grant whose scope sees these chores."
  [& visible]
  {:id "grant-planner"
   :action? (fn [_kind _action] true)
   :row? (fn [_kind id] (contains? (set (map str visible)) (str id)))})

(defn- accept!
  "`principal` accepts the goal of finishing a chore."
  ([eng principal chore-id] (accept! eng principal chore-id {} nil))
  ([eng principal chore-id extra grant]
   (:row (inv/create! eng :quest
                      (merge {:self (str "/api/chores/" chore-id)
                              :action "finish"}
                             extra)
                      (cond-> {:principal principal}
                        grant (assoc :grant grant))))))

(defn- take!
  ([eng id principal action] (take! eng id principal action {}))
  ([eng id principal action input]
   (inv/invoke! eng :quest (str id) action input
                {:principal principal
                 :idempotency-key (str (random-uuid))})))

(defn- refusal
  "The refusal's sentence, or nil when the call went through."
  [f]
  (try (f) nil
       (catch Exception e (or (inv/problem-reason e) "refused"))))

(deftest create-stamps-the-owner-and-the-grant
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        mine (accept! eng person c)
        led (accept! eng planner c {:title "Get the dishes done"}
                     (grant-seeing c))]
    (is (= "active" (name (:state mine))))
    (is (= "colton" (get-in mine [:data :owner]))
        "the engine stamps the owner from the principal")
    (is (nil? (get-in mine [:data :grant]))
        "a person acting as themselves carries no grant")
    (is (empty? (get-in mine [:data :plan])) "born with an empty plan")
    (is (nil? (get-in mine [:data :planned_at])))
    (is (false? (get-in mine [:data :pinned])))
    (is (str/includes? (str (get-in mine [:data :title])) "Finish")
        "the default title names the door")
    (is (str/includes? (str (get-in mine [:data :title])) "Dishes")
        "and the row")
    (is (= "planner" (get-in led [:data :owner])))
    (is (some? (refusal #(accept! eng planner c {:owner "iris"} (grant-seeing c))))
        "the body names no owner: the create model has no such field")
    (is (= "grant-planner" (get-in led [:data :grant])))
    (is (= "Get the dishes done" (get-in led [:data :title])))))

(deftest the-create-guards-refuse-an-unseen-row-and-an-unknown-door
  (let [eng (fresh-engine)
        seen (chore! eng "Dishes")
        hidden (chore! eng "Taxes")
        gr (grant-seeing seen)
        unseen (refusal #(accept! eng planner hidden {} gr))
        absent (refusal #(accept! eng person "no-such-row"))
        no-door (refusal #(accept! eng person seen {:action "launch"} nil))]
    (is (str/includes? (str unseen) "does not see that row"))
    (is (str/includes? (str absent) "does not see that row")
        "a row that does not exist reads the same as one out of scope")
    (is (str/includes? (str no-door) "`launch` is not a door"))
    (is (nil? (refusal #(accept! eng planner seen {} gr))))))

(deftest a-grant-that-does-not-admit-the-goal-door-is-refused-at-create
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        ;; sees the row, and admits every door of it but the goal
        reads-only (assoc (grant-seeing c)
                          :action? (fn [_kind action]
                                     (not= "finish" (name action))))
        shut (refusal #(accept! eng planner c {} reads-only))]
    (is (str/includes?
         (str shut)
         "your grant does not see that row or does not admit that door.")
        "the invitation's sentence: the owner could never take the goal")
    (is (nil? (refusal #(accept! eng planner c {:action "reopen"} reads-only)))
        "the same grant accepts a goal door it does admit")
    (is (nil? (refusal #(accept! eng person c)))
        "a person acting as themselves is judged by the row alone")))

(deftest an-owner-holds-at-most-twenty-active-quests
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        ids (mapv (fn [_] (:id (accept! eng person c))) (range quests/active-cap))]
    (is (str/includes? (str (refusal #(accept! eng person c))) "at most 20"))
    (is (nil? (refusal #(accept! eng other c))) "the cap is per owner")
    (take! eng (first ids) person :pause)
    (is (nil? (refusal #(accept! eng person c))) "a paused quest is not counted")
    (is (some? (refusal #(take! eng (first ids) person :resume)))
        "and resuming it would be a twenty-first")))

(deftest pin-moves-pinned-between-two-quests
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        a (:id (accept! eng person c))
        b (:id (accept! eng person c))
        theirs (:id (accept! eng other c))]
    (take! eng theirs other :pin)
    (take! eng a person :pin)
    (is (true? (:pinned (data-of eng a))))
    (take! eng b person :pin)
    (is (true? (:pinned (data-of eng b))))
    (is (false? (:pinned (data-of eng a))) "the owner's other quest is unpinned")
    (is (true? (:pinned (data-of eng theirs))) "another owner's pin is left alone")
    (take! eng b person :unpin)
    (is (false? (:pinned (data-of eng b))))
    (take! eng a person :pin)
    (take! eng a person :pause)
    (is (false? (:pinned (data-of eng a))) "a paused quest is not pinned")))

(deftest another-principals-doors-are-refused
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        id (:id (accept! eng person c))]
    (doseq [action [:pin :pause :abandon :replan]]
      (is (str/includes? (str (refusal #(take! eng id other action)))
                         "Only the owner")
          (str "another principal's " (name action))))
    (is (some? (refusal #(take! eng id quests/engine-actor :abandon)))
        "the engine is not the owner either")
    (is (nil? (refusal #(take! eng id person :replan))))
    (is (some? (:replan_requested_at (data-of eng id))))
    (is (nil? (refusal #(take! eng id person :abandon))))))

(deftest only-the-engine-writes-the-plan
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        id (:id (accept! eng person c))
        self (str "/api/chores/" c)
        plan {:plan [{:n 1 :door "reopen" :self self :whose "seat"
                      :waiting_on "Planner" :state "waiting"}
                     {:n 2 :door "finish" :self self :whose "person"
                      :note "Finish the chore." :state "later"}]
              :plan_is_estimate true
              :waiting_on "Planner"}]
    (is (some? (refusal #(take! eng id person :plan plan))) "not the owner")
    (is (some? (refusal #(take! eng id planner :plan plan))) "not an agent")
    (is (some? (refusal #(take! eng id person :finish))) "nor the finish")
    (is (empty? (:plan (data-of eng id))) "a refused plan wrote nothing")
    (take! eng id quests/engine-actor :plan plan)
    (let [d (data-of eng id)]
      (is (= 2 (count (:plan d))))
      (is (= ["seat" "person"] (mapv (comp name :whose) (:plan d))))
      (is (= ["waiting" "later"] (mapv (comp name :state) (:plan d))))
      (is (some? (:planned_at d)) "the engine stamps when it planned")
      (is (true? (:plan_is_estimate d)))
      (is (= "Planner" (:waiting_on d)))
      (is (nil? (:blocked_reason d))))
    (take! eng id quests/engine-actor :finish)
    (is (= "finished" (some-> (store/with-tx (:storage eng)
                                (fn [tx] (store/load-row (:storage eng) tx :quest (str id) {})))
                              :state name)))))
