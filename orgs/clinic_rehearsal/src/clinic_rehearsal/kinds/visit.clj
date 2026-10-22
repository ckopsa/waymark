(ns clinic_rehearsal.kinds.visit
  "A visit: booked from a plan of care into the therapist's open slot,
  attended or missed, then noted and signed by the treating therapist
  within 24 hours. A note a physical therapist assistant writes is also
  co-signed by a licensed therapist, never by its own author."
  (:require [waymark10.dsl :refer [defguard defresource defscenario hold refuse warn]]
            [waymark10.resource :as r]))

(defguard has-plan
  (refuse "A visit is booked from a plan of care; name the plan first.")
  '(present? :plan_id))

(defguard has-therapist
  (refuse "A visit is booked into a therapist's slot; name the therapist.")
  '(present? :therapist))

(defguard has-time
  (refuse "A visit is booked into a slot; name its time.")
  '(present? :starts_at))

(defguard has-note
  (refuse "Every visit note is written before it is signed.")
  '(is-set (input :note)))

(defguard has-signer
  (refuse "Every visit note is signed by the treating therapist; name who signs.")
  '(is-set (input :signer)))

(defguard has-author-role
  (refuse "Say whether a physical therapist or an assistant wrote the note.")
  '(is-set (input :author_role)))

;; the treating therapist's own tap stands in for the co-sign a PTA's
;; note needs: no door here takes the co-signature itself
(defguard therapist-signs
  (hold "A visit note is signed on the treating therapist's own tap; a note an assistant wrote also waits for a licensed therapist, never its author, to co-sign."
        :by '(data :therapist)))

;; advisory: a late note is still signed, and says it is late; the
;; :until age on :attended is what tells the owner
(defguard note-in-time
  (warn "This note is signed more than 24 hours after the visit.")
  '(<= (now) (+ (data :starts_at) (hours 24))))

(defscenario sign-with-note
  "The treating therapist signs a visit by writing its note, naming who signs and whether a PT or an assistant wrote it."
  {:kind :visit :attempt :sign :at "2026-10-21T20:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :attended :data {:therapist "pt_a" :starts_at "2026-10-21T16:00:00Z"}}
   :input {:note "Left knee post-op, session 1: flexion to 95 degrees." :signer "pt_a" :author_role "pt"}
   :expect {:allowed true}})

(defscenario sign-without-note
  "A visit signed without its note is refused."
  {:kind :visit :attempt :sign :at "2026-10-21T20:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :attended :data {:therapist "pt_a" :starts_at "2026-10-21T16:00:00Z"}}
   :input {}
   :expect {:refused :has-note}})

(defscenario note-past-a-day
  "A note signed 26 hours after the visit is late."
  {:kind :visit :attempt :sign :at "2026-10-22T18:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :attended :data {:therapist "pt_b" :starts_at "2026-10-21T16:00:00Z"}}
   :input {:note "Low back, session 3: tolerated progression." :signer "pt_b" :author_role "pt"}
   :expect {:refused :note-in-time}})

(defscenario second-no-show-with-fee
  "The front desk marks a second no-show with its $40 fee."
  {:kind :visit :attempt :no_show :at "2026-10-29T16:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :booked :data {:therapist "pt_b" :starts_at "2026-10-28T14:00:00Z"}}
   :input {:no_show_fee_cents 4000}
   :expect {:allowed true}})

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
            [:therapist {:kind :member :role "pt"} :waymark/ref]
            [:starts_at :waymark/instant]
            [:author_role {:optional true} [:maybe [:enum "pt" "pta"]]]
            [:note {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 4000}]]]
            [:signer {:optional true :kind :member :role "clinician"} [:maybe :waymark/ref]]
            [:cosigner {:optional true :kind :member :role "pt"} [:maybe :waymark/ref]]
            [:no_show_fee_cents {:optional true} [:maybe [:int {:min 0}]]]]
   :filterable {:state #{:eq :in} :therapist #{:eq} :plan_id #{:eq} :patient_id #{:eq}
                :starts_at #{:eq :range}}
   ;; no therapist is double-booked: one live visit per therapist per slot
   :unique [[:therapist :starts_at] {:while #{:booked :attended}}]
   ;; a signed note is the record; a correction is an addendum
   :frozen-in [:signed]
   :owns [{:kind :note_addendum :via :visit_id}]
   :measures {:no_show
              {:label "Visits the patient missed"
               :stat :rate
               ;; the law reads no state, so a missed visit is one never signed
               :among "state=signed,no_show"
               :where '(not (is-set (data :signer)))
               :at :starts_at
               :over '(months 1)
               :tell "clinic_rehearsal-mayor"
               :default-target {:op "<=" :target 0.08M}}}
   :decided {:booked "A visit stays booked until its time; the mayor tells the owner of one a day past its time."
             :attended "An attended visit's note is signed within 24 hours; past that, the mayor tells the owner."}
   :expected-age {:booked 1209600
                  :attended {:until '(+ (data :starts_at) (hours 24))}}
   :actions
   {:attend   {:from #{:booked} :to :attended
               :guards [has-plan has-therapist has-time]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "An attended visit is noted and signed from here."}
               :decided "The front desk checks a patient in on its own when they arrive for a booked visit."}
    :sign     {:from #{:attended} :to :signed
               :input [:map
                       [:note {:x-display {:widget "prose"}} [:string {:min 1 :max 4000}]]
                       [:signer {:kind :member :role "clinician"} :waymark/ref]
                       [:author_role [:enum "pt" "pta"]]]
               :handler (r/field-writer [:note :signer :author_role])
               :guards [has-note has-signer has-author-role note-in-time therapist-signs]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A signed note is the record; correct it with an addendum."}}
    :no_show  {:from #{:booked} :to :no_show
               :input [:map [:no_show_fee_cents {:optional true} [:int {:min 0}]]]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A no-show stays on the patient's record; a $40 fee is charged after the second."}
               :decided "The front desk marks a no-show on its own when the patient misses the visit, and charges the $40 fee after the second no-show."}
    :cancel   {:from #{:booked} :to :cancelled
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A cancelled visit frees its slot; book a new one."}
               :decided "The front desk cancels a visit on its own when the plan ends or lapses, or the patient asks."}}})
