(ns door_time_inputs.org
  "door_time_inputs: a fixture org whose doors' guards read optional
  fields off the row that no door asks for — prospect.engage reads
  letter_signed_on, study.records_in reads records_complete_on and
  records_confirmed_by — the shape orgs/dress_rehearsal had before it
  moved them onto the doors as inputs."
  (:require [waymark10.dsl :refer [defguard defresource in-domain refuse]]))

(defguard letter-signed
  (refuse "Nothing starts until the client signs the engagement letter; record the day they signed.")
  '(present? :letter_signed_on))

(defguard records-complete
  (refuse "Record the day the cost records were complete.")
  '(present? :records_complete_on))

(defguard records-confirmed
  (refuse "Record who confirmed the cost records are complete.")
  '(present? :records_confirmed_by))

(defresource prospect
  {:kind :prospect
   :plural "prospects"
   :states [:qualified :engaged]
   :initial :qualified
   :terminal #{:engaged}
   :summary "{data.company} · {state}"
   :schema [:map
            [:company [:string {:min 1 :max 120}]]
            [:letter_signed_on {:optional true} [:maybe [:string {:max 10}]]]]
   :actions
   {:engage {:from #{:qualified} :to :engaged
             :guards [letter-signed]
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "An engaged prospect is a client."}}}})

(defresource study
  {:kind :study
   :plural "studies"
   :states [:collecting :drafting]
   :initial :collecting
   :terminal #{:drafting}
   :summary "{data.client} · {state}"
   :schema [:map
            [:client [:string {:min 1 :max 120}]]
            [:records_complete_on {:optional true} [:maybe [:string {:max 10}]]]
            [:records_confirmed_by {:optional true} [:maybe [:string {:max 80}]]]]
   :actions
   {:records_in {:from #{:collecting} :to :drafting
                 :guards [records-complete records-confirmed]
                 :safety {:idempotent true :reversible false :confirm false
                          :one-way "A study in drafting stays there."}}}})

(defn resources [] (in-domain :door_time_inputs [prospect study]))
