(ns waymark10.mcp-sit-test
  "The keyed sitter session (docs/spec-seat.md § 12, R-12.12 to
  R-12.16): a person pastes a seat key into a Routine's instructions,
  the Routine's session presents it once, and THAT session — one of
  many wearing the same connector bearer — becomes the seat's sitter.

  The problem the feature answers, stated once: a person signed in
  through the claude.ai connector resolves to ONE delegate on ONE
  bearer, so a Routine's firing and an afternoon of chat are the same
  principal and cannot be told apart by credential. The key is what
  tells them apart, and the MCP session id is what it binds to.

  Memory storage, a locally-minted RSA keypair as the IdP's signing
  key, the real handler: no database, no network. The shape is
  connector_door_test's, whose helpers this file re-spells rather than
  reaches for — they are private there, and four small fns are cheaper
  than a seam nobody else wants."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)))

;; ── the connector door, as connector_door_test stands it up ─────────

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "sit-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "sit-key" :alg "RS256" :use "sig")]})

(defn- mint [claims]
  (jwt/sign (merge {:iss issuer :aud audience
                    :exp (+ (quot (System/currentTimeMillis) 1000) 600)}
                   claims)
            (.getPrivate keypair)
            {:alg :rs256 :header {:kid "sit-key"}}))

(defn- bearer [claims]
  {"authorization" (str "Bearer " (mint claims))})

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [fx/meal]
                  :oidc {:issuer issuer :audience audience :jwks jwks
                         :app-url "https://app.test/"
                         :delegate-clients {"connector" "Claude"}}}))

(defn- json [resp] (some-> (:body resp) wire/read-json))

;; the person at the keyboard, and the same person signing in THROUGH
;; the connector — the delegate every session of that tool resolves to
(def ^:private person (t/principal {:id "colton" :display "Colton Kopsa"}))
(def ^:private colton {:sub "colton" :azp "connector" :name "Colton Kopsa"})
(def ^:private delegate-id "connector:colton")

(defn- rpc
  "One JSON-RPC message at /api/-/mcp through the real handler."
  [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json (cond-> {:jsonrpc "2.0" :id 1 :method method}
                               params (assoc :params params)))}))

(defn- tool
  "One tools/call, answering the parsed tool result."
  [h headers tool-name args]
  (let [resp (rpc h headers "tools/call" {:name tool-name :arguments args})]
    (assoc (get-in (json resp) [:result]) :status (:status resp))))

(defn- text-of [result]
  (str (get-in result [:content 0 :text])))

(defn- doc-of [result]
  (wire/read-json (text-of result)))

;; ── the office the key opens ────────────────────────────────────────

(def ^:private a-key
  "128 bits of base64url — what a machine mints and no hand types."
  "c2VhdC1rZXktZm9yLXRoZS1yb3V0aW5l")

(defn- open-seat!
  "A person's seat over the fixture's meal kind, its schedule minted
  and its key offered — everything a Routine's instructions would
  already have."
  [eng]
  (let [model (:row (inv/create! eng :model
                                 {:name "claude-sit-5" :display "Sit 5"
                                  :vendor "anthropic" :tier "strong"
                                  :price_input_per_mtok 3M
                                  :price_output_per_mtok 15M
                                  :price_cache_read_per_mtok 0.3M
                                  :price_cache_write_per_mtok 3.75M}
                                 {:principal person}))
        seat (:row (inv/create!
                    eng :seat
                    {:name "meal-clerk"
                     :charter "Decide whether a meal belongs on the list."
                     :scope [{:kind "meal" :actions ["accept"]}]
                     :held_for [(:id model)]
                     :standing_ttl_seconds 604800
                     :cadence_seconds 3600
                     :budget_usd_per_week 5M
                     :sitting_budget_tokens 60000}
                    {:principal person}))]
    ;; the schedule is the engine's own row and the consumer mints it
    ;; on a started engine; a bare handler stands it up by hand, which
    ;; is the same call the consumer makes
    (schedules/ensure-schedule! eng seat)
    (inv/invoke! eng :seat (:id seat) :offer_key {:key a-key}
                 {:principal person})
    {:seat seat :model model}))

(defn- initialize!
  "The handshake, answering [session-id response]."
  [h]
  (let [resp (rpc h (bearer colton) "initialize"
                  {:protocolVersion mcp/protocol-version
                   :capabilities {} :clientInfo {:name "routine" :version "0"}})]
    [(get-in resp [:headers "Mcp-Session-Id"]) resp]))

