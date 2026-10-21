(ns dress_rehearsal.kinds.prospect
  "A prospect: a company that may qualify for the R&D credit, sent by
  its CPA or found through the website. The qualification call says
  whether it likely passes the four-part test; nothing starts until it
  signs the engagement letter."
  (:require [waymark10.dsl :refer [defguard defguardfn defresource defscenario refuse]]
            [waymark10.holds :as holds]
            [waymark10.types :as t]))

(defguard has-estimate
  (refuse "A prospect is qualified once the call has given a rough credit estimate.")
  '(is-set (input :estimated_credit_usd)))

(defguard letter-signed
  (refuse "Nothing starts until the client signs the engagement letter; record the day they signed.")
  '(is-set (input :letter_signed_on)))

(defguard letter-signed-already
  (refuse "The engagement letter is signed on a day that has come, not one still ahead; record the day they signed.")
  '(<= (input :letter_signed_on) (today)))

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
   ;; a film's day reads the row as it stood: its fields, not only its state
   :retain {:data true}
   :schema [:map
            [:company [:string {:min 1 :max 120}]]
            [:source {:optional true} [:maybe [:enum "cpa_referral" "website" "returning"]]]
            [:referring_cpa {:optional true} [:maybe [:string {:max 120}]]]
            [:estimated_credit_usd {:optional true} [:maybe [:int {:min 0}]]]
            [:letter_signed_on {:optional true} [:maybe :waymark/date]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]]
   :filterable {:state #{:eq :in} :source #{:eq} :referring_cpa #{:eq}}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:new "A prospect may stay new for 2 days; past that, the mayor tells The Principal."
             :qualified "A prospect may stay qualified for 14 days while the engagement letter is unsigned; past that, the mayor tells The Principal."}
   :actions
   {:qualify {:from #{:new} :to :qualified
              :input [:map [:estimated_credit_usd [:int {:min 0}]]]
              :guards [has-estimate]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A qualified prospect stays qualified; decline it if the letter is never signed."}
              :decided "The AI qualifies a prospect on its own once the call has given a rough credit estimate."}
    :engage  {:from #{:qualified} :to :engaged
              :input [:map [:letter_signed_on :waymark/date]]
              :guards [letter-signed letter-signed-already principal-engages]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "An engaged prospect is a client; its studies carry it from here."}}
    :decline {:from #{:new :qualified} :to :declined
              :guards [principal-declines]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A declined prospect is closed; open a new one if they come back."}}}})

(defscenario engage-with-the-day-signed
  "A qualified prospect is engaged once the day the client signed the letter is recorded."
  {:kind :prospect :attempt :engage
   :row {:state :qualified :data {:company "Acme Robotics" :estimated_credit_usd 80000}}
   :input {:letter_signed_on "2026-10-20"}
   :at "2026-10-21T16:00:00Z"
   :calendar {:zone "America/Denver"}
   :expect {:allowed true}})

(defscenario engage-refused-without-the-day-signed
  "A qualified prospect is not engaged while no signing day is recorded."
  {:kind :prospect :attempt :engage
   :row {:state :qualified :data {:company "Acme Robotics" :estimated_credit_usd 80000}}
   :input {}
   :at "2026-10-21T16:00:00Z"
   :calendar {:zone "America/Denver"}
   :expect {:refused :letter-signed}})
