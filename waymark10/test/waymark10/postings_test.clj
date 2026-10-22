(ns waymark10.postings-test
  "Ledger step 3b (docs/spec-ledger-money.md §3-§4): postings are rows
  of the engine-served, read-only kind `posting`. post! writes them
  inside a given transaction; a query filters them by account and sorts
  them by effective_on; and no door creates or edits one. Needs the
  test database (WAYMARK10_TEST_DSN)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.bounds :as bounds]
            [waymark10.expr :as expr]
            [waymark10.guards :as g]
            [waymark10.modules :as modules]
            [waymark10.money :as money]
            [waymark10.posts :as posts]
            [waymark10.resource :as r]
            [waymark10.scenario :as scenario]
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
               :accounts {:paid {:unit [:money "USD"]}}
               ;; a door whose handler writes the balance by hand
               :actions {:fudge {:from #{:open} :to :open
                                 :handler (fn [row _inp _ctx]
                                            (assoc-in row [:data :paid]
                                                      (money/money "99.00" "USD")))
                                 :safety {:idempotent false :reversible false :confirm false}
                                 :display {:label "Fudge"}}}}))

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
          (doseq [table ["postings" "pt_products" "pt_tills" "pt_mugs" "pt_orders"
                         "waymark10_transitions"
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

(defn- paid
  "The row's paid balance as stored: its amount."
  [st id]
  (:amount (money/from-wire
            (get-in (store/with-tx st (fn [tx] (store/load-row st tx :pt_product id {})))
                    [:data :paid])
            nil)))

(deftest step-3c-postings-move-the-balance-and-no-door-writes-it
  (fresh!)
  (with-st
    (fn [st]
      (let [eng (inv/engine {:storage st :resources [product postings/posting]})
            mug-id (get-in (inv/create! eng :pt_product {:name "mug"} {:principal elena})
                           [:row :id])
            pay! (fn [log-id & amounts]
                   (store/with-tx st
                     (fn [tx]
                       (postings/post! eng tx {:kind :pt_product :id mug-id :action :pay
                                               :log-id log-id}
                                       (vec (for [a amounts]
                                              {:account-kind :pt_product :account-row mug-id
                                               :account-name :paid :amount (usd a)}))))))]
        (testing "the field filters and sorts like any field"
          (is (contains? (:filterable product) :paid))
          (is (some #{:paid} (get-in product [:sortable :fields]))))
        (testing "a row is born with a zero balance"
          (is (== 0 (paid st mug-id))))
        (testing "postings move the field"
          (pay! "log-1" "18.00" "-2.00")
          (is (== 16 (paid st mug-id)))
          (pay! "log-2" "4.50")
          (is (== 20.5M (paid st mug-id))))
        (testing "a recompute from the posting rows equals the field"
          (is (== (paid st mug-id)
                  (:amount (store/with-tx st
                             (fn [tx]
                               (postings/recomputed-balance eng tx :pt_product mug-id :paid)))))))
        (testing "no hand writes it at birth"
          (is (thrown? clojure.lang.ExceptionInfo
                       (inv/create! eng :pt_product
                                    {:name "cup" :paid {:amount "5.00" :currency "USD"}}
                                    {:principal elena}))))
        (testing "a door that writes it is refused, and the balance stands"
          (is (thrown? clojure.lang.ExceptionInfo
                       (inv/invoke! eng :pt_product mug-id :fudge nil {:principal elena})))
          (is (== 20.5M (paid st mug-id))))))))

;; ── ledger step 4: :posts ────────────────────────────────────────────────

(def ^:private till
  (r/resource {:kind :pt_till
               :plural "pt_tills"
               :states [:open :paid]
               :initial :open
               :terminal #{}
               :summary "{data.name} · {state}"
               :schema [:map [:name [:string {:max 40}]]
                        [:tip_to {:optional true} [:maybe [:string {:max 80}]]]]
               :accounts {:cash {:unit [:money "USD"]} :owed {:unit [:money "USD"]}}
               :actions {:pay {:from #{:open} :to :paid :undo :unpay
                               :input [:map [:tip {:optional true} :boolean]]
                               :posts '[{:move (money "18.00" "USD")
                                         :from [:pt_till (self) :owed]
                                         :to [:pt_till (self) :cash]}
                                        {:account [:pt_product (data :tip_to) :paid]
                                         :amount (money "2.00" "USD") :sign :+
                                         :when (= (input :tip) true)}]
                               :safety {:idempotent false :reversible true :confirm false}
                               :display {:label "Pay"}}
                         :unpay {:from #{:paid} :to :open :undo :pay
                                 :safety {:idempotent false :reversible true :confirm false}
                                 :display {:label "Unpay"}}
                         :overcharge {:from #{:open} :to :open
                                      :posts '[{:account [:pt_till (self) :cash]
                                                :amount (money "-1.00" "USD") :sign :+}]
                                      :safety {:idempotent false :reversible false :confirm false}
                                      :display {:label "Overcharge"}}}}))

(defn- balance [st kind id field]
  (:amount (money/from-wire
            (get-in (store/with-tx st (fn [tx] (store/load-row st tx kind id {})))
                    [:data field])
            nil)))

(defn- postings-of [st]
  (store/with-tx st (fn [tx] (store/search-rows st tx :posting [] {}))))

(deftest step-9a-a-posting-is-dated-the-house-day-or-its-entry-on
  (testing "a commit at 23:30 in a UTC-7 house is dated the house's day"
    (fresh!)
    (with-st
      (fn [st]
        (let [eng (inv/engine {:storage st :resources [product postings/posting]})
              mug-id (get-in (inv/create! eng :pt_product {:name "mug"} {:principal elena})
                             [:row :id])]
          (store/with-tx st
            (fn [tx]
              (postings/post! eng tx {:kind :pt_product :id mug-id :action :pay :log-id "log-9a"
                                      :at (java.time.Instant/parse "2026-10-03T06:30:00Z")
                                      :zone (java.time.ZoneOffset/ofHours -7)}
                              [{:account-kind :house :account-name :processor_in
                                :amount (usd "1.00")}])))
          (is (= ["2026-10-02"]
                 (->> (postings-of st)
                      (filter #(= "log-9a" (get-in % [:data :log_id])))
                      (mapv #(str (get-in % [:data :effective_on]))))))))))
  (let [ps (posts/normalize :k :d '[{:account [:house :sales] :amount 1 :sign :+
                                     :on (data :placed_on)}])]
    (testing ":on is stored as written"
      (is (= [(LocalDate/parse "2026-09-30")]
             (mapv :effective-on
                   (posts/entries ps {:data {:placed_on (LocalDate/parse "2026-09-30")}})))))
    (testing "a non-date :on refuses the entry"
      (is (:posts-refused
           (ex-data (try (posts/entries ps {:data {:placed_on "soon"}})
                         (catch clojure.lang.ExceptionInfo e e)))))))
  (testing "only laws using :on change fingerprint"
    (let [fp (r/fingerprint till)
          dated (r/fingerprint
                 (assoc-in till [:actions :overcharge :posts]
                           (posts/normalize :pt_till :overcharge
                                            '[{:account [:pt_till (self) :cash]
                                               :amount (money "-1.00" "USD") :sign :+
                                               :on (data :placed_on)}])))]
      (is (not-any? #(contains? % "posts_on") (vals (get-in fp ["machine" "actions"]))))
      (is (contains? (get-in dated ["machine" "actions" "overcharge"]) "posts_on"))
      (is (= (dissoc (get-in fp ["machine" "actions"]) "overcharge")
             (dissoc (get-in dated ["machine" "actions"]) "overcharge"))))))

(deftest step-4-posts-normalize-at-the-def-site
  (testing ":move is the two entries it means"
    (is (= (vec (take 2 (get-in till [:actions :pay :posts])))
           (posts/normalize :pt_till :pay
                            '[{:account [:pt_till (self) :owed] :amount (money "18" "USD") :sign :-}
                              {:account [:pt_till (self) :cash] :amount (money "18.00" "USD") :sign :+}]))))
  (testing "normalizing twice is normalizing once"
    (let [ps (get-in till [:actions :pay :posts])]
      (is (= ps (posts/normalize :pt_till :pay ps)))))
  (testing "a bad sign, an unknown key and a line read outside :each refuse at the def site"
    (is (thrown? clojure.lang.ExceptionInfo
                 (posts/normalize :k :d '[{:account [:k (self) :a] :amount 1 :sign :up}])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (posts/normalize :k :d '[{:account [:k (self) :a] :amount 1 :sign :+ :memo "x"}])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (posts/normalize :k :d '[{:account [:k (self) :a] :amount (line :qty) :sign :+}]))))
  (testing "the undo of a posting door knows the door it reverses"
    (is (= #{:pay} (get-in till [:actions :unpay :reverses-posts])))
    (is (nil? (get-in till [:actions :pay :reverses-posts])))))

(deftest step-4-a-door-posts-with-its-log-row-and-undo-reverses-it
  (fresh!)
  (with-st
    (fn [st]
      (let [eng (inv/engine {:storage st :resources [product till postings/posting]})
            mug-id (get-in (inv/create! eng :pt_product {:name "mug"} {:principal elena})
                           [:row :id])
            till-id (get-in (inv/create! eng :pt_till {:name "t1" :tip_to (str mug-id)}
                                         {:principal elena})
                            [:row :id])
            as-elena (fn [k] {:principal elena :idempotency-key k})]
        (testing "the postings, the row and the log row land together"
          (inv/invoke! eng :pt_till till-id :pay {:tip true} (as-elena "pay-1"))
          (let [ps (postings-of st)
                [t] (store/with-tx st
                      (fn [tx] (store/transitions st tx {:kind :pt_till :resource-id till-id}
                                                  {:newest-first true :limit 1})))]
            (is (= 3 (count ps)))
            (is (every? #(= (str (:id t)) (get-in % [:data :log_id])) ps))
            (is (== 18 (balance st :pt_till till-id :cash)))
            (is (== -18 (balance st :pt_till till-id :owed)))
            (is (== 2 (balance st :pt_product mug-id :paid)))))
        (testing "a retried invoke never posts twice"
          (inv/invoke! eng :pt_till till-id :pay {:tip true} (as-elena "pay-1"))
          (is (= 3 (count (postings-of st)))))
        (testing "undo writes the reversing entries, each naming its posting"
          (inv/invoke! eng :pt_till till-id :unpay nil (as-elena "unpay-1"))
          (let [ps (postings-of st)
                ids (set (map (comp str :id) ps))
                rev (filter #(get-in % [:data :reverses]) ps)]
            (is (= 6 (count ps)))
            (is (= 3 (count rev)))
            (is (every? #(contains? ids (get-in % [:data :reverses])) rev))
            (is (== 0 (balance st :pt_till till-id :cash)))
            (is (== 0 (balance st :pt_till till-id :owed)))
            (is (== 0 (balance st :pt_product mug-id :paid)))
            (is (== 0 (:amount (store/with-tx st
                                 (fn [tx]
                                   (postings/recomputed-balance eng tx :pt_till till-id :cash))))))))
        (testing "an entry whose :when does not hold posts nothing"
          (inv/invoke! eng :pt_till till-id :pay {} (as-elena "pay-2"))
          (is (= 8 (count (postings-of st))))
          (is (== 0 (balance st :pt_product mug-id :paid))))
        (testing "a negative amount refuses the door, and nothing posts"
          (inv/invoke! eng :pt_till till-id :unpay nil (as-elena "unpay-2"))
          (is (= 10 (count (postings-of st))))
          (is (thrown? clojure.lang.ExceptionInfo
                       (inv/invoke! eng :pt_till till-id :overcharge nil (as-elena "over-1"))))
          (is (= 10 (count (postings-of st))))
          (is (= "open" (name (:state (store/with-tx st
                                        (fn [tx] (store/load-row st tx :pt_till till-id {})))))))
          (is (== 0 (balance st :pt_till till-id :cash))))))))

;; ── ledger step 5: (balance …) and the bounds ────────────────────────────

(def ^:private mug-kind
  (r/resource {:kind :pt_mug
               :plural "pt_mugs"
               :states [:open]
               :initial :open
               :terminal #{}
               :allow-dead #{:open}
               :summary "{data.name} · {state}"
               :schema [:map [:name [:string {:max 40}]]]
               :accounts {:on_hand {:unit :count :never-below 0}}
               :actions {:restock {:from #{:open} :to :open
                                   :input [:map [:qty [:int {:min 1}]]]
                                   :posts '[{:account [:pt_mug (self) :on_hand]
                                             :amount (input :qty) :sign :+}]
                                   :safety {:idempotent false :reversible false :confirm false}
                                   :display {:label "Restock"}}}}))

(def ^:private order-kind
  (r/resource {:kind :pt_order
               :plural "pt_orders"
               :states [:cart :placed]
               :initial :cart
               :terminal #{:placed}
               :summary "{data.name} · {state}"
               :schema [:map [:name [:string {:max 40}]]]
               :actions {:place {:from #{:cart} :to :placed
                                 :input [:map [:product [:string {:max 80}]] [:qty [:int {:min 1}]]]
                                 :posts '[{:account [:pt_mug (input :product) :on_hand]
                                           :amount (input :qty) :sign :-}]
                                 :safety {:idempotent false :reversible false :confirm false
                                          :one-way "A placed order is the test's last word."}
                                 :display {:label "Place"}}}}))

(def ^:private shop
  (bounds/derive-kinds {:pt_mug mug-kind :pt_order order-kind}))

(deftest step-5-a-bound-writes-its-guard-where-a-door-can-break-it
  (let [named #(some (fn [g] (when (= :pt_mug.on_hand-never-below (:name g)) g)) %)
        g (named (get-in shop [:pt_order :actions :place :guards]))]
    (testing "the door that takes from on_hand gains the guard, as a tree"
      (is (some? g))
      (is (= (expr/normalize '(>= (- (balance [:pt_mug (input :product) :on_hand]) (input :qty)) 0))
             (:when g)))
      (is (= [:product :qty] (:judges g))))
    (testing "a door that only adds gains none"
      (is (nil? (named (get-in shop [:pt_mug :actions :restock :guards])))))
    (testing "deriving twice is deriving once"
      (is (= shop (bounds/derive-kinds shop))))
    (testing "(balance …) is unknown with no hook to ask, as on the probe"
      (is (expr/unknown? (expr/evaluate (:when g) {:input {:product "m1" :qty 1}}))))))

(deftest step-5-two-orders-for-the-last-mug-one-is-refused
  (fresh!)
  (with-st
    (fn [st]
      (let [eng (inv/engine {:storage st :resources (conj (vec (vals shop)) postings/posting)})
            mug-id (str (get-in (inv/create! eng :pt_mug {:name "speckled"} {:principal elena})
                                [:row :id]))
            order! #(str (get-in (inv/create! eng :pt_order {:name %} {:principal elena})
                                 [:row :id]))
            on-hand #(get-in (store/with-tx st (fn [tx] (store/load-row st tx :pt_mug mug-id {})))
                             [:data :on_hand])
            place! (fn [oid k qty]
                     (try (inv/invoke! eng :pt_order oid :place {:product mug-id :qty qty}
                                       {:principal elena :idempotency-key k})
                          :placed
                          (catch clojure.lang.ExceptionInfo e e)))]
        (inv/invoke! eng :pt_mug mug-id :restock {:qty 1}
                     {:principal elena :idempotency-key "restock-1"})
        (is (== 1 (on-hand)))
        (testing "two orders at once for the last mug: one lands, one is refused"
          (let [a (order! "a")
                b (order! "b")
                gate (java.util.concurrent.CountDownLatch. 1)
                runs [(future (.await gate) (place! a "place-a" 1))
                      (future (.await gate) (place! b "place-b" 1))]
                _ (.countDown gate)
                outcomes (mapv deref runs)]
            (is (= 1 (count (filter #{:placed} outcomes))))
            (is (== 0 (on-hand)))
            (is (= 2 (count (postings-of st))))
            (testing "and the refusal names the account and the gap"
              (is (re-find #"Only 0 on hand on that pt mug; this pt order takes 1\."
                           (pr-str (ex-data (first (remove #{:placed} outcomes)))))))))
        (testing "an order larger than the stock is refused, and nothing posts"
          (inv/invoke! eng :pt_mug mug-id :restock {:qty 2}
                       {:principal elena :idempotency-key "restock-2"})
          (let [refused (place! (order! "c") "place-c" 3)]
            (is (instance? clojure.lang.ExceptionInfo refused))
            (is (re-find #"Only 2 on hand on that pt mug; this pt order takes 3\."
                         (pr-str (ex-data refused))))
            (is (== 2 (on-hand)))))))))

;; ── ledger step 8: scenarios state postings, rates and balances ─────────

(defn- judged
  "One scenario over `rdef`, judged at the check tier."
  [rdef m]
  (scenario/judge rdef (scenario/scenario :pt-ledger "The ledger keeps its word." m)))

(deftest step-8-a-scenario-stands-on-the-postings-it-states
  (let [order (:pt_order shop)
        base {:kind :pt_order :attempt :place :row {:state :cart :data {:name "a"}}
              :postings [[[:pt_mug "m1" :on_hand] 3 "2026-10-01"]
                         [[:pt_mug "m1" :on_hand] -2 "2026-10-02"]]}]
    (testing "it is judged at the check tier, with no storage"
      (is (scenario/check-tier? order (scenario/scenario :pt-ledger "x"
                                                         (assoc base :input {:product "m1" :qty 1}
                                                                :expect {:allowed true})))))
    (testing "the last mug: an order for two is refused by the bound"
      (is (= [] (judged order (assoc base :input {:product "m1" :qty 2}
                                     :expect {:refused :pt_mug.on_hand-never-below
                                              :because "Only 1 on hand"})))))
    (testing "an order for one lands, and leaves the balance expected"
      (is (= [] (judged order (assoc base :input {:product "m1" :qty 1}
                                     :expect {:allowed true :balances {[:pt_mug "m1" :on_hand] 0}})))))
    (testing "a balance that differs says what it holds"
      (is (re-find #"The ledger keeps its word\. — the balances answer \[:pt_mug \"m1\" :on_hand\] holds 0, not 1"
                   (str (first (judged order (assoc base :input {:product "m1" :qty 1}
                                                    :expect {:allowed true
                                                             :balances {[:pt_mug "m1" :on_hand] 1}})))))))
    (testing "a balance-reading law with no :postings is refused, not defaulted to zero"
      (is (re-find #"states no :postings"
                   (str (first (judged order (-> base (dissoc :postings)
                                                 (assoc :input {:product "m1" :qty 1}
                                                        :expect {:allowed true}))))))))
    (testing "an expected balance is one the postings state"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"which :postings does not state"
                            (scenario/scenario :pt-ledger "x"
                                               (assoc base :input {:product "m1" :qty 1}
                                                      :expect {:allowed true
                                                               :balances {[:pt_mug "m2" :on_hand] 0}})))))
    (testing "a float is not an amount"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is a whole number"
                            (scenario/scenario :pt-ledger "x"
                                               (assoc base :postings [[[:pt_mug "m1" :on_hand] 0.5 "2026-10-01"]]
                                                      :input {:product "m1" :qty 1}
                                                      :expect {:allowed true})))))))

(def ^:private sale-kind
  (r/resource {:kind :pt_sale
               :plural "pt_sales"
               :states [:open :closed]
               :initial :open
               :terminal #{:closed}
               :summary "{data.name} · {state}"
               :schema [:map [:name [:string {:max 40}]] [:region [:string {:max 8}]]
                        [:placed_on :waymark/date]]
               :actions {:close {:from #{:open} :to :closed
                                 :guards [(g/expr {:name :pt-tax-under-a-tenth
                                                   :severity :refuse
                                                   :explain "The sales tax there is a tenth or more."
                                                   :when '(< (rate :sales_tax {:region (data :region)}
                                                                   (data :placed_on))
                                                             0.1M)})]
                                 :safety {:idempotent false :reversible false :confirm false
                                          :one-way "A closed sale is the test's last word."}
                                 :display {:label "Close"}}}}))

(deftest step-8-a-scenario-states-the-rates-in-effect
  (let [base {:kind :pt_sale :attempt :close
              :rates {:sales_tax [{:key {:region "UT"} :on "2026-01-01" :value 0.0725M}
                                  {:key {:region "UT"} :on "2026-07-01" :value 0.12M}
                                  {:key {:region "AZ"} :on "2026-01-01" :value 0.5M}]}}
        on (fn [d] {:state :open :data {:name "s" :region "UT" :placed_on d}})]
    (testing "the rate in effect is the latest on or before the date, for the key asked"
      (is (= [] (judged sale-kind (assoc base :row (on "2026-03-01") :expect {:allowed true}))))
      (is (= [] (judged sale-kind (assoc base :row (on "2026-08-01")
                                         :expect {:refused :pt-tax-under-a-tenth})))))
    (testing "a rate-reading law with no :rates is refused"
      (is (re-find #"states no :rates"
                   (str (first (judged sale-kind (-> base (dissoc :rates)
                                                     (assoc :row (on "2026-03-01")
                                                            :expect {:allowed true}))))))))))

(deftest step-8-the-probe-reads-balances-and-rates
  (fresh!)
  (with-st
    (fn [st]
      (let [eng (inv/engine {:storage st :resources (conj (vec (vals shop)) postings/posting)})
            mug-id (str (get-in (inv/create! eng :pt_mug {:name "plain"} {:principal elena})
                                [:row :id]))
            hooks (inv/render-hooks eng)]
        (inv/invoke! eng :pt_mug mug-id :restock {:qty 4}
                     {:principal elena :idempotency-key "probe-restock"})
        (testing "the render hooks answer a balance as the row carries it"
          (is (== 4 ((:balance hooks) :pt_mug mug-id :on_hand))))
        (testing "an account no kind declares is unknown, not zero"
          (is (nil? ((:balance hooks) :pt_mug mug-id :reserved))))
        (testing "so the bound on the order's door is judged on the probe, not pending"
          (is (true? (expr/evaluate '(>= (- (balance [:pt_mug (input :product) :on_hand]) (input :qty)) 0)
                                    {:input {:product mug-id :qty 4} :balance (:balance hooks)})))
          (is (false? (expr/evaluate '(>= (- (balance [:pt_mug (input :product) :on_hand]) (input :qty)) 0)
                                     {:input {:product mug-id :qty 5} :balance (:balance hooks)}))))
        (testing "a rate table nothing declares answers nil"
          (is (nil? ((:rate-of hooks) :sales_tax {:region "UT"} (LocalDate/parse "2026-03-01")))))))))
