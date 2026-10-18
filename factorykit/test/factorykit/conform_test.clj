(ns factorykit.conform-test
  "Org conformance (ticket 86f96963): the example org passes clean, and
  a fixture org with a state no seat leaves, a guard with no sentence
  and a seat scope naming an unknown kind earns exactly those three
  findings, keyed the same on every run. No database, no network."
  (:require [clojure.test :refer [deftest is testing]]
            [factorykit.conform :as conform]))

(deftest the-example-org-conforms
  (is (= [] (conform/conform "../orgs/example"))))

(deftest a-broken-org-earns-exactly-its-three-findings
  (let [fs (conform/conform "fixtures/orgs/broken")]
    (is (= ["crack/no_exit/gizmo/stuck"
            "sentence/gizmo/start/broken/unsaid"
            "scope/unknown-kind/clerk/gadget"]
           (mapv :key fs)))
    (is (every? #(= :error (:severity %)) fs))
    (is (every? #(and (:class %) (:subject %) (seq (:sentence %)) (seq (:remedy %))) fs))
    (testing "the keys are stable across runs"
      (is (= (mapv :key fs)
             (mapv :key (conform/conform "fixtures/orgs/broken")))))))

(deftest a-seat-above-its-owners-ceiling-earns-one-finding
  (is (= ["scope/outside-owner/clerk/gizmo/finish"]
         (mapv :key (conform/conform "fixtures/orgs/overreach")))))
