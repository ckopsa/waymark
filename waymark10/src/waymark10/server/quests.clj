(ns waymark10.server.quests
  "The quest (docs/spec-quests.md): a goal a person accepted, as one door
  on one row, and the steps that lead to it.

  THE PERSON NAMES THE GOAL AND NOTHING ELSE. Create takes the goal
  row's path, the goal door and that door's input. The engine stamps
  `owner` from the principal and `grant` from the grant the create was
  made under, never from the body.

  THE ENGINE ALONE WRITES THE PLAN. A quest is born with an empty plan
  and no `planned_at`. `plan` and `finish` are the engine's doors; no
  hand at the wire takes them.

  ONE CONSUMER PLANS IT. A durable log consumer (`:quests`) hears a
  quest's `create` and `replan`, rehearses the goal as the owner under
  the owner's grant (`mcp/rehearse`, GRAIL's dry run), maps the answer
  to steps (`answer->plan`, a pure function) and walks `plan`. It reads
  before it writes and its key is made from what it heard, so a
  replayed transition changes nothing.

  ONE PINNED QUEST PER OWNER. `pin` unpins the owner's other quests
  through their own `unpin` door, in the same transaction. Only an
  active quest is pinned: `pause`, `abandon` and `finish` unpin."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.invitations :as invitations]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store :as store]
            [waymark10.summary :as summary]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def kind :quest)

(def engine-actor
  "The system actor that plans and finishes a quest."
  (t/principal {:id "waymark10-quests" :type :system
                :display "Quests"}))

(def active-cap
  "How many active quests one owner holds at a time."
  20)

;; ── guards ──────────────────────────────────────────────────────────

(defn- is? [row field ctx]
  (= (str (get-in row [:data field])) (str (:id (:principal ctx)))))

(defn- engine? [ctx]
  (= :system (:type (:principal ctx))))

(defn- active? [row]
  (= "active" (some-> (:state row) name)))

(defn- quests-of
  "The owner's quests, as the write's own transaction reads them."
  [owner ctx]
  (when-some [find (:find ctx)]
    (find kind {:owner (str owner)} {:limit 500})))

(g/defguard the-owner-sees-the-goal
  {:judges [:self :action]
   :reads [:principal :grant :storage]
   :vars [:problem]
   :open "Your own grant is the law here; no field of this door widens it. Name a row you can see, as /api/<plural>/<id>, and a door that kind declares."
   :explain "A quest's goal is a door on a row its owner can see: {problem}"}
  [_row inp ctx]
  (if (nil? (:rdef-of ctx))
    ;; no registry in scope (a render probe): the write path carries it
    (t/allow)
    (if-some [problem (invitations/sight-problem (select-keys inp [:self :action]) ctx)]
      (t/deny {:vars {:problem problem}})
      (t/allow))))

(g/defguard active-quests-are-few
  {:reads [:principal :storage]
   :vars [:cap]
   :open "The cap counts your active quests. Finish, pause or abandon one, then accept this one."
   :explain "One owner holds at most {cap} active quests."}
  [_row _inp ctx]
  (if (< (count (filter active? (quests-of (:id (:principal ctx)) ctx)))
         (long active-cap))
    (t/allow)
    (t/deny {:vars {:cap active-cap}})))

(g/defguard the-owner-moves-it
  {:reads [:principal]
   :open "The wall is about who: the owner moves its own quest, and no field of this door makes anyone else the owner."
   :explain "Only the owner of this quest pins, unpins, pauses, resumes, abandons or replans it."}
  [row _inp ctx]
  (if (is? row :owner ctx) (t/allow) (t/deny)))

(g/defguard the-engine-plans-it
  {:reads [:principal]
   :hide true
   :explain "The engine writes a quest's plan and finishes it when the goal is reached; no hand at the wire does."}
  [_row _inp ctx]
  (if (engine? ctx) (t/allow) (t/deny)))

;; ── handlers ────────────────────────────────────────────────────────

(defn- now-of ^Instant [ctx]
  (or (:now ctx) (Instant/now)))

