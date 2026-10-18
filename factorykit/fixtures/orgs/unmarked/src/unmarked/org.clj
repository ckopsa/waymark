(ns unmarked.org
  "unmarked: a fixture org with no manifest beside its deps.edn."
  (:require [waymark10.dsl :refer [defresource in-domain]]))

(defresource thing
  {:kind :thing
   :plural "things"
   :states [:new :done]
   :initial :new
   :terminal #{:done}
   :summary "{data.title} · {state}"
   :schema [:map [:title [:string {:min 1 :max 80}]]]
   :actions
   {:finish {:from #{:new} :to :done
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A done thing stays done."}}}})

(defn resources [] (in-domain :unmarked [thing]))
