(ns waymark10.model-claim-test
  "The model claim (docs/spec-seat.md § 9) — the harness declares
  which model runs a session, and the history says so afterwards.

  A session here is a signed token, not a row, so the declaration is
  a CLAIM in the token beside `actor_type`: POST /auth/agent takes an
  optional `model` at the bind, POST /auth/agent/renew re-declares it
  (or carries the live one forward), the identity resolver reads it
  back onto the principal, and every transition that principal writes
  stamps it into the actor's jsonb — present when a model was
  declared, absent when none was. The engine never verifies the
  claim; the only thing proved here is that what a harness said
  survives, unaltered, to the log a person reads.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.oidc-rp :as rp]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

(def ^:private tables
  ["meals" "members" "roles" "grants" "approval_requests"
   "attachments" "subscriptions" "jobs" "definitions" "capabilities"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"
   "waymark10_cursors" "waymark10_job_leases"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *raw* nil)
(def ^:dynamic *gated* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [eng (engine/engine
                   {:storage st :resources [fx/meal]
                    :oidc {:issuer "https://idp.test/realms/home"
                           :audience "model-claim-test"
                           ;; no RS256 client knocks here — the session
                           ;; cookie is the whole credential; the empty
                           ;; document satisfies config's own demand
                           :jwks {:keys []}
                           :rp {:client-id "model-claim-test"
                                :client-secret "shh"
                                :app-url "http://app.test"
                                :session-secret "a-32-byte-session-secret-seat!!"}}})]
          (binding [*eng* eng
                    *raw* (engine/handler eng)
                    *gated* ((rp/wrap-handler eng) (engine/handler eng))]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(defn- body-stream
  ;; a real ring body is an InputStream — and the doors slurp, which
  ;; would read a bare String as a file path
  [m]
  (java.io.ByteArrayInputStream. (.getBytes (wire/write-json m) "UTF-8")))

(defn- req* [h method uri & [{:keys [query body headers]}]]
  (h (cond-> {:request-method method :uri uri
              :headers (or headers {})}
       query (assoc :query-string query)
       body (assoc :body (body-stream body)))))

(defn- json [resp] (some-> (:body resp) (#(if (string? %) % (slurp %)))
                           wire/read-json))
(defn- id-of [resp] (last (str/split (str (:self (json resp))) #"/")))

(def ^:private admin
  {"x-waymark-principal" "admin" "x-waymark-actor-type" "system"})

;; the example spellings a `model` row would carry
(def ^:private sonnet "claude-sonnet-5")
(def ^:private haiku "claude-haiku-4-5")

(defn- invite!
  "An :invited agent member holding `tok` — setup on the raw handler,
  where the operator's own system actor lives."
  [display tok]
  (let [resp (req* *raw* :post "/api/members"
                   {:body {:display display :actor_type "agent"
                           :bind_token tok}
                    :headers admin})]
    (is (= 201 (:status resp)) (pr-str (json resp)))
    (id-of resp)))

(defn- cookie-of [resp] (get-in (json resp) [:session :use :value]))

(defn- principal-of
  "The identity resolver's own read of a minted session — the same
  call wrap-identity makes on every request that wears the cookie."
  [cookie]
  (rp/resolve-session (:oidc *eng*) {:headers {"cookie" cookie}}))

(defn- actor-of
  "The actor jsonb the row's one transition carries, read back out of
  the log rather than off the write's return value."
  [kind id]
  (-> (store/with-tx (:storage *eng*)
        (fn [tx]
          (store/transitions (:storage *eng*) tx
                             {:kind kind :resource-id id} {})))
      first
      :actor))

;; ── the doors ───────────────────────────────────────────────────────

(deftest the-bind-mints-what-the-harness-declares
  (let [tok "model-tok-declared-0001"]
    (invite! "declaring-sitter" tok)
    (let [resp (req* *gated* :post "/auth/agent"
                     {:query (str "invite=" tok)
                      :body {:model sonnet}})]
      (is (= 200 (:status resp)) (pr-str (json resp)))
      (testing "the claim rides the token, and the resolver reads it back"
        (is (= sonnet (:model (principal-of (cookie-of resp)))))))))

(deftest a-bind-that-declares-nothing-carries-no-model
  (let [tok "model-tok-silent-0002"]
    (invite! "silent-sitter" tok)
    (let [resp (req* *gated* :post "/auth/agent" {:query (str "invite=" tok)})
          principal (principal-of (cookie-of resp))]
      (is (= 200 (:status resp)) (pr-str (json resp)))
      (is (nil? (:model principal)))
      (testing "absent, not nil — a session that declared nothing says
                nothing, and the actor it writes has no key to omit"
        (is (not (contains? principal :model)))))))

(deftest the-renew-re-declares-and-otherwise-carries-forward
  (let [tok "model-tok-renewing-0003"]
    (invite! "renewing-sitter" tok)
    (let [bound (req* *gated* :post "/auth/agent"
                      {:query (str "invite=" tok) :body {:model sonnet}})
          moved (req* *gated* :post "/auth/agent/renew"
                      {:headers {"cookie" (cookie-of bound)}
                       :body {:model haiku}})]
      (is (= 200 (:status moved)) (pr-str (json moved)))
      (testing "a renew that names a model re-declares the session's"
        (is (= haiku (:model (principal-of (cookie-of moved))))))
      (testing "a renew that names none keeps the live claim — the
                ticking leash keeper sends no body and must not
                silently un-declare the model it is running"
        (let [quiet (req* *gated* :post "/auth/agent/renew"
                          {:headers {"cookie" (cookie-of moved)}})]
          (is (= 200 (:status quiet)))
          (is (= haiku (:model (principal-of (cookie-of quiet))))))))))

(deftest a-claim-that-is-not-a-name-is-refused-at-the-door
  (let [tok "model-tok-shapeless-0004"]
    (invite! "shapeless-sitter" tok)
    (testing "the SHAPE is the door's to hold (1 to 64 characters),
              even though the truth of the claim is nobody's"
      (let [resp (req* *gated* :post "/auth/agent"
                       {:query (str "invite=" tok)
                        :body {:model (apply str (repeat 65 "m"))}})]
        (is (= 400 (:status resp)))))
    (testing "and the token is unspent — a refused declaration binds nobody"
      (let [resp (req* *gated* :post "/auth/agent"
                       {:query (str "invite=" tok) :body {:model sonnet}})]
        (is (= 200 (:status resp)))
        (is (= sonnet (:model (principal-of (cookie-of resp)))))))))

;; ── the log ─────────────────────────────────────────────────────────

(deftest the-actor-carries-the-declared-model
  (let [declared "model-tok-writing-0005"
        silent "model-tok-writing-0006"]
    (invite! "writing-sitter" declared)
    (invite! "quiet-sitter" silent)
    (let [with (principal-of
                (cookie-of (req* *gated* :post "/auth/agent"
                                 {:query (str "invite=" declared)
                                  :body {:model sonnet}})))
          without (principal-of
                   (cookie-of (req* *gated* :post "/auth/agent"
                                    {:query (str "invite=" silent)})))
          mine (inv/create! *eng* :meal {:name "Congee" :themes ["warm"]}
                            {:principal with})
          theirs (inv/create! *eng* :meal {:name "Dal" :themes ["warm"]}
                              {:principal without})]
      (testing "a transition written under a declaring session names the
                model in its actor — the jsonb column that already
                holds whose hand and whose leash"
        (let [actor (actor-of :meal (get-in mine [:row :id]))]
          (is (= sonnet (:model actor)))
          (is (= (:id with) (:id actor)))
          (is (= "agent" (:type actor)))))
      (testing "and one written under a session that declared nothing
                has no :model key at all"
        (let [actor (actor-of :meal (get-in theirs [:row :id]))]
          (is (not (contains? actor :model)))
          (is (= (:id without) (:id actor))))))))

(deftest the-dev-header-declares-it-too
  ;; the dev resolver is one resolver among several (oidc.clj's line),
  ;; and a test driving the real router by header must be able to say
  ;; which model it is pretending to be
  (let [resp (req* *raw* :post "/api/meals"
                   {:body {:name "Khichdi" :themes ["warm"]}
                    :headers {"x-waymark-principal" "chef"
                              "x-waymark-model" haiku}})]
    (is (= 201 (:status resp)) (pr-str (json resp)))
    (is (= haiku (:model (actor-of :meal (id-of resp))))))
  (testing "and a request without the header writes an actor with no model"
    (let [resp (req* *raw* :post "/api/meals"
                     {:body {:name "Bhaat" :themes ["warm"]}
                      :headers {"x-waymark-principal" "chef"}})]
      (is (= 201 (:status resp)) (pr-str (json resp)))
      (is (not (contains? (actor-of :meal (id-of resp)) :model))))))
