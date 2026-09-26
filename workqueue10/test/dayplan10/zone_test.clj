(ns dayplan10.zone-test
  "One household, one clock (docs/spec-dayplan.md § 'The day
  boundary'; waymark-rptq). `dayplan10.zone` is the ONE read of
  WORKQUEUE10_ZONE → WORKQUEUE10_HA_ZONE → UTC, and a template's
  local clock times become instants in that zone.

  This file also proved, until the feed was retired (2026-09), that
  the household's feed recipe carried the same zone and that the
  feed's day rolled at LOCAL midnight. The feed went; the clock stays,
  and so does its proof with no database.

  Run: cd workqueue10 && clojure -M:test --focus dayplan10.zone-test"
  (:require [clojure.test :refer [deftest is testing]]
            [dayplan10.zone :as zone])
  (:import (java.time LocalDate ZoneId)))

(deftest the-household-has-one-zone
  (testing "the zone is read once and is a real zone this JVM knows"
    (is (instance? ZoneId (zone/id)))
    (is (identical? (zone/id) (zone/id))
        "a delay, read once — two reads are the same clock")))

(deftest a-window-is-a-pair-of-instants-in-that-zone
  (testing "a template's clock times read as HH:MM or not at all"
    (is (some? (zone/clock-time "09:00")))
    (is (nil? (zone/clock-time "9am")))
    (is (nil? (zone/clock-time "25:00"))))
  (testing "and a window on a date is two instants, in the household zone"
    (let [d (LocalDate/parse "2026-09-05")
          {:keys [starts_at ends_at]} (zone/window->instants
                                       d {:from "09:00" :to "12:00"})]
      (is (= (zone/at d (zone/clock-time "09:00")) starts_at))
      (is (= 3 (.toHours (java.time.Duration/between starts_at ends_at))))
      (is (= "09:00" (zone/clock starts_at))
          "read back on a card as the household reads it")))
  (testing "a window the clock cannot read is nil, not a guess"
    (is (nil? (zone/window->instants (LocalDate/parse "2026-09-05")
                                     {:from "nine" :to "12:00"})))))
