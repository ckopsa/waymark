(ns waymark10.server.collab
  "Live collab, completed to waymark-relay/2 (phase 12 / batch D):
  http-kit websockets at GET /api/{plural}/{id}/-/{action}/draft/collab
  for :edit actions whose :draft is {:shared true :live true}. The
  phase-9b punts — staleness rejection, acks, presence, regate, the
  cross-worker bus — close here, and prose fields gain server-
  authoritative operational transformation.

  Frames:

    server → joiner   {\"type\": \"state\", \"values\", \"revs\",
                       \"authors\", \"base_version\", \"stale\",
                       \"participants\"}
    client → server   {\"type\": \"set\", \"field\", \"value\", \"rev\"}
                      (rev = the field's base rev this edit saw)
    client → server   {\"type\": \"edit\", \"field\", \"rev\", \"ops\"}
                      (prose fields only; ops = [{\"retain\": n} |
                       {\"insert\": s} | {\"delete\": n}]; \"base_rev\"
                       is accepted as an alias for \"rev\")
    client → server   {\"type\": \"sync\"}
    server → sender   {\"type\": \"ack\", \"field\", \"rev\"}
    server → sender   {\"type\": \"stale\", \"field\", \"rev\", \"value\"}
                      (base ≠ current: here is the field's truth —
                       apply it, re-edit; nothing silently clobbered)
    server → others   {\"type\": \"update\", \"field\", \"value\",
                       \"rev\", \"author\"}
    server → others   {\"type\": \"edit\", \"field\", \"rev\", \"ops\",
                       \"author\"}     (the TRANSFORMED op)
    server → sender   {\"type\": \"sync\", …the state frame's body}
    server → room     {\"type\": \"presence\", \"event\":
                       \"joined\"|\"left\"|\"roster\", \"actor\"?,
                       \"participants\"}
    server → room     {\"type\": \"regate\", \"base_version\",
                       \"revs\"?, \"gone\"?} — the row's version moved
                      (or the row/draft is gone): re-pull the prefill;
                      sets against the old base answer stale
    server → room     {\"type\": \"resync\", \"field\"} — an oversized
                      frame could not relay; sync to converge
    server → sender   {\"type\": \"error\", \"errors\": {field: [msgs]}}

  Merge discipline. Scalar fields: server-ordered per-field revisions
  with explicit staleness rejection — a set whose base rev is not the
  field's current rev answers stale (no silent LWW). Prose fields
  ({:x-display {:widget \"prose\"}}): OPERATION frames — the server
  transforms the client's op against every op it has applied since
  the client's base rev (transform-pair, in-house, no deps), applies
  it, appends it to the field's op log, and broadcasts the
  transformed op under the new rev. The canonical per-field op log
  rides the draft document (waymark10.server.drafts owns the shape)
  capped at op-log-cap entries; an edit whose base rev predates the
  retained horizon answers stale and the client resyncs — compaction
  trades memory for a resync, never for corruption.

  Every accepted frame is the SAME write a draft PUT lands: partial
  validation against the action's input schema, then the shared
  draft row persists in the frame's own transaction — which takes
  the resource row's FOR UPDATE lock, so writers on separate
  processes serialize exactly like invokes do.

  Cross-process relay: accepted frames pg_notify waymark10_collab
  (inside the accepting transaction — a relayed frame exists iff its
  commit does), stamped with a per-process origin nonce so the
  publisher skips its own echo. Each engine runs one dedicated
  LISTEN connection (never from the Hikari pool), started lazily
  with its first room and stopped with its last. Presence rosters
  merge across processes: join/leave frames adjust a per-origin
  remote roster, a heartbeat (:collab-heartbeat-ms, default 5000)
  re-asserts each process's local members, and an origin silent for
  three heartbeats is evicted — a crashed process's ghosts leave
  within ~3 intervals. Frames over 7000 bytes relay as a resync hint
  (pg_notify's payload ceiling), recorded.

  Regate: when the underlying row's version moves (any transition —
  the fence bumps), every field's rev bumps, prose op logs clear,
  and the room hears {type: regate, base_version}; in-flight sets
  and edits against the old base answer stale until clients re-pull
  the prefill. Detected on the write path (the draft's stored
  base_version disagrees with the locked row's) and proactively by a
  per-relay consumer on the engine's events dispatcher — an act that
  CONSUMES the draft broadcasts {type: regate, gone: true}: the
  draft is gone, compose anew. An engine that never started (no
  dispatcher) keeps the write-path detection only, recorded.

  Remaining punts, each named: UI cursor/presence chrome (ui.html is
  another batch's), cursor positions on the wire, and a plain draft
  PUT beside a live room bumps revs but broadcasts nothing — live
  clients converge at their next stale/sync."
  (:require [next.jdbc :as jdbc]
            [org.httpkit.server :as http]
            [waymark10.schema :as schema]
            [waymark10.server.drafts :as drafts]
            [waymark10.server.events :as events]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.wire :as wire])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.sql Connection DriverManager)
           (org.postgresql PGConnection PGNotification)))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 collab: " parts))))

;; ── the OT core (pure; the generative proof drives exactly this) ────
;;
;; An op is a vector of components {:retain n} | {:insert s} |
;; {:delete n}, normalized: no zero-length components, no adjacent
;; components of one type.

(defn- push
  "conj a component, merging with the tail when types match and
  dropping empties — the one normalizer every builder shares."
  [ops op]
  (let [t (peek ops)]
    (cond
      (nil? op) ops
      (and (contains? op :retain) (zero? (long (:retain op)))) ops
      (and (contains? op :delete) (zero? (long (:delete op)))) ops
      (and (contains? op :insert) (= "" (:insert op))) ops

      (and t (:insert op) (:insert t))
      (conj (pop ops) {:insert (str (:insert t) (:insert op))})
      (and t (:retain op) (:retain t))
      (conj (pop ops) {:retain (+ (long (:retain t)) (long (:retain op)))})
      (and t (:delete op) (:delete t))
      (conj (pop ops) {:delete (+ (long (:delete t)) (long (:delete op)))})

      :else (conj ops op))))

(defn parse-ops
  "Wire ops → a normalized component vector; nil when malformed. Each
  wire component is exactly one of {:retain n≥0} {:insert s}
  {:delete n≥0} — zero-length components are dropped, anything else
  refuses."
  [ops]
  (when (sequential? ops)
    (reduce
     (fn [acc op]
       (if-not (and (map? op) (= 1 (count op)))
         (reduced nil)
         (let [[k v] (first op)]
           (cond
             (and (= :retain k) (int? v) (not (neg? (long v))))
             (push acc {:retain (long v)})
             (and (= :delete k) (int? v) (not (neg? (long v))))
             (push acc {:delete (long v)})
             (and (= :insert k) (string? v))
             (push acc {:insert v})
             :else (reduced nil)))))
     [] ops)))

(defn base-len
  "The document length an op applies to."
  ^long [ops]
  (reduce (fn [^long n op]
            (+ n (long (or (:retain op) (:delete op) 0))))
          0 ops))

(defn apply-ops
  "ops over s → the new string; nil when the op does not span s
  exactly."
  [^String s ops]
  (loop [i 0 ops (seq ops) sb (StringBuilder.)]
    (if (nil? ops)
      (when (= i (count s)) (.toString sb))
      (let [op (first ops)]
        (cond
          (:insert op)
          (do (.append sb ^String (:insert op))
              (recur i (next ops) sb))

          (contains? op :retain)
          (let [j (+ i (long (:retain op)))]
            (when (<= j (count s))
              (.append sb (subs s i j))
              (recur j (next ops) sb)))

          :else
          (let [j (+ i (long (:delete op)))]
            (when (<= j (count s))
              (recur j (next ops) sb))))))))

(defn transform-pair
  "Transform two concurrent ops over one base document → [a' b'] such
  that apply(apply(s, a), b') = apply(apply(s, b), a'). a is the
  earlier (server-priority) side: at an insertion tie a's text lands
  first. nil when a and b do not span the same base length."
  [a b]
  (loop [as (seq a) bs (seq b) a' [] b' []]
    (let [ha (first as) hb (first bs)]
      (cond
        (and (nil? ha) (nil? hb)) [a' b']

        ;; inserts consume no base — a's go first (the priority)
        (and ha (:insert ha))
        (recur (next as) bs
               (push a' ha)
               (push b' {:retain (count ^String (:insert ha))}))

        (and hb (:insert hb))
        (recur as (next bs)
               (push a' {:retain (count ^String (:insert hb))})
               (push b' hb))

        (or (nil? ha) (nil? hb)) nil

        :else
        (let [ta (if (contains? ha :retain) :retain :delete)
              tb (if (contains? hb :retain) :retain :delete)
              la (long (get ha ta)) lb (long (get hb tb))
              n (min la lb)
              ras (if (> la n) (cons {ta (- la n)} (next as)) (next as))
              rbs (if (> lb n) (cons {tb (- lb n)} (next bs)) (next bs))]
          (cond
            (and (= ta :retain) (= tb :retain))
            (recur ras rbs (push a' {:retain n}) (push b' {:retain n}))
            (and (= ta :delete) (= tb :delete))
            (recur ras rbs a' b')
            (= ta :delete)                     ; a deletes what b kept
            (recur ras rbs (push a' {:delete n}) b')
            :else                              ; b deletes what a kept
            (recur ras rbs a' (push b' {:delete n}))))))))

(def op-log-cap
  "Per-field op-log entries the draft document retains (the
  compaction cap): a client whose base rev predates the horizon
  answers stale and resyncs — the log never grows without bound."
  200)

(defn accept-edit
  "The server-authoritative OT step for one prose field. state is
  {:value s :rev n :log [{:rev r :ops […]} …]} (log ascending,
  contiguous, ending at :rev); a client edit arrives with the ops it
  composed at base-rev. → {:outcome :applied :ops' transformed
  :state next-state} | {:outcome :stale} (base ahead of the rev, or
  behind the compaction horizon) | {:outcome :malformed} (ops do not
  span the document)."
  [{:keys [value rev log] :or {value "" rev 0 log []}} base-rev ops]
  (let [rev (long rev) base-rev (long base-rev)]
    (cond
      (or (neg? base-rev) (> base-rev rev))
      {:outcome :stale}

      ;; the horizon: every op after base-rev must still be retained
      (and (< base-rev rev)
           (or (empty? log) (> (long (:rev (first log))) (inc base-rev))))
      {:outcome :stale}

      :else
      (let [concurrent (filter #(> (long (:rev %)) base-rev) log)
            ops' (reduce (fn [c h]
                           (if-some [pair (transform-pair (:ops h) c)]
                             (second pair)
                             (reduced ::broken)))
                         ops concurrent)]
        (if (= ::broken ops')
          {:outcome :malformed}
          (if-some [v' (apply-ops (or value "") ops')]
            (let [rev' (inc rev)]
              {:outcome :applied
               :ops' ops'
               :state {:value v'
                       :rev rev'
                       :log (vec (take-last op-log-cap
                                            (conj (vec log)
                                                  {:rev rev' :ops ops'})))}})
            {:outcome :malformed}))))))

;; ── rooms ───────────────────────────────────────────────────────────

(defn- room-of [eng key]
  (get (swap! (:collab-rooms eng) update key
              #(or % {:clients (atom {})
                      ;; origin → {:actors {id author} :seen ms} — the
                      ;; other processes' rosters, heartbeat-refreshed
                      :remotes (atom {})
                      :lock (Object.)}))
       key))

(defn- send-frame! [ch frame]
  (http/send! ch (wire/write-json frame)))

(defn- broadcast!
  "Every room member but the sender (nil sender = everyone)."
  [room frame sender]
  (doseq [ch (keys @(:clients room))
          :when (not (identical? ch sender))]
    (send-frame! ch frame)))

(defn- author-of [principal]
  {:id (:id principal)
   :type (name (:type principal :human))
   :display (or (:display principal) (:id principal))})

(defn- participants
  "The merged roster: this process's members plus every live remote
  origin's, deduped by id, id-ordered."
  [room]
  (->> (concat (vals @(:clients room))
               (mapcat (comp vals :actors) (vals @(:remotes room))))
       (reduce (fn [m a] (assoc m (:id a) a)) (sorted-map))
       vals
       vec))

;; ── the cross-process relay ─────────────────────────────────────────

(def collab-channel "waymark10_collab")

(def ^:private notify-cap
  "pg_notify's payload comfort ceiling; an oversized frame relays as
  a resync hint instead."
  7000)

;; engine's rooms atom → the running relay; one LISTEN connection per
;; engine (per process), lazily started with the first room
(defonce ^:private relays (atom {}))
(defonce ^:private relay-lock (Object.))

(defn- origin-of ^String [eng]
  (:origin (get @relays (:collab-rooms eng)) "solo"))

(defn- notify-payload ^String [origin [kind id action] frame]
  (let [s (wire/write-json {:origin origin :kind (name kind) :id id
                            :action (name action) :frame frame})]
    (if (<= (count s) notify-cap)
      s
      (wire/write-json {:origin origin :kind (name kind) :id id
                        :action (name action)
                        :frame {:type "resync" :field (:field frame)}}))))

(defn- notify-tx!
  "Relay a frame inside the accepting transaction — it exists iff the
  commit does."
  [eng tx key frame]
  (jdbc/execute-one! tx ["SELECT pg_notify(?, ?)" collab-channel
                         (notify-payload (origin-of eng) key frame)])
  nil)

(defn- notify!
  "Relay a frame outside any transaction (presence, heartbeats)."
  [eng key frame]
  (try
    (store/with-tx (:storage eng)
      (fn [tx] (notify-tx! eng tx key frame)))
    (catch Exception e
      (warn! "notify failed: " (ex-message e)))))

(defn- collab-connection
  "A dedicated raw LISTEN connection for the relay — never from the
  Hikari pool (getNotifications parks it; the dispatcher's own
  discipline)."
  ^Connection [storage]
  (let [url (.getJdbcUrl ^HikariDataSource (:ds storage))
        conn (DriverManager/getConnection url)]
    (with-open [stmt (.createStatement conn)]
      (.execute stmt (str "LISTEN " collab-channel)))
    conn))

(declare regate-room!)

(defn- heartbeat!
  "Re-assert this process's rosters and evict origins silent for
  three intervals — a crashed peer's ghosts leave on the clock, not
  never."
  [eng hb-ms]
  (let [now (System/currentTimeMillis)
        cutoff (- now (* 3 (long hb-ms)))]
    (doseq [[key room] @(:collab-rooms eng)]
      (notify! eng key {:type "presence-hb"
                        :actors (vec (vals @(:clients room)))})
      (let [stale (into [] (keep (fn [[org {:keys [seen]}]]
                                   (when (< (long seen) cutoff) org)))
                        @(:remotes room))]
        (when (seq stale)
          (swap! (:remotes room) #(apply dissoc % stale))
          (broadcast! room {:type "presence" :event "roster"
                            :participants (participants room)}
                      nil))))))

(defn- remote-presence!
  "A join/leave from another process: fold the actor into that
  origin's roster and tell the local members the merged truth. A
  first-seen origin gets our roster back at once (one targeted
  heartbeat), so two processes converge in a round trip."
  [eng key room origin' frame]
  (let [now (System/currentTimeMillis)
        actor (:actor frame)
        new? (not (contains? @(:remotes room) origin'))]
    (swap! (:remotes room) update origin'
           (fn [r]
             (let [actors (:actors r {})]
               {:seen now
                :actors (case (:event frame)
                          "joined" (assoc actors (:id actor) actor)
                          "left" (dissoc actors (:id actor))
                          actors)})))
    (when new?
      (notify! eng key {:type "presence-hb"
                        :actors (vec (vals @(:clients room)))}))
    (broadcast! room {:type "presence" :event (:event frame) :actor actor
                      :participants (participants room)}
                nil)))

(defn- remote-hb! [room origin' frame]
  (let [before (participants room)
        actors (into {} (map (fn [a] [(:id a) a])) (:actors frame))]
    (swap! (:remotes room) assoc origin'
           {:seen (System/currentTimeMillis) :actors actors})
    (let [after (participants room)]
      (when (not= before after)
        (broadcast! room {:type "presence" :event "roster"
                          :participants after}
                    nil)))))

(defn- on-notification! [eng origin ^String payload]
  (let [msg (try (wire/read-json payload) (catch Exception _ nil))]
    (when (and (map? msg) (not= origin (:origin msg)))
      (let [key [(keyword (:kind msg)) (:id msg) (keyword (:action msg))]
            frame (:frame msg)]
        (when-some [room (get @(:collab-rooms eng) key)]
          (case (:type frame)
            "presence" (remote-presence! eng key room (:origin msg) frame)
            "presence-hb" (remote-hb! room (:origin msg) frame)
            ;; update / edit / resync — deliver verbatim: the frame
            ;; was accepted (and persisted) where it happened
            (broadcast! room frame nil)))))))

(defn- start-relay!
  "One per-process relay: the LISTEN thread (frames in, heartbeats
  out) and — when the engine runs a dispatcher — the regate consumer
  that watches for the fence moving under any room. The relay
  registers itself BEFORE its threads start, so the first heartbeat
  already carries the real origin nonce."
  [eng]
  (let [storage (:storage eng)
        origin (str (random-uuid))
        hb-ms (long (:collab-heartbeat-ms eng 5000))
        running (atom true)
        conn (try (collab-connection storage)
                  (catch Exception e
                    (warn! "no LISTEN connection (" (ex-message e)
                           "); frames stay process-local")
                    nil))
        pg-conn (some-> ^Connection conn (.unwrap PGConnection))
        dispatcher (some-> (:runtime eng) deref :dispatcher)
        sub (when dispatcher (events/subscribe dispatcher {}))
        listen-t
        (Thread.
         ^Runnable
         (fn []
           (let [last-hb (atom 0)]
             (while @running
               (try
                 (if pg-conn
                   (doseq [^PGNotification n
                           (.getNotifications ^PGConnection pg-conn
                                              (int (min hb-ms 1000)))]
                     (on-notification! eng origin (.getParameter n)))
                   (Thread/sleep (min hb-ms 1000)))
                 (let [now (System/currentTimeMillis)]
                   (when (>= (- now (long @last-hb)) hb-ms)
                     (reset! last-hb now)
                     (heartbeat! eng hb-ms)))
                 (catch InterruptedException _ nil)
                 (catch Exception e
                   (when @running
                     (warn! "relay loop: " (ex-message e))
                     (try (Thread/sleep 1000)
                          (catch InterruptedException _ nil))))))))
         "waymark10-collab-relay")
        regate-t
        (when sub
          (Thread.
           ^Runnable
           (fn []
             (loop []
               (let [evt (events/take-event sub 60000)]
                 (cond
                   (= ::events/closed evt) nil
                   (nil? evt) (when @running (recur))
                   :else
                   (do (when @running
                         (doseq [[key room] @(:collab-rooms eng)
                                 :when (and (= (:kind evt) (first key))
                                            (= (:resource-id evt) (second key)))]
                           (try (regate-room! eng key room)
                                (catch Exception e
                                  (warn! "regate failed: " (ex-message e))))))
                       (when @running (recur)))))))
           "waymark10-collab-regate"))
        state {:origin origin :conn conn :running running
               :dispatcher dispatcher :sub sub
               :listen-thread listen-t :regate-thread regate-t}]
    (swap! relays assoc (:collab-rooms eng) state)
    (doto ^Thread listen-t (.setDaemon true) (.start))
    (some-> ^Thread regate-t (doto (.setDaemon true) (.start)))
    state))

(defn- stop-relay! [{:keys [conn running dispatcher sub
                            ^Thread listen-thread ^Thread regate-thread]}]
  (reset! running false)
  (when sub (events/unsubscribe dispatcher sub))
  (some-> ^Connection conn (.close))
  (some-> listen-thread .interrupt)
  (some-> listen-thread (.join 5000))
  (some-> regate-thread (.join 5000))
  nil)

(defn- ensure-relay! [eng]
  (locking relay-lock
    (or (get @relays (:collab-rooms eng))
        (start-relay! eng))))

(defn- reap-relay!
  "Stop the relay with the last room."
  [eng]
  (locking relay-lock
    (when-some [s (get @relays (:collab-rooms eng))]
      (when (empty? @(:collab-rooms eng))
        (stop-relay! s)
        (swap! relays dissoc (:collab-rooms eng))))))

(defn- leave! [eng key room ch]
  (let [clients (swap! (:clients room) dissoc ch)]
    (when (empty? clients)
      (swap! (:collab-rooms eng)
             (fn [rooms]
               ;; only reap the room we joined — a racing rejoin may
               ;; have re-minted the key
               (if (identical? (get rooms key) room)
                 (dissoc rooms key)
                 rooms)))
      (reap-relay! eng))))

;; ── the door ────────────────────────────────────────────────────────

(defn- live-defn
  "The action's normalized definition when its draft policy is
  {:shared true :live true}; 404 otherwise — a collab route for an
  unlive draft does not exist."
  [rdef action-name]
  (or (when-some [a (get-in rdef [:actions action-name])]
        (when (and (get-in a [:edit :draft :shared])
                   (get-in a [:edit :draft :live]))
          (assoc a :name action-name)))
      (throw (p/problem :not-found 404 "Not found"
                        {:detail (str (name (:kind rdef))
                                      " has no live shared draft on "
                                      (name action-name) ".")}))))

;; ── the document under the fence ────────────────────────────────────

(defn- open-doc
  "Inside a transaction: lock the resource row (FOR UPDATE — the same
  fence an invoke takes, so collab writers on every process
  serialize), load the shared draft's document, and reconcile the
  fence: when the row's version moved past the draft's base, every
  field's rev bumps, prose op logs clear, and the bumped document
  persists — the returned :regate frame tells the room. nil when the
  resource row is gone."
  [eng kind action-name id tx]
  (when-some [row (store/load-row (:storage eng) tx kind id
                                  {:for-update true})]
    (let [draft (store/load-draft (:storage eng) tx kind id
                                  action-name "shared")
          doc (drafts/document (:values draft))]
      (if (and draft (not= (:base-version draft) (:version row)))
        (let [revs' (reduce (fn [m k] (update m k (fnil inc 0)))
                            (:revs doc)
                            (distinct (concat (keys (:values doc))
                                              (keys (:revs doc)))))
              doc' (assoc doc :revs revs' :ops {})]
          (store/save-draft! (:storage eng) tx kind id action-name "shared"
                             (drafts/envelope doc') (:version row))
          {:row row :doc doc' :draft? true
           :regate {:type "regate" :base_version (:version row)
                    :revs (p/wire-value revs')}})
        {:row row :doc doc :draft? (some? draft)}))))

(defn- save-doc! [eng kind action-name id tx doc version]
  (store/save-draft! (:storage eng) tx kind id action-name "shared"
                     (drafts/envelope doc) version))

(defn- regate-room!
  "The fence moved under this room (a transition landed): reconcile
  and tell everyone. A consumed (or never-started) draft is
  {type: regate, gone: true} — compose anew; a surviving draft's revs
  bump so sets against the old base answer stale."
  [eng [kind id action-name :as _key] room]
  (locking (:lock room)
    (let [frame (store/with-tx (:storage eng)
                  (fn [tx]
                    (if-some [{:keys [row doc draft? regate]}
                              (open-doc eng kind action-name id tx)]
                      (or regate
                          (if draft?
                            {:type "regate" :base_version (:version row)
                             :revs (p/wire-value (:revs doc))}
                            {:type "regate" :base_version (:version row)
                             :gone true}))
                      {:type "regate" :base_version nil :gone true})))]
      (broadcast! room frame nil))))

;; ── frames ──────────────────────────────────────────────────────────

(defn- error! [ch errors]
  (send-frame! ch {:type "error" :errors (p/wire-value errors)}))

(defn- state-frame
  "The relay/2 state body: the document, the fence, the roster."
  [eng rdef defn' id room type']
  (store/with-tx (:storage eng)
    (fn [tx]
      (if-some [row (store/load-row (:storage eng) tx (:kind rdef) id {})]
        (let [draft (store/load-draft (:storage eng) tx (:kind rdef) id
                                      (:name defn') "shared")
              doc (drafts/document (:values draft))]
          {:type type'
           :values (p/wire-value (:values doc))
           :revs (p/wire-value (:revs doc))
           :authors (p/wire-value (:authors doc))
           :base_version (:version row)
           :stale (boolean (and draft
                                (not= (:base-version draft) (:version row))))
           :participants (participants room)})
        {:type "regate" :base_version nil :gone true}))))

(defn- deliver-outcome!
  "One accepted/refused frame's answers, in room-lock order: the
  regate first when the fence moved, then the sender's ack/stale/
  error and the others' broadcast."
  [rdef id room ch {:keys [gone regate stale errors ack broadcast]}]
  (when regate (broadcast! room regate nil))
  (cond
    gone (do (broadcast! room {:type "regate" :base_version nil :gone true}
                         nil)
             (error! ch {:_root [(str "no " (name (:kind rdef)) " " id)]}))
    errors (error! ch errors)
    stale (send-frame! ch stale)
    :else (do (when ack (send-frame! ch ack))
              (when broadcast (broadcast! room broadcast ch)))))

(defn- base-rev-of ^long [msg]
  (long (or (some #(when (int? %) %)
                  [(:rev msg) (:base_rev msg) (:base-rev msg)])
            0)))

(defn- handle-set!
  "A whole-value set: scalar merge discipline (base rev must equal
  the field's current rev), and on a prose field a rebase point —
  the op log clears, in-flight edits against older revs go stale."
  [eng rdef defn' id room ch msg principal ctx]
  (let [field-str (:field msg)
        field (when (string? field-str) (keyword field-str))]
    (cond
      (nil? field)
      (error! ch {:_root ["expected {\"type\": \"set\", \"field\", \"value\", \"rev\"}"]})

      (not (contains? (:known ctx) field))
      (error! ch {field ["unknown field"]})

      :else
      (let [value (:value msg)
            base (base-rev-of msg)
            decoded (schema/decode (:input defn') {field value})]
        (if-some [errors (schema/partial-closed-errors (:input defn') decoded)]
          (error! ch errors)
          (locking (:lock room)
            (let [key [(:kind rdef) id (:name defn')]
                  outcome
                  (store/with-tx (:storage eng)
                    (fn [tx]
                      (if-some [{:keys [row doc regate]}
                                (open-doc eng (:kind rdef) (:name defn') id tx)]
                        (let [cur (long (get-in doc [:revs field] 0))]
                          (if (not= base cur)
                            {:regate regate
                             :stale {:type "stale" :field field-str :rev cur
                                     :value (p/wire-value
                                             (get-in doc [:values field]))}}
                            (let [rev' (inc cur)
                                  author (author-of principal)
                                  doc' (-> doc
                                           (assoc-in [:values field] value)
                                           (assoc-in [:revs field] rev')
                                           (assoc-in [:authors field] author)
                                           (update :ops dissoc field))
                                  frame {:type "update" :field field-str
                                         :value (p/wire-value value)
                                         :rev rev' :author author}]
                              (save-doc! eng (:kind rdef) (:name defn') id tx
                                         doc' (:version row))
                              (notify-tx! eng tx key frame)
                              {:regate regate
                               :ack {:type "ack" :field field-str :rev rev'}
                               :broadcast frame})))
                        {:gone true})))]
              (deliver-outcome! rdef id room ch outcome))))))))

(defn- handle-edit!
  "An operation frame on a prose field: transform against everything
  applied since the client's base rev, apply, log, broadcast the
  transformed op."
  [eng rdef defn' id room ch msg principal ctx]
  (let [field-str (:field msg)
        field (when (string? field-str) (keyword field-str))
        ops (parse-ops (:ops msg))]
    (cond
      (nil? field)
      (error! ch {:_root ["expected {\"type\": \"edit\", \"field\", \"rev\", \"ops\"}"]})

      (not (contains? (:known ctx) field))
      (error! ch {field ["unknown field"]})

      (not (contains? (:prose ctx) field))
      (error! ch {field ["not a prose field — use set"]})

      (nil? ops)
      (error! ch {field ["malformed ops: [{\"retain\": n} | {\"insert\": s} | {\"delete\": n}]"]})

      :else
      (let [base (base-rev-of msg)]
        (locking (:lock room)
          (let [key [(:kind rdef) id (:name defn')]
                outcome
                (store/with-tx (:storage eng)
                  (fn [tx]
                    (if-some [{:keys [row doc regate]}
                              (open-doc eng (:kind rdef) (:name defn') id tx)]
                      (let [cur (long (get-in doc [:revs field] 0))
                            fstate {:value (or (get-in doc [:values field]) "")
                                    :rev cur
                                    :log (get-in doc [:ops field] [])}
                            out (accept-edit fstate base ops)]
                        (case (:outcome out)
                          :stale
                          {:regate regate
                           :stale {:type "stale" :field field-str :rev cur
                                   :value (:value fstate)}}

                          :malformed
                          {:regate regate
                           :errors {field ["ops do not span the document"]}}

                          :applied
                          (let [v' (:value (:state out))
                                decoded (schema/decode (:input defn')
                                                       {field v'})]
                            (if-some [errors (schema/partial-closed-errors
                                              (:input defn') decoded)]
                              {:regate regate :errors errors}
                              (let [rev' (:rev (:state out))
                                    author (author-of principal)
                                    doc' (-> doc
                                             (assoc-in [:values field] v')
                                             (assoc-in [:revs field] rev')
                                             (assoc-in [:authors field] author)
                                             (assoc-in [:ops field]
                                                       (:log (:state out))))
                                    frame {:type "edit" :field field-str
                                           :rev rev' :ops (:ops' out)
                                           :author author}]
                                (save-doc! eng (:kind rdef) (:name defn') id tx
                                           doc' (:version row))
                                (notify-tx! eng tx key frame)
                                {:regate regate
                                 :ack {:type "ack" :field field-str :rev rev'}
                                 :broadcast frame})))))
                      {:gone true})))]
            (deliver-outcome! rdef id room ch outcome)))))))

(defn- handle-sync! [eng rdef defn' id room ch]
  (send-frame! ch (state-frame eng rdef defn' id room "sync")))

(defn- handle! [eng rdef defn' id room ch raw principal ctx]
  (let [msg (when (string? raw)
              (try (wire/read-json raw) (catch Exception _ ::bad)))]
    (cond
      (or (= ::bad msg) (not (map? msg)))
      (error! ch {:_root ["invalid JSON"]})

      (= "set" (:type msg))
      (handle-set! eng rdef defn' id room ch msg principal ctx)

      (= "edit" (:type msg))
      (handle-edit! eng rdef defn' id room ch msg principal ctx)

      (= "sync" (:type msg))
      (handle-sync! eng rdef defn' id room ch)

      :else
      (error! ch {:_root ["expected type \"set\", \"edit\" or \"sync\""]}))))

;; ── the route ───────────────────────────────────────────────────────

(defn- prose-fields [input-form]
  (into #{}
        (keep (fn [[k entry]]
                (when (= "prose" (get-in entry [:properties :x-display :widget]))
                  k)))
        (schema/entry-map input-form)))

(defn join
  "Upgrade one request into a room membership for the draft's
  lifetime. The resource must exist and the action must declare a
  live shared draft — refusals throw problems BEFORE the upgrade, so
  the handshake answers them as plain HTTP. The joiner is answered
  with the state frame; the room (local and remote) hears the
  presence."
  [eng rdef action-name id principal req]
  (let [defn' (live-defn rdef action-name)
        _ (or (store/with-tx (:storage eng)
                (fn [tx] (store/load-row (:storage eng) tx (:kind rdef) id {})))
              (throw (p/not-found (:kind rdef) id)))
        key [(:kind rdef) id (:name defn')]
        room (room-of eng key)
        ctx {:known (set (schema/entry-keys (:input defn')))
             :prose (prose-fields (:input defn'))}
        author (author-of principal)]
    (ensure-relay! eng)
    (http/as-channel
     req
     {:on-open (fn [ch]
                 (swap! (:clients room) assoc ch author)
                 (send-frame! ch (state-frame eng rdef defn' id room "state"))
                 (broadcast! room {:type "presence" :event "joined"
                                   :actor author
                                   :participants (participants room)}
                             ch)
                 (notify! eng key {:type "presence" :event "joined"
                                   :actor author}))
      :on-receive (fn [ch raw]
                    (handle! eng rdef defn' id room ch raw principal ctx))
      :on-close (fn [ch _status]
                  (notify! eng key {:type "presence" :event "left"
                                    :actor author})
                  (leave! eng key room ch)
                  (broadcast! room {:type "presence" :event "left"
                                    :actor author
                                    :participants (participants room)}
                              ch))})))
