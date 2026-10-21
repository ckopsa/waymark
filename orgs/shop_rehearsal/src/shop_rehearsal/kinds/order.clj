(ns shop_rehearsal.kinds.order
  "An order: a customer's checkout on the storefront. It reserves its
  items when placed, is packed only once its payment has cleared, and
  ships within 3 business days."
  (:require [waymark10.dsl :refer [defguard defresource defscenario refuse warn]]))

(defguard payment-cleared
  (refuse "An order is not packed until the payment has cleared; name the cleared payment first.")
  '(present? :payment_id))

(defguard names-packer
  (refuse "Say which staff member packed the order.")
  '(present? :packed_by))

(defguard has-label
  (refuse "An order ships on a label bought from the carrier; name its label first.")
  '(present? :label_id))

;; advisory: a late order still ships, but whoever ships it is told
(defguard ships-in-time
  (warn "This order ships later than 3 business days after its payment cleared.")
  '(<= (today) (+ (data :paid_on) (business-days 3))))

(defresource order
  {:kind :order
   :plural "orders"
   :states [:placed :paid :packed :shipped :delivered :cancelled]
   :initial :placed
   :terminal #{:delivered :cancelled}
   :summary "{data.customer} · {data.total_cents}¢ · {state}"
   :schema [:map
            [:customer [:string {:min 1 :max 120}]]
            [:email {:optional true} [:maybe [:string {:max 200}]]]
            ;; stand-in: line items as text, one product per line
            [:items {:x-display {:widget "prose"}} [:string {:min 1 :max 2000}]]
            [:product_id {:optional true :kind :product} [:maybe :waymark/ref]]
            [:quantity {:optional true} [:maybe [:int {:min 1}]]]
            [:subtotal_cents [:int {:min 0}]]
            [:buyer_state [:string {:min 2 :max 2}]]
            [:tax_cents [:int {:min 0}]]
            [:total_cents [:int {:min 0}]]
            [:placed_on {:optional true} [:maybe [:string {:max 10}]]]
            [:payment_id {:optional true :kind :payment} [:maybe :waymark/ref]]
            ;; the day the payment cleared: the 3 business days count from here
            [:paid_on {:optional true} [:maybe :waymark/date]]
            [:packed_by {:optional true :kind :member} [:maybe :waymark/ref]]
            [:label_id {:optional true :kind :shipping_label} [:maybe :waymark/ref]]]
   :filterable {:state #{:eq :in} :customer #{:eq} :buyer_state #{:eq}}
   :decided {:placed "A placed order may wait a day for its payment to clear; past that, the mayor cancels it and tells the owner."
             :paid "A paid order ships within 3 business days; past that, the mayor tells the owner."
             :packed "A packed order may wait a day for its label and pickup; past that, the mayor tells the owner."
             :shipped "A shipped order may be in transit for two weeks; past that, the mayor asks the carrier and tells the owner."}
   :expected-age {:placed  86400
                  :paid    {:until '(+ (data :paid_on) (business-days 3))}
                  :packed  {:until '(+ (data :paid_on) (business-days 3))}
                  :shipped 1209600}
   :actions
   {:mark_paid {:from #{:placed} :to :paid
                :guards [payment-cleared]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "A paid order stays paid; a refund gives the money back."}
                :decided "The mayor marks an order paid on its own when the processor's payment for it has cleared: \"a payment that clears should move the order to paid without anyone retyping it.\""}
    :cancel    {:from #{:placed} :to :cancelled
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "A cancelled order releases its items; the customer orders again."}
                :decided "The mayor cancels an unpaid order on its own after a day, and says why."}
    :pack      {:from #{:paid} :to :packed
                :guards [payment-cleared names-packer]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "A packed order is boxed; it ships from here."}
                :decided "The mayor marks an order packed on its own once a staff member says they packed it."}
    :ship      {:from #{:packed} :to :shipped
                :guards [has-label ships-in-time]
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "A shipped order is with the carrier."}
                :decided "The mayor marks an order shipped on its own once its label is used."}
    :deliver   {:from #{:shipped} :to :delivered
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "A delivered order starts its 30 days for returns."}
                :decided "The mayor marks an order delivered on its own when the carrier says it was delivered."}}})

(defscenario ships-on-third-business-day
  "An order paid on a Thursday ships on the Tuesday after, its third business day."
  {:kind :order :attempt :ship
   :at "2026-10-27T18:00:00Z"
   :calendar {:zone "America/Denver" :workdays #{:mon :tue :wed :thu :fri}}
   :row {:state :packed
         :data {:customer "Ana" :items "Blue mug" :subtotal_cents 3200
                :buyer_state "CO" :tax_cents 0 :total_cents 3200
                :label_id "label-1" :paid_on "2026-10-22"}}
   :expect {:allowed true}})

(defscenario ships-late-is-told
  "An order paid on a Thursday and shipped the Wednesday after is late, and the shipper is told."
  {:kind :order :attempt :ship
   :at "2026-10-28T18:00:00Z"
   :calendar {:zone "America/Denver" :workdays #{:mon :tue :wed :thu :fri}}
   :row {:state :packed
         :data {:customer "Ana" :items "Blue mug" :subtotal_cents 3200
                :buyer_state "CO" :tax_cents 0 :total_cents 3200
                :label_id "label-1" :paid_on "2026-10-22"}}
   :expect {:refused :ships-in-time}})
