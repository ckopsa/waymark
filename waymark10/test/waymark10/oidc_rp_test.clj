(ns waymark10.oidc-rp-test
  "The RP browser flow without a browser: a locally-minted RSA
  keypair as the IdP's signing key (static JWKS), a local http-kit
  stub as its token endpoint, and the wrap composed over a marker
  handler. No engine, no database.

  Covered: the login redirect's shape (state, PKCE, the stash
  cookie), the full code→session round trip and the session
  resolver's reading of it, state mismatch, a stale/absent stash,
  tampered session cookies (fall through — never a 401), the
  anonymous-HTML ui redirect, and the no-:rp identity wrap."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [org.httpkit.server :as server]
            [waymark10.server.oidc :as oidc]
            [waymark10.server.oidc-rp :as rp]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)))

;; ── the IdP: one keypair, one static JWKS, one stub token door ──────

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "rp-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "test-key" :alg "RS256" :use "sig")]})

(defn- now-secs [] (quot (System/currentTimeMillis) 1000))

(defn- mint-access [claims]
  (jwt/sign (merge {:iss issuer :aud audience :exp (+ (now-secs) 600)}
                   claims)
            (.getPrivate keypair)
            {:alg :rs256 :header {:kid "test-key"}}))

(def ^:private idp-response
  "The stub's next answer — tests script it per case."
  (atom nil))

(def ^:private idp-saw (atom nil))

(def ^:dynamic *token-endpoint* nil)

(use-fixtures :once
  (fn [f]
    (let [srv (server/run-server
               (fn [req]
                 (reset! idp-saw (slurp (:body req)))
                 (or @idp-response
                     {:status 400 :body "{\"error\":\"unscripted\"}"}))
               {:port 0 :legacy-return-value? false})]
      (try
        (binding [*token-endpoint*
                  (str "http://localhost:" (server/server-port srv) "/token")]
          (f))
        (finally (server/server-stop! srv))))))

;; ── the app under test: the wrap over a marker handler ──────────────

(def ^:private session-secret "a-32-byte-session-secret-for-test!!")

(defn- oidc-config [& [rp-extra]]
  (oidc/config
   {:issuer issuer :audience audience :jwks jwks
    :roles-claim [:realm_access :roles]
    :rp (merge {:client-id "rp-test"
                :client-secret "shh"
                :app-url "http://app.test"
                :session-secret session-secret
                :token-endpoint *token-endpoint*}
               rp-extra)}))

(defn- app [& [rp-extra]]
  ((rp/wrap {:oidc (oidc-config rp-extra)})
   (fn [_req] {:status 200 :headers {} :body "through"})))

(defn- get-req [uri & [{:keys [query headers]}]]
  {:request-method :get :uri uri :query-string query
   :headers (or headers {})})

(defn- location [resp] (get-in resp [:headers "Location"]))

