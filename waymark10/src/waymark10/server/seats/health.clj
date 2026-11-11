(ns waymark10.server.seats.health
  "A sitting's judgment, its counters and its seat's health, carved out
  of `waymark10.server.seats` (ticket b44dbe85, round three): what a
  closed sitting came to (`sitting-health`, its `outcome` and `flags`,
  and `health-thresholds`, the numbers it is judged by), the
  maintenance writes an open sitting's calls make (`bump-counter!`,
  `stamp-call!`, `claim-sitting!`, `stamp-client!`,
  `add-cancelled-run!`, `add-served!`) with the half-life renewal they
  ride on, the correction a person's write charges a closed sitting
  with (`count-correction!`), and the seat's own rollup with the breach
  tickets it files (`roll-health!`, `health-breach`, the seat health
  promises). They are one group: the rollup reads the stamps the
  counters write and the outcomes the judge stamps, and nothing above
  them in seats.clj calls into them but the close.

  seats.clj keeps a one-line def for each var another namespace or its
  own resource calls, so every `seats/` alias still resolves. This file
  requires nothing of seats: seats requires it."
  (:require [clojure.string :as str]
            [waymark10.modules.hooks :as hooks]
            [waymark10.server.invoke :as inv]
            [waymark10.server.quiet :as quiet]
            [waymark10.server.seats.fire :refer [seats-actor]]
            [waymark10.server.seats.holds :as seat-holds]
            [waymark10.server.seats.inbox :refer [->instant now-of renewal-due?
                                                  interactive-seat?]]
            [waymark10.server.seats.ledger :refer [cost-scale]]
            [waymark10.server.store :as store])
  (:import (java.math RoundingMode)))

(set! *warn-on-reflection* true)

(def outcomes
  "What a closed sitting came to (seat health 1, ticket fad586b7): ONE
  value, the first of these that holds, in this order. `sitting-health`
  is the judge, and nothing else writes the field. A takeover's
  `superseded` comes before all of them and is no part of seat health:
  `sitting-outcomes` is the field's whole vocabulary."
  ["submitted" "stalled" "never_sat" "refused_out" "completed"
   "cut_short" "idle"])

(def health-thresholds
  "The numbers seat health judges a sitting by, in ONE place. The
  `outcome` and `flags` field help spell these same values, and piece 2
  and the Seat Health page read them here rather than restating them."
  {;; a hook close under this many turns is `cut_short`
   :cut-short-turns 10
   ;; more bench.test calls than this is `test_thrash`
   :test-calls 6
   ;; more bench.read plus bench.find bytes than this is `read_heavy`
   :read-bytes 150000
   ;; …and so is more than this share of the bytes served, dropped
   :dropped-share 1/2
   ;; this many earlier unsubmitted sittings on one row is `rewalk`
   :rewalk-sittings 2
   ;; a sitting that cost more dollars than this is `over_budget`
   :cost-usd 3M
   ;; this many refusals or more is `refusals_high`
   :refusals 5
   ;; this many identical calls or more, one tool with the same
   ;; arguments, is `poll_loop` (ticket c785073c)
   :poll-calls 10
   ;; how far back `backfill-health!` judges the sittings closed
   ;; before the close judged them
   :backfill-days 7
   ;; the closed sittings a seat's `health` is rolled over
   :window 10})

(def health-alert-defaults
  "The rules each close judges a seat's health by (seat health 3,
  ticket 698f6818); since seat S5 no seat states its own.
  `health-breach` is the judge."
  {;; a share of submits under this is a breach…
   :submit_rate_below 0.4M
   ;; one sitting in the window that never sat is a breach
   :never_sat_any true
   ;; this many refused_out sittings in a row, newest first
   :refused_out_run 2
   ;; this many cut_short sittings in a row
   :cut_short_run 3
   ;; this many sittings in a row that walked nothing, while rows of the
   ;; kind the seat walks wait outside its grant
   :walked_nothing_run 3
   ;; this many sittings in a row that carry the flag
   :flag_run {:test_thrash 3 :rewalk 2 :poll_loop 3}})

(def ^:private alert-rate-sittings
  "…judged only once the window holds this many sittings: a rate over
  fewer says nothing yet."
  6)

(def sitting-outcomes
  "Every value a sitting's `outcome` may hold: a takeover's `superseded`,
  then `outcomes`."
  (into ["superseded"] outcomes))

(def ^:private outcome-choices
  {"superseded" "Superseded — it went silent and a new sitting of its seat took its rows"
   "submitted" "Submitted — it submitted a change"
   "stalled" "Stalled — it stalled a change"
   "never_sat" "Never sat — a missed fire, or a run that took no turn"
   "refused_out" "Refused out — its last write was a refusal"
   "completed" "Completed — it wrote a verdict and closed"
   "cut_short" "Cut short — the hook closed it after a few turns"
   "idle" "Idle — it walked rows and moved nothing"})

(def ^:private outcome-help
  (str "Written by the engine at the close; the first that holds. superseded: a new sitting of its seat took its rows after it was silent (closed_by takeover, before leases 3b). submitted: a delivery the seat names in `delivers` (a change submit unless it says otherwise) was made under this sitting. stalled: a change was stalled. never_sat: a missed fire, or no turns and nothing served. refused_out: no transition follows its last refusal, and it is not a sitting whose later call answered and whose close carries a note. cut_short: closed by the hook in under "
       (:cut-short-turns health-thresholds)
       " turns, in a sitting its sit handed rows that moved something, refused something, or whose last call answered nothing: stopped mid-work, not merely brief. idle: none of these; it moved nothing, or its sit handed it no rows (`walked_nothing`), or it moved nothing, refused nothing and its last call answered."))

(def ^:private flags-help
  (let [{:keys [test-calls read-bytes dropped-share rewalk-sittings
                cost-usd refusals poll-calls]} health-thresholds]
    (str "Written by the engine at the close; any number of them. test_thrash: more than "
         test-calls " bench.test calls, or a cancelled run. read_heavy: bench.read plus bench.find over "
         read-bytes " bytes, or more than " dropped-share
         " of the bytes served dropped. rewalk: a row it walked was walked by "
         rewalk-sittings " or more earlier sittings of the seat that did not submit. over_budget: it cost over $"
         cost-usd ". refusals_high: " refusals
         " or more refusals. poll_loop: " poll-calls
         " or more identical calls, one tool with the same arguments. A refused_out sitting also carries refused:<type> and refused_by:<guard>, and so does one that a later answered call spared from refused_out.")))

