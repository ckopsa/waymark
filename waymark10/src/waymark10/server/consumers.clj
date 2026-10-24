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

  A FAMILY (register-family!, docs/spec-events.md §3) is the shape of
  the durable listeners: one thread reads the log once for many
  listener rows, each with its own cursor `<family>:<row id>`, and each
  member keeps the contract above for itself alone. The webhook
  deliverer is the family `webhook`."
  (:require [waymark10.server.events :as events]
            [waymark10.server.listen :as listen]
            [waymark10.server.store :as store])
  (:import (java.util.concurrent ExecutorService Executors ThreadFactory)))

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
  and never be heard. With a writer in flight the seed is the newest
  id under it, and the drains after deliver the ids above. → the
  position, or nil when the log has no row under the writers in
  flight and they outlasted `seed-tries`. No cursor is written then —
  a seed that read no rows is not an empty log, and seeding it at 0
  would replay the whole log — and the next drain seeds."
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

;; ── families: one read, a cursor per member (docs/spec-events.md §3) ─

(defn- said [^Throwable e]
  (or (ex-message e) (.getName (class e))))

(def stop
  "What a member's :deliver answers to stop that member where it is for
  this pass: the event is not delivered, the cursor stays before it and
  no failure is recorded. The member waits on something the log does
  not carry, such as a secret with no value yet."
  ::stop)

(defn member-cursor
  "The cursor row of one family member: `<family>:<row id>` (E-2.2)."
  ^String [family id]
  (str (name family) ":" id))

