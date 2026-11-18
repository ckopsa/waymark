(ns factory10.org-plan-next-step-test
  "An org plan's next_step is computed from its findings (ticket
  ebb60ce4): each door that changes the findings moves it, and no door
  writes it. The birth's onboarding_note leads until findings land.

  No database: the handlers and the computed field are plain fns.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.resources.org-plan :as org-plan])
  (:import (java.time Instant)))

(def ^:private ctx {:now (Instant/parse "2026-10-26T08:00:00Z")})

(defn- step [row]
  (:key ((get-in org-plan/org-plan [:computed :next_step :fn]) row {})))

(def ^:private crack
  {:key "crack/quote/person_only" :severity "error" :class "crack"
   :subject "quote" :sentence "Only a person approves a quote."})

(def ^:private born
  {:data {:name "greenleaf"
          :onboarding_note {:key "onboarding_seat" :severity "info"
                            :class "onboarding" :subject "onboarding-greenleaf"
                            :sentence "Offer onboarding-greenleaf a key you choose (seat offer_key, its `key` input) and sit with it to start."}}})

(defn- recorded [row findings]
  (org-plan/record-the-findings row {:head_sha "aaaaaaa1" :findings findings} ctx))

(deftest each-door-that-changes-the-findings-moves-the-step
  (testing "the birth: its onboarding note is the step"
    (is (= "onboarding_seat" (step born))))
  (testing "record_findings: the first error finding, and the note gives way"
    (is (= (:key crack) (step (recorded born [crack]))))
    (is (nil? (step (recorded born [])))))
  (testing "head_moved: the moved head is the step"
    (is (= "head_moved"
           (step (org-plan/the-head-moved (recorded born []) {:head_sha "bbbbbbb1"} ctx)))))
  (testing "recheck: its warning joins the findings and moves no step"
    (let [rechecked (org-plan/recheck-the-head (recorded born []) {:head_sha "bbbbbbb1"} ctx)]
      (is (some #(= "head_left" (:key %)) (get-in rechecked [:data :findings])))
      (is (nil? (step rechecked)))))
  (testing "no door writes next_step"
    (doseq [row [(recorded born [crack])
                 (org-plan/the-head-moved (recorded born []) {:head_sha "bbbbbbb1"} ctx)
                 (org-plan/recheck-the-head (recorded born []) {:head_sha "bbbbbbb1"} ctx)]]
      (is (not (contains? (:data row) :next_step))))))