;; ── seat health: the outcome and the flags (ticket fad586b7) ────────
;; Pure over the sitting's document, the transitions made under it and
;; the earlier sittings of its seat. No model reads any of it.

(defn- moved?
  "Do the sitting's transitions hold this action on this kind?"
  [transitions kind action]
  (boolean (some #(and (= kind (some-> (:kind %) name))
                       (= action (some-> (:action %) name)))
                 transitions)))

(defn- change-moved?
  "Do the sitting's transitions hold this action on a change?"
  [transitions action]
  (moved? transitions "change" action))

(def default-delivers
  "What a seat that states no `delivers` delivers: a change's submit."
  [{:kind "change" :action "submit"}])

(defn delivered?
  "Do the sitting's transitions hold one of the seat's deliveries?
  `delivers` is the seat's field; none stated means `default-delivers`."
  [transitions delivers]
  (boolean (some #(moved? transitions
                          (some-> (:kind %) name)
                          (some-> (:action %) name))
                 (or (seq delivers) default-delivers))))

(defn- refused-last?
  "Was the newest refusal the sitting's last write: no transition under
  it is newer than `last_refusal`. A bench write that was allowed
  leaves no stamp of its own, so the transitions are the only later
  writes the row can show."
  [data transitions]
  (when-some [^java.time.Instant at (->instant (get-in data [:last_refusal :at]))]
    (not-any? (fn [t]
                (when-some [^java.time.Instant moved (->instant (:at t))]
                  (.isAfter moved at)))
              transitions)))

(defn- answered-since?
  "Did the sitting recover from its newest refusal and end cleanly: a
  call answered after it (`last_answer_at` is newer than
  `last_refusal`), and the close carries a note. A seat that retried
  once, read what it came for and said it had nothing to do was not
  stopped by the refusal."
  [data]
  (let [^java.time.Instant refused (->instant (get-in data [:last_refusal :at]))
        ^java.time.Instant answered (->instant (:last_answer_at data))]
    (boolean (and refused answered
                  (.isAfter answered refused)
                  (some-> (:note data) str not-empty)))))

(defn- answered-last?
  "Did the sitting's last call answer (ticket 11a219d7): it carries a
  `last_answer_at`, and no `last_call_at` newer than it. An answered
  call stamps both in one write, the answer a moment after the call; a
  call that answered nothing moves `last_call_at` alone."
  [data]
  (let [^java.time.Instant called (->instant (:last_call_at data))
        ^java.time.Instant answered (->instant (:last_answer_at data))]
    (boolean (and answered
                  (or (nil? called) (not (.isAfter called answered)))))))

(defn- served-of
  "One count of `served` (`:calls`, `:bytes`, `:dropped`), summed over
  the named tools, or over every tool when none is named."
  [served k tools]
  (reduce + 0 (map #(long (or (get-in served [% k]) 0))
                   (or tools (keys served)))))

(defn- rewalked?
  "Was a row this sitting was handed already handed to `:rewalk-sittings`
  or more of `earlier` — the documents of its seat's earlier sittings —
  that were judged and did not submit? Each document carries `:handed`,
  the rows its leases held (`with-handed`). A sitting that carries no
  outcome was never judged, and is not counted."
  [data earlier]
  (let [unsubmitted (filter #(when-some [o (some-> (:outcome %) name)]
                               (not= "submitted" o))
                            earlier)
        walks (frequencies (mapcat #(distinct (map str (:handed %)))
                                   unsubmitted))]
    (boolean (some #(<= (long (:rewalk-sittings health-thresholds))
                        (long (get walks (str %) 0)))
                   (:handed data)))))

