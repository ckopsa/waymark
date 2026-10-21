(ns clinic_rehearsal.kinds.plan-of-care
  "A plan of care: the therapist evaluates the patient and writes how
  often and how long they are seen, for example 2 visits a week for 6
  weeks. Visits are booked from it while it runs; it ends when its
  weeks are done, and lapses when the physician has not re-signed it
  within 30 days."
  (:require [waymark10.dsl :refer [defguard defresource defscenario refuse]]))

(defguard has-patient
  (refuse "A plan of care is written for one patient; name the patient first.")
  '(present? :patient_id))

(defguard has-frequency
  (refuse "A plan of care says how many visits a week.")
  '(present? :visits_per_week))

(defguard has-weeks
  (refuse "A plan of care says for how many weeks.")
  '(present? :weeks))

(defguard has-evaluation
  (refuse "A therapist evaluates the patient before writing the plan; the evaluation goes on it.")
  '(present? :evaluation))

(defguard physician-signed
  (refuse "A plan runs only while the physician has signed it; put the day they signed on it.")
  '(present? :physician_signed_on))

(defguard physician-signed-not-future
  (refuse "The day the physician signed the plan cannot be later than today.")
  '(<= (data :physician_signed_on) (today)))

(defscenario activate-with-physician-signature
  "The treating therapist starts a plan by putting on it the day the physician signed it."
  {:kind :plan_of_care :attempt :activate :at "2026-10-21T16:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :draft :data {:patient_id "pat-1" :therapist "Dana Holt" :visits_per_week 2 :weeks 6 :evaluation "Left knee post-op."}}
   :input {:physician_signed_on "2026-10-20"}
   :expect {:allowed true}})

(defscenario activate-without-physician-signature
  "A plan started without the day the physician signed it is refused."
  {:kind :plan_of_care :attempt :activate :at "2026-10-21T16:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :draft :data {:patient_id "pat-1" :therapist "Dana Holt" :visits_per_week 2 :weeks 6 :evaluation "Left knee post-op."}}
   :input {}
   :expect {:refused :physician-signed}})

(defscenario resign-with-new-signature
  "The front desk records the physician's re-signature by putting its day on the plan."
  {:kind :plan_of_care :attempt :resign :at "2026-10-21T16:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :active :data {:therapist "Dana Holt"}}
   :input {:physician_signed_on "2026-10-21"}
   :expect {:allowed true}})

(defscenario resign-without-new-signature
  "A re-signature recorded without its day is refused."
  {:kind :plan_of_care :attempt :resign :at "2026-10-21T16:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :active :data {:therapist "Dana Holt"}}
   :input {}
   :expect {:refused :physician-signed}})

(defresource plan-of-care
  {:kind :plan_of_care
   :plural "plans_of_care"
   :states [:draft :active :ended :lapsed]
   :initial :draft
   :terminal #{:ended :lapsed}
   :summary "{data.therapist} · {data.visits_per_week}x/week for {data.weeks} weeks · {state}"
   :schema [:map
            [:patient_id {:optional true :kind :patient} [:maybe :waymark/ref]]
            [:therapist [:string {:min 1 :max 80}]]
            [:evaluation {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 4000}]]]
            [:visits_per_week {:optional true} [:maybe [:int {:min 1 :max 7}]]]
            [:weeks {:optional true} [:maybe [:int {:min 1 :max 52}]]]
            [:starts_on {:optional true} [:maybe [:string {:max 10}]]]
            [:physician_signed_on {:optional true} [:maybe :waymark/date]]]
   :filterable {:state #{:eq :in} :therapist #{:eq} :patient_id #{:eq}}
   :decided {:draft "A plan may stay a draft for three days while the physician signs it; past that, the mayor tells the owner."
             :active "A plan stays active until its weeks are done or the physician has not re-signed it within 30 days."}
   :expected-age {:draft 259200 :active 2592000}
   :actions
   {:activate {:from #{:draft} :to :active
               :input [:map [:physician_signed_on :waymark/date]]
               :guards [has-patient has-frequency has-weeks has-evaluation physician-signed physician-signed-not-future]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "An active plan books visits; end it or let it lapse to stop them."}
               :decided "The treating therapist starts a plan on their own once it says its frequency and the physician has signed it."}
    :resign   {:from #{:active} :to :active
               :input [:map [:physician_signed_on :waymark/date]]
               :guards [physician-signed physician-signed-not-future]
               :safety {:idempotent true :reversible true :confirm false}
               :decided "The front desk records the physician's re-signature on its own when it arrives."}
    :end      {:from #{:active} :to :ended
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "An ended plan books no more visits; write a new plan for more care."}
               :decided "The treating therapist ends a plan on their own when its weeks are done, because visits that recur on a plan stop when the plan ends."}
    :lapse    {:from #{:active} :to :lapsed
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A lapsed plan books no more visits until the physician signs a new one."}
               :decided "The front desk lapses a plan on its own when the physician has not re-signed it within 30 days, because visits stop then."}}})
