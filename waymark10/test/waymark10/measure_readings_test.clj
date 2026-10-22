(ns waymark10.measure-readings-test
  "Measures step 4 (docs/spec-measures.md §4): the maintainer's measure
  sweep writes one measure_reading per measure per window, judged
  against the goal in force — met and unknown counted, unjudged below
  :at-least and over the read cap, sealed after the window ends and
  never written again. In memory."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.goals :as goals]
            [waymark10.server.invoke :as inv]
            [waymark10.server.maintainer :as maintainer]
            [waymark10.server.measure :as measure]
            [waymark10.server.readings :as readings]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private token "mr_order.on_time")

(def ^:private on-time
  {:label "Orders on time"
   :stat :rate
   :among "state=placed"
   :where '(= (data :on_time) true)
   :at :placed_at
   :over '(weeks 1)})

(def ^:private order-decl
  {:kind :mr_order
   :plural "mr_orders"
   :states [:placed :shipped]
   :initial :placed
   :terminal #{:shipped}
   :summary "{data.name} · {state}"
   :schema [:map
            [:name [:string {:max 40}]]
            [:on_time :boolean]
            [:placed_at :waymark/instant]]
   :filterable {:state #{:eq :in} :placed_at #{:eq}}
   :measures {:on_time on-time
              :mine (assoc on-time :label "Orders named for the reader"
                           :where '(= (data :name) (me)))}
   :actions {:ship {:from #{:placed} :to :shipped
                    :safety {:idempotent false :reversible false :confirm false
                             :one-way "Shipped is shipped."}
                    :display {:label "Ship"}}}})

(def ^:private olive (t/principal {:id "olive" :display "Olive"}))

(defn- eng!
  ([clock] (eng! clock order-decl))
  ([clock decl]
   (let [eng (engine/engine {:storage (store/stamped (memory/storage) #(deref clock))
                             :now-fn #(deref clock)
                             :resources [(r/resource decl) goals/goal
                                         readings/measure-reading]})]
     (inv/create! eng :work_calendar {:name "House" :zone "UTC" :default true}
                  {:principal olive})
     eng)))

(defn- at [s] (Instant/parse s))

(defn- orders! [eng n on-time placed-at]
  (dotimes [i n]
    (inv/create! eng :mr_order {:name (str "o" i) :on_time (< i on-time) :placed_at placed-at}
                 {:principal olive})))

(defn- goal! [eng target]
  (:row (inv/create! eng :goal {:measure token :op ">=" :target target}
                     {:principal maintainer/goal-actor})))

(defn- readings-of [eng measure-token]
  (let [rdef (get (inv/resources eng) :measure_reading)]
    (sort-by #(str (get-in % [:data :window_start]))
             (mapv #(inv/decode-row rdef %)
                   (store/with-tx (:storage eng)
                     (fn [tx] (store/query-rows (:storage eng) tx :measure_reading
                                                {:measure measure-token} {:limit 50})))))))

(defn- dec= [a b] (zero? (compare (bigdec (str a)) (bigdec (str b)))))

(deftest a-sweep-counts-met-and-unknown-against-the-goal
  (let [clock (atom (at "2026-10-07T12:00:00Z"))
        eng (eng! clock)]
    (orders! eng 12 9 "2026-10-06T10:00:00Z")
    (orders! eng 2 2 "2026-09-30T10:00:00Z")
    (goal! eng 0.95M)
    (is (= 2 (maintainer/sweep-measures! eng)) "one reading per measure")
    (let [[rd :as rs] (readings-of eng token)
          d (:data rd)]
      (is (= 1 (count rs)))
      (is (= "open" (name (:state rd))))
      (is (= "2026-10-05" (str (:window_start d))) "the aligned week, not the last 7 days")
      (is (= "2026-10-12" (str (:window_end d))))
      (is (= 12 (:among d)) "last week's orders are another window's")
      (is (= 9 (:met d)))
      (is (= 0 (:unknown d)))
      (is (dec= 0.75M (:value d)))
      (is (= "missed" (:verdict d)))
      (is (dec= 0.95M (:target d)))
      (is (= ">=" (:op d)))
      (is (some? (:goal_id d)))
      (is (= 3 (count (:evidence d))) "the three late orders"))
    (testing "a :where it cannot judge is unknown, and with no goal unjudged"
      (let [d (:data (first (readings-of eng "mr_order.mine")))]
        (is (= 12 (:among d)))
        (is (= 0 (:met d)))
        (is (= 12 (:unknown d)))
        (is (= "unjudged" (:verdict d)))))
    (testing "inside the cadence a second sweep writes nothing"
      (is (= 0 (maintainer/sweep-measures! eng))))))

(deftest below-at-least-is-unjudged
  (let [clock (atom (at "2026-10-07T12:00:00Z"))
        eng (eng! clock)]
    (orders! eng 3 1 "2026-10-06T10:00:00Z")
    (goal! eng 0.95M)
    (maintainer/sweep-measures! eng)
    (let [d (:data (first (readings-of eng token)))]
      (is (= 3 (:among d)))
      (is (some? (:value d)) "the number still shows")
      (is (= "unjudged" (:verdict d)) "never a miss below :at-least"))))

(deftest a-reading-seals-after-its-window-and-stays-sealed
  (let [clock (atom (at "2026-10-07T12:00:00Z"))
        eng (eng! clock)]
    (orders! eng 10 10 "2026-10-06T10:00:00Z")
    (goal! eng 0.95M)
    (maintainer/sweep-measures! eng)
    (orders! eng 2 0 "2026-10-08T10:00:00Z")
    (reset! clock (at "2026-10-13T00:30:00Z"))
    (maintainer/sweep-measures! eng)
    (let [[old cur] (readings-of eng token)]
      (is (= "sealed" (name (:state old))))
      (is (= 12 (get-in old [:data :among])) "the final values, read at the window's end")
      (is (= "missed" (get-in old [:data :verdict])))
      (is (= "open" (name (:state cur))))
      (is (= "2026-10-12" (str (get-in cur [:data :window_start]))))
      (is (= 0 (get-in cur [:data :among])))
      (is (nil? (get-in cur [:data :value])) "no rows: no rate, not 0 and not 1")
      (testing "a sealed reading is never written again"
        (orders! eng 5 5 "2026-10-09T10:00:00Z")
        (reset! clock (at "2026-10-13T05:00:00Z"))
        (maintainer/sweep-measures! eng)
        (let [[old' _] (readings-of eng token)]
          (is (= (:data old) (:data old'))))))
    (testing "no hand at the wire writes a reading"
      (is (thrown? Exception
                   (inv/create! eng :measure_reading
                                {:measure token :window_start "2026-10-12"
                                 :window_end "2026-10-19" :among 0
                                 :verdict "unjudged" :evidence []
                                 :swept_at "2026-10-13T00:00:00Z"}
                                {:principal olive}))))))

(deftest over-the-read-cap-is-unjudged-with-the-reason
  (let [clock (atom (at "2026-10-07T12:00:00Z"))
        eng (eng! clock)]
    (orders! eng 6 6 "2026-10-06T10:00:00Z")
    (goal! eng 0.5M)
    (with-redefs [measure/read-cap 5]
      (maintainer/sweep-measures! eng))
    (let [d (:data (first (readings-of eng token)))]
      (is (= "unjudged" (:verdict d)))
      (is (nil? (:value d)) "never a number from part of the window")
      (is (re-find #"over 5 rows" (first (:evidence d)))))))

(deftest a-transition-measure-reads-the-log
  (let [clock (atom (at "2026-10-07T12:00:00Z"))
        shipped {:label "Orders shipped" :stat :count :among "state=shipped"
                 :at "transition:ship" :over '(weeks 1)}
        eng (eng! clock (assoc order-decl :measures
                               {:shipped shipped
                                :shipped_on_time (assoc shipped :label "Shipped on time"
                                                        :stat :rate
                                                        :where '(= (data :on_time) true))}))
        ids (mapv #(str (:id (:row (inv/create! eng :mr_order
                                                {:name (str "s" %) :on_time (< % 2)
                                                 :placed_at "2026-10-06T10:00:00Z"}
                                                {:principal olive}))))
                  (range 4))]
    (doseq [id (take 3 ids)]
      (inv/invoke! eng :mr_order id :ship nil {:principal olive
                                               :idempotency-key (str (random-uuid))}))
    (is (= 2 (maintainer/sweep-measures! eng)))
    (testing "a transition-counted :count reads a number"
      (let [d (:data (first (readings-of eng "mr_order.shipped")))]
        (is (= 3 (:among d)) "one per ship in the window")
        (is (dec= 3 (:value d)))
        (is (= [] (:evidence d)) "no unread reason")))
    (testing "a transition-counted :rate judges the rows the log names"
      (let [d (:data (first (readings-of eng "mr_order.shipped_on_time")))]
        (is (= 3 (:among d)))
        (is (= 2 (:met d)))
        (is (dec= 0.6667M (:value d)))
        (is (= [(nth ids 2)] (:evidence d)) "the late one shipped")))))
