(ns waymark10.server.seats.ledger
  "A domain's budget and its wall (epic aff24e84, piece 2;
  docs/spec-spend.md §5, spend step 8), carved out of
  `waymark10.server.seats` (ticket fdd28816, carve 2 of 4): the week's
  dollars, the domain row a seat reads as being in, the seats that spent
  most of a domain's week, and the two guards that judge a seat by its
  domain — `domain-under-budget` at the fire door and
  `budget-fits-the-domain` at a seat's create and restate.

  Carve 2b (ticket 791cf8a1) brought the rest of the bill: the cost
  arithmetic, the ledger read (the opening, its backfill and its
  agreement check), a seat's spend by domain, and the doors that move a
  seat's budget — `close_breaker`, `open_ledger`, `set_budget` and the
  mayor's no-tap restate.

  seats.clj keeps a one-line def for each var another namespace or its
  own resource calls, so every `seats/` alias still resolves. This file
  requires nothing of seats: seats requires it."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.money :as money]
            [waymark10.resource :refer [defhandler]]
            [waymark10.time :refer [instant-of]]
            [waymark10.server.delegation :as delegation]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.postings :as postings]
            [waymark10.server.seats.fire :as fire]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.math RoundingMode)
           (java.time Instant)))

(set! *warn-on-reflection* true)

(def default-domain
  "The name of the domain a seat with no `domain` reads as being in."
  "factory")

(defn- ->instant [v]
  ;; seats.clj's own spelling, which `domain-under-budget`'s form names
  (cond (instance? java.time.Instant v) v
        (inst? v) (.toInstant ^java.util.Date v)
        (some-> v str not-empty) (java.time.Instant/parse (str v))))

(defn in-window?
  "Does posting `p` count in the wall's window [since, now], by its
  `effective_at` as the wall counts it?"
  [^java.time.Instant since ^java.time.Instant now p]
  (when-some [at (get-in p [:data :effective_at])]
    (let [at (if (instance? java.time.Instant at)
               ^java.time.Instant at
               (java.time.Instant/parse (str at)))]
      (and (not (.isBefore at since)) (not (.isAfter at now))))))

(defn posting-dollars
  "A posting's amount in dollars; anything not money reads as zero."
  [p]
  (let [m (money/from-wire (get-in p [:data :amount]) nil)]
    (if (money/money? m) (:amount m) 0M)))

;; ── a domain's budget (epic aff24e84, piece 2) ──────────────────────

(defn dollars
  "A week's budget as a BigDecimal; none reads as zero."
  ^java.math.BigDecimal [v]
  (if (decimal? v) v (bigdec (or v 0))))

(defn money-text
  "A budget as a sentence spells it: 11, not 11.00."
  [v]
  (.toPlainString (.stripTrailingZeros (dollars v))))

