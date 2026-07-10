(ns waymark10.collab-test
  "Phase-9b acceptance, part 3: live collab. Two websocket clients
  (java.net.http) converge on a shared live draft — sets broadcast to
  the others with monotone revs and the author, sync answers the full
  draft, validation errors answer error frames — then the act
  consumes the draft. Rooms clean up on last disconnect. Suite-local
  kind on a started engine; real Postgres."
  (:require [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [org.httpkit.server :as http]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.net URI)
           (java.net.http HttpClient WebSocket WebSocket$Listener)))

;; ── the world ───────────────────────────────────────────────────────

(r/defhandler revise-pad [row inp _ctx]
  (update row :data merge inp))

(def ^:private pad
  (r/resource
   {:kind :pad
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
              :handler revise-pad
              :display {:label "Revise"}}
     :close {:from #{:open} :to :closed
             :safety {:idempotent true :reversible false :confirm false
                      :one-way "Closed pads stay closed."}}}}))

(def ^:private elena (t/principal {:id "elena" :display "Elena"}))

;; ── ws client sugar ─────────────────────────────────────────────────

(defn- ws-connect
  "One websocket client: {:ws :frames} — frames is the atom of parsed
  incoming messages."
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
  [client pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (some #(when (pred %) %) @(:frames client))
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 50)
            (recur))))))

;; ── the story ───────────────────────────────────────────────────────

(deftest two-clients-converge-then-the-act-consumes
  (let [st (pg/storage db/dsn)]
    (try
      (store/with-tx st
        (fn [tx]
          (doseq [table ["pads" "definitions" "waymark10_transitions"
                         "waymark10_idempotency" "waymark10_drafts"]]
            (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
      (let [eng (engine/engine {:storage st :resources [pad]})
            server (engine/start! eng 0)
            port (http/server-port server)
            h (engine/handler eng)]
        (try
          (let [{row :row} (inv/create! eng :pad {:title "Week plan"}
                                        {:principal elena})
                pid (:id row)
                ws-uri (str "ws://127.0.0.1:" port "/api/pads/" pid
                            "/-/revise/draft/collab")
                alice (ws-connect ws-uri "alice")
                bob (ws-connect ws-uri "bob")]
            (testing "a set broadcasts to the others with rev and author"
              (send! alice {:type "set" :field "title" :value "Family week"})
              (let [f (await-frame bob #(= "update" (:type %)) 10000)]
                (is (some? f))
                (is (= "title" (:field f)))
                (is (= "Family week" (:value f)))
                (is (= 1 (:rev f)))
                (is (= "alice" (get-in f [:author :id]))))
              (is (empty? @(:frames alice)) "the sender hears no echo"))
            (testing "the rev climbs monotonically across authors"
              (send! bob {:type "set" :field "notes" :value "tacos tuesday"})
              (let [f (await-frame alice #(= "update" (:type %)) 10000)]
                (is (= "notes" (:field f)))
                (is (= 2 (:rev f)))
                (is (= "bob" (get-in f [:author :id])))))
            (testing "sync answers the full draft"
              (send! alice {:type "sync"})
              (let [f (await-frame alice #(= "sync" (:type %)) 10000)]
                (is (= {:title "Family week" :notes "tacos tuesday"}
                       (:values f)))
                (is (= 2 (:rev f)))))
            (testing "an unknown field answers an error frame, applies nothing"
              (send! alice {:type "set" :field "evil" :value 1})
              (let [f (await-frame alice #(= "error" (:type %)) 10000)]
                (is (= ["unknown field"] (get-in f [:errors :evil])))))
            (testing "a broken value answers the field's own errors"
              (send! alice {:type "set" :field "title" :value 42})
              (is (some? (await-frame alice
                                      #(and (= "error" (:type %))
                                            (contains? (:errors %) :title))
                                      10000))))
            (testing "the persisted draft is what a plain GET sees"
              (let [resp (h {:request-method :get
                             :uri (str "/api/pads/" pid "/-/revise/draft")
                             :headers {"x-waymark-principal" "carol"}})
                    view (wire/read-json (:body resp))]
                (is (= 200 (:status resp)))
                (is (= {:title "Family week" :notes "tacos tuesday"}
                       (:values view))
                    "shared: a third principal reads the same draft")))
            (testing "the act consumes the converged draft"
              (let [etag (get-in (wire/read-json
                                  (:body (h {:request-method :get
                                             :uri (str "/api/pads/" pid)
                                             :headers {}})))
                                 [:meta :etag])
                    resp (h {:request-method :post
                             :uri (str "/api/pads/" pid "/-/revise")
                             :headers {"x-waymark-principal" "alice"
                                       "if-match" etag}
                             :body (wire/write-json
                                    {:title "Family week"
                                     :notes "tacos tuesday"})})]
                (is (= 200 (:status resp)))
                (is (= "Family week"
                       (get-in (wire/read-json (:body resp)) [:data :title]))))
              (is (= 404 (:status (h {:request-method :get
                                      :uri (str "/api/pads/" pid
                                                "/-/revise/draft")
                                      :headers {"x-waymark-principal" "alice"}})))
                  "consumed in the act's own commit"))
            (testing "a collab route for an unlive draft does not exist"
              (is (= 404 (:status (h {:request-method :get
                                      :uri (str "/api/pads/" pid
                                                "/-/close/draft/collab")
                                      :headers {}})))))
            (testing "rooms clean up on last disconnect"
              (close! alice)
              (close! bob)
              (let [deadline (+ (System/currentTimeMillis) 5000)]
                (loop []
                  (when (and (seq @(:collab-rooms eng))
                             (< (System/currentTimeMillis) deadline))
                    (Thread/sleep 50)
                    (recur))))
              (is (empty? @(:collab-rooms eng)))))
          (finally (engine/stop! eng server))))
      (finally (pg/close! st)))))
