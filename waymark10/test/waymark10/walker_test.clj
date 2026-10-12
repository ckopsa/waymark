(ns waymark10.walker-test
  "Phase-4a: random machine walks over the fixture kinds. Every step
  asserts the invariants inside random-walk itself — these tests
  stage the walks, check their shape, and pin determinism. Needs the
  waymark10_test database; WAYMARK10_TEST_DSN overrides the DSN."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [waymark10.fixtures :as fx]
            [waymark10.machine :as machine]
            [waymark10.resource :as r]
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

(def ^:private table
  "A kind with NO registered example whose create door and whose one
  action both need a meal: at the top level, inside a nested map and
  inside each item of a list. The walker has to name rows that stand."
  (r/resource
   {:kind :wk_table :plural "wk_tables"
    :states [:open :seated] :initial :open :terminal #{:seated}
    :summary "{data.title}"
    :schema [:map
             [:title [:string {:min 1 :max 40}]]
             [:meal_id {:kind :meal} :waymark/ref]
             [:host [:map [:meal_id {:kind :meal} :waymark/ref]]]
             [:courses [:vector [:map [:meal_id {:kind :meal} :waymark/ref]]]]]
    :actions {:seat {:from #{:open} :to :seated
                     :input [:map
                             [:guest [:map [:meal_id {:kind :meal} :waymark/ref]]]]
                     :safety {:idempotent true :reversible false
                              :confirm false :one-way "Seated."}}}}))

(def ^:dynamic *eng* nil)

(use-fixtures :once
  (fn [f]
    (db/with-test-engine [fx/meal fx/plan table]
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

(deftest required-refs-name-rows-that-stand
  (let [meal? (fn [id] (some? ((:read (fac/probe-ctx *eng*)) :meal id)))]
    (testing "a create body: top level, nested map, each item of a list"
      (doseq [seed [21 22 23 24 25]]
        (testing (str "seed " seed)
          (let [body (fac/create-body *eng* :wk_table {:seed seed})]
            (is (meal? (:meal_id body)))
            (is (meal? (get-in body [:host :meal_id])))
            (is (every? meal? (map :meal_id (:courses body))))))))
    (testing "an action input: the nested ref is a row, not a skip"
      (let [rdef (get-in *eng* [:resources :wk_table])
            seat (first (machine/path-to rdef :seated))
            row (:row (fac/create-example *eng* :wk_table {:seed 31}))
            input (fac/synthesize-input *eng* rdef seat row
                                        (fac/probe-ctx *eng*) {:seed 31})]
        (is (meal? (get-in input [:guest :meal_id])))))
    (testing "so the walk reaches the state with no example registered"
      (doseq [seed [41 42 43]]
        (testing (str "seed " seed)
          (let [row (fac/walk-to-state *eng* :wk_table :seated {:seed seed})]
            (is (nil? (:skip row)) (str (get-in row [:skip :reason])))
            (is (= :seated (:state row)))))))))

(deftest walks-are-deterministic
  (let [trace #(mapv (juxt :action :from :to)
                     (:steps (walker/random-walk *eng* :plan
                                                 {:seed 99 :steps 8})))]
    (is (= (trace) (trace))
        "the same seed walks the same actions in the same order")))
