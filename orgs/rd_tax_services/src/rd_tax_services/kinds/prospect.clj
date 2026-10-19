(ns rd_tax_services.kinds.prospect
  "A prospect: a company that may claim the R&D credit, sent by its CPA
  or found through the website. The free qualification call and the
  questionnaire qualify it; it is engaged once the client signs the
  principal's engagement letter, and nothing starts before that."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-estimate
  (refuse "A prospect is qualified once the call and questionnaire give a rough credit estimate.")
  '(present? :estimated_credit_usd))

(defguard letter-signed
  (refuse "Nothing starts until the client signs the engagement letter; record the date it was signed.")
  '(present? :letter_signed_on))

(defresource prospect
  {:kind :prospect
   :plural "prospects"
   :states [:new :qualified :engaged :declined]
   :initial :new
   :terminal #{:engaged :declined}
   :summary "{data.company} · {state}"
   :schema [:map
            [:company [:string {:min 1 :max 120}]]
            [:contact {:optional true} [:maybe [:string {:max 120}]]]
            [:source {:optional true} [:maybe [:string {:max 40}]]]
            [:referring_cpa_id {:optional true :kind :referring_cpa} [:maybe :waymark/ref]]
            [:estimated_credit_usd {:optional true} [:maybe [:int {:min 0}]]]
            [:letter_signed_on {:optional true} [:maybe [:string {:max 10}]]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :filterable {:state #{:eq :in} :source #{:eq} :referring_cpa_id #{:eq}}
   :default-filters {:state "new"}
   :actions
   {:qualify {:from #{:new} :to :qualified
              :guards [has-estimate]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A qualified prospect waits for the principal's engagement letter."}}
    :engage  {:from #{:qualified} :to :engaged
              :guards [letter-signed]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "An engaged prospect is a client; its study carries the work from here."}}
    :decline {:from #{:new :qualified} :to :declined
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A declined prospect is closed; a new prospect is opened if they come back."}}}})
