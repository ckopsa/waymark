(ns waymark10.server.live-cracks
  "The live cracks audit (ticket 26e51658, part 2 of epic 400609cf): a
  scheduled pass over ROWS, where `waymark10.server.cracks` reads
  declarations. It names five ways work has stopped with nobody told:

  aged          a row whose state has an expected age and that no
                write has touched for longer
  silent        a seat with rows in its walk that has not fired within
                its cadence, with what its schedule says held the wake
  bounce        a draft ticket shelved twice running for one reason
  refusal_loop  a seat whose last sittings all ended refused out or
                cut short on one guard
  orphan        a change open or submitted whose ticket is done or
                dropped

  Each is filed ONCE, as a draft ticket naming the row, the crack and
  the seats the static audit says can move it (`cracks/movers`). The
  ticket's detail ends in a marker, and a pass files nothing for a
  crack whose marker a ticket still standing carries, so a second pass
  adds nothing and a crack that outlives its ticket is filed again.

  AN AGED ROW'S NOTE GOES FIRST TO THE SEAT THAT HOLDS IT (ticket
  fee9090f): when exactly one fired seat holds a door out of the row's
  state and its walk admits the row (`holders`), the pass wakes that
  seat on the row, by the seat's own `fire` door, and files nothing.
  The fire's recorded text carries the crack's key, so the seat's log
  is the note, written once. The draft for the mayor is filed when no
  seat holds the row, when several do, when the seat would not fire,
  or when the seat was woken and the row is still aged one expected
  age later; then it says which seat was tried (`route`). The wake
  asks the damper first (`wakes/wake-held`, ticket 4ff29b4a): a seat
  with a run up or just fired, or a throttled Routine, is not woken a
  second time, and the crack waits for a later pass; a broken Routine
  or a spent week waits on a person, so that crack is the mayor's.

  AN EXPECTED AGE is declared per kind and state, in seconds, under the
  declaration's `:expected-age`. A state with none takes the default
  for its NAME (`default-ages`), and a state with neither has no
  expected age. That is deliberate: a row may rest in `draft`, `done`
  or `active` for as long as it likes.

  `find-cracks`, `unfiled` and `ticket-of` are pure over a world of
  rows, so a test judges them with no engine. `pass!` reads that world
  and files; nothing in it re-throws."
  (:require [clojure.string :as str]
            [waymark10.server.cracks :as cracks]
            [waymark10.server.invoke :as inv]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.wakes :as wakes]
            [waymark10.wire :as wire])
  (:import (java.time Instant)
           (java.util.concurrent CountDownLatch TimeUnit)))

(set! *warn-on-reflection* true)

(def default-ages
  "Seconds a row may sit in a state of this name with no write, for a
  kind that declares no `:expected-age` for it."
  {"open" 86400
   "submitted" 86400
   "in_review" 86400
   "blocked" 604800})

(def ^:private default-cadence
  "Seconds a seat that states no cadence may go without a fire while
  rows wait in its walk."
  3600)

(def ^:private loop-run
  "This many sittings in a row, newest first, make a refusal loop."
  3)

(def ^:private row-page
  "The most rows one read of one state answers."
  500)

(def ^:private file-cap
  "The most tickets one pass files. The rest wait for the next pass."
  20)

