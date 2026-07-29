(ns mealplan10.anchor-flex-test
  "Spec-pantry era 4, end to end over the real ring handler: the
  anchor/flex solver. A two-week plan, one grocery list per trip
  (the covers_from/covers_until window), and the coverage law walked
  inside compile_from_plan:

  - anchors (flour: long shelf life, no opened clock) ride trip 1,
    one purchase serving both weeks' uses, grams summed;
  - flex by the raw clock (cilantro) splits — a purchase at trip 1
    cannot reach day 12, so the day-12 use opens at trip 2;
  - flex by the opened clock (cream: fine unopened, dead five days
    after opening) splits the same way, and a long-enough opened
    clock (sour cream) keeps one purchase riding trip 1;
  - era 3 still wins first: a stocked staple lands in
    assumed_on_hand on every compiled list, never in items;
  - a list with NO window compiles the era-1 way, everything at once;
  - a use no trip can honestly serve still lands on the latest trip
    before it — best-effort, never dropped;
  - a half-set window is unrepresentable at birth (window_paired is
    required at create).

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

(defn- compile! [gself] (act! gself :compile_from_plan nil (fresh-key)))

;; ── the two-trip fortnight ──────────────────────────────────────────
;; 2026-01-06 is a Tuesday; the fortnight runs Jan 6–19. The week-1
;; dinner cooks Wed Jan 7 (day 2, american); the week-2 dinner cooks
;; Sat Jan 17 (day 12, bbq). Trip 1 shops Jan 6 (covers week 1),
;; trip 2 shops Jan 13 (day 8, covers week 2).

(deftest the-anchor-flex-solver-splits-the-fortnight
  (let [flour (active-ingredient! "Flour (af)" {:category "pantry"})
        cilantro (active-ingredient! "Cilantro (af)" {:category "produce"})
        cream (active-ingredient! "Cream (af)" {:category "dairy"})
        sour-cream (active-ingredient! "Sour cream (af)" {:category "dairy"})
        salt (active-ingredient! "Salt (af)" {:category "spices"})
        ;; the clocks: flour keeps ~forever raw and opening changes
        ;; nothing; cilantro dies raw in 4 days; cream survives sealed
        ;; but not opened; sour cream's opened clock spans the gap
        _ (act! (:self flour) :update_details {:shelf_life_days 180})
        _ (act! (:self cilantro) :update_details {:shelf_life_days 4})
        _ (act! (:self cream) :update_details
                {:shelf_life_days 21 :opened_shelf_life_days 5})
        _ (act! (:self sour-cream) :update_details
                {:shelf_life_days 21 :opened_shelf_life_days 14})
        ;; the staple, seeded once — sticky stocked (era 3's law)
        _ (restock! (:self salt))
        ;; 10 ¢/100 g flour and 200 ¢/100 g cilantro; the dairy stays
        ;; unpriceable, the era-1 nil-safety
        _ (priced-product! flour "winco" "WinCo flour (af)" 1000 100)
        _ (priced-product! cilantro "winco" "WinCo cilantro (af)" 100 200)
        week1 (listed-meal! "Week one dinner (af)" ["american"])
        week2 (listed-meal! "Week two BBQ (af)" ["bbq"])
        line! (fn [meal ing grams]
                (created! "meal_lines" {:meal_id (id-of meal)
                                        :ingredient_id (id-of ing)
                                        :grams grams}))
        _ (line! week1 flour 100)      ; est 10
        _ (line! week1 cilantro 30)    ; est 60
        _ (line! week1 cream 200)
        _ (line! week1 sour-cream 100)
        _ (line! week1 salt 5)
        _ (line! week2 flour 200)      ; est 20
        _ (line! week2 cilantro 20)    ; est 40
        _ (line! week2 cream 100)
        _ (line! week2 sour-cream 50)
        _ (line! week2 salt 5)
        plan (created! "plans" {:start_date "2026-01-06" :weeks 2})
        _ (act! (:self (day-env plan "2026-01-07")) :assign_meal
                {:meal_id (id-of week1)})
        _ (act! (:self (day-env plan "2026-01-17")) :assign_meal
                {:meal_id (id-of week2)})
        ;; the three lists exist before any compile — each compile
        ;; reads its siblings' covers_from as the trip schedule
        trip1 (created! "grocery_lists" {:plan_id (id-of plan)
                                         :covers_from "2026-01-06"
                                         :covers_until "2026-01-12"})
        trip2 (created! "grocery_lists" {:plan_id (id-of plan)
                                         :covers_from "2026-01-13"
                                         :covers_until "2026-01-19"})
        whole (created! "grocery_lists" {:plan_id (id-of plan)})
        env1 (compile! (:self trip1))
        env2 (compile! (:self trip2))
        items1 (by-name env1)
        items2 (by-name env2)]

    (testing "the anchor: one flour purchase at trip 1 serves both weeks"
      (let [it (items1 "Flour (af)")]
        (is (= "300 g" (:quantity it)) "100 + 200, both uses on one jar")
        (is (= ["Week one dinner (af)" "Week two BBQ (af)"] (:meals it)))
        (is (= 30 (:est_cost_cents it)) "10 + 20 — both uses' line ests"))
      (is (nil? (items2 "Flour (af)")) "absent from trip 2"))

    (testing "flex by the raw clock: cilantro splits across the trips"
      (let [it (items1 "Cilantro (af)")]
        (is (= "30 g" (:quantity it)) "the day-2 use only")
        (is (= ["Week one dinner (af)"] (:meals it)))
        (is (= 60 (:est_cost_cents it)) "this purchase's uses alone"))
      (let [it (items2 "Cilantro (af)")]
        (is (= "20 g" (:quantity it)) "the day-12 use opens at trip 2")
        (is (= ["Week two BBQ (af)"] (:meals it)))
        (is (= 40 (:est_cost_cents it)))))

    (testing "flex by the opened clock: cream opened day 2 is dead by
              day 12, so the day-12 use opens at trip 2"
      (is (= "200 g" (:quantity (items1 "Cream (af)"))))
      (is (= "100 g" (:quantity (items2 "Cream (af)"))))
      (is (nil? (:est_cost_cents (items1 "Cream (af)")))
          "unpriceable stays blank, era-1 nil-safety"))

    (testing "an opened clock long enough keeps one purchase: sour
              cream rides trip 1, summed"
      (let [it (items1 "Sour cream (af)")]
        (is (= "150 g" (:quantity it)) "100 + 50 — day 12 rides the tub")
        (is (= ["Week one dinner (af)" "Week two BBQ (af)"] (:meals it))))
      (is (nil? (items2 "Sour cream (af)")) "absent from trip 2"))

    (testing "era 3 still wins first: the stocked staple is assumed on
              hand on both compiled lists, never an item"
      (is (nil? (items1 "Salt (af)")))
      (is (nil? (items2 "Salt (af)")))
      (is (= [{:name "Salt (af)"
               :meals ["Week one dinner (af)" "Week two BBQ (af)"]}]
             (get-in env1 [:data :assumed_on_hand])))
      (is (= [{:name "Salt (af)"
               :meals ["Week one dinner (af)" "Week two BBQ (af)"]}]
             (get-in env2 [:data :assumed_on_hand]))))

    (testing "the trips' totals"
      (is (= 4 (get-in env1 [:data :total_items]))
          "flour, cilantro, cream, sour cream")
      (is (= 2 (get-in env2 [:data :total_items]))
          "cilantro and cream — the flex alone"))

    (testing "a list with no window on the same plan compiles the
              era-1 way: everything, one list"
      (let [env (compile! (:self whole))
            items (by-name env)]
        (is (= 4 (get-in env [:data :total_items])))
        (is (= "300 g" (:quantity (items "Flour (af)"))))
        (is (= "50 g" (:quantity (items "Cilantro (af)"))) "30 + 20")
        (is (= "300 g" (:quantity (items "Cream (af)"))))
        (is (= "150 g" (:quantity (items "Sour cream (af)"))))
        (is (= [{:name "Salt (af)"
                 :meals ["Week one dinner (af)" "Week two BBQ (af)"]}]
               (get-in env [:data :assumed_on_hand])))))))

;; ── the best-effort landing ─────────────────────────────────────────

(deftest an-uncoverable-use-lands-on-the-latest-prior-trip
  (let [cilantro (active-ingredient! "Cilantro (af7)"
                                     {:category "produce"})
        _ (act! (:self cilantro) :update_details {:shelf_life_days 4})
        bbq (listed-meal! "Lone BBQ (af7)" ["bbq"])
        _ (created! "meal_lines" {:meal_id (id-of bbq)
                                  :ingredient_id (id-of cilantro)
                                  :grams 25})
        plan (created! "plans" {:start_date "2026-01-06" :weeks 2})
        _ (act! (:self (day-env plan "2026-01-17")) :assign_meal
                {:meal_id (id-of bbq)})
        ;; one trip for the whole fortnight — day 1, and nothing later
        only (created! "grocery_lists" {:plan_id (id-of plan)
                                        :covers_from "2026-01-06"
                                        :covers_until "2026-01-19"})
        env (compile! (:self only))
        it ((by-name env) "Cilantro (af7)")]
    (testing "no trip can honestly serve day 12, but the purchase
              still lands — the best the schedule offers, not dropped"
      (is (some? it))
      (is (= "25 g" (:quantity it)))
      (is (= ["Lone BBQ (af7)"] (:meals it))))))

;; ── the window's contract at birth ──────────────────────────────────

(deftest a-half-set-window-cannot-be-born
  (let [plan (created! "plans" {:start_date "2026-03-03" :weeks 1})]
    (testing "one end without the other is refused at create — 409"
      (let [resp (req :post "/api/grocery_lists"
                      {:plan_id (id-of plan)
                       :covers_from "2026-03-03"})]
        (is (= 409 (:status resp)) (str (:body resp)))))
    (testing "an inverted window is refused the same way"
      (let [resp (req :post "/api/grocery_lists"
                      {:plan_id (id-of plan)
                       :covers_from "2026-03-09"
                       :covers_until "2026-03-03"})]
        (is (= 409 (:status resp)) (str (:body resp)))))
    (testing "both ends, in order, are born windowed"
      (let [g (created! "grocery_lists" {:plan_id (id-of plan)
                                         :covers_from "2026-03-03"
                                         :covers_until "2026-03-09"})]
        (is (= "2026-03-03" (get-in g [:data :covers_from])))
        (is (true? (get-in g [:data :window_paired])))))))
