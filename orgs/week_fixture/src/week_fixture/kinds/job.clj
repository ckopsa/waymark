(ns week_fixture.kinds.job
  "A job: a homeowner asks for garden work. The office quotes it once
  the homeowner says their budget, and it is booked on the owner's
  tap, or declined."
  (:require [waymark10.dsl :refer [defguard defresource hold refuse]]))

(defguard has-budget
  (refuse "A job is quoted once the homeowner says their budget.")
  '(present? :budget_usd))

(defguard owner-books
  (hold "A job is booked only on the owner's tap."))

(defresource job
  {:kind :garden_job
   :plural "garden_jobs"
   :states [:asked :quoted :booked :declined]
   :initial :asked
   :terminal #{:booked :declined}
   :summary "{data.title} · {state}"
   :schema [:map
            [:title [:string {:min 1 :max 120}]]
            [:budget_usd {:optional true} [:maybe [:int {:min 0}]]]
            [:follows {:optional true :kind :garden_job} [:maybe :waymark/ref]]]
   :filterable {:state #{:eq :in} :follows #{:eq}}
   :default-filters {:state "asked"}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:asked "A job may stay asked for a day; past that, the job clerk tells Rosa Delgado."
             :quoted "A quoted job may wait a week for Rosa Delgado's tap."}
   :expected-age {:asked 86400 :quoted 604800}
   :actions
   {:quote   {:from #{:asked} :to :quoted
              :guards [has-budget]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A quoted job stays quoted until it is booked or declined."}
              :decided "The job clerk quotes a job on its own once the homeowner says their budget."}
    :book    {:from #{:quoted} :to :booked
              :guards [owner-books]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A booked job stays booked; the crew does it from here."}}
    :decline {:from #{:asked :quoted} :to :declined
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A declined job is closed; the homeowner asks again with a new one."}
              :decided "The job clerk declines work this office does not do on its own, and says why."}}})
