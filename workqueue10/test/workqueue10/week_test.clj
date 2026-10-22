(ns workqueue10.week-test
  "workqueue10.week (ticket f084a59c): the fixture org's two-day week
  plays in-process, and its report names the guard that refused a call,
  counts the call held for the owner and allowed the same day (ticket
  2fa0d276; `:yesterday` warns it allowed an expired call), and
  reaches the payoff row. A broken script throws; a refusal does not.
  No database: the in-memory twin.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.week-test"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.store :as store]
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
    (testing "the mayor's booking is held, and the owner allows it the same day"
      (is (= "held" (:outcome (entry r 1 "book" "lawn"))) (pr-str (:calls r)))
      (let [a (entry r 1 "allow" "lawn")]
        (is (= "moved" (:outcome a)) (pr-str a))
        (is (nil? (:warning a)) (pr-str a)))
      (is (= 1 (:person_asks r)))
      (is (= 0 (:still_held r)))
      (is (= [] (:warnings r))))
    (testing "the next day's follow-up, a {\"$row\" name} input naming the row an earlier call made, moves"
      (is (= "moved" (:outcome (entry r 2 "create" "edging"))) (pr-str (:calls r))))
    (testing "the payoff row is reached"
      (is (= {:kind "garden_job" :state "booked" :count 1}
             (select-keys (:goal r) [:kind :state :count]))
          (pr-str (:goal r))))
    (testing "the text report says the same"
      (is (str/includes? (week/text r) "goal: garden_job booked, 1 row(s)")))))

(deftest a-week-that-allows-yesterday-warns-for-each-expired-call
  (let [script (week/read-week (java.io.File. fixture) "week_fixture")
        r (week/play fixture (update script :days (partial mapv #(assoc % :person-allows :yesterday))))
        a (entry r 2 "allow" "lawn")]
    (is (nil? (entry r 1 "allow" "lawn")) (pr-str (:calls r)))
    (is (str/includes? (str (:warning a)) "[week-allow-late]") (pr-str a))
    (is (= 1 (count (:warnings r))) (pr-str (:warnings r)))
    (is (str/includes? (week/text r) "[week-allow-late]"))))

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

(deftest a-seed-that-names-a-param-plays-with-a-stand-in
  (testing "a one-day week plays, though no environment gives the seed's param"
    (let [script (week/read-week (io/file fixture) "week_fixture")
          r (week/play fixture (update script :days #(vec (take 1 %))))]
      (is (= "moved" (:outcome (entry r 1 "quote" "lawn"))) (pr-str (:calls r)))))
  (testing "the param's stand-in, week-<name>, lands in the secret row"
    (let [orgs-dir (.getPath (.getParentFile (.getAbsoluteFile (io/file fixture))))
          eng (week/boot! "week_fixture" orgs-dir (java.time.Instant/parse "2026-10-21T09:00:00Z"))
          st (:storage eng)
          secret (store/with-tx st
                   (fn [tx]
                     (when-some [s (first (store/query-rows st tx :secret {:name "fixture-signing"} {:limit 1}))]
                       (store/load-row st tx :secret (str (:id s)) {}))))]
      (is (= "week-signing_secret_fixture" (get-in secret [:data :value])) (pr-str secret)))))

(deftest an-outside-event-is-posted-signed-and-reports-its-ending
  (let [script (week/read-week (io/file fixture) "week_fixture")
        r (week/play fixture
                     (update-in script [:days 1 :calls] into
                                [{:as :world :event :fixture :name "withdrawn"
                                  :body {:type "job.withdrawn" :title "Edge the back lawn"}}
                                 {:as :world :event :fixture
                                  :body {:type "job.withdrawn" :title "Paint the fence"}}]))
        [hit miss] (filter #(= "event" (:kind %)) (:calls r))]
    (testing "an event whose match finds its row moves it, signed with the stand-in secret"
      (is (= "applied" (:outcome hit)) (pr-str hit))
      (is (= "fixture" (:action hit)))
      (is (string? (:receipt hit))))
    (testing "one whose match finds no row is unmatched"
      (is (= "unmatched" (:outcome miss)) (pr-str miss)))
    (testing "the rest of the week plays as before"
      (is (= "moved" (:outcome (entry r 2 "decline" "hedge"))) (pr-str (:calls r)))))
  (testing "an event that is not the world's is a broken script"
    (let [script (week/read-week (io/file fixture) "week_fixture")]
      (is (true? (try (week/play fixture (update-in script [:days 0 :calls] conj
                                                    {:as :mayor :event :fixture :body {}}))
                      false
                      (catch clojure.lang.ExceptionInfo e
                        (true? (:workqueue10.week/broken (ex-data e))))))))))
