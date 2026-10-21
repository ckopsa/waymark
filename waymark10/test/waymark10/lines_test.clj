(ns waymark10.lines-test
  "Ledger step 6a (docs/spec-ledger-money.md §6): a kind that declares
  :lines gets an engine-made line kind with its :owns edge; its create
  and edit doors take `lines` and write the line rows in their own
  transaction; an edit replaces the set whole; a bad line refuses the
  whole door; and no hand writes a line directly. Step 6b: guards and
  posts read the door's lines — (lines), (line :f), and :each in
  :posts. Needs the test database (WAYMARK10_TEST_DSN)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.expr :as expr]
            [waymark10.guards :as g]
            [waymark10.lines :as lines]
            [waymark10.money :as money]
            [waymark10.posts :as posts]
            [waymark10.modules :as modules]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]
            [waymark10.server.invoke :as inv]
            [waymark10.server.postings :as postings]
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
                         "ln_stocks" "ln_sales" "ln_sale_lines" "postings"
                         "ln_priceds" "ln_tills" "ln_till_lines"
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

;; ── ledger step 6c: a line's defaults through follow, :totals in the door ─

(defn- usd [s] (money/money s "USD"))

(def ^:private priced
  (r/resource {:kind :ln_priced
               :plural "ln_priceds"
               :states [:open]
               :initial :open
               :terminal #{}
               :allow-dead #{:open}
               :summary "{data.name} · {state}"
               :schema [:map [:name [:string {:max 40}]] [:price :waymark/money]]}))