(def ^:private closed-states
  "A ticket in one of these no longer stands for its crack."
  #{"done" "dropped"})

(defn- warn! [& parts]
  (binding [*out* *err*]
    (println (apply str "waymark10 live cracks: " parts))))

(defn- text [v] (if (keyword? v) (name v) (str v)))

(defn- fget
  "A map's value for a key, whichever way the row spells the key."
  [m k]
  (when (map? m)
    (let [k (keyword (text k))]
      (if (contains? m k) (get m k) (get m (name k))))))

(defn- inst
  "An instant, however the row spells it. Unparsable is nil."
  ^Instant [v]
  (cond
    (instance? Instant v) v
    (some-> v str not-empty) (try (Instant/parse (str v))
                                  (catch Exception _ nil))
    :else nil))

(defn- past?
  "Is `now` more than `seconds` after `at`?"
  [^Instant at seconds ^Instant now]
  (.isBefore (.plusSeconds at (long seconds)) now))

(defn- short-id [id]
  (let [s (str id)] (subs s 0 (min 8 (count s)))))

(defn- seat-name [seat]
  (or (some-> (get-in seat [:data :name]) str not-empty) (str (:id seat))))

(defn expected-age
  "Seconds a row of this kind may sit in `state` with no write: the
  kind's own `:expected-age` for the state, or the default for the
  state's name, or nil when it has neither."
  [rdef state]
  (let [s (text state)]
    (or (some (fn [[k v]] (when (= s (text k)) v)) (:expected-age rdef))
        (get default-ages s))))

(defn- crack [word kind id m]
  (merge {:crack word
          :kind (text kind)
          :id (str id)
          :key (str word ":" (text kind) ":" id)}
         m))

(defn- movers [rdef seat-rows state]
  (if rdef (cracks/movers rdef seat-rows state) []))

(defn aged-rows
  "aged: every row in `rows` (kind → rows) that no write has touched
  for longer than its state's expected age."
  [rdefs seat-rows rows now]
  (for [rdef (sort-by (comp text :kind) rdefs)
        :let [terminal (into #{} (map text) (:terminal rdef))]
        row (get rows (keyword (text (:kind rdef))))
        :let [state (text (:state row))
              age (expected-age rdef state)
              at (inst (:updated-at row))]
        :when (and age at
                   (not (contains? terminal state))
                   (past? at age now))]
    (crack "aged" (:kind rdef) (:id row)
           {:state state
            :since (str at)
            :expected_seconds age
            :seats (movers rdef seat-rows state)
            :what (str "It has sat in " state " since " at
                       ", past the " age " seconds a row is expected to stay there.")})))

(defn- in-walk?
  "Does a walk under `flt` read this row? Its `state` entry, or the
  kind's default, names the states; with neither, any state that is not
  terminal. A range bound (_after, _before, _gte, _lte) is not judged
  here and lets the row pass; every other entry is read as field =
  value, so an entry that is no equality admits no row."
  [rdef flt row]
  (let [flt (into {} (map (fn [[k v]] [(text k) v])) flt)
        sv (or (get flt "state") (get-in rdef [:default-filters :state]))
        states (when (some? sv)
                 (into #{}
                       (comp (map (comp str/trim text)) (remove str/blank?))
                       (if (coll? sv) sv (str/split (text sv) #","))))
        state (text (:state row))]
    (and (if (seq states)
           (contains? states state)
           (not (contains? (into #{} (map text) (:terminal rdef)) state)))
         (every? (fn [[f v]]
                   (= (text v) (some-> (fget (:data row) f) text)))
                 (remove (fn [[f _]] (re-find #"_(after|before|gte|lte)$" f))
                         (dissoc flt "state"))))))

(defn walk-admits?
  "Would this seat's walk hand it this row? A seat that walks the row's
  kind does when the row is under its walk's filter; a seat that walks
  tickets does for a change when the ticket the change was born from is
  under it, since the sit hands a ticket's change beside it. A judgment
  seat walks its judgment's `queue`, read from `judgments` when the
  seat's judgment is there, and its scope entry's filter otherwise.
  `by-kind` is kind keyword → its declaration, `tickets` ticket id →
  row and `judgments` judgment id → row (ticket af41e6bf)."
  [by-kind {:keys [tickets judgments]} seat rdef row]
  (let [kind (text (:kind rdef))
        born (str (fget (:data row) "born_from"))
        ticket (when (and (= "change" kind) (str/starts-with? born "ticket:"))
                 (get tickets (subs born (count "ticket:"))))
        walk (some-> (get-in seat [:data :walk]) text not-empty)
        queue (some->> (get-in seat [:data :judgment]) str not-empty
                       (get judgments) :data :queue)
        flt (if (map? queue) queue (seats/walk-filter seat))]
    (boolean
     (cond
       (= walk kind) (in-walk? rdef flt row)
       (and ticket (= "ticket" walk) (:ticket by-kind))
       (in-walk? (:ticket by-kind) flt ticket)
       :else false))))

(defn- holders
  "The fired seats that hold a door out of this row's state and whose
  walk admits the row (`walk-admits?`). `by-kind` is kind keyword → its
  declaration and `tickets` is ticket id → row."
  [by-kind seat-rows tickets rdef row]
  (let [state (text (:state row))]
    (filterv (fn [seat]
               (boolean
                (and (not (seats/interactive-seat? seat))
                     (seq (cracks/movers rdef [seat] state))
                     (walk-admits? by-kind {:tickets tickets} seat rdef row))))
             seat-rows)))

(defn- to-its-holder
  "An aged crack, naming under `:wake` the ONE seat that holds its row.
  With no holder, or several, it names none and its note is the
  mayor's."
  [by-kind seat-rows rows tickets c]
  (let [kind (keyword (:kind c))
        rdef (get by-kind kind)
        row (some #(when (= (:id c) (str (:id %))) %) (get rows kind))
        held (when (and rdef row) (holders by-kind seat-rows tickets rdef row))]
    (cond-> c
      (= 1 (count held))
      (assoc :wake {:seat (str (:id (first held)))
                    :name (seat-name (first held))}))))

(defn silent-seats
  "silent: a fired seat with rows in its walk whose schedule shows no
  fire within its cadence. A seat a person sits in, and one that walks
  a judgment's queue, are not read. `schedules` is seat id → its
  schedule row. `withheld` is seat id → the walk row ids its sit would
  not hand (`seats/unwalkable-rows`), left out of the count as
  `wakes/walk-count` leaves them out."
  [rdefs seat-rows rows schedules withheld now]
  (let [by-kind (into {} (map (juxt (comp text :kind) identity)) rdefs)]
    (for [seat seat-rows
          :let [walk (some-> (get-in seat [:data :walk]) text not-empty)
                rdef (get by-kind walk)]
          :when (and rdef
                     (not (seats/interactive-seat? seat))
                     (str/blank? (str (get-in seat [:data :judgment]))))
          :let [flt (seats/walk-filter seat)
                skip (set (get withheld (str (:id seat))))
                waiting (count (filter #(and (in-walk? rdef flt %)
                                             (not (contains? skip (str (:id %)))))
                                       (get rows (keyword walk))))
                sched (get schedules (str (:id seat)))
                cadence (or (get-in seat [:data :cadence_seconds]) default-cadence)
                fired (last (sort (keep inst [(get-in sched [:data :last_fired_at])
                                              (get-in sched [:data :wake_fired_at])
                                              (:created-at seat)])))
                why (some-> (get-in sched [:data :last_silent_wake]) text not-empty)
                why-at (some-> (get-in sched [:data :last_silent_wake_at]) str not-empty)]
          :when (and (pos? waiting)
                     (or (nil? fired) (past? fired cadence now)))]
      (crack "silent" :seat (:id seat)
             {:waiting waiting
              :walk walk
              :last_fired_at (some-> fired str)
              :last_silent_wake why
              :last_silent_wake_at why-at
              :seats [(seat-name seat)]
              :what (str "The seat " (seat-name seat) " has " waiting " " walk
                         " row(s) in its walk and has not fired within "
                         cadence " seconds: its last fire was "
                         (or (some-> fired str) "never") ". Its schedule says "
                         (if why
                           (str "last_silent_wake " why " since " (or why-at "an unknown time"))
                           "nothing held a wake back")
                         ".")}))))

(defn bounces
  "bounce: a draft ticket whose last two shelvings gave one reason.
  `shelvings` is ticket id → the reasons its shelvings recorded, oldest
  first; a shelving that recorded none is nil there and ends a run."
  [ticket-rdef seat-rows tickets shelvings]
  (for [row tickets
        :let [[a b] (take-last 2 (get shelvings (str (:id row))))]
        :when (and (= "draft" (text (:state row)))
                   (some-> a str not-empty)
                   (= a b))]
    (crack "bounce" :ticket (:id row)
           {:state "draft"
            :shelved_because a
            :seats (movers ticket-rdef seat-rows :draft)
            :what (str "It was shelved back to draft twice running for one reason: " a)})))

(defn- refused-by
  "The guard a sitting's refusal named: its `refused_by:` flag, or its
  last refusal's guard."
  [sitting]
  (or (some (fn [f]
              (let [f (text f)]
                (when (str/starts-with? f "refused_by:")
                  (not-empty (subs f (count "refused_by:"))))))
            (:flags sitting))
      (some-> (fget (:last_refusal sitting) :guard) text not-empty)))

(defn refusal-loops
  "refusal_loop: a seat whose last `loop-run` sittings all ended
  refused_out or cut_short on one guard. `sittings` is seat id → its
  closed sittings' documents, newest first."
  [seat-rows sittings]
  (for [seat seat-rows
        :let [recent (take loop-run (get sittings (str (:id seat))))
              guards (map refused-by recent)]
        :when (and (= loop-run (count recent))
                   (every? #(contains? #{"refused_out" "cut_short"}
                                       (some-> (:outcome %) text))
                           recent)
                   (some? (first guards))
                   (apply = guards))]
    (crack "refusal_loop" :seat (:id seat)
           {:guard (first guards)
            :seats [(seat-name seat)]
            :what (str "The last " loop-run " sittings of the seat " (seat-name seat)
                       " ended refused out or cut short on one guard: "
                       (first guards) ".")})))

(defn orphans
  "orphan: a change open or submitted whose ticket is done or dropped.
  `tickets` is ticket id → row, for the tickets those changes were born
  from."
  [change-rdef seat-rows changes tickets]
  (for [row changes
        :let [state (text (:state row))
              born (str (get-in row [:data :born_from]))
              tid (when (str/starts-with? born "ticket:")
                    (subs born (count "ticket:")))
              tstate (some-> (get tickets tid) :state text)]
        :when (and (contains? #{"open" "submitted"} state)
                   (contains? closed-states tstate))]
    (crack "orphan" :change (:id row)
           {:state state
            :ticket tid
            :ticket_state tstate
            :seats (movers change-rdef seat-rows state)
            :what (str "It is " state " and the ticket it was born from, " tid
                       ", is " tstate ": nothing will merge or close it.")})))

(defn find-cracks
  "Every live crack in a world of rows, in a stable order. The world:
  :rdefs, :seats (the ACTIVE seat rows), :rows (kind → rows),
  :schedules (seat id → schedule row), :sittings (seat id → closed
  sitting documents, newest first), :shelvings (ticket id → recorded
  reasons, oldest first), :tickets (ticket id → row, for the changes'
  tickets), :withheld (seat id → the walk row ids its sit would not
  hand) and :now."
  [{:keys [rdefs seats rows schedules sittings shelvings tickets withheld now]}]
  (let [by-kind (into {} (map (juxt (comp keyword text :kind) identity)) rdefs)]
    (into []
          (concat (map #(to-its-holder by-kind seats rows tickets %)
                       (aged-rows rdefs seats rows now))
                  (silent-seats rdefs seats rows schedules withheld now)
                  (bounces (:ticket by-kind) seats (:ticket rows) shelvings)
                  (refusal-loops seats sittings)
                  (orphans (:change by-kind) seats (:change rows) tickets)))))

(defn- marker
  "The line a crack's ticket ends in, and what a later pass looks for."
  [c]
  (str "[crack " (:key c) "]"))

(defn ticket-of
  "The draft ticket one crack earns: the row, the crack, the seats that
  can move it, and the marker."
  [c]
  (let [title (str (:crack c) ": " (:kind c) " " (short-id (:id c))
                   (when (:state c) (str " in " (:state c))))]
    {:title (subs title 0 (min 200 (count title)))
     :detail (str (:what c)
                  "\n\nRow: " (:kind c) " " (:id c)
                  "\nCrack: " (:crack c)
                  "\nSeats that can move it: "
                  (if (seq (:seats c))
                    (str/join ", " (:seats c))
                    "none; read /api/-/cracks for the state no seat can leave")
                  (when (:tried c) (str "\nSeat tried: " (:tried c)))
                  "\n\n" (marker c))
     :type "bug"
     :priority 2}))

(defn unfiled
  "The cracks no standing ticket names, each one time. `tickets` are
  ticket rows; one that is done or dropped stands for nothing."
  [cracks tickets]
  (let [details (into []
                      (comp (remove #(contains? closed-states (text (:state %))))
                            (keep #(some-> (get-in % [:data :detail]) str)))
                      tickets)
        filed? (fn [c] (some #(str/includes? % (marker c)) details))]
    (second
     (reduce (fn [[seen out] c]
               (if (or (contains? seen (:key c)) (filed? c))
                 [seen out]
                 [(conj seen (:key c)) (conj out c)]))
             [#{} []]
             cracks))))

(defn wake-text
  "What the fire that wakes a seat on a crack's row carries: the row, as
  a transition wake names one (`wakes/wake-text`), so the session walks
  that row alone, and the crack's key, which a later pass looks for in
  the seat's log."
  [c]
  (wire/write-json {:kind (:kind c)
                    :id (:id c)
                    :action "crack"
                    :from (:state c)
                    :to (:state c)
                    :crack (:key c)}))

(defn route
  "Where one unfiled crack's note goes, as [door crack]. `woken` is when
  the seat it names was last woken on it, or nil.

      :file  no one seat holds the row, or the seat was woken and the
             row is still aged one expected age later; the crack then
             says which seat was tried
      :wake  one seat holds the row and has not been woken on it
      :wait  the seat was woken less than one expected age ago"
  [c woken now]
  (let [woken (inst woken)]
    (cond
      (nil? (:wake c)) [:file c]
      (nil? woken) [:wake c]
      (past? woken (or (:expected_seconds c) 0) now)
      [:file (assoc c :tried (str (get-in c [:wake :name])
                                  ", woken on the row at " woken))]
      :else [:wait c])))

;; ── the pass ────────────────────────────────────────────────────────

(defn- serves? [eng kind] (contains? (inv/resources eng) kind))

(defn- rows-in [eng kind where opts]
  (if (serves? eng kind)
    (store/with-tx (:storage eng)
      (fn [tx]
        (vec (store/query-rows (:storage eng) tx kind where
                               (merge {:limit row-page} opts)))))
    []))

(defn- shelvings-of
  "The reasons this ticket's shelvings recorded, oldest first."
  [eng ticket-id]
  (let [st (:storage eng)]
    (into []
          (comp (filter #(contains? #{"shelve" "turn_back"} (text (:action %))))
                (map #(some-> (fget (:inputs %) :shelved_because) str not-empty)))
          (store/with-tx st
            (fn [tx]
              (store/transitions st tx {:kind :ticket :resource-id (str ticket-id)}
                                 {:limit row-page}))))))

(defn- world
  "What `find-cracks` reads, from the store. A kind a seat walks, and
  the ticket and change kinds, are read in every state; any other kind
  only in the states that have an expected age. Each read is the
  oldest `row-page` rows of one state."
  [eng]
  (let [resources (inv/resources eng)
        seat-rows (rows-in eng :seat {:state :active} {})
        whole (into #{:ticket :change}
                    (keep #(some-> (get-in % [:data :walk]) text not-empty keyword))
                    seat-rows)
        rows (into {}
                   (keep (fn [[kind rdef]]
                           (let [states (if (contains? whole kind)
                                          (:states rdef)
                                          (filter #(expected-age rdef %) (:states rdef)))]
                             (when (seq states)
                               [kind (into []
                                           (mapcat #(rows-in eng kind {:state (keyword (text %))} {}))
                                           states)]))))
                   resources)
        born (into #{}
                   (keep (fn [row]
                           (let [b (str (get-in row [:data :born_from]))]
                             (when (str/starts-with? b "ticket:")
                               (subs b (count "ticket:"))))))
                   (:change rows))]
    {:rdefs (vec (vals resources))
     :seats seat-rows
     :rows rows
     :schedules (if (serves? eng :schedule)
                  (into {}
                        (keep (fn [seat]
                                (when-some [s (schedules/schedule-for-seat eng (:id seat))]
                                  [(str (:id seat)) s])))
                        seat-rows)
                  {})
     :sittings (into {}
                     (map (fn [seat]
                            [(str (:id seat))
                             (into []
                                   (comp (map :data) (filter :outcome))
                                   (rows-in eng :sitting
                                            {:seat (str (:id seat)) :state :closed}
                                            {:limit 20 :newest-first true}))]))
                     seat-rows)
     :shelvings (into {}
                      (comp (filter #(and (= "draft" (text (:state %)))
                                          (some-> (get-in % [:data :shelved_because])
                                                  str not-empty)))
                            (map (fn [row] [(str (:id row)) (shelvings-of eng (:id row))])))
                      (:ticket rows))
     :tickets (if (serves? eng :ticket)
                (store/with-tx (:storage eng)
                  (fn [tx]
                    (into {}
                          (keep (fn [tid]
                                  (when-some [t (store/load-row (:storage eng) tx :ticket tid {})]
                                    [tid t])))
                          born)))
                {})
     :withheld (into {}
                     (keep (fn [seat]
                             (when (some-> (get-in seat [:data :walk]) text not-empty)
                               [(str (:id seat))
                                (into #{} (map str) (seats/unwalkable-rows eng seat nil))])))
                     seat-rows)
     :now ((:now-fn eng))}))

(defn- standing-tickets
  "The newest tickets of every state that still stands for a crack."
  [eng]
  (into []
        (comp (remove #(contains? closed-states (text %)))
              (mapcat #(rows-in eng :ticket {:state (keyword (text %))}
                                {:newest-first true})))
        (:states (get (inv/resources eng) :ticket))))

(defn- file!
  "One crack's draft ticket, filed as the engine. The key carries the
  day, so a crack is filed at most once a day whatever the read of the
  standing tickets missed. → true when filed, nil when refused."
  [eng c now]
  (try
    (inv/create! eng :ticket
                 (assoc (ticket-of c)
                        :repo (get-in eng [:services :cracks :repo] "ckopsa/waymark"))
                 {:principal schedules/system-actor
                  :idempotency-key (str "crack:" (:key c) ":"
                                        (subs (str now) 0 (min 10 (count (str now)))))})
    true
    (catch Exception e
      (warn! "the ticket for " (:key c) " would not file — " (ex-message e))
      nil)))

(defn- woken-at
  "When the seat this crack names was last woken on it, read from the
  seat's own log: a recorded `fire` whose text carries the crack's key.
  A fire older than the row's last write was for an earlier age of the
  row and does not count."
  [eng c]
  (let [st (:storage eng)
        since (inst (:since c))
        mark (str "\"" (:key c) "\"")]
    (some (fn [t]
            (let [at (inst (:at t))]
              (when (and at
                         (= "fire" (text (:action t)))
                         (str/includes? (str (fget (:inputs t) :text)) mark)
                         (not (and since (.isBefore at since))))
                at)))
          (store/with-tx st
            (fn [tx]
              (store/transitions st tx
                                 {:kind :seat :resource-id (get-in c [:wake :seat])}
                                 {:limit row-page :newest-first true}))))))

(defn- wake!
  "Wake the seat this crack names on the crack's row, by the seat's own
  `fire` door as the engine. The key carries the row's last write, so a
  row is woken on at most once for each time it ages. → true when the
  fire went out, nil when the door refused."
  [eng c]
  (try
    (inv/invoke! eng :seat (get-in c [:wake :seat]) :fire
                 {:text (wake-text c)}
                 {:principal schedules/system-actor
                  :idempotency-key (str "crack:" (:key c) ":" (:since c))})
    true
    (catch Exception e
      (warn! "seat " (get-in c [:wake :name]) " would not be woken on "
             (:key c) " — " (ex-message e))
      nil)))

(def ^:private waits-for
  "What `wakes/wake-held` answers that holds a wake for a later pass:
  the damper (a run is up, or one just went out), a throttled Routine,
  and a row a run already has. They lift by themselves."
  #{:damped :throttled :spoken_for})

(defn- send!
  "One crack's note, by `route`: a wake for the seat that holds the row,
  or the draft ticket. A wake the damper holds (`waits-for`) sends
  nothing and waits for a later pass. A seat held otherwise, or whose
  door refuses the wake, was tried all the same, and the draft says
  so. → true when a note went out."
  [eng c now]
  (let [[door c] (route c (when (:wake c) (woken-at eng c)) now)]
    (case door
      :wait nil
      :file (file! eng c now)
      :wake (let [held (wakes/wake-held eng (get-in c [:wake :seat])
                                        (wake-text c) now)]
              (cond
                (contains? waits-for held) nil
                (and (nil? held) (wake! eng c)) true
                :else (file! eng
                             (assoc c :tried (str (get-in c [:wake :name])
                                                  ", which would not be woken"))
                             now))))))

(defn pass!
  "One pass: read the rows, find the cracks, and send a note for each
  one no standing ticket names — a wake for the seat that holds the
  row, or a draft ticket — at most `file-cap` of them. A crack that
  waits on a woken seat sends nothing and takes none of the cap. → how
  many notes went out."
  [eng]
  (if-not (and (serves? eng :ticket) (serves? eng :seat))
    0
    (let [w (world eng)
          fresh (unfiled (find-cracks w) (standing-tickets eng))]
      (reduce (fn [n c]
                (if (<= (long file-cap) (long n))
                  (reduced n)
                  (if (send! eng c (:now w)) (inc (long n)) n)))
              0
              fresh))))

(defn start-sweeper!
  "Every `:interval-ms`, `pass!`. The first pass is one interval after
  the start. Returns the handle `stop-sweeper!` takes."
  [eng {:keys [interval-ms] :or {interval-ms 900000}}]
  (let [stop (CountDownLatch. 1)
        t (Thread. ^Runnable
                   (fn []
                     (loop []
                       (when-not (.await stop (long interval-ms)
                                         TimeUnit/MILLISECONDS)
                         (try (pass! eng)
                              (catch Exception e
                                (warn! "the pass failed (" (ex-message e) ")")))
                         (recur))))
                   "waymark10-live-cracks")]
    (doto ^Thread t (.setDaemon true) (.start))
    {:thread t :stop stop}))

(defn stop-sweeper! [{:keys [^CountDownLatch stop]}]
  (some-> stop .countDown)
  nil)
