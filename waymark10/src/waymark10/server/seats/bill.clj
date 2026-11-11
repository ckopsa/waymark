(ns waymark10.server.seats.bill
  "A sitting's bill: the usage report, the close, the tally, the opening
  and the engine's floor (docs/spec-spend.md §§ 3, 4, 8), moved out of
  `waymark10.server.seats` in round five (ticket f190fba5).

  What a sitting cost is written by its own doors: `report_usage`,
  `tally` and `close` apply the harness's cumulative counts as a report
  and post its delta (`report-posts`); `expire` floors a sitting the
  engine ended from its transcript or from what it was served
  (`floor-posts`); `open_ledger` posts a bill from before the ledger
  (`opening-posts`). The sitting resource names these handlers, posts
  and guards by the same names, referred from here, so a change to the
  bill reads in this file alone. Nothing here requires
  `waymark10.server.seats`."
  (:require [waymark10.guards :as g]
            [waymark10.money :as money]
            [waymark10.resource :refer [defhandler]]
            [waymark10.server.invoke :as inv]
            [waymark10.server.leases :as leases]
            ;; a seat's spend address, which the opening's agreement reads
            [waymark10.server.postings :as postings]
            [waymark10.server.seats.fire :refer [seats-actor sit-deadline-seconds]]
            [waymark10.server.seats.health :refer [stamp-health]]
            [waymark10.server.seats.holds :refer [lease-end release-grace-default release-leases]]
            [waymark10.server.seats.inbox :refer [->instant interactive-seat? renewal-due?]]
            [waymark10.server.seats.ledger :refer [cost-of cost-pairs dollars-of unopened? usage-fields]]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(set! *warn-on-reflection* true)

(def hook-role
  "The role the close hook's hand wears (routes/seats `sitting-close`).
  The hook acts as the sitter, so its principal is the sitter's — this
  role is the one mark that tells its close from the sitter's own
  `close` through the door, and only the route puts it there."
  :sitting-close-hook)

(def ^:private counted-fields
  "The five a report writes onto the row, in the order the schema
  declares them. Named once: the close and the tally write the same
  five, and a list spelled twice would drift the day a sixth arrives."
  [:input_tokens :output_tokens :cache_read_tokens :cache_write_tokens
   :turns])

(defn- prices-now
  "The four prices this sitting's model carries AT THIS MOMENT
  (R-10.4). Four decimals, always — a model row that went missing
  between the open and the close costs zero rather than writing nulls
  into a map the schema says holds prices."
  [row ctx]
  (let [model (when (:read ctx)
                ((:read ctx) :model (str (get-in row [:data :model]))))]
    (into {}
          (map (fn [[_ price-field price-key]]
                 [price-key (or (get-in model [:data price-field]) 0M)]))
          cost-pairs)))

(defn- sum-counts
  "The five counts onto the row, as the sum of `usage_by_session`: a
  sitting more than one session reported into spent what they all
  did, and the sitting budget reads that sum (spec-seat.md)."
  [row]
  (let [usage (vals (get-in row [:data :usage_by_session]))]
    (reduce (fn [r f] (assoc-in r [:data f] (reduce + 0 (keep f usage))))
            row counted-fields)))

;; ── the usage report (docs/spec-spend.md §3, spend step 3c) ─────────
;;
;; CUMULATIVE IN, DELTA OUT. A session reports its whole total so far;
;; the sitting keeps each session's last counts in `usage_by_session`,
;; and posts only the price of what grew since, at this moment's
;; prices. `:posts` reads the row as the handler leaves it, so the
;; handler writes what it posts: `last_report.delta_usd`.

(defn- report-session
  "The session a report is filed under: the one it names, else the
  row's own stamp, else one key for a report that names none."
  [row inp]
  (or (some-> (:harness_session inp) str not-empty)
      (some-> (get-in row [:data :harness_session]) str not-empty)
      "unnamed"))

(defn- last-usage
  "This session's last reported counts on the row, or nil."
  [row session]
  (get-in row [:data :usage_by_session (keyword session)]))

(defn- report-prices
  "The prices a report is costed at: the model it names, when that row
  exists, else the sitting's own."
  [row inp ctx]
  (prices-now (cond-> row
                (and (some? (:model inp)) (:read ctx)
                     ((:read ctx) :model (str (:model inp))))
                (assoc-in [:data :model] (str (:model inp))))
              ctx))

(defn- first-decrease
  "The first count this report puts below its session's last one, as
  [field last-value], or nil. The harness's totals only grow."
  [row inp]
  (when-some [was (last-usage row (report-session row inp))]
    (some (fn [f]
            (let [before (get was f) now' (get inp f)]
              (when (and (some? before) (some? now') (< (long now') (long before)))
                [f before])))
          (conj usage-fields :turns))))

(defn apply-report
  "A report onto the row: its session's counts replaced in
  `usage_by_session`, `last_report` naming the delta it posts,
  `cost_usd` the sum the sitting has posted (a cache of its postings)
  and `bill_basis` reported once anything has. A count below the last
  is costed as no growth; the door's guard refuses it first.
  → [row' changed?], changed? false for a report that moves nothing."
  [row inp ctx]
  (let [session (report-session row inp)
        was (last-usage row session)
        prices (report-prices row inp ctx)
        grown (into {} (map (fn [f] [f (max 0 (- (long (or (get inp f) 0))
                                                  (long (or (get was f) 0))))]))
                    usage-fields)
        ^java.math.BigDecimal delta (cost-of grown prices)
        turns (if (some? (:turns inp)) (:turns inp) (:turns was))
        entry (cond-> (assoc (into {} (map (fn [f] [f (long (or (get inp f) 0))]))
                                   usage-fields)
                             :posted_usd (+ (bigdec (or (:posted_usd was) 0M)) delta))
                (some? turns) (assoc :turns turns))
        changed? (or (pos? (.signum delta))
                     (not= (dissoc entry :posted_usd) (some-> was (dissoc :posted_usd))))
        usage (assoc (or (get-in row [:data :usage_by_session]) {})
                     (keyword session) entry)]
    [(cond-> (-> row
                 (assoc-in [:data :usage_by_session] usage)
                 (assoc-in [:data :last_report] {:harness_session session
                                                 :delta_usd delta
                                                 :prices prices
                                                 :at (:now ctx)})
                 (assoc-in [:data :cost_usd]
                           (reduce + 0M (map #(bigdec (or (:posted_usd %) 0M))
                                             (vals usage)))))
       (pos? (.signum delta)) (assoc-in [:data :bill_basis] "reported")
       ;; spec-spend §4: none while nothing has posted
       (and (not (pos? (.signum delta)))
            (nil? (get-in row [:data :bill_basis])))
       (assoc-in [:data :bill_basis] "none"))
     changed?]))

(defn- reported
  "The row with `inp` applied as a report, for a door that writes on
  even when the report moved nothing (`close`, `tally`)."
  [row inp ctx]
  (first (apply-report row inp ctx)))

(def report-posts
  "What a report posts: `last_report`'s delta onto the seat's spend and
  the domain's, the domain's only when the sitting carries one. A zero
  delta posts nothing, and every posting rests on the harness's report.
  A late report onto an expired sitting (spec-spend §4) also reverses
  the standing estimate first and posts the floor's remainder after,
  each under the estimate basis it stands on (`reversed_basis`,
  `estimate_basis`), all on the door's one log row.
  The seat and domain accounts are :on-read, so no row is locked."
  '[{:account [:seat (data :seat) :spend]
     :amount (* (money "1.00" "USD") (get (data :last_report) :estimate_reversed_usd))
     :sign :-
     :when (and (is-set (get (data :last_report) :estimate_reversed_usd))
                (> (get (data :last_report) :estimate_reversed_usd) 0))
     :basis (get (data :last_report) :reversed_basis)}
    {:account [:domain (data :domain_id) :spend]
     :amount (* (money "1.00" "USD") (get (data :last_report) :estimate_reversed_usd))
     :sign :-
     :when (and (is-set (data :domain_id))
                (and (is-set (get (data :last_report) :estimate_reversed_usd))
                     (> (get (data :last_report) :estimate_reversed_usd) 0)))
     :basis (get (data :last_report) :reversed_basis)}
    {:account [:seat (data :seat) :spend]
     :amount (* (money "1.00" "USD") (get (data :last_report) :delta_usd))
     :sign :+
     :when (> (get (data :last_report) :delta_usd) 0)
     :basis "reported"}
    {:account [:domain (data :domain_id) :spend]
     :amount (* (money "1.00" "USD") (get (data :last_report) :delta_usd))
     :sign :+
     :when (and (is-set (data :domain_id))
                (> (get (data :last_report) :delta_usd) 0))
     :basis "reported"}
    {:account [:seat (data :seat) :spend]
     :amount (* (money "1.00" "USD") (get (data :last_report) :estimate_usd))
     :sign :+
     :when (and (is-set (get (data :last_report) :estimate_usd))
                (> (get (data :last_report) :estimate_usd) 0))
     :basis (get (data :last_report) :estimate_basis)}
    {:account [:domain (data :domain_id) :spend]
     :amount (* (money "1.00" "USD") (get (data :last_report) :estimate_usd))
     :sign :+
     :when (and (is-set (data :domain_id))
                (and (is-set (get (data :last_report) :estimate_usd))
                     (> (get (data :last_report) :estimate_usd) 0)))
     :basis (get (data :last_report) :estimate_basis)}])

(def floor-posts
  "What `expire` posts (spec-spend §4): the floor the handler wrote in
  `floor`, onto the seat's spend and the domain's, marked with the
  floor's own basis. A zero floor posts nothing."
  '[{:account [:seat (data :seat) :spend]
     :amount (* (money "1.00" "USD") (get (data :floor) :estimate_usd))
     :sign :+
     :when (and (is-set (get (data :floor) :estimate_usd))
                (> (get (data :floor) :estimate_usd) 0))
     :basis (get (data :floor) :basis)}
    {:account [:domain (data :domain_id) :spend]
     :amount (* (money "1.00" "USD") (get (data :floor) :estimate_usd))
     :sign :+
     :when (and (is-set (data :domain_id))
                (and (is-set (get (data :floor) :estimate_usd))
                     (> (get (data :floor) :estimate_usd) 0)))
     :basis (get (data :floor) :basis)}])

