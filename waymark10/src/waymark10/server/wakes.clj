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
  actions, which is the one moment the number can have changed, and
  again when one of the seat's sittings closes (`judge-count-on-close`),
  since a seat's work seldom moves the kind it counts — and
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
  `wake_due_at` forward, but never past the FIRST match's instant
  (`wake_first_at`) plus the entry's `max_wait_seconds`, which is
  three settles unless the entry says: matches that never stop must
  not hold the wake forever (ticket 8f482592). The seat's cadence is
  the backstop beside that cap (`backstop-due?`). `release!` then holds the wake until that
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

  The key guards only the fire path. A DAMPED match fires nothing and
  carries no key, so a replay of it while the released run's sitting
  was open set `wake_pending` again, and the next release fired the
  seat a second time for the one transition (waymark-fp62.21). So the
  schedule row remembers the last transitions it heard
  (`wake_heard`), and `wake-seat!` answers a replay of one of them
  with silence, on every path.

  Nothing here re-throws. A throwing consumer parks its cursor, and a
  parked cursor stops every other seat in the house from being woken
  by anything."
  (:require [clojure.string :as str]
            [waymark10.time :refer [instant-of]]
            [waymark10.server.clock :as clock]
            [waymark10.server.collections :as collections]
            [waymark10.server.consumers :as consumers]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.judgments :as judgments]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

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
  field existed. One is the seat as it always was. An open breaker
  (`breaker_open`, seat health 3) caps it to one and leaves the stated
  number on the row."
  [seat-row]
  (let [stated (max 1 (long (or (get-in seat-row [:data :max_open_sittings]) 1)))]
    (if (true? (get-in seat-row [:data :breaker_open]))
      (min stated 1)
      stated)))

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
                        :name (some-> (get-in row [:data :name]) str)
                        :wake-on (seats/effective-wake-on row (walk-rdef row))
                        :interval (interval-of row)
                        :max-open (max-open-of row)})))
          (rows-where eng :seat {:state :active} seat-page))))

(defn- told-of
  "The seats a refused receipt names in its `tell` (spec-integrations
  § 3): the `:on-event` entry's `:tell`, or the integration's approver,
  as the applier wrote them — seat names, or a principal's id. And the
  seat a missed measure reading names in its `tell`
  (docs/spec-measures.md §4). Empty for any other transition, and for
  a receipt that names no one."
  [eng t]
  (cond
    (and (= :integration_event (:kind t)) (= :mark_refused (:action t)))
    (into #{} (comp (map str) (remove empty?))
          (get-in (raw-row eng :integration_event (:resource-id t)) [:data :tell]))

    (and (= :measure_reading (:kind t)) (= :missed (:action t)))
    (into #{} (comp (keep identity) (map str) (remove empty?))
          [(get-in (raw-row eng :measure_reading (:resource-id t)) [:data :tell])])

    :else #{}))

(defn- told?
  "Whether a receipt's `tell` names this seat: by its name, its id, or
  the principal id it acts under."
  [told seat]
  (boolean (or (some-> (:name seat) told)
               (told (:id seat))
               (told (str "seat:" (:id seat))))))

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
  through the seat's scope (R-12.24: a count entry over another kind
  counts every row the filter matches, whatever the seat's grant). A
  filter the kind cannot
  answer is a warning and a nil — a seat that cannot be counted for
  is a seat that says nothing, rather than a consumer that parks."
  ([eng kind filter-map] (count-under eng kind filter-map nil))
  ;; `extra` is store conds counted under beside the filter's own: a
  ;; judging seat's anti-join (`judged-out`)
  ([eng kind filter-map extra]
   (when-some [rdef (get (inv/resources eng) kind)]
     (try
       (let [params (into {} (map (fn [[f v]] [(name f) (str v)])) filter-map)
             conds (into (vec (:conds (collections/parse-query rdef params)))
                         extra)
             st (:storage eng)]
         (store/with-tx st
           (fn [tx] (store/count-matching st tx (:kind rdef) conds))))
       (catch Exception e
         (warn! "the count wake over " (name kind) " could not be counted — "
                (ex-message e))
         nil)))))

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

(defn- max-wait-of
  "The longest a settled entry's wake may wait after the FIRST match
  it heard, in seconds, or nil for an entry that does not settle
  (ticket 8f482592). The entry's own `max_wait_seconds`, or three
  settles when it names none."
  [e]
  (when-some [settle (settle-of e)]
    (long (or (:max_wait_seconds e) (* 3 settle)))))

(declare entry-count)

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
  it heard.

  A count entry over the seat's own walk counts the walk the sit would
  hand (`entry-count`), not the whole collection."
  [eng seat entries t]
  (let [matched (matching-entries entries (:kind t) (:action t))
        seat-row (delay (raw-row eng :seat (:id seat)))]
    (or (some (fn [e]
                (when (and (not (count-entry? e))
                           (moved-under? eng (:kind t) (:resource-id t)
                                         (:filter e)))
                  {:text (wake-text t) :settle (settle-of e)
                   :max-wait (max-wait-of e)}))
              matched)
        (some (fn [e]
                (when (count-entry? e)
                  (when-some [n (entry-count eng @seat-row e)]
                    (let [n (long n)
                          settle (settle-of e)
                          max-wait (max-wait-of e)]
                      (if-some [at-least (:at_least e)]
                        (when (>= n (long at-least))
                          {:text (count-text (:kind e) n :at_least
                                             (long at-least))
                           :settle settle :max-wait max-wait})
                        (let [at-most (long (:at_most e))]
                          (when (<= n at-most)
                            {:text (count-text (:kind e) n :at_most at-most)
                             :settle settle :max-wait max-wait})))))))
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

(declare withheld-ended?)

(defn- stamp-fired!
  "The wake consumer's own clock (see `fired-recently?`), by the same
  maintenance write as the pending flag: no version bump, no
  transition. Clears the flag in the same write when asked, and the
  settle's due moment with it. The wake that was waiting has gone
  out, so nothing is due any more."
  [eng schedule-row ^Instant at clear-pending?]
  (let [ended? (withheld-ended? eng schedule-row)]
    (store/with-tx (:storage eng)
      (fn [tx]
        (store/update-data! (:storage eng) tx :schedule (:id schedule-row)
                            ;; a fire that went out is past the fuel wall, so
                            ;; the `halted` a wall hold stamped comes off, as
                            ;; a sit clear of the wall takes it off; what
                            ;; held the wake back (`stamp-silent!`) holds it
                            ;; no more
                            (cond-> (-> (:data schedule-row)
                                        (assoc :wake_fired_at (str at))
                                        (dissoc :halted :last_silent_wake
                                                :last_silent_wake_at))
                              clear-pending? (schedules/clear-wake)
                              ended? (dissoc :last_withheld_wake))
                            (:next-flip-at schedule-row)))))
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
                            (assoc (schedules/keep-textless (:data schedule-row))
                                   :wake_pending true)
                            (schedules/clear-wake (:data schedule-row)))
                          (:next-flip-at schedule-row))))
  nil)

(defn- mark-pending! [eng schedule-row]
  ;; a wake with no text beside waiting texts is its own fire (ticket
  ;; 58573175), so it is written even when the flag already stands
  (let [data (:data schedule-row)]
    (when-not (and (true? (:wake_pending data))
                   (= data (schedules/keep-textless data)))
      (write-pending! eng schedule-row true)))
  nil)

(def ^:private heard-cap
  "How many transitions a schedule row remembers hearing. A replay is
  the drain's last batch again, so a short tail is enough."
  32)

