(ns waymark10.expr-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [waymark10.expr :as e]
            [waymark10.gen-forms :as gf])
  (:import (java.time Instant LocalDate)))

(def trials 300)

;; ── properties (the phase-0 acceptance bar) ─────────────────────────

(defspec generated-forms-are-well-formed trials
  (prop/for-all [f gf/gen-form]
    (empty? (e/problems f))))

(defspec normalize-is-idempotent trials
  (prop/for-all [f gf/gen-form]
    (= (e/normalize f) (e/normalize (e/normalize f)))))

(defspec normalized-forms-stay-well-formed trials
  (prop/for-all [f gf/gen-form]
    (empty? (e/problems (e/normalize f)))))

(defspec evaluation-is-total trials
  (prop/for-all [f gf/gen-form
                 scope gf/gen-scope]
    (do (e/evaluate f scope)
        (e/evaluate (e/normalize f) scope)
        true)))

(defspec normalization-preserves-meaning trials
  (prop/for-all [f gf/gen-form
                 scope gf/gen-scope]
    (let [a (e/evaluate f scope)
          b (e/evaluate (e/normalize f) scope)]
      (or (= a b)
          ;; exact decimals compare by value, not scale
          (and (number? a) (number? b)
               (zero? (.compareTo (bigdec a) (bigdec b))))))))

;; ── semantics units ─────────────────────────────────────────────────

