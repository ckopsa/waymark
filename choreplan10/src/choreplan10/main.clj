(ns choreplan10.main
  "The family chore tracker: resource declarations in, everything
  else out.

      make dev-chores   # serve on :8013 against choreplan10_dev
      clojure -M:dev    # the same, from choreplan10/

  Agent/tool surface at /api/.well-known/waymark.

  Env knobs: CHOREPLAN10_DSN (default the local :5433
  choreplan10_dev), CHOREPLAN10_PORT (default 8013),
  CHOREPLAN10_MEALPLAN_URL (the mealplan10 engine the prep_task
  mirror syncs from — the in-memory fake feed when unset, offline
  dev's default), CHOREPLAN10_MEALPLAN_ASSIGNEE /
  _PRINCIPAL / _TOKEN (the feed's key and credentials),
  WAYMARK10_DEPLOY_MODE (promote, the default single-breath revise,
  or propose), WAYMARK10_AUTO_MIGRATE=1 (dev only — `make dev-chores`
  passes it explicitly; production boots REFUSE on schema drift and
  name the plan), WAYMARK10_OIDC_* (the family IdP —
  waymark10.server.oidc/from-env names them; absent = the dev-header
  resolver, unchanged).

  Schema evolution: `make migrate-chores` prints the plan (migrate!,
  the :migrate alias); APPLY=1 executes it, DESTRUCTIVE=1
  additionally the state-rename UPDATEs."
  (:require [choreplan10.mirror.mealplan :as mp]
            [choreplan10.resources.chore :refer [chore]]
            [choreplan10.resources.chore-run :refer [chore-run]]
            [choreplan10.resources.day :refer [day day-board]]
            [choreplan10.resources.prep-task :refer [prep-task-resource]]
            [waymark10.server.engine :as engine]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.oidc-rp :as oidc-rp]
            [waymark10.server.store.migrate :as migrate]
            [waymark10.server.store.postgres :as pg])
  (:gen-class))

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

(defn- dsn []
  (or (System/getenv "CHOREPLAN10_DSN")
      "jdbc:postgresql://localhost:5433/choreplan10_dev?user=ckopsa"))

(defn- deploy-mode []
  (case (System/getenv "WAYMARK10_DEPLOY_MODE")
    "propose" :propose
    :promote))

(defonce ^:private dev (atom nil))

(defn start!
  "Boot and serve. Returns the engine."
  []
  (let [storage (pg/storage (dsn))
        ;; with-push: prep_task declares :push-on-write, and engine
        ;; boot does not auto-wire the post-commit push pass (the
        ;; recorded seam in mirror/with-push) — the embedding wraps
        eng (mirror/with-push
             (engine/engine {:storage storage
                             :resources (resources (feed))
                             :surfaces surfaces
                             :deploy-mode (deploy-mode)
                             ;; dev-only, and only when asked: production
                             ;; posture is refuse-on-drift
                             :auto-migrate (= "1" (System/getenv
                                                   "WAYMARK10_AUTO_MIGRATE"))
                             ;; the family IdP, when deployed says so
                             ;; (WAYMARK10_OIDC_*); absent env = the
                             ;; dev-header resolver, unchanged
                             :oidc (oidc/from-env)}))
        port (or (some-> (System/getenv "CHOREPLAN10_PORT") parse-long) 8013)
        server (engine/start! eng port
                              {:wrap-handler (oidc-rp/wrap-handler eng)})]
    (reset! dev {:engine eng :server server :storage storage})
    (println (str "choreplan10: http://localhost:" port
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

;; ── the migrate CLI (make migrate-chores) ───────────────────────────

(defn migrate!
  "Print the schema plan for this app's full registry against
  CHOREPLAN10_DSN; APPLY=1 executes it, DESTRUCTIVE=1 additionally
  the state-rename UPDATEs (otherwise destructive steps are skipped
  and said so). Exits 0 on an empty plan or a fully applied one, 1
  while steps remain — scriptable as a deploy gate."
  [& _]
  (let [storage (pg/storage (dsn))]
    (try
      (let [reg (engine/full-registry (resources (feed)))
            steps (migrate/plan storage (vals (:kinds reg)))]
        (if (empty? steps)
          (println "choreplan10: storage matches the declarations — empty plan.")
          (do
            (println (str "choreplan10: " (count steps) " migration step(s):"))
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
