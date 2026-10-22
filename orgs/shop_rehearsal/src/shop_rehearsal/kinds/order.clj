(ns shop_rehearsal.kinds.order
  "An order: a checkout that is paid, packed, checked by someone else, and shipped within 3 business days of its payment clearing."
  (:require [waymark10.dsl :refer [defguard defresource defscenario hold refuse]]
            [waymark10.resource :as r]))

(defguard names-payment
  (refuse "An order is not packed until the payment has cleared; name the cleared payment first.")
  '(is-set (input :payment_id)))
(defguard has-label
  (refuse "An order ships on a label bought from the carrier; name its label first.")
  '(is-set (input :label_id)))
(defguard owner-purges
  (hold "An order's records are erased only on the owner's tap."))

;; 3 business days from the day the order's payment cleared
(def ship-by '(+ (follow (data :payment_id) :cleared_on) (business-days 3)))
;; what the customer gives at checkout; the rest is written by the doors
(def ^:private born-with
  [[:customer {:personal true} [:string {:min 1 :max 120}]]
   [:email {:optional true :personal true} [:maybe [:string {:max 200}]]]
   [:buyer_state [:string {:min 2 :max 2}]]
   [:placed_on :waymark/date]])
(defn- one-way [s] {:idempotent true :reversible false :confirm false :one-way s})

(defresource order
  {:kind :order
   :plural "orders"
   :states [:placed :paid :packed :checked :shipped :delivered :cancelled :purged]
   :initial :placed
   :terminal #{:cancelled :purged}
   :summary "{data.customer} · {data.subtotal} · {state}"
   :retain {:data true}
   :create-schema (into [:map] born-with)
   :schema (into [:map] (concat born-with [
            [:payment_id {:optional true :kind :payment} [:maybe :waymark/ref]]
            [:packed_by {:optional true :kind :member} [:maybe :waymark/ref]]
            [:label_id {:optional true :kind :shipping_label} [:maybe :waymark/ref]]
            [:shipped_on {:optional true} [:maybe :waymark/date]]]))
   :lines {:kind :order_line :parent :order
           :fields [[:product {:kind :product} :waymark/ref] [:qty [:int {:min 1}]] [:unit_price :waymark/money]]
           :defaults {:unit_price '(follow (line :product) :price)}
           :totals {:subtotal '(sum [l (lines)] (* (line :qty) (line :unit_price)))
                    :tax '(round (* (data :subtotal) (rate :sales_tax {:us_state (data :buyer_state)})))}}
   :accounts {:paid {:unit [:money "USD"]} :refunded {:unit [:money "USD"] :never-above :paid}}
   :measures {:shipped_on_time
              {:label "Orders shipped within 3 business days of payment" :stat :rate
               :among "state=shipped,delivered" :where (list '<= '(data :shipped_on) ship-by)
               :at :placed_on :over '(weeks 4) :tell "shop_rehearsal-mayor"
               :default-target {:op ">=" :target 0.95M}}}
   :keep {:for '(years 7) :from {:state :delivered} :then :purge}
   :filterable {:state #{:eq :in} :customer #{:eq} :buyer_state #{:eq} :placed_on #{:eq :range}}
   :decided {:placed "A placed order may wait a day for its payment to clear; past that, the mayor cancels it and tells the owner."
             :paid "A paid order ships within 3 business days of its payment clearing; past that, the mayor tells the owner."
             :packed "A packed order is checked by someone who did not pack it, within the same 3 business days."
             :checked "A checked order waits for its label and pickup, within the same 3 business days."
             :shipped "A shipped order may be in transit for two weeks; past that, the mayor asks the carrier and tells the owner."
             :delivered "A delivered order and its tax record are kept seven years, then purged."}
   :expected-age {:placed 86400 :paid {:until ship-by} :packed {:until ship-by} :checked {:until ship-by}
                  :shipped 1209600 :delivered 220903200}
   :actions
   {:mark_paid {:from #{:placed} :to :paid
                :input [:map [:payment_id {:kind :payment} :waymark/ref]]
                :handler (r/field-writer [:payment_id])
                :guards [names-payment]
                :posts '[{:each (lines) :move (line :qty) :from [:product (line :product) :on_hand] :to [:product (line :product) :reserved]}]
                :safety (one-way "A paid order stays paid; a refund gives the money back.")
                :decided "The mayor marks an order paid on its own when the processor's payment for it has cleared: \"a payment that clears should move the order to paid without anyone retyping it.\""}
    :cancel    {:from #{:placed} :to :cancelled
                :safety (one-way "A cancelled order releases its items; the customer orders again.")
                :decided "The mayor cancels an unpaid order on its own after a day, and says why."}
    :pack      {:from #{:paid} :to :packed
                :stamp {:packed_by '(actor)}
                :safety (one-way "A packed order is boxed; it is checked from here.")
                :decided "The mayor marks an order packed on its own once a staff member says they packed it."}
    :check     {:from #{:packed} :to :checked
                :not-by [:packed_by]
                :safety (one-way "A checked order is ready for its label.")
                :decided "Whoever did not pack the order checks it: the owner or the other staff member."}
    :ship      {:from #{:checked} :to :shipped
                :input [:map [:label_id {:kind :shipping_label} :waymark/ref] [:shipped_on :waymark/date]]
                :handler (r/field-writer [:label_id :shipped_on])
                :guards [has-label]
                :posts '[{:each (lines) :move (line :qty) :from [:product (line :product) :reserved] :to [:product (line :product) :sold]}]
                :safety (one-way "A shipped order is with the carrier.")
                :decided "The mayor marks an order shipped on its own once its label is used."}
    :deliver   {:from #{:shipped} :to :delivered
                :safety (one-way "A delivered order starts its 30 days for returns.")
                :decided "The mayor marks an order delivered on its own when the carrier says it was delivered."}
    :purge     {:from #{:delivered} :to :purged :purge true :waives #{:required} :guards [owner-purges]
                :safety {:idempotent true :reversible false :confirm true
                         :consequence "A purged order's customer details are erased for good; the row stays as the record it existed."}}}})

(def ^:private row {:customer "Ana" :buyer_state "CO" :placed_on "2026-10-22"})
(defscenario not-paid-without-payment "A placed order is not marked paid until its payment is named."
  {:kind :order :attempt :mark_paid :input {} :row {:state :placed :data row} :expect {:refused :names-payment}})
(defscenario packer-does-not-check "Whoever packed an order does not check it."
  {:kind :order :attempt :check :cast {:staff1 {:roles ["packer"]}} :as :staff1 :row {:state :packed :data (assoc row :packed_by "staff1")} :expect {:refused :packed_by-not-by}})
(defscenario purge-waits-for-owner "An agent's purge of an order waits for the owner's tap."
  {:kind :order :attempt :purge :row {:state :delivered :data row} :expect {:refused :owner-purges}})
