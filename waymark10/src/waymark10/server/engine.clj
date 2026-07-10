(ns waymark10.server.engine
  "Wiring: resources + storage → ring handler; http-kit lifecycle.
  The engine is invoke/engine plus the :render-fn seam — invoke's
  idempotency store keeps the rendered envelope, so a replayed key
  answers with the first execution's exact bytes."
  (:require [org.httpkit.server :as http]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]
            [waymark10.server.router :as router]
            [waymark10.server.store.postgres :as pg]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(defn engine
  "invoke/engine + :render-fn. render-fn runs inside the invoke
  transaction, so it stays pure: no storage reads, and the probe runs
  for the ANONYMOUS principal (threading the acting principal through
  finish! is phase 5's revisit) — principal-sensitive guards may
  render differently in a replay than they did live."
  [opts]
  (let [eng (inv/engine opts)]
    (assoc eng :render-fn
           (fn [rdef row]
             (wire/write-json
              (render/envelope rdef row {:principal t/anonymous
                                         :now ((:now-fn eng))
                                         :services (:services eng)}))))))

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
