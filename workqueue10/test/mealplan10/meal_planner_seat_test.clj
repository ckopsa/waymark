(ns mealplan10.meal-planner-seat-test
  "The meal-planner seat (bead waymark-fp62.14, R-5;
  docs/routines/meal-planner.md).

  THE SEAT THAT MAKES SURE THE COMING WEEK HAS A PLAN. It walks the
  DRAFT plans, covers each undecided day, and finalizes the week. It
  is a doing seat and not a judge: it says no verdict, and the rows it
  moves are the plan and its days.

  Two framework changes stand under it, and this suite is the proof
  that they hold over real declarations rather than a fixture kind:

  - THE WALK'S FILTER IS THE SCOPE ENTRY'S (waymark-fp62.12). The
    `plan` kind declares no default filter at all, so before that bead
    no seat could walk it: `walk-names-a-kind-in-scope` refused a kind
    whose collection is every row ever made. The scope entry's own
    `filter` is what makes the queue now, and `walk-leaves-its-filter`
    admits `state=draft` only because `finalize` — an action of that
    same entry — is the door out of draft.
  - THE ABSENCE WAKE (waymark-fp62.13). The wake entry names
    `at_most 0` over the planned plans, so the seat is woken when NO
    planned week is waiting. The count is the engine's own, so the
    scope filter that hides a planned plan from the seat does not hide
    it from the count.

  What is proved here, and what is not. THE ENGINE'S ANSWERS ARE THE
  PROOF: no model sits, no Routine fires, and nothing in this file
  reads a charter and decides anything. The first deftest reads the
  answer of the create door. The second reads the answer of
  `waymark_sit`, then walks the doors the seat's own grant opens and
  reads what the queue says afterwards.

  The sit is driven through `mcp/call-tool` with a delegate principal
  rather than through the OIDC door: the connector door is
  connector_door_test's and mcp_sit_test's subject, and re-minting an
  RSA keypair here would prove their bead a second time and this one
  not at all. The seat's schedule is left unminted for the same
  reason — `sit` reads the chair out of `held_for` when there is no
  schedule row, and the schedule is the consumer's.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [calendar10.source :as es]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [mealplan10.main :as main]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mcp :as mcp]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the seat's values (bead R-2, and docs/routines/meal-planner.md's
;;    own table) ──────────────────────────────────────────────────────

(def ^:private seat-name "meal-planner")

(def ^:private seat-scope
  "The authority. The `plan` entry is the queue: its filter makes it,
  and `finalize` is the door out of the filtered state. `plan_day`
  carries the four doors that cover a day — the wire spellings of
  plan-day.clj's `:actions` map, which is snake_case of the defaction
  name. `rotation` and `meal` are read-only: the seat reads the Sunday
  themes and the meals on the list, and writes neither."
  [{:kind "plan"
    :actions ["create" "finalize"]
    :filter {:state "draft"}}
   {:kind "plan_day"
    :actions ["assign_meal" "assign_off_theme"
              "set_sunday_theme" "mark_eating_out"]}
   {:kind "rotation" :actions []}
   {:kind "meal" :actions []}])

(def ^:private seat-wake
  "One count entry, and it counts on ABSENCE: every action of a `plan`
  re-counts the planned plans, and at or below zero the seat is fired
  (waymark-fp62.13)."
  [{:kind "plan" :actions [] :filter {:state "planned"} :at_most 0}])

(def ^:private seat-charter
  "The residual. Byte for byte the block under \"The charter\" in
  docs/routines/meal-planner.md, line breaks included: that block is
  what a person pastes, so a test carrying a reflowed copy of it would
  pin a charter no seat ever holds."
  (str "Make sure the coming week has a plan in planned.\n"
       "\n"
       "When the sit hands you a draft plan, cover each undecided day. Give\n"
       "the day a meal on that day's theme, from the rotation. Mark the day\n"
       "eating out when the calendar says the family is out that night.\n"
       "\n"
       "Finalize when every day is covered.\n"
       "\n"
       "When the sit hands you no plan, create one for the coming Tuesday and\n"
       "stop. The next firing walks it.\n"
       "\n"
       "When a gate refuses finalize, leave the plan in draft. Say why in the\n"
       "close of the sitting.\n"
       "\n"
       "Never acknowledge a warning. A person does that."))

