(ns waymark10.seat-sweep-test
  "The boot sweep (docs/spec-seat.md § 7, acceptance cases 8 and 16).

  THE REGISTRY CHANGES ONLY AT BOOT, so the boot is where the house
  says what the change cost a seat. Case 8 is waymark-enx made
  mechanical: a push retires an action, a seat's stored scope still
  names it, and before this leg the leash died in silence. Here the
  boot writes the failing entry into `stale` with the guard's own
  sentence, leaves the entries that still resolve alone (R-7.3), does
  not write again on the next boot, and `waymark_discover` carries it
  to the sitter (R-7.4). Case 16 is the other half: a sitting nobody
  closed ends as `abandoned`, with no tokens.

  The 'push' is two declarations of one kind — the wide one the seat
  was opened against, and the narrow one a later deploy resident.
  Booting a second engine over the same database IS the push.

  Real Postgres. Needs the test database; WAYMARK10_TEST_DSN
  overrides the DSN."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.resource :as r]
            [waymark10.server.definitions :as defs]
            [waymark10.server.engine :as engine]
            [waymark10.server.grants :as grants]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the kind a push narrows ─────────────────────────────────────────

(def ^:private quiet
  {:idempotent true :reversible false :confirm false
   :one-way "History keeps the record."})

(def ^:private errand-base
  {:kind :errand
   :plural "errands"
   :states [:queued :dropped :done]
   :initial :queued
   :terminal #{:dropped :done}
   :summary "{data.title} · {state}"
   :schema [:map [:title [:string {:min 1 :max 80}]]]
   :filterable {:state #{:eq :in}}})

(def ^:private errand-wide
  "Before the push: two ways out of the queue."
  (r/resource
   (assoc errand-base
          :actions {:finish {:from #{:queued} :to :done :safety quiet}
                    :drop {:from #{:queued} :to :dropped :safety quiet}
                    :abandon {:from #{:queued} :to :dropped :safety quiet}})))

(def ^:private errand-narrow
  "After the push: `abandon` is gone, and every state is still
  reachable — the deploy was a tidy-up, not a mistake."
  (r/resource
   (assoc errand-base
          :actions {:finish {:from #{:queued} :to :done :safety quiet}
                    :drop {:from #{:queued} :to :dropped :safety quiet}})))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["errands" "seats" "models" "sittings" "grants" "schedules"
   "approval_requests" "members" "roles" "definitions" "capabilities"
   "waymark10_transitions" "waymark10_idempotency" "waymark10_drafts"])

(def ^:private storage (atom nil))

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (reset! storage st)
        (f)
        (finally (reset! storage nil) (pg/close! st))))))

(defn- boot
  "A deploy: one engine over the shared database, whose construction
  runs boot-revise! and therefore the sweep."
  ([resources] (boot resources nil))
  ([resources now]
   (engine/engine (cond-> {:storage @storage :resources resources}
                    now (assoc :now-fn (constantly now))))))

(def ^:private colton (t/principal {:id "colton" :display "Colton"}))
(def ^:private clerk (t/principal {:id "clerk" :type :agent :display "Clerk"}))

(defn- row-of [eng kind id]
  (let [rdef (get (inv/resources eng) kind)]
    (some->> (store/with-tx (:storage eng)
               (fn [tx] (store/load-row (:storage eng) tx kind (str id) {})))
             (inv/decode-row rdef))))

(defn- log-of [eng kind id]
  (store/with-tx (:storage eng)
    (fn [tx]
      (store/transitions (:storage eng) tx
                         {:kind kind :resource-id (str id)} {}))))

(defn- open-seat! [eng name' extra]
  (:row (inv/create! eng :seat
                     (merge {:name name'
                             :charter "Decide what this errand asks of the house."
                             :scope [{:kind "model" :actions ["retire"]}]
                             :standing_ttl_seconds 604800
                             :cadence_seconds 3600
                             :budget_usd_per_week 5M
                             :sitting_budget_tokens 60000}
                            extra)
                     {:principal colton})))

(defn- add-model! [eng name']
  (:row (inv/create! eng :model
                     {:name name' :display name' :vendor "anthropic"
                      :tier "economy"
                      :price_input_per_mtok 1M :price_output_per_mtok 5M
                      :price_cache_read_per_mtok 0.1M
                      :price_cache_write_per_mtok 1.25M}
                     {:principal colton})))

(defn- sit!
  "The whole bootstrap, through the doors a harness uses: the agent
  asks to sit in the office by NAME, a person approves, and the minted
  grant cites the seat. → the grant's id."
  [eng seat-name who]
  (let [ask (:row (inv/create! eng :approval_request
                               {:task "Walk the queue this seat owns."
                                :seat seat-name}
                               {:principal who}))]
    ;; the mint is a WIRE-BOUNDARY effect (grants' recorded gap,
    ;; waymark-442.14): the router runs it after every invoke, so a
    ;; test that approves in-process runs it the same way
    (grants/approval-effects!
     eng (get (inv/resources eng) :approval_request) :approve
     (inv/invoke! eng :approval_request (:id ask) :approve nil
                  {:principal colton}))
    (get-in (row-of eng :approval_request (:id ask)) [:data :grant_id])))

(defn- scope-grant!
  "A plain leash that cites no office: minted, then accepted by its
  audience, which is what makes it live."
  [eng id audience]
  (let [row (:row (inv/create! eng :grant
                               {:audience audience
                                :scope [{:kind "model" :actions []}]}
                               {:principal grants/approvals-actor
                                :id id :mint? true}))]
    (inv/invoke! eng :grant (:id row) :accept {}
                 {:principal (t/principal {:id audience :type :agent})})
    (:id row)))

(defn- discover
  "The MCP tool as `who` wearing `grant-id` sees it."
  [eng who grant-id]
  (let [h (engine/handler eng)
        resp (h {:request-method :post :uri "/api/-/mcp"
                 :headers {"x-waymark-principal" who
                           "x-waymark-actor-type" "agent"
                           "x-waymark-grant" (str grant-id)}
                 :body (wire/write-json
                        {:jsonrpc "2.0" :id 1 :method "tools/call"
                         :params {:name "waymark_discover" :arguments {}}})})]
    (-> (wire/read-json (:body resp))
        (get-in [:result :content 0 :text])
        wire/read-json)))

;; ── case 8 · a boot with a retired action marks the seat stale ──────

(def ^:private mixed-scope
  [{:kind "errand" :actions ["abandon" "finish"]}
   {:kind "model" :actions ["retire"]}])

(deftest a-boot-with-a-retired-action-marks-the-seat-stale
  (let [before (boot [errand-wide])
        seat (open-seat! before "sweep-clerk" {:scope mixed-scope})
        grant (sit! before "sweep-clerk" clerk)]
    (is (nil? (get-in (row-of before :seat (:id seat)) [:data :stale]))
        "a seat opened against a registry that serves its scope is clean")

    (testing "the push: a boot whose registry no longer serves `abandon`"
      (let [after (boot [errand-narrow])
            row (row-of after :seat (:id seat))]
        (is (= [{:kind "errand" :actions ["abandon" "finish"]}]
               (get-in row [:data :stale]))
            "the entry that stopped resolving is on the row, and only it")
        (is (= mixed-scope (get-in row [:data :scope]))
            "the scope itself did not move — R-7.3 leaves the surviving
             entries to serve")
        (is (= :active (:state row))
            "stale is not a state: the seat is still the person's")
        (let [marked (last (filter #(= :mark_stale (:action %))
                                   (log-of after :seat (:id seat))))]
          (is (some? marked) "the write is a logged transition")
          (is (= "system" (get-in marked [:actor :type])))
          (is (str/includes? (str (get-in marked [:inputs :note])) "abandon")
              "the guard's own sentence names the entry that failed"))

        (testing "and a second sweep over the same registry writes nothing"
          (let [n (count (log-of after :seat (:id seat)))]
            (is (= 0 (:stale (defs/sweep-seats! after)))
                "nothing moved")
            (is (= n (count (log-of after :seat (:id seat))))
                "and nothing was logged — the sweep is idempotent across
                 boots, or every reboot would be a new alert")))

        (testing "discover carries it to the sitter"
          (let [door (get-in (discover after "clerk" grant)
                             [:doors :ask :seat])]
            (is (= "sweep-clerk" (:name door)))
            (is (= "active" (:state door)))
            (is (= [{:kind "errand" :actions ["abandon" "finish"]}]
                   (:stale door))
                "a stale seat must not be quiet (R-7.4)")
            (is (nil? (:halt door)))
            (is (= (str "/api/seats/" (:id seat) "/ledger") (:ledger door)))
            (is (zero? (compare 5M (get-in door [:budget :limit]))))
            (is (zero? (compare 0M (get-in door [:budget :spent])))
                "no closed sitting, nothing spent")
            (is (nil? (get-in door [:budget :resumes_at])))))))

    (testing "a grant that cites no seat gets no seat door at all"
      (let [plain (scope-grant! before "grant-no-seat" "stranger")
            ask (get-in (discover before "stranger" plain) [:doors :ask])]
        (is (= "grant-no-seat" (get-in ask [:anchor :grant_id]))
            "it is still wearing a leash")
        (is (nil? (:seat ask))
            "absent, the way a kind nobody granted is absent")))))

;; ── case 16 · a sitting left open past two cadences is abandoned ────

(deftest a-sitting-open-past-two-cadences-is-abandoned-by-the-sweep
  (let [eng (boot [errand-wide])
        model (add-model! eng "sweep-economy")
        seat (open-seat! eng "nap-clerk" {:cadence_seconds 300})
        grant (scope-grant! eng "grant-nap-clerk" "clerk")
        sitting! (fn [e] (:row (inv/create! e :sitting
                                            {:seat (:id seat)
                                             :model (:id model)
                                             :grant grant}
                                            {:principal clerk})))
        young (sitting! eng)
        ;; the wake that never came back: a session whose clock was an
        ;; hour ago and whose harness died before the close
        lost (sitting! (boot [errand-wide]
                             (.minusSeconds (Instant/now) 3600)))]
    (is (= :open (:state young)))
    (is (= :open (:state lost)))

    (testing "the next boot ends the lost one and leaves the young one"
      (let [swept (boot [errand-wide])]
        (is (= :abandoned (:state (row-of swept :sitting (:id lost)))))
        (is (= :open (:state (row-of swept :sitting (:id young))))
            "two cadences is 600 seconds; this one is seconds old")
        (let [ending (last (log-of swept :sitting (:id lost)))]
          (is (= :abandon (:action ending)))
          (is (= "waymark10-seats" (get-in ending [:actor :id]))
              "the seats actor, not a hand at the wire"))
        (let [row (row-of swept :sitting (:id lost))]
          (is (nil? (get-in row [:data :cost_usd]))
              "no tokens and no bill: the absence of one, not a zero")
          (is (nil? (get-in row [:data :ended_at]))))))

    (testing "and a second boot has nothing left to end"
      (is (= 0 (:abandoned (defs/sweep-seats! (boot [errand-wide]))))))))
