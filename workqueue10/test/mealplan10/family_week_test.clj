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
            [calendar10.source :as es]
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
  ["meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists" "prep_tasks"
   "ingredients" "products" "substitutions" "events"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)
(def ^:dynamic *feed* nil)
(def ^:dynamic *clock* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          feed (es/fake-calendar)
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

(defn- etag-of [self]
  (get-in (req :get self) [:headers "ETag"]))

(defn- act!
  "POST an action the way an honest client would — current ETag along
  (fenced doors demand it; unfenced ones ignore it) — assert 200,
  return the parsed envelope."
  ([self action] (act! self action nil {}))
  ([self action body] (act! self action body {}))
  ([self action body headers]
   (let [resp (req :post (str self "/-/" (name action)) body
                   (merge {"if-match" (etag-of self)} headers))]
     (is (= 200 (:status resp))
         (str self " " (name action) ": " (:status resp) " " (:body resp)))
     (json resp))))

(defn- refuse!
  "POST an action (ETag along), assert the status, return the problem."
  [self action body status]
  (let [resp (req :post (str self "/-/" (name action)) body
                  {"if-match" (etag-of self)})]
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

(defn- day-env
  "One plan_day's full envelope, by its plan and date."
  [plan-env date]
  (let [b (json (req :get (str "/api/plan_days?plan_id=" (id-of plan-env)
                               "&date=" date)))]
    (json (req :get (:self (first (get-in b [:data :items])))))))

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
        (is (= 7 (get-in plan [:data :total_days]))
            "born WITH its days — the 201 already counts them")
        (is (= 7 (get-in plan [:data :undecided_days])))
        (let [days (json (req :get (str "/api/plan_days?plan_id="
                                        (id-of plan) "&page%5Bsize%5D=10")))]
          (is (= ["mexican" "american" "asian" "pizza" "bbq"
                  "breakfast for dinner" "italian"]
                 (mapv #(get-in % [:fields :theme])
                       (get-in days [:data :items])))
              "weekdays fixed, Sunday pre-themed from the rotation, date-ordered"))
        (is (= (id-of rotation) (get-in plan [:data :rotation_id]))
            "a blank rotation_id resolved to the active rotation")

        (testing "a wrong-theme assign is refused with the guard's sentence"
          (let [wed (day-env plan "2026-07-15")
                p (refuse! (:self wed) :assign_meal
                           {:meal_id (id-of tacos)} 409)]
            (is (str/starts-with? (:detail p)
                                  "That meal doesn't serve this day"))
            (is (= ["plan_day.set_sunday_theme" "plan_day.assign_off_theme"]
                   (:remedies p)))))

        (testing "assigning covers the day: state = the decision, the
                  label the engine's"
          (let [tue (day-env plan "2026-07-14")
                env (act! (:self tue) :assign_meal {:meal_id (id-of tacos)})]
            (is (= "planned" (:state env)))
            (is (= "Carnitas tacos" (get-in env [:data :meal_name]))))
          (doseq [[date m] [["2026-07-15" burgers] ["2026-07-16" stir-fry]
                            ["2026-07-18" brisket] ["2026-07-19" pancakes]
                            ["2026-07-20" spaghetti]]]
            (act! (:self (day-env plan date)) :assign_meal
                  {:meal_id (id-of m)})))

        (testing "a side dish joins the planned day, labeled the same way"
          (let [tue (day-env plan "2026-07-14")
                env (act! (:self tue) :add_side_dish {:side_id (id-of elote)})]
            (is (= "Elote corn" (get-in env [:data :side_dish_name])))))

        (testing "eating out covers Friday — the state IS the fact"
          (let [fri (day-env plan "2026-07-17")
                env (act! (:self fri) :mark_eating_out {:where "Blaze Pizza"})]
            (is (= "eating_out" (:state env)))
            (is (= "Blaze Pizza" (get-in env [:data :eating_out_where]))))
          (let [env (get! self)]
            (is (= 0 (get-in env [:data :undecided_days])))
            (is (true? (get-in env [:data :all_days_covered]))
                "the composed fact flipped with the last day's state")))

        (testing "assigning over an eating-out day: the edge nulls what
                  it leaves"
          (let [fri (day-env plan "2026-07-17")
                env (act! (:self fri) :assign_meal {:meal_id (id-of pizza)})]
            (is (= "planned" (:state env)))
            (is (nil? (get-in env [:data :eating_out_where])))
            (is (= "Sheet-pan pizza" (get-in env [:data :meal_name])))
            ;; …and Priya changes her mind back: Friday is a night out
            (act! (:self env) :mark_eating_out {:where "Blaze Pizza"})))

        ;; ── the recital lands on the calendar ─────────────────────────
        (testing "a blocking event discovered on the feed flips the plan"
          (es/seed! *feed* "family:recital-2026-07-16"
                    {:title "Piano recital" :date "2026-07-16"
                     :kind "blocking"})
          (is (= 1 (mirror/discover! *eng* :event)))
          (let [env (get! self)]
            (is (= 1 (get-in env [:data :calendar_conflicts])))
            (is (true? (get-in env [:data :has_conflicts])))))

        ;; ── finalize: warned, acknowledged, planned ───────────────────
        (testing "finalize warns about the conflict AND the hollow
                  meals (waymark-m6j — none of the six planned
                  dinners carries meal_line rows), naming both
                  acknowledges in one 409"
          (let [p (refuse! self :finalize nil 409)]
            (is (= "Waymark-Acknowledge" (get-in p [:acknowledge :header])))
            (is (= ["calendar-clear" "recipes-attached"]
                   (get-in p [:acknowledge :names])))
            (is (str/includes? (-> p :warnings first :reason)
                               "1 calendar conflict(s) overlap this week"))
            (is (str/includes? (-> p :warnings second :reason)
                               "6 planned day(s) have meals with no recipe"))))
        (let [env (act! self :finalize nil
                        {"waymark-acknowledge"
                         "calendar-clear,recipes-attached"})]
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
                              :assignee "housekeeper"
                              :due_at "2026-07-17T14:00:00Z"})
              cook (created! "prep_tasks"
                             {:plan_id (id-of plan) :date "2026-07-18"
                              :meal_name "Traeger brisket" :task_type "cook"
                              :due_at "2026-07-18T13:00:00Z"})]
          (testing "the assignee filter is the mirror feed's key: one
                    query, only the housekeeper's step"
            (let [items (get-in (json (req :get "/api/prep_tasks?assignee=housekeeper"))
                                [:data :items])]
              (is (= 1 (count items)))
              (is (= (:self thaw) (:self (first items))))))
          (testing "the open tasks gate the week's close, by count"
            (let [p (refuse! self :complete nil 409)]
              (is (str/starts-with? (:detail p) "2 prep task(s) are still open"))
              (is (= ["prep_task.complete" "prep_task.cancel"] (:remedies p)))))

          (testing "the thaw step goes on the family calendar"
            ;; the agent creates the calendar event externally; the
            ;; feed discovers it, and the task records the ref
            (es/seed! *feed* "family:thaw-2026-07-17"
                      {:title "Thaw the brisket" :date "2026-07-17"})
            (mirror/discover! *eng* :event)
            (let [events (json (req :get "/api/events?external_id=family%3Athaw-2026-07-17"))
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
