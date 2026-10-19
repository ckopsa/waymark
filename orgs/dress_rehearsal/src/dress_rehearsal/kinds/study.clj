(ns dress_rehearsal.kinds.study
  "A study: one client's credit for one tax year. Records come in, the
  AI drafts each project's narrative, the specialist interviews the
  engineering lead and calculates the credit, the principal signs it
  off, and the report and Form 6765 go to the client's CPA."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-preparer
  (refuse "A study is prepared by someone; name the preparer first.")
  '(present? :preparer))

(defguard has-credit
  (refuse "A study goes to review once its credit is calculated.")
  '(present? :credit_usd))

(defguard has-cpa
  (refuse "A study is delivered to the client's CPA; name the CPA first.")
  '(present? :referrer_id))

(defresource study
  {:kind :study
   :plural "studies"
   :states [:collecting :drafting :interviewing :calculating :in_review :delivered :abandoned]
   :initial :collecting
   :terminal #{:delivered :abandoned}
   :summary "{data.client} {data.tax_year} · {state}"
   :schema [:map
            [:client [:string {:min 1 :max 120}]]
            [:tax_year [:int {:min 2000 :max 2100}]]
            [:engagement_id {:optional true :kind :engagement} [:maybe :waymark/ref]]
            [:prior_study_id {:optional true :kind :study} [:maybe :waymark/ref]]
            [:referrer_id {:optional true :kind :referrer} [:maybe :waymark/ref]]
            [:filing_date {:optional true} [:maybe [:string {:max 10}]]]
            [:records_due {:optional true} [:maybe [:string {:max 10}]]]
            [:preparer {:optional true} [:maybe [:string {:max 80}]]]
            [:qre_usd {:optional true} [:maybe [:int {:min 0}]]]
            [:credit_usd {:optional true} [:maybe [:int {:min 0}]]]
            [:payroll_offset {:optional true} [:maybe :boolean]]]
   :filterable {:state #{:eq :in} :tax_year #{:eq} :preparer #{:eq} :referrer_id #{:eq}}
   :actions
   {:records_in   {:from #{:collecting} :to :drafting
                   :safety {:idempotent true :reversible false :confirm false
                            :one-way "Records are in; the narratives are drafted from here."}}
    :drafted      {:from #{:drafting} :to :interviewing
                   :safety {:idempotent true :reversible false :confirm false
                            :one-way "The drafts go to the engineering lead's interview."}}
    :interviewed  {:from #{:interviewing} :to :calculating
                   :guards [has-preparer]
                   :safety {:idempotent true :reversible false :confirm false
                            :one-way "The narratives are confirmed; the credit is calculated from here."}}
    :calculated   {:from #{:calculating} :to :in_review
                   :guards [has-credit]
                   :safety {:idempotent true :reversible false :confirm false
                            :one-way "The study is with the principal for sign-off."}}
    :send_back    {:from #{:in_review} :to :calculating
                   :safety {:idempotent true :reversible true :confirm false}}
    :sign_off     {:from #{:in_review} :to :delivered
                   :guards [has-cpa]
                   :safety {:idempotent true :reversible false :confirm true
                            :consequence "The study report and Form 6765 go to the client's CPA."
                            :one-way "A delivered study stays delivered; its support is kept three years."}}
    :abandon      {:from #{:collecting :drafting :interviewing :calculating :in_review} :to :abandoned
                   :safety {:idempotent true :reversible false :confirm false
                            :one-way "An abandoned study is let go; open a new one to start again."}}}})
