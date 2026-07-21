(ns choreplan10.queue-test
  "The queue verb, over the ring handler: queueing work IS minting a
  run, and the verb lives on the chore — POST /-/queue births the
  chore_run through the ctx :create door in the same call, the ref
  label (chore_name) lands at birth, the touches promise correlates
  by id, and queueing twice mints twice (regret is one Skip on the
  run, and the record stays).

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [choreplan10.main :as main]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.conformance :as conf]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["chores" "chore_runs" "prep_tasks"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources (main/check-resources)})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(def colton-headers {"x-waymark-principal" "colton"})

(defn- req
  ([method uri] (req method uri nil {}))
  ([method uri body] (req method uri body {}))
  ([method uri body headers]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path
                   :headers (merge colton-headers headers)}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

;; ── the story ───────────────────────────────────────────────────────

(deftest queueing-work-is-minting-a-run
  (let [chore (json (req :post "/api/chores"
                         {:name "Dishes" :cadence "daily"
                          :assignee "housekeeper"
                          :notes "run the disposal; air-dry the pans"}))
        _ (is (some? (:self chore)))
        queue! (fn [due]
                 (req :post (str (:self chore) "/-/queue")
                      {:due_date due}
                      {"idempotency-key" (str (random-uuid))}))]

    (testing "the verb rides the chore's envelope"
      (is (contains? (:actions (json (req :get (:self chore)))) :queue)))

    (testing "one POST, one run — born with its label AND carry garnish"
      (let [resp (queue! "2026-07-22")
            _ (is (= 200 (:status resp)) (:body resp))
            runs (get-in (json (req :get "/api/chore_runs?state=due"))
                         [:data :items])]
        (is (= 1 (count runs)))
        (is (str/starts-with? (:summary (first runs)) "Dishes · 2026-07-22")
            "the ref label landed at birth — no join, the summary reads")
        (is (= "run the disposal; air-dry the pans"
               (get-in (json (req :get (:self (first runs))))
                       [:data :chore_notes]))
            "the chore's instructions ride the run — at a glance, no hop")))

    (testing "queueing twice mints twice — regret is a Skip, not a dedupe"
      (queue! "2026-07-23")
      (is (= 2 (get-in (json (req :get "/api/chore_runs?state=due"))
                       [:data :total]))))

    (testing "the touches promise holds by correlation id"
      (let [cid (str "queue-cid-" (random-uuid))
            chore-id (last (str/split (:self chore) #"/"))]
        (inv/invoke! *eng* :chore chore-id :queue {:due_date "2026-07-24"}
                     {:principal colton :correlation-id cid
                      :idempotency-key (str (random-uuid))})
        (is (empty? (conf/touches-violations *eng*)))))))
