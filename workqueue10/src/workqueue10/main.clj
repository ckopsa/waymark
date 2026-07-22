(ns workqueue10.main
  "The family work queue: every task-like row from the household's
  engines — choreplan10 chore runs, mealplan10 prep tasks — in ONE
  kind, prioritized against each other, for humans and agents alike.

      make dev-queue    # serve on :8014 against workqueue10_dev
      clojure -M:dev    # the same, from workqueue10/

  Agent/tool surface at /api/.well-known/waymark.

  Env knobs: WORKQUEUE10_DSN (default the local :5433
  workqueue10_dev), WORKQUEUE10_PORT (default 8014),
  WORKQUEUE10_CHOREPLAN_URL / WORKQUEUE10_MEALPLAN_URL (the source
  engines the confluence drinks from — each falls back to its
  in-memory fake source when unset, offline dev's default),
  WORKQUEUE10_PRINCIPAL (the x-waymark-principal pushes act as —
  default \"workqueue10\"), WORKQUEUE10_CHOREPLAN_TOKEN /
  _MEALPLAN_TOKEN (optional bearers), WAYMARK10_DEPLOY_MODE,
  WAYMARK10_AUTO_MIGRATE=1 (dev only — production boots REFUSE on
  schema drift and name the plan).

  Schema evolution: `make migrate-queue` prints the plan (migrate!,
  the :migrate alias); APPLY=1 executes it, DESTRUCTIVE=1
  additionally the state-rename UPDATEs."
  (:require [workqueue10.confluence :as conf]
            [workqueue10.resources.task :refer [task-resource]]
            [workqueue10.sources.choreplan :as chores]
            [workqueue10.sources.mealplan :as meals]
            [waymark10.server.engine :as engine]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store.migrate :as migrate]
            [waymark10.server.store.postgres :as pg])
  (:gen-class))

(defonce fake-chores
  ;; module-default fake boundaries — tests script them, offline dev
  ;; and the declaration gate run over them
  (conf/fake-source))

(defonce fake-meals
  (conf/fake-source))

(defn sources
  "The confluence's tag → TaskSource map. Each tag goes real when its
  URL is set, fake otherwise — a laptop can drink from a live
  choreplan while mealplan stays scripted."
  []
  (let [principal (System/getenv "WORKQUEUE10_PRINCIPAL")]
    {"chore" (if-some [url (System/getenv "WORKQUEUE10_CHOREPLAN_URL")]
               (chores/http-source
                {:url url :principal principal
                 :token (System/getenv "WORKQUEUE10_CHOREPLAN_TOKEN")})
               fake-chores)
     "meal" (if-some [url (System/getenv "WORKQUEUE10_MEALPLAN_URL")]
              (meals/http-source
               {:url url :principal principal
                :token (System/getenv "WORKQUEUE10_MEALPLAN_TOKEN")})
              fake-meals)}))

(defn resources
  "The one kind — what `make check-queue` (waymark10.check) assembles
  too. srcs: the confluence's tag → TaskSource map."
  [srcs]
  [(task-resource (conf/confluence srcs))])

(defn check-resources
  "Zero-arg so the declaration gate needs no env — the kind over the
  offline fakes."
  []
  (resources {"chore" fake-chores "meal" fake-meals}))

(defn- dsn []
  (or (System/getenv "WORKQUEUE10_DSN")
      "jdbc:postgresql://localhost:5433/workqueue10_dev?user=ckopsa"))

(defn- deploy-mode []
  (case (System/getenv "WAYMARK10_DEPLOY_MODE")
    "propose" :propose
    :promote))

(defonce ^:private dev (atom nil))

(defn start!
  "Boot and serve. Returns the engine."
  []
  (let [storage (pg/storage (dsn))
        ;; with-push: task declares :push-on-write, and engine boot
        ;; does not auto-wire the post-commit push pass (the recorded
        ;; seam in mirror/with-push) — the embedding wraps
        eng (mirror/with-push
             (engine/engine {:storage storage
                             :resources (resources (sources))
                             :deploy-mode (deploy-mode)
                             ;; dev-only, and only when asked: production
                             ;; posture is refuse-on-drift
                             :auto-migrate (= "1" (System/getenv
                                                   "WAYMARK10_AUTO_MIGRATE"))}))
        port (or (some-> (System/getenv "WORKQUEUE10_PORT") parse-long) 8014)
        server (engine/start! eng port)]
    (reset! dev {:engine eng :server server :storage storage})
    (println (str "workqueue10: http://localhost:" port
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

;; ── the migrate CLI (make migrate-queue) ────────────────────────────

(defn migrate!
  "Print the schema plan for this app's full registry against
  WORKQUEUE10_DSN; APPLY=1 executes it, DESTRUCTIVE=1 additionally
  the state-rename UPDATEs (otherwise destructive steps are skipped
  and said so). Exits 0 on an empty plan or a fully applied one, 1
  while steps remain — scriptable as a deploy gate."
  [& _]
  (let [storage (pg/storage (dsn))]
    (try
      (let [reg (engine/full-registry (resources (sources)))
            steps (migrate/plan storage (vals (:kinds reg)))]
        (if (empty? steps)
          (println "workqueue10: storage matches the declarations — empty plan.")
          (do
            (println (str "workqueue10: " (count steps) " migration step(s):"))
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
