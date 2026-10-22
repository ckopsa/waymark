(ns shop_rehearsal.kinds.breakage-claim
  "A parcel lost or broken in transit: claimed from the carrier through its integration, whose decision comes back as an event; the customer picks a replacement or a refund."
  (:require [waymark10.dsl :refer [defguard defresource defscenario refuse]]))

(defguard names-order
  (refuse "A breakage claim names the order that broke.")
  '(present? :order_id))
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
   :schema [:map [:customer [:string {:min 1 :max 120}]] [:order_id {:optional true :kind :order} [:maybe :waymark/ref]]
            [:item [:string {:min 1 :max 200}]]
            [:detail {:optional true :x-display {:widget "prose"}} [:maybe [:string {:max 2000}]]]
            [:loss {:optional true} [:maybe [:enum "damaged" "lost"]]] [:remedy {:optional true} [:maybe [:enum "replacement" "refund"]]]
            ;; written by :claim from the carrier's answer
            [:carrier_claim_ref {:optional true} [:maybe [:string {:max 120}]]]]
   :filterable {:state #{:eq :in} :order_id #{:eq} :remedy #{:eq} :carrier_claim_ref #{:eq}}
   :decided {:reported "A reported breakage is claimed from the carrier within two days; past that, the mayor tells the owner."
             :claimed "A carrier claim may take three weeks; past that, the mayor tells the owner."}
   :expected-age {:reported 172800 :claimed 1814400}
   :left-by [{:in #{:claimed} :by :engine :via :settle :to :settled}]
   :actions
   {;; a failed call leaves the claim reported, so it is filed again
    :claim  {:from #{:reported} :to :claimed :guards [names-order customer-chose]
             :safety (one-way "A claimed breakage is with the carrier; the customer's remedy goes ahead.")
             :decided "The mayor files the claim with the carrier on its own once the customer's choice is recorded."
             :effect {:power "carrier__file_claim" :input {:tracking '(follow (data :order_id) :tracking) :loss '(data :loss)
                                                          :item '(data :item) :detail '(data :detail) :reference '(self)}
                      :why "File the carrier claim for {customer}'s {item}." :idempotency-key '(str "claim:" (self))
                      :writes {:carrier_claim_ref '(result "claim_id")} :fails-to :reported}}
    ;; the carrier's decision walks settle and deny (:on-event below)
    :settle {:from #{:claimed} :to :settled :guards [customer-chose]
             :safety (one-way "A settled claim is closed.")
             :decided "The mayor settles a claim on its own when the carrier pays it and the customer has their replacement or refund."}
    :deny   {:from #{:claimed} :to :denied
             :safety (one-way "A denied claim is closed; the customer still has their remedy.")
             :decided "The carrier's denial marks a claim denied; the mayor tells the owner."}}
   :on-event [{:source :carrier :type "claim.approved" :match {:carrier_claim_ref '(event "data" "claim_id")}
               :door :settle :tell ["shop_rehearsal-mayor"]}
              {:source :carrier :type "claim.denied" :match {:carrier_claim_ref '(event "data" "claim_id")}
               :door :deny :tell ["shop_rehearsal-mayor"]}]})

(defscenario the-carrier-denies-the-claim "The carrier denies Maya's claim for her cracked bowl, so its event finds the claim by number and it reads Denied."
  {:kind :breakage_claim :attempt :settle :as {:id "owner" :type :person} :row {:state :claimed :data {:customer "Maya Chen" :item "Ash-glaze serving bowl" :order_id "order-1" :remedy "replacement" :carrier_claim_ref "UPS-CLM-48213"}}
   :events [{:source :carrier :type "claim.denied" :body {:id "evt_maya_claim" :data {:claim_id "UPS-CLM-48213"}}}]
   :expect {:refused :out-of-state :because "the resource is Denied"}})
