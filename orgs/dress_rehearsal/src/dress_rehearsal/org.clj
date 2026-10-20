(ns dress_rehearsal.org
  "dress_rehearsal: the org's module. `resources` is the whole of what
  this org declares, stamped with its domain; the engine boots it, the
  conformance suite judges it. Nothing here is engine code.

  A small landscaping company (rehearsal brief of org_plan
  landscape_rehearsal): a `quote` is written after a site visit and
  accepted with a 30% deposit; a `job` is scheduled on one crew once
  the deposit clears; a maintenance `contract` generates a `visit`
  every 2 weeks in season; crew leads log a `time_entry` per job per
  day; a complete job is billed for its balance with an `invoice`."
  (:require [dress_rehearsal.kinds.quote :refer [quote-kind]]
            [dress_rehearsal.kinds.job :refer [job]]
            [dress_rehearsal.kinds.contract :refer [contract]]
            [dress_rehearsal.kinds.visit :refer [visit]]
            [dress_rehearsal.kinds.time_entry :refer [time_entry]]
            [dress_rehearsal.kinds.invoice :refer [invoice]]
            [waymark10.dsl :refer [in-domain]]))

(set! *warn-on-reflection* true)

;; org name = namespace root = domain name
(def domain :dress_rehearsal)

(defn resources
  "Every kind this org declares, stamped with its domain."
  []
  (in-domain domain [quote-kind job contract visit time_entry invoice]))

(defn check-resources
  "Zero-arg, so the declaration gate needs no env and no adapter."
  []
  (resources))
