(ns waymark10.integrations-test
  "The integration (docs/spec-integrations.md § 2, build step 1): a
  person proposes it, another person approves it, and the approve
  mints the grant it wears as `integration:<id>`. Memory storage, real
  rows through the engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.engine :as engine]
            [waymark10.server.integrations :as integrations]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private clock (Instant/parse "2026-10-21T09:00:00Z"))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(def ^:private mom (t/principal {:id "mom" :display "Mom"}))

(def ^:private clerk
  (t/principal {:id "stripe-clerk" :type :agent :display "Clerk"}))

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage)
                  :resources []
                  :now-fn (fn [] clock)}))

(defn- row-of [eng kind id]
  (let [rdef (get (inv/resources eng) kind)]
    (inv/decode-row rdef
                    (store/with-tx (:storage eng)
                      (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))))

(defn- refused
  "The problem a thunk throws, or nil when it answered."
  [thunk]
  (try (thunk) nil (catch Exception e e)))

(defn- propose! [eng]
  (str (get-in (inv/create! eng :integration
                            {:name "stripe"
                             :system "Stripe, the payment processor"
                             :event_id_path ["id"]
                             :event_type_path ["type"]}
                            {:principal colton})
               [:row :id])))

(deftest a-person-proposes-it
  (let [eng (fresh-engine)
        id (propose! eng)
        row (row-of eng :integration id)]
    (is (= :proposed (:state row)))
    (is (= "colton" (get-in row [:data :created_by])))
    (is (nil? (get-in row [:data :grant])) "no grant before the approve")
    (testing "and an agent does not"
      (is (some? (refused #(inv/create! eng :integration
                                        {:name "shippo" :system "Shippo, the postage"}
                                        {:principal clerk})))))))

(deftest the-creator-cannot-approve
  (let [eng (fresh-engine)
        id (propose! eng)
        e (refused #(inv/invoke! eng :integration id :approve {}
                                 {:principal colton}))]
    (is (some? e))
    (is (str/includes? (str (ex-message e)) "cannot be the one who approves"))
    (is (= :proposed (:state (row-of eng :integration id))) "and the row did not move")
    (is (nil? (get-in (row-of eng :integration id) [:data :grant])))
    (testing "nor does an agent"
      (is (some? (refused #(inv/invoke! eng :integration id :approve {}
                                        {:principal clerk}))))
      (is (= :proposed (:state (row-of eng :integration id)))))))

(deftest approve-mints-a-grant
  (let [eng (fresh-engine)
        id (propose! eng)
        _ (inv/invoke! eng :integration id :approve {} {:principal mom})
        row (row-of eng :integration id)
        gid (get-in row [:data :grant])
        grant (row-of eng :grant gid)]
    (is (= :live (:state row)))
    (is (= "mom" (get-in row [:data :approved_by])))
    (is (some? gid) "the row names the grant it wears")
    (is (= (str "integration:" id) (get-in grant [:data :audience]))
        "the grant is the integration's own, not the approver's")
    (is (= (integrations/principal-id row) (get-in grant [:data :audience])))
    (is (= :accepted (:state grant)) "and it is live")))
