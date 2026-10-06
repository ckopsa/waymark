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

  The seeded epic's `complete` is pursued here as Ada, step by step:
  that test is the script of the film.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.demo-seed-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [factory10.main :as factory]
            [waymark10.client :as c]
            [waymark10.dev :as dev]
            [waymark10.server.held-calls :as held]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp-client :as client]
            [waymark10.server.mcp-servers :as mcp-servers]
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

;; ── the gate row's boot step ────────────────────────────────────────

(def ^:private gate-row-on-boot! @#'main/gate-row-on-boot!)

(defn- gate-rows-asked
  "What `mcp-servers/ensure-gate-row!` was called with while `f` ran: a
  vector of [engine opts] pairs, no row ever made."
  [f]
  (let [calls (atom [])]
    (with-redefs [mcp-servers/ensure-gate-row! (fn [eng opts]
                                                 (swap! calls conj [eng opts])
                                                 nil)]
      (f))
    @calls))

(deftest a-seeded-boot-makes-no-gate-row
  (testing "whatever the gate url says"
    (doseq [url [nil "" "http://gate.test/mcp/"]]
      (is (= [] (gate-rows-asked #(gate-row-on-boot! ::eng url "demo")))
          (pr-str url))))
  (testing "on an engine with the kind: no row named gate afterwards"
    (let [eng (dev/scratch! (factory/resources) {:name "demo-test"})]
      (is (nil? (gate-row-on-boot! eng "http://gate.test/mcp/" "demo")))
      (is (not-any? #(= "gate" (get-in % [:data :name]))
                    (dev/rows eng :mcp_server))))))

(deftest an-unseeded-boot-makes-the-gate-row-at-the-url-named
  (testing "with no url named, none"
    (doseq [url [nil ""]
            seed [nil ""]]
      (is (= [] (gate-rows-asked #(gate-row-on-boot! ::eng url seed)))
          (pr-str [url seed]))))
  (testing "with a url"
    (doseq [seed [nil ""]]
      (is (= [[::eng {:url "http://gate.test/mcp/"}]]
             (gate-rows-asked
              #(gate-row-on-boot! ::eng "http://gate.test/mcp/" seed)))
          (pr-str seed)))))

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

(defn- wall-answering!
  "An in-process wall: every request answers 403 with `text` as its
  body, under `content-type`. → {:url :hits :stop}."
  [^String text content-type]
  (let [hits (atom 0)
        ^bytes body (.getBytes text "UTF-8")
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
                                "Content-Type" (str content-type))
                          (.sendResponseHeaders exchange 403 (long (alength body)))
                          (with-open [^OutputStream out (.getResponseBody exchange)]
                            (.write out body))))))
    (.start server)
    {:url (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/mcp/")
     :hits hits
     :stop #(.stop server 0)}))

(def ^:private wall-rpc-body
  "The wall's sentence as a JSON-RPC error."
  (wire/write-json {:jsonrpc "2.0" :id nil
                    :error {:code -32000 :message wall-sentence}}))

(defn- wall!
  "An in-process wall: every request answers 403 with a JSON-RPC error
  that carries the wall's sentence. → {:url :hits :stop}."
  []
  (wall-answering! wall-rpc-body "application/json"))

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

;; ── the wall's sentence, from each kind of body ─────────────────────

(defn- refused-by
  "What the http client throws at the wall that answers `text`."
  [text content-type]
  (let [{:keys [url stop]} (wall-answering! text content-type)]
    (try
      ((client/http-client url) "tools/list" {})
      nil
      (catch Exception e e)
      (finally (stop)))))

(deftest the-http-client-reads-the-walls-sentence-from-each-kind-of-body
  (testing "a JSON-RPC error gives its message"
    (let [e (refused-by wall-rpc-body "application/json")]
      (is (= 502 (:status (ex-data e))))
      (is (= {:sentence wall-sentence :context "initialize answered 403"}
             (client/said e)))
      (is (str/includes? (ex-message e)
                         (str "initialize answered 403 " wall-sentence)))))
  (testing "a plain-text body gives its text, trimmed"
    (let [e (refused-by (str "  " wall-sentence "\n") "text/plain")]
      (is (= 502 (:status (ex-data e))))
      (is (= {:sentence wall-sentence :context "initialize answered 403"}
             (client/said e)))))
  (testing "a long text body gives its first 500 characters"
    (let [e (refused-by (apply str (repeat 600 "x")) "text/plain")]
      (is (= (apply str (repeat 500 "x")) (:sentence (client/said e))))))
  (testing "an empty body gives no sentence"
    (let [e (refused-by "" "text/plain")]
      (is (= 502 (:status (ex-data e))))
      (is (nil? (client/said e)))
      (is (str/includes? (ex-message e) "initialize answered 403.")))))

(defn- reason-behind
  "The seeded held call's reason after Ada allows it behind the wall
  that answers `text`."
  [text content-type]
  (let [{:keys [url stop]} (wall-answering! text content-type)]
    (try
      (let [eng (dev/scratch! (factory/resources) {:name "demo-test"})
            _ (seed/load! eng (seed/read-seed "demo") {:wall-url url})
            row (allow-as-ada! eng)]
        (is (= :failed (state-of row)))
        (str (get-in row [:data :reason])))
      (finally (stop)))))

(deftest the-seeded-held-calls-reason-starts-with-the-walls-sentence
  (let [expected (str wall-sentence
                      " [server mail is dark: initialize answered 403]")]
    (testing "behind a wall that answers a JSON-RPC error"
      (is (= expected (reason-behind wall-rpc-body "application/json"))))
    (testing "behind a wall that answers plain text"
      (is (= expected (reason-behind wall-sentence "text/plain")))))
  (testing "behind a wall that answers nothing, the engine's own words stand"
    (is (str/includes? (reason-behind "" "text/plain")
                       "initialize answered 403"))))

;; ── the quest: the seeded epic's `complete`, pursued as Ada ─────────

(def ^:private ending {:close_reason "Done: the house planned the dinner."})

(def ^:private film-url "https://films.example/harbour-house-dinner")

(defn- steps [entries] (mapv (juxt :door :row) entries))

(deftest the-seeded-epic-completes-as-a-quest
  (let [eng (dev/scratch! (factory/resources) {:name "demo-test"})
        refs (:refs (seed/load! eng (seed/read-seed "demo") {}))
        id-of (fn [k] (str (get-in refs [k :id])))
        h (dev/handler eng)
        ada (c/connect "http://test" {:principal "ada" :handler h
                                      :grant (id-of :ada-grant)})
        planner (c/connect "http://test" {:principal {:id "plan" :type :agent}
                                          :handler h
                                          :grant (id-of :plan-grant)})
        doc (fn [session k]
              (c/get-doc session
                         (str (get-in (c/index session) [:resources :ticket :href])
                              "/" (id-of k))))
        self (fn [k] (:self (doc ada k)))
        rehearse (fn [choices]
                   (c/pursue! ada (doc ada :q-epic) :complete ending
                              {:dry-run true :choices choices}))
        ;; no id: the refusal binds the child, and a person gives only
        ;; the sentence that child's `complete` asks for
        sentence {"ticket.complete" {:input ending}}
        on (fn [res k] (filterv #(= (self k) (:row %)) (:blocked-on res)))
        ;; a patch: the link alone, and the restate keeps the rest
        linked {:showcase {:evidence {:film_url film-url}}}]
    ;; a rehearsal reads one denier for each door, and the epic's own
    ;; envelope names the evidence first: the children show after it
    (testing "the first step known: the film's link, a restate that needs its showcase"
      (let [res (rehearse nil)]
        (is (:rehearsal res))
        (is (= [] (steps (:writes res))) (pr-str res))
        (is (= [{:door "ticket.restate" :row (self :q-epic) :needs [:showcase]}]
               (mapv #(select-keys % [:door :row :needs]) (:blocked-on res)))
            (pr-str res)))
      (let [res (rehearse {"ticket.restate" {:input linked}})]
        (is (= [["ticket.restate" (self :q-epic)]
                ["ticket.complete" (self :q-epic)]]
               (steps (:writes res)))
            (pr-str res)))
      (is (= film-url
             (get-in (c/act! ada (doc ada :q-epic) :restate (assoc linked :patch true))
                     [:data :showcase :evidence :film_url]))))
    (testing "the next, which no rehearsal could see before: Ada ends the child that is hers"
      (let [res (rehearse nil)]
        (is (= [] (steps (:writes res))) (pr-str res))
        (is (= [{:door "ticket.complete" :row (self :q-guide) :needs [:close_reason]}]
               (mapv #(select-keys % [:door :row :needs])
                     (filter #(= "ticket.complete" (:door %)) (:blocked-on res))))
            (pr-str res)))
      (let [res (rehearse sentence)]
        (is (= [["ticket.complete" (self :q-guide)]
                ["ticket.complete" (self :q-epic)]]
               (steps (:writes res)))
            (pr-str res)))
      (is (= "done" (:state (c/act! ada (doc ada :q-guide) :complete ending)))))
    (testing "the next: the deferred child, which is not Ada's to end"
      (let [res (rehearse sentence)]
        (is (= [] (steps (:writes res))) (pr-str res))
        (is (= (:blocked-on res) (on res :q-seats)) (pr-str res))
        ;; still plain blocked-on entries, and not an `:unseen` door: Ada
        ;; reads the child, and each refusal leaves her no way. The
        ;; child's `complete` is shut by its state and names `resume`,
        ;; the row's own door back to open, which her grant does not
        ;; admit; `drop` names nothing. Neither asks her for an input,
        ;; a tap or a confirm. That is the shape of a step that is a
        ;; seat's (`whose: seat`), waiting on whoever holds `resume`.
        (is (= [{:door "ticket.resume" :needs [] :or []
                 :reason (str "ticket.resume is not afforded on " (self :q-seats) ".")}
                {:door "ticket.drop" :needs [] :or ["ticket.complete"]
                 :reason (str "ticket.drop is not afforded on " (self :q-seats) ".")}]
               (mapv #(select-keys % [:door :needs :or :reason :confirm :held])
                     (:blocked-on res)))
            (pr-str res)))
      (is (= ["ticket.resume"]
             (get-in (doc ada :q-seats) [:unavailable :complete :remedies])))
      (is (nil? (get-in (doc ada :q-seats) [:actions :resume])))
      (testing "Planner, who holds resume, plans it as resume then complete"
        (is (= [["ticket.resume" (self :q-seats)]
                ["ticket.complete" (self :q-seats)]]
               (steps (:writes (c/pursue! planner (doc planner :q-seats)
                                          :complete ending {:dry-run true}))))))
      (testing "Planner ends it"
        (is (= "open" (:state (c/act! planner (doc planner :q-seats) :resume nil))))
        (is (= "done" (:state (c/act! planner (doc planner :q-seats)
                                      :complete ending))))))
    (testing "the goal is all that is left, and it lands"
      (is (= [["ticket.complete" (self :q-epic)]]
             (steps (:writes (rehearse nil)))))
      (let [res (c/pursue! ada (doc ada :q-epic) :complete ending)]
        (is (c/doc? (:done res)) (pr-str res))
        (is (= "done" (:state (doc ada :q-epic))))))))

;; ── the clone's sign-in for the quest (spec-demo-clones § 2) ────────

(deftest the-clones-sign-in-leaves-the-deferred-child-to-planner
  (let [eng (dev/scratch! (factory/resources) {:name "demo-test"})
        refs (:refs (seed/load! eng (seed/read-seed "demo") {}))
        id-of (fn [k] (str (get-in refs [k :id])))
        h (dev/handler eng)
        doc (fn [session kind k]
              (c/get-doc session
                         (str (get-in (c/index session) [:resources kind :href])
                              "/" (id-of k))))
        ;; the dev principal box: the header and nothing else
        typed (c/connect "http://test" {:principal "ada" :handler h})
        grant (doc typed :grant :ada-grant)
        ;; the grant screen's button: the last segment of the grant's self
        worn (c/connect "http://test" {:principal "ada" :handler h
                                       :grant (last (str/split (:self grant) #"/"))})]
    (testing "the header alone leaves the seat's step open to the person"
      (is (some? (get-in (doc typed :ticket :q-seats) [:actions :resume]))))
    (testing "the grant Ada opens is her own"
      (is (= "ada" (get-in grant [:data :audience]))))
    (testing "acting under it, the deferred child reads no resume door"
      (let [seats (doc worn :ticket :q-seats)]
        (is (= "deferred" (:state seats)))
        (is (nil? (get-in seats [:actions :resume])) (pr-str (:actions seats)))))))
