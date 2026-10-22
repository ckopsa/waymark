(ns landscape_rehearsal.kinds.materials-order
  "A materials order: what the house buys from an outside supplier for
  a job, and the day it is due. Its supplier is a member ref with the
  role `supplier`, so the supplier's grant (:names-me [\"supplier\"])
  admits the orders that name them and no other, and their `delay` from
  the link the order sent is theirs in the history, not transcribed.
  Film step 3 (docs/spec-teams.md §11) is its scenario."
  (:require [waymark10.dsl :refer [defresource defscenario]]
            [waymark10.resource :as r]))

(def ^:private safety
  {:idempotent true :reversible false :confirm false
   :one-way "An order delivered or cancelled stays so; order again for more."})

(defscenario the-supplier-delays-the-paving-stones
  "The paving stones for the Hendersons' patio are late, so Stoneyard, the order's supplier, moves them to Friday from their link."
  {:kind :materials_order :attempt :delay
   :cast {:stoneyard {:display "Stoneyard" :type :outside :roles ["supplier"]}}
   :as :stoneyard
   :row {:state :ordered
         :data {:item "Paving stones for the Hendersons' patio" :supplier "stoneyard"
                :arrives_on "2027-05-13"}}
   :input {:arrives_on "2027-05-14"}
   :expect {:allowed true}})

(defresource materials-order
  {:kind :materials_order
   :plural "materials_orders"
   :states [:ordered :delayed :delivered :cancelled]
   :initial :ordered
   :terminal #{:delivered :cancelled}
   :summary "{data.item} · {data.arrives_on} · {state}"
   :schema [:map
            [:item [:string {:min 1 :max 80}]]
            [:supplier {:kind :member :role "supplier"} :waymark/ref]
            [:booking_id {:optional true :kind :booking} [:maybe :waymark/ref]]
            [:arrives_on :waymark/date]]
   :filterable {:state #{:eq :in} :supplier #{:eq} :booking_id #{:eq}}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:ordered "An order waits for its day; a week past it, the mayor tells the owner."
             :delayed "A delayed order waits for its new day; a week past it, the mayor tells the owner."}
   :expected-age {:ordered 1209600 :delayed 604800}
   :actions
   {:delay {:from #{:ordered :delayed} :to :delayed
            :input [:map [:arrives_on :waymark/date]]
            :edit {:prefill [:arrives_on]}
            :handler (r/field-writer [:arrives_on])
            :display {:label "Delay" :order 1}
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A delayed order is not undelayed; delay it again to name another day."}
            :decided "The supplier moves an order's day from their link; the mayor does it only when they call instead."}
    :deliver {:from #{:ordered :delayed} :to :delivered :safety safety
              :display {:label "Delivered" :order 2}
              :decided "The mayor marks an order delivered on its own once the crew has the stones."}
    :cancel {:from #{:ordered :delayed} :to :cancelled :safety safety
             :display {:label "Cancel" :order 3}
             :decided "The mayor cancels an order on its own when its job is called off, and says why."}}
   :scenarios [the-supplier-delays-the-paving-stones]})