(defn- loc-params
  "The redirect URL's query string as a map."
  [resp]
  (into {}
        (map #(let [[k v] (str/split % #"=" 2)]
                [k (java.net.URLDecoder/decode (or v "") "UTF-8")]))
        (str/split (second (str/split (location resp) #"\?" 2)) #"&")))

(defn- cookie-of
  "Set-Cookie (string or the callback's vector) → {name value}."
  [resp]
  (into {}
        (map #(let [[kv] (str/split % #";" 2)
                    [k v] (str/split kv #"=" 2)]
                [k (or v "")]))
        (let [sc (get-in resp [:headers "Set-Cookie"])]
          (if (string? sc) [sc] sc))))

;; ── login ───────────────────────────────────────────────────────────

(deftest login-redirects-to-the-idp
  (let [resp ((app) (get-req "/auth/login" {:query "return-to=/api/-/ui"}))
        q (loc-params resp)]
    (is (= 302 (:status resp)))
    (is (str/starts-with? (location resp)
                          (str issuer "/protocol/openid-connect/auth?")))
    (is (= "code" (q "response_type")))
    (is (= "rp-test" (q "client_id")))
    (is (= "http://app.test/auth/callback" (q "redirect_uri")))
    (is (= "S256" (q "code_challenge_method")))
    (is (seq (q "state")))
    (is (seq (q "code_challenge")))
    (is (contains? (cookie-of resp) "waymark_auth"))))

(deftest login-sanitizes-return-to
  (let [stash-of (fn [resp]
                   (jwt/unsign (get (cookie-of resp) "waymark_auth")
                               session-secret {:alg :hs256}))]
    (testing "an absolute return-to is refused"
      (is (= "/"
             (:return-to (stash-of ((app) (get-req "/auth/login"
                                                   {:query "return-to=https%3A%2F%2Fevil.test"})))))))
    (testing "a protocol-relative return-to is refused"
      (is (= "/"
             (:return-to (stash-of ((app) (get-req "/auth/login"
                                                   {:query "return-to=%2F%2Fevil.test"})))))))))

;; ── callback ────────────────────────────────────────────────────────

(defn- login-then-callback
  "Drive the whole dance: /auth/login for the stash + state, the
  scripted IdP answer, /auth/callback with the stash cookie."
  [& [{:keys [state-override cookie-override]}]]
  (let [a (app)
        login-resp (a (get-req "/auth/login" {:query "return-to=/api/payouts"}))
        state (get (loc-params login-resp) "state")
        stash (get (cookie-of login-resp) "waymark_auth")]
    (a (get-req "/auth/callback"
                {:query (str "code=fake-code&state=" (or state-override state))
                 :headers {"cookie" (or cookie-override
                                        (str "waymark_auth=" stash))}}))))

(deftest callback-mints-the-session
  (reset! idp-response
          {:status 200 :headers {"Content-Type" "application/json"}
           :body (wire/write-json
                  {:access_token (mint-access
                                  {:sub "alice" :name "Alice"
                                   :realm_access {:roles ["ops" "finance"]}})})})
  (let [resp (login-then-callback)]
    (is (= 302 (:status resp)))
    (is (= "/api/payouts" (location resp)))
    (testing "the exchange carried PKCE and the client secret"
      (is (str/includes? @idp-saw "code_verifier="))
      (is (str/includes? @idp-saw "client_secret=shh")))
    (testing "the session cookie resolves to the principal"
      (let [session (get (cookie-of resp) "waymark_session")
            p (rp/resolve-session (oidc-config)
                                  {:headers {"cookie" (str "waymark_session=" session)}})]
        (is (= "alice" (:id p)))
        (is (= #{"ops" "finance"} (:roles p)))
        (is (= :human (:type p)))
        (is (= "Alice" (:display p)))))
    (testing "the stash cookie is cleared"
      (is (= "" (get (cookie-of resp) "waymark_auth"))))))

(deftest callback-refuses-a-state-mismatch
  (reset! idp-response nil)
  (let [resp (login-then-callback {:state-override "not-the-state"})]
    (is (= 400 (:status resp)))))

(deftest callback-refuses-without-the-stash
  (reset! idp-response nil)
  (let [resp ((app) (get-req "/auth/callback" {:query "code=x&state=y"}))]
    (is (= 400 (:status resp)))))

(deftest callback-surfaces-an-idp-refusal
  (reset! idp-response {:status 400 :body "{\"error\":\"invalid_grant\"}"})
  (is (= 502 (:status (login-then-callback)))))

(deftest callback-refuses-an-unverifiable-token
  (testing "the IdP answering with a token signed by a stranger"
    (let [stranger (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                                       (.initialize 2048)))]
      (reset! idp-response
              {:status 200 :headers {"Content-Type" "application/json"}
               :body (wire/write-json
                      {:access_token
                       (jwt/sign {:iss issuer :aud audience :sub "mallory"
                                  :exp (+ (now-secs) 600)}
                                 (.getPrivate stranger)
                                 {:alg :rs256 :header {:kid "test-key"}})})})
      (is (= 401 (:status (login-then-callback)))))))

;; ── the session resolver ────────────────────────────────────────────

(deftest resolver-falls-through-never-refuses
  (let [oidc (oidc-config)]
    (testing "no cookie"
      (is (nil? (rp/resolve-session oidc {:headers {}}))))
    (testing "a tampered cookie"
      (is (nil? (rp/resolve-session
                 oidc {:headers {"cookie" "waymark_session=garbage.garbage.garbage"}}))))
    (testing "an expired session"
      (let [dead (jwt/sign {:sub "alice" :roles ["ops"] :exp (- (now-secs) 10)}
                           session-secret {:alg :hs256})]
        (is (nil? (rp/resolve-session
                   oidc {:headers {"cookie" (str "waymark_session=" dead)}})))))
    (testing "no :rp config at all"
      (is (nil? (rp/resolve-session
                 (oidc/config {:issuer issuer :audience audience :jwks jwks})
                 {:headers {"cookie" "waymark_session=x"}}))))))

;; ── logout ──────────────────────────────────────────────────────────

(deftest logout-clears-and-redirects
  (let [resp ((app) (get-req "/auth/logout"))]
    (is (= 302 (:status resp)))
    (is (str/starts-with? (location resp)
                          (str issuer "/protocol/openid-connect/logout?")))
    (testing "post-logout lands on login — the URI clients register"
      (is (= "http://app.test/auth/login"
             (get (loc-params resp) "post_logout_redirect_uri"))))
    (is (= "" (get (cookie-of resp) "waymark_session")))))

;; ── the ui redirect nicety ──────────────────────────────────────────

(deftest anonymous-html-ui-gets-sent-to-login
  (let [a (app {:login-redirect? true})]
    (testing "a browser with no session"
      (let [resp (a (get-req "/api/-/ui" {:headers {"accept" "text/html"}}))]
        (is (= 302 (:status resp)))
        (is (str/starts-with? (location resp) "/auth/login?return-to="))))
    (testing "the root path bounces too — the ui's canonical address"
      (let [resp (a (get-req "/" {:headers {"accept" "text/html"}}))]
        (is (= 302 (:status resp)))
        (is (str/starts-with? (location resp) "/auth/login?return-to="))))
    (testing "an API client keeps its honest answer"
      (is (= 200 (:status (a (get-req "/api/-/ui"
                                      {:headers {"accept" "application/json"}}))))))
    (testing "a garbage session cookie still bounces — presence is not validity"
      (is (= 302 (:status (a (get-req "/api/-/ui"
                                      {:headers {"accept" "text/html"
                                                 "cookie" "waymark_session=x"}}))))))
    (testing "a browser holding a REAL session passes through"
      (let [session (jwt/sign {:sub "alice" :roles ["ops"]
                               :exp (+ (now-secs) 600)}
                              session-secret {:alg :hs256})]
        (is (= 200 (:status (a (get-req "/api/-/ui"
                                        {:headers {"accept" "text/html"
                                                   "cookie" (str "waymark_session=" session)}})))))))))

;; ── the require-auth gate ───────────────────────────────────────────

(deftest require-auth-closes-the-surface
  (let [a (app {:require-auth? true})
        session (jwt/sign {:sub "alice" :roles ["ops"]
                           :exp (+ (now-secs) 600)}
                          session-secret {:alg :hs256})]
    (testing "an anonymous API request gets the honest 401"
      (let [resp (a (get-req "/api/payouts"))]
        (is (= 401 (:status resp)))
        (is (= "Bearer realm=\"waymark\""
               (get-in resp [:headers "WWW-Authenticate"])))))
    (testing "an anonymous POST gets the 401 too — not a redirect"
      (is (= 401 (:status (a {:request-method :post :uri "/api/teams"
                              :headers {}})))))
    (testing "an anonymous browser goes to login instead"
      (let [resp (a (get-req "/api/payouts"
                             {:headers {"accept" "text/html"}}))]
        (is (= 302 (:status resp)))
        (is (str/starts-with? (location resp) "/auth/login?return-to="))))
    (testing "dev headers are NOT a credential"
      (is (= 401 (:status (a (get-req "/api/teams"
                                      {:headers {"x-waymark-principal" "root"}}))))))
    (testing "a valid session passes"
      (is (= 200 (:status (a (get-req "/api/payouts"
                                      {:headers {"cookie" (str "waymark_session=" session)}}))))))
    (testing "a garbage session does not"
      (is (= 401 (:status (a (get-req "/api/payouts"
                                      {:headers {"cookie" "waymark_session=x"}}))))))
    (testing "a Bearer-SHAPED header passes the GATE (wrap-identity judges it)"
      (is (= 200 (:status (a (get-req "/api/payouts"
                                      {:headers {"authorization" "Bearer anything"}}))))))
    (testing "a malformed Authorization is NOT a credential — the
              resolver would never judge it, so anonymous would slip
              the gate wearing an empty header"
      (is (= 401 (:status (a (get-req "/api/payouts"
                                      {:headers {"authorization" "Bearer "}})))))
      (is (= 401 (:status (a (get-req "/api/payouts"
                                      {:headers {"authorization" "Basic dXNlcjpwdw=="}}))))))
    (testing "the /auth doors stay open to the anonymous"
      (is (= 302 (:status (a (get-req "/auth/login"))))))))

(deftest no-rp-config-is-the-identity-wrap
  (let [a ((rp/wrap {:oidc (oidc/config {:issuer issuer :audience audience
                                         :jwks jwks})})
           (fn [_req] {:status 200 :headers {} :body "through"}))]
    (is (= 200 (:status (a (get-req "/auth/login")))))))

;; ── wrap-handler: the probe outside the gate ────────────────────────

(deftest health-probe-answers-outside-the-gate
  (let [a ((rp/wrap-handler {:oidc (oidc-config {:require-auth? true})})
           (fn [_req] {:status 200 :headers {} :body "through"}))]
    (testing "the anonymous probe answers 200 — liveness needs no credential"
      (is (= 200 (:status (a (get-req "/healthz"))))))
    (testing "the gate still closes everything else"
      (is (= 401 (:status (a (get-req "/api/payouts"))))))
    (testing "a POST to the probe's path is not the probe"
      (is (= 401 (:status (a {:request-method :post :uri "/healthz"
                              :headers {}})))))))

(deftest health-probe-rides-the-no-oidc-app-too
  (let [a ((rp/wrap-handler {})
           (fn [_req] {:status 200 :headers {} :body "through"}))]
    (is (= 200 (:status (a (get-req "/healthz")))))
    (testing "everything else passes through untouched"
      (is (= 200 (:status (a (get-req "/api/anything"))))))))

;; ── from-env: the deployed spelling of the same config ──────────────

(deftest from-env-reads-the-keycloak-shapes
  (testing "no issuer = no config — the undeployed app pays nothing"
    (is (nil? (oidc/from-env {}))))
  (testing "issuer alone is bearer-only, defaults filled"
    (let [o (oidc/from-env {"WAYMARK10_OIDC_ISSUER" "https://idp/realms/home"
                            "WAYMARK10_OIDC_AUDIENCE" "mealplan10"})]
      (is (= "https://idp/realms/home/protocol/openid-connect/certs"
             (:jwks-uri o)))
      (is (= [:realm_access :roles] (:roles-claim o)))
      (is (nil? (:rp o)))))
  (testing "a client id adds the :rp browser flow"
    (let [o (oidc/from-env
             {"WAYMARK10_OIDC_ISSUER" "https://idp/realms/home"
              "WAYMARK10_OIDC_CLIENT_ID" "mealplan10"
              "WAYMARK10_OIDC_CLIENT_SECRET" "shh"
              "WAYMARK10_OIDC_APP_URL" "https://meals.test"
              "WAYMARK10_OIDC_SESSION_SECRET" "s3cret"
              "WAYMARK10_OIDC_REQUIRE_AUTH" "1"})]
      (testing "the audience defaults to the client's own id"
        (is (= "mealplan10" (:audience o))))
      (is (= {:client-id "mealplan10" :client-secret "shh"
              :app-url "https://meals.test" :session-secret "s3cret"
              :require-auth? true :login-redirect? true}
             (:rp o)))
      (testing "and the whole shape survives config's validation"
        (is (map? (oidc/config (assoc o :jwks jwks)))))))
  (testing "LOGIN_REDIRECT=0 keeps the honest 401s"
    (is (false? (get-in (oidc/from-env
                         {"WAYMARK10_OIDC_ISSUER" "https://idp/realms/home"
                          "WAYMARK10_OIDC_CLIENT_ID" "x"
                          "WAYMARK10_OIDC_LOGIN_REDIRECT" "0"})
                        [:rp :login-redirect?])))))

;; ── the connector door's three variables (docs/spec-connector-door.md) ──

(deftest from-env-reads-the-connector-door
  (testing "the external name is read at the top level, client id or not"
    (is (= "https://work.test"
           (:app-url (oidc/from-env {"WAYMARK10_OIDC_ISSUER" "https://idp/realms/home"
                                     "WAYMARK10_OIDC_AUDIENCE" "work"
                                     "WAYMARK10_OIDC_APP_URL" "https://work.test"})))))
  (testing "delegate clients: id=Display pairs, a bare id displays as itself"
    (is (= {"waymark10-connector-claude" "Claude" "other-tool" "other-tool"}
           (:delegate-clients
            (oidc/from-env {"WAYMARK10_OIDC_ISSUER" "https://idp/realms/home"
                            "WAYMARK10_OIDC_AUDIENCE" "work"
                            "WAYMARK10_OIDC_DELEGATE_CLIENTS"
                            " waymark10-connector-claude=Claude, other-tool ,"})))))
  (testing "resource scopes, a comma list"
    (is (= ["waymark-workqueue10" "openid"]
           (:resource-scopes
            (oidc/from-env {"WAYMARK10_OIDC_ISSUER" "https://idp/realms/home"
                            "WAYMARK10_OIDC_AUDIENCE" "work"
                            "WAYMARK10_OIDC_RESOURCE_SCOPES" "waymark-workqueue10,openid"})))))
  (testing "unset, none of the three keys appears"
    (let [o (oidc/from-env {"WAYMARK10_OIDC_ISSUER" "https://idp/realms/home"
                            "WAYMARK10_OIDC_AUDIENCE" "work"})]
      (is (not (contains? o :app-url)))
      (is (not (contains? o :delegate-clients)))
      (is (not (contains? o :resource-scopes)))))
  (testing "and the whole shape survives config's validation"
    (is (map? (oidc/config
               (assoc (oidc/from-env {"WAYMARK10_OIDC_ISSUER" "https://idp/realms/home"
                                      "WAYMARK10_OIDC_AUDIENCE" "work"
                                      "WAYMARK10_OIDC_APP_URL" "https://work.test"
                                      "WAYMARK10_OIDC_DELEGATE_CLIENTS" "c=Claude"})
                      :jwks jwks))))))
