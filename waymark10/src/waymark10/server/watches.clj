(ns waymark10.server.watches
  "The watch (docs/spec-watch.md, ticket 14cbd2f4): what a member wants
  shown, as a row. A watch is for one member or seat (`for`), names its
  `cues` in the shape a seat's inbox cues have — kind, actions, filter,
  note — and reads in one of two modes. No kind decides whether it is
  shown to a person; watches do.

  HOLD. A held watch is read, not delivered: `held-read`, which
  GET /api/-/watches/held and waymark_held answer, is every row that
  matches a cue of the member's held watches NOW — a row of the cue's
  kind in a state the cue's `states` names, or, with no `states`, a row
  of the kind whose waiting_on names the member (the in-flight
  holder=me read). A cue of kind `*` is every kind.

  STREAM. The mode exists and the schema takes it; nothing delivers it
  yet. A seat's inline `inbox` is a stream-mode watch in all but
  storage, and stays where it is.

  DISMISS. `dismiss` marks one row on one watch with the state it is in;
  the held read answers a marked row under `dismissed`, apart, while it
  stands in that state. Once the row moves the mark is inert at once,
  and the next write of the watch drops it. A `required` watch refuses
  dismiss and undismiss.

  THE DEFAULT. Every member gets one held watch, \"Waiting on you\",
  at boot and at their first sign-in (`seed!`, run by this namespace's
  consumer on each member's transition). It is a row: its member may
  restate it or drop it, and a dropped one is not seeded again."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.resource :refer [defresource defhandler]]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.in-flight :as in-flight]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.render :as render]
            [waymark10.server.seats.fields :as seat-fields]
            [waymark10.server.seats.inbox :as inbox]
            [waymark10.server.store :as store]
            [waymark10.summary :as summary]
            [waymark10.types :as t]))

(set! *warn-on-reflection* true)

(def kind :watch)

(def engine-actor
  "The system actor that seeds each member's default watch."
  (t/principal {:id "waymark10-watches" :type :system
                :display "Watches"}))

(def any-kind
  "The cue kind that names every kind."
  "*")

(def default-name "Waiting on you")

(def default-cues
  "The default watch's cues: what waits on the member, the held calls,
  approval requests, questions and pinned quest first, then every other
  row whose waiting_on names them, so it answers the rows the in-flight
  holder=me read does."
  [{:kind "quest" :actions [] :filter {:pinned "true"}
    :note "Your pinned quest's next step waits on you."}
   {:kind "held_call" :actions []
    :note "A call an agent made waits on your Allow or Deny."}
   {:kind "approval_request" :actions []
    :note "An ask for access waits on your answer."}
   {:kind "question" :actions []
    :note "A question waits on your answer."}
   {:kind "connection" :actions [] :states ["dark"]
    :note "The connection {data.tag} is dark: its source has stopped answering."}
   {:kind any-kind :actions []
    :note "This waits on your move."}])

