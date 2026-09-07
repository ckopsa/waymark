(ns waymark10.mcp-notice-test
  "The MCP door's notice stream: a grant that widens or narrows
  mid-session reaches the connector WITHOUT a reconnect.

  The failure this pins, observed live 2026-09-07: an agent filed an
  approval_request for `messages.read` from inside a session, the
  person approved it within the minute, the grant widened at once —
  and the connector's tool list stayed the seven fixed tools until it
  reconnected, because `initialize` had said the list never changes
  and the transport had no stream to say otherwise on.

  What holds here, over a STARTED engine (http-kit, the transition
  dispatcher running) and the real door:

  • initialize declares tools.listChanged true;
  • GET /api/-/mcp with Accept: text/event-stream is a 200 SSE stream
    for a named principal; without the accept it is still the 405;
  • an ask approved for the streaming principal pushes
    notifications/tools/list_changed on that stream, and a fresh
    tools/list wearing the minted grant carries the Gate tools the
    new capability admits — Gate's own tools, through the fake rpc
    seam gate_proxy_test uses;
  • revoking the grant pushes the notice again, and the Gate tail is
    gone from the next tools/list;
  • another principal's grant moving pushes nothing to this stream —
    a principal is told about ITS leash and nobody else's;
  • on a never-started engine the stream GET answers the events
    doors' own 503.

  Real Postgres (the dispatcher's LISTEN + poll), generous timeouts —
  timing-tolerant by design, the events_test posture."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [org.httpkit.server :as http]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire])
  (:import (java.io BufferedReader InputStream InputStreamReader)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["capabilities" "members" "roles" "grants" "approval_requests"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors" "waymark10_job_leases"])

(defn- fresh! []
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st)))))

(def ^:private messa-tools
  "What the fake Gate serves: the three phone-text tools, all of
  which gate_proxy's map carries under messages.read."
  [{:name "messa__threads"
    :description "List recent text threads."
    :inputSchema {:type "object"
                  :properties {:limit {:type "integer"}
                               :__why {:type "string"}}}}
   {:name "messa__read_messages"
    :description "Read one thread's messages."
    :inputSchema {:type "object"
                  :properties {:thread {:type "string"}
                               :__why {:type "string"}}
                  :required ["thread"]}}
   {:name "messa__reset"
    :description "Reset the rig."
    :inputSchema {:type "object" :properties {}}}])

(defn- fake-gate [method params]
  (case method
    "tools/list" {:tools messa-tools}
    "tools/call" {:content [{:type "text" :text (str "gate answered " (:name params))}]
                  :isError false}))

(defn- with-eng [opts f]
  (let [st (pg/storage db/dsn)]
    (try
      (f (engine/engine (merge {:storage st
                                :resources [caps/capability]
                                :gate {:rpc fake-gate}}
                               opts)))
      (finally (pg/close! st)))))

;; ── request sugar over a started server ─────────────────────────────

(def ^:private mom {"x-waymark-principal" "mom"})
(def ^:private planner {"x-waymark-principal" "planner-1"
                        "x-waymark-actor-type" "agent"})
(def ^:private other {"x-waymark-principal" "other-agent"
                      "x-waymark-actor-type" "agent"})

(def ^:private client (delay (HttpClient/newHttpClient)))

(defn- post!
  "One JSON POST at the started server; answers {:status :doc}."
  [port uri headers body]
  (let [b (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port uri)))
        _ (doseq [[k v] (assoc headers "content-type" "application/json")]
            (.header b k v))
        req (.build (.POST b (HttpRequest$BodyPublishers/ofString
                              (if (string? body) body (wire/write-json body)))))
        resp (.send ^HttpClient @client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :doc (let [s (.body resp)] (when-not (str/blank? s) (wire/read-json s)))}))

(defn- rpc! [port headers method params]
  (post! port "/api/-/mcp" headers
         (cond-> {:jsonrpc "2.0" :id 1 :method method}
           params (assoc :params params))))

(defn- get-status [port uri headers]
  (let [b (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port uri)))
        _ (doseq [[k v] headers] (.header b k v))
        resp (.send ^HttpClient @client (.build (.GET b))
                    (HttpResponse$BodyHandlers/ofString))]
    (.statusCode resp)))

(defn- open-stream
  "GET the notice stream as `headers`; answers {:status :content-type
  :lines} where :lines is an atom the reader thread fills."
  [port headers]
  (let [b (HttpRequest/newBuilder (URI. (str "http://127.0.0.1:" port "/api/-/mcp")))
        _ (doseq [[k v] (assoc headers "accept" "text/event-stream")]
            (.header b k v))
        resp (.send ^HttpClient @client (.build (.GET b))
                    (HttpResponse$BodyHandlers/ofInputStream))
        rdr (BufferedReader. (InputStreamReader. ^InputStream (.body resp)))
        lines (atom [])]
    (future
      (loop []
        (when-some [l (.readLine rdr)]
          (swap! lines conj l)
          (recur))))
    {:status (.statusCode resp)
     :content-type (.orElse (.firstValue (.headers resp) "content-type") "")
     :lines lines}))

