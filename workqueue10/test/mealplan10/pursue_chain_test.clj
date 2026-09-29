(ns mealplan10.pursue-chain-test
  "GRAIL 1/3 over the REAL mealplan10 chain: waymark10's pursue_test
  proves pursue! against a trimmed copy, so this pins the declared
  remedies where they live — grocery_list.finalize → plan-is-planned →
  plan.finalize → all-days-covered-gate → plan_day.assign_meal (→
  meal-fits-day → assign_off_theme → meal-is-listed → meal.accept).
  A change to any of those guards' :remedies breaks the walk here.
  Needs the waymark10_test database."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [calendar10.source :as es]
            [mealplan10.main :as main]
            [next.jdbc :as jdbc]
            [waymark10.client :as c]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db])
  (:import (java.time Instant)))

(def ^:private tables
  ["meals" "meal_lines" "rotations" "plans" "plan_days" "grocery_lists"
   "prep_tasks" "ingredients" "products" "substitutions" "events"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(def ^:dynamic *session* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        ;; no :probe-reads: the cross-kind guards decline in the render
        ;; probe, so the POST answers each refusal with its remedies
        (let [h (engine/handler
                 (engine/engine {:storage st
                                 :resources (main/resources (es/fake-calendar))
                                 :now-fn (constantly
                                          (Instant/parse "2026-01-01T12:00:00Z"))}))]
          (binding [*session* (c/connect "http://test"
                                         {:principal "colton" :handler h})]
            (f)))
        (finally (pg/close! st))))))

(defn- coll [kind]
  (c/get-doc *session* (get-in (c/index *session*) [:resources kind :href])))

(defn- make! [kind input]
  (let [res (c/create! *session* (coll kind) input)]
    (is (c/doc? res) (pr-str res))
    res))

(defn- state-of [doc] (:state (c/get-doc *session* (:self doc))))

;; the envelope carries no id field: a row's id is its self's last segment
(defn- id-of [doc] (last (str/split (str (:self doc)) #"/")))

(defn- days-of
  "The plan's seven born days, by date."
  [plan]
  (into {}
        (map (fn [d] (let [d (c/get-doc *session* (:self d))]
                       [(get-in d [:data :date]) d])))
        (get-in (c/get-doc *session* (:self plan)) [:links :days :embedded])))

(defn- chain!
  "A mexican meal (listed or not), a week from a Tuesday whose other six
  nights are out, and a draft grocery list for it — so the one
  undecided Tuesday is all that stands between the list and ready."
  [start listed?]
  (let [meal (make! :meal {:name (str "Tacos " start) :themes ["mexican"]})
        meal (if listed? (c/act! *session* meal :accept nil) meal)
        plan (make! :plan {:start_date start :weeks 1})
        days (days-of plan)]
    (is (= 7 (count days)) (pr-str (keys days)))
    (doseq [[date d] days :when (not= date start)]
      (is (c/doc? (c/act! *session* d :mark_eating_out nil))))
    {:meal meal :day (get days start) :plan plan
     :glist (make! :grocery_list {:plan_id (id-of plan)})}))

(defn- resolver
  "Which row each remedy acts on — and, for the day, which meal."
  [{:keys [meal day plan]}]
  (fn [door _refused]
    (case door
      "plan.finalize" {:id (id-of plan)}
      ("plan_day.assign_meal" "plan_day.assign_off_theme")
      {:id (id-of day) :input {:meal_id (id-of meal)}}
      "meal.accept" {:id (id-of meal)}
      nil)))

;; 2026-01-06 and 2026-01-13 are Tuesdays: the mexican night

(deftest pursue-readies-the-list-through-the-real-plan
  (let [{:keys [day plan glist] :as rows} (chain! "2026-01-06" true)
        res (c/pursue! *session* glist :finalize nil
                       {:resolve (resolver rows)
                        ;; the meal carries no recipe lines: the
                        ;; hollow-week warning rides plan.finalize
                        :acknowledge ["recipes-attached"]})]
    (is (c/doc? (:done res)) (pr-str res))
    (is (= ["plan_day.assign_meal" "plan.finalize" "grocery_list.finalize"]
           (mapv :door (:writes res)))
        "plan-is-planned → plan.finalize; all-days-covered → assign_meal")
    (is (= "planned" (state-of day)))
    (is (= "planned" (state-of plan)))
    (is (= "ready" (state-of glist)))))

(deftest an-unlisted-meal-routes-through-meal-accept
  (let [{:keys [meal day plan glist] :as rows} (chain! "2026-01-13" false)
        res (c/pursue! *session* glist :finalize nil
                       {:resolve (resolver rows) :dry-run true})]
    (is (:rehearsal res))
    (is (= ["meal.accept" "plan_day.assign_off_theme" "plan_day.assign_meal"
            "plan.finalize" "grocery_list.finalize"]
           (mapv :door (:writes res)))
        (str "meal-fits-day → assign_off_theme; meal-is-listed → "
             "meal.accept: " (pr-str res)))
    (is (= "suggested" (state-of meal)) "the rehearsal writes nothing")
    (is (= "undecided" (state-of day)))
    (is (= "draft" (state-of plan)))
    (is (= "draft" (state-of glist)))))