(defn- heard?
  "Did this seat's wake already hear transition `t`? (the replay)"
  [schedule-row t]
  (boolean (some #{(str (:id t))} (get-in schedule-row [:data :wake_heard]))))

(defn- head-of
  "The row `t` moved, as [kind/id head], or nil when the row names no
  `head_sha` (ticket 70aca5e6)."
  [eng t]
  (when-some [head (some-> (raw-row eng (:kind t) (:resource-id t))
                           (get-in [:data :head_sha]) str not-empty)]
    [(str (name (:kind t)) "/" (:resource-id t)) head]))

(defn- same-head?
  "Is `t` a self-loop on a row whose head (`head-of`'s `seen`) is the
  one this seat's wake last heard it at? A forge poll writes
  observe_failing on a red change every pass, and only a new head is
  news (ticket 70aca5e6). A row that names no head is never the same."
  [schedule-row t seen]
  (and (some? seen)
       (some? (:from-state t))
       (= (:from-state t) (:to-state t))
       (boolean (some #{(str/join " " seen)}
                      (get-in schedule-row [:data :wake_seen])))))

(defn- with-seen
  "`data` with `seen` as its row's one entry in `wake_seen`, newest
  last, at most `heard-cap` rows."
  [data [row-key :as seen]]
  (let [kept (remove #(str/starts-with? (str %) (str row-key " "))
                     (:wake_seen data))]
    (assoc data :wake_seen
           (vec (take-last heard-cap (concat kept [(str/join " " seen)]))))))

(defn- remember-heard!
  "Remember that the wake heard `t`, by the same maintenance write as
  the pending flag, and the moved row's head beside it when `seen`
  names one (`head-of`). → the row as written, so the writes after
  this one build on it and do not drop the mark."
  ([eng schedule-row t] (remember-heard! eng schedule-row t nil))
  ([eng schedule-row t seen]
  (let [heard (->> (conj (vec (get-in schedule-row [:data :wake_heard]))
                         (str (:id t)))
                   (take-last heard-cap)
                   (vec))
        row (cond-> (assoc-in schedule-row [:data :wake_heard] heard)
              seen (update :data with-seen seen))]
    (store/with-tx (:storage eng)
      (fn [tx]
        (store/update-data! (:storage eng) tx :schedule (:id row)
                            (:data row) (:next-flip-at row))))
    row)))

(defn- first-heard
  "The instant of the FIRST match the waiting settled wake heard, or
  nil when no settled wake waits (ticket 8f482592). Read only beside a
  pending flag and a due moment, so a mark some other clear left
  behind is not taken for this wake's."
  ^Instant [schedule-row]
  (let [data (:data schedule-row)]
    (when (and (true? (:wake_pending data)) (:wake_due_at data))
      (instant-of (:wake_first_at data)))))

(defn- due-at
  "The moment a settled wake is due, as the row should hold it after
  this match: the match's own instant plus the entry's quiet time, or
  the moment already on the row when that one is LATER.

  Forward only, and never back (waymark-fp62.17). The settle exists
  to wait for the matches to stop, so a second entry with a shorter
  quiet time must not pull the wake in front of the first entry's.
  With one entry, which is the ordinary seat, the two readings are
  the same moment.

  `max-wait`, when given, is the cap on what this match may ASK for
  (ticket 8f482592): the first match's instant (`first-heard`, or
  this match when it is the first) plus that many seconds. Matches
  that arrive faster than the settle would otherwise move the moment
  forward for ever, and the seat would never sit. A moment the row
  already holds past the cap stays, for the forward-only reason."
  (^Instant [schedule-row ^Instant at settle-seconds]
   (due-at schedule-row at settle-seconds nil))
  (^Instant [schedule-row ^Instant at settle-seconds max-wait]
   (let [asked (.plusSeconds at (long settle-seconds))
         cap (when max-wait
               (.plusSeconds ^Instant (or (first-heard schedule-row) at)
                             (long max-wait)))
         asked (if (and cap (.isAfter asked ^Instant cap)) cap asked)
         held (instant-of (get-in schedule-row [:data :wake_due_at]))]
     (if (and held (.isAfter ^Instant held asked)) held asked))))

(defn- mark-settling!
  "A match on an entry that SETTLES: the seat is not woken now, and
  it is not woken on the first match at all (waymark-fp62.17). The
  match remembers itself as `wake_pending`, exactly as a damped match
  does, and writes `wake_due_at` beside it, so `release!` knows the
  wake is not ready. One maintenance write, and no transition, for
  `write-pending!`'s reason. `text`, when given, joins the waiting
  texts for the release to fire with (ticket afb445d4, and
  `schedules/keep-waiting`)."
  ([eng schedule-row due] (mark-settling! eng schedule-row due nil))
  ([eng schedule-row ^Instant due text]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/update-data! (:storage eng) tx :schedule (:id schedule-row)
                          (assoc (cond-> (:data schedule-row)
                                   text (schedules/keep-waiting text))
                                 :wake_pending true
                                 :wake_due_at (str due))
                          (:next-flip-at schedule-row))))
  nil))

(defn- settled?
  "Has the quiet time passed? A row with no `wake_due_at` has nothing
  to wait for, which is every wake an entry without `settle_seconds`
  left behind."
  [schedule-row ^Instant at]
  (if-some [due (instant-of (get-in schedule-row [:data :wake_due_at]))]
    (not (.isBefore at ^Instant due))
    true))

(defn- backstop-due?
  "Is the seat's cadence owed while a settled wake waits (ticket
  8f482592)? `sweep-cadence!` leaves a row whose wake is already
  pending alone, so a settle that keeps being moved would hold the
  cadence back too. With a settled wake waiting, a whole cadence
  since the last fire releases it whatever `wake_due_at` says. A seat
  that never fired counts from the first match it heard. A seat with
  no cadence, and a wake that no settled entry left, have no
  backstop."
  [seat-row schedule-row ^Instant at]
  (let [cadence (get-in seat-row [:data :cadence_seconds])
        heard (first-heard schedule-row)]
    (boolean
     (and heard
          (number? cadence)
          (pos? (long cadence))
          (if (some #(instant-of (get-in schedule-row [:data %]))
                    [:last_fired_at :wake_fired_at])
            (not (fired-recently? schedule-row (long cadence) at))
            (not (.isBefore at (.plusSeconds ^Instant heard
                                             (long cadence)))))))))

(defn- throttled?
  "Is the provider's throttle still on? The row's `retry_after` is the
  time the provider named; before it nothing is sent, and a wake that
  matches in the meantime folds into the one already pending."
  [schedule-row ^Instant at]
  (if-some [until (instant-of (get-in schedule-row [:data :retry_after]))]
    (.isBefore at ^Instant until)
    false))

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

(defn- wall-mark
  "The schedule's `halted` for a wake the fuel wall held, in the
  spelling a halted sit stamps (`seats/halt-mark`): the wall, and the
  sentence with the wall's own two numbers. A seat whose wakes are all
  held never sits, so the hold has to say it."
  [eng seat-row ^Instant at]
  {:wall "budget"
   :detail (str "The seat is against a wall. The week's fuel is spent: "
                (grants/spent-this-week eng (:id seat-row) at) " of "
                (or (get-in seat-row [:data :budget_usd_per_week]) 0M)
                " over this seat's sittings of the last seven days. The"
                " wall lifts on its own as the window rolls.")})

(defn- hold-at-the-wall!
  "A match the fuel wall held: remembered as `wake_pending`, so the
  first release after the window rolls fires it, and
  `last_halted_wake` stamped beside it with `halted`, the wall and its
  sentence (`wall-mark`), so a person reading the schedule row can see
  why the seat stayed quiet. The first fire that goes out takes
  `halted` off (`stamp-fired!`). One maintenance write, for
  `write-pending!`'s reason, and the seat marked halted
  (`seats/seat-halt-at-the-wall!`), once across repeated holds."
  [eng schedule-row seat-row ^Instant at]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/update-data! (:storage eng) tx :schedule (:id schedule-row)
                          (assoc (schedules/keep-textless (:data schedule-row))
                                 :wake_pending true
                                 :last_halted_wake (str at)
                                 :halted (wall-mark eng seat-row at))
                          (:next-flip-at schedule-row))))
  ;; … and the seat itself, once: a seat whose wakes are all held
  ;; never reaches the router's wall, so the hold says it there too
  (seats/seat-halt-at-the-wall! eng (:id seat-row)
                                (:detail (wall-mark eng seat-row at)))
  nil)

