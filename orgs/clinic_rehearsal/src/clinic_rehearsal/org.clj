(ns clinic_rehearsal.org
  "clinic_rehearsal: the org's module. `resources` is the whole of what
  this org declares, stamped with its domain; the engine boots it, the
  conformance suite judges it. Nothing here is engine code.

  A small outpatient physical therapy clinic: a physician's `referral`
  opens a `patient` chart, a therapist's `plan_of_care` books each
  `visit`, and each visit becomes an insurer `claim`."
  (:require [clinic_rehearsal.kinds.referral :refer [referral]]
            [clinic_rehearsal.kinds.patient :refer [patient]]
            [clinic_rehearsal.kinds.plan-of-care :refer [plan-of-care]]
            [clinic_rehearsal.kinds.visit :refer [visit]]
            [clinic_rehearsal.kinds.claim :refer [claim]]
            [waymark10.dsl :refer [in-domain]]))

(set! *warn-on-reflection* true)

;; org name = namespace root = domain name
(def domain :clinic_rehearsal)

;; seconds a row may rest in a state its kind gives no :expected-age;
;; nil: such a state never ages
(def default-expected-age 86400)

(defn resources
  "Every kind this org declares, stamped with its domain."
  []
  (in-domain domain {:default-expected-age default-expected-age}
             [referral patient plan-of-care visit claim]))

(defn check-resources
  "Zero-arg, so the declaration gate needs no env and no adapter."
  []
  (resources))
