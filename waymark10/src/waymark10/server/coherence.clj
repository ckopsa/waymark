(ns waymark10.server.coherence
  "Multi-process coherence — retiring the bus.py punt ('single-process
  engines'). Two mechanisms, one namespace: the law-refresh consumer
  and advisory-lock-elected singleton roles.

  THE PROBLEM. The law slots (:current-law, :judgment-laws, the
  proposed/piloted overlays, the judgment caches) live in each
  engine's registry ATOM, and the definitions lifecycle updates the
  local atom only — a second engine process against the same database
  keeps serving the OLD law after a promote on the first, violating
  'every path that applies law to a row applies the row's law'. And
  two of the running surfaces assume they are singletons: the webhook
  deliverer (two processes double-deliver — the per-subscription
  cursor is shared, unguarded) and the clock sweeper (double work,
  lock contention on due pages).

  1. LAW-SLOT REFRESH RIDES THE OUTBOX. Definition transitions are
  ordinary logged transitions (kind :definition), so the events
  dispatcher already delivers them to every process. The refresh
  consumer subscribes to exactly those, debounces bursts (a promote's
  effect logs several transitions — retire, supersede, adopt — and
  one refresh at the end of the burst covers them all), and calls
  definitions/boot-revise!: on UNCHANGED code boot-revise! is
  idempotent — hash-equal against the stored current row, it adopts
  revisions, holds, pilots, and overlays from the store, writing
  nothing. That is the whole mechanism; definitions.clj is untouched.

  The concurrency reading of boot-revise! (verified, phase-11 finding):
  - Each kind's slots install through ONE swap! (definitions'
    install!, which also resets the judgment cache), so a concurrent
    request sees the old slots or the new, never a partial set; every
    invocation resolves its rdef from a single registry snapshot.
    Hash-equal refresh is therefore safe beside serving traffic.
  - It is NOT safe unconditionally. On a process whose resident code
    matches no stored current/proposed/piloted revision (a mixed-code
    window: this process outlived a promote of newer code),
    boot-revise! would MINT law — re-proposing (or in :promote mode
    re-promoting) the old law from a non-deploy context, and two
    processes with different resident code would mint and withdraw
    each other's rows forever. And its unchanged-code path withdraws
    'lingering' proposals — which would withdraw a LIVE hold minted
    by a newer-code peer. So refresh! GUARDS: it runs boot-revise!
    only when every application kind would take a pure-adoption path
    (resident hash equals the stored current's with no foreign
    proposal rows, or equals a stored proposed/piloted row's);
    anything else warns and skips — a mixed-code process serves what
    it has, and its replacement is the rolling deploy's job, not the
    refresh's. Recorded boundary, tested.
  - Residual windows, honestly: refreshes are serialized on the one
    consumer thread, but a lifecycle effect invoked THROUGH this
    process runs its installs on the request thread beside a peer
    -triggered refresh — both derive from the store, every effect
    step logs a transition, and the re-fired consumer converges on
    the committed store. The guard's check and boot-revise!'s own
    read are separate store reads (TOCTOU) — a transition landing
    between them likewise re-fires the consumer. And between a peer's
    promote commit and this process's debounced refresh (~debounce +
    dispatcher poll), the stale slots still stamp creates and adopt
    targets with the prior law; rows already stamped keep being
    judged by their own law where an overlay entry exists, and by
    the resident code where the stamp is unknown (judgment.clj's
    recorded fallback).

  2. SINGLETON ROLES BY ADVISORY LOCK. start-role! elects one holder
  per role name across every process sharing the database:
  pg_try_advisory_lock on a well-known bigint, held on a DEDICATED
  raw connection (never from the Hikari pool — the lock is
  session-scoped and a recycled session would drop it silently; the
  same discipline as the dispatcher's LISTEN connection). The holder
  runs the role's start-fn and holds until stopped or the session
  dies; non-holders retry acquisition every :retry-ms. Closing the
  session releases the lock, so a clean stop OR a crashed process
  hands the role over within one retry interval. The LOCK KEYSPACE:
  the key's high 32 bits are the fixed namespace 0x574D3130 (the
  ASCII bytes \"WM10\"), the low 32 bits are the CRC32 of the role
  name's UTF-8 bytes — deterministic across JVMs, collision-free for
  the two names in use (webhooks-deliverer, clock-sweeper), and
  disjoint from any other application's advisory keys unless it also
  claims the WM10 word.

  WHAT STAYS PROCESS-LOCAL, recorded:
  - SSE subscribers: correct — each process serves its own
    connections off the shared log; every dispatcher reads every
    transition.
  - Collab rooms: cross-process live relay NOT built. Edits persist
    through the shared draft rows, so late joiners (and joiners on
    the other process) converge on the persisted values — but live
    frames do not cross processes; two clients on different processes
    editing one draft see each other only at sync/rejoin. This is
    the remaining relay/2-adjacent punt, named here so nobody
    re-discovers it.
  - Idempotency and natural replay: DB-anchored, already
    multi-process safe.
  - Jobs: the worker claims leases (claim-or-steal on expiry),
    already multi-process safe — the worker stays a per-process
    start, NOT a role.

  INTEGRATION (engine/start!, applied post-merge by the maintainer):
  coherence owns the deliverer and the sweeper — start! here REPLACES
  the direct webhooks/start-deliverer! and maintainer/start-sweeper!
  calls. In engine/start!'s runtime map, drop the :sweeper and
  :webhooks entries and add
      :coherence (coherence/start! eng dispatcher {})
  and in engine/stop!, before stopping the dispatcher,
      (some-> coherence coherence/stop!)
  (requiring [waymark10.server.coherence :as coherence]; the
  destructuring gains :coherence and loses :sweeper/:webhooks). The
  jobs worker and mirror discovery entries stay as they are."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [waymark10.server.definitions :as defs]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.maintainer :as maintainer]
            [waymark10.server.store :as store]
            [waymark10.server.webhooks :as webhooks])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.nio.charset StandardCharsets)
           (java.sql Connection DriverManager)
           (java.util.zip CRC32)))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 coherence: " parts))))

