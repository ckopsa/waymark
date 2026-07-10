(ns waymark10.server.engine
  "Wiring: resources + storage → ring handler; http-kit lifecycle.
  The engine holds the registry in an atom — the definitions boot and
  the law lifecycle swap slots into it, and every invocation/request
  reads a fresh snapshot through inv/resources — plus the :render-fn
  seam: invoke's idempotency store keeps the rendered envelope, so a
  replayed key answers with the first execution's exact bytes.

  Boot order (phase 5): assemble the registry with the definition
  resource enrolled beside the application kinds, ensure every
  kind's storage, then boot-revise! — definition rows exist before
  anything anchors to them. opts gain :deploy-mode (:promote, the
  default single-breath revise, or :propose — a data-law diff holds
  at proposed while the overlay serves the current law)."
  (:require [org.httpkit.server :as http]
            [waymark10.registry :as registry]
            [waymark10.server.definitions :as defs]
            [waymark10.server.render :as render]
            [waymark10.server.router :as router]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(defn engine
  "The booted engine. render-fn runs inside the invoke transaction,
  so it stays pure: no storage reads, and the probe runs for the
  ANONYMOUS principal (threading the acting principal through
  finish! remains a recorded punt) — principal-sensitive guards may
  render differently in a replay than they did live."
  [{:keys [storage resources services now-fn deploy-mode]}]
  (let [reg (registry/registry (conj (vec resources) defs/definition))
        eng {:storage storage
             :registry (atom reg)
             :services services
             :now-fn (or now-fn (fn [] (java.time.Instant/now)))
             :deploy-mode (or deploy-mode :promote)
             :lifecycle defs/lifecycle}
        eng (assoc eng :render-fn
                   (fn [rdef row]
                     (wire/write-json
                      (render/envelope rdef row {:principal t/anonymous
                                                 :now ((:now-fn eng))
                                                 :services (:services eng)}))))]
    (doseq [[_ rdef] (:kinds reg)]
      (store/ensure-kind! storage rdef))
    (defs/boot-revise! eng)
    eng))

(defn handler
  "The ring handler for an engine."
  [eng]
  (router/handler eng))

(defn start!
  "Serve the engine on port via http-kit; returns the server."
  [eng port]
  (http/run-server (handler eng) {:port port :legacy-return-value? false}))

(defn stop! [server]
  (when server (http/server-stop! server)))

;; ── the dev server (scripts/smoke10.sh) ─────────────────────────────

(defonce ^:private dev (atom nil))

(defn start-dev!
  "The fixture kinds (meal, plan) on port 8010 against the dev
  database — separate from the test DSN so `clojure -M:test` and a
  running dev server never fight over tables. The fixtures live on
  the test path, so run with it, e.g.:

    clojure -Sdeps '{:aliases {:fx {:extra-paths [\"test\"]}}}' -M:fx \\
      -e \"((requiring-resolve 'waymark10.server.engine/start-dev!)) @(promise)\""
  []
  (let [dsn (or (System/getenv "WAYMARK10_DEV_DSN")
                "jdbc:postgresql://localhost:5433/mealplan10_dev?user=ckopsa")
        storage (pg/storage dsn)
        eng (engine {:storage storage
                     :resources [@(requiring-resolve 'waymark10.fixtures/meal)
                                 @(requiring-resolve 'waymark10.fixtures/plan)]})
        server (start! eng 8010)]
    (reset! dev {:server server :storage storage :engine eng})
    (println "waymark10 dev server: http://localhost:8010/api/.well-known/waymark")
    eng))

(defn stop-dev! []
  (when-some [{:keys [server storage]} @dev]
    (stop! server)
    (pg/close! storage)
    (reset! dev nil)))
