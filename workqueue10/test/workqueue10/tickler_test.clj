(ns workqueue10.tickler-test
  "The tickler's arithmetic and its machine (waymark-iqa.4), judged
  with no database at all.

  The three scenarios in tickler.clj carry the half a scenario can
  carry — a let-go item never returns, a taken-back one stops asking,
  anybody in the house may answer — because scenario.clj is explicit
  that 'no scenario ever writes: a scenario is a verdict, not a
  story'. The BACKOFF is a story: it is what a handler computes, and
  the way to judge a pure function is to call it.

  So this file owns the arithmetic and the shape of the projected
  machine, and `:feed/ticklers` in waymark10.test.packs owns the same
  law over the wire, against a real row on a real feed. Between them
  the epic's two sentences are covered from both sides.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.tickler-test"
  (:require [clojure.test :refer [deftest is testing]]
            [workqueue10.resources.tickler :as tickler :refer [tickler]])
  (:import (java.time Instant)))

(def ^:private now (Instant/parse "2026-08-24T09:00:00Z"))

(def ^:private a-marker
  {:id "t1" :state :offered
   :data {:what "Sand and repaint the porch railing"
          :subject_kind "task" :subject_id "01HZQ7"
          :set_aside_by "colton"}})

(defn- say-not-now
  "One 'not now', at `at`, on the row a previous one left behind."
  [row at]
  (tickler/push-the-offer-out row {} {:now at :principal {:id "colton"}}))

;; ── the schedule ────────────────────────────────────────────────────

(deftest the-backoff-is-a-pure-function-of-how-often-you-said-no
  (testing "the declared schedule, step by step"
    (is (= [7 21 60 180] tickler/backoff-days)
        "a week, three weeks, two months, half a year — the household's
         policy, spelled as data so a test can read it")
    (is (= [7 21 60 180] (mapv tickler/days-out [1 2 3 4]))))
  (testing "the last step repeats rather than running out"
    (is (= [180 180 180] (mapv tickler/days-out [5 9 400]))
        "half a year forever: the only honest way to never see
         something again is to let it go, which is a verdict"))
  (testing "it reads no clock — the same inputs answer the same instant"
    (is (= (tickler/next-offer now 1) (tickler/next-offer now 1)))
    (is (= (Instant/parse "2026-08-31T09:00:00Z")
           (tickler/next-offer now 1)))))

(deftest a-not-now-returns-later-not-tomorrow
  (testing "the epic's own sentence, at every step of the schedule"
    (let [tomorrow (.plusSeconds now 86400)]
      (doseq [said (range 1 8)]
        (is (pos? (compare (tickler/next-offer now said) tomorrow))
            (str "not-now number " said " came back within a day — a"
                 " tickler that returns in the morning is a nag, and a"
                 " household learns to dismiss a nag unread"))))))

;; ── the handler ─────────────────────────────────────────────────────

(deftest each-not-now-pushes-the-next-offer-further-out
  (let [once (say-not-now a-marker now)
        twice (say-not-now once (.plusSeconds now 60))]
    (testing "the household keeps the count, because it is a household fact"
      (is (= 1 (get-in once [:data :offer_count])))
      (is (= 2 (get-in twice [:data :offer_count]))
          "'I have said not-now to this twice' is something the house
           reads, not something one person half-remembers"))
    (testing "and the date moves out by the step that count earned"
      (is (= (Instant/parse "2026-08-31T09:00:00Z")
             (get-in once [:data :next_offer_at])))
      (is (= (.plusSeconds (.plusSeconds now 60) (* 86400 21))
             (get-in twice [:data :next_offer_at]))
          "the second not-now is measured from when it was SAID, not
           from when the first one was"))
    (testing "and it records who said it"
      (is (= "colton" (get-in once [:data :answered_by]))))
    (testing "and it touches nothing else — no state, no subject"
      (is (= :offered (:state once))
          "the verdict RETURNS to the open state; the machine moves it,
           never the handler")
      (is (= (select-keys (:data a-marker)
                          [:what :subject_kind :subject_id :set_aside_by])
             (select-keys (:data twice)
                          [:what :subject_kind :subject_id :set_aside_by]))
          "a tickler never writes its subject — that is fork (b)'s
           first reason, and it is why this is a marker kind"))))

;; ── the machine the sugar projected ─────────────────────────────────

(deftest the-decision-sugar-projects-the-tickler-whole
  (testing "three states, and the two that end it are terminal"
    (is (= #{:offered :let_go :taken} (set (:states tickler))))
    (is (= :offered (:initial tickler)))
    (is (= #{:let_go :taken} (:terminal tickler))
        "let go and taken are ends; not-now is not"))
  (testing "the non-terminal verdict — the first one anywhere"
    (let [a (get-in tickler [:actions :not_now])]
      (is (= #{:offered} (:from a)))
      (is (= :offered (:to a))
          "a verdict that RETURNS to the open state: desugar-decision
           permits it ('at least one verdict must leave', not all) and
           nothing had exercised it before this kind")
      (is (false? (get-in a [:safety :idempotent]))
          "two taps are two not-nows, so the door asks for a key
           rather than compounding a double-tap")))
  (testing "no wall, and no guard standing in for one"
    (doseq [aname [:not_now :let_it_go :take_it_back]]
      (is (empty? (:guards (get-in tickler [:actions aname])))
          (str (name aname) " grew a guard — :decider :anyone means the"
               " household answers its own list"))))
  (testing "every verdict is one tap: no input, therefore assent"
    (doseq [aname [:not_now :let_it_go :take_it_back]]
      (is (nil? (:input (get-in tickler [:actions aname])))
          (str (name aname) " demands input — a card may only offer"
               " ≤ selection, and a verdict with a note field renders"
               " as recall and falls off the card into `heavier`"))))
  (testing "the birth door offers what a person can answer and nothing else"
    (let [fields (into #{} (map first) (rest (:create-schema tickler)))]
      (is (contains? fields :what))
      (is (contains? fields :subject_kind))
      (is (contains? fields :subject_id))
      (is (not (contains? fields :offer_count))
          "the count is the verdict's to write, never the form's")
      (is (not (contains? fields :set_aside_by))
          "who set it aside is stamped from the principal — a marker
           that could name somebody else is a marker that can blame
           them")
      (is (not (contains? fields :answered_by))))))
