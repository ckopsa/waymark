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
  at proposed while the overlay serves the current law).

  Phase 6: the engine carries the :maintain hook (the derivation
  maintainer rides invoke's after-write! seam on every engine), and
  start!/stop! own the running surfaces — the events dispatcher and
  the clock sweeper live in the engine's :runtime atom, so an engine
  that never starts (a test handler) pays nothing and its SSE routes
  answer 503. opts: :sweep-interval-ms (clock sweep, default 30s),
  :events-poll-ms (dispatcher backstop, default 2s),
  :sse-heartbeat-ms (default 15s), :maintainer-fan-out (default 200)."
  (:require [org.httpkit.server :as http]
            [waymark10.registry :as registry]
            [waymark10.server.definitions :as defs]
            [waymark10.server.events :as events]
            [waymark10.server.maintainer :as maintainer]
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
  [{:keys [storage resources services now-fn deploy-mode] :as opts}]
  (let [reg (registry/registry (conj (vec resources) defs/definition))
        eng (merge (select-keys opts [:sweep-interval-ms :events-poll-ms
                                      :sse-heartbeat-ms :maintainer-fan-out])
                   {:storage storage
                    :registry (atom reg)
                    :services services
                    :now-fn (or now-fn (fn [] (java.time.Instant/now)))
                    :deploy-mode (or deploy-mode :promote)
                    :lifecycle defs/lifecycle
                    :maintain maintainer/after-write
                    :runtime (atom nil)})
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
  "Serve the engine on port via http-kit and start its running
  surfaces — the events dispatcher (SSE's feed) and the clock
  sweeper — in the engine's :runtime atom. Returns the server; pass
  BOTH engine and server to stop!."
  [eng port]
  (when-some [rt (:runtime eng)]
    (reset! rt {:dispatcher (events/dispatcher
                             eng {:poll-ms (:events-poll-ms eng 2000)})
                :sweeper (maintainer/start-sweeper!
                          eng {:interval-ms (:sweep-interval-ms eng 30000)})}))
  (http/run-server (handler eng) {:port port :legacy-return-value? false}))

(defn stop!
  "Stop the server; the two-arity form also stops the engine's
  runtime (dispatcher + sweeper)."
  ([server]
   (when server (http/server-stop! server)))
  ([eng server]
   (when server (http/server-stop! server))
   (when-some [rt (:runtime eng)]
     (when-some [{:keys [dispatcher sweeper]} @rt]
       (some-> dispatcher events/stop!)
       (some-> sweeper maintainer/stop-sweeper!))
     (reset! rt nil))))

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
  (when-some [{:keys [server storage engine]} @dev]
    (stop! engine server)
    (pg/close! storage)
    (reset! dev nil)))
