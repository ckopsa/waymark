(ns mealplan10.main
  "The family meal-planning app: resource declarations in, everything
  else out.

      make dev10          # serve on :8010 against mealplan10_dev
      clojure -M:dev      # the same, from mealplan10/

  Agent/tool surface at /api/.well-known/waymark. The calendar here is
  always calendar10's scriptable fake: the iCal feed retired with
  waymark-6k5.3, and the real Google Calendar is wired by workqueue10,
  the engine that actually serves the family.

  Env knobs: MEALPLAN10_DSN (default the local :5433 mealplan10_dev),
  MEALPLAN10_PORT (default 8010), WAYMARK10_DEPLOY_MODE (promote, the
  default single-breath revise, or propose), WAYMARK10_AUTO_MIGRATE=1
  (dev only — `make dev10` passes it explicitly; production boots
  REFUSE on schema drift and name the plan), WAYMARK10_OIDC_* (the
  family IdP — waymark10.server.oidc/from-env names them; absent =
  the dev-header resolver, unchanged).

  Schema evolution: `make migrate10` prints the plan (migrate!, the
  :migrate alias); APPLY=1 executes it, DESTRUCTIVE=1 additionally
  the state-rename UPDATEs."
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
            [waymark10.saved-view :as saved-view]
            [waymark10.server.engine :as engine]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.oidc-rp :as oidc-rp]
            [waymark10.server.store.migrate :as migrate]
            [waymark10.server.store.postgres :as pg])
  (:gen-class))

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
  "All fourteen kinds over the offline adapter — what `make check10`
  (waymark10.check) assembles. Zero-arg so the gate needs no env."
  []
  (resources events))

(defn- dsn []
  (or (System/getenv "MEALPLAN10_DSN")
      "jdbc:postgresql://localhost:5433/mealplan10_dev?user=ckopsa"))

(defn- deploy-mode []
  (case (System/getenv "WAYMARK10_DEPLOY_MODE")
    "propose" :propose
    :promote))

(defonce ^:private dev (atom nil))

(defn start!
  "Boot and serve. Returns the engine."
  []
  (let [storage (pg/storage (dsn))
        eng (engine/engine {:storage storage
                            :resources (resources (events-adapter))
                            :surfaces surfaces
                            :deploy-mode (deploy-mode)
                            ;; the render probe carries the read hooks
                            ;; (waymark-1pq): a day's picker enumerates
                            ;; the meals that serve its night — the
                            ;; envelope IS the discovery
                            :probe-reads true
                            ;; dev-only, and only when asked: production
                            ;; posture is refuse-on-drift
                            :auto-migrate (= "1" (System/getenv
                                                  "WAYMARK10_AUTO_MIGRATE"))
                            ;; the family IdP, when deployed says so
                            ;; (WAYMARK10_OIDC_*); absent env = the
                            ;; dev-header resolver, unchanged
                            :oidc (oidc/from-env)})
        port (or (some-> (System/getenv "MEALPLAN10_PORT") parse-long) 8010)
        server (engine/start! eng port
                              {:wrap-handler (oidc-rp/wrap-handler eng)})]
    (reset! dev {:engine eng :server server :storage storage})
    (println (str "mealplan10: http://localhost:" port
                  "/api/.well-known/waymark"))
    eng))

(defn stop! []
  (when-some [{:keys [engine server storage]} @dev]
    (engine/stop! engine server)
    (pg/close! storage)
    (reset! dev nil)))

(defn -main [& _]
  (start!)
  @(promise))

;; ── the migrate CLI (make migrate10) ────────────────────────────────

(defn migrate!
  "Print the schema plan for this app's full registry (application
  kinds + everything the engine enrolls) against MEALPLAN10_DSN;
  APPLY=1 executes it, DESTRUCTIVE=1 additionally the state-rename
  UPDATEs (otherwise destructive steps are skipped and said so).
  Exits 0 on an empty plan or a fully applied one, 1 while steps
  remain — scriptable as a deploy gate."
  [& _]
  (let [storage (pg/storage (dsn))]
    (try
      (let [reg (engine/full-registry (resources (events-adapter)))
            steps (migrate/plan storage (vals (:kinds reg)))]
        (if (empty? steps)
          (println "mealplan10: storage matches the declarations — empty plan.")
          (do
            (println (str "mealplan10: " (count steps) " migration step(s):"))
            (doseq [s steps] (println " " (migrate/describe s)))
            (if (= "1" (System/getenv "APPLY"))
              (let [destructive? (= "1" (System/getenv "DESTRUCTIVE"))
                    {:keys [applied skipped]}
                    (migrate/apply! storage steps {:destructive? destructive?})]
                (println (str "applied " (count applied) " step(s)."))
                (when (seq skipped)
                  (println (str "SKIPPED " (count skipped)
                                " destructive step(s) — re-run with DESTRUCTIVE=1:"))
                  (doseq [s skipped] (println " " (migrate/describe s)))
                  (System/exit 1)))
              (do (println "dry run — APPLY=1 executes (DESTRUCTIVE=1 includes state renames).")
                  (System/exit 1))))))
      (finally
        (pg/close! storage)
        (shutdown-agents)))))
