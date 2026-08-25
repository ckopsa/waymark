(ns waymark10.phase9a-test
  "Phase 9a acceptance: identity and access over the real ring
  handler — members (auto-provision + the suspension gate), the OIDC
  bearer resolver (locally-minted RSA keypair, static JWKS, no
  network), grants (the scoped surface: concealment in envelopes,
  404s on the wire, the narrowed collection), and attachments (byte
  round-trip, the size cap). One engine, one handler: the OIDC config
  keeps the dev-header fallback, so every flow shares the fixture.
  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [buddy.sign.jwt :as jwt]
            [buddy.core.keys :as bkeys]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.members :as members]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.conformance :as conf]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)))

;; ── the local IdP: one RSA keypair, one static JWKS ─────────────────

(defn- rsa-keypair []
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private keypair (rsa-keypair))
(def ^:private stranger-keypair (rsa-keypair))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "waymark10-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "test-key" :alg "RS256" :use "sig")]})

(defn- now-secs [] (quot (System/currentTimeMillis) 1000))

(defn- mint
  "A signed JWT: claims over sane defaults; opts {:kid … :keypair …}."
  [claims & [{:keys [kid kp] :or {kid "test-key" kp keypair}}]]
  (jwt/sign (merge {:iss issuer :aud audience :exp (+ (now-secs) 600)}
                   claims)
            (.getPrivate kp)
            {:alg :rs256 :header {:kid kid}}))

;; ── the world ───────────────────────────────────────────────────────

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(def ^:private attachment-dir "target/test-attachments-9a")

