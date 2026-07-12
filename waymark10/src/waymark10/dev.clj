(ns waymark10.dev
  "The REPL scratchpad: a FULL engine — definitions boot, law
  lifecycle, in-commit derivation — over the in-memory store. One
  form, no database, no HTTP:

      (require '[waymark10.dev :as dev]
               '[mealplan10.resources.meal :refer [meal]])
      (def e (dev/scratch! [meal]))
      (def r (dev/create! e :meal {:name \"Tacos\" :themes [\"mexican\"]}))
      (dev/why-not e :meal (:id r) :retire)
      ;; => {:status :unavailable, :reason \"Available in state(s) on_list; …\"}
      (dev/act! e :meal (:id r) :accept nil)
      (dev/explain meal)

  Boundaries, each a sentence: engine/start! over a scratch engine
  works and serves the generic UI — memory has no LISTEN wire, so
  events fall back to poll-only and presence/collab/coherence stay
  process-local; :sum derived facts need Postgres (the maintainer's
  SUM is raw SQL); walk! needs test.check on the classpath (run
  under -A:test).

  For a browser-reachable UI, serve! is scratch!'s HTTP-capable
  sibling — a full engine over real Postgres storage, started on a
  port:

      (def h (dev/serve! [meal]))
      ;; => http://localhost:8123/api/-/ui
      (def h (dev/restart! h [meal plan]))  ; add a resource, same DB+port
      (dev/stop! h)

  Lives in src/, not a dev/ alias path, so an app that depends on
  waymark10 via :local/root reaches it from a bare REPL — the
  waymark10.test.* precedent."
  (:require [clojure.string :as str]
            [waymark10.fingerprint :as fp]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.store.postgres :as pg]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def dev-principal
  "The scratchpad's actor — every act! and create! runs as this
  unless the call passes its own :principal."
  (t/principal {:id "dev" :display "REPL"}))

(defn scratch!
  "A booted full engine over a fresh memory store: definition rows
  minted, laws stamped, maintainer live. opts merge into the engine
  options (e.g. {:now-fn …} to hold the clock)."
  ([resources] (scratch! resources {}))
  ([resources opts]
   (engine/engine (merge {:storage (memory/storage)
                          :resources (vec resources)}
                         opts))))

(def default-serve-dsn
  "The shared :5433 container's scratch database — never mealplan10's
  own DB, and never waymark10_test (the suite drops its tables)."
  "jdbc:postgresql://localhost:5433/waymark10_scratch?user=ckopsa")

(defn serve!
  "scratch!'s browser-reachable sibling: a full engine over real
  Postgres storage, with engine/start! actually bound to port. opts:
  :dsn (default default-serve-dsn), :port (default 8123), plus
  anything engine/engine takes. Returns a handle — {:engine :server
  :storage :port :resources} — pass it to restart! or stop!."
  ([resources] (serve! resources {}))
  ([resources {:keys [dsn port] :or {dsn default-serve-dsn port 8123}
               :as opts}]
   (let [st (pg/storage dsn)
         eng (engine/engine (merge {:storage st :resources (vec resources)}
                                   (dissoc opts :dsn :port)))
         srv (engine/start! eng port)]
     (println (str "waymark10 UI: http://localhost:" port "/api/-/ui"))
     {:engine eng :server srv :storage st :port port :resources (vec resources)})))

(defn restart!
  "Rebuild a serve! handle with a new resource list — e.g. after
  adding a resource — reusing the same storage (its connection pool
  and data survive) and port, so this is the live-reload move: stop
  the old router, ensure-kind! the new list, start again."
  [handle resources]
  (engine/stop! (:engine handle) (:server handle))
  (let [eng (engine/engine {:storage (:storage handle) :resources (vec resources)})
        srv (engine/start! eng (:port handle))]
    (println (str "waymark10 UI: http://localhost:" (:port handle) "/api/-/ui"))
    (assoc handle :engine eng :server srv :resources (vec resources))))

(defn stop!
  "Tear down a serve! handle: stop the HTTP server + engine runtime,
  close the Postgres pool."
  [handle]
  (engine/stop! (:engine handle) (:server handle))
  (pg/close! (:storage handle)))

(defn- rdef-of [eng kind]
  (or (get (inv/resources eng) kind)
      (throw (ex-info (str "no kind " kind " on this engine — kinds: "
                           (vec (sort (keys (inv/resources eng)))))
                      {:kind kind}))))

(defn row
  "The decoded live row, nil when absent."
  [eng kind id]
  (let [st (:storage eng)
        rdef (rdef-of eng kind)]
    (store/with-tx st
      (fn [tx]
        (some->> (store/load-row st tx kind (str id) {})
                 (inv/decode-row rdef))))))

(defn rows
  "Every decoded row of the kind (scratch scale — no paging)."
  [eng kind]
  (let [st (:storage eng)
        rdef (rdef-of eng kind)]
    (store/with-tx st
      (fn [tx]
        (mapv #(inv/decode-row rdef %)
              (store/query-rows st tx kind {} {}))))))

(defn create!
  "Create and return the row. opts pass through to the engine
  (:principal, :dry-run, :idempotency-key …)."
  ([eng kind body] (create! eng kind body {}))
  ([eng kind body opts]
   (let [res (inv/create! eng kind body
                          (merge {:principal dev-principal} opts))]
     (or (:row res) res))))

(defn act!
  "One write through the full invoke algorithm. Returns the engine's
  own result (row, transition, warnings — or the dry-run verdict)."
  ([eng kind id action body] (act! eng kind id action body {}))
  ([eng kind id action body opts]
   (inv/invoke! eng kind (str id) action body
                (merge {:principal dev-principal} opts))))

(defn envelope
  "The wire document a GET would answer, as data — the render probe
  over the live row. Keys are the wire's own snake strings
  ((get-in doc [\"unavailable\" \"retire\" :reason]) …)."
  ([eng kind id] (envelope eng kind id {}))
  ([eng kind id {:keys [principal]}]
   (render/envelope (rdef-of eng kind) (row eng kind id)
                    {:principal (or principal dev-principal)
                     :now ((:now-fn eng))
                     :services (:services eng)
                     :resources (inv/resources eng)})))

(defn why-not
  "Why the action isn't offered, in the guard's own words — the same
  :unavailable entry a client renders. :status :available when it IS
  offered; :absent when the declaration has no such action."
  [eng kind id action]
  (let [doc (envelope eng kind id)
        rdef (rdef-of eng kind)
        wire-name (str/replace (name action) "-" "_")]
    (cond
      (contains? (get doc "actions") wire-name)
      {:status :available}

      (contains? (get doc "unavailable") wire-name)
      (let [entry (get-in doc ["unavailable" wire-name])]
        (cond-> {:status :unavailable :reason (get entry "reason")}
          (get entry "remedies")
          (assoc :remedies (get entry "remedies"))
          (get entry "becomes_available")
          (assoc :becomes-available (get entry "becomes_available"))))

      (contains? (:actions rdef) action)
      {:status :concealed
       :note "declared, but rendered neither available nor unavailable (a :hide guard conceals it for this principal)"}

      :else
      {:status :absent
       :note (str "no action " action " on " (name kind) " — actions: "
                  (vec (keys (:actions rdef))))})))

(defn walk!
  "Drive the machine to a state with the conformance walker's own
  factories (loaded lazily — test.check is :test-scope):
  (walk! e :prep_task :scheduled) → the row there, or {:skip …}."
  [eng kind state]
  ((requiring-resolve 'waymark10.test.factories/walk-to-state)
   eng kind state {}))

(defn diff-law
  "What changed between two spellings of a kind's law, classified:
  {:verdict :data-law | :code-or-shape | :unchanged
   :changes [{:path … :class …} …]
   :stale [fact …]}"
  [a b]
  (let [fa (fp/fingerprint-of a)
        fb (fp/fingerprint-of b)
        diff (fp/diff-fingerprints fa fb)
        changes (vec (concat (:added diff) (:removed diff) (:changed diff)))]
    {:verdict (if (empty? changes) :unchanged (fp/classify-diff diff))
     :changes changes
     :stale (fp/stale-facts diff)}))

(defn handler
  "The ring handler — poke routes without a port:
  ((handler e) {:request-method :get :uri \"/api/meals\"})."
  [eng]
  (engine/handler eng))

;; ── serve!: the browser-reachable sibling ───────────────────────────

(defn serve!
  "scratch!'s HTTP-capable sibling: a full engine over real Postgres
  storage, started on a port — SSE, jobs, collab and the generic UI
  all live. Returns the handle stop!/restart! take.

  Defaults, each overridable in opts: :dsn (WAYMARK10_DEV_DSN, else
  the local :5433 waymark10_dev), :port 8123, :auto-migrate true (the
  dev posture, explicit here — production boots refuse on drift).
  Remaining opts pass through to the engine."
  ([resources] (serve! resources {}))
  ([resources {:keys [dsn port] :as opts}]
   (let [dsn (or dsn
                 (System/getenv "WAYMARK10_DEV_DSN")
                 "jdbc:postgresql://localhost:5433/waymark10_dev?user=ckopsa")
         port (or port 8123)
         storage (pg/storage dsn)
         eng (engine/engine (merge {:storage storage
                                    :resources (vec resources)
                                    :auto-migrate true}
                                   (dissoc opts :dsn :port)))
         server (engine/start! eng port)
         url (str "http://localhost:" port "/api/-/ui")]
     (println url)
     {:engine eng :server server :storage storage
      :dsn dsn :port port :url url})))

(defn stop!
  "Tear a serve! handle down: runtime, HTTP server, connection pool."
  [h]
  (engine/stop! (:engine h) (:server h))
  (pg/close! (:storage h))
  nil)

(defn restart!
  "Stop and serve again with a (possibly different) resource vector —
  same database, same port: (def h (dev/restart! h [meal plan]))."
  [h resources]
  (stop! h)
  (serve! resources {:dsn (:dsn h) :port (:port h)}))

;; ── explain: the declaration as prose ───────────────────────────────

(defn- guard-leaves [guard]
  (cond
    (:all guard) (mapcat guard-leaves (:all guard))
    (:any guard) (mapcat guard-leaves (:any guard))
    :else [guard]))

(defn- safety-sentence [safety]
  (cond
    (:confirm safety)
    (str "confirm — " (or (if (string? (:consequence safety))
                            (:consequence safety)
                            (first (vals (or (:consequence safety) {}))))
                          "(no consequence sentence)"))
    (string? (:one-way safety)) (str "one-way — " (:one-way safety))
    (:reversible safety) "reversible"
    :else (str (if (:idempotent safety) "idempotent" "not idempotent")
               ", irreversible")))

(defn explain
  "The normalized declaration rendered as prose — what this kind is,
  in the law's own sentences. Takes the declaration map (the
  defresource'd value) or an [eng kind] pair."
  ([eng kind] (explain (rdef-of eng kind)))
  ([rdef]
   (let [{:keys [kind states initial terminal actions derived]} rdef]
     (println (str (name kind) " — " (count states) " states ("
                   (name initial) " initial"
                   (when (seq terminal)
                     (str "; terminal: "
                          (str/join ", " (map name (sort terminal)))))
                   "), " (count actions) " actions"))
     (doseq [[aname a] actions]
       (println (str "  " (name aname) ": "
                     (str/join "|" (map name (sort (:from a))))
                     " → " (name (:to a))
                     " · " (safety-sentence (:safety a))))
       (doseq [g (mapcat guard-leaves (:guards a))
               :when (:explain g)]
         (println (str "      guard " (some-> (:name g) name) ": "
                       (:explain g)))))
     (doseq [[fact spec] derived]
       (println (str "  derived " (name fact)
                     (when-some [over (seq (:over spec))]
                       (str " over " (mapv name over)))
                     (when (:count spec) " (a count over a declared edge)"))))
     (when-some [ws (seq (:waymark10/warnings (meta rdef)))]
       (doseq [w ws] (println (str "  ⚠ " w))))
     (when-some [ds (seq (:deviations rdef))]
       (doseq [d ds] (println (str "  deviation: " d))))
     nil)))
