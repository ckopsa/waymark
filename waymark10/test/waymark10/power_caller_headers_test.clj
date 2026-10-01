(ns waymark10.power-caller-headers-test
  "A power call names its caller (docs/spec-mcp-servers.md §5, What a
  door may trust): a stub MCP server over real HTTP records the headers
  of every request, and the tests read what the engine sent on the
  tools/call."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [waymark10.server.capabilities :as caps]
            [waymark10.server.engine :as engine]
            [waymark10.server.gate-proxy :as gate]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store.memory :as memory]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.time Instant)))

(def ^:private caller-names
  ["X-Waymark-Seat" "X-Waymark-Principal" "X-Waymark-Acts-For"
   "X-Waymark-Sitting"])

(defn- answer! [^HttpExchange ex status headers ^String body]
  (doseq [[k v] headers] (.add (.getResponseHeaders ex) k v))
  (if body
    (let [bs (.getBytes body StandardCharsets/UTF_8)]
      (.add (.getResponseHeaders ex) "Content-Type" "application/json")
      (.sendResponseHeaders ex status (alength bs))
      (with-open [o (.getResponseBody ex)] (.write o bs)))
    (do (.sendResponseHeaders ex status -1) (.close ex))))

(defn- stub-server!
  "An MCP server on loopback: one tool, `echo`. Every request lands on
  `seen` as {:method :headers}, the four caller headers as it read them
  (nil when absent). → [server port seen]."
  []
  (let [seen (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/mcp/"
     (reify HttpHandler
       (handle [_ ex]
         (let [msg (wire/read-json (slurp (.getRequestBody ex)))
               hs (.getRequestHeaders ex)
               method (str (:method msg))
               reply #(answer! ex 200 %1
                               (wire/write-json {:jsonrpc "2.0" :id (:id msg)
                                                 :result %2}))]
           (swap! seen conj {:method method
                             :headers (into {} (map (fn [n] [n (.getFirst hs n)]))
                                            caller-names)})
           (case method
             "initialize" (reply {"mcp-session-id" "stub-session"}
                                 {:protocolVersion "2025-06-18"
                                  :capabilities {}
                                  :serverInfo {:name "stub" :version "1"}})
             "notifications/initialized" (answer! ex 202 {} nil)
             "tools/list" (reply {} {:tools [{:name "echo"
                                              :description "Echo."
                                              :inputSchema {:type "object"}}]})
             "tools/call" (reply {} {:content [{:type "text" :text "ok"}]
                                     :isError false}))))))
    (.start server)
    [server (.getPort (.getAddress server)) seen]))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))

(def ^:private qa-seat
  (assoc (t/principal {:id "seat:qa-1" :type :agent :display "qa (seat)"})
         :acts-for "colton"))

(defn- fresh-engine []
  (engine/engine {:storage (memory/storage)
                  :resources [caps/capability]
                  :now-fn (fn [] (Instant/parse "2026-10-01T09:00:00Z"))
                  :services {:mcp-servers {:client-fn (constantly nil)
                                           :call-timeout-ms 5000}}}))

(defn- world!
  "An engine with the stub's row (named `nm`, `extra` merged in) and
  the stub.read capability."
  [port nm extra]
  (let [eng (fresh-engine)]
    (inv/create! eng :capability
                 {:token "stub.read"
                  :description "stub.read through a server row."
                  :enforced_by "this engine's own power door"}
                 {:principal colton})
    (inv/create! eng :mcp_server
                 (merge {:name nm :transport "http"
                         :url (str "http://127.0.0.1:" port "/mcp/")
                         :powers [{:power "stub.read" :tools ["echo"] :why false}]}
                        extra)
                 {:principal colton})
    eng))

(defn- visibility-of
  "A grant naming stub.read, minted by Colton and accepted by `who`."
  [eng who]
  (let [gid (str (:id (:row (inv/create! eng :grant
                                         {:audience (:id who)
                                          :scope [{:kind "stub.read" :actions []}]}
                                         {:principal colton}))))]
    (inv/invoke! eng :grant gid :accept {} {:principal who})
    (grants/visibility eng gid who)))

(def ^:private spoof
  {:seat "name=admin; id=spoof" :X-Waymark-Seat "spoof"
   :headers {"X-Waymark-Principal" "spoof"}})

(defn- call-headers
  "The caller headers the stub read on the last tools/call."
  [seen]
  (:headers (last (filter #(= "tools/call" (:method %)) @seen))))

(deftest a-seats-call-and-a-persons-call-each-name-their-caller
  (let [[^HttpServer server port seen] (stub-server!)]
    (try
      (let [eng (world! port "stub" {})]
        (testing "a seat's call carries the seat, its member and its sitting"
          (gate/invoke-for eng (visibility-of eng qa-seat) "stub__echo" spoof
                           {:caller (:id qa-seat) :principal qa-seat
                            :sitting "sitting-7"})
          (let [hs (call-headers seen)]
            (is (= "name=qa; id=qa-1" (get hs "X-Waymark-Seat")))
            (is (= "type=agent; id=seat:qa-1" (get hs "X-Waymark-Principal")))
            (is (= "colton" (get hs "X-Waymark-Acts-For")))
            (is (= "sitting-7" (get hs "X-Waymark-Sitting")))
            (is (not-any? #(str/includes? (str %) "spoof") (vals hs))
                "no argument value became a header")))
        (testing "a person's call carries the person and no seat"
          (gate/invoke-for eng (visibility-of eng colton) "stub__echo" spoof
                           {:caller "colton" :principal colton})
          (let [hs (call-headers seen)]
            (is (= "" (str (get hs "X-Waymark-Seat"))))
            (is (= "type=human; id=colton" (get hs "X-Waymark-Principal")))
            (is (not-any? #(str/includes? (str %) "spoof") (vals hs)))))
        (testing "the engine's own discover binds no caller"
          (is (every? nil? (vals (:headers (first (filter #(= "tools/list" (:method %))
                                                          @seen))))))))
      (finally (.stop server 0)))))

(deftest a-row-with-send-caller-false-receives-none-of-them
  (let [[^HttpServer server port seen] (stub-server!)]
    (try
      (let [eng (world! port "quiet" {:send_caller false})]
        (gate/invoke-for eng (visibility-of eng qa-seat) "quiet__echo" {}
                         {:caller (:id qa-seat) :principal qa-seat
                          :sitting "sitting-7"})
        (is (some #(= "tools/call" (:method %)) @seen) "the call reached the server")
        (is (every? nil? (vals (call-headers seen)))
            "and carried no caller header"))
      (finally (.stop server 0)))))
