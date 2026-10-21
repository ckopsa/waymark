(ns waymark10.lines-test
  "Ledger step 6a (docs/spec-ledger-money.md §6): a kind that declares
  :lines gets an engine-made line kind with its :owns edge; its create
  and edit doors take `lines` and write the line rows in their own
  transaction; an edit replaces the set whole; a bad line refuses the
  whole door; and no hand writes a line directly. Needs the test
  database (WAYMARK10_TEST_DSN)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.lines :as lines]
            [waymark10.modules :as modules]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

(def ^:private product
  (r/resource {:kind :ln_product
               :plural "ln_products"
               :states [:open]
               :initial :open
               :terminal #{}
               :allow-dead #{:open}
               :summary "{data.name} · {state}"
               :schema [:map [:name [:string {:max 40}]]]}))

(def ^:private order-decl
  {:kind :ln_order
   :plural "ln_orders"
   :states [:open]
   :initial :open
   :terminal #{}
   :allow-dead #{:open}
   :summary "{data.note} · {state}"
   :schema [:map [:note [:string {:max 40}]]]
   :lines {:kind :ln_order_line
           :parent :order
           :fields [[:product {:kind :ln_product} :waymark/ref]
                    [:qty [:int {:min 1}]]]}
   :actions {:revise {:from #{:open} :to :open
                      :input [:map [:note [:string {:max 40}]]]
                      :edit {:prefill [:note]}
                      :handler (r/field-writer [:note])
                      :safety {:idempotent true :reversible false :confirm false}
                      :display {:label "Revise"}}}})

(def ^:private order (r/resource order-decl))

(def ^:private line (lines/line-kind order))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- with-st [f]
  (let [st (pg/storage db/dsn)]
    (try (f st) (finally (pg/close! st)))))

(defn- fresh! []
  (with-st
    (fn [st]
      (store/with-tx st
        (fn [tx]
          (doseq [table ["ln_products" "ln_orders" "ln_order_lines"
                         "waymark10_transitions"
                         "waymark10_idempotency" "waymark10_drafts"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")])))))))

(deftest the-line-kind-is-made-owned-and-enrolled
  (is (= :ln_order_line (:kind line)))
  (testing "it has no doors of its own"
    (is (empty? (:actions line))))
  (testing "the parent owns it through the line's ref back"
    (is (some #(= {:kind :ln_order_line :via :order} (select-keys % [:kind :via]))
              (:owns order))))
  (testing "the ledger module enrols it beside the declaring kind"
    (is (some #(= :ln_order_line (:kind %)) (modules/enrolled [product order] nil)))
    (is (not-any? #(= :ln_order_line (:kind %)) (modules/enrolled [product] nil))))
  (testing "the create and edit doors take lines; the row does not"
    (is (some #{:lines} (map first (rest (:create-schema order)))))
    (is (some #{:lines} (map first (rest (get-in order [:actions :revise :input])))))
    (is (not-any? #{:lines} (map first (rest (:schema order)))))))

(deftest a-lines-declaration-is-judged-at-the-def-site
  (testing "what step 6a does not read is refused, not ignored"
    (is (thrown? clojure.lang.ExceptionInfo
                 (r/resource (assoc-in order-decl [:lines :totals] {:subtotal 1})))))
  (testing "the parent ref and n are the engine's"
    (is (thrown? clojure.lang.ExceptionInfo
                 (r/resource (update-in order-decl [:lines :fields] conj
                                        [:n [:int {:min 0}]]))))))

(deftest an-order-and-its-lines-are-written-in-one-transaction
  (fresh!)
  (with-st
    (fn [st]
      (let [eng (inv/engine {:storage st :resources [product order line]})
            mint #(get-in (inv/create! eng :ln_product {:name %} {:principal elena})
                          [:row :id])
            mug (mint "mug")
            tea (mint "tea")
            rows-of (fn [kind conds & [opts]]
                      (store/with-tx st
                        (fn [tx] (store/search-rows st tx kind conds (or opts {})))))
            lines-of (fn [oid]
                       (mapv (fn [l] [(get-in l [:data :product])
                                      (get-in l [:data :qty])
                                      (get-in l [:data :n])])
                             (rows-of :ln_order_line
                                      [{:target :data :field :order :op := :value oid}]
                                      {:order-by :n})))
            res (inv/create! eng :ln_order
                             {:note "first"
                              :lines [{:product mug :qty 2} {:product tea :qty 1}]}
                             {:principal elena})
            oid (get-in res [:row :id])]
        (testing "the order is written, and lines are not a field of it"
          (is (= "first" (get-in res [:row :data :note])))
          (is (not (contains? (get-in res [:row :data]) :lines))))
        (testing "it reads back with its two lines, in order"
          (is (= [[mug 2 0] [tea 1 1]] (lines-of oid))))
        (testing "a bad line refuses the whole door: no order, no lines"
          (is (thrown? clojure.lang.ExceptionInfo
                       (inv/create! eng :ln_order
                                    {:note "bad"
                                     :lines [{:product mug :qty 1} {:product tea :qty 0}]}
                                    {:principal elena})))
          (is (= 1 (count (rows-of :ln_order []))))
          (is (= 2 (count (rows-of :ln_order_line [])))))
        (testing "an edit replaces the line set whole"
          (inv/invoke! eng :ln_order oid :revise
                       {:note "second" :lines [{:product tea :qty 3}]}
                       {:principal elena :if-match "1"})
          (is (= [[tea 3 0]] (lines-of oid))))
        (testing "an edit that names no lines keeps them"
          (inv/invoke! eng :ln_order oid :revise {:note "third"}
                       {:principal elena :if-match "2"})
          (is (= [[tea 3 0]] (lines-of oid))))
        (testing "a line cannot be written directly"
          (is (thrown? clojure.lang.ExceptionInfo
                       (inv/create! eng :ln_order_line
                                    {:order oid :n 5 :product mug :qty 1}
                                    {:principal elena})))
          (is (= 1 (count (lines-of oid)))))))))
