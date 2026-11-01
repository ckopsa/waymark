(ns waymark10.amendment-trial-test
  "Amendments step 8 (docs/spec-amendment.md §14): the trial's matched
  windows, its clipped breach seconds, its verdicts and `settle`."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.amendment-trial :as trial]
            [waymark10.server.amendments :as amendments]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.promises :as promises]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Duration Instant ZoneOffset)))

(def ^:private t0 (Instant/parse "2026-10-01T00:00:00Z"))

(defn- day [n] (.plus t0 (Duration/ofDays n)))

(def ^:private week {:from t0 :until (day 7)})

(deftest a-quiet-pair-reads-quiet
  (let [r (trial/reading "kept" [] week)]
    (is (= {:breaks 0 :keeps 0 :breach_seconds 0} r))
    (is (= ["quiet" nil] (trial/decide {:promises {:p {:before r :after r}}}))))
  (testing "a keep in either window is evidence, and the pair is not quiet"
    (is (= "same" (first (trial/decide {:promises {:p {:before {:breaks 0 :keeps 1 :breach_seconds 0}
                                                         :after {:breaks 0 :keeps 0 :breach_seconds 0}}}}))))))

(deftest an-unkept-breach-counts-to-the-windows-end
  (let [r (trial/reading "kept" [{:at (day 5) :action "break" :to "broken"}] week)]
    (is (= 1 (:breaks r)))
    (is (= (* 2 86400) (:breach_seconds r))))
  (testing "a breach standing when the window opens counts from its start"
    (is (= (* 3 86400)
           (:breach_seconds (trial/reading "broken" [{:at (day 3) :action "keep" :to "kept"}] week)))))
  (testing "moves outside the window are not read"
    (is (= {:breaks 0 :keeps 0 :breach_seconds 0}
           (trial/reading "kept" [{:at (day 8) :action "break" :to "broken"}] week))))
  (testing "same breaks and more breach seconds reads worse; far fewer reads better"
    (let [verdict (fn [b a] (first (trial/decide {:promises {:p {:before {:breaks 1 :keeps 0 :breach_seconds b}
                                                                   :after {:breaks 1 :keeps 0 :breach_seconds a}}}})))]
      (is (= "worse" (verdict 100 200)))
      (is (= "same" (verdict 100 110)))
      (is (= "better" (verdict 100 75))))))

(deftest an-overlapping-trial-moves-the-before-window
  (let [ws (trial/windows (day 20) ZoneOffset/UTC [{:from (day 15) :until (day 22)}])]
    (is (= {:from (day 8) :until (day 15)} (:before ws)))
    (is (= {:from (day 20) :until (day 27)} (:after ws))))
  (testing "no overlap: the week that ends at the adopt"
    (is (= {:from (day 13) :until (day 20)} (:before (trial/windows (day 20) ZoneOffset/UTC [])))))
  (testing "past 30 days back it is unjudged"
    (is (= "no clean window before"
           (:unjudged (trial/windows (day 20) ZoneOffset/UTC [{:from (day -9) :until (day 18)}]))))))

;; ── the sweep settles ────────────────────────────────────────────────

(def ^:private elena (t/principal {:id "elena" :type :human :display "Elena"}))

(def ^:private office
  {:name "planner"
   :charter "Plan the week."
   :scope [{:kind "amendment" :actions ["withdraw"]}]
   :held_for []
   :substitute_for []
   :standing_ttl_seconds 604800
   :cadence_seconds 3600
   :budget_usd_per_week 5M
   :sitting_budget_tokens 60000
   :rows_per_firing 20})

(defn- row [eng id]
  (let [st (:storage eng)]
    (store/with-tx st #(store/load-row st % :amendment id {}))))

(defn- later [eng seconds]
  (assoc eng :now-fn (constantly (.plusSeconds (Instant/now) (long seconds)))))

(deftest an-adopted-amendment-settles-when-its-trial-ends
  (let [eng (engine/engine {:storage (memory/storage)
                            :resources [promises/promise amendments/amendment]})
        sid (str (:id (:row (inv/create! eng :seat office {:principal elena}))))
        aid (str (:id (:row (inv/create! eng :amendment
                                         {:title "Plan next week"
                                          :changes {:kind "seat" :id sid :door "restate"
                                                    :patch {:charter "Plan next week."}}}
                                         {:principal elena}))))]
    (amendments/replay! eng aid)
    (inv/invoke! eng :amendment aid :adopt nil {:principal elena :idempotency-key "adopt"})
    (is (= :trial (keyword (name (:state (row eng aid))))))
    (testing "before T the trial runs on"
      (trial/sweep! (later eng 3600))
      (is (= "trial" (name (:state (row eng aid))))))
    (testing "at T it settles, and an amendment that answers no promise is unjudged"
      (trial/sweep! (later eng (* 8 86400)))
      (let [r (row eng aid)]
        (is (= "settled" (name (:state r))))
        (is (= "unjudged" (get-in r [:data :verdict])))
        (is (= "answers no promise" (get-in r [:data :trial :reason])))
        (is (some? (get-in r [:data :trial :before_window])))))))
