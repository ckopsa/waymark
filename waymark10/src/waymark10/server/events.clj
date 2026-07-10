(ns waymark10.server.events
  "Events (phase 6): the transactional outbox on the wire. The
  transition log IS the outbox — append-transition! pg_notifies
  waymark10_transitions inside the write's transaction, so an event
  exists iff its commit does. This namespace builds the dispatcher
  over it and the SSE surface the router exposes.

  The dispatcher: one dedicated LISTEN connection (raw pgjdbc, never
  from the Hikari pool — getNotifications parks it) plus a poll
  backstop; every wake-up (notification OR the poll interval, default
  2s) reads the log since the last-seen id and delivers, so
  notifications carry liveness only and the log carries truth.
  Delivery is exactly-once per connection in id order by
  construction: the single dispatcher thread is the only live
  enqueuer and it only ever advances past ids it has delivered —
  dedupe across notify/poll is the since-last-seen read itself, and a
  per-subscription delivered-id floor keeps a replay horizon honest
  when the dispatcher's poll lags the subscribe.

  Replay: a subscription with :since (SSE Last-Event-ID) is born
  paused — live deliveries buffer in :pending while the backlog
  enqueues from the log, then pending events beyond the replayed
  horizon flush and the stream goes live. No gaps, no duplicates;
  at-least-once across reconnects (the client replays from its last
  id).

  Recorded limits, each a sentence:
  - Commit-order vs id-order: two interleaved writes can commit out
    of id order, and a since-id poll that has advanced past a
    not-yet-visible lower id skips it — waymark9's dispatcher had the
    identical property; the row lock serializes per-resource, so a
    single resource's stream never gapes.
  - A slow consumer's full queue (1024) drops with a warning —
    truth is replayable from the log by Last-Event-ID; only liveness
    is lost.
  - Event frames carry the stored summary bytes, written under the
    law law_revision names — log prose is never re-rendered.

  DERIVATION-CLASS EVENTS (batch C — waymark9's second channel comes
  home). Maintenance changes — cross-row count/sum recomputes, clock
  flips, bulk law restamps — are not transitions (no version, no log
  row), so they get their own outbox: waymark10_observations (id
  bigserial, kind, resource_id NULLABLE — a bulk restamp is
  kind-wide, class, changed jsonb, at), appended INSIDE the
  maintenance write's transaction with pg_notify on its own channel
  (waymark10_observations), so an observation exists iff its commit
  does. The dispatcher LISTENs both channels and drains both tables;
  a subscription opts into classes ({:classes #{:transition
  :derivation}}, default transitions-only so the coherence consumer
  and the webhook deliverer — which ride subscriptions as transition
  feeds — never see a shape they predate). SSE frames derivations as
  `event: derivation` with NO id line: Last-Event-ID replay covers
  transitions only (recorded scope — the observations table is the
  record; a consumer that missed a flip re-derives from the
  envelope). Derivations bypass a replaying subscription's pause
  (their ids are a different sequence; ordering across classes is not
  promised — recorded). Observations are a Postgres surface: on any
  other backend record-observation! is a warned no-op (recorded
  scope; the store protocol is another batch's file, so the DDL and
  reads live here).

  SSE: http-kit channels, one writer thread per connection; a
  heartbeat comment frame every :sse-heartbeat-ms (default 15s)
  doubles as the disconnect probe — send! returns false on a closed
  channel and the writer unsubscribes, so a silent disconnect cleans
  up within one heartbeat. Engines built without engine/start! have
  no dispatcher; the router answers 503 (documented pick — lazy-start
  would hide a lifecycle the operator should own)."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [org.httpkit.server :as http]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.wire :as wire])
  (:import (java.lang.reflect Field)
           (java.nio.channels SelectionKey)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)
           (org.httpkit.server AsyncChannel)
           (org.postgresql PGConnection)
           (org.postgresql.util PGobject)))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 events: " parts))))

;; ── the derivation outbox (batch C) ─────────────────────────────────

(def observations-channel "waymark10_observations")

(def ^:private jdbc-opts {:builder-fn rs/as-unqualified-lower-maps})