;; ── the law refresh ─────────────────────────────────────────────────

(defn- kind-safety
  "Would boot-revise! take a pure-adoption path for this kind? The
  guard mirrors revise-kind!'s dispatch without duplicating its work:
  only the hash comparisons that pick the branch."
  [eng tx kind rdef]
  (let [rows (store/query-rows (:storage eng) tx :definition
                               {:target_kind (name kind)} {:limit 1000})
        by-state (group-by :state rows)
        fph (:fingerprint-hash rdef)
        hash-of #(get-in % [:data :fingerprint_hash])
        currents (:current by-state)
        proposeds (:proposed by-state)
        piloteds (:piloted by-state)]
    (cond
      ;; pre-law (boot would mint revision 1) or mid-effect (two
      ;; current rows coexist for the promote effect's duration —
      ;; invoke.clj's recorded deviation); the effect's own logged
      ;; transitions re-fire the consumer, which converges
      (not= 1 (count currents))
      {:kind kind :safe? false
       :why (str (count currents) " current rows — pre-law or mid-effect")}

      ;; (b')/(b''): the resident code IS the stored hold or pilot —
      ;; boot-revise! adopts it, writing nothing
      (some #(= fph (hash-of %)) (concat proposeds piloteds))
      {:kind kind :safe? true}

      ;; resident code matches no live stored revision: boot-revise!
      ;; would MINT law from a non-deploy context (and two mixed-code
      ;; processes would mint/withdraw each other's rows forever)
      (not= fph (hash-of (first currents)))
      {:kind kind :safe? false
       :why "resident code matches no stored live revision; the rolling deploy owns this process's replacement"}

      ;; resident == current, but a peer holds a proposal this code
      ;; does not express: boot-revise! would withdraw the live hold
      (some #(not= fph (hash-of %)) proposeds)
      {:kind kind :safe? false
       :why "a newer-code peer holds a live proposal; refreshing here would withdraw it"}

      ;; (b): adopt the stored current's slots, writing nothing
      :else {:kind kind :safe? true})))

(defn refresh!
  "One guarded law refresh: when every application kind would take a
  pure-adoption path, run definitions/boot-revise! (hash-equal →
  adopt revisions, holds, pilots, overlays from the store); otherwise
  warn per unsafe kind and skip. → {:refreshed? bool :unsafe [checks]}.
  The guard's read and boot-revise!'s own are separate transactions
  (recorded TOCTOU): a transition landing between them re-fires the
  consumer and the next refresh converges."
  [eng]
  (let [kinds (sort-by key (dissoc (inv/resources eng) :definition))
        checks (store/with-tx (:storage eng)
                 (fn [tx]
                   (mapv (fn [[k rd]] (kind-safety eng tx k rd)) kinds)))
        unsafe (filterv (complement :safe?) checks)]
    (if (seq unsafe)
      (do (doseq [{:keys [kind why]} unsafe]
            (warn! "law refresh skipped: " (name kind) " — " why))
          {:refreshed? false :unsafe unsafe})
      (do (defs/boot-revise! eng)
          {:refreshed? true :unsafe []}))))

(defn start-refresh!
  "The law-refresh consumer: subscribe to definition-kind transitions
  on the engine's dispatcher; on each burst (collapsed while events
  keep arriving within :debounce-ms, default 1000), run one guarded
  refresh!. One refresh runs at startup — transitions committed
  between the engine's boot and this subscribe are already in the
  log, and the dispatcher only delivers forward. All refreshes run on
  this one thread, so they never interleave with each other."
  [eng dispatcher {:keys [debounce-ms] :or {debounce-ms 1000}}]
  (let [sub (events/subscribe dispatcher {:kinds #{:definition}})
        running (atom true)
        refreshes (atom 0)
        skips (atom 0)
        run! (fn []
               (try
                 (if (:refreshed? (refresh! eng))
                   (swap! refreshes inc)
                   (swap! skips inc))
                 (catch Exception e
                   (when @running
                     (warn! "law refresh failed: " (ex-message e))))))
        t (Thread.
           ^Runnable
           (fn []
             (run!)
             (loop []
               (let [evt (events/take-event sub 60000)]
                 (cond
                   (= ::events/closed evt) nil
                   (nil? evt) (when @running (recur))
                   :else
                   ;; collapse the burst: quiet for debounce-ms first
                   (let [closed? (loop []
                                   (let [e (events/take-event sub debounce-ms)]
                                     (cond
                                       (= ::events/closed e) true
                                       (some? e) (recur)
                                       :else false)))]
                     (when @running (run!))
                     (when (and @running (not closed?)) (recur)))))))
           "waymark10-law-refresh")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:dispatcher dispatcher :sub sub :running running :thread t
     :refreshes refreshes :skips skips}))

(defn stop-refresh! [{:keys [dispatcher sub running ^Thread thread]}]
  (reset! running false)
  (events/unsubscribe dispatcher sub)
  (some-> thread (.join 5000))
  nil)

;; ── singleton roles by advisory lock ────────────────────────────────

(def lock-namespace
  "The high 32 bits of every waymark10 advisory-lock key: the ASCII
  bytes \"WM10\". Documented so no other tenant of the database
  claims the word by accident."
  0x574D3130)

(defn role-lock-key
  "role name → the pg advisory-lock bigint: (\"WM10\" << 32) |
  crc32(utf-8 name). Deterministic across JVMs."
  ^long [role-name]
  (let [crc (doto (CRC32.)
              (.update (.getBytes (name role-name) StandardCharsets/UTF_8)))]
    (bit-or (bit-shift-left (long lock-namespace) 32) (.getValue crc))))

(defn- raw-connection
  "A dedicated raw JDBC connection for one role's lock — deliberately
  NOT from the Hikari pool (the listen-connection discipline): the
  advisory lock is session-scoped, and the pool recycling the session
  would drop it silently."
  ^Connection [storage]
  (DriverManager/getConnection (.getJdbcUrl ^HikariDataSource (:ds storage))))

(defn- try-lock? [conn ^long key]
  (boolean (:locked (jdbc/execute-one!
                     conn ["SELECT pg_try_advisory_lock(?) AS locked" key]
                     {:builder-fn rs/as-unqualified-maps}))))

(defn start-role!
  "Elect-and-run one singleton role across every process sharing the
  database: acquire pg_try_advisory_lock(role-lock-key role-name) on
  a dedicated connection, retrying every :retry-ms (default 5000);
  the holder calls (start-fn) and holds until stop-role! or the
  session dies (checked every retry interval), then (stop-fn handle)
  runs and the session closes — releasing the lock, so the peer's
  next try takes over. A crashed process releases the same way: its
  sessions die with it. Returns the role handle; :held? and :starts
  are readable state."
  [storage role-name {:keys [retry-ms start-fn stop-fn]
                      :or {retry-ms 5000}}]
  (let [key (role-lock-key role-name)
        running (atom true)
        held? (atom false)
        starts (atom 0)
        t (Thread.
           ^Runnable
           (fn []
             (while @running
               (let [conn (try (raw-connection storage)
                               (catch Exception e
                                 (when @running
                                   (warn! "role " (name role-name)
                                          ": no lock connection ("
                                          (ex-message e) "); retrying"))
                                 nil))]
                 (if (nil? conn)
                   (try (Thread/sleep (long retry-ms))
                        (catch InterruptedException _ nil))
                   (try
                     ;; contend
                     (loop []
                       (when (and @running (not (try-lock? conn key)))
                         (Thread/sleep (long retry-ms))
                         (recur)))
                     ;; hold
                     (when @running
                       (swap! starts inc)
                       (reset! held? true)
                       (let [handle (start-fn)]
                         (try
                           (loop []
                             (when (and @running (.isValid ^Connection conn 2))
                               (Thread/sleep (long retry-ms))
                               (recur)))
                           (finally
                             (reset! held? false)
                             (try (stop-fn handle)
                                  (catch Exception e
                                    (warn! "role " (name role-name)
                                           " stop-fn: " (ex-message e))))))))
                     (catch InterruptedException _ nil)
                     (catch Exception e
                       (when @running
                         (warn! "role " (name role-name) " loop: "
                                (ex-message e))
                         (try (Thread/sleep (long retry-ms))
                              (catch InterruptedException _ nil))))
                     (finally
                       ;; closing the session releases the lock
                       (try (.close ^Connection conn) (catch Exception _ nil))))))))
           (str "waymark10-role-" (name role-name)))]
    (doto ^Thread t (.setDaemon true) (.start))
    {:role role-name :thread t :running running :held? held? :starts starts}))

(defn stop-role!
  "Stop the role: the holder's stop-fn runs and its lock session
  closes before this returns, so a peer acquires within one retry
  interval."
  [{:keys [running ^Thread thread]}]
  (reset! running false)
  (some-> thread .interrupt)
  (some-> thread (.join 5000))
  nil)

;; ── the coherence lifecycle ─────────────────────────────────────────

(defn start!
  "Start multi-process coherence for a booted engine and its running
  dispatcher: the law-refresh consumer, plus the two singleton roles
  — the webhook deliverer and the clock sweeper, each elected by
  advisory lock (their direct starts in engine/start! are what this
  replaces; see the ns docstring's integration note). opts:
  :debounce-ms (refresh burst collapse, default 1000), :role-retry-ms
  (lock acquisition/hold cadence, default 5000). Returns the handle
  stop! takes."
  ([eng dispatcher] (start! eng dispatcher {}))
  ([eng dispatcher {:keys [debounce-ms role-retry-ms]}]
   (let [retry (or role-retry-ms 5000)]
     {:refresh (start-refresh! eng dispatcher
                               {:debounce-ms (or debounce-ms 1000)})
      :webhooks-role
      (start-role! (:storage eng) :webhooks-deliverer
                   {:retry-ms retry
                    :start-fn #(webhooks/start-deliverer!
                                eng dispatcher
                                {:poll-ms (:webhooks-poll-ms eng 2000)})
                    :stop-fn webhooks/stop-deliverer!})
      :sweeper-role
      (start-role! (:storage eng) :clock-sweeper
                   {:retry-ms retry
                    :start-fn #(maintainer/start-sweeper!
                                eng {:interval-ms (:sweep-interval-ms eng 30000)})
                    :stop-fn maintainer/stop-sweeper!})})))

(defn stop!
  "Stop the coherence surfaces: roles first (their held locks release,
  a peer takes over), then the refresh consumer."
  [{:keys [refresh webhooks-role sweeper-role]}]
  (some-> webhooks-role stop-role!)
  (some-> sweeper-role stop-role!)
  (some-> refresh stop-refresh!)
  nil)
