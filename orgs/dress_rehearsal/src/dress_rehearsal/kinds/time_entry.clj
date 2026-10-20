(ns dress_rehearsal.kinds.time_entry
  "A time entry: the hours one crew logged on one job on one day,
  logged by its crew lead. Overtime is approved by the owner, never by
  the crew lead who logged it."
  (:require [waymark10.dsl :refer [defguard defresource refuse hold]]))

(defguard has-hours
  (refuse "A crew lead logs the hours per job each day; give the job, the day and the hours.")
  '(and (present? :job_id) (present? :worked_on) (present? :hours)))

(defguard owner-approves-overtime
  (hold "A crew lead never approves their own overtime; the owner does."))

(defresource time_entry
  {:kind :time_entry
   :plural "time_entries"
   :states [:logged :overtime_pending :accepted]
   :initial :logged
   :terminal #{:accepted}
   :summary "{data.crew} · {data.worked_on} · {data.hours}h · {state}"
   :schema [:map
            [:crew [:enum "crew_1" "crew_2" "crew_3"]]
            [:job_id {:optional true :kind :job} [:maybe :waymark/ref]]
            [:worked_on {:optional true} [:maybe [:string {:max 10}]]]
            [:hours {:optional true} [:maybe [:int {:min 0 :max 24}]]]
            [:overtime_hours {:optional true} [:maybe [:int {:min 0 :max 24}]]]
            [:crew_rate_cents {:optional true} [:maybe [:int {:min 0}]]]
            [:logged_by {:optional true :x-ref {:principal true}} [:maybe [:string {:max 80}]]]]
   :filterable {:state #{:eq :in} :crew #{:eq} :job_id #{:eq} :worked_on #{:eq}}
   :decided {:logged "Hours are accepted the day they are logged; the mayor accepts them on its own."
             :overtime_pending "Overtime waits for the owner; past 3 days, the mayor tells the owner again."}
   :actions
   {:accept           {:from #{:logged} :to :accepted
                       :guards [has-hours]
                       :safety {:idempotent true :reversible false :confirm false
                                :one-way "Accepted hours count toward the job's actual cost."}
                       :decided "The mayor accepts a day's hours on its own when they carry no overtime."}
    :claim_overtime   {:from #{:logged} :to :overtime_pending
                       :guards [has-hours]
                       :safety {:idempotent true :reversible true :confirm false}
                       :decided "The crew lead claims overtime with the day's hours."}
    :approve_overtime {:from #{:overtime_pending} :to :accepted
                       :guards [owner-approves-overtime]
                       :safety {:idempotent true :reversible false :confirm false
                                :one-way "Approved overtime is paid and counts toward the job's actual cost."}}}})
