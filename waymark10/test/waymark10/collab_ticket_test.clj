(ns waymark10.collab-ticket-test
  "Batch-D follow-up: identity over the collab socket. A browser's
  WebSocket cannot send the headers wrap-identity reads, so the door
  takes a one-time ticket minted by the authenticated HTTP session
  (POST /api/-/collab-ticket) and presented as ?ticket= on the join
  URL. Pinned here: the minted ticket joins as its principal (the
  state frame's roster, the presence join, per-field :authors all
  carry the real name); a spent or expired ticket refuses 401 BEFORE
  the upgrade; an anonymous mint refuses 422; and the ticket-less
  joins — dev header, or nothing at all — behave exactly as before
  (the anonymous join stays possible where it always was).

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

(r/defhandler revise-tpad [row inp _ctx]
  (update row :data merge inp))

(def ^:private tpad
  (r/resource
   {:kind :tpad
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
              :handler revise-tpad
              :display {:label "Revise"}}
     :close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Closed pads stay closed."}}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

(def ^:private tables
  ["tpads" "definitions" "members" "roles" "grants" "attachments"
   "subscriptions" "jobs"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"
   "waymark10_cursors" "waymark10_job_leases"])

;; ── ws client sugar (batch-d-collab-test's, headers optional) ───────

(defn- ws-connect
  "One websocket client: {:ws :frames}. principal-id nil connects
  bare — the browser's case, no header at all."
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
        builder (cond-> (.newWebSocketBuilder (HttpClient/newHttpClient))
                  principal-id (.header "x-waymark-principal" principal-id))
        ws (.join (.buildAsync builder (URI. uri) listener))]
    {:ws ws :frames frames}))

(defn- send! [client msg]
  (.join (.sendText ^WebSocket (:ws client) ^String (wire/write-json msg) true)))

(defn- close! [client]
  (try (.join (.sendClose ^WebSocket (:ws client) WebSocket/NORMAL_CLOSURE ""))
       (catch Exception _ nil)))

(defn- await-frame
  ([client pred] (await-frame client pred 10000))
  ([client pred timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (or (some #(when (pred %) %) @(:frames client))
           (when (< (System/currentTimeMillis) deadline)
             (Thread/sleep 25)
             (recur)))))))

;; ── the story ───────────────────────────────────────────────────────

(deftest tickets-name-the-socket
  (let [st (pg/storage dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table tables]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (let [eng (assoc (engine/engine {:storage st :resources [tpad]
                                       :events-poll-ms 200})
                       :collab-heartbeat-ms 1000)
            server (engine/start! eng 0)
            port (http/server-port server)
            h (engine/handler eng)
            json (fn [resp] (some-> (:body resp) wire/read-json))
            mint! (fn [headers]
                    (h {:request-method :post
                        :uri "/api/-/collab-ticket"
                        :headers headers}))]
        (try
          (let [{row :row} (inv/create! eng :tpad {:title "Week plan"}
                                        {:principal elena})
                pid (:id row)
                _ (Thread/sleep 500) ; the dispatcher past the create
                ws-uri (str "ws://127.0.0.1:" port "/api/tpads/" pid
                            "/-/revise/draft/collab")]

            (testing "the authenticated session mints; anonymous cannot"
              (let [resp (mint! {"x-waymark-principal" "elena"})]
                (is (= 200 (:status resp)))
                (let [b (json resp)]
                  (is (string? (:ticket b)))
                  (is (string? (:expires_at b)))))
              (is (= 422 (:status (mint! {})))))

            (testing "a ticket join carries the real principal: roster,
                      presence, and per-field authors all name elena"
              (let [alice (ws-connect ws-uri "alice")
                    _ (is (some? (await-frame alice #(= "state" (:type %)))))
                    tk (:ticket (json (mint! {"x-waymark-principal" "elena"})))
                    elena-ws (ws-connect (str ws-uri "?ticket=" tk) nil)]
                (let [f (await-frame elena-ws #(= "state" (:type %)))]
                  (is (some? f))
                  (is (some #(= "elena" (:id %)) (:participants f))
                      "the joiner's own roster names her"))
                (let [f (await-frame alice #(and (= "presence" (:type %))
                                                 (= "joined" (:event %))))]
                  (is (some? f))
                  (is (= "elena" (get-in f [:actor :id]))
                      "the room's presence join names the ticket's principal"))
                (send! elena-ws {:type "set" :field "title"
                                 :value "Family week" :rev 0})
                (let [f (await-frame alice #(= "update" (:type %)))]
                  (is (some? f))
                  (is (= "elena" (get-in f [:author :id]))
                      "the accepted frame's author is the ticket's principal"))
                (let [view (json (h {:request-method :get
                                     :uri (str "/api/tpads/" pid
                                               "/-/revise/draft")
                                     :headers {"x-waymark-principal" "carol"}}))]
                  (is (= "elena" (get-in view [:authors :title :id]))
                      "the draft document's authors carry the name"))

                (testing "…and the ticket is one-time: the spent nonce
                          refuses 401 before any upgrade"
                  (let [resp (h {:request-method :get
                                 :uri (str "/api/tpads/" pid
                                           "/-/revise/draft/collab")
                                 :query-string (str "ticket=" tk)
                                 :headers {}})]
                    (is (= 401 (:status resp)))
                    (is (= "application/problem+json"
                           (get-in resp [:headers "Content-Type"])))))

                (close! elena-ws)
                (close! alice)))

            (testing "an expired ticket refuses 401"
              (let [h' (engine/handler (assoc eng :collab-ticket-ttl-ms 1))
                    tk (:ticket (json (h' {:request-method :post
                                           :uri "/api/-/collab-ticket"
                                           :headers {"x-waymark-principal" "elena"}})))]
                (Thread/sleep 20)
                (is (= 401 (:status (h {:request-method :get
                                        :uri (str "/api/tpads/" pid
                                                  "/-/revise/draft/collab")
                                        :query-string (str "ticket=" tk)
                                        :headers {}}))))))

            (testing "no ticket, dev header: the join names the header's
                      principal — exactly as before"
              (let [bob (ws-connect ws-uri "bob")]
                (let [f (await-frame bob #(= "state" (:type %)))]
                  (is (some? f))
                  (is (some #(= "bob" (:id %)) (:participants f))))
                (close! bob)))

            (testing "no ticket, no header: the anonymous join is still
                      possible where it always was"
              (let [ghost (ws-connect ws-uri nil)]
                (let [f (await-frame ghost #(= "state" (:type %)))]
                  (is (some? f))
                  (is (some #(= "anonymous" (:id %)) (:participants f))))
                (close! ghost))))
          (finally (engine/stop! eng server))))
      (finally (pg/close! st)))))
