(ns rd_tax_services.kinds.assumption
  "An assumption: something in the firm's setup that is a best guess
  rather than settled (the fee, the team, the tools, the reminder
  schedule). The open ones are shown to the principal when he first
  signs in; he confirms each, changing its value first if it is wrong."
  (:require [waymark10.dsl :refer [defresource]]))

(defresource assumption
  {:kind :assumption
   :plural "assumptions"
   :states [:open :confirmed]
   :initial :open
   :terminal #{:confirmed}
   :summary "{data.topic}: {data.value} · {state}"
   :schema [:map
            [:topic [:string {:min 1 :max 80}]]
            [:value [:string {:min 1 :max 400}]]]
   :filterable {:state #{:eq :in} :topic #{:eq}}
   :default-filters {:state "open"}
   :actions
   {:confirm {:from #{:open} :to :confirmed
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A confirmed assumption is settled; a later change is a new assumption."}}}})
