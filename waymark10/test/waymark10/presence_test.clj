(ns waymark10.presence-test
  "Presence acceptance: the ephemeral follow-me surface. Registry
  level first (no HTTP): two registries over one database — two
  processes — see each other's joins, moves and leaves; three missed
  heartbeats evict; a crashed peer's ghosts leave on the clock; a
  per-resource stream registration is presence with source \"stream\".
  Then the wire: both reporting doors over a started engine, the
  scoped stream's byte-level absences (a concealed presence is never
  named, not even once), and the never-started engine's 503.

  Needs the waymark10_presence_test database (its own, never the
  suite's):
    WAYMARK10_PRESENCE_DSN=jdbc:postgresql://localhost:5433/waymark10_presence_test?user=ckopsa"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [org.httpkit.server :as http]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.presence :as presence]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.io BufferedReader InputStream InputStreamReader)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(def ^:private dsn
  (or (System/getenv "WAYMARK10_PRESENCE_DSN")
      "jdbc:postgresql://localhost:5433/waymark10_presence_test?user=ckopsa"))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private widget
  (r/resource
   {:kind :pres_widget
    :plural "pres_widgets"
    :states [:idle :spun]
    :initial :idle
    :terminal #{:spun}
    :summary "{data.name} · {state}"
    :schema [:map [:name [:string {:min 1 :max 40}]]]
    :actions {:spin {:from #{:idle} :to :spun
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "Spun is history."}}}}))

(def ^:private tables
  ["pres_widgets" "definitions" "members" "roles" "grants"
   "approval_requests" "attachments" "subscriptions" "jobs"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"
   "waymark10_cursors" "waymark10_job_leases" "waymark10_observations"])

(defn- fresh! []
  (let [st (pg/storage dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (finally (pg/close! st)))))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))
(def ^:private marco (t/principal {:id "marco" :display "Marco"}))
(def ^:private spy (t/principal {:id "spy" :type :agent :display "Spy"}))

(defn- next-frame
  "Consume the subscription until pred matches (the frame) or the
  timeout passes (nil). Skipped frames are gone — assertions read the
  stream in its own order."
  ([sub pred] (next-frame sub pred 8000))
  ([sub pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [remaining (- deadline (System/currentTimeMillis))]
         (when (pos? remaining)
           (let [f (presence/take-frame sub remaining)]
             (cond
               (nil? f) nil
               (keyword? f) nil
               (pred f) f
               :else (recur)))))))))

;; ── registry level: two processes, one truth ────────────────────────

