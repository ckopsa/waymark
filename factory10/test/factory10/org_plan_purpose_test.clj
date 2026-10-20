(ns factory10.org-plan-purpose-test
  "The purpose backfill (ticket 0fa0eb6c): an org_plan stored before
  `purpose` was declared reads `found` once the store's boot ran the
  kind's :data-migrate, a hire plan is untouched, and a `purpose <>
  hire` cond then matches the legacy live found plan; a create without
  `purpose` stores `found`.

  No database: dev/scratch! is the whole world.

  Run: cd factory10 && clojure -M:test"
  (:require [clojure.test :refer [deftest is testing]]
            [factory10.main :as main]
            [factory10.org-plan-walk :as walk]
            [factory10.resources.org-plan :as org-plan]
            [waymark10.dev :as dev]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private t0 (Instant/parse "2026-10-18T08:00:00Z"))

(defn- engine [] (dev/scratch! (main/resources) {:now-fn (constantly t0)}))

(def ^:private the-owner
  (t/principal {:id "colton" :type :human :display "colton"}))

(def ^:private the-engine
  (t/principal {:id "factory10-hire" :type :system :display "The engine"}))

(def ^:private gap
  {:kind "invoice" :state "sent" :reason "no_holder"
   :row_ids ["i1" "i2" "i3"] :oldest_since "2026-10-11T08:00:00Z"
   :expected_seconds 604800 :key "greenleaf/invoice/sent"})

(defn- filed! [eng]
  (:row (inv/create! eng :org_plan
                     {:name "greenleaf"
                      :brief "A six-person landscaping business."}
                     {:principal the-owner})))

(defn- stored [eng id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx :org_plan (str id) {}))))

(defn- forget-purpose!
  "The plan's stored document as it stood before M4a: no `purpose`."
  [eng id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (let [raw (store/load-row (:storage eng) tx :org_plan (str id) {})]
        (store/save-row! (:storage eng) tx :org_plan
                         (update raw :data dissoc :purpose) (:version raw))))))

(defn- not-hire
  "The ids a `purpose <> hire` cond matches, as the store answers it."
  [eng]
  (set (map str (store/with-tx (:storage eng)
                  (fn [tx]
                    (store/ids-matching (:storage eng) tx :org_plan
                                        [{:target :data :field :purpose
                                          :cast "text" :op :not= :value "hire"}]
                                        1000))))))

(deftest a-legacy-plan-is-backfilled-found-at-boot
  (let [eng (engine)
        live-id (str (walk/live! eng (:id (filed! eng)) the-owner))
        hire-id (str (:id (:row (org-plan/open-hire! eng {:name "greenleaf" :gap gap}
                                                     {:principal the-engine}))))]
    (forget-purpose! eng live-id)
    (testing "before the backfill a <> hire cond misses the legacy plan"
      (is (nil? (get-in (stored eng live-id) [:data :purpose])))
      (is (not (contains? (not-hire eng) live-id))))
    (store/ensure-kind! (:storage eng) (get (inv/resources eng) :org_plan))
    (testing "the boot's backfill writes found, and leaves the hire plan be"
      (is (= "found" (get-in (stored eng live-id) [:data :purpose])))
      (is (= "hire" (get-in (stored eng hire-id) [:data :purpose]))))
    (testing "the <> hire cond now matches the legacy live found plan"
      (is (contains? (not-hire eng) live-id))
      (is (not (contains? (not-hire eng) hire-id))))))

(deftest a-create-without-purpose-stores-found
  (let [eng (engine)]
    (is (= "found" (get-in (stored eng (:id (filed! eng))) [:data :purpose])))))
