(ns dress_rehearsal.kinds.prospect
  "A prospect: a company that may qualify for the R&D credit, sent by
  its CPA or found through the website. The qualification call says
  whether it likely passes the four-part test; nothing starts until it
  signs the engagement letter."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-estimate
  (refuse "A prospect is qualified once the call has given a rough credit estimate.")
  '(present? :estimated_credit_usd))

(defguard letter-signed
  (refuse "Nothing starts until the client signs the engagement letter; record the day they signed.")
  '(present? :letter_signed_on))

(defresource prospect
  {:kind :prospect
   :plural "prospects"
   :states [:new :qualified :engaged :declined]
   :initial :new
   :terminal #{:engaged :declined}
   :summary "{data.company} · {state}"
   ;; a film's day reads the row as it stood: its fields, not only its state
   :retain {:data true}
   :schema [:map
            [:company [:string {:min 1 :max 120}]]
            [:source {:optional true} [:maybe [:enum "cpa_referral" "website" "returning"]]]
            [:referring_cpa {:optional true} [:maybe [:string {:max 120}]]]
            [:estimated_credit_usd {:optional true} [:maybe [:int {:min 0}]]]
            [:letter_signed_on {:optional true} [:maybe [:string {:max 10}]]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :filterable {:state #{:eq :in} :source #{:eq} :referring_cpa #{:eq}}
   :actions
   {:qualify {:from #{:new} :to :qualified
              :guards [has-estimate]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A qualified prospect stays qualified; decline it if the letter is never signed."}}
    :engage  {:from #{:qualified} :to :engaged
              :guards [letter-signed]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "An engaged prospect is a client; its studies carry it from here."}}
    :decline {:from #{:new :qualified} :to :declined
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A declined prospect is closed; open a new one if they come back."}}}})
