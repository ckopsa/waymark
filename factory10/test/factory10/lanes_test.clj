(ns factory10.lanes-test
  "A change reads its lane from its touched paths and its repository's
  policy (ticket 9b27142e): `bench/lane-of`. Pure — no engine, no
  database.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
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
