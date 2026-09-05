(ns dayplan10.decision-test
  "The decision lived in over the real engine (waymark-i89n.4): what
  the declaration-time world cannot judge — a decision taking its day
  and member from its block, a subject resolving against real rows,
  Go firing the Home Assistant caller exactly once and recording
  started (and recording nothing when the room does not answer),
  change keeping both sentences, the block's skip letting its
  decisions go, and a decision with prep reaching the queue as ONE
  task with source day_plan through the dayplan TaskSource — the thaw
  task's road, drunk in-process.

  The scenarios on decision.clj already prove the verdicts (an
  unresolvable subject refused naming the address, a launch that does
  not say how, Go refused with no Home Assistant wired) through the
  HTTP door in the conformance suite and at declaration time; this
  file proves the EFFECTS.

  The engine is the WHOLE household registry (workqueue10.main), not
  dayplan10's alone, because the prep mirror needs the task kind and
  the confluence — and the day_plan source drinks this same engine
  through engine-ref, the way production wires it. Home Assistant is a
  recording fn on :services, beside the feature token the guard reads.

  The clock is the engine's :now-fn, pinned to a morning on the plan's
  own day; every instant is spelled through dayplan10.zone so the
  assertions hold in whatever zone the environment names.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides.
  Run: cd workqueue10 && clojure -M:test --focus dayplan10.decision-test"
  (:require [calendar10.source :as gcal]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dayplan10.zone :as zone]
            [next.jdbc :as jdbc]
            [waymark10.dev :as dev]
            [waymark10.server.engine :as engine]
            [waymark10.server.invoke :as inv]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]
            [workqueue10.confluence :as conf]
            [workqueue10.main :as main]
            [workqueue10.sources.dayplan :as dayplan]
            [workqueue10.sources.hub :as hub])
  (:import (java.time LocalDate LocalTime)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ;; the WHOLE folded registry's tables (task_queue_test's rule): this
  ;; engine boots every kind main/resources declares, so a fixture
  ;; that drops less boots into another suite's residue
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

(def ^:private today (LocalDate/parse "2026-01-06"))       ; a Tuesday
(def ^:private yesterday (LocalDate/parse "2026-01-05"))

(defn- at
  "A clock time on a date, in the household zone."
  ([hh mm] (at today hh mm))
  ([date hh mm] (zone/at date (LocalTime/of (int hh) (int mm)))))

(def ^:private clock
  "The engine's clock — eight on the plan's morning, before any window."
  (atom (at 8 0)))

(def ^:private ui-base "https://rod.kopsa.info")

(def ^:private ha-calls
  "Every service Home Assistant was asked to fire: [service data]."
  (atom []))

(defn- fire-home-assistant!
  "The recording twin of sources.homeassistant/call-service!: one
  service answers as the room would when it is down, the rest record."
  [service data]
  (when (= "switch/unreachable" service)
    (throw (ex-info "home assistant answered 502 for switch/unreachable"
                    {:status 502})))
  (swap! ha-calls conj [service data])
  nil)

(def ^:private colton
  (t/principal {:id "colton" :type :human :display "Colton"}))

(defn- create! [kind body]
  (:row (inv/create! *eng* kind body {:principal colton})))

(defn- act! [kind id action body]
  (inv/invoke! *eng* kind id action body {:principal colton}))

(defn- refusal
  "What a refused write says: {:problem … :guard … :detail …}, nil
  when it went through."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           {:problem (:waymark10/problem d)
            :guard (some-> (:guard d) name)
            :detail (:detail d)
            :message (ex-message e)}))))

(defn- row [kind id] (dev/row *eng* kind id))

(def ^:private member-seq (atom 0))
(defn- fresh-member [] (str "member-" (swap! member-seq inc)))

(defn- plan! [date]
  (create! :day_plan {:date (str date) :member (fresh-member)}))

(defn- block-named [plan-id nm]
  (->> (dev/rows *eng* :block)
       (some #(when (and (= plan-id (get-in % [:data :plan_id]))
                         (= nm (get-in % [:data :context_name])))
                %))))

(defn- workday-block!
  "A plan for today and its Workday block."
  []
  (let [plan (plan! today)]
    [plan (block-named (:id plan) "Workday")]))

(defn- decide!
  "A decision into a block, the ordinary way."
  [block body]
  (create! :decision (merge {:block_id (:id block) :kind "work" :order 1} body)))

