(ns landscape_rehearsal.org
  "landscape_rehearsal: the org's module. `resources` is the whole of
  what this org declares, stamped with its domain; the engine boots it,
  the conformance suite judges it. Nothing here is engine code.

  A landscaping company: a `booking` puts a crew at a customer's yard
  for a window, `time_off` keeps a crew away, and a signed `contract`
  makes its `visit` rows through the season (docs/spec-calendar-time.md
  §9). A crew is the engine's `team` row (docs/spec-teams.md step 11),
  and a `materials_order` names its outside supplier, who acts on it
  from their link (§11 step 3)."
  (:require [landscape_rehearsal.kinds.booking :refer [booking]]
            [landscape_rehearsal.kinds.time-off :refer [time-off]]
            [landscape_rehearsal.kinds.contract :refer [contract]]
            [landscape_rehearsal.kinds.visit :refer [visit]]
            [landscape_rehearsal.kinds.materials-order :refer [materials-order]]
            [waymark10.dsl :refer [in-domain]]))

(set! *warn-on-reflection* true)

;; org name = namespace root = domain name
(def domain :landscape_rehearsal)

(defn resources
  "Every kind this org declares, stamped with its domain."
  []
  (in-domain domain [booking time-off contract visit materials-order]))

(defn check-resources
  "Zero-arg, so the declaration gate needs no env and no adapter."
  []
  (resources))
