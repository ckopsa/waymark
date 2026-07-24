(ns waymark10.intents-test
  "Intent frames acceptance: the considering/asking surface, presence's
  ephemeral twin. Registry level first (no HTTP): two registries over
  one database — two processes — see each other's considerings open,
  expire on the TTL clock, abandon through either process, and hear
  an ask linger past the considering TTL until a human's answer
  restamps it; a crashed peer's asks leave on the clock. Then the
  wire: the dry-run door (a dry-run IS a considering), the warning
  wall door (the refusal IS the ask, the guard's own sentence the
  question), the acknowledged retry resolving the card through the
  E1 machinery, the explicit report/abandon doors, the scoped
  stream's byte-level absences, and the never-started engine's 503.

  Needs the waymark10_intents_test database (its own, never the
  suite's):
    WAYMARK10_INTENTS_DSN=jdbc:postgresql://localhost:5433/waymark10_intents_test?user=ckopsa"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [org.httpkit.server :as http]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.intents :as intents]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.io BufferedReader InputStream InputStreamReader)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)))

(def ^:private dsn
  (or (System/getenv "WAYMARK10_INTENTS_DSN")
      "jdbc:postgresql://localhost:5433/waymark10_intents_test?user=ckopsa"))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private calendar-conflict
  (g/expr {:name :calendar-conflict
           :severity :warning
           :when '(not (data :has_conflict))
           :explain "1 calendar conflict (recital, Thursday)."}))

(def ^:private widget
  (r/resource
   {:kind :int_widget
    :plural "int_widgets"
    :states [:idle :spun]
    :initial :idle
    :terminal #{:spun}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:min 1 :max 40}]]
             [:has_conflict {:optional true} [:maybe :boolean]]]
    :actions {:finalize {:from #{:idle} :to :idle
                         :guards [calendar-conflict]
                         :safety {:idempotent true :reversible true
                                  :confirm false}}
              :spin {:from #{:idle} :to :spun
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "Spun is history."}}
              ;; the bulk door (§23): its dry-run reports a considering
              ;; too — one card, the collection as self
              :spin_many {:from #{:idle} :to :spun
                          :bulk {:max-items 10}
                          :safety {:idempotent true :reversible false
                                   :confirm false
                                   :one-way "Spun is history."}}}}))

(def ^:private tables
  ["int_widgets" "definitions" "members" "roles" "grants"
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

(def ^:private sous (t/principal {:id "sous" :type :agent :display "Sous"}))
(def ^:private priya (t/principal {:id "priya" :display "Priya"}))
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
           (let [f (intents/take-frame sub remaining)]
             (cond
               (nil? f) nil
               (keyword? f) nil
               (pred f) f
               :else (recur)))))))))

;; ── registry level: two processes, one truth ────────────────────────

