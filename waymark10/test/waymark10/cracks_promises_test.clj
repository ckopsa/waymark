(ns waymark10.cracks-promises-test
  "Promise build piece 7a (docs/spec-promise.md R-P.11.2): the cracks
  audit names a promise not retired whose remedy door no active seat
  holds, and is silent once a seat holds one of its doors."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.server.cracks :as cracks]))

(defn- seat [name scope]
  {:id name :state :active :data {:name name :scope scope}})

(defn- promise [id state remedy]
  {:id id :state state :data {:name (str "promise " id) :remedy remedy}})

(defn- of [seats promises]
  (filterv #(= "promise_remedy_unheld" (:crack %))
           (cracks/audit [] seats {:promises promises})))

(deftest a-promise-no-seat-can-remedy-is-named
  (let [p (promise "p1" :kept {:kind "ticket" :doors ["complete" "ungroom"]})
        [c & more] (of [(seat "reader" [{:kind "ticket" :actions ["read"]}])] [p])]
    (is (nil? more))
    (is (= "promise" (:kind c)))
    (is (= "kept" (:state c)))
    (is (= "p1" (:promise c)))
    (is (= "ticket/complete,ungroom" (:remedy c)))
    (is (seq (:what_would_close_it c))))
  (testing "a one-door remedy is named as well"
    (is (= ["ticket/reopen"]
           (mapv :remedy (of [] [(promise "p2" :broken {:kind "ticket" :door "reopen"})]))))))

(deftest a-held-remedy-is-silent
  (let [p (promise "p1" :kept {:kind "ticket" :doors ["complete" "ungroom"]})]
    (testing "a seat that holds any one of the doors"
      (is (empty? (of [(seat "coder" [{:kind "ticket" :actions ["ungroom"]}])] [p]))))
    (testing "a person's seat that holds it"
      (is (empty? (of [(assoc-in (seat "mayor" [{:kind "ticket" :actions ["complete"]}])
                                 [:data :mode] "person")]
                      [p]))))
    (testing "a retired promise, and no promises read"
      (is (empty? (of [] [(promise "p3" :retired {:kind "ticket" :door "reopen"})])))
      (is (empty? (of [] nil))))))
