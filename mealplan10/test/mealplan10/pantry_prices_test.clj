(ns mealplan10.pantry-prices-test
  "The pantry-prices story, end to end over the real ring handler —
  mealplan9's four dogfood narratives (test_pantry_prices.py), ported
  with their numbers intact:

  1. the family learns what dinner costs — ingredient + product
     lifecycle, birth-sighting rollups, same-day upsert, absorb,
     the retire warning, and the no-write staleness flip;
  2. the grocery list knows what it costs — AI-stamped estimates,
     the upsert, and the plan's same-commit rollup;
  3. a meal knows what it potentially costs — lines price at create,
     reprice follows the price world, grams and removal move totals;
  4. a substitute prices the unpriceable — suggested prices nothing,
     accepted prices honestly (priced_via), a direct price wins the
     line back, and the swap consumes the acceptance.

  Each narrative builds its own rows (the :once world is shared and
  kaocha shuffles vars), so counts are asserted per-ingredient, never
  globally. Needs the waymark10_test database; WAYMARK10_TEST_DSN
  overrides."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [mealplan10.event-source :as es]
            [mealplan10.main :as main]
            [next.jdbc :as jdbc]
            [waymark10.server.engine :as engine]
            [waymark10.server.maintainer :as maintainer]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire])
  (:import (java.time Instant)))

;; ── the world ───────────────────────────────────────────────────────

