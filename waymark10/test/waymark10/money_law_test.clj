(ns waymark10.money-law-test
  "Ledger step 10b (docs/spec-ledger-money.md §5, §10): the money law's
  load errors. A kind :conserves the accounts its doors keep in sum,
  and a door that posts to one side only is [unbalanced-door]; money of
  two declared currencies meeting is [mixed-currency]; an account's row
  read by a field no index serves is [posts-unfiltered]. The §5 shop —
  fire_batch, place, ship, restock_return — loads balanced. No
  database."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.checks-assembly :as ca]
            [waymark10.fingerprint :as fp]
            [waymark10.guards :as g]
            [waymark10.lines :as lines]
            [waymark10.resource :as r]))

(def ^:private safety {:idempotent false :reversible false :confirm false})

(def ^:private one-way (assoc safety :one-way "The test's last word."))

(defn- product-decl [& {:as overrides}]
  (merge {:kind :ml_product
          :plural "ml_products"
          :states [:open]
          :initial :open
          :terminal #{}
          :allow-dead #{:open}
          :summary "{data.name} · {state}"
          :schema [:map [:name [:string {:max 40}]]]
          :accounts {:on_hand {:unit :count :never-below 0}
                     :reserved {:unit :count :never-below 0}
                     :sold {:unit :count :never-below 0}}
          :conserves [:on_hand :reserved :sold]
          :actions {:restock_return
                    {:from #{:open} :to :open
                     :input [:map [:qty [:int {:min 1}]]]
                     :posts '[{:move (input :qty)
                               :from [:ml_product (self) :sold]
                               :to [:ml_product (self) :on_hand]}]
                     :safety safety
                     :display {:label "Restock a return"}}}}
         overrides))

(defn- batch-decl [& {:as overrides}]
  (merge {:kind :ml_batch
          :plural "ml_batches"
          :states [:firing :fired]
          :initial :firing
          :terminal #{:fired}
          :summary "{data.pieces} pieces · {state}"
          :schema [:map
                   [:product {:kind :ml_product} :waymark/ref]
                   [:pieces [:int {:min 0}]]]
          :filterable {:product #{:eq}}
          :actions {:fire_batch
                    {:from #{:firing} :to :fired
                     :posts '[{:move (data :pieces)
                               :from [:house :made]
                               :to [:ml_product (data :product) :on_hand]}]
                     :safety one-way
                     :display {:label "Fire"}}}}
         overrides))

(def ^:private order
  (r/resource
   {:kind :ml_order
    :plural "ml_orders"
    :states [:cart :placed :shipped]
    :initial :cart
    :terminal #{:shipped}
    :summary "{data.note} · {state}"
    :schema [:map [:note [:string {:max 40}]]]
    :lines {:kind :ml_order_line
            :parent :order
            :fields [[:product {:kind :ml_product} :waymark/ref]
                     [:qty [:int {:min 1}]]]}
    :actions {:place {:from #{:cart} :to :placed
                      :posts '[{:each (lines) :move (line :qty)
                                :from [:ml_product (line :product) :on_hand]
                                :to [:ml_product (line :product) :reserved]}]
                      :safety one-way
                      :display {:label "Place"}}
              :ship {:from #{:placed} :to :shipped
                     :posts '[{:each (lines) :move (line :qty)
                               :from [:ml_product (line :product) :reserved]
                               :to [:ml_product (line :product) :sold]}]
                     :safety one-way
                     :display {:label "Ship"}}}}))

(defn- shop [& {:as kinds}]
  {:kinds (merge {:ml_product (r/resource (product-decl))
                  :ml_batch (r/resource (batch-decl))
                  :ml_order order
                  :ml_order_line (lines/line-kind order)}
                 kinds)})

(defn- refusal
  "The assembly gate's sentence for `reg`, or nil when it loads."
  [reg]
  (try (ca/run-all reg) nil
       (catch Exception e (ex-message e))))

(def ^:private restock-from-nowhere
  {:restock {:from #{:open} :to :open
             :input [:map [:qty [:int {:min 1}]]]
             :posts '[{:account [:ml_product (self) :on_hand]
                       :amount (input :qty) :sign :+}]
             :safety safety
             :display {:label "Restock"}}})

(deftest the-shop-loads-balanced
  (testing "fire_batch from the house's made, then place, ship and restock_return as moves"
    (is (nil? (refusal (shop))))))

