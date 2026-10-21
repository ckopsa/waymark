(ns workqueue10.week-test
  "workqueue10.week (ticket f084a59c): the fixture org's three-day week
  plays in-process, and its report names the guard that refused a call,
  counts the call held for the owner and allowed the next day, and
  reaches the payoff row. A broken script throws; a refusal does not.
  No database: the in-memory twin.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.week-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [workqueue10.week :as week]))

(def ^:private fixture "../orgs/week_fixture")

(defn- entry [report day action row]
  (first (filter #(and (= day (:day %)) (= action (:action %))
                       (= row (or (:row %) (:name %))))
                 (:calls report))))

(deftest the-fixture-week-reports-its-refusal-its-hold-and-its-payoff
  (let [r (week/play fixture)]
    (testing "a call a guard refuses is in the report, naming the guard"
      (let [c (entry r 1 "quote" "hedge")]
        (is (= "refused" (:outcome c)) (pr-str c))
        (is (str/includes? (str (:guard c)) "has-budget") (pr-str c))))
    (testing "the same door moves a job that says its budget"
      (is (= "moved" (:outcome (entry r 1 "quote" "lawn"))) (pr-str (:calls r))))
    (testing "the mayor's booking is held, and the owner allows it the next day"
      (is (= "held" (:outcome (entry r 2 "book" "lawn"))) (pr-str (:calls r)))
      (let [a (entry r 3 "allow" "lawn")]
        (is (= "moved" (:outcome a)) (pr-str a)))
      (is (= 1 (:person_asks r)))
      (is (= 0 (:still_held r))))
    (testing "a {\"$row\" name} input names the row an earlier call made"
      (is (= "moved" (:outcome (entry r 3 "create" "edging"))) (pr-str (:calls r))))
    (testing "the payoff row is reached"
      (is (= {:kind "garden_job" :state "booked" :count 1}
             (select-keys (:goal r) [:kind :state :count]))
          (pr-str (:goal r))))
    (testing "the text report says the same"
      (is (str/includes? (week/text r) "goal: garden_job booked, 1 row(s)")))))

(deftest a-broken-script-throws-and-a-refusal-does-not
  (let [script (week/read-week (java.io.File. fixture) "week_fixture")
        broken? (fn [w]
                  (try (week/play fixture w) false
                       (catch clojure.lang.ExceptionInfo e
                         (true? (:workqueue10.week/broken (ex-data e))))))]
    (is (broken? (assoc-in script [:days 0 :calls 1 :row] "nowhere"))
        "a row name no earlier call made")
    (is (broken? (assoc-in script [:days 0 :calls 0 :as] :crew))
        "a cast the week does not know")
    (is (broken? (assoc-in script [:days 0 :calls 0 :kind] "no_such_kind"))
        "a kind the engine does not serve")))
