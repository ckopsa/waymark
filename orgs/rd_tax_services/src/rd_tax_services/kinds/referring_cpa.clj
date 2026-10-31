(ns rd_tax_services.kinds.referring-cpa
  "A referring CPA on file: the clients they sent us (prospects and
  studies name them by id), any referral fee we owe them, and they are
  the ones who receive each finished study."
  (:require [waymark10.dsl :refer [defresource]]))

(defresource referring-cpa
  {:kind :referring_cpa
   :plural "referring_cpas"
   :states [:active :inactive]
   :initial :active
   :terminal #{:inactive}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 120}]]
            [:firm {:optional true} [:maybe [:string {:max 120}]]]
            [:email {:optional true} [:maybe [:string {:max 200}]]]
            [:referral_fee_owed_usd {:optional true} [:maybe [:int {:min 0}]]]]
   :filterable {:state #{:eq :in}}
   :actions
   {:retire {:from #{:active} :to :inactive
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "An inactive CPA stays on file for the studies that name them."}}}})
