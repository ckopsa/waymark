(ns waymark10.mcp-apps-test
  "MCP Apps child 1 (docs/spec-mcp-apps.md): a held call's Allow and
  Refuse inline, as the person. The real handler on memory storage; a
  delegate is declared by the dev headers, as mcp_sit_test declares one."
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

(defn- delegate [client sub]
  {"x-waymark-principal" (str client ":" sub)
   "x-waymark-actor-type" "agent"
   "x-waymark-acts-for" sub})

(def ^:private colton (delegate "connector" "colton"))

(defn- world
  "The house: `connector` is listed for the app tools, `other` is not."
  [& [oidc]]
  (let [clock (atom (Instant/parse "2026-10-01T09:00:00Z"))
        eng (engine/engine
             {:storage (memory/storage)
              :resources [fx/meal]
              :now-fn (fn [] @clock)
              :oidc (merge {:issuer "https://idp.test/realms/home"
                            :audience "apps-test"
                            :jwks-uri "https://idp.test/certs"
                            :delegate-clients {"connector" "Claude" "other" "Other"}
                            :app-clients ["connector"]
                            :app-ticket-secret "a-ticket-secret-for-the-test"}
                           oidc)})]
    {:clock clock :eng eng :h (engine/handler eng)}))

(defn- rpc [h headers method params]
  (h {:request-method :post :uri "/api/-/mcp" :headers headers
      :body (wire/write-json (cond-> {:jsonrpc "2.0" :id 1 :method method}
                               params (assoc :params params)))}))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- text-of [result] (str (get-in result [:content 0 :text])))

(defn- tool [h headers tool-name args]
  (:result (json (rpc h headers "tools/call" {:name tool-name :arguments args}))))

(defn- tools-of [h headers]
  (get-in (json (rpc h headers "tools/list" nil)) [:result :tools]))

(def ^:private ui-capability
  {:extensions {"io.modelcontextprotocol/ui" {:mimeTypes [mcp/app-mime]}}})

(defn- initialize! [h bearer capabilities]
  (let [resp (rpc h bearer "initialize"
                  {:protocolVersion mcp/protocol-version
                   :capabilities capabilities
                   :clientInfo {:name "claude-ai" :version "1"}})]
    (assoc bearer "mcp-session-id" (get-in resp [:headers "Mcp-Session-Id"]))))

(def ^:private the-fixed (mapv :name (mcp/listing)))
(def ^:private a-key "bWNwLWFwcHMtdGVzdC1zZWF0LWtleS0wMDAx")

(defn- seat-sat!
  "A seat, sat by a session of colton's connector that declared the
  extension, or by `bearer`'s session that declared `capabilities`."
  [{:keys [eng h]} & [bearer capabilities]]
  (let [model (:row (inv/create! eng :model
                                 {:name "apps-test-model" :display "Apps 1"
                                  :vendor "anthropic" :tier "strong"
                                  :price_input_per_mtok 3M :price_output_per_mtok 15M
                                  :price_cache_read_per_mtok 0.3M
                                  :price_cache_write_per_mtok 3.75M}
                                 {:principal person}))
        seat (:row (inv/create! eng :seat
                                {:name "apps-clerk"
                                 :charter "Decide whether a meal belongs on the list."
                                 :scope [{:kind "meal" :actions ["accept"]}]
                                 :held_for [(:id model)]
                                 :standing_ttl_seconds 604800 :cadence_seconds 3600
                                 :budget_usd_per_week 5M
                                 :sitting_budget_tokens 1000000}
                                {:principal person}))
        _ (inv/invoke! eng :seat (:id seat) :offer_key {:key a-key} {:principal person})
        as (initialize! h (or bearer colton) (or capabilities ui-capability))
        sat (tool h as "waymark_sit" {:key a-key})]
    (assert (false? (:isError sat)) (text-of sat))
    {:as as
     :sitter (seats/sitter-id seat)
     :sitting (str (:sitting (wire/read-json (text-of sat))))}))

(defn- hold!
  "A door call `caller` made, held for `owner`'s tap. → the row's id."
  [eng caller owner]
  (str (:id (held/hold-door! eng {:kind "meal" :action "create"
                                  :body {:name "Ramen" :themes []}
                                  :caller caller :owner owner
                                  :why "The seat wants a meal on the list."}))))

(defn- taps [eng id]
  (filterv #(contains? #{:allow :refuse} (:action %))
           (store/with-tx (:storage eng)
             #(store/transitions (:storage eng) %
                                 {:kind :held_call :resource-id (str id)} {}))))

(defn- ticket-of [h as id]
  (get-in (tool h as "waymark_app_read" {:kind "held_call" :id id})
          [:structuredContent :ticket]))

(defn- act! [h as id action ticket & [input]]
  (tool h as "waymark_app_act"
        (cond-> {:kind "held_call" :id id :action action :ticket ticket}
          input (assoc :input input))))

