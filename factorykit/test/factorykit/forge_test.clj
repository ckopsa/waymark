(ns factorykit.forge-test
  "The build-state table and the conformance cases (ticket 6b716e98):
  the kit's own judge passes every case, and each JSON copy says what
  its EDN file says."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [factorykit.forge :as kit]
            [jsonista.core :as json]))

(defn- json-of [n]
  (json/read-value (slurp (io/resource n)) json/keyword-keys-object-mapper))

(defn- edn-of [n]
  (edn/read-string (slurp (io/resource n))))

(deftest the-json-copies-say-what-the-edn-files-say
  (is (= (edn-of "build-states.edn") (json-of "build-states.json")))
  (is (= (edn-of "forge-conformance.edn") (json-of "forge-conformance.json"))))

(deftest the-kit-passes-its-own-cases
  (is (seq (:cases kit/conformance)))
  (is (= [] (kit/conformance-failures))))

(deftest a-wrong-judge-fails-and-is-named
  (is (= {:case "a skipped required check reads green"
          :expect {:verdict "green"}
          :actual {:verdict "red" :names ["gate"]}}
         (first (kit/conformance-failures
                 (fn [c]
                   (if (= "a skipped required check reads green" (:name c))
                     {:verdict :red :names ["gate"]}
                     (kit/judge c))))))))

(deftest every-raw-state-has-one-verdict-the-table-names
  (let [verdicts (set (map keyword (:verdicts kit/build-states)))]
    (doseq [[forge by-verdict] (:forges kit/build-states)]
      (is (every? verdicts (keys by-verdict)) (str forge))
      (is (apply distinct? (mapcat val by-verdict))
          (str forge " maps one raw state two ways")))))

(deftest a-state-the-table-does-not-name-is-never-green
  (is (nil? (kit/build-state :github {:status "completed" :conclusion "startup_failure"})))
  (is (nil? (kit/build-state :bitbucket {:state "PAUSED"})))
  (is (nil? (kit/verdict {:forge :bitbucket :required ["build"]}
                         [{:check_name "build" :state "PAUSED"}]))))
