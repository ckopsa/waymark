(ns shop_rehearsal.kinds.breakage-claim
  "Breakage in transit: claimed from the carrier; the customer picks a replacement or a refund."
  (:require [waymark10.dsl :refer [defguard defresource defscenario refuse]]))

(defguard names-order
  (refuse "A breakage claim names the order that broke.")
  '(present? :order_id))
(defguard carrier-claim
  (refuse "Breakage in transit is claimed from the carrier; record the carrier's claim number.")
  '(is-set (input :carrier_claim_ref)))
(defguard customer-chose
  (refuse "The customer gets a replacement or a refund, their choice; record which they chose.")
  '(present? :remedy))
(defn- one-way [s] {:idempotent true :reversible false :confirm false :one-way s})

(defresource breakage-claim
  {:kind :breakage_claim
   :plural "breakage_claims"
   :states [:reported :claimed :settled :denied]
   :initial :reported
   :terminal #{:settled :denied}
   :summary "{data.customer} · {data.item} · {state}"
   :schema [:map
            [:customer [:string {:min 1 :max 120}]]
            [:order_id {:optional true :kind :order} [:maybe :waymark/ref]]
            [:item [:string {:min 1 :max 200}]]
            [:detail {:optional true :x-display {:widget "prose"}} [:maybe [:string {:max 2000}]]]
            [:remedy {:optional true} [:maybe [:enum "replacement" "refund"]]]
            [:carrier_claim_ref {:optional true} [:maybe [:string {:max 120}]]]]
   :filterable {:state #{:eq :in} :order_id #{:eq} :remedy #{:eq}}
   :decided {:reported "A reported breakage is claimed from the carrier within two days; past that, the mayor tells the owner."
             :claimed "A carrier claim may take three weeks; past that, the mayor tells the owner."}
   :expected-age {:reported 172800 :claimed 1814400}
   :actions
   {:claim  {:from #{:reported} :to :claimed
             :input [:map [:carrier_claim_ref [:string {:min 1 :max 120}]] [:remedy [:enum "replacement" "refund"]]]
             :guards [names-order carrier-claim customer-chose]
             :safety (one-way "A claimed breakage is with the carrier; the customer's remedy goes ahead.")
             :decided "The mayor files the claim on its own once the carrier's claim number and the customer's choice are recorded."}
    :settle {:from #{:claimed} :to :settled
             :guards [customer-chose]
             :safety (one-way "A settled claim is closed.")
             :decided "The mayor settles a claim on its own when the carrier pays it and the customer has their replacement or refund."}
    :deny   {:from #{:claimed} :to :denied
             :safety (one-way "A denied claim is closed; the customer still has their remedy.")
             :decided "The mayor marks a claim denied on its own when the carrier refuses it, and tells the owner."}}})

(defscenario not-claimed-without-carrier-number "A reported breakage is not claimed until the carrier's claim number is recorded."
  {:kind :breakage_claim :attempt :claim :input {:remedy "replacement"} :row {:state :reported :data {:customer "Ana" :item "Blue mug" :order_id "order-1"}} :expect {:refused :carrier-claim}})