(deftest a-client-without-the-extension-gets-todays-door
  (let [{:keys [h]} (world)
        as (initialize! h colton {})
        listed (tools-of h as)
        error-of #(:error (json (rpc h as %1 %2)))]
    (is (= the-fixed (mapv :name listed)) "the eleven")
    (is (not-any? :_meta listed))
    (is (= -32601 (:code (error-of "resources/read" {:uri mcp/app-resource-uri}))))
    (is (some? (error-of "tools/call" {:name "waymark_app_read"}))
        "an app tool is the unknown-tool protocol error")
    (is (= (wire/write-json {:held true :held_call "x" :note held/held-note})
           (text-of (held/held-answer "x")))
        "the held answer is today's")))

(deftest every-other-caller-gets-the-fallback
  (doseq [[why w bearer]
          [["an unlisted delegate client" (world) (delegate "other" "colton")]
           ["an agent bearer that acts for nobody" (world)
            (dissoc colton "x-waymark-acts-for")]
           ["a listed client, and no signing key" (world {:app-ticket-secret nil}) colton]
           ["a listed client, and an empty key" (world {:app-ticket-secret ""}) colton]]]
    (testing why
      (let [h (:h w)
            as (initialize! h bearer ui-capability)]
        (is (= (:body (rpc h bearer "tools/list" nil)) (:body (rpc h as "tools/list" nil)))
            "today's list, byte for byte")
        (is (= the-fixed (mapv :name (tools-of h as))))
        (is (= -32601 (get-in (json (rpc h as "resources/list" nil)) [:error :code])))))))

(deftest a-listed-client-that-declared-it-gets-the-page-and-the-tools
  (let [{:keys [h]} (world)
        as (initialize! h colton ui-capability)
        listed (tools-of h as)
        ui-of (fn [n] (some #(when (= n (:name %)) (get-in % [:_meta :ui])) listed))
        page (get-in (json (rpc h as "resources/read" {:uri mcp/app-resource-uri}))
                     [:result :contents 0])]
    (is (= (into the-fixed ["waymark_show" "waymark_app_read" "waymark_app_act"])
           (mapv :name listed)))
    (is (= {:resourceUri "ui://waymark/row"} (ui-of "waymark_show")))
    (doseq [n ["waymark_app_read" "waymark_app_act"]]
      (is (= ["app"] (:visibility (ui-of n))) n))
    (is (= "text/html;profile=mcp-app" (:mimeType page)))
    (is (str/includes? (str (:text page)) "ui/initialize"))))

(deftest the-persons-tap-moves-the-seats-held-call
  (let [{:keys [eng h] :as w} (world)
        {:keys [as sitter sitting]} (seat-sat! w)
        id (hold! eng sitter "colton")
        own (tool h as "waymark_invoke" {:kind "held_call" :id id :action "allow"})
        served #(get-in (store/with-tx (:storage eng)
                          (fn [tx] (store/load-row (:storage eng) tx :sitting sitting {})))
                        [:data :served])
        before (served)
        seen (tool h as "waymark_app_read" {:kind "held_call" :id id})
        ticket (get-in seen [:structuredContent :ticket])]
    ;; this seat's grant names no held_call door, so the sitter meets the
    ;; concealing 404 ahead of `the-caller-does-not-decide`
    (is (true? (:isError own)) "the sitter's own invoke is still refused")
    (testing "the read is the person's"
      (is (false? (:isError seen)) (text-of seen))
      (is (= [["allow" true] ["refuse" true]]
             (mapv (juxt :action :available) (get-in seen [:structuredContent :doors]))))
      (is (not (str/includes? (text-of seen) ticket))
          "the ticket rides structuredContent only"))
    (testing "the tap lands as the person, under an mcp-app/ key"
      (let [r (act! h as id "allow" ticket)
            [tap & more] (taps eng id)]
        (is (false? (:isError r)) (text-of r))
        (is (= [:allow nil] [(:action tap) more]))
        (is (= "colton" (get-in tap [:actor :id])))
        (is (str/starts-with? (str (:idempotency-key tap))
                              "mcp-app/connector%3Acolton/"))))
    (testing "a replayed act lands one transition, and the sitting counted none"
      (act! h as id "allow" ticket)
      (is (= 1 (count (taps eng id))))
      (is (= before (served))))
    (testing "the person still answers after the sitting closed"
      (seats/claim-rows! eng sitting ["a-row"])
      (tool h as "waymark_invoke"
            {:kind "sitting" :id sitting :action "close"
             :input {:input_tokens 1000 :output_tokens 100
                     :cache_read_tokens 0 :cache_write_tokens 0 :turns 1}})
      (is (str/includes? (text-of (tool h as "waymark_query" {:kind "meal"}))
                         "sitting_closed"))
      (let [late (hold! eng sitter "colton")
            r (act! h as late "refuse" (ticket-of h as late) {:reason "Too late."})]
        (is (false? (:isError r)) (text-of r))
        (is (= [[:refuse "colton"]]
               (mapv (juxt :action #(get-in % [:actor :id])) (taps eng late))))))))

(deftest the-sitting-says-what-its-client-declared
  (let [stamps (fn [bearer capabilities]
                 (let [{:keys [eng] :as w} (world)
                       {:keys [sitting]} (seat-sat! w bearer capabilities)
                       row (store/with-tx (:storage eng)
                             (fn [tx] (store/load-row (:storage eng) tx :sitting sitting {})))]
                   (select-keys (:data row)
                                [:client_name :client_version :app_ui :app_tools])))
        named {:client_name "claude-ai" :client_version "1"}]
    (is (= (assoc named :app_ui true :app_tools true)
           (stamps colton ui-capability))
        "a listed client that declared the extension")
    (is (= (assoc named :app_ui false :app_tools false)
           (stamps colton {}))
        "a session without the extension")
    (is (= (assoc named :app_ui true :app_tools false)
           (stamps (delegate "other" "colton") ui-capability))
        "a declared session on an unlisted delegate client")))

(deftest the-read-says-the-call-in-words-and-links-the-row
  (let [{:keys [eng h] :as w} (world {:app-url "https://work.example/"})
        {:keys [as sitter]} (seat-sat! w)
        id (hold! eng sitter "colton")
        view (:structuredContent (tool h as "waymark_app_read" {:kind "held_call" :id id}))
        allow (some #(when (= "allow" (:action %)) %) (:doors view))
        page (get-in (json (rpc h as "resources/read" {:uri mcp/app-resource-uri}))
                     [:result :contents 0 :text])]
    (is (= (str "https://work.example/#/api/held_calls/" id) (:link view)))
    (is (= "apps-clerk wants to create meal" (:title view))
        "the seat by its name, the action in words")
    (is (= "create · meal" (some #(when (= "door" (:label %)) (:value %)) (:fields view))))
    (is (not-any? #{"patch"} (:inputs allow)) "the edit-door flag is no person's field")
    (is (not-any? #{"shown"} (map :label (:fields view))))
    (is (str/includes? (str page) "p.refusal") "the page carries 030-app.css"))
  (testing "a caller the person cannot read stays its bare id, and no :app-url is no link"
    (let [{:keys [eng h]} (world)
          as (initialize! h colton ui-capability)
          id (hold! eng "seat:somebody" "colton")
          view (:structuredContent (tool h as "waymark_app_read" {:kind "held_call" :id id}))]
      (is (= "seat:somebody wants to create meal" (:title view)))
      (is (nil? (:link view))))))

(deftest somebody-elses-call-is-refused-by-the-second-wall
  (let [{:keys [eng h]} (world)
        id (hold! eng "seat:somebody" "colton")
        as (initialize! h (delegate "connector" "mom") ui-capability)
        r (act! h as id "allow" (ticket-of h as id))]
    (is (true? (:isError r)))
    (is (re-find #"an[-_]approver[-_]decides" (text-of r)) (text-of r))
    (is (= [] (taps eng id)))))

(deftest the-ticket-binds-the-tap-to-what-was-rendered
  (let [{:keys [eng h clock]} (world)
        as (initialize! h colton ui-capability)
        id (hold! eng "seat:somebody" "colton")
        ticket (ticket-of h as id)
        refused? (fn [r] (and (true? (:isError r)) (empty? (taps eng id))))]
    (is (refused? (act! h as id "allow" nil)) "no ticket")
    (is (refused? (act! h as id "allow" (str ticket "x"))) "one nobody signed")
    (is (refused? (act! h (initialize! h colton ui-capability) id "allow" ticket))
        "one minted for another session")
    (is (refused? (act! h as id "land" ticket)) "a door outside the ticket's")
    (swap! clock #(.plusSeconds ^Instant % 601))
    (is (refused? (act! h as id "allow" ticket)) "an expired one")
    (testing "a row that moved since the read"
      (let [fresh (ticket-of h as id)]
        (inv/invoke! eng :held_call id :refuse {:reason "Not today."} {:principal person})
        (is (true? (:isError (act! h as id "allow" fresh))))
        (is (= [:refuse] (mapv :action (taps eng id))))))))

(deftest show-on-a-row-the-agent-may-not-see-answers-the-uniform-404
  (let [{:keys [eng h]} (world)
        as (initialize! h colton ui-capability)
        hidden (tool h as "waymark_show"
                     {:kind "held_call" :id (hold! eng "seat:somebody" "colton")})]
    (is (true? (:isError hidden)))
    (is (= 404 (:status (wire/read-json (text-of hidden)))))
    (is (= 2 (count (:content hidden))) "with the one hint every 404 carries")))
