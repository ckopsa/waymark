(ns waymark10.server.wakes
  "The wake consumer (spec-seat.md R-12.22): the third way a sitting
  begins.

  The first two are the cadence, which the schedule's copy at the
  provider fires — or, for a schedule with no link of its own that
  rides its chair's Routine, this file's tick (`sweep-cadence!`) — and
  a person's own `fire`. This is the third — a
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

  R-12.24 gives a `wake_on` entry a second reading. An entry that
  names NEITHER size is the TRANSITION wake above: the row that moved
  wakes the seat, and the text names it. The entry's `filter` is read
  on both — on this one it names which MOVED rows wake the seat
  (`moved-under?`), so a seat that watches one change's runs is not
  woken by every other change's. An entry that names `at_least` or
  `at_most` is a COUNT wake: the seat is not woken by a row, it is
  woken by a queue reaching a size. It does not poll — the count is
  read only when a transition of that kind matches the entry's
  actions, which is the one moment the number can have changed — and
  the count itself is the collection's own (`count-under`), so the
  number in the text is the number the list page would show under the
  same filter.

  The two sizes are the two directions (waymark-fp62.13). `at_least`
  wakes the seat when the queue has grown to the size: the work is
  the rows waiting. `at_most` wakes it when the queue has come DOWN
  to the size, and `at_most` 0 is the empty queue — the work is that
  nothing is waiting, which is the planner who must make the next
  plan. An entry names one of the two; the seat's schema refuses
  both, so nothing here has to choose between them.

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

  ── a seat that runs several sittings at once ──────────────────────

  A seat's `max_open_sittings` is one by default, and one is the seat
  exactly as above. Above one, part 1 reads as a count: the wake is
  held once the seat has that many runs going, the OPEN sittings and
  the fires still on their way to a sit (`in-flight`). Short of that,
  while a row of the seat's walk is left that no open sitting holds
  and no run on its way will take, the wake fires another run, and
  part 2's gap does not hold it: a slot is not a busy queue. When a
  sitting closes, such a seat fires again if a slot and such a row
  are left, pending or not. Each sit then claims its own row
  (`seats/claim-rows-atomically!`).

  ── the settle, and which edge a wake fires on ─────────────────────

  The damper above holds the matches AFTER the first one. The first
  one still fires at once, which is the LEADING edge, and for a queue
  that is right: the row is there and the work is to walk it.

  A conversation is the other case (waymark-fp62.17). The first reply
  in a family chat is the middle of the week's decision, not the end
  of it, and a seat woken by it reads a chat that is half answered.
  So an entry may name `settle_seconds`, and such an entry fires on
  the TRAILING edge. A match writes `wake_pending` and `wake_due_at`
  (the match's own instant plus the settle) on the schedule row and
  fires NOTHING, not even the first one. A later match moves
  `wake_due_at` forward. `release!` then holds the wake until that
  moment has passed, beside the walls it already keeps, and the tick
  is what asks: the settle is a duration, so the answer comes within
  one tick of the moment it ends. The release fires with no text, as
  every release does, so the session walks the queue.

  An entry with no `settle_seconds` behaves exactly as it always did.

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
            [waymark10.server.grants :as grants]
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

(defn max-open-of
  "The seat's `max_open_sittings`, or one for a row written before the
  field existed. One is the seat as it always was."
  [seat-row]
  (max 1 (long (or (get-in seat-row [:data :max_open_sittings]) 1))))

(defn- active-seats
  "Every active seat a wake can reach, as the three facts a match
  needs: its id, what wakes it (`effective-wake-on`, so a walk seat's
  computed default is already in — it is handed the WALKED kind's
  declaration, because a filtered walk's default names every action
  of that kind), and its own gap.

  AN INTERACTIVE SEAT IS NOT HERE (R-10.8). A person sits in it and
  nothing fires it — its own `fire` door refuses the engine — so it is
  dropped where the cache is built rather than at the door: a match
  judged and then refused would warn once per matching transition in
  a house where nothing is wrong."
  [eng]
  (let [walk-rdef (fn [row]
                    (some->> (get-in row [:data :walk]) str not-empty
                             keyword (get (inv/resources eng))))]
    (into []
          (comp (remove seats/interactive-seat?)
                (map (fn [row]
                       {:id (str (:id row))
                        :wake-on (seats/effective-wake-on row (walk-rdef row))
                        :interval (interval-of row)
                        :max-open (max-open-of row)})))
          (rows-where eng :seat {:state :active} seat-page))))

(defn- seats-of
  "The active seats, cached for the life of the registration and
  rebuilt lazily after any seat transition. A query per transition
  would be a query per write in the whole house."
  [eng cache]
  (or @cache (reset! cache (active-seats eng))))

(defn count-entry?
  "Is this a COUNT wake (R-12.24)? Either size says so: `at_least`,
  the queue grown to a size, or `at_most`, the queue drained to one.
  An entry that names neither is a transition wake. An entry that
  names both never reaches here — the seat's schema refuses it."
  [e]
  (or (some? (:at_least e)) (some? (:at_most e))))

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
  so the session walks the queue rather than one row.

  `size-field` is the entry's own word — `:at_least` or `:at_most` —
  so the text says which way the seat was counting, and a session
  reading `{\"count\": 0, \"at_most\": 0}` is told the queue is empty
  rather than left to infer it."
  [kind n size-field size]
  (wire/write-json {:kind (name kind) :count n size-field size}))

(defn- settle-of
  "The entry's quiet time in seconds, or nil for an entry that fires
  on the match (waymark-fp62.17). Read off the entry that MATCHED, so
  one settled entry does not settle the seat's other entries."
  [e]
  (some-> (:settle_seconds e) long))

(defn- wake-for
  "What this transition asks of this seat, as `{:text … :settle …}`,
  or nil when the transition wakes it not at all. The text is what
  the fire carries and the settle is the matched entry's own quiet
  time, which decides WHICH EDGE the wake fires on.

  A TRANSITION entry that matches answers with the transition
  (R-12.22), once the row that moved is under the entry's filter
  (`moved-under?`: one read by id, and an entry with no filter asks
  for no read at all). A COUNT entry that matches costs one count
  query (R-12.24) and answers only once the rows waiting have reached
  its size: at or above `at_least`, or at or below `at_most`. Short
  of that the seat is not woken and nothing is remembered, because
  the entry has not matched yet.

  A count that could not be taken wakes NOBODY. `count-under` answers
  nil for a kind this engine does not serve and for a filter the kind
  cannot answer, and nil is not zero: an `at_most` entry read as zero
  would fire on the engine's own failure to count, and say the queue
  was empty.

  A seat that wrote both kinds and matched both is woken by the
  transition: it is the more specific of the two and names the row
  that moved. A transition entry whose filter the moved row fails
  does not stop the count entry beside it from being asked — it
  matched nothing, so what is left is the count. One transition opens
  one fire either way — the fire's idempotency key is the transition
  it heard."
  [eng entries t]
  (let [matched (matching-entries entries (:kind t) (:action t))]
    (or (some (fn [e]
                (when (and (not (count-entry? e))
                           (moved-under? eng (:kind t) (:resource-id t)
                                         (:filter e)))
                  {:text (wake-text t) :settle (settle-of e)}))
              matched)
        (some (fn [e]
                (when (count-entry? e)
                  (when-some [n (count-under eng (keyword (name (:kind e)))
                                             (:filter e))]
                    (let [n (long n)
                          settle (settle-of e)]
                      (if-some [at-least (:at_least e)]
                        (when (>= n (long at-least))
                          {:text (count-text (:kind e) n :at_least
                                             (long at-least))
                           :settle settle})
                        (let [at-most (long (:at_most e))]
                          (when (<= n at-most)
                            {:text (count-text (:kind e) n :at_most at-most)
                             :settle settle})))))))
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
  transition. Clears the flag in the same write when asked, and the
  settle's due moment with it. The wake that was waiting has gone
  out, so nothing is due any more."
  [eng schedule-row ^Instant at clear-pending?]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/update-data! (:storage eng) tx :schedule (:id schedule-row)
                          (cond-> (assoc (:data schedule-row) :wake_fired_at (str at))
                            clear-pending? (dissoc :wake_pending :wake_due_at))
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
                            (dissoc (:data schedule-row) :wake_pending :wake_due_at))
                          (:next-flip-at schedule-row))))
  nil)

