(ns waymark10.mcp-refusals-test
  "The MCP door counts refusals (docs/spec-seat.md R-10.6, bead
  waymark-fp62.7 item 2).

  R-10.6 says the engine adds one to a sitting's `refusals` on each
  409 it serves. The HTTP handler did that, because
  `router/wrap-refusals-counted` was mounted in it. The MCP door built
  its own handler out of `assemble-routes` and wore `wrap-problems`
  alone, so a sitter's 409 at that door was lost — and a sitter is the
  one caller the counter exists for. A firing's 409 counts nothing,
  R-11.5's backlog reads empty, and the seat looks quieter than it is.

  What this suite proves:

  - one 409 at the MCP door adds one refusal to the open sitting, and
    a second adds a second;
  - a committed transition at that same door still adds one
    transition and no refusal, so the two counters stay apart;
  - a 404 at that door counts nothing: the rule is 409s, not
    refusals in general;
  - the refusal lands on the sitting of the grant that drew it, and
    on no other seat's sitting.

  NOT proved here, and recorded: a bulk or batch per-item refusal in
  a 200 report. The report keeps the refusal's sentence and drops its
  status, so a per-item 409 cannot be told from a per-item 404 or 422
  at the boundary. An atomic bulk throws one 409 and is counted like
  any other.

  Memory storage, a locally-minted RSA keypair as the IdP's signing
  key, the real handler: no database, no network. The shape is
  interactive_sitting_test's, whose helpers this file re-spells for
  the same reason that file re-spelled sitting_close_test's — they are
  private there by design."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.test :refer [deftest is testing]]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)
           (java.time Instant)))

;; ── the connector door, as interactive_sitting_test stands it up ────

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "counted-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "counted-key" :alg "RS256" :use "sig")]})

(defn- mint [claims]
  (jwt/sign (merge {:iss issuer :aud audience
                    :exp (+ (quot (System/currentTimeMillis) 1000) 600)}
                   claims)
            (.getPrivate keypair)
            {:alg :rs256 :header {:kid "counted-key"}}))

(def ^:private colton {:sub "colton" :azp "connector" :name "Colton Kopsa"})

(defn- bearer [claims] {"authorization" (str "Bearer " (mint claims))})

(def ^:private person (t/principal {:id "colton" :display "Colton Kopsa"}))

(defn- fresh-engine []
  (let [at (atom (Instant/parse "2026-09-17T09:00:00Z"))]
    (engine/engine {:storage (memory/storage)
                    :resources [fx/meal]
                    :now-fn (fn [] @at)
                    :oidc {:issuer issuer :audience audience :jwks jwks
                           :app-url "https://app.test/"
                           :delegate-clients {"connector" "Claude"}}})))

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
  (get-in (rpc h (bearer colton) "initialize"
               {:protocolVersion mcp/protocol-version
                :capabilities {} :clientInfo {:name "counted" :version "0"}})
          [:headers "Mcp-Session-Id"]))

(defn- with-session [sid] (assoc (bearer colton) "mcp-session-id" sid))

;; ── the office ──────────────────────────────────────────────────────

(def ^:private a-key
  "128 bits of base64url — what a machine mints and no hand types."
  "bWNwLXJlZnVzYWxzLWNvdW50ZWQta2V5LTAx")

(def ^:private other-key
  "A second seat's key, for the deftest that opens two."
  "bWNwLXJlZnVzYWxzLWNvdW50ZWQta2V5LTAy")

(defn- add-model! [eng]
  (:row (inv/create! eng :model
                     {:name "counted-test-model" :display "Counted 1"
                      :vendor "anthropic" :tier "strong"
                      :price_input_per_mtok 3M
                      :price_output_per_mtok 15M
                      :price_cache_read_per_mtok 0.3M
                      :price_cache_write_per_mtok 3.75M}
                     {:principal person})))

