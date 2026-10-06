(ns waymark10.server.consumers
  "Consumers-as-API (batch F): named, durable log consumers. A
  consumer is a function of one transition record; its position is a
  cursor row in waymark10_cursors (consumer:<name>), persisted per
  processed event — so a restart resumes instead of replaying or
  dropping, exactly the webhook deliverer's at-least-once discipline
  (whose cursor rows this table already holds). The dispatcher is
  only the wake signal; the log carries truth.

  The contract, each clause a sentence:
  - At-least-once: the cursor advances AFTER the function returns, so
    a crash between the call and the checkpoint re-delivers that
    event — consumers must tolerate a replay (the log id makes dedupe
    one comparison).
  - A THROWING consumer parks: the drain stops at the refusing event
    (cursor unmoved, a warning on *err*) and the next wake retries it
    — nothing is skipped silently, the webhook \"fail\" posture.
  - A new consumer hears the world from its REGISTRATION: the first
    drain seeds the cursor at the newest transition id, so history is
    not replayed into a consumer that never asked for it; pass
    {:from-origin? true} to hear everything the log holds.
  - Events arrive in id order, one at a time, on the consumer's own
    thread — a slow consumer delays only itself.

  Recorded boundary — the webhook deliverer is NOT refactored onto
  this: it predates the API, its drain is per-subscription (N cursors
  behind one thread) where this is per-consumer, and unifying the two
  is a real simplification deferred until a third consumer of the
  pattern exists (docs/waymark10-f-notes.md names it)."
  (:require [waymark10.server.events :as events]
            [waymark10.server.store :as store]))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 consumers: " parts))))

(defn- cursor-name ^String [name*]
  (str "consumer:" (name name*)))

(def ^:private seed-tries
  "How many times a seed asks for the settled log before it leaves the
  consumer unseeded for its next drain."
  4)

(defn- seed-cursor!
  "First registration: the consumer hears the world from now — the
  newest transition id (0 with :from-origin?, or an empty log). The
  read is SETTLED, so every id at or below the seed has committed by
  the time the seed is written: seeded at the newest COMMITTED id
  alone, a lower id still in flight would commit after registration
  and never be heard. → the position, or nil when the writers in
  flight outlasted `seed-tries`. No cursor is written then — a seed
  that read no rows is not an empty log, and seeding it at 0 would
  replay the whole log — and the next drain seeds."
  [eng name* from-origin?]
  (let [st (:storage eng)
        pos (if from-origin?
              0
              (when-some [rows (store/settled-transitions
                                st {} {:newest-first true :limit 1}
                                seed-tries)]
                (or (:id (first rows)) 0)))]
    (if pos
      (do (store/with-tx st #(store/cursor-set! st % (cursor-name name*) pos))
          pos)
      (do (warn! "consumer " (name name*) " not seeded: the log did not"
                 " settle; the next drain seeds it")
          nil))))

(defn drain-consumer!
  "One synchronous drain: deliver every transition past the named
  cursor to f, checkpointing per event; a throw parks the cursor at
  the refusing event and returns. Tests call this directly for
  determinism; the registered consumer's thread calls it on every
  wake. A consumer whose seed could not be read (seed-cursor!)
  processes nothing on this drain. → the number of events processed."
  [eng name* f & [{:keys [from-origin?]}]]
  (let [st (:storage eng)
        consumer (cursor-name name*)
        cursor (or (store/with-tx st #(store/cursor-get st % consumer))
                   (seed-cursor! eng name* from-origin?))]
    (if-not cursor
      0
      (loop [cursor cursor n 0]
        (let [rows (store/with-tx st
                     ;; :settled — the read stays behind writers in
                     ;; flight, so a lower id cannot commit after the
                     ;; cursor has passed it
                     (fn [tx] (store/transitions st tx {:since cursor}
                                                 {:limit 200 :settled true})))
              outcome
              (reduce
               (fn [[_cursor n] t]
                 (try
                   (f t)
                   (store/with-tx st
                     #(store/cursor-set! st % consumer (:id t)))
                   [(:id t) (inc n)]
                   (catch Exception e
                     (warn! "consumer " (name name*) " refused event "
                            (:id t) " (" (ex-message e)
                            "); parking — the next drain retries it")
                     (reduced [::parked n]))))
               [cursor n] rows)
              [cursor' n'] outcome]
          (if (and (not= ::parked cursor') (= 200 (count rows)))
            (recur cursor' (long n'))
            n'))))))

(defn register-consumer!
  "Register a named, durable log consumer: (f transition) for every
  transition past the consumer's cursor, at-least-once, riding the
  dispatcher as its wake signal (take-event's timeout is the poll
  backstop, default 2s) exactly as the webhook deliverer does. The
  2-arity reads the running engine's dispatcher (engine start!'s
  :runtime); pass one explicitly when driving a bare dispatcher in a
  test. opts: :poll-ms, :from-origin? (hear the whole log, not just
  the world after registration). Returns the running consumer;
  stop-consumer! ends it."
  ([eng name* f] (register-consumer! eng name* f {}))
  ([eng name* f {:keys [dispatcher poll-ms from-origin?]
                 :or {poll-ms 2000}}]
   (let [dispatcher (or dispatcher
                        (some-> (:runtime eng) deref :dispatcher)
                        (throw (ex-info
                                (str "no dispatcher: start the engine or pass "
                                     ":dispatcher explicitly")
                                {:consumer name*})))
         sub (events/subscribe dispatcher {})
         ;; The cursor is pinned HERE, on the caller's thread, before the
         ;; consumer thread exists. Seeding it lazily on that thread's
         ;; first drain left a window between "registered" and "first
         ;; poll" in which a committed write could land past the seed
         ;; and never be delivered — invisible on an idle laptop, routine
         ;; on a loaded 8-core node running six shards. At-least-once
         ;; starts at registration, not at whenever the scheduler gets
         ;; round to the thread.
         _ (let [st (:storage eng)]
             (when-not (store/with-tx st #(store/cursor-get st % (cursor-name name*)))
               (seed-cursor! eng name* from-origin?)))
         running (atom true)
         processed (atom 0)
         drain! (fn []
                  (try
                    (swap! processed + (drain-consumer! eng name* f
                                                        {:from-origin? from-origin?}))
                    (catch Exception e
                      (when @running
                        (warn! "consumer " (name name*) " drain failed: "
                               (ex-message e))))))
         t (Thread.
            ^Runnable
            (fn []
              ;; drain once at startup: an outage replays, never drops
              (drain!)
              (while @running
                (try
                  (let [evt (events/take-event sub poll-ms)]
                    (when-not (= ::events/closed evt)
                      (drain!)))
                  (catch InterruptedException _ nil)
                  (catch Exception e
                    (when @running
                      (warn! "consumer " (name name*) " loop: "
                             (ex-message e)))))))
            (str "waymark10-consumer-" (name name*)))]
     (doto ^Thread t (.setDaemon true) (.start))
     {:name name* :thread t :running running :dispatcher dispatcher
      :sub sub :processed processed})))

(defn stop-consumer!
  "Stop the consumer's thread; its cursor row stays — re-registering
  the same name resumes from exactly where it stopped."
  [{:keys [running dispatcher sub ^Thread thread]}]
  (reset! running false)
  (events/unsubscribe dispatcher sub)
  (some-> thread .interrupt)
  (some-> thread (.join 5000))
  nil)