(defn- mark-pending! [eng schedule-row]
  (when-not (true? (get-in schedule-row [:data :wake_pending]))
    (write-pending! eng schedule-row true))
  nil)

(defn- due-at
  "The moment a settled wake is due, as the row should hold it after
  this match: the match's own instant plus the entry's quiet time, or
  the moment already on the row when that one is LATER.

  Forward only, and never back (waymark-fp62.17). The settle exists
  to wait for the matches to stop, so a second entry with a shorter
  quiet time must not pull the wake in front of the first entry's.
  With one entry, which is the ordinary seat, the two readings are
  the same moment."
  ^Instant [schedule-row ^Instant at settle-seconds]
  (let [asked (.plusSeconds at (long settle-seconds))
        held (instant-of (get-in schedule-row [:data :wake_due_at]))]
    (if (and held (.isAfter ^Instant held asked)) held asked)))

(defn- mark-settling!
  "A match on an entry that SETTLES: the seat is not woken now, and
  it is not woken on the first match at all (waymark-fp62.17). The
  match remembers itself as `wake_pending`, exactly as a damped match
  does, and writes `wake_due_at` beside it, so `release!` knows the
  wake is not ready. One maintenance write, and no transition, for
  `write-pending!`'s reason."
  [eng schedule-row ^Instant due]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/update-data! (:storage eng) tx :schedule (:id schedule-row)
                          (assoc (:data schedule-row)
                                 :wake_pending true
                                 :wake_due_at (str due))
                          (:next-flip-at schedule-row))))
  nil)

