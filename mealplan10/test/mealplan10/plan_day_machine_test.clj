(ns mealplan10.plan-day-machine-test
  "The promoted day, exercised as a machine: state = the decision.
  Born with the plan (the birth door — the 201 already counts seven
  undecided days), cycling undecided → planned → eating_out with each
  edge nulling what it leaves, the side-dish undo pair, the declared
  (plan, date) uniqueness refusing a duplicate as 409, and the
  plan-editable gate: days edit in draft, freeze under review
  (planned), and open again for the LIVE week (active) — plan.begin's
  sentence, finally true. Needs the waymark10_test database."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [calendar10.source :as es]
            [mealplan10.main :as main]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

(def ^:private tables
  ["meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(def ^:dynamic *h* nil)
(def ^:dynamic *clock* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          clock (atom (Instant/parse "2026-07-08T12:00:00Z"))]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources (main/resources (es/fake-calendar))
                                  ;; main.clj's posture: the render
                                  ;; probe carries the read hooks, so
                                  ;; the day's pickers enumerate
                                  :probe-reads true
                                  :now-fn (fn [] @clock)})]
          (binding [*h* (engine/handler eng)
                    *clock* clock]
            (f)))
        (finally (pg/close! st))))))

(def ^:private colton {"x-waymark-principal" "colton"})

(defn- req
  ([method uri] (req method uri nil {}))
  ([method uri body] (req method uri body {}))
  ([method uri body headers]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path
                   :headers (merge colton headers)}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- created! [plural body]
  (let [resp (req :post (str "/api/" plural) body)]
    (is (= 201 (:status resp)) (str plural ": " (:body resp)))
    (json resp)))

(defn- etag-of [self] (get-in (req :get self) [:headers "ETag"]))

(defn- act!
  ([self action] (act! self action nil))
  ([self action body]
   (let [resp (req :post (str self "/-/" (name action)) body
                   {"if-match" (etag-of self)})]
     (is (= 200 (:status resp))
         (str self " " (name action) ": " (:status resp) " " (:body resp)))
     (json resp))))

(defn- refuse! [self action body status]
  (let [resp (req :post (str self "/-/" (name action)) body
                  {"if-match" (etag-of self)})]
    (is (= status (:status resp))
        (str self " " (name action) " answered " (:status resp)
             ", wanted " status ": " (:body resp)))
    (json resp)))