(defn seed-member!
  "A member with no cursor hears the log from its own create (E-2.3):
  the OLDEST transition of its listener row, not the newest of the log.
  Not the newest: a restate, or a pause and a resume, made before the
  first drain is a later transition of the row, and a seed there would
  pass over every event since the create.

  THIS READ IS NOT SETTLED, and needs no retry. The seed is the row's
  own create, the same id whenever it is read: a lower id still in
  flight took its place in the log before the row did, and is not the
  member's to hear. → the position."
  [eng family kind id]
  (let [st (:storage eng)
        pos (or (:id (first (store/with-tx st
                              (fn [tx]
                                (store/transitions
                                 st tx {:kind kind :resource-id id}
                                 {:limit 1})))))
                0)]
    (store/with-tx st #(store/cursor-set! st % (member-cursor family id) pos))
    pos))

(defn- member-position [eng family {:keys [kind]} row]
  (let [st (:storage eng)]
    (or (store/with-tx st #(store/cursor-get st % (member-cursor family (:id row))))
        (seed-member! eng family (or kind (:kind row)) (:id row)))))

(defn- settled-batch [eng cursor]
  (let [st (:storage eng)]
    (store/with-tx st
      ;; :settled — never past a writer still in flight
      (fn [tx] (store/transitions st tx {:since cursor}
                                  {:limit 200 :settled true})))))

(defn drain-member!
  "One member's drain from its own cursor, on the caller's thread
  (E-2.3). Each event its entry hears goes to (deliver row t), and the
  cursor moves after deliver returns. A deliver that throws is judged
  by (policy row): :skip calls (on-skip row t e) and moves on; :fail,
  the default, calls (on-park row t e) and parks the member at that
  event, and the next drain retries it. A deliver that answers `stop`
  stops the member there. An event the entry does not hear costs one
  predicate call: the cursor passes it at the end of the batch, not per
  event. → the number of events delivered."
  [eng family {:keys [entry ignore deliver policy on-skip on-park] :as spec} row]
  (let [st (:storage eng)
        id (:id row)
        heard (entry row)
        move! (fn [pos]
                (store/with-tx st #(store/cursor-set! st % (member-cursor family id) pos)))
        parks? (fn [t e]
                 (if (= :skip (when policy (policy row)))
                   (do (if on-skip
                         (on-skip row t e)
                         (warn! (name family) " member " id " skipped event "
                                (:id t) " (" (said e) ")"))
                       false)
                   (do (if on-park
                         (on-park row t e)
                         (warn! (name family) " member " id " refused event "
                                (:id t) " (" (said e)
                                "); parking — the next drain retries it"))
                       true)))]
    (loop [cursor (member-position eng family spec row) n 0]
      (let [rows (settled-batch eng cursor)
            [pos moved n' halted?]
            (reduce
             (fn [[pos moved n _] t]
               (if (or (and ignore (ignore t))
                       (not (listen/matches? eng heard t)))
                 [(:id t) moved n false]
                 (let [out (try (deliver row t)
                                (catch InterruptedException e (throw e))
                                (catch Exception e e))]
                   (cond
                     (= stop out) (reduced [pos moved n true])
                     (not (instance? Exception out))
                     (do (move! (:id t)) [(:id t) (:id t) (inc n) false])
                     (parks? t out) (reduced [pos moved n true])
                     :else (do (move! (:id t)) [(:id t) (:id t) n false])))))
             [cursor cursor n false] rows)]
        (when (not= pos moved) (move! pos))
        (if (and (not halted?) (= 200 (count rows)))
          (recur pos (long n'))
          n')))))

(defn drain-family!
  "One pass of a family, on the caller's thread (E-2.2, E-2.4). The
  members at the family's read position share ONE read of the log: a
  transition costs one predicate call for each of them, and each that
  heard nothing in a batch has its cursor moved past it at the batch's
  end. A member that hears a transition leaves the shared read there,
  and a member whose cursor is behind (it was parked or paused, or it
  is new) never joins it: each is handed to `run`, to drain from its
  own cursor, so none holds the others back. A predicate that throws
  hands its member over too, and its drain says why. `drain` (spec)
  defaults to drain-member!, `run` (opts) to calling drain inline."
  ([eng family spec] (drain-family! eng family spec {}))
  ([eng family {:keys [members entry ignore drain] :as spec} {:keys [run]}]
   (let [st (:storage eng)
         drain (or drain #(drain-member! eng family spec %))
         run (or run drain)
         rows (vec (members))
         at (into {} (map (juxt :id #(member-position eng family spec %))) rows)
         head (reduce max 0 (vals at))
         {group true behind false} (group-by #(= head (at (:id %))) rows)
         hears? (fn [[_ e] t]
                  (try (boolean (listen/matches? eng e t))
                       (catch Exception _ true)))
         heard (loop [cursor head
                      group (mapv (juxt identity entry) group)
                      heard []]
                 (if (empty? group)
                   heard
                   (let [batch (settled-batch eng cursor)
                         [group heard]
                         (reduce (fn [[g h] t]
                                   (if (and ignore (ignore t))
                                     [g h]
                                     (let [{hit true miss false} (group-by #(hears? % t) g)]
                                       [(vec miss) (into h (map first) hit)])))
                                 [group heard] batch)
                         last-id (:id (last batch))]
                     (when last-id
                       (doseq [[row _] group]
                         (store/with-tx st
                           #(store/cursor-set! st % (member-cursor family (:id row)) last-id))))
                     (if (= 200 (count batch))
                       (recur last-id group heard)
                       heard))))]
     (doseq [row (concat behind heard)]
       (run row)))))

(defn- drain-while-asked!
  "What one pool thread does for one member: drain it, and drain it
  again while a pass came in the meantime — that pass found it in
  flight and could only mark it :again. The member is read anew
  (`reload`) for each further pass, so one that is no longer a member
  is left alone."
  [family {:keys [drain reload]} row in-flight]
  (let [id (:id row)]
    (try
      (loop [row row]
        (drain row)
        (let [[was _] (swap-vals! in-flight
                                  #(if (= :again (get % id))
                                     (assoc % id :running)
                                     (dissoc % id)))]
          (when (= :again (get was id))
            (if-some [fresh (when-not (.isInterrupted (Thread/currentThread))
                              (reload row))]
              (recur fresh)
              (swap! in-flight dissoc id)))))
      (catch Throwable e
        (swap! in-flight dissoc id)
        (warn! (name family) " member " id " stopped: " (said e))))))

(defn- dispatch-family!
  "One pass that waits for nobody: the shared read, and each member it
  hands over goes to the pool. `in-flight` (member id → :running or
  :again) keeps ONE drain per member — its cursor is unguarded — so a
  member already in flight is marked :again and stays out of the
  shared read."
  [eng family spec ^ExecutorService pool in-flight]
  (drain-family!
   eng family
   (assoc spec :members
          (fn []
            (remove (fn [row]
                      (let [id (:id row)
                            [was _] (swap-vals! in-flight
                                                #(cond-> % (contains? % id)
                                                   (assoc id :again)))]
                        (contains? was id)))
                    ((:members spec)))))
   {:run (fn [row]
           (let [id (:id row)
                 [was _] (swap-vals! in-flight
                                     #(assoc % id (if (contains? % id) :again :running)))]
             (when-not (contains? was id)
               (try
                 (.execute pool
                           ^Runnable
                           (fn [] (drain-while-asked! family spec row in-flight)))
                 (catch Throwable e
                   (swap! in-flight dissoc id)
                   (throw e))))))}))

(defn register-family!
  "Register a family of durable listeners (E-2.2): one thread reads the
  log for all its members on every wake of the dispatcher, and each
  member with something to hear drains from its own cursor on a pool of
  `:drainers` threads (8), so a slow receiver costs the others nothing.
  spec: :members (fn []) the active listener rows, :entry (fn [row]) the
  row as a listen entry, :deliver (fn [row t]) the receiver, which may
  throw or answer `stop`, and :policy (fn [row]) :fail or :skip.
  Optional: :kind (the rows' kind, for the seed), :ignore (fn [t]) for
  transitions no member hears, :on-skip and :on-park (fn [row t e]),
  :drain (fn [row]) in place of drain-member!, and :reload (fn [row])
  the member anew, or nil when it is no longer one. opts: :dispatcher
  (the running engine's when absent), :poll-ms (2000). Any Throwable
  in the loop is logged and the loop goes on. Returns the running
  family; stop-family! ends it."
  ([eng family spec] (register-family! eng family spec {}))
  ([eng family spec {:keys [poll-ms drainers] :or {poll-ms 2000 drainers 8} :as opts}]
   (let [dispatcher (if (contains? opts :dispatcher)
                      (:dispatcher opts)
                      (or (some-> (:runtime eng) deref :dispatcher)
                          (throw (ex-info
                                  (str "no dispatcher: start the engine or pass "
                                       ":dispatcher explicitly")
                                  {:family family}))))
         spec (cond-> spec
                (nil? (:drain spec))
                (assoc :drain #(drain-member! eng family spec %))
                (nil? (:reload spec))
                (assoc :reload (fn [row]
                                 (some #(when (= (:id row) (:id %)) %)
                                       ((:members spec))))))
         sub (events/subscribe dispatcher {})
         running (atom true)
         pool (Executors/newFixedThreadPool
               (int drainers)
               (reify ThreadFactory
                 (newThread [_ r]
                   (doto (Thread. ^Runnable r (str "waymark10-" (name family) "-drain"))
                     (.setDaemon true)))))
         in-flight (atom {})
         pass! #(dispatch-family! eng family spec pool in-flight)
         t (Thread.
            ^Runnable
            (fn []
              ;; one pass at startup: an outage replays, never drops
              (try (pass!)
                   (catch Throwable e
                     (warn! "family " (name family) " startup pass failed: " (said e))))
              (while @running
                (try
                  (let [evt (events/take-event sub poll-ms)]
                    (when-not (= ::events/closed evt)
                      (pass!)))
                  (catch InterruptedException _ nil)
                  (catch Throwable e
                    (when @running
                      (warn! "family " (name family) " loop: " (said e)))))))
            (str "waymark10-" (name family)))]
     (doto ^Thread t (.setDaemon true) (.start))
     {:family family :thread t :running running :dispatcher dispatcher
      :sub sub :pool pool})))

(defn stop-family!
  "Stop the family's thread and its pool; every member's cursor row
  stays, so registering the family again resumes each where it was."
  [{:keys [running dispatcher sub ^Thread thread ^ExecutorService pool]}]
  (reset! running false)
  (events/unsubscribe dispatcher sub)
  (some-> thread .interrupt)
  (some-> pool .shutdownNow)
  nil)