(def ^:private tables
  ["meals" "meal_lines" "rotations" "plans" "grocery_lists" "prep_tasks"
   "ingredients" "products" "substitutions" "events"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(def ^:dynamic *eng* nil)
(def ^:dynamic *h* nil)
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
                                  :now-fn (fn [] @clock)})]
          (binding [*eng* eng
                    *h* (engine/handler eng)
                    *clock* clock]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

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

(defn- act!
  ([self action] (act! self action nil {}))
  ([self action body] (act! self action body {}))
  ([self action body headers]
   (let [resp (req :post (str self "/-/" (name action)) body headers)]
     (is (= 200 (:status resp))
         (str self " " (name action) ": " (:status resp) " " (:body resp)))
     (json resp))))

(defn- refuse! [self action body status]
  (let [resp (req :post (str self "/-/" (name action)) body)]
    (is (= status (:status resp))
        (str self " " (name action) " answered " (:status resp)
             ", wanted " status ": " (:body resp)))
    (json resp)))

(defn- get-env [self & [query]]
  (json (req :get (cond-> self query (str "?" query)))))

(defn- etag-of [self]
  (get-in (req :get self) [:headers "ETag"]))

(defn- fresh-key [] {"idempotency-key" (str (random-uuid))})

(defn- id-of [env] (last (str/split (:self env) #"/")))

(defn- active-ingredient! [nm & [body]]
  (let [i (created! "ingredients" (merge {:name nm} body))]
    (act! (:self i) :accept)))

;; ── 1. the family learns what dinner costs ──────────────────────────

(deftest the-family-learns-what-dinner-costs
  (let [ing (active-ingredient! "Chicken thighs (n1)"
                                {:aliases ["chkn thighs"]
                                 :category "meat"
                                 :preferred_stores ["costco" "winco"]})
        iid (id-of ing)]
    (testing "the accepted ingredient wears its defaults"
      (is (= "g" (get-in ing [:data :unit]))
          "the unit default filled at the door"))
    (let [kirkland (created!
                    "products"
                    {:ingredient_id iid :store "costco"
                     :name "Kirkland Organic Chicken Thighs (n1)"
                     :package_grams 2720
                     :sightings [{:seen_on "2026-07-01" :price_cents 1899
                                  :source "receipt"}]})]
      (testing "the birth sighting's rollups are already computed"
        (is (= 1899 (get-in kirkland [:data :latest_price_cents])))
        (is (= 70 (get-in kirkland [:data :cents_per_100g])))
        (is (= "2026-07-01" (get-in kirkland [:data :last_seen_on])))
        (is (false? (get-in kirkland [:data :price_is_stale])))
        (is (= 1 (get-in kirkland [:data :sightings 0 :quantity]))
            "the sighting's quantity default filled — item-level")
        (is (= "Chicken thighs (n1)"
               (get-in kirkland [:data :ingredient_name]))
            "the engine wrote the ref's label"))
      (act! (:self kirkland) :confirm_match)
      (testing "a same-day re-record replaces, never duplicates"
        (act! (:self kirkland) :record_sighting
              {:seen_on "2026-07-08" :price_cents 2099 :source "scrape"})
        (let [after (act! (:self kirkland) :record_sighting
                          {:seen_on "2026-07-08" :price_cents 1999
                           :source "scrape"})]
          (is (= 2 (count (get-in after [:data :sightings]))))
          (is (= 1999 (get-in after [:data :latest_price_cents])))
          (is (= 73 (get-in after [:data :cents_per_100g])))))
      (let [winco (created!
                   "products"
                   {:ingredient_id iid :store "winco"
                    :name "WinCo thighs (n1)" :package_grams 1000
                    :sightings [{:seen_on "2026-07-07" :price_cents 899
                                 :source "receipt"}]})]
        (act! (:self winco) :confirm_match)
        (testing "the collection answers the trip questions — no client math"
          (let [by-store (get-env "/api/products"
                                  (str "ingredient_id=" iid "&store=costco"))]
            (is (= [(:self kirkland)]
                   (mapv :self (get-in by-store [:data :items])))))
          (let [sorted (get-env "/api/products"
                                (str "ingredient_id=" iid
                                     "&sort=cents_per_100g"))]
            (is (= [(:self kirkland) (:self winco)]
                   (mapv :self (get-in sorted [:data :items]))))))
        (testing "the preferred-store membership filter finds the trip"
          (let [trip (get-env "/api/ingredients" "preferred_stores=costco")]
            (is (some #(= (:self ing) (:self %))
                      (get-in trip [:data :items])))))
        (testing "absorb: the duplicate's products repoint, its aliases
                  fold in, and it retires — one call, whole truth"
          (let [dup (active-ingredient! "Chicken thigh (n1 dup)")
                stray (created! "products"
                                {:ingredient_id (id-of dup) :store "smiths"
                                 :name "Smiths thighs (n1)"})
                env (act! (:self ing) :absorb {:duplicate_id (id-of dup)})]
            (is (some #{"Chicken thigh (n1 dup)"}
                      (get-in env [:data :aliases]))
                "the duplicate's name folded into the survivor's aliases")
            (is (= 3 (get-in env [:data :products_tracked]))
                "the response already counts the repointed products")
            (let [stray' (get-env (:self stray))]
              (is (= iid (get-in stray' [:data :ingredient_id])))
              (is (= "tracked" (:state stray'))
                  "absorb's rematch repoints AND confirms"))
            (is (= "retired" (:state (get-env (:self dup)))))
            (testing "and the absorb advertised its blast radius"
              (is (= [{:kind "product" :action "rematch" :may true}
                      {:kind "ingredient" :action "retire"}]
                     (get-in env [:actions :absorb :touches]))))))
        (testing "retiring an ingredient with tracked products warns — 409"
          (refuse! (:self ing) :retire nil 409))
        (testing "the ingredient page embeds how stores sell it"
          (let [env (get-env (:self ing))]
            (is (= 3 (get-in env [:links :products :badge])))
            (is (= 3 (count (get-in env [:links :products :embedded]))))))
        (testing "staleness flips on the clock, with no write"
          (let [versions (mapv #(get-in (get-env %) [:meta :version])
                               [(:self kirkland) (:self winco)])]
            (reset! *clock* (Instant/parse "2026-09-01T12:00:00Z"))
            (maintainer/sweep-clocks! *eng*)
            (let [stale (get-env "/api/products"
                                 (str "ingredient_id=" iid
                                      "&state=tracked&price_is_stale=true"))]
              (is (= 3 (get-in stale [:data :total]))
                  "every tracked product of the trip went stale"))
            (is (= versions (mapv #(get-in (get-env %) [:meta :version])
                                  [(:self kirkland) (:self winco)]))
                "the flip wrote no versions")
            (reset! *clock* (Instant/parse "2026-07-08T12:00:00Z"))
            (maintainer/sweep-clocks! *eng*)))))))

;; ── 2. the grocery list knows what it costs ─────────────────────────

(deftest the-grocery-list-knows-what-it-costs
  (let [thighs (active-ingredient! "Chicken thighs (n2)")
        plan (created! "plans" {:start_date "2026-01-13" :weeks 1})
        glist (created! "grocery_lists" {:plan_id (id-of plan)})]
    (testing "a fresh list totals zero"
      (is (= 0 (get-in glist [:data :estimated_total_cents])))
      (is (= 0 (get-in glist [:data :priced_items])))
      (is (= 0 (get-in glist [:data :total_items]))))
    (act! (:self glist) :add_item
          {:name "Chicken thighs" :ingredient_id (id-of thighs)
           :est_cost_cents 4298})
    (let [env (act! (:self glist) :add_item {:name "Cilantro"})]
      (testing "the totals move in the same response"
        (is (= 4298 (get-in env [:data :estimated_total_cents])))
        (is (= 1 (get-in env [:data :priced_items])))
        (is (= 2 (get-in env [:data :total_items])))))
    (testing "add_item is the upsert — a re-add replaces the estimate"
      (let [env (act! (:self glist) :add_item
                      {:name "Chicken thighs" :est_cost_cents 2149})]
        (is (= 2149 (get-in env [:data :estimated_total_cents])))
        (is (= 2 (get-in env [:data :total_items])))))
    (testing "the plan rolled the week up in the same commit"
      (let [penv (get-env (:self plan))]
        (is (= 2149 (get-in penv [:data :est_grocery_cost_cents])))
        (is (= 1 (get-in penv [:data :priced_grocery_items])))
        (is (= 2 (get-in penv [:data :total_grocery_items])))))))

;; ── 3. a meal knows what it potentially costs ───────────────────────

(deftest a-meal-knows-what-it-potentially-costs
  ;; NO preferred stores here — the cheapest unit price must win when
  ;; the family hasn't stated a preference
  (let [thighs (active-ingredient! "Chicken thighs (n3)")
        sauce (active-ingredient! "BBQ sauce (n3)")
        kirkland (created! "products"
                           {:ingredient_id (id-of thighs) :store "costco"
                            :name "Kirkland thighs (n3)" :package_grams 2720
                            :sightings [{:seen_on "2026-07-01"
                                         :price_cents 1899
                                         :source "receipt"}]})
        _ (act! (:self kirkland) :confirm_match)
        meal (created! "meals" {:name "BBQ chicken thighs (n3)"
                                :themes ["bbq"]})
        _ (act! (:self meal) :accept)
        line (created! "meal_lines" {:meal_id (id-of meal)
                                     :ingredient_id (id-of thighs)
                                     :grams 1400})]
    (testing "the line priced itself at create — (ingredient, grams)
              is enough"
      (is (= 980 (get-in line [:data :est_cost_cents])))   ; 1400×70÷100
      (is (true? (get-in line [:data :priced])))
      (is (nil? (get-in line [:data :priced_via])))
      (is (= "BBQ chicken thighs (n3)" (get-in line [:data :meal_name]))))
    (let [blank (created! "meal_lines" {:meal_id (id-of meal)
                                        :ingredient_id (id-of sauce)
                                        :grams 250})]
      (testing "an unpriceable line stays blank, honestly"
        (is (nil? (get-in blank [:data :est_cost_cents]))))
      (testing "the meal's rollups tell the whole recipe"
        (let [menv (get-env (:self meal))]
          (is (= 980 (get-in menv [:data :est_cost_cents])))
          (is (= 1 (get-in menv [:data :priced_ingredients])))
          (is (= 2 (get-in menv [:data :total_ingredients])))
          (is (= 2 (get-in menv [:links :ingredients :badge])))
          (is (= 2 (count (get-in menv [:links :ingredients :embedded]))))))
      (testing "a cheaper offer lands; meal.reprice fans it out"
        (let [winco (created! "products"
                              {:ingredient_id (id-of thighs) :store "winco"
                               :name "WinCo thighs (n3)" :package_grams 1000
                               :sightings [{:seen_on "2026-07-07"
                                            :price_cents 500
                                            :source "receipt"}]})]
          (act! (:self winco) :confirm_match))
        (act! (:self meal) :reprice nil (fresh-key))
        (is (= 700 (get-in (get-env (:self line)) [:data :est_cost_cents]))
            "1400 g × 50 ¢/100 g")
        (is (= 700 (get-in (get-env (:self meal))
                           [:data :est_cost_cents]))))
      (testing "set_grams re-prices — a fenced edit, the etag speaks"
        (let [env (act! (:self line) :set_grams {:grams 700}
                        {"if-match" (etag-of (:self line))})]
          (is (= 350 (get-in env [:data :est_cost_cents])))))
      (testing "removing a line drops it from the totals; the row stays"
        (act! (:self blank) :remove)
        (let [menv (get-env (:self meal))]
          (is (= 1 (get-in menv [:data :total_ingredients])))
          (is (= 350 (get-in menv [:data :est_cost_cents])))
          (is (= 1 (count (get-in menv [:links :ingredients :embedded]))))
          (is (= "removed" (:state (get-env (:self blank))))))))))

;; ── 4. a substitute prices the unpriceable ──────────────────────────

(deftest a-substitute-prices-the-unpriceable
  (let [butter (active-ingredient! "Butter (n4)")
        margarine (active-ingredient! "Margarine (n4)")
        oil (active-ingredient! "Vegetable oil (n4)")
        marg-prod (created! "products"
                            {:ingredient_id (id-of margarine) :store "winco"
                             :name "WinCo margarine (n4)" :package_grams 454
                             :sightings [{:seen_on "2026-07-01"
                                          :price_cents 349
                                          :source "receipt"}]})
        _ (act! (:self marg-prod) :confirm_match)
        meal (created! "meals" {:name "Pie crust (n4)" :themes ["baking"]})
        _ (act! (:self meal) :accept)]
    (testing "self-substitution is unrepresentable at birth — 409"
      (let [resp (req :post "/api/substitutions"
                      {:from_ingredient_id (id-of butter)
                       :to_ingredient_id (id-of butter)})]
        (is (= 409 (:status resp)) (str (:body resp)))))
    (let [sub (created! "substitutions"
                        {:from_ingredient_id (id-of butter)
                         :to_ingredient_id (id-of margarine)})
          line (created! "meal_lines" {:meal_id (id-of meal)
                                       :ingredient_id (id-of butter)
                                       :grams 200})]
      (testing "the ratio default filled; the distinct fact holds"
        (is (= 1 (get-in sub [:data :ratio])))
        (is (true? (get-in sub [:data :distinct]))))
      (testing "a SUGGESTED substitution prices nothing"
        (is (nil? (get-in line [:data :est_cost_cents]))))
      (act! (:self sub) :accept)
      (testing "accepted, it prices the line — and says so"
        (let [env (act! (:self line) :reprice nil (fresh-key))]
          (is (= 154 (get-in env [:data :est_cost_cents])))  ; 200×77÷100
          (is (= "Margarine (n4)" (get-in env [:data :priced_via])))))
      (testing "the meal rolls the honest estimate up"
        (is (= 154 (get-in (get-env (:self meal))
                           [:data :est_cost_cents]))))
      (testing "a direct price wins the line back on the next reprice"
        (let [butter-prod (created!
                           "products"
                           {:ingredient_id (id-of butter) :store "costco"
                            :name "Kirkland butter (n4)" :package_grams 1810
                            :sightings [{:seen_on "2026-07-07"
                                         :price_cents 1099
                                         :source "receipt"}]})]
          (act! (:self butter-prod) :confirm_match))
        (let [env (act! (:self line) :reprice nil (fresh-key))]
          (is (= 122 (get-in env [:data :est_cost_cents])))  ; 200×61÷100
          (is (nil? (get-in env [:data :priced_via]))
              "priced as itself again")))
      (testing "the swap: the line BECOMES the stand-in and consumes
                the acceptance"
        (let [b->oil (created! "substitutions"
                               {:from_ingredient_id (id-of butter)
                                :to_ingredient_id (id-of oil)
                                :ratio 0.8M})]
          (testing "a suggested substitution cannot swap — 409"
            (refuse! (:self line) :substitute
                     {:substitution_id (id-of b->oil)} 409))
          (act! (:self b->oil) :accept)
          (let [env (act! (:self line) :substitute
                          {:substitution_id (id-of b->oil)})]
            (is (= "Vegetable oil (n4)"
                   (get-in env [:data :ingredient_name])))
            (is (= 160 (get-in env [:data :grams])))          ; 200×0.8
            (is (nil? (get-in env [:data :est_cost_cents]))
                "oil is unpriced — blank, honestly"))
          (testing "the consumed acceptance no longer applies: the
                    butter→margarine sub against the now-oil line
                    refuses (a DIFFERENT input, so natural replay
                    cannot swallow the verdict)"
            (refuse! (:self line) :substitute
                     {:substitution_id (id-of sub)} 409)))))))
