(ns clinic_rehearsal.kinds.referral
  "A referral: a physician sends a patient's prescription. The front
  desk accepts it, which is when a chart is opened and a first visit may
  be booked, or declines it; a prescription older than 90 days expires."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-prescription
  (refuse "We cannot book a first visit until we have the prescription.")
  '(present? :prescription))

(defguard has-signed-on
  (refuse "A prescription is accepted once the day the physician signed it is on it.")
  '(present? :signed_on))

(defresource referral
  {:kind :referral
   :plural "referrals"
   :states [:received :accepted :declined :expired]
   :initial :received
   :terminal #{:accepted :declined :expired}
   :summary "{data.patient_name} · {data.physician} · {state}"
   :schema [:map
            [:patient_name [:string {:min 1 :max 120}]]
            [:physician [:string {:min 1 :max 120}]]
            [:prescription {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]
            [:signed_on {:optional true} [:maybe :waymark/date]]]
   :filterable {:state #{:eq :in} :physician #{:eq}}
   :default-filters {:state "received"}
   :decided {:received "A referral may wait a day for the front desk; past that, the mayor tells the owner."}
   :expected-age {:received 86400}
   :actions
   {:accept  {:from #{:received} :to :accepted
              :guards [has-prescription has-signed-on]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "An accepted referral stays accepted; the patient's chart carries it from here."}
              :decided "The front desk accepts a referral on its own once the prescription is on it, because we cannot book a first visit until we have it."}
    :decline {:from #{:received} :to :declined
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A declined referral is closed; the physician sends a new one."}
              :decided "The front desk declines a referral on its own when it is not care this clinic gives, and says why."}
    :expire  {:from #{:received} :to :expired
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "An expired prescription stays expired; the physician sends a new one."}
              :decided "The front desk expires a referral on its own once its prescription is past 90 days, because it expires after 90 days."}}})
