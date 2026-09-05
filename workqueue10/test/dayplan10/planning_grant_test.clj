(ns dayplan10.planning-grant-test
  "The planning chat's grant (waymark-i89n.6), lived in over the real
  engine: an agent files the ONE ask the sitting driver builds
  (scripts/sitting-run.sh § WHAT THE PLANNING CHAT NEEDS FROM THE
  LEASH — the same kinds, the same doors, spelled the same), a person
  approves it, and the minted grant is what Claude works the MCP with.

  What the grant must do, and this file proves through the MCP door
  and nothing else: waymark_discover shows the agent the day plan's
  kinds; waymark_schema on decision advertises change and create and
  never start, finish or skip; waymark_invoke creates tomorrow's plan,
  rehearses a decision (dry_run — a rehearsal writes nothing), writes
  the accepted one and sets the day; and the verdict doors stay the
  person's — start is ABSENT from the agent's envelope, 404 when
  invoked, and right there on the person's own reading of the same
  row. Concealment, not refusal: 'it isn't there' is the whole answer.

  No grant is seeded in code anywhere — the ask is how the leash grows
  — so the scope below is the pin: change the driver's list and this
  list together, or the ask the driver files stops matching what the
  engine admits. start, finish and skip on a decision are NOT in it
  and never will be.

  The engine is the whole household registry (workqueue10.main), the
  way production wires it; the clock is pinned to the morning before
  the plan's day. Needs the waymark10_test database; WAYMARK10_TEST_DSN
  overrides. Run: cd workqueue10 && clojure -M:test --focus
  dayplan10.planning-grant-test"
  (:require [calendar10.source :as gcal]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dayplan10.zone :as zone]
            [next.jdbc :as jdbc]
            [waymark10.dev :as dev]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [waymark10.wire :as wire]
            [workqueue10.confluence :as conf]
            [workqueue10.main :as main]
            [workqueue10.sources.hub :as hub])
  (:import (java.time LocalDate LocalTime)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ;; the WHOLE folded registry's tables (task_queue_test's rule)
  ["tasks" "task_lists" "media" "chores" "chore_runs" "days"
   "meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "letters" "ticklers" "insights" "weathers" "permission_slips"
   "selves" "journals" "dwellings" "connections" "capabilities"
   "saved_views" "dashboards" "dashboard_slots"
   "feed_recipes" "recipe_proposals" "feed_views" "feed_view_consents"
   "composition_requests" "outcome_pieces" "outcomes" "values" "people"
   "hypotheses"
   "activities" "evening_plans" "evening_sessions"
   "contexts" "day_plans" "blocks" "spans" "decisions"
   "members" "roles" "grants" "approval_requests"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts" "waymark10_cursors"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)

(def ^:private today (LocalDate/parse "2026-01-06"))      ; a Tuesday
(def ^:private tomorrow (LocalDate/parse "2026-01-07"))   ; a workday

(def ^:private clock
  "Eight on the evening before the plan's day: the planning hour."
  (atom (zone/at today (LocalTime/of 20 0))))

(def ^:private colton
  (t/principal {:id "colton" :type :human :display "Colton"}))

;; THE SCOPE — the driver's list, in the grant grammar. Read is an
;; empty actions list. No start, finish or skip on a decision.
(def ^:private planning-scope
  [{:kind "context"  :actions []}
   {:kind "day_plan" :actions ["create" "set" "replan" "reshape"]}
   {:kind "block"    :actions ["create" "skip" "restate"]}
   {:kind "span"     :actions ["move" "swap" "extend" "split"]}
   {:kind "decision" :actions ["create" "change"]}
   {:kind "media"    :actions []}
   {:kind "task"     :actions []}
   {:kind "plan_day" :actions []}
   {:kind "thread"   :actions []}
   {:kind "event"    :actions []}])

(def ^:private planning-kinds (into #{} (map :kind) planning-scope))

(def ^:private human {"x-waymark-principal" "colton"})
(def ^:private agent-id "planner-1")
(defn- agent-headers [& [grant]]
  (cond-> {"x-waymark-principal" agent-id
           "x-waymark-actor-type" "agent"}
    grant (assoc "x-waymark-grant" grant)))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- req [method uri {:keys [body headers]}]
  (*h* (cond-> {:request-method method :uri uri :headers (or headers {})}
         body (assoc :body (wire/write-json body)))))

(defn- rpc
  "One MCP tools/call, as the connector would send it."
  [headers tool args]
  (let [resp (req :post "/api/-/mcp"
                  {:headers (assoc headers "content-type" "application/json")
                   :body {:jsonrpc "2.0" :id 1 :method "tools/call"
                          :params {:name tool :arguments args}}})]
    (is (= 200 (:status resp)) (str tool ": " (:body resp)))
    (:result (json resp))))

(defn- text-of [result] (get-in result [:content 0 :text]))
(defn- doc-of [result] (wire/read-json (text-of result)))
(defn- id-of [self] (last (str/split (str self) #"/")))

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        ;; the whole household, over the offline fakes — discover has
        ;; to be able to show media, task, plan_day, thread and event
        (let [eng (engine/engine {:storage st
                                  :resources (main/resources
                                              {"chore" (conf/fake-source)
                                               "meal" (conf/fake-source)
                                               "todo" (conf/fake-source)
                                               "gtasks" (conf/fake-source)}
                                              {"hub" (hub/source)}
                                              (gcal/fake-calendar))
                                  :now-fn (fn [] @clock)})]
          (binding [*eng* eng
                    *h* (engine/handler eng)]
            (inv/create! eng :context
                         {:name "Workday" :default_shapes ["workday"]
                          :default_spans [{:from "09:00" :to "12:00"}
                                          {:from "13:00" :to "17:00"}]
                          :default_order 1}
                         {:principal colton})
            (f)))
        (finally (pg/close! st))))))

;; ── the one ask, and what it opens ──────────────────────────────────

(defn- mint-planning-grant!
  "The loop the house runs in production: the agent files the ask the
  driver builds, the person approves it in the feed, the approval
  mints the grant. Answers the grant id."
  []
  (let [ask (req :post "/api/approval_requests"
                 {:headers (agent-headers)
                  :body {:task "Let the planning chat build tomorrow's plan."
                         :scope planning-scope}})
        _ (is (= 201 (:status ask)) (pr-str (json ask)))
        approved (req :post (str (:self (json ask)) "/-/approve") {:headers human})
        gid (get-in (json approved) [:data :grant_id])]
    (is (= 200 (:status approved)) (pr-str (json approved)))
    (is (some? gid))
    (is (= "accepted" (:state (json (req :get (str "/api/grants/" gid) {:headers human}))))
        "the ask WAS the audience's consent")
    gid))

(deftest the-planning-chat-works-tomorrow-through-the-mcp-and-never-gives-the-verdict
  (let [gid (mint-planning-grant!)
        agent (agent-headers gid)]

    (testing "1 · discover shows the agent the day plan's kinds"
      (let [doc (doc-of (rpc agent "waymark_discover" {}))
            kinds (set (:kinds doc))]
        (doseq [k planning-kinds]
          (is (contains? kinds k) (str k " is not in " (pr-str kinds))))))

    (testing "2 · decision's doors, as the agent reads them: create and change, no verdict"
      (let [doc (doc-of (rpc agent "waymark_schema" {:kind "decision"}))
            actions (into #{} (map :name) (:actions doc))]
        (is (= ["create"] (:create doc)))
        (is (contains? actions "change"))
        (is (not-any? actions ["start" "finish" "skip" "reopen"])
            (str "a verdict door is advertised: " (pr-str actions)))))

    (let [plan (rpc agent "waymark_invoke"
                    {:kind "day_plan" :action "create"
                     :input {:date (str tomorrow) :member "colton"}})
          plan-doc (doc-of plan)
          plan-id (id-of (:self plan-doc))
          block (some #(when (= plan-id (get-in % [:data :plan_id])) %)
                      (dev/rows *eng* :block))]
      (testing "3 · the agent creates tomorrow's plan; the shape mints its blocks"
        (is (false? (:isError plan)) (text-of plan))
        (is (= "drafting" (:state plan-doc)))
        (is (some? block) "the Workday block was born with the plan"))

      (testing "4 · a decision rehearsed — dry_run judges schema and guards and writes nothing"
        (let [before (count (dev/rows *eng* :decision))
              r (rpc agent "waymark_invoke"
                     {:kind "decision" :action "create" :dry_run true
                      :input {:block_id (:id block) :kind "pick" :order 1
                              :text "The deck estimate with the contractor"
                              :subject (str "/api/day_plans/" plan-id)}})]
          (is (false? (:isError r)) (text-of r))
          (is (true? (:valid (doc-of r))) (text-of r))
          (is (= before (count (dev/rows *eng* :decision))) "a rehearsal wrote nothing")))

      (let [made (rpc agent "waymark_invoke"
                      {:kind "decision" :action "create"
                       :input {:block_id (:id block) :kind "pick" :order 1
                               :text "The deck estimate with the contractor"
                               :launch {:type "text" :text "Quote is in the blue folder"}}})
            made-doc (doc-of made)
            did (id-of (:self made-doc))]
        (testing "5 · the accepted decision is written, and its envelope affords the agent no verdict"
          (is (false? (:isError made)) (text-of made))
          (is (= "planned" (:state made-doc)))
          (let [afforded (set (map name (keys (:actions made-doc))))]
            (is (contains? afforded "change"))
            (is (not-any? afforded ["start" "finish" "skip"])
                (str "the agent's envelope advertises a verdict: " (pr-str afforded)))))

        (testing "6 · start invoked anyway is concealed — not found, not forbidden"
          (let [r (rpc agent "waymark_invoke" {:kind "decision" :id did :action "start"})]
            (is (true? (:isError r)))
            (is (= 404 (:status (doc-of r))) (text-of r)))
          (is (= 404 (:status (req :post (str "/api/decisions/" did "/-/start")
                                   {:headers agent})))))

        (testing "7 · and the same row, read by the person, has Go right there"
          (let [mine (json (req :get (str "/api/decisions/" did) {:headers human}))]
            (is (contains? (set (map name (keys (:actions mine)))) "start")
                (pr-str (keys (:actions mine)))))))

      (testing "8 · the agent sets the day"
        (let [r (rpc agent "waymark_invoke" {:kind "day_plan" :id plan-id :action "set"})]
          (is (false? (:isError r)) (text-of r))
          (is (= "set" (:state (doc-of r)))))))))
