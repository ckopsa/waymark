(ns landscape_rehearsal.kinds.visit
  "A visit: one date of a contract's season, made by the contract's
  visits rule. After it is made it is an ordinary row."
  (:require [waymark10.dsl :refer [defresource]]))

(def ^:private safety
  {:idempotent true :reversible false :confirm false
   :one-way "A visit done or skipped stays so; the contract makes the next one."})

(defresource visit
  {:kind :visit
   :plural "visits"
   :states [:planned :done :skipped]
   :initial :planned
   :terminal #{:done :skipped}
   :summary "{data.visit_on} · {state}"
   :schema [:map
            [:contract_id {:kind :contract} :waymark/ref]
            [:visit_on :waymark/date]
            [:crew_id {:optional true :kind :crew} [:maybe :waymark/ref]]]
   :filterable {:state #{:eq :in} :contract_id #{:eq}}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:planned "A visit is planned 14 days ahead; three weeks unvisited, the mayor tells the owner."}
   :expected-age {:planned 1814400}
   :actions
   {:finish {:from #{:planned} :to :done :safety safety
             :decided "The mayor finishes a visit on its own once the crew has been."}
    :skip {:from #{:planned} :to :skipped :safety safety
           :decided "The mayor skips a visit on its own when the customer asks, and says why."}}})
