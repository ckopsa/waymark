(ns waymark10.batch-b-members-test
  "Batch B acceptance — invite → bind membership over the real ring
  handler: an admin's token-bearing create is born :invited; the
  first authenticated principal presenting the token (X-Waymark-
  Invite) binds through the concealed :bind (registrar system actor,
  logged); the second binder is refused; an invited-only engine
  ({:members :invited-only}) refuses unknown principals instead of
  auto-provisioning, while the default engine keeps provisioning.
  Needs the batch database; export
  WAYMARK10_TEST_DSN=jdbc:postgresql://localhost:5433/waymark10_b_test?user=ckopsa"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.members :as members]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.conformance :as conf]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]))

(def ^:dynamic *eng* nil)         ; the default-mode engine
(def ^:dynamic *h* nil)           ; …and its handler
(def ^:dynamic *hi* nil)          ; the SAME engine, invited-only

(def ^:private tables
  ["meals" "plans" "members" "roles" "grants" "approval_requests"
   "attachments" "subscriptions" "jobs" "definitions"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"
   "waymark10_cursors" "waymark10_job_leases"])

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [eng (engine/engine {:storage st :resources [fx/meal]})]
          (binding [*eng* eng
                    *h* (engine/handler eng)
                    ;; the mode is one engine key ({:members
                    ;; :invited-only}); the whitelist line in
                    ;; engine/engine is the recorded one-line merge item
                    *hi* (engine/handler (assoc eng :members :invited-only))]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(defn- headers-for
  ([id] (headers-for id nil))
  ([id extra] (merge {"x-waymark-principal" id} extra)))

(def ^:private admin
  ;; system principals bypass the gate — the bootstrap admin of an
  ;; invited-only engine is the operator's own actor
  (headers-for "admin" {"x-waymark-actor-type" "system"}))

(defn- req*
  [h method uri body headers]
  (h (cond-> {:request-method method :uri uri :headers (or headers {})}
       body (assoc :body (wire/write-json body)))))

(defn- invited [method uri & [body headers]] (req* *hi* method uri body headers))
(defn- default [method uri & [body headers]] (req* *h* method uri body headers))

(defn- json [resp] (some-> (:body resp) (#(if (string? %) % (slurp %)))
                           wire/read-json))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))

(def ^:private token "tok-priya-8827")

(defn- invite!
  "The admin's invite: display + binding token (+ roles), born :invited."
  [display tok roles]
  (let [resp (invited :post "/api/members"
                      (cond-> {:display display :actor_type "human"
                               :bind_token tok}
                        roles (assoc :roles roles))
                      admin)]
    (is (= 201 (:status resp)))
    (is (= "invited" (:state (json resp))))
    (is (= "admin" (get-in (json resp) [:data :invited_by]))
        "the inviter is recorded at birth")
    (id-of resp)))

;; ── the flow ────────────────────────────────────────────────────────

(deftest invited-only-binds-once
  (is (= 201 (:status (invited :post "/api/roles" {:name "planner"} admin))))
  (let [mid (invite! "Priya" token ["planner"])]
    (testing "an unknown principal without a token is refused — membership
              is the admin's to extend, not the resolver's"
      (let [resp (invited :get "/api/meals" nil (headers-for "stranger"))
            b (json resp)]
        (is (= 403 (:status resp)))
        (is (str/ends-with? (str (:type b)) "membership-invited"))
        (let [vs (conf/problem-violations (:status resp)
                                          (get-in resp [:headers "Content-Type"])
                                          b {:where "invited-only stranger"})]
          (is (empty? vs) (str/join "\n" vs)))))
    (testing "first authenticated sight with the token binds: token → id
              claim, an audited transition by the registrar"
      (let [resp (invited :get "/api/meals" nil
                          (headers-for "priya-oidc-sub"
                                       {"x-waymark-invite" token}))]
        (is (= 200 (:status resp))))
      (let [env (json (invited :get (str "/api/members/" mid) nil admin))]
        (is (= "active" (:state env)))
        (is (= "priya-oidc-sub" (get-in env [:data :subject]))))
      (let [ts (store/with-tx (:storage *eng*)
                 (fn [tx] (store/transitions (:storage *eng*) tx
                                             {:kind :member
                                              :resource-id mid} {})))
            bind (first (filter #(= :bind (:action %)) ts))]
        (is (some? bind))
        (is (= (:id members/registrar) (get-in bind [:actor :id])))))
    (testing "the bound member needs no token afterwards, and carries the
              invite's roles"
      (is (= 200 (:status (invited :get "/api/meals" nil
                                   (headers-for "priya-oidc-sub")))))
      (is (contains? (:roles (members/gate!
                              (assoc *eng* :members :invited-only)
                              (t/principal {:id "priya-oidc-sub"})))
                     "planner")))
    (testing "the second binder is refused: the token is spent"
      (let [resp (invited :get "/api/meals" nil
                          (headers-for "mallory"
                                       {"x-waymark-invite" token}))]
        (is (= 403 (:status resp)))
        (is (str/ends-with? (str (:type (json resp))) "membership-invited"))))
    (testing "suspension gates the bound identity like any member"
      (is (= 200 (:status (invited :post (str "/api/members/" mid "/-/suspend")
                                   nil admin))))
      (let [resp (invited :get "/api/meals" nil (headers-for "priya-oidc-sub"))]
        (is (= 403 (:status resp)))
        (is (str/ends-with? (str (:type (json resp))) "member-suspended")))
      (is (= 200 (:status (invited :post (str "/api/members/" mid "/-/reinstate")
                                   nil admin)))))))

(deftest bind-is-concealed-and-auto-provision-stays-the-default
  (let [mid (invite! "Sam" "tok-sam-9911" nil)]
    (testing "bind is the registrar's, concealed: absent from a human's
              envelope, 404 when invoked by hand"
      ;; the DEFAULT handler serves the same storage; a human admin
      ;; reads the invited row there without tripping the invited gate
      (let [env (json (default :get (str "/api/members/" mid) nil
                               (headers-for "root")))]
        (is (= "invited" (:state env)))
        (is (not (contains? (:actions env) :bind)))
        (is (not (contains? (:unavailable env) :bind))))
      (is (= 404 (:status (default :post (str "/api/members/" mid "/-/bind")
                                   {:subject "by-hand"}
                                   (headers-for "root"))))))
    (testing "binding works under the default mode too — the mode only
              decides the unknown-principal fallback"
      (is (= 200 (:status (default :get "/api/meals" nil
                                   (headers-for "sam-sub"
                                                {"x-waymark-invite" "tok-sam-9911"})))))
      (is (= "active" (:state (json (default :get (str "/api/members/" mid)
                                             nil (headers-for "root")))))))
    (testing "auto-provision remains the default for unknowns"
      (is (= 200 (:status (default :get "/api/meals" nil (headers-for "walkin")))))
      (is (= 200 (:status (default :get "/api/members/walkin" nil
                                   (headers-for "root"))))))))
