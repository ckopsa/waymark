(ns waymark10.batch-d-relay-test
  "Batch-D acceptance, part 3: the cross-process relay. TWO engine
  instances — separate pools, separate registry atoms, separate
  collab relays with their own LISTEN connections — over one
  database in one JVM: the faithful two-process simulation
  (coherence_test's shape). Clients on separate engines see each
  other's accepted frames via pg_notify on waymark10_collab,
  presence rosters merge across origins (with the one-round-trip
  introduction and heartbeat-timed eviction of a silent origin),
  concurrent prose edits issued on different engines converge, and
  an act through one engine regates the other engine's room.

  Needs the waymark10_d_test database (batch D's own):
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

(r/defhandler revise-rpad [row inp _ctx]
  (update row :data merge inp))

(def ^:private rpad
  (r/resource
   {:kind :rpad
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
              :handler revise-rpad
              :display {:label "Revise"}}
     :close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Closed pads stay closed."}}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(def ^:private tables
  ["rpads" "definitions" "members" "roles" "grants" "attachments"
   "subscriptions" "jobs"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"
   "waymark10_cursors" "waymark10_job_leases"])

(def ^:private hb-ms 250)

;; ── ws client sugar (batch-d-collab-test's) ─────────────────────────

(defn- ws-connect [uri principal-id]
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
  ([client pred] (await-frame client 0 pred))
  ([client n pred]
   (let [deadline (+ (System/currentTimeMillis) 15000)]
     (loop []
       (or (some #(when (pred %) %) (drop n @(:frames client)))
           (when (< (System/currentTimeMillis) deadline)
             (Thread/sleep 25)
             (recur)))))))

(defn- sync-state [client]
  (let [n (count @(:frames client))]
    (send! client {:type "sync"})
    (await-frame client n #(= "sync" (:type %)))))

(defn- participant-ids [frame]
  (set (map :id (:participants frame))))

;; ── the story ───────────────────────────────────────────────────────

(deftest frames-cross-processes
  (let [boot (pg/storage dsn)]
    ;; every run starts from bytes it made itself
    (store/with-tx boot
      (fn [tx]
        (doseq [table tables]
          (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
    (pg/close! boot)
    (let [st-a (pg/storage dsn)
          st-b (pg/storage dsn)]
      (try
        (let [eng-a (assoc (engine/engine {:storage st-a :resources [rpad]
                                           :events-poll-ms 200})
                           :collab-heartbeat-ms hb-ms)
              eng-b (assoc (engine/engine {:storage st-b :resources [rpad]
                                           :events-poll-ms 200})
                           :collab-heartbeat-ms hb-ms)
              server-a (engine/start! eng-a 0)
              server-b (engine/start! eng-b 0)
              h-a (engine/handler eng-a)
              h-b (engine/handler eng-b)
              json (fn [resp] (some-> (:body resp) wire/read-json))]
          (try
            (let [{row :row} (inv/create! eng-a :rpad {:title "Week plan"}
                                          {:principal elena})
                  pid (:id row)
                  _ (Thread/sleep 500) ; both dispatchers past the create
                  uri (fn [server]
                        (str "ws://127.0.0.1:" (http/server-port server)
                             "/api/rpads/" pid "/-/revise/draft/collab"))
                  alice (ws-connect (uri server-a) "alice")
                  _ (is (some? (await-frame alice #(= "state" (:type %)))))
                  bob (ws-connect (uri server-b) "bob")
                  _ (is (some? (await-frame bob #(= "state" (:type %)))))]

              (testing "presence rosters merge across processes"
                (is (some? (await-frame alice #(and (= "presence" (:type %))
                                                    (= #{"alice" "bob"}
                                                       (participant-ids %)))))
                    "alice (engine A) hears bob's join on engine B")
                (is (some? (await-frame bob #(and (= "presence" (:type %))
                                                  (= #{"alice" "bob"}
                                                     (participant-ids %)))))
                    "bob learns alice via the introduction round trip")
                (is (= #{"alice" "bob"} (participant-ids (sync-state bob)))))

              (testing "an accepted set relays: engine A's frame reaches engine B's client"
                (send! alice {:type "set" :field "title"
                              :value "Family week" :rev 0})
                (is (= 1 (:rev (await-frame alice #(= "ack" (:type %))))))
                (let [f (await-frame bob #(= "update" (:type %)))]
                  (is (= "title" (:field f)))
                  (is (= "Family week" (:value f)))
                  (is (= 1 (:rev f)))
                  (is (= "alice" (get-in f [:author :id]))))
                (is (nil? (some #(when (= "update" (:type %)) %)
                                @(:frames alice)))
                    "the origin nonce skips self-delivery"))

              (testing "staleness holds across processes too"
                (send! bob {:type "set" :field "title"
                            :value "Bob's week" :rev 0})
                (is (= 1 (:rev (await-frame bob #(= "stale" (:type %))))))
                (send! bob {:type "set" :field "notes" :value "hello" :rev 0})
                (is (some? (await-frame bob #(and (= "ack" (:type %))
                                                  (= "notes" (:field %))))))
                (is (some? (await-frame alice #(and (= "update" (:type %))
                                                    (= "notes" (:field %)))))))

              (testing "concurrent prose edits on separate engines converge"
                ;; both edit notes ("hello") from base 1, one per engine;
                ;; whichever the row lock admits second gets transformed
                (send! alice {:type "edit" :field "notes" :rev 1
                              :ops [{:insert "A"} {:retain 5}]})
                (send! bob {:type "edit" :field "notes" :rev 1
                            :ops [{:insert "B"} {:retain 5}]})
                (is (some? (await-frame alice #(and (= "ack" (:type %))
                                                    (= "notes" (:field %))))))
                (is (some? (await-frame bob #(and (= "ack" (:type %))
                                                  (= "notes" (:field %))
                                                  (<= 2 (:rev %))))))
                ;; each also hears the other's transformed op
                (is (some? (await-frame alice #(= "edit" (:type %)))))
                (is (some? (await-frame bob #(= "edit" (:type %)))))
                (let [quiesce (fn [c]
                                (loop [tries 0 prev nil]
                                  (let [s (sync-state c)]
                                    (if (or (= prev (:values s)) (> tries 40))
                                      s
                                      (do (Thread/sleep 100)
                                          (recur (inc tries) (:values s)))))))
                      sa (quiesce alice)
                      sb (quiesce bob)]
                  (is (contains? #{"ABhello" "BAhello"}
                                 (get-in sa [:values :notes])))
                  (is (= (:values sa) (:values sb))
                      "both engines' clients read one document")
                  (is (= (:revs sa) (:revs sb)))
                  (is (= 3 (get-in sa [:revs :notes])))
                  (testing "…and both engines' draft rows are that document"
                    (let [va (json (h-a {:request-method :get
                                         :uri (str "/api/rpads/" pid
                                                   "/-/revise/draft")
                                         :headers {"x-waymark-principal" "carol"}}))
                          vb (json (h-b {:request-method :get
                                         :uri (str "/api/rpads/" pid
                                                   "/-/revise/draft")
                                         :headers {"x-waymark-principal" "carol"}}))]
                      (is (= (:values va) (:values vb) (:values sa)))))))

              (testing "an act through engine A regates engine B's room"
                (let [nb (count @(:frames bob))
                      na (count @(:frames alice))
                      etag (get-in (json (h-a {:request-method :get
                                               :uri (str "/api/rpads/" pid)
                                               :headers {}}))
                                   [:meta :etag])
                      resp (h-a {:request-method :post
                                 :uri (str "/api/rpads/" pid "/-/revise")
                                 :headers {"x-waymark-principal" "elena"
                                           "if-match" etag}
                                 :body (wire/write-json {:title "Family week"})})]
                  (is (= 200 (:status resp)))
                  (is (some? (await-frame bob nb #(and (= "regate" (:type %))
                                                       (true? (:gone %)))))
                      "engine B's dispatcher tells its clients the draft is gone")
                  (is (some? (await-frame alice na #(and (= "regate" (:type %))
                                                         (true? (:gone %))))))))

              (testing "a silent origin's roster evicts on the heartbeat clock"
                (let [n (count @(:frames alice))
                      payload (wire/write-json
                               {:origin "phantom" :kind "rpad" :id pid
                                :action "revise"
                                :frame {:type "presence-hb"
                                        :actors [{:id "carol" :type "human"
                                                  :display "Carol"}]}})]
                  (store/with-tx st-a
                    (fn [tx]
                      (jdbc/execute-one!
                       tx ["SELECT pg_notify('waymark10_collab', ?)" payload])))
                  (is (some? (await-frame alice n #(and (= "presence" (:type %))
                                                        (contains?
                                                         (participant-ids %)
                                                         "carol"))))
                      "the phantom origin's roster merges in")
                  (let [n' (count @(:frames alice))]
                    (is (some? (await-frame
                                alice n'
                                #(and (= "presence" (:type %))
                                      (not (contains? (participant-ids %)
                                                      "carol")))))
                        "…and leaves within three silent heartbeats"))))

              (testing "a leave on one engine reaches the other's roster"
                (let [n (count @(:frames alice))]
                  (close! bob)
                  (is (some? (await-frame
                              alice n
                              #(and (= "presence" (:type %))
                                    (not (contains? (participant-ids %)
                                                    "bob"))))))))

              (close! alice))
            (finally
              (engine/stop! eng-a server-a)
              (engine/stop! eng-b server-b))))
        (finally
          (pg/close! st-a)
          (pg/close! st-b))))))
