(ns mealplan10.meal-details-test
  "The meal catalog's field-test fixes, end to end over the real ring
  handler (waymark-v4a, waymark-93i, and the meal slice of
  waymark-ltr):

  - update_details, the human fix-it door: rename a meal, zero a
    phantom thaw (0 is a value, not a clear), and CLEAR a field with
    an explicit null — the decode boundary keeps null distinct from
    absent (apply-defaults fills only absent keys), so the handler's
    (contains? inp k) reads the author's intent;
  - catalog health: ?total_ingredients=0 / has_recipe=false are real
    queries — the empty shells among the meals stop hiding behind
    the cheap ones;
  - unpriced ≠ free, by law since waymark-vpv (2026-07-29): a meal
    whose lines none price — and a meal with no lines at all —
    reports est_cost_cents nil (meal-est-cost's :when-empty :absent;
    the old SUM-over-empty-coalesces-to-0 deviation retired), so an
    unknown cost can no longer sort or budget as free; the facets
    still name the shelf (priced_ingredients=0 beside
    has_recipe=true, filterable), and a priced line reports the
    honest sum.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
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

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(def ^:dynamic *h* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)
          feed (es/fake-calendar)
          clock (atom (Instant/parse "2026-07-28T12:00:00Z"))]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources (main/resources feed)
                                  :now-fn (fn [] @clock)})]
          (binding [*h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar (the family-week pattern: ETag along) ─────────────

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
    (is (= 201 (:status resp)) (str plural " create: " (:body resp)))
    (json resp)))

(defn- get-env [self & [query]]
  (json (req :get (cond-> self query (str "?" query)))))

(defn- etag-of [self]
  (get-in (req :get self) [:headers "ETag"]))

(defn- act!
  ([self action] (act! self action nil {}))
  ([self action body] (act! self action body {}))
  ([self action body headers]
   (let [resp (req :post (str self "/-/" (name action)) body
                   (merge {"if-match" (etag-of self)} headers))]
     (is (= 200 (:status resp))
         (str self " " (name action) ": " (:status resp) " " (:body resp)))
     (json resp))))

(defn- refuse!
  "The fenced refusal read: If-Match rides along so the answer is the
  action's own, never the fence's."
  [self action body status]
  (let [resp (req :post (str self "/-/" (name action)) body
                  {"if-match" (etag-of self)})]
    (is (= status (:status resp))
        (str self " " (name action) " answered " (:status resp)
             ", wanted " status ": " (:body resp)))
    (json resp)))

