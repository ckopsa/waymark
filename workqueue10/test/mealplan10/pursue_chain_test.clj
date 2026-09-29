(ns mealplan10.pursue-chain-test
  "GRAIL 1/3 over the REAL mealplan10 chain: waymark10's pursue_test
  proves pursue! against a trimmed copy, so this pins the declared
  remedies where they live — grocery_list.finalize → plan-is-planned →
  plan.finalize → all-days-covered-gate → plan_day.assign_meal (→
  meal-fits-day → assign_off_theme, a confirm door pursue stops at).
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

(defn- choices
  "Which meal the day gets: the refusals themselves name every row —
  plan-is-planned the plan, all-days-covered the undecided day."
  [{:keys [meal]}]
  {"plan_day.assign_meal" {:input {:meal_id (id-of meal)}}
   "plan_day.assign_off_theme" {:input {:meal_id (id-of meal)}}})

;; 2026-01-06 and 2026-01-13 are Tuesdays: the mexican night

(deftest pursue-readies-the-list-through-the-real-plan
  (let [{:keys [day plan glist] :as rows} (chain! "2026-01-06" true)
        res (c/pursue! *session* glist :finalize nil
                       {:choices (choices rows)
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

;; meal-fits-day accepts only on-list meals, and its remedies are
;; set_sunday_theme (refused off Sunday) and assign_off_theme — whose
;; confirm a person must give, so the walk halts there, not at
;; meal.accept
(deftest an-unlisted-meal-stops-at-the-off-theme-confirm
  (let [{:keys [meal day plan glist] :as rows} (chain! "2026-01-13" false)
        res (c/pursue! *session* glist :finalize nil
                       {:choices (choices rows) :dry-run true})
        off (some #(when (= "plan_day.assign_off_theme" (:door %)) %)
                  (:blocked-on res))]
    (is (:rehearsal res))
    (is (nil? (:done res)) (pr-str res))
    (is (= ["grocery_list.finalize" "plan.finalize" "plan_day.assign_meal"]
           (mapv :door (:stack res)))
        (str "plan-is-planned → plan.finalize; all-days-covered → "
             "assign_meal: " (pr-str res)))
    (is (:confirm off) (pr-str res))
    (is (= "The day gets a meal that does not match its theme night."
           (:consequence off)))
    (is (empty? (:writes res)))
    (is (= "suggested" (state-of meal)) "the rehearsal writes nothing")
    (is (= "undecided" (state-of day)))
    (is (= "draft" (state-of plan)))
    (is (= "draft" (state-of glist)))))

;; GRAIL 2b: the refusal names the uncovered day — the earliest, when
;; two wait — and assign_meal's remedy binds it
(deftest finalize-names-the-earlier-uncovered-day
  (let [plan (make! :plan {:start_date "2026-01-20" :weeks 1})
        days (days-of plan)
        open #{"2026-01-20" "2026-01-23"}
        _ (doseq [[date d] days :when (not (open date))]
            (is (c/doc? (c/act! *session* d :mark_eating_out nil))))
        first-day (id-of (get days "2026-01-20"))
        refusal (get-in (c/get-doc *session* (:self plan))
                        [:unavailable :finalize])]
    (is (= first-day (get-in refusal [:evidence :plan_day_id]))
        (pr-str refusal))
    (is (some #{{:door "plan_day.assign_meal" :id first-day}}
              (:resolved_remedies refusal))
        (pr-str refusal))
    (is (= "draft" (state-of plan)))))

;; which meal is the caller's: with no choice, the walk stops at the
;; day the refusal named, asking for the meal — it never guesses one
(deftest without-a-meal-the-walk-stops-at-the-named-day
  (let [{:keys [day plan glist]} (chain! "2026-01-27" true)
        res (c/pursue! *session* glist :finalize nil {:dry-run true})
        assign (some #(when (= "plan_day.assign_meal" (:door %)) %)
                     (:blocked-on res))]
    (is (nil? (:done res)) (pr-str res))
    (is (= (id-of day) (last (str/split (str (:row assign)) #"/")))
        (pr-str res))
    (is (= [:meal_id] (mapv keyword (:needs assign))) (pr-str res))
    (is (empty? (:writes res)))
    (is (= "undecided" (state-of day)))
    (is (= "draft" (state-of plan)))
    (is (= "draft" (state-of glist)))))
