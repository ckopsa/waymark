(ns factory10.repo-policy-summary-test
  "A repo policy's summary line says where its merge line stands (ticket
  b5a9b790): the front pull request, what it waits on, how many stand
  behind it, and the base or the deploy. Pure — no engine, no database.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.resources.repo-policy :as rp]
            [waymark10.summary :as summary])
  (:import (java.time Instant)))

(def ^:private now (Instant/parse "2026-10-03T12:12:00Z"))

(defn- line [data] (rp/summary-line data :active now))

(deftest the-line-says-the-front-the-count-and-the-state
  (testing "a front that waits on the merge, with a green deploy"
    (is (= "ckopsa/home-infrastructure · #74 front, waiting on merge · 3 behind · deploy green"
           (line {:repository "ckopsa/home-infrastructure"
                  :line_front_pr 74 :line_front_waiting "merge"
                  :line_waiting 3 :base_state "green"
                  :deploy_state "green" :auto_merge true}))))
  (testing "a front that waits on its checks, and no deploy check"
    (is (= "ckopsa/waymark · #794 front, waiting on checks · 0 behind · base green"
           (line {:repository "ckopsa/waymark"
                  :line_front_pr 794 :line_front_waiting "checks"
                  :line_waiting 0 :base_state "green"}))))
  (testing "no change stands in the line"
    (is (= "ckopsa/tgram · line empty · base green"
           (line {:repository "ckopsa/tgram" :base_state "green"})))))

(deftest the-line-says-a-manual-merge
  (is (= "ckopsa/tgram · line empty · base green · manual merge"
         (line {:repository "ckopsa/tgram" :base_state "green"
                :auto_merge false}))))

(deftest what-needs-a-person-leads
  (testing "a red base leads, with when the pass wrote it"
    (is (= "ckopsa/waymark · base red since 10-03 09:30Z · #794 front, waiting on checks · 2 behind"
           (line {:repository "ckopsa/waymark" :base_state "red"
                  :base_checked_at "2026-10-03T09:30:00Z"
                  :line_front_pr 794 :line_front_waiting "checks"
                  :line_waiting 2}))))
  (testing "a deploy the line waits on says for how long"
    (is (= "ckopsa/waymark · deploy waiting 12m · line empty"
           (line {:repository "ckopsa/waymark" :base_state "green"
                  :deploy_state "green"
                  :deploy_waits_on ["a b 250"]
                  :deploy_waiting_since (Instant/parse "2026-10-03T12:00:00Z")}))))
  (testing "a red deploy holds the line"
    (is (= "ckopsa/waymark · deploy red · line empty"
           (line {:repository "ckopsa/waymark" :base_state "green"
                  :deploy_state "red"})))))

(deftest a-part-that-does-not-fit-is-left-out-whole
  (let [s (line {:repository "ckopsa/home-infrastructure" :base_state "red"
                 :base_checked_at "2026-10-03T09:30:00Z"
                 :deploy_waiting_since "2026-10-03T12:00:00Z"
                 :line_front_pr 74 :line_front_waiting "merge"
                 :line_waiting 3 :auto_merge false})]
    (is (<= (count s) 100))
    (is (= "ckopsa/home-infrastructure · base red since 10-03 09:30Z · deploy waiting 12m"
           s))))

(deftest the-kind-serves-the-composed-line
  (testing "the declaration's composer answers the row's line"
    (is (= "ckopsa/tgram · line empty · base green"
           (summary/line rp/repo-policy
                         {:kind :repo_policy :state :active
                          :data {:repository "ckopsa/tgram"
                                 :base_state "green"}}))))
  (testing "a retired policy says so"
    (is (= "ckopsa/tgram · retired · line empty"
           (rp/summary-line {:repository "ckopsa/tgram"} :retired now))))
  (testing "a composer that throws leaves the template"
    (is (= "ckopsa/tgram · Active"
           (summary/line {:summary "{data.repository} · {state}"
                          :summary-fn (fn [_] (throw (ex-info "no" {})))}
                         {:state :active
                          :data {:repository "ckopsa/tgram"}})))))