(deftest nil-satisfies-no-ordering
  (is (false? (e/evaluate '(< (data :x) 3) {:data {}})))
  (is (false? (e/evaluate '(<= (data :x) (data :y)) {:data {:x nil :y 2}})))
  (is (true? (e/evaluate '(= (data :x) nil) {:data {}})))
  (is (false? (e/evaluate '(not= (data :x) nil) {:data {}}))))

(deftest arithmetic-propagates-nil
  (is (nil? (e/evaluate '(+ (data :x) 1) {:data {}})))
  (is (nil? (e/evaluate '(* 2 (data :x)) {:data {}})))
  (is (nil? (e/evaluate '(abs (data :x)) {:data {}})))
  (is (= 5 (e/evaluate '(min (data :x) 5) {:data {}}))
      "min/max skip missing operands"))

(deftest decimals-are-exact
  (is (true? (e/evaluate '(= (+ 0.1M 0.2M) 0.3M) {})))
  (is (true? (e/evaluate '(<= (abs (var :difference)) 0.02M)
                         {:vars {:difference -0.015M}})))
  (is (false? (e/evaluate '(<= (abs (var :difference)) 0.02M)
                          {:vars {:difference 0.021M}})))
  (is (true? (e/evaluate '(= 1.50M 1.5M) {}))
      "value equality, not scale equality"))

(deftest date-arithmetic
  (let [scope {:vars {:start_date (LocalDate/parse "2026-07-07") :weeks 2}}]
    (is (= (LocalDate/parse "2026-07-20")
           (e/evaluate (e/normalize '(+ (var :start_date)
                                        (days (- (* 7 (var :weeks)) 1))))
                       scope))))
  (is (true? (e/evaluate '(<= (data :start_date) (date-of (now)))
                         {:data {:start_date (LocalDate/parse "2026-07-01")}
                          :now (Instant/parse "2026-07-09T12:00:00Z")})))
  (is (= (LocalDate/parse "2026-07-09")
         (e/evaluate '(date-of (now))
                     {:now (Instant/parse "2026-07-09T23:59:59Z")}))))

(deftest quantifiers
  (is (true? (e/evaluate '(every [d (var :days)]
                            (or (is-set (get d :meal_id))
                                (= (get d :eating_out) true)))
                         {:vars {:days [{:meal_id "m1"} {:eating_out true}]}})))
  (is (false? (e/evaluate '(every [d (var :days)] (is-set (get d :meal_id)))
                          {:vars {:days [{:meal_id "m1"} {}]}})))
  (testing "vacuous truth over empty and missing collections"
    (is (true? (e/evaluate '(every [d (var :days)] (is-set (get d :x))) {:vars {:days []}})))
    (is (true? (e/evaluate '(every [d (var :days)] (is-set (get d :x))) {})))
    (is (false? (e/evaluate '(some [d (var :days)] (is-set (get d :x))) {}))))
  (testing "nested binders reach the right quantifier"
    (is (true? (e/evaluate
                '(some [g (var :groups)]
                   (every [m (get g :members)]
                     (= (get m :ok) true)))
                {:vars {:groups [{:members [{:ok false}]}
                                 {:members [{:ok true} {:ok true}]}]}})))))

(deftest de-bruijn-normalization
  (is (= '(every (var :days) (is-set (get (it 0) :meal_id)))
         (e/normalize '(every [d (var :days)] (is-set (get d :meal_id))))))
  (is (= (e/normalize '(some [x (var :xs)] (every [y (var :ys)] (= x y))))
         (e/normalize '(some [outer (var :xs)]
                         (every [inner (var :ys)] (= outer inner)))))
      "alpha-equivalent spellings are the same tree"))

(deftest respellings-normalize-identically
  (is (= (e/normalize '(> (data :a) (data :b)))
         (e/normalize '(< (data :b) (data :a)))))
  (is (= (e/normalize '(and (data :p) (and (data :q) (data :p))))
         (e/normalize '(and (data :p) (data :q))))
      "nested same-op flattens; duplicates collapse")
  (is (= (e/normalize '(not (not (= (data :a) 1))))
         (e/normalize '(= (data :a) 1))))
  (is (= (e/normalize '(not (= (data :a) 1)))
         (e/normalize '(not= 1 (data :a))))
      "not-of-= canonicalizes to not=; commutative operands sort")
  (is (= (e/normalize '(= 1.50M (data :x)))
         (e/normalize '(= (data :x) 1.5M)))
      "decimal literals normalize scale"))

(deftest collapse-respects-the-value-domain
  (is (= '(and (data :a)) (e/normalize '(and (data :a) (data :a))))
      "a non-boolean operand keeps its coercing wrapper")
  (is (= '(not (not (data :a))) (e/normalize '(not (not (data :a))))))
  (is (= '(is-set (data :a))
         (e/normalize '(and (is-set (data :a)) (is-set (data :a)))))
      "a boolean-valued operand may stand alone"))

(deftest scope-validators
  (is (empty? (e/derived-scope-problems
               '(+ (var :start_date) (days (var :weeks)))
               #{:start_date :weeks})))
  (is (seq (e/derived-scope-problems '(var :undeclared) #{:start_date})))
  (is (seq (e/derived-scope-problems '(data :start_date) #{:start_date}))
      "derivations read declared inputs, not the row")
  (is (seq (e/derived-scope-problems '(now) #{:start_date}))
      "the clock enters a derivation as a declared input")
  (is (empty? (e/guard-scope-problems
               '(<= (data :start_date) (date-of (now))))))
  (is (seq (e/guard-scope-problems '(var :start_date)))))

(deftest info-reads-the-tree
  (is (= {:vars #{:days} :data #{} :inputs #{} :uses-now false}
         (e/info '(every [d (var :days)] (is-set (get d :meal_id))))))
  (is (= {:vars #{} :data #{:start_date} :inputs #{:date} :uses-now true}
         (e/info '(and (<= (data :start_date) (date-of (now)))
                       (is-set (input :date)))))))

;; ── the boundary refuses ────────────────────────────────────────────

(deftest read-form-refuses-hostile-input
  (testing "arbitrary code is not law"
    (is (thrown? Exception (e/read-form "(eval (slurp \"/etc/passwd\"))")))
    (is (thrown? Exception (e/read-form "(fn [x] x)")))
    (is (thrown? Exception (e/read-form "(clojure.core/+ 1 2)"))))
  (testing "tagged literals are refused"
    (is (thrown? Exception (e/read-form "#inst \"2026-07-09\"")))
    (is (thrown? Exception (e/read-form "#uuid \"00000000-0000-0000-0000-000000000000\"")))
    (is (thrown? Exception (e/read-form "#custom/tag 1"))))
  (testing "floats are refused, not rounded"
    (is (thrown? Exception (e/read-form "(<= (data :x) 0.5)"))))
  (testing "arity and reference discipline"
    (is (thrown? Exception (e/read-form "(data :a :b)")))
    (is (thrown? Exception (e/read-form "(+ 1)")))
    (is (thrown? Exception (e/read-form "unbound")))
    (is (thrown? Exception (e/read-form "(it 0)"))
        "a binder reference outside any quantifier")
    (is (thrown? Exception (e/read-form "(every [d (var :days)] missing)"))))
  (testing "oversized input"
    (is (thrown? Exception
                 (e/read-form (apply str "(and " (concat (repeat 4000 "(data :a) ") [")"]))))))
  (testing "well-formed law reads"
    (is (= '(<= (abs (var :difference)) 0.02M)
           (e/read-form "(<= (abs (var :difference)) 0.02M)")))))
