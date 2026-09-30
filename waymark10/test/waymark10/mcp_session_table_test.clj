(ns waymark10.mcp-session-table-test
  "An MCP session survives a deploy. The deploy is start-before-stop,
  so for a while two allocations serve one database and the traffic
  moves from the old to the new. TWO engine instances over one
  database — separate pools, separate :mcp-sessions atoms, one JVM —
  are that moment: a session initialized and sat on A is answered on B
  as the same bound sitter, with no 404. An unknown id still answers
  404, an expired one is evicted, and the table holds a hash of the
  id, never the id.

  The connector door as mcp_sit_test stands it up: a locally-minted
  RSA keypair as the IdP's signing key and the real handler, over
  Postgres. Needs the waymark10_test database; WAYMARK10_TEST_DSN
  overrides."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.mcp-sessions :as sessions]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)
           (java.time Instant)))

;; ── the connector door ───────────────────────────────────────────────

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "deploy-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "deploy-key" :alg "RS256" :use "sig")]})

(defn- bearer [claims]
  {"authorization"
   (str "Bearer "
        (jwt/sign (merge {:iss issuer :aud audience
                          :exp (+ (quot (System/currentTimeMillis) 1000) 600)}
                         claims)
                  (.getPrivate keypair)
                  {:alg :rs256 :header {:kid "deploy-key"}}))})

(def ^:private person (t/principal {:id "colton" :display "Colton Kopsa"}))
(def ^:private colton {:sub "colton" :azp "connector" :name "Colton Kopsa"})

(defn- rpc [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json (cond-> {:jsonrpc "2.0" :id 1 :method method}
                               params (assoc :params params)))}))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- tool [h headers tool-name args]
  (let [resp (rpc h headers "tools/call" {:name tool-name :arguments args})]
    (assoc (get-in (json resp) [:result]) :status (:status resp))))

(defn- text-of [result] (str (get-in result [:content 0 :text])))
(defn- doc-of [result] (wire/read-json (text-of result)))

(defn- initialize! [h]
  (get-in (rpc h (bearer colton) "initialize"
               {:protocolVersion mcp/protocol-version
                :capabilities {} :clientInfo {:name "routine" :version "0"}})
          [:headers "Mcp-Session-Id"]))

(defn- with-session [sid] (assoc (bearer colton) "mcp-session-id" sid))

;; ── two allocations, one database ───────────────────────────────────

(defn- engine-over [st]
  (engine/engine {:storage st
                  :resources [fx/meal]
                  :oidc {:issuer issuer :audience audience :jwks jwks
                         :app-url "https://app.test/"
                         :delegate-clients {"connector" "Claude"}}}))

