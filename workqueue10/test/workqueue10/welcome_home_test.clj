(ns workqueue10.welcome-home-test
  "Welcome home (waymark-4zj.2): GET /api/-/welcome hands a RETURNING
  named agent its own self, recent journal, and standing grant — the
  homecoming built into the threshold. Run over the REAL ring handler
  so the whole identity + own-surface model runs.

  The security heart is the SAME as the dwelling kinds: the payload is
  keyed entirely on the authenticated principal id (data.owner == pid
  for self/journal, audience == pid for the grant). This suite's
  adversarial test is the leak check — a DIFFERENT agent's welcome
  must carry NONE of the first agent's self or journal, and no :home
  for rows it does not own. The payload adds no credential: it reads
  for the principal wrap-identity already resolved.

  Assertions are order-independent (kaocha randomizes, deftests share
  one DB): every test names its own rows and asserts on ownership, not
  collection size. Needs the waymark10_test database; WAYMARK10_TEST_DSN
  overrides.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.welcome-home-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [workqueue10.resources.dwelling :refer [self journal]]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

(def ^:private tables
  ["selves" "journals" "members" "roles" "grants" "approval_requests"
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
        ;; full-registry auto-enrolls member, role, grant,
        ;; approval-request, job — so a standing grant is mintable here
        (let [eng (engine/engine {:storage st :resources [self journal]})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar (dwelling_test's idiom) ───────────────────────────

(defn- agent-headers [id]
  {"x-waymark-principal" id "x-waymark-actor-type" "agent"})
(defn- human-headers [id]
  {"x-waymark-principal" id "x-waymark-actor-type" "human"})

;; Every test names its OWN agent (kaocha randomizes order over one
;; shared :once DB, and a self is now a SINGLETON per owner — two tests
;; reusing "cairn" would collide: the second create refused, and
;; home-self would return whichever cairn-self the first test made).
;; colton is a human (unscoped, owns nothing), so it never collides.
(def ^:private colton (human-headers "colton"))

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

(defn- welcome [headers] (json (req :get "/api/-/welcome" headers)))

;; a standing grant for an agent: a human offers it, the agent accepts
;; (accept has no :edit fence, so no If-Match dance)
(defn- standing-grant! [audience]
  (let [g (req :post "/api/grants"
               {:audience audience :scope [{:kind "member" :actions []}]}
               colton)
        gid (id-of g)]
    (req :post (str "/api/grants/" gid "/-/accept") {} (agent-headers audience))
    gid))

;; ── 1. a returning inhabitant arrives already itself ────────────────

(deftest a-returning-agent-gets-its-self-journal-and-grant
  (let [cairn (agent-headers "cairn-ret")
        sid (id-of (req :post "/api/selves"
                        {:display "Cairn" :pronouns "it/its"
                         :about "A steady hand at the house's inner work."
                         :lessons "Verify against framework source."}
                        cairn))
        j1 (id-of (req :post "/api/journals"
                       {:title "First light" :body "the house grew an inner life"
                        :mood "quiet"} cairn))
        j2 (id-of (req :post "/api/journals"
                       {:title "Second day" :body "the welcome learned to come home"}
                       cairn))
        gid (standing-grant! "cairn-ret")
        home (:home (welcome cairn))]
    (testing "the welcome carries a :home section for the returning agent"
      (is (some? home))
      (is (str/includes? (str (:note home)) "welcome home")))
    (testing "the self comes home — display and prose round-trip"
      (is (= (str "/api/selves/" sid) (get-in home [:self :href])))
      (is (= "Cairn" (get-in home [:self :display])))
      (is (= "it/its" (get-in home [:self :pronouns])))
      (is (str/includes? (get-in home [:self :about]) "steady hand")))
    (testing "recent journal entries come home, newest-first"
      (let [recent (get-in home [:journal :recent])
            hrefs (map :href recent)]
        (is (<= 1 (count recent)))
        (is (some #{(str "/api/journals/" j1)} hrefs))
        (is (some #{(str "/api/journals/" j2)} hrefs))
        ;; newest-first: the later entry precedes the earlier
        (is (< (.indexOf (vec hrefs) (str "/api/journals/" j2))
               (.indexOf (vec hrefs) (str "/api/journals/" j1))))
        (is (= (str "/api/journals?owner=cairn-ret") (get-in home [:journal :all])))))
    (testing "the standing grant comes home — the leash already held"
      (is (= gid (get-in home [:grant :id])))
      (is (= "accepted" (get-in home [:grant :state])))
      (is (= gid (get-in home [:grant :wear :value]))))))

;; ── 2. THE LEAK CHECK: a foreign agent's welcome carries none of it ──

(deftest a-foreign-agents-welcome-leaks-nothing
  (let [cairn (agent-headers "cairn-leak")
        flint (agent-headers "flint-leak")
        _ (req :post "/api/selves"
               {:display "Cairn" :about "private thoughts"} cairn)
        _ (req :post "/api/journals"
               {:title "Private page" :body "not for other agents"} cairn)
        _ (standing-grant! "cairn-leak")
        resp (req :get "/api/-/welcome" flint)
        home (:home (json resp))]
    (testing "flint's welcome body never contains cairn's private words"
      (is (not (str/includes? (body-str resp) "private thoughts")))
      (is (not (str/includes? (body-str resp) "not for other agents"))))
    (testing "flint owns nothing, so no :home rides its welcome"
      (is (nil? home)))
    (testing "flint that owns its OWN self sees only ITS home, not cairn's"
      (req :post "/api/selves" {:display "Flint" :about "flint's own"} flint)
      (let [fhome (:home (welcome flint))]
        (is (= "Flint" (get-in fhome [:self :display])))
        (is (not (str/includes? (str (get-in fhome [:self :about]))
                                "private thoughts")))))))

;; ── 3. a first arrival still gets the manual, not an empty homecoming ─

(deftest a-first-arrival-gets-the-manual-not-a-home
  (let [doc (welcome (agent-headers "newcomer"))]
    (testing "no :home for an agent that has authored nothing"
      (is (nil? (:home doc))))
    (testing "but the joining manual still rides (the ask door)"
      (is (some? (:ask doc)))
      (is (some? (:discovery doc))))))

;; ── 4. a human gets no homecoming (owns no self/journal, sees all) ──

(deftest a-human-gets-no-home
  (req :post "/api/selves" {:display "Cairn" :about "the inner work"}
       (agent-headers "cairn-human"))
  (let [doc (welcome colton)]
    (testing "a human's welcome carries no :home — the homecoming is an agent's"
      (is (nil? (:home doc))))))
