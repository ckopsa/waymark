(ns waymark10.server.waiting-on
  "What ONE row waits on (ticket 86af9d88): the read-only `waiting_on`
  block of a row's envelope. The cracks audit says which STATES work can
  stop in; this says, for the row in hand, who moves it next.

  since     when the row entered its state and by which door, read from
            its log (the last write when the log does not say), how many
            seconds ago that is, and the state's expected age
            (`live-cracks/expected-age`) when it has one
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
  caller's grant hides."
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
  "Seconds as a person says them: 45 s, 12 min, 3 h, 2 d."
  [seconds]
  (let [s (long seconds)]
    (cond
      (< s 120) (str s " s")
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

(defn- verdict
  "The one line. A seat a model sits in comes first, a woken one before
  one on a cadence; then a source; then a person; then nobody."
  [kind state doors for-words age-words held]
  (let [hs (holders doors)
        models (remove :person hs)
        pick (or (first (filter (comp seq :woken_by) models))
                 (first (filter :cadence_seconds models))
                 (first models))
        person (first (filter :person hs))
        source (first (filter #(= "source" (:held_by %)) doors))]
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
       (str "a person: no seat a model sits in holds a door out of " state
            for-words "; " (:seat person) " holds " (some-of (:holds person)))

       :else
       (str "nobody: no active seat holds a door out of " state for-words))
     (when doors age-words)
     (when (seq held) (str "; held by " (str/join ", " (keys held)))))))

(defn block
  "The `waiting_on` block of one row. `seat-rows` is the ACTIVE seats,
  as stored; `by-kind` is every kind the engine serves, by name. `now`
  is the instant of the read, `entered` the transition that put the row
  into its state when the log said (`:at`, `:action`), and `data` the
  row's data as the caller is shown it."
  [rdef seat-rows by-kind row {:keys [now entered data]}]
  (let [kind (text (:kind rdef))
        state (text (:state row))
        doors (some->> (cracks/row-doors rdef seat-rows by-kind row) vec)
        ^Instant at (or (inst (:at entered)) (inst (:updated-at row)))
        ^Instant now (inst now)
        secs (when (and at now)
               (max 0 (.getSeconds (Duration/between at now))))
        expected (when doors (live-cracks/expected-age rdef state))
        named (for [f (cracks/walked-fields rdef seat-rows)
                    :let [v (fget data f)]
                    :when (set-value? v)]
                (str f " " (if (coll? v) (str/join ", " (map text v)) (text v))))
        for-words (when (seq named) (str " for " (str/join ", " named)))
        age-words (when secs
                    (str ", " (span secs)
                         (when expected (str " of " (span expected)))))
        held (held-by data)]
    (cond-> {:state state
             :since (cond-> {}
                      at (assoc :at (str at))
                      (:action entered) (assoc :by (text (:action entered)))
                      secs (assoc :seconds secs)
                      expected (assoc :expected_seconds expected))
             :next (or doors [])
             :verdict (verdict kind state doors for-words age-words held)}
      (seq held) (assoc :held_by held))))

(defn for-row
  "The block for a row the engine serves, or nil: for an engine that
  serves no seat kind, and for a read that failed. `data` is the row's
  data as the envelope projected it for the caller."
  [eng rdef row data]
  (try
    (let [st (:storage eng)
          resources (inv/resources eng)]
      (when (contains? resources :seat)
        (let [[seat-rows log]
              (store/with-tx st
                (fn [tx]
                  [(store/query-rows st tx :seat {:state :active}
                                     {:limit seat-page})
                   (store/transitions st tx
                                      {:kind (:kind rdef)
                                       :resource-id (str (:id row))}
                                      {:limit log-page :newest-first true})]))]
          (block rdef seat-rows resources row
                 {:now ((:now-fn eng))
                  :entered (entered log (:state row))
                  :data data}))))
    (catch Exception e
      (warn! (text (:kind rdef)) " " (:id row) ": " (ex-message e))
      nil)))
