(ns calendar10.translation-test
  "The pure half of the transport: an authority event → our document
  and back, the identity split, the env parsing, and the two traps
  worth pinning — Google's exclusive all-day end, and a non-JSON error
  body. No network, no database."
  (:require [calendar10.source :as src]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; a timed event as API v3 actually sends it
(def timed
  {:id "6bl1c9k" :etag "\"318116\""
   :summary "Piano recital"
   :location "Community hall"
   :description "Bring the folding chairs"
   :htmlLink "https://calendar.google.com/event?eid=6bl1c9k"
   :start {:dateTime "2026-08-01T18:30:00-06:00"}
   :end {:dateTime "2026-08-01T20:00:00-06:00"}})

(def all-day
  {:id "picnicday" :etag "\"318117\""
   :summary "Family picnic"
   ;; Google's end.date is the morning AFTER the last day
   :start {:date "2026-08-08"}
   :end {:date "2026-08-10"}
   :transparency "transparent"})

;; ── event → document ────────────────────────────────────────────────

(deftest a-timed-event
  (let [doc (src/event->doc "family" timed "America/Denver")]
    (testing "the date is the START's date in the household zone"
      (is (= "2026-08-01" (:date doc)))
      (is (= "2026-08-02T00:30:00Z" (:starts_at doc))
          "the instant is canonical UTC — 18:30 MDT")
      (is (= "2026-08-02T02:00:00Z" (:ends_at doc))))

    (testing "a same-day event's end_date is its date, never before it"
      (is (= "2026-08-01" (:end_date doc))))

    (testing "opaque is the default, and it means the evening is spoken for"
      (is (= "blocking" (:kind doc))
          "the design note event.clj recorded — answered by transparency"))

    (is (= {:title "Piano recital" :all_day false :location "Community hall"
            :detail "Bring the folding chairs" :recurring false
            :calendar "family"}
           (select-keys doc [:title :all_day :location :detail :recurring
                             :calendar])))))

(deftest the-all-day-fencepost
  (let [doc (src/event->doc "family" all-day "America/Denver")]
    (testing "Google's exclusive end becomes our inclusive last day"
      (is (= "2026-08-08" (:date doc)))
      (is (= "2026-08-09" (:end_date doc))
          "end.date 08-10 is the morning after — the picnic ends on the 9th"))
    (is (true? (:all_day doc)))
    (is (nil? (:starts_at doc)) "an all-day event names no instant")
    (is (= "note" (:kind doc)) "transparent = free = not spoken for")))

(deftest the-zone-decides-the-date
  (testing "a late-evening event belongs to the day the family lived it"
    (let [late {:id "x" :summary "Late" :start {:dateTime "2026-08-01T23:30:00-06:00"}
                :end {:dateTime "2026-08-02T00:30:00-06:00"}}]
      (is (= "2026-08-01" (:date (src/event->doc "family" late "America/Denver")))
          "23:30 MDT is the 1st here even though it is the 2nd in UTC")
      (is (= "2026-08-02" (:date (src/event->doc "family" late "UTC")))
          "and the 2nd for a household that keeps UTC"))))

(deftest the-gaps-render
  (testing "a title-less event is Untitled, not nil"
    (is (= "Untitled" (:title (src/event->doc "family" {:id "x" :start {:date "2026-08-01"}
                                                        :end {:date "2026-08-02"}}
                                              "UTC")))))
  (testing "absent optional fields are nil, not empty strings"
    (let [doc (src/event->doc "family" {:id "x" :summary "Bare" :location ""
                                        :start {:date "2026-08-01"}
                                        :end {:date "2026-08-02"}}
                              "UTC")]
      (is (nil? (:location doc)))
      (is (nil? (:detail doc))))))

(deftest an-instance-knows-it-is-one
  (is (true? (:recurring (src/event->doc "family"
                                         (assoc timed :recurringEventId "series1")
                                         "UTC")))))

;; ── document → event ────────────────────────────────────────────────

(deftest writes-put-the-fencepost-back
  (let [body (src/doc->event {:title "Family picnic" :date "2026-08-08"
                              :end_date "2026-08-09" :all_day true
                              :kind "note"})]
    (is (= {:date "2026-08-08"} (:start body)))
    (is (= {:date "2026-08-10"} (:end body))
        "our inclusive 9th is Google's exclusive 10th")
    (is (= "transparent" (:transparency body)))))

(deftest a-timed-write
  (let [body (src/doc->event {:title "Piano recital" :all_day false
                              :starts_at "2026-08-02T00:30:00Z"
                              :ends_at "2026-08-02T02:00:00Z"
                              :kind "blocking" :location "Hall"
                              :detail "Chairs"})]
    (is (= {:dateTime "2026-08-02T00:30:00Z"} (:start body)))
    (is (= {:dateTime "2026-08-02T02:00:00Z"} (:end body)))
    (is (= "opaque" (:transparency body)))
    (is (= "Hall" (:location body)))
    (is (= "Chairs" (:description body)))))

(deftest the-round-trip-holds
  (testing "an all-day event survives the inclusive/exclusive trip exactly"
    (let [back (src/doc->event (src/event->doc "family" all-day "America/Denver"))]
      (is (= (:summary all-day) (:summary back)))
      (is (= (:start all-day) (:start back)))
      (is (= (:end all-day) (:end back))
          "08-10 out is 08-10 back, having been the 9th in between")))

  (testing "a timed event survives as the same INSTANT, spelled UTC"
    (let [back (src/doc->event (src/event->doc "family" timed "America/Denver"))
          inst (fn [s] (.toInstant (java.time.OffsetDateTime/parse s)))]
      (is (= (inst "2026-08-01T18:30:00-06:00")
             (inst (:dateTime (:start back)))))
      (is (= (inst "2026-08-01T20:00:00-06:00")
             (inst (:dateTime (:end back))))))))

(deftest a-write-must-name-its-start
  (testing "an all-day document with no date is refused by name, not by a 400"
    (is (thrown-with-msg? Exception #"all-day calendar event needs a date"
                          (src/writable! {:title "x" :all_day true}))))
  (testing "a timed document with no instant likewise"
    (is (thrown-with-msg? Exception #"timed calendar event needs a start"
                          (src/writable! {:title "x" :all_day false}))))
  (testing "a well-formed document passes through unchanged"
    (let [doc {:title "x" :all_day false :starts_at "2026-08-01T00:00:00Z"}]
      (is (= doc (src/writable! doc))))))

;; ── identity, env, and the two traps ────────────────────────────────

(deftest identity-splits
  (is (= "family:abc" (src/xid "family" "abc")))
  (is (= ["family" "abc"] (src/split-xid "family:abc")))
  (testing "a google id containing a colon still splits on the FIRST one"
    (is (= ["family" "abc:def"] (src/split-xid "family:abc:def"))))
  (testing "a tagless id is a programming error, and says so"
    (is (thrown-with-msg? Exception #"carries no tag:id split"
                          (src/split-xid "abc")))))

(deftest calendars-parse
  (is (= {"family" "primary"} (src/parse-calendars "family=primary")))
  (is (= {"family" "primary"} (src/parse-calendars "primary"))
      "a bare id takes the family tag — one calendar stays one word")
  (is (= {"family" "primary" "colton" "abc@group.calendar.google.com"}
         (src/parse-calendars "family=primary,colton=abc@group.calendar.google.com")))
  (is (nil? (src/parse-calendars "")))
  (is (nil? (src/parse-calendars nil))))

(deftest the-window-is-rfc-3339
  (let [{:keys [timeMin timeMax]} (src/window-params "America/Denver")]
    (testing "an offset, never a zone-region suffix — Google refuses the latter"
      (is (not (str/includes? timeMin "[")) timeMin)
      (is (re-matches #"\d{4}-\d{2}-\d{2}T00:00:00[+-]\d{2}:\d{2}" timeMin)
          timeMin))
    (is (neg? (compare timeMin timeMax)))))

(deftest a-non-json-error-body-still-carries-its-status
  ;; the bug waymark-t6s records, in the shape it actually bit: a
  ;; restarting proxy answers plain text, and parsing before judging
  ;; the status throws a RAW parse exception no caller's catch expects
  (let [thrown (try (src/parse-body "Bad Gateway" 502)
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (instance? clojure.lang.ExceptionInfo thrown)
        "a plain-text body must not escape as a raw parse exception")
    (is (= 502 (:status (ex-data thrown)))
        "and the real status has to survive the wrapping"))
  (testing "an empty body is simply nil — a 204 is not a failure"
    (is (nil? (src/parse-body "" 204)))
    (is (nil? (src/parse-body nil 204))))
  (testing "real JSON still parses"
    (is (= {:id "x"} (src/parse-body "{\"id\":\"x\"}" 200)))))
