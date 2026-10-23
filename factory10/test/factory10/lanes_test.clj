(ns factory10.lanes-test
  "A change reads its lane from its touched paths and its repository's
  policy (ticket 9b27142e): `bench/lane-of`. Pure — no engine, no
  database.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.bench :as bench]))

(def ^:private policy
  {:data {:repository "ckopsa/waymark"
          :lanes [{:name "docs" :paths ["docs/**"]}
                  {:name "orgs" :paths ["orgs/**"]}]}})

(defn- touching [& paths] {:data {:touched_paths (vec paths)}})

(deftest a-change-reads-its-lane-from-its-paths
  (testing "a docs-only change is docs"
    (is (= "docs" (bench/lane-of (touching "docs/a.md" "docs/b/c.md") policy))))
  (testing "orgs/a/** is orgs/a"
    (is (= "orgs/a" (bench/lane-of (touching "orgs/a/seed.edn" "orgs/a/x/y.md")
                                   policy))))
  (testing "two org directories span lanes, and are engine"
    (is (= "engine" (bench/lane-of (touching "orgs/a/seed.edn" "orgs/b/seed.edn")
                                   policy))))
  (testing "docs plus engine paths is engine"
    (is (= "engine" (bench/lane-of (touching "docs/a.md" "factory10/src/x.clj")
                                   policy))))
  (testing "no lanes declared means engine"
    (is (= "engine" (bench/lane-of (touching "docs/a.md")
                                   {:data {:repository "ckopsa/waymark"}})))
    (is (= "engine" (bench/lane-of (touching "docs/a.md") nil))))
  (testing "a change that touches nothing is engine"
    (is (= "engine" (bench/lane-of (touching) policy)))))

(defn- change [id & {:as data}]
  {:id id :data (merge {:repository "ckopsa/waymark" :base_branch "main"} data)})

(deftest a-change-in-an-open-plans-lane-is-held
  ;; ticket 59cd3e78
  (let [plan (change "plan" :born_from "org_plan:p1" :number 1595
                     :touched_paths ["orgs/x/seed.edn"])
        lane #(bench/lane-of % policy)
        into-x (change "a" :touched_paths ["orgs/x/a.clj" "README.md"])
        into-y (change "b" :touched_paths ["orgs/y/a.clj"])
        holds (bench/lane-holds [plan into-x into-y] [plan] lane)]
    (testing "a change touching orgs/x/a.clj is held, naming the plan and its lane"
      (is (str/includes? (str (get holds "a")) "orgs/x"))
      (is (str/includes? (str (get holds "a")) "ckopsa/waymark#1595")))
    (testing "one touching orgs/y is not held"
      (is (not (contains? holds "b"))))
    (testing "the plan's own change is never held"
      (is (not (contains? holds "plan"))))
    (testing "release_lane releases it"
      (is (empty? (bench/lane-holds
                   [(assoc-in into-x [:data :lane_released] "the plan takes it")]
                   [plan] lane))))
    (testing "a change toward another base is not held"
      (is (empty? (bench/lane-holds [(assoc-in into-x [:data :base_branch] "dev")]
                                    [plan] lane))))))
