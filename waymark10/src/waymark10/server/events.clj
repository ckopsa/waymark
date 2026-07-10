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
  - Derivation-class events (waymark9's second channel) remain a
    named punt — this stream (and the phase-9b webhooks riding it)
    carries transitions only; a consumer re-derives a missed flip
    from the envelope.
  - Event frames carry the stored summary bytes, written under the
    law law_revision names — log prose is never re-rendered.

  SSE: http-kit channels, one writer thread per connection; a
  heartbeat comment frame every :sse-heartbeat-ms (default 15s)
  doubles as the disconnect probe — send! returns false on a closed
  channel and the writer unsubscribes, so a silent disconnect cleans
  up within one heartbeat. Engines built without engine/start! have
  no dispatcher; the router answers 503 (documented pick — lazy-start
  would hide a lifecycle the operator should own)."
  (:require [org.httpkit.server :as http]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.wire :as wire])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)
           (org.postgresql PGConnection)))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 events: " parts))))

;; ── subscriptions ───────────────────────────────────────────────────

(defn- wants? [sub t]
  (cond
    (:resource sub) (= [(:kind t) (:resource-id t)] (:resource sub))
    (:kinds sub) (contains? (:kinds sub) (:kind t))
    :else true))

(defn- deliver-event! [sub t]
  (locking (:state sub)
    (let [{:keys [paused delivered]} @(:state sub)]
      (cond
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
  live)."
  [d {:keys [kinds resource since]}]
  (let [sub {:id (str (random-uuid))
             :kinds (not-empty (set (or kinds #{})))
             :resource resource
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
        pg-conn (some-> ^java.sql.Connection conn (.unwrap PGConnection))
        d {:eng eng
           :storage storage
           :last-seen (atom (or (:id (first (store/with-tx storage
                                              (fn [tx]
                                                (store/transitions
                                                 storage tx {}
                                                 {:newest-first true :limit 1})))))
                                0))
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

(defn frame ^String [eng t]
  (str "event: transition\n"
       "id: " (:id t) "\n"
       "data: " (wire/write-json (transition-payload eng t)) "\n\n"))

;; ── SSE over http-kit ───────────────────────────────────────────────

(def ^:private sse-headers
  {"Content-Type" "text/event-stream"
   "Cache-Control" "no-cache"
   "X-Accel-Buffering" "no"})

(defn sse-handler
  "The streaming response for one subscription: replay from :since,
  then live frames; heartbeat comments every hb-ms."
  [eng d {:keys [kinds resource since]} req]
  (let [hb-ms (:sse-heartbeat-ms eng 15000)
        sub (subscribe d {:kinds kinds :resource resource :since since})]
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
                           (nil? evt) (when (http/send! ch ": hb\n\n" false)
                                        (recur))
                           :else (when (http/send! ch (frame eng evt) false)
                                   (recur)))))
                     (finally (unsubscribe d sub))))
                 (str "waymark10-sse-" (:id sub)))]
          (doto ^Thread t (.setDaemon true) (.start))))
      :on-close
      (fn [_ch _status]
        (unsubscribe d sub))})))
