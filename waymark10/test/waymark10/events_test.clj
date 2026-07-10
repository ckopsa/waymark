(ns waymark10.events-test
  "Phase-6 acceptance, part 2: the events surface. Dispatcher-level
  exactly-once + replay (no HTTP), one SSE-over-HTTP test on a started
  engine (generous timeouts — timing-tolerant by design), and the 503
  answer of a never-started engine. Suite-local kind; real Postgres."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [org.httpkit.server :as http]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.events :as events]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.io BufferedReader InputStream InputStreamReader)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["mnt_gizmos" "definitions" "waymark10_transitions" "waymark10_idempotency"])

(defn- fresh! []
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st)))))

(defn- with-eng [opts f]
  (let [st (pg/storage db/dsn)
        gizmo (r/resource
               {:kind :mnt_gizmo
                :plural "mnt_gizmos"
                :states [:idle :spun]
                :initial :idle
                :terminal #{:spun}
                :summary "{data.name} · {state}"
                :schema [:map [:name [:string {:min 1 :max 40}]]]
                :actions {:spin {:from #{:idle} :to :spun
                                 :safety {:idempotent true :reversible false
                                          :confirm false
                                          :one-way "Spun is history."}}}})]
    (try
      (f (engine/engine (merge {:storage st :resources [gizmo]} opts)))
      (finally (pg/close! st)))))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(defn- reload [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx] (store/load-row (:storage eng) tx kind id {}))))

;; ── 3. dispatcher-level: exactly once, after commit, replayable ─────

(deftest events-exactly-once-after-commit
  (fresh!)
  (with-eng {}
    (fn [eng]
      (let [d (events/dispatcher eng {:poll-ms 200})]
        (try
          (let [created (inv/create! eng :mnt_gizmo {:name "g1"}
                                     {:principal elena})
                gid (get-in created [:row :id])
                create-id (get-in created [:transition :id])
                ;; live from the create's horizon — deterministic even
                ;; when the dispatcher has not yet polled the create
                sub (events/subscribe d {:kinds #{:mnt_gizmo}
                                         :since create-id})
                spun (inv/invoke! eng :mnt_gizmo gid :spin nil
                                  {:principal elena})
                spin-id (get-in spun [:transition :id])
                evt (events/take-event sub 10000)]
            (testing "exactly one event arrives"
              (is (some? evt))
              (is (= spin-id (:id evt)))
              (is (= :spin (:action evt)))
              (is (= gid (:resource-id evt)))
              (is (nil? (events/take-event sub 1500)) "…and only one"))
            (testing "arriving only after commit: the subscriber never
                      observes a version storage doesn't have"
              (let [row (reload eng :mnt_gizmo gid)]
                (is (= (keyword (name (:to-state evt))) (:state row)))
                (is (>= (:version row) 2))))
            (testing "replay from an old Last-Event-ID yields the missed
                      ids in order, no duplicates"
              (let [sub2 (events/subscribe d {:kinds #{:mnt_gizmo} :since 0})
                    got [(events/take-event sub2 5000)
                         (events/take-event sub2 5000)]]
                (is (= [create-id spin-id] (mapv :id got)))
                (is (= [:create :spin] (mapv :action got)))
                (is (nil? (events/take-event sub2 1000)) "no duplicates")))
            (testing "a resource-scoped subscription filters"
              (let [sub3 (events/subscribe d {:resource [:mnt_gizmo gid]
                                              :since 0})]
                (is (= [create-id spin-id]
                       [(:id (events/take-event sub3 5000))
                        (:id (events/take-event sub3 5000))])))))
          (finally (events/stop! d)))))))

;; ── 4. SSE over HTTP ────────────────────────────────────────────────

(defn- await-line
  "Poll the collected lines until one satisfies pred (or the timeout);
  returns the line or nil."
  [lines pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (some #(when (pred %) %) @lines)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 100)
            (recur))))))

(deftest sse-over-http
  (fresh!)
  (with-eng {:sse-heartbeat-ms 1000 :events-poll-ms 200}
    (fn [eng]
      (let [server (engine/start! eng 0)
            port (http/server-port server)]
        (try
          (let [{row :row} (inv/create! eng :mnt_gizmo {:name "g2"}
                                        {:principal elena})
                gid (:id row)
                client (HttpClient/newHttpClient)
                req (-> (HttpRequest/newBuilder
                         (URI. (str "http://127.0.0.1:" port
                                    "/api/mnt_gizmos/" gid "/-/events")))
                        (.header "Accept" "text/event-stream")
                        (.header "Last-Event-ID" "0")
                        (.build))
                resp (.send client req (HttpResponse$BodyHandlers/ofInputStream))
                rdr (BufferedReader.
                     (InputStreamReader. ^InputStream (.body resp)))
                lines (atom [])
                reader (future
                         (loop []
                           (when-some [l (.readLine rdr)]
                             (swap! lines conj l)
                             (recur))))]
            (is (= 200 (.statusCode resp)))
            (is (str/starts-with?
                 (.orElse (.firstValue (.headers resp) "content-type") "")
                 "text/event-stream"))
            (testing "Last-Event-ID replay delivers the create frame"
              (is (some? (await-line lines #(= % "event: transition") 15000)))
              (is (some? (await-line lines
                                     #(and (str/starts-with? % "data:")
                                           (str/includes? % "\"create\""))
                                     15000))))
            (testing "heartbeat comment frames arrive"
              (is (some? (await-line lines #(str/starts-with? % ": hb") 15000))))
            (testing "a transition frame arrives post-commit"
              (inv/invoke! eng :mnt_gizmo gid :spin nil {:principal elena})
              (let [data-line (await-line lines
                                          #(and (str/starts-with? % "data:")
                                                (str/includes? % "\"spin\""))
                                          20000)]
                (is (some? data-line))
                (when data-line
                  (let [payload (wire/read-json
                                 (str/trim (subs data-line 5)))]
                    (is (= "mnt_gizmo" (:kind payload)))
                    (is (= (str "/api/mnt_gizmos/" gid) (:self payload)))
                    (is (= "idle" (:from payload)))
                    (is (= "spun" (:to payload)))
                    (is (= "elena" (get-in payload [:actor :id])))
                    (is (contains? payload :law_revision))
                    (is (string? (:summary payload)))))))
            (future-cancel reader))
          (finally (engine/stop! eng server)))))))

;; ── engines without start! answer 503 ───────────────────────────────

(deftest sse-without-start-is-503
  (fresh!)
  (with-eng {}
    (fn [eng]
      (let [h (engine/handler eng)
            resp (h {:request-method :get
                     :uri "/api/-/events"
                     :headers {}})]
        (is (= 503 (:status resp)))
        (is (= "application/problem+json"
               (get-in resp [:headers "Content-Type"])))))))
