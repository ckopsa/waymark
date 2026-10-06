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

  IT PLANS AGAIN AFTER EVERY MOVE ON ITS ROWS. The consumer keeps an
  index of the rows the active quests name: the goal row and each
  step's row. A transition on such a row that is the goal door itself
  finishes the quest. Any other one rehearses the goal again: the steps
  that transition ended stay at the top as `done`, and the rest are
  the fresh rehearsal's. A transition on a row no active quest names
  reads no quest.

  ONE INVITATION FOLLOWS THE PLAN. The step the owner takes now is
  handed to the owner as an invitation the engine authors. When that
  step changes the old invitation is withdrawn and a new one opens. A
  step that waits on a seat or on a tap opens none. `finish` and
  `abandon` withdraw it.

  ONE PINNED QUEST PER OWNER. The rule is the engine's: `pin` unpins
  the owner's other quests through their own `unpin` door, in the same
  transaction, as `engine-actor` and not as the caller. A grant that
  offers `pin` needs no `unpin`. Only an active quest is pinned:
  `pause`, `abandon` and `finish` unpin."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.grants :as grants]
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
  "The system actor that plans and finishes a quest, and that unpins an
  owner's other quests when the owner pins one."
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

(g/defguard the-owner-or-the-engine-unpins-it
  {:reads [:principal]
   :open "The wall is about who: the owner moves its own quest, and no field of this door makes anyone else the owner."
   :explain "Only the owner of this quest unpins it. The engine unpins it when the owner pins another quest."}
  [row _inp ctx]
  (if (or (is? row :owner ctx) (engine? ctx)) (t/allow) (t/deny)))

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
  ;; this write's transaction. One pinned quest per owner is the engine's
  ;; rule, so the engine's actor takes that door: the caller's grant needs
  ;; no `unpin`, and each one's history says the engine unpinned it
  (when-some [invoke (:invoke ctx)]
    (doseq [other (quests-of (get-in row [:data :owner]) ctx)
            :when (and (not= (str (:id other)) (str (:id row)))
                       (true? (get-in other [:data :pinned]))
                       (active? other))]
      (invoke kind (str (:id other)) :unpin {} {:as engine-actor})))
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
          :waiting_on (:waiting_on inp)
          :invitation (:invitation inp)))

;; ── what a collection row reads ─────────────────────────────────────
;; the plan is a vector, so it does not ride a summary row; these two
;; lines are worked out from it at read time and do

(defn- step-state [step]
  (some-> (:state step) name))

