(ns waymark10.server.wakes
  "The wake consumer (spec-seat.md R-12.22): the third way a sitting
  begins.

  The first two are the cadence, which the schedule's copy at the
  provider fires, and a person's own `fire`. This is the third — a
  transition the seat ASKED to be woken by. The seat says which ones
  in `wake_on`, a list of scope-shaped entries; a committed
  transition that matches one opens the seat's own `fire` door, with
  the transition as the text, and the run walks that row (R-12.21).

  ── why this is a consumer and not a subscription ──────────────────

  R-12.22 says the engine already has the machine: the subscription
  kind, a cursor per subscription, at-least-once delivery, and a fail
  or skip policy. It also says the receiver of a `wake_on` entry is
  not a URL — it is the seat's `fire` door. That second sentence is
  the one that decides this file. A subscription row's whole surface
  is an endpoint, a secret and a delivery log; a wake delivers
  nothing over the wire and has nothing to sign. What it needs from
  that machine is the cursor and the at-least-once discipline, which
  `server/consumers` is exactly — one durable cursor, one function of
  one transition, a replay tolerated. So the seat's wake rides a
  named consumer (`:wakes`) beside the schedules mirror's, and no
  subscription row is minted for a receiver that is a door.

  ── the two kinds of entry ─────────────────────────────────────────

  R-12.24 gives a `wake_on` entry a second reading. An entry with no
  `at_least` is the TRANSITION wake above: the row that moved wakes
  the seat, and the text names it. The entry's `filter` is read on
  both — on this one it names which MOVED rows wake the seat
  (`moved-under?`), so a seat that watches one change's runs is not
  woken by every other change's. An entry WITH one is a COUNT wake:
  the seat is not woken by a row, it is woken by a queue reaching a
  size. It does not poll — the count is read only when a transition
  of that kind matches the entry's actions, which is the one moment
  the number can have changed — and the count itself is the
  collection's own (`count-under`), so the number in the text is the
  number the list page would show under the same filter.

  ── the damper, and what it is for ─────────────────────────────────

  A wake is fuel. R-12.22 gives the damper three parts and this file
  keeps them in one place (`wake-seat!`):

    1. the seat has an OPEN SITTING — the session already awake will
       see the row when it walks its queue, and a second run would
       pay twice for one piece of work;
    2. the seat fired inside its own `fire_interval_seconds` — a busy
       queue must not turn every row into a sitting;
    3. either way the match is not lost: `wake_pending` goes onto the
       schedule row, and the first fire after the damper lifts names
       NO row, so the session walks the whole queue it missed.

  The flag is a MAINTENANCE write — `store/update-data!`, no version
  bump, no transition, the `stamp-seen!` pattern one file over. A
  transition per damped match would be a log of the engine deciding
  not to act, which is audit noise nobody reads.

  Two things lift the damper and both land in `release!`: the sitting
  that was open closes or is abandoned, and the tick thread, which
  asks the same question of every pending row on a clock (the gap is
  a duration, and nothing commits when a duration ends).

  ── the replay ─────────────────────────────────────────────────────

  At-least-once means the drain re-delivers, so every fire this file
  opens carries an idempotency key made of the transition it heard
  (`wake:<seat>:<transition>`). `fire` is declared not idempotent —
  truthfully, since a second fire is a second run — so the key is
  demanded anyway, and keying it by the transition makes the demand
  free and the replay silent: invoke answers the stored result and no
  second run starts. The POST itself is deduped a second time, where
  it happens: `schedules/already-fired?` compares the row's stamp
  against the fire transition's own instant.

  Nothing here re-throws. A throwing consumer parks its cursor, and a
  parked cursor stops every other seat in the house from being woken
  by anything."
  (:require [waymark10.server.collections :as collections]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.invoke :as inv]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.wire :as wire])
  (:import (java.time Instant)
           (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

;; ── the small tools ─────────────────────────────────────────────────

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 wakes: " parts))))

(defn- now ^Instant [eng] ((:now-fn eng)))

(defn- serves? [eng kind] (contains? (inv/resources eng) kind))

(defn- raw-row [eng kind id]
  (when (and id (serves? eng kind))
    (store/with-tx (:storage eng)
      (fn [tx] (store/load-row (:storage eng) tx kind (str id) {})))))

(defn- rows-where [eng kind where limit]
  (if (serves? eng kind)
    (store/with-tx (:storage eng)
      (fn [tx] (store/query-rows (:storage eng) tx kind where {:limit limit})))
    []))

(defn- instant-of
  "An instant, however the row spells it — a stored string or an
  Instant already. Unparsable is nil, which reads as \"never\"."
  [v]
  (cond
    (instance? Instant v) v
    (some-> v str not-empty) (try (Instant/parse (str v))
                                  (catch Exception _ nil))
    :else nil))

;; ── what wakes which seat ───────────────────────────────────────────

(def consumer-name
  "The durable cursor's name in waymark10_cursors (consumer:wakes)."
  :wakes)

(def default-fire-interval-seconds
  "R-12.22's own default, for a row written before the field existed."
  300)

(def ^:private own-kinds
  "The kinds a wake never matches. `seat` and `schedule` are the
  engine's own writing about the wake itself — a seat woken by its own
  `fire` would wake itself forever — `sitting` is the run a wake
  starts, and `subscription` is the other consumer's bookkeeping. A
  person who wants a seat woken by a seat has asked for a loop."
  #{:seat :sitting :schedule :subscription})

(def ^:private seat-page
  "The most active seats one match is judged against. A house past
  this has more offices than the spec's ladder describes, and the
  honest fix is a query per entry rather than a longer page."
  500)

(def ^:private pending-page
  "The most pending schedules one tick releases."
  200)

(defn- interval-of [seat-row]
  (long (or (get-in seat-row [:data :fire_interval_seconds])
            default-fire-interval-seconds)))

(defn- active-seats
  "Every active seat a wake can reach, as the three facts a match
  needs: its id, what wakes it (`effective-wake-on`, so a walk seat's
  computed default is already in), and its own gap.

  AN INTERACTIVE SEAT IS NOT HERE (R-10.8). A person sits in it and
  nothing fires it — its own `fire` door refuses the engine — so it is
  dropped where the cache is built rather than at the door: a match
  judged and then refused would warn once per matching transition in
  a house where nothing is wrong."
  [eng]
  (into []
        (comp (remove seats/interactive-seat?)
              (map (fn [row]
                     {:id (str (:id row))
                      :wake-on (seats/effective-wake-on row)
                      :interval (interval-of row)})))
        (rows-where eng :seat {:state :active} seat-page)))

(defn- seats-of
  "The active seats, cached for the life of the registration and
  rebuilt lazily after any seat transition. A query per transition
  would be a query per write in the whole house."
  [eng cache]
  (or @cache (reset! cache (active-seats eng))))

(defn count-entry?
  "Is this a COUNT wake (R-12.24)? One field decides it: `at_least`."
  [e]
  (some? (:at_least e)))

(defn matches?
  "Does this ONE entry match the transition? The kind and the action,
  both by name.

  An empty actions list reads differently on the two kinds of entry,
  and deliberately. On a TRANSITION wake it matches nothing — a wake
  is an action happening, not a kind existing, and the read-only
  reading `actions []` carries in a scope has no meaning here. On a
  COUNT wake it matches every action of the kind (R-12.24's default),
  because what that entry watches is the SIZE of a collection, and
  every door of a kind can move it."
  [e kind action]
  (and (= (name kind) (str (:kind e)))
       (if (seq (:actions e))
         (boolean (some #(= (name action) (str %)) (:actions e)))
         (count-entry? e))))

(defn matching-entries
  "The seat's entries this transition matches, in the order the seat
  wrote them."
  [entries kind action]
  (filterv #(matches? % kind action) entries))

(defn wake-text
  "The transition, as the text the fire carries (R-12.22): the kind,
  the row id, the action, the from state and the to state. The
  provider puts it into the session in a payload block, and the
  session walks that one row (R-12.21)."
  [t]
  (wire/write-json {:kind (name (:kind t))
                    :id (str (:resource-id t))
                    :action (name (:action t))
                    :from (some-> (:from-state t) name)
                    :to (some-> (:to-state t) name)}))

;; ── the count wake (R-12.24) ────────────────────────────────────────

(defn count-under
  "How many rows of `kind` a count wake is looking at, or nil when
  this engine does not serve the kind.

  The COLLECTION's count, and not a second one. A list page reads its
  total as `store/count-matching` over the conds
  `collections/parse-query` compiles from the kind's own query
  grammar (`collections/envelope`), and this is those same two calls
  with the entry's filter standing where a caller's query string
  stands. So everything the grammar does for a person asking for a
  page it does here too: the kind's DEFAULT filters apply when the
  entry names none — which is what makes the absent filter the walk's
  own queue — a named field replaces its default, and a field the
  kind does not declare filterable is refused in the query's own
  sentence.

  What the count does NOT wear is a grant's projection. The number is
  the engine's; what the woken session then sees is the sitting's,
  through the seat's scope (R-12.24's punt). A filter the kind cannot
  answer is a warning and a nil — a seat that cannot be counted for
  is a seat that says nothing, rather than a consumer that parks."
  [eng kind filter-map]
  (when-some [rdef (get (inv/resources eng) kind)]
    (try
      (let [params (into {} (map (fn [[f v]] [(name f) (str v)])) filter-map)
            conds (:conds (collections/parse-query rdef params))
            st (:storage eng)]
        (store/with-tx st
          (fn [tx] (store/count-matching st tx (:kind rdef) conds))))
      (catch Exception e
        (warn! "the count wake over " (name kind) " could not be counted — "
               (ex-message e))
        nil))))

(defn moved-under?
  "Does the row that MOVED fall under this entry's filter (R-12.22)?
  An entry with NO filter answers true, which is every transition
  wake written before the filter was read here.

  The count wake's own machinery, asked about ONE row instead of a
  collection: the filter compiles through `collections/parse-query`
  to the conds `count-under` counts with, the moved row's id is one
  more cond, and the whole judgment is one read by primary key. So a
  field the kind does not declare filterable is refused in the
  query's own sentence here too.

  The read happens AFTER the transition committed — this consumer
  walks the log — so a row that moved OUT of the filter by this very
  transition does not wake the seat, and one that moved INTO it does.

  The kind's DEFAULT filters do NOT apply, and that is the one place
  this parts from `count-under`. A default filter is a COLLECTION's
  opening view, and what is asked here is not which rows a queue
  shows: the entry `{task [complete] filter {assignee A}}` names A's
  task, and a default of state=open would hide the very row the
  completion moved. The restamp population reads its filter the same
  way, for the same reason.

  A filter the kind cannot answer is a warning and a NO — a seat that
  cannot be judged for is a seat that says nothing, which is
  `count-under`'s posture at the same wall."
  [eng kind id filter-map]
  (if-not (seq filter-map)
    true
    (boolean
     (when-some [rdef (get (inv/resources eng) kind)]
       (try
         (let [params (into {} (map (fn [[f v]] [(name f) (str v)])) filter-map)
               conds (conj (vec (:conds (collections/parse-query
                                         rdef params {:defaults? false})))
                           {:target :id :op := :value (str id)})
               st (:storage eng)]
           (store/with-tx st
             (fn [tx]
               (pos? (long (store/count-matching st tx (:kind rdef) conds))))))
         (catch Exception e
           (warn! "the wake over " (name kind)
                  " could not judge the row that moved — " (ex-message e))
           false))))))

(defn count-text
  "The count, as the text a count wake's fire carries (R-12.24): the
  kind, the rows waiting, and the size that was asked for. NO row id,
  so the session walks the queue rather than one row."
  [kind n at-least]
  (wire/write-json {:kind (name kind) :count n :at_least at-least}))

(defn- wake-text-for
  "The text this seat's fire carries for this transition, or nil when
  the transition wakes it not at all.

  A TRANSITION entry that matches answers with the transition
  (R-12.22), once the row that moved is under the entry's filter
  (`moved-under?`: one read by id, and an entry with no filter asks
  for no read at all). A COUNT entry that matches costs one count
  query (R-12.24) and answers only once the rows waiting have reached
  its `at_least`; below that the seat is not woken and nothing is
  remembered, because the entry has not matched yet.

  A seat that wrote both kinds and matched both is woken by the
  transition: it is the more specific of the two and names the row
  that moved. A transition entry whose filter the moved row fails
  does not stop the count entry beside it from being asked — it
  matched nothing, so what is left is the count. One transition opens
  one fire either way — the fire's idempotency key is the transition
  it heard."
  [eng entries t]
  (let [matched (matching-entries entries (:kind t) (:action t))]
    (if (some (fn [e]
                (and (not (count-entry? e))
                     (moved-under? eng (:kind t) (:resource-id t) (:filter e))))
              matched)
      (wake-text t)
      (some (fn [e]
              (when (count-entry? e)
                (let [at-least (long (:at_least e))
                      n (or (count-under eng (keyword (name (:kind e)))
                                         (:filter e))
                            0)]
                  (when (>= n at-least)
                    (count-text (:kind e) n at-least)))))
            matched))))

;; ── the damper ──────────────────────────────────────────────────────

(defn fired-recently?
  "Is `at` inside the seat's own gap after the row's last fire? A row
  that never fired is not recent, which is why a first wake goes out
  the moment it matches."
  [schedule-row interval-seconds ^Instant at]
  ;; Two clocks, the later wins. `last_fired_at` is the schedules
  ;; consumer's stamp, written after the provider answered — one
  ;; consumer later, so a burst of matches inside one drain would all
  ;; read it unstamped. `wake_fired_at` is this consumer's own stamp,
  ;; written the moment its fire goes out, and closes that window.
  (let [fired (->> [(get-in schedule-row [:data :last_fired_at])
                    (get-in schedule-row [:data :wake_fired_at])]
                   (keep instant-of)
                   (sort)
                   (last))]
    (if fired
      (.isBefore at (.plusSeconds ^Instant fired (long interval-seconds)))
      false)))

(defn- stamp-fired!
  "The wake consumer's own clock (see `fired-recently?`), by the same
  maintenance write as the pending flag: no version bump, no
  transition. Clears the flag in the same write when asked."
  [eng schedule-row ^Instant at clear-pending?]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/update-data! (:storage eng) tx :schedule (:id schedule-row)
                          (cond-> (assoc (:data schedule-row) :wake_fired_at (str at))
                            clear-pending? (dissoc :wake_pending))
                          (:next-flip-at schedule-row))))
  nil)

(defn- write-pending!
  "Set or clear `wake_pending` by a MAINTENANCE write — no version
  bump, no transition (schedules/stamp-seen!'s pattern, and its
  reason: a transition per damped match would be a log of the engine
  deciding not to act)."
  [eng schedule-row pending?]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/update-data! (:storage eng) tx :schedule (:id schedule-row)
                          (if pending?
                            (assoc (:data schedule-row) :wake_pending true)
                            (dissoc (:data schedule-row) :wake_pending))
                          (:next-flip-at schedule-row))))
  nil)

(defn- mark-pending! [eng schedule-row]
  (when-not (true? (get-in schedule-row [:data :wake_pending]))
    (write-pending! eng schedule-row true))
  nil)

;; ── the fire ────────────────────────────────────────────────────────

(defn- fire!
  "Open the seat's OWN `fire` door as the engine (R-12.19's door, and
  a second concealed one would be the same law written twice). The
  key is the replay dedupe: a drain that re-delivers the transition
  invokes the same key, and invoke answers the stored result.

  A refusal is a warning and nothing else. The door has four guards
  and each of them is a wall this consumer must not argue with: a
  parked seat, a halted seat and an unlinked schedule are all reasons
  not to spend fuel. → true when a fire went out (or had already gone
  out), nil when the door said no."
  [eng seat-id text key]
  (try
    (inv/invoke! eng :seat (str seat-id) :fire
                 (when text {:text text})
                 {:principal schedules/system-actor
                  :idempotency-key key})
    true
    (catch Exception e
      (warn! "seat " seat-id " would not fire — " (ex-message e))
      nil)))

