(ns waymark10.server.walks
  "The recorded walk (docs/spec-guided-follow.md § 4): what a follower
  saw while it followed, kept as rows, sealed, and purged after its
  retention.

  TWO KINDS, transcript and transcript_entry's shape
  (server/transcripts.clj). `walk` is one row per recording: the
  recorder starts it, seals it, and may purge it; the retention sweep
  purges the rest. `walk_frame` is one row per frame, written with the
  engine's own hand and no transition of its own (`inv/insert-quiet!`),
  because the recorder's stream is the write worth recording and a
  transition per frame would put the stream in the log a second time.

  A WALK HOLDS NO MORE THAN ITS RECORDER SAW. `record-frame!` takes the
  recorder's own visibility (grants/visibility's closures) and a frame
  as the recorder's stream received it, after section 1's redaction. A
  frame whose `self` the recorder could not GET is not written, and a
  frame never carries request headers, grant ids, keys or credentials:
  those keys are dropped from the body at any depth before the insert.

  THE ROW OUTLIVES ITS FRAMES. The purge deletes every frame and moves
  the walk to `purged`; the row keeps its title and its counts as the
  audit that a walk existed."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Duration Instant)
           (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 walks: " parts))))

(def kind :walk)
(def frame-kind :walk_frame)

(def engine-actor
  "The system actor that writes frames and sweeps walks past retention."
  (t/principal {:id "waymark10-walks" :type :system
                :display "Walks"}))

(def default-retention-days
  "A walk naming no retention keeps its frames a month."
  30)

(def frame-types ["move" "ui" "transition" "invitation"])

(def never-recorded
  "Body keys dropped at any depth before a frame is written: a request's
  headers, grant ids, keys and credentials are never part of a frame."
  #{"headers" "header" "authorization" "cookie" "cookies"
    "grant" "grant_id" "grantid" "x-waymark-grant"
    "key" "keys" "api_key" "token" "access_token" "refresh_token"
    "credential" "credentials" "password" "secret"})

(def ^:private sweep-page 500)

(defn- instant-of ^Instant [v]
  (cond
    (instance? Instant v) v
    (string? v) (try (Instant/parse ^String v) (catch Exception _ nil))
    :else nil))

;; ── guards ──────────────────────────────────────────────────────────

(g/defguard a-named-recorder
  {:reads [:principal]
   :open "The wall is about who: a walk records a named principal's own stream, and no field of this door names one."
   :explain "Only a named principal records a walk; an anonymous request has no stream to record."}
  [_row _inp ctx]
  (if (= (:id t/anonymous) (:id (:principal ctx)))
    (t/deny)
    (t/allow)))

(g/defguard the-recorder-or-the-sweep
  {:reads [:principal]
   :open "The wall is about who: the recorder stops and deletes its own walk, the retention sweep deletes the rest, and no field of this door makes anyone else the recorder."
   :explain "Only the walk's recorder, or the engine's retention sweep, moves a walk."}
  [row _inp ctx]
  (let [p (:principal ctx)]
    (if (or (= :system (:type p))
            (= (str (get-in row [:data :recorder])) (str (:id p))))
      (t/allow)
      (t/deny))))

(g/defguard the-engine-writes-frames
  {:reads [:principal]
   :hide true
   :explain "A walk's frames are written by the engine from its recorder's own stream; no hand at the wire does."}
  [_row _inp ctx]
  (if (= :system (:type (:principal ctx)))
    (t/allow)
    (t/deny)))

(def purge-consequence
  "The sentence the purge's confirm echoes."
  "Every frame of this walk is deleted. The row, its title and its counts stay.")

;; ── handlers ────────────────────────────────────────────────────────

