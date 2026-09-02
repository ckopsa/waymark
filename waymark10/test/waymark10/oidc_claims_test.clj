(ns waymark10.oidc-claims-test
  "The claim options accept a keyword or a path vector — Keycloak's
  realm roles live at [:realm_access :roles], not at the top level.
  Pure resolver tests: a locally-minted RSA keypair, a static JWKS,
  no engine, no database, no network."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.oidc :as oidc])
  (:import (java.security KeyPairGenerator)))

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "claims-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "test-key" :alg "RS256" :use "sig")]})

(defn- mint [claims]
  (jwt/sign (merge {:iss issuer :aud audience
                    :exp (+ (quot (System/currentTimeMillis) 1000) 600)}
                   claims)
            (.getPrivate keypair)
            {:alg :rs256 :header {:kid "test-key"}}))

(defn- resolve-with [oidc-opts claims]
  (oidc/resolve-principal
   (oidc/config (merge {:issuer issuer :audience audience :jwks jwks}
                       oidc-opts))
   {"authorization" (str "Bearer " (mint claims))}))

(deftest roles-claim-as-keyword
  (let [p (resolve-with {:roles-claim :roles}
                        {:sub "alice" :roles ["ops" "admin"]})]
    (is (= #{"ops" "admin"} (:roles p)))))

(deftest roles-claim-as-path
  (testing "the Keycloak shape: realm_access.roles"
    (let [p (resolve-with {:roles-claim [:realm_access :roles]}
                          {:sub "alice"
                           :realm_access {:roles ["ops"]}})]
      (is (= #{"ops"} (:roles p)))))
  (testing "a path that misses yields no roles, not a throw"
    (let [p (resolve-with {:roles-claim [:realm_access :roles]}
                          {:sub "alice"})]
      (is (= #{} (:roles p))))))

(deftest type-claim-as-path
  (let [p (resolve-with {:type-claim [:waymark :actor_type]}
                        {:sub "bot-7" :waymark {:actor_type "agent"}})]
    (is (= :agent (:type p)))))

;; ── the connector door's delegate (docs/spec-connector-door.md § 3) ──

(def ^:private delegates {:delegate-clients {"connector" "Claude"}})

(deftest a-delegate-client-token-is-an-agent-acting-for-its-signer
  (let [p (resolve-with (merge delegates {:roles-claim [:realm_access :roles]})
                        {:sub "alice" :azp "connector" :name "Alice Example"
                         :realm_access {:roles ["recovery-admin"]}})]
    (testing "one agent per (tool, person), named for both"
      (is (= "connector:alice" (:id p)))
      (is (= :agent (:type p)))
      (is (= "Claude for Alice Example" (:display p))))
    (testing "the person's roles stay with the person"
      (is (= #{} (:roles p))))
    (testing "and the row will know whom it acts for"
      (is (= "alice" (:acts-for p))))))

(deftest a-client-nobody-named-is-the-person-unchanged
  (let [p (resolve-with delegates {:sub "alice" :azp "the-ui" :name "Alice"})]
    (is (= "alice" (:id p)))
    (is (= :human (:type p)))
    (is (nil? (:acts-for p)))))

(deftest config-refuses-a-malformed-delegate-table
  (is (thrown? Exception
               (oidc/config {:issuer issuer :audience audience :jwks jwks
                             :delegate-clients ["connector"]}))))

;; ── the challenge and the protected-resource document ────────────────

(defn- refusal-of [oidc-opts claims]
  (try (resolve-with oidc-opts claims)
       nil
       (catch Exception e (ex-data e))))

(deftest a-refusal-points-at-discovery-when-the-engine-knows-its-name
  (let [wrong-audience {:sub "alice" :aud "somebody-else"}]
    (testing "with :app-url, resource_metadata rides the challenge"
      (let [d (refusal-of {:app-url "https://app.test/"} wrong-audience)]
        (is (= 401 (:status d)))
        (is (= (str "Bearer realm=\"waymark\", error=\"invalid_token\", "
                    "resource_metadata=\"https://app.test/.well-known/"
                    "oauth-protected-resource/api/-/mcp\"")
               (get-in d [:waymark10/headers "WWW-Authenticate"])))))
    (testing "without it, the header is what it always was"
      (is (= "Bearer realm=\"waymark\", error=\"invalid_token\""
             (get-in (refusal-of {} wrong-audience)
                     [:waymark10/headers "WWW-Authenticate"]))))))

(deftest the-protected-resource-document
  (let [base {:issuer issuer :audience audience :jwks jwks}]
    (testing "nothing to advertise without an external base URL"
      (is (nil? (oidc/protected-resource (oidc/config base)))))
    (testing "the door, the issuer, the header method — scopes only when named"
      (let [doc (oidc/protected-resource
                 (oidc/config (assoc base :app-url "https://app.test")))]
        (is (= "https://app.test/api/-/mcp" (:resource doc)))
        (is (= [issuer] (:authorization_servers doc)))
        (is (= ["header"] (:bearer_methods_supported doc)))
        (is (not (contains? doc :scopes_supported)))))
    (testing "the browser-facing issuer wins when the deployment splits them"
      (let [doc (oidc/protected-resource
                 (oidc/config (assoc base
                                     :app-url "https://app.test"
                                     :resource-scopes ["waymark-x"]
                                     :rp {:client-id "ui" :client-secret "s"
                                          :app-url "https://app.test"
                                          :session-secret "k"
                                          :frontend-issuer "https://idp.public/realms/home"})))]
        (is (= ["https://idp.public/realms/home"] (:authorization_servers doc)))
        (is (= ["waymark-x"] (:scopes_supported doc)))))))
