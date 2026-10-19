(ns broken.org
  "broken: a fixture org with three faults and no others. A gizmo's
  `stuck` state is one no seat leaves; the `start` door's guard says
  nothing; the clerk's scope names a `gadget`, which nobody declares."
  (:require [waymark10.dsl :refer [defresource in-domain]]))

(def ^:private unsaid
  {:name :broken/unsaid
   :check (fn [_ _ _] nil)})

(defresource gizmo
  {:kind :gizmo
   :plural "gizmos"
   :states [:new :stuck :done]
   :initial :new
   :terminal #{:done}
   :summary "{data.title} · {state}"
   :schema [:map [:title [:string {:min 1 :max 80}]]]
   :decided {:new "A new gizmo waits a day." :stuck "A stuck gizmo waits a day."}
   :actions
   {:start  {:from #{:new} :to :stuck
             :guards [unsaid]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A started gizmo stays started."}
             :decided "The clerk starts a gizmo on its own."}
    :finish {:from #{:stuck} :to :done
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A done gizmo stays done."}
             :decided "The clerk finishes a gizmo on its own."}}})

(defn resources [] (in-domain :broken [gizmo]))
