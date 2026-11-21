(ns waymark10.connector-door-test
  "The connector door (docs/spec-connector-door.md): a tool that can
  present a bearer and nothing else — the claude.ai custom connector —
  finds the authorization server by OAuth discovery, and the person's
  token resolves to a DELEGATE agent whose worn grant the engine reads
  off its member row, because no header will ever carry it.

  Memory storage, a locally-minted RSA keypair as the IdP's signing
  key, the real handler: no database, no network."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.fixtures :as fx]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.mcp-servers :as servers]
            [org.httpkit.client :as http]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.oidc-rp :as rp]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)))

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "connector-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "connector-key" :alg "RS256" :use "sig")]})

(defn- mint [claims]
  (jwt/sign (merge {:iss issuer :aud audience
                    :exp (+ (quot (System/currentTimeMillis) 1000) 600)}
                   claims)
            (.getPrivate keypair)
            {:alg :rs256 :header {:kid "connector-key"}}))

(defn- bearer [claims]
  {"authorization" (str "Bearer " (mint claims))})

(defn- oidc-opts
  ([] (oidc-opts nil))
  ([more]
   (merge {:issuer issuer :audience audience :jwks jwks
           ;; a trailing slash on purpose: the document must not
           ;; double it
           :app-url "https://app.test/"
           :delegate-clients {"connector" "Claude"}}
          more)))

(defn- fresh-engine
  ([] (fresh-engine nil))
  ([opts]
   (engine/engine (merge {:storage (memory/storage)
                          :resources [fx/meal]
                          :oidc (oidc-opts)}
                         opts))))

(defn- no-tools
  "A gate client that answers an empty tool list. This namespace opens
  no socket: the bridge row is seeded for its POWERS, which is what
  doors.ask.powers lists (spec-mcp-servers R-5/R-6), and a row offers
  its powers whether or not the server behind it answers anything."
  [_method _params]
  {:tools []})

(defn- with-gate-row!
  "The bridge row (spec-mcp-servers R-13, § 3 step 1), seeded the way
  gate_proxy_test's boot seeds it. doors.ask.powers is the union of
  the mcp_server rows' power tokens since waymark-fp62.10, so an
  engine with no row honestly lists none — a test that reads the ask
  door's vocabulary has to carry the row that holds it."
  [eng]
  (servers/ensure-gate-row! eng)
  eng)

(defn- json [resp] (wire/read-json (:body resp)))

(defn- GET [h uri headers]
  (h {:request-method :get :uri uri :headers headers}))

(defn- mcp-post [h headers]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json {:jsonrpc "2.0" :id 1 :method "tools/list"})}))

(defn- rpc [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json {:jsonrpc "2.0" :id 1 :method method
                              :params params})}))

(defn- POST [h uri body]
  (h {:request-method :post :uri uri
      :headers {"content-type" "application/json"}
      :body (wire/write-json body)}))

(def ^:private claude-callback "https://claude.ai/api/mcp/auth_callback")

(def ^:private discovery
  "https://app.test/.well-known/oauth-protected-resource/api/-/mcp")

;; the person at the keyboard, for the doors that are a human's alone
(def ^:private person (t/principal {:id "colton" :display "Colton Kopsa"}))

;; the person, signing in THROUGH the connector: a human's token
;; carrying the household's strongest role, which the delegate must
;; not inherit
(def ^:private colton
  {:sub "colton" :azp "connector" :name "Colton Kopsa"
   :realm_access {:roles ["recovery-admin"]}})

;; ── 1. the engine names its authorization server ─────────────────────

(deftest the-protected-resource-document-names-the-door
  (let [h (engine/handler (fresh-engine))]
    (doseq [uri ["/.well-known/oauth-protected-resource"
                 "/.well-known/oauth-protected-resource/api/-/mcp"]]
      (testing uri
        (let [resp (GET h uri {})
              doc (json resp)]
          (is (= 200 (:status resp)))
          (is (= "https://app.test/api/-/mcp" (:resource doc)))
          (is (= ["https://app.test"] (:authorization_servers doc))
              "a Claude delegate client: the engine answers registration, so it is the server")
          (is (= ["header"] (:bearer_methods_supported doc)))
          (is (nil? (:scopes_supported doc))
              "no scopes configured, none advertised"))))
    (testing "scopes ride along when the deployment names them"
      (let [h (engine/handler
               (fresh-engine {:oidc (oidc-opts {:resource-scopes ["waymark-x"]})}))]
        (is (= ["waymark-x"]
               (:scopes_supported
                (json (GET h "/.well-known/oauth-protected-resource" {})))))))
    (testing "an engine with no external base URL advertises nothing"
      (let [h (engine/handler
               (fresh-engine {:oidc (dissoc (oidc-opts) :app-url)}))]
        (is (= 404 (:status (GET h "/.well-known/oauth-protected-resource" {}))))))))

