(ns workqueue10.weather-test
  "The hearth thermometer (waymark-tti.1): :weather lived in over the
  REAL ring handler so the whole visibility model runs — humans
  unscoped see every report, agents default-deny read and create only
  through the normal grant machinery (a scope entry naming weather),
  and the one rule the kind owns holds: FIRST PERSON. The engine
  stamps the owner from the principal, and a body naming a FOREIGN
  owner is refused from everyone — agent, human, even a recovery-admin
  human, because unlike dwelling there is no on-behalf path.

  Assertions are order-independent (kaocha randomizes, and the
  deftests share one DB): every test names its own principals and
  never asserts on collection SIZE, only on its own rows' presence and
  ownership. Needs the waymark10_test database; WAYMARK10_TEST_DSN
  overrides.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.weather-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [workqueue10.resources.weather :refer [weather]]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

(def ^:private tables
  ["weathers" "members" "roles" "grants" "approval_requests"
   "attachments" "subscriptions" "jobs" "definitions"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"
   "waymark10_cursors" "waymark10_job_leases"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st :resources [weather]})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar (dwelling_test's idiom) ───────────────────────────

(defn- agent-headers [id]
  {"x-waymark-principal" id "x-waymark-actor-type" "agent"})

(defn- human-headers [id]
  {"x-waymark-principal" id "x-waymark-actor-type" "human"})

(defn- req
  ([method uri headers] (req method uri nil headers))
  ([method uri body headers]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path :headers (or headers {})}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp)
                           (#(if (string? %) % (slurp %)))
                           wire/read-json))
(defn- body-str ^String [resp]
  (let [b (:body resp)] (if (string? b) b (slurp b))))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))

