(ns waymark10.webhooks-test
  "Phase-9b acceptance, part 1: webhooks. An in-process http-kit
  receiver plays the third party: delivery of matching transitions
  with the SSE data shape and the hex HMAC signature, the cursor's
  resume across a deliverer restart, the failure transition after the
  declared attempts (cursor parked, resume replays), and one live
  end-to-end pass over a started engine. Suite-local kind; real
  Postgres."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
  ["wh_gizmos" "subscriptions" "jobs" "definitions"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_cursors"])

(defn- fresh! []
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st)))))

(def ^:private gizmo
  (r/resource
   {:kind :wh_gizmo
    :plural "wh_gizmos"
    :states [:idle :spun]
    :initial :idle
    :terminal #{:spun}
    :summary "{data.name} · {state}"
    :schema [:map [:name [:string {:min 1 :max 40}]]]
    :actions {:spin {:from #{:idle} :to :spun
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "Spun is history."}}}}))

(defn- with-eng [opts f]
  (let [st (pg/storage db/dsn)]
    (try
      (f (engine/engine (merge {:storage st :resources [gizmo]} opts)))
      (finally (pg/close! st)))))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- receiver!
  "An in-process endpoint capturing every POST; answers with @status."
  []
  (let [hits (atom [])
        status (atom 200)
        server (http/run-server
                (fn [req]
                  (swap! hits conj {:headers (:headers req)
                                    :body (slurp (:body req))})
                  {:status @status :headers {} :body ""})
                {:port 0 :legacy-return-value? false})]
    {:hits hits :status status :server server
     :url (str "http://127.0.0.1:" (http/server-port server) "/hook")}))

(defn- await-pred
  "Poll until (pred) is truthy or the timeout; returns the value."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 50)
            (recur))))))

(defn- sub-state [eng id]
  (:state (store/with-tx (:storage eng)
            (fn [tx] (store/load-row (:storage eng) tx :subscription id {})))))

;; ── 1. delivery, shape, signature, filter, resume across restart ────

(deftest delivery-signature-and-cursor
  (fresh!)
  (with-eng {:webhook-attempts 2 :webhook-backoff-ms 5}
    (fn [eng]
      (let [rcv (receiver!)]
        (try
          ;; a transition BEFORE the subscription exists is never heard
          (let [{g1 :row} (inv/create! eng :wh_gizmo {:name "before"}
                                       {:principal elena})
                {sub :row} (inv/create! eng :subscription
                                        {:url (:url rcv)
                                         :kinds ["wh_gizmo"]
                                         :secret "whsec-test-1"}
                                        {:principal elena})
                _ (inv/invoke! eng :wh_gizmo (:id g1) :spin nil
                               {:principal elena})]
            (webhooks/drain! eng)
            (testing "exactly the post-subscription transition delivers"
              (is (= 1 (count @(:hits rcv))))
              (let [{:keys [headers body]} (first @(:hits rcv))
                    payload (wire/read-json body)]
                (is (= "wh_gizmo" (:kind payload)))
                (is (= "spin" (:action payload)))
                (is (= (str "/api/wh_gizmos/" (:id g1)) (:self payload)))
                (is (= "idle" (:from payload)))
                (is (= "spun" (:to payload)))
                (is (= "elena" (get-in payload [:actor :id])))
                (testing "the signature is hex hmac-sha256(secret, body)"
                  (is (= (webhooks/sign "whsec-test-1" body)
                         (get headers "x-waymark-signature"))))
                (is (string? (get headers "x-waymark-event-id")))))
            (testing "a second drain replays nothing — the cursor advanced"
              (webhooks/drain! eng)
              (is (= 1 (count @(:hits rcv)))))
            (testing "the cursor survives a deliverer restart (fresh
                      engine, same database): only NEW transitions
                      deliver"
              (with-eng {:webhook-attempts 2 :webhook-backoff-ms 5}
                (fn [eng2]
                  (webhooks/drain! eng2)
                  (is (= 1 (count @(:hits rcv))) "nothing replayed")
                  (let [{g2 :row} (inv/create! eng2 :wh_gizmo {:name "after"}
                                               {:principal elena})]
                    (inv/invoke! eng2 :wh_gizmo (:id g2) :spin nil
                                 {:principal elena})
                    (webhooks/drain! eng2)
                    (is (= 3 (count @(:hits rcv)))
                        "the create and the spin, delivered once each")
                    (is (= ["create" "spin"]
                           (mapv #(:action (wire/read-json (:body %)))
                                 (drop 1 @(:hits rcv)))))))))
            (testing "subscription bookkeeping is never delivered"
              (is (not-any? #(= "subscription"
                                (:kind (wire/read-json (:body %))))
                            @(:hits rcv))))
            (is (= :active (sub-state eng (:id sub)))))
          (finally (engine/stop! (:server rcv))))))))