(g/defguard reports-only-grow
  {:reads []
   :vars [:field :last :session]
   :explain "{field} went down: session {session} last reported {last}, and a harness's totals only grow."
   :open "Report the session's whole total so far, which is never below its last report; a count that falls is a hook bug."}
  [row inp _ctx]
  (if-some [[f was] (first-decrease row inp)]
    (t/deny {:vars {:field (name f) :last (str was)
                    :session (report-session row inp)}})
    (t/allow)))

;; ── the opening (docs/spec-spend.md §8, spend step 4) ───────────────
;;
;; A sitting billed before the ledger carries its cost and no posting.
;; `open_ledger` posts that cost unchanged, dated when it was spent, and
;; seeds `usage_by_session` from the counters, so the first real report
;; after it posts only what grew.

(def opening-posts
  "What an opening posts: the bill the sitting already carries, written
  as `last_report.delta_usd`, onto the seat's spend and the domain's,
  each at `last_report.at` — when it was spent — and resting on basis
  opening. Not repriced."
  '[{:account [:seat (data :seat) :spend]
     :amount (* (money "1.00" "USD") (get (data :last_report) :delta_usd))
     :sign :+
     :when (> (get (data :last_report) :delta_usd) 0)
     :at (get (data :last_report) :at)
     :basis "opening"}
    {:account [:domain (data :domain_id) :spend]
     :amount (* (money "1.00" "USD") (get (data :last_report) :delta_usd))
     :sign :+
     :when (and (is-set (data :domain_id))
                (> (get (data :last_report) :delta_usd) 0))
     :at (get (data :last_report) :at)
     :basis "opening"}])