(defn- items [resp] (get-in (json resp) [:data :items]))
(defn- item-owner [it] (get-in it [:fields :owner]))
(defn- item-id [it] (last (str/split (:self it) #"/")))

;; weather is NOT own-surface: an agent reads and creates only through
;; the normal grant machinery. A human offers the grant, the audience
;; agent accepts it, and the agent presents the grant id per request —
;; batch_b_access_test's idiom.
(defn- scoped-agent!
  "Grant-scope an agent for weather ({kind \"weather\" actions
  [\"create\"]} = read+create) and return its request headers."
  [agent-id human-id]
  (let [offer (req :post "/api/grants"
                   {:audience agent-id
                    :scope [{:kind "weather" :actions ["create"]}]}
                   (human-headers human-id))
        gid (id-of offer)]
    (is (= 201 (:status offer)))
    (is (= 200 (:status (req :post (str "/api/grants/" gid "/-/accept")
                             nil (agent-headers agent-id)))))
    (assoc (agent-headers agent-id) "x-waymark-grant" gid)))

;; ── 1. first person: the engine stamps, the body cannot forge ───────

(deftest an-agent-reports-its-own-weather
  (let [wren (scoped-agent! "wren-w1" "colton-w1")
        make (req :post "/api/weathers" {:sky "loud" :note "context pressure"}
                  wren)]
    (testing "the create lands, owner stamped to the agent — body omitted it"
      (is (= 201 (:status make)))
      (is (= "wren-w1" (get-in (json make) [:data :owner])))
      (is (= "loud" (get-in (json make) [:data :sky]))))
    (testing "naming its OWN id explicitly is the same report"
      (let [r (req :post "/api/weathers" {:owner "wren-w1" :sky "steady"} wren)]
        (is (= 201 (:status r)))
        (is (= "wren-w1" (get-in (json r) [:data :owner])))))))

(deftest a-foreign-owner-is-refused-from-everyone
  (let [wren (scoped-agent! "wren-w2" "colton-w2")]
    (testing "an agent naming a DIFFERENT owner is refused"
      (let [r (req :post "/api/weathers"
                   {:owner "someone-else" :sky "quiet"} wren)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))
    (testing "a plain human naming another's weather is refused"
      (let [r (req :post "/api/weathers"
                   {:owner "wren-w2" :sky "quiet"} (human-headers "mallory-w2"))]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))
    (testing "even a recovery-admin human — there is NO on-behalf path"
      (let [r (req :post "/api/weathers"
                   {:owner "wren-w2" :sky "quiet"}
                   (assoc (human-headers "colton-w2")
                          "x-waymark-roles" "recovery-admin"))]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))
    (testing "no forged report landed in the owner's rows"
      (is (not-any? #(= "quiet" (get-in % [:fields :sky]))
                    (filter #(= "wren-w2" (item-owner %))
                            (items (req :get "/api/weathers?owner=wren-w2"
                                        wren))))))))

(deftest a-human-reports-their-own-weather
  (let [colton (human-headers "colton-w3")
        make (req :post "/api/weathers" {:sky "steady"} colton)]
    (testing "the create lands, owner stamped from the human principal"
      (is (= 201 (:status make)))
      (is (= "colton-w3" (get-in (json make) [:data :owner]))))
    (testing "the human reads it back — household-shared, no grant needed"
      (let [r (req :get (str "/api/weathers/" (id-of make)) colton)]
        (is (= 200 (:status r)))
        (is (= "steady" (get-in (json r) [:data :sky])))))))

;; ── 2. append-only: weather passes, it is not edited ────────────────

(deftest a-second-report-is-a-second-row-and-sorts-newest-first
  (let [colton (human-headers "colton-w4")
        first-r (req :post "/api/weathers" {:sky "loud"} colton)
        second-r (req :post "/api/weathers" {:sky "quiet"} colton)]
    (testing "both reports land as their own rows"
      (is (= 201 (:status first-r)))
      (is (= 201 (:status second-r)))
      (is (not= (id-of first-r) (id-of second-r))))
    (testing "the owner's collection keeps both, newest first (default sort)"
      (let [mine (filterv #(= "colton-w4" (item-owner %))
                          (items (req :get "/api/weathers?owner=colton-w4"
                                      colton)))]
        (is (some #(= (id-of first-r) (item-id %)) mine))
        (is (some #(= (id-of second-r) (item-id %)) mine))
        (is (= (id-of second-r) (item-id (first mine)))
            "current weather = the latest row")))
    (testing "a wrong tap has no edit to reach for — no actions beyond create"
      (let [acts (:actions (json (req :get (str "/api/weathers/"
                                               (id-of second-r)) colton)))]
        (is (not (contains? acts :update)))
        (is (not (contains? acts :retire)))
        (is (not (contains? acts :amend)))))))

;; ── 3. exactly three skies ──────────────────────────────────────────

(deftest a-sky-outside-the-three-is-refused
  (let [colton (human-headers "colton-w5")]
    (doseq [sky ["stormy" "fine" "" "QUIET" "3"]]
      (let [r (req :post "/api/weathers" {:sky sky} colton)]
        (is (>= (:status r) 400) (str "sky " (pr-str sky) " must refuse"))
        (is (not= 201 (:status r)))))))

;; ── 4. household-shared reads: the grant is the agent's whole door ──

(deftest a-weather-scoped-agent-reads-all-weathers
  (let [colton (human-headers "colton-w6")
        cid (id-of (req :post "/api/weathers" {:sky "steady"} colton))
        wren (scoped-agent! "wren-w6" "colton-w6")]
    (testing "the scoped agent sees ANOTHER principal's report — shared, not own-surface"
      (let [r (req :get (str "/api/weathers/" cid) wren)]
        (is (= 200 (:status r)))
        (is (= "colton-w6" (get-in (json r) [:data :owner])))))
    (testing "and the collection carries the human's row too"
      (is (some #(= cid (item-id %)) (items (req :get "/api/weathers" wren)))))))

(deftest an-agent-without-a-weather-scope-gets-nothing
  (let [colton (human-headers "colton-w7")
        cid (id-of (req :post "/api/weathers" {:sky "quiet"} colton))
        bare (agent-headers "flint-w7")
        ;; a grant that names a DIFFERENT kind opens no weather door
        offer (req :post "/api/grants"
                   {:audience "flint-w7"
                    :scope [{:kind "member" :actions []}]}
                   colton)
        gid (id-of offer)
        _ (is (= 201 (:status offer)))
        _ (is (= 200 (:status (req :post (str "/api/grants/" gid "/-/accept")
                                   nil bare))))
        scoped (assoc bare "x-waymark-grant" gid)]
    (testing "a bare agent (no grant) is walled out — 404, not 403"
      (is (= 404 (:status (req :get (str "/api/weathers/" cid) bare))))
      (is (= 404 (:status (req :get "/api/weathers" bare)))))
    (testing "a grant whose scope omits weather is the same wall"
      (is (= 404 (:status (req :get (str "/api/weathers/" cid) scoped))))
      (is (= 404 (:status (req :get "/api/weathers" scoped)))))
    (testing "…and neither door lets a create through"
      (is (not= 201 (:status (req :post "/api/weathers" {:sky "loud"} bare))))
      (is (not= 201 (:status (req :post "/api/weathers" {:sky "loud"} scoped)))))
    (testing "the concealed sky never leaks in a refused body"
      (is (not (str/includes? (body-str (req :get (str "/api/weathers/" cid)
                                            bare))
                              "quiet"))))))