(defn progress-line
  "The count a reader sees: k steps done, n known so far. It is never
  \"k of n\": a plan is not a total (docs/spec-quests.md, Counts). Nil
  until the first plan lands."
  [row _]
  (let [plan (get-in row [:data :plan])]
    (when (seq plan)
      (str (count (filter #(= "done" (step-state %)) plan))
           " done, " (count plan) " known so far"))))

(defn next-line
  "The head step in one line: its note when it is the owner's to take,
  or who it waits on. Nil when every known step is done."
  [row _]
  (let [d (:data row)
        head (first (remove #(= "done" (step-state %)) (:plan d)))]
    (when head
      (if (= "waiting" (step-state head))
        (str "waiting on "
             (or (not-empty (:waiting_on head))
                 (not-empty (:waiting_on d))
                 (when (= "held" (some-> (:whose head) name)) "your tap")
                 "someone else"))
        (or (not-empty (:note head)) (str (:door head)))))))

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
    [:maybe [:string {:max 128}]]]
   [:invitation {:optional true
                 :kind :invitation
                 :x-display {:label "Invitation"
                             :help "The invitation that hands the owner the step to take now, written by the engine. Empty when no step is the owner's."}}
    [:maybe :waymark/ref]]])

(defresource quest
  {:kind :quest
   :plural "quests"
   :nav :secondary
   :states [:active :paused :finished :abandoned]
   :initial :active
   :terminal #{:finished :abandoned}
   :summary "{data.title} · {state}"
   :label-template "{data.title}"
   :computed {:progress
              {:schema [:maybe [:string {:max 60}]]
               :x-display {:label "Progress"
                           :help "How many steps are done, and how many are known so far. More may appear."}
               :fn progress-line}
              :next_step
              {:schema [:maybe [:string {:max 300}]]
               :x-display {:label "Next"
                           :help "The note of the step to take now, or who the quest waits on."}
               :fn next-line}}
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
   ;; the pinned quest first, then the newest
   :sortable {:fields [:pinned :created_at] :default ["-pinned" "-created_at"]}
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
     :guards [the-owner-or-the-engine-unpins-it]
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
  [{:keys [door row reason needs confirm consequence held hold warnings] :as entry}
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
      (let [seats (seq (take 3 (when (and k seat-lookup) (seat-lookup k action row))))
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
  `seat-lookup` is (fn [kind action self]) → the names of those who can
  take that door on the row at `self` (nil when the rehearsal bound no
  row), and is asked only for a door the owner cannot take.

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

(defn- row-of
  "One row of kind `k`, decoded; nil when it is gone."
  [eng k id]
  (let [st (:storage eng)]
    (when-some [rdef (get (inv/resources eng) k)]
      (some->> (store/with-tx st
                 (fn [tx] (store/load-row st tx k (str id) {})))
               (inv/decode-row rdef)))))

(defn- id-of-path
  "The id a row's path ends in, `/api/<plural>/<id>`; nil for no path."
  [self]
  (some-> self str not-empty (str/split #"/") peek not-empty))

(defn- seat-lookup
  "(fn [kind action self]) → the display names of those who can take
  that door on the row at `self`, the quest's `owner` left out: the
  active seats and the holders of a live grant, each judged as a grant
  is judged (`grants/door-takers`). Read when a plan needs it, never
  per write."
  [eng owner]
  (fn [k action self]
    (grants/door-takers eng k action (id-of-path self) owner)))

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
                    (seat-lookup eng owner))
      (catch clojure.lang.ExceptionInfo e
        {:plan []
         :plan_is_estimate true
         :blocked_reason (clip (or (get-in (ex-data e) [:waymark10/problem :detail])
                                   (ex-message e))
                               480)}))))

;; ── the plan, kept in step ──────────────────────────────────────────

(defn- before?
  "Whether instant `a` is before instant `b`; false when either is
  missing."
  [a b]
  (let [a (invitations/instant-of a)
        b (invitations/instant-of b)]
    (boolean (and a b (.isBefore a b)))))

(defn- asked-since?
  "Whether the owner asked for a new plan after the one the row holds
  was written. The plan that answers the ask replaces the old one
  whole: no step of it is carried."
  [row]
  (let [asked (get-in row [:data :replan_requested_at])
        planned (get-in row [:data :planned_at])]
    (boolean (and asked (or (nil? planned) (before? planned asked))))))

(defn- taken
  "A step of the old plan, as a step that was taken."
  [step]
  (-> (into {} (remove (comp nil? val)) step)
      (update :whose #(some-> % name))
      (assoc :state "done")))

(defn- carried
  "The fresh plan under the steps already taken. A step of the old plan
  that was done stays, and so does one the heard transition ended: a
  step on `self` whose door is `action`. They keep their order at the
  top and the fresh steps are numbered after them."
  [old fresh {:keys [self action]}]
  (let [done (into []
                   (comp (filter (fn [s]
                                   (or (= "done" (some-> (:state s) name))
                                       (and self
                                            (= self (:self s))
                                            (= action (some-> (:door s) name))))))
                         (map taken))
                   old)]
    (assoc fresh :plan
           (into []
                 (comp (take 100)
                       (map-indexed (fn [i s] (assoc s :n (inc (long i))))))
                 (into done (:plan fresh))))))

;; ── the invitation ──────────────────────────────────────────────────

(defn- step-to-hand
  "The step the owner is invited to take now: the `next` one, when it
  is a person's or a confirm, or a choice that names the arguments it
  needs. A choice of a row names no door the owner can be sent to."
  [plan]
  (first (filter (fn [s]
                   (and (= "next" (:state s))
                        (or (contains? #{"person" "confirm"} (:whose s))
                            (and (= "choice" (:whose s)) (seq (:needs s))))))
                 plan)))

(defn- invitation-of
  "The invitation a step becomes: the step's row, door and needs,
  addressed to the quest's owner, with the step's note."
  [row step]
  (let [fields (into [] (take 8) (:needs step))]
    (cond-> {:subject (get-in row [:data :owner])
             :self (:self step)
             :action (:door step)
             :note (or (clip (:note step) 240)
                       (clip (str "The next step of your quest: "
                                  (get-in row [:data :title]))
                             240))}
      (seq fields) (assoc :fields fields))))

(defn- open-invitation
  "The quest's invitation, when it is still open; else nil."
  [eng row]
  (when-some [id (some-> (get-in row [:data :invitation]) str not-empty)]
    (when-some [invitation (row-of eng invitations/kind id)]
      (when (= "open" (some-> (:state invitation) name))
        invitation))))

(defn- same-step? [invitation want]
  (and (= (:self want) (get-in invitation [:data :self]))
       (= (:action want) (str/trim (str (get-in invitation [:data :action]))))
       (= (vec (:fields want)) (vec (get-in invitation [:data :fields])))))

(defn- withdraw!
  "Take one open invitation back with the engine's hand, best effort."
  [eng invitation-id]
  (try
    (inv/invoke! eng invitations/kind (str invitation-id) :withdraw {}
                 {:principal engine-actor})
    (catch Exception e
      (warn! "invitation " invitation-id " could not be withdrawn ("
             (ex-message e) ")")
      nil)))

(defn- close-invitation!
  "Withdraw the quest's invitation when it is still open."
  [eng row]
  (when-some [open (open-invitation eng row)]
    (withdraw! eng (:id open))))

(defn- invite!
  "Keep the quest's invitation in step with `plan`, and answer the id
  of the one that stands, or nil. An open invitation for the same step
  is kept. Any other is withdrawn, and the step to hand over opens a
  new one. The key is made from `t`, so a replay opens no second one.
  A create the invitation's own guards refuse is a warning: the plan
  still lands, with no invitation."
  [eng row plan t]
  (when (contains? (inv/resources eng) invitations/kind)
    (let [open (open-invitation eng row)
          want (some->> (step-to-hand plan) (invitation-of row))]
      (if (and open want (same-step? open want))
        (str (:id open))
        (do
          (when open (withdraw! eng (:id open)))
          (when want
            (try
              (some-> (inv/create! eng invitations/kind want
                                   {:principal engine-actor
                                    :idempotency-key (str "quest-open:" (:id row) ":" (:id t))})
                      :row :id str)
              (catch Exception e
                (warn! "quest " (:id row) " could not open an invitation ("
                       (or (inv/problem-reason e) (ex-message e)) ")")
                nil))))))))

(defn- plan!
  "Plan one quest for the transition `t` that asked. A paused, finished
  or abandoned quest is not planned. The key is made from `t`, so the
  same transition heard twice writes one plan. `heard` is the row and
  the door of a move on one of the plan's rows, or nil when the quest's
  own door asked."
  [eng id t heard]
  (when-some [row (row-of eng kind id)]
    (when (and (active? row) (not (planned-since? t row)))
      (let [old (when-not (asked-since? row) (get-in row [:data :plan]))
            plan (carried old (rehearsed eng row) heard)]
        (inv/invoke! eng kind (str id) :plan
                     (assoc plan :invitation (invite! eng row (:plan plan) t))
                     {:principal engine-actor
                      :idempotency-key (str "quest-plan:" id ":" (:id t))})))))

(defn- moved!
  "A transition on a row quest `qid` names. The goal door itself
  finishes the quest and closes its invitation; any other move plans
  it again. A transition older than the quest moves nothing."
  [eng qid t self action]
  (when-some [row (row-of eng kind qid)]
    (when (and (active? row) (not (before? (:at t) (:created-at row))))
      (if (and (= self (str/trim (str (get-in row [:data :self]))))
               (= action (str/trim (str (get-in row [:data :action])))))
        (do (inv/invoke! eng kind (str qid) :finish {}
                         {:principal engine-actor
                          :idempotency-key (str "quest-finish:" qid ":" (:id t))})
            (close-invitation! eng row))
        (plan! eng qid t {:self self :action action})))))

;; ── the index of the rows the active quests name ────────────────────

(def ^:private index-cap
  "The most active quests the index is built from."
  10000)

(defn- quest-rows
  "The rows a quest names: its goal row and each step's row."
  [row]
  (into #{}
        (keep #(some-> % str str/trim not-empty))
        (cons (get-in row [:data :self])
              (map :self (get-in row [:data :plan])))))

(defn- indexed
  "The index with quest `qid` naming exactly `rows`; none takes it out."
  [index qid rows]
  (let [qid (str qid)
        index (reduce (fn [m r]
                        (let [left (disj (get-in m [:by-row r] #{}) qid)]
                          (if (seq left)
                            (assoc-in m [:by-row r] left)
                            (update m :by-row dissoc r))))
                      index
                      (get-in index [:of-quest qid]))]
    (if (seq rows)
      (-> (reduce (fn [m r] (update-in m [:by-row r] (fnil conj #{}) qid))
                  index rows)
          (assoc-in [:of-quest qid] rows))
      (update index :of-quest dissoc qid))))

(defn- built
  "The index, read from every active quest. One read, when a consumer
  first needs it."
  [eng]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) kind)]
    (reduce (fn [m raw]
              (let [row (inv/decode-row rdef raw)]
                (indexed m (:id row) (quest-rows row))))
            {:by-row {} :of-quest {}}
            (store/with-tx st
              (fn [tx]
                (vec (store/query-rows st tx kind {:state :active}
                                       {:limit index-cap})))))))

(defn- index-of [eng index]
  (or @index (reset! index (built eng))))

(defn- note-quest!
  "Bring one quest's entry in a built index up to its row: an active
  quest names its rows, any other names none."
  [eng index qid]
  (when (some? @index)
    (let [row (row-of eng kind qid)]
      (swap! index indexed qid (when (and row (active? row)) (quest-rows row))))))

(def consumer-name
  "The durable cursor's name in waymark10_cursors (consumer:quests)."
  :quests)

(defn handle-transition!
  "One committed transition. A quest's own `create`, `replan` or
  `resume` plans it, and its `abandon` or `finish` closes its
  invitation. A transition on a row an active quest names finishes that
  quest when it is the goal door, and plans it again otherwise. `index`
  is the consumer's atom of those rows; without one the index is read
  for this call alone. Never throws: a parked cursor would stop every
  later quest from being planned."
  ([eng t] (handle-transition! eng t (atom nil)))
  ([eng t index]
   (try
     (let [rs (inv/resources eng)
           k (some-> (:kind t) keyword)
           id (some-> (:resource-id t) str)
           action (some-> (:action t) name)]
       (when (and (contains? rs kind) id action)
         (if (= kind k)
           (do (case action
                 ("create" "replan" "resume") (plan! eng id t nil)
                 ("abandon" "finish") (some->> (row-of eng kind id)
                                             (close-invitation! eng))
                 nil)
               (note-quest! eng index id))
           (when-some [rdef (get rs k)]
             (let [self (str "/api/" (:plural rdef) "/" id)]
               (doseq [qid (get-in (index-of eng index) [:by-row self])]
                 (try
                   (moved! eng qid t self action)
                   (catch Exception e
                     (warn! "quest " qid " could not follow transition "
                            (:id t) " — " (ex-message e))))
                 (note-quest! eng index qid)))))))
     (catch Exception e
       (warn! "transition " (:id t) " could not be handled — " (ex-message e))
       nil))
   nil))

(defn consumer-fn
  "The consumer's function of one transition, with its own index of the
  rows the active quests name. Public because a test drains it directly
  (`consumers/drain-consumer!`)."
  [eng]
  (let [index (atom nil)]
    (fn [t] (handle-transition! eng t index))))

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
