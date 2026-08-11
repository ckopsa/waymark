(ns choreplan10.main
  "The chore module: the :chores domain's declarations, served by
  workqueue10 — the one household engine — since the bwu.1 fold. The
  standalone boot retired with the consolidation cleanup
  (waymark-26j); this namespace remains the registry the tests and
  the declaration gate assemble from, including the pre-fold
  prep_task mirror the mirror tests still exercise (the served
  prep_task is mealplan10's native kind since bwu stage 2)."
  (:require [choreplan10.mirror.mealplan :as mp]
            [choreplan10.resources.chore :refer [chore]]
            [choreplan10.resources.chore-run :refer [chore-run]]
            [choreplan10.resources.day :refer [day day-board]]
            [choreplan10.resources.prep-task :refer [prep-task-resource]]
            [waymark10.server.oidc :as oidc]))

(defonce fake-feed
  ;; the module-default fake boundary — tests script it, offline dev
  ;; and the declaration gate run over it
  (mp/fake-feed))

(defn feed
  "The mealplan10 boundary: real over CHOREPLAN10_MEALPLAN_URL, the
  in-memory fake otherwise."
  []
  (if-some [url (System/getenv "CHOREPLAN10_MEALPLAN_URL")]
    (mp/http-feed {:url url
                   :assignee (System/getenv "CHOREPLAN10_MEALPLAN_ASSIGNEE")
                   :principal (System/getenv "CHOREPLAN10_MEALPLAN_PRINCIPAL")
                   :token (System/getenv "CHOREPLAN10_MEALPLAN_TOKEN")
                   ;; production's bearer: this engine's OWN client
                   ;; mints against mealplan's audience scope, fresh
                   ;; every hour (waymark-mvl); a static _TOKEN wins
                   :token-fn (oidc/outbound-token-fn "waymark-mealplan10")})
    fake-feed))

(defn resources
  "All four kinds — what `make check-chores` (waymark10.check)
  assembles too."
  [feed]
  [chore chore-run day (prep-task-resource feed)])

(def surfaces
  "The declared decision screens (phase 9b): the housekeeper's day
  board."
  [day-board])

(defn check-resources
  "Zero-arg so the declaration gate needs no env — every kind over
  the offline fake."
  []
  (resources fake-feed))