(defn- wake-seat!
  "One active seat, one transition it asked to be woken by, and the
  text that transition earned (`wake-text-for`: the row that moved,
  or a count wake's count).

  No schedule, or one nobody linked: silence, and the link is asked
  BEFORE the damper. The `fire` door would refuse an unlinked seat
  with its own sentence, and a refusal logged per matching transition
  is that sentence a hundred times; remembering the match instead
  would set a flag on a row that has no way to clear it, since the
  release fires through the same refused door.

  Damped — an open sitting, or a fire inside this seat's gap — the
  match is REMEMBERED as `wake_pending` and nothing goes out.
  → true when a fire went out."
  [eng seat t ^Instant at text]
  (when-some [row (schedules/schedule-for-seat eng (:id seat))]
    (when (schedules/linked? row)
      (if (or (some? (seats/open-sitting-for-seat eng (:id seat)))
              (fired-recently? row (:interval seat) at))
        (mark-pending! eng row)
        (when (fire! eng (:id seat) text
                     (str "wake:" (:id seat) ":" (:id t)))
          (stamp-fired! eng row at false)
          true)))))

(defn release!
  "The pending wake of one seat, released now that the damper has
  lifted: a fire with NO TEXT, so the session walks the queue rather
  than one row (R-12.22), and then the flag is cleared.

  Silence when there is nothing pending, when the seat is not active,
  when a sitting is still open, when the gap has not passed, or when
  nobody linked the row. The flag is cleared only after a fire went
  out, so a seat behind a wall keeps its pending wake until the wall
  lifts. → true when a fire went out."
  [eng seat-row schedule-row key ^Instant at]
  (when (and seat-row schedule-row
             (get-in schedule-row [:data :wake_pending])
             (= :active (:state seat-row))
             (schedules/linked? schedule-row)
             (not (fired-recently? schedule-row (interval-of seat-row) at))
             (nil? (seats/open-sitting-for-seat eng (:id seat-row))))
    (when (fire! eng (:id seat-row) nil key)
      (stamp-fired! eng schedule-row at true)
      true)))

(defn- release-for-sitting!
  "A sitting closed or abandoned: the seat it belonged to may have a
  wake waiting on exactly that. Keyed by the sitting's own transition,
  so a replayed close releases once."
  [eng t ^Instant at]
  (when-some [sitting (raw-row eng :sitting (:resource-id t))]
    (when-some [seat-id (some-> (get-in sitting [:data :seat]) str not-empty)]
      (release! eng
                (raw-row eng :seat seat-id)
                (schedules/schedule-for-seat eng seat-id)
                (str "wake:" seat-id ":release:" (:id t))
                at))))

(defn sweep-pending!
  "Every schedule row carrying a pending wake, released where the
  damper has lifted. The tick's whole body, and the one call a test
  makes instead of waiting for it. → the number of fires that went
  out."
  [eng]
  (let [at (now eng)]
    (reduce (fn [n row]
              (let [seat-id (str (get-in row [:data :seat]))]
                (if (release! eng (raw-row eng :seat seat-id) row
                              (str "wake:" seat-id ":sweep:" at)
                              at)
                  (inc n)
                  n)))
            0
            (rows-where eng :schedule {:wake_pending true} pending-page))))

;; ── the consumer ────────────────────────────────────────────────────

(defn handle-transition!
  "One transition → the wake it implies, or nothing.

      seat <anything>          the active-seat cache is stale; drop it
      sitting close, abandon   release that seat's pending wake
      seat, sitting, schedule,
      subscription             nothing else — these are the engine's
                               own writing about wakes, and a seat
                               woken by its own fire wakes forever
      everything else          match it against every active seat's
                               effective wake_on, count for the count
                               entries it matched, and wake the seats
                               that asked

  Never throws: a throwing consumer parks its cursor, and a parked
  cursor stops the whole house from being woken by anything."
  [eng cache t]
  (try
    (let [kind (:kind t)]
      (cond
        (= :seat kind) (reset! cache nil)

        (and (= :sitting kind)
             (contains? #{:close :abandon} (:action t)))
        (release-for-sitting! eng t (now eng))

        (contains? own-kinds kind) nil

        :else
        (let [at (now eng)]
          (doseq [seat (seats-of eng cache)
                  :let [text (wake-text-for eng (:wake-on seat) t)]
                  :when text]
            (wake-seat! eng seat t at text)))))
    (catch Exception e
      (warn! "transition " (:id t) " could not be handled — " (ex-message e))
      nil))
  nil)

(defn consumer-fn
  "The consumer's function of one transition, with the active-seat
  cache held for the life of the registration. Public because a test
  drains it directly (`consumers/drain-consumer!`), which is how this
  suite stays deterministic — schedules/consumer-fn's own shape."
  [eng]
  (let [cache (atom nil)]
    (fn [t] (handle-transition! eng cache t))))

;; ── the tick ────────────────────────────────────────────────────────

(def default-tick-ms
  "Thirty seconds. The damper is a duration and nothing commits when a
  duration ends, so somebody has to ask; overridable per engine as
  `:wake-tick-ms`."
  30000)

(defn start-tick!
  "The release loop, on `start-drift-sweeper!`'s shape. ONE process
  per database should run it, and that is not decided here: the
  module's hook carries `:elected`."
  [eng {:keys [interval-ms] :or {interval-ms default-tick-ms}}]
  (let [stop (CountDownLatch. 1)
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (when-not (.await stop (long interval-ms)
                                         TimeUnit/MILLISECONDS)
                         (try (sweep-pending! eng)
                              (catch Exception e
                                (warn! "the pending sweep failed: "
                                       (ex-message e))))
                         (recur))))
                   "waymark10-wakes-tick")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop}))

(defn stop-tick! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)

(defn start-wakes!
  "Register the durable log consumer that wakes seats on the
  transitions they asked for, and start the tick that releases the
  wakes the damper held. Returns the handle `stop-wakes!` takes.
  opts: :dispatcher, :poll-ms, :from-origin? (the consumer's) and
  :tick-ms."
  ([eng] (start-wakes! eng {}))
  ([eng {:keys [tick-ms] :as opts}]
   {:consumer (consumers/register-consumer!
               eng consumer-name (consumer-fn eng)
               (select-keys opts [:dispatcher :poll-ms :from-origin?]))
    :tick (start-tick! eng {:interval-ms (or tick-ms default-tick-ms)})}))

(defn stop-wakes! [{:keys [consumer tick]}]
  (some-> consumer consumers/stop-consumer!)
  (some-> tick stop-tick!)
  nil)
