(ns clinic_rehearsal.kinds.claim
  "A claim: after each visit we submit a claim to the insurer, track
  what they pay, and bill the patient's copay or balance. A claim the
  insurer denies goes to the billing specialist within 2 business days.
  Submitting is an effect on the insurer integration."
  (:require [waymark10.dsl :refer [defguard defresource defscenario refuse warn]]))

(defguard has-visit
  (refuse "A claim is for one signed visit; name it first.")
  '(present? :visit_id))
(defguard has-amount
  (refuse "A claim says what it bills the insurer, in cents.")
  '(present? :billed_cents))
(defguard has-payment
  (refuse "A paid claim says what the insurer paid, in cents.")
  '(is-set (input :paid_cents)))
(defguard has-denial-reason
  (refuse "A denied claim says why the insurer denied it.")
  '(is-set (input :denial_reason)))
(defguard has-submitted-on
  (refuse "A submitted claim says the day it went to the insurer.")
  '(is-set (input :submitted_on)))
(defguard submitted-on-not-future
  (refuse "The day a claim went to the insurer cannot be later than today.")
  '(<= (input :submitted_on) (today)))
(defguard has-patient-owes
  (refuse "A patient's bill says what they owe after the insurer paid, in cents.")
  '(is-set (input :patient_owes_cents)))
;; advisory: a late payment is still posted; the :submitted age tells the specialist
(defguard paid-within-net-30
  (warn "The insurer paid this claim more than 30 days after it was submitted.")
  '(<= (today) (+ (data :submitted_on) (days 30))))

(defscenario submit-with-day
  "The billing specialist submits a claim by putting on it the day it went to the insurer."
  {:kind :claim :attempt :submit :at "2026-10-22T16:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :draft :data {:visit_id "visit-1" :insurer "Acme Health" :billed_cents 18000}}
   :input {:submitted_on "2026-10-22"}
   :expect {:allowed true}})
(defscenario submit-without-day
  "A claim submitted without the day it went to the insurer is refused."
  {:kind :claim :attempt :submit :at "2026-10-22T16:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :draft :data {:visit_id "visit-1" :insurer "Acme Health" :billed_cents 18000}}
   :input {}
   :expect {:refused :has-submitted-on}})
(defscenario post-payment-without-amount
  "A payment posted without what the insurer paid is refused."
  {:kind :claim :attempt :post_payment :at "2026-10-21T16:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :submitted :data {:insurer "Blue Plains" :billed_cents 18000 :submitted_on "2026-10-01"}}
   :input {}
   :expect {:refused :has-payment}})
(defscenario deny-with-reason
  "The billing specialist records a denial by putting the insurer's reason on the claim."
  {:kind :claim :attempt :deny :at "2026-10-21T16:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :submitted :data {:insurer "Blue Plains" :billed_cents 18000 :submitted_on "2026-10-01"}}
   :input {:denial_reason "CO-197: precertification absent."}
   :expect {:allowed true}})
(defscenario deny-without-reason
  "A denial recorded without the insurer's reason is refused."
  {:kind :claim :attempt :deny :at "2026-10-21T16:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :submitted :data {:insurer "Blue Plains" :billed_cents 18000 :submitted_on "2026-10-01"}}
   :input {}
   :expect {:refused :has-denial-reason}})
(defscenario bill-patient-with-balance
  "The billing specialist bills the patient by putting on the claim the copay or balance they owe."
  {:kind :claim :attempt :bill_patient :at "2026-10-21T16:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :paid :data {:insurer "Acme Health" :billed_cents 18000 :paid_cents 14400 :submitted_on "2026-10-09"}}
   :input {:patient_owes_cents 3600}
   :expect {:allowed true}})
(defscenario claim-inside-net-30
  "A payment posted 20 days after the claim was submitted is inside net-30."
  {:kind :claim :attempt :post_payment :at "2026-10-21T16:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :submitted :data {:insurer "Blue Plains" :billed_cents 18000 :submitted_on "2026-10-01"}}
   :input {:paid_cents 14000}
   :expect {:allowed true}})
(defscenario claim-past-net-30
  "A payment posted 35 days after the claim was submitted is past net-30."
  {:kind :claim :attempt :post_payment :at "2026-10-21T16:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :submitted :data {:insurer "Blue Plains" :billed_cents 18000 :submitted_on "2026-09-16"}}
   :input {:paid_cents 14000}
   :expect {:refused :paid-within-net-30}})
(defscenario remittance-records-what-was-paid
  "The insurer's remittance puts on the claim what it paid, with nobody retyping it, and the claim moves to paid."
  {:kind :claim :attempt :post_payment :at "2026-10-21T16:00:00Z" :calendar {:zone "America/Denver"}
   :row {:state :submitted :data {:visit_id "visit-1" :insurer "acme" :billed_cents 18000 :submitted_on "2026-10-09"}}
   :input {:paid_cents 14400} :expect {:state "paid"}})

(defresource claim
  {:kind :claim
   :plural "claims"
   :states [:draft :submitted :paid :denied :billed :written_off]
   :initial :draft
   :terminal #{:billed :written_off}
   :summary "{data.insurer} · {data.billed_cents}¢ · {state}"
   :schema [:map
            [:visit_id {:optional true :kind :visit} [:maybe :waymark/ref]]
            [:insurer {:optional true :kind :member :role "insurer"} [:maybe :waymark/ref]]
            [:billed_cents {:optional true} [:maybe [:int {:min 0}]]]
            [:submitted_on {:optional true} [:maybe :waymark/date]]
            [:paid_cents {:optional true} [:maybe [:int {:min 0}]]]
            [:patient_owes_cents {:optional true} [:maybe [:int {:min 0}]]]
            [:denial_reason {:optional true} [:maybe [:string {:max 500}]]]]
   :filterable {:state #{:eq :in} :insurer #{:eq}}
   :decided {:draft "A claim is submitted the day after its visit; past that, the mayor tells the owner."
             :submitted "An insurer pays a claim within 30 days on average; past 30 days, the mayor tells the billing specialist."
             :paid "A paid claim's copay or balance is billed to the patient within a week."
             :denied "A denied claim goes to the billing specialist within 2 business days."}
   :expected-age {:draft 86400
                  :submitted {:until '(+ (data :submitted_on) (days 30))}
                  :paid 604800 :denied 172800}
   :actions
   {:submit      {:from #{:draft} :to :submitted
                  :input [:map [:submitted_on :waymark/date]]
                  :guards [has-visit has-amount has-submitted-on submitted-on-not-future]
                  :effect {:power "insurer.submit_claim" :why "The claim goes to the insurer." :idempotency-key '(data :visit_id)
                           :input {:visit_id '(data :visit_id) :billed_cents '(data :billed_cents)} :lands :submitted :fails-to :draft}
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A submitted claim is the insurer's to pay or deny."}
                  :decided "The billing specialist submits a claim on their own after each visit, because after each visit we submit a claim to the insurer."}
    :post_payment {:from #{:submitted} :to :paid
                  :input [:map [:paid_cents [:int {:min 0}]]]
                  :guards [has-payment paid-within-net-30]
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A posted payment stays posted."}
                  :decided "The billing specialist posts what the insurer paid on their own, because we track what they pay."}
    :deny        {:from #{:submitted} :to :denied
                  :input [:map [:denial_reason [:string {:min 1 :max 240}]]]
                  :guards [has-denial-reason]
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A denied claim goes to the billing specialist."}
                  :decided "The billing specialist records an insurer's denial on their own when it arrives."}
    :resubmit    {:from #{:denied} :to :submitted
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A resubmitted claim is the insurer's again."}
                  :decided "The billing specialist corrects and resubmits a denied claim on their own within 2 business days."}
    :write_off   {:from #{:denied} :to :written_off
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A written-off claim is closed for good."}
                  :decided "The billing specialist writes off a denied claim on their own when it cannot be resubmitted, and bills the patient instead."}
    :bill_patient {:from #{:paid} :to :billed
                  :input [:map [:patient_owes_cents [:int {:min 0}]]]
                  :guards [has-patient-owes]
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A billed claim is closed; the patient's balance is theirs to pay."}
                  :decided "The billing specialist bills the patient's copay or balance on their own once the insurer has paid."}}})
