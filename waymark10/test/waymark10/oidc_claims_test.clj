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