(defn- seat-body
  "The create body R-6 posts, with `held_for` filled in and anything a
  refusal case wants changed merged over it."
  [model-id extra]
  (merge {:name seat-name
          :charter seat-charter
          :mode "fired"
          :scope seat-scope
          :held_for [model-id]
          :standing_ttl_seconds 604800
          :cadence_seconds 604800
          :budget_usd_per_week 5
          :sitting_budget_tokens 200000
          :walk "plan"
          :rows_per_firing 2
          :wake_on seat-wake
          :fire_interval_seconds 3600}
         extra))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private app-resources (main/resources (es/fake-calendar)))

(def ^:private engine-tables
  "The seat's own kinds are enrolled by the framework, so they are not
  in `app-resources` and have to be named here."
  ["seats" "models" "sittings" "grants" "schedules" "approval_requests"
   "members" "roles" "definitions" "waymark10_transitions"
   "waymark10_idempotency" "waymark10_drafts"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)
(def ^:dynamic *made* nil)
(def ^:dynamic *seat-id* nil)
(def ^:dynamic *model-id* nil)

(def ^:private colton {"x-waymark-principal" "colton"})

(def ^:private person
  "The person behind the connector: the one `waymark_sit` says the
  sitter acts for."
  (t/principal {:id "colton" :display "Colton Kopsa"}))

(def ^:private delegate
  "The owner's connector. `:acts-for` rides OUTSIDE t/principal's
  closed shape, the way oidc.clj's gate assocs it — it is the one
  thing `waymark_sit` asks of the session that presents the key."
  (assoc (t/principal {:id "connector:colton" :type :agent
                       :display "Claude for Colton"})
         :acts-for "colton"))

(def ^:private chair-key
  "128 bits of base64url — what a machine mints and no hand types."
  "bWVhbC1wbGFubmVyLWNoYWlyLWtleS0x")

(def ^:private mcp-session "meal-planner-routine-run-1")

(defn- req
  ([method uri] (req method uri nil colton))
  ([method uri body] (req method uri body colton))
  ([method uri body headers]
   ;; the query rides :query-string and never the :uri — a ring
   ;; request carrying "?" inside :uri asks for a path nobody serves
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path :headers headers}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [env] (last (str/split (str (:self env)) #"/")))

(defn- created! [plural body]
  (let [resp (req :post (str "/api/" plural) body)]
    (is (= 201 (:status resp)) (str plural ": " (:body resp)))
    (json resp)))

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          clock (atom (Instant/parse "2026-07-08T12:00:00Z"))]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table (concat (map #(store/definition-checked-name
                                         (:plural %))
                                       app-resources)
                                  engine-tables)]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources app-resources
                                  ;; main.clj's posture: the render
                                  ;; probe carries the read hooks
                                  :probe-reads true
                                  :now-fn (fn [] @clock)})
              h (engine/handler eng)
              model (json (h {:request-method :post :uri "/api/models"
                              :headers colton
                              :body (wire/write-json
                                     {:name "claude-planner-5"
                                      :display "Planner 5"
                                      :vendor "anthropic" :tier "strong"
                                      :price_input_per_mtok 3
                                      :price_output_per_mtok 15
                                      :price_cache_read_per_mtok 0.3
                                      :price_cache_write_per_mtok 3.75})}))
              ;; ONE create of the office, here rather than in a
              ;; deftest: two deftests share this database and the
              ;; one-seat-spelling guard refuses a second
              ;; `meal-planner`. The response is bound, so the deftest
              ;; that reads the guards' answer reads it whatever order
              ;; the two run in.
              made (h {:request-method :post :uri "/api/seats"
                       :headers colton
                       :body (wire/write-json
                              (seat-body (id-of model) {}))})
              ;; only a 201 carries a self to read an id out of; a
              ;; refusal leaves this nil and the first deftest below
              ;; says which guard refused
              seat-id (when (= 201 (:status made)) (id-of (json made)))]
          ;; the chair key, offered the way a person offers it: the
          ;; engine stores it and never shows it again
          (when seat-id
            (inv/invoke! eng :seat seat-id :offer_key {:key chair-key}
                         {:principal person}))
          (binding [*eng* eng *h* h *made* made *seat-id* seat-id
                    *model-id* (id-of model)]
            (f)))
        (finally (pg/close! st))))))

