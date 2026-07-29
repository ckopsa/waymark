(ns mealplan10.grocery-compile-test
  "Spec-pantry era 1, end to end over the real ring handler: the list
  compiles itself. The derivation chain plan → plan_day → meal_line →
  grocery item, walked by compile_from_plan:

  - grams sum per ingredient across every planned night — a meal
    cooked on two nights buys double, honestly;
  - estimates sum from the lines' write-time ests (pricing is linear
    in grams), and a group NO line prices stays blank;
  - every compiled item wears the compiler's stamp (:source \"plan\");
    a re-add by name does not clear it;
  - recompile after a plan edit replaces only the plan-stamped rows —
    hand-added extras survive with their nil provenance.

  Era 3, the pantry consult (the noise fix): a grouped ingredient
  whose stocked fact is true skips the items and lands in
  assumed_on_hand — the compiler's honest record, replaced wholesale
  every compile — while mark_out + recompile moves it back; a manual
  row for an assumed ingredient survives beside the assumption.

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
          clock (atom (Instant/parse "2026-07-08T12:00:00Z"))]
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

(defn- fresh-key [] {"idempotency-key" (str (random-uuid))})

(defn- restock! [self] (act! self :restock nil (fresh-key)))

(defn- id-of [env] (last (str/split (:self env) #"/")))

(defn- active-ingredient! [nm & [body]]
  (let [i (created! "ingredients" (merge {:name nm} body))]
    (act! (:self i) :accept)))

(defn- priced-product! [ing store nm grams cents]
  (let [p (created! "products"
                    {:ingredient_id (id-of ing) :store store :name nm
                     :package_grams grams
                     :sightings [{:seen_on "2026-01-02" :price_cents cents
                                  :source "receipt"}]})]
    (act! (:self p) :confirm_match)))

(defn- listed-meal! [nm themes]
  (let [m (created! "meals" {:name nm :themes themes})]
    (act! (:self m) :accept)))

(defn- day-env
  "One plan_day's full envelope, by its plan and date."
  [plan-env date]
  (let [b (json (req :get (str "/api/plan_days?plan_id=" (id-of plan-env)
                               "&date=" date)))]
    (json (req :get (:self (first (get-in b [:data :items])))))))

(defn- by-name [env]
  (into {} (map (juxt :name identity)) (get-in env [:data :items])))

;; ── the compile ─────────────────────────────────────────────────────
;; 2026-01-06 is a Tuesday (mexican); Wed = american, Thu = asian.

(deftest the-list-compiles-itself
  (let [thighs (active-ingredient! "Chicken thighs (gc)"
                                   {:category "meat"})
        _cilantro (active-ingredient! "Cilantro (gc)"
                                      {:category "produce"})
        _rice (active-ingredient! "Rice (gc)" {:category "pantry"})
        ;; 50 ¢/100 g and 20 ¢/100 g; cilantro stays unpriceable
        _ (priced-product! thighs "winco" "WinCo thighs (gc)" 1000 500)
        _ (priced-product! _rice "winco" "WinCo rice (gc)" 1000 200)
        tacos (listed-meal! "Chicken tacos (gc)" ["mexican" "american"])
        fried-rice (listed-meal! "Chicken fried rice (gc)" ["asian"])
        _ (created! "meal_lines" {:meal_id (id-of tacos)
                                  :ingredient_id (id-of thighs)
                                  :grams 500})           ; est 250
        _ (created! "meal_lines" {:meal_id (id-of tacos)
                                  :ingredient_id (id-of _cilantro)
                                  :grams 30})            ; unpriced
        _ (created! "meal_lines" {:meal_id (id-of fried-rice)
                                  :ingredient_id (id-of thighs)
                                  :grams 300})           ; est 150
        _ (created! "meal_lines" {:meal_id (id-of fried-rice)
                                  :ingredient_id (id-of _rice)
                                  :grams 200})           ; est 40
        plan (created! "plans" {:start_date "2026-01-06" :weeks 1})
        ;; tacos cover TWO nights — the same meal, double groceries
        _ (act! (:self (day-env plan "2026-01-06")) :assign_meal
                {:meal_id (id-of tacos)})
        _ (act! (:self (day-env plan "2026-01-07")) :assign_meal
                {:meal_id (id-of tacos)})
        _ (act! (:self (day-env plan "2026-01-08")) :assign_meal
                {:meal_id (id-of fried-rice)})
        glist (created! "grocery_lists" {:plan_id (id-of plan)})
        gself (:self glist)]
    (act! gself :add_item {:name "birthday candles" :category "party"})

    (testing "the compile walks plan → plan_day → meal_line and groups"
      (let [env (act! gself :compile_from_plan nil (fresh-key))
            items (by-name env)]
        (is (= 4 (get-in env [:data :total_items]))
            "the manual item plus three compiled groups")
        (testing "grams sum across the two taco nights and the stir-fry"
          (let [it (items "Chicken thighs (gc)")]
            (is (= "1300 g" (:quantity it)) "500 + 500 + 300")
            (is (= "meat" (:category it)) "the ingredient's category")
            (is (= ["Chicken tacos (gc)" "Chicken fried rice (gc)"]
                   (:meals it))
                "distinct contributing meal names, in plan order")
            (is (= (id-of thighs) (:ingredient_id it)))
            (is (= 650 (:est_cost_cents it)) "250 + 250 + 150")
            (is (= "plan" (:source it)) "the compiler's stamp")
            (is (false? (:have it)))))
        (testing "an unpriceable group stays blank, honestly"
          (let [it (items "Cilantro (gc)")]
            (is (= "60 g" (:quantity it)) "30 g on each taco night")
            (is (nil? (:est_cost_cents it)))
            (is (= ["Chicken tacos (gc)"] (:meals it)))))
        (testing "a single-night ingredient compiles once"
          (let [it (items "Rice (gc)")]
            (is (= "200 g" (:quantity it)))
            (is (= 40 (:est_cost_cents it)))))
        (testing "the manual item survives, provenance nil"
          (let [it (items "birthday candles")]
            (is (some? it))
            (is (nil? (:source it)))))
        (testing "the derived totals moved in the same response"
          (is (= 690 (get-in env [:data :estimated_total_cents]))
              "650 + 40; the blank groups count zero")
          (is (= 2 (get-in env [:data :priced_items]))))))

    (testing "a re-add by name keeps the compiler's stamp"
      (let [env (act! gself :add_item {:name "Chicken thighs (gc)"
                                      :quantity "2 packs"})
            it ((by-name env) "Chicken thighs (gc)")]
        (is (= "2 packs" (:quantity it)) "the re-add overwrote what it stated")
        (is (= "plan" (:source it)) "…and preserved the provenance")))

    (testing "recompile follows the plan edit: plan rows replaced,
              the manual one kept"
      (act! (:self (day-env plan "2026-01-08")) :clear_day)
      (let [env (act! gself :compile_from_plan nil (fresh-key))
            items (by-name env)]
        (is (= 3 (get-in env [:data :total_items])))
        (let [it (items "Chicken thighs (gc)")]
          (is (= "1000 g" (:quantity it))
              "the cleared night's 300 g left the group — the re-added
               quantity was the old plan row's to lose")
          (is (= 500 (:est_cost_cents it)))
          (is (= ["Chicken tacos (gc)"] (:meals it))))
        (is (nil? (items "Rice (gc)"))
            "the cleared night's only ingredient left the list")
        (is (some? (items "birthday candles"))
            "the hand-added extra survives every recompile")
        (is (nil? (:source (items "birthday candles"))))))))

;; ── the pantry consult (spec-pantry era 3) ──────────────────────────
;; Tuesday 2026-01-06 = mexican, Wednesday = american, as above.

(deftest the-compile-consults-the-pantry
  (let [oil (active-ingredient! "Olive oil (gc3)" {:category "pantry"})
        cumin (active-ingredient! "Cumin (gc3)" {:category "spices"})
        ;; a staple (nil shelf_life_days), restocked once — sticky
        _ (restock! (:self oil))
        ;; "we're out of cumin" — the human override
        _ (act! (:self cumin) :mark_out)
        curry (listed-meal! "Chickpea curry (gc3)" ["mexican"])
        bread (listed-meal! "Flatbread (gc3)" ["american"])
        _ (created! "meal_lines" {:meal_id (id-of curry)
                                  :ingredient_id (id-of oil)
                                  :grams 20})
        _ (created! "meal_lines" {:meal_id (id-of curry)
                                  :ingredient_id (id-of cumin)
                                  :grams 5})
        _ (created! "meal_lines" {:meal_id (id-of bread)
                                  :ingredient_id (id-of oil)
                                  :grams 30})
        plan (created! "plans" {:start_date "2026-01-06" :weeks 1})
        _ (act! (:self (day-env plan "2026-01-06")) :assign_meal
                {:meal_id (id-of curry)})
        _ (act! (:self (day-env plan "2026-01-07")) :assign_meal
                {:meal_id (id-of bread)})
        glist (created! "grocery_lists" {:plan_id (id-of plan)})
        gself (:self glist)]
    ;; the family also wrote the oil down by hand — the manual row is
    ;; theirs, whatever the pantry says
    (act! gself :add_item {:name "Olive oil (gc3)"})

    (testing "a stocked ingredient leaves the list, but never silently"
      (let [env (act! gself :compile_from_plan nil (fresh-key))
            items (by-name env)]
        (is (= [{:name "Olive oil (gc3)"
                 :meals ["Chickpea curry (gc3)" "Flatbread (gc3)"]}]
               (get-in env [:data :assumed_on_hand]))
            "the compiler's honest record: the name and its meals")
        (testing "…the out ingredient compiles as an item, as ever"
          (let [it (items "Cumin (gc3)")]
            (is (= "5 g" (:quantity it)))
            (is (= "plan" (:source it)))))
        (testing "…and the manual row survives beside the assumption"
          (let [it (items "Olive oil (gc3)")]
            (is (some? it))
            (is (nil? (:source it)) "still manual — the compile did
                                     not claim its name")))
        (is (= 2 (get-in env [:data :total_items]))
            "compiled cumin plus the manual oil; no plan-stamped oil")))

    (testing "mark_out + recompile moves the assumption back to items"
      (act! (:self oil) :mark_out)
      (let [env (act! gself :compile_from_plan nil (fresh-key))
            items (by-name env)]
        (is (= [] (get-in env [:data :assumed_on_hand]))
            "replaced wholesale — the oil assumption did not linger")
        (let [it (items "Olive oil (gc3)")]
          (is (= "50 g" (:quantity it)) "20 + 30, both nights")
          (is (= "plan" (:source it))
              "the compile now claims the name — one row per name")
          (is (= ["Chickpea curry (gc3)" "Flatbread (gc3)"]
                 (:meals it))))
        (is (= 2 (get-in env [:data :total_items])))))

    (testing "a restock swings it the other way — wholesale, both
              directions"
      (restock! (:self cumin))
      (let [env (act! gself :compile_from_plan nil (fresh-key))
            items (by-name env)]
        (is (= [{:name "Cumin (gc3)"
                 :meals ["Chickpea curry (gc3)"]}]
               (get-in env [:data :assumed_on_hand]))
            "cumin in, oil out — this compile's record alone")
        (is (nil? (items "Cumin (gc3)"))
            "the restocked ingredient left the items")
        (is (some? (items "Olive oil (gc3)"))
            "the still-out oil stays an item")))))