(deftest intents-cross-processes-expire-and-answer
  (fresh!)
  (let [st-a (pg/storage dsn)
        st-b (pg/storage dsn)]
    (try
      (let [eng-a (engine/engine {:storage st-a :resources [widget]})
            eng-b (engine/engine {:storage st-b :resources [widget]})
            reg-a (intents/start! eng-a {:hb-ms 200 :ttl-ms 700 :ask-ttl-ms 8000})
            reg-b (intents/start! eng-b {:hb-ms 200 :ttl-ms 700 :ask-ttl-ms 8000})
            sub-b (intents/subscribe reg-b nil)]
        (try
          (testing "a considering on process A opens on process B"
            (intents/report! reg-a sous {:self "/api/int_widgets/w1"
                                         :action :assign})
            (let [f (next-frame sub-b #(= "open" (:event %)))]
              (is (some? f))
              (is (= "sous" (get-in f [:principal :id])))
              (is (= "Sous" (get-in f [:principal :display])))
              (is (= "considering" (:status f)))
              (is (= "assign" (:action f)))
              (is (= "/api/int_widgets/w1" (:self f)))))

          (testing "an unrefreshed considering vanishes on the TTL clock"
            (let [f (next-frame sub-b #(= "close" (:event %)))]
              (is (some? f))
              (is (= "expired" (:outcome f)))))

          (testing "an abandon through EITHER process closes the card"
            (intents/report! reg-a sous {:self "/api/int_widgets/w1"
                                         :action :assign})
            (is (some? (next-frame sub-b #(= "open" (:event %)))))
            (intents/abandon! reg-b sous {:self "/api/int_widgets/w1"
                                          :action :assign})
            (let [f (next-frame sub-b #(= "close" (:event %)))]
              (is (some? f))
              (is (= "abandoned" (:outcome f)))))

          (testing "an ask lingers past the considering TTL — a pending
                    gate is an intent that waits to be answered"
            (intents/report! reg-a sous
                             {:self "/api/int_widgets/w2"
                              :action :finalize
                              :question "1 calendar conflict. Proceed?"
                              :warnings [{:name :calendar-conflict
                                          :reason "1 calendar conflict."}]
                              :acknowledge {:names [:calendar-conflict]}})
            (let [f (next-frame sub-b #(and (= "open" (:event %))
                                            (= "asking" (:status %))))]
              (is (some? f))
              (is (= "1 calendar conflict. Proceed?" (:question f)))
              (is (= ["calendar-conflict"] (get-in f [:acknowledge :names]))))
            (Thread/sleep 1500)   ; several sweeps past ttl-ms
            (is (some #(= "asking" (:status %))
                      (intents/snapshot reg-b (constantly true)))
                "the ask outlived the considering TTL"))

          (testing "the answer, given on process B, updates process A's
                    viewers — names default to the ask's own"
            (let [sub-a (intents/subscribe reg-a nil)]
              (try
                (intents/answer! reg-b priya
                                 {:id "sous:finalize@/api/int_widgets/w2"}
                                 nil)
                (let [f (next-frame sub-a #(= "update" (:event %)))]
                  (is (some? f))
                  (is (= "answered" (:status f)))
                  (is (= "priya" (get-in f [:answer :by :id])))
                  (is (= ["calendar-conflict"] (get-in f [:answer :names]))))
                (finally (intents/unsubscribe reg-a sub-a)))))

          (testing "a crashed peer's asks leave on the clock"
            (intents/report! reg-a sous {:self "/api/int_widgets/w3"
                                         :action :finalize
                                         :question "Proceed?"})
            (is (some? (next-frame sub-b #(and (= "open" (:event %))
                                               (= "/api/int_widgets/w3" (:self %))))))
            (intents/stop! reg-a)          ; no clean drop — a crash
            (is (some? (next-frame sub-b #(and (= "close" (:event %))
                                               (= "/api/int_widgets/w3" (:self %)))))
                "process B evicts the silent origin's cards itself"))
          (finally
            (intents/stop! reg-a)
            (intents/stop! reg-b))))
      (finally
        (pg/close! st-a)
        (pg/close! st-b)))))

;; ── the wire: the doors, the concealed stream, the 503 ─────────────

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

(defn- await-line [lines pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (some #(when (pred %) %) @lines)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 100)
            (recur))))))

(deftest intents-on-the-wire
  (fresh!)
  (let [st (pg/storage dsn)]
    (try
      (let [eng (engine/engine {:storage st :resources [widget]
                                :events-poll-ms 200
                                :sse-heartbeat-ms 500
                                :intents-heartbeat-ms 300
                                :intent-ttl-ms 60000})
            server (engine/start! eng 0)
            port (http/server-port server)
            h (engine/handler eng)]
        (try
          (let [w1 (get-in (inv/create! eng :int_widget
                                        {:name "granted" :has_conflict true}
                                        {:principal priya})
                           [:row :id])
                w2 (get-in (inv/create! eng :int_widget
                                        {:name "concealed" :has_conflict false}
                                        {:principal priya})
                           [:row :id])
                self1 (str "/api/int_widgets/" w1)
                ;; the agent default (waymark-rci): sous rehearses
                ;; UNDER A GRANT — an ungranted agent's dry-run is a
                ;; probe of concealed law and answers 404 now
                sous-grant (get-in (inv/create!
                                    eng :grant
                                    {:audience "sous"
                                     :scope [{:kind "int_widget"
                                              :actions ["spin" "create"
                                                        "spin_many"
                                                        "finalize"]}]}
                                    {:principal priya})
                                   [:row :id])
                _ (inv/invoke! eng :grant sous-grant :accept {}
                               {:principal sous})
                sous-headers {"x-waymark-principal" "sous"
                              "x-waymark-actor-type" "agent"
                              "x-waymark-grant" (str sous-grant)}
                watcher (sse-lines port "/api/-/intents"
                                   {"x-waymark-principal" "watcher"})]

            (testing "beat 3: a dry-run IS a considering on the stream"
              (let [resp (h {:request-method :post
                             :uri (str "/api/int_widgets/" w1 "/-/spin")
                             :query-string "dry_run=1"
                             :headers sous-headers})]
                (is (= 200 (:status resp)))
                (is (true? (:valid (wire/read-json (:body resp))))))
              (is (some? (await-line (:lines watcher)
                                     #(and (str/starts-with? % "data:")
                                           (str/includes? % "considering")
                                           (str/includes? % "\"sous\"")
                                           (str/includes? % "\"spin\""))
                                     10000))))

            (testing "beat 3 at the other doors (§23): the create and
                      bulk rehearsals report too — one card per door,
                      the collection as self"
              (let [resp (h {:request-method :post
                             :uri "/api/int_widgets"
                             :query-string "dry_run=1"
                             :headers sous-headers
                             :body (wire/write-json {:name "pondered"})})]
                (is (= 200 (:status resp)))
                (is (true? (:valid (wire/read-json (:body resp))))))
              (is (some? (await-line (:lines watcher)
                                     #(and (str/includes? % "considering")
                                           (str/includes? % "\"create\"")
                                           (str/includes? % "\"/api/int_widgets\""))
                                     10000))
                  "the create door's card names the collection")
              (let [resp (h {:request-method :post
                             :uri "/api/int_widgets/-/spin_many"
                             :query-string "dry_run=1"
                             :headers sous-headers
                             :body (wire/write-json {:ids [w2]})})]
                (is (= 200 (:status resp)))
                (is (true? (:valid (wire/read-json (:body resp))))))
              (is (some? (await-line (:lines watcher)
                                     #(and (str/includes? % "considering")
                                           (str/includes? % "\"spin_many\""))
                                     10000))
                  "the bulk door's card rides the same stream"))

            (testing "beat 5: the warning wall IS the ask — the guard's
                      own sentence, addressed to whoever can see"
              (let [resp (h {:request-method :post
                             :uri (str "/api/int_widgets/" w1 "/-/finalize")
                             :headers sous-headers})]
                (is (= 409 (:status resp)))
                (is (str/includes? (:body resp) "Waymark-Acknowledge")))
              (let [l (await-line (:lines watcher)
                                  #(and (str/includes? % "\"asking\"")
                                        (str/includes? % "recital"))
                                  10000)]
                (is (some? l))
                (when l
                  (let [f (wire/read-json (str/trim (subs l 5)))]
                    (is (= "1 calendar conflict (recital, Thursday)."
                           (:question f)))
                    (is (= ["calendar-conflict"]
                           (get-in f [:acknowledge :names])))))))

            (testing "the human's answer rides back down the stream"
              (is (= 204 (:status (h {:request-method :post
                                      :uri "/api/-/intents/answer"
                                      :headers {"x-waymark-principal" "priya"}
                                      :body (wire/write-json
                                             {:id (str "sous:finalize@" self1)})}))))
              (is (some? (await-line (:lines watcher)
                                     #(and (str/includes? % "\"answered\"")
                                           (str/includes? % "\"priya\""))
                                     10000))))

            (testing "the acknowledged retry (E1, the one path) lands
                      the act and resolves the ask"
              (let [resp (h {:request-method :post
                             :uri (str "/api/int_widgets/" w1 "/-/finalize")
                             :headers (assoc sous-headers
                                             "waymark-acknowledge"
                                             "calendar-conflict")})]
                (is (= 200 (:status resp))))
              (is (some? (await-line (:lines watcher)
                                     #(and (str/includes? % "\"close\"")
                                           (str/includes? % "\"resolved\"")
                                           (str/includes? % "finalize"))
                                     10000))))

            (testing "the real act resolves the considering too"
              (let [resp (h {:request-method :post
                             :uri (str "/api/int_widgets/" w1 "/-/spin")
                             :headers sous-headers})]
                (is (= 200 (:status resp))))
              (is (some? (await-line (:lines watcher)
                                     #(and (str/includes? % "\"close\"")
                                           (str/includes? % "\"resolved\"")
                                           (str/includes? % "\"spin\""))
                                     10000))))

            (testing "the explicit doors: report opens, abandon closes"
              (is (= 204 (:status (h {:request-method :post
                                      :uri "/api/-/intents"
                                      :headers sous-headers
                                      :body (wire/write-json
                                             {:self self1 :action "repaint"})}))))
              (is (some? (await-line (:lines watcher)
                                     #(and (str/includes? % "\"open\"")
                                           (str/includes? % "\"repaint\""))
                                     10000)))
              (is (= 204 (:status (h {:request-method :post
                                      :uri "/api/-/intents/abandon"
                                      :headers sous-headers
                                      :body (wire/write-json
                                             {:self self1 :action "repaint"})}))))
              (is (some? (await-line (:lines watcher)
                                     #(and (str/includes? % "\"abandoned\"")
                                           (str/includes? % "\"repaint\""))
                                     10000))))

            (testing "refusals: anonymous reports, malformed selves, an
                      answer to nothing, an answer to a considering"
              (is (= 422 (:status (h {:request-method :post
                                      :uri "/api/-/intents"
                                      :headers {}
                                      :body (wire/write-json
                                             {:self self1 :action "spin"})}))))
              (is (= 422 (:status (h {:request-method :post
                                      :uri "/api/-/intents"
                                      :headers sous-headers
                                      :body (wire/write-json
                                             {:self "not-an-href"
                                              :action "spin"})}))))
              (is (= 404 (:status (h {:request-method :post
                                      :uri "/api/-/intents/answer"
                                      :headers {"x-waymark-principal" "priya"}
                                      :body (wire/write-json
                                             {:id "nobody:nothing@/api/int_widgets/x"})}))))
              ;; a considering is not a question
              (is (= 204 (:status (h {:request-method :post
                                      :uri "/api/-/intents"
                                      :headers sous-headers
                                      :body (wire/write-json
                                             {:self self1 :action "polish"})}))))
              (is (= 409 (:status (h {:request-method :post
                                      :uri "/api/-/intents/answer"
                                      :headers {"x-waymark-principal" "priya"}
                                      :body (wire/write-json
                                             {:id (str "sous:polish@" self1)})})))))

            (testing "concealment: a scoped stream never names an
                      ungranted self — byte-level absence"
              ;; the grant: spy sees w1 and nothing else
              (let [gid (get-in (inv/create!
                                 eng :grant
                                 {:audience "spy"
                                  :scope [{:kind "int_widget"
                                           :ids [w1] :actions []}]}
                                 {:principal priya})
                                [:row :id])]
                (inv/invoke! eng :grant gid :accept nil {:principal spy})
                ;; intents before the scoped stream opens (the
                ;; snapshot path) …
                (h {:request-method :post
                    :uri "/api/-/intents"
                    :headers {"x-waymark-principal" "quinn"}
                    :body (wire/write-json {:self (str "/api/int_widgets/" w2)
                                            :action "polish"})})
                (Thread/sleep 300)
                (let [scoped (sse-lines port "/api/-/intents"
                                        {"x-waymark-principal" "spy"
                                         "x-waymark-grant" gid})]
                  (is (some? (await-line (:lines scoped)
                                         #(str/includes? % "\"sous\"")
                                         10000))
                      "the granted self's intent is on the scoped stream")
                  ;; … and after it opened (the live path)
                  (h {:request-method :post
                      :uri "/api/-/intents"
                      :headers {"x-waymark-principal" "nadia"}
                      :body (wire/write-json {:self (str "/api/int_widgets/" w2)
                                              :action "polish"})})
                  (Thread/sleep 1500)
                  (let [bytes' (str/join "\n" @(:lines scoped))]
                    (is (str/includes? bytes' w1))
                    (is (not (str/includes? bytes' "quinn"))
                        "a snapshot intent on an ungranted self is absent")
                    (is (not (str/includes? bytes' "nadia"))
                        "a live intent on an ungranted self is absent")
                    (is (not (str/includes? bytes' w2))
                        "the concealed row's id never crosses the wire"))
                  (testing "…and answering a concealed ask is the same
                            404 as answering none"
                    (is (= 404 (:status
                                (h {:request-method :post
                                    :uri "/api/-/intents/answer"
                                    :headers {"x-waymark-principal" "spy"
                                              "x-waymark-grant" gid}
                                    :body (wire/write-json
                                           {:id (str "quinn:polish@/api/int_widgets/"
                                                     w2)})})))))
                  (.close ^InputStream (:body scoped))
                  (future-cancel (:reader scoped)))))

            (.close ^InputStream (:body watcher))
            (future-cancel (:reader watcher)))
          (finally (engine/stop! eng server))))
      (finally (pg/close! st)))))

;; ── engines without start! answer 503, the SSE discipline ──────────

(deftest intents-without-start-is-503
  (fresh!)
  (let [st (pg/storage dsn)]
    (try
      (let [eng (engine/engine {:storage st :resources [widget]})
            h (engine/handler eng)]
        (doseq [req [{:request-method :get :uri "/api/-/intents" :headers {}}
                     {:request-method :post :uri "/api/-/intents"
                      :headers {"x-waymark-principal" "sous"}
                      :body (wire/write-json {:self "/api/int_widgets/x"
                                              :action "spin"})}
                     {:request-method :post :uri "/api/-/intents/abandon"
                      :headers {"x-waymark-principal" "sous"}
                      :body (wire/write-json {:self "/api/int_widgets/x"
                                              :action "spin"})}
                     {:request-method :post :uri "/api/-/intents/answer"
                      :headers {"x-waymark-principal" "priya"}
                      :body (wire/write-json {:id "sous:spin@/api/int_widgets/x"})}]]
          (let [resp (h req)]
            (is (= 503 (:status resp)))
            (is (= "application/problem+json"
                   (get-in resp [:headers "Content-Type"]))))))
      (finally (pg/close! st)))))
