(ns dress_rehearsal.kinds.job
  "A job: the design-and-build work of one accepted quote. It is
  scheduled only once the deposit has cleared, on one crew, with the
  heavy equipment it needs. A crew is on one job at a time, and the
  mini excavator and the skid steer are on one job a day. Materials
  are ordered when it is scheduled and must arrive 2 business days
  before the start. Rain pushes the day's work to the crew's next free
  business day, and the customer is told. Crew leads log hours daily;
  the owner sees a job running more than 15% over its quote. The crew
  lead marks it complete with photos, and the balance is invoiced."
  (:require [waymark10.dsl :refer [defguard defresource refuse]]))

(defguard has-quote
  (refuse "A job is built from an accepted quote; name the quote.")
  '(present? :quote_id))

(defguard deposit-cleared
  (refuse "A job is scheduled only once the deposit has cleared; record the day it cleared.")
  '(present? :deposit_cleared_on))

(defguard has-crew-and-start
  (refuse "A job is scheduled on one crew and a start day; name both.")
  '(and (present? :crew) (present? :start_on)))

(defguard crew-free
  (refuse "A crew can be on only one job at a time; that crew is booked then, so pick a free crew.")
  '(present? :crew_checked_free_by))

(defguard equipment-free
  (refuse "The mini excavator and the skid steer are on one job a day; that day the equipment is booked elsewhere.")
  '(present? :equipment_checked_free_by))

(defguard materials-ordered
  (refuse "Materials are ordered from the supplier when the job is scheduled; record the order.")
  '(present? :materials_order_ref))

(defguard has-photos
  (refuse "The crew lead marks a job complete with photos; attach them.")
  '(present? :photos_url))

(defguard has-completer
  (refuse "The crew lead marks the job complete; name who did.")
  '(present? :completed_by))

(defguard has-rain-day
  (refuse "A rain day pushes the job to the crew's next free business day; record the new start and that the customer was told.")
  '(and (present? :start_on) (present? :customer_told_on)))

(defresource job
  {:kind :job
   :plural "jobs"
   :states [:awaiting_deposit :ready :scheduled :in_progress :complete :cancelled]
   :initial :awaiting_deposit
   :terminal #{:complete :cancelled}
   :summary "{data.customer} · {data.crew} · {data.start_on} · {state}"
   :schema [:map
            [:customer [:string {:min 1 :max 120}]]
            [:quote_id {:optional true :kind :quote} [:maybe :waymark/ref]]
            [:quoted_cents {:optional true} [:maybe [:int {:min 0}]]]
            [:deposit_cleared_on {:optional true} [:maybe [:string {:max 10}]]]
            [:crew {:optional true} [:maybe [:enum "crew_1" "crew_2" "crew_3"]]]
            [:needs_excavator {:optional true} [:maybe :boolean]]
            [:needs_skid_steer {:optional true} [:maybe :boolean]]
            [:start_on {:optional true} [:maybe [:string {:max 10}]]]
            [:end_on {:optional true} [:maybe [:string {:max 10}]]]
            [:crew_checked_free_by {:optional true :x-ref {:principal true}} [:maybe [:string {:max 80}]]]
            [:equipment_checked_free_by {:optional true :x-ref {:principal true}} [:maybe [:string {:max 80}]]]
            [:materials_order_ref {:optional true} [:maybe [:string {:max 120}]]]
            [:materials_needed_by {:optional true} [:maybe [:string {:max 10}]]]
            [:customer_told_on {:optional true} [:maybe [:string {:max 10}]]]
            [:hours_logged {:optional true} [:maybe [:int {:min 0}]]]
            [:actual_cost_cents {:optional true} [:maybe [:int {:min 0}]]]
            [:photos_url {:optional true} [:maybe [:string {:max 500}]]]
            [:completed_by {:optional true :x-ref {:principal true}} [:maybe [:string {:max 80}]]]
            [:completed_on {:optional true} [:maybe [:string {:max 10}]]]
            [:warranty_until {:optional true} [:maybe [:string {:max 10}]]]
            [:keep_until {:optional true} [:maybe [:string {:max 10}]]]]
   :filterable {:state #{:eq :in} :crew #{:eq} :start_on #{:eq} :customer #{:eq}}
   :decided {:awaiting_deposit "A job waits for its deposit as long as its quote is good, 30 days; then the mayor cancels it."
             :ready "A job with a cleared deposit is scheduled within a week; past that, the mayor tells the office manager."
             :scheduled "A scheduled job starts on its start day; when its materials have not arrived 2 business days before, the mayor tells the office manager."
             :in_progress "A job running more than 15% over its quote is shown to the owner."}
   :actions
   {:clear_deposit {:from #{:awaiting_deposit} :to :ready
                    :guards [has-quote deposit-cleared]
                    :safety {:idempotent true :reversible false :confirm false
                             :one-way "The deposit has cleared and is held against this job."}
                    :decided "The deposit clears through the online processor; the mayor moves the job on its own when it does."}
    :schedule      {:from #{:ready} :to :scheduled
                    :guards [has-crew-and-start crew-free equipment-free materials-ordered]
                    :safety {:idempotent true :reversible true :confirm false}
                    :decided "The office manager schedules a job; the mayor may schedule it on its own when the crew and equipment are free."}
    :rain_delay    {:from #{:scheduled :in_progress} :to :scheduled
                    :guards [has-rain-day]
                    :safety {:idempotent false :reversible true :confirm false}
                    :decided "Rain days push that day's jobs to the next free business day for that crew, and the customer is told."}
    :start         {:from #{:scheduled} :to :in_progress
                    :safety {:idempotent true :reversible false :confirm false
                             :one-way "Work has begun on site."}
                    :decided "The crew lead starts the job on its start day; the mayor may start it on its own that morning."}
    :complete      {:from #{:in_progress} :to :complete
                    :guards [has-photos has-completer]
                    :safety {:idempotent true :reversible false :confirm false
                             :one-way "A complete job is invoiced for its balance; warranty work is a new job."}
                    :decided "When a job is done, the crew lead marks it complete with photos."}
    :cancel        {:from #{:awaiting_deposit :ready :scheduled} :to :cancelled
                    :safety {:idempotent true :reversible false :confirm false
                             :one-way "A cancelled job frees its crew and equipment; quote again to restart."}
                    :decided "The mayor cancels a job on its own when its deposit never comes within the quote's 30 days, and says why."}}})
