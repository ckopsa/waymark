(ns week_fixture.kinds.supply
  "A supply order: the yard packs it, and a second hand checks what the
  first packed (ticket 3aeb7fb8): `:pack` stamps who packed it, and
  `:not-by` keeps them from `:check`."
  (:require [waymark10.dsl :refer [defresource]]))

(defresource supply
  {:kind :supply_order
   :plural "supply_orders"
   :states [:ordered :packed :checked]
   :initial :ordered
   :terminal #{:checked}
   :summary "{data.item} · {state}"
   :schema [:map
            [:item [:string {:min 1 :max 120}]]
            [:reorders {:optional true :kind :supply_order} [:maybe :waymark/ref]]
            [:packed_by {:optional true :kind :member} [:maybe :waymark/ref]]]
   ;; a stamped field is writable only by its stamp, at create too
   :create-schema [:map
                   [:item [:string {:min 1 :max 120}]]
                   [:reorders {:optional true :kind :supply_order} [:maybe :waymark/ref]]]
   :filterable {:state #{:eq :in}}
   :default-filters {:state "ordered"}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:ordered "An order may wait a day for the yard to pack it."
             :packed "A packed order may wait a day for a second hand to check it."}
   :expected-age {:ordered 86400 :packed 86400}
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
                     :one-way "A checked order is ready to go out."}}}})