(deftest the-engine-answers-claudes-registration-itself
  (let [h (engine/handler (fresh-engine))]
    (testing "the authorization-server metadata is the engine's, every endpoint under its issuer"
      (doseq [uri ["/.well-known/oauth-authorization-server"
                   "/.well-known/openid-configuration"]]
        (testing uri
          (let [resp (GET h uri {})
                doc (json resp)]
            (is (= 200 (:status resp)))
            (is (= "https://app.test" (:issuer doc)))
            (is (= "https://app.test/api/-/oauth/register" (:registration_endpoint doc)))
            (is (= "https://app.test/api/-/oauth/authorize" (:authorization_endpoint doc)))
            (is (= "https://app.test/api/-/oauth/token" (:token_endpoint doc)))
            (is (true? (:authorization_response_iss_parameter_supported doc)))
            (is (every? #(str/starts-with? (str (get doc %)) (str (:issuer doc) "/"))
                        [:authorization_endpoint :token_endpoint :registration_endpoint])
                "RFC 9207: the iss Claude checks is the issuer every endpoint lives under")
            (is (= ["S256"] (:code_challenge_methods_supported doc)))
            (is (= ["none"] (:token_endpoint_auth_methods_supported doc)))))))
    (testing "Claude's callback gets the house's one connector client back, public"
      (let [resp (POST h "/api/-/oauth/register"
                       {:client_name "Claude" :redirect_uris [claude-callback]
                        :grant_types ["authorization_code" "refresh_token"]
                        :token_endpoint_auth_method "client_secret_post"})
            doc (json resp)]
        (is (= 201 (:status resp)))
        (is (= "connector" (:client_id doc)))
        (is (= "none" (:token_endpoint_auth_method doc)))
        (is (= [claude-callback] (:redirect_uris doc)))
        (is (nil? (:client_secret doc)) "nothing secret is minted or handed out")))
    (testing "another redirect is refused with a sentence"
      (let [resp (POST h "/api/-/oauth/register"
                       {:redirect_uris ["https://elsewhere.test/callback"]})
            doc (json resp)]
        (is (= 400 (:status resp)))
        (is (= "invalid_redirect_uri" (:error doc)))
        (is (str/includes? (str (:error_description doc)) claude-callback))))
    (testing "and so is a request that names no redirect"
      (is (= 400 (:status (POST h "/api/-/oauth/register" {}))))))
  (testing "with no Claude delegate client the provider stays the authorization server"
    (let [h (engine/handler
             (fresh-engine {:oidc (oidc-opts {:delegate-clients {"other" "Gemini"}})}))]
      (is (= [issuer] (:authorization_servers
                       (json (GET h "/.well-known/oauth-protected-resource" {})))))
      (is (= 404 (:status (GET h "/.well-known/oauth-authorization-server" {}))))
      (is (= 404 (:status (POST h "/api/-/oauth/register"
                                {:redirect_uris [claude-callback]}))))))
  (testing "a solo engine (no :oidc) serves none of it"
    (let [h (engine/handler (fresh-engine {:oidc nil}))]
      (is (= 404 (:status (GET h "/.well-known/oauth-authorization-server" {}))))
      (is (= 404 (:status (POST h "/api/-/oauth/register"
                                {:redirect_uris [claude-callback]})))))))

(deftest a-confidential-connector-client-is-the-operators-to-hand-out
  (let [h (engine/handler
           (fresh-engine {:oidc (oidc-opts {:connector-confidential? true})}))]
    (testing "registration refuses with a sentence Claude can show"
      (let [resp (POST h "/api/-/oauth/register" {:redirect_uris [claude-callback]})
            doc (json resp)]
        (is (= 400 (:status resp)))
        (is (= "invalid_client_metadata" (:error doc)))
        (is (= (str "This house needs the client ID and secret from its operator: "
                    "add the connector again, and enter both in its advanced settings.")
               (:error_description doc)))
        (is (nil? (:client_id doc)) "no client is handed out")))
    (testing "the metadata still serves login and token, with a secret"
      (is (= ["client_secret_basic" "client_secret_post"]
             (:token_endpoint_auth_methods_supported
              (json (GET h "/.well-known/oauth-authorization-server" {}))))))))

;; ── 1b. the engine carries sign-in through to the provider ───────────

(defn- enc [s] (java.net.URLEncoder/encode (str s) "UTF-8"))

(defn- GET-q [h uri query]
  (h {:request-method :get :uri uri :query-string query :headers {}}))

