(ns waymark10.walkthroughs-test
  "The walkthrough kind (docs/spec-walkthrough.md § 1 and § 2): the
  create guards over every step and who may take which door. Memory
  storage and the real engine. No consumer runs here: the engine's own
  doors are walked by hand with the engine's actor."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.walkthroughs :as walkthroughs]
            [waymark10.types :as t]))

(def ^:private chore
  "The row a step acts on: one door, two plain arguments and one secret."
  (r/resource
   {:kind :chore
    :plural "chores"
    :states [:open :done]
    :initial :open
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema
    [:map
     [:title {:x-display {:label "Title"}} [:string {:min 1 :max 80}]]
     [:room {:optional true :x-display {:label "Room"}}
      [:maybe [:string {:max 40}]]]
     [:pin {:optional true :x-display {:label "Lock code"}}
      [:maybe [:string {:max 12}]]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:rename {:from #{:open} :to :open
              :input [:map
                      [:title {:x-display {:label "Title"}}
                       [:string {:min 1 :max 80}]]
                      [:room {:optional true :x-display {:label "Room"}}
                       [:maybe [:string {:max 40}]]]
                      [:pin {:optional true :x-secret true
                             :x-display {:label "Lock code"}}
                       [:maybe [:string {:max 12}]]]]
              :handler (fn [row inp _ctx]
                         (update row :data merge (select-keys inp [:title :room :pin])))
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Rename" :order 1}}
     :finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Finish" :order 2}}
     :reopen {:from #{:done} :to :open
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Reopen" :order 3}}}}))

(def ^:private person (t/principal {:id "colton" :display "Colton"}))
(def ^:private other (t/principal {:id "iris" :display "Iris"}))
(def ^:private planner (t/principal {:id "planner" :type :agent}))
(def ^:private the-engine walkthroughs/engine-actor)

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage) :resources [chore]}))

(defn- row-of [eng id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) :walkthrough)]
    (store/with-tx st
      (fn [tx]
        (some->> (store/load-row st tx :walkthrough (str id) {})
                 (inv/decode-row rdef))))))

(defn- state-of [eng id] (some-> (row-of eng id) :state name))

(defn- data-of [eng id] (:data (row-of eng id)))

(defn- chore! [eng title]
  (:id (:row (inv/create! eng :chore {:title title} {:principal person}))))

(defn- grant-seeing
  "The guard's-eye view of a grant whose scope sees these chores."
  [& visible]
  {:id "grant-planner"
   :action? (fn [_kind _action] true)
   :row? (fn [_kind id] (contains? (set (map str visible)) (str id)))})

(defn- person-step
  ([chore-id] (person-step chore-id {}))
  ([chore-id extra]
   (merge {:who "person"
           :self (str "/api/chores/" chore-id)
           :action "rename"
           :fields ["title"]
           :note "Pick the new title here."}
          extra)))

(def ^:private agent-step
  {:who "agent" :note "Watch me: reading the open chores."})

(defn- lead!
  "The planner offers `steps` to Colton under `grant`."
  [eng steps grant]
  (:row (inv/create! eng :walkthrough
                     {:subject "colton" :title "Tidying the chores" :steps steps}
                     {:principal planner :grant grant})))

(defn- take!
  "One door, with a fresh key: `advance` and `step` are not idempotent,
  and a call without one is refused before any guard is asked."
  ([eng id principal action] (take! eng id principal action {}))
  ([eng id principal action input]
   (inv/invoke! eng :walkthrough (str id) action input
                {:principal principal
                 :idempotency-key (str (random-uuid))})))

(defn- refusal
  "The refusal's sentence, or nil when the call went through."
  [f]
  (try (f) nil
       (catch Exception e (or (inv/problem-reason e) "refused"))))

(deftest a-walkthrough-is-born-open-with-its-author-stamped
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        row (lead! eng [(person-step c) agent-step] (grant-seeing c))]
    (is (= "open" (name (:state row))))
    (is (= "planner" (get-in row [:data :author]))
        "the engine stamps the author from the principal")
    (is (= 1 (get-in row [:data :current])))
    (is (nil? (get-in row [:data :waiting_on])) "nothing is open before the start")
    (is (empty? (get-in row [:data :outcomes])))
    (is (= 2 (count (:steps (data-of eng (:id row))))))))

