(ns waymark10.batch-d-collab-test
  "Batch-D acceptance, part 2: relay/2 on the wire, one engine. Two
  websocket clients over a live shared draft: the state frame on
  join, acks to the setter, per-field revs with explicit staleness
  rejection (no silent LWW), presence joined/left, prose OPERATION
  frames with server-transformed broadcasts, the regate when the
  fence bumps (a version-moving act), and the regate-gone when the
  act consumes the draft — op logs and revs consumed with the row.

  Needs the waymark10_d_test database (batch D's own, never the
  suite's):
    WAYMARK10_D_DSN=jdbc:postgresql://localhost:5433/waymark10_d_test?user=ckopsa"
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [org.httpkit.server :as http]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.net URI)
           (java.net.http HttpClient WebSocket WebSocket$Listener)))

(def ^:private dsn
  (or (System/getenv "WAYMARK10_D_DSN")
      "jdbc:postgresql://localhost:5433/waymark10_d_test?user=ckopsa"))

;; ── the world ───────────────────────────────────────────────────────

(r/defhandler revise-dpad [row inp _ctx]
  (update row :data merge inp))

(def ^:private dpad
  (r/resource
   {:kind :dpad
    :states [:open :closed]
    :initial :open
    :terminal #{:closed}
    :summary "{data.title} · {state}"
    :schema [:map
             [:title [:string {:min 1 :max 60}]]
             [:notes {:optional true :x-display {:widget "prose"}}
              [:maybe [:string {:max 500}]]]]
    :actions
    {:revise {:from #{:open} :to :open
              :input [:map
                      [:title {:optional true} [:maybe [:string {:min 1 :max 60}]]]
                      [:notes {:optional true :x-display {:widget "prose"}}
                       [:maybe [:string {:max 500}]]]]
              :edit {:draft {:shared true :live true}
                     :prefill [:title :notes]}
              :guards []
              :safety {:idempotent true :reversible true :confirm false}
              :handler revise-dpad
              :display {:label "Revise"}}
     ;; a version-moving act that does NOT consume revise's draft —
     ;; the fence bump the regate watches for
     :touch {:from #{:open} :to :open
             :safety {:idempotent true :reversible true :confirm false}}
     :close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Closed pads stay closed."}}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(def ^:private tables
  ["dpads" "definitions" "members" "roles" "grants" "attachments"
   "subscriptions" "jobs"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"
   "waymark10_cursors" "waymark10_job_leases"])

;; ── ws client sugar ─────────────────────────────────────────────────

(defn- ws-connect
  "One websocket client: {:ws :frames} — frames is the atom of parsed
  incoming messages, in arrival order."
  [uri principal-id]
  (let [frames (atom [])
        buf (StringBuilder.)
        listener (reify WebSocket$Listener
                   (onText [_ ws data last?]
                     (.append buf ^CharSequence data)
                     (when last?
                       (swap! frames conj (wire/read-json (.toString buf)))
                       (.setLength buf 0))
                     (.request ^WebSocket ws 1)
                     nil))
        ws (-> (HttpClient/newHttpClient)
               (.newWebSocketBuilder)
               (.header "x-waymark-principal" principal-id)
               (.buildAsync (URI. uri) listener)
               (.join))]
    {:ws ws :frames frames}))

(defn- send! [client msg]
  (.join (.sendText ^WebSocket (:ws client) ^String (wire/write-json msg) true)))

(defn- close! [client]
  (try (.join (.sendClose ^WebSocket (:ws client) WebSocket/NORMAL_CLOSURE ""))
       (catch Exception _ nil)))

(defn- await-frame
  "The first frame matching pred within the timeout, or nil."
  ([client pred] (await-frame client pred 10000))
  ([client pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (or (some #(when (pred %) %) @(:frames client))
           (when (< (System/currentTimeMillis) deadline)
             (Thread/sleep 25)
             (recur)))))))

(defn- await-frame-after
  "The first frame past index n matching pred within the timeout, or
  nil — for frames whose earlier twins would satisfy the pred."
  [client n pred]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (or (some #(when (pred %) %) (drop n @(:frames client)))
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 25)
            (recur))))))

