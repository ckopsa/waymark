(ns waymark10.server.presence
  "Presence — waymark9's follow-me surface restored: your screen goes
  where the followed principal LOOKS, not just where they write.
  Presence is EPHEMERAL STATE, never law: no table, no transitions,
  no fingerprint — an in-process registry fanned across processes on
  its own pg_notify channel (waymark10_presence, origin-nonce'd, the
  collab relay's precedent), TTL-evicted. A restart forgets everyone;
  the next heartbeat re-teaches it.

  REPORTING, two doors, both marked by :source on the wire:
  (a) implicit — a per-resource SSE subscription IS presence (the
      engine already knows the principal and the resource): the
      router's stream hook registers on subscribe and drops on
      disconnect (source \"stream\");
  (b) explicit — POST /api/-/presence {self} is a heartbeat for
      clients that only hold the firehose, the ported UI's case
      (source \"heartbeat\"). Three missed heartbeats evict.

  THE STREAM: GET /api/-/presence (SSE) — a snapshot frame on
  connect, then join/move/leave frames {principal {id, display,
  type}, self, source, at}. No id lines and no replay: presence is
  liveness, the snapshot on connect is the truth, and Last-Event-ID
  means nothing here.

  CONCEALMENT: a scoped principal's stream shows only presences on
  selves it could GET — the request's own visibility closures
  (grants/visibility) judge each frame's self, and a filtered frame
  is BYTE-LEVEL ABSENT, never narrated. An unscoped viewer sees all.
  A scoped principal's own reporting is always accepted (where it
  looks is its own to say; who gets to watch is the grant's).

  CROSS-PROCESS: every local report notifies {origin, pid, entry}
  (drops notify {origin, pid}); each process re-asserts its local
  entries every :presence-heartbeat-ms and evicts a remote entry
  silent for three intervals — a crashed peer's ghosts leave on the
  clock. Frames a viewer sees derive from ONE merged-view diff
  (freshest entry per principal across origins), so local reports,
  remote frames and evictions all speak through the same mouth.

  Recorded boundaries, each a sentence:
  - a principal mid-request (an invoke, a GET) is invisible — only
    held streams and explicit heartbeats register, so firehose-only
    agents appear exactly when they choose to say where they look.
  - a followed principal's move onto a concealed self reads as
    stillness to a scoped viewer (the frame is absent, not narrated
    as departure) — byte-level absence beats an honest-looking lie.
  - presence fan-out is a Postgres surface: on any other backend the
    registry stays process-local, warned once at start.
  - a slow presence subscriber's full queue (256) drops frames with
    a warning — the snapshot on reconnect is the whole recovery.
  - selves cap at 512 chars (each entry rides one pg_notify payload).
  - ordering across processes is liveness-only; two origins' frames
    interleave as they arrive."
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [org.httpkit.server :as http]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            ;; loaded for the PostgresStorage record class alone
            [waymark10.server.store.postgres]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.sql Connection DriverManager)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)
           (org.postgresql PGConnection PGNotification)))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 presence: " parts))))

(def presence-channel "waymark10_presence")

(def self-max-chars
  "The longest self a heartbeat may claim — each entry rides one
  pg_notify payload, whose comfort ceiling is small."
  512)

(defn- pg-storage? [st]
  (instance? waymark10.server.store.postgres.PostgresStorage st))

(defn- author-of [principal]
  {:id (:id principal)
   :type (name (:type principal :human))
   ;; t/principal defaults :display to "" — blank means unnamed
   :display (let [d (:display principal)]
              (if (str/blank? (str d)) (:id principal) d))})

;; ── the registry ────────────────────────────────────────────────────
;;
;; :local    pid → {:entry public-entry :streams {self n} :hb-at ms}
;; :remotes  origin → {pid {:entry public-entry :seen ms}}
;; :published pid → public-entry — the merged truth last announced;
;;            every mutation re-merges and diffs against it, so join/
;;            move/leave frames have exactly one source of judgment.
;; public-entry: {:principal {…} :self :source :at iso :at-ms ms}

(defn- entry-of [reg principal self source]
  {:principal (author-of principal)
   :self self
   :source source
   :at (str ((:now-fn (:eng reg) (fn [] (java.time.Instant/now)))))
   :at-ms (System/currentTimeMillis)})

(defn- frame-of [event e]
  (assoc (select-keys e [:principal :self :source :at]) :event event))

(defn- fan! [reg frame]
  (doseq [sub @(:subs reg)]
    (when ((:visible? sub) (:self frame))
      (when-not (.offer ^LinkedBlockingQueue (:queue sub) frame)
        (warn! "subscriber queue full; dropping a frame — the snapshot"
               " on reconnect is the recovery")))))

