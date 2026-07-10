(ns mealplan10.batch-e-rrule-test
  "The RRULE expander (batch E), pure and table-driven: the profile
  real family calendars use — FREQ=DAILY/WEEKLY/MONTHLY with INTERVAL,
  BYDAY (weekly), UNTIL/COUNT, EXDATE — expands to per-date
  occurrences; anything outside the profile skips THAT event with a
  *err* warning naming the rule. No database."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [mealplan10.event-source :as es])
  (:import (java.time DayOfWeek LocalDate)))

(defn- d ^LocalDate [s] (LocalDate/parse s))

(defn- dates [res] (mapv str (:dates res)))

(def lo (d "2026-07-01"))
(def hi (d "2026-10-01"))

;; ── the expander, case by case ──────────────────────────────────────

(deftest the-supported-profile
  (testing "weekly recital, COUNT-limited, with an EXDATE"
    ;; COUNT counts the whole set FIRST (RFC 5545), then the EXDATE
    ;; removes its date: 15, [22 excluded], 29 — never a 4th
    (is (= ["2026-07-15" "2026-07-29"]
           (dates (es/expand-rrule (d "2026-07-15") "FREQ=WEEKLY;COUNT=3"
                                   #{(d "2026-07-22")} lo hi)))))

  (testing "biweekly with BYDAY lands both days on the on-weeks only"
    (is (= ["2026-07-14" "2026-07-16" "2026-07-28" "2026-07-30"
            "2026-08-11" "2026-08-13"]
           (dates (es/expand-rrule (d "2026-07-14")
                                   "FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,TH"
                                   #{} lo (d "2026-08-15"))))))

  (testing "monthly by day-of-month skips months without that day"
    (is (= ["2026-01-31" "2026-03-31" "2026-05-31" "2026-07-31"]
           (dates (es/expand-rrule (d "2026-01-31") "FREQ=MONTHLY;COUNT=4"
                                   #{} (d "2026-01-01") (d "2026-12-31"))))
        "Feb/Apr/Jun produce no occurrence and do not count against COUNT"))

  (testing "a single BYMONTHDAY"
    (is (= ["2026-07-05" "2026-08-05" "2026-09-05"]
           (dates (es/expand-rrule (d "2026-07-05")
                                   "FREQ=MONTHLY;BYMONTHDAY=5"
                                   #{} lo hi)))))

  (testing "daily with INTERVAL and an inclusive UNTIL"
    (is (= ["2026-07-15" "2026-07-18" "2026-07-21" "2026-07-24"]
           (dates (es/expand-rrule (d "2026-07-15")
                                   "FREQ=DAILY;INTERVAL=3;UNTIL=20260724T000000Z"
                                   #{} lo hi)))))

  (testing "the window clips both ends"
    (is (= ["2026-07-01" "2026-07-08"]
           (dates (es/expand-rrule (d "2026-06-03") "FREQ=WEEKLY;UNTIL=20260708"
                                   #{} lo hi)))))

  (testing "lower-case rules parse (the grammar is case-insensitive)"
    (is (= ["2026-07-15" "2026-07-22"]
           (dates (es/expand-rrule (d "2026-07-15") "freq=weekly;count=2"
                                   #{} lo hi))))))

(deftest property-ish-weekly-grids
  ;; every weekly expansion keeps DTSTART's weekday, starts at
  ;; DTSTART, spaces by exactly INTERVAL weeks, and stays in-window
  (doseq [interval [1 2 3 4]]
    (let [start (d "2026-07-15")
          {:keys [dates]} (es/expand-rrule start
                                          (str "FREQ=WEEKLY;INTERVAL=" interval)
                                          #{} lo hi)]
      (is (= start (first dates)))
      (is (every? #(= DayOfWeek/WEDNESDAY (.getDayOfWeek ^LocalDate %)) dates))
      (is (every? #(zero? (mod (.between java.time.temporal.ChronoUnit/DAYS
                                         start ^LocalDate %)
                               (* 7 interval)))
                  dates))
      (is (every? #(and (not (.isBefore ^LocalDate % lo))
                        (not (.isAfter ^LocalDate % hi)))
                  dates)))))

(deftest the-recorded-boundary
  (doseq [[rule part] [["FREQ=WEEKLY;BYSETPOS=1" "BYSETPOS"]
                       ["FREQ=YEARLY" "FREQ=YEARLY"]
                       ["FREQ=MONTHLY;BYDAY=2TU" "BYDAY with FREQ=MONTHLY"]
                       ["FREQ=WEEKLY;BYDAY=2TU" "BYDAY=2TU"]
                       ["FREQ=MONTHLY;BYMONTHDAY=1,15" "BYMONTHDAY=1,15"]
                       ["FREQ=WEEKLY;INTERVAL=0" "INTERVAL=0"]
                       ["FREQ=WEEKLY;INTERVAL=2;WKST=SU" "WKST=SU"]]]
    (let [res (es/expand-rrule (d "2026-07-15") rule #{} lo hi)]
      (is (str/includes? (str (:unsupported res)) part)
          (str rule " names its unsupported part")))))

;; ── through the feed parser ─────────────────────────────────────────

(defn- vevent [& lines]
  (str "BEGIN:VEVENT\r\n"
       (apply str (map #(str % "\r\n") lines))
       "END:VEVENT\r\n"))

(defn- ics [& events]
  (str "BEGIN:VCALENDAR\r\n" (apply str events) "END:VCALENDAR\r\n"))

(defn- with-err
  "→ [value stderr-text]"
  [f]
  (let [sw (java.io.StringWriter.)]
    (binding [*err* sw]
      (let [v (f)] [v (str sw)]))))

(deftest feed-expansion-and-identity
  (testing "a recurring VEVENT becomes one occurrence per date, {uid}@{date}"
    (let [occs (es/feed-occurrences
                (ics (vevent "UID:recital"
                             "SUMMARY:Piano recital"
                             "DTSTART;VALUE=DATE:20260715"
                             "RRULE:FREQ=WEEKLY;COUNT=3"
                             "EXDATE;VALUE=DATE:20260722"))
                lo hi)]
      (is (= {"recital@2026-07-15" {:title "Piano recital"
                                    :date "2026-07-15" :kind "note"}
              "recital@2026-07-29" {:title "Piano recital"
                                    :date "2026-07-29" :kind "note"}}
             occs))))

  (testing "EXDATE accumulates across lines and comma lists"
    (let [occs (es/feed-occurrences
                (ics (vevent "UID:practice"
                             "SUMMARY:Practice"
                             "DTSTART:20260701T170000Z"
                             "RRULE:FREQ=WEEKLY;COUNT=6"
                             "EXDATE:20260708T170000Z,20260715T170000Z"
                             "EXDATE:20260729T170000Z"))
                lo hi)]
      (is (= #{"practice@2026-07-01" "practice@2026-07-22"
               "practice@2026-08-05"}
             (set (keys occs))))))

  (testing "an unsupported rule skips THAT event with a warning naming it"
    (let [[occs err] (with-err
                       #(es/feed-occurrences
                         (ics (vevent "UID:fancy"
                                      "SUMMARY:First Monday brunch"
                                      "DTSTART;VALUE=DATE:20260706"
                                      "RRULE:FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1")
                              (vevent "UID:plain"
                                      "SUMMARY:Dentist"
                                      "DTSTART;VALUE=DATE:20260710"))
                         lo hi))]
      (is (= #{"plain@2026-07-10"} (set (keys occs)))
          "the neighbor still expands — the boundary is per-event")
      (is (str/includes? err "fancy"))
      (is (str/includes? err "BYSETPOS"))
      (is (str/includes? err "FREQ=MONTHLY;BYDAY=MO;BYSETPOS=1")
          "the warning names the whole rule")))

  (testing "RDATE (occurrences we would silently lose) skips the event"
    (let [[occs err] (with-err
                       #(es/feed-occurrences
                         (ics (vevent "UID:extra"
                                      "SUMMARY:Movable feast"
                                      "DTSTART;VALUE=DATE:20260710"
                                      "RRULE:FREQ=WEEKLY;COUNT=2"
                                      "RDATE;VALUE=DATE:20260801"))
                         lo hi))]
      (is (empty? occs))
      (is (str/includes? err "RDATE"))))

  (testing "a corrupt DTSTART year drops per-event, never the feed"
    (let [occs (es/feed-occurrences
                (ics (vevent "UID:corrupt"
                             "SUMMARY:Year one"
                             "DTSTART;VALUE=DATE:00010101"
                             "RRULE:FREQ=WEEKLY")
                     (vevent "UID:fine"
                             "SUMMARY:Fine"
                             "DTSTART;VALUE=DATE:20260710"))
                lo hi)]
      (is (= #{"fine@2026-07-10"} (set (keys occs)))))))

;; ── the FakeEvents pathway ──────────────────────────────────────────

(deftest fake-events-recurrence-pathway
  (let [fake (es/fake-events)
        ids (es/seed-recurring! fake "uid-recital"
                                {:title "Piano recital"
                                 :kind "blocking"
                                 :dtstart "2026-07-15"
                                 :rrule "FREQ=WEEKLY;COUNT=3"
                                 :exdates ["2026-07-22"]
                                 :from "2026-07-01" :to "2026-10-01"})]
    (is (= ["uid-recital@2026-07-15" "uid-recital@2026-07-29"] ids))
    (is (= (set ids) (set (waymark10.server.mirror/discover fake)))
        "each occurrence is discoverable")
    (let [[doc _etag] (waymark10.server.mirror/pull fake "uid-recital@2026-07-29")]
      (is (= {:title "Piano recital" :date "2026-07-29" :kind "blocking"}
             doc)))
    (is (thrown-with-msg?
         Exception #"BYSETPOS"
         (es/seed-recurring! fake "uid-bad"
                             {:title "Bad" :dtstart "2026-07-15"
                              :rrule "FREQ=WEEKLY;BYSETPOS=2"
                              :from "2026-07-01" :to "2026-10-01"}))
        "a test seeding an unsupported rule gets the loud no")))
