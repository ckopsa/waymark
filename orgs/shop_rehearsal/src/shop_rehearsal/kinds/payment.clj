(ns shop_rehearsal.kinds.payment
  "A payment: the processor's word that a checkout's money has cleared
  (or failed). It is applied to the order it pays."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard names-order
  (refuse "A payment is applied to one order; name the order first.")
  '(present? :order_id))

(defresource payment
  {:kind :payment
   :plural "payments"
   :states [:cleared :applied :failed]
   :initial :cleared
   :terminal #{:applied :failed}
   :summary "{data.processor_ref} · {data.amount_cents}¢ · {state}"
   :schema [:map
            [:processor_ref [:string {:min 1 :max 120}]]
            [:order_id {:optional true :kind :order} [:maybe :waymark/ref]]
            [:amount_cents [:int {:min 0}]]
            [:fee_cents [:int {:min 0}]]
            [:cleared_on {:optional true} [:maybe [:string {:max 10}]]]]
   :filterable {:state #{:eq :in} :order_id #{:eq} :processor_ref #{:eq}}
   :decided {:cleared "A cleared payment is applied to its order within the hour; past that, the mayor tells the owner."}
   :expected-age {:cleared 3600}
   :actions
   {:apply {:from #{:cleared} :to :applied
            :guards [names-order]
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "An applied payment has paid its order."}
            :decided "The mayor applies a cleared payment to its order on its own, and marks that order paid."}
    :fail  {:from #{:cleared} :to :failed
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A failed payment pays nothing; the customer pays again."}
            :decided "The mayor marks a payment failed on its own when the processor reverses it."}}})
