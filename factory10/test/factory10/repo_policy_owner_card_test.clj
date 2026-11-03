(ns factory10.repo-policy-owner-card-test
  "A repo policy's owner card (docs/spec-first-run.md R-8.2): an
  enrolled policy whose required check the forge does not enforce yet
  waits on the owner, and the card clears once the base pass reads it
  as required. Pure — no engine, no database.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.resources.repo-policy :as rp]))

(def ^:private policy
  {:state :active
   :data {:repository "ckopsa/tgram"
          :required_checks ["tests"]
          :enrolled_at "2026-11-03T06:00:00Z"}})

(defn- card [on-forge]
  (rp/owner-card (assoc-in policy [:data :required_on_forge] on-forge) {}))

(deftest the-card-waits-until-tests-is-required
  (testing "the forge does not require tests: the card says so"
    (is (= (str "After you merge the repo-kit pull request, make `tests` a required "
                "check on your default branch: it is what the merge waits on.")
           (card []))))
  (testing "the protection was not read: no card, no false alarm"
    (is (nil? (rp/owner-card policy {}))))
  (testing "the base pass reads tests as enforced: the card clears"
    (is (nil? (card ["tests"])))))

(deftest an-unenrolled-policy-waits-on-the-owner-until-mark-enrolled
  ;; ticket d4c94030 (R-6.1): the card is the groom guard's sentence
  (let [unenrolled (-> policy
                       (update :data dissoc :enrolled_at)
                       (assoc-in [:data :required_on_forge] []))]
    (is (= (str "The bench does not hold this repository yet. It takes it once "
                "the GitHub token is entered, and a seat builds here from then on.")
           (rp/owner-card unenrolled {})))
    (is (= rp/unenrolled-card (rp/owner-card unenrolled {})))
    (testing "mark_enrolled stamps enrolled_at: the enrolment card clears"
      (is (nil? (rp/owner-card (-> unenrolled
                                   (assoc-in [:data :enrolled_at] "2026-11-03T07:00:00Z")
                                   (assoc-in [:data :required_on_forge] ["tests"]))
                               {}))))))

(deftest the-card-is-only-on-an-active-policy
  (is (nil? (rp/owner-card (-> policy
                               (update :data dissoc :enrolled_at)
                               (assoc :state :retired))
                           {})))
  (is (nil? (rp/owner-card (-> policy
                               (assoc :state :retired)
                               (assoc-in [:data :required_on_forge] []))
                           {}))))
