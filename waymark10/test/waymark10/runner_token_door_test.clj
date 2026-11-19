(ns waymark10.runner-token-door-test
  "A pull run at the MCP door on its runner link's token (ticket
  9ef6e515): a localfire's run on a hosted engine holds no bearer, and
  the pull link's token in Waymark-Runner-Token lets it in as NOBODY.
  Before it sits it may call waymark_sit and nothing else; a wrong
  token is refused as any bad credential is, and a key this engine
  never minted sits nothing. Memory storage and the real handler, as
  mcp_sit_test stands them up; its helpers are re-spelled here, as it
  re-spells connector_door_test's."
  (:require [buddy.core.keys :as bkeys]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [waymark10.fixtures :as fx]
            [waymark10.modules.hooks :as hooks]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.runner-links :as rl]
            [waymark10.server.schedules :as schedules]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.security KeyPairGenerator)))

(defn- factory-hooks [f]
  (let [registry @#'hooks/registry
        before @registry]
    (hooks/register-hook! :factory-changes
                          (fn [eng tx where limit]
                            (store/query-rows (:storage eng) tx :change where
                                              {:limit limit})))
    (hooks/register-hook! :factory-merged-changes
                          (fn [eng tx author limit]
                            (store/query-rows (:storage eng) tx :change
                                              {:state :merged :author author}
                                              {:limit limit :newest-first true})))
    (hooks/register-hook! :factory-tickets
                          (fn [eng tx where limit]
                            (store/query-rows (:storage eng) tx :ticket where
                                              {:limit limit})))
    (hooks/register-hook! :factory-ticket
                          (fn [eng tx id]
                            (store/load-row (:storage eng) tx :ticket (str id) {})))
    (try (f) (finally (reset! registry before)))))

(use-fixtures :each factory-hooks)

(def ^:private keypair
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                      (.initialize 2048))))

(def ^:private issuer "https://idp.test/realms/home")
(def ^:private audience "runner-token-test")

(def ^:private jwks
  {:keys [(assoc (bkeys/public-key->jwk (.getPublic keypair))
                 :kid "runner-key" :alg "RS256" :use "sig")]})

(defn- bearer [claims]
  {"authorization"
   (str "Bearer "
        (jwt/sign (merge {:iss issuer :aud audience
                          :exp (+ (quot (System/currentTimeMillis) 1000) 600)}
                         claims)
                  (.getPrivate keypair)
                  {:alg :rs256 :header {:kid "runner-key"}}))})

(defn- fresh-engine []
  (doto (engine/engine {:storage (memory/storage)
                        :resources [fx/meal]
                        :oidc {:issuer issuer :audience audience :jwks jwks
                               :app-url "https://app.test/"
                               :delegate-clients {"connector" "Claude"}}})
    (rl/ensure-providers!)))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(def ^:private person (t/principal {:id "colton" :display "Colton Kopsa"}))
(def ^:private colton {:sub "colton" :azp "connector" :name "Colton Kopsa"})

(defn- rpc [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json (cond-> {:jsonrpc "2.0" :id 1 :method method}
                               params (assoc :params params)))}))

(defn- tool [h headers tool-name args]
  (let [resp (rpc h headers "tools/call" {:name tool-name :arguments args})]
    (assoc (get-in (json resp) [:result]) :status (:status resp))))

(defn- text-of [result]
  (str (get-in result [:content 0 :text])))

(def ^:private a-key "c2VhdC1rZXktZm9yLXRoZS1yb3V0aW5l")
(def ^:private another-engines-key "b3RoZXItZW5naW5lcy1zZWF0LWtleQ")
(def ^:private a-token "pull-link-token-0123456789abcdef")

(defn- open-seat!
  "A person's seat over the fixture's meal kind, its schedule minted
  and `key` offered, as mcp_sit_test opens one."
  [eng key]
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
    (schedules/ensure-schedule! eng seat)
    (inv/invoke! eng :seat (:id seat) :offer_key {:key key}
                 {:principal person})
    seat))

(defn- pull-link! [eng]
  (:row (inv/create! eng :runner_link
                     {:provider "localfire" :delivery "pull" :fire_token a-token}
                     {:principal person})))

(defn- runner-session!
  "A pull run's handshake on the link's token: its headers, session id
  and all."
  [h]
  (let [runner {"waymark-runner-token" a-token}
        resp (rpc h runner "initialize"
                  {:protocolVersion mcp/protocol-version
                   :capabilities {} :clientInfo {:name "localfire" :version "0"}})]
    (is (= 200 (:status resp)))
    (assoc runner "mcp-session-id" (get-in resp [:headers "Mcp-Session-Id"]))))

(deftest a-runner-token-session-may-only-sit-before-it-sits
  (let [eng (fresh-engine)
        h (engine/handler eng)
        ;; the person signs in once, as the owner of a house has
        _ (rpc h (bearer colton) "tools/list" nil)
        _ (open-seat! eng a-key)
        _ (pull-link! eng)
        hs (runner-session! h)]
    (testing "the tool list is the fixed one"
      (is (some #{"waymark_sit"}
                (map :name (get-in (json (rpc h hs "tools/list" nil)) [:result :tools])))))
    (testing "every tool but waymark_sit answers that it must sit first"
      (doseq [[t args] [["waymark_discover" {}]
                        ["waymark_query" {:kind "meal"}]
                        ["waymark_schema" {:kind "meal"}]
                        ["waymark_powers" {}]
                        ["waymark_held" {}]
                        ["waymark_invoke" {:kind "meal" :action "create" :input {}}]]]
        (let [r (tool h hs t args)]
          (is (true? (:isError r)) t)
          (is (str/includes? (text-of r) "call waymark_sit first") t))))
    (testing "a key this engine never minted sits nothing"
      (is (true? (:isError (tool h hs "waymark_sit"
                                 {:key "bm8tc3VjaC1rZXktYW55d2hlcmU"})))))
    (testing "nor does a key of another engine"
      (open-seat! (fresh-engine) another-engines-key)
      (is (true? (:isError (tool h hs "waymark_sit" {:key another-engines-key})))))
    (testing "and the session is still nobody after a refused sit"
      (is (str/includes? (text-of (tool h hs "waymark_query" {:kind "meal"}))
                         "call waymark_sit first")))
    (testing "the seat's key sits it, and then it is the seat's sitter"
      (let [r (tool h hs "waymark_sit" {:key a-key :session "run-1"})]
        (is (not (:isError r)) (text-of r))
        (is (not (str/includes? (text-of (tool h hs "waymark_query" {:kind "meal"}))
                                "call waymark_sit first")))))))

(deftest a-wrong-token-is-refused-as-any-bad-credential-is
  (let [eng (fresh-engine)
        h (engine/handler eng)
        _ (pull-link! eng)]
    (testing "a token no link holds is 401, and sends nobody to sign in"
      (let [r (rpc h {"waymark-runner-token" "not-this-engines-token"} "tools/list" nil)]
        (is (= 401 (:status r)))
        (is (nil? (get-in r [:headers "WWW-Authenticate"])))))
    (testing "no token at all is the anonymous 401 it always was"
      (is (= 401 (:status (rpc h {} "tools/list" nil)))))))
