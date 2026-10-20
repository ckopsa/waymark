(ns beta.org
  "beta: a fixture org whose manifest says not live. Never loaded as a
  live org; WAYMARK10_ORG loads it from disk by name."
  (:require [waymark10.dsl :refer [defresource in-domain]]))

(defresource cog
  {:kind :cog
   :plural "cogs"
   :states [:new :done]
   :initial :new
   :terminal #{:done}
   :summary "{data.title} · {state}"
   :schema [:map [:title [:string {:min 1 :max 80}]]]
   :actions
   {:finish {:from #{:new} :to :done
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A finished cog stays finished."}}}})

(defn resources [] (in-domain :beta [cog]))
