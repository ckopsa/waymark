(ns waymark10.batch-c-expr-test
  "Batch C, the earned vocabulary: (count <coll>), (count [d <coll>]
  <pred>) and (sum [d <coll>] <expr>) — validation, normalization
  (binder erasure, idempotence), total evaluation, wire round-trip,
  scope discipline, and a generator property over the new nodes. No
  database."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [waymark10.expr :as expr]
            [waymark10.wire :as wire]))

;; ── validation ──────────────────────────────────────────────────────

(deftest count-sum-validation
  (testing "the three authored spellings are well-formed"
    (is (expr/well-formed? '(count (var :days))))
    (is (expr/well-formed? '(count [d (var :days)] (is-set (get d :meal_id)))))
    (is (expr/well-formed? '(sum [d (var :items)] (get d :qty)))))
  (testing "canonical spellings too"
    (is (expr/well-formed? '(count (var :days) (is-set (get (it 0) :meal_id)))))
    (is (expr/well-formed? '(sum (var :items) (get (it 0) :qty)))))
  (testing "arity is refused by sentence"
    (is (seq (expr/problems '(sum (var :items)))))
    (is (seq (expr/problems '(count))))
    (is (seq (expr/problems '(sum [d (var :items)]))))
    (is (seq (expr/problems '(count [d] (is-set d))))))
  (testing "binder references outside their quantifier refuse"
    (is (seq (expr/problems '(sum (var :items) (get (it 1) :qty)))))))

;; ── normalization ───────────────────────────────────────────────────

(deftest count-sum-normalization
  (testing "binder names erase to de Bruijn"
    (is (= '(count (var :days) (is-set (get (it 0) :meal_id)))
           (expr/normalize '(count [d (var :days)]
                              (is-set (get d :meal_id))))))
    (is (= '(sum (var :items) (get (it 0) :qty))
           (expr/normalize '(sum [x (var :items)] (get x :qty))))))
  (testing "alpha-equivalent quantifiers are the same tree"
    (is (= (expr/normalize '(sum [a (var :items)] (get a :qty)))
           (expr/normalize '(sum [b (var :items)] (get b :qty))))))
  (testing "1-ary count passes through"
    (is (= '(count (var :days)) (expr/normalize '(count (var :days))))))
  (testing "idempotent"
    (doseq [f ['(count [d (var :days)] (= (get d :x) 1))
               '(sum [d (var :items)] (* 2 (get d :qty)))
               '(count (var :days))]]
      (let [n (expr/normalize f)]
        (is (= n (expr/normalize n))))))
  (testing "count/sum are not boolean-valued: and does not collapse around them"
    (is (= '(and (count (var :days)))
           (expr/normalize '(and (count (var :days)) (count (var :days))))))))

;; ── evaluation ──────────────────────────────────────────────────────

(deftest count-sum-evaluation
  (let [ev (fn [f scope] (expr/evaluate (expr/normalize f) scope))]
    (testing "count of items, with and without predicate"
      (is (= 3 (ev '(count (var :days)) {:vars {:days [1 2 3]}})))
      (is (= 2 (ev '(count [d (var :days)] (is-set (get d :meal_id)))
                   {:vars {:days [{:meal_id "a"} {} {:meal_id "b"}]}}))))
    (testing "the quantifier empty rule, numerically: missing collections are 0"
      (is (= 0 (ev '(count (var :days)) {:vars {}})))
      (is (= 0 (ev '(sum [d (var :items)] (get d :qty)) {:vars {}})))
      (is (= 0 (ev '(sum [d (var :items)] (get d :qty)) {:vars {:items []}}))))
    (testing "sum is exact across long and decimal"
      (is (= 5 (ev '(sum [d (var :items)] (get d :qty))
                   {:vars {:items [{:qty 2} {:qty 3}]}})))
      (is (= 2.5M (ev '(sum [d (var :items)] (get d :qty))
                      {:vars {:items [{:qty 2} {:qty 0.5M}]}}))))
    (testing "a non-numeric addend nil-propagates the whole sum"
      (is (nil? (ev '(sum [d (var :items)] (get d :qty))
                    {:vars {:items [{:qty 2} {}]}})))
      (is (nil? (ev '(sum [d (var :items)] (get d :qty))
                    {:vars {:items [{:qty "three"}]}}))))
    (testing "count composes under comparison"
      (is (true? (ev '(<= 2 (count [d (var :days)] (= (get d :ok) true)))
                     {:vars {:days [{:ok true} {:ok true} {}]}}))))
    (testing "total over garbage"
      (is (= 0 (ev '(count (var :days)) {:vars {:days "not-a-coll"}})))
      (is (= 0 (ev '(sum (var :items) (it 0)) {:vars {:items 42}}))))))

;; ── wire ────────────────────────────────────────────────────────────

(deftest count-sum-wire
  (testing "the JSON tree spelling and its round trip"
    (let [f (expr/normalize '(sum [d (var :items)] (get d :qty)))]
      (is (= ["sum" ["var" "items"] ["get" ["it" 0] "qty"]]
             (wire/form->wire f)))
      (is (= f (wire/wire->form (wire/form->wire f)))))
    (let [f (expr/normalize '(count (var :days)))]
      (is (= ["count" ["var" "days"]] (wire/form->wire f)))
      (is (= f (wire/wire->form (wire/form->wire f)))))))

;; ── scope discipline ────────────────────────────────────────────────

(deftest count-sum-scope
  (testing "derived scope: only declared :over names"
    (is (= [] (expr/derived-scope-problems
               (expr/normalize '(sum [d (var :items)] (get d :qty)))
               #{:items})))
    (is (seq (expr/derived-scope-problems
              (expr/normalize '(sum [d (var :items)] (get d :qty)))
              #{:days}))))
  (testing "guard scope refuses bare (var …) inside the quantifier"
    (is (seq (expr/guard-scope-problems
              (expr/normalize '(count [d (var :items)] (is-set d))))))
    (is (= [] (expr/guard-scope-problems
               (expr/normalize '(count [d (data :items)]
                                  (is-set (get d :x)))))))))

;; ── the generator property ──────────────────────────────────────────
;; Generated count/sum forms are well-formed; normalize is idempotent
;; and meaning-preserving; wire round-trip is identity on canonicals.

(def ^:private gen-item-num
  (gen/one-of [gen/small-integer
               (gen/fmap #(BigDecimal/valueOf (long %)) gen/small-integer)]))

(def ^:private gen-scope
  (gen/let [qtys (gen/vector gen-item-num 0 6)
            flags (gen/vector gen/boolean 0 6)]
    {:vars {:items (mapv (fn [q f] {:qty q :ok f}) qtys (cycle flags))}}))

(def ^:private gen-form
  (gen/elements
   ['(count (var :items))
    '(count [d (var :items)] (= (get d :ok) true))
    '(count [d (var :items)] (< 0 (get d :qty)))
    '(sum [d (var :items)] (get d :qty))
    '(sum [d (var :items)] (* 2 (get d :qty)))
    '(sum [d (var :items)] (+ (get d :qty) 1))
    '(< (sum [d (var :items)] (get d :qty))
        (count (var :items)))]))

(defspec count-sum-property 200
  (prop/for-all [form gen-form
                 scope gen-scope]
    (let [n (expr/normalize form)]
      (and (expr/well-formed? form)
           (expr/well-formed? n)
           (= n (expr/normalize n))
           (= n (wire/wire->form (wire/form->wire n)))
           ;; meaning preservation: authored and canonical agree
           (= (expr/evaluate form scope) (expr/evaluate n scope))))))