(defn- id-of [env] (last (str/split (:self env) #"/")))

(defn- listed-meal! [nm themes]
  (let [m (created! "meals" {:name nm :themes themes})]
    (act! (:self m) :accept)
    m))

(deftest the-day-is-a-machine
  (let [tacos (listed-meal! "Carnitas tacos" ["mexican"])
        elote (listed-meal! "Elote corn" ["mexican"])
        beans (listed-meal! "Charro beans" ["mexican"])
        rice (listed-meal! "Arroz rojo" ["mexican"])
        plan (created! "plans" {:start_date "2026-07-14" :weeks 1})]

    (testing "born with the plan: the 201 counts seven undecided days"
      (is (= 7 (get-in plan [:data :total_days])))
      (is (= 7 (get-in plan [:data :undecided_days])))
      (is (false? (get-in plan [:data :all_days_covered]))))

    (testing "the plan's days embed, date-ordered, badge honest"
      (let [env (json (req :get (:self plan)))]
        (is (= 7 (get-in env [:links :days :badge])))
        (is (= 7 (count (get-in env [:links :days :embedded]))))))

    (let [days (get-in (json (req :get (str "/api/plan_days?plan_id="
                                            (id-of plan)
                                            "&page%5Bsize%5D=10")))
                       [:data :items])
          tue (:self (first days))]
      (testing "the cycle: each edge nulls what it leaves"
        (let [d (act! tue :assign_meal {:meal_id (id-of tacos)})]
          (is (= "planned" (:state d)))
          (is (= "Carnitas tacos" (get-in d [:data :meal_name]))))
        (act! tue :add_side_dish {:side_id (id-of elote)})
        (let [d (act! tue :mark_eating_out {:where "Culvers"})]
          (is (= "eating_out" (:state d)))
          (is (nil? (get-in d [:data :meal_id])) "the meal stepped aside")
          (is (nil? (get-in d [:data :meal_name])) "…and its label followed")
          (is (nil? (get-in d [:data :side_dish_id])) "sides included"))
        (let [d (act! tue :assign_meal {:meal_id (id-of tacos)})]
          (is (= "planned" (:state d)))
          (is (nil? (get-in d [:data :eating_out_where]))))
        (let [d (act! tue :clear_day)]
          (is (= "undecided" (:state d)))
          (is (= "mexican" (get-in d [:data :theme])) "the theme survives")))

      (testing "two sides, then the declarative free-slot refusal"
        (act! tue :assign_meal {:meal_id (id-of tacos)})
        (act! tue :add_side_dish {:side_id (id-of elote)})
        (act! tue :add_side_dish {:side_id (id-of beans)})
        (let [p (refuse! tue :add_side_dish {:side_id (id-of rice)} 409)]
          (is (str/includes? (:detail p) "already has 2 side dishes")))
        (testing "…and the undo pair walks one back"
          (act! tue :remove_side_dish {:side_id (id-of beans)})
          (act! tue :add_side_dish {:side_id (id-of rice)})))

      (testing "one day per (plan, date): the index refuses the twin"
        (let [resp (req :post "/api/plan_days"
                        {:plan_id (id-of plan) :date "2026-07-14"})]
          (is (= 409 (:status resp)))
          (is (str/includes? (str (:detail (json resp))) "already exists"))))

      (testing "the plan-editable gate: draft edits, planned freezes,
                the LIVE week opens again"
        (doseq [d (rest days)]
          (act! (:self d) :mark_eating_out nil))
        (act! (:self plan) :finalize nil)
        (let [p (refuse! tue :clear_day nil 409)]
          (is (str/includes? (:detail p) "frozen"))
          (is (= ["plan.reopen" "plan.begin"] (:remedies p))))
        (reset! *clock* (Instant/parse "2026-07-14T18:00:00Z"))
        (act! (:self plan) :begin)
        (let [d (act! tue :mark_eating_out {:where "surprise date night"})]
          (is (= "eating_out" (:state d))
              "the live week bends around reality — begin's sentence, true"))))))

(deftest discovery-is-the-envelope-and-a-planned-day-swaps-in-place
  ;; waymark-1pq: the acceptance set RENDERS (probe-reads) — the
  ;; picker enum is the eligible-meal discovery the client could only
  ;; try-and-fail toward before — and a decided day re-decides in
  ;; place, never routing through undecided. Asian meals only: the
  ;; other deftest's mexican list stays out of Thursday's set either
  ;; way the vars run.
  (let [stir-fry (listed-meal! "Chicken stir fry" ["asian"])
        beef (listed-meal! "Beef and broccoli" ["asian"])
        noodles (listed-meal! "Garlic noodles" ["asian"])
        burgers (listed-meal! "Smash burgers" ["american"])
        plan (created! "plans" {:start_date "2026-07-21" :weeks 1})
        days (get-in (json (req :get (str "/api/plan_days?plan_id="
                                          (id-of plan)
                                          "&page%5Bsize%5D=10")))
                     [:data :items])
        thu (:self (nth days 2))
        asian #{(id-of stir-fry) (id-of beef) (id-of noodles)}]

    (testing "the undecided day's picker enumerates the meals that
              serve its night — the admitted set IS the enum"
      (let [env (json (req :get thu))]
        (is (= "asian" (get-in env [:data :theme])))
        (is (= asian
               (set (get-in env [:actions :assign_meal :input
                                 :properties :meal_id :enum])))
            "asian meals admitted, the american one excluded")))

    (act! thu :assign_meal {:meal_id (id-of stir-fry)})

    (testing "the planned day advertises the swap door AND the side
              picker's same admitted set"
      (let [env (json (req :get thu))]
        (is (contains? (:actions env) :assign_meal)
            "re-assign is available in :planned — no clear_day detour")
        (is (= asian
               (set (get-in env [:actions :add_side_dish :input
                                 :properties :side_id :enum]))))))

    (testing "an admitted side attaches; the excluded one refuses with
              the acceptance set's own sentence"
      (act! thu :add_side_dish {:side_id (id-of noodles)})
      (let [p (refuse! thu :add_side_dish {:side_id (id-of burgers)} 409)]
        (is (str/starts-with?
             (:detail p) "That side doesn't serve this day's theme night"))))

    (testing "a planned day swaps its meal in one step — state never
              leaves :planned, the new meal lands, the side survives"
      (let [d (act! thu :assign_meal {:meal_id (id-of beef)})]
        (is (= "planned" (:state d)))
        (is (= (id-of beef) (get-in d [:data :meal_id])))
        (is (= "Beef and broccoli" (get-in d [:data :meal_name])))
        (is (= "Garlic noodles" (get-in d [:data :side_dish_name]))
            "the swap keeps the day's sides — same meal arm")))

    (testing "…and a night out re-marks where without clearing first"
      (act! thu :mark_eating_out {:where "Culvers"})
      (let [d (act! thu :mark_eating_out {:where "Cafe Rio"})]
        (is (= "eating_out" (:state d)))
        (is (= "Cafe Rio" (get-in d [:data :eating_out_where])))))))
