(ns shop_rehearsal.kinds.invoice
  "What a cafe owes for a wholesale order, due net-30."
  (:require [waymark10.dsl :refer [defguard defresource defscenario refuse]]))

(defguard names-account
  (refuse "An invoice is billed to one wholesale account; name it first.")
  '(present? :account_id))
(defguard payment-recorded
  (refuse "Record the cafe's payment reference before the invoice is paid.")
  '(is-set (input :payment_ref)))
(defguard past-net-30
  (refuse "An invoice is not past due until 30 days after it was issued.")
  '(> (today) (+ (data :issued_on) (days 30))))
(defn- one-way [s] {:idempotent true :reversible false :confirm false :one-way s})

(defresource invoice
  {:kind :invoice
   :plural "invoices"
   :states [:issued :overdue :paid :void]
   :initial :issued
   :terminal #{:paid :void}
   :summary "{data.number} · {data.amount_cents}¢ · issued {data.issued_on} · {state}"
   :schema [:map [:number [:string {:min 1 :max 40}]]
            [:account_id {:optional true :kind :wholesale_account} [:maybe :waymark/ref]]
            [:wholesale_order_id {:optional true :kind :wholesale_order} [:maybe :waymark/ref]]
            [:amount_cents [:int {:min 0}]] [:tax_cents [:int {:min 0}]]
            ;; due 30 days later: the law reads it, nobody types it
            [:issued_on :waymark/date] [:payment_ref {:optional true} [:maybe [:string {:max 120}]]]]
   :filterable {:state #{:eq :in} :account_id #{:eq}}
   :decided {:issued "An issued invoice is due 30 days after it is issued; the day after it is due, the mayor marks it overdue."
             :overdue "An overdue invoice is chased every week; more than 15 days past due, the cafe gets no new wholesale order until it pays."}
   :expected-age {:issued {:until '(+ (data :issued_on) (days 30))} :overdue 604800}
   :actions
   {:pay      {:from #{:issued :overdue} :to :paid :input [:map [:payment_ref [:string {:min 1 :max 120}]]]
               :guards [names-account payment-recorded] :safety (one-way "A paid invoice is settled.")
               :decided "The mayor marks an invoice paid on its own when the cafe's payment arrives."}
    :mark_overdue {:from #{:issued} :to :overdue :guards [past-net-30]
               :safety (one-way "An overdue invoice stays overdue until it is paid.")
               :decided "The mayor marks an invoice overdue on its own the day after its due date."}
    :void     {:from #{:issued} :to :void :safety (one-way "A void invoice is owed by nobody; issue a new one.")
               :decided "The mayor voids an invoice on its own when its wholesale order is declined, and says why."}}})

(defscenario overdue-after-net-30 "An invoice issued on 1 September is past due on 2 October."
  {:kind :invoice :attempt :mark_overdue :at "2026-10-02T18:00:00Z" :calendar {:zone "America/Denver"} :row {:state :issued :data {:number "W-1001" :amount_cents 48000 :tax_cents 0 :issued_on "2026-09-01"}} :expect {:allowed true}})
(defscenario not-overdue-within-net-30 "An invoice issued on 1 September is not past due on 1 October."
  {:kind :invoice :attempt :mark_overdue :at "2026-10-01T18:00:00Z" :calendar {:zone "America/Denver"} :row {:state :issued :data {:number "W-1001" :amount_cents 48000 :tax_cents 0 :issued_on "2026-09-01"}} :expect {:refused :past-net-30}})
