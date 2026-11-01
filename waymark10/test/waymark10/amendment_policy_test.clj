(ns waymark10.amendment-policy-test
  "Amendments step 10 (docs/spec-amendment.md §14): an amendment is
  adopted in the mode its kind's policy holds, a watched `worse` reverts
  nothing, and an armed one reverts once."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.amendment-policy :as policy]
            [waymark10.server.amendment-trial :as trial]
            [waymark10.server.amendments :as amendments]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.promises :as promises]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

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

(defn- house []
  (engine/engine {:storage (memory/storage)
                  :resources [promises/promise amendments/amendment policy/amendment-policy]}))

(defn- load-row [eng kind id]
  (let [st (:storage eng)]
    (store/with-tx st #(store/load-row st % kind id {}))))

(defn- restates [eng sid]
  (let [st (:storage eng)]
    (count (filter #(and (= sid (str (:resource-id %))) (= "restate" (name (:action %))))
                   (store/with-tx st #(store/transitions st % {:kind :seat}
                                                         {:newest-first true :limit 1000}))))))

(defn- policy! [eng door]
  (let [pid (str (:id (:row (inv/create! eng :amendment_policy {:target "seat.restate"}
                                         {:principal elena}))))]
    (when door
      (inv/invoke! eng :amendment_policy pid door nil {:principal elena :idempotency-key (str "policy-" (name door))}))
    pid))

(defn- adopt! [eng]
  (let [sid (str (:id (:row (inv/create! eng :seat office {:principal elena}))))
        aid (str (:id (:row (inv/create! eng :amendment
                                         {:title "Plan next week"
                                          :changes {:kind "seat" :id sid :door "restate"
                                                    :patch {:charter "Plan next week."}}}
                                         {:principal elena}))))]
    (amendments/replay! eng aid)
    (inv/invoke! eng :amendment aid :adopt nil {:principal elena :idempotency-key "adopt"})
    {:sid sid :aid aid}))

(defn- sweep-worse! [eng]
  (with-redefs [trial/judge (fn [& _] {:trial {:reason "breaks rose"} :verdict "worse"})]
    (trial/sweep! (assoc eng :now-fn (constantly (.plusSeconds (Instant/now) (* 8 86400)))))))

(deftest a-watched-worse-reverts-nothing
  (let [eng (house)
        {:keys [sid aid]} (adopt! eng)]
    (is (= "watch" (get-in (load-row eng :amendment aid) [:data :mode])))
    (sweep-worse! eng)
    (let [r (load-row eng :amendment aid)]
      (is (= "settled" (name (:state r))))
      (is (= "worse" (get-in r [:data :verdict]))))
    (is (= 1 (restates eng sid)) "the adopt's restate, and no revert")))

(deftest an-armed-worse-reverts-once
  (let [eng (house)
        pid (policy! eng :arm)
        _ (is (true? (get-in (load-row eng :amendment_policy pid) [:data :auto_revert])))
        {:keys [sid aid]} (adopt! eng)]
    (is (= "revert" (get-in (load-row eng :amendment aid) [:data :mode])))
    (sweep-worse! eng)
    (sweep-worse! eng)
    (is (= "reverted" (name (:state (load-row eng :amendment aid)))))
    (is (= 2 (restates eng sid)) "the adopt's restate and one revert")))

(deftest a-later-flip-leaves-the-trial-alone
  (let [eng (house)
        pid (policy! eng :arm)
        {:keys [aid]} (adopt! eng)]
    (inv/invoke! eng :amendment_policy pid :disarm nil {:principal elena :idempotency-key "disarm"})
    (is (false? (get-in (load-row eng :amendment_policy pid) [:data :auto_revert])))
    (is (= "revert" (get-in (load-row eng :amendment aid) [:data :mode])))
    (testing "one policy per kind"
      (is (thrown? Exception (policy! eng nil))))))
