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
  write doors (`record-own!`, and `record-refused!` for a write a door
  refused). The sight is the request's own.

  THE ROW OUTLIVES ITS FRAMES. The purge deletes every frame and moves
  the walk to `purged`; the row keeps its title and its counts as the
  audit that a walk existed.

  A WALK MAY CARRY ITS SCREENS (docs/spec-agent-demo-walks.md § 8a). A
  walk created with `docs` follows each `move`, `ui` and `transition`
  it took with a `doc` frame: the document of that screen as the
  recorder's own read answers it then (`record-doc!`). Replay draws
  the product's screen from it, and the export redacts it again under
  the exporter (`export-doc`)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.schema :as schema]
            [waymark10.server.collections :as collections]
            [waymark10.server.events :as events]
            [waymark10.server.invitations :as invitations]
            [waymark10.server.invoke :as inv]
            [waymark10.server.presence :as presence]
            [waymark10.server.problems :as p]
            [waymark10.server.render :as render]
            [waymark10.server.store :as store]
            [waymark10.summary :as summary]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.net URLDecoder)
           (java.security MessageDigest)
           (java.time Duration Instant ZoneOffset)
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

(def frame-types ["move" "ui" "transition" "invitation" "caption" "doc" "refusal"])

(def caption-max
  "A caption is one line of at most this many characters
  (docs/spec-agent-demo-walks.md § 3)."
  140)

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
  retention gets the default. The clock is the recording's: on a demo
  engine whose own clock is shifted (clock-shift), `started_at` is real
  time, because each frame's `t` counts real time from it."
  [row ctx]
  (-> row
      (assoc-in [:data :recorder] (str (get-in ctx [:principal :id])))
      (assoc-in [:data :started_at] (or (some-> (get-in ctx [:services :recording-clock])
                                                (apply []))
                                        (:now ctx)
                                        (Instant/now)))
      (assoc-in [:data :frame_count] 0)
      (update-in [:data :docs] boolean)
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
     [:int {:min 1 :max 3650}]]
    [:docs {:optional true
            :x-display {:label "Carries its screens"
                        :help "Whether each move and each write is followed by the document of the screen it shows, so a replay draws the product's own screens. It is chosen at the start and does not change."}}
     :boolean]]
   ;; the recorder, the clock and the count are the engine's to write
   :create-schema
   [:map
    followed-field
    title-field
    [:retention_days {:optional true
                      :x-display {:label "Kept for (days)"
                                  :help "Left empty, the frames are kept 30 days after the walk ends."}}
     [:maybe [:int {:min 1 :max 3650}]]]
    [:docs {:optional true
            :x-display {:label "Carry the screens"
                        :help "Checked, each move and each write is followed by the document of the row or the collection it shows, as the recorder could read it, and a replay draws the real screens. Left empty, the walk records no documents."}}
     [:maybe :boolean]]]
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
                        :help "move, ui, transition or invitation: what the recorder's stream carried. caption: the line its recorder said about a step. refusal: the answer a write door refused its recorder with. doc: the document of the screen the frame before it shows."}}
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
            ;; recording time, not engine time: a demo engine's shifted
            ;; clock (clock-shift) must not reorder the frames
            ^Instant now ((or (get-in eng [:services :recording-clock])
                              (:now-fn eng)))
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

;; the screens a walk carries (docs/spec-agent-demo-walks.md § 8a)

(def doc-cap
  "A document over this many bytes is not recorded."
  (* 64 1024))

(def docs-cap
  "A walk's documents stop at this many bytes in total."
  (* 8 1024 1024))

(defn- sha-of ^String [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md (.getBytes s "UTF-8"))))))

(defn- body-of
  "A stored body's value under `k`, however the store spelled the key."
  [body k]
  (or (get body k) (get body (name k))))

