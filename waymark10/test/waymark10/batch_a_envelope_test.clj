(ns waymark10.batch-a-envelope-test
  "Batch A acceptance: parts, links, depth and effort — the envelope
  obligations (waymark10.test.envelope-obligations) driven as ring
  requests through the real handler, over the phase-1 fixtures plus
  the link-bearing batch-a trio and one suite-local placed kind whose
  acceptance set actually excludes items (the fixtures' date guard
  admits every day, so per-item narration needs its own provocation).
  Needs a Postgres database; WAYMARK10_TEST_DSN points at it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [next.jdbc :as jdbc]
            [waymark10.batch-a-fixtures :as bafx]
            [waymark10.fixtures :as fx]
            [waymark10.guards :as g]
            [waymark10.resource :as r]
            [waymark10.server.collections :as collections]
            [waymark10.server.engine :as engine]
            [waymark10.server.store :as store]
            [waymark10.server.store.postgres :as pg]
            [waymark10.test.db :as db]
            [waymark10.test.envelope-obligations :as ob]
            [waymark10.wire :as wire]))

;; ── the suite-local placed kind: an acceptance set with teeth ───────

(def ^:private slot-open
  (g/guard {:name :slot-open
            :judges [:slot]
            :accepts (fn [row]
                       (into []
                             (keep #(when (:open %) (:slot %)))
                             (get-in row [:data :slots])))
            :explain "Slot {slot} is closed."}))

(r/defhandler book-slot [row inp _ctx]
  (update-in row [:data :slots]
             (fn [slots]
               (mapv #(if (= (:slot %) (:slot inp))
                        (assoc % :assignee (:assignee inp))
                        %)
                     slots))))

(def roster
  (r/resource
   {:kind :ba_roster
    :states [:open :done]
    :initial :open
    :terminal #{:done}
    :summary "{data.name} · {state}"
    :schema [:map
             [:name [:string {:min 1 :max 40}]]
             [:slots [:vector
                      [:map
                       [:slot [:string {:min 1 :max 10}]]
                       [:open {:optional true} [:maybe :boolean]]
                       [:assignee {:optional true}
                        [:maybe [:string {:max 40}]]]]]]]
    :part-scopes {:slots {:path :slots :key :slot}}
    :actions
    {:book {:from #{:open} :to :open
            :place :slots
            :input [:map
                    [:slot [:string {:min 1 :max 10}]]
                    [:assignee [:string {:min 1 :max 40}]]]
            :guards [slot-open]
            :safety {:idempotent true :reversible true :confirm false}
            :handler book-slot
            :display {:label "Book slot" :style :primary}}
     :finish {:from #{:open} :to :done
              :safety {:idempotent true :reversible false :confirm false
                       :one-way "A finished roster is history."}}}}))

;; ── boot ────────────────────────────────────────────────────────────

(def ^:dynamic *h* nil)

(def ^:private tables
  ["meals" "plans" "ba_projects" "ba_tickets" "ba_days" "ba_rosters"
   "definitions" "waymark10_transitions" "waymark10_idempotency"
   "waymark10_drafts"])

(use-fixtures :once
  (fn [f]
    (let [st (pg/storage db/dsn)]
      (try
        (store/with-tx st
          (fn [tx]
            (doseq [table tables]
              (jdbc/execute! tx [(str "DROP TABLE IF EXISTS " table
                                      " CASCADE")]))))
        (let [eng (engine/engine {:storage st
                                  :resources [fx/meal fx/plan
                                              bafx/ba-project bafx/ba-ticket
                                              bafx/ba-day roster]})]
          (binding [*h* (engine/handler eng)]
            (f)))
        (finally (pg/close! st))))))

;; ── request sugar ───────────────────────────────────────────────────

(def ^:private headers
  {"x-waymark-principal" "batch-a" "content-type" "application/json"})

