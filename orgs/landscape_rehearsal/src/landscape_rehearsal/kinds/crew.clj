(ns landscape_rehearsal.kinds.crew
  "A crew: the resource a booking and a time off hold. It declares
  nothing about its time; the kinds that occupy it do."
  (:require [waymark10.dsl :refer [defresource]]))

(defresource crew
  {:kind :crew
   :plural "crews"
   :states [:active :resting]
   :initial :active
   :terminal #{:resting}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 80}]]]
   :filterable {:state #{:eq :in}}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:active "A crew stays active for the season; the owner reviews the crews every three months."}
   :expected-age {:active 7776000}
   :actions
   {:rest {:from #{:active} :to :resting
           :safety {:idempotent true :reversible false :confirm false
                    :one-way "A resting crew takes no more work; make a new crew to take it."}
           :decided "The mayor rests a crew on its own when the owner has said the crew is disbanded."}}})