(defn- goal-label
  "The goal row's label: its kind's label template, else its name or
  title, else its summary; nil when the row cannot be read."
  [rdef row]
  (when row
    (or (some-> (:label-template rdef) (summary/render row) str not-empty)
        (some-> (or (get-in row [:data :name]) (get-in row [:data :title]))
                str not-empty)
        (some-> (:summary rdef) (summary/render row) str not-empty))))

(defn- default-title
  "The door's display label and the row's label, in one line."
  [data ctx]
  (let [{:keys [plural id]} (invitations/parse-self (:self data))
        rdef (when plural (some-> (:rdef-of ctx) (apply [plural])))
        action (keyword (str/trim (str (:action data))))
        door (or (get-in rdef [:actions action :display :label]) (name action))
        row (when rdef (some-> (:read ctx) (apply [(:kind rdef) id])))
        line (str/join ": " (remove nil? [door (goal-label rdef row)]))]
    (subs line 0 (min 120 (count line)))))

(defn- born
  "The birth stamps: the owner is the principal that created the row and
  the grant is the one it was created under, never the body. The plan
  is empty until the engine writes one."
  [row ctx]
  (let [title (or (some-> (get-in row [:data :title]) str not-empty)
                  (default-title (:data row) ctx))]
    (update row :data assoc
            :owner (str (get-in ctx [:principal :id]))
            :grant (some-> (:grant ctx) :id str)
            :title title
            :pinned false
            :plan []
            :planned_at nil)))

(defn- unpinned [row]
  (assoc-in row [:data :pinned] false))

(defhandler pin-it [row _inp ctx]
  ;; the owner's other pinned quests leave through their own door, in
  ;; this write's transaction, so each one's history says who unpinned it
  (when-some [invoke (:invoke ctx)]
    (doseq [other (quests-of (get-in row [:data :owner]) ctx)
            :when (and (not= (str (:id other)) (str (:id row)))
                       (true? (get-in other [:data :pinned]))
                       (active? other))]
      (invoke kind (str (:id other)) :unpin {})))
  (assoc-in row [:data :pinned] true))

(defhandler unpin-it [row _inp _ctx]
  (unpinned row))

(defhandler ask-replan [row _inp ctx]
  (assoc-in row [:data :replan_requested_at] (now-of ctx)))

(defhandler write-plan [row inp ctx]
  (update row :data assoc
          :plan (vec (:plan inp))
          :planned_at (or (:planned_at inp) (now-of ctx))
          :blocked_reason (:blocked_reason inp)
          :plan_is_estimate (boolean (:plan_is_estimate inp))
          :waiting_on (:waiting_on inp)))

;; ── the kind ────────────────────────────────────────────────────────

(def ^:private step-schema
  "One step of a plan (docs/spec-quests.md, the step vocabulary)."
  [:map
   [:n {:x-display {:label "Step" :help "The number of the step, counted from one."}}
    [:int {:min 1}]]
   [:door {:x-display {:raw true
                       :label "The door"
                       :help "The action this step takes."}}
    [:string {:min 1 :max 60}]]
   [:self {:x-display {:raw true
                       :label "The row"
                       :help "The row the step acts on, as its path: /api/<plural>/<id>."}}
    [:string {:min 1 :max 300}]]
   [:whose {:x-display {:label "Whose step"
                        :help "`person`: the owner takes it. `seat`: an agent's seat takes it. `held`: it waits for a person's approval. `confirm`: the owner must acknowledge a consequence. `choice`: the owner must choose an input."}}
    [:enum "person" "seat" "held" "confirm" "choice"]]
   [:note {:optional true
           :x-display {:widget "prose"
                       :label "Note"
                       :help "One sentence the owner reads on this step."}}
    [:maybe [:string {:max 240}]]]
   [:needs {:optional true
            :x-display {:label "Needs"
                        :help "The arguments of the door that are still to be given."}}
    [:maybe [:vector {:max 16} [:string {:min 1 :max 60}]]]]
   [:waiting_on {:optional true
                 :x-display {:label "Waiting on"
                             :help "Who the step waits on, by name, when it is not the owner's."}}
    [:maybe [:string {:max 128}]]]
   [:state {:x-display {:label "State"
                        :help "`done`: the step was taken. `next`: it is the one to take now. `waiting`: it waits on someone else. `later`: a step before it is not done."}}
    [:enum "done" "next" "waiting" "later"]]])

