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
  process-local; walk! needs test.check on the classpath (run
  under -A:test).

  For a browser-reachable UI, serve! is scratch!'s HTTP-capable
  sibling — a full engine over real Postgres storage, started on a
  port:

      (def h (dev/serve! [meal]))
      ;; => http://localhost:8123/
      (def h (dev/restart! h [meal plan]))  ; add a resource, same DB+port
      (dev/stop! h)

  Lives in src/, not a dev/ alias path, so an app that depends on
  waymark10 via :local/root reaches it from a bare REPL — the
  waymark10.test.* precedent."
  (:require [clojure.main :as cm]
            [clojure.string :as str]
            [waymark10.declaration :as declaration]
            [waymark10.expr :as expr]
            [waymark10.fingerprint :as fp]
            [waymark10.resource :as r]
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
     (println (str "waymark10 UI: http://localhost:" port "/"))
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
    (println (str "waymark10 UI: http://localhost:" (:port handle) "/"))
    (assoc handle :engine eng :server srv :resources (vec resources))))

(defn stop!
  "Tear down a serve! handle: stop the HTTP server + engine runtime,
  close the Postgres pool."
  [handle]
  (engine/stop! (:engine handle) (:server handle))
  (pg/close! (:storage handle)))

;; ── watch!: the save-to-reload loop (waymark-6ba) ───────────────────

(defn- watched-sources
  "Every .clj/.cljc under the roots (dotfiles and editor droppings
  skipped), as {canonical-path [mtime-ms length]} — length rides
  along because some filesystems stamp mtime coarsely."
  [paths]
  (into {}
        (for [p paths
              :let [root (java.io.File. (str p))]
              :when (.isDirectory root)
              ^java.io.File f (file-seq root)
              :let [n (.getName f)]
              :when (and (.isFile f)
                         (not (str/starts-with? n "."))
                         (or (str/ends-with? n ".clj")
                             (str/ends-with? n ".cljc")))]
          [(.getCanonicalPath f) [(.lastModified f) (.length f)]])))

(defn- err-lines [^Throwable t]
  (-> t Throwable->map cm/ex-triage cm/ex-str))