(defn- with-two-engines
  "Drop what a run before this one left, then boot A and B over the
  same database and call (f eng-a eng-b)."
  [f]
  (let [boot (pg/storage db/dsn)]
    (try
      (store/with-tx boot
        (fn [tx]
          (doseq [table (concat (map #(store/definition-checked-name (:plural %))
                                     (vals (:kinds (engine/full-registry [fx/meal]))))
                                ["waymark10_transitions" "waymark10_idempotency"
                                 "waymark10_cursors" "waymark10_drafts"
                                 "waymark10_mcp_sessions"])]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! boot))))
  (let [st-a (pg/storage db/dsn)
        st-b (pg/storage db/dsn)]
    (try
      (let [eng-a (engine-over st-a)
            eng-b (engine-over st-b)]
        (f eng-a eng-b))
      (finally
        (pg/close! st-a)
        (pg/close! st-b)))))

(def ^:private a-key
  "c2VhdC1rZXktZm9yLXRoZS1kZXBsb3k")

(defn- open-seat!
  "mcp_sit_test's seat over the meal kind, its schedule minted and its
  key offered. `extra` merges into the seat's fields."
  [eng & [extra]]
  (let [model (:row (inv/create! eng :model
                                 {:name "claude-deploy-5" :display "Deploy 5"
                                  :vendor "anthropic" :tier "strong"
                                  :price_input_per_mtok 3M
                                  :price_output_per_mtok 15M
                                  :price_cache_read_per_mtok 0.3M
                                  :price_cache_write_per_mtok 3.75M}
                                 {:principal person}))
        seat (:row (inv/create!
                    eng :seat
                    (merge {:name "deploy-clerk"
                            :charter "Decide whether a meal belongs on the list."
                            :scope [{:kind "meal" :actions ["accept"]}]
                            :held_for [(:id model)]
                            :standing_ttl_seconds 604800
                            :cadence_seconds 3600
                            :budget_usd_per_week 5M
                            :sitting_budget_tokens 60000}
                           extra)
                    {:principal person}))]
    (schedules/ensure-schedule! eng seat)
    (inv/invoke! eng :seat (:id seat) :offer_key {:key a-key}
                 {:principal person})
    seat))

(defn- session-rows [eng]
  (store/with-tx (:storage eng)
    (fn [tx]
      (jdbc/execute! tx ["SELECT * FROM waymark10_mcp_sessions"]
                     {:builder-fn rs/as-unqualified-lower-maps}))))

;; ── the story ───────────────────────────────────────────────────────

(deftest a-session-sat-on-the-old-engine-is-the-same-sitter-on-the-new
  (with-two-engines
    (fn [eng-a eng-b]
      (let [h-a (engine/handler eng-a)
            h-b (engine/handler eng-b)
            seat (open-seat! eng-a)
            sitter-id (seats/sitter-id seat)
            sid (initialize! h-a)
            sat (tool h-a (with-session sid) "waymark_sit" {:key a-key})]

        (testing "the sit lands on A"
          (is (string? sid))
          (is (false? (:isError sat)) (text-of sat))
          (is (= "deploy-clerk" (:seat (doc-of sat)))))

        (testing "B knows the session A minted, and it is the same sitter"
          (let [r (tool h-b (with-session sid) "waymark_discover" {})
                doc (doc-of r)]
            (is (= 200 (:status r)) "no 404: the id is known on B")
            (is (= sitter-id (get-in doc [:principal :id])))
            (is (= "deploy-clerk" (get-in doc [:doors :ask :seat :name])))))

        (testing "the binding reads back whole on B"
          (let [bound (:bound (mcp/touch-session! eng-b sid))]
            (is (= (str (:sitting (doc-of sat))) (str (:sitting bound))))
            (is (= (str (:id seat)) (str (:seat bound))))
            (is (= sitter-id (get-in bound [:sitter :id])))
            (is (= :agent (get-in bound [:sitter :type])))
            (is (instance? Instant (:bound-at bound)))))

        (testing "the table holds a hash of the id, never the id"
          (let [rows (session-rows eng-b)]
            (is (= [(sessions/id-hash sid)]
                   (mapv :id_hash (filter #(= (sessions/id-hash sid) (:id_hash %))
                                          rows))))
            (is (not= sid (sessions/id-hash sid)))
            (is (not (str/includes? (pr-str rows) sid)))))))))

(deftest an-unknown-id-still-answers-404-and-an-expired-one-is-evicted
  (with-two-engines
    (fn [eng-a eng-b]
      (let [h-b (engine/handler eng-b)]

        (testing "an id neither engine minted answers 404 on B"
          (let [resp (rpc h-b (with-session "not-a-session-this-house-knows")
                          "tools/list" nil)]
            (is (= 404 (:status resp)))
            (is (str/includes? (str (:detail (json resp))) "initialize again"))))

        (testing "a session untouched past the TTL is gone, and its row with it"
          (let [sid (mcp/open-session! eng-a)
                later (.plusSeconds ^Instant ((:now-fn eng-b))
                                    (+ mcp/session-ttl-seconds 60))
                eng-b-later (assoc eng-b :now-fn (constantly later))]
            (is (some? (mcp/touch-session! eng-b sid)) "alive on B now")
            (is (nil? (mcp/touch-session! eng-b-later sid))
                "expired eight hours on")
            (is (not-any? #(= (sessions/id-hash sid) (:id_hash %))
                          (session-rows eng-b))
                "and evicted, not merely refused")
            (is (= 404 (:status (rpc (engine/handler eng-b-later)
                                     (with-session sid) "tools/list" nil))))))))))

(deftest a-session-less-re-sit-claims-its-sitting-over-the-table
  ;; Ticket 7496403e over Postgres: mcp_sit_test covers the claim on the
  ;; in-memory twin; here `live-hashes` reads the session table, and two
  ;; concurrent sits — one on each engine, each on its own pool —
  ;; serialise on the sitting's FOR UPDATE lock.
  (with-two-engines
    (fn [eng-a eng-b]
      (let [h-a (engine/handler eng-a)
            h-b (engine/handler eng-b)
            _ (open-seat! eng-a {:max_open_sittings 3})
            sit! (fn [h sid]
                   (let [r (tool h (with-session sid) "waymark_sit" {:key a-key})]
                     (is (false? (:isError r)) (text-of r))
                     (str (:sitting (doc-of r)))))
            unheard! (fn [sid]
                       (store/with-tx (:storage eng-a)
                         (fn [tx]
                           (jdbc/execute!
                            tx ["UPDATE waymark10_mcp_sessions SET touched = ?
                                 WHERE id_hash = ?"
                                (java.sql.Timestamp/from
                                 (.minusSeconds (Instant/now) 7200))
                                (sessions/id-hash sid)]))))
            dropped (initialize! h-a)
            a (sit! h-a dropped)]
        (testing "while the first session is heard from, a new one gets a fresh sitting"
          (is (not= a (sit! h-a (initialize! h-a)))))
        (unheard! dropped)
        (testing "once the dropped session is unheard past the idle limit, the re-sit gets the same sitting"
          (let [s2 (initialize! h-b)]
            (is (= a (sit! h-b s2)))
            (unheard! s2)))
        (testing "two concurrent session-less sits get that sitting once and one fresh sitting"
          (let [s3 (initialize! h-a)
                s4 (initialize! h-b)
                f3 (future (sit! h-a s3))
                f4 (future (sit! h-b s4))
                got [@f3 @f4]]
            (is (not= (first got) (second got)))
            (is (= 1 (count (filter #{a} got))))))))))