(defn- sync-state
  "Ask for a sync and answer the freshest sync frame."
  [client]
  (let [n (count @(:frames client))]
    (send! client {:type "sync"})
    (await-frame client (fn [_]
                          (some #(= "sync" (:type %))
                                (drop n @(:frames client)))))
    (last (filter #(= "sync" (:type %)) @(:frames client)))))

;; ── the story ───────────────────────────────────────────────────────

(deftest relay-2-on-the-wire
  (let [st (pg/storage dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (let [eng (assoc (engine/engine {:storage st :resources [dpad]
                                       :events-poll-ms 200})
                       :collab-heartbeat-ms 1000)
            server (engine/start! eng 0)
            port (http/server-port server)
            h (engine/handler eng)
            json (fn [resp] (some-> (:body resp) wire/read-json))]
        (try
          (let [{row :row} (inv/create! eng :dpad {:title "Week plan"}
                                        {:principal elena})
                pid (:id row)
                ;; let the dispatcher drain the create's transition
                ;; before any room subscribes — a late drain would
                ;; regate a room over a transition older than it
                _ (Thread/sleep 500)
                ws-uri (str "ws://127.0.0.1:" port "/api/dpads/" pid
                            "/-/revise/draft/collab")
                alice (ws-connect ws-uri "alice")
                _ (is (some? (await-frame alice #(= "state" (:type %)))))
                bob (ws-connect ws-uri "bob")]

            (testing "the joiner is answered with the state frame"
              (let [f (await-frame bob #(= "state" (:type %)))]
                (is (some? f))
                (is (= {} (:values f)))
                (is (= {} (:revs f)))
                (is (= 1 (:base_version f)))
                (is (false? (:stale f)))
                (is (= ["alice" "bob"] (mapv :id (:participants f))))))

            (testing "presence: the room hears the join"
              (let [f (await-frame alice #(and (= "presence" (:type %))
                                               (= "joined" (:event %))))]
                (is (= "bob" (get-in f [:actor :id])))
                (is (= ["alice" "bob"] (mapv :id (:participants f))))))

            (testing "a set is acked to the setter and broadcast with rev + author"
              (send! alice {:type "set" :field "title"
                            :value "Family week" :rev 0})
              (let [ack (await-frame alice #(= "ack" (:type %)))]
                (is (= "title" (:field ack)))
                (is (= 1 (:rev ack))))
              (let [f (await-frame bob #(= "update" (:type %)))]
                (is (= "title" (:field f)))
                (is (= "Family week" (:value f)))
                (is (= 1 (:rev f)))
                (is (= "alice" (get-in f [:author :id])))))

            (testing "a stale base is rejected with the field's truth — no silent LWW"
              (send! bob {:type "set" :field "title"
                          :value "Bob's week" :rev 0})
              (let [f (await-frame bob #(= "stale" (:type %)))]
                (is (= "title" (:field f)))
                (is (= 1 (:rev f)))
                (is (= "Family week" (:value f))))
              (is (nil? (some #(when (and (= "update" (:type %))
                                          (= "Bob's week" (:value %))) %)
                              @(:frames alice)))
                  "the rejected set reached nobody"))

            (testing "a set at the current base advances the field's rev"
              (send! bob {:type "set" :field "title"
                          :value "Bob's week" :rev 1})
              (is (= 2 (:rev (await-frame bob #(and (= "ack" (:type %))
                                                    (= 2 (:rev %)))))))
              (is (some? (await-frame alice #(and (= "update" (:type %))
                                                  (= 2 (:rev %)))))))

            (testing "revs are per field"
              (send! bob {:type "set" :field "notes" :value "hello" :rev 0})
              (let [ack (await-frame bob #(and (= "ack" (:type %))
                                               (= "notes" (:field %))))]
                (is (= 1 (:rev ack)) "notes' first rev is 1 — not the room's 3")))

            (testing "prose fields take operation frames; the broadcast is transformed"
              ;; alice edits from base 1 ("hello" → "Ahello")
              (send! alice {:type "edit" :field "notes" :rev 1
                            :ops [{:insert "A"} {:retain 5}]})
              (is (= 2 (:rev (await-frame alice #(and (= "ack" (:type %))
                                                      (= "notes" (:field %)))))))
              (let [f (await-frame bob #(= "edit" (:type %)))]
                (is (= [{:insert "A"} {:retain 5}] (:ops f)))
                (is (= 2 (:rev f)))
                (is (= "alice" (get-in f [:author :id]))))
              ;; bob edits CONCURRENTLY from the same base 1 ("hello" →
              ;; "Bhello" locally); the server transforms it against
              ;; alice's rev-2 op before applying and broadcasting
              (send! bob {:type "edit" :field "notes" :rev 1
                          :ops [{:insert "B"} {:retain 5}]})
              (is (= 3 (:rev (await-frame bob #(and (= "ack" (:type %))
                                                    (= 3 (:rev %)))))))
              (let [f (await-frame alice #(= "edit" (:type %)))]
                (is (= [{:retain 1} {:insert "B"} {:retain 5}] (:ops f))
                    "the broadcast op is the TRANSFORMED op")
                (is (= 3 (:rev f)))
                (is (= "bob" (get-in f [:author :id]))))
              (is (= "ABhello" (get-in (sync-state alice) [:values :notes])))
              (is (= "ABhello" (get-in (sync-state bob) [:values :notes]))
                  "both clients converge on the server's document"))

            (testing "the draft row carries the document — revs and authors on GET"
              (let [resp (h {:request-method :get
                             :uri (str "/api/dpads/" pid "/-/revise/draft")
                             :headers {"x-waymark-principal" "carol"}})
                    view (json resp)]
                (is (= 200 (:status resp)))
                (is (= {:title "Bob's week" :notes "ABhello"} (:values view)))
                (is (= {:title 2 :notes 3} (:revs view)))
                (is (= "bob" (get-in view [:authors :notes :id])))))

            (testing "an edit refuses what it must"
              (send! alice {:type "edit" :field "title" :rev 2
                            :ops [{:retain 10}]})
              (is (some? (await-frame alice #(and (= "error" (:type %))
                                                  (contains? (:errors %) :title))))
                  "a scalar field takes sets, not ops")
              (send! alice {:type "edit" :field "notes" :rev 3
                            :ops [{:grow 3}]})
              (is (some? (await-frame alice #(and (= "error" (:type %))
                                                  (contains? (:errors %) :notes))))
                  "malformed ops answer an error")
              (send! alice {:type "edit" :field "notes" :rev 3
                            :ops [{:retain 999}]})
              (is (some? (await-frame alice #(and (= "error" (:type %))
                                                  (some #{"ops do not span the document"}
                                                        (get-in % [:errors :notes])))))
                  "ops must span the document"))

            (testing "unknown fields and broken values still answer errors"
              (send! alice {:type "set" :field "evil" :value 1 :rev 0})
              (is (= ["unknown field"]
                     (get-in (await-frame alice #(and (= "error" (:type %))
                                                      (contains? (:errors %) :evil)))
                             [:errors :evil])))
              (let [n (count @(:frames alice))]
                (send! alice {:type "set" :field "title" :value 42 :rev 2})
                (is (some? (await-frame-after
                            alice n #(and (= "error" (:type %))
                                          (contains? (:errors %) :title)))))))

            (testing "the fence bumps: a version-moving act regates the room"
              (let [etag (get-in (json (h {:request-method :get
                                           :uri (str "/api/dpads/" pid)
                                           :headers {}}))
                                 [:meta :etag])
                    resp (h {:request-method :post
                             :uri (str "/api/dpads/" pid "/-/touch")
                             :headers {"x-waymark-principal" "elena"
                                       "if-match" etag}
                             :body (wire/write-json {})})]
                (is (= 200 (:status resp))))
              (let [f (await-frame alice #(and (= "regate" (:type %))
                                               (not (:gone %))))]
                (is (= 2 (:base_version f)))
                (is (= 3 (get-in f [:revs :title])) "every field's rev bumped"))
              (is (some? (await-frame bob #(and (= "regate" (:type %))
                                                (not (:gone %))))))
              (testing "sets against the old base answer stale until the re-pull"
                (send! alice {:type "set" :field "title"
                              :value "post-touch" :rev 2})
                (is (= 3 (:rev (await-frame alice #(and (= "stale" (:type %))
                                                        (= 3 (:rev %))))))))
              (testing "the re-pulled base is accepted"
                (send! alice {:type "set" :field "title"
                              :value "post-touch" :rev 3})
                (is (= 4 (:rev (await-frame alice #(and (= "ack" (:type %))
                                                        (= 4 (:rev %)))))))))

            (testing "the act consumes the draft: regate-gone, op logs cleared"
              (let [na (count @(:frames alice))
                    nb (count @(:frames bob))
                    etag (get-in (json (h {:request-method :get
                                           :uri (str "/api/dpads/" pid)
                                           :headers {}}))
                                 [:meta :etag])
                    resp (h {:request-method :post
                             :uri (str "/api/dpads/" pid "/-/revise")
                             :headers {"x-waymark-principal" "alice"
                                       "if-match" etag}
                             :body (wire/write-json {:title "post-touch"
                                                     :notes "ABhello"})})]
                (is (= 200 (:status resp)))
                (is (= "post-touch" (get-in (json resp) [:data :title])))
                (is (some? (await-frame-after
                            alice na #(and (= "regate" (:type %))
                                           (true? (:gone %)))))
                    "the room hears the draft is gone")
                (is (some? (await-frame-after
                            bob nb #(and (= "regate" (:type %))
                                         (true? (:gone %)))))))
              (is (= 404 (:status (h {:request-method :get
                                      :uri (str "/api/dpads/" pid
                                                "/-/revise/draft")
                                      :headers {"x-waymark-principal" "alice"}})))
                  "consumed in the act's own commit")
              (let [s (sync-state alice)]
                (is (= {} (:values s)))
                (is (= {} (:revs s)) "revs and op logs went with the row")
                (is (= 3 (:base_version s))))
              (testing "composition starts anew at rev 0"
                (send! alice {:type "set" :field "title"
                              :value "next week" :rev 4})
                (is (= 0 (:rev (await-frame alice #(and (= "stale" (:type %))
                                                        (= 0 (:rev %)))))))
                (let [n (count @(:frames alice))]
                  (send! alice {:type "set" :field "title"
                                :value "next week" :rev 0})
                  (is (= 1 (:rev (await-frame-after
                                  alice n #(and (= "ack" (:type %))
                                                (= 1 (:rev %))))))))))

            (testing "a collab route for an unlive draft does not exist"
              (is (= 404 (:status (h {:request-method :get
                                      :uri (str "/api/dpads/" pid
                                                "/-/close/draft/collab")
                                      :headers {}})))))

            (testing "presence: the room hears the leave"
              (let [n (count @(:frames alice))]
                (close! bob)
                (is (some? (await-frame
                            alice
                            (fn [_]
                              (some #(and (= "presence" (:type %))
                                          (= "left" (:event %))
                                          (= "bob" (get-in % [:actor :id])))
                                    (drop n @(:frames alice)))))))))

            (testing "rooms and the relay clean up on last disconnect"
              (close! alice)
              (let [deadline (+ (System/currentTimeMillis) 5000)]
                (loop []
                  (when (and (seq @(:collab-rooms eng))
                             (< (System/currentTimeMillis) deadline))
                    (Thread/sleep 50)
                    (recur))))
              (is (empty? @(:collab-rooms eng)))
              (let [deadline (+ (System/currentTimeMillis) 5000)
                    relay-thread? (fn []
                                    (some #(and (.startsWith
                                                 (.getName ^Thread %)
                                                 "waymark10-collab-relay")
                                                (.isAlive ^Thread %))
                                          (keys (Thread/getAllStackTraces))))]
                (loop []
                  (when (and (relay-thread?)
                             (< (System/currentTimeMillis) deadline))
                    (Thread/sleep 50)
                    (recur)))
                (is (not (relay-thread?))
                    "the LISTEN thread stops with the last room"))))
          (finally (engine/stop! eng server))))
      (finally (pg/close! st)))))
