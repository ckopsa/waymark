(ns waymark10.windows
  "A measure's windows (docs/spec-measures.md §2 \"Windows\", build step
  3): ALIGNED, not rolling. `(days n)` windows start at midnight,
  `(weeks n)` on the first weekday of the governing work_calendar and
  `(months n)` on the first of a month, all in the calendar's zone, and
  every one counts from the epoch 2026-01-05 (a Monday) — so `(weeks 4)`
  is the same four weeks for every measure that says it.

  Pure: the calendar is the value the `:calendar` hook answers
  (work-calendar/governing), and a window is a function of it, the
  period and one instant or date. A window is half-open, [start, end):
  a row belongs to the window its :at falls in. With no governing
  calendar the window is `expr/unknown`, never UTC by accident.

  The dashboard slot's rolling form is not this and is unchanged."
  (:require [waymark10.expr :as expr]
            [waymark10.server.work-calendar :as work-calendar])
  (:import (java.time Instant LocalDate ZoneId)
           (java.time.temporal ChronoUnit)))

(set! *warn-on-reflection* true)

(def ^LocalDate epoch
  "The day every window counts from: a Monday."
  (LocalDate/of 2026 1 5))

(def ^:private units #{'days 'weeks 'months})

(defn period
  "The period a measure's :over form names — `(days n)`, `(weeks n)` or
  `(months n)` with n a positive whole number — as {:unit :n}, or nil.
  Read from the form and not from expr's Period, because expr makes
  `(weeks n)` the law `(days (* 7 n))` and a week window aligns to the
  calendar where a seven-day window does not."
  [form]
  (when (and (seq? form) (= 2 (count form)))
    (let [[op n] form]
      (when (and (symbol? op) (contains? units (symbol (name op)))
                 (int? n) (pos? (long n)) (<= (long n) Integer/MAX_VALUE))
        {:unit (keyword (name op)) :n (long n)}))))

(defn- first-weekday
  "The calendar's first workday in week order, Monday first: the day its
  weeks start on. Monday when it names none."
  [cal]
  (let [ws (set (map name (:workdays cal)))]
    (or (first (keep-indexed (fn [i d] (when (contains? ws d) i))
                             work-calendar/weekdays))
        0)))

(defn- start-date
  "The first day of the window `d` falls in."
  ^LocalDate [{:keys [unit n]} cal ^LocalDate d]
  (let [n (long n)]
    (case unit
      :days
      (.plusDays epoch (* n (Math/floorDiv (.between ChronoUnit/DAYS epoch d) n)))

      :weeks
      (let [anchor (.plusDays epoch (long (first-weekday cal)))
            span (* 7 n)]
        (.plusDays anchor (* span (Math/floorDiv (.between ChronoUnit/DAYS anchor d) span))))

      :months
      (let [first-of (LocalDate/of (.getYear epoch) (.getMonthValue epoch) 1)
            m (.between ChronoUnit/MONTHS first-of (.withDayOfMonth d 1))]
        (.plusMonths first-of (* n (Math/floorDiv m n)))))))

(defn- end-date
  "The first day after the window that starts on `from`."
  ^LocalDate [{:keys [unit n]} ^LocalDate from]
  (let [n (long n)]
    (case unit
      :days (.plusDays from n)
      :weeks (.plusWeeks from n)
      :months (.plusMonths from n))))

(defn- date-in
  "The date `at` names in `zone`: a date is itself, an instant is read
  in the zone; nil for anything else."
  [at ^ZoneId zone]
  (cond (instance? LocalDate at) at
        (instance? Instant at) (.toLocalDate (.atZone ^Instant at zone))
        :else nil))

(defn window
  "The aligned window `at` (an Instant or a LocalDate) falls in, for the
  period `over` (a form or what `period` answers) under the calendar
  `cal` (the :calendar hook's answer): {:from :until} the first day and
  the first day after, {:start :end} the instants they begin at in the
  calendar's zone — a day the clocks change is as long as it is there.
  `expr/unknown` when `cal` is; nil for a period or an `at` that is
  not one."
  [over cal at]
  (if (expr/unknown? cal)
    expr/unknown
    (let [p (if (map? over) over (period over))
          zone (:zone cal)]
      (when (and p (instance? ZoneId zone))
        (when-some [d (date-in at zone)]
          (let [from (start-date p cal d)
                until (end-date p from)]
            {:from from
             :until until
             :start (.toInstant (.atStartOfDay from ^ZoneId zone))
             :end (.toInstant (.atStartOfDay until ^ZoneId zone))}))))))

(defn within?
  "True when `at` (an Instant or a LocalDate) falls in window `w`:
  from its start, before its end."
  [w at]
  (boolean
   (when (map? w)
     (cond (instance? Instant at)
           (and (not (.isBefore ^Instant at ^Instant (:start w)))
                (.isBefore ^Instant at ^Instant (:end w)))
           (instance? LocalDate at)
           (and (not (.isBefore ^LocalDate at ^LocalDate (:from w)))
                (.isBefore ^LocalDate at ^LocalDate (:until w)))))))

(defn current
  "The window a sweep at `now` reads: the one `now` falls in, to date —
  its :end and :until stay the window's, and :to is `now`, where the
  reading stops."
  [over cal ^Instant now]
  (let [w (window over cal now)]
    (if (map? w) (assoc w :to now) w)))
