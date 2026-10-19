(ns two_words.kinds.thing
  "A thing: new until it is done."
  (:require [waymark10.dsl :refer [defresource]]))

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