(def seeded-before-connections
  "The default cues as they were seeded before the dark connection cue
  (ticket 80216c42): a seeded watch whose cues are still exactly these
  gains it at boot."
  (filterv #(not= "connection" (:kind %)) default-cues))

(defn- text [v] (if (keyword? v) (name v) (str v)))

(defn- presence-field
  "The field a cue filter's `<field>_set` key asks about — the
  collection grammar's presence spelling — or nil for an equality key."
  [f]
  (when (and (str/ends-with? f "_set") (< 4 (count f)))
    (subs f 0 (- (count f) 4))))

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10.watches: " parts))))

;; ── guards ──────────────────────────────────────────────────────────

(defn- engine? [ctx]
  (= :system (:type (:principal ctx))))

(defn- mayor?
  "Whether the caller sits the seat named mayor."
  [ctx]
  (boolean
   (when-some [sid (members/seat-id (:id (:principal ctx)))]
     (when-some [find' (:find ctx)]
       (some #(= sid (str (:id %))) (find' :seat {:name "mayor"} {:limit 10}))))))

(defn- writes-for? [for' ctx]
  (or (engine? ctx)
      (= (str for') (str (:id (:principal ctx))))
      (mayor? ctx)))

(g/defguard the-watcher-or-the-mayor-names-it
  {:judges [:for]
   :reads [:principal :storage]
   :open "The wall is about who: name yourself as `for`, or leave it out. The mayor seat writes watches for any member."
   :explain "A member creates watches for themself; the mayor seat creates them for any member."}
  [_row inp ctx]
  (if (writes-for? (or (some-> (:for inp) str not-empty)
                       (str (:id (:principal ctx))))
                   ctx)
    (t/allow)
    (t/deny)))

(g/defguard the-watcher-or-the-mayor-moves-it
  {:reads [:principal :storage]
   :open "The wall is about who: the member a watch is for moves it, and the mayor seat moves any member's."
   :explain "Only the member this watch is for, or the mayor seat, restates, drops, dismisses or undismisses it."}
  [row _inp ctx]
  (if (writes-for? (get-in row [:data :for]) ctx) (t/allow) (t/deny)))

(g/defguard a-required-watch-shows-everything
  {:open "No door clears this one: a required watch shows every row it matches. Restate its cues to change what it matches."
   :explain "This watch is required: none of its rows is dismissed."}
  [row _inp _ctx]
  (if (true? (get-in row [:data :required])) (t/deny) (t/allow)))

(g/defguard every-cue-filter-reads
  {:judges [:cues]
   :vars [:key]
   :open "The wall is about spelling: a cue filter key is `state`, `id`, a field name with the value it equals, or `<field>_set` with true or false."
   :explain "The cue filter key {key} asks whether a field is set, and takes only true or false."}
  [_row inp _ctx]
  (if-some [bad (some (fn [cue]
                        (some (fn [[f v]]
                                (let [f (text f)]
                                  (when (and (presence-field f)
                                             (not (contains? #{"true" "false"} (text v))))
                                    f)))
                              (:filter cue)))
                      (:cues inp))]
    (t/deny {:vars {:key bad}})
    (t/allow)))

;; ── handlers ────────────────────────────────────────────────────────

(defn- born
  "The birth stamps: `for` is the creator unless named, the mode is
  hold unless named, and a watch the engine seeds says so."
  [row ctx]
  (let [d (:data row)
        pid (str (get-in ctx [:principal :id]))]
    (update row :data assoc
            :for (or (some-> (:for d) str not-empty) pid)
            :mode (or (some-> (:mode d) text not-empty) "hold")
            :required (true? (:required d))
            :seeded (= (str (:id engine-actor)) pid)
            :dismissed [])))

(defn- live-marks
  "The marks whose row still stands in the state it was dismissed in."
  [marks ctx]
  (if-some [read' (:read ctx)]
    (filterv (fn [{:keys [row_id row_kind state]}]
               (and (some? state)
                    (= (str state)
                       (some-> (read' (keyword (str row_kind)) (str row_id))
                               :state text))))
             marks)
    (vec marks)))

(defn- without [marks row-id]
  (remove #(= (str row-id) (str (:row_id %))) marks))

(defhandler restate-it [row inp _ctx]
  (update row :data merge (select-keys inp [:cues :name])))

(defhandler dismiss-it [row inp ctx]
  (let [rk (str (:row_kind inp))
        rid (str (:row_id inp))
        target (some-> (:read ctx) (apply [(keyword rk) rid]))
        marks (live-marks (without (get-in row [:data :dismissed]) rid) ctx)]
    (assoc-in row [:data :dismissed]
              (conj marks {:row_id rid :row_kind rk
                           :state (some-> target :state text)}))))

(defhandler undismiss-it [row inp ctx]
  (assoc-in row [:data :dismissed]
            (live-marks (without (get-in row [:data :dismissed]) (:row_id inp)) ctx)))

;; ── the declaration ─────────────────────────────────────────────────

(def cue-schema
  "One cue: a seat inbox cue (`seats.fields/inbox-cue-schema`), the
  states whose rows it holds, the note a hosted engine answers, and the
  card's heading in words. A card's note may say its steps, one to a
  line, so it is longer than a seat's one line (ticket 96ea43ed)."
  (conj (mapv #(if (and (vector? %) (= :note (first %)))
                 (-> (assoc % 2 [:string {:min 1 :max 600}])
                     (assoc-in [1 :x-display :widget] "prose"))
                 %)
              seat-fields/inbox-cue-schema)
        [:states {:optional true
                  :x-display {:label "In these states"
                              :help "Hold every row of the kind in one of these states. Leave it out and the cue holds the rows of the kind that wait on the member this watch is for."}}
         [:maybe [:vector [:string {:min 1 :max 64}]]]]
        [:note_hosted {:optional true
                       :x-display {:widget "prose" :label "Note on a hosted engine"
                                   :help "What the held read says in place of the note on an engine with sign-in configured. Leave it out and the note is said everywhere."}}
         [:maybe [:string {:min 1 :max 600}]]]
        [:title {:optional true
                 :x-display {:label "Heading"
                             :help "The step this card is, in words, said in place of the row's summary line. Leave it out and the card is headed by its row."}}
         [:maybe [:string {:min 1 :max 80}]]]))

(def ^:private mark-schema
  [:map
   [:row_id {:not-a-ref "It names a row of the kind beside it, any kind; the held read matches it, and a row that is gone is simply not shown."}
    [:string {:min 1 :max 128}]]
   [:row_kind [:string {:min 1 :max 64}]]
   [:state [:maybe [:string {:max 64}]]]])

(def ^:private for-props
  {:x-ref {:principal true}
   :x-display {:raw true
               :label "For"
               :help "The member or seat this watch is for. Leave it out to make it your own."}})

(def ^:private name-entry
  [:name {:x-display {:label "Name"
                      :help "What this watch is called where it is shown."}}
   [:string {:min 1 :max 80}]])

(def ^:private cues-entry
  [:cues {:x-display {:label "Cues"
                      :help "What this watch holds: each cue a kind, and the states or the wait that make a row of it show."}}
   [:vector {:min 1 :max 20} cue-schema]])

(def ^:private mode-entry
  [:mode {:optional true
          :default "hold"
          :x-display {:label "Mode"
                      :choices {"hold" "Hold — the rows it matches are read when you look"
                                "stream" "Stream — each event is delivered as it happens (not delivered yet)"}}}
   [:enum "hold" "stream"]])

(def ^:private required-entry
  [:required {:optional true
              :default false
              :x-display {:label "Required"
                          :help "True and none of its rows may be dismissed."}}
   :boolean])

(def ^:private row-input
  [:map
   [:row_id {:not-a-ref "It names a row of the kind beside it, any kind; the held read matches it."
             :x-display {:label "Row" :help "The id of the row."}}
    [:string {:min 1 :max 128}]]
   [:row_kind {:x-options {:from :kinds}
               :x-display {:label "Its kind" :help "The kind of the row."}}
    [:string {:min 1 :max 64}]]])

(defresource watch
  {:kind :watch
   :plural "watches"
   :purpose {:what "What one member wants shown: the cues that make a row show, read when they look."
             :for :person
             :for-note "Made by a member for themself, or by the mayor for any member, and read by that member when they look."
             :when "Use it to say which rows a person sees on their page, and in what words."
             :not [{:kind :quest
                    :why "A quest is one goal a person set out to reach; a watch is what they want to see."}]}
   :nav :secondary
   :states [:active :dropped]
   :initial :active
   :terminal #{:dropped}
   :summary "{data.name} · {data.mode} · {state}"
   :label-template "{data.name}"
   :schema [:map
            [:for for-props [:string {:min 1 :max 128}]]
            name-entry
            cues-entry
            mode-entry
            required-entry
            [:seeded {:optional true
                      :x-display {:label "Seeded"
                                  :help "True on the watch the engine gave this member."}}
             [:maybe :boolean]]
            [:dismissed {:optional true
                         :x-display {:label "Dismissed"
                                     :help "The rows set aside, each with the state it was in; one shows again when it moves."}}
             [:maybe [:vector mark-schema]]]]
   :create-schema [:map
                   [:for (assoc for-props :optional true) [:maybe [:string {:min 1 :max 128}]]]
                   name-entry
                   cues-entry
                   mode-entry
                   required-entry]
   :filterable {:state #{:eq :in}
                :for #{:eq}
                :mode #{:eq}}
   :default-filters {:state "active"}
   :own-surface {:by :for
                 :actions #{:create :restate :drop :dismiss :undismiss}}
   :create-guards [the-watcher-or-the-mayor-names-it every-cue-filter-reads]
   :on-create born
   :actions
   {:restate
    {:from #{:active} :to :active
     :input [:map
             (assoc-in name-entry [1 :optional] true)
             (assoc-in cues-entry [1 :optional] true)]
     :edit {:prefill [:name :cues]}
     :record true
     :guards [the-watcher-or-the-mayor-moves-it every-cue-filter-reads]
     :handler restate-it
     :safety {:idempotent true :reversible false :confirm false
              :one-way "The cues before are replaced; the log keeps what was written."}
     :display {:label "Restate" :order 1
               :description "Rename this watch or say again what it holds"}}
    :dismiss
    {:from #{:active} :to :active
     :input row-input
     :guards [the-watcher-or-the-mayor-moves-it a-required-watch-shows-everything]
     :handler dismiss-it
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Dismiss" :order 2
               :description "Set one row aside until it moves"}}
    :undismiss
    {:from #{:active} :to :active
     :input row-input
     :guards [the-watcher-or-the-mayor-moves-it a-required-watch-shows-everything]
     :handler undismiss-it
     :safety {:idempotent true :reversible true :confirm false}
     :display {:label "Undismiss" :order 3
               :description "Show a set-aside row again"}}
    :drop
    {:from #{:active} :to :dropped
     :guards [the-watcher-or-the-mayor-moves-it]
     :safety {:idempotent true :reversible false :confirm false
              :final "The watch stops holding rows; making it again is a new watch."}
     :display {:label "Drop" :style :danger :order 4
               :description "Stop showing what this watch holds"}}}})

;; ── the held read ───────────────────────────────────────────────────

(defn- watches-of
  "The active held watches for any of `ids`: the required ones first,
  then the member's others, the seeded one last."
  [eng ids]
  (when-some [rdef (get (inv/resources eng) kind)]
    (let [st (:storage eng)
          raws (store/with-tx st
                 (fn [tx]
                   (vec (mapcat #(store/query-rows st tx kind {:for %} {:limit 200})
                                (distinct ids)))))]
      (->> raws
           (map #(inv/decode-row rdef %))
           (filter #(and (= "active" (text (:state %)))
                         (= "hold" (some-> (get-in % [:data :mode]) text))))
           (reduce (fn [acc w] (if (some #(= (:id %) (:id w)) acc) acc (conj acc w))) [])
           (sort-by (juxt #(cond (true? (get-in % [:data :required])) 0
                                 (true? (get-in % [:data :seeded])) 2
                                 :else 1)
                          (comp str :id)))))))

(defn- row-of [eng rs k id]
  (when-some [rdef (get rs k)]
    (let [st (:storage eng)]
      (some->> (store/with-tx st (fn [tx] (store/load-row st tx k (str id) {})))
               (inv/decode-row rdef)))))

(defn- admits? [vis k id]
  (or (nil? vis)
      (and (boolean (some-> (:kind? vis) (apply [k])))
           (or (boolean (some-> (:whole-kind? vis) (apply [k])))
               (boolean (some-> (:row? vis) (apply [k (str id)])))))))

(defn- value-of [row f]
  (case f
    "state" (:state row)
    "id" (:id row)
    (get-in row [:data (keyword f)])))

(defn- sees-field? [vis k f]
  (or (contains? #{"state" "id"} f)
      (if-some [field? (:field? vis)] (boolean (field? k f)) true)))

(defn- hashed? [vis k f]
  (boolean (some-> (:hashed? vis) (apply [k f]))))

(defn- fits?
  "Whether the row matches the cue's filter, field by field: `id` is the
  row's own, `<field>_set` true or false asks whether the field holds a
  value (nil, missing and \"\" do not), any other key is equality and a
  nil field equals nothing. A field the grant does not admit fits
  nothing, so the read tells nobody what it holds; nor does equality
  on a field the grant shows hashed, so a guess is never confirmed."
  [vis k cue row]
  (every? (fn [[f v]]
            (let [f (text f)
                  p (presence-field f)
                  field (or p f)
                  have (value-of row field)]
              (and (sees-field? vis k field)
                   (if p
                     (= (= "true" (text v)) (not (or (nil? have) (= "" have))))
                     (and (not (hashed? vis k field))
                          (some? have) (= (text v) (text have)))))))
          (:filter cue)))

(defn- door-of
  "The first door of waiting_on.next a person or member takes, else the
  first door."
  [w]
  (let [nx (:next w)]
    (some-> (or (first (filter #(contains? #{"person" "member"} (text (:held_by %))) nx))
                (first nx))
            :door
            text)))

(defn- rank [cue k]
  (if (= any-kind (str (:kind cue)))
    3
    (case k "quest" 0 "held_call" 1 "approval_request" 2 3)))

(defn engine-url
  "This engine's public MCP URL: the MCP door's own address as its
  protected-resource document names it (`oidc/protected-resource`, off
  the :oidc config's :app-url). nil when the deployment named none."
  [eng]
  (some-> (:oidc eng) oidc/protected-resource :resource))

(def ^:private hole #"\{([A-Za-z0-9_.]+)\}")

(def ^:private row-hole #"(?:id|kind|state|version|data\..+)")

(defn- place-command
  "The mayor's place command (docs/spec-first-run.md R-5.6) for a seat
  row on a solo engine, as `seats/mayor-card` composes it; nil for any
  other row, and on an engine with no solo owner."
  [eng rdef row]
  (when (= "seat" (text (:kind rdef)))
    (some-> (:solo-owner eng) str not-empty (inbox/place-command (:id row)))))

(defn- fill-note
  "A stated note as the held read answers it: {engine_url} is `url`,
  {place_command} is `place`, the row's holes fill as
  `seats.inbox/cue-note` fills them, and any other {...} is left as
  written. An engine hole with no value is left out: the spaces and
  empty lines it leaves close up, a space before punctuation drops, and
  a colon left with nothing after it on its line drops."
  [template row {:keys [url place]}]
  (let [given {"engine_url" url "place_command" place}
        s (str/replace template hole
                       (fn [[m path]]
                         (cond (contains? given path) (or (get given path) "")
                               (re-matches row-hole path) (summary/render m row)
                               :else m)))]
    (if (every? (fn [[k v]] (or v (not (str/includes? template (str "{" k "}"))))) given)
      s
      (-> s
          (str/replace #" {2,}" " ")
          (str/replace #"\n{2,}" "\n")
          (str/replace #" +([.,;:!?])" "$1")
          str/trim
          (str/replace #"(?m):(?=[.,;!?]|$)" "")
          str/trim))))

(defn- note-of
  "The cue's note for `row`: `note_hosted` on an engine with :oidc
  sign-in when the cue has one, else `note`, filled (`fill-note`); a
  cue with neither says inbox's default sentence."
  [eng rdef cue row]
  (let [hosted (when (some? (:oidc eng)) (some-> (:note_hosted cue) str not-empty))]
    (if-some [n (or hosted (some-> (:note cue) str not-empty))]
      (fill-note n row {:url (engine-url eng) :place (place-command eng rdef row)})
      (inbox/cue-note cue (first (:actions cue)) row))))

(defn- item [eng rdef row entry watch cue vis]
  (let [k (text (:kind rdef))]
    {:id (str (:id row))
     :kind k
     :state (text (:state row))
     :summary (or (some-> (:title cue) str not-empty)
                  (:summary entry)
                  (render/target-summary rdef row vis))
     :note (note-of eng rdef cue row)
     ;; a row held by state waits on nobody, so its button is the
     ;; cue's first named action: the one door the step is
     :door (or (some-> (:waiting_on entry) door-of)
               (when (seq (:states cue)) (some-> (first (:actions cue)) text not-empty)))
     :since (some-> (get-in entry [:waiting_on :since :at]) str)
     :watch {:id (str (:id watch)) :name (get-in watch [:data :name])}
     :rank (rank cue k)}))

(defn- matches
  "Every row one cue of one watch holds, as items."
  [eng rs vis watch cue entries]
  (let [ck (str (:kind cue))
        states (seq (:states cue))]
    (if states
      (when-some [rdef (get rs (keyword ck))]
        (let [st (:storage eng)
              k (keyword ck)
              raws (store/with-tx st
                     (fn [tx]
                       (vec (mapcat #(store/query-rows st tx k {:state (keyword (str %))} {:limit 200})
                                    states))))
              by-id (into {} (map (juxt :id identity)) entries)]
          (for [raw raws
                :let [row (inv/decode-row rdef raw)]
                :when (and (admits? vis k (:id row)) (fits? vis k cue row))]
            (item eng rdef row (get by-id (str (:id row))) watch cue vis))))
      (for [e entries
            :when (or (= any-kind ck) (= ck (:kind e)))
            :let [k (keyword (:kind e))
                  rdef (get rs k)
                  row (row-of eng rs k (:id e))]
            :when (and rdef row (fits? vis k cue row))]
        (item eng rdef row e watch cue vis)))))

(defn held-read
  "The held read (GET /api/-/watches/held, waymark_held) for one
  principal: every row a cue of their held watches matches now, in
  watch order, then cue order, then inside one cue the pinned quest
  head, held calls and approval requests first and then by
  waiting_on.since, with the dismissed rows apart. `vis` nil is a
  person unscoped, as in-flight's read takes it."
  [eng vis principal]
  (let [pid (str (:id principal))
        token (in-flight/viewer-holder eng pid)
        me (subs token (count "member:"))
        watches (vec (watches-of eng [me pid]))
        rs (inv/resources eng)
        entries (when (seq watches)
                  (into [] (mapcat :rows)
                        (:groups (in-flight/read-all eng vis {:holder token}))))
        found (reduce (fn [acc it]
                        (let [key [(:kind it) (:id it)]]
                          (if (contains? (:seen acc) key)
                            acc
                            (-> acc (update :seen conj key) (update :items conj it)))))
                      {:seen #{} :items []}
                      (for [[wi w] (map-indexed vector watches)
                            [ci cue] (map-indexed vector (get-in w [:data :cues]))
                            it (matches eng rs vis w cue entries)]
                        (assoc it :rank [wi ci (:rank it)])))
        marks (into #{}
                    (comp (mapcat #(get-in % [:data :dismissed]))
                          (map (fn [m] [(str (:row_kind m)) (str (:row_id m)) (some-> (:state m) str)])))
                    watches)
        dismissed? (fn [it] (contains? marks [(:kind it) (:id it) (:state it)]))
        order (fn [it] [(:rank it) (or (:since it) "~") (:kind it) (:id it)])
        shown (->> (:items found) (remove dismissed?) (sort-by order) (mapv #(dissoc % :rank)))
        hidden (->> (:items found) (filter dismissed?) (sort-by order) (mapv #(dissoc % :rank)))]
    {:waymark "held"
     :for me
     :watches (mapv (fn [w] {:id (str (:id w))
                             :name (get-in w [:data :name])
                             :required (true? (get-in w [:data :required]))
                             :seeded (true? (get-in w [:data :seeded]))})
                    watches)
     :total (count shown)
     :rows shown
     :dismissed {:count (count hidden) :rows hidden}
     :at (str ((:now-fn eng)))}))

;; ── the default watch ───────────────────────────────────────────────

(defn- person? [m]
  (and (not (members/seat-id (:id m)))
       (not= "agent" (some-> (get-in m [:data :type]) text))))

(defn- cue-words [cues]
  (mapv (juxt (comp text :kind) (comp str :note)) cues))

(defn- upgrade!
  "Give a member's seeded watch the cues the default gained since it was
  seeded (the dark connection cue, ticket 80216c42), only while its cues
  are still exactly the ones it was seeded with: a watch its member
  restated is left alone."
  [eng rdef raws]
  (doseq [raw raws
          :let [w (inv/decode-row rdef raw)]
          :when (and (true? (get-in w [:data :seeded]))
                     (= "active" (text (:state w)))
                     (= (cue-words seeded-before-connections)
                        (cue-words (get-in w [:data :cues]))))]
    (try (inv/invoke! eng kind (str (:id w)) :restate {:cues default-cues}
                      {:principal engine-actor
                       :if-match (some-> (or (:version w) (:version raw)) str)})
         (catch Exception e
           (warn! "watch " (:id w) " kept its cues — " (ex-message e))))))

(defn seed!
  "Give the member their default watch, unless one was ever seeded for
  them (a dropped one included); a seeded one still as it was seeded
  gains the cues the default gained since. Answers the row it made, or
  nil."
  [eng member-id]
  (let [rs (inv/resources eng)
        rdef (get rs kind)
        st (:storage eng)
        mid (str member-id)]
    (when (and rdef (not (str/blank? mid)) (not (members/seat-id mid)))
      (let [have (store/with-tx st (fn [tx] (store/query-rows st tx kind {:for mid} {:limit 200})))]
        (if (some #(true? (get-in (inv/decode-row rdef %) [:data :seeded])) have)
          (do (upgrade! eng rdef have) nil)
          (:row (inv/create! eng kind {:for mid :name default-name :mode "hold"
                                       :cues default-cues}
                             {:principal engine-actor})))))))

(defn seed-all!
  "Seed every active member's default watch; answers how many were made."
  [eng]
  (let [rs (inv/resources eng)
        mdef (get rs :member)
        st (:storage eng)]
    (if (and mdef (get rs kind))
      (let [ms (store/with-tx st (fn [tx] (store/query-rows st tx :member {:state :active} {:limit 1000})))]
        (reduce (fn [n raw]
                  (let [m (inv/decode-row mdef raw)]
                    (if (and (person? m)
                             (try (seed! eng (:id m))
                                  (catch Exception e
                                    (warn! "member " (:id m) " got no default watch — " (ex-message e))
                                    nil)))
                      (inc n)
                      n)))
                0 ms))
      0)))

(def consumer-name
  "The durable cursor's name in waymark10_cursors (consumer:watches)."
  :watches)

(defn handle-transition!
  "One committed transition: a member's, that leaves them active, seeds
  their default watch — their first sign-in makes their row. Never
  throws."
  [eng t]
  (try
    (when (= "member" (some-> (:kind t) text))
      (let [rs (inv/resources eng)]
        (when-some [m (row-of eng rs :member (:resource-id t))]
          (when (and (= "active" (text (:state m))) (person? m))
            (seed! eng (:id m))))))
    (catch Exception e
      (warn! "transition " (:id t) " could not be handled — " (ex-message e))))
  nil)

(defn start!
  "Seed the members there are, then register the consumer that seeds
  each one who comes. opts: :dispatcher, :poll-ms, :from-origin?."
  ([eng] (start! eng {}))
  ([eng opts]
   (try (seed-all! eng)
        (catch Exception e
          (warn! "the default watches could not be seeded — " (ex-message e))))
   (consumers/register-consumer!
    eng consumer-name #(handle-transition! eng %)
    (select-keys opts [:dispatcher :poll-ms :from-origin?]))))

(defn stop! [consumer]
  (some-> consumer consumers/stop-consumer!))
