(ns mealplan10.pantry-stock-test
  "Spec-pantry era 2, end to end over the real ring handler: the
  pantry as three fields and one law.

  - the two regimes of the stocked fact: a staple (nil
    shelf_life_days) is sticky — restocked once, stocked until a
    human marks it out; a perishable decays on the clock with no
    write and no poll (the price_is_stale pattern, pointed at the
    pantry);
  - :flips-at is scheduling advice, never law — the exact expiry
    instant for a perishable, nothing for a staple or an out row;
  - the purchase stamp: grocery_list.complete restocks every CHECKED
    item carrying an ingredient ref in the same commit, advertised as
    :touches; unref'd manual items stamp nothing.

  Needs the waymark10_test database; WAYMARK10_TEST_DSN overrides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [calendar10.source :as es]
            [mealplan10.main :as main]
            [mealplan10.resources.ingredient :as ingredient]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.maintainer :as maintainer]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire])
  (:import (java.time Instant LocalDate)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)
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
                                  :now-fn (fn [] @clock)})]
          (binding [*eng* eng
                    *h* (engine/handler eng)
                    *clock* clock]
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

(defn- fresh-key [] {"idempotency-key" (str (random-uuid))})

(defn- restock! [self] (act! self :restock nil (fresh-key)))

(defn- id-of [env] (last (str/split (:self env) #"/")))

(defn- active-ingredient! [nm & [body]]
  (let [i (created! "ingredients" (merge {:name nm} body))]
    (act! (:self i) :accept)))

(defn- day-env
  "One plan_day's full envelope, by its plan and date."
  [plan-env date]
  (let [b (json (req :get (str "/api/plan_days?plan_id=" (id-of plan-env)
                               "&date=" date)))]
    (json (req :get (:self (first (get-in b [:data :items])))))))

;; ── 1. the two regimes ──────────────────────────────────────────────

(deftest the-staple-regime-is-sticky
  (let [salt (active-ingredient! "Salt (pst)")]
    (testing "never stocked = not stocked"
      (is (false? (get-in salt [:data :stocked])))
      (is (nil? (get-in salt [:data :stocked_on]))))
    (testing "restock stamps today and clears the override"
      (let [env (restock! (:self salt))]
        (is (= "2026-07-08" (get-in env [:data :stocked_on])))
        (is (false? (get-in env [:data :out])))
        (is (true? (get-in env [:data :stocked])))))
    (testing "a staple carries no clock — stocked is sticky until a
              human notices the jar is empty"
      (is (nil? (get-in (get-env (:self salt)) [:data :shelf_life_days])))
      (let [env (act! (:self salt) :mark_out)]
        (is (true? (get-in env [:data :out])))
        (is (false? (get-in env [:data :stocked])))
        (is (= "2026-07-08" (get-in env [:data :stocked_on]))
            "the override beats the clock; the stamp stays history")))
    (testing "restock again — the way back from we're-out"
      (let [env (restock! (:self salt))]
        (is (true? (get-in env [:data :stocked])))
        (is (false? (get-in env [:data :out])))))))

(deftest the-perishable-clock-flips-with-no-write
  (let [cream (active-ingredient! "Cream (pst)")]
    (testing "update_details grows the rough clock"
      (let [env (act! (:self cream) :update_details {:shelf_life_days 10})]
        (is (= 10 (get-in env [:data :shelf_life_days])))))
    (testing "a fresh restock is stocked today"
      (let [env (restock! (:self cream))]
        (is (= "2026-07-08" (get-in env [:data :stocked_on])))
        (is (true? (get-in env [:data :stocked])))))
    (testing "ten days on, the clock flips it — no write, no poll"
      (let [version (get-in (get-env (:self cream)) [:meta :version])]
        (reset! *clock* (Instant/parse "2026-07-18T06:00:00Z"))
        (maintainer/sweep-clocks! *eng*)
        (let [gone (get-env "/api/ingredients" "state=active&stocked=false")]
          (is (some #(= (:self cream) (:self %))
                    (get-in gone [:data :items]))
              "the expired perishable answers the stocked=false filter"))
        (is (false? (get-in (get-env (:self cream)) [:data :stocked])))
        (is (= version (get-in (get-env (:self cream)) [:meta :version]))
            "the flip wrote no version")))
    (testing "a later restock winds the clock again"
      (let [env (restock! (:self cream))]
        (is (= "2026-07-18" (get-in env [:data :stocked_on])))
        (is (true? (get-in env [:data :stocked])))))
    (reset! *clock* (Instant/parse "2026-07-08T12:00:00Z"))
    (maintainer/sweep-clocks! *eng*)))

;; ── 2. the flip instant, unit-level ─────────────────────────────────

(deftest the-flip-instant-is-scheduling-advice
  (let [flips (:flips-at ingredient/stocked)]
    (is (= (Instant/parse "2026-07-18T00:00:00Z")
           (flips {:data {:stocked_on (LocalDate/parse "2026-07-08")
                          :shelf_life_days 10}}))
        "stocked_on + shelf_life_days at start-of-day UTC")
    (is (nil? (flips {:data {:stocked_on (LocalDate/parse "2026-07-08")}}))
        "a staple hands the maintainer nothing — the absence of a clock")
    (is (nil? (flips {:data {:shelf_life_days 10}}))
        "never stocked, nothing to decay")
    (is (nil? (flips {:data {:stocked_on (LocalDate/parse "2026-07-08")
                             :shelf_life_days 10
                             :out true}}))
        "an out row is already false — no flip to schedule")))

;; ── 3. the seeding door ─────────────────────────────────────────────

(deftest bulk-restock-seeds-the-pantry
  (reset! *clock* (Instant/parse "2026-07-08T12:00:00Z"))
  (let [flour (active-ingredient! "Flour (pst)")            ; staple
        yogurt (active-ingredient! "Yogurt (pst)"           ; perishable
                                   {:shelf_life_days 14})
        pepper (active-ingredient! "Pepper (pst)")
        ids (mapv id-of [flour yogurt pepper])]
    (testing "the fan-out is as honestly non-idempotent as the single
              verdict — no Idempotency-Key, no write"
      (let [resp (req :post "/api/ingredients/-/restock_many" {:ids ids})]
        (is (= 428 (:status resp)))
        (is (nil? (get-in (get-env (:self flour)) [:data :stocked_on])))))
    (testing "one call seeds the selection"
      (let [resp (req :post "/api/ingredients/-/restock_many" {:ids ids}
                      (fresh-key))
            doc (json resp)]
        (is (= 200 (:status resp)) (str (:status resp) " " (:body resp)))
        (is (= "bulk_report" (:kind doc)))
        (is (= {:succeeded 3 :refused 0 :failed 0}
               (select-keys (:data doc) [:succeeded :refused :failed])))))
    (testing "each selected row carries the stamp — the staple sticky,
              the perishable's clock wound"
      (doseq [env (map (comp get-env :self) [flour yogurt pepper])]
        (is (= "2026-07-08" (get-in env [:data :stocked_on]))
            (str (get-in env [:data :name]) " stamped with today"))
        (is (false? (get-in env [:data :out])))
        (is (true? (get-in env [:data :stocked])))))))

;; ── 4. the purchase stamp ───────────────────────────────────────────

(deftest a-completed-shop-stamps-the-pantry
  (let [thighs (active-ingredient! "Chicken thighs (pst)")
        cumin (active-ingredient! "Cumin (pst)")
        candles (active-ingredient! "Candles control (pst)")
        _ (act! (:self cumin) :mark_out)   ; "we're out of cumin"
        plan (created! "plans" {:start_date "2026-01-06" :weeks 1})]
    ;; cover the week so the plan (and then the list) can finalize
    (doseq [i (range 7)]
      (act! (:self (day-env plan (format "2026-01-%02d" (+ 6 i))))
            :mark_eating_out))
    (act! (:self plan) :finalize nil
          {"waymark-acknowledge" "calendar-clear"})
    (let [glist (created! "grocery_lists" {:plan_id (id-of plan)})
          gself (:self glist)]
      (act! gself :add_item {:name "Chicken thighs (pst)"
                             :ingredient_id (id-of thighs)})
      (act! gself :add_item {:name "Cumin (pst)"
                             :ingredient_id (id-of cumin)})
      (act! gself :add_item {:name "birthday candles"})
      (act! gself :finalize)
      (act! gself :check_item {:name "Chicken thighs (pst)"})
      (act! gself :check_item {:name "Cumin (pst)"})
      (act! gself :check_item {:name "birthday candles"})
      (testing "the blast radius is advertised before the shop closes
                (an available action renders its :touches)"
        (is (= [{:kind "ingredient" :action "restock" :may true}]
               (get-in (get-env gself) [:actions :complete :touches]))))
      (let [env (act! gself :complete)]
        (is (= "done" (:state env))))
      (testing "every checked ref'd item restocked in the same commit"
        (let [t (get-env (:self thighs))]
          (is (= "2026-07-08" (get-in t [:data :stocked_on]))
              "stamped with today, the engine's clock")
          (is (false? (get-in t [:data :out])))
          (is (true? (get-in t [:data :stocked]))))
        (let [c (get-env (:self cumin))]
          (is (false? (get-in c [:data :out]))
              "the stamp beats the standing we're-out verdict")
          (is (true? (get-in c [:data :stocked])))))
      (testing "the unref'd manual item stamped nothing"
        (let [ctrl (get-env (:self candles))]
          (is (nil? (get-in ctrl [:data :stocked_on])))
          (is (false? (get-in ctrl [:data :stocked]))))))))
