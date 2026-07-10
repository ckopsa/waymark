(ns waymark10.collections-test
  "Phase-7 acceptance: the collection contract, driven as ring request
  maps through the real handler. Obligations: every filterable field
  round-trips (the filter's promise holds on every returned item,
  checked through a follow-up GET), sortable fields order both ways,
  pagination walks the full set exactly once, unknown filter/sort
  params are one 422 naming them, the total is the real filtered
  count, and facet counts sum to an independent recount.

  Suite-local kind :visit provokes what the fixtures don't declare:
  int eq/in/range filters, a date :after filter, and two sortable
  fields."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.fixtures :as fx]
            [waymark10.resource :as r]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.conformance :as conf]
            [waymark10.test.db :as db]
            [waymark10.wire :as wire]))

(def visit
  (r/resource
   {:kind :visit
    :states [:booked :done]
    :initial :booked
    :terminal #{:done}
    :summary "{data.guest} · {state}"
    :schema [:map
             [:guest [:string {:min 1 :max 60}]]
             [:arrives_on :waymark/date]
             [:party [:int {:min 1 :max 20}]]]
    :filterable {:state #{:eq :in}
                 :arrives_on #{:eq :range :after}
                 :party #{:eq :in :range}}
    :sortable {:fields [:arrives_on :party] :default "arrives_on"}
    :actions
    {:finish {:from #{:booked} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A finished visit is history."}}}}))

(def ^:dynamic *h* nil)

(declare seed!)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["meals" "plans" "visits" "definitions"
                           "waymark10_transitions" "waymark10_idempotency"
                           "waymark10_drafts"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*h* (engine/handler
                       (engine/engine {:storage st
                                       :resources [fx/meal fx/plan visit]}))]
          (seed!)
          (f))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(defn- req
  ([method uri] (req method uri nil nil))
  ([method uri body] (req method uri body nil))
  ([method uri body headers]
   (*h* (cond-> {:request-method method
                 :uri uri
                 :headers (merge {"x-waymark-principal" "colton"} headers)}
          body (assoc :body (wire/write-json body))))))

(defn- get-q [uri query]
  (*h* {:request-method :get :uri uri :query-string query
        :headers {"x-waymark-principal" "colton"}}))

(defn- json [resp] (some-> (:body resp) wire/read-json))
(defn- id-of [resp] (last (str/split (:self (json resp)) #"/")))

(defn- item-data
  "The follow-up GET per item: collection items carry no data, the
  round-trip obligation reads it from self."
  [item]
  (:data (json (req :get (:self item)))))

;; ── the seeded world ────────────────────────────────────────────────
;; meals: 5, two accepted (on_list); themes overlap for facet sums
;; visits: 5, spread over dates and party sizes

(def meal-specs
  [["Tacos al pastor" ["mexican" "family"] true]
   ["Brisket" ["bbq" "family"] true]
   ["Ramen" ["soup"] false]
   ["Pulled pork" ["bbq"] false]
   ["Minestrone" ["soup" "veggie"] false]])

(def visit-specs
  [["Ana" "2026-07-10" 2]
   ["Bo" "2026-07-12" 4]
   ["Cyd" "2026-07-14" 2]
   ["Dee" "2026-07-16" 6]
   ["Eli" "2026-07-18" 3]])

(defn- seed! []
  (doseq [[name' themes accept?] meal-specs]
    (let [resp (req :post "/api/meals" {:name name' :themes themes})]
      (assert (= 201 (:status resp)) (:body resp))
      (when accept?
        (req :post (str "/api/meals/" (id-of resp) "/-/accept")))))
  (doseq [[guest arrives party] visit-specs]
    (let [resp (req :post "/api/visits" {:guest guest :arrives_on arrives
                                         :party party})]
      (assert (= 201 (:status resp)) (:body resp))))
  (doseq [[start conflicts] [["2026-06-01" nil] ["2026-06-08" 2]
                             ["2026-06-15" nil] ["2026-06-22" 0]]]
    (let [resp (req :post "/api/plans"
                    (cond-> {:start_date start :weeks 1
                             :days [{:date start :eating_out true}]}
                      conflicts (assoc :calendar_conflicts conflicts)))]
      (assert (= 201 (:status resp)) (:body resp)))))

;; ── 1. the envelope shape ───────────────────────────────────────────

(deftest collection-envelope-shape
  (let [violations
        (vec
         (for [[kind uri total] [[:meal "/api/meals" 5]
                                 [:plan "/api/plans" 4]
                                 [:visit "/api/visits" 5]]
               :let [resp (req :get uri)
                     b (json resp)]
               v (concat
                  (when (not= 200 (:status resp))
                    [(str uri ": GET " (:status resp))])
                  (when (not= "application/waymark+json"
                              (get-in resp [:headers "Content-Type"]))
                    [(str uri ": Content-Type "
                          (get-in resp [:headers "Content-Type"]))])
                  (conf/collection-envelope-violations b {:kind kind})
                  (when (not= total (get-in b [:data :total]))
                    [(str uri ": total " (get-in b [:data :total])
                          ", seeded " total)])
                  (when (not= uri (:self b))
                    [(str uri ": unfiltered self is " (:self b))]))]
           v))]
    (is (empty? violations) (str/join "\n" violations))))

;; ── 2. filters round-trip ───────────────────────────────────────────

(deftest filters-round-trip
  (testing "state=on_list — every item shows the state, total is the count"
    (let [b (json (get-q "/api/meals" "state=on_list"))]
      (is (= 2 (get-in b [:data :total])))
      (is (every? #(= "on_list" (:state %)) (get-in b [:data :items])))
      (is (str/includes? (:summary b) "filtered: state=on_list"))))

  (testing "state=a,b in-list"
    (let [b (json (get-q "/api/meals" "state=on_list,suggested"))]
      (is (= 5 (get-in b [:data :total])))))

  (testing "themes membership (JSONB containment on the vocab array)"
    (let [b (json (get-q "/api/meals" "themes=bbq"))]
      (is (= 2 (get-in b [:data :total])))
      (is (every? #(some #{"bbq"} (:themes (item-data %)))
                  (get-in b [:data :items]))))
    (let [b (json (get-q "/api/meals" "themes=bbq,soup"))]
      (is (= 4 (get-in b [:data :total])) "any-of membership")))

  (testing "date range on plans"
    (let [b (json (get-q "/api/plans"
                         "start_date_gte=2026-06-08&start_date_lte=2026-06-15"))]
      (is (= 2 (get-in b [:data :total])))
      (doseq [item (get-in b [:data :items])
              :let [d (:start_date (item-data item))]]
        (is (and (<= (compare "2026-06-08" d) 0)
                 (<= (compare d "2026-06-15") 0))
            (str d " outside the filtered range")))))

  (testing "a derived boolean filters honestly"
    (let [b (json (get-q "/api/plans" "has_conflicts=true"))]
      (is (= 1 (get-in b [:data :total])))
      (is (= true (:has_conflicts (item-data (first (get-in b [:data :items]))))))))

  (testing "int eq, in-list and range"
    (is (= 2 (get-in (json (get-q "/api/visits" "party=2")) [:data :total])))
    (let [b (json (get-q "/api/visits" "party=2,6"))]
      (is (= 3 (get-in b [:data :total])))
      (is (every? #(contains? #{2 6} (:party (item-data %)))
                  (get-in b [:data :items]))))
    (is (= 2 (get-in (json (get-q "/api/visits" "party_gte=4"))
                     [:data :total]))))

  (testing "date :after is strictly greater"
    (let [b (json (get-q "/api/visits" "arrives_on_after=2026-07-14"))]
      (is (= 2 (get-in b [:data :total])))
      (is (every? #(pos? (compare (:arrives_on (item-data %)) "2026-07-14"))
                  (get-in b [:data :items])))))

  (testing "filters compose"
    (is (= 1 (get-in (json (get-q "/api/visits"
                                  "party=2&arrives_on_after=2026-07-10"))
                     [:data :total])))))

;; ── 3. sort ─────────────────────────────────────────────────────────

(defn- shown [b field]
  (mapv #(get (item-data %) field) (get-in b [:data :items])))

(deftest sortable-orders
  (testing "ascending and descending over each declared field"
    (let [asc (shown (json (get-q "/api/visits" "sort=party")) :party)
          desc (shown (json (get-q "/api/visits" "sort=-party")) :party)]
      (is (= (sort asc) asc))
      (is (= (reverse (sort desc)) desc))
      (is (= (frequencies asc) (frequencies desc))))
    (let [asc (shown (json (get-q "/api/visits" "sort=arrives_on")) :arrives_on)]
      (is (= (sort asc) asc)))
    (let [names (shown (json (get-q "/api/meals" "sort=name")) :name)]
      (is (= (sort names) names))))

  (testing "the declared default orders without a param (plan: -start_date)"
    (let [dates (shown (json (req :get "/api/plans")) :start_date)]
      (is (= (reverse (sort dates)) dates)))))

;; ── 4. pagination walks the set exactly once ────────────────────────

(deftest pagination-walks-once
  (let [page (fn [n] (json (get-q "/api/visits"
                                  (str "page%5Bsize%5D=2&page%5Bnumber%5D=" n))))
        pages [(page 1) (page 2) (page 3)]
        ids (mapcat (fn [b] (map :self (get-in b [:data :items]))) pages)]
    (is (every? #(= 5 (get-in % [:data :total])) pages)
        "the total holds steady across pages")
    (is (= [2 2 1] (mapv #(count (get-in % [:data :items])) pages)))
    (is (= 5 (count ids)))
    (is (= (count ids) (count (distinct ids))) "no row appears twice")
    (testing "links appear away from the edges and vanish at them"
      (is (some? (get-in (nth pages 0) [:links :next])))
      (is (nil? (get-in (nth pages 0) [:links :prev])))
      (is (some? (get-in (nth pages 1) [:links :next])))
      (is (some? (get-in (nth pages 1) [:links :prev])))
      (is (nil? (get-in (nth pages 2) [:links :next])))
      (is (some? (get-in (nth pages 2) [:links :prev]))))
    (testing "the next link round-trips to the next page"
      (let [href (get-in (nth pages 0) [:links :next :href])
            [uri q] (str/split href #"\?" 2)
            b (json (get-q uri q))]
        (is (= (map :self (get-in (nth pages 1) [:data :items]))
               (map :self (get-in b [:data :items]))))))
    (testing "self carries the applied params"
      (let [b (page 2)]
        (is (str/includes? (:self b) "page%5Bnumber%5D=2"))))))

;; ── 5. the 422 surface ──────────────────────────────────────────────

(deftest unknown-and-malformed-params-refuse
  (doseq [[query bad-param]
          [["bogus=1" :bogus]
           ["sort=bogus" :sort]
           ["sort=name" :sort]              ; plan-only spelling on visits
           ["start_date_gte=not-a-date" :start_date_gte]
           ["party=abc" :party]
           ["state=nope" :state]
           ["page%5Bsize%5D=0" (keyword "page[size]")]
           ["page%5Bsize%5D=101" (keyword "page[size]")]
           ["page%5Bnumber%5D=0" (keyword "page[number]")]]]
    (let [uri (if (str/starts-with? query "start_date") "/api/plans" "/api/visits")
          resp (get-q uri query)
          b (json resp)]
      (is (= 422 (:status resp)) (str query " answered " (:status resp)))
      (is (= "application/problem+json"
             (get-in resp [:headers "Content-Type"])))
      (is (contains? (:errors b) bad-param)
          (str query " errors " (pr-str (:errors b)) " do not name " bad-param))))
  (testing "one 422 names every bad parameter at once"
    (let [b (json (get-q "/api/visits" "bogus=1&party=abc"))]
      (is (= #{:bogus :party} (set (keys (:errors b))))))))

;; ── 6. the query affordance and facets ──────────────────────────────

(deftest query-schema-and-facets
  (let [b (json (req :get "/api/meals"))
        input (get-in b [:actions :query :input])
        props (:properties input)]
    (testing "the generated grammar advertises exactly the declared surface"
      (is (= "object" (:type input)))
      (is (false? (:additionalProperties input)))
      (is (= ["on_list" "retired" "suggested"] (sort (get-in props [:state :enum]))))
      (is (true? (get-in props [:state :x-in])))
      (is (= ["-name" "name"] (sort (get-in props [:sort :enum]))))
      (is (= "name" (get-in props [:sort :default])))
      (is (= 100 (get-in props [(keyword "page[size]") :maximum])))
      (is (= 25 (get-in props [(keyword "page[size]") :default]))))
    (testing "range/after params advertise typed bounds (visit)"
      (let [vprops (get-in (json (req :get "/api/visits"))
                           [:actions :query :input :properties])]
        (is (= {:type "string" :format "date"} (:arrives_on_gte vprops)))
        (is (= {:type "string" :format "date"} (:arrives_on_after vprops)))
        (is (= "integer" (get-in vprops [:party_gte :type])))))
    (testing "facet counts are the real GROUP BY, and sum to a recount"
      (let [facets (get-in props [:themes :x-facets])
            expected (frequencies (mapcat second meal-specs))]
        (is (= (into {} (map (fn [[k v]] [(keyword k) v])) expected)
               facets))
        (is (= (reduce + (vals expected)) (reduce + (vals facets)))
            "facet counts sum to the recounted theme assignments")
        (is (= (sort (map name (keys facets)))
               (get-in props [:themes :enum]))
            "the observed values become the dynamic vocabulary enum")))
    (testing "facets narrow with the filter's own WHERE"
      (let [fb (json (get-q "/api/meals" "state=on_list"))
            facets (get-in fb [:actions :query :input :properties :themes :x-facets])]
        (is (= {:bbq 1 :family 2 :mexican 1} facets))))))

;; ── 7. filtered self round-trips ────────────────────────────────────

(deftest filtered-self-round-trips
  (let [b (json (get-q "/api/visits" "party=2&sort=-arrives_on"))
        [uri q] (str/split (:self b) #"\?" 2)
        again (json (get-q uri q))]
    (is (= (:self b) (:self again)) "the canonical self is a fixed point")
    (is (= (map :self (get-in b [:data :items]))
           (map :self (get-in again [:data :items]))))
    (is (= (get-in b [:data :total]) (get-in again [:data :total])))))
