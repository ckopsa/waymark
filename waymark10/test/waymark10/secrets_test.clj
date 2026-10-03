(ns waymark10.secrets-test
  "The secret (ticket 8cd850fa): the owner enters a value once, no
  grant reads it, and a power call names it by reference — against an
  in-process fake server registered through the engine's `:client-fn`
  seam. No network, memory storage, real rows through the engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.schema :as schema]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.grants :as grants]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.render :as render]
            [waymark10.server.secrets :as secrets]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.server.transcripts :as transcripts]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private the-value "tskey-client-0123456789abcdef")

(def ^:private vault-tools
  [{:name "echo" :description "Say the token back."
    :inputSchema {:type "object"
                  :properties {:token {:type "string" :x-secret-ref true}}}}
   {:name "login" :description "Log in with the token."
    :inputSchema {:type "object"
                  :properties {:token {:type "string" :x-secret-ref true}
                               :host {:type "string"}}}}])

(defn- fake-server
  "An in-process MCP server that answers every call with its own
  arguments, so an echo is what it always does. Every call lands on
  `log`."
  [log]
  (fn [method params]
    (swap! log conj {:method method :params params})
    (case method
      "tools/list" {:tools vault-tools}
      "tools/call" {:content [{:type "text"
                               :text (str "answered " (:name params) " "
                                          (wire/write-json (:arguments params)))}]
                    :isError false})))

(def ^:private vault-powers
  [{:power "vault.echo" :tools ["echo"] :approval "none"}
   {:power "vault.login" :tools ["login"] :approval "person"}])

