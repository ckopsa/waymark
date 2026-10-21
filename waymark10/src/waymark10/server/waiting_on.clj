(ns waymark10.server.waiting-on
  "What ONE row waits on (ticket 86af9d88): the read-only `waiting_on`
  block of a row's envelope. The cracks audit says which STATES work can
  stop in; this says, for the row in hand, who moves it next.

  since     when the row entered its state and by which door, read from
            its log (the last write when the log does not say), and the
            state's expected age
            (`live-cracks/expected-age`) when it has one — or, where
            that age is a map, its `due` (`live-cracks/deadline` over
            the :calendar hook) and no nominal seconds
  next      every way out of the state (`cracks/row-doors`): the door,
            the active seats that hold it for THIS row, what wakes each
            of them, and `held_by`: seat, person, source or nobody
  held_by   the row's own hold fields that are set (`hold-fields`)
  verdict   one line for a person

  It is computed from the kind's law and the seat rows and names no
  kind. `block` is pure over (declaration, seat rows, row), so a test
  judges it with no engine. `for-row` reads the seats and the row's log
  and never throws: a row is served without the block before it is not
  served.

  The hold fields and the values the verdict names are read from the
  data the caller was SHOWN, so the block says nothing of a field the
  caller's grant hides.

  The block is OUTSIDE what the envelope's ETag covers (ticket
  9ff24b82): the ETag is the row's version, and the verdict's age words
  and the seats `next` names move without a write. `since` serves the
  instant and the expected age, never an age, so it holds still under
  one version; the reader subtracts."
  (:require [clojure.string :as str]
            [waymark10.server.cracks :as cracks]
            [waymark10.server.invoke :as inv]
            [waymark10.server.live-cracks :as live-cracks]
            [waymark10.server.store :as store])
  (:import (java.time Duration Instant OffsetDateTime)
           (java.util Date)))

(set! *warn-on-reflection* true)

(def hold-fields
  "The fields that say a row is held, by NAME: a kind that carries one
  of them set says why its row does not move."
  ["blocked_by" "merge_waits" "held_reason" "line_why" "shelved_because"])

(def ^:private person-holds
  "The hold fields whose value `person` says a row is kept for a
  person's tap, by NAME, each with the field that says why (ticket
  6644eeeb): a change whose touched paths its repository keeps for a
  person carries line_why person and its line_reason."
  {"line_why" "line_reason"})

(def ^:private seat-page
  "The most active seats one read takes."
  500)

(def ^:private log-page
  "The most transitions, newest first, read to find when the row entered
  its state. A row that has taken more self-loops than this since says
  its last write."
  50)

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 waiting on: " parts))))

(defn- text [v] (if (keyword? v) (name v) (str v)))

(defn- seat-name [seat]
  (or (some-> (get-in seat [:data :name]) str not-empty) (str (:id seat))))

(defn- fget
  "A map's value for a key, whichever way the map spells the key."
  [m k]
  (when (map? m)
    (let [k (keyword (text k))]
      (if (contains? m k) (get m k) (get m (name k))))))

(defn- inst
  "An instant, however the row spells it. Unparsable is nil."
  ^Instant [v]
  (cond
    (instance? Instant v) v
    (instance? Date v) (.toInstant ^Date v)
    (instance? OffsetDateTime v) (.toInstant ^OffsetDateTime v)
    (some-> v str not-empty) (try (Instant/parse (str v))
                                  (catch Exception _ nil))
    :else nil))

(defn- span
  "Seconds as a person says them: under 2 min, 12 min, 3 h, 2 d. No
  finer: two reads of one row a moment apart answer the same words."
  [seconds]
  (let [s (long seconds)]
    (cond
      (< s 120) "under 2 min"
      (< s 7200) (str (quot s 60) " min")
      (< s 172800) (str (quot s 3600) " h")
      :else (str (quot s 86400) " d"))))

(defn- set-value?
  "Does a hold field say something? Not nil, false, blank or empty."
  [v]
  (cond
    (nil? v) false
    (false? v) false
    (string? v) (not (str/blank? v))
    (coll? v) (boolean (seq v))
    :else true))

(defn- held-by
  "The hold fields this data carries set, as {field value}."
  [data]
  (into (sorted-map)
        (for [f hold-fields
              :let [v (fget data f)]
              :when (set-value? v)]
          [f v])))