(defn sitting-health
  "A closed sitting's `outcome` and `flags`, from its document, the
  transitions made under it (`sitting-transitions`' rows) and the
  documents of its seat's earlier sittings. `outcomes` names the order
  the first match is taken in, and `health-thresholds` the numbers.
  `delivers` is the seat's own field: the kind+action pairs that count
  as `submitted`, a change's submit when the seat states none.
  → {:outcome str :flags [str …]}."
  [data transitions earlier & [delivers]]
  (let [{:keys [cut-short-turns test-calls read-bytes dropped-share
                cost-usd refusals poll-calls]} health-thresholds
        closed (some-> (:closed_by data) name)
        turns (long (or (:turns data) 0))
        served (:served data)
        refusal (:last_refusal data)
        refused-last (refused-last? data transitions)
        ;; a later call answered and the close said so: not refused out
        recovered (boolean (and refused-last (answered-since? data)))
        outcome (cond
                  (= "takeover" closed) "superseded"
                  (delivered? transitions delivers) "submitted"
                  (change-moved? transitions "stall") "stalled"
                  (or (= "missed" closed) (true? (:missed data))
                      (and (zero? turns) (empty? served))) "never_sat"
                  (and refused-last (not recovered)) "refused_out"
                  ;; a judge's work is its verdict, however few its turns,
                  ;; when its seat declares no delivers; a declared
                  ;; delivery still decides (e2a379e0)
                  (and (empty? delivers)
                       (moved? transitions "verdict" "judge")) "completed"
                  ;; a sit that handed no rows, and a sitting that moved
                  ;; nothing after, left no work to cut short (ticket
                  ;; 70aca5e6): it is idle, however few its turns
                  (and (true? (:walked_nothing data))
                       (empty? transitions)) "idle"
                  ;; a sitting that moved nothing, refused nothing and
                  ;; whose last call answered read its rows and rightly
                  ;; left them: brief, not stopped mid-work (11a219d7)
                  (and (empty? transitions) (nil? refusal)
                       (answered-last? data)) "idle"
                  (and (= "hook" closed) (< turns cut-short-turns)) "cut_short"
                  :else "idle")]
    {:outcome outcome
     :flags (cond-> []
              (or (> (served-of served :calls [:bench__test]) test-calls)
                  (pos? (long (or (:cancelled_runs data) 0))))
              (conj "test_thrash")

              (or (> (served-of served :bytes [:bench__read :bench__find])
                     read-bytes)
                  (> (served-of served :dropped nil)
                     (* dropped-share (served-of served :bytes nil))))
              (conj "read_heavy")

              (rewalked? data earlier) (conj "rewalk")

              (some-> (:cost_usd data) bigdec (> cost-usd))
              (conj "over_budget")

              (>= (long (or (:refusals data) 0)) refusals)
              (conj "refusals_high")

              (>= (long (or (:repeat_calls data) 0)) poll-calls)
              (conj "poll_loop")

              ;; taken over before its hook ever tallied: the counts it
              ;; was closed with are zeroes, not a report (e3add1a6)
              (and (= "takeover" closed) (nil? (:tallied_at data)))
              (conj "unreported")

              ;; which law refused it out, beside the outcome; a sitting
              ;; that recovered keeps the record of what refused it
              (or (= "refused_out" outcome)
                  (and recovered
                       (contains? #{"completed" "cut_short" "idle"} outcome)))
              (into (keep (fn [[label v]]
                            (when-some [s (some-> v str not-empty)]
                              (str label ":" s))))
                    [["refused" (:type refusal)]
                     ["refused_by" (:guard refusal)]]))}))

(def ^:private seat-health-page
  "The most of one seat's sittings a close reads for `rewalk`, newest
  first. A row walked again after this many sittings is past the page."
  100)

(def ^:private earlier-sittings seat-holds/earlier-sittings)

(def ^:private with-handed seat-holds/with-handed)

(defn stamp-health
  "The close's last write: `outcome` and `flags`, judged from the row as
  the close leaves it. The transitions are read by the sitting's grant
  from its start to now, and the seat's earlier sittings through the
  write's own transaction. The seat's `delivers` says what counts as
  submitted. A ctx with no such hook judges the row alone."
  [row ctx]
  (let [data (:data row)
        grant (some-> (:grant data) str not-empty)
        under (:transitions-under ctx)
        find-rows (:find ctx)
        read' (:read ctx)
        seat (when read'
               (some->> (:seat data) str not-empty (read' :seat)))
        moved (when (and grant under)
                (under grant (->instant (:started_at data)) nil))
        seat-rows (when find-rows
                    (find-rows :sitting {:seat (str (:seat data))}
                               {:limit seat-health-page :newest-first true}))]
    (let [[judged earlier] (with-handed find-rows (:id row) data
                             (earlier-sittings seat-rows (:id row) data))]
      (update row :data merge
              (sitting-health judged moved earlier
                              (get-in seat [:data :delivers]))))))

;; ── the sitting's counters and stamps ──────────────────────────────

(defn- call-stamp
  "The moment a counted call lands, as the maintenance writes store it
  (ticket 086307f2): the engine's own clock, so a test that moves the
  clock moves this stamp too."
  [eng]
  (str ((or (:now-fn eng) #(java.time.Instant/now)))))

(def ^:private seat-of-sitting seat-holds/seat-of-sitting)

(def ^:private ^:dynamic *renewal-due*
  "While a stamp runs under `with-renewal`: what its `judge-renewal!`
  found."
  nil)

(defn- judge-renewal!
  "Judge the half-life rule (docs/spec-leases.md §2) inside a stamp's
  own transaction, on the row it holds for update, for `with-renewal`
  to act on once that write has committed."
  [eng tx row]
  (when *renewal-due*
    (vreset! *renewal-due* (renewal-due? row (now-of eng)
                                         (seat-of-sitting eng tx row)))))

(defn- with-renewal
  "Run `stamp`, a maintenance write on one sitting that calls
  `judge-renewal!`, and once the write has committed make the renewal
  it judged due: the sitting's own `renew` door, in its own
  transaction, as the engine. Never inside, because `renew` takes the
  same row lock and would wait on its own transaction. A renewal the
  door refuses (the lease lapsed in between) leaves the call it rode on
  standing. → what `stamp` answered.

  A quiet engine (server/quiet) runs neither: a read it serves keeps
  no sitting's bookkeeping, and every stamp answers nil."
  [eng sitting-id stamp]
  (when-not (quiet/quiet? eng)
    (let [due (volatile! false)
          out (binding [*renewal-due* due] (stamp))]
      (when @due
        (try (inv/invoke! eng :sitting (str sitting-id) :renew nil
                          {:principal seats-actor})
             (catch clojure.lang.ExceptionInfo _ nil)))
      out)))

(declare bump-counter* stamp-call* add-served*)

(defn bump-counter!
  "Add one to an open sitting's `:transitions` or `:refusals`. A
  MAINTENANCE write — document only, version untouched, no transition
  (see the ns docstring). → the new count, or nil when there was
  nothing to count: an unknown id, a sitting already closed, or a
  counter this kind does not keep.

  With a `refusal` ({:type :guard}) on a `:refusals` count, the same
  write stamps `:last_refusal` — the problem type, the guard name when
  one refused, and the moment — so the close can read which law the
  newest refusal was, not only how many there were."
  ([eng sitting-id counter] (bump-counter! eng sitting-id counter nil))
  ([eng sitting-id counter refusal]
   (with-renewal eng sitting-id #(bump-counter* eng sitting-id counter refusal))))

(defn- bump-counter*
  "`bump-counter!`'s write, in its own transaction."
  ([eng sitting-id counter refusal]
   (when (and sitting-id
              (contains? #{:transitions :refusals} counter)
              (get (inv/resources eng) :sitting))
     (store/with-tx (:storage eng)
       (fn [tx]
         (when-some [row (store/load-row (:storage eng) tx :sitting
                                         (str sitting-id) {:for-update true})]
           (when (= :open (:state row))
             (judge-renewal! eng tx row)
             (let [n (inc (long (or (get (:data row) counter) 0)))
                   stamp (when (and refusal (= :refusals counter))
                           (cond-> {:type (some-> (:type refusal) str not-empty)
                                    :at (str (java.time.Instant/now))}
                             (some? (:guard refusal))
                             (assoc :guard (let [g (:guard refusal)]
                                             (if (keyword? g) (name g) (str g))))))]
               (store/maintain! :counter (:storage eng) tx :sitting (str sitting-id)
                                   (cond-> (assoc (:data row) counter n
                                                  :last_call_at (call-stamp eng))
                                     stamp (assoc :last_refusal stamp))
                                   nil)
               n))))))))

(defn stamp-call!
  "Move an open sitting's `last_call_at` to now and nothing else
  (ticket 900764ce): a READ through the router is activity the idle
  sweep must see, but it is neither a transition nor a refusal, and
  `served` is the MCP door's per-tool ledger. The same MAINTENANCE
  write as `bump-counter!` — document only, version untouched.

  `answered?` is optional (ticket 3ff10737): true when the read was
  not refused, and then the same write stamps `last_answer_at`, as
  `add-served!` does for the MCP door. That stamp is the wall clock's,
  because the close compares it with `last_refusal`'s.

  → the stamp, or nil when there was nothing to stamp: no id, an
  unknown id, a sitting already closed, or a kind this engine does not
  serve."
  ([eng sitting-id] (stamp-call! eng sitting-id false))
  ([eng sitting-id answered?]
   (with-renewal eng sitting-id #(stamp-call* eng sitting-id answered?))))

(defn- stamp-call*
  "`stamp-call!`'s write, in its own transaction."
  ([eng sitting-id answered?]
   (when (and sitting-id (get (inv/resources eng) :sitting))
     (store/with-tx (:storage eng)
       (fn [tx]
         (when-some [row (store/load-row (:storage eng) tx :sitting
                                         (str sitting-id) {:for-update true})]
           (when (= :open (:state row))
             (judge-renewal! eng tx row)
             (let [at (call-stamp eng)]
               (store/maintain! :stamp (:storage eng) tx :sitting (str sitting-id)
                                   (cond-> (assoc (:data row) :last_call_at at)
                                     answered?
                                     (assoc :last_answer_at
                                            (str (java.time.Instant/now))))
                                   nil)
               at))))))))

(defn stamp-client!
  "Stamp an open sitting with what its connector session's client
  declared at initialize (ticket b9f90987): `client_name`,
  `client_version`, `app_ui`, and `app_tools`, whether that session's
  tool listing carried the app tools as the sit judged it. The sit
  writes all four at the bind and again at each re-sit, so the row
  reads as the session that last sat in it. The same MAINTENANCE write
  as `stamp-call!`. → the row as stamped, or nil: no id, an unknown
  id, or a closed sitting."
  [eng sitting-id {:keys [client-name client-version app-ui app-tools]}]
  (when (and sitting-id (get (inv/resources eng) :sitting))
    (store/with-tx (:storage eng)
      (fn [tx]
        (when-some [row (store/load-row (:storage eng) tx :sitting
                                        (str sitting-id) {:for-update true})]
          (when (= :open (:state row))
            (let [said (fn [v]
                         (when-some [s (some-> v str not-empty)]
                           (subs s 0 (min 200 (count s)))))
                  data (assoc (:data row)
                              :client_name (said client-name)
                              :client_version (said client-version)
                              :app_ui (boolean app-ui)
                              :app_tools (boolean app-tools))]
              (store/maintain! :stamp (:storage eng) tx :sitting (str sitting-id)
                                  data nil)
              (assoc row :data data))))))))

(defn add-cancelled-run!
  "Count one cancelled bench.test run on an open sitting (ticket
  39b2c934). A run with an id is counted once: its id joins
  `cancelled_run_ids`, and a later answer naming the same run leaves
  the count where it is. A run with no id is counted each time, which
  is the most an unnamed run can say. `bump-counter!`'s maintenance
  write. → the count, or nil when there was nothing to count on."
  [eng sitting-id run-id]
  (when (and sitting-id (get (inv/resources eng) :sitting))
    (store/with-tx (:storage eng)
      (fn [tx]
        (when-some [row (store/load-row (:storage eng) tx :sitting
                                        (str sitting-id) {:for-update true})]
          (when (= :open (:state row))
            (let [run (some-> run-id str not-empty)
                  seen (vec (:cancelled_run_ids (:data row)))
                  n (long (or (:cancelled_runs (:data row)) 0))]
              (if (and run (some #{run} seen))
                n
                (let [n (inc n)]
                  (store/maintain! :counter (:storage eng) tx :sitting (str sitting-id)
                                      (cond-> (assoc (:data row) :cancelled_runs n)
                                        run (assoc :cancelled_run_ids (conj seen run)))
                                      nil)
                  n)))))))))

(def ^:private backfill-page
  "The most closed sittings one `backfill-health!` pass reads, newest
  first: a week of every seat's wakes, with room."
  2000)

(defn backfill-health!
  "Stamp `outcome` and `flags` on the closed sittings that carry none
  and started in the last `:backfill-days` days: the rows closed before
  the close judged them (ticket fad586b7). Oldest first, so `rewalk`
  reads earlier sittings this pass has already judged. A MAINTENANCE
  write, `bump-counter!`'s spelling. A judged row is never judged
  again, so a second pass writes nothing. → the number stamped."
  [eng]
  (if (get (inv/resources eng) :sitting)
    (let [st (:storage eng)
          ^java.time.Instant now ((or (:now-fn eng) #(java.time.Instant/now)))
          since (.minus now (java.time.Duration/ofDays
                             (long (:backfill-days health-thresholds))))
          started #(->instant (get-in % [:data :started_at]))
          due (store/with-tx st
                (fn [tx]
                  (->> (store/query-rows st tx :sitting {:state :closed}
                                         {:limit backfill-page
                                          :newest-first true})
                       (filter (fn [row]
                                 (when-some [^java.time.Instant at (started row)]
                                   (and (nil? (get-in row [:data :outcome]))
                                        (.isAfter at since)))))
                       (sort-by started)
                       (mapv (comp str :id)))))]
      (count
       (filterv
        (fn [id]
          (store/with-tx st
            (fn [tx]
              (when-some [row (store/load-row st tx :sitting id
                                              {:for-update true})]
                (let [data (:data row)
                      grant (some-> (:grant data) str not-empty)]
                  (when (and (= :closed (:state row)) (nil? (:outcome data)))
                    (let [moved (when grant
                                  (store/transitions-under-grant
                                   st tx grant
                                   (->instant (:started_at data))
                                   (->instant (:ended_at data))
                                   {}))
                          seat-rows (store/query-rows
                                     st tx :sitting {:seat (str (:seat data))}
                                     {:limit seat-health-page
                                      :newest-first true})
                          seat (some->> (:seat data) str not-empty
                                        (#(store/load-row st tx :seat % {})))
                          [judged earlier] (with-handed
                                            #(store/query-rows st tx %1 %2 %3)
                                            id data
                                            (earlier-sittings seat-rows id data))]
                      (store/maintain! :cache
                       st tx :sitting id
                       (merge data
                              (sitting-health
                               judged moved earlier
                               (get-in seat [:data :delivers])))
                       nil)
                      true)))))))
        due)))
    0))

;; ── seat health 2: the seat's own rollup (ticket 64a835b4) ──────────
;;
;; A sitting says what ONE wake came to. The seat row says what the
;; last few came to, so a page or a mayor reads every seat's health off
;; the seat collection in one call and opens no sitting to do it.

(defn- divided
  "`num` ÷ `den` to `scale` decimal places, or nil when there is nothing
  to divide by. Exact decimals, `cost-of`'s own rule."
  [num den scale]
  (when (pos? (long den))
    (.divide ^java.math.BigDecimal (bigdec num)
             ^java.math.BigDecimal (bigdec den)
             (int scale) RoundingMode/HALF_UP)))

(defn- flag-key
  "The key one flag is counted under. `refused:<type>` counts as
  `refused`: a problem type is an address, and an address is no key.
  `refused_by:<guard>` keeps its guard, because which law refuses a
  seat out is the thing a reader wants."
  [flag]
  (let [s (if (keyword? flag) (subs (str flag) 1) (str flag))]
    (keyword (if (str/starts-with? s "refused:") "refused" s))))

(defn seat-health
  "A seat's `health` over `sittings`: the documents of its last closed
  and judged sittings, newest first. `merged` is how many of the seat's
  changes merged since the oldest of them started, and `at` the moment
  of the count. Pure. → the map the seat row carries."
  [sittings merged at]
  (let [n (count sittings)
        outcome-of #(some-> (:outcome %) name)
        counts (frequencies (keep outcome-of sittings))
        submits (long (get counts "submitted" 0))
        merged (long (or merged 0))
        cost (transduce (keep #(some-> (:cost_usd %) bigdec)) + 0M sittings)]
    {:sittings n
     :outcomes (into {}
                     (map (fn [o] [(keyword o) (long (get counts o 0))]))
                     outcomes)
     :submit_rate (divided submits n 4)
     :cost_usd cost
     :cost_per_submit (divided cost submits cost-scale)
     :flags (frequencies (map flag-key (mapcat :flags sittings)))
     :merged_prs merged
     :cost_per_merge (divided cost merged cost-scale)
     :last_submit_at (some->> sittings
                              (filter #(= "submitted" (outcome-of %)))
                              first
                              :ended_at
                              ->instant
                              str)
     :last_outcome (some-> (first sittings) outcome-of)
     :computed_at (str at)}))

;; ── seat health 3: a breach is told (ticket 698f6818) ───────────────
;;
;; The rollup says how a seat is doing. An alert says when that is bad
;; enough to tell somebody: each close judges `health-alert-defaults`
;; over the window it just rolled. A rule that newly breaks files one
;; draft ticket and is recorded as `health.breach`; a rule still broken
;; at the next close files nothing, and one that passes clears the
;; record.

(defn- flag-name
  "A flag as `flag_run` names it: `flag-key`'s spelling, as a string."
  [flag]
  (subs (str (flag-key flag)) 1))

(defn- walked-nothing?
  "Did this sitting end with nothing walked: its sit handed it no rows
  (`stamp-walked-nothing!`) and it moved nothing after. A hook closes
  such a wake within a few turns, and one closed before ticket 70aca5e6
  reads `cut_short`, so `cut_short` counts beside `idle`."
  [sitting]
  (and (true? (:walked_nothing sitting))
       (contains? #{"idle" "cut_short"} (some-> (:outcome sitting) name))))

(defn submits-change?
  "Can a seat with this `scope` submit a change: does an entry of kind
  `change` hold the `submit` action (ticket 854d448c). A seat that
  cannot never moves its `submit_rate`, so `health-breach` does not
  judge it. Pure."
  [scope]
  (boolean
   (some (fn [e]
           (and (= "change" (some-> (:kind e) name))
                (some #(contains? #{"submit" "*"} (some-> % name))
                      (:actions e))))
         scope)))

(defn submitting-seat?
  "Is this seat's work submitting, so `health-breach` judges its
  `submit_rate`: its scope holds `change.submit` (`submits-change?`)
  and a routine fires it. A seat a person sits in (`interactive-seat?`,
  the mayor) may submit but its work is not submitting, so it is not
  judged on its rate (ticket a7cf5d27). Pure."
  [seat-row]
  (and (not (interactive-seat? seat-row))
       (submits-change? (get-in seat-row [:data :scope]))))

(defn health-breach
  "The alert rule a seat's window breaks, or nil when it breaks none.
  `alerts` is laid over `health-alert-defaults` (a close passes none:
  seat S5 dropped `health_alerts`); `sittings` are the window's documents, newest
  first, and `health` their rollup (`seat-health`). A run is counted
  from the newest sitting back, so one sitting that is not of the run
  ends it. The rules are judged in one order and the first that breaks
  is the answer. `hidden` is how many rows of the kind the seat walks
  wait outside its grant (`hidden-waiting`), and `walked_nothing_run`
  breaks only while there are some. `submits?` false (`submitting-seat?`)
  skips `submit_rate_below`: a seat that cannot submit, or a person's
  seat that only may, is not judged on its rate (tickets 854d448c,
  a7cf5d27). `(:only alerts)`
  names one rule of `health-alert-defaults` and the others are not
  judged: a seat health promise asks after its own rule (R-P.11.3).
  Pure. → the rule's name."
  [alerts health sittings & [hidden submits?]]
  (let [only (some-> (:only alerts) name keyword)
        {:keys [submit_rate_below never_sat_any refused_out_run
                cut_short_run flag_run walked_nothing_run]}
        (cond-> (merge health-alert-defaults
                       (into {} (remove (comp nil? val)) (dissoc alerts :only)))
          only (select-keys [only]))
        outcome? (fn [o] #(= o (some-> (:outcome %) name)))
        run (fn [pred] (count (take-while pred sittings)))
        reached? (fn [limit n] (and (some? limit) (>= (long n) (long limit))))
        rate (:submit_rate health)]
    (cond
      (and (true? never_sat_any) (some (outcome? "never_sat") sittings))
      "never_sat_any"

      (and (pos? (long (or hidden 0)))
           (reached? walked_nothing_run (run walked-nothing?)))
      "walked_nothing_run"

      (reached? refused_out_run (run (outcome? "refused_out")))
      "refused_out_run"

      (reached? cut_short_run (run (outcome? "cut_short")))
      "cut_short_run"

      :else
      (or (some (fn [[flag limit]]
                  (let [flag (flag-name flag)]
                    (when (reached? limit
                                    (run (fn [s] (some #(= flag (flag-name %))
                                                       (:flags s)))))
                      (str "flag_run:" flag))))
                (sort-by #(flag-name (key %)) flag_run))
          (when (and (not (false? submits?))
                     (some? rate) (some? submit_rate_below)
                     (>= (long (:sittings health)) (long alert-rate-sittings))
                     (neg? (compare (bigdec rate) (bigdec submit_rate_below))))
            "submit_rate_below")))))

;; ── seat health 4: rows wait outside the grant (ticket fd930ff1) ────
;;
;; A sitting never learns of the rows its grant hides. The person who
;; owns the seat should: a seat that keeps walking nothing while rows of
;; the kind it walks wait outside its scope entry's filter is leashed
;; too short. The engine counts those rows with its own hand, and the
;; count goes to the ticket and to no answer a sitting reads.

(def ^:private hidden-waiting seat-holds/hidden-waiting)

(defn- hidden-sentence
  "What the person is told of a `walked_nothing_run` breach: how many
  times the seat walked nothing and how many rows waited outside its
  grant. The count and the kind, and no row's id."
  [seat sittings {n :count :keys [state plural fields]}]
  (str (get-in seat [:data :name]) " walked nothing "
       (count (take-while walked-nothing? sittings)) " times while "
       n " " (when state (str state " ")) plural
       " sat outside its grant (" (str/join ", " fields)
       " filter or scope)."))

(defn- breach-detail
  "What the ticket says: the seat, the rule, and each sitting of the
  window with what it came to, its flags and its link. A
  `walked_nothing_run` breach opens with `hidden-sentence`."
  [seat rule sittings hidden]
  (let [data (:data seat)]
    (str (when (and hidden (= "walked_nothing_run" rule))
           (str (hidden-sentence seat sittings hidden) "\n\n"))
         "Seat " (:name data) " (/api/seats/" (:id seat)
         ") broke its health alert `" rule "` at "
         (get-in data [:health :breach :at]) ".\n\n"
         "Its last " (count sittings) " sittings, newest first:\n"
         (str/join
          "\n"
          (map (fn [s]
                 (str "- /api/sittings/" (:id s) " · "
                      (or (some-> (:outcome s) name) "not judged")
                      (when-some [flags (seq (:flags s))]
                        (str " · "
                             (str/join ", " (map #(if (keyword? %)
                                                    (subs (str %) 1)
                                                    (str %))
                                                 flags))))))
               sittings))
         (when (true? (:breaker_open data))
           (str "\n\nThe breaker is open (`breaker_open`): the seat wakes one sitting at a time, and its max_open_sittings stays "
                (:max_open_sittings data)
                ". `close_breaker` on the seat lets it wake them all again.")))))

(defn- file-breach-ticket!
  "The draft ticket a new breach earns, filed as the engine
  (`judgments/file-ticket!`'s posture). Best effort: an engine that
  serves no `ticket` kind files none, and a refusal leaves the breach
  recorded without one. → the ticket's id, or nil."
  [eng seat sittings hidden]
  (when (get (inv/resources eng) :ticket)
    (let [{:keys [rule at]} (get-in seat [:data :health :breach])]
      (try
        (some-> (inv/create! eng :ticket
                             {:title (str "seat " (get-in seat [:data :name])
                                          " health breach: " rule)
                              :detail (breach-detail seat rule sittings hidden)
                              :repo "ckopsa/waymark"
                              :type "bug"
                              :priority 1}
                             {:principal seats-actor
                              :idempotency-key (str "seat-health:" (:id seat)
                                                    ":" rule ":" at)})
                :row :id str)
        (catch Exception _ nil)))))

(defn- stamp-breach-ticket!
  "Write the filed ticket's id beside the breach it was filed for, when
  that breach still stands. The rollup's own MAINTENANCE write.
  → the seat's `health` as written, or nil."
  [eng seat-id rule ticket]
  (let [st (:storage eng)]
    (store/with-tx st
      (fn [tx]
        (when-some [seat (store/load-row st tx :seat seat-id
                                         {:for-update true})]
          (when (= rule (get-in seat [:data :health :breach :rule]))
            (let [health (assoc-in (get-in seat [:data :health])
                                   [:breach :ticket] ticket)]
              (store/maintain! :stamp st tx :seat seat-id
                                  (assoc (:data seat) :health health)
                                  (:next-flip-at seat))
              health)))))))

(defn- merged-since
  "How many of this seat's changes merged at or after `since`: the rows
  of the `change` kind in `merged` whose author is the seat's name,
  read by the moment each row last moved — a merged change is over, so
  that moment is the merge unless a later maintenance write touched the
  row. An engine that serves no such kind has merged none, and so does
  one whose factory module registered no `:factory-merged-changes`."
  [eng tx seat-row ^java.time.Instant since]
  (let [author (some-> (get-in seat-row [:data :name]) str not-empty)]
    (if (and since author (get (inv/resources eng) :change))
      (count
       (filter (fn [row]
                 (when-some [^java.time.Instant at (->instant (:updated-at row))]
                   (not (.isBefore at since))))
               (hooks/hook :factory-merged-changes eng tx author seat-health-page)))
      0)))

(defn- window-sittings
  "The seat's last `(:window health-thresholds)` closed sittings that
  carry an outcome, newest first: each its document, its id riding
  along for the ticket's links."
  [st tx seat-id]
  (let [started #(->instant (:started_at %))]
    (->> (store/query-rows st tx :sitting
                           {:seat seat-id :state :closed}
                           {:limit seat-health-page :newest-first true})
         (map #(assoc (:data %) :id (:id %)))
         (filter #(and (some? (:outcome %)) (started %)))
         (sort-by started #(compare %2 %1))
         (take (:window health-thresholds))
         vec)))

(defn health-default-key
  "What a seat health promise carries as its `default_of`
  (docs/spec-promise.md R-P.11.3): `seat.health.<rule>`, for a key of
  `health-alert-defaults` or a rule `health-breach` answers —
  `flag_run:<flag>` is `flag_run`'s."
  [rule]
  (str "seat.health." (first (str/split (name rule) #":"))))

(defn- health-enforced?
  "Does an enforced, unretired seat health promise stand for this rule
  (R-P.8.2)? Then its breach wakes the holder of the remedy and the old
  alert files nothing, so a seat is never both alerted and breached for
  one rule. False for an engine that serves no `promise`."
  [eng rule]
  (boolean
   (when (and rule (get (inv/resources eng) :promise))
     (let [st (:storage eng)
           k (health-default-key rule)
           field (fn [m f] (some-> (or (get m f) (get m (name f))) name))]
       (some (fn [p]
               (and (= k (field (:data p) :default_of))
                    (= "enforced" (field (:data p) :mode))
                    (not= "retired" (some-> (:state p) name))))
             (store/with-tx st
               #(store/query-rows st % :promise {} {:limit 1000})))))))

(defn roll-health!
  "Write the seat's `health` again, over its last `(:window health-thresholds)` closed
  sittings that carry an outcome (`seat-health`). A sitting closed
  before the close judged it is not in the window. A MAINTENANCE write,
  `bump-counter!`'s spelling: document only, version untouched, no
  transition. Best-effort — a rollup that cannot be counted leaves the
  last one standing and never fails the close that asked for it.

  SEAT HEALTH 3: the same write judges the seat's alerts over the
  window (`health-breach`). A rule that newly breaks is recorded as
  `health.breach` and files one ticket after the write commits. The same rule at the next close keeps its record and files
  nothing; no rule clears it.

  SEAT HEALTH 4: when the newest sitting walked nothing, the rows of
  the walked kind that wait outside the seat's grant are counted in the
  same transaction (`hidden-waiting`) for `walked_nothing_run`. The
  count rides to the ticket and is not written on the seat row.
  → the map written, or nil when there was nothing to write."
  [eng seat-id]
  (when (and seat-id
             (get (inv/resources eng) :seat)
             (get (inv/resources eng) :sitting))
    (try
      (let [st (:storage eng)
            seat-id (str seat-id)
            started #(->instant (:started_at %))]
        (when-some [{:keys [seat sittings health new? hidden]}
                    (store/with-tx st
                      (fn [tx]
                        (when-some [seat (store/load-row st tx :seat seat-id
                                                         {:for-update true})]
                          (let [sittings (window-sittings st tx seat-id)
                                at (call-stamp eng)
                                health (seat-health
                                        sittings
                                        (merged-since eng tx seat
                                                      (some-> (peek sittings) started))
                                        at)
                                was (get-in seat [:data :health :breach])
                                hidden (when (walked-nothing? (first sittings))
                                         (hidden-waiting eng tx seat))
                                rule (health-breach nil health sittings (:count hidden)
                                                    (submitting-seat? seat))
                                new? (and (some? rule)
                                          (not= rule (some-> (:rule was) str)))
                                health (cond-> health
                                         rule (assoc :breach
                                                     (if new?
                                                       {:rule rule :at (str at)}
                                                       was)))
                                data (assoc (:data seat) :health health)]
                            (store/maintain! :cache st tx :seat seat-id data
                                                (:next-flip-at seat))
                            {:seat (assoc seat :data data)
                             :sittings sittings
                             :health health
                             :new? new?
                             :hidden hidden}))))]
          ;; the ticket is its own write, after the rollup's commit; an
          ;; enforced health promise answers for its rule instead
          (or (when (and new? (not (health-enforced? eng (get-in health [:breach :rule]))))
                (when-some [ticket (file-breach-ticket! eng seat sittings hidden)]
                  (stamp-breach-ticket! eng seat-id
                                        (get-in health [:breach :rule])
                                        ticket)))
              health)))
      (catch Exception _ nil))))

(defn health-promise
  "The default promise for one rule of `health-alert-defaults`
  (docs/spec-promise.md R-P.11.3), as its create input: about the
  active seats, holds while the seat's window of sittings breaks no such
  rule (a `health` query, which `rule-breach` answers), judged at every
  sweep, remedy the seat's own `restate`. Born `watch` (R-P.8.2)."
  [rule]
  (let [rule (name rule)]
    {:name (str "a seat's sittings break no " rule " rule")
     :about {:kind "seat" :filter {:state "active"}}
     :holds {:query {:kind "sitting" :health rule :expect "none"}}
     :by {:window 3600}
     :remedy {:kind "seat" :door "restate"}
     :mode "watch"
     :default_of (health-default-key rule)}))

(defn health-promises
  "One `health-promise` for each rule of `health-alert-defaults`."
  []
  (mapv health-promise (sort (map name (keys health-alert-defaults)))))

(defn within-window
  "The `sittings` that started at or after `since`, in their order; all
  of them when `since` is nil. Pure."
  [sittings since]
  (if since
    (filterv #(when-some [s (->instant (:started_at %))]
                (not (neg? (compare s since))))
             sittings)
    sittings))

(defn rule-breach
  "Does this seat's window break `rule` of `health-alert-defaults`: the
  rule's name as `health-breach` answers it, or nil. The window is the
  one `roll-health!` reads and the rollup the `health` it wrote, so the
  promise reads the facts the close stamped. `window` (seconds, the
  promise's `by.window`) and `now` narrow it to the sittings that
  started inside it, and the rollup is counted over those alone: a
  breach clears once its sittings age out (ticket 18137dd5). nil for a
  seat it cannot read."
  [eng seat-id rule & [window now]]
  (when (and seat-id
             (get (inv/resources eng) :seat)
             (get (inv/resources eng) :sitting))
    (let [st (:storage eng)
          since (when (and (number? window) now)
                  (.minusSeconds ^java.time.Instant now (long window)))]
      (store/with-tx st
        (fn [tx]
          (when-some [seat (store/load-row st tx :seat (str seat-id) {})]
            (let [sittings (within-window (window-sittings st tx (str seat-id))
                                          since)
                  hidden (when (walked-nothing? (first sittings))
                           (hidden-waiting eng tx seat))]
              (health-breach {:only rule}
                             (if since
                               (seat-health sittings 0 now)
                               (get-in seat [:data :health]))
                             sittings
                             (:count hidden)
                             (submitting-seat? seat)))))))))

(def ^:private roll-page
  "The most seats of one state one `roll-all-health!` pass reads: every
  seat an engine carries, with room."
  500)

(defn roll-all-health!
  "Roll the `health` of every active and parked seat once
  (`roll-health!`): the boot's pass (ticket bb3e1c1a), so a seat that
  has not closed a sitting since the deploy carries one. A merged or a
  retired seat is left alone. Each seat is its own best-effort write,
  and a second pass counts the same sittings again. → the number of
  seats rolled."
  [eng]
  (if (and (get (inv/resources eng) :seat)
           (get (inv/resources eng) :sitting))
    (let [st (:storage eng)
          ids (store/with-tx st
                (fn [tx]
                  (->> [:active :parked]
                       (mapcat #(store/query-rows st tx :seat {:state %}
                                                  {:limit roll-page}))
                       (mapv (comp str :id)))))]
      (count (filterv #(some? (roll-health! eng %)) ids)))
    0))

(defn after-write
  "The engine's `:maintain` hook, this module's arm (seat health 2): a
  committed write that leaves a sitting closed rolls its seat's
  `health`. It answers nil for every write, so the composition keeps
  the row the passes before it decided on. Since seat S5 the window
  is `health-thresholds`' own, so no restate moves it and none rolls
  (ticket 8bda4005's case is gone)."
  [eng kind _action-name res]
  (cond
    (and (= :sitting kind)
         (= "closed" (some-> (get-in res [:row :state]) name)))
    (roll-health! eng (get-in res [:row :data :seat])))
  nil)

(defn- a-persons-write?
  "A logged actor a person answers for: a human, or a held call a person
  allowed (`allowed_by`) — the ledger's own reading (R-11.3)."
  [actor]
  (or (= "human" (some-> (:type actor) name))
      (some? (:allowed_by actor))))

(defn count-correction!
  "R-11.3 on the sitting's own row: when the transition just committed on
  `kind`/`resource-id` is a person's and the one before it was a
  sitter's, the CLOSED sitting that wore that transition's grant gains
  one `corrections` and the row's id in `corrected_rows`. The same
  MAINTENANCE write as `bump-counter!` — document only, no transition.
  A second person's transition reads a person before it and adds
  nothing; a row no sitter touched has no grant to find. Framework
  kinds (`:nav :system`) are left out, as the ledger leaves them out.
  → the new count, or nil when nothing was corrected."
  [eng kind resource-id]
  (let [st (:storage eng)
        rdef (get (inv/resources eng) kind)]
    (when (and resource-id rdef
               ;; a sitting's own doors are not the seat's work, as
               ;; count-committed! reads them
               (not= :sitting kind)
               (not= :system (:nav rdef))
               (get (inv/resources eng) :sitting))
      (let [[after before] (store/with-tx st
                             (fn [tx]
                               (store/transitions st tx {:kind kind
                                                         :resource-id (str resource-id)}
                                                  {:newest-first true :limit 2})))
            gid (get-in before [:actor :grant])]
        (when (and before gid
                   (a-persons-write? (:actor after))
                   (not (a-persons-write? (:actor before))))
          (store/with-tx st
            (fn [tx]
              ;; the grant's newest sitting, whatever its state: one still
              ;; open is not credited, and an older closed one is not either
              (when-some [s (some-> (store/query-rows st tx :sitting
                                                      {:grant (str gid)}
                                                      {:limit 1 :newest-first true})
                                    first
                                    (as-> s (when (= "closed" (some-> (:state s) name)) s)))]
                (when-some [row (store/load-row st tx :sitting (str (:id s))
                                                {:for-update true})]
                  (let [n (inc (long (or (get-in row [:data :corrections]) 0)))
                        rid (str resource-id)
                        ids (vec (get-in row [:data :corrected_rows]))]
                    (store/maintain! :counter st tx :sitting (str (:id s))
                                        (assoc (:data row)
                                               :corrections n
                                               :corrected_rows (if (some #{rid} ids)
                                                                 ids
                                                                 (conj ids rid)))
                                        nil)
                    n))))))))))

(def ^:private served-entry
  "One tool's line in `served` (R-10.6a): how many times the MCP door
  answered that tool, and how many bytes of text those answers
  carried. No price, because a price is the harness's and a byte is
  the engine's.

  A third count joins them when, and only when, a caller asked this
  door to make an answer smaller (waymark-fp62.7.16): `dropped`, the
  bytes the shape removed on the way through. `served` plus `dropped`
  is then what the power answered, which is the one arithmetic that
  says what the shape was worth. A tool nobody shaped carries no
  `dropped` key at all."
  [:map
   [:calls {:x-display {:label "Calls"
                        :help "How many times the door answered this tool."}}
    [:int {:min 0}]]
   [:bytes {:x-display {:label "Bytes"
                        :help "The UTF-8 length of the text those answers carried."}}
    [:int {:min 0}]]
   [:dropped {:optional true
              :x-display
              {:label "Bytes dropped"
               :help "The bytes this door removed because the caller asked for a smaller answer — text only, or a cap on the characters. Add it to the bytes above to see what the power itself answered. Absent when nothing was shaped."}}
    [:maybe [:int {:min 0}]]]])

(defn add-served!
  "Add one call and `bytes` bytes to an open sitting's `served`, under
  the tool that answered them (R-10.6a). `bump-counter!`'s write, one
  field wider: THE DOCUMENT ONLY — version untouched, no transition —
  because a log line per tool answer would cost more than the thing it
  records.

  The key is the tool's own name. Keys are open, so a tool this engine
  gains tomorrow needs no schema change, and a name is keywordized on
  the way in because the store hands every key back as a keyword.

  `dropped` is the fourth argument and it is optional
  (waymark-fp62.7.16): the bytes the door removed from this answer
  because the caller asked for a smaller one. It is added to the line
  only when it is more than nothing, so a tool nobody shaped keeps the
  two counts it always had.

  `answered?` is the fifth and it is optional too (ticket 28a5d117):
  true when the answer was not a refusal, and then the same write
  stamps `last_answer_at`. The stamp is the wall clock's, as
  `last_refusal`'s is, because the close compares the two.

  → the tool's new line, {:calls n :bytes b} and `:dropped` when
  there is one, or nil when there was nothing to count: an unknown id,
  a sitting already closed, a call with no tool name, or a kind this
  engine does not serve."
  ([eng sitting-id tool bytes] (add-served! eng sitting-id tool bytes 0))
  ([eng sitting-id tool bytes dropped]
   (add-served! eng sitting-id tool bytes dropped false))
  ([eng sitting-id tool bytes dropped answered?]
   ;; the MCP door's call stamp, so a run that only reads through the
   ;; connector renews its lease as `stamp-call!` does
   (with-renewal eng sitting-id
     #(add-served* eng sitting-id tool bytes dropped answered?))))

(defn- add-served*
  "`add-served!`'s write, in its own transaction."
  ([eng sitting-id tool bytes dropped answered?]
   (let [tool (some-> tool str not-empty)
         bytes (long (or bytes 0))
         dropped (long (or dropped 0))]
     (when (and sitting-id tool (not (neg? bytes)) (not (neg? dropped))
                (get (inv/resources eng) :sitting))
       (store/with-tx (:storage eng)
         (fn [tx]
           (when-some [row (store/load-row (:storage eng) tx :sitting
                                           (str sitting-id) {:for-update true})]
             (when (= :open (:state row))
               (judge-renewal! eng tx row)
               (let [k (keyword tool)
                     prior (get-in (:data row) [:served k])
                     total (+ (long (or (:dropped prior) 0)) dropped)
                     line (cond-> {:calls (inc (long (or (:calls prior) 0)))
                                   :bytes (+ (long (or (:bytes prior) 0)) bytes)}
                            (pos? total) (assoc :dropped total))]
                 (store/maintain! :counter (:storage eng) tx :sitting (str sitting-id)
                                     (cond-> (-> (:data row)
                                                 (assoc-in [:served k] line)
                                                 (assoc :last_call_at (call-stamp eng)))
                                       answered?
                                       (assoc :last_answer_at
                                              (str (java.time.Instant/now))))
                                     nil)
                 line)))))))))
