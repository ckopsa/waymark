(ns workqueue10.demo-seed-test
  "The demo seed itself (docs/spec-demo-clones.md § 1, § 5 item 1),
  loaded where its own kinds are: the factory's kinds over the
  in-memory twin. This is what keeps the seed true as the kinds change:
  a step the law now refuses fails here before it fails a clone's boot.
  The boot step that reads the two variables is here too, given their
  values as arguments.

  The seeded held call is allowed here too (§ 4), as Ada: with no wall
  it ends `failed` with the engine's no-server sentence, and behind a
  wall served in-process it ends `failed` with the wall's sentence.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.demo-seed-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as factory]
            [waymark10.dev :as dev]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.members :as members]
            [waymark10.server.seed :as seed]
            [waymark10.server.store :as store]
            [waymark10.types :as t]
            [waymark10.wire :as wire]
            [workqueue10.main :as main])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io InputStream OutputStream)
           (java.net InetSocketAddress)))

(defn- moves [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind kind :resource-id (str id)} {}))))

(defn- state-of [row] (keyword (name (:state row))))

(deftest the-demo-seed-loads-and-reaches-every-ticket-state-but-in-review
  (let [eng (dev/scratch! (factory/resources) {:name "demo-test"})
        demo (seed/read-seed "demo")
        result (seed/load! eng demo {})
        tickets (dev/rows eng :ticket)
        by-state (frequencies (map state-of tickets))
        declared (set (:states (get (inv/resources eng) :ticket)))]
    (is (true? (:seeded result)))
    (testing "every state the kind declares but in_review, which only a change's submit reaches"
      (is (contains? declared :in_review))
      (is (= (disj declared :in_review) (set (keys by-state))))
      (is (every? #(<= 2 %) (vals by-state)) (pr-str by-state)))
    (testing "each ticket reached its state by the doors"
      (doseq [t tickets]
        (is (seq (moves eng :ticket (:id t))) (get-in t [:data :title]))))
    (testing "two households, as two groups of members"
      (is (= 2 (count (set (map :household (vals (:cast demo)))))))
      (doseq [m (vals (:cast demo))]
        (is (some? (dev/row eng :member (:id m))) (:id m))))
    (testing "one held call waits on a person's tap"
      (let [held (dev/rows eng :held_call)]
        (is (= [:held] (mapv state-of held)))
        (is (= "plan" (get-in (first held) [:data :caller])))))
    (testing "one open invitation, to the member the person signs in as"
      (let [invited (dev/rows eng :invitation)]
        (is (= [:open] (mapv state-of invited)))
        (is (= "ada" (get-in (first invited) [:data :subject])))))
    (testing "the household's agent authored it, wearing the grant the seed minted"
      (let [invited (first (dev/rows eng :invitation))
            author (get-in invited [:data :author])
            granted (filter #(= author (get-in % [:data :audience]))
                            (dev/rows eng :grant))]
        (is (= "plan" author))
        (is (= "agent" (get-in (dev/row eng :member author) [:data :actor_type])))
        (is (= [:accepted] (mapv state-of granted)))))
    (testing "a restarted task does not seed twice"
      (is (false? (:seeded (seed/load! eng demo {}))))
      (is (= (count tickets) (count (dev/rows eng :ticket)))))))

;; ── the boot step ───────────────────────────────────────────────────

(def ^:private seed-on-boot! @#'main/seed-on-boot!)

(def ^:private factory-refusal
  "WAYMARK10_SEED is set and FACTORY10 is not 1: the seed's tickets need the factory kinds, so a demo engine boots with FACTORY10=1.")

(defn- booted
  "What `seed/boot!` was called with while `f` ran: a vector of
  [engine seed-name opts] triples, the seed itself never loaded."
  [f]
  (let [calls (atom [])]
    (with-redefs [seed/boot! (fn [eng seed-name opts]
                               (swap! calls conj [eng seed-name opts])
                               nil)]
      (f))
    @calls))

(deftest a-seed-without-the-factory-kinds-refuses-the-boot
  (doseq [factory [nil "" "0" "true"]]
    (testing (pr-str factory)
      (let [thrown (atom nil)
            calls (booted #(try (seed-on-boot! ::eng "demo" factory nil)
                                (catch clojure.lang.ExceptionInfo e
                                  (reset! thrown e))))]
        (is (some? @thrown))
        (is (= factory-refusal (some-> @thrown ex-message)))
        (is (= {:seed "demo"} (some-> @thrown ex-data)))
        (is (= [] calls) "the refusal comes before the seed is read")))))

(deftest no-seed-named-does-nothing
  (doseq [seed [nil ""]
          factory [nil "1"]]
    (testing (pr-str [seed factory])
      (let [answer (atom ::unset)
            calls (booted #(reset! answer (seed-on-boot! ::eng seed factory nil)))]
        (is (nil? @answer))
        (is (= [] calls))))))

(deftest a-seed-with-the-factory-kinds-boots-it-by-name
  (testing "with no wall named"
    (doseq [wall [nil ""]]
      (is (= [[::eng "demo" {:wall-url nil}]]
             (booted #(seed-on-boot! ::eng "demo" "1" wall))))))
  (testing "with the clone's wall"
    (is (= [[::eng "demo" {:wall-url "http://wall.test"}]]
           (booted #(seed-on-boot! ::eng "demo" "1" "http://wall.test"))))))

;; ── allowing the seeded held call (§ 4) ─────────────────────────────

(defn- ada
  "Ada as the identity gate hands her over: the seeded member's id and
  the roles her own row holds."
  [eng]
  (t/principal {:id "ada" :display "Ada Example"
                :roles (members/held-roles eng "ada")}))

(defn- allow-as-ada!
  "Ada's tap on the one seeded held call, and the wire-boundary effect
  the router walks after it. → the row afterwards."
  [eng]
  (let [id (str (:id (first (dev/rows eng :held_call))))
        rdef (get (inv/resources eng) :held_call)
        out (inv/invoke! eng :held_call id :allow {} {:principal (ada eng)})]
    (held/after-allow! eng rdef :allow out)
    (first (dev/rows eng :held_call))))

(def ^:private wall-sentence "The demo sends no mail.")

(defn- wall!
  "An in-process wall: every request answers 403 with a JSON-RPC error
  that carries the wall's sentence. → {:url :hits :stop}."
  []
  (let [hits (atom 0)
        ^bytes body (.getBytes ^String (wire/write-json
                                        {:jsonrpc "2.0" :id nil
                                         :error {:code -32000
                                                 :message wall-sentence}})
                               "UTF-8")
        ^HttpServer server (HttpServer/create
                            (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [^HttpExchange exchange exchange]
                          (swap! hits inc)
                          (with-open [^InputStream in (.getRequestBody exchange)]
                            (.readAllBytes in))
                          (.add (.getResponseHeaders exchange)
                                "Content-Type" "application/json")
                          (.sendResponseHeaders exchange 403 (long (alength body)))
                          (with-open [^OutputStream out (.getResponseBody exchange)]
                            (.write out body))))))
    (.start server)
    {:url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/mcp/")
     :hits hits
     :stop #(.stop server 0)}))

(deftest allowing-the-seeded-held-call-with-no-wall-fails-with-the-no-server-sentence
  (let [eng (dev/scratch! (factory/resources) {:name "demo-test"})
        _ (seed/load! eng (seed/read-seed "demo") {})
        servers (dev/rows eng :mcp_server)
        row (allow-as-ada! eng)]
    (testing "with no wall named, the seed makes no server row"
      (is (empty? servers)))
    (is (= :failed (state-of row)))
    (is (= "Not found: No power \"mail__send\"." (get-in row [:data :reason])))
    (is (nil? (get-in row [:data :answer])))))

(deftest allowing-the-seeded-held-call-behind-a-wall-fails-with-the-walls-sentence
  (let [{:keys [url hits stop]} (wall!)]
    (try
      (let [eng (dev/scratch! (factory/resources) {:name "demo-test"})
            _ (seed/load! eng (seed/read-seed "demo") {:wall-url url})
            servers (dev/rows eng :mcp_server)
            before @hits
            row (allow-as-ada! eng)]
        (testing "the seed makes one server row, at the wall"
          (is (= ["mail"] (mapv #(get-in % [:data :name]) servers)))
          (is (= url (get-in (first servers) [:data :url]))))
        (is (= :failed (state-of row)))
        (is (str/includes? (str (get-in row [:data :reason])) wall-sentence)
            (pr-str (get-in row [:data :reason])))
        (is (nil? (get-in row [:data :answer])))
        (is (< before @hits) "the allow reached the wall"))
      (finally (stop)))))