(def ^:private tables
  ["meals" "plans" "members" "roles" "grants" "attachments" "definitions"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"])

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [dir (io/file attachment-dir)]
          (when (.isDirectory dir)
            (doseq [^java.io.File g (.listFiles dir)] (.delete g))))
        (let [eng (engine/engine {:storage st
                                  :resources [fx/meal fx/plan]
                                  :oidc {:issuer issuer
                                         :audience audience
                                         :jwks jwks
                                         :roles-claim :roles}
                                  :attachment-dir attachment-dir
                                  :attachment-max-bytes 1024})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(defn- dev-headers
  ([id] (dev-headers id nil))
  ([id extra] (merge {"x-waymark-principal" id} extra)))

(def ^:private root (dev-headers "root"))

;; assigning roles is a recovery-admin's authority (waymark-du2); an
;; admin authenticates WITH the role (prod: the Keycloak token roles
;; claim; here: the dev-header roles), never from a bare assign_roles
(def ^:private admin (dev-headers "root" {"x-waymark-roles" "recovery-admin"}))

(defn- req
  ([method uri] (req method uri nil root))
  ([method uri body] (req method uri body root))
  ([method uri body headers]
   (*h* (cond-> {:request-method method :uri uri :headers (or headers {})}
          body (assoc :body (if (string? body) body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) (#(if (string? %) % (slurp %)))
                           wire/read-json))
(defn- ctype [resp] (get-in resp [:headers "Content-Type"]))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))

(defn- etag-of [uri headers]
  (get-in (req :get uri nil headers) [:headers "ETag"]))

(def ^:private covered-plan
  {:start_date "2025-01-06" :weeks 1
   :days [{:date "2025-01-06" :eating_out true}
          {:date "2025-01-07" :eating_out true}]})

;; ── 1. members: auto-provision on first sight, logged ───────────────

(deftest member-auto-provision
  (let [_ (req :get "/api/meals" nil (dev-headers "priya"))
        resp (req :get "/api/members/priya")
        env (json resp)]
    (is (= 200 (:status resp)) "first sight minted the member row")
    (is (= "active" (:state env)))
    (is (= "priya" (get-in env [:data :display])))
    (is (= "human" (get-in env [:data :actor_type])))
    (testing "the provision is a logged create by the registrar"
      (let [[create] (store/with-tx (:storage *eng*)
                       (fn [tx] (store/transitions (:storage *eng*) tx
                                                   {:kind :member
                                                    :resource-id "priya"} {})))]
        (is (= :create (:action create)))
        (is (= (:id members/registrar) (get-in create [:actor :id])))))
    (testing "system principals are never provisioned"
      (req :get "/api/meals" nil (dev-headers "deploy-bot"
                                              {"x-waymark-actor-type" "system"}))
      (is (= 404 (:status (req :get "/api/members/deploy-bot")))))))

;; ── 2. members: the suspension gate ─────────────────────────────────

(deftest member-suspension-gates
  (let [sam (dev-headers "sam")
        _ (req :get "/api/meals" nil sam)
        _ (is (= 200 (:status (req :post "/api/members/sam/-/suspend"))))]
    (testing "a suspended member's every request is one 403 problem"
      (doseq [[who resp] [["GET collection" (req :get "/api/meals" nil sam)]
                          ["POST create" (req :post "/api/plans" covered-plan sam)]
                          ["GET well-known" (req :get "/api/.well-known/waymark"
                                                 nil sam)]]]
        (let [vs (conf/suspension-violations (:status resp) (ctype resp)
                                             (json resp) {:where who})]
          (is (empty? vs) (str/join "\n" vs)))))
    (testing "reinstate lifts the gate"
      (is (= 200 (:status (req :post "/api/members/sam/-/reinstate"))))
      (is (= 200 (:status (req :get "/api/meals" nil sam)))))))

;; ── 3. roles: the registry and the member's held roles ──────────────

(deftest roles-registry
  (let [made (req :post "/api/roles" {:name "admin"})]
    (is (= 201 (:status made)))
    (testing "one spelling per role: the second admin refuses at create"
      (let [again (req :post "/api/roles" {:name "admin"})]
        (is (= 409 (:status again)))
        (is (str/includes? (:detail (json again)) "already exists"))))
    (testing "an unregistered role refuses at assignment, naming it"
      (req :get "/api/meals" nil (dev-headers "rohan"))
      (let [etag (etag-of "/api/members/rohan" admin)
            resp (req :post "/api/members/rohan/-/assign_roles"
                      {:roles ["adminn"]}
                      (assoc admin "if-match" etag))]
        (is (= 409 (:status resp)))
        (is (str/includes? (:detail (json resp)) "adminn"))))
    (testing "a registered role assigns, and rides the member's principal"
      (let [etag (etag-of "/api/members/rohan" admin)
            resp (req :post "/api/members/rohan/-/assign_roles"
                      {:roles ["admin"]}
                      (assoc admin "if-match" etag))]
        (is (= 200 (:status resp)))
        (is (= ["admin"] (get-in (json resp) [:data :roles]))))
      (is (contains? (:roles (members/gate! *eng* (t/principal {:id "rohan"})))
                     "admin")
          "the gate unions the member's held roles onto the credential"))))

;; ── 3b. assign_roles is a credential boundary (waymark-du2) ──────────

(deftest assign-roles-is-recovery-admins
  ;; the roles this test assigns must be registered (roles-registered
  ;; still applies beside the new authorization guard)
  (req :post "/api/roles" {:name "recovery-admin"})
  (req :post "/api/roles" {:name "planner"})
  ;; a role-less member to act on (auto-provisioned on first sight)
  (req :get "/api/meals" nil (dev-headers "faramir"))
  (testing "a non-admin human cannot assign roles — self-escalation is closed"
    (let [self (dev-headers "faramir")
          etag (etag-of "/api/members/faramir" self)
          resp (req :post "/api/members/faramir/-/assign_roles"
                    {:roles ["recovery-admin"]}
                    (assoc self "if-match" etag))]
      (is (= 409 (:status resp))
          "a bare assign_roles by a role-less principal is refused")
      (is (str/includes? (str (:detail (json resp))) "recovery-admin")
          "the refusal names the recovery-admin authority")
      (is (empty? (get-in (json (req :get "/api/members/faramir" nil self))
                          [:data :roles]))
          "no role was granted — the escalation did not land")))
  (testing "an admin holding recovery-admin may assign roles"
    (let [_ (req :get "/api/meals" nil admin)
          etag (etag-of "/api/members/faramir" admin)
          resp (req :post "/api/members/faramir/-/assign_roles"
                    {:roles ["planner"]}
                    (assoc admin "if-match" etag))]
      (is (= 200 (:status resp)))
      (is (= ["planner"] (get-in (json resp) [:data :roles])))))
  (testing "a system principal may assign roles"
    (let [sys (dev-headers "deploy-bot" {"x-waymark-actor-type" "system"})
          etag (etag-of "/api/members/faramir" sys)
          resp (req :post "/api/members/faramir/-/assign_roles"
                    {:roles ["planner" "recovery-admin"]}
                    (assoc sys "if-match" etag))]
      (is (= 200 (:status resp)))
      (is (= #{"planner" "recovery-admin"}
             (set (get-in (json resp) [:data :roles])))
          "the system actor's assign is allowed"))))

;; ── 4. OIDC: the bearer resolver ────────────────────────────────────

(deftest oidc-claims-map-to-the-principal
  (let [p (oidc/resolve-principal
           (:oidc *eng*)
           {"authorization"
            (str "Bearer " (mint {:sub "oidc-alice" :name "Alice"
                                  :roles ["planner" "admin"]}))})]
    (is (= "oidc-alice" (:id p)))
    (is (= "Alice" (:display p)))
    (is (= #{"planner" "admin"} (:roles p)))
    (is (= :human (:type p))))
  (testing "the type claim maps agents; system stays engine-internal"
    (is (= :agent (:type (oidc/resolve-principal
                          (:oidc *eng*)
                          {"authorization"
                           (str "Bearer " (mint {:sub "bot" :actor_type "agent"}))}))))
    (is (= :human (:type (oidc/resolve-principal
                          (:oidc *eng*)
                          {"authorization"
                           (str "Bearer " (mint {:sub "sly" :actor_type "system"}))})))))
  (testing "no bearer → nil: the dev headers stay the fallback"
    (is (nil? (oidc/resolve-principal (:oidc *eng*) {})))))

(deftest oidc-over-the-wire
  (testing "the happy path: a verified token acts, and is provisioned"
    (let [resp (req :get "/api/meals" nil
                    {"authorization" (str "Bearer " (mint {:sub "oidc-alice"
                                                           :name "Alice"}))})]
      (is (= 200 (:status resp))))
    (let [env (json (req :get "/api/members/oidc-alice"))]
      (is (= "Alice" (get-in env [:data :display])))))
  (testing "expired, wrong audience, bad signature, unknown kid: 401
            problems carrying WWW-Authenticate"
    (doseq [[who token]
            [["expired" (mint {:sub "a" :exp (- (now-secs) 60)})]
             ["wrong audience" (mint {:sub "a" :aud "someone-else"})]
             ["bad signature" (mint {:sub "a"} {:kp stranger-keypair})]
             ["unknown kid" (mint {:sub "a"} {:kid "rotated"})]
             ["not a jwt" "garbage.token"]]]
      (let [resp (req :get "/api/meals" nil
                      {"authorization" (str "Bearer " token)})
            b (json resp)]
        (is (= 401 (:status resp)) who)
        (is (some? (get-in resp [:headers "WWW-Authenticate"])) who)
        (let [vs (conf/problem-violations (:status resp) (ctype resp) b
                                          {:where (str "oidc " who)})]
          (is (empty? vs) (str/join "\n" vs)))))))

;; ── 5. grants: the scoped surface ───────────────────────────────────

(def ^:private agent-headers
  (dev-headers "agent-7" {"x-waymark-actor-type" "agent"}))

(defn- scoped [grant-id]
  (assoc agent-headers "x-waymark-grant" grant-id))

(defn- offer-grant! [body]
  (let [resp (req :post "/api/grants" body)]
    (is (= 201 (:status resp)))
    (id-of resp)))

(defn- accept! [gid]
  (let [resp (req :post (str "/api/grants/" gid "/-/accept") nil agent-headers)]
    (is (= 200 (:status resp)))
    (is (= "accepted" (:state (json resp))))))

(deftest grant-scoped-surface
  (let [pid (id-of (req :post "/api/plans" covered-plan))
        gid (offer-grant! {:audience "agent-7"
                           :scope [{:kind "plan"
                                    :actions ["assign_meal" "finalize"]}]})]
    (testing "an offered-but-unaccepted grant confers nothing"
      (is (= 404 (:status (req :get "/api/plans" nil (scoped gid))))))
    (accept! gid)
    (testing "the envelope shows exactly the granted actions — nothing
              else advertised, nothing else narrated"
      (let [env (json (req :get (str "/api/plans/" pid) nil (scoped gid)))]
        (is (= #{:assign_meal :finalize}
               (into (set (keys (:actions env))) (keys (:unavailable env)))))
        (let [vs (conf/grant-concealment-violations env #{"assign_meal"
                                                          "finalize"})]
          (is (empty? vs) (str/join "\n" vs)))))
    (testing "a granted action invokes; its response stays projected"
      (let [resp (req :post (str "/api/plans/" pid "/-/assign_meal")
                      {:date "2025-01-06" :meal_id "m-1"} (scoped gid))
            env (json resp)]
        (is (= 200 (:status resp)))
        (let [vs (conf/grant-concealment-violations env #{"assign_meal"
                                                          "finalize"})]
          (is (empty? vs) (str/join "\n" vs)))))
    (testing "a non-granted action 404s — concealment, not refusal"
      (let [resp (req :post (str "/api/plans/" pid "/-/abandon") nil
                      (scoped gid))
            b (json resp)]
        (is (= 404 (:status resp)))
        (is (str/includes? (:detail b) "abandon"))
        (let [vs (conf/problem-violations 404 (ctype resp) b
                                          {:where "scoped abandon"})]
          (is (empty? vs) (str/join "\n" vs)))))
    (testing "non-granted kinds do not exist: row, collection, schema,
              the grant itself"
      (is (= 404 (:status (req :get "/api/meals" nil (scoped gid)))))
      (is (= 404 (:status (req :get "/api/schemas/meal" nil (scoped gid)))))
      ;; batch B: the own-grant surface — a holder may read its grant
      (is (= 200 (:status (req :get (str "/api/grants/" gid) nil (scoped gid))))))
    (testing "discovery lists the granted kinds plus the own surface
              (negotiation kinds, the principal's own jobs, and — since
              waymark-0k4 — the recipe proposals it staged itself. That
              one is READ-ONLY courtesy: an agent that could not read
              back what it staged could not tell an applied change from
              a declined one, but staging itself takes an ordinary
              grant)"
      (let [b (json (req :get "/api/.well-known/waymark" nil (scoped gid)))]
        ;; …and verdict_reason beside them (waymark-jfv.16), for the
        ;; same reason and the same shape: a reason is the sayer's own,
        ;; so the own surface carries it on every leash
        (is (= ["approval_request" "feed_view" "feed_view_consent"
                "grant" "job" "plan" "recipe_proposal" "verdict_reason"]
               (:kinds b)))))
    (testing "the granted collection renders, its items projected"
      (let [b (json (req :get "/api/plans" nil (scoped gid)))]
        (is (= 200 (:status (req :get "/api/plans" nil (scoped gid)))))
        (doseq [item (get-in b [:data :items])]
          (let [vs (conf/grant-concealment-violations item #{"assign_meal"
                                                             "finalize"})]
            (is (empty? vs) (str/join "\n" vs))))
        (is (not (contains? (:actions b) :create))
            "create is not granted; the collection conceals it")))
    (testing "a revoked grant scopes to nothing"
      (is (= 200 (:status (req :post (str "/api/grants/" gid "/-/revoke")))))
      (is (= 404 (:status (req :get (str "/api/plans/" pid) nil (scoped gid))))))))

(deftest grant-id-narrowing
  (let [pid1 (id-of (req :post "/api/plans" covered-plan))
        pid2 (id-of (req :post "/api/plans" covered-plan))
        gid (offer-grant! {:audience "agent-7"
                           :scope [{:kind "plan" :ids [pid1]
                                    :actions ["assign_meal"]}]})]
    (accept! gid)
    (testing "the granted id renders; its sibling does not exist"
      (is (= 200 (:status (req :get (str "/api/plans/" pid1) nil (scoped gid)))))
      (is (= 404 (:status (req :get (str "/api/plans/" pid2) nil (scoped gid))))))
    (testing "the collection narrows to the granted rows, total honest"
      (let [b (json (req :get "/api/plans" nil (scoped gid)))]
        (is (= 1 (get-in b [:data :total])))
        (is (= [(str "/api/plans/" pid1)]
               (mapv :self (get-in b [:data :items]))))))))

(deftest grant-expiry-and-self-dealing
  (let [gid (offer-grant! {:audience "agent-7"
                           :scope [{:kind "plan" :actions ["assign_meal"]}]
                           :expires_at "2020-01-01T00:00:00Z"})]
    (accept! gid)
    (testing "an accepted grant past its expiry scopes to nothing, live"
      (is (= 404 (:status (req :get "/api/plans" nil (scoped gid))))))
    (testing "expire is the clock's bookkeeping once due"
      (let [resp (req :post (str "/api/grants/" gid "/-/expire"))]
        (is (= 200 (:status resp)))
        (is (= "expired" (:state (json resp)))))))
  (let [gid (offer-grant! {:audience "agent-7"
                           :scope [{:kind "plan" :actions ["assign_meal"]}]
                           :expires_at "2030-01-01T00:00:00Z"})]
    (accept! gid)
    (testing "expire refuses before the clock passes"
      (let [resp (req :post (str "/api/grants/" gid "/-/expire"))]
        (is (= 409 (:status resp)))
        (is (= "2030-01-01T00:00:00Z"
               (get-in (json resp) [:becomes_available :at]))
            "structured hope names the expiry")))
    (testing "the audience cannot revoke its own grant"
      (let [resp (req :post (str "/api/grants/" gid "/-/revoke") nil
                      agent-headers)]
        (is (= 409 (:status resp)))
        (is (str/includes? (:detail (json resp)) "someone else"))))
    (testing "only the audience accepts — a FOREIGN grant conceals
              whole under the agent default (waymark-rci): not the
              guard's 409 but the honest 404 of a row that does not
              exist for this principal"
      (let [gid2 (offer-grant! {:audience "agent-8"
                                :scope [{:kind "plan" :actions ["finalize"]}]})
            resp (req :post (str "/api/grants/" gid2 "/-/accept") nil
                      agent-headers)]
        (is (= 404 (:status resp)))))))

;; ── 6. attachments: bytes round-trip and the cap ────────────────────

(deftest attachment-byte-roundtrip
  (let [made (req :post "/api/attachments" {:name "brisket.txt"
                                            :media_type "text/plain"})
        aid (id-of made)
        sent "brisket 4200g · Traeger at 225F until 203F internal"
        put (req :put (str "/api/attachments/" aid "/bytes") sent)
        put-env (json put)
        got (req :get (str "/api/attachments/" aid "/bytes"))]
    (is (= 201 (:status made)))
    (is (= "pending" (:state (json made))))
    (let [vs (conf/attachment-roundtrip-violations
              {:sent (.getBytes sent "UTF-8")
               :put-status (:status put)
               :put-env put-env
               :get-status (:status got)
               :get-ctype (ctype got)
               :got (.getBytes ^String (slurp (:body got)) "UTF-8")
               :media-type "text/plain"})]
      (is (empty? vs) (str/join "\n" vs)))
    (testing "the stored mark is the bytes actor's, logged"
      (let [ts (store/with-tx (:storage *eng*)
                 (fn [tx] (store/transitions (:storage *eng*) tx
                                             {:kind :attachment
                                              :resource-id aid} {})))
            stored (first (filter #(= :mark_stored (:action %)) ts))]
        (is (= "waymark10-attachment-bytes" (get-in stored [:actor :id])))))
    (testing "a re-PUT of the same bytes replays; a different size refuses"
      (is (= 200 (:status (req :put (str "/api/attachments/" aid "/bytes")
                               sent))))
      (is (= 409 (:status (req :put (str "/api/attachments/" aid "/bytes")
                               "different bytes")))))
    (testing "mark_stored is concealed: absent from the envelope, 404
              when invoked by hand"
      (let [env (json (req :get (str "/api/attachments/" aid)))]
        (is (not (contains? (:actions env) :mark_stored)))
        (is (not (contains? (:unavailable env) :mark_stored))))
      (is (= 404 (:status (req :post (str "/api/attachments/" aid
                                          "/-/mark_stored")
                               {:size 1})))))
    (testing "delete stops the bytes being served"
      (is (= 200 (:status (req :post (str "/api/attachments/" aid "/-/delete")))))
      (is (= 404 (:status (req :get (str "/api/attachments/" aid "/bytes"))))))))

(deftest attachment-size-cap-and-emptiness
  (let [aid (id-of (req :post "/api/attachments" {:name "big.bin"
                                                  :media_type "application/octet-stream"}))]
    (testing "over the cap: one 413 problem"
      (let [resp (req :put (str "/api/attachments/" aid "/bytes")
                      (apply str (repeat 2000 "x")))
            b (json resp)]
        (is (= 413 (:status resp)))
        (let [vs (conf/problem-violations 413 (ctype resp) b
                                          {:where "attachment cap"})]
          (is (empty? vs) (str/join "\n" vs)))))
    (testing "an empty body: a field-keyed 422"
      (let [resp (req :put (str "/api/attachments/" aid "/bytes") "")
            b (json resp)]
        (is (= 422 (:status resp)))
        (is (seq (get-in b [:errors :bytes])))))
    (testing "no bytes yet: the GET is an honest 404"
      (is (= 404 (:status (req :get (str "/api/attachments/" aid "/bytes"))))))))
