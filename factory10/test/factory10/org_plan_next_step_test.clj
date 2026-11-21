(ns factory10.org-plan-next-step-test
  "An org plan's next_step is computed from its findings (ticket
  ebb60ce4): each door that changes the findings moves it, and no door
  writes it. The birth's onboarding_note leads until findings land.

  No database: the handlers and the computed field are plain fns.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.bench :as bench]
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

(defn- waiting [row]
  ((get-in org-plan/org-plan [:computed :next_step_waiting :fn]) row {}))

(deftest an-asked-step-says-it-waits-on-the-owner
  (let [found (recorded born [crack])
        asked (org-plan/ask-the-owner found {:key "q1" :finding_key (:key crack)
                                             :question "Who approves a quote?"} ctx)]
    (testing "no question on the step: nothing"
      (is (nil? (waiting found))))
    (testing "asked and unanswered: the step waits on the owner's answer"
      (is (= (:key crack) (step asked)))
      (is (= "waiting on the owner's answer to: Who approves a quote?" (waiting asked))))
    (testing "answered: the seat codes it"
      (is (= "answered: code it and submit"
             (waiting (org-plan/answer-the-question asked {:key "q1" :answer "Dana."} ctx)))))))

(deftest head-left-shows-the-change
  (let [row (assoc-in (recorded born []) [:data :findings_sha] "5eed000aaa")
        moved (org-plan/recheck-the-head row {:head_sha "5eed000bbb"} ctx)
        left (some #(when (= "head_left" (:key %)) %) (get-in moved [:data :findings]))]
    (is (str/includes? (:sentence left) "5eed000b from 5eed000a"))
    (is (str/starts-with? (:remedy left) "Nothing to do")))
  (is (= ["abcdef1" "1234567"] (org-plan/distinct-shas "abcdef1999" "1234567999"))))

(deftest a-plan-born-change-gets-the-path-and-room
  (let [policy {:data {:rounds_per_change 3}}]
    (is (= 3 (bench/rounds-of policy {:data {:born_from "ticket:t1"}})))
    (is (= bench/plan-rounds (bench/rounds-of policy {:data {:born_from "org_plan:p1"}})))
    (is (<= 6 bench/plan-rounds))
    (is (= 10 (bench/rounds-of {:data {:rounds_per_change 10}} {:data {:born_from "org_plan:p1"}})))))

(deftest the-stored-onboarding-note-is-not-on-the-page
  ;; ticket 0e453388: next_step is the page's one next step; the stored
  ;; note is only its input, so a seated onboarder's stale offer_key
  ;; sentence is not rendered beside it
  (let [props (some #(when (and (vector? %) (= :onboarding_note (first %))) (second %))
                    (:schema org-plan/org-plan))]
    (is (map? props))
    (is (true? (get-in props [:x-display :hidden])))))
