(ns waymark10.walker-test
  "Phase-4a: random machine walks over the fixture kinds. Every step
  asserts the invariants inside random-walk itself — these tests
  stage the walks, check their shape, and pin determinism. Needs the
  waymark10_test database; WAYMARK10_TEST_DSN overrides the DSN."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [waymark10.fixtures :as fx]
            [waymark10.test.db :as db]
            [waymark10.test.factories :as fac]
            [waymark10.test.walker :as walker]))

;; the same enrollment the conformance namespace makes: a walkable
;; week — past start, every day covered (the registry is shared, so
;; a duplicate registration is harmless)
(fac/example-input! :plan :create
  {:start_date "2025-01-06" :weeks 1
   :days [{:date "2025-01-06" :eating_out true}
          {:date "2025-01-07" :eating_out true}]})

(def ^:dynamic *eng* nil)

(use-fixtures :once
  (fn [f]
    (db/with-test-engine [fx/meal fx/plan]
      (fn [eng] (binding [*eng* eng] (f))))))

(defn- well-formed-step? [s]
  (and (keyword? (:action s)) (keyword? (:from s)) (keyword? (:to s))))

(deftest meal-walks
  (doseq [seed [1 2 3 4 5]]
    (testing (str "seed " seed)
      (let [{:keys [steps row]} (walker/random-walk *eng* :meal
                                                    {:seed seed :steps 8})]
        (is (map? row))
        (is (contains? (set (:states (get-in *eng* [:resources :meal])))
                       (:state row)))
        (is (every? well-formed-step? steps))))))

(deftest plan-walks
  (doseq [seed [11 12 13]]
    (testing (str "seed " seed)
      (let [{:keys [steps row]} (walker/random-walk *eng* :plan
                                                    {:seed seed :steps 10})]
        (is (map? row))
        (is (contains? (set (:states (get-in *eng* [:resources :plan])))
                       (:state row)))
        (is (every? well-formed-step? steps))))))

(deftest walks-are-deterministic
  (let [trace #(mapv (juxt :action :from :to)
                     (:steps (walker/random-walk *eng* :plan
                                                 {:seed 99 :steps 8})))]
    (is (= (trace) (trace))
        "the same seed walks the same actions in the same order")))
