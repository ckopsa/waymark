(ns dress_rehearsal.kinds.visit
  "A visit: one maintenance call generated from a contract, on one
  crew and one day. Rain pushes it to the crew's next free business
  day, and the customer is told."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-contract
  (refuse "A visit is generated from a contract; name it.")
  '(and (present? :contract_id) (present? :crew) (present? :visit_on)))

(defguard customer-told
  (refuse "A rained-out visit moves to the crew's next free business day and the customer is told; record both.")
  '(present? :customer_told_on))

(defguard has-done-by
  (refuse "The crew lead marks a visit done; name who did.")
  '(present? :done_by))

(defresource visit
  {:kind :visit
   :plural "visits"
   :states [:planned :done :skipped]
   :initial :planned
   :terminal #{:done :skipped}
   :summary "{data.customer} · {data.crew} · {data.visit_on} · {state}"
   :schema [:map
            [:customer [:string {:min 1 :max 120}]]
            [:contract_id {:optional true :kind :contract} [:maybe :waymark/ref]]
            [:crew {:optional true} [:maybe [:enum "crew_1" "crew_2" "crew_3"]]]
            [:visit_on {:optional true} [:maybe [:string {:max 10}]]]
            [:customer_told_on {:optional true} [:maybe [:string {:max 10}]]]
            [:done_by {:optional true :x-ref {:principal true}} [:maybe [:string {:max 80}]]]
            [:hours {:optional true} [:maybe [:int {:min 0 :max 24}]]]]
   :filterable {:state #{:eq :in} :crew #{:eq} :visit_on #{:eq} :contract_id #{:eq}}
   :decided {:planned "A visit happens on its day; a visit still planned the day after, the mayor tells the office manager."}
   :actions
   {:rain_delay {:from #{:planned} :to :planned
                 :guards [has-contract customer-told]
                 :safety {:idempotent false :reversible true :confirm false}
                 :decided "Rain days push that day's jobs to the next free business day for that crew, and the customer is told."}
    :done       {:from #{:planned} :to :done
                 :guards [has-done-by]
                 :safety {:idempotent true :reversible false :confirm false
                          :one-way "A done visit is billed under its contract."}
                 :decided "The crew lead marks a visit done; the mayor may on its own when its hours are logged."}
    :skip       {:from #{:planned} :to :skipped
                 :safety {:idempotent true :reversible false :confirm false
                          :one-way "A skipped visit is not made up; the next one comes on schedule."}
                 :decided "The mayor skips a visit on its own when it falls on a company holiday, and says why."}}})
