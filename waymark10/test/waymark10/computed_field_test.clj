(ns waymark10.computed-field-test
  "A kind's :computed field: read-only, computed at read time from the
  decoded stored row. It shows in data, the grid fields, collection
  items and the published schema (readOnly); no body may name it; a
  grant that omits it never sees it; a throwing fn renders nil and
  warns once. The same claims hold over the real ring handler, and
  the OpenAPI document lists it. Storage is the in-memory twin."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.resource :as r]
            [waymark10.schema :as schema]
            [waymark10.server.collections :as collections]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.openapi :as openapi]
            [waymark10.server.render :as render]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private ana (t/principal {:id "ana" :type :human :display "Ana"}))

(def ^:private quiet
  {:idempotent true :reversible false :confirm false
   :one-way "The prior value is on the audit trail."})

;; ── computed fields ──────────────────────────────────────────────────

(r/defhandler shout-rename-handler [row inp _ctx]
  (assoc-in row [:data :name] (:name inp)))

(defn- shouting
  "A small kind whose :echo is computed by `compute`."
  [kind compute]
  (r/resource
   {:kind kind
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.name} · {state}"
    :schema [:map [:name [:string {:min 1 :max 80}]]]
    :computed {:echo {:schema [:maybe :string]
                      :x-display {:label "Echo"}
                      :fn compute}}
    :actions
    {:rename {:from #{:open} :to :open
              :input [:map [:name [:string {:min 1 :max 80}]]]
              :waives #{:edit-shape}
              :safety quiet
              :handler shout-rename-handler}
     :close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A closed shout stays closed."}}}}))

(def shout
  (shouting :shout (fn [row _ctx]
                     (some-> (get-in row [:data :name]) str/upper-case))))

(def broken
  (shouting :broken (fn [_row _ctx] (throw (ex-info "boom" {})))))

(defn- shouts [] (inv/engine {:storage (memory/storage)
                              :resources [shout broken]}))

(defn- create-shout! [eng kind]
  (:row (inv/create! eng kind {:name "hello"} {:principal ana})))

(deftest a-computed-field-shows-in-get
  (let [row (create-shout! (shouts) :shout)
        env (render/envelope shout row {:now (Instant/now)})]
    (testing "never stored"
      (is (not (contains? (:data row) :echo))))
    (testing "in data and in the grid fields"
      (is (= "HELLO" (get-in env ["data" "echo"])))
      (is (= "HELLO" (get-in env ["fields" "echo"]))))
    (testing "no action input names it"
      (is (not (contains? (get-in env ["actions" "rename" "input" "properties"])
                          "echo"))))))

(deftest a-computed-field-shows-in-query-items
  (let [eng (shouts)
        _ (create-shout! eng :shout)
        col (collections/envelope eng shout {} {:now (Instant/now)})
        items (get-in col ["data" "items"])]
    (is (= 1 (count items)))
    (is (= "HELLO" (get-in (first items) ["fields" "echo"])))
    (testing "the create input does not name it"
      (is (not (contains? (get-in col ["actions" "create" "input" "properties"])
                          "echo"))))))

(deftest a-computed-field-is-published-read-only
  (let [js (schema/with-computed (schema/json-schema (:schema shout))
             (:computed shout))]
    (is (true? (get-in js [:properties :echo :readOnly])))
    (is (not (some #{"echo" :echo} (:required js))))
    (is (contains? (:properties js) :name))))

(deftest a-body-naming-a-computed-field-is-refused
  (let [eng (shouts)]
    (testing "create"
      (is (thrown? Exception
                   (inv/create! eng :shout {:name "x" :echo "X"}
                                {:principal ana}))))
    (testing "an action"
      (let [row (create-shout! eng :shout)]
        (is (thrown? Exception
                     (inv/invoke! eng :shout (:id row) :rename
                                  {:name "y" :echo "Y"} {:principal ana})))))))

(deftest a-grant-that-omits-a-computed-field-never-sees-it
  (let [row (create-shout! (shouts) :shout)
        vis {:kind? (constantly true)
             :action? (constantly true)
             :arg? (constantly true)
             :field? (fn [_kind f] (not= "echo" (name f)))}
        env (render/envelope shout row {:now (Instant/now) :visibility vis})]
    (is (= "hello" (get-in env ["data" "name"])))
    (is (not (contains? (get env "data") "echo")))
    (is (not (contains? (get env "fields") "echo")))))

(deftest a-throwing-computed-fn-renders-nil-and-warns-once
  (let [row (create-shout! (shouts) :broken)
        err (java.io.StringWriter.)
        env (binding [*err* err]
              (render/envelope broken row {:now (Instant/now)}))]
    (is (= "hello" (get-in env ["data" "name"])))
    (is (nil? (get-in env ["data" "echo"])))
    (is (= 1 (count (re-seq #"computed field \[broken\.echo\]" (str err)))))))

;; ── computed fields over the real ring handler ────────────────────────
;; GET row and GET collection carry it, /api/schemas/{kind} publishes
;; it readOnly, a grant that omits it never sees it on any of those
;; routes, and the OpenAPI document lists it in {kind}_data.

(def ^:private cf-shout
  (r/resource
   {:kind :cf_shout
    :plural "cf_shouts"
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.name} · {state}"
    :schema [:map [:name [:string {:min 1 :max 80}]]]
    :computed {:echo {:schema [:maybe :string]
                      :x-display {:label "Echo"}
                      :fn (fn [row _ctx]
                            (some-> (get-in row [:data :name]) str/upper-case))}}
    :actions
    {:rename {:from #{:open} :to :open
              :input [:map [:name [:string {:min 1 :max 80}]]]
              :waives #{:edit-shape}
              :safety quiet
              :handler shout-rename-handler}
     :close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "A closed shout stays closed."}}}}))

(def ^:private as-owner {"x-waymark-principal" "owner"})
(def ^:private as-agent {"x-waymark-principal" "agent-7"
                         "x-waymark-actor-type" "agent"})

(defn- route!
  "One request through the real handler; :doc is the parsed body,
  :text the raw one."
  [eng method uri & {:keys [body headers]}]
  (let [resp ((engine/handler eng)
              (cond-> {:request-method method :uri uri
                       :headers (merge {"content-type" "application/json"}
                                       (or headers as-owner))}
                body (assoc :body (wire/write-json body))))
        text (let [b (:body resp)]
               (cond (nil? b) "" (string? b) b :else (slurp b)))]
    (assoc resp :text text
           :doc (when-not (str/blank? text) (wire/read-json text)))))

(defn- boot-shouts
  "An engine with one cf_shout named hello; answers [eng row-uri]."
  []
  (let [eng (engine/engine {:storage (memory/storage) :resources [cf-shout]})
        r (route! eng :post "/api/cf_shouts" :body {:name "hello"})]
    (is (= 201 (:status r)) (:text r))
    [eng (str (get-in r [:doc :self]))]))

(defn- wear!
  "Mint a grant for agent-7 over `scope`, accept it as agent-7, and
  answer the headers the agent wears presenting it."
  [eng scope]
  (let [minted (route! eng :post "/api/grants"
                       :body {:audience "agent-7" :scope scope})
        _ (is (= 201 (:status minted)) (:text minted))
        gid (last (str/split (str (get-in minted [:doc :self])) #"/"))
        accepted (route! eng :post (str "/api/grants/" gid "/-/accept")
                         :headers as-agent)]
    (is (= 200 (:status accepted)) (:text accepted))
    (assoc as-agent "x-waymark-grant" gid)))

(deftest the-routes-carry-the-computed-field
  (let [[eng uri] (boot-shouts)]
    (testing "GET row: in data and in the grid fields"
      (let [r (route! eng :get uri)]
        (is (= 200 (:status r)) (:text r))
        (is (= "HELLO" (get-in r [:doc :data :echo])))
        (is (= "HELLO" (get-in r [:doc :fields :echo])))
        (is (not (contains? (get-in r [:doc :actions :rename :input :properties])
                            :echo)))))
    (testing "GET collection: in each item's fields"
      (let [r (route! eng :get "/api/cf_shouts")
            items (get-in r [:doc :data :items])]
        (is (= 200 (:status r)) (:text r))
        (is (= 1 (count items)))
        (is (= "HELLO" (get-in (first items) [:fields :echo])))))
    (testing "GET /api/schemas/{kind}: published readOnly, never required"
      (let [r (route! eng :get "/api/schemas/cf_shout")]
        (is (= 200 (:status r)) (:text r))
        (is (true? (get-in r [:doc :properties :echo :readOnly])))
        (is (not (some #{"echo"} (get-in r [:doc :required]))))))))

(deftest a-grant-omitting-the-computed-field-never-sees-it-on-the-routes
  (let [[eng uri] (boot-shouts)
        deny-echo (wear! eng [{:kind "cf_shout" :actions ["rename"]
                               :fields {:mode "deny" :names ["echo"]}}])]
    (testing "GET row"
      (let [r (route! eng :get uri :headers deny-echo)]
        (is (= 200 (:status r)) (:text r))
        (is (= "hello" (get-in r [:doc :data :name])))
        (is (not (contains? (get-in r [:doc :data]) :echo)))
        (is (not (str/includes? (:text r) "HELLO")))))
    (testing "GET collection"
      (let [r (route! eng :get "/api/cf_shouts" :headers deny-echo)]
        (is (= 200 (:status r)) (:text r))
        (is (not (str/includes? (:text r) "HELLO")))))
    (testing "GET /api/schemas/{kind}"
      (let [r (route! eng :get "/api/schemas/cf_shout" :headers deny-echo)]
        (is (= 200 (:status r)) (:text r))
        (is (contains? (get-in r [:doc :properties]) :name))
        (is (not (contains? (get-in r [:doc :properties]) :echo)))))))

(deftest openapi-lists-the-computed-field
  (let [[eng _] (boot-shouts)
        doc (openapi/document eng)
        fetch (get-in doc [:paths "/api/cf_shouts/{id}" :get :responses "200"
                           :content "application/waymark+json" :schema])
        data (get-in fetch [:properties :data])]
    (testing "the fetch response's data carries it readOnly"
      (is (contains? (:properties data) :name))
      (is (true? (get-in data [:properties :echo :readOnly])))
      (is (not (some #{"echo" :echo} (:required data)))))
    (testing "the fetch response still references the shared envelope"
      (is (= "#/components/schemas/envelope" (get fetch "$ref")))
      (is (not (contains? (get-in doc [:components :schemas]) "cf_shout_data"))))
    (testing "the create body does not name it"
      (is (not (contains? (get-in doc [:paths "/api/cf_shouts" :post :requestBody
                                       :content "application/json" :schema
                                       :properties])
                          :echo))))))
