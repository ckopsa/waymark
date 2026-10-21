(ns dress_rehearsal.kinds.invoice
  "An invoice: the fee for one delivered study, a share of the credit
  with a fixed minimum, due in 30 days. It names its study by id."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-amount
  (refuse "An invoice is paid against an amount; say what was billed.")
  '(present? :amount_usd))

(defresource invoice
  {:kind :invoice
   :plural "invoices"
   :states [:open :paid :written_off]
   :initial :open
   :terminal #{:paid :written_off}
   :summary "{data.client} · {state}"
   ;; a film's day reads the row as it stood: its fields, not only its state
   :retain {:data true}
   :schema [:map
            [:client [:string {:min 1 :max 120}]]
            [:study_id {:optional true :kind :study} [:maybe :waymark/ref]]
            [:amount_usd {:optional true} [:maybe [:int {:min 0}]]]
            [:due_on {:optional true} [:maybe [:string {:max 10}]]]]
   :filterable {:state #{:eq :in} :client #{:eq} :study_id #{:eq}}
   :actions
   {:pay       {:from #{:open} :to :paid
                :guards [has-amount]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "A paid invoice stays paid."}}
    :write_off {:from #{:open} :to :written_off
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "A written-off invoice is let go; bill again with a new one."}}}})
