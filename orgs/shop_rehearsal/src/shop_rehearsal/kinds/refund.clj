(ns shop_rehearsal.kinds.refund
  "A refund through the processor: never more than was paid, the owner approves any over $100, never its packer."
  (:require [waymark10.dsl :refer [defguard defresource defscenario hold refuse]]))

(defguard names-order
  (refuse "A refund names the order it gives money back for.")
  '(present? :order_id))
(defguard names-approver
  (refuse "Say who approves this refund.")
  '(is-set (input :approved_by)))
(defguard packer-does-not-approve
  (refuse "Whoever packed an order does not approve its refund.")
  '(not= (input :approved_by) (follow (data :order_id) :packed_by)))
(defguard owner-over-100
  (hold "A refund over $100 waits for the owner's approval.")
  '(<= (data :amount) (money "100.00" "USD")))
(defguard has-processor-ref
  (refuse "A refund goes back through the processor; record the processor's refund reference.")
  '(is-set (input :processor_ref)))
(defn- one-way [s] {:idempotent true :reversible false :confirm false :one-way s})

(defresource refund
  {:kind :refund
   :plural "refunds"
   :states [:requested :approved :sent :declined]
   :initial :requested
   :terminal #{:sent :declined}
   :summary "{data.amount} · {data.reason} · {state}"
   :schema [:map
            [:order_id {:optional true :kind :order} [:maybe :waymark/ref]]
            [:reason [:enum "return" "breakage" "cancelled"]]
            [:amount :waymark/money]
            [:approved_by {:optional true :kind :member} [:maybe :waymark/ref]]
            [:processor_ref {:optional true} [:maybe [:string {:max 120}]]]]
   :filterable {:state #{:eq :in} :order_id #{:eq} :reason #{:eq}}
   :decided {:requested "A requested refund is approved or declined within two days; past that, the mayor tells the owner."
             :approved "An approved refund is sent through the processor within a day; past that, the mayor tells the owner."}
   :expected-age {:requested 172800 :approved 86400}
   :actions
   {:approve {:from #{:requested} :to :approved
              :input [:map [:approved_by {:kind :member} :waymark/ref]]
              :guards [names-order names-approver packer-does-not-approve owner-over-100]
              :safety (one-way "An approved refund will be paid back.")
              :decided "The mayor approves a refund of $100 or less on its own once a staff member who did not pack the order approves it."}
    :send    {:from #{:approved} :to :sent
              :input [:map [:processor_ref [:string {:min 1 :max 120}]]]
              :guards [has-processor-ref]
              ;; never more than was paid: the order's refunded account never goes above its paid
              :posts '[{:move (data :amount) :from [:house :processor_in] :to [:order (data :order_id) :refunded]}]
              :safety (one-way "A sent refund has gone back to the customer through the processor.")
              :decided "The mayor marks a refund sent on its own when the processor confirms it: \"the refund goes back through the processor.\""}
    :decline {:from #{:requested} :to :declined
              :safety (one-way "A declined refund pays nothing.")
              :decided "The mayor declines a refund on its own when its return was declined or rejected, and says why."}}})

(defscenario packer-cannot-approve "The staff member who packed the order cannot approve its refund."
  {:kind :refund :attempt :approve :input {:approved_by "staff1"} :given [{:kind :order :handle :order-1 :state :delivered :data {:customer "Ana" :buyer_state "CO" :placed_on "2026-10-22" :packed_by "staff1"}}] :row {:state :requested :data {:order_id :order-1 :reason "return" :amount "32.00 USD"}} :expect {:refused :packer-does-not-approve}})
(defscenario not-sent-without-processor-ref "An approved refund is not sent until the processor's refund reference is recorded."
  {:kind :refund :attempt :send :input {} :row {:state :approved :data {:order_id "order-1" :reason "return" :amount "32.00 USD"}} :expect {:refused :has-processor-ref}})