(defn- with-session [sid] (assoc (bearer colton) "mcp-session-id" sid))

;; ── 1. the transport keeps a session ────────────────────────────────

(deftest initialize-answers-a-session-id-and-a-stateless-client-is-unchanged
  (let [h (engine/handler (fresh-engine))
        [sid resp] (initialize! h)]
    (is (= 200 (:status resp)))
    (is (string? sid))
    (is (<= 22 (count sid)) "128 bits, base64url, unpadded")
    (is (not (str/includes? sid "=")) "no padding to escape in a header")
    (testing "a second initialize is a second session"
      (is (not= sid (first (initialize! h)))))
    (testing "a client that sends no header is served exactly as before"
      (let [r (rpc h (bearer colton) "tools/list" nil)]
        (is (= 200 (:status r)))
        (is (seq (get-in (json r) [:result :tools])))))
    (testing "and a known id on a later message is fine"
      (is (= 200 (:status (rpc h (with-session sid) "tools/list" nil)))))))

(deftest an-unknown-session-id-answers-404
  (let [h (engine/handler (fresh-engine))
        resp (rpc h (with-session "not-a-session-this-engine-knows")
                  "tools/list" nil)]
    (is (= 404 (:status resp)))
    (is (str/includes? (str (:detail (json resp))) "initialize again")
        "MCP's own remedy for an unknown session")
    (testing "but an initialize carrying a stale id is answered, not refused —
              it IS how a client starts over"
      (let [r (rpc h (with-session "not-a-session-this-engine-knows")
                   "initialize" {:protocolVersion mcp/protocol-version})]
        (is (= 200 (:status r)))
        (is (string? (get-in r [:headers "Mcp-Session-Id"])))))))

;; ── 2. the tool is on the list, and says what it does ───────────────

