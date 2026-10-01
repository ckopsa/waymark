(ns waymark10.mcp-apps-approval-test
  "MCP Apps child 2 (docs/spec-mcp-apps.md): Approve and Deny an
  approval_request inline, as the person. The real handler on memory
  storage, in mcp_apps_test's house: the delegate asks, and the person
  it acts for gives the verdict from the page."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.fixtures :as fx]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private sentence
  "The requester's grant gains exactly the scope shown, immediately.")

(def ^:private requester
  (t/principal {:id "connector:colton" :type :agent :display "Claude"}))

(def ^:private colton
  {"x-waymark-principal" "connector:colton"
   "x-waymark-actor-type" "agent"
   "x-waymark-acts-for" "colton"})

(defn- world []
  (let [eng (engine/engine
             {:storage (memory/storage)
              :resources [fx/meal]
              :now-fn (constantly (Instant/parse "2026-10-01T09:00:00Z"))
              :oidc {:issuer "https://idp.test/realms/home"
                     :audience "apps-test"
                     :jwks-uri "https://idp.test/certs"
                     :delegate-clients {"connector" "Claude"}
                     :app-clients ["connector"]
                     :app-ticket-secret "a-ticket-secret-for-the-test"}})]
    {:eng eng :h (engine/handler eng)}))

(defn- rpc [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json {:jsonrpc "2.0" :id 1 :method method :params params})}))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- text-of [result] (str (get-in result [:content 0 :text])))

(defn- tool [h headers tool-name args]
  (:result (json (rpc h headers "tools/call" {:name tool-name :arguments args}))))

(defn- initialize!
  "A session of colton's connector that declared the extension."
  [h]
  (let [resp (rpc h colton "initialize"
                  {:protocolVersion mcp/protocol-version
                   :capabilities {:extensions {"io.modelcontextprotocol/ui"
                                               {:mimeTypes [mcp/app-mime]}}}
                   :clientInfo {:name "claude-ai" :version "1"}})]
    (assoc colton "mcp-session-id" (get-in resp [:headers "Mcp-Session-Id"]))))

(defn- ask!
  "The delegate's bootstrap ask for one door of `meal`. → the row's id."
  [eng]
  (str (:id (:row (inv/create! eng :approval_request
                               {:task "Accept the week's meals."
                                :scope [{:kind "meal" :actions ["accept"]}]}
                               {:principal requester})))))

(defn- row-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))

(defn- verdicts [eng id]
  (filterv #(contains? #{:approve :deny} (:action %))
           (store/with-tx (:storage eng)
             #(store/transitions (:storage eng) %
                                 {:kind :approval_request :resource-id (str id)} {}))))

(defn- read! [h as id]
  (:structuredContent (tool h as "waymark_app_read" {:kind "approval_request" :id id})))

(defn- act! [h as id action ticket & [more]]
  (tool h as "waymark_app_act"
        (merge {:kind "approval_request" :id id :action action :ticket ticket} more)))

(defn- leash
  "What a minted grant gives, without the ids that name it."
  [eng ask-id]
  (let [ask (row-of eng :approval_request ask-id)
        grant (row-of eng :grant (get-in ask [:data :grant_id]))]
    {:ask (name (:state ask))
     :approved-by (get-in ask [:data :approved_by])
     :grant (some-> (:state grant) name)
     :data (select-keys (:data grant) [:audience :scope :expires_at])}))

