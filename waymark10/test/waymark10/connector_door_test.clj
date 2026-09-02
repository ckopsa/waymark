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
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.oidc-rp :as rp]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
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

(defn- json [resp] (wire/read-json (:body resp)))

(defn- GET [h uri headers]
  (h {:request-method :get :uri uri :headers headers}))

(defn- mcp-post [h headers]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json {:jsonrpc "2.0" :id 1 :method "tools/list"})}))

(def ^:private discovery
  "https://app.test/.well-known/oauth-protected-resource/api/-/mcp")

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
          (is (= [issuer] (:authorization_servers doc)))
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
    (testing "the gate's own 401 carries the same challenge"
      (let [resp (mcp-post h {})]
        (is (= 401 (:status resp)))
        (is (= (str "Bearer realm=\"waymark\", resource_metadata=\"" discovery "\"")
               (get-in resp [:headers "WWW-Authenticate"])))))))

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

(defn- rpc [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json {:jsonrpc "2.0" :id 1 :method method
                              :params params})}))

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
