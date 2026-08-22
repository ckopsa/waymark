(ns waymark10.server.presence
  "Presence — waymark9's follow-me surface restored: your screen goes
  where the followed principal LOOKS, not just where they write.
  Presence is EPHEMERAL STATE, never law: no table, no transitions,
  no fingerprint — an in-process registry fanned across processes on
  its own pg_notify channel (waymark10_presence, origin-nonce'd, the
  collab relay's precedent), TTL-evicted. A restart forgets everyone;
  the next heartbeat re-teaches it.

  REPORTING, three doors, each marked by :source on the wire:
  (a) implicit — a per-resource SSE subscription IS presence (the
      engine already knows the principal and the resource): the
      router's stream hook registers on subscribe and drops on
      disconnect (source \"stream\");
  (b) explicit — POST /api/-/presence {self} is a heartbeat for
      clients that only hold the firehose, the ported UI's case
      (source \"heartbeat\"). Three missed heartbeats evict.
  (c) implicit — a GRANT-SCOPED principal's successful GET marks its
      gaze on what it read (source \"read\"): under a leash means
      watchable, so a raw-HTTP agent is followable with zero
      cooperation. Unscoped reads stay invisible, as ever.

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
  One widening (waymark-tti.4): a self that is EXACTLY /api/{plural}
  — a collection screen — shows to a scoped viewer iff its grant
  sees the WHOLE kind (grants/visibility :whole-kind?, no ids or
  filter narrowing); everything else stays concealed as before.

  THE CURTAIN (waymark-tti.4): a principal whose member row carries
  :curtain true (members.clj draw_curtain) is NOT PUBLISHED — all
  three doors refuse its entries at publish time (nothing stored,
  nothing notified, so no process, local or remote, ever holds a
  frame), and merged-view/snapshot filter as belt and braces. The
  curtain is durable BECAUSE it is a member field; presence itself
  stays ephemeral — the registry only ever READS the row, through
  the SHARED curtain component (server/curtain.clj — one reader, one
  cache, the intents surface asking the same question), and never
  writes anything anywhere. The honest bound on a drawn curtain,
  post-review: the draw is REFUSED at publish time from the moment
  the transition's invalidation lands (the committed draw travels
  the events log to every process, which forgets the pid and evicts
  its live entries — that eviction's diff IS the leave frame), a
  cached stale open survives at most :curtain-ttl-ms (the engine
  passes 2s), and the sweep's fresh read clears the board within one
  heartbeat even if both wires were lost.

  CROSS-PROCESS: every local report notifies {origin, pid, entry}
  (drops notify {origin, pid}); each process re-asserts its local
  entries every :presence-heartbeat-ms and evicts a remote entry
  silent for three intervals — a crashed peer's ghosts leave on the
  clock. Frames a viewer sees derive from ONE merged-view diff
  (freshest entry per principal across origins), so local reports,
  remote frames and evictions all speak through the same mouth.

  Recorded boundaries, each a sentence:
  - an UNSCOPED principal mid-request (an invoke, a GET) is invisible
    — held streams and explicit heartbeats are its only doors, so a
    human's casual curl paints no gaze; only the leashed (the read
    door above) are seen by the act of reading itself.
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

;; ── the curtain (a member field, read-only here) ────────────────────
;;
;; The ONE durable thing presence consults: the member row's :curtain
;; (members.clj waymark-tti.4), asked through the SHARED component in
;; server/curtain.clj — one lookup, one cache, one invalidation wire
;; for presence and intents alike. This registry never writes it —
;; the draw/open member actions are the only hands — and it never
;; gains a table to honor it: the curtain is enforced by REFUSING to
;; store or announce, not by remembering.

(defn- curtained?
  "This pid's curtain, through the shared component. fresh? bypasses
  the cache (the sweep's spelling)."
  ([reg pid] (curtain/curtained? (:curtain reg) pid false))
  ([reg pid fresh?] (curtain/curtained? (:curtain reg) pid fresh?)))

(defn- curtain-view
  "The curtain verdict for every pid this publish round could judge —
  resolved BEFORE the lock is taken, because a cache miss reads the
  store and a store read under (:lock reg) would park every door
  behind the database (the skeptic's F5). also names pids the caller
  is about to add (its own beat's); fresh? bypasses the cache for the
  whole round (the sweep's ≤-one-heartbeat promise). Anything not in
  the map reads as CURTAINED downstream — fail closed, and the next
  publish (the caller's own, or the sweep's) corrects the rare race."
  ([reg] (curtain-view reg nil false))
  ([reg also] (curtain-view reg also false))
  ([reg also fresh?]
   (curtain/verdicts
    (:curtain reg)
    (concat also
            (keys @(:local reg))
            (keys @(:published reg))
            ;; a remote entry is judged by the identity it PUBLISHES,
            ;; not by the key its origin notified under, so the round
            ;; resolves both spellings — they are the same string in
            ;; every honest frame, and distinct makes the overlap free
            (for [[_ entries] @(:remotes reg)
                  [pid {:keys [entry]}] entries
                  id [pid (get-in entry [:principal :id])]
                  :when (string? id)]
              id))
    fresh?)))

(defn- verdict
  "One pid's curtain INSIDE a publish round: the prefetched view
  first, and for a pid the view could not have named — one that
  arrived during the prefetch→lock window, so it was in none of the
  maps curtain-view walked — this process's already-warmed cache
  (curtain/cached-verdict: a deref, no store read, safe under the
  lock; the arriving thread's own door filled it).

  `default` is what UNKNOWN means to THIS caller, and the two callers
  mean opposite things by it:
    • publishing (merged-view) passes true — fail closed, we do not
      announce a principal we cannot vouch for;
    • evicting (sweep!) passes nil and acts only on a KNOWN true —
      throwing a live principal off the board and telling every peer
      to drop it is not a safe default, and the publish side is
      already the wall.
  Before this, both read a missing key as `true` and ordinary
  concurrency evicted live, un-curtained principals."
  [reg cv pid default]
  (if (contains? cv pid)
    (get cv pid)
    (curtain/cached-verdict (:curtain reg) pid default)))

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
  process's live locals and every remote origin's. Curtained pids
  are filtered HERE, belt and braces over the doors' own refusals:
  publish! diffs this view, so no join/move frame can fan for a
  curtained principal and no snapshot can serve one — not even in
  the race between a draw and the sweep, and not from a remote
  origin whose own sweep has not caught up yet. cv is the caller's
  prefetched curtain-view (no store reads down here, under the
  lock); a pid it cannot name falls back to the warm cache and, past
  that, reads as curtained.

  A REMOTE entry is judged by the identity it publishes —
  (:principal entry), the id that rides the frame — and not by the
  key its origin notified under: the wire's key is bookkeeping, the
  frame's principal is who subscribers see (intents judges the same
  way)."
  [reg now cv]
  (let [locals (into {}
                     (keep (fn [[pid st]]
                             (when (and (live-local? st now (:hb-ms reg))
                                        (not (verdict reg cv pid true)))
                               [pid (:entry st)])))
                     @(:local reg))]
    (reduce (fn [m [pid e]]
              (if-some [cur (get m pid)]
                (if (> (long (:at-ms e 0)) (long (:at-ms cur 0)))
                  (assoc m pid e) m)
                (assoc m pid e)))
            locals
            (for [[_ entries] @(:remotes reg)
                  [pid {:keys [entry]}] entries
                  :when (not (verdict reg cv (get-in entry [:principal :id])
                                      true))]
              [pid entry]))))

(defn- publish!
  "Re-merge, diff against the last announced truth, fan the
  difference as join/move/leave frames. Reentrant under :lock; cv is
  the curtain-view its caller resolved OUTSIDE the lock."
  [reg cv]
  (locking (:lock reg)
    (let [now (System/currentTimeMillis)
          m (merged-view reg now cv)
          old @(:published reg)]
      (doseq [[pid e] m
              :let [o (get old pid)]]
        (cond
          (nil? o) (fan! reg (frame-of "join" e))
          (not= (:self o) (:self e)) (fan! reg (frame-of "move" e))))
      ;; a LEAVE is whatever merged-view dropped. That is why the
      ;; curtain verdict is resolved there and not here: a pid missing
      ;; from cv used to be filtered out as "curtained" and left the
      ;; board with a fanned leave — a live principal, invisible until
      ;; some later publisher's prefetch happened to include it
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
      ;; the arriving principal rides `also`: it is not in any map
      ;; yet, and the prefetch happens before the lock (F5). It is the
      ;; entry's PUBLISHED identity, not the notify key, because that
      ;; is the id merged-view will judge and the frame will carry
      ;; (intents' spelling)
      (let [rid (get-in msg [:entry :principal :id])
            cv (curtain-view reg (when (string? rid) [rid]))]
        (locking (:lock reg)
          (case (:event msg)
            "report" (when (and (string? (:pid msg)) (map? (:entry msg)))
                       (swap! (:remotes reg) assoc-in [(:origin msg) (:pid msg)]
                              {:entry (:entry msg)
                               :seen (System/currentTimeMillis)}))
            "drop" (swap! (:remotes reg) update (:origin msg) dissoc (:pid msg))
            nil)
          (publish! reg cv))))))

(defn- evict-local!
  "Curtain enforcement's eviction: drop a pid's local entry whole
  (stream refcounts included — an unpublishable presence holds no
  claim), tell the peers, re-publish. The publish!'s diff is where
  the LEAVE frame subscribers observe comes from when a curtain
  draws over a live entry."
  [reg pid]
  (let [cv (curtain-view reg [pid])]
    (locking (:lock reg)
      (when (contains? @(:local reg) pid)
        (swap! (:local reg) dissoc pid)
        (notify! reg {:event "drop" :pid pid}))
      (publish! reg cv)))
  nil)

(defn- reassert!
  "Every hb tick: re-report each local entry so peers keep its :seen
  fresh — the entry itself is unchanged (no phantom moves). A
  curtained pid is never re-taught to peers: the sweep is about to
  evict it, and a re-assertion would hand remote processes the very
  frame the doors refused to publish."
  [reg]
  (doseq [[pid {:keys [entry]}] @(:local reg)
          :when (not (curtained? reg pid))]
    (notify! reg {:event "report" :pid pid :entry entry})))

(defn- sweep!
  "TTL eviction, both directions: local entries whose heartbeats went
  quiet (three missed) and no stream holds them, and remote entries
  whose origin stopped re-asserting."
  [reg]
  ;; the curtain, read FRESH (cache bypassed and refreshed) and read
  ;; BEFORE the lock: this is the ≤-one-heartbeat backstop — a drawn
  ;; curtain whose invalidation never arrived still clears the board
  ;; on the next sweep, whatever the cache believes. One store read
  ;; per present pid per interval; the board is a household, not a
  ;; city.
  (let [cv (curtain-view reg nil true)]
    (locking (:lock reg)
      (let [now (System/currentTimeMillis)
            cutoff (- now (* 3 (long (:hb-ms reg))))
            ;; eviction acts on a KNOWN curtain only (verdict's nil
            ;; default): the fresh prefetch above names every pid
            ;; that was local when it ran, so a pid missing from cv
            ;; joined DURING the prefetch→lock window — live, and
            ;; almost certainly un-curtained. Reading that absence as
            ;; "curtained" evicted it and told every peer to drop it.
            ;; Publishing still fails closed (merged-view), so the
            ;; worst an unknown verdict costs here is one more
            ;; heartbeat before the board settles.
            dead (into [] (keep (fn [[pid st]]
                                  (when (or (not (live-local? st now (:hb-ms reg)))
                                            (true? (verdict reg cv pid nil)))
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
        (publish! reg cv)))))

;; ── reporting (the two doors) ───────────────────────────────────────

(defn normalize-self
  "Accept a full URL where an href was meant — a raw-HTTP agent's
  natural spelling: the origin strips, the path (query and all)
  stays. Anything else passes through untouched for check-self! to
  judge.

  PUBLIC because it is a SECURITY SEAM, not a convenience: this is
  the answer to \"what will this door actually store for that self?\",
  and the router's private-row gate (reportable-self?, waymark-tti.3
  L7) must judge THAT value, not the caller's raw spelling. It once
  judged the raw one, and a full-URL spelling of a private letter
  therefore walked past a gate that could not recognise it. One
  spelling of the stripping, here, read by both the gate and report!
  — never copied into the router."
  [self]
  (if (and (string? self) (re-find #"^https?://" self))
    (let [path (str/replace-first self #"^https?://[^/]*" "")]
      (if (str/blank? path) self path))
    self))

(defn- valid-self? [self]
  (and (string? self)
       (str/starts-with? self "/api/")
       (<= (count self) self-max-chars)))

(defn- check-self! [self]
  (when-not (valid-self? self)
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
  (let [self (normalize-self self)]
    (check-self! self)
    (when (= (:id principal) (:id t/anonymous))
      (throw (p/problem :presence-anonymous 422 "Presence names its principal"
                        {:detail "An anonymous heartbeat would mark nobody; present a principal."})))
    (let [pid (:id principal)]
      ;; the curtain: a curtained principal's beat is ACCEPTED — the
      ;; response shape never changes, so the wire does not narrate
      ;; the curtain to whoever sent the beat — but it publishes
      ;; NOTHING: not stored, not notified, so no process ever holds
      ;; a frame. Any entry that predates the draw drops right now
      ;; (its own beat is the earliest messenger) instead of waiting
      ;; for the sweep.
      (if (curtained? reg pid)
        (evict-local! reg pid)
        (let [e (entry-of reg principal self "heartbeat")
              cv (curtain-view reg [pid])]
          (locking (:lock reg)
            (swap! (:local reg) update pid
                   (fn [st] (-> (or st {:streams {}})
                                (assoc :entry e :hb-at (:at-ms e)))))
            (notify! reg {:event "report" :pid pid :entry e})
            (publish! reg cv))))
      nil)))

(def read-beat-ms
  "Same-self implicit reads collapse to one report per this window —
  the reference client's own throttle, server-side."
  5000)

(defn read!
  "The third door: a grant-scoped principal's successful GET IS its
  gaze — the read itself reports (source \"read\"), no second request.
  Router-called and best-effort by construction: an anonymous
  principal or a malformed self marks nothing, nothing here throws,
  and a same-self re-read within read-beat-ms is silent — the read's
  fate never rides on being seen."
  [reg principal self]
  (let [self (normalize-self self)]
    (when (and (valid-self? self)
               (not= (:id principal) (:id t/anonymous))
               ;; the curtain: a curtained principal's reads stamp
               ;; nothing — judged before the throttle bookkeeping,
               ;; so the first read after an open_curtain reports
               ;; immediately instead of hiding in a stale window
               (not (curtained? reg (:id principal))))
      (let [pid (:id principal)
            now (System/currentTimeMillis)
            [prev at] (get @(:read-at reg) pid [nil 0])]
        ;; the throttle yields to absence: an evicted principal's next
        ;; read always re-reports (a TTL shorter than the throttle —
        ;; a test clock's ordering — must not leave re-reads unseen)
        (when (or (not (contains? @(:local reg) pid))
                  (not= prev self)
                  (< (+ (long at) read-beat-ms) now))
          (swap! (:read-at reg) assoc pid [self now])
          (let [e (entry-of reg principal self "read")
                cv (curtain-view reg [pid])]
            (locking (:lock reg)
              (swap! (:local reg) update pid
                     (fn [st] (-> (or st {:streams {}})
                                  (assoc :entry e :hb-at (:at-ms e)))))
              (notify! reg {:event "report" :pid pid :entry e})
              (publish! reg cv))))))
    nil))

(defn stream-open!
  "The implicit door, opening half: a per-resource SSE subscription
  IS presence — the router's hook calls this on subscribe. A
  curtained principal's subscription still OPENS (watching was never
  the curtain's business — being watched is); only the presence
  registration is refused: nothing stored, nothing notified."
  [reg principal self]
  (let [pid (:id principal)]
    (when-not (curtained? reg pid)
      (let [e (entry-of reg principal self "stream")
            cv (curtain-view reg [pid])]
        (locking (:lock reg)
          (swap! (:local reg) update pid
                 (fn [st] (-> (or st {:streams {}})
                              (update-in [:streams self] (fnil inc 0))
                              (assoc :entry e))))
          (notify! reg {:event "report" :pid pid :entry e})
          (publish! reg cv))))
    nil))

(defn stream-closed!
  "The implicit door, closing half: drop on disconnect. When another
  stream (or a fresh heartbeat) still holds the principal, the entry
  survives — its self moves back to a held stream if the closed one
  was the announced screen."
  [reg principal self]
  (let [pid (:id principal)
        ;; both branches below publish; the verdict and the whole
        ;; round's view are resolved before the lock (F5)
        drawn? (curtained? reg pid)
        cv (curtain-view reg [pid])]
    (locking (:lock reg)
      (let [st (get @(:local reg) pid)]
        (when st
          (if drawn?
            ;; the curtain drew while streams were open and the sweep
            ;; has not passed yet: whatever remains is unpublishable —
            ;; drop it whole rather than re-notify an entry (the else
            ;; branch's move-back re-report) the doors would refuse
            (do (swap! (:local reg) dissoc pid)
                (notify! reg {:event "drop" :pid pid}))
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
                    (notify! reg {:event "drop" :pid pid})))))))
      (publish! reg cv))
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
  stream's first frame. Curtained pids are filtered here TOO
  (merged-view already refused them into :published; this is the
  last brace): a join snapshot must never serve a curtained
  principal, however the draw races the publish. Through `verdict`,
  so a pid that joined during the prefetch→lock window is judged by
  the warm cache and not by its absence — the first frame is the only
  one a new subscriber gets for an already-published principal, so
  dropping a live one here hid it until it moved."
  [reg visible?]
  (let [cv (curtain-view reg)]
    (locking (:lock reg)
      (into []
            (comp (remove (fn [[pid _]] (verdict reg cv pid true)))
                  (map val)
                  (filter #(visible? (:self %)))
                  (map #(select-keys % [:principal :self :source :at])))
            (sort-by key @(:published reg))))))

;; ── visibility (the concealment predicate) ──────────────────────────

(defn self-visible?
  "The stream's concealment predicate for one request: nil visibility
  (an unscoped viewer) sees all; a scoped viewer sees a presence iff
  it could GET the self it names — the row? closure the request
  already resolved. One widening (waymark-tti.4, symmetric presence):
  a self that is EXACTLY /api/{plural} — a collection screen — shows
  iff the grant sees the WHOLE kind, judged by the visibility's own
  :whole-kind? (no ids, no filter narrowing; grants.clj), never by
  sampling :row? — ids-narrowed sight of SOME rows is not sight of
  the collection. Everything else (non-/api/ selves, the workspace,
  door selves like /api/-/events — their \"plural\" names no rdef)
  stays concealed from scoped viewers, exactly as before."
  [eng vis]
  (if (nil? vis)
    (constantly true)
    (fn [self]
      (boolean
       (let [self (str self)
             parts (str/split self #"/")
             rdef-by-plural (fn [plural]
                              (some (fn [[_ r]] (when (= plural (:plural r)) r))
                                    (inv/resources eng)))]
         (cond
           ;; a row self /api/{plural}/{id}: unchanged — the row?
           ;; closure the request already resolved is the judge
           (and (= 4 (count parts)) (= "api" (nth parts 1)))
           (when-some [rdef (rdef-by-plural (nth parts 2))]
             ((:row? vis) (:kind rdef) (nth parts 3)))
           ;; a collection self, exactly /api/{plural}: str/split
           ;; drops trailing empties, so /api/tasks/ would count 3 —
           ;; the ends-with guard keeps EXACTLY exact. A self with a
           ;; query string fails the rdef lookup and stays concealed.
           (and (= 3 (count parts)) (= "api" (nth parts 1))
                (not (str/ends-with? self "/")))
           (when-some [rdef (rdef-by-plural (nth parts 2))]
             (when-some [whole-kind? (:whole-kind? vis)]
               (whole-kind? (:kind rdef))))
           :else nil))))))

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

(defrecord Registry []
  ;; The three implicit doors, as CORE knocks on them
  ;; (waymark-db9.7). The router used to require this namespace for
  ;; them: a GET marks a gaze, an SSE subscription opens and closes
  ;; one, and none of the three is a route. They are the same three
  ;; fns above, reachable now by TYPE — core holds this registry
  ;; already (runtime/surface eng :presence, the handle the realtime
  ;; module's own lifecycle hook published) and asks it to do its
  ;; own work.
  ;;
  ;; No fields: the registry is a map that has always known its own
  ;; shape, built by start! below and read by keyword everywhere in
  ;; this namespace. The record adds a NAME for that map and nothing
  ;; else — the price of protocol dispatch, and cheaper than
  ;; re-declaring fifteen keys in two places that could drift.
  seams/Gaze
  (mark-read! [reg principal self] (read! reg principal self))
  (watch-opened! [reg principal self] (stream-open! reg principal self))
  (watch-closed! [reg principal self] (stream-closed! reg principal self)))

(defn start!
  "The engine's presence registry: one LISTEN thread (frames in,
  re-assertions out, TTL sweeps on the clock). opts {:hb-ms} —
  default the engine's :presence-heartbeat-ms (15s); eviction is
  three missed intervals. The curtain arrives as :curtain — the
  SHARED component (curtain/start!) the engine hands presence and
  intents alike, so one member-row read serves both; without it the
  registry starts a private one from :curtained? (a fn pid → truthy,
  tests' seam; default reads the member row) and :curtain-ttl-ms
  (default 2s — the shared component's own, since a private curtain
  has no invalidation wire and its TTL is all a draw gets). Presence
  also WATCHES the shared curtain: a committed draw evicts that pid's
  entries at once, and the eviction's diff is the leave frame.
  Returns the registry; stop! ends it."
  [eng {:keys [hb-ms curtained? curtain-ttl-ms curtain]}]
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
        ;; the curtain consult (waymark-tti.4): the engine's shared
        ;; component, or a private one for a standalone registry — a
        ;; read-only lookup and its per-pid TTL cache; presence stays
        ;; ephemeral and no table is ever gained
        ;; the standalone TTL is the SHARED component's (2s), not the
        ;; heartbeat's 15s: with no dispatcher there is no
        ;; invalidation wire, so the TTL is the only thing that ever
        ;; honors a draw — a quarter-minute of it was the widest gap
        ;; in the house, and it differed from production for no
        ;; reason. curtain/start! says so out loud now too
        own-curtain (when (nil? curtain)
                      (curtain/start! eng {:lookup curtained?
                                           :ttl-ms (or curtain-ttl-ms 2000)}))
        reg (map->Registry
             {:eng eng
              :storage storage
              :origin (str (random-uuid))
              :hb-ms hb-ms
              :lock (Object.)
              :local (atom {})
              :remotes (atom {})
              ;; the read door's throttle: pid → [self at-ms]
              :read-at (atom {})
              :curtain (or curtain own-curtain)
              :own-curtain own-curtain
              :published (atom {})
              :subs (atom #{})
              :running (atom true)
              :conn conn})
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
         "waymark10-presence")
        ;; a DRAWN curtain is an event, not just a fact: the shared
        ;; component hears the committed transition (its own process's
        ;; and every peer's) and calls this — the entry goes, the
        ;; peers hear the drop, and publish!'s diff is the leave frame
        ;; subscribers observe. The sweep's fresh read stays the
        ;; backstop for a lost invalidation, never the mechanism.
        watch (curtain/watch! (:curtain reg)
                              (fn [pid] (evict-local! reg pid)))]
    (doto ^Thread thread (.setDaemon true) (.start))
    (assoc reg :thread thread :curtain-watch watch)))

(defn stop! [reg]
  (reset! (:running reg) false)
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
