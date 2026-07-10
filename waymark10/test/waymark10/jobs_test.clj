(ns waymark10.jobs-test
  "Phase-9b acceptance, part 2: deferred jobs. A defer-over bulk call
  202s with the job envelope in Location; the worker claims through
  waymark10_job_leases and executes the per-item invokes (the SAME
  path a synchronous bulk item runs), reporting progress and
  refusals; cancel stops between batches; an expired lease is stolen.
  Suite-local kind over the ring handler; real Postgres."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
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
   {:kind :errand
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
            (doseq [table ["errands" "jobs" "subscriptions" "definitions"
                           "waymark10_transitions" "waymark10_idempotency"
                           "waymark10_job_leases"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [eng (engine/engine {:storage st :resources [errand]})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(defn- req
  ([method uri] (req method uri nil nil))
  ([method uri body] (req method uri body nil))
  ([method uri body headers]
   (*h* (cond-> {:request-method method
                 :uri uri
                 :headers (merge {"x-waymark-principal" "priya"} headers)}
          body (assoc :body (wire/write-json body))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))

(defn- errands!
  "Fresh errands; ready-mask marks which are ready. → [ids]"
  [ready-mask]
  (mapv (fn [i ready?]
          (id-of (req :post "/api/errands"
                      {:title (str "errand " i) :ready ready?})))
        (range (count ready-mask)) ready-mask))

(defn- errand-state [id]
  (:state (json (req :get (str "/api/errands/" id)))))

(defn- defer!
  "One over-threshold bulk call → the parsed 202 job envelope."
  [ids]
  (let [resp (req :post "/api/errands/-/finish" {:ids ids})
        job (json resp)]
    (is (= 202 (:status resp)) (str "wanted a 202: " (:body resp)))
    (is (= (:self job) (get-in resp [:headers "Location"])))
    job))

(defn- job-doc [job] (json (req :get (:self job))))

;; ── 1. defer → 202 → the worker completes with a correct report ─────

(deftest deferred-bulk-completes
  (let [ids (errands! [true true false true])
        job (defer! ids)]
    (testing "the 202 envelope is the running job, work untouched"
      (is (= "job" (:kind job)))
      (is (= "running" (:state job)))
      (is (= {:done 0 :total 4 :refusals []} (get-in job [:data :progress])))
      (is (= ids (get-in job [:data :ids])))
      (is (= "priya" (get-in job [:data :requested_by :id])))
      (doseq [id ids] (is (= "open" (errand-state id)))))
    (testing "one worker pass drives it to completed"
      (is (= 1 (jobs/run-once! *eng* {:batch-size 2})))
      (let [done (job-doc job)]
        (is (= "completed" (:state done)))
        (is (= 4 (get-in done [:data :progress :done])))
        (testing "the report counts honestly: the guard's own sentence"
          (let [[refusal] (get-in done [:data :progress :refusals])]
            (is (= 1 (count (get-in done [:data :progress :refusals]))))
            (is (= (str "/api/errands/" (nth ids 2)) (:self refusal)))
            (is (= "This errand is not ready." (:reason refusal))))))
      (testing "the items moved through the ordinary per-item path"
        (is (= ["done" "done" "open" "done"]
               (mapv errand-state ids))))
      (testing "the item transitions carry the requesting principal"
        (let [ts (store/with-tx (:storage *eng*)
                   #(store/transitions (:storage *eng*) %
                                       {:kind :errand
                                        :resource-id (first ids)} {}))]
          (is (= "priya" (get-in (last ts) [:actor :id]))))))))

;; ── 2. cancel stops between batches ─────────────────────────────────

(deftest cancel-mid-run
  (let [ids (errands! [true true true true true])
        job (defer! ids)
        job-id (last (str/split (:self job) #"/"))
        opts {:batch-size 2 :holder "w1" :lease-seconds 60}]
    (is (true? (jobs/claim! *eng* job-id "w1" 60)))
    (testing "the first batch lands"
      (is (= :running (jobs/run-batch! *eng* job-id opts)))
      (is (= 2 (get-in (job-doc job) [:data :progress :done])))
      (is (= ["done" "done" "open" "open" "open"] (mapv errand-state ids))))
    (testing "cancel is an ordinary confirm-gated action on the wire"
      (let [resp (req :post (str (:self job) "/-/cancel"))]
        (is (= 200 (:status resp)))
        (is (= "cancelled" (:state (json resp))))))
    (testing "the worker observes the cancel between batches and stops"
      (is (= :cancelled (jobs/run-batch! *eng* job-id opts)))
      (is (= 2 (get-in (job-doc job) [:data :progress :done]))
          "items already processed stay done; the rest were never touched")
      (is (= ["done" "done" "open" "open" "open"] (mapv errand-state ids))))
    (jobs/release! *eng* job-id "w1")))

;; ── 3. the lease: held, renewed, stolen on expiry ───────────────────

(deftest lease-steal-after-expiry
  (let [ids (errands! [true true true])
        job (defer! ids)
        job-id (last (str/split (:self job) #"/"))]
    (testing "a live lease refuses another holder"
      (is (true? (jobs/claim! *eng* job-id "alive" 60)))
      (is (false? (jobs/claim! *eng* job-id "poacher" 60)))
      (is (true? (jobs/claim! *eng* job-id "alive" 60))
          "re-claiming our own lease renews it")
      (jobs/release! *eng* job-id "alive"))
    (testing "an expired lease is stolen — the steal IS the resume"
      ;; the dead worker's lease expires immediately
      (is (true? (jobs/claim! *eng* job-id "dead" 0)))
      (Thread/sleep 50)
      (is (= 1 (jobs/run-once! *eng* {:batch-size 10 :holder "heir"}))
          "the next worker claims-or-steals and finishes the job")
      (let [done (job-doc job)]
        (is (= "completed" (:state done)))
        (is (= 3 (get-in done [:data :progress :done])))))))

;; ── 4. the job surface holds its shape ──────────────────────────────

(deftest job-is-engine-owned
  (let [ids (errands! [true true true])
        job (defer! ids)]
    (testing "jobs are never wire-created"
      (let [resp (req :post "/api/jobs"
                      {:action "finish" :kind "errand" :ids ids
                       :requested_by {:id "priya"}
                       :progress {:done 0 :total 3 :refusals []}})]
        (is (= 409 (:status resp)))
        (is (str/includes? (:detail (json resp)) "never over the wire"))))
    (testing "complete is the worker's, hidden from humans"
      (is (= 404 (:status (req :post (str (:self job) "/-/complete")))))
      (let [env (job-doc job)]
        (is (not (contains? (:actions env) :complete)))
        (is (not (contains? (:unavailable env) :complete)))
        (is (contains? (:actions env) :cancel))))
    (jobs/run-once! *eng* {:batch-size 10})))
