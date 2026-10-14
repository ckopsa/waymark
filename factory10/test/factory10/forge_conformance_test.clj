(ns factory10.forge-conformance-test
  "factory10's forge reads build states through `factorykit.forge` and
  its one table (ticket 6b716e98), and passes the kit's conformance
  cases: the GitHub verdicts by `forge/check-verdict`, which the pass
  itself calls, and the rest by the kit's own judge."
  (:require [clojure.test :refer [deftest is]]
            [factory10.sources.forge :as forge]
            [factorykit.forge :as kit]))

(deftest factory10-passes-the-forge-conformance-cases
  (is (= []
         (kit/conformance-failures
          (fn [c]
            (if (and (= "verdict" (:asks c))
                     (= "github" (:forge c))
                     (empty? (:advisory c)))
              (forge/check-verdict (:required c) (:checks c))
              (kit/judge c)))))))

(deftest the-conclusion-sets-are-the-table's
  (is (= (kit/raw-states :github :red) forge/red-conclusions))
  (is (= (kit/raw-states :github :green) forge/green-conclusions)))

(deftest the-protocol-is-the-kit's
  (is (identical? kit/ForgeSource forge/ForgeSource))
  (is (identical? kit/forge-checks forge/forge-checks)))
