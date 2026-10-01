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

  THE RECORDER IS THE FOLLOWER'S OWN STREAM. `recorder` answers the taps
  routes/realtime hangs on a stream opened with ?ui=<pid>: every `move`
  and `ui` frame of the followed principal that stream sends, and every
  firehose transition that principal made, goes to `record-frame!` for
  each walk the follower is recording of that principal.

  A SELF WALK NEEDS NO FOLLOWER. A walk whose `followed` is its
  `recorder` is a person recording their own screen. Nobody's stream
  carries those frames, so the doors that made them hand them over:
  the beat (`presence/report!`, tapped by `self-recorder`) and the
  write doors (`record-own!`). The sight is the request's own.

  THE ROW OUTLIVES ITS FRAMES. The purge deletes every frame and moves
  the walk to `purged`; the row keeps its title and its counts as the
  audit that a walk existed."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.presence :as presence]
            [waymark10.server.store :as store]
            [waymark10.summary :as summary]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Duration Instant ZoneOffset)
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

(def frame-ceiling
  "The most frames one walk takes (docs/spec-agent-demo-walks.md § 4).
  At this count the engine seals the walk with its own hand, so a walk
  nobody seals does not grow without bound."
  20000)

(def ceiling-actor
  "The hand that seals a walk at `frame-ceiling`: the engine's own actor,
  displayed so the walk's history says why it was sealed."
  (t/principal {:id "waymark10-walks" :type :system
                :display "Walks (the frame ceiling was reached)"}))

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
                          :help "The principal whose screen and writes this walk holds: their principal id. It is the recorder's own id when a person or an agent records itself."}}
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
                            :help "The principal who records this walk, stamped by the engine at birth: a follower recording someone else's stream, or a person or an agent recording its own. A frame holds only what this principal could see."}}
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
   ["§ 4 lists `export` as a door; it is the route GET /api/walks/{id}/export (router/walk-export) and not an action, because it answers newline-delimited JSON and not an envelope."
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

(defn- seal-at-ceiling!
  "Seal a walk that reached `frame-ceiling`, under the engine's hand
  (`the-recorder-or-the-sweep` admits the system actor)."
  [eng id]
  (try
    (inv/invoke! eng kind id :seal {} {:principal ceiling-actor})
    (catch Exception e
      (warn! "walk " id " could not be sealed at the frame ceiling ("
             (ex-message e) ")"))))

(defn record-frame!
  "Write one frame of a recording walk, as the recorder's stream
  received it. `sight` is the recorder's own visibility; `frame` is
  {:type :body}, and `(:self body)` names what the frame is about.
  → the frame row, or nil when nothing was written: the walk is not
  recording, the type is not a frame type, or the recorder could not
  see the frame's `self`. The frame that brings the walk to
  `frame-ceiling` is written, and the engine then seals the walk."
  [eng walk-id sight {:keys [type body]}]
  (let [type (some-> type name)
        body (scrub (or body {}))
        self (or (:self body) (get body "self"))]
    (when (and (some #{type} frame-types)
               (or (nil? self) (sees-self? eng sight self))
               ;; a transition is the firehose's event projected by the
               ;; recorder's visibility, the export's own rule
               (or (not= "transition" type)
                   (some? (events/visible-transition sight body))))
      (let [st (:storage eng)
            ^Instant now ((:now-fn eng))
            id (str walk-id)
            ceiling (long frame-ceiling)
            {:keys [frame full?]}
            (store/with-tx st
              (fn [tx]
                (when-some [row (store/load-row st tx kind id {:for-update true})]
                  (when (= "recording" (some-> (:state row) name))
                    (let [d (:data row)
                          had (long (or (:frame_count d) 0))]
                      (if (>= had ceiling)
                        ;; an earlier seal at the ceiling did not land
                        {:full? true}
                        (let [started (instant-of (:started_at d))
                              ms (if started
                                   (max 0 (.toMillis (Duration/between started now)))
                                   0)
                              frame (inv/insert-quiet! eng tx frame-kind
                                                       {:walk id :t ms :type type :body body}
                                                       {:principal engine-actor})]
                          (store/update-data! st tx kind id
                                              (assoc d :frame_count (inc had))
                                              (:next-flip-at row))
                          {:frame frame :full? (>= (inc had) ceiling)})))))))]
        ;; the seal is its own transition, after the frame's commit
        (when full? (seal-at-ceiling! eng id))
        frame))))

;; ── the recorder (a follower's own stream) ──────────────────────────

(def ^:private recorder-page
  "How many walks of one principal one follower records at once."
  20)

(defn- recording-walks
  "The ids of the walks `recorder` is recording of `followed` now."
  [eng recorder followed]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (into []
              (comp (filter #(and (= recorder (str (get-in % [:data :recorder])))
                                  (= followed (str (get-in % [:data :followed])))))
                    (map #(str (:id %))))
              (store/query-rows st tx kind
                                {:state :recording
                                 :recorder recorder
                                 :followed followed}
                                {:limit recorder-page}))))))

(defn- suggest-for
  "An invitation's suggested values as `vis` may read them: the keys its
  `:arg?` admits on the invited step, and none when the step names no
  served kind. nil `vis` reads them whole. nil when there are none."
  [eng vis {:keys [self action suggest]}]
  (when (and (map? suggest) (seq suggest))
    (if-some [arg? (:arg? vis)]
      (let [[_ plural] (re-find #"/api/([^/?#]+)/[^/?#]+" (str self))
            k (some->> plural (kind-of-plural eng))
            action (some-> action name str/trim not-empty keyword)]
        (if (and k action)
          (into {} (filter (fn [[arg _]] (arg? k action (name arg)))) suggest)
          {}))
      suggest)))

(defn- invitation-frame
  "The `invitation` frame of one firehose event, when that event is the
  birth of an invitation the follower can see; nil otherwise. The body
  is pinned: {id, author, subject, self, action, field, fields, note,
  suggest}; a row born before `fields` carries `field` alone,
  and `suggest` keeps only the keys the follower's `:arg?` admits."
  [eng sight t]
  (when (and (= "invitation" (some-> (:kind t) name))
             (nil? (:from-state t))
             (:resource-id t))
    (let [st (:storage eng)
          id (str (:resource-id t))
          rdef (get (inv/resources eng) :invitation)]
      (when (and rdef (or (nil? sight) ((:row? sight) :invitation id)))
        (when-some [row (store/with-tx st
                          (fn [tx] (store/load-row st tx :invitation id {})))]
          (let [d (:data (inv/decode-row rdef row))]
            {:type "invitation"
             :body (assoc (select-keys d [:author :subject :self :action
                                          :field :fields :note])
                          :id id
                          :suggest (suggest-for eng sight d))}))))))

(defn recorder
  "One stream's recorder (docs/spec-guided-follow.md § 4): the taps a
  stream serving `follower` hangs on its sources while it follows the
  principal id `followed`. `sight` is the follower's visibility, the
  one the stream redacts under. → {:presence f :event f}.

  `:presence` takes a presence frame as the stream sends it, after the
  follower's redaction, and records the followed principal's `move` and
  `ui`. `:event` takes a firehose event and records a `transition` when
  its actor is the followed principal, and an `invitation` beside it
  when that transition created one (`invitation-frame`).

  Each frame goes through `record-frame!` to every walk the follower
  is recording of `followed` at that moment. The walks are read per
  frame, so a walk started after the stream opened is recorded and a
  sealed or purged one takes nothing. Closing the stream seals no walk.
  A tap never throws: the stream outlives a write that failed."
  [eng follower sight followed]
  (let [fid (str (:id follower))
        pid (str followed)
        write! (fn [what frames]
                 (try
                   (when-some [ids (seq (recording-walks eng fid pid))]
                     (let [frames (frames)]
                       (doseq [id ids
                               frame frames]
                         (record-frame! eng id sight frame))))
                   (catch Exception e
                     (warn! "a " what " frame of " pid " was not recorded — "
                            (ex-message e)))))]
    {:presence
     (fn [frame]
       (let [event (:event frame)]
         (when (and (contains? #{"move" "ui"} event)
                    (= pid (str (get-in frame [:principal :id]))))
           (write! event (fn [] [{:type event :body frame}])))))
     :event
     (fn [t]
       (when (and (not= :derivation (::events/class t))
                  (= pid (str (get-in t [:actor :id]))))
         (write! "transition"
                 (fn []
                   (keep identity
                         [{:type "transition"
                           :body (walk/keywordize-keys
                                  (events/transition-payload eng t))}
                          (invitation-frame eng sight t)])))))}))

;; ── the self walk (a person's own screen, nobody following) ─────────

(defn self-recorder
  "A SELF walk's recorder: a walk whose `followed` is its `recorder` is
  a person recording their own screen, and no follower's stream carries
  its frames. This is `recorder` with the person as their own follower,
  so `sight` is the person's own visibility, the one the request that
  made the frame was judged under. → {:presence f :event f}, or nil on
  an engine that serves no walks.

  `:presence` is the tap `presence/report!` takes: it sees each `move`
  and `ui` frame the person's own beat made. A `ui` frame passes
  section 1's redaction under `sight` first, as a follower's stream
  would have redacted it. `:event` takes a transition the person
  committed (`record-own!`)."
  [eng principal sight]
  (when (contains? (inv/resources eng) kind)
    (let [pid (str (:id principal))
          rec (recorder eng principal sight pid)
          redact (presence/ui-redactor eng sight)]
      (assoc rec :presence
             (fn [frame]
               (try
                 (when-some [frame (if (= "ui" (:event frame))
                                     (redact frame)
                                     frame)]
                   ((:presence rec) frame))
                 (catch Exception e
                   (warn! "a frame of " pid "'s own walk was not recorded — "
                          (ex-message e)))))))))

(defn record-own!
  "A write door's post-commit pass (router/count-committed! at the
  create and action routes, router/bulk-action once per row it moved;
  the connector's invoke rides those same routes): the
  transition `principal` just committed goes to every self walk they
  are recording, under `sight`, the request's own visibility. A replay,
  a rehearsal and an anonymous write record nothing, and neither does a
  move of a walk or a frame: the recording's own doors are not the work
  it shows. Returns `result`, and never throws."
  [eng principal sight result]
  (try
    (let [t (:transition result)]
      (when (and t
                 (nil? (:replayed? result))
                 (not= (:id t/anonymous) (:id principal))
                 (not (contains? #{"walk" "walk_frame"}
                                 (some-> (:kind t) name))))
        (when-some [event (:event (self-recorder eng principal sight))]
          (event t))))
    (catch Exception e
      (warn! "a write was not recorded in its own walk — " (ex-message e))))
  result)

(defn recording-own?
  "Is `principal` recording a self walk now? The connector stages its
  calls while, and only while, this is true
  (docs/spec-agent-demo-walks.md § 2)."
  [eng principal]
  (let [pid (str (:id principal))]
    (and (contains? (inv/resources eng) kind)
         (boolean (seq (recording-walks eng pid pid))))))

;; ── the export (waymark-walk/1) ─────────────────────────────────────

(def export-format "waymark-walk/1")

(def unseen-display
  "What the cast calls a principal whose row the exporter cannot see."
  "someone")

(def never-exported
  "Body keys an export line drops at any depth, beside never-recorded:
  a principal's own map and a sitting's id. A principal crosses only
  as its cast alias."
  #{"principal" "actor" "pid" "sitting" "sitting_id" "sittingid"
    "allowed_by"})

(def ^:private export-page 1000)

(defn- path-of
  "A `self` as a path: a recorded origin is cut. nil for a blank."
  [self]
  (let [s (str/trim (str self))]
    (when-not (str/blank? s)
      (str/replace s #"^[A-Za-z][A-Za-z0-9+.\-]*://[^/?#]*" ""))))

(defn- clean
  "A line's part as the export carries it: never-recorded and
  never-exported keys dropped at any depth, a string that is a cast
  member's principal id replaced by its alias, and the origin cut from
  a string that addresses this API."
  [alias part]
  (walk/postwalk
   (fn [x]
     (cond
       (map? x) (into {} (remove (fn [[k _]]
                                   (and (or (keyword? k) (string? k))
                                        (let [n (str/lower-case (name k))]
                                          (or (contains? never-recorded n)
                                              (contains? never-exported n))))))
                      x)
       (string? x) (or (get alias x)
                       (str/replace x #"^[A-Za-z][A-Za-z0-9+.\-]*://[^/?#]*(?=/api/)" ""))
       :else x))
   part))

(defn- who-of
  "The principal a frame is by, read where its type carries it →
  [principal id, the actor type the frame recorded]."
  [type body]
  (let [p (case type
            ("move" "ui") (:principal body)
            "transition" (:actor body)
            "invitation" (:author body)
            nil)]
    (cond
      (map? p) [(some-> (:id p) str) (some-> (:type p) name)]
      (some? p) [(str p) nil])))

(defn- principal-row
  "The row a principal id names → [kind row], or nil: a `seat:` or
  `model:` id names that kind's row, any other a member by row id or
  by bound subject, the two spellings the members gate resolves."
  [eng pid]
  (let [st (:storage eng)
        pid (str pid)
        [_ prefix id] (re-matches #"(seat|model):(.+)" pid)
        k (if prefix (keyword prefix) :member)
        rdef (get (inv/resources eng) k)]
    (when rdef
      (when-some [row (or (store/with-tx st
                            (fn [tx] (store/load-row st tx k (or id pid) {})))
                          (when-not prefix
                            (first (store/with-tx st
                                     (fn [tx]
                                       (store/query-rows st tx k {:subject pid}
                                                         {:limit 1}))))))]
        [k (inv/decode-row rdef row)]))))

(defn- cast-member
  "One cast entry, {:display :type}. The display is the principal's row
  label read under the EXPORTER's visibility, and `unseen-display` when
  the exporter cannot see that row or no row carries the id. The type
  is agent for a seat, a model or an agent member, human for any other
  member, and the frame's recorded actor type when no row says."
  [eng vis pid recorded-type]
  (let [[k row] (principal-row eng pid)
        rdef (get (inv/resources eng) k)
        seen? (and row (or (nil? vis)
                           (boolean ((:row? vis) k (str (:id row))))))
        label (when seen?
                (summary/render (or (:label-template rdef) (:summary rdef))
                                (assoc row :kind k)))
        agent? (case k
                 (:seat :model) true
                 :member (= "agent" (some-> (get-in row [:data :actor_type]) name))
                 (contains? #{"agent" "system"} recorded-type))]
    {:display (if (str/blank? label) unseen-display label)
     :type (if agent? "agent" "human")}))

(defn- cast-of
  "The cast of the lines that crossed, in order of first appearance →
  {:alias {principal id → alias} :cast [[alias entry] …]}. Agents are
  a1, a2 …, people p1, p2 …. A frame left out names nobody."
  [eng vis parts]
  (reduce (fn [acc [pid recorded-type]]
            (if (or (str/blank? (str pid)) (contains? (:alias acc) pid))
              acc
              (let [entry (cast-member eng vis pid recorded-type)
                    counter (if (= "agent" (:type entry)) :agents :people)
                    n (inc (long (get acc counter)))
                    alias (str (if (= :agents counter) "a" "p") n)]
                (-> acc
                    (assoc counter n)
                    (assoc-in [:alias pid] alias)
                    (update :cast conj [alias entry])))))
          {:agents 0 :people 0 :alias {} :cast []}
          (mapcat (fn [p] [[(::who p) (::who-type p)] [(::subject p) nil]])
                  parts)))

(defn- export-part
  "One frame re-redacted under the exporter's visibility → the line's
  own part, or nil when nothing of it is left. Each type has one rule:
  `move` presence's self rule; `ui` that rule and presence/ui-redactor,
  and a ui frame with every part redacted crosses as a plain move;
  `transition` events/visible-transition; `invitation` :row? on the
  invitation its pinned body names by `id` (`invitation-frame`), and
  its `suggest` keeps the keys the exporter's :arg? admits."
  [{:keys [vis visible? redact-ui suggest]} type body]
  (let [self (path-of (:self body))]
    (case type
      "move" (when (and self (visible? self))
               {:type "move" :self self})
      "ui" (when (and self (visible? self))
             (let [f (redact-ui (assoc body :self self))]
               (if (and (map? (:ui f)) (not= "move" (:event f)))
                 {:type "ui" :self self :ui (:ui f)}
                 {:type "move" :self self})))
      "transition" (when-some [p (events/visible-transition
                                  vis (cond-> body self (assoc :self self)))]
                     (assoc (select-keys p [:kind :self :action :from :to
                                            :at :summary])
                            :type "transition"))
      "invitation" (let [id (:id body)]
                     (when (and id (or (nil? vis)
                                       ((:row? vis) :invitation (str id))))
                       (let [suggested (suggest (assoc body :self self))]
                         (cond-> (assoc (select-keys body [:action :field :fields
                                                           :note])
                                        :type "invitation"
                                        ::subject (some-> (:subject body) str))
                           self (assoc :self self)
                           (seq suggested) (assoc :suggest suggested)))))
      nil)))

(defn- export-line [alias part]
  (let [who (get alias (::who part))
        subject (get alias (::subject part))]
    (cond-> (merge (array-map :t (:t part) :type (:type part))
                   (when who {:who who})
                   (clean alias (dissoc part :t :type ::who ::who-type ::subject)))
      subject (assoc :subject subject))))

(defn export
  "A sealed walk as `waymark-walk/1` (docs/spec-guided-follow.md § 4):
  newline-delimited JSON, the header line and then one line per frame.
  `vis` is the EXPORTER's visibility (nil is an unscoped exporter), and
  every frame is redacted again under it by its type's rule
  (`export-part`), so the export holds what the recorder and the
  exporter could both see. A frame with nothing left is left out and
  the `t` of the others keeps the gap. A principal crosses only as a
  cast alias: no principal id, grant id, sitting id, header, key or
  origin is written. → the text, or nil when the walk is absent or
  not sealed."
  [eng walk-id vis]
  (let [st (:storage eng)
        id (str walk-id)
        row (store/with-tx st (fn [tx] (store/load-row st tx kind id {})))]
    (when (= "sealed" (some-> (:state row) name))
      (let [d (:data row)
            frdef (get (inv/resources eng) frame-kind)
            rules {:vis vis
                   :visible? (presence/self-visible? eng vis)
                   :redact-ui (presence/ui-redactor eng vis)
                   :suggest #(suggest-for eng vis %)}
            frames (->> (store/with-tx st
                          (fn [tx]
                            (vec (store/query-rows
                                  st tx frame-kind {:walk id}
                                  {:limit (max (long export-page)
                                               (long (or (:frame_count d) 0)))}))))
                        (map #(:data (inv/decode-row frdef %)))
                        (sort-by #(long (or (:t %) 0))))
            parts (vec (keep (fn [{:keys [t type body]}]
                               (let [type (some-> type name)
                                     body (walk/keywordize-keys (or body {}))
                                     [pid recorded-type] (who-of type body)]
                                 (when-some [part (export-part rules type body)]
                                   (assoc part :t t
                                          ::who pid
                                          ::who-type recorded-type))))
                             frames))
            {:keys [alias cast]} (cast-of eng vis parts)
            started (instant-of (:started_at d))
            header (array-map
                    :format export-format
                    :title (:title d)
                    :recorded (when started
                                (str (.toLocalDate (.atZone started ZoneOffset/UTC))))
                    :engine (or (:name eng) "waymark")
                    :cast (apply array-map (mapcat identity cast)))]
        (apply str (map #(str (wire/write-json %) "\n")
                        (cons header (map #(export-line alias %) parts))))))))

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