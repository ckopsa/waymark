(ns rd_tax_services.kinds.study
  "A study: one client's R&D credit study for one tax year, one study
  per client per tax year. Records are collected (payroll, contractor
  invoices, cloud and supply costs, engineering records), the AI drafts
  each project's narrative and the specialist confirms it with the
  client's engineering lead, the specialist calculates the QREs and the
  credit, the principal reviews and signs off (never the preparer), and
  the report and Form 6765 are delivered to the client's CPA. We never
  file returns. A new year's study starts from last year's."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard records-in
  (refuse "Drafting starts once the client's records are in; note what was received.")
  '(present? :records_received_on))

(defguard qre-calculated
  (refuse "A study goes to review once the specialist has calculated the qualified research expenses.")
  '(present? :qre_usd))

(defguard credit-calculated
  (refuse "A study goes to review once the specialist has calculated the credit.")
  '(present? :credit_usd))

(defguard reviewer-named
  (refuse "The principal signs off a study; name the principal who reviewed it, who is never its preparer.")
  '(present? :reviewer))

(defguard cpa-named
  (refuse "A study is delivered to the client's CPA; name the CPA first.")
  '(present? :referring_cpa_id))

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
            [:referring_cpa_id {:optional true :kind :referring_cpa} [:maybe :waymark/ref]]
            [:prior_study_id {:optional true :kind :study} [:maybe :waymark/ref]]
            [:preparer {:optional true} [:maybe [:string {:max 80}]]]
            [:reviewer {:optional true} [:maybe [:string {:max 80}]]]
            [:records_due_on {:optional true} [:maybe [:string {:max 10}]]]
            [:filing_date {:optional true} [:maybe [:string {:max 10}]]]
            [:records_received_on {:optional true} [:maybe [:string {:max 10}]]]
            [:missing_records {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 2000}]]]
            [:chases_sent {:optional true} [:maybe [:int {:min 0}]]]
            [:qre_usd {:optional true} [:maybe [:int {:min 0}]]]
            [:credit_usd {:optional true} [:maybe [:int {:min 0}]]]
            [:payroll_offset_usd {:optional true} [:maybe [:int {:min 0}]]]
            [:notes {:optional true :x-display {:widget "prose"}}
             [:maybe [:string {:max 4000}]]]]
   :filterable {:state #{:eq :in} :client #{:eq} :tax_year #{:eq}
                :referring_cpa_id #{:eq} :preparer #{:eq}}
   :actions
   {:start_drafting {:from #{:collecting} :to :drafting
                     :guards [records-in]
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "Once drafting starts, missing records are asked for in the notes, not by going back."}}
    :submit_review  {:from #{:drafting} :to :review
                     :guards [qre-calculated credit-calculated]
                     :safety {:idempotent true :reversible true :confirm false}}
    :return         {:from #{:review} :to :drafting
                     :safety {:idempotent true :reversible true :confirm false}}
    :sign_off       {:from #{:review} :to :signed_off
                     :guards [reviewer-named]
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "A signed-off study is final; a correction is a new review."}}
    :deliver        {:from #{:signed_off} :to :delivered
                     :guards [cpa-named]
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "The study report and Form 6765 have gone to the client's CPA; its support is kept at least three years."}}}})
