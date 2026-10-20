(ns shop_rehearsal.kinds.return-request
  "A return: a customer sends back an item within 30 days of delivery.
  It comes back into stock only after a staff member inspects it and
  finds it unbroken."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard names-order
  (refuse "A return names the order it comes from.")
  '(present? :order_id))

(defguard inspected
  (refuse "An item comes back into stock only after a staff member inspects it; say who inspected it.")
  '(present? :inspected_by))

(defguard unbroken
  (refuse "Only an unbroken item goes back into stock.")
  '(= (data :condition) "unbroken"))

(defresource return-request
  {:kind :return_request
   :plural "return_requests"
   :states [:requested :received :restocked :rejected :declined]
   :initial :requested
   :terminal #{:restocked :rejected :declined}
   :summary "{data.customer} · {data.item} · {state}"
   :schema [:map
            [:customer [:string {:min 1 :max 120}]]
            [:order_id {:optional true :kind :order} [:maybe :waymark/ref]]
            [:product_id {:optional true :kind :product} [:maybe :waymark/ref]]
            [:item [:string {:min 1 :max 200}]]
            [:delivered_on {:optional true} [:maybe [:string {:max 10}]]]
            [:requested_on {:optional true} [:maybe [:string {:max 10}]]]
            [:reason {:optional true :x-display {:widget "prose"}} [:maybe [:string {:max 1000}]]]
            [:inspected_by {:optional true :kind :member} [:maybe :waymark/ref]]
            [:condition {:optional true} [:maybe [:enum "unbroken" "broken"]]]]
   :filterable {:state #{:eq :in} :order_id #{:eq} :customer #{:eq}}
   :decided {:requested "A requested return may take two weeks to come back; past that, the mayor tells the owner."
             :received "A received return is inspected within two days; past that, the mayor tells the owner."}
   :expected-age {:requested 1209600 :received 172800}
   :actions
   {:receive {:from #{:requested} :to :received
              :guards [names-order]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A received return waits for inspection."}
              :decided "The mayor marks a return received on its own when the parcel arrives."}
    :decline {:from #{:requested} :to :declined
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A declined return is closed; nothing is refunded for it."}
              :decided "The mayor declines a return on its own when it is asked more than 30 days after delivery: \"returns are accepted within 30 days of delivery for unbroken items.\""}
    :restock {:from #{:received} :to :restocked
              :guards [inspected unbroken]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A restocked item is back on sale; its refund follows."}
              :decided "The mayor restocks a return on its own once a staff member has inspected it and found it unbroken."}
    :reject  {:from #{:received} :to :rejected
              :guards [inspected]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A rejected return does not go back into stock."}
              :decided "The mayor rejects a return on its own when the inspecting staff member finds it broken, and says why."}}})
