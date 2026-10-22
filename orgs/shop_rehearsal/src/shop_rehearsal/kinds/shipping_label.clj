(ns shop_rehearsal.kinds.shipping-label
  "Postage bought from the carrier once per order: used when it ships, voided if refunded first."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard names-order
  (refuse "A label is bought for one order; name the order first.")
  '(present? :order_id))

(defresource shipping-label
  {:kind :shipping_label
   :plural "shipping_labels"
   :states [:bought :used :voided]
   :initial :bought
   :terminal #{:used :voided}
   :summary "{data.tracking} · {state}"
   :schema [:map
            [:order_id {:optional true :kind :order} [:maybe :waymark/ref]]
            [:tracking [:string {:min 1 :max 120}]]
            ;; the carrier is an outside party; the postage it sells is a supplier cost
            [:carrier {:optional true :kind :member :role "carrier"} [:maybe :waymark/ref]]
            [:postage :waymark/money]]
   :filterable {:state #{:eq :in} :order_id #{:eq}}
   ;; buying a label happens once per order: one live label at a time
   :unique [:order_id {:while #{:bought :used}}]
   :decided {:bought "A bought label is used within a day; past that, the mayor tells the owner."}
   :expected-age {:bought 86400}
   :actions
   {:use  {:from #{:bought} :to :used
           :guards [names-order]
           :posts '[{:move (data :postage) :from [:house :payables] :to [:house :supplier_costs]}]
           :safety {:idempotent true :reversible false :confirm false :one-way "A used label has gone with its parcel."}
           :decided "The mayor marks a label used on its own when the carrier scans the parcel."}
    :void {:from #{:bought} :to :voided
           :guards [names-order]
           :safety {:idempotent true :reversible false :confirm false :one-way "A voided label cannot ship; buy a new one if the order ships after all."}
           :decided "The mayor voids an unused label on its own when its order is refunded: \"a refunded order must void its unused label.\""}}})
