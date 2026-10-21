(ns waymark10.postings-test
  "Ledger step 3b (docs/spec-ledger-money.md §3-§4): postings are rows
  of the engine-served, read-only kind `posting`. post! writes them
  inside a given transaction; a query filters them by account and sorts
  them by effective_on; and no door creates or edits one. Needs the
  test database (WAYMARK10_TEST_DSN)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.modules :as modules]
            [waymark10.money :as money]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.postings :as postings]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t])
  (:import (java.time LocalDate)))

(def ^:private product
  (r/resource {:kind :pt_product
               :plural "pt_products"
               :states [:open]
               :initial :open
               :terminal #{}
               :allow-dead #{:open}
               :summary "{data.name} · {state}"
               :schema [:map [:name [:string {:max 40}]]]
               :accounts {:paid {:unit [:money "USD"]}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- usd [s] (money/money s "USD"))

(defn- with-st [f]
  (let [st (pg/storage db/dsn)]
    (try (f st) (finally (pg/close! st)))))

(defn- fresh! []
  (with-st
    (fn [st]
      (store/with-tx st
        (fn [tx]
          (doseq [table ["postings" "pt_products" "waymark10_transitions"
                         "waymark10_idempotency" "waymark10_drafts"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")])))))))

(deftest the-kind-is-enrolled-where-accounts-are-declared
  (is (some #(= :posting (:kind %)) (modules/enrolled [product] nil)))
  (is (not-any? #(= :posting (:kind %))
                (modules/enrolled [(r/resource {:kind :pt_plain
                                                :states [:open]
                                                :initial :open
                                                :terminal #{}
                                                :allow-dead #{:open}
                                                :summary "{data.name} · {state}"
                                                :schema [:map [:name [:string {:max 40}]]]})]
                                  nil))))

(deftest the-kind-has-no-edit-door
  (is (empty? (:actions postings/posting))))

(deftest post!-writes-rows-a-query-filters-and-sorts
  (fresh!)
  (with-st
    (fn [st]
      (let [eng (inv/engine {:storage st :resources [product postings/posting]})
            mug (inv/create! eng :pt_product {:name "mug"} {:principal elena})
            mug-id (get-in mug [:row :id])
            day #(LocalDate/parse %)
            source {:kind :pt_product :id mug-id :action :pay :log-id "log-1"}]
        (store/with-tx st
          (fn [tx]
            (postings/post! eng tx source
                            [{:account-kind :pt_product :account-row mug-id :account-name :paid
                              :amount (usd "18.00") :effective-on (day "2026-10-03")}
                             {:account-kind :pt_product :account-row mug-id :account-name :paid
                              :amount (usd "-2.00") :effective-on (day "2026-10-01")}
                             {:account-kind :house :account-name :processor_in
                              :amount (usd "16.00") :effective-on (day "2026-10-02")}])))
        (let [account (postings/account-address :pt_product mug-id :paid)
              rows (store/with-tx st
                     (fn [tx]
                       (store/search-rows st tx :posting
                                          [{:target :data :field :account :op := :value account}]
                                          {:order-by :effective_on})))]
          (testing "the filter keeps the account's own postings"
            (is (= 2 (count rows))))
          (testing "sorted by effective_on"
            (is (= ["2026-10-01" "2026-10-03"]
                   (mapv #(str (get-in % [:data :effective_on])) rows))))
          (testing "each carries its log row and source"
            (is (every? #(= "log-1" (get-in % [:data :log_id])) rows))
            (is (every? #(= "pt_product" (get-in % [:data :kind])) rows))))
        (testing "no hand at the wire creates one"
          (is (thrown? clojure.lang.ExceptionInfo
                       (inv/create! eng :posting
                                    {:account "house/processor_in"
                                     :account_kind "house"
                                     :account_name "processor_in"
                                     :amount {:amount "1.00" :currency "USD"}
                                     :at "2026-10-01T00:00:00Z"
                                     :effective_on "2026-10-01"
                                     :kind "pt_product"
                                     :source_id mug-id
                                     :action "pay"
                                     :log_id "log-2"}
                                    {:principal elena}))))))))
