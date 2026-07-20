(ns choreplan10.resources.prep-task
  "The PrepTask resource: a Mirror of mealplan10's prep_task — the
  housekeeper's slice of the kitchen (?assignee=housekeeper is the
  whole feed), so meal prep sits in the day's worklist next to the
  chore runs without choreplan re-authoring what the meal plan
  already derives.

  The authority keeps everything that is the PLAN's business: the
  plan_id and calendar_event_id refs point at kinds this engine does
  not register, so they stay behind — meal_name/date ride the task as
  garnish precisely so it reads standalone. Domain state is data
  (:status, the planner's own four states); the machine here is the
  sync machine. :overdue derives LOCALLY over the synced due_at — the
  clock flips it in this engine, no write, no poll, one indexed
  filter for the worklist.

  One local write: complete — the housekeeper marking kitchen
  reality. It sets :status and the post-commit pass pushes it back
  (mirror/with-push in main), where the boundary translates the
  document into mealplan's own :complete action (see
  choreplan10.mirror.mealplan/push-plan). A step the cook cancelled
  upstream refuses locally with the guard's sentence; a push the
  planner refuses lands conflicted, and resolve_conflict decides."
  (:require [waymark10.dsl :refer [defguard defhandler refuse resource]]
            [waymark10.server.mirror :as mirror]))

;; a kitchen feed: due times matter within the hour, not the second
(def ttl-seconds 900)
(def discover-every 900)

;; local writes move between the writable sync states (the machine
;; owns conflicted; resolve_conflict is the way back)
(def ^:private writable #{:fresh :stale :unreachable})

(defguard not-cancelled
  (refuse "A step the cook cancelled does not complete — the plan already let it go.")
  '(not= (var :status) "cancelled"))

(defhandler mark-done [row _inp _ctx]
  (assoc-in row [:data :status] "done"))

(defn prep-task-resource
  [adapter]
  (resource
   (mirror/declaration
    {:kind :prep_task
     :summary "{data.task_type} · {data.meal_name} ({data.date}) · {data.status}"
     :label-template "{data.task_type}: {data.meal_name}"
     :schema [:map
              [:meal_name {:optional true}
               [:maybe [:string {:max 200}]]]
              [:date {:optional true} [:maybe :waymark/date]]
              [:task_type {:optional true :filter #{:eq :in}}
               [:maybe [:enum "thaw" "prep" "cook"]]]
              [:assignee {:optional true}
               [:maybe [:waymark/vocab {:open true}]]]
              [:due_at {:optional true :filter #{:after} :sort :default
                        :x-display {:label "When to start"}}
               [:maybe :waymark/instant]]
              ;; local law over a synced fact: the worklist's one
              ;; indexed "past due" filter
              [:overdue {:optional true :filter #{:eq}
                         :derived {:over [:due_at :now]
                                   :expr '(< (var :due_at) (var :now))}}
               [:maybe :boolean]]
              ;; the planner's own row state, as data
              [:status {:optional true :filter #{:eq :in}
                        :x-display {:showcase true}}
               [:maybe [:enum "pending" "scheduled" "done" "cancelled"]]]
              [:duration_minutes {:optional true} [:maybe [:int {:min 0}]]]
              [:notes {:optional true :x-display {:widget "prose"}}
               [:maybe [:string {:max 1000}]]]]
     :filterable {:state #{:eq :in}}
     :display {:title "{data.task_type}: {data.meal_name}"}
     :actions
     {:complete
      {:from writable :to :fresh
       :guards [not-cancelled]
       :safety {:idempotent true :reversible false :confirm false
                :one-way "Done is done — the kitchen's record; the planner hears about it on the push."}
       :handler mark-done
       :display {:label "Done" :style :primary :order 1}}}}
    {:adapter adapter
     :ttl-seconds ttl-seconds
     :discover-every discover-every
     :push-on-write true})))
