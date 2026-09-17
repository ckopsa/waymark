(ns waymark10.seat-ledger-test
  "The ledger route (docs/spec-seat.md § 11, acceptance cases 21 and
  23): the six answers about a seat over a window, as one call.

  R-11.3a says the six must arrive together, because a person
  weighing a step down the ladder reads them together or not at all.
  Five are a read over the seat's closed sittings. The sixth,
  corrections, is the one question no row answers — nothing on a row
  records that a person undid an agent — so it is a window over the
  transition log, and case 21 is what proves it counts the right
  pairs: a person's move on a row the sitter moved last is a
  correction; a person's move on a row the sitter never touched is
  not.

  R-11.4 is what the numbers are FOR: they point a person at the
  sittings to read, and they prove nothing on their own.

  Real Postgres. Needs the test database; WAYMARK10_TEST_DSN
  overrides the DSN."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.seats :as seats]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)
           (java.time.temporal ChronoUnit)))

;; ── the queue a seat walks ──────────────────────────────────────────

(def ^:private memo
  "Two doors and a way back: the sitter dismisses, a person reopens.
  That reopen is what a correction looks like on the wire."
  (r/resource
   {:kind :memo
    :plural "memos"
    :states [:queued :dismissed :filed]
    :initial :queued
    ;; ONE TOMB: `dismissed` is over without being terminal, exactly
    ;; as workqueue10's inbox_item declares it — a tomb with a door
    ;; out of it is refused by name, and the person's correction IS
    ;; that door. Which is the whole of case 21.
    :terminal #{:filed}
    :over {:accomplished #{:filed} :let-go #{:dismissed}}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 80}]]]
    :filterable {:state #{:eq :in}}
    :actions
    {:dismiss {:from #{:queued} :to :dismissed
               :safety {:idempotent true :reversible true :confirm false}}
     :reopen {:from #{:dismissed} :to :queued
              :safety {:idempotent true :reversible true :confirm false}}
     :file {:from #{:queued} :to :filed
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A filed memo keeps its history."}}}}))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["memos" "seats" "models" "sittings" "grants" "schedules"
   "approval_requests" "members" "roles" "definitions" "capabilities"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"])

(def ^:private storage (atom nil))
(def ^:dynamic *eng* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (reset! storage st)
        (binding [*eng* (engine/engine {:storage st :resources [memo]})]
          (f))
        (finally (reset! storage nil) (pg/close! st))))))

(def ^:private model-name "ledger-economy")

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))
(def ^:private clerk
  ;; the session's model claim rides the actor of every transition it
  ;; writes (R-9.5) — which is how a correction knows which rung of
  ;; the ladder it belongs to
  (t/principal {:id "ledger-clerk" :type :agent :display "Clerk"
                :model model-name}))

(defn- past-engine
  "A second deploy over the same database whose clock is elsewhere —
  how a sitting gets a `started_at` outside the rolling week."
  [now]
  (engine/engine {:storage @storage :resources [memo]
                  :now-fn (constantly now)}))

(defn- row-of
  ([kind id] (row-of *eng* kind id))
  ([eng kind id]
   (let [rdef (get (inv/resources eng) kind)]
     (some->> (store/with-tx (:storage eng)
                (fn [tx] (store/load-row (:storage eng) tx kind (str id) {})))
              (inv/decode-row rdef)))))

(defn- open-seat! [name']
  (:row (inv/create! *eng* :seat
                     {:name name'
                      :charter "Decide whether a memo asks something."
                      :scope [{:kind "memo" :actions ["dismiss"]}]
                      :standing_ttl_seconds 604800
                      :cadence_seconds 3600
                      :budget_usd_per_week 5M
                      :sitting_budget_tokens 60000}
                     {:principal colton})))

(defn- the-model []
  (or (first (store/with-tx (:storage *eng*)
               (fn [tx] (store/query-rows (:storage *eng*) tx :model
                                          {:name model-name} {:limit 1}))))
      (:row (inv/create! *eng* :model
                         {:name model-name :display model-name
                          :vendor "anthropic" :tier "economy"
                          :price_input_per_mtok 1M
                          :price_output_per_mtok 5M
                          :price_cache_read_per_mtok 0.1M
                          :price_cache_write_per_mtok 1.25M}
                         {:principal colton}))))

(defn- sit!
  "The whole bootstrap, through the doors a harness uses: the sitter
  asks to sit in the office by NAME, a person approves, and the minted
  grant cites the seat. → the grant's id."
  [seat-name]
  (let [ask (:row (inv/create! *eng* :approval_request
                               {:task "Walk the memos this seat owns."
                                :seat seat-name}
                               {:principal clerk}))]
    ;; the mint is a WIRE-BOUNDARY effect (grants' recorded gap,
    ;; waymark-442.14): the router runs it after every invoke, so a
    ;; test that approves in-process runs it the same way
    (grants/approval-effects!
     *eng* (get (inv/resources *eng*) :approval_request) :approve
     (inv/invoke! *eng* :approval_request (:id ask) :approve nil
                  {:principal colton}))
    (get-in (row-of :approval_request (:id ask)) [:data :grant_id])))

(defn- scope-grant!
  "A plain leash that cites no office: minted, then accepted by its
  audience, which is what makes it live."
  [id audience]
  (let [row (:row (inv/create! *eng* :grant
                               {:audience audience
                                :scope [{:kind "memo" :actions []}]}
                               {:principal grants/approvals-actor
                                :id id :mint? true}))]
    (inv/invoke! *eng* :grant (:id row) :accept {}
                 {:principal (t/principal {:id audience :type :agent})})
    (:id row)))

(defn- sitting!
  "One wake: open it, count what it did, close it with the harness's
  token counts. `eng` may be a past deploy, which is what puts a
  sitting outside the window."
  [eng seat model grant {:keys [in out transitions refusals]}]
  (let [row (:row (inv/create! eng :sitting
                               {:seat (:id seat) :model (:id model)
                                :grant (str grant)}
                               {:principal clerk}))]
    (dotimes [_ (or transitions 0)]
      (seats/bump-counter! eng (:id row) :transitions))
    (dotimes [_ (or refusals 0)]
      (seats/bump-counter! eng (:id row) :refusals))
    (inv/invoke! eng :sitting (:id row) :close
                 {:input_tokens (or in 0) :output_tokens (or out 0)
                  :cache_read_tokens 0 :cache_write_tokens 0 :turns 1}
                 {:principal clerk})
    row))

(defn- GET
  "The query string is its own key on a ring request; a `?` inside
  :uri would never reach the route at all."
  [uri query headers]
  ((engine/handler *eng*)
   (cond-> {:request-method :get :uri uri :headers headers}
     query (assoc :query-string query))))

(defn- ledger-status [seat headers]
  (:status (GET (str "/api/seats/" (:id seat) "/ledger") nil headers)))

(defn- ledger
  ([seat] (ledger seat nil))
  ([seat query]
   (let [resp (GET (str "/api/seats/" (:id seat) "/ledger") query
                   {"x-waymark-principal" "colton"})]
     (assoc (wire/read-json (:body resp)) :status (:status resp)))))

;; ── case 23 · the six answers, over a window ────────────────────────

(deftest the-ledger-answers-six-questions-about-a-window
  (let [model (the-model)
        seat (open-seat! "ledger-clerk-seat")
        grant (sit! "ledger-clerk-seat")
        _ (sitting! *eng* seat model grant
                    {:in 1000000 :out 200000 :transitions 4 :refusals 1})
        _ (sitting! *eng* seat model grant
                    {:in 500000 :out 0 :transitions 2 :refusals 0})
        ;; eight days back: inside the seat's life, outside its week
        old (past-engine (.minus (Instant/now) 8 ChronoUnit/DAYS))
        _ (sitting! old seat model grant
                    {:in 4000000 :out 0 :transitions 99 :refusals 9})
        doc (ledger seat)]
    (testing "the default window is the rolling week"
      (is (= 200 (:status doc)))
      (is (= "seat_ledger" (:kind doc)))
      (is (= (str (:id seat)) (:seat doc)))
      (is (some? (get-in doc [:window :since])))
      (is (some? (get-in doc [:window :until]))))

    (testing "what it cost, what it did, where it hit the law"
      (is (zero? (compare (bigdec "2.5") (:cost_usd doc)))
          "1.00 + 1.00 in the first wake, 0.50 in the second")
      (is (= 6 (:transitions doc)))
      (is (= 1 (:refusals doc))))

    (testing "what each thing cost"
      (is (zero? (compare (bigdec "0.416667") (:cost_per_transition doc)))
          "2.5 over 6, to the sitting's own six places"))

    (testing "which model did it"
      (is (= 1 (count (:by_model doc))))
      (let [row (first (:by_model doc))]
        (is (= (str (:id model)) (:model row)))
        (is (zero? (compare (bigdec "2.5") (:cost_usd row))))
        (is (= 6 (:transitions row)))))

    (testing "the sitting eight days back is outside the window, and a
              `since` that reaches it brings it in"
      (let [wide (ledger seat (str "since="
                                   (.minus (Instant/now) 30 ChronoUnit/DAYS)))]
        (is (zero? (compare (bigdec "6.5") (:cost_usd wide))))
        (is (= 105 (:transitions wide)))
        (is (= 10 (:refusals wide)))))

    (testing "an unreadable since is refused rather than quietly rounded"
      (is (= 422 (:status (ledger seat "since=last%20tuesday")))))

    (testing "who may read it"
      (let [as-sitter (fn [gid]
                        (ledger-status seat
                                       {"x-waymark-principal" (:id clerk)
                                        "x-waymark-actor-type" "agent"
                                        "x-waymark-grant" (str gid)}))]
        (is (= 404 (:status (ledger {:id "seat-nobody-opened"})))
            "a seat that does not exist")
        (is (= 200 (as-sitter grant))
            "the seat's own sitter reads its ledger")
        (is (= 404 (as-sitter (scope-grant! "grant-ledger-scope-only"
                                            (:id clerk))))
            "the same hand on a live leash that cites NO seat is told the
             address does not exist — concealment, never a 403")))))

;; ── case 21 · what it got wrong ─────────────────────────────────────

(deftest a-person-undoing-the-sitter-is-a-correction-and-nothing-else-is
  (let [model (the-model)
        seat (open-seat! "correction-clerk-seat")
        grant (sit! "correction-clerk-seat")
        _ (sitting! *eng* seat model grant {:in 100000 :transitions 2})
        undone (:row (inv/create! *eng* :memo {:title "Receipt, probably"}
                                  {:principal colton}))
        untouched (:row (inv/create! *eng* :memo {:title "A real ask"}
                                     {:principal colton}))]
    (testing "the sitter dismisses, and a person puts it back"
      (inv/invoke! *eng* :memo (:id undone) :dismiss nil {:principal clerk})
      (inv/invoke! *eng* :memo (:id undone) :reopen nil {:principal colton}))
    (testing "and a person moves a row the sitter never touched"
      (inv/invoke! *eng* :memo (:id untouched) :dismiss nil
                   {:principal colton}))

    (let [doc (ledger seat)]
      (is (= 1 (:corrections doc))
          "the reopen counts; the dismiss on the row the sitter never
           moved does not")
      (is (= [(str (:id model))] (mapv :model (:by_model doc)))
          "and the correction lands on the rung the sitter declared")
      (is (= 1 (:corrections (first (:by_model doc))))))

    (testing "a person's own undo of a person is not a correction"
      (inv/invoke! *eng* :memo (:id untouched) :reopen nil
                   {:principal colton})
      (is (= 1 (:corrections (ledger seat)))))))