(def ^:private till-decl
  {:kind :ln_till
   :plural "ln_tills"
   :states [:open]
   :initial :open
   :terminal #{}
   :allow-dead #{:open}
   :summary "{data.note} · {state}"
   :schema [:map [:note [:string {:max 40}]]]
   :lines {:kind :ln_till_line
           :parent :till
           :fields [[:item {:kind :ln_priced} :waymark/ref]
                    [:qty [:int {:min 1}]]
                    [:unit_price :waymark/money]]
           :defaults {:unit_price '(follow (line :item) :price)}
           :totals {:total '(sum [l (lines)] (* (line :qty) (line :unit_price)))}}
   :actions {:revise {:from #{:open} :to :open
                      :input [:map [:note [:string {:max 40}]]]
                      :edit {:prefill [:note]}
                      :handler (r/field-writer [:note])
                      :safety {:idempotent true :reversible false :confirm false}
                      :display {:label "Revise"}}}})

(def ^:private till (r/resource till-decl))

(def ^:private till-line (lines/line-kind till))

(deftest step-6c-a-total-is-a-read-only-field-and-a-default-names-a-line-field
  (testing "a total is the parent's engine-written field, filterable"
    (is (get-in (schema/entry-map (:schema till)) [:total :properties :engine-written]))
    (is (contains? (:filterable till) :total)))
  (testing "a defaulted line field may be left out of the lines input"
    (is (empty? (schema/closed-errors (:create-schema till)
                                      {:note "x" :lines [{:item "i" :qty 1}]}))))
  (testing "a default for no line field is refused at the def site"
    (is (thrown? clojure.lang.ExceptionInfo
                 (r/resource (assoc-in till-decl [:lines :defaults]
                                       {:nope '(follow (line :item) :price)}))))))

(deftest step-6c-money-sums-as-money
  (is (== 4.00M (:amount (expr/evaluate
                          (expr/normalize '(sum [l (lines)] (* (line :qty) (line :p))))
                          {:lines [{:qty 2 :p (usd "1.25")} {:qty 1 :p (usd "1.50")}]})))))

(deftest step-6c-a-total-is-computed-in-the-door-from-prices-that-came-through-follow
  (fresh!)
  (with-st
    (fn [st]
      (let [eng (inv/engine {:storage st :resources [priced till till-line]})
            mint #(str (get-in (inv/create! eng :ln_priced {:name %1 :price (usd %2)}
                                            {:principal elena})
                               [:row :id]))
            mug (mint "mug" "4.50")
            tea (mint "tea" "3.00")
            total #(some-> (get-in % [:row :data :total]) :amount)
            res (inv/create! eng :ln_till
                             {:note "first"
                              :lines [{:item mug :qty 2}
                                      {:item tea :qty 1 :unit_price (usd "2.50")}]}
                             {:principal elena})
            tid (get-in res [:row :id])]
        (testing "a line without a price takes its product's, and the total sums the lines"
          (is (== 11.50M (total res))))
        (testing "after an edit the total is the sum of the new lines"
          (is (== 12.00M (total (inv/invoke! eng :ln_till tid :revise
                                             {:note "second" :lines [{:item tea :qty 4}]}
                                             {:principal elena :if-match "1"})))))
        (testing "an edit that names no lines keeps the stored total"
          (is (== 12.00M (total (inv/invoke! eng :ln_till tid :revise {:note "third"}
                                             {:principal elena :if-match "2"})))))
        (testing "a write to a total is refused"
          (is (thrown? clojure.lang.ExceptionInfo
                       (inv/create! eng :ln_till {:note "hand" :total (usd "1.00")}
                                    {:principal elena}))))))))

;; ── ledger step 6b: (lines), (line :f), and :each in :posts ─────────────

(deftest step-6b-lines-and-line-in-the-law
  (let [ls [{:qty 2} {:qty 3}]]
    (testing "(lines) reads the scope's lines, and a delay only when asked"
      (is (= 2 (expr/evaluate (expr/normalize '(count (lines))) {:lines ls})))
      (is (= 2 (expr/evaluate (expr/normalize '(count (lines))) {:lines (delay ls)}))))
    (testing "(line :f) reads the line a sum over (lines) binds"
      (is (= 5 (expr/evaluate (expr/normalize '(sum [l (lines)] (line :qty))) {:lines ls}))))
    (testing "no lines to read is unknown, as on the probe"
      (is (expr/unknown? (expr/evaluate '(lines) {}))))
    (testing "(line :f) with no line bound is refused at the def site"
      (is (seq (expr/problems '(< 0 (line :qty)))))
      (is (empty? (expr/problems '(< 0 (line :qty)) 1))))
    (testing ":each normalizes and repeats its entry per line"
      (let [ps (posts/normalize :k :d '[{:each (lines) :account [:k (line :k) :a]
                                         :amount (line :qty) :sign :-}])]
        (is (= ps (posts/normalize :k :d ps)))
        (is (= [{:account-kind :k :account-row "x" :account-name :a :amount -2}
                {:account-kind :k :account-row "y" :account-name :a :amount -3}]
               (posts/entries ps {:lines [{:k "x" :qty 2} {:k "y" :qty 3}]})))))))

(def ^:private stock
  (r/resource {:kind :ln_stock
               :plural "ln_stocks"
               :states [:open]
               :initial :open
               :terminal #{}
               :allow-dead #{:open}
               :summary "{data.name} · {state}"
               :schema [:map [:name [:string {:max 40}]]]
               :accounts {:sold {:unit :count}}}))

(def ^:private sale
  (r/resource {:kind :ln_sale
               :plural "ln_sales"
               :states [:open :placed]
               :initial :open
               :terminal #{}
               :summary "{data.note} · {state}"
               :schema [:map [:note [:string {:max 40}]]]
               :lines {:kind :ln_sale_line
                       :parent :sale
                       :fields [[:stock {:kind :ln_stock} :waymark/ref]
                                [:qty [:int {:min 1}]]]}
               :actions {:place {:from #{:open} :to :placed :undo :unplace
                                 :guards [(g/expr {:name :has-lines
                                                   :severity :refuse
                                                   :explain "An order needs at least one line."
                                                   :when '(< 0 (count (lines)))})]
                                 :posts '[{:each (lines)
                                           :account [:ln_stock (line :stock) :sold]
                                           :amount (line :qty) :sign :+}]
                                 :safety {:idempotent false :reversible true :confirm false}
                                 :display {:label "Place"}}
                         :unplace {:from #{:placed} :to :open :undo :place
                                   :safety {:idempotent false :reversible true :confirm false}
                                   :display {:label "Unplace"}}}}))

(def ^:private sale-line (lines/line-kind sale))

(deftest step-6b-an-order-of-two-lines-posts-two-postings-and-undo-reverses-both
  (fresh!)
  (with-st
    (fn [st]
      (let [eng (inv/engine {:storage st :resources [stock sale sale-line postings/posting]})
            mint #(str (get-in (inv/create! eng :ln_stock {:name %} {:principal elena})
                               [:row :id]))
            mug (mint "mug")
            tea (mint "tea")
            sold #(get-in (store/with-tx st (fn [tx] (store/load-row st tx :ln_stock % {})))
                          [:data :sold])
            postings-of #(store/with-tx st (fn [tx] (store/search-rows st tx :posting [] {})))
            as-elena (fn [k] {:principal elena :idempotency-key k})
            sid (get-in (inv/create! eng :ln_sale
                                     {:note "two" :lines [{:stock mug :qty 2} {:stock tea :qty 1}]}
                                     {:principal elena})
                        [:row :id])
            none (get-in (inv/create! eng :ln_sale {:note "none"} {:principal elena})
                         [:row :id])]
        (testing "a guard over (lines) refuses an empty order"
          (is (thrown? clojure.lang.ExceptionInfo
                       (inv/invoke! eng :ln_sale none :place nil (as-elena "place-0"))))
          (is (empty? (postings-of))))
        (testing "a door with :each posts one posting per line, each the line's amount, atomically"
          (inv/invoke! eng :ln_sale sid :place nil (as-elena "place-1"))
          (let [ps (postings-of)]
            (is (= 2 (count ps)))
            (is (= 1 (count (set (map #(get-in % [:data :log_id]) ps)))))
            (is (== 2 (sold mug)))
            (is (== 1 (sold tea)))))
        (testing "undo reverses them all"
          (inv/invoke! eng :ln_sale sid :unplace nil (as-elena "unplace-1"))
          (let [ps (postings-of)]
            (is (= 4 (count ps)))
            (is (= 2 (count (filter #(get-in % [:data :reverses]) ps)))))
          (is (== 0 (sold mug)))
          (is (== 0 (sold tea))))))))
