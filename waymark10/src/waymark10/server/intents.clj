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

  THE CURTAIN (waymark-tti.4, closed by the skeptic's F1): an intent
  frame names its principal exactly as a presence frame does —
  {principal, self, action} IS presence-of-intent — so the curtain
  binds here too, through the SAME shared component presence reads
  (server/curtain.clj: one member-row read, one cache, one
  invalidation wire). A curtained principal's report is ACCEPTED and
  publishes NOTHING: nothing stored, nothing notified, so no process
  ever holds the card — and the dry-run or confirm that occasioned it
  still runs untouched, because the curtain suppresses the
  ANNOUNCEMENT, never the work. merged-view and snapshot filter
  curtained principals as belt and braces, and a draw closes the
  cards already dealt (the invalidation's watcher re-publishes at
  once; the TTL sweep is the backstop). The ANSWER door is
  deliberately unshaded: answering is an act on someone else's card,
  legible like the curtain transition itself.

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
            [waymark10.server.curtain :as curtain]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.problems :as p]
            [waymark10.server.seams :as seams]
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

;; ── the curtain (waymark-tti.4) ─────────────────────────────────────

(defn- curtained?
  "This principal's curtain, through the shared component (one cache
  for presence and intents both)."
  [reg pid]
  (curtain/curtained? (:curtain reg) pid))

(defn- curtain-view
  "The curtain verdict for every principal this publish round could
  name — resolved BEFORE the lock, because a cache miss reads the
  store and a store read under (:lock reg) would park every door
  behind the database (presence's F5 discipline, same shape). A
  principal absent from the map falls back to the warm cache and,
  past that, reads as CURTAINED downstream: fail closed, and the next
  publish corrects the rare race."
  ([reg] (curtain-view reg nil))
  ([reg also]
   (curtain/verdicts
    (:curtain reg)
    (concat also
            (keep (fn [[_ {:keys [entry]}]] (get-in entry [:principal :id]))
                  @(:local reg))
            (keep (fn [[_ e]] (get-in e [:principal :id]))
                  @(:published reg))
            (for [[_ entries] @(:remotes reg)
                  [_ {:keys [entry]}] entries]
              (get-in entry [:principal :id])))
    false)))

(defn- verdict
  "One principal's curtain INSIDE a publish round: the prefetched
  view first, and for a principal the view could not have named — one
  whose card arrived during the prefetch→lock window, so it was in
  none of the maps curtain-view walked — this process's already-warmed
  cache (curtain/cached-verdict: a deref, no store read, safe under
  the lock; the reporting thread's own door filled it). Only a
  principal this process has never asked about reads as curtained,
  which is the fail-closed answer we want; a KNOWN-open one no longer
  has its card closed by the race (presence carried the same bug, and
  the same fix)."
  [reg cv pid]
  (if (contains? cv pid)
    (get cv pid)
    (curtain/cached-verdict (:curtain reg) pid true)))

(defn- merged-view
  "The one truth: per intent id, the freshest entry across this
  process's unexpired locals and every remote origin's. Curtained
  principals are filtered HERE, belt and braces over report!'s own
  refusal: publish! diffs this view, so no open/update frame can fan
  for a curtained actor and no snapshot can serve one — not in the
  race between a draw and the invalidation, and not from a remote
  origin that has not caught up. cv is the caller's prefetched
  curtain-view; an unknown principal reads as curtained."
  [reg now cv]
  (let [shown? (fn [e] (not (verdict reg cv (get-in e [:principal :id]))))
        locals (into {}
                     (keep (fn [[iid {:keys [entry expires-at]}]]
                             (when (and (< (long now) (long expires-at))
                                        (shown? entry))
                               [iid entry])))
                     @(:local reg))]
    (reduce (fn [m [iid e]]
              (if-some [cur (get m iid)]
                (if (> (long (:at-ms e 0)) (long (:at-ms cur 0)))
                  (assoc m iid e) m)
                (assoc m iid e)))
            locals
            (for [[_ entries] @(:remotes reg)
                  [iid {:keys [entry]}] entries
                  :when (shown? entry)]
              [iid entry]))))

(defn- publish!
  "Re-merge, diff against the last announced truth, fan the
  difference as open/update/close frames — a close names its outcome
  (:outcomes, consumed here; the default is \"expired\"). Reentrant
  under :lock; cv is the curtain-view its caller resolved OUTSIDE
  the lock."
  [reg cv]
  (locking (:lock reg)
    (let [now (System/currentTimeMillis)
          m (merged-view reg now cv)
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
      ;; the arriving entry's principal rides `also`: it is in no map
      ;; yet, and the prefetch happens before the lock
      (let [cv (curtain-view reg [(get-in msg [:entry :principal :id])])]
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
          (publish! reg cv))))))

(defn- reassert!
  "Every hb tick: re-report each live local entry so peers keep its
  :seen fresh — the entry itself is unchanged (no phantom updates).
  A curtained actor's card is never re-taught to peers: it is about
  to close here, and a re-assertion would hand remote processes the
  very frame report! refused to publish."
  [reg]
  (let [now (System/currentTimeMillis)]
    (doseq [[iid {:keys [entry expires-at]}] @(:local reg)
            :when (and (< now (long expires-at))
                       (not (curtained? reg (get-in entry [:principal :id]))))]
      (notify! reg {:event "report" :id iid :entry entry}))))

(defn- sweep!
  "TTL eviction, both directions: local entries past their expiry
  (dropped with outcome \"expired\") and remote entries whose origin
  stopped re-asserting."
  [reg]
  (let [cv (curtain-view reg)]
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
        (publish! reg cv)))))

;; ── reporting (the doors) ───────────────────────────────────────────

(defn normalize-self
  "What this door would STORE for a caller's self: exactly what it was
  handed. Unlike presence, the intents door does not accept a full URL
  where an href was meant — check-self! below refuses anything that
  does not start with /api/, so there is no origin to strip and no
  rewriting to do.

  It exists as a named seam anyway, and it is PUBLIC for one reason:
  the router's private-row gate (reportable-self?, waymark-tti.3 L7)
  must judge the value the door will actually store, and it must ask
  each door in that door's own spelling rather than assume the two
  doors agree. If this surface ever grows a normalization, the gate
  follows it here instead of quietly falling behind it."
  [self]
  self)

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
  card. Always accepted from any named principal, scoped or not —
  and a CURTAINED one's is accepted too and publishes nothing: no
  entry, no notify, so no process (local or remote) ever holds the
  card. The dry-run or confirm behind it ran and answered normally;
  only the announcement is suppressed."
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
    ;; the curtain, judged AFTER validation (a malformed intent still
    ;; meets its 422 — the curtain changes what is published, never
    ;; what a caller is told about its own request) and BEFORE any
    ;; write: nothing stored, nothing notified
    (when-not (curtained? reg (:id principal))
      (let [iid (intent-id principal self action)
            e (entry-of reg iid principal
                        {:self self :action action :status status
                         :question question :warnings warnings
                         :acknowledge acknowledge})
            ttl (if (= "asking" status) (:ask-ttl-ms reg) (:ttl-ms reg))
            cv (curtain-view reg [(:id principal)])]
        (locking (:lock reg)
          (swap! (:local reg) assoc iid
                 {:entry e :expires-at (+ (long (:at-ms e)) (long ttl))})
          (notify! reg {:event "report" :id iid :entry e})
          (publish! reg cv))))
    nil))

