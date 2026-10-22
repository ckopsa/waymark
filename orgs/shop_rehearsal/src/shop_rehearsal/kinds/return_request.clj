(ns shop_rehearsal.kinds.return-request
  "A return within 30 days of delivery; back into stock only once inspected and unbroken."
  (:require [waymark10.dsl :refer [defguard defresource defscenario refuse]]))

(defguard names-order
  (refuse "A return names the order it comes from.")
  '(present? :order_id))

(defguard within-returns-window
  (refuse "Returns are accepted within 30 days of delivery; this one was asked for later.")
  '(<= (data :requested_on) (+ (data :delivered_on) (days 30))))

(defguard inspected
  (refuse "An item comes back into stock only after a staff member inspects it; say who inspected it.")
  '(is-set (input :inspected_by)))

(defguard unbroken
  (refuse "Only an unbroken item goes back into stock.")
  '(= (input :condition) "unbroken"))

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
            [:delivered_on {:optional true} [:maybe :waymark/date]]
            [:requested_on {:optional true} [:maybe :waymark/date]]
            [:reason {:optional true :x-display {:widget "prose"}} [:maybe [:string {:max 1000}]]]
            [:inspected_by {:optional true :kind :member} [:maybe :waymark/ref]]
            [:condition {:optional true} [:maybe [:enum "unbroken" "broken"]]]]
   :filterable {:state #{:eq :in} :order_id #{:eq} :customer #{:eq}}
   :decided {:requested "A requested return may take two weeks to come back; past that, the mayor tells the owner."
             :received "A received return is inspected within two days; past that, the mayor tells the owner."}
   :expected-age {:requested 1209600 :received 172800}
   :actions
   {:receive {:from #{:requested} :to :received
              :guards [names-order within-returns-window]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A received return waits for inspection."}
              :decided "The mayor marks a return received on its own when the parcel arrives."}
    :decline {:from #{:requested} :to :declined
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A declined return is closed; nothing is refunded for it."}
              :decided "The mayor declines a return on its own when it is asked more than 30 days after delivery: \"returns are accepted within 30 days of delivery for unbroken items.\""}
    :restock {:from #{:received} :to :restocked
              :input [:map [:inspected_by {:kind :member} :waymark/ref] [:condition [:enum "unbroken" "broken"]]]
              :guards [inspected unbroken]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A restocked item is back on sale; its refund follows."}
              :decided "The mayor restocks a return on its own once a staff member has inspected it and found it unbroken."}
    :reject  {:from #{:received} :to :rejected
              :input [:map [:inspected_by {:kind :member} :waymark/ref]]
              :guards [inspected]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A rejected return does not go back into stock."}
              :decided "The mayor rejects a return on its own when the inspecting staff member finds it broken, and says why."}}})

(defscenario return-past-window "A return asked for 31 days after delivery is refused."
  {:kind :return_request :attempt :receive :row {:state :requested :data {:customer "Ana" :item "Blue mug" :order_id "order-1" :delivered_on "2026-09-01" :requested_on "2026-10-02"}} :expect {:refused :within-returns-window}})
(defscenario not-restocked-without-inspector "A received return does not go back into stock until the staff member who inspected it is named."
  {:kind :return_request :attempt :restock :input {:condition "unbroken"} :row {:state :received :data {:customer "Ana" :item "Blue mug" :order_id "order-1"}} :expect {:refused :inspected}})
(defscenario broken-not-restocked "A received return found broken does not go back into stock."
  {:kind :return_request :attempt :restock :input {:inspected_by "staff2" :condition "broken"} :row {:state :received :data {:customer "Ana" :item "Blue mug" :order_id "order-1"}} :expect {:refused :unbroken}})
