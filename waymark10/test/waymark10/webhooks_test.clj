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
            [waymark10.schema :as schema]
            [waymark10.server.engine :as engine]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.server.webhooks :as webhooks]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["wh_gizmos" "wh_asks" "wh_seats" "wh_changes" "seats" "domains" "models"
   "sittings" "subscriptions" "secrets" "jobs" "definitions"
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

;; ── 2a. a subscription that leaves active in the middle of a drain ──

(deftest a-drain-stops-when-the-subscription-is-no-longer-active
  (fresh!)
  (with-eng {:webhook-attempts 2 :webhook-backoff-ms 5}
    (fn [eng]
      (let [hits (atom [])
            sub-id (promise)
            ;; the owner pauses the subscription while the first delivery
            ;; is still being answered: the drain has read both events
            server (http/run-server
                    (fn [req]
                      (swap! hits conj (slurp (:body req)))
                      (when (= 1 (count @hits))
                        (inv/invoke! eng :subscription @sub-id :pause nil
                                     {:principal elena}))
                      {:status 200 :headers {} :body ""})
                    {:port 0 :legacy-return-value? false})
            url (str "http://127.0.0.1:" (http/server-port server) "/hook")]
        (try
          (let [{sub :row} (inv/create! eng :subscription
                                        {:url url :kinds ["wh_gizmo"]}
                                        {:principal elena})
                _ (deliver sub-id (:id sub))
                {g :row} (inv/create! eng :wh_gizmo {:name "twice"}
                                      {:principal elena})]
            (inv/invoke! eng :wh_gizmo (:id g) :spin nil {:principal elena})
            (webhooks/drain! eng)
            (testing "the event read before the pause is not sent after it"
              (is (= ["create"] (mapv #(:action (wire/read-json %)) @hits)))
              (is (= :paused (sub-state eng (:id sub)))))
            (testing "the cursor stayed: a resume delivers it"
              (inv/invoke! eng :subscription (:id sub) :resume nil
                           {:principal elena})
              (webhooks/drain! eng)
              (is (= ["create" "spin"]
                     (mapv #(:action (wire/read-json %)) @hits)))))
          (finally (engine/stop! server)))))))

;; ── 2b. skip_actors, and who made the move ──────────────────────────

(deftest skip-actors-and-the-actor-name
  (fresh!)
  (with-eng {:webhook-attempts 2 :webhook-backoff-ms 5}
    (fn [eng]
      (let [rcv (receiver!)
            payloads #(mapv (comp wire/read-json :body) @(:hits rcv))]
        (try
          ;; two rows stand in for two seats: an actor's address is
          ;; `kind:id`, and its name is that row's own
          (let [{mayor :row} (inv/create! eng :wh_gizmo {:name "mayor"}
                                          {:principal elena})
                {coder :row} (inv/create! eng :wh_gizmo {:name "coder"}
                                          {:principal elena})
                address #(str "wh_gizmo:" (:id %))
                as #(t/principal {:id (address %)})
                {sub :row} (inv/create! eng :subscription
                                        {:url (:url rcv)
                                         :kinds ["wh_gizmo"]
                                         :skip_actors [(address mayor)]}
                                        {:principal elena})]
            (inv/create! eng :wh_gizmo {:name "own"} {:principal (as mayor)})
            (inv/create! eng :wh_gizmo {:name "theirs"} {:principal (as coder)})
            (webhooks/drain! eng)
            (testing "a skipped actor's move is not delivered; another's is,
                      and names who made it"
              (is (= 1 (count @(:hits rcv))))
              (let [payload (first (payloads))]
                (is (= (address coder) (get-in payload [:actor :id])))
                (is (= "coder" (:actor_name payload)))))
            (testing "the skipped move is passed, not held: a second drain
                      sends nothing"
              (webhooks/drain! eng)
              (is (= 1 (count @(:hits rcv)))))
            (testing "an actor that names no row is named by its display"
              (inv/create! eng :wh_gizmo {:name "hers"} {:principal elena})
              (webhooks/drain! eng)
              (is (= 2 (count @(:hits rcv))))
              (is (= "Elena" (:actor_name (peek (payloads))))))
            (testing "restated to an empty list, it delivers everyone's"
              ;; restate is an edit door: it is fenced on the row's version
              (inv/invoke! eng :subscription (:id sub) :restate
                           {:skip_actors []}
                           {:principal elena
                            :if-match
                            (inv/etag
                             :subscription (:id sub)
                             (:version
                              (store/with-tx (:storage eng)
                                (fn [tx]
                                  (store/load-row (:storage eng) tx
                                                  :subscription (:id sub) {})))))})
              (inv/create! eng :wh_gizmo {:name "heard"}
                           {:principal (as mayor)})
              (webhooks/drain! eng)
              (is (= 3 (count @(:hits rcv))))
              (let [payload (peek (payloads))]
                (is (= (address mayor) (get-in payload [:actor :id])))
                (is (= "mayor" (:actor_name payload))))
              (is (= :active (sub-state eng (:id sub))))))
          (finally (engine/stop! (:server rcv))))))))

;; ── 2c. restate: what it hears changes, the cursor does not ─────────

(defn- restate!
  "Invoke an edit door on a subscription, fenced on the version it has."
  [eng id action input]
  (let [row (store/with-tx (:storage eng)
              (fn [tx] (store/load-row (:storage eng) tx :subscription id {})))]
    (inv/invoke! eng :subscription id action input
                 {:principal elena
                  :if-match (inv/etag :subscription id (:version row))})))

(defn- cursor-of [eng id]
  (store/with-tx (:storage eng)
    #(store/cursor-get (:storage eng) % (str "webhook:" id))))

(deftest a-restate-keeps-the-cursor
  (fresh!)
  (with-eng {:webhook-attempts 2 :webhook-backoff-ms 5}
    (fn [eng]
      (let [rcv (receiver!)
            moved (receiver!)
            actors (fn [r] (mapv #(get-in (wire/read-json (:body %))
                                          [:actor :id])
                                 @(:hits r)))]
        (try
          (let [{mayor :row} (inv/create! eng :wh_gizmo {:name "mayor"}
                                          {:principal elena})
                {coder :row} (inv/create! eng :wh_gizmo {:name "coder"}
                                          {:principal elena})
                address #(str "wh_gizmo:" (:id %))
                as #(t/principal {:id (address %)})
                {sub :row} (inv/create! eng :subscription
                                        {:url (:url rcv)
                                         :kinds ["wh_gizmo"]}
                                        {:principal elena})
                stored #(store/with-tx (:storage eng)
                          (fn [tx] (store/load-row (:storage eng) tx
                                                   :subscription (:id sub) {})))]
            (webhooks/drain! eng)
            (is (= 0 (count @(:hits rcv))))
            (inv/create! eng :wh_gizmo {:name "a"} {:principal (as mayor)})
            (let [before (cursor-of eng (:id sub))]
              (restate! eng (:id sub) :restate
                        {:skip_actors [(address coder)]})
              (testing "the restate leaves the cursor where it was"
                (is (some? before))
                (is (= before (cursor-of eng (:id sub)))))
              (testing "an omitted field keeps its value"
                (is (= ["wh_gizmo"] (get-in (stored) [:data :kinds])))
                (is (= (:url rcv) (get-in (stored) [:data :url])))))
            (inv/create! eng :wh_gizmo {:name "b"} {:principal (as coder)})
            (inv/create! eng :wh_gizmo {:name "c"} {:principal (as mayor)})
            (webhooks/drain! eng)
            (testing "the move made before the restate is still delivered,
                      and the next delivery honours the new list"
              (is (= [(address mayor) (address mayor)] (actors rcv))))
            (inv/invoke! eng :subscription (:id sub) :pause nil
                         {:principal elena})
            (inv/create! eng :wh_gizmo {:name "d"} {:principal (as mayor)})
            (restate! eng (:id sub) :restate_paused
                      {:url (:url moved)
                       :secret "whsec-restated"
                       :skip_actors nil})
            (testing "a paused subscription is restated and stays paused"
              (is (= :paused (sub-state eng (:id sub)))))
            (testing "a literal key is held as at create: marked, not shown"
              (is (= webhooks/literal-mark (get-in (stored) [:data :secret])))
              (is (not (str/includes?
                        (pr-str (store/with-tx (:storage eng)
                                  (fn [tx]
                                    (store/transitions
                                     (:storage eng) tx
                                     {:kind :subscription
                                      :resource-id (:id sub)}
                                     {:limit 50}))))
                        "whsec-restated"))))
            (inv/invoke! eng :subscription (:id sub) :resume nil
                         {:principal elena})
            (inv/create! eng :wh_gizmo {:name "e"} {:principal (as coder)})
            (webhooks/drain! eng)
            (testing "the new url and key take effect from the next delivery,
                      which starts where the cursor was"
              (is (= 2 (count @(:hits rcv))))
              (is (= [(address mayor) (address coder)] (actors moved)))
              (is (every? #(= (webhooks/sign "whsec-restated" (:body %))
                              (get-in % [:headers "x-waymark-signature"]))
                          @(:hits moved)))))
          (finally (engine/stop! (:server rcv))
                   (engine/stop! (:server moved))))))))

;; ── 2d. domains: a subscription keeps to the domains it names ───────

;; wh_ask keeps a domain's NAME as a ticket does: absent counts as
;; factory. wh_seat keeps a `domain` that is not a name and declares no
;; :absent-as, as a seat does.
(defn- domain-kind
  ([kind plural absent] (domain-kind kind plural absent :spin))
  ([kind plural absent action]
   (r/resource
    (cond-> {:kind kind
            :plural plural
            :states [:idle :spun]
            :initial :idle
            :terminal #{:spun}
            :summary "{data.name} · {state}"
            :schema [:map
                     [:name [:string {:min 1 :max 40}]]
                     [:domain {:optional true
                               :not-a-ref "A word the test writes, not a row id."}
                      [:maybe [:string {:max 40}]]]
                     [:requested_by {:optional true
                                     :not-a-ref "A domain's name, not a row id."}
                      [:maybe [:string {:max 40}]]]]
            :filterable {:state #{:eq :in} :domain #{:eq}}
            :actions {action {:from #{:idle} :to :spun
                             :safety {:idempotent true :reversible false
                                      :confirm false
                                      :one-way "Spun is history."}}}}
     absent (assoc :absent-as absent)))))

(def ^:private wh-ask (domain-kind :wh_ask "wh_asks" {:domain "factory"}))
(def ^:private wh-seat (domain-kind :wh_seat "wh_seats" nil))
;; a change as the filter knows one: it keeps a domain's name, and a
;; seat stalls it.
(def ^:private wh-change
  (domain-kind :change "wh_changes" {:domain "factory"} :stall))

(deftest a-subscription-keeps-to-its-domains
  (fresh!)
  (with-eng {:webhook-attempts 2 :webhook-backoff-ms 5
             :resources [gizmo wh-ask wh-seat]}
    (fn [eng]
      (let [rcv (receiver!)
            heard #(mapv (comp :summary wire/read-json :body) @(:hits rcv))
            born! (fn [kind data]
                    (:row (inv/create! eng kind data {:principal elena})))]
        (try
          (let [{sub :row} (inv/create! eng :subscription
                                        {:url (:url rcv)
                                         :domains ["factory"]}
                                        {:principal elena})]
            (born! :wh_ask {:name "unstamped"})
            (born! :wh_ask {:name "ours" :domain "factory"})
            (born! :wh_ask {:name "theirs" :domain "household"})
            (let [seat (born! :wh_seat {:name "seat" :domain "a-row-id"})]
              (born! :wh_gizmo {:name "plain"})
              (inv/invoke! eng :wh_seat (:id seat) :spin nil
                           {:principal elena}))
            (webhooks/drain! eng)
            (testing "a row with no stored domain and one stamped factory are
                      delivered, one stamped household is not, and a kind
                      that keeps no domain name passes"
              (is (= ["unstamped · Idle" "ours · Idle" "seat · Idle"
                      "plain · Idle" "seat · Spun"]
                     (heard))))
            (testing "the skipped row is passed, not held"
              (webhooks/drain! eng)
              (is (= 5 (count @(:hits rcv)))))
            (testing "it works alongside skip_actors"
              (restate! eng (:id sub) :restate {:skip_actors ["elena"]})
              (born! :wh_ask {:name "own" :domain "factory"})
              (webhooks/drain! eng)
              (is (= 5 (count @(:hits rcv)))))
            (testing "restated to another domain, it hears that one"
              (restate! eng (:id sub) :restate
                        {:skip_actors nil :domains ["household"]})
              (born! :wh_ask {:name "late"})
              (born! :wh_ask {:name "home" :domain "household"})
              (webhooks/drain! eng)
              (is (= "home · Idle" (peek (heard))))
              (is (= 6 (count @(:hits rcv)))))
            (testing "restated to none, it hears every domain"
              (restate! eng (:id sub) :restate {:domains nil})
              (born! :wh_ask {:name "any" :domain "household"})
              (born! :wh_ask {:name "all"})
              (webhooks/drain! eng)
              (is (= 8 (count @(:hits rcv))))
              (is (= :active (sub-state eng (:id sub))))))
          (finally (engine/stop! (:server rcv))))))))

;; ── 2e. domains: the asker's request, and a service seat's alerts ───

(deftest a-subscription-hears-asks-into-its-domains-and-its-service-seats
  (fresh!)
  (with-eng {:webhook-attempts 2 :webhook-backoff-ms 5
             :resources [gizmo wh-ask wh-change]}
    (fn [eng]
      (let [rcv (receiver!)
            heard #(mapv (comp :summary wire/read-json :body) @(:hits rcv))
            born! (fn [kind data]
                    (:row (inv/create! eng kind data {:principal elena})))
            ;; a seat that stores no domain is in factory
            seat! (fn [name' extra]
                    (born! :seat
                           (merge {:name name'
                                   :charter "Build what the house asks for."
                                   :scope [{:kind "model" :actions ["retire"]}]
                                   :standing_ttl_seconds 604800
                                   :cadence_seconds 3600
                                   :budget_usd_per_week 5M
                                   :sitting_budget_tokens 60000}
                                  extra)))
            stall! (fn [seat name']
                     (let [c (born! :change {:name name' :domain "household"})]
                       (inv/invoke! eng :change (:id c) :stall nil
                                    {:principal
                                     (t/principal {:id (str "seat:" (:id seat))
                                                   :type :agent
                                                   :display (str name')})})))
            infra (seat! "wh-infra" {:serves "any"})
            worker (seat! "wh-worker" {})]
        (try
          (let [{sub :row} (inv/create! eng :subscription
                                        {:url (:url rcv)
                                         :domains ["factory"]}
                                        {:principal elena})]
            (testing "a household row that factory asked for is delivered,
                      and one household asked for itself is not"
              (born! :wh_ask {:name "asked" :domain "household"
                              :requested_by "factory"})
              (born! :wh_ask {:name "theirs" :domain "household"
                              :requested_by "household"})
              (webhooks/drain! eng)
              (is (= ["asked · Idle"] (heard))))
            (testing "without service_alerts, a service seat's stall on
                      household work is not delivered"
              (stall! infra "quiet")
              (webhooks/drain! eng)
              (is (= ["asked · Idle"] (heard))))
            (testing "with service_alerts it is, and the stall of a seat
                      that serves its own domain is not"
              (restate! eng (:id sub) :restate {:service_alerts true})
              (stall! infra "loud")
              (stall! worker "own")
              (webhooks/drain! eng)
              (is (= ["asked · Idle" "loud · Spun"] (heard)))
              (is (= :active (sub-state eng (:id sub))))))
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

;; ── 5. the secret by reference: a secret row's id, never its value ──

(deftest a-secret-row-signs-by-reference
  (fresh!)
  (with-eng {:webhook-attempts 2 :webhook-backoff-ms 5}
    (fn [eng]
      (let [rcv (receiver!)
            sub-row (fn [id]
                      (store/with-tx (:storage eng)
                        (fn [tx] (store/load-row (:storage eng) tx
                                                 :subscription id {}))))
            spin! (fn [n]
                    (let [{g :row} (inv/create! eng :wh_gizmo {:name n}
                                                {:principal elena})]
                      (inv/invoke! eng :wh_gizmo (:id g) :spin nil
                                   {:principal elena})))
            replace! (fn [id value]
                       (let [row (store/with-tx (:storage eng)
                                   (fn [tx] (store/load-row (:storage eng) tx
                                                            :secret id {})))]
                         (inv/invoke! eng :secret id :replace {:value value}
                                      {:principal elena
                                       :if-match (inv/etag :secret id (:version row))})))
            signed-with? (fn [k hits]
                           (every? #(= (webhooks/sign k (:body %))
                                       (get-in % [:headers "x-waymark-signature"]))
                                   hits))]
        (try
          (let [{sec :row} (inv/create! eng :secret {:name "WH_TEST_SECRET"}
                                        {:principal elena})
                ref (str (:id sec))
                {sub :row} (inv/create! eng :subscription
                                        {:url (:url rcv)
                                         :kinds ["wh_gizmo"]
                                         :secret ref}
                                        {:principal elena})]
            (spin! "one")
            (webhooks/drain! eng)
            (testing "an unset row holds deliveries: not failed, not unsigned"
              (is (empty? @(:hits rcv)))
              (is (= :active (sub-state eng (:id sub))))
              (is (str/includes?
                   (str (get-in (sub-row (:id sub)) [:data :failure_reason]))
                   "holds no value yet")))
            (testing "the wait is said one time"
              (webhooks/drain! eng)
              (is (empty? @(:hits rcv)))
              (is (= 1 (count (filter #(= "await_secret" (name (:action %)))
                                      (store/with-tx (:storage eng)
                                        (fn [tx]
                                          (store/transitions
                                           (:storage eng) tx
                                           {:kind :subscription
                                            :resource-id (:id sub)}
                                           {:limit 50}))))))))
            (replace! ref "whsec-row-value-1")
            (webhooks/drain! eng)
            (testing "a reference signs with the row's value, and nothing was lost"
              (is (= ["create" "spin"]
                     (mapv #(:action (wire/read-json (:body %))) @(:hits rcv))))
              (is (signed-with? "whsec-row-value-1" @(:hits rcv)))
              (is (nil? (get-in (sub-row (:id sub)) [:data :failure_reason]))))
            (replace! ref "whsec-row-value-2")
            (spin! "two")
            (webhooks/drain! eng)
            (testing "a rotated row signs with the new value"
              (is (= 4 (count @(:hits rcv))))
              (is (signed-with? "whsec-row-value-2" (drop 2 @(:hits rcv)))))
            (testing "the subscription keeps the reference, never the value"
              (is (= ref (get-in (sub-row (:id sub)) [:data :secret])))))
          (finally (engine/stop! (:server rcv))))))))

;; ── 6. a literal key: it signs, and no read shows it ────────────────

(deftest a-literal-secret-is-never-shown
  (fresh!)
  (with-eng {:webhook-attempts 2 :webhook-backoff-ms 5}
    (fn [eng]
      (let [rcv (receiver!)
            literal "whsec-literal-1"]
        (try
          (let [{sub :row} (inv/create! eng :subscription
                                        {:url (:url rcv)
                                         :kinds ["wh_gizmo"]
                                         :secret literal
                                         :signing_key "whsec-not-this"}
                                        {:principal elena})
                stored (store/with-tx (:storage eng)
                         (fn [tx] (store/load-row (:storage eng) tx
                                                  :subscription (:id sub) {})))
                log (store/with-tx (:storage eng)
                      (fn [tx]
                        (store/transitions (:storage eng) tx
                                           {:kind :subscription
                                            :resource-id (:id sub)}
                                           {:limit 50})))]
            (testing "the field says a key is set, and holds no key"
              (is (= webhooks/literal-mark (get-in stored [:data :secret]))))
            (testing "the key is in a field no projection carries"
              (is (contains? (schema/secret-fields
                              (:schema webhooks/subscription))
                             :signing_key)))
            (testing "the create transition does not hold it"
              (is (= ["create"] (mapv #(name (:action %)) log)))
              (is (not (str/includes? (pr-str log) literal))))
            (inv/create! eng :wh_gizmo {:name "one"} {:principal elena})
            (webhooks/drain! eng)
            (testing "the deliveries are signed with the literal"
              (is (= 1 (count @(:hits rcv))))
              (is (every? #(= (webhooks/sign literal (:body %))
                              (get-in % [:headers "x-waymark-signature"]))
                          @(:hits rcv)))))
          (finally (engine/stop! (:server rcv))))))))

;; ── 7. the deliverer: an Error does not end it, and a slow endpoint
;;       does not hold the others ────────────────────────────────────

(defn- with-deliverer
  "Runs (f) beside a started deliverer whose only wake is a 20 ms poll:
  there is no dispatcher, so the log alone carries the events."
  [eng f]
  (with-redefs [events/subscribe (fn [_ _] ::no-dispatcher)
                events/unsubscribe (fn [_ _] nil)
                events/take-event (fn [_ ms] (Thread/sleep (long ms)) nil)]
    (let [deliverer (webhooks/start-deliverer! eng nil {:poll-ms 20})]
      (try (f)
           (finally (webhooks/stop-deliverer! deliverer))))))

(deftest the-deliverer-outlives-an-error
  (fresh!)
  (with-eng {:webhook-attempts 2 :webhook-backoff-ms 5}
    (fn [eng]
      (let [rcv (receiver!)
            active-subscriptions @#'webhooks/active-subscriptions
            drain-subscription @#'webhooks/drain-subscription!
            passes (atom 0)
            drains (atom 0)]
        (try
          (inv/create! eng :subscription
                       {:url (:url rcv) :kinds ["wh_gizmo"]}
                       {:principal elena})
          (inv/create! eng :wh_gizmo {:name "one"} {:principal elena})
          ;; the startup pass and the first pass of the loop throw an
          ;; Error; so does the first drain of the subscription
          (with-redefs [webhooks/active-subscriptions
                        (fn [e]
                          (if (< 2 (swap! passes inc))
                            (active-subscriptions e)
                            (throw (StackOverflowError.))))
                        webhooks/drain-subscription!
                        (fn [& args]
                          (if (< 1 (swap! drains inc))
                            (apply drain-subscription args)
                            (throw (AssertionError. "drain"))))]
            (with-deliverer eng
              (fn []
                (testing "the deliverer still delivers after the Errors"
                  (is (await-pred #(= 1 (count @(:hits rcv))) 5000))
                  (is (< 2 @passes))
                  (is (< 1 @drains))))))
          (finally (engine/stop! (:server rcv))))))))

(deftest a-slow-endpoint-does-not-hold-the-others
  (fresh!)
  ;; the slow endpoint costs 3 attempts x 2 s for each event
  (with-eng {:webhook-attempts 3 :webhook-backoff-ms 5
             :webhook-timeout-ms 2000}
    (fn [eng]
      (let [rcv (receiver!)
            slow-hits (atom 0)
            slow (http/run-server
                  (fn [_]
                    (swap! slow-hits inc)
                    (Thread/sleep 2500)
                    {:status 200 :headers {} :body ""})
                  {:port 0 :legacy-return-value? false})]
        (try
          (inv/create! eng :subscription
                       {:url (str "http://127.0.0.1:"
                                  (http/server-port slow) "/hook")
                        :kinds ["wh_gizmo"]
                        :delivery_policy "skip"}
                       {:principal elena})
          (inv/create! eng :subscription
                       {:url (:url rcv) :kinds ["wh_gizmo"]}
                       {:principal elena})
          (with-deliverer eng
            (fn []
              (inv/create! eng :wh_gizmo {:name "one"} {:principal elena})
              (testing "the healthy subscription hears the first event"
                (is (await-pred #(= 1 (count @(:hits rcv))) 1500)))
              (testing "the slow endpoint is being tried"
                (is (await-pred #(pos? @slow-hits) 1500)))
              (inv/create! eng :wh_gizmo {:name "two"} {:principal elena})
              (testing "the healthy subscription hears the second event
                        while the slow one is still at the first"
                (is (await-pred #(= 2 (count @(:hits rcv))) 1500))
                (is (<= @slow-hits 3)))))
          (finally (engine/stop! slow)
                   (engine/stop! (:server rcv))))))))