(def ^:private goal-fields
  "What the person writes: the create model."
  [[:self {:x-display {:raw true
                       :label "The goal row"
                       :help "The row the goal door is on, as its path: /api/<plural>/<id>."}}
    [:string {:min 1 :max 300}]]
   [:action {:x-display {:raw true
                         :label "The goal door"
                         :help "The action on that row that the quest is finished by."}}
    [:string {:min 1 :max 60}]]
   [:input {:optional true
            :x-display {:label "The door's input"
                        :help "What the goal door is given when it is taken."
                        :spelled-by-hand "Its keys are the arguments of the goal door, which differ per door, so no fixed sub-form can offer them."}}
    [:maybe [:map-of :keyword :any]]]
   [:title {:optional true
            :examples ["Complete: Friday demo"]
            :x-display {:label "Title"
                        :help "One line that names the goal. Left empty, it is the door's label and the row's label."}}
    [:maybe [:string {:min 1 :max 120}]]]])

(def ^:private plan-fields
  "What the engine's `plan` door writes."
  [[:plan {:optional true
           :x-display {:label "Plan"
                       :help "The steps known so far, in order, written by the engine. Empty until the first plan lands."}}
    [:maybe [:vector {:max 100} step-schema]]]
   [:planned_at {:optional true
                 :x-display {:label "Planned"
                             :help "When the engine last wrote the plan. Empty while the first plan is being made."}}
    [:maybe :waymark/instant]]
   [:blocked_reason {:optional true
                     :x-display {:label "Why it is blocked"
                                 :help "One sentence when no step can be taken now. Empty otherwise."}}
    [:maybe [:string {:max 480}]]]
   [:plan_is_estimate {:optional true
                       :x-display {:label "Plan is an estimate"
                                   :help "True when later steps may appear after the known ones are taken."}}
    [:maybe :boolean]]
   [:waiting_on {:optional true
                 :x-display {:label "Waiting on"
                             :help "Who the head step waits on, by name. Empty when it is the owner's."}}
    [:maybe [:string {:max 128}]]]])

