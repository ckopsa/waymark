(ns waymark10.clock-test
  (:require [clojure.test :refer [deftest is]]
            [waymark10.server.clock :as clock])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

(deftest a-pass-that-throws-is-logged-and-the-next-still-runs
  (let [passes (atom 0)
        logged (atom [])
        third (CountDownLatch. 1)]
    (with-redefs [clock/log-failure! (fn [loop-name e]
                                       (swap! logged conj [loop-name (ex-message e)]))]
      (let [stop (clock/every! {:name "waymark10-clock-test"
                                :period-ms 10
                                :f (fn []
                                     (let [n (swap! passes inc)]
                                       (when (= 3 n) (.countDown third))
                                       (when (= 1 n) (throw (ex-info "boom" {})))))})]
        (try
          (is (.await third 5 TimeUnit/SECONDS)
              "the loop outlived its first pass's exception")
          (finally (stop)))))
    (is (= ["waymark10-clock-test" "boom"] (first @logged)))))

(deftest stop-ends-the-loop
  (let [passes (atom 0)
        stop (clock/every! {:name "waymark10-clock-stop-test"
                            :period-ms 10
                            :f #(swap! passes inc)})]
    (Thread/sleep 50)
    (stop)
    (Thread/sleep 30)
    (let [n @passes]
      (Thread/sleep 60)
      (is (= n @passes)))))
