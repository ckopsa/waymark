(ns waymark10.replay-guards-test
  "Guard replay (docs/spec-amendment.md §14 step 6): a recorded move
  that a new value would refuse is counted; a move whose input was not
  kept is not judged and names `refusals`."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.guards :as g]
            [waymark10.server.replay :as replay]
            [waymark10.server.replay-guards :as replay-guards]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Duration Instant)))

(def ^:private until (Instant/parse "2026-11-01T00:00:00Z"))

(defn- days-ago ^Instant [n] (.minus ^Instant until (Duration/ofDays n)))

(def ^:private spend {:kind :spend :plural "spends" :retain {:data true}})

(defn- fresh []
  (let [st (memory/storage)]
    (store/ensure-kind! st spend)
    st))

(defn- put-row! [st id state data]
  (store/with-tx st #(store/insert-row! st % :spend {:id id :state state :data data})))

(defn- log!
  "Append one move and pin its time, as the stores stamp their own."
  [st id action from to at inputs after]
  (let [rec (store/with-tx st
              #(store/append-transition!
                st % (cond-> {:kind :spend :resource-id id :action action
                              :from-state from :to-state to
                              :actor {:id "p" :type "person"}}
                       inputs (assoc :inputs inputs)
                       after (assoc :after after))))]
    (swap! (:state st) update :transitions
           (fn [log] (mapv #(if (= (:id rec) (:id %)) (assoc % :at at) %) log)))))

(defn- under [limit]
  (g/guard {:name :under-limit
            :explain "The amount is over the limit."
            :judges [:amount]
            :reads [:limit]
            :check (fn [_ inp _]
                     (if (<= (:amount inp) limit) (t/allow) (t/deny)))}))

(defn- token [r tok] (some #(when (= tok (:token %)) %) (:blind r)))

(deftest a-recorded-move-a-new-value-would-refuse-is-counted
  (let [st (fresh)]
    (put-row! st "s1" :approved {:amount 40})
    (put-row! st "s2" :approved {:amount 5})
    (log! st "s1" :approve :open :approved (days-ago 3) {:amount 40} {:amount 40})
    (log! st "s2" :approve :open :approved (days-ago 2) {:amount 5} {:amount 5})
    (let [w (replay/window until)
          r (replay-guards/replay st spend w {:doors {:approve [(under 10)]}
                                              :patched [:limit]})]
      (is (= 2 (:judged r)))
      (is (= ["s1"] (mapv :id (:refused r))))
      (is (= ["under-limit"] (:guards (first (:refused r)))))
      (is (= {"approve" 1} (:counts r)))
      (is (= 0 (:moments (token r "refusals")))
          "once-refused moves stay unseen, and the report says so")
      (testing "the old value refuses nothing"
        (is (empty? (:refused (replay-guards/replay
                               st spend w {:doors {:approve [(under 50)]}
                                           :patched [:limit]}))))))))

(deftest a-move-whose-input-was-not-kept-is-blind
  (let [st (fresh)]
    (put-row! st "s1" :approved {:amount 40})
    (log! st "s1" :approve :open :approved (days-ago 3) nil {:amount 40})
    (let [r (replay-guards/replay st spend (replay/window until)
                                  {:doors {:approve [(under 10)]}})]
      (is (= 0 (:judged r)))
      (is (empty? (:refused r)))
      (is (= 1 (:moments (token r "refusals")))))))

(deftest a-door-the-patch-does-not-touch-is-not-replayed
  (let [st (fresh)]
    (put-row! st "s1" :approved {:amount 40})
    (log! st "s1" :approve :open :approved (days-ago 3) {:amount 40} {:amount 40})
    (let [r (replay-guards/replay st spend (replay/window until)
                                  {:doors {:approve [(under 10)]}
                                   :patched [:title]})]
      (is (= 0 (:judged r)))
      (is (nil? (token r "refusals"))))))