(deftest waymark-sit-is-a-fixed-tool-and-the-instructions-name-it
  (let [h (engine/handler (fresh-engine))
        names (mapv :name (get-in (json (rpc h (bearer colton) "tools/list" nil))
                                  [:result :tools]))]
    (is (some #{"waymark_sit"} names))
    (is (= (count mcp/tools) (count names)))
    (testing "the connect-time instructions tell a firing to sit first"
      (is (str/includes? mcp/instructions "waymark_sit"))
      (is (str/includes? mcp/instructions "IF YOU WERE HANDED A SEAT KEY")))
    (testing "and the tool's own sentence says what binding costs and spares"
      (is (str/includes? mcp/sit-description "Call it once, first."))
      (is (str/includes? mcp/sit-description
                         "Your person's other sessions are untouched.")))))

;; ── 3. the refusals, each one sentence ──────────────────────────────

(deftest a-key-binds-nothing-without-a-session-a-person-or-a-seat
  (let [eng (fresh-engine)
        h (engine/handler eng)
        _ (open-seat! eng)
        [sid _] (initialize! h)]
    (testing "a client that keeps no session is told why a key cannot bind it"
      (let [r (tool h (bearer colton) "waymark_sit" {:key a-key})]
        (is (true? (:isError r)))
        (is (str/includes? (text-of r) "This client keeps no MCP session"))
        (is (str/includes? (text-of r) "Mcp-Session-Id"))))

    (testing "a bare agent — nobody's tool — is refused, session or no session"
      ;; the dev headers are a bare handler's own credential: an agent
      ;; holding its own key, which is exactly NOT a delegate
      (let [agent-headers {"x-waymark-principal" "lone-agent"
                           "x-waymark-actor-type" "agent"
                           "mcp-session-id" sid}
            r (tool h agent-headers "waymark_sit" {:key a-key})]
        (is (true? (:isError r)))
        (is (str/includes? (text-of r) "A seat key binds a person's tool"))))

    (testing "a key no seat answers to is one uniform sentence"
      (let [r (tool h (with-session sid) "waymark_sit"
                    {:key "c2VhdC1rZXktbm9ib2R5LWhvbGRz"})]
        (is (true? (:isError r)))
        (is (= "No seat answers this key." (text-of r))
            "uniform: it does not say whether the key is malformed, stale
             or simply somebody else's")))))

;; ── 4. the bound session IS the sitter ──────────────────────────────

(deftest a-key-binds-this-session-and-leaves-the-persons-others-alone
  (let [eng (fresh-engine)
        h (engine/handler eng)
        {:keys [seat model]} (open-seat! eng)
        sitter-id (seats/sitter-id seat)
        [sid _] (initialize! h)
        sat (tool h (with-session sid) "waymark_sit" {:key a-key})
        answer (doc-of sat)]

    (testing "the sit answers the office, the sitter, the model and the leash"
      (is (false? (:isError sat)) (text-of sat))
      (is (= "meal-clerk" (:seat answer)))
      (is (= sitter-id (:sitter answer)))
      (is (= "claude-sit-5" (:model answer))
          "the seat's schedule names the model, and the claim is it")
      (is (string? (:grant answer)))
      (is (str/includes? (str (:note answer)) "waymark_get")))

    (testing "the sitter row was minted once, acting for the person"
      (let [row (store/with-tx (:storage eng)
                  (fn [tx] (store/load-row (:storage eng) tx :member
                                           sitter-id {})))]
        (is (some? row))
        (is (= "agent" (get-in row [:data :actor_type])))
        (is (= "colton" (get-in row [:data :acts_for])))
        (is (= "meal-clerk (seat)" (get-in row [:data :display])))))

    (testing "the minted grant cites the seat and carries no scope of its own"
      (let [g (store/with-tx (:storage eng)
                (fn [tx] (store/load-row (:storage eng) tx :grant
                                         (str (:grant answer)) {})))]
        (is (= sitter-id (get-in g [:data :audience])))
        (is (= (:id seat) (str (get-in g [:data :seat]))))
        (is (empty? (get-in g [:data :scope])))))

    (testing "waymark_discover ON THIS SESSION is the sitter, in the seat"
      (let [doc (doc-of (tool h (with-session sid) "waymark_discover" {}))]
        (is (= sitter-id (get-in doc [:principal :id])))
        (is (= "meal-clerk" (get-in doc [:doors :ask :seat :name])))
        (is (= "active" (get-in doc [:doors :ask :seat :state])))))

    (testing "and WITHOUT the header the same person is still the delegate"
      (let [doc (doc-of (tool h (bearer colton) "waymark_discover" {}))]
        (is (= delegate-id (get-in doc [:principal :id])))
        (is (nil? (get-in doc [:doors :ask :seat]))
            "the delegate sits in no office — its chats are untouched")))

    (testing "a SECOND session of the same bearer is the delegate too"
      (let [[other _] (initialize! h)
            doc (doc-of (tool h (with-session other) "waymark_discover" {}))]
        (is (= delegate-id (get-in doc [:principal :id])))))

    (testing "sitting twice on one session is the same seat, not a second grant"
      (let [again (doc-of (tool h (with-session sid) "waymark_sit" {:key a-key}))]
        (is (= (:grant answer) (:grant again))
            "the standing seat grant is reused, never re-minted")
        (is (= (:sitting answer) (:sitting again))
            "and so is the open sitting")))

    ;; ── the router's own seat machinery, wearing the sitter ─────────
    (let [meal (:row (inv/create! eng :meal {:name "Soup" :themes []}
                                  {:principal person}))
          ;; the sit opened the sitting (R-12.15): nobody else opens one
          ;; for a keyed session, and the answer names it
          sitting (seats/open-sitting-for-grant eng (str (:grant answer)))
          _ (is (some? sitting) "waymark_sit opened the seat's sitting")
          _ (is (= (str (:id sitting)) (str (:sitting answer)))
                "and its answer names the sitting it opened")
          _ (is (= sitter-id (str (get-in sitting [:data :member])))
                "born as the sitter")
          counts (fn []
                   (let [row (store/with-tx (:storage eng)
                               (fn [tx] (store/load-row (:storage eng) tx
                                                        :sitting (:id sitting) {})))]
                     [(get-in row [:data :transitions])
                      (get-in row [:data :refusals])]))]
      (is (= [0 0] (counts)) "a sitting does not count its own birth")

      (testing "an invoke through the BOUND session moves the row as the sitter"
        (let [r (tool h (with-session sid) "waymark_invoke"
                      {:kind "meal" :id (:id meal) :action "accept"})]
          (is (false? (:isError r)) (text-of r))))

      (testing "the transition's actor is the seat, not the person's tool"
        (let [log (store/with-tx (:storage eng)
                    (fn [tx] (store/transitions (:storage eng) tx
                                                {:kind :meal :resource-id (:id meal)}
                                                {:limit 10 :newest-first true})))
              accepted (first (filter #(= :accept (:action %)) log))]
          (is (some? accepted))
          (is (= sitter-id (str (get-in accepted [:actor :id])))
              "the ledger reads one actor per office")))

      (testing "and it counted against the seat's open sitting"
        (is (= [1 0] (counts)))))))
