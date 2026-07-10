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

  Recorded deviations: task_type carries no field default (v10
  declares none — the AI states it); the with_plan profile and href
  link render have no v10 spelling (the link declaration is carried)."
  (:require [waymark10.resource :as r :refer [defresource defhandler]]))

(defhandler set-calendar-event [row inp _ctx]
  (assoc-in row [:data :calendar_event_id] (:event_id inp)))

(defresource prep-task
  {:kind :prep_task
   :states [:pending :scheduled :done :cancelled]
   :initial :pending
   :terminal #{:done :cancelled}
   :summary "{data.task_type} · {data.meal_name} ({data.date}) · {state}"
   :schema [:map
            [:plan_id {:kind :plan} :waymark/ref]
            [:date {:x-display {:label "Dinner date"}} :waymark/date]
            [:meal_name [:string {:min 1 :max 200}]]
            [:task_type [:enum "thaw" "prep" "cook"]]
            [:due_at {:x-display {:label "When to start"}} :waymark/instant]
            [:overdue {:optional true} [:maybe :boolean]]
            [:duration_minutes {:optional true} [:maybe [:int {:min 0}]]]
            [:calendar_event_id {:optional true :kind :event
                                 :x-display {:hidden true}}
             [:maybe :waymark/ref]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 1000}]]]]
   :derived {:overdue {:over [:due_at :now]
                       :expr '(< (var :due_at) (var :now))}}
   ;; carried declaration; envelope link render is v10's phase-3 punt
   :links [{:rel "plan" :kind :plan
            :summary "The meal plan this task serves"}]
   :filterable {:state #{:eq :in}
                :plan_id #{:eq}
                :task_type #{:eq :in}
                :due_at #{:after}
                :overdue #{:eq}}
   :sortable {:fields [:due_at] :default "due_at"}
   :display {:title "{data.task_type}: {data.meal_name}"}
   :actions
   {:schedule {:from #{:pending} :to :scheduled
               ;; named for what it is: the id of the NEW event just
               ;; created — not an edit of the stored one, so no
               ;; prefill and no :edit
               :input [:map [:event_id {:kind :event} :waymark/ref]]
               :safety {:idempotent true :reversible false :confirm true
                        :consequence "An event goes on the family calendar for this prep step."}
               :handler set-calendar-event
               :display {:label "Put on calendar" :style :primary :order 1}}
    :complete {:from #{:pending :scheduled} :to :done
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "Marking a prep step done records kitchen reality; nothing external changes."}
               :display {:label "Done" :order 2}}
    :cancel {:from #{:pending :scheduled} :to :cancelled
             :safety {:idempotent true :reversible false :confirm true
                      :consequence "The task is dropped; any calendar event for it should be removed by hand."}
             :display {:label "Cancel" :style :danger :order 9}}}})
