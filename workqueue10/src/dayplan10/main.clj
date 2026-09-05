(ns dayplan10.main
  "The day-plan module: the :day domain's declarations, served by
  workqueue10 — the one household engine — the way eveningplan10's
  are (docs/spec-dayplan.md). This namespace is the registry the tests
  and the declaration gate assemble from; the enrolment itself is
  workqueue10.main/resources' `(in-domain :day …)` line."
  (:require [dayplan10.resources.block :refer [block]]
            [dayplan10.resources.context :refer [context]]
            [dayplan10.resources.day-plan :refer [day-plan]]
            [dayplan10.resources.span :refer [span]]))

(defn resources
  "All four kinds — the template, the day, its blocks, their windows;
  what the declaration gate assembles too. decision (slice .4) joins
  this vector when it lands."
  []
  [context day-plan block span])

(defn check-resources
  "Zero-arg so the declaration gate needs no env."
  []
  (resources))
