(ns waymark10.measure-law-test
  "Measures step 8 (docs/spec-measures.md §7): a law scenario's :goals
  and :expect :readings, refused without a clock; an org's seed carries
  its goals to a clone and no readings; the law sweep reads a changed
  measure over the last sealed window beside the sealed value. In
  memory."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.scenario :as scenario]
            [waymark10.server.engine :as engine]
            [waymark10.server.goals :as goals]
            [waymark10.server.invoke :as inv]
            [waymark10.server.maintainer :as maintainer]
            [waymark10.server.org :as org]
            [waymark10.server.readings :as readings]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private on-time
  {:label "Orders shipped on time"
   :stat :rate
   :among "state=shipped"
   :where '(= (data :on_time) true)
   :at :placed_at
   :over '(weeks 1)
   :at-least 2})

(def ^:private order
  (r/resource
   {:kind :ml_order
    :plural "ml_orders"
    :states [:placed :shipped]
    :initial :placed
    :terminal #{:shipped}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:max 40}]]
             [:on_time :boolean]
             [:placed_at :waymark/instant]]
    :filterable {:state #{:eq :in} :placed_at #{:eq}}
    :measures {:on_time on-time}
    :actions {:ship {:from #{:placed} :to :shipped
                     :safety {:idempotent true :reversible false :confirm false
                              :one-way "Shipped is shipped."}
                     :display {:label "Ship"}}}}))

(defn- dec= [a b] (zero? (compare (bigdec (str a)) (bigdec (str b)))))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))

;; ── a law scenario reads the measure its walk leaves ──

(def ^:private sentence
  "Shipping a late order brings the week's on-time rate under its goal.")

(defn- shipped [n on-time placed-at]
  {:kind :ml_order :state :shipped :data {:name n :on_time on-time :placed_at placed-at}})

(defn- late
  "A late order shipped on Wednesday of the week of 2026-10-05: three
  shipped orders in the week beside it, two on time, and one the week
  before."
  [readings]
  {:kind :ml_order :attempt :ship
   :at "2026-10-07T12:00:00Z"
   :calendar {:zone "UTC" :workdays #{:mon :tue :wed :thu :fri}}
   :row {:state :placed :data {:name "e" :on_time false :placed_at "2026-10-07T09:00:00Z"}}
   :given [(shipped "a" true "2026-10-05T10:00:00Z")
           (shipped "b" true "2026-10-06T10:00:00Z")
           (shipped "c" false "2026-10-06T11:00:00Z")
           (shipped "d" true "2026-09-30T10:00:00Z")]
   :goals [{:measure "ml_order.on_time" :op ">=" :target 0.9}]
   :expect {:allowed true :readings readings}})

(deftest a-scenario-reads-the-measure-its-walk-leaves
  (testing "the walked row joins its window as shipped, and the goal judges the rate"
    (let [s (scenario/scenario :late sentence
                               (late {"ml_order.on_time" {:value 0.5 :verdict :missed
                                                          :among 4 :met 2}}))]
      (is (scenario/check-tier? order s) "a reading scenario is judged here")
      (is (= [] (scenario/judge order s)))))
  (testing "a wrong reading fails in the household's words, naming both values"
    (let [[v :as vs] (scenario/judge order (scenario/scenario
                                            :late sentence
                                            (late {"ml_order.on_time" {:value 0.75 :verdict :met}})))]
      (is (= 1 (count vs)))
      (is (str/includes? v sentence))
      (is (str/includes? v "value 0.5000, not 0.75"))
      (is (str/includes? v "verdict missed, not met"))))
  (testing "with no goal the reading is unjudged"
    (is (= [] (scenario/judge order (scenario/scenario
                                     :late sentence
                                     (assoc (late {"ml_order.on_time" {:value 0.5 :verdict :unjudged}})
                                            :goals []))))))
  (testing "a token the kind does not declare is an authoring fault"
    (is (str/includes? (first (scenario/judge order (scenario/scenario
                                                     :late sentence
                                                     (late {"ml_order.late" {:value 0.5}}))))
                       "does not declare"))))

(deftest a-reading-without-a-clock-is-refused
  (let [m (late {"ml_order.on_time" {:value 0.5}})]
    (is (str/includes? (refusal #(scenario/scenario :late sentence (dissoc m :at)))
                       "say :at"))
    (is (str/includes? (refusal #(scenario/scenario :late sentence (dissoc m :calendar)))
                       "say :calendar"))
    (is (str/includes? (refusal #(scenario/scenario :late sentence
                                                    (assoc m :goals [{:measure "ml_order.on_time"
                                                                      :op "==" :target 0.9}])))
                       ":op is one of"))))

;; ── a clone copies goals, not readings ──

(deftest an-orgs-seed-carries-its-goals-to-a-clone
  (let [gl {:measure "ml_order.on_time" :op ">=" :target 0.9}
        seed {:org "shop" :goals [gl]}]
    (doseq [opts [{} {:clone? true}]]
      (let [ls (org/loader-seed seed opts)]
        (is (some #{{:as :owner :kind :goal :create gl}} (:steps ls))
            "the goal is made through its door, live and on a clone")
        (is (not-any? #(= :measure_reading (:kind %)) (:steps ls)) "no reading is seeded")
        (is (not (contains? ls :goals)))))))

;; ── the law sweep reads a changed measure over the last sealed window ──

(def ^:private olive (t/principal {:id "olive" :display "Olive"}))

(defn- eng! [clock]
  (let [eng (engine/engine {:storage (memory/storage)
                            :now-fn #(deref clock)
                            :resources [order goals/goal readings/measure-reading]})]
    (inv/create! eng :work_calendar {:name "House" :zone "UTC" :default true}
                 {:principal olive})
    eng))

(deftest the-law-sweep-reads-a-changed-measure-over-the-last-sealed-window
  (let [clock (atom (Instant/parse "2026-10-07T12:00:00Z"))
        eng (eng! clock)]
    (doseq [[i on] (map-indexed vector [true true true false])]
      (let [id (str (:id (:row (inv/create! eng :ml_order
                                             {:name (str "o" i) :on_time on
                                              :placed_at "2026-10-06T10:00:00Z"}
                                             {:principal olive}))))]
        (inv/invoke! eng :ml_order id :ship nil {:principal olive})))
    (inv/create! eng :goal {:measure "ml_order.on_time" :op ">=" :target 0.9M}
                 {:principal maintainer/goal-actor})
    (maintainer/sweep-measures! eng)
    (reset! clock (Instant/parse "2026-10-13T12:00:00Z"))
    (maintainer/sweep-measures! eng)
    (let [proposed (assoc on-time :of :ml_order :where '(= (data :on_time) false))
          cmp (maintainer/reread-sealed eng "ml_order.on_time" proposed)]
      (is (= "2026-10-05" (:window_start cmp)) "the week before the one now falls in")
      (is (= "2026-10-12" (:window_end cmp)))
      (is (some? (:sealed cmp)))
      (is (dec= 0.75M (get-in cmp [:old :value])) "the sealed value")
      (is (= "missed" (get-in cmp [:old :verdict])))
      (is (dec= 0.25M (get-in cmp [:new :value])) "the proposed value, beside it")
      (is (= 4 (get-in cmp [:new :among])))
      (is (= "missed" (get-in cmp [:new :verdict]))))))