(defn- location-params [resp]
  (let [[to q] (str/split (str (get-in resp [:headers "Location"])) #"\?" 2)]
    [to (oidc/form-decode q)]))

(def ^:private authorize-query
  (str "response_type=code&client_id=connector"
       "&redirect_uri=" (enc claude-callback)
       "&scope=openid&state=claude-state-1"
       "&code_challenge=abc123&code_challenge_method=S256"))

(defn- form-body [m]
  (str/join "&" (map (fn [[k v]] (str k "=" (enc v))) m)))

(deftest the-engine-carries-sign-in-through-to-the-provider
  (let [h (engine/handler (fresh-engine))]
    (testing "authorize sends the person to the provider with the engine's callback and a wrapped state"
      (let [resp (GET-q h "/api/-/oauth/authorize" authorize-query)
            [to params] (location-params resp)]
        (is (= 302 (:status resp)))
        (is (= (str issuer "/protocol/openid-connect/auth") to))
        (is (= "connector" (get params "client_id")))
        (is (= "https://app.test/api/-/oauth/callback" (get params "redirect_uri")))
        (is (= "abc123" (get params "code_challenge")))
        (is (= "S256" (get params "code_challenge_method")))
        (is (= "openid offline_access" (get params "scope"))
            "an offline token outlives the provider's SSO session")
        (is (not= "claude-state-1" (get params "state")) "Claude's state rides wrapped")
        (testing "and the callback restores Claude's state, keeps the code and adds iss"
          (let [back (GET-q h "/api/-/oauth/callback"
                            (str "code=provider-code&state=" (enc (get params "state"))))
                [to2 p2] (location-params back)]
            (is (= 302 (:status back)))
            (is (= claude-callback to2))
            (is (= {"code" "provider-code" "state" "claude-state-1" "iss" "https://app.test"}
                   p2))))
        (testing "a provider error passes through as an OAuth error with the same iss"
          (let [[_ p2] (location-params
                        (GET-q h "/api/-/oauth/callback"
                               (str "error=access_denied&state=" (enc (get params "state")))))]
            (is (= "access_denied" (get p2 "error")))
            (is (= "claude-state-1" (get p2 "state")))
            (is (= "https://app.test" (get p2 "iss")))))
        (testing "a tampered state is refused, and nothing is redirected"
          (let [resp (GET-q h "/api/-/oauth/callback"
                            (str "code=c&state=" (enc (str (get params "state") "x"))))]
            (is (= 400 (:status resp)))
            (is (nil? (get-in resp [:headers "Location"])))))))
    (testing "an expired state is refused"
      (let [[_ params] (with-redefs [oidc/state-ttl-s -60]
                         (location-params (GET-q h "/api/-/oauth/authorize" authorize-query)))
            resp (GET-q h "/api/-/oauth/callback" (str "code=c&state=" (enc (get params "state"))))]
        (is (= 400 (:status resp)))
        (is (nil? (get-in resp [:headers "Location"])))))
    (testing "an unknown client is refused without a redirect"
      (let [resp (GET-q h "/api/-/oauth/authorize"
                        (str/replace authorize-query "client_id=connector" "client_id=stranger"))]
        (is (= 400 (:status resp)))
        (is (= "invalid_client" (:error (json resp))))
        (is (nil? (get-in resp [:headers "Location"])))))
    (testing "so is a redirect the client does not hold: no open redirect"
      (let [resp (GET-q h "/api/-/oauth/authorize"
                        (str/replace authorize-query (enc claude-callback)
                                     (enc "https://elsewhere.test/callback")))]
        (is (= 400 (:status resp)))
        (is (nil? (get-in resp [:headers "Location"])))))
    (testing "the token door forwards to the provider and answers its body unchanged"
      (let [seen (atom nil)
            provider-body "{\"access_token\":\"provider-jwt\",\"token_type\":\"Bearer\"}"
            resp (with-redefs [http/post
                               (fn [url opts]
                                 (reset! seen [url opts])
                                 (doto (promise)
                                   (deliver {:status 200
                                             :headers {:content-type "application/json"}
                                             :body provider-body})))]
                   (h {:request-method :post :uri "/api/-/oauth/token"
                       :headers {"content-type" "application/x-www-form-urlencoded"}
                       :body (form-body {"grant_type" "authorization_code"
                                         "code" "provider-code"
                                         "client_id" "connector"
                                         "redirect_uri" claude-callback
                                         "code_verifier" "the-verifier"})}))
            [url opts] @seen]
        (is (= 200 (:status resp)))
        (is (= provider-body (:body resp)))
        (is (= (str issuer "/protocol/openid-connect/token") url))
        (is (= "https://app.test/api/-/oauth/callback" (get-in opts [:form-params "redirect_uri"]))
            "the redirect the code was issued for: the engine's callback")
        (is (= "the-verifier" (get-in opts [:form-params "code_verifier"])))
        (is (= "provider-code" (get-in opts [:form-params "code"])))))
    (testing "and refuses a grant it does not carry"
      (is (= 400 (:status (h {:request-method :post :uri "/api/-/oauth/token"
                              :headers {"content-type" "application/x-www-form-urlencoded"}
                              :body "grant_type=password&username=a&password=b"})))))))

;; ── 2. every 401 says where to start ─────────────────────────────────

(deftest an-anonymous-mcp-call-is-told-where-to-start
  (let [h (engine/handler (fresh-engine))]
    (testing "the POST"
      (let [resp (mcp-post h {})]
        (is (= 401 (:status resp)))
        (is (= (str "Bearer realm=\"waymark\", resource_metadata=\"" discovery "\"")
               (get-in resp [:headers "WWW-Authenticate"])))))
    (testing "and the stream GET, refused the same way before its 405"
      (let [resp (GET h "/api/-/mcp" {})]
        (is (= 401 (:status resp)))
        (is (str/includes? (get-in resp [:headers "WWW-Authenticate"])
                           "resource_metadata="))))
    (testing "a presented-and-refused token says so, and still points home"
      (let [resp (mcp-post h (bearer (assoc colton :aud "somebody-else")))]
        (is (= 401 (:status resp)))
        (is (= (str "Bearer realm=\"waymark\", error=\"invalid_token\", "
                    "resource_metadata=\"" discovery "\"")
               (get-in resp [:headers "WWW-Authenticate"])))))))

(deftest require-auth-leaves-discovery-open
  (let [eng (fresh-engine
             {:oidc (oidc-opts
                     {:rp {:client-id "ui" :client-secret "shh"
                           :app-url "https://app.test"
                           :session-secret "a-32-byte-session-secret-conn!!"
                           :require-auth? true :login-redirect? false}})})
        h ((rp/wrap eng) (engine/handler eng))]
    (testing "the document is reachable by the anonymous — it is how they stop being anonymous"
      (is (= 200 (:status (GET h "/.well-known/oauth-protected-resource/api/-/mcp" {})))))
    (testing "and so are the engine's authorization-server metadata and its registration"
      (is (= 200 (:status (GET h "/.well-known/oauth-authorization-server" {}))))
      (is (= 201 (:status (POST h "/api/-/oauth/register"
                                {:redirect_uris [claude-callback]})))))
    (testing "the gate's own 401 carries the same challenge"
      (let [resp (mcp-post h {})]
        (is (= 401 (:status resp)))
        (is (= (str "Bearer realm=\"waymark\", resource_metadata=\"" discovery "\"")
               (get-in resp [:headers "WWW-Authenticate"])))))))

;; ── 2b. a connected Claude outlives a restart (ticket f5412b73) ──────

(deftest a-connected-claude-outlives-a-restart
  (let [storage (memory/storage)
        token (mint colton)
        headers {"authorization" (str "Bearer " token)}
        eng (fresh-engine {:storage storage})]
    (inv/create! eng :grant
                 {:audience "connector:colton"
                  :scope [{:kind "meal" :actions ["accept"]}]}
                 {:principal grants/approvals-actor
                  :id "grant-restart-1"
                  :mint? true})
    (is (= 200 (:status (mcp-post (engine/handler eng) headers))))
    (testing "the same token is accepted by an engine rebuilt as a boot builds it"
      (let [h (engine/handler (fresh-engine {:storage storage}))
            resp (mcp-post h headers)]
        (is (= 200 (:status resp)))
        (is (seq (get-in (json resp) [:result :tools]))
            "and the delegate still wears its grant")))
    (testing "a sign-in that already asks offline_access asks it once"
      (let [[_ params] (location-params
                        (GET-q (engine/handler eng) "/api/-/oauth/authorize"
                               (str/replace authorize-query "scope=openid"
                                            "scope=openid%20offline_access")))]
        (is (= "openid offline_access" (get params "scope")))))
    (testing "a refresh after the boot goes to the provider unchanged, no browser"
      (let [seen (atom nil)
            resp (with-redefs [http/post
                               (fn [url opts]
                                 (reset! seen [url opts])
                                 (doto (promise)
                                   (deliver {:status 200
                                             :headers {:content-type "application/json"}
                                             :body "{\"access_token\":\"fresh\"}"})))]
                   ((engine/handler (fresh-engine {:storage storage}))
                    {:request-method :post :uri "/api/-/oauth/token"
                     :headers {"content-type" "application/x-www-form-urlencoded"}
                     :body (form-body {"grant_type" "refresh_token"
                                       "refresh_token" "the-offline-token"
                                       "client_id" "connector"})}))
            [url opts] @seen]
        (is (= 200 (:status resp)))
        (is (= (str issuer "/protocol/openid-connect/token") url))
        (is (= {"grant_type" "refresh_token"
                "refresh_token" "the-offline-token"
                "client_id" "connector"}
               (:form-params opts)))))))

;; ── 3. who Claude is at the door ─────────────────────────────────────

(defn- delegates-of [eng person]
  (store/with-tx (:storage eng)
    (fn [tx] (store/query-rows (:storage eng) tx :member
                               {:acts_for person} {:limit 10}))))

(deftest a-connector-token-arrives-as-a-delegate-on-a-leash
  (let [eng (fresh-engine)
        h (engine/handler eng)]
    (testing "first sight provisions the delegate row, bound to its person"
      (is (= 200 (:status (GET h "/api/.well-known/waymark" (bearer colton)))))
      (let [[row & more] (delegates-of eng "colton")]
        (is (some? row))
        (is (nil? more))
        (is (= "connector:colton" (:id row)))
        (is (= "agent" (get-in row [:data :actor_type])))
        (is (= "idp" (get-in row [:data :provenance]))
            "a durable identity: the provider vouches for both halves")
        (is (= "Claude for Colton Kopsa" (get-in row [:data :display])))
        (is (empty? (get-in row [:data :roles]))
            "the person's recovery-admin stays with the person")))
    (testing "with no grant standing, the bootstrap surface: the kind is not there"
      (is (= 404 (:status (GET h "/api/meals" (bearer colton))))))
    (testing "a grant offered to the delegate is worn on the next request, no header needed"
      (inv/create! eng :grant
                   {:audience "connector:colton"
                    :scope [{:kind "meal" :actions ["accept"]}]}
                   {:principal grants/approvals-actor
                    :id "grant-connector-1"
                    :mint? true})
      (is (= 200 (:status (GET h "/api/meals" (bearer colton)))))
      (testing "and acceptance was the delegate's own act"
        (is (= :accepted
               (:state (grants/standing-grant-for eng "connector:colton"))))))
    (testing "the MCP door itself answers the delegate"
      (let [resp (mcp-post h (bearer colton))]
        (is (= 200 (:status resp)))
        (is (seq (get-in (json resp) [:result :tools])))))
    (testing "a token from a client nobody named is the person, unchanged"
      (is (= 200 (:status (GET h "/api/meals" (bearer (assoc colton :azp "the-ui"))))))
      (is (= 1 (count (delegates-of eng "colton"))) "no second delegate row"))))

(deftest a-delegate-wearing-a-grant-keeps-the-vocabulary-to-ask-for-more
  ;; waymark-kkx.6: a presented grant narrows well-known and the agent
  ;; drops its header to see the whole vocabulary again; a delegate has
  ;; no header to drop, so its worn grant must not narrow the NAMES —
  ;; only the rows
  (let [eng (with-gate-row!
              (fresh-engine {:resources [fx/meal fx/plan]
                             :services {:mcp-servers {:gate-rpc no-tools}}}))
        h (engine/handler eng)
        kinds (fn [] (set (:kinds (json (GET h "/api/.well-known/waymark" (bearer colton))))))]
    (testing "before any grant: the bootstrap surface names every kind"
      (is (contains? (kinds) "plan"))
      (is (contains? (kinds) "meal")))
    (inv/create! eng :grant
                 {:audience "connector:colton"
                  :scope [{:kind "meal" :actions []}]}
                 {:principal grants/approvals-actor
                  :id "grant-connector-3"
                  :mint? true})
    (testing "wearing a meal-only grant: the rows narrow, the names do not"
      (is (= 200 (:status (GET h "/api/meals" (bearer colton)))))
      (is (= 404 (:status (GET h "/api/plans" (bearer colton))))
          "an ungranted kind's rows stay concealed")
      (is (contains? (kinds) "plan")
          "and its NAME is still there to ask for")
      (is (contains? (kinds) "meal")))
    (testing "and the MCP discover tool says the same"
      (let [resp (rpc h (bearer colton) "tools/call"
                      {:name "waymark_discover" :arguments {}})
            text (get-in (json resp) [:result :content 0 :text])]
        (is (= 200 (:status resp)))
        (is (str/includes? (str text) "plan") text)
        ;; waymark-r1m7: doors.ask carries what an ask needs beyond the
        ;; names — the anchor (the grant this session wears, so the ask
        ;; widens instead of replacing) and the powers the engine's
        ;; mcp_server rows name (waymark-fp62.10: the seeded bridge
        ;; row above is where "messages.read" comes from)
        (let [ask (get-in (wire/read-json text) [:doors :ask])]
          (is (= "grant-connector-3" (get-in ask [:anchor :grant_id])))
          (is (some #{"messages.read"} (:powers ask)))
          (is (str/includes? (str (:posture ask)) "approval_request"))
          ;; spec-seat.md R-7.4: a plain scope grant cites no seat, so
          ;; there is no seat door — absent, not empty
          (is (nil? (:seat ask))))))
    (testing "and the connect-time instructions say asking is the default, anchored"
      (is (str/includes? mcp/instructions "grant_id"))
      (is (str/includes? mcp/instructions "ASKING IS THE DEFAULT"))
      (is (str/includes? mcp/instructions "doors.ask.seat")
          "and that a sitter reads its seat first")
      (is (str/includes? mcp/instructions "say why and stop")))))

(deftest a-grant-that-cites-a-seat-carries-the-seat-into-discover
  ;; spec-seat.md R-7.4 / R-12.4: a firing reads doors.ask.seat FIRST.
  ;; The delegate is the one agent that can present no header, so the
  ;; seat it sits in has to reach discover off the grant the engine
  ;; finds for it — the worn grant, resolved like any other.
  (let [eng (fresh-engine)
        h (engine/handler eng)
        seat (:row (inv/create!
                    eng :seat
                    {:name "connector-clerk"
                     :charter "Decide whether a meal belongs on the list."
                     :scope [{:kind "meal" :actions ["accept"]}]
                     :standing_ttl_seconds 604800
                     :cadence_seconds 3600
                     :budget_usd_per_week 5M
                     :sitting_budget_tokens 60000}
                    {:principal person}))]
    ;; the bootstrap through the doors: the delegate asks to sit in the
    ;; office by name, the person approves, and the minted grant cites
    ;; the seat — which is what the resolve reads at every request
    (let [ask (:row (inv/create! eng :approval_request
                                 {:task "Walk the meal list this seat owns."
                                  :seat "connector-clerk"}
                                 {:principal (t/principal
                                              {:id "connector:colton"
                                               :type :agent
                                               :display "Claude"})}))]
      ;; the mint is a wire-boundary effect, so the approve runs it
      ;; the way the router does
      (grants/approval-effects!
       eng (get (inv/resources eng) :approval_request) :approve
       (inv/invoke! eng :approval_request (:id ask) :approve nil
                    {:principal person})))
    (let [resp (rpc h (bearer colton) "tools/call"
                    {:name "waymark_discover" :arguments {}})
          door (-> (json resp)
                   (get-in [:result :content 0 :text])
                   wire/read-json
                   (get-in [:doors :ask :seat]))]
      (is (= "connector-clerk" (:name door)))
      (is (= "active" (:state door)))
      (is (= 604800 (:standing_ttl_seconds door)))
      (is (nil? (:halt door)) "no wall has been met")
      (is (nil? (:stale door)) "and nothing in the scope stopped resolving")
      (is (not (contains? door :drift)) "the schedule mirror is gone, and its read-back with it")
      (is (zero? (compare 5M (get-in door [:budget :limit]))))
      (is (zero? (compare 0M (get-in door [:budget :spent])))
          "no closed sitting, nothing spent")
      (is (nil? (get-in door [:budget :resumes_at]))
          "and nothing to wait for")
      (is (= (str "/api/seats/" (:id seat) "/ledger") (:ledger door))
          "discover names the ledger route (R-11.3a)"))))

(deftest an-anchored-seat-ask-mints-the-seat-beside-its-anchor
  ;; discover's ask door tells every agent to anchor, so a connector's
  ;; seat ask names the grant it wears. Its approval must still MINT
  ;; the seat grant — extending the anchor would slide its expiry and
  ;; give no seat — and leave the anchor as it was.
  (let [eng (fresh-engine {:resources [fx/meal fx/plan]})
        h (engine/handler eng)
        load-grant #(#'grants/load-decoded eng :grant %)
        scoped-kinds #(set (map (comp name :kind) (get-in % [:data :scope])))
        connector (t/principal {:id "connector:colton"
                                :type :agent
                                :display "Claude"})
        approve! (fn [ask]
                   (grants/approval-effects!
                    eng (get (inv/resources eng) :approval_request) :approve
                    (inv/invoke! eng :approval_request (:id ask) :approve nil
                                 {:principal person})))]
    (inv/create! eng :seat
                 {:name "connector-clerk"
                  :charter "Decide whether a meal belongs on the list."
                  :scope [{:kind "meal" :actions ["accept"]}]
                  :standing_ttl_seconds 604800
                  :cadence_seconds 3600
                  :budget_usd_per_week 5M
                  :sitting_budget_tokens 60000}
                 {:principal person})
    (inv/create! eng :grant
                 {:audience "connector:colton"
                  :scope [{:kind "plan" :actions []}]}
                 {:principal grants/approvals-actor
                  :id "grant-connector-3"
                  :mint? true})
    (inv/invoke! eng :grant "grant-connector-3" :accept nil
                 {:principal grants/approvals-actor})
    (testing "an anchored non-seat ask still extends its anchor"
      (let [ask (:row (inv/create! eng :approval_request
                                   {:task "Read the meal list too."
                                    :grant_id "grant-connector-3"
                                    :scope [{:kind "meal" :actions []}]}
                                   {:principal connector}))]
        (approve! ask)
        (is (= #{"plan" "meal"} (scoped-kinds (load-grant "grant-connector-3"))))
        (is (nil? (load-grant (str "grant-" (:id ask))))
            "and mints nothing beside it")))
    (let [before (load-grant "grant-connector-3")
          ask (:row (inv/create! eng :approval_request
                                 {:task "Walk the meal list this seat owns."
                                  :seat "connector-clerk"
                                  :grant_id "grant-connector-3"}
                                 {:principal connector}))
          _ (approve! ask)
          minted (load-grant (str "grant-" (:id ask)))]
      (testing "an anchored seat ask, approved, mints and accepts a seat grant"
        (is (some? minted))
        (is (= :accepted (:state minted)))
        (is (= "connector:colton" (get-in minted [:data :audience])))
        (is (some? (get-in minted [:data :seat])) "it cites the seat"))
      (testing "and the anchored grant is unchanged"
        (is (= (:data before) (:data (load-grant "grant-connector-3"))))
        (is (= (:state before) (:state (load-grant "grant-connector-3")))))
      (testing "the connector's next request wears the seat grant, the seat's kinds only"
        (let [worn (-> (GET h "/api/.well-known/waymark" (bearer colton))
                       json :principal :worn_grant)]
          (is (= (str "grant-" (:id ask)) (:id worn)))
          (is (some #{"meal"} (:kinds worn)))
          (is (not (some #{"plan"} (:kinds worn)))
              "the anchor's kinds are not worn beside the seat's"))))))

(deftest invited-only-admits-a-delegate-exactly-when-its-person-is-a-member
  (let [eng (fresh-engine {:members :invited-only})
        h (engine/handler eng)]
    (testing "a stranger's tool is refused with the stranger's 403"
      (is (= 403 (:status (GET h "/api/.well-known/waymark" (bearer colton))))))
    (testing "once the person is a member — a human row bound to the subject — the tool is admitted"
      (inv/create! eng :member
                   {:display "Colton" :actor_type "human" :subject "colton"}
                   {:principal grants/approvals-actor :id "colton"})
      (is (= 200 (:status (GET h "/api/.well-known/waymark" (bearer colton)))))
      (is (= 1 (count (delegates-of eng "colton")))))))

(deftest acts-for-is-never-written-by-hand
  (let [h (engine/handler (fresh-engine))
        resp (h {:request-method :post :uri "/api/members"
                 :headers {"x-waymark-principal" "somebody"
                           "content-type" "application/json"}
                 :body (wire/write-json {:display "Forged" :actor_type "agent"
                                         :acts_for "colton"})})]
    (is (= 409 (:status resp)))
    (is (str/includes? (str (:body resp)) "acts_for"))))

;; ── 4. the experiment this door exists for ───────────────────────────

(deftest an-invoke-from-the-door-signs-the-transition-with-its-origin
  (let [eng (fresh-engine)
        h (engine/handler eng)]
    (inv/create! eng :meal {:name "Soup" :themes []}
                 {:principal grants/approvals-actor :id "soup"})
    (inv/create! eng :grant
                 {:audience "connector:colton"
                  :scope [{:kind "meal" :actions ["accept" "update_recipe"]}]}
                 {:principal grants/approvals-actor
                  :id "grant-connector-2"
                  :mint? true})
    (testing "an idempotent action is stamped too — the door signs every write"
      (let [resp (rpc h (bearer colton) "tools/call"
                      {:name "waymark_invoke"
                       :arguments {:kind "meal" :id "soup" :action "accept"}})]
        (is (= 200 (:status resp)))
        (is (false? (get-in (json resp) [:result :isError])) (:body resp))))
    (testing "and a non-idempotent one, which the client rule already required"
      (let [resp (rpc h (bearer colton) "tools/call"
                      {:name "waymark_invoke"
                       :arguments {:kind "meal" :id "soup" :action "update_recipe"
                                   :input {:recipe "boil"}}})]
        (is (= 200 (:status resp)))
        (is (false? (get-in (json resp) [:result :isError])) (:body resp))))
    (let [log (store/with-tx (:storage eng)
                (fn [tx] (store/transitions (:storage eng) tx
                                            {:kind :meal :resource-id "soup"}
                                            {:limit 10 :newest-first true})))
          keys (map :idempotency-key log)]
      (testing "both transitions carry mcp/<principal>/<nonce>"
        (is (= 2 (count (filter mcp/origin-of keys))) (pr-str keys))
        (is (= #{"connector:colton"}
               (into #{} (map (comp :principal mcp/origin-of)) (filter mcp/origin-of keys))))
        (is (apply distinct? (map (comp :nonce mcp/origin-of) (filter mcp/origin-of keys)))
            "the nonce keeps two verbs on one row apart"))
      (testing "the birth, minted by hand, is nobody's origin"
        (is (nil? (mcp/origin-of (:idempotency-key (last log)))))))
    (testing "the count reads the delegate's actions off the log by prefix"
      (let [all (mcp/actions-from-mcp eng)
            mine (mcp/actions-from-mcp eng {:principal-prefix "connector:"})
            nobody (mcp/actions-from-mcp eng {:principal-prefix "the-ui:"})]
        (is (= 2 (:total all)))
        (is (= {"connector:colton" 2} (:by-principal mine)))
        (is (= {"meal.accept" 1 "meal.update_recipe" 1} (:by-action mine)))
        (is (= {"meal" 2} (:by-kind mine)))
        (is (zero? (:total nobody)))
        (is (false? (:reached-cap all)))))
    (testing "the reader refuses every other shape"
      (is (nil? (mcp/origin-of "feed/2026-08-24/do_now%2Ftask%2F1/9f")))
      (is (nil? (mcp/origin-of "mcp//x")))
      (is (nil? (mcp/origin-of "mcp/only-two")))
      (is (nil? (mcp/origin-of nil)))
      (is (= {:principal "a:b/c" :nonce "n"}
             (mcp/origin-of (mcp/origin-key "a:b/c" "n")))
          "a principal id carrying the separator round-trips"))))

;; ── 5. a batch from the door counts per row ──────────────────────────

;; a suite-local kind with a bulk door: the fixtures' meal has none,
;; and the sighting batch the connector's first session wanted is a
;; non-idempotent verb with an input per row
(r/defhandler tag-handler [row inp _ctx]
  (assoc-in row [:data :label] (:label inp)))

(def ^:private chore
  (r/resource
   {:kind :chore
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:label {:optional true} [:maybe [:string {:max 20}]]]]
    :actions
    {:tag {:from #{:open} :to :open
           :bulk {:max-items 10}
           :input [:map [:label [:string {:min 1 :max 20}]]]
           :safety {:idempotent false :reversible true :confirm false}
           :handler tag-handler}
     :complete {:from #{:open} :to :done
                :bulk {:max-items 10}
                :safety {:idempotent true :reversible false :confirm false
                         :one-way "Done is done."}}}}))

(deftest a-bulk-invoke-from-the-door-counts-one-action-per-row
  ;; waymark-pywy.5: whole-call idempotency used to leave the items
  ;; unsigned, so a fourteen-row batch from the connector counted as
  ;; zero writes — the one action the experiment most wanted to see.
  ;; Every item now carries the call's origin-key as a stamp, and the
  ;; store still holds one record per call.
  (let [eng (fresh-engine {:resources [fx/meal chore]})
        h (engine/handler eng)
        st (:storage eng)
        ids (mapv (fn [i]
                    (let [id (str "chore-" i)]
                      (inv/create! eng :chore {:title (str "chore " i)}
                                   {:principal grants/approvals-actor :id id})
                      id))
                  (range 5))
        [a b c d e] ids
        log-of (fn [id]
                 (store/with-tx st
                   (fn [tx] (store/transitions st tx {:kind :chore :resource-id id}
                                               {:limit 10 :newest-first true}))))
        invoke (fn [args]
                 (let [resp (rpc h (bearer colton) "tools/call"
                                 {:name "waymark_invoke" :arguments args})
                       result (:result (json resp))]
                   (is (= 200 (:status resp)) (:body resp))
                   (is (false? (:isError result)) (:body resp))
                   (wire/read-json (get-in result [:content 0 :text]))))]
    (inv/create! eng :grant
                 {:audience "connector:colton"
                  :scope [{:kind "chore" :actions ["tag" "complete"]}]}
                 {:principal grants/approvals-actor
                  :id "grant-connector-4"
                  :mint? true})
    (testing "items: three rows, three signed transitions, one record"
      (let [report (invoke {:kind "chore" :action "tag"
                            :items [{:id a :input {:label "one"}}
                                    {:id b :input {:label "two"}}
                                    {:id c :input {:label "three"}}]})
            keys (mapv #(:idempotency-key (first (log-of %))) [a b c])]
        (is (= 3 (get-in report [:data :succeeded])))
        (is (every? mcp/origin-of keys) (pr-str keys))
        (is (= 1 (count (distinct keys))) "one call, one key, stamped thrice")
        (is (= #{"connector:colton"}
               (into #{} (map (comp :principal mcp/origin-of)) keys)))
        (let [rec (store/with-tx st #(store/idempotency-lookup st % (first keys) :chore))]
          (is (= (keyword "bulk:tag") (:action rec))
              "the one record under the key is the call's report, not an item's"))
        (is (= {:total 3 :by-action {"chore.tag" 3} :by-kind {"chore" 3}}
               (select-keys (mcp/actions-from-mcp eng {:principal-prefix "connector:"})
                            [:total :by-action :by-kind])))))
    (testing "ids: the shared-input shape signs each row the same way"
      (let [report (invoke {:kind "chore" :action "complete" :ids [d e]})
            keys (mapv #(:idempotency-key (first (log-of %))) [d e])]
        (is (= 2 (get-in report [:data :succeeded])))
        (is (every? mcp/origin-of keys) (pr-str keys))
        (is (= 1 (count (distinct keys))))
        (is (not= (first keys) (:idempotency-key (first (log-of a))))
            "a different call, a different nonce")
        (is (= (keyword "bulk:complete")
               (:action (store/with-tx
                          st #(store/idempotency-lookup st % (first keys) :chore)))))))
    (testing "the experiment's number reads five actions, per row, per verb"
      (let [mine (mcp/actions-from-mcp eng {:principal-prefix "connector:"})]
        (is (= 5 (:total mine)))
        (is (= {"connector:colton" 5} (:by-principal mine)))
        (is (= {"chore.tag" 3 "chore.complete" 2} (:by-action mine)))
        (is (= {"chore" 5} (:by-kind mine)))
        (is (zero? (:total (mcp/actions-from-mcp eng {:principal-prefix "the-ui:"}))))))
    (testing "the births, minted by hand, are nobody's"
      (is (every? nil? (map #(mcp/origin-of (:idempotency-key (last (log-of %))))
                            ids))))))
