(ns clinic_rehearsal.kinds.claim
  "A claim: after each visit we submit a claim to the insurer, track
  what they pay, and bill the patient's copay or balance. A claim the
  insurer denies goes to the billing specialist within 2 business days."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-visit
  (refuse "A claim is for one signed visit; name it first.")
  '(present? :visit_id))

(defguard has-amount
  (refuse "A claim says what it bills the insurer, in cents.")
  '(present? :billed_cents))

(defguard has-payment
  (refuse "A paid claim says what the insurer paid, in cents.")
  '(present? :paid_cents))

(defguard has-denial-reason
  (refuse "A denied claim says why the insurer denied it.")
  '(present? :denial_reason))

(defresource claim
  {:kind :claim
   :plural "claims"
   :states [:draft :submitted :paid :denied :billed :written_off]
   :initial :draft
   :terminal #{:billed :written_off}
   :summary "{data.insurer} · {data.billed_cents}¢ · {state}"
   :schema [:map
            [:visit_id {:optional true :kind :visit} [:maybe :waymark/ref]]
            [:insurer {:optional true} [:maybe [:string {:max 120}]]]
            [:billed_cents {:optional true} [:maybe [:int {:min 0}]]]
            [:paid_cents {:optional true} [:maybe [:int {:min 0}]]]
            [:patient_owes_cents {:optional true} [:maybe [:int {:min 0}]]]
            [:denial_reason {:optional true} [:maybe [:string {:max 500}]]]]
   :filterable {:state #{:eq :in} :insurer #{:eq}}
   :decided {:draft "A claim is submitted the day after its visit; past that, the mayor tells the owner."
             :submitted "An insurer pays a claim within 30 days on average; past 30 days, the mayor tells the billing specialist."
             :paid "A paid claim's copay or balance is billed to the patient within a week."
             :denied "A denied claim goes to the billing specialist within 2 business days."}
   :expected-age {:draft 86400 :submitted 2592000 :paid 604800 :denied 172800}
   :actions
   {:submit      {:from #{:draft} :to :submitted
                  :guards [has-visit has-amount]
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A submitted claim is the insurer's to pay or deny."}
                  :decided "The billing specialist submits a claim on their own after each visit, because after each visit we submit a claim to the insurer."}
    :post_payment {:from #{:submitted} :to :paid
                  :guards [has-payment]
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A posted payment stays posted."}
                  :decided "The billing specialist posts what the insurer paid on their own, because we track what they pay."}
    :deny        {:from #{:submitted} :to :denied
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
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A billed claim is closed; the patient's balance is theirs to pay."}
                  :decided "The billing specialist bills the patient's copay or balance on their own once the insurer has paid."}}})