(defn- reload-and-restart!
  "One watch cycle: load-file every changed source FIRST — a typo or
  a law refused at the def site is printed and the running server is
  left untouched — and only when all of them compile hand control to
  reboot (the app's own stop-then-start)."
  [changed reboot]
  (let [errs (into []
                   (keep (fn [path]
                           (try (load-file path) nil
                                (catch Throwable t [path t]))))
                   changed)]
    (if (seq errs)
      (doseq [[path t] errs]
        (println (str "watch: " path " REFUSED — still serving the previous law:"))
        (println (err-lines t)))
      (do
        (doseq [path changed] (println (str "watch: reloaded " path)))
        (try
          (reboot)
          (catch Throwable t
            (println "watch: RESTART FAILED — the server may be down until the next good save:")
            (println (err-lines t))))))))

(defn watch!
  "The save-to-reload dev loop — `uvicorn --reload`'s feel without
  the JVM reboot. Polls :paths (default [\"src\"]) for .clj/.cljc
  changes on a daemon thread; a change load-files the touched
  sources first, and only when every one compiles calls :restart! —
  the app's own stop-then-start, so consumers re-register and dev
  auto-migrate reruns (schema edits included). A file that refuses
  is printed and the server keeps serving the previous law; the next
  good save heals it. New files load too; a deleted file just stops
  being watched (its namespace stays in the JVM until the next real
  boot).

  The app wiring, guarded by WAYMARK10_WATCH=1 in the make dev-*
  targets:

      (when (= \"1\" (System/getenv \"WAYMARK10_WATCH\"))
        ((requiring-resolve 'waymark10.dev/watch!)
         {:restart! (fn [] (stop!) (start!))}))

  Returns {:stop! fn}. Dev-tool honesty, said aloud when it
  happens: only the files that changed are re-evaluated (a macro's
  downstream users re-cook on their own next save), and a failure
  inside :restart! itself leaves the server down until the next
  good save.

  opts: :restart! (required, zero-arg), :paths, :interval-ms."
  [{reboot :restart! :keys [paths interval-ms]
    :or {paths ["src"] interval-ms 300}}]
  (assert (fn? reboot)
          "watch! needs :restart! — the app's zero-arg stop-then-start")
  (let [running (atom true)
        snapshot (atom (watched-sources paths))
        step (fn []
               (let [cur (watched-sources paths)
                     changed (into []
                                   (comp (remove (fn [[path stamp]]
                                                   (= stamp (get @snapshot path))))
                                         (map key))
                                   cur)]
                 (reset! snapshot cur)
                 (when (seq changed)
                   (reload-and-restart! (sort changed) reboot))))
        ;; bound-fn: the watcher narrates into its caller's *out*
        ;; (the REPL's, the test's), not the root binding's
        ^Runnable work (bound-fn []
                         (while @running
                           (Thread/sleep (long interval-ms))
                           (try (step)
                                (catch Throwable t
                                  (println "watch: watcher error:")
                                  (println (err-lines t))))))
        t (Thread. work)]
    (.setDaemon t true)
    (.setName t "waymark10-watch")
    (.start t)
    (println (str "watch: reload-on-save over " (vec paths)
                  " every " interval-ms "ms"))
    {:stop! (fn [] (reset! running false))}))

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

(defn- transition-line
  "One readable line per recorded transition — the feed's projection,
  the way explain projects the law (probe defect D3)."
  [t]
  (let [actor (:actor t)
        display (:display actor)]
    (str (:id actor)
         (when (seq display) (str " (" display ")"))
         " · " (name (:kind t)) " " (name (:action t)) ": "
         (or (some-> (:from-state t) name) "∅")
         " → " (name (:to-state t))
         " · \"" (:summary t) "\"")))

(defn create!
  "Create and return the row, printing the transition's one-line
  projection. opts pass through to the engine (:principal, :dry-run,
  :idempotency-key …)."
  ([eng kind body] (create! eng kind body {}))
  ([eng kind body opts]
   (let [res (inv/create! eng kind body
                          (merge {:principal dev-principal} opts))]
     (when-some [t (:transition res)]
       (println (transition-line t)))
     (or (:row res) res))))

(defn act!
  "One write through the full invoke algorithm, printing the
  transition's one-line projection. Returns the engine's own result
  (row, transition, warnings — or the dry-run verdict). A fenced
  action (an :edit implies If-Match) gets the live row's own etag
  unless opts carry :if-match — the scratchpad reads what it just
  wrote, so the fence is bookkeeping here, not protection (probe run
  3's D8)."
  ([eng kind id action body] (act! eng kind id action body {}))
  ([eng kind id action body opts]
   (let [adef (get (:actions (rdef-of eng kind)) action)
         opts (if (and (get-in adef [:safety :fence])
                       (nil? (:if-match opts)))
                (assoc opts :if-match
                       (inv/etag kind (str id)
                                 (:version (row eng kind id))))
                opts)
         res (inv/invoke! eng kind (str id) action body
                          (merge {:principal dev-principal} opts))]
     (when-some [t (:transition res)]
       (println (transition-line t)))
     res)))

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

(defn- guard-leaves [guard]
  (cond
    (:all guard) (mapcat guard-leaves (:all guard))
    (:any guard) (mapcat guard-leaves (:any guard))
    :else [guard]))

(defn- reads-beyond-clock
  "The guards of this action that consult other kinds — the ones the
  PURE render probe advertises optimistically (probe run 2's D6)."
  [action-def]
  (into []
        (comp (mapcat guard-leaves)
              (filter #(seq (remove #{:now} (:reads %)))))
        (:guards action-def)))

(defn why-not
  "Why the action isn't offered, in the guard's own words — the same
  :unavailable entry a client renders. :status :available when it IS
  offered; :absent when the declaration has no such action.

  Cross-resource guards (:reads beyond the clock) advertise
  optimistically on the pure render probe, so an :available answer
  for one is verified through a dry-run — the enforcement's own
  verdict. Bodiless, the partial rehearsal judges every guard whose
  inputs are already answerable (the row-reading ones); guards still
  awaiting input fields are named under :awaiting, and passing a body
  — (why-not e kind id action body) — judges those too."
  ([eng kind id action] (why-not eng kind id action nil))
  ([eng kind id action body]
   (let [doc (envelope eng kind id)
         rdef (rdef-of eng kind)
         wire-name (str/replace (name action) "-" "_")]
     (cond
       (contains? (get doc "actions") wire-name)
       (let [readers (reads-beyond-clock (get (:actions rdef) action))]
         (if (and (empty? readers) (nil? body))
           {:status :available}
           (try
             (let [res (inv/invoke! eng kind (str id) action body
                                    {:principal dev-principal
                                     :dry-run (if (some? body) true :partial)})]
               (cond-> {:status :available :verified :dry-run}
                 (seq (:awaiting res))
                 (assoc :awaiting (vec (:awaiting res))
                        :note "these guards judge input fields — pass a body to judge them too")))
             (catch clojure.lang.ExceptionInfo e
               (condp = (:waymark10/problem (ex-data e))
                 :guard-refused
                 {:status :unavailable
                  :reason (:detail (ex-data e))
                  :guard (:guard (ex-data e))
                  :via :dry-run}
                 :schema-invalid
                 {:status :invalid-input
                  :errors (:errors (ex-data e))
                  :note "the body failed the input schema before any guard judged"}
                 (throw e))))))

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
                   (vec (keys (:actions rdef))))}))))

(defn walk!
  "Drive the machine to a state with the conformance walker's own
  factories (loaded lazily — test.check is :test-scope):
  (walk! e :prep_task :scheduled) → the row there, or {:skip …}."
  [eng kind state]
  (let [f (try (requiring-resolve 'waymark10.test.factories/walk-to-state)
               (catch java.io.FileNotFoundException e
                 (throw (ex-info
                         "walk! needs test.check on the classpath — start the REPL under the :test alias (clj -A:test)"
                         {:missing 'clojure.test.check.generators} e))))]
    (f eng kind state {})))

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

;; ── vocab: the enforced vocabulary, from the live vars ──────────────

(defn vocab
  "The vocabulary the framework enforces, read from the enforcing
  vars themselves — the REPL twin of docs/waymark10-vocabulary.md
  (which carries the worked examples and the tree dialects). If the
  page and this ever disagree, this is right."
  []
  (let [line (fn [label xs]
               (println (str label ":"))
               (println (str "  " (str/join " " (sort-by str xs)))))]
    (line "expression ops (waymark10.expr/ops)" expr/ops)
    (line "declaration keys (waymark10.declaration/top-level-keys)"
          declaration/top-level-keys)
    (line "action keys (waymark10.declaration/action-keys)"
          declaration/action-keys)
    (line "flow-row opts (waymark10.resource/flow-opt-keys)"
          r/flow-opt-keys)
    (line "entry filter ops (waymark10.declaration/filter-ops)"
          declaration/filter-ops)
    (line "entry sort marks" [true :default :default-desc])
    (line "colocated entry law keys" [:filter :sort :derived :part-scope])
    (line "ref entry props" [:kind :label :predecessor])
    (println "worked examples: docs/waymark10-vocabulary.md")
    nil))

;; ── explain: the declaration as prose ───────────────────────────────

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
