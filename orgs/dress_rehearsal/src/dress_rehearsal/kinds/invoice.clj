(ns dress_rehearsal.kinds.invoice
  "An invoice: the balance of one complete job (the price less the 30%
  deposit), due in 15 days. A reminder goes out at 7 days late.
  Payments arrive through the online processor and mark the invoice
  paid by themselves."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-amount
  (refuse "An invoice is paid against an amount; say what was billed.")
  '(and (present? :balance_cents) (present? :due_on)))

(defguard has-payment
  (refuse "An invoice is marked paid by the processor's payment; record its reference.")
  '(present? :payment_ref))

(defguard reminder-sent
  (refuse "A reminder goes out at 7 days late; record the day it went.")
  '(present? :reminded_on))

(defresource invoice
  {:kind :invoice
   :plural "invoices"
   :states [:open :reminded :paid :written_off]
   :initial :open
   :terminal #{:paid :written_off}
   :summary "{data.customer} · {data.due_on} · {state}"
   :schema [:map
            [:customer [:string {:min 1 :max 120}]]
            [:job_id {:optional true :kind :job} [:maybe :waymark/ref]]
            [:balance_cents {:optional true} [:maybe [:int {:min 0}]]]
            [:due_on {:optional true} [:maybe [:string {:max 10}]]]
            [:reminded_on {:optional true} [:maybe [:string {:max 10}]]]
            [:payment_ref {:optional true} [:maybe [:string {:max 120}]]]]
   :filterable {:state #{:eq :in} :customer #{:eq} :job_id #{:eq}}
   :decided {:open "An invoice is due in 15 days; at 7 days late the mayor sends the reminder."
             :reminded "After the reminder, an invoice 30 days late is shown to the owner."}
   :actions
   {:remind    {:from #{:open} :to :reminded
                :guards [reminder-sent]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "The reminder is with the customer."}
                :decided "A reminder goes out at 7 days late; the mayor sends it on its own."}
    :pay       {:from #{:open :reminded} :to :paid
                :guards [has-amount has-payment]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "A paid invoice stays paid."}
                :decided "Payments arrive through our online processor and should mark the invoice paid by themselves."}
    :write_off {:from #{:open :reminded} :to :written_off
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "A written-off invoice is let go; bill again with a new one."}
                :decided "The mayor writes off an invoice only when the owner says so, and says why."}}})
