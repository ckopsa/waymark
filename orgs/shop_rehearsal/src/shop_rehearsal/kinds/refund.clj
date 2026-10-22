(ns shop_rehearsal.kinds.refund
  "A refund through the processor, its settlement the processor's event: never more than was paid, the owner approves any over $100, never its packer."
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
(defn- one-way [s] {:idempotent true :reversible false :confirm false :one-way s})

(defresource refund
  {:kind :refund
   :plural "refunds"
   :states [:requested :approved :sent :settled :declined]
   :initial :requested
   :terminal #{:settled :declined}
   :summary "{data.amount} · {data.reason} · {state}"
   :schema [:map [:order_id {:optional true :kind :order} [:maybe :waymark/ref]]
            [:reason [:enum "return" "breakage" "cancelled"]] [:amount :waymark/money]
            [:approved_by {:optional true :kind :member} [:maybe :waymark/ref]]
            ;; written by :send from the processor's answer
            [:processor_ref {:optional true} [:maybe [:string {:max 120}]]]]
   :filterable {:state #{:eq :in} :order_id #{:eq} :reason #{:eq} :processor_ref #{:eq}}
   :decided {:requested "A requested refund is approved or declined within two days; past that, the mayor tells the owner."
             :approved "An approved refund is sent through the processor within a day; past that, the mayor tells the owner."
             :sent "A sent refund settles within ten days; past that, the mayor tells the owner."}
   :expected-age {:requested 172800 :approved 86400 :sent 864000}
   :left-by [{:in #{:sent} :by :engine :via :settle :to :settled}]
   :actions
   {:approve {:from #{:requested} :to :approved
              :input [:map [:approved_by {:kind :member} :waymark/ref]]
              :guards [names-order names-approver packer-does-not-approve owner-over-100]
              :safety (one-way "An approved refund will be paid back.")
              :decided "The mayor approves a refund of $100 or less on its own once a staff member who did not pack the order approves it."}
    ;; a failed call leaves the refund approved, so it is sent again
    :send    {:from #{:approved} :to :sent :guards [names-order]
              ;; never more than was paid: the order's refunded account never goes above its paid
              :posts '[{:move (data :amount) :from [:house :processor_in] :to [:order (data :order_id) :refunded]}]
              :safety (one-way "A sent refund is with the processor; it settles from there.")
              :decided "The mayor sends an approved refund through the processor on its own: \"the refund goes back through the processor.\""
              :effect {:power "payments__refund" :input {:amount '(data :amount) :order '(data :order_id) :reference '(self)}
                       :why "Refund {amount} through the processor." :idempotency-key '(str "refund:" (self))
                       :writes {:processor_ref '(result "id")} :fails-to :approved}}
    ;; only the processor's settlement event walks it (:on-event below)
    :settle  {:from #{:sent} :to :settled :safety (one-way "A settled refund is back in the customer's account.")}
    :decline {:from #{:requested} :to :declined :safety (one-way "A declined refund pays nothing.")
              :decided "The mayor declines a refund on its own when its return was declined or rejected, and says why."}}
   :on-event [{:source :payments :type "refund.updated" :match {:processor_ref '(event "data" "object" "id")}
               :door :settle :tell ["shop_rehearsal-mayor"]}]})

(defscenario packer-cannot-approve "The staff member who packed the order cannot approve its refund."
  {:kind :refund :attempt :approve :input {:approved_by "staff1"} :given [{:kind :order :handle :order-1 :state :delivered :data {:customer "Ana" :buyer_state "CO" :placed_on "2026-10-22" :packed_by "staff1"}}] :row {:state :requested :data {:order_id :order-1 :reason "return" :amount "32.00 USD"}} :expect {:refused :packer-does-not-approve}})
(defscenario the-refund-settles "Maya's $32.00 refund settles at the processor, so its event finds the refund by its reference and it reads Settled."
  {:kind :refund :attempt :settle :as {:id "owner" :type :person} :row {:state :sent :data {:order_id "order-1" :reason "return" :amount "32.00 USD" :processor_ref "re_maya01"}}
   :events [{:source :payments :type "refund.updated" :body {:id "evt_maya_refund" :data {:object {:id "re_maya01" :status "succeeded"}}}}]
   :expect {:refused :out-of-state :because "the resource is Settled"}})
