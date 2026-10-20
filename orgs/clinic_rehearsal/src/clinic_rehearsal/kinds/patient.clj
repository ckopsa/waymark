(ns clinic_rehearsal.kinds.patient
  "A patient: the chart opened at intake from an accepted referral. The
  patient signs the consent form and the privacy notice and gives their
  insurance; the front desk verifies coverage before the first visit,
  and a patient with no verified coverage pays self-pay rates. A chart
  is kept seven years after the last visit, then purged."
  (:require [waymark10.dsl :refer [defguard defresource hold refuse]]))

(defguard has-referral
  (refuse "A chart is opened from the physician's accepted referral; name it first.")
  '(present? :referral_id))

(defguard consent-signed
  (refuse "The patient signs the consent form at intake, before any care.")
  '(present? :consent_signed_on))

(defguard privacy-signed
  (refuse "The patient signs the privacy notice at intake, before any care.")
  '(present? :privacy_signed_on))

;; :coverage is absent until the front desk has asked the insurer; its
;; two values are the only ways out of intake
(defguard coverage-settled
  (refuse "The front desk verifies coverage with the insurer before the first visit; without verified coverage the patient is self-pay.")
  '(present? :coverage))

(defguard owner-purges
  (hold "A chart is purged only on the owner's tap."))

(defguard has-last-visit
  (refuse "A chart is purged seven years after the last visit; the last visit's day must be on it.")
  '(present? :last_visit_on))

(defresource patient
  {:kind :patient
   :plural "patients"
   :states [:intake :active :discharged :purged]
   :initial :intake
   :terminal #{:purged}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 120}]]
            [:referral_id {:optional true :kind :referral} [:maybe :waymark/ref]]
            [:consent_signed_on {:optional true} [:maybe [:string {:max 10}]]]
            [:privacy_signed_on {:optional true} [:maybe [:string {:max 10}]]]
            [:insurer {:optional true} [:maybe [:string {:max 120}]]]
            [:insurance_member_no {:optional true} [:maybe [:string {:max 60}]]]
            [:coverage {:optional true} [:maybe [:enum "verified" "self_pay"]]]
            [:care_team {:optional true} [:maybe [:string {:max 240}]]]
            [:no_shows {:optional true} [:maybe [:int {:min 0}]]]
            [:last_visit_on {:optional true} [:maybe [:string {:max 10}]]]]
   :filterable {:state #{:eq :in} :coverage #{:eq}}
   :default-filters {:state "intake"}
   :decided {:intake "A patient may wait at intake for a week while the front desk verifies coverage; past that, the mayor tells the owner."
             :active "A patient stays active while a plan of care runs; the mayor tells the owner of a chart with no visit in 60 days."
             :discharged "A discharged chart is kept seven years after the last visit, then purged."}
   :expected-age {:intake 604800 :active 5184000 :discharged 220898664}
   :actions
   {:admit     {:from #{:intake} :to :active
                :guards [has-referral consent-signed privacy-signed coverage-settled]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "An admitted patient stays on the books until discharged."}
                :decided "The front desk admits a patient on its own once the consent form and privacy notice are signed and coverage is verified or the patient is self-pay, because the front desk verifies coverage with the insurer before the first visit."}
    :discharge {:from #{:active} :to :discharged
                :guards [has-last-visit]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "A discharged patient returns with a new referral."}
                :decided "The treating therapist discharges a patient on their own when the plan of care has ended."}
    :purge     {:from #{:discharged} :to :purged
                :guards [has-last-visit owner-purges]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "A purged chart is gone for good."}}}})
