(ns dress_rehearsal.kinds.engagement
  "An engagement: a prospect is qualified on a free call and a
  questionnaire, the principal sends a letter, and nothing starts
  until the client signs it."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-estimate
  (refuse "A letter goes out once the qualification call has an estimated credit.")
  '(present? :estimated_credit_usd))

(defresource engagement
  {:kind :engagement
   :plural "engagements"
   :states [:qualifying :letter_sent :signed :declined]
   :initial :qualifying
   :terminal #{:signed :declined}
   :summary "{data.client} · {state}"
   :schema [:map
            [:client [:string {:min 1 :max 120}]]
            [:source {:optional true} [:maybe [:enum "referral" "website" "returning"]]]
            [:referrer_id {:optional true :kind :referrer} [:maybe :waymark/ref]]
            [:estimated_credit_usd {:optional true} [:maybe [:int {:min 0}]]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :filterable {:state #{:eq :in} :referrer_id #{:eq}}
   :actions
   {:send_letter {:from #{:qualifying} :to :letter_sent
                  :guards [has-estimate]
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A sent letter is with the client; decline the engagement to withdraw it."}}
    :sign        {:from #{:letter_sent} :to :signed
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A signed engagement starts the work; its study carries it from here."}}
    :decline     {:from #{:qualifying :letter_sent} :to :declined
                  :safety {:idempotent true :reversible false :confirm false
                           :one-way "A declined engagement is closed; open a new one to ask again."}}}})
