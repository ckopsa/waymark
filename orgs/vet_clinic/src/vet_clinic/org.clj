(ns vet_clinic.org
  "vet_clinic: the org's module. `resources` is the whole of what this
  org declares, stamped with its domain.

  A veterinary clinic answering emailed business inquiries: each
  `inquiry` is read and sorted by the replies seat, answered on its own
  when it asks only a fixed public fact, held for the owner's tap when
  it touches pricing, and handed to staff as urgent when it is about a
  sick or injured animal. `clinic_fact` holds what the replies may say."
  (:require [vet_clinic.kinds.clinic-fact :refer [clinic-fact]]
            [vet_clinic.kinds.inquiry :refer [inquiry]]
            [waymark10.dsl :refer [in-domain]]))

(set! *warn-on-reflection* true)

;; org name = namespace root = domain name
(def domain :vet_clinic)

;; nil: a state its kind gives no :expected-age never ages; how long a
;; row may wait is the owner's to say
(def default-expected-age nil)

(defn resources
  "Every kind this org declares, stamped with its domain."
  []
  (in-domain domain {:default-expected-age default-expected-age}
             [inquiry clinic-fact]))

(defn check-resources
  "Zero-arg, so the declaration gate needs no env and no adapter."
  []
  (resources))
