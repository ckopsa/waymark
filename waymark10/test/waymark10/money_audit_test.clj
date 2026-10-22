(ns waymark10.money-audit-test
  "Ledger step 10a (docs/spec-ledger-money.md §10): the audit asks where
  money is typed by hand. [balance-typed] a balance a door types in,
  [money-no-currency] money held as a bare number, [reconcile-typed] a
  guard comparing typed money — each a warning ending in its question,
  over a shop product shaped like the shop rehearsal's."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.guards :as g]
            [waymark10.resource :as r]))

(def ^:private routine {:idempotent true :reversible true :confirm false})

(def ^:private settled
  (g/expr {:name :settled
           :when '(= (data :paid_total) (data :price_cents))
           :explain "An order settles once what was paid meets the price."}))

(defn- product [& {:as overrides}]
  (merge {:kind :audit_product
          :states [:open]
          :initial :open
          :terminal #{}
          :allow-dead #{:open}
          :summary "{data.name} · {state}"
          :schema [:map
                   [:name [:string {:max 40}]]
                   [:on_hand {:optional true} [:maybe [:int {:min 0}]]]
                   [:price_cents {:optional true} [:maybe :int]]
                   [:paid_total {:optional true} [:maybe :int]]]
          :actions {:count_stock {:from #{:open} :to :open
                                  :input [:map
                                          [:on_hand [:int {:min 0}]]
                                          [:paid_total {:optional true} [:maybe :int]]]
                                  :safety routine}
                    :settle {:from #{:open} :to :open
                             :guards [settled]
                             :safety routine}}}
         overrides))

(defn- warnings [rmap]
  (:waymark10/warnings (meta (r/resource rmap))))

(defn- tagged [tag rmap]
  (filterv #(str/starts-with? % (str "[" tag "]")) (warnings rmap)))

(deftest a-typed-balance-is-asked-about
  (let [ws (tagged "balance-typed" (product))]
    (is (some #(str/includes? % "audit_product.on_hand") ws))
    (is (some #(str/includes? % "audit_product.paid_total") ws))
    (is (every? #(str/ends-with? % "and should that be the only way?") ws)))
  (testing "a field no door types in is quiet"
    (is (empty? (tagged "balance-typed"
                        (product :create-schema [:map [:name [:string {:max 40}]]]
                                 :actions {:settle {:from #{:open} :to :open
                                                    :safety routine}})))))
  (testing "a declared account does not trip it"
    (is (empty? (tagged "balance-typed"
                        (product :schema [:map [:name [:string {:max 40}]]]
                                 :accounts {:on_hand {:unit :count :never-below 0}}
                                 :actions {:count_stock {:from #{:open} :to :open
                                                         :safety routine}}))))))

(deftest money-with-no-currency-is-asked-about
  (let [ws (tagged "money-no-currency" (product))]
    (is (some #(str/includes? % "audit_product.price_cents") ws))
    (is (some #(str/ends-with? % "Which currency is price_cents in?") ws)))
  (testing "a dollar sign in the label says money"
    (is (some #(str/includes? % "audit_product.charge")
              (tagged "money-no-currency"
                      (product :schema [:map
                                        [:name [:string {:max 40}]]
                                        [:charge {:optional true
                                                  :x-display {:label "Charge ($)"}}
                                         [:maybe :int]]]
                               :actions {})))))
  (testing "a count that is not money, and money that names its currency, are quiet"
    (is (empty? (tagged "money-no-currency"
                        (product :schema [:map
                                          [:name [:string {:max 40}]]
                                          [:qty {:optional true} [:maybe :int]]
                                          [:price {:optional true} [:maybe :waymark/money]]]
                                 :actions {}))))))

(deftest a-guard-over-typed-money-is-asked-about
  (let [ws (tagged "reconcile-typed" (product))]
    (is (= 1 (count ws)))
    (is (str/includes? (first ws) "guard settled on audit_product.settle"))
    (is (str/ends-with? (first ws)
                        "Where do the numbers in audit_product come from before someone types them?")))
  (testing "a guard reading one money field is quiet"
    (is (empty? (tagged "reconcile-typed"
                        (product :actions
                                 {:count_stock {:from #{:open} :to :open
                                                :input [:map [:paid_total {:optional true} [:maybe :int]]]
                                                :safety routine}
                                  :settle {:from #{:open} :to :open
                                           :guards [(g/expr {:name :paid
                                                             :when '(< 0 (data :paid_total))
                                                             :explain "Something was paid."})]
                                           :safety routine}}))))))
