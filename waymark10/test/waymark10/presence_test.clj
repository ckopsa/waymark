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
;; L7): grants/private-kind? reads :own-surface :grantable, so a
;; :letter declared here with :grantable false wears the real privacy
;; walls (waymark-ti0: it used to key on the kind NAME). The OWN-SURFACE half
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
    :own-surface {:by [:owner :to] :actions #{"create" "open"}
                  :grantable false}
    :schema [:map
             [:owner {:not-a-ref "A fixture's bare principal id."}
              [:string {:min 1 :max 128}]]
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
   "waymark10_cursors" "waymark10_job_leases" "waymark10_observations"
   "pres_notes"])

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
            h (engine/handler eng)
            beating (atom nil)]
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
            ;; keep all three beating for the whole test: the 300ms
            ;; heartbeat evicts a self after ~900ms, and a slow SSE
            ;; connect could otherwise open onto an empty snapshot
            (reset! beating
                    (future
                      (loop []
                        (beat! "elena" "/api/pres_widgets")
                        (beat! "quinn" "/api/members")
                        (beat! "nadia" "/api/-/events")
                        (Thread/sleep 100)
                        (recur))))
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
              (let [open (sse-lines port "/api/-/presence"
                                    {"x-waymark-principal" "watcher"})]
                (doseq [pid ["elena" "quinn" "nadia"]]
                  (is (some? (await-line (:lines open)
                                         #(str/includes? % (str "\"" pid "\""))
                                         10000))
                      (str pid "'s frame rides the unscoped stream")))
                (.close ^InputStream (:body open))
                (future-cancel (:reader open)))))
          (finally
            (some-> @beating future-cancel)
            (engine/stop! eng server))))
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

;; ── guided follow: the ui frame (docs/spec-guided-follow.md §1–2) ──

(def ^:private note
  "A row with one door: two plain arguments and one secret."
  (r/resource
   {:kind :pres_note
    :plural "pres_notes"
    :states [:open]
    :initial :open
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 80}]]]
    :actions
    {:assign {:from #{:open} :to :open
              :input [:map
                      [:assignee [:string {:max 400}]]
                      [:note {:optional true} [:maybe [:string {:max 8000}]]]
                      [:pin {:optional true :x-secret true}
                       [:maybe [:string {:max 12}]]]]
              :handler (fn [row _inp _ctx] row)
              :safety {:idempotent true :reversible true :confirm false}
              :display {:label "Assign" :order 1}}}}))

(def ^:private assigning
  {:dialog {:self "/api/pres_notes/n1" :action "assign"}
   :fields {:assignee "marco" :note "Take this one"}
   :collection {:self "/api/pres_notes" :filter {:title "x"} :sort "-title" :page 2}
   :focus "/api/pres_notes/n1"})