(defn- settled?
  "Has the quiet time passed? A row with no `wake_due_at` has nothing
  to wait for, which is every wake an entry without `settle_seconds`
  left behind."
  [schedule-row ^Instant at]
  (if-some [due (instant-of (get-in schedule-row [:data :wake_due_at]))]
    (not (.isBefore at ^Instant due))
    true))

;; ── the fuel wall ────────────────────────────────────────────────────
;;
;; A wake is fuel, and a seat whose week of fuel is spent has none to
;; give. The router's own wall (R-5.2 step 3) would scope the run's
;; grant to nothing, so a run fired past it spends tokens on a sit that
;; can walk nothing. The wake asks the SAME wall before it fires, and a
;; wake the wall holds waits as `wake_pending`, exactly as a damped
;; match does, until the window rolls.

(defn at-the-fuel-wall?
  "Is this seat's week of fuel spent at `at`? The router's arithmetic,
  said once: `grants/spent-this-week` against the seat's budget,
  through `grants/under-budget?`, so the wake and the wall cannot
  disagree about one number. No seat row is no wall."
  [eng seat-row ^Instant at]
  (boolean
   (and seat-row
        (not (grants/under-budget?
              (grants/spent-this-week eng (:id seat-row) at)
              (get-in seat-row [:data :budget_usd_per_week]))))))

(defn- hold-at-the-wall!
  "A match the fuel wall held: remembered as `wake_pending`, so the
  first release after the window rolls fires it, and
  `last_halted_wake` stamped beside it, so a person reading the
  schedule row can see why the seat stayed quiet. One maintenance
  write, for `write-pending!`'s reason."
  [eng schedule-row ^Instant at]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/update-data! (:storage eng) tx :schedule (:id schedule-row)
                          (assoc (:data schedule-row)
                                 :wake_pending true
                                 :last_halted_wake (str at))
                          (:next-flip-at schedule-row))))
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

;; ── the slots: a seat that runs several sittings at once ──────────────

(def ^:private in-flight-seconds
  "How long a fire counts as a run on its way to a sit: the missed-fire
  sweep's own deadline (`default-sit-deadline-seconds`). A run that has
  not sat by then has died, and the sweep writes it down."
  600)

