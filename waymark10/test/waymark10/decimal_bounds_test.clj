(ns waymark10.decimal-bounds-test
  "Exclusive decimal bounds (design §24): the registered :decimal
  honors :gt/:lt alongside :min/:max — a ratio is > 0, not ≥ some
  arbitrary floor. Projection speaks the standard exclusiveMinimum /
  exclusiveMaximum keywords. Pure."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.schema :as schema]))

(def ^:private ratio [:decimal {:gt 0 :max 100}])

(deftest exclusive-bounds-validate
  (testing "the boundary itself refuses; anything inside passes"
    (is (not (schema/validate ratio 0M)))
    (is (schema/validate ratio 0.005M))
    (is (schema/validate ratio 0.8M))
    (is (schema/validate ratio 100M))
    (is (not (schema/validate ratio 100.1M))))
  (testing ":lt mirrors"
    (let [s [:decimal {:min 0 :lt 1}]]
      (is (schema/validate s 0M))
      (is (schema/validate s 0.99M))
      (is (not (schema/validate s 1M))))))

(deftest exclusive-bounds-project
  (is (= {:type "number" :format "decimal" :exclusiveMinimum 0 :maximum 100}
         (get-in (schema/json-schema [:map [:ratio ratio]])
                 [:properties :ratio]))))
