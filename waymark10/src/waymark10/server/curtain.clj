(ns waymark10.server.curtain
  "The curtain — ONE reader, ONE cache, every watching surface.
  A principal whose member row carries :curtain true (members.clj
  draw_curtain, waymark-tti.4) is NOT PUBLISHED by any ephemeral
  surface. The member row is the durable law; this namespace is the
  only thing that reads it on the ephemeral side, so presence and
  intents ask the SAME question of the SAME cache and can never drift
  into two answers (the first cut cached per-registry, and the
  intents stream simply never asked — the skeptic's F1).

  Nothing here writes: the draw/open member actions are the only
  hands, and the curtain gains no table of its own. Suppression is
  REFUSING to store or announce, never remembering.

  THE CACHE: pid → verdict, TTL :ttl-ms (the engine's 2s — a
  household's member-row read is free, and a beat arrives every 5-10s
  per principal, so the store still sees one read per pid per TTL at
  worst). A lookup FAILURE counts as curtained (fail closed: when we
  cannot know, we do not publish — a privacy switch must not fail
  toward leaking) and is never cached, so one store hiccup silences a
  beat, not a quarter-hour. Failures warn once per burst, so \"store
  down\" reads differently from \"everyone left\".

  THE INVALIDATION WIRE: the engine's events dispatcher — the same
  committed-transition feed the intents surface already resolves
  cards from. Every process runs a dispatcher over the shared
  transition log, so a draw_curtain committed ANYWHERE reaches EVERY
  process (its own included) as fast as the log's pg_notify travels;
  each one forgets that pid's cached verdict, and a DRAW also fires
  the watchers — presence evicts the pid's live entries (the
  eviction's diff is the leave frame), intents re-publishes so the
  card closes. The TTL is the backstop for a lost notify, not the
  mechanism. Coupling runs ONE WAY: members.clj knows nothing of any
  of this (a security-core file gains no new caller), the ephemeral
  surfaces depend on the curtain, and the curtain depends only on the
  log everyone already writes to.

  An engine without the member kind keeps no curtains: nobody can
  draw one, so nobody is behind one."
  (:require [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 curtain: " parts))))

(def curtain-actions
  "The two member transitions that move a curtain. Seasons excludes
  these from its work rhythm by the same names (person-rhythm, never
  work), so the set lives here, once."
  #{"draw_curtain" "open_curtain"})

;; ── the lookup (a member field, read-only here) ─────────────────────

(defn member-lookup
  "The default curtain source: this pid's member row — found by id,
  or by the bound subject a :bind wrote (gate!'s own two resolutions,
  because a bound member's ephemeral entries are keyed by its
  PRINCIPAL id while its curtain lives on the row). One tx,
  read-only, called through the cache below."
  [eng]
  (if (nil? (get (inv/resources eng) :member))
    (fn [_pid] false)
    (fn [pid]
      (let [st (:storage eng)]
        (store/with-tx st
          (fn [tx]
            (let [row (or (store/load-row st tx :member pid {})
                          (first (store/query-rows st tx :member
                                                   {:subject pid}
                                                   {:limit 1})))]
              (true? (get-in row [:data :curtain])))))))))

(defn- member-pids
  "Every principal id one member row answers to — its own id and the
  subject a :bind wrote. The invalidation names a ROW; the surfaces
  key their entries by PRINCIPAL, so the wire has to speak both."
  [eng rid]
  (into #{rid}
        (try
          (let [st (:storage eng)]
            (when (and st (get (inv/resources eng) :member))
              (some-> (store/with-tx st
                        (fn [tx] (store/load-row st tx :member rid {})))
                      (get-in [:data :subject])
                      vector)))
          (catch Exception e
            (warn! "could not read member " rid " for invalidation: "
                   (ex-message e) " — the bound subject waits for the TTL")
            nil))))

;; ── the shared verdict ──────────────────────────────────────────────

(defn curtained?
  "Is this pid behind its curtain? Cached (:ttl-ms); fresh? bypasses
  AND refreshes the cache — the presence sweep's spelling. A lookup
  failure answers TRUE (fail closed) and is never cached."
  ([cur pid] (curtained? cur pid false))
  ([cur pid fresh?]
   (let [now (System/currentTimeMillis)
         c (get @(:cache cur) pid)]
     (if (and (not fresh?) c
              (<= (- now (long (:at c))) (long (:ttl-ms cur))))
       (:val c)
       (let [v (try (boolean ((:lookup cur) pid))
                    (catch Exception e
                      ;; once per burst: a silenced board should be
                      ;; readable as an outage in the log, and a
                      ;; per-beat warning would bury it
                      (when (compare-and-set! (:failing? cur) false true)
                        (warn! "lookup failed for " pid ": " (ex-message e)
                               " — suppressing (fail closed); further"
                               " failures in this burst stay quiet"))
                      ::unknown))]
         (if (= ::unknown v)
           true
           (do (reset! (:failing? cur) false)
               (swap! (:cache cur) assoc pid {:val v :at now})
               v)))))))

(defn cached-verdict
  "This pid's verdict IF this process already holds a LIVE cached one
  — a plain atom deref, no store I/O — else `default`. This is the
  one curtain reader a surface may call with its registry lock held.

  It exists for the prefetch→lock window. A surface resolves its
  whole round's verdicts before taking the lock (verdicts below), so
  a principal that arrives DURING that window is in no prefetched
  map; reading its absence as \"curtained\" evicted LIVE principals
  and fanned spurious leaves. But the arriving thread has already
  warmed this cache through its own door's curtained? call, so the
  answer is sitting right here — it need only be read without going
  to the store. A pid this process has never asked about stays
  UNKNOWN and gets the caller's `default`, which on any publishing
  path is `true`: we still do not publish what we cannot know.

  An expired entry is NOT knowledge (curtained? re-reads one too), so
  it answers `default` as well — a forgotten verdict is exactly what
  the invalidation wire leaves behind on a draw."
  [cur pid default]
  (let [c (get @(:cache cur) pid)]
    (if (and c (<= (- (System/currentTimeMillis) (long (:at c)))
                   (long (:ttl-ms cur))))
      (:val c)
      default)))

(defn verdicts
  "The curtain for MANY pids at once — every caller's pre-lock
  prefetch. A cache miss reads the store, and a store read taken
  under a registry's lock would park every door behind the database
  (the skeptic's F5), so the surfaces resolve the whole round here
  first and carry a plain map inside."
  [cur pids fresh?]
  (persistent!
   (reduce (fn [m pid] (assoc! m pid (curtained? cur pid (boolean fresh?))))
           (transient {})
           (distinct pids))))

(defn forget!
  "Drop one pid's cached verdict — the invalidation's whole local
  effect. The next ask reads the row."
  [cur pid]
  (swap! (:cache cur) dissoc pid)
  nil)

;; ── the watchers (a DRAW is an event, not just a fact) ───────────────

(defn watch!
  "Register f, called with a pid whose curtain just DREW — presence
  evicts, intents re-publishes. Returns the key stop!/unwatch! needs.
  A watcher's throw is warned and swallowed: the invalidation is
  everyone's, so one surface's bad day must not eat another's."
  [cur f]
  (let [k (str (random-uuid))]
    (swap! (:watchers cur) assoc k f)
    k))

(defn unwatch! [cur k]
  (swap! (:watchers cur) dissoc k)
  nil)

(defn- curtain-transition?
  [evt]
  (and (some? (:kind evt))
       (= "member" (name (:kind evt)))
       (some? (:action evt))
       (contains? curtain-actions (name (:action evt)))))

(defn note-transition!
  "One committed transition: when it moved a curtain, forget the
  affected pids' verdicts everywhere this process caches them, and on
  a DRAW wake the watchers. Called for every transition the
  dispatcher delivers — local commits included, which is exactly why
  the post-draw refusal and the post-open reopening are both
  immediate rather than TTL-shaped."
  [cur eng evt]
  (when (curtain-transition? evt)
    (let [pids (member-pids eng (:resource-id evt))
          drawn? (= "draw_curtain" (name (:action evt)))]
      (doseq [pid pids] (forget! cur pid))
      (when drawn?
        (doseq [f (vals @(:watchers cur))
                pid pids]
          (try (f pid)
               (catch Exception e
                 (warn! "watcher failed for " pid ": " (ex-message e))))))))
  nil)

;; ── lifecycle ───────────────────────────────────────────────────────

(defn start!
  "The shared curtain: a lookup, its TTL cache, and — when the caller
  hands it the engine's events dispatcher — the consumer that
  invalidates on every committed draw/open. opts {:lookup (a fn pid →
  truthy, tests' seam and presence's :curtained? passthrough;
  default reads the member row), :ttl-ms (default 2000),
  :dispatcher}. Returns the component; stop! ends it."
  [eng {:keys [lookup ttl-ms dispatcher]}]
  ;; a curtain with NO dispatcher has no invalidation wire: a draw
  ;; committed anywhere reaches it only when the TTL expires, and the
  ;; watchers (presence's eviction, intents' re-publish) never fire at
  ;; all. That is a legitimate standalone/test shape, but it is a
  ;; degraded one and it used to be SILENT — so it says so, once, on
  ;; the way up
  (when-not dispatcher
    (warn! "started with no :dispatcher — no invalidation wire, so a"
           " committed draw is honored only when the " (or ttl-ms 2000)
           "ms cache expires and no watcher fires. The engine's shared"
           " curtain carries the wire; this is the standalone shape."))
  ;; :kinds #{:member} — the log is the household's whole traffic and
  ;; only the member kind can carry a curtain; the dispatcher filters
  ;; before the queue, so this consumer never wakes for a task
  (let [sub (when dispatcher (events/subscribe dispatcher {:kinds #{:member}}))
        cur {:eng eng
             :lookup (or lookup (member-lookup eng))
             :cache (atom {})
             :ttl-ms (long (or ttl-ms 2000))
             :failing? (atom false)
             :watchers (atom {})
             :running (atom true)
             :dispatcher dispatcher
             :sub sub}
        t (when sub
            (Thread.
             ^Runnable
             (fn []
               (loop []
                 (let [evt (events/take-event sub 60000)]
                   (cond
                     (= ::events/closed evt) nil
                     (nil? evt) (when @(:running cur) (recur))
                     :else
                     (do (when @(:running cur)
                           (try (note-transition! cur eng evt)
                                (catch Exception e
                                  (warn! "invalidate: " (ex-message e)))))
                         (when @(:running cur) (recur)))))))
             "waymark10-curtain"))]
    (some-> ^Thread t (doto (.setDaemon true) (.start)))
    (assoc cur :thread t)))

(defn stop! [cur]
  (reset! (:running cur) false)
  (when-some [sub (:sub cur)]
    (events/unsubscribe (:dispatcher cur) sub))
  (some-> ^Thread (:thread cur) .interrupt)
  nil)
