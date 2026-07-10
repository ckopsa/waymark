(ns mealplan10.main
  "The family meal-planning app: resource declarations in, everything
  else out.

      make dev10          # serve on :8010 against mealplan10_dev
      clojure -M:dev      # the same, from mealplan10/

  Agent/tool surface at /api/.well-known/waymark. The calendar adapter
  is real by default when MEALPLAN_GCAL_ICS_URL is set (the private
  Google Calendar feed URL — a bearer secret: env var only, never
  source); otherwise the in-memory FakeEvents twin serves offline dev.

  Env knobs: MEALPLAN10_DSN (default the local :5433 mealplan10_dev),
  MEALPLAN10_PORT (default 8010), WAYMARK10_DEPLOY_MODE (promote, the
  default single-breath revise, or propose), WAYMARK10_AUTO_MIGRATE=1
  (dev only — `make dev10` passes it explicitly; production boots
  REFUSE on schema drift and name the plan).

  Schema evolution: `make migrate10` prints the plan (migrate!, the
  :migrate alias); APPLY=1 executes it, DESTRUCTIVE=1 additionally
  the state-rename UPDATEs."
  (:require [mealplan10.event-source :as es]
            [mealplan10.resources.event :as event]
            [mealplan10.resources.grocery-list :refer [grocery-list]]
            [mealplan10.resources.meal :refer [meal]]
            [mealplan10.resources.plan :refer [plan week-board]]
            [mealplan10.resources.prep-task :refer [prep-task]]
            [mealplan10.resources.rotation :refer [rotation]]
            [waymark10.server.engine :as engine]
            [waymark10.server.store.migrate :as migrate]
            [waymark10.server.store.postgres :as pg])
  (:gen-class))

(defonce events
  ;; the module-default FakeEvents singleton — tests script it,
  ;; offline dev pulls from it
  (es/fake-events))

(defn events-adapter []
  (if-some [url (System/getenv "MEALPLAN_GCAL_ICS_URL")]
    (es/google-calendar-events url)
    events))

(defn resources
  "All six kinds, the event kind bound to its adapter."
  [adapter]
  [meal rotation plan grocery-list prep-task (event/event-resource adapter)])

(def surfaces
  "The declared decision screens (phase 9b): the week board."
  [week-board])

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
                            ;; dev-only, and only when asked: production
                            ;; posture is refuse-on-drift
                            :auto-migrate (= "1" (System/getenv
                                                  "WAYMARK10_AUTO_MIGRATE"))})
        port (or (some-> (System/getenv "MEALPLAN10_PORT") parse-long) 8010)
        server (engine/start! eng port)]
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
