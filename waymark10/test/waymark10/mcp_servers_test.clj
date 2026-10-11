(ns waymark10.mcp-servers-test
  "An MCP server as a row (docs/spec-mcp-servers.md, bead
  waymark-fp62.10): the acceptance cases, one deftest each, against
  an in-process fake server (R-11) — a function that answers
  tools/list and tools/call, registered as a row's client through the
  engine's `:client-fn` seam. No network, memory storage, real rows
  through the engine.

  Acceptance 8 (the gate row with passthrough, and the static map
  gone) lives in gate_proxy_test, beside the tests that walk the
  passthrough row through both surfaces. Acceptance 9 (a stdio row
  started with the bench's own command) is the last test here; it
  runs when python3 and the bench checkout are present and asserts
  its own skip otherwise, because a deftest that runs without an
  assertion is a failure and a machine without python3 is not a
  broken engine."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.mcp-client :as client]
            [waymark10.server.mcp-servers :as servers]
            [waymark10.server.problems :as p]
            [waymark10.server.store :as store]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io File)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.time Instant)
           (java.util.concurrent CountDownLatch TimeUnit)))

;; ── the fake server ─────────────────────────────────────────────────

(def ^:private three-tools
  [{:name "read" :description "Read one message."
    :inputSchema {:type "object"
                  :properties {:uid {:type "string"}}
                  :required ["uid"]}}
   {:name "search" :description "Search messages."
    :inputSchema {:type "object"
                  :properties {:query {:type "string"}}}}
   {:name "send" :description "Send a message."
    :inputSchema {:type "object"
                  :properties {:to {:type "string"}}}}])

(defn- fake-server
  "An in-process MCP server: (fn [method params]) → the JSON-RPC
  :result. `tools` is an atom, so a test can move the list; every
  call lands on `log`."
  [tools log]
  (fn [method params]
    (swap! log conj {:method method :params params})
    (case method
      "tools/list" {:tools @tools}
      "tools/call" {:content [{:type "text"
                               :text (str "answered " (:name params) " "
                                          (wire/write-json (:arguments params)))}]
                    :isError false})))

(def ^:private emila-powers
  "The policy under test: email.read admits two of the three tools,
  email.send the third, and only the send demands a why."
  [{:power "email.read" :tools ["read" "search"] :why false}
   {:power "email.send" :tools ["send"] :why true}])

(def ^:private clock (Instant/parse "2026-09-18T09:00:00Z"))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(def ^:private clerk
  (t/principal {:id "mail-clerk" :type :agent :display "Clerk"
                :model "servers-test-model"}))

(defn- fresh-engine
  "An engine whose client seam answers `clients` by row name; a row
  named in no entry builds its own transport client."
  [{:keys [clients timeout-ms]}]
  (engine/engine {:storage (memory/storage)
                  :resources [caps/capability]
                  :now-fn (fn [] clock)
                  :services {:mcp-servers
                             {:client-fn (fn [row]
                                           (get clients (get-in row [:data :name])))
                              :call-timeout-ms (or timeout-ms 30000)}}}))

(defn- a-server!
  "One mcp_server row, created by a person through the engine."
  [eng body]
  (:row (inv/create! eng :mcp_server
                     (merge {:transport "http" :url "http://fake.invalid/mcp/"}
                            body)
                     {:principal colton})))

(defn- refused
  "The problem a thunk throws, or nil when it answered."
  [thunk]
  (try (thunk) nil (catch Exception e e)))

;; ── acceptance 1: discover mirrors the tools onto the row ───────────

(deftest a-row-discovers-three-tools-with-schemas-and-a-hash
  (let [log (atom [])
        eng (fresh-engine {:clients {"emila" (fake-server (atom three-tools) log)}})
        row (a-server! eng {:name "emila" :powers emila-powers})]
    (is (= :live (:state row)) "a server that answered is born live")
    (is (= ["read" "search" "send"] (mapv :name (get-in row [:data :tools])))
        "the three tools, sorted by name")
    (is (= "Read one message."
           (get-in row [:data :tools 0 :description])))
    (is (= {:type "object" :properties {:uid {:type "string"}} :required ["uid"]}
           (get-in row [:data :tools 0 :input_schema]))
        "each tool's input schema, the server's own")
    (is (= 64 (count (str (get-in row [:data :tools_hash]))))
        "a hash of the list rides beside it")
    (is (= clock (get-in row [:data :discovered_at])))
    (is (= ["tools/list"] (mapv :method @log))
        "the create cost exactly one tools/list")))

