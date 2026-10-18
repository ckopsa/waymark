(ns overreach.org
  "overreach: a fixture org with one fault. The clerk is owned by the
  boss, whose :scope holds gizmo.start, and the clerk's scope also
  holds gizmo.finish."
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

(defn resources [] (in-domain :overreach [gizmo]))