(defn- stamp-withheld!
  "A match withheld by name (ticket 80a8e60b): NOT pending, since the
  row stays withheld until its change moves, but `last_withheld_wake`
  stamped with the row the wake named, so this branch is told apart
  from an empty walk (ticket d803df2d), which stamps `last_silent_wake`
  instead. Written here and nowhere else, and it stays after the row
  it names has ended: the next fire that goes out takes it off when
  that row has left the walk (`withheld-ended?`, ticket d1c371db). One
  maintenance write, for `write-pending!`'s reason."
  [eng schedule-row resource-id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/update-data! (:storage eng) tx :schedule (:id schedule-row)
                          (assoc (:data schedule-row)
                                 :last_withheld_wake (str resource-id))
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

(defn- flying-fires
  "The fires of this seat that are runs that have not sat yet, oldest
  first, each as `{:at … :text …}`. A fire
  goes out before its run opens a sitting, so a burst of wakes that
  counted open sittings alone would read every slot free and fire past
  the ceiling. The fires of the last `in-flight-seconds` are paired,
  oldest first, with the sittings born after them, and a fire no
  sitting took is a run still starting.

  A fire held on a pull link (`:pull_fire`, ticket 56d104f0) is a run in
  flight until a localfire takes it, however long it waits, and then
  for `in-flight-seconds` from `:taken_at`; the keep sweep's drop, or
  the sit that spends it, takes its key off the row. Its key entry is
  what is read once its fire is older than the window: an entry still
  on the row is a run that has not sat, so it pairs with no sitting."
  [eng seat-id ^Instant at]
  (let [st (:storage eng)
        cut (.minusSeconds at (long in-flight-seconds))
        recent (fn [v] (when-some [i (instant-of v)]
                         (when (.isAfter ^Instant i cut) i)))
        ;; a fire that waits for a release grace (`schedules/defer-fire!`)
        ;; was never carried out: its text waits on the schedule row, and
        ;; the release sends it as a fire of its own (ticket 2b128405)
        waiting (set (some-> (schedules/schedule-for-seat eng seat-id)
                             :data schedules/waiting-texts))
        [fires starts held]
        (store/with-tx st
          (fn [tx]
            [(->> (store/transitions st tx {:kind :seat
                                            :resource-id (str seat-id)}
                                     {:limit in-flight-page :newest-first true})
                  (filter #(= "fire" (some-> (:action %) name)))
                  (remove #(contains? waiting
                                      (some-> (get-in % [:inputs :text])
                                              str not-empty)))
                  (keep #(when-some [i (recent (:at %))]
                           {:at i :text (get-in % [:inputs :text])}))
                  (sort-by :at))
             ;; the row's own birth, on the clock the log's `at` is
             ;; written by; a missed sitting is the sweep's record of a
             ;; fire older than the window, and took none of these
             (->> (store/query-rows st tx :sitting {:seat (str seat-id)}
                                    {:limit in-flight-page :newest-first true})
                  (remove #(true? (get-in % [:data :missed])))
                  (keep #(recent (or (:created-at %)
                                     (get-in % [:data :started_at]))))
                  (sort))
             ;; pull fires older than the window: untaken, or taken
             ;; within it; a fire inside the window is in `fires` already
             (->> (get-in (store/load-row st tx :seat (str seat-id) {})
                          [:data :fire_keys])
                  (keep #(when-some [fired (instant-of (:fired_at %))]
                           (when (and (:pull_fire %)
                                      (not (recent (:fired_at %)))
                                      (or (nil? (:taken_at %))
                                          (recent (:taken_at %))))
                             {:at fired :text nil}))))]))
        flying (loop [fs fires ss starts]
                 (cond
                   (empty? fs) nil
                   (empty? ss) fs
                   ;; a sitting born before the oldest fire left took none of them
                   (.isBefore ^Instant (first ss) ^Instant (:at (first fs)))
                   (recur fs (rest ss))
                   :else (recur (rest fs) (rest ss))))]
    (when-some [all (seq (concat held flying))]
      (sort-by :at all))))

(defn- in-flight
  "How many fires of this seat are runs that have not sat yet
  (`flying-fires`)."
  [eng seat-id ^Instant at]
  (count (flying-fires eng seat-id at)))

(defn- spoken-for?
  "Is the walk row this wake's `text` names already another run's? The
  slots count rows and not WHICH row, so with a second row free a
  second wake on one row read a free slot for a free row and fired a
  run that sat, was told its row was held, and stopped (ticket
  884fff20). The row is spoken for when a run still on its way to a
  sit was fired with a text that names it (`flying-fires`), or an open
  sitting of the seat holds it. A text that names no walk row is
  spoken for by nobody."
  [eng seat-row text ^Instant at]
  (when-some [named (seats/fire-names eng seat-row text)]
    (boolean
     (or (some #(= named (seats/fire-names eng seat-row (:text %)))
               (flying-fires eng (str (:id seat-row)) at))
         (contains? (seats/open-walked-rows eng (:id seat-row)) named)))))

(defn- one-for-a-row
  "These wake `texts` with one kept for each walk row they name: the
  first. `spoken-for?` is asked of each text before the release fires
  any, so two texts of different wording on one row both passed it, and
  the second run sat to a row the first had (ticket 2be02e40). A text
  that names no walk row is always kept."
  [eng seat-row texts]
  (first
   (reduce (fn [[kept seen] text]
             (let [named (seats/fire-names eng seat-row text)]
               (if (and named (contains? seen named))
                 [kept seen]
                 [(conj kept text) (cond-> seen named (conj named))])))
           [[] #{}]
           texts)))

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
  served or the filter cannot be answered. `extra` is store conds read
  under beside the filter's own (`judged-out`)."
  [eng kind filter-map extra]
  (when-some [rdef (get (inv/resources eng) kind)]
    (try
      (let [params (into {} (map (fn [[f v]] [(name f) (str v)])) filter-map)
            conds (into (vec (:conds (collections/parse-query rdef params)))
                        extra)
            st (:storage eng)]
        (into #{} (map str)
              (store/with-tx st
                (fn [tx] (store/ids-matching st tx (:kind rdef) conds
                                             queue-page)))))
      (catch Exception e
        (warn! "the walk over " (name kind) " could not be read — "
               (ex-message e))
        nil))))

(defn- withheld-rows
  "The walk row ids a sit of this seat would leave off its page after
  the queue is read: `seats/unwalkable-rows`. → a set of ids."
  [eng seat-row]
  (set (seats/unwalkable-rows eng seat-row nil)))

(defn- judged-out
  "For a seat that walks a judgment, the conds that leave out of its
  queue the subjects that judgment has already judged and, for a judge
  of sittings, its own sittings and the unfinished ones
  (`judgments/walk-conds`, which `mcp/walk-of` reads the same way,
  ticket f508c646); nil for any other seat. Without them a judged
  subject still open in the queue read as a row to hand: a seat of
  several slots fired again on every sitting's close, and each run sat
  to an empty walk (ticket 871c8555). The store answers them in the
  queue's own query, so the count has no page of verdicts to run past
  and the ids read are free ones (ticket 279366ee)."
  [eng seat-row]
  (when-some [jid (some-> (get-in seat-row [:data :judgment]) str not-empty)]
    (not-empty
     (judgments/walk-conds eng (or (raw-row eng :judgment jid) {:id jid})
                           (:id seat-row)))))

(defn- slots
  "What a seat with several slots has in hand at `at`. `:busy` is its
  open sittings and the runs on their way to a sit, less the sittings
  whose sit handed them nothing (`seats/busy-sitting-count`, ticket
  cc2f8e7f): those wait only for the hook's close; `:free` is the rows
  of its walk the sit would hand (no open sitting holds them, no stuck
  change stands beside them and no verdict of its judgment stands on
  them, `withheld-rows`), less the
  rows those runs will take, and less one for each open sitting that
  has sat and not claimed its row yet (`seats/sitting-down-count`,
  ticket c822d9e4): its run is no longer on its way, and the row it is
  about to take reads unclaimed. A seat that walks nothing has no free
  row."
  [eng seat-row ^Instant at]
  (let [seat-id (str (:id seat-row))
        flying (long (in-flight eng seat-id at))
        sitting-down (long (seats/sitting-down-count eng seat-id))
        queue (when-some [[kind f] (walk-query eng seat-row)]
                (ids-under eng kind f (judged-out eng seat-row)))
        unclaimed (count (remove (withheld-rows eng seat-row) queue))]
    {:busy (+ (long (seats/busy-sitting-count eng seat-id)) flying)
     :free (max 0 (- unclaimed flying sitting-down))}))

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

(defn- runs-to-start
  "How many runs one release of a seat with several slots may start at
  `at`: no more than it has slots free, and no more than it has rows to
  hand them (`slots`). A release sends one run for each waiting text and
  one for the wake of no text, and three of them over two free rows
  started a run that sat to a walk its siblings had taken (ticket
  8c071e14). Never less than one: the release was already judged to
  fire. nil for a seat of one slot, which sends as it always did."
  [eng seat-row ^Instant at]
  (let [max-open (long (max-open-of seat-row))]
    (when (< 1 max-open)
      (let [{:keys [busy free]} (slots eng seat-row at)]
        (max 1 (min (- max-open (long busy)) (long free)))))))

