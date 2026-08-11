(ns eveningplan10.resources.evening-session
  "The EveningSession resource: one evening, one row — staged →
  preparing → active → complete, referencing its plan by :plan_id
  rather than living embedded inside it (see evening-plan's docstring
  for why).

  :at-create is just :plan_id + :date, the two facts that never
  change; capacity/childcare/window_minutes live in :while-open
  because they're decided as the evening actually approaches, not
  once forever at creation — this app pre-stages evenings weeks
  ahead (eveningplan10.consumers), so locking that decision in early
  would be wrong. :filterable/:sortable ride separately from :fields
  because a :fields row is strictly [field (word …)] — no props slot
  for :filter/:sort the way a plain :schema entry gets one.
  :filterable is what makes evening-plan's :links.sessions href a
  real filter, not a dead one; :sortable is what its server-side grid
  embed can offer to sort by."
  (:require [waymark10.dsl :refer [defresource defguard defguardfn
                                    refuse one-of flag prose ref-to]]
            [waymark10.types :as t]))

;; a residual code guard (vocabulary doc §6): the verdict reads
;; another kind, so it can't be a pure (row, input) expression
(defguardfn date-in-plan-range
  {:judges [:plan_id :date] :reads [:evening_plan]
   :explain "That date is outside the plan's start/end range."}
  [_row inp ctx]
  (if-some [read (:read ctx)]
    (let [plan (read :evening_plan (:plan_id inp))
          d ^java.time.LocalDate (:date inp)]
      (if (and plan
               (not (.isBefore d ^java.time.LocalDate (get-in plan [:data :start_date])))
               (not (.isAfter d ^java.time.LocalDate (get-in plan [:data :end_date]))))
        (t/allow)
        (t/deny)))
    (t/allow)))   ; the pure render probe carries no :read — advertise optimistically

(defguard schedule-fits
  (refuse "Your available window ({window_minutes} mins) is too short for this activity.")
  '(>= (var :window_minutes) 60))

(defresource evening-session
  {:kind :evening_session
   :states [:staged :preparing :active :complete]
   :initial :staged
   :terminal #{:complete}
   :summary "Session: {data.date} — {state}"
   :create-guards [date-in-plan-range]
   :filterable {:plan_id #{:eq}}
   :sortable {:fields [:date] :default "date"}

   :fields
   {:at-create  [[:plan_id (ref-to :evening_plan)]
                 [:date :waymark/date]]

    :while-open [[:notes (prose "What actually happened")]
                 [:capacity (one-of :high_focus :low_focus :exhausted)]
                 [:childcare (flag)]
                 [:window_minutes [:int {:min 30 :max 180}]]
                 [:desired_activity_id (ref-to :activity {:label :desired_activity_name})]
                 [:backup_activity_id_1 (ref-to :activity {:label :backup_activity_1_name})]
                 [:backup_activity_id_2 (ref-to :activity {:label :backup_activity_2_name})]]
    :open       #{:staged :preparing :active}
    :support    [[:interrupted (flag)]]}

   :actions
   {:lock-in
    {:from #{:staged} :to :preparing
     :guards [schedule-fits]
     :safety {:idempotent true :reversible true :confirm false}}

    ;; :lock-in claims :reversible true, which demands a real
    ;; :preparing → :staged edge to point at — this is it
    :reconsider
    {:from #{:preparing} :to :staged
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Reconsidering just returns to staged; nothing external changes."}}

    :start
    {:from #{:preparing} :to :active
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Once the evening block starts, focus begins."}}

    :finish
    {:from #{:active} :to :complete
     :safety {:idempotent true :reversible false :confirm false
              :one-way "Finishing closes out the session; the record stays as history."}}}})
