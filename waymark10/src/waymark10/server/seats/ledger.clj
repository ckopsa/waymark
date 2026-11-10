(ns waymark10.server.seats.ledger
  "A domain's budget and its wall (epic aff24e84, piece 2;
  docs/spec-spend.md §5, spend step 8), carved out of
  `waymark10.server.seats` (ticket fdd28816, carve 2 of 4): the week's
  dollars, the domain row a seat reads as being in, the seats that spent
  most of a domain's week, and the two guards that judge a seat by its
  domain — `domain-under-budget` at the fire door and
  `budget-fits-the-domain` at a seat's create and restate.

  seats.clj keeps a one-line def for each var another namespace or its
  own resource calls, so every `seats/` alias still resolves. This file
  requires nothing of seats: seats requires it."
  (:require [clojure.string :as str]
            [waymark10.guards :as g]
            [waymark10.money :as money]
            [waymark10.server.grants :as grants]
            [waymark10.server.postings :as postings]
            [waymark10.types :as t]))

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