(defn- live-local? [{:keys [streams hb-at]} now hb-ms]
  (or (boolean (some pos? (vals streams)))
      (and hb-at (<= (- (long now) (* 3 (long hb-ms))) (long hb-at)))))

(defn- merged-view
  "The one truth: per principal, the freshest entry across this
  process's live locals and every remote origin's."
  [reg now]
  (let [locals (into {}
                     (keep (fn [[pid st]]
                             (when (live-local? st now (:hb-ms reg))
                               [pid (:entry st)])))
                     @(:local reg))]
    (reduce (fn [m [pid e]]
              (if-some [cur (get m pid)]
                (if (> (long (:at-ms e 0)) (long (:at-ms cur 0)))
                  (assoc m pid e) m)
                (assoc m pid e)))
            locals
            (for [[_ entries] @(:remotes reg)
                  [pid {:keys [entry]}] entries]
              [pid entry]))))

(defn- publish!
  "Re-merge, diff against the last announced truth, fan the
  difference as join/move/leave frames. Reentrant under :lock."
  [reg]
  (locking (:lock reg)
    (let [now (System/currentTimeMillis)
          m (merged-view reg now)
          old @(:published reg)]
      (doseq [[pid e] m
              :let [o (get old pid)]]
        (cond
          (nil? o) (fan! reg (frame-of "join" e))
          (not= (:self o) (:self e)) (fan! reg (frame-of "move" e))))
      (doseq [[pid o] old
              :when (not (contains? m pid))]
        (fan! reg (frame-of "leave" o)))
      (reset! (:published reg) m))))

;; ── the cross-process fan ───────────────────────────────────────────

(defn- notify! [reg msg]
  (when (pg-storage? (:storage reg))
    (try
      (store/with-tx (:storage reg)
        (fn [tx]
          (jdbc/execute-one! tx ["SELECT pg_notify(?, ?)" presence-channel
                                 (wire/write-json
                                  (assoc msg :origin (:origin reg)))])))
      (catch Exception e
        (warn! "notify failed: " (ex-message e))))))

(defn- on-notification! [reg ^String payload]
  (let [msg (try (wire/read-json payload) (catch Exception _ nil))]
    (when (and (map? msg) (not= (:origin reg) (:origin msg)))
      (locking (:lock reg)
        (case (:event msg)
          "report" (when (and (string? (:pid msg)) (map? (:entry msg)))
                     (swap! (:remotes reg) assoc-in [(:origin msg) (:pid msg)]
                            {:entry (:entry msg)
                             :seen (System/currentTimeMillis)}))
          "drop" (swap! (:remotes reg) update (:origin msg) dissoc (:pid msg))
          nil)
        (publish! reg)))))

(defn- reassert!
  "Every hb tick: re-report each local entry so peers keep its :seen
  fresh — the entry itself is unchanged (no phantom moves)."
  [reg]
  (doseq [[pid {:keys [entry]}] @(:local reg)]
    (notify! reg {:event "report" :pid pid :entry entry})))

