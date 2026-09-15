(ns waymark10.server.live
  "ONE live stream per tab (waymark-p5tg) — GET /api/-/live.

  THE PROBLEM IT CLOSES. A UI tab opened three long-lived SSE
  connections at boot: the firehose (/api/-/events), presence
  (/api/-/presence) and intents (/api/-/intents). http-kit speaks
  HTTP/1.1 and Chromium allows six connections per host, so the
  SECOND tab of the same app saturated the pool and every ordinary
  fetch behind it hung. Three streams is two too many when the frames
  are already tagged: `event: transition`, `event: derivation`,
  `event: presence`, `event: intent` — four distinct names on one
  socket, dispatched by the client.

  THE FRAME-SOURCE PROTOCOL. A source is a plain map, built by the
  three constructors below, one per existing surface:

    {:label    a keyword, for the thread name
     :prelude  (fn [] String|nil) — the frames that belong in the
               OPENING body (presence and intents each open with a
               snapshot; the firehose opens with nothing)
     :take     (fn [timeout-ms] evt|nil|closed) — the source's OWN
               blocking take, on the source's OWN subscription
     :closed   the sentinel that take returns after unsubscribe
     :frame    (fn [evt] String) — the source's OWN renderer
     :close!   (fn []) — the source's OWN unsubscribe}

  Every one of those is the existing surface's function, called, not
  copied. That is the load-bearing property of this namespace: each
  source keeps its own admission and its own concealment because
  each source IS the old route's subscribe/filter/render triple,
  merely pumped onto a shared socket instead of its own.

    - firehose: router/firehose-admission decides. A grant-scoped
      caller is REFUSED there (the standalone route's 404), and
      refusal here means the source does not exist — no transition
      and no derivation frame is ever rendered for that connection.
      Concealment by absence, which is the shape concealment already
      has on the presence and intents streams.
    - presence and intents: presence/self-visible? over the request's
      resolved visibility, the SAME predicate the two standalone
      routes build, handed to the SAME subscribe. A frame the viewer
      may not see is refused before it is enqueued — byte-level
      absent, here as there.

  THE SHAPE OF THE CONNECTION. One http-kit channel; one pump thread
  per source (each parked on its own queue — there is no way to wait
  on three queues at once, and the pump is what turns three waits
  into one); one writer thread draining the single outbound queue;
  ONE heartbeat, not three. Ordering is preserved per source, which
  is the only ordering any of the three ever promised — the firehose
  already documents that transitions and derivations ride different
  id sequences and do not order against each other.

  Last-Event-ID rides through untouched: router/firehose-admission
  reads it exactly as /api/-/events does, so a reconnecting tab
  replays its row events the same way. Presence and intents have no
  replay (their snapshot on connect IS the recovery), so the
  combined stream's id lines are the firehose's id lines and nobody
  else's.

  WHAT THIS ROUTE IS NOT. It does not replace the three routes:
  agents, waymark10.client and the CLI read /api/-/events, and the
  two ephemeral doors keep both their GET and their POST. Nothing
  here changes what any of them serve. Per-document
  /api/{plural}/{id}/-/events history streams are out of scope — a
  tab opens those on purpose, briefly, and they carry the implicit
  presence registration this route deliberately does not."
  (:require [org.httpkit.server :as http]
            [waymark10.server.events :as events]
            [waymark10.server.intents :as intents]
            [waymark10.server.presence :as presence]
            [waymark10.server.router :as router])
  (:import (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 live: " parts))))

(def ^:private sse-headers
  {"Content-Type" "text/event-stream"
   "Cache-Control" "no-cache"
   "X-Accel-Buffering" "no"})

;; ── the three sources ───────────────────────────────────────────────

(defn firehose-source
  "The row events, on the firehose's own terms. nil when the firehose
  refuses this caller — router/firehose-admission is the judge, the
  same call /api/-/events makes, so a grant-scoped caller gets the
  concealment it gets there and a never-started engine gets the same
  503 thrown from the same place."
  [eng req]
  (when-some [{:keys [dispatcher kinds since]} (router/firehose-admission eng req)]
    (let [sub (events/subscribe dispatcher
                                {:kinds kinds :since since
                                 :classes #{:transition :derivation}})]
      {:label :events
       :prelude (constantly nil)
       :take (fn [ms] (events/take-event sub ms))
       :closed ::events/closed
       :frame (fn [evt] (events/frame eng evt))
       :close! (fn [] (events/unsubscribe dispatcher sub))})))

(defn presence-source
  "Who is looking where, on presence's own terms: subscribe with the
  request's concealment predicate, open with the snapshot frame that
  predicate filters, render with presence/frame."
  [reg visible?]
  (let [sub (presence/subscribe reg visible?)]
    {:label :presence
     :prelude (fn [] (presence/frame {:event "snapshot"
                                      :presences (presence/snapshot
                                                  reg (:visible? sub))}))
     :take (fn [ms] (presence/take-frame sub ms))
     :closed ::presence/closed
     :frame presence/frame
     :close! (fn [] (presence/unsubscribe reg sub))}))

(defn intents-source
  "What is being considered and asked, on intents' own terms — the
  presence source's shape with the intents registry's own subscribe,
  snapshot and renderer."
  [reg visible?]
  (let [sub (intents/subscribe reg visible?)]
    {:label :intents
     :prelude (fn [] (intents/frame {:event "snapshot"
                                     :intents (intents/snapshot
                                               reg (:visible? sub))}))
     :take (fn [ms] (intents/take-frame sub ms))
     :closed ::intents/closed
     :frame intents/frame
     :close! (fn [] (intents/unsubscribe reg sub))}))

;; ── the multiplexer ─────────────────────────────────────────────────

(def ^:private closed-sentinel ::closed)

(defn- pump!
  "One source's reader: park on THAT source's queue, render on this
  thread, hand the bytes to the writer. Rendering here and not in the
  writer is deliberate — the writer must stay a writer, or one slow
  json encode stalls every source's heartbeat."
  [{:keys [take closed frame label]} ^LinkedBlockingQueue out done hb-ms]
  (try
    (loop []
      (let [evt (take hb-ms)]
        (cond
          @done nil
          (nil? evt) (recur)
          (= closed evt) nil
          :else (do (when-not (.offer out (frame evt))
                      (warn! "outbound queue full; dropping a " (name label)
                             " frame — the row events replay by Last-Event-ID"
                             " and the ephemeral surfaces re-snapshot on"
                             " reconnect"))
                    (recur)))))
    (catch Exception e
      (warn! (name label) " pump: " (ex-message e)))))

(defn sse-handler
  "The combined streaming response. `sources` is the source list —
  nils (a refused firehose) are dropped, so a scoped caller gets a
  perfectly good two-source stream rather than a refusal.

  The opening body is ONE `: stream open` comment followed by each
  source's prelude in the order given; then the writer drains the one
  outbound queue, heartbeating ONCE per :sse-heartbeat-ms. The
  heartbeat doubles as the disconnect probe through
  events/channel-alive? — http-kit 2.8.0's streaming send! never
  fails after a disconnect, so the SelectionKey is the truth (the
  quirk events.clj records)."
  [eng sources req]
  (let [hb-ms (:sse-heartbeat-ms eng 15000)
        sources (vec (remove nil? sources))
        id (str (random-uuid))
        out (LinkedBlockingQueue. 1024)
        done (atom false)
        cleanup! (fn []
                   (when (compare-and-set! done false true)
                     (doseq [s sources]
                       (try ((:close! s))
                            (catch Exception e
                              (warn! "unsubscribe: " (ex-message e)))))
                     ;; wake a parked writer now rather than at its
                     ;; next heartbeat tick
                     (.offer out closed-sentinel)))]
    (http/as-channel
     req
     {:on-open
      (fn [ch]
        (http/send! ch {:status 200 :headers sse-headers
                        :body (apply str ": stream open\n\n"
                                     (keep (fn [s] ((:prelude s))) sources))}
                    false)
        (doseq [s sources]
          (doto (Thread. ^Runnable #(pump! s out done hb-ms)
                         (str "waymark10-live-" (name (:label s)) "-" id))
            (.setDaemon true)
            (.start)))
        (doto (Thread.
               ^Runnable
               (fn []
                 (try
                   (loop []
                     (let [f (.poll out (long hb-ms) TimeUnit/MILLISECONDS)]
                       (cond
                         (= closed-sentinel f) nil
                         (nil? f) (when (and (http/send! ch ": hb\n\n" false)
                                             (events/channel-alive? ch))
                                    (recur))
                         :else (when (and (http/send! ch ^String f false)
                                          (events/channel-alive? ch))
                                 (recur)))))
                   (finally (cleanup!))))
               (str "waymark10-live-sse-" id))
          (.setDaemon true)
          (.start)))
      :on-close
      (fn [_ch _status] (cleanup!))})))
