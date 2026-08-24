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

;; the private own-surface's own kind, borrowed by name (waymark-tti.3
;; L7): grants/private-kind? keys on the kind NAME, so a :letter
;; declared here wears the real privacy walls. The OWN-SURFACE half
;; is no longer borrowed at all — since waymark-442.6 it is declared,
;; here as in the app, and this fixture says the same two-party
;; sentence workqueue10's letter says: a row is yours as its
;; data.owner or its data.to, and nobody else's.
(def ^:private letter
  (r/resource
   {:kind :letter
    :plural "letters"
    :states [:waiting :opened]
    :initial :waiting
    :terminal #{}
    :allow-dead #{:opened}
    :summary "{data.title} · {state}"
    :own-surface {:by [:owner :to] :actions #{"create" "open"}}
    :schema [:map
             [:owner [:string {:min 1 :max 128}]]
             [:to [:string {:min 1 :max 128}]]
             [:title {:optional true} [:maybe [:string {:max 120}]]]
             [:body {:x-display {:widget "prose"}} [:string {:min 1 :max 400}]]]
    :filterable {:owner #{:eq} :to #{:eq} :state #{:eq}}
    :actions {:open {:from #{:waiting} :to :opened
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "Opened is landed."}}}}))

(def ^:private tables
  ["pres_widgets" "letters" "definitions" "members" "roles" "grants"
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

;; ── the curtain (waymark-tti.4): suppression on the wire ────────────

(deftest the-curtain-on-the-wire
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
          (let [w1 (get-in (inv/create! eng :pres_widget {:name "seen"}
                                        {:principal elena})
                           [:row :id])
                beat! (fn [pid self]
                        (h {:request-method :post
                            :uri "/api/-/presence"
                            :headers {"x-waymark-principal" pid}
                            :body (wire/write-json {:self self})}))
                act! (fn [pid action & [headers]]
                       (h {:request-method :post
                           :uri (str "/api/members/" pid "/-/" action)
                           :headers (merge {"x-waymark-principal" pid}
                                           headers)}))
                watcher (sse-lines port "/api/-/presence"
                                   {"x-waymark-principal" "watcher"})
                ;; lines past this mark are "since then" — the
                ;; byte-level absence assertions' window
                since (fn [w n] (str/join "\n" (drop n @(:lines w))))]

            (testing "draw over a LIVE entry: the leave lands within
                      one heartbeat, not a TTL"
              (is (= 204 (:status (beat! "elena" (str "/api/pres_widgets/" w1)))))
              (is (some? (await-line (:lines watcher)
                                     #(and (str/includes? % "\"elena\"")
                                           (str/includes? % "\"join\""))
                                     10000)))
              ;; elena's own hand, through the normal action path
              (is (= 200 (:status (act! "elena" "draw_curtain"))))
              (is (some? (await-line (:lines watcher)
                                     #(and (str/includes? % "\"elena\"")
                                           (str/includes? % "\"leave\""))
                                     10000))
                  "the sweep's fresh curtain read clears the board"))

            (testing "while curtained: the explicit door answers its
                      usual 204 but publishes NOTHING"
              (let [mark (count @(:lines watcher))]
                (is (= 204 (:status (beat! "elena" (str "/api/pres_widgets/" w1)))))
                (Thread/sleep 1500)
                (is (not (str/includes? (since watcher mark) "elena"))
                    "no frame for a curtained beat — byte-level absent")
                (is (nil? (get @(:local reg) "elena")))))

            (testing "while curtained: a row-SSE open publishes nothing"
              (let [mark (count @(:lines watcher))
                    stream (socket-sse port (str "/api/pres_widgets/" w1 "/-/events")
                                       {"x-waymark-principal" "elena"})]
                (Thread/sleep 1500)
                (is (not (str/includes? (since watcher mark) "elena"))
                    "the subscription opens; the presence never does")
                (.close stream)))

            (testing "while curtained: a grant-scoped read stamps
                      nothing (the third door)"
              ;; shade: a scoped agent who then draws its curtain.
              ;; The grant also names the member actions — a
              ;; grantless agent runs on the bootstrap surface
              ;; (waymark-rci), where the member kind is concealed,
              ;; so its self-service door rides its scope.
              (let [gid (get-in (inv/create!
                                 eng :grant
                                 {:audience "shade"
                                  :scope [{:kind "pres_widget"
                                           :ids [w1] :actions []}
                                          {:kind "member"
                                           :ids ["shade"]
                                           :actions ["draw_curtain"
                                                     "open_curtain"]}]}
                                 {:principal elena})
                                [:row :id])
                    shade {"x-waymark-principal" "shade"
                           "x-waymark-actor-type" "agent"}]
                (inv/invoke! eng :grant gid :accept nil
                             {:principal (t/principal {:id "shade" :type :agent})})
                (is (= 200 (:status (act! "shade" "draw_curtain"
                                          {"x-waymark-actor-type" "agent"
                                           "x-waymark-grant" gid}))))
                (let [mark (count @(:lines watcher))]
                  (is (= 200 (:status (h {:request-method :get
                                          :uri (str "/api/pres_widgets/" w1)
                                          :headers (assoc shade
                                                          "x-waymark-grant" gid)}))))
                  (Thread/sleep 1500)
                  (is (not (str/includes? (since watcher mark) "shade"))
                      "under a leash but behind the curtain: unwatchable")
                  (is (nil? (get @(:local reg) "shade"))))))

            (testing "a fresh stream's join snapshot omits curtained
                      pids — the race can never serve one"
              (let [fresh (sse-lines port "/api/-/presence"
                                     {"x-waymark-principal" "watcher2"})]
                (is (some? (await-line (:lines fresh)
                                       #(str/includes? % "snapshot")
                                       10000)))
                (let [bytes' (str/join "\n" @(:lines fresh))]
                  (is (not (str/includes? bytes' "elena")))
                  (is (not (str/includes? bytes' "shade"))))
                (.close ^InputStream (:body fresh))
                (future-cancel (:reader fresh))))

            (testing "another principal cannot open elena's curtain;
                      the wall answers, not the concealment"
              (is (contains? #{404 409 422}
                             (:status (h {:request-method :post
                                          :uri "/api/members/elena/-/open_curtain"
                                          :headers {"x-waymark-principal" "meddler"}})))))

            (testing "open_curtain: the next beat publishes again"
              (is (= 200 (:status (act! "elena" "open_curtain"))))
              ;; beat past the curtain cache's TTL (≤ hb-ms here)
              (let [deadline (+ (System/currentTimeMillis) 5000)]
                (loop []
                  (beat! "elena" (str "/api/pres_widgets/" w1))
                  (when (and (nil? (get @(:local reg) "elena"))
                             (< (System/currentTimeMillis) deadline))
                    (Thread/sleep 100)
                    (recur))))
              (is (some? (await-line (:lines watcher)
                                     #(and (str/includes? % "\"elena\"")
                                           (str/includes? % "\"join\""))
                                     10000))
                  "an opened curtain publishes on the next beat"))

            (.close ^InputStream (:body watcher))
            (future-cancel (:reader watcher)))
          (finally (engine/stop! eng server))))
      (finally (pg/close! st)))))

;; ── the widening (waymark-tti.4): collection frames for whole-kind
;;    grants — presence works both ways ─────────────────────────────

(deftest collection-frames-follow-whole-kind-sight
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
          (let [w1 (get-in (inv/create! eng :pres_widget {:name "w"}
                                        {:principal elena})
                           [:row :id])
                beat! (fn [pid self]
                        (h {:request-method :post
                            :uri "/api/-/presence"
                            :headers {"x-waymark-principal" pid}
                            :body (wire/write-json {:self self})}))
                grant! (fn [audience scope]
                         (let [gid (get-in (inv/create!
                                            eng :grant
                                            {:audience audience :scope scope}
                                            {:principal elena})
                                           [:row :id])]
                           (inv/invoke! eng :grant gid :accept nil
                                        {:principal (t/principal
                                                     {:id audience :type :agent})})
                           gid))
                ;; whole-kind sight: a scope entry naming the kind,
                ;; no ids, no filter
                whole (grant! "spy" [{:kind "pres_widget" :actions []}])
                ;; ids-narrowed sight of the same kind
                narrowed (grant! "ida" [{:kind "pres_widget"
                                         :ids [w1] :actions []}])]
            ;; three gazes: the granted collection, an ungranted
            ;; kind's collection, a door self — distinct pids so
            ;; byte-level absence is assertable by name
            (beat! "elena" "/api/pres_widgets")
            (beat! "quinn" "/api/members")
            (beat! "nadia" "/api/-/events")
            (Thread/sleep 300)

            (testing "whole-kind sight shows the collection frame;
                      everything else stays byte-level absent"
              (let [scoped (sse-lines port "/api/-/presence"
                                      {"x-waymark-principal" "spy"
                                       "x-waymark-grant" whole})]
                (is (some? (await-line (:lines scoped)
                                       #(and (str/includes? % "\"elena\"")
                                             (str/includes? % "\"/api/pres_widgets\""))
                                       10000))
                    "the collection-level self is visible under whole-kind sight")
                (Thread/sleep 1000)
                (let [bytes' (str/join "\n" @(:lines scoped))]
                  (is (not (str/includes? bytes' "quinn"))
                      "an ungranted kind's collection stays concealed")
                  (is (not (str/includes? bytes' "nadia"))
                      "door selves (/api/-/…) stay concealed"))
                (.close ^InputStream (:body scoped))
                (future-cancel (:reader scoped))))

            (testing "ids-narrowed sight of SOME rows is NOT sight of
                      the collection — no :row? sampling"
              (let [scoped (sse-lines port "/api/-/presence"
                                      {"x-waymark-principal" "ida"
                                       "x-waymark-grant" narrowed})]
                (is (some? (await-line (:lines scoped)
                                       #(str/includes? % "snapshot")
                                       10000)))
                (Thread/sleep 1000)
                (let [bytes' (str/join "\n" @(:lines scoped))]
                  (is (not (str/includes? bytes' "elena"))
                      "the collection frame is absent under narrowed sight")
                  (is (not (str/includes? bytes' "quinn")))
                  (is (not (str/includes? bytes' "nadia"))))
                (.close ^InputStream (:body scoped))
                (future-cancel (:reader scoped))))

            (testing "the unscoped viewer is unchanged — sees all
                      three (regression)"
              ;; the 300ms test heartbeat evicted the opening beats
              ;; (3 missed ≈ 900ms) while the scoped blocks above slept;
              ;; re-beat so the watcher's snapshot has something to see
              (beat! "elena" "/api/pres_widgets")
              (beat! "quinn" "/api/members")
              (beat! "nadia" "/api/-/events")
              (let [open (sse-lines port "/api/-/presence"
                                    {"x-waymark-principal" "watcher"})]
                (doseq [pid ["elena" "quinn" "nadia"]]
                  (is (some? (await-line (:lines open)
                                         #(str/includes? % (str "\"" pid "\""))
                                         10000))
                      (str pid "'s frame rides the unscoped stream")))
                (.close ^InputStream (:body open))
                (future-cancel (:reader open)))))
          (finally (engine/stop! eng server))))
      (finally (pg/close! st)))))

;; ── the fabricated self (waymark-tti.3 L7) ─────────────────────────
;;
;; Both ephemeral doors take a caller-supplied self and validate it
;; for SHAPE only, then publish the frame to everyone whose visibility
;; can GET that self. On the PRIVATE own-surface kinds that inverts
;; the wall: a stranger who 404s a letter could post a frame naming it
;; and have "someone is opening your letter" delivered to exactly the
;; two people who can read it. The report must pass the REPORTER's own
;; sight — silently, the same 204 either way, because a door that
;; narrated the drop would be the row-probe the 404 refuses to be.

(deftest fabricated-private-selves-are-never-published
  (fresh!)
  (let [st (pg/storage dsn)]
    (try
      (let [eng (engine/engine {:storage st :resources [widget letter]
                                :presence-heartbeat-ms 3000
                                :sse-heartbeat-ms 500
                                :events-poll-ms 200})
            server (engine/start! eng 0)
            h (engine/handler eng)
            reg (:presence @(:runtime eng))
            ireg (:intents @(:runtime eng))]
        (try
          (let [lid (get-in (inv/create! eng :letter
                                         {:owner "quill" :to "reed"
                                          :title "Sealed"
                                          :body "for reed alone"}
                                         {:principal elena})
                            [:row :id])
                w1 (get-in (inv/create! eng :pres_widget {:name "open"}
                                        {:principal elena})
                           [:row :id])
                self (str "/api/letters/" lid)
                post! (fn [uri body headers]
                        (h {:request-method :post :uri uri :headers headers
                            :body (wire/write-json body)}))
                agent-h (fn [id] {"x-waymark-principal" id
                                  "x-waymark-actor-type" "agent"})]

            (testing "a third agent's beat on a letter it 404s: 204, and nothing stored"
              (is (= 404 (:status (h {:request-method :get :uri self
                                      :headers (agent-h "spy")}))))
              (is (= 204 (:status (post! "/api/-/presence" {:self self}
                                         (agent-h "spy")))))
              (is (nil? (get @(:local reg) "spy"))
                  "the 204 is the same 204 — and the frame does not exist"))

            (testing "…and its fabricated INTENT is dropped the same silent way"
              (is (= 204 (:status (post! "/api/-/intents"
                                         {:self self :action "open"}
                                         (agent-h "spy")))))
              (is (not-any? #(= "spy" (get-in % [:entry :principal :id]))
                            (vals @(:local ireg)))))

            (testing "the RECIPIENT's own beat on the same self publishes (positive control)"
              (is (= 204 (:status (post! "/api/-/presence" {:self self}
                                         (agent-h "reed")))))
              (is (= self (get-in @(:local reg) ["reed" :entry :self]))))

            (testing "the AUTHOR's beat publishes too — two-party sight, both ends"
              (is (= 204 (:status (post! "/api/-/presence" {:self self}
                                         (agent-h "quill")))))
              (is (= self (get-in @(:local reg) ["quill" :entry :self]))))

            (testing "an ORDINARY kind is untouched: a stranger still marks its gaze"
              (let [wself (str "/api/pres_widgets/" w1)]
                (is (= 204 (:status (post! "/api/-/presence" {:self wself}
                                           (agent-h "spy")))))
                (is (= wself (get-in @(:local reg) ["spy" :entry :self]))
                    "only the private trio is gated — presence stays presence")))

            (testing "an unscoped human reports on a letter — it really can see it"
              (is (= 204 (:status (post! "/api/-/presence" {:self self}
                                         {"x-waymark-principal" "elena"}))))
              (is (= self (get-in @(:local reg) ["elena" :entry :self]))))

            (testing "a malformed self still meets its 422 — the drop never eats validation"
              (is (= 422 (:status (post! "/api/-/presence" {:self "nope"}
                                         (agent-h "spy")))))))
          (finally (engine/stop! eng server))))
      (finally (pg/close! st)))))

;; ── the same fabrication, spelled as a full URL ─────────────────────
;;
;; presence/report! STRIPS an http(s)://origin off a self before it
;; stores one — a raw-HTTP agent's natural spelling — so the gate has
;; to judge the stripped value or the strip becomes the bypass: a
;; full URL splits into six parts, does not look like a row self at
;; all, and the frame that finally lands names the private letter
;; anyway. The intents door strips nothing (a full URL is a 422
;; there, for reader and stranger alike, so the refusal tells no one
;; anything), and the gate asks each door in its own spelling rather
;; than assuming the two agree.

(deftest full-url-private-selves-are-never-published
  (fresh!)
  (let [st (pg/storage dsn)]
    (try
      (let [eng (engine/engine {:storage st :resources [widget letter]
                                :presence-heartbeat-ms 3000
                                :sse-heartbeat-ms 500
                                :events-poll-ms 200})
            server (engine/start! eng 0)
            port (http/server-port server)
            h (engine/handler eng)
            reg (:presence @(:runtime eng))
            ireg (:intents @(:runtime eng))]
        (try
          (let [lid (get-in (inv/create! eng :letter
                                         {:owner "scribe" :to "nib"
                                          :title "Sealed twice"
                                          :body "for nib alone"}
                                         {:principal elena})
                            [:row :id])
                self (str "/api/letters/" lid)
                ;; the spelling under test: origin and all
                full (str "http://127.0.0.1:" port self)
                post! (fn [uri body headers]
                        (h {:request-method :post :uri uri :headers headers
                            :body (wire/write-json body)}))
                agent-h (fn [id] {"x-waymark-principal" id
                                  "x-waymark-actor-type" "agent"})
                ;; UNSCOPED watchers: they see every frame either
                ;; registry publishes, so an absence here is a frame
                ;; that was never made — not one that was concealed
                pwatch (sse-lines port "/api/-/presence"
                                  {"x-waymark-principal" "lookout"})
                iwatch (sse-lines port "/api/-/intents"
                                  {"x-waymark-principal" "lookout"})]
            (is (some? (await-line (:lines pwatch)
                                   #(str/includes? % "snapshot") 10000)))
            (is (some? (await-line (:lines iwatch)
                                   #(str/includes? % "snapshot") 10000)))

            (testing "a stranger 404s the letter, then beats on its full URL"
              (is (= 404 (:status (h {:request-method :get :uri self
                                      :headers (agent-h "ghost")}))))
              (is (= 204 (:status (post! "/api/-/presence" {:self full}
                                         (agent-h "ghost"))))
                  "the same 204 — the door narrates nothing")
              (is (nil? (get @(:local reg) "ghost"))
                  "the origin-stripped self is the one the gate judged"))

            (testing "…and the fabricated INTENT spelled the same way"
              (is (= 422 (:status (post! "/api/-/intents"
                                         {:self full :action "open"}
                                         (agent-h "wraith"))))
                  "the intents door refuses a full URL outright")
              (is (not-any? #(= "wraith" (get-in % [:entry :principal :id]))
                            (vals @(:local ireg)))))

            (testing "the RECIPIENT's full-URL beat publishes, stored as
                      the bare path — the gate judges, it does not ban"
              (is (= 204 (:status (post! "/api/-/presence" {:self full}
                                         (agent-h "nib")))))
              (is (= self (get-in @(:local reg) ["nib" :entry :self]))))

            (testing "the recipient meets the intents door's SAME 422 —
                      the refusal is about spelling, never about sight"
              (is (= 422 (:status (post! "/api/-/intents"
                                         {:self full :action "open"}
                                         (agent-h "nib")))))
              (is (= 204 (:status (post! "/api/-/intents"
                                         {:self self :action "open"}
                                         (agent-h "nib"))))
                  "the href spelling is the one this door takes"))

            (Thread/sleep 1000)
            (testing "byte-level absence: no fabricated frame exists on
                      either stream, not even for a viewer who sees all"
              (let [pbytes (str/join "\n" @(:lines pwatch))
                    ibytes (str/join "\n" @(:lines iwatch))]
                (is (not (str/includes? pbytes "ghost")))
                (is (not (str/includes? ibytes "wraith")))
                (is (str/includes? pbytes "nib")
                    "the recipient's own frame did ride (positive control)")
                (is (str/includes? ibytes "nib"))))

            (.close ^InputStream (:body pwatch))
            (future-cancel (:reader pwatch))
            (.close ^InputStream (:body iwatch))
            (future-cancel (:reader iwatch)))
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