(defn- ui-of? [pid]
  #(and (= "ui" (:event %)) (= pid (get-in % [:principal :id]))))

(defn- with-ui-reg
  "One engine and one registry over the presence database; f gets
  [eng reg]."
  [opts f]
  (fresh!)
  (let [st (pg/storage dsn)]
    (try
      (let [eng (engine/engine {:storage st :resources [widget note]})
            ;; a heartbeat long enough that no wait below evicts (an
            ;; eviction is a leave, and a leave clears the ui)
            reg (presence/start! eng (merge {:hb-ms 5000} opts))]
        (try (f eng reg)
             (finally (presence/stop! reg))))
      (finally (pg/close! st)))))

(deftest ui-frames-cross-only-to-a-follower-that-asked
  (with-ui-reg {}
    (fn [eng reg]
      (let [follower (presence/subscribe reg nil {:ui "elena"
                                                  :redact (presence/ui-redactor eng nil)})
            other (presence/subscribe reg nil {:ui "marco"
                                               :redact (presence/ui-redactor eng nil)})
            plain (presence/subscribe reg nil)]
        (presence/report! reg elena "/api/pres_notes/n1" assigning)
        (let [f (next-frame follower (ui-of? "elena"))]
          (is (some? f))
          (is (= "/api/pres_notes/n1" (:self f)))
          (is (= {:self "/api/pres_notes/n1" :action "assign"} (get-in f [:ui :dialog])))
          (is (= {:assignee "marco" :note "Take this one"} (get-in f [:ui :fields])))
          (is (= "/api/pres_notes/n1" (get-in f [:ui :focus])))
          (testing "seq counts up per principal"
            (presence/report! reg elena "/api/pres_notes/n1" (assoc assigning :focus nil))
            (let [g (next-frame follower (ui-of? "elena"))]
              (is (< (long (:seq f)) (long (:seq g))))
              (is (nil? (get-in g [:ui :focus]))))))
        (testing "a stream that asked for another pid, or for none, gets no ui frame"
          (is (nil? (next-frame other #(= "ui" (:event %)) 500)))
          (is (nil? (next-frame plain #(= "ui" (:event %)) 500))))
        (testing "a plain beat keeps the last ui; a late follower's snapshot carries it"
          (presence/report! reg elena "/api/pres_notes/n2")
          (let [[e] (presence/snapshot reg (constantly true)
                                       {:ui "elena" :redact (presence/ui-redactor eng nil)})]
            (is (= "/api/pres_notes/n2" (:self e)))
            (is (= "assign" (get-in e [:ui :dialog :action])))
            (is (some? (:seq e)))))))))

(deftest a-stream-without-ui-is-byte-for-byte-today
  (with-ui-reg {}
    (fn [_eng reg]
      (let [plain (presence/subscribe reg nil)]
        (presence/report! reg elena "/api/pres_notes/n1" assigning)
        (let [f (next-frame plain #(= "join" (:event %)))]
          (is (= #{:event :principal :self :source :at} (set (keys f))))
          (is (not (str/includes? (presence/frame f) "assign"))))
        (presence/report! reg elena "/api/pres_notes/n1" (assoc assigning :focus nil))
        (is (nil? (next-frame plain some? 500))
            "a ui-only change enqueues nothing on a stream that did not ask")
        (let [snap (presence/snapshot reg (constantly true))]
          (is (= [#{:principal :self :source :at}] (mapv (comp set keys) snap)))
          (is (not (str/includes? (presence/frame {:event "snapshot" :presences snap})
                                  "assign"))))))))

(deftest ui-fields-the-follower-cannot-read-are-absent
  (with-ui-reg {}
    (fn [eng reg]
      (let [vis (fn [{:keys [rows actions args fields whole]}]
                  {:row? (fn [_k id] (contains? rows id))
                   :action? (fn [_k a] (contains? actions (name a)))
                   :arg? (fn [_k _a arg] (contains? args (name arg)))
                   :field? (fn [_k f] (contains? fields (name f)))
                   :whole-kind? (fn [_k] whole)})
            frame {:event "ui" :principal {:id "elena" :display "Elena" :type "human"}
                   :self "/api/pres_notes/n1" :source "heartbeat" :at "t" :seq 7
                   :ui assigning}
            redact #((presence/ui-redactor eng (vis %)) frame)]
        (testing "a key :arg? refuses is removed, not blanked"
          (let [f (redact {:rows #{"n1"} :actions #{"assign"} :args #{"assignee"}
                           :fields #{"title"} :whole true})]
            (is (= "ui" (:event f)))
            (is (= {:assignee "marco"} (get-in f [:ui :fields])))
            (is (not (contains? (get-in f [:ui :fields]) :note)))
            (is (= {:title "x"} (get-in f [:ui :collection :filter])))
            (is (= "-title" (get-in f [:ui :collection :sort])))))
        (testing "a dialog :action? refuses is null, and its fields go with it"
          (let [f (redact {:rows #{"n1"} :actions #{} :args #{"assignee" "note"}
                           :fields #{} :whole true})]
            (is (nil? (get-in f [:ui :dialog])))
            (is (nil? (get-in f [:ui :fields])))
            (is (= {} (get-in f [:ui :collection :filter])))
            (is (not (contains? (get-in f [:ui :collection]) :sort)))
            (is (= "/api/pres_notes/n1" (get-in f [:ui :focus])))))
        (testing "a frame redacted whole crosses as a plain move"
          (is (= {:event "move" :principal (:principal frame)
                  :self "/api/pres_notes/n1" :source "heartbeat" :at "t"}
                 (redact {:rows #{} :actions #{"assign"} :args #{} :fields #{}
                          :whole false}))))
        (testing "on the stream, under the follower's own visibility"
          (let [v (vis {:rows #{"n1"} :actions #{"assign"} :args #{"assignee"}
                        :fields #{} :whole true})
                sub (presence/subscribe reg (presence/self-visible? eng v)
                                        {:ui "elena" :redact (presence/ui-redactor eng v)})]
            (presence/report! reg elena "/api/pres_notes/n1" assigning)
            (let [f (next-frame sub (ui-of? "elena"))]
              (is (= {:assignee "marco"} (get-in f [:ui :fields])))
              (is (not (str/includes? (presence/frame f) "Take this one"))))))))))

(deftest secret-fields-never-reach-the-registry
  (with-ui-reg {}
    (fn [eng reg]
      (let [follower (presence/subscribe reg nil {:ui "elena"
                                                  :redact (presence/ui-redactor eng nil)})]
        (presence/report! reg elena "/api/pres_notes/n1"
                          (assoc-in assigning [:fields :pin] "8675309"))
        (let [f (next-frame follower (ui-of? "elena"))]
          (is (= {:assignee "marco" :note "Take this one"} (get-in f [:ui :fields])))
          (is (not (str/includes? (presence/frame f) "8675309"))))
        (is (not (str/includes? (pr-str @(:local reg)) "8675309"))
            "removed at report time: never stored, so never notified")
        (is (not (str/includes? (pr-str @(:published reg)) "8675309")))))))

(deftest ui-over-the-cap-elides-longest-first
  (with-ui-reg {}
    (fn [_eng reg]
      (let [long-note (apply str (repeat 7000 "x"))
            mid (apply str (repeat 300 "y"))]
        (presence/report! reg elena "/api/pres_notes/n1"
                          (assoc assigning :fields {:assignee mid :note long-note}))
        (let [fields (get-in @(:local reg) ["elena" :entry :ui :fields])]
          (is (= {:elided true} (:note fields)) "the longest value elides first")
          (is (= mid (:assignee fields)) "and only as many as it takes"))
        (testing "422 when even empty fields do not fit"
          (let [e (try (presence/report! reg elena "/api/pres_notes/n1"
                                         (assoc-in assigning [:collection :filter :title]
                                                   long-note))
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (some? e))
            (is (str/includes? (pr-str (ex-data e)) "422"))))))))

(deftest ui-frames-honour-the-curtain
  (with-ui-reg {:curtained? #(= "elena" %)}
    (fn [eng reg]
      (let [follower (presence/subscribe reg nil {:ui "elena"
                                                  :redact (presence/ui-redactor eng nil)})]
        (presence/report! reg elena "/api/pres_notes/n1" assigning)
        (presence/report! reg marco "/api/pres_notes/n1" assigning)
        (is (= "marco" (get-in (next-frame follower #(contains? #{"elena" "marco"}
                                                                (get-in % [:principal :id])))
                               [:principal :id]))
            "the curtained principal's frames, ui and all, never cross")
        (is (nil? (get @(:local reg) "elena")))
        (is (empty? (filter #(= "elena" (get-in % [:principal :id]))
                            (presence/snapshot reg (constantly true) {:ui "elena"}))))))))

;; ── the ui frame on the wire: GET ?ui=<pid> and POST {self, ui} ─────

(deftest ui-on-the-wire
  (fresh!)
  (let [st (pg/storage dsn)]
    (try
      (let [eng (engine/engine {:storage st :resources [widget note letter]
                                :presence-heartbeat-ms 3000
                                :sse-heartbeat-ms 500
                                :events-poll-ms 200})
            server (engine/start! eng 0)
            port (http/server-port server)
            h (engine/handler eng)
            reg (:presence @(:runtime eng))]
        (try
          (let [n1 (get-in (inv/create! eng :pres_note {:title "wired"}
                                        {:principal elena})
                           [:row :id])
                lid (get-in (inv/create! eng :letter
                                         {:owner "quill" :to "reed"
                                          :title "Sealed"
                                          :body "for reed alone"}
                                         {:principal elena})
                            [:row :id])
                self (str "/api/pres_notes/" n1)
                sealed (str "/api/letters/" lid)
                ui (assoc assigning
                          :dialog {:self self :action "assign"}
                          :collection {:self "/api/pres_notes" :filter {:title "x"}}
                          :focus self)
                beat! (fn [headers body]
                        (h {:request-method :post
                            :uri "/api/-/presence"
                            :headers headers
                            :body (wire/write-json body)}))
                agent-h (fn [id] {"x-waymark-principal" id
                                  "x-waymark-actor-type" "agent"})
                frames (fn [lines]
                         (keep #(when (str/starts-with? % "data:")
                                  (wire/read-json (str/trim (subs % 5))))
                               @lines))
                ui-line? (fn [pid]
                           #(and (str/starts-with? % "data:")
                                 ((ui-of? pid) (wire/read-json (str/trim (subs % 5))))))
                close! (fn [s]
                         (.close ^InputStream (:body s))
                         (future-cancel (:reader s)))
                gid (get-in (inv/create!
                             eng :grant
                             {:audience "spy"
                              :scope [{:kind "pres_note"
                                       :ids [n1] :actions []}]}
                             {:principal elena})
                            [:row :id])
                _ (inv/invoke! eng :grant gid :accept nil {:principal spy})
                plain (sse-lines port "/api/-/presence"
                                 {"x-waymark-principal" "watcher"})
                follower (sse-lines port "/api/-/presence?ui=elena"
                                    {"x-waymark-principal" "watcher"})
                scoped (sse-lines port "/api/-/presence?ui=elena"
                                  {"x-waymark-principal" "spy"
                                   "x-waymark-grant" gid})]
            (Thread/sleep 300)
            (is (= 204 (:status (beat! {"x-waymark-principal" "elena"}
                                       {:self self :ui ui}))))

            (testing "an unscoped ?ui= follower receives the frame whole"
              (let [l (await-line (:lines follower) (ui-line? "elena") 10000)
                    f (some-> l (subs 5) str/trim wire/read-json)]
                (is (some? f))
                (is (= self (:self f)))
                (is (= {:self self :action "assign"} (get-in f [:ui :dialog])))
                (is (= {:assignee "marco" :note "Take this one"}
                       (get-in f [:ui :fields])))
                (is (= self (get-in f [:ui :focus])))))

            (testing "a scoped follower's ?ui= stream receives the frame redacted"
              (let [l (await-line (:lines scoped) (ui-line? "elena") 10000)
                    f (some-> l (subs 5) str/trim wire/read-json)]
                (is (some? f))
                (is (= self (get-in f [:ui :focus])) "the granted row's focus crosses")
                (is (nil? (get-in f [:ui :dialog])) "an ungranted action's dialog is null")
                (is (nil? (get-in f [:ui :fields])) "and its fields go with it")
                (is (not (str/includes? (str/join "\n" @(:lines scoped)) "Take this one")))))

            (testing "a stream without ?ui= is byte for byte today's"
              (is (some? (await-line (:lines plain)
                                     #(and (str/includes? % "\"elena\"")
                                           (str/includes? % "\"join\""))
                                     10000)))
              (let [bytes' (str/join "\n" @(:lines plain))]
                (is (not-any? #(= "ui" (:event %)) (frames (:lines plain))))
                (is (every? #(= #{:event :principal :self :source :at} (set (keys %)))
                            (filter #(= "join" (:event %)) (frames (:lines plain)))))
                (is (not (str/includes? bytes' "assign")))
                (is (not (str/includes? bytes' "Take this one")))))

            (testing "an oversized ui part answers 422"
              (is (= 422 (:status (beat! {"x-waymark-principal" "elena"}
                                         {:self self
                                          :ui (assoc-in ui [:collection :filter :title]
                                                        (apply str (repeat 7000 "x")))})))))

            (testing "a dialog or focus on a private row the reporter cannot see never publishes"
              (let [watch (sse-lines port "/api/-/presence?ui=spy"
                                     {"x-waymark-principal" "watcher"})]
                (Thread/sleep 300)
                (is (= 404 (:status (h {:request-method :get :uri sealed
                                        :headers (agent-h "spy")}))))
                (is (= 204 (:status (beat! (agent-h "spy")
                                           {:self self
                                            :ui {:dialog {:self sealed :action "open"}
                                                 :fields {:body "peek"}
                                                 :focus sealed}}))))
                (is (= self (get-in @(:local reg) ["spy" :entry :self]))
                    "the beat itself, on a readable row, is stored")
                (is (nil? (get-in @(:local reg) ["spy" :entry :ui :focus])))
                (is (nil? (get-in @(:local reg) ["spy" :entry :ui :dialog])))
                (is (nil? (get-in @(:local reg) ["spy" :entry :ui :fields])))
                (Thread/sleep 1500)
                (let [bytes' (str/join "\n" @(:lines watch))]
                  (is (not (str/includes? bytes' lid))
                      "the private row's id never crosses the wire")
                  (is (not (str/includes? bytes' "peek"))))
                (close! watch))
              (testing "the recipient's own focus on it is kept (positive control)"
                (is (= 204 (:status (beat! (agent-h "reed")
                                           {:self sealed :ui {:focus sealed}}))))
                (is (= sealed (get-in @(:local reg) ["reed" :entry :ui :focus])))))

            (close! plain)
            (close! follower)
            (close! scoped))
          (finally (engine/stop! eng server))))
      (finally (pg/close! st)))))
