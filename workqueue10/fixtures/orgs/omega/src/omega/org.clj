(ns omega.org
  "omega: a fixture org whose kind name collides with alpha's. Skipped."
  (:require [waymark10.dsl :refer [defresource in-domain]]))

(defresource sprocket
  {:kind :sprocket
   :plural "omega_sprockets"
   :states [:new :done]
   :initial :new
   :terminal #{:done}
   :summary "{data.title} · {state}"
   :schema [:map [:title [:string {:min 1 :max 80}]]]
   :actions
   {:finish {:from #{:new} :to :done
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A finished sprocket stays finished."}}}})

(defn resources [] (in-domain :omega [sprocket]))
