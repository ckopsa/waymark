(ns waymark10.pursue-test
  "GRAIL 1/3: pursue! reaches a goal by following refusal remedies,
  proven over the ring handler against a trimmed copy of mealplan10's
  chain — grocery_list.create → plan-is-planned → plan.finalize →
  day-is-covered → plan_day.assign_meal → meal-is-listed → meal.accept
  — plus a latch whose remedy names its own door (the cycle)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.client :as c]
            [waymark10.fixtures :as fx]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.types :as t]))

;; ── the chain's guards: cross-kind reads, so the render probe (no
;; :read) declines and the POST answers the refusal with its remedies

(g/defguard plan-is-planned
  {:reads [:plan]
   :explain "Finalize the meal plan first — the grocery list follows from it."
   :remedies [:plan/finalize]}
  [row inp ctx]
  (if-some [read (:read ctx)]
    (let [plan (read :plan (or (:plan_id inp) (get-in row [:data :plan_id])))]
      (if (= "planned" (some-> plan :state name)) (t/allow) (t/deny)))
    (t/allow)))

(g/defguard day-is-covered
  {:reads [:plan_day]
   :explain "Every day needs a meal before finalizing."
   :remedies [:plan_day/assign_meal]}
  [row _inp ctx]
  (if-some [read (:read ctx)]
    (if (every? #(= "planned" (some-> (read :plan_day %) :state name))
                (keep #(get-in row [:data %]) [:day_id :day2_id]))
      (t/allow)
      (t/deny))
    (t/allow)))

(g/defguard meal-is-listed
  {:judges [:meal_id] :reads [:meal]
   :explain "That meal is not on the meal list yet."
   :open "Any meal on the list."
   :remedies [:meal/accept]}
  [_row inp ctx]
  (if-some [read (:read ctx)]
    (let [meal (read :meal (:meal_id inp))]
      (if (= "on_list" (some-> meal :state name)) (t/allow) (t/deny)))
    (t/allow)))

;; pure over the row, so the render probe judges it: the refusal rides
;; the envelope's unavailable entry, remedy and all
(def latch-free
  (g/expr {:name :latch-free
           :when '(= (data :free) true)
           :explain "The latch is stuck."
           :remedies [:latch/lift]}))

;; a remedy that lands and changes nothing: the step bound's loop
(def hatch-heard
  (g/expr {:name :hatch-heard
           :when '(= (data :heard) true)
           :explain "Nobody answers the knock."
           :remedies [:hatch/knock]}))

(r/defhandler assign-meal-handler [row inp _ctx]
  (assoc-in row [:data :meal_id] (:meal_id inp)))

(def plan-day
  (r/resource
   {:kind :plan_day
    :states [:undecided :planned]
    :initial :undecided
    :summary "{data.label} · {state}"
    :schema [:map
             [:label [:string {:max 40}]]
             [:meal_id {:optional true} [:maybe [:string {:max 80}]]]]
    :actions
    {:assign_meal {:from #{:undecided} :to :planned
                   :input [:map [:meal_id [:string {:min 1 :max 80}]]]
                   :guards [meal-is-listed]
                   :safety fx/routine
                   :handler assign-meal-handler}
     :clear_day {:from #{:planned} :to :undecided
                 :safety fx/routine}}}))

(def plan
  (r/resource
   {:kind :plan
    :states [:draft :planned]
    :initial :draft
    :summary "Plan · {state}"
    :schema [:map
             [:day_id [:string {:max 80}]]
             [:day2_id {:optional true} [:maybe [:string {:max 80}]]]]
    :actions
    {:finalize {:from #{:draft} :to :planned
                :guards [day-is-covered]
                :safety fx/routine}
     :reopen {:from #{:planned} :to :draft
              :safety fx/routine}}}))

(def grocery-list
  (r/resource
   {:kind :grocery_list
    :states [:draft :done]
    :initial :draft
    :summary "Groceries · {state}"
    :schema [:map [:plan_id [:string {:max 80}]]]
    :create-guards [plan-is-planned]
    :actions
    {:finish {:from #{:draft} :to :done :safety fx/routine}
     :reopen {:from #{:done} :to :draft :safety fx/routine}}}))

(def latch
  (r/resource
   {:kind :latch
    :states [:shut :open]
    :initial :shut
    :summary "Latch · {state}"
    :schema [:map [:free {:optional true} [:maybe :boolean]]]
    :actions
    {:lift {:from #{:shut} :to :open
            :guards [latch-free]
            :safety fx/routine}
     :lower {:from #{:open} :to :shut :safety fx/routine}}}))

(def hatch
  (r/resource
   {:kind :hatch
    :states [:shut :open]
    :initial :shut
    :summary "Hatch · {state}"
    :schema [:map [:heard {:optional true} [:maybe :boolean]]]
    :actions
    {:swing {:from #{:shut} :to :open
             :guards [hatch-heard]
             :safety fx/routine}
     :knock {:from #{:shut} :to :shut :safety fx/routine}
     :close {:from #{:open} :to :shut :safety fx/routine}}}))

(def resources [fx/meal plan-day plan grocery-list latch hatch])

(def ^:dynamic *session* nil)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table (concat (map #(store/definition-checked-name (:plural %))
                                       resources)
                                  ["definitions" "waymark10_transitions"
                                   "waymark10_idempotency" "waymark10_drafts"])]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (let [h (engine/handler
                 (engine/engine {:storage st :resources resources}))]
          (binding [*session* (c/connect "http://test"
                                         {:principal "priya" :handler h})]
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

(defn- chain!
  "A suggested meal, an undecided day, a draft plan over that day."
  []
  ;; fresh inputs each call: the session's key-store replays an
  ;; identical create, and every test needs rows of its own
  (let [n (subs (str (random-uuid)) 0 8)
        meal (make! :meal {:name (str "Tacos " n) :themes ["test"]})
        day (make! :plan_day {:label (str "Monday " n)})]
    {:meal meal :day day :plan (make! :plan {:day_id (id-of day)})}))

(defn- resolver
  "The test's :resolve: which row each remedy acts on — and, for the
  day, which meal, when one was chosen."
  [{:keys [meal day plan]} meal-id]
  (fn [door _refused]
    (case door
      "plan.finalize" {:id (id-of plan)}
      "plan_day.assign_meal" (cond-> {:id (id-of day)}
                               meal-id (assoc :input {:meal_id meal-id}))
      "meal.accept" {:id (id-of meal)}
      nil)))

(defn- pursue-list! [{:keys [plan]} opts]
  (c/pursue! *session* (coll :grocery_list) :create {:plan_id (id-of plan)} opts))

(def chain-doors
  ["meal.accept" "plan_day.assign_meal" "plan.finalize" "grocery_list.create"])

(deftest pursue-drives-the-chain-end-to-end
  (let [{:keys [meal day plan] :as rows} (chain!)
        res (pursue-list! rows {:resolve (resolver rows (id-of meal))})]
    (is (c/doc? (:done res)) (pr-str res))
    (is (= "grocery_list" (:kind (:done res))))
    (is (= chain-doors (mapv :door (:writes res)))
        "deepest remedy first, then each door it unblocked")
    (is (= "on_list" (state-of meal)))
    (is (= "planned" (state-of day)))
    (is (= "planned" (state-of plan)))))

(deftest a-missing-choice-comes-back-as-blocked-on
  (let [{:keys [meal day plan] :as rows} (chain!)
        res (pursue-list! rows {:resolve (resolver rows nil)})
        choice (first (:blocked-on res))]
    (is (nil? (:done res)))
    (is (= 1 (count (:blocked-on res))) (pr-str res))
    (is (= "plan_day.assign_meal" (:door choice)))
    (is (= [:meal_id] (:needs choice)))
    (is (= (:self day) (:row choice)))
    (is (= ["grocery_list.create" "plan.finalize"] (mapv :door (:stack res)))
        "the stack names the doors waiting on the choice")
    (testing "a blocked rehearsal writes nothing"
      (is (= "suggested" (state-of meal)))
      (is (= "undecided" (state-of day)))
      (is (= "draft" (state-of plan))))))

(deftest without-resolve-a-cross-kind-remedy-is-a-choice
  (let [{:keys [plan] :as rows} (chain!)
        res (pursue-list! rows {})
        choice (first (:blocked-on res))]
    (is (= ["plan.finalize"] (mapv :door (:blocked-on res))) (pr-str res))
    (is (nil? (:row choice)) "nobody said which plan")
    (is (= "draft" (state-of plan)))))

(deftest the-depth-bound-stops-a-branch
  (let [{:keys [meal plan] :as rows} (chain!)
        res (pursue-list! rows {:resolve (resolver rows (id-of meal))
                                :max-depth 2})
        choice (first (:blocked-on res))]
    (is (= "plan_day.assign_meal" (:door choice)) (pr-str res))
    (is (= :depth (:reason choice)))
    (is (= "suggested" (state-of meal)))
    (is (= "draft" (state-of plan)))))

(deftest the-cycle-check-stops-a-branch
  (let [lt (make! :latch {:free false})
        res (c/pursue! *session* lt :lift nil)
        choice (first (:blocked-on res))]
    (is (= "latch.lift" (:door choice)) (pr-str res))
    (is (= :cycle (:reason choice)))
    (is (= (:self lt) (:row choice)))
    (is (= "shut" (state-of lt)))))

(deftest the-rehearsal-writes-nothing
  (let [{:keys [meal day plan] :as rows} (chain!)
        res (pursue-list! rows {:resolve (resolver rows (id-of meal))
                                :dry-run true})]
    (is (:rehearsal res) (pr-str res))
    (is (= chain-doors (mapv :door (:writes res)))
        "the rehearsal reports every write it would make")
    (is (empty? (:blocked-on res)))
    (is (:first-estimate res) "a rehearsal before any write is an estimate")
    (is (= "suggested" (state-of meal)))
    (is (= "undecided" (state-of day)))
    (is (= "draft" (state-of plan)))))

;; ── re-rehearsal: a door that needs one remedy on several rows

(defn- week!
  "A suggested meal, two undecided days, a draft plan over both."
  []
  (let [n (subs (str (random-uuid)) 0 8)
        meal (make! :meal {:name (str "Soup " n) :themes ["test"]})
        d1 (make! :plan_day {:label (str "Monday " n)})
        d2 (make! :plan_day {:label (str "Tuesday " n)})]
    {:meal meal :days [d1 d2]
     :plan (make! :plan {:day_id (id-of d1) :day2_id (id-of d2)})}))

(defn- week-resolver
  "assign_meal acts on the first day still undecided, with the meal
  `meals` (day self → meal id) chose for it, if any."
  [{:keys [meal days]} meals]
  (fn [door _refused]
    (case door
      "plan_day.assign_meal"
      (when-some [d (first (filter #(= "undecided" (state-of %)) days))]
        (cond-> {:id (id-of d)}
          (get meals (:self d)) (assoc :input {:meal_id (get meals (:self d))})))
      "meal.accept" {:id (id-of meal)}
      nil)))

(deftest a-remedy-needed-twice-lands-in-one-call
  (let [{:keys [meal days plan] :as rows} (week!)
        m (id-of meal)
        res (c/pursue! *session* plan :finalize nil
                       {:resolve (week-resolver rows (zipmap (map :self days) [m m]))})]
    (is (c/doc? (:done res)) (pr-str res))
    (is (= ["meal.accept" "plan_day.assign_meal" "plan_day.assign_meal" "plan.finalize"]
           (mapv :door (:writes res))))
    (is (= (mapv :self days)
           (mapv :row (filter #(= "plan_day.assign_meal" (:door %)) (:writes res))))
        "one assign_meal per day, each found by a re-rehearsal")
    (is (= ["planned" "planned"] (mapv state-of days)))
    (is (= "planned" (state-of plan)))))

(deftest a-choice-found-mid-run-comes-back-with-the-steps-taken
  (let [{:keys [meal days plan] :as rows} (week!)
        [d1 d2] days
        res (c/pursue! *session* plan :finalize nil
                       {:resolve (week-resolver rows {(:self d1) (id-of meal)})})
        choice (first (:blocked-on res))]
    (is (nil? (:done res)) (pr-str res))
    (is (not (:rehearsal res)) "the first rehearsal reached the goal, so the run began")
    (is (= ["meal.accept" "plan_day.assign_meal"] (mapv :door (:writes res))))
    (is (= "plan_day.assign_meal" (:door choice)))
    (is (= [:meal_id] (:needs choice)))
    (is (= (:self d2) (:row choice)))
    (is (= ["plan.finalize"] (mapv :door (:stack res))))
    (is (= ["planned" "undecided"] (mapv state-of [d1 d2])))
    (is (= "draft" (state-of plan)))))

(deftest the-step-bound-stops-a-loop-that-lands-without-progress
  (let [hx (make! :hatch {:heard false})
        res (c/pursue! *session* hx :swing nil {:max-depth 2})]
    (is (= {:step-bound 8} (:stopped res)) (pr-str res))
    (is (seq (:writes res)))
    (is (every? #{"hatch.knock"} (map :door (:writes res))))
    (is (= "shut" (state-of hx)))))
