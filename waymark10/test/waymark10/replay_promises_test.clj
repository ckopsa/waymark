(ns waymark10.replay-promises-test
  "Promise replay (docs/spec-amendment.md §14 step 5): a looser `by`
  logs fewer breaks, and a probe promise answers `probes` with no count."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.replay-promises :as replay-promises])
  (:import (java.time Instant)))

(def ^:private day 86400)

(def ^:private t0 (Instant/parse "2026-10-18T00:00:00Z"))
(def ^:private t1 (Instant/parse "2026-10-20T00:00:00Z"))
(def ^:private t2 (Instant/parse "2026-10-23T00:00:00Z"))
(def ^:private t3 (Instant/parse "2026-11-01T00:00:00Z"))

(def ^:private history
  {:rows {["job" "j1"] {:kind "job" :id "j1" :opening "draft"
                        :timeline [{:at t1 :action "groom" :from "draft" :to "open"
                                    :data {:repo "r1"}}
                                   {:at t2 :action "finish" :from "open" :to "done"
                                    :data {:repo "r1"}}]}}
   :blind []})

(defn- promise [by]
  {:about {:kind "job" :filter {:repo "r1"}}
   :holds {:query {:kind "job" :filter {:id "{id}" :state "open"} :expect "none"}}
   :by by})

(def ^:private probe
  (assoc (promise {:window 900})
         :holds {:probe {:power "site.get" :expect {:path "status" :eq 200} :every 900}}))

(defn- replay [before after]
  (replay-promises/replay {:history history :before before :after after
                           :window {:from t0 :until t3}}))

(deftest a-looser-by-logs-fewer-breaks
  (let [r (replay (promise {:after "groom" :within day})
                  (promise {:after "groom" :within (* 10 day)}))]
    (is (= "job" (:kind r)))
    (is (= 1 (:breaks (:before r))))
    (is (= 0 (:breaks (:after r))))
    (is (= [] (:blind r)))
    (testing "the break is logged a day after the groom, and kept a sweep after the row leaves open"
      (is (= [{:event "break" :row "j1" :at (.plusSeconds t1 day)
               :cause "job expected no row and read 1"}
              {:event "keep" :row "j1" :at (.plusSeconds t2 900)}]
             (:log (:before r))))
      (is (= 1 (:keeps (:before r)))))
    (testing "the same promise on both sides logs the same"
      (let [same (replay (promise {:after "groom" :within day})
                         (promise {:after "groom" :within day}))]
        (is (= (:log (:before same)) (:log (:after same))))))))

(deftest a-probe-promise-answers-probes-and-no-count
  (let [r (replay (promise {:after "groom" :within day}) probe)]
    (is (not (contains? (:after r) :breaks)))
    (is (= 1 (:breaks (:before r))))
    (is (= ["probes"] (mapv :token (:blind r))))
    (testing "a probe runs on the sweep alone: 14 days of 15 minute ticks"
      (is (= (* 14 96) (:moments (first (:blind r)))))))
  (testing "a judgment check is named blind the same way"
    (let [judged (assoc probe :holds {:judgment {:id "jd" :expect ["yes"]}})
          r (replay judged judged)]
      (is (= ["judgment_checks"] (mapv :token (:blind r))))
      (is (not (contains? (:before r) :breaks))))))
