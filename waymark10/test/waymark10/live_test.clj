(ns waymark10.live-test
  "The combined stream (waymark-p5tg): GET /api/-/live carries the
  firehose's row events, presence and intents on ONE connection, and
  the acceptance is a DIFF — four streams opened at the same instant
  for the same principal, the same activity driven past all of them,
  and the frames compared byte for byte.

  Three principals do the work:
  - watcher, unscoped: every frame /api/-/events, /api/-/presence and
    /api/-/intents deliver must arrive on /api/-/live, identical.
  - spy, grant-scoped to ONE widget: /api/-/events is a 404 for it
    (the firehose is unscoped-only, a recorded punt), so /api/-/live
    must carry NO transition and NO derivation frame — concealment by
    absence rather than by refusal, since the other two sources still
    have something to say to it.
  - quinn, a stranger, reporting an intent on the widget spy cannot
    see: that card must not reach spy's combined stream, byte-level,
    exactly as it does not reach spy's /api/-/intents.

  The old three routes are asserted unchanged throughout — they are
  the comparison, so a regression in them fails this suite too.

  Needs the waymark10_live_test database (its own, never the suite's):
    WAYMARK10_LIVE_DSN=jdbc:postgresql://localhost:5433/waymark10_live_test?user=ckopsa"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [org.httpkit.server :as http]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.io BufferedReader InputStream InputStreamReader)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(def ^:private dsn
  (or (System/getenv "WAYMARK10_LIVE_DSN")
      "jdbc:postgresql://localhost:5433/waymark10_live_test?user=ckopsa"))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private widget
  (r/resource
   {:kind :live_widget
    :plural "live_widgets"
    :states [:idle :spun]
    :initial :idle
    :terminal #{:spun}
    :summary "{data.name} · {state}"
    :schema [:map [:name [:string {:min 1 :max 40}]]]
    :filterable {:state #{:eq}}
    :actions {:spin {:from #{:idle} :to :spun
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "Spun is history."}}}}))