(deftest a-door-that-posts-to-one-side-only-is-unbalanced
  (testing "a restock that adds to on_hand from nowhere"
    (let [msg (str (refusal (shop :ml_product (r/resource (product-decl :actions restock-from-nowhere)))))]
      (is (re-find #"ml_product \[unbalanced-door\] restock posts to ml_product\.on_hand on one side only" msg))
      (is (re-find #"make it a :move, or post from a house account" msg))))
  (testing "a door of another kind that fires into on_hand with no house account"
    (is (re-find #"ml_batch \[unbalanced-door\] fire_batch"
                 (str (refusal (shop :ml_batch
                                     (r/resource
                                      (batch-decl
                                       :actions {:fire_batch
                                                 {:from #{:firing} :to :fired
                                                  :posts '[{:account [:ml_product (data :product) :on_hand]
                                                            :amount (data :pieces) :sign :+}]
                                                  :safety one-way
                                                  :display {:label "Fire"}}}))))))))
  (testing "a kind that conserves nothing keeps no sum"
    (is (nil? (refusal (shop :ml_product (r/resource (product-decl :conserves nil
                                                                   :actions restock-from-nowhere))))))))

(deftest conserves-names-accounts-of-its-kind
  (is (thrown-with-msg? Exception #":conserves names stock, which is not an account of this kind"
                        (r/resource (product-decl :conserves [:on_hand :stock]))))
  (is (thrown-with-msg? Exception #":conserves is a vector of the accounts"
                        (r/resource (product-decl :conserves :on_hand)))))

(deftest an-account-row-read-by-an-unindexed-field-is-unfiltered
  (testing "(data :product) on a kind that does not filter by product"
    (is (re-find #"ml_batch \[posts-unfiltered\] fire_batch posts to ml_product\.on_hand .* read by product"
                 (str (refusal (shop :ml_batch (r/resource (batch-decl :filterable {:pieces #{:range}})))))))))

(defn- till-decl [& {:as overrides}]
  (merge {:kind :ml_till
          :plural "ml_tills"
          :states [:open]
          :initial :open
          :terminal #{}
          :allow-dead #{:open}
          :summary "{data.name} · {state}"
          :schema [:map
                   [:name [:string {:max 40}]]
                   [:price [:waymark/money {:currency "USD"}]]]
          :accounts {:cash {:unit [:money "USD"]}}}
         overrides))

(defn- till-door [door]
  (refusal {:kinds {:ml_till (r/resource (till-decl :actions {:ring (merge {:from #{:open} :to :open
                                                                            :safety safety
                                                                            :display {:label "Ring"}}
                                                                           door)}))}}))

(deftest money-of-two-currencies-meeting-is-mixed
  (testing "a guard that compares a USD field with a EUR literal"
    (let [msg (str (till-door {:guards [(g/expr {:name :under-five
                                                 :severity :refuse
                                                 :explain "Only a small price rings."
                                                 :when '(< (data :price) (money "5.00" "EUR"))})]}))]
      (is (re-find #"ml_till \[mixed-currency\] guard " msg))
      (is (re-find #"meets USD and EUR" msg))
      (is (re-find #"convert explicitly, or keep the sides apart" msg))))
  (testing "the same guard in one currency loads"
    (is (nil? (till-door {:guards [(g/expr {:name :under-five
                                            :severity :refuse
                                            :explain "Only a small price rings."
                                            :when '(< (data :price) (money "5.00" "USD"))})]}))))
  (testing "a posting that adds EUR to a USD field"
    (is (re-find #"ml_till \[mixed-currency\] ring :posts: .* meets USD and EUR"
                 (str (till-door {:posts '[{:account [:ml_till (self) :cash]
                                            :amount (+ (data :price) (money "1.00" "EUR")) :sign :+}]})))))
  (testing "a posting of EUR into a USD account"
    (is (re-find #"ml_till \[mixed-currency\] ring posts EUR into ml_till\.cash, an account of USD"
                 (str (till-door {:posts '[{:account [:ml_till (self) :cash]
                                            :amount (money "1.00" "EUR") :sign :+}]}))))))

(defn- hash-of [rmap]
  (fp/fingerprint-hash (r/fingerprint (r/resource rmap))))

(deftest conserves-is-law
  (is (= ["on_hand" "reserved" "sold"]
         (get (r/fingerprint (r/resource (product-decl))) "conserves")))
  (testing "which accounts are kept is law"
    (is (not= (hash-of (product-decl))
              (hash-of (product-decl :conserves [:on_hand :reserved])))))
  (testing "a kind that conserves nothing carries no facet, so no existing hash moves"
    (is (not (contains? (r/fingerprint (r/resource (product-decl :conserves nil))) "conserves")))))
