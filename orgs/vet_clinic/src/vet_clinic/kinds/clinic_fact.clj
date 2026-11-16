(ns vet_clinic.kinds.clinic-fact
  "A clinic fact: one thing the clinic has written down for replies to
  draw on (opening hours, address, directions, services, how to book,
  the price list). The owner keeps it current; a reply states only
  what a current fact says."
  (:require [waymark10.dsl :refer [defresource]]
            [waymark10.resource :as r]))

(defresource clinic-fact
  {:kind :clinic_fact
   :plural "clinic_facts"
   :states [:current :retired]
   :initial :current
   :terminal #{:retired}
   :summary "{data.topic} · {state}"
   :schema [:map
            [:topic [:enum "opening_hours" "address" "directions" "services" "booking" "prices"]]
            [:text {:x-display {:widget "prose"}} [:string {:min 1 :max 4000}]]]
   :filterable {:state #{:eq :in} :topic #{:eq}}
   :default-filters {:state "current"}
   :actions
   {:revise {:from #{:current} :to :current
             :input [:map [:text {:x-display {:widget "prose"}} [:string {:min 1 :max 4000}]]]
             :edit {:prefill [:text]}
             :handler (r/field-writer [:text])
             :safety {:idempotent true :reversible true :confirm false}}
    :retire {:from #{:current} :to :retired
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A retired fact is no longer said; write a new one to say it again."}}}})
