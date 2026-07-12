(ns waymark10.server.intents
  "Intent frames — presence-of-intent, the hand-in-hand story's beats
  3 and 5 on one channel. An actor's dry-runs broadcast ephemerally
  to anyone following (\"considering — assign_meal on plan p1\"), and
  an actor's pending warning wall lingers as a question addressed to
  the humans who can see the resource. Like presence, an intent is
  EPHEMERAL STATE, never law: no table, no transitions, no
  fingerprint — an in-process registry fanned across processes on its
  own pg_notify channel (waymark10_intents, origin-nonce'd, the
  presence surface's precedent), TTL-evicted. A restart forgets every
  intent; the next dry-run re-teaches it.

  ONE ENTRY PER (principal, action, self) — the id is that triple
  spelled out, so a re-considered dry-run refreshes the same card
  instead of dealing a second one. Three statuses, three lifetimes:
  - \"considering\" (a dry-run's shadow): TTL :intent-ttl-ms
    (default 30s) — gone in a moment if abandoned;
  - \"asking\" (a warning wall the actor hit, or a confirm gate its
    client chose to surface): TTL :intent-ask-ttl-ms (default 10
    min) — a pending gate is an intent that lingers until answered;
  - \"answered\" (a human said yes): the ask, restamped with the
    answer {by, names, at} — the actor's retry rides the EXISTING
    acknowledge-by-name (E1) machinery; this surface only delivers
    the question and relays the yes, it never overrides a guard.

  REPORTING, the doors:
  (a) implicit — a dry-run through the router IS a considering, and a
      warning-refused invoke IS an ask (the router's seams report
      both; the guard's own sentence becomes the question);
  (b) explicit — POST /api/-/intents {self, action, question?} for a
      client surfacing its own gate (a confirm's consequence, which
      the server never refuses on); POST /api/-/intents/abandon
      {self, action} clears the caller's own card;
  (c) the answer — POST /api/-/intents/answer {id, names?} restamps
      an asking intent \"answered\" (names default to the ask's own
      acknowledge names).

  RESOLUTION: the corresponding real transition clears the card — a
  per-registry consumer on the engine's events dispatcher purges
  every local intent whose (self, action) the committed act names,
  outcome \"resolved\"; peers hear the drop. Abandons purge with
  outcome \"abandoned\"; the TTL sweep with \"expired\".

  THE STREAM: GET /api/-/intents (SSE) — a snapshot frame on connect,
  then open/update/close frames (close carries :outcome). No id lines
  and no replay: an intent is liveness, the snapshot on connect is
  the truth.

  CONCEALMENT: the presence stream's own predicate, reused — a scoped
  viewer sees an intent iff it could GET the self it names; a
  filtered frame is BYTE-LEVEL ABSENT, never narrated. An actor's own
  reporting is always accepted (what it considers is its own to say;
  who gets to watch is the grant's). Answering a concealed intent is
  the same 404 as answering none.

  CROSS-PROCESS: every local report notifies {origin, id, entry};
  each process re-asserts its live local entries every
  :intents-heartbeat-ms and evicts a remote entry silent for three
  intervals. Drops carry their outcome: \"resolved\"/\"abandoned\"
  are authoritative (the id purges everywhere), \"expired\" is
  origin-scoped (another origin's fresher copy — an answer — may
  legitimately outlive it). Frames a viewer sees derive from ONE
  merged-view diff (freshest entry per id across origins), the
  presence discipline.

  Recorded boundaries, each a sentence:
  - every FULL dry-run door reports (single, batch, bulk, create —
    §23 opened the last three; one card per door, the bulk/create
    card naming the collection self), while partial rehearsals and
    direct inv/invoke! calls are invisible (the router's seams are
    the doors, the presence precedent for mid-request invisibility).
  - the confirm gate lives in clients (the server never refuses on
    it), so its ask arrives only through the explicit door.
  - resolution matches (self, action), not the actor: an act by
    anyone moots everyone's consideration of it.
  - fan-out is a Postgres surface: on any other backend the registry
    stays process-local, warned once at start.
  - freshest-entry merging trusts one wall clock across origins (the
    presence surface's own assumption).
  - a slow subscriber's full queue (256) drops frames with a warning
    — the snapshot on reconnect is the whole recovery."
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
    (println (apply str "waymark10 intents: " parts))))

(def intents-channel "waymark10_intents")

(def self-max-chars
  "The longest self an intent may name — the presence surface's cap
  (each entry rides one pg_notify payload)."
  512)

(def question-max-chars
  "The longest question an explicit ask may carry — one guard
  sentence, not an essay."
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
;; :local     id → {:entry public-entry :expires-at ms}
;; :remotes   origin → {id {:entry public-entry :seen ms}}
;; :published id → public-entry — the merged truth last announced.
;; :outcomes  id → outcome — why the next close closes; consumed by
;;            each publish! pass (default \"expired\").
;; public-entry: {:id :principal {…} :self :action :status :question?
;;                :warnings? :acknowledge? :answer? :at iso :at-ms ms}

(defn intent-id
  "One card per (principal, action, self) — deterministic, readable,
  and its owner's name is baked in (abandon needs no lookup and
  cannot be forged through the doors, which derive it from the
  request's own principal)."
  ^String [principal self action]
  (str (:id principal) ":" (name action) "@" self))

(defn- now-str [reg]
  (str ((:now-fn (:eng reg) (fn [] (java.time.Instant/now))))))

(defn- normalize-warnings
  "Warning names cross pg_notify as strings; store them that way so
  a local frame and its remote echo are one value."
  [warnings]
  (some->> (not-empty warnings)
           (mapv (fn [w] (p/prune {:name (some-> (:name w) name)
                                   :reason (:reason w)})))))

(defn- entry-of [reg iid principal
                 {:keys [self action status question warnings acknowledge]}]
  (p/prune
   {:id iid
    :principal (author-of principal)
    :self self
    :action (name action)
    :status status
    :question question
    :warnings (normalize-warnings warnings)
    :acknowledge (when acknowledge
                   {:header (:header acknowledge "Waymark-Acknowledge")
                    :names (mapv name (:names acknowledge))})
    :at (now-str reg)
    :at-ms (System/currentTimeMillis)}))

(defn- frame-of [event e]
  (assoc (dissoc e :at-ms) :event event))

(defn- fan! [reg frame]
  (doseq [sub @(:subs reg)]
    (when ((:visible? sub) (:self frame))
      (when-not (.offer ^LinkedBlockingQueue (:queue sub) frame)
        (warn! "subscriber queue full; dropping a frame — the snapshot"
               " on reconnect is the recovery")))))

(defn- merged-view
  "The one truth: per intent id, the freshest entry across this
  process's unexpired locals and every remote origin's."
  [reg now]
  (let [locals (into {}
                     (keep (fn [[iid {:keys [entry expires-at]}]]
                             (when (< (long now) (long expires-at))
                               [iid entry])))
                     @(:local reg))]
    (reduce (fn [m [iid e]]
              (if-some [cur (get m iid)]
                (if (> (long (:at-ms e 0)) (long (:at-ms cur 0)))
                  (assoc m iid e) m)
                (assoc m iid e)))
            locals
            (for [[_ entries] @(:remotes reg)
                  [iid {:keys [entry]}] entries]
              [iid entry]))))

(defn- publish!
  "Re-merge, diff against the last announced truth, fan the
  difference as open/update/close frames — a close names its outcome
  (:outcomes, consumed here; the default is \"expired\"). Reentrant
  under :lock."
  [reg]
  (locking (:lock reg)
    (let [now (System/currentTimeMillis)
          m (merged-view reg now)
          old @(:published reg)
          outcomes @(:outcomes reg)]
      (doseq [[iid e] m
              :let [o (get old iid)]]
        (cond
          (nil? o) (fan! reg (frame-of "open" e))
          ;; a re-assertion only refreshes :at — no phantom updates
          (not= (dissoc o :at :at-ms) (dissoc e :at :at-ms))
          (fan! reg (frame-of "update" e))))
      (doseq [[iid o] old
              :when (not (contains? m iid))]
        (fan! reg (assoc (frame-of "close" o)
                         :outcome (get outcomes iid "expired"))))
      (reset! (:published reg) m)
      (reset! (:outcomes reg) {}))))

(defn- purge!
  "Remove one id everywhere this process knows it — local and every
  origin's remote copy — and record why. The authoritative removals
  (resolved, abandoned); expiry never purges another origin's copy."
  [reg iid outcome]
  (swap! (:local reg) dissoc iid)
  (swap! (:remotes reg)
         (fn [rs]
           (into {} (map (fn [[org entries]] [org (dissoc entries iid)])) rs)))
  (swap! (:outcomes reg) assoc iid outcome))

;; ── the cross-process fan ───────────────────────────────────────────

(defn- notify! [reg msg]
  (when (pg-storage? (:storage reg))
    (try
      (store/with-tx (:storage reg)
        (fn [tx]
          (jdbc/execute-one! tx ["SELECT pg_notify(?, ?)" intents-channel
                                 (wire/write-json
                                  (assoc msg :origin (:origin reg)))])))
      (catch Exception e
        (warn! "notify failed: " (ex-message e))))))

(defn- on-notification! [reg ^String payload]
  (let [msg (try (wire/read-json payload) (catch Exception _ nil))]
    (when (and (map? msg) (not= (:origin reg) (:origin msg)))
      (locking (:lock reg)
        (case (:event msg)
          "report" (when (and (string? (:id msg)) (map? (:entry msg)))
                     (swap! (:remotes reg) assoc-in [(:origin msg) (:id msg)]
                            {:entry (:entry msg)
                             :seen (System/currentTimeMillis)}))
          "drop" (let [iid (:id msg) outcome (:outcome msg "expired")]
                   (when (string? iid)
                     (swap! (:outcomes reg) assoc iid outcome)
                     (if (contains? #{"resolved" "abandoned"} outcome)
                       (purge! reg iid outcome)
                       (swap! (:remotes reg) update (:origin msg) dissoc iid))))
          nil)
        (publish! reg)))))

(defn- reassert!
  "Every hb tick: re-report each live local entry so peers keep its
  :seen fresh — the entry itself is unchanged (no phantom updates)."
  [reg]
  (let [now (System/currentTimeMillis)]
    (doseq [[iid {:keys [entry expires-at]}] @(:local reg)
            :when (< now (long expires-at))]
      (notify! reg {:event "report" :id iid :entry entry}))))

(defn- sweep!
  "TTL eviction, both directions: local entries past their expiry
  (dropped with outcome \"expired\") and remote entries whose origin
  stopped re-asserting."
  [reg]
  (locking (:lock reg)
    (let [now (System/currentTimeMillis)
          cutoff (- now (* 3 (long (:hb-ms reg))))
          dead (into [] (keep (fn [[iid {:keys [expires-at]}]]
                                (when (<= (long expires-at) now) iid)))
                     @(:local reg))]
      (when (seq dead)
        (swap! (:local reg) #(apply dissoc % dead))
        (doseq [iid dead]
          (swap! (:outcomes reg) assoc iid "expired")
          (notify! reg {:event "drop" :id iid :outcome "expired"})))
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

;; ── reporting (the doors) ───────────────────────────────────────────

(defn- check-self! [self]
  (when-not (and (string? self)
                 (str/starts-with? self "/api/")
                 (<= (count self) self-max-chars))
    (throw (p/schema-invalid
            :intent
            {:self [(str "must be an /api/… href of at most "
                         self-max-chars " chars")]}))))

(defn- check-action! [action]
  (when-not (and (or (keyword? action)
                     (and (string? action) (not (str/blank? action))))
                 (<= (count (name action)) 80))
    (throw (p/schema-invalid
            :intent
            {:action ["must name the transition being considered"]}))))

(defn- check-named! [principal what]
  (when (= (:id principal) (:id t/anonymous))
    (throw (p/problem :intent-anonymous 422 "An intent names its principal"
                      {:detail (str "An anonymous " what
                                    " would speak for nobody; present a"
                                    " principal.")}))))

(defn report!
  "One intent, this principal's own mouth: considering (a dry-run's
  shadow, short TTL) or asking (a pending gate, the lingering TTL —
  question present, or :status \"asking\" with the wall's warnings
  and acknowledge names riding along). A re-report refreshes the same
  card. Always accepted from any named principal, scoped or not."
  [reg principal {:keys [self action question warnings acknowledge status]}]
  (check-self! self)
  (check-action! action)
  (check-named! principal "intent")
  (let [status (or status (if question "asking" "considering"))]
    (when-not (contains? #{"considering" "asking"} status)
      (throw (p/schema-invalid
              :intent {:status ["must be \"considering\" or \"asking\""]})))
    (when (and (some? question)
               (not (and (string? question)
                         (not (str/blank? question))
                         (<= (count question) question-max-chars))))
      (throw (p/schema-invalid
              :intent
              {:question [(str "must be one sentence of at most "
                               question-max-chars " chars")]})))
    (let [iid (intent-id principal self action)
          e (entry-of reg iid principal
                      {:self self :action action :status status
                       :question question :warnings warnings
                       :acknowledge acknowledge})
          ttl (if (= "asking" status) (:ask-ttl-ms reg) (:ttl-ms reg))]
      (locking (:lock reg)
        (swap! (:local reg) assoc iid
               {:entry e :expires-at (+ (long (:at-ms e)) (long ttl))})
        (notify! reg {:event "report" :id iid :entry e})
        (publish! reg))
      nil)))

(defn abandon!
  "The explicit clear: the caller's own card (the id derives from its
  own principal — nobody abandons anyone else's thought) closes with
  outcome \"abandoned\", everywhere. Idempotent: abandoning a card
  that already vanished is a no-op."
  [reg principal {:keys [self action]}]
  (check-self! self)
  (check-action! action)
  (check-named! principal "abandon")
  (let [iid (intent-id principal self action)]
    (locking (:lock reg)
      (purge! reg iid "abandoned")
      (notify! reg {:event "drop" :id iid :outcome "abandoned"})
      (publish! reg))
    nil))

(defn answer!
  "The human's yes: restamp an asking intent \"answered\" with
  {by, names, at} — names default to the ask's own acknowledge names.
  The answer only DELIVERS; the actor's retry still passes the guard
  through the E1 header (no second acknowledgement path). visible? is
  the caller's concealment predicate: an intent it may not see is the
  same 404 as no intent at all."
  [reg answerer {:keys [id names]} visible?]
  (check-named! answerer "answer")
  (when-not (string? id)
    (throw (p/schema-invalid :intent {:id ["required — the asking intent's id"]})))
  (locking (:lock reg)
    (let [e (get @(:published reg) id)
          visible? (or visible? (constantly true))]
      (when (or (nil? e) (not (visible? (:self e))))
        (throw (p/problem :not-found 404 "Not found"
                          {:detail (str "No intent " (pr-str id) ".")})))
      (when-not (contains? #{"asking" "answered"} (:status e))
        (throw (p/problem :intent-not-asking 409 "Nothing to answer"
                          {:detail "This intent is a considering, not a pending ask."})))
      (let [now-ms (System/currentTimeMillis)
            e' (-> e
                   (assoc :status "answered"
                          :answer (p/prune
                                   {:by (author-of answerer)
                                    :names (or (some->> (seq names) (mapv name))
                                               (get-in e [:acknowledge :names]))
                                    :at (now-str reg)})
                          :at (now-str reg)
                          :at-ms now-ms))]
        (swap! (:local reg) assoc id
               {:entry e' :expires-at (+ now-ms (long (:ask-ttl-ms reg)))})
        (notify! reg {:event "report" :id id :entry e'})
        (publish! reg))))
  nil)

;; ── resolution (the real act clears the card) ───────────────────────

(defn- resolve-event!
  "One committed transition: purge every LOCAL intent naming its
  (self, action), outcome \"resolved\" — each process resolves what
  it holds; peers hear the drop. Whoever acted, the consideration is
  moot."
  [reg evt]
  (when-some [rdef (get (inv/resources (:eng reg)) (:kind evt))]
    (when (and (:resource-id evt) (:action evt))
      (let [self (str "/api/" (:plural rdef) "/" (:resource-id evt))
            action-name (name (:action evt))
            hits (into []
                       (keep (fn [[iid {:keys [entry]}]]
                               (when (and (= self (:self entry))
                                          (= action-name (:action entry)))
                                 iid)))
                       @(:local reg))]
        (when (seq hits)
          (locking (:lock reg)
            (doseq [iid hits]
              (purge! reg iid "resolved")
              (notify! reg {:event "drop" :id iid :outcome "resolved"}))
            (publish! reg)))))))

;; ── subscriptions ───────────────────────────────────────────────────

(defn subscribe
  "→ an intents subscription. visible? is the concealment predicate
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
                (map #(dissoc % :at-ms)))
          (sort-by key @(:published reg)))))

;; ── lifecycle ───────────────────────────────────────────────────────

(defn- intents-connection
  "A dedicated raw LISTEN connection — never from the Hikari pool
  (getNotifications parks it; the dispatcher's discipline)."
  ^Connection [storage]
  (let [url (.getJdbcUrl ^HikariDataSource (:ds storage))
        conn (DriverManager/getConnection url)]
    (with-open [stmt (.createStatement conn)]
      (.execute stmt (str "LISTEN " intents-channel)))
    conn))

(defn start!
  "The engine's intents registry: one LISTEN thread (frames in,
  re-assertions out, TTL sweeps on the clock) and — when the caller
  hands it the engine's events dispatcher — the resolution consumer
  that clears a card when its real act lands. opts {:hb-ms :ttl-ms
  :ask-ttl-ms :dispatcher} — defaults :intents-heartbeat-ms (15s),
  :intent-ttl-ms (30s), :intent-ask-ttl-ms (10 min). Returns the
  registry; stop! ends it."
  [eng {:keys [hb-ms ttl-ms ask-ttl-ms dispatcher]}]
  (let [storage (:storage eng)
        hb-ms (long (or hb-ms (:intents-heartbeat-ms eng) 15000))
        ttl-ms (long (or ttl-ms (:intent-ttl-ms eng) 30000))
        ask-ttl-ms (long (or ask-ttl-ms (:intent-ask-ttl-ms eng) 600000))
        pg? (pg-storage? storage)
        _ (when-not pg?
            (warn! "fan-out is a Postgres surface; intents stay"
                   " process-local (recorded scope)"))
        conn (when pg?
               (try (intents-connection storage)
                    (catch Exception e
                      (warn! "no LISTEN connection (" (ex-message e)
                             "); intents stay process-local")
                      nil)))
        pg-conn (some-> ^Connection conn (.unwrap PGConnection))
        sub (when dispatcher (events/subscribe dispatcher {}))
        reg {:eng eng
             :storage storage
             :origin (str (random-uuid))
             :hb-ms hb-ms
             :ttl-ms ttl-ms
             :ask-ttl-ms ask-ttl-ms
             :lock (Object.)
             :local (atom {})
             :remotes (atom {})
             :published (atom {})
             :outcomes (atom {})
             :subs (atom #{})
             :running (atom true)
             :conn conn
             :dispatcher dispatcher
             :sub sub}
        listen-t
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
         "waymark10-intents")
        resolve-t
        (when sub
          (Thread.
           ^Runnable
           (fn []
             (loop []
               (let [evt (events/take-event sub 60000)]
                 (cond
                   (= ::events/closed evt) nil
                   (nil? evt) (when @(:running reg) (recur))
                   :else
                   (do (when @(:running reg)
                         (try (resolve-event! reg evt)
                              (catch Exception e
                                (warn! "resolve: " (ex-message e)))))
                       (when @(:running reg) (recur)))))))
           "waymark10-intents-resolve"))]
    (doto ^Thread listen-t (.setDaemon true) (.start))
    (some-> ^Thread resolve-t (doto (.setDaemon true) (.start)))
    (assoc reg :thread listen-t :resolve-thread resolve-t)))

(defn stop! [reg]
  (reset! (:running reg) false)
  (when-some [sub (:sub reg)]
    (events/unsubscribe (:dispatcher reg) sub))
  (some-> ^Connection (:conn reg) .close)
  (some-> ^Thread (:thread reg) .interrupt)
  nil)

;; ── SSE over http-kit ───────────────────────────────────────────────

(def ^:private sse-headers
  {"Content-Type" "text/event-stream"
   "Cache-Control" "no-cache"
   "X-Accel-Buffering" "no"})

(defn- frame-str ^String [payload]
  (str "event: intent\n"
       "data: " (wire/write-json (p/wire-value payload)) "\n\n"))

(defn sse-handler
  "The intents stream for one viewer: a snapshot frame on connect,
  then live open/update/close frames — each filtered by visible?
  BEFORE it is enqueued, so a concealed intent is byte-level absent.
  Heartbeat comments double as the disconnect probe (the events
  surface's discipline). No id lines: no replay."
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
                                     :intents (snapshot reg (:visible? sub))}))}
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
                 (str "waymark10-intents-sse-" (:id sub)))]
          (doto ^Thread t (.setDaemon true) (.start))))
      :on-close
      (fn [_ch _status]
        (unsubscribe reg sub))})))