(defn- open-seat!
  "A seat with its key offered. No schedule is minted: this suite is
  about what a bound session's calls cost, not about a firing. One key
  per seat, because `seat-by-key` answers the seat that holds the key
  and two seats on one key would be a test asking it to guess."
  ([eng model] (open-seat! eng model "counted-one" a-key))
  ([eng model named key']
   (let [seat (:row (inv/create! eng :seat
                                 {:name named
                                  :charter "Decide whether a meal belongs on the list."
                                  :scope [{:kind "meal" :actions ["accept" "decline"]}]
                                  :held_for [(:id model)]
                                  :standing_ttl_seconds 604800
                                  :cadence_seconds 3600
                                  :budget_usd_per_week 5M
                                  :sitting_budget_tokens 60000}
                                 {:principal person}))]
     (inv/invoke! eng :seat (:id seat) :offer_key {:key key'}
                  {:principal person})
     seat)))

(defn- meal!
  "One fresh row for the sitter to act on."
  [eng name']
  (:row (inv/create! eng :meal {:name name' :themes []}
                     {:principal person})))

(defn- row-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))

(defn- counts-of
  "The open sitting's two counters, as numbers rather than nils — a
  sitting that has counted nothing writes no field at all."
  [eng sitting-id]
  (let [d (:data (row-of eng :sitting sitting-id))]
    {:transitions (long (or (:transitions d) 0))
     :refusals (long (or (:refusals d) 0))}))

(defn- accept!
  "The sitter's own invoke through the MCP door."
  [h sid meal-id]
  (tool h (with-session sid) "waymark_invoke"
        {:kind "meal" :id (str meal-id) :action "accept"}))

(defn- decline!
  "The wrong-state door: `decline` leaves `suggested` only, so on a
  meal already accepted it is the engine's own 409. A SECOND `accept`
  would not be: `accept` is idempotent, and the same action with the
  same input on a row at its outcome is a natural replay, answered
  200 and counted nothing (invoke.clj step 8). The seat's scope names
  `decline` too: a door the grant does not admit is CONCEALED, a 404
  that counts nothing, and a 409 needs a door the sitter may see."
  [h sid meal-id]
  (tool h (with-session sid) "waymark_invoke"
        {:kind "meal" :id (str meal-id) :action "decline"}))

(defn- sit!
  "A bound session, and the sitting it opened."
  ([eng h] (sit! eng h a-key))
  ([_eng h key']
   (let [sid (initialize! h)
         answer (doc-of (tool h (with-session sid) "waymark_sit" {:key key'}))]
     {:sid sid
      :grant (str (:grant answer))
      :sitting (str (:sitting answer))})))

;; ── 1 · a 409 at the MCP door counts one refusal ────────────────────

(deftest a-409-at-the-mcp-door-counts-one-refusal-on-the-open-sitting
  (let [eng (fresh-engine)
        h (engine/handler eng)
        model (add-model! eng)
        _ (open-seat! eng model)
        {:keys [sid sitting grant]} (sit! eng h)
        dinner (:id (meal! eng "Soup"))]

    (testing "the sit opened the sitting the counter looks for"
      (is (string? sitting))
      (is (= sitting (str (:id (seats/open-sitting-for-grant eng grant))))))

    (testing "the first accept lands, and counts a transition, not a refusal"
      (let [r (accept! h sid dinner)]
        (is (false? (:isError r)) (text-of r)))
      (is (= {:transitions 1 :refusals 0} (counts-of eng sitting))))

    (testing "a decline on the accepted meal is a 409, and the sitting counts it"
      (let [r (decline! h sid dinner)
            doc (doc-of r)]
        (is (true? (:isError r)))
        (is (= 409 (:status doc))
            "the engine's own wrong-state refusal, byte for byte")
        (is (= "Wrong state" (str (:title doc)))))
      (is (= {:transitions 1 :refusals 1} (counts-of eng sitting))
          "R-10.6 at the MCP door: one 409, one refusal, and exactly one"))

    (testing "a second 409 counts a second refusal"
      (is (true? (:isError (decline! h sid dinner))))
      (is (= {:transitions 1 :refusals 2} (counts-of eng sitting))))))

;; ── 2 · only 409s, and only the grant's own sitting ────────────────

(deftest the-counter-reads-the-status-and-the-grant-and-nothing-else
  (let [eng (fresh-engine)
        h (engine/handler eng)
        model (add-model! eng)
        _ (open-seat! eng model)
        _ (open-seat! eng model "counted-two" other-key)
        one (sit! eng h a-key)
        two (sit! eng h other-key)
        dinner (:id (meal! eng "Stew"))]

    (testing "two seats, two grants, two sittings"
      (is (not= (:grant one) (:grant two)))
      (is (not= (:sitting one) (:sitting two))))

    (testing "a 404 at the same door counts nothing: the rule is 409s"
      (let [r (accept! h (:sid one) "meal-nobody-minted")]
        (is (true? (:isError r)))
        (is (= 404 (:status (doc-of r)))))
      (is (= {:transitions 0 :refusals 0} (counts-of eng (:sitting one)))))

    (testing "the first seat works, then meets the wrong state"
      (is (false? (:isError (accept! h (:sid one) dinner))))
      (let [r (decline! h (:sid one) dinner)]
        (is (true? (:isError r)))
        (is (= 409 (:status (doc-of r))))))

    (testing "and the refusal lands on the grant that drew it, alone"
      (is (= {:transitions 1 :refusals 1} (counts-of eng (:sitting one))))
      (is (= {:transitions 0 :refusals 0} (counts-of eng (:sitting two)))
          "one lookup by grant, so a seat is never billed for another's law"))))
