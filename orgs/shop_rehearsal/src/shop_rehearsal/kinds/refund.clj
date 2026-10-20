(ns shop_rehearsal.kinds.refund
  "A refund: money given back through the processor, for a return, a
  breakage or a cancelled order. Never more than was paid; the owner
  approves any over $100; whoever packed the order does not approve it."
  (:require [waymark10.dsl :refer [defguard defresource hold refuse]]))

(defguard names-order
  (refuse "A refund names the order it gives money back for.")
  '(present? :order_id))

(defguard not-over-paid
  (refuse "A refund is never more than was paid.")
  '(<= (data :amount_cents) (data :paid_cents)))

(defguard names-approver
  (refuse "Say who approves this refund.")
  '(present? :approved_by))

(defguard packer-does-not-approve
  (refuse "Whoever packed an order does not approve its refund.")
  '(not= (data :approved_by) (data :packed_by)))

(defguard owner-over-100
  (hold "A refund over $100 waits for the owner's approval.")
  '(<= (data :amount_cents) 10000))

(defguard has-processor-ref
  (refuse "A refund goes back through the processor; record the processor's refund reference.")
  '(present? :processor_ref))

(defresource refund
  {:kind :refund
   :plural "refunds"
   :states [:requested :approved :sent :declined]
   :initial :requested
   :terminal #{:sent :declined}
   :summary "{data.amount_cents}¢ · {data.reason} · {state}"
   :schema [:map
            [:order_id {:optional true :kind :order} [:maybe :waymark/ref]]
            [:reason [:enum "return" "breakage" "cancelled"]]
            [:amount_cents [:int {:min 1}]]
            ;; copied from the order and its packer by hand (concept 1)
            [:paid_cents [:int {:min 0}]]
            [:packed_by {:optional true :kind :member} [:maybe :waymark/ref]]
            [:approved_by {:optional true :kind :member} [:maybe :waymark/ref]]
            [:processor_ref {:optional true} [:maybe [:string {:max 120}]]]]
   :filterable {:state #{:eq :in} :order_id #{:eq} :reason #{:eq}}
   :decided {:requested "A requested refund is approved or declined within two days; past that, the mayor tells the owner."
             :approved "An approved refund is sent through the processor within a day; past that, the mayor tells the owner."}
   :expected-age {:requested 172800 :approved 86400}
   :actions
   {:approve {:from #{:requested} :to :approved
              :guards [names-order not-over-paid names-approver packer-does-not-approve owner-over-100]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "An approved refund will be paid back."}
              :decided "The mayor approves a refund of $100 or less on its own once a staff member who did not pack the order approves it."}
    :send    {:from #{:approved} :to :sent
              :guards [has-processor-ref]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A sent refund has gone back to the customer through the processor."}
              :decided "The mayor marks a refund sent on its own when the processor confirms it: \"the refund goes back through the processor.\""}
    :decline {:from #{:requested} :to :declined
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A declined refund pays nothing."}
              :decided "The mayor declines a refund on its own when its return was declined or rejected, and says why."}}})
