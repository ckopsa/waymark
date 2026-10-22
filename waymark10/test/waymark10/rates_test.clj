(ns waymark10.rates-test
  "Ledger step 7 (docs/spec-ledger-money.md §8): a kind declares its
  rows a :rate-table, and (rate …) answers the value of the row whose
  key matches and whose effective date is the latest on or before the
  date — unknown, not zero, where no row is. The last test needs the
  test database (WAYMARK10_TEST_DSN)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.checks-assembly :as ca]
            [waymark10.expr :as e]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time LocalDate)))

(def ^:private safety
  {:idempotent true :reversible false :confirm false :one-way "Retired is retired."})

(defn- rate-map [overrides]
  (merge {:kind :rt_tax_rate
          :plural "rt_tax_rates"
          :states [:active :retired]
          :initial :active
          :terminal #{:retired}
          :summary "{data.region} · {state}"
          :schema [:map
                   [:region [:string {:max 2}]]
                   [:rate :decimal]
                   [:effective_on :waymark/date]]
          :filterable {:region #{:eq} :effective_on #{:eq :range}}
          :rate-table {:name :sales_tax :key [:region] :value :rate :effective :effective_on}
          :actions {:retire {:from #{:active} :to :retired :safety safety}}}
         overrides))

(def ^:private tax-rate (r/resource (rate-map {})))

(def ^:private law '(rate :sales_tax {:region (data :ship_region)} (data :placed_on)))

;; ── the form ────────────────────────────────────────────────────────────────────────

(deftest step-7-rate-is-a-form-of-the-law
  (testing "a table, its key and a date; the date may be left out"
    (is (empty? (e/problems law)))
    (is (empty? (e/problems '(rate :sales_tax {:region (data :ship_region)})))))
  (testing "a shape it does not take is refused"
    (is (seq (e/problems '(rate sales_tax {:region (data :r)}))))
    (is (seq (e/problems '(rate :sales_tax {}))))
    (is (seq (e/problems '(rate :sales_tax (data :r))))))
  (testing "a rate with no date is a rate on (today)"
    (is (= (e/normalize '(rate :sales_tax {:region (data :ship_region)} (today)))
           (e/normalize '(rate :sales_tax {:region (data :ship_region)})))))
  (testing "it reads its key and its date, and names the table"
    (let [i (e/info (e/normalize law))]
      (is (= #{:ship_region :placed_on} (:data i)))
      (is (= #{:sales_tax} (:rates i)))))
  (testing "a derivation may not read a rate table's rows"
    (is (seq (e/derived-scope-problems '(rate :sales_tax {:region (var :r)} (var :on)) #{:r :on}))))
  (testing "the wire tree reads back as the same law"
    (let [f (e/normalize law)]
      (is (= f (wire/wire->form (wire/read-json (wire/write-json (wire/form->wire f)))))))))

(deftest step-7-rate-answers-through-its-hook
  (let [f (e/normalize law)
        data {:ship_region "NV" :placed_on "2026-10-21"}
        asked (atom nil)
        hook (fn [table kvs on] (reset! asked [table kvs on]) (when (= "AZ" (:region kvs)) 0.056M))]
    (testing "no hook to ask is unknown, as on the probe"
      (is (e/unknown? (e/evaluate f {:data data}))))
    (testing "the hook is asked for the table, the key and the day"
      (e/evaluate f {:data data :rate hook})
      (is (= [:sales_tax {:region "NV"} (LocalDate/parse "2026-10-21")] @asked)))
    (testing "no row is unknown, not zero, and says which rate is missing"
      (let [v (e/evaluate f {:data data :rate hook})]
        (is (e/unknown? v))
        (is (= "No sales_tax rate for NV on 2026-10-21" (e/unknown-why v)))))
    (testing "a row answers its value"
      (is (= 0.056M (e/evaluate f {:data (assoc data :ship_region "AZ") :rate hook}))))
    (testing "a key or a date that is missing is unknown"
      (is (e/unknown? (e/evaluate f {:data (dissoc data :placed_on) :rate hook})))
      (is (e/unknown? (e/evaluate f {:data (dissoc data :ship_region) :rate hook}))))))

;; ── the declaration ───────────────────────────────────────────────────────────────────

(deftest step-7-a-rate-table-names-fields-of-its-kind
  (testing "a field the kind does not have"
    (is (thrown-with-msg? Exception #"rate-table names state, which is not a field"
                          (r/resource (rate-map {:rate-table {:name :sales_tax :key [:state]
                                                              :value :rate :effective :effective_on}})))))
  (testing "an :effective that is not a date"
    (is (thrown-with-msg? Exception #":effective region is a :waymark/date"
                          (r/resource (rate-map {:rate-table {:name :sales_tax :key [:region]
                                                              :value :rate :effective :region}})))))
  (testing "an entry it does not read"
    (is (thrown-with-msg? Exception #"a rate table reads :name, :key, :value and :effective"
                          (r/resource (rate-map {:rate-table {:name :sales_tax :key [:region] :value :rate
                                                              :effective :effective_on :default 0}})))))
  (testing "[rate-unfiltered]: a key or effective field the kind does not index"
    (is (thrown-with-msg? Exception #"\[rate-unfiltered\]"
                          (ca/run-all {:kinds {:rt_tax_rate (r/resource (rate-map {:filterable {:region #{:eq}}}))}})))
    (is (thrown-with-msg? Exception #"\[rate-unfiltered\]"
                          (ca/run-all {:kinds {:rt_tax_rate (r/resource (rate-map {:filterable {:effective_on #{:eq}}}))}})))))

;; ── the read, over the test database ────────────────────────────────────────────────────

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- with-st [f]
  (let [st (pg/storage db/dsn)]
    (try (f st) (finally (pg/close! st)))))

(deftest step-7-the-rate-in-effect-is-the-latest-on-or-before-the-day
  (with-st
    (fn [st]
      (store/with-tx st
        (fn [tx]
          (doseq [table ["rt_tax_rates" "waymark10_transitions"
                         "waymark10_idempotency" "waymark10_drafts"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))))
  (with-st
    (fn [st]
      (let [eng (inv/engine {:storage st :resources [tax-rate]})
            add! (fn [region rate on]
                   (inv/create! eng :rt_tax_rate {:region region :rate rate :effective_on on}
                                {:principal elena}))
            rate-on (fn [table kvs on]
                      (store/with-tx st
                        (fn [tx] (#'inv/rate-on eng tx table kvs (LocalDate/parse on)))))]
        (add! "NV" 0.0685M "2026-01-01")
        (add! "NV" 0.0725M "2026-07-01")
        (add! "AZ" 0.056M "2026-01-01")
        (testing "the latest effective on or before the day"
          (is (== 0.0685M (rate-on :sales_tax {:region "NV"} "2026-06-30")))
          (is (== 0.0725M (rate-on :sales_tax {:region "NV"} "2026-07-01")))
          (is (== 0.0725M (rate-on :sales_tax {:region "NV"} "2026-10-21"))))
        (testing "the key keeps the regions apart"
          (is (== 0.056M (rate-on :sales_tax {:region "AZ"} "2026-10-21"))))
        (testing "before the first row, for a region nobody entered, or for another table: nothing"
          (is (nil? (rate-on :sales_tax {:region "NV"} "2025-12-31")))
          (is (nil? (rate-on :sales_tax {:region "UT"} "2026-10-21")))
          (is (nil? (rate-on :fee_schedule {:region "NV"} "2026-10-21"))))
        (testing "a key of other fields than the table's is no row"
          (is (nil? (rate-on :sales_tax {:state "NV"} "2026-10-21"))))))))