(defn- params-of
  "A self's query string as the collection grammar's params map."
  [self]
  (let [[_ q] (str/split (str self) #"\?" 2)]
    (into {}
          (keep (fn [kv]
                  (let [[k v] (str/split kv #"=" 2)]
                    (when-not (str/blank? k)
                      [(URLDecoder/decode ^String k "UTF-8")
                       (URLDecoder/decode ^String (or v "") "UTF-8")]))))
          (some-> q (str/split #"&")))))

(defn- screen-of
  "The screen a frame leaves its recorder looking at → [self params],
  or nil when it shows none: a `move`'s self, a `ui` frame's collection
  under its query, a `transition`'s row. `self` is a path, and `params`
  is the collection's query."
  [{:keys [type body]}]
  (let [path (fn [self]
               (some-> self str (str/split #"[?#]" 2) first not-empty))]
    (case (some-> type name)
      "move" (when-some [self (path (:self body))]
               [self (params-of (:self body))])
      "ui" (let [c (get-in body [:ui :collection])]
             (when-some [self (path (:self c))]
               [self (cond-> (into {}
                                   (keep (fn [[k v]]
                                           (when (some? v) [(name k) (str v)])))
                                   (:filter c))
                       (:sort c) (assoc "sort" (str (:sort c)))
                       (:page c) (assoc "page[number]" (str (:page c))))]))
      "transition" (when-some [self (path (:self body))]
                     [self nil])
      nil)))

(defn ref-summaries
  "render's :ref-summary hook under `sight`: a referenced row's
  {:href :summary} as the recorder may read it, nil when it may not.
  The connector's staging labels a typed ref argument with it."
  [eng sight]
  (let [st (:storage eng)]
    (fn [k id]
      (let [id (str id)]
        (when-some [trdef (get (inv/resources eng) k)]
          (when (or (nil? sight) ((:row? sight) k id))
            (when-some [raw (store/with-tx st
                              (fn [tx] (store/load-row st tx k id {})))]
              {:href (str "/api/" (:plural trdef) "/" id)
               :summary (render/target-summary
                         trdef (inv/decode-row trdef raw) sight)})))))))

(defn- doc-of
  "The document of `self` as `principal`'s own read under `sight`
  answers it now: a row's envelope, or a collection's page under
  `params`. nil when `self` names no served kind or no standing row."
  [eng principal sight self params]
  (let [[_ plural id] (re-matches #"/api/([^/?#]+)(?:/([^/?#]+))?" (str self))
        k (when (and plural (not= "-" plural)) (kind-of-plural eng plural))
        rdef (get (inv/resources eng) k)
        st (:storage eng)
        ;; router/render-opts, with the recorder where the request stood
        opts (cond-> {:evidence-reads (inv/render-hooks eng)
                      :principal principal
                      :now ((:now-fn eng))
                      :services (:services eng)
                      :visibility sight
                      :resources (inv/resources eng)
                      :link-doors (:link-doors eng)}
               (:probe-reads eng) (merge (inv/render-hooks eng)))]
    (cond
      (nil? rdef) nil
      id (when-some [row (store/with-tx st
                           (fn [tx] (store/load-row st tx k id {})))]
           (render/envelope rdef (inv/decode-row rdef row)
                            (assoc opts :ref-summary (ref-summaries eng sight))))
      :else (collections/envelope eng rdef (or params {}) opts))))

(defn- doc-frames
  "The bodies of the `doc` frames a walk holds, in the order they were
  recorded."
  [eng walk-id frame-count]
  (let [st (:storage eng)
        frdef (get (inv/resources eng) frame-kind)]
    (->> (store/with-tx st
           (fn [tx]
             (vec (store/query-rows st tx frame-kind
                                    {:walk (str walk-id) :type "doc"}
                                    {:limit (max 1 (long (or frame-count 0)))}))))
         (map #(:data (inv/decode-row frdef %)))
         (filter #(= "doc" (some-> (:type %) name)))
         (map :body)
         (sort-by #(long (or (body-of % :n) 0))))))

(defn record-doc!
  "Follow a frame with the screen it shows: the document of `self` as
  `principal`'s own read under `sight` answers it now (`doc-of`),
  written as a `doc` frame of a recording walk made with `docs`. The
  body is {self, n, bytes, sha, doc}: `n` counts the walk's documents
  and `sha` is the document's own. → the frame row, or nil when
  nothing was written: the walk carries no screens, the recorder cannot
  see `self`, the document is over `doc-cap`, it equals the last one
  recorded for that `self`, or the walk's documents would pass
  `docs-cap`. Replay then draws that screen from the other frames."
  [eng walk-id principal sight self params]
  (let [st (:storage eng)
        id (str walk-id)
        row (store/with-tx st (fn [tx] (store/load-row st tx kind id {})))
        d (:data row)]
    (when (and (= "recording" (some-> (:state row) name))
               (true? (:docs d))
               (sees-self? eng sight self))
      (when-some [doc (some-> (doc-of eng principal sight self params) scrub)]
        (let [text (wire/write-json doc)
              size (alength (.getBytes text "UTF-8"))
              sha (sha-of text)
              held (doc-frames eng id (:frame_count d))
              prior (last (filter #(= self (body-of % :self)) held))
              total (reduce + 0 (map #(long (or (body-of % :bytes) 0)) held))]
          (when (and (<= size (long doc-cap))
                     (not= sha (body-of prior :sha))
                     (<= (+ size (long total)) (long docs-cap)))
            (record-frame! eng id sight
                           {:type "doc"
                            :body {:self self :n (count held) :bytes size
                                   :sha sha :doc doc}})))))))

(defn- follow-with-doc!
  "The frame a walk just took, followed by the screen it shows. Never
  throws: the frame is already kept, and a screen that was not recorded
  is one replay draws from the other frames."
  [eng walk-id principal sight frame]
  (try
    (when-some [[self params] (screen-of frame)]
      (record-doc! eng walk-id principal sight self params))
    (catch Exception e
      (warn! "a screen of the walk " walk-id " was not recorded — "
             (ex-message e)))))

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
  and `suggest` keeps only the keys the follower's `:arg?` admits. A
  walkthrough's step carries {walkthrough, step, of} beside them
  (docs/spec-walkthrough.md § 6)."
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
          (let [d (:data (inv/decode-row rdef row))
                led (cond-> (into {} (filter (comp some? val))
                                  (select-keys d [:step :of]))
                      (some? (:walkthrough d))
                      (assoc :walkthrough (str (:walkthrough d))))]
            {:type "invitation"
             :body (assoc (merge (select-keys d [:author :subject :self :action
                                                 :field :fields :note])
                                 led)
                          :id id
                          :suggest (suggest-for eng sight d))}))))))

(defn- invitation-birth? [t]
  (and (= "invitation" (some-> (:kind t) name))
       (nil? (:from-state t))
       (some? (:resource-id t))))

(defn- answers?
  "Whether `t`, the recorder's own transition, answers an invitation the
  followed principal `pid` wrote for the recorder `fid`: it takes the
  invited (self, action), or it declines the invitation itself. The
  stream and the invitations' consumer hear the log apart, so the
  invitation is either still `open` and no younger than `t`, or already
  `answered` with `t`'s own log id."
  [eng pid fid t]
  (let [st (:storage eng)
        rs (inv/resources eng)
        idef (get rs :invitation)
        rdef (get rs (some-> (:kind t) keyword))
        asked? (fn [row]
                 (and (= pid (str (get-in row [:data :author])))
                      (= fid (str (get-in row [:data :subject])))))
        rows (fn [f]
               (store/with-tx st
                 (fn [tx] (mapv #(inv/decode-row idef %) (f tx)))))]
    (boolean
     (when (and idef rdef (:resource-id t) (:action t))
       (let [action (name (:action t))]
         (if (= :invitation (:kind rdef))
           (and (= "decline" action)
                (some asked?
                      (rows (fn [tx]
                              (some-> (store/load-row st tx :invitation
                                                      (str (:resource-id t)) {})
                                      vector)))))
           (let [self (str "/api/" (:plural rdef) "/" (:resource-id t))
                 at (instant-of (:at t))]
             (some (fn [row]
                     (let [d (:data row)
                           born (instant-of (:created-at row))]
                       (and (asked? row)
                            (= self (str (:self d)))
                            (= action (str/trim (str (:action d))))
                            (case (some-> (:state row) name)
                              "answered" (= (str (:id t)) (str (:answered_by d)))
                              "open" (not (and at born (.isBefore at born)))
                              false))))
                   (rows (fn [tx]
                           (store/query-rows st tx :invitation
                                             {:subject fid :author pid}
                                             {:limit recorder-page
                                              :newest-first true})))))))))))

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

  A walkthrough's steps are made by the engine's hand and answered by
  the recorder's (docs/spec-walkthrough.md § 6), so `:event` also takes
  two events of other actors: the birth of an invitation whose `author`
  is the followed principal and whose `subject` is the follower, as an
  `invitation` frame, and the follower's own transition that answers
  such an invitation (`answers?`), as a `transition` frame.

  Each frame goes through `record-frame!` to every walk the follower
  is recording of `followed` at that moment. The walks are read per
  frame, so a walk started after the stream opened is recorded and a
  sealed or purged one takes nothing. Closing the stream seals no walk.
  A walk made with `docs` is handed the screen each frame it took shows
  (`follow-with-doc!`), as the follower's own read answers it.
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
                         (when (record-frame! eng id sight frame)
                           (follow-with-doc! eng id follower sight frame)))))
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
       (when (not= :derivation (::events/class t))
         (let [actor (str (get-in t [:actor :id]))
               transition (fn []
                            {:type "transition"
                             :body (walk/keywordize-keys
                                    (events/transition-payload eng t))})]
           (cond
             (= pid actor)
             (write! "transition"
                     (fn []
                       (keep identity
                             [(transition) (invitation-frame eng sight t)])))

             ;; keyed on the invitation's `author`, not on the hand that
             ;; made it: the engine opens a walkthrough's steps
             (invitation-birth? t)
             (write! "invitation"
                     (fn []
                       (when-some [f (invitation-frame eng sight t)]
                         (when (and (= pid (str (get-in f [:body :author])))
                                    (= fid (str (get-in f [:body :subject]))))
                           [f]))))

             ;; the recorder's own answer: without it the replay shows
             ;; the question and never what the row became
             (= fid actor)
             (write! "transition"
                     (fn []
                       (when (answers? eng pid fid t)
                         [(transition)])))))))}))

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

(defn- flat-errors
  "One argument's errors `v` → [[path [sentence]]], `path` spelled as the
  form names that field's widget: a map's child is `path.child` and a
  list's entry `path[i]`, the entries that passed (nil) left out."
  [path v]
  (cond
    (string? v) [[path [v]]]
    (map? v) (mapcat (fn [[k c]]
                       (flat-errors (if (= :malli/error k)
                                      path
                                      (str path "." (if (keyword? k) (name k) (str k))))
                                    c))
                     v)
    (sequential? v) (if (every? string? v)
                      (when (seq v) [[path (vec v)]])
                      (mapcat (fn [i c]
                                (when (some? c)
                                  (flat-errors (str path "[" i "]") c)))
                              (range) v))))

(defn- refusal-errors
  "A schema refusal's sentences by argument, as a `refusal` frame may
  hold them → {argument [sentence]}, or nil when there are none. A
  nested argument's are held by the dotted path the form's own slots
  use (`shelf.label`, `items[1].name`: `flat-errors`). A secret
  argument's entry is dropped, as presence's `clean-ui` drops its
  value, a nested argument's secret child with it, and so is every
  entry when `self` names no door `action`: the secret ones are then
  unknown."
  [eng self action errors]
  (when (map? errors)
    (when-some [secret (presence/secret-argument-paths eng self action)]
      (let [open? (fn [path]
                    (let [parts (str/split (str/replace path #"\[\d+\]" "") #"\.")]
                      (not-any? secret (reductions #(str %1 "." %2) parts))))]
        (not-empty
         (reduce (fn [m [path said]]
                   (if (open? path)
                     (update m path (fnil into []) said)
                     m))
                 {}
                 (mapcat (fn [[k v]]
                           (flat-errors (if (keyword? k) (name k) (str k)) v))
                         errors)))))))

(defn record-refused!
  "A write door's refusal (router's action route, which the connector's
  invoke rides; its create route, where `self` is the collection; and
  its bulk route, once for each row refused): the problem the exception
  `e` carries goes, as a
  `refusal` frame under `sight`, the request's own visibility, to every
  self walk `principal` is recording. The body is {principal, self,
  action, title, detail, remedies, errors}: what the refusal's box and
  the form's fields show a person, and no more of the problem.
  `remedies` are the door names as the wire spells them, and `errors`
  a schema refusal's sentences by argument, a secret argument's left
  out (`refusal-errors`). An exception that is no problem, an anonymous
  write and a refusal by a walk's or a frame's own door record nothing;
  a rehearsal's refusal is not handed here. `record-frame!` writes the
  frame only when the recorder can see `self`. → the frames written. It
  never throws."
  [eng principal sight {:keys [self action]} e]
  (try
    (let [pid (str (:id principal))
          d (ex-data e)
          self (str self)
          errors (refusal-errors eng self action (:errors d))]
      (if (and (:waymark10/problem d)
               (contains? (inv/resources eng) kind)
               (not= (:id t/anonymous) (:id principal))
               (not (re-find #"^/api/walk(s|_frames)(/|$)" self)))
        (let [body (cond-> {:principal {:id pid :type (some-> (:type principal) name)}
                            :self self
                            :action (name action)
                            :title (str (:title d))}
                     (some? (:detail d)) (assoc :detail (str (:detail d)))
                     (seq (:remedies d)) (assoc :remedies (p/wire-value (vec (:remedies d))))
                     errors (assoc :errors errors))]
          (into []
                (keep #(record-frame! eng % sight {:type "refusal" :body body}))
                (recording-walks eng pid pid)))
        []))
    (catch Exception e
      (warn! "a refusal was not recorded in its own walk — " (ex-message e))
      [])))

(defn recording-own?
  "Is `principal` recording a self walk now? The connector stages its
  calls while, and only while, this is true
  (docs/spec-agent-demo-walks.md § 2)."
  [eng principal]
  (let [pid (str (:id principal))]
    (and (contains? (inv/resources eng) kind)
         (boolean (seq (recording-walks eng pid pid))))))

(defn record-seen!
  "A screen of `principal`'s that another hand changed: the document of
  `self`, as `principal`'s own read under `sight` answers it now, goes
  to every self walk they are recording with `docs` (`record-doc!`).
  The quests' consumer calls it for a quest its owner is filming, since
  the engine's `plan` and `finish` pass no write door of the owner's
  (docs/spec-agent-demo-walks.md § 8a). → the frames written. It never
  throws."
  [eng principal sight self]
  (try
    (let [pid (str (:id principal))]
      (if (and (contains? (inv/resources eng) kind)
               (not= (:id t/anonymous) (:id principal)))
        (into []
              (keep #(record-doc! eng % principal sight (str self) nil))
              (recording-walks eng pid pid))
        []))
    (catch Exception e
      (warn! "a screen another hand changed was not recorded — " (ex-message e))
      [])))

(defn record-heard!
  "A move another hand made that `principal`'s screen answers to: the
  transition `t` goes, as a `transition` frame under `sight`, to every
  self walk they are recording. The quests' consumer calls it for a
  move on a row a quest's plan names, just before that quest's document
  (`record-seen!`), so a replay says whose move planned the quest again
  (docs/spec-agent-demo-walks.md § 8a). A row `sight` does not admit
  writes no frame (`record-frame!`). → the frames written. It never
  throws."
  [eng principal sight t]
  (try
    (let [pid (str (:id principal))]
      (if (and (contains? (inv/resources eng) kind)
               (not= (:id t/anonymous) (:id principal))
               (not= :derivation (::events/class t)))
        (let [frame {:type "transition"
                     :body (walk/keywordize-keys
                            (events/transition-payload
                             eng (update t :kind keyword)))}]
          (into []
                (keep #(record-frame! eng % sight frame))
                (recording-walks eng pid pid)))
        []))
    (catch Exception e
      (warn! "a move another hand made was not recorded — " (ex-message e))
      [])))

;; ── captions (docs/spec-agent-demo-walks.md § 3) ────────────────────

(defn caption-problem
  "Why the caption `c`, {:self :action :field :text}, cannot be shown;
  nil when it can. The text is one line of at most `caption-max`
  characters, and the empty one clears. `field` names one argument of
  `action` on `self`'s kind, and the invitation's own rule judges it
  (`invitations/fields-problem`): the action has that argument, and the
  argument is not secret."
  [eng {:keys [self action field text]}]
  (cond
    (not (string? text))
    "`caption` is one line of text."

    (re-find #"[\r\n]" text)
    "`caption` is one line: it holds no line break."

    (> (count text) (long caption-max))
    (str "`caption` is at most " caption-max " characters, and this one has "
         (count text) ".")

    (nil? field) nil

    (str/blank? (str (some-> action name)))
    "`caption_field` names an argument of the invoked action, and this call invokes none."

    :else
    (let [[_ plural] (re-find #"^/api/([^/?#]+)" (str self))
          resources (inv/resources eng)]
      (some->> (invitations/fields-problem
                ;; the rule reads the door off a row's path, and which row
                ;; it is does not matter to it: a create has none yet
                {:self (str "/api/" plural "/-") :action (name action) :field field}
                {:rdef-of (fn [p]
                            (some (fn [[_ rdef]] (when (= p (:plural rdef)) rdef))
                                  resources))})
               (str "`caption_field`: ")))))

(defn caption!
  "One `caption` frame in every self walk `principal` is recording,
  under `sight`, the request's own visibility. The body is {principal,
  self, action, field, text}, with `action` and `field` only when the
  caption names them. `record-frame!` writes it only when the recorder
  can see `self`, and an empty `text` is the frame that clears the
  line. → the frames written. It never throws."
  [eng principal sight {:keys [self action field text]}]
  (try
    (let [pid (str (:id principal))
          body (cond-> {:principal {:id pid :type (some-> (:type principal) name)}
                        :self (str self)}
                 (some? action) (assoc :action (name action))
                 (some? field) (assoc :field (if (keyword? field) (name field) (str field)))
                 true (assoc :text (str text)))]
      (if (and (contains? (inv/resources eng) kind)
               (not= (:id t/anonymous) (:id principal)))
        (into []
              (keep #(record-frame! eng % sight {:type "caption" :body body}))
              (recording-walks eng pid pid))
        []))
    (catch Exception e
      (warn! "a caption was not recorded — " (ex-message e))
      [])))

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
            ("move" "ui" "caption" "refusal") (:principal body)
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
          (mapcat (fn [p]
                    (concat [[(::who p) (::who-type p)] [(::subject p) nil]]
                            ;; the principals a `doc` line's rows name
                            (map (fn [pid] [pid nil]) (::refs p))))
                  parts)))

(defn- doc-rdef
  "The kind a recorded document is of: a row's, or a collection's own."
  [eng doc]
  (get (inv/resources eng)
       (some-> (:kind doc) str (str/replace #"_collection$" "") keyword)))

(defn- principal-fields
  "The data fields of a kind that hold a principal id."
  [rdef]
  (into #{}
        (keep (fn [entry]
                (when (and (vector? entry) (map? (second entry))
                           (get-in (second entry) [:x-ref :principal]))
                  (first entry))))
        (rest (:schema rdef))))

(defn- doc-principals
  "The principal ids a document names in a principal-ref field of a
  row, at any depth: the row itself, a collection's items, a link's
  embedded rows. The cast gives each an alias, and `clean` writes it."
  [eng doc]
  (->> (tree-seq coll? seq doc)
       (filter #(and (map? %) (:kind %) (:self %)))
       (mapcat (fn [d]
                 (for [f (principal-fields (doc-rdef eng d))
                       part [:data :fields]
                       v (let [v (get-in d [part f])]
                           (if (sequential? v) v [v]))
                       :when (and (string? v) (not (str/blank? v)))]
                   v)))
       distinct))

(defn- doc-under
  "A recorded row document (a row's envelope, a collection's item or a
  link's embedded row) as `vis` may read it, or nil when `vis` does not
  see that row. `data`, `fields` and `refs` keep the keys :field?
  admits, and `actions` and `unavailable` the entries :action? admits.
  A summary, a display and a parts group may spell a field out, so they
  cross only when every field of the kind does."
  [eng vis doc]
  (let [rdef (doc-rdef eng doc)
        k (:kind rdef)
        [_ id] (re-matches #"/api/[^/?#]+/([^/?#]+).*" (str (:self doc)))
        field? (or (:field? vis) (constantly true))
        action? (or (:action? vis) (constantly true))
        only (fn [d part ok?]
               (if (map? (get d part))
                 (update d part
                         (fn [m]
                           (into {}
                                 (filter (fn [[n _]] (ok? k (keyword (name n)))))
                                 m)))
                 d))]
    (when (and rdef id ((:row? vis) k id))
      (cond-> (-> doc
                  (only :data field?)
                  (only :fields field?)
                  (only :refs field?)
                  (only :actions action?)
                  (only :unavailable action?))
        (not-every? #(field? k %) (schema/entry-keys (:schema rdef)))
        (dissoc :summary :display :parts)
        (map? (:links doc))
        (update :links
                (fn [links]
                  (into {}
                        (map (fn [[rel link]]
                               [rel (if (sequential? (:embedded link))
                                      (update link :embedded
                                              #(vec (keep (fn [item]
                                                            (doc-under eng vis item))
                                                          %)))
                                      link)]))
                        links)))))))

(defn- export-doc
  "A `doc` frame's document under the exporter's visibility
  (docs/spec-agent-demo-walks.md § 8a), or nil when nothing of it is
  left. A row's envelope crosses by `doc-under`. A collection's page
  keeps the items `doc-under` keeps and the collection doors :action?
  admits, beside `query`. nil `vis` reads it whole."
  [eng vis doc]
  (cond
    (nil? vis) doc
    (str/ends-with? (str (:kind doc)) "_collection")
    (let [k (:kind (doc-rdef eng doc))
          action? (or (:action? vis) (constantly true))]
      (cond-> doc
        (sequential? (get-in doc [:data :items]))
        (update-in [:data :items]
                   #(vec (keep (fn [item] (doc-under eng vis item)) %)))
        (map? (:actions doc))
        (update :actions
                (fn [m]
                  (into {}
                        (filter (fn [[n _]]
                                  (or (= "query" (name n))
                                      (action? k (keyword (name n))))))
                        m)))))
    :else (doc-under eng vis doc)))

(defn- remedy-seen?
  "Does the exporter's visibility admit the door a refusal's remedy names
  (`kind.action`, the wire's spelling)? An unscoped exporter sees each."
  [vis remedy]
  (or (nil? vis)
      (let [[_ k a] (re-matches #"([^./]+)[./]([^./]+)" (str remedy))
            action? (:action? vis)]
        (boolean (and k a action? (action? (keyword k) (keyword a)))))))

(defn- errors-seen
  "A refusal's field errors as `vis` may read them: the entries whose
  argument its `:arg?` admits on the refused door, and none when the
  door names no served kind. A nested argument's entry (`shelf.label`,
  `items[1].name`) is judged by its top-level argument. nil `vis` reads
  them whole. nil when there are none."
  [eng vis {:keys [self action errors]}]
  (when (and (map? errors) (seq errors))
    (not-empty
     (if-some [arg? (:arg? vis)]
       (let [[_ plural] (re-find #"/api/([^/?#]+)" (str self))
             k (some->> plural (kind-of-plural eng))
             action (some-> action name str/trim not-empty keyword)]
         (if (and k action)
           (into {}
                 (filter (fn [[arg _]]
                           (arg? k action (or (re-find #"^[^.\[]+" (name arg))
                                              (name arg)))))
                 errors)
           {}))
       errors))))

(defn- export-part
  "One frame re-redacted under the exporter's visibility → the line's
  own part, or nil when nothing of it is left. Each type has one rule:
  `move` presence's self rule; `ui` that rule and presence/ui-redactor,
  and a ui frame with every part redacted crosses as a plain move;
  `transition` events/visible-transition; `invitation` :row? on the
  invitation its pinned body names by `id` (`invitation-frame`), and
  its `suggest` keeps the keys the exporter's :arg? admits; `caption`
  presence's self rule, so the line crosses only with its `self`;
  `refusal` that rule as well, of its `remedies` the doors the
  exporter's :action? admits (`remedy-seen?`), and of its `errors` the
  arguments the exporter's :arg? admits (`errors-seen`); `doc`
  presence's self rule and `export-doc`, and the principals its rows
  name ride as ::refs for the cast."
  [{:keys [eng vis visible? redact-ui suggest]} type body]
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
                                                           :note :step :of])
                                        :type "invitation"
                                        ::subject (some-> (:subject body) str))
                           self (assoc :self self)
                           (seq suggested) (assoc :suggest suggested)))))
      "caption" (when (and self (visible? self))
                  (cond-> {:type "caption" :self self}
                    (:action body) (assoc :action (:action body))
                    (:field body) (assoc :field (:field body))
                    true (assoc :text (str (:text body)))))
      "refusal" (when (and self (visible? self) (:action body))
                  (let [seen (filterv #(remedy-seen? vis %) (:remedies body))
                        errors (errors-seen eng vis body)]
                    (cond-> {:type "refusal" :self self
                             :action (:action body)
                             :title (str (:title body))}
                      (:detail body) (assoc :detail (str (:detail body)))
                      (seq seen) (assoc :remedies seen)
                      errors (assoc :errors errors))))
      "doc" (when (and self (visible? self) (map? (:doc body)))
              (when-some [doc (export-doc eng vis (:doc body))]
                {:type "doc" :self self :doc doc
                 ::refs (doc-principals eng doc)}))
      nil)))

(defn- export-line [alias part]
  (let [who (get alias (::who part))
        subject (get alias (::subject part))]
    (cond-> (merge (array-map :t (:t part) :type (:type part))
                   (when who {:who who})
                   (clean alias (dissoc part :t :type ::who ::who-type ::subject
                                        ::refs)))
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
            rules {:eng eng
                   :vis vis
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