(defn- left-waiting
  "`data` with only the sends a release had no row for still waiting:
  `kept` is their texts in order, nil for the wake of no text."
  [data kept]
  (let [texts (vec (keep identity kept))]
    (cond-> (assoc (schedules/clear-wake data) :wake_pending true)
      (seq texts) (assoc :wake_texts texts :wake_text (first texts))
      (and (seq texts) (some nil? kept)) (assoc :wake_textless true))))

;; ── a wake that does not go out says why (ticket d1c371db) ───────────
;;
;; A seat with rows waiting and no run was silence on the schedule row:
;; every branch that holds or drops a wake wrote a line on *err* and
;; nothing a person could read. Each of them now leaves
;; `last_silent_wake`, a token, and `last_silent_wake_at`, when that
;; reason began to hold. The fire that goes out takes both off
;; (`stamp-fired!`).

(defn- withheld-ended?
  "Has the row `last_withheld_wake` names left this seat's walk? A
  stamp on a row that is done reads as the reason for a silence it has
  nothing to do with. A walk that could not be read ends nothing."
  [eng schedule-row]
  (boolean
   (when-some [id (some-> (get-in schedule-row [:data :last_withheld_wake])
                          str not-empty)]
     (when-some [[kind f] (walk-query
                           eng (raw-row eng :seat
                                        (get-in schedule-row [:data :seat])))]
       (when-some [queue (ids-under eng kind f nil)]
         (not (contains? queue id)))))))

(defn- silent
  "`schedule-row` saying `why` its wake did not go out at `at`. The
  moment is the first one this reason held, so a reason that still
  holds keeps its moment."
  [schedule-row ^Instant at why]
  (if (= why (get-in schedule-row [:data :last_silent_wake]))
    schedule-row
    (update schedule-row :data assoc
            :last_silent_wake why
            :last_silent_wake_at (str at))))

(defn- stamp-silent!
  "Write `silent` onto the row, unless the row already says exactly
  that: a wake held at every tick writes one time. One maintenance
  write, for `write-pending!`'s reason. → nil."
  [eng schedule-row ^Instant at why]
  (when (not= why (get-in schedule-row [:data :last_silent_wake]))
    (store/with-tx (:storage eng)
      (fn [tx]
        (store/update-data! (:storage eng) tx :schedule (:id schedule-row)
                            (:data (silent schedule-row at why))
                            (:next-flip-at schedule-row)))))
  nil)

(defn- held-back
  "Why `release!` holds this seat's wake at `at`, or nil when nothing
  does: \"no_live_link\" (`schedules/held?`), \"settling\" (the quiet
  time has not passed and no cadence is owed), \"throttled\" (the
  provider named a later time), \"slots_busy\" (`damped?`, a seat of
  several slots), \"open_sitting\" or \"fire_gap\" (a seat of one)."
  [eng seat-row schedule-row ^Instant at]
  (cond
    (schedules/held? eng schedule-row seat-row) "no_live_link"

    (not (or (settled? schedule-row at)
             (backstop-due? seat-row schedule-row at)))
    "settling"

    (throttled? schedule-row at) "throttled"

    (< 1 (max-open-of seat-row))
    (when (damped? eng seat-row schedule-row at) "slots_busy")

    (some? (seats/open-sitting-for-seat eng (:id seat-row))) "open_sitting"

    (fired-recently? schedule-row (interval-of seat-row) at) "fire_gap"))

(declare empty-walk? grace-lift elsewhere-lift)