(def ^:private in-flight-page
  "The most of the seat's newest transitions, and of its newest
  sittings, one count of the runs in flight reads."
  50)

(def ^:private queue-page
  "The most walk rows one count of the unclaimed rows reads."
  200)

(defn- in-flight
  "How many fires of this seat are runs that have not sat yet. A fire
  goes out before its run opens a sitting, so a burst of wakes that
  counted open sittings alone would read every slot free and fire past
  the ceiling. The fires of the last `in-flight-seconds` are paired,
  oldest first, with the sittings born after them, and a fire no
  sitting took is a run still starting."
  [eng seat-id ^Instant at]
  (let [st (:storage eng)
        cut (.minusSeconds at (long in-flight-seconds))
        recent (fn [v] (when-some [i (instant-of v)]
                         (when (.isAfter ^Instant i cut) i)))
        [fires starts]
        (store/with-tx st
          (fn [tx]
            [(->> (store/transitions st tx {:kind :seat
                                            :resource-id (str seat-id)}
                                     {:limit in-flight-page :newest-first true})
                  (filter #(= "fire" (some-> (:action %) name)))
                  (keep #(recent (:at %)))
                  (sort))
             ;; the row's own birth, on the clock the log's `at` is
             ;; written by; a missed sitting is the sweep's record of a
             ;; fire older than the window, and took none of these
             (->> (store/query-rows st tx :sitting {:seat (str seat-id)}
                                    {:limit in-flight-page :newest-first true})
                  (remove #(true? (get-in % [:data :missed])))
                  (keep #(recent (or (:created-at %)
                                     (get-in % [:data :started_at]))))
                  (sort))]))]
    (loop [fs fires ss starts]
      (cond
        (empty? fs) 0
        (empty? ss) (count fs)
        ;; a sitting born before the oldest fire left took none of them
        (.isBefore ^Instant (first ss) ^Instant (first fs)) (recur fs (rest ss))
        :else (recur (rest fs) (rest ss))))))

(defn- walk-query
  "The kind the seat's sit walks and the filter it walks it under, as
  [kind filter-map], or nil when the seat walks nothing this engine
  serves: the judgment's `queue` for a seat that says one, and the
  walk's scope entry filter otherwise (`mcp/walk-of`'s own choice)."
  [eng seat-row]
  (when-some [walk (some->> (get-in seat-row [:data :walk]) str not-empty
                            keyword)]
    (when (serves? eng walk)
      [walk (if-some [judgment (raw-row eng :judgment
                                        (some-> (get-in seat-row [:data :judgment])
                                                str not-empty))]
              (get-in judgment [:data :queue])
              (seats/walk-filter seat-row))])))

(defn- ids-under
  "`count-under`'s rows by id rather than by number, at most
  `queue-page` of them, as a set of strings; nil when the kind is not
  served or the filter cannot be answered."
  [eng kind filter-map]
  (when-some [rdef (get (inv/resources eng) kind)]
    (try
      (let [params (into {} (map (fn [[f v]] [(name f) (str v)])) filter-map)
            conds (:conds (collections/parse-query rdef params))
            st (:storage eng)]
        (into #{} (map str)
              (store/with-tx st
                (fn [tx] (store/ids-matching st tx (:kind rdef) conds
                                             queue-page)))))
      (catch Exception e
        (warn! "the walk over " (name kind) " could not be read — "
               (ex-message e))
        nil))))

(defn- slots
  "What a seat with several slots has in hand at `at`. `:busy` is its
  open sittings and the runs on their way to a sit; `:free` is the rows
  of its walk no open sitting holds, less the rows those runs will
  take. A seat that walks nothing has no free row."
  [eng seat-row ^Instant at]
  (let [seat-id (str (:id seat-row))
        flying (long (in-flight eng seat-id at))
        queue (when-some [[kind f] (walk-query eng seat-row)]
                (ids-under eng kind f))
        unclaimed (count (remove (seats/claimed-rows eng seat-id nil) queue))]
    {:busy (+ (long (seats/open-sitting-count eng seat-id)) flying)
     :free (max 0 (- unclaimed flying))}))

