(ns dress_rehearsal.kinds.invoice
  "An invoice: the fee for one delivered study, a share of the credit
  with a fixed minimum, due in 30 days. It names its study by id."
  (:require [waymark10.dsl :refer [defguard defguardfn defresource refuse]]
            [waymark10.holds :as holds]
            [waymark10.types :as t]))

(defguard has-amount
  (refuse "An invoice is paid against an amount; say what was billed.")
  '(present? :amount_usd))

(defguardfn principal-writes-off
  {:reads [:principal :within]
   :hold true
   :explain "An invoice is written off only on The Principal's tap; the AI never writes one off on its own."
   :open "No door clears this one. The call waits as a held call for The Principal's tap."}
  [row _inp ctx]
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :invoice :write_off (:id row)) (t/allow)
    :else (t/deny)))

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