(defn- id-of [env] (last (str/split (:self env) #"/")))

(defn- selves [env] (into #{} (map :self) (get-in env [:data :items])))

(defn- active-ingredient! [nm & [body]]
  (let [i (created! "ingredients" (merge {:name nm} body))]
    (act! (:self i) :accept)))

(defn- listed-meal! [body]
  (let [m (created! "meals" body)]
    (act! (:self m) :accept)))

;; ── 1. update_details: what create froze, a human can fix ───────────

(deftest update-details-fixes-what-create-froze
  (let [meal (listed-meal! {:name "Smoked tri-tip (md1)" :themes ["bbq"]
                            :thaw_hours 12 :prep_minutes 30 :servings 4})
        self (:self meal)]
    (testing "a rename touches nothing else — absent keys leave fields"
      (let [env (act! self :update_details
                      {:name "Smoked tri-tip sandwiches (md1)"})]
        (is (= "Smoked tri-tip sandwiches (md1)" (get-in env [:data :name])))
        (is (= 12 (get-in env [:data :thaw_hours])))
        (is (= 30 (get-in env [:data :prep_minutes])))
        (is (= 4 (get-in env [:data :servings])))))
    (testing "zeroing a phantom thaw — 0 is a value, not a clear"
      (let [env (act! self :update_details {:thaw_hours 0})]
        (is (= 0 (get-in env [:data :thaw_hours])))
        (is (= 30 (get-in env [:data :prep_minutes])))))
    (testing "an explicit null CLEARS — the boundary keeps null
              distinct from absent, and the handler honors it"
      (let [env (act! self :update_details {:prep_minutes nil
                                            :servings 6})]
        (is (nil? (get-in env [:data :prep_minutes])))
        (is (= 6 (get-in env [:data :servings])))
        (is (= 0 (get-in env [:data :thaw_hours]))
            "the zeroed thaw stayed a zero — absent left it alone")))
    (testing "name never clears — a meal keeps its name"
      (refuse! self :update_details {:name nil} 422)
      (is (= "Smoked tri-tip sandwiches (md1)"
             (get-in (get-env self) [:data :name]))))))

;; ── 2. catalog health: the empty shells stop hiding ─────────────────

(deftest the-catalog-answers-which-meals-have-no-recipe
  (let [bare (listed-meal! {:name "Phantom freezer meal (md2)"
                            :themes ["mexican"]})
        thighs (active-ingredient! "Chicken thighs (md2)")
        full (listed-meal! {:name "Chicken fajitas (md2)"
                            :themes ["mexican"]})
        _ (created! "meal_lines" {:meal_id (id-of full)
                                  :ingredient_id (id-of thighs)
                                  :grams 500})]
    (testing "the counts and the composed bool are facts on the row"
      (let [b (get-env (:self bare))]
        (is (= 0 (get-in b [:data :total_ingredients])))
        (is (false? (get-in b [:data :has_recipe])))
        (is (nil? (get-in b [:data :est_cost_cents]))
            "no lines at all: the cost is unknown, not $0 (waymark-vpv)"))
      (is (true? (get-in (get-env (:self full)) [:data :has_recipe]))))
    (testing "?total_ingredients=0 is a real query now"
      (let [zero (selves (get-env "/api/meals"
                                  "state=on_list&total_ingredients=0"))]
        (is (contains? zero (:self bare)))
        (is (not (contains? zero (:self full))))))
    (testing "?has_recipe=false — the actionable facet"
      (let [empties (selves (get-env "/api/meals"
                                     "state=on_list&has_recipe=false"))]
        (is (contains? empties (:self bare)))
        (is (not (contains? empties (:self full))))))
    (testing "the range half: meals with at least one line"
      (let [some' (selves (get-env "/api/meals"
                                   "state=on_list&total_ingredients_gte=1"))]
        (is (contains? some' (:self full)))
        (is (not (contains? some' (:self bare))))))))

;; ── 3. unpriced ≠ free ──────────────────────────────────────────────

(deftest an-unpriced-recipe-is-not-a-free-dinner
  (let [sirloin (active-ingredient! "Sirloin (md3)")   ; no products price it
        steak (listed-meal! {:name "Grilled sirloin (md3)" :themes ["bbq"]})
        line (created! "meal_lines" {:meal_id (id-of steak)
                                     :ingredient_id (id-of sirloin)
                                     :grams 700})]
    (testing "the line itself is honestly blank"
      (is (nil? (get-in line [:data :est_cost_cents])))
      (is (false? (get-in line [:data :priced]))))
    (testing "the meal's est is ABSENT — no priced information is nil,
              not $0 (waymark-vpv retired the SUM-over-empty
              deviation) — and the facets still say unpriced"
      (let [env (get-env (:self steak))]
        (is (nil? (get-in env [:data :est_cost_cents]))
            "a line with no estimate contributes no information")
        (is (= 0 (get-in env [:data :priced_ingredients])))
        (is (= 1 (get-in env [:data :total_ingredients])))
        (is (true? (get-in env [:data :has_recipe])))))
    (testing "?has_recipe=true&priced_ingredients=0 is the unpriced
              shelf — distinguishable from both empty and cheap"
      (let [unpriced (selves
                      (get-env "/api/meals"
                               "state=on_list&has_recipe=true&priced_ingredients=0"))]
        (is (contains? unpriced (:self steak)))))
    (testing "a meal with a priced line reports the sum"
      (let [thighs (active-ingredient! "Chicken thighs (md3)")
            kirkland (created! "products"
                               {:ingredient_id (id-of thighs)
                                :store "costco"
                                :name "Kirkland thighs (md3)"
                                :package_grams 2720
                                :sightings [{:seen_on "2026-07-01"
                                             :price_cents 1899
                                             :source "receipt"}]})
            _ (act! (:self kirkland) :confirm_match)
            bbq (listed-meal! {:name "BBQ thighs (md3)" :themes ["bbq"]})
            _ (created! "meal_lines" {:meal_id (id-of bbq)
                                      :ingredient_id (id-of thighs)
                                      :grams 1400})
            env (get-env (:self bbq))]
        (is (= 980 (get-in env [:data :est_cost_cents]))   ; 1400×70÷100
            "the priced line's write-time estimate IS the meal's sum")
        (is (= 1 (get-in env [:data :priced_ingredients])))
        (is (not (contains?
                  (selves (get-env "/api/meals"
                                   "state=on_list&has_recipe=true&priced_ingredients=0"))
                  (:self bbq)))
            "the priced meal left the unpriced shelf")))))