;; ── 1 · the guards admit the seat (bead R-5, first half) ────────────

(deftest the-guards-admit-the-meal-planner-seat
  (let [seat (json *made*)]
    (testing "the create door answers 201"
      (is (= 201 (:status *made*)) (str (:body *made*))))

    (testing "the walk is the plan queue, and the filter on its scope
              entry is what makes it (waymark-fp62.12)"
      (is (= "plan" (get-in seat [:data :walk])))
      (let [entry (first (filter #(= "plan" (str (:kind %)))
                                 (get-in seat [:data :scope])))]
        (is (= {:state "draft"} (:filter entry))
            "the plan kind declares no default filter of its own, so
             this one IS the queue")
        (is (= ["create" "finalize"] (:actions entry))
            "finalize is the door out of draft, which is what
             walk-leaves-its-filter asks of a state filter")))

    (testing "the scope is admitted whole, plan_day named directly"
      (is (= ["plan" "plan_day" "rotation" "meal"]
             (mapv #(str (:kind %)) (get-in seat [:data :scope])))
          "a day is an owned child of the plan, and an entry for the
           owner does not open it — one entry opens one kind")
      (is (= ["assign_meal" "assign_off_theme"
              "set_sunday_theme" "mark_eating_out"]
             (:actions (second (get-in seat [:data :scope]))))
          "the wire spellings of plan-day.clj's :actions map"))

    (testing "the absence wake is accepted (waymark-fp62.13)"
      (let [entry (first (get-in seat [:data :wake_on]))]
        (is (= "plan" (str (:kind entry))))
        (is (= [] (:actions entry))
            "no action named: every action of a plan re-counts")
        (is (= {:state "planned"} (:filter entry)))
        (is (= 0 (:at_most entry)))
        (is (nil? (:at_least entry))
            "an entry names one of the two, never both")))

    (testing "and the rest of R-2's values stand on the row"
      (is (= "fired" (get-in seat [:data :mode])))
      (is (= 2 (get-in seat [:data :rows_per_firing])))
      (is (= 604800 (get-in seat [:data :cadence_seconds])))
      (is (= 3600 (get-in seat [:data :fire_interval_seconds])))
      (is (= "active" (:state seat))))))

(deftest the-walk-of-plan-owes-a-filter-and-a-door-out-of-it
  ;; The other half of the acceptance: the same body is refused when
  ;; the filter is gone, and refused again when the entry carries no
  ;; door out of the filtered state. Both sentences are
  ;; waymark-fp62.12's own, so the assertions read the status and the
  ;; kind the refusal is about rather than the words.
  (testing "no filter at all: the plan kind declares no default one,
            so the collection a firing opens would be every plan"
    (let [resp (req :post "/api/seats"
                    (seat-body *model-id*
                               {:name "meal-planner-unfiltered"
                                :scope (update seat-scope 0
                                               dissoc :filter)}))]
      (is (= 409 (:status resp)) (str (:body resp)))
      (is (str/includes? (str (:body resp)) "plan"))))

  (testing "a state filter with no door out of the filtered state"
    (let [resp (req :post "/api/seats"
                    (seat-body *model-id*
                               {:name "meal-planner-no-exit"
                                :scope (assoc-in seat-scope [0 :actions]
                                                 ["create"])}))]
      (is (= 409 (:status resp)) (str (:body resp)))
      (is (str/includes? (str (:body resp)) "plan")))))

;; ── 2 · the seat walks the week (bead R-5, second half) ─────────────

(defn- sit!
  "One `waymark_sit`, through the real tool body. The session is the
  delegate the connector resolves to; everything about the sitter —
  its member row, its grant, its sitting — the engine makes itself."
  []
  (let [r (mcp/call-tool *eng* (mcp/door *eng*)
                         {:principal delegate :mcp-session-id mcp-session}
                         "waymark_sit" {:key chair-key})
        text (str (get-in r [:content 0 :text]))]
    (is (false? (:isError r)) text)
    (wire/read-json text)))

(defn- sitter-headers
  "The seat's own hand, as the sit names it: the sitter member the
  engine minted, wearing the grant the engine minted for it. Every
  request below is under the seat's leash and nobody else's."
  [answer]
  {"x-waymark-principal" (str (:sitter answer))
   "x-waymark-actor-type" "agent"
   "x-waymark-grant" (str (:grant answer))})

(defn- etag-of [headers self]
  (get-in (req :get self nil headers) [:headers "ETag"]))

(deftest the-seat-walks-a-draft-week-and-finalizes-it
  (let [plan (created! "plans" {:start_date "2026-07-14" :weeks 1})
        plan-self (str (:self plan))
        answer (sit!)
        walk (:walk answer)
        hands (sitter-headers answer)]

    (testing "the sit binds the seat and answers its walk (R-12.28)"
      (is (= seat-name (str (:seat answer))))
      (is (= (str "seat:" *seat-id*) (str (:sitter answer)))
          "one sitter per office, derived from the seat row")
      (is (= "plan" (str (:kind walk))))
      (is (= seat-charter (str (:charter walk)))
          "the charter rides the sit: the sitter never reads the seat
           row to learn what the office is for"))

    (testing "the draft week is the row the walk hands over"
      (is (= [(id-of plan)] (mapv :id (:rows walk))))
      (is (= 1 (:total walk)))
      (is (= 7 (get-in plan [:data :total_days]))
          "the birth door made the days with the week")
      (is (= 7 (get-in plan [:data :undecided_days]))))

    (testing "finalize is refused while one day is undecided"
      (let [resp (req :post (str plan-self "/-/finalize") nil
                      (assoc hands "if-match" (etag-of hands plan-self)))]
        (is (= 409 (:status resp)) (str (:body resp)))
        (is (str/includes? (str (:detail (json resp)))
                           "Every day needs a meal or an eating-out mark")
            "the gate's own sentence, which the seat repeats in its
             close rather than working around")
        (is (= "draft" (:state (json (req :get plan-self nil hands))))
            "a refused finalize leaves the week in draft")))

    (testing "the seat covers every day through the doors its scope
              opens"
      (let [days (get-in (json (req :get (str "/api/plan_days?plan_id="
                                              (id-of plan)
                                              "&page%5Bsize%5D=10")
                                    nil hands))
                         [:data :items])]
        (is (= 7 (count days))
            "the days are inside the leash: the scope names plan_day")
        (doseq [d days]
          (let [self (str (:self d))
                resp (req :post (str self "/-/mark_eating_out")
                          {:where "Out"}
                          (assoc hands "if-match" (etag-of hands self)))]
            (is (= 200 (:status resp)) (str self ": " (:body resp)))))))

    (testing "…and finalize is then served, under the seat's own leash"
      (let [resp (req :post (str plan-self "/-/finalize") nil
                      (assoc hands "if-match" (etag-of hands plan-self)))]
        (is (= 200 (:status resp)) (str (:body resp)))
        (is (= "planned" (:state (json resp))))
        (is (true? (get-in (json resp) [:data :all_days_covered])))))

    (testing "a plan that walked finalize is not in the next walk"
      (let [again (:walk (sit!))]
        (is (= [] (mapv :id (:rows again)))
            "the week left state=draft in the commit that finalized it,
             so it left the scope entry's filter in the same commit")
        (is (= 0 (:total again))
            "the count is the queue's own under that filter, so the
             empty queue is what the charter answers with a create")))

    (testing "and the planned week is out of the seat's sight
              altogether"
      (let [resp (req :get plan-self nil hands)]
        (is (= 404 (:status resp))
            "absent, never refused: a row outside the leash is a row
             the sitter cannot tell from one that was never made")))))
