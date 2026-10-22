(ns clinic_integration.org
  "clinic_integration: docs/orgkit.md § Integrations' example whole. A
  claim's submit is an effect calling the insurer; the insurer's
  remittance and denial events walk post_payment and deny, paid_cents
  from the body; its coverage answer walks the patient's
  answer_coverage."
  (:require [waymark10.dsl :refer [defresource in-domain]]
            [waymark10.resource :as r]))

(defresource claim
  {:kind :claim
   :plural "claims"
   :states [:draft :submitting :submitted :paid :denied]
   :initial :draft
   :terminal #{:paid :denied}
   :summary "{data.patient_name} · {state}"
   :schema [:map
            [:patient_name [:string {:min 1 :max 80}]]
            [:billed [:waymark/money {:currency "USD"}]]
            ;; the insurer's claim number, written by submit's answer
            [:claim_ref {:optional true} [:maybe [:string {:max 120}]]]
            [:paid_cents {:optional true} [:maybe [:int {:min 0}]]]
            [:denial_reason {:optional true} [:maybe [:string {:max 480}]]]]
   :filterable {:state #{:eq :in} :claim_ref #{:eq}}
   :decided {:draft "A coded claim may wait a day to be submitted; past that, the mayor tells the owner."
             :submitting "A claim may wait an hour for the insurer to take it; past that, the mayor tells the owner."
             :submitted "A submitted claim may wait thirty days for the insurer's answer; past that, the mayor tells the owner."}
   :expected-age {:draft 86400 :submitting 3600 :submitted 2592000}
   :left-by [{:in #{:submitting} :by :engine :via :submit_landed :to :submitted}
             {:in #{:submitted} :by :engine :via :post_payment :to :paid}
             {:in #{:submitted} :by :engine :via :deny :to :denied}]
   :actions
   {:submit {:from #{:draft} :to :submitting
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A claim sent is with the insurer; a corrected one is a new claim."}
             :decided "The mayor submits a claim on its own once the visit is coded."
             :effect {:power "insurer__submit_claim"
                      :input {:amount '(data :billed)
                              :patient '(data :patient_name)
                              :reference '(self)}
                      :why "Submit the claim for {patient_name}."
                      :idempotency-key '(str "claim:" (self))
                      :writes {:claim_ref '(result "claim_id")}
                      :lands :submitted
                      :fails-to :draft}}
    :post_payment {:from #{:submitted} :to :paid
                   :input [:map [:paid_cents [:int {:min 0}]]]
                   :handler (r/field-writer [:paid_cents])
                   :safety {:idempotent true :reversible false :confirm false
                            :one-way "A paid claim stays paid; a refund is the insurer's."}}
    :deny {:from #{:submitted} :to :denied
           :input [:map [:denial_reason [:string {:min 1 :max 480}]]]
           :handler (r/field-writer [:denial_reason])
           :safety {:idempotent true :reversible false :confirm false
                    :one-way "A denied claim stays denied; an appeal is a new claim."}}}
   :on-event [{:source :insurer :type "remittance.paid"
               :match {:claim_ref '(event "claim_id")}
               :door :post_payment
               :input {:paid_cents '(event "paid_cents")}
               :tell ["clinic_integration-mayor"]}
              {:source :insurer :type "claim.denied"
               :match {:claim_ref '(event "claim_id")}
               :door :deny
               :input {:denial_reason '(event "reason")}
               :tell ["clinic_integration-mayor"]}]})

(defresource patient
  {:kind :patient
   :plural "patients"
   :states [:checking :answered]
   :initial :checking
   :terminal #{:answered}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 80}]]
            ;; the member number the insurer knows the patient by
            [:member_ref [:string {:min 1 :max 120}]]
            [:covered {:optional true} [:maybe :boolean]]]
   :filterable {:state #{:eq :in} :member_ref #{:eq}}
   :decided {:checking "A patient's coverage may wait two days for the insurer's answer; past that, the mayor tells the owner."}
   :expected-age {:checking 172800}
   :left-by [{:in #{:checking} :by :engine :via :answer_coverage :to :answered}]
   :actions
   {:answer_coverage {:from #{:checking} :to :answered
                      :input [:map [:covered :boolean]]
                      :handler (r/field-writer [:covered])
                      :safety {:idempotent true :reversible false :confirm false
                               :one-way "The insurer's answer stands until it sends another."}}}
   :on-event [{:source :insurer :type "coverage.answered"
               :match {:member_ref '(event "member_id")}
               :door :answer_coverage
               :input {:covered '(event "covered")}
               :tell ["clinic_integration-mayor"]}]})

(defn resources [] (in-domain :clinic_integration [claim patient]))