(defn- born
  "The birth stamps: the recorder is the principal that created the
  row, never the body; the clock starts now; a walk naming no
  retention gets the default."
  [row ctx]
  (-> row
      (assoc-in [:data :recorder] (str (get-in ctx [:principal :id])))
      (assoc-in [:data :started_at] (or (:now ctx) (Instant/now)))
      (assoc-in [:data :frame_count] 0)
      (update-in [:data :retention_days] #(or % default-retention-days))))

(defhandler stamp-end [row _inp ctx]
  (update-in row [:data :ended_at] #(or % (:now ctx) (Instant/now))))

;; ── the kinds ───────────────────────────────────────────────────────

(def ^:private followed-field
  [:followed {:x-ref {:principal true}
              :x-display {:raw true
                          :label "Who was followed"
                          :help "The principal the recorder followed: their principal id."}}
   [:string {:min 1 :max 128}]])

(def ^:private title-field
  [:title {:examples ["Filing a ticket"]
           :x-display {:label "Title"
                       :help "One line saying what the walk shows."}}
   [:string {:min 1 :max 200}]])

(defresource walk
  {:kind :walk
   :plural "walks"
   :nav :secondary
   :states [:recording :sealed :purged]
   :initial :recording
   :terminal #{:purged}
   :summary "{data.title} · {data.frame_count} frames · {state}"
   :label-template "{data.title}"
   :schema
   [:map
    [:recorder {:x-ref {:principal true}
                :x-display {:raw true
                            :label "Who recorded"
                            :help "The follower whose stream this walk records, stamped by the engine at birth. A frame holds only what this principal could see."}}
     [:string {:min 1 :max 128}]]
    followed-field
    title-field
    [:started_at {:x-display {:label "Started"
                              :help "When the recording began. Each frame's `t` counts from here."}}
     :waymark/instant]
    [:ended_at {:optional true
                :x-display {:label "Ended"
                            :help "When the walk was sealed, or purged while still recording."}}
     [:maybe :waymark/instant]]
    [:frame_count {:default 0
                   :x-display {:label "Frames"
                               :help "How many frames were recorded. It stays after the purge."}}
     [:int {:min 0}]]
    [:retention_days {:default default-retention-days
                      :x-display {:label "Kept for (days)"
                                  :help "Days the frames are kept after the walk ends; then the sweep deletes them."}}
     [:int {:min 1 :max 3650}]]]
   ;; the recorder, the clock and the count are the engine's to write
   :create-schema
   [:map
    followed-field
    title-field
    [:retention_days {:optional true
                      :x-display {:label "Kept for (days)"
                                  :help "Left empty, the frames are kept 30 days after the walk ends."}}
     [:maybe [:int {:min 1 :max 3650}]]]]
   :create-guards [a-named-recorder]
   :on-create born
   :filterable {:state #{:eq :in}
                :recorder #{:eq}
                :followed #{:eq}}
   :sortable {:fields [:created_at] :default "-created_at"}
   :actions
   {:seal
    {:from #{:recording} :to :sealed
     :guards [the-recorder-or-the-sweep]
     :handler stamp-end
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The walk takes no more frames. What it holds stays until the purge."}
     :display {:label "Stop recording" :order 1
               :description "Stop recording; the frames stay until the purge"}}
    :purge
    {:from #{:recording :sealed} :to :purged
     :guards [the-recorder-or-the-sweep]
     :handler stamp-end
     :safety {:idempotent true :reversible false :confirm true
              :consequence purge-consequence}
     :display {:label "Delete the frames" :style :danger :order 2
               :description "Delete every frame of this walk now, before its days are up"}}}
   :deviations
   ["§ 4 lists `export`; this child builds the kinds, the frames and the sweep, and the export door is its own child."
    "`purge` also leaves `recording`, so a walk nobody sealed is still swept after its days, counted from its start."
    "The purge deletes the frames with `store/delete-rows!`, a maintenance delete with no transition per frame; the `purge` transition on this row is the record of it."]})

(defresource walk-frame
  {:kind :walk_frame
   :plural "walk_frames"
   :states [:held]
   :initial :held
   :terminal #{:held}
   :nav :system
   :summary "{data.t} ms · {data.type}"
   :schema
   [:map
    [:walk {:kind :walk
            :x-display {:raw true :label "The walk"}}
     :waymark/ref]
    [:t {:x-display {:label "When (ms)"
                     :help "Milliseconds since the walk started. The default sort."}}
     [:int {:min 0}]]
    [:type {:x-display {:label "Type"
                        :help "move, ui, transition or invitation: what the recorder's stream carried."}}
     (into [:enum] frame-types)]
    [:body {:x-display {:raw true
                        :label "What the stream carried"
                        :help "The frame as the recorder's stream received it, after redaction. No header, grant id, key or credential."
                        :spelled-by-hand "Its keys are the stream's own, which differ per frame type, so no fixed sub-form can offer them."}}
     [:map-of :keyword :any]]]
   :create-guards [the-engine-writes-frames]
   :filterable {:walk #{:eq}
                :type #{:eq :in}}
   :sortable {:fields [:t] :default "t"}
   :links [{:rel "walk" :kind :walk
            :href "/api/walks/{data.walk}"
            :summary "The walk this frame belongs to"}]
   :deviations
   ["Each frame is inserted by the engine's quiet birth door (`inv/insert-quiet!`) with no transition of its own, transcript_entry's precedent."]})

;; ── recording ───────────────────────────────────────────────────────

(defn- scrub
  "The body with every never-recorded key dropped, at any depth."
  [body]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (into {} (remove (fn [[k _]]
                          (and (or (keyword? k) (string? k))
                               (contains? never-recorded
                                          (str/lower-case (name k))))))
             x)
       x))
   body))

(defn- kind-of-plural [eng plural]
  (some (fn [[k rdef]] (when (= plural (:plural rdef)) k))
        (inv/resources eng)))

(defn- sees-self?
  "Could the recorder GET this `self`? A row path asks `:row?`, a bare
  collection path asks whole-kind sight, and a path naming no served
  kind is never seen. nil `sight` is an unscoped recorder."
  [eng sight self]
  (let [[_ plural id] (re-matches #"/api/([^/?#]+)(?:/([^/?#]+))?(?:[?#].*)?"
                                  (str/trim (str self)))
        k (when (and plural (not= "-" plural)) (kind-of-plural eng plural))]
    (cond
      (nil? k) false
      (nil? sight) true
      id (boolean (some-> (:row? sight) (apply [k id])))
      :else (boolean (some-> (:whole-kind? sight) (apply [k]))))))

(defn record-frame!
  "Write one frame of a recording walk, as the recorder's stream
  received it. `sight` is the recorder's own visibility; `frame` is
  {:type :body}, and `(:self body)` names what the frame is about.
  → the frame row, or nil when nothing was written: the walk is not
  recording, the type is not a frame type, or the recorder could not
  see the frame's `self`."
  [eng walk-id sight {:keys [type body]}]
  (let [type (some-> type name)
        body (scrub (or body {}))
        self (or (:self body) (get body "self"))]
    (when (and (some #{type} frame-types)
               (or (nil? self) (sees-self? eng sight self)))
      (let [st (:storage eng)
            ^Instant now ((:now-fn eng))
            id (str walk-id)]
        (store/with-tx st
          (fn [tx]
            (when-some [row (store/load-row st tx kind id {:for-update true})]
              (when (= "recording" (some-> (:state row) name))
                (let [d (:data row)
                      started (instant-of (:started_at d))
                      ms (if started
                           (max 0 (.toMillis (Duration/between started now)))
                           0)
                      frame (inv/insert-quiet! eng tx frame-kind
                                               {:walk id :t ms :type type :body body}
                                               {:principal engine-actor})]
                  (store/update-data! st tx kind id
                                      (update d :frame_count #(inc (long (or % 0))))
                                      (:next-flip-at row))
                  frame)))))))))

;; ── the purge and the sweep ─────────────────────────────────────────

(defn purge-frames!
  "Delete every frame of one walk. → how many."
  [eng walk-id]
  (let [st (:storage eng)]
    (loop [n 0]
      (let [ids (store/with-tx st
                  (fn [tx]
                    (store/ids-matching st tx frame-kind
                                        [{:target :data :field :walk
                                          :cast "text" :op :=
                                          :value (str walk-id)}]
                                        5000)))]
        (if (empty? ids)
          n
          (recur (+ n (long (store/with-tx st
                              (fn [tx] (store/delete-rows! st tx frame-kind ids)))))))))))

(defn after-purge!
  "THE WIRE-BOUNDARY EFFECT of a recorder's `purge`, called by the
  router after every committed invoke beside transcripts/after-purge!:
  the row moved to `purged`, and the frames go now, outside the
  transition's own transaction. Every other write passes through
  untouched, and a replay deletes nothing twice."
  [eng rdef action out]
  (when (and (= kind (:kind rdef))
             (= :purge action)
             (map? out)
             (:transition out)
             (nil? (:replayed? out)))
    (purge-frames! eng (:id (:row out))))
  out)

(defn- rows-in [eng state]
  (store/with-tx (:storage eng)
    (fn [tx] (store/query-rows (:storage eng) tx kind {:state state}
                               {:limit sweep-page}))))

(defn- past-retention? [d ^Instant now]
  (when-some [from (instant-of (or (:ended_at d) (:started_at d)))]
    (let [days (long (or (:retention_days d) default-retention-days))]
      (not (.isAfter (.plusSeconds from (* days 86400)) now)))))

(defn sweep!
  "One pass: every walk past its retention loses its frames and moves
  to `purged` under the engine's hand. → how many moved."
  [eng]
  (if-not (contains? (inv/resources eng) kind)
    0
    (let [^Instant now ((:now-fn eng))]
      (reduce
       (fn [n row]
         (if (past-retention? (:data row) now)
           (try
             (purge-frames! eng (:id row))
             (inv/invoke! eng kind (str (:id row)) :purge {}
                          {:principal engine-actor})
             (inc (long n))
             (catch Exception e
               (warn! "walk " (:id row) " could not be purged ("
                      (ex-message e) ")")
               n))
           n))
       0
       (concat (rows-in eng :recording) (rows-in eng :sealed))))))

(defn start-sweeper!
  "Every `:interval-ms`, `sweep!`. The first pass is one interval after
  the start. Returns the handle `stop-sweeper!` takes."
  [eng {:keys [interval-ms] :or {interval-ms 3600000}}]
  (let [stop (CountDownLatch. 1)
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (when-not (.await stop (long interval-ms)
                                         TimeUnit/MILLISECONDS)
                         (try (sweep! eng)
                              (catch Exception e
                                (warn! "the retention pass failed ("
                                       (ex-message e) ")")))
                         (recur))))
                   "waymark10-walk-retention")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop}))

(defn stop-sweeper! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)