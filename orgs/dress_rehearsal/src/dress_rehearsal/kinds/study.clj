(ns dress_rehearsal.kinds.study
  "A study: one client's R&D credit study for one tax year. Records are
  collected, the AI drafts each project's narrative and the specialist
  confirms it with the client's engineering lead, the specialist
  calculates the credit, the principal signs it off, and it goes to the
  client's CPA, who files it. We never file returns ourselves.

  While a study is collecting, the AI drafts each project's narrative as
  its engineering records arrive. The specialist, not the AI, decides
  when the cost records are complete, and that is what moves the study
  to drafting. A missing document is chased twice; after the second
  chase the principal is told."
  (:require [waymark10.dsl :refer [defguard defguardfn defresource refuse]]
            [waymark10.holds :as holds]
            [waymark10.types :as t]))

(defguard records-complete
  (refuse "Drafting starts once the cost records are complete; say when the last one came.")
  '(present? :records_complete_on))

(defguard records-confirmed
  (refuse "The specialist decides the cost records are complete; name who confirmed them.")
  '(present? :records_confirmed_by))

(defguard has-preparer
  (refuse "A study goes to review with the name of who prepared it.")
  '(present? :preparer))

(defguard has-credit
  (refuse "A study goes to review once the credit is calculated.")
  '(present? :credit_usd))

(defguard has-signer
  (refuse "The principal signs off every study, never the one who prepared it; name who signed.")
  '(present? :signed_off_by))

(defguardfn records-confirmed-by-a-person
  {:reads [:principal :within]
   :hold true
   :explain "The cost records are declared complete only on a person's tap: The Principal's for now, the tax specialist's once one is on file."
   :open "No door clears this one. The call waits as a held call for The Principal's tap."}
  [row _inp ctx]
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :study :records_in (:id row)) (t/allow)
    :else (t/deny)))

(defguardfn principal-signs-off
  {:reads [:principal :within]
   :hold true
   :explain "A study is signed off only on The Principal's tap, because the principal signs off every study, never the one who prepared it."
   :open "No door clears this one. The call waits as a held call for The Principal's tap."}
  [row _inp ctx]
  (cond
    (not= :agent (:type (:principal ctx))) (t/allow)
    (holds/approved-hold? ctx :study :sign_off (:id row)) (t/allow)
    :else (t/deny)))

(defresource study
  {:kind :study
   :plural "studies"
   :states [:collecting :drafting :review :signed_off :delivered]
   :initial :collecting
   :terminal #{:delivered}
   :summary "{data.client} {data.tax_year} · {state}"
   :schema [:map
            [:client [:string {:min 1 :max 120}]]
            [:tax_year [:int {:min 2000 :max 2100}]]
            [:prospect_id {:optional true :kind :prospect} [:maybe :waymark/ref]]
            [:cpa {:optional true} [:maybe [:string {:max 120}]]]
            [:records_due_on {:optional true} [:maybe [:string {:max 10}]]]
            [:filing_date {:optional true} [:maybe [:string {:max 10}]]]
            [:records_complete_on {:optional true} [:maybe [:string {:max 10}]]]
            [:records_confirmed_by {:optional true :x-ref {:principal true}} [:maybe [:string {:max 80}]]]
            [:missing_records {:optional true} [:maybe [:string {:max 500}]]]
            [:chases_sent {:optional true} [:maybe [:int {:min 0 :max 2}]]]
            [:principal_told_on {:optional true} [:maybe [:string {:max 10}]]]
            [:preparer {:optional true} [:maybe [:string {:max 80}]]]
            [:credit_usd {:optional true} [:maybe [:int {:min 0}]]]
            [:signed_off_by {:optional true :x-ref {:principal true}} [:maybe [:string {:max 80}]]]]
   :filterable {:state #{:eq :in} :client #{:eq} :tax_year #{:eq} :preparer #{:eq}}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:collecting "A study may stay collecting for 14 days: missing documents are chased twice, then the principal is told, and past 14 days the mayor tells The Principal."
             :drafting "A study may stay drafting for 7 days; past that, the mayor tells the tax specialist."
             :review "A study may stay in review for 3 days; past that, the mayor tells The Principal."
             :signed_off "A signed-off study may wait 2 days to be delivered; past that, the mayor tells The Principal."}
   :actions
   {:records_in {:from #{:collecting} :to :drafting
                 :guards [records-complete records-confirmed records-confirmed-by-a-person]
                 :safety {:idempotent true :reversible false :confirm false
                          :one-way "Drafting has begun; a late record is added to the draft."}}
    :submit     {:from #{:drafting} :to :review
                 :guards [has-preparer has-credit]
                 :safety {:idempotent true :reversible true :confirm false}}
    :send_back  {:from #{:review} :to :drafting
                 :safety {:idempotent true :reversible true :confirm false}}
    :sign_off   {:from #{:review} :to :signed_off
                 :guards [has-signer principal-signs-off]
                 :safety {:idempotent true :reversible false :confirm false
                          :one-way "A signed-off study is final; correct it with a new study."}}
    :deliver    {:from #{:signed_off} :to :delivered
                 :safety {:idempotent true :reversible false :confirm false
                          :one-way "The study and Form 6765 are with the client's CPA."}
                 :decided "The AI delivers a study to the client's CPA on its own once it is signed off; sign-off is the gate."}}})
