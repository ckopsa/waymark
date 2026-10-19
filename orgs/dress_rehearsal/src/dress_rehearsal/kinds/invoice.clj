(ns dress_rehearsal.kinds.invoice
  "An invoice: the fee for one delivered study, due in 30 days. Past
  due it gets a reminder at 15 days late and the principal's call at 30."
  (:require [waymark10.dsl :refer [defresource]]))

(defresource invoice
  {:kind :invoice
   :plural "invoices"
   :states [:sent :overdue :reminded :called :paid :written_off]
   :initial :sent
   :terminal #{:paid :written_off}
   :summary "{data.client} ${data.amount_usd} · {state}"
   :schema [:map
            [:client [:string {:min 1 :max 120}]]
            [:study_id {:optional true :kind :study} [:maybe :waymark/ref]]
            [:amount_usd [:int {:min 0}]]
            [:due {:optional true} [:maybe [:string {:max 10}]]]]
   :filterable {:state #{:eq :in} :study_id #{:eq}}
   :actions
   {:fall_due {:from #{:sent} :to :overdue
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "The invoice is past its due date."}}
    :remind   {:from #{:overdue} :to :reminded
               :safety {:idempotent false :reversible false :confirm false
                        :one-way "The client has been sent a reminder."}}
    :call     {:from #{:reminded} :to :called
               :safety {:idempotent false :reversible false :confirm false
                        :one-way "The principal has called the client."}}
    :pay      {:from #{:sent :overdue :reminded :called} :to :paid
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A paid invoice stays paid."}}
    :write_off {:from #{:overdue :reminded :called} :to :written_off
                :safety {:idempotent true :reversible false :confirm true
                         :consequence "The fee is no longer chased."
                         :one-way "A written-off invoice is closed; bill again with a new one."}}}})
