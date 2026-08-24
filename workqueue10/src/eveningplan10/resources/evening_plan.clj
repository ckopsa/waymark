(ns eveningplan10.resources.evening-plan
  "The EveningPlan resource: just the boundary — start_date, end_date,
  its own draft/archived lifecycle. It does NOT hold the days.

  A day-in-a-plan is a real evening_session row (one per date, its own
  staged→preparing→active→complete lifecycle), not data embedded in
  this resource — see eveningplan10.resources.evening-session for why
  that split earns its cost here (unlike mealplan10's plan, whose
  embedded :days is the right call for a day with no lifecycle of its
  own). Populating a plan's evenings is therefore NOT this resource's
  :on-create — the engine's create hooks are read-only across kinds
  (:read/:find, no writer) — it's eveningplan10.consumers, a durable
  reaction to this resource's own create transition.

  :owns + :links mirror how a real meal's envelope carries its
  ingredients (waymark9's meal.links.ingredients): fetch a plan over
  HTTP and links.sessions carries an embedded array of its evenings,
  no second request. :via is spelled because evening_session's field
  is :plan_id, not the :evening_plan_id the sugar would default to."
  (:require [waymark10.dsl :refer [defresource]]))

(defresource evening-plan
  {:kind :evening_plan
   :states [:draft :archived]
   :initial :draft
   :terminal #{:archived}
   :summary "Plan {data.start_date} → {data.end_date} · {state}"
   ;; a plan is a stretch of evenings and has no name of its own; the
   ;; two dates ARE how the household says which one it means, and the
   ;; alternative a ref picker falls back to is a raw id (waymark-ts2)
   :label-template "Evenings {data.start_date} → {data.end_date}"
   :schema [:map
            [:start_date {:filter #{:eq :range} :sort :default
                          :x-display {:label "First evening"
                                      :help "The first night this stretch covers — a session gets staged for every date from here to the last."}}
             :waymark/date]
            [:end_date {:filter #{:eq :range}
                        :x-display {:label "Last evening"
                                    :help "The last night this stretch covers; an evening outside the two dates is refused rather than quietly filed here."}}
             :waymark/date]
            [:notes {:optional true
                     :examples ["Ana's away the second week — plan the loud jobs for then."]
                     :x-display {:widget "prose"
                                 :label "What this stretch is for"
                                 :help "Anything that shapes the whole run of evenings — a trip, a deadline, a season with the light going early."}}
             [:maybe [:string {:max 2000}]]]]
   :owns {:sessions {:kind :evening_session :via :plan_id}}
   :links [{:rel "sessions" :owns :evening_session
            :summary "The plan's evening sessions" :embed true}]
   :actions
   {:archive {:from #{:draft} :to :archived
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Archiving retires the plan; its evenings stay readable as a record."}}}})