(defn- kept-for
  "Why this data says its row is kept for a person's tap, or nil."
  [data]
  (some (fn [[f why]]
          (when (= "person" (text (fget data f)))
            (let [r (fget data why)]
              (when (set-value? r) (text r)))))
        person-holds))

(defn- entered
  "The transition that put the row into `state`, from its log newest
  first: the first one that arrived there from another state, with only
  self-loops after it. nil when the log read does not reach it."
  [log state]
  (let [s (text state)]
    (some (fn [t]
            (when (not= s (some-> (:from-state t) text)) t))
          (take-while #(= s (some-> (:to-state %) text)) log))))

(defn- some-of
  "The first three names, and how many more."
  [names]
  (let [[shown more] (split-at 3 names)]
    (str (str/join ", " shown)
         (when (seq more) (str " and " (count more) " more")))))

(defn- holders
  "The seats the doors name, each one time in the order met, with every
  door it holds."
  [doors]
  (let [pairs (for [d doors, s (:seats d)] [(:door d) s])
        holds (reduce (fn [acc [door s]]
                        (update acc (:seat s) (fnil conj []) door))
                      {} pairs)]
    (for [n (distinct (map (comp :seat second) pairs))]
      (assoc (some (fn [[_ s]] (when (= n (:seat s)) s)) pairs)
             :holds (get holds n)))))

(defn- choose
  "Who holds the row, as the verdict names it (ticket ca68c559): a seat
  a model sits in first, a woken one before one on a cadence; then a
  source; then a person; then nobody. Not the first door of `next`:
  many of those are held by nobody while a later one is held.

  A seat counts only when its walk would hand it this row and a wake or
  its cadence brings it to the row (ticket af41e6bf): `walking` is the
  names of the seats whose walk admits the row, or that name no walk to
  narrow them. `:strays` says a seat a
  model sits in holds a door out and none of them walks the row."
  [doors walking]
  (let [hs (holders doors)
        models (remove :person hs)
        walkers (filter #(and (contains? walking (:seat %))
                              (or (seq (:woken_by %)) (:cadence_seconds %)))
                        models)]
    {:pick (or (first (filter (comp seq :woken_by) walkers))
               (first walkers))
     :strays (boolean (and (seq models) (empty? walkers)))
     :person (first (filter :person hs))
     :source (first (filter #(= "source" (:held_by %)) doors))}))

(defn- holder-of
  "The choice as the block carries it: {type name}, type one of seat,
  source, person or nobody; nil for a state a row rests in."
  [doors walking]
  (when doors
    (let [{:keys [pick person source]} (choose doors walking)]
      (cond
        pick {:type "seat" :name (:seat pick)}
        source {:type "source" :name (:source source)}
        person {:type "person" :name (:seat person)}
        :else {:type "nobody"}))))

(defn- verdict
  "The one line, naming the holder `choose` picks."
  [kind state doors walking for-words age-words held kept]
  (let [{:keys [pick person source strays]} (choose doors walking)
        holds (if strays "walks this row to" "holds")]
    (str
     (cond
       (nil? doors)
       (str "nothing: " state " is where a " kind " row rests")

       pick
       (str "waiting on " (:seat pick) " (holds " (some-of (:holds pick)) ", "
            (cond
              (seq (:woken_by pick)) (str "woken by " (str/join ", " (:woken_by pick)))
              (:cadence_seconds pick) (str "on its " (span (:cadence_seconds pick))
                                           " cadence")
              :else "nothing wakes it")
            ")")

       source
       (str "waiting on the " (:source source) " (" (:door source) ")")

       person
       (str "a person: no seat a model sits in " holds " a door out of " state
            for-words "; " (:seat person) " holds " (some-of (:holds person)))

       :else
       (str "nobody: no active seat " holds " a door out of " state for-words))
     (when doors age-words)
     (when (seq held) (str "; held by " (str/join ", " (keys held))))
     (when kept (str "; waits on a person's tap: " kept)))))

(defn- walk-led?
  "Does this seat name a walk or a judgment that says which rows it is
  handed?"
  [seat]
  (boolean (or (some-> (get-in seat [:data :walk]) text not-empty)
               (some-> (get-in seat [:data :judgment]) str not-empty))))

(defn block
  "The `waiting_on` block of one row. `seat-rows` is the ACTIVE seats,
  as stored; `by-kind` is every kind the engine serves, by name. `now`
  is the instant of the read, `entered` the transition that put the row
  into its state when the log said (`:at`, `:action`), `data` the
  row's data as the caller is shown it, and `tickets` and `judgments`
  what `walk-reads` read for it, and `calendar` the :calendar hook a
  map age reads."
  [rdef seat-rows by-kind row {:keys [now entered data tickets judgments calendar]}]
  (let [kind (text (:kind rdef))
        state (text (:state row))
        doors (some->> (cracks/row-doors rdef seat-rows by-kind row) vec)
        ^Instant at (or (inst (:at entered)) (inst (:updated-at row)))
        ^Instant now (inst now)
        secs (when (and at now)
               (max 0 (.getSeconds (Duration/between at now))))
        dated (map? (first (live-cracks/age-entry rdef state)))
        expected (when (and doors (not dated)) (live-cracks/expected-age rdef state))
        ^Instant due (when (and doors dated at now)
                       (live-cracks/deadline (first (live-cracks/age-entry rdef state))
                                             row at now calendar))
        named (for [f (cracks/walked-fields rdef seat-rows)
                    :let [v (fget data f)]
                    :when (set-value? v)]
                (str f " " (if (coll? v) (str/join ", " (map text v)) (text v))))
        for-words (when (seq named) (str " for " (str/join ", " named)))
        age-words (when secs
                    (str ", " (span secs)
                         (cond
                           due (str (if (.isBefore due now) ", past its deadline " ", due ")
                                    due)
                           expected (str " of " (span expected)))))
        held (held-by data)
        kept (kept-for data)
        reads {:tickets tickets :judgments judgments}
        ;; a seat that names no walk and no judgment is narrowed by
        ;; neither, so its cadence alone may bring it to any row
        walking (into #{}
                      (comp (filter #(or (not (walk-led? %))
                                         (live-cracks/walk-admits? by-kind reads % rdef row)))
                            (map seat-name))
                      seat-rows)
        holder (holder-of doors walking)]
    (cond-> {:state state
             :since (cond-> {}
                      at (assoc :at (str at))
                      (:action entered) (assoc :by (text (:action entered)))
                      expected (assoc :expected_seconds expected)
                      due (assoc :due (str due)))
             :next (or doors [])
             :verdict (verdict kind state doors walking for-words age-words held kept)}
      holder (assoc :holder holder)
      (seq held) (assoc :held_by held)
      kept (assoc :note (str "waits on a person's tap: " kept)))))

(defn- born-ticket-id
  "The id of the ticket a row was born from (`born_from` ticket:<id>),
  or nil."
  [row]
  (let [born (str (fget (:data row) :born_from))]
    (when (str/starts-with? born "ticket:")
      (subs born (count "ticket:")))))

(defn walk-reads
  "What `block` reads, in `tx`, to tell whose walk hands it a row:
  {:tickets id → row} for the tickets these rows were born from, and
  {:judgments id → row} for the judgments these seats sit for."
  [eng tx seat-rows rows]
  (let [st (:storage eng)
        resources (inv/resources eng)
        load (fn [kind ids]
               (if-some [rdef (get resources kind)]
                 (into {}
                       (keep (fn [id]
                               (when-some [r (store/load-row st tx kind id {})]
                                 [id (inv/decode-row rdef r)])))
                       (distinct ids))
                 {}))]
    {:tickets (load :ticket (keep born-ticket-id rows))
     :judgments (load :judgment
                      (keep #(some-> (get-in % [:data :judgment]) str not-empty)
                            seat-rows))}))

(defn for-row
  "The block for a row the engine serves, or nil for a read that
  failed. The seat kind is core's and always enrolled, so every engine
  serves one. `data` is the row's data as the envelope projected it
  for the caller."
  [eng rdef row data]
  (try
    (let [st (:storage eng)
          resources (inv/resources eng)
          [seat-rows log reads]
          (store/with-tx st
            (fn [tx]
              (let [seat-rows (store/query-rows st tx :seat {:state :active}
                                                {:limit seat-page})]
                [seat-rows
                 (store/transitions st tx
                                    {:kind (:kind rdef)
                                     :resource-id (str (:id row))}
                                    {:limit log-page :newest-first true})
                 (walk-reads eng tx seat-rows [row])])))]
      (block rdef seat-rows resources row
             (assoc reads
                    :now ((:now-fn eng))
                    :entered (entered log (:state row))
                    :data data
                    :calendar (:calendar (inv/render-hooks eng)))))
    (catch Exception e
      (warn! (text (:kind rdef)) " " (:id row) ": " (ex-message e))
      nil)))