(def ^:private observations-ddl
  ["CREATE TABLE IF NOT EXISTS waymark10_observations (
      id bigserial PRIMARY KEY,
      kind text NOT NULL,
      resource_id text,
      class text NOT NULL,
      changed jsonb,
      at timestamptz NOT NULL DEFAULT now())"
   "CREATE INDEX IF NOT EXISTS ix_wm10_obs_kind
      ON waymark10_observations (kind, id)"])

(defn- pg-storage? [st]
  (instance? waymark10.server.store.postgres.PostgresStorage st))

(defonce ^:private obs-ensured (atom #{}))

(defn- ensure-observations! [storage tx]
  (when-not (contains? @obs-ensured storage)
    (doseq [sql observations-ddl]
      (jdbc/execute! tx [sql]))
    (swap! obs-ensured conj storage)))

(defn record-observation!
  "Append one derivation-class observation INSIDE the caller's
  transaction — the maintenance write's outbox half. class is
  \"recompute\" | \"flip\" | \"restamp\"; resource-id nil means the
  whole kind (a bulk restamp). changed is a JSON-able value (fact
  names, or the restamp's revision map)."
  [storage tx {:keys [kind resource-id class changed]}]
  (if-not (pg-storage? storage)
    (warn! "derivation observations are a Postgres surface; dropping "
           class " on " (name kind) " (recorded scope)")
    (do
      (ensure-observations! storage tx)
      (let [res (jdbc/execute-one!
                 tx
                 ["INSERT INTO waymark10_observations
                     (kind, resource_id, class, changed)
                   VALUES (?, ?, ?, ?) RETURNING id"
                  (name kind) resource-id (str class)
                  (when (some? changed)
                    (doto (PGobject.)
                      (.setType "jsonb")
                      (.setValue (wire/write-json changed))))]
                 jdbc-opts)]
        (jdbc/execute-one! tx ["SELECT pg_notify(?, ?)"
                               observations-channel (str (:id res))])
        (:id res)))))

(defn- obs->event [r]
  {::class :derivation
   :id (:id r)
   :kind (keyword (:kind r))
   :resource-id (:resource_id r)
   :class (:class r)
   :changed (when-some [c (:changed r)]
              (wire/read-json (.getValue ^PGobject c)))
   :at (when-some [t (:at r)]
         (if (instance? java.sql.Timestamp t)
           (.toInstant ^java.sql.Timestamp t)
           t))})

(defn- observations-since [storage after limit]
  (if-not (pg-storage? storage)
    []
    (store/with-tx storage
      (fn [tx]
        (ensure-observations! storage tx)
        (mapv obs->event
              (jdbc/execute! tx
                             [(str "SELECT * FROM waymark10_observations"
                                   " WHERE id > ? ORDER BY id LIMIT " (long limit))
                              (long after)]
                             jdbc-opts))))))

(defn- last-observation-id [storage]
  (if-not (pg-storage? storage)
    0
    (store/with-tx storage
      (fn [tx]
        (ensure-observations! storage tx)
        (or (:id (jdbc/execute-one!
                  tx ["SELECT max(id) AS id FROM waymark10_observations"]
                  jdbc-opts))
            0)))))

;; ── subscriptions ───────────────────────────────────────────────────

(defn- wants? [sub t]
  (and (contains? (:classes sub) (::class t :transition))
       (cond
         (:resource sub) (and (= (:kind t) (first (:resource sub)))
                              ;; a kind-wide observation (bulk restamp,
                              ;; resource_id nil) reaches every watcher
                              ;; of the kind's rows
                              (or (nil? (:resource-id t))
                                  (= (:resource-id t) (second (:resource sub)))))
         (:kinds sub) (contains? (:kinds sub) (:kind t))
         :else true)))

(defn- deliver-event! [sub t]
  (locking (:state sub)
    (let [{:keys [paused delivered]} @(:state sub)]
      (cond
        ;; derivations ride a separate id sequence and promise no
        ;; replay: they bypass the transition floor and the replay
        ;; pause (recorded — ordering across classes is not promised)
        (= :derivation (::class t))
        (when-not (.offer ^LinkedBlockingQueue (:queue sub) t)
          (warn! "subscriber queue full; dropping derivation " (:id t)
                 " — the next transition refetch re-derives it"))

        paused
        (swap! (:state sub) update :pending conj t)

        ;; the per-subscription floor: exactly-once even when the
        ;; dispatcher's poll lags a replay horizon
        (<= (:id t) delivered) nil

        :else
        (do (swap! (:state sub) assoc :delivered (:id t))
            (when-not (.offer ^LinkedBlockingQueue (:queue sub) t)
              (warn! "subscriber queue full; dropping transition " (:id t)
                     " — replay by Last-Event-ID recovers it")))))))

(defn- log-since [storage after limit]
  (store/with-tx storage
    (fn [tx] (store/transitions storage tx {:since after} {:limit limit}))))

(defn- replay!
  "Enqueue the backlog after after-id, then flush live events buffered
  past the replayed horizon, in order — then go live."
  [d sub after-id]
  (let [rows (loop [acc [] after after-id]
               (let [batch (log-since (:storage d) after 500)]
                 (if (= 500 (count batch))
                   (recur (into acc batch) (:id (last batch)))
                   (into acc batch))))]
    (locking (:state sub)
      (let [delivered
            (reduce (fn [last-id t]
                      (when (wants? sub t)
                        (.offer ^LinkedBlockingQueue (:queue sub) t))
                      (max last-id (:id t)))
                    after-id rows)
            delivered
            (reduce (fn [last-id t]
                      (if (> (:id t) last-id)
                        (do (.offer ^LinkedBlockingQueue (:queue sub) t)
                            (:id t))
                        last-id))
                    delivered
                    (:pending @(:state sub)))]
        (swap! (:state sub) assoc
               :paused false :pending [] :delivered delivered)))))

(defn subscribe
  "→ a subscription. opts: :kinds (set, nil = all), :resource
  [kind id], :since (log id — replay everything after it before going
  live), :classes (set of :transition/:derivation; default
  #{:transition}, so consumers that predate the derivation class —
  coherence, the webhook deliverer — never see a shape they did not
  ask for)."
  [d {:keys [kinds resource since classes]}]
  (let [sub {:id (str (random-uuid))
             :kinds (not-empty (set (or kinds #{})))
             :resource resource
             :classes (or (not-empty (set classes)) #{:transition})
             :queue (LinkedBlockingQueue. 1024)
             :state (atom {:paused (some? since)
                           :pending []
                           :delivered (or since 0)})}]
    (swap! (:subs d) conj sub)
    (when since (replay! d sub since))
    sub))

(defn unsubscribe [d sub]
  (swap! (:subs d) disj sub)
  ;; wake a parked writer immediately rather than at its next heartbeat
  (.offer ^LinkedBlockingQueue (:queue sub) ::closed)
  nil)

(defn take-event
  "The subscription's next transition; nil on timeout, ::closed after
  unsubscribe."
  [sub timeout-ms]
  (.poll ^LinkedBlockingQueue (:queue sub)
         (long timeout-ms) TimeUnit/MILLISECONDS))

;; ── the dispatcher ──────────────────────────────────────────────────

(defn- drain! [d]
  (loop []
    (let [rows (log-since (:storage d) @(:last-seen d) 500)]
      (doseq [t rows]
        (doseq [sub @(:subs d)]
          (when (wants? sub t) (deliver-event! sub t)))
        (swap! (:last-seen d) max (:id t)))
      (when (= 500 (count rows)) (recur))))
  ;; the second class: observations drain on the same wake-ups, their
  ;; own id horizon (a different sequence, deliberately uncompared)
  (loop []
    (let [rows (observations-since (:storage d) @(:last-obs d) 500)]
      (doseq [t rows]
        (doseq [sub @(:subs d)]
          (when (wants? sub t) (deliver-event! sub t)))
        (swap! (:last-obs d) max (:id t)))
      (when (= 500 (count rows)) (recur)))))

(defn dispatcher
  "Start the transition dispatcher for an engine: LISTEN + poll
  backstop feeding subscribers. opts {:poll-ms 2000}. Returns the
  running dispatcher; stop! ends it."
  [eng {:keys [poll-ms] :or {poll-ms 2000}}]
  (let [storage (:storage eng)
        conn (try (pg/listen-connection storage)
                  (catch Exception e
                    (warn! "no LISTEN connection (" (ex-message e)
                           "); running on the poll backstop alone")
                    nil))
        ;; the second channel rides the same parked connection
        _ (when conn
            (try
              (with-open [stmt (.createStatement ^java.sql.Connection conn)]
                (.execute stmt (str "LISTEN " observations-channel)))
              (catch Exception e
                (warn! "observations LISTEN failed (" (ex-message e)
                       "); derivations ride the poll backstop"))))
        pg-conn (some-> ^java.sql.Connection conn (.unwrap PGConnection))
        d {:eng eng
           :storage storage
           :last-seen (atom (or (:id (first (store/with-tx storage
                                              (fn [tx]
                                                (store/transitions
                                                 storage tx {}
                                                 {:newest-first true :limit 1})))))
                                0))
           :last-obs (atom (last-observation-id storage))
           :subs (atom #{})
           :running (atom true)
           :conn conn}
        t (Thread.
           ^Runnable
           (fn []
             (while @(:running d)
               (try
                 ;; the wait IS the backstop: a notification wakes it
                 ;; early, the timeout polls regardless
                 (if pg-conn
                   (.getNotifications ^PGConnection pg-conn (int poll-ms))
                   (Thread/sleep (long poll-ms)))
                 (drain! d)
                 (catch InterruptedException _ nil)
                 (catch Exception e
                   (when @(:running d)
                     (warn! "dispatcher loop: " (ex-message e))
                     (try (Thread/sleep (long poll-ms))
                          (catch InterruptedException _ nil)))))))
           "waymark10-dispatcher")]
    (doto ^Thread t (.setDaemon true) (.start))
    (assoc d :thread t)))

(defn stop! [d]
  (reset! (:running d) false)
  (some-> ^java.sql.Connection (:conn d) .close)
  (some-> ^Thread (:thread d) .interrupt)
  nil)

;; ── the wire shape ──────────────────────────────────────────────────

(defn transition-payload
  "One transition as wire JSON — the SSE frame's data (and, later, the
  webhook delivery's)."
  [eng t]
  (let [rdef (get (inv/resources eng) (:kind t))]
    (p/wire-value
     {:kind (:kind t)
      :self (str "/api/" (:plural rdef) "/" (:resource-id t))
      :action (:action t)
      :from (:from-state t)
      :to (:to-state t)
      :actor (:actor t)
      :at (str (:at t))
      :law-revision (:law-revision t)
      :summary (:summary t)})))

(defn derivation-payload
  "One observation as wire JSON — the derivation frame's data. self is
  nil for a kind-wide observation (a bulk restamp names no row)."
  [eng t]
  (let [rdef (get (inv/resources eng) (:kind t))]
    (p/wire-value
     {:kind (:kind t)
      :self (when (:resource-id t)
              (str "/api/" (:plural rdef) "/" (:resource-id t)))
      :class (:class t)
      :changed (:changed t)
      :at (str (:at t))})))

(defn frame ^String [eng t]
  (if (= :derivation (::class t))
    ;; no id line: Last-Event-ID resumes transitions only — a
    ;; derivation's replay is recomputation from the envelope
    (str "event: derivation\n"
         "data: " (wire/write-json (derivation-payload eng t)) "\n\n")
    (str "event: transition\n"
         "id: " (:id t) "\n"
         "data: " (wire/write-json (transition-payload eng t)) "\n\n")))

;; ── SSE over http-kit ───────────────────────────────────────────────

(def ^:private sse-headers
  {"Content-Type" "text/event-stream"
   "Cache-Control" "no-cache"
   "X-Accel-Buffering" "no"})

(def ^:private channel-key-field
  (delay (doto (.getDeclaredField AsyncChannel "key")
           (.setAccessible true))))

(defn channel-alive?
  "Is this http-kit channel's socket still open? Verified quirk of
  http-kit 2.8.0 plain-HTTP streaming (the #375 per-request
  AsyncChannel): closeKey notifies the KEY ATTACHMENT's stale
  channel, so a streaming response's on-close never fires and send!
  keeps answering true into a closed socket — the docstring's
  'heartbeat doubles as the disconnect probe' held only for
  websockets. The underlying SelectionKey knows the truth (a FIN
  read or a failed selector write invalidates it), so the writer
  threads probe it each heartbeat tick. Reflection, guarded: an
  unreadable field degrades to 'alive' — the old behavior, a leak
  but never a wrong disconnect."
  [ch]
  (try
    (let [^SelectionKey k (.get ^Field @channel-key-field ch)]
      (.isValid k))
    (catch Throwable _ true)))

(defn sse-handler
  "The streaming response for one subscription: replay from :since,
  then live frames; heartbeat comments every hb-ms. The SSE surface
  speaks both event classes — `event: transition` (with ids) and
  `event: derivation` (without; batch C).

  The subscription hook (the presence seam): :on-subscribe fires once
  beside the subscribe, :on-unsubscribe exactly once with the
  unsubscribe (writer-detected disconnects and :on-close race; the
  cleanup gate keeps the hook single-shot) — the router uses the pair
  to register a per-resource stream AS presence. Hook failures warn
  and never touch the stream."
  [eng d {:keys [kinds resource since on-subscribe on-unsubscribe]} req]
  (let [hb-ms (:sse-heartbeat-ms eng 15000)
        sub (subscribe d {:kinds kinds :resource resource :since since
                          :classes #{:transition :derivation}})
        _ (when on-subscribe
            (try (on-subscribe)
                 (catch Exception e
                   (warn! "subscribe hook: " (ex-message e)))))
        done (atom false)
        cleanup! (fn []
                   (when (compare-and-set! done false true)
                     (unsubscribe d sub)
                     (when on-unsubscribe
                       (try (on-unsubscribe)
                            (catch Exception e
                              (warn! "unsubscribe hook: " (ex-message e)))))))]
    (http/as-channel
     req
     {:on-open
      (fn [ch]
        (http/send! ch {:status 200 :headers sse-headers
                        :body ": stream open\n\n"}
                    false)
        (let [t (Thread.
                 ^Runnable
                 (fn []
                   (try
                     (loop []
                       (let [evt (take-event sub hb-ms)]
                         (cond
                           (= ::closed evt) nil
                           (nil? evt) (when (and (http/send! ch ": hb\n\n" false)
                                                 (channel-alive? ch))
                                        (recur))
                           :else (when (and (http/send! ch (frame eng evt) false)
                                            (channel-alive? ch))
                                   (recur)))))
                     (finally (cleanup!))))
                 (str "waymark10-sse-" (:id sub)))]
          (doto ^Thread t (.setDaemon true) (.start))))
      :on-close
      (fn [_ch _status]
        (cleanup!))})))
