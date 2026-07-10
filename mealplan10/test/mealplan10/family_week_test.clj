(ns mealplan10.family-week-test
  "The Priya story, end to end over the ring handler: one family week
  planned, judged, shopped and cooked through the wire — the same
  moves the generic UI and the agent client would make. Needs the
  waymark10_test database; WAYMARK10_TEST_DSN overrides.

  The transcript: activate the rotation → the AI suggests meals and
  the family accepts them (one bulk accept) → create the plan (days
  pre-themed, Sunday from the rotation) → assign meals per day
  (labels engine-written; a wrong-theme assign refused with the
  relation's own sentence; eating out clears the meal arm) → a
  blocking recital lands on the calendar (FakeEvents + discovery) and
  has_conflicts flips on the plan → finalize warns → acknowledge →
  planned → begin refuses before Tuesday, then starts the week →
  prep tasks spawn, one goes on the calendar, complete refuses while
  they're open → the week-board surface composes the plan with both
  calendar events and the attention flag (phase 9b) → the grocery
  list fills, checks off, completes → the week completes. A second
  plan abandons and its task cascades to cancelled."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [mealplan10.event-source :as es]
            [mealplan10.main :as main]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.mirror :as mirror]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["meals" "rotations" "plans" "grocery_lists" "prep_tasks" "events"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)
(def ^:dynamic *feed* nil)
(def ^:dynamic *clock* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          feed (es/fake-events)
          clock (atom (Instant/parse "2026-07-08T12:00:00Z"))]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources (main/resources feed)
                                  :surfaces main/surfaces
                                  :now-fn (fn [] @clock)})]
          (binding [*eng* eng
                    *h* (engine/handler eng)
                    *feed* feed
                    *clock* clock]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(def priya-headers {"x-waymark-principal" "priya"})

(defn- req
  ([method uri] (req method uri nil {}))
  ([method uri body] (req method uri body {}))
  ([method uri body headers]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path
                   :headers (merge priya-headers headers)}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- created!
  "POST a create, assert 201, return the parsed envelope."
  [plural body]
  (let [resp (req :post (str "/api/" plural) body)]
    (is (= 201 (:status resp)) (str plural " create: " (:body resp)))
    (json resp)))

(defn- id-of [env] (last (str/split (:self env) #"/")))

(defn- act!
  "POST an action, assert 200, return the parsed envelope."
  ([self action] (act! self action nil {}))
  ([self action body] (act! self action body {}))
  ([self action body headers]
   (let [resp (req :post (str self "/-/" (name action)) body headers)]
     (is (= 200 (:status resp))
         (str self " " (name action) ": " (:status resp) " " (:body resp)))
     (json resp))))

(defn- refuse!
  "POST an action, assert the status, return the parsed problem."
  [self action body status]
  (let [resp (req :post (str self "/-/" (name action)) body)]
    (is (= status (:status resp))
        (str self " " (name action) " answered " (:status resp)
             ", wanted " status ": " (:body resp)))
    (json resp)))

(defn- get! [self]
  (let [resp (req :get self)]
    (is (= 200 (:status resp)))
    (json resp)))

(defn- suggest-meal! [body]
  (created! "meals" body))

(defn- day-of [env date]
  (first (filter #(= date (:date %)) (get-in env [:data :days]))))

;; ── the week ────────────────────────────────────────────────────────
;; 2026-07-14 is a Tuesday; the week runs Tue 14 … Mon 20.

(deftest priya-plans-a-week
  ;; ── the rotation ──────────────────────────────────────────────────
  (let [rotation (created! "rotations" {})]
    (is (= ["breakfast for dinner" "indian" "greek" "soup night"]
           (get-in rotation [:data :themes]))
        "a blank create lands the starter themes")
    (act! (:self rotation) :activate)

    ;; ── the AI suggests; the family accepts ─────────────────────────
    (let [tacos (suggest-meal!
                 {:name "Carnitas tacos" :themes ["mexican"]
                  :recipe "# Carnitas tacos\n\npork shoulder 1400g…"
                  :prep_minutes 45 :thaw_hours 12})
          elote (suggest-meal! {:name "Elote corn" :themes ["mexican"]})
          burgers (suggest-meal! {:name "Smash burgers" :themes ["american"]})
          stir-fry (suggest-meal! {:name "Chicken stir fry" :themes ["asian"]})
          brisket (suggest-meal!
                   {:name "Traeger brisket" :themes ["bbq"]
                    :recipe "# Traeger brisket\n\nbrisket 2500g, 225F until 203F internal…"
                    :prep_minutes 60 :thaw_hours 24})
          pancakes (suggest-meal! {:name "Pancake supper"
                                   :themes ["breakfast for dinner"]})
          pizza (suggest-meal! {:name "Sheet-pan pizza" :themes ["pizza"]})
          spaghetti (suggest-meal! {:name "Spaghetti and meatballs"
                                    :themes ["italian"]})]
      (testing "accepting a suggestion is idempotent — the retry replays"
        (act! (:self tacos) :accept)
        (let [again (act! (:self tacos) :accept)]
          (is (= "on_list" (:state again)))))
      (doseq [m [elote burgers stir-fry brisket pancakes pizza]]
        (act! (:self m) :accept))
      (testing "the bulk door: the last suggestion joins by accept_many"
        (let [resp (req :post "/api/meals/-/accept_many"
                        {:ids [(id-of spaghetti)]})
              report (json resp)]
          (is (= 200 (:status resp)))
          (is (= "bulk_report" (:kind report)))
          (is (= 1 (get-in report [:data :succeeded])))))

      ;; ── the plan: days pre-themed, Sunday from the rotation ───────
      (let [plan (created! "plans" {:start_date "2026-07-14" :weeks 1})
            self (:self plan)]
        (is (= "2026-07-20" (get-in plan [:data :end_date]))
            "the far boundary materialized at birth")
        (is (= ["mexican" "american" "asian" "pizza" "bbq"
                "breakfast for dinner" "italian"]
               (mapv :theme (get-in plan [:data :days])))
            "weekdays fixed, Sunday pre-themed from the rotation")
        (is (= (id-of rotation) (get-in plan [:data :rotation_id]))
            "a blank rotation_id resolved to the active rotation")

        (testing "a wrong-theme assign is refused with the relation's sentence"
          (let [p (refuse! self :assign_meal
                           {:date "2026-07-15" :meal_id (id-of tacos)} 409)]
            (is (str/starts-with? (:detail p)
                                  "That meal doesn't serve 2026-07-15"))
            (is (= ["plan.set_sunday_theme" "plan.assign_off_theme"]
                   (:remedies p)))))

        (testing "assigning meals writes the engine-maintained labels"
          (let [env (act! self :assign_meal {:date "2026-07-14"
                                             :meal_id (id-of tacos)})]
            (is (= "Carnitas tacos" (:meal_name (day-of env "2026-07-14")))))
          (act! self :assign_meal {:date "2026-07-15" :meal_id (id-of burgers)})
          (act! self :assign_meal {:date "2026-07-16" :meal_id (id-of stir-fry)})
          (act! self :assign_meal {:date "2026-07-18" :meal_id (id-of brisket)})
          (act! self :assign_meal {:date "2026-07-19" :meal_id (id-of pancakes)})
          (act! self :assign_meal {:date "2026-07-20" :meal_id (id-of spaghetti)}))

        (testing "a side dish rides the meal arm, labeled the same way"
          (let [env (act! self :add_side_dish {:date "2026-07-14"
                                               :meal_id (id-of elote)})]
            (is (= "Elote corn" (:side_dish_name (day-of env "2026-07-14"))))))

        (testing "eating out covers Friday and clears nothing else"
          (let [env (act! self :mark_eating_out {:date "2026-07-17"
                                                 :where "Blaze Pizza"})
                friday (day-of env "2026-07-17")]
            (is (true? (:eating_out friday)))
            (is (= "Blaze Pizza" (:eating_out_where friday)))
            (is (true? (get-in env [:data :all_days_covered]))
                "the coverage fact flipped with the last day")))

        (testing "assigning over an eating-out day clears the other arm"
          (let [env (act! self :assign_meal {:date "2026-07-17"
                                             :meal_id (id-of pizza)})
                friday (day-of env "2026-07-17")]
            (is (nil? (:eating_out friday)))
            (is (nil? (:eating_out_where friday)))
            (is (= "Sheet-pan pizza" (:meal_name friday))))
          ;; …and Priya changes her mind back: Friday is a night out
          (act! self :mark_eating_out {:date "2026-07-17"
                                       :where "Blaze Pizza"}))

        ;; ── the recital lands on the calendar ─────────────────────────
        (testing "a blocking event discovered on the feed flips the plan"
          (es/seed! *feed* "uid-recital@2026-07-16"
                    {:title "Piano recital" :date "2026-07-16"
                     :kind "blocking"})
          (is (= 1 (mirror/discover! *eng* :event)))
          (let [env (get! self)]
            (is (= 1 (get-in env [:data :calendar_conflicts])))
            (is (true? (get-in env [:data :has_conflicts])))))

        ;; ── finalize: warned, acknowledged, planned ───────────────────
        (testing "finalize warns about the conflict and names the acknowledge"
          (let [p (refuse! self :finalize nil 409)]
            (is (= "Waymark-Acknowledge" (get-in p [:acknowledge :header])))
            (is (= ["calendar-clear"] (get-in p [:acknowledge :names])))
            (is (str/includes? (-> p :warnings first :reason)
                               "1 calendar conflict(s) overlap this week"))))
        (let [env (act! self :finalize nil
                        {"waymark-acknowledge" "calendar-clear"})]
          (is (= "planned" (:state env))))

        ;; ── begin: the clock gate holds, then opens ───────────────────
        (testing "the week cannot start before Tuesday"
          (let [p (refuse! self :begin nil 409)]
            (is (= "The plan starts 2026-07-14." (:detail p)))
            (is (= {:at "2026-07-14"} (:becomes_available p)))))
        (reset! *clock* (Instant/parse "2026-07-14T18:00:00Z"))
        (is (= "active" (:state (act! self :begin))))

        ;; ── prep tasks: spawn, schedule, gate the close ───────────────
        (let [thaw (created! "prep_tasks"
                             {:plan_id (id-of plan) :date "2026-07-18"
                              :meal_name "Traeger brisket" :task_type "thaw"
                              :due_at "2026-07-17T14:00:00Z"})
              cook (created! "prep_tasks"
                             {:plan_id (id-of plan) :date "2026-07-18"
                              :meal_name "Traeger brisket" :task_type "cook"
                              :due_at "2026-07-18T13:00:00Z"})]
          (testing "the open tasks gate the week's close, by count"
            (let [p (refuse! self :complete nil 409)]
              (is (str/starts-with? (:detail p) "2 prep task(s) are still open"))
              (is (= ["prep_task.complete" "prep_task.cancel"] (:remedies p)))))

          (testing "the thaw step goes on the family calendar"
            ;; the agent creates the calendar event externally; the
            ;; feed discovers it, and the task records the ref
            (es/seed! *feed* "uid-thaw@2026-07-17"
                      {:title "Thaw the brisket" :date "2026-07-17"})
            (mirror/discover! *eng* :event)
            (let [events (json (req :get "/api/events?external_id=uid-thaw%402026-07-17"))
                  event-self (get-in events [:data :items 0 :self])]
              (is (some? event-self) (pr-str events))
              (is (= "scheduled"
                     (:state (act! (:self thaw) :schedule
                                   {:event_id (last (str/split event-self #"/"))}))))))

          ;; ── the week board: the decision screen, composed ─────────
          (testing "the week board shows the conflicted week whole"
            (is (= "/api/surfaces/week-board/{anchor-id}"
                   (get-in (json (req :get "/api/.well-known/waymark"))
                           [:surfaces :week-board :href]))
                "well-known lists the declared surface")
            (let [resp (req :get (str "/api/surfaces/week-board/"
                                      (id-of plan)))
                  board (json resp)]
              (is (= 200 (:status resp)))
              (is (= "surface" (:kind board)))
              (is (= ["finalize"] (:showcase board)))
              (is (true? (get-in board [:attention :has_conflicts]))
                  "the conflicted week is flagged for the dashboard")
              (is (= "active" (get-in board [:anchor :state]))
                  "the anchor arrives as its full envelope")
              (is (= 1 (get-in board [:anchor :data :calendar_conflicts])))
              (let [items (get-in board [:members :calendar :items])]
                (is (= #{"Piano recital · 2026-07-16"
                         "Thaw the brisket · 2026-07-17"}
                       (into #{} (map :summary) items))
                    "both calendar members ride the declared edge")
                (is (not-any? #(contains? % :data) items)
                    "member items are envelope-minus-data"))))

          ;; ── the grocery list ──────────────────────────────────────────
          (let [grocery (created! "grocery_lists" {:plan_id (id-of plan)})
                gself (:self grocery)]
            (act! gself :add_item {:name "pork shoulder 1400g"
                                   :category "meat" :meals ["Carnitas tacos"]})
            (act! gself :add_item {:name "brisket 2500g"
                                   :category "meat" :meals ["Traeger brisket"]})
            (act! gself :add_item {:name "tortillas" :category "pantry"
                                   :meals ["Carnitas tacos"]})
            (testing "adding the same item merges instead of duplicating"
              (let [env (act! gself :add_item {:name "tortillas"
                                               :quantity "2 packs"})]
                (is (= 3 (count (get-in env [:data :items]))))
                (is (= "2 packs" (:quantity (last (get-in env [:data :items])))))))
            (is (= "ready" (:state (act! gself :finalize)))
                "the plan is planned/active, so the list may follow it")
            (act! gself :check_item {:name "pork shoulder 1400g"})
            (act! gself :check_item {:name "brisket 2500g"})
            (testing "complete refuses while anything is unchecked"
              (let [p (refuse! gself :complete nil 409)]
                (is (str/includes? (:detail p) "unchecked"))))
            (testing "an accidental tap has a one-tap way back"
              (act! gself :uncheck_item {:name "brisket 2500g"})
              (act! gself :check_item {:name "brisket 2500g"}))
            (act! gself :check_item {:name "tortillas"})
            (let [env (act! gself :complete)]
              (is (= "done" (:state env)))
              (is (true? (get-in env [:data :all_items_checked])))))

          ;; ── the week closes ───────────────────────────────────────────
          (act! (:self thaw) :complete)
          (act! (:self cook) :complete)
          (let [env (act! self :complete)]
            (is (= "done" (:state env)))
            (is (= 0 (get-in env [:data :open_tasks])))))))))

;; ── the abandon cascade, on the app's own edge ──────────────────────

(deftest abandoning-a-plan-cancels-its-tasks
  (let [plan (created! "plans" {:start_date "2026-07-21" :weeks 1})
        task (created! "prep_tasks"
                       {:plan_id (id-of plan) :date "2026-07-21"
                        :meal_name "Carnitas tacos" :task_type "thaw"
                        :due_at "2026-07-20T14:00:00Z"})]
    (is (= 1 (get-in (get! (:self plan)) [:data :open_tasks])))
    (let [env (act! (:self plan) :abandon)]
      (is (= "abandoned" (:state env)))
      (is (= 0 (get-in env [:data :open_tasks]))
          "the response already tells the post-cascade truth"))
    (let [tenv (get! (:self task))]
      (is (= "cancelled" (:state tenv))
          "the owns cascade cancelled the open task"))))
