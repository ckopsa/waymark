(ns workqueue10.sources-test
  "The boundary's pure half: the envelope→canonical translations, the
  shared push-plan table, namespaced identity, and the confluence's
  routing — no database, no HTTP, the fakes where an adapter is
  needed."
  (:require [clojure.test :refer [deftest is testing]]
            [workqueue10.confluence :as conf]
            [workqueue10.sources.choreplan :as chores]
            [workqueue10.sources.mealplan :as meals]
            [waymark10.server.mirror :as mirror]))

;; ── the translations ────────────────────────────────────────────────

(deftest chore-run-translation
  (testing "state → status, per chore_run's three states"
    (is (= "open" (:status (chores/run->task {:state "due" :data {}}))))
    (is (= "done" (:status (chores/run->task {:state "done" :data {}}))))
    (is (= "dropped" (:status (chores/run->task {:state "skipped" :data {}})))))

  (testing "a fourth state fails loudly, never maps silently"
    (is (thrown? IllegalArgumentException
                 (chores/run->task {:state "paused" :data {}}))))

  (testing "due_date widens to the day's CLOSING midnight — overdue
            flips exactly when choreplan's own date-typed law flips"
    (is (= "2026-07-22T00:00:00Z"
           (:due_at (chores/run->task
                     {:state "due" :data {:due_date "2026-07-21"}})))))

  (testing "the run reads standalone: label copy and carried notes"
    (let [t (chores/run->task
             {:state "due"
              :data {:chore_name "Dishes" :assignee "colton"
                     :due_date "2026-07-21"
                     :chore_notes "load and run before bed"}})]
      (is (= "Dishes" (:title t)))
      (is (= "colton" (:assignee t)))
      (is (= "load and run before bed" (:detail t))))))

(deftest prep-task-translation
  (testing "state → status, per prep_task's four states"
    (is (= "open" (:status (meals/prep->task {:state "pending" :data {}}))))
    (is (= "open" (:status (meals/prep->task {:state "scheduled" :data {}}))))
    (is (= "done" (:status (meals/prep->task {:state "done" :data {}}))))
    (is (= "dropped" (:status (meals/prep->task {:state "cancelled" :data {}})))))

  (testing "title folds task_type + meal_name; due_at rides through"
    (let [t (meals/prep->task
             {:state "pending"
              :data {:task_type "thaw" :meal_name "Traeger brisket"
                     :assignee "housekeeper"
                     :due_at "2026-07-21T16:00:00Z"
                     :notes "move 2500g brisket to the fridge"}})]
      (is (= "thaw: Traeger brisket" (:title t)))
      (is (= "2026-07-21T16:00:00Z" (:due_at t)))
      (is (= "move 2500g brisket to the fridge" (:detail t))))))

;; ── the shared push-plan ────────────────────────────────────────────

(deftest push-plan-table
  (testing "a local-only write (prioritize) has nothing to say"
    (is (= :noop (conf/push-plan {:status "open" :priority 1} "open")))
    (is (= :noop (conf/push-plan {:status "open" :priority 1} "done"))))

  (testing "done travels once; an agreeing authority is idempotence"
    (is (= :complete (conf/push-plan {:status "done"} "open")))
    (is (= :noop (conf/push-plan {:status "done"} "done"))))

  (testing "done against a dropped task is the conflicted state"
    (is (thrown-with-msg? Exception #"complete does not apply"
                          (conf/push-plan {:status "done"} "dropped")))))

;; ── namespaced identity ─────────────────────────────────────────────

(deftest xid-round-trip
  (is (= "chore:cr-1" (conf/xid "chore" "cr-1")))
  (is (= ["chore" "cr-1"] (conf/split-xid "chore:cr-1")))
  (testing "a source-local id containing colons survives the round trip"
    (is (= ["meal" "a:b:c"] (conf/split-xid (conf/xid "meal" "a:b:c")))))
  (testing "an unprefixed id refuses — every row is born through the
            confluence"
    (is (thrown-with-msg? Exception #"carries no source tag"
                          (conf/split-xid "bare-uuid")))))

;; ── the confluence's routing ────────────────────────────────────────

(deftest confluence-routing
  (let [chore-fake (conf/fake-source)
        meal-fake (conf/fake-source)
        feed (conf/confluence {"chore" chore-fake "meal" meal-fake})]
    (conf/seed! chore-fake "cr-1" {:title "Dishes" :status "open"})
    (conf/seed! meal-fake "pt-1" {:title "thaw: Brisket" :status "open"})

    (testing "discover namespaces every source's ids"
      (is (= #{"chore:cr-1" "meal:pt-1"} (set (mirror/discover feed)))))

    (testing "pull routes by prefix and stamps :source on the way through"
      (let [[doc _etag] (mirror/pull feed "chore:cr-1")]
        (is (= "chore" (:source doc)))
        (is (= "Dishes" (:title doc)))))

    (testing "pull-many fans out per source and re-prefixes the keys"
      (let [pulled (mirror/pull-many feed ["chore:cr-1" "meal:pt-1"])]
        (is (= #{"chore:cr-1" "meal:pt-1"} (set (keys pulled))))
        (is (= "meal" (:source (first (get pulled "meal:pt-1")))))))

    (testing "a down source degrades per-source: discover skips it,
              pull-many drops its ids, the other source flows"
      (conf/down! chore-fake true)
      (is (= ["meal:pt-1"] (vec (mirror/discover feed))))
      (is (= #{"meal:pt-1"}
             (set (keys (mirror/pull-many feed ["chore:cr-1" "meal:pt-1"])))))
      (testing "…while a singular pull throws — per-row unreachable is
                the sync machine's honest rendering"
        (is (thrown? Exception (mirror/pull feed "chore:cr-1"))))
      (conf/down! chore-fake false))

    (testing "push routes done to the tagged authority"
      (mirror/push feed "chore:cr-1" {:status "done" :title "Dishes"})
      (is (= "done" (get-in @(:state chore-fake) [:docs "cr-1" :status])))
      (is (= "open" (get-in @(:state meal-fake) [:docs "pt-1" :status]))
          "the other source never heard about it"))

    (testing "an unregistered tag refuses loudly"
      (is (thrown-with-msg? Exception #"no source registered"
                            (mirror/pull feed "laundry:x-1"))))))
