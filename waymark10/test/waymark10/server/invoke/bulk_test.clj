(ns waymark10.server.invoke.bulk-test
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.invoke.bulk :as bulk]))

(deftest the-three-body-shapes-read-into-one-item-list
  (is (= [{:id "a" :input {:note "x"} :acknowledged #{}}
          {:id "b" :input {:note "x"} :acknowledged #{}}]
         (bulk/bulk-items :close {:ids ["a" "b"] :note "x"} #{})))
  (is (= [{:id "a" :input nil :acknowledged #{:late}}]
         (bulk/bulk-items :close {:items [{:id "a" :acknowledge ["late"]}]} #{})))
  (is (= [{:id "r1" :input nil :acknowledged #{}}]
         (bulk/where-items :close {:where {:state "open"}} #{} (constantly ["r1"]))))
  (testing "two shapes at once, or a where with no resolver, is refused"
    (is (thrown? Exception (bulk/bulk-items :close {:ids ["a"] :items [{:id "a"}]} #{})))
    (is (thrown? Exception (bulk/where-items :close {:where {:state "open"}} #{} nil)))))

(deftest on-error-may-only-tighten-the-declaration
  (is (= "continue" (bulk/on-error-mode :close {} {})))
  (is (= "stop" (bulk/on-error-mode :close {} {:on_error "stop"})))
  (is (= "atomic" (bulk/on-error-mode :close {:atomic true} {})))
  (is (thrown? Exception (bulk/on-error-mode :close {:atomic true} {:on_error "continue"})))
  (is (thrown? Exception (bulk/on-error-mode :close {} {:on_error "later"}))))
