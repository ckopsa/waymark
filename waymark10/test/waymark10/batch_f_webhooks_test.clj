(ns waymark10.batch-f-webhooks-test
  "Batch F, deliverable 3: webhook completeness. :revoked is terminal
  and owner-gated (waymark9's state, ported); :delivery_policy picks
  the exhausted-delivery posture per subscription — \"fail\" (default,
  the v10 discipline: mark failed, park the cursor) or \"skip\"
  (waymark9's liveness posture: log, advance, stay active). Real
  Postgres (WAYMARK10_TEST_DSN); an in-process http-kit receiver
  plays the third party."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [org.httpkit.server :as http]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.server.webhooks :as webhooks]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["f_widgets" "subscriptions" "jobs" "definitions" "members" "roles"
   "grants" "attachments" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_cursors"])

(defn- fresh! []
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st)))))

(def ^:private widget
  (r/resource
   {:kind :f_widget
    :plural "f_widgets"
    :states [:idle :spun]
    :initial :idle
    :terminal #{:spun}
    :summary "{data.name} · {state}"
    :schema [:map [:name [:string {:min 1 :max 40}]]]
    :actions {:spin {:from #{:idle} :to :spun
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "Spun is history."}}}}))

(defn- with-eng [f]
  (let [st (pg/storage db/dsn)]
    (try
      (f (engine/engine {:storage st :resources [widget]
                         :webhook-attempts 2 :webhook-backoff-ms 5}))
      (finally (pg/close! st)))))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))
(def ^:private priya (t/principal {:id "priya" :display "Priya"}))

(defn- receiver! []
  (let [hits (atom [])
        status (atom 200)
        server (http/run-server
                (fn [req]
                  (swap! hits conj {:body (slurp (:body req))})
                  {:status @status :headers {} :body ""})
                {:port 0 :legacy-return-value? false})]
    {:hits hits :status status :server server
     :url (str "http://127.0.0.1:" (http/server-port server) "/hook")}))

(defn- sub-row [eng id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx :subscription id {}))))

(defn- spin! [eng nm]
  (let [{g :row} (inv/create! eng :f_widget {:name nm} {:principal elena})]
    (inv/invoke! eng :f_widget (:id g) :spin nil {:principal elena})))

(defn- problem-of [thunk]
  (try (thunk) nil (catch Exception e (ex-data e))))

;; ── 1. revoked: terminal, owner-gated ───────────────────────────────

(deftest revoke-is-owner-gated-and-terminal
  (fresh!)
  (with-eng
    (fn [eng]
      (let [rcv (receiver!)]
        (try
          (let [{sub :row} (inv/create! eng :subscription
                                        {:url (:url rcv)
                                         :kinds ["f_widget"]}
                                        {:principal elena})]
            (testing "another principal's revoke refuses with the guard's sentence"
              (let [p (problem-of #(inv/invoke! eng :subscription (:id sub)
                                                :revoke nil
                                                {:principal priya}))]
                (is (= :guard-refused (:waymark10/problem p)))
                (is (= "Only the subscription's owner may revoke it — pause it instead."
                       (:detail p)))))
            (testing "the owner revokes; the state is terminal"
              (let [{row :row} (inv/invoke! eng :subscription (:id sub)
                                            :revoke nil {:principal elena})]
                (is (= :revoked (:state row))))
              (let [p (problem-of #(inv/invoke! eng :subscription (:id sub)
                                                :resume nil
                                                {:principal elena}))]
                (is (= 409 (:status p)) "revoked does not resume")))
            (testing "a revoked subscription never hears another event"
              (spin! eng "after-revoke")
              (webhooks/drain! eng)
              (is (empty? @(:hits rcv)))))
          (finally (engine/stop! (:server rcv))))))))

;; ── 2. delivery policy: skip advances, stays active ─────────────────

(deftest skip-policy-advances-past-the-refusing-event
  (fresh!)
  (with-eng
    (fn [eng]
      (let [rcv (receiver!)]
        (try
          (reset! (:status rcv) 500)
          (let [{sub :row} (inv/create! eng :subscription
                                        {:url (:url rcv)
                                         :kinds ["f_widget"]
                                         :delivery_policy "skip"}
                                        {:principal elena})]
            (spin! eng "grumpy")
            (webhooks/drain! eng)
            (testing "exhausted retries SKIP: the subscription stays active"
              (is (= :active (:state (sub-row eng (:id sub)))))
              (is (pos? (count @(:hits rcv))) "the endpoint was tried"))
            (testing "the cursor advanced: recovery delivers only NEW events"
              (reset! (:status rcv) 200)
              (let [before (count @(:hits rcv))]
                (spin! eng "sunny")
                (webhooks/drain! eng)
                (let [delivered (drop before @(:hits rcv))]
                  (is (pos? (count delivered)))
                  (is (every? #(let [b (wire/read-json (:body %))]
                                 (not= "grumpy"
                                       (some-> (:summary b) (subs 0 6))))
                              delivered)
                      "the skipped events never redeliver")
                  (is (some #(= "spin" (:action (wire/read-json (:body %))))
                            delivered))))))
          (finally (engine/stop! (:server rcv))))))))

;; ── 3. the default is still the fail posture ────────────────────────

(deftest default-policy-still-fails-and-parks
  (fresh!)
  (with-eng
    (fn [eng]
      (let [rcv (receiver!)]
        (try
          (reset! (:status rcv) 500)
          (let [{sub :row} (inv/create! eng :subscription
                                        {:url (:url rcv)
                                         :kinds ["f_widget"]}
                                        {:principal elena})]
            (spin! eng "grumpy")
            (webhooks/drain! eng)
            (testing "no declared policy: the subscription fails, cursor parked"
              (let [row (sub-row eng (:id sub))]
                (is (= :failed (:state row)))
                (is (some? (get-in row [:data :failure_reason]))))))
          (finally (engine/stop! (:server rcv))))))))
