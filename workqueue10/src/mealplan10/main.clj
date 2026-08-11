(ns mealplan10.main
  "The meal-plan module: the :meals domain's declarations, served by
  workqueue10 — the one household engine — since the bwu.2 fold. The
  standalone boot retired with the consolidation cleanup
  (waymark-26j); this namespace remains the registry the engine,
  the tests, and the declaration gate assemble from.

  The calendar here is always calendar10's scriptable fake: the iCal
  feed retired with waymark-6k5.3, and the real Google Calendar is
  wired by workqueue10."
  (:require [calendar10.resources.event :as event]
            [calendar10.source :as gcal]
            [mealplan10.resources.grocery-list :refer [grocery-list]]
            [mealplan10.resources.ingredient :refer [ingredient]]
            [mealplan10.resources.meal :refer [meal]]
            [mealplan10.resources.meal-line :refer [meal-line]]
            [mealplan10.resources.plan :refer [plan week-board]]
            [mealplan10.resources.plan-day :refer [plan-day]]
            [mealplan10.resources.prep-task :refer [prep-task]]
            [mealplan10.resources.product :refer [price-desk product]]
            [mealplan10.resources.rotation :refer [rotation]]
            [mealplan10.resources.substitution :refer [substitution]]
            [waymark10.dashboard :as dashboard]
            [waymark10.saved-view :as saved-view]))

(defonce events
  ;; the module-default fake calendar — tests script it, offline dev
  ;; pulls from it. mealplan10 standing alone never talks to the real
  ;; Google Calendar: the engine that does is workqueue10, which wires
  ;; calendar10.source/from-env against the deployed credential
  ;; (waymark-6k5.3). Here the calendar exists so the plan's overlap
  ;; predicate has something to cite.
  (gcal/fake-calendar))

(defn events-adapter [] events)

(defn meal-resources
  "The ten kinds that are actually the MEAL plan's — everything except
  the calendar. The pantry quartet
  (ingredient/product/meal_line/substitution) is the pantry-prices
  era, ported at parity.

  Split out for waymark-6k5.2: the calendar became its own domain
  (calendar10), so the engine that hosts both takes its event kind
  from there and its meal kinds from here. The plan's :related edge
  still cites :event — it joins on the promoted :date, which both
  spellings carry, so the overlap predicate does not care which
  registry supplied the kind."
  []
  [meal meal-line rotation plan plan-day grocery-list prep-task
   ingredient product substitution])

(defn resources
  "All fourteen kinds — the meal plan's ten, the LOCAL event kind, and
  the framework's user-authoring kinds (opted into like any app kind):
  saved_view (user-authored collection views, waymark-rla) and the
  dashboard pair (user-composed surfaces, waymark-ggw), for mealplan10
  standing alone.

  The event kind is calendar10's — the same one workqueue10 serves —
  over this module's fake calendar. workqueue10 does not call this
  (see meal-resources); it composes calendar10's kind itself, under
  the :calendar domain, against the real adapter."
  [adapter]
  (into (conj (meal-resources)
              (event/event-resource adapter)
              saved-view/saved-view)
        dashboard/resources))

(def surfaces
  "The declared decision screens (phase 9b): the week board (anchored
  on one plan) and the price desk (anchorless — the stale-price and
  needs-weight queues composed with the fix actions, waymark-34n)."
  [week-board price-desk])

(defn check-resources
  "All fourteen kinds over the offline adapter — the declaration
  gate's registry (waymark10.check). Zero-arg so the gate needs no
  env."
  []
  (resources events))