(defn- prep-tasks []
  (->> (dev/rows *eng* :task)
       (filter #(= "day_plan" (get-in % [:data :source])))))

(defn- task-for [decision-id]
  (some #(when (= (str "day_plan:" decision-id) (get-in % [:data :external_id])) %)
        (prep-tasks)))

;; the household's engine, whole: the day plan's source drinks it
;; through engine-ref, and Home Assistant is the recording fn beside
;; the feature token
(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          engine-ref (atom nil)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [eng (mirror/with-push
                   (engine/engine
                    {:storage st
                     :resources (main/resources
                                 {"chore" (conf/fake-source)
                                  "meal" (conf/fake-source)
                                  "todo" (conf/fake-source)
                                  "gtasks" (conf/fake-source)
                                  "day_plan" (dayplan/engine-source
                                              {:engine-ref engine-ref
                                               :ui-base ui-base
                                               :principal "workqueue10"})}
                                 {"hub" (hub/source)}
                                 (gcal/fake-calendar))
                     :now-fn (fn [] @clock)
                     :services {:features ["home_assistant"]
                                :home-assistant fire-home-assistant!}}))]
          (reset! engine-ref eng)
          (binding [*eng* eng]
            (create! :context {:name "Workday" :default_shapes ["workday"]
                               :default_spans [{:from "09:00" :to "12:00"}
                                               {:from "13:00" :to "17:00"}]
                               :default_order 1})
            (f)))
        (finally (pg/close! st))))))

;; ── § 1 birth ───────────────────────────────────────────────────────

