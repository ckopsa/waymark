(ns dress_rehearsal.kinds.prospect
  "A prospect: a company that may qualify for the R&D credit, sent by
  its CPA or found through the website. The qualification call says
  whether it likely passes the four-part test; nothing starts until it
  signs the engagement letter."
  (:require [waymark10.dsl :refer [defguard defguardfn defresource refuse]]
            [waymark10.holds :as holds]
            [waymark10.types :as t]))

(defguard has-estimate
  (refuse "A prospect is qualified once the call has given a rough credit estimate.")
  '(present? :estimated_credit_usd))

(defguard letter-signed
  (refuse "Nothing starts until the client signs the engagement letter; record the day they signed.")
  '(present? :letter_signed_on))

(defguardfn principal-engages
  {:reads [:principal :within]
   :hold true
   :explain "A prospect is engaged only on The Principal's tap, because nothing starts until the client signs the engagement letter: the AI's call is held for The Principal."
   :open "No door clears this one. The call waits as a held call for The Principal's tap."}
  [row _inp ctx]
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :prospect :engage (:id row)) (t/allow)
    :else (t/deny)))

(defguardfn principal-declines
  {:reads [:principal :within]
   :hold true
   :explain "A prospect is declined only on The Principal's tap; the AI never declines one on its own."
   :open "No door clears this one. The call waits as a held call for The Principal's tap."}
  [row _inp ctx]
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :prospect :decline (:id row)) (t/allow)
    :else (t/deny)))

(defresource prospect
  {:kind :prospect
   :plural "prospects"
   :states [:new :qualified :engaged :declined]
   :initial :new
   :terminal #{:engaged :declined}
   :summary "{data.company} · {state}"
   :schema [:map
            [:company [:string {:min 1 :max 120}]]
            [:source {:optional true} [:maybe [:enum "cpa_referral" "website" "returning"]]]
            [:referring_cpa {:optional true} [:maybe [:string {:max 120}]]]
            [:estimated_credit_usd {:optional true} [:maybe [:int {:min 0}]]]
            [:letter_signed_on {:optional true} [:maybe [:string {:max 10}]]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :filterable {:state #{:eq :in} :source #{:eq} :referring_cpa #{:eq}}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:new "A prospect may stay new for 2 days; past that, the mayor tells The Principal."
             :qualified "A prospect may stay qualified for 14 days while the engagement letter is unsigned; past that, the mayor tells The Principal."}
   :actions
   {:qualify {:from #{:new} :to :qualified
              :guards [has-estimate]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A qualified prospect stays qualified; decline it if the letter is never signed."}
              :decided "The AI qualifies a prospect on its own once the call has given a rough credit estimate."}
    :engage  {:from #{:qualified} :to :engaged
              :guards [letter-signed principal-engages]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "An engaged prospect is a client; its studies carry it from here."}}
    :decline {:from #{:new :qualified} :to :declined
              :guards [principal-declines]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A declined prospect is closed; open a new one if they come back."}}}})
