(ns workqueue10.main
  "The family work queue: every task-like row from the household's
  engines — choreplan10 chore runs, mealplan10 prep tasks — in ONE
  kind, prioritized against each other, for humans and agents alike.

      make dev-queue    # serve on :8014 against workqueue10_dev
      clojure -M:dev    # the same, from workqueue10/

  Agent/tool surface at /api/.well-known/waymark.

  Env knobs: WORKQUEUE10_DSN (default the local :5433
  workqueue10_dev), WORKQUEUE10_PORT (default 8014),
  WORKQUEUE10_CHOREPLAN_URL / WORKQUEUE10_MEALPLAN_URL /
  WORKQUEUE10_HA_URL (the sources the confluence drinks from — each
  falls back to its in-memory fake source when unset, offline dev's
  default), WORKQUEUE10_PRINCIPAL (the x-waymark-principal pushes
  act as — default \"workqueue10\"), WORKQUEUE10_CHOREPLAN_TOKEN /
  _MEALPLAN_TOKEN (optional bearers), WORKQUEUE10_HA_TOKEN /
  _HA_UI_URL / _HA_LISTS / _HA_ZONE (the home assistant boundary:
  long-lived token, the browser-facing base for origin links, the
  comma-separated todo entity ids, the zone naive due datetimes
  parse in), WAYMARK10_DEPLOY_MODE,
  WAYMARK10_AUTO_MIGRATE=1 (dev only — production boots REFUSE on
  schema drift and name the plan), WAYMARK10_OIDC_* (the family
  IdP — waymark10.server.oidc/from-env names them; absent = the
  dev-header resolver, unchanged).

  Schema evolution: `make migrate-queue` prints the plan (migrate!,
  the :migrate alias); APPLY=1 executes it, DESTRUCTIVE=1
  additionally the state-rename UPDATEs."
  (:require [choreplan10.resources.chore :refer [chore]]
            [choreplan10.resources.chore-run :refer [chore-run]]
            [choreplan10.resources.day :refer [day day-board]]
            [mealplan10.main :as mealplan]
            [workqueue10.confluence :as conf]
            [workqueue10.resources.task :refer [task-resource]]
            [workqueue10.sources.choreplan :as chores]
            [workqueue10.sources.homeassistant :as ha]
            [workqueue10.sources.mealplan :as meals]
            [waymark10.dsl :refer [in-domain]]
            [waymark10.server.engine :as engine]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.oidc-rp :as oidc-rp]
            [waymark10.server.store.migrate :as migrate]
            [waymark10.server.store.postgres :as pg])
  (:gen-class))

(defonce fake-chores
  ;; module-default fake boundaries — tests script them, offline dev
  ;; and the declaration gate run over them
  (conf/fake-source))

(defonce fake-meals
  (conf/fake-source))

(defonce fake-todos
  (conf/fake-source))

(defonce engine-ref
  ;; the stage-1 fold's late binding: in-process sources need the
  ;; engine that hosts them, and the engine's registry needs the
  ;; sources — start! delivers this between engine and serve
  (atom nil))

(defn- ui-base []
  (or (System/getenv "WAYMARK10_OIDC_APP_URL") "http://localhost:8014"))

(defn sources
  "The confluence's tag → TaskSource map. The chore kinds live in
  THIS engine since the stage-1 fold (waymark-bwu.1) — their source
  is in-process unless WORKQUEUE10_CHOREPLAN_URL points at a separate
  engine (the pre-fold posture, kept for the transition). The rest go
  real when their URL is set, fake otherwise."
  []
  (let [principal (System/getenv "WORKQUEUE10_PRINCIPAL")]
    {"chore" (if-some [url (System/getenv "WORKQUEUE10_CHOREPLAN_URL")]
               (chores/http-source
                {:url url :principal principal
                 :token (System/getenv "WORKQUEUE10_CHOREPLAN_TOKEN")
                 ;; production's bearer: this engine's OWN client
                 ;; mints against the source's audience scope, fresh
                 ;; every hour (waymark-mvl); a static _TOKEN wins
                 :token-fn (oidc/outbound-token-fn "waymark-choreplan10")})
               (chores/engine-source
                {:engine-ref engine-ref :ui-base (ui-base)
                 :principal principal}))
     "meal" (if-some [url (System/getenv "WORKQUEUE10_MEALPLAN_URL")]
              (meals/http-source
               {:url url :principal principal
                :token (System/getenv "WORKQUEUE10_MEALPLAN_TOKEN")
                :token-fn (oidc/outbound-token-fn "waymark-mealplan10")})
              (meals/engine-source
               {:engine-ref engine-ref :ui-base (ui-base)
                :principal principal}))
     "todo" (if-some [url (System/getenv "WORKQUEUE10_HA_URL")]
              (ha/http-source
               {:url url
                :ui-url (System/getenv "WORKQUEUE10_HA_UI_URL")
                :token (System/getenv "WORKQUEUE10_HA_TOKEN")
                :lists (System/getenv "WORKQUEUE10_HA_LISTS")
                :zone (System/getenv "WORKQUEUE10_HA_ZONE")
                :capture-list (System/getenv "WORKQUEUE10_HA_CAPTURE")})
              fake-todos)}))

(defn resources
  "One domestic economics (waymark-bwu): the queue's kind, the folded
  chore registry (chore, chore_run, day — bwu.1), and the folded meal
  registry (mealplan10's eleven kinds — bwu.2). prep_task is
  mealplan's NATIVE kind now; choreplan's HTTP mirror of it retired
  with stage 2 — the day board joins the real rows. srcs: the
  confluence's tag → TaskSource map; adapter: the family calendar's
  event boundary."
  [srcs adapter]
  (-> (in-domain :queue [(task-resource (conf/confluence srcs))])
      (into (in-domain :chores [chore chore-run day]))
      (into (in-domain :meals (mealplan/resources adapter)))))

(def surfaces
  "Both decision screens, one engine: the housekeeper's day board and
  the planner's week board."
  (into [day-board] mealplan/surfaces))

(defn check-resources
  "Zero-arg so the declaration gate needs no env — every kind over
  the offline fakes."
  []
  (resources {"chore" fake-chores "meal" fake-meals "todo" fake-todos}
             mealplan/events))

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
                             :resources (resources (sources)
                                                   (mealplan/events-adapter))
                             :surfaces surfaces
                             :deploy-mode (deploy-mode)
                             ;; dev-only, and only when asked: production
                             ;; posture is refuse-on-drift
                             :auto-migrate (= "1" (System/getenv
                                                   "WAYMARK10_AUTO_MIGRATE"))
                             ;; the family IdP, when deployed says so
                             ;; (WAYMARK10_OIDC_*); absent env = the
                             ;; dev-header resolver, unchanged
                             :oidc (oidc/from-env)
                             ;; the hashed disposition's salt
                             ;; (waymark-rci) — a real secret in
                             ;; production; absent = the dev constant
                             :services {:field-hash-salt
                                        (System/getenv
                                         "WAYMARK10_FIELD_HASH_SALT")}}))
        ;; the in-process sources' late binding: delivered BEFORE
        ;; start! wakes the discovery runner
        _ (reset! engine-ref eng)
        port (or (some-> (System/getenv "WORKQUEUE10_PORT") parse-long) 8014)
        server (engine/start! eng port
                              {:wrap-handler (oidc-rp/wrap-handler eng)})]
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
      (let [reg (engine/full-registry (resources (sources)
                                                 (mealplan/events-adapter)))
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
