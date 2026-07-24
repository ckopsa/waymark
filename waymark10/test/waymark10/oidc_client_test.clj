(ns waymark10.oidc-client-test
  "The outbound credential without an IdP: a local http-kit stub as
  the token endpoint, counting every mint it serves. Covered: the
  cache holds while the token is fresh, a token expiring inside the
  skew re-mints, a refusal throws (an unreachable IdP is one
  unreachable feed, never a silently-empty one), and outbound-from-env
  wiring — nil without env, the engine's own client with it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [org.httpkit.server :as server]
            [waymark10.server.oidc :as oidc]
            [waymark10.wire :as wire]))

(def ^:private mints (atom 0))
(def ^:private answer
  "The stub's next answer — tests script it per case."
  (atom {:expires_in 300}))

(def ^:dynamic *endpoint* nil)

(use-fixtures :once
  (fn [f]
    (let [srv (server/run-server
               (fn [_req]
                 (let [{:keys [status expires_in]} @answer
                       n (swap! mints inc)]
                   (if (= 400 status)
                     {:status 400 :body "{\"error\":\"invalid_client\"}"}
                     {:status 200
                      :headers {"Content-Type" "application/json"}
                      :body (wire/write-json
                             {:access_token (str "tok-" n)
                              :expires_in expires_in})})))
               {:port 0 :legacy-return-value? false})]
      (try
        (binding [*endpoint*
                  (str "http://localhost:" (server/server-port srv) "/token")]
          (f))
        (finally (server/server-stop! srv))))))

(defn- fresh-fn [& [{:as overrides}]]
  (oidc/client-credentials-fn
   (merge {:issuer "https://idp.test/realms/home"
           :client-id "choreplan10" :client-secret "shh"
           :scope "waymark-mealplan10"
           :token-endpoint *endpoint*}
          overrides)))

(deftest a-fresh-token-is-cached
  (reset! mints 0)
  (reset! answer {:expires_in 300})
  (let [f (fresh-fn)]
    (is (= (f) (f) (f)) "three calls, one token")
    (is (= 1 @mints) "and one mint")))

(deftest an-expiring-token-re-mints
  (reset! mints 0)
  (reset! answer {:expires_in 5})       ; inside the 30s skew
  (let [f (fresh-fn)]
    (is (not= (f) (f)) "each call finds the cache too near exp")
    (is (= 2 @mints))))

(deftest a-refusal-throws
  (reset! answer {:status 400})
  (is (thrown-with-msg? Exception #"refused the client-credentials mint"
                        ((fresh-fn)))))

(deftest outbound-from-env-wiring
  (testing "no OIDC env = nil — dev and tests pay nothing"
    (is (nil? (oidc/outbound-token-fn "waymark-mealplan10" {}))))
  (testing "bearer-only env (no client) = nil too"
    (is (nil? (oidc/outbound-token-fn
               "waymark-mealplan10"
               {"WAYMARK10_OIDC_ISSUER" "https://idp.test/realms/home"
                "WAYMARK10_OIDC_AUDIENCE" "x"}))))
  (testing "the engine's own client mints"
    (reset! mints 0)
    (reset! answer {:expires_in 300})
    (let [f (oidc/outbound-token-fn
             "waymark-mealplan10"
             {"WAYMARK10_OIDC_ISSUER" "https://idp.test/realms/home"
              "WAYMARK10_OIDC_CLIENT_ID" "choreplan10"
              "WAYMARK10_OIDC_CLIENT_SECRET" "shh"
              "WAYMARK10_OIDC_APP_URL" "https://rod.test"
              "WAYMARK10_OIDC_SESSION_SECRET" "s"
              "WAYMARK10_OIDC_TOKEN_ENDPOINT" *endpoint*})]
      (is (some? (f)))
      (is (= 1 @mints)))))
