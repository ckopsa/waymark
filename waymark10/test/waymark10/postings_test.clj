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
            [waymark10.posts :as posts]
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
          (doseq [table ["postings" "pt_products" "pt_tills" "waymark10_transitions"
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

(deftest step-4-posts-normalize-at-the-def-site
  (testing ":move is the two entries it means"
    (is (= (vec (take 2 (get-in till [:actions :pay :posts])))
           (posts/normalize :pt_till :pay
                            '[{:account [:pt_till (self) :owed] :amount (money "18" "USD") :sign :-}
                              {:account [:pt_till (self) :cash] :amount (money "18.00" "USD") :sign :+}]))))
  (testing "normalizing twice is normalizing once"
    (let [ps (get-in till [:actions :pay :posts])]
      (is (= ps (posts/normalize :pt_till :pay ps)))))
  (testing "a bad sign, an unknown key and :each refuse at the def site"
    (is (thrown? clojure.lang.ExceptionInfo
                 (posts/normalize :k :d '[{:account [:k (self) :a] :amount 1 :sign :up}])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (posts/normalize :k :d '[{:account [:k (self) :a] :amount 1 :sign :+ :memo "x"}])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (posts/normalize :k :d '[{:each (lines) :account [:k (self) :a] :amount 1 :sign :+}]))))
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
