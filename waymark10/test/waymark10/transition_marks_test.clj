(ns waymark10.transition-marks-test
  "store/transition-marks over Postgres: the measure sweep's
  transition:<action> read answers {:at :resource-id} for exactly the
  logged transitions of the action inside [since, until) on a row the
  conds admit, oldest first, at most `limit`. The memory twin is
  exercised by measure-readings-test; this drives the SQL against the
  waymark10_test database (WAYMARK10_TEST_DSN overrides; run serially)."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.types :as t])
  (:import (java.time Instant)))

(def ^:private dsn
  (or (System/getenv "WAYMARK10_TEST_DSN")
      "jdbc:postgresql://localhost:5433/waymark10_test?user=ckopsa"))

(def ^:private item
  (r/resource
   {:kind :tm_item
    :plural "tm_items"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 80}]]
             [:tag [:string {:min 1 :max 20}]]]
    :actions
    {:poke {:from #{:open} :to :open
            :safety {:idempotent true :reversible true :confirm false}}
     :nudge {:from #{:open} :to :open
             :safety {:idempotent true :reversible true :confirm false}}
     :close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "History keeps the record."}}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- at [s] (Instant/parse s))

(defn- create! [eng title tag]
  (str (get-in (inv/create! eng :tm_item {:title title :tag tag}
                            {:principal elena})
               [:row :id])))

(defn- move! [eng st id action when]
  ;; logs one transition of action on the row, then pins its time
  (inv/invoke! eng :tm_item id action nil
               {:principal elena :idempotency-key (str (random-uuid))})
  (store/with-tx st
    (fn [tx]
      (jdbc/execute! tx [(str "UPDATE waymark10_transitions SET at = ?"
                              " WHERE resource_id::text = ? AND action = ?")
                         (java.sql.Timestamp/from ^Instant when)
                         id (name action)]))))

(def ^:private kept [{:target :data :field :tag :op := :value "keep"}])

(deftest transition-marks-over-postgres
  (let [st (pg/storage dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table ["tm_items" "waymark10_transitions"
                         "waymark10_idempotency"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (let [eng (inv/engine {:storage st :resources [item]})
            since (at "2026-10-01T00:00:00Z")
            until (at "2026-10-08T00:00:00Z")
            late (create! eng "late" "keep")
            early (create! eng "early" "keep")
            refused (create! eng "refused" "drop")
            before (create! eng "before" "keep")
            edge (create! eng "edge" "keep")
            other (create! eng "other" "keep")
            marks (fn [conds limit]
                    (store/with-tx st
                      #(store/transition-marks st % :tm_item :poke
                                               since until conds limit)))]
        ;; created first, poked later: id order is not at order
        (move! eng st late :poke (at "2026-10-05T12:00:00Z"))
        (move! eng st early :poke (at "2026-10-02T12:00:00Z"))
        (move! eng st refused :poke (at "2026-10-03T12:00:00Z"))
        (move! eng st before :poke (at "2026-09-30T23:59:59Z"))
        (move! eng st edge :poke until)
        (move! eng st other :nudge (at "2026-10-04T12:00:00Z"))
        (testing "in-window, admitted transitions of the action, oldest first"
          (is (= [{:at (at "2026-10-02T12:00:00Z") :resource-id early}
                  {:at (at "2026-10-05T12:00:00Z") :resource-id late}]
                 (marks kept 10))
              "before the window, at until, another action and a row the conds refuse are all left out"))
        (testing "no conds admit every row of the kind"
          (is (= [early refused late]
                 (mapv :resource-id (marks [] 10)))))
        (testing "the limit keeps the oldest"
          (is (= [{:at (at "2026-10-02T12:00:00Z") :resource-id early}]
                 (marks kept 1)))))
      (finally (pg/close! st)))))
