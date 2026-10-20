(ns dress_rehearsal.kinds.contract
  "A maintenance contract: recurring care of one customer's grounds,
  every 2 weeks from April through October, skipping company holidays.
  Each visit is generated from the contract."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-season
  (refuse "A contract runs for a season; give its first and last day and its crew.")
  '(and (present? :starts_on) (present? :ends_on) (present? :crew)))

(defguard has-signed
  (refuse "A contract starts once the customer has signed it; record the day.")
  '(present? :signed_on))

(defresource contract
  {:kind :contract
   :plural "contracts"
   :states [:draft :active :ended :cancelled]
   :initial :draft
   :terminal #{:ended :cancelled}
   :summary "{data.customer} · every {data.every_days} days · {state}"
   :schema [:map
            [:customer [:string {:min 1 :max 120}]]
            [:crew {:optional true} [:maybe [:enum "crew_1" "crew_2" "crew_3"]]]
            [:every_days {:optional true} [:maybe [:int {:min 1 :max 365}]]]
            [:starts_on {:optional true} [:maybe [:string {:max 10}]]]
            [:ends_on {:optional true} [:maybe [:string {:max 10}]]]
            [:signed_on {:optional true} [:maybe [:string {:max 10}]]]
            [:visit_price_cents {:optional true} [:maybe [:int {:min 0}]]]
            [:holidays {:optional true :x-display {:widget "prose"}} [:maybe [:string {:max 1000}]]]]
   :filterable {:state #{:eq :in} :customer #{:eq} :crew #{:eq}}
   :decided {:draft "A contract is signed within 2 weeks of being written; past that, the mayor tells the office manager."
             :active "While a contract is active, a visit is generated every 2 weeks from April through October, skipping company holidays."}
   :actions
   {:activate {:from #{:draft} :to :active
               :guards [has-season has-signed]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "An active contract generates its visits; end or cancel it to stop them."}
               :decided "The office manager activates a signed contract; the mayor may on its own once it is signed."}
    :end      {:from #{:active} :to :ended
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "An ended contract generates no more visits; renew it with a new contract."}
               :decided "The mayor ends a contract on its own after its last October visit."}
    :cancel   {:from #{:draft :active} :to :cancelled
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A cancelled contract generates no more visits."}
               :decided "The mayor cancels a contract on its own when the customer ends it, and says why."}}})
