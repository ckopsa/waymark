(ns waymark10.recurs
  "`:recurs` (docs/spec-calendar-time.md §4, build step 8a): a kind
  declares a rule that makes rows of another kind on dates, and the
  dates are a pure function of the rule, the source row's data and the
  house's calendar — so a recurrence scenario judges them at the check
  tier. Nothing here makes a row or schedules one; arming is step 8b.

  A rule is {:name :makes :every :on :from :until :times :while
  :holidays :lead :owner :input}. :every, :from, :until, :lead and
  :owner are law expressions over the source row; :on is a set of
  weekday tokens; :holidays is :skip (the default), :next, :previous or
  :keep; :input is the made kind's create input, where (occurrence) is
  the occurrence's date.

  Occurrences are listed from :from, one period every :every: at each
  :on weekday in the period's first week — so every 2 weeks on Tuesday
  is the Tuesday of every other week — or at the period's first day
  when there is no :on. A date the calendar keeps as a holiday is then
  moved under :holidays: dropped, walked to the next or previous
  business day, or kept. The list is cut at :until and :times, and
  never starts before :from."
  (:require [waymark10.expr :as expr])
  (:import (java.time LocalDate Period)
           (java.time.format DateTimeParseException)
           (java.util Locale)))

(set! *warn-on-reflection* true)

