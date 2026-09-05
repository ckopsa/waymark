(ns dayplan10.zone-test
  "One household, one clock (docs/spec-dayplan.md § 'The day
  boundary'; waymark-rptq). `feed/today` reads the recipe's `:zone`
  and defaulted it to UTC, and the household's recipe never set it —
  so the feed's day rolled at 18:00 Mountain: every evening the order
  reshuffled and afternoon cursors 409'd at dinner. The fix is one
  line at the app's build site, and this is its proof with no
  database: the household recipe carries the zone `dayplan10.zone`
  read from the environment, the recipe still assembles under
  `check-recipe!` with the day's line on top, and `today` under a
  fixed now-fn rolls at LOCAL midnight for a zone that is not UTC.

  Run: cd workqueue10 && clojure -M:test --focus dayplan10.zone-test"
  (:require [clojure.test :refer [deftest is testing]]
            [dayplan10.zone :as zone]
            [waymark10.server.feed :as feed]
            [workqueue10.main :as main])
  (:import (java.time Instant)))

(defn- eng-at [^String iso]
  {:now-fn (constantly (Instant/parse iso))})

(deftest the-household-recipe-carries-the-one-zone
  (testing "the recipe's zone is dayplan10.zone's one read — the same
            clock the plan's windows are minted in"
    (is (= (str (zone/id)) (:zone main/feed-recipe)))
    (is (string? (:zone main/feed-recipe))))
  (testing "and the recipe still assembles: the day's line first, the
            census in order, every population one the engine names"
    (is (= main/feed-recipe (feed/check-recipe! main/feed-recipe)))
    (is (= {:section :now :population :current_block :take 6}
           (select-keys (first (:order main/feed-recipe))
                        [:section :population :take])))))

(deftest today-rolls-at-local-midnight
  (let [denver (assoc main/feed-recipe :zone "America/Denver")
        utc (assoc main/feed-recipe :zone "UTC")]
    (testing "an evening in Denver is already tomorrow in UTC — the bug"
      ;; 20:30 MDT on the 5th is 02:30Z on the 6th
      (let [eng (eng-at "2026-09-06T02:30:00Z")]
        (is (= "2026-09-06" (feed/today eng utc)))
        (is (= "2026-09-05" (feed/today eng denver))
            "under the house's zone it is still the 5th at dinner")))
    (testing "the day rolls at midnight Mountain, and not a minute before"
      ;; 23:59 MDT is 05:59Z; 00:00 MDT is 06:00Z
      (is (= "2026-09-05" (feed/today (eng-at "2026-09-06T05:59:59Z") denver)))
      (is (= "2026-09-06" (feed/today (eng-at "2026-09-06T06:00:00Z") denver))))
    (testing "and the seed rolls with it — same member, two sides of the
              local midnight, two feeds"
      (let [a (feed/seed-of denver "colton" (feed/today (eng-at "2026-09-06T05:59:59Z") denver))
            b (feed/seed-of denver "colton" (feed/today (eng-at "2026-09-06T06:00:00Z") denver))]
        (is (not= a b))))))