(defn- req
  ([method uri] (req method uri nil))
  ([method uri body]
   (let [[path query] (str/split uri #"\?" 2)]
     (*h* (cond-> {:request-method method :uri path :headers headers}
            query (assoc :query-string query)
            body (assoc :body (wire/write-json body)))))))

(defn- json [resp] (some-> (:body resp) wire/read-json))

(defn- created [uri body]
  (let [resp (req :post uri body)]
    (is (= 201 (:status resp)) (str uri " → " (:status resp) " "
                                    (:body resp)))
    (json resp)))

(defn- get-json [href]
  (let [resp (req :get href)]
    {:status (:status resp) :body (json resp)}))

(defn- post-json [href body]
  (let [resp (req :post href body)]
    {:status (:status resp) :body (json resp)}))

;; ── parts ───────────────────────────────────────────────────────────

(deftest parts-are-a-refinement-with-per-item-honesty
  (let [meal (created "/api/meals" {:name "Carnitas tacos"
                                    :themes ["mexican"]})
        meal-id (last (str/split (:self meal) #"/"))
        plan (created "/api/plans"
                      {:start_date "2026-07-14" :weeks 1
                       :days [{:date "2026-07-14" :theme "mexican"}
                              {:date "2026-07-15"}]})
        env (:body (get-json (:self plan)))]
    (testing "the placed action renders top-level AND per item"
      (is (contains? (:actions env) :assign_meal))
      (is (= ["2026-07-14" "2026-07-15"]
             (mapv :key (get-in env [:parts :days :items]))))
      (is (= "date" (get-in env [:parts :days :key])))
      (is (= "2026-07-15"
             (get-in env [:parts :days :items 1 :actions :assign_meal
                          :input :properties :date :const]))
          "the key field is const-bound per item"))
    (testing "the parts shape obligation"
      (is (empty? (ob/parts-violations fx/plan env))))
    (testing "parts honesty over the wire: pre-bound keys are accepted"
      (is (empty? (ob/parts-enforcement-violations
                   env {:post post-json
                        :fill (fn [_ _] {:meal_id meal-id})}))))
    (testing "effort truth: the part binding demands a selection"
      (is (= "selection"
             (get-in env [:parts :days :items 0 :actions :assign_meal
                          :effort])))
      (is (empty? (ob/effort-violations env))))
    (testing "a row-level refusal narrates once, never per item"
      (doseq [day ["2026-07-14" "2026-07-15"]]
        (is (= 200 (:status (post-json (str (:self plan) "/-/assign_meal")
                                       {:date day :meal_id meal-id})))))
      (let [_ (is (= 200 (:status (post-json (str (:self plan) "/-/finalize")
                                             nil))))
            env' (:body (get-json (:self plan)))]
        (is (= "planned" (:state env')))
        (is (not (contains? env' :parts))
            "no parts when the placed action is out of state")
        (is (contains? (:unavailable env') :assign_meal))))))

(deftest per-item-narration-matches-enforcement
  (let [roster-env (created "/api/ba_rosters"
                            {:name "week crew"
                             :slots [{:slot "mon" :open true}
                                     {:slot "tue" :open false}
                                     {:slot "wed" :open true}]})
        env (:body (get-json (:self roster-env)))
        items (get-in env [:parts :slots :items])]
    (testing "the admitted items carry the bound action"
      (is (= ["mon" "tue" "wed"] (mapv :key items)))
      (is (contains? (get-in items [0 :actions]) :book))
      (is (contains? (get-in items [2 :actions]) :book)))
    (testing "the excluded item narrates per item, the guard's own sentence"
      (is (nil? (get-in items [1 :actions :book])))
      (is (= "Slot tue is closed."
             (get-in items [1 :unavailable :book :reason]))))
    (testing "shape and enforcement obligations"
      (is (empty? (ob/parts-violations roster env)))
      (is (empty? (ob/parts-enforcement-violations
                   env {:post post-json
                        :fill (fn [_ _] {:assignee "colton"})}))))
    (testing "the book entry's demand class is recall (open assignee)"
      (is (= "recall" (get-in items [0 :actions :book :effort]))))))

;; ── links ───────────────────────────────────────────────────────────

(defn- stage-linked-rows []
  (let [project (created "/api/ba_projects" {:name "Kitchen"})
        pid (last (str/split (:self project) #"/"))
        t1 (created "/api/ba_tickets" {:title "Sand the top"
                                       :project_id pid
                                       :due_date "2026-07-20"
                                       :points 3})
        _t2 (created "/api/ba_tickets" {:title "Oil the top"
                                        :project_id pid
                                        :due_date "2026-07-21"
                                        :points 1})
        day (created "/api/ba_days" {:date "2026-07-20" :label "Sanding day"})]
    {:project project :pid pid :t1 t1 :day day}))

(deftest links-render-badges-and-embeds
  (let [{:keys [project pid t1]} (stage-linked-rows)
        ticket-env (:body (get-json (:self t1)))
        project-env (:body (get-json (:self project)))]
    (testing "the edge link compiles onto the public filter grammar"
      (is (= "/api/ba_days?date=2026-07-20"
             (get-in ticket-env [:links :agenda :href])))
      (is (= "ba_day_collection" (get-in ticket-env [:links :agenda :kind])))
      (is (= "Days sharing this due date"
             (get-in ticket-env [:links :agenda :summary])))
      (is (= 3 (get-in ticket-env [:links :agenda :badge]))
          "the badge is the row's own materialized fact"))
    (testing "the template link resolves over the instance"
      (is (= (str "/api/ba_projects/" pid)
             (get-in ticket-env [:links :parent :href]))))
    (testing "the owns link filters the child collection by the via ref"
      (is (= (str "/api/ba_tickets?project_id=" pid)
             (get-in project-env [:links :tickets :href])))
      (is (= 2 (get-in project-env [:links :tickets :badge]))
          "the rollup fact rides as the badge"))
    (testing "embed is grid mode: capped envelope-minus-data items, total+page always present"
      (let [embedded (get-in project-env [:links :tickets :embedded])]
        (is (= 2 (count embedded)))
        (is (every? #(not (contains? % :data)) embedded))
        (is (some #(str/includes? (:summary %) "Sand the top") embedded))
        (is (= 2 (get-in project-env [:links :tickets :total]))
            "total is the real filtered count, same as a collection GET's data.total")
        (is (= {:size collections/page-size-default :number 1}
               (get-in project-env [:links :tickets :page]))
            "no override → the framework's own default page size, not the old flat cap")))
    (testing "the link object advertises its own effective bounds"
      ;; the wire boundary kebab→snakes keys: :max-limit renders as
      ;; max_limit, same as everywhere else on the wire
      (is (= {:limit collections/page-size-default :max_limit collections/page-size-max}
             (get-in project-env [:links :tickets :embed]))
          "bool-form :embed true renders its effective limits, not a bare true")
      (is (= {:limit 1 :max_limit 3}
             (get-in project-env [:links :tickets_limited :embed]))
          "map-form :embed renders its declared limits verbatim"))
    (testing "embedded items carry real field values, not just state+summary"
      (let [sanding (first (filter #(str/includes? (:summary %) "Sand the top")
                                   (get-in project-env [:links :tickets :embedded])))]
        (is (= {:title "Sand the top" :due_date "2026-07-20" :points 3}
               (:fields sanding))
            "notes (a prose field) is excluded; project_id is excluded too — it's
             locked by this embed's own href (every row already shares this
             project), the parent envelope is where it's meaningful")))
    (testing "an embed link carries the target's own filter/sort vocabulary minus its locked keys"
      (let [direct (:body (get-json (str "/api/ba_tickets?project_id=" pid)))]
        (is (= (dissoc (get-in direct [:actions :query :input :properties]) :project_id)
               (get-in project-env [:links :tickets :columns :properties]))
            "same shape a direct collection GET advertises, minus project_id —
             the embed's own href already fixes it, so filtering on it would
             refuse (422) and every row would show one repeated value")
        (is (not (contains? (get-in project-env [:links :tickets :columns :properties])
                             :project_id))
            "project_id itself never rides — nothing to pick in the Filters popover")))
    (testing "the pure and wire obligations"
      (is (empty? (ob/links-violations bafx/ba-ticket ticket-env)))
      (is (empty? (ob/links-violations bafx/ba-project project-env)))
      (is (empty? (ob/links-wire-violations ticket-env get-json)))
      (is (empty? (ob/links-wire-violations project-env get-json)))
      (is (empty? (ob/fields-violations bafx/ba-ticket ticket-env)))
      (is (empty? (ob/fields-violations bafx/ba-project project-env))))))

(deftest embed-overrides-filter-sort-and-page-through-the-parent
  (let [{:keys [project pid]} (stage-linked-rows)
        overridden (get-json (str (:self project)
                                  "?embed.tickets.state=open"
                                  "&embed.tickets.sort=points"
                                  "&embed.tickets.page[size]=1"))
        direct (get-json (str "/api/ba_tickets?project_id=" pid
                              "&state=open&sort=points&page[size]=1"))]
    (is (= 200 (:status overridden)))
    (is (= 1 (count (get-in overridden [:body :links :tickets :embedded]))))
    (is (= 2 (get-in overridden [:body :links :tickets :total]))
        "total counts every match, independent of the requested page size")
    (is (= (mapv :self (get-in direct [:body :data :items]))
           (mapv :self (get-in overridden [:body :links :tickets :embedded])))
        "the embed's override channel and the real collection endpoint answer
         the identical query the identical way — same parse-query underneath")))

(deftest embed-locked-param-refuses-and-never-leaks
  (let [{:keys [project pid]} (stage-linked-rows)
        other (created "/api/ba_projects" {:name "Garage"})
        other-pid (last (str/split (:self other) #"/"))
        _ (created "/api/ba_tickets" {:title "Sweep the floor"
                                      :project_id other-pid})
        resp (get-json (str (:self project) "?embed.tickets.project_id=" other-pid))]
    (is (= 422 (:status resp)))
    (is (contains? (:errors (:body resp)) :embed.tickets.project_id))))

(deftest embed-unknown-rel-refuses
  (let [{:keys [project]} (stage-linked-rows)
        resp (get-json (str (:self project) "?embed.nonexistent.state=open"))]
    (is (= 422 (:status resp)))
    (is (contains? (:errors (:body resp)) :embed.nonexistent))))

(deftest embed-max-limit-refuses
  (let [{:keys [project]} (stage-linked-rows)
        resp (get-json (str (:self project) "?embed.tickets_limited.page[size]=5"))]
    (is (= 422 (:status resp))
        "5 exceeds tickets_limited's own declared :max-limit 3, distinct from
         parse-query's global 100")
    ;; page[size]'s brackets aren't valid in a bare keyword literal
    ;; (the reader treats [ ] as terminating macro chars) — build it
    (is (contains? (:errors (:body resp))
                   (keyword "embed.tickets_limited.page[size]")))))

(deftest embed-unfilterable-field-refuses
  (let [{:keys [project]} (stage-linked-rows)
        resp (get-json (str (:self project) "?embed.tickets.title=Sand"))]
    (is (= 422 (:status resp))
        "title isn't declared :filterable on ba_ticket — \"if the column
         supports it\" is enforced, not just documented")
    (is (contains? (:errors (:body resp)) :title))))

(deftest a-null-join-value-omits-the-link
  (let [{:keys [pid]} (stage-linked-rows)
        t (created "/api/ba_tickets" {:title "No due date yet"
                                      :project_id pid})
        env (:body (get-json (:self t)))]
    (is (nil? (get-in env [:links :agenda]))
        "a row with no boundary relates to nothing")
    (is (some? (get-in env [:links :parent])))
    (is (empty? (ob/links-violations bafx/ba-ticket env)))))

;; ── depth ───────────────────────────────────────────────────────────

(deftest depth-summary-and-rows-none
  (let [plan (created "/api/plans"
                      {:start_date "2026-08-04" :weeks 1
                       :days [{:date "2026-08-04"}]})
        full (:body (get-json (:self plan)))
        summary (:body (get-json (str (:self plan) "?depth=summary")))]
    (testing "summary = envelope minus data/parts, actions complete"
      (is (empty? (ob/depth-violations {:full full :summary summary}))))
    (testing "a summary drops :data but keeps :self — the prefill door"
      ;; the action dialog prefills from doc.data, and a collection row
      ;; is exactly this projection; :self is how it fetches the rest
      ;; rather than opening an edit form blank
      (is (nil? (:data summary)))
      (is (some? (:data full)))
      (is (= (:self full) (:self summary))))
    (testing "depth=full is the default spelling"
      (is (= full (:body (get-json (str (:self plan) "?depth=full"))))))
    (testing "an unknown depth is one 422 naming the parameter"
      (let [resp (req :get (str (:self plan) "?depth=deep"))]
        (is (= 422 (:status resp)))
        (is (contains? (:errors (json resp)) :depth))))
    (testing "rows=none items carry explicit nulls and resolve on GET"
      (let [resp (req :get "/api/plans?rows=none")
            body (json resp)
            items (get-in body [:data :items])]
        (is (= 200 (:status resp)))
        (is (seq items))
        (doseq [item items]
          (is (empty? (ob/rows-none-violations item get-json))))
        ;; the raw wire really says null, not absent
        (is (str/includes? (:body resp) "\"actions\":null"))))
    (testing "an unknown rows value is one 422 naming the parameter"
      (let [resp (req :get "/api/plans?rows=some")]
        (is (= 422 (:status resp)))
        (is (contains? (:errors (json resp)) :rows))))))

;; ── effort ──────────────────────────────────────────────────────────

(deftest effort-classes-across-the-fixture-kinds
  (let [meal (created "/api/meals" {:name "Traeger brisket"
                                    :themes ["bbq"]})
        env (:body (get-json (:self meal)))]
    (testing "assent: no input, one click"
      (is (= "assent" (get-in env [:actions :accept :effort]))))
    (is (empty? (ob/effort-violations env)))
    (let [_ (is (= 200 (:status (post-json (str (:self meal) "/-/accept")
                                           nil))))
          env' (:body (get-json (:self meal)))]
      (testing "composition: the prose recipe field"
        (is (= "composition"
               (get-in env' [:actions :update_recipe :effort]))))
      (is (empty? (ob/effort-violations env')))))
  (let [{:keys [t1]} (stage-linked-rows)
        env (:body (get-json (:self t1)))]
    (testing "recall: an open numeric field"
      (is (= "recall" (get-in env [:actions :estimate :effort]))))
    (is (empty? (ob/effort-violations env)))))
