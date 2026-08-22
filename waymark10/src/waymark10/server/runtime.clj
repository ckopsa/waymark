(ns waymark10.server.runtime
  "The engine's RUNNING half: what a started engine holds, and the
  walker that starts and stops it.

  Until waymark-db9.4 there was no such noun. engine/start! built one
  literal map into the :runtime atom — dispatcher, curtain, presence,
  intents, discovery, coherence, jobs worker, two sweepers — and
  engine/stop! took the same nine apart by hand, in an order two
  comments defended and nothing enforced. This namespace turns that
  literal into an ITERATION over data: a seq of lifecycle hooks the
  assembled modules contribute (waymark10.modules/inventory's `:hooks`
  column), each one

      {:hook    :curtain            ; the runtime key it publishes at
       :after   [:dispatcher]       ; start-order constraint
       :when    (fn [eng] …)        ; optional: run at all?
       :elected :clock-sweeper      ; optional: one holder per storage
       :start   (fn [eng running]) → handle
       :stop    (fn [handle])}

  ── the two orders ──

  `:after` is the only order anyone declares, and BOTH orders derive
  from it: start is a stable topological sort (table order breaks
  ties, so a reader of the inventory reads the start order), stop is
  that sequence reversed. The curtain is the case that made this worth
  writing down — it wants the dispatcher, and presence and intents
  want it, so stop! carried the hand-placed comment 'after its
  readers, before the dispatcher it subscribes to'. That sentence is
  now a consequence rather than a promise, and modules_test pins the
  sequence.

  RECORDED, because the rewrite changed exactly one position: mirror
  discovery used to be stopped LAST, after the dispatcher. Nothing
  defended it — discovery neither reads nor writes the dispatcher; it
  holds a job lease and hands it back — so it now stops first, like
  any hook with no declared dependency and the last start slot.

  ── election ──

  `:elected` is a property of a HOOK, not of a subsystem. A surface
  that must have one holder per database says so where it is
  declared, and the walker asks the STORAGE to elect
  (store/elect-role!): pg_advisory_lock on Postgres, immediate
  self-election on the in-memory twin. server/coherence used to reach
  into webhooks and maintainer to start them under election — a core
  namespace naming two modules — and this flag is what dissolved that.
  The `:elected` value is the ROLE NAME, not a bare true, because the
  name IS the advisory-lock keyspace (store/postgres/role-lock-key).

  ── the reading side ──

  `surface` is the seam a core handler uses to reach a running
  module's surface by key instead of by namespace. waymark-db9.7 spent
  it: the router's presence reach-ins (the read mark, the two stream
  hooks) and its intents announcement are `(surface eng :presence)`
  and `(surface eng :intents)` now, and what they CALL on the handle
  is a protocol core names (waymark10.server.seams). Recorded there,
  because it is the boundary of this namespace: the router's other
  two reach-ins — the mirror's pull-through and the job the bulk door
  defers — could NOT come through here. They run on engines that
  never start, so they ride the declaration instead. A running
  surface is a fact about a process; those two are facts about what
  was declared.

  An engine that never starts still pays NOTHING: the hooks are data
  until start-hooks! walks them, the :runtime atom stays nil, and the
  SSE routes answer 503 exactly as they always did."
  (:require [waymark10.server.store :as store]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 runtime: " parts))))

;; ── reading a started engine ────────────────────────────────────────

(defn running
  "Every surface this engine started, {hook-key handle} — nil on an
  engine that never started."
  [eng]
  (some-> (:runtime eng) deref))

(defn started?
  "Has this engine's runtime been walked? False for a test handler,
  which is the posture that keeps a bare engine free."
  [eng]
  (some? (running eng)))

(defn surface
  "The handle one started hook published, by its `:hook` key — nil on
  an engine that never started, on one whose module was left out of
  the assembly, and on one whose hook's `:when` said no. Every caller
  therefore has to have an answer for nil, which is the honest shape:
  a running surface is a fact about a process, never about a
  declaration."
  [eng hook-key]
  (get (running eng) hook-key))

(defn surfaces
  "The hook keys actually running on this engine — the set the
  conformance driver judges a `[:surface k]` need against
  (waymark10.test.suite)."
  [eng]
  (set (keys (or (running eng) {}))))

;; ── ordering ────────────────────────────────────────────────────────

(defn order
  "The hooks in START order: a stable topological sort by `:after`,
  ties broken by inventory order. An `:after` naming a hook this
  assembly does not carry is satisfied vacuously — dropping a module
  drops its surface, it does not strand the surfaces that would have
  liked it. Stop order is this, reversed.

  A cycle refuses loudly: an engine that started half its surfaces and
  then deadlocked on the order of the rest is worse than one that will
  not boot."
  [hooks]
  (let [ks (mapv :hook hooks)
        dupes (mapv key (filter #(< 1 (val %)) (frequencies ks)))]
    (when (seq dupes)
      (throw (t/definition-error
              (str "two lifecycle hooks share the key(s) " dupes
                   " — a runtime key names one surface")
              {:check :hooks :duplicate dupes})))
    (let [present (set ks)]
      (loop [pending (vec hooks) done #{} out []]
        (if (empty? pending)
          out
          (let [i (first (keep-indexed
                          (fn [i {:keys [after]}]
                            (when (every? #(or (not (present %)) (done %))
                                          after)
                              i))
                          pending))]
            (when (nil? i)
              (throw (t/definition-error
                      (str "lifecycle hooks cannot be ordered — :after "
                           "cycles among " (mapv :hook pending))
                      {:check :hooks :unordered (mapv :hook pending)})))
            (recur (into (subvec pending 0 i) (subvec pending (inc i)))
                   (conj done (:hook (nth pending i)))
                   (conj out (nth pending i)))))))))

;; ── the walk ────────────────────────────────────────────────────────

(defn- halt!
  "Stop one started hook — through the storage when it was elected
  (the role's own stop-fn runs on the holder), directly otherwise. A
  surface that throws on the way down is warned about and skipped:
  one bad teardown must not strand the eight below it."
  [eng {:keys [hook elected stop]} handle]
  (try
    (if elected
      (store/release-role! (:storage eng) handle)
      (when stop (stop handle)))
    (catch Throwable e
      (warn! "hook " hook " failed to stop: " (ex-message e)))))

(defn stop-hooks!
  "Stop every surface in `started` ({hook-key handle}), in the reverse
  of the order these hooks start in."
  [eng hooks started]
  (doseq [{h :hook :as hook} (reverse (order hooks))]
    (when-some [handle (get started h)]
      (halt! eng hook handle)))
  nil)

(defn start-hooks!
  "Walk the hooks in start order and return {hook-key handle} for the
  ones that ran. A hook with a `:when` that answers falsey contributes
  no key at all — the mirror's when-declared discovery generalized, so
  an engine with no mirror kinds pays nothing for the module.

  A hook that throws on the way UP takes the whole start down with it:
  every surface already running is stopped, in order, before the
  exception leaves. Half a runtime is not a posture this engine
  offers."
  [eng hooks]
  (let [ordered (order hooks)]
    (loop [remaining ordered
           started {}]
      (if-some [{:keys [hook elected start] pred :when :as h} (first remaining)]
        (if (and pred (not (pred eng)))
          (recur (rest remaining) started)
          (let [handle (try
                         (if elected
                           (store/elect-role!
                            (:storage eng) elected
                            {:retry-ms (:role-retry-ms eng 5000)
                             :start-fn #(start eng started)
                             :stop-fn (:stop h)})
                           (start eng started))
                         (catch Throwable e
                           (stop-hooks! eng hooks started)
                           (throw (ex-info
                                   (str "lifecycle hook " hook
                                        " failed to start: " (ex-message e))
                                   {:hook hook} e))))]
            (recur (rest remaining) (assoc started hook handle))))
        started))))