(def ^:private tables
  ["live_widgets" "definitions" "members" "roles" "grants"
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
(def ^:private spy (t/principal {:id "spy" :type :agent :display "Spy"}))

;; ── the wire, read as raw lines ─────────────────────────────────────

(defn- sse-lines
  "Open one SSE GET and collect its raw lines into an atom — byte-level
  truth, which is the only kind this suite asserts on (the presence
  suite's own helper)."
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
    {:resp resp :status (.statusCode resp) :lines lines :reader reader
     :body (.body resp)}))

(defn- close-stream! [s]
  (try (.close ^InputStream (:body s)) (catch Exception _ nil))
  (future-cancel (:reader s)))

(defn- frames
  "The collected lines grouped back into SSE frames — each a vector of
  its own lines, comments dropped. Comments are the ONE thing this
  suite cannot compare: `: hb` arrives once per connection on
  /api/-/live and once per connection on each of the three, which is
  the whole point of the bead (one heartbeat, not three)."
  [lines]
  (->> (partition-by str/blank? lines)
       (remove #(every? str/blank? %))
       (remove #(str/starts-with? (first %) ":"))
       (mapv vec)))

(defn- named
  "Every frame carrying this SSE event name, in arrival order."
  [fs ev]
  (filterv #(= (str "event: " ev) (first %)) fs))

(defn- await-frame
  "Wait until a frame matching pred has arrived on this stream."
  ([s pred] (await-frame s pred 10000))
  ([s pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (or (some #(when (pred %) %) (frames @(:lines s)))
           (when (< (System/currentTimeMillis) deadline)
             (Thread/sleep 100)
             (recur)))))))

(defn- has? [frame s] (boolean (some #(str/includes? % s) frame)))

(defn- bytes-of [s] (str/join "\n" @(:lines s)))

;; ── the unscoped diff: four streams, one truth ──────────────────────

(deftest live-carries-every-frame-the-three-routes-do
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
          (let [post! (fn [path pid body]
                        (h {:request-method :post
                            :uri path
                            :headers {"x-waymark-principal" pid}
                            :body (wire/write-json body)}))
                who {"x-waymark-principal" "watcher"}
                ;; FIRST sight of a principal registers a member, and
                ;; that is a transition like any other: opened cold,
                ;; the four streams would each start one id apart and
                ;; the diff would be comparing arrival times. So every
                ;; principal this test speaks as is walked through the
                ;; identity gate first, and the dispatcher is given a
                ;; beat to drain what that wrote.
                _ (doseq [pid ["watcher" "elena"]]
                    (h {:request-method :get :uri "/api/live_widgets"
                        :headers {"x-waymark-principal" pid}}))
                _ (Thread/sleep 800)
                ;; all four at once, BEFORE any activity — so the two
                ;; snapshot frames are empty on every stream and the
                ;; comparison is not a race against the clock
                live (sse-lines port "/api/-/live" who)
                evts (sse-lines port "/api/-/events" who)
                pres (sse-lines port "/api/-/presence" who)
                ints (sse-lines port "/api/-/intents" who)]
            (try
              (is (= 200 (:status live)))

              (testing "the opening snapshots are the ephemeral routes'
                        own, byte for byte"
                (is (some? (await-frame live #(= "event: presence" (first %)))))
                (is (some? (await-frame live #(= "event: intent" (first %)))))
                (is (= (named (frames @(:lines pres)) "presence")
                       (named (frames @(:lines live)) "presence"))
                    "the presence snapshot is the presence route's frame")
                (is (= (named (frames @(:lines ints)) "intent")
                       (named (frames @(:lines live)) "intent"))
                    "the intents snapshot is the intents route's frame"))

              (let [w (get-in (inv/create! eng :live_widget {:name "one"}
                                           {:principal elena})
                              [:row :id])
                    self (str "/api/live_widgets/" w)]

                (testing "a row event reaches the combined stream as the
                          firehose's own transition frame"
                  (is (some? (await-frame evts #(has? % "\"create\""))))
                  (is (some? (await-frame live #(has? % "\"create\""))))
                  (inv/invoke! eng :live_widget w :spin nil {:principal elena})
                  (is (some? (await-frame evts #(has? % "\"spin\""))))
                  (is (some? (await-frame live #(has? % "\"spin\"")))))

                (testing "a presence beat and an intent card reach it as
                          their own routes' frames"
                  (is (= 204 (:status (post! "/api/-/presence" "elena"
                                             {:self self}))))
                  (is (= 204 (:status (post! "/api/-/intents" "elena"
                                             {:self self :action "spin"
                                              :question "Spin it?"}))))
                  (is (some? (await-frame live #(has? % "\"Spin it?\""))))
                  (is (some? (await-frame ints #(has? % "\"Spin it?\""))))
                  (is (some? (await-frame pres #(has? % "\"heartbeat\""))))
                  (is (some? (await-frame live #(has? % "\"heartbeat\"")))))

                ;; let every stream settle on the same tail
                (Thread/sleep 1500)

                (testing "THE DIFF: each event name's frames are the same
                          frames, in the same order, on /api/-/live as on
                          the route that owns them"
                  (let [l (frames @(:lines live))]
                    (is (= (named (frames @(:lines evts)) "transition")
                           (named l "transition")))
                    (is (= (named (frames @(:lines evts)) "derivation")
                           (named l "derivation")))
                    (is (= (named (frames @(:lines pres)) "presence")
                           (named l "presence")))
                    (is (= (named (frames @(:lines ints)) "intent")
                           (named l "intent")))
                    (is (seq (named l "transition"))
                        "the diff is not vacuous — row events did arrive")
                    (is (seq (named l "presence")))
                    (is (seq (named l "intent")))
                    (is (= #{"event: transition" "event: presence"
                             "event: intent"}
                           (set (map first l)))
                        "and nothing else rides the combined stream")))

                (testing "Last-Event-ID replays the row events the same
                          way on both doors"
                  (let [rl (sse-lines port "/api/-/live?last_event_id=0" who)
                        re (sse-lines port "/api/-/events?last_event_id=0" who)]
                    (try
                      (is (some? (await-frame rl #(has? % "\"spin\""))))
                      (is (some? (await-frame re #(has? % "\"spin\""))))
                      (Thread/sleep 800)
                      (is (= (named (frames @(:lines re)) "transition")
                             (named (frames @(:lines rl)) "transition"))
                          "same replay, id lines and all")
                      (let [rf (named (frames @(:lines rl)) "transition")]
                        (is (some #(has? % "\"spin\"") rf)
                            "the spin is in the replay, not merely the tail")
                        (is (some #(has? % "\"create\"") rf))
                        (is (every? #(some (fn [l] (str/starts-with? l "id: ")) %) rf)
                            "every replayed row event carries its id line"))
                      (finally (close-stream! rl) (close-stream! re))))))
              (finally
                (doseq [s [live evts pres ints]] (close-stream! s)))))
          (finally (engine/stop! eng server))))
      (finally (pg/close! st)))))

;; ── the scoped diff: the firehose half is absent, not refused ───────

(deftest a-scoped-caller-gets-no-row-events-and-no-strangers-card
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
          (let [post! (fn [path pid headers body]
                        (h {:request-method :post
                            :uri path
                            :headers (merge {"x-waymark-principal" pid} headers)
                            :body (wire/write-json body)}))
                w1 (get-in (inv/create! eng :live_widget {:name "granted"}
                                        {:principal elena})
                           [:row :id])
                w2 (get-in (inv/create! eng :live_widget {:name "concealed"}
                                        {:principal elena})
                           [:row :id])
                seen (str "/api/live_widgets/" w1)
                unseen (str "/api/live_widgets/" w2)
                ;; spy sees w1 and nothing else
                gid (get-in (inv/create! eng :grant
                                         {:audience "spy"
                                          :scope [{:kind "live_widget"
                                                   :ids [w1] :actions []}]}
                                         {:principal elena})
                            [:row :id])
                _ (inv/invoke! eng :grant gid :accept nil {:principal spy})
                scoped {"x-waymark-principal" "spy" "x-waymark-grant" gid}
                ;; the same member-registration beat as above: every
                ;; principal through the gate before a stream opens
                _ (doseq [pid ["spy" "elena" "nadia" "quinn"]]
                    (h {:request-method :get :uri "/api/live_widgets"
                        :headers {"x-waymark-principal" pid}}))
                _ (Thread/sleep 800)]

            (testing "the firehose still refuses a scoped caller outright
                      — the old route is untouched"
              (let [s (sse-lines port "/api/-/events" scoped)]
                (is (= 404 (:status s)))
                (close-stream! s)))

            (let [live (sse-lines port "/api/-/live" scoped)
                  pres (sse-lines port "/api/-/presence" scoped)
                  ints (sse-lines port "/api/-/intents" scoped)]
              (try
                (is (= 200 (:status live))
                    "but the combined stream serves it: presence and
                     intents still have something to say")

                ;; row events happen, on the granted row and the other
                (inv/invoke! eng :live_widget w1 :spin nil {:principal elena})
                (inv/invoke! eng :live_widget w2 :spin nil {:principal elena})

                ;; presence and intents, both from OTHER principals:
                ;; one on the row spy may see, one on the row it may not
                (is (= 204 (:status (post! "/api/-/presence" "elena" {}
                                           {:self seen}))))
                (is (= 204 (:status (post! "/api/-/presence" "nadia" {}
                                           {:self unseen}))))
                (is (= 204 (:status (post! "/api/-/intents" "elena" {}
                                           {:self seen :action "spin"
                                            :question "Spin the granted one?"}))))
                (is (= 204 (:status (post! "/api/-/intents" "quinn" {}
                                           {:self unseen :action "spin"
                                            :question "Spin the hidden one?"}))))

                (is (some? (await-frame live #(has? % "\"elena\""))))
                ;; long enough for a transition frame to have arrived if
                ;; one were ever going to
                (Thread/sleep 2000)

                (testing "no row event crosses a boundary it could not
                          cross on its own route"
                  (let [l (frames @(:lines live))]
                    (is (empty? (named l "transition"))
                        "the firehose half is ABSENT for a scoped caller")
                    (is (empty? (named l "derivation")))
                    (is (= #{"event: presence" "event: intent"}
                           (set (map first l))))))

                (testing "and no stranger's frame on an ungranted self
                          appears — byte level, as on the routes
                          themselves"
                  (let [b (bytes-of live)]
                    (is (str/includes? b w1))
                    (is (str/includes? b "elena"))
                    (is (str/includes? b "Spin the granted one?"))
                    (is (not (str/includes? b w2))
                        "the concealed row's id never crosses the wire")
                    (is (not (str/includes? b "nadia"))
                        "a presence on an ungranted self is absent")
                    (is (not (str/includes? b "quinn"))
                        "another principal's intent card is absent")
                    (is (not (str/includes? b "Spin the hidden one?")))))

                (testing "THE DIFF, scoped: what does arrive is exactly
                          what the two ephemeral routes deliver"
                  (let [l (frames @(:lines live))]
                    (is (= (named (frames @(:lines pres)) "presence")
                           (named l "presence")))
                    (is (= (named (frames @(:lines ints)) "intent")
                           (named l "intent")))))
                (finally
                  (doseq [s [live pres ints]] (close-stream! s))))))
          (finally (engine/stop! eng server))))
      (finally (pg/close! st)))))

;; ── the lifecycle discipline ────────────────────────────────────────

(deftest live-without-start-is-503
  (fresh!)
  (let [st (pg/storage dsn)]
    (try
      (let [eng (engine/engine {:storage st :resources [widget]})
            h (engine/handler eng)
            res (h {:request-method :get :uri "/api/-/live"
                    :headers {"x-waymark-principal" "watcher"}})]
        (is (= 503 (:status res))
            "an engine that never started runs no dispatcher and no
             registries — the same 503 the three routes answer"))
      (finally (pg/close! st)))))
