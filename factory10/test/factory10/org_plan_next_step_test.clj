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

(def ^:private red-film
  {:key "film/invoice/late-payer-chased" :severity "error" :class "film"
   :subject "invoice" :sentence "In the film no one chased the late invoice."})

(def ^:private born
  {:data {:name "greenleaf"
          :onboarding_note {:key "onboarding_seat" :severity "info"
                            :class "onboarding" :subject "onboarding-greenleaf"
                            :sentence "Offer onboarding-greenleaf a key (seat offer_key) and sit with it to start."}}})

(defn- recorded [row findings]
  (org-plan/record-the-findings row {:head_sha "aaaaaaa1" :findings findings} ctx))

(deftest each-door-that-changes-the-findings-moves-the-step
  (testing "the birth: its onboarding note is the step"
    (is (= "onboarding_seat" (step born))))
  (testing "record_findings: the first error finding, and the note gives way"
    (is (= (:key crack) (step (recorded born [crack]))))
    (is (nil? (step (recorded born [])))))
  (let [filmed (org-plan/record-the-film-findings
                (recorded born []) {:head_sha "aaaaaaa1" :findings [red-film]} ctx)]
    (testing "record_film_findings: the film's red rule"
      (is (= (:key red-film) (step filmed))))
    (testing "void_take: the film's findings go, and a warning moves no step"
      (is (nil? (step (org-plan/void-the-take filmed {:head_sha "bbbbbbb1"} ctx))))))
  (testing "head_moved: the moved head is the step"
    (is (= "head_moved"
           (step (org-plan/the-head-moved (recorded born []) {:head_sha "bbbbbbb1"} ctx)))))
  (testing "refilm: its warning joins the findings and moves no step"
    (let [refilmed (org-plan/refilm-the-head (recorded born []) {:head_sha "bbbbbbb1"} ctx)]
      (is (some #(= "take_left" (:key %)) (get-in refilmed [:data :findings])))
      (is (nil? (step refilmed)))))
  (testing "no door writes next_step"
    (doseq [row [(recorded born [crack])
                 (org-plan/the-head-moved (recorded born []) {:head_sha "bbbbbbb1"} ctx)
                 (org-plan/refilm-the-head (recorded born []) {:head_sha "bbbbbbb1"} ctx)]]
      (is (not (contains? (:data row) :next_step))))))
