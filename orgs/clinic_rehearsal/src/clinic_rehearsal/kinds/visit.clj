(ns clinic_rehearsal.kinds.visit
  "A visit: booked from a plan of care into the therapist's open slot,
  attended or missed, then noted and signed by the treating therapist
  within 24 hours. A note a physical therapist assistant writes is also
  co-signed by a licensed therapist, never by its own author."
  (:require [waymark10.dsl :refer [defguard defresource hold refuse]]))

(defguard has-plan
  (refuse "A visit is booked from a plan of care; name the plan first.")
  '(present? :plan_id))

(defguard has-slot
  (refuse "A visit is booked into the therapist's slot; name the therapist and the time.")
  '(and (present? :therapist) (present? :starts_at)))

(defguard has-note
  (refuse "Every visit note is written before it is signed.")
  '(present? :note))

(defguard signed-by-treating-therapist
  (refuse "Every visit note is signed by the treating therapist.")
  '(= :signed_by :therapist))

(defguard assistant-cosigned
  (refuse "A note written by a physical therapist assistant is also co-signed by a licensed therapist.")
  '(or (= :author_role "pt") (present? :cosigned_by)))

(defguard no-self-cosign
  (refuse "Nobody co-signs their own note.")
  '(not= :cosigned_by :signed_by))

(defguard therapist-signs
  (hold "A visit note is signed on the treating therapist's own tap."))

(defresource visit
  {:kind :visit
   :plural "visits"
   :states [:booked :attended :signed :no_show :cancelled]
   :initial :booked
   :terminal #{:signed :no_show :cancelled}
   :summary "{data.therapist} · {data.starts_at} · {state}"
   :schema [:map
            [:plan_id {:optional true :kind :plan_of_care} [:maybe :waymark/ref]]
            [:patient_id {:optional true :kind :patient} [:maybe :waymark/ref]]
            [:therapist {:optional true} [:maybe [:string {:max 80}]]]
            [:starts_at {:optional true} [:maybe :waymark/datetime]]
            [:author_role {:optional true} [:maybe [:enum "pt" "pta"]]]
            [:note {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 4000}]]]
            [:signed_by {:optional true} [:maybe [:string {:max 80}]]]
            [:cosigned_by {:optional true} [:maybe [:string {:max 80}]]]
            [:no_show_fee_cents {:optional true} [:maybe [:int {:min 0}]]]]
   :filterable {:state #{:eq :in} :therapist #{:eq} :plan_id #{:eq} :patient_id #{:eq}}
   :decided {:booked "A visit stays booked until its time; the mayor tells the owner of one a day past its time."
             :attended "An attended visit's note is signed within 24 hours; past that, the mayor tells the owner."}
   :expected-age {:booked 1209600 :attended 86400}
   :actions
   {:attend   {:from #{:booked} :to :attended
               :guards [has-plan has-slot]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "An attended visit is noted and signed from here."}
               :decided "The front desk checks a patient in on its own when they arrive for a booked visit."}
    :sign     {:from #{:attended} :to :signed
               :guards [has-note signed-by-treating-therapist assistant-cosigned no-self-cosign therapist-signs]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A signed note is the record; correct it with an addendum on a new visit."}}
    :no_show  {:from #{:booked} :to :no_show
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A no-show stays on the patient's record; a $40 fee is charged after the second."}
               :decided "The front desk marks a no-show on its own when the patient misses the visit, and charges the $40 fee after the second no-show."}
    :cancel   {:from #{:booked} :to :cancelled
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A cancelled visit frees its slot; book a new one."}
               :decided "The front desk cancels a visit on its own when the plan ends or lapses, or the patient asks."}}})
