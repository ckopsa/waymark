(ns waymark10.server.coherence
  "Multi-process coherence — retiring the bus.py punt ('single-process
  engines'). Two mechanisms, one namespace: the law-refresh consumer
  and the elected singleton role.

  Neither is WIRED here any more (waymark-db9.4). Both are lifecycle
  hooks on waymark10.modules/inventory, walked by
  waymark10.server.runtime — the refresh as a core hook, election as
  a `:elected` flag any module's hook may carry. What is left in this
  file is the law-refresh consumer's guarded body, which was always
  ours, and start-role!/stop-role! as the election primitive's named
  spelling over the Storage protocol's elect-role!.

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

  2. SINGLETON ROLES BY ELECTION. start-role! elects one holder per
  role name across every process sharing the storage. The holder runs
  the role's start-fn and holds until stopped or its session dies;
  non-holders retry acquisition every :retry-ms, so a clean stop OR a
  crashed process hands the role over within one retry interval.

  The MECHANISM is no longer here (waymark-db9.4). It is
  store/elect-role!, a method on the Storage protocol — pg_advisory_
  lock on a dedicated session in server/store/postgres.clj, and an
  immediate self-election on the in-memory twin, which has no peers
  to contend with. Two things follow, and both were the point. A
  lifecycle hook can now declare `:elected <role>` as a PROPERTY of
  the running surface (waymark10.modules) instead of this namespace
  reaching out and starting two other modules by name. And the twin
  degrades that flag to a plain start rather than dropping the
  surface, because the engine asks the storage to elect and never
  learns which backend it has — the same posture as the boot's
  migrate gate, which asks store/migratable? instead of guessing.

  start-role!/stop-role! stay here as the named primitive, and the
  lock keyspace is documented at store/postgres/role-lock-key.

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

  INTEGRATION. Neither half is wired from here any more. Both are
  entries in waymark10.modules/inventory's `:hooks` column, which
  engine/start! walks (waymark-db9.4): the law refresh is a CORE hook
  ordered `:after [:dispatcher]`, and the webhook deliverer and the
  clock sweeper are their own modules' hooks carrying `:elected`.
  Until db9.4 this namespace started those two itself, by name — a
  core namespace reaching into two module namespaces, which is
  exactly the coupling the module seam exists to remove. What is left
  here is what was always ours: the guarded refresh, and the election
  primitive's public spelling."
  (:require [waymark10.server.definitions :as defs]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]))

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

;; ── singleton roles: the election primitive ────────────────────────
;; The mechanism moved to the Storage protocol at waymark-db9.4
;; (store/elect-role!, pg_advisory_lock on Postgres and an immediate
;; self-election on the in-memory twin). These two stay as the named
;; primitive — the spelling jobs, attachments and the batch suites
;; already read — and as the place the discipline is written down.

(defn start-role!
  "Elect-and-run one singleton role across every process sharing the
  storage: the holder calls (start-fn) and holds until stop-role! or
  its election session dies, then (stop-fn handle) runs and the
  session closes — releasing the role, so a peer takes over within
  one :retry-ms (default 5000). A crashed process releases the same
  way. Returns the role handle; :held? and :starts are readable
  state, and :storage rides along so stop-role! stays one-armed.

  Prefer `:elected` on a lifecycle hook (waymark10.modules) to
  calling this: a surface that must be a singleton says so where it
  is declared, and the engine does the electing."
  [storage role-name opts]
  (assoc (store/elect-role! storage role-name opts) :storage storage))

(defn stop-role!
  "Stop an elected role: the holder's stop-fn runs and its election
  session closes before this returns."
  [{:keys [storage] :as handle}]
  (when handle (store/release-role! storage handle))
  nil)