(defn- wake-seat!
  "One active seat, one transition it asked to be woken by, and the
  text that transition earned (`wake-for`: the row that moved,
  or a count wake's count).

  No schedule, and no Routine to fire — the row's own link or the
  chair's (waymark-fp62.7.23), or a runner pool (waymark ticket
  102d00d2) — is silence, and the link is asked
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

  A match the `fire` door refuses — a halt line, a parked seat — is
  REMEMBERED the same way, as `release!` keeps its flag: a seat behind
  a wall keeps its pending wake until the wall lifts.

  A transition this seat's wake already heard (`heard?`) is the
  drain's replay, and it is silence on every path, the damped one
  included (waymark-fp62.21).

  A self-loop on a row whose head this seat's wake already heard
  (`same-head?`) is silence too (ticket 70aca5e6): the forge's poll
  on a red change whose head stands has nothing new for the seat, and
  firing it started a sitting that walked nothing. A new head wakes
  it, and a row that names no head is woken as before.
  → true when a fire went out."
  [eng seat t ^Instant at {:keys [text settle max-wait]}]
  (when-some [row (schedules/schedule-for-seat eng (:id seat))]
    (when (and (schedules/fires-out? eng row)
               (not (heard? row t)))
      (let [seen (head-of eng t)
            ;; judged against the heads heard BEFORE this one is kept
            unmoved (same-head? row t seen)
            row (remember-heard! eng row t seen)
            ;; when the grace of the row this wake names lifts: asked
            ;; one time, and only by the branch that reads it
            deferred (delay (when (some? text)
                              (seats/fire-deferred-until
                               eng (raw-row eng :seat (:id seat)) text at)))]
       (cond
        unmoved
        nil

        settle
        ;; the first match of this wake is kept beside the due moment,
        ;; so later matches cannot move it past the cap (8f482592)
        (mark-settling! eng (update row :data
                                    #(assoc (schedules/keep-textless %)
                                            :wake_first_at
                                            (str (or (first-heard row) at))))
                        (due-at row at settle max-wait))

        ;; a broken Routine fires nothing: the wake waits for the link
        ;; that mends it (waymark ticket bb19404d)
        (schedules/held? eng row)
        (mark-pending! eng row)

        (if (< 1 (long (or (:max-open seat) 1)))
          (damped? eng (raw-row eng :seat (:id seat)) row at)
          (or (some? (seats/open-sitting-for-seat eng (:id seat)))
              (fired-recently? row (:interval seat) at)))
        (mark-pending! eng row)

        ;; a throttled Routine: the wake waits for the time it named
        (throttled? row at)
        (mark-pending! eng row)

        ;; the fuel wall: the wake waits, and says it was held
        (at-the-fuel-wall? eng (raw-row eng :seat (:id seat)) at)
        (hold-at-the-wall! eng row (raw-row eng :seat (:id seat)) at)

        ;; the row the wake names is already a run's (ticket 884fff20):
        ;; a seat of several slots is not held by `damped?` while another
        ;; row is free, and its fire would start a run that sits and is
        ;; told the row is held. The run that has the row answers for
        ;; it, and its close fires again while a row is left free.
        (and (< 1 (long (or (:max-open seat) 1)))
             (some? text)
             (spoken-for? eng (raw-row eng :seat (:id seat)) text at))
        (do (warn! "seat " (:id seat) " was woken on " (:resource-id t)
                   ", which a run of it already has — its wake fires nothing")
            nil)

        ;; the row the wake names is withheld by name (ticket 80a8e60b):
        ;; a groom that leaves a ticket open beside its submitted change
        ;; names a row the sit will not hand
        ;; (`seats/named-open-beside-a-submitted-change?`), so its fire
        ;; would start a run that walks nothing. A groom, unblock or
        ;; resume that put a stuck pull request back under review sends
        ;; its ticket out for review after it (ticket 7e01dbe5), and the
        ;; wake that move earned names a round already in the house's
        ;; hands: it is withheld the same way. Any other wake naming a
        ;; ticket in review fires (ticket 7af7d506).
        (let [walk (some-> (raw-row eng :seat (:id seat))
                           (get-in [:data :walk]) str not-empty)]
          (and (some? text)
               (= walk (name (:kind t)))
               (seats/named-beside-a-submitted-change?
                eng walk (:resource-id t)
                (if (contains? #{"groom" "unblock" "resume"}
                               (some-> (:action t) name))
                  #{"open" "in_review"}
                  #{"open"}))))
        (do (warn! "seat " (:id seat) " was woken on " (:resource-id t)
                   ", which is open beside a submitted change — its wake"
                   " fires nothing")
            (stamp-withheld! eng row (:resource-id t)))

        ;; the walk would hand nothing (ticket 87c928e9): a transition
        ;; wake asks the same question `release!` does, so a row the
        ;; walk withholds never fires a run that sits and finds nothing
        (empty-walk? eng (raw-row eng :seat (:id seat)))
        (if-some [lift (grace-lift eng (raw-row eng :seat (:id seat)) at)]
          ;; the row rests in a closed sitting's grace: the wake waits
          ;; for the grace to end rather than being spent now
          (mark-settling! eng (update row :data schedules/keep-textless)
                          (due-at row lift 0))
          (if-some [lift (elsewhere-lift eng (raw-row eng :seat (:id seat)) at)]
            ;; another seat's run holds the row: the wake waits for that
            ;; hold to end, with the text it earned (ticket cbe4d495)
            (mark-settling! eng row (due-at row lift 0) text)
            (do (warn! "seat " (:id seat) " has an empty walk — its wake"
                       " fires nothing")
                (stamp-silent! eng row at "empty_walk"))))

        ;; the row the wake names rests in a closed sitting's grace
        ;; while others wait (ticket afb445d4): the wake waits for the
        ;; grace to lift and then fires with its own text, rather than
        ;; a run that is told its row is held and stops
        (some? @deferred)
        (mark-settling! eng row (due-at row @deferred 0) text)

        :else
        (if (fire! eng (:id seat) text
                   (str "wake:" (:id seat) ":" (:id t)))
          (do (stamp-fired! eng row at false)
              true)
          (mark-pending! eng row)))))))

(defn wake-held
  "What would hold a wake of this seat at `at`, asked with no transition
  and remembering nothing: the live cracks pass wakes a seat on an aged
  row by its `fire` door (ticket 4ff29b4a) and asks this first, so its
  wake is held where `wake-seat!` would hold one. `text` is the fire's
  text, read for the row it names.

    :held        a broken Routine (`schedules/held?`)
    :damped      an open sitting or a fire inside the gap; for a seat
                 of several slots, `damped?`
    :throttled   the provider's `retry_after` is still ahead
    :fuel_wall   the seat's week of fuel is spent
    :spoken_for  a run of the seat already has the row

  nil when nothing holds it. A seat with no schedule, or one that fires
  nowhere, is nil too: its `fire` door refuses it with its own sentence."
  [eng seat-id text ^Instant at]
  (let [seat-row (raw-row eng :seat seat-id)
        row (when seat-row (schedules/schedule-for-seat eng (:id seat-row)))]
    (when (and row (schedules/fires-out? eng row))
      (cond
        (schedules/held? eng row) :held
        (damped? eng seat-row row at) :damped
        (throttled? row at) :throttled
        (at-the-fuel-wall? eng seat-row at) :fuel_wall
        (and (some? text) (spoken-for? eng seat-row text at)) :spoken_for))))

(defn- walk-count
  "How many rows the seat's sit would hand it, or nil when the seat
  walks nothing or the count cannot be taken. The sit's own filter
  (`mcp/walk-of`): the judgment's `queue` for a seat that says one,
  and the walk's scope entry filter otherwise, both under the kind's
  defaults. Less, as the sit leaves them out, the rows another open
  sitting holds, the tickets whose change is stuck (ticket e031e479)
  (`withheld-rows`), and the subjects the seat's judgment has judged
  (`judged-out`, ticket 871c8555). The judged are left out by the store
  in the count's own query. With no row withheld the count is
  `count-under`'s, and with some it is the queue's ids, at most
  `queue-page` of them, less those. A zero here is a zero on the sit's
  page too."
  [eng seat-row]
  (when-some [[walk f] (walk-query eng seat-row)]
    (let [skip (withheld-rows eng seat-row)
          out (judged-out eng seat-row)]
      (if (empty? skip)
        (count-under eng walk f out)
        (some->> (ids-under eng walk f out) (remove skip) count)))))

(defn- unjudged-transcripts
  "How many sealed transcripts under the entry's filter record a
  sitting this judgment has not judged (ticket c9edc5bd), or nil when
  they cannot be read. Only fired sittings keep a transcript
  (`keep_transcripts` fired), so this is the fired sittings still
  waiting on the judge. The entry's
  filter is read through `collections/parse-query`, as `count-under`
  reads it — so a comma value is any-of — and its own `state` replaces
  `sealed`. The store counts them in that one query (ticket 2abff5f9),
  under two anti-joins on the transcript's `sitting`: no standing
  (`said`) verdict of the judgment on it, `judgments/walk-conds`' rule,
  and no sitting of the judging seat `seat-id` with that id: the walk
  never hands the seat its own sitting, so a wake never counts one
  (ticket f508c646). No page of transcripts, verdicts or sittings
  bounds the count."
  [eng judgment-id filter-map seat-id]
  (when-some [rdef (when (serves? eng :transcript)
                     (get (inv/resources eng) :transcript))]
    (try
      (let [params (merge {"state" "sealed"}
                          (into {} (map (fn [[f v]] [(name f) (str v)]))
                                filter-map))
            conds (cond-> (vec (:conds (collections/parse-query
                                        rdef params {:defaults? false})))
                    (serves? eng :verdict)
                    (conj {:target :data :field :sitting :op :no-row
                           :from {:kind :verdict :target :data
                                  :field :subject_id
                                  :conds [{:target :state :op :=
                                           :value "said"}
                                          {:target :data :field :judgment
                                           :cast "text" :op :=
                                           :value (str judgment-id)}]}})

                    (and seat-id (serves? eng :sitting))
                    (conj {:target :data :field :sitting :op :no-row
                           :from {:kind :sitting :target :id
                                  :conds [{:target :data :field :seat
                                           :cast "text" :op :=
                                           :value (str seat-id)}]}}))
            st (:storage eng)]
        (store/with-tx st
          (fn [tx] (store/count-matching st tx :transcript conds))))
      (catch Exception e
        (warn! "the unjudged transcripts could not be counted — "
               (ex-message e))
        nil))))

(defn- entry-count
  "The number a COUNT entry is judged by. An entry of a JUDGMENT seat
  over `transcript` counts the sealed transcripts whose sitting that
  judgment has not judged (`unjudged-transcripts`, ticket c9edc5bd), so
  a judge wakes on the sittings still waiting for it and not on every
  seal. An entry over the seat's own
  walk (its walk kind, under the walk's own filter) counts the walk
  the sit would hand (`walk-count`), so a wake never fires a run whose
  sit walks nothing (ticket e031e479). An entry over the walk's kind
  under a filter of its own counts that filter's rows less the ones the
  sit would withhold (`seats/unwalkable-rows`, ticket 87c928e9), so a
  row the walk holds back never fires the seat whatever filter its
  entry writes. Every other entry counts the collection
  (`count-under`), as it always has."
  [eng seat-row e]
  (let [kind (keyword (name (:kind e)))
        judgment (when seat-row
                   (raw-row eng :judgment
                            (some-> (get-in seat-row [:data :judgment])
                                    str not-empty)))
        [walk f] (when seat-row (walk-query eng seat-row))]
    (cond
      (and judgment (= :transcript kind))
      (unjudged-transcripts eng (:id judgment) (:filter e) (:id seat-row))

      (and walk (= walk kind) (= (not-empty (:filter e)) (not-empty f)))
      (walk-count eng seat-row)

      (and walk (= walk kind))
      (let [skip (withheld-rows eng seat-row)
            out (judged-out eng seat-row)]
        (if (empty? skip)
          (count-under eng kind (:filter e) out)
          (some->> (ids-under eng kind (:filter e) out)
                   (remove skip)
                   count)))

      :else
      (count-under eng kind (:filter e)))))

(defn- grace-lift
  "When the rows of this seat's walk that a closed sitting still holds
  through its grace are handed on (`seats/grace-lifts-at`), or nil. An
  empty walk with a lift ahead is not empty: its wake is deferred to
  that moment, as `wake_due_at`, the way a settle defers one
  (ticket 1a4038bf)."
  [eng seat-row ^Instant at]
  (when-some [[kind f] (walk-query eng seat-row)]
    (when-some [queue (not-empty (ids-under eng kind f nil))]
      (seats/grace-lifts-at eng seat-row queue at))))

(defn- elsewhere-lift
  "When the rows of this seat's walk that an open sitting of ANOTHER
  seat holds are let go (`seats/elsewhere-lifts-at`), or nil. An empty
  walk with such a lift ahead is not empty: its wake waits for it, and
  the tick asks again then (ticket cbe4d495)."
  [eng seat-row ^Instant at]
  (when-some [[kind f] (walk-query eng seat-row)]
    (when-some [queue (not-empty (ids-under eng kind f nil))]
      (seats/elsewhere-lifts-at eng seat-row queue at))))

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
  than one row (R-12.22), and then the flag is cleared. A fire that
  waited out a release grace goes with the text it was given (ticket
  afb445d4), so its run walks the row it named: one run for each
  waiting text (`schedules/waiting-texts`, ticket 58573175), each under
  a key of its own, and a wake with no text that waited beside them
  (`wake_textless`) as its own fire after them.

  Silence when there is nothing pending, when the seat is not active,
  when a sitting is still open, when the gap has not passed, when the
  settle has not passed (`settled?`, waymark-fp62.17), or when the row
  has no way out — no link of its own or its chair's, and no runner
  pool (`schedules/fires-out?`). The flag is cleared only after a fire
  went out, so a seat behind a wall keeps its pending wake until the
  wall lifts.

  A seat whose walk has NO ROW left (`empty-walk?`) is not fired: the
  wake is often the last sitting's own `complete`, and that sitting
  took the row. Its flag is cleared without a fire, and one line says
  so — unless a row of the walk only rests in a closed sitting's grace
  (`grace-lift`): then the wake is due when the grace ends, and the
  tick releases it then.

  A seat of several slots is held by `damped?` rather than by the two
  walls above, and `slot?` (a sitting of it closed) lets it fire with
  NOTHING pending when a slot and a row for it are free (`free-slot?`).
  Its release starts at most `runs-to-start` runs, and the sends past
  that keep waiting (`left-waiting`) for a slot and a row of their own.
  → true when a fire went out."
  ([eng seat-row schedule-row key at]
   (release! eng seat-row schedule-row key at false))
  ([eng seat-row schedule-row key ^Instant at slot?]
  (when (and seat-row schedule-row
             (= :active (:state seat-row))
             (or (get-in schedule-row [:data :wake_pending])
                 (and slot? (free-slot? eng seat-row at)))
             (schedules/fires-out? eng schedule-row))
   (if-some [why (held-back eng seat-row schedule-row at)]
    ;; a wake that waits says what holds it (ticket d1c371db)
    (when (get-in schedule-row [:data :wake_pending])
      (stamp-silent! eng schedule-row at why))
    (cond
      (empty-walk? eng seat-row)
      (if-some [lift (grace-lift eng seat-row at)]
        ;; a row still in a closed sitting's grace: held until it ends
        (mark-settling! eng schedule-row (due-at schedule-row lift 0))
        (if-some [lift (elsewhere-lift eng seat-row at)]
          ;; another seat's run still holds the row: asked again when
          ;; that hold lapses (ticket cbe4d495)
          (mark-settling! eng schedule-row (due-at schedule-row lift 0))
          (do (write-pending! eng (silent schedule-row at "empty_walk") false)
              (warn! "seat " (:id seat-row) " has an empty queue — its pending"
                     " wake is cleared without a fire")
              nil)))

      ;; the fuel wall still holds: the wake keeps waiting, and the
      ;; next release after the window rolls fires it
      (at-the-fuel-wall? eng seat-row at)
      nil

      :else
      (let [waiting (schedules/waiting-texts (:data schedule-row))
            ;; a text that waited may name a row a run took since (ticket
            ;; d65f9247): `runs-to-start` counts rows and not WHICH row,
            ;; so it is dropped here as `wake-seat!` drops it. The run
            ;; that has the row answers for it. Two texts that name one
            ;; row are one send, for the same reason (ticket 2be02e40),
            ;; and that holds for a seat of one slot too (ticket
            ;; fd179d32): no run of it is open here, so no text of its
            ;; own is spoken for, but two of them may still name one row.
            texts (one-for-a-row
                   eng seat-row
                   (if (< 1 (max-open-of seat-row))
                     (remove #(spoken-for? eng seat-row % at) waiting)
                     waiting))
            textless? (get-in schedule-row [:data :wake_textless])
            sends (cond
                    (seq texts)
                    (cond-> (vec (map-indexed
                                  (fn [i text]
                                    [text (if (zero? i) key (str key ":" i))])
                                  texts))
                      textless?
                      (conj [nil (str key ":queue")]))

                    ;; every waiting text names a row a run already has
                    (and (seq waiting) (not textless?))
                    []

                    :else
                    [[nil key]])
            room (runs-to-start eng seat-row at)
            [sends kept] (if room (split-at room sends) [sends nil])
            sent (mapv (fn [[text k]] (fire! eng (:id seat-row) text k)) sends)]
        (cond
          (and (empty? sends) (empty? kept))
          (do (write-pending! eng (silent schedule-row at "rows_taken") false)
              (warn! "seat " (:id seat-row) " had only waiting wakes on rows"
                     " a run of it already has — its release fires nothing")
              nil)

          (some true? sent)
          (do (if (seq kept)
                ;; no free row for these yet: they wait for the next release
                (stamp-fired! eng
                              (update schedule-row :data left-waiting
                                      (map first kept))
                              at false)
                (stamp-fired! eng schedule-row at true))
              true)

          ;; the door said no: the wake keeps waiting, and says so
          (seq sends)
          (stamp-silent! eng schedule-row at "fire_refused"))))))))

(defn release-linked!
  "The wake a broken schedule held, released by the link that mended
  it (waymark ticket bb19404d). Keyed by the link's transition, so a
  replayed drain fires it once. → true when a fire went out."
  [eng schedule-row t]
  (let [seat-id (get-in schedule-row [:data :seat])]
    (release! eng (raw-row eng :seat seat-id) schedule-row
              (str "wake:" seat-id ":link:" (:id t)) (now eng))))

(defn- count-wake-holds?
  "Does one of the seat's COUNT wakes (`at_least`) still hold now? The
  count is `entry-count`'s, the one the consumer itself would take."
  [eng seat-row]
  (let [walk-rdef (some->> (get-in seat-row [:data :walk]) str not-empty
                           keyword (get (inv/resources eng)))]
    (boolean
     (some (fn [e]
             (when-some [at-least (:at_least e)]
               (when-some [n (entry-count eng seat-row e)]
                 (>= (long n) (long at-least)))))
           (seats/effective-wake-on seat-row walk-rdef)))))

(defn- judge-count-on-close
  "The close is a further evaluation point for the seat's count wakes
  (R-12.32, ticket 668a845d). A seat that takes one row per sitting
  usually writes another kind than the one it counts, so no transition
  of the counted kind follows its close, and the rows left would wait
  for the cadence. When nothing is pending yet and an `at_least` entry
  still holds, the wake is marked pending here, as a damped match is,
  and `release!` fires it under the usual damper. An empty queue marks
  nothing. A replayed close is heard once. → the schedule row as it
  stands after."
  [eng seat-row schedule-row t]
  (if (and seat-row schedule-row
           (= :active (:state seat-row))
           (schedules/fires-out? eng schedule-row)
           (not (get-in schedule-row [:data :wake_pending]))
           (not (heard? schedule-row t))
           (count-wake-holds? eng seat-row))
    (do (mark-pending! eng (remember-heard! eng schedule-row t))
        (schedules/schedule-for-seat eng (:id seat-row)))
    schedule-row))

(def ^:private short-outcomes
  "The outcomes of a sitting that did not walk its batch through: a
  close of one of these is another crack, not rows left by the cap."
  #{"cut_short" "refused_out" "never_sat"})

(defn- unhanded-count
  "How many rows the seat's sit would hand it that `handed` (a closed
  sitting's `walked_rows`) does not name: `walk-count`'s rows, by id,
  less those. nil when the walk cannot be read."
  [eng seat-row handed]
  (when-some [[walk f] (walk-query eng seat-row)]
    (some->> (ids-under eng walk f (judged-out eng seat-row))
             (remove (into (withheld-rows eng seat-row) handed))
             count)))

(defn- refire-on-close
  "A sitting handed a full `rows_per_firing` batch closed, and its walk
  still holds rows that firing did not hand it (ticket 7c4f0392). No
  transition of the walked kind may follow — the seat's wake came from
  rows that all arrived before the fire — so the rows left would sit
  until the cadence. The wake is marked pending here, as
  `judge-count-on-close` marks one, and `release!` fires it under the
  usual damper. Only a door or hook close is heard, not an abandon,
  and not one whose outcome says it was cut short, refused out or
  never sat. An empty walk marks nothing. A replayed close is heard
  once.

  A SHORT batch counts too when the walk holds rows the sitting was
  never handed (ticket 629f81b9). A create wake's fire names its one
  row, and the sit hands that row alone, so a seat woken row by row
  never walks the older rows its queue still holds: ops-seat's four
  red ci_runs waited behind one named row a fire. The rows the sitting
  was handed and left are not counted here, so a run that could not
  move a row does not fire itself again for them: those rows are
  `rewake-undelivered!`'s, which gives a row a seat owed a delivery on
  one more try and then records it (ticket 00bad58a). Unhanded rows
  refire; handed rows get one more try, then are recorded.

  A wake already pending stops this only when it is itself the queue
  wake. A rewake's waiting text, written by the same close, is not:
  the queue wake is marked `wake_textless` beside it
  (`schedules/keep-textless`) and the release sends both (ticket
  2a9252a4). → the schedule row as it stands after."
  [eng seat-row schedule-row t]
  (let [sitting (raw-row eng :sitting (:resource-id t))
        walked (into #{} (keep #(some-> % str not-empty))
                     (get-in sitting [:data :walked_rows]))
        handed (count walked)
        cap (long (or (get-in seat-row [:data :rows_per_firing]) 20))]
    (if (and seat-row schedule-row sitting
             (= :close (:action t))
             (= :active (:state seat-row))
             (schedules/fires-out? eng schedule-row)
             (let [data (:data schedule-row)]
               (not (and (:wake_pending data)
                         (= data (schedules/keep-textless data)))))
             (not (heard? schedule-row t))
             (pos? handed)
             (not (contains? short-outcomes
                             (some-> (get-in sitting [:data :outcome]) name)))
             (if (>= handed cap)
               (pos? (long (or (walk-count eng seat-row) 0)))
               (pos? (long (or (unhanded-count eng seat-row walked) 0)))))
      (do (mark-pending! eng (remember-heard! eng schedule-row t))
          (schedules/schedule-for-seat eng (:id seat-row)))
      schedule-row)))

(def ^:private delivered-outcomes
  "The outcomes of a sitting that left with what it owed: its delivery,
  a stall that says why not, or a judge's verdict."
  #{"submitted" "completed" "stalled"})

(def ^:private rewake-page
  "How many of a seat's newest sittings `missed-before?` reads for the
  one that walked a row before."
  50)

(defn- delivery-of
  "What a seat owes a sitting's close, as one line: its `delivers`, or
  `verdict.judge` for a judge that states none. nil when it owes
  nothing a close can miss."
  [seat-row]
  (let [delivers (get-in seat-row [:data :delivers])]
    (cond
      (seq delivers)
      (apply str (interpose ", " (map #(str (some-> (:kind %) name) "."
                                            (some-> (:action %) name))
                                      delivers)))

      (some-> (get-in seat-row [:data :judgment]) str not-empty)
      "verdict.judge")))

(defn- missed-before?
  "Did the newest earlier sitting of this seat that walked `row` leave
  without its delivery too? One that delivered answers false, so a
  delivered sitting in between starts the count again."
  [eng seat-id sitting-id row]
  (let [st (:storage eng)
        prior (->> (store/with-tx st
                     (fn [tx]
                       (store/query-rows st tx :sitting {:seat (str seat-id)}
                                         {:limit rewake-page
                                          :newest-first true})))
                   (remove #(= (str (:id %)) (str sitting-id)))
                   (filter #(some-> (get-in % [:data :outcome]) name))
                   (filter #(some #{row} (map str (get-in % [:data :walked_rows]))))
                   first)]
    (boolean
     (when prior
       (not (contains? delivered-outcomes
                       (name (get-in prior [:data :outcome]))))))))

(defn- rewake-undelivered!
  "A fired sitting of an active seat that owes a delivery (`delivery-of`)
  closed without it, its outcome none of `delivered-outcomes` (ticket
  00bad58a). For each row it walked, the seat is woken ONE more time
  naming that row: a waiting text on the schedule, due when the row's
  release grace lifts, which `release!` fires under the usual damper.
  When the seat's sitting before on that row missed too, the wake is
  not given again: the concealed seat door `undelivered` records the
  sitting, the row, the delivery and the last refusal, and nothing
  fires. `refire-on-close` keeps the rows a sitting was never handed;
  this is the bounded path for the rows it was. A close replayed after
  the wake went out wakes nothing again, and the record's key makes it
  once. Never throws."
  [eng t ^Instant at]
  (when-some [sitting (when (= :close (:action t))
                        (raw-row eng :sitting (:resource-id t)))]
    (let [seat-id (some-> (get-in sitting [:data :seat]) str not-empty)
          seat-row (some->> seat-id (raw-row eng :seat))
          delivery (some-> seat-row delivery-of)
          walk (some-> (get-in seat-row [:data :walk]) str not-empty)
          outcome (some-> (get-in sitting [:data :outcome]) name)
          walked (into [] (comp (keep #(some-> % str not-empty)) (distinct))
                       (get-in sitting [:data :walked_rows]))
          refusal (get-in sitting [:data :last_refusal])
          refused (some-> (or (:guard refusal) (:type refusal)) str not-empty)
          ended (instant-of (get-in sitting [:data :ended_at]))]
      (when (and delivery walk outcome (seq walked)
                 (= :active (:state seat-row))
                 (not (seats/interactive-seat? seat-row))
                 (not (contains? delivered-outcomes outcome)))
        (doseq [row walked]
          (try
            (if (missed-before? eng seat-id (:id sitting) row)
              (inv/invoke! eng :seat seat-id :undelivered
                           (cond-> {:sitting (str (:id sitting))
                                    :row row
                                    :delivery delivery}
                             refused (assoc :last_refusal refused))
                           {:principal seats/seats-actor
                            :idempotency-key (str "undelivered:" (:id t) ":" row)})
              (when-some [schedule (schedules/schedule-for-seat eng seat-id)]
                (let [fired (instant-of (get-in schedule [:data :wake_fired_at]))
                      text (wire/write-json {:kind walk :id row :action "rewake"})]
                  (when (and (schedules/fires-out? eng schedule)
                             (not (and fired ended
                                       (.isAfter ^Instant fired ^Instant ended))))
                    (mark-settling! eng schedule
                                    (due-at schedule
                                            (or (seats/fire-deferred-until
                                                 eng seat-row text at)
                                                at)
                                            0)
                                    text)))))
            (catch Exception e
              (warn! "seat " seat-id " was not woken again for " row
                     " — " (ex-message e)))))))))

(defn- release-for-sitting!
  "A sitting closed or abandoned: the seat it belonged to may have a
  wake waiting on exactly that, a count wake that still holds
  (`judge-count-on-close`), or rows its full batch left behind
  (`refire-on-close`). Keyed by the sitting's own transition,
  so a replayed close releases once. A seat of several slots may fire
  with nothing pending, when a slot and a row for it are free."
  [eng t ^Instant at]
  (when-some [sitting (raw-row eng :sitting (:resource-id t))]
    (when-some [seat-id (some-> (get-in sitting [:data :seat]) str not-empty)]
      (let [seat-row (raw-row eng :seat seat-id)]
        (release! eng
                  seat-row
                  (refire-on-close
                   eng seat-row
                   (judge-count-on-close
                    eng seat-row (schedules/schedule-for-seat eng seat-id) t)
                   t)
                  (str "wake:" seat-id ":release:" (:id t))
                  at
                  true)))))

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
  "Every active seat whose schedule rides its chair's link or a runner
  pool (`schedules/fires-out?`, waymark ticket 962e0aeb), whose
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
                         (schedules/fires-out? eng row)
                         (not (schedules/held? eng row seat-row))
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
  expire, since nobody can say when its run was due.

  A fire held on a pull link (`:pull_fire`, ticket a25950b4) counts from
  when a localfire took it (`:taken_at`), not from when it was queued:
  a laptop asleep is not a run that died. One nobody took yet is not
  missed at all; the pull link's own keep drops it
  (`runner-links/drop-missed!`)."
  [seat-row ^Instant now deadline]
  (let [cut (.minusSeconds now (long deadline))]
    (->> (get-in seat-row [:data :fire_keys])
         (keep (fn [e]
                 (when-some [at (instant-of (if (:pull_fire e)
                                              (:taken_at e)
                                              (:fired_at e)))]
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
  [eng seat-row entry ^Instant now deadline rearm? & [note]]
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
                           :closed_by "missed"
                           ;; seat health 1: no close judges a row
                           ;; born closed, so its outcome is written
                           ;; here — a fire nobody sat in never sat
                           :outcome "never_sat"
                           :flags []
                           :note (or note (missed-note fired deadline schedule))}
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
                            ;; seat health 2: a sitting born closed
                            ;; is a close too, and no door ran for it
                            (do (seats/roll-health! eng (:id seat-row))
                                (inc n))
                            n))
                        n
                        found)))))
        0
        (concat (rows-where eng :seat {:state :active} missed-seat-page)
                (rows-where eng :seat {:state :parked} missed-seat-page)))))))

;; ── the fire a localfire cannot vouch for (ticket 734b3ecd) ─────────

(defn not-started-note
  "The sentence a not-started pull fire's sitting carries."
  [reason]
  (let [s (str "localfire stopped before it knew whether the run started: "
               reason)]
    (subs s 0 (min (count s) (long missed-note-cap)))))

(defn record-not-started!
  "A pull fire whose localfire stopped before it knew whether the run
  started, `held` ({:seat :hash}) its key: when no run spent the key, it
  leaves the seat row and a closed sitting says so with `reason`, as
  `record-missed!` writes one. No wake is armed: the seat's next cadence
  wake fires as usual. → true when this call wrote the sitting."
  [eng {:keys [seat hash]} reason]
  (boolean
   (when (and seat hash (serves? eng :seat) (serves? eng :sitting))
     (let [st (:storage eng)
           seat-row (store/with-tx st
                      (fn [tx] (store/load-row st tx :seat (str seat) {})))
           entry (some #(when (= (str (:hash %)) (str hash)) %)
                       (get-in seat-row [:data :fire_keys]))]
       (when (and entry
                  (record-missed! eng seat-row entry (now eng) 0 false
                                  (not-started-note reason)))
         (seats/roll-health! eng (:id seat-row))
         true)))))

;; ── the fire nobody carried out (ticket cc2a7754) ───────────────────
;;
;; The key above is minted by the schedules consumer, at the moment it
;; carries the seat's `fire` out. A fire that consumer never carried
;; out holds no key, so the pass above cannot see it: the wake stamped
;; `wake_fired_at` and cleared its flag, `last_fired_at` stayed where
;; it was, no run sat, and nothing was written down. This pass reads
;; the log instead, so a silent seat is never unexplained.

(def ^:private unsent-lookback-seconds
  "How far back a fire nobody carried out is still written down. One
  day: an older one is history, and its queue has been fired since."
  86400)

(defn- born-at
  "When a sitting row was born, on the clock the log's `at` is written
  by (`flying-fires`' reading)."
  [sitting]
  (instant-of (or (:created-at sitting) (get-in sitting [:data :started_at]))))

(defn- newest-sitting [st tx seat-id]
  (first (store/query-rows st tx :sitting {:seat (str seat-id)}
                           {:limit 1 :newest-first true})))

(defn- unsent-fire
  "The instant of this seat's newest `fire` when nobody carried it out
  and nothing on the schedule row says why. It is older than `deadline`
  seconds and younger than `unsent-lookback-seconds`; `last_fired_at`,
  which a fire that went out stamps with the fire's own instant, is
  before it; no sitting was born since, a missed one included; and no
  wake waits for a moment still ahead (`wake_due_at`, a deferred
  fire's). → an Instant, or nil."
  [eng seat-row schedule ^Instant now deadline]
  (let [st (:storage eng)
        seat-id (str (:id seat-row))
        [fired sitting]
        (store/with-tx st
          (fn [tx]
            [(->> (store/transitions st tx {:kind :seat :resource-id seat-id}
                                     {:limit in-flight-page :newest-first true})
                  (filter #(= "fire" (some-> (:action %) name)))
                  (keep #(instant-of (:at %)))
                  (sort)
                  (last))
             (newest-sitting st tx seat-id)]))
        stamped (instant-of (get-in schedule [:data :last_fired_at]))
        due (instant-of (get-in schedule [:data :wake_due_at]))
        born (some-> sitting born-at)]
    (when (and fired
               (.isBefore ^Instant fired (.minusSeconds now (long deadline)))
               (.isAfter ^Instant fired
                         (.minusSeconds now (long unsent-lookback-seconds)))
               ;; a millisecond's room, for a stamp the row keeps shorter
               ;; than the log keeps its own
               (not (and stamped
                         (not (.isBefore ^Instant stamped
                                         (.minusMillis ^Instant fired 1)))))
               (not (and born (not (.isBefore ^Instant born ^Instant fired))))
               (not (and due (.isAfter ^Instant due now))))
      fired)))

(defn- record-unsent!
  "One fire nobody carried out, written down in ONE transaction as
  `record-missed!` writes an unspent key: a closed sitting that says
  the run never sat and why, and, unless the seat's last sitting was
  missed too, `wake_pending` on the schedule row, so `release!` fires
  the queue again under the usual damper. The seat row is read FOR
  UPDATE and the newest sitting is read again under it, so two passes
  write one row. → true when this call wrote the sitting."
  [eng seat-row schedule ^Instant fired ^Instant now deadline]
  (let [st (:storage eng)
        seat-id (str (:id seat-row))
        model (or (seats/chair-of seat-row)
                  (some-> (get-in schedule [:data :model]) str not-empty))]
    (boolean
     (store/with-tx st
       (fn [tx]
         (when (store/load-row st tx :seat seat-id {:for-update true})
           (let [newest (newest-sitting st tx seat-id)
                 born (some-> newest born-at)]
             (when-not (and born (not (.isBefore ^Instant born fired)))
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
                         :closed_by "missed"
                         :outcome "never_sat"
                         :flags []
                         :note (str "Fired at " fired "; the fire was never"
                                    " sent to a runner, and no session sat"
                                    " within " deadline "s.")}
                  model (assoc :model model))
                {:principal seats/seats-actor :state :closed})
               (when-not (true? (get-in newest [:data :missed]))
                 (store/update-data! st tx :schedule (str (:id schedule))
                                     (assoc (:data schedule) :wake_pending true)
                                     (:next-flip-at schedule)))
               true))))))))

(defn sweep-unsent!
  "Every active seat whose newest fire nobody carried out
  (`unsent-fire`), written down as a closed `missed` sitting, and its
  wake armed again. The clock sweep's pass, after `sweep-missed!`, so a
  fire that pass just wrote down is not written twice. A schedule that
  is broken or throttled already says why on its own row, and is left
  alone. A second pass over the same state writes nothing, because the
  first left a sitting born after the fire.
  → the number of missed sittings written."
  ([eng] (sweep-unsent! eng default-sit-deadline-seconds))
  ([eng deadline]
   (if-not (and (serves? eng :seat) (serves? eng :sitting)
                (serves? eng :schedule))
     0
     (let [at (now eng)]
       (reduce
        (fn [n seat-row]
          (if (try
                (when-some [schedule (when-not (seats/interactive-seat? seat-row)
                                       (schedules/schedule-for-seat
                                        eng (:id seat-row)))]
                  (when (and (schedules/fires-out? eng schedule)
                             (not (schedules/held? eng schedule seat-row))
                             (not (throttled? schedule at)))
                    (when-some [fired (unsent-fire eng seat-row schedule
                                                   at deadline)]
                      (record-unsent! eng seat-row schedule fired at
                                      deadline))))
                (catch Exception e
                  (warn! "seat " (:id seat-row)
                         " unsent fire not recorded — " (ex-message e))
                  false))
            (do (seats/roll-health! eng (:id seat-row))
                (inc n))
            n))
        0
        (rows-where eng :seat {:state :active} missed-seat-page))))))

;; ── the consumer ────────────────────────────────────────────────────

(defn handle-transition!
  "One transition → the wake it implies, or nothing.

      seat <anything>          the active-seat cache is stale; drop it
      sitting close, abandon   release that seat's pending wake, and
                               judge its count wakes once more
      seat, sitting, schedule,
      subscription             nothing else — these are the engine's
                               own writing about wakes, and a seat
                               woken by its own fire wakes forever
      everything else          match it against every active seat's
                               effective wake_on, count for the count
                               entries it matched, and wake the seats
                               that asked — and, for a refused
                               integration receipt, the seats its
                               `tell` names, asked or not

  Never throws: a throwing consumer parks its cursor, and a parked
  cursor stops the whole house from being woken by anything."
  [eng cache t]
  (try
    (let [kind (:kind t)]
      (cond
        (= :seat kind) (reset! cache nil)

        (and (= :sitting kind)
             (contains? #{:close :abandon} (:action t)))
        (do (rewake-undelivered! eng t (now eng))
            (release-for-sitting! eng t (now eng)))

        (contains? own-kinds kind) nil

        :else
        (let [at (now eng)
              told (told-of eng t)]
          (doseq [seat (seats-of eng cache)
                  :let [wake (or (wake-for eng seat (:wake-on seat) t)
                                 (when (told? told seat)
                                   {:text (wake-text t)}))]
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
  {:stop (clock/every! {:name "waymark10-wakes-tick" :period-ms interval-ms
                        :f #(tick! eng)})})

(defn stop-tick! [{:keys [stop]}]
  (when stop (stop))
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