(defresource quest
  {:kind :quest
   :plural "quests"
   :nav :secondary
   :states [:active :paused :finished :abandoned]
   :initial :active
   :terminal #{:finished :abandoned}
   :summary "{data.title} · {state}"
   :label-template "{data.title}"
   :schema
   (-> [:map
        [:owner {:x-ref {:principal true}
                 :x-display {:raw true
                             :label "Owner"
                             :help "The principal that accepted the quest, stamped by the engine at birth."}}
         [:string {:min 1 :max 128}]]
        [:grant {:optional true
                 :not-a-ref "It says which grant the quest was accepted under, and it must still say so after that grant has expired or been revoked."
                 :x-display {:raw true
                             :label "Accepted under"
                             :help "The id of the grant the quest was created under, stamped by the engine. Empty for a person acting as themselves."}}
         [:maybe [:string {:max 128}]]]]
       (into goal-fields)
       (into plan-fields)
       (into
        [[:pinned {:optional true
                   :x-display {:label "Pinned"
                               :help "True on the one quest its owner keeps in view."}}
          [:maybe :boolean]]
         [:replan_requested_at {:optional true
                                :x-display {:label "Replan asked"
                                            :help "When the owner last asked the engine to plan again."}}
          [:maybe :waymark/instant]]]))
   ;; everything but the goal is the engine's to write
   :create-schema (into [:map] goal-fields)
   :filterable {:state #{:eq :in}
                :owner #{:eq}
                :pinned #{:eq}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :default-filters {:state "active,paused"}
   :create-guards [the-owner-sees-the-goal active-quests-are-few]
   :on-create born
   :actions
   {:pin
    {:from #{:active} :to :active
     :guards [the-owner-moves-it]
     :handler pin-it
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Pin" :order 1
               :description "Keep this quest in view; your other quests are unpinned"}}
    :unpin
    {:from #{:active} :to :active
     :guards [the-owner-moves-it]
     :handler unpin-it
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Unpin" :order 2
               :description "Take this quest out of view"}}
    :pause
    {:from #{:active} :to :paused
     :guards [the-owner-moves-it]
     :handler unpin-it
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Pause" :order 3
               :description "Set the quest aside; resume picks it up again"}}
    :resume
    {:from #{:paused} :to :active
     :guards [the-owner-moves-it active-quests-are-few]
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Resume" :order 4
               :description "Take the quest up again"}}
    :replan
    {:from #{:active} :to :active
     :guards [the-owner-moves-it]
     :handler ask-replan
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The engine is asked to plan again; the plan it writes replaces this one."}
     :display {:label "Replan" :order 5
               :description "Ask the engine to find the steps again"}}
    :abandon
    {:from #{:active :paused} :to :abandoned
     :guards [the-owner-moves-it]
     :handler unpin-it
     :safety {:idempotent true :reversible false :confirm false
              :final "The goal is let go and the plan is no longer kept current; reopening would show steps nobody checked. Accepting the goal again is a new quest."}
     :display {:label "Abandon" :style :danger :order 6
               :description "Let this goal go"}}
    ;; the two moves the ENGINE writes
    :plan
    {:from #{:active} :to :active
     :input (-> [:map]
                (into (map (fn [[k props sch]]
                             [k (assoc props :x-display {:hidden true}) sch]))
                      plan-fields))
     :guards [the-engine-plans-it]
     :handler write-plan
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The plan before this one is replaced; the transition that wrote it is in the log."}
     :display {:label "Planned"}}
    :finish
    {:from #{:active} :to :finished
     :guards [the-engine-plans-it]
     :handler unpin-it
     :safety {:idempotent true :reversible false :confirm false
              :final "The goal door was taken; reopening would make the record lie. Reaching the goal again is a new quest."}
     :display {:label "Finished"}}}
   :deviations
   ["Only an active quest is pinned: pause, abandon and finish unpin, so `pin` and `unpin` are doors of the active state alone."
    "`resume` is judged by the cap as create is, so a resumed quest never makes a twenty-first active one."]})

;; ── the plan, from a rehearsal ──────────────────────────────────────

(def ^:private your-tap
  "Who a held step waits on."
  "your tap")

(def ^:private more-may-follow
  "More steps may follow once this one is done.")

(def ^:private no-row-chosen
  "The sentence `client/pursue!` blocks a remedy with when nobody named
  the row it acts on: a choice. An entry with any other sentence is a
  door the owner has no way through."
  "No row was chosen for this remedy.")

(defn- clip [s n]
  (when-some [s (some-> s str not-empty)]
    (subs s 0 (min (long n) (count s)))))

(defn- door-parts
  "A door as the rehearsal spells it, `kind.action`, as its two names."
  [door]
  (if (keyword? door)
    [(namespace door) (name door)]
    (let [d (str door)
          i (str/last-index-of d ".")]
      (if i [(subs d 0 i) (subs d (inc (long i)))] [nil d]))))

(defn- step-of
  "The door and the row of a write, a blocked entry or a stack frame, as
  a step's. A remedy nobody bound to a row is shown on the goal's."
  [{:keys [door row]} goal]
  {:door (clip (second (door-parts door)) 60)
   :self (clip (or row (:self goal)) 300)})

(defn- loop-reason
  "The sentence for a branch the rehearsal gave up on, or nil."
  [{:keys [door reason]}]
  (case (some-> reason name)
    "cycle" (str "The way to " door " leads back to " door
                 ", so no step can be taken now.")
    "depth" (str "The way to " door " is longer than the engine follows"
                 ", so no step can be taken now.")
    nil))

