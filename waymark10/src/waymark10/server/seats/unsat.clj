(ns waymark10.server.seats.unsat
  "A fire that opened no sitting, and a sit that was refused, as the
  seat's own row says them (ticket dfbb48b3).

  A fire of a seat whose sitter row does not exist yet, or that names
  no model, opens no sitting (`seats/open-fire-sitting!`): its key's
  entry carries no `:sitting`, and only the run's own sit would have
  made one. A run that never sat then left nothing behind: no sitting
  to expire as missed, and a key that lapsed without a word.

  `sweep!` is the clock's pass over those entries. One unspent past
  the sit deadline leaves the seat row, as a missed fire's does
  (`wakes/missed-expired!`), and the seat's `unsat_fires` counts it: a
  never_sat with no sitting, carrying the fire's time, the runner link
  and the run's page, and one sentence that says it. The same write
  counts it under the seat health's `never_sat`, and the rollup at a
  close keeps counting it until a sitting starts after it
  (`fold-health`).

  `refused!` is the other half: a sit refused at a real seat stamps
  `last_sit_refusal` {at, reason} on it, the refusal's own name. No
  key and no hash of one is written.

  Both are maintenance writes, `hold-fire-key!`'s spelling: no
  transition, the seat's version untouched."
  (:require [waymark10.time :refer [instant-of]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.seats.fire :as fire]
            [waymark10.server.seats.keys :as seat-keys]
            [waymark10.server.store :as store])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def refusal-reasons
  "The names a refused sit is stamped with: the refusal's own."
  #{"not-a-delegate" "interactive" "no-seat" "stood-down" "modelless"})

(def ^:private says-cap 240)

(defn lapsed?
  "Is `entry` the key of a fire that opened no sitting, still unspent a
  sit deadline after its fire at `now`? A pull fire counts from its
  take, and one no localfire took is no run yet."
  [entry ^Instant now]
  (and (nil? (some-> (:sitting entry) str not-empty))
       (let [^Instant from (instant-of (if (:pull_fire entry)
                                         (:taken_at entry)
                                         (:fired_at entry)))]
         (and (some? from)
              (not (.isAfter (.plusSeconds from (long fire/sit-deadline-seconds))
                             now))))))

(defn says
  "The sentence for `n` fires in a row that opened no sitting, with the
  refusal of the last sit tried, or nil when none was."
  [n refusal]
  (let [s (str "fired " n (if (= 1 (long n)) " time" " times") ", never sat; "
               (if-some [r (some-> (:reason refusal) str not-empty)]
                 (str "last sit refused: " r)
                 "no sit was attempted"))]
    (subs s 0 (min (count s) (long says-cap)))))

(defn- sat-after?
  "Did a sitting of the seat start after `at`? Then the fires counted
  before it are no longer a run of misses."
  [st tx seat-id at]
  (let [^Instant at (instant-of at)
        newest (first (store/query-rows st tx :sitting {:seat (str seat-id)}
                                        {:limit 1 :newest-first true}))
        ^Instant started (some-> newest (get-in [:data :started_at]) instant-of)]
    (boolean (and at started (.isAfter started at)))))

(defn- tally
  "The seat's `unsat_fires` once `gone` are counted on `seat-row`'s."
  [st tx seat-row gone schedule-row]
  (let [was (get-in seat-row [:data :unsat_fires])
        fresh? (or (nil? was) (sat-after? st tx (:id seat-row) (:last_fired_at was)))
        dated (sort-by #(str (:fired_at %)) gone)
        n (+ (if fresh? 0 (long (or (:count was) 0))) (count gone))
        since (if fresh? (:fired_at (first dated)) (or (:since was) (:fired_at (first dated))))
        refusal (get-in seat-row [:data :last_sit_refusal])
        ^Instant refused-at (instant-of (:at refusal))
        ^Instant since-at (instant-of since)
        refusal (when (and refused-at (or (nil? since-at) (not (.isBefore refused-at since-at))))
                  refusal)
        runner (some-> (get-in schedule-row [:data :last_runner]) str not-empty)
        url (some-> (get-in schedule-row [:data :last_run_url]) str not-empty)]
    (cond-> {:count n :says (says n refusal)}
      since (assoc :since (str since))
      (:fired_at (last dated)) (assoc :last_fired_at (str (:fired_at (last dated))))
      runner (assoc :runner_link runner)
      url (assoc :run_url url))))

(defn- counted
  "`health` with `n` more never_sat, born when the seat has none yet."
  [health n at]
  (-> (or health {:sittings 0 :outcomes {} :cost_usd 0M :flags {}
                  :merged_prs 0 :computed_at (str at)})
      (update-in [:outcomes :never_sat] #(+ (long (or % 0)) (long n)))
      (assoc :last_outcome "never_sat")))

(defn- tally!
  "Take `seat-id`'s lapsed sitting-less entries off it and count them,
  in one transaction FOR UPDATE. → how many, or nil."
  [eng seat-id ^Instant now]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (when-some [row (store/load-row st tx :seat (str seat-id) {:for-update true})]
          (let [held (get-in row [:data :fire_keys])
                gone (filterv #(lapsed? % now) held)]
            (when (seq gone)
              (let [sched (when (get (inv/resources eng) :schedule)
                            (some->> (get-in row [:data :schedule]) str not-empty
                                     (#(store/load-row st tx :schedule % {}))))
                    unsat (tally st tx row gone sched)]
                (store/maintain! :credential st tx :seat (str seat-id)
                                 (-> (:data row)
                                     (assoc :fire_keys (into [] (remove (set gone)) held)
                                            :unsat_fires unsat)
                                     (update :health counted (count gone) now))
                                 (:next-flip-at row))
                (count gone)))))))))

(defn sweep!
  "The clock's pass: every active seat's fires that opened no sitting
  and were not sat by the deadline are taken off and counted
  (`tally!`). A seat that cannot be counted is left for the next pass.
  → how many fires were counted."
  [eng]
  (if-not (get (inv/resources eng) :seat)
    0
    (let [st (:storage eng)
          ^Instant now ((:now-fn eng))
          rows (store/with-tx st
                 (fn [tx] (store/query-rows st tx :seat {:state :active} {:limit 500})))]
      (reduce + 0
              (for [row rows
                    :when (some #(lapsed? % now) (get-in row [:data :fire_keys]))]
                (long (or (try (tally! eng (:id row) now)
                               (catch Exception _ nil))
                          0)))))))

(defn fold-health
  "`health`, the rollup over the seat's closed sittings, with its run of
  sitting-less fires counted as never_sat while no sitting started
  after the last of them. `started` is the newest one's start."
  [health seat-row started]
  (let [u (get-in seat-row [:data :unsat_fires])
        n (long (or (:count u) 0))
        ^Instant last (instant-of (:last_fired_at u))
        ^Instant s (instant-of started)]
    (if (or (zero? n) (nil? last) (and s (.isAfter s last)))
      health
      (-> health
          (update-in [:outcomes :never_sat] #(+ (long (or % 0)) n))
          (assoc :last_outcome "never_sat")))))

(defn refused!
  "Stamp `last_sit_refusal` {at, reason} on the seat a refused sit
  reached: `seat`, a row, or the name or id the sit named
  (`seat-keys/seat-named`). Best-effort: a stamp that cannot be written
  never changes the refusal. → `answer`."
  [eng seat reason answer]
  (try
    (when-some [row (if (string? seat) (seat-keys/seat-named eng seat) seat)]
      (when (contains? refusal-reasons reason)
        (let [st (:storage eng)]
          (store/with-tx st
            (fn [tx]
              (when-some [r (store/load-row st tx :seat (str (:id row)) {:for-update true})]
                (store/maintain! :stamp st tx :seat (str (:id row))
                                 (assoc (:data r) :last_sit_refusal
                                        {:at (str ((:now-fn eng))) :reason reason})
                                 (:next-flip-at r))))))))
    (catch Exception _ nil))
  answer)