(defn- await-count
  "Poll until at least n lines satisfy pred (or the timeout); answers
  the count seen."
  [lines pred n timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [c (count (filter pred @lines))]
        (if (or (>= c n) (>= (System/currentTimeMillis) deadline))
          c
          (do (Thread/sleep 100) (recur)))))))

(defn- notice-line? [l]
  (and (str/starts-with? l "data:")
       (str/includes? l "notifications/tools/list_changed")))

(defn- id-of [self] (last (str/split (str self) #"/")))

(def ^:private the-fixed (mapv :name (mcp/listing)))

(defn- tool-names [port headers]
  (let [r (rpc! port headers "tools/list" nil)]
    (is (= 200 (:status r)) (pr-str (:doc r)))
    (mapv :name (get-in r [:doc :result :tools]))))

;; ── the acceptance ──────────────────────────────────────────────────

(deftest an-approval-tapped-mid-session-reaches-the-stream
  (fresh!)
  (with-eng {:sse-heartbeat-ms 1000 :events-poll-ms 200}
    (fn [eng]
      (let [server (engine/start! eng 0)
            port (http/server-port server)]
        (try
          (testing "initialize says the list can change"
            (let [r (rpc! port planner "initialize"
                          {:protocolVersion mcp/protocol-version
                           :capabilities {} :clientInfo {:name "t" :version "0"}})]
              (is (= 200 (:status r)))
              (is (true? (get-in r [:doc :result :capabilities :tools :listChanged])))))

          (testing "the vocabulary: messages.read exists as a capability row"
            (let [r (post! port "/api/capabilities" mom
                           {:token "messages.read"
                            :description "Read the phone's texts through Gate."
                            :enforced_by "this engine's own gate door"})]
              (is (= 201 (:status r)) (pr-str (:doc r)))))

          (testing "a GET without the SSE accept is still the 405"
            (is (= 405 (get-status port "/api/-/mcp" planner))))

          (testing "and anonymous, the stream GET is the 401 before anything"
            (is (= 401 (get-status port "/api/-/mcp" {"accept" "text/event-stream"}))))

          (let [stream (open-stream port planner)
                lines (:lines stream)
                bystander (open-stream port other)]
            (testing "the stream opens for a named principal"
              (is (= 200 (:status stream)))
              (is (str/starts-with? (:content-type stream) "text/event-stream"))
              (is (= 200 (:status bystander))))

            (testing "before any ask, tools/list is exactly the fixed tools"
              (is (= the-fixed (tool-names port planner))))

            (let [ask (post! port "/api/approval_requests" planner
                             {:task "Read the phone's texts for tomorrow's plan."
                              :scope [{:kind "messages.read" :actions []}]})
                  _ (is (= 201 (:status ask)) (pr-str (:doc ask)))
                  rid (id-of (get-in ask [:doc :self]))
                  approved (post! port (str "/api/approval_requests/" rid "/-/approve")
                                  mom nil)
                  gid (get-in approved [:doc :data :grant_id])]
              (is (= 200 (:status approved)) (pr-str (:doc approved)))
              (is (some? gid))

              (testing "the tap pushes notifications/tools/list_changed"
                (is (>= (await-count lines notice-line? 1 20000) 1)
                    (pr-str @lines)))

              (testing "and a fresh tools/list wearing the grant carries the Gate tail"
                (let [names (tool-names port (assoc planner "x-waymark-grant" gid))]
                  (is (= the-fixed (vec (take (count the-fixed) names))))
                  (is (= #{"messa__threads" "messa__read_messages" "messa__reset"}
                         (set (drop (count the-fixed) names))))))

              (testing "the bystander's stream heard nothing of it"
                (is (>= (await-count (:lines bystander) #(str/starts-with? % ": hb") 1 15000) 1)
                    "the bystander's stream is alive (a heartbeat arrived)")
                (is (= 0 (count (filter notice-line? @(:lines bystander))))))

              (let [seen (count (filter notice-line? @lines))
                    revoked (post! port (str "/api/grants/" gid "/-/revoke") mom nil)]
                (is (= 200 (:status revoked)) (pr-str (:doc revoked)))
                (testing "revoke pushes the notice again"
                  (is (>= (await-count lines notice-line? (inc seen) 20000) (inc seen))
                      (pr-str @lines)))
                (testing "and the Gate tail is gone"
                  (let [names (tool-names port (assoc planner "x-waymark-grant" gid))]
                    (is (not-any? #(str/starts-with? % "messa__") names)))))))
          (finally
            (engine/stop! eng server)))))))

(deftest a-never-started-engine-answers-503-to-the-stream
  (fresh!)
  (with-eng {}
    (fn [eng]
      (let [h (engine/handler eng)
            resp (h {:request-method :get :uri "/api/-/mcp"
                     :headers (assoc planner "accept" "text/event-stream")})]
        (is (= 503 (:status resp)))
        (is (= 405 (:status (h {:request-method :get :uri "/api/-/mcp"
                                :headers planner}))))))))