(def rule-keys
  "One rule's whole authored surface."
  #{:name :makes :every :on :from :until :times :while :holidays :lead :owner :input})

(def holiday-moves #{:skip :next :previous :keep})

(def weekdays [:mon :tue :wed :thu :fri :sat :sun])

(def ^:private occurrence-cap
  "How many occurrences one listing answers at most: a rule kept going
  only by :while lists this many and stops."
  1000)

(def ^:private period-cap
  "How many periods one listing walks at most."
  1000)

(def ^:private walk-cap
  "How many days a holiday move may walk before it gives up."
  366)

;; ── the shape ───────────────────────────────────────────────────────

(defn rule-problems
  "What is wrong with one :recurs rule of `rdef` as a shape, as
  sentences; empty when nothing is. The findings that need the
  registry — the made kind and its create door — are
  checks-assembly's."
  [rdef rule]
  (if-not (map? rule)
    [(str "a :recurs rule is {:name :makes :every :from …}; " (pr-str rule) " is not")]
    (let [extra (remove rule-keys (keys rule))
          rname (pr-str (:name rule))
          expr? #(or (nil? %) (expr/well-formed? %))]
      (into []
            (remove nil?)
            [(when (seq extra)
               (str "rule " rname " declares " (vec (sort extra)) ", keys a rule does not know"))
             (when-not (keyword? (:name rule))
               "a :recurs rule is named by a keyword :name")
             (when (< 1 (count (filter #(= (:name rule) (:name %)) (:recurs rdef))))
               (str "two :recurs rules answer to the name " rname))
             (when-not (keyword? (:makes rule))
               (str "rule " rname " names the kind it :makes by a keyword"))
             (when-not (and (some? (:every rule)) (expr? (:every rule)))
               (str "rule " rname " says how often in :every, a period such as (weeks 2)"))
             (when-not (and (some? (:from rule)) (expr? (:from rule)))
               (str "rule " rname " says where it starts in :from, a date such as (data :starts_on)"))
             (when-not (every? #(expr? (get rule %)) [:until :lead :owner])
               (str "rule " rname "'s :until, :lead and :owner are law expressions"))
             (when-not (or (nil? (:on rule))
                           (and (set? (:on rule)) (seq (:on rule)) (every? (set weekdays) (:on rule))))
               (str "rule " rname "'s :on is a set of weekdays, #{:mon :tue …}"))
             (when-not (contains? holiday-moves (:holidays rule :skip))
               (str "rule " rname "'s :holidays is one of " (vec (sort holiday-moves))))
             (when-not (or (nil? (:times rule)) (pos-int? (:times rule)))
               (str "rule " rname "'s :times is a positive count"))
             (when-not (or (nil? (:while rule))
                           (and (set? (:while rule)) (seq (:while rule))
                                (every? (set (:states rdef)) (:while rule))))
               (str "rule " rname "'s :while is a set of " (name (:kind rdef)) "'s states, "
                    (pr-str (vec (:states rdef)))))
             (when-not (or (nil? (:input rule))
                           (and (map? (:input rule)) (every? keyword? (keys (:input rule)))))
               (str "rule " rname "'s :input is {field form}, the made row's create input"))]))))

;; ── the dates ───────────────────────────────────────────────────────

(defn- weekday
  "A date's weekday token, :mon to :sun."
  [^LocalDate d]
  (keyword (subs (.toLowerCase (.name (.getDayOfWeek d)) Locale/ROOT) 0 3)))

(defn- date-of
  "A date the rule reads: a LocalDate as it is, an ISO date string
  parsed — a row's data as the scenario writes it — else nil."
  ^LocalDate [v]
  (cond (instance? LocalDate v) v
        (string? v) (try (LocalDate/parse ^String v) (catch DateTimeParseException _ nil))
        :else nil))

(defn- value
  "One of the rule's expressions over the source row's data, with the
  calendar as the scope's :calendar hook; nil when it is not declared
  or answers unknown."
  [form data calendar]
  (when (some? form)
    (let [v (expr/evaluate (expr/normalize form)
                           (cond-> {:data data}
                             calendar (assoc :calendar (fn ([] calendar) ([_] calendar)))))]
      (when-not (expr/unknown? v) v))))

(defn owner
  "The principal id the rule's :owner names for a source row with this
  data (spec R-4: whose act each made row is), or nil when it is not
  declared or answers nothing."
  [rule data]
  (some-> (value (:owner rule) data nil) str not-empty))

(defn- holiday? [calendar d]
  (contains? (:holidays calendar) d))

(defn- business? [calendar ^LocalDate d]
  (and (contains? (:workdays calendar) (weekday d))
       (not (holiday? calendar d))))

(defn- walk
  "The first business day of `calendar` past `d`, stepping `step` days;
  nil past the walk's cap."
  [calendar ^LocalDate d step]
  (loop [d (.plusDays d (long step)) walked 1]
    (cond (business? calendar d) d
          (>= walked walk-cap) nil
          :else (recur (.plusDays d (long step)) (inc walked)))))

(defn- moved
  "One listed date under the rule's :holidays: itself when the calendar
  keeps no holiday on it, else kept, walked, or nil when skipped."
  [move calendar ^LocalDate d]
  (if (and calendar (holiday? calendar d))
    (case move
      :keep d
      :next (walk calendar d 1)
      :previous (walk calendar d -1)
      nil)
    d))

(defn- period-dates
  "The dates of the period that starts on `start`: each `on` weekday in
  its first week, or `start` itself with none."
  [^LocalDate start on]
  (if (seq on)
    (for [i (range 7)
          :let [d (.plusDays start (long i))]
          :when (contains? on (weekday d))]
      d)
    [start]))

(defn occurrences
  "The rule's occurrences for a source row's `data` under `calendar`
  (what the :calendar hook answers, or nil: then no date is a holiday)
  — a vector of LocalDate in order, at most occurrence-cap of them, and
  [] when :from answers no date or :every no forward Period."
  [rule data calendar]
  (let [from (date-of (value (:from rule) data calendar))
        every (value (:every rule) data calendar)
        until (date-of (value (:until rule) data calendar))
        limit (min occurrence-cap (or (:times rule) occurrence-cap))
        on (set (:on rule))
        move (:holidays rule :skip)]
    (if-not (and from (instance? Period every)
                 (not (.isZero ^Period every)) (not (.isNegative ^Period every)))
      []
      (let [kept (fn [^LocalDate d]
                   (and (not (.isBefore d from))
                        (not (and until (.isAfter d until)))))]
        (loop [k 0 out (sorted-set)]
          (let [start (.plus from (.multipliedBy ^Period every (int k)))]
            (if (or (>= (count out) limit)
                    (>= k period-cap)
                    (and until (.isAfter start until)))
              (into [] (take limit) out)
              (recur (inc k)
                     (into out
                           (comp (keep #(moved move calendar %)) (filter kept))
                           (period-dates start on))))))))))

(defn next-occurrences
  "The first `n` of the rule's occurrences on or after `on-or-after` —
  what arming asks, with (today) and 1."
  [rule data calendar ^LocalDate on-or-after n]
  (into []
        (comp (remove #(.isBefore ^LocalDate % on-or-after)) (take n))
        (occurrences rule data calendar)))
