(ns waymark10.test.db
  "The test database lifecycle: one engine per test namespace. Tables
  are dropped first — every run starts from bytes it made itself —
  and only tables the passed declarations name are touched: a suite
  drops nothing it did not enroll.

  WAYMARK10_TEST_DSN overrides the default local DSN."
  (:require [next.jdbc :as jdbc]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]))

(def dsn
  (or (System/getenv "WAYMARK10_TEST_DSN")
      "jdbc:postgresql://localhost:5433/waymark10_test?user=ckopsa"))

(defn with-test-engine
  "Connect, drop the resources' tables plus the engine's transition
  and idempotency tables, build the engine over `resources`
  (normalized declarations), call (f eng), close the pool."
  [resources f]
  (let [st (pg/storage dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table (concat (map #(store/definition-checked-name (:plural %))
                                     resources)
                                ["waymark10_transitions" "waymark10_idempotency"
                                 "waymark10_drafts"])]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (f (inv/engine {:storage st :resources resources}))
      (finally (pg/close! st)))))