(deftest an-unchanged-list-costs-no-transition-and-a-moved-one-lands
  (let [tools (atom three-tools)
        eng (fresh-engine {:clients {"emila" (fake-server tools (atom []))}})
        row (a-server! eng {:name "emila" :powers emila-powers})
        v1 (:version row)]
    (servers/sweep-discover! eng)
    (is (= v1 (:version (servers/row-by-name eng "emila")))
        "an equal hash writes nothing: no version bump")
    (swap! tools conj {:name "folders" :description "List folders."
                       :inputSchema {:type "object" :properties {}}})
    (servers/sweep-discover! eng)
    (let [after (servers/row-by-name eng "emila")]
      (is (= (inc (long v1)) (long (:version after)))
          "a moved hash walks the discover door once")
      (is (= ["folders" "read" "search" "send"]
             (mapv :name (get-in after [:data :tools])))))
    (swap! tools (fn [ts] (vec (remove #(= "send" (:name %)) ts))))
    (servers/sweep-discover! eng)
    (is (= ["folders" "read" "search"]
           (mapv :name (get-in (servers/row-by-name eng "emila") [:data :tools])))
        "a tool that leaves the list leaves the row")))

(deftest a-person-discover-asks-the-server-every-time
  (let [tools (atom three-tools)
        log (atom [])
        eng (fresh-engine {:clients {"emila" (fake-server tools log)}})
        row (a-server! eng {:name "emila" :powers emila-powers})
        id (str (:id row))
        discover! #(:row (inv/invoke! eng :mcp_server id :discover {}
                                      {:principal colton}))
        discovers #(->> (store/with-tx (:storage eng)
                          (fn [tx]
                            (store/transitions (:storage eng) tx
                                               {:kind :mcp_server :resource-id (:id row)}
                                               {:newest-first true :limit 100})))
                        (filter (fn [tr] (= "discover" (name (:action tr)))))
                        count)]
    (is (= ["read" "search" "send"] (mapv :name (get-in (discover!) [:data :tools]))))
    (swap! tools conj {:name "folders" :description "List folders."
                       :inputSchema {:type "object" :properties {}}})
    (is (= ["folders" "read" "search" "send"]
           (mapv :name (get-in (discover!) [:data :tools])))
        "a second inputless discover reads the moved list, not the last answer")
    (is (= 3 (count (filter #(= "tools/list" (:method %)) @log)))
        "the create and each discover asked the server")
    (is (= 1 (discovers))
        "only the discover whose list moved lands in the row's history")))

(deftest an-unchanged-discover-leaves-the-version-alone
  (let [log (atom [])
        eng (fresh-engine {:clients {"emila" (fake-server (atom three-tools) log)}})
        row (a-server! eng {:name "emila" :powers emila-powers})
        history #(count (store/with-tx (:storage eng)
                          (fn [tx]
                            (store/transitions (:storage eng) tx
                                               {:kind :mcp_server :resource-id (:id row)}
                                               {:newest-first true :limit 100}))))
        before (history)
        res (inv/invoke! eng :mcp_server (str (:id row)) :discover {}
                         {:principal colton})]
    (is (= 2 (count (filter #(= "tools/list" (:method %)) @log)))
        "the discover still asked the server")
    (is (= (:version row) (:version (:row res)))
        "the answer carries the version it read")
    (is (= (:version row) (:version (servers/row-by-name eng "emila")))
        "an equal list bumps no version")
    (is (= before (history))
        "and lands no history entry")))

;; ── the grant world: a sitter wearing a leash, through the MCP door ─

(defn- mint-capabilities! [eng]
  (doseq [token ["email.read" "email.send"]]
    (inv/create! eng :capability
                 {:token token
                  :description (str token " through a server row.")
                  :enforced_by "this engine's own power door"}
                 {:principal colton})))

(defn- wear!
  "A grant naming `scope`, minted by a person and accepted by the
  clerk → {:grant-id :session}."
  [eng scope]
  (let [row (:row (inv/create! eng :grant
                               {:audience (:id clerk) :scope scope}
                               {:principal colton}))
        gid (str (:id row))]
    (inv/invoke! eng :grant gid :accept {} {:principal clerk})
    {:grant-id gid
     :session {:principal clerk
               :visibility (grants/visibility eng gid clerk)}}))

(defn- tool!
  "One tools/call through the whole message layer → the result."
  [eng session tool-name args]
  (get-in (mcp/message eng (mcp/door eng) (gate/rpc-of eng) session
                       {:jsonrpc "2.0" :id 1 :method "tools/call"
                        :params {:name tool-name :arguments args}})
          [:result]))

(defn- text-of [result]
  (str (get-in result [:content 0 :text])))

(defn- powers-doc [eng session]
  (wire/read-json (text-of (tool! eng session "waymark_powers" {}))))

;; ── acceptance 2: waymark_powers is the grant's shadow over the rows ─

(deftest a-scope-sees-the-two-tools-its-power-admits-and-not-the-third
  (let [eng (fresh-engine {:clients {"emila" (fake-server (atom three-tools) (atom []))}})
        _ (mint-capabilities! eng)
        _ (a-server! eng {:name "emila" :powers emila-powers})
        {:keys [session]} (wear! eng [{:kind "email.read" :actions []}])
        doc (powers-doc eng session)]
    (is (= #{:emila__read :emila__search} (set (keys (:links doc))))
        "the two tools email.read maps to, wearing the prefix")
    (is (= {} (:actions doc)) "and not the send")
    (is (not (str/includes? (wire/write-json doc) "emila__send"))
        "the third tool's name is not in the document at all")
    (let [read (get-in doc [:links :emila__read])]
      (is (= "email.read" (:capability read)))
      (is (= "/api/-/gate/emila__read" (:href read)))
      (is (contains? (get-in read [:input :properties]) :uid)
          "the mirrored schema is the server's own"))
    (testing "email.send adds the third as a form that demands a why"
      (let [{:keys [session]} (wear! eng [{:kind "email.read" :actions []}
                                         {:kind "email.send" :actions []}])
            doc (powers-doc eng session)
            send (get-in doc [:actions :emila__send])]
        (is (= #{:emila__send} (set (keys (:actions doc)))))
        (is (true? (get-in send [:why :required])))
        (is (contains? (get-in send [:input :properties]) :why))
        (is (some #{"why"} (get-in send [:input :required])))))
    (testing "a grant admitting nothing reads an empty document"
      (let [{:keys [session]} (wear! eng [{:kind "capability" :actions []}])
            doc (powers-doc eng session)]
        (is (= {} (:links doc)))
        (is (= {} (:actions doc)))
        (is (some? (:ask doc)))))))

;; ── acceptance 3: the refusals, before any wire ─────────────────────

(deftest waymark-power-refuses-an-unnamed-tool-and-demands-a-why
  (let [log (atom [])
        eng (fresh-engine {:clients {"emila" (fake-server (atom three-tools) log)}})
        _ (mint-capabilities! eng)
        _ (a-server! eng {:name "emila" :powers emila-powers})
        calls #(filterv (fn [c] (= "tools/call" (:method c))) @log)]

    (testing "a tool no entry names does not exist through the door,
              whatever the server offers"
      (let [{:keys [session]} (wear! eng [{:kind "email.read" :actions []}
                                         {:kind "email.send" :actions []}])
            out (tool! eng session "waymark_power"
                       {:tool "emila__delete" :arguments {}})]
        (is (true? (:isError out)))
        (is (str/includes? (text-of out) "emila__delete"))
        (is (= [] (calls)) "nothing was forwarded")))

    (testing "a tool the grant does not admit refuses naming the ask"
      (let [{:keys [session]} (wear! eng [{:kind "email.read" :actions []}])
            out (tool! eng session "waymark_power"
                       {:tool "emila__send"
                        :arguments {:to "x@y.z" :why "because"}})]
        (is (true? (:isError out)))
        (is (str/includes? (text-of out) "email.send")
            "capabilities are words: the refusal says what to ask for")
        (is (= [] (calls)))))

    (testing "a why-required tool without a why refuses naming why"
      (let [{:keys [session]} (wear! eng [{:kind "email.send" :actions []}])
            out (tool! eng session "waymark_power"
                       {:tool "emila__send" :arguments {:to "x@y.z"}})]
        (is (true? (:isError out)))
        (is (str/includes? (text-of out) "why"))
        (is (= [] (calls)))
        (testing "…and with one, it forwards, the why kept on this side"
          (let [out (tool! eng session "waymark_power"
                           {:tool "emila__send"
                            :arguments {:to "x@y.z" :why "the household asked"}})]
            (is (false? (:isError out)))
            (is (str/starts-with? (text-of out) "answered send"))
            (let [call (last (calls))]
              (is (= "send" (get-in call [:params :name]))
                  "the server hears its own bare name")
              (is (= {:to "x@y.z"} (get-in call [:params :arguments]))
                  "a server that never asked for a why receives none"))))))

    (testing "a granted read forwards and answers the payload verbatim"
      (let [{:keys [session]} (wear! eng [{:kind "email.read" :actions []}])
            out (tool! eng session "waymark_power"
                       {:tool "emila__read" :arguments {:uid "m1"}})]
        (is (false? (:isError out)))
        (is (= "answered read {\"uid\":\"m1\"}" (text-of out)))))))

;; ── acceptance 4: a dark row refuses, naming mark_live ──────────────

(deftest a-dark-row-refuses-with-the-remedy-naming-mark-live
  (let [broken? (atom false)
        inner (fake-server (atom three-tools) (atom []))
        flaky (fn [method params]
                (when @broken?
                  (throw (client/unreachable "the fake is down.")))
                (inner method params))
        eng (fresh-engine {:clients {"emila" flaky}})
        _ (mint-capabilities! eng)
        row (a-server! eng {:name "emila" :powers emila-powers})
        {:keys [session]} (wear! eng [{:kind "email.read" :actions []}])]
    (is (= :live (:state row)))

    (testing "a call that fails on the wire marks the row dark"
      (reset! broken? true)
      (let [e (refused #(servers/call! eng "emila__read" {:uid "1"}))]
        (is (p/problem? e))
        (is (= 502 (:status (ex-data e)))))
      (is (= :dark (:state (servers/row-by-name eng "emila")))))

    (testing "and every power on it refuses with mark_live as the remedy"
      (let [e (refused #(servers/call! eng "emila__read" {:uid "1"}))]
        (is (= 503 (:status (ex-data e))))
        (is (str/includes? (str (first (:remedies (ex-data e)))) "mark_live")))
      (let [out (tool! eng session "waymark_power"
                       {:tool "emila__read" :arguments {:uid "1"}})]
        (is (true? (:isError out)))
        (is (str/includes? (text-of out) "mark_live")
            "the sitter reads the same remedy as tool output"))
      (is (= {} (:links (powers-doc eng session)))
          "a dark row's tools are not offered"))

    (testing "a person's mark_live refuses while the server does not
              answer, and lands when it does"
      (let [e (refused #(inv/invoke! eng :mcp_server (:id row) :mark_live nil
                                     {:principal colton}))]
        (is (p/problem? e))
        (is (= :dark (:state (servers/row-by-name eng "emila")))))
      (reset! broken? false)
      (inv/invoke! eng :mcp_server (:id row) :mark_live nil {:principal colton})
      (let [live (servers/row-by-name eng "emila")]
        (is (= :live (:state live)))
        (is (nil? (get-in live [:data :last_error])))
        (is (= 3 (count (get-in live [:data :tools]))))))))

(deftest a-server-that-does-not-answer-at-create-is-born-dark
  (let [down (fn [_method _params]
               (throw (client/unreachable "nobody home.")))
        eng (fresh-engine {:clients {"emila" down}})
        row (a-server! eng {:name "emila" :powers emila-powers})]
    (is (= :dark (:state row)))
    (is (str/includes? (str (get-in row [:data :last_error])) "nobody home"))
    (is (= "engine" (get-in row [:data :dark_by]))
        "a failure put it dark, so the engine probes it")
    (is (empty? (get-in row [:data :tools])))))

;; ── a dark row comes back on its own ────────────────────────────────

(defn- flaky-server
  "The fake server, failing every message while `down?` holds, the
  way a rig does while it restarts."
  [down? log]
  (let [inner (fake-server (atom three-tools) log)]
    (fn [method params]
      (when @down?
        (throw (client/unreachable "the fake is restarting.")))
      (inner method params))))

(deftest a-row-a-failure-darkened-is-live-again-after-the-probe
  (let [down? (atom false)
        eng (fresh-engine {:clients {"emila" (flaky-server down? (atom []))}})
        _ (a-server! eng {:name "emila" :powers emila-powers})
        t0 1000000
        state #(:state (servers/row-by-name eng "emila"))]
    (reset! down? true)
    (is (p/problem? (refused #(servers/call! eng "emila__read" {:uid "1"}))))
    (is (= :dark (state)) "one failed call puts the row dark")
    (is (= "engine" (get-in (servers/row-by-name eng "emila") [:data :dark_by])))

    (testing "the first pass starts the wait, and a probe the server does
              not answer waits the next step"
      (is (= [] (servers/probe-dark! eng t0)))
      (is (= [] (servers/probe-dark! eng (+ t0 30000))))
      (is (= :dark (state))))

    (reset! down? false)
    (testing "the server is back, but the wait is not over"
      (is (= [] (servers/probe-dark! eng (+ t0 60000))))
      (is (= :dark (state))))

    (testing "and when it is, the probe opens the row without a person"
      (is (= ["emila"] (servers/probe-dark! eng (+ t0 90000))))
      (let [live (servers/row-by-name eng "emila")]
        (is (= :live (:state live)))
        (is (nil? (get-in live [:data :last_error])))
        (is (nil? (get-in live [:data :dark_by])))
        (is (= clock (get-in live [:data :revived_at]))
            "the row records that it came back on its own")
        (is (= 3 (count (get-in live [:data :tools])))))
      (is (false? (:isError (servers/call! eng "emila__read" {:uid "2"})))))))

(deftest a-row-a-person-put-dark-stays-dark
  (let [log (atom [])
        eng (fresh-engine {:clients {"emila" (fake-server (atom three-tools) log)}})
        row (a-server! eng {:name "emila" :powers emila-powers})]
    (inv/invoke! eng :mcp_server (:id row) :retire nil {:principal colton})
    (inv/invoke! eng :mcp_server (:id row) :restore nil {:principal colton})
    (let [dark (servers/row-by-name eng "emila")]
      (is (= :dark (:state dark)))
      (is (= "person" (get-in dark [:data :dark_by]))))
    (reset! log [])

    (testing "no probe pass and no call revives it, though the server answers"
      (is (= [] (servers/probe-dark! eng 0)))
      (is (= [] (servers/probe-dark! eng 100000000)))
      (is (nil? (servers/revive! eng (servers/row-by-name eng "emila"))))
      (let [e (refused #(servers/call! eng "emila__read" {:uid "1"}))]
        (is (= 503 (:status (ex-data e))))
        (is (str/includes? (str (ex-data e)) "A person put it dark")))
      (is (= :dark (:state (servers/row-by-name eng "emila"))))
      (is (= [] @log) "the server was never asked"))

    (testing "a person's mark_live opens it, and it did not come back on its own"
      (inv/invoke! eng :mcp_server (:id row) :mark_live nil {:principal colton})
      (let [live (servers/row-by-name eng "emila")]
        (is (= :live (:state live)))
        (is (nil? (get-in live [:data :dark_by])))
        (is (nil? (get-in live [:data :revived_at])))))))

(deftest a-call-to-a-dark-row-whose-server-is-back-goes-through
  (let [down? (atom false)
        log (atom [])
        eng (fresh-engine {:clients {"emila" (flaky-server down? log)}})
        _ (a-server! eng {:name "emila" :powers emila-powers})]
    (reset! down? true)
    (refused #(servers/call! eng "emila__read" {:uid "1"}))
    (is (= :dark (:state (servers/row-by-name eng "emila"))))

    (testing "while the server is down, the one probe fails and the call answers 503"
      (let [e (refused #(servers/call! eng "emila__read" {:uid "1"}))]
        (is (= 503 (:status (ex-data e))))
        (is (str/includes? (str (first (:remedies (ex-data e)))) "mark_live")))
      (is (= :dark (:state (servers/row-by-name eng "emila")))))

    (reset! down? false)
    (testing "once it is back, the call's own probe opens the row and the call goes out"
      (let [out (servers/call! eng "emila__read" {:uid "9"})]
        (is (false? (:isError out)))
        (is (= "answered read {\"uid\":\"9\"}" (text-of out))))
      (let [live (servers/row-by-name eng "emila")]
        (is (= :live (:state live)))
        (is (= clock (get-in live [:data :revived_at]))))
      (is (= "tools/call" (:method (last @log)))))))

;; ── acceptance 5: a secret never lands in auth_env ──────────────────

(deftest a-restate-whose-auth-env-looks-like-a-value-refuses
  (let [eng (fresh-engine {:clients {"emila" (fake-server (atom three-tools) (atom []))}})]
    (testing "at create"
      (let [e (refused #(a-server! eng {:name "emila" :powers emila-powers
                                        :auth_env "Bearer abc123"}))]
        (is (p/problem? e) "a space is a value")
        (is (nil? (servers/row-by-name eng "emila")) "no row was born"))
      (let [e (refused #(a-server! eng {:name "emila" :powers emila-powers
                                        :auth_env (apply str (repeat 70 "A"))}))]
        (is (p/problem? e) "more than 64 characters is a value")))
    (testing "the name of a variable is what the row holds"
      (let [row (a-server! eng {:name "emila" :powers emila-powers
                                :auth_env "EMILA_TOKEN"})]
        (is (= "EMILA_TOKEN" (get-in row [:data :auth_env])))
        (testing "and the same wall stands at restate"
          (let [e (refused #(inv/invoke! eng :mcp_server (:id row) :restate
                                         {:auth_env "token: abc"}
                                         {:principal colton}))]
            (is (p/problem? e) "a colon is a value"))
          (is (= "EMILA_TOKEN"
                 (get-in (servers/row-by-name eng "emila") [:data :auth_env]))))))))

;; ── acceptance 6: the sitting's served line ─────────────────────────

(def ^:private model-name "servers-test-model")

(defn- a-sitting!
  "A seat, a model and one open sitting under `gid` for the clerk, so
  the served counter has a row to write on → the sitting's id."
  [eng gid]
  (let [seat (:row (inv/create! eng :seat
                                {:name "mail-clerk"
                                 :charter "Decide whether a message asks something."
                                 :scope [{:kind "capability" :actions []}]
                                 :standing_ttl_seconds 604800
                                 :cadence_seconds 3600
                                 :budget_usd_per_week 5M
                                 :sitting_budget_tokens 1000000}
                                {:principal colton}))
        model (:row (inv/create! eng :model
                                 {:name model-name :display "Servers Test 1"
                                  :vendor "anthropic" :tier "economy"
                                  :price_input_per_mtok 1M
                                  :price_output_per_mtok 5M
                                  :price_cache_read_per_mtok 0.1M
                                  :price_cache_write_per_mtok 1.25M}
                                 {:principal colton}))]
    (:id (:row (inv/create! eng :sitting
                            {:seat (:id seat) :model (:id model) :grant gid}
                            {:principal clerk})))))

(defn- served-line [eng sitting-id tool]
  (get-in (store/with-tx (:storage eng)
            (fn [tx] (store/load-row (:storage eng) tx :sitting
                                     (str sitting-id) {})))
          [:data :served (keyword tool)]))

(deftest the-bytes-and-the-dropped-bytes-land-on-the-served-line
  (let [long-text (apply str (repeat 2000 "The stain order waits on the answer. "))
        big (fn [method _params]
              (case method
                "tools/list" {:tools three-tools}
                "tools/call" {:content [{:type "text" :text long-text}]
                              :isError false}))
        eng (fresh-engine {:clients {"emila" big}})
        _ (mint-capabilities! eng)
        _ (a-server! eng {:name "emila" :powers emila-powers})
        {:keys [grant-id session]} (wear! eng [{:kind "email.read" :actions []}])
        sitting (a-sitting! eng grant-id)
        out (tool! eng session "waymark_power"
                   {:tool "emila__read" :arguments {:uid "m1"} :max_chars 500})
        answered (alength (.getBytes ^String (text-of out) StandardCharsets/UTF_8))
        line (served-line eng sitting "waymark_power")]
    (is (false? (:isError out)))
    (is (= 500 (count (text-of out))) "cut where the caller said")
    (is (= 1 (:calls line)))
    (is (= answered (:bytes line)) "what the model read")
    (is (pos? (long (:dropped line))) "and what never left this engine")
    (is (= (alength (.getBytes ^String long-text StandardCharsets/UTF_8))
           (+ (long (:bytes line)) (long (:dropped line))))
        "served plus dropped is what the server itself answered")))

;; ── acceptance 7: one hung server does not stop another ─────────────

(deftest one-hung-server-does-not-stop-anothers-call
  (let [latch (CountDownLatch. 1)
        hung (fn [method _params]
               (case method
                 "tools/list" {:tools three-tools}
                 "tools/call" (do (.await latch) {:content [] :isError false})))
        quick (fake-server (atom three-tools) (atom []))
        eng (fresh-engine {:clients {"hung" hung "quick" quick} :timeout-ms 300})
        _ (a-server! eng {:name "hung" :powers emila-powers})
        _ (a-server! eng {:name "quick" :powers emila-powers})]
    (try
      (let [t0 (System/nanoTime)
            e (refused #(servers/call! eng "hung__read" {:uid "1"}))
            ms (/ (- (System/nanoTime) t0) 1000000.0)]
        (is (p/problem? e))
        (is (= 502 (:status (ex-data e))) "the hung row answers its own timeout")
        (is (< ms 5000.0) "inside its timeout, not a thread's patience"))
      (let [out (servers/call! eng "quick__read" {:uid "1"})]
        (is (false? (:isError out)) "and the other row answers"))
      (is (= :live (:state (servers/row-by-name eng "quick"))))
      (finally
        (.countDown latch)))))

;; ── a tool that waits on purpose outlives the row's bound ───────────

(deftest a-slow-tool-answers-past-the-rows-timeout
  (let [tools [{:name "read" :description "Read a file."
                :inputSchema {:type "object" :properties {}}}
               {:name "test_result" :description "Read a run."
                :inputSchema {:type "object" :properties {}}}]
        latch (CountDownLatch. 1)
        rig (fn [method params]
              (case method
                "tools/list" {:tools tools}
                "tools/call" (do (if (= "read" (:name params))
                                   (.await latch)
                                   (Thread/sleep 900))
                                 {:content [{:type "text" :text "pending"}]
                                  :isError false})))
        eng (fresh-engine {:clients {"bench" rig} :timeout-ms 300})
        _ (a-server! eng {:name "bench"
                          :powers [{:power "bench.read"
                                    :tools ["read" "test_result"]
                                    :why false}]})]
    (try
      (let [out (servers/call! eng "bench__test_result" {})]
        (is (false? (:isError out))
            "the rig's wait is longer than the row's bound, and it answers"))
      (is (= :live (:state (servers/row-by-name eng "bench")))
          "a wait is not a death: the row is not marked dark")
      (let [e (refused #(servers/call! eng "bench__read" {}))]
        (is (p/problem? e))
        (is (= 502 (:status (ex-data e)))
            "every other tool of the row keeps the row's bound"))
      (finally
        (.countDown latch)))))

;; ── a call that waits on an http row does not hold up the next ──────

(deftest a-waiting-call-does-not-delay-a-second-call-to-the-same-http-row
  (let [tools [{:name "read" :description "Read a file."
                :inputSchema {:type "object" :properties {}}}
               {:name "test_result" :description "Read a run."
                :inputSchema {:type "object" :properties {}}}]
        waiting (CountDownLatch. 1)
        latch (CountDownLatch. 1)
        rig (fn [method params]
              (case method
                "tools/list" {:tools tools}
                "tools/call" (do (when (= "test_result" (:name params))
                                   (.countDown waiting)
                                   (.await latch))
                                 {:content [{:type "text" :text (:name params)}]
                                  :isError false})))
        eng (fresh-engine {:clients {"bench" rig} :timeout-ms 300})
        _ (a-server! eng {:name "bench"
                          :powers [{:power "bench.read"
                                    :tools ["read" "test_result"]
                                    :why false}]})
        slow (future (servers/call! eng "bench__test_result" {}))]
    (try
      (is (.await waiting 5 TimeUnit/SECONDS) "the slow call is on the wire")
      (let [out (deref (future (servers/call! eng "bench__read" {}))
                       5000 ::queued)]
        (is (not= ::queued out)
            "the second call answers while the first still waits")
        (is (false? (:isError out))))
      (.countDown latch)
      (is (false? (:isError (deref slow 5000 ::queued)))
          "and the first answers when its wait ends")
      (finally
        (.countDown latch)))))

;; ── the http client under two callers (ticket 08b88006) ─────────────

(defn- session-server!
  "An MCP server on loopback that answers `initialize` slowly, each with
  its own session id, and lands every method it was sent on `seen`.
  `boom` answers a JSON-RPC error and `down` answers HTTP 500.
  `sessionless?` leaves the session id off every answer.
  → [server url seen]."
  [& {:keys [sessionless?]}]
  (let [seen (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        answer! (fn [^HttpExchange ex status session body]
                  (when session
                    (.add (.getResponseHeaders ex) "mcp-session-id" session))
                  (if body
                    (let [bs (.getBytes ^String (wire/write-json body)
                                        StandardCharsets/UTF_8)]
                      (.add (.getResponseHeaders ex)
                            "Content-Type" "application/json")
                      (.sendResponseHeaders ex status (alength bs))
                      (with-open [o (.getResponseBody ex)] (.write o bs)))
                    (do (.sendResponseHeaders ex status -1) (.close ex))))]
    (.createContext
     server "/mcp"
     (reify HttpHandler
       (handle [_ ex]
         (let [msg (wire/read-json (slurp (.getRequestBody ex)))
               method (str (:method msg))
               n (count (filter #{"initialize"} (swap! seen conj method)))
               result #(hash-map :jsonrpc "2.0" :id (:id msg) :result %)]
           (case method
             "initialize" (do (Thread/sleep 300)
                              (answer! ex 200
                                       (when-not sessionless?
                                         (str "session-" n))
                                       (result {:capabilities {}})))
             "notifications/initialized" (answer! ex 202 nil nil)
             "tools/list" (answer! ex 200 nil (result {:tools []}))
             "boom" (answer! ex 200 nil
                             {:jsonrpc "2.0" :id (:id msg)
                              :error {:code -32000 :message "no"}})
             "down" (answer! ex 500 nil nil))))))
    (.start server)
    [server (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/mcp")
     seen]))

(deftest two-first-calls-on-one-http-client-make-one-initialize
  (let [[^HttpServer server url seen] (session-server!)
        c (client/http-client url {:timeout-ms 5000})
        go (CountDownLatch. 1)
        call! #(future (.await go) (c "tools/list" {}))
        a (call!)
        b (call!)]
    (try
      (.countDown go)
      (is (= [] (:tools (deref a 10000 ::late))) "the first caller answers")
      (is (= [] (:tools (deref b 10000 ::late))) "and so does the second")
      (is (= 1 (count (filter #{"initialize"} @seen)))
          "the second caller waited for the first handshake and reused it")
      (finally
        (client/close! c)
        (.stop server 0)))))

(deftest a-server-that-gives-no-session-id-is-initialized-once
  (let [[^HttpServer server url seen] (session-server! :sessionless? true)
        c (client/http-client url {:timeout-ms 5000})
        initializes #(count (filter #{"initialize"} @seen))]
    (try
      (is (= [] (:tools (c "tools/list" {}))) "the first call answers")
      (is (= [] (:tools (c "tools/list" {}))) "and so does the second")
      (is (= 1 (initializes))
          "the second call reused the handshake, with no session id to hold")
      (client/close! c)
      (is (= [] (:tools (c "tools/list" {}))))
      (is (= 2 (initializes)) "a closed client handshakes again")
      (finally
        (client/close! c)
        (.stop server 0)))))

(deftest an-http-failure-is-judged-by-the-problem-it-threw
  (let [[^HttpServer server url _] (session-server!)
        c (client/http-client url {:timeout-ms 5000})]
    (try
      (let [wire-failure (refused #(c "down" {}))
            rpc-failure (refused #(c "boom" {}))]
        (is (p/problem? wire-failure))
        (is (p/problem? rpc-failure))
        (testing "each keeps its verdict whatever the client did since"
          (is (true? (client/dead? c wire-failure))
              "a wire failure is fatal after a later rpc error")
          (is (= [] (:tools (c "tools/list" {}))))
          (is (true? (client/dead? c wire-failure))
              "and after a later answer")
          (is (false? (client/dead? c rpc-failure))
              "a JSON-RPC error is not")
          (refused #(c "down" {}))
          (is (false? (client/dead? c rpc-failure))
              "nor after a later wire failure")))
      (finally
        (client/close! c)
        (.stop server 0)))))

;; ── the stdio client's death policy (R-3) ───────────────────────────

(deftest a-stdio-process-that-dies-is-restarted-and-dark-after-three-deaths
  (if-not (.canExecute (File. "/bin/sh"))
    (is true "the death policy test skipped: no /bin/sh on this machine")
    (let [c (client/stdio-client {:command "/bin/sh" :args ["-c" "exit 0"]
                                  :timeout-ms 5000 :window-ms 60000 :max-deaths 3})]
      (try
        (dotimes [n 3]
          (let [e (refused #(c "tools/list" {}))]
            (is (p/problem? e) (str "death " (inc n) " fails the call"))
            (is (= (= 2 n) (client/dead? c))
                (str "after " (inc n) " death(s), dead? is "
                     (if (= 2 n) "true" "false")))))
        (finally (client/close! c))))))

;; ── acceptance 9: a stdio row started with the bench's own command ──

(def ^:private bench-dir "/home/user/waymark-bench")

(defn- python3? []
  (try
    (let [p (.start (ProcessBuilder. ["python3" "--version"]))]
      (and (.waitFor p 10 TimeUnit/SECONDS) (zero? (.exitValue p))))
    (catch Exception _ false)))

(deftest a-stdio-row-started-with-the-bench-discovers-eight-tools
  (if-not (and (python3?) (.isDirectory (File. bench-dir)))
    ;; a skip is an ASSERTION, not a print: a deftest that runs
    ;; without one is a failure to kaocha, and a green machine with no
    ;; python3 must not read as a red suite
    (is true "acceptance 9 skipped: python3 or the bench checkout is absent")
    (let [dir (str (Files/createTempDirectory "waymark-bench-test"
                                              (make-array FileAttribute 0)))
          cfg (str dir "/bench.json")
          _ (spit cfg (wire/write-json
                       {:data_dir (str dir "/data")
                        :repos {:waymark {:clone_url "https://github.com/ckopsa/waymark"
                                          :default_branch "main"}}}))
          eng (fresh-engine {})
          row (a-server! eng {:name "bench"
                              :transport "stdio"
                              :url nil
                              :command "python3"
                              ;; the bench's own module, on the path
                              ;; from anywhere: `python3 -m bench` run
                              ;; through runpy with the checkout ahead
                              :args ["-c"
                                     (str "import sys, runpy; sys.path.insert(0, "
                                          (pr-str bench-dir)
                                          "); runpy.run_module('bench', run_name='__main__')")
                                     "--stdio" "--config" cfg]
                              :powers [{:power "bench.read"
                                        :tools ["find" "read" "status"]
                                        :why false}]})]
      (try
        (is (= :live (:state row)) (str (get-in row [:data :last_error])))
        (is (every? (set (mapv :name (get-in row [:data :tools])))
                    ["discard" "edit" "find" "prepare" "pull" "read"
                     "status" "submit"])
            "the eight tools this engine knows, by name — a rig that
             offers more (the enrolment's three, waymark-fp62.6.3.7)
             is a rig this engine reads whole and asks for what it
             needs")
        (is (every? #(= "object" (get-in % [:input_schema :type]))
                    (get-in row [:data :tools]))
            "each with its input schema")
        (testing "a call rides the same process and answers the bench's own refusal"
          (let [out (servers/call! eng "bench__status" {:repo "nope" :branch "x"})]
            (is (true? (:isError out)))
            (is (= "repo" (get-in out [:structuredContent :result :refused])))))
        (finally
          (servers/drop-client! (:id row)))))))
