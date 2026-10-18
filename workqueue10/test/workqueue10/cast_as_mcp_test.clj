(ns workqueue10.cast-as-mcp-test
  "Clone guard, end to end: on a clone of orgs/example, a seat with
  acts_as_cast sits through the MCP door and calls waymark_invoke kind
  `request`, action `create`, `as` a seeded cast member — and the
  request's log carries the cast member as its actor and the seat as
  acted_by. Off a clone, the same call is refused 403 and writes no
  row. waymark10.cast-as-test judges `router/cast-principal` directly;
  this suite stands the seat up over the connector door instead.

  Memory storage, a locally-minted RSA keypair as the IdP's signing
  key, the real handler: no database, no network. The connector
  helpers are mcp_refusals_test's, re-spelled because they are private
  there.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.cast-as-mcp-test"
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.test :refer [deftest is testing]]
            [waymark10.dev :as dev]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.org :as org]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire]
            [workqueue10.main :as main])
  (:import (java.security KeyPairGenerator)
           (java.time Instant)))

;; ── the connector door, as mcp_refusals_test stands it up ───────────

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "cast-as-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "cast-as-key" :alg "RS256" :use "sig")]})

(defn- mint [claims]
  (jwt/sign (merge {:iss issuer :aud audience
                    :exp (+ (quot (System/currentTimeMillis) 1000) 600)}
                   claims)
            (.getPrivate keypair)
            {:alg :rs256 :header {:kid "cast-as-key"}}))

;; the org's owner, as the seed's cast names them
(def ^:private owner-claims {:sub "owner" :azp "connector" :name "Rosa Delgado"})

(defn- bearer [claims] {"authorization" (str "Bearer " (mint claims))})

(def ^:private owner (t/principal {:id "owner" :display "Rosa Delgado"}))

(def ^:private example (org/org-name "orgs/example"))

(defn- fresh-engine
  "orgs/example beside the factory, as FACTORY10_ONLY=1 with
  WAYMARK10_ORG boots it, its seed loaded; a clone when `clone?`."
  [clone?]
  (let [at (atom (Instant/parse "2026-10-18T09:00:00Z"))
        eng (engine/engine {:storage (memory/storage)
                            :resources (main/with-org (main/factory-only-resources) example)
                            :clone? clone?
                            :suppress-mirror-refresh true
                            :now-fn (fn [] @at)
                            :oidc {:issuer issuer :audience audience :jwks jwks
                                   :app-url "https://app.test/"
                                   :delegate-clients {"connector" "Claude"}}})]
    (main/org-on-boot! eng example {:params {}})
    eng))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- rpc [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json (cond-> {:jsonrpc "2.0" :id 1 :method method}
                               params (assoc :params params)))}))

(defn- tool [h headers tool-name args]
  (let [resp (rpc h headers "tools/call" {:name tool-name :arguments args})]
    (assoc (get-in (json resp) [:result]) :status (:status resp))))

(defn- text-of [result] (str (get-in result [:content 0 :text])))
(defn- doc-of [result] (wire/read-json (text-of result)))

(defn- initialize! [h]
  (get-in (rpc h (bearer owner-claims) "initialize"
               {:protocolVersion mcp/protocol-version
                :capabilities {} :clientInfo {:name "cast-as" :version "0"}})
          [:headers "Mcp-Session-Id"]))

(defn- with-session [sid] (assoc (bearer owner-claims) "mcp-session-id" sid))

;; ── the world seat ──────────────────────────────────────────────────

(def ^:private a-key
  "128 bits of base64url — what a machine mints and no hand types."
  "Y2FzdC1hcy1tY3AtdGVzdC1rZXktMDAwMDAx")

(defn- add-model! [eng]
  (:row (inv/create! eng :model
                     {:name "cast-as-test-model" :display "Cast 1"
                      :vendor "anthropic" :tier "strong"
                      :price_input_per_mtok 3M
                      :price_output_per_mtok 15M
                      :price_cache_read_per_mtok 0.3M
                      :price_cache_write_per_mtok 3.75M}
                     {:principal owner})))

(defn- open-seat!
  "A world seat over the org's `request` create door, its key offered.
  acts_as_cast only where `cast?`: off a clone the seat door refuses it."
  [eng cast?]
  (let [model (add-model! eng)
        seat (:row (inv/create! eng :seat
                                (cond-> {:name "world"
                                         :charter "Play the customers who file requests."
                                         :scope [{:kind "request" :actions ["create"]}]
                                         :held_for [(:id model)]
                                         :standing_ttl_seconds 604800
                                         :cadence_seconds 3600
                                         :budget_usd_per_week 5M
                                         :sitting_budget_tokens 60000}
                                  cast? (assoc :acts_as_cast true))
                                {:principal owner}))]
    (inv/invoke! eng :seat (:id seat) :offer_key {:key a-key}
                 {:principal owner})
    seat))

(defn- sit!
  "A bound session, and what waymark_sit answered."
  [h]
  (let [sid (initialize! h)]
    {:sid sid
     :sat (doc-of (tool h (with-session sid) "waymark_sit" {:key a-key}))}))

(defn- create-as! [h sid as-id title]
  (tool h (with-session sid) "waymark_invoke"
        {:kind "request" :action "create" :as as-id
         :input {:title title :customer "Lee Park"}
         :return "summary"}))

(defn- moves [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind kind :resource-id (str id)} {}))))

;; the seed's cast member who is neither the owner nor a manager
(def ^:private cast-member "crew")

;; ── 1 · on a clone, the log records both hands ──────────────────────

(deftest on-a-clone-a-seated-world-seat-creates-as-a-cast-member
  (let [eng (fresh-engine true)
        h (engine/handler eng)
        _ (open-seat! eng true)
        {:keys [sid sat]} (sit! h)
        r (create-as! h sid cast-member "Mow the back lawn")
        made (doc-of r)
        log (moves eng :request (:id made))]
    (is (true? (get-in (dev/row eng :member cast-member) [:data :cast]))
        "the seed loader marked the cast member")
    (is (false? (:isError r)) (text-of r))
    (is (= "new" (str (:state made))))
    (testing "the request's log: actor the cast member, acted_by the seat"
      (is (= cast-member (get-in (first log) [:actor :id])))
      (is (some? (:sitter sat)) (pr-str sat))
      (is (= (str (:sitter sat)) (get-in (first log) [:actor :acted_by]))))))

;; ── 2 · off a clone, the same call is a 403 ─────────────────────────

(deftest off-a-clone-the-same-call-is-refused-403
  (let [eng (fresh-engine false)
        h (engine/handler eng)
        _ (open-seat! eng false)
        {:keys [sid]} (sit! h)
        before (count (dev/rows eng :request))
        r (create-as! h sid cast-member "Mow the back lawn")]
    (is (true? (:isError r)) (text-of r))
    (is (= 403 (:status (doc-of r))) (text-of r))
    (is (= before (count (dev/rows eng :request))) "no row was written")))
