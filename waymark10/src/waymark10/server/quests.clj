(ns waymark10.server.quests
  "The quest (docs/spec-quests.md): a goal a person accepted, as one door
  on one row, and the steps that lead to it.

  THE PERSON NAMES THE GOAL AND NOTHING ELSE. Create takes the goal
  row's path, the goal door and that door's input. The engine stamps
  `owner` from the principal and `grant` from the grant the create was
  made under, never from the body.

  THE ENGINE ALONE WRITES THE PLAN. A quest is born with an empty plan
  and no `planned_at`. `plan` and `finish` are the engine's doors; no
  hand at the wire takes them. This namespace plans nothing: the
  consumer that hears `create` and `replan` and walks `plan` is its own
  change (Quests 1b).

  ONE PINNED QUEST PER OWNER. `pin` unpins the owner's other quests
  through their own `unpin` door, in the same transaction. Only an
  active quest is pinned: `pause`, `abandon` and `finish` unpin."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.invitations :as invitations]
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
