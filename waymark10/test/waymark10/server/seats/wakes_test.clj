(ns waymark10.server.seats.wakes-test
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.seats :as seats]
            [waymark10.server.seats.wakes :as wakes]))

(def ^:private walker {:id "s-1" :data {:walk "ticket"}})

(def ^:private filtered
  {:id "s-1" :data {:walk "ticket"
                    :scope [{:kind "ticket" :filter {:state "open"}}]}})

(deftest a-walk-seat-wakes-on-a-row-born-in-its-queue
  (is (= [{:kind "ticket" :actions ["create"]}] (wakes/effective-wake-on walker)))
  (testing "a seat that walks nothing wakes on its cadence alone"
    (is (= [] (wakes/effective-wake-on {:data {}})))))

(deftest a-filtered-walk-is-managed-as-every-action-under-its-filter
  (is (= {:state "open"} (wakes/walk-filter filtered)))
  (is (= [{:kind "ticket" :actions [] :filter {:state "open"}}]
         (wakes/managed-wakes filtered))))

(deftest an-interactive-seat-is-owed-no-wake-row
  (is (= [] (wakes/missing-wake-subscriptions
             (assoc-in walker [:data :mode] "interactive") []))))

(deftest seats-still-answers-for-every-moved-var
  (is (identical? wakes/walk-filter seats/walk-filter))
  (is (identical? wakes/effective-wake-on seats/effective-wake-on))
  (is (identical? wakes/wake-entries seats/wake-entries))
  (is (identical? wakes/wakes-of seats/wakes-of))
  (is (identical? wakes/door-subscribed? seats/door-subscribed?))
  (is (identical? wakes/missing-wake-subscriptions seats/missing-wake-subscriptions))
  (is (identical? wakes/restate-managed-wakes! seats/restate-managed-wakes!))
  (is (identical? wakes/wake-seat! seats/wake-seat!))
  (is (identical? wakes/wake-seat-through! seats/wake-seat-through!))
  (is (identical? wakes/inbox-subscriptions seats/inbox-subscriptions))
  (is (identical? wakes/missing-inbox-subscriptions seats/missing-inbox-subscriptions))
  (is (identical? wakes/restate-managed-inbox! seats/restate-managed-inbox!))
  (is (= wakes/walk-create-action seats/walk-create-action)))
