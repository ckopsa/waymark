(ns workqueue10.permission-slip-test
  "The house's first declared decision kind (waymark-442.6), lived in
  over the REAL ring handler.

  Everything under test here is PROJECTED: `permission_slip` has no
  hand-written state, no hand-written action, no hand-written guard
  and no hand-written hook. One `:decision` key says what the house
  means — somebody asks for leave, a grown-up who is not the asker
  answers, the slip is good until a stated hour, asking is paced —
  and the engine mints the machine. So this suite is really two
  claims at once: that the sugar projects a machine that works over
  the wire, and that a kind an APP declared reaches the own-surface
  the framework used to hand out by name.

  That second one is the point of the whole seam. Before this bead,
  `grants/own-kinds` was a literal set of seven kind-name strings in
  core; a decision kind declared in an app was invisible to its own
  asker no matter what it declared, and the failure was silent — the
  rows simply were not there. Here the asker reads their own slips
  with no grant at all, and a sibling agent 404s the same row.

  Assertions are order-independent (kaocha randomizes and the
  deftests share one DB): every test names its own principals and
  never asserts on collection SIZE, only on ownership.

  Run: cd workqueue10 && clojure -M:test --focus workqueue10.permission-slip-test"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]
            [workqueue10.resources.permission-slip :refer [permission-slip]]))

(def ^:private tables
  ["permission_slips" "members" "roles" "grants" "approval_requests"
   "attachments" "subscriptions" "jobs" "definitions"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"
   "waymark10_cursors" "waymark10_job_leases"])

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
        (let [eng (engine/engine {:storage st :resources [permission-slip]})]
          (binding [*h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

(defn- agent-headers [id]
  {"x-waymark-principal" id "x-waymark-actor-type" "agent"})

;; the household's grown-ups wear the role the declaration names; the
;; roles header is how a human's member roles reach a request
(defn- parent-headers [id]
  {"x-waymark-principal" id "x-waymark-actor-type" "human"
   "x-waymark-roles" "parent"})

(defn- kid-headers [id]
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
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))
(defn- items [resp] (get-in (json resp) [:data :items]))
(defn- item-id [it] (last (str/split (:self it) #"/")))

(defn- ask! [body headers] (req :post "/api/permission_slips" body headers))
(defn- sign! [sid verb headers]
  (req :post (str "/api/permission_slips/" sid "/-/" (name verb)) {} headers))
(defn- answer! [sid verb body headers]
  (req :post (str "/api/permission_slips/" sid "/-/" (name verb)) body headers))

;; ── the projected machine ───────────────────────────────────────────

(deftest the-slip-is-born-offered-with-its-asker-and-its-hour-stamped
  (let [iris (kid-headers "iris-born")
        made (ask! {:for_what "bike to the park with Otto"} iris)]
    (testing "the ask lands in the open state the sugar chose"
      (is (= 201 (:status made)))
      (is (= "offered" (:state (json made)))))
    (testing "the asker is stamped from the principal, never supplied"
      (is (= "iris-born" (get-in (json made) [:data :asked_by]))))
    (testing "a blank leash gets the declared default rather than none"
      (is (some? (get-in (json made) [:data :good_until]))))
    (testing "the stamped fields are not the asker's to write"
      ;; :asked_by and :signed_by are omitted from the projected create
      ;; model, so naming one is the unknown-field refusal, not a
      ;; quietly ignored key
      (let [r (ask! {:for_what "stay up for the game" :asked_by "mom"} iris)]
        (is (>= (:status r) 400))
        (is (not= 201 (:status r)))))))

(deftest a-grown-up-who-did-not-ask-signs-the-slip
  (let [iris (kid-headers "iris-sign")
        mom (parent-headers "mom-sign")
        sid (id-of (ask! {:for_what "bike to the park with Otto"} iris))
        signed (answer! sid :allow {:answer "back before dark"} mom)]
    (testing "the verdict lands in the state the declaration named"
      (is (= 200 (:status signed)))
      (is (= "allowed" (:state (json signed)))))
    (testing "the slip records WHO said yes — the projected stamp"
      (is (= "mom-sign" (get-in (json signed) [:data :signed_by]))))
    (testing "…and what they wrote with it"
      (is (= "back before dark" (get-in (json signed) [:data :answer]))))
    (testing "a signed slip does not sign twice — the machine itself refuses"
      (is (>= (:status (sign! sid :allow mom)) 400)))))

(deftest the-two-walls-hold-over-the-wire
  (let [iris (kid-headers "iris-walls")
        otto (kid-headers "otto-walls")
        mom (parent-headers "mom-walls")
        sid (id-of (ask! {:for_what "ride to the shop"} iris))]
    (testing "the asker cannot sign her own slip, even wearing the role"
      (let [r (sign! sid :allow (assoc iris "x-waymark-roles" "parent"))]
        (is (>= (:status r) 400))
        (is (not= 200 (:status r)))))
    (testing "a sibling is past the first wall and stopped by the second"
      (let [r (sign! sid :refuse otto)]
        (is (>= (:status r) 400))
        (is (not= 200 (:status r)))))
    (testing "the slip is still waiting — neither refusal moved it"
      (is (= "offered" (:state (json (req :get (str "/api/permission_slips/" sid)
                                          mom))))))
    (testing "and a grown-up who did not ask still can"
      (is (= 200 (:status (sign! sid :allow mom)))))))

;; ── the own-surface, declared rather than handed out by name ────────

(deftest the-asker-reads-her-own-answers-with-no-grant
  (let [iris (agent-headers "iris-own")
        otto (agent-headers "otto-own")
        sid (id-of (ask! {:for_what "sleepover on Friday"} iris))]
    (testing "an agent with no grant at all files the ask"
      (is (some? sid)))
    (testing "…and reads the row back — an ask you cannot read is not an ask"
      (let [r (req :get (str "/api/permission_slips/" sid) iris)]
        (is (= 200 (:status r)))
        (is (= "iris-own" (get-in (json r) [:data :asked_by])))))
    (testing "…and finds it in her own collection"
      (is (some #(= sid (item-id %))
                (items (req :get "/api/permission_slips" iris)))))
    (testing "a sibling agent 404s the row — concealment, not refusal"
      (is (= 404 (:status (req :get (str "/api/permission_slips/" sid) otto)))))
    (testing "…and never sees it in a collection of his own"
      (is (not-any? #(= sid (item-id %))
                    (items (req :get "/api/permission_slips" otto)))))))