(defn- sweep!
  "TTL eviction, both directions: local entries whose heartbeats went
  quiet (three missed) and no stream holds them, and remote entries
  whose origin stopped re-asserting."
  [reg]
  (locking (:lock reg)
    (let [now (System/currentTimeMillis)
          cutoff (- now (* 3 (long (:hb-ms reg))))
          dead (into [] (keep (fn [[pid st]]
                                (when-not (live-local? st now (:hb-ms reg))
                                  pid)))
                     @(:local reg))]
      (when (seq dead)
        (swap! (:local reg) #(apply dissoc % dead))
        (doseq [pid dead] (notify! reg {:event "drop" :pid pid})))
      (swap! (:remotes reg)
             (fn [rs]
               (into {}
                     (keep (fn [[org entries]]
                             (let [live (into {}
                                             (filter (fn [[_ {:keys [seen]}]]
                                                       (<= cutoff (long seen))))
                                             entries)]
                               (when (seq live) [org live]))))
                     rs)))
      (publish! reg))))

;; ── reporting (the two doors) ───────────────────────────────────────

(defn- check-self! [self]
  (when-not (and (string? self)
                 (str/starts-with? self "/api/")
                 (<= (count self) self-max-chars))
    (throw (p/schema-invalid
            :presence
            {:self [(str "must be an /api/… href of at most "
                         self-max-chars " chars")]}))))

(defn report!
  "The explicit door: one heartbeat — this principal is looking at
  self, right now. Always accepted from any named principal, scoped
  or not (where it looks is its own to say). Three missed heartbeats
  evict."
  [reg principal self]
  (check-self! self)
  (when (= (:id principal) (:id t/anonymous))
    (throw (p/problem :presence-anonymous 422 "Presence names its principal"
                      {:detail "An anonymous heartbeat would mark nobody; present a principal."})))
  (let [pid (:id principal)
        e (entry-of reg principal self "heartbeat")]
    (locking (:lock reg)
      (swap! (:local reg) update pid
             (fn [st] (-> (or st {:streams {}})
                          (assoc :entry e :hb-at (:at-ms e)))))
      (notify! reg {:event "report" :pid pid :entry e})
      (publish! reg))
    nil))

(defn stream-open!
  "The implicit door, opening half: a per-resource SSE subscription
  IS presence — the router's hook calls this on subscribe."
  [reg principal self]
  (let [pid (:id principal)
        e (entry-of reg principal self "stream")]
    (locking (:lock reg)
      (swap! (:local reg) update pid
             (fn [st] (-> (or st {:streams {}})
                          (update-in [:streams self] (fnil inc 0))
                          (assoc :entry e))))
      (notify! reg {:event "report" :pid pid :entry e})
      (publish! reg))
    nil))

(defn stream-closed!
  "The implicit door, closing half: drop on disconnect. When another
  stream (or a fresh heartbeat) still holds the principal, the entry
  survives — its self moves back to a held stream if the closed one
  was the announced screen."
  [reg principal self]
  (let [pid (:id principal)]
    (locking (:lock reg)
      (let [st (get @(:local reg) pid)]
        (when st
          (let [n (dec (long (get-in st [:streams self] 1)))
                streams (if (pos? n)
                          (assoc (:streams st) self n)
                          (dissoc (:streams st) self))
                st' (assoc st :streams streams)]
            (if (live-local? st' (System/currentTimeMillis) (:hb-ms reg))
              (let [st' (if (and (= self (get-in st' [:entry :self]))
                                 (not (contains? streams self))
                                 (seq streams))
                          (update st' :entry assoc :self (first (keys streams)))
                          st')]
                (swap! (:local reg) assoc pid st')
                (notify! reg {:event "report" :pid pid :entry (:entry st')}))
              (do (swap! (:local reg) dissoc pid)
                  (notify! reg {:event "drop" :pid pid}))))))
      (publish! reg))
    nil))

;; ── subscriptions ───────────────────────────────────────────────────

(defn subscribe
  "→ a presence subscription. visible? is the concealment predicate
  over a frame's self (nil = sees all); frames it refuses are never
  enqueued — byte-level absence."
  [reg visible?]
  (let [sub {:id (str (random-uuid))
             :queue (LinkedBlockingQueue. 256)
             :visible? (or visible? (constantly true))}]
    (swap! (:subs reg) conj sub)
    sub))

(defn unsubscribe [reg sub]
  (swap! (:subs reg) disj sub)
  (.offer ^LinkedBlockingQueue (:queue sub) ::closed)
  nil)

(defn take-frame
  "The subscription's next frame; nil on timeout, ::closed after
  unsubscribe."
  [sub timeout-ms]
  (.poll ^LinkedBlockingQueue (:queue sub)
         (long timeout-ms) TimeUnit/MILLISECONDS))

(defn snapshot
  "The merged truth as last announced, filtered by visible? — the
  stream's first frame."
  [reg visible?]
  (locking (:lock reg)
    (into []
          (comp (map val)
                (filter #(visible? (:self %)))
                (map #(select-keys % [:principal :self :source :at])))
          (sort-by key @(:published reg)))))

;; ── visibility (the concealment predicate) ──────────────────────────

(defn self-visible?
  "The stream's concealment predicate for one request: nil visibility
  (an unscoped viewer) sees all; a scoped viewer sees a presence iff
  it could GET the self it names — the row? closure the request
  already resolved. A self that names no known row shape (a
  collection screen, the workspace) is concealed from scoped viewers:
  what cannot be GETed row-wise cannot be watched."
  [eng vis]
  (if (nil? vis)
    (constantly true)
    (fn [self]
      (boolean
       (let [parts (str/split (str self) #"/")]
         (when (and (= 4 (count parts)) (= "api" (nth parts 1)))
           (let [plural (nth parts 2)
                 id (nth parts 3)]
             (when-some [rdef (some (fn [[_ r]] (when (= plural (:plural r)) r))
                                    (inv/resources eng))]
               ((:row? vis) (:kind rdef) id)))))))))

;; ── lifecycle ───────────────────────────────────────────────────────

(defn- presence-connection
  "A dedicated raw LISTEN connection — never from the Hikari pool
  (getNotifications parks it; the dispatcher's discipline)."
  ^Connection [storage]
  (let [url (.getJdbcUrl ^HikariDataSource (:ds storage))
        conn (DriverManager/getConnection url)]
    (with-open [stmt (.createStatement conn)]
      (.execute stmt (str "LISTEN " presence-channel)))
    conn))

(defn start!
  "The engine's presence registry: one LISTEN thread (frames in,
  re-assertions out, TTL sweeps on the clock). opts {:hb-ms} —
  default the engine's :presence-heartbeat-ms (15s); eviction is
  three missed intervals. Returns the registry; stop! ends it."
  [eng {:keys [hb-ms]}]
  (let [storage (:storage eng)
        hb-ms (long (or hb-ms (:presence-heartbeat-ms eng) 15000))
        pg? (pg-storage? storage)
        _ (when-not pg?
            (warn! "fan-out is a Postgres surface; presence stays"
                   " process-local (recorded scope)"))
        conn (when pg?
               (try (presence-connection storage)
                    (catch Exception e
                      (warn! "no LISTEN connection (" (ex-message e)
                             "); presence stays process-local")
                      nil)))
        pg-conn (some-> ^Connection conn (.unwrap PGConnection))
        reg {:eng eng
             :storage storage
             :origin (str (random-uuid))
             :hb-ms hb-ms
             :lock (Object.)
             :local (atom {})
             :remotes (atom {})
             :published (atom {})
             :subs (atom #{})
             :running (atom true)
             :conn conn}
        thread
        (Thread.
         ^Runnable
         (fn []
           (let [last-hb (atom 0)]
             (while @(:running reg)
               (try
                 (if pg-conn
                   (doseq [^PGNotification n
                           (.getNotifications ^PGConnection pg-conn
                                              (int (min hb-ms 1000)))]
                     (on-notification! reg (.getParameter n)))
                   (Thread/sleep (long (min hb-ms 1000))))
                 (let [now (System/currentTimeMillis)]
                   (when (>= (- now (long @last-hb)) hb-ms)
                     (reset! last-hb now)
                     (reassert! reg)
                     (sweep! reg)))
                 (catch InterruptedException _ nil)
                 (catch Exception e
                   (when @(:running reg)
                     (warn! "loop: " (ex-message e))
                     (try (Thread/sleep 1000)
                          (catch InterruptedException _ nil))))))))
         "waymark10-presence")]
    (doto ^Thread thread (.setDaemon true) (.start))
    (assoc reg :thread thread)))

(defn stop! [reg]
  (reset! (:running reg) false)
  (some-> ^Connection (:conn reg) .close)
  (some-> ^Thread (:thread reg) .interrupt)
  nil)

;; ── SSE over http-kit ───────────────────────────────────────────────

(def ^:private sse-headers
  {"Content-Type" "text/event-stream"
   "Cache-Control" "no-cache"
   "X-Accel-Buffering" "no"})

(defn- frame-str ^String [payload]
  (str "event: presence\n"
       "data: " (wire/write-json (p/wire-value payload)) "\n\n"))

(defn sse-handler
  "The presence stream for one viewer: a snapshot frame on connect,
  then live join/move/leave frames — each filtered by visible?
  BEFORE it is enqueued, so a concealed presence is byte-level
  absent. Heartbeat comments double as the disconnect probe (the
  events surface's discipline). No id lines: no replay."
  [eng reg visible? req]
  (let [hb-ms (:sse-heartbeat-ms eng 15000)
        sub (subscribe reg visible?)]
    (http/as-channel
     req
     {:on-open
      (fn [ch]
        (http/send! ch {:status 200 :headers sse-headers
                        :body (str ": stream open\n\n"
                                   (frame-str
                                    {:event "snapshot"
                                     :presences (snapshot reg (:visible? sub))}))}
                    false)
        (let [t (Thread.
                 ^Runnable
                 (fn []
                   (try
                     (loop []
                       (let [evt (take-frame sub hb-ms)]
                         (cond
                           (= ::closed evt) nil
                           ;; events/channel-alive?: http-kit 2.8.0's
                           ;; streaming send! never fails after a
                           ;; disconnect — the key probe is the truth
                           (nil? evt) (when (and (http/send! ch ": hb\n\n" false)
                                                 (events/channel-alive? ch))
                                        (recur))
                           :else (when (and (http/send! ch (frame-str evt) false)
                                            (events/channel-alive? ch))
                                   (recur)))))
                     (finally (unsubscribe reg sub))))
                 (str "waymark10-presence-sse-" (:id sub)))]
          (doto ^Thread t (.setDaemon true) (.start))))
      :on-close
      (fn [_ch _status]
        (unsubscribe reg sub))})))
