(ns example.kinds.request
  "A request: a customer asks for work. The office accepts it, which
  is when a work order is written, or declines it."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-detail
  (refuse "A request is accepted once its detail says what the work is.")
  '(present? :detail))

(defresource request
  {:kind :request
   :plural "requests"
   :states [:new :accepted :declined]
   :initial :new
   :terminal #{:accepted :declined}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 120}]]
            [:customer {:optional true} [:maybe [:string {:max 80}]]]
            [:detail {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :filterable {:state #{:eq :in} :customer #{:eq}}
   ;; the office-clerk seat walks requests: its queue is the new ones
   :default-filters {:state "new"}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:new "A request may stay new for a day; past that, the office clerk tells Rosa Delgado."}
   :expected-age {:new 86400}
   :actions
   {:accept  {:from #{:new} :to :accepted
              :guards [has-detail]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "An accepted request stays accepted; its work order carries it from here."}
              :decided "The office clerk accepts a request on its own once its detail says what the work is."}
    :decline {:from #{:new} :to :declined
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A declined request is closed; the customer asks again with a new one."}
              :decided "The office clerk declines work this office does not do on its own, and says why."}}})
