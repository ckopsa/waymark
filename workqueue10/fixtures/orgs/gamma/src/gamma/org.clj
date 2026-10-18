(ns gamma.org
  "gamma: a fixture org live beside alpha. One kind, one seed."
  (:require [waymark10.dsl :refer [defresource in-domain]]))

(defresource gear
  {:kind :gear
   :plural "gears"
   :states [:new :done]
   :initial :new
   :terminal #{:done}
   :summary "{data.title} · {state}"
   :schema [:map [:title [:string {:min 1 :max 80}]]]
   :filterable {:state #{:eq :in}}
   ;; the gamma-mayor seat walks gears: its queue is the new ones
   :default-filters {:state "new"}
   :actions
   {:finish {:from #{:new} :to :done
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A finished gear stays finished."}}}})

(defn resources [] (in-domain :gamma [gear]))
