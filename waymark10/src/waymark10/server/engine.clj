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
  start!/stop! own the running surfaces, which live in the engine's
  :runtime atom — so an engine that never starts (a test handler)
  pays nothing and its SSE routes answer 503.

  THE RUNNING SURFACES ARE NO LONGER ENUMERATED HERE. This docstring
  used to list them in prose while start! listed them again in a map
  literal and stop! a third time in a teardown; three enumerations
  drift, and the prose one drifted first. They are now the `:hooks`
  column of waymark10.modules/inventory — one entry per surface,
  saying which module owns it, what it starts after, whether it is an
  elected singleton, and how it stops — walked by
  waymark10.server.runtime (waymark-db9.4). Read the table.

  What stays here is the OPTS, because they are the engine's and
  every hook reads them off it: :sweep-interval-ms (clock sweep,
  default 30s), :events-poll-ms (dispatcher backstop, 2s),
  :sse-heartbeat-ms (15s), :maintainer-fan-out (200),
  :presence-heartbeat-ms (15s — paces the registry's heartbeats and
  TTL), :curtain-ttl-ms (2s — the honest bound on a stale verdict
  when the invalidation wire is lost), :webhook-attempts (3),
  :webhook-backoff-ms (250), :webhook-timeout-ms (10s),
  :webhooks-poll-ms (deliverer backstop, 2s), :jobs-poll-ms (worker
  cadence, 1s), :jobs-batch-size (progress granularity, 10),
  :orphan-sweep-ms (30s), :purge-sweep-ms (60s), :role-retry-ms (the
  election's contention/liveness cadence, 5s) and
  :law-refresh-debounce-ms (the refresh burst collapse, 1s).

  Phase 9a: every engine enrolls the identity-and-access kinds beside
  the definition — member, role, grant, attachment — so well-known
  lists them and the law lifecycle governs them like any kind. opts
  gain :oidc (the relying-party config, validated at boot; absent =
  dev-header auth unchanged), :attachment-dir (default
  target/attachments) and :attachment-max-bytes (default 10 MiB).

  Phase 9b: the engine also enrolls :subscription (webhooks) and :job
  (deferred bulk), assembles declared :surfaces against the registry,
  and carries the collab rooms atom. opts gain :surfaces (surface
  declarations)."
  (:require [clojure.string :as str]
            [org.httpkit.server :as http]
            [waymark10.modules :as modules]
            [waymark10.registry :as registry]
            [waymark10.server.definitions :as defs]
            [waymark10.server.maintainer :as maintainer]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.render :as render]
            [waymark10.server.router :as router]
            [waymark10.server.runtime :as runtime]
            [waymark10.server.store :as store]
            [waymark10.server.store.migrate :as migrate]
            [waymark10.server.store.postgres :as pg]
            [waymark10.server.surface :as surface]
            [waymark10.server.worksheet :as worksheet]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(set! *warn-on-reflection* true)

(defn full-registry
  "The application's resources plus every kind the engine itself
  enrolls — the one list, shared with the declaration-time check and
  the migrate CLI so a plan covers exactly the kinds a boot would
  serve.

  The list is no longer a literal here: it is
  waymark10.modules/inventory, a plain readable table saying which
  module owns each kind and whether the engine enrols it always, only
  when some application kind asks for it (the worksheet's rule), or
  never — the app naming the rdef in its own resources vector. Pass
  `modules` to assemble a named subset; nil, the spelling every
  caller uses today, means the whole table."
  ([resources] (full-registry resources nil))
  ([resources modules]
   (registry/registry (into (vec resources)
                            (modules/enrolled resources modules)))))

(defn- migrate-gate!
  "The boot's schema gate (migrate): plan the drift; a non-empty plan
  refuses to serve — the production posture — unless :auto-migrate,
  which applies the NON-destructive steps. State renames are never
  auto-applied: a destructive remainder refuses the boot too, naming
  the explicit opt-in (the migrate CLI's DESTRUCTIVE=1). The
  state-token check runs after either way: rows in state tokens no
  declaration names or maps refuse the boot with the fix named
  (waymark9's check_state_tokens)."
  [storage reg opts]
  ;; a storage with no SQL schema to snapshot (the in-memory twin)
  ;; cannot drift — the gate is vacuous there, and the planner's
  ;; Postgres reads would crash it (store/migratable?)
  (when (store/migratable? storage)
    (let [rdefs (vals (:kinds reg))
          steps (migrate/plan storage rdefs)
          refuse (fn [steps remedy]
                   (throw (t/definition-error
                           (str "storage drift: " (count steps)
                                " migration step(s) pending — " remedy "\n"
                                (str/join "\n" (map migrate/describe steps)))
                           {:check :migrate :steps (vec steps)})))]
      (when (seq steps)
        (if (:auto-migrate opts)
          (let [{:keys [skipped]}
                (migrate/apply! storage steps {:destructive? false})]
            (when (seq skipped)
              (refuse skipped
                      (str "these rewrite state tokens, which :auto-migrate "
                           "never does; apply them deliberately "
                           "(make migrate10 APPLY=1 DESTRUCTIVE=1)"))))
          (refuse steps
                  "apply the plan (make migrate10 APPLY=1) or boot with :auto-migrate")))
      (migrate/assert-known-states! storage rdefs))))

(defn engine
  "The booted engine. render-fn runs inside the invoke transaction,
  so it stays pure: no storage reads, and the probe runs for the
  ANONYMOUS principal (threading the acting principal through
  finish! remains a recorded punt) — principal-sensitive guards may
  render differently in a replay than they did live.

  :probe-reads true (opt-in, never a default: dev's why-not premise
  and every envelope pin rest on the pure probe) hands the ROUTER's
  render path the enforcement :read/:find hooks
  (invoke/render-hooks), so cross-resource acceptance sets enumerate
  as picker enums on GET envelopes and cross-row guards tell their
  real verdict. The invoke-transaction render-fn above stays hookless
  either way — recorded punt: an action response's envelope still
  advertises optimistically; the follow-up GET tells the folded
  truth.

  :modules names the inventory's subset this engine assembles
  (waymark10.modules); absent — every caller today — means the whole
  table, and the engine serves exactly the kinds and the routes it
  always has. Core is never droppable. The selection rides ON the
  engine, because it governs two seams now: the kinds `full-registry`
  enrols above, and the routes `handler` mounts below — an engine
  assembled without :seasons has no /api/-/seasons to answer with.
  It governs the THIRD seam too, since db9.4: the running surfaces
  start! walks are the selection's `:hooks`, so an engine assembled
  without :realtime runs no curtain and no presence registry.

  Migrate (the schema gate): after every kind's storage is ensured,
  the boot plans declared-vs-live drift and REFUSES to serve on a
  non-empty plan — unless opts carry :auto-migrate true (dev
  posture, passed explicitly; never a default), which applies the
  non-destructive steps in place. Rows in state tokens no declaration
  maps refuse the boot either way (waymark9's check_state_tokens)."
  [{:keys [storage resources services now-fn deploy-mode modules] :as opts}]
  (let [reg (full-registry resources modules)
        eng (merge (select-keys opts [:sweep-interval-ms :events-poll-ms
                                      :sse-heartbeat-ms :maintainer-fan-out
                                      :suppress-mirror-refresh :probe-reads
                                      :attachment-dir :attachment-max-bytes
                                      :webhook-attempts :webhook-backoff-ms
                                      :webhook-timeout-ms :webhooks-poll-ms
                                      :jobs-poll-ms :jobs-batch-size
                                      :orphan-sweep-ms :purge-sweep-ms
                                      :role-retry-ms :law-refresh-debounce-ms
                                      :report-pass
                                      :members :collab-heartbeat-ms
                                      :presence-heartbeat-ms
                                      :collab-ticket-ttl-ms
                                      :intents-heartbeat-ms
                                      :intent-ttl-ms :intent-ask-ttl-ms
                                      :curtain-ttl-ms
                                      ;; the feed's recipe (waymark-iqa.2):
                                      ;; static data, read at the route's
                                      ;; build site with its default and
                                      ;; checked there. An opt rather than a
                                      ;; fifth module column — the
                                      ;; contribution table is closed at four
                                      ;; and this is the spelling
                                      ;; waymark10.modules already names for
                                      ;; every other module's knob.
                                      :feed])
                   (when-some [o (:oidc opts)] {:oidc (oidc/config o)})
                   {:storage storage
                    :registry (atom reg)
                    ;; the assembled selection, kept because the
                    ;; router seam asks for it again at handler time
                    ;; (nil = the whole inventory, every caller today)
                    :modules modules
                    :services services
                    :now-fn (or now-fn (fn [] (java.time.Instant/now)))
                    :deploy-mode (or deploy-mode :promote)
                    :lifecycle defs/lifecycle
                    ;; the worksheet pass composes over the derivation
                    ;; maintainer — the engine's own kind, so the boot
                    ;; wires it (an app-level with-push wraps outside)
                    :maintain (fn [engine kind action-name res]
                                (worksheet/after-write!
                                 engine kind action-name
                                 (or (maintainer/after-write
                                      engine kind action-name res)
                                     res)))
                    ;; the declared surfaces, validated where every
                    ;; kind is known (phase 9b)
                    :surfaces (surface/assemble reg (:surfaces opts))
                    ;; live collab's per-draft rooms (phase 9b)
                    :collab-rooms (atom {})
                    ;; the socket's one-time identity vouchers —
                    ;; ephemeral, never law (collab/mint-ticket!)
                    :collab-tickets (atom {})
                    :runtime (atom nil)})
        eng (assoc eng :render-fn
                   (fn [rdef row]
                     (wire/write-json
                      (render/envelope rdef row {:principal t/anonymous
                                                 :now ((:now-fn eng))
                                                 :services (:services eng)}))))]
    (doseq [[_ rdef] (:kinds reg)]
      (store/ensure-kind! storage rdef))
    (migrate-gate! storage reg opts)
    (defs/boot-revise! eng)
    eng))

(defn handler
  "The ring handler for an engine: core's routes plus the assembled
  modules'. THIS is where the two halves of the module table meet —
  the router knows no module by name and the modules know no router
  by name; this line hands one to the other."
  [eng]
  (router/handler eng (modules/route-sets eng (:modules eng))))

(defn start-runtime!
  "Start the engine's running surfaces into its :runtime atom, and
  return the {hook-key handle} map that landed there.

  There is no literal here any more. The surfaces are the `:hooks` of
  the modules this engine assembled (waymark10.modules/hooks reads the
  same `selected` the kinds and the routes read), and
  runtime/start-hooks! walks them in the order their `:after` implies,
  electing the ones that declared themselves singletons. Adding a
  running surface is a row in a table a reviewer can read; it is no
  longer an edit to this function and a second edit to stop!.

  Idempotent in the only way that matters: an engine with no :runtime
  atom — nothing builds one, but the shape allows it — starts
  nothing."
  [eng]
  (when-some [rt (:runtime eng)]
    (reset! rt (runtime/start-hooks! eng (modules/hooks (:modules eng))))))

(defn stop-runtime!
  "Stop every surface start-runtime! started, in the reverse of the
  order it started them, and empty the :runtime atom. The hook seq is
  recomputed from the same table rather than remembered, so the two
  walks cannot disagree about what is running."
  [eng]
  (when-some [rt (:runtime eng)]
    (when-some [started @rt]
      (runtime/stop-hooks! eng (modules/hooks (:modules eng)) started))
    (reset! rt nil))
  nil)

(defn start!
  "Serve the engine on port via http-kit and start its running
  surfaces (start-runtime!). Returns the server; pass BOTH engine and
  server to stop!.

  opts: :wrap-handler — a ring middleware the embedding composes
  around the engine's handler (an app-level route that needs the
  engine alive, e.g. a byte-store redirect, mounts here without
  forking the boot)."
  [eng port & [{:keys [wrap-handler]}]]
  (start-runtime! eng)
  (http/run-server ((or wrap-handler identity) (handler eng))
                   {:port port :legacy-return-value? false}))

(defn stop!
  "Stop the server; the two-arity form also stops the engine's
  runtime (stop-runtime!)."
  ([server]
   (when server (http/server-stop! server)))
  ([eng server]
   (when server (http/server-stop! server))
   (stop-runtime! eng)))

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
                     ;; the dev fixture server reconciles its own drift;
                     ;; production engines refuse and name the plan
                     :auto-migrate true
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
