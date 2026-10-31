(ns shop_rehearsal.kinds.shipping-label
  "Postage bought from the carrier once per order: :buy asks the carrier integration for the label and
  writes its tracking number from the answer; :void is its own call, and the carrier's event closes it."
  (:require [waymark10.dsl :refer [defguard defresource defscenario refuse]]))

(defguard names-order
  (refuse "A label is bought for one order; name the order first.")
  '(present? :order_id))
(defn- one-way [s] {:idempotent true :reversible false :confirm false :one-way s})

(defresource shipping-label
  {:kind :shipping_label
   :plural "shipping_labels"
   :states [:quoted :bought :used :voiding :voided]
   :initial :quoted
   :terminal #{:used :voided}
   :summary "{data.tracking} · {state}"
   :schema [:map
            [:order_id {:optional true :kind :order} [:maybe :waymark/ref]]
            ;; written by :buy from the carrier's answer, never typed
            [:tracking {:optional true} [:maybe [:string {:max 120}]]]
            ;; the carrier is an outside party; the postage it sells is a supplier cost
            [:carrier {:optional true :kind :member :role "carrier"} [:maybe :waymark/ref]]
            [:postage :waymark/money]]
   :filterable {:state #{:eq :in} :order_id #{:eq} :tracking #{:eq}}
   ;; buying a label happens once per order: one live label at a time
   :unique [:order_id {:while #{:quoted :bought :used}}]
   :decided {:quoted "A quoted label is bought within a day; past that, the mayor tells the owner."
             :bought "A bought label is used within a day; past that, the mayor tells the owner."
             :voiding "A label being voided waits a day for the carrier to return its postage; past that, the mayor tells the owner."}
   :expected-age {:quoted 86400 :bought 86400 :voiding 86400}
   :left-by [{:in #{:voiding} :by :engine :via :voided :to :voided}]
   ;; a failed call leaves the label where it was, so it is asked again
   :actions
   {:buy    {:from #{:quoted} :to :bought :guards [names-order]
             :safety (one-way "A bought label is paid for; void it to get the postage back.")
             :decided "The mayor buys an order's label from the carrier on its own once the order is checked."
             :effect {:power "carrier__buy_label" :input {:order '(data :order_id) :postage '(data :postage) :reference '(self)}
                      :why "Buy the postage label for this order." :idempotency-key '(str "label:" (data :order_id))
                      :writes {:tracking '(result "tracking_number")} :fails-to :quoted}}
    :use    {:from #{:bought} :to :used :guards [names-order]
             :posts '[{:move (data :postage) :from [:house :payables] :to [:house :supplier_costs]}]
             :safety (one-way "A used label has gone with its parcel.")
             :decided "The mayor marks a label used on its own when the carrier scans the parcel."}
    :void   {:from #{:bought} :to :voiding :guards [names-order]
             :safety (one-way "A voided label cannot ship; buy a new one if the order ships after all.")
             :decided "The mayor voids an unused label on its own when its order is refunded: \"a refunded order must void its unused label.\""
             :effect {:power "carrier__void_label" :input {:tracking '(data :tracking) :reference '(self)}
                      :why "Void the unused label {tracking}." :idempotency-key '(str "void:" (self)) :fails-to :bought}}
    ;; only the carrier's event walks it (:on-event below)
    :voided {:from #{:voiding} :to :voided :safety (one-way "A voided label's postage is back with the shop.")}}
   :on-event [{:source :carrier :type "label.voided" :match {:tracking '(event "data" "tracking_number")}
               :door :voided :tell ["shop_rehearsal-mayor"]}]})

(defscenario the-carrier-sells-the-label "Maya's bowls are checked, so the mayor buys their label and the carrier's tracking number lands on it."
  {:kind :shipping_label :attempt :buy :as {:id "owner" :type :person}
   :row {:state :quoted :data {:order_id "order-1" :postage "12.40 USD"}}
   :effects {"carrier__buy_label" {:answer {"tracking_number" "1Z999AA10123456784"}}}
   :expect {:allowed true :lands {:tracking "1Z999AA10123456784"}}})
