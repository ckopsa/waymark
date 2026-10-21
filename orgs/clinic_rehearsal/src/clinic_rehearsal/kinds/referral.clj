(ns clinic_rehearsal.kinds.referral
  "A referral: a physician sends a patient's prescription. The front
  desk accepts it, which is when a chart is opened and a first visit may
  be booked, or declines it; a prescription older than 90 days expires."
  (:require [waymark10.dsl :refer [defguard defresource defscenario refuse]]))

(defguard has-prescription
  (refuse "We cannot book a first visit until we have the prescription.")
  '(present? :prescription))

(defguard has-signed-on
  (refuse "A prescription is accepted once the day the physician signed it is on it.")
  '(present? :signed_on))

(defguard prescription-is-live
  (refuse "A prescription expires 90 days after the physician signed it; the physician sends a new one.")
  '(< (today) (+ (data :signed_on) (days 90))))

(defscenario accept-live-prescription
  "A prescription signed 30 days ago is accepted."
  {:kind :referral :attempt :accept
   :at "2026-10-21T16:00:00Z"
   :calendar {:zone "America/Denver"}
   :row {:state :received
         :data {:patient_name "Ana Ruiz" :physician "Dr. Lee"
                :prescription "PT 2x/week for 6 weeks, low back."
                :signed_on "2026-09-21"}}
   :expect {:allowed true}})

(defscenario accept-expired-prescription
  "A prescription signed 91 days ago has expired and is not accepted."
  {:kind :referral :attempt :accept
   :at "2026-10-21T16:00:00Z"
   :calendar {:zone "America/Denver"}
   :row {:state :received
         :data {:patient_name "Ana Ruiz" :physician "Dr. Lee"
                :prescription "PT 2x/week for 6 weeks, low back."
                :signed_on "2026-07-22"}}
   :expect {:refused :prescription-is-live}})

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
              :guards [has-prescription has-signed-on prescription-is-live]
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
