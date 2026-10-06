(ns waymark10.quests-test
  "The quest kind (docs/spec-quests.md): the birth stamps, the create
  guards, who may take which door, and the engine's `plan`. Memory
  storage and the real engine. No consumer runs: the engine's own doors
  are walked by hand with the engine's actor. The consumer's cases at
  the bottom drain it directly, and the mapping's run on canned
  answers with no engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
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

(deftest a-row-reads-its-progress-and-its-next-step
  ;; one step of each state: the collection row's two lines
  (let [self "/api/chores/1"
        step (fn [n whose state & {:as more}]
               (merge {:n n :door "finish" :self self :whose whose :state state} more))
        done (step 1 "person" "done" :note "Write the guide.")
        nxt (step 2 "choice" "next" :note "Pick the reviewer." :needs ["reviewer"])
        seat (step 3 "seat" "waiting" :waiting_on "Planner")
        held (step 4 "held" "waiting")
        later (step 5 "person" "later" :note "Complete the epic.")
        row (fn [& plan] {:data {:plan (vec plan)}})]
    (testing "the count is k done, n known so far, and never k of n"
      (is (= "1 done, 5 known so far"
             (quests/progress-line (row done nxt seat held later) nil)))
      (is (nil? (quests/progress-line (row) nil)) "no plan yet"))
    (testing "the head step is the first one not done"
      (is (= "Pick the reviewer." (quests/next-line (row done nxt seat held later) nil)))
      (is (= "waiting on Planner" (quests/next-line (row done seat later) nil)))
      (is (= "waiting on your tap" (quests/next-line (row done held later) nil)))
      (is (= "finish" (quests/next-line (row (dissoc nxt :note)) nil))
          "a step with no note reads as its door")
      (is (nil? (quests/next-line (row done) nil)) "every known step is done"))
    (testing "a stored step's state may be a keyword"
      (is (= "1 done, 1 known so far"
             (quests/progress-line (row (update done :state keyword)) nil))))))

;; ── the mapping (Quests 1b): canned rehearsal answers, no engine ────

(defn- seats-for
  "A seat lookup that knows one door."
  [k action]
  (when (= ["chore" "reopen"] [k action]) ["Planner"]))

(defn- shape [plan ks]
  (mapv #(mapv % ks) (:plan plan)))

(deftest the-mapping-orders-person-writes-and-ends-on-the-goal
  (let [plan (quests/answer->plan
              {:writes [{:door "q_latch.lift" :row "/api/q_latches/1" :input nil}
                        {:door "q_bolt.draw" :row "/api/q_bolts/2" :input nil}
                        {:door "q_crate.open" :row "/api/q_crates/3" :input nil}]
               :rehearsal true :first-estimate true}
              seats-for)]
    (is (= [[1 "lift" "/api/q_latches/1" "person" "next"]
            [2 "draw" "/api/q_bolts/2" "person" "later"]
            [3 "open" "/api/q_crates/3" "person" "later"]]
           (shape plan [:n :door :self :whose :state])))
    (is (true? (:plan_is_estimate plan)))
    (is (nil? (:blocked_reason plan)))
    (is (nil? (:waiting_on plan)))))

(deftest the-mapping-names-the-seat-a-step-waits-on
  (testing "a refusal on the bound row names the seats that hold the door"
    (let [plan (quests/answer->plan
                {:writes []
                 :blocked-on [{:door "chore.reopen" :row "/api/chores/9" :needs [] :or []
                               :reason "Only the planner reopens a chore."}]
                 :stack [{:door "chore.finish" :row "/api/chores/9"}]
                 :rehearsal true :first-estimate true}
                seats-for)]
      (is (= [[1 "reopen" "seat" "waiting" "Planner"]
              [2 "finish" "person" "later" nil]]
             (shape plan [:n :door :whose :state :waiting_on])))
      (is (= "More steps may follow once this one is done."
             (:note (second (:plan plan)))))
      (is (= "Planner" (:waiting_on plan)))))
  (testing "an unseen row, with no seat that holds the door"
    (let [plan (quests/answer->plan
                {:writes []
                 :blocked-on [{:door "pantry.stock" :row nil :needs [] :or []
                               :reason "Not found"}]
                 :stack [{:door "chore.finish" :row "/api/chores/9"}]}
                seats-for)]
      (is (= [["stock" "/api/chores/9" "seat" "waiting"]
              ["finish" "/api/chores/9" "person" "later"]]
             (shape plan [:door :self :whose :state])))
      (is (= "someone who holds pantry.stock" (:waiting_on plan))))))

(deftest the-mapping-marks-a-confirm-a-hold-and-a-choice
  (testing "a confirm door carries its consequence and is the owner's next"
    (let [plan (quests/answer->plan
                {:writes []
                 :blocked-on [{:door "pt_seal.break" :row "/api/pt_seals/1" :needs [] :or []
                               :confirm true
                               :consequence "The seal cannot be made whole again."
                               :reason "safety.confirm is true"}]
                 :stack [{:door "pt_vault.open" :row "/api/pt_vaults/2"}]}
                seats-for)]
      (is (= [["break" "confirm" "next" "The seal cannot be made whole again."]]
             (shape plan [:door :whose :state :note])))))
  (testing "a rehearsed hold is a write marked :hold; a real one blocks"
    (let [rehearsed (quests/answer->plan
                     {:writes [{:door "pt_turnstile.turn" :row "/api/pt_turnstiles/1"
                                :hold true}]}
                     seats-for)
          held (quests/answer->plan
                {:writes []
                 :blocked-on [{:door "pt_turnstile.turn" :row "/api/pt_turnstiles/1"
                               :held true :held_call "hc-1"}]}
                seats-for)]
      (is (= [["turn" "held" "waiting" "your tap"]]
             (shape rehearsed [:door :whose :state :waiting_on])
             (shape held [:door :whose :state :waiting_on])))
      (is (= "your tap" (:waiting_on rehearsed)))))
  (testing "a remedy with no row chosen, and a door with an input to give"
    (let [plan (quests/answer->plan
                {:writes []
                 :blocked-on [{:door "plan_day.assign_meal" :row nil :needs [] :or []
                               :reason "No row was chosen for this remedy."}
                              {:door "plan.finalize" :row "/api/plans/4"
                               :needs [:meal_id] :or []}]
                 :stack [{:door "plan.finalize" :row "/api/plans/4"}]}
                seats-for)]
      (is (= [["assign_meal" "/api/plans/4" "choice" "next" nil]
              ["finalize" "/api/plans/4" "choice" "later" ["meal_id"]]]
             (shape plan [:door :self :whose :state :needs]))))))

(deftest the-mapping-says-why-a-cycle-is-blocked
  (let [plan (quests/answer->plan
              {:writes []
               :blocked-on [{:door "latch.lift" :row "/api/latches/1" :needs [] :or []
                             :reason :cycle}]
               :stack [{:door "latch.lift" :row "/api/latches/1"}]}
              seats-for)]
    (is (empty? (:plan plan)))
    (is (str/includes? (str (:blocked_reason plan)) "latch.lift"))
    (is (nil? (:waiting_on plan)))))

;; ── the consumer: fixture kinds whose remedy is BOUND to a row ──────

;; a cross-kind read, so the render probe declines and the dry run
;; answers the refusal with its remedy bound to the crate's own latch
(g/defguard the-latch-is-up
  {:reads [:q_latch]
   :explain "Lift the latch first."
   :remedies [{:door :q_latch/lift :id '(data :latch_id)}]}
  [row _inp ctx]
  (if-some [read (:read ctx)]
    (let [latch (read :q_latch (get-in row [:data :latch_id]))]
      (if (= "up" (some-> latch :state name)) (t/allow) (t/deny)))
    (t/allow)))

(def ^:private routine {:idempotent true :reversible true :confirm false})

(def ^:private latch
  (r/resource
   {:kind :q_latch
    :plural "q_latches"
    :states [:down :up]
    :initial :down
    :summary "Latch · {state}"
    :schema [:map [:label {:optional true} [:maybe [:string {:max 40}]]]]
    :actions
    {:lift {:from #{:down} :to :up :safety routine}
     :lower {:from #{:up} :to :down :safety routine}}}))

(def ^:private crate
  (r/resource
   {:kind :q_crate
    :plural "q_crates"
    :states [:shut :open]
    :initial :shut
    :summary "Crate · {state}"
    :schema [:map
             [:latch_id {:not-a-ref "quests fixture: the-latch-is-up binds its remedy to it"}
              [:string {:max 80}]]]
    :actions
    {:open {:from #{:shut} :to :open
            :guards [the-latch-is-up]
            :safety routine}
     :close {:from #{:open} :to :shut :safety routine}}}))

(defn- crate-engine
  "An engine with the fixture chain, and the owner made a member as the
  identity boundary makes anybody who calls."
  []
  (let [eng (engine/engine {:storage (memory/storage) :resources [chore latch crate]})]
    ((engine/handler eng) {:request-method :get :uri "/api/q_crates"
                           :headers {"x-waymark-principal" "colton"}})
    eng))

(defn- make! [eng kind data]
  (str (:id (:row (inv/create! eng kind data {:principal person})))))

(defn- hear!
  "Drain the quests' consumer, from the log's origin on its first pass."
  [eng]
  (consumers/drain-consumer! eng quests/consumer-name (quests/consumer-fn eng)
                             {:from-origin? true}))

(defn- crate-quest!
  "A shut crate behind a lowered latch, and the quest to open it."
  [eng]
  (let [l (make! eng :q_latch {})
        c (make! eng :q_crate {:latch_id l})
        self (str "/api/q_crates/" c)]
    {:latch l :crate c :self self
     :quest (:id (:row (inv/create! eng :quest {:self self :action "open"}
                                    {:principal person})))}))

(deftest accepting-a-quest-lands-the-plan-the-rehearsal-found
  (let [eng (crate-engine)
        {:keys [quest self]} (crate-quest! eng)
        _ (hear! eng)
        d (data-of eng quest)
        answer (mcp/rehearse eng {:principal "colton"} self :open nil)]
    (is (= ["q_latch.lift" "q_crate.open"] (mapv :door (:writes answer))) (pr-str answer))
    (is (= (mapv :row (:writes answer)) (mapv :self (:plan d)))
        "each step acts on the row the rehearsal named")
    (is (= ["lift" "open"] (mapv :door (:plan d))))
    (is (= ["person" "person"] (mapv (comp name :whose) (:plan d))))
    (is (= ["next" "later"] (mapv (comp name :state) (:plan d))))
    (is (true? (:plan_is_estimate d)))
    (is (some? (:planned_at d)))
    (is (nil? (:blocked_reason d)))))

(deftest replan-after-a-step-taken-by-hand-lands-a-shorter-plan
  (let [eng (crate-engine)
        {:keys [quest latch]} (crate-quest! eng)]
    (hear! eng)
    (is (= 2 (count (:plan (data-of eng quest)))))
    (inv/invoke! eng :q_latch latch :lift {}
                 {:principal person :idempotency-key (str (random-uuid))})
    (take! eng quest person :replan)
    (hear! eng)
    (let [d (data-of eng quest)]
      (is (= ["open"] (mapv :door (:plan d))))
      (is (= ["next"] (mapv (comp name :state) (:plan d)))))))

(deftest replaying-the-log-writes-nothing-new
  (let [eng (crate-engine)
        {:keys [quest]} (crate-quest! eng)
        _ (hear! eng)
        before (data-of eng quest)
        ;; a second cursor at the origin hears every entry again
        heard (consumers/drain-consumer! eng :quests-replay (quests/consumer-fn eng)
                                         {:from-origin? true})]
    (is (pos? heard))
    (is (= 2 (count (:plan before))))
    (is (= before (data-of eng quest)) "the plan and its stamp are the ones first written")))

;; ── Quests 2: two remedies before the goal ──────────────────────────

(g/defguard the-crate-is-open
  {:reads [:q_crate]
   :explain "Open the crate first."
   :remedies [{:door :q_crate/open :id '(data :crate_id)}]}
  [row _inp ctx]
  (if-some [read (:read ctx)]
    (let [c (read :q_crate (get-in row [:data :crate_id]))]
      (if (= "open" (some-> c :state name)) (t/allow) (t/deny)))
    (t/allow)))

(def ^:private vault
  (r/resource
   {:kind :q_vault
    :plural "q_vaults"
    :states [:shut :open]
    :initial :shut
    :summary "Vault · {state}"
    :schema [:map
             [:crate_id {:not-a-ref "quests fixture: the-crate-is-open binds its remedy to it"}
              [:string {:max 80}]]]
    :actions
    {:open {:from #{:shut} :to :open
            :guards [the-crate-is-open]
            :safety routine}
     :close {:from #{:open} :to :shut :safety routine}}}))

(defn- vault-engine []
  (let [eng (engine/engine {:storage (memory/storage) :resources [chore latch crate vault]})]
    ((engine/handler eng) {:request-method :get :uri "/api/q_vaults"
                           :headers {"x-waymark-principal" "colton"}})
    eng))

(defn- vault-quest!
  "A shut vault behind a shut crate behind a lowered latch, and the
  quest to open the vault: lift, open, open."
  [eng]
  (let [l (make! eng :q_latch {})
        c (make! eng :q_crate {:latch_id l})
        v (make! eng :q_vault {:crate_id c})
        self (str "/api/q_vaults/" v)]
    {:latch l :crate c :vault v :self self
     :quest (:id (:row (inv/create! eng :quest {:self self :action "open"}
                                    {:principal person})))}))

(defn- move! [eng kind id action principal]
  (inv/invoke! eng kind (str id) action {}
               {:principal principal :idempotency-key (str (random-uuid))}))

(defn- row-of [eng kind id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) kind)]
    (store/with-tx st
      (fn [tx]
        (some->> (store/load-row st tx kind (str id) {})
                 (inv/decode-row rdef))))))

(defn- invitation-of
  "The invitation the quest names, as its row; nil when it names none."
  [eng quest]
  (some->> (:invitation (data-of eng quest)) str (row-of eng :invitation)))

(defn- states [d] (mapv (comp name :state) (:plan d)))

(deftest landing-a-step-plans-again-and-moves-the-invitation
  (let [eng (vault-engine)
        {:keys [quest latch crate]} (vault-quest! eng)
        _ (hear! eng)
        d (data-of eng quest)
        first-one (invitation-of eng quest)]
    (is (= ["lift" "open" "open"] (mapv :door (:plan d))) (pr-str d))
    (is (= ["next" "later" "later"] (states d)))
    (is (= "open" (some-> first-one :state name)) "the first step is handed to the owner")
    (is (= (str "/api/q_latches/" latch) (get-in first-one [:data :self])))
    (is (= "lift" (get-in first-one [:data :action])))
    (is (= "colton" (get-in first-one [:data :subject])))
    (is (= (str (:id quests/engine-actor)) (get-in first-one [:data :author])))
    (move! eng :q_latch latch :lift person)
    (hear! eng)
    (let [d (data-of eng quest)
          second-one (invitation-of eng quest)]
      (is (= ["lift" "open" "open"] (mapv :door (:plan d))))
      (is (= ["done" "next" "later"] (states d))
          "the step taken stays at the top, then one step and the goal")
      (is (= [1 2 3] (mapv :n (:plan d))))
      (is (= "open" (some-> second-one :state name)))
      (is (= (str "/api/q_crates/" crate) (get-in second-one [:data :self])))
      (is (not= (str (:id first-one)) (str (:id second-one))))
      (is (= "withdrawn" (name (:state (row-of eng :invitation (:id first-one)))))
          "the old step's invitation is closed"))))

(deftest landing-the-goal-finishes-the-quest-and-closes-its-invitation
  (let [eng (vault-engine)
        {:keys [quest latch crate vault]} (vault-quest! eng)]
    (hear! eng)
    (move! eng :q_latch latch :lift person)
    (hear! eng)
    (move! eng :q_crate crate :open person)
    (hear! eng)
    (let [d (data-of eng quest)
          last-one (invitation-of eng quest)]
      (is (= ["done" "done" "next"] (states d)))
      (is (= (str "/api/q_vaults/" vault) (get-in last-one [:data :self])))
      (move! eng :q_vault vault :open person)
      (hear! eng)
      (is (= "finished" (name (:state (row-of eng :quest quest)))))
      (is (not= "open" (name (:state (row-of eng :invitation (:id last-one)))))))))

(deftest another-principals-move-on-a-plan-row-plans-again
  (let [eng (vault-engine)
        {:keys [quest latch crate]} (vault-quest! eng)]
    (hear! eng)
    (move! eng :q_latch latch :lift other)
    (hear! eng)
    (let [d (data-of eng quest)]
      (is (= ["done" "next" "later"] (states d)))
      (is (= (str "/api/q_crates/" crate)
             (get-in (invitation-of eng quest) [:data :self]))))))

(deftest replaying-a-move-writes-nothing-the-second-time
  (let [eng (vault-engine)
        {:keys [quest latch]} (vault-quest! eng)]
    (hear! eng)
    (move! eng :q_latch latch :lift person)
    (hear! eng)
    (let [before (data-of eng quest)
          invited (invitation-of eng quest)
          heard (consumers/drain-consumer! eng :quests-replay (quests/consumer-fn eng)
                                           {:from-origin? true})]
      (is (pos? heard))
      (is (= before (data-of eng quest)) "the plan, its stamp and its invitation stand")
      (is (= "open" (name (:state (row-of eng :invitation (:id invited)))))))))

(deftest a-move-on-a-row-no-quest-names-plans-nothing
  (let [eng (vault-engine)
        {:keys [quest]} (vault-quest! eng)
        stray (make! eng :q_latch {})]
    (hear! eng)
    (let [before (data-of eng quest)]
      (move! eng :q_latch stray :lift person)
      (hear! eng)
      (is (= before (data-of eng quest))))))

(deftest an-abandoned-quest-closes-its-invitation
  (let [eng (vault-engine)
        {:keys [quest]} (vault-quest! eng)]
    (hear! eng)
    (let [invited (invitation-of eng quest)]
      (is (= "open" (some-> invited :state name)))
      (take! eng quest person :abandon)
      (hear! eng)
      (is (= "withdrawn" (name (:state (row-of eng :invitation (:id invited)))))))))

(deftest a-paused-quest-is-not-planned
  (let [eng (crate-engine)
        {:keys [quest]} (crate-quest! eng)]
    (take! eng quest person :pause)
    (hear! eng)
    (let [d (data-of eng quest)]
      (is (empty? (:plan d)))
      (is (nil? (:planned_at d))))))