(defn domain-row-of
  "The domain row a seat's `domain` value names, read through the ctx's
  hooks: that row, or the one named `default-domain` when the value is
  empty. nil when the hook is absent or no such row is stored."
  [ctx domain]
  (if-some [id (some-> domain str not-empty)]
    (when-some [read' (:read ctx)] (read' :domain id))
    (when-some [find' (:find ctx)]
      (first (find' :domain {:name default-domain} {:limit 1})))))

;; ── the domain wall (docs/spec-spend.md §5, spend step 8) ────────────

(defn domain-top-seats
  "The seats that spent most of this domain's week, largest first: at
  most `n` pairs of the seat's name and its dollars. A domain posting
  is charged to the seat its sitting names, so a seat that moved domain
  counts toward the domain it posted to."
  [read' find' domain-id ^java.time.Instant now n]
  (let [since (.minusSeconds now (long grants/budget-window-seconds))
        seat-of (fn [p]
                  (some->> (get-in p [:data :source_id]) str not-empty
                           (read' :sitting) :data :seat str not-empty))]
    (->> (find' :posting
                {:account (postings/account-address :domain (str domain-id) :spend)}
                {:limit 10000})
         (filter #(in-window? since now %))
         (reduce (fn [acc p]
                   (if-some [seat (seat-of p)]
                     (update acc seat (fnil + 0M) (posting-dollars p))
                     acc))
                 {})
         (sort-by val #(compare %2 %1))
         (take n)
         (mapv (fn [[id usd]]
                 [(or (some-> (read' :seat id) (get-in [:data :name]) str not-empty) id)
                  usd])))))

(g/defguard domain-under-budget
  {:reads [:storage]
   :vars [:domain :spent :budget :top]
   :remedies [:domain/restate]
   :explain "The domain {domain} has spent {spent} of its {budget} dollars over the last seven days. Its largest seats this week: {top}. The wall lifts on its own as the window rolls, or when the domain's budget is restated."}
  [row _inp ctx]
  ;; The domain's week is the seat wall's window sum over the domain's
  ;; `domain/<id>/spend` account. A domain with no budget, or a ctx with
  ;; no hand to find or sum with (the pure render probe), has no wall.
  (let [domain (when (and (:find ctx) (:sum-amounts ctx) (:now ctx))
                 (domain-row-of ctx (get-in row [:data :domain])))
        budget (get-in domain [:data :budget_usd_per_week])]
    (if (nil? budget)
      (t/allow)
      (let [now (->instant (:now ctx))
            spent (grants/domain-spent-with (:sum-amounts ctx) (:id domain) now)]
        (if (grants/under-budget? spent (dollars budget))
          (t/allow)
          (let [top (when (:read ctx)
                      (domain-top-seats (:read ctx) (:find ctx) (:id domain) now 3))]
            (t/deny {:vars {:domain (str (or (get-in domain [:data :name]) (:id domain)))
                            :spent (money-text spent)
                            :budget (money-text budget)
                            :top (if (seq top)
                                   (str/join ", " (map (fn [[nm usd]] (str nm " " (money-text usd))) top))
                                   "none on the ledger")}
                     :evidence {:top_seats (mapv (fn [[nm usd]] {:seat nm :spent (money-text usd)}) top)}})))))))

(defn domain-seats
  "The ACTIVE seats in this domain: the ones that name it, and for
  `default-domain` the ones that name none. A parked, merged or retired
  seat is not among them."
  [find' domain-row]
  (let [id (str (:id domain-row))
        default? (= default-domain (str (get-in domain-row [:data :name])))]
    (filter (fn [s]
              (if-some [d (some-> (get-in s [:data :domain]) str not-empty)]
                (= d id)
                default?))
            (find' :seat (cond-> {:state "active"}
                           (not default?) (assoc :domain id))
                   {:limit 10000}))))

(defn- budgets-of [seat-rows]
  (transduce (map (fn [s] (dollars (get-in s [:data :budget_usd_per_week]))))
             + 0M seat-rows))

(defn domain-allocated
  "The total of the budgets of this domain's active seats."
  [find' domain-row]
  (budgets-of (domain-seats find' domain-row)))

(g/defguard budget-fits-the-domain
  {:reads [:seat :domain]
   :vars [:domain :total :ceiling :headroom]
   :remedies [:domain/restate]
   :explain "The active seats of {domain} would come to {total} dollars a week together, and the domain's budget is {ceiling}; its headroom today is {headroom}. Ask for no more than the headroom, or restate the domain's budget, which is held for its owner's tap."}
  [row inp ctx]
  ;; seat create and restate. A seat that is not active does not count,
  ;; so its restate is not judged; an engine with no domain row for the
  ;; seat has no ceiling to judge by.
  (let [find' (:find ctx)
        domain (when find'
                 (domain-row-of ctx (if (contains? inp :domain)
                                      (:domain inp)
                                      (get-in row [:data :domain]))))]
    (if (or (nil? domain)
            (and (:id row) (not= "active" (name (:state row)))))
      (t/allow)
      (let [ceiling (dollars (get-in domain [:data :budget_usd_per_week]))
            active (domain-seats find' domain)
            today (budgets-of active)
            others (budgets-of (remove (fn [s] (= (str (:id s)) (str (:id row))))
                                       active))
            total (+ others (dollars (:budget_usd_per_week inp)))]
        ;; a domain already over its budget is not made worse, and a
        ;; restate that does not raise its total is not refused for it
        (if (and (pos? (compare total ceiling))
                 (pos? (compare total today)))
          (t/deny {:vars {:domain (str (get-in domain [:data :name]))
                          :total (money-text total)
                          :ceiling (money-text ceiling)
                          :headroom (money-text (- ceiling today))}})
          (t/allow))))))

;; ── a sitting's bill: the cost arithmetic (carve 2b) ────────────────

(def ^:private million (bigdec 1000000))

(def cost-scale
  "Decimal places a sitting's cost is written to. Six, because a turn
  on an economy model costs a fraction of a cent and a ledger that
  rounded it to the penny would report zero for a week of work."
  6)

(def cost-pairs
  "Which token count is priced by which field — the four halves of a
  sitting's bill, named once so the close and the stored `prices` map
  cannot drift apart."
  [[:input_tokens :price_input_per_mtok :input]
   [:output_tokens :price_output_per_mtok :output]
   [:cache_read_tokens :price_cache_read_per_mtok :cache_read]
   [:cache_write_tokens :price_cache_write_per_mtok :cache_write]])

(defn cost-of
  "The dollars a sitting's token counts cost at these prices —
  sum(tokens ÷ 1e6 × price), exact decimals throughout (never floats,
  batch H's own rule) and rounded once at the end. Public because the
  ledger route and the tests both want the arithmetic named rather
  than repeated."
  [counts prices]
  (let [^java.math.BigDecimal total
        (reduce (fn [acc [tokens-field _ price-key]]
                  (+ acc (* (bigdec (or (get counts tokens-field) 0))
                            (bigdec (or (get prices price-key) 0)))))
                0M
                cost-pairs)]
    (.divide total ^java.math.BigDecimal million (int cost-scale)
             RoundingMode/HALF_UP)))

(def usage-fields
  "The four priced counts `usage_by_session` keeps per session."
  [:input_tokens :output_tokens :cache_read_tokens :cache_write_tokens])

;; ── the ledger read: the opening (docs/spec-spend.md §8) ─────────────

(defn dollars-of
  "A sitting's stored cost as a BigDecimal; none reads as zero."
  [v] (bigdec (str (or v 0))))

(defn unopened?
  "Does this sitting carry a bill the ledger has not seen: a cost, and no
  usage report yet? A reported sitting's postings are its bill already."
  [row]
  (and (pos? (compare (dollars-of (get-in row [:data :cost_usd])) 0M))
       (empty? (get-in row [:data :usage_by_session]))))

(defn- spent-at
  "The instant a sitting's bill counts at on the ledger: its end, else
  its last tally, else its start — an open sitting's."
  [row ctx]
  (or (some #(instant-of (get-in row [:data %])) [:ended_at :tallied_at :started_at])
      (:now ctx)))

(defhandler open-ledger [row inp ctx]
  ;; docs/spec-spend.md §8: the cost the row carries is what
  ;; `opening-posts` posts, at `spent-at`. The counters seed their
  ;; session's line with what it posted, under the row's stamp or
  ;; "opening", so a later report of the same session posts its growth.
  (let [d (:data row)
        session (or (some-> (:harness_session d) str not-empty) "opening")
        cost (dollars-of (:cost_usd d))
        entry (cond-> (assoc (into {} (map (fn [f] [f (long (or (get d f) 0))]))
                                   usage-fields)
                             :posted_usd cost)
                (some? (:turns d)) (assoc :turns (long (:turns d))))]
    (-> row
        (assoc-in [:data :usage_by_session] {(keyword session) entry})
        (assoc-in [:data :last_report] {:harness_session session
                                        :delta_usd cost
                                        :prices (or (:prices inp) (:prices d) {})
                                        :at (spent-at row ctx)})
        (assoc-in [:data :bill_basis] "opening"))))

(def ^:private seats-actor fire/seats-actor)

(defn backfill-domain-ids!
  "Stamp `domain_id` on every sitting that carries none, from its seat's
  current domain (spend step 3a): the rows born before the birth stamped
  it. A seat with no domain stamps nothing, and a stamped row is never
  stamped again, so a second pass writes nothing. A MAINTENANCE write,
  `backfill-health!`'s spelling. → the number stamped."
  [eng]
  (if (and (get (inv/resources eng) :sitting) (get (inv/resources eng) :domain))
    (let [st (:storage eng)
          stamped? #(some-> (:domain_id %) str not-empty)
          due (store/with-tx st
                (fn [tx]
                  (->> (store/query-rows st tx :sitting {} {:all true})
                       (remove (comp stamped? :data))
                       (mapv (comp str :id)))))]
      (count
       (filterv
        (fn [id]
          (store/with-tx st
            (fn [tx]
              (when-some [row (store/load-row st tx :sitting id
                                              {:for-update true})]
                (let [data (:data row)
                      domain (when-not (stamped? data)
                               (some->> (:seat data) str not-empty
                                        (#(store/load-row st tx :seat % {}))
                                        :data :domain str not-empty
                                        (#(store/load-row st tx :domain % {}))
                                        :id str))]
                  (when domain
                    (store/update-data! st tx :sitting id
                                        (assoc data :domain_id domain) nil)
                    true))))))
        due)))
    0))

(defn open-ledger!
  "Spend step 4 (docs/spec-spend.md §8), the engine task: every sitting
  with a bill and no posting yet, opened onto the ledger through its
  own `open_ledger` door as the seats actor. Domains are stamped first
  (`backfill-domain-ids!`), so an old row's bill reaches its domain's
  spend too. A sitting whose postings exist (by source_id) is skipped,
  and the door refuses one already reported, so a second run posts
  nothing. A refused sitting is warned of and passed. → the number
  opened."
  [eng]
  (let [res (inv/resources eng)]
    (if-not (and (get res :sitting) (get res :posting))
      0
      (let [_ (backfill-domain-ids! eng)
            st (:storage eng)
            rdef (get res :sitting)
            posted? (fn [tx id]
                      (seq (store/search-rows
                            st tx :posting
                            [{:target :data :field :kind :op := :value "sitting"}
                             {:target :data :field :source_id :op := :value (str id)}]
                            {:limit 1})))
            due (store/with-tx st
                  (fn [tx]
                    (into []
                          (comp (map #(inv/decode-row rdef %))
                                (filter unopened?)
                                (remove #(posted? tx (:id %))))
                          (store/query-rows st tx :sitting {} {:all true}))))]
        (count
         (filterv
          (fn [row]
            (try (inv/invoke! eng :sitting (str (:id row)) :open_ledger
                              (cond-> {:basis "opening"}
                                (get-in row [:data :prices])
                                (assoc :prices (get-in row [:data :prices])))
                              {:principal seats-actor})
                 true
                 (catch Exception e
                   (binding [*out* *err*]
                     (println "WARN: sitting" (:id row) "was not opened onto the ledger:"
                              (ex-message e)))
                   false)))
          due))))))

(defn ledger-agreement
  "The opening's agreement check (docs/spec-spend.md §8): for each seat,
  the week's wall as it read before spend step 5 — its sittings started
  in the window, closed or open (`grants/seat-window-conds`) — beside the
  ledger's, its spend postings by `effective_at` over the same seven
  days. → one entry per seat whose numbers differ, {:seat :wall :ledger
  :sittings}, each sitting {:id :wall :ledger} whose two shares differ:
  most often one that started before the window and ended inside it.
  The difference is intended (§5); this lists it, with its cause."
  [eng ^Instant now]
  (let [res (inv/resources eng)]
    (if-not (and (get res :seat) (get res :sitting) (get res :posting))
      []
      (let [st (:storage eng)
            since (.minusSeconds now (long grants/budget-window-seconds))
            amount-of #(let [m (money/from-wire (get-in % [:data :amount]) nil)]
                         (if (money/money? m) (:amount m) 0M))
            total #(reduce + 0M (vals %))]
        (into []
              (keep
               (fn [seat]
                 (let [id (str (:id seat))
                       [sittings ps]
                       (store/with-tx st
                         (fn [tx]
                           [(store/search-rows st tx :sitting
                                               (grants/seat-window-conds id since ["closed" "open"])
                                               {:all true})
                            (store/search-rows
                             st tx :posting
                             [{:target :data :field :account :op :=
                               :value (postings/account-address :seat id :spend)}
                              {:target :data :field :effective_at :cast "timestamptz"
                               :op :>= :value (str since)}]
                             {:all true})]))
                       wall (into {} (map (fn [s] [(str (:id s))
                                                   (dollars-of (get-in s [:data :cost_usd]))]))
                                  sittings)
                       ledger (reduce (fn [m p]
                                        (update m (str (get-in p [:data :source_id]))
                                                (fnil + 0M) (amount-of p)))
                                      {} ps)]
                   (when-not (zero? (compare (total wall) (total ledger)))
                     {:seat id
                      :wall (total wall)
                      :ledger (total ledger)
                      :sittings (into []
                                      (keep (fn [sid]
                                              (let [w (get wall sid 0M) l (get ledger sid 0M)]
                                                (when-not (zero? (compare w l))
                                                  {:id sid :wall w :ledger l}))))
                                      (sort (into (set (keys wall)) (keys ledger))))}))))
              (store/with-tx st
                (fn [tx] (store/query-rows st tx :seat {} {:all true}))))))))

;; ── a seat's spend by domain (epic aff24e84, piece 5) ───────────────

(defn domain-name-of
  "The name of the domain this seat is in: its stored domain's, read
  through `read'`, or `default-domain` when the row names none. A
  derived default, and nothing is stored."
  [read' row]
  (if-some [id (some-> (get-in row [:data :domain]) str not-empty)]
    (some-> (read' :domain id) (get-in [:data :name]) str)
    default-domain))

(defn spend-by-domain
  "The dollars this seat's spend postings of the wall's window add, by
  the domain each sitting was worked for (epic aff24e84, piece 5;
  docs/spec-spend.md §5): a map from the domain's name to dollars. A
  posting counts by its `effective_at`, as the wall counts it, and is
  charged through the sitting it names. A sitting is charged to the domain that
  asked for the row it walked, which is the row's `requested_by` when
  it has one and otherwise its `domain`; a row that stores neither
  counts as `default-domain`. A sitting that walked several rows is
  shared evenly among them, and one that walked none is charged to the
  seat's own domain. `leased-row-ids` is seats.clj's (find' where) →
  the subject ids a sitting's leases held, passed in so this file
  requires nothing of seats."
  [leased-row-ids read' find' row ^java.time.Instant now]
  (let [since (.minusSeconds now (long grants/budget-window-seconds))
        walk (some-> (get-in row [:data :walk]) str not-empty keyword)
        own (or (domain-name-of read' row) default-domain)
        asked-by (fn [id]
                   (let [d (:data (when walk (read' walk id)))]
                     (or (some-> (:requested_by d) str not-empty)
                         (some-> (:domain d) str not-empty)
                         default-domain)))]
    (reduce (fn [acc p]
              (let [cost (posting-dollars p)
                    s (some->> (get-in p [:data :source_id]) str not-empty
                               (read' :sitting))
                    rows (when s (leased-row-ids find' {:holder_id (str (:id s))}))
                    names (if (seq rows) (map asked-by rows) [own])
                    share (with-precision 20 (/ cost (count names)))]
                (reduce #(update %1 (keyword %2) (fnil + 0M) share) acc names)))
            {}
            (filter #(in-window? since now %)
                    (find' :posting
                           {:account (postings/account-address :seat (str (:id row)) :spend)}
                           {:limit 10000})))))

;; ── the budget doors ─────────────────────────────────────────────────

(defhandler close-breaker [row _inp _ctx]
  ;; seat health 3: the stated `max_open_sittings` was never lowered, so
  ;; dropping the mark is the whole of it. The breach stays recorded,
  ;; and only a NEW breach opens the breaker again.
  (update row :data dissoc :breaker_open))

(defn field-moved?
  "Did a restate change this field? One spelling, because two doors
  ask it: the note guard below, and the halt line the restate lifts
  (R-1 of waymark-fp62.7.13).

  A LIST OF REFS compares as strings — a ref is an id, whatever type
  carried it here — and a DECIMAL compares by value and not by scale,
  because 12 and 12.00 are the same dollars and a line lifted by a
  scale is a record dropped by nothing."
  [row inp f]
  (let [was (get-in row [:data f])
        asked (get inp f)]
    (cond
      (or (vector? was) (vector? asked))
      (not= (mapv str asked) (mapv str was))

      (and (decimal? was) (decimal? asked))
      (not (zero? (compare was asked)))

      :else (not= was asked))))

(def restatable
  "The fields a `restate` states again. `name` is not among them (one
  spelling per seat) and neither is anything the engine writes."
  [:charter :instructions :mode :scope :held_for
   :substitute_for
   :standing_ttl_seconds :cadence_seconds :sitting_idle_seconds
   :keep_transcripts :transcript_days :inbox :feed_url
   :budget_usd_per_week
   :sitting_budget_tokens :walk :judgment
   :rows_per_firing :fire_interval_seconds :max_open_sittings
   :release_grace_seconds :delivers :delegates :acts_as_cast])

(def ^:private wall-inputs
  "The seat field each wall is judged against, for the walls a person
  states again (R-1 of waymark-fp62.7.13). `seat_not_active` is not
  here: its input is the seat's STATE, and `unpark` is the door that
  moves it. `sitting_budget_reached` is not here either: its input is
  a sitting's running count, which no restate touches."
  {"budget_reached" [:budget_usd_per_week]
   "model_not_held" [:held_for :substitute_for]})

(defn lifts-the-line?
  "Does this restate change the input of the wall the line records?
  PURE, row against input: a restate is a person changing the law,
  and the next request judges the new law and writes the line again
  if the wall still holds. The handler never sums the week."
  [row inp]
  (boolean (some #(field-moved? row inp %)
                 (get wall-inputs (str (get-in row [:data :halt :reason]))))))

(defhandler set-seat-budget [row inp _ctx]
  ;; the restate's own rule for the halt line: a new budget lifts a
  ;; `budget_reached` line in the same transaction
  (cond-> (assoc-in row [:data :budget_usd_per_week] (:budget_usd_per_week inp))
    (lifts-the-line? row inp) (update :data dissoc :halt)))

(defn- same-field? [row inp f]
  (or (= (get inp f) (get-in row [:data f]))
      (not (field-moved? row inp f))))

(defn- mayor-moves-only-the-budget?
  "Is this restate the one a domain's mayor makes with no tap: `author`
  is the mayor of the domain the seat is in, and the body states every
  field as the row holds it except the week's budget."
  [row inp ctx author]
  (and (some? (:id row))
       (every? #(same-field? row inp %)
               (remove #{:budget_usd_per_week} restatable))
       (or (not (contains? inp :domain))
           (= (str (:domain inp)) (str (get-in row [:data :domain]))))
       (or (not (contains? inp :serves))
           (= (:serves inp) (get-in row [:data :serves])))
       (= (str (:id author))
          (some-> (domain-row-of ctx (get-in row [:data :domain]))
                  (get-in [:data :mayor])
                  str))))

(delegation/mayors-own-domain! #'mayor-moves-only-the-budget?)
