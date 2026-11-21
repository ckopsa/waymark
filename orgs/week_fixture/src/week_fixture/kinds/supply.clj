(ns week_fixture.kinds.supply
  "A supply order: the yard packs it, and a second hand checks what the
  first packed (ticket 3aeb7fb8): `:pack` stamps who packed it, and
  `:not-by` keeps them from `:check`. A checked order ships through a
  courier (ticket c6252997): `:ship` asks in `:shipping`, and the
  answer lands `:shipped`."
  (:require [waymark10.dsl :refer [defresource]]))

(defresource supply
  {:kind :supply_order
   :plural "supply_orders"
   :states [:ordered :packed :checked :shipping :shipped]
   :initial :ordered
   :terminal #{:shipped}
   :summary "{data.item} · {state}"
   :schema [:map
            [:item [:string {:min 1 :max 120}]]
            [:reorders {:optional true :kind :supply_order} [:maybe :waymark/ref]]
            [:packed_by {:optional true :kind :member} [:maybe :waymark/ref]]
            ;; the courier's tracking number, written by ship's answer
            [:tracking {:optional true} [:maybe [:string {:max 120}]]]]
   ;; a stamped field is writable only by its stamp, at create too
   :create-schema [:map
                   [:item [:string {:min 1 :max 120}]]
                   [:reorders {:optional true :kind :supply_order} [:maybe :waymark/ref]]]
   :filterable {:state #{:eq :in}}
   :default-filters {:state "ordered"}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:ordered "An order may wait a day for the yard to pack it."
             :packed "A packed order may wait a day for a second hand to check it."
             :checked "A checked order may wait a day for the courier to be booked."
             :shipping "An order may wait an hour for the courier to take it."}
   :expected-age {:ordered 86400 :packed 86400 :checked 86400 :shipping 3600}
   :left-by [{:in #{:shipping} :by :engine :via :ship_landed :to :shipped}]
   :actions
   {:pack  {:from #{:ordered} :to :packed
            :stamp {:packed_by '(actor)}
            :decided "The yard clerk packs an order on its own once the yard has it ready."
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A packed order stays packed until it is checked."}}
    :check {:from #{:packed} :to :checked
            :not-by [:packed_by]
            :decided "Who packed an order is stamped when it is packed; the check only keeps them from checking it."
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A checked order is ready to go out."}}
    :ship {:from #{:checked} :to :shipping
           :decided "The yard clerk books the courier on its own once an order is checked."
           :safety {:idempotent true :reversible false :confirm false
                    :one-way "A booked courier comes; an order ships once."}
           :effect {:power "courier__book_pickup"
                    :input {:item '(data :item) :reference '(self)}
                    :why "Book the courier for {item}."
                    :idempotency-key '(str "pickup:" (self))
                    :writes {:tracking '(result "tracking")}
                    :lands :shipped
                    :fails-to :checked}}}})
