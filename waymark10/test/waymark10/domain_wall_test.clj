(ns waymark10.domain-wall-test
  "The domain wall at the fire door (docs/spec-spend.md §5, spend step
  8): a fire is refused when its domain's week of spend reaches the
  domain's budget, naming the domain's largest seats. Pure: the guard
  is judged over a ctx whose hooks read plain maps, no database."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.postings :as postings]
            [waymark10.server.seats :as seats]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private now (Instant/parse "2026-10-20T12:00:00Z"))
(def ^:private recently (.minusSeconds ^Instant now 3600))

(defn- posting [account usd at sitting]
  {:data {:account account :amount {:amount usd :currency "USD"}
          :effective_at (str at) :source_id sitting}})

(defn- ctx
  "A fire door's ctx over these rows: `read` by kind and id, `find` by
  equality on data fields, and `sum-amounts` over the account and
  window conds `grants/account-window-conds` writes."
  [{:keys [domains seats sittings postings]}]
  (let [by-id (fn [rows] (into {} (map (juxt (comp str :id) identity)) rows))
        tables {:domain (by-id domains) :seat (by-id seats) :sitting (by-id sittings)}
        matches? (fn [row filt]
                   (every? (fn [[k v]] (= (str v) (str (get-in row [:data k])))) filt))]
    {:now now
     :read (fn [kind id] (get-in tables [kind (str id)]))
     :find (fn [kind filt _opts]
             (filter #(matches? % filt)
                     (if (= kind :posting) postings (vals (get tables kind)))))
     :sum-amounts (fn [_kind _field conds]
                    (let [account (:value (first conds))
                          [lo hi] (map #(Instant/parse (:value %)) (rest conds))]
                      {"USD" (reduce + 0M
                                     (for [p postings
                                           :let [at (Instant/parse (get-in p [:data :effective_at]))]
                                           :when (and (= account (get-in p [:data :account]))
                                                      (not (.isBefore at lo))
                                                      (not (.isAfter at hi)))]
                                       (bigdec (get-in p [:data :amount :amount]))))}))}))

(defn- judge [world seat]
  ((:check seats/domain-under-budget) seat {} (ctx world)))

(def ^:private kitchen {:id "d1" :data {:name "kitchen" :budget_usd_per_week 10M}})
(def ^:private kitchen-spend (postings/account-address :domain "d1" :spend))
(def ^:private cook {:id "s1" :data {:name "cook" :domain "d1"}})
(def ^:private baker {:id "s2" :data {:name "baker" :domain "d1"}})
(def ^:private sittings [{:id "x1" :data {:seat "s1"}} {:id "x2" :data {:seat "s2"}}])

(deftest a-domain-under-budget-fires
  (is (t/allow? (judge {:domains [kitchen] :seats [cook baker] :sittings sittings
                        :postings [(posting kitchen-spend "4.00" recently "x1")
                                   (posting kitchen-spend "5.99" recently "x2")]}
                       cook))))

(deftest a-domain-at-its-budget-refuses-naming-its-top-seats
  (let [v (judge {:domains [kitchen] :seats [cook baker] :sittings sittings
                  :postings [(posting kitchen-spend "3.00" recently "x1")
                             (posting kitchen-spend "7.00" recently "x2")]}
                 cook)]
    (is (t/deny? v))
    (is (= "kitchen" (get-in v [:vars :domain])))
    (is (= "10" (get-in v [:vars :spent])))
    (is (= "10" (get-in v [:vars :budget])))
    (is (= "baker 7, cook 3" (get-in v [:vars :top])))
    (is (= [{:seat "baker" :spent "7"} {:seat "cook" :spent "3"}]
           (get-in v [:evidence :top_seats])))))

(deftest spend-older-than-the-window-does-not-count
  (is (t/allow? (judge {:domains [kitchen] :seats [cook] :sittings sittings
                        :postings [(posting kitchen-spend "50.00"
                                            (.minusSeconds ^Instant now (* 8 86400)) "x1")]}
                       cook))))

(deftest a-seat-that-moved-counts-toward-the-domain-it-posted-to
  (let [pantry {:id "d2" :data {:name "pantry" :budget_usd_per_week 10M}}
        moved (assoc-in cook [:data :domain] "d2")
        world {:domains [kitchen pantry] :seats [moved baker] :sittings sittings
               :postings [(posting kitchen-spend "12.00" recently "x1")]}]
    (testing "the domain it posted to keeps that week"
      (let [v (judge world baker)]
        (is (t/deny? v))
        (is (= "cook 12" (get-in v [:vars :top])))))
    (testing "its new domain does not"
      (is (t/allow? (judge world moved))))))

(deftest a-domain-with-no-budget-has-no-wall
  (let [open {:id "d3" :data {:name "open"}}
        free {:id "s3" :data {:name "free" :domain "d3"}}]
    (is (t/allow? (judge {:domains [open] :seats [free]
                          :sittings [{:id "x3" :data {:seat "s3"}}]
                          :postings [(posting (postings/account-address :domain "d3" :spend)
                                              "999.00" recently "x3")]}
                         free)))))

(deftest no-hand-to-sum-with-is-no-wall
  (is (t/allow? ((:check seats/domain-under-budget) cook {} {:now now}))))