;; ── 2. an unsigned subscription and the kind filter ─────────────────

(deftest kind-filter-and-optional-secret
  (fresh!)
  (with-eng {:webhook-attempts 2 :webhook-backoff-ms 5}
    (fn [eng]
      (let [rcv (receiver!)]
        (try
          (inv/create! eng :subscription
                       {:url (:url rcv) :kinds ["member"]}
                       {:principal elena})
          (let [{g :row} (inv/create! eng :wh_gizmo {:name "quiet"}
                                      {:principal elena})]
            (inv/invoke! eng :wh_gizmo (:id g) :spin nil {:principal elena}))
          (webhooks/drain! eng)
          (is (empty? @(:hits rcv)) "no wh_gizmo event for a member filter")
          ;; a member transition (elena auto-provisions via the gate is
          ;; not in play here — mint one directly)
          (inv/create! eng :member {:display "Elena" :actor_type "human"}
                       {:principal (t/principal {:id "reg" :type :system})})
          (webhooks/drain! eng)
          (is (await-pred #(= 1 (count @(:hits rcv))) 2000))
          (testing "no secret, no signature header"
            (is (nil? (get-in (first @(:hits rcv))
                              [:headers "x-waymark-signature"]))))
          (finally (engine/stop! (:server rcv))))))))

;; ── 3. failure: bounded retries, then the subscription fails ────────

(deftest failure-parks-the-cursor
  (fresh!)
  (with-eng {:webhook-attempts 2 :webhook-backoff-ms 5}
    (fn [eng]
      (let [rcv (receiver!)]
        (try
          (reset! (:status rcv) 500)
          (let [{sub :row} (inv/create! eng :subscription
                                        {:url (:url rcv)
                                         :kinds ["wh_gizmo"]
                                         :secret "whsec-test-2"}
                                        {:principal elena})
                {g :row} (inv/create! eng :wh_gizmo {:name "grumpy"}
                                      {:principal elena})]
            (inv/invoke! eng :wh_gizmo (:id g) :spin nil {:principal elena})
            (webhooks/drain! eng)
            (testing "after N refusals the subscription is failed, logged"
              (is (= 2 (count @(:hits rcv))) "exactly the declared attempts")
              (is (= :failed (sub-state eng (:id sub))))
              (let [row (store/with-tx (:storage eng)
                          (fn [tx] (store/load-row (:storage eng) tx
                                                   :subscription (:id sub) {})))]
                (is (str/includes?
                     (get-in row [:data :failure_reason] "")
                     "failed after 2 attempts"))))
            (testing "a failed subscription stops delivering"
              (webhooks/drain! eng)
              (is (= 2 (count @(:hits rcv)))))
            (testing "resume replays from the parked cursor — nothing lost"
              (reset! (:status rcv) 200)
              (inv/invoke! eng :subscription (:id sub) :resume nil
                           {:principal elena})
              (webhooks/drain! eng)
              (is (= :active (sub-state eng (:id sub))))
              (let [delivered (drop 2 @(:hits rcv))]
                (is (= ["create" "spin"]
                       (mapv #(:action (wire/read-json (:body %)))
                             delivered))
                    "the refused event and its successor both land"))))
          (finally (engine/stop! (:server rcv))))))))

;; ── 4. the live deliverer on a started engine ───────────────────────

(deftest live-deliverer-end-to-end
  (fresh!)
  (with-eng {:webhook-attempts 2 :webhook-backoff-ms 5
             :webhooks-poll-ms 200 :events-poll-ms 200}
    (fn [eng]
      (let [rcv (receiver!)
            server (engine/start! eng 0)]
        (try
          (inv/create! eng :subscription
                       {:url (:url rcv) :kinds ["wh_gizmo"]
                        :secret "whsec-live"}
                       {:principal elena})
          (let [{g :row} (inv/create! eng :wh_gizmo {:name "live"}
                                      {:principal elena})]
            (inv/invoke! eng :wh_gizmo (:id g) :spin nil {:principal elena})
            (is (await-pred #(some (fn [h]
                                     (= "spin" (:action (wire/read-json (:body h)))))
                                   @(:hits rcv))
                            15000)
                "the deliverer rides the dispatcher to the receiver")
            (let [hit (first (filter #(= "spin" (:action (wire/read-json (:body %))))
                                     @(:hits rcv)))]
              (is (= (webhooks/sign "whsec-live" (:body hit))
                     (get-in hit [:headers "x-waymark-signature"])))))
          (finally
            (engine/stop! eng server)
            (engine/stop! (:server rcv))))))))
