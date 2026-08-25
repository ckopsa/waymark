(ns waymark10.feed-view-test
  "What the view door claims about ITSELF, with no engine in the room
  (waymark-8um.1).

  The pack obligation `:feed/view-events` walks the whole law from the
  wire — off is off, the switch is the member's own, the row is
  engine-stamped, a preview hands the previewer nothing to record
  with. What is here instead is the handful of claims a driver with
  one world cannot make: that the two sides of the acted-follows JOIN
  are spelled by one source, that the reads the ranking formula is
  coming for are indexed, and that a record of what somebody was shown
  has no verbs on it at all."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.feed-view :as fv]
            [waymark10.schema :as schema]
            [waymark10.server.feed :as feed]
            [waymark10.server.store :as store]))

(def ^:private card-id "do_now/task/01HZQ7Y7F2R3W4V5X6Y7Z8A9B0")
(def ^:private day "2026-08-26")

(deftest the-two-sides-of-the-join-are-spelled-by-one-source
  ;; This is the whole of the acted-follows DECISION. A view row does
  ;; not carry "did an action follow" and must not: every verb fired
  ;; from a card already rides feed/origin-key, and origin-of reads the
  ;; day and the card back out of the audit trail. So the question is a
  ;; join over two names — and it is only a join if BOTH sides spell
  ;; those two names the same way, which is what this asserts rather
  ;; than assumes.
  (let [read-back (feed/origin-of (feed/origin-key day card-id "9f3c1a"))
        stored (set (keys (schema/entry-map (:schema fv/feed-view))))]
    (is (= day (:day read-back)))
    (is (= card-id (:card-id read-back)))
    (testing "and the row keeps exactly those two names, whole"
      (is (contains? stored :day))
      (is (contains? stored :card_id)))
    (testing "the card id is kept UNSPLIT — the section is the first
              segment of the id and reading it out is a split, not a
              query, so a second column for it would be a second truth"
      (is (not (contains? stored :section)))
      (is (= "do_now" (:section read-back))))
    (testing "…and there is no `at` beside created_at: the engine's
              clock is the engine's column"
      (is (not (contains? stored :at))))))

(deftest the-reads-the-formula-is-coming-for-are-indexed
  ;; waymark-8um.3 aggregates BY CARD over a window of days. A declared
  ;; :unique group is the only index a declaration in this engine can
  ;; ask for beyond state, law and the sortable clocks, so its field
  ;; ORDER is the one indexing decision available and it is spent here.
  (let [{:keys [columns indexes]} (store/kind-projection fv/feed-view)
        promoted (into #{} (comp (filter :generated?) (map :name)) columns)]
    (is (= #{"f_card_id" "f_day" "f_member" "f_population"} promoted)
        "every name the formula filters or groups on is a column")
    (is (contains? indexes "ux_feed_views_card_id_day_member")
        "…and (card_id, day, member) is unique AND ordered for the
         by-card read; the member's own read walks the table, which is
         the trade docs/spec-feed.md records with the growth math")
    (testing "the switch is one row per member, in the storage and not
              only in a guard — 'off' cannot be half-true"
      (is (contains? (:indexes (store/kind-projection fv/feed-view-consent))
                     "ux_feed_view_consents_member")))))

(deftest nobody-edits-what-they-were-shown
  (is (empty? (:actions fv/feed-view))
      "a view is not a thing that happens to a row over time")
  (is (= [:recorded] (:states fv/feed-view)))
  (is (= #{:recorded} (:terminal fv/feed-view))
      "born finished — there is nowhere for it to go")
  (testing "and the switch is reversible in both directions, because a
            household setting that could only be flipped once is a trap"
    (is (= #{} (:terminal fv/feed-view-consent)))
    (is (= #{:stop :resume} (set (keys (:actions fv/feed-view-consent)))))))

(deftest the-walls-stand-in-shape-then-world-order
  ;; insight's ordering, inherited: a body that names somebody else
  ;; hears about ITSELF before it hears anything about the house — and
  ;; because the last wall counts rows, a refused create spends nothing.
  (is (= [:a-view-is-your-own
          :the-member-turned-this-on
          :this-card-is-counted-once-a-day]
         (mapv :name (:create-guards fv/feed-view))))
  (testing "the first wall reads only the caller, which is what lets a
            previewer's mis-attribution be refused with no database in
            the room at all"
    (is (= [:principal] (:reads (first (:create-guards fv/feed-view))))))
  (testing "and the switch's own two walls read only the caller too, so
            both of its scenarios are judged by `check` for free"
    (is (every? #(= [:principal] (:reads %))
                (concat (:create-guards fv/feed-view-consent)
                        (mapcat :guards (vals (:actions fv/feed-view-consent))))))))
