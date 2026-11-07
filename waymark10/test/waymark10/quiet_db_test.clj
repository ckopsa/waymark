(ns waymark10.quiet-db-test
  "A quiet engine writes nothing to its database (ticket 96d77fbe): on a
  read-only storage it boots, adopts the law a live engine wrote, runs
  the surfaces that serve, and leaves every table as it found it —
  while reads keep working and a write is refused by Postgres itself.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]))

(def ^:private chore
  (r/resource
   {:kind :qt_chore
    :plural "qt_chores"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 80}]]]
    :actions
    {:close {:from #{:open} :to :done
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Done is done."}}}}))

(def ^:private tables
  ["qt_chores" "definitions" "subscriptions" "members" "roles" "grants"
   "approval_requests" "jobs" "waymark10_transitions"
   "waymark10_idempotency" "waymark10_cursors" "waymark10_observations"])

(defn- counts
  "Each table's row count, read through `st` — nil for a table the
  live boot did not make."
  [st]
  (into {}
        (for [t tables]
          [t (try (:n (jdbc/execute-one! (:ds st) [(str "SELECT count(*) AS n FROM " t)]))
                  (catch Exception _ nil))])))

(defn- refusal
  "The message Postgres answers `sql` with on `st`, or nil when it ran."
  [st sql]
  (try (jdbc/execute! (:ds st) [sql]) nil
       (catch Exception e (ex-message e))))

(deftest a-quiet-engine-writes-nothing-to-its-database
  ;; the live engine: the schema, the first law and the outbox are its
  ;; to write, and it has run once before the quiet one boots
  (let [rw (pg/storage db/dsn)]
    (try
      (store/with-tx rw
        (fn [tx]
          (doseq [t ["qt_chores" "qt_never"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " t " CASCADE")]))))
      (let [live (engine/engine {:storage rw :resources [chore]})]
        (engine/start-runtime! live)
        (engine/stop-runtime! live))
      (finally (pg/close! rw))))
  (let [ro (pg/storage db/dsn {:read-only? true})]
    (try
      (let [before (counts ro)]
        (testing "reads keep working on a read-only session"
          (is (= 0 (get before "qt_chores"))))
        (testing "Postgres refuses a write on it"
          (is (re-find #"read-only" (str (refusal ro "CREATE TABLE qt_never (id int)"))))
          (is (re-find #"read-only" (str (refusal ro "DELETE FROM definitions")))))
        (testing "a quiet engine boots on it, starts what serves, and writes nothing"
          (let [eng (engine/engine {:storage ro :resources [chore] :quiet? true
                                    :events-poll-ms 200
                                    :law-refresh-debounce-ms 100
                                    :curtain-ttl-ms 500
                                    :presence-heartbeat-ms 300})]
            (engine/start-runtime! eng)
            (try
              (Thread/sleep 1500)
              (is (seq @(:runtime eng)) "the surfaces that serve started")
              (finally (engine/stop-runtime! eng)))
            (is (= before (counts ro))))))
      (finally (pg/close! ro)))))
