(ns dress-rehearsal.kinds.study
  "A study: one client's R&D credit study for one tax year. Records are
  collected, the AI drafts each project's narrative and the specialist
  confirms it with the client's engineering lead, the specialist
  calculates the credit, the principal signs it off, and it goes to the
  client's CPA, who files it. We never file returns ourselves."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard records-complete
  (refuse "Drafting starts once every record the study needs is in; say when the last one came.")
  '(present? :records_complete_on))

(defguard has-preparer
  (refuse "A study goes to review with the name of who prepared it.")
  '(present? :preparer))

(defguard has-credit
  (refuse "A study goes to review once the credit is calculated.")
  '(present? :credit_usd))

(defguard has-signer
  (refuse "The principal signs off every study, never the one who prepared it; name who signed.")
  '(present? :signed_off_by))

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
            [:preparer {:optional true} [:maybe [:string {:max 80}]]]
            [:credit_usd {:optional true} [:maybe [:int {:min 0}]]]
            [:signed_off_by {:optional true} [:maybe [:string {:max 80}]]]]
   :filterable {:state #{:eq :in} :client #{:eq} :tax_year #{:eq} :preparer #{:eq}}
   :actions
   {:records_in {:from #{:collecting} :to :drafting
                 :guards [records-complete]
                 :safety {:idempotent true :reversible false :confirm false
                          :one-way "Drafting has begun; a late record is added to the draft."}}
    :submit     {:from #{:drafting} :to :review
                 :guards [has-preparer has-credit]
                 :safety {:idempotent true :reversible true :confirm false}}
    :send_back  {:from #{:review} :to :drafting
                 :safety {:idempotent true :reversible true :confirm false}}
    :sign_off   {:from #{:review} :to :signed_off
                 :guards [has-signer]
                 :safety {:idempotent true :reversible false :confirm false
                          :one-way "A signed-off study is final; correct it with a new study."}}
    :deliver    {:from #{:signed_off} :to :delivered
                 :safety {:idempotent true :reversible false :confirm false
                          :one-way "The study and Form 6765 are with the client's CPA."}}}})
