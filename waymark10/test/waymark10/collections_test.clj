(ns waymark10.collections-test
  "Phase-7 acceptance: the collection contract, driven as ring request
  maps through the real handler. Obligations: every filterable field
  round-trips (the filter's promise holds on every returned item,
  checked through a follow-up GET), sortable fields order both ways,
  pagination walks the full set exactly once, unknown filter/sort
  params are one 422 naming them, the total is the real filtered
  count, and facet counts sum to an independent recount.

  Suite-local kind :visit provokes what the fixtures don't declare:
  int eq/in/range/ne filters, date :after/:before filters, a
  presence (:set) and substring (:contains) filter over a nullable
  notes field, two sortable fields plus both engine timestamps, and a
  filterable REF (the meal served) — whose query param must advertise
  its target, so a filter by reference is a pick from the target's
  rows and not an id typed from memory.

  Suite-local kind :ask is the collection-defaults kind: it opens
  newest-first over created_at (a column no schema entry names) and on
  a declared default filter, so the obligations a hiding default must
  meet — advertised on the query schema, echoed in the summary,
  spelled in the self href a person copies, overridable, and clearable
  with an empty value — are checked here rather than trusted."
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
             [:party [:int {:min 1 :max 20}]]
             [:notes {:optional true} [:maybe [:string {:max 200}]]]
             [:meal_id {:optional true :filter #{:eq} :kind :meal}
              [:maybe :waymark/ref]]]
    :filterable {:state #{:eq :in}
                 :guest #{:contains}
                 :arrives_on #{:eq :range :after :before}
                 :party #{:eq :in :range :ne}
                 :notes #{:ne :set :contains}}
    ;; the engine's own timestamps sort beside the promoted fields —
    ;; neither is a schema entry, and neither promotes a column
    :sortable {:fields [:arrives_on :party :created_at :updated_at]
               :default "arrives_on"}
    :actions
    {:finish {:from #{:booked} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A finished visit is history."}}}}))

(def ask
  "The collection-defaults kind: nothing here is sortable but the
  engine's clock, and the page opens on the asks still waiting."
  (r/resource
   {:kind :ask
    :plural "asks"
    :states [:offered :approved :denied]
    :initial :offered
    :terminal #{:approved :denied}
    :summary "{data.topic} · {state}"
    :schema [:map
             [:topic [:string {:min 1 :max 60}]]
             [:owner [:string {:min 1 :max 60}]]]
    :filterable {:state #{:eq :in}
                 :owner #{:eq}}
    :sortable {:fields [:created_at] :default "-created_at"}
    :default-filters {:state "offered"}
    :actions
    {:approve {:from #{:offered} :to :approved
               :safety {:idempotent true :reversible false :confirm false
                        :one-way "An approved ask is history."}}
     :deny {:from #{:offered} :to :denied
            :safety {:idempotent true :reversible false :confirm false
                     :one-way "A denied ask stays on record."}}}}))

(def ticket
  "The views kind: a triage :deck (both gestures reversible via their
  :undo pairs, the where naming the states the deck drains) and a
  sequential :feed — what the envelope must advertise beside its
  actions and links."
  (r/resource
   {:kind :ticket
    :states [:pending :approved :flagged]
    :initial :pending
    :terminal #{}
    :summary "{data.title} · {state}"
    :schema [:map [:title [:string {:min 1 :max 100}]]]
    :filterable {:state #{:eq :in}}
    :flow [[:pending :approve :approved {:undo :unapprove
                                         :display {:label "Approve"}}]
           [:approved :unapprove :pending {:undo :approve}]
           [:pending :flag :flagged {:undo :unflag
                                     :display {:label "Flag"}}]
           [:flagged :unflag :pending {:undo :flag}]]
    :views [{:name :triage :kind :deck :where {:state "pending"}
             :right :approve :left :flag
             :card [:title] :display {:label "Triage"}}
            {:name :review :kind :feed :where {:state "pending"}
             :display {:label "Review"}}]}))

(def ^:dynamic *h* nil)

(declare seed!)

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table ["meals" "plans" "visits" "asks" "tickets"
                           "definitions"
                           "waymark10_transitions" "waymark10_idempotency"
                           "waymark10_drafts"]]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table " CASCADE")]))))
        (binding [*h* (engine/handler
                       (engine/engine {:storage st
                                       :resources [fx/meal fx/plan visit ask
                                                   ticket]}))]
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
  [["Ana" "2026-07-10" 2 "100% window seat"]
   ["Bo" "2026-07-12" 4 "aisle"]
   ["Cyd" "2026-07-14" 2 nil]
   ["Dee" "2026-07-16" 6 nil]
   ["Eli" "2026-07-18" 3 "window"]])

