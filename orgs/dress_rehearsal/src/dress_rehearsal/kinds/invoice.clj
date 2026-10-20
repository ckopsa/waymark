(ns dress_rehearsal.kinds.invoice
  "An invoice: the fee for one delivered study, a share of the credit
  with a fixed minimum, due in 30 days. It names its study by id."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-amount
  (refuse "An invoice is paid against an amount; say what was billed.")
  '(present? :amount_usd))

(defguard principal-writes-off
  (refuse "An invoice is written off only on The Principal's tap; the AI never writes one off on its own, so this waits for The Principal." {:hold true})
  '(= 1 0))

(defresource invoice
  {:kind :invoice
   :plural "invoices"
   :states [:open :paid :written_off]
   :initial :open
   :terminal #{:paid :written_off}
   :summary "{data.client} · {state}"
   :schema [:map
            [:client [:string {:min 1 :max 120}]]
            [:study_id {:optional true :kind :study} [:maybe :waymark/ref]]
            [:amount_usd {:optional true} [:maybe [:int {:min 0}]]]
            [:due_on {:optional true} [:maybe [:string {:max 10}]]]]
   :filterable {:state #{:eq :in} :client #{:eq} :study_id #{:eq}}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:open "An invoice may stay open for 45 days: it is due in 30, a reminder goes at 15 days late, and past 45 days the mayor tells The Principal."}
   :actions
   {:pay       {:from #{:open} :to :paid
                :guards [has-amount]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "A paid invoice stays paid."}
                :decided "The AI records an invoice paid on its own when the payment arrives."}
    :write_off {:from #{:open} :to :written_off
                :guards [principal-writes-off]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "A written-off invoice is let go; bill again with a new one."}}}})
