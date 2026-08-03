(ns waymark10.agent-door-test
  "The /auth/agent door — the invite link is the whole key: an agent
  holding ONLY the link reads the welcome document through the gate
  (token-gated by design), POSTs the same token to the door, and
  walks away bound with an engine-minted session; the link goes dark
  behind it. A live session renews itself; a lapsed or absent one is
  the honest 401; a spent or unknown token answers 404 and says
  nothing. Setup drives the RAW handler (an admin's system actor is
  the operator's own); the flow drives the WRAPPED one — the same
  gate production serves.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [buddy.core.keys :as bkeys]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.members :as members]
            [waymark10.server.oidc-rp :as rp]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)))

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "door-key" :alg "RS256" :use "sig")]})

(def ^:private tables
  ["meals" "members" "roles" "grants" "approval_requests"
   "attachments" "subscriptions" "jobs" "definitions"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"
   "waymark10_cursors" "waymark10_job_leases"])

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
                           :audience "door-test"
                           :jwks jwks
                           :rp {:client-id "door-test"
                                :client-secret "shh"
                                :app-url "http://app.test"
                                :session-secret "a-32-byte-session-secret-door!!"
                                :require-auth? true}}})]
          (binding [*raw* (engine/handler eng)
                    *gated* ((rp/wrap-handler eng) (engine/handler eng))]
            (f)))
        (finally (pg/close! st))))))

(defn- req* [h method uri & [{:keys [query body headers]}]]
  (h (cond-> {:request-method method :uri uri
              :headers (or headers {})}
       query (assoc :query-string query)
       body (assoc :body (wire/write-json body)))))

