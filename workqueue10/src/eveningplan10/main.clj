(ns eveningplan10.main
  "The evening module: the :evenings domain's declarations, served by
  workqueue10 — the one household engine — since the consolidation
  cleanup (waymark-26j) folded the last standalone app. This
  namespace remains the registry the tests and the declaration gate
  assemble from; the plan-sessions consumer (eveningplan10.consumers)
  registers in workqueue10.main/start!."
  (:require [eveningplan10.resources.activity :refer [activity]]
            [eveningplan10.resources.evening-plan :refer [evening-plan]]
            [eveningplan10.resources.evening-session :refer [evening-session]]))

(defn resources
  "All three kinds — what the declaration gate assembles too."
  []
  [activity evening-plan evening-session])

(defn check-resources
  "Zero-arg so the declaration gate needs no env."
  []
  (resources))
