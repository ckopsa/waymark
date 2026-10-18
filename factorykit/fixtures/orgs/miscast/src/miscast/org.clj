(ns miscast.org
  "miscast: a fixture org with two faults, both in the boss's :scope:
  gizmo.begin, which gizmo does not declare, and the kind gadget,
  which the engine does not serve."
  (:require [waymark10.dsl :refer [defresource in-domain]]))

(defresource gizmo
  {:kind :gizmo
   :plural "gizmos"
   :states [:new :started :done]
   :initial :new
   :terminal #{:done}
   :summary "{data.title} · {state}"
   :schema [:map [:title [:string {:min 1 :max 80}]]]
   :actions
   {:start  {:from #{:new} :to :started
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A started gizmo stays started."}}
    :finish {:from #{:started} :to :done
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A done gizmo stays done."}}}})

(defn resources [] (in-domain :miscast [gizmo]))
