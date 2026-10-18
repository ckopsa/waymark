(ns alpha.org
  "alpha: a fixture org whose manifest says live. One kind, one seed."
  (:require [waymark10.dsl :refer [defresource in-domain]]))

(defresource sprocket
  {:kind :sprocket
   :plural "sprockets"
   :states [:new :done]
   :initial :new
   :terminal #{:done}
   :summary "{data.title} · {state}"
   :schema [:map [:title [:string {:min 1 :max 80}]]]
   :actions
   {:finish {:from #{:new} :to :done
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A finished sprocket stays finished."}}}})

(defn resources [] (in-domain :alpha [sprocket]))
