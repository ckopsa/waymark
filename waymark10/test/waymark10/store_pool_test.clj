(ns waymark10.store-pool-test
  "The pool's size and hold log are settable, and many long waits that
  release between reads leave connections for ordinary calls (83852ca1)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]))

(deftest the-pool-takes-its-size-and-hold-log-from-opts
  (let [st (pg/storage db/dsn {:pool-size 3 :hold-log-ms 5000})
        ds ^com.zaxxer.hikari.HikariDataSource (:ds st)]
    (try
      (is (= 3 (.getMaximumPoolSize ds)))
      (is (= 5000 (.getLeakDetectionThreshold ds)))
      (finally (pg/close! st)))))

(deftest thirty-long-polls-leave-connections-free
  (testing "30 waits that read, release and sleep never starve a plain read"
    (let [st (pg/storage db/dsn {:pool-size 4})
          ds (:ds st)
          stop (atom false)
          poll (fn []
                 (while (not @stop)
                   (with-open [conn (jdbc/get-connection ds)]
                     (jdbc/execute! conn ["select 1"]))
                   (Thread/sleep 250)))
          polls (doall (repeatedly 30 #(future (poll))))]
      (try
        (Thread/sleep 500)
        (dotimes [_ 10]
          (let [t0 (System/nanoTime)]
            (with-open [conn (jdbc/get-connection ds)]
              (jdbc/execute! conn ["select 1"]))
            (is (< (/ (- (System/nanoTime) t0) 1e6) 1000.0)))
          (is (< (:waiting (pg/pool-stats st)) 30))
          (Thread/sleep 100))
        (finally
          (reset! stop true)
          (run! deref polls)
          (pg/close! st))))))