(g/defguard a-bill-to-open
  {:reads []
   :vars [:why]
   :explain "This sitting has no bill to open onto the ledger: {why}."
   :open "Only a sitting with a cost and no usage report is opened; a reported sitting's postings are already its bill, so an opening runs once."}
  [row _inp _ctx]
  (cond
    (unopened? row) (t/allow)
    (seq (get-in row [:data :usage_by_session]))
    (t/deny {:vars {:why "its usage is on the ledger already"}})
    :else (t/deny {:vars {:why "it cost nothing"}})))

(defn- closed-by
  "Which path this close came down, from the hand that closes it: the
  engine's own actor is the sweep, the hook's hand wears `hook-role`,
  and every other hand came through the door."
  [ctx]
  (let [p (:principal ctx)]
    (cond
      (= :takeover (:action (:within ctx))) "takeover"
      (= (:id seats-actor) (:id p)) "sweep"
      (contains? (set (:roles p)) hook-role) "hook"
      :else "door")))

;; ── a sitting's ending lets go of its leases (docs/spec-leases.md §3) ─

(defn- release-at-close
  "The close's release: the seat's `release_grace_seconds`, so a run
  still mid-call when its hook fired keeps its rows (ticket f6c8d5ce),
  and none at a takeover, whose rows go to the sitting that took them
  (ticket e3add1a6)."
  [row ctx]
  (release-leases row ctx
                  (if (= "takeover" (closed-by ctx))
                    0
                    (or (when-some [read' (:read ctx)]
                          (some->> (get-in row [:data :seat]) str not-empty
                                   (read' :seat) :data :release_grace_seconds))
                        release-grace-default))))

(defhandler abandon-sitting [row _inp ctx]
  ;; nothing is written on the row; its leases are let go with no
  ;; grace, because a swept sitting holds nothing
  (release-leases row ctx 0))

;; ── the engine's floor (docs/spec-spend.md §4, spend step 6) ────────
;;
;; A sitting the engine expires still leaves its bill: what its
;; transcript shows beyond every session's report, priced per line and
;; posted `estimated_from_transcript`. The standing estimate is the net
;; of those postings on the ledger, so a late report reverses it
;; without knowing which log row posted it. A sitting with no line and
;; no report is floored instead from what the engine served it, posted
;; `estimated_from_calls`, until lines or a report replace it.

(def ^:private estimated "estimated_from_transcript")

(def ^:private estimated-from-calls "estimated_from_calls")

(defn- request-lines
  "The sitting's transcript lines that carry usage, one per request,
  oldest first: lines sharing a `request_id` count once, as the last of
  them (by `n`) says, transcripts/sum-usage's rule. A line with no
  request id stands alone."
  [row ctx]
  (when-some [find' (:find ctx)]
    (let [lines (->> (find' :transcript_entry {:sitting (str (:id row))} {:all true})
                     (map :data)
                     (filter #(map? (:usage %)))
                     (sort-by #(long (or (:n %) 0))))
          rid #(some-> (:request_id %) str not-empty)
          last-of (reduce (fn [m d] (if-let [r (rid d)] (assoc m r d) m)) {} lines)]
      (filterv #(if-let [r (rid %)] (identical? % (get last-of r)) true) lines))))

(defn- line-prices
  "The prices a transcript line is costed at, at this moment: the model
  it names, by name or by row id, else the sitting's own."
  [row model ctx]
  (let [m (some-> model str not-empty)
        found (when m
                (or (when-some [find' (:find ctx)]
                      (first (find' :model {:name m} {:limit 1})))
                    (when-some [read' (:read ctx)]
                      (when (parse-uuid m) (read' :model m)))))]
    (prices-now (cond-> row found (assoc-in [:data :model] (str (:id found)))) ctx)))

(defn- reported-totals
  "Each priced count summed across every session's last report."
  [row]
  (let [usage (vals (get-in row [:data :usage_by_session]))]
    (into {} (map (fn [f] [f (reduce + 0 (map #(long (or (get % f) 0)) usage))]))
          usage-fields)))

(defn- transcript-floor
  "The engine's floor (spec-spend §4): per count, what the transcript
  shows beyond the sessions' reported totals, priced per line at that
  line's model. Reports are cumulative, so they cover the oldest lines:
  each reported total is spent down the lines in order, and what is
  left of a line is its tail. → {:usd BigDecimal :models [name]}, the
  models whose lines priced a tail."
  [row ctx]
  (let [price-of (memoize #(line-prices row % ctx))]
    (loop [left (reported-totals row)
           usd 0M
           models (sorted-set)
           [d & more :as lines] (request-lines row ctx)]
      (if (empty? lines)
        {:usd usd :models (vec models) :basis estimated}
        (let [n #(long (or (get-in d [:usage %]) 0))
              tail (into {} (map (fn [f] [f (max 0 (- (n f) (get left f)))])) usage-fields)
              ^java.math.BigDecimal cost (cost-of tail (price-of (:model d)))]
          (recur (into {} (map (fn [f] [f (max 0 (- (get left f) (n f)))])) usage-fields)
                 (+ usd cost)
                 (cond-> models
                   (pos? (.signum cost))
                   (conj (str (or (some-> (:model d) str not-empty)
                                  (get-in row [:data :model])))))
                 more))))))

(defn- served-floor
  "The floor of a sitting no session reported and no line priced: what
  the engine served it (`served`), read as input tokens at four bytes a
  token and priced at the sitting's model with no output, a lower
  bound. → transcript-floor's answer, basis `estimated_from_calls`."
  [row ctx]
  (let [bytes (reduce + 0 (map #(long (or (:bytes %) 0))
                               (vals (get-in row [:data :served]))))
        ^java.math.BigDecimal usd (cost-of {:input_tokens (quot bytes 4)}
                                           (prices-now row ctx))
        model (some-> (get-in row [:data :model]) str not-empty)
        named (when-some [read' (when model (:read ctx))]
                (some-> (read' :model model) :data :name str))]
    {:usd usd
     :models (if (and model (pos? (.signum usd))) [(or named model)] [])
     :basis estimated-from-calls}))

(defn- floor-of
  "The floor a sitting stands on: its transcript's, or, for a FIRED
  sitting with neither a report nor a usage-bearing line, what it was
  served. An interactive sitting keeps R-7.6: no served floor.
  → {:usd BigDecimal :models [name] :basis}."
  [row ctx]
  (if (and (not (interactive-seat? row))
           (empty? (get-in row [:data :usage_by_session]))
           (empty? (request-lines row ctx)))
    (served-floor row ctx)
    (transcript-floor row ctx)))

(defn- posted-amount
  "A posting's signed amount as a decimal, decoded or on the wire."
  [a]
  (let [m (if (money/money? a) a (money/from-wire a nil))]
    (if (money/money? m) (bigdec (:amount m)) 0M)))

(defn- ledger-of
  "What the ledger holds for this sitting on its seat's spend (the
  domain's postings are their twins): `:net`, the net of its estimate
  postings, the standing estimate whichever log row posted it;
  `:net-basis`, the estimate basis that net stands on; and `:latest`,
  the basis of its latest other posting, or nil."
  [row ctx]
  (let [ps (when-some [find' (:find ctx)]
             (when-some [seat (some-> (get-in row [:data :seat]) str not-empty)]
               (->> (find' :posting {:source_id (str (:id row))
                                     :account (postings/account-address :seat seat :spend)}
                           {:all true})
                    (map :data)
                    (filter #(= "sitting" (str (:kind %)))))))
        est? #(contains? #{estimated estimated-from-calls} (str (:basis %)))
        at-of #(if-some [i (->instant (:at %))] (.toEpochMilli ^Instant i) 0)
        nets (reduce (fn [m p] (update m (str (:basis p)) (fnil + 0M) (posted-amount (:amount p))))
                     {} (filter est? ps))]
    {:net (reduce + 0M (vals nets))
     :net-basis (or (some (fn [[b ^java.math.BigDecimal n]] (when (pos? (.signum n)) b)) nets)
                    estimated)
     :latest (some->> (seq (filter :basis (remove est? ps)))
                      (apply max-key at-of)
                      :basis
                      str)}))

(defn- bill-basis
  "spec-spend §4: the estimate's basis while the net estimate is above
  zero, else the basis of the latest posting, else none."
  [^java.math.BigDecimal net basis latest]
  (cond (pos? (.signum net)) basis
        (some? latest) latest
        :else "none"))

(defn floor-onto
  "The floor written onto an expiring sitting for `floor-posts` to post
  (`floor`), `cost_usd` grown by it as the ledger grows, and
  `bill_basis`. → the row."
  [row ctx]
  (let [{:keys [usd models basis]} (floor-of row ctx)
        latest (or (:latest (ledger-of row ctx))
                   (#{"reported" "opening"} (get-in row [:data :bill_basis])))]
    (cond-> (-> row
                (assoc-in [:data :floor] {:estimate_usd usd :models models :basis basis :at (:now ctx)})
                (assoc-in [:data :bill_basis] (bill-basis usd basis latest)))
      (pos? (.signum ^java.math.BigDecimal usd))
      (update-in [:data :cost_usd] #(+ (dollars-of %) usd)))))

;; ── the fire nobody sat in, ended by its lease (ticket 9100c7b9) ────

(def ^:private missed-note-cap
  "The sitting's `note` is 240 characters wide."
  240)

(defn missed-note
  "The sentence a missed sitting carries, with the provider's page for
  the run when the schedule row has one."
  [fired deadline schedule-row]
  (let [url (some-> (get-in schedule-row [:data :last_run_url]) str not-empty)
        s (str "Fired at " fired "; no session sat within " deadline "s."
               (when url (str " Last run: " url)))]
    (subs s 0 (min (count s) (long missed-note-cap)))))

(defn not-started-note
  "The sentence a not-started pull fire's sitting carries."
  [reason]
  (let [s (str "localfire stopped before it knew whether the run started: "
               reason)]
    (subs s 0 (min (count s) (long missed-note-cap)))))

(defn fire-entry-of
  "The entry of `seat-row`'s `fire_keys` that names `sitting-id` as the
  sitting its fire opened, or nil."
  [seat-row sitting-id]
  (first (filter #(= (str sitting-id) (some-> (:sitting %) str))
                 (get-in seat-row [:data :fire_keys]))))

(defn- missed-onto
  "An expiring sitting nobody sat in: unclaimed, and its seat's
  `fire_keys` still holds the entry of the fire that opened it. It is
  stamped `missed`, with the note `wakes/record-missed!` writes, or the
  not-started one when a localfire said it stopped early; the close's
  own rule then reads it as `never_sat`. The entry leaves the seat row
  after the commit (`wakes/missed-expired!`), since no door writes
  `fire_keys`. → the row."
  [row ctx]
  (let [read' (:read ctx)
        seat (when read'
               (some->> (get-in row [:data :seat]) str not-empty (read' :seat)))
        entry (when-not (some-> (get-in row [:data :connector_session]) str not-empty)
                (fire-entry-of seat (:id row)))]
    (if-not entry
      row
      (-> row
          (assoc-in [:data :missed] true)
          (assoc-in [:data :note]
                    (if-some [why (some-> (:not_started entry) str not-empty)]
                      (not-started-note why)
                      (missed-note (:fired_at entry) sit-deadline-seconds
                                   (some->> (get-in seat [:data :schedule])
                                            str not-empty (read' :schedule)))))))))

(defn- expired?
  "Did the engine end this sitting (`expire`)?"
  [row]
  (= "expiry" (str (get-in row [:data :closed_by]))))

(defn late-report
  "A report onto a sitting the engine expired (spec-spend §4), in its
  one transaction: the standing estimate reversed, the report's own
  delta, and the floor re-run against the new reported totals across
  all sessions, the three amounts in `last_report` for `report-posts`.
  `row'` is `row` with the report applied. → the row, or unchanged
  when it moves nothing."
  [row row' changed? ctx]
  (let [{:keys [net net-basis latest]} (ledger-of row ctx)
        {:keys [^java.math.BigDecimal usd models basis]} (floor-of row' ctx)
        ^java.math.BigDecimal delta (get-in row' [:data :last_report :delta_usd])]
    (if (and (not changed?) (zero? (compare net usd))
             (or (zero? (.signum usd)) (= net-basis basis)))
      (inv/unchanged row)
      (-> row'
          (update-in [:data :last_report] assoc
                     :estimate_reversed_usd net :reversed_basis net-basis
                     :estimate_usd usd :estimate_basis basis)
          (assoc-in [:data :floor] {:estimate_usd usd :models models :basis basis :at (:now ctx)})
          (update-in [:data :cost_usd] #(+ (dollars-of %) usd))
          (assoc-in [:data :bill_basis]
                    (bill-basis usd basis (if (pos? (.signum delta)) "reported" latest)))))))

(defn- expire-leases
  "End every `held` lease the sitting `row` holds, `expired`, by id, with
  no grace: an expired holder is not mid-call. → the row, unchanged."
  [row ctx]
  (when-some [invoke' (:invoke ctx)]
    (when-some [find' (:find ctx)]
      (doseq [l (sort-by #(str (:id %))
                         (find' :lease {:holder_kind "sitting" :holder_id (str (:id row))
                                        :state "held"}
                                {:limit 50}))]
        (invoke' :lease (:id l) :expire {:reason "expired"} {:as leases/leaser}))))
  row)

(defhandler expire-sitting [row _inp ctx]
  ;; docs/spec-leases.md §3: what it reported stands; docs/spec-spend.md
  ;; §4: the floor posts what the transcript shows beyond it
  (-> row
      (assoc-in [:data :ended_at] (:now ctx))
      (assoc-in [:data :closed_by] "expiry")
      ;; a fire nobody sat in ends here, as missed (ticket 9100c7b9)
      (missed-onto ctx)
      (stamp-health ctx)
      (floor-onto ctx)
      (expire-leases ctx)))

(defhandler close-sitting [row inp ctx]
  ;; R-10.4: the model's prices are read AT THIS MOMENT, the cost is
  ;; computed from them, and the prices used are written beside it —
  ;; so a reprice tomorrow moves the model row and does not move one
  ;; byte of what last week cost. R-10.5: the engine never estimates
  ;; a token; these are the harness's counts, recorded.
  ;; Spend step 3c: the counts are applied as a report (`apply-report`)
  ;; in this same transaction, and the close's :posts post its delta;
  ;; the close no longer writes `cost_usd` or `prices` itself, and its
  ;; counts are the sum of the sessions' reports.
  (-> row
        (assoc-in [:data :note] (:note inp))
        ;; THE BIRTH STAMP WINS (R-12.15, R-12.17). `waymark_sit` may
        ;; already have paired this row with the harness session that
        ;; opened it, and two runs of one seat can overlap — so a
        ;; close writes the id only onto a row that carries none. A
        ;; report naming a different session is the door's fallback
        ;; case, and moving the stamp would move the bill to a run
        ;; that did not spend it.
        (cond-> (and (some? (:harness_session inp))
                     (nil? (get-in row [:data :harness_session])))
          (assoc-in [:data :harness_session] (:harness_session inp)))
        (assoc-in [:data :ended_at] (:now ctx))
        (assoc-in [:data :closed_by] (closed-by ctx))
        (reported inp ctx)
        sum-counts
        ;; seat health 1: judged LAST, from the counts, the hand and
        ;; the cost this close has just written
        (stamp-health ctx)
        ;; and its leases are let go, under the seat's grace
        (release-at-close ctx)))

(defn- seat-read
  "The seat of this sitting, read through the ctx, or nil."
  [row ctx]
  (when-some [read' (:read ctx)]
    (some->> (get-in row [:data :seat]) str not-empty (read' :seat))))

(defn- renew-at-half-life
  "The half-life rule for a report (docs/spec-leases.md §2): a sitting
  whose lease has less than half its length left queues its own
  `renew`, made once this write has committed. Invoked inside it, the
  renewal would wait on the row lock this write holds. An ended
  sitting never renews. → the row."
  [row ctx]
  (when-some [follow-up (:follow-up ctx)]
    (when (renewal-due? row (:now ctx) (seat-read row ctx))
      (follow-up {:kind :sitting :id (:id row) :action :renew})))
  row)

(defhandler tally-sitting [row inp ctx]
  ;; R-12.25, R-12.27: the same five counts as a close, written onto a
  ;; sitting that is STILL OPEN, priced at this moment so the week's
  ;; wall can see what an unfinished sitting has spent.
  ;;
  ;; CUMULATIVE, never additive: the hook sums the whole transcript
  ;; every turn, so the newest tally REPLACES its session's last and a
  ;; replayed one writes what is already there. A falling count is
  ;; refused (`reports-only-grow`), as `report_usage` refuses it.
  ;;
  ;; No `prices` map is copied down. The prices belong beside the bill
  ;; that a reprice must not be able to move, and the only bill is the
  ;; close's; a running cost is a reading of the row right now, and it
  ;; is re-read at the next turn.
  (-> row
      ;; a tally with nothing to say leaves the last sentence standing
      ;; — the close is where a note is owed, and a per-turn wipe would
      ;; lose it
      (cond-> (some? (:note inp))
        (assoc-in [:data :note] (:note inp)))
      ;; THE BIRTH STAMP WINS, the close's rule verbatim (R-12.17)
      (cond-> (and (some? (:harness_session inp))
                   (nil? (get-in row [:data :harness_session])))
        (assoc-in [:data :harness_session] (:harness_session inp)))
      (assoc-in [:data :tallied_at] (:now ctx))
      ;; a hook tallying through a long wait is a run still there
      (assoc-in [:data :last_call_at] (:now ctx))
      ;; an alias of `report_usage` (spend step 3c): the same report,
      ;; posted by the tally's own :posts
      (reported inp ctx)
      sum-counts
      ;; and the same renewal at half-life (docs/spec-leases.md §3)
      (renew-at-half-life ctx)))

(defhandler report-usage [row inp ctx]
  ;; docs/spec-spend.md §3: a report that moves nothing is no move —
  ;; no transition, no log row, no posting — so a hook that reports
  ;; every turn through a long wait costs nothing
  ;; An open sitting's report renews as a call does: the lease must
  ;; not end a person's session that reports and makes no engine call
  ;; (docs/spec-leases.md §3)
  ;; A report onto an expired sitting reverses its standing estimate
  ;; and re-runs the floor (docs/spec-spend.md §4)
  (let [[row' changed?] (apply-report row inp ctx)]
    (cond (expired? row) (late-report row row' changed? ctx)
          changed? (renew-at-half-life row' ctx)
          :else (inv/unchanged row))))

(defn refloor
  "An expired sitting's floor re-run against the transcript lines that
  landed since (spec-spend §4): `late-report`'s reversal and re-post
  with no report of its own, onto the sessions' reported totals.
  → the row, or unchanged when the floor stands."
  [row ctx]
  (late-report row
               (-> row
                   (assoc-in [:data :last_report] {:delta_usd 0M :at (:now ctx)})
                   (assoc-in [:data :cost_usd]
                             (reduce + 0M (map #(bigdec (or (:posted_usd %) 0M))
                                               (vals (get-in row [:data :usage_by_session]))))))
               false ctx))

(defhandler refloor-sitting [row _inp ctx]
  ;; docs/spec-spend.md §4: lines stored after the sweep still bill, so
  ;; a run that sat and died before its upload landed is not billed $0
  (if (expired? row) (refloor row ctx) (inv/unchanged row)))

(defhandler renew-sitting [row _inp ctx]
  ;; docs/spec-leases.md §3: the lease's end moves to now plus its
  ;; length; `lease-expired` has already refused a lapsed one
  (assoc-in row [:data :expires_at] (lease-end (:now ctx) (seat-read row ctx))))
