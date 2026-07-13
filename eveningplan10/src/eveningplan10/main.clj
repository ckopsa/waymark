(ns eveningplan10.main
  "The evening-activity planner: resource declarations in, everything
  else out.

      make dev-eveningplan10   # serve on :8011 against eveningplan10_dev
      clojure -M:dev           # the same, from eveningplan10/

  Agent/tool surface at /api/.well-known/waymark.

  Env knobs: EVENINGPLAN10_DSN (default the local :5433
  eveningplan10_dev), EVENINGPLAN10_PORT (default 8011),
  WAYMARK10_DEPLOY_MODE (promote, the default single-breath revise, or
  propose), WAYMARK10_AUTO_MIGRATE=1 (dev only — `make
  dev-eveningplan10` passes it explicitly; production boots REFUSE on
  schema drift and name the plan).

  Schema evolution: `make migrate-eveningplan10` prints the plan
  (migrate!, the :migrate alias); APPLY=1 executes it, DESTRUCTIVE=1
  additionally the state-rename UPDATEs."
  (:require [eveningplan10.consumers :as consumers]
            [eveningplan10.resources.activity :refer [activity]]
            [eveningplan10.resources.evening-plan :refer [evening-plan]]
            [eveningplan10.resources.evening-session :refer [evening-session]]
            [waymark10.server.engine :as engine]
            [waymark10.server.store.migrate :as migrate]
            [waymark10.server.store.postgres :as pg])
  (:gen-class))

(defn resources
  "All three kinds — what `make check-eveningplan10`
  (waymark10.check) assembles too."
  []
  [activity evening-plan evening-session])

(defn check-resources
  "Zero-arg so the declaration gate needs no env."
  []
  (resources))

(defn- dsn []
  (or (System/getenv "EVENINGPLAN10_DSN")
      "jdbc:postgresql://localhost:5433/eveningplan10_dev?user=ckopsa"))

(defn- deploy-mode []
  (case (System/getenv "WAYMARK10_DEPLOY_MODE")
    "propose" :propose
    :promote))

(defonce ^:private dev (atom nil))

(defn start!
  "Boot, serve, and register the plan-sessions consumer. Returns the
  engine."
  []
  (let [storage (pg/storage (dsn))
        eng (engine/engine {:storage storage
                            :resources (resources)
                            :deploy-mode (deploy-mode)
                            ;; dev-only, and only when asked: production
                            ;; posture is refuse-on-drift
                            :auto-migrate (= "1" (System/getenv
                                                  "WAYMARK10_AUTO_MIGRATE"))})
        port (or (some-> (System/getenv "EVENINGPLAN10_PORT") parse-long) 8011)
        server (engine/start! eng port)
        consumer (consumers/register! eng)]
    (reset! dev {:engine eng :server server :storage storage :consumer consumer})
    (println (str "eveningplan10: http://localhost:" port
                  "/api/.well-known/waymark"))
    eng))

(defn stop! []
  (when-some [{:keys [engine server storage consumer]} @dev]
    (consumers/stop! consumer)
    (engine/stop! engine server)
    (pg/close! storage)
    (reset! dev nil)))

(defn -main [& _]
  (start!)
  @(promise))

;; ── the migrate CLI (make migrate-eveningplan10) ────────────────────

(defn migrate!
  "Print the schema plan for this app's full registry against
  EVENINGPLAN10_DSN; APPLY=1 executes it, DESTRUCTIVE=1 additionally
  the state-rename UPDATEs (otherwise destructive steps are skipped
  and said so). Exits 0 on an empty plan or a fully applied one, 1
  while steps remain — scriptable as a deploy gate."
  [& _]
  (let [storage (pg/storage (dsn))]
    (try
      (let [reg (engine/full-registry (resources))
            steps (migrate/plan storage (vals (:kinds reg)))]
        (if (empty? steps)
          (println "eveningplan10: storage matches the declarations — empty plan.")
          (do
            (println (str "eveningplan10: " (count steps) " migration step(s):"))
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
