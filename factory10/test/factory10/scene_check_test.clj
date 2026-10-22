(ns factory10.scene-check-test
  "The plan audit runs the scene check (ticket 50ab921e): a first-week
  scene the scene kind's `check` would refuse is the error finding
  `film/scene-check`; a scene it would pass adds none. Memory storage,
  no network.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.first-week :as first-week]
            [factory10.scene-check :as scene-check]))

(def ^:private seed
  (edn/read-string (slurp (io/resource "example/seed.edn"))))

(defn- example-dir
  "orgs/example, found from its seed at <org-dir>/resources/example/seed.edn."
  []
  (.getPath (.getParentFile (.getParentFile (.getParentFile
                                             (io/file (io/resource "example/seed.edn")))))))

(defn- example-kinds []
  ((requiring-resolve 'example.org/resources)))

(def ^:private filmed-on
  {:clone_url ""
   :head_sha "5e469c6166edf6a3f28ae2e6bdb88b775025f14d"
   :day_one "2026-10-19"})

(def ^:private a-refused-week
  "A week.edn whose one call moves a row no earlier call made, as the
  dress plan's `birch-2025` did."
  {:day-one "2026-10-26"
   :days [{:day 1 :card "Monday. The birch job is accepted."
           :calls [{:as :world :kind "request" :action "accept" :row "birch-2025"}]}]})

(deftest a-clean-org-gets-no-scene-check-finding
  (testing "the example org's scene, as the audit renders it, checks"
    (is (= [] (scene-check/org-findings
               (example-dir)
               (first-week/org-scene (example-dir) (:head_sha filmed-on) (:day_one filmed-on))))))
  (testing "no scene rendered is no finding"
    (is (= [] (scene-check/org-findings (example-dir) "")))))

(deftest a-scene-the-check-refuses-is-an-error-finding
  (let [doc (first-week/scene seed (assoc filmed-on :resources (example-kinds)
                                          :week_script a-refused-week))
        found (scene-check/findings doc (example-kinds))
        f (first found)]
    (testing "one finding, in the audit's shape"
      (is (= 1 (count found)) (pr-str found))
      (is (= {:key "film/scene-check" :severity "error" :class "film"}
             (select-keys f [:key :severity :class]))))
    (testing "its sentence is the refusal's"
      (is (str/includes? (str (:sentence f)) "`birch-2025`, which no earlier call shot made")
          (:sentence f)))
    (testing "its remedy"
      (is (= "Fix the org's week or the render so the scene check passes." (:remedy f))))))
