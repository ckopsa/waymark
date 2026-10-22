(ns week_fixture.kinds.patient
  "A patient and their visit, clinic-shaped (ticket 5a0bc8f3): the admit
  door files the patient's consent form, a document, and a visit is
  signed only once that form is stored, read through `:patient_id`
  (`:evidence`). `workqueue10.week-test` plays them with `:attach`."
  (:require [waymark10.dsl :refer [defresource]]
            [waymark10.resource :as r]))

(defresource patient
  {:kind :patient
   :plural "patients"
   :states [:waiting :admitted]
   :initial :waiting
   :terminal #{:admitted}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:min 1 :max 80}]]
            [:consent_form {:optional true
                            :waymark/document {:accept ["application/pdf" "image/*"] :personal false}}
             [:maybe :waymark/ref]]]
   :filterable {:state #{:eq :in}}
   :default-filters {:state "waiting"}
   ;; the owner's answers to the org audit's questions (factorykit.conform)
   :decided {:waiting "A patient may wait a day to be admitted; past that, the front desk tells Rosa Delgado."}
   :expected-age {:waiting 86400}
   :actions
   {:admit {:from #{:waiting} :to :admitted
            :input [:map [:consent_form {:optional true :kind :attachment} [:maybe :waymark/ref]]]
            :handler (r/field-writer [:consent_form])
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "An admitted patient stays admitted."}
            :decided "The front desk admits a patient on its own, filing the consent form they bring."}}})

(defresource visit
  {:kind :clinic_visit
   :plural "clinic_visits"
   :states [:open :signed]
   :initial :open
   :terminal #{:signed}
   :summary "{data.reason} · {state}"
   :schema [:map
            [:patient_id {:kind :patient} :waymark/ref]
            [:reason [:string {:min 1 :max 120}]]]
   :filterable {:state #{:eq :in} :patient_id #{:eq}}
   :default-filters {:state "open"}
   :decided {:open "A visit may stay open a day; past that, the front desk tells Rosa Delgado."}
   :expected-age {:open 86400}
   :actions
   {:sign {:from #{:open} :to :signed
           :evidence {:consent_form {:via :patient_id}}
           :safety {:idempotent true :reversible false :confirm false
                    :one-way "A signed visit stays signed; a correction is a new visit."}
           :decided "The doctor signs a visit on their own once the patient's consent form is on file."}}})
