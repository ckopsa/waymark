(ns mealplan10.resources.prep-task
  "The PrepTask resource: when to thaw the meat and when to start
  cooking.

  After a plan is finalized, the AI derives one task per meal step
  from the recipe's thaw_hours / prep_minutes and creates them here.
  Putting a task on the family calendar is outward-facing, so schedule
  is confirm-gated with its consequence spelled out: the agent client
  hard-stops until a human approves, then creates the calendar event
  and records its id back on the task.

  plan_id is a :waymark/ref; the schedule input names what it actually
  is (the NEW event's id, not an edit of the stored one), which is why
  it declares no :edit. The calendar is a kind in this engine
  (mealplan10.resources.event), so the linkage is a real ref.

  overdue is the deliverable-tracker fact in miniature (design §2,
  §3): the clock flips it — no write, no poll — and ?overdue=true is
  one indexed filter instead of every client re-deriving \"past due\".
  due_at demanded :waymark/instant into the schema vocabulary (phase
  8): a point in time the clock can compare.

  Recorded deviations ride the declaration itself (:deviations, DX
  phase 5) — fingerprint-carried, rendered by waymark10.dev/explain."
  (:require [waymark10.dsl :refer [defresource defhandler one-of ref-to]]))

(defhandler set-calendar-event [row inp _ctx]
  (assoc-in row [:data :calendar_event_id] (:event_id inp)))

;; the two-origin rows share one opts value each — rows of one action
;; must agree on everything but :confirm, so agreement is spelled as
;; citation, not repetition
(def step-done
  {:one-way "Marking a prep step done records kitchen reality; nothing external changes."
   :display {:label "Done" :order 2}})

(def drop-task
  {:confirm "The task is dropped; any calendar event for it should be removed by hand."
   :display {:label "Cancel" :style :danger :order 9}})

(defresource prep-task
  {:kind :prep_task
   :initial :pending
   :terminal #{:done :cancelled}
   :summary "{data.task_type} · {data.meal_name} ({data.date}) · {state}"
   :schema [:map
            [:plan_id {:kind :plan :filter #{:eq}} :waymark/ref]
            [:date {:x-display {:label "Dinner date"}} :waymark/date]
            [:meal_name [:string {:min 1 :max 200}]]
            [:task_type {:filter #{:eq :in}} (one-of :thaw :prep :cook)]
            [:due_at {:filter #{:after} :sort :default
                      :x-display {:label "When to start"}}
             :waymark/instant]
            ;; a one-liner fact stays inline: the clock flips it — no
            ;; write, no poll
            [:overdue {:optional true :filter #{:eq}
                       :derived {:over [:due_at :now]
                                 :expr '(< (var :due_at) (var :now))}}
             [:maybe :boolean]]
            [:duration_minutes {:optional true} [:maybe [:int {:min 0}]]]
            [:calendar_event_id {:optional true :kind :event
                                 :x-display {:hidden true}}
             [:maybe :waymark/ref]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 1000}]]]]
   :links [{:rel "plan" :kind :plan
            :summary "The meal plan this task serves"}]
   :filterable {:state #{:eq :in}}
   :display {:title "{data.task_type}: {data.meal_name}"}
   :deviations
   ["No :undo pointers — every edge here is honestly one-way or confirmed, and nothing walks a task back."
    "task_type carries no field default — the AI states it with each task."
    "The with_plan profile has no v10 spelling."]
   ;; the whole machine as rows — the rows name the states
   :flow
   [[:pending   :schedule :scheduled
     ;; the arg is named for what it is: the id of the NEW event just
     ;; created — not an edit of the stored one, so no prefill, no :edit
     {:args [[:event_id (ref-to :event)]]
      :confirm "An event goes on the family calendar for this prep step."
      :handler set-calendar-event
      :display {:label "Put on calendar" :style :primary :order 1}}]
    [:pending   :complete :done      step-done]
    [:scheduled :complete :done      step-done]
    [:pending   :cancel   :cancelled drop-task]
    [:scheduled :cancel   :cancelled drop-task]]})