(def ^:private clock (Instant/parse "2026-10-03T09:00:00Z"))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(def ^:private approver
  (t/principal {:id "mom" :display "Mom" :roles #{"approver"}}))

(def ^:private clerk
  (t/principal {:id "vault-clerk" :type :agent :display "Clerk"
                :model "secrets-test-model"}))

(defn- world []
  (let [log (atom [])
        eng (engine/engine {:storage (memory/storage)
                            :resources [caps/capability]
                            :now-fn (fn [] clock)
                            :services {:mcp-servers
                                       {:client-fn (fn [row]
                                                     (when (= "vault" (get-in row [:data :name]))
                                                       (fake-server log)))}}})]
    (doseq [token ["vault.echo" "vault.login"]]
      (inv/create! eng :capability
                   {:token token
                    :description (str token " through a server row.")
                    :enforced_by "this engine's own power door"}
                   {:principal colton}))
    (inv/create! eng :mcp_server
                 {:name "vault" :transport "http" :url "http://fake.invalid/mcp/"
                  :powers vault-powers}
                 {:principal colton})
    {:eng eng :log log}))

(defn- wear
  "A grant naming `scope`, minted by a person and accepted by the
  clerk → the session the MCP door reads."
  [eng scope]
  (let [gid (str (:id (:row (inv/create! eng :grant
                                         {:audience (:id clerk) :scope scope}
                                         {:principal colton}))))]
    (inv/invoke! eng :grant gid :accept {} {:principal clerk})
    {:principal clerk :visibility (grants/visibility eng gid clerk)}))

(defn- tool! [eng session tool-name args]
  (get-in (mcp/message eng (mcp/door eng) (gate/rpc-of eng) session
                       {:jsonrpc "2.0" :id 1 :method "tools/call"
                        :params {:name tool-name :arguments args}})
          [:result]))

(defn- text-of [result] (str (get-in result [:content 0 :text])))

(defn- row-of [eng kind id]
  (let [rdef (get (inv/resources eng) kind)]
    (inv/decode-row rdef
                    (store/with-tx (:storage eng)
                      (fn [tx] (store/load-row (:storage eng) tx kind (str id) {}))))))

(defn- calls [log]
  (filterv #(= "tools/call" (:method %)) @log))

(defn- refusal [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (if (:waymark10/problem d) d (throw e))))))

(defn- a-secret! [eng]
  (str (:id (:row (inv/create! eng :secret
                               {:name "TS_OAUTH_SECRET" :value the-value}
                               {:principal colton})))))

(defn- replace! [eng id value who]
  (inv/invoke! eng :secret id :replace {:value value}
               {:principal who
                :if-match (inv/etag :secret id (:version (row-of eng :secret id)))}))

;; ── the value is write-only ─────────────────────────────────────────

(deftest the-value-is-the-one-secret
  (is (= #{:value} (schema/secret-fields (:schema secrets/secret)))))

(deftest a-person-sets-a-value-and-no-seat-grant-reads-it
  (let [{:keys [eng]} (world)
        id (a-secret! eng)
        session (wear eng [{:kind "secret" :actions []}])]
    (testing "the engine holds the value"
      (is (= the-value (get-in (row-of eng :secret id) [:data :value])))
      (is (= "colton" (get-in (row-of eng :secret id) [:data :created_by]))))
    (testing "no envelope carries it"
      (let [env (render/envelope (get (inv/resources eng) :secret)
                                 (row-of eng :secret id)
                                 {:principal clerk :now clock})]
        (is (not (contains? (get env "data") "value")))
        (is (not (str/includes? (pr-str env) the-value)))))
    (testing "no door the seat's grant opens serves it"
      (doseq [[tool args] [["waymark_get" {:kind "secret" :id id}]
                           ["waymark_query" {:kind "secret"}]
                           ["waymark_history" {:kind "secret" :id id}]]]
        (let [out (text-of (tool! eng session tool args))]
          (is (not (str/blank? out)) tool)
          (is (not (str/includes? out the-value)) tool))))
    (testing "the log says what was done, without the value"
      (let [log (store/with-tx (:storage eng)
                  (fn [tx] (store/transitions (:storage eng) tx
                                              {:kind :secret :resource-id id} {})))]
        (is (not (str/includes? (pr-str log) the-value)))))
    (testing "an agent neither sets nor replaces a value"
      (is (= :a-person-enters-the-value
             (:guard (refusal #(inv/create! eng :secret
                                            {:name "OTHER" :value the-value}
                                            {:principal clerk})))))
      (is (= :the-owner-fills-the-value
             (:guard (refusal #(replace! eng id "tskey-other-value-000" clerk)))))
      (is (= the-value (get-in (row-of eng :secret id) [:data :value]))))))

(deftest a-seat-asks-for-a-secret-and-the-owner-fills-it
  (let [{:keys [eng]} (world)
        row (:row (inv/create! eng :secret
                               {:name "TS_OAUTH_CLIENT_ID"
                                :note "For home-infrastructure#54."}
                               {:principal clerk}))
        id (str (:id row))]
    (is (nil? (get-in row [:data :value])))
    (is (= "vault-clerk" (get-in row [:data :created_by])))
    (testing "a ref to it refuses until it holds a value"
      (is (some? (refusal #(secrets/resolve-refs!
                            eng {:properties {:token {:x-secret-ref true}}}
                            {:token id})))))
    (replace! eng id the-value colton)
    (let [row (row-of eng :secret id)]
      (is (= the-value (get-in row [:data :value])))
      (is (= clock (get-in row [:data :value_set_at]))))))

;; ── by reference ────────────────────────────────────────────────────

(deftest a-held-call-forwards-the-value-and-keeps-only-the-id
  (let [{:keys [eng log]} (world)
        id (a-secret! eng)
        session (wear eng [{:kind "vault.login" :actions []}])
        out (tool! eng session "waymark_power"
                   {:tool "vault__login"
                    :arguments {:token id :host "tail.example"
                                :why "The tofu run needs it."}})
        held-id (str (:held_call (wire/read-json (text-of out))))]
    (testing "the held call keeps the id"
      (let [row (row-of eng :held_call held-id)]
        (is (str/includes? (pr-str (get-in row [:data :forward])) id))
        (is (not (str/includes? (pr-str row) the-value)))))
    (is (empty? (calls log)) "nothing went out before the tap")
    (let [rdef (get (inv/resources eng) :held_call)]
      (held/after-allow! eng rdef :allow
                         (inv/invoke! eng :held_call held-id :allow {}
                                      {:principal approver})))
    (testing "the door received the value"
      (is (= [the-value]
             (mapv #(get-in % [:params :arguments :token]) (calls log)))))
    (testing "each forward stamps used_at"
      (is (= clock (get-in (row-of eng :secret id) [:data :used_at]))))
    (testing "the answer it landed is scrubbed of the echo"
      (let [row (row-of eng :held_call held-id)]
        (is (= "done" (name (:state row))))
        (is (not (str/includes? (pr-str row) the-value)))
        (is (str/includes? (pr-str row) "[redacted:secret]"))))))

(deftest a-value-echoed-in-a-door-answer-is-redacted
  (let [{:keys [eng log]} (world)
        id (a-secret! eng)
        session (wear eng [{:kind "vault.echo" :actions []}])
        out (text-of (tool! eng session "waymark_power"
                            {:tool "vault__echo" :arguments {:token id}}))]
    (is (= [the-value]
           (mapv #(get-in % [:params :arguments :token]) (calls log))))
    (is (str/includes? out "answered echo"))
    (is (str/includes? out "[redacted:secret]"))
    (is (not (str/includes? out the-value)))
    (testing "the transcript's second pass knows every value"
      (is (contains? (secrets/values eng) the-value))
      (let [[line n] (transcripts/redact-line (str "the door said " the-value)
                                              {:raw (secrets/values eng)})]
        (is (pos? (long n)))
        (is (not (str/includes? line the-value)))))))
