(ns shop_rehearsal.kinds.invoice
  "An invoice: what a cafe owes for a wholesale order, due net-30. An
  invoice more than 15 days past due stops the cafe's new wholesale
  orders until it is paid."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard names-account
  (refuse "An invoice is billed to one wholesale account; name it first.")
  '(present? :account_id))

(defguard payment-recorded
  (refuse "Record the cafe's payment reference before the invoice is paid.")
  '(present? :payment_ref))

(defresource invoice
  {:kind :invoice
   :plural "invoices"
   :states [:issued :overdue :paid :void]
   :initial :issued
   :terminal #{:paid :void}
   :summary "{data.number} · {data.amount_cents}¢ · due {data.due_on} · {state}"
   :schema [:map
            [:number [:string {:min 1 :max 40}]]
            [:account_id {:optional true :kind :wholesale_account} [:maybe :waymark/ref]]
            [:wholesale_order_id {:optional true :kind :wholesale_order} [:maybe :waymark/ref]]
            [:amount_cents [:int {:min 0}]]
            [:tax_cents [:int {:min 0}]]
            [:issued_on [:string {:min 10 :max 10}]]
            ;; typed by hand as issued_on + 30 days (concept 2)
            [:due_on [:string {:min 10 :max 10}]]
            [:payment_ref {:optional true} [:maybe [:string {:max 120}]]]]
   :filterable {:state #{:eq :in} :account_id #{:eq}}
   :decided {:issued "An issued invoice is due 30 days after it is issued; the day after it is due, the mayor marks it overdue."
             :overdue "An overdue invoice is chased every week; more than 15 days past due, the cafe gets no new wholesale order until it pays."}
   :expected-age {:issued 2592000 :overdue 604800}
   :actions
   {:pay      {:from #{:issued :overdue} :to :paid
               :guards [names-account payment-recorded]
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A paid invoice is settled."}
               :decided "The mayor marks an invoice paid on its own when the cafe's payment arrives."}
    :mark_overdue {:from #{:issued} :to :overdue
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "An overdue invoice stays overdue until it is paid."}
               :decided "The mayor marks an invoice overdue on its own the day after its due date."}
    :void     {:from #{:issued} :to :void
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "A void invoice is owed by nobody; issue a new one."}
               :decided "The mayor voids an invoice on its own when its wholesale order is declined, and says why."}}})