(deftest the-persons-approve-mints-the-grant-as-the-feeds-tap-does
  (let [{:keys [eng h]} (world)
        as (initialize! h)
        id (ask! eng)
        view (read! h as id)
        [approve deny] (:doors view)
        scope (some #(when (= "scope" (:label %)) (:value %)) (:fields view))]
    (testing "the read is the person's, and carries the sentence beside the ticket"
      (is (= [["approve" true] ["deny" true]]
             (mapv (juxt :action :available) (:doors view))))
      (is (= sentence (:consequence approve)))
      (is (nil? (:consequence deny)))
      (is (= ["meal" ["accept"]] ((juxt :kind :actions) (first scope))))
      (is (some? (:ticket view))))
    (testing "the tap lands as the person, under an mcp-app/ key"
      (let [r (act! h as id "approve" (:ticket view) {:acknowledge (:consequence approve)})
            [tap & more] (verdicts eng id)]
        (is (false? (:isError r)) (text-of r))
        (is (= [:approve nil] [(:action tap) more]))
        (is (= "colton" (get-in tap [:actor :id])))
        (is (str/starts-with? (str (:idempotency-key tap))
                              "mcp-app/connector%3Acolton/"))))
    (testing "the grant is the one the feed's tap mints"
      (let [feed (world)
            fid (ask! (:eng feed))
            tapped ((:h feed) {:request-method :post
                               :uri (str "/api/approval_requests/" fid "/-/approve")
                               :headers {"x-waymark-principal" "colton"}
                               :body "{}"})
            minted (leash eng id)]
        (is (= 200 (:status tapped)) (str (:body tapped)))
        (is (= "approved" (:ask minted)))
        (is (= "colton" (:approved-by minted)))
        (is (= "connector:colton" (get-in minted [:data :audience])))
        (is (some? (:grant minted)) "the grant row exists")
        (is (= (leash (:eng feed) fid) minted))))))

(deftest the-delegates-own-approve-is-refused-as-today
  (let [{:keys [eng h]} (world)
        as (initialize! h)
        id (ask! eng)
        own (tool h as "waymark_invoke"
                  {:kind "approval_request" :id id :action "approve"
                   :acknowledge sentence})]
    (is (true? (:isError own)) (text-of own))
    (is (= [] (verdicts eng id)))
    (is (= "offered" (name (:state (row-of eng :approval_request id)))))))

(deftest the-list-changed-notice-still-fires-after-the-persons-approve
  ;; the stream (routes/mcp.clj) pushes list_changed for each grant and
  ;; approval_request transition this predicate admits for the caller
  (let [{:keys [eng h]} (world)
        as (initialize! h)
        id (ask! eng)
        view (read! h as id)
        r (act! h as id "approve" (:ticket view) {:acknowledge sentence})
        ask (row-of eng :approval_request id)
        grant (row-of eng :grant (get-in ask [:data :grant_id]))]
    (is (false? (:isError r)) (text-of r))
    (is (= 1 (count (verdicts eng id))) "one approval_request transition to push on")
    (is (true? (mcp/grant-moved-for? :approval_request ask "connector:colton")))
    (is (true? (mcp/grant-moved-for? :grant grant "connector:colton")))
    (is (false? (mcp/grant-moved-for? :approval_request ask "connector:mom"))
        "and nobody else's stream hears of it")))

(deftest an-approve-whose-acknowledge-is-not-the-sentence-is-refused
  (let [{:keys [eng h]} (world)
        as (initialize! h)
        id (ask! eng)
        ticket (:ticket (read! h as id))]
    (doseq [[why more] [["no acknowledge" nil]
                        ["a paraphrase" {:acknowledge "The requester gets this scope now."}]]]
      (testing why
        (let [r (act! h as id "approve" ticket more)]
          (is (true? (:isError r)))
          (is (str/includes? (text-of r) sentence) "the gate names the sentence it wants")
          (is (= [] (verdicts eng id)))
          (is (= "offered" (name (:state (row-of eng :approval_request id))))))))
    (testing "and the same ticket, with the read's sentence, still lands"
      (is (false? (:isError (act! h as id "approve" ticket {:acknowledge sentence}))))
      (is (= [:approve] (mapv :action (verdicts eng id)))))))

(deftest deny-with-a-note-lands-the-note
  (let [{:keys [eng h]} (world)
        as (initialize! h)
        id (ask! eng)
        view (read! h as id)
        deny (second (:doors view))
        r (act! h as id "deny" (:ticket view) {:input {:note "Ask for less."}})
        row (row-of eng :approval_request id)]
    (is (some #{"note"} (:inputs deny)) "the page has a box for the note")
    (is (false? (:isError r)) (text-of r))
    (is (= "denied" (name (:state row))))
    (is (= "Ask for less." (get-in row [:data :note])))
    (is (= [[:deny "colton"]]
           (mapv (juxt :action #(get-in % [:actor :id])) (verdicts eng id))))))