;; topic, owner, and the verdict (if any) each ask is born with,
;; oldest first
(def ask-specs
  [["Read the ledger" "ana" "approve"]
   ["Write the ledger" "bo" nil]
   ["Drop the ledger" "cyd" "deny"]
   ["Read the roster" "ana" nil]])

;; the seeded meals by name — the ref filter needs a real target id
(def ^:private meal-ids (atom {}))

(defn- seed! []
  (doseq [[name' themes accept?] meal-specs]
    (let [resp (req :post "/api/meals" {:name name' :themes themes})]
      (assert (= 201 (:status resp)) (:body resp))
      (swap! meal-ids assoc name' (id-of resp))
      (when accept?
        (req :post (str "/api/meals/" (id-of resp) "/-/accept")))))
  (doseq [[guest arrives party notes] visit-specs]
    (let [resp (req :post "/api/visits"
                    (cond-> {:guest guest :arrives_on arrives :party party}
                      notes (assoc :notes notes)
                      ;; one visit dines on a declared meal
                      (= "Ana" guest) (assoc :meal_id (@meal-ids "Brisket"))))]
      (assert (= 201 (:status resp)) (:body resp))))
  (doseq [[start conflicts] [["2026-06-01" nil] ["2026-06-08" 2]
                             ["2026-06-15" nil] ["2026-06-22" 0]]]
    (let [resp (req :post "/api/plans"
                    (cond-> {:start_date start :weeks 1
                             :days [{:date start :eating_out true}]}
                      conflicts (assoc :calendar_conflicts conflicts)))]
      (assert (= 201 (:status resp)) (:body resp))))
  ;; asks in a known birth order, two of them judged — the judged ones
  ;; are what a default filter hides, so the suite can prove they are
  ;; still reachable
  (doseq [[topic owner verdict] ask-specs]
    (let [resp (req :post "/api/asks" {:topic topic :owner owner})]
      (assert (= 201 (:status resp)) (:body resp))
      (when verdict
        (req :post (str "/api/asks/" (id-of resp) "/-/" verdict))))))

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

(defn- total [resp] (get-in (json resp) [:data :total]))

(deftest new-filter-ops-round-trip
  (testing "ne excludes matches; a comma list negates as not-in"
    (is (= 3 (total (get-q "/api/visits" "party_ne=2"))))
    (is (= 2 (total (get-q "/api/visits" "party_ne=2,6")))))

  (testing "a row without the field never answers ne (SQL NULL)"
    (is (= 2 (total (get-q "/api/visits" "notes_ne=aisle")))
        "Cyd and Dee carry no notes — NULL fails <> too"))

  (testing "date :before is strictly less"
    (let [b (json (get-q "/api/visits" "arrives_on_before=2026-07-14"))]
      (is (= 2 (get-in b [:data :total])))
      (is (every? #(neg? (compare (:arrives_on (item-data %)) "2026-07-14"))
                  (get-in b [:data :items]))))
    (is (= 1 (total (get-q "/api/visits"
                           (str "arrives_on_after=2026-07-10"
                                "&arrives_on_before=2026-07-14"))))
        "after and before compose to an exclusive window"))

  (testing "set answers presence both ways"
    (is (= 3 (total (get-q "/api/visits" "notes_set=true"))))
    (is (= 2 (total (get-q "/api/visits" "notes_set=false")))
        "absent fields answer set=false — the one op NULL satisfies"))

  (testing "contains is case-insensitive substring"
    (is (= 1 (total (get-q "/api/visits" "guest_contains=AN"))))
    (is (= 2 (total (get-q "/api/visits" "notes_contains=window")))))

  (testing "contains treats LIKE wildcards as literals"
    (is (= 1 (total (get-q "/api/visits" "notes_contains=100%25%20window")))
        "a literal % in the value matches itself")
    (is (= 0 (total (get-q "/api/visits" "notes_contains=1_0")))
        "an unescaped _ would match any character and hit \"100\"")))

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

;; ── 3b. the engine's timestamps sort like any other field ───────────

(defn- guests [b] (mapv #(get-in % [:fields :guest]) (get-in b [:data :items])))
(defn- topics [b] (mapv #(get-in % [:fields :topic]) (get-in b [:data :items])))

(deftest sortable-timestamps-order-by-the-engine-column
  (let [birth-order (mapv first visit-specs)]
    (testing "created_at names no schema field, promotes no column, and
              still orders both ways — the visits come back in the
              order they were created, and reversed"
      (is (= birth-order (guests (json (get-q "/api/visits" "sort=created_at")))))
      (is (= (reverse birth-order)
             (guests (json (get-q "/api/visits" "sort=-created_at"))))))
    (testing "updated_at orders too — untouched rows keep their birth order"
      (is (= birth-order (guests (json (get-q "/api/visits" "sort=updated_at"))))))
    (testing "…and a touched row moves to the end of it"
      (let [id (last (str/split (:self (first (get-in (json (get-q "/api/visits" "sort=created_at"))
                                                      [:data :items])))
                                #"/"))]
        (req :post (str "/api/visits/" id "/-/finish"))
        (is (= (conj (vec (rest birth-order)) (first birth-order))
               (guests (json (get-q "/api/visits" "sort=updated_at"))))
            "Ana was just finished, so her row updated last")))
    (testing "the sort enum advertises them beside the promoted fields"
      (let [props (get-in (json (req :get "/api/visits"))
                          [:actions :query :input :properties])]
        (is (= ["-arrives_on" "-created_at" "-party" "-updated_at"
                "arrives_on" "created_at" "party" "updated_at"]
               (sort (get-in props [:sort :enum]))))
        (is (nil? (:created_at props))
            "a sortable timestamp is not a filter param — it has no
             schema entry to type-check a value against")))))

(deftest a-schema-field-may-not-shadow-an-engine-timestamp
  (doseq [f [:created_at :updated_at]]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"shadows the engine column"
         (r/resource
          {:kind :shadow_probe
           :states [:booked :done]
           :initial :booked
           :terminal #{:done}
           :summary "{data.guest} · {state}"
           :schema [:map [:guest [:string {:min 1 :max 60}]]
                    [f {:optional true} [:maybe :waymark/instant]]]
           :actions
           {:finish {:from #{:booked} :to :done
                     :safety {:idempotent true :reversible false
                              :confirm false
                              :one-way "A finished probe is history."}}}}))
        (str f " must not shadow the engine column"))))

;; ── 3c. default filters: applied, advertised, and escapable ─────────

(deftest default-filter-opens-the-page-and-says-so
  (let [b (json (req :get "/api/asks"))]
    (testing "the declared default filters the page"
      (is (= 2 (get-in b [:data :total])) "two asks are still offered")
      (is (every? #(= "offered" (:state %)) (get-in b [:data :items]))))
    (testing "…and the default sort orders it, newest ask first"
      (is (= ["Read the roster" "Write the ledger"] (topics b))))
    (testing "the summary echoes the filter it applied"
      (is (str/includes? (:summary b) "filtered: state=offered")))
    (testing "self carries the applied filter — the URL a person copies
              is the view they saw"
      (is (str/includes? (:self b) "state=offered"))
      (let [[uri q] (str/split (:self b) #"\?" 2)
            again (json (get-q uri q))]
        (is (= (topics b) (topics again)) "and it round-trips to itself")))
    (testing "the query schema advertises it, exactly as sort's rides sort"
      (let [props (get-in b [:actions :query :input :properties])]
        (is (= "offered" (get-in props [:state :default])))
        (is (= "-created_at" (get-in props [:sort :default])))))))

(deftest explicit-always-beats-the-default
  (testing "another value on the same field overrides"
    (let [b (json (get-q "/api/asks" "state=denied"))]
      (is (= 1 (get-in b [:data :total])))
      (is (= ["Drop the ledger"] (topics b)))
      (is (str/includes? (:self b) "state=denied"))
      (is (not (str/includes? (:self b) "state=offered")))))
  (testing "an empty value clears the default rather than re-substituting it"
    (let [b (json (get-q "/api/asks" "state="))]
      (is (= 4 (get-in b [:data :total])) "every ask, judged or not")
      (is (not (str/includes? (:summary b) "filtered:"))
          "nothing was filtered, so nothing is echoed")))
  (testing "an explicit sort beats the declared default sort"
    (is (= ["Write the ledger" "Read the roster"]
           (topics (json (get-q "/api/asks" "state=offered&sort=created_at"))))))
  (testing "a filter on a DIFFERENT field leaves the default standing"
    (let [b (json (get-q "/api/asks" "owner=ana"))]
      (is (= 1 (get-in b [:data :total]))
          "ana asked twice; one ask is already approved")
      (is (= ["Read the roster"] (topics b)))
      (is (str/includes? (:self b) "state=offered")))))

(deftest a-default-filter-the-door-would-refuse-is-a-definition-error
  (let [probe (fn [defaults]
                {:kind :default_probe
                 :states [:offered :done]
                 :initial :offered
                 :terminal #{:done}
                 :summary "{data.topic} · {state}"
                 :schema [:map [:topic [:string {:min 1 :max 60}]]
                          [:party [:int {:min 1 :max 20}]]
                          [:note {:optional true} [:maybe [:string {:max 60}]]]]
                 :filterable {:state #{:eq :in}
                              :party #{:eq}
                              :note #{:contains}}
                 :default-filters defaults
                 :actions
                 {:finish {:from #{:offered} :to :done
                           :safety {:idempotent true :reversible false
                                    :confirm false
                                    :one-way "A finished probe is history."}}}})]
    (is (some? (r/resource (probe {:state "offered"}))) "the good one stands")
    (is (some? (r/resource (probe {:state :offered})))
        "a keyword value is the same law, spelled in Clojure")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not a state"
                          (r/resource (probe {:state "pending"}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be an integer"
                          (r/resource (probe {:party "many"}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an :eq/:in-filterable"
                          (r/resource (probe {:note "ledger"})))
        "a substring-only field has no equality param to default")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not an :eq/:in-filterable"
                          (r/resource (probe {:nothing "here"}))))))

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
           ["party_ne=abc" :party_ne]
           ["arrives_on_before=not-a-date" :arrives_on_before]
           ["notes_set=maybe" :notes_set]
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
    (testing "the ne/before/set/contains params advertise their shapes"
      (let [vprops (get-in (json (req :get "/api/visits"))
                           [:actions :query :input :properties])]
        (is (= {:type "integer" :x-in true} (:party_ne vprops))
            "ne keeps the field's type and takes a comma list")
        (is (= {:type "string" :format "date"} (:arrives_on_before vprops)))
        (is (= {:type "boolean"} (:notes_set vprops)))
        (is (= {:type "string"} (:guest_contains vprops)))
        (is (nil? (:guest vprops))
            "contains alone never opens the bare eq param")))
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

(deftest ref-filters-advertise-their-target
  (let [vprops (get-in (json (req :get "/api/visits"))
                       [:actions :query :input :properties])]
    (testing "a ref's filter param carries the ref's own declaration —
              the client offers the target's rows by label, the way
              the form's picker does, instead of asking for an id"
      (is (= {:type "string" :format "waymark-ref" :x-ref {:kind "meal"}}
             (:meal_id vprops))))
    (testing "…and the param it advertises is the one that filters"
      (let [b (json (get-q "/api/visits"
                           (str "meal_id=" (@meal-ids "Brisket"))))]
        (is (= 1 (get-in b [:data :total])))
        (is (= "Ana" (get-in (first (get-in b [:data :items]))
                             [:fields :guest])))))))

(deftest fields-on-collection-items
  (let [items (get-in (json (get-q "/api/visits" "sort=arrives_on"))
                      [:data :items])]
    (testing "every item carries real field values, not just state+summary"
      (is (= 5 (count items)))
      (is (= (mapv first visit-specs) (mapv #(get-in % [:fields :guest]) items)))
      (is (= (mapv (comp str second) visit-specs)
             (mapv #(get-in % [:fields :arrives_on]) items)))
      (is (= (mapv #(nth % 2) visit-specs)
             (mapv #(get-in % [:fields :party]) items))))
    (testing "no item's fields carry a vector or prose-widget field"
      ;; :visit has none declared — the exclusion itself is covered
      ;; by waymark10.batch-a-fixtures' ba_ticket (a real prose field)
      ;; and ba_roster (a real vector field); this just confirms a
      ;; kind with neither still gets exactly its plain scalar fields
      ;; (:notes only where seeded, :meal_id only on Ana's visit — an
      ;; absent optional never renders, ref or not)
      (is (= (mapv (fn [[guest _ _ notes]]
                     (cond-> #{:guest :arrives_on :party}
                       notes (conj :notes)
                       (= "Ana" guest) (conj :meal_id)))
                   visit-specs)
             (mapv #(set (keys (:fields %))) items))))))

;; ── 7. the declared op set is closed ────────────────────────────────

(deftest a-typo-d-filter-op-refuses-at-boot
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"not a filter op"
       (r/resource
        {:kind :typo_probe
         :states [:booked :done]
         :initial :booked
         :terminal #{:done}
         :summary "{data.guest} · {state}"
         :schema [:map [:guest [:string {:min 1 :max 60}]]]
         :filterable {:guest #{:eq :contians}}
         :actions
         {:finish {:from #{:booked} :to :done
                   :safety {:idempotent true :reversible false
                            :confirm false
                            :one-way "A finished probe is history."}}}}))))

;; ── 7b. declared views advertise on the envelope ────────────────────

(deftest views-advertise-on-the-collection-envelope
  (let [_ (req :post "/api/tickets" {:title "Fix the door"})
        resp (req :get "/api/tickets")
        b (json resp)]
    (is (= 200 (:status resp)))
    (testing "the envelope carries both declared views, wire-shaped"
      (is (= [{:name "triage" :kind "deck"
               :where {:state "pending"}
               :card ["title"]
               :gestures {:right {:action "approve" :label "Approve"}
                          :left {:action "flag" :label "Flag"}}
               :display {:label "Triage"}}
              {:name "review" :kind "feed"
               :where {:state "pending"}
               :display {:label "Review"}}]
             (:views b))))
    (testing "a feed advertises no gestures"
      (is (not (contains? (second (:views b)) :gestures))))
    (testing "the per-item affordances still gate the gestures — the
              view names actions, the item carries them"
      (let [item (first (get-in b [:data :items]))]
        (is (contains? (:actions item) :approve))
        (is (contains? (:actions item) :flag))))
    (testing "a kind declaring no views advertises none"
      (is (not (contains? (json (req :get "/api/visits")) :views))))))

;; ── 8. filtered self round-trips ────────────────────────────────────

(deftest filtered-self-round-trips
  (let [b (json (get-q "/api/visits" "party=2&sort=-arrives_on"))
        [uri q] (str/split (:self b) #"\?" 2)
        again (json (get-q uri q))]
    (is (= (:self b) (:self again)) "the canonical self is a fixed point")
    (is (= (map :self (get-in b [:data :items]))
           (map :self (get-in again [:data :items]))))
    (is (= (get-in b [:data :total]) (get-in again [:data :total])))))