(deftest a-decision-takes-its-day-and-member-from-its-block
  (let [[plan block] (workday-block!)
        d (decide! block {:kind "pick" :text "Tonight's film" :order 3})]
    (is (= :planned (:state d)))
    (is (= today (get-in d [:data :date])) "the date is the block's")
    (is (= (get-in plan [:data :member]) (get-in d [:data :member]))
        "the member is the plan's")
    (is (= "Workday · 2026-01-06" (get-in d [:data :block_name]))
        "the block's label rides the ref")
    (is (false? (get-in d [:data :has_prep])) "no prep sentence, no prep fact")
    (testing "a decision needs a block that stands"
      (let [r (refusal #(create! :decision {:block_id "01HZQ7Y7F2R3W4V5X6Y7Z8A9F9"
                                            :kind "work" :text "Nowhere" :order 1}))]
        (is (= "on-a-planned-block" (:guard r)))
        (is (re-find #"add the block to the day first" (str (:detail r))))))
    (testing "…and one that is still planned"
      (act! :block (:id block) :skip nil)
      (let [r (refusal #(decide! block {:text "Into a skipped block"}))]
        (is (= "on-a-planned-block" (:guard r)))
        (is (re-find #"Workday block is skipped" (str (:detail r))))))))

(deftest a-subject-is-a-row-that-stands
  (let [[plan block] (workday-block!)]
    (testing "an address naming a row this house serves is admitted"
      (is (nil? (refusal #(decide! block {:text "Replan the day"
                                          :subject (str "/api/day_plans/" (:id plan))})))))
    (testing "an address whose row does not exist is refused naming it"
      (let [addr "/api/day_plans/01HZQ7Y7F2R3W4V5X6Y7Z8A9F8"
            r (refusal #(decide! block {:text "A day nobody planned" :subject addr}))]
        (is (= "subject-resolves" (:guard r)))
        (is (re-find (re-pattern (java.util.regex.Pattern/quote addr)) (str (:detail r)))
            "the refusal names the address")))
    (testing "a collection this house does not serve is refused the same way"
      (is (= "subject-resolves"
             (:guard (refusal #(decide! block {:text "Elsewhere"
                                               :subject "/api/nothings/01HZQ7Y7F2R3W4V5X6Y7Z8A9F7"}))))))
    (testing "a query string is not an address"
      (is (= "subject-resolves"
             (:guard (refusal #(decide! block {:text "A search"
                                               :subject "/api/tasks?status=open"}))))))))

;; ── § 2 go ──────────────────────────────────────────────────────────

(deftest go-fires-the-service-exactly-once-and-records-started
  (reset! ha-calls [])
  (let [[_ block] (workday-block!)
        d (decide! block {:text "Porch lights on"
                          :launch {:type "service" :service "light/turn_on"
                                   :data {:entity_id "light.porch"}}})]
    (act! :decision (:id d) :start nil)
    (is (= :started (:state (row :decision (:id d)))) "the record says you went")
    (is (= [["light/turn_on" {:entity_id "light.porch"}]] @ha-calls)
        "…and the room heard it exactly once")
    (testing "a second Go re-fires nothing — the verdict was given"
      (refusal #(act! :decision (:id d) :start nil))
      (is (= 1 (count @ha-calls))))
    (testing "a link fires nothing here — the card opens it"
      (let [d2 (decide! block {:kind "pick" :text "Tonight's film" :order 2
                               :launch {:type "href" :href "https://letterboxd.com/"}})]
        (act! :decision (:id d2) :start nil)
        (is (= :started (:state (row :decision (:id d2)))))
        (is (= 1 (count @ha-calls)))))
    (testing "a room that does not answer refuses the start, and nothing is recorded"
      (let [d3 (decide! block {:text "The unreachable switch" :order 3
                               :launch {:type "service" :service "switch/unreachable"}})
            r (refusal #(act! :decision (:id d3) :start nil))]
        (is (some? r) "the start did not go through")
        (is (re-find #"502" (str (:message r) (:detail r))))
        (is (= :planned (:state (row :decision (:id d3))))
            "the record does not say 'went' while the room stayed dark")))))

;; ── § 3 the other doors ─────────────────────────────────────────────

(deftest change-records-what-it-became
  (let [[_ block] (workday-block!)
        d (decide! block {:kind "agenda" :text "The deck estimate"})]
    (act! :decision (:id d) :start nil)
    (act! :decision (:id d) :change {:changed_to "The fence quote instead"})
    (let [d' (row :decision (:id d))]
      (is (= :changed (:state d')))
      (is (= "The fence quote instead" (get-in d' [:data :changed_to])))
      (is (= "The deck estimate" (get-in d' [:data :text]))
          "the decision said this, the day said that, and both are kept"))
    (testing "changed is a tomb"
      (is (= :wrong-state (:problem (refusal #(act! :decision (:id d) :start nil))))))
    (testing "done is reached from planned as well as started, and stays done"
      (let [d2 (decide! block {:text "The porch railing" :order 2})]
        (act! :decision (:id d2) :finish nil)
        (is (= :done (:state (row :decision (:id d2)))))
        (is (some? (refusal #(act! :decision (:id d2) :start nil))))))
    (testing "skip is undone by reopen, from planned or started"
      (let [d3 (decide! block {:text "The gutters" :order 3})]
        (act! :decision (:id d3) :start nil)
        (act! :decision (:id d3) :skip nil)
        (is (= :skipped (:state (row :decision (:id d3)))))
        (act! :decision (:id d3) :reopen nil)
        (is (= :planned (:state (row :decision (:id d3)))))))))

(deftest skipping-the-block-lets-its-decisions-go
  (let [[_ block] (workday-block!)
        planned (decide! block {:text "The porch railing" :order 1})
        started (decide! block {:text "The deck estimate" :order 2})
        done (decide! block {:text "The gutters" :order 3})]
    (act! :decision (:id started) :start nil)
    (act! :decision (:id done) :finish nil)
    (act! :block (:id block) :skip nil)
    (is (= :skipped (:state (row :decision (:id planned)))))
    (is (= :skipped (:state (row :decision (:id started)))))
    (is (= :done (:state (row :decision (:id done))))
        "a finished decision is the record, not something to let go")))

;; ── § 4 prep is a task ──────────────────────────────────────────────

(deftest a-decision-with-prep-is-one-task-in-the-queue
  (let [[_ block] (workday-block!)
        bag (decide! block {:kind "prepare" :text "Bag by the door"
                            :prep "Pack the bag" :order 1})
        _ (decide! block {:text "The porch railing" :order 2})]
    (is (true? (get-in bag [:data :has_prep])) "the discovery fact reads the prep")
    (testing "one discovery pass mints exactly one task, for the decision with prep"
      (is (= 1 (mirror/discover! *eng* :task)))
      (let [t (task-for (:id bag))]
        (is (some? t) "identity is the decision's id under the day_plan tag")
        (is (= "day_plan" (get-in t [:data :source])))
        (is (= "Pack the bag" (get-in t [:data :title])) "titled by the prep sentence")
        (is (= "open" (get-in t [:data :status])))
        (is (= (at yesterday 18 0) (get-in t [:data :due_at]))
            "due the evening before the block's date, six in the household zone")
        (is (= (str ui-base "/api/decisions/" (:id bag)) (get-in t [:data :source_href]))
            "source_href leads back to the decision")
        (is (= "For: Bag by the door — Workday · 2026-01-06" (get-in t [:data :detail])))))
    (testing "a started decision's prep still stands in the queue"
      (let [keys' (decide! block {:kind "prepare" :text "Keys on the hook"
                                  :prep "Find the spare keys" :order 3})]
        (act! :decision (:id keys') :start nil)
        (is (= 1 (mirror/discover! *eng* :task)))
        (is (= "open" (get-in (task-for (:id keys')) [:data :status])))))
    (testing "skipping the decision reads as gone — the queue drops the task"
      (act! :decision (:id bag) :skip nil)
      (mirror/resync! *eng* :task)
      (is (= "dropped" (get-in (task-for (:id bag)) [:data :status]))))
    (testing "a decision skipped before discovery is never minted"
      (let [gone (decide! block {:kind "prepare" :text "Never mind"
                                 :prep "Nothing" :order 4})]
        (act! :decision (:id gone) :skip nil)
        (is (zero? (mirror/discover! *eng* :task)))
        (is (nil? (task-for (:id gone))))))))
