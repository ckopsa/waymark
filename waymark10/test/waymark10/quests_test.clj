(ns waymark10.quests-test
  "The quest kind (docs/spec-quests.md): the birth stamps, the create
  guards, who may take which door, and the engine's `plan`. Memory
  storage and the real engine. No consumer runs: the engine's own doors
  are walked by hand with the engine's actor. The consumer's cases at
  the bottom drain it directly, and the mapping's run on canned
  answers with no engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invitations :as invitations]
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

(defn- log-of [eng id]
  (store/with-tx (:storage eng)
    #(store/transitions (:storage eng) % {:kind :quest :resource-id id} {})))

(deftest pin-unpins-the-other-quest-with-the-engines-hand
  (let [eng (fresh-engine)
        c (chore! eng "Dishes")
        ;; a leash that offers `pin` and not `unpin`, and sees no quest row
        gr (assoc (grant-seeing c)
                  :action? (fn [_kind action] (not= "unpin" (name action))))
        pin! (fn [id]
               (inv/invoke! eng :quest (str id) :pin {}
                            {:principal planner
                             :grant gr
                             :idempotency-key (str (random-uuid))}))
        a (:id (accept! eng planner c {} gr))
        b (:id (accept! eng planner c {} gr))]
    (pin! a)
    (is (nil? (refusal #(pin! b))) "a grant offering `pin` needs no `unpin`")
    (is (true? (:pinned (data-of eng b))) "the second quest is pinned")
    (is (false? (:pinned (data-of eng a))) "the first is unpinned")
    (let [unpin (first (filter #(= :unpin (:action %)) (log-of eng a)))]
      (is (some? unpin) "through its own `unpin` door")
      (is (= (:id quests/engine-actor) (get-in unpin [:actor :id]))
          "the log names the engine's actor")
      (is (nil? (get-in unpin [:actor :grant]))
          "and no grant: the engine's hand wears no leash"))
    (is (some? (refusal #(take! eng b other :unpin)))
        "another principal still does not unpin it")
    (is (nil? (refusal #(take! eng b planner :unpin)))
        "and the owner still does")))

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
  [k action _self]
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

(deftest the-mapping-puts-a-refusals-other-remedy-on-its-step
  (let [plan (quests/answer->plan
              {:writes []
               :blocked-on [{:door "ticket.complete" :row "/api/tickets/7"
                             :needs [:close_reason] :or ["ticket.drop"]}
                            {:door "ticket.drop" :row "/api/tickets/7" :needs []
                             :or ["ticket.complete"] :confirm true
                             :consequence "The ticket is let go."}]
               :stack [{:door "epic.complete" :row "/api/epics/1"}]}
              seats-for)]
    (is (= [[1 "complete" "/api/tickets/7" "choice" "next"]]
           (shape plan [:n :door :self :whose :state]))
        "drop is the other remedy, not a step after complete")
    (is (= [{:door "drop" :self "/api/tickets/7"}]
           (:alternatives (first (:plan plan)))))))

(deftest the-mapping-says-more-may-follow-one-time-and-last
  (testing "a seat head whose refusal named two remedies"
    (let [plan (quests/answer->plan
                {:writes []
                 :blocked-on [{:door "chore.reopen" :row "/api/chores/9" :needs []
                               :or ["chore.drop"]
                               :reason "Only the planner reopens a chore."}
                              {:door "chore.drop" :row "/api/chores/9" :needs []
                               :or ["chore.reopen"]
                               :reason "Only the planner drops a chore."}]
                 :stack [{:door "chore.finish" :row "/api/chores/9"}]}
                seats-for)]
      (is (= [[1 "reopen" "seat" "waiting"]
              [2 "finish" "person" "later"]]
             (shape plan [:n :door :whose :state])))
      (is (= [{:door "drop" :self "/api/chores/9"}]
             (:alternatives (first (:plan plan)))))
      (is (= [nil "More steps may follow once this one is done."]
             (mapv #(when (= "person" (:whose %)) (:note %)) (:plan plan))))))
  (testing "two doors that are somebody else's"
    (let [plan (quests/answer->plan
                {:writes []
                 :blocked-on [{:door "chore.reopen" :row "/api/chores/9" :needs [] :or []
                               :reason "Only the planner reopens a chore."}
                              {:door "pantry.stock" :row nil :needs [] :or []
                               :reason "Not found"}]
                 :stack [{:door "chore.finish" :row "/api/chores/9"}]}
                seats-for)]
      (is (= [["reopen" "seat" "waiting"]
              ["stock" "seat" "later"]
              ["finish" "person" "later"]]
             (shape plan [:door :whose :state]))))))

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

;; ── who a blocked step waits on: the grants and the seats, as read ──

(defn- grant!
  "An accepted grant for `audience`, minted as an approved ask mints one."
  [eng audience scope]
  (let [made (:row (inv/create! eng :grant {:audience audience :scope scope}
                                {:principal grants/approvals-actor}))]
    (:row (inv/invoke! eng :grant (str (:id made)) :accept nil
                       {:principal grants/approvals-actor}))))

(defn- takers
  "Who the quest of `owner` would wait on to reopen this chore."
  [eng owner chore-id]
  ((#'quests/seat-lookup eng owner) "chore" "reopen" (str "/api/chores/" chore-id)))

(deftest a-granted-agent-who-may-take-the-door-is-named
  (let [eng (fresh-engine)
        c (chore! eng "Sweep")
        m (:row (inv/create! eng :member {:display "Planner" :actor_type "agent"}
                             {:principal person}))]
    (grant! eng (str (:id m)) [{:kind "chore" :actions ["reopen"]}])
    (grant! eng "iris" [{:kind "chore" :actions ["finish"]}])
    (is (= ["Planner"] (takers eng "colton" c))
        "the holder is named by its member row; a grant without the door names nobody")))

(deftest a-seat-whose-filter-excludes-the-row-is-not-named
  (let [eng (fresh-engine)
        c (chore! eng "Sweep")
        model (:row (inv/create! eng :model
                                 {:name "quest-frontier"
                                  :display "quest-frontier"
                                  :vendor "anthropic"
                                  :tier "frontier"
                                  :price_input_per_mtok 3M
                                  :price_output_per_mtok 15M
                                  :price_cache_read_per_mtok 0.3M
                                  :price_cache_write_per_mtok 3.75M}
                                 {:principal person}))
        seat! (fn [nm scope]
                (inv/create! eng :seat
                             {:name nm
                              :charter "Reopen a chore that was finished too soon."
                              :scope scope
                              :standing_ttl_seconds 604800
                              :cadence_seconds 3600
                              :budget_usd_per_week 5M
                              :sitting_budget_tokens 60000
                              :held_for [(:id model)]}
                             {:principal person}))]
    (seat! "done-chores" [{:kind "chore" :actions ["reopen"] :filter {:state "done"}}])
    (seat! "all-chores" [{:kind "chore" :actions ["reopen"]}])
    (is (= ["all-chores"] (takers eng "colton" c))
        "the chore is open, so the seat that admits done chores cannot take its door")
    (inv/invoke! eng :chore (str c) :finish {}
                 {:principal person :idempotency-key (str (random-uuid))})
    (is (= ["done-chores" "all-chores"] (takers eng "colton" c))
        "the same seat is named once the row sits inside its filter")))

(deftest the-owner-is-never-named
  (let [eng (fresh-engine)
        c (chore! eng "Sweep")]
    (grant! eng "colton" [{:kind "chore" :actions ["reopen"]}])
    (grant! eng "iris" [{:kind "chore" :actions ["reopen"]}])
    (is (= ["iris"] (takers eng "colton" c)))
    (is (= ["colton"] (takers eng "iris" c)))))

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

(deftest a-finished-quest-reads-all-done
  (testing "the goal step is done and a step nobody took is dropped"
    (let [eng (fresh-engine)
          c (chore! eng "Dishes")
          id (:id (accept! eng person c))
          self (str "/api/chores/" c)]
      (take! eng id quests/engine-actor :plan
             {:plan [{:n 1 :door "reopen" :self self :whose "person" :state "done"
                      :alternatives [{:door "drop" :self self}]}
                     {:n 2 :door "sweep" :self self :whose "seat"
                      :waiting_on "Planner" :state "waiting"}
                     {:n 3 :door "finish" :self self :whose "person"
                      :note "More steps may follow once this one is done."
                      :state "later"}]
              :plan_is_estimate true})
      (is (= [{:door "drop" :self self}]
             (:alternatives (first (:plan (data-of eng id)))))
          "the plan door takes a step's alternatives")
      (take! eng id quests/engine-actor :finish)
      (let [plan (:plan (data-of eng id))]
        (is (= [[1 "reopen" "done"] [2 "finish" "done"]]
               (mapv (juxt :n :door (comp name :state)) plan)))
        (is (some? (:ended_at (last plan))))
        (is (nil? (:note (last plan)))))))
  (testing "landing the goal door marks its own step done"
    (let [eng (vault-engine)
          {:keys [quest latch crate vault]} (vault-quest! eng)]
      (hear! eng)
      (move! eng :q_latch latch :lift person)
      (hear! eng)
      (move! eng :q_crate crate :open person)
      (hear! eng)
      (move! eng :q_vault vault :open person)
      (hear! eng)
      (let [d (data-of eng quest)]
        (is (= "finished" (name (:state (row-of eng :quest quest)))))
        (is (= ["lift" "open" "open"] (mapv :door (:plan d))))
        (is (= ["done" "done" "done"] (states d)))
        (is (some? (:ended_at (last (:plan d)))))))))

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

;; ── the quest in its owner's walk (spec-agent-demo-walks.md § 8a) ───

(defn- self-walk! [eng docs]
  (:id (:row (inv/create! eng :walk
                          {:followed "colton" :title "The quest, filmed" :docs docs}
                          {:principal person}))))

(defn- docs-in
  "The `doc` frames a walk holds, oldest first → [{:self :n :doc}]."
  [eng walk-id]
  (let [st (:storage eng)
        frdef (get (inv/resources eng) :walk_frame)]
    (->> (store/with-tx st
           (fn [tx]
             (vec (store/query-rows st tx :walk_frame {:walk (str walk-id)}
                                    {:limit 100}))))
         (map #(:data (inv/decode-row frdef %)))
         (filter #(= "doc" (name (:type %))))
         (map #(walk/keywordize-keys (:body %)))
         (sort-by :n)
         vec)))

(deftest a-quest-the-engine-moves-is-recorded-in-its-owners-walk
  (let [eng (vault-engine)
        w (self-walk! eng true)
        plain (self-walk! eng false)
        {:keys [quest latch crate vault]} (vault-quest! eng)
        self (str "/api/quests/" quest)
        latest (fn [] (:doc (peek (docs-in eng w))))
        steps (fn [doc] (mapv (comp name :state) (get-in doc [:data :plan])))]
    (hear! eng)
    (testing "the engine's first plan lands as the quest's envelope"
      (is (= ["lift" "open" "open"] (mapv :door (get-in (latest) [:data :plan]))))
      (is (= ["next" "later" "later"] (steps (latest))))
      (is (every? #(= self (:self %)) (docs-in eng w))))
    (testing "another principal's step plans again, and the walk takes that plan"
      (move! eng :q_latch latch :lift other)
      (hear! eng)
      (is (= ["done" "next" "later"] (steps (latest)))))
    (testing "the engine's finish is recorded"
      (move! eng :q_crate crate :open person)
      (hear! eng)
      (move! eng :q_vault vault :open person)
      (hear! eng)
      (is (= "finished" (some-> (:state (latest)) name))))
    (testing "an envelope byte-equal to the last is not recorded again"
      (let [held (count (docs-in eng w))]
        (consumers/drain-consumer! eng :quests-replay (quests/consumer-fn eng)
                                   {:from-origin? true})
        (is (= held (count (docs-in eng w))))))
    (testing "a walk made without docs records none"
      (is (empty? (docs-in eng plain))))))

(defn- heard-in
  "The `transition` frames a walk holds → [body]."
  [eng walk-id]
  (let [st (:storage eng)
        frdef (get (inv/resources eng) :walk_frame)]
    (->> (store/with-tx st
           (fn [tx]
             (vec (store/query-rows st tx :walk_frame {:walk (str walk-id)}
                                    {:limit 100}))))
         (map #(:data (inv/decode-row frdef %)))
         (filter #(= "transition" (name (:type %))))
         (mapv #(walk/keywordize-keys (:body %))))))

(deftest another-principals-step-is-recorded-in-the-owners-walk
  (let [eng (vault-engine)
        w (self-walk! eng true)
        {:keys [latch crate]} (vault-quest! eng)
        doors (fn [] (mapv (comp name :action) (heard-in eng w)))]
    (hear! eng)
    (is (empty? (doors)) "the engine's own plan is no notice")
    (testing "the move that planned the quest again is a transition frame"
      (move! eng :q_latch latch :lift other)
      (hear! eng)
      (is (= ["lift"] (doors))))
    (testing "the owner's own step is not: their write door records it"
      (move! eng :q_crate crate :open person)
      (hear! eng)
      (is (= ["lift"] (doors))))
    (testing "a transition heard twice is recorded one time"
      (consumers/drain-consumer! eng :quests-replay (quests/consumer-fn eng)
                                 {:from-origin? true})
      (is (= ["lift"] (doors))))))

;; ── a step already done, taken again (the access drive's led_note) ──

(def ^:private high-shelf-filed-by-room
  (g/expr {:name :q-high-shelf-filed-by-room
           :when '(or (not= (input :shelf) "high") (data :room))
           :explain "The high shelf is filed by room, and this note names no room."
           :remedies [:q_note/rename]}))

(def ^:private note
  "A row whose remedy door stays open after it is taken: `rename` moves
  no state, so anybody may take it again."
  (r/resource
   {:kind :q_note
    :plural "q_notes"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 80}]]
             [:room {:optional true} [:maybe [:string {:max 40}]]]
             [:shelf {:optional true} [:maybe [:enum "low" "high"]]]]
    :actions
    {:rename {:from #{:open} :to :open
              :input [:map
                      [:title [:string {:min 1 :max 80}]]
                      [:room {:optional true} [:maybe [:string {:max 40}]]]]
              :handler (fn [row inp _ctx]
                         (update row :data merge (select-keys inp [:title :room])))
              :safety routine}
     :shelve {:from #{:open} :to :open
              :input [:map [:shelf [:enum "low" "high"]]]
              :guards [high-shelf-filed-by-room]
              :handler (fn [row inp _ctx]
                         (assoc-in row [:data :shelf] (:shelf inp)))
              :safety routine}
     :finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A finished note is history."}}}}))

(defn- note-engine []
  (let [eng (engine/engine {:storage (memory/storage) :resources [chore note]})]
    ((engine/handler eng) {:request-method :get :uri "/api/q_notes"
                           :headers {"x-waymark-principal" "colton"}})
    eng))

(deftest another-principals-repeat-of-a-done-step-plans-again
  (let [eng (note-engine)
        w (self-walk! eng true)
        n (make! eng :q_note {:title "Unsorted mail"})
        quest (:id (:row (inv/create! eng :quest
                                      {:self (str "/api/q_notes/" n)
                                       :action "shelve"
                                       :input {:shelf "high"}}
                                      {:principal person})))
        rename! (fn [who title]
                  (inv/invoke! eng :q_note n :rename {:title title :room "hall"}
                               {:principal who :idempotency-key (str (random-uuid))}))
        plans (fn [] (count (filter #(= :plan (:action %)) (log-of eng quest))))]
    (hear! eng)
    (is (= "rename" (:door (first (:plan (data-of eng quest)))))
        (pr-str (data-of eng quest)))
    (rename! person "Hall mail")
    (hear! eng)
    (let [before (data-of eng quest)
          written (plans)
          filmed (count (docs-in eng w))]
      (is (= ["rename" "shelve"] (mapv :door (:plan before))) (pr-str before))
      (is (= ["done" "next"] (states before)))
      (rename! other "Hall post")
      (hear! eng)
      (let [after (data-of eng quest)]
        (is (= (:plan before) (:plan after)) "the fresh plan equals the one held")
        (is (= (inc written) (plans)) "and it is written all the same")
        (is (= (:invitation before) (:invitation after))
            "the step to take is the same, so its invitation stands")
        (is (= ["rename"] (mapv (comp name :action) (heard-in eng w)))
            "the owner's walk holds the other principal's move")
        (is (= (inc filmed) (count (docs-in eng w)))
            "and the quest's document after it")))))

;; ── the goal's form is the last step ────────────────────────────────

(g/defguard the-part-is-finished
  {:reads [:chore]
   :explain "Finish the part first."
   :remedies [{:door :chore/finish :id '(data :part_id)}]}
  [row _inp ctx]
  (if-some [read (:read ctx)]
    (let [part (read :chore (get-in row [:data :part_id]))]
      (if (= "done" (some-> part :state name)) (t/allow) (t/deny)))
    (t/allow)))

(g/defguard the-film-is-a-link
  {:judges [:film]
   :explain "The film is a link."}
  [_row inp _ctx]
  (if (or (nil? (:film inp)) (str/starts-with? (str (:film inp)) "https://"))
    (t/allow)
    (t/deny)))

(g/defguard the-part-is-still-open
  {:reads [:chore]
   :severity :warning
   :explain "The part is not finished."}
  [row _inp ctx]
  (if-some [read (:read ctx)]
    (let [part (read :chore (get-in row [:data :part_id]))]
      (if (= "done" (some-> part :state name)) (t/allow) (t/deny)))
    (t/allow)))

(g/defguard the-part-is-kept
  {:reads [:chore]
   :explain "The part is not finished, and no door here finishes it."}
  [row _inp ctx]
  (if-some [read (:read ctx)]
    (let [part (read :chore (get-in row [:data :part_id]))]
      (if (= "done" (some-> part :state name)) (t/allow) (t/deny)))
    (t/allow)))

(def ^:private epic
  "A row whose goal door takes a form: `complete` requires a reason,
  and takes a secret `passphrase`."
  (r/resource
   {:kind :q_epic
    :plural "q_epics"
    :states [:open :closed]
    :initial :open
    :summary "Epic · {state}"
    :schema [:map
             [:part_id {:not-a-ref "quests fixture: the-part-is-finished binds its remedy to it"}
              [:string {:max 80}]]
             [:close_reason {:optional true} [:maybe [:string {:max 480}]]]]
    :actions
    {:complete {:from #{:open} :to :closed
                :input [:map
                        [:close_reason [:string {:min 1 :max 480}]]
                        [:film {:optional true} [:maybe [:string {:max 200}]]]
                        [:passphrase {:optional true :secret true}
                         [:maybe [:string {:max 80}]]]]
                :guards [the-part-is-finished the-film-is-a-link]
                :handler (fn [row inp _ctx]
                           (assoc-in row [:data :close_reason] (:close_reason inp)))
                :safety routine}
     :drop {:from #{:open} :to :closed
            :input [:map
                    [:close_reason [:string {:min 1 :max 480}]]
                    [:film {:optional true} [:maybe [:string {:max 200}]]]]
            :guards [the-film-is-a-link]
            :handler (fn [row inp _ctx]
                       (assoc-in row [:data :close_reason] (:close_reason inp)))
            :safety {:idempotent true :reversible true :confirm true
                     :consequence "The epic is let go."}}
     :seal {:from #{:open} :to :closed
            :guards [the-part-is-finished]
            :safety {:idempotent true :reversible true :confirm true
                     :consequence "The epic is sealed."}}
     :shelve {:from #{:open} :to :closed
              :input [:map
                      [:close_reason [:string {:min 1 :max 480}]]
                      [:film {:optional true} [:maybe [:string {:max 200}]]]]
              :guards [the-part-is-still-open the-film-is-a-link]
              :handler (fn [row inp _ctx]
                         (assoc-in row [:data :close_reason] (:close_reason inp)))
              :safety {:idempotent true :reversible true :confirm true
                       :consequence "The epic is shelved."}}
     :park {:from #{:open} :to :closed
            :input [:map
                    [:close_reason [:string {:min 1 :max 480}]]
                    [:film {:optional true} [:maybe [:string {:max 200}]]]]
            :guards [the-part-is-still-open the-film-is-a-link]
            :handler (fn [row inp _ctx]
                       (assoc-in row [:data :close_reason] (:close_reason inp)))
            :safety routine}
     :retire {:from #{:open} :to :closed
              :input [:map
                      [:close_reason [:string {:min 1 :max 480}]]
                      [:film {:optional true} [:maybe [:string {:max 200}]]]]
              :guards [the-part-is-finished the-film-is-a-link]
              :handler (fn [row inp _ctx]
                         (assoc-in row [:data :close_reason] (:close_reason inp)))
              :safety {:idempotent true :reversible true :confirm true
                       :consequence "The epic is retired."}}
     :scrap {:from #{:open} :to :closed
             :input [:map
                     [:close_reason [:string {:min 1 :max 480}]]
                     [:film {:optional true} [:maybe [:string {:max 200}]]]]
             :guards [the-part-is-kept the-film-is-a-link]
             :handler (fn [row inp _ctx]
                        (assoc-in row [:data :close_reason] (:close_reason inp)))
             :safety {:idempotent true :reversible true :confirm true
                      :consequence "The epic is scrapped."}}
     :reopen {:from #{:closed} :to :open :safety routine}}}))

(defn- epic-engine [& [opts]]
  (let [eng (engine/engine (merge {:storage (memory/storage) :resources [chore epic]}
                                  opts))]
    ((engine/handler eng) {:request-method :get :uri "/api/q_epics"
                           :headers {"x-waymark-principal" "colton"}})
    eng))

(defn- epic-quest!
  "An open epic over an unfinished part, and the quest to complete it
  with `input`, or with none."
  [eng input]
  (let [part (str (chore! eng "Write the guide"))
        e (make! eng :q_epic {:part_id part})
        self (str "/api/q_epics/" e)]
    {:part part :epic e :self self
     :quest (:id (:row (inv/create! eng :quest
                                    (cond-> {:self self :action "complete"}
                                      input (assoc :input input))
                                    {:principal person})))}))

(deftest a-goal-with-no-input-is-the-last-step-and-names-its-needs
  (let [eng (epic-engine)
        {:keys [quest part epic self]} (epic-quest! eng nil)
        _ (hear! eng)
        d (data-of eng quest)]
    (is (= ["finish" "complete"] (mapv :door (:plan d))) (pr-str d))
    (is (= [(str "/api/chores/" part) self] (mapv :self (:plan d))))
    (is (= ["person" "person"] (mapv (comp name :whose) (:plan d))))
    (is (= ["next" "later"] (states d)))
    (is (empty? (:needs (first (:plan d)))))
    (is (= ["close_reason"] (:needs (last (:plan d)))))
    (move! eng :chore part :finish person)
    (hear! eng)
    (let [d (data-of eng quest)
          goal (last (:plan d))]
      (is (= ["done" "next"] (states d)) (pr-str d))
      (is (= ["close_reason"] (:needs goal)))
      (is (str/includes? (str (:note goal)) "the-film-is-a-link")
          "a guard that reads a field not given yet is named on the goal step")
      (is (= ["close_reason"] (get-in (invitation-of eng quest) [:data :fields]))
          "the goal's form is what the owner is handed")
      (inv/invoke! eng :q_epic epic :complete {:close_reason "Merged."}
                   {:principal person :idempotency-key (str (random-uuid))})
      (hear! eng)
      (is (= "finished" (name (:state (row-of eng :quest quest))))))))

(deftest a-goal-behind-an-unfinished-part-names-its-awaiting-guard-in-the-first-plan
  (let [eng (epic-engine)
        {:keys [quest]} (epic-quest! eng nil)
        _ (hear! eng)
        d (data-of eng quest)
        goal (last (:plan d))]
    (is (= ["finish" "complete"] (mapv :door (:plan d))) (pr-str d))
    (is (= ["close_reason"] (:needs goal)))
    (is (str/includes? (str (:note goal)) "the-film-is-a-link")
        "the refused rehearsal already names the guard that waits on the form")))

(deftest a-confirm-goal-with-no-input-names-its-needs
  (let [eng (epic-engine)
        part (str (chore! eng "Write the guide"))
        e (make! eng :q_epic {:part_id part})
        quest (:id (:row (inv/create! eng :quest
                                      {:self (str "/api/q_epics/" e) :action "drop"}
                                      {:principal person})))
        _ (hear! eng)
        d (data-of eng quest)]
    (is (= ["drop"] (mapv :door (:plan d))) (pr-str d))
    (is (= ["confirm"] (mapv (comp name :whose) (:plan d))))
    (is (= ["close_reason"] (:needs (first (:plan d)))))))

(deftest a-confirm-goal-with-no-input-names-the-guard-that-awaits-its-form
  (let [eng (epic-engine)
        part (str (chore! eng "Write the guide"))
        e (make! eng :q_epic {:part_id part})
        quest (:id (:row (inv/create! eng :quest
                                      {:self (str "/api/q_epics/" e) :action "drop"}
                                      {:principal person})))
        _ (hear! eng)
        d (data-of eng quest)
        step (first (:plan d))]
    (is (= ["confirm"] (mapv (comp name :whose) (:plan d))) (pr-str d))
    (is (= ["close_reason"] (:needs step)))
    (is (= "The epic is let go. Judged when you fill the form: the-film-is-a-link."
           (:note step))
        "the consequence comes first, and the waiting guard after it")))

(deftest a-confirm-goal-behind-a-row-guard-plans-its-remedy-first
  (let [eng (epic-engine)
        part (str (chore! eng "Write the guide"))
        e (make! eng :q_epic {:part_id part})
        self (str "/api/q_epics/" e)
        quest (:id (:row (inv/create! eng :quest
                                      {:self self :action "retire"}
                                      {:principal person})))
        _ (hear! eng)
        d (data-of eng quest)
        goal (last (:plan d))]
    (is (= ["finish" "retire"] (mapv :door (:plan d))) (pr-str d))
    (is (= [(str "/api/chores/" part) self] (mapv :self (:plan d))))
    (is (= ["person" "confirm"] (mapv (comp name :whose) (:plan d))))
    (is (= ["next" "later"] (states d)))
    (is (empty? (:needs (first (:plan d)))))
    (is (= ["close_reason"] (:needs goal)))
    (is (= "The epic is retired. Judged when you fill the form: the-film-is-a-link."
           (:note goal))
        "the refused rehearsal keeps the consequence and the waiting guard")))

(deftest a-confirm-goal-refused-with-no-remedy-names-the-refusal
  (let [eng (epic-engine)
        part (str (chore! eng "Write the guide"))
        e (make! eng :q_epic {:part_id part})
        quest (:id (:row (inv/create! eng :quest
                                      {:self (str "/api/q_epics/" e) :action "scrap"}
                                      {:principal person})))
        _ (hear! eng)
        d (data-of eng quest)
        step (first (:plan d))]
    (is (= ["scrap"] (mapv :door (:plan d))) (pr-str d))
    (is (not= "confirm" (some-> (:whose step) name))
        "a door that refuses is not one to confirm")
    (is (= ["close_reason"] (:needs step)))
    (is (str/includes? (str (:note step)) "no door here finishes it")
        "the step names the refusal")))

(deftest a-confirm-goal-whose-partial-rehearsal-warns-names-the-warning
  (let [eng (epic-engine)
        part (str (chore! eng "Write the guide"))
        e (make! eng :q_epic {:part_id part})
        quest (:id (:row (inv/create! eng :quest
                                      {:self (str "/api/q_epics/" e) :action "shelve"}
                                      {:principal person})))
        _ (hear! eng)
        d (data-of eng quest)
        step (first (:plan d))]
    (is (= ["shelve"] (mapv :door (:plan d))) (pr-str d))
    (is (= ["confirm"] (mapv (comp name :whose) (:plan d))))
    (is (= ["close_reason"] (:needs step)))
    (is (= (str "The epic is shelved. You also accept a warning: The part is not finished."
                " Judged when you fill the form: the-film-is-a-link.")
           (:note step))
        "the consequence, then the warning to accept, then the waiting guard")))

(deftest a-goal-that-is-no-confirm-door-whose-rehearsal-warns-says-a-warning-is-accepted
  (doseq [[input needs note]
          [[nil ["close_reason"]
            (str "You accept a warning: The part is not finished."
                 " Judged when you fill the form: the-film-is-a-link.")]
           [{:close_reason "Parked."} nil
            "You accept a warning: The part is not finished."]]]
    (testing (if input "the full rehearsal" "the partial rehearsal")
      (let [eng (epic-engine)
            part (str (chore! eng "Write the guide"))
            e (make! eng :q_epic {:part_id part})
            quest (:id (:row (inv/create! eng :quest
                                          (cond-> {:self (str "/api/q_epics/" e)
                                                   :action "park"}
                                            input (assoc :input input))
                                          {:principal person})))
            _ (hear! eng)
            d (data-of eng quest)
            step (first (:plan d))]
        (is (= ["park"] (mapv :door (:plan d))) (pr-str d))
        (is (= ["confirm"] (mapv (comp name :whose) (:plan d))))
        (is (= needs (not-empty (:needs step))))
        (is (= note (:note step))
            "the guard's own reason, then the guard that waits on the form")))))

(deftest the-mapping-names-a-confirm-entrys-warning-after-its-consequence
  (let [plan (quests/answer->plan
              {:writes []
               :blocked-on [{:door "q_epic.shelve" :row "/api/q_epics/1" :or []
                             :needs [:close_reason]
                             :confirm true
                             :consequence "The epic is shelved."
                             :warnings [{:name "the-part-is-still-open"
                                         :reason "The part is not finished."}]
                             :reason "safety.confirm is true"}]
               :stack [{:door "q_epic.shelve" :row "/api/q_epics/1"}]}
              nil)]
    (is (= [["shelve" "confirm" "next"
             "The epic is shelved. You also accept a warning: The part is not finished."]]
           (mapv (juxt :door :whose :state :note) (:plan plan))))))

(deftest a-goal-whose-input-was-given-keeps-it-and-needs-nothing
  (let [eng (epic-engine)
        {:keys [quest]} (epic-quest! eng {:close_reason "Merged."})
        _ (hear! eng)
        d (data-of eng quest)]
    (is (= ["finish" "complete"] (mapv :door (:plan d))) (pr-str d))
    (is (every? (comp empty? :needs) (:plan d)))
    (is (= "Merged." (:close_reason (walk/keywordize-keys (:input d)))))))

(deftest the-goals-invitation-carries-the-stored-input-as-given
  (let [eng (epic-engine)
        film "https://example.org/film"
        {:keys [quest part]} (epic-quest! eng {:film film})
        _ (hear! eng)]
    (is (nil? (get-in (invitation-of eng quest) [:data :given]))
        "a step that is not the goal's carries no stored input")
    (move! eng :chore part :finish person)
    (hear! eng)
    (let [data (:data (invitation-of eng quest))]
      (is (= ["close_reason"] (:fields data)) (pr-str data))
      (is (= {:film film} (walk/keywordize-keys (:given data)))
          "the goal's form opens with what the quest stored")
      (is (nil? (:suggest data))
          "the owner's own values are not marked as a suggestion"))))

(deftest the-goals-invitation-leaves-out-a-stored-secret-argument
  (let [eng (epic-engine)
        film "https://example.org/film"
        passphrase "open-sesame-4471"
        {:keys [quest part]} (epic-quest! eng {:film film :passphrase passphrase})
        _ (hear! eng)]
    (is (= passphrase (:passphrase (walk/keywordize-keys (:input (data-of eng quest)))))
        "the quest stored the secret argument")
    (move! eng :chore part :finish person)
    (hear! eng)
    (let [data (:data (invitation-of eng quest))]
      (is (= "complete" (some-> (:action data) name)) (pr-str data))
      (is (= {:film film} (walk/keywordize-keys (:given data)))
          "the other stored value still pre-fills, and the secret one is left out")
      (is (not (str/includes? (pr-str data) passphrase))
          "the secret value is nowhere in the invitation"))))

(deftest a-stored-key-the-invitation-may-not-show-is-left-out-alone
  (is (= {:film "https://example.org/film"}
         (invitations/showable epic "complete"
                               {:film "https://example.org/film"
                                :colour "red"}))
      "the key the door does not take goes, and the other still pre-fills")
  (is (= {} (invitations/showable nil "complete" {:film "x"}))
      "a row of no served kind shows nothing"))

(deftest the-mapping-ends-on-a-goal-whose-form-is-not-filled
  (let [plan (quests/answer->plan
              {:blocked-on [{:door "ticket.complete" :row "/api/tickets/c1"
                             :needs [:close_reason] :or []}]
               :stack [{:door "ticket.complete" :row "/api/tickets/e1"
                        :needs [:close_reason] :awaiting ["the-film-is-a-link"]}]
               :writes []}
              nil)]
    (is (= [["complete" "/api/tickets/c1" "choice" "next"]
            ["complete" "/api/tickets/e1" "person" "later"]]
           (mapv (juxt :door :self :whose :state) (:plan plan))))
    (is (= [["close_reason"] ["close_reason"]] (mapv :needs (:plan plan))))
    (is (= "Judged when you fill the form: the-film-is-a-link."
           (:note (last (:plan plan)))))))

;; ── a goal its row draws shut ───────────────────────────────────────

(deftest the-mapping-ends-on-a-shut-goal-with-its-declared-needs
  (let [answer {:blocked-on [{:door "ticket.complete" :row "/api/tickets/c1"
                              :needs [:close_reason] :or []}]
                ;; the frame of a door not afforded: no form was read
                :stack [{:door "ticket.complete" :row "/api/tickets/e1"}]
                :writes []}
        steps (fn [needs]
                (:plan (quests/answer->plan
                        answer nil
                        (when needs
                          {:door "complete" :self "/api/tickets/e1" :needs needs}))))]
    (testing "the goal is the last step, with the declaration's needs"
      (let [plan (steps ["close_reason"])]
        (is (= [["complete" "/api/tickets/c1" "choice" "next"]
                ["complete" "/api/tickets/e1" "person" "later"]]
               (mapv (juxt :door :self :whose :state) plan)))
        (is (= [["close_reason"] ["close_reason"]] (mapv :needs plan)))
        (is (nil? (:note (last plan))) "no guard was asked about the form")))
    (testing "a declaration that requires nothing still ends on the goal"
      (let [plan (steps [])]
        (is (= ["/api/tickets/c1" "/api/tickets/e1"] (mapv :self plan)))
        (is (empty? (:needs (last plan))))))
    (testing "with no declaration the plan is the rehearsal's alone"
      (is (= ["/api/tickets/c1"] (mapv :self (:plan (quests/answer->plan answer nil))))))
    (testing "a declared confirm door is the owner's to confirm"
      (let [goal (last (:plan (quests/answer->plan
                               answer nil
                               {:door "complete" :self "/api/tickets/e1" :needs []
                                :confirm true :consequence "The ticket is let go."})))]
        (is (= ["complete" "/api/tickets/e1" "confirm" "The ticket is let go."]
               ((juxt :door :self :whose :note) goal)))))))

(deftest a-goal-the-row-draws-shut-is-the-last-step-of-the-first-plan
  ;; :probe-reads lets the render judge the-part-is-finished, so the
  ;; epic's row does not afford `complete` and the rehearsal reads no form
  (let [eng (epic-engine {:probe-reads true})
        {:keys [quest part self]} (epic-quest! eng nil)
        _ (hear! eng)
        d (data-of eng quest)]
    (is (= ["finish" "complete"] (mapv :door (:plan d))) (pr-str d))
    (is (= [(str "/api/chores/" part) self] (mapv :self (:plan d))))
    (is (= ["next" "later"] (states d)))
    (is (= ["close_reason"] (:needs (last (:plan d))))
        "the goal's needs are read from the kind's declaration")
    (move! eng :chore part :finish person)
    (hear! eng)
    (let [d (data-of eng quest)]
      (is (= ["finish" "complete"] (mapv :door (:plan d))) (pr-str d))
      (is (= ["done" "next"] (states d)))
      (is (= ["close_reason"] (:needs (last (:plan d))))))))

(deftest a-confirm-goal-the-row-draws-shut-is-a-confirm-step-of-the-first-plan
  (let [eng (epic-engine {:probe-reads true})
        part (str (chore! eng "Write the guide"))
        e (make! eng :q_epic {:part_id part})
        self (str "/api/q_epics/" e)
        quest (:id (:row (inv/create! eng :quest
                                      {:self self :action "seal"}
                                      {:principal person})))
        _ (hear! eng)
        d (data-of eng quest)
        goal (last (:plan d))]
    (is (= ["finish" "seal"] (mapv :door (:plan d))) (pr-str d))
    (is (= [(str "/api/chores/" part) self] (mapv :self (:plan d))))
    (is (= "confirm" (name (:whose goal)))
        "the declaration says the shut door is a confirm door")
    (is (= "The epic is sealed." (:note goal))
        "the consequence is read from the kind's declaration")))

;; ── the preview: the create door, rehearsed ─────────────────────────

(defn- held
  "How many quests and invitations the store holds, and how long the
  log of each of `rows` ([kind id]) is."
  [eng rows]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        {:quests (count (store/query-rows st tx :quest {} {:limit 100}))
         :invitations (count (store/query-rows st tx :invitation {} {:limit 100}))
         :log (mapv (fn [[k id]]
                      (count (store/transitions st tx {:kind k :resource-id id} {})))
                    rows)}))))

(deftest the-preview-of-a-shut-door-answers-the-plan-and-writes-nothing
  (let [eng (epic-engine {:probe-reads true})
        part (str (chore! eng "Write the guide"))
        e (make! eng :q_epic {:part_id part})
        self (str "/api/q_epics/" e)
        rows [[:chore part] [:q_epic e]]
        before (held eng rows)
        {:keys [valid? preview]} (inv/create! eng :quest
                                              {:self self :action "complete"}
                                              {:principal person :dry-run true})]
    (is (true? valid?))
    (is (= ["finish" "complete"] (mapv :door (:plan preview))) (pr-str preview))
    (is (= [(str "/api/chores/" part) self] (mapv :self (:plan preview)))
        "the goal is the last step")
    (is (= ["close_reason"] (:needs (last (:plan preview))))
        "with the fields its form will ask for")
    (is (true? (:plan_is_estimate preview)))
    (is (some? (not-empty (:goal preview))) "the goal is said in words")
    (is (some? (not-empty (:shut_reason preview)))
        "and why its door is shut now")
    (is (= {:quests 0 :invitations 0} (select-keys before [:quests :invitations])))
    (is (= before (held eng rows))
        "no quest, no invitation and no transition")
    (hear! eng)
    (is (= before (held eng rows)) "and the planner hears of nothing")))

(def ^:private ledger
  "A row whose goal door is fenced: `lock` asks for the version its
  caller read."
  (r/resource
   {:kind :q_ledger
    :plural "q_ledgers"
    :states [:open :closed]
    :initial :open
    :summary "Ledger · {state}"
    :schema [:map
             [:part_id {:not-a-ref "quests fixture: the-part-is-finished binds its remedy to it"}
              [:string {:max 80}]]]
    :actions
    {:lock {:from #{:open} :to :closed
            :guards [the-part-is-finished]
            :safety {:idempotent true :reversible true :confirm false :fence true}}
     :reopen {:from #{:closed} :to :open :safety routine}}}))

(deftest the-preview-of-a-fenced-shut-door-answers-its-guard-and-no-etag-line
  (let [eng (epic-engine {:probe-reads true :resources [chore epic ledger]})
        part (str (chore! eng "Write the guide"))
        v (make! eng :q_ledger {:part_id part})
        {:keys [preview]} (inv/create! eng :quest
                                       {:self (str "/api/q_ledgers/" v) :action "lock"}
                                       {:principal person :dry-run true})
        shut (str (:shut_reason preview))]
    (is (str/includes? shut "Finish the part first.") (pr-str preview))
    (is (not (str/includes? shut "changed since you read it"))
        "the fence's version refusal is no reason the door is shut")
    (is (not (str/includes? shut "etag")))))

(deftest a-preview-step-names-its-row-and-its-needs-in-words
  (let [eng (epic-engine {:probe-reads true})
        part (str (chore! eng "Write the guide"))
        e (make! eng :q_epic {:part_id part})
        {:keys [preview]} (inv/create! eng :quest
                                       {:self (str "/api/q_epics/" e) :action "complete"}
                                       {:principal person :dry-run true})
        [step goal] (:plan preview)]
    (is (= "Write the guide" (:row_label step)) (pr-str preview))
    (is (= "Finish" (:door_label step)) "the door's declared label")
    (is (nil? (:needs_labels step)) "a step that asks for nothing names no field")
    (is (= "Epic · Open" (:row_label goal))
        "a row with no name or title is its summary")
    (is (= "Complete" (:door_label goal)) "a door with no label is its name in words")
    (is (= ["close_reason"] (:needs goal)) "the field names stay")
    (is (= ["Close reason"] (:needs_labels goal))
        "beside the labels of the fields the form will ask for")))

(deftest a-planned-step-stores-its-door-and-its-needs-in-words
  (let [eng (epic-engine)
        {:keys [quest]} (epic-quest! eng nil)
        _ (hear! eng)
        [step goal :as plan] (:plan (data-of eng quest))]
    (is (= ["Finish" "Complete"] (mapv :door_label plan)) (pr-str plan))
    (is (nil? (:needs_labels step)) "a step that asks for nothing names no field")
    (is (= ["close_reason"] (:needs goal)) "the field names stay")
    (is (= ["Close reason"] (:needs_labels goal))
        "beside the labels of the fields the form will ask for")
    (is (not-any? :row_label plan) "the stored plan holds no row's label")))

(deftest a-nested-need-is-named-by-its-declared-label
  (let [evidence [:map
                  [:film_url {:x-display {:label "The film"}} [:string {:max 300}]]
                  [:poster_url [:string {:max 300}]]]
        rdef {:actions
              {:restate
               {:input [:map
                        [:title {:x-display {:label "Title"}} [:string {:max 80}]]
                        [:showcase {:x-display {:label "Showcase"}}
                         [:maybe
                          [:map
                           [:evidence {:x-display {:label "Evidence"}} evidence]]]]
                        [:sequel {:x-display {:label "Sequel"}}
                         [:or [:string {:max 80}] evidence]]]}}}
        labels #(:needs_labels (#'quests/labels-of rdef {:door "restate" :needs %}))]
    (is (= ["The film"] (labels ["showcase.evidence.film_url"]))
        "the label of the field at the end of the path, through a :maybe")
    (is (= ["Title" "The film"] (labels ["title" "sequel.film_url"]))
        "a top-level need is as it was, and an :or is walked by its map arm")
    (is (= ["Poster url"] (labels ["showcase.evidence.poster_url"]))
        "a nested field that declares no label is its own name in words")
    (is (= ["Evidence: The film" "Sequel: The film"]
           (labels ["showcase.evidence.film_url" "sequel.film_url"]))
        "two needs that end in the same label are each said after their parent's")
    (is (= ["No such"] (labels ["showcase.no_such"]))
        "a path the schema does not name is its last step in words")))

(deftest a-rename-of-a-steps-row-leaves-the-quests-plan-as-it-was
  (let [eng (note-engine)
        n (make! eng :q_note {:title "Unsorted mail"})
        quest (:id (:row (inv/create! eng :quest
                                      {:self (str "/api/q_notes/" n)
                                       :action "shelve"
                                       :input {:shelf "high"}}
                                      {:principal person})))
        rename! (fn [who title]
                  (inv/invoke! eng :q_note n :rename {:title title :room "hall"}
                               {:principal who :idempotency-key (str (random-uuid))}))]
    (hear! eng)
    (rename! person "Hall mail")
    (hear! eng)
    (let [before (data-of eng quest)]
      (is (every? (comp string? :door_label) (:plan before)) (pr-str before))
      (rename! other "Hall post")
      (hear! eng)
      (let [after (data-of eng quest)]
        (is (= (:plan before) (:plan after)) "the plan is the one held")
        (is (= (:title before) (:title after)) "and so is the quest's title")
        (is (not-any? :row_label (:plan after)) "no step holds the row's label")
        (is (not (str/includes? (pr-str after) "Hall post"))
            "the row's new name is nowhere in the quest")))))

(deftest the-preview-of-a-goal-create-refuses-answers-that-refusal
  (let [eng (epic-engine)
        c (chore! eng "Dishes")
        rehearse #(inv/create! eng :quest {:self % :action "finish"}
                               {:principal person :dry-run true})
        unseen (refusal #(rehearse "/api/chores/no-such-row"))
        open (refusal #(rehearse (str "/api/chores/" c)))
        _ (dotimes [_ quests/active-cap] (accept! eng person c))
        full (refusal #(rehearse (str "/api/chores/" c)))]
    (is (str/includes? (str unseen) "does not see that row"))
    (is (nil? open) "a goal create would take is no refusal")
    (is (str/includes? (str full) "at most 20")
        "the cap is said in the preview, not after Accept")))