(defn- blocked-steps
  "One `:blocked-on` entry as its steps: none for a cycle or the depth
  bound, two for a door that is somebody else's, else one."
  [{:keys [door reason needs confirm consequence held hold warnings] :as entry}
   goal seat-lookup]
  (let [[k action] (door-parts door)
        base (step-of entry goal)]
    (cond
      (loop-reason entry) []

      confirm
      [(assoc base :whose "confirm" :note (clip (or consequence reason) 240))]

      (or held hold)
      [(assoc base :whose "held" :waiting_on your-tap)]

      ;; an advisory guard: the owner accepts its warning at the door
      (seq warnings)
      [(assoc base :whose "confirm"
              :note "This door asks you to accept a warning before it opens.")]

      (or (seq needs) (= no-row-chosen reason))
      [(cond-> (assoc base :whose "choice" :note (clip reason 240))
         (seq needs) (assoc :needs (into []
                                         (comp (map #(clip (if (keyword? %) (name %) %) 60))
                                               (remove nil?)
                                               (take 16))
                                         needs)))]

      ;; a row the owner cannot read, or a refusal that names no way
      :else
      (let [seats (seq (take 3 (when (and k seat-lookup) (seat-lookup k action))))
            who (if seats
                  (str/join ", " seats)
                  (str "someone who holds " (if (keyword? door) (str k "." action) door)))]
        [(assoc base :whose "seat" :waiting_on (clip who 128) :note (clip reason 240))
         (assoc (if (:door goal) goal base) :whose "person" :note more-may-follow)]))))

(defn- with-states
  "Number the steps and say which is taken now: a seat's or a held step
  at the head waits and nothing is next; otherwise the first step that
  is the owner's is next. The rest are later."
  [steps]
  (let [waiting? (contains? #{"seat" "held"} (:whose (first steps)))
        next-at (when-not waiting?
                  (first (keep-indexed
                          (fn [i s]
                            (when (contains? #{"person" "confirm" "choice"} (:whose s)) i))
                          steps)))]
    (into []
          (map-indexed
           (fn [i s]
             (assoc s
                    :n (inc (long i))
                    :state (cond
                             (and waiting? (zero? (long i))) "waiting"
                             (= i next-at) "next"
                             :else "later"))))
          steps)))

(defn answer->plan
  "A rehearsal's answer (`client/pursue!` with `:dry-run true`, or
  `mcp/rehearse`'s `:stopped`) as the input of the `plan` door. Pure:
  `seat-lookup` is (fn [kind action]) → the names of the active seats
  whose scope admits that door, and is asked only for a door the owner
  cannot take.

  The writes come first, in the rehearsal's order, so the goal door is
  last when it is among them; each blocked entry follows. The plan is
  always an estimate: a rehearsal cannot see the effect of a write it
  did not make."
  [answer seat-lookup]
  (let [blocked (vec (:blocked-on answer))
        writes (vec (:writes answer))
        goal (some-> (or (first (:stack answer)) (peek writes) (first blocked))
                     (step-of nil))
        steps (-> (mapv (fn [w]
                          (cond-> (assoc (step-of w goal) :whose "person")
                            (:hold w) (assoc :whose "held" :waiting_on your-tap)))
                        writes)
                  (into (mapcat #(blocked-steps % goal seat-lookup)) blocked))
        steps (with-states (into [] (comp (filter :self) (take 100)) steps))
        head (first steps)
        stopped (when-some [s (:stopped answer)]
                  (or (get-in s [:problem :detail])
                      (get-in s [:problem :title])
                      "The goal could not be read, so no step can be found."))]
    {:plan steps
     :plan_is_estimate true
     :blocked_reason (clip (or (some loop-reason blocked) stopped) 480)
     :waiting_on (when (= "waiting" (:state head)) (:waiting_on head))}))

;; ── the engine's own hand ───────────────────────────────────────────

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 quests: " parts))))

(def ^:private sweep-cap
  "The most rows one pass reads."
  500)

(defn- row-of
  "One row of kind `k`, decoded; nil when it is gone."
  [eng k id]
  (let [st (:storage eng)]
    (when-some [rdef (get (inv/resources eng) k)]
      (some->> (store/with-tx st
                 (fn [tx] (store/load-row st tx k (str id) {})))
               (inv/decode-row rdef)))))

(defn- rows-of [eng k where]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) k)]
    (mapv #(inv/decode-row rdef %)
          (store/with-tx st
            (fn [tx]
              (vec (store/query-rows st tx k where {:limit sweep-cap})))))))

(defn- seat-lookup
  "(fn [kind action]) → the names of the active seats whose scope names
  that action on that kind. Read when a plan needs it, never per write."
  [eng]
  (fn [k action]
    (when (contains? (inv/resources eng) :seat)
      (into []
            (comp (filter (fn [row]
                            (some (fn [e]
                                    (and (= (str k) (some-> (:kind e) name))
                                         (some #(= (str action) (name %)) (:actions e))))
                                  (get-in row [:data :scope]))))
                  (keep #(some-> (get-in % [:data :name]) str not-empty)))
            (rows-of eng :seat {:state :active})))))

(defn- planned-since?
  "Whether the row's plan was written after transition `t` was
  committed. Such a transition was already answered: a replay of it
  plans nothing."
  [t row]
  (let [at (invitations/instant-of (:at t))
        planned (invitations/instant-of (get-in row [:data :planned_at]))]
    (boolean (and at planned (.isBefore at planned)))))

(defn- rehearsed
  "The plan for one quest: the goal rehearsed as its owner under its
  grant. A rehearsal that cannot be made (the owner is no member now,
  the grant confers nothing) is a plan of no steps that says why."
  [eng row]
  (let [{:keys [owner grant self action input]} (:data row)]
    (try
      (answer->plan (mcp/rehearse eng {:principal owner :grant grant}
                                  self action input)
                    (seat-lookup eng))
      (catch clojure.lang.ExceptionInfo e
        {:plan []
         :plan_is_estimate true
         :blocked_reason (clip (or (get-in (ex-data e) [:waymark10/problem :detail])
                                   (ex-message e))
                               480)}))))

(defn- plan!
  "Plan one quest for the transition `t` that asked. A paused, finished
  or abandoned quest is not planned. The key is made from `t`, so the
  same transition heard twice writes one plan."
  [eng id t]
  (when-some [row (row-of eng kind id)]
    (when (and (active? row) (not (planned-since? t row)))
      (inv/invoke! eng kind (str id) :plan (rehearsed eng row)
                   {:principal engine-actor
                    :idempotency-key (str "quest-plan:" id ":" (:id t))}))))

(def consumer-name
  "The durable cursor's name in waymark10_cursors (consumer:quests)."
  :quests)

(defn handle-transition!
  "One committed transition. A quest's own `create` or `replan` plans
  it. Never throws: a parked cursor would stop every later quest from
  being planned."
  [eng t]
  (try
    (let [k (some-> (:kind t) keyword)
          action (some-> (:action t) name)]
      (when (and (contains? (inv/resources eng) kind) (:resource-id t) action)
        (cond
          (= kind k)
          (when (contains? #{"create" "replan"} action)
            (plan! eng (:resource-id t) t))

          ;; Quests 2 goes here: a move of a row a plan names re-plans
          ;; that quest, keeps its invitation and finishes it when the
          ;; goal door was taken.
          :else nil)))
    (catch Exception e
      (warn! "transition " (:id t) " could not be handled — " (ex-message e))
      nil))
  nil)

(defn consumer-fn
  "The consumer's function of one transition. Public because a test
  drains it directly (`consumers/drain-consumer!`)."
  [eng]
  (fn [t] (handle-transition! eng t)))

(defn start!
  "Register the durable log consumer that plans each quest. opts:
  :dispatcher, :poll-ms, :from-origin?."
  ([eng] (start! eng {}))
  ([eng opts]
   (consumers/register-consumer!
    eng consumer-name (consumer-fn eng)
    (select-keys opts [:dispatcher :poll-ms :from-origin?]))))

(defn stop! [consumer]
  (some-> consumer consumers/stop-consumer!))