(defn abandon!
  "The explicit clear: the caller's own card (the id derives from its
  own principal — nobody abandons anyone else's thought) closes with
  outcome \"abandoned\", everywhere. Idempotent: abandoning a card
  that already vanished is a no-op."
  [reg principal {:keys [self action]}]
  (check-self! self)
  (check-action! action)
  (check-named! principal "abandon")
  (let [iid (intent-id principal self action)
        cv (curtain-view reg)]
    (locking (:lock reg)
      (purge! reg iid "abandoned")
      (notify! reg {:event "drop" :id iid :outcome "abandoned"})
      (publish! reg cv))
    nil))

(defn answer!
  "The human's yes: restamp an asking intent \"answered\" with
  {by, names, at} — names default to the ask's own acknowledge names.
  The answer only DELIVERS; the actor's retry still passes the guard
  through the E1 header (no second acknowledgement path). visible? is
  the caller's concealment predicate: an intent it may not see is the
  same 404 as no intent at all.

  A CURTAINED answerer answers anyway — answering is a legible act,
  and the ask must resolve — but ANONYMOUSLY: :by is omitted. The
  carve-out was ever about the answer being visible, never about the
  curtained principal's id, type, display and a freshly-stamped
  liveness clock riding a repeating ephemeral stream to every
  watcher. Everything else in the frame stands, so the actor's retry
  loop and the card's close are untouched."
  [reg answerer {:keys [id names]} visible?]
  (check-named! answerer "answer")
  (when-not (string? id)
    (throw (p/schema-invalid :intent {:id ["required — the asking intent's id"]})))
  ;; both resolved before the lock — curtained? can read the store
  (let [drawn? (curtained? reg (:id answerer))
        cv (curtain-view reg)]
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
                            ;; p/prune drops the nil: a curtained
                            ;; answerer's :by is ABSENT, not blank
                            :answer (p/prune
                                     {:by (when-not drawn? (author-of answerer))
                                      :names (or (some->> (seq names) (mapv name))
                                                 (get-in e [:acknowledge :names]))
                                      :at (now-str reg)})
                            :at (now-str reg)
                            :at-ms now-ms))]
          (swap! (:local reg) assoc id
                 {:entry e' :expires-at (+ now-ms (long (:ask-ttl-ms reg)))})
          (notify! reg {:event "report" :id id :entry e'})
          (publish! reg cv)))))
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
          (let [cv (curtain-view reg)]
            (locking (:lock reg)
              (doseq [iid hits]
                (purge! reg iid "resolved")
                (notify! reg {:event "drop" :id iid :outcome "resolved"}))
              (publish! reg cv))))))))

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
  stream's first frame. Curtained actors are filtered here TOO
  (merged-view already refused them into :published; this is the last
  brace): a join snapshot must never deal a curtained principal's
  card, however the draw races the publish. Through `verdict`, so a
  card reported during the prefetch→lock window is judged by the warm
  cache and not by its absence — the first frame is the only one a
  new subscriber gets for an already-published card."
  [reg visible?]
  (let [cv (curtain-view reg)]
    (locking (:lock reg)
      (into []
            (comp (map val)
                  (remove #(verdict reg cv (get-in % [:principal :id])))
                  (filter #(visible? (:self %)))
                  (map #(dissoc % :at-ms)))
            (sort-by key @(:published reg))))))

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

(defrecord Registry []
  ;; The announcement, as CORE knocks on it (waymark-db9.7). The
  ;; router used to require this namespace so a dry-run door could
  ;; deal its considering card; the card is not a route, it is this
  ;; running surface being asked to do its own work. Core already
  ;; holds the registry (runtime/surface eng :intents, the handle the
  ;; realtime module's own lifecycle hook published), so the reach-in
  ;; dissolves into type dispatch. Field-less for the same reason
  ;; presence's is: the map's shape is start!'s, and one home for it
  ;; is better than two that can drift.
  seams/Considering
  (announce! [reg principal intent] (report! reg principal intent)))

(defn start!
  "The engine's intents registry: one LISTEN thread (frames in,
  re-assertions out, TTL sweeps on the clock) and — when the caller
  hands it the engine's events dispatcher — the resolution consumer
  that clears a card when its real act lands. opts {:hb-ms :ttl-ms
  :ask-ttl-ms :dispatcher :curtain} — defaults :intents-heartbeat-ms
  (15s), :intent-ttl-ms (30s), :intent-ask-ttl-ms (10 min). :curtain
  is the SHARED component presence reads too (curtain/start!);
  without it the registry starts a private one, so a standalone
  intents surface still honors the member row. A committed DRAW
  re-publishes at once through the component's watcher — the cards
  already dealt for that principal close on the spot rather than
  lingering to their TTL. Returns the registry; stop! ends it."
  [eng {:keys [hb-ms ttl-ms ask-ttl-ms dispatcher curtain]}]
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
        ;; the curtain consult (waymark-tti.4): the engine's shared
        ;; component, or a private one for a standalone registry
        own-curtain (when (nil? curtain)
                      (curtain/start! eng {:dispatcher dispatcher}))
        reg (map->Registry
             {:eng eng
              :storage storage
              :origin (str (random-uuid))
              :hb-ms hb-ms
              :ttl-ms ttl-ms
              :ask-ttl-ms ask-ttl-ms
              :curtain (or curtain own-curtain)
              :own-curtain own-curtain
              :lock (Object.)
              :local (atom {})
              :remotes (atom {})
              :published (atom {})
              :outcomes (atom {})
              :subs (atom #{})
              :running (atom true)
              :conn conn
              :dispatcher dispatcher
              :sub sub})
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
           "waymark10-intents-resolve"))
        ;; a DRAWN curtain closes the cards already dealt: re-publish
        ;; and the merged view (which now refuses that principal)
        ;; fans the closes itself — no second purge path to keep
        ;; honest
        watch (curtain/watch! (:curtain reg)
                              (fn [_pid]
                                (let [cv (curtain-view reg)]
                                  (locking (:lock reg)
                                    (publish! reg cv)))))]
    (doto ^Thread listen-t (.setDaemon true) (.start))
    (some-> ^Thread resolve-t (doto (.setDaemon true) (.start)))
    (assoc reg :thread listen-t :resolve-thread resolve-t
           :curtain-watch watch)))

(defn stop! [reg]
  (reset! (:running reg) false)
  (when-some [sub (:sub reg)]
    (events/unsubscribe (:dispatcher reg) sub))
  (when-some [w (:curtain-watch reg)]
    (curtain/unwatch! (:curtain reg) w))
  ;; only a registry that STARTED its own curtain stops one — the
  ;; engine's shared component outlives every surface that reads it
  (some-> (:own-curtain reg) curtain/stop!)
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