(deftest presence-crosses-processes-and-evicts
  (fresh!)
  (let [st-a (pg/storage dsn)
        st-b (pg/storage dsn)]
    (try
      (let [eng-a (engine/engine {:storage st-a :resources [widget]})
            eng-b (engine/engine {:storage st-b :resources [widget]})
            reg-a (presence/start! eng-a {:hb-ms 200})
            reg-b (presence/start! eng-b {:hb-ms 200})
            sub-b (presence/subscribe reg-b nil)]
        (try
          (testing "a heartbeat on process A joins on process B"
            (presence/report! reg-a elena "/api/pres_widgets/w1")
            (let [f (next-frame sub-b #(= "join" (:event %)))]
              (is (some? f))
              (is (= "elena" (get-in f [:principal :id])))
              (is (= "Elena" (get-in f [:principal :display])))
              (is (= "/api/pres_widgets/w1" (:self f)))
              (is (= "heartbeat" (:source f)))))

          (testing "a moved gaze is a move frame, not a rejoin"
            (presence/report! reg-a elena "/api/pres_widgets/w2")
            (let [f (next-frame sub-b #(= "move" (:event %)))]
              (is (some? f))
              (is (= "elena" (get-in f [:principal :id])))
              (is (= "/api/pres_widgets/w2" (:self f)))))

          (testing "three missed heartbeats evict; the leave crosses too"
            (let [f (next-frame sub-b #(= "leave" (:event %)))]
              (is (some? f))
              (is (= "elena" (get-in f [:principal :id])))))

          (testing "a per-resource stream registration IS presence
                    (source \"stream\"), dropped on disconnect"
            (presence/stream-open! reg-a marco "/api/pres_widgets/w1")
            (let [f (next-frame sub-b #(and (= "join" (:event %))
                                            (= "marco" (get-in % [:principal :id]))))]
              (is (some? f))
              (is (= "stream" (:source f))))
            (presence/stream-closed! reg-a marco "/api/pres_widgets/w1")
            (is (some? (next-frame sub-b #(and (= "leave" (:event %))
                                               (= "marco" (get-in % [:principal :id])))))
                "the disconnect drops without waiting for a TTL"))

          (testing "a crashed peer's ghosts leave on the clock"
            (presence/report! reg-a elena "/api/pres_widgets/w1")
            (is (some? (next-frame sub-b #(and (= "join" (:event %))
                                               (= "elena" (get-in % [:principal :id]))))))
            (presence/stop! reg-a)          ; no clean drop — a crash
            (is (some? (next-frame sub-b #(and (= "leave" (:event %))
                                               (= "elena" (get-in % [:principal :id])))))
                "process B evicts the silent origin's entries itself"))
          (finally
            (presence/stop! reg-a)
            (presence/stop! reg-b))))
      (finally
        (pg/close! st-a)
        (pg/close! st-b)))))

;; ── the wire: both doors, the concealed stream, the 503 ────────────

(defn- sse-lines
  "Open one SSE GET and collect its raw lines into an atom —
  byte-level truth for the concealment assertions."
  [port path headers]
  (let [client (HttpClient/newHttpClient)
        req (let [b (HttpRequest/newBuilder
                     (URI. (str "http://127.0.0.1:" port path)))]
              (doseq [[k v] (assoc headers "Accept" "text/event-stream")]
                (.header b k v))
              (.build b))
        resp (.send client req (HttpResponse$BodyHandlers/ofInputStream))
        rdr (BufferedReader.
             (InputStreamReader. ^InputStream (.body resp)))
        lines (atom [])
        reader (future
                 (try
                   (loop []
                     (when-some [l (.readLine rdr)]
                       (swap! lines conj l)
                       (recur)))
                   (catch Exception _ nil)))]
    {:resp resp :lines lines :reader reader
     :body (.body resp)}))

(defn- socket-sse
  "A raw-socket SSE GET — closing the socket is a REAL disconnect
  (FIN), which the HttpClient body-close does not promise."
  ^java.net.Socket [port path headers]
  (let [sock (java.net.Socket. "127.0.0.1" (int port))
        out (.getOutputStream sock)]
    (.write out (.getBytes
                 (str "GET " path " HTTP/1.1\r\n"
                      "Host: 127.0.0.1\r\n"
                      (apply str (map (fn [[k v]] (str k ": " v "\r\n")) headers))
                      "Accept: text/event-stream\r\n\r\n")))
    (.flush out)
    ;; read one byte so the request is known-served before we act on it
    (.read (.getInputStream sock))
    sock))

(defn- await-line [lines pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (some #(when (pred %) %) @lines)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 100)
            (recur))))))

(deftest presence-on-the-wire
  (fresh!)
  (let [st (pg/storage dsn)]
    (try
      (let [eng (engine/engine {:storage st :resources [widget]
                                :presence-heartbeat-ms 300
                                :sse-heartbeat-ms 500
                                :events-poll-ms 200})
            server (engine/start! eng 0)
            port (http/server-port server)
            h (engine/handler eng)]
        (try
          (let [w1 (get-in (inv/create! eng :pres_widget {:name "granted"}
                                        {:principal elena})
                           [:row :id])
                w2 (get-in (inv/create! eng :pres_widget {:name "concealed"}
                                        {:principal elena})
                           [:row :id])
                beat! (fn [pid self]
                        (h {:request-method :post
                            :uri "/api/-/presence"
                            :headers {"x-waymark-principal" pid}
                            :body (wire/write-json {:self self})}))
                watcher (sse-lines port "/api/-/presence"
                                   {"x-waymark-principal" "watcher"})]

            (testing "the explicit door: POST heartbeat → 204 → a join
                      frame with source heartbeat on the stream"
              (is (= 204 (:status (beat! "elena" (str "/api/pres_widgets/" w1)))))
              (let [l (await-line (:lines watcher)
                                  #(and (str/starts-with? % "data:")
                                        (str/includes? % "\"elena\""))
                                  10000)]
                (is (some? l))
                (when l
                  (let [f (wire/read-json (str/trim (subs l 5)))]
                    (is (contains? #{"join" "snapshot"} (:event f)))
                    (is (str/includes? l "heartbeat"))))))

            (testing "the implicit door: a per-resource SSE subscription
                      is presence, source stream; disconnect drops it"
              (let [stream (socket-sse port (str "/api/pres_widgets/" w1 "/-/events")
                                       {"x-waymark-principal" "marco"})]
                (is (some? (await-line (:lines watcher)
                                       #(and (str/includes? % "\"marco\"")
                                             (str/includes? % "\"stream\"")
                                             (str/includes? % "\"join\""))
                                       10000))
                    "the stream's open registers marco")
                (.close stream)
                (is (some? (await-line (:lines watcher)
                                       #(and (str/includes? % "\"marco\"")
                                             (str/includes? % "\"leave\""))
                                       10000))
                    "the disconnect is the leave")))

            (testing "refusals: an anonymous heartbeat and a malformed
                      self both answer 422"
              (is (= 422 (:status (h {:request-method :post
                                      :uri "/api/-/presence"
                                      :headers {}
                                      :body (wire/write-json
                                             {:self (str "/api/pres_widgets/" w1)})}))))
              (is (= 422 (:status (beat! "elena" "not-an-href")))))

            (testing "concealment: a scoped stream never names an
                      ungranted self — byte-level absence"
              ;; the grant: spy sees w1 and nothing else
              (let [gid (get-in (inv/create!
                                 eng :grant
                                 {:audience "spy"
                                  :scope [{:kind "pres_widget"
                                           :ids [w1] :actions []}]}
                                 {:principal elena})
                                [:row :id])]
                (inv/invoke! eng :grant gid :accept nil {:principal spy})
                ;; presences before the scoped stream opens (the
                ;; snapshot path) …
                (beat! "elena" (str "/api/pres_widgets/" w1))
                (beat! "quinn" (str "/api/pres_widgets/" w2))
                (Thread/sleep 300)
                (let [scoped (sse-lines port "/api/-/presence"
                                        {"x-waymark-principal" "spy"
                                         "x-waymark-grant" gid})]
                  (is (some? (await-line (:lines scoped)
                                         #(str/includes? % "\"elena\"")
                                         10000))
                      "the granted self's presence is on the scoped stream")
                  ;; … and after it opened (the live path)
                  (beat! "nadia" (str "/api/pres_widgets/" w2))
                  (Thread/sleep 1500)
                  (let [bytes' (str/join "\n" @(:lines scoped))]
                    (is (str/includes? bytes' w1))
                    (is (not (str/includes? bytes' "quinn"))
                        "a snapshot presence on an ungranted self is absent")
                    (is (not (str/includes? bytes' "nadia"))
                        "a live presence on an ungranted self is absent")
                    (is (not (str/includes? bytes' w2))
                        "the concealed row's id never crosses the wire"))
                  (.close ^InputStream (:body scoped))
                  (future-cancel (:reader scoped)))))

            (testing "the scoped principal's own reporting is accepted"
              (is (= 204 (:status (h {:request-method :post
                                      :uri "/api/-/presence"
                                      :headers {"x-waymark-principal" "spy"}
                                      :body (wire/write-json
                                             {:self (str "/api/pres_widgets/" w1)})})))))

            (.close ^InputStream (:body watcher))
            (future-cancel (:reader watcher)))
          (finally (engine/stop! eng server))))
      (finally (pg/close! st)))))

;; ── the read door: a grant-scoped GET IS presence ───────────────────

(deftest grant-scoped-reads-mark-presence
  (fresh!)
  (let [st (pg/storage dsn)]
    (try
      (let [eng (engine/engine {:storage st :resources [widget]
                                :presence-heartbeat-ms 300
                                :sse-heartbeat-ms 500
                                :events-poll-ms 200})
            server (engine/start! eng 0)
            port (http/server-port server)
            h (engine/handler eng)
            reg (:presence @(:runtime eng))]
        (try
          (let [w1 (get-in (inv/create! eng :pres_widget {:name "granted"}
                                        {:principal elena})
                           [:row :id])
                gid (get-in (inv/create!
                             eng :grant
                             {:audience "spy"
                              :scope [{:kind "pres_widget"
                                       :ids [w1] :actions []}]}
                             {:principal elena})
                            [:row :id])
                _ (inv/invoke! eng :grant gid :accept nil {:principal spy})
                get! (fn [path headers]
                       (h {:request-method :get :uri path :headers headers}))
                scoped {"x-waymark-principal" "spy"
                        "x-waymark-actor-type" "agent"
                        "x-waymark-grant" gid}
                watcher (sse-lines port "/api/-/presence"
                                   {"x-waymark-principal" "watcher"})]

            (testing "a grant-scoped row GET marks gaze, source read"
              (is (= 200 (:status (get! (str "/api/pres_widgets/" w1) scoped))))
              (is (some? (await-line (:lines watcher)
                                     #(and (str/includes? % "\"spy\"")
                                           (str/includes? % "\"read\"")
                                           (str/includes? % w1))
                                     10000))
                  "the read itself is the join frame — no second request"))

            (testing "a grant-scoped collection GET marks the collection"
              (is (= 200 (:status (get! "/api/pres_widgets" scoped))))
              (is (some? (await-line (:lines watcher)
                                     #(and (str/includes? % "\"spy\"")
                                           (str/includes? % "\"/api/pres_widgets\""))
                                     10000))
                  "a different self within the throttle window still reports"))

            (testing "an unscoped read stays invisible — no grant, no gaze"
              (is (= 200 (:status (get! (str "/api/pres_widgets/" w1)
                                        {"x-waymark-principal" "elena"}))))
              (Thread/sleep 700)
              (is (nil? (get @(:local reg) "elena"))
                  "a human's casual GET paints nothing"))

            (testing "a refused read marks nothing — probing paints no gaze"
              (is (= 404 (:status (get! "/api/pres_widgets/nope" scoped))))
              (is (not= "/api/pres_widgets/nope"
                        (get-in @(:local reg) ["spy" :entry :self]))))

            (testing "same-self re-reads throttle: the entry keeps its stamp"
              (presence/read! reg marco "/api/pres_widgets/w9")
              (let [at1 (get-in @(:local reg) ["marco" :entry :at-ms])]
                (presence/read! reg marco "/api/pres_widgets/w9")
                (is (= at1 (get-in @(:local reg) ["marco" :entry :at-ms])))))

            (testing "the read door never throws: anonymous marks nothing"
              (is (nil? (presence/read! reg t/anonymous "/api/pres_widgets/w9")))
              (is (nil? (get @(:local reg) (:id t/anonymous)))))

            (testing "a full URL where an href was meant: the origin
                      strips, on both doors"
              (is (= 204 (:status
                          (h {:request-method :post
                              :uri "/api/-/presence"
                              :headers {"x-waymark-principal" "elena"}
                              :body (wire/write-json
                                     {:self (str "http://127.0.0.1:" port
                                                 "/api/pres_widgets/" w1)})}))))
              (is (= (str "/api/pres_widgets/" w1)
                     (get-in @(:local reg) ["elena" :entry :self]))
                  "the entry holds the path, not the URL")
              (presence/read! reg marco
                              (str "https://example.test/api/pres_widgets/" w1))
              (is (= (str "/api/pres_widgets/" w1)
                     (get-in @(:local reg) ["marco" :entry :self]))))

            (.close ^InputStream (:body watcher))
            (future-cancel (:reader watcher)))
          (finally (engine/stop! eng server))))
      (finally (pg/close! st)))))

;; ── engines without start! answer 503, the SSE discipline ──────────

(deftest presence-without-start-is-503
  (fresh!)
  (let [st (pg/storage dsn)]
    (try
      (let [eng (engine/engine {:storage st :resources [widget]})
            h (engine/handler eng)]
        (doseq [req [{:request-method :get :uri "/api/-/presence" :headers {}}
                     {:request-method :post :uri "/api/-/presence"
                      :headers {"x-waymark-principal" "elena"}
                      :body (wire/write-json {:self "/api/pres_widgets/x"})}]]
          (let [resp (h req)]
            (is (= 503 (:status resp)))
            (is (= "application/problem+json"
                   (get-in resp [:headers "Content-Type"]))))))
      (finally (pg/close! st)))))
