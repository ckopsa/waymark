(ns waymark10.batch-f-jobs-test
  "Batch F, deliverable 2: jobs completeness. A job is born :queued
  and the worker's claim starts it; the final per-item report persists
  on the row (:report, the artifact); the orphan sweep re-queues
  :running jobs whose lease expired with no claimant — directly and
  under the coherence-elected role. Real Postgres
  (WAYMARK10_TEST_DSN)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.coherence :as coherence]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.jobs :as jobs]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private ready-gate
  (g/expr {:name :ready
           :when '(= (data :ready) true)
           :explain "This errand is not ready."}))

(def ^:private errand
  (r/resource
   {:kind :f_errand
    :plural "f_errands"
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:ready {:optional true} [:maybe :boolean]]]
    :actions
    {:finish {:from #{:open} :to :done
              :bulk {:defer-over 2 :max-items 100}
              :guards [ready-gate]
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "Done is done."}}}}))

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["f_errands" "jobs" "subscriptions" "definitions"
                           "members" "roles" "grants" "attachments"
                           "waymark10_transitions" "waymark10_idempotency"
                           "waymark10_job_leases"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [eng (engine/engine {:storage st :resources [errand]})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

(defn- req
  ([method uri] (req method uri nil))
  ([method uri body]
   (*h* (cond-> {:request-method method
                 :uri uri
                 :headers {"x-waymark-principal" "priya"}}
          body (assoc :body (wire/write-json body))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))

(defn- errands! [ready-mask]
  (mapv (fn [i ready?]
          (id-of (req :post "/api/f_errands"
                      {:title (str "errand " i) :ready ready?})))
        (range (count ready-mask)) ready-mask))

(defn- defer! [ids]
  (let [resp (req :post "/api/f_errands/-/finish" {:ids ids})]
    (is (= 202 (:status resp)))
    (json resp)))

(defn- job-doc [job] (json (req :get (:self job))))
(defn- job-id-of [job] (last (str/split (:self job) #"/")))

(defn- job-state [job-id]
  (:state (store/with-tx (:storage *eng*)
            #(store/load-row (:storage *eng*) % :job job-id {}))))

;; ── 1. born queued, the worker's claim starts it ────────────────────

(deftest born-queued-worker-starts
  (let [ids (errands! [true true true])
        job (defer! ids)
        job-id (job-id-of job)]
    (testing "the 202 envelope is the QUEUED job — nobody works it yet"
      (is (= "queued" (:state job)))
      (is (contains? (:actions job) :cancel) "cancel is live from queued"))
    (testing "the worker pass claims, starts, and completes"
      (is (= 1 (jobs/run-once! *eng* {:batch-size 2})))
      (let [done (job-doc job)]
        (is (= "completed" (:state done)))
        (is (= 3 (get-in done [:data :progress :done])))))
    (testing "the start transition is logged, worker actor"
      (let [ts (store/with-tx (:storage *eng*)
                 #(store/transitions (:storage *eng*) %
                                     {:kind :job :resource-id job-id} {}))
            start (first (filter #(= :start (:action %)) ts))]
        (is (some? start))
        (is (= :queued (:from-state start)))
        (is (= :running (:to-state start)))
        (is (= "waymark10-jobs" (get-in start [:actor :id])))))))

;; ── 2. the artifact: the final report on the row ────────────────────

(deftest artifact-persists-the-final-report
  (let [ids (errands! [true false true true])
        job (defer! ids)]
    (jobs/run-once! *eng* {:batch-size 2})
    (let [done (job-doc job)
          report (get-in done [:data :report])]
      (is (= "completed" (:state done)))
      (testing "the report carries the whole outcome"
        (is (= "finish" (:action report)))
        (is (= "f_errand" (:kind report)))
        (is (= 4 (:total report)))
        (is (= 3 (:succeeded report)))
        (is (= 1 (:refused report)))
        (is (= 0 (:failed report))))
      (testing "each refusal is classed and worded"
        (let [[refusal] (:refusals report)]
          (is (= 1 (count (:refusals report))))
          (is (= (str "/api/f_errands/" (second ids)) (:self refusal)))
          (is (= "This errand is not ready." (:reason refusal)))
          (is (= "refused" (:class refusal))))))))

;; ── 3. the orphan sweep ─────────────────────────────────────────────

(defn- orphan!
  "A :running job wearing a dead worker's lease: claim with a 0-ttl
  lease and start it as the worker would."
  []
  (let [ids (errands! [true true true])
        job (defer! ids)
        job-id (job-id-of job)]
    (is (true? (jobs/claim! *eng* job-id "dead-worker" 0)))
    (inv/invoke! *eng* :job job-id :start nil
                 {:principal jobs/worker-actor})
    ;; a 0-ttl lease expires at the DATABASE's now; the sweep judges
    ;; by the JVM's. A dockerized test db runs a few hundred ms ahead
    ;; of the host (0.26s observed), and 50ms lost that race roughly
    ;; every other run — sleep past any sane skew
    (Thread/sleep 1000)
    job-id))

(deftest orphan-sweep-requeues
  (let [job-id (orphan!)]
    (testing "the sweep re-queues the orphan and drops the stale lease"
      (is (= :running (job-state job-id)))
      (is (= 1 (jobs/sweep-orphans! *eng*)))
      (is (= :queued (job-state job-id)))
      (is (nil? (store/with-tx (:storage *eng*)
                  #(store/job-lease (:storage *eng*) % job-id))))
      (testing "the re-queue is in the audit trail, system actor"
        (let [ts (store/with-tx (:storage *eng*)
                   #(store/transitions (:storage *eng*) %
                                       {:kind :job :resource-id job-id} {}))
              rq (first (filter #(= :requeue (:action %)) ts))]
          (is (some? rq))
          (is (= "waymark10-jobs" (get-in rq [:actor :id]))))))
    (testing "a live claimant is never swept"
      (is (true? (jobs/claim! *eng* job-id "alive" 60)))
      (inv/invoke! *eng* :job job-id :start nil {:principal jobs/worker-actor})
      (is (= 0 (jobs/sweep-orphans! *eng*)))
      (is (= :running (job-state job-id)))
      (jobs/release! *eng* job-id "alive"))
    (testing "the next worker pass finishes the re-queued job"
      (is (= 1 (jobs/sweep-orphans! *eng*)) "released → orphan again")
      (is (= 1 (jobs/run-once! *eng* {:batch-size 10})))
      (is (= :completed (job-state job-id))))))

(deftest orphan-sweeper-is-an-elected-role
  (let [job-id (orphan!)
        role (jobs/start-orphan-sweeper! *eng* {:interval-ms 100
                                                :retry-ms 100})]
    (try
      (testing "the elected sweeper re-queues within a few intervals"
        (let [deadline (+ (System/currentTimeMillis) 10000)]
          (loop []
            (when (and (not= :queued (job-state job-id))
                       (< (System/currentTimeMillis) deadline))
              (Thread/sleep 50)
              (recur))))
        (is (= :queued (job-state job-id)))
        (is (true? @(:held? role)) "this process holds the role's lock"))
      (finally (coherence/stop-role! role)))
    ;; leave nothing queued behind this test
    (jobs/run-once! *eng* {:batch-size 10})))

;; ── 4. the queued surface holds its shape on the wire ───────────────

(deftest queued-shape-on-the-wire
  (let [ids (errands! [true true true])
        job (defer! ids)]
    (testing "start and requeue are the worker's, hidden from humans"
      (let [env (job-doc job)]
        (is (not (contains? (:actions env) :start)))
        (is (not (contains? (:unavailable env) :start)))
        (is (not (contains? (:actions env) :requeue)))
        (is (not (contains? (:unavailable env) :requeue))))
      (is (= 404 (:status (req :post (str (:self job) "/-/start"))))))
    (testing "cancel from queued is an ordinary wire action"
      (let [resp (req :post (str (:self job) "/-/cancel"))]
        (is (= 200 (:status resp)))
        (is (= "cancelled" (:state (json resp))))))))
