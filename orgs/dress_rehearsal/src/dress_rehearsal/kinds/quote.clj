(ns dress_rehearsal.kinds.quote
  "A quote: the office manager visits the customer, writes labor,
  materials and a price, and sends it. The owner approves any quote
  over $10,000 before it goes out. A quote is good for 30 days; the
  customer accepts it by paying a 30% deposit online."
  (:require [waymark10.dsl :refer [defguard defresource refuse hold]]))

(defguard has-price
  (refuse "A quote goes out with labor, materials and a price; fill in all three.")
  '(and (present? :labor_cents) (present? :materials_cents) (present? :price_cents)))

(defguard has-visit
  (refuse "The office manager visits before quoting; record the day of the visit.")
  '(present? :visited_on))

(defguard owner-approves-large
  (hold "The owner approves any quote over $10,000 before it goes out.")
  '(<= :price_cents 1000000))

(defguard has-valid-until
  (refuse "A quote is good for 30 days; record the day it lapses.")
  '(present? :valid_until))

(defguard deposit-cleared
  (refuse "A quote is accepted once the 30% deposit has cleared; record the day it cleared.")
  '(present? :deposit_cleared_on))

(defresource quote-kind
  {:kind :quote
   :plural "quotes"
   :states [:drafting :sent :accepted :declined :expired]
   :initial :drafting
   :terminal #{:accepted :declined :expired}
   :summary "{data.customer} · {data.work} · {state}"
   :schema [:map
            [:customer [:string {:min 1 :max 120}]]
            [:work [:enum "patio" "planting" "irrigation" "design_build" "maintenance"]]
            [:site_address {:optional true} [:maybe [:string {:max 240}]]]
            [:visited_on {:optional true} [:maybe [:string {:max 10}]]]
            [:labor_cents {:optional true} [:maybe [:int {:min 0}]]]
            [:materials_cents {:optional true} [:maybe [:int {:min 0}]]]
            [:price_cents {:optional true} [:maybe [:int {:min 0}]]]
            [:deposit_cents {:optional true} [:maybe [:int {:min 0}]]]
            [:sent_on {:optional true} [:maybe [:string {:max 10}]]]
            [:valid_until {:optional true} [:maybe [:string {:max 10}]]]
            [:deposit_cleared_on {:optional true} [:maybe [:string {:max 10}]]]
            [:signed_quote_url {:optional true} [:maybe [:string {:max 500}]]]
            [:keep_until {:optional true} [:maybe [:string {:max 10}]]]]
   :filterable {:state #{:eq :in} :customer #{:eq} :work #{:eq}}
   :decided {:drafting "A quote is written within a week of the visit; past that, the mayor tells the office manager."
             :sent "A quote is good for 30 days; on its 31st day the mayor marks it expired."}
   :actions
   {:send    {:from #{:drafting} :to :sent
              :guards [has-visit has-price has-valid-until owner-approves-large]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A sent quote is with the customer; change it by sending a new quote."}
              :decided "The office manager sends a quote; the owner approves any quote over $10,000 before it goes out."}
    :accept  {:from #{:sent} :to :accepted
              :guards [deposit-cleared]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "An accepted quote becomes a job; the deposit is held against it."}
              :decided "When the customer accepts, they pay a 30% deposit online; the mayor accepts the quote on its own once it clears."}
    :decline {:from #{:drafting :sent} :to :declined
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A declined quote is closed; quote again if the customer comes back."}
              :decided "The mayor declines a quote on its own when the customer says no, and says why."}
    :expire  {:from #{:sent} :to :expired
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "An expired quote is closed; requote at today's prices."}
              :decided "A quote is good for 30 days; the mayor expires it on its own after that."}}})
