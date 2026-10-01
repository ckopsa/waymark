(ns waymark10.mcp-apps-count-test
  "MCP Apps child 6 (docs/spec-mcp-apps.md, The count): a person's tap
  in Claude is stamped on the held call and counted off the transition
  log. The real handler on memory storage, as mcp_apps_test builds it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private person (t/principal {:id "colton" :display "Colton Kopsa"}))

(def ^:private colton
  {"x-waymark-principal" "connector:colton"
   "x-waymark-actor-type" "agent"
   "x-waymark-acts-for" "colton"})

(def ^:private colton-at-http
  {"x-waymark-principal" "colton"
   "x-waymark-actor-type" "human"})

(defn- world []
  (let [clock (atom (Instant/parse "2026-10-01T09:00:00Z"))
        eng (engine/engine
             {:storage (memory/storage)
              :resources [fx/meal]
              :now-fn (fn [] @clock)
              :oidc {:issuer "https://idp.test/realms/home"
                     :audience "apps-test"
                     :jwks-uri "https://idp.test/certs"
                     :delegate-clients {"connector" "Claude"}
                     :app-clients ["connector"]
                     :app-ticket-secret "a-ticket-secret-for-the-test"}})]
    {:eng eng :h (engine/handler eng)}))

(defn- rpc [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json (cond-> {:jsonrpc "2.0" :id 1 :method method}
                               params (assoc :params params)))}))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- text-of [result] (str (get-in result [:content 0 :text])))

(defn- tool [h headers tool-name args]
  (:result (json (rpc h headers "tools/call" {:name tool-name :arguments args}))))

(defn- initialize! [h bearer]
  (let [resp (rpc h bearer "initialize"
                  {:protocolVersion mcp/protocol-version
                   :capabilities {:extensions {"io.modelcontextprotocol/ui"
                                               {:mimeTypes [mcp/app-mime]}}}
                   :clientInfo {:name "claude-ai" :version "1"}})]
    (assoc bearer "mcp-session-id" (get-in resp [:headers "Mcp-Session-Id"]))))

(def ^:private a-key "bWNwLWFwcHMtY291bnQtc2VhdC1rZXktMDAwMQ")

(defn- seat-sat!
  "A seat, sat by a session of colton's connector that declared the extension."
  [{:keys [eng h]}]
  (let [model (:row (inv/create! eng :model
                                 {:name "apps-count-model" :display "Apps 6"
                                  :vendor "anthropic" :tier "strong"
                                  :price_input_per_mtok 3M :price_output_per_mtok 15M
                                  :price_cache_read_per_mtok 0.3M
                                  :price_cache_write_per_mtok 3.75M}
                                 {:principal person}))
        seat (:row (inv/create! eng :seat
                                {:name "apps-counter"
                                 :charter "Decide whether a meal belongs on the list."
                                 :scope [{:kind "meal" :actions ["accept"]}]
                                 :held_for [(:id model)]
                                 :standing_ttl_seconds 604800 :cadence_seconds 3600
                                 :budget_usd_per_week 5M
                                 :sitting_budget_tokens 1000000}
                                {:principal person}))
        _ (inv/invoke! eng :seat (:id seat) :offer_key {:key a-key} {:principal person})
        as (initialize! h colton)
        sat (tool h as "waymark_sit" {:key a-key})]
    (assert (false? (:isError sat)) (text-of sat))
    {:as as :sitter (seats/sitter-id seat)}))

(defn- hold!
  "A door call `caller` made, held for colton's tap. → the row's id."
  [eng caller]
  (str (:id (held/hold-door! eng {:kind "meal" :action "create"
                                  :body {:name "Ramen" :themes []}
                                  :caller caller :owner "colton"
                                  :why "The seat wants a meal on the list."}))))

(defn- answered-via [eng id]
  (get-in (store/with-tx (:storage eng)
            (fn [tx] (store/load-row (:storage eng) tx :held_call id {})))
          [:data :answered_via]))

(defn- act! [h as id action]
  (let [ticket (get-in (tool h as "waymark_app_read" {:kind "held_call" :id id})
                       [:structuredContent :ticket])
        args {:kind "held_call" :id id :action action :ticket ticket}]
    ;; the second call is the replay: the same ticket, the same nonce
    [(tool h as "waymark_app_act" args) (tool h as "waymark_app_act" args)]))

(defn- answered-by-filter
  "The held_call collection as colton reads it over HTTP, every state,
  narrowed to one `answered_via`. → the answer, printed."
  [h via]
  (let [resp (h {:request-method :get :uri "/api/held_calls"
                 :query-string (str "answered_via=" via "&state=")
                 :headers colton-at-http})]
    (is (= 200 (:status resp)) via)
    (pr-str (json resp))))

(deftest the-prefixes-are-spelled-the-same-in-both-places
  (is (= mcp/app-origin-prefix held/app-key-prefix))
  (is (= mcp/origin-prefix held/mcp-key-prefix))
  (is (= "mcp" (held/answered-via (mcp/origin-key "connector:colton" "9f3c1a"))))
  (is (= "other" (held/answered-via nil)))
  (is (= "other" (held/answered-via "a-clients-own-key"))))

(deftest a-tap-in-claude-is-stamped-and-counted
  (let [{:keys [eng h] :as w} (world)
        {:keys [as sitter]} (seat-sat! w)
        tapped (hold! eng sitter)
        typed (hold! eng sitter)
        before (mcp/taps-from-app eng)
        [first-act replay] (act! h as tapped "allow")
        refused (h {:request-method :post
                    :uri (str "/api/held_calls/" typed "/-/refuse")
                    :headers colton-at-http
                    :body (wire/write-json {:reason "Not today."})})
        counted (mcp/taps-from-app eng)]
    (is (= 0 (:total before)))
    (testing "an allow through waymark_app_act is stamped, and counted once"
      (is (false? (:isError first-act)) (text-of first-act))
      (is (false? (:isError replay)) (text-of replay))
      (is (= "mcp-app" (answered-via eng tapped)))
      (is (= 1 (:total counted)))
      (is (= {"connector:colton" 1} (:by-principal counted)))
      (is (= {"held_call" 1} (:by-kind counted)))
      (is (= {"held_call.allow" 1} (:by-action counted)))
      (is (pos? (:scanned counted)))
      (is (false? (:reached-cap counted))))
    (testing "a refuse through the HTTP door is stamped other, and not counted"
      (is (= 200 (:status refused)) (str (:body refused)))
      (is (= "other" (answered-via eng typed))))
    (testing "actions-from-mcp still does not count the person's tap"
      (is (nil? (get (:by-kind (mcp/actions-from-mcp eng)) "held_call"))))
    (testing "the held_call collection filters on answered_via"
      (let [in-claude (answered-by-filter h "mcp-app")
            elsewhere (answered-by-filter h "other")]
        (is (str/includes? in-claude tapped))
        (is (not (str/includes? in-claude typed)))
        (is (str/includes? elsewhere typed))
        (is (not (str/includes? elsewhere tapped)))))))