(deftest an-author-cannot-name-a-step-it-cannot-see
  (let [eng (fresh-engine)
        mine (chore! eng "Dishes")
        hidden (chore! eng "Taxes")
        why (refusal #(lead! eng [(person-step mine) (person-step hidden)]
                             (grant-seeing mine)))
        absent (refusal #(lead! eng [(person-step mine) (person-step "no-such-row")]
                                (grant-seeing mine "no-such-row")))]
    (is (some? why) "one unseen step refuses the whole create")
    (is (str/includes? (str why) "step 2") "the refusal names the step")
    (is (str/includes? (str why) "does not see that row"))
    (is (str/includes? (str absent) "does not see that row")
        "a row that does not exist reads the same as one out of scope")
    (is (nil? (refusal #(lead! eng [(person-step mine) (person-step hidden)]
                               (grant-seeing mine hidden)))))))

(deftest a-secret-field-in-any-step-refuses-the-create
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        gr (grant-seeing c)
        named (refusal #(lead! eng [(person-step c)
                                    agent-step
                                    (person-step c {:fields ["room" "pin"]})]
                               gr))
        suggested (refusal #(lead! eng [(person-step c {:suggest {:pin "1234"}})] gr))]
    (is (str/includes? (str named) "step 3"))
    (is (str/includes? (str named) "`pin`"))
    (is (str/includes? (str named) "secret"))
    (is (str/includes? (str suggested) "secret")
        "a key of `suggest` is judged the same way")
    (is (some? (refusal #(lead! eng [(person-step c {:fields ["colour"]})] gr)))
        "a name that is no argument of the action is refused too")))

(deftest a-door-shut-now-does-not-refuse-the-create
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")]
    (inv/invoke! eng :chore (str c) :finish {} {:principal person})
    (is (nil? (refusal #(lead! eng [agent-step (person-step c)] (grant-seeing c))))
        "a done chore offers no rename now; the step is judged when it opens")))

(deftest only-the-subject-starts
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        id (:id (lead! eng [(person-step c)] (grant-seeing c)))]
    (is (some? (refusal #(take! eng id planner :start))) "not the author")
    (is (some? (refusal #(take! eng id other :start))) "not another person")
    (is (= "open" (state-of eng id)))
    (take! eng id person :start)
    (is (= "running" (state-of eng id)))
    (is (= "person" (some-> (:waiting_on (data-of eng id)) name)))
    (is (some? (:step_opened_at (data-of eng id))))))

(deftest the-author-does-not-resume-the-subjects-stop
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        id (:id (lead! eng [(person-step c)] (grant-seeing c)))]
    (take! eng id person :start)
    (take! eng id person :stop {:reason "Back in a minute."})
    (is (= "stopped" (state-of eng id)))
    (is (= "colton" (:stopped_by (data-of eng id))))
    (is (= "Back in a minute." (:stop_reason (data-of eng id))))
    (is (some? (refusal #(take! eng id planner :resume)))
        "an author never overrides a person's stop")
    (is (= "stopped" (state-of eng id)))
    (take! eng id person :resume)
    (is (= "running" (state-of eng id)))
    (take! eng id planner :stop)
    (is (= "planner" (:stopped_by (data-of eng id))))
    (is (some? (refusal #(take! eng id other :resume))))
    (take! eng id planner :resume)
    (is (= "running" (state-of eng id)) "its own stop is the author's to lift")))

(deftest advance-is-the-authors-and-only-on-an-agent-step
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        id (:id (lead! eng [(person-step c) agent-step] (grant-seeing c)))]
    (take! eng id person :start)
    (is (some? (refusal #(take! eng id planner :advance)))
        "step 1 is the person's")
    (take! eng id the-engine :step {:outcome "answered" :by "log-1"})
    (is (= 2 (:current (data-of eng id))))
    (is (= "agent" (some-> (:waiting_on (data-of eng id)) name)))
    (is (some? (refusal #(take! eng id person :advance)))
        "the person cannot end the agent's step")
    (take! eng id planner :advance)
    (let [{:keys [current waiting_on outcomes]} (data-of eng id)]
      (is (= 3 current) "one past the last step")
      (is (nil? waiting_on))
      (is (= [[1 "answered" "log-1"] [2 "done" nil]]
             (mapv (fn [o] [(:step o) (name (:outcome o)) (:by o)]) outcomes))
          "an advance by hand records no transition"))
    (is (some? (refusal #(take! eng id planner :advance)))
        "past the last step there is no agent step to end")))

(deftest step-and-finish-are-the-engines-alone
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        id (:id (lead! eng [(person-step c)] (grant-seeing c)))]
    (take! eng id person :start)
    (doseq [who [person planner other]]
      (is (some? (refusal #(take! eng id who :step {:outcome "answered"}))))
      (is (some? (refusal #(take! eng id who :finish)))))
    (is (= 1 (:current (data-of eng id))))
    (take! eng id the-engine :step {:outcome "skipped" :by "log-2"})
    (is (= 2 (:current (data-of eng id))))
    (take! eng id the-engine :finish)
    (is (= "finished" (state-of eng id)))))
