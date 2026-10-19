(ns dress_rehearsal.kinds.assumption
  "An assumption: a best guess the firm runs on until the principal
  confirms it or changes it. The open ones are shown to the principal
  when he first signs in."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-answer
  (refuse "A changed assumption says what it is now; write the answer first.")
  '(present? :answer))

(defresource assumption
  {:kind :assumption
   :plural "assumptions"
   :states [:open :confirmed :changed]
   :initial :open
   :terminal #{:confirmed :changed}
   :summary "{data.topic} · {state}"
   :schema [:map
            [:topic [:enum "fee" "team" "tools" "reminders" "other"]]
            [:guess [:string {:min 1 :max 500}]]
            [:answer {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 1000}]]]]
   :filterable {:state #{:eq :in} :topic #{:eq}}
   :default-filters {:state "open"}
   :actions
   {:confirm {:from #{:open} :to :confirmed
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A confirmed assumption is settled; open a new one to revisit it."}}
    :change  {:from #{:open} :to :changed
              :guards [has-answer]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A changed assumption is settled at its answer."}}}})
