(ns waymark10.wire-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [waymark10.expr :as e]
            [waymark10.gen-forms :as gf]
            [waymark10.wire :as w]))

(def trials 300)

(defspec wire-round-trip-is-identity trials
  (prop/for-all [f gf/gen-form]
    (let [n (e/normalize f)]
      (= n (w/wire->form (w/read-json (w/write-json (w/form->wire n))))))))

(defspec wire-trees-are-canonically-encodable trials
  (prop/for-all [f gf/gen-form]
    (string? (w/canonical-json (w/form->wire (e/normalize f))))))

(deftest the-wire-shape-is-the-documented-one
  (is (= "[\"every\",[\"var\",\"days\"],[\"or\",[\"is-set\",[\"get\",[\"it\",0],\"meal_id\"]],[\"=\",[\"get\",[\"it\",0],\"eating_out\"],true]]]"
         (w/write-json
          (w/form->wire
           (e/normalize '(every [d (var :days)]
                           (or (is-set (get d :meal_id))
                               (= (get d :eating_out) true))))))))
  (is (= {"dec" "0.02"} (w/form->wire 0.02M)))
  (is (= {"date" "2026-07-09"} (w/form->wire '(date "2026-07-09")))))

(deftest wire-refuses-what-it-cannot-read-back
  (testing "unknown operators"
    (is (thrown? Exception (w/wire->form ["exec" "rm -rf /"])))
    (is (thrown? Exception (w/wire->form ["sum" ["var" "xs"]]))
        "sum is not yet earned"))
  (testing "floats"
    (is (thrown? Exception (w/wire->form ["<=" ["data" "x"] 0.5]))))
  (testing "malformed nodes"
    (is (thrown? Exception (w/wire->form [["data" "x"] "y"]))
        "a list node needs an operator head")
    (is (thrown? Exception (w/wire->form {"dec" "not-a-number"})))
    (is (thrown? Exception (w/wire->form {"dec" "1" "date" "2026-01-01"})))
    (is (thrown? Exception (w/wire->form ["it" -1])))
    (is (thrown? Exception (w/wire->form ["it" 3]))
        "a binder index outside its quantifiers is refused at validation"))
  (testing "scope discipline survives the wire"
    (is (thrown? Exception (w/wire->form ["var" "x" "y"])))))

(deftest canonical-json-is-deterministic
  (is (= (w/canonical-json {:b 1 :a {:d 2 :c 3}})
         (w/canonical-json (into (sorted-map-by (fn [x y] (compare y x)))
                                 {:b 1 :a (into (sorted-map-by (fn [x y] (compare y x)))
                                                {:d 2 :c 3})})))
      "key insertion order never reaches the bytes")
  (is (= "{\"a\":{\"c\":3,\"d\":2},\"b\":1}"
         (w/canonical-json {:b 1 :a {:d 2 :c 3}})))
  (testing "floats and raw decimals are refused from canonical bytes"
    (is (thrown? Exception (w/canonical-json {:x 0.5})))
    (is (thrown? Exception (w/canonical-json {:x 0.5M}))
        "decimals must cross as {\"dec\": …} wire nodes")))

(deftest digests-are-stable
  (is (= (w/digest {:kind "plan" :n 1})
         (w/digest {:n 1 :kind "plan"})))
  (is (not= (w/digest {:kind "plan" :n 1})
            (w/digest {:kind "plan" :n 2}))))

(deftest json-decimals-stay-exact
  (is (= 0.02M (get (w/read-json "{\"x\": 0.02}") :x))
      "the HTTP mapper parses decimals as BigDecimal, never double"))
