(ns rd_tax_services.kinds.invoice
  "An invoice: the fee for one delivered study, a percentage of the
  credit with a fixed minimum (an assumption the principal confirms),
  due 30 days after delivery. Past due it gets a reminder at 15 days
  late and the principal calls at 30."
  (:require [waymark10.dsl :refer [defresource]]))

(defresource invoice
  {:kind :invoice
   :plural "invoices"
   :states [:open :paid :written_off]
   :initial :open
   :terminal #{:paid :written_off}
   :summary "{data.client} ${data.amount_usd} · {state}"
   :schema [:map
            [:client [:string {:min 1 :max 120}]]
            [:study_id {:optional true :kind :study} [:maybe :waymark/ref]]
            [:amount_usd [:int {:min 0}]]
            [:due_on {:optional true} [:maybe [:string {:max 10}]]]
            [:reminder_sent_on {:optional true} [:maybe [:string {:max 10}]]]
            [:principal_called_on {:optional true} [:maybe [:string {:max 10}]]]]
   :filterable {:state #{:eq :in} :client #{:eq} :study_id #{:eq}}
   :actions
   {:mark_paid {:from #{:open} :to :paid
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "A paid invoice stays paid."}}
    :write_off {:from #{:open} :to :written_off
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "A written-off invoice is not chased again."}}}})
