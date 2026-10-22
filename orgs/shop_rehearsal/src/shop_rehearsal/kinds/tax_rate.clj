(ns shop_rehearsal.kinds.tax-rate
  "A state's sales tax rate, in force from the day it takes effect until a later row replaces it."
  (:require [waymark10.dsl :refer [defresource]]))

(defresource tax-rate
  {:kind :tax_rate
   :plural "tax_rates"
   :states [:in_force :retired]
   :initial :in_force
   :terminal #{:retired}
   :summary "{data.us_state} · {data.rate} from {data.effective_on}"
   :schema [:map
            [:us_state [:string {:min 2 :max 2}]]
            [:rate :decimal]
            [:effective_on :waymark/date]]
   :filterable {:us_state #{:eq} :effective_on #{:eq :range}}
   :rate-table {:name :sales_tax :key [:us_state] :value :rate :effective :effective_on}
   :decided {:in_force "A rate stays in force until the owner enters a later one for its state."}
   :expected-age {:in_force 315360000}
   :actions
   {:retire {:from #{:in_force} :to :retired
             :safety {:idempotent true :reversible false :confirm false :one-way "A retired rate was entered in error; enter the right one as a new rate."}
             :decided "The mayor retires a rate on its own when the owner says it was entered in error."}}})
