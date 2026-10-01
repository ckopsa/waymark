(ns workqueue10.demo-seed-test
  "The demo seed itself (docs/spec-demo-clones.md § 1, § 5 item 1),
  loaded where its own kinds are: the factory's kinds over the
  in-memory twin. This is what keeps the seed true as the kinds change:
  a step the law now refuses fails here before it fails a clone's boot.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.demo-seed-test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.main :as factory]
            [waymark10.dev :as dev]
            [waymark10.server.invoke :as inv]
            [waymark10.server.seed :as seed]
            [waymark10.server.store :as store]))

(defn- moves [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind kind :resource-id (str id)} {}))))

(defn- state-of [row] (keyword (name (:state row))))

(deftest the-demo-seed-loads-and-reaches-every-ticket-state-but-in-review
  (let [eng (dev/scratch! (factory/resources) {:name "demo-test"})
        demo (seed/read-seed "demo")
        result (seed/load! eng demo {})
        tickets (dev/rows eng :ticket)
        by-state (frequencies (map state-of tickets))
        declared (set (:states (get (inv/resources eng) :ticket)))]
    (is (true? (:seeded result)))
    (testing "every state the kind declares but in_review, which only a change's submit reaches"
      (is (contains? declared :in_review))
      (is (= (disj declared :in_review) (set (keys by-state))))
      (is (every? #(<= 2 %) (vals by-state)) (pr-str by-state)))
    (testing "each ticket reached its state by the doors"
      (doseq [t tickets]
        (is (seq (moves eng :ticket (:id t))) (get-in t [:data :title]))))
    (testing "two households, as two groups of members"
      (is (= 2 (count (set (map :household (vals (:cast demo)))))))
      (doseq [m (vals (:cast demo))]
        (is (some? (dev/row eng :member (:id m))) (:id m))))
    (testing "one held call waits on a person's tap"
      (let [held (dev/rows eng :held_call)]
        (is (= [:held] (mapv state-of held)))
        (is (= "plan" (get-in (first held) [:data :caller])))))
    (testing "one open invitation, to the member the person signs in as"
      (let [invited (dev/rows eng :invitation)]
        (is (= [:open] (mapv state-of invited)))
        (is (= "ada" (get-in (first invited) [:data :subject])))))
    (testing "the household's agent authored it, wearing the grant the seed minted"
      (let [invited (first (dev/rows eng :invitation))
            author (get-in invited [:data :author])
            granted (filter #(= author (get-in % [:data :audience]))
                            (dev/rows eng :grant))]
        (is (= "plan" author))
        (is (= "agent" (get-in (dev/row eng :member author) [:data :actor_type])))
        (is (= [:accepted] (mapv state-of granted)))))
    (testing "a restarted task does not seed twice"
      (is (false? (:seeded (seed/load! eng demo {}))))
      (is (= (count tickets) (count (dev/rows eng :ticket)))))))
