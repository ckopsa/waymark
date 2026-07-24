(ns choreplan10.resources.day
  "The Day resource: the housekeeper's day sheet — the anchor row the
  day-board surface composes (surfaces anchor on rows, so \"today\"
  earns a noun; mealplan10's week board anchors on the plan the same
  way).

  The related edges are range joins, deliberately: a day relates to
  every run due ON OR BEFORE it and every prep task dated on or
  before it — the day board's members then filter to the still-
  actionable (states due, pending/scheduled), so the board
  reads as \"everything that should be done by today and isn't\":
  the overdue backlog and today's work in one screen, the archive
  nowhere in sight.

  :notes is the household's standing message to the housekeeper for
  the day (\"skip the office, guests used the guest bath\"); close
  wraps the day — the runs themselves keep their own records."
  (:require [waymark10.dsl :refer [defresource prose]]))

(defresource day
  {:kind :day
   :states [:open :closed]
   :initial :open
   :terminal #{:closed}
   :summary "{data.date} · {state}"
   :label-template "{data.date}"
   :filterable {:state #{:eq :in} :date #{:eq :range}}
   :sortable {:fields [:date] :default "date"}

   :fields
   {:at-create  [[:date :waymark/date]]
    :while-open [[:notes (prose "Notes for the day")]]
    :open       #{:open}}

   ;; the day board's edges — what this day relates to, by law
   :related {:runs {:kind :chore_run :on [[:date :>= :due_date]]}
             :prep {:kind :prep_task :on [[:date :>= :date]]}}

   :actions
   {:close
    {:from #{:open} :to :closed
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Closing the day just ends the sheet; every run keeps its own record."}
     :display {:label "Close the day" :order 9}}}})

(def day-board
  "The housekeeper's screen (phase 9b surface, the week-board
  pattern): one day, its still-actionable work — runs due by the day
  that are still due, prep steps dated by the day the planner still
  holds open. The :where standing filters keep the archive out."
  {:name :day-board
   :anchor :day
   :members [{:name :runs :kind :chore_run :related :runs
              :where {:state #{"due"}}}
             ;; :state, not :status, since the meals fold (waymark-
             ;; bwu.2): prep_task is mealplan's NATIVE kind in the one
             ;; engine — pending/scheduled are its real states, no
             ;; longer a mirror's domain-state-as-data
             {:name :prep :kind :prep_task :related :prep
              :where {:state #{"pending" "scheduled"}}}]
   :showcase [:close]})
