(ns waymark10.windows-test
  "A measure's aligned windows (docs/spec-measures.md §2, build step 3):
  days at midnight, weeks on the calendar's first weekday, months on
  the first, all in the calendar's zone and counted from 2026-01-05.
  Pure: no database."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.expr :as expr]
            [waymark10.windows :as windows])
  (:import (java.time Instant LocalDate ZoneId)))

(def ^:private weekdays #{:mon :tue :wed :thu :fri})

(defn- cal [zone & [workdays]]
  {:zone (ZoneId/of zone) :workdays (or workdays weekdays) :holidays (sorted-map)})

(defn- at [s] (Instant/parse s))

(deftest a-window-across-a-zone-change
  (testing "the day Denver's clocks spring forward is 23 hours long"
    (let [w (windows/window '(days 1) (cal "America/Denver") (at "2026-03-08T17:00:00Z"))]
      (is (= (LocalDate/of 2026 3 8) (:from w)))
      (is (= (at "2026-03-08T07:00:00Z") (:start w)))
      (is (= (at "2026-03-09T06:00:00Z") (:end w)))))
  (testing "a week across the change starts and ends at local midnight"
    (let [w (windows/window '(weeks 1) (cal "America/Denver") (at "2026-03-08T17:00:00Z"))]
      (is (= (LocalDate/of 2026 3 2) (:from w)))
      (is (= (LocalDate/of 2026 3 9) (:until w)))
      (is (= (at "2026-03-02T07:00:00Z") (:start w)))
      (is (= (at "2026-03-09T06:00:00Z") (:end w)))))
  (testing "an instant is read in the house's zone, not UTC"
    (let [w (windows/window '(days 1) (cal "America/Denver") (at "2026-03-10T03:00:00Z"))]
      (is (= (LocalDate/of 2026 3 9) (:from w))))))

(deftest a-month-in-february
  (let [w (windows/window '(months 1) (cal "UTC") (at "2026-02-14T12:00:00Z"))]
    (is (= (LocalDate/of 2026 2 1) (:from w)))
    (is (= (LocalDate/of 2026 3 1) (:until w)))
    (is (= (at "2026-03-01T00:00:00Z") (:end w))))
  (testing "three months count from January, the epoch's month"
    (let [w (windows/window '(months 3) (cal "UTC") (LocalDate/of 2026 2 28))]
      (is (= (LocalDate/of 2026 1 1) (:from w)))
      (is (= (LocalDate/of 2026 4 1) (:until w))))))

(deftest weeks-align-to-the-epoch-and-the-calendar
  (testing "(weeks 4) is the same four weeks for every measure"
    (let [w (windows/window '(weeks 4) (cal "UTC") (LocalDate/of 2026 10 22))]
      (is (= (LocalDate/of 2026 10 12) (:from w)) "ten blocks of 28 days after the epoch")
      (is (= (LocalDate/of 2026 11 9) (:until w)))))
  (testing "a calendar that works Tuesday to Saturday starts its weeks Tuesday"
    (let [w (windows/window '(weeks 1) (cal "UTC" #{:tue :wed :thu :fri :sat})
                            (LocalDate/of 2026 1 5))]
      (is (= (LocalDate/of 2025 12 30) (:from w)))
      (is (= (LocalDate/of 2026 1 6) (:until w))))))

(deftest a-window-is-deterministic-for-any-instant
  (let [c (cal "Europe/London")]
    (doseq [over ['(days 3) '(weeks 2) '(months 2)]
            s ["2025-06-30T23:30:00Z" "2026-01-05T00:00:00Z" "2026-10-25T00:30:00Z"
               "2031-02-28T12:00:00Z"]]
      (let [i (at s)
            w (windows/window over c i)]
        (is (= w (windows/window over c i)))
        (is (windows/within? w i) (str over " " s))
        (is (= w (windows/window over c (:start w))) "a window's start is in it")
        (is (not (windows/within? w (:end w))) "its end is the next one's start")
        (is (= (:end w) (:start (windows/window over c (:end w)))))))))

(deftest current-reads-to-date
  (let [now (at "2026-10-22T15:00:00Z")
        w (windows/current '(months 1) (cal "UTC") now)]
    (is (= (LocalDate/of 2026 10 1) (:from w)))
    (is (= now (:to w)))))

(deftest no-calendar-no-window
  (is (expr/unknown? (windows/window '(days 1) expr/unknown (at "2026-01-05T00:00:00Z"))))
  (is (nil? (windows/window '(years 1) (cal "UTC") (at "2026-01-05T00:00:00Z"))))
  (is (nil? (windows/period '(days 0))))
  (is (= {:unit :weeks :n 4} (windows/period '(weeks 4)))))