(defn- json [resp] (some-> (:body resp) (#(if (string? %) % (slurp %)))
                           wire/read-json))

(def ^:private admin
  {"x-waymark-principal" "admin" "x-waymark-actor-type" "system"})

(def ^:private token "door-tok-4471")

(deftest the-invite-link-is-the-whole-key
  ;; the admin's invite, born :invited — setup on the raw handler
  (let [resp (req* *raw* :post "/api/members"
                   {:body {:display "door-agent" :actor_type "agent"
                           :bind_token token}
                    :headers admin})]
    (is (= 201 (:status resp)))
    (is (= "invited" (:state (json resp)))))

  (testing "the gate refuses the anonymous, but not the welcome page —
            token-gated by design, and it advertises the door"
    (is (= 401 (:status (req* *gated* :get "/api/meals"))))
    (let [resp (req* *gated* :get "/api/-/welcome"
                     {:query (str "invite=" token)})]
      (is (= 200 (:status resp)))
      (is (= (str "/auth/agent?invite=" token)
             (get-in (json resp) [:session :href])))))

  (let [resp (req* *gated* :post "/auth/agent" {:query (str "invite=" token)})
        body (json resp)
        cookie (get-in body [:session :use :value])]
    (testing "the door binds and mints"
      (is (= 200 (:status resp)))
      (is (= "door-agent" (get-in body [:agent :display])))
      (is (string? (get-in body [:session :token])))
      (is (str/starts-with? cookie "waymark_session=")))

    (testing "the session is an IDENTITY, not sight (waymark-rci):
              the domain answers 404, the asking door answers 200 —
              an agent's access is off by default and negotiated"
      (is (= 404 (:status (req* *gated* :get "/api/meals"
                                {:headers {"cookie" cookie}}))))
      (is (= 200 (:status (req* *gated* :get "/api/approval_requests"
                                {:headers {"cookie" cookie}}))))
      (is (contains? (set (:kinds (json (req* *gated* :get
                                              "/api/.well-known/waymark"
                                              {:headers {"cookie" cookie}}))))
                     "meal")
          "the vocabulary stays whole — an agent must be able to
           NAME what it asks for"))

    (testing "the link went dark — door and welcome both"
      (is (= 404 (:status (req* *gated* :post "/auth/agent"
                                {:query (str "invite=" token)}))))
      (is (= 404 (:status (req* *gated* :get "/api/-/welcome"
                                {:query (str "invite=" token)})))))

    (testing "a live session renews; nothing renews nothing"
      (let [renewed (req* *gated* :post "/auth/agent/renew"
                          {:headers {"cookie" cookie}})]
        (is (= 200 (:status renewed)))
        (is (string? (get-in (json renewed) [:session :token]))))
      (is (= 401 (:status (req* *gated* :post "/auth/agent/renew"))))))

  (testing "garbage never binds"
    (is (= 404 (:status (req* *gated* :post "/auth/agent"
                              {:query "invite=nope"}))))))

;; ── the whole negotiation: hidden < hashed < read ───────────────────

(def ^:private token2 "door-tok-9932")

(deftest sight-is-always-negotiated
  ;; the world: one meal (a human's, unscoped), one invited agent
  (let [meal (json (req* *raw* :post "/api/meals"
                         {:body {:name "Traeger brisket" :themes ["bbq"]}
                          :headers {"x-waymark-principal" "colton"}}))
        _ (is (some? (:self meal)))
        _ (req* *raw* :post "/api/members"
                {:body {:display "scoped-agent" :actor_type "agent"
                        :bind_token token2}
                 :headers admin})
        cookie (-> (req* *gated* :post "/auth/agent"
                         {:query (str "invite=" token2)})
                   json (get-in [:session :use :value]))
        as-agent (fn [method uri & [opts]]
                   (req* *gated* method uri
                         (update opts :headers merge {"cookie" cookie})))]

    (testing "default: the meal kind does not exist for the agent"
      (is (= 404 (:status (as-agent :get "/api/meals"))))
      (is (= 404 (:status (as-agent :get (:self meal))))))

    ;; the ask: correlate meals by name, never read them
    (let [ask (json (as-agent :post "/api/approval_requests"
                              {:body {:task "correlate meals without reading names"
                                      :scope [{:kind "meal" :actions []
                                               :hashed ["name"]}]}}))
          _ (is (some? (:self ask)) (pr-str ask))
          ;; the human approves (four-eyes: never the requester)
          approved (req* *raw* :post (str (:self ask) "/-/approve")
                         {:headers admin})
          _ (is (= 200 (:status approved)) (:body approved))
          grant-id (get-in (json (req* *raw* :get (:self ask)
                                       {:headers admin}))
                           [:data :grant_id])
          _ (is (some? grant-id))
          granted (fn [method uri & [opts]]
                    (as-agent method uri
                              (update opts :headers merge
                                      {"x-waymark-grant" grant-id})))]

      (testing "under the grant: the row exists, the name is a token"
        (let [row (json (granted :get (:self meal)))]
          (is (str/starts-with? (get-in row [:data :name]) "#")
              (pr-str (:data row)))
          (is (= ["bbq"] (get-in row [:data :themes]))
              "un-named fields stay plainly visible")
          (is (str/includes? (:summary row) "#")
              "the summary wears the token, never the value")))

      (testing "the token is stable — correlation is the point"
        (is (= (get-in (json (granted :get (:self meal))) [:data :name])
               (get-in (json (granted :get (:self meal))) [:data :name]))))

      (testing "the oracle is closed: filters and sorts on the hashed
                field answer the unknown-parameter 422"
        (is (= 422 (:status (granted :get "/api/meals"
                                     {:query "name=Traeger%20brisket"}))))
        (is (= 422 (:status (granted :get "/api/meals"
                                     {:query "sort=name"}))))
        (is (= 200 (:status (granted :get "/api/meals")))
            "the kind's own default sort softens to id order"))

      (testing "dropping the grant header drops the sight again"
        (is (= 404 (:status (as-agent :get (:self meal)))))))))

;; ── the knock: the invite loop inverted (/agentInvite) ──────────────

(deftest the-agent-knocks-and-carries-the-link-back
  (testing "the door teaches the anonymous before it mints — open
            through the require-auth gate by design"
    (let [resp (req* *gated* :get "/agentInvite")]
      (is (= 200 (:status resp)))
      (is (= "POST" (get-in (json resp) [:knock :method]))))
    (is (= 200 (:status (req* *gated* :get "/api/-/agent-invite")))
        "the /api/-/ spelling answers too"))

  (testing "a nameless knock is refused with the way in"
    (is (= 422 (:status (req* *gated* :post "/agentInvite" {:body {}})))))

  (testing "a named knock mints both halves: the agent's welcome link
            and the human's follow link"
    (let [resp (req* *gated* :post "/agentInvite"
                     {:body {:display "Knocker"}
                      :headers {"host" "app.test"
                                "x-forwarded-proto" "https"}})
          body (json resp)
          welcome (get-in body [:welcome :href])
          follow (get-in body [:follow :href])]
      (is (= 201 (:status resp)) (pr-str body))
      (is (str/starts-with? (str welcome)
                            "https://app.test/api/-/welcome?invite="))
      (is (str/starts-with? (str follow) "https://app.test/?follow="))
      (is (str/includes? (str follow) "follow_name=Knocker"))
      (is (str/ends-with? (str follow) "#access"))

      (let [tok (subs welcome (inc (str/index-of welcome "=")))]
        (testing "the minted token opens the same welcome doc"
          (is (= 200 (:status (req* *gated* :get "/api/-/welcome"
                                    {:query (str "invite=" tok)})))))
        (testing "and binds down the credential-less door — the follow
                  link already names the principal the session acts as"
          (let [bound (req* *gated* :post "/auth/agent"
                            {:query (str "invite=" tok)})
                agent-id (get-in (json bound) [:agent :id])]
            (is (= 200 (:status bound)))
            (is (= "Knocker" (get-in (json bound) [:agent :display])))
            (is (str/includes? (str follow) (str agent-id)))))))))

(deftest a-bodyless-knock-is-still-the-nameless-refusal
  (is (= 422 (:status (req* *gated* :post "/agentInvite")))))

(deftest the-knock-is-paced-two-ways
  (let [knock #(req* *gated* :post "/agentInvite"
                     {:body {:display (str "pace-probe-" %)}
                      :headers {"host" "app.test"}})
        token-of (fn [resp]
                   (let [w (get-in (json resp) [:welcome :href])]
                     (subs w (inc (str/index-of w "=")))))
        bind! (fn [tok]
                (is (= 200 (:status (req* *gated* :post "/auth/agent"
                                          {:query (str "invite=" tok)})))))]

    (testing "the rolling hour refuses a window already full — the
              wall binding cannot drain"
      (reset! members/knock-log
              (vec (repeat 12 (java.time.Instant/now))))
      (let [resp (knock "hour")]
        (is (= 429 (:status resp)))
        (is (str/includes? (:detail (json resp)) "paced"))))

    (testing "the standing shelf refuses the 13th unclaimed invitation,
              and binding reopens the door"
      (let [minted (loop [n 0 toks []]
                     (reset! members/knock-log [])   ; isolate the shelf
                     (let [resp (knock n)]
                       (cond (= 429 (:status resp))
                             (do (is (str/includes? (:detail (json resp))
                                                    "unclaimed"))
                                 toks)
                             (< n 13) (recur (inc n)
                                             (conj toks (token-of resp)))
                             :else (do (is false "the shelf never filled")
                                       toks))))]
        (is (pos? (count minted)) "the loop minted before it was refused")
        ;; drain the shelf: every invitation binds, none stand unclaimed
        (doseq [tok minted] (bind! tok))
        (reset! members/knock-log [])
        (let [again (knock "reopened")]
          (is (= 201 (:status again)))
          (bind! (token-of again)))))
    (reset! members/knock-log [])))
