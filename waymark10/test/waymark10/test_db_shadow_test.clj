(ns waymark10.test-db-shadow-test
  "with-test-engine refuses a test rdef that shadows a built-in module
  kind (ticket 4d791724) — before it touches the database."
  (:require [clojure.test :refer [deftest is testing]]
            [waymark10.test.db :as db]))

(deftest a-test-kind-may-not-shadow-a-built-in
  (testing "the kind, the plural, or both name the clash"
    (is (= [{:kind :member :plural "members" :shadows :member}]
           (db/shadowed-built-ins [{:kind :member :plural "members"}])))
    (is (= [{:kind :volunteer :plural "members" :shadows :member}]
           (db/shadowed-built-ins [{:kind :volunteer :plural "members"}])))
    (is (= [{:kind :member :plural "volunteers" :shadows :member}]
           (db/shadowed-built-ins [{:kind :member :plural "volunteers"}]))))
  (testing "an application kind passes"
    (is (= [] (db/shadowed-built-ins [{:kind :client :plural "clients"}]))))
  (testing "with-test-engine refuses before it connects"
    (let [e (try (db/with-test-engine [{:kind :member :plural "members"}]
                   (fn [_] :ran))
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (= :shadows-built-in (:check (ex-data e)))))))
