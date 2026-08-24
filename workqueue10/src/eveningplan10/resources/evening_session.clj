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
                                    refuse one-of flag prose ref-to
                                    described]]
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
   ;; one session per date, so the date IS the name; without this a ref
   ;; picker and the plan's embedded list both show a raw id
   ;; (waymark-ts2)
   :label-template "Evening of {data.date}"
   :create-guards [date-in-plan-range]
   :filterable {:plan_id #{:eq}}
   :sortable {:fields [:date] :default "date"}

   ;; a :fields row is strictly [field (word …)] and has no properties
   ;; slot, so the household's prose rides on the word itself (described,
   ;; waymark-ts2). The form is untouched; only the advertisement grows
   :fields
   {:at-create  [[:plan_id (described (ref-to :evening_plan)
                                      {:label "Which stretch"
                                       :help "The plan this evening belongs to — the date has to fall inside its two dates."})]
                 [:date (described :waymark/date
                                   {:label "Which evening"
                                    :help "The night itself. One row per date, staged weeks ahead and decided as it gets close."})]]

    :while-open [[:notes (described (prose "What actually happened")
                                    {:help "Written after, not before — what you got to, what got in the way, what to try differently next time."})]
                 [:capacity (described (one-of :high_focus :low_focus :exhausted)
                                       {:label "What you've got in you"
                                        :choices {"high_focus" "Sharp — a real go at something that needs thinking"
                                                  "low_focus"  "Willing but foggy — hands, not head"
                                                  "exhausted"  "Nothing left — pick something that asks for nothing"}})]
                 [:childcare (described (flag)
                                        {:label "Kids covered"
                                         :help "On when somebody else has the children for the evening — that is what makes the workshop possible at all."})]
                 [:window_minutes (described [:int {:min 30 :max 180}]
                                             {:label "Minutes you actually have"
                                              :help "From when you're free to when you're done — anything under an hour is refused for the activities that need one."})]
                 [:desired_activity_id (described
                                        (ref-to :activity {:label :desired_activity_name})
                                        {:label "What you'd like to do"
                                         :help "First choice off the activity shelf — the one this evening is really for."})]
                 [:backup_activity_id_1 (described
                                         (ref-to :activity {:label :backup_activity_1_name})
                                         {:label "If that falls through"
                                          :help "Second choice, picked now so a tired evening doesn't have to decide anything."})]
                 [:backup_activity_id_2 (described
                                         (ref-to :activity {:label :backup_activity_2_name})
                                         {:label "And if that falls through"
                                          :help "Third choice — usually the low-energy one that always works."})]]
    :open       #{:staged :preparing :active}
    :support    [[:interrupted (described (flag)
                                          {:label "Interrupted"
                                           :help "On when the evening got taken from you — a kid up, a call, a leak. It keeps the record honest without failing the session."})]]}

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