(defn- damped?
  "Does the damper hold a wake of this seat at `at` (R-12.22's parts 1
  and 2)? With one slot: an open sitting, or a fire inside the gap.
  With several: every slot busy; or no free row, and then an open
  sitting or the gap holds it as it always did. A free slot for a free
  row is not held by the gap."
  [eng seat-row schedule-row ^Instant at]
  (let [recent? (fired-recently? schedule-row (interval-of seat-row) at)
        max-open (max-open-of seat-row)]
    (if (= 1 max-open)
      (or (some? (seats/open-sitting-for-seat eng (:id seat-row))) recent?)
      (let [{:keys [busy free]} (slots eng seat-row at)]
        (or (>= (long busy) max-open)
            (and (not (pos? (long free)))
                 (or (pos? (long busy)) recent?)))))))

(defn- free-slot?
  "Has a seat with several slots a slot free AND a row for it? What a
  closing sitting asks before it fires another run with nothing
  pending."
  [eng seat-row ^Instant at]
  (let [max-open (max-open-of seat-row)]
    (and (< 1 max-open)
         (let [{:keys [busy free]} (slots eng seat-row at)]
           (and (< (long busy) max-open) (pos? (long free)))))))

(defn- wake-seat!
  "One active seat, one transition it asked to be woken by, and the
  text that transition earned (`wake-for`: the row that moved,
  or a count wake's count).

  No schedule, and no Routine to fire — the row's own link or the
  chair's (waymark-fp62.7.23) — is silence, and the link is asked
  BEFORE the damper. The `fire` door would refuse an unlinked seat
  with its own sentence, and a refusal logged per matching transition
  is that sentence a hundred times; remembering the match instead
  would set a flag on a row that has no way to clear it, since the
  release fires through the same refused door.

  A SETTLED entry, one that names `settle_seconds`, fires nothing on
  the match. It moves the due moment forward instead
  (waymark-fp62.17). That branch is asked BEFORE the damper. The
  damper's business is the matches after the FIRST fire, and a
  settled entry has no first fire: the leading edge is the very thing
  it gives up.

  Damped — an open sitting, or a fire inside this seat's gap; for a
  seat of several slots, `damped?` — the match is REMEMBERED as
  `wake_pending` and nothing goes out.
  → true when a fire went out."
  [eng seat t ^Instant at {:keys [text settle]}]
  (when-some [row (schedules/schedule-for-seat eng (:id seat))]
    (when (schedules/linked? eng row)
      (cond
        settle
        (mark-settling! eng row (due-at row at settle))

        (if (< 1 (long (or (:max-open seat) 1)))
          (damped? eng (raw-row eng :seat (:id seat)) row at)
          (or (some? (seats/open-sitting-for-seat eng (:id seat)))
              (fired-recently? row (:interval seat) at)))
        (mark-pending! eng row)

        ;; the fuel wall: the wake waits, and says it was held
        (at-the-fuel-wall? eng (raw-row eng :seat (:id seat)) at)
        (hold-at-the-wall! eng row at)

        :else
        (when (fire! eng (:id seat) text
                     (str "wake:" (:id seat) ":" (:id t)))
          (stamp-fired! eng row at false)
          true)))))

(defn- walk-count
  "How many rows the seat's sit would hand it, or nil when the seat
  walks nothing or the count cannot be taken. The sit's own filter
  (`mcp/walk-of`): the judgment's `queue` for a seat that says one,
  and the walk's scope entry filter otherwise, both under the kind's
  defaults through `count-under`. A judgment seat's count does not
  subtract the subjects already judged, so it can only read high: a
  zero here is a zero on the sit's page too."
  [eng seat-row]
  (when-some [[walk f] (walk-query eng seat-row)]
    (count-under eng walk f)))

(defn- empty-walk?
  "Would a release put this seat's session in front of an empty queue?
  Only a seat with a walk is asked, and not one that wakes when its
  queue comes DOWN (`at_most`): its work begins on the empty queue. A
  count that could not be taken is not zero, so that seat fires."
  [eng seat-row]
  (let [walk-rdef (some->> (get-in seat-row [:data :walk]) str not-empty
                           keyword (get (inv/resources eng)))]
    (and (some? walk-rdef)
         (not (some #(some? (:at_most %))
                    (seats/effective-wake-on seat-row walk-rdef)))
         (= 0 (some-> (walk-count eng seat-row) long)))))

(defn release!
  "The pending wake of one seat, released now that the damper has
  lifted: a fire with NO TEXT, so the session walks the queue rather
  than one row (R-12.22), and then the flag is cleared.

  Silence when there is nothing pending, when the seat is not active,
  when a sitting is still open, when the gap has not passed, when the
  settle has not passed (`settled?`, waymark-fp62.17), or when nobody
  linked the row or its chair. The flag is cleared only after a fire
  went out, so a seat behind a wall keeps its pending wake until the
  wall lifts.

  A seat whose walk has NO ROW left (`empty-walk?`) is not fired: the
  wake is often the last sitting's own `complete`, and that sitting
  took the row. Its flag is cleared without a fire, and one line says
  so.

  A seat of several slots is held by `damped?` rather than by the two
  walls above, and `slot?` (a sitting of it closed) lets it fire with
  NOTHING pending when a slot and a row for it are free (`free-slot?`).
  → true when a fire went out."
  ([eng seat-row schedule-row key at]
   (release! eng seat-row schedule-row key at false))
  ([eng seat-row schedule-row key ^Instant at slot?]
  (when (and seat-row schedule-row
             (= :active (:state seat-row))
             (or (get-in schedule-row [:data :wake_pending])
                 (and slot? (free-slot? eng seat-row at)))
             (schedules/linked? eng schedule-row)
             (settled? schedule-row at)
             (if (< 1 (max-open-of seat-row))
               (not (damped? eng seat-row schedule-row at))
               (and (not (fired-recently? schedule-row (interval-of seat-row) at))
                    (nil? (seats/open-sitting-for-seat eng (:id seat-row))))))
    (cond
      (empty-walk? eng seat-row)
      (do (write-pending! eng schedule-row false)
          (warn! "seat " (:id seat-row) " has an empty queue — its pending"
                 " wake is cleared without a fire")
          nil)

      ;; the fuel wall still holds: the wake keeps waiting, and the
      ;; next release after the window rolls fires it
      (at-the-fuel-wall? eng seat-row at)
      nil

      :else
      (when (fire! eng (:id seat-row) nil key)
        (stamp-fired! eng schedule-row at true)
        true)))))

(defn- release-for-sitting!
  "A sitting closed or abandoned: the seat it belonged to may have a
  wake waiting on exactly that. Keyed by the sitting's own transition,
  so a replayed close releases once. A seat of several slots may fire
  with nothing pending, when a slot and a row for it are free."
  [eng t ^Instant at]
  (when-some [sitting (raw-row eng :sitting (:resource-id t))]
    (when-some [seat-id (some-> (get-in sitting [:data :seat]) str not-empty)]
      (release! eng
                (raw-row eng :seat seat-id)
                (schedules/schedule-for-seat eng seat-id)
                (str "wake:" seat-id ":release:" (:id t))
                at
                true))))

(defn sweep-pending!
  "Every schedule row carrying a pending wake, released where the
  damper has lifted and the settle has passed. The tick's whole body,
  and the one call a test makes instead of waiting for it. → the
  number of fires that went out."
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

;; ── the cadence a chair's Routine does not keep ─────────────────────
;;
;; A schedule with its own link has a copy at the provider, and the
;; provider's cron keeps its cadence. A schedule that rides its CHAIR'S
;; Routine has no copy anywhere — the adapter leaves it alone, and the
;; one Routine a model stands for carries no cron of any one seat — so
;; nobody kept its cadence and it fired only on wakes. The tick keeps
;; it: a cadence owed is a pending wake, released by `release!` under
;; the same damper, empty-walk and fuel checks as any other.

(defn- cadence-due?
  "Is this seat's cadence owed at `at`? A seat with no cadence owes
  nothing. The last fire is `fired-recently?`'s, the later of the
  provider's stamp and the wake's own, so a seat woken inside its
  cadence is not fired again by it."
  [seat-row schedule-row ^Instant at]
  (let [cadence (get-in seat-row [:data :cadence_seconds])]
    (boolean
     (and (number? cadence)
          (pos? (long cadence))
          (not (fired-recently? schedule-row (long cadence) at))))))

(defn sweep-cadence!
  "Every active seat whose schedule rides its chair's link, whose
  cadence is owed, marked `wake_pending` so the release that follows
  fires it. A schedule with its own link is left to its provider's
  cron, and an interactive seat is fired by nobody (R-10.8). → the
  number of wakes marked."
  [eng]
  (let [at (now eng)]
    (reduce (fn [n seat-row]
              (let [row (when-not (seats/interactive-seat? seat-row)
                          (schedules/schedule-for-seat eng (:id seat-row)))]
                (if (and row
                         (not (schedules/linked? row))
                         (schedules/linked? eng row)
                         (not (true? (get-in row [:data :wake_pending])))
                         (cadence-due? seat-row row at))
                  (do (write-pending! eng row true) (inc n))
                  n)))
            0
            (rows-where eng :seat {:state :active} seat-page))))

(defn tick!
  "The tick's whole body, and the one call a test makes instead of
  waiting for it: the cadences owed, then every pending wake. → the
  number of fires that went out."
  [eng]
  (try (sweep-cadence! eng)
       (catch Exception e
         (warn! "the cadence sweep failed: " (ex-message e))))
  (sweep-pending! eng))

;; ── the fire nobody sat in ──────────────────────────────────────────
;;
;; A fire mints a key (`seats/hold-fire-key!`) and the run's sit spends
;; it. A run that dies before it sits spends nothing, opens no sitting
;; and gets no transcript key, so the run is invisible to an audit that
;; reads the sittings. It also costs the queue its wake: a count wake
;; was consumed by the fire, and nothing arms it again. The clock sweep
;; asks this pass, and the pass writes both things down.

(def default-sit-deadline-seconds
  "How long a fired run has to sit before the sweep says it never did.
  Ten minutes: a run that has not sat by then has died."
  600)

(def ^:private missed-seat-page
  "The most seats one pass reads for unspent keys."
  500)

(def ^:private missed-note-cap
  "The sitting's `note` is 240 characters wide."
  240)

(defn- missed-entries
  "The fire-key entries of this seat row still unspent more than
  `deadline` seconds after their `fired_at`, oldest first. An entry
  with no `fired_at` was held before the field existed; it is left to
  expire, since nobody can say when its run was due."
  [seat-row ^Instant now deadline]
  (let [cut (.minusSeconds now (long deadline))]
    (->> (get-in seat-row [:data :fire_keys])
         (keep (fn [e]
                 (when-some [at (instant-of (:fired_at e))]
                   (when (.isBefore ^Instant at cut) [at e]))))
         (sort-by first)
         (mapv second))))

(defn- missed-note
  "The sentence the missed sitting carries, with the provider's page for
  the run when the schedule row has one."
  [^Instant fired deadline schedule-row]
  (let [url (some-> (get-in schedule-row [:data :last_run_url]) str not-empty)
        s (str "Fired at " fired "; no session sat within " deadline "s."
               (when url (str " Last run: " url)))]
    (if (<= (count s) (long missed-note-cap))
      s
      (subs s 0 missed-note-cap))))

(defn- count-wake-holds?
  "Does one of the seat's COUNT wakes (`at_least`) still hold now? The
  count is `count-under`'s, the one the consumer itself would take."
  [eng seat-row]
  (let [walk-rdef (some->> (get-in seat-row [:data :walk]) str not-empty
                           keyword (get (inv/resources eng)))]
    (boolean
     (some (fn [e]
             (when-some [at-least (:at_least e)]
               (when-some [n (count-under eng (keyword (name (:kind e)))
                                          (:filter e))]
                 (>= (long n) (long at-least)))))
           (seats/effective-wake-on seat-row walk-rdef)))))

(defn- last-sitting-missed?
  "Was the seat's newest sitting itself a missed one? Two missed runs
  in a row stop the re-arm, so a Routine that is dark does not loop."
  [st tx seat-id]
  (true? (get-in (first (store/query-rows st tx :sitting {:seat (str seat-id)}
                                          {:limit 1 :newest-first true}))
                 [:data :missed])))

(defn- record-missed!
  "One unspent entry, written down in ONE transaction: the entry leaves
  the seat row, so a late sit with its key is refused as it always
  was; a closed sitting that says the run never sat is born by the
  quiet door; and, when `rearm?` and the seat's last sitting was not
  missed too, `wake_pending` goes onto the schedule row, as the
  consumer marks a damped wake, so `release!` fires the queue again
  under the usual damper. The seat row is read FOR UPDATE, so a sit
  that spends the key at the same moment wins or loses whole.
  → true when this call wrote the sitting."
  [eng seat-row entry ^Instant now deadline rearm?]
  (let [st (:storage eng)
        seat-id (str (:id seat-row))
        schedule (schedules/schedule-for-seat eng seat-id)
        fired (instant-of (:fired_at entry))
        model (or (seats/chair-of seat-row)
                  (some-> (get-in schedule [:data :model]) str not-empty))
        same? #(= (str (:hash %)) (str (:hash entry)))]
    (boolean
     (store/with-tx st
       (fn [tx]
         (when-some [row (store/load-row st tx :seat seat-id {:for-update true})]
           (let [held (get-in row [:data :fire_keys])]
             (when (some same? held)
               (let [again? (last-sitting-missed? st tx seat-id)]
                 (store/update-data! st tx :seat seat-id
                                     (assoc (:data row) :fire_keys
                                            (into [] (remove same?) held))
                                     (:next-flip-at row))
                 (inv/insert-quiet!
                  eng tx :sitting
                  (cond-> {:seat seat-id
                           :member (seats/sitter-id seat-row)
                           :mode seats/default-mode
                           :started_at fired
                           :ended_at now
                           :input_tokens 0 :output_tokens 0
                           :cache_read_tokens 0 :cache_write_tokens 0
                           :turns 0 :transitions 0 :refusals 0
                           :served {}
                           :missed true
                           :note (missed-note fired deadline schedule)}
                    model (assoc :model model))
                  {:principal seats/seats-actor :state :closed})
                 (when (and rearm? (not again?) schedule)
                   (store/update-data! st tx :schedule (str (:id schedule))
                                       (assoc (:data schedule) :wake_pending true)
                                       (:next-flip-at schedule)))
                 true)))))))))

(defn sweep-missed!
  "Every fire whose key is still unspent more than `deadline` seconds
  (default `default-sit-deadline-seconds`) after the fire, written down
  as a closed `missed` sitting, and the count wake it spent armed
  again where it still holds. The clock sweep's pass
  (`definitions/sweep-clock!`). A second pass over the same state
  writes nothing, because the first took the entry off the seat row.
  → the number of missed sittings written."
  ([eng] (sweep-missed! eng default-sit-deadline-seconds))
  ([eng deadline]
   (if-not (and (serves? eng :seat) (serves? eng :sitting))
     0
     (let [at (now eng)]
       (reduce
        (fn [n seat-row]
          (let [found (missed-entries seat-row at deadline)]
            (if (empty? found)
              n
              (let [rearm? (and (= :active (:state seat-row))
                                (count-wake-holds? eng seat-row))]
                (reduce (fn [n entry]
                          (if (try (record-missed! eng seat-row entry at
                                                   deadline rearm?)
                                   (catch Exception e
                                     (warn! "seat " (:id seat-row)
                                            " missed fire not recorded — "
                                            (ex-message e))
                                     false))
                            (inc n)
                            n))
                        n
                        found)))))
        0
        (concat (rows-where eng :seat {:state :active} missed-seat-page)
                (rows-where eng :seat {:state :parked} missed-seat-page)))))))

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
                  :let [wake (wake-for eng (:wake-on seat) t)]
                  :when wake]
            (wake-seat! eng seat t at wake)))))
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
                         (try (tick! eng)
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
