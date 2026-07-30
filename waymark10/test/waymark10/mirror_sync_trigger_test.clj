(ns waymark10.mirror-sync-trigger-test
  "The manual sync trigger (the operator's door): POST
  /api/-/mirrors/{plural}/{resync|discover} mints a SYNC JOB — an
  ordinary :job row the discovery daemon services and the bulk
  worker skips; one pending job per (kind, flavor); the report is
  the pass's own statistics; a cancel takes effect before the run
  starts or not at all; an unreachable feed completes WITH the
  failure in the report (a heal, never a gate). Real Postgres
  (WAYMARK10_TEST_DSN)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.jobs :as jobs]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

;; ── the scriptable remote (discovery-pass's pattern, plus :down) ────

(defrecord ScriptedRemote [state]
  mirror/MirrorAdapter
  (discover [_]
    (when (:down @state) (throw (ex-info "the feed is down" {})))
    (vec (sort (keys (:docs @state)))))
  (pull [_ xid]
    (when (:down @state) (throw (ex-info "the feed is down" {})))
    (if-some [doc (get-in @state [:docs xid])]
      [doc (wire/digest doc)]
      (throw (ex-info (str xid " is gone from the remote") {}))))
  (pull-many [_ xids]
    (swap! state update :pulls (fnil inc 0))
    (when (:down @state) (throw (ex-info "the feed is down" {})))
    (into {}
          (keep (fn [xid]
                  (when-some [doc (get-in @state [:docs xid])]
                    [xid [doc (wire/digest doc)]])))
          xids))
  (push [_ _ _] (throw (ex-info "pull-only" {}))))

(defn- remote [] (->ScriptedRemote (atom {:docs {}})))
(defn- seed! [rm xid doc] (swap! (:state rm) assoc-in [:docs xid] doc))
(defn- pulls-of [rm] (get @(:state rm) :pulls 0))

;; one kind per test that needs isolation — kaocha may run tests in
;; any order, and a shared kind's row counts would couple the reports
(def ^:private rm-metric (remote))
(def ^:private rm-gauge (remote))
(def ^:private rm-dial (remote))
(def ^:private rm-flare (remote))

(defn- mirror-kind [kw plural adapter]
  (r/resource
   (mirror/declaration
    {:kind kw :plural plural :summary "{data.name}"
     :schema [:map [:name {:optional true} [:maybe [:string {:max 80}]]]]}
    {:adapter adapter :ttl-seconds 3600 :discover-every 3600})))

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["sync_metrics" "sync_gauges" "sync_dials"
                           "sync_flares" "jobs" "subscriptions"
                           "definitions" "members" "roles" "grants"
                           "attachments" "waymark10_transitions"
                           "waymark10_idempotency" "waymark10_job_leases"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [eng (engine/engine
                   {:storage st
                    :resources [(mirror-kind :sync_metric "sync_metrics" rm-metric)
                                (mirror-kind :sync_gauge "sync_gauges" rm-gauge)
                                (mirror-kind :sync_dial "sync_dials" rm-dial)
                                (mirror-kind :sync_flare "sync_flares" rm-flare)]})]
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

(defn- anon-req [method uri]
  (*h* {:request-method method :uri uri :headers {}}))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- rows-of [kind]
  (store/with-tx (:storage *eng*)
    (fn [tx] (store/query-rows (:storage *eng*) tx kind {} {:limit 100}))))

;; ── the whole flow: mint, dedupe, worker skip, service, report ──────

(deftest trigger-mints-dedupes-and-the-daemon-services
  (seed! rm-metric "a" {:name "A"})
  (seed! rm-metric "b" {:name "B"})
  (is (= 2 (mirror/discover! *eng* :sync_metric)))
  (seed! rm-metric "a" {:name "A2"})
  (let [resp (req :post "/api/-/mirrors/sync_metrics/resync")
        job (json resp)]
    (testing "202: the queued job, its Location, the requester recorded"
      (is (= 202 (:status resp)))
      (is (str/starts-with? (get-in resp [:headers "Location"] "") "/api/jobs/"))
      (is (= "queued" (:state job)))
      (is (= "resync" (get-in job [:data :action])))
      (is (= "sync_metric" (get-in job [:data :kind])))
      (is (= "priya" (get-in job [:data :requested_by :id]))))
    (testing "a second trigger answers the pending job — no twin"
      (let [again (req :post "/api/-/mirrors/sync_metrics/resync")]
        (is (= 200 (:status again)))
        (is (= (:self job) (:self (json again))))))
    (testing "the bulk worker never claims a sync job"
      (is (= 0 (jobs/run-once! *eng* {})))
      (is (= "queued" (:state (json (req :get (:self job)))))))
    (testing "the daemon's service pass runs the heal and reports"
      (is (= 1 (mirror/service-sync-jobs! *eng* {:holder "test-daemon"})))
      (let [done (json (req :get (:self job)))
            report (get-in done [:data :report])]
        (is (= "completed" (:state done)))
        (is (= 1 (get-in done [:data :progress :done])))
        (is (= "resync" (:action report)))
        (is (= "sync_metric" (:kind report)))
        (is (= 1 (:rewritten report)))
        (is (= 2 (:checked report)))))
    (testing "the changed document landed on the row"
      (let [row (first (store/with-tx (:storage *eng*)
                         (fn [tx] (store/query-rows
                                   (:storage *eng*) tx :sync_metric
                                   {:external_id "a"} {:limit 1}))))]
        (is (= "A2" (get-in row [:data :name])))))
    (testing "a serviced job pends no more — the next trigger mints fresh"
      (let [resp2 (req :post "/api/-/mirrors/sync_metrics/resync")]
        (is (= 202 (:status resp2)))
        (is (not= (:self job) (:self (json resp2))))
        ;; leave the suite clean: no queued sync job outlives its test
        (is (= 1 (mirror/service-sync-jobs! *eng* {:holder "test-daemon"})))))))

;; ── the discover flavor ─────────────────────────────────────────────

(deftest discover-flavor-mints-and-fills
  (seed! rm-gauge "g1" {:name "G1"})
  (let [resp (req :post "/api/-/mirrors/sync_gauges/discover")
        job (json resp)]
    (is (= 202 (:status resp)))
    (is (= "discover" (get-in job [:data :action])))
    (is (= 1 (mirror/service-sync-jobs! *eng* {:holder "test-daemon"})))
    (let [done (json (req :get (:self job)))]
      (is (= "completed" (:state done)))
      (is (= 1 (get-in done [:data :report :minted]))))
    (is (= "G1" (get-in (first (rows-of :sync_gauge)) [:data :name])))))

;; ── cancel: before the run starts, or not at all ────────────────────

(deftest cancel-before-service-wins
  (seed! rm-dial "d1" {:name "D1"})
  (let [job (json (req :post "/api/-/mirrors/sync_dials/resync"))
        before (pulls-of rm-dial)]
    (is (= 200 (:status (req :post (str (:self job) "/-/cancel")))))
    (testing "the service pass never sees a cancelled job"
      (is (= 0 (mirror/service-sync-jobs! *eng* {:holder "test-daemon"})))
      (is (= "cancelled" (:state (json (req :get (:self job))))))
      (is (= before (pulls-of rm-dial)) "the adapter was never consulted"))))

;; ── an unreachable feed: the failure IS the report ──────────────────

(deftest unreachable-feed-completes-with-the-failure-reported
  (seed! rm-flare "f1" {:name "F1"})
  (is (= 1 (mirror/discover! *eng* :sync_flare)))
  (swap! (:state rm-flare) assoc :down true)
  (let [job (json (req :post "/api/-/mirrors/sync_flares/resync"))]
    (is (= 1 (mirror/service-sync-jobs! *eng* {:holder "test-daemon"})))
    (let [done (json (req :get (:self job)))]
      (is (= "completed" (:state done))
          "resync is a heal, never a gate — no spinning retry")
      (is (str/includes? (get-in done [:data :report :error] "")
                         "unreachable")))))

;; ── the door's refusals ─────────────────────────────────────────────

(deftest the-door-refuses-what-it-must
  (testing "anonymous gets no operational lever"
    (is (= 401 (:status (anon-req :post "/api/-/mirrors/sync_metrics/resync")))))
  (testing "a kind that holds its own truth has no sync passes"
    (is (= 404 (:status (req :post "/api/-/mirrors/jobs/resync")))))
  (testing "an unknown flavor is no route"
    (is (= 404 (:status (req :post "/api/-/mirrors/sync_metrics/refresh")))))
  (testing "an unknown collection is no route"
    (is (= 404 (:status (req :post "/api/-/mirrors/nope/resync"))))))
